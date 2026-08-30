package oj.judge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface JudgeOutboxRepository extends JpaRepository<JudgeOutbox, Long> {

    List<JudgeOutbox> findTop100ByStatusOrderByIdAsc(String status);

    Optional<JudgeOutbox> findByEventTypeAndTaskUuid(String eventType, String taskUuid);
}
