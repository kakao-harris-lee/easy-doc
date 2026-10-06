-- Billing events commit with their source mutation. No credentials, provider payloads or addresses in the outbox.
CREATE TABLE billing_notifications (
    id bigserial PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    event_key text UNIQUE NOT NULL,
    event_type varchar(40) NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'pending' CHECK (state IN ('pending','sending','sent','failed','manual_review')),
    created_at timestamptz NOT NULL DEFAULT now(),
    attempted_at timestamptz,
    sent_at timestamptz
);
CREATE INDEX billing_notifications_pending ON billing_notifications(id) WHERE state='pending';

CREATE FUNCTION queue_billing_order_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('paid','failed','manual_review') AND
       (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        INSERT INTO billing_notifications(workspace_id,event_key,event_type)
        VALUES (NEW.workspace_id,'order:'||NEW.id||':'||NEW.status,NEW.kind||'_'||NEW.status)
        ON CONFLICT (event_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_order_notification AFTER INSERT OR UPDATE ON toss_billing_orders
    FOR EACH ROW EXECUTE FUNCTION queue_billing_order_notification();

CREATE FUNCTION queue_billing_subscription_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider IN ('toss_test','toss_live') AND NEW.status IN ('canceling','past_due','expired','suspended') AND
       (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        INSERT INTO billing_notifications(workspace_id,event_key,event_type)
        VALUES (NEW.workspace_id,'subscription:'||NEW.workspace_id||':'||NEW.cycle_ends_at||':'||NEW.status,NEW.status)
        ON CONFLICT (event_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER billing_subscription_notification AFTER INSERT OR UPDATE ON workspace_subscriptions
    FOR EACH ROW EXECUTE FUNCTION queue_billing_subscription_notification();
