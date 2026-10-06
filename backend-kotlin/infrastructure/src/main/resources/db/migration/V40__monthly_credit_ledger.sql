-- Capture exact account state under its existing row lock; never reconstruct old cycles from dates.
ALTER TABLE workspace_credit_accounts ADD COLUMN revision bigint NOT NULL DEFAULT 0;
-- One bounded latest-change snapshot per account; txid gates use to the same transaction only.
CREATE TABLE credit_account_change_snapshots (
    -- Settlement already locks the credit account. Referencing workspaces here would add
    -- an account -> workspace lock edge and deadlock with account deletion waiting on jobs.
    workspace_id uuid PRIMARY KEY REFERENCES workspace_credit_accounts(workspace_id) ON DELETE CASCADE,
    transaction_id bigint NOT NULL,
    before_state jsonb NOT NULL,
    after_state jsonb NOT NULL
);
CREATE FUNCTION capture_credit_account_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.revision := OLD.revision + 1;
    INSERT INTO credit_account_change_snapshots VALUES (NEW.workspace_id, txid_current(), to_jsonb(OLD), to_jsonb(NEW))
    ON CONFLICT (workspace_id) DO UPDATE SET transaction_id=EXCLUDED.transaction_id,
        before_state=EXCLUDED.before_state, after_state=EXCLUDED.after_state;
    RETURN NEW;
END $$;
CREATE TRIGGER credit_account_change BEFORE UPDATE ON workspace_credit_accounts
FOR EACH ROW EXECUTE FUNCTION capture_credit_account_change();

ALTER TABLE credit_transactions
    ADD COLUMN granted_amount numeric, ADD COLUMN expired_amount numeric,
    ADD COLUMN adjustment_amount numeric,
    ADD COLUMN balance_before numeric, ADD COLUMN balance_after numeric,
    ADD COLUMN cycle_started_at_before timestamptz, ADD COLUMN cycle_ends_at_before timestamptz,
    ADD COLUMN cycle_started_at_after timestamptz, ADD COLUMN cycle_ends_at_after timestamptz,
    ADD COLUMN payment_id uuid;
CREATE FUNCTION enrich_credit_cycle_transaction() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s credit_account_change_snapshots%ROWTYPE;
BEGIN
    IF NEW.kind IN ('cycle_set', 'cycle_reset') THEN
        SELECT * INTO s FROM credit_account_change_snapshots
        WHERE workspace_id=NEW.workspace_id AND transaction_id=txid_current();
        IF FOUND THEN
            NEW.balance_before := (s.before_state->>'balance')::numeric;
            NEW.balance_after := (s.after_state->>'balance')::numeric;
            IF NEW.balance_after - NEW.balance_before <> NEW.balance_delta THEN
                RAISE EXCEPTION 'Cycle ledger does not match account change';
            END IF;
            NEW.granted_amount := greatest(NEW.balance_after, 0);
            NEW.expired_amount := greatest(NEW.balance_before, 0);
            NEW.adjustment_amount := least(NEW.balance_after, 0) - least(NEW.balance_before, 0);
            NEW.cycle_started_at_before := (s.before_state->>'cycle_started_at')::timestamptz;
            NEW.cycle_ends_at_before := (s.before_state->>'cycle_ends_at')::timestamptz;
            NEW.cycle_started_at_after := (s.after_state->>'cycle_started_at')::timestamptz;
            NEW.cycle_ends_at_after := (s.after_state->>'cycle_ends_at')::timestamptz;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER credit_cycle_metadata BEFORE INSERT ON credit_transactions
FOR EACH ROW EXECUTE FUNCTION enrich_credit_cycle_transaction();

CREATE TABLE subscription_payment_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id uuid NOT NULL,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    kind varchar(16) NOT NULL CHECK (kind IN ('payment','refund')),
    amount_krw integer NOT NULL CHECK (amount_krw > 0),
    is_test boolean NOT NULL,
    occurred_at timestamptz,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    operation_key text NOT NULL UNIQUE,
    historical boolean NOT NULL DEFAULT false
);
CREATE INDEX payment_events_workspace_month ON subscription_payment_events(workspace_id, occurred_at DESC, id);
-- created_at on old payments is order creation, not confirmation. Do not invent confirmation months.
INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,operation_key,historical)
SELECT id,workspace_id,'payment',amount,provider IN ('stub','toss_test'),workspace_id::text||':'||id::text||':payment',true
FROM subscription_payments WHERE status IN ('paid','partially_refunded','refunded') AND amount>0;
INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,operation_key,historical)
SELECT id,workspace_id,'refund',refunded_amount,provider IN ('stub','toss_test'),
       workspace_id::text||':'||id::text||':refund:'||refunded_amount::text,true
FROM subscription_payments WHERE refunded_amount>0;
CREATE FUNCTION record_confirmed_payment_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_refund integer := 0;
BEGIN
    IF TG_OP='UPDATE' THEN previous_refund := OLD.refunded_amount; END IF;
    IF NEW.status IN ('paid','partially_refunded','refunded') AND NEW.amount>0 THEN
        INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,occurred_at,operation_key)
        VALUES(NEW.id,NEW.workspace_id,'payment',NEW.amount,NEW.provider IN ('stub','toss_test'),clock_timestamp(),NEW.workspace_id::text||':'||NEW.id::text||':payment')
        ON CONFLICT(operation_key) DO NOTHING;
    END IF;
    IF NEW.refunded_amount > previous_refund THEN
        INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,occurred_at,operation_key)
        VALUES(NEW.id,NEW.workspace_id,'refund',NEW.refunded_amount-previous_refund,NEW.provider IN ('stub','toss_test'),
               clock_timestamp(),NEW.workspace_id::text||':'||NEW.id::text||':refund:'||NEW.refunded_amount::text)
        ON CONFLICT(operation_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER confirmed_payment_event AFTER INSERT OR UPDATE ON subscription_payments
FOR EACH ROW EXECUTE FUNCTION record_confirmed_payment_event();

CREATE TABLE admin_credit_adjustments (
    operation_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    credits numeric NOT NULL,
    reason varchar(32) NOT NULL,
    note varchar(200) NOT NULL,
    expected_balance numeric NOT NULL,
    expected_reserved numeric NOT NULL,
    expected_revision bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX credit_transactions_workspace_month ON credit_transactions(workspace_id,created_at DESC,id DESC);

ALTER TABLE credit_transactions ADD CONSTRAINT cycle_metadata_consistent CHECK (
    (granted_amount IS NULL AND expired_amount IS NULL AND adjustment_amount IS NULL
        AND balance_before IS NULL AND balance_after IS NULL) OR (
        granted_amount IS NOT NULL AND expired_amount IS NOT NULL AND adjustment_amount IS NOT NULL
        AND balance_before IS NOT NULL AND balance_after IS NOT NULL AND
        balance_after - balance_before = balance_delta AND
        granted_amount >= 0 AND expired_amount >= 0 AND
        granted_amount - expired_amount + adjustment_amount = balance_delta AND
        granted_amount * 10 = trunc(granted_amount * 10) AND
        expired_amount * 10 = trunc(expired_amount * 10) AND
        adjustment_amount * 10 = trunc(adjustment_amount * 10) AND
        balance_before * 10 = trunc(balance_before * 10) AND
        balance_after * 10 = trunc(balance_after * 10)
    )
);
ALTER TABLE admin_credit_adjustments ADD CHECK (
    credits <> 0 AND credits * 10 = trunc(credits * 10) AND
    expected_balance * 10 = trunc(expected_balance * 10) AND
    expected_reserved * 10 = trunc(expected_reserved * 10) AND
    expected_revision >= 0 AND expected_reserved >= 0 AND length(trim(note)) > 0
);
