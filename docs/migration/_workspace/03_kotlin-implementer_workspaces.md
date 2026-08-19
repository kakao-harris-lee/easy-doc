# Phase 3 두 번째 작업 단위 `workspaces` — 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-19 / **범위:** 게이트 22 (= `auth-fixes2` 배치 + 이 단위)
**커밋:** `e31bbb4` · `ab53420` · `e4be6ff` · `951b1fd` · `5b28851` · `693a246` · `0c838ee`
**정본:** 계약 `contracts/easy-doc-v1.yaml` · 명세 `03_contract-keeper_workspaces-test-spec.md`

> **값을 여기 전사하지 않는다.** 상한 숫자·`detail` 문구·헤더 값은 계약 파일이 정본이고,
> 이 문서는 **어느 파일 어느 심벌이 무엇을 하는가**와 **실측 결과**만 든다.

---

## 1. 대응표 — 파일·심벌 지목

| 계층 | 파일 | 무엇 |
|---|---|---|
| `core` | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/workspace/Workspace.kt` | `Workspace`(소유자 미포함) · `WorkspaceListing`(목록 한 줄 = 공간 + 문서 수) |
| `application` | `.../application/workspace/WorkspaceNameRules.kt` | `normalizeWorkspaceName`(제어문자 제거 → trim) · `requireValidWorkspaceName`(코드 포인트 길이) |
| `application` | `.../application/workspace/WorkspaceMessages.kt` | 두 계층이 함께 쓰는 문구 4종 |
| `application` | `.../application/workspace/WorkspaceService.kt` | `list`·`create`·`rename`·`delete` · `refusalFor`(거절 두 갈래의 **순서**) |
| `application` | `.../application/auth/AuthPorts.kt` | `WorkspaceRepository` 5메서드 추가 · `WorkspaceDeletionState` |
| `infrastructure` | `.../infrastructure/auth/JdbcWorkspaceRepository.kt` | `listOwned`·`create`·`rename`·`lockForDeletion`·`delete` |
| `infrastructure` | `.../infrastructure/workspace/WorkspaceConfiguration.kt` | `WorkspaceService` 빈 |
| `api` | `.../api/workspace/WorkspaceDtos.kt` | 요청 1 + 응답 3. `name` 에 Bean Validation 없음(F3) |
| `api` | `.../api/workspace/WorkspaceController.kt` | 네 오퍼레이션 · 사적 헤더 하한선 3곳 |
| `api` | `.../api/auth/AuthenticatedEndpoints.kt` | 보호 목록에 `/workspaces`·`/workspaces/{workspace_id}` 등재 |

**테스트**

| 계층 | 파일 | 케이스 |
|---|---|---|
| C-M | `api/src/test/.../WorkspaceContractTest.kt` | P-16 · P-19·P-20 · WL-1·WL-2 · WC-1·WC-4~WC-11 · WR-6·WR-7 · WD-6 |
| C-R·C-I | `api/src/test/.../WorkspaceEndpointReachTest.kt` | WX-1 · WL-3~WL-6 · WC-2·WC-3·WC-12 · WR-1·WR-3~WR-5·WR-8·WR-9 · WD-1~WD-5·WD-7·WD-8 · 소유권 404 시간 동형 |
| C-P | `api/src/test/.../AuthUnavailableContractTest.kt` (기존 파일에 1건 추가) | WL-7 |
| DB | `infrastructure/src/test/.../JdbcWorkspaceRepositoryTest.kt` | 행 잠금 · FK 409 · 유일 인덱스 · `count(d.id)` |
| 계약↔코드 | `api/src/test/.../ContainerRejectionCoverageContractTest.kt` | 파싱 거절 열거자 **집합** 대조 (계약 정정 `4a25a7c` 후속) |
| 파서 | `api/src/test/.../support/ContractSpec.kt` | P-16~P-21 · `MeasurementAxis` 어휘 매핑 · `defaultWorkspaceName()` |

**WR-2(PUT 부재)는 개별 케이스를 두지 않았다** — 명세대로 `AuthenticationCoverageContractTest`
의 「서비스 중인 (경로, 메서드) = 계약의 (경로, 메서드)」 정확 일치가 겸한다.

---

## 2. 검사 결과

| 검사 | 결과 |
|---|---|
| `./gradlew ktlintCheck detekt build --continue --rerun-tasks` (단독 실행) | **exit 0 · BUILD SUCCESSFUL** |
| 모듈별 테스트 | core 357 · application 43 · infrastructure 108 · api 169 · worker 3 = **680건 · 실패 0 · 오류 0** |
| `moduleBoundaryCheck` | `:api` · `:worker` 두 태스크 실행됨 |
| `uv run ruff check .` | All checks passed |
| `uv run mypy . .claude` | Success — 137 files |
| `uv run pytest` | 1243 passed · 68 skipped · 5 xfailed |
| Python 무변경 | `app/**`·`tests/**` 손대지 않았다 (커밋 7건 전부 `backend-kotlin/` 아래) |

**`00_progress.md`·`contracts/` 무접촉** — 이 단위의 커밋 7건 중 어느 것도 그 두 경로를
스테이징하지 않았다. 작업 중 contract-keeper 가 `4a25a7c`·`b6e3093` 을 냈고 그것을 받아
썼을 뿐이다.

---

## 3. 음성 대조 — 전건 빨강, 과잉 결합 없음

**일회용 worktree**(`git worktree add --detach`)에서만 했다. 복원은 `git checkout --` +
**sha256 대조**이고 `cp` 를 쓰지 않았다(규칙 5). 대조 뒤 worktree 를 제거했고 본 트리의
계약 파일 해시가 그대로임을 확인했다(`214bc63d…`).

### 3-1. 계약 값 변이 (명세 §4-4)

| # | 바꾼 노드 | 빨강이 된 케이스 | 판정 |
|---|---|---|---|
| **N-11a** | `fields[?name].limit` 50→**49** | WC-5 · WR-6 · P-19·P-20 | 상한의 **복제본이 계약에 묶여 있다**(아래 정정) |
| **N-11b** | 같은 노드 50→**51** | WC-6 · WC-7 · WR-6 · P-19·P-20 | **경계 양쪽**이 계약을 읽는다 |
| **N-12** | `POST /workspaces` 422 `empty` 예시 `detail` 한 글자 | WC-4 · WR-6 | P-17(경로 인라인 예시)이 배선돼 있다 |
| **N-13** | 같은 절 `too_long` 예시 한 글자 | WC-5 · WR-6 | 두 갈래를 한 값으로 뭉개지 않았다 |
| **N-14** | `x-input-limits.max_workspace_name_length` **만** | P-19·P-20 | 이중 선언 대조가 배선돼 있다 |
| **N-15** | `WorkspaceNameRequest…x-service-constraint.max_length` **만** | P-19·P-20 | **셋째 축**이 대조에 들어가 있다 |
| **N-16** | `WorkspaceListItem` 의 `allOf` 둘째 갈래 `required` 비움 | WL-2 · P-16 | `allOf` 를 **합성**해서 읽는다 |
| **N-17** | `DELETE` 409 `last_one` 예시 한 글자 | **WD-5 만** (WD-4 초록) | 두 409 갈래가 서로 다른 값을 본다 |
| **N-18** | `parameters[0].name` → `workspaceId` | **16건** — WR-1·WR-3~WR-5·WR-8·WR-9 · WD-1·WD-2·WD-4·WD-5·WD-7·WD-8 **+ WR-6·WR-7·WD-6 + 「소유권 404 응답 시간 동형」** | P-21 이 배선돼 URL 이 계약에서 온다 |
| **N-19**(신설) | `unreachable_by_filter.cases` 한 갈래 이름을 다른 표현으로 | `ContainerRejectionCoverageContractTest` | 열거자↔계약 **집합** 대조가 산다 |

> **정정 (게이트 22, X-2/F-1 · 2026-08-19).** 종전 판정 문구 *"상한이 코드에 복제돼
> 있지 않다"* 는 **사실과 다르다.** `WorkspaceNameRules.kt` 에 값 `50` 이 두 번(상수와
> 문구 안), `detail` 문구 두 종이 그대로 있다(codex #2 가 짚은 그대로).
>
> 정확한 진술은 **「테스트의 기대값이 구현이 아니라 계약에서 온다 — 그래서 코드의
> 복제본이 계약에 묶여 있다」**다. 프로덕션 코드가 런타임에 YAML 을 읽지 않는 이상
> 복제 자체는 불가피하고, 그 복제를 계약과 **함께 움직이게** 만드는 것이 이 변이가
> 확인한 성질이다 — 계약을 49 나 51 로 바꾸면 테스트가 빨개져 구현을 따라오게 만든다.
>
> **리더 판정(2026-08-19): 「계약이 정본」의 강제 범위는 테스트 기대값까지다.**
> `WorkspaceNameRules` 의 상수는 그대로 두고, 그 상수를 계약에 결속하는 장치가
> **P-18~P-20 의 세 벌 대조**(`x-input-limits` ↔ `fields[].limit` ↔ 스키마
> `x-service-constraint.max_length`)와 **경계값 케이스 WC-5·WC-6·WC-7·WR-6** 이라는
> 사실을 여기 명시한다. 코드 변경은 없다. 런타임까지 강제하는 codex 처방(빌드 시
> 생성·주입)은 채택하지 않았다.

**N-11a 에서 WC-6 이 초록인 것은 결함이 아니다** — 상한을 내리면 「정확히 상한」 케이스는
실제 상한 아래를 쏘게 되어 통과가 맞다. 그래서 **반대 방향(N-11b)을 따로 돌렸고**, 거기서
WC-6·WC-7 이 빨강이다. 한 방향만 돌렸다면 경계 위쪽 배선이 검증되지 않은 채 남았다.

### 3-2. 구현 변이

| 무엇을 없앴나 | 빨강이 된 케이스 | 판정 |
|---|---|---|
| `rename` SQL 에서 `AND user_id = :ownerId` 제거 | WR-3 · WR-4 | 소유권 은닉이 **SQL 조건**에서 온다 |
| `AuthenticatedEndpoints` 에서 작업 공간 두 경로 제거 | `AuthenticationCoverageContractTest`「보호 목록이 계약과 같다」 | 명세가 예고한 그대로 계약이 누락을 잡는다 |
| `lockForDeletion` 의 `FOR UPDATE` 제거 | `JdbcWorkspaceRepositoryTest`「동시 삭제가 직렬화된다」 | 잠금이 **집합**에 걸려 있다 |

각 변이에서 빨강이 된 것이 **위 열의 케이스뿐**임을 확인했다(과잉 결합 없음). 복원 후
`git status` 잔여 변경 0건.

> **정정 (게이트 22, X-11/F-2 · 2026-08-19).** N-18 의 빨강 목록이 **12건으로 과소
> 표기**돼 있었고 위 표에서 **16건**으로 고쳤다. 빠졌던 넷은 `WorkspaceContractTest` 의
> **WR-6·WR-7·WD-6** 과 `WorkspaceEndpointReachTest` 의 **「소유권 404 응답 시간 동형」**
> 이다. 수치는 contract-keeper §4-3(앵커 유일성을 먼저 단언한 뒤 **행으로 좁혀** 재현)을
> 채택했고 리더가 확정했다. Claude 독립 재현은 13(시간 동형 1건만 추가)이었는데, 두
> 관점이 어긋난 것이 아니라 **재현 방법의 좁기가 달랐다** — 키 경로가 아니라 문자열
> 치환으로 재현하면 `GET /documents` 의 쿼리 파라미터 `workspace_id`(계약 `:995`)와
> 경로 변수(`:1324`)가 함께 바뀐다(contract-keeper 가 자기 명세의 결함으로 등재).
>
> **위험 방향은 아니지만(과소 표기라 결합이 실제보다 적어 보인다) 고치는 이유**는,
> 「빨강이 된 것이 위 열의 케이스뿐」이라는 **판정의 근거가 그 목록 자체**이기 때문이다.
> 목록이 틀리면 「과잉 결합 없음」이 무엇을 확인한 진술인지 알 수 없다.

### 3-3. `limit`/`offset` 검증 제거 — **대상 없음**

리더 지시 6의 셋째 항목은 이 단위에 대상이 없다. 계약 `paths./workspaces`·
`paths./workspaces/{workspace_id}` 에 쿼리 파라미터가 하나도 없고 `parameters` 는 경로 변수
하나뿐이다(2026-08-19 계약 확인). **없는 것을 만들어 재지 않았다.** 아래 §5 ①과 같은 건이다.

---

## 4. 갈림 — 계약이 말하지 않아 구현이 정한 것

이 셋은 **계약을 넘어선 것이 아니라 계약이 침묵하는 자리**다. 계약에 값이 생기면 그때
구현을 그 값에 맞춘다.

| # | 자리 | 구현이 고른 것 | 근거 |
|---|---|---|---|
| **D-1** | `POST`·`PATCH` 409(이름 중복)의 `detail` 문구 | `WorkspaceMessages.DUPLICATE_WORKSPACE_NAME_MESSAGE` | 계약이 그 두 409 에 `examples` 를 두지 않았다(명세 O-7 · 리더 판단 대기). **계약 테스트는 문구를 단언하지 않는다** — 상태 코드와 `detail` **타입**까지만 건다. 문구를 테스트에 적으면 계약이 아니라 구현이 계약이 된다 |
| **D-2** | 삭제 거절 두 갈래가 **동시에** 해당할 때의 순서 | 「마지막 하나」가 먼저 | 무조건적 거절이라 문서를 다 비워도 결과가 안 바뀐다. 반대로 고르면 사용자가 안내대로 문서를 지운 **뒤에** 다시 거절당하고, 그 파기는 되돌릴 수 없다 — 계약이 이 자리에서 지키려던 것이 바로 그 파기다 |
| **D-3** | 목록 정렬의 동점 처리 | `ORDER BY created_at, id` | `created_at` 은 `DEFAULT now()`(트랜잭션 시각)라 같은 트랜잭션의 두 행이 동점이다. 정해지지 않은 순서는 「첫 번째가 기본 작업 공간」이라는 계약을 **실행마다 다르게** 만든다. `id` 가 뜻 있는 순서를 주지는 않지만 흔들리지 않게는 한다 |

---

## 5. 미결·대기 — 이 단위에서 닫지 않은 것

| # | 항목 | 상태 |
|---|---|---|
| **①** | **지침 8 `handleHandlerMethodValidationException`** | **이 단위가 마감이 아니다.** 계약이 작업 공간 어디에도 제약 애너테이션을 요구하지 않고, 경로 변수 422 는 `format: uuid` **타입 변환** 실패라 `MethodArgumentTypeMismatchException` 경로다 — 그 핸들러는 돌지 않는다. 핸들러 자체는 auth 단위부터 코드에 있으나 **실행 경로가 0**이다. 마감은 `limit`/`offset` 이 생기는 단위(`GET /documents`, Phase 4). **이 단위가 「덮었다」로 읽히면 안 된다** |
| **②** | `spring-boot-starter-validation` | **들이지 않았다**(명세 §3-1). 지금 들이면 F3 음성 대조의 1차 방벽(`jakarta.validation` 부재)이 계약이 요구하지 않는 시점에 사라진다 |
| **③** | **O-7**(409 중복 이름 문구의 계약 침묵) | 리더 판단 대기. 구현은 §4 D-1 대로 두고 테스트를 넓히지 않았다 |
| **④** | **O-8**(`PATCH` 422 `description` 열거가 규범인가 서술인가) | 리더 재심(escalate ④)에 묶여 있다. 테스트는 계약이 **선언한 상태 코드**까지만 재고 `description` 열거는 재지 않는다 |
| **⑤** | **N-10**(같은 헤더 이름이 서로 다른 컴포넌트를 가리키게 만들기) | auth 에서 등재만 되고 밟히지 않은 항목. 명세가 「여기서 새로 요구하지 않고 이월」로 정했고 그대로 뒀다 |
| **⑥** | 계정이 지워진 상태의 유효 토큰으로 `POST /workspaces` | 사용자 FK 위반 → `StorageException` → **500**. 계약이 이 경로에 그 갈래를 두지 않았고(`/auth/me` 는 401 로 다룬다), 인터셉터는 토큰만 검증하고 계정 존재는 보지 않는다. **결함으로 단정하지 않고 등재한다** — 401 로 옮기려면 인터셉터가 매 요청 사용자 조회를 하거나 SQLState 를 읽어야 하고, 어느 쪽도 계약이 요구한 적이 없다 |
| **⑦** | 포트의 패키지 배치 | `WorkspaceRepository`·`TransactionRunner` 가 `application/auth/AuthPorts.kt` 에 남아 있다. auth 단위가 *"다음 작업 단위에서 이 인터페이스에 붙는다"* 로 예고한 자리를 따랐고, 대신 **파일이 담는 범위를 주석에서 넓혀 적었다**. 재배치는 개선 후보 — 리뷰를 마친 auth import 를 흔들 값어치가 지금은 없다 |
| **⑧** | React 3자 대조 | 하지 않았다(Phase 6). 다만 `frontend/src/api/types.ts:117-137` 이 이 네 응답 타입을 이미 계약과 같은 모양으로 들고 있음은 확인했다 |

---

## 6. 구현 판단 메모 (리뷰가 볼 자리)

- **소유권 은닉의 시간 축**을 SQL 조건으로 세웠다. 「읽고 나서 비교」 형태를 만들 수 없게
  포트 시그니처가 전부 `ownerId` 를 받는다. 실측 **1.031배**(없음 2.172ms / 타인 2.106ms).
  문턱 2.0 의 근거(auth 의 1.5 보다 넓은 이유 = 바닥 비용의 차이)는 테스트 KDoc 에 있다.
- **삭제의 잠금 범위**가 대상 행이 아니라 **그 사용자의 행 전부**다. 「마지막 하나」가 집합
  판정이기 때문이고, `ORDER BY id` 로 잠금 순서를 고정해 교착을 막는다.
- **FK 를 두 번째 방벽으로 쓴다.** 유스케이스의 문서 수 확인과 DELETE 사이에 문서 삽입은
  아무것도 막지 않는다. 그 창에서 도는 것이 `fk_documents_workspace_id_workspaces` 이고,
  이 DELETE 에서 터질 수 있는 제약이 그것 하나뿐이라 메시지·SQLState 를 읽지 않고 409 로
  옮긴다.
- **로그 0.** `WorkspaceService`·`JdbcWorkspaceRepository`·`WorkspaceController` 어디에도
  로깅 호출이 없다. 작업 공간 이름은 사용자가 적은 콘텐츠이고, 남길 만한 것(ID·상태)은
  접근 로그가 이미 든다.
- **스키마 무변경.** `V3` 를 만들지 않았다. `workspaces`·`documents` 는
  `V1__python_schema_baseline.sql` 그대로다.
