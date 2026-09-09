package fun.hatsumi.chatbackend.chat.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 聊天消息实体（chat_messages）。P2P 消息不经过服务端、不入库。
 */
@Data
@TableName("chat_messages")
public class ChatMessageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sender_id")
    private Long senderId;

    @TableField("receiver_id")
    private Long receiverId;

    @TableField("chat_type")
    private String chatType;

    @TableField("content")
    private String content;

    @TableField("text_color")
    private String textColor;

    @TableField("sent_at")
    private LocalDateTime sentAt;
}
