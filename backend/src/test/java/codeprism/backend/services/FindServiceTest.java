package codeprism.backend.services;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mockito;
import static org.mockito.Mockito.when;
import org.springframework.test.util.ReflectionTestUtils;

import tools.jackson.databind.json.JsonMapper;

import codeprism.backend.dto.FindResultDto;
import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.entity.RelationType;
import codeprism.backend.repository.CodeRelationshipRepository;

class FindServiceTest {

    private CodeRelationshipRepository codeRelationshipRepository;
    private JsonMapper jsonMapper;
    private FindService findService;

    @BeforeEach
    void setUp() {
        codeRelationshipRepository = Mockito.mock(CodeRelationshipRepository.class);
        jsonMapper = JsonMapper.builder().build();
        findService = new FindService(codeRelationshipRepository, jsonMapper);
    }

    @Test
    void shouldRankExactMatchesFirst() {
        UUID repoId = UUID.randomUUID();

        CodeRelationship relSubstring = CodeRelationship.builder()
                .repositoryId(repoId)
                .sourceSymbol("UserServiceHelper")
                .targetSymbol("User")
                .relationType(RelationType.CALLS)
                .filePath("src/main/java/service/UserServiceHelper.java")
                .build();

        CodeRelationship relExact = CodeRelationship.builder()
                .repositoryId(repoId)
                .sourceSymbol("UserService")
                .targetSymbol("UserRepository")
                .relationType(RelationType.USES_REPOSITORY)
                .filePath("src/main/java/service/UserService.java")
                .build();

        when(codeRelationshipRepository.findBySymbolContaining(eq(repoId), eq("UserService")))
                .thenReturn(List.of(relSubstring, relExact));

        List<FindResultDto> results = findService.findSymbols(repoId, "UserService");

        assertThat(results).isNotEmpty();
        // Exact match should be first
        assertThat(results.get(0).symbolName()).isEqualTo("UserService");
        assertThat(results.get(0).exactMatch()).isTrue();
    }

    @Test
    void shouldReturnEmptyListForBlankSymbol() {
        UUID repoId = UUID.randomUUID();
        assertThat(findService.findSymbols(repoId, "")).isEmpty();
        assertThat(findService.findSymbols(repoId, "   ")).isEmpty();
        assertThat(findService.findSymbols(repoId, null)).isEmpty();
    }
}
