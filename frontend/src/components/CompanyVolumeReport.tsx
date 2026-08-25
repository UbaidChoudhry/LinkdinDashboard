import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { ApiError, companyVolumeReport } from "../api/client";
import type { CompanyVolumeResponse } from "../types/api";

const DEFAULT_DAYS = 7;
const DEFAULT_THRESHOLD = 40;

export function CompanyVolumeReport() {
  const [days, setDays] = useState(DEFAULT_DAYS);
  const [threshold, setThreshold] = useState(DEFAULT_THRESHOLD);
  const [rows, setRows] = useState<CompanyVolumeResponse[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function load(d: number, t: number) {
    setLoading(true);
    setError(null);
    try {
      setRows(await companyVolumeReport(d, t));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Failed to load report.");
      setRows([]);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    load(DEFAULT_DAYS, DEFAULT_THRESHOLD);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  function handleSubmit(e: FormEvent) {
    e.preventDefault();
    load(days, threshold);
  }

  return (
    <section className="company-volume-report">
      <h2>Company posting volume</h2>
      <p className="hint">
        Companies posting a high volume of listings in the recency window - a structural signal
        for staffing-agency-style relays, not an automatic verdict.
      </p>

      <form className="inline-form" onSubmit={handleSubmit}>
        <label htmlFor="cvr-days">Days</label>
        <input
          id="cvr-days"
          type="number"
          min={1}
          value={days}
          onChange={(e) => setDays(Number(e.target.value))}
        />
        <label htmlFor="cvr-threshold">Threshold</label>
        <input
          id="cvr-threshold"
          type="number"
          min={1}
          value={threshold}
          onChange={(e) => setThreshold(Number(e.target.value))}
        />
        <button type="submit" disabled={loading}>
          {loading ? "Loading..." : "Refresh"}
        </button>
      </form>

      {error && (
        <p className="form-error" role="alert">
          {error}
        </p>
      )}

      {rows === null && !error && <p className="hint">Loading...</p>}
      {rows !== null && rows.length === 0 && !error && (
        <p className="hint">No companies at or above this posting threshold in the selected window.</p>
      )}
      {rows !== null && rows.length > 0 && (
        <table className="company-table">
          <thead>
            <tr>
              <th>Company</th>
              <th>Postings</th>
              <th>Distinct titles</th>
              <th>Distinct locations</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.company}>
                <td>{r.company}</td>
                <td>{r.postings}</td>
                <td>{r.titles}</td>
                <td>{r.locations}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}
