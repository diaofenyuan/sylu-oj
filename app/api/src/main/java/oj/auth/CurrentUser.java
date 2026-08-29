package oj.auth;

/**
 * 当前请求的已认证主体。由认证拦截器写入，服务层授权统一从这里读取。
 */
public record CurrentUser(Long appUserId, String loginName, AppUser.Role role,
                          Long teacherId, Long studentId) {

    public boolean isAdmin() {
        return role == AppUser.Role.ADMIN;
    }

    public boolean isTeacher() {
        return role == AppUser.Role.TEACHER;
    }

    public boolean isStudent() {
        return role == AppUser.Role.STUDENT;
    }
}
