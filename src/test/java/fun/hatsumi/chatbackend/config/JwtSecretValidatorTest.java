package fun.hatsumi.chatbackend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 密钥启动校验纯单元测试（不依赖 Spring context / 数据库）。
 */
class JwtSecretValidatorTest {

    private JwtProperties props(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        return properties;
    }

    private JwtSecretValidator validator(JwtProperties properties) {
        return new JwtSecretValidator(properties);
    }

    @Test
    @DisplayName("secret 为 null 时启动失败并提示环境变量")
    void afterPropertiesSet_whenSecretNull_throwsWithHint() {
        assertThatThrownBy(() -> validator(props(null)).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATROOM_JWT_SECRET");
    }

    @Test
    @DisplayName("secret 为空串时启动失败")
    void afterPropertiesSet_whenSecretBlank_throwsWithHint() {
        assertThatThrownBy(() -> validator(props("   ")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHATROOM_JWT_SECRET");
    }

    @Test
    @DisplayName("secret 不足 32 字节时启动失败并提示长度")
    void afterPropertiesSet_whenSecretTooShort_throwsWithLength() {
        assertThatThrownBy(() -> validator(props("short")).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32")
                .hasMessageContaining("5");
    }

    @Test
    @DisplayName("secret 达到 32 字节时校验通过")
    void afterPropertiesSet_whenSecretLongEnough_passes() {
        assertThatCode(() -> validator(props("dev-secret-0123456789abcdef0123456789abcdef")).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
