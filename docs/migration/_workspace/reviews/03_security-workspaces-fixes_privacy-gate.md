# 게이트 23 — `03_security-workspaces-fixes` 보안 축 재감사 (privacy-gate)

- **대상**: `7205d37..e9502a6` (11 커밋). 핵심 `fa87aed`(X-1) · `ea36330`(스캐너) · `30cc405`(A-2·A-3·A-4) · `b37012c`(X-3) · `3466f6d`(max_connections)
- **정본**: `migration-safety-gate` I-3·I-5·I-6·I-9 · 계약 `x-auth.failure_uniformity`(`:299-302`) · `CLAUDE.md` 규칙 4
- **판정 요약**: **통과 5 · 해제 1(R-3) · 수정 필요 1(Minor) · 기록 3 · 확인 불가 0 · 신규 차단 0.** 미해제 차단 없음.

> 모든 판정은 **내가 직접 작성해 돌린 탐침**의 결과다. 구현자 테스트(`DeletedAccountTokenReachTest`
> 등)의 실측치를 인용해 닫은 항목은 없다. 탐침은 일회용 git worktree 2개(`pg23`·`pg23n`)에서
> 돌렸고 감사 종료 시 `git worktree remove --force` 로 제거했다. **Kotlin 코드 수정 0 · 커밋 0 ·
> `00_progress.md` 무접촉 · 다른 리뷰어 산출물 무접촉.** 변조 복원은 `cp` 가 아니라
> `git checkout --` 로 했고 sha256 을 본 저장소와 대조해 일치를 확인했다(§7).

---

## 0. 기준선 — 구현자 테스트 전량 강제 재실행

```
./gradlew test --rerun-tasks        → BUILD SUCCESSFUL (27s)
core 359 / application 44 / infrastructure 111 / api 174 / worker 3 = 691 tests
failures 0 · errors 0 · skipped 0
"too many clients" 적중 0건
```

**기준선이지 판정 근거가 아니다.** 아래는 전부 별도 탐침이다.

---

## 1. R-3 해제 여부 (X-1) — **해제**

`PgAudit23HttpProbe.p1` — 원시 소켓으로 상태 줄·헤더·본문 전체 바이트 확보. 계정 행은
`DELETE FROM users` 로 지우고 **대기 없이 즉시** 재측정했다(캐시 0 확인).

### 1-1. 다섯 경로가 하나로 모인다

| 경로 | 살아 있을 때 | 삭제 직후 |
|---|---|---|
| `GET /auth/me` | 200 | **401** |
| `GET /workspaces` | 200 | **401** (종전 200 `{"items":[]}`) |
| `POST /workspaces` | 201 | **401** (종전 500) |
| `PATCH /workspaces/{id}` | 200 | **401** (종전 404) |
| `DELETE /workspaces/{id}` | 204 | **401** (종전 404) |

살아 있을 때의 대조가 있어야 「무엇을 보내도 401 인 구현」과 구분된다 — 다섯이 각각
200·200·201·200·204 로 통했다.

**구분 불가 실측**

```
서로 다른 본문 종류 = 1   {"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}
서로 다른 헤더 이름 집합 = 1
  [cache-control, connection, content-type, transfer-encoding, vary,
   www-authenticate, x-content-type-options]   (date 제외)
```

**위조 토큰 401 과의 바이트 대조**: 본문 동일 `true` · 헤더 이름 집합 동일 `true`.
만료 토큰(계정 생존)도 같은 본문이다. 무헤더 갈래만 `{"detail":"인증이 필요합니다"}` 로
갈리는데, 이는 계약 `Unauthorized` 가 선언한 **두 갈래**이며 종전 판정과 같다.

### 1-2. 확인이 새는지 — 코드가 아니라 나가는 SQL 로 봤다

`PgAudit23QueryProbe.q1` — JdbcTemplate DEBUG 로 나가는 SQL 문면을 잡았다(계측을 위해
DataSource 를 바꾸지 않았다 — 바꾸면 재는 대상이 달라진다).

| 요청 | `users` 질의 수 | 문면 |
|---|---|---|
| `GET /workspaces` | **1** | `SELECT 1 FROM users WHERE id = ?` |
| `PATCH /workspaces/{id}` | **1** | `SELECT 1 FROM users WHERE id = ?` |
| `GET /auth/me` | **2** | `SELECT 1 …` + `SELECT id, email, created_at FROM users WHERE id = ?` |

- **`exists` 는 이메일을 적재하지 않는다** — 문면이 `SELECT 1` 이고, 그 질의에 `email`·
  `password` 문자열이 없음을 실행으로 확인했다(코드 읽기가 아니라 나간 SQL 로).
- **`/auth/me` 는 이중 조회다** → 아래 기록 ②.

### 1-3. 「유일한 목」이 구조로 성립하는가

```
accessTokens.verify  생산 코드 호출 지점 = 1   (AuthService.kt:147, authenticate 안)
authenticate         생산 코드 호출자   = 1   (AuthenticationInterceptor.kt:76)
```

서명 검증을 하면서 존재 확인을 건너뛸 수 있는 경로가 **타입·호출 구조상 없다.**
`AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` 는 이 범위에서 변경되지 않았으므로
게이트 22 의 N4 음성 대조 판정(빈 선언에서 통과하지 않는다)을 근거와 함께 유지하고
재감사하지 않는다.

`authenticate` 에 광범위 `catch` 가 없다 — DB 장애는 401 이 아니라 500 으로 나간다.
방향이 옳다(사용자를 오해 소지 있는 401 로 잠그지도, 열어 주지도 않는다).

### 1-4. R-3 판정과 「계정 삭제 출하 단위」 조건의 재기재

**R-3 해제.** 게이트 22 가 건 조건은 *"계정 삭제 기능 커밋과 같은 단위에서 해소"* 였고,
기능보다 **먼저** 해소됐으므로 조건은 선행 충족이다. 다만 조건 문장을 지우지 않고
**다음 형태로 갱신해 남긴다** — 지금 닫힌 것은 「토큰 수명 동안 API 를 쓴다」 한 갈래뿐이다.

> **잔여 조건(계정 삭제 기능 출하 단위에서 다시 볼 것).** ⑴ `users` 삭제가
> `fk_workspaces_user_id_users`(ON DELETE CASCADE)를 타고 작업 공간을 지운다 — Phase 4 가
> `documents`·`conversions` 를 붙이면 그 연쇄가 **암호문·보존 파기 정책과 맞는지**를 그
> 커밋에서 확인한다(I-4·I-11). ⑵ 토큰 폐기 수단은 여전히 「사용자 행이 사라졌는가」
> 하나뿐이다 — 비밀번호 변경·로그아웃에는 폐기 경로가 없고, 그것은 이 감사의 범위 밖이나
> 계정 삭제 기능이 들어올 때 함께 결정되어야 할 자리다.

---

## 2. 스캐너 OWNERSHIP-403 정밀화 재검 (`ea36330`) — **통과**

### 2-1. 처방이 판정대로인가 — 그대로다

`OWNERSHIP_403_INERT` 가 **소비형 대안**으로 패턴 앞에 놓였고, `refine` 은 자기 패턴이
직접 소비한 캡처 그룹(`inert`)의 참여 여부만 본다. 처방에 없던 것이 들어오지 않았다:
`hardened` 는 `None`, `sanctioned` 는 비어 있음, 심각도 `BLOCK` 유지, 경로 면제 0.
부수 수정 `Rule.refine_reason` 은 필드 **끝에** 추가됐다(위치 인자 밀림 방지 주석 준수).

### 2-2. 음성 대조 — 일회용 worktree 스캔 루트에 주입 후 CI와 동일 명령

주입 파일은 신규 생성 후 삭제(기존 파일 무수정). 기준선·복원 후 모두 exit 0 으로 잔여 0 확인.

| 대조 | 주입 형태 | exit | 검출 |
|---|---|---|---|
| 0 기준선 | — | **0** | — |
| A | `ResponseEntity.status(403)` | **1** | `[BLOCK] OWNERSHIP-403` ×1 |
| B | `status(HttpStatus.FORBIDDEN)` | **1** | `[BLOCK] OWNERSHIP-403` ×1 |
| C | `const val FORBIDDEN = 403` **+ 사용처** | **1** | `[BLOCK] OWNERSHIP-403` ×1 |
| **F** | **양성 단언 `isEqualTo(FORBIDDEN)`** | **1** | `[BLOCK] OWNERSHIP-403` ×1 |
| **G** | **`@ApiResponse(responseCode="403")`** | **1** | `[BLOCK] OWNERSHIP-403` ×1 |
| H | 집행형(`isNotEqualTo` + 자체 줄 상수 선언) | **0** | 제외 6→**8건** |
| D | 난수꼴 `jwtSecret` 리터럴 | **1** | `[BLOCK] SECRET-LITERAL` ×1 |
| E | 상수 이름만 `SIGNUP_PASSWORD` 로 되돌림 | **1** | `[BLOCK] SECRET-LITERAL` ×1 |
| 복원 후 | — | **0** | — |

- **F·G 는 내가 추가한 대조다.** 산출물이 *기각했다*고 적은 두 갈래(양성 단언·403 응답
  선언)가 **실제로 남아 있는지**를 잰다. 둘 다 잡힌다 — 정밀화가 근거를 넘지 않았다.
- **H 는 무손실의 반대쪽**이다. 저장소 실형태(자체 줄 상수 + 부호 반전 단언)에서만
  제외되고 exit 0 이 유지된다. 같은 내용을 **한 줄로 뭉치면 잡힌다**(내 첫 주입이 그랬다) —
  즉 정밀화는 모르는 형태에서 **닫히는 쪽**이다.
- **D·E** 는 SECRET-LITERAL **탐지를 건드리지 않았음**의 증명이다. 규칙은 그대로이고
  개명이 해소의 원인이다(E 가 그것을 보인다).

### 2-3. 은폐 장치·예산·CI 도달

```
UNMARKABLE_RULES = {LLM-RAW-INPUT, OWNERSHIP-403, PLAINTEXT-PERSIST,
                    SECRET-LITERAL, LLM-VENDOR-SDK, XML-DTD}   ← 6종 그대로
MARKER_BUDGET = 7 (변경 없음) · 실사용 7/7 (변경 없음)
uv run pytest tests/test_privacy_scanner.py → 112 passed, 7 xfailed
CI 스텝 동일 명령 (HEAD, 인자 없음)         → exit 0, [BLOCK] 0건
```

마커 0건 신규 · 경로 면제 0 · 심각도 강등 0 · 예산 인상 0. **은폐형 없음.**

---

## 3. A-3 이름 마스킹 — **통과** / **수정 필요 1건(Minor)**

### 3-1. `toString` 은 가리고 직렬화는 가리지 않는다 — 실측

`PgAudit23LogProbe.l1`. 표식 `MARKER933311-7654321` 은 합성 값이다.

| 대상 | `toString()` | 표식 포함 |
|---|---|---|
| `Workspace` | `Workspace(id=…, name=***, createdAt=…)` | **false** |
| `WorkspaceResponse` | `WorkspaceResponse(id=…, name=***, createdAt=…)` | **false** |
| `WorkspaceNameRequest` | `WorkspaceNameRequest(name=***)` | **false** |
| `WorkspaceListItemResponse` | `…(id=…, name=***, createdAt=…, documentCount=3)` | **false** |
| 문자열 보간 `"$workspace"` | 위와 동일 | **false** |
| `WorkspaceListing` (중첩) | `WorkspaceListing(workspace=Workspace(…name=***…), documentCount=2)` | **false** |

**직렬화는 정상이다** — 가리는 것이 응답까지 먹으면 기능이 깨진 것이다.

```
{"id":"…","name":"MARKER933311-7654321","created_at":"…"}
{"id":"…","name":"MARKER933311-7654321","created_at":"…","document_count":3}
```

### 3-2. 실기동 로그 캡처 — 제품 기본 레벨

`PgAudit23LogProbe.l2`. 전 수명주기(가입·로그인·생성·목록·이름변경) + 실패 6경로
(422 누락·422 타입·422 깨진 JSON·404·409 마지막 하나·401 위조·로그인 실패)를 태웠다.

| 후보 | 적중 |
|---|---|
| 작업 공간 이름 표식 | **0** |
| 이메일 | **0** |
| 평문 비밀번호 | **0** |
| PHC 접두사 `$argon2id$v=` | **0** |
| 액세스 토큰 앞 24자 | **0** |
| **양성 대조 표식** | **1 (캡처 살아 있음)** |
| 캡처 이벤트 수 | 1 (= 양성 대조 그 자체) · `kr.easydoc` 로거 이벤트 **0** |

게이트 22 와 같은 결론이며 강도도 같다 — *"찍힌 것 중에 없다"* 가 아니라 **"이 경로가
아무것도 찍지 않는다"** 이다.

### 3-3. 수정 필요(Minor) — **같은 규율이 이메일에는 닿지 않았다**

`PgAudit23LogProbe.l1b` 실측.

| 타입 | `toString()` | 이메일 포함 |
|---|---|---|
| `kr.easydoc.core.user.User` | `User(id=…, email=leak-probe@example.test, createdAt=…)` | **true** |
| `kr.easydoc.api.auth.UserResponse` | `UserResponse(id=…, email=leak-probe@example.test)` | **true** |
| `StoredUser` | `StoredUser(userId=…)` | false (마스킹됨) |
| `SignupRequest`·`LoginRequest`·`TokenResponse` | `(...)`·토큰 제외 | false (마스킹됨) |

**왜 위반이 아니라 「수정 필요」인가**: 오늘 도달 0 이다(§3-2 — 이 경로에 로거가 없다).
I-3 는 현재 지켜진다.

**왜 그래도 적는가**: A-3 이 이름을 막은 근거 문장이 그대로 이 자리에 적용된다 —
*"막는 비용이 한 줄인데 새는 순간은 **로깅이 처음 들어오는 커밋**이고 그때 아무도 이
클래스를 다시 보지 않기 때문"*. 그리고 A-3 자신이 고친 결함의 이름이 **비대칭**이었다
(`Secret`·`PasswordHash`·`StoredUser`·`TokenResponse`·`SignupRequest` 는 마스킹하는데
`Workspace.name` 만 안 했다). 같은 배치에서 `User.email`·`UserResponse.email` 이 남았으므로
비대칭은 이동했을 뿐 사라지지 않았다. `/auth/me` 는 **요청마다** 이메일을 `User` 객체로
힙에 올린다(§1-2 실측) — 그 객체가 마스킹 없는 `toString()` 을 가진 자리다.

- **해제 조건**: `User`·`UserResponse` 의 `toString()` 이 이메일 대신 표식을 내고,
  JSON 직렬화는 그대로 이메일을 내는 것(§3-1 과 같은 형태의 회귀 1건 동반).
- **수신자**: `kotlin-implementer`. **§5 Phase 7 즉시 중단 기준 해당: 아니오.**

---

## 4. X-3 탐지형 — **통과** (변이 재현으로 확인)

### 4-1. 변이 — 소유 조건을 SQL `WHERE` 에서 빼고 Kotlin 에서 비교

일회용 worktree `pg23n` 에서 `JdbcWorkspaceRepository.rename` 을 「`SELECT user_id` 로 읽고
같으면 `UPDATE … WHERE id`」로 바꿨다.

| 축 | 테스트 | 결과 |
|---|---|---|
| **구조** | `JdbcWorkspaceRepositoryTest` 「이름 변경이 …같은 수의 SQL 문을 낸다」 | **FAILED** |
| 시간 | `WorkspaceEndpointReachTest` (문턱 1.5, 전 16건) | **전건 통과** (failure 0) |

구조 축 실패 문면:

```
소유 결과에 따라 SQL 문 수가 갈린다 — 없음=1 타인=1 내것=2.
소유 조건이 WHERE 를 떠났다는 신호다
```

**왕복 1 단언이 소유 조건의 SQL 내장을 강제한다**는 주장이 실행으로 확인됐다. 같은 변이에서
API 계층 테스트가 **전부 초록**이라는 사실이 이 장치의 필요성을 동시에 증명한다 — 구조 축이
없으면 이 변이는 어느 게이트에도 걸리지 않는다.

### 4-2. 시간 축의 잔여 정직 등재 — 확인함

`WorkspaceEndpointReachTest` KDoc 이 *"이 게이트는 「소유 조건이 SQL 을 떠났는가」를 재지
않는다"* 를 명시하고 구조 축으로 포워딩한다. 문턱 인하(2.0→1.5)의 근거도 실측 3건과 함께
적혀 있다. **은폐가 아니라 등재**이고, 내 변이 재현이 그 등재가 정확함을 확인했다.

### 4-3. 기록 — 구조 축의 도달은 `rename` 한 오퍼레이션이다

단언은 `RENAME_STATEMENTS = 1` 하나이며 `delete`·`lockForDeletion`·`listOwned` 는 덮지 않는다.
**오늘 위반은 없다** — 코드 확인: `DELETE … WHERE id = :id AND user_id = :ownerId`,
`SELECT id FROM workspaces WHERE user_id = :ownerId … FOR UPDATE` 로 셋 다 소유 조건이 SQL 안에
있다. 다만 KDoc 이 「같은 DB 왕복 구조」라는 일반형으로 선언하는 데 비해 강제 도달은 1건이다.
**범위는 근거를 넘지 않는다**(`CLAUDE.md` 규칙 4) — 선언을 좁히거나 도달을 넓히는 쪽 중
하나를 Phase 4(문서가 붙어 참조가 늘 때)에서 정하도록 남긴다. 개선 권고이며 차단 아님.

`A-2` 의 「참조 FK 가 정확히 하나」 단언(`pg_constraint` 직접 조회)은 같은 자리를 지키는
짝이며, 마이그레이션 파일 문자열이 아니라 **Flyway 가 실제로 적용한 것**을 묻는다 — 옳은 형태다.

---

## 5. 로그·헤더 (I-3·I-6) — **통과**

### 5-1. 스캐너 변경분 범위

```
--changed --base 7205d37   검사 파일 24개 → exit 0
[BLOCK] 0건 · [WARN] CACHE-HEADER 4건(AuthController 헤더 상수, 정상)
2차 판정 제외: OWNERSHIP-403 6건 · SECRET-LITERAL 1건 · XML-DTD 2건
```

게이트 22 에서 오탐 7건으로 판정했던 `WorkspaceEndpointReachTest` 적중이 **정밀화로 사라졌고**,
그 사라짐이 무손실임은 §2-2 의 F·G·H 가 잰다.

### 5-2. 401 변종 12종의 사적 응답 헤더 — 전건 부착

`PgAudit23HttpProbe.p3`. 전건 `cache-control=[no-store]` · `x-content-type-options=[nosniff]`
(**값 목록 길이 1** — 이중 부착 없음).

```
/auth/me      무헤더 · 위조 · 삭제계정
/workspaces   무헤더 · 위조 · 삭제계정 · 비JWT 문자열
POST·PATCH·DELETE /workspaces  삭제계정
PATCH /workspaces/not-a-uuid   삭제계정 (비UUID 경로 + 무효 토큰 → 401, 422 아님)
POST /auth/login 실패
```

마지막 항목이 X-A3(인증이 입력 검증보다 먼저)을 **삭제 계정 갈래에서도** 재확인한다.

---

## 6. 기록 (차단 아님)

### 기록 ① — 401 네 갈래의 **시간**이 갈린다 (리더 판단 요청)

`PgAudit23HttpProbe.p2`. `GET /workspaces` 한 자리, 표본 각 101, 교차 순서(고정 시드
20260819), 워밍업 20라운드 선행.

| 갈래 | p50 (ms) | p90 (ms) |
|---|---|---|
| **삭제 계정 401** | **1.067** | 1.706 |
| 위조 서명 401 | 0.539 | 0.669 |
| 만료 토큰 401 | 0.547 | 0.763 |
| 무헤더 401 | 0.490 | 0.631 |
| (참고) 유효 200 | 1.680 | 2.408 |

```
삭제/위조 = 1.980   삭제/만료 = 1.950   삭제/무헤더 = 2.177   위조/만료 = 0.985
401 네 갈래 p50 최대/최소 = 2.177
```

**양쪽 해석을 병기한다.**

- *문제 쪽*: 계약 `x-auth.failure_uniformity` 는 *"토큰 만료·위조·계정 삭제를 모두 같은 401과
  같은 메시지"* 로 묶고 **바로 다음 문장에서 "응답 시간으로도 새지 않게 한다"** 를 적는다.
  바이트 채널은 X-1 이 닫았지만 시간 채널은 2.0~2.2배로 열려 있고, 「계정 삭제」와 「토큰 만료」가
  그 비로 갈린다. 저장소가 같은 성질에 쓰는 문턱은 **1.5** 다(로그인 B-1, 소유권 X-3ⓑ).
- *문제 아님 쪽*: `failure_uniformity` 의 시간 문장은 **로그인 경로**의 더미 해시를 가리키며
  (*"사용자가 없을 때도 더미 해시로 같은 검증 비용"*), 인증 경계에는 그 요구가 붙은 적이 없다.
  격차의 정체는 「서명·클레임이 유효해 DB 왕복 1회를 더 돈다」이고, 이를 없애려면 **서명 실패에도
  더미 DB 왕복을 돌아야** 한다 — 인증 실패 트래픽에 DB 부하를 그대로 얹는 절충이다.
  악용에는 **대상 계정의 유효 서명 토큰 보유**가 선행돼야 하고, 그때 얻는 정보는
  「이 계정이 그 사이 지워졌는가」뿐이다.

**X-1 이 만든 채널이 아니다.** 고치기 전에도 삭제 계정 요청은 200(목록 질의)·500(FK 위반)으로
**DB 왕복을 돌았으므로** 같은 시간 격차가 있었고, 그 위에 상태 코드 채널까지 있었다. X-1 은
엄격한 개선이다. 그래서 **차단이 아니라 기록**이고, 문턱 1.5 선례와의 정합만 리더 판단으로 넘긴다.

### 기록 ② — `/auth/me` 가 `users` 를 두 번 읽는다

`SELECT 1 …`(인증 경계) + `SELECT id, email, created_at …`(`readUser`). §1-2 실측.

보안 결함이 아니다 — 두 번째 질의는 `/auth/me` 가 **응답에 이메일을 실어야 해서** 필요하고,
첫 번째는 인증 경계의 단일 목을 유지하는 대가다. 다만 그 경로에서만 PK 조회 1회가 순수 잉여이고,
**이메일을 담은 `User` 객체가 요청마다 생기는 자리**라 §3-3 과 붙어 있다. 비용은 실측 p50 기준
약 0.5ms(1.067 − 0.539). `AuthService.readUser` 의 `null` 갈래를 남긴 판단은 옳다 —
결합을 타입에서 지우지 않는다.

### 기록 ③ — `toString` 방어가 닿지 않는 층: 로그 레벨

`PgAudit23LogProbe` 를 같은 시나리오로 세 레벨에서 돌렸다.

| 루트 레벨 | 이벤트 | 이름 | 이메일 | 평문 비번 | PHC | 토큰 | 유출 로거 |
|---|---|---|---|---|---|---|---|
| **기본(제품 `application.yml`)** | 1 | 0 | 0 | 0 | 0 | 0 | 없음 |
| 강제 DEBUG | 91 | 0 | 0 | 0 | 0 | 0 | 없음 |
| 강제 TRACE | 739 | **10** | **9** | **2** | **2** | **9** | `org.apache.coyote.http11.Http11InputBuffer@DEBUG` · `org.springframework.jdbc.core.StatementCreatorUtils@TRACE` · `org.postgresql.core.v3.QueryExecutorImpl@TRACE` |

세 레벨 전부 `kr.easydoc` 로거 이벤트 **0**. 즉 유출은 전부 프레임워크 층이고
**`toString()` 재정의로는 원리상 막을 수 없다**(바인딩 파라미터·원시 요청 바이트).

`api/application.yml` 은 `root: INFO`·`kr.easydoc: INFO`·`org.springframework.web: INFO` 를
고정하고 주석에 *"요청 본문을 찍는 로거는 절대 DEBUG 로 내리지 않는다"* 라고 적었다.
**그런데 실제로 요청 바이트를 찍는 로거는 `org.apache.coyote.http11.Http11InputBuffer` 이고,
명시 고정 세 줄 중 어느 것도 그것을 가리키지 않는다** — 지금은 `root: INFO` 상속으로 조용할
뿐이다. 선언(*"요청 본문을 찍는 로거"*)과 강제 도달(`org.springframework.web` 하나)이 갈리는
자리이며 `CLAUDE.md` 규칙 4 가 말하는 형태다.

- 지금 위반은 아니다(기본·DEBUG 모두 0건). **DEBUG 에서도 0인 이유**는 Tomcat 이 그 줄을
  `isTraceEnabled` 로 감싸 두었기 때문이고, 그것은 우리 장치가 아니라 Tomcat 의 사정이다.
- 개선 권고: 세 로거를 이름으로 고정하거나, 문서 본문이 들어오는 Phase 4 전에 이 표를 회귀로
  고정한다. 게이트 22 의 「로그 회귀 검출력」 개선 권고와 같은 자리이며 **구체 대상 3건이
  이번에 실측으로 확보됐다.**

---

## 7. 부수 확인

- **`max_connections=400`(`3466f6d`) — 통과.** 실행 중 컨테이너에 직접 물어 `400` 확인
  (`SHOW max_connections`). 전량 재실행 691건에서 `too many clients` **0건**. 컨테이너 설정
  변경이라 제품 런타임에 닿지 않는다.
- **`PasswordHashingBackpressureReachTest` 변경은 강화다** — 「본문 distinct 1」만 보던
  단언에 **두 계정 집단이 과부하 부분집합에 다 들어 있는지**를 선행 조건으로 붙였다.
  종전 형태는 한 집단이 전부 500, 다른 집단이 전부 401 이어도 초록이었고 그 분포 자체가
  열거 신호다. 약화 아님.
- **계약 변경(`0fe654c`)은 보안 불변식에 닿지 않는다** — D-2 삭제 거절 순서와 M-405b 수치
  정정뿐이고, `x-auth.failure_uniformity` 문면은 이 범위에서 바뀌지 않았다. 즉 X-1 은 조항을
  고쳐 맞춘 것이 아니라 **구현을 기존 조항에 맞춘 것**이다.

---

## 8. 판정 표

| # | 항목 | 판정 | 근거 |
|---|---|---|---|
| 1 | R-3 삭제 계정 유효 토큰 (I-5·I-9) | **해제** | 5경로 전건 401 · 본문/헤더 집합 각 1종 · 위조 401 과 바이트 동일 · 삭제 직후 즉시 |
| 1a | 인증 경계의 유일성 | **통과** | `verify` 호출 1 · `authenticate` 호출자 1 (구조) |
| 1b | `exists` 가 이메일을 안 읽음 | **통과** | 나간 SQL 문면 `SELECT 1 FROM users WHERE id = ?` |
| 2 | 스캐너 OWNERSHIP-403 정밀화 | **통과** | 음성 대조 8종(F·G 신규) · 회귀 112+7 · CI exit 0 · 은폐 0 |
| 3 | A-3 이름 마스킹 | **통과** | 4타입 전건 `***` · 직렬화 정상 · 기본 레벨 캡처 유출 0(양성 대조 1) |
| 3a | **같은 규율의 이메일 미도달** | **수정 필요(Minor)** | `User`·`UserResponse` `toString()` 이 이메일 평문 — 도달 0 |
| 4 | X-3 구조 축 탐지형 | **통과** | 변이 주입 시 구조 축 FAIL(1/1/2) · 시간 축 전건 통과(등재대로) |
| 4a | 구조 축 도달 = `rename` 1건 | **개선 권고** | 선언은 일반형, 강제는 1 오퍼레이션 |
| 5 | 사적 응답 헤더 (I-6) | **통과** | 401 변종 12종 전건 `no-store`/`nosniff`, 값 목록 길이 1 |
| 5a | 스캐너 변경분 범위 | **통과** | `--changed --base 7205d37` exit 0, BLOCK 0 |
| 6 | 401 네 갈래 시간 격차 | **기록 ①** | 최대/최소 2.177 — 리더 판단(1.5 선례와의 정합) |
| 7 | `/auth/me` 이중 조회 | **기록 ②** | `users` 질의 2회, 둘째가 이메일 적재 |
| 8 | 로그 레벨 층의 유출 표면 | **기록 ③** | TRACE 에서 프레임워크 로거 3종 — 명시 고정이 미도달 |

**확인 불가: 없음. 신규 차단: 없음. 미해제 차단: 없음. 차단 사유서 없음.**

### §5 Phase 7 즉시 중단 기준 대조

| 기준 | 해당 |
|---|---|
| 다른 사용자 데이터 노출 또는 404 소유권 규칙 위반 | **아니오** (항목 1·4) |
| 마스킹 전 본문이 LLM 이나 로그로 전송됨 | **아니오** (항목 3. 이 배치에 LLM 경로 없음) |
| AEAD round-trip 실패 / 변조 통과 | **범위 밖** (Phase 4) |
| 중복 LLM 호출 | **범위 밖** (Phase 5) |

---

## 9. 감사 자체의 격리

- 탐침 3종(`PgAudit23HttpProbe`·`PgAudit23QueryProbe`·`PgAudit23LogProbe`)과 변이는 전부
  일회용 worktree `pg23`·`pg23n` 에서만 존재했다.
- 변조 복원은 `git checkout --` 로 했고 `JdbcWorkspaceRepository.kt` 의 sha256 을 본 저장소와
  대조해 **일치** 확인 (`6b4e70bc…c323ad`).
- 스캐너 음성 대조의 주입 파일은 **신규 생성 후 삭제**했고(기존 파일 무수정), 기준선·복원 후
  exit 0 으로 잔여 0 을 확인했다.
- `git worktree remove --force` 로 두 worktree 를 제거했다. **본 저장소에 대한 쓰기는 이
  문서 하나뿐이다** — Kotlin 수정 0 · 커밋 0 · `00_progress.md` 무접촉 · 다른 리뷰어 산출물 무접촉.
- 이 문서에 실제 사용자 데이터·평문 본문·키·암호문을 옮겨 적지 않았다. 등장하는 UUID·이메일·
  이름·표식은 전부 탐침이 그 자리에서 만든 합성 값이다.
