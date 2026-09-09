package fun.hatsumi.chatbackend.chat.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;

import fun.hatsumi.chatbackend.chat.entity.ChatMessageEntity;
import fun.hatsumi.chatbackend.chat.mapper.ChatMessageMapper;

/**
 * 聊天消息业务：入库成功后才生成最终消息 ID（去重依据）。
 */
@Service
public class ChatMessageService {

    private final ChatMessageMapper chatMessageMapper;

    public ChatMessageService(ChatMessageMapper chatMessageMapper) {
        this.chatMessageMapper = chatMessageMapper;
    }

    /**
     * 保存私聊或群聊消息，返回带 ID 的实体。
     */
    public ChatMessageEntity save(Long senderId, Long receiverId, String chatType, String content, String textColor) {
        ChatMessageEntity entity = new ChatMessageEntity();
        entity.setSenderId(senderId);
        entity.setReceiverId(receiverId);
        entity.setChatType(chatType);
        entity.setContent(content);
        entity.setTextColor(textColor);
        entity.setSentAt(LocalDateTime.now());
        chatMessageMapper.insert(entity);
        return entity;
    }
}
