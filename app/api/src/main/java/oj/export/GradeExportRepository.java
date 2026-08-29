package oj.export;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GradeExportRepository extends JpaRepository<GradeExport, Long> {

    List<GradeExport> findByRequestedByOrderByIdDesc(Long requestedBy);

    List<GradeExport> findByStatusAndExpiresAtBefore(GradeExport.Status status, java.time.LocalDateTime now);

    Optional<GradeExport> findByIdAndRequestedBy(Long id, Long requestedBy);
}
