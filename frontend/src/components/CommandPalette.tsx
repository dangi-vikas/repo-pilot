import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from "react";
import { DESTINATIONS } from "@/components/destinations";
import { Icon, type IconName } from "@/components/icons";
import type { Theme } from "@/hooks/theme";

interface Command {
  id: string;
  label: string;
  group: string;
  hint: string | null;
  icon: IconName;
  keywords: string;
  run: () => void;
}

/**
 * A keyboard-first jump-anywhere palette.
 *
 * It lists destinations and a handful of shell actions, and nothing else: a palette
 * that guesses at content (symbols, files, jobs) would need a search backend the
 * console does not have yet, and inventing one here would be dishonest about what is
 * searchable.
 */
export function CommandPalette({
  onClose,
  onNavigate,
  onToggleTheme,
  onOpenCreateWorkspace,
  onOpenRegisterRepository,
  theme,
}: {
  onClose: () => void;
  onNavigate: (path: string) => void;
  onToggleTheme: () => void;
  onOpenCreateWorkspace: () => void;
  onOpenRegisterRepository: () => void;
  theme: Theme;
}) {
  const [query, setQuery] = useState("");
  const [index, setIndex] = useState(0);
  const listRef = useRef<HTMLDivElement | null>(null);

  const commands = useMemo<Command[]>(() => {
    const destinations: Command[] = DESTINATIONS.map((destination) => ({
      id: `go-${destination.id}`,
      label: destination.label,
      group: "Go to",
      hint: destination.available ? destination.section : "not yet available",
      icon: destination.icon,
      keywords: `${destination.section} ${destination.description}`,
      run: () => onNavigate(destination.path),
    }));
    const actions: Command[] = [
      {
        id: "go-settings",
        label: "Settings",
        group: "Go to",
        hint: "operator panel",
        icon: "settings",
        keywords: "configuration provider token security preferences",
        run: () => onNavigate("/settings"),
      },
      {
        id: "action-create-workspace",
        label: "Create workspace",
        group: "Actions",
        hint: null,
        icon: "plus",
        keywords: "new add workspace",
        run: onOpenCreateWorkspace,
      },
      {
        id: "action-register-repository",
        label: "Register repository",
        group: "Actions",
        hint: null,
        icon: "folder",
        keywords: "new add repository index path",
        run: onOpenRegisterRepository,
      },
      {
        id: "action-toggle-theme",
        label: theme === "dark" ? "Switch to light theme" : "Switch to dark theme",
        group: "Actions",
        hint: null,
        icon: theme === "dark" ? "sun" : "moon",
        keywords: "appearance dark light theme",
        run: onToggleTheme,
      },
    ];
    return [...actions, ...destinations];
  }, [
    onNavigate,
    onOpenCreateWorkspace,
    onOpenRegisterRepository,
    onToggleTheme,
    theme,
  ]);

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (needle.length === 0) {
      return commands;
    }
    return commands.filter((command) =>
      `${command.label} ${command.group} ${command.keywords}`.toLowerCase().includes(needle),
    );
  }, [commands, query]);

  useEffect(() => {
    setIndex(0);
  }, [query]);

  useEffect(() => {
    const active = filtered[Math.min(index, Math.max(0, filtered.length - 1))];
    if (active === undefined) {
      return;
    }
    const node = listRef.current?.querySelector<HTMLElement>(`[data-command-id="${active.id}"]`);
    node?.scrollIntoView({ block: "nearest" });
  }, [filtered, index]);

  function run(command: Command) {
    command.run();
    onClose();
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === "ArrowDown") {
      event.preventDefault();
      setIndex((current) => Math.min(current + 1, Math.max(0, filtered.length - 1)));
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      setIndex((current) => Math.max(0, current - 1));
    } else if (event.key === "Enter") {
      event.preventDefault();
      const active = filtered[Math.min(index, Math.max(0, filtered.length - 1))];
      if (active !== undefined) {
        run(active);
      }
    } else if (event.key === "Escape") {
      event.preventDefault();
      onClose();
    }
  }

  const groups = Array.from(new Set(filtered.map((command) => command.group)));

  return (
    <div
      className="cmdk-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="cmdk" role="dialog" aria-modal="true" aria-label="Command palette">
        <div className="cmdk-input">
          <Icon name="search" size={16} />
          <input
            autoFocus
            value={query}
            placeholder="Jump to a destination, or run an action…"
            aria-label="Command palette search"
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <span className="kbd">Esc</span>
        </div>
        <div className="cmdk-list" ref={listRef} role="listbox">
          {filtered.length === 0 ? (
            <div className="cmdk-empty">No matches</div>
          ) : (
            groups.map((group) => (
              <div key={group}>
                <div className="cmdk-group">{group}</div>
                {filtered
                  .filter((command) => command.group === group)
                  .map((command) => {
                    const active = filtered[Math.min(index, filtered.length - 1)]?.id === command.id;
                    return (
                      <div
                        key={command.id}
                        data-command-id={command.id}
                        role="option"
                        aria-selected={active}
                        className="cmdk-item"
                        onMouseEnter={() => setIndex(filtered.indexOf(command))}
                        onMouseDown={(event) => {
                          event.preventDefault();
                          run(command);
                        }}
                      >
                        <Icon name={command.icon} size={16} />
                        <span>{command.label}</span>
                        {command.hint !== null ? <span className="cmdk-item-hint">{command.hint}</span> : null}
                      </div>
                    );
                  })}
              </div>
            ))
          )}
        </div>
      </div>
    </div>
  );
}
