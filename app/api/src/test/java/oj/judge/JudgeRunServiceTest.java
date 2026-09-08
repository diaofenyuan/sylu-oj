package oj.judge;

import oj.shared.ApiException;
import oj.shared.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class JudgeRunServiceTest {
    private final ExecutorService workers = Executors.newCachedThreadPool();

    @AfterEach
    void stopWorkers() throws InterruptedException {
        workers.shutdownNow();
        assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    private Future<JudgeRunService.RunResultPayload> submit(JudgeRunService service) {
        return workers.submit(() -> service.execute("PYTHON", "cpython-3.12", "{}", "print(3)", ""));
    }

    private JudgeRunService.RunTaskPayload claim(JudgeRunService service, String agent) {
        AtomicReference<JudgeRunService.RunTaskPayload> task = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(3)).until(() -> {
            task.set(service.claim(agent));
            return task.get() != null;
        });
        return task.get();
    }

    private JudgeRunService.RunResultPayload result(String uuid) {
        return new JudgeRunService.RunResultPayload(uuid, "3\n", "", "", 0, 1, 100, false, "test");
    }

    private Queue<?> queue(JudgeRunService service) {
        return (Queue<?>) ReflectionTestUtils.getField(service, "queue");
    }

    @Test
    void onlyExactClaimOwnerCanCompleteAndCapacityIsReused() throws Exception {
        JudgeRunService service = new JudgeRunService(1, 10);
        Future<JudgeRunService.RunResultPayload> waiting = submit(service);
        var task = claim(service, "agent-10");
        assertThatThrownBy(() -> service.complete("agent-1", task.runUuid(), result(task.runUuid())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AGENT_UNAUTHORIZED));
        assertThat(waiting.isDone()).isFalse();
        service.complete("agent-10", task.runUuid(), result(task.runUuid()));
        assertThat(waiting.get(3, TimeUnit.SECONDS).output()).isEqualTo("3\n");
        assertThatThrownBy(() -> service.complete("agent-10", task.runUuid(), result(task.runUuid())))
                .isInstanceOf(ApiException.class);
        Future<JudgeRunService.RunResultPayload> next = submit(service);
        var nextTask = claim(service, "agent-10");
        service.complete("agent-10", nextTask.runUuid(), result(nextTask.runUuid()));
        assertThat(next.get(3, TimeUnit.SECONDS).output()).isEqualTo("3\n");
    }

    @Test
    void unclaimedResultIsRejectedWithoutNullPointerException() throws Exception {
        JudgeRunService service = new JudgeRunService(1, 10);
        Future<?> waiting = submit(service);
        await().atMost(Duration.ofSeconds(3)).until(() -> !queue(service).isEmpty());
        var task = (JudgeRunService.RunTaskPayload) queue(service).peek();
        assertThatThrownBy(() -> service.complete("agent", task.runUuid(), result(task.runUuid())))
                .isInstanceOfSatisfying(ApiException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.AGENT_UNAUTHORIZED));
        waiting.cancel(true);
        await().atMost(Duration.ofSeconds(3)).until(() -> queue(service).isEmpty());
    }

    @Test
    void timeoutRemovesUnclaimedSourceFromQueue() throws Exception {
        JudgeRunService service = new JudgeRunService(1, 1);
        Future<?> waiting = submit(service);
        assertThatThrownBy(() -> waiting.get(3, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class);
        assertThat(queue(service)).isEmpty();
        assertThat(service.claim("agent")).isNull();
        Future<?> next = submit(service);
        var task = claim(service, "agent");
        service.complete("agent", task.runUuid(), result(task.runUuid()));
        next.get(3, TimeUnit.SECONDS);
    }

    @Test
    void concurrentAdmissionNeverExceedsCapacity() throws Exception {
        JudgeRunService service = new JudgeRunService(2, 10);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch rejected = new CountDownLatch(18);
        for (int i = 0; i < 20; i++) {
            workers.submit(() -> {
                try {
                    start.await();
                    service.execute("PYTHON", "cpython-3.12", "{}", "print(3)", "");
                } catch (ApiException e) {
                    if (e.getMessage().contains("队列已满")) rejected.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        assertThat(rejected.await(3, TimeUnit.SECONDS)).isTrue();
        var first = claim(service, "agent");
        var second = claim(service, "agent");
        assertThat(service.claim("agent")).isNull();
        assertThatThrownBy(() -> service.execute("PYTHON", "cpython-3.12", "{}", "", ""))
                .isInstanceOf(ApiException.class).hasMessageContaining("队列已满");
        service.complete("agent", first.runUuid(), result(first.runUuid()));
        service.complete("agent", second.runUuid(), result(second.runUuid()));
    }
}
