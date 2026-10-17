package dev.repopilot.analysis.checks;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LiteralStringValueExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.ThrowStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import dev.repopilot.analysis.AnalysisBatch;
import dev.repopilot.analysis.AnalysisSource;
import dev.repopilot.analysis.SourceAnalyzer;
import dev.repopilot.analysis.StaticFinding;
import dev.repopilot.repository.scan.DetectedLanguage;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The built-in rules, implemented over JavaParser's syntax tree.
 *
 * <h2>No symbol resolution</h2>
 * The analyzer reads one file and never resolves types. That is a deliberate limit shared
 * with the indexer: anything requiring the classpath would either need a compile step
 * RepoPilot does not perform, or would silently guess. Where a name is not enough to know
 * the truth, the rule is written as a named-pattern match with {@code MEDIUM} confidence and
 * a stated limitation rather than as a resolved claim.
 *
 * <h2>Partial parse trees are analysed</h2>
 * A file with syntax problems still yields a tree in most cases, and the rules run over it.
 * Findings are per-node, so one unparseable method does not hide a swallowed exception in
 * the method next to it.
 *
 * <h2>Never throws</h2>
 * Malformed, truncated, or pathological input is ordinary input for an analyzer. Anything
 * unexpected is caught and reported as "no findings from this file" — never as a failed run
 * that would put the other 2 999 files' results in doubt.
 *
 * <h2>Secrets are never copied</h2>
 * {@link CustomRule#HARDCODED_CREDENTIAL} reports the identifier and the line. It never puts
 * the literal it matched into a finding, because a finding is stored, served over the API,
 * and read by a language model — three places a credential must not reach.
 */
@Component
public class CustomCheckAnalyzer implements SourceAnalyzer {

    public static final String ID = "custom-checks";
    public static final String VERSION = "1.0.0";

    /** A literal shorter than this is not treated as a candidate credential. */
    private static final int MIN_CREDENTIAL_LENGTH = 6;

    /** Field and column limits declared on {@code finding}; text is truncated, never rejected. */
    private static final int MAX_SUMMARY = 4000;
    private static final int MAX_TITLE = 500;

    /** Identifiers that read as credentials. Deliberately name-based: see the rule's limitations. */
    private static final Pattern CREDENTIAL_NAME = Pattern.compile(
            "(?i).*(password|passwd|passphrase|pwd|secret|token|apikey|api[_-]?key|credential"
                    + "|private[_-]?key|access[_-]?key|client[_-]?secret|auth[_-]?key).*");

    /** Values that are obviously not a real credential, so a fixture is not reported as a leak. */
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?i)(change[_-]?me|replace[_-]?me|your[ _-].*|my[ _-].*|x{3,}|todo|tbd|none|null"
                    + "|foo|bar|baz|dummy.*|example.*|placeholder.*|sample.*|test|testing)");

    /** A caught parameter named this way is read as a deliberate decision, not an oversight. */
    private static final Pattern DELIBERATE_IGNORE = Pattern.compile("(?i)^(ignored?|expected|suppressed)\\w*$");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Set<DetectedLanguage> languages() {
        return Set.of(DetectedLanguage.JAVA);
    }

    @Override
    public AnalysisBatch analyze(List<AnalysisSource> sources) {
        Map<String, List<StaticFinding>> byPath = new LinkedHashMap<>();
        for (AnalysisSource source : sources) {
            if (!languages().contains(source.language())) {
                continue;
            }
            List<StaticFinding> findings = analyzeOne(source);
            if (!findings.isEmpty()) {
                byPath.put(source.relativePath(), findings);
            }
        }
        // A syntax-tree walk needs no run-level configuration, so this analyzer has nothing to
        // note about the run that its findings do not already say.
        return AnalysisBatch.of(byPath, List.of());
    }

    private List<StaticFinding> analyzeOne(AnalysisSource source) {
        ParseResult<CompilationUnit> result;
        try {
            result = new JavaParser(configuration()).parse(source.content());
        } catch (RuntimeException e) {
            // JavaParser can throw on pathological input; deep nesting causing a stack
            // overflow is the realistic case. Nothing is claimed about such a file.
            return List.of();
        }

        Optional<CompilationUnit> parsed = result.getResult();
        if (parsed.isEmpty()) {
            return List.of();
        }

        List<StaticFinding> findings = new ArrayList<>();
        try {
            CompilationUnit unit = parsed.get();
            checkCatchClauses(unit, source, findings);
            checkMethodCalls(unit, source, findings);
            checkCredentialLiterals(unit, source, findings);
            checkDateFormats(unit, source, findings);
        } catch (RuntimeException e) {
            // A rule that fails on one construct must not discard what the other rules
            // already established about this file.
            return findings;
        }
        return findings;
    }

    /**
     * Parser settings mirror the indexer's, so a file the index parsed is a file the rules
     * can see. Comment attribution is off: no rule reads comments, and enabling it would
     * retain text nothing here uses.
     */
    private static ParserConfiguration configuration() {
        return new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false)
                .setLexicalPreservationEnabled(false)
                .setStoreTokens(true)
                .setCharacterEncoding(StandardCharsets.UTF_8);
    }

    private void checkCatchClauses(CompilationUnit unit, AnalysisSource source, List<StaticFinding> findings) {
        for (CatchClause clause : unit.findAll(CatchClause.class)) {
            String name = clause.getParameter().getNameAsString();
            String type = clause.getParameter().getType().asString();
            boolean deliberate = DELIBERATE_IGNORE.matcher(name).matches();
            boolean emptyBody = clause.getBody().getStatements().isEmpty();

            if (emptyBody && !deliberate) {
                findings.add(finding(CustomRule.EMPTY_CATCH_BLOCK, source.relativePath(), clause,
                        "The catch block for `" + type + " " + name + "` is empty."));
            }
            if (!emptyBody && !deliberate && !reads(clause, name) && !rethrows(clause)) {
                findings.add(finding(CustomRule.SWALLOWED_EXCEPTION, source.relativePath(), clause,
                        "The catch block for `" + type + " " + name + "` never reads the exception and does not "
                                + "rethrow it."));
            }
            if ("Throwable".equals(type)) {
                findings.add(finding(CustomRule.CATCHES_THROWABLE, source.relativePath(), clause,
                        "The catch clause handles `Throwable`, which includes every `Error`."));
            }
        }
    }

    private void checkMethodCalls(CompilationUnit unit, AnalysisSource source, List<StaticFinding> findings) {
        for (MethodCallExpr call : unit.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if ("printStackTrace".equals(name)) {
                findings.add(finding(CustomRule.PRINT_STACK_TRACE, source.relativePath(), call,
                        "`printStackTrace()` writes the trace to standard error instead of the logging pipeline."));
                continue;
            }
            if (isConsoleCall(call)) {
                findings.add(finding(CustomRule.CONSOLE_OUTPUT, source.relativePath(), call,
                        "`" + call.getNameAsString() + "` is called on `System."
                                + consoleStream(call) + "`."));
                continue;
            }
            if ("sleep".equals(name)) {
                Optional<Integer> loopLine = enclosingLoopLine(call);
                loopLine.ifPresent(line -> findings.add(finding(CustomRule.SLEEP_IN_LOOP, source.relativePath(), call,
                        "`sleep(...)` is called inside the loop at line " + line
                                + "; each iteration holds its thread for the whole wait.")));
            }
        }
    }

    private void checkCredentialLiterals(CompilationUnit unit, AnalysisSource source, List<StaticFinding> findings) {
        for (VariableDeclarator declarator : unit.findAll(VariableDeclarator.class)) {
            String name = declarator.getNameAsString();
            if (!CREDENTIAL_NAME.matcher(name).matches()) {
                continue;
            }
            Optional<String> value = declarator.getInitializer().flatMap(CustomCheckAnalyzer::literalValue);
            if (value.isEmpty() || looksLikePlaceholder(value.get())) {
                continue;
            }
            boolean field = declarator.getParentNode().orElse(null) instanceof FieldDeclaration;
            findings.add(finding(CustomRule.HARDCODED_CREDENTIAL, source.relativePath(), declarator,
                    "A string literal is assigned to the " + (field ? "field" : "local variable") + " `" + name
                            + "`. The value is deliberately not recorded in this finding."));
        }

        for (AssignExpr assignment : unit.findAll(AssignExpr.class)) {
            String target = assignedName(assignment);
            if (target == null || !CREDENTIAL_NAME.matcher(target).matches()) {
                continue;
            }
            Optional<String> value = literalValue(assignment.getValue());
            if (value.isEmpty() || looksLikePlaceholder(value.get())) {
                continue;
            }
            findings.add(finding(CustomRule.HARDCODED_CREDENTIAL, source.relativePath(), assignment,
                    "A string literal is assigned to `" + target
                            + "`. The value is deliberately not recorded in this finding."));
        }
    }

    private void checkDateFormats(CompilationUnit unit, AnalysisSource source, List<StaticFinding> findings) {
        for (FieldDeclaration field : unit.findAll(FieldDeclaration.class)) {
            if (!field.getElementType().asString().endsWith("SimpleDateFormat")) {
                continue;
            }
            String names = field.getVariables().stream()
                    .map(VariableDeclarator::getNameAsString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("field");
            findings.add(finding(CustomRule.SHARED_DATE_FORMAT, source.relativePath(), field,
                    "`" + names + "` is a `SimpleDateFormat` held in a field. Two threads formatting at once "
                            + "corrupt the output or throw."));
        }
    }

    /** True when the handler body mentions the caught variable at all. */
    private static boolean reads(CatchClause clause, String name) {
        return clause.getBody().findAll(NameExpr.class).stream()
                .anyMatch(expression -> expression.getNameAsString().equals(name));
    }

    /** True when the handler throws anything, which counts as not swallowing. */
    private static boolean rethrows(CatchClause clause) {
        return !clause.getBody().findAll(ThrowStmt.class).isEmpty();
    }

    /** True for a call on {@code System.out} or {@code System.err}. */
    private static boolean isConsoleCall(MethodCallExpr call) {
        return consoleStream(call) != null;
    }

    private static String consoleStream(MethodCallExpr call) {
        if (!(call.getScope().orElse(null) instanceof FieldAccessExpr field)) {
            return null;
        }
        if (!(field.getScope() instanceof NameExpr owner) || !"System".equals(owner.getNameAsString())) {
            return null;
        }
        String stream = field.getNameAsString();
        return "out".equals(stream) || "err".equals(stream) ? stream : null;
    }

    /**
     * Line of the enclosing loop, if the call is inside one.
     *
     * <p>The walk stops at a method declaration: a call sitting in a method declared inside a
     * loop body — an anonymous class, a local class — is not executed per iteration, so
     * reporting it as a loop body would be wrong.
     *
     * <p>A lambda is deliberately not a boundary. A lambda written inside a loop body is
     * normally run once per iteration, so treating it as a separate scope would hide exactly
     * the case the rule exists for. The cost is that a lambda handed to an asynchronous
     * executor is reported even though the loop itself does not sleep; that is stated in the
     * rule's limitations.
     */
    private static Optional<Integer> enclosingLoopLine(Node node) {
        Node current = node;
        while (true) {
            Optional<Node> parent = current.getParentNode();
            if (parent.isEmpty()) {
                return Optional.empty();
            }
            Node enclosing = parent.get();
            if (enclosing instanceof ForStmt || enclosing instanceof WhileStmt || enclosing instanceof DoStmt) {
                return enclosing.getRange().map(range -> range.begin.line);
            }
            if (enclosing instanceof com.github.javaparser.ast.body.MethodDeclaration) {
                return Optional.empty();
            }
            current = enclosing;
        }
    }

    private static Optional<String> literalValue(com.github.javaparser.ast.expr.Expression expression) {
        if (expression instanceof LiteralStringValueExpr literal) {
            String value = literal.getValue();
            return Optional.ofNullable(value);
        }
        return Optional.empty();
    }

    private static String assignedName(AssignExpr assignment) {
        if (assignment.getTarget() instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (assignment.getTarget() instanceof FieldAccessExpr field) {
            return field.getNameAsString();
        }
        return null;
    }

    /**
     * True for a value that is not a credential: too short, a template expression, or one of
     * the placeholders people write on purpose. Judged on the value in memory only — it is
     * never written anywhere, so a false negative here stays contained.
     */
    private static boolean looksLikePlaceholder(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < MIN_CREDENTIAL_LENGTH) {
            return true;
        }
        if (trimmed.contains("${") || trimmed.contains("%s") || trimmed.contains("<%")) {
            return true;
        }
        return PLACEHOLDER.matcher(trimmed).matches();
    }

    private static StaticFinding finding(CustomRule rule, String path, Node node, String summary) {
        return new StaticFinding(
                ID,
                VERSION,
                rule.id(),
                rule.findingType(),
                truncate(rule.title(), MAX_TITLE),
                truncate(summary, MAX_SUMMARY),
                rule.severity(),
                rule.confidence(),
                rule.category(),
                path,
                startLine(node),
                endLine(node),
                rule.impact(),
                rule.recommendedAction(),
                rule.limitations());
    }

    private static Integer startLine(Node node) {
        return node.getRange().map(range -> range.begin.line).orElse(null);
    }

    private static Integer endLine(Node node) {
        return node.getRange().map(range -> range.end.line).orElse(null);
    }

    private static String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit - 1) + "…";
    }
}
