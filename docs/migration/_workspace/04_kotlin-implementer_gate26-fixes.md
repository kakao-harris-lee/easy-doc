# 게이트 26 (`04_gate25-fixes`) — Kotlin 몫 조치

**작성:** kotlin-implementer / **일자:** 2026-08-19 / **기준 HEAD:** `0ce88b4` → **`e02c6e4`**
**입력:** `reviews/04_gate25-fixes_cross.md` §2.3·§5.2·§5.4·행 21 · `reviews/04_security-crypto-fixes_privacy-gate.md`(S-2·R-2·R-5) · `04_kotlin-implementer_crypto-fixes.md`
**범위:** `backend-kotlin/**` 만.

> **무접촉 확인 (실측).** 이 배치의 4커밋이 건드린 파일은 전부 `backend-kotlin/**` 이고,
> 산출물은 이 문서 하나다. `tests/**` · `.claude/**` · `.github/**` ·
> `docs/migration/_workspace/reviews/**` · `00_progress.md` · `parity/fixtures/**` ·
> `CLAUDE.md` · `app/**` · `docker-compose.yml` 무변경.
> 같은 시각 하네스 레인이 `446f946`(도달 대조) · `6040978`(스캐너 `OWNERSHIP-403`)을 올렸고
> 겹치는 파일이 없다.

---

## 0. 계획 (구현 착수 **전에** 적고 `b85b66a` 로 커밋했다)

프로젝트 `CLAUDE.md` 「구현 전 리서치·계획」. 계획 커밋이 조치 커밋들보다 앞선다.

### 0.1 라이브러리 리서치 — **기억이 아니라 이 저장소가 실제로 쓰는 산출물로 확인**

| 확인한 것 | 방법 | 결과 |
|---|---|---|
| Spring Boot 4.1.0 이 `EnvironmentPostProcessor` 를 `META-INF/spring.factories` 로 적재하는가 | `spring-boot-4.1.0.jar` 의 `META-INF/spring.factories` 를 풀어 읽음 | **그렇다.** 키가 `org.springframework.boot.EnvironmentPostProcessor` — Boot 3 의 `org.springframework.boot.env.*` 가 **아니다**(패키지 이동). 기억대로 썼으면 조용히 적재되지 않았을 자리 |
| 그 인터페이스 시그니처 | `javap` | `postProcessEnvironment(ConfigurableEnvironment, org.springframework.boot.SpringApplication)` |
| `ApplicationContextRunner` 좌표·API | `unzip -l spring-boot-test-4.1.0.jar` · `javap` | `org.springframework.boot.test.context.runner.ApplicationContextRunner`, `withBean(Class, Supplier, ...)` 존재 |
| **서명 없는 JCE `Provider`** 로 `Cipher` 를 바꿔치기할 수 있는가 | 툴체인과 같은 JDK(Temurin 21.0.4)에서 `javac -d out` → `java -cp out` 실행 | **된다.** 단일 파일 실행이 아니라 **디렉터리 클래스패스**에서도 우리 공급자가 선택되고 `ProviderException` 이 올라온다 |

### 0.2 기구현 재사용 (새로 만들지 않은 것)

`KeyCheckValue.of`(테스트 키의 kcv 를 **제품 코드로** 계산) · `Secret` · `PostgresTestSupport` ·
`CryptoStartupVerificationTest` 의 케이스 형태(실행 시점 난수 키, 소스에 키 리터럴 0) ·
`MigrationCatalog` · 프로필 이름 `migrate`.

---

## 1. 조치 요약 — 지시 항목 ↔ 커밋

| # | 지시 | 상태 | 커밋 | 무엇을 했나 |
|---|---|---|---|---|
| 5 | 원시 제어문자 2자리 | 완료 | `a68facd` | `AesGcmContentCipherTest.kt:75` NUL 1개 · `WorkspaceNameRules.kt:45` `0x01` 2개 → 이스케이프 표기 |
| 1 | 기동 자기점검 **우회 스위치 제거** | 완료 | `d9eeb9a` | `verifyOnStartup` 필드 · 빌드 스크립트의 시스템 속성 · 그 스위치를 지키던 탐지기 2케이스를 **함께** 삭제. 테스트는 끄는 대신 **진짜 키**를 받는다 |
| 2 | `migrate` 를 **선언되고 테스트된 면제**로 | 완료 | `d9eeb9a` | `@Profile("!migrate")` + 양방향 고정 테스트 2파일(6케이스) + yml 문서 정정 |
| 3 | **S-2** 값이 빈 키 세대 | 완료 | `d6abe51` | `.filterNot { it.value.isBlank() }` 제거 · 두 갈래를 다른 문구로 · 회귀 2건 |
| 4 | **D-3** `R-4` 음성 통제 | 완료 | `e02c6e4` | `ProviderException` 을 던지는 JCA 공급자를 끼워 실제 갈래를 만든다 + 도달 계수 |

---

## 2. 무엇을 어떻게 고쳤나 — **판단이 갈릴 수 있는 자리만**

### 2.1 조치 1 — 스위치를 없앤 대가는 「테스트가 키를 갖는 것」이고, 그것이 R-1 을 함께 닫았다

스위치를 지우면 테스트 Spring 컨텍스트도 자기점검을 지나야 한다. 지나려면 유효한 키가 필요하다.
선택지는 셋이었고 고른 이유를 적는다.

| 선택지 | 기각/채택 사유 |
|---|---|
| 테스트 리소스 yml 에 키 리터럴 | **기각.** 소스에 든 키 재료다(스캐너 `SECRET-LITERAL`, `CLAUDE.md` 보안 규칙). 이 저장소의 기존 테스트가 전부 실행 시점 난수를 쓰는 것과도 어긋난다 |
| `@SpringBootTest` 클래스마다 `@DynamicPropertySource` | **기각.** 오늘 11개이고 앞으로 는다. **새 테스트가 빠뜨리는 것이 기본**이 되고, 빠뜨린 테스트는 기동 실패로 빨개지므로 다음 사람은 그것을 「이 테스트만 키를 안 주면 되는 것」으로 배운다 — 면제를 다시 만드는 길 |
| **testFixtures 의 `EnvironmentPostProcessor`** | **채택.** 실행 시점 난수 키 + **제품 `KeyCheckValue`** 로 계산한 kcv 를 `addLast` 로 넣는다. 우선순위가 가장 낮아 실제 환경변수·`@TestPropertySource`·`@SpringBootTest(properties=…)` 가 전부 이긴다 |

**이것은 면제가 아니라 fixture 데이터다** — 자기점검을 끄지 않고 **지나게** 한다. 실측이 그렇게 말한다:

- 자기점검 **통과 로그가 남은 테스트 클래스 13개** (api 10 · worker 1 · infrastructure 2).
- 「건너뛴다」 로그 **0건** — 그 갈래는 코드에서 사라졌다.
- 게이트 25 시점 privacy-gate **R-1** 은 *"저장소의 어떤 Spring 컨텍스트도 `verify()` 를 실행하지 않는다"*(건너뜀 11 · 0세대 12) 였다. **그 상태가 이 배치로 닫혔다.**

제품 유출 방지는 두 겹이다 — ⑴ `testFixtures` 산출물은 `testFixtures(project(...))` 로 명시적으로
당긴 테스트 클래스패스에만 오르고 `bootJar` 의 `runtimeClasspath` 에는 없다, ⑵ 그래도
`requireTestRuntime()` 이 JUnit 표식 부재 시 **던진다**. 조용한 갈래를 두지 않은 이유는
「제품에서 이 클래스가 아무것도 안 한다」가 되면 실수로 실린 사실 자체를 아무도 못 보기 때문이다.

**저장소에 스위치 잔재 0건**(실측: `verify-on-startup|verifyOnStartup|VERIFY_ON_STARTUP` 적중은
「없앴다」를 설명하는 주석 3줄뿐).

### 2.2 조치 2 — 면제 조건을 **부정 목록**으로 썼다

`@Profile("!migrate")` 이지 `@Profile("api | worker")` 가 아니다. 허용 목록이면 새 프로필
(예: 재암호화 배치)이 조용히 키 없이 뜨는 쪽으로 기본값이 잡힌다. 부정 목록은 **키를
요구하는 쪽이 기본**이다.

**「검사만 건너뛴다」가 아니라 「조립하지 않는다」로 갔다.** privacy-gate R-2 의 축은 최소 권한이고,
검사만 끄면 `migrate` 는 여전히 키를 `SecretKey` 로 만들어 메모리에 든다. 대가 하나를 명시한다 —
앞으로 `ContentCipher` 를 주입받는 빈이 `migrate` 프로필에서도 스캔되면 **그 프로필의 기동이 깨진다.**
조용한 실패가 아니라 빈 생성 오류이므로 그때 판단하면 된다고 봤다.

**`MIGRATE_PROFILE` 상수가 두 곳에 있다**(`infrastructure` 의 공개 상수 · `ApiApplication.kt` 의
private 상수). 합칠 수 없는 이유는 의존 방향이다 — `api` 는 `infrastructure` 를 `runtimeOnly` 로만
의존해 컴파일 시점에 그 상수를 볼 수 없다. 어긋나는 방향이 **fail-closed**(`migrate` 가 다시 키를
요구해 배포에서 시끄럽게 드러난다)라 소스 대조 장치를 새로 두지 않았다. **이 판단은 갈릴 수 있다.**

### 2.3 조치 3 — 「값도 kcv 도 없는 세대」도 끊기로 했다 (privacy-gate 가 함께 정하라고 한 자리)

privacy-gate 해제 조건이 *"값도 kcv 도 둘 다 빈 세대를 어떻게 볼지는 함께 정해 근거를 적는다"*
였다. **끊는 쪽으로 정했다.**

- 허용하면 「회전 뒤 옛 세대의 환경변수가 빠졌다」(사고)와 「아직 안 채운 자리」(의도)가
  **설정만 보고는 구분되지 않는다.** 구분할 수 없는 두 상태를 같은 취급으로 통과시키는 것이
  이 항목이 지적한 침묵 그 자체다.
- 비용이 낮다. 자리를 미리 잡아 두려면 세대 항목을 값이 생길 때 더하면 된다.
- 두 갈래는 운영자가 잃은 것이 다르므로 **문구를 나눠** 알린다.

**부수 효과 하나를 보고한다.** `api`·`worker` 의 `application.yml` 은 `keys[0]` 을 환경변수
자리표시자로 선언한다. 그래서 키를 하나도 주지 않고 띄우면 이제 **문제가 두 줄** 나온다 —
「쓰기 세대 v1 의 키가 적재되지 않았다」 + 「키 v1 에 값도 kcv 도 없다」. 중복이 아니라
같은 오설정의 두 축이고, 실측은 음성 대조 **N-1** 로그에 있다.

### 2.4 조치 4 — 제품 코드에 시험용 이음매를 내지 않았다

`open` 안에서 `RuntimeException` 이 나올 수 있는 자리는 **JCA 공급자 하나뿐**이다(짧은 봉투·
모르는 키 세대는 호출 전에 균일화 갈래로 대체되므로 예외를 만들지 못한다). 그래서 선택지는 둘이었다.

| 선택지 | 판단 |
|---|---|
| `Cipher` 팩토리를 생성자로 주입 | **기각.** 제품 API 를 시험을 위해 넓힌다. `random` 이음매는 「음성 대조가 nonce 를 고정해야 한다」는 성질 때문에 있는 것이라 선례가 아니다 |
| **표준 JCA 확장점으로 공급자 교체** | **채택.** 제품 코드 무변경. 대신 전역 상태(`java.security.Security`)를 건드리므로 `finally` 로 되돌리고, **되돌린 뒤 정상 복호화가 살아 있는지도 같은 케이스에서 확인**한다 |

**도달 계수를 함께 잰다.** 공급자가 선택되지 않으면 케이스는 아무것도 재지 않은 채 초록이 된다.
`engineDoFinal` 도달 횟수가 0이면 실패한다.

전제 하나가 실측에 걸려 있다 — 이 저장소의 Gradle 테스트는 병렬 실행 설정이 없다
(`maxParallelForks` 없음 · `junit-platform.properties` 없음). 병렬을 켜면 이 케이스가
다른 테스트의 암호 연산을 밟을 수 있으므로, 그때 격리를 다시 봐야 한다.

### 2.5 조치 5 — 이 커밋 자신의 diff 는 **여전히 `Bin`** 이다

옛 블롭이 바이너리면 한쪽만으로도 git 이 그렇게 판정한다. 텍스트로 읽히는 것은 **다음 변경부터**다.
`.gitattributes text` 는 쓰지 않았다 — git 의 렌더링만 바꾸고 디스크의 바이트는 그대로여서
`grep`·민짜 `diff`·`file` 의 스니핑은 계속 속는다(codex D-1 의 기각 근거를 그대로 따랐다).

**이 문서를 쓰는 도중에도 같은 사고가 났다.** 조치 5 커밋 메시지에 원시 제어문자가 섞였고
도구 가드가 잡아 커밋이 거부됐다. cross §7.2 가 *"NUL 을 언급하려면 NUL 을 타이핑하게 되고,
아무 도구도 그것을 막지 않는다"* 로 적은 기제의 **네 번째 재발**이다. 이후 커밋 메시지와 이 문서는
전부 파일로 쓰고 제어문자 0 을 단언한 뒤 넘겼다.

---

## 3. 음성 대조 — 전건 실행

> 전부 **일회용 `git worktree`**(`git worktree add --detach` → `git worktree remove --force`).
> `cp`·`stash` 를 쓰지 않았다. 종료 후 본 저장소 `git status` 에 내 경계 파일 변경 0건 확인.

| # | 변이 | 기대 | 실측 | 빨개진 케이스 |
|---|---|---|---|---|
| N-1 | testFixtures 의 `spring.factories` 삭제(테스트 키 공급 제거) | 빨강 | **빨강** | `WorkerStartupTest` 3건 전부. 원인 로그가 `contentCipher` 빈 생성 실패 + 자기점검 메시지 — **Spring 컨텍스트가 실제로 자기점검을 지난다는 직접 증거** |
| N-2 | `CryptoConfiguration` 에서 `@Profile("!migrate")` 제거 | 빨강 | **빨강** | `migrate 프로필은 키 없이 뜬다` · `migrate 프로필에는 ContentCipher 빈이 아예 없다` |
| N-2b | 면제를 **조용히 넓힘**(`!migrate & !api`) | 빨강 | **빨강** | `api·worker·프로필 미지정은 키가 없으면 거부한다` · `api 프로필은 유효한 키로 조립되고 그 빈이 실제로 왕복한다` |
| N-4 | `.filterNot { it.value.isBlank() }` 복귀 | 빨강 | **빨강** | S-2 2건 |
| N-5 | `open` 의 `catch (RuntimeException)` 제거 | 빨강 | **빨강** | R-4 1건. 실패 문면이 *"Expecting actual throwable to be an instance of DecryptionFailedException but was …"* — 정확히 막으려던 갈래다 |
| N-6 | `@Profile` 제거 (end-to-end 축) | 빨강 | **빨강** | `MigrateProfileWithoutEncryptionKeyTest` 2건 |
| N-3 | 조치 5 의 음성 대조 | — | **대신 하네스 레인의 전수 탐지기로 확인** | `tests/test_raw_control_chars.py` 실행 시 **내 두 파일은 위반 목록에 없다**(§5.3) |

**N-2 와 N-6 은 같은 변이의 두 축**이다(러너 단위 / 실제 앱 단위). 둘 다 두는 이유는
러너가 `application.yml`·Flyway 배선을 지나지 않기 때문이다.

---

## 4. 리더 판정이 필요한 것 / 이 배치가 하지 않은 것

### ① 하네스 레인의 새 제어문자 탐지기가 **지금 빨갛다** — 남은 4건이 전부 내 경계 밖이다

`tests/test_raw_control_chars.py` 실측(현재 작업 트리):

| 파일 | 제어문자 | 누구 몫인가 |
|---|---|---|
| `docs/migration/_workspace/reviews/12_export-luhn-suppression_cross.md` | `0x1f` `0x7f` 4개 | **심판문** — 조치 레인이 고치지 않는다(지시 명시) |
| `docs/migration/_workspace/reviews/13_regression-and-pins_migration-reviewer.md` | `0x7f` 1개 | 같음 |
| `parity/fixtures/export/export.json` | `0x7f` 21개 | **`parity-verifier` 와 공용** — 형식 변경은 합의 후에만 |
| `parity/fixtures/text/text.json` | `0x7f` 1개 | 같음 |

**두 fixture 는 값을 바꾸지 않고 고칠 수 있다.** JSON 에서 `\u007f` 는 원시 `0x7f` 와 **파싱
결과가 완전히 같다**. 실제로 `export.json` 은 같은 목록 안에서 `\u0000`·`\u001f` 를 이미
이스케이프로 적고 있고 `0x7f` 만 raw 다 — 일관성 결함에 가깝다. **다만 내 경계 밖이라 손대지
않았다.** 리더가 `parity-verifier` 에 넘길 항목이다.

`docs/migration/_workspace/02_kotlin-implementer_export-domain.md` 는 커밋된 블롭에 `0x01` 이
있으나 **작업 트리에서는 이미 고쳐져 있다**(미커밋). 같은 시각 다른 레인이 잡은 것으로 보인다.

### ② `docker-compose.yml` 은 여전히 `kotlin-migrate` 에 키 환경변수를 넘긴다

앱은 이제 그 값을 **읽지 않는다**(`migrate` 에 `CryptoConfiguration` 이 없다). 그러나 compose 의
`env_file: .env` 는 세 서비스에 같은 파일을 준다. privacy-gate R-2 의 최소 권한을 끝까지 실현하려면
compose 쪽에서도 그 변수를 빼야 하는데, `docker-compose.yml` 은 **내 파일 경계 밖**이다.
오늘 상태는 「키가 프로세스 환경에 있으나 아무도 읽지 않는다」이고, 이것을 남길지 리더가 정한다.

### ③ 이 배치가 **닫지 않은** cross 항목 (지시 밖)

| 항목 | 왜 안 했나 |
|---|---|
| 행 12 타이밍 문턱 1.5 의 유도(codex A-5·D-2) | **지시가 명시적으로 이월**(리더: 「이 자리는 이번 배치에서 고치지 마라」) |
| 행 10 AAD 형식 KAT 벡터(Claude S-7) | 이 배치 지시 밖. 마감이 「첫 INSERT 전」 |
| 행 16 `V4` 의 `NOT VALID` | 지시가 「운영 지적이고 이번 마감이 아니다」 |
| 행 7 일반 class toString 후보 탈락 · 행 18·19 선언 수/하한 | 지시 밖(하네스·테스트 축) |
| 행 14 `PlainBody` 의 JVM 경계(R-5) | 세 레인이 **도달 0 으로 합의**한 기록 항목. 오늘 열 수 있는 경로가 없다 |
| 행 27·28·30 (예외 타입 · `kcv` 바인딩 · 422 거부율) | Minor 권고, 지시 밖 |
| 행 5·6·23·24·25 | 하네스·계약·스캐너 레인 소관 |

### ④ 판단이 갈릴 수 있다고 스스로 보는 세 자리 (§2 에 근거를 적었다)

⑴ `MIGRATE_PROFILE` 상수 이중화를 소스 대조로 묶지 않은 것(§2.2) ·
⑵ 「값도 kcv 도 없는 세대」를 **경고가 아니라 실패**로 정한 것(§2.3) ·
⑶ 조치 4 가 전역 `Security` 를 건드리는 것(§2.4 — 병렬 실행을 켜면 재검토가 필요하다).

---

## 5. 검사 표

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 빌드·린트·테스트 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **BUILD SUCCESSFUL** · 82 tasks 전부 실행 · warning 0 |
| 테스트 건수 | 산출물 XML 집계 | **100 클래스 / 761 케이스 / 실패 0 / 오류 0 / 건너뜀 0** (게이트 25 시점 98/754) |
| 자기점검 도달 | XML 에서 통과 로그 검색 | **13 클래스** (게이트 25 시점 **0**) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **BUILD SUCCESSFUL** |
| 개인정보 스캐너 (CI 명령 그대로) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** · BLOCK 0건 |
| Python 린트 | `uv run ruff check .` | **exit 0** (`All checks passed!`) |
| Python 타입 | `uv run mypy . .claude` | **exit 0** (`Success: no issues found in 139 source files`) |
| Python 테스트 | `uv run pytest` | **exit 1 — 1 failed, 1384 passed, 68 skipped, 5 deselected, 5 xfailed** (§5.1) |

### 5.1 왜 Python 테스트가 빨간가 — **내 변경 때문이 아니다**

실패는 `tests/test_raw_control_chars.py::test_추적_파일에_원시_제어문자가_없다` 하나이고,
위반 4건은 전부 **내 경계 밖 파일**이다(§4-①). 같은 시각 하네스 레인이 신설한 장치이고,
`app/**` 은 이 배치에서 무변경이다. **내가 고칠 수 없는 실패이므로 「통과」로 적지 않는다.**

### 5.2 이 배치가 **실행하지 않은** 검사

- **프론트엔드 게이트**(`npm run build`·e2e) — `frontend/` 무접촉.
- **`parityHarness`** — parity 도메인 무접촉.
- **compose 기동 스모크**(`docker compose --profile kotlin up`) — **미실행**. 조치 2 가 `migrate` 의
  기동 조건을 바꿨고 `MigrateProfileWithoutEncryptionKeyTest` 가 그 성질을 실제 `application.yml` 로
  재지만, **컨테이너 경로 자체는 돌려 보지 않았다.** `documents` 착수 전에 한 번 권한다.
- **`tests/golden`** — 프롬프트·스타일 규칙 무접촉.

### 5.3 조치 5 의 확인

하네스 레인의 전수 탐지기(`tests/test_raw_control_chars.py`, 텍스트 718개 훑음)에서
**`AesGcmContentCipherTest.kt` 와 `WorkspaceNameRules.kt` 는 위반 목록에 없다.**
`git ls-files` 전수 재확인에서도 `backend-kotlin/**` 의 비바이너리 위반은 0건이다.

---

## 6. 개선 후보 — 적용하지 않았다

1. **`parity/fixtures/*.json` 의 raw `0x7f` → `\u007f`** — 값이 바뀌지 않는다(§4-①). 경계 밖이라
   `parity-verifier` 합의가 필요하다.
2. **compose 에서 `kotlin-migrate` 의 암호화 키 환경변수 제거**(§4-②) — 경계 밖.
3. **`EncryptionProperties.keys` 를 `Map<Int, …>` 로** — 직전 배치에서 올린 것이 그대로 남아 있다.
   `toString` 게이트가 지도 갈래를 모르는 것이 이유이고, 게이트를 넓히는 판단은 게이트 소유 레인 몫.
4. **KCV 계산 CLI** — 직전 배치와 같은 사유로 보류(키를 다루는 새 진입점).
5. **`ConfigurationPropertiesBindingTest` 에 `kcv` 바인딩 축 추가**(cross 행 28, T-6) — Minor 권고이고
   이 배치 지시 밖이라 넣지 않았다. 한 줄이다.
