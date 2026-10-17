package dev.repopilot.codeindex.model;

/**
 * The kinds of framework surface RepoPilot detects from source.
 *
 * <p>This is the persisted form. {@link dev.repopilot.codeindex.parse.ParsedMapping}
 * carries a mirrored enum so the parser has no dependency on the persistence layer;
 * a test asserts that the two sets stay identical, which is what keeps the parser
 * reusable without letting the two representations drift.
 */
public enum MappingType {
    /** An HTTP endpoint exposed by a controller. */
    REST_ENDPOINT,
    /** A Kafka consumer (for example {@code @KafkaListener}). */
    KAFKA_LISTENER,
    /** A Kafka producer call inferred from a {@code send(...)} invocation. */
    KAFKA_PRODUCER,
    /** A scheduled method (for example {@code @Scheduled}). */
    SCHEDULED,
    /** A Spring Data repository interface. */
    JPA_REPOSITORY,
    /** A transactional method ({@code @Transactional}). */
    TRANSACTIONAL,
    /** A declarative HTTP client ({@code @FeignClient}). */
    FEIGN_CLIENT,
    /** A cache-annotated method ({@code @Cacheable} and friends). */
    CACHEABLE,
    /** A type bound to externalised configuration ({@code @ConfigurationProperties}). */
    CONFIGURATION_PROPERTIES,
    /** A Spring-managed bean (stereotype annotation). */
    SPRING_BEAN,
    /** An asynchronous method ({@code @Async}). */
    ASYNC,
    /** A reactive pipeline marker. */
    REACTIVE_CHAIN
}
