package dev.repopilot.codeindex.parse;

import dev.repopilot.codeindex.model.EdgeType;

/**
 * A reference observed in source, before it has been resolved against the revision's
 * symbol index.
 *
 * <p>Kept separate from a resolved edge so parsing and resolution can be two distinct
 * steps: parse every file once (discarding the AST to bound memory), then resolve all
 * references against a complete symbol index built from every file. Resolving during
 * parsing would only ever see declarations from files parsed earlier, producing
 * order-dependent results.
 *
 * @param sourceSymbolKey key of the enclosing declaration, or null when the reference
 *                        sits in a static initialiser or a file-level position
 * @param edgeType        what kind of reference this is
 * @param targetName      the name exactly as written: {@code OrderService},
 *                        {@code com.example.OrderService}, {@code OrderService#process}
 * @param scopeText       for method calls, the receiver expression as written, or null
 * @param sourceLine      1-based line of the reference
 * @param detail          optional context, bounded
 */
public record RawReference(
        String sourceSymbolKey,
        EdgeType edgeType,
        String targetName,
        String scopeText,
        int sourceLine,
        String detail
) {
}
