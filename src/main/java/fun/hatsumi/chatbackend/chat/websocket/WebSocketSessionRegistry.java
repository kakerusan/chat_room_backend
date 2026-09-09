package fun.hatsumi.chatbackend.chat.websocket;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.hatsumi.chatbackend.chat.protocol.WsEnvelope;

/**
 * 在线会话注册表：userId -> session。
 * 同一用户重复连接时关闭旧连接、保留新连接；同一 session 发送动作串行。
 */
@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册新连接；若同一用户已有连接，先关闭旧连接。
     */
    public void register(Long userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old != session && old.isOpen()) {
            try {
                old.close();
            } catch (IOException e) {
                log.debug("Close stale session for user {}: {}", userId, e.getMessage());
            }
        }
        log.info("WS registered: userId={}, online={}", userId, sessions.size());
    }

    /**
     * 注销连接（仅当当前注册的仍是该 session 时）。
     */
    public void unregister(Long userId, WebSocketSession session) {
        sessions.remove(userId, session);
        log.info("WS unregistered: userId={}, online={}", userId, sessions.size());
    }

    public WebSocketSession get(Long userId) {
        return sessions.get(userId);
    }

    public boolean isOnline(Long userId) {
        WebSocketSession s = sessions.get(userId);
        return s != null && s.isOpen();
    }

    public List<Long> onlineUserIds() {
        return new ArrayList<>(sessions.keySet());
    }

    /**
     * 向指定用户发送信封；单个连接异常只影响自身。
     */
    public boolean sendTo(Long userId, WsEnvelope envelope) {
        WebSocketSession session = sessions.get(userId);
        if (session == null || !session.isOpen()) {
            return false;
        }
        return send(session, envelope);
    }

    /**
     * 广播给所有在线用户。
     */
    public void broadcast(WsEnvelope envelope) {
        for (Map.Entry<Long, WebSocketSession> entry : sessions.entrySet()) {
            send(entry.getValue(), envelope);
        }
    }

    /**
     * 串行发送：同一 session 的发送动作加锁，避免并发写导致报文交错。
     */
    public boolean send(WebSocketSession session, WsEnvelope envelope) {
        try {
            String text = MAPPER.writeValueAsString(envelope);
            synchronized (session) {
                session.sendMessage(new TextMessage(text));
            }
            return true;
        } catch (IOException e) {
            log.warn("Send failed to session {}: {}", session.getId(), e.getMessage());
            return false;
        }
    }
}
