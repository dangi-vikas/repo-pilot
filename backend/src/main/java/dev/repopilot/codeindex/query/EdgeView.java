package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.persistence.DependencyEdge;

import java.util.UUID;

/** A reference as returned by the code explorer, carrying its resolution explicitly. */
public record EdgeView(
        UUID id,
        UUID sourceSymbolId,
        UUID targetSymbolId,
        String edgeType,
        String targetName,
        String resolution,
        Integer sourceLine,
        String detail
) {

    public static EdgeView from(DependencyEdge edge) {
        return new EdgeView(
                edge.getId(),
                edge.getSourceSymbolId(),
                edge.getTargetSymbolId(),
                edge.getEdgeType().name(),
                edge.getTargetName(),
                edge.getResolution().name(),
                edge.getSourceLine(),
                edge.getDetail());
    }
}
