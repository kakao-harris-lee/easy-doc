-- 파일럿 게이트 ① 검수 피드백. `docs/master-plan.md` §9 의 게이트 ①(파일럿 실무자 검증 →
-- 단계 2 진행)을 판정할 근거를 담는다. 절차는 `docs/pilot-runbook.md` 「게이트 ① 판정」이다.
--
-- 수기 입력은 검수 화면 하단의 폼 제출 **한 번**이 전부다 — 배포 의향 · 품질 만족도 ·
-- 이번 건 소요 시간 · 자유 의견(선택). 나머지는 시스템이 남긴다.
--
-- 코드 쪽 수치 범위의 정본은 `core/pilot/ConversionFeedback.kt` 다. 아래 CHECK 는 그 값이
-- DB 까지 갔을 때의 **마지막 방어선**이지 유일한 방어선이 아니다.

-- --- conversion_feedback ------------------------------------------------------
CREATE TABLE conversion_feedback (
    -- 변환 한 건에 피드백도 하나다. 별도 피드백 id 를 두지 않는 것이 `PUT` 을 멱등하게
    -- 만드는 방법이다 — 같은 변환에 두 번 제출하면 덮어쓰기가 되고 행은 하나로 남는다
    -- (`conversion_jobs` 가 작업 id 를 변환 id 로 고정한 것과 같은 규칙).
    conversion_id uuid NOT NULL,

    -- 집계가 참여자 수를 세는 축이다. 표본 10건이 한 사람 것인지 두 사람 것인지가
    -- 판정에 들어가므로(runbook 「대상과 규모」) 제출자를 행에 함께 적는다.
    user_id uuid NOT NULL,

    -- 통과 기준 ①의 판정 대상. `as_is` 와 `with_edits` 를 「쓸 수 있다」로 센다.
    publish_intent character varying(16) NOT NULL,

    -- 통과 기준 ②(평균)의 표본. 1~5 척도다.
    quality_score smallint NOT NULL,

    -- 통과 기준 ③(중앙값)의 표본. 이 문서 **한 건**에 들인 시간(분)이다.
    minutes_spent integer NOT NULL,

    -- 자유 의견. **여기만 AEAD 로 봉한다.**
    --
    -- 위 세 값은 척도이고 사용자 콘텐츠가 아니지만, 자유 의견 칸에는 검수자가 문제를
    -- 설명하려고 **문서 본문 조각을 그대로 붙여 넣는 일**이 실제로 일어난다("○○동 ○○○
    -- 님께 안내드립니다 부분이 어색합니다"). 원문을 봉해 놓고 그 조각을 옆 테이블에
    -- 평문으로 남기면 봉인이 의미를 잃는다(`documents.title` 을 본문에서 만들지 않기로 한
    -- 판정과 같은 사유 — `core/document/TitleRules.kt`).
    --
    -- 그래서 집계 스크립트는 이 컬럼을 읽지 않는다. 열람이 필요하면 소유자 토큰으로
    -- 화면에서 본다.
    comment_encrypted bytea,

    -- 암호문 자체에는 어떤 방식·키 세대로 썼는지가 남지 않는다 — `documents` 와 같은 사유다.
    -- 코드 쪽 정본은 `core/crypto/StoredContent.kt` 의 `EncryptionScheme` 이고, 결속 이름은
    -- 같은 파일의 `EncryptedField.CONVERSION_FEEDBACK_COMMENT` 다.
    --
    -- `documents`·`conversions` 와 달리 세 컬럼이 전부 NULL 을 허용한다 — 자유 의견이
    -- **선택 항목**이라 봉투가 아예 없는 행이 정상이기 때문이다.
    encryption_scheme character varying(16),
    key_version smallint,

    -- 수정률 지표 셋. **평문 숫자로 둔다.**
    --
    -- 집계가 건별 복호화 없이 SQL 하나로 끝나게 하려는 선택이다(runbook 「집계」의 psql
    -- 한 줄). 봉해 두면 판정 때마다 애플리케이션을 띄워 키를 꺼내고 10건을 풀어야 한다.
    --
    -- 숫자만으로는 본문이 복원되지 않는다 — 글자 수 두 개와 편집 거리 하나는 "얼마나
    -- 고쳤는가"를 말할 뿐 "무엇이 적혀 있었는가"를 말하지 않는다. 봉인 대상은 본문이지
    -- 본문에 대한 척도가 아니다.
    --
    -- NULL 을 허용하는 이유: 피드백은 검수 수정본 없이도 제출될 수 있다(그대로 쓸 만하면
    -- 고칠 것이 없다). 그때 「수정률 0%」와 「측정 대상 아님」은 다른 값이라 0 으로 채우지
    -- 않는다.
    --
    -- 셋이 서로 독립이 아니라는 것은 아래 `ck_conversion_feedback_edit_metrics_*` 세 CHECK 가
    -- 진다 — 「짝 없는 편집 거리」 같은 조합은 NULL 허용의 부산물이 아니라 결함이다.
    easy_char_count integer,
    edited_char_count integer,
    edit_distance integer,

    submitted_at timestamp with time zone DEFAULT now() NOT NULL,
    -- 재제출(멱등 upsert)이 이 값을 민다. **트리거로 갱신하지 않는다** — 이 저장소에
    -- 트리거 관행이 없고(`conversions.updated_at` 도 같다), upsert 문이 now() 를 적는다.
    updated_at timestamp with time zone DEFAULT now() NOT NULL,

    CONSTRAINT pk_conversion_feedback PRIMARY KEY (conversion_id),

    -- **FK 를 걸지 않는다. 이것이 이 테이블 설계의 핵심이다.**
    --
    -- `conversion_id` → `conversions` 나 `user_id` → `users` 에 FK 를 걸면 CASCADE 사슬이
    -- 판정 근거를 함께 지운다. 문서는 기본 30일 보존 뒤 파기되고(master-plan §3.2,
    -- `documents.retention_expires_at`), 그 삭제가 documents → conversions →
    -- conversion_jobs 로 이미 이어진다. 여기에 한 칸을 더 이으면 **파일럿이 끝나기도 전에
    -- 게이트 ① 의 표본이 사라진다** — 30일은 파일럿 기간과 같은 자릿수다.
    --
    -- **그래서 이 표에는 삭제 경로가 아직 없다.** TTL 도, 보존 만료 파기의 대상도 아니다
    -- (`JdbcExpiredDocumentPurge` 는 `documents` 만 지운다). 계정 삭제 기능도 제품에 없다.
    -- 남는 것이 대부분 척도 숫자인 것은 맞지만, **봉인된 자유 의견 칸은 예외다** — 위
    -- 「자유 의견」 주석이 적었듯 거기에는 문서 본문 조각이 실제로 들어온다. 봉인은 저장
    -- 시 기밀성이지 삭제가 아니고, 키는 운영 마스터 키라 사용자가 사라져도 그대로 열린다.
    -- 그러므로 「개인정보 삭제 요구는 다른 표가 지워지는 것으로 이미 충족된다」고 읽지 마라.
    -- 이 표의 파기는 파일럿 종료 절차(`docs/pilot-runbook.md`)가 지는 몫이고, 그 절차가
    -- 서기 전까지 이 행들은 남는다.
    --
    -- 그래서 여기 있는 `conversion_id`·`user_id` 는 **참조가 아니라 기록**이다. 가리키는
    -- 행이 이미 없을 수 있고, 그것이 정상이다. 다음 사람이 이것을 「빠뜨린 FK」로 보고
    -- 되살리면 위 성질이 조용히 사라진다 — 되살리기 전에 이 주석을 지워야 한다.

    -- 알 수 없는 의향 값이 조용히 들어가는 것을 막는다. 목록을 애플리케이션 상수에서
    -- 가져오지 않고 SQL 로 직접 적는다 — 마이그레이션은 그 시점 스키마의 스냅샷이라,
    -- 나중에 값이 추가돼도 이미 적용된 이 스크립트가 따라 바뀌면 안 된다
    -- (V1 의 ck_conversions_status_valid 와 같은 규칙).
    CONSTRAINT ck_conversion_feedback_publish_intent_valid
        CHECK (publish_intent IN ('as_is', 'with_edits', 'not_usable')),

    -- 척도 밖 값은 평균을 조용히 망가뜨린다 — 0 점이나 6 점이 한 건 섞이면 기준 ②의
    -- 판정이 바뀐다. smallint 는 범위를 막아 주지 못하므로 CHECK 로 적는다.
    CONSTRAINT ck_conversion_feedback_quality_score_range
        CHECK (quality_score BETWEEN 1 AND 5),

    -- 음수는 있을 수 없고, 상한은 단위 착오(초·시간 입력)와 오타를 막는다. 표본이
    -- 10건뿐이라 그런 한 건이 기준 ③의 중앙값을 흔든다.
    CONSTRAINT ck_conversion_feedback_minutes_spent_range
        CHECK (minutes_spent >= 0 AND minutes_spent <= 600),

    -- 방식 이름 목록. `documents`·`conversions` 와 같은 값이어야 복호화 경로가 하나로 남는다.
    CONSTRAINT ck_conversion_feedback_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),

    -- 세대 번호는 associated data 에 들어가고 복호화 시점의 키 조회 키다. 설정에 있을 수
    -- 없는 번호(0 이하)가 적힌 행은 영원히 열리지 않는다 — 상한(32767)은 smallint 가 막는다.
    CONSTRAINT ck_conversion_feedback_key_version_positive CHECK (key_version > 0),

    -- 암호문·방식·세대는 **함께 있거나 함께 없다.** 암호문만 있으면 무엇으로 열어야 하는지
    -- 모르고, 봉투 값만 있으면 열 것이 없다. 자유 의견이 선택 항목이라 "셋 다 NULL" 이
    -- 정상 상태이므로, NOT NULL 이 아니라 이 짝 제약이 그 자리를 진다
    -- (V1 의 ck_conversion_jobs_lease_paired 와 같은 형태).
    CONSTRAINT ck_conversion_feedback_comment_scheme_paired
        CHECK ((comment_encrypted IS NULL) = (encryption_scheme IS NULL)),
    CONSTRAINT ck_conversion_feedback_comment_key_version_paired
        CHECK ((comment_encrypted IS NULL) = (key_version IS NULL)),

    -- 지표 셋의 **음수 금지.** 글자 수는 세는 값이고 편집 거리는 연산 수라 셋 다 0 이 바닥이다.
    -- 음수가 한 건 섞이면 집계의 수정률(`edit_distance / easy_char_count`)이 음수가 되고,
    -- runbook 「집계」의 psql 한 줄은 그것을 「거의 안 고쳤다」로 읽는다 — 척도값에 범위
    -- CHECK 를 둔 것과 같은 사유다. NULL 은 여기서 걸리지 않는다(`NULL >= 0` 은 unknown).
    CONSTRAINT ck_conversion_feedback_edit_metrics_nonnegative
        CHECK (easy_char_count >= 0 AND edited_char_count >= 0 AND edit_distance >= 0),

    -- **검수본 글자 수와 편집 거리는 함께 있거나 함께 없다.** 둘 다 검수본이 있어야 뜻이
    -- 생기는 값이고, `ConversionFeedbackService` 의 `EditMetrics` 는 실제로 그 둘을 한
    -- 갈래에서 함께 채운다. 「검수본이 없는데 편집 거리는 0」인 행이 들어오면 집계는 그것을
    -- 「하나도 고치지 않았다」로 읽는다 — NULL 을 허용한 사유가 정확히 그 구분이므로,
    -- 짝이 깨진 조합을 여기서 끊어야 그 구분이 실제로 성립한다.
    CONSTRAINT ck_conversion_feedback_edit_metrics_paired
        CHECK ((edited_char_count IS NULL) = (edit_distance IS NULL)),

    -- **분모 없는 분자를 막는다.** 초안 글자 수는 수정률의 분모이자 편집 거리의 한쪽 끝이라,
    -- 그것이 없으면 나머지 둘은 「무엇에 견준 값인지」를 말하지 못한다. `EditMetrics` 도
    -- 초안이 없으면 셋을 함께 비운다.
    --
    -- 위 둘과 합치면 통과하는 조합은 셋뿐이고, 그것이 `EditMetrics` 가 만들 수 있는 전부다:
    -- (NULL, NULL, NULL) · (초안, NULL, NULL) · (초안, 검수본, 거리).
    CONSTRAINT ck_conversion_feedback_edit_metrics_measured
        CHECK (edited_char_count IS NULL OR easy_char_count IS NOT NULL)
);

-- 집계는 기간으로 자르고(문서 보존 만료 전에 돌린다) 그 안에서 참여자 수를 센다.
-- 기간 조건이 선택적인 쪽이라 인덱스는 여기 하나만 만든다.
--
-- `user_id` 에 인덱스를 두지 않는 이유: 참여자 수는 위 기간 조건으로 이미 좁혀진 집합을
-- 훑어 세므로 인덱스가 할 일이 없고, 소유자 검증은 기본 키(conversion_id)로 행을 집은 뒤
-- 컬럼 값을 비교한다. 파일럿 표본은 10건 규모라 어차피 계획이 순차 스캔으로 간다 —
-- 쓸모없는 인덱스는 upsert 마다 갱신 비용만 남긴다.
CREATE INDEX ix_conversion_feedback_submitted_at
    ON conversion_feedback USING btree (submitted_at);
