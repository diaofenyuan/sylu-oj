package oj.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Outbox 调度器（Rabbit 模式）：轮询 PENDING 事件并经 TLS 发布到 RabbitMQ，
 * 成功后标记 PUBLISHED。失败保持 PENDING 由下一轮重试，保证数据库与
 * 消息状态最终一致。发布失败告警接入 Task 8。
 */
@Service
@ConditionalOnProperty(name = "oj.judge.mq.mode", havingValue = "rabbit")
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final JudgeOutboxRepository outboxRepository;
    private final RabbitJudgeMq.RabbitPublisher publisher;

    public OutboxDispatcher(JudgeOutboxRepository outboxRepository, RabbitJudgeMq.RabbitPublisher publisher) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${oj.judge.outbox-poll-ms:1000}")
    @Transactional
    public void dispatchPending() {
        List<JudgeOutbox> pending = outboxRepository.findTop100ByStatusOrderByIdAsc(JudgeOutbox.PENDING);
        for (JudgeOutbox outbox : pending) {
            try {
                publisher.sendToBroker(outbox);
                outbox.markPublished(LocalDateTime.now());
                outboxRepository.save(outbox);
            } catch (Exception e) {
                log.warn("Outbox 事件发布失败 id={} event={} taskUuid={}",
                        outbox.getId(), outbox.getEventType(), outbox.getTaskUuid());
                break;
            }
        }
    }
}
