-- Preserve existing defaults and rows; extend both snapshots for level 1.
ALTER TABLE conversions DROP CONSTRAINT ck_conversions_reading_level;
ALTER TABLE conversions ADD CONSTRAINT ck_conversions_reading_level
    CHECK (reading_level IN ('middle_school', 'grade_5_6', 'grade_3_4'));

ALTER TABLE llm_calls DROP CONSTRAINT ck_llm_calls_reading_level;
ALTER TABLE llm_calls ADD CONSTRAINT ck_llm_calls_reading_level
    CHECK (reading_level IS NULL OR reading_level IN ('middle_school', 'grade_5_6', 'grade_3_4'));
