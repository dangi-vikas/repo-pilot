package dev.repopilot.codeindex.persistence;

import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.model.SymbolVisibility;
import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A declaration indexed from source: a type, method, constructor, field or enum
 * constant.
 *
 * <p>{@link #parentSymbolId} models the declaration hierarchy as a self-reference so
 * the code explorer can render a type together with its members without a second
 * query per symbol. Foreign keys are raw {@link UUID}s, consistent with the rest of
 * RepoPilot, to keep module boundaries explicit.
 *
 * <p>Line numbers are recorded exactly as the parser observed them. They are the
 * anchor the backend validates AI citations against; if a cited line is outside
 * {@code [startLine, endLine]} the citation is rejected rather than shown.
 */
@Entity
@Table(name = "code_symbol")
public class CodeSymbol extends AbstractCreatedEntity {

    @Column(name = "source_file_id", nullable = false, updatable = false)
    private UUID sourceFileId;

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "parent_symbol_id", updatable = false)
    private UUID parentSymbolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    private SymbolKind kind;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "qualified_name", length = 1500)
    private String qualifiedName;

    @Column(name = "signature", length = 2000)
    private String signature;

    @Column(name = "declared_type", length = 500)
    private String declaredType;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", length = 32)
    private SymbolVisibility visibility;

    @Column(name = "is_static", nullable = false)
    private boolean staticModifier;

    @Column(name = "is_abstract", nullable = false)
    private boolean abstractModifier;

    @Column(name = "start_line")
    private Integer startLine;

    @Column(name = "start_column")
    private Integer startColumn;

    @Column(name = "end_line")
    private Integer endLine;

    @Column(name = "end_column")
    private Integer endColumn;

    @Column(name = "annotation_names", length = 2000)
    private String annotationNames;

    @Column(name = "javadoc_summary", length = 2000)
    private String javadocSummary;

    @Column(name = "complexity")
    private Integer complexity;

    protected CodeSymbol() {
        // Required by JPA.
    }

    public CodeSymbol(UUID sourceFileId, UUID revisionId, UUID parentSymbolId, SymbolKind kind,
                      String name, String qualifiedName, String signature, String declaredType,
                      SymbolVisibility visibility, boolean staticModifier, boolean abstractModifier,
                      Integer startLine, Integer startColumn, Integer endLine, Integer endColumn,
                      String annotationNames, String javadocSummary, Integer complexity) {
        this.sourceFileId = sourceFileId;
        this.revisionId = revisionId;
        this.parentSymbolId = parentSymbolId;
        this.kind = kind;
        this.name = name;
        this.qualifiedName = qualifiedName;
        this.signature = signature;
        this.declaredType = declaredType;
        this.visibility = visibility;
        this.staticModifier = staticModifier;
        this.abstractModifier = abstractModifier;
        this.startLine = startLine;
        this.startColumn = startColumn;
        this.endLine = endLine;
        this.endColumn = endColumn;
        this.annotationNames = annotationNames;
        this.javadocSummary = javadocSummary;
        this.complexity = complexity;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getParentSymbolId() {
        return parentSymbolId;
    }

    public void setParentSymbolId(UUID parentSymbolId) {
        this.parentSymbolId = parentSymbolId;
    }

    public SymbolKind getKind() {
        return kind;
    }

    public String getName() {
        return name;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public String getSignature() {
        return signature;
    }

    public String getDeclaredType() {
        return declaredType;
    }

    public SymbolVisibility getVisibility() {
        return visibility;
    }

    public boolean isStaticModifier() {
        return staticModifier;
    }

    public boolean isAbstractModifier() {
        return abstractModifier;
    }

    public Integer getStartLine() {
        return startLine;
    }

    public Integer getStartColumn() {
        return startColumn;
    }

    public Integer getEndLine() {
        return endLine;
    }

    public Integer getEndColumn() {
        return endColumn;
    }

    public String getAnnotationNames() {
        return annotationNames;
    }

    public String getJavadocSummary() {
        return javadocSummary;
    }

    public Integer getComplexity() {
        return complexity;
    }
}
