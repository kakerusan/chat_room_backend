package fun.hatsumi.chatbackend.config;

import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 启动期校验 JWT 密钥：为空或不足 32 字节直接终止启动。
 */
@Component
public class JwtSecretValidator implements InitializingBean {

    private static final int MIN_SECRET_BYTES = 32;

    private final JwtProperties jwtProperties;

    public JwtSecretValidator(JwtProperties jwtProperties) {
        this.jwtProperties = jwtProperties;
    }

    @Override
    public void afterPropertiesSet() {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "CHATROOM_JWT_SECRET 未设置：JWT 密钥必须通过环境变量提供（至少 " + MIN_SECRET_BYTES + " 字节）");
        }
        int length = secret.getBytes(StandardCharsets.UTF_8).length;
        if (length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "CHATROOM_JWT_SECRET 长度不足：至少 " + MIN_SECRET_BYTES + " 字节（当前 " + length + " 字节）");
        }
    }
}
