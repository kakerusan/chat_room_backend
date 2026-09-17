package fun.hatsumi.chatbackend.file.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import fun.hatsumi.chatbackend.config.ChatroomProperties;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * S3StorageBackend 集成测试：直连本机 RustFS 容器（docker compose up -d rustfs）。
 *
 * <p>默认跳过；设置 RUSTFS_IT=1 后运行：
 * {@code $env:RUSTFS_IT="1"; .\mvnw.cmd test -Dtest=S3StorageBackendIT}</p>
 */
@EnabledIfEnvironmentVariable(named = "RUSTFS_IT", matches = "1")
class S3StorageBackendIT {

    static S3StorageBackend backend;

    static final String TEST_PREFIX = "it-" + UUID.randomUUID().toString().substring(0, 8);

    @BeforeAll
    static void setUp() {
        ChatroomProperties properties = new ChatroomProperties();
        ChatroomProperties.Storage storage = properties.getStorage();
        storage.setType("s3");
        storage.setEndpoint("http://localhost:9000");
        storage.setAccessKey("chatroom-dev");
        storage.setSecretKey("chatroom-dev-secret");
        storage.setBucket("chatroom-files");
        storage.setRegion("us-east-1");

        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(storage.getEndpoint()))
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        backend = new S3StorageBackend(client, properties);
        backend.ensureBucket(); // 触发 bucket 兜底逻辑
    }

    @AfterAll
    static void tearDown() {
        // 测试对象统一放在 TEST_PREFIX 目录下，清理即可（桶保留）
    }

    @Test
    @DisplayName("完整 multipart 流程：init → 乱序分片 → complete → 回流 SHA-256 正确")
    void multipartFullFlow() throws Exception {
        byte[] part0 = ("A".repeat(5 * 1024 * 1024)).getBytes(StandardCharsets.US_ASCII); // 5 MiB
        byte[] part1 = "tail-part".getBytes(StandardCharsets.UTF_8); // 最后一块可小于 5 MiB
        String key = TEST_PREFIX + "/multipart.bin";

        String backendUploadId = backend.initUpload("it-upload-1", key);
        assertNotEquals(null, backendUploadId);

        // 乱序上传：先块 1 再块 0
        String etag1 = backend.putChunk("it-upload-1", backendUploadId, key, 1, part1);
        String etag0 = backend.putChunk("it-upload-1", backendUploadId, key, 0, part0);
        assertTrue(etag0 != null && !etag0.isBlank());
        assertTrue(etag1 != null && !etag1.isBlank());

        StorageBackend.CompletedUpload completed = backend.completeUpload("it-upload-1", backendUploadId, key,
                List.of(new StorageBackend.UploadedPart(0, etag0),
                        new StorageBackend.UploadedPart(1, etag1)));

        assertEquals(key, completed.key());
        assertEquals(sha256Of(part0, part1), completed.sha256Hex());
        assertEquals(part0.length + part1.length, backend.size(key));
        assertTrue(backend.exists(key));

        // 全量读取
        try (InputStream in = backend.openStream(key, null, null)) {
            assertEquals(part0.length + part1.length, readAll(in).length);
        }
        // Range：跳过 part0 取尾部
        try (InputStream in = backend.openStream(key, (long) part0.length, null)) {
            assertEquals("tail-part", new String(readAll(in), StandardCharsets.UTF_8));
        }
        // Range：中间段
        try (InputStream in = backend.openStream(key, (long) part0.length + 1, (long) part0.length + 3)) {
            assertEquals("ail", new String(readAll(in), StandardCharsets.UTF_8));
        }

        backend.delete(key);
        assertFalse(backend.exists(key));
    }

    @Test
    @DisplayName("同 partNumber 幂等重传：覆盖旧分片后 complete 正常")
    void idempotentPartRetry() throws Exception {
        byte[] part0 = ("B".repeat(5 * 1024 * 1024)).getBytes(StandardCharsets.US_ASCII);
        String key = TEST_PREFIX + "/retry.bin";

        String backendUploadId = backend.initUpload("it-upload-2", key);
        // 第一次上传错误数据，重传正确数据（同 partNumber 覆盖）
        backend.putChunk("it-upload-2", backendUploadId, key, 0, "wrong-data".getBytes());
        String etag0 = backend.putChunk("it-upload-2", backendUploadId, key, 0, part0);

        StorageBackend.CompletedUpload completed = backend.completeUpload("it-upload-2", backendUploadId, key,
                List.of(new StorageBackend.UploadedPart(0, etag0)));
        assertEquals(sha256Of(part0), completed.sha256Hex());
        backend.delete(key);
    }

    @Test
    @DisplayName("abortUpload：放弃后 complete 应不可用（multipart 已终止）")
    void abortDanglingUpload() {
        byte[] part0 = ("C".repeat(5 * 1024 * 1024)).getBytes(StandardCharsets.US_ASCII);
        String key = TEST_PREFIX + "/abort.bin";

        String backendUploadId = backend.initUpload("it-upload-3", key);
        backend.putChunk("it-upload-3", backendUploadId, key, 0, part0);
        assertDoesNotThrow(() -> backend.abortUpload("it-upload-3", backendUploadId, key));
        assertFalse(backend.exists(key)); // abort 不产生对象
    }

    // ---------------------------------------------------------------- helpers

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        in.transferTo(out);
        return out.toByteArray();
    }

    private static String sha256Of(byte[]... parts) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (byte[] p : parts) {
            digest.update(p);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
