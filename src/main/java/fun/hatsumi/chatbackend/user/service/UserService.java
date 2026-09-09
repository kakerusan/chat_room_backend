package fun.hatsumi.chatbackend.user.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.user.dto.UserDTO;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.mapper.UserMapper;

/**
 * 用户业务：注册（BCrypt 哈希）与登录校验。
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private static final String LOGIN_FAILURE_MESSAGE = "用户名或密码错误";

    private final UserMapper userMapper;

    private final BCryptPasswordEncoder passwordEncoder;

    private final ChatroomProperties properties;

    public UserService(UserMapper userMapper, BCryptPasswordEncoder passwordEncoder, ChatroomProperties properties) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    /**
     * 注册：查重 -> BCrypt 哈希 -> 入库 -> 创建私人存储目录。
     */
    public UserDTO register(String username, String displayName, String password) {
        Long existing = userMapper.selectCount(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (existing != null && existing > 0) {
            throw BusinessException.conflict("用户名已存在");
        }

        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        entity.setDisplayName(displayName);
        entity.setPasswordHash(passwordEncoder.encode(password));
        entity.setRole("USER");
        entity.setEnabled(true);
        userMapper.insert(entity);

        createUserStorageDir(entity.getId());
        log.info("User registered: id={}, username={}", entity.getId(), username);

        return new UserDTO(entity.getId(), entity.getUsername(), entity.getDisplayName(), entity.getRole(), entity.getEnabled());
    }

    /**
     * 登录校验：用户不存在/禁用/密码错误统一返回同一文案，避免暴露用户是否存在。
     */
    public UserEntity verifyLogin(String username, String password) {
        UserEntity entity = userMapper.selectOne(
                new LambdaQueryWrapper<UserEntity>().eq(UserEntity::getUsername, username));
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())
                || !passwordEncoder.matches(password, entity.getPasswordHash())) {
            throw BusinessException.unauthorized(LOGIN_FAILURE_MESSAGE);
        }
        return entity;
    }

    /**
     * 供拦截器复查用户是否仍启用。
     */
    public UserEntity getEnabledUserById(Long userId) {
        UserEntity entity = userMapper.selectById(userId);
        if (entity == null || !Boolean.TRUE.equals(entity.getEnabled())) {
            return null;
        }
        return entity;
    }

    private void createUserStorageDir(Long userId) {
        try {
            Path dir = Paths.get(properties.getStorageRoot(), "users", String.valueOf(userId));
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.warn("Failed to create storage dir for user {}: {}", userId, e.getMessage());
        }
    }
}
