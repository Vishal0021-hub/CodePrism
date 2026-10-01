package codeprism.backend.services.indexing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;

import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.entity.RelationType;
import codeprism.backend.repository.CodeRelationshipRepository;

@ExtendWith(MockitoExtension.class)
class CodeGraphExtractorTest {

    @Mock
    private CodeRelationshipRepository repository;

    private CodeGraphExtractor extractor;

    @BeforeEach
    void setUp() {
        extractor = new CodeGraphExtractor(repository);
    }

    @Test
    void testExtractAllStructuralRelationships() {
        String code = """
                package codeprism.backend.services;

                import codeprism.backend.repository.UserRepository;
                import codeprism.backend.entity.User;

                public class UserService extends BaseService implements IUserService {
                    private final UserRepository userRepository;

                    public UserService(UserRepository userRepository) {
                        this.userRepository = userRepository;
                    }

                    public User getUser(Long id) {
                        return userRepository.findById(id);
                    }
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();
        UUID repoId = UUID.randomUUID();
        String filePath = "src/main/java/codeprism/backend/services/UserService.java";

        extractor.extractAndSave(repoId, filePath, cu);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CodeRelationship>> captor = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(captor.capture());

        List<CodeRelationship> saved = captor.getValue();
        assertTrue(!saved.isEmpty(), "Should extract relationships");

        // Verify IMPORTS
        boolean hasImportUserRepo = saved.stream().anyMatch(r ->
                r.getRelationType() == RelationType.IMPORTS
                && r.getSourceSymbol().equals("UserService")
                && r.getTargetSymbol().equals("codeprism.backend.repository.UserRepository"));
        assertTrue(hasImportUserRepo, "Should capture UserRepository import");

        // Verify EXTENDS
        boolean hasExtendsBase = saved.stream().anyMatch(r ->
                r.getRelationType() == RelationType.EXTENDS
                && r.getSourceSymbol().equals("UserService")
                && r.getTargetSymbol().equals("BaseService"));
        assertTrue(hasExtendsBase, "Should capture extends BaseService");

        boolean hasImplements = saved.stream().anyMatch(r ->
                r.getRelationType() == RelationType.EXTENDS
                && r.getSourceSymbol().equals("UserService")
                && r.getTargetSymbol().equals("IUserService"));
        assertTrue(hasImplements, "Should capture implements IUserService");

        // Verify USES_REPOSITORY
        boolean hasUsesRepo = saved.stream().anyMatch(r ->
                r.getRelationType() == RelationType.USES_REPOSITORY
                && r.getSourceSymbol().equals("UserService")
                && r.getTargetSymbol().equals("UserRepository"));
        assertTrue(hasUsesRepo, "Should capture USES_REPOSITORY for UserRepository");

        // Verify CALLS
        boolean hasCall = saved.stream().anyMatch(r ->
                r.getRelationType() == RelationType.CALLS
                && r.getSourceSymbol().equals("UserService.getUser")
                && r.getTargetSymbol().equals("UserRepository.findById"));
        assertTrue(hasCall, "Should capture CALLS UserRepository.findById");

        for (CodeRelationship r : saved) {
            assertEquals(repoId, r.getRepositoryId());
            assertEquals(filePath, r.getFilePath());
        }
    }
}
