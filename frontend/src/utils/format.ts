/** Formats an ISO-8601 timestamp as a short relative time ("3 hours ago"). */
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) {
    return "unknown time";
  }
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) {
    return "unknown time";
  }
  const now = Date.now();
  const diffMs = now - then;
  const future = diffMs < 0;
  const diffSec = Math.round(Math.abs(diffMs) / 1000);

  const units: [string, number][] = [
    ["year", 31536000],
    ["month", 2592000],
    ["week", 604800],
    ["day", 86400],
    ["hour", 3600],
    ["minute", 60],
    ["second", 1],
  ];

  for (const [name, secs] of units) {
    const value = Math.floor(diffSec / secs);
    if (value >= 1) {
      const plural = value === 1 ? name : `${name}s`;
      return future ? `in ${value} ${plural}` : `${value} ${plural} ago`;
    }
  }
  return "just now";
}

/**
 * Loosely normalizes a company name for comparison: lowercase, strip punctuation
 * and whitespace, and drop a leading "the" plus trailing legal suffixes
 * (inc, llc, corp, co, ltd, limited, lp, llp, plc).
 */
function normalizeCompany(name: string): string {
  let s = name.toLowerCase().replace(/[^a-z0-9\s]/g, " ").replace(/\s+/g, " ").trim();
  s = s.replace(/^the\s+/, "");
  const suffixes = ["incorporated", "inc", "llc", "corporation", "corp", "co", "ltd", "limited", "lp", "llp", "plc"];
  let changed = true;
  while (changed) {
    changed = false;
    for (const suf of suffixes) {
      if (s.endsWith(" " + suf)) {
        s = s.slice(0, -(suf.length + 1)).trim();
        changed = true;
      }
    }
  }
  return s.replace(/\s+/g, "");
}

/**
 * True when two company names refer to the same company under a loose
 * normalization (see normalizeCompany). Used to tell an exact LCA-employer match
 * from a fuzzy one worth flagging in the UI.
 */
export function looselySameCompany(a: string | null | undefined, b: string | null | undefined): boolean {
  if (!a || !b) return false;
  const na = normalizeCompany(a);
  const nb = normalizeCompany(b);
  if (!na || !nb) return false;
  // Equality only - deliberately NOT a prefix test. The backend matches LCA employers by
  // prefix ("amazon" -> "amazon com services"), so a prefix comparison here would call every
  // one of those an exact match and the fuzzy marker would never appear. It would also call
  // "Meta" and "Metabase" the same company, which is the trap HANDOFF.md keeps warning about.
  // Whitespace is already stripped by normalizeCompany, so "J P Morgan" == "JPMorgan".
  return na === nb;
}

/** Formats a byte count as a human-readable size ("4.2 MB"). */
export function formatBytes(bytes: number): string {
  if (bytes < 1024) {
    return `${bytes} B`;
  }
  const units = ["KB", "MB", "GB", "TB"];
  let value = bytes / 1024;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(1)} ${units[unitIndex]}`;
}

/** Formats an ISO-8601 timestamp as a readable absolute date/time, for tooltips. */
export function absoluteTime(iso: string | null | undefined): string {
  if (!iso) {
    return "unknown";
  }
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return "unknown";
  }
  return d.toLocaleString();
}
