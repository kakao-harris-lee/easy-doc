-- 변환 완료 메일 알림(P0-3)의 멱등 표시. 2026-09-04.
--
-- worker 가 완료 결과를 커밋한 **뒤**, 트랜잭션 밖에서 메일을 보낸다
-- (`ConversionCompletedNotifier`). 발송에 성공한 뒤에만 이 컬럼을 채워, 같은 변환에
-- 알림 로직이 다시 호출돼도(방어적 재실행) 메일을 다시 보내지 않는다 — 실패한 발송은
-- 채우지 않아, 나중에 재시도 도구가 생기면 다시 집을 수 있게 남겨 둔다
-- (`ConversionCompletedNotifier` KDoc).
ALTER TABLE conversions
    ADD COLUMN notified_at timestamp with time zone;
