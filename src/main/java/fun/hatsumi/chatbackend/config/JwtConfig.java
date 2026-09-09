package fun.hatsumi.chatbackend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.config.JwtProperties;

/**
 * JwtService 装配（依赖启动期已校验的 secret）。
 */
@Configuration
public class JwtConfig {

    @Bean
    public JwtService jwtService(JwtProperties jwtProperties) {
        return new JwtService(jwtProperties);
    }
}
