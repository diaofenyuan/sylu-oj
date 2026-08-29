package oj.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    Page<AuditEvent> findByTargetTypeAndTargetIdOrderByCreatedAtDesc(String targetType, String targetId, Pageable pageable);

    @Query("select e from AuditEvent e where e.targetType = :type and e.targetId = :targetId "
            + "order by e.id desc")
    Page<AuditEvent> latestForTarget(@Param("type") String type, @Param("targetId") String targetId, Pageable pageable);
}
