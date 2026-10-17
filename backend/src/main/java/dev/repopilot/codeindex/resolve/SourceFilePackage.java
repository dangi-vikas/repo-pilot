package dev.repopilot.codeindex.resolve;

import java.util.UUID;

/**
 * A minimal projection of a parsed file: its identity and the package it declares.
 *
 * <p>Name resolution needs to know which package a reference was written in, because a
 * simple name is looked up in the same package before any on-demand import. Loading whole
 * {@link dev.repopilot.codeindex.persistence.SourceFile} entities would pull parse statuses
 * and errors into memory for no benefit.
 *
 * @param fileId      persisted source file id, as referenced by
 *                    {@link dev.repopilot.codeindex.persistence.DependencyEdge#getSourceFileId()}
 * @param packageName declared package, or {@code null} for a file with no package declaration
 */
public record SourceFilePackage(UUID fileId, String packageName) {
}
