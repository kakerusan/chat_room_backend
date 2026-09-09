package fun.hatsumi.chatbackend.chat.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.chat.entity.ChatMessageEntity;
import fun.hatsumi.chatbackend.chat.protocol.WsEnvelope;
import fun.hatsumi.chatbackend.chat.service.ChatMessageService;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.service.UserService;

/**
 * 聊天 WebSocket 协议路由：
 * 首帧 AUTH（Token 不进 URL）→ AUTH_SUCCESS 后方可收发其余消息。
 * 私聊/群聊入库后回执 CHAT_MESSAGE；RTC 信令仅中转不落库。
 */
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** session attributes 键。 */
    private static final String ATTR_USER_ID = "userId";

    private static final String ATTR_USER = "user";

    /** 单条消息内容上限（字符）。 */
    private static final int MAX_CONTENT_CHARS = 2000;

    private final JwtService jwtService;

    private final UserService userService;

    private final ChatMessageService chatMessageService;

    private final WebSocketSessionRegistry registry;

    private final ChatroomProperties properties;

    /** userId -> 最近活跃时间（毫秒），供心跳清理。 */
    private final Map<Long, Long> lastActive = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-auth-timeout");
        t.setDaemon(true);
        return t;
    });

    public ChatWebSocketHandler(JwtService jwtService, UserService userService,
            ChatMessageService chatMessageService, WebSocketSessionRegistry registry,
            ChatroomProperties properties) {
        this.jwtService = jwtService;
        this.userService = userService;
        this.chatMessageService = chatMessageService;
        this.registry = registry;
        this.properties = properties;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        // 连接建立后 5 秒内必须完成首帧 AUTH
        final long deadline = properties.getWs().getAuthTimeoutSeconds();
        scheduler.schedule(() -> {
            try {
                if (session.isOpen() && session.getAttributes().get(ATTR_USER_ID) == null) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("AUTH timeout"));
                }
            } catch (IOException e) {
                log.debug("Close unauthenticated session: {}", e.getMessage());
            }
        }, deadline, TimeUnit.SECONDS);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        WsEnvelope envelope;
        try {
            envelope = MAPPER.readValue(message.getPayload(), WsEnvelope.class);
        } catch (Exception e) {
            send(session, WsEnvelope.of("ERROR", null, Map.of("message", "无法解析的消息格式")));
            return;
        }
        if (envelope.getType() == null) {
            return;
        }

        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            if ("AUTH".equals(envelope.getType())) {
                handleAuth(session, envelope);
            } else {
                send(session, WsEnvelope.of("ERROR", null, Map.of("message", "请先发送 AUTH 完成认证")));
            }
            return;
        }

        lastActive.put(userId, System.currentTimeMillis());

        switch (envelope.getType()) {
            case "PING" -> send(session, WsEnvelope.of("PONG", envelope.getRequestId(), Map.of()));
            case "CHAT_PRIVATE" -> handlePrivateChat(session, userId, envelope);
            case "CHAT_BROADCAST" -> handleBroadcastChat(userId, envelope);
            case "RTC_OFFER", "RTC_ANSWER", "RTC_ICE" -> handleRtcSignal(userId, envelope);
            default -> { /* 未知类型忽略，避免恶意探测 */ }
        }
    }

    // ---------------------------------------------------------------- AUTH

    private void handleAuth(WebSocketSession session, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        String token = payload != null ? String.valueOf(payload.get("token")) : null;

        Long userId;
        try {
            userId = Long.valueOf(jwtService.verify(token).getSubject());
        } catch (Exception e) {
            send(session, WsEnvelope.of("AUTH_FAILURE", envelope.getRequestId(),
                    Map.of("message", e.getMessage() == null ? "认证失败" : e.getMessage())));
            closeQuietly(session);
            return;
        }

        UserEntity user = userService.getEnabledUserById(userId);
        if (user == null) {
            send(session, WsEnvelope.of("AUTH_FAILURE", envelope.getRequestId(),
                    Map.of("message", "用户不存在或已被禁用")));
            closeQuietly(session);
            return;
        }

        session.getAttributes().put(ATTR_USER_ID, user.getId());
        session.getAttributes().put(ATTR_USER, user);
        registry.register(user.getId(), session);
        lastActive.put(user.getId(), System.currentTimeMillis());

        send(session, WsEnvelope.of("AUTH_SUCCESS", envelope.getRequestId(), Map.of("users", onlineUsers())));
        announcePresence(user);
        log.info("WS auth success: userId={}, username={}", user.getId(), user.getUsername());
    }

    private void announcePresence(UserEntity user) {
        Map<String, Object> online = new HashMap<>();
        online.put("user", brief(user));
        registry.broadcast(WsEnvelope.of("USER_ONLINE", null, online));

        registry.broadcast(WsEnvelope.of("USER_LIST", null, Map.of("users", onlineUsers())));
    }

    // ---------------------------------------------------------------- CHAT

    private void handlePrivateChat(WebSocketSession session, Long senderId, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        Long receiverId = toLong(payload == null ? null : payload.get("receiverId"));
        String content = payload == null ? null : (String) payload.get("content");
        String textColor = payload == null ? null : (String) payload.get("textColor");

        if (receiverId == null || content == null || content.isBlank()) {
            send(session, WsEnvelope.of("ERROR", envelope.getRequestId(), Map.of("message", "receiverId 与 content 不能为空")));
            return;
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            send(session, WsEnvelope.of("ERROR", envelope.getRequestId(),
                    Map.of("message", "单条消息最多 " + MAX_CONTENT_CHARS + " 字符")));
            return;
        }
        if (!registry.isOnline(receiverId)) {
            send(session, WsEnvelope.of("ERROR", envelope.getRequestId(), Map.of("message", "目标用户不在线")));
            return;
        }

        UserEntity sender = (UserEntity) session.getAttributes().get(ATTR_USER);
        ChatMessageEntity saved = chatMessageService.save(senderId, receiverId, "PRIVATE", content, textColor);

        Map<String, Object> messagePayload = messagePayload(saved, sender, envelope.getRequestId());
        registry.sendTo(senderId, WsEnvelope.of("CHAT_MESSAGE", envelope.getRequestId(), messagePayload));
        registry.sendTo(receiverId, WsEnvelope.of("CHAT_MESSAGE", null, messagePayload));
    }

    private void handleBroadcastChat(Long senderId, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        String content = payload == null ? null : (String) payload.get("content");
        String textColor = payload == null ? null : (String) payload.get("textColor");

        if (content == null || content.isBlank()) {
            return;
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            return;
        }

        UserEntity sender = findUser(senderId);
        ChatMessageEntity saved = chatMessageService.save(senderId, null, "BROADCAST", content, textColor);
        registry.broadcast(WsEnvelope.of("CHAT_MESSAGE", envelope.getRequestId(), messagePayload(saved, sender, envelope.getRequestId())));
    }

    private Map<String, Object> messagePayload(ChatMessageEntity saved, UserEntity sender, String requestId) {
        Map<String, Object> pm = new HashMap<>();
        pm.put("id", saved.getId());
        pm.put("senderId", saved.getSenderId());
        pm.put("senderName", sender != null ? sender.getDisplayName() : "");
        pm.put("receiverId", saved.getReceiverId());
        pm.put("chatType", saved.getChatType());
        pm.put("content", saved.getContent());
        pm.put("textColor", saved.getTextColor());
        pm.put("sentAt", System.currentTimeMillis());
        if (requestId != null) {
            pm.put("requestId", requestId);
        }
        return pm;
    }

    // ---------------------------------------------------------------- RTC 信令中转

    private void handleRtcSignal(Long senderId, WsEnvelope envelope) {
        Map<String, Object> payload = envelope.getPayload();
        Long targetUserId = toLong(payload == null ? null : payload.get("targetUserId"));
        if (targetUserId == null) {
            return;
        }
        if (!registry.isOnline(targetUserId)) {
            send(findSession(senderId), WsEnvelope.of("ERROR", envelope.getRequestId(),
                    Map.of("message", "对方不在线，无法建立 P2P")));
            return;
        }

        // 转发给目标：targetUserId 字段改写为发起方 ID，接收端据此识别信令来源
        Map<String, Object> forwarded = new HashMap<>(payload);
        forwarded.put("targetUserId", senderId);
        registry.sendTo(targetUserId, WsEnvelope.of(envelope.getType(), envelope.getRequestId(), forwarded));
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
        if (userId == null) {
            return;
        }
        registry.unregister(userId, session);
        lastActive.remove(userId);

        registry.broadcast(WsEnvelope.of("USER_OFFLINE", null, Map.of("userId", userId)));
        registry.broadcast(WsEnvelope.of("USER_LIST", null, Map.of("users", onlineUsers())));
        log.info("WS closed: userId={}, status={}", userId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        // 传输异常直接关闭，走 afterConnectionClosed 统一清理
        if (session.isOpen()) {
            session.close(CloseStatus.SERVER_ERROR);
        }
    }

    /**
     * 心跳清理：60 秒无任何消息（含 PING）的连接强制下线。每 10 秒扫描一次。
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 10_000)
    public void evictIdleSessions() {
        long idleThreshold = properties.getWs().getIdleTimeoutSeconds() * 1000L;
        long now = System.currentTimeMillis();
        lastActive.forEach((userId, ts) -> {
            if (now - ts > idleThreshold) {
                WebSocketSession session = registry.get(userId);
                if (session != null) {
                    closeQuietly(session);
                    registry.unregister(userId, session);
                    lastActive.remove(userId);
                    registry.broadcast(WsEnvelope.of("USER_OFFLINE", null, Map.of("userId", userId)));
                }
            }
        });
    }

    // ---------------------------------------------------------------- helpers

    /** 供 Admin 广播系统通知。 */
    public void broadcastServerNotice(String content) {
        registry.broadcast(WsEnvelope.of("SERVER_NOTICE", null, Map.of("content", content)));
    }

    /** 供 Admin 禁用用户后踢下线。 */
    public void kickUser(Long userId) {
        WebSocketSession session = registry.get(userId);
        if (session != null && session.isOpen()) {
            closeQuietly(session);
            registry.unregister(userId, session);
        }
        lastActive.remove(userId);
    }

    public List<Map<String, Object>> onlineUsers() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Long id : registry.onlineUserIds()) {
            UserEntity user = findUser(id);
            if (user != null) {
                list.add(brief(user));
            }
        }
        return list;
    }

    private Map<String, Object> brief(UserEntity user) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", user.getId());
        m.put("username", user.getUsername());
        m.put("displayName", user.getDisplayName());
        return m;
    }

    private UserEntity findUser(Long userId) {
        WebSocketSession session = registry.get(userId);
        if (session != null) {
            UserEntity cached = (UserEntity) session.getAttributes().get(ATTR_USER);
            if (cached != null && cached.getId().equals(userId)) {
                return cached;
            }
        }
        return userService.getEnabledUserById(userId);
    }

    private WebSocketSession findSession(Long userId) {
        return registry.get(userId);
    }

    private boolean send(WebSocketSession session, WsEnvelope envelope) {
        if (session == null) {
            return false;
        }
        return registry.send(session, envelope);
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            session.close();
        } catch (IOException ignored) {
            // 已关闭
        }
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
