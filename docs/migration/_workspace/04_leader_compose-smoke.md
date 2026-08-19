# compose 기동 스모크 (계획 §7.1 P4 · 원장 미실행 목록 N-5) — 2026-08-20, 리더

`documents` C2 의 선결이다. 게이트 26 조치 2(`@Profile("!migrate")`, 판정 ④)가 기동 조건을
바꾼 뒤 **compose 를 한 번도 띄우지 않았다**는 사실이 원장 「§6 미실행」에 적혀 있었고,
이 문서가 그 자리를 닫는다.

착수 HEAD `269fe28` · 실행 시각 2026-08-20 · 실행 주체 리더(위임 아님).

## 1. 무엇을 재려 했는가

게이트 26 판정 ④는 **강제형 장치(기동 fail-fast)의 범위를 근거에 맞춰 좁힌 것**이고, 좁힌
경계가 실제 배포 형상에서 그대로 서는지는 단위 테스트(`CryptoProfileExemptionTest`)가
아니라 **컨테이너 기동**에서만 확인된다. 그래서 네 갈래를 각각 실행해 종료 코드를 봤다.

| 갈래 | 기대 | 근거 |
|---|---|---|
| A-1 `kotlin-migrate`, 키 **없음** | 뜬다(exit 0) · 암호 빈 조립 **안 됨** | 판정 ④ — 스키마만 옮기는 잡은 본문 키를 쥐지 않는다 |
| A-2 `kotlin-api`, 키 **없음** | **안 뜬다**(exit 1) | F-2/X7 — 서비스 경로의 오설정 침묵을 막는다 |
| B-1 `kotlin-api`, 키 있고 **kcv 없음** | **안 뜬다** + 계산된 kcv 를 알려 준다 | F-3/S-2 |
| B-2 `kotlin-api`·`kotlin-worker`, 키·kcv 있음 | 뜬다 · `/health` 200 | 양성 경로 |

## 2. 실행과 결과 (전건 실측)

명령은 **파이프 없이** 돌리고 종료 코드를 직접 읽었다(원장 함정 5).

| # | 명령 | 종료 코드 | 판정 |
|---|---|---|---|
| — | `docker compose --profile kotlin build kotlin-api` | **0** | 이미지 `easy-doc-kotlin:local` 재생성 |
| A-1 | `docker compose --profile kotlin up kotlin-migrate --exit-code-from kotlin-migrate --no-deps` | **0** | **기대대로.** V3·V4 적용(`Successfully applied 2 migrations`) |
| A-2 | `docker compose --profile kotlin run --rm --no-deps kotlin-api` | **1** | **기대대로.** 아래 §2.1 |
| B-1 | 같은 명령(키만 채운 상태) | **1** | **기대대로.** 아래 §2.2 |
| B-2 | `docker compose --profile kotlin up -d kotlin-api kotlin-worker` | **0** | api `healthy` · worker `running` |

### 2.1 A-1 · A-2 가 함께 보인 것 — 면제가 조용하지 않다

- A-1 의 `kotlin-migrate` 로그에서 문자열 `저장 암호화 기동 자기점검 통과` 출현 **0회**.
  「검사를 건너뛰었다」가 아니라 **빈이 조립되지 않았다**는 뜻이고, 판정 ④ 가 고른 형태가
  그것이다. 키 재료가 `SecretKey` 가 되는 자리 자체가 없다.
- A-2 는 두 문제를 **한 번에** 보고하고 멈췄다(하나씩 끊지 않는다는 `CryptoConfiguration`
  설계 그대로): 쓰기 세대 v1 키 미적재 + 「값도 kcv 도 없는 세대 선언」. 후자는 게이트 26
  조치 3(privacy-gate S-2)이 신설한 갈래이고, **이 실행이 그 갈래의 첫 컨테이너 관측이다.**

### 2.2 B-1 — F-3 의 대조 경로가 실제로 돈다

키만 채우고 kcv 를 비운 채 띄우니 기동이 실패하면서 **계산된 검사값 `5739b94ba3dc`** 를
알려 줬다(`.env.example` 이 적어 둔 절차 그대로). 검사값은 비밀이 아니므로 여기 적는다.
그 값을 설정에 넣은 뒤 B-2 가 통과했다 — 즉 **kcv 대조는 통과 경로에서도 실제로 계산되고
비교된다.** 값을 비워 둔 채 초록이 되는 갈래는 없다.

### 2.3 B-2 — 양성 경로

- `kotlin-api` `healthy`, `kotlin-worker` `running`(`Started WorkerApplicationKt in 3.713 seconds`).
- 호스트에서 `GET http://127.0.0.1:8100/health` → **200** `{"status":"ok"}`,
  헤더에 `Cache-Control: no-store` · `X-Content-Type-Options: nosniff` 실재.
- **api 와 worker 둘 다** `저장 암호화 기동 자기점검 통과. 검증한 세대 1개.` 를 남겼다 —
  면제 대상이 `migrate` 하나뿐임이 기동 로그로도 확인된다.
- Flyway 이력 최종: `1 python schema baseline` · `2 encryption scheme` ·
  `3 encryption scheme aead` · `4 key version domain` — 전건 `success = true`.
- **키 재료 유출 0건**: 세 컨테이너 로그에서 `EASYDOC_ENCRYPTION_KEY_V1` 값 문자열
  고정 검색(`grep -cF`) 결과 **api 0 · worker 0 · migrate 0**.

## 3. 이 스모크가 **도중에 찾은 것** — 로컬 DB 의 Flyway 체크섬 불일치

첫 A-1 실행이 exit 1 로 멈췄고 원인은 게이트 26 과 무관했다:

```
Migration checksum mismatch for migration version 2
-> Applied to database : 1359337517
-> Resolved locally    : 307400641
```

`V3__encryption_scheme_aead.sql` 주석이 예고한 바로 그 경우다 — V2 를 고쳤으므로 **이미 V2 를
적용한 DB 는 다음 기동에서 멈춘다**. 이 저장소의 로컬 볼륨(`easy-doc_postgres_data`)이 Phase 1
시점의 V1·V2 를 들고 있었다.

**조치는 DB 재생성이 아니라 체크섬 정정(repair)이었고, 그 선택의 근거를 실측으로 남긴다.**

- `git diff 2ed897d e891a08 -- <V2 경로>` = **18 삽입 / 9 삭제, 전부 주석**.
  비주석 변경 라인 수를 세면 **0** 이다. 즉 DB 에 적용된 DDL 과 현재 파일의 DDL 이 같고,
  달라진 것은 체크섬 입력(주석)뿐이다. 그래서 repair 는 「다르게 적용된 것을 같다고 우기는」
  조작이 아니다.
- 그럼에도 **먼저 재생성을 시도했고 승인 게이트가 막았다**(로컬 DB 파괴). 막힌 것이 옳다 —
  재생성은 사용자가 이름을 대야 하는 종류이고, 위 실측을 하기 전이었다.
- 실제 조치: `UPDATE flyway_schema_history SET checksum = 307400641 WHERE version = '2' AND checksum = 1359337517;` → `UPDATE 1`.
  **파괴 전 확인**: 제품 4테이블 실제 개수 `users/workspaces/documents/conversions = 0/0/0/0`.

**남기는 사실**: 이 함정은 다음 사람도 밟는다. 로컬 볼륨을 오래 쓰는 개발자는 V2 를 고친
커밋 이후 첫 Kotlin 기동에서 반드시 여기 걸리고, 오류 문구만 보면 「스키마가 갈렸다」로 읽힌다.
실제로는 주석뿐일 수 있으므로 **재생성 전에 diff 의 비주석 변경 수를 먼저 세라.**

## 4. 이 스모크가 **증명하지 않는 것** (오독 금지)

- **업무 흐름을 재지 않았다.** 업로드·조회·다운로드는 도는 코드가 아직 없다(C2~C7 이 만든다).
  이 실행이 닫는 것은 「기동 조건」 하나이고 원장 E2E 게이트 행은 그대로 미실행이다.
- **CI 도달 0이다.** 이 스모크는 `local:docker compose --profile kotlin up` 이며 어느 CI 잡도
  이것을 돌리지 않는다. 다음에 이것이 깨져도 자동으로 아무도 모른다.
- **`kotlin-migrate` 는 여전히 환경으로 키를 받는다.** `docker inspect` 실측 —
  `EASYDOC_ENCRYPTION_KEY_V1` 이 migrate 컨테이너의 `Config.Env` 에 **존재한다**.
  앱 층에서는 그 값이 `SecretKey` 가 되지 않으므로(§2.1) 게이트 26 §5 가 이 항목의 마감을
  **Phase 7**(배포 매니페스트와 함께)로 미룬 판단은 이 실측 뒤에도 유효하다. 다만
  **「compose 층에서도 닫혔다」고 적으면 거짓**이다.
- **키는 이 스모크가 로컬에서 만든 것**이다(`openssl rand -base64 32`). `.env` 는 추적되지
  않으므로 저장소에 들어가지 않는다. 값은 어떤 산출물에도 적지 않았고, 검사값(비밀 아님)만 적었다.

## 5. 상태

**P4 닫힘.** C2 착수를 막는 선결은 없다.
