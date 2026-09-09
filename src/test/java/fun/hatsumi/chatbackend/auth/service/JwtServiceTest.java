package fun.hatsumi.chatbackend.auth.service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.JwtProperties;
import fun.hatsumi.chatbackend.user.entity.UserEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 服务单元测试（纯 java-jwt，无需 Spring/DB）。
 */
class JwtServiceTest {

    private static final String SECRET = "test-secret-0123456789abcdef0123456789abcdef";
    private static final String ISSUER = "chatroom-lab";

    private JwtService jwtService;

    private UserEntity user() {
        UserEntity entity = new UserEntity();
        entity.setId(42L);
        entity.setUsername("alice");
        entity.setRole("USER");
        return entity;
    }

    @BeforeEach
    void setUp() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(SECRET);
        properties.setIssuer(ISSUER);
        properties.setExpireHours(12);
        jwtService = new JwtService(properties);
    }

    @Test
    @DisplayName("签发与验证往返：claims 正确")
    void issueAndVerify_roundtrip() {
        String token = jwtService.issue(user());

        DecodedJWT decoded = jwtService.verify(token);

        assertThat(decoded.getSubject()).isEqualTo("42");
        assertThat(decoded.getClaim("username").asString()).isEqualTo("alice");
        assertThat(decoded.getClaim("role").asString()).isEqualTo("USER");
        assertThat(decoded.getIssuer()).isEqualTo(ISSUER);
    }

    @Test
    @DisplayName("有效期：exp - iat ≈ 12 小时")
    void issuedToken_hasTwelveHourExpiry() {
        String token = jwtService.issue(user());

        DecodedJWT decoded = jwtService.verify(token);
        long seconds = decoded.getExpiresAtAsInstant().getEpochSecond()
                - decoded.getIssuedAtAsInstant().getEpochSecond();

        assertThat(seconds).isBetween(TimeUnit.HOURS.toSeconds(12) - 60, TimeUnit.HOURS.toSeconds(12) + 60);
    }

    @Test
    @DisplayName("过期 Token：401 且文案为已过期")
    void verify_expiredToken_throws401WithExpiredMessage() {
        Instant past = Instant.now().minusSeconds(3600);
        String token = JWT.create()
                .withIssuer(ISSUER)
                .withSubject("42")
                .withIssuedAt(past.minusSeconds(7200))
                .withExpiresAt(past)
                .sign(Algorithm.HMAC256(SECRET));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Token 已过期");
    }

    @Test
    @DisplayName("伪造签名 Token：401 Token 无效")
    void verify_forgedSignature_throws401() {
        String token = JWT.create()
                .withIssuer(ISSUER)
                .withSubject("42")
                .withExpiresAt(Instant.now().plusSeconds(3600))
                .sign(Algorithm.HMAC256("evil-secret-0123456789abcdef01234567"));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Token 无效");
    }

    @Test
    @DisplayName("错误 issuer：401 Token 无效")
    void verify_wrongIssuer_throws401() {
        String token = JWT.create()
                .withIssuer("evil-issuer")
                .withSubject("42")
                .withExpiresAt(Instant.now().plusSeconds(3600))
                .sign(Algorithm.HMAC256(SECRET));

        assertThatThrownBy(() -> jwtService.verify(token))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Token 无效");
    }

    @Test
    @DisplayName("垃圾字符串：401 Token 无效（不抛原始异常）")
    void verify_garbage_throws401() {
        assertThatThrownBy(() -> jwtService.verify("not.a.jwt"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Token 无效");
    }
}
