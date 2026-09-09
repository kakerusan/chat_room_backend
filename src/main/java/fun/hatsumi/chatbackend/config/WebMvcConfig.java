package fun.hatsumi.chatbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import fun.hatsumi.chatbackend.auth.interceptor.AuthInterceptor;
import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.user.service.UserService;

/**
 * MVC 拦截器注册：/api/** 统一 Token 校验，排除免认证端点与 OPTIONS 预检。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final JwtService jwtService;

    private final UserService userService;

    public WebMvcConfig(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(jwtService, userService))
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/auth/register",
                        "/api/auth/login",
                        "/api/health");
    }
}
