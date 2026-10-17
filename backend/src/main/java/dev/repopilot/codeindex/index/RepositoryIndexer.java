package dev.repopilot.codeindex.index;

import dev.repopilot.codeindex.model.EdgeResolution;
import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.codeindex.parse.JavaSourceParser;
import dev.repopilot.codeindex.parse.ParseStatus;
import dev.repopilot.codeindex.parse.ParsedFile;
import dev.repopilot.codeindex.parse.ParsedMapping;
import dev.repopilot.codeindex.parse.ParsedSymbol;
import dev.repopilot.codeindex.parse.RawReference;
import dev.repopilot.codeindex.persistence.CodeSymbol;
import dev.repopilot.codeindex.persistence.CodeSymbolRepository;
import dev.repopilot.codeindex.persistence.DependencyEdge;
import dev.repopilot.codeindex.persistence.DependencyEdgeRepository;
import dev.repopilot.codeindex.persistence.JavaMapping;
import dev.repopilot.codeindex.persistence.JavaMappingRepository;
import dev.repopilot.codeindex.persistence.SourceFile;
import dev.repopilot.codeindex.persistence.SourceFileRepository;
import dev.repopilot.codeindex.resolve.EdgeResolver;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.repository.RepositoryRevision;
import dev.repopilot.repository.RepositoryService;
import dev.repopilot.repository.ScanAccounting;
import dev.repopilot.repository.scan.DetectedLanguage;
import dev.repopilot.repository.scan.ScannedFile;
import dev.repopilot.repository.scan.ScanManifest;
import dev.repopilot.repository.scan.SourceFileEnumerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns a repository on disk into queryable index rows.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Enumerate the repository with the sandbox-aware enumerator.</li>
 *   <li>Read and hash every selected file to compute the revision fingerprint. This
 *       is the cheap half of the read: bytes only, no parsing.</li>
 *   <li>Create the revision row, then read, parse and persist each file in short
 *       batches. Parsing happens <em>outside</em> the database transaction; only the
 *       already-built rows are written inside it. Holding a connection for the
 *       duration of a CPU-bound parse is the exact failure ADR 0006 forbids.</li>
 *   <li>Resolve references against the complete symbol index in a second pass.</li>
 * </ol>
 *
 * <p>Files are read twice. That is a deliberate trade: it keeps peak memory at one
 * parse result plus a nullable-safe list of hashes, instead of buffering every file's
 * symbols and references for a repository that may hold 20 000 files. The first pass
 * reads bytes and hashes them, which is I/O-bound and fast on a local filesystem.
 *
 * <h2>Idempotency</h2>
 * The revision identity is the content fingerprint. Re-indexing the same content
 * reuses the existing revision row and replaces its derived rows, so a retried job
 * cannot produce duplicates and a no-op re-index leaves the database as it found it.
 *
 * <h2>Honesty</h2>
 * A file that fails to parse is still recorded with its status and a bounded reason.
 * The outcome reports indexed, unparsed and unsupported counts rather than only the
 * successes.
 */
@Component
public class RepositoryIndexer {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIndexer.class);

    /** Files buffered before a persistence transaction is opened. */
    private static final int PERSIST_BATCH = 50;
    /** Maximum number of parser problems persisted per file. */
    private static final int MAX_PARSE_ERROR_CHARS = 1900;

    private final SourceFileEnumerator enumerator;
    private final JavaSourceParser parser;
    private final RepositoryService repositories;
    private final SourceFileRepository sourceFiles;
    private final CodeSymbolRepository symbols;
    private final DependencyEdgeRepository edges;
    private final JavaMappingRepository mappings;
    private final EdgeResolver edgeResolver;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RepositoryIndexer(SourceFileEnumerator enumerator,
                             JavaSourceParser parser,
                             RepositoryService repositories,
                             SourceFileRepository sourceFiles,
                             CodeSymbolRepository symbols,
                             DependencyEdgeRepository edges,
                             JavaMappingRepository mappings,
                             EdgeResolver edgeResolver,
                             PlatformTransactionManager transactionManager,
                             Clock clock) {
        this.enumerator = enumerator;
        this.parser = parser;
        this.repositories = repositories;
        this.sourceFiles = sourceFiles;
        this.symbols = symbols;
        this.edges = edges;
        this.mappings = mappings;
        this.edgeResolver = edgeResolver;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Runs the full indexing pipeline for a registered repository. */
    public IndexOutcome index(UUID repositoryId, JobContext context) {
        Path root = repositories.resolveRoot(repositoryId);
        checkpoint(context);

        progress(context, 3, "Enumerating", "Listing files under the repository root");
        ScanManifest manifest = enumerator.enumerate(root,
                context == null ? () -> false : context::isCancelled);
        if (manifest.isEmpty()) {
            // No parsable files is a legitimate outcome (an empty or non-Java repository).
            // The repository is marked ready with a count of zero rather than failed. The
            // scan accounting is still reported: "nothing to index" and "nothing was even
            // looked at" are different facts, and the skip list is how they are told apart.
            repositories.completeIndexing(repositoryId, 0, clock.instant());
            return new IndexOutcome(null, null, null, manifest.enumeratedFiles(),
                    0, manifest.totalSkipped(), manifest.totalPrunedDirectories(),
                    manifest.skippedByReasonText(),
                    0, 0, 0, 0, 0, 0, 0, 0, manifest.truncated());
        }

        List<ScannedFile> files = manifest.analysable();

        // What the walker deliberately did not read. Persisted with the revision so the
        // coverage view can state it, and reported in the job result so an operator sees
        // it in the history without having to query coverage.
        ScanAccounting accounting = new ScanAccounting(manifest.enumeratedFiles(),
                manifest.totalSkipped(), manifest.totalPrunedDirectories(),
                manifest.skippedByReasonText(), manifest.prunedByReasonText(),
                manifest.compiledClassesFound());

        // -------- pass one: hash --------
        progress(context, 6, "Reading", files.size() + " file(s) to hash");
        List<String> hashes = new ArrayList<>(files.size());
        List<String> digestInput = new ArrayList<>(files.size());
        for (int i = 0; i < files.size(); i++) {
            checkpoint(context);
            byte[] bytes = readBytes(files.get(i), root);
            String hash = bytes == null ? "unreadable:" + i : sha256(bytes);
            hashes.add(hash);
            digestInput.add(files.get(i).relativePath() + "\u0000" + hash);
            int percent = 6 + (int) Math.round(12.0 * (i + 1) / files.size());
            progress(context, percent, "Hashing", "Hashed " + (i + 1) + " of " + files.size() + " file(s)");
        }

        String fingerprint = fingerprint(digestInput);
        String revisionLabel = "content:" + fingerprint.substring(0, 40);

        // -------- create the revision before any derived row --------
        UUID revisionId = transactions.execute(status -> {
            RepositoryRevision existing = repositories.findRevision(repositoryId, revisionLabel)
                    .orElse(null);
            // The revision identity is the fingerprint of the analysed set, so an
            // unchanged repository legitimately reuses its revision. The scan accounting
            // is written either way: a newly added credential file changes what was
            // excluded without changing the analysed fingerprint.
            RepositoryRevision revision = existing != null ? existing
                    : new RepositoryRevision(repositoryId, revisionLabel, null, null, null,
                            fingerprint, files.size(), clock.instant(), accounting);
            revision.recordScanAccounting(accounting);
            UUID id = repositories.saveRevision(revision).getId();
            // Replace derived rows so a retry is a no-op and cannot duplicate. Deleting
            // source files cascades to symbols, edges and mappings at the database level.
            sourceFiles.deleteByRevisionId(id);
            return id;
        });

        // -------- pass two: parse and persist --------
        progress(context, 20, "Parsing", "Parsing " + files.size() + " file(s)");
        List<FileDraft> batch = new ArrayList<>(PERSIST_BATCH);
        int indexed = 0;
        int parseFailures = 0;
        int recorded = 0;

        for (int i = 0; i < files.size(); i++) {
            checkpoint(context);
            ScannedFile file = files.get(i);
            FileDraft draft = readAndParse(file, root, hashes.get(i));
            batch.add(draft);
            // Every draft here becomes a row, so all four outcomes count as indexed. They are
            // separated because "parsed", "parsed with problems", "failed" and "recorded
            // without an analyzer" are different claims about the same file. Files excluded
            // by the walker never reach this loop and are counted from the manifest instead.
            if (draft.parsed()) {
                indexed++;
            } else if (draft.parseStatus() == ParseStatus.FAILED) {
                indexed++;
                parseFailures++;
            } else if (draft.parseStatus() == ParseStatus.RECORDED) {
                indexed++;
                recorded++;
            }

            if (batch.size() >= PERSIST_BATCH) {
                persistBatch(revisionId, batch);
                batch.clear();
            }

            int percent = 20 + (int) Math.round(45.0 * (i + 1) / files.size());
            progress(context, percent, "Parsing", "Parsed " + (i + 1) + " of " + files.size() + " file(s)");
        }
        if (!batch.isEmpty()) {
            persistBatch(revisionId, batch);
            batch.clear();
        }

        // -------- resolve --------
        checkpoint(context);
        progress(context, 70, "Resolving", "Classifying references against the revision's symbols");
        EdgeResolver.ResolutionSummary resolution = edgeResolver.resolveRevision(revisionId, context);

        long symbolCount = count(() -> symbols.countByRevisionId(revisionId));
        long edgeCount = count(() -> edges.countByRevisionId(revisionId));
        long mappingCount = count(() -> mappings.countByRevisionId(revisionId));

        repositories.completeIndexing(repositoryId, indexed, clock.instant());

        progress(context, 100, "Complete", "Indexed " + indexed + " file(s)");
        log.info("indexed repository {} revision {}: {} files, {} symbols, {} references "
                        + "({} resolved / {} inferred / {} external)",
                repositoryId, revisionLabel, indexed, symbolCount, edgeCount,
                resolution.resolved(), resolution.inferred(), resolution.external());

        return new IndexOutcome(revisionId, revisionLabel, fingerprint, manifest.enumeratedFiles(),
                indexed, manifest.totalSkipped(), manifest.totalPrunedDirectories(),
                manifest.skippedByReasonText(), parseFailures, recorded, symbolCount, edgeCount, mappingCount,
                resolution.resolved(), resolution.inferred(), resolution.external(), manifest.truncated());
    }

    // ------------------------------------------------------------------
    // Reading and parsing
    // ------------------------------------------------------------------

    private FileDraft readAndParse(ScannedFile file, Path root, String contentHash) {
        byte[] bytes = readBytes(file, root);
        if (bytes == null) {
            return FileDraft.unreadable(file, contentHash,
                    "The file could not be read (permission change or deletion during the scan)");
        }
        String source = decode(bytes);
        // Only Java goes to the Java parser. Handing a .properties or .yml file to it
        // produced a Java syntax verdict about a file that is not Java, which surfaced as
        // "parsed with problems" for every application.properties in a repository. The file
        // is still read and recorded -- the configuration analyzer will need it -- and its
        // status says only what happened.
        if (!file.language().parsed()) {
            return FileDraft.recorded(file, contentHash, JavaSourceParser.countLines(source));
        }
        ParsedFile parsed = parser.parse(file.relativePath(), source);
        return FileDraft.from(file, contentHash, parsed);
    }

    private static byte[] readBytes(ScannedFile file, Path root) {
        try {
            return Files.readAllBytes(file.absolutePath());
        } catch (IOException | RuntimeException e) {
            // RuntimeException covers a mid-scan disappearance surfacing as an unchecked
            // filesystem error on some providers.
            log.debug("unable to read {}: {}", file.relativePath(), e.toString());
            return null;
        }
    }

    private static String decode(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        // A leading BOM would otherwise appear as a stray character to the parser.
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private void persistBatch(UUID revisionId, List<FileDraft> batch) {
        transactions.executeWithoutResult(status -> {
            for (FileDraft draft : batch) {
                SourceFile sourceFile = sourceFiles.save(new SourceFile(revisionId, draft.relativePath(),
                        draft.language(), draft.sizeBytes(), draft.contentHash(), draft.lineCount(),
                        draft.parseStatus(), draft.parseError(), draft.packageName()));

                // Parents are emitted before their members, so the qualified-name map is
                // complete by the time a child is written.
                Map<String, UUID> idByKey = new HashMap<>();
                Map<String, UUID> idByQualifiedName = new HashMap<>();

                for (ParsedSymbol symbol : draft.symbols()) {
                    UUID parentId = symbol.parentQualifiedName() == null
                            ? null : idByQualifiedName.get(symbol.parentQualifiedName());
                    CodeSymbol entity = symbols.save(new CodeSymbol(sourceFile.getId(), revisionId, parentId,
                            symbol.kind(), symbol.simpleName(), symbol.qualifiedName(), symbol.signature(),
                            symbol.declaredType(), symbol.visibility(), symbol.isStatic(), symbol.isAbstract(),
                            symbol.startLine(), symbol.startColumn(), symbol.endLine(), symbol.endColumn(),
                            symbol.annotationNamesJoined(), symbol.javadocSummary(), symbol.complexity()));
                    idByKey.put(symbol.key(), entity.getId());
                    if (symbol.qualifiedName() != null) {
                        idByQualifiedName.putIfAbsent(symbol.qualifiedName(), entity.getId());
                    }
                }

                for (ParsedMapping mapping : draft.mappings()) {
                    mappings.save(new JavaMapping(revisionId, sourceFile.getId(),
                            idByKey.get(mapping.symbolKey()), toMappingType(mapping.mappingType()),
                            mapping.httpMethod(), mapping.path(), mapping.topic(),
                            mapping.consumerGroup(), mapping.detail(), mapping.startLine()));
                }

                for (RawReference reference : draft.references()) {
                    edges.save(new DependencyEdge(revisionId,
                            reference.sourceSymbolKey() == null ? null : idByKey.get(reference.sourceSymbolKey()),
                            null, sourceFile.getId(), reference.edgeType(),
                            reference.targetName(), EdgeResolution.UNRESOLVED,
                            reference.sourceLine(), reference.detail()));
                }
            }
        });
    }

    private static MappingType toMappingType(ParsedMapping.MappingTypeValue value) {
        return MappingType.valueOf(value.name());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the Java platform; absence is unrecoverable.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
    }

    /** Aggregate fingerprint over the sorted per-file digests. */
    static String fingerprint(List<String> digestInput) {
        List<String> sorted = new ArrayList<>(digestInput);
        Collections.sort(sorted);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", e);
        }
        for (String value : sorted) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void checkpoint(JobContext context) {
        if (context != null) {
            context.checkpoint();
        }
    }

    private static void progress(JobContext context, int percent, String stage, String detail) {
        if (context != null) {
            context.progress(percent, stage, detail);
        }
    }

    private interface CountSupplier {
        long get();
    }

    private long count(CountSupplier supplier) {
        Long value = transactions.execute(status -> supplier.get());
        return value == null ? 0L : value;
    }

    /**
     * An intermediate, non-persistent parse result: everything needed to write one
     * file's rows, held only until the batch is flushed.
     */
    private record FileDraft(
            String relativePath,
            DetectedLanguage language,
            long sizeBytes,
            String contentHash,
            Integer lineCount,
            ParseStatus parseStatus,
            String parseError,
            String packageName,
            List<ParsedSymbol> symbols,
            List<RawReference> references,
            List<ParsedMapping> mappings,
            boolean parsed
    ) {

        static FileDraft from(ScannedFile file, String hash, ParsedFile parsed) {
            return new FileDraft(file.relativePath(), file.language(), file.sizeBytes(), hash,
                    parsed.lineCount(), parsed.status(), joinProblems(parsed.problems()),
                    parsed.packageName(), parsed.symbols(), parsed.references(), parsed.mappings(),
                    parsed.parsedSuccessfully());
        }

        static FileDraft unreadable(ScannedFile file, String hash, String reason) {
            return new FileDraft(file.relativePath(), file.language(), file.sizeBytes(), hash,
                    null, ParseStatus.FAILED, reason, "",
                    List.of(), List.of(), List.of(), false);
        }

        /**
         * A file that was read and recorded but has no analyzer for its language yet.
         *
         * <p>No symbols, no references and no mappings are derived from it, and the status
         * says so, so nothing downstream can mistake silence for a clean analysis.
         */
        static FileDraft recorded(ScannedFile file, String hash, int lineCount) {
            return new FileDraft(file.relativePath(), file.language(), file.sizeBytes(), hash,
                    lineCount, ParseStatus.RECORDED, null, "",
                    List.of(), List.of(), List.of(), false);
        }

        static String joinProblems(List<String> problems) {
            if (problems == null || problems.isEmpty()) {
                return null;
            }
            String joined = String.join("; ", problems);
            return joined.length() <= MAX_PARSE_ERROR_CHARS
                    ? joined : joined.substring(0, MAX_PARSE_ERROR_CHARS) + "…";
        }
    }
}
