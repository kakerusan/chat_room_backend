package fun.hatsumi.chatbackend.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import lombok.Data;

/**
 * 注册请求。
 */
@Data
public class RegisterRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(min = 3, max = 50, message = "用户名长度须为 3~50 个字符")
    private String username;

    @NotBlank(message = "显示名称不能为空")
    @Size(max = 50, message = "显示名称最长 50 个字符")
    private String displayName;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 32, message = "密码长度须为 6~32 个字符")
    private String password;
}
