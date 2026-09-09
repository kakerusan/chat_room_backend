package fun.hatsumi.chatbackend.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import fun.hatsumi.chatbackend.chat.entity.ChatMessageEntity;

/**
 * 聊天消息 Mapper（单表写入，暂无复杂 XML 需求）。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
