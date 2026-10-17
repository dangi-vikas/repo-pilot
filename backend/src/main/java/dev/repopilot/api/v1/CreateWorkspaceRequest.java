package dev.repopilot.api.v1;

import jakarta.validation.constraints.Size;

/** Request body for creating a workspace. */
public record CreateWorkspaceRequest(
        @Size(max = 200, message = "name must be at most 200 characters")
        String name,
        @Size(max = 2000, message = "description must be at most 2000 characters")
        String description
) {
}
