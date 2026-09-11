# Kotlin Lean MVP 파일럿 실행서

## 전제

- Docker Engine과 Docker Compose v2
- `.env.example`을 복사한 `.env`
- `EASYDOC_AUTH_JWT_SECRET`, `EASYDOC_ENCRYPTION_KEY_V1`, `EASYDOC_ENCRYPTION_KCV_V1`
- 실제 변환을 확인할 때만 선택한 provider API key. 유료 호출 없이 상태만 보려면 `EASYDOC_LLM_PROVIDER=fake`(Compose가 `local` 프로필을 켠다)
- 변환 완료 메일 알림은 `EASYDOC_MAIL_PROVIDER=fake`가 기본값이라 별도 설정 없이도 뜬다(메모리 기록만, 실제 발송 없음). 실제 발송이 필요하면 `EASYDOC_MAIL_PROVIDER=smtp`(임시, Daum 등 소비자 메일 계정 — SES가 의도한 운영 provider이고 smtp는 그 전환 전까지의 임시 조치, 2026-09-04 사용자 결정)로 `EASYDOC_MAIL_SMTP_HOST`·`EASYDOC_MAIL_SMTP_PORT`·`EASYDOC_MAIL_SMTP_SSL`·`EASYDOC_MAIL_SMTP_USERNAME`·`EASYDOC_MAIL_SMTP_PASSWORD`(전부 비밀값, `.env`에만)를 채운다 — 넷 중 host·username·password·from-address 하나라도 비면 기동이 즉시 실패한다. `EASYDOC_MAIL_FROM_ADDRESS`·`EASYDOC_MAIL_TIMEOUT_MS`·`EASYDOC_APP_PUBLIC_BASE_URL`(알림 링크 기준 URL)은 `.env.example` 참고

worker는 lease를 집어 LLM 호출 → 결과 저장까지 실행한다(개인정보 마스킹은 2026-09-07에 제거됐다 — master-plan 3.2 「제품 전제」). 내보내기는
`GET /conversions/{conversion_id}/export?format=docx|txt|hwpx`다. `pdf`는 계약상 422다.

## 전체 스택

```bash
cp .env.example .env
docker compose -f compose.yml config
docker compose -f compose.yml up -d --build --wait
docker compose -f compose.yml ps
```

- 화면: `http://127.0.0.1:8080`
- API: `http://127.0.0.1:8100`
- health: `http://127.0.0.1:8100/health`

서비스 흐름은 다음과 같다.

```text
browser -> frontend/nginx -> backend-api -> PostgreSQL
                                |
                                +-> lease queue -> backend-worker
```

`backend-api`가 기동하면서 Flyway로 스키마를 적용한다. `backend-worker`는 API가 healthy 한 뒤 시작한다.

## 확인 순서

1. `/health`가 200인지 확인한다.
2. 가입·로그인·작업 공간 생성이 되는지 확인한다.
3. 텍스트와 지원 파일(DOCX/PDF/HWPX)의 등록 응답이 202인지 확인한다.
4. 변환 상태가 `pending`을 지나 `done` 또는 `failed`로 끝나는지 확인한다. fake provider면 본문은 고정 문장이다.
5. 로그에 문서 본문, 개인정보, API key가 없는지 확인한다.

## 로그와 종료

```bash
docker compose -f compose.yml logs -f backend-api backend-worker
docker compose -f compose.yml down
```

`down -v`는 로컬 DB 볼륨을 삭제하므로 데이터 폐기가 명시적으로 필요할 때만 사용한다.

## CI와 같은 독립 프로젝트 검증

```bash
docker compose -f compose.yml -f compose.ci.yml run --rm backend-check
docker compose -f compose.yml -f compose.ci.yml run --rm frontend-check
```

실제 LLM 호출, 외부 배포, 기관 데이터 사용은 별도 승인과 비밀값 관리 없이 실행하지 않는다.

## 키 회전 실행

낡은 세대 키로 봉인된 행(`documents`·`document_originals`·`conversions`·`conversion_feedback`)을
현재 쓰기 세대로 재봉인한다. `docs/kotlin-redevelopment-backlog.md` §1.1 「키 회전에 운영
진입점이 없음」이 이 절차로 닫혔다 — `EnvelopeRotation`(행 단위 회전 로직)을
`KeyRotationBatch`(가족 넷을 배치로 순회)가 부르고, `rotate-keys` profile 이 그 배치를
컨텍스트 기동 시 한 번 돌리고 종료한다. `migrate`와 같은 CLI one-off 다 — Compose 상시
서비스가 아니다.

**절차:**

1. `.env`(또는 배포 환경변수)에 새 키 세대를 **더한다** — 기존 `EASYDOC_ENCRYPTION_KEY_V1`은
   그대로 두고 `EASYDOC_ENCRYPTION_KEY_V2`·`EASYDOC_ENCRYPTION_KCV_V2`를 추가한 뒤,
   `EASYDOC_ENCRYPTION_WRITE_KEY_VERSION`을 `2`로 올린다. `application.yml`의
   `easydoc.encryption.keys` 목록도 새 세대를 더하도록 배포 설정을 맞춘다(옛 세대를 지우면
   그 세대로 쓴 행이 열리지 않는다 — 회전이 끝난 뒤에만 지운다).
2. 먼저 **API·워커를 새 write-key-version으로 재배포**한다 — 이 시점부터 신규 쓰기는 새
   세대로 저장되고, 옛 세대 행은 읽기만 가능한 채로 남는다.
3. 회전 배치를 돌린다:
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=rotate-keys
   ```
   (Compose에 `rotate-keys` 전용 one-shot 서비스는 없다 — `migrate`도 마찬가지로 없고, 위
   명령처럼 `backend-api` 이미지를 프로필만 바꿔 일회성으로 돌린다.)
4. 로그에서 가족별 `rotated`/`skipped`/`remaining` 집계만 확인한다(행 id·본문은 찍히지
   않는다). 종료 코드 0이면 그 실행에서 회전할 후보를 전부 처리했다는 뜻이다 — 배치가
   예외로 실패하면 0이 아닌 코드로 끝난다.
5. **멱등이다** — 다시 돌려도 안전하다. 회전할 것이 없으면 즉시 종료 코드 0으로 끝난다.
   동시 쓰기와 겹쳐 이번 실행이 놓친 행(`remaining`)은 세대가 그대로 남으므로 **다음
   실행이 처음부터 다시 훑으며 자동으로 다시 고른다** — 별도 재시도 절차가 필요 없다.
6. 전량이 회전됐음을 확인한 뒤(모든 가족 `rotated`+`skipped`가 전체 행 수와 같고
   `remaining`이 0인 실행이 한 번 나온 뒤)에만 옛 세대를 `.env`·`application.yml`에서
   지운다.

배치 크기는 `easydoc.encryption.rotation.batch-size`(환경변수
`EASYDOC_ENCRYPTION_ROTATION_BATCH_SIZE`, 기본 200)로 조정한다 — `document_originals`는
파일이 최대 10MB라 배치를 낮춰 재실행할 수 있다.

`rotate-keys`도 `migrate`와 달리 본문 암호화 키 전체 세대를 요구한다 —
`write-key-version`이 키 링에 없으면 기동 자체가 거부된다(기존 기동 자기점검을 그대로
탄다, 이 프로필만의 우회는 없다).

## 월간 청구

`llm_calls`(V14) 원장을 사용자·워크스페이스별로 집계해 CSV로 낸다. 결제 PG가 MVP
범위 밖이라(master-plan §4.0) 청구는 사람이 수기로 진행한다 — `usage-report` profile이
그 절차의 첫 단계(집계)만 자동화한다. `migrate`·`rotate-keys`와 같은 CLI one-off다.
계획 `docs/plans/2026-09-07-usage-ledger-and-report.md` §3 U3.

**절차:**

1. **월초에 리포트를 뽑는다.** `docker compose run --rm`은 실행이 끝나면 그 컨테이너를
   지우므로, 컨테이너 안에 CSV를 썼다가 나중에 `docker compose cp`로 꺼내려 하면 이미
   지워진 컨테이너를 가리켜 아무 파일도 호스트에 남지 않는다. **호스트 디렉터리를
   같은 실행에 바인드 마운트**해 컨테이너가 그 자리에 바로 쓰게 한다. 인자를 생략하면
   지난달 1일~마지막날이 기본이다:
   ```bash
   docker compose -f compose.yml run --rm -v "$PWD:/out" backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=usage-report \
     --out=/out/usage-report.csv --actor-email=<이 CLI를 실행하는 운영자 이메일>
   ```
   **`--actor-email`(필수)** — 접속기록(`personal_data_access_logs`, V22) 의무 때문이다
   (개인정보의 안전성 확보조치 기준, 접속기록 1년 이상 보관·월 1회 이상 점검). 이 CLI를
   실행하는 사람(운영자) 자신의 로그인 이메일을 준다 — `admin-grant --email`과 같은
   형태이지 대상이 아니다. 등록된 계정이 아니면 exit 1이고 아무것도 쓰지 않는다.
   **`--out`을 반드시 마운트 경로(`/out/...`) 아래로 지정한다** — 생략하면 기본값
   `./usage-report.csv`가 컨테이너 안 작업 디렉터리(`/app`)에 떨어지고, 그 컨테이너는
   `--rm`으로 곧 지워지므로 파일이 호스트에 남지 않는다. 특정 기간을 지정하려면
   `--from=YYYY-MM-DD --to=YYYY-MM-DD`를 더한다(포함 상한, 366일 초과는 거절). CSV는
   UTF-8 **BOM 포함**으로 쓴다 — 엑셀이 BOM 없는 UTF-8을 열면 한글 워크스페이스 이름이
   깨져 보인다(저장소에 CSV 내보내기 선례가 없어 이번에 정했다). 행은 사용자 ×
   워크스페이스 단위이고, 워크스페이스가 나중에 삭제된 행은 `workspace_id`가 비고
   `workspace_name`이 `(삭제된 워크스페이스)`로 남는다 — 삭제 이후에도 그 달의 문서
   수·문자 수·크레딧·비용이 청구 대상에서 빠지지 않는다. **`owner_email`이
   `(탈퇴한 계정 합계)`인 행은 개별 계정이 아니다** — 그 기간에 탈퇴한 계정 전체의
   사용량을 하나로 합친 값이다(회원 탈퇴, `docs/plans/2026-09-09-account-deletion.md`
   V19). 탈퇴한 계정은 귀속을 되돌릴 방법이 없어 누구 것인지 가릴 수 없다 — **이 줄로
   누군가에게 청구서를 보내지 않는다.**
2. **CSV를 보고 사용자별 청구서를 만든다.** 열은
   `workspace_id,workspace_name,owner_email,documents,characters,credits,llm_calls,failed_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls`
   12열이다(`failed_calls`는 계약 2.26.0 신설, `llm_calls` 바로 뒤). **그 기간에 호출이
   한 번도 없던 워크스페이스는 행 자체가 없다** — `/usage` 화면이 그런 워크스페이스도
   0으로 채워 보여 주는 것과 다르다(이 리포트는 `llm_calls`에 실제로 행이 있는
   사용자·워크스페이스 조합만 훑는다). 청구서를 사람 손으로 만들 때 "리포트에
   없다"를 "그 기간 사용량 0"으로 읽으면 된다 — 다만 워크스페이스 자체의 존재를 이
   CSV로 확인할 수는 없다. `estimated_cost_usd`는 참고용 원가 추정치이지 고객에게
   청구할 금액이 아니다 — 실제 청구는 크레딧(1,000자 = 1크레딧, master-plan §3.3
   요금제 한도)과 별도 계약 단가를 기준으로 사람이 산정한다. `cost_unknown_calls`가
   0이 아니면 그 기간 일부 호출의 원가가 단가 미설정으로 잡히지 않았다는 뜻이다 —
   청구서 발송 전에 원인(단가 설정 누락)을 확인한다. **`failed_calls`가 0이 아니면
   그 기간에 완성 자체가 나지 않은 호출이 있었다는 뜻이다(토큰·비용에는 들어가지
   않는다)** — 벤더가 그 실패 요청의 입력 토큰에 과금했을 수 있어, 벤더 청구서와
   대조할 때 이 건수를 함께 본다. 완성 자체가 나지 않은 호출만 있고 그 기간에 문서가
   하나도 완료되지 않은 워크스페이스는 `documents 0, llm_calls 0, failed_calls > 0`
   인 행으로 나타날 수 있다 — "0으로 채워진 이상한 행"이 아니라 "시도는 있었지만
   완료된 변환이 없다"는 뜻이니 청구서에는 사용량 0으로 반영한다.
3. **계좌이체 확인.** PG가 없으므로 결제는 계좌이체다 — 입금 내역을 수기로 대조한다.
4. **세금계산서 요청이 오면 운영자 메일을 확인한다.** 사용자가 `/usage` 화면의
   「세금계산서 요청」 버튼으로 사업자등록번호·상호·기간을 제출하면(계획
   `docs/plans/2026-09-07-invoice-requests.md` §2, 계약 2.24.0) `easydoc.billing
   .operator-email`(환경변수 `EASYDOC_BILLING_OPERATOR_EMAIL`, `.env.example` 참고)로
   알림 메일이 온다(요청 id·워크스페이스 id·사업자번호·상호·기간·연락 이메일을 담는다 —
   비어 있으면 알림이 가지 않고 서버 로그에 경고 한 줄만 남으므로 파일럿 착수 전에
   반드시 설정한다).
5. **홈택스에서 수동 발급한다.** 국세청 홈택스 등 별도 채널에서 메일에 적힌
   사업자등록번호로 전자세금계산서를 발급한다(제품이 직접 발급하지 않는다 — 홈택스
   API 연동은 범위 밖).
6. **`invoice-handle` 프로필로 상태를 반영한다.** `credit-grant`·`usage-report`와 같은
   일회성 운영 프로필이다(Compose 상시 서비스가 아니다). 발급을 마쳤으면:
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=invoice-handle \
     --id=<요청 id> --status=issued
   ```
   발급할 수 없으면 사유를 `--note`(500자 이내)에 남기고 거절한다:
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=invoice-handle \
     --id=<요청 id> --status=rejected --note="사업자등록번호 확인 불가"
   ```
   표준출력에 요청 id·반영된 상태만 찍힌다. 종료 코드 0이면 반영된 것이고, 1이면
   인자 오류·존재하지 않는 id·이미 처리된 요청 중 하나다(메시지 한 줄만 남는다 —
   로그에서 확인한다). 처리 직후 요청자에게 상태 안내 메일(발급 또는 거절+사유)이
   최선 노력으로 나간다. 처리 결과는 요청자·운영자 모두 `/usage` 화면의 요청 목록에서
   확인할 수 있다.

리포트 CSV에는 소유자 이메일·워크스페이스 이름이 실리므로 발송 전까지만 보관하고,
공유 스토리지에 영구 보관하지 않는다(사용자 문서 본문·개인정보를 로그에 남기지
않는다는 원칙과 같은 이유 — 이 CSV는 로그가 아니라 운영 산출물이지만 마찬가지로
최소 보관한다).

## 크레딧 충전

워크스페이스마다 크레딧 잔액을 두고, 문서 등록 때 필요한 크레딧을 예약하고 변환이
끝나면 소비하며, 잔액이 모자라면(집행이 켜졌을 때만) 등록을 거절한다(계획
`docs/plans/2026-09-07-credit-accounts.md`). 충전은 PG가 없으므로 계좌이체 확인 뒤
운영자가 `credit-grant` profile로 수동 부여한다 — `usage-report`·`rotate-keys`와 같은
CLI one-off다(Compose 상시 서비스가 아니다).

**정책(2026-09-08 결정, 계획 §7):** 부여한 크레딧은 **만료·이월 없이** 소진할 때까지
남는다. 월 구독 갱신은 **월초에 운영자가 `credit-grant --reason=plan_monthly`를 수동으로**
돌리는 것이고(자동 부여 없음), 가입 시 기본 워크스페이스에는 `EASYDOC_CREDITS_SIGNUP_GRANT=50`
(Starter 한 달치)을 1회 부여한다. 집행(`EASYDOC_CREDITS_ENFORCED=true`)은 아래 4번대로
**부여 직후** 켠다 — 파일럿 내내 꺼 두지 않는다.

**절차:**

1. **계좌이체 확인.** 입금 내역을 수기로 대조한다(위 「월간 청구」 3번과 같다).
2. **`credit-grant`로 부여한다.** `usage-report` 호출과 같은 모양이다 — 바인드 마운트는
   필요 없다(파일을 쓰지 않는다). `--workspace`는 워크스페이스 uuid,
   `--credits`는 정수(월 구독 갱신·수동 충전은 양수, 환급 취소 같은 조정은 음수),
   `--reason`은 `plan_monthly`(월 구독 갱신)·`manual`(수동 부여)·`refund`(환급) 중 하나,
   `--note`는 선택(200자 이내, 운영자 메모 — 워크스페이스 이름·이메일을 적지 않는다.
   표준출력·`GET .../credits` 응답 어디에도 이름·이메일은 실리지 않는다):
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=credit-grant \
     --workspace=00000000-0000-4000-8000-000000000001 --credits=50 \
     --reason=plan_monthly --note="2026년 9월 정기 충전" \
     --actor-email=<이 CLI를 실행하는 운영자 이메일>
   ```
   **`--actor-email`(필수)** — `usage-report`와 같은 이유(접속기록 의무). 이 CLI를 실행하는
   운영자 자신의 이메일이다.
   표준출력에 `workspace_id`·적용 델타·반영 뒤 `balance`/`reserved`/`available`이 한 줄로
   찍힌다. 종료 코드 0이면 반영된 것이고, 1이면 인자 오류나 존재하지 않는 워크스페이스다
   (스택트레이스 없이 메시지 한 줄만 남는다 — 로그에서 확인한다).
   **`usage-report`와 같은 이유로 이 프로필도 아무것도 면제하지 않는다** — 본문 암호화
   키 전체 세대·LLM provider 조립을 그대로 요구하므로, `docker compose run` 은 위처럼
   `backend-api` 서비스(그 환경변수 전부)로 돌려야 기동 자기점검을 통과한다.
3. **확인한다.** 인증된 그 워크스페이스 소유자로 `GET /workspaces/{workspace_id}/credits`를
   불러 잔액·거래 1건이 반영됐는지 본다(화면이면 `/usage` 크레딧 카드).
4. **집행을 켠다(파일럿 워크스페이스 부여가 끝난 직후 — 2026-09-08 결정).** 기본값 `easydoc.credits.enforced=false`는
   꺼져 있어도 예약·소비·거래를 그대로 기록한다(잔액이 음수로 남을 수 있다) — 켜는
   순간부터 가용 크레딧이 모자란 등록이 402로 거절된다. `EASYDOC_CREDITS_ENFORCED=true`를
   `.env`(또는 배포 환경변수)에 넣고 `backend-api`를 재기동한다.

   **⚠ 순서를 지킨다 — 부여 → 확인 → 켜기.** 배포 직후 모든 워크스페이스 잔액이 0이라
   먼저 켜면 그 순간부터 모든 등록이 402가 된다(계획 §6 리스크 1). 파일럿에 참여하는
   워크스페이스 전부에 위 1~3단계로 잔액을 부여해 둔 뒤에만 켠다.

## 가입 크레딧은 이메일당 한 번

가입 크레딧(`EASYDOC_CREDITS_SIGNUP_GRANT`)은 이메일당 **정확히 한 번**만 나간다(계획
`docs/plans/2026-09-09-account-deletion.md` §6~§9 후속). 부여한 이메일의 단방향 해시
(`HMAC-SHA256(pepper, 정규화된 이메일)`)를 `signup_grant_records`(V20)에 남겨, 탈퇴한
이메일로 재가입해도 다시 부여하지 않는다 — 대신 그 사실을 이메일 인증을 마친 사용자에게
크레딧 화면(`GET /workspaces/{workspace_id}/credits`의 `signup_grant_skipped`, 계약
2.29.0)에서 고지한다.

- **`EASYDOC_CREDITS_SIGNUP_GRANT_PEPPER`가 필수다.** `EASYDOC_CREDITS_SIGNUP_GRANT`가
  0보다 크면 이 값 없이는 기동이 실패한다(`easydoc.encryption` 키 자기점검과 같은 자리 —
  조용히 부여해 버리는 fail-open을 만들지 않는다). `EASYDOC_CREDITS_SIGNUP_GRANT=0`이면
  이 값 없이도 뜬다.
- **회전하지 않는 값이다.** pepper를 바꾸면 `signup_grant_records`의 기존 행이 새
  해시와 매칭되지 않아 그 이메일이 이미 가입 부여를 받았어도 다시 부여받는다 — 배포
  뒤에는 절대 바꾸지 않는다. 유출됐다고 판단되면 회전이 아니라 별도 사고 대응 절차로
  다룬다(이 계획의 범위 밖).
- 평문 이메일은 이 표에 담기지 않는다. pepper 없이는 해시로부터 이메일을 역산할 수 없다.

## 관리자 부여

관리자는 `users.is_admin` DB 플래그다(V17, 계획 `docs/plans/2026-09-07-admin-minimum.md` §2
결정 1). 설정 파일·환경변수로 관리자를 정하지 않고, 화면에도 부여·회수 기능이 없다 —
운영자가 `admin-grant` 일회성 프로필로만 바꾼다(`credit-grant`·`usage-report`와 같은
Compose one-off, 상시 서비스가 아니다).

**사전 조건: 대상 계정의 이메일이 검증된 상태여야 한다**(`email_verified=true`). 미검증
이메일로 부여를 시도하면 exit 1이고 아무것도 바뀌지 않는다 — 이메일 미검증 계정을 도용해
관리자 권한을 얻는 경로를 막기 위해서다(계획 §5 리스크 2). 검증 여부는 대상 계정으로
`GET /auth/me`의 `email_verified`를 보거나, 본인이 이메일 인증 화면에서 인증을 마쳤는지
확인한다.

**절차:**

1. **부여한다.** `--email`은 **대상** 계정의 로그인 이메일이고, `--actor-email`은 이 CLI를
   실행하는 **운영자 자신**의 이메일이다(접속기록 의무, `usage-report`와 같은 이유 — 둘을
   혼동하지 않는다):
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=admin-grant \
     --email=operator@example.test --actor-email=<이 CLI를 실행하는 운영자 이메일>
   ```
   표준출력에 `user_id`·반영된 `is_admin` 값만 찍힌다(`관리자 권한 반영 — user_id=… is_admin=true`)
   — 이메일은 담지 않는다(`credit-grant`의 "이름·이메일은 내지 않는다"와 같은 규약). 종료
   코드 0이면 반영된 것이고, 1이면 알 수 없는 이메일·이메일 미검증·인자 오류 중 하나다
   (메시지 한 줄만 남는다 — 로그에서 확인한다).
2. **회수한다.** `--revoke` 플래그만 더한다(값을 받지 않는다):
   ```bash
   docker compose -f compose.yml run --rm backend-api \
     java -jar /app/easy-doc-api.jar --spring.profiles.active=admin-grant \
     --email=operator@example.test --revoke --actor-email=<이 CLI를 실행하는 운영자 이메일>
   ```
   **회수는 다음 요청부터 즉시 반영된다** — 관리자 판정은 토큰에 넣지 않고 매 요청 DB에서
   다시 읽는다(`AdminGuard`, 계획 §2 결정 2). 이미 발급된 액세스 토큰을 들고 있어도 그
   사용자의 다음 관리자 API 호출은 403 「관리자 권한이 필요합니다」다.
3. **확인한다.** 대상 계정으로 로그인해 `GET /auth/me`의 `is_admin`을 보거나(부여 뒤 계정
   메뉴에 「관리」 링크가 뜬다), 화면에서 `/admin`에 들어가 본다.

## 접속기록 점검

개인정보의 안전성 확보조치 기준이 요구하는 **접속기록 월 1회 이상 점검**을 위한
CLI다(계획 `docs/plans/2026-09-11-access-log-retention.md` §3.5). 화면은 없다 — 취급자가
운영자 한 명뿐이고 점검은 월 1회이므로 CLI 보고서로 충분하다.

관리자 API(`x-admin-only` 10개 오퍼레이션)와 CLI 프로필 셋(`usage-report`·`credit-grant`·
`admin-grant`)의 접속·실행이 `personal_data_access_logs`(V22)에 자동으로 쌓인다 — 이
점검 자체는 그 표를 읽기만 한다.

**매달 한 번:**

```bash
docker compose -f compose.yml run --rm -v "$PWD:/out" backend-api \
  java -jar /app/easy-doc-api.jar --spring.profiles.active=access-log-report \
  --out=/out/access-log-report.csv
```

인자를 생략하면 지난달 전체다(`--from=YYYY-MM-DD --to=YYYY-MM-DD`로 지정 가능,
366일 초과는 거절). 표준출력에 기간·총 건수·거절 건수·취급자별 횟수·업무별 횟수가
찍히고, `--out`(BOM 포함 UTF-8 CSV)에는 원시 목록(건마다 취급자·접속 일시·접속지·업무·
조회 범위·성공 또는 거절)이 실린다. **거절(rejected) 건이 있으면 그 기간에 권한 없는
접근 시도가 있었다는 뜻**이니 원시 목록에서 어떤 계정이 어떤 업무를 시도했는지 확인한다.

**점검을 수행했다는 사실 자체는 이 CLI가 기록하지 않는다** — 위 CSV 파일이 그 증거이고,
보관은 운영 절차(예: 발송한 메일함, 사내 문서함)로 한다.

## 어드민 화면 운영

`/admin` 화면(계약 2.25.0)은 관리자로 로그인하면 계정 메뉴 「관리」 링크로 들어간다 — 탭
넷이다: 「워크스페이스」(검색·크레딧 조회·수동 조정), 「세금계산서」(상태별 목록·발급/거절
처리), 「오류」(기간별 실패 코드 집계·최근 목록), 「공지」(작성·활성 토글·본문 고치기).

**화면이 대신하는 CLI 작업:**

- 위 「크레딧 충전」 1~3단계(`credit-grant` 프로필) — 「워크스페이스」 탭 상세의 크레딧
  조정 폼이 같은 서비스 경로(`AdminCreditAdjustmentService`)를 쓴다. 관리자가 화면에서
  조정하면 거래에 `actor_user_id`(V17)가 남아 누가 조정했는지 감사할 수 있다 — CLI
  경로(`credit-grant`)는 이 필드가 비어 있다.
- 위 「월간 청구」 6단계(`invoice-handle` 프로필) — 「세금계산서」 탭의 처리 폼이 같은
  유스케이스(`InvoiceRequestService.handle`)를 쓴다. `handled_by`(V17)가 남고, 처리 뒤
  요청자 메일은 CLI 경로와 동일하게 나간다.
- 사용량 조회 — 「워크스페이스」 탭 목록·상세가 그 워크스페이스의 이번 달 사용량을
  보여준다(`UsageQueryService` 재사용). 오류 조회는 「오류」 탭이 기간별로 보여준다.

**CLI로만 남는 것:**

- **`usage-report` CSV 산출** — 월간 청구서를 사람이 만들 때 쓰는 원본 파일이다. 화면의
  `GET /admin/usage`(`AdminUsageResponse`)는 같은 행을 JSON으로 화면 조회용으로만 내고,
  BOM 포함 CSV 파일 자체는 여전히 `usage-report` 프로필이 만든다 — 위 「월간 청구」 1~2단계
  그대로다.
- **`admin-grant`(관리자 부여·회수)** — 위 「관리자 부여」절 그대로다. `/admin` 화면 어디에도
  다른 계정을 관리자로 만들거나 회수하는 조작이 없다(계획 §2 결정 1 "이 API로는 바꿀 수
  없다") — 관리자 후보 계정을 늘리는 조작은 운영자가 서버에 직접 접근할 수 있을 때만
  일어나야 한다는 판단이다.

닫기(공지 배너)·워크스페이스 검색 같은 화면 전용 조작은 계약에 없다 — 서버 상태를
바꾸지 않는 순수 화면 기능이기 때문이다.

---

## 게이트 ① 판정

`docs/master-plan.md` §9의 **게이트 ①**(파일럿 실무자 검증 → 단계 2 진행)을 판정하는 절차다.
성공 기준의 정본은 §4.0의 두 문장이다 — 파일럿 실무자가 실제 문서 10건을 처리했을 때
① 외주 대비 시간이 유의미하게 단축되고 ② "이 결과물을 다듬어 실제로 배포하겠다"고 답하는 것.

위 「확인 순서」는 스택이 도는지 보는 스모크 테스트이고, 이 절은 **제품이 통과했는지**를 본다.
둘은 다른 판정이다.

### 대상과 규모

- 파일럿 기관 1~2곳(§7 KPI의 「파일럿 기관 1~2곳 확보」와 같은 규모).
- 실무자가 **실제 업무 문서 10건**을 처리한다.
- **10건은 기관 합산이다.** 한 사람이 10건을 채워야 하는 것이 아니고, 기관이 둘이면
  합쳐서 10건이면 된다. 다만 한 사람이 10건을 전부 채우면 표본이 한 사람의 취향이 되므로,
  가능하면 실무자 2인 이상으로 나눈다.

### 사전 조건

- **실제 LLM provider 키와 유료 호출 승인.** `EASYDOC_LLM_PROVIDER=fake`는 고정 문장을
  돌려주므로 품질 판단이 성립하지 않는다 — fake로 채운 10건은 이 게이트의 표본이 아니다.
  비용과 범위는 사용자가 승인한 뒤에 켠다(프로젝트 `CLAUDE.md` 모델·비용 정책).
- **개인정보가 든 문서는 지양하도록 안내한다.** 이 제품은 공공 배포 문서를 전제하며
  개인정보 마스킹을 하지 않으므로(master-plan §3.2 「제품 전제」, 2026-09-07), 올린 본문은
  그대로 provider로 나간다. §8 리스크 표의 「개인정보가 든 문서가 올라와 그대로 전송」이 이
  자리다.
- 기관의 **기존 방식 소요**를 착수 전에 인터뷰로 받아 아래 「기존 방식 소요」 칸에 적는다.
  이 값이 없으면 기준 ③을 판정할 대조군이 없다(기준 ①은 배포 의향 건수라 대조군이 필요 없다).
- **게이트 ⓪(긴 문서 처리)이 먼저 판정돼 있어야 한다** — `docs/master-plan.md` §9.
  2026-08-27 실측에서 `docs/golden`의 실제 공개 문서 중 가장 작은 hwpx 3건·pdf 1건이
  **모두 4,000자 상한에서 422**였다. 실무 문서가 상한에 걸리는 채로 파일럿을 돌리면
  실무자가 문서를 손으로 잘라 넣게 되고, 그 시간은 제품이 만든 시간이 아니라서
  **기준 ③(시간 단축)의 측정이 성립하지 않는다.** 상한 상향으로 끝날지 분할 변환이
  필요할지는 그 게이트의 측정이 정한다.

### 기록 방법

수기 입력은 **검수 화면 하단의 피드백 폼 제출 한 번**이 전부다
(배포 의향 · 품질 만족도 1~5 · 이번 건 소요 시간(분) · 자유 의견(선택)).
나머지는 시스템이 남긴다 — 처리 건수·상태·토큰은 `conversions`가, 소요 지표와 수정률은
피드백 저장 시점에 계산돼 `conversion_feedback`에 평문 숫자로 들어간다.

피드백 표는 **문서 30일 보존 파기와 분리돼 있다**(FK 없음). 문서가 파기돼도 판정 근거는 남는다.
자유 의견만 AEAD로 봉인되므로 집계 스크립트는 읽지 않는다 — 열람이 필요하면
소유자 토큰으로 화면에서 본다.

### 통과 기준 — **확정(2026-09-04)**

아래 숫자는 2026-09-04 사용자가 제안값 그대로 확정했다. 이후 숫자를 바꿀 때는 이 절에 날짜를 남긴 변경 기록을 추가한다.

| # | 기준 | 확정값 | 판정 |
|---|---|---|---|
| 1 | 배포 의향이 `as_is`(그대로 쓸 수 있다) 또는 `with_edits`(조금 고쳐서 쓰겠다)인 건수 | 10건 중 **8건 이상** | 스크립트 |
| 2 | 품질 만족도 평균 | **3.5 이상** | 스크립트 |
| 3 | 문서 1건 소요 시간의 중앙값이 기존 방식 소요 대비 유의미하게 짧을 것 | — | **사람** |

- 기준 3은 자동 판정하지 않는다. 기존 방식 소요는 기관마다 다르고(외주 발주 리드타임이
  주 단위인 곳과 내부 인력이 하루에 끝내는 곳이 같은 임계값을 쓸 수 없다) 그 값은
  인터뷰로만 들어온다. 스크립트는 **중앙값을 내고, 판단은 사람이 한다.**
- **표본 10건은 통계적으로 작다.** §7의 경고("경계값에서의 재실행·표본 확대 판단은 사람이
  하며, 자동 재시도로 가리지 않는다")를 그대로 적용한다. 8/10과 7/10의 차이는 한 사람의
  그날 기분만큼도 안정적이지 않다 — 경계값이면 표본을 늘리거나 다시 돌린다.

**기존 방식 소요(인터뷰 기록)**

| 기관 | 문서 종류 | 기존 방식 | 1건 소요 | 출처·일자 |
|---|---|---|---|---|
| _(파일럿 착수 시 채운다)_ | | | | |

### 집계

문서 보존 만료(기본 30일) **전에** 실행한다. 지표 표는 파기 대상이 아니지만, 판정 중
원문·변환 결과를 대조해야 할 때 그쪽은 이미 사라져 있다.

수정률(`edit_distance`) 표본은 **두 가지 이유로** 전체 표본보다 작을 수 있다(2026-09-04,
`edit_distance_skip_reason` 컬럼 추가 — `V4__conversion_feedback_edit_distance_skip_reason.sql`).
검수 수정본 자체가 없으면(`no_review`) 애초에 잴 것이 없고, 수정본은 있지만 셀 예산
(`easydoc.feedback.edit-distance-cell-budget`)을 넘으면 계산을 포기한다(`budget_exceeded`,
요청 스레드 CPU 상한, `core/text/EditDistance.kt`). 두 경우 모두 `edit_distance`가 `NULL`로
남지만 원인이 다르다 — `budget_exceeded`는 검수자가 실제로 문서를 수정했는데도 표본에서
빠지는 경우라 예산을 올릴지 판단할 근거가 되고, `no_review`는 검수자가 그대로 쓸 만하다고
판단해 수정본 자체를 내지 않은 경우다. `scripts/pilot-report.sql`의 「③-1 수정률 표본
구성」이 이 둘과 「측정됨」을 나눠 낸다 — 수정률 평균·중앙값을 읽을 때는 그 표를 **함께
읽는다.**

```bash
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -f - < scripts/pilot-report.sql
```

### 파일럿 종료 정리 — `conversion_feedback`

**순서는 집계 → 판정 기록 → 이 정리다.** 아래 「판정 기록」을 남긴 **직후**에 실행한다.
집계 전이나 판정 기록 전에 실행하면 판정 근거를 스스로 지우는 것이 된다.

판정이 끝나면 `conversion_feedback`을 정리한다. 이 표는 **문서 30일 파기의 사슬 밖**이다 —
파기는 `documents` → `conversions` → `conversion_jobs`로만 이어지고 피드백 표에는 FK가 없다
(판정 근거를 남기려고 의도한 설계다). 그 결과 이 표에는 TTL도 purge도 없어서
**아무도 지우지 않으면 영구히 남는다.**

**→ 갱신(2026-09-04):** 아래 ⒜ 정책은 이제 worker의 보존 파기 배치(`RetentionPurgeScheduler`
→ `PurgeFeedbackComments` → `JdbcFeedbackCommentPurge`)가 매일 자동으로 수행한다 —
`easydoc.feedback.comment-retention-days`(기본 30일, env `EASYDOC_FEEDBACK_COMMENT_RETENTION_DAYS`)
보다 오래된 의견의 세 열만 `NULL`로 만들고 척도 숫자는 그대로 둔다. 나이 판정은
`submitted_at`(마지막 재제출 시각)이다 — 키 회전은 `updated_at`만 밀고 `submitted_at`은
밀지 않으므로 회전이 삭제 시계를 늦추지 않는다(`FeedbackProperties` KDoc). 이 절차가
여전히 남는 이유는 둘이다: ⑴ 파일럿 종료 직후 배치 주기(기본 매일 03:00)를 기다리지 않고
**즉시** 비우고 싶을 때, ⑵ ⒝(표를 통째로 지우는 선택)는 자동화 대상이 아니다 — 척도
숫자까지 지우는 것은 사람이 판정 기록을 문서로 남긴 뒤 스스로 판단할 일이라 배치가
대신하지 않는다.

자유 의견 칸이 문제다. AEAD로 봉해 두었지만 **봉인은 기밀성이지 삭제가 아니다** — 키는
운영 마스터 키라 계속 열린다. 그리고 그 칸에는 검수자가 문서 본문 조각을 그대로 붙여 넣는
일이 실제로 일어나고(`V2__conversion_feedback.sql`의 주석 — "○○동 ○○○님께 안내드립니다
부분이 어색합니다"), 개인정보 마스킹이 없으므로(master-plan §3.2 「제품 전제」)
그 조각의 **이름·주소·전화번호는 어디서도 가려지지 않는다.**

**선택지는 둘이다.**

**⒜ 자유 의견만 비운다 (기본 권고, 2026-09-04부터 매일 배치로도 자동 실행)**

`comment_encrypted`·`encryption_scheme`·`key_version` 세 열을 `NULL`로 만든다.
개인정보가 들어오는 칸이 사라지고 **척도 숫자(배포 의향·품질 만족도·소요 시간)와
수정률 지표는 남는다.** 나중에 판정을 되짚거나 영업·계약 자료로 쓸 수 있다 —
없앨 이유가 있는 것은 본문 조각이지 본문에 대한 척도가 아니다. 그래서 이쪽이 기본이다.

아래 수기 명령은 이제 **즉시 실행하고 싶을 때만** 쓴다 — 30일이 지난 의견은 배치가
어차피 다음 03:00에 비운다. 파일럿 종료 직후처럼 배치를 기다리지 않고 지금 비우고
싶을 때, 또는 `easydoc.feedback.comment-retention-days`를 짧게 바꿔 놓지 않은 채
당장 정리해야 할 때 그대로 쓴다.

```bash
# 세 열을 반드시 함께 NULL 로 만든다. 스키마에 「셋이 함께 있거나 함께 없다」 CHECK 가
# 걸려 있어(ck_conversion_feedback_comment_scheme_paired,
# ck_conversion_feedback_comment_key_version_paired) 하나만 비우면 거절된다.
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -c \
  "UPDATE conversion_feedback SET comment_encrypted = NULL, encryption_scheme = NULL, key_version = NULL;"
```

**⒝ 표를 통째로 지운다**

판정 기록 문서(`docs/plans/`)에 집계 출력과 결론을 이미 남긴 뒤라면 이쪽이 깔끔하다.
표가 사라지므로 되짚을 근거는 그 문서에만 남는다.

```bash
docker compose -f compose.yml exec -T postgres \
  psql -U postgres -d easydoc -c "DELETE FROM conversion_feedback;"
```

**이것이 수기 절차인 이유**: 제품에 삭제 요청을 처리하는 경로가 아직 없다 — 계정 삭제
기능 자체가 없다(계약 `contracts/easy-doc-v1.yaml`의 오퍼레이션에 없다). master-plan §3.2가
약속한 "삭제 요청 시 즉시 파기"를 이 표에 대해서는 사람이 대신 실행하는 것이다.
자동화는 그 경로가 제품에 생길 때 함께 선다(`docs/kotlin-redevelopment-backlog.md`
「1.1 추후 개선 항목」).

### 판정 기록

- 판정 근거와 결론을 `docs/plans/`에 판정 기록 문서로 남긴다(집계 출력·인터뷰 값·사람 판단).
- `docs/master-plan.md` §9의 게이트 ① 줄에 결과를 반영한다.
