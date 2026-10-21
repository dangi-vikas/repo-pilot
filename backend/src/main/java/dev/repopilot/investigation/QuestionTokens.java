package dev.repopilot.investigation;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a question into the terms retrieval searches for.
 *
 * <p>Deliberately crude: lowercase words, identifier parts and file names, with the words
 * that appear in every question removed. There is no stemming, no synonym list and no
 * embedding, because the retrieval has to be explainable — a reader can ask "why was this
 * evidence here" and get the answer "because the question said {@code OrderService}". A
 * semantic retriever would answer "because the vectors were close", which is not a reason
 * this product is willing to put next to a citation.
 *
 * <p>Camel case is split so a question about {@code OrderService} matches the identifier
 * {@code OrderService}, and a question about "order service" matches it too.
 */
public final class QuestionTokens {

    /** Terms that carry no discriminating power in a code question. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "what", "why", "how", "does", "did", "this", "that",
            "these", "those", "from", "into", "about", "should", "could", "would", "can", "cannot",
            "not", "but", "all", "any", "are", "was", "were", "has", "have", "had", "its", "it's",
            "you", "your", "when", "where", "which", "who", "whom", "there", "their", "then",
            "than", "also", "just", "only", "more", "most", "some", "such", "very", "will",
            "may", "might", "must", "our", "out", "over", "under", "again", "further", "once",
            "here", "both", "each", "few", "other", "own", "same", "too", "now", "use", "used",
            "using", "get", "gets", "make", "makes", "know", "tell", "explain", "find", "show",
            "look", "like", "want", "need", "please", "give", "list", "describe",
            // Java/module noise: matching these would return the whole repository.
            "java", "class", "classes", "method", "methods", "code", "repository", "repo",
            "file", "files", "project", "package", "line", "lines", "source", "thing", "things",
            "issue", "issues", "problem", "problems", "work", "works", "happen", "happens");

    private static final int MAX_TOKENS = 8;
    private static final int MIN_LENGTH = 3;

    private QuestionTokens() {
    }

    /**
     * @return up to eight distinct lowercase terms, in the order they first appear
     */
    public static List<String> from(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        // Path-like fragments first: "src/main/java/Foo.java" is one useful term, and its
        // basename "Foo" is another, but neither survives splitting on punctuation.
        java.util.regex.Matcher paths = java.util.regex.Pattern
                .compile("[\\w./-]+\\.(java|kt|groovy)")
                .matcher(question);
        while (paths.find() && tokens.size() < MAX_TOKENS) {
            String path = paths.group().toLowerCase(Locale.ROOT);
            add(tokens, path);
            int slash = path.lastIndexOf('/');
            String basename = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = basename.lastIndexOf('.');
            add(tokens, dot > 0 ? basename.substring(0, dot) : basename);
        }

        for (String raw : question.split("[^\\p{L}\\p{N}_]+")) {
            if (raw.isEmpty()) {
                continue;
            }
            // Split camelCase and PascalCase before lowercasing.
            for (String part : raw.split("(?<=[a-z0-9])(?=[A-Z])")) {
                add(tokens, part);
                if (tokens.size() >= MAX_TOKENS) {
                    return List.copyOf(tokens);
                }
            }
        }
        return List.copyOf(tokens);
    }

    private static void add(Set<String> tokens, String candidate) {
        if (tokens.size() >= MAX_TOKENS) {
            return;
        }
        String token = candidate.toLowerCase(Locale.ROOT).trim();
        if (token.length() < MIN_LENGTH || STOPWORDS.contains(token)) {
            return;
        }
        if (token.chars().allMatch(Character::isDigit)) {
            return;
        }
        tokens.add(token);
    }

    /** Terms that mean "the question is about behaviour rather than structure". */
    public static boolean isRuntimeQuestion(String question) {
        if (question == null) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (String term : RUNTIME_TERMS) {
            if (lower.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> RUNTIME_TERMS = new ArrayList<>(List.of(
            "slow", "latency", "cpu", "memory", "heap", "allocat", "gc", "garbage",
            "throughput", "benchmark", "profile", "jfr", "recording", "thread", "blocked",
            "contention", "dump", "timeout", "performance", "op/s", "ops/us"));
}
