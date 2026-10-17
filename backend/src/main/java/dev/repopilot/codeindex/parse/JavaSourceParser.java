package dev.repopilot.codeindex.parse;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.repopilot.codeindex.model.EdgeType;
import dev.repopilot.codeindex.model.SymbolKind;
import dev.repopilot.codeindex.model.SymbolVisibility;
import dev.repopilot.repository.scan.DetectedLanguage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts structured facts from a Java source file with JavaParser.
 *
 * <h2>Design decisions</h2>
 * <ol>
 *   <li><b>No symbol solver.</b> Full type resolution needs a classpath built from the
 *       project's dependencies, which RepoPilot does not have and will not obtain by
 *       executing a build. Resolution is therefore name-based and every edge records how
 *       confident RepoPilot is ({@link dev.repopilot.codeindex.model.EdgeResolution}).
 *       Claiming full resolution would be a lie.</li>
 *   <li><b>Explicit recursion, not a visitor.</b> Symbol extraction needs to know the
 *       enclosing declaration. Recursing over type bodies keeps that scope explicit and
 *       avoids the scope-stack bugs that visitor-based extractors are prone to.</li>
 *   <li><b>Partial failure is normal.</b> A file that fails to parse is recorded with
 *       {@link ParseStatus#FAILED} and its problems. It never fails the scan, and it never
 *       silently disappears from coverage reporting.</li>
 *   <li><b>Bounded output.</b> Reference and symbol counts per file are capped. A generated
 *       50 000-line file must not be able to exhaust the heap or the database, and the
 *       resulting truncation is reported rather than hidden.</li>
 *   <li><b>Comments are not parsed by default.</b> Javadoc summaries are extracted for the
 *       UI, but comments are otherwise skipped, because comment text is an
 *       attacker-controlled narrative that must never be promoted to instructions.</li>
 * </ol>
 */
@Component
public class JavaSourceParser {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceParser.class);

    /** Maximum declarations recorded for one file. */
    static final int MAX_SYMBOLS_PER_FILE = 4000;
    /** Maximum references recorded for one file. */
    static final int MAX_REFERENCES_PER_FILE = 4000;
    /** Javadoc summary length cap. */
    private static final int JAVADOC_SUMMARY_LIMIT = 400;
    /** Parser problem text cap. */
    private static final int PROBLEM_LIMIT = 300;
    /** Maximum parser problems retained per file. */
    private static final int MAX_PROBLEMS = 10;

    private final JavaParser javaParser;

    public JavaSourceParser() {
        ParserConfiguration configuration = new ParserConfiguration()
                // Java 21 matches the runtime RepoPilot requires, so records, sealed types,
                // pattern matching for switch and text blocks all parse.
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                // Javadoc is attributed so the first sentence can be shown in the UI. That
                // is the only comment-derived text RepoPilot keeps, and it is treated as
                // untrusted display data, never as instructions.
                .setAttributeComments(true)
                .setLexicalPreservationEnabled(false)
                // Tokens must be stored: line/column ranges are what the code viewer and the
                // citation validator rely on. Disabling token storage would make every
                // recorded position zero.
                .setStoreTokens(true)
                // Report every problem rather than stopping at the first: the set of
                // problems is what tells the user why a file was only partially understood.
                .setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8);
        this.javaParser = new JavaParser(configuration);
    }

    /**
     * Parses source text.
     *
     * @param relativePath repository-relative path, used for identity and diagnostics only
     * @param source       file content; never logged and never persisted
     */
    public ParsedFile parse(String relativePath, String source) {
        int lineCount = countLines(source);

        ParseResult<CompilationUnit> result;
        try {
            result = javaParser.parse(source);
        } catch (RuntimeException e) {
            // JavaParser can throw on pathological input (deep nesting causing a stack
            // overflow is the realistic case). Convert it into a recorded failure.
            return ParsedFile.skipped(relativePath, DetectedLanguage.JAVA, ParseStatus.FAILED,
                    "Parser error: " + e.getClass().getSimpleName(), lineCount);
        }

        List<String> problems = result.getProblems().stream()
                .map(this::describe)
                .limit(MAX_PROBLEMS)
                .toList();

        if (result.getResult().isEmpty()) {
            return ParsedFile.skipped(relativePath, DetectedLanguage.JAVA, ParseStatus.FAILED,
                    problems.isEmpty() ? "The file could not be parsed" : String.join("; ", problems),
                    lineCount);
        }

        CompilationUnit unit = result.getResult().get();
        try {
            return extract(relativePath, source, unit, problems, lineCount);
        } catch (RuntimeException e) {
            // A bug in extraction must not invalidate the scan, and must be visible.
            log.warn("extraction failed for {}: {}", relativePath, e.toString());
            return ParsedFile.skipped(relativePath, DetectedLanguage.JAVA, ParseStatus.PARSED_WITH_PROBLEMS,
                    "Declarations could not be fully extracted: " + e.getClass().getSimpleName(), lineCount);
        }
    }

    // ------------------------------------------------------------------
    // Extraction
    // ------------------------------------------------------------------

    private ParsedFile extract(String relativePath,
                               String source,
                               CompilationUnit unit,
                               List<String> problems,
                               int lineCount) {
        String packageName = unit.getPackageDeclaration()
                .map(declaration -> declaration.getNameAsString())
                .orElse("");

        List<String> imports = new ArrayList<>();
        for (ImportDeclaration importDeclaration : unit.getImports()) {
            String name = importDeclaration.getNameAsString();
            imports.add(importDeclaration.isStatic() ? "static " + name : name);
        }

        var extractor = new FileExtractor(relativePath, packageName);
        for (TypeDeclaration<?> type : unit.getTypes()) {
            extractor.visitType(type, null);
        }

        int totalSymbols = extractor.symbols.size();
        int totalReferences = extractor.references.size();
        boolean truncated = extractor.truncated;

        ParseStatus status = problems.isEmpty() ? ParseStatus.PARSED : ParseStatus.PARSED_WITH_PROBLEMS;

        // Suppress a false-positive source line: 'source' is accepted so a future
        // implementation can extract string-literal configuration without a signature change.
        assert source != null;

        return new ParsedFile(relativePath, DetectedLanguage.JAVA, packageName, imports, status,
                problems, extractor.symbols, extractor.references, extractor.mappings,
                lineCount, truncated, extractor.primaryTypeName(totalSymbols, totalReferences));
    }

    private String describe(Problem problem) {
        String message = problem.getMessage() == null ? "unspecified problem" : problem.getMessage();
        String location = problem.getLocation()
                .flatMap(tokenRange -> tokenRange.getBegin().getRange())
                .map(range -> "line " + range.begin.line)
                .orElse("unknown location");
        String combined = location + ": " + message;
        return combined.length() > PROBLEM_LIMIT ? combined.substring(0, PROBLEM_LIMIT) + "…" : combined;
    }

    /**
     * Line count for a file that was recorded without being parsed.
     *
     * <p>Public so the indexer counts lines the same way the parser does: a file must not
     * report a different line count depending on whether an analyzer for its language
     * exists.
     */
    public static int countLines(String source) {
        if (source == null || source.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Per-file extractor
    // ------------------------------------------------------------------

    /**
     * Walks one compilation unit, emitting symbols, references and framework mappings.
     *
     * <p>Stateful and single-use: created per file so no cross-file state can leak.
     */
    private static final class FileExtractor {

        private final String relativePath;
        private final String packageName;
        private final List<ParsedSymbol> symbols = new ArrayList<>();
        private final List<RawReference> references = new ArrayList<>();
        private final List<ParsedMapping> mappings = new ArrayList<>();
        private boolean truncated;

        /**
         * Class-level request path of the type currently being walked, so a method mapping
         * can be recorded as the full external path. Reset when a type's members are done.
         */
        private String currentClassPathPrefix;

        FileExtractor(String relativePath, String packageName) {
            this.relativePath = relativePath;
            this.packageName = packageName;
        }

        String primaryTypeName(int totalSymbols, int totalReferences) {
            for (ParsedSymbol symbol : symbols) {
                if (symbol.kind().isType() && symbol.parentQualifiedName() == null) {
                    return symbol.qualifiedName();
                }
            }
            return null;
        }

        // -------- types --------

        void visitType(TypeDeclaration<?> type, String enclosingQualifiedType) {
            if (symbols.size() >= MAX_SYMBOLS_PER_FILE) {
                truncated = true;
                return;
            }

            SymbolKind kind = kindOf(type);
            String simpleName = type.getNameAsString();
            String qualifiedName = enclosingQualifiedType == null
                    ? qualify(packageName, simpleName)
                    : enclosingQualifiedType + "." + simpleName;

            List<String> annotations = annotationNames(type);
            ParsedSymbol symbol = new ParsedSymbol(
                    key(relativePath, qualifiedName),
                    qualifiedName,
                    simpleName,
                    kind,
                    enclosingQualifiedType,
                    null,
                    null,
                    visibilityOf(type),
                    type.isStatic(),
                    (type instanceof ClassOrInterfaceDeclaration declaration && declaration.isAbstract())
                            || type instanceof AnnotationDeclaration,
                    line(type, true),
                    0,
                    line(type, false),
                    0,
                    annotations,
                    javadocSummary(type),
                    null,
                    isGenerated(annotations),
                    relativePath);
            symbols.add(symbol);

            referencesForAnnotations(annotations, symbol.key(), line(type, true));

            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
                    addReference(symbol.key(), EdgeType.EXTENDS, typeName(extended), null, line(type, true), null);
                }
                for (ClassOrInterfaceType implemented : declaration.getImplementedTypes()) {
                    addReference(symbol.key(), EdgeType.IMPLEMENTS, typeName(implemented), null, line(type, true), null);
                }
                mappingsForType(declaration, symbol, annotations);
            } else if (type instanceof EnumDeclaration declaration) {
                for (ClassOrInterfaceType implemented : declaration.getImplementedTypes()) {
                    addReference(symbol.key(), EdgeType.IMPLEMENTS, typeName(implemented), null, line(type, true), null);
                }
                for (EnumConstantDeclaration constant : declaration.getEntries()) {
                    symbols.add(new ParsedSymbol(
                            key(relativePath, qualifiedName + "#" + constant.getNameAsString()),
                            qualifiedName + "#" + constant.getNameAsString(),
                            constant.getNameAsString(),
                            SymbolKind.ENUM_CONSTANT,
                            qualifiedName,
                            null,
                            null,
                            SymbolVisibility.PUBLIC,
                            true,
                            false,
                            constant.getBegin().map(position -> position.line).orElse(line(type, true)),
                            0,
                            constant.getEnd().map(position -> position.line).orElse(line(type, false)),
                            0,
                            annotationNames(constant),
                            null,
                            null,
                            false,
                            relativePath));
                }
            } else if (type instanceof RecordDeclaration declaration) {
                for (ClassOrInterfaceType implemented : declaration.getImplementedTypes()) {
                    addReference(symbol.key(), EdgeType.IMPLEMENTS, typeName(implemented), null, line(type, true), null);
                }
                for (Parameter component : declaration.getParameters()) {
                    addTypeReference(symbol.key(), component.getType(), line(type, true), "record component");
                }
            }

            String previousPrefix = currentClassPathPrefix;
            currentClassPathPrefix = type instanceof ClassOrInterfaceDeclaration declaration
                    ? classLevelRequestMapping(declaration) : null;
            for (BodyDeclaration<?> member : type.getMembers()) {
                visitMember(member, qualifiedName, symbol.key());
            }
            currentClassPathPrefix = previousPrefix;
        }

        // -------- members --------

        void visitMember(BodyDeclaration<?> member, String enclosingType, String enclosingTypeKey) {
            if (symbols.size() >= MAX_SYMBOLS_PER_FILE) {
                truncated = true;
                return;
            }

            if (member instanceof TypeDeclaration<?> nested) {
                visitType(nested, enclosingType);
                return;
            }
            if (member instanceof MethodDeclaration method) {
                visitMethod(method, enclosingType, enclosingTypeKey);
                return;
            }
            if (member instanceof ConstructorDeclaration constructor) {
                visitConstructor(constructor, enclosingType, enclosingTypeKey);
                return;
            }
            if (member instanceof FieldDeclaration field) {
                visitField(field, enclosingType, enclosingTypeKey);
            }
        }

        void visitField(FieldDeclaration field, String enclosingType, String enclosingTypeKey) {
            List<String> annotations = annotationNames(field);
            for (VariableDeclarator variable : field.getVariables()) {
                String qualifiedName = enclosingType + "#" + variable.getNameAsString();
                String declaredType = typeName(field.getElementType());
                symbols.add(new ParsedSymbol(
                        key(relativePath, qualifiedName),
                        qualifiedName,
                        variable.getNameAsString(),
                        SymbolKind.FIELD,
                        enclosingType,
                        declaredType,
                        declaredType,
                        visibilityOf(field),
                        field.isStatic(),
                        false,
                        variable.getBegin().map(position -> position.line).orElse(line(field, true)),
                        0,
                        variable.getEnd().map(position -> position.line).orElse(line(field, false)),
                        0,
                        annotations,
                        javadocSummary(field),
                        null,
                        isGenerated(annotations),
                        relativePath));

                addTypeReference(enclosingTypeKey, field.getElementType(),
                        line(field, true), "field " + variable.getNameAsString());
                referencesForAnnotations(annotations, enclosingTypeKey, line(field, true));

                if (variable.getInitializer().isPresent()) {
                    Expression initializer = variable.getInitializer().get();
                    // new HashMap<>() as a field initialiser is the canonical unbounded-cache
                    // pattern; capturing the instantiated type lets the memory checks see it.
                    if (initializer instanceof ObjectCreationExpr creation) {
                        addReference(enclosingTypeKey, EdgeType.INSTANTIATES, typeName(creation.getType()),
                                null, line(field, true), null);
                    }
                    collectArgumentTypes(initializer, enclosingTypeKey);
                }
            }
        }

        void visitMethod(MethodDeclaration method, String enclosingType, String enclosingTypeKey) {
            String signature = signatureOf(method);
            String qualifiedName = enclosingType + "#" + method.getNameAsString() + "(" + signature + ")";
            String symbolKey = key(relativePath, qualifiedName);
            List<String> annotations = annotationNames(method);

            symbols.add(new ParsedSymbol(
                    symbolKey,
                    qualifiedName,
                    method.getNameAsString(),
                    SymbolKind.METHOD,
                    enclosingType,
                    signature,
                    typeName(method.getType()),
                    visibilityOf(method),
                    method.isStatic(),
                    method.isAbstract(),
                    line(method, true),
                    0,
                    line(method, false),
                    0,
                    annotations,
                    javadocSummary(method),
                    complexityOf(method),
                    isGenerated(annotations),
                    relativePath));

            addTypeReference(symbolKey, method.getType(), line(method, true), "return type");
            for (Parameter parameter : method.getParameters()) {
                addTypeReference(symbolKey, parameter.getType(), line(method, true),
                        "parameter " + parameter.getNameAsString());
                referencesForAnnotations(annotationNames(parameter), symbolKey, line(method, true));
            }
            for (var thrown : method.getThrownExceptions()) {
                addReference(symbolKey, EdgeType.THROWS, typeName(thrown), null, line(method, true), null);
            }
            referencesForAnnotations(annotations, symbolKey, line(method, true));
            mappingsForCallable(method, symbolKey, annotations, line(method, true));

            method.getBody().ifPresent(body -> collectFromNode(body, symbolKey));
        }

        void visitConstructor(ConstructorDeclaration constructor, String enclosingType, String enclosingTypeKey) {
            String signature = constructor.getParameters().stream()
                    .map(parameter -> typeName(parameter.getType()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
            String qualifiedName = enclosingType + "#<init>(" + signature + ")";
            String symbolKey = key(relativePath, qualifiedName);
            List<String> annotations = annotationNames(constructor);

            symbols.add(new ParsedSymbol(
                    symbolKey,
                    qualifiedName,
                    "<init>",
                    SymbolKind.CONSTRUCTOR,
                    enclosingType,
                    signature,
                    null,
                    visibilityOf(constructor),
                    false,
                    false,
                    line(constructor, true),
                    0,
                    line(constructor, false),
                    0,
                    annotations,
                    javadocSummary(constructor),
                    complexityOf(constructor),
                    false,
                    relativePath));

            for (Parameter parameter : constructor.getParameters()) {
                addTypeReference(symbolKey, parameter.getType(), line(constructor, true),
                        "parameter " + parameter.getNameAsString());
            }
            for (var thrown : constructor.getThrownExceptions()) {
                addReference(symbolKey, EdgeType.THROWS, typeName(thrown), null, line(constructor, true), null);
            }
            referencesForAnnotations(annotations, symbolKey, line(constructor, true));

            constructor.getBody().accept(new BodyCollector(this, symbolKey), null);
        }

        // -------- body scanning --------

        /**
         * Collects references from an arbitrary subtree. Used for field initialisers, where
         * the enclosing symbol is the declaring type.
         */
        void collectFromNode(Node node, String symbolKey) {
            node.accept(new BodyCollector(this, symbolKey), null);
        }

        /** Extracts a type reference from an expression context such as a generic argument. */
        void collectArgumentTypes(Expression expression, String symbolKey) {
            expression.findAll(ClassOrInterfaceType.class).forEach(type ->
                    addReference(symbolKey, EdgeType.TYPE_REFERENCE, typeName(type), null, line(expression, true),
                            "generic argument"));
        }

        // -------- mapping (framework surface) detection --------

        private void mappingsForType(ClassOrInterfaceDeclaration declaration,
                                     ParsedSymbol symbol,
                                     List<String> annotations) {
            String classPathPrefix = classLevelRequestMapping(declaration);

            if (annotations.contains("RestController") || annotations.contains("Controller")) {
                if (classPathPrefix != null && !classPathPrefix.isEmpty()) {
                    // The class-level mapping is recorded even without a method mapping, so a
                    // controller that delegates to an interface still shows a surface.
                    addMapping(ParsedMapping.MappingTypeValue.REST_ENDPOINT, symbol.key(),
                            classPathPrefix, null, null, null,
                            "class-level @RequestMapping", line(declaration, true));
                }
            }
            if (isJpaRepository(declaration)) {
                addMapping(ParsedMapping.MappingTypeValue.JPA_REPOSITORY, symbol.key(),
                        null, null, null, null,
                        "extends a Spring Data repository interface", line(declaration, true));
            }
            for (AnnotationExpr annotation : declaration.getAnnotations()) {
                String name = annotation.getNameAsString();
                switch (simpleName(name)) {
                    case "FeignClient" -> addMapping(ParsedMapping.MappingTypeValue.FEIGN_CLIENT, symbol.key(),
                            null, null, null, null, annotationText(annotation), line(declaration, true));
                    case "ConfigurationProperties" -> addMapping(
                            ParsedMapping.MappingTypeValue.CONFIGURATION_PROPERTIES, symbol.key(),
                            null, null, null, null, annotationText(annotation), line(declaration, true));
                    case "Component", "Service", "Repository", "Configuration" -> addMapping(
                            ParsedMapping.MappingTypeValue.SPRING_BEAN, symbol.key(),
                            null, null, null, null, simpleName(name), line(declaration, true));
                    default -> {
                        // Not a framework surface RepoPilot models.
                    }
                }
            }
        }

        private void mappingsForCallable(NodeWithAnnotations<?> node,
                                         String symbolKey,
                                         List<String> annotations,
                                         int startLine) {
            String httpMethod = null;
            String path = null;

            for (AnnotationExpr annotation : node.getAnnotations()) {
                String name = simpleName(annotation.getNameAsString());
                switch (name) {
                    case "GetMapping" -> {
                        httpMethod = "GET";
                        path = firstStringValue(annotation);
                    }
                    case "PostMapping" -> {
                        httpMethod = "POST";
                        path = firstStringValue(annotation);
                    }
                    case "PutMapping" -> {
                        httpMethod = "PUT";
                        path = firstStringValue(annotation);
                    }
                    case "DeleteMapping" -> {
                        httpMethod = "DELETE";
                        path = firstStringValue(annotation);
                    }
                    case "PatchMapping" -> {
                        httpMethod = "PATCH";
                        path = firstStringValue(annotation);
                    }
                    case "RequestMapping" -> {
                        httpMethod = null;
                        path = firstStringValue(annotation);
                    }
                    case "KafkaListener" -> addMapping(ParsedMapping.MappingTypeValue.KAFKA_LISTENER, symbolKey,
                            null, null, firstNamedStringValue(annotation, "topics", "topicPattern", "topic"),
                            firstNamedStringValue(annotation, "groupId"),
                            annotationText(annotation), startLine);
                    case "Scheduled" -> addMapping(ParsedMapping.MappingTypeValue.SCHEDULED, symbolKey,
                            null, null, null, null, annotationText(annotation), startLine);
                    case "Cacheable", "CachePut", "CacheEvict" -> addMapping(
                            ParsedMapping.MappingTypeValue.CACHEABLE, symbolKey,
                            null, null, null, null, annotationText(annotation), startLine);
                    case "Async" -> addMapping(ParsedMapping.MappingTypeValue.ASYNC, symbolKey,
                            null, null, null, null, "asynchronous execution", startLine);
                    case "Transactional" -> addMapping(ParsedMapping.MappingTypeValue.TRANSACTIONAL, symbolKey,
                            null, null, null, null, annotationText(annotation), startLine);
                    default -> {
                        // Not a mapped surface.
                    }
                }
            }

            if (httpMethod != null || path != null) {
                addMapping(ParsedMapping.MappingTypeValue.REST_ENDPOINT, symbolKey,
                        combinePaths(currentClassPathPrefix, path), httpMethod, null, null, null, startLine);
            }
        }

        /**
         * Joins a class-level request path with a method-level one into the external path.
         * Kept separate from the class-level mapping row, which remains recorded so a
         * controller with no method mappings still shows a surface.
         */
        private static String combinePaths(String prefix, String path) {
            String left = prefix == null ? "" : prefix.strip();
            String right = path == null ? "" : path.strip();
            if (left.isEmpty()) {
                return right.isEmpty() ? null : right;
            }
            if (right.isEmpty()) {
                return left;
            }
            String joined = (left.endsWith("/") || right.startsWith("/"))
                    ? left + right
                    : left + "/" + right;
            return joined.replaceAll("/{2,}", "/");
        }

        private void addMapping(ParsedMapping.MappingTypeValue type, String symbolKey,
                                String path, String httpMethod, String topic, String groupId,
                                String detail, int startLine) {
            if (mappings.size() > 500) {
                truncated = true;
                return;
            }
            mappings.add(new ParsedMapping(type, symbolKey,
                    truncate(path, 1000), truncate(httpMethod, 16),
                    truncate(topic, 500), truncate(groupId, 500),
                    truncate(detail, 400), startLine));
        }

        private String classLevelRequestMapping(ClassOrInterfaceDeclaration declaration) {
            for (AnnotationExpr annotation : declaration.getAnnotations()) {
                if ("RequestMapping".equals(simpleName(annotation.getNameAsString()))) {
                    String value = firstStringValue(annotation);
                    return value == null ? "" : value;
                }
            }
            return null;
        }

        private boolean isJpaRepository(ClassOrInterfaceDeclaration declaration) {
            for (ClassOrInterfaceType implemented : declaration.getImplementedTypes()) {
                for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
                    if (isSpringDataRepositoryType(extended.getNameAsString())) {
                        return true;
                    }
                }
                if (isSpringDataRepositoryType(implemented.getNameAsString())) {
                    return true;
                }
            }
            for (ClassOrInterfaceType extended : declaration.getExtendedTypes()) {
                if (isSpringDataRepositoryType(extended.getNameAsString())) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isSpringDataRepositoryType(String name) {
            return switch (name) {
                case "JpaRepository", "CrudRepository", "PagingAndSortingRepository",
                     "ReactiveCrudRepository", "ReactiveSortingRepository", "MongoRepository",
                     "JpaSpecificationExecutor", "Repository" -> true;
                default -> false;
            };
        }

        // -------- reference helpers --------

        void addReference(String symbolKey, EdgeType edgeType, String targetName,
                          String scopeText, int sourceLine, String detail) {
            // Cleaning happens before the emptiness check: a target such as {@code var} or
            // {@code val} is non-blank as written but is not a type, and must not become a
            // row with a null name.
            String cleaned = cleanTypeName(targetName);
            if (cleaned == null || cleaned.isBlank()) {
                return;
            }
            if (references.size() >= MAX_REFERENCES_PER_FILE) {
                truncated = true;
                return;
            }
            references.add(new RawReference(symbolKey, edgeType,
                    truncate(cleaned, 1500),
                    truncate(scopeText, 500),
                    sourceLine,
                    truncate(detail, 400)));
        }

        private void addTypeReference(String symbolKey, Type type, int sourceLine, String detail) {
            if (type == null) {
                return;
            }
            if (type.isPrimitiveType() || type.isVoidType()) {
                return;
            }
            if (type.isArrayType()) {
                addTypeReference(symbolKey, type.asArrayType().getComponentType(), sourceLine, detail);
                return;
            }
            addReference(symbolKey, EdgeType.TYPE_REFERENCE, typeName(type), null, sourceLine, detail);
            // Generic arguments are real dependencies: List<Order> depends on Order.
            for (ClassOrInterfaceType argument : type.findAll(ClassOrInterfaceType.class)) {
                if (!typeName(argument).equals(typeName(type))) {
                    addReference(symbolKey, EdgeType.TYPE_REFERENCE, typeName(argument), null, sourceLine,
                            detail + " (type argument)");
                }
            }
        }

        private void referencesForAnnotations(List<String> annotations, String symbolKey, int sourceLine) {
            for (String annotation : annotations) {
                addReference(symbolKey, EdgeType.ANNOTATION, annotation, null, sourceLine, null);
            }
        }

        // -------- small utilities --------

        static String key(String relativePath, String qualifiedName) {
            return relativePath + "#" + qualifiedName;
        }

        static String qualify(String packageName, String simpleName) {
            return packageName == null || packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
        }

        static String typeName(Type type) {
            if (type == null) {
                return null;
            }
            // asString() preserves generic arguments: the resolver strips them for lookup while
            // the graph shows them as written. A diamond has no argument to show, so
            // "ArrayList<>" would only ever be an unreadable node label.
            return stripAnnotations(type.asString()).replace("<>", "");
        }

        static String cleanTypeName(String raw) {
            if (raw == null) {
                return null;
            }
            String value = raw.strip();
            // var/val are not types.
            if ("var".equals(value) || "val".equals(value)) {
                return null;
            }
            return value;
        }

        private static String stripAnnotations(String value) {
            if (value == null || value.indexOf('@') < 0) {
                return value;
            }
            return value.replaceAll("@\\w+(\\([^)]*\\))?\\s*", "");
        }

        static SymbolKind kindOf(TypeDeclaration<?> type) {
            if (type instanceof AnnotationDeclaration) {
                return SymbolKind.ANNOTATION;
            }
            if (type instanceof EnumDeclaration) {
                return SymbolKind.ENUM;
            }
            if (type instanceof RecordDeclaration) {
                return SymbolKind.RECORD;
            }
            if (type instanceof ClassOrInterfaceDeclaration declaration) {
                return declaration.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
            }
            return SymbolKind.CLASS;
        }

        static SymbolVisibility visibilityOf(Node node) {
            if (node instanceof com.github.javaparser.ast.nodeTypes.NodeWithModifiers<?> withModifiers) {
                return switch (withModifiers.getAccessSpecifier()) {
                    case PUBLIC -> SymbolVisibility.PUBLIC;
                    case PROTECTED -> SymbolVisibility.PROTECTED;
                    case PRIVATE -> SymbolVisibility.PRIVATE;
                    case NONE -> SymbolVisibility.PACKAGE_PRIVATE;
                };
            }
            return SymbolVisibility.PACKAGE_PRIVATE;
        }

        static List<String> annotationNames(Node node) {
            List<AnnotationExpr> annotations = node.getChildNodes().stream()
                    .filter(AnnotationExpr.class::isInstance)
                    .map(AnnotationExpr.class::cast)
                    .toList();
            if (annotations.isEmpty()) {
                annotations = node.findAll(AnnotationExpr.class).stream()
                        // Only annotations directly attached to this node's header, not ones
                        // nested inside the body.
                        .filter(annotation -> annotation.getParentNode().filter(parent -> parent == node).isPresent())
                        .toList();
            }
            Set<String> names = new LinkedHashSet<>();
            for (AnnotationExpr annotation : annotations) {
                names.add(simpleName(annotation.getNameAsString()));
            }
            return List.copyOf(names);
        }

        static String simpleName(String qualified) {
            if (qualified == null) {
                return "";
            }
            int dot = qualified.lastIndexOf('.');
            return dot < 0 ? qualified : qualified.substring(dot + 1);
        }

        static int line(Node node, boolean begin) {
            Optional<com.github.javaparser.Position> position = begin
                    ? node.getBegin()
                    : node.getEnd();
            return position.map(value -> value.line).orElse(0);
        }

        static String javadocSummary(Node node) {
            if (!(node instanceof com.github.javaparser.ast.nodeTypes.NodeWithJavadoc<?> withJavadoc)) {
                return null;
            }
            return withJavadoc.getJavadoc()
                    .map(javadoc -> {
                        String text = javadoc.getDescription().toText().strip();
                        if (text.isEmpty()) {
                            return null;
                        }
                        return truncate(text.replaceAll("\\s+", " "), JAVADOC_SUMMARY_LIMIT);
                    })
                    .orElse(null);
        }

        static String annotationText(AnnotationExpr annotation) {
            return truncate(annotation.toString().replaceAll("\\s+", " "), 400);
        }

        static String firstStringValue(AnnotationExpr annotation) {
            return annotation.findAll(StringLiteralExpr.class).stream()
                    .map(StringLiteralExpr::getValue)
                    .findFirst()
                    .orElse(null);
        }

        static String firstNamedStringValue(AnnotationExpr annotation, String... names) {
            for (String name : names) {
                var values = annotation.findAll(com.github.javaparser.ast.expr.MemberValuePair.class);
                for (var pair : values) {
                    if (pair.getNameAsString().equals(name)) {
                        if (pair.getValue() instanceof StringLiteralExpr literal) {
                            return literal.getValue();
                        }
                        // Array initialisers: {@code topics = {"a","b"}} or {@code topics = "a"}
                        String joined = pair.getValue().findAll(StringLiteralExpr.class).stream()
                                .map(StringLiteralExpr::getValue)
                                .reduce((left, right) -> left + "," + right)
                                .orElse(null);
                        if (joined != null) {
                            return joined;
                        }
                    }
                }
                // Shorthand form: @KafkaListener("topic")
                if ("topics".equals(name) || "topic".equals(name)) {
                    String shorthand = firstStringValue(annotation);
                    if (shorthand != null) {
                        return shorthand;
                    }
                }
            }
            return null;
        }

        static boolean isGenerated(List<String> annotations) {
            return annotations.contains("Generated") || annotations.contains("GeneratedValue");
        }

        static int complexityOf(com.github.javaparser.ast.body.CallableDeclaration<?> callable) {
            int decisionPoints = 0;
            decisionPoints += callable.findAll(IfStmt.class).size();
            decisionPoints += callable.findAll(ForStmt.class).size();
            decisionPoints += callable.findAll(ForEachStmt.class).size();
            decisionPoints += callable.findAll(WhileStmt.class).size();
            decisionPoints += callable.findAll(DoStmt.class).size();
            decisionPoints += callable.findAll(CatchClause.class).size();
            decisionPoints += callable.findAll(ConditionalExpr.class).size();
            decisionPoints += callable.findAll(InstanceOfExpr.class).size();
            decisionPoints += callable.findAll(BinaryExpr.class).stream()
                    .filter(binary -> binary.getOperator() == BinaryExpr.Operator.AND
                            || binary.getOperator() == BinaryExpr.Operator.OR)
                    .count();
            decisionPoints += callable.findAll(SwitchEntry.class).stream()
                    .filter(entry -> !entry.getLabels().isEmpty())
                    .count();
            decisionPoints += callable.getParameters().size();
            // Switch expressions without a selector entry (default-only) still add a path.
            return Math.max(1, 1 + decisionPoints);
        }

        static String signatureOf(MethodDeclaration method) {
            return method.getParameters().stream()
                    .map(parameter -> typeName(parameter.getType()))
                    .reduce((left, right) -> left + "," + right)
                    .orElse("");
        }

        static String truncate(String value, int max) {
            if (value == null) {
                return null;
            }
            String single = value.length() <= max ? value : value.substring(0, max - 1) + "…";
            return single;
        }
    }

    /**
     * Collects references from statements and expressions inside a method or field body.
     *
     * <p>Deliberately a focused visitor rather than a full traversal: RepoPilot records the
     * dependency-relevant expressions (instantiation, invocation, declared types, casts,
     * instanceof, static access) and ignores literals, operators and control flow, which
     * would multiply reference counts without adding graph value.
     */
    private static final class BodyCollector
            extends com.github.javaparser.ast.visitor.VoidVisitorAdapter<Void> {

        private final FileExtractor owner;
        private final String symbolKey;

        BodyCollector(FileExtractor owner, String symbolKey) {
            this.owner = owner;
            this.symbolKey = symbolKey;
        }

        @Override
        public void visit(ObjectCreationExpr creation, Void arg) {
            owner.addReference(symbolKey, EdgeType.INSTANTIATES,
                    FileExtractor.typeName(creation.getType()), null,
                    FileExtractor.line(creation, true), null);
            super.visit(creation, arg);
        }

        @Override
        public void visit(MethodCallExpr call, Void arg) {
            String scope = call.getScope().map(Expression::toString).orElse(null);
            owner.addReference(symbolKey, EdgeType.CALLS, call.getNameAsString(),
                    scope == null ? null : FileExtractor.truncate(scope, 400),
                    FileExtractor.line(call, true), null);

            // A Kafka producer send is a real event-flow edge. It is inferred from the call
            // shape, never asserted: a template variable could be aliased, and the topic
            // could come from configuration rather than a literal.
            if ("send".equals(call.getNameAsString()) && scope != null
                    && scope.toLowerCase(Locale.ROOT).contains("template")
                    && !call.getArguments().isEmpty()
                    && call.getArgument(0) instanceof StringLiteralExpr topic) {
                owner.addMapping(ParsedMapping.MappingTypeValue.KAFKA_PRODUCER, symbolKey,
                        null, null, topic.getValue(), null,
                        "send(" + topic.getValue() + ") via " + FileExtractor.truncate(scope, 100),
                        FileExtractor.line(call, true));
                owner.addReference(symbolKey, EdgeType.KAFKA_PUBLISH, topic.getValue(), scope,
                        FileExtractor.line(call, true), "inferred from a producer call");
            }
            super.visit(call, arg);
        }

        @Override
        public void visit(VariableDeclarator declarator, Void arg) {
            var declaredType = declarator.getType();
            if (!declaredType.isPrimitiveType() && !declaredType.isVoidType()) {
                String type = FileExtractor.typeName(declaredType);
                if (type != null) {
                    owner.addReference(symbolKey, EdgeType.TYPE_REFERENCE, type, null,
                            FileExtractor.line(declarator, true), "local variable");
                }
            }
            super.visit(declarator, arg);
        }

        @Override
        public void visit(CastExpr cast, Void arg) {
            owner.addReference(symbolKey, EdgeType.TYPE_REFERENCE,
                    FileExtractor.typeName(cast.getType()), null,
                    FileExtractor.line(cast, true), "cast");
            super.visit(cast, arg);
        }

        @Override
        public void visit(InstanceOfExpr instanceOf, Void arg) {
            owner.addReference(symbolKey, EdgeType.TYPE_REFERENCE,
                    FileExtractor.typeName(instanceOf.getType()), null,
                    FileExtractor.line(instanceOf, true), "instanceof");
            super.visit(instanceOf, arg);
        }

        @Override
        public void visit(FieldAccessExpr fieldAccess, Void arg) {
            // Only record static-style access, where the scope is a type name. Recording
            // every field read would produce an enormous, low-value reference set.
            if (fieldAccess.getScope() instanceof NameExpr name
                    && !name.getNameAsString().isEmpty()
                    && Character.isUpperCase(name.getNameAsString().charAt(0))) {
                owner.addReference(symbolKey, EdgeType.TYPE_REFERENCE,
                        name.getNameAsString(), null,
                        FileExtractor.line(fieldAccess, true),
                        "static access: " + FileExtractor.truncate(fieldAccess.getNameAsString(), 100));
            }
            super.visit(fieldAccess, arg);
        }

        @Override
        public void visit(ClassOrInterfaceType type, Void arg) {
            owner.addReference(symbolKey, EdgeType.TYPE_REFERENCE, FileExtractor.typeName(type),
                    null, FileExtractor.line(type, true), "type usage");
            super.visit(type, arg);
        }
    }
}
