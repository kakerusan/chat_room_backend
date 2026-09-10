package fun.hatsumi.chatbackend.file.storage;

import java.io.InputStream;
import java.util.List;

/**
 * 文件存储后端抽象：local（本地磁盘）与 s3（RustFS 对象存储）双实现。
 *
 * <p>分块上传生命周期：</p>
 * <ol>
 *   <li>业务层生成 targetKey（{@link #newTargetKey}，后端无关命名）；</li>
 *   <li>{@link #initUpload} 创建后端上传上下文，返回 backendUploadId（local 返回 null）；</li>
 *   <li>{@link #putChunk} 逐块写入，返回块后端标识（S3 为分片 ETag；local 返回 null）；</li>
 *   <li>{@link #completeUpload} 按业务层账本（upload_chunks）终结上传，
 *       返回最终存储位置与完整文件 SHA-256；</li>
 *   <li>{@link #abortUpload} 放弃上传（S3: AbortMultipartUpload；local: 清理临时目录）。</li>
 * </ol>
 *
 * <p>key 规则：两种后端共用同一套相对路径命名（public/... 或 users/{ownerId}/...），
 * 保证 stored_files.relative_path 语义与后端无关。</p>
 */
public interface StorageBackend {

    /**
     * 创建后端上传上下文。
     *
     * @return backendUploadId（S3: multipart uploadId；local: null）
     */
    String initUpload(String uploadId, String targetKey);

    /**
     * 写入一个分块（数据已通过业务层 SHA-256 校验）。
     *
     * @param chunkIndex 分块序号（0 起；S3 分片号 = chunkIndex + 1）
     * @return 块后端标识（S3: 分片 ETag；local: null）
     */
    String putChunk(String uploadId, String backendUploadId, String targetKey, int chunkIndex, byte[] data);

    /**
     * 完成上传：按 parts（chunk_index 升序）终结合并，返回存储位置与完整 SHA-256。
     * 调用方比对摘要失败时应调用 {@link #delete(String)} 清理。
     *
     * @param parts 业务层分块账本（含每块 ETag，S3 CompleteMultipartUpload 必需）
     */
    CompletedUpload completeUpload(String uploadId, String backendUploadId, String targetKey,
            List<UploadedPart> parts);

    /** 放弃上传，释放后端资源。 */
    void abortUpload(String uploadId, String backendUploadId, String targetKey);

    /** 上传终结后的临时资源清理（local: temp 目录；S3: 无操作）。 */
    void cleanupUpload(String uploadId);

    /**
     * 打开读取流。
     *
     * @param start 起始偏移（null 表示从头）
     * @param end   结束偏移，含端点（null 表示到末尾）
     */
    InputStream openStream(String key, Long start, Long end);

    long size(String key);

    boolean exists(String key);

    void delete(String key);

    /** 已上传分块（业务层账本条目）。 */
    record UploadedPart(int chunkIndex, String etag) {
    }

    /** 完成上传结果：最终存储位置 + 完整文件 SHA-256。 */
    record CompletedUpload(String key, String sha256Hex) {
    }

    /**
     * 生成后端无关的存储 key：{public|users/{ownerId}}/{时间戳}-{随机串}{安全扩展名}。
     */
    static String newTargetKey(String scope, Long ownerId, String originalName) {
        String storedName = System.currentTimeMillis() + "-"
                + java.util.UUID.randomUUID().toString().substring(0, 8)
                + sanitizeExtension(originalName);
        String dir = "PUBLIC".equals(scope) ? "public" : "users/" + ownerId;
        return dir + "/" + storedName;
    }

    /** 仅保留安全的扩展名字符，防止存储名携带路径分隔符。 */
    static String sanitizeExtension(String originalName) {
        int dot = originalName == null ? -1 : originalName.lastIndexOf('.');
        if (dot < 0 || dot == originalName.length() - 1) {
            return "";
        }
        String ext = originalName.substring(dot).replaceAll("[^a-zA-Z0-9.]", "");
        return ext.length() > 16 ? ext.substring(0, 16) : ext;
    }
}
