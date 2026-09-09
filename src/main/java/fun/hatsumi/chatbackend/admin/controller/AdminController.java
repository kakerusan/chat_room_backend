package fun.hatsumi.chatbackend.admin.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.chat.websocket.ChatWebSocketHandler;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.mapper.UserMapper;

/**
 * 管理员接口（拦截器已校验 ADMIN 角色）：
 * 用户列表、启用/禁用（禁用踢线）、运行时差错率、系统通知广播。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final UserMapper userMapper;

    private final ChatWebSocketHandler wsHandler;

    private final ChatroomProperties properties;

    public AdminController(UserMapper userMapper, ChatWebSocketHandler wsHandler,
            ChatroomProperties properties) {
        this.userMapper = userMapper;
        this.wsHandler = wsHandler;
        this.properties = properties;
    }

    @GetMapping("/users")
    public ApiResponse<List<Map<String, Object>>> listUsers() {
        List<UserEntity> users = userMapper.selectList(
                new LambdaQueryWrapper<UserEntity>().orderByAsc(UserEntity::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (UserEntity u : users) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("displayName", u.getDisplayName());
            m.put("role", u.getRole());
            m.put("enabled", Boolean.TRUE.equals(u.getEnabled()));
            result.add(m);
        }
        return ApiResponse.ok(result);
    }

    public record SetEnabledRequest(Boolean enabled) {
    }

    @PutMapping("/users/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable Long id, @RequestBody SetEnabledRequest request) {
        if (request.enabled() == null) {
            throw BusinessException.badRequest("enabled 必填");
        }
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw BusinessException.notFound("用户不存在");
        }
        user.setEnabled(request.enabled());
        userMapper.updateById(user);
        if (!request.enabled()) {
            wsHandler.kickUser(id); // 禁用立即踢下线
        }
        return ApiResponse.ok();
    }

    public record SetErrorRateRequest(Double errorRate) {
    }

    @PutMapping("/settings/error-rate")
    public ApiResponse<Map<String, Object>> setErrorRate(@RequestBody SetErrorRateRequest request) {
        if (request.errorRate() == null || request.errorRate() < 0 || request.errorRate() > 1) {
            throw BusinessException.badRequest("errorRate 需在 0~1 之间");
        }
        properties.setErrorSimulationRate(request.errorRate()); // 即时生效，无需重启
        return ApiResponse.ok(Map.of("errorRate", request.errorRate()));
    }

    public record NoticeRequest(String content) {
    }

    @PostMapping("/notices")
    public ApiResponse<Void> postNotice(@RequestBody NoticeRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw BusinessException.badRequest("通知内容不能为空");
        }
        wsHandler.broadcastServerNotice(request.content());
        return ApiResponse.ok();
    }
}
