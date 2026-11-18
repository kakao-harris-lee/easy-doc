#!/usr/bin/env bash
# 토스 심사용 계정(toss-review@easydoc.kr)의 비밀번호를 초기화하고
# 이메일·휴대폰 인증 완료로 표시한다.
#
# 비밀번호는 환경변수 TOSS_REVIEW_PW 로만 받는다. 인자·로그·셸 기록에 남기지 않는다.
#   read -rs -p 'PW: ' TOSS_REVIEW_PW && export TOSS_REVIEW_PW && echo
#   ops/toss-billing-review/reset-review-account.sh            # 적용
#   DRY_RUN=1 ops/toss-billing-review/reset-review-account.sh  # 끝에서 ROLLBACK
#   unset TOSS_REVIEW_PW
#
# 해시는 앱의 Argon2PasswordHasher 와 같은 argon2id 정책
# (v=19, m=65536, t=3, p=4, salt 16B, hash 32B)으로 만든다. 정책이 다르면 로그인은
# 되지만 매 로그인마다 재해시 대상이 된다.
set -euo pipefail
set +x

EMAIL='toss-review@easydoc.kr'
PG_CONTAINER="${PG_CONTAINER:-easy-doc-postgres-1}"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV="$HERE/.venv-reset"

if [ -z "${TOSS_REVIEW_PW:-}" ]; then
  echo "TOSS_REVIEW_PW 환경변수가 비어 있습니다." >&2
  exit 1
fi
if [ "${#TOSS_REVIEW_PW}" -lt 8 ]; then
  echo "비밀번호는 8자 이상이어야 합니다(계약 min_password_length)." >&2
  exit 1
fi

if [ ! -x "$VENV/bin/python" ]; then
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install --quiet 'argon2-cffi==23.1.0'
fi

# 비밀번호는 환경변수로만 파이썬에 넘긴다. 출력은 해시 한 줄뿐이다.
HASH="$("$VENV/bin/python" -I - <<'PY'
import os, sys
from argon2 import PasswordHasher, Type
pw = os.environ["TOSS_REVIEW_PW"]
ph = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=4,
                    hash_len=32, salt_len=16, type=Type.ID)
h = ph.hash(pw)
assert ph.verify(h, pw)
assert h.startswith("$argon2id$v=19$m=65536,t=3,p=4$")
sys.stdout.write(h)
PY
)"

if [ "${DRY_RUN:-0}" = "1" ]; then END='ROLLBACK'; else END='COMMIT'; fi

# 해시는 docker/psql 인자가 아니라 stdin 으로 넘긴다(ps 에 보이지 않게).
docker exec -i "$PG_CONTAINER" sh -c 'psql -X -q -U "$POSTGRES_USER" -d easydoc -v ON_ERROR_STOP=1 -At -F" | "' <<SQL
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
          'email_verified=' || (email_verified_at IS NOT NULL),
          'phone_verified=' || (phone_verified_at IS NOT NULL);
$END;
SQL

unset HASH
echo "완료($END): $EMAIL 비밀번호 초기화, 이메일·휴대폰 인증 표시"
