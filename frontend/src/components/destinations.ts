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
 */
export const DESTINATIONS: Destination[] = [
  {
    id: "overview",
    path: "/overview",
    label: "Overview",
    icon: "overview",
    section: "Intelligence",
    description: "Repository inventory, job activity and system status at a glance.",
    available: true,
  },
  {
    id: "code",
    path: "/code",
    label: "Code Explorer",
    icon: "code",
    section: "Intelligence",
    description: "Browse files, symbols, API mappings and indexed coverage.",
    available: true,
  },
  {
    id: "graph",
    path: "/graph",
    label: "Dependency Graph",
    icon: "graph",
    section: "Intelligence",
    description: "Type and reference relationships between symbols.",
    available: true,
  },
  {
    id: "performance",
    path: "/performance",
    label: "Performance Lab",
    icon: "gauge",
    section: "Performance",
    description: "JFR recordings and benchmark results, measured — not guessed.",
    available: true,
  },
  {
    id: "memory",
    path: "/memory",
    label: "Memory Analysis",
    icon: "memory",
    section: "Performance",
    description: "What one heap dump recorded: objects, types and the bytes they hold.",
    available: true,
  },
  {
    id: "concurrency",
    path: "/concurrency",
    label: "Concurrency Inspector",
    icon: "threads",
    section: "Performance",
    description: "Thread states, monitor contention and the deadlocks a dump reported.",
    available: true,
  },
  {
    id: "investigation",
    path: "/investigation",
    label: "AI Investigation",
    icon: "sparkles",
    section: "Assist",
    description: "Ask questions grounded in indexed evidence, with citations checked.",
    available: false,
  },
  {
    id: "history",
    path: "/history",
    label: "Analysis History",
    icon: "history",
    section: "Operations",
    description: "Every job and analysis run, with outcomes and provenance.",
    available: true,
  },
  {
    id: "settings",
    path: "/settings",
    label: "Settings",
    icon: "settings",
    section: "Operations",
    description: "Connection, workspace roots, AI provider and privacy controls.",
    available: true,
  },
];

export function destinationByPath(path: string): Destination | undefined {
  return DESTINATIONS.find((destination) => destination.path === path);
}

export const SECTIONS: string[] = Array.from(new Set(DESTINATIONS.map((d) => d.section)));
