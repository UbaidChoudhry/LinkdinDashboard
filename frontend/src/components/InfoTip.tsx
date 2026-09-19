import { useRef, useState, type ReactNode } from "react";

interface InfoTipProps {
  /** Describes what the tip explains, for screen readers. */
  label: string;
  children: ReactNode;
}

type Placement = { x: "left" | "right"; y: "below" | "above" };

/**
 * An "i" button that reveals explanatory copy on hover. Shown on focus too, not just hover, so
 * it is reachable by keyboard — a hover-only affordance is invisible to anyone tabbing through.
 *
 * Visibility is pure CSS (:hover / :focus-within on the wrapper). The only state is the
 * placement: measured when the pointer or focus arrives, the panel opens toward the centre of
 * the viewport — leftward from an icon on the right half, upward from one in the lower half — so
 * it never runs off screen (an icon near the right edge used to push a 460px panel past it and
 * grow the page's scroll area).
 */
export function InfoTip({ label, children }: InfoTipProps) {
  const wrapperRef = useRef<HTMLSpanElement>(null);
  const [placement, setPlacement] = useState<Placement>({ x: "left", y: "below" });

  function measure() {
    const el = wrapperRef.current;
    if (!el) return;
    const rect = el.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    setPlacement({
      x: centerX > window.innerWidth / 2 ? "right" : "left",
      y: centerY > window.innerHeight / 2 ? "above" : "below",
    });
  }

  return (
    <span className="info-tip" ref={wrapperRef} onPointerEnter={measure} onFocus={measure}>
      <button type="button" className="info-tip-button" aria-label={label}>
        i
      </button>
      <span className={`info-tip-content ${placement.x} ${placement.y}`} role="tooltip">
        {children}
      </span>
    </span>
  );
}
