package fun.hatsumi.chatbackend.file.storage;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * RustFS（S3 兼容）存储后端：chatroom.storage.type=s3 时装配。
 *
 * <p>分块上传映射为 S3 multipart：1 chunk = 1 UploadPart（partNumber = chunkIndex + 1，
 * S3 分片号 1 起）。分片 ETag 由业务层记入 upload_chunks，Complete 时按账本组装，
 * 不依赖 ListParts（RustFS 对部分列举接口的兼容性未列入官方保证矩阵）。</p>
 *
 * <p>完整文件 SHA-256：CompleteMultipartUpload 后 GetObject 回流一次流式计算
 * （multipart ETag 非文件级哈希，不可用于校验）。</p>
 */
@Service
@ConditionalOnProperty(name = "chatroom.storage.type", havingValue = "s3")
public class S3StorageBackend implements StorageBackend {

    private static final Logger log = LoggerFactory.getLogger(S3StorageBackend.class);

    private final S3Client s3;

    private final ChatroomProperties.Storage props;

    public S3StorageBackend(S3Client s3Client, ChatroomProperties properties) {
        this.s3 = s3Client;
        this.props = properties.getStorage();
    }

    /**
     * 启动兜底：RustFS 不支持自动建桶，缺失时创建。
     */
    @PostConstruct
    void ensureBucket() {
        String bucket = props.getBucket();
        try {
            s3.headBucket(b -> b.bucket(bucket));
            log.info("S3 bucket ready: {} (endpoint={})", bucket, props.getEndpoint());
        } catch (S3Exception e) {
            // 不存在的桶可能返回 NoSuchBucket（其即 S3Exception 子类）或裸 404
            if (e instanceof NoSuchBucketException || e.statusCode() == 404) {
                s3.createBucket(b -> b.bucket(bucket));
                log.info("S3 bucket created: {}", bucket);
                return;
            }
            throw new IllegalStateException("S3 存储不可用（endpoint=" + props.getEndpoint() + "）: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- StorageBackend

    @Override
    public String initUpload(String uploadId, String targetKey) {
        try {
            return s3.createMultipartUpload(CreateMultipartUploadRequest.builder()
                            .bucket(props.getBucket())
                            .key(targetKey)
                            .build())
                    .uploadId();
        } catch (S3Exception e) {
            throw new BusinessException("创建 S3 multipart 上传失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public String putChunk(String uploadId, String backendUploadId, String targetKey, int chunkIndex, byte[] data) {
        try {
            UploadPartResponse resp = s3.uploadPart(
                    UploadPartRequest.builder()
                            .bucket(props.getBucket())
                            .key(targetKey)
                            .uploadId(backendUploadId)
                            .partNumber(chunkIndex + 1) // S3 分片号 1 起
                            .build(),
                    RequestBody.fromBytes(data));
            return resp.eTag();
        } catch (S3Exception e) {
            throw new BusinessException("S3 分片上传失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public CompletedUpload completeUpload(String uploadId, String backendUploadId, String targetKey,
            List<UploadedPart> parts) {
        try {
            // 账本按 chunk_index 升序 → partNumber 升序，满足 Complete 顺序要求
            List<CompletedPart> completedParts = parts.stream()
                    .map(p -> CompletedPart.builder()
                            .partNumber(p.chunkIndex() + 1)
                            .eTag(p.etag())
                            .build())
                    .toList();
            s3.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(targetKey)
                    .uploadId(backendUploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build());

            // 回流计算完整 SHA-256（multipart ETag 非文件级哈希）
            String sha256;
            try (ResponseInputStream<GetObjectResponse> in = s3.getObject(GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(targetKey)
                    .build())) {
                sha256 = sha256Hex(in);
            }
            return new CompletedUpload(targetKey, sha256);
        } catch (S3Exception e) {
            throw new BusinessException("S3 完成上传失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (IOException e) {
            throw new BusinessException("S3 回流校验失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public void abortUpload(String uploadId, String backendUploadId, String targetKey) {
        if (backendUploadId == null) {
            return;
        }
        try {
            s3.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(props.getBucket())
                    .key(targetKey)
                    .uploadId(backendUploadId)
                    .build());
        } catch (S3Exception e) {
            // abort 幂等：已终结/不存在的 multipart 报错不阻断清理流程
            log.warn("S3 abortMultipartUpload failed (uploadId={}, key={}): {}",
                    backendUploadId, targetKey, e.getMessage());
        }
    }

    @Override
    public void cleanupUpload(String uploadId) {
        // S3 模式无服务端临时资源（Complete/Abort 即释放）
    }

    @Override
    public InputStream openStream(String key, Long start, Long end) {
        try {
            GetObjectRequest.Builder builder = GetObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key);
            if (start != null || end != null) {
                long from = start == null ? 0 : start;
                String range = "bytes=" + from + "-" + (end == null ? "" : end);
                builder.range(range);
            }
            return s3.getObject(builder.build()); // ResponseInputStream，调用方负责 close
        } catch (NoSuchKeyException e) {
            throw BusinessException.notFound("文件不存在");
        } catch (S3Exception e) {
            throw new BusinessException("S3 读取失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public long size(String key) {
        try {
            HeadObjectResponse resp = s3.headObject(HeadObjectRequest.builder()
                    .bucket(props.getBucket())
                    .key(key)
                    .build());
            return resp.contentLength();
        } catch (NoSuchKeyException e) {
            throw BusinessException.notFound("文件不存在");
        } catch (S3Exception e) {
            throw new BusinessException("S3 查询失败: " + e.getMessage(),
                    org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(props.getBucket()).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(props.getBucket()).key(key).build());
        } catch (S3Exception e) {
            log.warn("S3 deleteObject failed (key={}): {}", key, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private static String sha256Hex(InputStream in) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = in.read(buffer)) != -1) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
