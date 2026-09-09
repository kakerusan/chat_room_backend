package fun.hatsumi.chatbackend.file.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 已存储文件实体（stored_files）。
 */
@Data
@TableName("stored_files")
public class StoredFileEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("owner_id")
    private Long ownerId;

    @TableField("scope")
    private String scope;

    @TableField("original_name")
    private String originalName;

    @TableField("stored_name")
    private String storedName;

    @TableField("relative_path")
    private String relativePath;

    @TableField("size_bytes")
    private Long sizeBytes;

    @TableField("sha256")
    private String sha256;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
