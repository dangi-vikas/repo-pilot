package dev.repopilot.workspace;

import dev.repopilot.shared.error.ErrorCode;
import dev.repopilot.shared.error.RepoPilotException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Workspace lifecycle.
 *
 * <p>Transactions here are short and contain no I/O beyond the database. Long
 * operations (repository scans, model calls, profiling) are never executed inside
 * these methods; they are handed to the job system. See
 * docs/adr/0006-transaction-boundaries.md.
 */
@Service
public class WorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceService.class);

    /** Slug of the workspace created on first start so the UI always has a target. */
    public static final String DEFAULT_SLUG = "default";
    private static final int MAX_SLUG_LENGTH = 190;

    private final WorkspaceRepository workspaces;

    public WorkspaceService(WorkspaceRepository workspaces) {
        this.workspaces = workspaces;
    }

    @Transactional(readOnly = true)
    public List<WorkspaceSummary> listSummaries() {
        return workspaces.findAllSummaries();
    }

    @Transactional(readOnly = true)
    public WorkspaceSummary require(UUID id) {
        return workspaces.findSummaryById(id)
                .orElseThrow(() -> RepoPilotException.notFound("Workspace", id));
    }

    @Transactional(readOnly = true)
    public Optional<Workspace> find(UUID id) {
        return workspaces.findById(id);
    }

    @Transactional
    public WorkspaceSummary create(String name, String description) {
        String trimmedName = requireText(name, "name", 200);
        String slug = uniqueSlug(trimmedName);

        Workspace workspace = new Workspace(trimmedName, slug, normalizeDescription(description));
        try {
            workspaces.saveAndFlush(workspace);
        } catch (DataIntegrityViolationException e) {
            // Two concurrent creates can compute the same slug. Surfacing this as a
            // conflict is honest and actionable; it must not be silently retried with a
            // different slug, which would hide the race.
            throw RepoPilotException.conflict(
                    "A workspace using that name already exists. Choose a different name.");
        }
        log.info("created workspace {} ({})", workspace.getSlug(), workspace.getId());
        return require(workspace.getId());
    }

    @Transactional
    public WorkspaceSummary update(UUID id, String name, String description) {
        Workspace workspace = workspaces.findById(id)
                .orElseThrow(() -> RepoPilotException.notFound("Workspace", id));
        if (name != null && !name.isBlank()) {
            workspace.setName(requireText(name, "name", 200));
        }
        if (description != null) {
            workspace.setDescription(normalizeDescription(description));
        }
        return require(id);
    }

    @Transactional
    public void delete(UUID id) {
        Workspace workspace = workspaces.findById(id)
                .orElseThrow(() -> RepoPilotException.notFound("Workspace", id));
        // Repository rows cascade at the database level. Derived artefacts on disk
        // are pruned by the retention task, not here: filesystem work must not run
        // inside a database transaction.
        workspaces.delete(workspace);
        log.info("deleted workspace {} and all dependent records", id);
    }

    /**
     * Ensures at least one workspace exists. Called on startup.
     *
     * <p>Idempotent by slug. Runs in its own transaction so a failure cannot roll
     * back anything else that happens during startup.
     */
    @Transactional
    public void ensureDefaultWorkspace() {
        if (workspaces.existsBySlug(DEFAULT_SLUG) || workspaces.count() > 0) {
            return;
        }
        workspaces.saveAndFlush(new Workspace(
                "Default workspace",
                DEFAULT_SLUG,
                "Created automatically so RepoPilot is usable on first start."));
        log.info("created the default workspace");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private String uniqueSlug(String name) {
        String base = slugify(name);
        if (!workspaces.existsBySlug(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1000; suffix++) {
            String candidate = truncate(base, MAX_SLUG_LENGTH - 5) + "-" + suffix;
            if (!workspaces.existsBySlug(candidate)) {
                return candidate;
            }
        }
        throw RepoPilotException.conflict("Unable to derive a unique workspace slug from '" + name + "'");
    }

    /**
     * Produces an ASCII, lowercase, hyphenated slug. Non-ASCII is transliterated
     * where possible and dropped otherwise, so slugs stay safe in URLs and logs.
     */
    static String slugify(String name) {
        String normalized = Normalizer.normalize(name, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (normalized.isEmpty()) {
            normalized = "workspace";
        }
        return truncate(normalized, MAX_SLUG_LENGTH);
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String requireText(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED, field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    field + " must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String trimmed = description.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > 2000) {
            throw new RepoPilotException(ErrorCode.VALIDATION_FAILED,
                    "description must be at most 2000 characters");
        }
        return trimmed;
    }
}
