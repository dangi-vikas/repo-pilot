package dev.repopilot.api.v1;

import jakarta.validation.constraints.Size;

/**
 * Request body for registering a repository.
 *
 * @param name optional display name; derived from the directory when absent
 * @param path a directory under a permitted workspace root. Absolute, or relative to
 *             the primary root. Never echoed back to the client.
 */
public record RegisterRepositoryRequest(
        @Size(max = 300, message = "name must be at most 300 characters")
        String name,
        String path
) {
}
