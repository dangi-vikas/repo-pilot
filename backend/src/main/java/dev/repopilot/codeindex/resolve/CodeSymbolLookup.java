package dev.repopilot.codeindex.resolve;

import dev.repopilot.codeindex.model.SymbolKind;

import java.util.UUID;

/**
 * A minimal projection of a persisted symbol, used only for name-based resolution.
 *
 * <p>Resolution needs a symbol's identity, its names and its kind, and nothing else.
 * Loading whole {@link dev.repopilot.codeindex.persistence.CodeSymbol} entities for a
 * whole revision would pull signatures, Javadoc and annotations into memory for no
 * benefit; this projection keeps the resident index proportional to the number of
 * names rather than the size of the code.
 *
 * @param id            persisted symbol id
 * @param name          simple name, as declared
 * @param qualifiedName fully-qualified name, or the file-qualified member name
 * @param kind          declaration kind
 */
public record CodeSymbolLookup(UUID id, String name, String qualifiedName, SymbolKind kind) {

    public boolean isType() {
        return kind.isType();
    }

    public boolean isCallable() {
        return kind.isCallable();
    }
}
