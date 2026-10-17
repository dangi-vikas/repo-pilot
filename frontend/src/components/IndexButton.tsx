import { useState } from "react";
import { Icon } from "@/components/icons";
import { useStartIndexing } from "@/hooks/queries";
import { ApiError } from "@/lib/api";
import type { Uuid } from "@/lib/types";

/**
 * Queues an indexing job for one repository.
 *
 * The conflict case ("already indexing") is expected and is shown as information, not
 * as an error: the operator asked for something that is already happening.
 */
export function IndexButton({
  repositoryId,
  disabled = false,
}: {
  repositoryId: Uuid;
  disabled?: boolean;
}) {
  const indexing = useStartIndexing(repositoryId);
  const [message, setMessage] = useState<string | null>(null);

  async function start() {
    setMessage(null);
    try {
      await indexing.mutateAsync();
      setMessage("Indexing job queued.");
    } catch (cause) {
      setMessage(cause instanceof ApiError ? cause.message : "Could not start indexing.");
    }
  }

  return (
    <>
      <button
        type="button"
        className="btn btn-sm"
        disabled={disabled || indexing.isPending}
        onClick={() => void start()}
        title="Queue an indexing job for this repository"
      >
        <Icon name="play" size={14} />
        {indexing.isPending ? "Submitting…" : "Index"}
      </button>
      {message !== null ? (
        <span className="rp-faint" style={{ fontSize: "var(--text-xs)", marginLeft: "var(--space-2)" }}>
          {message}
        </span>
      ) : null}
    </>
  );
}
