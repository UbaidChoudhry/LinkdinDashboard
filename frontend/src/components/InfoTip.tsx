import type { ReactNode } from "react";

interface InfoTipProps {
  /** Describes what the tip explains, for screen readers and the button's own tooltip. */
  label: string;
  children: ReactNode;
}

/**
 * An "i" button that reveals explanatory copy on hover. Shown on focus too, not just hover, so
 * it is reachable by keyboard — a hover-only affordance is invisible to anyone tabbing through.
 *
 * Visibility is pure CSS (:hover / :focus-within on the wrapper); there is no open/closed state
 * to keep in sync.
 */
export function InfoTip({ label, children }: InfoTipProps) {
  return (
    <span className="info-tip">
      <button type="button" className="info-tip-button" aria-label={label} title={label}>
        i
      </button>
      <span className="info-tip-content" role="tooltip">
        {children}
      </span>
    </span>
  );
}
