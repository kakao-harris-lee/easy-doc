-- 원천 CSV는 보존하고 정본 재생성 시 내보내기 전에 적용한다.
-- 일반 뜻을 다듬은 결정이며 사람 검수 완료 또는 개별 사업 자격 검증을 뜻하지 않는다.
BEGIN;
UPDATE entries SET easy_term = '이미 준 돈이나 물건을 다시 거두어들임',
    definition = '이미 준 돈이나 물건을 다시 거두어들이는 일입니다.',
    review_note = '2026-09-12: Codex가 부자연스러운 되거둠 후보를 문맥 설명으로 수정. 사람 검수 완료 표시는 변경하지 않음.',
    checksum = '2b22608a40a1ac51'
WHERE id = 1585 AND term_norm = '환수';
UPDATE entries SET definition = '가구를 소득이 적은 순서대로 놓았을 때 가운데에 있는 소득입니다. 복지 안내에서 쓰는 기준 중위소득은 이를 바탕으로 나라가 따로 정해 발표하는 값입니다.',
    caution = '중위소득과 기준 중위소득을 구분하세요. 원문에 있는 비율과 대상 조건을 그대로 유지하세요.',
    review_note = '2026-09-12: 통계적인 중간값과 나라가 고시하는 기준 중위소득의 정의 혼합 수정. 사람 검수 완료 표시는 변경하지 않음.',
    checksum = '62d7425f3c231535'
WHERE id = 1771 AND term_norm = '중위소득';
UPDATE entries SET easy_term = '의료 기구나 기술을 써서 몸의 병이나 상처를 치료하는 일',
    definition = '의료 기구나 기술을 써서 몸의 병이나 상처를 치료하는 일입니다.',
    review_note = '2026-09-12: Codex가 고급 사전의 의학적인 처치라는 정의를 일상적인 설명으로 수정. 사람 검수 완료 표시는 변경하지 않음.',
    checksum = '0f3fe0085ba74f41'
WHERE id = 1855 AND term_norm = '시술';
COMMIT;
