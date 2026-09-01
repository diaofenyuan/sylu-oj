-- ---------- 作业时间窗口可留空：publish_at / deadline 允许为 NULL ----------
-- NULL 语义：publish_at=NULL 立即开放（无发布时间限制）；
--            deadline=NULL  不限截止时间。用于“定时发布 + 留空不限制”。
ALTER TABLE assignment_target MODIFY COLUMN publish_at DATETIME NULL;
ALTER TABLE assignment_target MODIFY COLUMN deadline DATETIME NULL;

-- 原 CHECK (deadline > publish_at) 在缺省值（NULL）下不可用，
-- 重定义为 NULL 容忍的窗口顺序约束。
ALTER TABLE assignment_target DROP CONSTRAINT ck_assignment_target_window;
ALTER TABLE assignment_target ADD CONSTRAINT ck_assignment_target_window
    CHECK (deadline IS NULL OR publish_at IS NULL OR deadline > publish_at);
