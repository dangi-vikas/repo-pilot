import { useLocation } from "react-router-dom";
import { destinationByPath } from "@/components/destinations";
import { Icon } from "@/components/icons";
import { RepositoryPicker } from "@/components/RepositoryPicker";

/**
 * The top bar: where you are, what you are looking at, and the way to jump anywhere.
 *
 * It intentionally holds no navigation of its own — the rail owns destinations and the
 * palette owns shortcuts — so there is one obvious place to look for each thing.
 */
export function TopBar({ onOpenPalette }: { onOpenPalette: () => void }) {
  const location = useLocation();
  const destination = destinationByPath(location.pathname);

  return (
    <header className="topbar">
      <div className="topbar-left">
        <div className="crumbs">
          {destination !== undefined ? (
            <>
              <span>{destination.section}</span>
              <span className="sep">/</span>
            </>
          ) : null}
          <strong>{destination?.label ?? "RepoPilot"}</strong>
        </div>
      </div>
      <div className="topbar-right">
        <RepositoryPicker />
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={onOpenPalette}
          aria-label="Open command palette"
        >
          <Icon name="command" size={15} />
          <span className="kbd">Ctrl K</span>
        </button>
      </div>
    </header>
  );
}
