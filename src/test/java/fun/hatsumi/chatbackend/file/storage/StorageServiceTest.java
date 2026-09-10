package fun.hatsumi.chatbackend.file.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;

/**
 * 存储服务安全测试：路径穿越拒绝、块写入/合并/摘要。
 */
class StorageServiceTest {

    @TempDir
    static Path tempDir;

    static StorageService storage;

    @BeforeAll
    static void setUp() {
        ChatroomProperties properties = new ChatroomProperties();
        properties.setStorageRoot(tempDir.toString());
        storage = new StorageService(properties);
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
}
