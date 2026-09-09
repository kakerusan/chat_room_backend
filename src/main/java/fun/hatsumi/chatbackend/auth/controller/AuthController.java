package fun.hatsumi.chatbackend.auth.controller;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.hatsumi.chatbackend.auth.dto.LoginDTO;
import fun.hatsumi.chatbackend.auth.dto.LoginRequest;
import fun.hatsumi.chatbackend.auth.dto.RegisterRequest;
import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.common.api.ApiResponse;
import fun.hatsumi.chatbackend.user.dto.UserDTO;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.service.UserService;

/**
 * 注册与登录（免 Token 端点，由拦截器排除）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ApiResponse<UserDTO> register(@Valid @RequestBody RegisterRequest request) {
        UserDTO user = userService.register(request.getUsername(), request.getDisplayName(), request.getPassword());
        return ApiResponse.ok(user);
    }

    @PostMapping("/login")
    public ApiResponse<LoginDTO> login(@Valid @RequestBody LoginRequest request) {
        UserEntity user = userService.verifyLogin(request.getUsername(), request.getPassword());
        String token = jwtService.issue(user);
        long expiresAt = jwtService.expiresAtMillis(token);
        return ApiResponse.ok(new LoginDTO(token, expiresAt,
                new LoginDTO.User(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole())));
    }
}
