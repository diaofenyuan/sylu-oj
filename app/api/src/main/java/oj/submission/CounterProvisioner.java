package oj.submission;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 提交计数行的幂等创建（REQUIRES_NEW 隔离）。
 *
 * <p>直接用 JDBC 执行，避免 JPA flush 的约束冲突把事务标记为 rollback-only。
 * 并发首次提交时仅一个事务成功 INSERT，其余捕获主键冲突后忽略。
 * 生产为单 API 实例（单物理服务器），计数递增仍由主事务行锁（PESSIMISTIC_WRITE）保证原子。</p>
 */
@Component
public class CounterProvisioner {

    private final JdbcTemplate jdbcTemplate;

    public CounterProvisioner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void provision(Long assignmentTargetId, Long studentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM submission_counter WHERE assignment_target_id = ? AND student_id = ?",
                Integer.class, assignmentTargetId, studentId);
        if (count != null && count > 0) {
            return;
        }
        try {
            jdbcTemplate.update(
                    "INSERT INTO submission_counter (assignment_target_id, student_id, attempt_count) VALUES (?, ?, 0)",
                    assignmentTargetId, studentId);
        } catch (DuplicateKeyException ignored) {
            // 并发下另一事务已创建，忽略
        }
    }
}
