package oj.auth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {

    Optional<AuthToken> findByTokenHash(String tokenHash);

    /** 管理员批量撤销某用户全部未撤销会话。 */
    List<AuthToken> findByAppUserIdAndRevokedAtIsNull(Long appUserId);
}
