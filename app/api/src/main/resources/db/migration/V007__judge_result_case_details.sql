-- V007: 扩展 judge_result 表，添加测试点详情字段
-- 用于存储每个测试点的详细性能数据（时间、内存、状态）

ALTER TABLE judge_result ADD COLUMN case_details JSON COMMENT '测试点详情: [{order, status, timeMs, memoryKb, score}]';
