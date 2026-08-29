package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ExternalIdentityRepository extends JpaRepository<ExternalIdentity, Long> {

    Optional<ExternalIdentity> findByExternalNo(String externalNo);

    Optional<ExternalIdentity> findByAppUserId(Long appUserId);

    List<ExternalIdentity> findByStatus(ExternalIdentity.Status status);
}
