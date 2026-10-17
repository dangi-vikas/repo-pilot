package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.model.SymbolVisibility;
import dev.repopilot.codeindex.persistence.CodeSymbol;

import java.util.UUID;

/** A declaration as returned by the code explorer. */
public record SymbolView(
        UUID id,
        UUID sourceFileId,
        UUID parentSymbolId,
        SymbolKind kind,
        String name,
        String qualifiedName,
        String signature,
        String declaredType,
        SymbolVisibility visibility,
        boolean isStatic,
        boolean isAbstract,
        Integer startLine,
        Integer endLine,
        String annotationNames,
        String javadocSummary,
        Integer complexity
) {

    public static SymbolView from(CodeSymbol symbol) {
        return new SymbolView(
                symbol.getId(),
                symbol.getSourceFileId(),
                symbol.getParentSymbolId(),
                symbol.getKind(),
                symbol.getName(),
                symbol.getQualifiedName(),
                symbol.getSignature(),
                symbol.getDeclaredType(),
                symbol.getVisibility(),
                symbol.isStaticModifier(),
                symbol.isAbstractModifier(),
                symbol.getStartLine(),
                symbol.getEndLine(),
                symbol.getAnnotationNames(),
                symbol.getJavadocSummary(),
                symbol.getComplexity());
    }
}
