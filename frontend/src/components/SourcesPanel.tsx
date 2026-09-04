import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import {
  ApiError,
  addSource,
  deleteSource,
  getSourceSummary,
  listSources,
  setSourceEnabled,
} from "../api/client";
import type { AtsCompanyResponse, JobSourceName, SourceSummaryResponse } from "../types/api";
import { ATS_SOURCES, SOURCE_LABELS } from "../types/api";
import { relativeTime } from "../utils/format";

const PAGE_SIZE = 50;

/**
 * The ATS company catalog. The imported catalog can hold tens of thousands of companies, so this
 * is deliberately a paginated, searchable browser rather than a plain list - and a run only ever
 * visits the companies enabled here.
 */
export function SourcesPanel() {
  const [rows, setRows] = useState<AtsCompanyResponse[] | null>(null);
  const [total, setTotal] = useState(0);
  const [summary, setSummary] = useState<SourceSummaryResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const [atsFilter, setAtsFilter] = useState<JobSourceName | "">("");
  const [search, setSearch] = useState("");
  const [enabledOnly, setEnabledOnly] = useState(false);
  const [offset, setOffset] = useState(0);

  // The "add a company by hand" form - the override for boards the catalog doesn't carry, and
  // for Workday tenants whose site id can't be auto-discovered from robots.txt.
  const [newAts, setNewAts] = useState<JobSourceName>("greenhouse");
  const [newSlug, setNewSlug] = useState("");
  const [newCompany, setNewCompany] = useState("");
  const [newCareersUrl, setNewCareersUrl] = useState("");
  const [addBusy, setAddBusy] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const page = await listSources({
        ats: atsFilter,
        search,
        enabledOnly,
        limit: PAGE_SIZE,
        offset,
      });
      setRows(page.items);
      setTotal(page.total);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load sources.");
      setRows([]);
    }
  }, [atsFilter, search, enabledOnly, offset]);

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await getSourceSummary());
    } catch {
      // The summary is decoration; a failure here must not blank the table below it.
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    loadSummary();
  }, [loadSummary]);

  // Any filter change invalidates the current page offset - staying on page 4 of a new,
  // shorter result set shows an empty table that looks like "no results".
  useEffect(() => {
    setOffset(0);
  }, [atsFilter, search, enabledOnly]);

  async function handleToggle(row: AtsCompanyResponse) {
    setBusyId(row.id);
    setError(null);
    try {
      const updated = await setSourceEnabled(row.id, !row.enabled);
      setRows((current) => current?.map((r) => (r.id === updated.id ? updated : r)) ?? null);
      loadSummary();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to update that company.");
    } finally {
      setBusyId(null);
    }
  }

  async function handleDelete(row: AtsCompanyResponse) {
    setBusyId(row.id);
    setError(null);
    try {
      await deleteSource(row.id);
      await load();
      loadSummary();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to delete that company.");
    } finally {
      setBusyId(null);
    }
  }

  async function handleAdd(e: FormEvent) {
    e.preventDefault();
    setAddBusy(true);
    setError(null);
    try {
      await addSource({
        ats: newAts,
        slug: newSlug.trim() || undefined,
        company: newCompany.trim() || undefined,
        careersUrl: newCareersUrl.trim() || undefined,
      });
      setNewSlug("");
      setNewCompany("");
      setNewCareersUrl("");
      await load();
      loadSummary();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to add that company.");
    } finally {
      setAddBusy(false);
    }
  }

  const enabledCount = summary
    ? Object.entries(summary.byStatus).reduce((acc, [, n]) => acc + n, 0)
    : null;

  return (
    <section className="sources-panel">
      <h2>Job sources</h2>
      <p className="panel-hint">
        ATS boards are per-company — there is no global search — so a run visits the companies
        enabled here. Importing a catalog with <code>./import-slugs.sh</code> adds companies
        <strong> disabled</strong>; enable the ones you want. A board that 404s repeatedly is
        retired automatically and shown as <em>dead</em>.
      </p>

      {summary && (
        <p className="sources-summary">
          {ATS_SOURCES.map((a) => (
            <span key={a} className="sources-summary-item">
              {SOURCE_LABELS[a]}: {summary.byAts[a] ?? 0}
            </span>
          ))}
          {enabledCount != null && (
            <span className="sources-summary-item">Catalog total: {enabledCount}</span>
          )}
        </p>
      )}

      <div className="table-controls">
        <label htmlFor="sp-ats">Platform</label>
        <select
          id="sp-ats"
          value={atsFilter}
          onChange={(e) => setAtsFilter(e.target.value as JobSourceName | "")}
        >
          <option value="">All</option>
          {ATS_SOURCES.map((a) => (
            <option key={a} value={a}>
              {SOURCE_LABELS[a]}
            </option>
          ))}
        </select>

        <label htmlFor="sp-search">Search</label>
        <input
          id="sp-search"
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="company or slug"
        />

        <label className="checkbox-inline">
          <input
            type="checkbox"
            checked={enabledOnly}
            onChange={(e) => setEnabledOnly(e.target.checked)}
          />
          Enabled only
        </label>
      </div>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {rows == null ? (
        <p className="muted">Loading…</p>
      ) : rows.length === 0 ? (
        <p className="muted">No companies match that filter.</p>
      ) : (
        <>
          <div className="job-table-scroll">
            <table className="job-table">
              <thead>
                <tr>
                  <th>On</th>
                  <th>Company</th>
                  <th>Platform</th>
                  <th>Slug</th>
                  <th>Status</th>
                  <th>Last checked</th>
                  <th>Jobs</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className={r.status === "dead" ? "source-dead" : undefined}>
                    <td>
                      <input
                        type="checkbox"
                        checked={r.enabled}
                        disabled={busyId === r.id}
                        onChange={() => handleToggle(r)}
                        aria-label={`Enable ${r.company}`}
                      />
                    </td>
                    <td>{r.company}</td>
                    <td>{SOURCE_LABELS[r.ats] ?? r.ats}</td>
                    <td>
                      <code>{r.slug}</code>
                      {r.site && <span className="source-site"> · {r.site}</span>}
                    </td>
                    <td>
                      <span className={`source-status source-status-${r.status}`}>{r.status}</span>
                      {r.consecutiveFailures > 0 && r.status !== "dead" && (
                        <span className="source-fails"> ({r.consecutiveFailures} fail)</span>
                      )}
                    </td>
                    <td>{relativeTime(r.lastCheckedAt)}</td>
                    <td>{r.lastJobCount ?? "—"}</td>
                    <td>
                      <button type="button" disabled={busyId === r.id} onClick={() => handleDelete(r)}>
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="pager">
            <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - PAGE_SIZE))}>
              Previous
            </button>
            <span>
              {offset + 1}–{Math.min(offset + rows.length, total)} of {total}
            </span>
            <button
              type="button"
              disabled={offset + PAGE_SIZE >= total}
              onClick={() => setOffset(offset + PAGE_SIZE)}
            >
              Next
            </button>
          </div>
        </>
      )}

      <form className="add-source" onSubmit={handleAdd}>
        <h3>Add a company</h3>
        <div className="field-row">
          <label htmlFor="sp-new-ats">Platform</label>
          <select
            id="sp-new-ats"
            value={newAts}
            onChange={(e) => setNewAts(e.target.value as JobSourceName)}
            disabled={addBusy}
          >
            {ATS_SOURCES.map((a) => (
              <option key={a} value={a}>
                {SOURCE_LABELS[a]}
              </option>
            ))}
          </select>
        </div>

        {newAts === "workday" ? (
          <div className="field-row">
            <label htmlFor="sp-new-url">Careers URL</label>
            <input
              id="sp-new-url"
              type="url"
              value={newCareersUrl}
              onChange={(e) => setNewCareersUrl(e.target.value)}
              placeholder="https://acme.wd1.myworkdayjobs.com/en-US/AcmeCareers"
              disabled={addBusy}
            />
          </div>
        ) : (
          <div className="field-row">
            <label htmlFor="sp-new-slug">Board slug</label>
            <input
              id="sp-new-slug"
              type="text"
              value={newSlug}
              onChange={(e) => setNewSlug(e.target.value)}
              placeholder={newAts === "greenhouse" ? "e.g. airbnb" : "e.g. palantir"}
              disabled={addBusy}
            />
          </div>
        )}

        <div className="field-row">
          <label htmlFor="sp-new-company">Display name (optional)</label>
          <input
            id="sp-new-company"
            type="text"
            value={newCompany}
            onChange={(e) => setNewCompany(e.target.value)}
            disabled={addBusy}
          />
        </div>

        <button type="submit" disabled={addBusy}>
          {addBusy ? "Adding…" : "Add company"}
        </button>
      </form>
    </section>
  );
}
