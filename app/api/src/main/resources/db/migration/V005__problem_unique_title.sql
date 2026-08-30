-- ---------- 题库题目防重复：同一题库内标题唯一 ----------
-- 编号唯一由 V001 的 uk_problem_code (problem_bank_id, code) 保证；
-- 此处补标题唯一约束，防止同题库出现同名题目（服务层先行校验并给出友好报错）。
ALTER TABLE problem ADD CONSTRAINT uk_problem_bank_title UNIQUE (problem_bank_id, title);
