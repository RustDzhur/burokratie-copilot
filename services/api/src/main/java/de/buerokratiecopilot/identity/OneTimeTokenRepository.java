package de.buerokratiecopilot.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.identity.domain.OneTimeToken;

public interface OneTimeTokenRepository extends JpaRepository<OneTimeToken, UUID> {

    @Query("""
            select t from OneTimeToken t
            where t.tokenHash = :hash and t.purpose = :purpose and t.consumedAt is null and t.expiresAt > :now
            """)
    Optional<OneTimeToken> findUsable(@Param("hash") String hash,
                                      @Param("purpose") OneTimeToken.Purpose purpose,
                                      @Param("now") Instant now);

    @Query("""
            select count(t) from OneTimeToken t
            where t.userId = :userId and t.purpose = :purpose and t.consumedAt is null
            """)
    long countOutstanding(@Param("userId") UUID userId, @Param("purpose") OneTimeToken.Purpose purpose);
}
