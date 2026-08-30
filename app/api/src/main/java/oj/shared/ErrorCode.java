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

    // 教务网页登录适配器与会话（Task 5）
    IDP_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "IDP_UNAVAILABLE", "教务系统暂不可用，已停止新登录"),
    ADAPTER_HALTED(HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_HALTED", "登录适配器已熔断，请联系管理员"),
    ADAPTER_NOT_CERTIFIED(HttpStatus.SERVICE_UNAVAILABLE, "ADAPTER_NOT_CERTIFIED", "教务登录适配器尚未通过现场验收登记"),
    LOGIN_STATE_INVALID(HttpStatus.UNAUTHORIZED, "LOGIN_STATE_INVALID", "登录状态无效、已过期或已被使用"),
    LOGIN_SOURCE_MISMATCH(HttpStatus.UNAUTHORIZED, "LOGIN_SOURCE_MISMATCH", "登录来源与发起时不一致"),
    CALLBACK_NOT_ALLOWED(HttpStatus.BAD_REQUEST, "CALLBACK_NOT_ALLOWED", "回调地址不在白名单内"),
    TICKET_INVALID(HttpStatus.UNAUTHORIZED, "TICKET_INVALID", "教务票据校验失败"),
    TICKET_REPLAYED(HttpStatus.UNAUTHORIZED, "TICKET_REPLAYED", "教务票据已被使用"),
    IDENTITY_UNRESOLVED(HttpStatus.FORBIDDEN, "IDENTITY_UNRESOLVED", "学号/工号未在教学组织建档，请联系管理员"),
    IDENTITY_CONFIRMATION_REQUIRED(HttpStatus.FORBIDDEN, "IDENTITY_CONFIRMATION_REQUIRED", "检测到身份变更，等待管理员人工确认"),
    NO_TEACHING_RELATION(HttpStatus.FORBIDDEN, "NO_TEACHING_RELATION", "当前没有有效的授课关系"),
    NO_ACTIVE_ENROLLMENT(HttpStatus.FORBIDDEN, "NO_ACTIVE_ENROLLMENT", "当前没有有效的选课归属"),
    REFRESH_INVALID(HttpStatus.UNAUTHORIZED, "REFRESH_INVALID", "刷新令牌无效或已过期"),
    REFRESH_REUSED(HttpStatus.UNAUTHORIZED, "REFRESH_REUSED", "检测到刷新令牌重用，相关会话已全部撤销"),
    MFA_REQUIRED(HttpStatus.UNAUTHORIZED, "MFA_REQUIRED", "需要完成第二因子验证"),
    MFA_FAILED(HttpStatus.UNAUTHORIZED, "MFA_FAILED", "第二因子验证失败"),
    MFA_ENROLLMENT_REQUIRED(HttpStatus.FORBIDDEN, "MFA_ENROLLMENT_REQUIRED", "管理员必须先完成双因子绑定"),
    LOGIN_LOCKED(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_LOCKED", "失败次数过多，账号已临时锁定"),
    LOGIN_THROTTLED(HttpStatus.TOO_MANY_REQUESTS, "LOGIN_THROTTLED", "登录请求过于频繁，请稍后再试"),

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
    EXAM_APPROVAL_REQUIRED(HttpStatus.CONFLICT, "EXAM_APPROVAL_REQUIRED", "考试期间修改需双人审批后重试"),
    EXAM_SELF_APPROVAL(HttpStatus.CONFLICT, "EXAM_SELF_APPROVAL", "请求人与批准人不得为同一人"),
    EXAM_NOT_LOCKED(HttpStatus.CONFLICT, "EXAM_NOT_LOCKED", "考试尚未锁定"),
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

    // Judge Gateway 与测试数据分发（Task 6）
    AGENT_UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "AGENT_UNAUTHORIZED", "判题代理认证失败"),
    AGENT_SUSPENDED(HttpStatus.FORBIDDEN, "AGENT_SUSPENDED", "判题代理已被暂停，禁止领取任务"),
    TASK_NOT_CLAIMABLE(HttpStatus.CONFLICT, "TASK_NOT_CLAIMABLE", "任务不可领取、租约已过期或归属其他代理"),
    TESTCASE_MISMATCH(HttpStatus.FORBIDDEN, "TESTCASE_MISMATCH", "用例请求与当前任务不匹配"),
    RESULT_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "RESULT_SIGNATURE_INVALID", "结果签名校验失败"),
    RESULT_LIMIT_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "RESULT_LIMIT_INVALID", "结果超出资源上限约束"),

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
