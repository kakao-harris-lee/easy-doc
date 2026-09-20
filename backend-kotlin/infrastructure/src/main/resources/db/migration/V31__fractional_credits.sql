-- Fractional credit amounts.
--
-- Credit amounts are now charged in 0.1 units.  NUMERIC preserves every existing
-- integer value exactly while allowing the new tenth-credit reservations and ledger
-- entries.  The USING expressions are explicit so this migration cannot silently
-- round or reinterpret an existing balance, reservation, allowance, or ledger row.

ALTER TABLE workspace_credit_accounts
    ALTER COLUMN balance TYPE numeric USING balance::numeric,
    ALTER COLUMN reserved TYPE numeric USING reserved::numeric,
    ALTER COLUMN allowance TYPE numeric USING allowance::numeric;

ALTER TABLE credit_transactions
    ALTER COLUMN balance_delta TYPE numeric USING balance_delta::numeric,
    ALTER COLUMN reserved_delta TYPE numeric USING reserved_delta::numeric;

ALTER TABLE conversions
    ALTER COLUMN credits_reserved TYPE numeric USING credits_reserved::numeric;

ALTER TABLE action_guide_jobs
    ALTER COLUMN reserved_credits TYPE numeric USING reserved_credits::numeric;

-- All application entry points validate tenths.  Keep the same invariant at the
-- database boundary so a direct SQL writer or an old worker cannot introduce a
-- hundredth and make later reservations settle at a value the API cannot represent.
ALTER TABLE workspace_credit_accounts
    ADD CONSTRAINT ck_workspace_credit_accounts_balance_tenth
        CHECK (balance = trunc(balance, 1)),
    ADD CONSTRAINT ck_workspace_credit_accounts_reserved_tenth
        CHECK (reserved = trunc(reserved, 1)),
    ADD CONSTRAINT ck_workspace_credit_accounts_allowance_tenth
        CHECK (allowance = trunc(allowance, 1));

ALTER TABLE credit_transactions
    ADD CONSTRAINT ck_credit_transactions_balance_delta_tenth
        CHECK (balance_delta = trunc(balance_delta, 1)),
    ADD CONSTRAINT ck_credit_transactions_reserved_delta_tenth
        CHECK (reserved_delta = trunc(reserved_delta, 1));

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_credits_reserved_tenth
        CHECK (credits_reserved = trunc(credits_reserved, 1));

ALTER TABLE action_guide_jobs
    ADD CONSTRAINT ck_action_guide_jobs_reserved_credits_tenth
        CHECK (reserved_credits = trunc(reserved_credits, 1));

-- V29's document-delete trigger aggregated the integer reservation column.  Recreate
-- the function after the column conversion so every historical job releases exactly
-- its stored reservation amount, including fractional reservations.
CREATE OR REPLACE FUNCTION settle_action_guide_jobs_for_document(deleting_document_id uuid)
RETURNS void
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM id
    FROM action_guide_jobs
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running')
    FOR UPDATE;

    UPDATE workspace_credit_accounts AS account
    SET reserved = account.reserved - released.amount,
        updated_at = now()
    FROM (
        SELECT workspace_id, sum(reserved_credits) AS amount
        FROM action_guide_jobs
        WHERE document_id = deleting_document_id
          AND status IN ('queued', 'running')
          AND settlement = 'reserved'
          AND workspace_id IS NOT NULL
        GROUP BY workspace_id
    ) AS released
    WHERE account.workspace_id = released.workspace_id;

    INSERT INTO credit_transactions
        (id, workspace_id, owner_user_id, document_id, kind, balance_delta,
         reserved_delta, reason, note, action_guide_job_id)
    SELECT gen_random_uuid(), job.workspace_id, job.owner_user_id, job.document_id,
           'release', 0, -job.reserved_credits, 'action_guide',
           'document_deleted', job.id
    FROM action_guide_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status IN ('queued', 'running')
      AND job.settlement = 'reserved'
      AND job.owner_user_id IS NOT NULL;

    UPDATE llm_calls AS call
    SET outcome = 'outcome_unknown',
        failure_class = NULL
    FROM action_guide_jobs AS job
    WHERE job.document_id = deleting_document_id
      AND job.status = 'running'
      AND job.provider_started_at IS NOT NULL
      AND call.action_guide_job_id = job.id
      AND call.outcome = 'in_progress';

    UPDATE action_guide_jobs
    SET status = 'superseded',
        settlement = 'released',
        failure_code = NULL,
        lease_owner = NULL,
        lease_until = NULL,
        worker_slot = NULL,
        updated_at = now()
    WHERE document_id = deleting_document_id
      AND status IN ('queued', 'running')
      AND settlement = 'reserved';
END;
$$;
