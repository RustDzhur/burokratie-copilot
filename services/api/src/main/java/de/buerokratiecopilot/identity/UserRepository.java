package de.buerokratiecopilot.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import de.buerokratiecopilot.identity.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {

    @Query("select u from User u where lower(cast(u.email as string)) = lower(:email) and u.deletedAt is null")
    Optional<User> findActiveByEmail(@Param("email") String email);

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    List<User> findByStatus(User.Status status);
}
