package oj.shared;

import org.springframework.http.HttpStatus;

/**
 * 统一错误码。message 面向最终用户，不得包含堆栈、SQL、内部路径等细节。
 */
public enum ErrorCode {

    // 通用
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "请求参数不合法"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统内部错误"),
    AUTH_REQUIRED(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "未认证"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行该操作"),

    // 认证（本地合成账号，仅开发/内测）
    LOGIN_FAILED(HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "账号或密码错误"),
    ACCOUNT_DISABLED(HttpStatus.UNAUTHORIZED, "ACCOUNT_DISABLED", "账号已停用"),
    LOCAL_ACCOUNTS_DISABLED(HttpStatus.FORBIDDEN, "LOCAL_ACCOUNTS_DISABLED", "本地合成账号未启用"),

    // 教学组织
    NOT_FOUND(HttpStatus.NOT_FOUND, "NOT_FOUND", "资源不存在"),
    CODE_CONFLICT(HttpStatus.CONFLICT, "CODE_CONFLICT", "编码或名称已存在"),
    TERM_CLOSED(HttpStatus.CONFLICT, "TERM_CLOSED", "学期已关闭"),
    CLASS_REQUIRES_PRIMARY(HttpStatus.CONFLICT, "CLASS_REQUIRES_PRIMARY", "教学班必须保留至少一名主讲教师"),
    TEACHER_ALREADY_ASSIGNED(HttpStatus.CONFLICT, "TEACHER_ALREADY_ASSIGNED", "该教师在此教学班已有有效授课关系"),
    STUDENT_ALREADY_ENROLLED(HttpStatus.CONFLICT, "STUDENT_ALREADY_ENROLLED", "学生在此学期已有教学班归属，转班需显式 transfer"),
    ENROLLMENT_NOT_ACTIVE(HttpStatus.CONFLICT, "ENROLLMENT_NOT_ACTIVE", "选课归属已结束"),

    // 题库
    PROBLEM_NOT_DRAFT(HttpStatus.CONFLICT, "PROBLEM_NOT_DRAFT", "仅草稿题目可整体编辑"),
    PROBLEM_NOT_PUBLISHED(HttpStatus.CONFLICT, "PROBLEM_NOT_PUBLISHED", "题目未发布，不能进入作业"),
    TESTCASE_REQUIRED(HttpStatus.CONFLICT, "TESTCASE_REQUIRED", "发布前至少需要一个测试用例"),

    // 作业
    WEIGHT_NOT_100(HttpStatus.CONFLICT, "WEIGHT_NOT_100", "题目权重之和必须恰为 100"),
    EMPTY_COMPOSITION(HttpStatus.CONFLICT, "EMPTY_COMPOSITION", "作业至少包含一道题目"),
    DUPLICATE_TARGET(HttpStatus.CONFLICT, "DUPLICATE_TARGET", "目标班级重复"),
    ASSIGNMENT_NOT_DRAFT(HttpStatus.CONFLICT, "ASSIGNMENT_NOT_DRAFT", "仅草稿作业可修改组卷"),
    ASSIGNMENT_NOT_PUBLISHED(HttpStatus.CONFLICT, "ASSIGNMENT_NOT_PUBLISHED", "作业未发布"),
    EXAM_LOCKED(HttpStatus.CONFLICT, "EXAM_LOCKED", "正式考试已发布锁定，禁止修改"),
    TARGET_RULE_INVALID(HttpStatus.BAD_REQUEST, "TARGET_RULE_INVALID", "目标班级规则不合法"),

    // 提交
    WINDOW_CLOSED(HttpStatus.FORBIDDEN, "WINDOW_CLOSED", "不在提交时间窗口内"),
    SUBMISSION_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "SUBMISSION_LIMIT_EXCEEDED", "已达到最大提交次数"),
    LANGUAGE_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "LANGUAGE_NOT_ALLOWED", "该题目不支持所选语言"),
    CODE_TOO_LARGE(HttpStatus.BAD_REQUEST, "CODE_TOO_LARGE", "代码超出大小限制"),
    IDEMPOTENCY_REPLAY(HttpStatus.CONFLICT, "IDEMPOTENCY_REPLAY", "重复提交请求"),

    // 判题结果
    RESULT_NOT_TERMINAL(HttpStatus.CONFLICT, "RESULT_NOT_TERMINAL", "记录终态结果时不能使用非终态码"),
    RESULT_SCORE_INVALID(HttpStatus.CONFLICT, "RESULT_SCORE_INVALID", "结果得分不合法"),
    STALE_RESULT_VERSION(HttpStatus.CONFLICT, "STALE_RESULT_VERSION", "旧版本结果不能覆盖新版本"),

    // 导出
    EXPORT_NOT_READY(HttpStatus.CONFLICT, "EXPORT_NOT_READY", "导出文件尚未就绪"),
    EXPORT_EXPIRED(HttpStatus.CONFLICT, "EXPORT_EXPIRED", "导出文件已过期"),
    EXPORT_TOKEN_INVALID(HttpStatus.FORBIDDEN, "EXPORT_TOKEN_INVALID", "下载授权无效"),
    EXPORT_TOKEN_EXPIRED(HttpStatus.FORBIDDEN, "EXPORT_TOKEN_EXPIRED", "下载授权已过期"),
    EXPORT_TOKEN_USED(HttpStatus.FORBIDDEN, "EXPORT_TOKEN_USED", "下载授权已被使用"),
    EXPORT_FILE_MISSING(HttpStatus.CONFLICT, "EXPORT_FILE_MISSING", "导出文件缺失");

    private final HttpStatus httpStatus;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String code, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
