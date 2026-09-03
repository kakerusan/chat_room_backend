package fun.hatsumi.chatbackend.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（chatroom.jwt.*）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "chatroom.jwt")
public class JwtProperties {

    /** 签发者标识。 */
    private String issuer = "chatroom-lab";

    /** HMAC256 密钥，来自环境变量 CHATROOM_JWT_SECRET，必须至少 32 字节。 */
    private String secret;

    /** Token 有效期（小时）。 */
    private int expireHours = 12;
}
