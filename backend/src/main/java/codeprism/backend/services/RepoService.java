package codeprism.backend.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import codeprism.backend.dto.IndexStatusResponse;
import codeprism.backend.dto.RepositoryResponse;
import codeprism.backend.entity.IndexStatus;
import codeprism.backend.entity.Repository;
import codeprism.backend.entity.User;
import codeprism.backend.exceptions.NotFoundException;
import codeprism.backend.repository.RepositoryRepository;
import codeprism.backend.services.github.GithubApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class RepoService {
    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;

    @Transactional
    public List<RepositoryResponse> syncAndListRepos(UUID userId) {
        User user = userService.requiredById(userId);
        String token = userService.decryptAccessToken(user);

        List<Map<String, Object>> remoteRepos;
        try {
            remoteRepos = gitHubApiClient.listUserRepos(token);
        } catch (Exception ex) {
            log.warn("Failed to fetch repos from GitHub for user {}: {}. Falling back to cached repos.",
                    userId, ex.getMessage());
            List<RepositoryResponse> cached = listStored(userId);
            if (!cached.isEmpty()) {
                return cached;
            }
            throw ex;
        }

        List<Repository> saved = new ArrayList<>();

        for (Map<String, Object> remote : remoteRepos) {
            Long githubRepoId = toLong(remote.get("id"));
            if (githubRepoId == null || githubRepoId == 0L) {
                continue;
            }

            Repository repo = repositoryRepository
                    .findByUserIdAndGithubRepoId(userId, githubRepoId)
                    .orElseGet(() -> {
                        Repository r = new Repository();
                        r.setCreatedAt(Instant.now());
                        r.setIndexStatus(IndexStatus.PENDING);
                        return r;
                    });

            String fullName = remote.get("full_name") != null ? String.valueOf(remote.get("full_name")) : "";
            String[] parts = fullName.split("/", 2);

            String owner = parts.length > 0 && !parts[0].isBlank() ? parts[0] : "";
            if (owner.isBlank()) {
                Object ownerObj = remote.get("owner");
                if (ownerObj instanceof Map<?, ?> ownerMap && ownerMap.get("login") != null) {
                    owner = String.valueOf(ownerMap.get("login"));
                } else if (ownerObj != null) {
                    owner = String.valueOf(ownerObj);
                }
            }
            if (owner.isBlank()) {
                owner = "unknown";
            }

            String name = parts.length > 1 && !parts[1].isBlank() ? parts[1] : "";
            if (name.isBlank() && remote.get("name") != null) {
                name = String.valueOf(remote.get("name"));
            }
            if (name.isBlank()) {
                name = fullName;
            }

            repo.setUserId(userId);
            repo.setGithubRepoId(githubRepoId);
            repo.setOwner(owner.length() > 100 ? owner.substring(0, 100) : owner);
            repo.setName(name.length() > 200 ? name.substring(0, 200) : name);
            repo.setFullName(fullName.length() > 300 ? fullName.substring(0, 300) : fullName);
            repo.setPrivate(Boolean.TRUE.equals(remote.get("private")));
            repo.setDefaultBranch(remote.get("default_branch") != null
                    ? String.valueOf(remote.get("default_branch"))
                    : "main");
            repo.setLanguage(remote.get("language") != null ? String.valueOf(remote.get("language")) : null);
            repo.setHtmlUrl(remote.get("html_url") != null ? String.valueOf(remote.get("html_url")) : null);
            repo.setDescription(remote.get("description") != null ? String.valueOf(remote.get("description")) : null);
            repo.setUpdatedAt(Instant.now());
            if (repo.getCreatedAt() == null) {
                repo.setCreatedAt(Instant.now());
            }

            saved.add(repositoryRepository.save(repo));
        }

        return saved.stream()
                .sorted((a, b) -> a.getFullName().compareToIgnoreCase(b.getFullName()))
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<RepositoryResponse> listStored(UUID userId) {
        return repositoryRepository.findByUserIdOrderByFullNameAsc(userId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Repository requireOwned(UUID repoId, UUID userId) {
        return repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    @Transactional(readOnly = true)
    public IndexStatusResponse status(UUID repoId, UUID userId) {
        Repository repo = requireOwned(repoId, userId);
        return new IndexStatusResponse(
                repo.getId(),
                repo.getIndexStatus(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getChunkCount(),
                repo.getIndexedAt(),
                repo.getErrorMessage());
    }

    public RepositoryResponse toResponse(Repository repo) {
        return new RepositoryResponse(
                repo.getId(),
                repo.getGithubRepoId(),
                repo.getOwner(),
                repo.getName(),
                repo.getFullName(),
                repo.isPrivate(),
                repo.getDefaultBranch(),
                repo.getLanguage(),
                repo.getHtmlUrl(),
                repo.getDescription(),
                repo.getIndexStatus(),
                repo.getIndexedAt(),
                repo.getChunkCount(),
                repo.getFilesTotal(),
                repo.getFilesProcessed(),
                repo.getErrorMessage());
    }

    private static Long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}