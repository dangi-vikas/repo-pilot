package dev.repopilot.repository.scan;

import dev.repopilot.config.RepoPilotProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides which files RepoPilot is willing to look at.
 *
 * <p>Two distinct exclusion categories with different consequences:
 *
 * <ul>
 *   <li><b>Not worth analysing</b> (build output, dependency caches, VCS metadata):
 *       excluded to keep scans fast and deterministic.</li>
 *   <li><b>Must not be read</b> ({@link SkipReason#SECRET_BEARING}): excluded because
 *       reading them would place credentials into RepoPilot's memory, database and
 *       potentially an LLM prompt. These files are identified <em>by name and
 *       location only</em> and are never opened. This is a hard security boundary,
 *       not an optimisation, and it is the reason the check runs before size and
 *       binary inspection.</li>
 * </ul>
 *
 * <p>False negatives are possible: a secret committed in a {@code .java} file or a
 * non-standardly-named credential store will be read like any other source file.
 * RepoPilot therefore also runs a content-level redactor before anything reaches a
 * model or is persisted as finding detail. See docs/security.md.
 */
@Component
public class FileExclusionPolicy {

    /** Directory names skipped entirely, compared case-insensitively. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            // version control
            ".git", ".hg", ".svn", ".bzr",
            // build output
            "target", "build", "dist", "out", "bin", "obj", ".next", ".nuxt", ".output",
            // dependency caches
            "node_modules", "bower_components", "vendor", ".gradle", ".m2", ".npm", ".pnpm-store",
            ".venv", "venv", "__pycache__", ".tox", ".cache", ".parcel-cache",
            // ide and tooling metadata
            ".idea", ".vscode", ".settings", ".mvn", ".metadata", ".fleet",
            // credential and cloud config directories: hard security boundary
            ".ssh", ".aws", ".azure", ".gcloud", ".gnupg", ".kube", ".docker");

    /** Path segments that indicate generated code even when the directory is not excluded. */
    private static final Set<String> GENERATED_PATH_MARKERS = Set.of(
            "generated-sources", "generated-code", "generated", "gen", "auto-generated");

    /** File-name patterns that are never read. Matched case-insensitively on the file name. */
    private static final Set<String> SECRET_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.production", ".env.development", ".env.test",
            ".netrc", ".htpasswd", ".npmrc", ".pypirc", ".pgpass", ".my.cnf",
            "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
            "credentials", "credentials.json", "secring.gpg", "trustdb.gpg",
            "terraform.tfstate", "terraform.tfstate.backup",
            "secrets.yml", "secrets.yaml", "secrets.json",
            "keystore", "keystore.jks", "truststore.jks");

    private static final Set<String> SECRET_EXTENSIONS = Set.of(
            "pem", "key", "p12", "pfx", "jks", "keystore", "truststore", "ppk",
            "asc", "gpg", "kdbx", "ovpn", "der", "crt", "cer", "jceks", "bcfks");

    private static final Pattern SECRET_NAME_PATTERN = Pattern.compile(
            "^(.*[._-])?(secret|secrets|credential|credentials|password|passwd|apikey|api-key|"
                    + "access[._-]?key|private[._-]?key|service[._-]?account)([._-].*)?$",
            Pattern.CASE_INSENSITIVE);

    /** Build artefacts that may be inspected for bytecode presence but never parsed as source. */
    private static final Set<String> BYTECODE_DIRECTORIES = Set.of("target", "build", "out", "bin");

    private final RepoPilotProperties.Workspace workspace;

    public FileExclusionPolicy(RepoPilotProperties properties) {
        this.workspace = properties.workspace();
    }

    /** True when a directory must not be descended into at all. */
    public boolean isExcludedDirectory(String directoryName) {
        return EXCLUDED_DIRECTORIES.contains(directoryName.toLowerCase(Locale.ROOT));
    }

    /**
     * Classifies why a directory is excluded, so the scan report can explain pruning
     * by category rather than lumping every skipped directory together.
     */
    public SkipReason directorySkipReason(String directoryName) {
        String name = directoryName.toLowerCase(Locale.ROOT);
        if (name.equals(".git") || name.equals(".hg") || name.equals(".svn") || name.equals(".bzr")) {
            return SkipReason.VERSION_CONTROL;
        }
        if (name.equals("target") || name.equals("build") || name.equals("dist") || name.equals("out")
                || name.equals("bin") || name.equals("obj") || name.startsWith(".")) {
            // Credential directories and tool metadata are both dot-directories; the
            // security-relevant ones are called out explicitly first.
            if (name.equals(".ssh") || name.equals(".aws") || name.equals(".azure")
                    || name.equals(".gcloud") || name.equals(".gnupg") || name.equals(".kube")
                    || name.equals(".docker")) {
                return SkipReason.SECRET_BEARING;
            }
            return name.equals(".idea") || name.equals(".vscode") || name.equals(".settings")
                    || name.equals(".mvn") || name.equals(".metadata") || name.equals(".fleet")
                    ? SkipReason.IDE_METADATA
                    : SkipReason.BUILD_OUTPUT;
        }
        return SkipReason.DEPENDENCY_CACHE;
    }

    /** True when the directory is a conventional build output location (for bytecode detection). */
    public boolean isBytecodeDirectory(String directoryName) {
        return BYTECODE_DIRECTORIES.contains(directoryName.toLowerCase(Locale.ROOT));
    }

    /** True when the directory name conventionally contains generated code. */
    public boolean isGeneratedDirectory(String directoryName) {
        return GENERATED_PATH_MARKERS.contains(directoryName.toLowerCase(Locale.ROOT));
    }

    /**
     * Names that must never be opened.
     *
     * <p>Deliberately tested against the file <em>name</em> only and before any
     * stat, so a slow or failing filesystem cannot cause RepoPilot to fall through to
     * reading the file.
     */
    public boolean isSecretBearing(String fileName, String relativePath) {
        String name = fileName.toLowerCase(Locale.ROOT);

        if (SECRET_FILE_NAMES.contains(name)) {
            return true;
        }
        // .env.production.local and friends
        if (name.startsWith(".env")) {
            return true;
        }

        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String extension = name.substring(dot + 1);
            if (SECRET_EXTENSIONS.contains(extension)) {
                return true;
            }
        }

        if (SECRET_NAME_PATTERN.matcher(name).matches()) {
            return true;
        }

        // Anything inside a credential directory, even if this policy is consulted directly.
        for (String segment : relativePath.toLowerCase(Locale.ROOT).split("/")) {
            if (segment.equals(".ssh") || segment.equals(".aws") || segment.equals(".azure")
                    || segment.equals(".gcloud") || segment.equals(".gnupg") || segment.equals(".kube")) {
                return true;
            }
        }
        return false;
    }

    /** True when the relative path suggests generated code. */
    public boolean isGeneratedPath(String relativePath) {
        for (String segment : relativePath.toLowerCase(Locale.ROOT).split("/")) {
            if (GENERATED_PATH_MARKERS.contains(segment)) {
                return true;
            }
        }
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.contains(".generated.") || name.endsWith("generated.java") || name.endsWith("_pb.java");
    }

    /** Maximum size of a file that will be read. */
    public long maxFileSizeBytes() {
        return workspace.maxFileSizeBytes();
    }

    /** Maximum number of files recorded per repository import. */
    public int maxFilesPerRepository() {
        return workspace.maxFilesPerRepository();
    }

    /** Maximum total bytes enumerated per import. */
    public long maxRepositorySizeBytes() {
        return workspace.maxRepositorySizeBytes();
    }

    /** Whether symbolic links are followed during enumeration. */
    public boolean followSymlinks() {
        return workspace.followSymlinks();
    }
}
