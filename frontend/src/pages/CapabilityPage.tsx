import { DESTINATIONS } from "@/components/destinations";
import { JobTable } from "@/components/JobTable";
import { Banner, Card, Loading, PageHeader } from "@/components/ui";
import { useJobs } from "@/hooks/queries";
import type { JobType } from "@/lib/types";

interface Plan {
  exists: string[];
  planned: string[];
  jobTypes: JobType[];
  blockedBy: string;
}

const PLANS: Record<string, Plan> = {
  performance: {
    blockedBy: "No JFR ingestion or benchmark import handler exists yet, so this screen cannot show a single measurement.",
    exists: [
      "The durable job lifecycle this will run on: queued, running, cancelable, retryable, with progress and heartbeats.",
      "A per-revision symbol index with line ranges, which is what lets a hot method be attributed to a real source location instead of a stack frame name.",
    ],
    planned: [
      "Record or import a JFR file and parse it locally with the JDK's Flight Recorder API — no agent, no upload.",
      "Attribute hot methods and allocation sites to indexed symbols, and store the recording's own parameters with every number so a measurement can always be traced to its run.",
      "Import JMH results as measurements rather than estimates, with the JVM and machine recorded alongside them.",
    ],
    jobTypes: ["JFR_ANALYSIS", "BENCHMARK_IMPORT"],
  },
  memory: {
    blockedBy: "Heap dump parsing is not implemented, so there is nothing measured to display.",
    exists: [
      "Repository and revision scoping, so a dump can be tied to the exact code revision it came from.",
      "The same job and storage plumbing JFR analysis will use.",
    ],
    planned: [
      "Parse a heap dump offline and report dominator trees and retained sizes for classes that exist in the index.",
      "Correlate retained-set classes with indexed types to name the owning code.",
      "Keep the dump on disk and report only derived numbers — never upload the heap.",
    ],
    jobTypes: ["HEAP_ANALYSIS"],
  },
  concurrency: {
    blockedBy: "Thread dumps are not collected or analysed yet, so there is nothing to show.",
    exists: [
      "Indexed call edges, which is the static half of the picture: what calls what.",
      "Synchronised and locking constructs are already visible in the Code Explorer as symbols.",
    ],
    planned: [
      "Capture live thread dumps against a target process, or import existing dumps.",
      "Detect deadlock cycles and long-held monitors and report them as runtime measurements with the dump attached.",
      "Overlay lock wait evidence onto the static call graph instead of guessing at contention from source alone.",
    ],
    jobTypes: ["THREAD_DUMP_ANALYSIS"],
  },
  investigation: {
    blockedBy:
      "The AI provider abstraction and health reporting exist, but the investigation pipeline — retrieval, prompting and citation validation — is not written yet.",
    exists: [
      "A provider-agnostic AI layer (Ollama by default) with no paid API required, and honest degraded status when no model is reachable.",
      "A local symbol index precise enough to check a citation: the backend can confirm that a cited file exists and that a cited line exists in it.",
      "The evidence model this screen will use — observation, static finding, runtime measurement, AI hypothesis — already labels every category in the UI.",
    ],
    planned: [
      "Ask a question against a revision and receive an answer that cites only indexed symbols and files.",
      "Validate every citation server-side before the answer is returned; drop or flag anything that does not resolve.",
      "Label the answer as a hypothesis, and keep the retrieved evidence next to it so the reasoning can be audited.",
    ],
    jobTypes: ["INVESTIGATION"],
  },
};

/**
 * An honest placeholder for a destination whose backend does not exist yet.
 *
 * The page does not simulate the feature. It says what is missing, what already exists
 * underneath it, and what is planned — and it shows the real job rows (if any) of the
 * types that will back it, so the screen is wired to the same durable record every
 * other screen uses.
 */
export function CapabilityPage({ destinationId }: { destinationId: string }) {
  const destination = DESTINATIONS.find((candidate) => candidate.id === destinationId);
  const plan = PLANS[destinationId];
  const jobs = useJobs(undefined);

  if (destination === undefined || plan === undefined) {
    return <PageHeader title="Not found" description="This capability is not declared." />;
  }

  const related = (jobs.data?.items ?? []).filter((job) => plan.jobTypes.includes(job.jobType));

  return (
    <>
      <PageHeader title={destination.label} description={destination.description} />

      <div style={{ marginBottom: "var(--space-5)" }}>
        <Banner tone="warning" title="Not available yet">
          {plan.blockedBy}
        </Banner>
      </div>

      <div className="grid grid-2" style={{ marginBottom: "var(--space-5)" }}>
        <Card title="What already exists">
          <ul className="capability-list">
            {plan.exists.map((item) => (
              <li key={item}>
                <span className="bullet" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </Card>
        <Card title="What is planned">
          <ul className="capability-list">
            {plan.planned.map((item) => (
              <li key={item} className="planned">
                <span className="bullet" />
                <span>{item}</span>
              </li>
            ))}
          </ul>
        </Card>
      </div>

      <Card
        title="Runs of the related job types"
        actions={<span className="rp-faint" style={{ fontSize: "var(--text-xs)" }}>{plan.jobTypes.join(", ")}</span>}
        flush
      >
        {jobs.isLoading ? (
          <Loading label="Loading jobs" />
        ) : (
          <JobTable
            jobs={related}
            emptyMessage="No jobs of this type have ever been submitted, which is expected while the handler is unimplemented."
          />
        )}
      </Card>
    </>
  );
}
