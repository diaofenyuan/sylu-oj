package oj.practice;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
 * 自测运行（local-run）仅在开发/内测配置启用，不落库、不占提交次数、不计分。
 */
@RestController
@RequestMapping("/api/student/practice")
public class PracticeController {

    private static final int MAX_RUN_CODE_BYTES = 262_144;
    private static final int MAX_RUN_INPUT_BYTES = 65_536;

    private final PracticeCatalogService catalogService;
    private final LocalCodeRunner localCodeRunner;
    private final AccessGuard accessGuard;
    private final boolean localRunEnabled;

    public PracticeController(PracticeCatalogService catalogService,
                              LocalCodeRunner localCodeRunner,
                              AccessGuard accessGuard,
                              @Value("${oj.judge.local-run.enabled:false}") boolean localRunEnabled) {
        this.catalogService = catalogService;
        this.localCodeRunner = localCodeRunner;
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
        LocalCodeRunner.RunOutcome outcome =
                localCodeRunner.run(request.language(), request.code(), input);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("output", outcome.output());
        result.put("stderr", outcome.stderr());
        result.put("compileError", outcome.compileError());
        result.put("exitCode", outcome.exitCode());
        result.put("timeMs", outcome.timeMs());
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
