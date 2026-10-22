import type { IconName } from "@/components/icons";

export interface Destination {
  id: string;
  path: string;
  label: string;
  icon: IconName;
  section: string;
  description: string;
  /**
   * False when the capability's backend is not implemented yet. The screen is still
   * routable and explains what is coming and what already exists, rather than hiding
   * the destination — the product's roadmap is not a secret.
   */
  available: boolean;
}

/**
 * The nine destinations of the console, declared once so the nav rail, the command
 * palette, the router and page titles can never disagree about the product's shape.
 *
 * Settings is deliberately not a destination: it is an operational panel reached from
 * the command bar's gear, so the primary navigation stays about the work — exploring,
 * investigating, and reading evidence.
 */
export const DESTINATIONS: Destination[] = [
  {
    id: "overview",
    path: "/overview",
    label: "Overview",
    icon: "overview",
    section: "Universe",
    description: "Repository health, live activity and the way into the code universe.",
    available: true,
  },
  {
    id: "universe",
    path: "/universe",
    label: "Code Universe",
    icon: "graph",
    section: "Universe",
    description: "An interactive map of classes, methods, services and their relationships.",
    available: true,
  },
  {
    id: "code",
    path: "/code",
    label: "Code Explorer",
    icon: "code",
    section: "Universe",
    description: "Browse files, symbols, API mappings and indexed coverage.",
    available: true,
  },
  {
    id: "investigation",
    path: "/investigation",
    label: "AI Investigations",
    icon: "sparkles",
    section: "Investigate",
    description: "Ask questions grounded in indexed evidence, with citations checked.",
    available: true,
  },
  {
    id: "memory",
    path: "/memory",
    label: "Memory Analysis",
    icon: "memory",
    section: "Investigate",
    description: "What one heap dump recorded: objects, types and the bytes they hold.",
    available: true,
  },
  {
    id: "performance",
    path: "/performance",
    label: "Performance Lab",
    icon: "gauge",
    section: "Investigate",
    description: "JFR recordings and benchmark results — measured, not guessed.",
    available: true,
  },
  {
    id: "concurrency",
    path: "/concurrency",
    label: "Concurrency Inspector",
    icon: "threads",
    section: "Investigate",
    description: "Thread states, monitor contention and the deadlocks a dump reported.",
    available: true,
  },
  {
    id: "findings",
    path: "/findings",
    label: "Findings",
    icon: "alert",
    section: "Investigate",
    description: "Static-analysis findings by severity, category and location.",
    available: true,
  },
  {
    id: "history",
    path: "/history",
    label: "Analysis History",
    icon: "history",
    section: "Operate",
    description: "Every job and analysis run, with outcomes and provenance.",
    available: true,
  },
];

export function destinationByPath(path: string): Destination | undefined {
  return DESTINATIONS.find((destination) => destination.path === path);
}

export const SECTIONS: string[] = Array.from(new Set(DESTINATIONS.map((d) => d.section)));
