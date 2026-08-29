package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TotpEnrollmentRepository extends JpaRepository<TotpEnrollment, Long> {

    Optional<TotpEnrollment> findByTokenHash(String tokenHash);
}
