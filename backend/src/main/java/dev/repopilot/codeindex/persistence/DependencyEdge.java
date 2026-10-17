package dev.repopilot.codeindex.persistence;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A relationship between symbols, or between a symbol and a name that could not be
 * located.
 *
 * <p>An edge never claims more than was observed. {@link #targetName} is the name as
 * written in source and is always present; {@link #targetSymbolId} is populated only
 * when {@link #resolution} is {@link EdgeResolution#RESOLVED} or
 * {@link EdgeResolution#INFERRED}. When the target is external (a JDK or library
 * type) the name is still recorded, with {@link EdgeResolution#EXTERNAL}, so the
 * dependency graph can show the boundary instead of appearing incomplete.
 */
@Entity
@Table(name = "dependency_edge")
public class DependencyEdge extends AbstractCreatedEntity {

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "source_symbol_id", updatable = false)
    private UUID sourceSymbolId;

    /**
     * Set by {@link dev.repopilot.codeindex.resolve.EdgeResolver} after the edge is inserted,
     * so it must stay updatable: the resolution pass is the only writer.
     */
    @Column(name = "target_symbol_id")
    private UUID targetSymbolId;

    @Column(name = "source_file_id", updatable = false)
    private UUID sourceFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "edge_type", nullable = false, length = 48)
    private EdgeType edgeType;

    @Column(name = "target_name", nullable = false, length = 1500)
    private String targetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution", nullable = false, length = 32)
    private EdgeResolution resolution;

    @Column(name = "source_line")
    private Integer sourceLine;

    @Column(name = "detail", length = 2000)
    private String detail;

    protected DependencyEdge() {
        // Required by JPA.
    }

    public DependencyEdge(UUID revisionId, UUID sourceSymbolId, UUID targetSymbolId, UUID sourceFileId,
                          EdgeType edgeType, String targetName, EdgeResolution resolution,
                          Integer sourceLine, String detail) {
        this.revisionId = revisionId;
        this.sourceSymbolId = sourceSymbolId;
        this.targetSymbolId = targetSymbolId;
        this.sourceFileId = sourceFileId;
        this.edgeType = edgeType;
        this.targetName = targetName;
        this.resolution = resolution;
        this.sourceLine = sourceLine;
        this.detail = detail;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getSourceSymbolId() {
        return sourceSymbolId;
    }

    public UUID getTargetSymbolId() {
        return targetSymbolId;
    }

    public void setTargetSymbolId(UUID targetSymbolId) {
        this.targetSymbolId = targetSymbolId;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public EdgeType getEdgeType() {
        return edgeType;
    }

    public String getTargetName() {
        return targetName;
    }

    public EdgeResolution getResolution() {
        return resolution;
    }

    public void setResolution(EdgeResolution resolution) {
        this.resolution = resolution;
    }

    public Integer getSourceLine() {
        return sourceLine;
    }

    public String getDetail() {
        return detail;
    }
}
