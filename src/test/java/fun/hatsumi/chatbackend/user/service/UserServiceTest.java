package fun.hatsumi.chatbackend.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.ChatroomProperties;
import fun.hatsumi.chatbackend.user.dto.UserDTO;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.mapper.UserMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户业务单元测试（mock Mapper，无需数据库；DB 集成验证由 T35 恢复）。
 */
class UserServiceTest {

    private UserMapper userMapper;
    private BCryptPasswordEncoder encoder;
    private ChatroomProperties properties;
    private UserService userService;

    @BeforeEach
    void setUp() {
        userMapper = mock(UserMapper.class);
        encoder = new BCryptPasswordEncoder(10);
        properties = new ChatroomProperties();
        properties.setStorageRoot(System.getProperty("java.io.tmpdir") + "/chatroom-test-storage");
        userService = new UserService(userMapper, encoder, properties);
    }

    private UserEntity storedUser(String username, String rawPassword, boolean enabled) {
        UserEntity entity = new UserEntity();
        entity.setId(1L);
        entity.setUsername(username);
        entity.setDisplayName("Display " + username);
        entity.setPasswordHash(encoder.encode(rawPassword));
        entity.setRole("USER");
        entity.setEnabled(enabled);
        return entity;
    }

    @Test
    @DisplayName("注册成功：保存 BCrypt 哈希而非明文")
    void register_success_persistsBcryptHash() {
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(userMapper.insert(any(UserEntity.class))).thenAnswer(invocation -> {
            UserEntity entity = invocation.getArgument(0);
            entity.setId(100L);
            return 1;
        });

        UserDTO dto = userService.register("alice", "Alice", "secret123");

        ArgumentCaptor<UserEntity> captor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(captor.capture());
        UserEntity saved = captor.getValue();

        assertThat(saved.getPasswordHash()).isNotEqualTo("secret123");
        assertThat(saved.getPasswordHash()).startsWith("$2a$10$");
        assertThat(encoder.matches("secret123", saved.getPasswordHash())).isTrue();
        assertThat(dto.getUserId()).isEqualTo(100L);
        assertThat(dto.getUsername()).isEqualTo("alice");
        assertThat(dto.getRole()).isEqualTo("USER");
    }

    @Test
    @DisplayName("重复用户名注册返回 409")
    void register_duplicateUsername_throws409() {
        when(userMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> userService.register("alice", "Alice", "secret123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名已存在");
        verify(userMapper, never()).insert(any(UserEntity.class));
    }

    @Test
    @DisplayName("正确密码登录成功")
    void verifyLogin_correctPassword_returnsUser() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "secret123", true));

        UserEntity entity = userService.verifyLogin("alice", "secret123");

        assertThat(entity.getUsername()).isEqualTo("alice");
    }

    @Test
    @DisplayName("错误密码登录返回 401 且文案统一")
    void verifyLogin_wrongPassword_throws401() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "secret123", true));

        assertThatThrownBy(() -> userService.verifyLogin("alice", "wrong-pass"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("用户不存在登录失败文案与密码错误一致（防枚举）")
    void verifyLogin_unknownUser_sameMessageAsWrongPassword() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> userService.verifyLogin("ghost", "whatever"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("禁用用户登录返回 401")
    void verifyLogin_disabledUser_throws401() {
        when(userMapper.selectOne(any())).thenReturn(storedUser("alice", "secret123", false));

        assertThatThrownBy(() -> userService.verifyLogin("alice", "secret123"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("用户名或密码错误");
    }

    @Test
    @DisplayName("getEnabledUserById：禁用或不存在返回 null")
    void getEnabledUserById_disabledOrMissing_returnsNull() {
        when(userMapper.selectById(anyLong())).thenReturn(null);
        assertThat(userService.getEnabledUserById(1L)).isNull();

        UserEntity disabled = storedUser("alice", "secret123", false);
        when(userMapper.selectById(anyLong())).thenReturn(disabled);
        assertThat(userService.getEnabledUserById(1L)).isNull();
    }

    @Test
    @DisplayName("getEnabledUserById：启用用户返回实体")
    void getEnabledUserById_enabled_returnsEntity() {
        UserEntity enabled = storedUser("alice", "secret123", true);
        when(userMapper.selectById(anyLong())).thenReturn(enabled);

        assertThat(userService.getEnabledUserById(1L)).isSameAs(enabled);
    }
}
