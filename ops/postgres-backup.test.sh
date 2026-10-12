#!/bin/sh

# Focused boundary tests for the backup service. They use local command stubs,
# never contact an S3 endpoint, and exercise the Compose entry point.

set -eu

script_dir=$(cd -- "$(dirname "$0")" && pwd)
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

bin_dir="$work_dir/bin"
backup_dir="$work_dir/backups"
remote_dir="$work_dir/remote"
mkdir -p "$bin_dir" "$backup_dir" "$remote_dir"

cat >"$bin_dir/pg_isready" <<'STUB'
#!/bin/sh
exit 0
STUB

cat >"$bin_dir/pg_dump" <<'STUB'
#!/bin/sh
for argument in "$@"; do
  case "$argument" in
    --file=*) dump_path=${argument#--file=} ;;
  esac
done
printf 'stub postgres custom dump\n' >"$dump_path"
STUB

cat >"$bin_dir/pg_restore" <<'STUB'
#!/bin/sh
exit 0
STUB

cat >"$bin_dir/psql" <<'STUB'
#!/bin/sh
query=$*
case "$query" in
  *"to_regclass('account_deletion_tombstones')"*) printf 'account_deletion_tombstones\n' ;;
  *"to_regclass('document_deletion_tombstones')"*) printf 'document_deletion_tombstones\n' ;;
  *"user_id"*) printf '[{"id":"account-marker-1","user_id":"user-1","deleted_at":"2026-10-11T00:00:00Z","expires_at":"2026-10-19T00:00:00Z"}]\n' ;;
  *"json_build_object('id', id::text"*) printf '[{"id":"document-marker-1","deleted_at":"2026-10-11T00:00:00Z","expires_at":"2026-10-19T00:00:00Z"}]\n' ;;
  *) exit 1 ;;
esac
STUB

cat >"$bin_dir/age" <<'STUB'
#!/bin/sh
set -eu
output=
input=
while [ "$#" -gt 0 ]; do
  case "$1" in
    -o) output=$2; shift 2 ;;
    -r) shift 2 ;;
    -) input=-; shift ;;
    *) input=$1; shift ;;
  esac
done
[ -n "$output" ]
if [ "$input" = - ]; then
  base64 < /dev/stdin >"$output"
else
  base64 <"$input" >"$output"
fi
STUB

cat >"$bin_dir/aws" <<'STUB'
#!/bin/sh
set -eu

root=${AWS_STUB_ROOT:?}
fail_file=${AWS_STUB_FAIL_FILE:?}
delete_log=${AWS_STUB_DELETE_LOG:?}
mkdir -p "$root"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --endpoint-url|--region|--cli-connect-timeout|--cli-read-timeout) shift 2 ;;
    *) break ;;
  esac
done
service=$1
action=$2
shift 2

remote_path() {
  uri=$1
  printf '%s\n' "$uri" | sed 's#^s3://[^/]*/##'
}

copy_file() {
  source=$1
  destination=$2
  case "$source" in
    s3://*) source_path="$root/$(remote_path "$source")"; destination_path=$destination ;;
    *) source_path=$source; destination_path="$root/$(remote_path "$destination")" ;;
  esac
  if [ -s "$fail_file" ] && [ "$(cat "$fail_file")" -gt 0 ]; then
    failures=$(cat "$fail_file")
    printf '%s\n' "$((failures - 1))" >"$fail_file"
    exit 1
  fi
  mkdir -p "$(dirname "$destination_path")"
  cp "$source_path" "$destination_path"
}

case "$service:$action" in
  s3:cp)
    source=
    destination=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --*) shift ;;
        *)
          if [ -z "$source" ]; then source=$1; else destination=$1; fi
          shift
          ;;
      esac
    done
    copy_file "$source" "$destination"
    ;;
  s3api:list-objects-v2)
    prefix=
    query=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --prefix) prefix=$2; shift 2 ;;
        --query) query=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    find "$root/$prefix" -type f -print 2>/dev/null | while IFS= read -r path; do
      key=${path#"$root/"}
      if printf '%s' "$query" | grep -q 'Size'; then
        wc -c <"$path"
      else
        printf '%s\t2020-01-01T00:00:00.000Z\n' "$key"
      fi
    done
    ;;
  s3api:head-object)
    key=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    [ -f "$root/$key" ]
    ;;
  s3api:delete-object)
    key=
    while [ "$#" -gt 0 ]; do
      case "$1" in
        --key) key=$2; shift 2 ;;
        *) shift ;;
      esac
    done
    rm -f "$root/$key"
    printf '%s\n' "$key" >>"$delete_log"
    ;;
  *)
    echo "unexpected aws stub command: $service $action" >&2
    exit 1
    ;;
esac
STUB

chmod +x "$bin_dir"/*

fail_file="$work_dir/aws-failures"
delete_log="$work_dir/deletes"
: >"$fail_file"
: >"$delete_log"

common_env() {
  export PATH="$bin_dir:$PATH"
  export POSTGRES_BACKUP_DIR="$backup_dir"
  export POSTGRES_BACKUP_INTERVAL_SECONDS=300
  export POSTGRES_BACKUP_RETENTION_DAYS=7
  export POSTGRES_BACKUP_REMOTE_ENABLED=true
  export POSTGRES_BACKUP_S3_ENDPOINT=http://s3.stub.invalid
  export POSTGRES_BACKUP_S3_REGION=ap-northeast-2
  export POSTGRES_BACKUP_S3_BUCKET=easy-doc-backups
  export POSTGRES_BACKUP_S3_PREFIX=postgres
  export POSTGRES_BACKUP_S3_ACCESS_KEY_ID=test-access
  export POSTGRES_BACKUP_S3_SECRET_ACCESS_KEY=test-secret
  export POSTGRES_BACKUP_AGE_PUBLIC_KEY=age1testpublickey
  export POSTGRES_BACKUP_REMOTE_MAX_FILE_BYTES=52428800
  export POSTGRES_BACKUP_REMOTE_CAPACITY_BYTES=1000000
  export POSTGRES_BACKUP_REMOTE_PRUNE_MAX_OBJECTS=100
  export POSTGRES_BACKUP_REMOTE_MAX_AGE_SECONDS=28800
  export POSTGRES_BACKUP_DELETION_EXPORT_INTERVAL_SECONDS=60
  export AWS_STUB_ROOT="$remote_dir"
  export AWS_STUB_FAIL_FILE="$fail_file"
  export AWS_STUB_DELETE_LOG="$delete_log"
}

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

common_env
"$script_dir/postgres-backup.sh" once >/dev/null 2>"$work_dir/success.log"
[ -L "$backup_dir/latest.dump" ] || fail "local latest dump was not created"
[ -f "$remote_dir/postgres/latest.json" ] || fail "remote latest pointer was not created"
[ -f "$remote_dir/postgres/deletions-latest.json" ] || fail "deletion latest pointer was not created"
find "$remote_dir/postgres" -name '*.age' -type f | grep -q . || fail "encrypted remote objects were not created"
if grep -R -q 'user-1\|account-marker-1' "$remote_dir/postgres"; then
  fail "deletion marker appeared as plaintext in remote objects"
fi
deletion_object=$(find "$remote_dir/postgres" -name 'easydoc-deletions-*.json.age' -type f | head -n 1)
if ! base64 -d -i "$deletion_object" >"$work_dir/deletions.json" 2>/dev/null; then
  base64 -D -i "$deletion_object" >"$work_dir/deletions.json"
fi
grep -q '"captured_epoch":[0-9]' "$work_dir/deletions.json" || fail "deletion export epoch was not captured"
"$script_dir/postgres-backup.sh" health >/dev/null 2>"$work_dir/healthy.log" || fail "healthy remote state failed health"

# A fresh database dump does not make a stale deletion export healthy. This is
# the fail-closed boundary for restoring accounts or documents that were erased.
printf '{"schema":"easy-doc.deletion-pointer.v1","captured_epoch":1,"available":true,"object_key":"postgres/easydoc-deletions-20200101T000000Z.json.age"}\n' >"$remote_dir/postgres/deletions-latest.json"
if "$script_dir/postgres-backup.sh" health >/dev/null 2>"$work_dir/stale-deletions.log"; then
  fail "stale deletion export was reported healthy"
fi

# Remote outage is retried three times but does not prevent the local dump.
printf '100\n' >"$fail_file"
"$script_dir/postgres-backup.sh" once >/dev/null 2>"$work_dir/outage.log" || fail "remote outage stopped local scheduling"
[ -L "$backup_dir/latest.dump" ] || fail "local latest dump disappeared after remote outage"
grep -q 'attempt 3/3' "$work_dir/outage.log" || fail "remote outage did not reach the retry boundary"
: >"$fail_file"

# Retention deletes only managed key shapes below the configured prefix.
mkdir -p "$remote_dir/postgres"
printf 'old managed dump' >"$remote_dir/postgres/easydoc-20200101T000000Z.dump.age"
printf 'old managed manifest' >"$remote_dir/postgres/easydoc-20200101T000000Z.manifest.json"
printf 'foreign object' >"$remote_dir/postgres/foreign-20200101T000000Z.dump.age"
printf 'malformed managed-looking object' >"$remote_dir/postgres/easydoc-20200101T000000Z-abc.dump.age"
mkdir -p "$remote_dir/postgres/nested"
printf 'nested foreign object' >"$remote_dir/postgres/nested/easydoc-20200101T000000Z.dump.age"
touch -t 202001010000 "$backup_dir/.remote-prune-check"
"$script_dir/postgres-backup.sh" once >/dev/null 2>"$work_dir/retention.log"
[ ! -e "$remote_dir/postgres/easydoc-20200101T000000Z.dump.age" ] || fail "old managed dump was retained"
[ ! -e "$remote_dir/postgres/easydoc-20200101T000000Z.manifest.json" ] || fail "old managed manifest was retained"
[ -e "$remote_dir/postgres/foreign-20200101T000000Z.dump.age" ] || fail "foreign object was deleted"
[ -e "$remote_dir/postgres/easydoc-20200101T000000Z-abc.dump.age" ] || fail "malformed object was deleted"
[ -e "$remote_dir/postgres/nested/easydoc-20200101T000000Z.dump.age" ] || fail "nested object was deleted"

# A retention sweep is bounded so a large old prefix cannot delay the local dump.
printf 'old managed dump a' >"$remote_dir/postgres/easydoc-20200101T000001Z.dump.age"
printf 'old managed dump b' >"$remote_dir/postgres/easydoc-20200101T000002Z.dump.age"
export POSTGRES_BACKUP_REMOTE_PRUNE_MAX_OBJECTS=1
touch -t 202001010000 "$backup_dir/.remote-prune-check"
"$script_dir/postgres-backup.sh" once >/dev/null 2>"$work_dir/bounded-retention.log"
if [ ! -e "$remote_dir/postgres/easydoc-20200101T000001Z.dump.age" ] &&
  [ ! -e "$remote_dir/postgres/easydoc-20200101T000002Z.dump.age" ]; then
  fail "bounded retention deleted more than one managed object"
fi
if [ -e "$remote_dir/postgres/easydoc-20200101T000001Z.dump.age" ] &&
  [ -e "$remote_dir/postgres/easydoc-20200101T000002Z.dump.age" ]; then
  fail "bounded retention did not delete one eligible managed object"
fi
export POSTGRES_BACKUP_REMOTE_PRUNE_MAX_OBJECTS=100

# File-limit failure is clear but local scheduling still completes.
export POSTGRES_BACKUP_REMOTE_MAX_FILE_BYTES=1
"$script_dir/postgres-backup.sh" once >/dev/null 2>"$work_dir/limit.log" || fail "file limit stopped local scheduling"
grep -q 'over configured limit' "$work_dir/limit.log" || fail "file limit failure was not reported"
export POSTGRES_BACKUP_REMOTE_MAX_FILE_BYTES=52428800

# Freshness is unhealthy after eight hours; explicit remote OFF is healthy.
printf '{"schema":"easy-doc.postgres-backup-pointer.v1","backup_epoch":1,"object_key":"postgres/easydoc-20200101T000000Z.dump.age"}\n' >"$remote_dir/postgres/latest.json"
if "$script_dir/postgres-backup.sh" health >/dev/null 2>"$work_dir/stale.log"; then
  fail "stale remote pointer was reported healthy"
fi
export POSTGRES_BACKUP_REMOTE_ENABLED=false
"$script_dir/postgres-backup.sh" health >/dev/null 2>"$work_dir/disabled.log" || fail "disabled remote health failed"
grep -q 'remote=disabled' "$work_dir/disabled.log" || fail "disabled remote health was not explicit"

echo "postgres-backup boundary tests passed"
