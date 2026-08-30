package oj.judge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内联事件通道（dev/test，oj.judge.mq.mode=inline）：
 * 不依赖外部 MQ，在写入 Outbox 的同一事务内直接派发并标记 PUBLISHED，
 * 事务回滚时派发效果一并回滚，语义与生产 Outbox → MQ 链路一致。
 */
@Service
@ConditionalOnProperty(name = "oj.judge.mq.mode", havingValue = "inline", matchIfMissing = true)
public class InlineJudgeEventPublisher implements JudgeEventPublisher {

    private final JudgeTaskDispatcher dispatcher;
    private final JudgeOutboxRepository outboxRepository;

    public InlineJudgeEventPublisher(JudgeTaskDispatcher dispatcher, JudgeOutboxRepository outboxRepository) {
        this.dispatcher = dispatcher;
        this.outboxRepository = outboxRepository;
    }

    @Override
    @Transactional
    public void onOutboxWritten(JudgeOutbox outbox) {
        dispatcher.handleEvent(outbox.getEventType(), outbox.getTaskUuid(), outbox.getPayload());
        outbox.markPublished(java.time.LocalDateTime.now());
        outboxRepository.save(outbox);
    }
}
