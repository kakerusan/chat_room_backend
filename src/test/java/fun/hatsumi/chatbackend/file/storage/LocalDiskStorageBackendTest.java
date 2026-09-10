package fun.hatsumi.chatbackend.file.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;

/**
 * 本地磁盘存储后端测试：路径穿越拒绝、块写入/合并/摘要、Range 读取、key 生成。
 */
class LocalDiskStorageBackendTest {

    @TempDir
    static Path tempDir;

    static LocalDiskStorageBackend storage;

    @BeforeAll
    static void setUp() {
        ChatroomProperties properties = new ChatroomProperties();
        properties.setStorageRoot(tempDir.toString());
        storage = new LocalDiskStorageBackend(properties);
    }

    @AfterAll
    static void tearDown() {
        // 清理合并产物，避免 Windows 下 TempDir 删除被文件锁阻塞
        storage.cleanupTemp("test-upload");
        storage.cleanupTemp("partial-upload");
    }

    @Test
    @DisplayName("路径穿越被拒绝：../ 逃出 storage root")
    void pathTraversalRejected() {
        assertThrows(BusinessException.class, () -> storage.resolve("../outside.txt"));
        assertThrows(BusinessException.class, () -> storage.resolve("public/../../etc/passwd"));
        assertThrows(BusinessException.class, () -> storage.resolve("a/b/../../../escape"));
    }

    @Test
    @DisplayName("合法相对路径可解析且位于 root 内")
    void legitPathResolved() {
        Path resolved = storage.resolve("public/some-file.bin");
        assertTrue(resolved.startsWith(tempDir.toAbsolutePath().normalize()));
    }

    @Test
    @DisplayName("分块写入 + 顺序合并 + 完整 SHA-256 正确")
    void writeAndMergeChunks() {
        String uploadId = "test-upload";
        byte[] chunk0 = "0123456789".getBytes();
        byte[] chunk1 = "abcdefg".getBytes();

        assertDoesNotThrow(() -> storage.writeChunk(uploadId, 0, chunk0));
        assertDoesNotThrow(() -> storage.writeChunk(uploadId, 1, chunk1));

        Path target = tempDir.resolve("merged.bin");
        String sha = storage.mergeChunks(uploadId, 2, target);

        // 手工计算预期摘要
        String expected = fun.hatsumi.chatbackend.file.service.UploadService.sha256Hex(
                ("0123456789" + "abcdefg").getBytes());
        assertEquals(expected, sha);
        assertEquals(chunk0.length + chunk1.length, storage.size("merged.bin"));
    }

    @Test
    @DisplayName("缺块合并报冲突")
    void mergeMissingChunkRejected() {
        storage.writeChunk("partial-upload", 0, new byte[]{1});
        assertThrows(BusinessException.class,
                () -> storage.mergeChunks("partial-upload", 2, tempDir.resolve("x.bin")));
    }

    @Test
    @DisplayName("清理临时目录")
    void cleanupTemp() {
        storage.writeChunk("cleanup-upload", 0, new byte[]{1});
        storage.cleanupTemp("cleanup-upload");
        assertTrue(!Files.exists(tempDir.resolve("temp/cleanup-upload")));
    }

    @Test
    @DisplayName("openStream 支持 Range 精确定位（start/end）与全量读取")
    void openStreamRange() throws Exception {
        String key = "range-test.bin";
        byte[] data = "0123456789abcdefghij".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve(key), data);

        // 全量
        try (InputStream in = storage.openStream(key, null, null)) {
            assertEquals("0123456789abcdefghij", readAll(in));
        }
        // bytes=4-7 → "4567"
        try (InputStream in = storage.openStream(key, 4L, 7L)) {
            assertEquals("4567", readAll(in));
        }
        // bytes=10- （到末尾）
        try (InputStream in = storage.openStream(key, 10L, null)) {
            assertEquals("abcdefghij", readAll(in));
        }
        // 位置必须精确等于 start
        try (InputStream in = storage.openStream(key, 20L, null)) {
            assertEquals("", readAll(in));
        }
    }

    @Test
    @DisplayName("newTargetKey：scope 目录正确、扩展名净化、无路径分隔符")
    void newTargetKeyRules() {
        String pub = StorageBackend.newTargetKey("PUBLIC", null, "report.pdf");
        assertTrue(pub.startsWith("public/"), pub);
        assertTrue(pub.endsWith(".pdf"));

        String priv = StorageBackend.newTargetKey("PRIVATE", 42L, "photo.png");
        assertTrue(priv.startsWith("users/42/"), priv);

        String unsafe = StorageBackend.newTargetKey("PUBLIC", null, "..\\evil<script>.exe");
        String name = unsafe.substring(unsafe.lastIndexOf('/') + 1);
        assertTrue(name.matches("[0-9]+-[0-9a-f]{8}(\\.[a-zA-Z0-9.]{1,16})?"), unsafe);
    }

    @Test
    @DisplayName("completeUpload：合并 → 原子落位 → 返回 key 与 SHA-256")
    void completeUploadFlow() {
        String uploadId = "complete-flow-upload";
        byte[] part0 = "hello ".getBytes(StandardCharsets.UTF_8);
        byte[] part1 = "world".getBytes(StandardCharsets.UTF_8);
        storage.writeChunk(uploadId, 0, part0);
        storage.writeChunk(uploadId, 1, part1);

        String targetKey = "public/" + uploadId + ".txt";
        StorageBackend.CompletedUpload completed = storage.completeUpload(uploadId, null, targetKey,
                List.of(new StorageBackend.UploadedPart(0, null),
                        new StorageBackend.UploadedPart(1, null)));

        assertEquals(targetKey, completed.key());
        assertEquals(fun.hatsumi.chatbackend.file.service.UploadService.sha256Hex(
                "hello world".getBytes(StandardCharsets.UTF_8)), completed.sha256Hex());
        assertEquals(11, storage.size(targetKey));
        assertTrue(storage.exists(targetKey));

        storage.delete(targetKey);
        assertTrue(!storage.exists(targetKey));
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }
}
