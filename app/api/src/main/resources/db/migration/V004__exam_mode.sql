-- ---------- Task 9: 考试模式（锁定、双人审批、归档、申诉、抽查） ----------

ALTER TABLE assignment ADD COLUMN exam_locked BOOLEAN NOT NULL DEFAULT FALSE;

-- 考试锁定：冻结判题运行时标识与策略校验和
CREATE TABLE exam_lock (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    assignment_id   BIGINT       NOT NULL,
    locked_by       BIGINT       NOT NULL,
    locked_at       DATETIME     NOT NULL,
    runtime_ids     VARCHAR(256) NOT NULL,
    policy_checksum VARCHAR(64)  NOT NULL,
    reason          VARCHAR(512) NULL,
    CONSTRAINT uk_exam_lock_assignment UNIQUE (assignment_id),
    CONSTRAINT fk_exam_lock_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id)
);

-- 双人审批：请求人与批准人不得为同一人；批准人须持有已确认的 TOTP（二次认证）
CREATE TABLE exam_approval (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT       NOT NULL,
    action        VARCHAR(32)  NOT NULL,
    payload       MEDIUMTEXT   NULL,
    reason        VARCHAR(512) NULL,
    requested_by  BIGINT       NOT NULL,
    approved_by   BIGINT       NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at    DATETIME     NOT NULL,
    decided_at    DATETIME     NULL,
    CONSTRAINT fk_exam_approval_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id),
    CONSTRAINT ck_exam_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED'))
);

CREATE INDEX idx_exam_approval_pending ON exam_approval (assignment_id, status);

-- 考试结束不可变归档（JSONL + SHA-256 校验和）
CREATE TABLE exam_archive (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    assignment_id   BIGINT       NOT NULL,
    storage_key     VARCHAR(128) NOT NULL,
    checksum        VARCHAR(64)  NOT NULL,
    submission_count INT         NOT NULL,
    created_at      DATETIME     NOT NULL,
    CONSTRAINT uk_exam_archive_assignment UNIQUE (assignment_id),
    CONSTRAINT fk_exam_archive_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id)
);

-- 申诉复判：差异不得自动采用任一版本，须人工复核后 RESOLVED
CREATE TABLE exam_appeal (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    assignment_id  BIGINT        NOT NULL,
    submission_id  BIGINT        NOT NULL,
    requested_by   BIGINT        NOT NULL,
    reason         VARCHAR(512)  NOT NULL,
    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    original_code  VARCHAR(4)    NULL,
    original_score DECIMAL(7, 2) NULL,
    rejudged_code  VARCHAR(4)    NULL,
    rejudged_score DECIMAL(7, 2) NULL,
    diff_note      VARCHAR(512)  NULL,
    resolved_by    BIGINT        NULL,
    created_at     DATETIME      NOT NULL,
    resolved_at    DATETIME      NULL,
    CONSTRAINT fk_exam_appeal_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT ck_exam_appeal_status CHECK (status IN ('PENDING', 'REJUDGED', 'RESOLVED', 'REJECTED'))
);

CREATE INDEX idx_exam_appeal_submission ON exam_appeal (submission_id);

-- 随机抽查（≥5%，选样种子留痕）
CREATE TABLE exam_spot_check (
    id            BIGINT   AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT   NOT NULL,
    submission_id BIGINT   NOT NULL,
    seed          VARCHAR(64) NOT NULL,
    sampled_at    DATETIME NOT NULL,
    CONSTRAINT uk_exam_spot_check UNIQUE (assignment_id, submission_id),
    CONSTRAINT fk_exam_spot_check_assignment FOREIGN KEY (assignment_id) REFERENCES assignment (id)
);
