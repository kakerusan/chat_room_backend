package fun.hatsumi.chatbackend.common.api;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一 REST 响应信封：{"code":0,"message":"success","data":{}}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /** 业务码，0 表示成功，非 0 为错误码（与 HTTP 状态一致）。 */
    private int code;

    /** 提示信息。 */
    private String message;

    /** 业务数据。 */
    private T data;

    public static <T> ApiResponse<T> ok() {
        return new ApiResponse<>(0, "success", null);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "success", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
