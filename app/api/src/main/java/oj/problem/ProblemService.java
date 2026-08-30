package oj.problem;

import oj.audit.AuditService;
import oj.classroom.ClassroomService;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.AuditActions;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 题库服务：教师（主讲或助教）维护本授课班级题库。
 * 题目编辑生成新版本测试集；题面与用例编辑都递增题目版本。
 */
@Service
public class ProblemService {

    public static final Set<String> SUPPORTED_LANGUAGES = Set.of("C", "CPP", "PYTHON", "JAVA");

    private final ProblemBankRepository bankRepository;
    private final ProblemRepository problemRepository;
    private final TestcaseSetRepository testcaseSetRepository;
    private final TestcaseRepository testcaseRepository;
    private final ClassroomService classroomService;
    private final AuditService auditService;
    private final AccessGuard accessGuard;

    public ProblemService(ProblemBankRepository bankRepository, ProblemRepository problemRepository,
                          TestcaseSetRepository testcaseSetRepository, TestcaseRepository testcaseRepository,
                          ClassroomService classroomService, AuditService auditService, AccessGuard accessGuard) {
        this.bankRepository = bankRepository;
        this.problemRepository = problemRepository;
        this.testcaseSetRepository = testcaseSetRepository;
        this.testcaseRepository = testcaseRepository;
        this.classroomService = classroomService;
        this.auditService = auditService;
        this.accessGuard = accessGuard;
    }

    @Transactional
    public ProblemBank createBank(Long teachingClassId, String name, String description) {
        var user = accessGuard.requireAdminOrTeacher();
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), teachingClassId);
        }
        if (bankRepository.existsByTeachingClassIdAndName(teachingClassId, name)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT);
        }
        ProblemBank bank = bankRepository.save(new ProblemBank(teachingClassId, name, description));
        auditService.record("PROBLEM_BANK_CREATED", "PROBLEM_BANK", String.valueOf(bank.getId()),
                null, Map.of("teachingClassId", teachingClassId, "name", name));
        return bank;
    }

    public List<ProblemBank> listBanks(Long teachingClassId) {
        var user = accessGuard.requireAdminOrTeacher();
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), teachingClassId);
        }
        return bankRepository.findByTeachingClassIdOrderByIdAsc(teachingClassId);
    }

    public record TestcaseInput(int orderNum, boolean sample, String input, String expectedOutput,
                                BigDecimal score) {
    }

    @Transactional
    public Problem createProblem(Long bankId, String code, String title, String description,
                                  List<String> languages, int timeLimitMs, int memoryLimitMb,
                                  int outputLimitKb, BigDecimal maxScore, List<TestcaseInput> testcases) {
        var user = accessGuard.requireAdminOrTeacher();
        ProblemBank bank = bankRepository.findById(bankId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "题库不存在"));
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), bank.getTeachingClassId());
        }
        String normalizedCode = requireText(code, "题目编号不能为空");
        String normalizedTitle = requireText(title, "题目标题不能为空");
        if (problemRepository.existsByProblemBankIdAndCode(bankId, normalizedCode)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT, "题目编号已存在于该题库：" + normalizedCode);
        }
        if (problemRepository.existsByProblemBankIdAndTitle(bankId, normalizedTitle)) {
            throw new ApiException(ErrorCode.CODE_CONFLICT, "题目标题已存在于该题库：" + normalizedTitle);
        }
        String languageString = normalizeLanguages(languages);
        Problem problem = problemRepository.save(new Problem(bankId, normalizedCode, normalizedTitle,
                description, languageString, "EASY", timeLimitMs, memoryLimitMb, outputLimitKb, maxScore,
                user.isTeacher() ? user.teacherId() : 0L));
        saveTestcases(problem, testcases == null ? List.of() : testcases);
        auditService.record(AuditActions.PROBLEM_CREATED, "PROBLEM", String.valueOf(problem.getId()),
                null, Map.of("bankId", bankId, "code", normalizedCode, "version", problem.getVersion()));
        return problem;
    }

    @Transactional
    public Problem updateProblem(Long problemId, String title, String description,
                                 List<String> languages, int timeLimitMs, int memoryLimitMb,
                                 int outputLimitKb, List<TestcaseInput> testcases) {
        var user = accessGuard.requireAdminOrTeacher();
        Problem problem = requireAccessibleProblem(problemId, user);
        Map<String, Object> before = Map.of(
                "title", problem.getTitle(),
                "version", problem.getVersion(),
                "timeLimitMs", problem.getTimeLimitMs(),
                "memoryLimitMb", problem.getMemoryLimitMb());
        if (title != null) {
            String normalizedTitle = requireText(title, "题目标题不能为空");
            if (problemRepository.existsByProblemBankIdAndTitleAndIdNot(
                    problem.getProblemBankId(), normalizedTitle, problem.getId())) {
                throw new ApiException(ErrorCode.CODE_CONFLICT, "题目标题已存在于该题库：" + normalizedTitle);
            }
            problem.updateTitle(normalizedTitle);
        }
        if (description != null) {
            problem.updateDescription(description);
        }
        if (languages != null && !languages.isEmpty()) {
            problem.updateLanguages(normalizeLanguages(languages));
        }
        if (timeLimitMs > 0) {
            problem.updateTimeLimitMs(timeLimitMs);
        }
        if (memoryLimitMb > 0) {
            problem.updateMemoryLimitMb(memoryLimitMb);
        }
        if (outputLimitKb > 0) {
            problem.updateOutputLimitKb(outputLimitKb);
        }
        if (testcases != null) {
            saveTestcases(problem, testcases);
        }
        problem.bumpVersion();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("title", problem.getTitle());
        after.put("version", problem.getVersion());
        after.put("timeLimitMs", problem.getTimeLimitMs());
        after.put("memoryLimitMb", problem.getMemoryLimitMb());
        auditService.record(AuditActions.PROBLEM_UPDATED, "PROBLEM", String.valueOf(problemId), before, after);
        return problem;
    }

    @Transactional
    public Problem publishProblem(Long problemId) {
        var user = accessGuard.requireAdminOrTeacher();
        Problem problem = requireAccessibleProblem(problemId, user);
        TestcaseSet latest = requireLatestTestcaseSet(problemId);
        if (testcaseRepository.findByTestcaseSetIdOrderByOrderNumAsc(latest.getId()).isEmpty()) {
            throw new ApiException(ErrorCode.TESTCASE_REQUIRED);
        }
        Map<String, Object> before = Map.of("status", problem.getStatus().name());
        problem.publish();
        auditService.record("PROBLEM_PUBLISHED", "PROBLEM", String.valueOf(problemId),
                before, Map.of("status", "PUBLISHED", "testcaseSetId", latest.getId()));
        return problem;
    }

    public List<Problem> listProblems(Long bankId) {
        var user = accessGuard.requireAdminOrTeacher();
        ProblemBank bank = bankRepository.findById(bankId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "题库不存在"));
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), bank.getTeachingClassId());
        }
        return problemRepository.findByProblemBankIdOrderByIdAsc(bankId);
    }

    /**
     * 学生读取题面：仅 PUBLISHED 且经由作业快照；公开样例用例不暴露隐藏用例。
     */
    public List<Testcase> sampleTestcases(Long testcaseSetId) {
        return testcaseRepository.findByTestcaseSetIdAndSampleOrderByOrderNumAsc(testcaseSetId, true);
    }

    public List<Testcase> allTestcases(Long testcaseSetId) {
        return testcaseRepository.findByTestcaseSetIdOrderByOrderNumAsc(testcaseSetId);
    }

    public TestcaseSet requireLatestTestcaseSet(Long problemId) {
        return testcaseSetRepository.findFirstByProblemIdOrderByVersionDesc(problemId)
                .orElseThrow(() -> new ApiException(ErrorCode.TESTCASE_REQUIRED));
    }

    public Problem requireProblem(Long problemId) {
        return problemRepository.findById(problemId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "题目不存在"));
    }

    /**
     * 统计给定题目中处于 PUBLISHED 状态的数量（组卷校验用）。
     */
    public long countPublishedByIds(Set<Long> problemIds) {
        if (problemIds.isEmpty()) {
            return 0;
        }
        return problemRepository.countByIdInAndStatus(problemIds, Problem.Status.PUBLISHED);
    }

    /**
     * 对象级授权：教师必须通过题库所属教学班的有效授课关系访问题目。
     */
    public Problem requireAccessibleProblem(Long problemId, oj.auth.CurrentUser user) {
        Problem problem = requireProblem(problemId);
        ProblemBank bank = bankRepository.findById(problem.getProblemBankId())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "题库不存在"));
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), bank.getTeachingClassId());
        }
        return problem;
    }

    private void saveTestcases(Problem problem, List<TestcaseInput> testcases) {
        int nextVersion = testcaseSetRepository.findFirstByProblemIdOrderByVersionDesc(problem.getId())
                .map(ts -> ts.getVersion() + 1)
                .orElse(1);
        TestcaseSet set = testcaseSetRepository.save(new TestcaseSet(problem.getId(), nextVersion));
        List<Testcase> rows = new ArrayList<>();
        int order = 1;
        for (TestcaseInput tc : testcases) {
            if (tc.input() == null || tc.input().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "测试用例输入不能为空");
            }
            BigDecimal score = tc.score() == null ? BigDecimal.TEN : tc.score();
            if (score.compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "测试用例分值不能为负");
            }
            rows.add(new Testcase(set.getId(), tc.orderNum() > 0 ? tc.orderNum() : order,
                    tc.sample(), tc.input(), tc.expectedOutput(), score));
            order++;
        }
        testcaseRepository.saveAll(rows);
    }

    private String requireText(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, message);
        }
        return value.trim();
    }

    private String normalizeLanguages(List<String> languages) {
        if (languages == null || languages.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "至少支持一种语言");
        }
        List<String> normalized = languages.stream()
                .map(String::trim)
                .map(String::toUpperCase)
                .distinct()
                .toList();
        for (String lang : normalized) {
            if (!SUPPORTED_LANGUAGES.contains(lang)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的语言：" + lang);
            }
        }
        return String.join(",", normalized);
    }
}
