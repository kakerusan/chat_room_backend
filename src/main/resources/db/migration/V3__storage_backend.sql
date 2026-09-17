-- ============================================================
-- V3__storage_backend.sql — 存储后端抽象支撑列
-- 依据：doc/聊天室系统-RustFS存储集成实施文档.md 第 7 节
-- S3（RustFS）模式下记录 multipart 上下文与目标 key；
-- local 模式下三列均为 NULL，零影响。
-- ============================================================

-- S3 multipart 分片 ETag（CompleteMultipartUpload 必需；local 为空）
ALTER TABLE upload_chunks
    ADD COLUMN etag VARCHAR(128) NULL COMMENT 'S3 分片 ETag（本地磁盘模式为空）' AFTER chunk_sha256;

-- S3 multipart uploadId（local 为空）
ALTER TABLE upload_sessions
    ADD COLUMN backend_upload_id VARCHAR(128) NULL COMMENT '后端上传标识（S3 multipart uploadId）' AFTER status;

-- 会话创建时生成的后端无关存储 key（public/... 或 users/{id}/...）
ALTER TABLE upload_sessions
    ADD COLUMN target_key VARCHAR(512) NULL COMMENT '目标存储 key（后端无关）' AFTER backend_upload_id;
