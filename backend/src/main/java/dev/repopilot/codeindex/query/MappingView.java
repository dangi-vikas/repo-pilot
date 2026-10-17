package dev.repopilot.codeindex.query;

import dev.repopilot.codeindex.model.MappingType;
import dev.repopilot.codeindex.persistence.JavaMapping;

import java.util.UUID;

/** A framework surface as returned by the code explorer. */
public record MappingView(
        UUID id,
        MappingType mappingType,
        String httpMethod,
        String path,
        String topic,
        String consumerGroup,
        String detail,
        UUID symbolId,
        Integer startLine
) {

    public static MappingView from(JavaMapping mapping) {
        return new MappingView(
                mapping.getId(),
                mapping.getMappingType(),
                mapping.getHttpMethod(),
                mapping.getPath(),
                mapping.getTopic(),
                mapping.getConsumerGroup(),
                mapping.getAnnotationNames(),
                mapping.getSymbolId(),
                mapping.getStartLine());
    }
}
