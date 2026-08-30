package oj.exam;

import oj.shared.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 考试模式工作流（Task 9，仅 PRIMARY 教师/管理员）：
 * 锁定（双人审批）→ 锁定后修改须审批放行 → 结束后不可变归档 →
 * 申诉复判（差异不自动采用）→ ≥5% 随机抽查。
 * HOMEWORK 不进入本流程（ExamService 校验 mode=EXAM）。
 */
@RestController
@RequestMapping("/api/teacher/exams")
public class ExamController {

    private final ExamService examService;

    public ExamController(ExamService examService) {
        this.examService = examService;
    }

    public record ChangeRequest(String action, String reason) {
    }

    public record DecisionRequest(String reason) {
    }

    public record AppealRequest(Long submissionId, String reason) {
    }

    public record ResolveRequest(boolean adoptRejudged, String basis) {
    }

    public record ReviseScoreRequest(Long submissionId, java.math.BigDecimal newScore, String reason) {
    }

    @PostMapping("/{assignmentId}/changes")
    public Map<String, Object> requestChange(@PathVariable Long assignmentId,
                                             @RequestBody ChangeRequest request) {
        if (request.action() == null || request.action().isBlank()) {
            throw new ApiException(oj.shared.ErrorCode.VALIDATION_FAILED, "缺少审批动作");
        }
        ExamApproval approval = examService.requestChange(assignmentId, request.action(), request.reason());
        return Map.of("approvalId", approval.getId(), "status", approval.getStatus(),
                "action", approval.getAction());
    }

    @PostMapping("/approvals/{approvalId}/approve")
    public Map<String, Object> approve(@PathVariable Long approvalId,
                                       @RequestBody(required = false) DecisionRequest request) {
        ExamApproval approval = examService.decide(approvalId, true, request == null ? null : request.reason());
        return Map.of("approvalId", approval.getId(), "status", approval.getStatus());
    }

    @PostMapping("/approvals/{approvalId}/reject")
    public Map<String, Object> reject(@PathVariable Long approvalId,
                                      @RequestBody(required = false) DecisionRequest request) {
        ExamApproval approval = examService.decide(approvalId, false, request == null ? null : request.reason());
        return Map.of("approvalId", approval.getId(), "status", approval.getStatus());
    }

    @GetMapping("/{assignmentId}/approvals")
    public List<ExamApproval> approvals(@PathVariable Long assignmentId) {
        return examService.approvals(assignmentId);
    }

    @PostMapping("/{assignmentId}/archive")
    public Map<String, Object> archive(@PathVariable Long assignmentId) {
        ExamArchive archive = examService.exportArchive(assignmentId);
        return Map.of("assignmentId", archive.getAssignmentId(),
                "checksum", archive.getChecksum(),
                "submissions", archive.getSubmissionCount());
    }

    @PostMapping("/{assignmentId}/appeals")
    public Map<String, Object> appeal(@PathVariable Long assignmentId,
                                      @RequestBody AppealRequest request) {
        if (request.submissionId() == null || request.reason() == null || request.reason().isBlank()) {
            throw new ApiException(oj.shared.ErrorCode.VALIDATION_FAILED, "缺少提交 ID 或申诉理由");
        }
        ExamAppeal appeal = examService.createAppeal(assignmentId, request.submissionId(), request.reason());
        return Map.of("appealId", appeal.getId(), "status", appeal.getStatus(),
                "originalCode", appeal.getOriginalCode(), "originalScore", appeal.getOriginalScore());
    }

    @GetMapping("/{assignmentId}/appeals")
    public List<ExamAppeal> appeals(@PathVariable Long assignmentId) {
        return examService.appeals(assignmentId);
    }

    @PostMapping("/{assignmentId}/appeals/{appealId}/resolve")
    public Map<String, Object> resolve(@PathVariable Long assignmentId, @PathVariable Long appealId,
                                       @RequestBody ResolveRequest request) {
        ExamAppeal appeal = examService.resolveAppeal(assignmentId, appealId,
                request.adoptRejudged(), request.basis());
        return Map.of("appealId", appeal.getId(), "status", appeal.getStatus(),
                "diffNote", appeal.getDiffNote() == null ? "" : appeal.getDiffNote());
    }

    @PostMapping("/{assignmentId}/spot-check")
    public List<Map<String, Object>> spotCheck(@PathVariable Long assignmentId) {
        return examService.spotCheck(assignmentId).stream()
                .map(s -> Map.<String, Object>of("submissionId", s.getSubmissionId(), "seed", s.getSeed()))
                .toList();
    }

    @PostMapping("/{assignmentId}/revise-score")
    public Map<String, Object> reviseScore(@PathVariable Long assignmentId,
                                           @RequestBody ReviseScoreRequest request) {
        var result = examService.reviseScore(assignmentId, request.submissionId(),
                request.newScore(), request.reason());
        return Map.of("submissionId", request.submissionId(),
                "resultCode", result.getResultCode(),
                "normalizedScore", result.getNormalizedScore(),
                "resultVersion", result.getResultVersion());
    }

    @GetMapping("/{assignmentId}/lock-status")
    public Map<String, Object> lockStatus(@PathVariable Long assignmentId) {
        return Map.of("locked", examService.isLocked(assignmentId));
    }
}
