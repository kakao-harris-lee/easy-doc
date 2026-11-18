#!/usr/bin/env bash
# 토스 심사용 계정(toss-review@easydoc.kr)의 비밀번호를 초기화하고
# 이메일·휴대폰 인증 완료로 표시한다.
#
# 비밀번호는 환경변수 TOSS_REVIEW_PW 로만 받는다. 인자·로그·셸 기록에 남기지 않는다.
# 셸 환경에 남지 않도록 이 명령에만 넘긴다:
#   read -rs -p 'PW: ' pw && echo
#   DRY_RUN=1 TOSS_REVIEW_PW="$pw" ops/toss-billing-review/reset-review-account.sh  # 끝에서 ROLLBACK
#   DRY_RUN=0 TOSS_REVIEW_PW="$pw" ops/toss-billing-review/reset-review-account.sh  # 적용
#   unset pw
#
# 해시는 앱의 Argon2PasswordHasher 와 같은 argon2id 정책으로 만든다. 정책의 정본은
# backend-kotlin/infrastructure/.../auth/AuthConfiguration.kt 의 Argon2Properties
# (v=19, m=65536, t=3, p=4, salt 16B, hash 32B)다. 정책이 다르면 로그인은 되지만
# 로그인할 때마다 재해시 대상이 된다.
set -Eeuo pipefail
set +x
umask 077

EMAIL='toss-review@easydoc.kr'
PG_CONTAINER="${PG_CONTAINER:-easy-doc-postgres-1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/.venv-reset"

fail() {
  echo "$1" >&2
  exit 1
}

command -v docker >/dev/null || fail "docker 가 필요합니다."
command -v python3 >/dev/null || fail "python3 가 필요합니다."

# 실수로 실제 DB에 쓰지 않도록 DRY_RUN 은 0 또는 1 만 받는다.
case "${DRY_RUN:-}" in
  1) END='ROLLBACK' ;;
  0) END='COMMIT' ;;
  *) fail "DRY_RUN=1(시험, 되돌림) 또는 DRY_RUN=0(적용)을 지정하세요." ;;
esac

[ -n "${TOSS_REVIEW_PW:-}" ] || fail "TOSS_REVIEW_PW 환경변수가 비어 있습니다."

# 가상환경 준비 단계에는 비밀번호를 넘기지 않는다(외부 패키지 설치 코드가 돈다).
# 설치가 중간에 실패한 가상환경을 다시 쓰지 않도록 인터프리터가 아니라 모듈로 확인한다.
if ! [ -x "$VENV/bin/python" ] || ! env -u TOSS_REVIEW_PW "$VENV/bin/python" -I -c 'import argon2' 2>/dev/null; then
  rm -rf "$VENV"
  env -u TOSS_REVIEW_PW python3 -m venv "$VENV"
  env -u TOSS_REVIEW_PW "$VENV/bin/pip" install --quiet --no-cache-dir 'argon2-cffi==23.1.0'
fi

# 비밀번호는 환경변수로만 파이썬에 넘긴다. 출력은 해시 한 줄뿐이다.
HASH="$("$VENV/bin/python" -I - <<'PY'
import base64, os, re, sys
from argon2 import PasswordHasher, Type
pw = os.environ["TOSS_REVIEW_PW"]
# 계약 min_password_length(8)와 같은 기준(Kotlin String.length)으로 센다.
if len(pw.encode("utf-16-le")) // 2 < 8:
    sys.exit("비밀번호는 8자 이상이어야 합니다(계약 min_password_length).")
ph = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4,
                    hash_len=32, salt_len=16, type=Type.ID)
h = ph.hash(pw)
assert ph.verify(h, pw)
m = re.fullmatch(r"\$argon2id\$v=19\$m=65536,t=3,p=4\$([A-Za-z0-9+/]+)\$([A-Za-z0-9+/]+)", h)
assert m and len(base64.b64decode(m[1] + "==")) == 16 and len(base64.b64decode(m[2] + "=")) == 32
sys.stdout.write(h)
PY
)"

# 해시는 docker/psql 인자가 아니라 stdin 으로 넘긴다(ps 에 보이지 않게).
# RETURNING 은 갱신 뒤 값이라, 인증 시각을 그대로 보여 기존 값이 유지됐는지 확인하게 한다.
docker exec -i "$PG_CONTAINER" sh -c 'psql -X -q -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -At -F" | "' <<SQL
\\set hash '$HASH'
\\set email '$EMAIL'
BEGIN;
DO \$\$
BEGIN
  IF (SELECT count(*) FROM users WHERE email = '$EMAIL') <> 1 THEN
    RAISE EXCEPTION '심사 계정 행이 정확히 1개가 아닙니다';
  END IF;
END
\$\$;
UPDATE users
SET password_hash = :'hash',
    email_verified_at = COALESCE(email_verified_at, now()),
    phone_verified_at = COALESCE(phone_verified_at, now()),
    pending_phone_fingerprint = NULL,
    pending_phone_verification_id = NULL
WHERE email = :'email'
RETURNING 'updated', id,
          'email_verified_at=' || email_verified_at,
          'phone_verified_at=' || phone_verified_at;
$END;
SQL

unset HASH
echo "완료($END): $EMAIL 비밀번호 초기화, 이메일·휴대폰 인증 표시"
