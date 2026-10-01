package codeprism.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import codeprism.backend.entity.CodeRelationship;

@Repository
public interface CodeRelationshipRepository extends JpaRepository<CodeRelationship, UUID> {

    List<CodeRelationship> findByRepositoryIdAndSourceSymbol(UUID repositoryId, String sourceSymbol);

    List<CodeRelationship> findByRepositoryIdAndTargetSymbol(UUID repositoryId, String targetSymbol);

    List<CodeRelationship> findByRepositoryIdAndFilePath(UUID repositoryId, String filePath);

    @Query("""
        SELECT r FROM CodeRelationship r
        WHERE r.repositoryId = :repoId
          AND (r.sourceSymbol IN :symbols OR r.targetSymbol IN :symbols)
    """)
    List<CodeRelationship> findConnectedRelationships(
            @Param("repoId") UUID repoId,
            @Param("symbols") Collection<String> symbols);

    @Modifying
    @Query("DELETE FROM CodeRelationship r WHERE r.repositoryId = :repoId")
    void deleteByRepositoryId(@Param("repoId") UUID repoId);
}
