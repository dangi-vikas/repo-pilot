package dev.repopilot.api.v1;

import jakarta.validation.constraints.Size;

/**
 * Request body for submitting an indexing job.
 *
 * @param idempotencyKey optional de-duplication key. Submitting twice with the same key
 *                       returns the original job instead of starting a second scan.
 */
public record SubmitIndexRequest(
        @Size(max = 200, message = "idempotencyKey must be at most 200 characters")
        String idempotencyKey
) {
}
