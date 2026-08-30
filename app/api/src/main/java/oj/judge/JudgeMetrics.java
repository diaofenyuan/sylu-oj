package oj.judge;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 判题域指标（Task 8，供 ops/monitoring/alert.rules.yml 消费）：
 * - oj_judge_tasks_pending / claimed：队列积压（P1 阈值 20/10 分钟）
 * - oj_judge_outbox_pending：事件发布通道积压
 * - oj_judge_agents_active / suspended：Agent 存活与熔断
 * - oj_testcase_mismatch_total：错配用例请求（P1，设计 6.3）
 * - oj_sandbox_mode_info：沙箱运行模式（gvisor=1 即降级运行，P1）
 * 查询走轻量 COUNT，抓取周期 30s，不影响判题事务。
 */
@Component
public class JudgeMetrics {

    private final JdbcTemplate jdbcTemplate;
    private final AtomicLong sandboxFirecracker = new AtomicLong();
    private final AtomicLong sandboxGvisor = new AtomicLong();
    private final AtomicLong testcaseMismatch = new AtomicLong();

    public JudgeMetrics(MeterRegistry registry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        Gauge.builder("oj_judge_tasks_pending", this, m -> count(
                        "SELECT COUNT(*) FROM judge_task WHERE status = 'PENDING'"))
                .description("等待领取的判题任务数").register(registry);
        Gauge.builder("oj_judge_tasks_claimed", this, m -> count(
                        "SELECT COUNT(*) FROM judge_task WHERE status = 'CLAIMED'"))
                .description("已领取未完成的判题任务数").register(registry);
        Gauge.builder("oj_judge_outbox_pending", this, m -> count(
                        "SELECT COUNT(*) FROM judge_outbox WHERE status = 'PENDING'"))
                .description("等待发布的判题事件数").register(registry);
        Gauge.builder("oj_judge_agents_active", this, m -> count(
                        "SELECT COUNT(*) FROM judge_agent WHERE status = 'ACTIVE'"))
                .description("活跃判题 Agent 数").register(registry);
        Gauge.builder("oj_judge_agents_suspended", this, m -> count(
                        "SELECT COUNT(*) FROM judge_agent WHERE status = 'SUSPENDED'"))
                .description("被熔断暂停的 Agent 数").register(registry);
        Gauge.builder("oj_sandbox_mode_info", sandboxFirecracker, AtomicLong::doubleValue)
                .tags(Tags.of(Tag.of("mode", "firecracker")))
                .description("沙箱运行模式标记（1=当前模式）").register(registry);
        Gauge.builder("oj_sandbox_mode_info", sandboxGvisor, AtomicLong::doubleValue)
                .tags(Tags.of(Tag.of("mode", "gvisor")))
                .register(registry);
        FunctionCounter.builder("oj_testcase_mismatch_total", testcaseMismatch, AtomicLong::doubleValue)
                .description("与当前任务不匹配的用例请求累计数")
                .register(registry);
    }

    /** 沙箱模式随结果回传更新（Agent 上报 SandboxMode）。 */
    public void observeSandboxMode(String mode) {
        if ("gvisor".equals(mode)) {
            sandboxGvisor.set(1);
            sandboxFirecracker.set(0);
        } else if ("firecracker".equals(mode)) {
            sandboxFirecracker.set(1);
            sandboxGvisor.set(0);
        }
    }

    /** 错配请求计数（JudgeGuard 调用，独立于审计事务的进程内计数）。 */
    public void incrementTestcaseMismatch() {
        testcaseMismatch.incrementAndGet();
    }

    private double count(String sql) {
        try {
            Long v = jdbcTemplate.queryForObject(sql, Long.class);
            return v == null ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }
}
