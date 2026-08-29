package oj.shared;

/**
 * 审计常量：事件类型与目标类型统一命名，避免拼写漂移。
 */
public final class AuditActions {

    private AuditActions() {
    }

    // 教学组织
    public static final String TERM_CREATED = "TERM_CREATED";
    public static final String MAJOR_CREATED = "MAJOR_CREATED";
    public static final String COURSE_CREATED = "COURSE_CREATED";
    public static final String CLASS_CREATED = "CLASS_CREATED";
    public static final String TEACHER_CREATED = "TEACHER_CREATED";
    public static final String STUDENT_CREATED = "STUDENT_CREATED";
    public static final String TEACHER_ASSIGNED = "TEACHER_ASSIGNED";
    public static final String TEACHER_ASSIGNMENT_ENDED = "TEACHER_ASSIGNMENT_ENDED";
    public static final String STUDENT_ENROLLED = "STUDENT_ENROLLED";
    public static final String STUDENT_TRANSFERRED = "STUDENT_TRANSFERRED";
    public static final String ENROLLMENT_ENDED = "ENROLLMENT_ENDED";

    // 题库
    public static final String PROBLEM_CREATED = "PROBLEM_CREATED";
    public static final String PROBLEM_UPDATED = "PROBLEM_UPDATED";

    // 作业
    public static final String ASSIGNMENT_CREATED = "ASSIGNMENT_CREATED";
    public static final String ASSIGNMENT_PUBLISHED = "ASSIGNMENT_PUBLISHED";
    public static final String ASSIGNMENT_TARGET_WITHDRAWN = "ASSIGNMENT_TARGET_WITHDRAWN";
    public static final String TARGET_RULES_UPDATED = "TARGET_RULES_UPDATED";

    // 提交与判题
    public static final String SUBMISSION_ACCEPTED = "SUBMISSION_ACCEPTED";
    public static final String SUBMISSION_REPLAY_REJECTED = "SUBMISSION_REPLAY_REJECTED";
    public static final String JUDGE_RESULT_RECORDED = "JUDGE_RESULT_RECORDED";

    // 导出
    public static final String EXPORT_REQUESTED = "EXPORT_REQUESTED";
    public static final String EXPORT_GENERATED = "EXPORT_GENERATED";
    public static final String EXPORT_FAILED = "EXPORT_FAILED";
    public static final String EXPORT_TOKEN_ISSUED = "EXPORT_TOKEN_ISSUED";
    public static final String EXPORT_DOWNLOADED = "EXPORT_DOWNLOADED";
    public static final String EXPORT_TOKEN_EXPIRED = "EXPORT_TOKEN_EXPIRED";
    public static final String EXPORT_TOKEN_REVOKED = "EXPORT_TOKEN_REVOKED";
    public static final String EXPORT_FILE_EXPIRED = "EXPORT_FILE_EXPIRED";
    public static final String EXPORT_CLEANUP_FAILED = "EXPORT_CLEANUP_FAILED";

    // 认证
    public static final String LOGIN_SUCCESS = "LOGIN_SUCCESS";
    public static final String LOGIN_FAILED = "LOGIN_FAILED";
    public static final String LOGOUT = "LOGOUT";
    public static final String LOCAL_ACCOUNT_CREATED = "LOCAL_ACCOUNT_CREATED";
}
