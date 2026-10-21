package dev.repopilot.investigation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.repopilot.ai.AiProviderException;
import dev.repopilot.ai.AiProviderRegistry;
import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatRequest;
import dev.repopilot.investigation.answer.CitationValidator;
import dev.repopilot.investigation.answer.InvestigationAnswer;
import dev.repopilot.investigation.answer.InvestigationAnswerParser;
import dev.repopilot.investigation.answer.SourceVerifier;
import dev.repopilot.investigation.prompt.InvestigationPrompt;
import dev.repopilot.investigation.prompt.InvestigationPromptBuilder;
import dev.repopilot.jobs.JobAbortedException;
import dev.repopilot.jobs.JobContext;
import dev.repopilot.jobs.JobHandler;
import dev.repopilot.jobs.JobResult;
import dev.repopilot.jobs.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code INVESTIGATION} job handler: retrieve, ask, check, record.
 *
 * <h2>The order is the point</h2>
 * <ol>
 *   <li>Retrieval runs before any provider call, and an empty result fails the investigation
 *       without calling a model at all. Asking a model a question about code it was not shown is
 *       how ungrounded answers are manufactured.</li>
 *   <li>The prompt is built deterministically from the retrieved evidence and hashed. What the
 *       model saw is therefore reproducible in shape, even though the model's answer is not.</li>
 *   <li>The model call happens with <b>no transaction open</b>. It is bounded by a timeout, and a
 *       failure is recorded on the investigation before the job is failed, so the reason survives
 *       in the record the user reads rather than only in the job's error field.</li>
 *   <li>Every cited excerpt is re-read from disk and compared with the hash of what was sent.</li>
 *   <li>Claims are stored as the model wrote them, with the backend's verdict in a separate
 *       column. Nothing is rewritten, and an ungrounded answer is stored rather than discarded:
 *       the user is entitled to see what the model said and why it did not hold up.</li>
 * </ol>
 */
@Component
public class InvestigationJobHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(InvestigationJobHandler.class);

    /** A local model can take a while; beyond this the job is failed with a specific message. */
    private static final Duration MODEL_TIMEOUT = Duration.ofMinutes(3);
    private static final int MAX_ANSWER_TOKENS = 1_500;
    private static final int MAX_LIMITATIONS_CHARS = 2_000;

    private final InvestigationStore store;
    private final EvidenceRetriever retriever;
    private final SourceVerifier verifier;
    private final AiProviderRegistry registry;
    private final ObjectMapper json;

    public InvestigationJobHandler(InvestigationStore store,
                                   EvidenceRetriever retriever,
                                   SourceVerifier verifier,
                                   AiProviderRegistry registry,
                                   ObjectMapper json) {
        this.store = store;
        this.retriever = retriever;
        this.verifier = verifier;
        this.registry = registry;
        this.json = json;
    }

    @Override
    public JobType jobType() {
        return JobType.INVESTIGATION;
    }

    @Override
    public JobResult execute(JobContext context) {
        UUID investigationId = investigationId(context);

        if (registry.active().isEmpty()) {
            String message = registry.unavailableMessage();
            store.fail(investigationId, message, null);
            throw new IllegalStateException(message);
        }

        InvestigationStore.Start start = store.begin(investigationId, context.jobId());
        try {
            return run(context, start);
        } catch (JobAbortedException e) {
            store.cancel(investigationId);
            throw e;
        }
    }

    private JobResult run(JobContext context, InvestigationStore.Start start) {
        context.checkpoint();
        context.progressNow(15, "Retrieving evidence",
                "Matching findings, symbols and files against the question");

        EvidenceRetriever.RetrievalResult retrieval = retriever.retrieve(
                start.repositoryId(), start.revisionId(), start.question());
        // A repository summary is always available, but counts are context rather than evidence:
        // answering a question from "this repository has 3 008 files" would be an answer to
        // nothing. Only a summary means the question matched no evidence at all.
        boolean onlyContext = retrieval.items().stream()
                .allMatch(item -> item.kind() == EvidenceKind.REPOSITORY);
        if (retrieval.items().isEmpty() || onlyContext) {
            String message = "No indexed evidence matched this question, so nothing was sent to a "
                    + "model and no answer was produced."
                    + (retrieval.notes().isEmpty() ? "" : " " + String.join(" ", retrieval.notes()));
            store.fail(start.investigationId(), message,
                    new InvestigationStore.CallInfo(start.providerId(), start.modelName(),
                            null, null, null, null));
            throw new IllegalStateException(message);
        }

        InvestigationPrompt prompt = InvestigationPromptBuilder.build(
                start.question(), start.revisionId(), retrieval);
        // Retrieval reports items it dropped to stay within the twelve-item cap. The prompt budget can
        // drop further items, and the diagnostics must not stay silent about that: the evidence table
        // is a complete record of what the model was shown, so "what was not shown" has to be said.
        List<String> notes = new ArrayList<>(retrieval.notes());
        int omittedByBudget = retrieval.items().size() - prompt.items().size();
        if (omittedByBudget > 0) {
            notes.add(omittedByBudget + " item(s) were left out of the prompt to stay within the "
                    + InvestigationPromptBuilder.MAX_PROMPT_CHARS + "-character prompt budget.");
        }
        context.progressNow(35, "Asking the provider",
                prompt.items().size() + " evidence item(s), " + prompt.charCount() + " characters");

        long startedNanos = System.nanoTime();
        ChatCompletion completion;
        try {
            completion = registry.complete(ChatRequest.builder(start.modelName())
                            .system(prompt.system())
                            .user(prompt.user())
                            .temperature(0.1)
                            .maxTokens(MAX_ANSWER_TOKENS)
                            .jsonMode(true)
                            .requestId(context.jobId().toString())
                            .build())
                    .block(MODEL_TIMEOUT);
        } catch (AiProviderException e) {
            long latency = (System.nanoTime() - startedNanos) / 1_000_000;
            String message = "The AI provider did not answer: " + e.getMessage()
                    + " (" + e.kind() + ")";
            store.fail(start.investigationId(), message, new InvestigationStore.CallInfo(
                    start.providerId(), start.modelName(), prompt.charCount(),
                    prompt.contextChars(), latency, prompt.sha256()));
            throw new IllegalStateException(message, e);
        } catch (RuntimeException e) {
            // Reactor reports a block timeout as IllegalStateException; anything else at this
            // point is still a provider-side failure as far as the user is concerned.
            long latency = (System.nanoTime() - startedNanos) / 1_000_000;
            String message = "The AI provider did not answer within "
                    + MODEL_TIMEOUT.toMinutes() + " minutes: " + safeMessage(e);
            store.fail(start.investigationId(), message, new InvestigationStore.CallInfo(
                    start.providerId(), start.modelName(), prompt.charCount(),
                    prompt.contextChars(), latency, prompt.sha256()));
            throw new IllegalStateException(message, e);
        }

        long latencyMs = (System.nanoTime() - startedNanos) / 1_000_000;
        InvestigationStore.CallInfo call = new InvestigationStore.CallInfo(
                start.providerId(),
                completion != null && completion.model() != null ? completion.model() : start.modelName(),
                prompt.charCount(), prompt.contextChars(), latencyMs, prompt.sha256());

        context.checkpoint();
        context.progressNow(70, "Checking citations", "Re-reading every cited excerpt");

        if (completion == null || completion.isEmpty()) {
            String message = "The AI provider returned no text, so there is no answer to check.";
            store.fail(start.investigationId(), message, call);
            throw new IllegalStateException(message);
        }

        InvestigationAnswerParser.ParsedAnswer parsed = InvestigationAnswerParser.parse(
                completion.content(), json);
        InvestigationAnswer answer = parsed.parsed()
                ? parsed.answer()
                : new InvestigationAnswer(parsed.raw(), List.of(), null, List.of(), null);

        Map<String, SourceStatus> sourceStatus = verifier.verify(
                start.repositoryId(), start.revisionId(), prompt.items());
        CitationValidator.ValidationResult validation = CitationValidator.validate(
                answer, prompt.items(), sourceStatus);

        Set<String> sentKeys = new LinkedHashSet<>();
        for (EvidenceItem item : prompt.items()) {
            sentKeys.add(item.key());
        }
        Set<String> cited = new LinkedHashSet<>(validation.citedKeys());
        cited.retainAll(sentKeys);

        String limitations = limitations(parsed, completion.truncated());
        String diagnostics = diagnostics(parsed, validation, notes);

        context.progress(90, "Recording the verdict", null);
        return store.complete(start.investigationId(), new InvestigationStore.Outcome(
                truncate(answer.answer(), 20_000),
                answer.confidence(),
                retrieval.summary(),
                limitations,
                validation.grounded(),
                diagnostics,
                call,
                prompt.items(),
                sourceStatus,
                cited,
                validation.claims()));
    }

    private String limitations(InvestigationAnswerParser.ParsedAnswer parsed, boolean truncated) {
        StringBuilder limitations = new StringBuilder();
        if (!parsed.parsed()) {
            limitations.append("The model did not return the JSON object this build requires, so the ")
                    .append("text above is its raw response and no claim in it could be checked. ");
        } else if (parsed.answer().limitations() != null && !parsed.answer().limitations().isBlank()) {
            limitations.append(parsed.answer().limitations().strip()).append(' ');
        }
        if (truncated) {
            limitations.append("The answer was cut off at the token limit, so it may be incomplete. ");
        }
        return truncate(limitations.toString().strip(), MAX_LIMITATIONS_CHARS);
    }

    private String diagnostics(InvestigationAnswerParser.ParsedAnswer parsed,
                               CitationValidator.ValidationResult validation,
                               List<String> retrievalNotes) {
        var node = json.createObjectNode();
        var unanswered = node.putArray("unanswered");
        if (parsed.parsed()) {
            parsed.answer().unanswered().forEach(unanswered::add);
        }
        var dangling = node.putArray("danglingMarkers");
        validation.danglingMarkers().forEach(dangling::add);
        var retrieval = node.putArray("retrievalNotes");
        retrievalNotes.forEach(retrieval::add);
        var parserNotes = node.putArray("parserNotes");
        parsed.notes().forEach(parserNotes::add);
        validation.notes().forEach(parserNotes::add);
        return node.toString();
    }

    private UUID investigationId(JobContext context) {
        String parameters = context.parametersJson();
        if (parameters == null || parameters.isBlank()) {
            throw new IllegalArgumentException(
                    "An investigation job requires an investigationId parameter; none was supplied.");
        }
        try {
            JsonNode node = json.readTree(parameters);
            JsonNode id = node == null ? null : node.get("investigationId");
            if (id == null || id.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "An investigation job requires an investigationId parameter; none was supplied.");
            }
            return UUID.fromString(id.asText());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "The investigation job's parameters are not readable JSON.", e);
        }
    }

    private static String safeMessage(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
