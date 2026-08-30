package oj.exam;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExamArchiveRepository extends JpaRepository<ExamArchive, Long> {

    Optional<ExamArchive> findByAssignmentId(Long assignmentId);
}
