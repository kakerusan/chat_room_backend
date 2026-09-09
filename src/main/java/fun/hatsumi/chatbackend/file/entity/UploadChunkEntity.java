package fun.hatsumi.chatbackend.file.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 上传分块实体（upload_chunks），(upload_id, chunk_index) 唯一索引支撑幂等。
 */
@Data
@TableName("upload_chunks")
public class UploadChunkEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("upload_id")
    private String uploadId;

    @TableField("chunk_index")
    private Integer chunkIndex;

    @TableField("chunk_size")
    private Integer chunkSize;

    @TableField("chunk_sha256")
    private String chunkSha256;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
