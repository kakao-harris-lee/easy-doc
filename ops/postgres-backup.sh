#!/bin/sh

set -eu
umask 077

backup_dir=/backups
interval_seconds=${POSTGRES_BACKUP_INTERVAL_SECONDS:-21600}
retention_days=${POSTGRES_BACKUP_RETENTION_DAYS:-30}
temporary_dump=
temporary_checksum=

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

cleanup() {
  [ -z "$temporary_dump" ] || rm -f "$temporary_dump"
  [ -z "$temporary_checksum" ] || rm -f "$temporary_checksum"
}

trap 'exit 0' INT TERM
trap cleanup EXIT

validate_positive_integer POSTGRES_BACKUP_INTERVAL_SECONDS "$interval_seconds" 300
validate_positive_integer POSTGRES_BACKUP_RETENTION_DAYS "$retention_days" 1
mkdir -p "$backup_dir"

until pg_isready -q; do
  echo "PostgreSQL is not ready; retrying in 5 seconds" >&2
  sleep 5
done

while :; do
  backup_timestamp=$(date -u '+%Y%m%dT%H%M%SZ')
  backup_name="easydoc-$backup_timestamp.dump"
  backup_path="$backup_dir/$backup_name"
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

  find "$backup_dir" -maxdepth 1 -type f -name 'easydoc-*.dump' -mtime "+$retention_days" -delete
  find "$backup_dir" -maxdepth 1 -type f -name 'easydoc-*.dump.sha256' -mtime "+$retention_days" -delete

  echo "PostgreSQL backup completed: $backup_name"
  sleep "$interval_seconds" &
  wait "$!"
done
