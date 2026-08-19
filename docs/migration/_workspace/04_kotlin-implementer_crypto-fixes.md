# 게이트 25 (`04_crypto`) — Kotlin 몫 조치

**작성:** kotlin-implementer / **일자:** 2026-08-19 / **범위:** `6ac9158..ccc508e` 중 Kotlin 레인 8커밋
**입력:** `reviews/04_crypto_cross.md`(정본) · `reviews/04_crypto_codex-reviewer.md` · `reviews/04_crypto_migration-reviewer.md` · `reviews/04_security_privacy-gate.md`
**성격:** `documents` 단위 착수 전 조치. 배선(문서 API·재암호화)은 이 배치 범위 밖이다.

> **무접촉 확인:** `.claude/**` · `contracts/**` · `docs/migration/_workspace/00_progress.md` 를 한 줄도 건드리지 않았다.
> `app/**` 도 무변경이다. 같은 시각 하네스 레인이 `aad5ca5`·`8f3730f`·`53aa0da`·`e572476`·`1e685dc` 를 올렸고 그 파일들과 겹치지 않는다.

---

## 1. 조치 요약 — 지시 항목 ↔ 커밋

| # | 지시 | 상태 | 커밋 | 무엇을 했나 |
|---|---|---|---|---|
| 1 | **X1** 비쌍 서로게이트 거부 | 완료 | `81f37af` | `PlainBody` 생성 시점 거부(`InvalidInputException` + 고정 문구). round-trip 「거부됨」 케이스 추가 |
| 2 | **R-1** AAD `scheme`·`keyVersion` 결속을 성질로 | 완료 | `558936c` | 키 재료 공유 2세대 케이스 + JCA 미러 케이스. 음성 대조 2건 빨강 |
| 3 | **X10** `wireName` 결속 | 완료 | `7be37db` | `wireName` ↔ 스키마 bytea 컬럼 **양방향** 대조 + V3 SQL 리터럴 ↔ 코드 상수 |
| 4 | **X3** 타이밍 균일화 + **R-4** catch 범위 | 완료 | `558936c` | 조기 반환 제거(더미 키·더미 바이트로 AEAD 1회 균일). `RuntimeException` 포섭. 비 회귀 신설 |
| 5 | **F-3** KCV | 완료 | `a7f6e30` | `KeyCheckValue`(고정 입력 AEAD 태그 6바이트 hex) + 기동 검증 fail-fast |
| 6 | **F-2** 쓰기 키 fail-fast · **V4 CHECK** · `key_version` 범위 | 완료 | `58a292b`·`a7f6e30`·`ccc508e` | V4 `CHECK (key_version > 0)` · `EncryptedContent.KEY_VERSION_RANGE` · 조립 검증 · **V4 강제 확인 테스트** |
| 7 | **H-1** `fun interface` · **U-1** 정확 일치 | 완료 | `0061c8d`·`185dd89` | `MODIFIERS` 에 `fun` + 자기 파일 적재 대조. 선언 수 하한 20 → **정확 일치 46** |
| 8 | **H11** M-3b 상설 회귀 명시 | 완료 | `6431208` | KDoc 에 「상설임」과 codex A-6 한계·문턱 유지 근거. 음성 대조 재확인(2.01배) |
| 9 | **R-10** 일반 class toString 축 | 완료 | `185dd89` | **오늘 0건이 아니었다** — 재정의 9건 중 민감 후보 3건, `ExportFile` 이 파일명을 찍고 있었다 |

**리더 판정 전제는 전부 그대로 따랐다.** X1=거부(치환 수용 아님) / X7=기동 fail-fast(테스트 프로파일 제외, 503 대기 아님) / X8=V4 CHECK / 타이밍=조기 분기도 균일 비용 + 비율 회귀 / F-3=설정 KCV + 기동 fail-fast.
② 행 단위 재암호화 4조건은 **포트 시그니처 준비만** — 배선은 `documents` 단위다(§5).

---

## 2. 무엇을 어떻게 고쳤나 — 판단이 갈릴 수 있는 자리만

### 2.1 X1 — 고치는 자리를 AEAD 가 아니라 **평문의 정의역**으로 잡았다

`AesGcmContentCipher` 에 검사를 넣으면 「cipher 가 거부한다」가 되고, 다른 경로로 만든
`PlainBody` 는 여전히 손상 값을 들 수 있다. `PlainBody` 생성에서 거부하면
`ContentCipher` 의 round-trip 불변식이 **정의역 전체에 대해 전건으로** 참이 된다 —
리더 지시의 문면 그대로다.

부수 효과 하나를 명시한다: `decrypt` 의 `PlainBody(String(opened, UTF_8))` 는 **절대 이
예외를 던질 수 없다.** UTF-8 디코딩은 짝 없는 서로게이트를 만들지 않는다(불가 바이트는
U+FFFD 로 간다). 따라서 이 검사가 복호화 경로에 새 실패 갈래를 만들지 않고, oracle 축도
건드리지 않는다.

**privacy-gate 표의 「U+FFFD 대체」는 오기다**(실제는 인코딩 시점의 `?`, U+003F). cross §4-② 가
JDK 21.0.4 실측으로 확정했고 `PlainBodyTest` 가 매 실행 재현한다. **정정은 privacy-gate 소관**이라
여기서 고치지 않았다 — 언급만 한다.

### 2.2 X3 — 「빠른 갈래를 느리게」로 갔다

반대 방향(느린 갈래를 빠르게)은 AEAD 검증을 건너뛰는 것이라 곧 인증 없는 복호화다.
없는 키 세대에는 **기동 시 난수로 만든 더미 키**, 길이 미달에는 최소 길이 더미 바이트를
넣고 `open()` 을 정확히 한 번 돌린다. 더미 키를 상수로 두지 않은 이유 둘 — 소스에 키
리터럴을 적지 않는다(스캐너 `SECRET-LITERAL`), 그리고 어떤 실제 키와도 같지 않아야 한다.

**측정:** 태그 불일치 6708ns / 모르는 방식 6708ns / 모르는 키 세대 6750ns / 길이 미달 6667ns → **비 1.012**(문턱 1.5).

시간 축 본문을 짧게(`"안내"`) 둔 이유: 길이 미달 갈래만 최소 길이 더미로 대체되므로 정상
봉투가 길면 **AEAD 자신의 길이 비례 비용**이 비에 섞인다. 그 축은 이 케이스가 재는 것이 아니다.

### 2.3 R-4 — 이름 열거가 아니라 `RuntimeException` 종류로 포섭

`ProviderException` 만 더하면 다음 공급자에서 또 빈다(`CLAUDE.md` 규칙 4 — 넓힘은
인스턴스가 아니라 종류만큼). detekt `TooGenericExceptionCaught` 를 `@Suppress` 로 억제했고,
**억제 사유를 KDoc 에 적었다**: 대가는 우리 쪽 프로그래밍 오류(NPE 등)도 복호화 실패로
보이는 것이고, 그 진단은 회귀 테스트가 지며 여기서는 **밖으로 새지 않는 것**이 우선이다.

### 2.4 F-3 — KCV 값을 실패 메시지에 **넣었다**

키 검사값은 결제·HSM 관행에서 공개 가능한 값이다(256비트 키에 대한 태그 6바이트).
넣지 않으면 운영자가 새 세대의 설정을 처음 적을 방법이 없어 fail-fast 가 막다른 길이 된다.
**키 재료는 한 조각도 넣지 않으며**, 그 사실을 base64 전체·뒤 12자·hex 세 축으로 단언한다
(`기동 실패 메시지가 키를 담지 않는다`).

고정 nonce(0×12)를 쓰는데 안전한 근거도 KDoc 에 적었다 — 평문이 비어 있고 언제나 같으며
산출물을 암호문으로 저장·전송하지 않는다. 저장 암호화는 이것과 무관하게 매 호출 새 nonce 다.

### 2.5 F-2 — 면제 스위치를 두면서 **그 스위치의 탐지기**를 함께 뒀다

리더 지시가 「테스트 프로파일 제외」였고, 스위치만 두면 그것이 곧 면제 조항이다.
그래서 ⑴ 끄는 자리를 `build.gradle.kts` 테스트 태스크 **한 줄**로 한정하고,
⑵ `CryptoStartupVerificationTest` 가 **기본값이 켜짐**인지와 **제품 설정에 이 키가 새지
않았는지**를 파일을 훑어 확인한다(모듈 main 리소스 yml 전수 + `docker-compose.yml` +
`Dockerfile` + `.env.example`, 목록을 열거하지 않고 훑는다).

**운영 영향 하나를 보고한다.** `migrate` 프로필도 같은 컨텍스트를 조립하므로, 키가 없으면
`kotlin-migrate` 도 뜨지 않는다. compose 는 `env_file: .env` 라 세 서비스가 같은 키를 받으므로
실무 경로에서는 문제가 없다고 판단했으나, **「마이그레이션에 암호화 키가 필요한가」는
리더가 뒤집을 수 있는 판단**이라 여기 적어 둔다(§4-①).

### 2.6 R-10 — 후보를 「재정의를 **선언한** 일반 class」로 좁혔다

좁힘의 근거가 사유가 아니라 **구조**다: 재정의가 없으면 `Any.toString()`(클래스명@해시)이라
값이 나올 수 없다. 그래서 면제 조항이 아니다.

**오늘 0건이 아니었다** — 재정의 선언 9건 중 민감 후보 3건(`Converted`·`ExportFile`·`StoredUser`),
그중 **`ExportFile.toString()` 이 파일명을 그대로 찍고 있었다.** 파일명은 문서 제목에서 만들어지고
계약이 제목을 사용자 콘텐츠로 분류한다(`x-private-response-headers.applies_to`). 길이만 남기게 고쳤다.
표본을 만들지 못한 후보는 `undecidableGeneralClasses` 에 남고 그 목록이 비어 있는지도 단언한다 —
**판정 불가는 통과가 아니다.**

### 2.7 U-1 — 44 가 아니라 **46** 이다

리뷰 산출물의 44 는 crypto 커밋(`9c7aa03`) 이전 시점 수다. 오늘 실측이 46 이고,
**그 차이를 아무도 눈치채지 못한 것 자체가** 하한을 버리고 정확 일치로 가는 근거다
(「44 이상」이면 46 도 44 도 20 도 전부 초록이다). 상수 KDoc 에 이 경위를 적었다.

---

## 3. 음성 대조 — 전건 실행

> 전부 **일회용 git worktree**에서 돌렸다(`git worktree add` → `git worktree remove --force`).
> `cp`·`stash` 를 쓰지 않았고, 종료 후 본 저장소 `git status` 에 `backend-kotlin/`·`app/`·`.env*` 변경 0건을 확인했다.

| # | 변이 | 기대 | 실측 | 빨개진 케이스 |
|---|---|---|---|---|
| MUT-1 | AAD 에서 `scheme` 축 제거 | 빨강 | **빨강** | `AAD 문자열에 방식·키 세대가 실제로 실린다` · `표준 AES-256-GCM 으로 열린다` · (부수) 타이밍 비 1.64 |
| MUT-2 | AAD 에서 `keyVersion` 축 제거 | 빨강 | **빨강** | `AAD 가 키 세대를 결속한다` · `AAD 문자열에 …` · `표준 AES-256-GCM 으로 열린다` |
| MUT-3 | 타이밍 균일화 제거(조기 반환 복귀) | 빨강 | **빨강 — 비 3.33** (태그 7916ns ↔ 나머지 2375ns) | `조기 분기와 태그 검증 실패의 소요 시간이 갈리지 않는다` |
| MUT-4 | `wireName` 문자열 1글자 변경 | 빨강 | **빨강** | `wireName 이 실제 컬럼을 가리킨다` |
| MUT-5 | `MODIFIERS` 에서 `fun` 제거 | 빨강 | **빨강** | `fun interface 안의 중첩 이름이 적재와 일치한다` · `잡는 형태` |
| MUT-6 | KCV 검사 제거 | 빨강 | **빨강** | F-3 3케이스(`오타 키` · `kcv 가 없다` · `메시지가 키를 담지 않는다`) |
| MUT-7 | `PlainBody` 정의역 검사 제거 | 빨강 | **빨강** | `PlainBodyTest` 2건 · `왕복이 깨질 값은 저장 전에 거부된다` |
| MUT-8 | 면제 스위치를 `api/application.yml` 에 흘림 | 빨강 | **빨강** | `면제 스위치가 제품 설정에 새지 않았다` |
| MUT-9 | V4 의 `ALTER TABLE … CHECK` 제거 | 빨강 | **빨강** | `V4 — key_version 이 0 이하인 행은 저장되지 않는다` |
| MUT-10 | F-2 쓰기 키 검사 제거 | 빨강 | **빨강** | `쓰기 세대의 키가 없으면 기동이 실패한다` |
| MUT-12 | `AuthService` 균일화(`ABSENT_USER_PROBE_ID`) 제거 | 빨강 | **빨강 — 비 2.01** (삭제 계정 1.66ms ↔ 만료 0.82ms) | M-3b |

**MUT-3 은 리뷰 두 레인의 관측을 독립 재현한다** — privacy-gate 2.84배 · codex 6.5배 ↔ 이 측정 3.33배.
방향은 셋이 같고 배수만 측정 방법으로 갈린다는 cross §5-ⓒ 의 판정과 정합한다.

**MUT-11(선언 수 정확 일치)은 돌리지 않았다** — 상수를 46 이 아닌 값으로 바꾸면 즉시 빨개지는 것이
자명하고(`isEqualTo`), 변이 실행이 새 정보를 주지 않는다. 이 판단 자체를 여기 적어 둔다.

---

## 4. 리더 판정이 필요한 것 / 이 배치가 하지 않은 것

### ① `migrate` 프로필도 암호화 키를 요구하게 됐다 — 뒤집을 수 있는 판단

기동 fail-fast 는 `CryptoConfiguration` 빈 생성에서 끊으므로 `migrate` 프로필도 키가 없으면 뜨지 않는다.
스키마 적용은 암호화 키와 무관한 일이므로 **`migrate` 만 면제할 여지가 있다.** 면제하지 않은 이유는
「프로필마다 다른 안전 수준」이 곧 다음 사고의 자리이기 때문이고, compose 가 `env_file: .env` 로 세
서비스에 같은 키를 주므로 실무에서 걸리지 않는다고 봤다. **뒤집으려면 지금이 싸다.**

### ② X5(재암호화 4조건) — **시그니처 준비만 했다**

`ContentCipher` 포트는 이미 행 단위 세대(`writeKeyVersion`)와 컬럼 결속을 요구하는 형태이고,
`EncryptedContent` 가 세 값을 한 타입으로 묶는다. **단일 UPDATE · NULL 보존 · 실패 시 전체 중단 ·
대응표 노출창**은 저장 repository 가 생기는 `documents`/`conversions` 단위에서 구현한다.
지시 문면대로 이 배치에서는 배선하지 않았다.

### ③ 이 배치가 **닫지 않은** cross §9 항목

| 항목 | 왜 안 했나 |
|---|---|
| X2 `PlainBody` 웹 직렬화 fail-closed | 응답 DTO 신설과 동시가 마감(cross §9.2 #10). 오늘 참조 0건이라 경계 테스트를 걸 대상이 없다 |
| H2 중복 FQCN 거짓 양성 · H5 `$ref` 연쇄 · H4 CI 스텝 | 이 배치 지시 밖. H4·H5 는 하네스/계약 레인 소관 |
| H6·H7 스캐너 403 | `privacy-gate` 수신자. 같은 시각 하네스 레인이 `aad5ca5` 로 손댔다 |
| K1·K2·K4·K5 | `contract-keeper` 수신자 |
| S1 체크섬 manifest | 「첫 배포 전」 마감 · 리더 판정 대기 |
| H3 toString 후보 선정(X24-3 3회차) | **사용자 판단 대기**(원장 ⑲). R-10 은 그와 **다른 구멍**이고 이 배치가 닫았다 |

### ④ 커밋 메시지 제목 하나가 부정확하다

`0061c8d` 의 제목이 「… 선언 수를 정확 일치로 바꾼다」인데, U-1(정확 일치)은 다음 커밋
`185dd89` 에 있다. 본문은 amend 로 정정했으나 제목은 비-HEAD 커밋이라 대화형 rebase 없이
고칠 수 없어 그대로 뒀다. **내용 오류가 아니라 제목 범위 오기다.**

---

## 5. 검사 표

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 빌드·린트·테스트 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **exit 0** · 81 tasks 전부 실행 · warning 0 (`allWarningsAsErrors`) |
| 테스트 건수 | 산출물 XML 집계 | **98 클래스 / 754 케이스** (게이트 25 시점 729 → +25) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **BUILD SUCCESSFUL** |
| 개인정보 스캐너 (CI 명령 그대로) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** (BLOCK 후보 0건) |
| Python 무변경 | `git status` · `git diff --stat 6ac9158..HEAD -- app/` | **app/ 변경 0** |
| Python 게이트 | `uv run ruff check .` / `uv run mypy . .claude` / `uv run pytest` | §5.1 참고 |

### 5.1 Python 게이트 실행 결과

- `uv run ruff check .` — **exit 0** (`All checks passed!`)
- `uv run mypy . .claude` — **exit 0** (`Success: no issues found in 138 source files`)
- `uv run pytest` — **exit 0** (`1321 passed, 69 skipped, 5 deselected, 7 xfailed`)

`app/**` 은 무변경이므로 이 게이트가 깨졌다면 원인은 이 배치 밖이다(같은 시각 하네스 레인이
`tests/test_kotlin_gate_reach.py` 를 신설하고 `.github/workflows/ci.yml` 을 고쳤다).

### 5.2 이 배치가 **실행하지 않은** 검사

- **프론트엔드 게이트**(`npm run build`·e2e) — 이 배치는 `frontend/` 를 건드리지 않았다.
- **compose 기동 스모크** — 기동 fail-fast 도입이 `docker compose --profile kotlin up` 동작을
  바꿨을 가능성이 있다(§4-①). 로컬 `.env` 에 `EASYDOC_ENCRYPTION_KEY_V1` 이 없으면 세 서비스가
  뜨지 않는다. **미실행**이며, `documents` 단위 착수 전에 한 번 돌려 보는 것을 권한다.
- **`parityHarness`** — 이 배치는 parity 도메인을 건드리지 않았다.

---

## 6. 개선 후보 — 적용하지 않았다

`docs/migration/_workspace/04_kotlin-implementer_improvement-backlog.md` 대신 여기 적는다(항목이 셋뿐이다).

1. **`AesGcmContentCipher.decrypt` 의 길이 미달 갈래를 실제 길이로 패딩**하면 비가 더 안정될 수 있다.
   오늘 1.012 라 필요가 없고, 넣으면 더미 바이트를 매 호출 할당하게 된다.
2. **`EncryptionProperties.keys` 를 `Map<Int, …>` 로**. 오늘 목록인 이유는 `toString` 게이트가 지도
   갈래를 모르기 때문이고(그 KDoc 에 적혀 있다), 게이트를 넓히는 판단은 게이트 소유 레인 몫이다.
3. **KCV 계산 CLI** — `openssl rand -base64 32` 로 키를 만든 뒤 KCV 를 알려면 앱을 한 번 띄워야 한다.
   작은 Gradle 태스크 하나면 되지만, 키를 다루는 새 진입점이라 보안 레인 검토 없이 넣지 않았다.
