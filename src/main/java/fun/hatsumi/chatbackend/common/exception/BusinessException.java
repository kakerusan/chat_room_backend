package fun.hatsumi.chatbackend.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常：携带 HTTP 状态与业务码，由 GlobalExceptionHandler 统一转换。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    private final HttpStatus httpStatus;

    public BusinessException(String message, HttpStatus httpStatus) {
        super(message);
        this.code = httpStatus.value();
        this.httpStatus = httpStatus;
    }

    public BusinessException(int code, String message, HttpStatus httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(message, HttpStatus.BAD_REQUEST);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(message, HttpStatus.UNAUTHORIZED);
    }

    public static BusinessException forbidden(String message) {
        return new BusinessException(message, HttpStatus.FORBIDDEN);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(message, HttpStatus.NOT_FOUND);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(message, HttpStatus.CONFLICT);
    }

    public static BusinessException unprocessable(String message) {
        return new BusinessException(message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    public int getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
