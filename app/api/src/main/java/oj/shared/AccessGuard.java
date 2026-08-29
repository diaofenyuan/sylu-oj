package oj.shared;

import oj.auth.AppUser;
import oj.auth.CurrentUser;
import oj.auth.CurrentUserContext;
import org.springframework.stereotype.Component;

/**
 * 统一授权入口：服务层通过该守卫校验角色与身份。
 * 所有对象级授权（授课关系、选课归属）在各业务服务中逐一校验。
 */
@Component
public class AccessGuard {

    public CurrentUser requireUser() {
        CurrentUser user = CurrentUserContext.get();
        if (user == null) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
        return user;
    }

    public CurrentUser requireAdmin() {
        CurrentUser user = requireUser();
        if (user.role() != AppUser.Role.ADMIN) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public CurrentUser requireTeacher() {
        CurrentUser user = requireUser();
        if (user.role() != AppUser.Role.TEACHER || user.teacherId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public CurrentUser requireStudent() {
        CurrentUser user = requireUser();
        if (user.role() != AppUser.Role.STUDENT || user.studentId() == null) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user;
    }

    public CurrentUser requireAdminOrTeacher() {
        CurrentUser user = requireUser();
        if (user.role() != AppUser.Role.ADMIN && user.role() != AppUser.Role.TEACHER) {
            throw new ApiException(ErrorCode.FORBIDDEN);
        }
        return user;
    }
}
