-- =====================================================================
-- V001__teaching_problem_assignment.sql
-- 教学组织、题库、组卷与作业发布业务数据模型（MySQL 8 / H2 MySQL 模式兼容）
--
-- 关键约束设计：
--   1. active_marker 技巧：UNIQUE(col_a, col_b, active_marker) 中
--      active_marker 仅在“有效”行取 1、结束行为 NULL。MySQL/H2 的唯一索引
--      均允许多个 NULL，从而在数据库层强制“每键至多一条有效记录”，
--      覆盖：学生每学期唯一有效归属、每教学班每教师唯一有效授课关系。
--   2. 幂等键：submission(assignment_target_id, problem_id, student_id,
--      idempotency_key) 唯一，防止提交重放消耗次数。
--   3. 判题结果幂等：judge_result.submission_id 唯一，
--      以 result_version 防止旧版本覆盖新版本（服务层校验）。
-- =====================================================================

-- ---------- 学期 / 专业 / 课程 ----------
CREATE TABLE term (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    start_date   DATE         NOT NULL,
    end_date     DATE         NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at   DATETIME     NOT NULL,
    CONSTRAINT uk_term_code UNIQUE (code),
    CONSTRAINT ck_term_status CHECK (status IN ('ACTIVE', 'CLOSED'))
);

CREATE TABLE major (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    created_at DATETIME     NOT NULL,
    CONSTRAINT uk_major_code UNIQUE (code)
);

CREATE TABLE course (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    code       VARCHAR(32)  NOT NULL,
    name        VARCHAR(128) NOT NULL,
    credit      DECIMAL(4, 1) NOT NULL DEFAULT 1.0,
    created_at  DATETIME     NOT NULL,
    CONSTRAINT uk_course_code UNIQUE (code),
    CONSTRAINT ck_course_credit CHECK (credit > 0 AND credit <= 20)
);

-- ---------- 教师与学生 ----------
CREATE TABLE teacher (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    staff_no   VARCHAR(32) NOT NULL,
    name       VARCHAR(64) NOT NULL,
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at DATETIME   NOT NULL,
    CONSTRAINT uk_teacher_staff_no UNIQUE (staff_no),
    CONSTRAINT ck_teacher_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE student (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_no  VARCHAR(32) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME   NOT NULL,
    CONSTRAINT uk_student_no UNIQUE (student_no),
    CONSTRAINT ck_student_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

-- ---------- 教学班 ----------
CREATE TABLE teaching_class (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    term_id         BIGINT       NOT NULL,
    course_id       BIGINT       NOT NULL,
    major_id        BIGINT       NULL,
    code            VARCHAR(32)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    created_at      DATETIME     NOT NULL,
    CONSTRAINT uk_teaching_class UNIQUE (term_id, course_id, code),
    CONSTRAINT fk_teaching_class_term FOREIGN KEY (term_id) REFERENCES term (id),
    CONSTRAINT fk_teaching_class_course FOREIGN KEY (course_id) REFERENCES course (id),
    CONSTRAINT fk_teaching_class_major FOREIGN KEY (major_id) REFERENCES major (id)
);

CREATE INDEX idx_teaching_class_term ON teaching_class (term_id);
CREATE INDEX idx_teaching_class_course ON teaching_class (course_id);

-- ---------- 教师授课关系（PRIMARY/ASSISTANT，每班每教师至多一条有效） ----------
CREATE TABLE teacher_assignment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    teaching_class_id BIGINT      NOT NULL,
    teacher_id       BIGINT      NOT NULL,
    role             VARCHAR(16) NOT NULL,
    valid_from       DATETIME    NOT NULL,
    valid_to         DATETIME    NULL,
    active_marker    BIGINT      NULL,
    created_at       DATETIME    NOT NULL,
    CONSTRAINT uk_teacher_assignment_active UNIQUE (teaching_class_id, teacher_id, active_marker),
    CONSTRAINT fk_teacher_assignment_class FOREIGN KEY (teaching_class_id) REFERENCES teaching_class (id),
    CONSTRAINT fk_teacher_assignment_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT ck_teacher_assignment_role CHECK (role IN ('PRIMARY', 'ASSISTANT'))
);

CREATE INDEX idx_teacher_assignment_teacher ON teacher_assignment (teacher_id);
CREATE INDEX idx_teacher_assignment_class ON teacher_assignment (teaching_class_id);

-- ---------- 学生选课归属（每学生每学期至多一条有效；term_id 冗余用于唯一约束） ----------
CREATE TABLE student_enrollment (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    student_id       BIGINT   NOT NULL,
    teaching_class_id BIGINT   NOT NULL,
    term_id          BIGINT   NOT NULL,
    enrolled_at      DATETIME NOT NULL,
    ended_at         DATETIME NULL,
    active_marker    BIGINT   NULL,
    created_at       DATETIME NOT NULL,
    CONSTRAINT uk_enrollment_active_per_term UNIQUE (student_id, term_id, active_marker),
    CONSTRAINT uk_enrollment_active_per_class UNIQUE (student_id, teaching_class_id, active_marker),
    CONSTRAINT fk_enrollment_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT fk_enrollment_class FOREIGN KEY (teaching_class_id) REFERENCES teaching_class (id),
    CONSTRAINT fk_enrollment_term FOREIGN KEY (term_id) REFERENCES term (id)
);

CREATE INDEX idx_enrollment_student ON student_enrollment (student_id);
CREATE INDEX idx_enrollment_class ON student_enrollment (teaching_class_id);

-- ---------- 题库 / 题目 / 测试数据 ----------
CREATE TABLE problem_bank (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    teaching_class_id BIGINT       NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(512) NULL,
    created_at       DATETIME     NOT NULL,
    CONSTRAINT uk_problem_bank UNIQUE (teaching_class_id, name),
    CONSTRAINT fk_problem_bank_class FOREIGN KEY (teaching_class_id) REFERENCES teaching_class (id)
);

CREATE TABLE problem (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    problem_bank_id  BIGINT        NOT NULL,
    code             VARCHAR(32)   NOT NULL,
    title            VARCHAR(256) NOT NULL,
    description      MEDIUMTEXT    NULL,
    languages        VARCHAR(128) NOT NULL,
    difficulty       VARCHAR(16)   NOT NULL DEFAULT 'EASY',
    status           VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',
    version          INT           NOT NULL DEFAULT 1,
    time_limit_ms    INT           NOT NULL DEFAULT 10000,
    memory_limit_mb  INT           NOT NULL DEFAULT 256,
    output_limit_kb  INT           NOT NULL DEFAULT 65536,
    max_score        DECIMAL(7, 2) NOT NULL DEFAULT 100.00,
    created_by       BIGINT        NOT NULL,
    created_at       DATETIME      NOT NULL,
    updated_at       DATETIME      NOT NULL,
    CONSTRAINT uk_problem_code UNIQUE (problem_bank_id, code),
    CONSTRAINT fk_problem_bank FOREIGN KEY (problem_bank_id) REFERENCES problem_bank (id),
    CONSTRAINT fk_problem_creator FOREIGN KEY (created_by) REFERENCES teacher (id),
    CONSTRAINT ck_problem_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT ck_problem_limits CHECK (time_limit_ms > 0 AND memory_limit_mb > 0 AND output_limit_kb > 0),
    CONSTRAINT ck_problem_score CHECK (max_score > 0 AND max_score <= 10000)
);

CREATE INDEX idx_problem_bank ON problem (problem_bank_id);

CREATE TABLE testcase_set (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    problem_id BIGINT   NOT NULL,
    version    INT      NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_testcase_set_version UNIQUE (problem_id, version),
    CONSTRAINT fk_testcase_set_problem FOREIGN KEY (problem_id) REFERENCES problem (id)
);

CREATE TABLE testcase (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    testcase_set_id BIGINT        NOT NULL,
    order_num       INT           NOT NULL,
    is_sample       BOOLEAN       NOT NULL DEFAULT FALSE,
    input           MEDIUMTEXT    NULL,
    expected_output MEDIUMTEXT    NULL,
    score           DECIMAL(7, 2) NOT NULL DEFAULT 10.00,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_testcase_order UNIQUE (testcase_set_id, order_num),
    CONSTRAINT fk_testcase_set FOREIGN KEY (testcase_set_id) REFERENCES testcase_set (id),
    CONSTRAINT ck_testcase_score CHECK (score >= 0)
);

CREATE INDEX idx_testcase_set ON testcase (testcase_set_id);

-- ---------- 作业 / 组卷 / 目标班级 ----------
CREATE TABLE assignment (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    title      VARCHAR(256) NOT NULL,
    mode       VARCHAR(16)  NOT NULL,
    status     VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT       NOT NULL,
    created_at DATETIME     NOT NULL,
    updated_at DATETIME     NOT NULL,
    CONSTRAINT fk_assignment_creator FOREIGN KEY (created_by) REFERENCES teacher (id),
    CONSTRAINT ck_assignment_mode CHECK (mode IN ('HOMEWORK', 'EXAM')),
    CONSTRAINT ck_assignment_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN'))
);

CREATE INDEX idx_assignment_creator ON assignment (created_by);

CREATE TABLE assignment_problem (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT        NOT NULL,
    problem_id    BIGINT        NOT NULL,
    order_num     INT           NOT NULL,
    weight        DECIMAL(5, 2) NOT NULL,
    CONSTRAINT uk_assignment_problem UNIQUE (assignment_id, problem_id),
    CONSTRAINT uk_assignment_problem_order UNIQUE (assignment_id, order_num),
    CONSTRAINT fk_assignment_problem_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id),
    CONSTRAINT fk_assignment_problem_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT ck_assignment_problem_weight CHECK (weight > 0 AND weight <= 100)
);

CREATE INDEX idx_assignment_problem_problem ON assignment_problem (problem_id);

CREATE TABLE assignment_target (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id    BIGINT        NOT NULL,
    teaching_class_id BIGINT       NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PUBLISHED',
    publish_at       DATETIME      NOT NULL,
    deadline         DATETIME      NOT NULL,
    max_submissions  INT           NOT NULL DEFAULT 50,
    scoring_rules    VARCHAR(512)  NULL,
    version          INT           NOT NULL DEFAULT 1,
    created_at       DATETIME      NOT NULL,
    updated_at       DATETIME      NOT NULL,
    CONSTRAINT uk_assignment_target UNIQUE (assignment_id, teaching_class_id),
    CONSTRAINT fk_assignment_target_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id),
    CONSTRAINT fk_assignment_target_class FOREIGN KEY (teaching_class_id) REFERENCES teaching_class (id),
    CONSTRAINT ck_assignment_target_status CHECK (status IN ('PUBLISHED', 'WITHDRAWN')),
    CONSTRAINT ck_assignment_target_window CHECK (deadline > publish_at),
    CONSTRAINT ck_assignment_target_max_subs CHECK (max_submissions > 0 AND max_submissions <= 1000)
);

CREATE INDEX idx_assignment_target_assignment ON assignment_target (assignment_id);
CREATE INDEX idx_assignment_target_class ON assignment_target (teaching_class_id);

-- ---------- 发布时的不可变题目快照 ----------
CREATE TABLE problem_snapshot (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id    BIGINT        NOT NULL,
    problem_id       BIGINT        NOT NULL,
    problem_version  INT           NOT NULL,
    testcase_set_id  BIGINT        NOT NULL,
    title            VARCHAR(256) NOT NULL,
    description      MEDIUMTEXT    NULL,
    languages        VARCHAR(128) NOT NULL,
    judge_config     VARCHAR(1024) NOT NULL,
    content_checksum VARCHAR(64)  NOT NULL,
    created_at       DATETIME     NOT NULL,
    CONSTRAINT uk_problem_snapshot UNIQUE (assignment_id, problem_id),
    CONSTRAINT fk_snapshot_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id),
    CONSTRAINT fk_snapshot_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT fk_snapshot_testcase_set FOREIGN KEY (testcase_set_id) REFERENCES testcase_set (id)
);

CREATE INDEX idx_problem_snapshot_assignment ON problem_snapshot (assignment_id);

-- ---------- 提交与判题结果 ----------
CREATE TABLE submission (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_target_id BIGINT       NOT NULL,
    problem_id           BIGINT       NOT NULL,
    student_id           BIGINT       NOT NULL,
    language             VARCHAR(16)  NOT NULL,
    code                 MEDIUMTEXT   NOT NULL,
    attempt_no           INT          NOT NULL,
    idempotency_key      VARCHAR(64)  NOT NULL,
    judge_status         VARCHAR(4)   NOT NULL DEFAULT 'PD',
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    CONSTRAINT uk_submission_idempotency UNIQUE (assignment_target_id, problem_id, student_id, idempotency_key),
    CONSTRAINT fk_submission_target FOREIGN KEY (assignment_target_id) REFERENCES assignment_target (id),
    CONSTRAINT fk_submission_problem FOREIGN KEY (problem_id) REFERENCES problem (id),
    CONSTRAINT fk_submission_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT ck_submission_status CHECK (judge_status IN ('PD', 'CE', 'AC', 'WA', 'RE', 'TLE', 'MLE', 'OLE', 'PE', 'SE', 'BSC'))
);

CREATE INDEX idx_submission_target ON submission (assignment_target_id);
CREATE INDEX idx_submission_student ON submission (student_id);

CREATE TABLE judge_result (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    submission_id     BIGINT        NOT NULL,
    result_code       VARCHAR(4)    NOT NULL,
    normalized_score  DECIMAL(7, 2) NOT NULL,
    total_time_ms     BIGINT        NOT NULL DEFAULT 0,
    peak_memory_kb    BIGINT        NOT NULL DEFAULT 0,
    agent_id          VARCHAR(64)   NOT NULL DEFAULT 'agent-1',
    result_version    INT           NOT NULL DEFAULT 1,
    created_at        DATETIME      NOT NULL,
    CONSTRAINT uk_judge_result_submission UNIQUE (submission_id),
    CONSTRAINT fk_judge_result_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT ck_judge_result_code CHECK (result_code IN ('PD', 'CE', 'AC', 'WA', 'RE', 'TLE', 'MLE', 'OLE', 'PE', 'SE', 'BSC')),
    CONSTRAINT ck_judge_result_score CHECK (normalized_score >= 0 AND normalized_score <= 100)
);

CREATE TABLE testcase_result (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    judge_result_id BIGINT        NOT NULL,
    testcase_order  INT           NOT NULL,
    status          VARCHAR(4)    NOT NULL,
    score           DECIMAL(7, 2) NOT NULL DEFAULT 0.00,
    time_ms         BIGINT        NOT NULL DEFAULT 0,
    memory_kb       BIGINT        NOT NULL DEFAULT 0,
    created_at      DATETIME      NOT NULL,
    CONSTRAINT uk_testcase_result_order UNIQUE (judge_result_id, testcase_order),
    CONSTRAINT fk_testcase_result_judge FOREIGN KEY (judge_result_id) REFERENCES judge_result (id),
    CONSTRAINT ck_testcase_result_status CHECK (status IN ('PD', 'CE', 'AC', 'WA', 'RE', 'TLE', 'MLE', 'OLE', 'PE', 'SE', 'BSC'))
);

-- ---------- 提交次数计数（行锁保证原子计数与并发拒绝超限） ----------
CREATE TABLE submission_counter (
    assignment_target_id BIGINT NOT NULL,
    student_id          BIGINT NOT NULL,
    attempt_count       INT    NOT NULL DEFAULT 0,
    CONSTRAINT pk_submission_counter PRIMARY KEY (assignment_target_id, student_id)
);

-- ---------- 成绩导出 ----------
CREATE TABLE grade_export (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    requested_by         BIGINT       NOT NULL,
    assignment_target_id BIGINT       NOT NULL,
    format              VARCHAR(8)   NOT NULL,
    filter_student_no   VARCHAR(32)  NULL,
    filter_name_keyword  VARCHAR(64)  NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'QUEUED',
    match_count          INT          NOT NULL DEFAULT 0,
    file_checksum        VARCHAR(64)  NULL,
    storage_key          VARCHAR(128) NULL,
    error_code           VARCHAR(32)  NULL,
    created_at           DATETIME     NOT NULL,
    completed_at         DATETIME     NULL,
    expires_at           DATETIME     NULL,
    CONSTRAINT fk_grade_export_teacher FOREIGN KEY (requested_by) REFERENCES teacher (id),
    CONSTRAINT fk_grade_export_target FOREIGN KEY (assignment_target_id) REFERENCES assignment_target (id),
    CONSTRAINT ck_grade_export_format CHECK (format IN ('XLSX', 'CSV')),
    CONSTRAINT ck_grade_export_status CHECK (status IN ('QUEUED', 'GENERATING', 'READY', 'FAILED', 'EXPIRED'))
);

CREATE INDEX idx_grade_export_target ON grade_export (assignment_target_id);
CREATE INDEX idx_grade_export_status ON grade_export (status, expires_at);

CREATE TABLE grade_export_token (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    grade_export_id BIGINT      NOT NULL,
    token_hash     VARCHAR(64)  NOT NULL,
    issued_by      BIGINT       NOT NULL,
    issued_at      DATETIME     NOT NULL,
    expires_at     DATETIME     NOT NULL,
    used_at        DATETIME     NULL,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uk_grade_export_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_export_token_export FOREIGN KEY (grade_export_id) REFERENCES grade_export (id),
    CONSTRAINT fk_export_token_teacher FOREIGN KEY (issued_by) REFERENCES teacher (id),
    CONSTRAINT ck_grade_export_token_status CHECK (status IN ('ACTIVE', 'USED', 'EXPIRED', 'REVOKED'))
);

CREATE INDEX idx_grade_export_token_export ON grade_export_token (grade_export_id);

-- ---------- 审计事件 ----------
CREATE TABLE audit_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_type   VARCHAR(16)  NOT NULL,
    actor_id     VARCHAR(64)  NOT NULL,
    action       VARCHAR(64)  NOT NULL,
    target_type  VARCHAR(32)  NOT NULL,
    target_id    VARCHAR(64)  NOT NULL,
    before_value MEDIUMTEXT   NULL,
    after_value  MEDIUMTEXT   NULL,
    trace_id     VARCHAR(64)  NULL,
    created_at   DATETIME     NOT NULL
);

CREATE INDEX idx_audit_event_created ON audit_event (created_at);
CREATE INDEX idx_audit_event_target ON audit_event (target_type, target_id);

-- ---------- 本地合成账号（仅开发/内测；Task 5 教务登录适配器上线后禁用） ----------
CREATE TABLE app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    login_name    VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(128) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    teacher_id    BIGINT       NULL,
    student_id    BIGINT       NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at    DATETIME     NOT NULL,
    CONSTRAINT uk_app_user_login UNIQUE (login_name),
    CONSTRAINT fk_app_user_teacher FOREIGN KEY (teacher_id) REFERENCES teacher (id),
    CONSTRAINT fk_app_user_student FOREIGN KEY (student_id) REFERENCES student (id),
    CONSTRAINT ck_app_user_role CHECK (role IN ('ADMIN', 'TEACHER', 'STUDENT')),
    CONSTRAINT ck_app_user_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE auth_token (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash  VARCHAR(64) NOT NULL,
    app_user_id BIGINT      NOT NULL,
    issued_at   DATETIME    NOT NULL,
    expires_at  DATETIME    NOT NULL,
    revoked_at  DATETIME   NULL,
    CONSTRAINT uk_auth_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_token_user FOREIGN KEY (app_user_id) REFERENCES app_user (id)
);

-- ---------- 只读分析视图：按 (AssignmentTarget, 学生) 汇总 ----------
-- total_score   = Σ(题目权重 × 该题最高有效提交分 / 100)，未提交学生为 0
-- ac_problems   = 最高有效提交分达到满分（100）的题目数
-- submission_count = 全部提交次数（含 PD/SE 等非终态与无效提交）
CREATE VIEW v_assignment_analytics AS
SELECT
    tgt.id            AS assignment_target_id,
    stu.id            AS student_id,
    stu.student_no    AS student_no,
    COALESCE(pc.problem_count, 0)  AS problems_total,
    COALESCE(agg.total_score, 0)   AS total_score,
    COALESCE(agg.ac_problems, 0)   AS ac_problems,
    COALESCE(agg.problems_scored, 0) AS problems_scored,
    COALESCE(sc.submission_count, 0) AS submission_count
FROM assignment_target tgt
JOIN student_enrollment se
     ON se.teaching_class_id = tgt.teaching_class_id AND se.active_marker IS NOT NULL
JOIN student stu ON stu.id = se.student_id
LEFT JOIN (
    SELECT a.id AS assignment_id, COUNT(ap.id) AS problem_count
    FROM assignment a
    JOIN assignment_problem ap ON ap.assignment_id = a.id
    GROUP BY a.id
) pc ON pc.assignment_id = tgt.assignment_id
LEFT JOIN (
    SELECT b.assignment_target_id, b.student_id,
           SUM(ap.weight * b.best / 100.0) AS total_score,
           SUM(CASE WHEN b.best >= 100 THEN 1 ELSE 0 END) AS ac_problems,
           COUNT(*) AS problems_scored
    FROM (
        SELECT sub.assignment_target_id, sub.student_id, sub.problem_id,
               MAX(jr.normalized_score) AS best
        FROM submission sub
        JOIN judge_result jr ON jr.submission_id = sub.id
        WHERE sub.judge_status IN ('CE', 'AC', 'WA', 'RE', 'TLE', 'MLE', 'OLE', 'PE', 'BSC')
        GROUP BY sub.assignment_target_id, sub.student_id, sub.problem_id
    ) b
    JOIN assignment_target t3 ON t3.id = b.assignment_target_id
    JOIN assignment_problem ap
         ON ap.assignment_id = t3.assignment_id AND ap.problem_id = b.problem_id
    GROUP BY b.assignment_target_id, b.student_id
) agg ON agg.assignment_target_id = tgt.id AND agg.student_id = se.student_id
LEFT JOIN (
    SELECT sub.assignment_target_id, sub.student_id, COUNT(*) AS submission_count
    FROM submission sub
    GROUP BY sub.assignment_target_id, sub.student_id
) sc ON sc.assignment_target_id = tgt.id AND sc.student_id = se.student_id;
