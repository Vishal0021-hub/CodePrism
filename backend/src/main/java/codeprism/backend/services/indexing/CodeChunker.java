package codeprism.backend.services.indexing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;

import codeprism.backend.services.ai.RagSettings;

@Component
public class CodeChunker {

    public record ChunkResult(List<Document> documents, CompilationUnit compilationUnit) {}

    private final TokenTextSplitter splitter;
    private final CodeFileFilter fileFilter;
    private final JavaParser javaParser;

    public CodeChunker(
            @Value("${app.indexing.chunk-size:800}") int chunkSize,
            CodeFileFilter fileFilter) {
        // Spring AI splits by tokens; ~4 characters per token is a reasonable default for code.
        int chunkTokens = Math.max(50, chunkSize / 4);

        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkTokens)
                .build();
        this.fileFilter = fileFilter;
        this.javaParser = new JavaParser();
    }

    public List<Document> chunkFile(String repoId, String filePath, String content) {
        return chunkFileWithAst(repoId, filePath, content).documents();
    }

    public ChunkResult chunkFileWithAst(String repoId, String filePath, String content) {
        if (content == null || content.isBlank()) {
            return new ChunkResult(List.of(), null);
        }

        String language = fileFilter.detectLanguage(filePath);
        boolean isJava = "java".equalsIgnoreCase(language) || (filePath != null && filePath.endsWith(".java"));

        if (!isJava) {
            return new ChunkResult(chunkFallback(repoId, filePath, content, language), null);
        }

        try {
            ParseResult<CompilationUnit> parseResult = javaParser.parse(content);
            if (!parseResult.isSuccessful() || parseResult.getResult().isEmpty()) {
                return new ChunkResult(chunkFallback(repoId, filePath, content, language), null);
            }

            CompilationUnit cu = parseResult.getResult().get();
            if (cu.getTypes().isEmpty()) {
                return new ChunkResult(chunkFallback(repoId, filePath, content, language), cu);
            }

            List<Document> javaChunks = chunkJavaCompilationUnit(repoId, filePath, cu);
            if (javaChunks.isEmpty()) {
                return new ChunkResult(chunkFallback(repoId, filePath, content, language), cu);
            }

            List<Document> indexedDocs = IntStream.range(0, javaChunks.size())
                    .mapToObj(i -> withChunkIndex(javaChunks.get(i), i))
                    .toList();

            return new ChunkResult(indexedDocs, cu);
        } catch (Exception ex) {
            return new ChunkResult(chunkFallback(repoId, filePath, content, language), null);
        }
    }

    private List<Document> chunkJavaCompilationUnit(String repoId, String filePath, CompilationUnit cu) {
        List<Document> chunks = new ArrayList<>();
        String fileHeader = "// File: " + filePath + "\n";

        String pkgHeader = cu.getPackageDeclaration()
                .map(p -> p.toString().trim() + "\n\n")
                .orElse("");

        for (TypeDeclaration<?> type : cu.getTypes()) {
            // 1. Class-level fields + signature chunk
            Document classChunk = buildClassLevelChunk(repoId, filePath, cu, type, fileHeader, pkgHeader);
            if (classChunk != null) {
                chunks.addAll(splitIfExceedsLimit(classChunk, fileHeader));
            }

            // 2. Constructors
            for (ConstructorDeclaration constructor : type.getConstructors()) {
                Document ctorChunk = buildMethodOrConstructorChunk(
                        repoId, filePath, type.getNameAsString(), constructor.getNameAsString(),
                        fileHeader + constructor.toString(),
                        constructor.getRange().map(r -> r.begin.line).orElse(null),
                        constructor.getRange().map(r -> r.end.line).orElse(null));
                chunks.addAll(splitIfExceedsLimit(ctorChunk, fileHeader));
            }

            // 3. Methods (one chunk per method)
            for (MethodDeclaration method : type.getMethods()) {
                Document methodChunk = buildMethodOrConstructorChunk(
                        repoId, filePath, type.getNameAsString(), method.getNameAsString(),
                        fileHeader + method.toString(),
                        method.getRange().map(r -> r.begin.line).orElse(null),
                        method.getRange().map(r -> r.end.line).orElse(null));
                chunks.addAll(splitIfExceedsLimit(methodChunk, fileHeader));
            }
        }

        return chunks;
    }

    private Document buildClassLevelChunk(
            String repoId,
            String filePath,
            CompilationUnit cu,
            TypeDeclaration<?> type,
            String fileHeader,
            String pkgHeader) {

        StringBuilder sb = new StringBuilder();
        sb.append(fileHeader);
        sb.append(pkgHeader);

        for (var imp : cu.getImports()) {
            sb.append(imp.toString());
        }
        if (!cu.getImports().isEmpty()) {
            sb.append("\n");
        }

        for (var ann : type.getAnnotations()) {
            sb.append(ann.toString()).append("\n");
        }

        sb.append(buildTypeSignature(type)).append("\n");

        for (FieldDeclaration field : type.getFields()) {
            sb.append("    ").append(field.toString().trim()).append("\n");
        }
        sb.append("}\n");

        int startLine = type.getRange().map(r -> r.begin.line).orElse(1);
        int endLine = type.getFields().isEmpty()
                ? startLine
                : type.getFields().getLast().getRange().map(r -> r.end.line).orElse(startLine);

        Map<String, Object> metadata = baseMetadata(repoId, filePath, "java");
        metadata.put("symbol", type.getNameAsString());
        metadata.put("className", type.getNameAsString());
        metadata.put("startLine", startLine);
        metadata.put("endLine", endLine);
        metadata.put("matchType", "semantic");

        return new Document(sb.toString(), metadata);
    }

    private String buildTypeSignature(TypeDeclaration<?> type) {
        StringBuilder sig = new StringBuilder();
        String mods = type.getModifiers().stream().map(Node::toString).collect(Collectors.joining(" "));
        if (!mods.isBlank()) {
            sig.append(mods).append(" ");
        }

        if (type instanceof ClassOrInterfaceDeclaration cid) {
            sig.append(cid.isInterface() ? "interface " : "class ").append(cid.getNameAsString());
            if (!cid.getTypeParameters().isEmpty()) {
                sig.append("<").append(cid.getTypeParameters().stream().map(Node::toString).collect(Collectors.joining(", "))).append(">");
            }
            if (!cid.getExtendedTypes().isEmpty()) {
                sig.append(" extends ").append(cid.getExtendedTypes().stream().map(Node::toString).collect(Collectors.joining(", ")));
            }
            if (!cid.getImplementedTypes().isEmpty()) {
                sig.append(" implements ").append(cid.getImplementedTypes().stream().map(Node::toString).collect(Collectors.joining(", ")));
            }
        } else if (type instanceof RecordDeclaration rd) {
            sig.append("record ").append(rd.getNameAsString()).append("(")
                    .append(rd.getParameters().stream().map(Node::toString).collect(Collectors.joining(", ")))
                    .append(")");
            if (!rd.getImplementedTypes().isEmpty()) {
                sig.append(" implements ").append(rd.getImplementedTypes().stream().map(Node::toString).collect(Collectors.joining(", ")));
            }
        } else if (type instanceof EnumDeclaration ed) {
            sig.append("enum ").append(ed.getNameAsString());
        } else {
            sig.append("class ").append(type.getNameAsString());
        }

        sig.append(" {");
        return sig.toString();
    }

    private Document buildMethodOrConstructorChunk(
            String repoId,
            String filePath,
            String className,
            String methodName,
            String content,
            Integer startLine,
            Integer endLine) {

        Map<String, Object> metadata = baseMetadata(repoId, filePath, "java");
        metadata.put("symbol", className + "." + methodName);
        metadata.put("className", className);
        metadata.put("methodName", methodName);
        if (startLine != null) {
            metadata.put("startLine", startLine);
        }
        if (endLine != null) {
            metadata.put("endLine", endLine);
        }
        metadata.put("matchType", "semantic");

        return new Document(content, metadata);
    }

    private List<Document> splitIfExceedsLimit(Document doc, String fileHeader) {
        List<Document> split = splitter.apply(List.of(doc));
        if (split.size() <= 1) {
            return List.of(doc);
        }

        List<Document> result = new ArrayList<>();
        for (Document sub : split) {
            String text = sub.getText();
            if (!text.startsWith("// File: ")) {
                text = fileHeader + text;
            }
            Map<String, Object> meta = new HashMap<>(doc.getMetadata());
            result.add(new Document(text, meta));
        }
        return result;
    }

    private List<Document> chunkFallback(String repoId, String filePath, String content, String language) {
        String header = "// File: " + filePath + "\n";
        Document source = new Document(header + content, baseMetadata(repoId, filePath, language));
        List<Document> split = splitter.apply(List.of(source));

        return IntStream.range(0, split.size())
                .mapToObj(i -> withChunkIndex(split.get(i), repoId, filePath, language, i))
                .toList();
    }

    private static Map<String, Object> baseMetadata(String repoId, String filePath, String language) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        metadata.put("matchType", "semantic");
        return metadata;
    }

    private static Document withChunkIndex(Document chunk, int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put("chunkIndex", chunkIndex);
        return new Document(chunk.getText(), metadata);
    }

    private static Document withChunkIndex(
            Document chunk,
            String repoId,
            String filePath,
            String language,
            int chunkIndex) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        metadata.put("chunkIndex", chunkIndex);
        metadata.put("matchType", "semantic");
        return new Document(chunk.getText(), metadata);
    }
}