package oj.judge;

/**
 * 判题事件通道：Outbox 行落库后由通道负责投递。
 * inline（dev/test）：立即同步派发给 JudgeTaskDispatcher，同一事务内标记 PUBLISHED；
 * rabbit（prod）：由 OutboxDispatcher 轮询 Outbox 后经 TLS 发布到 RabbitMQ。
 */
public interface JudgeEventPublisher {

    void onOutboxWritten(JudgeOutbox outbox);
}
