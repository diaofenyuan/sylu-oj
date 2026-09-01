package oj.judge;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 自测运行通道（dev/内测）：学生自测运行与提交判题走同一沙盒执行管线。
 * 请求入内存队列（不落库、不计分、不占提交次数），由 Judge Agent 领取执行，
 * 结果经 result 接口回传并唤醒等待方（PracticeController）。
 */
@Service
public class JudgeRunService {

    public record RunTaskPayload(String runUuid, String language, String languageRuntime,
                                 String judgeConfig, String code, String input) {
    }

    public record RunResultPayload(String runUuid, String output, String stderr, String compileError,
                                   int exitCode, long totalTimeMs, long peakMemoryKb,
                                   boolean timedOut, String sandboxMode) {
    }

    private static final long WAIT_TIMEOUT_SECONDS = 60;
    private static final int MAX_PENDING_RUNS = 64;

    private static final class PendingRun {
        final RunTaskPayload payload;
        final CompletableFuture<RunResultPayload> future = new CompletableFuture<>();
        final AtomicReference<String> claimedBy = new AtomicReference<>();

        PendingRun(RunTaskPayload payload) {
            this.payload = payload;
        }
    }

    private final ConcurrentLinkedQueue<RunTaskPayload> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, PendingRun> pending = new ConcurrentHashMap<>();

    /** 入队并等待沙盒结果；agent 不可用或超时抛 INTERNAL_ERROR（HTTP 层映射 500/超时提示）。 */
    public RunResultPayload execute(String language, String languageRuntime, String judgeConfig,
                                    String code, String input) {
        if (pending.size() >= MAX_PENDING_RUNS) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行队列已满，请稍后重试");
        }
        String runUuid = java.util.UUID.randomUUID().toString();
        RunTaskPayload payload = new RunTaskPayload(runUuid, language, languageRuntime,
                judgeConfig, code, input);
        PendingRun run = new PendingRun(payload);
        pending.put(runUuid, run);
        queue.add(payload);
        try {
            RunResultPayload result = run.future.get(WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return result;
        } catch (TimeoutException e) {
            pending.remove(runUuid);
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "自测运行等待超时：判题代理繁忙或不可用，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.remove(runUuid);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            pending.remove(runUuid);
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行内部错误："
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        }
    }

    /** Agent 领取：出队并在 pending 表标记认领人；无效/已被认领的条目直接跳过。 */
    public RunTaskPayload claim(String agentId) {
        LocalDateTime now = LocalDateTime.now();
        while (true) {
            RunTaskPayload payload = queue.poll();
            if (payload == null) {
                return null;
            }
            PendingRun run = pending.get(payload.runUuid());
            if (run == null) {
                continue; // 已超时清理，跳过
            }
            if (!run.claimedBy.compareAndSet(null, agentId + "@" + now)) {
                continue;
            }
            return payload;
        }
    }

    /** Agent 回传结果：校验认领人后完成等待方并移除条目。 */
    public RunResultPayload complete(String agentId, String runUuid, RunResultPayload result) {
        PendingRun run = pending.get(runUuid);
        if (run == null || !run.claimedBy.get().startsWith(agentId)) {
            throw new ApiException(ErrorCode.AGENT_UNAUTHORIZED, "自测运行任务不存在或认领方不符");
        }
        pending.remove(runUuid);
        run.future.complete(new RunResultPayload(runUuid, result.output(), result.stderr(),
                result.compileError(), result.exitCode(), result.totalTimeMs(),
                result.peakMemoryKb(), result.timedOut(), result.sandboxMode()));
        return result;
    }
}
