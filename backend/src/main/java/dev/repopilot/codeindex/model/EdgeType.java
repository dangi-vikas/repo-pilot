package dev.repopilot.codeindex.model;

/** The kinds of relationship RepoPilot records between symbols. */
public enum EdgeType {
    /** Class {@code A extends B}. */
    EXTENDS,
    /** Class {@code A implements B}. */
    IMPLEMENTS,
    /** A field, parameter or return type reference. */
    TYPE_REFERENCE,
    /** An object instantiation ({@code new B()}). */
    INSTANTIATES,
    /** A method invocation. */
    CALLS,
    /** An annotation usage. */
    ANNOTATION,
    /** A thrown exception type. */
    THROWS,
    /** Injects a dependency (constructor, field or setter injection detected from Spring annotations). */
    INJECTS,
    /** Sends to a Kafka topic. Inferred from a producer call, never guaranteed. */
    KAFKA_PUBLISH,
    /** Consumes from a Kafka topic, detected from a listener annotation. */
    KAFKA_CONSUME,
    /** Exposes an HTTP endpoint, detected from a mapping annotation. */
    REST_ENDPOINT
}
