-- Kotlin 전용 첫 변경. Phase 0 필수 조치 D.
--
-- ## 왜 V1이 아니라 V2인가 (리더가 판단을 위임한 지점)
--
-- 필수 조치 D는 "Phase 1 첫 Flyway에 additive로 추가"를 요구했고, V1과 V2 중 어디에
-- 넣을지는 구현자 판단으로 남겼다. **V2가 맞다.** 근거 셋:
--
-- 1. V1의 정의는 "Python 스키마 재현"이다(계획 §4.2-3). Kotlin 신규 컬럼이 들어가면
--    그 정의가 깨지고, V1이 무엇을 재현하는지 아무도 말할 수 없게 된다.
-- 2. **결정적인 이유** — 계획 §4.2-4는 기존 Alembic DB를 "schema checksum이 일치할 때만
--    Flyway baseline version 1로 기록"하라고 한다. baseline은 V1이 이미 적용된 것으로
--    간주하고 건너뛴다. `encryption_scheme` 이 V1 안에 있으면 그런 DB에서는 컬럼이
--    **영원히 만들어지지 않는다.** V2에 두면 baseline 직후 정상적으로 적용된다.
-- 3. 계획 §4.2-5가 "Kotlin 전용 변경은 V2부터 추가한다"고 이미 명시했다.
--
-- ## additive 규칙
--
-- 기존 컬럼을 지우거나 이름을 바꾸거나 타입을 좁히지 않는다(계획 §4.2). 이 스크립트는
-- NOT NULL + DEFAULT 컬럼 추가뿐이라 Python 런타임이 이 DB를 계속 읽고 쓸 수 있다 —
-- SQLAlchemy 모델에 없는 컬럼은 INSERT 문에 나타나지 않고 DEFAULT가 채운다.
-- Phase 7 관찰 기간에 Python으로 롤백해도 이 컬럼이 걸림돌이 되지 않는다는 뜻이다.
--
-- ## 값은 관찰 기간 내내 'fernet-v1' 로 고정한다
--
-- 계획 §4.3-5가 "향후 암호 방식 변경을 위해 encryption_scheme 과 key_version 을 함께
-- 관리한다"고 요구한다. 지금 컬럼을 넣는 이유는 방식을 바꾸기 위해서가 아니라,
-- 바꿔야 할 때 **어떤 행이 어떤 방식으로 암호화됐는지 알 수 있게** 하기 위해서다.
-- Fernet 토큰 자체에는 그 정보가 없다.
-- AEAD 전환은 Phase 8 이후 단일 런타임 상태에서 별건으로 다룬다.

ALTER TABLE documents
    ADD COLUMN encryption_scheme character varying(16) DEFAULT 'fernet-v1' NOT NULL;

ALTER TABLE conversions
    ADD COLUMN encryption_scheme character varying(16) DEFAULT 'fernet-v1' NOT NULL;

-- 알 수 없는 방식 이름이 조용히 들어가는 것을 막는다. 새 방식을 도입할 때는 이 제약을
-- 먼저 늘리는 마이그레이션을 쓰게 되므로, 스키마 이력에 전환 시점이 남는다.
ALTER TABLE documents
    ADD CONSTRAINT ck_documents_encryption_scheme_valid
        CHECK (encryption_scheme IN ('fernet-v1'));

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_encryption_scheme_valid
        CHECK (encryption_scheme IN ('fernet-v1'));
