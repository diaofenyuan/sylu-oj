package oj.practice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import oj.judge.JudgeRunService;
import oj.judge.JudgeTaskService;
import oj.shared.AccessGuard;
import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 学生刷题接口：查询与自测运行。正式提交仍通过 StudentController 进入统一判题沙盒。
 * 自测运行（local-run）仅在开发/内测配置启用：经 Judge Gateway 交给与提交
 * 判题完全相同的沙盒执行管线（同一 sandbox Runner、同一 cgroup/wait4 资源测量口径），
 * 不落库、不占提交次数、不计分。
 */
@RestController
@RequestMapping("/api/student/practice")
public class PracticeController {

    private static final int MAX_RUN_CODE_BYTES = 262_144;
    private static final int MAX_RUN_INPUT_BYTES = 65_536;

    private final PracticeCatalogService catalogService;
    private final JudgeRunService judgeRunService;
    private final AccessGuard accessGuard;
    private final boolean localRunEnabled;

    public PracticeController(PracticeCatalogService catalogService,
                              JudgeRunService judgeRunService,
                              AccessGuard accessGuard,
                              @Value("${oj.judge.local-run.enabled:false}") boolean localRunEnabled) {
        this.catalogService = catalogService;
        this.judgeRunService = judgeRunService;
        this.accessGuard = accessGuard;
        this.localRunEnabled = localRunEnabled;
    }

    @GetMapping("/problems")
    public List<PracticeCatalogService.PracticeProblem> problems(
            @RequestParam(required = false) String difficulty) {
        return catalogService.listProblems(accessGuard.requireStudent().studentId(), difficulty);
    }

    @GetMapping("/problems/{problemId}")
    public PracticeCatalogService.PracticeProblem problem(@PathVariable Long problemId) {
        return catalogService.detail(accessGuard.requireStudent().studentId(), problemId);
    }

    public record RunRequest(@NotNull Long problemId, @NotBlank String language,
                             @NotBlank String code, String input) {
    }

    @PostMapping("/run")
    public Map<String, Object> run(@Valid @RequestBody RunRequest request) {
        var user = accessGuard.requireStudent();
        if (!localRunEnabled) {
            throw new ApiException(ErrorCode.FORBIDDEN, "自测运行未启用");
        }
        if (request.code().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RUN_CODE_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "代码超过自测长度限制");
        }
        String input = request.input() == null ? "" : request.input();
        if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_RUN_INPUT_BYTES) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "自测输入超过长度限制");
        }
        PracticeCatalogService.PracticeProblem problem =
                catalogService.detail(user.studentId(), request.problemId());
        if (!problem.languages().contains(request.language())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "该题目不支持此语言");
        }
        // 与提交判题同一沙盒：相同快照 judge_config、相同运行时、相同资源测量。
        String judgeConfig = catalogService.judgeConfigFor(user.studentId(), request.problemId());
        JudgeRunService.RunResultPayload outcome = judgeRunService.execute(
                request.language(), JudgeTaskService.runtimeFor(request.language()),
                judgeConfig, request.code(), input);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", outcome.output());
        result.put("stderr", outcome.stderr());
        result.put("compileError", outcome.compileError());
        result.put("exitCode", outcome.exitCode());
        result.put("timeUs", outcome.totalTimeMs() * 1000);
        result.put("peakMemoryKb", outcome.peakMemoryKb());
        result.put("timedOut", outcome.timedOut());
        if (outcome.compileError() != null && !outcome.compileError().isBlank()) {
            result.put("phase", "COMPILE_ERROR");
        } else if (outcome.timedOut()) {
            result.put("phase", "TIMEOUT");
        } else {
            result.put("phase", "FINISHED");
        }
        return result;
    }
}
