package dev.repopilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.util.List;

/**
 * Root configuration for RepoPilot.
 *
 * <p>Every field is explicitly defaulted so that the application starts with no
 * external configuration, which is required for the local-first first-run
 * experience. Secrets are never bound here from a file that could be committed;
 * they are supplied through environment variables (see .env.example).
 *
 * <p>The configuration deliberately exposes hard limits (file counts, artefact
 * sizes, worker counts, timeouts). Unbounded values are a design smell in an
 * application that ingests untrusted input, so none are offered.
 */
@ConfigurationProperties(prefix = "repopilot")
public record RepoPilotProperties(
        @DefaultValue Security security,
        @DefaultValue Workspace workspace,
        @DefaultValue Jobs jobs,
        @DefaultValue Ai ai,
        @DefaultValue Analysis analysis,
        @DefaultValue Storage storage,
        @DefaultValue Privacy privacy
) {

    /** Authentication and network exposure. */
    public record Security(
            /**
             * Bearer token required for {@code /api/v1/**}. When blank a random
             * token is generated at startup and written to {@code <dataDir>/token.txt}.
             * Never log this value.
             */
            @DefaultValue("") String token,
            /**
             * Binding to a non-loopback address is refused unless this is true.
             * Turning it on without setting an explicit token fails startup.
             */
            @DefaultValue("false") boolean allowRemoteBind,
            /** Explicit CORS origins for the Vite dev server. Empty disables cross-origin access. */
            @DefaultValue("http://localhost:5173") List<String> corsAllowedOrigins,
            /** Requests per minute allowed per remote address for expensive endpoints. */
            @DefaultValue("120") int requestsPerMinutePerClient
    ) {
    }

    /** Repository sandbox. */
    public record Workspace(
            /**
             * Allow-list of directories a repository may be imported from.
             * Empty means "use the default managed workspace directory".
             */
            @DefaultValue List<String> roots,
            /** Directory name created under the user home when no roots are configured. */
            @DefaultValue("repo-pilot-workspace") String defaultRootName,
            /** Maximum number of files considered per repository import. */
            @DefaultValue("20000") int maxFilesPerRepository,
            /** Maximum size of a single file that will be read or parsed. */
            @DefaultValue("2097152") long maxFileSizeBytes,
            /** Maximum total bytes enumerated per repository import. */
            @DefaultValue("524288000") long maxRepositorySizeBytes,
            /** Follow symbolic links during enumeration. Off by default: symlinks are an escape vector. */
            @DefaultValue("false") boolean followSymlinks
    ) {
    }

    /** Bounded background job execution. */
    public record Jobs(
            @DefaultValue("3") int workerThreads,
            @DefaultValue("200") int queueCapacity,
            @DefaultValue("900") long defaultTimeoutSeconds,
            /** Jobs still RUNNING with a heartbeat older than this are considered abandoned after restart. */
            @DefaultValue("120") long staleJobThresholdSeconds,
            /** Delay between the poller passes that recover queued work. */
            @DefaultValue("1500") long dispatchIntervalMillis
    ) {
    }

    /** AI provider configuration. Absence of a provider is a supported state. */
    public record Ai(
            @DefaultValue("true") boolean enabled,
            /** Default provider id: {@code ollama} or {@code openai-compatible}. */
            @DefaultValue("ollama") String provider,
            @DefaultValue("http://localhost:11434") String baseUrl,
            @DefaultValue("qwen2.5-coder:7b") String chatModel,
            @DefaultValue("nomic-embed-text") String embeddingModel,
            @DefaultValue("120") long requestTimeoutSeconds,
            @DefaultValue("2") int maxConcurrentRequests,
            /** Character budget for retrieved evidence injected into a prompt. */
            @DefaultValue("24000") int contextCharBudget,
            /** Maximum completion tokens requested from the provider. */
            @DefaultValue("2048") int maxCompletionTokens,
            @DefaultValue("0.1") double temperature,
            @DefaultValue("false") boolean embeddingsEnabled,
            @DefaultValue OpenAiCompatible openaiCompatible
    ) {
    }

    /** Optional OpenAI-compatible cloud provider. Disabled unless fully configured. */
    public record OpenAiCompatible(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String baseUrl,
            @DefaultValue("") String apiKey,
            @DefaultValue("") String chatModel,
            @DefaultValue("") String embeddingModel
    ) {
    }

    /** Static analysis and runtime analysis limits. */
    public record Analysis(
            /** Enable the PMD source analyzer (runs in-process, no build execution). */
            @DefaultValue("true") boolean pmdEnabled,
            @DefaultValue("true") boolean customChecksEnabled,
            /**
             * SpotBugs requires compiled bytecode. RepoPilot never compiles a
             * repository implicitly, so this analyzer reports NOT_APPLICABLE
             * until bytecode is supplied explicitly.
             */
            @DefaultValue("true") boolean spotbugsEnabled,
            /** Maximum number of findings retained per analysis run before truncation is reported. */
            @DefaultValue("5000") int maxFindingsPerRun,
            /** Maximum number of JFR events parsed per recording. */
            @DefaultValue("2000000") int maxJfrEvents,
            /** Maximum size of an uploaded runtime artefact. */
            @DefaultValue("1073741824") long maxArtifactSizeBytes,
            /** JVM heap cap hint for isolated heap-dump processing (bytes). Informational. */
            @DefaultValue("2147483648") long heapDumpProcessingBudgetBytes
    ) {
    }

    /** Where RepoPilot keeps its durable state. */
    public record Storage(
            /** Data directory. Relative paths resolve against the process working directory. */
            @DefaultValue("./data") String dataDir,
            /** Directory for uploaded runtime artefacts (JFR, dumps, benchmark JSON). */
            @DefaultValue("artifacts") String artifactsDir,
            /** Delete artefacts and runs older than this many days. 0 disables automatic pruning. */
            @DefaultValue("30") int retentionDays,
            /** Delete abandoned temporary extraction directories on startup. */
            @DefaultValue("true") boolean cleanupTempOnStartup
    ) {
        public Path artifactsPath(Path dataDirPath) {
            return dataDirPath.resolve(artifactsDir);
        }
    }

    /** Redaction and logging privacy controls. */
    public record Privacy(
            /** Include source text in log output. Off by default: source is sensitive. */
            @DefaultValue("false") boolean logSourceContent,
            /** Include full prompts in log output. Off by default. */
            @DefaultValue("false") boolean logPrompts,
            /** Redact secrets from analyzer output before persisting. */
            @DefaultValue("true") boolean redactDetectedSecrets
    ) {
    }
}
