package fun.hatsumi.chatbackend.auth.interceptor;

/**
 * 请求级用户上下文（ThreadLocal）。必须由拦截器在 afterCompletion 清理。
 */
public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static Long currentUserId() {
        CurrentUser user = HOLDER.get();
        return user == null ? null : user.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
