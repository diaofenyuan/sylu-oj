package oj.export;

import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 导出异步 worker 与定时清理调度。独立组件，保证事务与异步代理生效。
 */
@Component
public class ExportScheduler {

    private final GradeExportService gradeExportService;

    public ExportScheduler(GradeExportService gradeExportService) {
        this.gradeExportService = gradeExportService;
    }

    @Async
    public void generateAsync(Long exportId) {
        try {
            gradeExportService.generate(exportId);
        } catch (Exception ignored) {
            // 状态已在 generate 内落库为 FAILED
        }
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void scheduledCleanup() {
        gradeExportService.cleanup();
    }
}
