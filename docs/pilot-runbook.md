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
     --out=/out/usage-report.csv
   ```
   **`--out`을 반드시 마운트 경로(`/out/...`) 아래로 지정한다** — 생략하면 기본값
   `./usage-report.csv`가 컨테이너 안 작업 디렉터리(`/app`)에 떨어지고, 그 컨테이너는
   `--rm`으로 곧 지워지므로 파일이 호스트에 남지 않는다. 특정 기간을 지정하려면
   `--from=YYYY-MM-DD --to=YYYY-MM-DD`를 더한다(포함 상한, 366일 초과는 거절). CSV는
   UTF-8 **BOM 포함**으로 쓴다 — 엑셀이 BOM 없는 UTF-8을 열면 한글 워크스페이스 이름이
   깨져 보인다(저장소에 CSV 내보내기 선례가 없어 이번에 정했다). 행은 사용자 ×
   워크스페이스 단위이고, 워크스페이스가 나중에 삭제된 행은 `workspace_id`가 비고
   `workspace_name`이 `(삭제된 워크스페이스)`로 남는다 — 삭제 이후에도 그 달의 문서
   수·문자 수·크레딧·비용이 청구 대상에서 빠지지 않는다.
2. **CSV를 보고 사용자별 청구서를 만든다.** 열은
   `workspace_id,workspace_name,owner_email,documents,characters,credits,llm_calls,input_tokens,output_tokens,estimated_cost_usd,cost_unknown_calls`다.
   **그 기간에 호출이 한 번도 없던 워크스페이스는 행 자체가 없다** — `/usage` 화면이
   그런 워크스페이스도 0으로 채워 보여 주는 것과 다르다(이 리포트는 `llm_calls`에
   실제로 행이 있는 사용자·워크스페이스 조합만 훑는다). 청구서를 사람 손으로 만들
   때 "리포트에 없다"를 "그 기간 사용량 0"으로 읽으면 된다 — 다만 워크스페이스
   자체의 존재를 이 CSV로 확인할 수는 없다. `estimated_cost_usd`는 참고용 원가
   추정치이지 고객에게 청구할 금액이 아니다 — 실제 청구는 크레딧(1,000자 = 1크레딧,
   master-plan §3.3 요금제 한도)과 별도 계약 단가를 기준으로 사람이 산정한다.
   `cost_unknown_calls`가 0이 아니면 그 기간 일부 호출의 원가가 단가 미설정으로
   잡히지 않았다는 뜻이다 — 청구서 발송 전에 원인(단가 설정 누락)을 확인한다.
3. **계좌이체 확인.** PG가 없으므로 결제는 계좌이체다 — 입금 내역을 수기로 대조한다.
4. **세금계산서를 수동 발급한다.** 국세청 홈택스 등 별도 채널로 발급하고, 문의가 오면
   사업자번호를 안내한다(제품에 사업자번호 저장·조회 기능이 없다 — 운영자가 별도로
   관리한다).

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
     --reason=plan_monthly --note="2026년 9월 정기 충전"
   ```
   표준출력에 `workspace_id`·적용 델타·반영 뒤 `balance`/`reserved`/`available`이 한 줄로
   찍힌다. 종료 코드 0이면 반영된 것이고, 1이면 인자 오류나 존재하지 않는 워크스페이스다
   (스택트레이스 없이 메시지 한 줄만 남는다 — 로그에서 확인한다).
   **`usage-report`와 같은 이유로 이 프로필도 아무것도 면제하지 않는다** — 본문 암호화
   키 전체 세대·LLM provider 조립을 그대로 요구하므로, `docker compose run` 은 위처럼
   `backend-api` 서비스(그 환경변수 전부)로 돌려야 기동 자기점검을 통과한다.
3. **확인한다.** 인증된 그 워크스페이스 소유자로 `GET /workspaces/{workspace_id}/credits`를
   불러 잔액·거래 1건이 반영됐는지 본다(화면이면 `/usage` 크레딧 카드).
4. **집행을 켠다(선택, 파일럿 준비가 끝난 뒤).** 기본값 `easydoc.credits.enforced=false`는
   꺼져 있어도 예약·소비·거래를 그대로 기록한다(잔액이 음수로 남을 수 있다) — 켜는
   순간부터 가용 크레딧이 모자란 등록이 402로 거절된다. `EASYDOC_CREDITS_ENFORCED=true`를
   `.env`(또는 배포 환경변수)에 넣고 `backend-api`를 재기동한다.

   **⚠ 순서를 지킨다 — 부여 → 확인 → 켜기.** 배포 직후 모든 워크스페이스 잔액이 0이라
   먼저 켜면 그 순간부터 모든 등록이 402가 된다(계획 §6 리스크 1). 파일럿에 참여하는
   워크스페이스 전부에 위 1~3단계로 잔액을 부여해 둔 뒤에만 켠다.

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
