package fun.hatsumi.chatbackend.file.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 上传会话实体（upload_sessions）。
 */
@Data
@TableName("upload_sessions")
public class UploadSessionEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("upload_id")
    private String uploadId;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("scope")
    private String scope;

    @TableField("file_name")
    private String fileName;

    @TableField("file_size")
    private Long fileSize;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("total_chunks")
    private Integer totalChunks;

    @TableField("file_sha256")
    private String fileSha256;

    /** INIT / MERGING / COMPLETED / EXPIRED */
    @TableField("status")
    private String status;

    /** S3 multipart uploadId（local 模式为空） */
    @TableField("backend_upload_id")
    private String backendUploadId;

    /** 会话创建时生成的后端无关存储 key */
    @TableField("target_key")
    private String targetKey;

    @TableField("expire_at")
    private LocalDateTime expireAt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
