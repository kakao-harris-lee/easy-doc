-- Mock only: no card data, billing keys or real payment credentials.
CREATE TABLE workspace_subscriptions (
    workspace_id uuid PRIMARY KEY REFERENCES workspaces(id) ON DELETE CASCADE,
    plan_id varchar(32) NOT NULL CHECK (plan_id IN ('starter', 'pro')),
    allowance integer NOT NULL CHECK (allowance > 0),
    monthly_price integer NOT NULL CHECK (monthly_price > 0),
    status varchar(16) NOT NULL CHECK (status IN ('active', 'canceling', 'expired', 'past_due')),
    cycle_ends_at timestamptz NOT NULL
);
CREATE INDEX workspace_subscriptions_due ON workspace_subscriptions(cycle_ends_at) WHERE status IN ('active', 'canceling');
CREATE TABLE subscription_payments (
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    id uuid NOT NULL,
    plan_id varchar(32) NOT NULL,
    amount integer NOT NULL CHECK (amount > 0),
    status varchar(16) NOT NULL CHECK (status IN ('paid', 'failed')),
    created_at timestamptz NOT NULL,
    simulated_failure boolean NOT NULL,
    PRIMARY KEY (workspace_id, id)
);
CREATE INDEX subscription_payments_recent ON subscription_payments(workspace_id, created_at DESC);
