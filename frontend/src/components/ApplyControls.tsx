import { Fragment, useState } from "react";
import { startApplications } from "../api/client";
import { useApplyBatch } from "../hooks/useApplyBatch";
import { ApplyBatchDetails, ApplyBatchStatus } from "./ApplyBatchView";
import { InfoTip } from "./InfoTip";

interface ApplyControlsProps {
  /** Job ids eligible for this run: recommended, untriaged, non-LinkedIn rows on screen. */
  eligibleJobIds: number[];
  /** How many recommended rows on screen are LinkedIn (skipped, apply manually). */
  linkedinCount: number;
  /** Called once a batch finishes, so the caller can reload its job list. */
  onFinished: () => void;
}

export function ApplyControls({ eligibleJobIds, linkedinCount, onFinished }: ApplyControlsProps) {
  const [submitChecked, setSubmitChecked] = useState(false);
  const [showDetails, setShowDetails] = useState(false);
  const { batch, running, starting, error, start, cancel } = useApplyBatch(onFinished);

  function handleApply() {
    void start(() => startApplications({ jobIds: eligibleJobIds, submit: submitChecked }));
  }

  return (
    <Fragment>
      <div className="apply-controls">
        <button type="button" onClick={handleApply} disabled={eligibleJobIds.length === 0 || running || starting}>
          {starting ? "Starting…" : `Apply with Claude (${eligibleJobIds.length})`}
        </button>

        <span className="checkbox-row">
          <input
            id="apply-submit"
            type="checkbox"
            checked={submitChecked}
            onChange={(e) => setSubmitChecked(e.target.checked)}
            disabled={running}
          />
          <label htmlFor="apply-submit">Submit applications</label>
        </span>
        <InfoTip label="About Apply with Claude">
          Runs over the Recommended bucket, several applications at once (apply.concurrency, default
          3). Unchecked, Claude fills each form and stops before Submit, leaving the tab open for
          review; checked, it submits.
          {linkedinCount > 0 && (
            <>
              {" "}
              {linkedinCount} LinkedIn job{linkedinCount === 1 ? " has" : "s have"} no company apply
              link and will be skipped - open the posting, copy its Apply link, and paste it into the
              Apply tab.
            </>
          )}{" "}
          If a job gets stuck, open its log or resume the session in a terminal - the browser tabs
          are gone once the session ends, but Claude can reopen them.
        </InfoTip>

        {batch && <ApplyBatchStatus batch={batch} onCancel={cancel} />}

        {batch && (
          <button type="button" className="secondary" onClick={() => setShowDetails((v) => !v)}>
            {showDetails ? "Details ▴" : "Details ▾"}
          </button>
        )}

        {error && (
          <p className="form-error" role="alert">
            {error}
          </p>
        )}
      </div>

      {batch && showDetails && <ApplyBatchDetails batch={batch} />}
    </Fragment>
  );
}
