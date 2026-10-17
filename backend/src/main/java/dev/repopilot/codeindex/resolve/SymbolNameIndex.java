package dev.repopilot.codeindex.resolve;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An in-memory name index over a revision's symbols, built once per resolution pass.
 *
 * <p>Only names are indexed, never source. Two lookups are provided because two
 * questions are asked:
 * <ul>
 *   <li>{@link #typeByQualifiedName} answers "is there a declaration with exactly this
 *       name?" — a qualified match is treated as {@code RESOLVED}.</li>
 *   <li>{@link #typesBySimpleName} and {@link #callablesByName} answer "which
 *       declarations share this simple name?" — a unique match is {@code INFERRED},
 *       because a simple name cannot prove which declaration was intended without a
 *       classpath.</li>
 * </ul>
 *
 * <p>Ambiguity is preserved rather than resolved arbitrarily: several matches are
 * returned so the caller records an unconfirmed edge instead of guessing.
 */
public final class SymbolNameIndex {

    private final Map<String, CodeSymbolLookup> typeByQualifiedName = new HashMap<>();
    private final Map<String, List<CodeSymbolLookup>> typesBySimpleName = new HashMap<>();
    private final Map<String, List<CodeSymbolLookup>> callablesByName = new HashMap<>();

    public static SymbolNameIndex build(List<CodeSymbolLookup> symbols) {
        SymbolNameIndex index = new SymbolNameIndex();
        for (CodeSymbolLookup symbol : symbols) {
            if (symbol.isType()) {
                if (symbol.qualifiedName() != null) {
                    // First writer wins; duplicate qualified names across files are a real
                    // (if unusual) situation and cannot both be the target.
                    index.typeByQualifiedName.putIfAbsent(symbol.qualifiedName(), symbol);
                }
                index.typesBySimpleName
                        .computeIfAbsent(symbol.name(), key -> new ArrayList<>())
                        .add(symbol);
            } else if (symbol.isCallable()) {
                index.callablesByName
                        .computeIfAbsent(symbol.name(), key -> new ArrayList<>())
                        .add(symbol);
            }
        }
        return index;
    }

    public CodeSymbolLookup typeByQualifiedName(String qualifiedName) {
        return qualifiedName == null ? null : typeByQualifiedName.get(qualifiedName);
    }

    public List<CodeSymbolLookup> typesBySimpleName(String simpleName) {
        return typesBySimpleName.getOrDefault(simpleName, List.of());
    }

    public List<CodeSymbolLookup> callablesByName(String name) {
        return callablesByName.getOrDefault(name, List.of());
    }

    public int typeCount() {
        return typeByQualifiedName.size();
    }
}
