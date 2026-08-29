-- =====================================================================
-- V002__identity_login.sql
-- Task 5：教务网页登录适配器与会话安全
--
-- 关键约束设计：
--   1. external_identity 以学号/工号（external_no）为唯一外部身份键，
--      禁止邮箱/姓名等可变字段作主键；断言指纹用于识别学号复用/身份变更。
--   2. login_state 一次性 state（state_hash 唯一 + consumed_at），
--      绑定来源 IP 与精确回调地址，短有效期。
--   3. consumed_ticket 记录已消费票据哈希（唯一），保留期覆盖票据最大有效期，
--      拒绝票据重放。
--   4. refresh_token 单次轮换：consumed_at 标记已轮换；同 family 重用触发
--      整链撤销（服务层）。仅存 SHA-256 哈希。
--   5. app_user.password_hash 放开为 NULL：生产经教务适配器登录的账号
--      不设置任何本地密码（禁止本地密码回退）。
-- =====================================================================

-- ---------- 外部身份（学号/工号 → 本地账号绑定） ----------
CREATE TABLE external_identity (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    external_no           VARCHAR(32)  NOT NULL,
    external_type         VARCHAR(16)  NOT NULL,
    app_user_id           BIGINT       NOT NULL,
    assertion_fingerprint VARCHAR(64)  NOT NULL,
    status                VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    failed_attempts       INT          NOT NULL DEFAULT 0,
    locked_until          DATETIME     NULL,
    bound_at              DATETIME     NOT NULL,
    last_login_at         DATETIME     NULL,
    created_at            DATETIME     NOT NULL,
    CONSTRAINT uk_external_identity_no UNIQUE (external_no),
    CONSTRAINT uk_external_identity_user UNIQUE (app_user_id),
    CONSTRAINT fk_external_identity_user FOREIGN KEY (app_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_external_identity_type CHECK (external_type IN ('STUDENT', 'STAFF')),
    CONSTRAINT ck_external_identity_status CHECK (status IN ('ACTIVE', 'PENDING_CONFIRMATION', 'DISABLED'))
);

CREATE INDEX idx_external_identity_status ON external_identity (status);

-- ---------- 一次性登录 state ----------
CREATE TABLE login_state (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    state_hash   VARCHAR(64)  NOT NULL,
    callback_url VARCHAR(255) NOT NULL,
    source_ip    VARCHAR(64)  NOT NULL,
    created_at   DATETIME     NOT NULL,
    expires_at   DATETIME     NOT NULL,
    consumed_at  DATETIME     NULL,
    CONSTRAINT uk_login_state_hash UNIQUE (state_hash)
);

-- ---------- 已消费票据（防重放） ----------
CREATE TABLE consumed_ticket (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ticket_hash VARCHAR(64) NOT NULL,
    consumed_at DATETIME    NOT NULL,
    expires_at  DATETIME    NOT NULL,
    CONSTRAINT uk_consumed_ticket UNIQUE (ticket_hash)
);

CREATE INDEX idx_consumed_ticket_expires ON consumed_ticket (expires_at);

-- ---------- 刷新令牌（单次轮换、可撤销、整链可追溯） ----------
CREATE TABLE refresh_token (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash   VARCHAR(64) NOT NULL,
    app_user_id  BIGINT      NOT NULL,
    family_id    VARCHAR(64) NOT NULL,
    issued_at    DATETIME    NOT NULL,
    expires_at   DATETIME    NOT NULL,
    consumed_at  DATETIME    NULL,
    revoked_at   DATETIME    NULL,
    CONSTRAINT uk_refresh_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (app_user_id) REFERENCES app_user (id)
);

CREATE INDEX idx_refresh_token_user ON refresh_token (app_user_id);
CREATE INDEX idx_refresh_token_family ON refresh_token (family_id);

-- ---------- 适配器页面指纹与运行状态 ----------
CREATE TABLE adapter_page_fingerprint (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    page_url         VARCHAR(255) NOT NULL,
    fingerprint_hash VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL,
    captured_at      DATETIME     NOT NULL,
    CONSTRAINT ck_adapter_fingerprint_status CHECK (status IN ('OK', 'CHANGED'))
);

CREATE INDEX idx_adapter_fingerprint_captured ON adapter_page_fingerprint (captured_at);

CREATE TABLE identity_adapter_status (
    id            BIGINT      NOT NULL PRIMARY KEY,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    halted_reason VARCHAR(64) NULL,
    updated_at    DATETIME    NOT NULL,
    CONSTRAINT ck_adapter_status CHECK (status IN ('ACTIVE', 'HALTED'))
);

INSERT INTO identity_adapter_status (id, status, halted_reason, updated_at)
VALUES (1, 'ACTIVE', NULL, CURRENT_TIMESTAMP);

-- ---------- 管理员 TOTP（密文存储）与一次性绑定令牌 ----------
CREATE TABLE admin_totp (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    app_user_id      BIGINT       NOT NULL,
    secret_encrypted VARCHAR(512) NOT NULL,
    confirmed        VARCHAR(8)   NOT NULL DEFAULT 'N',
    created_at       DATETIME     NOT NULL,
    CONSTRAINT uk_admin_totp_user UNIQUE (app_user_id),
    CONSTRAINT fk_admin_totp_user FOREIGN KEY (app_user_id) REFERENCES app_user (id),
    CONSTRAINT ck_admin_totp_confirmed CHECK (confirmed IN ('Y', 'N'))
);

CREATE TABLE totp_enrollment (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    token_hash  VARCHAR(64) NOT NULL,
    app_user_id BIGINT      NOT NULL,
    expires_at  DATETIME    NOT NULL,
    consumed_at DATETIME    NULL,
    CONSTRAINT uk_totp_enrollment_token UNIQUE (token_hash),
    CONSTRAINT fk_totp_enrollment_user FOREIGN KEY (app_user_id) REFERENCES app_user (id)
);

-- ---------- 本地合成账号密码列说明 ----------
-- 教务适配器登录账号不设置可用本地密码：服务层写入随机不可用哈希（保持
-- NOT NULL，避免 H2/MySQL ALTER 语法差异），本地密码路径天然无法命中。

