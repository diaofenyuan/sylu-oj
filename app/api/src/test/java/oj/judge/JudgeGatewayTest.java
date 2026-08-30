package oj.judge;

import oj.assignment.Assignment;
import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.classroom.ClassroomService;
import oj.classroom.TestSupport;
import oj.problem.Problem;
import oj.problem.ProblemService;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import oj.submission.SubmissionCounterRepository;
import oj.submission.SubmissionService;
import oj.testdata.TestdataCipher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 6 验收：提交事务一致性（PENDING 提交 + 审计 + Outbox 同事务）、
 * 事件重复投递幂等、长轮询领取、测试数据按需分发与越权熔断、
 * 结果签名/快照版本/资源上限/得分一致性校验、SE 自动重试不扣次数。
 */
@SpringBootTest
@ActiveProfiles("test")
class JudgeGatewayTest extends TestSupport {

    @Autowired
    private ClassroomService classroomService;
    @Autowired
    private ProblemService problemService;
    @Autowired
    private AssignmentService assignmentService;
    @Autowired
    private SubmissionService submissionService;
    @Autowired
    private JudgeTaskService taskService;
    @Autowired
    private JudgeTaskDispatcher dispatcher;
    @Autowired
    private TestcaseDistributionService distributionService;
    @Autowired
    private JudgeResultGatewayService resultGatewayService;
    @Autowired
    private JudgeAgentService agentService;
    @Autowired
    private SubmissionCounterRepository counterRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final String[] CLEANUP = {
            "testcase_distribution", "judge_outbox", "judge_task", "judge_agent",
            "audit_event", "testcase_result", "judge_result", "submission", "submission_counter",
            "problem_snapshot", "assignment_target", "assignment_problem", "assignment",
            "testcase", "testcase_set", "problem", "problem_bank",
            "student_enrollment", "teacher_assignment", "teaching_class",
            "totp_enrollment", "admin_totp", "identity_adapter_status", "adapter_page_fingerprint",
            "consumed_ticket", "refresh_token", "login_state", "external_identity",
            "auth_token", "app_user", "grade_export_token", "grade_export",
            "teacher", "student", "course", "major", "term"
    };

    private Long targetId;
    private Long problemId;
    private Long studentId;
    private String agentSecret;
    private static final String AGENT = "agent-t6";

    @BeforeEach
    void setupScenario() {
        for (String table : CLEANUP) {
            jdbcTemplate.execute("DELETE FROM " + table);
        }
        asAdmin();
        Long termId = classroomService.createTerm("T6", "学期", LocalDate.now(), LocalDate.now().plusMonths(6)).getId();
        Long courseId = classroomService.createCourse("C6", "课程", BigDecimal.ONE).getId();
        Long classId = classroomService.createTeachingClass(termId, courseId, null, "A6", "A班").getId();
        Long teacherId = classroomService.createTeacher("T6T", "老师").getId();
        studentId = classroomService.createStudent("T6S", "学生").getId();
        classroomService.assignTeacher(classId, teacherId, oj.classroom.TeacherAssignment.Role.PRIMARY);
        classroomService.enrollStudent(classId, studentId, false);

        asTeacher(teacherId);
        Long bankId = problemService.createBank(classId, "题库T6", null).getId();
        Problem problem = problemService.createProblem(bankId, "P6", "题6", "描述",
                languages("CPP"), 1000, 128, 65536, new BigDecimal("100.00"),
                List.of(new ProblemService.TestcaseInput(1, true, "1 2", "3", new BigDecimal("60.00")),
                        new ProblemService.TestcaseInput(2, false, "10 20", "30", new BigDecimal("40.00"))));
        problemService.publishProblem(problem.getId());
        problemId = problem.getId();
        Assignment assignment = assignmentService.createAssignment("作业T6", Assignment.Mode.HOMEWORK,
                List.of(new AssignmentService.CompositionItem(problemId, new BigDecimal("100.00"))));
        assignmentService.publish(assignment.getId(),
                Map.of(classId, new AssignmentService.TargetRule(
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(1), 5, null)));
        AssignmentTarget target = assignmentService.targets(assignment.getId()).get(0);
        targetId = target.getId();

        agentSecret = agentService.register(AGENT, "验收 Agent").secret();
        asStudent(studentId);
    }

    private Long submit(String key) {
        return submissionService.submit(new SubmissionService.SubmitCommand(
                targetId, problemId, "CPP", "int main(){return 0;}", key)).getId();
    }

    private String sign(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(agentSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JudgeResultGatewayService.GatewayResult recordResult(String taskUuid, String code,
                                                                 String score, int version,
                                                                 List<JudgeResultGatewayService.GatewayTestcaseOutcome> tcs) {
        String canonical = taskUuid + "|" + code + "|" + score + "|12|300|" + version;
        return resultGatewayService.record(new JudgeResultGatewayService.GatewayResultCommand(
                taskUuid, AGENT, code, new BigDecimal(score), 12, 300, version, null, "firecracker",
                tcs, sign(canonical)));
    }

    private List<JudgeResultGatewayService.GatewayTestcaseOutcome> acTestcases() {
        return List.of(
                new JudgeResultGatewayService.GatewayTestcaseOutcome(1, "AC", new BigDecimal("60.00"), 5, 100),
                new JudgeResultGatewayService.GatewayTestcaseOutcome(2, "AC", new BigDecimal("40.00"), 7, 120));
    }

    @Test
    void submission_writes_task_and_outbox_in_same_transaction() {
        Long submissionId = submit("k-tx");
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM judge_task WHERE submission_id = ? AND status = 'PENDING' " +
                        "AND dispatched_at IS NOT NULL", Integer.class, submissionId);
        assertThat(tasks).isEqualTo(1);
        Integer outbox = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM judge_outbox WHERE status = 'PUBLISHED' " +
                        "AND event_type = 'JUDGE_TASK_CREATED'", Integer.class);
        assertThat(outbox).isEqualTo(1);

        // 幂等重放：同一提交不产生新任务与新事件
        submit("k-tx");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_outbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void failed_submission_rolls_back_without_task_or_outbox() {
        // 语言不在快照白名单 → 整个提交事务回滚
        assertThatThrownBy(() -> submissionService.submit(new SubmissionService.SubmitCommand(
                targetId, problemId, "JAVA", "int main(){return 0;}", "k-rollback")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.LANGUAGE_NOT_ALLOWED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_task", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_outbox", Integer.class)).isZero();
    }

    @Test
    void undelivered_task_is_not_claimable() {
        submit("k-nodispatch");
        jdbcTemplate.update("UPDATE judge_task SET dispatched_at = NULL");
        assertThat(taskService.claimOnce(AGENT)).isNull();
        jdbcTemplate.update("UPDATE judge_task SET dispatched_at = CURRENT_TIMESTAMP");
        assertThat(taskService.claimOnce(AGENT)).isNotNull();
    }

    @Test
    void duplicate_event_delivery_is_idempotent() {
        submit("k-dup");
        String taskUuid = jdbcTemplate.queryForObject("SELECT task_uuid FROM judge_task", String.class);
        int before = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'JUDGE_TASK_CREATED'", Integer.class);
        dispatcher.handleEvent(JudgeOutbox.EVENT_TASK_CREATED, taskUuid, "{}");
        dispatcher.handleEvent(JudgeOutbox.EVENT_TASK_CREATED, taskUuid, "{}");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_task", Integer.class)).isEqualTo(1);
        // 幂等消费不重复产生任务创建审计
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'JUDGE_TASK_CREATED'", Integer.class))
                .isEqualTo(before);
    }

    @Test
    void claim_payload_contains_code_and_case_refs_but_no_data() {
        submit("k-claim");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);
        assertThat(payload).isNotNull();
        assertThat(payload.code()).isEqualTo("int main(){return 0;}");
        assertThat(payload.languageRuntime()).isEqualTo("gcc-13.3-c++20");
        assertThat(payload.testcaseRefs()).containsExactly(1, 2);
        assertThat(payload.judgeConfig()).contains("timeLimitMs");
        // 载荷不含用例数据内容与任何数据区凭据
        String dumped = payload.code() + payload.judgeConfig() + payload.testcaseRefs();
        assertThat(dumped).doesNotContain("10 20", "expectedOutput", "password", "jdbc");
    }

    @Test
    void happy_path_claim_fetch_result_with_idempotent_duplicate() {
        Long submissionId = submit("k-happy");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);

        // 按任务逐用例加密下发，Agent 密钥可解密
        var case1 = distributionService.distribute(AGENT, payload.taskUuid(), 1);
        assertThat(case1.algo()).isEqualTo("AES-256-GCM");
        String plain = new String(oj.shared.GcmCrypto.open(
                TestdataCipher.deriveKey(AGENT, agentSecret),
                Base64.getDecoder().decode(case1.iv()),
                Base64.getDecoder().decode(case1.ciphertext())), StandardCharsets.UTF_8);
        assertThat(plain).contains("\"input\":\"1 2\"", "\"expectedOutput\":\"3\"");
        Integer distributed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM testcase_distribution WHERE task_uuid = ? AND matched = TRUE",
                Integer.class, payload.taskUuid());
        assertThat(distributed).isEqualTo(1);

        // 结果落库（测试点得分之和 = 归一化得分）
        var outcome = recordResult(payload.taskUuid(), "AC", "100.00", 1, acTestcases());
        assertThat(outcome.duplicate()).isFalse();
        assertThat(outcome.result().getResultCode()).isEqualTo("AC");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT judge_status FROM submission WHERE id = ?", String.class, submissionId)).isEqualTo("AC");

        // 重复投递同版本结果 → 幂等返回；旧版本 → 拒绝
        var replay = recordResult(payload.taskUuid(), "AC", "100.00", 1, acTestcases());
        assertThat(replay.duplicate()).isTrue();
        assertThatThrownBy(() -> recordResult(payload.taskUuid(), "WA", "40.00", 0, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.STALE_RESULT_VERSION);
    }

    @Test
    void result_with_invalid_signature_is_rejected() {
        submit("k-sig");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);
        assertThatThrownBy(() -> resultGatewayService.record(
                new JudgeResultGatewayService.GatewayResultCommand(
                        payload.taskUuid(), AGENT, "AC", new BigDecimal("100.00"), 12, 300, 1, null,
                        "firecracker", acTestcases(), "deadbeef")))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_SIGNATURE_INVALID);
        // 结果未被写入
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_result", Integer.class)).isZero();
    }

    @Test
    void score_must_match_testcase_sum_and_limits() {
        submit("k-sum");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);
        // 测试点得分之和 ≠ 归一化得分（60+40=100 ≠ 90）
        assertThatThrownBy(() -> recordResult(payload.taskUuid(), "WA", "90.00", 1,
                List.of(new JudgeResultGatewayService.GatewayTestcaseOutcome(1, "AC", new BigDecimal("60.00"), 5, 100),
                        new JudgeResultGatewayService.GatewayTestcaseOutcome(2, "WA", new BigDecimal("40.00"), 7, 120))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_SCORE_INVALID);
        // CE 携带得分 → 拒绝
        assertThatThrownBy(() -> recordResult(payload.taskUuid(), "CE", "10.00", 1, null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_SCORE_INVALID);
        // 耗时超出快照上限的宽松上界 → 拒绝
        String canonical = payload.taskUuid() + "|AC|100.00|999999999|300|1";
        assertThatThrownBy(() -> resultGatewayService.record(
                new JudgeResultGatewayService.GatewayResultCommand(
                        payload.taskUuid(), AGENT, "AC", new BigDecimal("100.00"), 999999999L, 300, 1, null,
                        "firecracker", acTestcases(), sign(canonical))))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.RESULT_LIMIT_INVALID);
    }

    @Test
    void se_retry_does_not_consume_attempt() {
        Long submissionId = submit("k-se");
        JudgeTaskService.TaskPayload first = taskService.claimOnce(AGENT);
        var se = recordResult(first.taskUuid(), "SE", "0.00", 1,
                List.of(new JudgeResultGatewayService.GatewayTestcaseOutcome(1, "SE", BigDecimal.ZERO, 0, 0),
                        new JudgeResultGatewayService.GatewayTestcaseOutcome(2, "SE", BigDecimal.ZERO, 0, 0)));
        assertThat(se.result().getResultCode()).isEqualTo("SE");
        // SE 回退提交计数：计数器回到 0
        assertThat(counterRepository.findById(new oj.submission.SubmissionCounter.Pk(targetId, studentId))
                .orElseThrow().getAttemptCount()).isZero();
        // 自动重试生成 attempt 2 任务并可领取
        JudgeTaskService.TaskPayload retry = taskService.claimOnce(AGENT);
        assertThat(retry).isNotNull();
        assertThat(retry.attempt()).isEqualTo(2);
        assertThat(retry.submissionId()).isEqualTo(submissionId);
        assertThat(retry.taskUuid()).isNotEqualTo(first.taskUuid());
        // SE 未消耗次数：学生仍可继续提交（不触发超限拒绝）
        submit("k-se-2");
        // 重试任务以更高版本覆盖 SE 结果
        var outcome = recordResult(retry.taskUuid(), "AC", "100.00", 2, acTestcases());
        assertThat(outcome.result().getResultCode()).isEqualTo("AC");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT judge_status FROM submission WHERE id = ?", String.class, submissionId)).isEqualTo("AC");
    }

    @Test
    void mismatched_testcase_request_suspends_agent() {
        submit("k-mismatch");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);
        assertThatThrownBy(() -> distributionService.distribute(AGENT, payload.taskUuid(), 99))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TESTCASE_MISMATCH);
        // 错配请求已留痕且触发 P1 审计
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM testcase_distribution WHERE matched = FALSE", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'TESTCASE_MISMATCH_DETECTED'", Integer.class))
                .isEqualTo(1);
        // Agent 被熔断：后续认证被拒
        assertThatThrownBy(() -> agentService.authenticate(AGENT, agentSecret))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.AGENT_SUSPENDED);
    }

    @Test
    void other_agent_cannot_access_task_or_results() {
        submit("k-other");
        JudgeTaskService.TaskPayload payload = taskService.claimOnce(AGENT);
        String otherSecret = agentService.register("agent-other", "其他 Agent").secret();
        assertThat(otherSecret).isNotEqualTo(agentSecret);
        // 未领取任务的 Agent 不能拉取用例
        assertThatThrownBy(() -> distributionService.distribute("agent-other", payload.taskUuid(), 1))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).errorCode())
                .isEqualTo(ErrorCode.TASK_NOT_CLAIMABLE);
        // 未领取任务的 Agent 不能回传结果（签名有效也不行）
        String canonical = payload.taskUuid() + "|AC|100.00|12|300|1";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(otherSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String otherSig = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            assertThatThrownBy(() -> resultGatewayService.record(
                    new JudgeResultGatewayService.GatewayResultCommand(
                            payload.taskUuid(), "agent-other", "AC", new BigDecimal("100.00"), 12, 300, 1, null,
                            "firecracker", acTestcases(), otherSig)))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).errorCode())
                    .isEqualTo(ErrorCode.TASK_NOT_CLAIMABLE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM judge_result", Integer.class)).isZero();
    }
}
