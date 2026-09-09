package fun.hatsumi.chatbackend.auth.interceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.service.UserService;

/**
 * REST Token 拦截器：Bearer JWT 校验 + 用户启用复查 + ADMIN 角色检查。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    private static final String BEARER_PREFIX = "Bearer ";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JwtService jwtService;

    private final UserService userService;

    public AuthInterceptor(JwtService jwtService, UserService userService) {
        this.jwtService = jwtService;
        this.userService = userService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return reject(response, 401, "缺少 Bearer Token");
        }
        String token = authorization.substring(BEARER_PREFIX.length()).trim();

        Long userId;
        try {
            var decoded = jwtService.verify(token);
            userId = Long.valueOf(decoded.getSubject());
        } catch (Exception e) {
            log.debug("Token rejected: {}", e.getMessage());
            return reject(response, 401, e.getMessage());
        }

        UserEntity user = userService.getEnabledUserById(userId);
        if (user == null) {
            return reject(response, 401, "用户不存在或已被禁用");
        }

        if (request.getRequestURI().startsWith("/api/admin/") && !"ADMIN".equals(user.getRole())) {
            return reject(response, 403, "需要管理员权限");
        }

        UserContext.set(new CurrentUser(user.getId(), user.getUsername(), user.getRole()));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        UserContext.clear();
    }

    private boolean reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(OBJECT_MAPPER.writeValueAsString(ApiResponse.error(code, message)));
        return false;
    }
}
