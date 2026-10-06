-- Unknown legacy environment is quarantined, never guessed from the worker's current setting.
ALTER TABLE billing_notifications ADD COLUMN environment text
    CHECK (environment IN ('toss_test','toss_live'));

UPDATE billing_notifications n SET environment=o.environment
FROM toss_billing_orders o
WHERE n.workspace_id=o.workspace_id AND split_part(n.event_key,':',1)='order'
    AND split_part(n.event_key,':',2)=o.id::text;

-- A workspace's current subscription/session cannot establish a historical notification's environment.
-- Keep every legacy non-order event unknown, even if all surviving workspace records currently agree.
UPDATE billing_notifications SET state='manual_review'
WHERE environment IS NULL AND state IN ('pending','sending');
CREATE INDEX billing_notifications_environment_pending ON billing_notifications(environment,id) WHERE state='pending';

CREATE OR REPLACE FUNCTION queue_billing_order_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status IN ('paid','failed','manual_review') AND
       (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        INSERT INTO billing_notifications(workspace_id,event_key,event_type,environment)
        VALUES (NEW.workspace_id,'order:'||NEW.id||':'||NEW.status,NEW.kind||'_'||NEW.status,NEW.environment)
        ON CONFLICT (event_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION queue_billing_subscription_notification() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider IN ('toss_test','toss_live') AND NEW.status IN ('canceling','past_due','expired','suspended') AND
       (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM NEW.status) THEN
        INSERT INTO billing_notifications(workspace_id,event_key,event_type,environment)
        VALUES (NEW.workspace_id,'subscription:'||NEW.workspace_id||':'||NEW.cycle_ends_at||':'||NEW.status,
            NEW.status,NEW.provider)
        ON CONFLICT (event_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
