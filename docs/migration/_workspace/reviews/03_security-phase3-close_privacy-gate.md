# 게이트 24 — `03_security-phase3-close` 보안 축 감사 (privacy-gate)

- **대상**: `9b9d8ad..2a4523d` (7 커밋). 핵심 `01d78a1`(스캐너 정정) · `f51295b`(F-4) · `b529108`(toString 종류 탐지기) · `560c292`(union 파서) · `b9097f6`(401 균일화)
- **정본**: `migration-safety-gate` I-1~I-13 · 계약 `x-auth.failure_uniformity`(`:299-302`) · `x-auth.clock_skew_seconds`(`:272`) · `components/responses/Unauthorized`(`:1500-1510`) · `CLAUDE.md` 보안 규칙·규칙 4
- **판정 요약**: **통과 3 · 해제 1(A-3) · 신규 Minor 1(A-3′) · 잠정 위반 1(I-8, Phase 4 조건) · 기록 3 · 확인 불가 4(Phase 4·5 미구현).**
- **Phase 3 종료 차단: 없음.** §5 Phase 7 즉시 중단 기준 4항 중 이 범위에서 걸린 것 0.

> 모든 판정은 **내가 직접 작성해 돌린 탐침**의 결과다. 구현자 테스트(`SensitiveToStringReachTest`·
> `AuthenticationWorkUniformityTest` 등)의 실측치를 인용해 닫은 항목은 없다 — 기준선으로만 썼다.
> 탐침은 일회용 git worktree `pg24` 에서 돌렸고 감사 종료 시 `git worktree remove --force` 로
> 제거했다(잔여 0 확인). 변조 복원은 `cp` 가 아니라 `git checkout --` 로 했고 sha256 을 본
> 저장소와 대조해 일치를 확인했다. **Kotlin 코드 수정 0 · 커밋 0 · `00_progress.md` 무접촉 ·
> 다른 리뷰어 산출물 무접촉.** 이 문서에 실제 본문·암호문·키·사용자 데이터를 옮겨 적지 않았다.

---

## 0. 기준선

```
./gradlew test --rerun-tasks                → BUILD SUCCESSFUL (26s), exit 0
core 359 / application 44 / infrastructure 115 / api 178 / worker 3 = 699
failures 0 · errors 0 · skipped 0
```

**기준선이지 판정 근거가 아니다.** 아래는 전부 별도 탐침이다.

---

## 1. A-3 Minor 해제 여부 — **해제. 다만 같은 자리에서 신규 Minor(A-3′)를 제기한다**

### 1-1. `User`·`UserResponse` 이메일 마스킹 — 해제

내 반사 탐침(`PgAudit24ToStringProbe`)이 민감 필드에 표식을 심어 **실제로 인스턴스를 만들고**
`toString()` 산출을 읽었다. 구현자 탐지기를 쓰지 않았다.

| # | 모듈 | 타입 | 민감 필드 | `toString()` 산출 | 표식 |
|---|---|---|---|---|---|
| 1 | api | `auth.LoginRequest` | email,password | `LoginRequest(...)` | 가려짐 |
| 2 | api | `auth.SignupRequest` | email,password | `SignupRequest(...)` | 가려짐 |
| 3 | api | `auth.UserResponse` | email | `UserResponse(id=…, email=***)` | 가려짐 |
| 4 | api | `workspace.WorkspaceListItemResponse` | name | `…name=***…` | 가려짐 |
| 5 | api | `workspace.WorkspaceNameRequest` | name | `WorkspaceNameRequest(name=***)` | 가려짐 |
| 6 | api | `workspace.WorkspaceResponse` | name | `…name=***…` | 가려짐 |
| 7 | application | `conversion.Adoption` | text | `Adoption(text=33자, repaired=false)` | 가려짐 |
| 8 | application | `conversion.Outcome$Body` | text | `Body(33자)` | 가려짐 |
| 9 | core | `easyread.RepairPrompt` | system,user | `RepairPrompt(system=33자, user=33자)` | 가려짐 |
| 10 | core | `easyread.SentenceIssue` | sentence,word | `…sentence=33자, word=***` | 가려짐 |
| 11 | core | `llm.LlmCompletion` | text | `…text=33자…` | 가려짐 |
| 12 | core | `privacy.PlaceholderRestoration` | text | `…text=33자…` | 가려짐 |
| 13 | core | `user.User` | email | `User(id=…, email=***, createdAt=…)` | 가려짐 |
| 14 | core | `workspace.Workspace` | name | `Workspace(id=…, name=***, …)` | 가려짐 |

**표식 노출 0 / 14.** `b529108` 이 지목한 여섯(`User`·`UserResponse`·`SentenceIssue`·
`RepairPrompt`·`Outcome.Body`·`Adoption`)이 전부 포함돼 있다. **A-3 Minor 해제.**

### 1-2. 새 DTO 주입 red 재현 — 정상 동작

`core/privacy` 에 `data class PgAudit24Control(val id: String, val body: String)` 을 주입
(재정의 없음) → `SensitiveToStringReachTest` **RED**, 문면이 타입과 필드를 정확히 지목:

```
아래 data class 의 toString() 이 사용자 콘텐츠·개인정보를 그대로 찍는다:
  - kr.easydoc.core.privacy.PgAudit24Control (민감 필드: [body])
```

### 1-3. 「범위 선언형」 바닥 — 정상 동작

`UserResponse.email` 을 토큰 목록 밖 이름(`addr`)으로 바꾸고 재정의를 지우자 → **RED**
(`민감 판정 기준이 아래 타입에 닿지 않는다: [UserResponse]`). 기준이 조용히 좁아지는 방향은
막힌다. 컴파일 축에서 `AuthDtoLeakTest` 도 먼저 깨진다(이중 방어).

### 1-4. `@UserContent` 오용 구조 — 좁힐 수 없다

| 확인 | 실측 |
|---|---|
| `@Target` | `[CLASS]` — 필드·함수에 못 붙는다 |
| `@Retention` | `RUNTIME` — 탐지기가 적재된 클래스를 읽으므로 필수 |
| 판정식 | `field.type == String && (annotated \|\| isSensitiveName(name))` — **OR**. 붙이면 넓어지고, 떼도 이름 규약이 그대로 판정한다 |
| 면제 조항 | 0건. 검사를 끄는 인자·경로 예외 없음 |
| 사용처 | 1건 (`core.easyread.RepairPrompt`) |

**은폐형으로 쓸 수 없는 구조다**(`CLAUDE.md` 규칙 4 — 은폐형은 넓히지 않는다).

### 1-5. **신규 Minor A-3′ — 탐지기가 「1번 파라미터가 value class」인 data class 를 조용히 건너뛴다**

#### 기제

`@JvmInline value class` 파라미터의 `componentN()` 은 JVM 에서 **이름이 맹글링**된다. 탐지기의
`COMPONENT_ACCESSOR = Regex("""component\d+""")` 가 그것을 세지 못해 `components` 가 과소 계수되고,
그 결과 `fields.take(components)` 가 짧아져 생성자 타입 비교가 어긋나며,
`SensitiveToStringReachTest.kt:124` 의 `?: return@mapNotNull null` 로 **클래스 전체가 조용히 빠진다.**

KDoc 은 적재 실패를 "건너뛰지 않고 끊는다"고 적었지만, 후보 선정 단계의 두 `null` 갈래
(`:119`, `:124`)에는 그 규율이 적용되지 않았다.

#### 제품 코드의 실례 — `kr.easydoc.core.privacy.MaskingResult`

내 반사 탐침 실측:

```
component 접근자 = [component2:List]          ← component1 이 맹글링돼 빠졌다
declaredFields  = [maskedText:String, items:List]
ctor(비합성)     = [String, List]
→ 탐지기 매칭 = false   (이 타입은 검사 밖)
```

하필 **마스킹 대응표를 든 타입**이다. 오늘은 자기 `toString()` 재정의가 있어 실누출이 없지만,
그 재정의를 지워도 게이트는 초록이다 — 즉 **지금 지켜지는 이유가 게이트가 아니다.**

#### 음성 대조 — 차이가 value class 하나뿐인 쌍

두 타입 다 `body: String` 을 그대로 들고 `toString()` 재정의가 없다.

| 주입 타입 | 1번 파라미터 | 탐지기 | 실제 `toString()` |
|---|---|---|---|
| `PgAudit24Control(id: String, body: String)` | 평범한 `String` | **RED** | 본문 전문 노출 |
| `PgAudit24ValueFirst(head: PgAudit24Head, body: String)` | `@JvmInline value class` | **BUILD SUCCESSFUL** | **본문 전문 노출** (실측 `표식 포함 = true`) |

즉 **본문을 통째로 찍는 제품 data class 를 넣고 테스트를 돌려도 초록이다.**

#### 왜 이것이 「한 자리」가 아니라 「종류」인가

이 저장소의 규약이 **본문·비밀을 value class 로 감싸는 것**이다 — `MaskedText`·`ModelDraft`·
`ReviewedBody`(`Masking.kt` 「value class 와 toString」 절)·`Secret`·`PasswordHash`. 따라서
**가장 위험한 DTO 가 정확히 탐지 밖에 놓인다.** `b529108` 의 KDoc 이 스스로 겨냥한
Phase 4 의 `DocumentResponse(…)`·`ExportRequest(…)` 가 `body: ModelDraft` 를 1번에 두는 순간
그대로 샌다. 결함이 「옮겨간」 상태라는 점에서 `b529108` 이 고친 것과 **같은 형태**다.

#### 범위 선언형 축이 이것을 못 잡는다

`탐지 범위가 실재한다` 테스트의 두 축 모두 통과한다 — `MIN_PRODUCTION_CLASSES = 60` (실측
**179**, 3배 여유), `KNOWN_SENSITIVE_TYPES` 14타입 바닥에 **value-class-first 가 하나도 없다.**
빈 선언은 아니지만 이 종류에 대해서는 비어 있다.

#### 부수 실측 — 모듈 도달

```
제품 클래스 179  ·  모듈별 {api=50, application=30, core=67, infrastructure=32}
worker 모듈 도달 = false   (api 가 worker 를 의존하지 않는다 — build.gradle.kts 확인)
```

선언(KDoc "다섯 모듈 중 넷")과 일치하므로 오늘은 위반이 아니다. **Phase 5 가 worker 에 DTO 를
만들면 그 모듈은 통째로 검사 밖**이라는 사실만 기록한다.

#### 판정

**신규 Minor(A-3′).** Phase 3 종료 차단은 **아니다** — 오늘 제품 코드의 실누출 0(표 1-1).
**Phase 4 문서 DTO 착수 전 해소**를 조건으로 건다.

- **해제 조건**: ⑴ 후보 선정 단계의 두 `null` 갈래를 「조용히 건너뛰기」에서 **끊기**로 바꾸거나
  (`error(...)`), 맹글링된 `componentN` 을 함께 세도록 판정을 고친다. ⑵ 값 필드가 value class 인
  타입도 표식을 심을 수 있게 `valueFor` 를 넓힌다. ⑶ 검증은 이 문서의 쌍 대조 재현 —
  `ValueFirst` 형태가 **RED** 가 되고 `MaskingResult` 가 후보 목록에 나타날 것.
- **수신자**: `kotlin-implementer` / 참조 `migration-reviewer`·`codex-reviewer`.

---

## 2. 401 네 갈래 타이밍 재실측 (게이트 23 기록 ① 후속) — **토큰 3갈래 통과**

`PgAudit24AuthProbe` — 실제 부트 앱 + 실제 PostgreSQL, `GET /workspaces` 로 통일, 교차 순서
무작위, 표본 각 121 중 워밍업 20 버리고 101.

### 2-1. 응답 시간

| 갈래 | p50 (ms) | n |
|---|---|---|
| 삭제 계정 (유효 서명, 사용자 행 없음) | **1.061** | 101 |
| 위조 (다른 키로 재서명) | **1.058** | 101 |
| 만료 (`exp` 과거, 서명 정상) | **1.058** | 101 |
| 무헤더 | 0.486 | 101 |

```
토큰 3갈래 최대/최소 비 = 1.003   (저장소 문턱 1.5)
네 갈래   최대/최소 비 = 2.185
```

게이트 23 의 **1.95~1.98** 이 **1.003** 으로 해소됐다. 절대값도 정합한다 — 그때 삭제 계정
1.067 / 지금 1.061.

### 2-2. DB 왕복 (JdbcTemplate DEBUG 로 나가는 SQL 문면을 잡았다 — DataSource 를 바꾸지 않았다)

| 갈래 | HTTP | `users` 질의 | 문면 |
|---|---|---|---|
| 삭제 계정 | 401 | **1** | `SELECT 1 FROM users WHERE id = ?` |
| 위조 | 401 | **1** | 〃 |
| 만료 | 401 | **1** | 〃 |
| 비JWT 문자열(`Bearer not-a-jwt`) | 401 | **1** | 〃 |
| 무헤더 | 401 | **0** | — |
| 정상(대조) | 200 | 2 | `SELECT 1 …` + 작업 공간 목록 |

`exists` 문면이 `SELECT 1` 이라 이메일을 적재하지 않는다 — 코드 읽기가 아니라 나간 SQL 로 확인.

### 2-3. 바이트 대조

| 갈래 | 본문 |
|---|---|
| 삭제 계정 · 위조 · 만료 · 비JWT | `{"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}` (동일) |
| 무헤더 | `{"detail":"인증이 필요합니다"}` |

헤더 이름 집합은 **네 갈래 전부 동일**:
`[cache-control, content-type, transfer-encoding, vary, www-authenticate, x-content-type-options]`.

### 2-4. 무헤더 제외의 계약 근거 — 실측

- `x-auth.failure_uniformity` (`:299-302`) 열거 = **이메일 부재 · 비밀번호 불일치 · 토큰 만료 ·
  위조 · 계정 삭제**. **무헤더는 열거에 없다.**
- `components/responses/Unauthorized` (`:1500-1501` 서술, `:1509-1510` 예시) 가 두 문구를
  `no_header` / `invalid_token` 으로 **명시적으로 갈라** 선언한다.

구현자의 주장(계약 열거 밖 · 본문 문구 다름) 두 조각 다 **문서로 참**이다.

### 2-5. 보안 관점 판정 — 계약 취지상 무헤더도 균일해야 하는가 (**리더 판단 재료**)

**균일화할 필요 없다.** 근거는 「열거되지 않았으니까」가 아니라 기제다.

타이밍 채널이 열거 공격의 단서가 되려면 시간차가 **서버만 아는 사실**과 상관돼야 한다.
`failure_uniformity` 가 묶은 다섯은 전부 그 조건을 만족한다 — 「이 이메일이 가입돼 있는가」,
「이 계정이 아직 살아 있는가」는 요청자가 모르고 서버만 안다. 무헤더 ↔ 토큰 있음의 시간차가
가르는 두 상태는 **둘 다 요청자가 직접 만든 것**이고, 그 사이에 서버 비밀이 없다. 정보
이득이 0 이므로 균일화해도 얻는 것이 없다.

**반대 방향에서 새 비용이 하나 생겼다 — 기록 ②.**

`Bearer <아무 문자열>` 이면 서명 검증이 즉시 실패해도 **DB 왕복 1회가 돈다**(실측: `Bearer
not-a-jwt` → 401 · `users` 질의 1). 종전에는 메모리에서 끊겼다. 즉 **자격증명 없는 트래픽이
DB 부하를 만드는 경로**가 열렸고, 로그인 경로의 Argon2 세마포어 같은 상한이 여기엔 없다.

주목할 점은 구현자가 무헤더를 뺀 이유가 *"인증 헤더 없는 트래픽 전부에 DB 부하를 얹게
된다"* 였다는 것이다. **그 논거는 헤더 모양만 갖추면 그대로 성립한다** — 공격자는 무헤더가
아니라 `Bearer x` 를 보내면 되고, 비용은 똑같이 발생한다. 논거가 막으려던 것을 절반만 막았다.

가용성 문제이지 기밀성 문제가 아니라 **I-항목이 아니고 Phase 3 종료 차단도 아니다.** 조치가
필요하다고 판단되면 선택지는 ⑴ 그대로 두고 배포 시 레이트 리밋에 맡긴다, ⑵ 형식이 명백히
JWT 가 아닌 입력(점 2개 없음 등)은 왕복 없이 끊는다 — 다만 그 순간 「형식 오류」와 「서명
오류」가 시간으로 갈리므로 §2-5 의 기제 판정을 그 두 갈래에 다시 적용해야 한다.

### 2-6. nil UUID `exists` 가 새 오라클을 여는가 — **열지 않는다 (기록 ③)**

| 축 | 판정 | 근거 |
|---|---|---|
| 「이 식별자가 있는가」 통로 | 없음 | `ABSENT_USER_PROBE_ID` 가 컴파일 상수(`UUID(0,0)`)라 공격자가 고를 수 없다. 검증 실패한 토큰의 `sub` 를 쓰지 않는다 |
| 인덱스 경로 채널 | 없음 | 질의 문면이 삭제 계정 갈래와 **동일**하고 둘 다 0행. 실측 편차 0.3% (1.058 vs 1.061) |
| 결과가 동작을 가르는가 | 아니오 | 반환값을 쓰지 않는다 — 갈래는 무조건 실패로 끝난다 |
| 잔여 | 이론상 | nil UUID 행이 존재하면 그 갈래만 행을 읽어 시간이 갈린다. 식별자는 `JdbcUserRepository.create` 의 `UUID.randomUUID()`(v4)로만 만들어지므로 발생 경로가 없다 |

---

## 3. 위조 토큰 대조 도구 — **자기 감사 결과: 함정에 빠지지 않았다**

### 3-1. 함정의 재현

HS256 서명 32바이트는 base64url **43글자**로 인코딩되고(43×6 = 258비트) 마지막 글자의
**하위 2비트는 어느 바이트에도 실리지 않는다**. 그 2비트만 바꾸면 문자열은 달라지지만
디코드 결과는 같다 — 즉 **위조가 아니라 같은 토큰**이다.

| 생성 방식 | 서명 문자열 변경 | 디코드 바이트 동일 | 실제로 위조인가 | HTTP |
|---|---|---|---|---|
| ① 디코드 바이트 비트반전 | true | false | **예** | **401** |
| ② 다른 키로 재서명 | true | false | **예** | **401** |
| ③ base64url 마지막 글자 하위 2비트 | true | **true** | **아니오** | **200** |
| (대조) 정상 토큰 | — | — | — | 200 |

### 3-2. 내 게이트 20~23 실측이 ③ 이었을 수 있는가 — **아니다**

판별자는 **응답 코드**다. 함정 ③ 은 서명이 통과하므로 살아 있는 계정에서 **200** 을 낸다.

- 내 게이트 20·22·23 기록은 위조 갈래를 **전건 401** 로 적었다(게이트 22 「무헤더 / 위조 토큰」
  표, 게이트 23 §1-1 「위조 토큰 401 과의 바이트 대조」).
- 더 강한 증거: 게이트 23 타이밍표의 위조 갈래는 **0.539ms** 로 삭제 계정 1.067ms 의 **절반**이었다.
  그 값은 「DB 를 아예 타지 않는 경로」의 값이고, 그것은 **`accessTokens.verify` 가 실제로
  예외를 던졌을 때만** 나온다. 함정 ③ 이었다면 서명이 통과해 왕복 2회 + 200 이 나왔어야 한다.

두 겹으로 「서명 검증이 실제로 실패했다」가 성립한다. **자기 감사 통과.**

### 3-3. 제품 테스트 도구도 함정 밖이다

`api/src/test/kotlin/kr/easydoc/api/support/TestJwt.kt` 의 `withBrokenSignature` 는
**디코드한 바이트 배열의 0번 바이트를 XOR** 한 뒤 재인코딩한다 — 문자 조작이 아니다.
오늘 방식 ① 로 재현해 디코드 바이트가 실제로 달라짐을 확인했다.

### 3-4. 오늘 재측정에 채택한 방식

§2 의 타이밍은 **방식 ②(다른 키로 재서명)** 로 쟀다. 원래 서명 바이트와 무관하게 생성되므로
base64 경계 문제가 원천적으로 없다.

---

## 4. F-4 — 서비스 층 소유 판정 변이 → **구조 red 재현 성공**

일회용 worktree 에서 `WorkspaceService` 에 F-4 가 실증한 형태의 변이 셋을 주입.

| 변이 | 주입 형태 | 결과 | 실패 문면 |
|---|---|---|---|
| rename | 서비스가 `listOwned()` 로 선행 확인 후 저장소 호출 | **RED** | `없음=1 타인=1 내것=2 … 유스케이스가 선행 조회를 얹었다(서비스)` |
| delete | **내 자원일 때만** 조회 하나 추가 | **RED** | `소유 자원 삭제가 3 문장이 아니라 4 문장이다` |
| list | 질의를 두 번 낸다(N+1 대역) | **RED** | `목록이 1 문장이 아니라 2 문장이다` |

계측 진입점이 `JdbcWorkspaceRepository.rename` 한 메서드에서 **유스케이스(= 요청 하나)**로
올라가면서, 게이트 23 에서 구조 축 11/11 · 시간 축 22/22 로 빠져나갔던 우회가 닫혔다.
성공 경로의 정수를 함께 못박은 것이 결정적이다 — F-4 가 실증한 우회가 **내 자원일 때만
조회가 느는 형태**라 「셋이 같다」만으로는 걸리지 않는다.

**전제 하나가 남는다(구현자 KDoc 이 명시).** `CountingDataSource` 는 `Statement` 를 다시 감싸지
않으므로 「문장 하나에 SQL 둘」을 세지 못한다. `JdbcClient` 를 쓰는 한 성립하지 않는 형태이고
오늘 코드로 참이다 — **저장소가 raw JDBC 로 내려가는 커밋에서 이 전제를 다시 봐야 한다.**

---

## 5. Phase 3 종료 전 I-항목 전수 재확인

### 5-1. 스캐너

| 명령 | 결과 |
|---|---|
| CI 명령 (`uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py`) | **exit 0** · 전수 241파일 · **BLOCK 후보 0** · 호출 지점 표기 억제 **7건(상한 7)** |
| `--changed --base 5d762ec` (Phase 3 착수 이후 전체) | **exit 0** · 변경분 70파일 · **BLOCK 후보 0** · 2차 제외 `OWNERSHIP-403` 6건 |
| `uv run pytest tests/test_privacy_scanner.py` | **120 passed, 7 xfailed** |
| `uv run pytest tests/` (전체) | **1272 passed, 68 skipped, 5 deselected, 7 xfailed** |

**억제 7/7 재확인** — 7건 전부 문면을 열어 보간 인자를 확인했다. 표본으로 두 건을 코드에서
직접 대조: `FlywayBaselineGuard.kt:74` 는 `migrationsExecuted`·`targetSchemaVersion` 둘만,
`scripts/collect_golden.py:134` 의 `detail` 은 `category.value`(열거형) + 건수로만 조립된다
(가려진 원문이 들어갈 자리가 없다). 나머지 5건도 문서 id·글자 수·분류값·개수뿐. **오탐 판정
7/7 유효, 상한 초과 0.**

`01d78a1` 자체(정밀화 ③ 에 이름 관문)는 **다른 관점이 리뷰한다** — 여기서는 요청대로 CI 명령
exit 0 과 억제 7/7 만 재확인했다.

### 5-2. I-항목 표

| # | 불변식 | 판정 | 근거 |
|---|---|---|---|
| I-1 | 마스킹 선행 | **준수** | `LlmPrompt` private 생성자가 `MaskedText`/`ModelDraft` 만 받는 타입 관문. 이 범위에서 무변경(`git diff --stat` 확인) |
| I-2 | LLM 호출 ≤2 | 확인 불가(범위 밖) | Phase 5. 오늘 provider 호출 배선 도달 0 |
| I-3 | 원문·결과·대응표 평문 저장 금지 | **준수(도달 범위 내)** | auth·workspaces 는 평문 저장 대상이 아니다. 응답 본문 실측: 401/409/422 세 갈래에 이메일·이름 원문 **0건**(§5-3) |
| I-4 | 평문 로그 0 | **준수 + 기록 ④** | 스캐너 BLOCK 0 · 예외 메시지 구성 지점 고정 문자열(`JdbcUserRepository` 가 원인 체인을 끊는다). 기록: 강제 TRACE 에서 프레임워크 로거 3종(`Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl`)에 레벨 고정이 미도달 — 게이트 23 기록 ③ 그대로이며 **이 범위에서 무변경**(`application.yml` diff 0) |
| I-5 | 타인 자원 404 | **준수** | 실측 §5-3. SQL `WHERE … AND user_id = :ownerId` 4개 질의 전부 + 구조 축(§4) |
| I-6 | private 응답 헤더 | **준수** | 실측 14/14 정확히 1회씩 + 서블릿 미도달 3종(§5-3) |
| I-7 | AEAD round-trip·변조 거부·nonce | **확인 불가 — 구현 없음** | `backend-kotlin` 에 AEAD/GCM 코드 0. Phase 4 |
| I-8 | `encryption_scheme`·`key_version` 키 회전 | **잠정 위반 (Phase 4 해소 조건)** | §5-4 |
| I-9 | Argon2 전체 파라미터 동등성 · 성공 시에만 재해시 | **준수** | `Argon2Policy.matches` 가 7필드(variant·version·memory·iterations·parallelism·salt·hash) 전부 비교. `upgradeEncoding` **미사용**. `AuthService.login` 은 `verify` 성공 뒤에만 `rehashIfOutdated` 호출. 재해시 실패는 best-effort 로 삼키되 로그에 사용자 ID + 예외 **타입 이름**만 |
| I-9b | JWT HS256 · skew 0 | **준수** | 계약 `clock_skew_seconds: 0`(`:272`). Nimbus `DefaultJWTClaimsVerifier`(기본 60초) **미사용**, 만료를 직접 판정. `exp` 부재도 거부 |
| I-10 | 파서 DTD/XXE·zip bomb | 확인 불가(범위 밖) | Kotlin 파서 코드 0. Phase 4 |
| I-11 | 보존 파기 04:00 KST · 500건 | 확인 불가(범위 밖) | Kotlin purge 코드 0. Phase 5 |
| I-12 | 삭제 시 동시 파기 | **부분** | `fk_workspaces_user_id_users` ON DELETE CASCADE 확인. `documents`·`conversions` 연쇄는 Phase 4 에서 함께 봐야 한다(게이트 23 잔여 조건 유지) |
| I-13 | no-training 전제 명시 | **준수** | `AnthropicProvider.kt:27-29` 주석 |

### 5-3. HTTP 경계 실측 (`PgAudit24IProbe` — 실제 앱 + 실제 PostgreSQL, 계정 둘)

**I-5 소유권 은닉**

| 시도 | 결과 |
|---|---|
| 밥 → 앨리스 작업 공간 `PATCH` | **404** `{"detail":"작업 공간을 찾을 수 없습니다"}` |
| 밥 → 앨리스 작업 공간 `DELETE` | **404** (본문 동일) |
| 밥 → 없는 UUID `PATCH` / `DELETE` | **404** (본문 동일) |
| 밥의 `GET /workspaces` | 200 · 앨리스 이름 포함 **false** · 앨리스 workspace id 포함 **false** |

403·200 은 한 건도 없다. 존재하는 남의 자원과 아예 없는 자원이 **상태 코드·본문 모두 동일**.

**I-6 사적 응답 헤더 — 14갈래 전수**

`GET /health` 200 · signup 409 · signup 422 · login 401 · `/auth/me` 200 · `/auth/me` 401 ·
`/workspaces` 200 · 201 · 409 · PATCH 404 · DELETE · 없는 경로 404 · 405 · 깨진 JSON 422

```
헤더 어긋난 응답 = 0 / 14
  (전부 Cache-Control=no-store ×1, X-Content-Type-Options=nosniff ×1 — 이중 부착 0)
```

**서블릿에 닿지 못하는 요청(원시 소켓)** — 밸브 층이 덮는지

| 요청 | 상태 | no-store | nosniff |
|---|---|---|---|
| 깨진 요청 줄 (`HTTP/9.9`) | 505 | true | true |
| 알 수 없는 메서드 (`BREW`) | 405 | true | true |
| 콜론 없는 헤더 줄 | 400 | true | true |

**I-3 응답 본문 평문**

| 응답 | 본문 | 원문 포함 |
|---|---|---|
| 로그인 실패 401 | `{"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}` | 이메일 **false** |
| 중복 가입 409 | `{"detail":"이미 가입된 이메일입니다"}` | 이메일 **false** |
| 이름 초과 422 | `{"detail":"작업 공간 이름은 50자 이하여야 합니다"}` | 이름 원문 **false** |

### 5-4. I-8 — **잠정 위반 (Phase 3 종료 차단 아님 / Phase 4 착수 전 해소)**

`V2__encryption_scheme.sql` 실측:

```sql
ADD COLUMN encryption_scheme character varying(16) DEFAULT 'fernet-v1' NOT NULL;   -- documents, conversions
ADD CONSTRAINT ck_..._encryption_scheme_valid CHECK (encryption_scheme IN ('fernet-v1'));
```

`key_version` 은 `V1__python_schema_baseline.sql:81,114` 에 존재한다(두 컬럼 다 있다).

**무엇이 어긋났나.** V2 는 `2ed897d`(Phase 1)에서 들어왔고, 그 주석이 든 근거 셋이
2026-08-12 결정으로 **전부 무효**가 됐다:

| V2 주석의 근거 | 현재 |
|---|---|
| *"Python 런타임이 이 DB를 계속 읽고 쓸 수 있다"* | Python 폐기 (master-plan 6.2) |
| *"Phase 7 관찰 기간에 Python으로 롤백해도"* | 롤백 포기 (§9 결정 2·3) |
| *"값은 관찰 기간 내내 `fernet-v1` 로 고정한다"* | **이 규칙 자체가 삭제됐다** — 내 역할 명세가 명시적으로 폐기했고, 남은 것은 키 회전 규율뿐 |

`migration-safety-gate` I-8 의 판정 문구는 *"처음부터 새 scheme 값(예: `aes-gcm-v1`)으로 쓰는지 —
`fernet-v1`을 쓰면 위반이다"* 이다. 오늘 스키마는 기본값이 `fernet-v1` 이고 CHECK 가 그 값
하나만 허용한다.

**왜 지금 차단하지 않는가.** 세 가지가 다 참이다: ⑴ 이 컬럼을 쓰는 Kotlin 코드가 **0**,
⑵ `documents`·`conversions` 에 행을 쓰는 경로가 **없다**, ⑶ 따라서 잘못된 scheme 이름이 붙은
행이 **존재할 수 없다**. Phase 3 은 auth·workspaces 이고 이 표면에 닿지 않는다.

**왜 지금 적는가.** Phase 4 의 첫 INSERT 가 컬럼을 명시하지 않으면 DEFAULT 가 조용히
`fernet-v1` 을 채우고, 그 값은 **데이터에 대해 거짓**이다(암호문이 AEAD 인데 이름이 Fernet).
CHECK 가 `aes-gcm-v1` 을 거부하므로 마이그레이션을 먼저 써야 한다는 점은 오히려 안전장치다 —
문제는 **DEFAULT 가 그 안전장치를 우회한다**는 것이다.

- **해제 조건**: Phase 4 에서 저장 암호화를 배선하기 **전에** ⑴ 새 마이그레이션으로 CHECK 도메인을
  넓히고 DEFAULT 를 새 scheme 으로 바꾸거나 DEFAULT 를 제거하고, ⑵ V2 주석의 무효 근거 3개를
  정정하며, ⑶ `encryption_scheme`·`key_version` 이 읽기·쓰기 경로에서 **실제로 쓰인다**는 것을
  실행으로 보인다(회전 시나리오: v1 로 쓴 행을 v2 배선 뒤에도 읽는다).
- **수신자**: `kotlin-implementer` / 참조 리더·`contract-keeper`.

---

## 6. Phase 3 종료 차단 여부 — **차단 없음**

§5 Phase 7 즉시 중단 기준 중 이 역할이 직접 판정하는 넷:

| 기준 | 이 범위 판정 | 근거 |
|---|---|---|
| 마스킹 전 본문이 LLM 이나 로그로 전송됨 | **해당 없음** | provider 호출 배선 도달 0(Phase 5). 타입 관문 무변경. 스캐너 BLOCK 0 |
| 다른 사용자 데이터 노출 또는 404 소유권 규칙 위반 | **해당 없음** | 교차 접근 4건 전건 404·본문 동일, 목록 누출 0(§5-3) + 구조 축 red 재현(§4) |
| AEAD 복호화 round-trip 실패 또는 변조 통과 | **판정 유보** | 구현 없음(Phase 4). 「없어서 통과」가 아니라 **미검사**로 기록 |
| 중복 LLM 호출 | **해당 없음** | 도달 0 |

**미해제 차단 0건.** 차단 사유서(`03_security-phase3-close_privacy-gate_blocking.md`)는 만들지 않는다.

**Phase 4 착수 전 해소 조건 2건** — 종료를 막지는 않으나 다음 Phase 의 첫 커밋보다 앞선다:

1. **A-3′** — 탐지기가 value-class-first data class 를 조용히 건너뛴다 (§1-5)
2. **I-8** — `encryption_scheme` 기본값·CHECK 가 폐기된 전제 위에 있다 (§5-4)

**기록(판정 아님, 리더 판단 재료) 4건**

| # | 내용 | 위치 |
|---|---|---|
| ① | `Bearer <임의 문자열>` 이 무자격 DB 왕복을 만든다 — 무헤더를 뺀 논거가 절반만 적용됐다 | §2-5 |
| ② | nil UUID `exists` 는 새 오라클을 열지 않는다(잔여 조건 명시) | §2-6 |
| ③ | 강제 TRACE 에서 프레임워크 로거 3종 미도달 — 이 범위 무변경, 게이트 23 기록 ③ 승계 | §5-2 I-4 |
| ④ | `CountingDataSource` 는 `JdbcClient` 전제 위에 선다 — raw JDBC 로 내려가는 커밋에서 재확인 | §4 |

---

## 7. 감사 위생

- 탐침 worktree `pg24` — `git worktree remove --force` 완료. `git worktree list` 에 잔여 0.
- 변조 복원은 `git checkout -- backend-kotlin`, sha256 을 본 저장소와 대조해 **일치 확인**
  (`WorkspaceService.kt`·`AuthDtos.kt` 각 `same=true`).
- 본 저장소 `git status --porcelain` — 감사 시작 시점의 미추적 3건 외 변화 0. HEAD `2a4523d` 유지.
- Kotlin 파일 수정 0 · 마커/면제/강등 0 · 커밋 0 · `00_progress.md` 무접촉 · 다른 리뷰어 산출물 무접촉.
- 이 문서에 실제 사용자 데이터·본문·암호문·키를 옮겨 적지 않았다. 재현 값은 전부 합성이다.
