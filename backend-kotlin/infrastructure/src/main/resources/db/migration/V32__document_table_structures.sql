-- R4 표 구조. 셀 값은 저장하지 않고 source_text의 기존 단위 좌표만 암호화해 보관한다.
-- 문서별 한 payload라 문서 삭제·보존 만료·회원 탈퇴의 CASCADE가 파생 데이터까지 함께 지운다.
CREATE TABLE document_table_structures (
    document_id uuid NOT NULL,
    -- payload_encrypted에는 고정 바이트 상한(review_snapshots/V33의 octet_length CHECK 참고)을
    -- 두지 않는다. review_snapshots는 크기가 일정한 정책 객체지만, 이 payload는 업로드 원문
    -- (상한 10MiB)에서 파생된 표 좌표라 문서마다 크기가 달라진다. 대신 구조 제약으로 상한을
    -- 건다 — 문서당 표 1,000개(TableStructure.MAX_TABLES), 표당 리스트 하나에 인덱스 20,000개
    -- (TableStructure.MAX_INDEXES_PER_LIST), 표당 100행×20열. 고정 바이트 상한을 따로 두면
    -- 이 구조 제약 안에 있는 정상 문서를 크기만으로 잘못 거절할 수 있어 일부러 두지 않는다.
    payload_encrypted bytea NOT NULL,
    encryption_scheme character varying(16) NOT NULL,
    key_version smallint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT pk_document_table_structures PRIMARY KEY (document_id),
    CONSTRAINT fk_document_table_structures_document_id_documents FOREIGN KEY (document_id)
        REFERENCES documents (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_table_structures_encryption_scheme_valid
        CHECK (encryption_scheme IN ('aes256gcm-v1')),
    CONSTRAINT ck_document_table_structures_key_version_positive CHECK (key_version > 0)
);
