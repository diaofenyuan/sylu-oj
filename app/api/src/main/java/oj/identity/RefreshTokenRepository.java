package oj.identity;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByAppUserIdAndRevokedAtIsNull(Long appUserId);

    List<RefreshToken> findByFamilyId(String familyId);

    long countByAppUserIdAndRevokedAtIsNullAndConsumedAtIsNull(Long appUserId);
}
