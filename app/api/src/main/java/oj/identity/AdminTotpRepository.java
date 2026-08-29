package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminTotpRepository extends JpaRepository<AdminTotp, Long> {

    Optional<AdminTotp> findByAppUserId(Long appUserId);
}
