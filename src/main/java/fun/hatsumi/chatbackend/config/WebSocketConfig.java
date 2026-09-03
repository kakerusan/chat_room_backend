package fun.hatsumi.chatbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 原生 WebSocket 端点注册（/ws/chat）。
 * Handler 由 ChatWebSocketHandler 任务装配后注册。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // TODO(T16): registry.addHandler(chatWebSocketHandler, "/ws/chat");
    }
}
