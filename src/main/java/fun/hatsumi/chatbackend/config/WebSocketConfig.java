package fun.hatsumi.chatbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

import fun.hatsumi.chatbackend.chat.websocket.ChatWebSocketHandler;

/**
 * 原生 WebSocket 端点注册（/ws/chat）+ 容器级消息大小限制。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOriginPatterns("*"); // 课程实验环境放开跨域
    }

    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer(
            fun.hatsumi.chatbackend.config.ChatroomProperties properties) {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.getWs().getMaxTextMessageSize());
        container.setMaxBinaryMessageBufferSize(properties.getWs().getMaxTextMessageSize());
        return container;
    }
}
