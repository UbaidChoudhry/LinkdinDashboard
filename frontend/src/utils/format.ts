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
