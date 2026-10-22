import type { SVGProps } from "react";

/**
 * A small, dependency-free icon set.
 *
 * Inline SVG keeps the bundle tiny and avoids an icon-library dependency for what is
 * ultimately two dozen glyphs. Every icon is drawn on a 24x24 grid with a 1.6 stroke
 * so they read consistently at the sizes the UI uses (14–20px).
 */

export type IconName =
  | "overview"
  | "code"
  | "sparkles"
  | "memory"
  | "gauge"
  | "threads"
  | "graph"
  | "history"
  | "settings"
  | "search"
  | "command"
  | "refresh"
  | "play"
  | "stop"
  | "plus"
  | "folder"
  | "branch"
  | "file"
  | "alert"
  | "check"
  | "close"
  | "chevron-right"
  | "chevron-down"
  | "chevron-up"
  | "external"
  | "plug"
  | "database"
  | "upload"
  | "sun"
  | "moon"
  | "panel-left"
  | "info"
  | "bell"
  | "zap"
  | "layers"
  | "terminal"
  | "target"
  | "eye";

const PATHS: Record<IconName, string> = {
  overview: "M4 13h7V4H4v9Zm0 7h7v-4H4v4Zm9 0h7V11h-7v9Zm0-16v4h7V4h-7Z",
  code: "M9 18l-6-6 6-6M15 6l6 6-6 6",
  sparkles:
    "M12 3l1.9 4.6L18.5 9.5l-4.6 1.9L12 16l-1.9-4.6L5.5 9.5l4.6-1.9L12 3ZM18.5 15.5l.9 2.1 2.1.9-2.1.9-.9 2.1-.9-2.1-2.1-.9 2.1-.9.9-2.1Z",
  memory: "M6 4h12v16H6zM9 8h6M9 12h6M9 16h3M3 8h3M3 16h3M18 8h3M18 16h3",
  gauge: "M12 14l4-4M4.5 19a9 9 0 1 1 15 0",
  threads: "M4 6h16M4 12h10M4 18h13M18 12l3 0M8 6v0",
  graph:
    "M7 7.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM17 21.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5ZM6.5 7.6l9 10.8M19.5 12v4.3M12 9h5a2.5 2.5 0 0 0 2.5-2.5v0",
  history: "M12 8v4l3 2M3.5 12a8.5 8.5 0 1 0 2.6-6.1M3.5 4.5V9H8",
  settings:
    "M12 15.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-2.9 1.2V21a2 2 0 1 1-4 0v-.1A1.7 1.7 0 0 0 7 19.2l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.7 1.7 0 0 0 3 13.5H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.8 7l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1A1.7 1.7 0 0 0 10.5 3V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 2.9 1.2l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0 1.2 2.9H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z",
  search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16ZM21 21l-4.3-4.3",
  command:
    "M9 6a3 3 0 1 0-3 3h12a3 3 0 1 0-3-3v12a3 3 0 1 0 3-3H6a3 3 0 1 0 3 3V6Z",
  refresh: "M20 11a8 8 0 0 0-14.3-4.7L4 8M4 13a8 8 0 0 0 14.3 4.7L20 16M4 4v4h4M20 20v-4h-4",
  play: "M7 5l12 7-12 7V5Z",
  stop: "M7 7h10v10H7z",
  plus: "M12 5v14M5 12h14",
  folder: "M4 7a2 2 0 0 1 2-2h3.5l2 2H18a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z",
  branch: "M6 4v10M6 18a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM18 4a2 2 0 1 0 0 4 2 2 0 0 0 0-4ZM18 8v3a4 4 0 0 1-4 4H6",
  file: "M7 3h7l5 5v13H7zM14 3v5h5",
  alert: "M12 8v5M12 16.5v.5M10.3 4l-8 14h19.4l-8-14a1.6 1.6 0 0 0-2.8 0Z",
  check: "M5 13l4 4L19 7",
  close: "M6 6l12 12M18 6L6 18",
  "chevron-right": "M9 6l6 6-6 6",
  "chevron-down": "M6 9l6 6 6-6",
  "chevron-up": "M6 15l6-6 6 6",
  external: "M14 5h5v5M19 5l-8 8M18 14v4a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4",
  plug: "M9 3v6M15 3v6M7 9h10v3a5 5 0 0 1-10 0V9ZM12 17v4",
  database: "M12 8c4.4 0 8-1.1 8-2.5S16.4 3 12 3 4 4.1 4 5.5 7.6 8 12 8ZM4 5.5v13C4 19.9 7.6 21 12 21s8-1.1 8-2.5v-13M4 12c0 1.4 3.6 2.5 8 2.5s8-1.1 8-2.5",
  upload: "M12 16V4M7 9l5-5 5 5M5 20h14",
  sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10ZM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
  moon: "M20 14.5A8.5 8.5 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5Z",
  "panel-left": "M4 5h16v14H4zM9.5 5v14",
  info: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 11v5M12 7.5v.5",
  bell: "M18 9a6 6 0 1 0-12 0c0 5-2 6-2 6h16s-2-1-2-6M10 20h4",
  zap: "M13 2L4 14h6l-1 8 9-12h-6l1-8Z",
  layers: "M12 3l9 5-9 5-9-5 9-5ZM3 13l9 5 9-5M3 17l9 5 9-5",
  terminal: "M4 5h16v14H4zM7 9l3 3-3 3M13 15h4",
  target: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM12 3v3M12 18v3M3 12h3M18 12h3",
  eye: "M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12ZM12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6Z",
};

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
}

export function Icon({ name, size = 18, ...rest }: IconProps) {
  const path = PATHS[name];
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      {...rest}
    >
      <path d={path} />
    </svg>
  );
}
