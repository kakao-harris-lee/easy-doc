-- Existing records remain test records; never promote a stored credential to live.
ALTER TABLE toss_billing_sessions
    ADD COLUMN environment text NOT NULL DEFAULT 'toss_test' CHECK (environment IN ('toss_test','toss_live')),
    ADD COLUMN purpose text NOT NULL DEFAULT 'purchase' CHECK (purpose IN ('purchase','replace_card')),
    ADD COLUMN consent_version text,
    ADD COLUMN consent_at timestamptz;
ALTER TABLE toss_billing_orders
    ADD COLUMN environment text NOT NULL DEFAULT 'toss_test' CHECK (environment IN ('toss_test','toss_live')),
    ADD COLUMN cycle_id uuid,
    ADD COLUMN attempt integer NOT NULL DEFAULT 0,
    ADD COLUMN first_failure_at timestamptz,
    ADD COLUMN approved_at timestamptz,
    ADD COLUMN canceled_at timestamptz;
UPDATE toss_billing_orders SET cycle_id=id;
ALTER TABLE toss_billing_orders ALTER COLUMN cycle_id SET NOT NULL;
CREATE UNIQUE INDEX ux_toss_cycle_attempt ON toss_billing_orders(cycle_id, attempt);
CREATE INDEX ix_toss_environment_status ON toss_billing_orders(environment,status,next_attempt_at);
ALTER TABLE toss_billing_orders DROP CONSTRAINT toss_billing_orders_kind_check;
ALTER TABLE toss_billing_orders ADD CONSTRAINT toss_billing_orders_kind_check CHECK (kind IN ('charge','renewal','refund'));
DROP INDEX toss_one_pending_charge;
CREATE UNIQUE INDEX toss_one_pending_charge ON toss_billing_orders(workspace_id)
    WHERE kind IN ('charge','renewal') AND status IN ('pending','processing','manual_review','suspend_pending','scheduled');
ALTER TABLE toss_billing_sessions ADD COLUMN key_fingerprint varchar(64);
CREATE INDEX ix_toss_key_fingerprint ON toss_billing_sessions(environment,key_fingerprint);
ALTER TABLE toss_billing_sessions ADD COLUMN cleanup_pending boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX ux_toss_cycle_paid ON toss_billing_orders(cycle_id) WHERE kind<>'refund' AND status='paid';
