# 게이트 22 (`03_workspaces`) — Claude 독립 리뷰 (1회차)

**작성:** migration-reviewer / **일자:** 2026-08-19
**회차:** **1차 — 독립 리뷰.** codex 산출물을 입력으로 받지 않았고 **요구하지도 않았다**(병렬 1단계라
이 시점에 존재하지 않는 것이 정상이다). 교차 대조표는 이 문서에 없다 — 2차 재호출의 `..._cross.md` 몫이다.
`privacy-gate` 산출물도 읽지 않았다(리더 지시). 보안 축 판정이 갈리면 `privacy-gate` 가 우선한다.
**대상 범위:** `d04ad98..cc7268c` (21커밋) — ⓐ `auth-fixes2` 배치 · ⓑ 계약 G1 정정 + 명세 + escalate 표 ·
ⓒ `workspaces` 단위
**참조:** 계획 §2.2 · §2.3 · §3.1 · §3.2 · §4.4 · §5 Phase 3·7 · §6 / `migration-safety-gate` I-1·I-3·I-4·I-5·I-8·I-9 /
`CLAUDE.md` 「선언한 범위와 실제 도달을 대조한다」 / 원장 「Phase 3 auth 단위」 §6

> **범위 고정 메모.** 리뷰 중 본 트리 HEAD 가 `2c4a44f`(contract-keeper 2단계 검증)로 진행했다.
> 리더가 지정한 범위는 `d04ad98..cc7268c` 이므로 그 뒤 커밋은 **보지 않았고 판정에도 넣지 않는다.**

---

## 0. 요약 — 심각도별 건수

| 심각도 | 건수 | 항목 |
|---|---|---|
| **차단** | **1** (전부 **② 장치**) | B-1 |
| **수정 필요** | **3** | F-1 · F-2 · F-3 |
| **권고** | **7** | A-1 ~ A-7 |
| **판정 필요** | **4** | J-1 ~ J-4 |
| 합계 | 15 | |

**① 사건 차단 0건.** §5 Phase 7 즉시 중단 기준(AEAD·타 사용자 노출·404 위반·마스킹 전 전송·중복
LLM 호출·작업 유실·추출 누락·2회 호출 위반)에 해당하는 경로를 이 범위에서 찾지 못했다. 제품 코드
두 단위는 **내가 이 하네스에서 본 것 중 가장 튼튼한 축에 든다** — 아래 §2·§5 의 독립 재현이 그 근거다.

**차단 1건은 제품 코드가 아니라 게이트 자신에 있다.** 그리고 그것은 리더가 이번 회차에 끊으라고
지시한 바로 그 축(CI 3회차 연속 0관점)에서 나왔다.

---

## 1. 도달 범위 점검 — **CI 0관점을 끊었다** (다섯 축을 가로지르는 필수 구획)

원장 「Phase 3 auth 단위」 §6 사실 ⑨: *"CI 원격 캐시 거동은 이번에도 0관점이다 … **다음 push 의
CI 실행이 첫 관측**이 된다."* 그 첫 관측을 여기서 했다.

### B-1 [**차단 · ② 장치**] CI `quality` 레인이 데이터 보호 불변식 스캔에서 red 이고, 그 한 스텝이 **레인의 나머지 8스텝을 통째로 건너뛰게 만든다**

**관측 (실행 근거)**

```
gh run view 32211120665            # headSha 6fe4357 (원격 브랜치 HEAD), 2026-08-19T03:09:16Z
  frontend  success
  kotlin    success        ← 오랫동안 failure 였던 잡이 이 push 에서 처음 초록
  llm-lane  cancelled      ← 기지 항목(30분 상한, Phase 5)
  quality   failure        ← 실패 스텝: "데이터 보호 불변식 스캔 (BLOCK 후보 0건 유지)"
```

**실패 뒤 skip 된 스텝 8개** (`gh run view --json jobs`):

| # | 스텝 | 결과 |
|---|---|---|
| 9 | 데이터 보호 불변식 스캔 (BLOCK 후보 0건 유지) | **failure** |
| 10 | Python 정본 스냅샷 재생성 diff 검사 (X-9 뒤 조각) | skipped |
| 11 | `uv run alembic upgrade head` | skipped |
| 12 | 하네스 도달 검사 실재 확인 (`tests/test_harness_scope_reach.py`) | skipped |
| 13 | **스캐너 회귀 실재 확인** (`tests/test_privacy_scanner.py`) | skipped |
| 14 | parity 게이트 회귀 실재 확인 | skipped |
| 15 | 스냅샷 가드 회귀 실재 확인 | skipped |
| 16 | 게이트 러너 계약 실재 확인 | skipped |
| 17 | **`uv run pytest`** (Python 전체 스위트) | skipped |

`.github/workflows/ci.yml:103-104` 는 `--no-fail` 없이 스캔을 돌린다. 스캔이 exit 1 이면 스텝이
실패하고 **기본 동작으로 잡이 거기서 끊긴다.**

**BLOCK 후보 수 — base 대비 이 단위가 늘렸다** (일회용 worktree 두 개에서 각 트리의 **자기 스크립트
사본**으로 전수 실행. 스크립트 경로를 본 트리에서 주면 루트가 본 트리로 잡혀 base 측정이 무효가 된다 —
그 함정을 밟았다가 다시 쟀다):

| 트리 | 검사 파일 | BLOCK | 내역 | exit |
|---|---|---|---|---|
| `d04ad98` (base) | 221 | **1** | `SECRET-LITERAL` × 1 | **1** |
| `cc7268c` (이 단위) | 233 | **8** | 위 1 + **`OWNERSHIP-403` × 7** | **1** |

- `SECRET-LITERAL` — `backend-kotlin/api/src/test/.../RequestFieldConstraintLayerTest.kt:229`
  `PASSWORD = "SignupRequest.password"`. 도입 커밋 `f9ee3e6`(auth 단위, `d04ad98` 의 조상).
- `OWNERSHIP-403` × 7 — **전부 이 단위가 새로 만든** `WorkspaceEndpointReachTest.kt:181·182·189·296·304·533(×2)`.
  아이러니하게도 「**403 이 아니다**」를 단언하는 줄들이다.

**왜 차단인가**

1. **탐지선이 상시 빨강이면 진짜 위반이 새 빨강과 구분되지 않는다.** 이 스캔은 I-1·I-3·I-4·I-5·I-12 의
   기계 탐지선이고, `--changed` 가 아니라 전수로 도는 유일한 자리다.
2. **한 스텝의 red 가 레인 전체를 껐다.** 특히 skip 된 13번은 `ci.yml:147` 이 *"이 파일이 지키는 것은
   CI BLOCK 게이트(`scan_privacy_invariants.py`)의 **동작 전부**다"* 라고 적은 스캐너 회귀다. 즉
   **스캐너가 빨간 동안 스캐너의 회귀도 돌지 않는다.** 17번(`uv run pytest`)까지 함께 죽어 Python
   전체 스위트도 CI 에서 실행되지 않았다.
3. **로컬 검사 목록이 CI 게이트와 갈려 있어 볼 방법이 없었다** — 아래 F-3.

**8건의 실질은 전부 오탐이다**(테스트가 「403 이 아님」을 단언하는 줄 / 필드 이름 상수). 그러나
**은폐형으로 닫지 않는다** — `CLAUDE.md` 규칙 4가 「무시 패턴·억제·면제 조항은 ⑴이 참이어도 넓히지
않는다 — 탐지형으로 갈아탄다」로 정했다. `OWNERSHIP-403` 은 「403 을 **기대하지 않는다**」는 단언까지
잡는 **구조적** 오탐이라 `hardened`/`refine` 축(탐지형)으로 갈아타는 것이 방향이고, `SECRET-LITERAL` 은
`looks_like_real_secret` 이 점 포함 식별자꼴을 난수로 오판한 자리다. **처방 판정은 `privacy-gate` 소관**이고
여기서는 게이트가 무력하다는 사실만 올린다.

**마감: 즉시.** 이미 실사용 중인 게이트이고, 다음 push 전에 닫히지 않으면 이번 회차의 관측이 그대로
다음 회차의 관측이 된다. 착수 차단 여부의 판정은 리더 몫이다.

### 그 밖의 도달 범위 점검 — **검토함**

| 점검 | 결과 |
|---|---|
| 새 「전역/모든/항상」 선언 | **검토함 — 지적 없음.** `Nulls.FAIL` 「전역 기본값」이 유일한 신규 전역 선언이고, 실제 도달을 실측했다 → 아래 |
| `Nulls.FAIL` 의 실제 도달(blast radius) | **검토함 — 지적 없음.** `JsonMapperBuilderCustomizer` 는 Spring 이 만든 매퍼만 덮는다. 본 소스의 다른 JSON 소비자는 `AnthropicProvider.kt:164` 하나이고 **자기 `JsonMapper.builder().build()` 를 쓴다** — LLM 응답 파싱은 이 설정에 닿지 않는다(Phase 5 우려 해소). 산출물 §7-3 #15 가 연 항목의 절반이 여기서 닫힌다 |
| 은폐형 확대(무시 패턴·면제·억제) | **검토함 — 지적 없음.** 이 범위에 새 억제·면제 0건. L-1 을 레벨 억제 대신 탐지 회귀로 닫은 것(ⓘ)은 규칙 4의 올바른 방향이다 |
| 새 게이트가 **어디서 도는가** | **검토함.** `ContainerRejectionCoverageContractTest`·`WorkspaceContractTest` 는 컨텍스트 없이 도는 단위·슬라이스 테스트, `WorkspaceEndpointReachTest`·`JdbcWorkspaceRepositoryTest` 는 Testcontainers. CI `kotlin` 잡이 `docker pull pgvector/pgvector:pg16` 뒤 `./gradlew build` 를 돌리므로 **전건 CI 도달이 선다** — 단 **이 단위는 아직 push 되지 않았다**(원격 HEAD = `6fe4357`). 이 단위의 CI 첫 관측은 다음 push 다 |
| 검사 기준이 검사 대상 자신에게서 오는가 | **검토함 — 1건 지적**(A-5). workspaces 쪽은 전건 계약 파서 경유(§2 계약 축), auth 쪽 S-9c 만 기대값이 문자열 리터럴 복제다 |
| 대리 지표로 판정하는가 | **검토함 — 지적 없음.** 「빌드 초록 = 그 경로가 돌았다」로 바꿔 읽은 자리를 찾지 못했다. 오히려 반대로, `ContainerRejectionCoverageContractTest` 는 **개수 대조를 명시적으로 거부**하고 집합으로 갔다(맞바뀜이 개수로는 안 드러난다는 근거를 KDoc 에 적었다) |
| 대리 경로에서 측정했는가 | **검토함 — 지적 없음.** 소유권 404 시간 동형 측정이 인메모리 대역이 아니라 **실기동 + 실 Postgres** 에서 이뤄지는지 의심해 확인했다: `WorkspaceEndpointReachTest` 는 `@SpringBootTest(RANDOM_PORT)` + `@DynamicPropertySource` 로 `PostgresTestSupport` 컨테이너를 붙인다(:579-584). 인메모리 대역(`AuthSliceBeans.InMemoryWorkspaceRepository`)은 **슬라이스 전용**이고 그쪽에는 타이밍 케이스가 없다 |
| 음성 대조가 붙어 있는가 | **검토함 — 독립 재현 8건 전건 성립**(§5) |
| 판정 코드가 자기 자신을 검사 대상에 넣는가 | **검토함 — 지적 없음.** 새 판정 코드는 전부 Kotlin 테스트라 `ktlintCheck`·`detekt`·`build` 범위 안이다(`--rerun-tasks` 81태스크 전건 실행 확인) |

---

## 2. 계약 준수

### 통과 확인 (실행·대조 근거)

| 항목 | 근거 |
|---|---|
| F3 — `name` 에 Bean Validation 부재, 판정은 서비스 층 | `WorkspaceDtos.kt:32-36` 에 제약 애너테이션 0. `RequestFieldConstraintLayerTest` 가 계약 파일을 읽어 상시 확인하고, DTO 가 생긴 순간 그 도달 범위에 들어온다 |
| `measured_on` = 정규화 후 | `WC-7` 이 `MeasurementAxis.NORMALIZED` 를 계약에서 읽어 단언하고(:168), 제어문자 포함 원시 초과 입력이 통과함을 잰다 |
| X-F10 — 422 `detail` 이 **문자열** | `WC-4` 가 `assertDetailIsString` + 값 대조. 배열이면 `@Size`/`@NotBlank` 구현이라는 판별 근거를 KDoc 에 적었다 |
| X-A3 두 축 | `WC-12`(토큰 없음 + 빈 이름 → 401) · `WR-8`(위조 토큰 + 비 UUID 경로 변수 → 401). 둘 다 실기동 |
| 404/409/422 본문·값 미반향 | 오류 문구가 전부 고정 상수. `WorkspaceNameRules.kt:60` 에 *"입력값을 메시지에 넣지 않는다 — 이 문자열이 그대로 응답 detail 이 된다"* 근거 주석. `JdbcWorkspaceRepository` 는 PostgreSQL 제약 위반의 `DETAIL`(행 전체 = 사용자가 적은 이름)이 새지 않도록 **원인 체인을 끊는다**(:53-56·:111-117·:146-150·:208-210) |
| 지침 8 「실행 경로 0」 | **사실 확인.** `handleHandlerMethodValidationException` 은 `GlobalExceptionHandler.kt:161` 에 존재하나 이 단위에서 도는 경로가 없다 — 경로 변수 422 는 `format: uuid` **타입 변환** 실패라 `MethodArgumentTypeMismatchException` 경로이고, 계약이 작업 공간 어디에도 제약 애너테이션을 요구하지 않는다. `WR-7` 이 그 사실을 `pathParameter().format == uuid` 단언으로 굳혔다(:236). **산출물 미결 ① 의 「이 단위가 마감이 아니다」 표기가 정확하다** |
| G1 정정 정합 | 계약이 인용 수를 고친 자리 **여섯 곳 전부** 확인(`x-failure-mode-shift`·`enforcement`·`x-container-coupling`·`x-openapi-expressibility` ⑥·`resolution`·`residual`). `x-unmeasured` 는 405 를 대상에서 빼고 「콜론 없는 헤더 줄 400」 1종 측정을 등재하되 **E-4 를 닫지 않았다**(5종 미측정 명시) — 과대 표기 없음 |
| 열거자 ↔ 계약 **집합** 대조 실재 | `ContainerRejectionCoverageContractTest` 실재. 빈 계약 목록도 막고(`isNotEmpty`), 상수 이름 중복도 막는다(`doesNotHaveDuplicates` — 접히면 집합 크기가 줄어 대조가 헐거워지는 자리). **양방향 독립 변이로 확인**(§5 M5·M6) |
| P-16~P-21 파서 확장 | 6종 전건 실재. 모든 파서가 노드 부재 시 `error(...)` 로 끊는다(빈 선언 통과 없음) |
| React 3자 | `frontend/src/api/types.ts:117-137` 이 네 타입을 계약과 같은 모양(snake_case·`document_count` 목록 전용·`items` 하나)으로 이미 들고 있다. 전면 대조는 Phase 6 |

### J-1 [**판정 필요**] C-2(i) — 명시적 `null` 과 필드 누락을 둘 다 `missing` 으로 통일한 것이 계약과 맞는가

계약 `components/responses/ValidationFailed`(:1524-1554)는 배열 갈래를 *"필드 누락·타입 불일치·enum 밖 값"*
으로 열거하고 예시 `field_missing` 은 `{loc:["body","password"], msg:"Field required", type:"missing"}` 하나다.
**명시적 `null` 갈래를 두지 않았다** — 계약 침묵이다. 구현은 전역 `Nulls.FAIL` 로 둘을 하나로 만들고
(`GlobalExceptionHandler.kt:329`) `msg` 는 계약 예시와 같은 `"Field required"`(:287), `type` 은 `"missing"` 이다.

Python/Pydantic 은 명시 `null` 을 `string_type` 으로 가른다. 산출물 §2-1 이 **「의도한 어긋남 1건」으로
스스로 등재**했고 근거(계약 문면 기준, Python 비정본)가 `CLAUDE.md` 와 맞는다. 남는 것은 계약 소유자의
확인 한 줄이다 — 침묵이 맞는지, 아니면 `null` 갈래를 계약에 적을 것인지. **→ contract-keeper.**

### J-2 · J-3 [**판정 필요**] 갈림 3건 중 둘은 계약 침묵이 맞다 — 실제 확인

리더 지시대로 **「계약이 정말 침묵하는가」를 계약 파일에서 직접 확인**했다.

| 갈림 | 계약 확인 결과 | 판정 |
|---|---|---|
| **D-1** 409(이름 중복) `detail` 문구 | `POST /workspaces` 409 · `PATCH` 409 에 `examples` 노드 **없음** — 침묵 확인 | **J-2 판정 필요(이월).** 계약 테스트가 상태 코드와 `detail` **타입**까지만 걸고 문구를 단언하지 않은 선택이 옳다 — 문구를 테스트에 적으면 구현이 계약이 된다. O-7 그대로 리더 대기 |
| **D-2** 삭제 거절 두 갈래의 순서 | `DELETE` 409 에 `has_documents`·`last_one` **두 예시가 다 있으나**(:1402-1404) 동시 해당 시의 우선순위 조항 **없음** — 침묵 확인 | **J-3 판정 필요.** 구현 근거(「마지막 하나」가 무조건적 거절이라 먼저 말해야 사용자가 되돌릴 수 없는 파기를 헛되이 실행하지 않는다)는 조항의 뜻에 부합한다. 다만 **침묵인 채로 두면 다음 사람이 뒤집는다** — 계약에 한 줄 적을 것을 권고 → contract-keeper |
| **D-3** 정렬 동점 `ORDER BY created_at, id` | 계약은 「만든 순서 · 첫 번째가 기본 작업 공간」만 말한다 — 동점 규칙 침묵 | **권고(수용).** `id`(UUIDv4)가 뜻 있는 순서를 주지는 않지만 흔들리지 않게는 한다. 침묵 자리를 결정론으로 메운 것이라 방향이 옳다 |

---

## 3. 보안 불변식

> `privacy-gate` 가 정본이다. 아래는 이 회차에 **새로 들어온 코드가 그 목록의 어느 항목에 닿는지**의 지목이다.

### 통과 확인

| 불변식 | 확인 |
|---|---|
| **I-5 소유권 은닉 404** — 네 오퍼레이션 | 포트 5메서드가 **전부 `ownerId` 를 받고**(`AuthPorts.kt:56-125`) 구현이 그것을 `WHERE` 에 넣는다. 「읽고 나서 비교」 형태를 만들 수 없게 **시그니처가 막는다**. `rename` 은 없는 것과 남의 것을 같은 `null` 로 끝내고(:127-150), `lockForDeletion` 은 **문서 수 질의가 소유 확인 뒤에만 돈다**(:175-186) — 두 경우가 하는 일이 같다 |
| **I-5 시간 축** | 독립 재현: **비 1.068**(없음 2.400ms / 타인 2.247ms, 문턱 2.0). 산출물의 1.031 과 값은 다르지만 **성질은 같다** — 타이밍은 표본마다 흔들리는 것이 정상이고, 두 측정이 같은 쪽(문턱 한참 아래)에 있다. 문턱 2.0 이 auth 의 1.5 보다 넓은 근거(바닥 비용이 Argon2 100ms 가 아니라 질의 수 ms 라 같은 절대 지터가 훨씬 큰 비로 나타난다)가 KDoc 에 적혀 있고 타당하다. 교차 측정(interleaved)으로 워밍업 편향을 막은 것도 맞는 설계다 |
| **I-5 SQL WHERE 우회 경로** | **전수 확인 — 0건.** `ownerId` 가 요청 본문·쿼리에서 오는 경로가 없다(`WorkspaceController` 는 전부 `user.id`, `AuthenticatedUser` 는 인터셉터가 토큰에서 넣는 타입). 소유 조건 없는 질의는 `createDefault`(가입 트랜잭션 — 대상 없음)와 `lockForDeletion` 의 문서 수 질의(소유 확인 뒤)뿐 |
| **삭제 잠금 범위 · 「마지막 하나」 동시 삭제** | `SELECT id FROM workspaces WHERE user_id = :ownerId ORDER BY id FOR UPDATE` — 대상 행이 아니라 **집합**을 잠근다. `JdbcWorkspaceRepositoryTest`「동시 삭제가 직렬화된다」가 `CyclicBarrier` 로 두 스레드를 같은 순간에 풀어 실제로 겹치게 만들고(겹치지 않으면 순차 실행과 같아져 아무것도 재지 않는다는 근거를 KDoc 에 적었다) `containsExactlyInAnyOrder(true,false)` 를 단언한다. **독립 변이 M8 에서 `FOR UPDATE` 를 떼자 정확히 이 케이스만 빨강**(§5) |
| **FK 409 방벽** | 판정과 DELETE 사이의 문서 삽입 창에서 `fk_documents_workspace_id_workspaces`(NO ACTION)가 돈다. `JdbcWorkspaceRepositoryTest`「외래 키가 문서 든 작업 공간을 막는다」가 **유스케이스 사전 확인을 건너뛰고 저장소를 직접 불러** 그 창을 재현한다 |
| **I-3 로그** | `WorkspaceService`·`JdbcWorkspaceRepository`·`WorkspaceController` 에 로깅 호출 **0건**(실측). `GlobalExceptionHandler` 는 예외 **타입 이름만** 찍는다(:103·:129) |
| **I-8 M-1 난수 더미** | `randomDummySource()` = `SecureRandom` 32바이트 Base64(`Argon2PasswordHasher.kt:176-181`). 조립 1회·정책 추종 유지. `AuthService.verifyAgainstDummy` KDoc 이 재해시 미도달의 근거를 **`verify` 결과가 아니라 제어 흐름**으로 다시 적었다 — 종전 근거가 거짓이었던 자리의 정확한 수정이다 |
| **I-8 세마포어 250ms** | `AuthProperties.maxHashWaitMillis` 5_000 → 250, 유도(`W × P / H`, 5000×4/100 = 200 = Tomcat 기본 스레드 수)가 KDoc 에 있다. `PasswordHashingBackpressureReachTest` 실재 |
| **L-1 탐지형** | `PasswordHashLogLeakReachTest` 실재. 로거를 가리지 않고 모든 로그를 훑는 구조 |

### A-3 [**권고**] `Workspace`·응답 DTO 가 `data class` 라 `toString()` 에 사용자가 적은 이름이 실린다 (I-3 잠재)

계약 자신이 작업 공간 이름을 사적 응답 헤더 대상으로 분류했다(`x-private-response-headers.applies_to` —
*"작업 공간 이름도 사용자가 적은 콘텐츠"*). 그런데 `Workspace`·`WorkspaceListing`·`WorkspaceNameRequest`·
`WorkspaceResponse`·`WorkspaceListItemResponse` 가 전부 `data class` 이고 `toString()` 재정의가 없다.
`migration-safety-gate` I-3 검증 2가 요구하는 형태다.

**오늘 도달은 0이다** — 세 프로덕션 클래스에 로거가 없고, 예외 매퍼가 예외 메시지를 쓰지 않으며,
JVM 스택 프레임은 인자 값을 싣지 않는다. `Workspace` KDoc 이 소유자 미포함 근거로 *"담아 두면
`toString()`·직렬화 어디로든 새는 경로가 생긴다"* 를 들면서 **`name` 자신에는 같은 규율을 적용하지 않은
비대칭**이 남는다. **마감: 이 경로에 첫 로깅이 들어오는 커밋**(Phase 4 문서 API 가 유력하다).

### J-4 [**판정 필요**] 계정이 지워진 유효 토큰 (산출물 미결 ⑥)

`POST /workspaces` → 사용자 FK 위반 → `StorageException` → **500**. 계약이 그 오퍼레이션에 `InternalError` 를
선언하므로 **계약 위반은 아니다.** 다만 같은 토큰으로 `GET /workspaces` 는 **200 + 빈 배열**이 나가는데,
계약은 *"첫 번째 항목이 기본 작업 공간이다"* 를 전제한다(구조적으로는 빈 배열도 유효하다).
**오늘 도달 0** — 계정 삭제 엔드포인트가 존재하지 않는다. 구현이 「결함으로 단정하지 않고 등재한다」로
남긴 판단이 정확하다. **마감: 계정 삭제 경로가 생기는 단위.**

---

## 4. Kotlin/Spring 관용성

| 항목 | 판정 |
|---|---|
| 레이어 분리 (라우터 로직 0) | **통과.** `WorkspaceController` 는 HTTP 표현 변환만 한다. 정규화·길이 판정·소유권·삭제 거절 두 갈래가 전부 `WorkspaceService` |
| `core` 가 Spring·DB 를 끌어들이지 않는가 | **통과.** `core/workspace/Workspace.kt` 는 `java.time`·`java.util` 만 import. `moduleBoundaryCheck` 2태스크 실행 확인 |
| `application` 이 Spring 을 모르는가 | **통과.** `WorkspaceService` 에 애너테이션 0. 조립은 `infrastructure/workspace/WorkspaceConfiguration` |
| 트랜잭션 경계 (§4.4) | **통과.** 판정과 삭제가 한 트랜잭션(`WorkspaceService.delete`). `SpringTransactionRunner` 가 `TransactionTemplate` 기반이라 **자기 호출 프록시 함정이 없다** — 그 이점을 KDoc 이 근거로 적었다 |
| `JdbcClient` 사용 · JPA 미유입 | **통과.** JPA 애너테이션 0 |
| `ORDER BY id FOR UPDATE` 교착 위험 | **검토함 — 실질 위험 없음.** PostgreSQL 은 `LockRows` 노드가 `Sort` 위에 서므로 정렬 순서로 잠근다. 다만 A-7 참조 |
| `WorkspaceRepository` 를 `AuthPorts.kt` 에 둔 배치 | **권고 A-6 아님 — 수용.** 파일 KDoc 이 *"인증 전용 파일이 아니다"* 로 **범위를 넓혀 적었다**(:12-22). 「선언한 범위 = 실제 내용」이 성립하므로 이 하네스가 금지하는 형태가 아니다. 재배치는 리뷰를 마친 auth import 를 흔드는 비용이 이득보다 크다는 판단이 타당하다 |
| `Nulls.FAIL` 전역 부작용 | **§1 에서 실측 — 다른 DTO·LLM 어댑터에 닿지 않는다** |
| `JsonRequestStrictnessConfig` 개명 후 옛 이름 참조 | **코드 잔존 0건**(전수 grep). 산출물 문서 4곳에만 남고 그중 3곳은 「이름이 바뀌었다」를 적는 정정문이다 — 정상 |
| 스키마 무변경 | **확인.** `V3` 없음. `workspaces`·`documents` 는 `V1__python_schema_baseline.sql` 그대로 |

### A-1 [**권고 → 판정 회부**] 사용자당 작업 공간 **개수 상한이 계약에도 코드에도 없고**, 삭제 비용이 그 수에 비례한다

- 계약 `x-input-limits` 에 `max_workspace_name_length` 는 있으나 **개수 상한 없음**(전수 grep).
- `GET /workspaces` 는 **페이지네이션이 없다**(계약이 `limit`·`offset`·`has_more` 를 두지 않았다 — 이것은
  계약의 명시적 선택이고 DTO KDoc 이 근거를 적었다).
- `lockForDeletion` 은 매 DELETE 마다 **그 사용자의 행 전부**를 `FOR UPDATE` 로 잠근다.

셋이 합쳐지면, 인증된 사용자가 `POST /workspaces` 를 반복할수록 자기 계정의 **모든 DELETE 가 잠그는
행 수**와 **목록 응답 크기**가 함께 커진다. 자해에 가깝고 타인에게는 닿지 않으므로 ① 사건이 아니다.
**계약 침묵 자리**라 위반도 아니다. 다만 Phase 4 에서 문서·크레딧이 붙으면 같은 자리에 배수가 걸린다.
**→ contract-keeper 회부. 마감: Phase 4 착수 전.**

### A-2 [**권고**] `delete` 의 「터질 수 있는 제약은 하나뿐」 전제를 지키는 장치가 0이다

`JdbcWorkspaceRepository.delete`(:198-210)는 `DataIntegrityViolationException` 을 **메시지·SQLState 를 읽지
않고** 곧장 `ConflictException(WORKSPACE_HAS_DOCUMENTS_MESSAGE)` 로 옮긴다. 근거는 *"이 DELETE 에서
터질 수 있는 제약은 `fk_documents_workspace_id_workspaces` 하나뿐"* 이다.

**오늘 참이다** — V1 스키마에서 `workspaces` 를 참조하는 FK 는 그것 하나다(실측:
`V1__python_schema_baseline.sql:92`). 그러나 **그 전제를 지키는 테스트도 스키마 단언도 없다.**
Phase 4·5 가 `workspaces` 를 참조하는 테이블을 하나만 더 만들면, 그 위반이 조용히
**「작업 공간에 문서가 남아 있습니다」 409** 로 둔갑한다 — 사용자는 문서를 다 지워도 계속 거절당하고
원인을 알 수 없다. **마감: `workspaces` 를 참조하는 다음 마이그레이션 커밋.**

### A-4 [**권고**] 동시 삭제 정합성이 READ COMMITTED 의 `FOR UPDATE` 재평가에 기대는데 그 전제가 적혀 있지 않다

`lockForDeletion` → `refusalFor` → `delete` 가 옳게 도는 근거는, 앞선 트랜잭션이 커밋한 뒤 `FOR UPDATE` 가
삭제된 행을 결과에서 **빼 주는** READ COMMITTED 의 동작(EPQ)이다. `SpringTransactionRunner` 는 격리 수준을
명시하지 않고 `TransactionTemplate` 기본값(DataSource/DB 기본 = READ COMMITTED)을 따른다. 누군가
REPEATABLE READ 로 올리면 같은 시나리오가 409 가 아니라 **직렬화 실패(40001) → 500** 이 된다.
전제가 KDoc·테스트 어디에도 없다. **마감: 격리 수준을 건드리는 커밋 또는 Phase 5 워커 착수 시.**

### A-7 [**권고**] `ORDER BY id` 의 「교착을 막는다」 근거가 실제보다 넓다

교착은 **서로 다른 잠금 순서를 갖는 두 트랜잭션** 사이에서 난다. 여기서 잠그는 집합은 `user_id` 로
갈리므로 **서로 다른 사용자는 애초에 서로소**이고, 같은 사용자의 두 요청은 같은 질의라 같은 순서를 쓴다.
즉 `ORDER BY id` 는 무해한 보험이지 「교착을 막는」 장치가 아니다. 근거가 실제보다 넓게 적히면
다음 사람이 그 문장을 근거로 잠금 범위를 넓힐 수 있다. **문면만 좁히면 된다.**

---

## 5. 테스트 적정성 — 음성 대조 **독립 재현 8건, 전건 성립**

일회용 worktree(`git worktree add --detach … cc7268c`)에서만 했다. 복원은 `git checkout --` + **sha256 대조**
이고 **`cp` 를 쓰지 않았다**(규칙 5). 시작 시 계약 파일 해시가 산출물이 적은 `214bc63d…` 와 같음을 확인했고,
8변이 전건 후 세 파일 해시가 기준선과 같으며 `git status --porcelain` 0건임을 확인한 뒤 worktree 를 제거했다.
본 트리 계약 해시도 그대로다.

| # | 변이 | 산출물이 예고한 빨강 | **내가 관측한 빨강** | 판정 |
|---|---|---|---|---|
| **M1** | `fields[4].limit` 50→**49** (N-11a) | WC-5 · WR-6 · P-19·P-20 | WC-5 · WR-6 · P-19·P-20 | **정확 일치.** WC-6 초록도 예고대로 |
| **M2** | 같은 노드 50→**51** (N-11b) | WC-6 · WC-7 · WR-6 · P-19·P-20 | WC-6 · WC-7 · WR-6 · P-19·P-20 | **정확 일치.** 경계 **양쪽**이 계약을 읽는다 |
| **M3** | `DELETE` 409 `last_one` 예시 한 글자 (N-17) | **WD-5 만**(WD-4 초록) | **WD-5 만** | **정확 일치.** 두 409 갈래가 서로 다른 값을 본다 |
| **M4** | `parameters[0].name` → `workspaceId` (N-18) | WR-1·WR-3~5·WR-8·WR-9 · WD-1·WD-2·WD-4·WD-5·WD-7·WD-8 (12) | 위 12건 **+ 「소유권 404 응답 시간 동형」** (13) | **1건 과소 표기** → F-2 |
| **M5** | 계약 `unreachable_by_filter.cases` 갈래 이름 1개 변경 (N-19 계약 쪽) | 열거자↔계약 집합 대조 | 같음 | **정확 일치** |
| **M6** | **열거자 쪽** 같은 이름 변경 (**내가 추가한 반대 방향** — 리더 지시의 「맞바꿈」 축) | (산출물에 없음) | 같은 케이스 빨강 | **집합 대조가 양방향으로 산다** |
| **M7** | `rename` SQL 에서 `AND user_id = :ownerId` 제거 | (HTTP 축: WR-3·WR-4) | (**저장소 축으로 독립 확인**) 「남의 작업 공간은 바꿀 수 없다」·「남의 자원에서는 유일성 위반이 일어나지 않는다」 | **보완 근거.** 산출물과 **다른 계층**에서 같은 결함을 잡았다 |
| **M8** | `lockForDeletion` 의 `FOR UPDATE` 제거 | 「동시 삭제가 직렬화된다」 | 같음 | **정확 일치** |

**13건 중 8건을 독립 재현했다**(리더 지시 「최소 절반」 충족). 지시가 명시한 넷(N-11 양방향 · N-17 WD-5만 ·
소유 조건 제거 · 잠금 범위 축소)과 `ContainerRejectionCoverageContractTest` 맞바꿈이 전부 들어 있다.

### 그 밖의 테스트 축

| 항목 | 판정 |
|---|---|
| **검사 결과 독립 검증** | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` → **BUILD SUCCESSFUL · 81태스크 전건 실행(캐시 초록 아님) · exit 0**. 모듈별 XML 합계 **core 357 · application 43 · infrastructure 108 · api 169 · worker 3 = 680 · 실패 0** — 산출물 §2 와 **완전 일치** |
| **Testcontainers 저장소 테스트 3종** | ⑴ 행 잠금(동시 삭제 배리어) ⑵ FK 409(사전 확인 우회) ⑶ 유일 인덱스 → `DuplicateKeyException` → 409. 셋 다 *"실제 PostgreSQL 에서만 잴 수 있는 것"* 이라는 선정 근거가 KDoc 에 있고 실제로 그렇다. `count(d.id)` vs `count(*)` 회귀까지 별건으로 잡았다 |
| **HTTP 경계 — MockMvc 금지** | 401·404·타이밍·헤더 개수가 전부 `WorkspaceEndpointReachTest`(실기동 + 실 Postgres + `java.net.http`). `WorkspaceContractTest`(MockMvc 슬라이스)에는 그런 케이스가 없다. **예외 1건 — WD-6**(`DELETE` 경로 변수 422)이 MockMvc 다. 재는 것이 체인 순서가 아니라 예외 매퍼 출력이라 슬라이스가 맞는 자리이고, 같은 성질의 X-A3 축은 WR-8 이 실기동으로 겸한다 — **지적 아님** |
| **실패 경로 커버리지** | 성공 경로만 있는 모듈 없음. 네 오퍼레이션 전부 401·404·409·422 갈래를 든다 |
| **명세 케이스 대응** | 아래 §7 |

### A-5 [**권고**] auth 쪽 S-9c 의 기대값만 계약 파서를 거치지 않는다

workspaces 테스트는 상태·문구·헤더·경로 변수 이름을 전건 `ContractSpec` 으로 읽는다. 반면
`AuthEndpointReachTest` S-9c(:265)는 `"Field required"` 와 `"missing"` 을 **문자열 리터럴로 복제**한다.
`ContractSpec` 에 배열형 예시(`ValidationFailed.examples.field_missing`)를 읽는 파서가 없는 것이 원인이다 —
기존 `responseExampleDetail` 은 `value["detail"]?.toString()` 이라 배열에 쓸 수 없다. 계약의 그 예시가
바뀌면 S-9c 는 알아채지 못한다. 이 저장소가 workspaces 에서 이미 옳게 한 것을 auth 쪽에 맞추는 일이다.
**마감: Phase 4 첫 배열형 422 케이스 커밋.**

---

## 6. 산출물 정직성 — §7 열린 18건 · 미결 8

**전반적으로 정직하다.** 특히 다음 셋은 이 하네스가 반복해 고쳐 온 형태를 **선제적으로 막은** 자리다.

- 미결 ① — 지침 8 을 「이 단위가 마감이 아니다 · 실행 경로 0」으로 적고 *"이 단위가 「덮었다」로 읽히면
  안 된다"* 를 명시. **§2 에서 사실 확인.**
- §3-3 — `limit`/`offset` 음성 대조를 **「대상 없음」으로 적고 없는 것을 만들어 재지 않았다.**
- §4 — 갈림 3건을 「계약을 넘어선 것이 아니라 계약이 침묵하는 자리」로 분리. **§2 에서 세 건 다 침묵 확인.**
- auth-fixes2 §5 — 종전 산출물의 과대 표기 6행을 **그 파일을 고치지 않고** 정정.

그럼에도 **같은 형태가 이번 회차에 두 건 새로 생겼다.**

### F-1 [**수정 필요**] N-11a/N-11b 판정 문구가 사실과 다르다 — 「상한이 코드에 복제돼 있지 않다」

상한은 **코드에 두 번 복제돼 있다**:

- `WorkspaceNameRules.kt:31` — `private const val MAX_WORKSPACE_NAME_LENGTH = 50`
- `WorkspaceNameRules.kt:40` — `NAME_TOO_LONG_MESSAGE = "작업 공간 이름은 50자 이하여야 합니다"`

변이가 빨강이 된 것은 **테스트의 기대값이** 코드가 아니라 계약에서 오기 때문이지, 코드에 복제가 없어서가
아니다(프로덕션 코드가 런타임에 YAML 을 읽지 않는 이상 복제는 불가피하고, 그 복제를 **계약과 묶어 두는 것**이
테스트의 일이다). 기제 자체는 옳다 — 계약을 60으로 바꾸면 테스트가 빨개져 구현을 따라오게 만든다.

**문면만 틀렸는데 그것이 문제인 이유**: 산출물이 원장으로 옮겨질 때 남는 것은 이 판정 문구다.
게이트 21 §5 가 정정한 여섯 행이 전부 같은 형태였다. 정확한 진술은 **「테스트의 기대값이 구현이 아니라
계약에서 온다 — 코드의 복제본은 계약에 묶여 있다」**다. **마감: 게이트 22 종결 전.**

### F-2 [**수정 필요**] N-18 의 빨강 목록에서 타이밍 케이스가 빠졌고, 그것이 「과잉 결합 없음」 판정의 근거를 흠집낸다

§3-1 이 *"각 변이에서 빨강이 된 것이 **위 열의 케이스뿐**임을 확인했다(과잉 결합 없음)"* 이라고 적는데,
N-18 에서 「소유권 404 의 응답 시간이 갈리지 않는다」도 빨강이다(§5 M4 — 내 독립 재현). 과소 표기라
위험 방향은 아니지만, **목록의 정확성 자체가 그 판정의 근거**다. **마감: 게이트 22 종결 전.**

### F-3 [**수정 필요**] 두 산출물의 「검사 결과」 표에 `scan_privacy_invariants.py` 가 없다 — B-1 이 로컬에서 보이지 않았던 이유

| 산출물 | 검사 목록 | 스캔 |
|---|---|---|
| `auth-fixes2` §6-1·§6-2 | gradle · ruff · mypy · pytest | **없음** |
| `workspaces` §2 | gradle · 모듈별 테스트 · `moduleBoundaryCheck` · ruff · mypy · pytest | **없음** |

CI `quality` 잡은 그 스캔을 **BLOCK 게이트로** 돌린다. 로컬 검사 집합에서 빠져 있으면 red 를 커밋 전에
볼 방법이 없고, 실제로 두 회차가 red 위에 커밋했다. `ci.yml:66-67` 이 mypy 자리에서 *"로컬에서 치는 명령과
CI 가 도는 명령이 같아야 사각지대가 다시 안 생긴다"* 고 적은 그 규약이 이 스텝에는 적용되지 않았다.
**마감: 즉시**(B-1 과 한 묶음).

### A-6 [**권고 · 리더 몫**] 원장 「아직 돌리지 않은 검증 게이트」 표가 이 단위 뒤로 낡았다

| 행 | 원장 표기 | 실제 |
|---|---|---|
| Contract (14 endpoints) | `안 돎` — *"contract test 미구현"* | `WorkspaceContractTest`·`WorkspaceEndpointReachTest`·`ContainerRejectionCoverageContractTest` 등이 `ci:kotlin` 에서 돈다 |
| DB (Testcontainers) | *"repository·트랜잭션·`SKIP LOCKED` 는 아직 없다(Phase 3·5)"* | `JdbcWorkspaceRepositoryTest` 가 repository·트랜잭션·`FOR UPDATE` 를 실제로 잰다 |
| Security (소유권·로그·캐시) | `미실행` | 교차 사용자 404 · 시간 동형 · 사적 헤더가 실기동으로 측정된다 |

**과소 선언이라 위험 방향은 아니다.** 다만 리더가 Phase 판정에 쓰는 표이고, 이 하네스의 규칙은
「선언과 실제가 갈리면 잰다」이지 「안전한 쪽으로 갈려도 된다」가 아니다. **구현 레인이 원장을 접촉하지
않은 것은 옳았다** — 갱신은 리더 몫이다.

---

## 7. workspaces 계약 케이스 대응 (명세 `03_contract-keeper_workspaces-test-spec.md` ↔ 테스트)

기계 대조(케이스 ID 추출 후 집합 비교):

| 축 | 명세 | 테스트 | 결과 |
|---|---|---|---|
| `WX`·`WL`·`WC`·`WR`·`WD` | **37건** | **36건** | **차이 1건 = `WR-2`** |
| `P-16`~`P-21` | 6건 | 6건 | **전건 일치** |

**`WR-2`(PUT 부재)에 개별 케이스가 없는 것은 결함이 아니다.** 명세대로
`AuthenticationCoverageContractTest` 의 「서비스 중인 (경로, 메서드) = 계약의 (경로, 메서드)」 **정확 일치**가
겸한다 — `PUT /workspaces/{workspace_id}` 를 구현하면 계약에 없는 오퍼레이션이라 그 테스트가 빨개진다.
게이트 21 codex C-1 이 `(경로, 메서드)` 투영을 요구한 바로 그 수정이 이 케이스를 성립시킨 것이라,
**두 항목이 서로를 지탱한다.**

배치도 계층과 맞는다 — 체인 순서·자원 실재가 필요한 것은 전부 실기동(C-R·C-I), 계약 파일 대조는
슬라이스·단위(C-M·C-P), DB 제약·잠금은 Testcontainers.

---

## 8. 게이트 21 항목별 해소 상태

### 8-1. `auth-fixes2` §0 조치 12건

| # | 항목 | 상태 | 근거 |
|---|---|---|---|
| 1 | codex C-1 `(경로, 메서드)` 투영 | **해소** | `AuthenticationCoverageContractTest` 전면 개편 확인. `servedOperations()` · `contractOperationsBySecurity()` · 메서드 어휘 정본 1곳(`ContractSpec.HTTP_METHODS`) · 빈 매핑을 어휘 전부로 펼침 |
| 2 | M-1 난수 더미 | **해소** | `randomDummySource()` 확인. `AuthPorts.dummyHash()` KDoc ⑵ 가 근거를 「제어 흐름」으로 다시 적었다 |
| 3 | KTL-2 ≡ R-2 대기 상한 250ms | **해소** | 값 + 유도(`W × P / H`) KDoc 확인 |
| 4 | TST-2 배압 HTTP 회귀 | **해소** | `PasswordHashingBackpressureReachTest` 실재 · 빌드 통과 |
| 5 | SEC-3 + RCH-2 L-1 탐지형 | **해소** | `PasswordHashLogLeakReachTest` 실재. 은폐형(레벨 억제)으로 닫지 않았다 |
| 6 | codex C-2 (i) 누락·`null` | **해소** (계약 침묵 확인은 **J-1**) | `Nulls.FAIL` + `InvalidNullException` 분기 + **S-9c 가 `loc`·`type`·`msg` 를 값으로 단언** |
| 7 | codex C-2 (ii) 클래스명 노출 | **해소** | `typeLabelOf` 마지막 갈래에서 `requiredType.simpleName` 제거 확인 |
| 8 | TST-3 ≡ C-2 (iii) S-9b | **해소** | 값 단언 + S-9c·S-9d 신설 |
| 9 | TST-1 ≡ codex C-3 L-3b | **해소** | 절대 하한 + 교차 측정 + 수치 출력 |
| 10 | SEC-4 ≡ ck §1-3 백스톱 KDoc | **해소(문면)** | `GlobalExceptionHandler:112-126` 이 *"「예상하지 못한 예외」뿐이라고 적을 수 없다"* 로 사실대로 |
| 11 | ck §3-4 계약 7종 vs 열거자 | **해소** | 계약 정정 `4a25a7c`(6종) + 열거자 6 + `ContainerRejectionCoverageContractTest`. **양방향 독립 변이 확인**(M5·M6). §4 가 「대조 장치는 아직 없다」로 남긴 자리가 이 단위에서 닫혔다 |
| 12 | §5 과대 표기 정정 | **해소 — 단 같은 형태가 재발** | 정정표 6행 확인. 그러나 이번 회차가 **F-1·F-2** 를 새로 만들었다 |

### 8-2. 리더 판정 ⓐ~ⓚ

| 판정 | 상태 |
|---|---|
| **ⓐ** 배압 500 유지 | **준수** — 코드 무변경 확인 |
| **ⓑ** `InternalError`/`ServiceUnavailable` 개정 → escalate ④ | **이행** — `03_contract-keeper_escalation-503.md` 선택지 표 A/B/C 작성. **blast radius 13/14 동일 확인**(A 는 `ServiceUnavailable`, B 는 `InternalError` — 둘 다 14 오퍼레이션 중 13 이 `$ref`). 계약 파일 무접촉. **사용자 판단 대기** |
| **ⓔ** SEC-2 signup 409 | **사용자 판단 대기** — 코드 무변경 |
| **ⓖ** 세마포어 | **해소**(3·4) |
| **ⓗ** SEC-1 ≡ R-1 운영 지침 등재 | **미검토** — 원장 등재 여부를 이 회차에 확인하지 않았다(원장은 읽기만 했고 해당 절을 찾지 못했다) |
| **ⓘ** L-1 탐지형 | **해소**(5) |
| **ⓙ** C-1 | **해소**(1) |
| **ⓚ** M-1 | **해소**(2) |

### 8-3. `auth-fixes2` §7 열린 18건 중 이 회차에 움직인 것

| # | 항목 | 이번 회차 |
|---|---|---|
| 5 | 계약 ↔ 열거자 대조 장치 | **해소**(§8-1 #11) |
| 14 | **CI 원격 캐시 거동 — 0관점** | **관측함 → B-1.** 3회차 연속 0관점이 끊겼다. 관측 결과가 red 다 |
| 15 | 전역 `Nulls.FAIL` blast radius | **부분 해소.** LLM 어댑터 축은 실측으로 닫혔다(§1). 「선택 필드가 생기면 `Nulls.SET` 을 명시」는 Phase 4 그대로 |
| 18 | `JsonCoercionConfig` 옛 이름 참조 | **해소(코드 축).** 코드 잔존 0건. 문서 잔존 4곳은 정정문이거나 종전 산출물(사후 편집 금지 대상) |
| 나머지 14건 | — | **무변경.** 마감이 전부 Phase 4 이후이거나 리더·사용자 대기 |

---

## 9. Phase 종료 조건 대비 현황

계획 §5 Phase 3 는 「인증·작업 공간 API 가 계약대로 동작하고 교차 사용자 접근이 0」이다.
**`workspaces` 단위 자체는 그 조건 쪽에서 막는 것이 없다** — ① 사건 차단 0, 계약 케이스 36/37 배치
(1건은 커버리지 테스트가 겸함), 교차 사용자 404 가 상태·본문 바이트·헤더 이름 집합·응답 시간 네 축으로
실측된다.

**막는 것은 B-1 하나다.** §6 검증 매트릭스의 Security 게이트와 계획 §5 Phase 7 즉시 중단 기준을 **기계로**
지키는 장치가 red 이고, 그 red 가 CI quality 레인의 나머지(스캐너 자기 회귀 · parity 게이트 회귀 ·
하네스 도달 검사 · Python 전체 스위트)까지 껐다. **Critical 이 하나라도 남으면 Phase 종료를 보고하지
않는다**(`codex-review` §5 판단 규칙 5). 착수 차단 여부의 판정은 리더 몫이며, 마감은 **즉시**다.

**미해결 목록(마감순)**

| 마감 | 항목 |
|---|---|
| **즉시** | **B-1**(CI quality red · 8스텝 skip) · **F-3**(로컬 검사 목록에 스캔 부재) |
| 게이트 22 종결 전 | F-1 · F-2 |
| 리더·사용자 판단 | J-1(→ck) · J-2(O-7 이월) · J-3(→ck) · A-1(→ck) · A-6(원장) |
| Phase 4 착수 전 | A-1 |
| Phase 4 해당 커밋 | A-2(`workspaces` 참조 마이그레이션) · A-3(첫 로깅) · A-5(첫 배열형 422) · J-4(계정 삭제 경로) |
| Phase 5 / 격리 수준 변경 | A-4 |
| 문면만 | A-7 |

---

## 10. 미실행 · 확인 불가 항목

**보지 못한 것을 「지적 없음」으로 적지 않는다.**

| 항목 | 상태 | 사유 |
|---|---|---|
| codex 교차 대조 | **미수행 — 정상** | 1차(독립 리뷰) 회차다. codex 산출물은 이 시점에 존재하지 않는 것이 정상이며 **실패로 기록하지 않는다** |
| `privacy-gate` 산출물 | **미참조** | 리더 지시. 작업 중 `03_security-workspaces_privacy-gate.md` 가 워킹 트리에 나타났으나 **열지 않았다.** 보안 축 판정이 갈리면 `privacy-gate` 가 우선한다 |
| 음성 대조 나머지 5건 | **미재현** | N-12·N-13·N-14·N-15·N-16 · 「보호 목록에서 두 경로 제거」. 8건으로 「최소 절반」을 넘겼고 지시가 명시한 축을 전부 덮었다. 산출물 표기는 재현한 8건에서 **8/8 정확**(F-2 의 1건 과소 표기 제외)이라 나머지의 신뢰도도 높게 본다 — 다만 **재지 않았다는 사실을 그대로 남긴다** |
| auth-fixes2 6변이(X-1·X-2·X-4 옛 판/새 판 갈림) | **미재현** | 옛 판 복원(`07a8bc5^`·`6fecf9c^` 의 특정 파일만 되돌리기)이 필요해 이 회차 예산을 넘었다. **코드 대조로는 셋 다 처방이 실재함을 확인**했으나 「옛 판 초록 / 새 판 빨강」의 갈림 자체는 재현하지 않았다 |
| L-1 캡처 회귀 양성/음성 대조 | **미재현** | 위와 같은 이유. 테스트 파일 실재와 구조(로거를 가리지 않는다)만 확인 |
| R-2 수치(240 동시 3점) | **미재현** | 실기동 부하 측정이라 예산 밖. 값의 방향(대기 상한 축소 → `/health` 최대 지연 감소)은 유도식과 정합 |
| ⓗ 운영 지침 원장 등재 | **미검토** | §8-2 |
| `tests/golden` | **미실행** | 이 범위가 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았다(`app/` 무접촉 확인) |
| Python 검사(ruff·mypy·pytest) | **미재실행** | `app/`·`tests/` 무접촉을 diff 로 확인해 산출물 결과를 받았다. 단 **CI 에서 `uv run pytest` 가 skip 됐다**(B-1) — 로컬 결과만 있고 CI 관측은 없다 |
| React 전면 3자 대조 | **미실행** | Phase 6. 네 타입의 모양 일치만 눈으로 확인 |
| 이 단위의 CI 실행 | **불가** | `e6eb72e..cc7268c` 미push(원격 HEAD = `6fe4357`). 이 단위의 CI 첫 관측은 다음 push 다 |

---

## 11. 2차(교차 종합) 재호출에 넘기는 것

- 이 문서와 `03_workspaces_codex-reviewer.md` 를 입력으로 `03_workspaces_cross.md` 를 만든다.
  **어간 `03_workspaces` 고정** — 셋이 같아야 게이트가 닫힌다.
- **B-1 은 codex 가 볼 수 없는 축일 가능성이 높다** — codex 리뷰 대상은 diff 이고, B-1 의 근거는
  `gh run` 관측과 두 트리의 스캔 실행이다. 2차에서 「Claude 단독」으로 나오면 그 이유를 사실로 적고
  짐작을 붙이지 않는다.
- 2차는 **대조만 한다.** 여기 없는 새 지적을 `..._cross.md` 본문에 넣지 않고, 종합 중 발견한 것은
  **「종합 중 발견 — 미교차」** 구획으로 분리한다.
