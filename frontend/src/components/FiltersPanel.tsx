import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import {
  ApiError,
  addBlockedCompany,
  addExcludeWord,
  deleteBlockedCompany,
  deleteExcludeWord,
  listBlockedCompanies,
  listExcludeWords,
} from "../api/client";
import type { CompanyBlocklistResponse } from "../types/api";
import { absoluteTime } from "../utils/format";

export function FiltersPanel() {
  const [words, setWords] = useState<string[] | null>(null);
  const [companies, setCompanies] = useState<CompanyBlocklistResponse[] | null>(null);
  const [wordsError, setWordsError] = useState<string | null>(null);
  const [companiesError, setCompaniesError] = useState<string | null>(null);

  const [newWord, setNewWord] = useState("");
  const [newCompany, setNewCompany] = useState("");
  const [newCompanyReason, setNewCompanyReason] = useState("");
  const [wordBusy, setWordBusy] = useState(false);
  const [companyBusy, setCompanyBusy] = useState(false);

  async function loadWords() {
    setWordsError(null);
    try {
      setWords(await listExcludeWords());
    } catch (err) {
      setWordsError(err instanceof ApiError ? err.message : "Failed to load exclude words.");
      setWords([]);
    }
  }

  async function loadCompanies() {
    setCompaniesError(null);
    try {
      setCompanies(await listBlockedCompanies());
    } catch (err) {
      setCompaniesError(err instanceof ApiError ? err.message : "Failed to load blocked companies.");
      setCompanies([]);
    }
  }

  useEffect(() => {
    loadWords();
    loadCompanies();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function handleAddWord(e: FormEvent) {
    e.preventDefault();
    if (!newWord.trim()) return;
    setWordBusy(true);
    setWordsError(null);
    try {
      const updated = await addExcludeWord(newWord.trim());
      setWords(updated);
      setNewWord("");
    } catch (err) {
      setWordsError(err instanceof ApiError ? err.message : "Failed to add word.");
    } finally {
      setWordBusy(false);
    }
  }

  async function handleDeleteWord(word: string) {
    setWordBusy(true);
    setWordsError(null);
    try {
      const updated = await deleteExcludeWord(word);
      setWords(updated);
    } catch (err) {
      setWordsError(err instanceof ApiError ? err.message : "Failed to delete word.");
    } finally {
      setWordBusy(false);
    }
  }

  async function handleAddCompany(e: FormEvent) {
    e.preventDefault();
    if (!newCompany.trim()) return;
    setCompanyBusy(true);
    setCompaniesError(null);
    try {
      const updated = await addBlockedCompany(newCompany.trim(), newCompanyReason.trim() || undefined);
      setCompanies(updated);
      setNewCompany("");
      setNewCompanyReason("");
    } catch (err) {
      setCompaniesError(err instanceof ApiError ? err.message : "Failed to add company.");
    } finally {
      setCompanyBusy(false);
    }
  }

  async function handleDeleteCompany(company: string) {
    setCompanyBusy(true);
    setCompaniesError(null);
    try {
      const updated = await deleteBlockedCompany(company);
      setCompanies(updated);
    } catch (err) {
      setCompaniesError(err instanceof ApiError ? err.message : "Failed to delete company.");
    } finally {
      setCompanyBusy(false);
    }
  }

  return (
    <section className="filters-panel">
      <h2>Filters</h2>
      <p className="hint">
        Editing either list below re-evaluates every previously stored job, not just future
        sweeps - removing a word or company can bring previously rejected jobs back into Search.
      </p>

      <div className="filters-columns">
        <div className="filter-column">
          <h3>Exclude words</h3>
          <form className="inline-form" onSubmit={handleAddWord}>
            <label htmlFor="fp-new-word" className="sr-only">
              New exclude word
            </label>
            <input
              id="fp-new-word"
              type="text"
              value={newWord}
              onChange={(e) => setNewWord(e.target.value)}
              placeholder="e.g. senior"
              disabled={wordBusy}
            />
            <button type="submit" disabled={wordBusy || !newWord.trim()}>
              Add
            </button>
          </form>

          {wordsError && (
            <p className="form-error" role="alert">
              {wordsError}
            </p>
          )}

          {words === null && <p className="hint">Loading...</p>}
          {words !== null && words.length === 0 && !wordsError && <p className="hint">No exclude words yet.</p>}
          {words !== null && words.length > 0 && (
            <ul className="chip-list">
              {words.map((word) => (
                <li key={word} className="chip">
                  <span>{word}</span>
                  <button
                    type="button"
                    className="chip-remove"
                    onClick={() => handleDeleteWord(word)}
                    disabled={wordBusy}
                    aria-label={`Remove ${word} from exclude words`}
                  >
                    ×
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="filter-column">
          <h3>Blocked companies</h3>
          <form className="inline-form" onSubmit={handleAddCompany}>
            <label htmlFor="fp-new-company" className="sr-only">
              New blocked company
            </label>
            <input
              id="fp-new-company"
              type="text"
              value={newCompany}
              onChange={(e) => setNewCompany(e.target.value)}
              placeholder="company name"
              disabled={companyBusy}
            />
            <label htmlFor="fp-new-reason" className="sr-only">
              Reason (optional)
            </label>
            <input
              id="fp-new-reason"
              type="text"
              value={newCompanyReason}
              onChange={(e) => setNewCompanyReason(e.target.value)}
              placeholder="reason (optional)"
              disabled={companyBusy}
            />
            <button type="submit" disabled={companyBusy || !newCompany.trim()}>
              Add
            </button>
          </form>

          {companiesError && (
            <p className="form-error" role="alert">
              {companiesError}
            </p>
          )}

          {companies === null && <p className="hint">Loading...</p>}
          {companies !== null && companies.length === 0 && !companiesError && (
            <p className="hint">No blocked companies yet.</p>
          )}
          {companies !== null && companies.length > 0 && (
            <table className="company-table">
              <thead>
                <tr>
                  <th>Company</th>
                  <th>Reason</th>
                  <th>Added</th>
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {companies.map((c) => (
                  <tr key={c.company}>
                    <td>{c.company}</td>
                    <td>{c.reason ?? "-"}</td>
                    <td>{absoluteTime(c.addedAt)}</td>
                    <td>
                      <button
                        type="button"
                        className="secondary"
                        onClick={() => handleDeleteCompany(c.company)}
                        disabled={companyBusy}
                      >
                        Remove
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </section>
  );
}
