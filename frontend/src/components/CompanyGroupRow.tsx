import { groupLocations, groupNewestPostedAt, groupTopSalary, type CompanyGroup } from "../utils/group";
import { absoluteTime, relativeTime } from "../utils/format";

interface CompanyGroupRowProps {
  group: CompanyGroup;
  expanded: boolean;
  onToggle: (key: string) => void;
}

function formatUsd(n: number): string {
  return `$${Math.round(n).toLocaleString()}`;
}

/**
 * The collapsed summary row for a company with more than one job. Only rendered when grouping
 * is on and the group has 2+ jobs — a company with a single job is shown as a plain JobRow, so
 * grouping never adds a click for the common case.
 *
 * The summary deliberately surfaces the distinct-location count: one company posting many jobs
 * across many locations is the same relay/spam signal the company-volume report looks for.
 */
export function CompanyGroupRow({ group, expanded, onToggle }: CompanyGroupRowProps) {
  const count = group.jobs.length;
  const topSalary = groupTopSalary(group);
  const newest = groupNewestPostedAt(group);
  const locations = groupLocations(group);

  return (
    <tr className={expanded ? "company-group expanded" : "company-group"}>
      <td className="col-title">
        <button
          type="button"
          className="group-toggle"
          aria-expanded={expanded}
          onClick={() => onToggle(group.key)}
        >
          <span className="group-caret" aria-hidden="true">
            {expanded ? "▾" : "▸"}
          </span>
          {count} jobs
        </button>
      </td>
      <td className="group-company">{group.company}</td>
      <td>
        {locations.length === 0
          ? "–"
          : locations.length === 1
            ? locations[0]
            : `${locations.length} locations`}
      </td>
      <td title={absoluteTime(newest)}>{relativeTime(newest)}</td>
      <td className="col-salary">{topSalary == null ? "–" : `up to ${formatUsd(topSalary)}`}</td>
      <td className="col-actions" />
    </tr>
  );
}
