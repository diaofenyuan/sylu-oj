package oj.judge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

/**
 * RabbitMQ over TLS 事件通道（prod，oj.judge.mq.mode=rabbit）。
 * 连接参数与 TLS 由 spring.rabbitmq.* 配置注入（见 application-prod.yml）。
 * 发布经 OutboxDispatcher 轮询后执行；消费按 task_uuid 幂等，重复投递安全。
 */
public class RabbitJudgeMq {

    private RabbitJudgeMq() {
    }

    @Configuration
    @ConditionalOnProperty(name = "oj.judge.mq.mode", havingValue = "rabbit")
    static class Topology {

        @Bean
        TopicExchange judgeExchange(@Value("${oj.judge.mq.exchange:oj.judge}") String exchange) {
            return new TopicExchange(exchange, true, false);
        }

        @Bean
        Queue judgeQueue(@Value("${oj.judge.mq.queue:oj.judge.tasks}") String queue) {
            return new Queue(queue, true);
        }

        @Bean
        Binding judgeBinding(Queue judgeQueue, TopicExchange judgeExchange) {
            return BindingBuilder.bind(judgeQueue).to(judgeExchange).with("#");
        }
    }

    @Service
    @ConditionalOnProperty(name = "oj.judge.mq.mode", havingValue = "rabbit")
    static class RabbitPublisher implements JudgeEventPublisher {

        private final RabbitTemplate rabbitTemplate;
        private final String exchange;

        RabbitPublisher(RabbitTemplate rabbitTemplate,
                        @Value("${oj.judge.mq.exchange:oj.judge}") String exchange) {
            this.rabbitTemplate = rabbitTemplate;
            this.exchange = exchange;
        }

        /** Outbox 落库时无需立即动作：由 OutboxDispatcher 轮询发布，保证 DB 与消息状态一致。 */
        @Override
        public void onOutboxWritten(JudgeOutbox outbox) {
            // no-op：Rabbit 模式下发布由 OutboxDispatcher 完成
        }

        /** 向 RabbitMQ（TLS）发布单条 Outbox 事件；routing key = 事件类型。 */
        public void sendToBroker(JudgeOutbox outbox) {
            rabbitTemplate.convertAndSend(exchange, outbox.getEventType(), outbox.getPayload());
        }
    }

    @Service
    @ConditionalOnProperty(name = "oj.judge.mq.mode", havingValue = "rabbit")
    static class RabbitListenerBridge {

        private static final Logger log = LoggerFactory.getLogger(RabbitListenerBridge.class);

        private final JudgeTaskDispatcher dispatcher;

        RabbitListenerBridge(JudgeTaskDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @RabbitListener(queues = "${oj.judge.mq.queue:oj.judge.tasks}")
        public void onMessage(String payload) {
            try {
                JudgeTaskDispatcher.Event event = dispatcher.parse(payload);
                dispatcher.handleEvent(event.eventType(), event.taskUuid(), payload);
            } catch (Exception e) {
                // 消息至少投递一次；解析失败的消息不终止监听，告警接入见 Task 8
                log.error("判题事件消费失败 payload 大小={}", payload == null ? 0 : payload.length(), e);
            }
        }
    }
}
