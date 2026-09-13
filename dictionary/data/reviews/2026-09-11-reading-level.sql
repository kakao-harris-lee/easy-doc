-- 원천 CSV는 보존한다. 정본 재생성 시 이 검수 결정을 내보내기 전에 적용한다.
-- rink의 의미 한정이 표면형 색인에서 사라져 웹 링크까지 경기장으로 바뀌었다.
BEGIN;
UPDATE entries
SET status = 'deprecated',
    review_note = '2026-09-11: rink 의미 한정이 사라져 웹 링크를 경기장으로 오치환. 문맥 구분 전까지 배포 제외.'
WHERE term = '링크' AND easy_term IN ('경기장', '스케이트장');

UPDATE entries
SET easy_term = '살다',
    caution = '문장에 맞게 활용하세요. 거주하는 주민은 사는 주민으로 씁니다. 사는 것을 하는으로 끼워 넣지 마세요.',
    review_note = '2026-09-11: 사는 것의 축자 삽입 비문을 막기 위해 동사 뜻풀이와 문장 예문으로 수정.',
    checksum = '154dc4540bcd15db'
WHERE term = '거주' AND easy_term IN ('사는 것', '살다');

UPDATE examples
SET before_text = '해당 지역에 거주하는 주민이 신청할 수 있습니다.',
    after_text = '해당 지역에 사는 주민이 신청할 수 있습니다.',
    note = '2026-09-11: Codex가 기존 합성 비문을 수정. 사람 검수 전이므로 프롬프트 예문에서는 제외.',
    is_golden = 0
WHERE entry_id IN (SELECT id FROM entries WHERE term = '거주');
COMMIT;
