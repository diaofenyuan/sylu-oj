package oj.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import oj.auth.CurrentUser;
import oj.auth.CurrentUserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 审计服务：与业务同事务写入（REQUIRES_NEW 不使用，避免主事务回滚后审计仍记录成功）。
 * 日志内容不得包含密码、令牌、完整身份证明材料。
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditEventRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditEventRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String targetType, String targetId, Object before, Object after) {
        CurrentUser actor = CurrentUserContext.get();
        String actorType = actor != null ? actor.role().name() : "SYSTEM";
        String actorId = actor != null ? String.valueOf(actor.appUserId()) : "system";
        AuditEvent event = new AuditEvent(actorType, actorId, action, targetType, targetId,
                toJson(before), toJson(after));
        repository.save(event);
        log.info("AUDIT action={} targetType={} targetId={} actor={}/{}", action, targetType, targetId, actorType, actorId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("审计值序列化失败，退化为 toString：{}", e.getMessage());
            return String.valueOf(value);
        }
    }
}
