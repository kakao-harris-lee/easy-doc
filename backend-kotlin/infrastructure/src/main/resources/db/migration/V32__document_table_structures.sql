-- R4 표 구조. 셀 값은 저장하지 않고 source_text의 기존 단위 좌표만 암호화해 보관한다.
-- 문서별 한 payload라 문서 삭제·보존 만료·회원 탈퇴의 CASCADE가 파생 데이터까지 함께 지운다.
CREATE TABLE document_table_structures (
    document_id uuid NOT NULL,
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
