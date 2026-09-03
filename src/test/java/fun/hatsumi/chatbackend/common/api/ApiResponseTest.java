package fun.hatsumi.chatbackend.common.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    @Test
    @DisplayName("ok() 无数据工厂")
    void ok_withoutData() {
        ApiResponse<Void> response = ApiResponse.ok();
        assertThat(response.getCode()).isZero();
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isNull();
    }

    @Test
    @DisplayName("ok(data) 工厂携带数据")
    void ok_withData() {
        ApiResponse<String> response = ApiResponse.ok("payload");
        assertThat(response.getCode()).isZero();
        assertThat(response.getMessage()).isEqualTo("success");
        assertThat(response.getData()).isEqualTo("payload");
    }

    @Test
    @DisplayName("error 工厂携带错误码与消息")
    void error_factory() {
        ApiResponse<Void> response = ApiResponse.error(409, "用户名已存在");
        assertThat(response.getCode()).isEqualTo(409);
        assertThat(response.getMessage()).isEqualTo("用户名已存在");
        assertThat(response.getData()).isNull();
    }
}
