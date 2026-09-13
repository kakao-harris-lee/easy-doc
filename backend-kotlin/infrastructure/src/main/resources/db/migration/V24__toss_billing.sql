ALTER TABLE workspace_subscriptions ADD COLUMN provider varchar(16) NOT NULL DEFAULT 'stub';
ALTER TABLE subscription_payments ADD COLUMN provider varchar(16) NOT NULL DEFAULT 'stub';
ALTER TABLE subscription_payments ADD COLUMN refunded_amount integer NOT NULL DEFAULT 0;
ALTER TABLE subscription_payments ALTER COLUMN status TYPE varchar(32);
ALTER TABLE subscription_payments DROP CONSTRAINT subscription_payments_status_check;
ALTER TABLE subscription_payments ADD CHECK (status IN ('paid', 'failed', 'partially_refunded', 'refunded'));

CREATE TABLE toss_billing_sessions (
    workspace_id uuid PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    id uuid UNIQUE NOT NULL,
    customer uuid NOT NULL,
    plan_id varchar(32) NOT NULL,
    state varchar(16) NOT NULL,
    expires_at timestamptz NOT NULL,
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL CHECK (encryption_scheme='aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version >= 1)
);
CREATE TABLE toss_billing_orders (
    id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    plan_id varchar(32) NOT NULL,
    amount integer NOT NULL CHECK (amount > 0),
    created_at timestamptz NOT NULL,
    cycle_ends_at timestamptz NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('charge','refund')),
    original_id uuid REFERENCES toss_billing_orders(id),
    previous_remaining integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL,
    simulate_failure boolean NOT NULL DEFAULT false,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    lease_until timestamptz,
    sync_requested boolean NOT NULL DEFAULT false,
    payload_encrypted bytea NOT NULL,
    encryption_scheme varchar(32) NOT NULL CHECK (encryption_scheme='aes256gcm-v1'),
    key_version smallint NOT NULL CHECK (key_version >= 1)
);
CREATE INDEX toss_billing_due ON toss_billing_orders(next_attempt_at) WHERE status IN ('pending','processing');
CREATE UNIQUE INDEX toss_one_pending_charge ON toss_billing_orders(workspace_id) WHERE kind='charge' AND status IN ('pending','processing','manual_review');
CREATE UNIQUE INDEX toss_one_pending_refund ON toss_billing_orders(original_id) WHERE kind='refund' AND status IN ('pending','processing','manual_review');
