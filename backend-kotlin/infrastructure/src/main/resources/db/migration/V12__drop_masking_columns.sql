-- 마스킹 제거(제품 결정, 2026-09-07): 이 서비스가 변환하는 문서는 공공기관이 이미
-- 공개 배포한 안내문이라 개인정보를 담지 않는다. 주민등록번호·카드번호 마스킹과 그
-- 자리표시자 대응표·복원 로직을 코드에서 전부 걷어냈고, 이 두 컬럼이 그 유일한 흔적이다.
--
-- `masked_items_encrypted` — 자리표시자↔원문 대응표를 담던 봉인 열. 애플리케이션이 더
-- 쓰지도 읽지도 않는다.
-- `missing_placeholders` — 변환 후 자리표시자 유실 라벨만 담던 평문 jsonb 열. 자리표시자
-- 개념 자체가 없어졌으므로 함께 없앤다.
--
-- `masked_items_encrypted`는 nullable, `missing_placeholders`는 NOT NULL DEFAULT '[]'였다
-- (V1 참고) — 둘 다 DROP COLUMN이라 기존 행의 NULL 여부와 무관하게 안전하다.
ALTER TABLE conversions
    DROP COLUMN masked_items_encrypted,
    DROP COLUMN missing_placeholders;
