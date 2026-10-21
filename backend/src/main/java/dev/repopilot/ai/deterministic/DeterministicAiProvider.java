package dev.repopilot.ai.deterministic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.repopilot.ai.AiFailureKind;
import dev.repopilot.ai.AiProvider;
import dev.repopilot.ai.AiProviderException;
import dev.repopilot.ai.ModelAvailability;
import dev.repopilot.ai.model.ChatCompletion;
import dev.repopilot.ai.model.ChatMessage;
import dev.repopilot.ai.model.ChatRequest;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A provider that does not call a model, and says so in every answer it produces.
 *
 * <h2>Why this exists</h2>
 * The part of an investigation that RepoPilot is responsible for is retrieval and citation
 * checking. That part has to be exercisable on a machine with no model runtime — in CI, in a
 * container, or on a laptop where Ollama is not running — and the honest way to do that is not
 * to fake a model but to declare a second implementation of the same contract and label it
 * everywhere it appears.
 *
 * <h2>What it does, precisely</h2>
 * It reads the numbered evidence blocks out of the prompt it was given and returns them as the
 * answer contract: one {@code OBSERVATION} claim per evidence item, each citing that item's
 * key, with the item's own label and detail quoted. It draws no conclusion, ranks nothing and
 * composes no sentence that is not already in the evidence. Its answers are therefore
 * extractive by construction, and the citation check that runs afterwards is the real check:
 * if this provider's output ever came back ungrounded, retrieval or validation would be broken.
 *
 * <h2>How it is prevented from being mistaken for a model</h2>
 * <ul>
 *   <li>It is only active when {@code repopilot.ai.provider=deterministic}; the default is
 *       {@code ollama}.</li>
 *   <li>Its id, display name and model name say "deterministic" and "no model".</li>
 *   <li>Every answer's {@code limitations} field states that no language model was called.</li>
 *   <li>The investigation row stores the provider id, and the UI renders it.</li>
 * </ul>
 */
public class DeterministicAiProvider implements AiProvider {

    public static final String PROVIDER_ID = "deterministic";
    /** The name stored as the "model" so a stored investigation is never ambiguous. */
    public static final String MODEL_NAME = "deterministic-extractor (no model called)";

    private static final Pattern BLOCK = Pattern.compile("(?m)^\\[(E\\d+)] (\\S+) — (.*)$");
    private static final int MAX_ANSWER_CHARS = 16_000;

    private final ObjectMapper json;

    public DeterministicAiProvider(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public String displayName() {
        return "Deterministic extractor (no model)";
    }

    @Override
    public String chatModel() {
        return MODEL_NAME;
    }

    @Override
    public boolean supportsEmbeddings() {
        return false;
    }

    @Override
    public Mono<ModelAvailability> checkAvailability() {
        return Mono.just(new ModelAvailability(PROVIDER_ID, true, MODEL_NAME, true, List.of(MODEL_NAME),
                "The deterministic extractor is configured. It quotes the retrieved evidence and "
                        + "calls no language model, so findings and code browsing behave exactly as "
                        + "they do with any other provider.",
                Instant.now()));
    }

    @Override
    public Mono<ChatCompletion> complete(ChatRequest request) {
        long started = System.nanoTime();
        String userText = request.messages().stream()
                .filter(message -> message.role() == dev.repopilot.ai.model.MessageRole.USER)
                .map(ChatMessage::content)
                .reduce((first, second) -> second)
                .orElse("");
        List<Block> blocks = parseBlocks(userText);
        if (blocks.isEmpty()) {
            return Mono.error(new AiProviderException(AiFailureKind.INVALID_RESPONSE, PROVIDER_ID,
                    "The prompt contained no numbered evidence, so there was nothing to extract. "
                            + "This provider only quotes evidence."));
        }

        String content = render(blocks);
        return Mono.just(new ChatCompletion(content, MODEL_NAME, null, null,
                java.time.Duration.ofNanos(System.nanoTime() - started), false,
                java.util.Map.of("finishReason", "extractive")));
    }

    private List<Block> parseBlocks(String userText) {
        List<Block> blocks = new ArrayList<>();
        Matcher matcher = BLOCK.matcher(userText);
        List<Integer> starts = new ArrayList<>();
        List<String[]> heads = new ArrayList<>();
        while (matcher.find()) {
            starts.add(matcher.start());
            heads.add(new String[]{matcher.group(1), matcher.group(2), matcher.group(3)});
        }
        for (int i = 0; i < heads.size(); i++) {
            int from = starts.get(i);
            int to = i + 1 < starts.size() ? starts.get(i + 1) : userText.length();
            String body = userText.substring(from, to);
            String[] head = heads.get(i);
            blocks.add(new Block(head[0], head[1], head[2].strip(),
                    field(body, "location:"), field(body, "detail:")));
        }
        return blocks;
    }

    private static String field(String body, String prefix) {
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length()).strip();
            }
        }
        return null;
    }

    private String render(List<Block> blocks) {
        StringBuilder answer = new StringBuilder();
        answer.append("Deterministic extract of ").append(blocks.size())
                .append(" item(s) of retrieved evidence. This provider composes no sentence that is ")
                .append("not already in the evidence, so the answer is a list rather than an ")
                .append("explanation.\n\n");

        ObjectNode root = json.createObjectNode();
        ArrayNode claims = root.putArray("claims");
        for (Block block : blocks) {
            answer.append("- [").append(block.key()).append("] ").append(block.headline());
            if (block.location() != null) {
                answer.append(" (").append(block.location()).append(')');
            }
            answer.append('\n');

            ObjectNode claim = claims.addObject();
            StringBuilder text = new StringBuilder(block.headline());
            if (block.detail() != null) {
                text.append(": ").append(block.detail());
            }
            if (block.location() != null) {
                text.append(" at ").append(block.location());
            }
            claim.put("text", text.toString());
            claim.put("kind", "OBSERVATION");
            claim.putArray("evidence").add(block.key());
        }

        root.put("answer", truncate(answer.toString(), MAX_ANSWER_CHARS));
        root.put("confidence", "LOW");
        ArrayNode unanswered = root.putArray("unanswered");
        unanswered.add("This provider does not reason about the evidence, so it answers no part of "
                + "the question that requires a conclusion.");
        root.put("limitations", "No language model was called. This answer quotes the retrieved "
                + "evidence verbatim: every sentence is an observation taken from an indexed row, "
                + "and nothing here is an inference or a recommendation.");

        try {
            return json.writeValueAsString(root);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AiProviderException(AiFailureKind.PROVIDER_ERROR, PROVIDER_ID,
                    "The deterministic provider could not serialise its own answer.", e);
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }

    private record Block(String key, String kind, String headline, String location, String detail) {
    }
}
