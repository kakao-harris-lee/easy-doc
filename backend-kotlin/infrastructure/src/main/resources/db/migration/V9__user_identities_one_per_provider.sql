-- 명시적 계정 연결(backlog §1.4) 리뷰 후속 조치 — "사용자당 제공자 하나" 불변식을 DB
-- 유일성 제약으로 뒷받침한다.
--
-- 종전에는 이 불변식을 `SocialLoginService.linkCallback` 이 트랜잭션 **밖**에서
-- `findByUserAndProvider` 로 미리 확인하는 것만으로 지켰다(`UserIdentityRepository`
-- KDoc). 그 확인과 뒤이은 `INSERT` 사이에는 커넥션 경계가 없어, 같은 사용자가 서로
-- 다른 Google 계정으로 동시에 두 번 연결을 시도하면 **둘 다 확인을 통과한 뒤 둘 다
-- INSERT 에 성공할 수 있었다** — 사용자당 제공자 하나 불변식이 실제로는 강제되지
-- 않는 경쟁 창이었다(리뷰 지적, HIGH). 유일성 제약이 마지막 방어선이 되게 한다 —
-- `ix_users_email`(V1)·`uq_user_identities_provider_provider_user_id`(V6)와 같은 방침.
ALTER TABLE user_identities
    ADD CONSTRAINT uq_user_identities_user_id_provider UNIQUE (user_id, provider);
