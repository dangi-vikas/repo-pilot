package dev.repopilot.api.v1;

import java.util.List;

/**
 * Aggregate counters for the overview screen.
 *
 * <p>Each count is a live database count. The AI block reports availability truthfully
 * rather than assuming a provider is present; an installation with no model runtime
 * still has a fully usable overview.
 */
public record OverviewView(
        long workspaceCount,
        long repositoryCount,
        long activeJobCount,
        long queuedJobCount,
        long runningJobCount,
        long succeededJobCount,
        long failedJobCount,
        long cancelledJobCount,
        AiStatus ai
) {

    /** Availability of the model provider, mirrored from the registry's probe. */
    public record AiStatus(
            boolean enabled,
            String providerId,
            boolean reachable,
            boolean usable,
            boolean embeddingsEnabled,
            String message,
            List<String> availableProviderIds
    ) {
    }
}
