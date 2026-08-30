package oj.judge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 判题事件消费：把任务标记为"已派发"（可被长轮询领取）。
 * 消费按 task_uuid 幂等：重复投递的事件只会设置一次 dispatched_at。
 */
@Service
public class JudgeTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JudgeTaskDispatcher.class);

    private final JudgeTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public JudgeTaskDispatcher(JudgeTaskRepository taskRepository, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void handleEvent(String eventType, String taskUuid, String payloadJson) {
        JudgeTask task = taskRepository.findByTaskUuid(taskUuid).orElse(null);
        if (task == null) {
            log.warn("判题事件对应任务不存在 event={} taskUuid={}", eventType, taskUuid);
            return;
        }
        task.markDispatched(LocalDateTime.now());
        taskRepository.save(task);
    }

    /** 从事件 JSON 中解析 eventType/taskUuid（Rabbit 消费路径使用）。 */
    public record Event(String eventType, String taskUuid) {
    }

    public Event parse(String payloadJson) {
        try {
            JsonNode node = objectMapper.readTree(payloadJson);
            return new Event(node.path("eventType").asText(), node.path("taskUuid").asText());
        } catch (Exception e) {
            throw new IllegalArgumentException("判题事件载荷解析失败", e);
        }
    }
}
