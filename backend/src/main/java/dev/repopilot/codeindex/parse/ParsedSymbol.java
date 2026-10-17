package dev.repopilot.codeindex.parse;

import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.model.SymbolVisibility;

import java.util.List;

/**
 * A declaration extracted from source.
 *
 * @param key                stable, file-scoped identity ({@code relativePath#qualifiedName#signature})
 * @param qualifiedName      {@code com.example.OrderService} or {@code com.example.OrderService#process(int)}
 * @param simpleName         {@code OrderService} or {@code process}
 * @param kind               declaration kind
 * @param parentQualifiedName enclosing type for members; null for top-level types
 * @param signature          source-level signature for callables, type text for fields
 * @param declaredType       field/return declared type as written, unqualified
 * @param visibility         effective visibility
 * @param isStatic           static modifier present
 * @param isAbstract         abstract modifier present
 * @param startLine          1-based, inclusive
 * @param endLine            1-based, inclusive
 * @param annotationNames    annotations present, simple names only
 * @param javadocSummary     first sentence of the Javadoc when present, redacted and bounded
 * @param complexity         cyclomatic complexity estimate for callables, null for types and fields
 * @param generated          carries a marker annotation such as {@code @Generated}
 */
public record ParsedSymbol(
        String key,
        String qualifiedName,
        String simpleName,
        SymbolKind kind,
        String parentQualifiedName,
        String signature,
        String declaredType,
        SymbolVisibility visibility,
        boolean isStatic,
        boolean isAbstract,
        int startLine,
        int startColumn,
        int endLine,
        int endColumn,
        List<String> annotationNames,
        String javadocSummary,
        Integer complexity,
        boolean generated,
        String relativePath
) {

    public boolean isType() {
        return kind.isType();
    }

    public boolean isCallable() {
        return kind.isCallable();
    }

    /** The annotation list as a bounded, comma-separated string for storage. */
    public String annotationNamesJoined() {
        if (annotationNames == null || annotationNames.isEmpty()) {
            return null;
        }
        String joined = String.join(",", annotationNames);
        return joined.length() <= 2000 ? joined : joined.substring(0, 1999);
    }
}
