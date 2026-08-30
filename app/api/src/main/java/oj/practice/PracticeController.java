package oj.practice;

import oj.shared.AccessGuard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 学生刷题查询接口；提交仍通过 StudentController 进入统一判题沙盒。 */
@RestController
@RequestMapping("/api/student/practice")
public class PracticeController {

    private final PracticeCatalogService catalogService;
    private final AccessGuard accessGuard;

    public PracticeController(PracticeCatalogService catalogService, AccessGuard accessGuard) {
        this.catalogService = catalogService;
        this.accessGuard = accessGuard;
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
}
