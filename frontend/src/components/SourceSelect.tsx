import { useEffect, useRef, useState } from "react";
import type { JobSourceName } from "../types/api";
import { ATS_SOURCES, SOURCE_LABELS } from "../types/api";

interface SourceSelectProps {
  value: JobSourceName[];
  onChange: (next: JobSourceName[]) => void;
  disabled?: boolean;
}

const ALL: JobSourceName[] = ["linkedin", ...ATS_SOURCES];

/**
 * Multi-select dropdown for a run's job sources.
 *
 * LinkedIn is mutually exclusive with the ATS boards, and the exclusivity is enforced here at
 * the point of selection rather than being reported as a validation error afterwards: picking
 * LinkedIn clears the others and vice versa, so an invalid combination can never be assembled.
 * The reason is that LinkedIn's guest search returns no job description, leaving the AI match
 * step with nothing to compare a resume against.
 */
export function SourceSelect({ value, onChange, disabled }: SourceSelectProps) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Close on an outside click or Escape - a dropdown left open over the rest of the form is
  // worse than one that needs a second click to reopen.
  useEffect(() => {
    if (!open) return;
    function onDocPointerDown(e: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("mousedown", onDocPointerDown);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onDocPointerDown);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  function toggle(source: JobSourceName) {
    const selected = value.includes(source);
    if (selected) {
      // Never allow an empty selection - a run with no source would do nothing.
      const next = value.filter((s) => s !== source);
      onChange(next.length === 0 ? [source] : next);
      return;
    }
    if (source === "linkedin") {
      onChange(["linkedin"]);
      return;
    }
    onChange([...value.filter((s) => s !== "linkedin"), source]);
  }

  const summary =
    value.length === 0
      ? "None"
      : value.length === 1
        ? SOURCE_LABELS[value[0]]
        : `${value.length} sources`;

  return (
    <div className="source-select" ref={containerRef}>
      <button
        type="button"
        className="source-select-trigger"
        onClick={() => setOpen((o) => !o)}
        disabled={disabled}
        aria-expanded={open}
        aria-haspopup="listbox"
      >
        {summary}
        <span className="source-select-caret" aria-hidden="true">
          ▾
        </span>
      </button>

      {open && (
        <div className="source-select-menu" role="listbox" aria-multiselectable="true">
          {ALL.map((s) => {
            const checked = value.includes(s);
            const blocked =
              (s === "linkedin" && value.some((v) => v !== "linkedin")) ||
              (s !== "linkedin" && value.includes("linkedin"));
            return (
              <label key={s} className={blocked ? "source-option blocked" : "source-option"}>
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(s)}
                  disabled={disabled}
                />
                <span>{SOURCE_LABELS[s]}</span>
                {s === "linkedin" && <span className="source-option-note">no AI scan</span>}
              </label>
            );
          })}
          <p className="source-select-hint">
            LinkedIn can't be combined with the ATS boards — its guest search returns no job
            description, so there is nothing for the AI scan to read.
          </p>
        </div>
      )}
    </div>
  );
}
