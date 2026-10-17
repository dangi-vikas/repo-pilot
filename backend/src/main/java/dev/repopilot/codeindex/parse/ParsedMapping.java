package dev.repopilot.codeindex.parse;

import java.util.List;

/**
 * A framework-level surface detected from source: an HTTP endpoint, a Kafka
 * listener, a scheduled task, and so on.
 *
 * @param mappingType     one of {@link dev.repopilot.codeindex.model.MappingType}
 * @param symbolKey       the annotated declaration
 * @param path            HTTP path (class-level prefix combined with the method mapping)
 * @param httpMethod      HTTP verb for REST mappings, null otherwise
 * @param topic           Kafka topic for listeners, or the literal topic of a detected send
 * @param consumerGroup   Kafka group id when declared
 * @param detail          bounded human-readable note, such as the raw annotation text
 * @param startLine       1-based line of the annotated declaration
 */
public record ParsedMapping(
        MappingTypeValue mappingType,
        String symbolKey,
        String path,
        String httpMethod,
        String topic,
        String consumerGroup,
        String detail,
        int startLine
) {

    /**
     * Mirror of the persisted enum, kept as a nested enum so the parser has no
     * dependency on the persistence layer. The mapping between the two is asserted by
     * a test rather than by a shared type, which keeps the parser reusable.
     */
    public enum MappingTypeValue {
        REST_ENDPOINT,
        KAFKA_LISTENER,
        KAFKA_PRODUCER,
        SCHEDULED,
        JPA_REPOSITORY,
        TRANSACTIONAL,
        FEIGN_CLIENT,
        CACHEABLE,
        CONFIGURATION_PROPERTIES,
        SPRING_BEAN,
        ASYNC,
        REACTIVE_CHAIN
    }

    /** All topic values extracted when a listener declares several. */
    public static List<String> splitTopics(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
