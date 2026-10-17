package dev.repopilot.codeindex.persistence;

import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.shared.persistence.AbstractCreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A framework surface detected in a revision: an HTTP endpoint, a Kafka topic, a
 * scheduled task, a Spring bean, and so on.
 *
 * <p>Mappings are stored separately from symbols because they answer a different
 * question. A symbol says "this method exists"; a mapping says "this method is
 * reachable at {@code POST /orders}". The Memory Analysis and Code Explorer screens
 * use mappings to connect source to observable behaviour.
 */
@Entity
@Table(name = "java_mapping")
public class JavaMapping extends AbstractCreatedEntity {

    @Column(name = "revision_id", nullable = false, updatable = false)
    private UUID revisionId;

    @Column(name = "source_file_id", nullable = false, updatable = false)
    private UUID sourceFileId;

    @Column(name = "symbol_id", updatable = false)
    private UUID symbolId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mapping_type", nullable = false, length = 48)
    private MappingType mappingType;

    @Column(name = "http_method", length = 16)
    private String httpMethod;

    @Column(name = "path", length = 1000)
    private String path;

    @Column(name = "topic", length = 500)
    private String topic;

    @Column(name = "consumer_group", length = 500)
    private String consumerGroup;

    @Column(name = "annotation_names", length = 2000)
    private String annotationNames;

    @Column(name = "start_line")
    private Integer startLine;

    protected JavaMapping() {
        // Required by JPA.
    }

    public JavaMapping(UUID revisionId, UUID sourceFileId, UUID symbolId, MappingType mappingType,
                       String httpMethod, String path, String topic, String consumerGroup,
                       String annotationNames, Integer startLine) {
        this.revisionId = revisionId;
        this.sourceFileId = sourceFileId;
        this.symbolId = symbolId;
        this.mappingType = mappingType;
        this.httpMethod = httpMethod;
        this.path = path;
        this.topic = topic;
        this.consumerGroup = consumerGroup;
        this.annotationNames = annotationNames;
        this.startLine = startLine;
    }

    public UUID getRevisionId() {
        return revisionId;
    }

    public UUID getSourceFileId() {
        return sourceFileId;
    }

    public UUID getSymbolId() {
        return symbolId;
    }

    public MappingType getMappingType() {
        return mappingType;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public String getTopic() {
        return topic;
    }

    public String getConsumerGroup() {
        return consumerGroup;
    }

    public String getAnnotationNames() {
        return annotationNames;
    }

    public Integer getStartLine() {
        return startLine;
    }
}
