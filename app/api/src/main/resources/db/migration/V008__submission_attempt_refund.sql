-- 同一次提交即使多次遇到系统错误，也只能退还一次提交额度。
ALTER TABLE submission ADD COLUMN attempt_refunded BOOLEAN NOT NULL DEFAULT FALSE;

-- 兼容升级前的系统错误记录，包括已经重判成功的提交；不改写历史计数。
UPDATE submission
SET attempt_refunded = TRUE
WHERE judge_status = 'SE'
   OR EXISTS (
       SELECT 1 FROM audit_event ae
       WHERE ae.target_type = 'SUBMISSION'
         AND ae.target_id = CONCAT('', submission.id)
         AND ae.action = 'JUDGE_RESULT_RECORDED'
         AND REPLACE(ae.after_value, ' ', '') LIKE '%"resultCode":"SE"%'
   );
