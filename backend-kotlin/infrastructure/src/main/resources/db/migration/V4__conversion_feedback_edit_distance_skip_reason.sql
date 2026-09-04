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

-- ## 구버전 쓰기 호환
--
-- 이 마이그레이션이 적용된 뒤 `edit_distance_skip_reason` 을 **모르는** 옛 애플리케이션이
-- 계속 쓸 수 있다 — 배포 롤백, 또는 그 사이 떠 있던 옛 인스턴스. `JdbcConversionFeedbackRepository`
-- 의 옛 `UPSERT_SQL`(4e0c1b0)은 이 컬럼을 아예 언급하지 않는 INSERT ... ON CONFLICT 라,
-- 새로 생기는 아래 `ck_conversion_feedback_edit_distance_skip_reason_paired` 앞에서 매번
-- 걸린다: `edit_distance` 는 채우거나 비우면서 `edit_distance_skip_reason` 은 항상 암묵적
-- NULL 로 두기 때문이다. 검수본 없는 최초 제출(`edit_distance IS NULL`)도, 예산 초과가
-- 섞인 재제출의 ON CONFLICT UPDATE 도 CHECK 위반으로 500 이 된다. Flyway 는 마이그레이션을
-- 되돌리지 않으므로, 옛 버전으로 애플리케이션만 롤백해도 이 표는 그대로 막힌다.
--
-- 아래 트리거가 그 구멍을 메운다 — 사유를 명시하지 않는 쓰기에서 지표 조합으로부터
-- 사유를 **되짚어 채우는** 호환 shim 이다. 애플리케이션은 여전히 사유를 명시적으로 쓰고,
-- 트리거는 그 값이 없을 때만(옛 쓰기 경로) 개입한다. 아래 CHECK 셋이 여전히 마지막
-- 방어선이다 — 트리거는 짝을 맞출 뿐 검증하지 않는다.
--
-- 이 저장소에는 트리거를 쓰는 관행이 없다(`updated_at` 조차 upsert 문이 직접 민다,
-- V2 주석 참고) — 이 트리거는 예외이고, 이 PR 보다 오래된 쓰기 경로가 더는 존재할 수
-- 없게 되면(옛 애플리케이션 인스턴스가 전부 걷히면) 나중 마이그레이션에서 지워도 된다.
CREATE FUNCTION conversion_feedback_derive_skip_reason() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.edit_distance IS NOT NULL THEN
        -- 거리를 실제로 쟀다 — 옛 UPSERT 의 재제출(ON CONFLICT UPDATE)이 예전에 남긴
        -- 사유를 지운다. 그러지 않으면 「사유가 있는데 거리도 있다」는 짝 어긋난 행이 된다.
        NEW.edit_distance_skip_reason := NULL;
    ELSIF NEW.edit_distance_skip_reason IS NULL THEN
        -- 거리가 없는데 사유도 안 왔다 — 옛 쓰기 경로다. `EditMetrics.of` 의 두 갈래와 같은
        -- 판별: 검수본 글자 수가 없으면 검수본 자체가 없었던 것(no_review), 있으면 거리
        -- 계산만 예산 때문에 포기한 것(budget_exceeded).
        IF NEW.edited_char_count IS NULL THEN
            NEW.edit_distance_skip_reason := 'no_review';
        ELSE
            NEW.edit_distance_skip_reason := 'budget_exceeded';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_conversion_feedback_derive_skip_reason
    BEFORE INSERT OR UPDATE ON conversion_feedback
    FOR EACH ROW
    EXECUTE FUNCTION conversion_feedback_derive_skip_reason();

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
