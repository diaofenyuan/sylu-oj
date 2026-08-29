package oj.auth;

/**
 * 当前请求主体上下文（线程绑定）。拦截器请求前置写入、请求完成后清理。
 */
public final class CurrentUserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private CurrentUserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    public static CurrentUser get() {
        return HOLDER.get();
    }

    public static CurrentUser require() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new IllegalStateException("当前线程无已认证主体上下文");
        }
        return user;
    }

    public static void clear() {
        HOLDER.remove();
    }
}
