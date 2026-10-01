package codeprism.backend.services.indexing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;

import codeprism.backend.entity.CodeRelationship;
import codeprism.backend.entity.RelationType;
import codeprism.backend.repository.CodeRelationshipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class CodeGraphExtractor {

    private final CodeRelationshipRepository codeRelationshipRepository;

    public CodeGraphExtractor(CodeRelationshipRepository codeRelationshipRepository) {
        this.codeRelationshipRepository = codeRelationshipRepository;
    }

    @Transactional
    public void extractAndSave(UUID repositoryId, String filePath, CompilationUnit cu) {
        if (cu == null) {
            return;
        }

        try {
            List<CodeRelationship> relationships = new ArrayList<>();
            Set<String> deduplicationKeys = new HashSet<>();

            String primaryType = cu.getPrimaryTypeName()
                    .or(() -> cu.getTypes().stream().findFirst().map(TypeDeclaration::getNameAsString))
                    .orElse("File");

            // 1. Imports
            for (ImportDeclaration imp : cu.getImports()) {
                String importedName = imp.getNameAsString();
                addRelationship(relationships, deduplicationKeys, repositoryId, primaryType, importedName, RelationType.IMPORTS, filePath);
            }

            // 2. Extends, Implements, USES_REPOSITORY, and CALLS
            for (TypeDeclaration<?> type : cu.getTypes()) {
                String typeName = type.getNameAsString();

                // Extends / Implements
                if (type instanceof ClassOrInterfaceDeclaration cid) {
                    for (ClassOrInterfaceType ext : cid.getExtendedTypes()) {
                        addRelationship(relationships, deduplicationKeys, repositoryId, typeName, ext.getNameAsString(), RelationType.EXTENDS, filePath);
                    }
                    for (ClassOrInterfaceType impl : cid.getImplementedTypes()) {
                        addRelationship(relationships, deduplicationKeys, repositoryId, typeName, impl.getNameAsString(), RelationType.EXTENDS, filePath);
                    }
                } else if (type instanceof RecordDeclaration rd) {
                    for (ClassOrInterfaceType impl : rd.getImplementedTypes()) {
                        addRelationship(relationships, deduplicationKeys, repositoryId, typeName, impl.getNameAsString(), RelationType.EXTENDS, filePath);
                    }
                }

                // Fields typed as Spring Data Repository
                Map<String, String> fieldTypes = new HashMap<>();
                for (FieldDeclaration field : type.getFields()) {
                    for (VariableDeclarator var : field.getVariables()) {
                        String fieldName = var.getNameAsString();
                        String fieldType = var.getType().asString();
                        fieldTypes.put(fieldName, fieldType);

                        if (fieldType.endsWith("Repository") || fieldType.contains("Repository")) {
                            addRelationship(relationships, deduplicationKeys, repositoryId, typeName, fieldType, RelationType.USES_REPOSITORY, filePath);
                        }
                    }
                }

                // Method call expressions from methods
                for (MethodDeclaration method : type.getMethods()) {
                    String callerSymbol = typeName + "." + method.getNameAsString();
                    extractCalls(relationships, deduplicationKeys, repositoryId, filePath, typeName, callerSymbol, fieldTypes, method.findAll(MethodCallExpr.class));
                }

                // Method call expressions from constructors
                for (ConstructorDeclaration constructor : type.getConstructors()) {
                    String callerSymbol = typeName + "." + constructor.getNameAsString();
                    extractCalls(relationships, deduplicationKeys, repositoryId, filePath, typeName, callerSymbol, fieldTypes, constructor.findAll(MethodCallExpr.class));
                }
            }

            if (!relationships.isEmpty()) {
                codeRelationshipRepository.saveAll(relationships);
            }
        } catch (Exception ex) {
            log.warn("Failed extracting code graph for {}: {}", filePath, ex.getMessage());
        }
    }

    private void extractCalls(
            List<CodeRelationship> relationships,
            Set<String> deduplicationKeys,
            UUID repoId,
            String filePath,
            String typeName,
            String callerSymbol,
            Map<String, String> fieldTypes,
            List<MethodCallExpr> calls) {

        for (MethodCallExpr call : calls) {
            String methodName = call.getNameAsString();
            String targetSymbol;

            if (call.getScope().isPresent()) {
                Expression scope = call.getScope().get();
                String scopeStr = scope.toString();
                if ("this".equals(scopeStr)) {
                    targetSymbol = typeName + "." + methodName;
                } else if (fieldTypes.containsKey(scopeStr)) {
                    targetSymbol = fieldTypes.get(scopeStr) + "." + methodName;
                } else {
                    targetSymbol = scopeStr + "." + methodName;
                }
            } else {
                targetSymbol = typeName + "." + methodName;
            }

            addRelationship(relationships, deduplicationKeys, repoId, callerSymbol, targetSymbol, RelationType.CALLS, filePath);
        }
    }

    private void addRelationship(
            List<CodeRelationship> relationships,
            Set<String> deduplicationKeys,
            UUID repoId,
            String source,
            String target,
            RelationType type,
            String file) {

        if (source == null || target == null || source.isBlank() || target.isBlank()) {
            return;
        }

        String key = source + "|" + target + "|" + type.name() + "|" + file;
        if (deduplicationKeys.add(key)) {
            relationships.add(CodeRelationship.builder()
                    .repositoryId(repoId)
                    .sourceSymbol(source.trim())
                    .targetSymbol(target.trim())
                    .relationType(type)
                    .filePath(file)
                    .build());
        }
    }
}
