-- ---------- Task 6: Judge Gateway、Outbox 与测试数据分发 ----------

-- 判题任务：一次提交判题尝试对应一条任务（attempt 区分 SE 自动重试）。
-- 任务载荷只含代码、语言运行时标识与用例序号引用；测试数据由 Agent 逐个用例拉取。
CREATE TABLE judge_task (
    id                   BIGINT       AUTO_INCREMENT PRIMARY KEY,
    task_uuid            VARCHAR(36)  NOT NULL,
    submission_id        BIGINT       NOT NULL,
    assignment_id        BIGINT       NOT NULL,
    assignment_target_id BIGINT       NOT NULL,
    problem_id           BIGINT       NOT NULL,
    snapshot_id          BIGINT       NOT NULL,
    snapshot_version     INT          NOT NULL,
    testcase_set_id      BIGINT       NOT NULL,
    language             VARCHAR(16)  NOT NULL,
    language_runtime     VARCHAR(64)  NOT NULL,
    status               VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt              INT          NOT NULL DEFAULT 1,
    claimed_by           VARCHAR(64)  NULL,
    claimed_at           DATETIME     NULL,
    lease_expires_at     DATETIME     NULL,
    dispatched_at        DATETIME     NULL,
    created_at           DATETIME     NOT NULL,
    updated_at           DATETIME     NOT NULL,
    CONSTRAINT uk_judge_task_uuid UNIQUE (task_uuid),
    CONSTRAINT uk_judge_task_attempt UNIQUE (submission_id, attempt),
    CONSTRAINT fk_judge_task_submission FOREIGN KEY (submission_id) REFERENCES submission (id),
    CONSTRAINT fk_judge_task_snapshot FOREIGN KEY (snapshot_id) REFERENCES problem_snapshot (id),
    CONSTRAINT ck_judge_task_status CHECK (status IN ('PENDING', 'CLAIMED', 'COMPLETED')),
    CONSTRAINT ck_judge_task_attempt CHECK (attempt >= 1)
);

CREATE INDEX idx_judge_task_claim ON judge_task (status, dispatched_at, id);
CREATE INDEX idx_judge_task_lease ON judge_task (status, lease_expires_at);
CREATE INDEX idx_judge_task_submission ON judge_task (submission_id);

-- 判题 Outbox：与提交/任务同一事务写入，由调度器向 RabbitMQ（TLS）投递。
-- (event_type, task_uuid) 唯一：重复事件天然幂等。
CREATE TABLE judge_outbox (
    id           BIGINT       AUTO_INCREMENT PRIMARY KEY,
    event_type   VARCHAR(64)  NOT NULL,
    task_uuid    VARCHAR(36)  NOT NULL,
    payload      MEDIUMTEXT   NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    created_at   DATETIME     NOT NULL,
    published_at DATETIME     NULL,
    CONSTRAINT uk_judge_outbox_event UNIQUE (event_type, task_uuid),
    CONSTRAINT ck_judge_outbox_status CHECK (status IN ('PENDING', 'PUBLISHED'))
);

CREATE INDEX idx_judge_outbox_pending ON judge_outbox (status, id);

-- 判题 Agent 注册表：身份 + 签名密钥（AES-GCM 密文落库）+ 熔断状态。
CREATE TABLE judge_agent (
    id               VARCHAR(64)  PRIMARY KEY,
    display_name     VARCHAR(128) NOT NULL,
    secret_encrypted VARCHAR(256) NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    suspension_reason VARCHAR(256) NULL,
    suspended_at     DATETIME     NULL,
    last_seen_at     DATETIME     NULL,
    registered_at    DATETIME     NOT NULL,
    CONSTRAINT ck_judge_agent_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- 测试数据分发记录：逐用例审计 + 异常拉取（错配/超速）检测依据。
CREATE TABLE testcase_distribution (
    id             BIGINT       AUTO_INCREMENT PRIMARY KEY,
    agent_id       VARCHAR(64)  NOT NULL,
    task_uuid      VARCHAR(36)  NOT NULL,
    problem_id     BIGINT       NOT NULL,
    testcase_order INT          NOT NULL,
    matched        BOOLEAN      NOT NULL DEFAULT TRUE,
    distributed_at DATETIME     NOT NULL
);

CREATE INDEX idx_testcase_distribution_agent ON testcase_distribution (agent_id, distributed_at);
CREATE INDEX idx_testcase_distribution_task ON testcase_distribution (task_uuid);
