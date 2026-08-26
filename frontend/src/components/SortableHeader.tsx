import type { SortColumn, SortState } from "../utils/sort";

interface SortableHeaderProps {
  column: SortColumn;
  label: string;
  sort: SortState;
  onSort: (column: SortColumn) => void;
}

/**
 * A <th> that's also a sort control: click (or Enter/Space) to sort by this column, click again
 * to reverse direction. `aria-sort` is set on the <th> itself so screen readers get the standard
 * table-sort announcement, and the visible arrow only appears on the active column.
 */
export function SortableHeader({ column, label, sort, onSort }: SortableHeaderProps) {
  const active = sort.column === column;
  const ariaSort = active ? (sort.direction === "asc" ? "ascending" : "descending") : "none";

  return (
    <th aria-sort={ariaSort} className={active ? "sortable active" : "sortable"}>
      <button
        type="button"
        className="sort-button"
        onClick={() => onSort(column)}
        title={active ? `Sorted ${sort.direction === "asc" ? "ascending" : "descending"}` : `Sort by ${label}`}
      >
        {label}
        <span className="sort-indicator" aria-hidden="true">
          {active ? (sort.direction === "asc" ? "▲" : "▼") : ""}
        </span>
      </button>
    </th>
  );
}

/** Non-interactive header cell for columns that can't be sorted (e.g. Actions). */
export function PlainHeader({ label }: { label: string }) {
  return <th>{label}</th>;
}
