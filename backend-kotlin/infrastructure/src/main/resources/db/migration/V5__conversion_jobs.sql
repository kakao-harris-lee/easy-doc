-- 변환 작업 큐 — **PostgreSQL 테이블 하나**. Redis·ARQ 를 쓰지 않는다.
--
-- ## 왜 DB 인가 (계획 §4.4 · 2026-08-12 전환)
--
-- ARQ 의 Redis 내부 직렬화 형식을 Kotlin 에서 흉내 내지 않는다. 큐를 위해 두 번째 저장소를
-- 운영하지 않는 것이 스택 결정이고(프로젝트 `CLAUDE.md` 기술 스택), 그 결정이 **원자성**을
-- 함께 준다 — 문서·변환·작업 행을 같은 트랜잭션에서 저장하면 "DB 커밋 성공, 큐 등록 실패"
-- 간극이 구조적으로 사라진다.
--
-- ## additive 규칙 (계획 §4.2·§10)
--
-- **새 테이블만 만든다.** 기존 테이블·컬럼·제약의 이름을 바꾸거나 타입을 좁히지 않는다.
--
-- ## 이 스크립트가 정하지 **않는** 것 — 소비 쪽
--
-- `FOR UPDATE SKIP LOCKED` 획득, lease 만료 재처리, backoff, 재시도 상한은 **Phase 5** 의
-- worker 가 정한다. 여기서 만드는 것은 그 worker 가 읽을 자리와, 그 자리에 값이 어떤 모양으로
-- 들어갈 수 있는지의 **제약**뿐이다. 상태 전이 규칙을 지금 SQL 로 못박지 않는 이유는, 아직
-- 아무도 그 전이를 실행하지 않아 무엇이 맞는지 확인할 방법이 없기 때문이다 — 확인할 수 없는
-- 규칙을 스키마에 새기면 그것을 고치는 마이그레이션이 곧 따라온다.

CREATE TABLE conversion_jobs (
    -- 작업 식별자를 **변환 식별자로 고정한다.** 별도 작업 id 를 두지 않는 것이 등록을
    -- 멱등하게 만드는 방법이다 — 계약 `POST /documents` 가 이미 그렇게 적었다
    -- ("등록은 작업 id를 변환 id로 고정해 멱등하다"). 같은 변환을 두 번 등록해도 작업은 하나다.
    conversion_id uuid NOT NULL,

    -- 작업의 처리 상태. `conversions.status` 와 **다른 축**이다: 저쪽은 사용자에게 보이는
    -- 변환 결과의 상태이고, 이쪽은 큐가 이 행을 다시 집어야 하는지의 판정이다. 둘을 한
    -- 컬럼으로 합치면 "사용자에게는 실패인데 큐는 재시도해야 하는" 상태를 표현할 수 없다.
    state character varying(16) NOT NULL,

    -- 시도 횟수. 재시도 상한 판정에 쓴다. 로그에 남길 수 있는 값이다(계획 §4.4 —
    -- "conversion id, 상태, 시도 횟수, failure code만 기록한다").
    attempts integer NOT NULL,

    -- 이 시각 이전에는 집지 않는다. backoff 가 이 값을 민다.
    next_attempt_at timestamp with time zone NOT NULL,

    -- lease 를 쥔 worker 의 식별자와 만료 시각. 만료된 lease 는 다른 worker 가 회수한다 —
    -- 그래서 worker 가 죽어도 작업이 영영 묶이지 않는다.
    --
    -- 식별자를 `character varying(64)` 로 둔다. 호스트명·컨테이너 id 가 들어갈 자리이고
    -- **사용자 데이터가 아니다.**
    lease_owner character varying(64),
    lease_until timestamp with time zone,

    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,

    CONSTRAINT pk_conversion_jobs PRIMARY KEY (conversion_id),

    -- 변환이 사라지면 작업도 사라진다. 문서 삭제(즉시 파기)가 `documents` → `conversions`
    -- → `conversion_jobs` 로 이어지도록 CASCADE 를 잇는다. 이것이 없으면 지워진 변환을
    -- 가리키는 작업이 남아 worker 가 매번 없는 행을 읽으러 간다.
    CONSTRAINT fk_conversion_jobs_conversion_id_conversions FOREIGN KEY (conversion_id)
        REFERENCES conversions (id) ON DELETE CASCADE,

    -- 상태 값 목록을 애플리케이션 상수에서 가져오지 않고 SQL 로 직접 적는다 —
    -- 마이그레이션은 그 시점 스키마의 스냅샷이라, 나중에 상태가 추가돼도 이미 적용된
    -- 이 스크립트의 내용이 따라 바뀌면 안 된다(V1 의 `ck_conversions_status_valid` 와 같은 규칙).
    CONSTRAINT ck_conversion_jobs_state_valid
        CHECK (state IN ('ready', 'leased', 'done', 'failed')),

    CONSTRAINT ck_conversion_jobs_attempts_non_negative CHECK (attempts >= 0),

    -- lease 는 **두 값이 함께 있거나 함께 없다.** 한쪽만 있으면 회수 판정이 성립하지 않는다
    -- (주인은 있는데 만료가 없으면 영원히 묶이고, 만료만 있으면 누가 쥐었는지 모른다).
    -- `IS NULL` 비교는 NULL 을 내지 않으므로 이 CHECK 는 언제나 TRUE/FALSE 로 판정된다.
    CONSTRAINT ck_conversion_jobs_lease_paired
        CHECK ((lease_owner IS NULL) = (lease_until IS NULL))
);

-- 집을 수 있는 작업을 고르는 인덱스. worker 의 획득 질의가 `state = 'ready'` 와
-- `next_attempt_at <= now()` 로 들어온다.
--
-- 부분 인덱스의 술어에 `state` 만 둔다 — `now()` 는 IMMUTABLE 이 아니라 술어에 넣을 수 없고,
-- 그 조건은 인덱스 스캔이 정렬된 `next_attempt_at` 으로 걸러 준다.
CREATE INDEX ix_conversion_jobs_ready ON conversion_jobs USING btree (next_attempt_at)
    WHERE state = 'ready';

-- lease 회수 잡이 만료된 것을 찾는다. 부분 인덱스라 정상 작업이 든 행은 이 인덱스에 없다.
CREATE INDEX ix_conversion_jobs_leased ON conversion_jobs USING btree (lease_until)
    WHERE state = 'leased';
