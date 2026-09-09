package fun.hatsumi.chatbackend.auth.interceptor;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 当前请求用户身份（由 Token 解析 + DB 复查填充）。
 */
@Data
@AllArgsConstructor
public class CurrentUser {

    private Long userId;

    private String username;

    private String role;
}
