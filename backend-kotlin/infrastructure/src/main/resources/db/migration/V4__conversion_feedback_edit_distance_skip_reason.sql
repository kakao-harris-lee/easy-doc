-- `conversion_feedback.edit_distance` 가 `null` 인 **사유**를 구분한다. 2026-09-04, Codex 가
-- PR #13 을 심사하며 잡은 결함(발견 1·4)의 수정이다.
--
-- ## 왜 필요한가
--
-- 2026-09-03 셀 예산(`easydoc.feedback.edit-distance-cell-budget`)을 도입하면서
-- `ConversionFeedbackService.EditMetrics.of` 가 `editedCharCount` 를 채운 채로
-- `editDistance` 만 `null` 로 두는 갈래(예산 초과)가 생겼다. 그런데 이 표의
-- `ck_conversion_feedback_edit_metrics_paired`(V2)는 `(edited_char_count IS NULL) =
-- (edit_distance IS NULL)` 를 강제한다 — 예산 초과 행은 그 CHECK 를 어겨 JDBC upsert 가
-- 매번 실패한다. 실물 DB 로는 한 번도 잰 적이 없었다(테스트가 인메모리 대역만 쓴 탓,
-- `ConversionFeedbackServiceTest` 「예산 초과는 거리만 비우고 글자 수는 남긴다」).
--
-- 고치는 김에 `edit_distance IS NULL` 의 **두 사유**(검수본 없음 · 예산 초과)를 컬럼으로
-- 남긴다 — 그래야 `scripts/pilot-report.sql` 이 「측정 안 됨」 표본이 왜 빠졌는지 구분해
-- 보여줄 수 있다(둘을 뭉개면 파일럿 운영자가 예산을 올려야 하는지 판단할 근거가 없다).
--
-- ## 새 컬럼과 되짚는 백필
--
-- `edit_distance_skip_reason` 은 코드 쪽 정본 `core/pilot/ConversionFeedback.kt` 의
-- `EditDistanceSkipReason` 과 1:1 이다. 이 마이그레이션 **전**에 들어간 행은 전부 예산 도입
-- 전에 저장됐으므로(예산 초과 행은 위 결함 때문에 DB 에 한 건도 없다) `edit_distance IS
-- NULL` 인 기존 행은 전부 「검수본 없음」이다 — `no_review` 로 되짚어 채운다.
ALTER TABLE conversion_feedback
    ADD COLUMN edit_distance_skip_reason text NULL;

UPDATE conversion_feedback
SET edit_distance_skip_reason = 'no_review'
WHERE edit_distance IS NULL;

-- 알 수 없는 사유가 조용히 들어가는 것을 막는다. V1 의 `ck_conversions_status_valid` 와 같은
-- 규칙으로, 목록을 코드가 아니라 이 시점 스냅샷으로 SQL 에 직접 적는다.
ALTER TABLE conversion_feedback
    ADD CONSTRAINT ck_conversion_feedback_edit_distance_skip_reason_valid
        CHECK (edit_distance_skip_reason IN ('no_review', 'budget_exceeded'));

-- 옛 짝 제약을 뗀다 — 「예산 초과」 행은 `edited_char_count` 가 채워진 채로 `edit_distance`
-- 만 `null` 이라 이 제약을 어긴다. 아래 세 CHECK 가 같은 자리를 새 컬럼과 함께 다시 진다.
ALTER TABLE conversion_feedback
    DROP CONSTRAINT ck_conversion_feedback_edit_metrics_paired;

-- **거리와 사유는 함께 있거나 함께 없다.** `EditMetrics` 의 `init` 이 같은 불변식을
-- 코드에서 지키고, 여기가 마지막 방어선이다.
ALTER TABLE conversion_feedback
    ADD CONSTRAINT ck_conversion_feedback_edit_distance_skip_reason_paired
        CHECK ((edit_distance IS NULL) = (edit_distance_skip_reason IS NOT NULL));

-- **「검수본 없음」이면 검수본 글자 수도 없다.** `EditMetrics.of` 의 처음 두 갈래(초안 없음·
-- 검수본 없음)가 실제로 그렇게 만든다 — 검수본이 없는데 그 글자 수를 셀 수 없다.
ALTER TABLE conversion_feedback
    ADD CONSTRAINT ck_conversion_feedback_no_review_measured_pair
        CHECK (edit_distance_skip_reason IS DISTINCT FROM 'no_review' OR edited_char_count IS NULL);

-- **「예산 초과」면 검수본 글자 수는 있다.** 예산은 편집 거리 계산만 포기하고, 글자 수 둘은
-- 예산과 무관하게 항상 O(n) 이라 그대로 남는다(`EditDistance.kt` KDoc).
ALTER TABLE conversion_feedback
    ADD CONSTRAINT ck_conversion_feedback_budget_exceeded_measured_pair
        CHECK (edit_distance_skip_reason IS DISTINCT FROM 'budget_exceeded' OR edited_char_count IS NOT NULL);

-- **편집 거리가 있으면 검수본 글자 수도 있다.** 위 세 CHECK 만으로는 이 방향이 빈다 —
-- `edit_distance_skip_reason` 이 `NULL`(=거리를 실제로 쟀다)일 때는 세 CHECK 모두
-- 무조건 참(단축 평가로 통과)이라 `edited_char_count` 를 비워도 걸리지 않는다.
-- `EditMetrics` 의 마지막 갈래(측정 성공)는 둘을 항상 함께 채우므로 이 CHECK 가 그 불변식을
-- 마지막에서 닫는다.
ALTER TABLE conversion_feedback
    ADD CONSTRAINT ck_conversion_feedback_edit_distance_measured_pair
        CHECK (edit_distance IS NULL OR edited_char_count IS NOT NULL);
