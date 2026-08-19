-- 저장 암호화 방식을 **표준 AEAD** 로 확정한다 — V2 가 남긴 `fernet-v1` 을 걷어낸다.
--
-- ## 왜 지금인가
--
-- `migration-safety-gate` I-7 검증 5 는 *"처음부터 새 scheme 값으로 쓰고 `fernet-v1` 을
-- 쓰지 않는다"* 를 요구한다. V2(Phase 1)는 그 반대로 `DEFAULT 'fernet-v1'` +
-- `CHECK IN ('fernet-v1')` 을 넣었고, privacy-gate 는 그것을 **잠정 위반**으로 적으면서
-- 해제 조건을 *"Phase 4 에서 저장 암호화를 배선하기 **전에**"* 로 못박았다
-- (`docs/migration/_workspace/reviews/03_security-phase3-close_privacy-gate.md` §5-4).
-- 이 스크립트가 그 해제 조건 ⑴ 이다.
--
-- 지금까지 차단이 아니었던 이유도 같은 문서에 있다: 이 컬럼을 쓰는 Kotlin 코드가 0 이고,
-- 두 테이블에 행을 쓰는 경로가 없어 **잘못된 이름이 붙은 행이 존재할 수 없었다.**
-- 그 전제가 무너지는 순간이 Phase 4 의 첫 INSERT 이고, 그래서 그 앞에 둔다.
--
-- ## V2 의 근거가 왜 전부 무효인가
--
-- V2 주석은 셋을 들었다 — ⑴ Python 런타임이 이 DB 를 계속 읽고 쓴다 ⑵ Phase 7 관찰
-- 기간에 Python 으로 롤백한다 ⑶ 값은 관찰 기간 내내 `fernet-v1` 로 고정한다.
-- 2026-08-12 사용자 결정으로 Python 은 **폐기 대상**이 되고 롤백 대상이 사라졌다
-- (master-plan 6.2 · 계획 §4.3 2차 개정 · §9 결정 2·3). ⑶ 은 규칙 자체가 삭제됐다.
-- V2 파일의 그 세 문단도 함께 정정했다 — 근거가 죽은 주석을 남겨 두면 다음 사람이
-- 그것을 읽고 같은 결론에 다시 도달한다.
--
-- **V2 파일을 고치면 Flyway 체크섬이 바뀐다.** 이미 V2 를 적용한 DB 는 다음 기동에서
-- `Migration checksum mismatch` 로 멈춘다. 지금 그것을 감수하는 근거는 하나다 —
-- **적용된 DB 가 일회용 테스트 컨테이너 말고는 없다**(§9 결정 2: 보존할 운영·파일럿 DB
-- 없음). 로컬 개발 DB 를 오래 쓰고 있었다면 다시 만들어야 한다. 배포 이후에는 이 방식을
-- 쓸 수 없고, 그때는 정정도 새 마이그레이션으로 한다.
--
-- ## additive 규칙과의 관계 (계획 §4.2)
--
-- 컬럼을 지우거나 이름을 바꾸거나 타입을 좁히지 않는다. 바뀌는 것은 **CHECK 의 값 목록과
-- DEFAULT** 뿐이다. 값 목록이 좁아지는 방향이라 §4.2 의 「additive 만」에서 벗어나 보이는데,
-- 그 조항이 지키려던 것은 *"Python 런타임이 이 DB 를 계속 쓸 수 있어야 한다"* 였고 그
-- 전제가 위에서 사라졌다. 아래 DO 블록이 **선언 대신 실측으로** 안전을 확인한다.

-- 「행이 0 이라 안전하다」를 적어 두지 않고 **확인한다.**
-- 남아 있는 행이 있으면 CHECK 추가가 어차피 실패하지만, 그때 나오는 메시지는
-- "check constraint is violated by some row" 라 무엇을 해야 하는지가 없다.
DO $$
DECLARE
    stale bigint;
BEGIN
    SELECT (SELECT count(*) FROM documents) + (SELECT count(*) FROM conversions) INTO stale;
    IF stale > 0 THEN
        RAISE EXCEPTION
            'documents·conversions 에 행이 % 건 있다. 이 마이그레이션은 fernet-v1 로 적힌 행이 '
            '하나도 없다는 전제 위에 서 있다(privacy-gate 03 §5-4). 그 행들의 암호문이 실제로 '
            '무엇인지 먼저 판정하고, 재암호화 마이그레이션을 따로 써라.', stale;
    END IF;
END $$;

-- ── DEFAULT 를 없앤다 ────────────────────────────────────────────────────────────
--
-- **DEFAULT 가 안전장치를 우회한다**는 것이 privacy-gate 판정의 핵심이었다. CHECK 는
-- 모르는 이름을 막지만, 컬럼을 명시하지 않은 INSERT 에는 DEFAULT 가 조용히 값을 채운다 —
-- 그 값은 데이터에 대해 **거짓**일 수 있다(암호문은 AEAD 인데 이름이 Fernet).
--
-- DEFAULT 를 새 이름으로 바꾸는 대신 **없애는** 이유: 이름을 바꿔도 "쓰는 쪽이 방식을
-- 적지 않아도 된다"는 구조는 그대로다. 다음 방식이 생기는 날 같은 사고가 반복된다.
-- 없애면 컬럼을 빠뜨린 INSERT 가 NOT NULL 위반으로 **즉시 시끄럽게** 실패한다.
ALTER TABLE documents ALTER COLUMN encryption_scheme DROP DEFAULT;
ALTER TABLE conversions ALTER COLUMN encryption_scheme DROP DEFAULT;

-- `key_version` 도 같은 이유로 DEFAULT 를 없앤다. V1 의 `DEFAULT 1` 은 Python 이
-- `CURRENT_KEY_VERSION = 1` 하나만 쓰던 시절의 값이라, 키를 회전한 뒤에도 쓰는 쪽이
-- 세대를 빠뜨리면 **v1 로 적힌 v2 암호문**이 생긴다. 그 행은 영원히 열리지 않는다
-- (associated data 에 키 세대가 실리므로 태그 검증부터 실패한다).
-- NOT NULL 은 그대로 유지한다 — 암호문이 아직 없는 행(대기 중 변환)도 앞으로 쓸 세대를
-- 적어 두는 편이, 나중에 NULL 을 해석하는 규칙을 만드는 것보다 낫다.
ALTER TABLE documents ALTER COLUMN key_version DROP DEFAULT;
ALTER TABLE conversions ALTER COLUMN key_version DROP DEFAULT;

-- ── CHECK 도메인을 AEAD 이름으로 옮긴다 ──────────────────────────────────────────
--
-- `fernet-v1` 을 목록에 남겨 두지 않는다. 남기면 그 이름으로 쓰는 경로가 다시 열리고,
-- I-7 이 금지한 것이 바로 그 값이다. 옛 이름을 읽어야 할 행은 존재하지 않는다(위 DO 블록).
--
-- 값이 `aes256gcm-v1` 인 이유: `aes-gcm` 만으로는 AES-128 과 AES-256 이 구분되지 않고,
-- 이 컬럼의 존재 이유가 **어떤 행이 어떤 방식으로 암호화됐는지 아는 것**이다.
-- 코드 쪽 정본은 `core/crypto/StoredContent.kt` 의 `EncryptionScheme.AES_256_GCM_V1` 이고,
-- 두 값이 갈리면 `EncryptionSchemeSchemaTest` 가 빨개진다.
ALTER TABLE documents
    DROP CONSTRAINT ck_documents_encryption_scheme_valid;
ALTER TABLE conversions
    DROP CONSTRAINT ck_conversions_encryption_scheme_valid;

ALTER TABLE documents
    ADD CONSTRAINT ck_documents_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1'));

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1'));
