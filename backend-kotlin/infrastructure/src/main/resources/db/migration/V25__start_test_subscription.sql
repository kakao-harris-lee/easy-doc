ALTER TABLE workspace_subscriptions DROP CONSTRAINT workspace_subscriptions_plan_id_check;

-- 이전 테스트 플랜은 실제 청구가 없던 개발 데이터다. 활성 주기를 Start 하나로 모아
-- 다음 테스트 갱신이 제거된 starter/pro 식별자로 실패하지 않게 한다.
UPDATE workspace_subscriptions
SET plan_id = 'start', allowance = 50, monthly_price = 99000
WHERE plan_id IN ('starter', 'pro');

ALTER TABLE workspace_subscriptions
    ADD CONSTRAINT workspace_subscriptions_plan_id_check CHECK (plan_id = 'start');

UPDATE toss_billing_sessions
SET plan_id = 'start'
WHERE plan_id IN ('starter', 'pro');

UPDATE toss_billing_orders
SET plan_id = 'start', amount = 99000
WHERE plan_id IN ('starter', 'pro')
  AND kind = 'charge'
  AND status IN ('pending', 'processing', 'manual_review');
