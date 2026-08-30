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

    // Judge Gateway、任务调度与测试数据分发（Task 6）
    public static final String JUDGE_TASK_CREATED = "JUDGE_TASK_CREATED";
    public static final String JUDGE_TASK_CLAIMED = "JUDGE_TASK_CLAIMED";
    public static final String JUDGE_TASK_RETRY_SCHEDULED = "JUDGE_TASK_RETRY_SCHEDULED";
    public static final String JUDGE_RETRY_EXHAUSTED = "JUDGE_RETRY_EXHAUSTED";
    public static final String JUDGE_TASK_LEASE_EXPIRED = "JUDGE_TASK_LEASE_EXPIRED";
    public static final String TESTCASE_DISTRIBUTED = "TESTCASE_DISTRIBUTED";
    public static final String JUDGE_AGENT_REGISTERED = "JUDGE_AGENT_REGISTERED";
    public static final String TESTCASE_MISMATCH_DETECTED = "TESTCASE_MISMATCH_DETECTED";
    public static final String AGENT_SUSPENDED = "AGENT_SUSPENDED";
    public static final String JUDGE_RESULT_SIGNATURE_INVALID = "JUDGE_RESULT_SIGNATURE_INVALID";
    public static final String JUDGE_RESULT_STALE_REJECTED = "JUDGE_RESULT_STALE_REJECTED";

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

    // 教务网页登录适配器与会话（Task 5）
    public static final String IDENTITY_LOGIN_START = "IDENTITY_LOGIN_START";
    public static final String IDENTITY_LOGIN_SUCCESS = "IDENTITY_LOGIN_SUCCESS";
    public static final String IDENTITY_LOGIN_FAILED = "IDENTITY_LOGIN_FAILED";
    public static final String LOGIN_STATE_REPLAY_REJECTED = "LOGIN_STATE_REPLAY_REJECTED";
    public static final String LOGIN_TICKET_REPLAY_REJECTED = "LOGIN_TICKET_REPLAY_REJECTED";
    public static final String ADAPTER_PAGE_CHANGED = "ADAPTER_PAGE_CHANGED";
    public static final String ADAPTER_CAPTCHA_DETECTED = "ADAPTER_CAPTCHA_DETECTED";
    public static final String ADAPTER_HALTED = "ADAPTER_HALTED";
    public static final String ADAPTER_RESUMED = "ADAPTER_RESUMED";
    public static final String IDENTITY_CHANGE_DETECTED = "IDENTITY_CHANGE_DETECTED";
    public static final String IDENTITY_CONFIRMED = "IDENTITY_CONFIRMED";
    public static final String IDENTITY_DISABLED = "IDENTITY_DISABLED";
    public static final String IDENTITY_BOUND = "IDENTITY_BOUND";
    public static final String REFRESH_ROTATED = "REFRESH_ROTATED";
    public static final String REFRESH_REUSE_DETECTED = "REFRESH_REUSE_DETECTED";
    public static final String SESSIONS_REVOKED = "SESSIONS_REVOKED";
    public static final String MFA_ENROLLED = "MFA_ENROLLED";
    public static final String MFA_CONFIRMED = "MFA_CONFIRMED";
    public static final String MFA_FAILED = "MFA_FAILED";
    public static final String LOGIN_THROTTLED = "LOGIN_THROTTLED";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String SESSION_ANOMALY = "SESSION_ANOMALY";
}
