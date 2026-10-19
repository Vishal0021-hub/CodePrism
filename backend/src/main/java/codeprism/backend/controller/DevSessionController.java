package codeprism.backend.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import codeprism.backend.entity.ChatSession;
import codeprism.backend.entity.IndexStatus;
import codeprism.backend.entity.Repository;
import codeprism.backend.entity.User;
import codeprism.backend.repository.ChatSessionRepository;
import codeprism.backend.repository.RepositoryRepository;
import codeprism.backend.repository.UserRepository;
import codeprism.backend.security.AppUserPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * Development & local verification endpoint for local reproducibility.
 * Only activated when app.security.allow-test-login=true (disabled by default in production).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.security.allow-test-login", havingValue = "true")
public class DevSessionController {

    private final UserRepository userRepository;
    private final RepositoryRepository repositoryRepository;
    private final ChatSessionRepository chatSessionRepository;

    @PostMapping("/dev-session")
    public ResponseEntity<Map<String, Object>> createDevSession(HttpServletRequest request) {
        User user = userRepository.findByGithubId(999999L)
                .orElseGet(() -> userRepository.save(User.builder()
                        .githubId(999999L)
                        .githubUsername("dev-local")
                        .displayName("Dev Local User")
                        .avatarUrl("https://avatars.githubusercontent.com/u/999999")
                        .accessToken("dev-local-token")
                        .build()));

        Repository repo = repositoryRepository.findByUserIdAndGithubRepoId(user.getId(), 888888L)
                .orElseGet(() -> repositoryRepository.save(Repository.builder()
                        .userId(user.getId())
                        .githubRepoId(888888L)
                        .owner("codeprism")
                        .name("local-demo")
                        .fullName("codeprism/local-demo")
                        .defaultBranch("main")
                        .isPrivate(false)
                        .indexStatus(IndexStatus.READY)
                        .indexedAt(Instant.now())
                        .build()));

        List<ChatSession> sessions = chatSessionRepository.findByUserIdAndRepositoryIdOrderByCreatedAtDesc(user.getId(), repo.getId());
        ChatSession session = sessions.isEmpty()
                ? chatSessionRepository.save(ChatSession.builder()
                        .userId(user.getId())
                        .repositoryId(repo.getId())
                        .title("Local Verification Chat")
                        .build())
                : sessions.get(0);

        AppUserPrincipal principal = new AppUserPrincipal(user, Map.of(
                "id", user.getGithubId(),
                "login", user.getGithubUsername(),
                "name", user.getDisplayName()
        ));

        Authentication auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );

        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(auth);
        SecurityContextHolder.setContext(securityContext);

        HttpSession httpSession = request.getSession(true);
        httpSession.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, securityContext);

        return ResponseEntity.ok(Map.of(
                "status", "authenticated",
                "userId", user.getId().toString(),
                "repositoryId", repo.getId().toString(),
                "sessionId", session.getId().toString(),
                "chatStreamUrl", "/api/chat/sessions/" + session.getId() + "/messages"
        ));
    }
}
