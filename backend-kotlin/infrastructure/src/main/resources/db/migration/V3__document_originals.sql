-- 업로드 **원본 파일 바이트** 보존. `DESIGN.md` §6.5 「원본 형식 내보내기와 서식 유지」의
-- 선행 조건이다 — 지금 `documents` 는 추출한 **텍스트만** 들고 있어서(`source_text_encrypted`)
-- 업로드된 DOCX·HWPX·PDF 는 파싱이 끝나는 순간 사라진다. 서식을 유지한 채 원본 형식으로
-- 내보내려면 그 바이트가 남아 있어야 한다.
--
-- 이 마이그레이션은 **자리만 만든다.** 저장은 시작되지만 어떤 공개 API 도 이 표를 읽지
-- 않는다(계약 `contracts/easy-doc-v1.yaml` 은 이번 변경에서 그대로다).

-- --- document_originals -------------------------------------------------------
-- **`documents` 에 컬럼을 더하지 않고 표를 따로 세운 이유** — 되돌리기 어려운 결정이라
-- 네 가지 근거를 함께 적는다.
--
-- ① **「원본이 없다」가 NULL 이 아니라 「행이 없다」가 된다.** 붙여넣기(`source_format='text'`)
--    경로에는 원본 파일이 자체가 없다. `documents` 에 컬럼 넷(바이트·방식·세대·크기)을 더하면
--    그 넷이 전부 NULL 을 허용해야 하고, 「넷이 함께 있거나 함께 없다」를 짝 CHECK 세 개로
--    다시 세워야 한다(V2 의 `ck_conversion_feedback_comment_*_paired` 가 그 값을 치렀다).
--    표를 나누면 그 불변식이 제약이 아니라 **구조**가 된다 — 아래 네 컬럼은 전부 NOT NULL 이고,
--    원본이 없는 문서는 행이 없다.
--
-- ② **삭제와 보존 만료가 공짜로 따라온다.** 즉시 파기(`DocumentService.delete`)도 30일 보존
--    만료 파기(`JdbcExpiredDocumentPurge`)도 계정 삭제(users → documents CASCADE)도 전부
--    `DELETE FROM documents` 한 문장으로 끝난다. 아래 FK 의 ON DELETE CASCADE 가 그 한 문장에
--    원본을 얹는다 — 파기 코드를 고칠 필요가 없고, **원본만 살아남는 경로가 존재하지 않는다.**
--
-- ③ **목록 조회가 읽는 행이 얇게 남는다.** 원본은 최대 10MB 이고 `GET /documents` 는 그것을
--    읽지 않는다. 같은 행에 두어도 bytea 는 TOAST 로 빠져 SELECT 가 건드리지 않는 것이 맞지만,
--    `documents` 는 **UPDATE 되는 표다** — 키 회전(`rewriteEnvelope`)이 원문 암호문을 다시 쓸
--    때마다 새 힙 튜플이 생기고, 원본이 같은 행에 있으면 그 회전이 10MB TOAST 사슬을 함께
--    끌고 다닌다. 두 암호문의 수명과 크기가 두 자릿수 다르므로 행을 나눈다.
--
-- ④ **회전을 따로 돌릴 수 있다.** ⑤ 참고.
--
-- **버린 쪽:** `documents` 에 `original_*` 컬럼 넷을 더하는 안. 표 하나가 덜 생기고 조인이
-- 없다는 것이 장점이지만, ①의 짝 CHECK 셋과 ③의 행 비대를 떠안고 ⑤가 불가능해진다.
CREATE TABLE document_originals (
    -- 문서 한 건에 원본도 하나다. 별도 id 를 두지 않는 것이 「원본은 문서의 일부」라는
    -- 사실을 키로 적는 방법이다(V2 의 `conversion_feedback.conversion_id` 와 같은 규칙).
    document_id uuid NOT NULL,

    -- 업로드된 파일 **그대로**의 바이트. 추출 텍스트가 아니라 원본이다.
    --
    -- `documents.source_text_encrypted` 와 같은 이유로 봉한다(master-plan 3.2, 저장 암호화):
    -- 이 표를 직접 조회해도 문서가 보이면 안 된다. 오히려 이쪽이 더 민감하다 — 추출 텍스트는
    -- 본문만 남지만 원본 파일에는 작성자·수정 이력·주석·삭제된 조각 같은 메타데이터가 함께
    -- 들어 있다.
    --
    -- 방식·세대 체계는 기존 것을 그대로 쓴다(`core/crypto/StoredContent.kt`). 결속 이름은
    -- 같은 파일의 `EncryptedField.DOCUMENT_ORIGINAL_BYTES` 이고, AEAD 의 associated data 에
    -- `document_originals.file_bytes_encrypted` 라는 이 컬럼 이름이 실린다 — 다른 컬럼의
    -- 암호문을 여기에 옮겨 놓아도 열리지 않는다.
    file_bytes_encrypted bytea NOT NULL,

    -- ⑤ **봉투를 `documents` 와 공유하지 않는다.**
    --
    -- 판단 기준은 「키 회전이 두 암호문을 따로 돌릴 수 있어야 하는가」였고, 답은 그렇다이다.
    -- 두 암호문이 다른 표에 있으므로 봉투를 공유하면 회전 한 번이 **두 표를 원자적으로**
    -- 갱신해야 한다. `EnvelopeRotation` 의 쓰기 조건은 「잠근 채 읽은 암호문 그 자체」인데,
    -- 그 조건이 두 표에 걸치면 낙관적 조건이 둘로 갈라지고 부분 갱신이 가능해진다 —
    -- 그렇게 갈린 행은 한쪽 세대로만 열리고 다른 쪽은 영원히 닫힌다.
    --
    -- 공유하지 않으면 회전이 두 갈래로 선다: 4KB 텍스트를 도는 `rotateDocument` 와 10MB
    -- 바이트를 도는 `rotateDocumentOriginal`. 운영이 둘을 다른 속도로 돌릴 수 있고, 원본이
    -- 없는 문서(붙여넣기)는 뒤쪽 갈래가 아예 건너뛴다. 봉투를 공유했다면 텍스트만 회전하는
    -- 문서에서도 10MB 를 열고 다시 봉해야 한다.
    --
    -- **버린 쪽:** `documents.encryption_scheme`/`key_version` 을 그대로 쓰는 안. 컬럼 둘이
    -- 덜 생기지만 위 원자성 문제를 떠안는다.
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,

    -- 봉하기 **전** 바이트 수. 암호문 길이(nonce+본문+태그)가 아니라 원본 파일 크기다.
    --
    -- 평문 숫자로 두는 이유는 V2 의 수정률 지표와 같다 — 크기 하나로는 내용이 복원되지 않고,
    -- 「원본이 있는가·얼마나 큰가」를 묻는 데 매번 키를 꺼내 10MB 를 열 이유가 없다.
    -- (`documents.char_count` 가 이미 같은 성격의 값이다.)
    --
    -- 상한 CHECK 를 두지 않는다: 업로드 상한은 계약값(`x-input-limits.max_upload_bytes`)이라
    -- 바뀔 수 있고, 마이그레이션은 그 시점 스키마의 스냅샷이라 따라 바뀌지 못한다. 상한 검사는
    -- `DocumentService` 가 추출보다 먼저 한다.
    byte_size integer NOT NULL,

    created_at timestamp with time zone DEFAULT now() NOT NULL,

    CONSTRAINT pk_document_originals PRIMARY KEY (document_id),

    -- ② 의 근거가 여기 한 줄이다. **CASCADE 를 빼면 안 된다** — 즉시 파기·보존 만료 파기·계정
    -- 삭제 셋 다 `DELETE FROM documents` 이고, 그 문장이 원본을 데려가지 못하면 사용자가 지운
    -- 문서의 원본 파일이 DB 에 남는다(개인정보 삭제 요구 위반).
    --
    -- `conversion_feedback` 이 FK 를 일부러 걸지 않은 것과 **정반대 판단이다.** 그쪽은 파일럿
    -- 판정 근거(대부분 척도 숫자)를 문서 파기보다 오래 남기려는 표였다. 이쪽은 사용자 콘텐츠
    -- 원본 그 자체라 문서보다 **한순간도 더 살면 안 된다.**
    CONSTRAINT fk_document_originals_document_id_documents FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE,

    -- 보존 기한을 이 표에 다시 적지 않는다. `documents.retention_expires_at` 하나가 정본이고,
    -- 복사해 두면 두 값이 갈리는 순간 원본만 더 오래 남는다(master-plan 3.2 의 30일 보존).

    -- 방식 이름 목록. `documents`·`conversions`·`conversion_feedback` 과 같은 값이어야
    -- 복호화 경로가 하나로 남는다. 목록을 애플리케이션 상수에서 가져오지 않고 SQL 로 직접
    -- 적는 것은 V1·V2 와 같은 규칙이다 — 마이그레이션은 그 시점 스키마의 스냅샷이다.
    CONSTRAINT ck_document_originals_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),

    -- 세대 번호는 associated data 에 들어가고 복호화 시점의 키 조회 키다. 설정에 있을 수 없는
    -- 번호(0 이하)가 적힌 행은 영원히 열리지 않는다 — 상한(32767)은 smallint 가 막는다.
    CONSTRAINT ck_document_originals_key_version_positive CHECK (key_version > 0),

    -- 0바이트 원본은 있을 수 없다 — 빈 파일은 추출 단계에서 이미 거절된다
    -- (`DocumentService.createFromFile` 의 `NO_TEXT_IN_DOCUMENT_MESSAGE`). 코드 쪽 정본은
    -- `core/crypto/StoredContent.kt` 의 `PlainBytes` 이고 여기는 마지막 방어선이다.
    CONSTRAINT ck_document_originals_byte_size_positive CHECK (byte_size > 0)
);

-- 인덱스를 따로 만들지 않는다. 이 표에 들어오는 질의는 셋뿐이고 전부 기본 키로 한 행을 집는다
-- (저장 · 소유자 확인 후 읽기 · 회전이 한 행을 잠그기). 목록·범위 조회는 `documents` 쪽에서
-- 끝나고 여기로 내려오지 않는다. `document_id` 는 PK 라 FK 의 CASCADE 삭제도 그 인덱스를 탄다.
