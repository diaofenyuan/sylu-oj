package oj.judge;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
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

    private final long waitTimeoutSeconds;
    private final Semaphore capacity;

    public JudgeRunService(@Value("${oj.judge.run-max-pending:64}") int maxPendingRuns,
                           @Value("${oj.judge.run-wait-timeout-seconds:60}") long waitTimeoutSeconds) {
        if (maxPendingRuns <= 0 || waitTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("自测队列容量和等待超时必须大于零");
        }
        this.capacity = new Semaphore(maxPendingRuns);
        this.waitTimeoutSeconds = waitTimeoutSeconds;
    }

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
        // 用原子配额覆盖排队和执行阶段，避免并发请求同时通过 size 检查。
        if (!capacity.tryAcquire()) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行队列已满，请稍后重试");
        }
        String runUuid = java.util.UUID.randomUUID().toString();
        RunTaskPayload payload = new RunTaskPayload(runUuid, language, languageRuntime,
                judgeConfig, code, input);
        PendingRun run = new PendingRun(payload);
        pending.put(runUuid, run);
        queue.add(payload);
        try {
            RunResultPayload result = run.future.get(waitTimeoutSeconds, TimeUnit.SECONDS);
            return result;
        } catch (TimeoutException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR,
                    "自测运行等待超时：判题代理繁忙或不可用，请稍后重试");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行被中断");
        } catch (java.util.concurrent.ExecutionException e) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, "自测运行内部错误："
                    + (e.getCause() == null ? e.getMessage() : e.getCause().getMessage()));
        } finally {
            // Agent 离线时也必须移除队列中的源码和输入，不能依赖下一次领取清理。
            pending.remove(runUuid, run);
            queue.remove(payload);
            capacity.release();
        }
    }

    /** Agent 领取：出队并在 pending 表标记认领人；无效/已被认领的条目直接跳过。 */
    public RunTaskPayload claim(String agentId) {
        while (true) {
            RunTaskPayload payload = queue.poll();
            if (payload == null) {
                return null;
            }
            PendingRun run = pending.get(payload.runUuid());
            if (run == null) {
                continue; // 已超时清理，跳过
            }
            if (!run.claimedBy.compareAndSet(null, agentId)) {
                continue;
            }
            return payload;
        }
    }

    /** Agent 回传结果：校验认领人后完成等待方并移除条目。 */
    public RunResultPayload complete(String agentId, String runUuid, RunResultPayload result) {
        PendingRun run = pending.get(runUuid);
        if (run == null || agentId == null || !agentId.equals(run.claimedBy.get())
                || !pending.remove(runUuid, run)) {
            throw new ApiException(ErrorCode.AGENT_UNAUTHORIZED, "自测运行任务不存在或认领方不符");
        }
        run.future.complete(new RunResultPayload(runUuid, result.output(), result.stderr(),
                result.compileError(), result.exitCode(), result.totalTimeMs(),
                result.peakMemoryKb(), result.timedOut(), result.sandboxMode()));
        return result;
    }
}
