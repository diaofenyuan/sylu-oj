package oj.analytics;

import oj.assignment.AssignmentService;
import oj.assignment.AssignmentTarget;
import oj.classroom.ClassroomService;
import oj.classroom.Student;
import oj.classroom.StudentRepository;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 成绩分析服务：总分、通过率、提交次数、状态分布与班级排名。
 *
 * <p>排名规则：按两位小数总分降序，采用并列竞赛排名（1, 2, 2, 4），
 * 学号仅作为并列记录的稳定展示键，不用于打破并列名次。</p>
 */
@Service
public class AnalyticsService {

    private final AssignmentAnalyticsRepository analyticsRepository;
    private final AssignmentService assignmentService;
    private final ClassroomService classroomService;
    private final StudentRepository studentRepository;
    private final oj.submission.SubmissionRepository submissionRepository;
    private final AccessGuard accessGuard;

    public AnalyticsService(AssignmentAnalyticsRepository analyticsRepository,
                            AssignmentService assignmentService,
                            ClassroomService classroomService,
                            StudentRepository studentRepository,
                            oj.submission.SubmissionRepository submissionRepository,
                            AccessGuard accessGuard) {
        this.analyticsRepository = analyticsRepository;
        this.assignmentService = assignmentService;
        this.classroomService = classroomService;
        this.studentRepository = studentRepository;
        this.submissionRepository = submissionRepository;
        this.accessGuard = accessGuard;
    }

    public record StudentAnalyticsRow(Long studentId, String studentNo, String name,
                                      BigDecimal totalScore, BigDecimal passRate,
                                      long submissionCount, long acProblems,
                                      long problemsTotal, int rank,
                                      Map<String, Long> statusDistribution) {
    }

    public record TargetAnalytics(Long targetId, List<StudentAnalyticsRow> rows,
                                  Map<String, Long> classStatusDistribution) {
    }

    /**
     * 教师查看自己授课目标班级的分析（主讲或助教）；管理员可查看全部。
     */
    @Transactional(readOnly = true)
    public TargetAnalytics targetAnalyticsForTeacher(Long targetId) {
        var user = accessGuard.requireAdminOrTeacher();
        AssignmentTarget target = assignmentService.requireTargetById(targetId);
        if (user.isTeacher()) {
            classroomService.requireActiveAssignment(user.teacherId(), target.getTeachingClassId());
        }
        return buildAnalytics(target);
    }

    /**
     * 学生查看自己的分析行。
     */
    @Transactional(readOnly = true)
    public StudentAnalyticsRow ownAnalytics(Long studentId, Long targetId) {
        AssignmentTarget target = assignmentService.requireAccessibleTargetForStudent(studentId, targetId);
        List<StudentAnalyticsRow> rows = buildAnalytics(target).rows();
        return rows.stream()
                .filter(r -> r.studentId().equals(studentId))
                .findFirst()
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }

    /**
     * 导出与分析共用：构建带排名的分析行（内部方法，不做角色校验）。
     */
    public TargetAnalytics buildAnalytics(AssignmentTarget target) {
        List<AssignmentAnalytics> raw = analyticsRepository.findByAssignmentTargetId(target.getId());
        Map<String, Long> classStatus = new LinkedHashMap<>();
        for (oj.submission.Submission s : submissionsOf(target.getId())) {
            classStatus.merge(s.getJudgeStatus(), 1L, Long::sum);
        }
        Map<Long, String> names = new LinkedHashMap<>();
        for (AssignmentAnalytics a : raw) {
            Student student = studentRepository.findById(a.getStudentId()).orElse(null);
            names.put(a.getStudentId(), student == null ? "" : student.getName());
        }
        // 排名：先按两位小数总分降序，学号仅做稳定排序；并列名次采用竞赛排名
        List<AssignmentAnalytics> sorted = new ArrayList<>(raw);
        sorted.sort(Comparator
                .comparing((AssignmentAnalytics a) -> a.getTotalScore().setScale(2, RoundingMode.HALF_UP))
                .reversed()
                .thenComparing(AssignmentAnalytics::getStudentNo));
        List<StudentAnalyticsRow> rows = new ArrayList<>(sorted.size());
        int position = 0;
        BigDecimal lastScore = null;
        int lastRank = 0;
        for (AssignmentAnalytics a : sorted) {
            position++;
            BigDecimal score = a.getTotalScore().setScale(2, RoundingMode.HALF_UP);
            int rank;
            if (lastScore != null && score.compareTo(lastScore) == 0) {
                rank = lastRank;
            } else {
                rank = position;
                lastRank = position;
                lastScore = score;
            }
            BigDecimal passRate = a.getProblemsTotal() == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.valueOf(a.getAcProblems())
                            .multiply(BigDecimal.valueOf(100))
                            .divide(BigDecimal.valueOf(a.getProblemsTotal()), 2, RoundingMode.HALF_UP);
            rows.add(new StudentAnalyticsRow(a.getStudentId(), a.getStudentNo(),
                    names.getOrDefault(a.getStudentId(), ""), score, passRate,
                    a.getSubmissionCount(), a.getAcProblems(), a.getProblemsTotal(),
                    rank, statusDistributionFor(target.getId(), a.getStudentId())));
        }
        return new TargetAnalytics(target.getId(), rows, classStatus);
    }

    private Map<String, Long> statusDistributionFor(Long targetId, Long studentId) {
        Map<String, Long> dist = new LinkedHashMap<>();
        for (oj.submission.Submission s : submissionsOf(targetId)) {
            if (s.getStudentId().equals(studentId)) {
                dist.merge(s.getJudgeStatus(), 1L, Long::sum);
            }
        }
        return dist;
    }

    private List<oj.submission.Submission> submissionsOf(Long targetId) {
        return submissionRepository.findByAssignmentTargetIdOrderByIdAsc(targetId);
    }
}
