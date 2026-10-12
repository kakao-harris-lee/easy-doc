-- Independent deletion markers survive restoring a dump taken before document deletion.
CREATE TABLE document_deletion_tombstones (
    id uuid PRIMARY KEY,
    deleted_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp() + interval '8 days'
);
CREATE INDEX document_deletion_tombstones_expiry ON document_deletion_tombstones(expires_at);
CREATE FUNCTION record_document_deletion() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO document_deletion_tombstones(id) VALUES (OLD.id)
    ON CONFLICT (id) DO NOTHING;
    RETURN OLD;
END $$;
CREATE TRIGGER document_deletion_marker AFTER DELETE ON documents
FOR EACH ROW EXECUTE FUNCTION record_document_deletion();
