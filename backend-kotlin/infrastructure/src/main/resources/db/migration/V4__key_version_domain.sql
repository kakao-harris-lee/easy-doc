-- `key_version` 에 도메인 제약을 준다 — **같은 목적의 두 컬럼인데 방어가 비대칭이었다.**
--
-- ## 무엇이 열려 있었나 (게이트 25 X8 / privacy-gate F-4)
--
-- `encryption_scheme` 에는 CHECK 가 있는데 `key_version` 에는 아무 제약이 없었다.
-- 실측: `key_version = -1` 로 **INSERT 가 성공**한다. `write-key-version: -1` 로 설정하면
-- 암호화도 성공하고 그 값이 봉투에 그대로 실린다.
--
-- 그 행이 왜 위험한가 — 세대 번호는 associated data 에 들어가고 복호화 시점의 키 조회 키다.
-- 설정에 있을 수 없는 번호가 적힌 행은 **영원히 열리지 않는다.** `encryption_scheme` 쪽은
-- 같은 사고를 CHECK 로 막고 있었으므로, 여기만 열어 둘 근거가 없다.
--
-- 리더 판정(2026-08-19): **조립 시점 검증이 아니라 V4 CHECK** 로 넣는다. 조립 검증만으로는
-- 앱을 거치지 않는 쓰기(운영 SQL·마이그레이션·다른 프로세스)를 막지 못하고, 이 컬럼은
-- 틀린 값이 들어가는 순간 그 행이 복구 불가가 되는 종류다. 앱 쪽 검증도 함께 넣었지만
-- (`CryptoConfiguration` 기동 자기점검) 그것은 **발견 시점을 당기는** 장치이고, 마지막
-- 방어선은 여기다.
--
-- ## 왜 `> 0` 인가
--
-- 컬럼 타입이 `smallint` 라 상한(32767)은 이미 타입이 막는다 — 넘는 값은 저장에서 깨진다.
-- 열려 있던 것은 **하향**(0 과 음수)뿐이고, 세대 번호는 1 부터 센다
-- (`CryptoConfiguration.EncryptionProperties.writeKeyVersion` 기본값 1).
--
-- ## additive 규칙 (계획 §4.2)
--
-- 컬럼을 지우거나 이름을 바꾸거나 타입을 좁히지 않는다. 제약 하나를 더할 뿐이다.
-- 기존 행에 대해서는 아래 확인이 먼저 돈다 — CHECK 추가가 위반 행에서 실패하면 나오는
-- 메시지에 무엇을 해야 하는지가 없기 때문이다(V3 와 같은 이유).

DO $$
DECLARE
    invalid bigint;
BEGIN
    SELECT (SELECT count(*) FROM documents WHERE key_version <= 0)
         + (SELECT count(*) FROM conversions WHERE key_version <= 0) INTO invalid;
    IF invalid > 0 THEN
        RAISE EXCEPTION
            'key_version 이 0 이하인 행이 % 건 있다. 그 행의 암호문은 설정에 있을 수 없는 '
            '키 세대를 가리키므로 열리지 않는다. 어느 세대로 쓴 것인지 먼저 판정하고 '
            '재암호화 마이그레이션을 따로 써라.', invalid;
    END IF;
END $$;

ALTER TABLE documents
    ADD CONSTRAINT ck_documents_key_version_positive
        CHECK (key_version > 0);

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_key_version_positive
        CHECK (key_version > 0);
