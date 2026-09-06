-- P0-4 S8-1 — 표·목록 구조 힌트: 원본 단위 종류를 저장한다(계획 §1.2,
-- docs/plans/2026-09-06-p0-4-structure-hints.md).
--
-- 값은 `splitUnits(source_text)` 줄 수만큼의 한 글자 코드 문자열('B'=본문·'T'=표 칸·
-- 'L'=목록 항목)이다. `NULL` 은 이 조각 이전에 만든 문서를 뜻하고 애플리케이션이 전부 BODY 로
-- 읽는다(`StoredSourceText.structureOrBody`) — 백필하지 않는다. 원본 바이트를 다시 파싱해야만
-- 채울 수 있는 값이라, 옛 문서는 다시 올리면 된다.
ALTER TABLE documents
    ADD COLUMN source_unit_kinds text NULL;
