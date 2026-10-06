-- A row-local guard remains correct when an UPDATE waits on a concurrent refund reservation.
ALTER TABLE workspace_credit_accounts ADD COLUMN refund_reserved numeric NOT NULL DEFAULT 0
    CHECK (refund_reserved >= 0 AND refund_reserved <= reserved);

CREATE TABLE admin_billing_operations (
    operation_id uuid PRIMARY KEY,
    workspace_id uuid REFERENCES workspaces(id) ON DELETE SET NULL,
    payment_id uuid NOT NULL,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    amount integer NOT NULL CHECK (amount > 0),
    recovery_credits numeric NOT NULL CHECK (recovery_credits >= 0 AND recovery_credits * 10 = trunc(recovery_credits * 10)),
    stop_renewal boolean NOT NULL,
    reason varchar(200) NOT NULL CHECK (length(trim(reason)) > 0),
    expected_revision bigint NOT NULL CHECK (expected_revision >= 0),
    status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','completed','failed')),
    before_state jsonb NOT NULL,
    after_state jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX admin_billing_operations_pending ON admin_billing_operations(workspace_id) WHERE status='pending';
CREATE TABLE admin_billing_actions (
    operation_id uuid PRIMARY KEY,
    workspace_id uuid REFERENCES workspaces(id) ON DELETE SET NULL,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    action varchar(32) NOT NULL,
    target_id uuid,
    reason varchar(200) NOT NULL,
    before_state jsonb NOT NULL,
    after_state jsonb,
    status varchar(16) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','completed')),
    updated_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now()
);
