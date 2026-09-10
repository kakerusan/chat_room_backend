package fun.hatsumi.chatbackend.chat.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import fun.hatsumi.chatbackend.chat.entity.ChatMessageEntity;
import fun.hatsumi.chatbackend.chat.mapper.ChatMessageMapper;

/**
 * 聊天消息业务：入库成功后才生成最终消息 ID（去重依据）。
 */
@Service
public class ChatMessageService {

    /** 历史消息单页上限。 */
    public static final int HISTORY_PAGE_SIZE = 100;

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

    /**
     * 历史消息查询（按时间升序返回，方便前端直接追加渲染）。
     *
     * @param userId      当前用户（鉴权由拦截器保证）
     * @param chatType    PRIVATE / BROADCAST
     * @param peerId      私聊对方用户 ID；群聊忽略
     * @param beforeId    分页游标：只取 id 小于该值的消息；首页传 null
     * @param limit       单页条数上限（服务端封顶 HISTORY_PAGE_SIZE）
     */
    public List<ChatMessageEntity> history(Long userId, String chatType, Long peerId, Long beforeId, Integer limit) {
        int size = limit == null ? HISTORY_PAGE_SIZE : Math.min(Math.max(limit, 1), HISTORY_PAGE_SIZE);

        LambdaQueryWrapper<ChatMessageEntity> wrapper = new LambdaQueryWrapper<>();
        if ("BROADCAST".equals(chatType)) {
            wrapper.eq(ChatMessageEntity::getChatType, "BROADCAST");
        } else {
            // 私聊：双方任意方向的的消息（sender/receiver 任一匹配当前用户或对方）
            wrapper.eq(ChatMessageEntity::getChatType, "PRIVATE")
                    .and(w -> w
                            .and(w2 -> w2.eq(ChatMessageEntity::getSenderId, userId)
                                    .eq(ChatMessageEntity::getReceiverId, peerId))
                            .or(w2 -> w2.eq(ChatMessageEntity::getSenderId, peerId)
                                    .eq(ChatMessageEntity::getReceiverId, userId)));
        }
        if (beforeId != null) {
            wrapper.lt(ChatMessageEntity::getId, beforeId);
        }
        wrapper.orderByDesc(ChatMessageEntity::getId)
                .last("LIMIT " + size);

        List<ChatMessageEntity> desc = chatMessageMapper.selectList(wrapper);
        // 倒序取回后翻转为升序
        java.util.Collections.reverse(desc);
        return desc;
    }
}
