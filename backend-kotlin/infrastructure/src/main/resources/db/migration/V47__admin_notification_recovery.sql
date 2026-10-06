ALTER TABLE billing_notifications ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE billing_notifications ADD COLUMN resolution text CHECK (resolution IN ('delivered','not_delivered'));
CREATE TABLE billing_notification_attempts (
    id bigserial PRIMARY KEY,
    notification_id bigint NOT NULL REFERENCES billing_notifications(id) ON DELETE CASCADE,
    state text NOT NULL CHECK (state IN ('sending','sent','failed','manual_review')),
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    failure_code text CHECK (failure_code IN ('recipient_missing','provider_rejected','uncertain'))
);
CREATE UNIQUE INDEX billing_notification_attempt_active ON billing_notification_attempts(notification_id) WHERE state='sending';
CREATE TABLE admin_notification_actions (
    operation_id uuid PRIMARY KEY,
    notification_id bigint NOT NULL REFERENCES billing_notifications(id) ON DELETE CASCADE,
    actor_user_id uuid REFERENCES users(id) ON DELETE SET NULL,
    action text NOT NULL CHECK (action IN ('retry','delivered','not_delivered')),
    expected_revision bigint NOT NULL,
    reason varchar(200) NOT NULL CHECK (length(trim(reason)) > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
-- History begins here. Do not invent legacy attempts or delivery evidence.
ALTER TABLE admin_billing_actions ADD COLUMN expected_revision bigint;

-- Row-local stamps avoid a trigger taking workspace or shared counter row locks.
-- Lease/attempt scheduling updates do not change the operator-visible revision.
CREATE SEQUENCE admin_billing_revision_seq MAXVALUE 9007199254740991;
ALTER TABLE workspace_subscriptions ADD COLUMN admin_revision bigint NOT NULL DEFAULT nextval('admin_billing_revision_seq');
ALTER TABLE toss_billing_sessions ADD COLUMN admin_revision bigint NOT NULL DEFAULT nextval('admin_billing_revision_seq');
ALTER TABLE toss_billing_orders ADD COLUMN admin_revision bigint NOT NULL DEFAULT nextval('admin_billing_revision_seq');
CREATE INDEX admin_order_revision_by_workspace ON toss_billing_orders(workspace_id,admin_revision DESC);
ALTER TABLE toss_billing_sessions ADD COLUMN deletion_pending_since timestamptz;
-- Existing pending rows keep an unknown start time. No historical date is invented.
CREATE FUNCTION stamp_admin_subscription_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.status,NEW.plan_id,NEW.provider,NEW.cycle_ends_at,NEW.allowance,NEW.monthly_price)
        IS DISTINCT FROM ROW(OLD.status,OLD.plan_id,OLD.provider,OLD.cycle_ends_at,OLD.allowance,OLD.monthly_price) THEN
        NEW.admin_revision := nextval('admin_billing_revision_seq');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER admin_subscription_revision BEFORE UPDATE ON workspace_subscriptions
    FOR EACH ROW EXECUTE FUNCTION stamp_admin_subscription_revision();
CREATE FUNCTION stamp_admin_session_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pending boolean;
BEGIN
    pending := NEW.state='revoking' OR NEW.cleanup_pending;
    IF TG_OP='INSERT' THEN
        IF pending THEN NEW.deletion_pending_since := now(); END IF;
    ELSE
        IF pending AND NOT (OLD.state='revoking' OR OLD.cleanup_pending) THEN
            NEW.deletion_pending_since := now();
        ELSIF NOT pending THEN
            NEW.deletion_pending_since := NULL;
        END IF;
        IF ROW(NEW.id,NEW.state,NEW.cleanup_pending,NEW.environment,NEW.plan_id)
            IS DISTINCT FROM ROW(OLD.id,OLD.state,OLD.cleanup_pending,OLD.environment,OLD.plan_id) THEN
            NEW.admin_revision := nextval('admin_billing_revision_seq');
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER admin_session_revision BEFORE INSERT OR UPDATE ON toss_billing_sessions
    FOR EACH ROW EXECUTE FUNCTION stamp_admin_session_revision();
CREATE FUNCTION stamp_admin_order_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(NEW.status,NEW.kind,NEW.amount,NEW.environment,NEW.approved_at,NEW.canceled_at)
        IS DISTINCT FROM ROW(OLD.status,OLD.kind,OLD.amount,OLD.environment,OLD.approved_at,OLD.canceled_at) THEN
        NEW.admin_revision := nextval('admin_billing_revision_seq');
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER admin_order_revision BEFORE UPDATE ON toss_billing_orders
    FOR EACH ROW EXECUTE FUNCTION stamp_admin_order_revision();
