# 게이트 23 교차 종합 — Kotlin 몫 4건 조치 (`03_workspaces-fixes2`)

**작성:** kotlin-implementer / **일자:** 2026-08-19
**입력:** `reviews/03_workspaces-fixes_cross.md` (ⓒ·ⓓ·ⓔ·§6-② 조치 목록) ·
`reviews/03_workspaces-fixes_migration-reviewer.md` (F-3·F-4·F-5·A-10) ·
`reviews/03_security-workspaces-fixes_privacy-gate.md` (3a Minor · 기록 ①③)
**기준 커밋:** `b401039`(직전 HEAD) → **`b9097f6`** (Kotlin 4커밋)
**리더 판정 전제:** F-4 는 차단②로 올리지 않되 이 배치에서 닫는다 / xfail 유지 / 표 5 심각도는 사용자 대기 /
401 타이밍은 X-1 이 만든 채널이 아니므로 문면 정정 + 측정 등재, 값싼 균일화가 있으면 적용.

**무접촉 선언:** `contracts/` · `.claude/` · `00_progress.md` · `app/**` · `frontend/**` — **변경 0**
(`git diff --stat b401039..HEAD` 로 확인). 스캐너(Python)는 privacy-gate 레인이 `01d78a1` 로 먼저 고쳤고,
이 배치와 **파일이 겹치지 않는다.**

---

## 0. 한 줄 요약

| # | 항목 | 상태 | 커밋 |
|---|---|---|---|
| 1 | **F-4** 구조 축 도달을 서비스 경계로 | **닫음** — 실증된 우회가 빨개진다 | `f51295b` |
| 2 | **A-3/C-4** `toString` 비대칭 이동 | **닫음** — 6타입 수정 + **종류 탐지기** 신설 | `b529108` |
| 3 | **C-5/A-10** `ErrorResponse` union fail-open | **닫음** — 파서 + 소비자 둘 다 | `560c292` |
| 4 | **F-3/F-5** 산출물 문면 | **정정** | 문서 커밋 |
| 5 | **401 타이밍** 균일화 | **적용** — 토큰 세 갈래 비 **2.356 → 1.007~1.036** | `b9097f6` |

**남긴 것(의도):** 표 5 의 나머지 fail-open 2자리(`headerComponentsByName`·`collectHeaderRefs`)는
마감이 「계약에 인라인 헤더가 처음 생기는 커밋」이라 이 배치의 범위 밖이다 — §6 에 등재한다.

---

## 1. F-4 — 구조 축 계측을 **서비스 경계**로 올린다

### 1-1. 무엇이 문제였나

종전 단언은 `JdbcWorkspaceRepository.rename` **한 메서드**를 감쌌다. 그래서 소유 판정을
`WorkspaceService` 로 올린 변이(서비스가 `listOwned()` 로 먼저 확인하고 저장소는 그대로)가
**구조 축 11/11 · 시간 축 22/22 초록**으로 두 게이트를 모두 빠져나갔다(migration-reviewer F-4 실증).
「소유 조건이 SQL 을 떠났는가」라는 **선언된 주제 자체가 한 층 위에서 검사되지 않았다.**

### 1-2. 무엇을 바꿨나

계측 진입점을 **유스케이스 호출 하나 = 요청 하나**로 올리고, 대상을 `rename` 하나에서 넷으로 넓혔다.
트랜잭션 관리자도 같은 `CountingDataSource` 를 받게 했다 — 다른 것을 주면 `delete` 의 `FOR UPDATE`
가 계측되지 않은 커넥션에서 돌아 **세는 대상과 도는 대상이 갈린다.**

| 단언 | 값 | 무엇을 강제하는가 |
|---|---|---|
| `rename` 없음·타인·내것 | **1 · 1 · 1** | 소유 판정과 갱신이 한 문장 (저장소) + 선행 조회 없음 (서비스) |
| `delete` 거절 없음·타인 | **1 · 1** | `lockForDeletion` 의 잠금 질의 하나 — 존재 여부가 일한 양으로 새지 않는다 |
| `delete` 소유(성공) | **3** | 잠금 + 문서 수 + DELETE. **성공 경로의 정수를 놓아 두면 F-4 우회 자리가 그대로 열린다** |
| `list` (자원 2건) | **1** | 문서 수가 같은 질의에 담긴다 — N+1 이면 3 |

`lockForDeletion` 은 별도 케이스를 만들지 않고 `delete` 의 두 정수가 그 분해다(1 / 2+1).
표면을 늘리는 대신 **요청 단위 정수**로 덮는 편이 F-4 가 지적한 층위 문제를 다시 만들지 않는다.

`CountingDataSource` KDoc 에 codex C-3 의 사실을 적었다 — 세는 것은 **실행이 아니라 문장 생성**이고,
`JdbcClient` 를 벗어나 raw JDBC 로 내려가면 「문장 하나에 SQL 둘」 우회가 성립한다. 오늘 그 전제는
코드로 참이며, **저장소가 `JdbcClient` 를 떠나는 커밋에서 이 KDoc 을 함께 고쳐야 한다**고 적었다.

### 1-3. 음성 대조 — **F-4 가 실증한 바로 그 변이**

일회용 worktree(`b9097f6` 고정). `WorkspaceService.rename` 이 `listOwned()` 로 먼저 소유를 확인하고,
내 자원일 때만 저장소를 부르게 했다.

| 게이트 | 종전(F-4 실측) | **이번 판** |
|---|---|---|
| `JdbcWorkspaceRepositoryTest` (구조 축) | 11/11 초록 | **13건 중 1건 red** — `없음=1 타인=1 내것=2` |
| `WorkspaceEndpointReachTest` (시간 축) | 22/22 초록 | **22/22 초록** (재현 — 시간 축은 여전히 못 잡는다) |

**과잉 결합 0.** 빨개진 것은 이름 변경 케이스 하나이고, 실패 메시지가 두 후보를 함께 지목한다
(*"소유 조건이 WHERE 를 떠났거나(저장소), 유스케이스가 선행 조회를 얹었다(서비스)"*).

**종전 장치가 왜 초록이었는지**는 측정이 아니라 코드 사실이다 — 이 변이는 `JdbcWorkspaceRepository` 를
**한 글자도 바꾸지 않으므로** 저장소 메서드를 직접 감싼 계수는 정의상 1 그대로다.

---

## 2. A-3/C-4 — `toString` 비대칭을 **종류로** 막는다

### 2-1. 고친 6타입 (cross ⓓ 표 전건)

| 타입 | 새던 값 | 지금 |
|---|---|---|
| `kr.easydoc.core.user.User` | `email=leak-probe@example.test` | `email=***` |
| `kr.easydoc.api.auth.UserResponse` | 〃 | 〃 |
| `core.easyread.SentenceIssue` | `sentence=<본문 조각>`, `word=<낱말>` | 길이 + 표식 (kind·reason 은 남긴다 — 우리가 만든 고정 문구) |
| `core.easyread.RepairPrompt` | `system`·`user` 프롬프트 전문 | 길이 |
| `application.conversion.Outcome.Body` | 변환 본문 전문 | 길이 |
| `application.conversion.Adoption` | 최종 본문 전문 | 길이 + `repaired` |

**직렬화는 그대로다.** `UserResponse.email` 은 계약 `required` 라 JSON 에 실려야 한다 — 두 축의 구분을
`AuthDtoLeakTest` 가 양쪽으로 단언한다(`WorkspaceDtoLeakTest` 와 같은 형태, privacy-gate 3a 해제 조건).

### 2-2. 열거 대신 탐지기 — `SensitiveToStringReachTest`

**판정을 「재정의가 있는가」로 하지 않았다.** `data class` 는 컴파일러가 언제나 `toString()` 을
**선언**하므로 반사로는 구분되지 않고, 형식만 갖춘 재정의는 값을 그대로 찍을 수 있다. 그래서
**표식을 민감 필드에 심어 실제로 인스턴스를 만들고 `toString()` 산출에 그 표식이 없음**을 단언한다.

| 축 | 내용 |
|---|---|
| 대상 | 테스트 런타임 클래스패스의 `kr.easydoc.**` **main** 클래스 전부(테스트·testFixtures 산출물 제외) |
| 민감 판정 | 파라미터 타입이 `String` 이고 ⑴ 이름이 토큰 목록을 품거나 ⑵ 클래스에 `@UserContent` 가 붙음 |
| 모르는 형태 | **끊는다** — `valueFor` 가 만들 줄 모르는 파라미터 타입이면 실패(조용히 건너뛰면 그 타입을 쓰는 DTO 가 통째로 검사 밖) |
| 범위 선언형 | main 클래스 수 하한 + 오늘 걸리는 **14타입 바닥**(`containsAll`) |

**이름 토큰**: `email · password · name · text · body · content · prompt · sentence · word · title · filename · phone · address`.
`title`·`filename`·`body`·`content` 는 **오늘 대상 0건**이고 Phase 4 문서 DTO 를 겨냥해 미리 뒀다 —
이 게이트가 실제로 쓰이는 첫 자리가 거기다.

**`token`·`secret`·`key` 는 넣지 않았다.** 그 범주는 `Secret`·`PasswordHash` 래퍼와 스캐너
`SECRET-LITERAL` 이 맡고, 넣으면 `tokenType`(값이 "Bearer")까지 끌려와 **범위가 근거를 넘는다.**

**`String` 이 아닌 파라미터는 대상이 아니다.** `Secret`·`MaskedText` 는 자기 `toString()` 에서 이미
가리고, 숫자·enum·UUID 는 콘텐츠를 담지 못한다. 이 규칙이 없으면 `StyleCheckResult.totalSentences: Int`
(이름 규약에 걸리지만 위험이 0)와 `AuthProperties.jwtSecret: Secret` 이 거짓 양성이 된다.

### 2-3. `@UserContent` — **넓히기만 하고 좁히지 못한다**

`RepairPrompt(system, user)` 는 두 필드 다 프롬프트 **전문**을 담는데 이름 어디에도 그 사실이 없다.
`user` 를 이름 토큰에 넣으면 규약이 근거보다 넓어지므로 **선언으로 적었다**(`core/privacy/UserContent.kt`).
붙이면 검사를 받고, 떼면 이름 규약이 여전히 판정한다 — **면제 조항으로는 쓸 수 없고**, KDoc 에 그 사실을
못박았다(은폐형으로 넓히지 않는다, `CLAUDE.md` 규칙 4).

### 2-4. 음성 대조 — **두 방향**

일회용 worktree. 실패 메시지가 두 건을 함께 지목했다.

| 변이 | 결과 |
|---|---|
| `User.toString()` 을 **고치기 전으로 되돌림** | `SensitiveToStringReachTest` **red** + `AuthDtoLeakTest` **red** |
| 목록 어디에도 없는 **새 DTO** `ProbeDocumentResponse(id, title)` 추가 (Phase 4 문서 DTO 모사, 재정의 없음) | `SensitiveToStringReachTest` **red** — `(민감 필드: [title])` |

**둘째가 이 조치의 요점이다.** 그 타입은 어떤 테스트에도, `KNOWN_SENSITIVE_TYPES` 바닥 목록에도 없는데
탐지기가 잡았다 — F-2·C-4 가 요구한 「종류를 잡는다」가 실행으로 성립한다.

---

## 3. C-5/A-10 — `ErrorResponse` union 의 fail-open

### 3-1. 두 층을 함께 고쳤다

- **파서** `ContractSpec.errorDetailUnionTypes()` — `filterIsInstance<Map<*, *>>()` → `mapIndexed` +
  `as? Map<*, *> ?: error(...)`. X-4 가 `requiredOf`·`pathParameters` 에 받은 처방과 같은 형태이고,
  **몇 번째 갈래인지와 실제 노드**를 메시지에 담는다.
- **소비자 둘** — 종전에는 기대 갈래를 코드에 복제(`containsExactlyInAnyOrder("string","array")`)하거나
  **개수만** 봤다(`hasSize(2)`). 이제 **계약에서 읽은 갈래 집합**과 **실제 관측 집합**을 그대로 맞댄다.
  관측값을 계약과 같은 어휘로 옮기는 자리는 `ContractSpec.observedDetailType` 하나이고, **모르는 모양이면
  끊는다.** `isInstanceOf(String::class.java)` 처럼 계약의 말과 JVM 타입의 대응을 테스트에 복제하지 않는다.

### 3-2. 음성 대조 — 세 갈래

일회용 worktree. 계약은 매번 `git checkout` 으로 되돌렸고 마지막에 `sha256 = 7877d263…` 이 본 트리와
같음을 확인했다(`cp` 미사용).

| 대조 | 결과 |
|---|---|
| **fail-open 재현** — 종전 파서 + 종전 소비자 + `oneOf` 에 스칼라 갈래 주입 | **33/33 초록** ← codex C-5 가 말한 방향이 실제로 열려 있었다 |
| 새 파서 + 같은 계약 | **정확히 2건 red**(S-11 · WC-10) — `ErrorResponse.detail 의 oneOf[2] 가 매핑이 아니다 … 주입된-스칼라-갈래` |
| 새 파서·새 소비자 + **유효한 세 번째 갈래**(`type: object`) 주입 | **정확히 2건 red** — `계약이 선언한 갈래 [string, array, object] 와 실제 관측 [string, array] 가 다르다` |

**과잉 결합 0** — 두 방향 모두 두 케이스만 빨개진다.
**첫 시도는 무효였다**(YAML 들여쓰기를 깨뜨려 스캐너가 아니라 파서가 먼저 죽었다 — 13+14건 red).
그 실행은 판정 근거로 쓰지 않고 다시 만들었다.

---

## 4. 401 타이밍 — **값싼 균일화를 적용했다**

### 4-1. 판단 — 계약이 묶은 것은 **토큰이 든 세 갈래**다

리더 지시는 *"위조·만료 경로에서도 `exists` 조회를 수행해 왕복 수를 맞추는 것이 계약
`failure_uniformity` 취지에 맞는지 판단 — 맞으면 적용"* 이었다. **맞는다고 판단했고, 범위를 좁혔다.**

계약 `x-auth.failure_uniformity` 는 **토큰 만료·위조·계정 삭제**를 같은 401·같은 메시지로 묶고
바로 다음 문장에서 *"응답 시간으로도 새지 않게 한다"* 를 적는다. **무헤더는 그 열거에 없고, 계약이
아예 다른 문구를 준다**(`components/responses/Unauthorized` 의 `no_header` = "인증이 필요합니다" ↔
`invalid_token` = "이메일 또는 비밀번호가 올바르지 않습니다"). 즉 **무헤더는 바이트 축에서 이미
구분되므로 시간을 맞춰도 얻을 것이 없다.** 반대로 맞추려면 인증 헤더 없는 트래픽 전부에 DB 부하를
얹게 된다 — 균일화의 비용이 이익을 넘는 유일한 갈래다.

그래서 균일화 대상은 **`AuthService.authenticate` 에 도달하는 갈래**로 한정했다(무헤더는
인터셉터가 그 앞에서 끊는다).

### 4-2. 무엇을 했나

`accessTokens.verify` 가 `InvalidCredentialsException` 으로 끊는 갈래에서도 `users.exists` 왕복
**하나**를 돈다. 로그인 경로가 계정 부재에 더미 해시 비용을 치르는 것과 **같은 형태**이고 성격도 같다 —
결과를 쓰지 않으며 안전성은 제어 흐름이 진다.

- **조회 인자는 nil UUID** — 계정 식별자는 `UUID.randomUUID()`(버전 4)라 절대 충돌하지 않는다.
  검증 실패한 토큰의 `sub` 를 쓰지 않는 이유 둘: 서명이 확인되지 않은 입력을 질의 인자로 삼게 되고,
  **공격자가 고른 식별자의 존재를 물어보는 통로**가 열린다.
- **예외를 삼키지 않는다** — DB 가 죽으면 유효 토큰 경로와 실패 갈래가 **똑같이** 500 이 된다.
  조회 실패를 삼켜 401 로 바꾸면 「DB 장애 중에는 위조만 401」이라는 새 채널이 생긴다.

### 4-3. 실측 (일회용 worktree · `GET /workspaces` · 표본 각 101 · 교차 순서 고정 시드 · 워밍업 20라운드)

privacy-gate 기록 ① 과 같은 형태로 쟀다. 「삭제 계정」은 **서명이 유효한 존재하지 않는 `sub`** 로
대신했다 — `authenticate` 관점에서 완전히 같은 경로(verify 통과 → `exists` 거짓)다.

| 갈래 | **전(균일화 제거)** p50 | **후(적용)** p50 · 1회 | **후** p50 · 2회 |
|---|---|---|---|
| 삭제 계정 | **1.238** | 1.439 | 1.307 |
| 위조 서명 | **0.525** | 1.389 | 1.306 |
| 만료 토큰 | **0.536** | 1.414 | 1.315 |
| 무헤더 | 0.481 | 0.483 | 0.470 |
| **토큰 세 갈래 최대/최소** | **2.356** | **1.036** | **1.007** |
| (참고) 네 갈래 최대/최소 | 2.575 | 2.983 | 2.801 |

**읽을 점 둘.**
1. 계약이 묶은 세 갈래의 비가 **2.356 → 1.007~1.036** 으로 닫혔다. 저장소가 같은 성질에 쓰는 문턱
   **1.5** 아래이고, privacy-gate 가 리더에게 올린 정합 질문(1.5 선례 ↔ 2.177)이 이 축에서는 해소된다.
2. **네 갈래 비는 오히려 커졌다(2.575 → 2.8~3.0).** 이것을 숨기지 않는다 — 무헤더를 의도적으로 대상에서
   뺀 결과이고, 그 갈래는 **본문이 이미 다르므로** 시간이 추가로 알려 주는 것이 없다. 「네 갈래 균일」을
   요구하려면 계약 `failure_uniformity` 의 열거를 먼저 넓혀야 하고, 그것은 contract-keeper 의 소유다.

### 4-4. 회귀는 **시간이 아니라 구조**로 걸었다

같은 배치가 시간 축 게이트의 한계를 실측했다 — 소유 조건을 SQL 에서 빼낸 변이가 비 1.013~1.090 으로
문턱 1.5 를 전혀 건드리지 않았다(X-3ⓒ). 밀리초 응답에서 왕복 하나는 CI 부하에 따라 흔들리고,
**흔들리는 게이트는 곧 꺼진다.** 그 격차가 실제로 무엇인가는 잡음이 없는 정수 — **DB 왕복 수**다.

`AuthenticationWorkUniformityTest` 가 다섯 갈래(성공·삭제 계정·위조·만료·JWT 형식 아님)의
SQL 문 수가 전부 **1** 임을 단언한다. 둘째 케이스가 **결과는 그대로 실패**임을 함께 건다 —
비용을 맞추는 조회의 결과를 쓰기 시작하면 nil UUID 행이 우연히 생기는 날 위조 토큰이 통과한다.

**음성 대조:** 균일화를 걷어내면
`{유효 토큰(성공)=1, 삭제 계정=1, 위조 서명=0, 만료 토큰=0, JWT 형식 아님=0}` 로 **red**.
실패 메시지가 채널의 모양을 그대로 보여 준다.

**대조 도구의 결함 하나를 스스로 잡았다.** 첫 판의 「위조 토큰」은 base64url **마지막 글자**를 바꾸는
방식이었는데, HS256 서명 32바이트는 43글자로 인코딩되고 마지막 글자의 하위 2비트는 어느 바이트에도
실리지 않는다 — **디코딩 결과가 같아 서명이 여전히 유효했다.** 즉 「위조 토큰」이 위조가 아닌 채로
통과하고 있었다. 서명 **첫 바이트의 1비트**를 뒤집는 방식(`TestJwt.withBrokenSignature` 와 같은 형태)으로
고쳤고, KDoc 에 그 함정을 적었다.

---

## 5. F-3 / F-5 — 산출물 문면 정정

`03_kotlin-implementer_workspaces-fixes.md` 를 고쳤다. **초판을 지우지 않고 정정 블록을 함께 남겼다.**

| 자리 | 무엇이 틀렸나 | 어떻게 고쳤나 |
|---|---|---|
| §1-3 | 제목이 「시간 동형 — 유지됐다」인데 근거 수치(1.023·1.031·1.039·1.103)는 **소유권 404 타이밍 게이트**의 비였다. X-1 이 만든 축은 재지 않았다 | **축 ⓐ(잰 것) / 축 ⓑ(안 잰 것)** 로 갈라 적고, ⓑ 에 privacy-gate 기록 ① 실측 표(1.067 / 0.539 / 0.547 / 0.490, 최대/최소 **2.177**)를 넣었다. 「이 배치는 이 축을 재지 않았다」를 명시하고 §4 로 포워딩했다 |
| §3-1 | 「16건 전건 초록」·「5건 빨강」이 **범위 표기 없이** 적혀 fail-open 이 실제보다 작아 보이고(16 vs 691) 검출 범위도 좁아 보였다(5 vs 20) | 표를 **두 열**로 나눴다 — `WorkspaceContractTest` 기준 / 전체 스위트 기준(**691/691 초록**, **20건 red** = WorkspaceContractTest 5 + WorkspaceEndpointReachTest 14 + DeletedAccountTokenReachTest 1) |

---

## 6. 남긴 것 · 개선 후보 (코드에 넣지 않았다)

| # | 항목 | 왜 남겼나 |
|---|---|---|
| 1 | 표 5 의 잔존 fail-open 2자리 — `headerComponentsByName()` `:158·162·166` + `collectHeaderRefs` 의 `?: return@forEach` | cross 조치 7 의 마감이 「계약에 인라인 헤더·새 `oneOf` 갈래가 처음 생기는 커밋」이고, **오늘 계약에 인라인 헤더 선언이 0건**이다. 이 배치에 넣으면 마감이 없는 변경이 리뷰 표면을 늘린다. `requestFieldConstraint()` `:237` 은 A-10 판정대로 **해소 불요**(`error()` 가 곧바로 터진다) |
| 2 | 표 18 — TRACE 프레임워크 로거 3종(`Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl`) 이름 고정 | 마감이 「Phase 4 문서 본문 진입 전」. `toString()` 재정의로는 **원리상 막을 수 없는** 층이라(바인딩 파라미터·원시 요청 바이트) 이 배치의 처방과 성격이 다르다 |
| 3 | 표 2b — `/auth/me` 의 `users` 이중 조회 | 3관점이 「사실·성능」으로 합의. 균일화가 실패 갈래에 왕복을 하나 **더했으므로** 최적화 압력은 오히려 늘었다 — Phase 4 에서 `authenticate` 가 사용자를 실어 나르는 형태를 고르면 두 자리가 함께 닫힌다 |
| 4 | 네 갈래(무헤더 포함) 시간 균일 | §4-1 판단대로 **계약의 열거를 넘어선다.** 요구하려면 계약 `x-auth.failure_uniformity` 를 먼저 고쳐야 하고 그것은 contract-keeper 소유다 |
| 5 | `SensitiveToStringReachTest` 가 `data class` 만 본다 | 일반 클래스(`StoredUser` 등)는 개별 KDoc 규율이다. 넓히려면 「생성자를 반사로 부를 수 있는가」가 불확실해지고 fail-closed 지점이 늘어난다 — 근거가 생기면 그때 넓힌다 |
| 6 | 탐지기가 `.value` 를 직접 꺼내 로거에 넘기는 줄을 못 막는다 | 그쪽 절반은 스캐너 `LOG-BODY` 가 식별자 이름으로 잡는다. 한 칸 더 옮기지 않는다 |

---

## 7. 검사 결과

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 빌드·린트·전체 테스트 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **exit 0** · 81 태스크 전건 실행 · 경고 0 |
| 모듈별 테스트 건수 | (테스트 결과 XML 집계) | core 359 · application 44 · **infrastructure 115** · **api 178** · worker 3 = **699** (직전 691 → +8) · red 0 |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | **exit 0** — api·worker 둘 다 선언 종류 + compileClasspath 통과 |
| 개인정보 스캐너 (CI 동일 명령) | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **exit 0** — BLOCK 0 (privacy-gate 레인 `01d78a1` 이 포함된 HEAD 에서 확인) |
| Python 린트 | `uv run ruff check .` | **exit 0** |
| Python 타입 | `uv run mypy . .claude` | **exit 0** — 137 파일 |
| Python 테스트 | `uv run pytest` | **exit 0** — 1272 passed · 68 skipped · 5 deselected · 7 xfailed |
| 골든셋 | — | **대상 없음** — 프롬프트 문구·스타일 규칙·LLM 설정 무변경. `RepairPrompt`·`SentenceIssue` 는 `toString()` 만 더했고 값·생성 로직 무접촉 |

**+8 의 내역:** `JdbcWorkspaceRepositoryTest` 11→13(+2) · `SensitiveToStringReachTest`(+2) ·
`AuthDtoLeakTest`(+2) · `AuthenticationWorkUniformityTest`(+2).

**규칙 5(복원 절차) 준수.** 음성 대조는 전부 일회용 worktree(`/tmp/g24nc`, `b9097f6` 고정)에서 했고
본 트리에 쓰지 않았다. 복원은 `git checkout` + `git worktree remove` 이며 **`cp` 미사용**.
제거 전후로 본 트리의 `sha256(contracts/easy-doc-v1.yaml) = 7877d263a36d5fefdba0f86375ca3dabfc1d778b24d778cabb6ba52484977c4d`
동일 확인. 마감 시점 본 트리 추적 파일 수정 0건.

---

## 8. 이 배치가 하지 않은 것

- **계약을 읽기만 했다** — `contracts/easy-doc-v1.yaml` 변경 0. 음성 대조의 주입은 worktree 안에서만.
- **`.claude/**` 무접촉** — 스캐너는 privacy-gate 레인이 `01d78a1` 로 고쳤고 파일이 겹치지 않는다.
- **`00_progress.md` 무접촉** — 원장 갱신은 리더의 몫이다.
- **`app/**`·`frontend/**` 무접촉.**
- **스스로 통과를 선언하지 않는다** — 위 표는 실행 증거이고, 게이트 판정은 `migration-reviewer`·
  `codex-reviewer`·`privacy-gate` 와 리더의 몫이다. 특히 §2 의 탐지기와 §4 의 균일화는
  **보안 축 판정이 필요한 변경**이다.
