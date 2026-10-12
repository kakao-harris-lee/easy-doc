#!/usr/bin/env bash

# Re-apply an encrypted deletion export after restoring an older PostgreSQL
# dump. This is deliberately a separate, restore-only command: it must never
# be able to delete rows from a live application database.

set -Eeuo pipefail
umask 077

usage() {
  printf 'usage: %s deletion-export.json --apply\n' "$0" >&2
  exit 64
}

fail() {
  printf 'deletion reapply refused: %s\n' "$1" >&2
  exit 1
}

[[ $# -eq 2 && $2 == '--apply' ]] || usage
export_path=$1
[[ -f "$export_path" && -r "$export_path" ]] || fail 'export file is not readable'
command -v jq >/dev/null 2>&1 || fail 'jq is required'
command -v psql >/dev/null 2>&1 || fail 'psql is required'

freshness_limit=${POSTGRES_BACKUP_DELETION_MAX_AGE_SECONDS:-180}
[[ "$freshness_limit" =~ ^[0-9]+$ ]] || fail 'freshness limit must be an integer'
(( freshness_limit >= 1 && freshness_limit <= 180 )) ||
  fail 'freshness limit must be between 1 and 180 seconds'

# Validate the shape and every identifier before generating CSV. COPY still
# performs the PostgreSQL type checks, while this rejects malformed values
# before a transaction is opened.
if ! jq -e '
  def valid_uuid:
    if type == "string" then
      test("^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$")
    else false end;
  def valid_text: type == "string";
  def valid_epoch:
    if type == "number" then floor == . and . >= 0 else false end;
  (.schema == "easy-doc.deletion-manifest.v1") and
  (.available == true) and
  (.captured_at | valid_text) and
  (.captured_epoch | valid_epoch) and
  (if (.account_deletions | type) == "array" then
     all(.account_deletions[]; (.id | valid_uuid) and (.user_id | valid_uuid) and
       (.deleted_at | valid_text) and (.expires_at | valid_text))
   else false end) and
  (if (.document_deletions | type) == "array" then
     all(.document_deletions[]; (.id | valid_uuid) and
       (.deleted_at | valid_text) and (.expires_at | valid_text))
   else false end)
' "$export_path" >/dev/null; then
  fail 'export schema, availability, timestamp, or UUID validation failed'
fi

captured_epoch=$(jq -er '.captured_epoch' "$export_path") ||
  fail 'captured_epoch is missing'
now_epoch=$(date -u '+%s')
export_age=$((now_epoch - captured_epoch))
if (( export_age < 0 || export_age > freshness_limit )); then
  fail 'deletion export is stale or from the future'
fi

if ! database_name=$(psql -X -qAt -v ON_ERROR_STOP=1 -c 'select current_database()'); then
  fail 'could not connect to the restore database'
fi
[[ "$database_name" == easydoc_restore_* ]] ||
  fail 'target database must use the easydoc_restore_ prefix'

temporary_dir=$(mktemp -d "${TMPDIR:-/tmp}/easydoc-reapply.XXXXXX")
cleanup() {
  rm -rf -- "$temporary_dir"
}
trap cleanup EXIT

jq -r '.account_deletions[] | [.id, .user_id, .deleted_at, .expires_at] | @csv' \
  "$export_path" >"$temporary_dir/account.csv"
jq -r '.document_deletions[] | [.id, .deleted_at, .expires_at] | @csv' \
  "$export_path" >"$temporary_dir/document.csv"
expected_accounts=$(jq -er '.account_deletions | length' "$export_path")
expected_documents=$(jq -er '.document_deletions | length' "$export_path")
[[ "$expected_accounts" =~ ^[0-9]+$ && "$expected_documents" =~ ^[0-9]+$ ]] ||
  fail 'deletion marker counts are invalid'

# psql keeps COPY and all deletes in one transaction. CSV is sent through COPY
# stdin so no SQL variable or unquoted path interpolation can alter the SQL.
# If any COPY, constraint, or remaining-target check fails before COMMIT, the
# connection closes with the transaction uncommitted and PostgreSQL rolls it
# back.
if ! {
cat <<'SQL'
BEGIN;
CREATE TEMP TABLE restore_expected_counts ON COMMIT DROP AS
SELECT :expected_accounts::bigint AS accounts, :expected_documents::bigint AS documents;

DO $$
BEGIN
    IF substring(current_database() FROM 1 FOR char_length('easydoc_restore_'))
        <> 'easydoc_restore_' THEN
        RAISE EXCEPTION 'restore database prefix is required';
    END IF;
END
$$;

CREATE TEMP TABLE restore_account_deletions (
    id uuid NOT NULL,
    user_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
) ON COMMIT DROP;

CREATE TEMP TABLE restore_document_deletions (
    id uuid NOT NULL,
    deleted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL
) ON COMMIT DROP;

COPY restore_account_deletions (id, user_id, deleted_at, expires_at) FROM STDIN WITH (FORMAT csv);
SQL
cat "$temporary_dir/account.csv"
printf '%s\n' '\.'
cat <<'SQL'
COPY restore_document_deletions (id, deleted_at, expires_at) FROM STDIN WITH (FORMAT csv);
SQL
cat "$temporary_dir/document.csv"
printf '%s\n' '\.'
cat <<'SQL'

DO $$
BEGIN
    IF (SELECT count(*) FROM restore_account_deletions) <>
        (SELECT accounts FROM restore_expected_counts) OR
       (SELECT count(*) FROM restore_document_deletions) <>
        (SELECT documents FROM restore_expected_counts) THEN
        RAISE EXCEPTION 'incomplete deletion marker input';
    END IF;
    IF EXISTS (
        SELECT 1 FROM restore_account_deletions GROUP BY id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate account deletion marker';
    END IF;
    IF EXISTS (
        SELECT 1 FROM restore_document_deletions GROUP BY id HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate document deletion marker';
    END IF;
END
$$;

INSERT INTO account_deletion_tombstones (id, user_id, deleted_at, expires_at)
SELECT id, user_id, deleted_at, expires_at
FROM restore_account_deletions
ON CONFLICT (id) DO UPDATE SET
    user_id = EXCLUDED.user_id,
    deleted_at = EXCLUDED.deleted_at,
    expires_at = EXCLUDED.expires_at;

INSERT INTO document_deletion_tombstones (id, deleted_at, expires_at)
SELECT id, deleted_at, expires_at
FROM restore_document_deletions
ON CONFLICT (id) DO UPDATE SET
    deleted_at = EXCLUDED.deleted_at,
    expires_at = EXCLUDED.expires_at;

DELETE FROM documents AS target
USING restore_document_deletions AS marker
WHERE target.id = marker.id;

DELETE FROM users AS target
USING restore_account_deletions AS marker
WHERE target.id = marker.user_id;

DO $$
DECLARE
    remaining_documents bigint;
    remaining_users bigint;
BEGIN
    SELECT count(*) INTO remaining_documents
    FROM documents AS target
    JOIN restore_document_deletions AS marker ON marker.id = target.id;
    SELECT count(*) INTO remaining_users
    FROM users AS target
    JOIN restore_account_deletions AS marker ON marker.user_id = target.id;
    IF remaining_documents <> 0 OR remaining_users <> 0 THEN
        RAISE EXCEPTION 'deletion target check failed: % documents, % users remain',
            remaining_documents, remaining_users;
    END IF;
END
$$;

COMMIT;
SQL
} | psql -X -q -v ON_ERROR_STOP=1 \
  -v expected_accounts="$expected_accounts" -v expected_documents="$expected_documents"
then
  fail 'transaction rolled back; deletion targets were not fully re-applied'
fi

printf '%s\n' 'deletion export applied to the isolated restore database'
