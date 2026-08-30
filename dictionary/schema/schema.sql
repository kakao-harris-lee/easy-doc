-- ============================================================================
-- easy-dictionary (쉬운 말 사전) SQLite 스키마
-- 이 파일은 SQLite를 정본(source of truth)으로 삼는다 (DESIGN.md §3).
-- 재실행 안전성을 위해 모든 DDL 객체는 IF NOT EXISTS 로 생성한다.
-- ============================================================================

PRAGMA foreign_keys = ON;
PRAGMA journal_mode = WAL;

-- ----------------------------------------------------------------------------
-- meta: 빌드 버전 / 생성 시각 / 카운트 등 산출물 전체에 대한 메타 정보.
-- key-value 형태로 두어 export.py가 자유롭게 항목을 추가할 수 있게 한다.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS meta (
    key         TEXT PRIMARY KEY,
    value       TEXT NOT NULL
);

-- ----------------------------------------------------------------------------
-- sources: 원천 데이터(기관·라이선스·URL·원본 해시).
-- B2G 납품 시 "이 순화어의 근거가 무엇이냐"에 답하기 위해 엔트리 단위로 FK를 물린다 (§2.3).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS sources (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    code          TEXT NOT NULL UNIQUE,   -- 예: 'data.go.kr:admin-terms'
    name          TEXT NOT NULL,          -- 예: '행정용어 순화어 대조표'
    organization  TEXT,                   -- 예: '행정안전부'
    license       TEXT,                   -- 예: '공공누리 제1유형' (재배포 가능 여부 판단 근거)
    url           TEXT,
    version       TEXT,
    collected_at  TEXT,                   -- 원본 수집 시각 (ISO 8601)
    file_sha256   TEXT,                   -- 원본 CSV/XLSX 파일 해시 (역추적/재현용)
    created_at    TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

-- ----------------------------------------------------------------------------
-- entries: 표제어 본체 (DESIGN.md §3.2 컬럼 사전 그대로 구현).
--
-- 이 사전의 핵심 안전장치는 단순 {term: easy_term} 치환이 아니라
-- replace_strategy / risk_level 두 축으로 "이 말을 바꿔도 되는가"를 강제하는 것이다.
-- (§2.1: 과태료 → 벌금 처럼 법적으로 다른 개념을 치환해버리는 사고를 막기 위함)
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS entries (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,

    term              TEXT NOT NULL,               -- 원어(어려운 말). 예 '차상위계층'
    term_norm         TEXT NOT NULL,                -- 정규화 키: NFC + 공백/·/-/괄호 제거 + 소문자
    term_hanja        TEXT,                         -- 한자 병기. 예 '次上位階層'
    pos               TEXT
                        CHECK (pos IS NULL OR pos IN
                            ('noun', 'verb', 'adjective', 'adverb', 'determiner', 'phrase')),

    easy_term         TEXT NOT NULL,                -- 문장에 그대로 끼울 짧은 대치어

    definition        TEXT,                         -- 한 문장 풀이 (초등 3~4학년 수준)

    -- replace_strategy: 치환 전략 (§2.1의 핵심 안전장치).
    --   substitute : 원어를 지우고 쉬운 말로 교체해도 안전한 경우.        예) 내방 → 방문
    --   gloss      : 원어를 "남기고" 쉬운 말을 덧붙여 설명해야 하는 경우. 예) 과태료 → 과태료(늦게 내서 더 내는 돈)
    --                제도명·법률용어처럼 원어 자체가 다시 쓰여야 하는 용어에 사용한다.
    --   keep       : 바꾸지 않는다. 표시만 하고 별도 용어 설명 박스로 뺀다.
    --                법령명·금액·기한처럼 사실관계가 걸린 값은 치환 대상에서 원천 배제한다.
    -- 이 값이 잘못되면 "과태료"가 "벌금"으로 둔갑하는 등 법적 사고로 이어지므로 CHECK로 강제한다.
    replace_strategy  TEXT NOT NULL
                        CHECK (replace_strategy IN ('substitute', 'gloss', 'keep')),

    -- risk_level: 자동 치환의 위험도. high는 자동 파이프라인에서 사람 검수 큐로 보낸다 (§2.1, §5.2).
    risk_level        TEXT NOT NULL
                        CHECK (risk_level IN ('none', 'low', 'high')),

    caution           TEXT,                         -- 치환 시 주의사항 (사람이 읽는 메모)

    -- readability: 1(가장 쉬움) ~ 3(여전히 조금 어려움).
    readability       INTEGER NOT NULL
                        CHECK (readability BETWEEN 1 AND 3),

    -- confidence: 0~1. 자동 치환 신뢰도.
    confidence        REAL NOT NULL
                        CHECK (confidence >= 0.0 AND confidence <= 1.0),

    priority          INTEGER NOT NULL DEFAULT 100,  -- 겹칠 때 큰 값 우선. 기본 100 + len(term)*10

    -- cell_rank: 원천 CSV 한 셀에 순화어가 여러 개 나열됐을 때 그 셀 안에서
    -- 이 엔트리가 몇 번째로 쓰였는지(0-base, "정보 통신 기술, 정보 문화 기술"이면
    -- 앞의 것이 0). 동일 표면형 승자 결정(§6.8 정렬 키 ④)에 쓰인다 — 원천이
    -- 먼저 적어 둔 것이 대개 권장어라는 저작 의도 신호다. 셀에 순화어가
    -- 하나뿐이거나(대부분) 이 개념이 없는 원천이면 0으로 남는다. DEFAULT 0은
    -- "신호 없음"과 같은 값이라 §6.8에서 자동으로 동률 처리되어 키 ⑤로
    -- 넘어간다 — 미등록 원천이 부당하게 이기거나 지지 않는다.
    cell_rank         INTEGER NOT NULL DEFAULT 0,

    frequency         INTEGER,                       -- 코퍼스 출현 빈도 (선택)

    -- status: active만 자동 치환에 실사용된다. risk_level='high'는 반드시 'review'로 적재된다 (§5.2).
    status            TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'review', 'deprecated')),

    source_id         INTEGER
                        REFERENCES sources(id) ON DELETE SET NULL,
    source_ref        TEXT,                          -- 원본 행 번호/조문 등 역추적 키

    checksum          TEXT NOT NULL,                 -- sha256(term_norm|easy_term)[:16], 중복 판정용

    created_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),
    updated_at        TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now')),

    -- 같은 원어(term_norm)에 서로 다른 순화어(easy_term)가 여러 개 오는 것은 허용한다 (문맥별 대안).
    -- 완전 중복(동일 원어 + 동일 순화어)만 차단한다.
    UNIQUE (term_norm, easy_term)
);

CREATE INDEX IF NOT EXISTS idx_entries_term_norm      ON entries(term_norm);
CREATE INDEX IF NOT EXISTS idx_entries_easy_term      ON entries(easy_term);
CREATE INDEX IF NOT EXISTS idx_entries_status         ON entries(status);
CREATE INDEX IF NOT EXISTS idx_entries_risk_level     ON entries(risk_level);
CREATE INDEX IF NOT EXISTS idx_entries_source_id      ON entries(source_id);

-- entries 갱신 시 updated_at 자동 갱신.
-- WHEN 절로 "updated_at 자체를 UPDATE하는 재귀 호출"만 걸러, 무한 재귀 없이 1회만 갱신한다.
CREATE TRIGGER IF NOT EXISTS trg_entries_updated_at
AFTER UPDATE ON entries
FOR EACH ROW
WHEN NEW.updated_at IS OLD.updated_at
BEGIN
    UPDATE entries
       SET updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
     WHERE id = NEW.id;
END;

-- ----------------------------------------------------------------------------
-- tags / entry_tags: 카테고리는 단일 컬럼이 아니라 태그 N:M (§2.4).
-- 대표 태그(is_primary=1)만 표시해 기존 {"category": "행정용어"} 형태와의 호환을 유지한다.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tags (
    name    TEXT PRIMARY KEY,   -- 예: 'admin'
    label   TEXT NOT NULL,      -- 예: '행정'
    kind    TEXT NOT NULL       -- 예: 'domain' | 'topic' | 'register' | 'ops'
);

CREATE TABLE IF NOT EXISTS entry_tags (
    entry_id    INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    tag_name    TEXT NOT NULL REFERENCES tags(name) ON DELETE CASCADE,
    is_primary  INTEGER NOT NULL DEFAULT 0 CHECK (is_primary IN (0, 1)),
    PRIMARY KEY (entry_id, tag_name)
);

CREATE INDEX IF NOT EXISTS idx_entry_tags_tag_name ON entry_tags(tag_name);

-- ----------------------------------------------------------------------------
-- variants: 활용형·띄어쓰기·한자·약어 변형 (§2.2, §3.4).
-- 조사(은/는/이/가...)는 여기 저장하지 않고 매칭 시 경계 패턴으로 처리해 조합 폭발을 막는다.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS variants (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id      INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    surface       TEXT NOT NULL,        -- 실제 표면형. 예: '명기하여'
    surface_norm  TEXT NOT NULL,        -- normalize_key(surface). 매칭/중복판정 키
    kind          TEXT NOT NULL
                    CHECK (kind IN ('conjugation', 'spacing', 'hanja', 'abbrev', 'synonym', 'typo')),
    is_auto       INTEGER NOT NULL DEFAULT 1 CHECK (is_auto IN (0, 1)),  -- typo는 항상 수동(0)

    -- 같은 표제어 안에서 완전히 동일한 표면형(surface)이 중복 생성되는 것만
    -- 막는다. surface_norm이 아니라 surface 기준인 이유(2026-08-27 변경):
    -- 공백 삽입형 변형형(예: '해외이주법'의 각 음절 사이에 공백을 하나씩 끼운
    -- '해 외이주법'/'해외 이주법'/'해외이 주법'/'해외이주 법')은 공백을 지우면
    -- 전부 같은 surface_norm으로 수렴하지만, export_index()가 색인 키로 쓰는
    -- 것은 surface(원문)이지 surface_norm이 아니다. surface_norm 기준으로
    -- UNIQUE를 걸면 매칭에 필요한 서로 다른 표면형이 "중복"으로 오인되어
    -- 하나만 남고 나머지가 조용히 버려진다 — 어느 것이 살아남을지는 삽입
    -- 순서에 좌우되어 재현성도 없다. surface 기준으로 바꾸면 이런 변형형들이
    -- 모두 유지되면서도, 완전히 같은 표면형의 진짜 중복(예: 같은 표제어에
    -- gen_variants가 우연히 같은 활용형을 두 번 만든 경우)은 여전히 막는다.
    -- surface_norm 컬럼/인덱스는 정규화 키 조회용으로 그대로 유지한다.
    UNIQUE (surface, entry_id)
);

CREATE INDEX IF NOT EXISTS idx_variants_entry_id     ON variants(entry_id);
CREATE INDEX IF NOT EXISTS idx_variants_surface_norm ON variants(surface_norm);

-- ----------------------------------------------------------------------------
-- examples: before/after 예문 (RAG few-shot 재료). is_golden=1은 easy-doc 골든셋 검증과 연동.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS examples (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id     INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    before_text  TEXT NOT NULL,
    after_text   TEXT NOT NULL,
    note         TEXT,
    is_golden    INTEGER NOT NULL DEFAULT 0 CHECK (is_golden IN (0, 1))
);

CREATE INDEX IF NOT EXISTS idx_examples_entry_id ON examples(entry_id);

-- ----------------------------------------------------------------------------
-- relations: 동의어·상하위어·충돌어. related_entry_id가 없어도(사전에 없는 개념) related_term으로 남길 수 있다.
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS relations (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id          INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    related_entry_id  INTEGER REFERENCES entries(id) ON DELETE SET NULL,
    related_term      TEXT,             -- related_entry_id가 없을 때(사전 미등재 개념)의 표시용 텍스트
    kind              TEXT NOT NULL
                        CHECK (kind IN ('synonym', 'broader', 'narrower', 'conflict')),
    note              TEXT
);

CREATE INDEX IF NOT EXISTS idx_relations_entry_id         ON relations(entry_id);
CREATE INDEX IF NOT EXISTS idx_relations_related_entry_id ON relations(related_entry_id);

-- ----------------------------------------------------------------------------
-- embeddings: 벡터 검색용 예약 자리. 1.0 범위에서는 비워둔다 (§1.2).
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS embeddings (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    entry_id    INTEGER NOT NULL REFERENCES entries(id) ON DELETE CASCADE,
    model       TEXT,       -- 임베딩 모델 식별자
    dim         INTEGER,    -- 벡터 차원
    vector      BLOB,       -- 직렬화된 벡터
    created_at  TEXT NOT NULL DEFAULT (strftime('%Y-%m-%dT%H:%M:%fZ', 'now'))
);

CREATE INDEX IF NOT EXISTS idx_embeddings_entry_id ON embeddings(entry_id);

-- ============================================================================
-- entries_fts: FTS5 전문검색 (external content 방식, entries가 정본).
-- 검수 도구(EasyDict.search, sqlite 모드)가 사용한다.
-- ============================================================================
CREATE VIRTUAL TABLE IF NOT EXISTS entries_fts USING fts5(
    term,
    term_hanja,
    easy_term,
    definition,
    caution,
    content = 'entries',
    content_rowid = 'id',
    tokenize = 'unicode61 remove_diacritics 2'
);

-- entries INSERT → entries_fts 동기화
CREATE TRIGGER IF NOT EXISTS trg_entries_fts_ai
AFTER INSERT ON entries
BEGIN
    INSERT INTO entries_fts (rowid, term, term_hanja, easy_term, definition, caution)
    VALUES (new.id, new.term, new.term_hanja, new.easy_term, new.definition, new.caution);
END;

-- entries DELETE → entries_fts 동기화 (external content 방식의 delete 관용구)
CREATE TRIGGER IF NOT EXISTS trg_entries_fts_ad
AFTER DELETE ON entries
BEGIN
    INSERT INTO entries_fts (entries_fts, rowid, term, term_hanja, easy_term, definition, caution)
    VALUES ('delete', old.id, old.term, old.term_hanja, old.easy_term, old.definition, old.caution);
END;

-- entries UPDATE → entries_fts 동기화 (delete old + insert new)
CREATE TRIGGER IF NOT EXISTS trg_entries_fts_au
AFTER UPDATE ON entries
BEGIN
    INSERT INTO entries_fts (entries_fts, rowid, term, term_hanja, easy_term, definition, caution)
    VALUES ('delete', old.id, old.term, old.term_hanja, old.easy_term, old.definition, old.caution);
    INSERT INTO entries_fts (rowid, term, term_hanja, easy_term, definition, caution)
    VALUES (new.id, new.term, new.term_hanja, new.easy_term, new.definition, new.caution);
END;

-- ============================================================================
-- v_entry_full: entries + sources + 태그(group_concat) + variant/example 개수.
-- JSON 익스포트(export_full)와 백엔드 단일 조회가 이 뷰 하나로 끝나야 한다 (§3.1).
-- ============================================================================
CREATE VIEW IF NOT EXISTS v_entry_full AS
SELECT
    e.id,
    e.term,
    e.term_norm,
    e.term_hanja,
    e.pos,
    e.easy_term,
    e.definition,
    e.replace_strategy,
    e.risk_level,
    e.caution,
    e.readability,
    e.confidence,
    e.priority,
    e.cell_rank,
    e.frequency,
    e.status,
    e.source_id,
    e.source_ref,
    e.checksum,
    e.created_at,
    e.updated_at,

    s.code          AS source_code,
    s.name          AS source_name,
    s.organization  AS source_organization,
    s.license       AS source_license,
    s.url           AS source_url,

    (SELECT group_concat(t.name)
       FROM entry_tags et
       JOIN tags t ON t.name = et.tag_name
      WHERE et.entry_id = e.id)                                    AS tags,

    (SELECT et2.tag_name
       FROM entry_tags et2
      WHERE et2.entry_id = e.id AND et2.is_primary = 1
      LIMIT 1)                                                      AS primary_tag,

    (SELECT COUNT(*) FROM variants v WHERE v.entry_id = e.id)       AS variant_count,
    (SELECT COUNT(*) FROM examples ex WHERE ex.entry_id = e.id)     AS example_count

FROM entries e
LEFT JOIN sources s ON s.id = e.source_id;

-- ============================================================================
-- 태그 표준값 시딩 (§3.3). 재실행해도 안전하도록 INSERT OR IGNORE 사용.
-- ============================================================================
INSERT OR IGNORE INTO tags (name, label, kind) VALUES
    ('admin',        '행정',     'domain'),
    ('law',          '법률',     'domain'),
    ('welfare',      '복지',     'domain'),
    ('medical',      '보건·의료', 'domain'),
    ('finance',      '금융·세무', 'domain'),
    ('form',         '서식·신청', 'topic'),
    ('hanja',        '한자어',   'register'),
    ('loanword',     '외래어',   'register'),
    ('jargon',       '전문용어', 'register'),
    ('needs_review', '검수필요', 'ops');
