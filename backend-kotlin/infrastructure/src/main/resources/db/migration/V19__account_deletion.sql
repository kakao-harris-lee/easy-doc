-- 회원 탈퇴와 개인정보 파기 경로(계획 `docs/plans/2026-09-09-account-deletion.md` §2 결정 4).
--
-- 지금까지 계정을 지우는 경로 자체가 없었다 — 개인정보 보호법의 파기 의무를 이행할
-- 방법이 없는 구멍이었다. `POST /auth/me/deletion`이 그 경로를 연다. `users` 행을
-- 지우면 CASCADE로 대부분(`workspaces`·`documents`·`conversions`·`user_identities`·
-- `credit_transactions`·`invoice_requests` 등)이 함께 사라진다 — 이 마이그레이션이
-- 손대는 것은 CASCADE가 **닿지 않는** 한 곳뿐이다.
--
-- **`llm_calls.user_id`를 `CASCADE`에서 `SET NULL`로 바꾼다(열 nullable 화 포함).**
-- V14 머리주석이 "계정 자체가 없으면 청구 대상도 없다"며 `CASCADE`로 결정했었지만,
-- 그 판단은 이 원장이 **개인정보를 담지 않는다**(문서 id·글자 수·토큰·비용뿐, V14
-- 머리주석)는 사실과 어긋난다 — 탈퇴해도 원가·사용량 집계 근거는 남겨야 한다. 이미
-- `workspace_id`·`conversion_id`·`document_id` 셋은 같은 이유(청구 근거 보존)로 FK
-- 자체가 없거나(`conversion_id`·`document_id`, V14 3차 정정) `SET NULL`이다
-- (`workspace_id`). `user_id`만 뒤처져 있었다.
--
-- **`credit_transactions`·`invoice_requests`는 그대로 둔다** — `owner_user_id`가
-- 여전히 `CASCADE`다. 아직 실제 결제가 없어 법정 보존 대상이 성립하지 않는다(계획
-- §2 결정 4 후반). 결제 도입 시 별도 마이그레이션으로 다시 연다.
ALTER TABLE llm_calls
    DROP CONSTRAINT fk_llm_calls_user_id_users;

ALTER TABLE llm_calls
    ALTER COLUMN user_id DROP NOT NULL;

ALTER TABLE llm_calls
    ADD CONSTRAINT fk_llm_calls_user_id_users FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE SET NULL;
