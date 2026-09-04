-- 파일럿 게이트 ① 판정용 집계. `docs/pilot-runbook.md` 「게이트 ① 판정」의 「집계」가
-- 부르는 스크립트다. 호출 방식도 그 절에 적힌 대로다.
--
--   docker compose -f compose.yml exec -T postgres \
--     psql -U postgres -d easydoc -f - < scripts/pilot-report.sql
--
-- **읽기 전용이다.** SELECT 만 쓴다 — 임시 테이블도, CREATE·UPDATE 도 없다. 파일럿을
-- 운영 중인 DB에 대고 도는 스크립트라 집계가 표본을 건드리면 안 된다.
--
-- **자유 의견(`conversion_feedback.comment_encrypted`)을 읽지 않는다.** 그 칸은 AEAD 로
-- 봉해 두었고(V2 주석: 검수자가 문서 본문 조각을 그대로 붙여 넣는 일이 실제로 일어난다),
-- 게이트 판정에 필요한 것은 수치뿐이다. 지표를 평문 숫자로 남긴 이유가 바로 이것 —
-- 집계가 건별 복호화 없이 SQL 하나로 끝나게 하려는 선택이다. 자유 의견 열람이 필요하면
-- 소유자 토큰으로 화면에서 본다.
--
-- **통과 기준(runbook 「통과 기준」 표를 그대로 인용한다).**
--   > ### 통과 기준 — **제안값이며 착수 전 승인이 필요하다**
--   > 아래 숫자는 아직 승인되지 않았다. 파일럿을 시작하기 전에 사용자가 확정하고 이 절을 고친다.
--   >
--   > | # | 기준 | 제안값 | 판정 |
--   > |---|---|---|---|
--   > | 1 | 배포 의향이 `as_is`(그대로 쓸 수 있다) 또는 `with_edits`(조금 고쳐서 쓰겠다)인 건수 | 10건 중 **8건 이상** | 스크립트 |
--   > | 2 | 품질 만족도 평균 | **3.5 이상** | 스크립트 |
--   > | 3 | 문서 1건 소요 시간의 중앙값이 기존 방식 소요 대비 유의미하게 짧을 것 | — | **사람** |
--
-- 아래 리터럴 8 과 3.5 는 저 표에서 온 값이고, **아직 승인 대기 중인 제안값**이다.
-- 값이 확정되면 runbook 의 표와 이 파일을 함께 고친다 — 한쪽만 고치면 판정 기준이
-- 두 곳에서 갈라진다.
--
-- **기준 ③ 은 자동 판정하지 않는다.** 대조군인 「기존 방식 소요」는 기관 인터뷰로만
-- 들어오고(runbook 「기존 방식 소요(인터뷰 기록)」 표) 이 DB 에 없다. 스크립트는
-- 중앙값만 내고 충족·미충족을 찍지 않는다 — 판단은 사람이 한다.
--
-- **표본 수를 항상 함께 낸다.** 표본이 0건이면 평균과 중앙값은 NULL 이 되는데, 그 NULL 이
-- 조용히 「통과」로 읽히면 안 된다. 각 기준 줄에 표본 수를 붙이고, 0건은 판정이 아니라
-- 「판정 불가」로 적는다.
--
-- 집계 범위는 `conversion_feedback` 전체다. 파일럿 DB 라 표가 파일럿 표본 그 자체이고,
-- 기간을 좁혀야 하면 각 SELECT 의 FROM 절에 `WHERE submitted_at >= ...` 를 더한다
-- (`ix_conversion_feedback_submitted_at` 이 그 조건을 받는 인덱스다). 대신 결과 ① 이
-- 표본의 제출 시각 범위를 함께 내므로, 어느 기간이 집계됐는지는 출력으로 확인한다.


-- --- ① 처리 현황 --------------------------------------------------------------
-- 변환 건수는 `conversions`, 표본 수와 참여자 수는 `conversion_feedback` 에서 온다.
-- 두 수가 어긋날 수 있고 그것이 정상이다 — 문서는 기본 30일 보존 뒤 파기되고 그 삭제가
-- documents → conversions 로 이어지지만, 피드백 표에는 FK 가 없어 판정 근거가 남는다
-- (V2 주석 「FK 를 걸지 않는다」). 즉 「변환 건수 < 피드백 표본 수」는 오류가 아니라
-- 보존 만료가 지나갔다는 뜻이다.
SELECT
    '① 처리 현황' AS "구분",
    (SELECT count(*) FROM conversions) AS "변환_건수_전체",
    (SELECT count(*) FROM conversions WHERE status = 'done') AS "변환_완료",
    (SELECT count(*) FROM conversions WHERE status = 'failed') AS "변환_실패",
    (SELECT count(*) FROM conversions WHERE status IN ('pending', 'processing')) AS "변환_진행중",
    (SELECT count(*) FROM documents) AS "문서_건수_보존중",
    (SELECT count(*) FROM conversion_feedback) AS "피드백_표본_수",
    (SELECT count(DISTINCT user_id) FROM conversion_feedback) AS "참여_사용자_수",
    (SELECT min(submitted_at) FROM conversion_feedback) AS "표본_최초_제출",
    (SELECT max(submitted_at) FROM conversion_feedback) AS "표본_최종_제출";


-- --- ② 배포 의향 분포 (기준 ①의 표본) -----------------------------------------
-- 세 값을 VALUES 로 적어 두고 LEFT JOIN 한다 — 0건인 의향도 「0건」으로 보여야 하기
-- 때문이다. GROUP BY 만 쓰면 아무도 고르지 않은 값은 행 자체가 사라져서, 표본이 없는
-- 것인지 그 의향이 0건인 것인지 구분되지 않는다.
-- 값 목록은 V2 의 ck_conversion_feedback_publish_intent_valid 와 같아야 한다.
WITH intents(intent, label, usable) AS (
    VALUES ('as_is',      '그대로 쓸 수 있다',   true),
           ('with_edits', '조금 고쳐서 쓰겠다', true),
           ('not_usable', '쓸 수 없다',         false)
),
counted AS (
    SELECT publish_intent, count(*) AS n
    FROM conversion_feedback
    GROUP BY publish_intent
)
SELECT
    '② 배포 의향 분포' AS "구분",
    i.intent AS "배포_의향",
    i.label AS "설명",
    CASE WHEN i.usable THEN '기준①에 포함' ELSE '-' END AS "기준①_집계",
    coalesce(c.n, 0) AS "건수"
FROM intents i
LEFT JOIN counted c ON c.publish_intent = i.intent
ORDER BY array_position(ARRAY['as_is', 'with_edits', 'not_usable'], i.intent);


-- --- ③ 중앙값 지표 -------------------------------------------------------------
-- 중앙값은 percentile_cont(0.5) WITHIN GROUP (ORDER BY ...) 로 낸다.
--
-- 소요 시간은 기준 ③ 의 표본이지만 여기서 판정하지 않는다(머리 주석 참조).
-- 수정률은 edit_distance / easy_char_count 이고, NULLIF 로 분모 0 을 막는다. 두 지표 모두
-- 「표본 수」를 함께 내는데, 수정률 표본은 전체 표본보다 작을 수 있다 — 검수 수정본 없이
-- 제출된 피드백은 세 지표가 NULL 이고(V2 주석: 「수정률 0%」와 「측정 대상 아님」은 다른
-- 값이다) percentile_cont 가 그 행을 조용히 건너뛰기 때문이다.
--
-- **수정률이 빠진 이유는 하나가 아니다** (2026-09-04, `edit_distance_skip_reason` 컬럼 추가,
-- `V4__conversion_feedback_edit_distance_skip_reason.sql`). 검수 수정본 자체가 없는 경우
-- (`no_review`)와, 수정본은 있지만 셀 예산(`easydoc.feedback.edit-distance-cell-budget`)을
-- 넘어 계산을 포기한 경우(`budget_exceeded`)가 여기서는 구분되지 않고 함께 빠진다 — 아래
-- ③-1 이 그 둘을 나눠 보여준다.
WITH sample AS (
    SELECT
        minutes_spent,
        CASE
            WHEN edit_distance IS NULL THEN NULL
            ELSE edit_distance::numeric / NULLIF(easy_char_count, 0)
        END AS edit_ratio
    FROM conversion_feedback
)
SELECT
    '③ 중앙값 지표' AS "구분",
    m.metric AS "지표",
    m.value AS "중앙값",
    m.n AS "표본_수",
    m.note AS "비고"
FROM (
    SELECT
        1 AS ord,
        '문서 1건 소요 시간(분)' AS metric,
        coalesce(
            round(percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes_spent)::numeric, 1)::text,
            '표본 없음'
        ) AS value,
        count(minutes_spent) AS n,
        '기준 ③ — 판단은 사람이 한다. 기존 방식 소요는 기관 인터뷰 값이라 스크립트가 알 수 없다.' AS note
    FROM sample
    UNION ALL
    SELECT
        2,
        '수정률 (edit_distance / easy_char_count)',
        coalesce(
            round(percentile_cont(0.5) WITHIN GROUP (ORDER BY edit_ratio)::numeric * 100, 1)::text || '%',
            '표본 없음'
        ),
        count(edit_ratio),
        '참고 지표 — 통과 기준 없음. 검수 수정본이 없는 제출은 표본에서 빠진다.'
    FROM sample
) m
ORDER BY m.ord;


-- --- ③-1 수정률 표본 구성 (2026-09-04 추가) -------------------------------------
-- 위 ③의 수정률 표본 수가 전체 표본보다 작을 때, **왜 빠졌는지**를 나눠 보여준다.
-- `측정됨` = edit_distance IS NOT NULL(위 ③이 실제로 쓰는 표본). `검수본_없음`과
-- `예산_초과`는 edit_distance_skip_reason 의 두 값이다(`core/pilot/ConversionFeedback.kt`
-- 의 EditDistanceSkipReason). 세 수의 합이 항상 전체 표본 수와 같다 — 다르면 `EditMetrics`
-- 가 만들 수 없는 조합이 DB 에 들어간 것이므로 그 자체가 버그 신호다.
SELECT
    '③-1 수정률 표본 구성' AS "구분",
    count(*) FILTER (WHERE edit_distance IS NOT NULL) AS "측정됨",
    count(*) FILTER (WHERE edit_distance_skip_reason = 'no_review') AS "검수본_없음",
    count(*) FILTER (WHERE edit_distance_skip_reason = 'budget_exceeded') AS "예산_초과",
    count(*) AS "전체_표본"
FROM conversion_feedback;


-- --- ④ 기준 판정 --------------------------------------------------------------
-- 각 기준을 한 줄로 낸다. 표본 0건이면 「충족/미충족」이 아니라 「판정 불가」다 —
-- 빈 표에서 avg 는 NULL 이고 count(*) FILTER 는 0 인데, 둘 다 아무 판정도 뒷받침하지
-- 않는다. 표본이 10건에 못 미치는 경우도 비고에 적는다: 기준 ①의 임계값 8 은 「10건 중」
-- 8건이므로, 표본이 6건일 때의 「미충족」은 아직 판정이 아니라 미완이다.
--
-- **표본이 목표에 못 미칠 때 「충족」을 그대로 찍지 않는다.** 비고에만 「잠정이다」를
-- 적어 두면 판정 칸의 「충족」이 먼저 읽힌다 — 위에서 「NULL 이 조용히 통과로 읽히면
-- 안 된다」고 한 것과 같은 종류의 문제다. 그래서 뒤집힐 수 있는 충족은 판정 칸 자체를
-- 「잠정 충족」으로 적는다.
--
-- **기준 ① 에는 같은 처리를 하지 않는다 — 표본이 느는 방향의 단조성 때문이다.** 기준 ① 은
-- 건수 조건(`usable >= 8`)이고, **표본이 더해지는 방향으로는** `usable` 이 늘기만 한다. 즉
-- 표본 3건에서 8건을 이미 넘겼다면, 표본이 10건으로 늘어난다는 사실**만으로는** 그 충족이
-- 뒤집히지 않는다. 기준 ② 는 평균이라 다르다 — 3건 평균 5.0 은 남은 7건에 따라 3.5 아래로
-- 얼마든지 내려간다. 두 기준을 다르게 다루는 것은 실수가 아니라 이 사유이며, 사유 없이
-- 통일하지 않는다.
--
-- **그 단조성은 「표본 수」 축에서만 참이고 「시간」 축에서는 거짓이다.** 피드백 제출은 멱등
-- upsert 다 — 계약이 `PUT /conversions/{conversion_id}/feedback` 하나이고 V2 의 기본 키가
-- `conversion_id` 하나라, 한 변환에 행은 언제나 하나다. 같은 검수자가 같은 문서에 대해 답을
-- **바꿔 다시 제출**할 수 있고 `as_is` → `not_usable` 로 바뀌면 `usable` 은 **줄어든다.**
-- 그래서 여기서 찍는 「충족」은 **집계를 돌린 그 시점의 표**에 대한 판정이다: 새 표본이
-- 들어오는 것만으로는 뒤집히지 않지만, 기존 답이 바뀌면 달라질 수 있다. 실질 위험은 작다 —
-- 이 스크립트는 상태를 들고 있지 않고 읽기 전용이라, 다시 돌리면 그 시점의 표를 처음부터
-- 다시 센다. 고정된 것은 판정이 아니라 판정 방식이다.
-- (미충족 쪽은 두 기준 모두 판정 칸을 그대로 두고 비고가 맡는다. 미충족은 「아직 통과가
-- 아니다」라 조용히 통과로 읽힐 위험이 없고, 표본 미달이라는 사실은 비고 줄에 있다.)
WITH s AS (
    SELECT
        -- 목표 표본 수. 정본은 runbook 「대상과 규모」의 「실제 업무 문서 10건」이고,
        -- **이 ④ 블록에서 목표 표본 수를 적는 자리는 여기 하나다** — 아래 판정·비고와
        -- 기준 ① 임계값 표시 문자열이 전부 이 값을 참조한다. (⑤ 경고 첫 줄의 「표본 10건」과
        -- 「8/10」은 master-plan §7 문장을 인용한 것이라 이 값이 아니다. 목표를 바꾸면 그
        -- 인용은 인용대로 두고 §7 과 runbook 을 함께 본다.)
        10 AS target_n,
        count(*) AS n,
        count(*) FILTER (WHERE publish_intent IN ('as_is', 'with_edits')) AS usable,
        avg(quality_score) AS avg_quality,
        percentile_cont(0.5) WITHIN GROUP (ORDER BY minutes_spent) AS median_minutes
    FROM conversion_feedback
)
SELECT
    '④ 기준 판정' AS "구분",
    j.criterion AS "기준",
    j.threshold AS "임계값(제안·미승인)",
    j.actual AS "실측",
    j.n AS "표본_수",
    j.verdict AS "판정",
    j.note AS "비고"
FROM (
    SELECT
        1 AS ord,
        '① 배포 의향이 as_is 또는 with_edits 인 건수' AS criterion,
        s.target_n || '건 중 8건 이상' AS threshold,
        s.usable::text || '건' AS actual,
        s.n AS n,
        CASE
            WHEN s.n = 0 THEN '판정 불가 (표본 0건)'
            WHEN s.usable >= 8 THEN '충족'
            ELSE '미충족'
        END AS verdict,
        CASE
            WHEN s.n = 0 THEN '표본이 없다. 피드백 제출 0건.'
            WHEN s.n < s.target_n THEN '표본 ' || s.n || '건 / ' || s.target_n
                || '건 — 파일럿 미완. 새 표본이 더해지는 것만으로는 충족이 뒤집히지 않지만(건수 조건의 단조성)'
                || ' 미충족은 아직 판정이 아니다. 검수자가 답을 고쳐 다시 내면 이 수는 줄 수도 있다 —'
                || ' 이 판정은 지금 이 표에 대한 것이고, 다시 돌리면 그때의 표를 다시 센다.'
            ELSE '표본 ' || s.n || '건. 이 판정은 지금 이 표에 대한 것이다 — 피드백은 멱등 upsert 라'
                || ' 기존 답이 바뀌면 건수도 바뀐다. 확정할 때는 다시 돌려 그 시점 값으로 본다.'
        END AS note
    FROM s
    UNION ALL
    SELECT
        2,
        '② 품질 만족도 평균 (1~5)',
        '3.5 이상',
        coalesce(round(s.avg_quality, 2)::text, '표본 없음'),
        s.n,
        -- 평균은 표본이 늘면 어느 쪽으로도 움직인다. 표본이 목표에 못 미치는 동안의
        -- 충족은 최종 판정이 아니므로 판정 칸에 그렇게 적는다(머리 주석 참조).
        CASE
            WHEN s.n = 0 THEN '판정 불가 (표본 0건)'
            WHEN s.avg_quality >= 3.5 AND s.n < s.target_n THEN '잠정 충족'
            WHEN s.avg_quality >= 3.5 THEN '충족'
            ELSE '미충족'
        END,
        CASE
            WHEN s.n = 0 THEN '표본이 없다. 평균이 NULL 인 것은 통과가 아니다.'
            WHEN s.n < s.target_n THEN '표본 ' || s.n || '건 / ' || s.target_n
                || '건 — 파일럿 미완. 평균은 남은 표본으로 뒤집힐 수 있다.'
            ELSE '표본 ' || s.n || '건.'
        END
    FROM s
    UNION ALL
    SELECT
        3,
        '③ 소요 시간 중앙값이 기존 방식 대비 유의미하게 짧을 것',
        '기관별 인터뷰 값 (스크립트가 알 수 없음)',
        coalesce(round(s.median_minutes::numeric, 1)::text || '분', '표본 없음'),
        s.n,
        '사람이 판단',
        '자동 판정하지 않는다. runbook 「기존 방식 소요(인터뷰 기록)」 표의 1건 소요와 대조한다.'
    FROM s
) j
ORDER BY j.ord;


-- --- ⑤ 경고 -------------------------------------------------------------------
-- master-plan §7 의 경고를 판정하는 사람 눈앞에 남긴다. 출력 마지막 줄인 이유는
-- 위 ④ 의 「충족」을 보고 창을 닫기 전에 읽혀야 하기 때문이다.
SELECT '⑤ 경고' AS "구분", w.line AS "내용"
FROM (
    VALUES
        (1, '표본 10건은 통계적으로 작다 (master-plan §7). 8/10과 7/10의 차이는 한 사람의 그날 기분만큼도 안정적이지 않다 — 경계값이면 표본을 늘리거나 다시 돌린다. 자동 재시도로 가리지 않는다.'),
        (2, '임계값 8건·3.5 는 runbook 의 제안값이며 아직 승인되지 않았다. 착수 전 확정한 값과 다르면 이 스크립트가 아니라 runbook 표가 정본이다.'),
        (3, '이 집계는 자유 의견(comment_encrypted)을 읽지 않는다. 열람이 필요하면 소유자 토큰으로 화면에서 본다.')
) w(ord, line)
ORDER BY w.ord;
