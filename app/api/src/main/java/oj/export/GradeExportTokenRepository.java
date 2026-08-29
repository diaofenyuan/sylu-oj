package oj.export;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GradeExportTokenRepository extends JpaRepository<GradeExportToken, Long> {

    Optional<GradeExportToken> findByTokenHash(String tokenHash);

    List<GradeExportToken> findByGradeExportId(Long gradeExportId);

    List<GradeExportToken> findByStatusAndExpiresAtBefore(GradeExportToken.Status status, java.time.LocalDateTime now);
}
