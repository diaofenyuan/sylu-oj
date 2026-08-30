package oj.judge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface TestcaseDistributionRepository extends JpaRepository<TestcaseDistribution, Long> {

    long countByAgentIdAndDistributedAtAfterAndMatchedTrue(String agentId, LocalDateTime since);
}
