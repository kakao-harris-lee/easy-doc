-- P0-4 S4 — 문단 재변환 호출 예산(계획 §4 결정 3, docs/plans/2026-09-04-p0-4-paragraph-mapping-reconversion.md).
--
-- 두 평문 정수 열을 더한다. **암호화하지 않는다** — 담는 것은 호출 횟수뿐이고 개인정보가
-- 아니다(`missing_placeholders` 가 평문 jsonb 인 것과 같은 판단, V1 머리주석 참고).
--
-- `reconversion_calls_reserved` 는 지금 진행 중인 재변환들이 예약해 둔 몫(호출 전 +=2, 호출
-- 후 실제 사용량만 `used` 로 옮기고 나머지를 환불), `reconversion_calls_used` 는 실제로 나간
-- 호출의 누적 합이다. 예약 시점의 판정은 `used + reserved + 2 <= budget` 이고
-- (`JdbcConversionRepository.RESERVE_RECONVERSION_CALLS_SQL`), 그래서 두 열을 나눈다 — 하나로
-- 합치면 "지금 몇 회가 나갔는가"와 "지금 몇 회가 나갈 수 있는가"를 구분할 수 없다.
ALTER TABLE conversions
    ADD COLUMN reconversion_calls_reserved integer NOT NULL DEFAULT 0,
    ADD COLUMN reconversion_calls_used integer NOT NULL DEFAULT 0;

ALTER TABLE conversions
    ADD CONSTRAINT ck_conversions_reconversion_calls_reserved_non_negative
        CHECK (reconversion_calls_reserved >= 0),
    ADD CONSTRAINT ck_conversions_reconversion_calls_used_non_negative
        CHECK (reconversion_calls_used >= 0);
