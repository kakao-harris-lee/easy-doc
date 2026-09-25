-- Persist the selected reading level on the conversion so worker retries and
-- paragraph reconversions cannot silently fall back to the default level.
ALTER TABLE conversions
    ADD COLUMN reading_level varchar(16) NOT NULL DEFAULT 'grade_5_6',
    ADD CONSTRAINT ck_conversions_reading_level
        CHECK (reading_level IN ('grade_5_6', 'grade_3_4'));

-- A nullable snapshot distinguishes conversion/reconversion calls from other
-- LLM features (for example action guides), which do not have a reading level.
ALTER TABLE llm_calls
    ADD COLUMN reading_level varchar(16),
    ADD CONSTRAINT ck_llm_calls_reading_level
        CHECK (reading_level IS NULL OR reading_level IN ('grade_5_6', 'grade_3_4'));
