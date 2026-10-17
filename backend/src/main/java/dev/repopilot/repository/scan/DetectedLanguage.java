package dev.repopilot.repository.scan;

import java.util.Locale;
import java.util.Set;

/**
 * Best-effort language classification from file name.
 *
 * <p>{@link #parsed()} distinguishes files RepoPilot can actually reason about from
 * files it only records. A file whose language is not parsed is still enumerated
 * and reported as unsupported, because telling the user "we skipped 431 Kotlin
 * files" is more honest than silently reporting a repository as fully analysed.
 */
public enum DetectedLanguage {

    JAVA(true, true),
    /** Parsed by the configuration analyzer (thread pools, Kafka, datasource settings). */
    PROPERTIES(false, true),
    YAML(false, true),
    /** Recorded but not parsed. */
    KOTLIN(false, false),
    GROOVY(false, false),
    SCALA(false, false),
    XML(false, false),
    SQL(false, false),
    MARKDOWN(false, false),
    JSON(false, false),
    SHELL(false, false),
    OTHER_TEXT(false, false),
    BINARY(false, false),
    UNKNOWN(false, false);

    private final boolean sourceParsed;
    private final boolean configurationParsed;

    DetectedLanguage(boolean sourceParsed, boolean configurationParsed) {
        this.sourceParsed = sourceParsed;
        this.configurationParsed = configurationParsed;
    }

    /** True when a full Java (or source-level) parse is performed. */
    public boolean parsed() {
        return sourceParsed;
    }

    /** True when the file is read by the configuration analyzer. Source text is not persisted. */
    public boolean configurationAnalysed() {
        return configurationParsed;
    }

    /** True when the file must be read to be analysed. */
    public boolean requiresRead() {
        return sourceParsed || configurationParsed;
    }

    private static final Set<String> TEXT_LIKE = Set.of(
            "txt", "csv", "tsv", "log", "gitignore", "gitattributes", "editorconfig",
            "dockerignore", "npmrc", "adoc", "rst", "html", "css", "toml", "ini", "conf", "cfg");

    /** Classifies a repository-relative path. Never throws. */
    public static DetectedLanguage fromFileName(String relativePath) {
        String name = fileName(relativePath).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        String extension = dot < 0 ? "" : name.substring(dot + 1);

        return switch (extension) {
            case "java" -> JAVA;
            case "properties" -> PROPERTIES;
            case "yml", "yaml" -> YAML;
            case "kt", "kts" -> KOTLIN;
            case "groovy", "gradle" -> GROOVY;
            case "scala", "sc" -> SCALA;
            case "xml", "xsd", "wsdl", "pom" -> XML;
            case "sql" -> SQL;
            case "md", "markdown" -> MARKDOWN;
            case "json", "json5" -> JSON;
            case "sh", "bash", "zsh", "fish", "ps1", "cmd", "bat" -> SHELL;
            default -> TEXT_LIKE.contains(extension) ? OTHER_TEXT : UNKNOWN;
        };
    }

    private static String fileName(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? relativePath : relativePath.substring(slash + 1);
    }
}
