package fun.hatsumi.chatbackend.auth.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fun.hatsumi.chatbackend.auth.dto.LoginRequest;
import fun.hatsumi.chatbackend.auth.dto.RegisterRequest;
import fun.hatsumi.chatbackend.auth.service.JwtService;
import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.common.exception.GlobalExceptionHandler;
import fun.hatsumi.chatbackend.user.dto.UserDTO;
import fun.hatsumi.chatbackend.user.entity.UserEntity;
import fun.hatsumi.chatbackend.user.service.UserService;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 注册/登录切片测试（standalone MockMvc + mock Service，无需 DB）。
 */
class AuthControllerTest {

    private UserService userService;
    private JwtService jwtService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = Mockito.mock(UserService.class);
        jwtService = Mockito.mock(JwtService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AuthController(userService, jwtService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("注册成功返回脱敏用户")
    void register_success() throws Exception {
        when(userService.register(anyString(), anyString(), anyString()))
                .thenReturn(new UserDTO(100L, "alice", "Alice", "USER", true));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.userId").value(100))
                .andExpect(jsonPath("$.data.username").value("alice"));

        Mockito.verify(userService).register("alice", "Alice", "secret123");
    }

    @Test
    @DisplayName("注册密码过短返回 400")
    void register_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("注册空用户名返回 400")
    void register_blankUsername_returns400() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"displayName\":\"Alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("注册重复用户名返回 409")
    void register_duplicate_returns409() throws Exception {
        when(userService.register(anyString(), anyString(), anyString()))
                .thenThrow(BusinessException.conflict("用户名已存在"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"displayName\":\"Alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @DisplayName("登录成功返回 token 与用户信息")
    void login_success_returnsToken() throws Exception {
        UserEntity entity = new UserEntity();
        entity.setId(42L);
        entity.setUsername("alice");
        entity.setDisplayName("Alice");
        entity.setRole("USER");
        when(userService.verifyLogin("alice", "secret123")).thenReturn(entity);
        when(jwtService.issue(entity)).thenReturn("fake.jwt.token");
        when(jwtService.expiresAtMillis("fake.jwt.token")).thenReturn(1893456000000L);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("fake.jwt.token"))
                .andExpect(jsonPath("$.data.expiresAt").value(1893456000000L))
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.user.username").value("alice"))
                .andExpect(jsonPath("$.data.user.displayName").value("Alice"))
                .andExpect(jsonPath("$.data.user.role").value("USER"));
    }

    @Test
    @DisplayName("登录失败返回 401 统一文案")
    void login_wrongCredentials_returns401() throws Exception {
        when(userService.verifyLogin(anyString(), anyString()))
                .thenThrow(BusinessException.unauthorized("用户名或密码错误"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong-pass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }
}
