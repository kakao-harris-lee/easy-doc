#!/bin/sh

# PostgreSQL local backup and optional Supabase S3 replication.
#
# The local dump is the primary recovery copy. Remote replication is deliberately
# best effort: a remote outage must never stop the local six-hour dump schedule.
# Only age-encrypted objects leave the host. The age identity/private key is not
# used by this container and must remain in a separate restore-only secret store.

set -eu
umask 077

backup_dir=${POSTGRES_BACKUP_DIR:-/backups}
interval_seconds=${POSTGRES_BACKUP_INTERVAL_SECONDS:-21600}
# 개인정보처리방침 §3.2가 7일 보관을 공개한다 — 늘리면 방침을 먼저 개정한다
retention_days=${POSTGRES_BACKUP_RETENTION_DAYS:-7}
remote_enabled=${POSTGRES_BACKUP_REMOTE_ENABLED:-false}
remote_endpoint=${POSTGRES_BACKUP_S3_ENDPOINT:-}
remote_region=${POSTGRES_BACKUP_S3_REGION:-}
remote_bucket=${POSTGRES_BACKUP_S3_BUCKET:-easy-doc-backups}
remote_prefix=${POSTGRES_BACKUP_S3_PREFIX:-postgres}
remote_max_file_bytes=${POSTGRES_BACKUP_REMOTE_MAX_FILE_BYTES:-52428800}
remote_capacity_bytes=${POSTGRES_BACKUP_REMOTE_CAPACITY_BYTES:-}
remote_max_age_seconds=${POSTGRES_BACKUP_REMOTE_MAX_AGE_SECONDS:-28800}
remote_prune_max_objects=${POSTGRES_BACKUP_REMOTE_PRUNE_MAX_OBJECTS:-200}
deletion_max_age_seconds=${POSTGRES_BACKUP_DELETION_MAX_AGE_SECONDS:-180}
deletion_export_interval_seconds=${POSTGRES_BACKUP_DELETION_EXPORT_INTERVAL_SECONDS:-60}
deletion_account_table=${POSTGRES_BACKUP_DELETION_ACCOUNT_TABLE:-account_deletion_tombstones}
deletion_document_table=${POSTGRES_BACKUP_DELETION_DOCUMENT_TABLE:-document_deletion_tombstones}
age_public_key=${POSTGRES_BACKUP_AGE_PUBLIC_KEY:-}

# A dedicated pair of names keeps the .env.example explicit. AWS_* remains
# supported so the AWS CLI can also be injected by a managed secret provider.
if [ -n "${POSTGRES_BACKUP_S3_ACCESS_KEY_ID:-}" ]; then
  export AWS_ACCESS_KEY_ID="$POSTGRES_BACKUP_S3_ACCESS_KEY_ID"
fi
if [ -n "${POSTGRES_BACKUP_S3_SECRET_ACCESS_KEY:-}" ]; then
  export AWS_SECRET_ACCESS_KEY="$POSTGRES_BACKUP_S3_SECRET_ACCESS_KEY"
fi
if [ -n "$remote_region" ]; then
  export AWS_DEFAULT_REGION="$remote_region"
fi
export AWS_EC2_METADATA_DISABLED=true
export AWS_PAGER=
export AWS_CLI_AUTO_PROMPT=off
# Keep one bounded CLI attempt inside the shell-level three-attempt policy.
export AWS_MAX_ATTEMPTS=1

temporary_dump=
temporary_checksum=
temporary_deletion_encrypted=
temporary_remote_download=
temporary_remote_deletion_download=
temporary_remote_manifest=
temporary_remote_manifest_download=
temporary_remote_latest=
temporary_remote_latest_download=
temporary_deletion_manifest=

log() {
  printf '%s\n' "$*" >&2
}

validate_positive_integer() {
  variable_name=$1
  variable_value=$2
  minimum=$3

  case "$variable_value" in
    ''|*[!0-9]*)
      echo "$variable_name must be an integer" >&2
      exit 64
      ;;
  esac

  if [ "$variable_value" -lt "$minimum" ]; then
    echo "$variable_name must be at least $minimum" >&2
    exit 64
  fi
}

validate_optional_sql_identifier() {
  variable_name=$1
  variable_value=$2
  case "$variable_value" in
    ''|*[!A-Za-z0-9_.]*)
      echo "$variable_name must contain only SQL identifier characters" >&2
      exit 64
      ;;
  esac
}

cleanup() {
  [ -z "$temporary_dump" ] || rm -f "$temporary_dump"
  [ -z "$temporary_checksum" ] || rm -f "$temporary_checksum"
  [ -z "$temporary_deletion_encrypted" ] || rm -f "$temporary_deletion_encrypted"
  [ -z "$temporary_remote_download" ] || rm -f "$temporary_remote_download"
  [ -z "$temporary_remote_deletion_download" ] || rm -f "$temporary_remote_deletion_download"
  [ -z "$temporary_remote_manifest" ] || rm -f "$temporary_remote_manifest"
  [ -z "$temporary_remote_manifest_download" ] || rm -f "$temporary_remote_manifest_download"
  [ -z "$temporary_remote_latest" ] || rm -f "$temporary_remote_latest"
  [ -z "$temporary_remote_latest_download" ] || rm -f "$temporary_remote_latest_download"
  [ -z "$temporary_deletion_manifest" ] || rm -f "$temporary_deletion_manifest"
}

reset_temporary_paths() {
  temporary_dump=
  temporary_checksum=
  temporary_deletion_encrypted=
  temporary_remote_download=
  temporary_remote_deletion_download=
  temporary_remote_manifest=
  temporary_remote_manifest_download=
  temporary_remote_latest=
  temporary_remote_latest_download=
  temporary_deletion_manifest=
}

trap 'exit 0' INT TERM
trap cleanup EXIT

validate_positive_integer POSTGRES_BACKUP_INTERVAL_SECONDS "$interval_seconds" 300
validate_positive_integer POSTGRES_BACKUP_RETENTION_DAYS "$retention_days" 1
validate_positive_integer POSTGRES_BACKUP_DELETION_EXPORT_INTERVAL_SECONDS \
  "$deletion_export_interval_seconds" 60
validate_positive_integer POSTGRES_BACKUP_REMOTE_MAX_AGE_SECONDS "$remote_max_age_seconds" 60
validate_positive_integer POSTGRES_BACKUP_REMOTE_PRUNE_MAX_OBJECTS "$remote_prune_max_objects" 1
validate_positive_integer POSTGRES_BACKUP_DELETION_MAX_AGE_SECONDS "$deletion_max_age_seconds" 60
mkdir -p "$backup_dir"

if [ "$remote_prefix" = "/" ]; then
  remote_prefix=postgres
else
  remote_prefix=${remote_prefix#/}
  remote_prefix=${remote_prefix%/}
fi
[ -n "$remote_prefix" ] || remote_prefix=postgres

remote_uri_for_key() {
  key=$1
  printf 's3://%s/%s' "$remote_bucket" "$key"
}

remote_aws() {
  # Do not print the command: an injected AWS CLI wrapper or debug setting must
  # not turn credentials into logs. Endpoint, region and keys stay in env/config.
  aws \
    --endpoint-url "$remote_endpoint" \
    --region "$remote_region" \
    --cli-connect-timeout 5 \
    --cli-read-timeout 30 \
    "$@"
}

file_size() {
  stat -c '%s' "$1" 2>/dev/null || stat -f '%z' "$1"
}

file_mtime() {
  stat -c '%Y' "$1" 2>/dev/null || stat -f '%m' "$1"
}

epoch_now() {
  date -u '+%s'
}

timestamp_to_epoch() {
  timestamp=$1
  # The object name deliberately uses a compact UTC timestamp. Normalize it to
  # the dashed ISO form first: GNU date does not accept the compact form on all
  # Linux images, while BSD date has no -d option at all.
  normalized_timestamp=$(printf '%s\n' "$timestamp" | sed -n \
    's/^\([0-9]\{4\}\)\([0-9]\{2\}\)\([0-9]\{2\}\)T\([0-9]\{2\}\)\([0-9]\{2\}\)\([0-9]\{2\}\)Z$/\1-\2-\3T\4:\5:\6Z/p')
  [ -n "$normalized_timestamp" ] || return 1
  if date -u -d "$normalized_timestamp" '+%s' 2>/dev/null; then
    return 0
  fi
  date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$normalized_timestamp" '+%s'
}

sha256_of() {
  sha256sum "$1" | awk '{print $1}'
}

validate_remote_config() {
  if [ "$remote_enabled" != "true" ]; then
    return 0
  fi

  [ -n "$remote_endpoint" ] || { log "remote backup disabled for this run: POSTGRES_BACKUP_S3_ENDPOINT is missing"; return 1; }
  [ -n "$remote_region" ] || { log "remote backup disabled for this run: POSTGRES_BACKUP_S3_REGION is missing"; return 1; }
  [ -n "$remote_bucket" ] || { log "remote backup disabled for this run: POSTGRES_BACKUP_S3_BUCKET is missing"; return 1; }
  [ -n "$age_public_key" ] || { log "remote backup disabled for this run: POSTGRES_BACKUP_AGE_PUBLIC_KEY is missing"; return 1; }
  [ -n "${AWS_ACCESS_KEY_ID:-}" ] || { log "remote backup disabled for this run: S3 access key is missing"; return 1; }
  [ -n "${AWS_SECRET_ACCESS_KEY:-}" ] || { log "remote backup disabled for this run: S3 secret key is missing"; return 1; }
  [ -n "$remote_capacity_bytes" ] || {
    log "remote backup disabled for this run: POSTGRES_BACKUP_REMOTE_CAPACITY_BYTES must be set after confirming the account quota"
    return 1
  }

  case "$remote_max_file_bytes" in
    ''|*[!0-9]*|0)
      log "remote backup disabled for this run: POSTGRES_BACKUP_REMOTE_MAX_FILE_BYTES must be a positive integer"
      return 1
      ;;
  esac
  case "$remote_capacity_bytes" in
    ''|*[!0-9]*|0)
      log "remote backup disabled for this run: POSTGRES_BACKUP_REMOTE_CAPACITY_BYTES must be a positive integer"
      return 1
      ;;
  esac
  command -v age >/dev/null 2>&1 || { log "remote backup disabled for this run: age is unavailable"; return 1; }
  command -v aws >/dev/null 2>&1 || { log "remote backup disabled for this run: aws is unavailable"; return 1; }
  return 0
}

ensure_postgres_ready() {
  until pg_isready -q; do
    log "PostgreSQL is not ready; retrying in 5 seconds"
    sleep 5
  done
}

prune_local_backups() {
  # -mtime +N is only past N+1 full days, therefore use N-1 to retain seven
  # complete days as disclosed by the privacy policy.
  find "$backup_dir" -maxdepth 1 -type f -name 'easydoc-*.dump' -mtime "+$((retention_days - 1))" -delete
  find "$backup_dir" -maxdepth 1 -type f -name 'easydoc-*.dump.sha256' -mtime "+$((retention_days - 1))" -delete
}

remote_managed_bytes() {
  managed_listing=$(remote_aws s3api list-objects-v2 \
    --bucket "$remote_bucket" \
    --prefix "$remote_prefix/" \
    --query 'Contents[].Size' \
    --output text 2>/dev/null) || return 1
  printf '%s\n' "$managed_listing" | awk '
      { for (i = 1; i <= NF; i++) if ($i ~ /^[0-9]+$/) total += $i }
      END { print total + 0 }
    '
}

remote_capacity_preflight() {
  first_size=$1
  second_size=$2
  first_name=$3
  second_name=$4

  if [ "$first_size" -gt "$remote_max_file_bytes" ]; then
    log "remote backup skipped: $first_name is $first_size bytes, over configured limit $remote_max_file_bytes"
    return 1
  fi
  if [ "$second_size" -gt "$remote_max_file_bytes" ]; then
    log "remote backup skipped: $second_name is $second_size bytes, over configured limit $remote_max_file_bytes"
    return 1
  fi

  used_bytes=$(remote_managed_bytes) || {
    log "remote backup skipped: could not read the configured S3 capacity"
    return 1
  }
  # Include small JSON manifests and latest pointers in the budget. This is a
  # conservative account quota check; no paid capacity is ever provisioned.
  required_bytes=$((used_bytes + first_size + second_size + 8192))
  if [ "$required_bytes" -gt "$remote_capacity_bytes" ]; then
    log "remote backup skipped: configured capacity $remote_capacity_bytes bytes is below required $required_bytes bytes"
    return 1
  fi
  return 0
}

extract_managed_timestamp() {
  candidate=$1
  printf '%s\n' "$candidate" | sed -n \
    -e 's/^easydoc-\([0-9]\{8\}T[0-9]\{6\}Z\)\(-[0-9][0-9]*\)\{0,1\}\.dump\.age$/\1/p' \
    -e 's/^easydoc-\([0-9]\{8\}T[0-9]\{6\}Z\)\(-[0-9][0-9]*\)\{0,1\}\.manifest\.json$/\1/p' \
    -e 's/^easydoc-deletions-\([0-9]\{8\}T[0-9]\{6\}Z\)\(-[0-9][0-9]*\)\{0,1\}\.json\.age$/\1/p' \
    -e 's/^easydoc-deletions-\([0-9]\{8\}T[0-9]\{6\}Z\)\(-[0-9][0-9]*\)\{0,1\}\.manifest\.json$/\1/p'
}

remote_prune_if_due() {
  marker="$backup_dir/.remote-prune-check"
  now=$(epoch_now)
  if [ -f "$marker" ]; then
    marker_age=$((now - $(file_mtime "$marker")))
    if [ "$marker_age" -lt 3600 ]; then
      return 0
    fi
  fi

  cutoff=$((now - retention_days * 86400))
  listing=$(remote_aws s3api list-objects-v2 \
    --bucket "$remote_bucket" \
    --prefix "$remote_prefix/" \
    --query 'Contents[].[Key,LastModified]' \
    --output text 2>/dev/null) || {
      log "remote retention check failed; local backup continues"
      return 1
    }

  # The key shape is the deletion boundary. Objects from another product or a
  # foreign prefix are never passed to delete-object, even if they are old.
  pruned_objects=0
  printf '%s\n' "$listing" | while IFS="$(printf '\t')" read -r key _last_modified; do
    [ "$pruned_objects" -lt "$remote_prune_max_objects" ] || break
    [ -n "${key:-}" ] || continue
    relative=${key#"$remote_prefix/"}
    [ "$relative" != "$key" ] || continue
    timestamp=$(extract_managed_timestamp "$relative")
    [ -n "$timestamp" ] || continue
    object_epoch=$(timestamp_to_epoch "$timestamp" 2>/dev/null) || continue
    if [ "$object_epoch" -le "$cutoff" ]; then
      if ! remote_aws s3api delete-object --bucket "$remote_bucket" --key "$key" >/dev/null 2>&1; then
        log "remote retention could not remove one managed object; stop this pruning pass"
        break
      fi
      pruned_objects=$((pruned_objects + 1))
    fi
  done

  touch "$marker"
  return 0
}

remote_transfer_and_verify() {
  source_path=$1
  object_key=$2
  expected_sha=$3
  download_path=$4
  attempt=1
  remote_uri=$(remote_uri_for_key "$object_key")

  while [ "$attempt" -le 3 ]; do
    rm -f "$download_path"
    if remote_aws s3 cp "$source_path" "$remote_uri" --only-show-errors >/dev/null 2>&1 &&
      remote_aws s3 cp "$remote_uri" "$download_path" --only-show-errors >/dev/null 2>&1 &&
      [ "$(sha256_of "$download_path")" = "$expected_sha" ]; then
      return 0
    fi
    rm -f "$download_path"
    log "remote backup attempt $attempt/3 failed for managed object"
    attempt=$((attempt + 1))
  done
  return 1
}

remote_write_and_verify_manifest() {
  source_path=$1
  object_key=$2
  expected_sha=$3
  download_path=$4
  remote_transfer_and_verify "$source_path" "$object_key" "$expected_sha" "$download_path"
}

publish_dump_remote() {
  backup_name=$1
  backup_path=$2
  backup_epoch=$3
  backup_timestamp=$4
  backup_sha=$5

  validate_remote_config || return 1
  remote_prune_if_due || true

  encrypted_dump="$backup_dir/.$backup_name.age.$$.tmp"
  temporary_remote_download="$backup_dir/.$backup_name.remote.$$.tmp"
  temporary_remote_manifest="$backup_dir/.$backup_name.manifest.$$.tmp"
  temporary_remote_manifest_download="$backup_dir/.$backup_name.manifest.remote.$$.tmp"
  temporary_remote_latest="$backup_dir/.$backup_name.latest.$$.tmp"
  temporary_remote_latest_download="$backup_dir/.$backup_name.latest.remote.$$.tmp"

  age -r "$age_public_key" -o "$encrypted_dump" "$backup_path" >/dev/null 2>&1 || {
    log "remote backup skipped: age encryption failed"
    rm -f "$encrypted_dump"
    return 1
  }
  encrypted_size=$(file_size "$encrypted_dump")
  remote_capacity_preflight "$encrypted_size" 1 "encrypted dump" "manifest" || {
    rm -f "$encrypted_dump"
    return 1
  }
  encrypted_sha=$(sha256_of "$encrypted_dump")
  dump_key="$remote_prefix/$backup_name.age"
  manifest_key="$remote_prefix/$backup_name.manifest.json"
  latest_key="$remote_prefix/latest.json"

  remote_transfer_and_verify "$encrypted_dump" "$dump_key" "$encrypted_sha" \
    "$temporary_remote_download" || {
    log "remote dump upload failed after three verified attempts; local backup remains healthy"
    rm -f "$encrypted_dump"
    return 1
  }

  manifest_json=$(printf '{"schema":"easy-doc.postgres-backup.v1","backup_timestamp":"%s","backup_epoch":%s,"object_key":"%s","encrypted_sha256":"%s","plain_sha256":"%s","encrypted_bytes":%s}' \
    "$backup_timestamp" "$backup_epoch" "$dump_key" "$encrypted_sha" "$backup_sha" "$encrypted_size")
  printf '%s\n' "$manifest_json" >"$temporary_remote_manifest"
  manifest_sha=$(sha256_of "$temporary_remote_manifest")
  remote_write_and_verify_manifest "$temporary_remote_manifest" "$manifest_key" \
    "$manifest_sha" "$temporary_remote_manifest_download" || {
    log "remote dump manifest upload failed after three verified attempts"
    rm -f "$encrypted_dump"
    return 1
  }

  printf '{"schema":"easy-doc.postgres-backup-pointer.v1","backup_timestamp":"%s","backup_epoch":%s,"object_key":"%s","manifest_key":"%s","encrypted_sha256":"%s"}\n' \
    "$backup_timestamp" "$backup_epoch" "$dump_key" "$manifest_key" "$encrypted_sha" >"$temporary_remote_latest"
  latest_sha=$(sha256_of "$temporary_remote_latest")
  remote_write_and_verify_manifest "$temporary_remote_latest" "$latest_key" \
    "$latest_sha" "$temporary_remote_latest_download" || {
    log "remote dump latest pointer upload failed after three verified attempts"
    rm -f "$encrypted_dump"
    return 1
  }

  printf '%s\n' "$backup_timestamp" >"$backup_dir/remote-latest"
  chmod 600 "$backup_dir/remote-latest"
  rm -f "$encrypted_dump" "$temporary_remote_download" "$temporary_remote_manifest" \
    "$temporary_remote_manifest_download" "$temporary_remote_latest" "$temporary_remote_latest_download"
  temporary_remote_download=
  temporary_remote_manifest=
  temporary_remote_manifest_download=
  temporary_remote_latest=
  temporary_remote_latest_download=
  log "remote PostgreSQL backup verified: $backup_name"
  return 0
}

load_deletion_state() {
  deletion_available=true
  deletion_account_json='[]'
  deletion_document_json='[]'
  validate_optional_sql_identifier POSTGRES_BACKUP_DELETION_ACCOUNT_TABLE "$deletion_account_table"
  validate_optional_sql_identifier POSTGRES_BACKUP_DELETION_DOCUMENT_TABLE "$deletion_document_table"

  account_regclass=$(psql -X -qAt -c "select to_regclass('$deletion_account_table')" 2>/dev/null || true)
  document_regclass=$(psql -X -qAt -c "select to_regclass('$deletion_document_table')" 2>/dev/null || true)
  if [ -z "$account_regclass" ] || [ -z "$document_regclass" ]; then
    deletion_available=false
    return 0
  fi

  deletion_account_json=$(psql -X -qAt -c \
    "select coalesce(json_agg(json_build_object('id', id::text, 'user_id', user_id::text, 'deleted_at', deleted_at, 'expires_at', expires_at) order by deleted_at)::text, '[]') from $deletion_account_table where expires_at > current_timestamp" \
    2>/dev/null) || deletion_available=false
  deletion_document_json=$(psql -X -qAt -c \
    "select coalesce(json_agg(json_build_object('id', id::text, 'deleted_at', deleted_at, 'expires_at', expires_at) order by deleted_at)::text, '[]') from $deletion_document_table where expires_at > current_timestamp" \
    2>/dev/null) || deletion_available=false
  case "$deletion_account_json" in \[*\]) ;; *) deletion_available=false; deletion_account_json='[]' ;; esac
  case "$deletion_document_json" in \[*\]) ;; *) deletion_available=false; deletion_document_json='[]' ;; esac
}

write_deletion_manifest() {
  captured_at=$1
  captured_epoch=$2
  if [ "$deletion_available" = true ]; then
    printf '{"schema":"easy-doc.deletion-manifest.v1","captured_at":"%s","captured_epoch":%s,"available":true,"account_deletions":%s,"document_deletions":%s}\n' \
      "$captured_at" "$captured_epoch" "$deletion_account_json" "$deletion_document_json"
  else
    printf '{"schema":"easy-doc.deletion-manifest.v1","captured_at":"%s","captured_epoch":%s,"available":false,"account_deletions":[],"document_deletions":[]}\n' \
      "$captured_at" "$captured_epoch"
  fi
}

publish_deletion_remote() {
  validate_remote_config || return 1
  load_deletion_state
  deletion_timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
  deletion_object_timestamp="$deletion_timestamp-$$"
  deletion_epoch=$(epoch_now)
  deletion_key="$remote_prefix/easydoc-deletions-$deletion_object_timestamp.json.age"
  deletion_manifest_key="$remote_prefix/easydoc-deletions-$deletion_object_timestamp.manifest.json"
  deletion_latest_key="$remote_prefix/deletions-latest.json"
  temporary_deletion_encrypted="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.$$.tmp"
  temporary_remote_deletion_download="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.remote.$$.tmp"
  temporary_remote_manifest="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.manifest.$$.tmp"
  temporary_remote_manifest_download="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.manifest.remote.$$.tmp"
  temporary_remote_latest="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.latest.$$.tmp"
  temporary_remote_latest_download="$backup_dir/.easydoc-deletions-$deletion_object_timestamp.latest.remote.$$.tmp"

  # The tombstone JSON is streamed through age, so IDs never become a
  # plaintext persistent file or a log line. Only the encrypted object is kept
  # temporarily until its remote round trip is verified.
  write_deletion_manifest "$deletion_timestamp" "$deletion_epoch" |
    age -r "$age_public_key" -o "$temporary_deletion_encrypted" - >/dev/null 2>&1 || {
    log "remote deletion export skipped: age encryption failed"
    return 1
  }
  deletion_size=$(file_size "$temporary_deletion_encrypted")
  remote_prune_if_due || true
  remote_capacity_preflight "$deletion_size" 1 "encrypted deletion export" "manifest" || return 1
  deletion_sha=$(sha256_of "$temporary_deletion_encrypted")

  remote_transfer_and_verify "$temporary_deletion_encrypted" "$deletion_key" "$deletion_sha" \
    "$temporary_remote_deletion_download" || {
    log "remote deletion export failed after three verified attempts"
    return 1
  }

  deletion_manifest_json=$(printf '{"schema":"easy-doc.deletion-export.v1","captured_at":"%s","captured_epoch":%s,"available":%s,"object_key":"%s","encrypted_sha256":"%s","encrypted_bytes":%s}' \
    "$deletion_timestamp" "$deletion_epoch" "$deletion_available" "$deletion_key" "$deletion_sha" "$deletion_size")
  printf '%s\n' "$deletion_manifest_json" >"$temporary_remote_manifest"
  deletion_manifest_sha=$(sha256_of "$temporary_remote_manifest")
  remote_write_and_verify_manifest "$temporary_remote_manifest" "$deletion_manifest_key" \
    "$deletion_manifest_sha" "$temporary_remote_manifest_download" || {
    log "remote deletion manifest upload failed after three verified attempts"
    return 1
  }

  printf '{"schema":"easy-doc.deletion-pointer.v1","captured_at":"%s","captured_epoch":%s,"available":%s,"object_key":"%s","manifest_key":"%s","encrypted_sha256":"%s"}\n' \
    "$deletion_timestamp" "$deletion_epoch" "$deletion_available" "$deletion_key" "$deletion_manifest_key" "$deletion_sha" >"$temporary_remote_latest"
  deletion_latest_sha=$(sha256_of "$temporary_remote_latest")
  remote_write_and_verify_manifest "$temporary_remote_latest" "$deletion_latest_key" \
    "$deletion_latest_sha" "$temporary_remote_latest_download" || {
    log "remote deletion latest pointer upload failed after three verified attempts"
    return 1
  }

  rm -f "$temporary_deletion_encrypted" "$temporary_remote_deletion_download" "$temporary_remote_manifest" \
    "$temporary_remote_manifest_download" "$temporary_remote_latest" "$temporary_remote_latest_download"
  temporary_deletion_encrypted=
  temporary_remote_deletion_download=
  temporary_remote_manifest=
  temporary_remote_manifest_download=
  temporary_remote_latest=
  temporary_remote_latest_download=
  if [ "$deletion_available" = true ]; then
    log "remote deletion export verified"
  else
    log "remote deletion export verified with unavailable tombstone tables"
  fi
  return 0
}

run_dump_once() {
  backup_timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
  backup_name="easydoc-$backup_timestamp.dump"
  backup_path="$backup_dir/$backup_name"
  if [ -e "$backup_path" ]; then
    backup_name="easydoc-$backup_timestamp-$$.dump"
    backup_path="$backup_dir/$backup_name"
  fi
  temporary_dump="$backup_dir/.$backup_name.$$.tmp"
  temporary_checksum="$backup_dir/.$backup_name.sha256.$$.tmp"

  pg_dump \
    --format=custom \
    --compress=6 \
    --no-owner \
    --no-privileges \
    --file="$temporary_dump"
  pg_restore --list "$temporary_dump" >/dev/null
  mv "$temporary_dump" "$backup_path"
  temporary_dump=

  (
    cd "$backup_dir"
    sha256sum "$backup_name" >"$(basename "$temporary_checksum")"
  )
  mv "$temporary_checksum" "$backup_path.sha256"
  temporary_checksum=
  ln -sfn "$backup_name" "$backup_dir/latest.dump"
  ln -sfn "$backup_name.sha256" "$backup_dir/latest.dump.sha256"
  prune_local_backups

  backup_epoch=$(epoch_now)
  backup_sha=$(sha256_of "$backup_path")
  if [ "$remote_enabled" = true ]; then
    publish_dump_remote "$backup_name" "$backup_path" "$backup_epoch" \
      "$backup_timestamp" "$backup_sha" || true
  else
    log "remote PostgreSQL backup disabled; local backup remains enabled"
  fi
  log "PostgreSQL backup completed: $backup_name"
  return 0
}

health_local() {
  latest_dump="$backup_dir/latest.dump"
  latest_checksum="$backup_dir/latest.dump.sha256"
  if [ ! -L "$latest_dump" ] || [ ! -f "$latest_checksum" ] ||
    ! pg_restore --list "$latest_dump" >/dev/null 2>&1 ||
    ! (cd "$backup_dir" && sha256sum --check "$(basename "$latest_checksum")" >/dev/null 2>&1); then
    log "backup health: local=unhealthy"
    return 1
  fi
  local_age=$(( $(epoch_now) - $(file_mtime "$latest_dump") ))
  if [ "$local_age" -gt "$remote_max_age_seconds" ]; then
    log "backup health: local=unhealthy (latest dump is older than freshness limit)"
    return 1
  fi
  log "backup health: local=healthy"
  return 0
}

health_remote_pointer() {
  pointer_key=$1
  pointer_path=$2
  expected_kind=$3
  freshness_limit=$4
  remote_aws s3 cp "$(remote_uri_for_key "$pointer_key")" "$pointer_path" --only-show-errors >/dev/null 2>&1 || return 1
  pointer_epoch=$(sed -n 's/.*"backup_epoch"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$pointer_path" | head -n 1)
  if [ -z "$pointer_epoch" ]; then
    pointer_epoch=$(sed -n 's/.*"captured_epoch"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' "$pointer_path" | head -n 1)
  fi
  pointer_object=$(sed -n 's/.*"object_key"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$pointer_path" | head -n 1)
  [ -n "$pointer_epoch" ] || return 1
  [ -n "$pointer_object" ] || return 1
  pointer_relative=${pointer_object#"$remote_prefix/"}
  [ "$pointer_relative" != "$pointer_object" ] || return 1
  case "$expected_kind" in
    dump)
      case "$pointer_relative" in easydoc-*.dump.age) ;; *) return 1 ;; esac
      ;;
    deletion)
      case "$pointer_relative" in easydoc-deletions-*.json.age) ;; *) return 1 ;; esac
      ;;
    *) return 1 ;;
  esac
  [ -n "$(extract_managed_timestamp "$pointer_relative")" ] || return 1
  remote_aws s3api head-object --bucket "$remote_bucket" --key "$pointer_object" >/dev/null 2>&1 || return 1
  pointer_age=$(( $(epoch_now) - pointer_epoch ))
  [ "$pointer_age" -ge 0 ] || return 1
  [ "$pointer_age" -le "$freshness_limit" ] || return 1
  return 0
}

health_remote() {
  if [ "$remote_enabled" != "true" ]; then
    log "backup health: remote=disabled"
    return 0
  fi
  validate_remote_config || {
    log "backup health: remote=unhealthy (configuration incomplete)"
    return 1
  }
  temporary_remote_latest_download="$backup_dir/.health-latest.$$.tmp"
  temporary_remote_deletion_download="$backup_dir/.health-deletions-latest.$$.tmp"
  if ! health_remote_pointer "$remote_prefix/latest.json" "$temporary_remote_latest_download" dump "$remote_max_age_seconds"; then
    rm -f "$temporary_remote_latest_download" "$temporary_remote_deletion_download"
    temporary_remote_latest_download=
    temporary_remote_deletion_download=
    log "backup health: remote=unhealthy (dump pointer is stale or unavailable)"
    return 1
  fi
  if ! health_remote_pointer "$remote_prefix/deletions-latest.json" "$temporary_remote_deletion_download" deletion "$deletion_max_age_seconds"; then
    rm -f "$temporary_remote_latest_download" "$temporary_remote_deletion_download"
    temporary_remote_latest_download=
    temporary_remote_deletion_download=
    log "backup health: remote=unhealthy (deletion export is stale or unavailable)"
    return 1
  fi
  deletion_available_value=$(sed -n 's/.*"available"[[:space:]]*:[[:space:]]*\([[:alpha:]]*\).*/\1/p' "$temporary_remote_deletion_download" | head -n 1)
  rm -f "$temporary_remote_latest_download" "$temporary_remote_deletion_download"
  temporary_remote_latest_download=
  temporary_remote_deletion_download=
  if [ "$deletion_available_value" != true ]; then
    log "backup health: remote=unhealthy (deletion export reports unavailable tombstone tables)"
    return 1
  fi
  log "backup health: remote=healthy"
  return 0
}

health() {
  local_status=0
  remote_status=0
  health_local || local_status=$?
  health_remote || remote_status=$?
  if [ "$local_status" -ne 0 ] || [ "$remote_status" -ne 0 ]; then
    return 1
  fi
  return 0
}

run_once() {
  ensure_postgres_ready
  run_dump_once
  cleanup
  reset_temporary_paths
  if [ "$remote_enabled" = true ]; then
    publish_deletion_remote || true
  fi
  cleanup
  reset_temporary_paths
}

run_loop() {
  ensure_postgres_ready
  if [ "$remote_enabled" != "true" ]; then
    while :; do
      run_dump_once
      cleanup
      reset_temporary_paths
      sleep "$interval_seconds" &
      wait "$!"
    done
  fi

  next_dump=0
  next_deletion=0
  while :; do
    now=$(epoch_now)
    if [ "$now" -ge "$next_dump" ]; then
      run_dump_once
      next_dump=$((now + interval_seconds))
      cleanup
      reset_temporary_paths
    fi
    if [ "$now" -ge "$next_deletion" ]; then
      publish_deletion_remote || true
      next_deletion=$((now + deletion_export_interval_seconds))
      cleanup
      reset_temporary_paths
    fi
    sleep_seconds=60
    until_dump=$((next_dump - now))
    until_deletion=$((next_deletion - now))
    if [ "$until_dump" -gt 0 ] && [ "$until_dump" -lt "$sleep_seconds" ]; then
      sleep_seconds=$until_dump
    fi
    if [ "$until_deletion" -gt 0 ] && [ "$until_deletion" -lt "$sleep_seconds" ]; then
      sleep_seconds=$until_deletion
    fi
    [ "$sleep_seconds" -gt 0 ] || sleep_seconds=1
    sleep "$sleep_seconds" &
    wait "$!"
  done
}

case "${1:-loop}" in
  once)
    run_once
    ;;
  deletion-once)
    ensure_postgres_ready
    if [ "$remote_enabled" = true ]; then
      publish_deletion_remote
    else
      log "remote deletion export disabled"
    fi
    ;;
  health)
    health
    ;;
  loop)
    run_loop
    ;;
  *)
    echo "usage: $0 [loop|once|deletion-once|health]" >&2
    exit 64
    ;;
esac
