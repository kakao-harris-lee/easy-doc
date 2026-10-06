-- Provider confirmation time is authoritative. Never backfill historical unknown dates with order creation.
CREATE OR REPLACE FUNCTION record_confirmed_payment_event() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_refund integer := 0;
        approval timestamptz;
        cancellation timestamptz;
BEGIN
    IF TG_OP='UPDATE' THEN previous_refund := OLD.refunded_amount; END IF;
    IF NEW.provider IN ('toss_test','toss_live') THEN
        SELECT approved_at,canceled_at INTO approval,cancellation FROM toss_billing_orders
        WHERE id=NEW.id AND workspace_id=NEW.workspace_id;
    ELSE
        approval := clock_timestamp();
        cancellation := clock_timestamp();
    END IF;
    IF NEW.status IN ('paid','partially_refunded','refunded') AND NEW.amount>0 THEN
        INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,occurred_at,operation_key)
        VALUES(NEW.id,NEW.workspace_id,'payment',NEW.amount,NEW.provider IN ('stub','toss_test'),approval,
               NEW.workspace_id::text||':'||NEW.id::text||':payment')
        ON CONFLICT(operation_key) DO NOTHING;
    END IF;
    IF NEW.refunded_amount > previous_refund THEN
        INSERT INTO subscription_payment_events(payment_id,workspace_id,kind,amount_krw,is_test,occurred_at,operation_key)
        VALUES(NEW.id,NEW.workspace_id,'refund',NEW.refunded_amount-previous_refund,NEW.provider IN ('stub','toss_test'),
               cancellation,NEW.workspace_id::text||':'||NEW.id::text||':refund:'||NEW.refunded_amount::text)
        ON CONFLICT(operation_key) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
