package dev.repopilot.codeindex.parse;

import dev.repopilot.repository.scan.DetectedLanguage;

import java.util.List;

/**
 * Everything extracted from a single source file in one parse.
 *
 * <p>The AST itself is discarded before this object is returned. Holding ASTs for a
 * 20 000-file repository would cost orders of magnitude more memory than holding the
 * extracted facts, and RepoPilot never needs the tree again: source text is read from
 * disk on demand for the code viewer.
 *
 * @param relativePath  repository-relative path, {@code /} separated
 * @param language      detected language
 * @param packageName   declared package, or the empty string for the default package
 * @param imports       explicit and on-demand import names, as written
 * @param status        parse outcome
 * @param problems      bounded parser problem descriptions; empty when clean
 * @param symbols       declarations, ordered by source position
 * @param references    unresolved references, to be resolved against the whole revision
 * @param mappings      framework surfaces detected in this file
 * @param lineCount     number of lines in the file
 * @param truncated     true when the reference or symbol cap was hit, so coverage within
 *                      this file is incomplete and must be reported
 * @param primaryTypeName the first top-level type declared, used for display and search
 */
public record ParsedFile(
        String relativePath,
        DetectedLanguage language,
        String packageName,
        List<String> imports,
        ParseStatus status,
        List<String> problems,
        List<ParsedSymbol> symbols,
        List<RawReference> references,
        List<ParsedMapping> mappings,
        int lineCount,
        boolean truncated,
        String primaryTypeName
) {

    public ParsedFile {
        imports = List.copyOf(imports);
        problems = List.copyOf(problems);
        symbols = List.copyOf(symbols);
        references = List.copyOf(references);
        mappings = List.copyOf(mappings);
    }

    /** A file that was recorded but not parsed, with the reason. */
    public static ParsedFile skipped(String relativePath, DetectedLanguage language,
                                    ParseStatus status, String reason, int lineCount) {
        return new ParsedFile(relativePath, language, "", List.of(), status,
                reason == null ? List.of() : List.of(reason),
                List.of(), List.of(), List.of(), lineCount, false, null);
    }

    public boolean parsedSuccessfully() {
        return status == ParseStatus.PARSED || status == ParseStatus.PARSED_WITH_PROBLEMS;
    }

    public long typeCount() {
        return symbols.stream().filter(ParsedSymbol::isType).count();
    }

    public long callableCount() {
        return symbols.stream().filter(ParsedSymbol::isCallable).count();
    }
}
