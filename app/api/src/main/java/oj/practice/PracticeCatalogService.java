package oj.practice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import oj.assignment.Assignment;
import oj.assignment.AssignmentProblem;
import oj.assignment.AssignmentRepository;
import oj.assignment.AssignmentTarget;
import oj.assignment.AssignmentTargetRepository;
import oj.assignment.ProblemSnapshot;
import oj.assignment.ProblemSnapshotRepository;
import oj.classroom.ClassroomService;
import oj.classroom.StudentEnrollment;
import oj.classroom.TeacherAssignment;
import oj.classroom.TeacherAssignmentRepository;
import oj.classroom.TeachingClassRepository;
import oj.problem.Problem;
import oj.problem.ProblemBank;
import oj.problem.ProblemBankRepository;
import oj.problem.ProblemRepository;
import oj.problem.Testcase;
import oj.problem.TestcaseRepository;
import oj.problem.TestcaseSet;
import oj.problem.TestcaseSetRepository;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import oj.submission.JudgeResultRepository;
import oj.submission.Submission;
import oj.submission.SubmissionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 学生刷题目录：使用现有题目、快照、作业目标和提交表，确保刷题提交
 * 与普通作业一样进入判题沙盒。目录按教学班懒加载且幂等创建。
 */
@Service
public class PracticeCatalogService {

    public static final String BANK_NAME = "系统刷题题库";
    public static final String ASSIGNMENT_TITLE = "系统刷题中心";
    private static final String PRACTICE_SCORING_RULES = "{\"catalog\":\"SYLU_OJ_PRACTICE_V2\",\"policy\":\"bestScore\"}";
    private static final List<String> LEVELS = List.of("EASY", "BASIC", "INTERMEDIATE", "HARD");
    private static final List<String> LANGUAGES = List.of("C", "CPP", "PYTHON", "JAVA");

    private final ClassroomService classroomService;
    private final TeacherAssignmentRepository teacherAssignmentRepository;
    private final TeachingClassRepository teachingClassRepository;
    private final ProblemBankRepository bankRepository;
    private final ProblemRepository problemRepository;
    private final TestcaseSetRepository testcaseSetRepository;
    private final TestcaseRepository testcaseRepository;
    private final AssignmentRepository assignmentRepository;
    private final oj.assignment.AssignmentProblemRepository assignmentProblemRepository;
    private final AssignmentTargetRepository targetRepository;
    private final ProblemSnapshotRepository snapshotRepository;
    private final SubmissionRepository submissionRepository;
    private final JudgeResultRepository judgeResultRepository;
    private final ObjectMapper objectMapper;

    public PracticeCatalogService(ClassroomService classroomService,
                                  TeacherAssignmentRepository teacherAssignmentRepository,
                                  TeachingClassRepository teachingClassRepository,
                                  ProblemBankRepository bankRepository,
                                  ProblemRepository problemRepository,
                                  TestcaseSetRepository testcaseSetRepository,
                                  TestcaseRepository testcaseRepository,
                                  AssignmentRepository assignmentRepository,
                                  oj.assignment.AssignmentProblemRepository assignmentProblemRepository,
                                  AssignmentTargetRepository targetRepository,
                                  ProblemSnapshotRepository snapshotRepository,
                                  SubmissionRepository submissionRepository,
                                  JudgeResultRepository judgeResultRepository,
                                  ObjectMapper objectMapper) {
        this.classroomService = classroomService;
        this.teacherAssignmentRepository = teacherAssignmentRepository;
        this.teachingClassRepository = teachingClassRepository;
        this.bankRepository = bankRepository;
        this.problemRepository = problemRepository;
        this.testcaseSetRepository = testcaseSetRepository;
        this.testcaseRepository = testcaseRepository;
        this.assignmentRepository = assignmentRepository;
        this.assignmentProblemRepository = assignmentProblemRepository;
        this.targetRepository = targetRepository;
        this.snapshotRepository = snapshotRepository;
        this.submissionRepository = submissionRepository;
        this.judgeResultRepository = judgeResultRepository;
        this.objectMapper = objectMapper;
    }

    public record Sample(int orderNum, String input, String expectedOutput) {
    }

    public record PracticeProblem(Long problemId, String code, String title, String description,
                                  String difficulty, List<String> languages, Long assignmentTargetId,
                                  BigDecimal bestScore, String status, List<Sample> samples,
                                  int timeLimitMs, int memoryLimitMb) {
    }

    @Transactional
    public List<PracticeProblem> listProblems(Long studentId, String difficulty) {
        Catalog catalog = ensureCatalog(studentId);
        String normalized = difficulty == null ? null : difficulty.trim().toUpperCase(Locale.ROOT);
        if (normalized != null && !normalized.isBlank() && !LEVELS.contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "不支持的题目难度");
        }
        return catalog.snapshots().stream()
                .map(snapshot -> toProblem(catalog.target().getId(), studentId, snapshot,
                        normalized != null && !normalized.isBlank()))
                .filter(problem -> normalized == null || normalized.isBlank()
                        || problem.difficulty().equals(normalized))
                .toList();
    }

    @Transactional
    public PracticeProblem detail(Long studentId, Long problemId) {
        Catalog catalog = ensureCatalog(studentId);
        ProblemSnapshot snapshot = catalog.snapshots().stream()
                .filter(item -> item.getProblemId().equals(problemId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "刷题题目不存在"));
        return toProblem(catalog.target().getId(), studentId, snapshot, true);
    }

    private PracticeProblem toProblem(Long targetId, Long studentId, ProblemSnapshot snapshot,
                                     boolean includeSamples) {
        List<String> languages = List.of(snapshot.getLanguages().split(","));
        List<Submission> submissions = submissionRepository
                .findByAssignmentTargetIdAndProblemIdAndStudentIdOrderByCreatedAtAsc(
                        targetId, snapshot.getProblemId(), studentId);
        BigDecimal best = BigDecimal.ZERO;
        String status = "UNATTEMPTED";
        for (Submission submission : submissions) {
            status = submission.getJudgeStatus();
            var result = judgeResultRepository.findBySubmissionId(submission.getId()).orElse(null);
            if (result != null && result.getNormalizedScore().compareTo(best) > 0) {
                best = result.getNormalizedScore();
            }
            if (best.compareTo(BigDecimal.valueOf(100)) >= 0) {
                status = "AC";
            }
        }
        List<Sample> samples = includeSamples
                ? testcaseRepository.findByTestcaseSetIdAndSampleOrderByOrderNumAsc(snapshot.getTestcaseSetId(), true)
                .stream().map(tc -> new Sample(tc.getOrderNum(), tc.getInput(), tc.getExpectedOutput())).toList()
                : List.of();
        String code = problemRepository.findById(snapshot.getProblemId())
                .map(Problem::getCode).orElse("P" + snapshot.getProblemId());
        Problem problem = problemRepository.findById(snapshot.getProblemId()).orElse(null);
        return new PracticeProblem(snapshot.getProblemId(), code, snapshot.getTitle(),
                snapshot.getDescription(), difficultyOf(snapshot), languages, targetId, best, status, samples,
                problem == null ? 10000 : problem.getTimeLimitMs(),
                problem == null ? 256 : problem.getMemoryLimitMb());
    }

    private String difficultyOf(ProblemSnapshot snapshot) {
        return problemRepository.findById(snapshot.getProblemId())
                .map(Problem::getDifficulty).orElse("EASY");
    }

    private record Catalog(AssignmentTarget target, List<ProblemSnapshot> snapshots) {
    }

    private Catalog ensureCatalog(Long studentId) {
        StudentEnrollment enrollment = classroomService.requireActiveEnrollmentAny(studentId);
        Long classId = enrollment.getTeachingClassId();
        // 对教学班加悲观锁，保证多学生首次访问时只有一个线程创建系统目录。
        teachingClassRepository.findByIdForUpdate(classId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "教学班不存在"));
        AssignmentTarget existing = targetRepository.findByTeachingClassIdAndStatusOrderByIdAsc(
                        classId, AssignmentTarget.Status.PUBLISHED).stream()
                .filter(target -> assignmentRepository.findById(target.getAssignmentId())
                        .filter(assignment -> ASSIGNMENT_TITLE.equals(assignment.getTitle())
                                && assignment.getMode() == Assignment.Mode.HOMEWORK)
                        .map(assignment -> isPracticeCatalog(assignment, target))
                        .orElse(false))
                .findFirst().orElse(null);
        if (existing != null) {
            return new Catalog(existing, snapshotRepository.findByAssignmentIdOrderByProblemIdAsc(existing.getAssignmentId()));
        }

        TeacherAssignment teacher = teacherAssignmentRepository
                .findByTeachingClassIdAndActiveMarkerIsNotNullOrderByIdAsc(classId).stream()
                .filter(item -> item.getRole() == TeacherAssignment.Role.PRIMARY)
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.FORBIDDEN, "该教学班尚未配置主讲教师，暂不能开启刷题中心"));
        ProblemBank bank = bankRepository.findByTeachingClassIdOrderByIdAsc(classId).stream()
                .filter(item -> BANK_NAME.equals(item.getName())).findFirst()
                .orElseGet(() -> bankRepository.save(new ProblemBank(classId, BANK_NAME, "系统提供的分级刷题题库")));

        List<Problem> problems = new ArrayList<>();
        for (int level = 0; level < LEVELS.size(); level++) {
            String levelName = LEVELS.get(level);
            for (int index = 1; index <= 25; index++) {
                PracticeQuestionCatalog.Question question = PracticeQuestionCatalog.question(level, index);
                String code = "PRACTICE-" + levelName + "-" + String.format("%02d", index);
                Problem problem = problemRepository.findByProblemBankIdAndCode(bank.getId(), code)
                        .map(current -> {
                            current.updateTitle(question.title());
                            current.updateDescription(question.description());
                            current.bumpVersion();
                            return current;
                        })
                        .orElseGet(() -> new Problem(bank.getId(), code, question.title(), question.description(),
                                String.join(",", LANGUAGES), levelName, 10000, 256, 65536,
                                BigDecimal.valueOf(100), teacher.getTeacherId()));
                problem.publish();
                problem = problemRepository.save(problem);
                // 每个版本生成新的用例集合：1 个公开样例，做对满分 100 分。
                int version = testcaseSetRepository.findFirstByProblemIdOrderByVersionDesc(problem.getId())
                        .map(TestcaseSet::getVersion).orElse(0) + 1;
                TestcaseSet set = testcaseSetRepository.save(new TestcaseSet(problem.getId(), version));
                testcaseRepository.save(new Testcase(set.getId(), 1, true,
                        question.sampleInput(), question.sampleOutput(), BigDecimal.valueOf(100)));
                problems.add(problem);
            }
        }

        Assignment assignment = assignmentRepository.save(new Assignment(ASSIGNMENT_TITLE,
                Assignment.Mode.HOMEWORK, teacher.getTeacherId()));
        int order = 1;
        for (Problem problem : problems) {
            assignmentProblemRepository.save(new AssignmentProblem(assignment.getId(), problem.getId(), order++, BigDecimal.ONE));
        }
        LocalDateTime now = LocalDateTime.now();
        AssignmentTarget target = targetRepository.save(new AssignmentTarget(assignment.getId(), classId,
                now.minusMinutes(1), now.plusYears(10), 1000, PRACTICE_SCORING_RULES));
        assignment.publish();
        for (Problem problem : problems) {
            TestcaseSet set = testcaseSetRepository.findFirstByProblemIdOrderByVersionDesc(problem.getId()).orElseThrow();
            snapshotRepository.save(new ProblemSnapshot(assignment.getId(), problem.getId(), problem.getVersion(), set.getId(),
                    problem.getTitle(), problem.getDescription(), problem.getLanguages(), judgeConfig(problem), checksum(problem, set)));
        }
        return new Catalog(target, snapshotRepository.findByAssignmentIdOrderByProblemIdAsc(assignment.getId()));
    }

    /** 仅认领由本服务生成的最新版（V2）固定 100 题目录；
     * 旧版占位目录（V1）不匹配，将在下一次访问时被替换重建。 */
    private boolean isPracticeCatalog(Assignment assignment, AssignmentTarget target) {
        if (!PRACTICE_SCORING_RULES.equals(target.getScoringRules())) {
            return false;
        }
        List<ProblemSnapshot> snapshots = snapshotRepository.findByAssignmentIdOrderByProblemIdAsc(assignment.getId());
        if (snapshots.size() != LEVELS.size() * 25) {
            return false;
        }
        java.util.Set<String> codes = new java.util.HashSet<>();
        java.util.Map<String, Integer> levelCounts = new java.util.HashMap<>();
        for (ProblemSnapshot snapshot : snapshots) {
            Problem problem = problemRepository.findById(snapshot.getProblemId()).orElse(null);
            if (problem == null || !codes.add(problem.getCode())
                    || !problem.getCode().startsWith("PRACTICE-")
                    || !LEVELS.contains(problem.getDifficulty())) {
                return false;
            }
            String expectedPrefix = "PRACTICE-" + problem.getDifficulty() + "-";
            if (!problem.getCode().startsWith(expectedPrefix)
                    || !problem.getCode().matches("PRACTICE-(EASY|BASIC|INTERMEDIATE|HARD)-\\d{2}")) {
                return false;
            }
            int questionNumber = Integer.parseInt(problem.getCode().substring(expectedPrefix.length()));
            if (questionNumber < 1 || questionNumber > 25) {
                return false;
            }
            levelCounts.merge(problem.getDifficulty(), 1, Integer::sum);
        }
        return codes.size() == LEVELS.size() * 25
                && LEVELS.stream().allMatch(level -> levelCounts.getOrDefault(level, 0) == 25);
    }

    private String judgeConfig(Problem problem) {
        try {
            return objectMapper.writeValueAsString(Map.of("timeLimitMs", problem.getTimeLimitMs(),
                    "memoryLimitMb", problem.getMemoryLimitMb(), "outputLimitKb", problem.getOutputLimitKb(),
                    "maxScore", problem.getMaxScore()));
        } catch (JsonProcessingException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "刷题题目配置生成失败");
        }
    }

    private String checksum(Problem problem, TestcaseSet set) {
        try {
            String text = problem.getId() + "|" + problem.getVersion() + "|" + problem.getTitle() + "|"
                    + problem.getDescription() + "|" + problem.getLanguages() + "|" + set.getId();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "刷题题目校验摘要生成失败");
        }
    }
}
