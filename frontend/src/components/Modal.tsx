import { useEffect, type ReactNode } from "react";
import { Icon } from "@/components/icons";

/**
 * A minimal modal dialog.
 *
 * Deliberately not a general-purpose component: it renders a title, a scrolling body
 * and an optional footer, closes on Escape and on a click outside the panel, and
 * traps nothing else. Focus management beyond autofocus is out of scope for the two
 * forms the console currently needs, and pretending otherwise would be worse than
 * doing the small honest thing.
 */
export function Modal({
  title,
  onClose,
  children,
  footer,
}: {
  title: string;
  onClose: () => void;
  children: ReactNode;
  footer?: ReactNode;
}) {
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  return (
    <div
      className="modal-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) {
          onClose();
        }
      }}
    >
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <header className="modal-header">
          <h3>{title}</h3>
          <button type="button" className="btn btn-ghost btn-icon btn-sm" onClick={onClose} aria-label="Close">
            <Icon name="close" size={16} />
          </button>
        </header>
        <div className="modal-body">{children}</div>
        {footer !== undefined ? <footer className="modal-footer">{footer}</footer> : null}
      </div>
    </div>
  );
}
