package oj.classroom;

import org.junit.jupiter.api.AfterEach;

import java.util.List;

/**
 * 测试基类：提供通过 CurrentUserContext 注入角色的辅助方法，
 * 避免在测试中建立真实 AppUser 与令牌，聚焦服务层对象级授权与业务规则。
 */
public abstract class TestSupport {

    protected static final oj.auth.CurrentUser ADMIN =
            new oj.auth.CurrentUser(1L, "admin", oj.auth.AppUser.Role.ADMIN, null, null);

    protected oj.auth.CurrentUser asAdmin() {
        return as(ADMIN);
    }

    protected oj.auth.CurrentUser asTeacher(Long teacherId) {
        return as(new oj.auth.CurrentUser(1000L + teacherId, "teacher-" + teacherId,
                oj.auth.AppUser.Role.TEACHER, teacherId, null));
    }

    protected oj.auth.CurrentUser asStudent(Long studentId) {
        return as(new oj.auth.CurrentUser(2000L + studentId, "student-" + studentId,
                oj.auth.AppUser.Role.STUDENT, null, studentId));
    }

    protected oj.auth.CurrentUser as(oj.auth.CurrentUser user) {
        oj.auth.CurrentUserContext.set(user);
        return user;
    }

    @AfterEach
    void clearContext() {
        oj.auth.CurrentUserContext.clear();
    }

    protected static List<String> languages(String... langs) {
        return List.of(langs);
    }
}
