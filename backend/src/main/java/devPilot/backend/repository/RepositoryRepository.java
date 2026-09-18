package devPilot.backend.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import devPilot.backend.entity.Repository;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    Optional<Repository> findByUserIdAndGithubRepoId(UUID userId, Long githubRepoId);
    List<Repository> findByUserIdOrderByFullNameAsc(UUID userId);
    Optional<Repository> findByIdAndUserId(UUID id, UUID userId);
}
