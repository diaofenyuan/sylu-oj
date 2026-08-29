package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface LoginStateRepository extends JpaRepository<LoginState, Long> {

    Optional<LoginState> findByStateHash(String stateHash);

    /** 原子消费：仅当尚未消费时置位，返回影响行数，防止并发重放。 */
    @Modifying
    @Query("update LoginState s set s.consumedAt = :now where s.id = :id and s.consumedAt is null")
    int consumeIfAvailable(@Param("id") Long id, @Param("now") LocalDateTime now);
}
