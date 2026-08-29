package oj.classroom;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TermRepository extends JpaRepository<Term, Long> {

    boolean existsByCode(String code);

    Optional<Term> findByCode(String code);

    List<Term> findByOrderByStartDateDesc();

    List<Term> findByStatusOrderByStartDateDesc(Term.Status status);
}
