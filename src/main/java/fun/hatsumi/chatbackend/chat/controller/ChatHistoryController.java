package fun.hatsumi.chatbackend.chat.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.chat.entity.ChatMessageEntity;
import fun.hatsumi.chatbackend.chat.service.ChatMessageService;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.mapper.UserMapper;

/**
 * 聊天历史消息查询。与 WS 实时消息共用 chat_messages 表：
 * 前端登录后先拉历史，再接收实时推送（按消息 ID 去重）。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatHistoryController {

    private final ChatMessageService chatMessageService;

    private final UserMapper userMapper;

    public ChatHistoryController(ChatMessageService chatMessageService, UserMapper userMapper) {
        this.chatMessageService = chatMessageService;
        this.userMapper = userMapper;
    }

    /**
     * GET /api/chat/history?type=PRIVATE&peerId=2&beforeId=100&limit=50
     * GET /api/chat/history?type=BROADCAST&beforeId=100&limit=50
     */
    @GetMapping("/history")
    public ApiResponse<List<Map<String, Object>>> history(
            @RequestParam String type,
            @RequestParam(required = false) Long peerId,
            @RequestParam(required = false) Long beforeId,
            @RequestParam(required = false) Integer limit) {

        Long me = fun.hatsumi.chatbackend.auth.interceptor.UserContext.currentUserId();
        if (me == null) {
            return ApiResponse.error(401, "未登录");
        }
        if (!"PRIVATE".equals(type) && !"BROADCAST".equals(type)) {
            return ApiResponse.error(400, "type 必须为 PRIVATE 或 BROADCAST");
        }
        if ("PRIVATE".equals(type) && peerId == null) {
            return ApiResponse.error(400, "私聊历史必须提供 peerId");
        }

        List<ChatMessageEntity> messages = chatMessageService.history(me, type, peerId, beforeId, limit);

        // 批量补齐发送者昵称
        Map<Long, String> nameById = new HashMap<>();
        List<Long> senderIds = messages.stream().map(ChatMessageEntity::getSenderId).distinct().toList();
        if (!senderIds.isEmpty()) {
            List<UserEntity> users = userMapper.selectList(
                    new LambdaQueryWrapper<UserEntity>().in(UserEntity::getId, senderIds));
            for (UserEntity u : users) {
                nameById.put(u.getId(), u.getDisplayName() != null ? u.getDisplayName() : u.getUsername());
            }
        }

        List<Map<String, Object>> data = new ArrayList<>();
        for (ChatMessageEntity m : messages) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", m.getId());
            item.put("senderId", m.getSenderId());
            item.put("senderName", nameById.getOrDefault(m.getSenderId(), ""));
            item.put("receiverId", m.getReceiverId());
            item.put("chatType", m.getChatType());
            item.put("content", m.getContent());
            item.put("textColor", m.getTextColor());
            item.put("sentAt", m.getSentAt() == null ? null
                    : m.getSentAt().atZone(java.time.ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli());
            data.add(item);
        }
        return ApiResponse.ok(data);
    }
}
