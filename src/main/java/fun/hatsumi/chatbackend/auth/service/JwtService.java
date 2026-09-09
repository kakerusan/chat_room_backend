package fun.hatsumi.chatbackend.auth.service;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.TokenExpiredException;
import com.auth0.jwt.interfaces.DecodedJWT;

import fun.hatsumi.chatbackend.common.exception.BusinessException;
import fun.hatsumi.chatbackend.config.JwtProperties;
import fun.hatsumi.chatbackend.user.entity.UserEntity;

/**
 * JWT 签发与验证（HMAC256，轻量方案：无 Refresh Token、无黑名单）。
 */
public class JwtService {

    private final JwtProperties properties;

    private final Algorithm algorithm;

    private final JWTVerifier verifier;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.algorithm = Algorithm.HMAC256(properties.getSecret());
        this.verifier = JWT.require(algorithm)
                .withIssuer(properties.getIssuer())
                .acceptLeeway(TimeUnit.SECONDS.toSeconds(5))
                .build();
    }

    /**
     * 签发 Token：sub=用户 ID，claims 只放最小身份信息（username/role）。
     */
    public String issue(UserEntity user) {
        Instant now = Instant.now();
        return JWT.create()
                .withIssuer(properties.getIssuer())
                .withSubject(String.valueOf(user.getId()))
                .withClaim("username", user.getUsername())
                .withClaim("role", user.getRole())
                .withIssuedAt(now)
                .withExpiresAt(now.plusSeconds(TimeUnit.HOURS.toSeconds(properties.getExpireHours())))
                .sign(algorithm);
    }

    /**
     * 验证 Token：过期返回"Token 已过期"，其余验证失败返回"Token 无效"。
     */
    public DecodedJWT verify(String token) {
        try {
            return verifier.verify(token);
        } catch (TokenExpiredException e) {
            throw BusinessException.unauthorized("Token 已过期");
        } catch (Exception e) {
            throw BusinessException.unauthorized("Token 无效");
        }
    }

    /**
     * 从 Token 解出过期时间（epoch 毫秒），供登录响应透出。
     */
    public long expiresAtMillis(String token) {
        return verifier.verify(token).getExpiresAt().getTime();
    }
}
