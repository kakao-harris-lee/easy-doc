-- grade_3_4 원가 측정 1단계: 파일럿 원장 읽기 전용 조회.
-- 계획: docs/plans/2026-10-22-grade-3-4-cost-measurement.md
-- 실행 절차: docs/plans/2026-10-22-grade-3-4-cost-measurement-handoff.md
-- 결과는 집계값만 나온다. 원장에는 본문이 없다.

BEGIN READ ONLY;
SET LOCAL statement_timeout = '60s';

\echo '== ① 표본 크기와 단가 기록 여부'
SELECT reading_level, purpose, model, count(*) AS calls,
       count(*) FILTER (WHERE estimated_cost_usd IS NULL) AS cost_unknown,
       min(called_at)::date AS first_day, max(called_at)::date AS last_day
FROM llm_calls
WHERE called_at >= '2026-09-25' AND purpose IN ('convert', 'repair', 'reconvert')
GROUP BY 1, 2, 3
ORDER BY 1, 2, 3;

\echo '== ② 완료된 최초 변환(변환+보정) 1건 기준, 모델·수준별 전체(0: 전체)와 길이 구간별'
WITH conv AS (
  SELECT l.conversion_id, l.model, l.reading_level, max(l.document_char_count) AS chars,
         sum(l.estimated_cost_usd) AS cost_usd,
         sum(l.input_tokens) AS in_tok, sum(l.output_tokens) AS out_tok,
         bool_or(l.purpose = 'repair') AS repaired, sum(l.latency_ms) AS latency_ms
  FROM llm_calls l
  JOIN conversions c ON c.id = l.conversion_id AND c.status = 'done'
  WHERE l.called_at >= '2026-09-25' AND l.purpose IN ('convert', 'repair')
  GROUP BY l.conversion_id, l.model, l.reading_level
  HAVING count(*) FILTER (WHERE l.estimated_cost_usd IS NULL) = 0
), charged AS (
  SELECT *, CASE WHEN reading_level = 'grade_3_4'
                 THEN ceil(ceil(chars / 100.0) * 1.2) ELSE ceil(chars / 100.0) END AS units,
         CASE WHEN chars <= 1000 THEN '1: ~1천' WHEN chars <= 3000 THEN '2: ~3천'
              WHEN chars <= 7000 THEN '3: ~7천' ELSE '4: 7천~' END AS length_band
  FROM conv
)
SELECT model, reading_level, coalesce(length_band, '0: 전체') AS band,
       count(*) AS conversions,
       round(sum(cost_usd) / (sum(units) / 10.0), 5) AS usd_per_charged_credit,
       round(sum(cost_usd) / (sum(chars) / 1000.0), 5) AS usd_per_1k_chars,
       round(avg(out_tok::numeric / chars), 3) AS out_tokens_per_char,
       round(avg(in_tok)) AS avg_input_tokens_per_conversion,
       round(avg(repaired::int), 2) AS repair_rate,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY latency_ms) AS p50_latency_ms,
       max(latency_ms) AS max_latency_ms
FROM charged
GROUP BY GROUPING SETS ((model, reading_level, length_band), (model, reading_level))
ORDER BY 1, 3, 2;

\echo '== ③ 문단 재변환 1회 기준(근사: 같은 변환·같은 단위 길이로 3분 안에 이어진 행을 재변환 1회로 묶음)'
WITH r AS (
  SELECT conversion_id, model, reading_level, char_count, called_at, estimated_cost_usd,
         CASE WHEN called_at - lag(called_at) OVER w < interval '3 minutes' THEN 0 ELSE 1 END AS starts
  FROM llm_calls
  WHERE purpose = 'reconvert' AND called_at >= '2026-09-25' AND estimated_cost_usd IS NOT NULL
  WINDOW w AS (PARTITION BY conversion_id, char_count ORDER BY called_at)
), e AS (
  SELECT *, sum(starts) OVER (PARTITION BY conversion_id, char_count ORDER BY called_at) AS event_no
  FROM r
), ev AS (
  SELECT conversion_id, char_count, event_no, model, reading_level,
         sum(estimated_cost_usd) AS cost_usd, count(*) AS calls
  FROM e
  GROUP BY 1, 2, 3, 4, 5
)
SELECT model, reading_level, count(*) AS reconversions,
       round(avg(calls), 2) AS calls_per_reconversion,
       round(sum(cost_usd) / (sum(CASE WHEN reading_level = 'grade_3_4'
             THEN ceil(ceil(char_count / 100.0) * 1.2) ELSE ceil(char_count / 100.0) END) / 10.0), 5)
         AS usd_per_charged_credit,
       round(avg(char_count)) AS avg_unit_chars,
       round(percentile_cont(0.5) WITHIN GROUP (ORDER BY cost_usd)::numeric, 5) AS p50_cost_usd,
       round(percentile_cont(0.95) WITHIN GROUP (ORDER BY cost_usd)::numeric, 5) AS p95_cost_usd
FROM ev
GROUP BY 1, 2
ORDER BY 1, 2;

ROLLBACK;
