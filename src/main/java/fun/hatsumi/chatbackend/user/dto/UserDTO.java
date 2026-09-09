package fun.hatsumi.chatbackend.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户脱敏视图（不含 password_hash）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDTO {

    private Long userId;

    private String username;

    private String displayName;

    private String role;

    private Boolean enabled;
}
