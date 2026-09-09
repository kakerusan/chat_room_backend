package fun.hatsumi.chatbackend.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功响应（字段名与前端契约一致：token / expiresAt / user{...}）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginDTO {

    private String token;

    /** Token 过期时间（epoch 毫秒）。 */
    private long expiresAt;

    private User user;

    /** 嵌套用户信息。 */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class User {
        private Long id;
        private String username;
        private String displayName;
        private String role;
    }
}
