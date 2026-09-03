package fun.hatsumi.chatbackend.common.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import fun.hatsumi.chatbackend.common.api.HealthController;

/**
 * 全局异常处理器单元测试（standalone MockMvc，无 Spring context）。
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class BoomController {

        @GetMapping("/api/boom")
        public String boom() {
            throw new RuntimeException("boom-detail-should-not-leak");
        }

        @GetMapping("/api/conflict")
        public String conflict() {
            throw BusinessException.conflict("用户名已存在");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new HealthController(), new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("健康端点返回统一格式")
    void health_returnsUnifiedEnvelope() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"));
    }

    @Test
    @DisplayName("未知异常返回 500 且不泄漏堆栈")
    void unknownException_returns500WithoutStacktrace() throws Exception {
        String body = mockMvc.perform(get("/api/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("服务器内部错误"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("at fun.");
        assertThat(body).doesNotContain("boom-detail-should-not-leak");
    }

    @Test
    @DisplayName("业务异常按状态码返回")
    void businessException_returnsMappedStatus() throws Exception {
        mockMvc.perform(get("/api/conflict"))
                .andExpect(status().is(HttpStatus.CONFLICT.value()))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("用户名已存在"));
    }

    @Test
    @DisplayName("业务异常静态工厂状态映射")
    void businessException_factories() {
        assertThat(BusinessException.badRequest("x").getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(BusinessException.unauthorized("x").getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(BusinessException.forbidden("x").getHttpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(BusinessException.notFound("x").getHttpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(BusinessException.conflict("x").getHttpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(BusinessException.unprocessable("x").getHttpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
