# 게이트 22 · 1단계 codex 독립 리뷰 — `03_workspaces`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`03_workspaces_cross.md`)의 몫이다.

**어간**: `03_workspaces` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 12:49:03 KST |
| 종료 시각 | 2026-08-19 13:03:28 KST |
| 소요 | **14분 25초** |
| 대상 범위 | **`d04ad98..cc7268c`** — 커밋 21개, 변경 파일 39개 |
| 모드 | `adversarial` (focus text 필수 — 소유권 은닉·동시성·계약·게이트 무력화 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base d04ad98`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6** — 캐시에 이 버전 하나뿐) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-mszjya0f-11kcm6` |
| codex session ID | `01a01823-0d43-7721-962a-ec5e770e9e70` (turn `01a01823-0eb2-7e73-a2a7-fdb5ecc62273`) |
| job 로그 | `~/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-mszjya0f-11kcm6.log` |
| codex 판정 | **`needs-attention`** — "NO-SHIP." |
| codex 실행 셸 명령 | **123건 시작 / 119건 완료 / 4건 실패**(실패 목록은 §5) |
| focus text 크기 | 16,039 바이트 |
| 지적 건수 | **8건 — high 3 · medium 5 · low 0** |
| codex 출력 크기 | 9,682 바이트 (sha256 `42a494dc00a2c5cd8fd3241dcfe84b1542eb45cce91acbdcf8596ca586fa51a6`) |

### 1.1 base 를 `d04ad98` 로 잡은 근거

리더의 지정 문자열 `d04ad98..cc7268c` 를 **그대로** 썼다. `d04ad98` 은 이 배치의 **직전 상태**
(게이트 21 산출물 커밋)이지 리뷰 대상 커밋이 아니므로 `~1` 보정을 하지 않았다.
`git rev-list --count d04ad98..cc7268c` 가 **21** 을 돌려주며 리더가 명시한 "21 커밋" 과 일치한다.
범위가 어긋나지 않았다.

세 덩어리의 커밋 배분도 리더 지정과 맞는다.

| 덩어리 | 커밋 |
|---|---|
| ⓐ auth-fixes2 배치 | `d74be4d` · `07a8bc5` · `83791bd` · `92a81aa` · `0cb0d0b` · `b97899c` · `6fecf9c` · `d7848ad` · `6b212a6` · `6fe4357` |
| ⓑ 계약 G1 정정 | `4a25a7c` + 명세 `e6eb72e` + escalate 표 `b6e3093` |
| ⓒ workspaces 단위 | `e31bbb4` · `ab53420` · `e4be6ff` · `951b1fd` · `5b28851` · `693a246` · `0c838ee` · `cc7268c` |

### 1.2 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs d04ad98
codex-review: 대상 판정 = non-empty (merge-base=d04ad98c81e0, 변경 파일 39개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다. `--dry-run` 선행 실행에서도
같은 두 줄이 나왔다.

### 1.3 리더가 지정한 문서를 codex 가 실제로 읽었는가 (전사 금지 지시의 이행 확인)

리더 지시는 "codex 에게 이 파일들을 **읽게** 하라(전사 금지)" 였다. focus text 는 네 문서와
계약의 내용을 옮겨 적지 않고 **경로와 라인 범위만** 주었다. codex 가 전부 열었음이 job 로그로
확인된다(로그 내 파일명 등장 횟수).

| 문서 | 로그 내 등장 |
|---|---|
| `contracts/easy-doc-v1.yaml` | 38회 |
| `docs/migration/_workspace/03_contract-keeper_workspaces-test-spec.md` | 4회 |
| `docs/migration/_workspace/03_contract-keeper_escalation-503.md` | 4회 |
| `docs/migration/_workspace/03_kotlin-implementer_workspaces.md` | 4회 |
| `docs/migration/_workspace/03_kotlin-implementer_auth-fixes2.md` | 2회 |

**diff 밖까지 읽었다.** codex 가 지적 근거로 인용한 파일 중 두 개는 이 배치의 변경 39개 파일에
**들어 있지 않다** — `api/auth/AuthenticationInterceptor.kt`, `api/ContractErrorBodyReachTest.kt`.
codex 가 diff 를 넘어 호출 사슬을 따라간 결과다. (사실 기록이며 평가가 아니다.)

### 1.4 인용 경로의 기계적 실재 확인

codex 가 준 파일 경로 14개를 **존재 여부와 행 수만** 대조했다(내용 판정 아님). **전부 실재하고
인용 라인 범위가 파일 길이 안에 든다.** 이 회차에는 "존재하지 않는 파일·범위 밖 라인" 인용이 없다.

| 인용 파일 | 총 행 수 | codex 인용 |
|---|---|---|
| `application/.../auth/AuthService.kt` | 170 | 105-116 |
| `api/.../auth/AuthenticationInterceptor.kt` (diff 밖) | 118 | 66-70 |
| `infrastructure/.../auth/JdbcWorkspaceRepository.kt` | 225 | 111-117 |
| `application/.../workspace/WorkspaceNameRules.kt` | 67 | 30-40 |
| `api/.../workspace/WorkspaceController.kt` | 116 | 107-114 |
| `api/.../error/GlobalExceptionHandler.kt` | 486 | 118-130 · 286-289 |
| `api/src/test/.../AuthEndpointReachTest.kt` | 614 | 262-265 |
| `api/src/test/.../WorkspaceEndpointReachTest.kt` | 593 | 228-240 · 365-382 · 559-569 |
| `api/src/test/.../support/ContractSpec.kt` | 489 | 273-290 · 330-339 |
| `api/src/test/.../ContractErrorBodyReachTest.kt` (diff 밖) | 345 | 233-237 |
| `api/src/test/.../support/RawHttp.kt` | 319 | 255-307 |
| `api/src/test/.../PasswordHashingBackpressureReachTest.kt` | 193 | 112-116 |
| `infrastructure/src/test/.../JdbcWorkspaceRepositoryTest.kt` | 259 | 202-219 |
| `contracts/easy-doc-v1.yaml` | 2439 | 299-302 · 495-512 · 1484-1492 · 1557-1565 · 2424 |

### 1.5 리뷰 중 저장소 상태 — HEAD 가 움직였다

| 시각 | 사실 |
|---|---|
| 12:49:03 | 리뷰 시작. HEAD = `cc7268c` |
| 리뷰 중 | HEAD = **`2c4a44f` 로 이동** |
| 13:03:28 | 리뷰 종료. HEAD = `2c4a44f` |

codex 자신이 요약에서 이 이동을 보고했다 — *"검토 중 HEAD가 외부 변경으로 2c4a44fd20aa로
이동했지만 관련 코드·계약 파일은 바뀌지 않았으며, Codex는 파일을 수정하지 않았다."*

이 에이전트가 독립 확인한 결과 **`2c4a44f` 는 병렬 레인(`contract-keeper`)의 문서 전용 커밋**이다.

```
2c4a44f docs(migration): workspaces 2단계 검증 — 파서 6노드·케이스 36건·음성 대조 10건 독립 재현
 docs/migration/_workspace/00_contract-keeper_test-plan.md                  |   6 +-
 docs/migration/_workspace/03_contract-keeper_workspaces-verification.md    | 368 +++++
 docs/migration/_workspace/03_kotlin-implementer_phase3-preflight.md        |   4 +-
```

`backend-kotlin/**` 와 `contracts/**` 는 한 줄도 바뀌지 않았다. **리뷰 대상은 오염되지 않았다.**
다만 `--base d04ad98` 는 실행 시점의 HEAD 를 끝점으로 삼으므로, 리뷰 후반부에 codex 가 본
작업 트리에는 이 문서 3개가 포함돼 있었다 — 리더가 지정한 `cc7268c` 종점과 **형식상 어긋나는
지점**이라 사실대로 남긴다.

**codex 가 작업 트리를 오염시켰는가: 아니다.** focus text 가 "변이 테스트를 했으면 원상복구하고
`git status --porcelain` 을 찍어 리뷰 전과 같음을 보여라" 를 요구했고, codex 는 마지막에
`git status --porcelain=v1` 을 실행했다. 리뷰 종료 후 이 에이전트가 독립 확인한 결과
**tracked 수정 0건**이다. 늘어난 작업 트리 항목 하나는 병렬 레인 산출물이며 codex 와 무관하다.

| 항목 | 출처 |
|---|---|
| `docs/migration/_workspace/reviews/03_security-workspaces_privacy-gate.md` (??) | 병렬 레인 `privacy-gate` |

### 1.6 실행 명령 전문

```bash
SP=<스크래치패드>
FOCUS="$(cat "$SP/focus_22.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base d04ad98 --dry-run "$FOCUS"

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base d04ad98 "$FOCUS"
```

헬퍼로 전개된 명령(스크립트가 stderr 에 찍은 것):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base d04ad98 '<focus text 전문>'
```

산출물 파일은 회차 고유 이름을 썼다(`focus_22.txt` · `codex_out_22.txt` · `codex_err_22.txt` ·
`exit_22.txt` · `start_22.txt` · `end_22.txt`). 스크래치패드에 이전 회차의 `codex_exit.txt`·
`codex_out.txt`(8/18) 가 남아 있어 접미사 없는 이름을 쓰면 **옛 회차 파일을 이번 결과로 오독**할
수 있었다 — 이 회차는 그 함정을 피했다.

### 1.7 민감 데이터 미포함 확인

프롬프트에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. 인용한 수치(1.031배,
250ms, 문턱 2.0, 50자)는 계약 문면과 직전 산출물의 **합성 계정 실측 통계**이고 자격증명 값이
아니다. 계약 조항 인용은 공개 스펙 파일(`contracts/easy-doc-v1.yaml`)의 문면이다.

---

## 2. 전달한 프롬프트 전문 (focus text)

아래는 codex 에 전달한 focus text **전문**이다. 요약·발췌가 아니다.

````text
## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot(`backend-kotlin/`)로 교체하는 중이다.
제품 동작과 개인정보 보호 정책은 보존해야 하지만 **Python 출력을 정답으로 삼지 않는다** —
Python은 폐기 대상이고 판정 기준은 요구사항·계약·정책 불변식이다. 정본은 계약 파일
`contracts/easy-doc-v1.yaml` 하나이며, 값(상한 숫자·`detail` 문구·헤더 값)은 계약에 있고
구현·테스트는 그것을 **읽어야** 한다(전사하면 계약이 아니라 구현이 계약이 된다).

이번 리뷰 대상은 Phase 3의 두 덩어리다. ⓐ 직전 게이트(21) 지적에 대한 조치 배치,
ⓑ 계약 정정 1건, ⓒ `/workspaces` 네 오퍼레이션 신규 구현.

## 지켜야 하는 조건 (채점 기준)

이것들이 지켜져야 한다. "확인해 달라"가 아니라 **위반을 찾아라.**

1. **소유권 은닉** — 다른 사용자의 자원에 접근하면 403이 아니라 **404**다. 자원의 존재
   자체가 새면 안 된다 — 본문·헤더·상태 코드뿐 아니라 **응답 시간**으로도 새면 안 된다.
   깨지면 타 사용자 데이터의 존재가 열거 가능해진다(즉시 중단 기준).
2. **오류 본문** — 모든 오류 응답은 `{"detail": ...}`다. Spring 기본 `ProblemDetail`이
   노출되면 안 된다. JSON 필드는 snake_case다. 오류 본문이 사용자 입력값을 되비추면 안 된다.
3. **사적 응답 헤더** — `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`가
   빠지는 응답 경로가 없어야 한다.
4. **계약이 정본** — 상한(작업 공간 이름 길이 등)·`detail` 예시 문구·경로 변수 이름은
   계약에서 읽어야 하고 코드·테스트에 복제돼 있으면 안 된다. 계약이 침묵하는 자리를
   구현이 정했으면 그 사실이 기록돼야 한다.
5. **core 모듈은 Spring·DB 비의존** — 모듈 경계(`core` ← `application` ← `infrastructure`/`api`).
6. **로그에 문서 본문·개인정보 금지** — 로깅은 ID·길이·상태까지만.
7. **선언 범위 = 실제 도달** — "전역"·"모든"·"항상"을 선언한 장치는 그 선언만큼 실제로
   닿아야 한다. 닿지 않는 경로가 있으면 그 장치는 **작동하는 것처럼 보이면서 아무것도
   재지 않는다.** 검사가 자기 자신에게서 나온 기준과 대조하거나, 대상 0건인데 성공으로
   끝나거나, 떼어내도 아무 테스트가 깨지지 않는 장치가 여기 해당한다.

## 대상 — 읽어라 (내용을 여기 옮겨 적지 않았다)

리뷰 범위는 `d04ad98..cc7268c` (21 커밋)이다. 아래 파일을 **직접 읽어라.**

**계약·명세 (정본)**
- `contracts/easy-doc-v1.yaml` — 특히 `:654-661`(파싱 거절 열거 + `x-405-reclassification`),
  `:1249-1387`(`/workspaces` 네 오퍼레이션), `x-input-limits`(`:307-`),
  `x-error-body-universality`(`:495-` 부근 `x-unmeasured` 포함),
  `WorkspaceNameRequest`(`:1983` 부근 `x-service-constraint`)
- `docs/migration/_workspace/03_contract-keeper_workspaces-test-spec.md` — 케이스 명세
  (P-16~P-21 파서 확장, N-11~N-18 음성 대조, WL/WC/WR/WD 케이스표)
- `docs/migration/_workspace/03_contract-keeper_escalation-503.md` — escalate 선택지 표

**구현 산출물 (구현자의 주장. 검증 대상이지 근거가 아니다)**
- `docs/migration/_workspace/03_kotlin-implementer_auth-fixes2.md` — ⓐ 배치의 주장
- `docs/migration/_workspace/03_kotlin-implementer_workspaces.md` — ⓒ 단위의 주장
  (음성 대조 13건, 소유권 404 시간비 1.031배, 갈림 3건 D-1~D-3, 미결 8건)

**Kotlin 코드**
- `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/workspace/Workspace.kt`
- `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/workspace/` 전체
  (`WorkspaceService.kt`·`WorkspaceNameRules.kt`·`WorkspaceMessages.kt`)
- `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthPorts.kt`
- `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/JdbcWorkspaceRepository.kt`
- `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/Argon2PasswordHasher.kt`
- `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/workspace/` (`WorkspaceController.kt`·`WorkspaceDtos.kt`)
- `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndpoints.kt`
- `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt`
- `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/JsonRequestStrictnessConfig.kt`
  (게이트 21에서 `JsonCoercionConfig`에서 개명됨)

**테스트**
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceContractTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReachTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ContainerRejectionCoverageContractTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthenticationCoverageContractTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PasswordHashLogLeakReachTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PasswordHashingBackpressureReachTest.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt` (계약 파서)
- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcWorkspaceRepositoryTest.kt`

대응 Python 참고 구현(정답이 아니라 참고): `app/api/workspaces.py`, `app/services/workspaces.py`.

## 질문 — 다섯 축

### ① 소유권 은닉이 실제로 성립하는가

- 네 오퍼레이션(`GET /workspaces`, `POST /workspaces`, `PATCH /workspaces/{id}`,
  `DELETE /workspaces/{id}`) 전부에서 타인 자원이 404인가, 아니면 403·401·500이 새는 경로가
  있는가? 예외 매퍼(`GlobalExceptionHandler`)가 중간에서 다른 상태를 만들지 않는가?
- 소유 조건이 **전부 SQL WHERE**에 있다고 주장한다. `JdbcWorkspaceRepository`의 다섯 메서드
  각각에서 그 조건이 실제로 있는가? 조건 없이 읽고 나서 Kotlin에서 비교하는 형태로 되돌아간
  경로, 또는 `ownerId`를 받지만 쓰지 않는 시그니처가 있는가?
- **시간 채널**: "없는 workspace"와 "타인의 workspace"의 응답 시간비가 1.031배라는 실측
  주장이 있다. 그 측정이 실제로 두 갈래를 구분할 수 있는 설계인가 — 문턱 2.0이 어떤 차이를
  통과시키는가, 워밍업·반복 횟수·이상치 처리가 측정을 무의미하게 만들지 않는가,
  DB 왕복이 아니라 캐시된 값을 재는 구조는 아닌가?
- 존재 여부가 **본문·헤더**로 새는가? 404와 409, 404와 422의 본문이 서로 다른 정보량을
  갖는가? `Allow` 헤더·`Location`·오류 `detail`이 자원 존재를 암시하는가?
- 유효한 토큰인데 그 계정이 삭제된 경우: FK 위반 → 500 경로가 있다고 등재돼 있다.
  그 500 응답이 스택트레이스·SQL·제약 이름·사용자 ID를 노출하는가? 이 경로로 타 계정의
  존재를 탐지할 수 있는가?

### ② 동시성·무결성

- `DELETE`의 "마지막 하나는 못 지운다" 판정은 **집합** 판정인데, 잠금이 그 사용자의 행
  전부에 `ORDER BY id FOR UPDATE`로 걸린다고 한다. 두 요청이 서로 다른 두 workspace를
  동시에 지울 때 둘 다 성공해 0개가 되는 창이 남는가? 잠금 획득과 개수 세기의 순서가
  뒤집힌 곳이 있는가? 트랜잭션 격리 수준이 이 판정을 실제로 지키는가?
- 잠금·검사·삭제가 **하나의 트랜잭션**인가? `@Transactional`이 프록시를 타지 않는 자리
  (자기 호출, private/internal, 인터페이스 밖)가 있는가?
- 문서가 있는 workspace 삭제는 409인데, 개수 확인과 DELETE 사이에 문서가 삽입되는 창을
  FK 위반으로 잡아 409로 옮긴다고 한다. 그 DELETE에서 터질 수 있는 제약이 정말 하나뿐인가?
  다른 제약 위반(또는 다른 원인의 `DataIntegrityViolation`)이 같은 경로로 흘러 409로
  둔갑하는가? cascade 설정이 문서를 조용히 지우는 경로는 없는가?
- 목록 정렬 `ORDER BY created_at, id`가 "첫 번째가 기본 작업 공간"이라는 계약을 실행마다
  같게 만드는가? `created_at`이 트랜잭션 시각이라 동점이 나는 조건에서 `id`(UUID인지
  순차인지 확인하라)가 뜻 있는 순서를 주는가?
- 이름 유일성(`uq_workspaces_user_id_name`)이 **정규화 후** 이름에 걸리는가, 원시 이름에
  걸리는가? 정규화가 서로 다른 두 입력을 같은 이름으로 만들면 유일 인덱스가 그것을 잡는가,
  아니면 500이 되는가?

### ③ 계약 준수

- `name`에 Bean Validation 애너테이션이 **0개**이고 서비스 층에서 정규화 **후** 판정한다고
  한다. DTO·컨트롤러·설정 어디에도 `jakarta.validation` 애너테이션이 없는가? 정규화(제어문자
  제거 → trim)와 길이 판정의 순서가 계약의 `measured_on: normalized`와 일치하는가?
  길이를 코드 포인트로 세는가 UTF-16 단위로 세는가 — 계약이 무엇을 요구하는가?
- 공백만으로 된 이름(`"   "`)이 422이고 `detail`이 **문자열**인가(X-F10)? 그 값이 계약의
  예시에서 오는가 코드에 적혀 있는가?
- 인증 우선순위(X-A3): 인증 실패와 본문 오류·경로 변수 오류가 동시에 성립할 때 **401이
  먼저**인가? 두 축(본문 오류·경로 변수 오류) 모두에서 성립하는가, 아니면 필터·인터셉터
  순서 때문에 422가 먼저 나오는 경로가 있는가?
- 404/409/422 응답 본문이 계약이 정한 모양인가? 사용자가 보낸 값(이름 문자열, 경로의 UUID)이
  `detail`에 되반사되는 자리가 있는가?
- `ContainerRejectionCoverageContractTest`가 열거자와 계약을 **집합**으로 대조한다고 한다.
  그 대조가 한쪽 방향만 보는가(계약에 있는데 코드에 없는 것만, 또는 그 반대)? 집합이
  비어 있어도 통과하는가? 열거자가 자기 자신을 기준으로 삼는 순환 구조인가?
- 계약 파서 확장 P-16(`allOf`의 `required` 합성)·P-17(경로 인라인 예시)이 실재하는가 —
  `ContractSpec.kt`에서 그 코드를 짚어라. 파서가 못 읽은 노드를 조용히 건너뛰고 통과하는
  경로(`?:` 기본값, `orEmpty()`, null 무시)가 있는가? 그러면 계약을 바꿔도 테스트가
  초록으로 남는다.
- `03_kotlin-implementer_workspaces.md` §5 ①은 "지침 8(`handleHandlerMethodValidationException`)은
  이 단위에 대상이 없다"고 적는다. 그 주장이 사실인가 — 그 핸들러에 실제로 도달하는 요청을
  이 단위에서 만들 수 있는가? "대상 없음"이 검증 회피가 아닌가?

### ④ auth-fixes2 배치 (게이트 21 조치)

- **C-1**: 보호 경로 자동 발견이 경로만이 아니라 `(method, path)` 쌍으로 투영된다고 한다.
  같은 경로의 다른 메서드가 보호를 우회하는가? `POST /health`처럼 계약에 없는 (메서드, 경로)를
  추가하면 그 테스트가 실제로 빨강이 되는가 — 코드를 읽고 판단하고, 가능하면 변이를 실행해
  확인하라(실행했다면 **반드시 원상복구**하고 `git status --porcelain`으로 증명하라).
- **M-1**: 더미 PHC(존재하지 않는 계정에 대해 시간을 맞추기 위한 가짜 해시)를 기동 시
  난수로 만든다. 두 번 기동하면 실제로 다른 값이 나오는가? 그 더미의 검증 비용이 실제
  계정 해시의 검증 비용과 **동일한 파라미터**인가(m, t, p, salt 길이)? 비용이 다르면
  타이밍으로 계정 존재가 새고, 그것이 이 조치가 막으려던 것이다.
- **R-2**: 해시 대기 상한을 5s → 250ms 세마포어로 바꿨다. 상한 초과 시 어떤 상태 코드·본문이
  나가는가, 그것이 계약에 있는가? 대기 중 스레드가 인터럽트/타임아웃될 때 세마포어 permit이
  누수되는가? 부하 상황에서 이 배압이 인증 실패와 구분 가능한 신호를 만들어 계정 열거에
  쓰일 수 있는가?
- **L-1**: `PasswordHashLogLeakReachTest`가 **탐지형**이라고 한다. 깨진 PHC 문자열을 로그로
  흘리는 코드를 넣으면 실제로 빨강이 되는가(양성), 정상 상태에서 초록인가(음성)? 로그 캡처가
  실제로 그 로거·그 레벨·그 어펜더를 붙잡는가, 아니면 아무것도 캡처하지 않고 통과하는가?
- **C-2**: 필드 누락과 `null`을 그 필드를 지목하는 `missing`으로 만든다고 한다. 실제 응답을
  만들어 보라(테스트 코드로 확인 가능하면 그것으로) — 누락·null·루트가 배열인 JSON 세 경우의
  응답이 계약과 맞는가? 루트 배열에서 **타입 이름이 노출되지 않는가**(Jackson의 클래스명·
  패키지명이 `detail`에 새면 내부 구조가 드러난다)? `field_missing`이 실재하는 값인가
  선언만 있는가?
- `JsonCoercionConfig` → `JsonRequestStrictnessConfig` 개명이 설정 키·프로퍼티·문서 참조와
  일관되게 바뀌었는가, 아니면 옛 이름을 가리키는 죽은 참조가 남았는가?

### ⑤ 선언 범위 대 실제 도달

- 이 21개 커밋이 **새로 세우거나 넓힌** 게이트·불변식·규칙이 있는가? 각각에 대해:
  그 장치가 **어디서 도는가**(로컬 전용인가, CI 잡에 배선돼 있는가, 아무 데서도 안 도는가)?
  `.github/workflows/` 에서 실제 실행 경로를 짚어라.
- "전역"·"모든 응답"·"항상"을 선언하는 자리가 새로 생겼는가? 그 선언의 강제 수단이 닿지
  않는 요청 경로(필터 앞, 컨테이너가 만드는 응답, ERROR 디스패치, 비동기 경로)가 있는가?
- **은폐형**을 경계하라 — 무시 패턴·억제 주석(`@Suppress`, detekt baseline, ktlint
  disable)·예외 조항·`.gitignore` 항목이 이번 배치에서 늘었는가? 늘었다면 그 범위가
  근거보다 넓어서 앞으로 같은 사고를 **보이지 않게** 만드는가?
- 계약 정정 `4a25a7c`(파싱 단계 거절 7종 → 6종, 「알 수 없는 메서드 405」를 도달 쪽으로
  재분류)가 계약의 **다른 자리와 정합**한가? `x-unmeasured`, `x-error-body-universality`의
  갈래 번호(④가 아니라 ①), 도달/미도달 개수를 세는 다른 조항, 그리고 그 개수를 읽는
  테스트가 전부 같은 수를 보는가? 한 곳만 고치고 다른 곳이 옛 수를 들고 있는가?
- 구현자가 **음성 대조 13건 전건 빨강**을 주장한다(계약 값 변이 10건 N-11a~N-19 + 구현
  변이 3건). 이 주장을 재현할 수 있는 만큼 재현하라. Gradle 실행이 막히면(락 파일 생성
  거부, 네트워크 부재 등) **재현하지 못했다고 그대로 보고하고**, 대신 `git show`·정적 독해·
  `jshell`로 확인 가능한 것을 확인하라 — 재현하지 못한 것을 재현한 것처럼 쓰지 마라.
  특히: 각 변이가 **그 케이스만** 빨강으로 만든다는 주장(과잉 결합 없음)이 코드 구조상
  성립하는가? 어떤 변이를 넣어도 초록으로 남는 테스트가 있는가?
- 이 배치에서 추가된 테스트 중 **떼어내도 아무것도 깨지지 않는 것**, 또는 구현을 그대로
  복사해 항상 통과하는 구조인 것이 있는가?

## 산출물 요구

- 지적마다 **파일 경로와 라인 번호**를 달아라.
- 실행해서 확인한 것과 코드를 읽고 추론한 것을 **구분해서** 표시하라.
- 변이 테스트를 했으면 원상복구하고 `git status --porcelain`을 찍어 리뷰 전과 같음을 보여라.
- 지적할 것이 없는 축이 있으면 "없다"고 적어라 — 채우지 마라.
````

---

## 3. codex 원문 출력 (무편집)

> 아래는 codex 가 돌려준 출력 **전문**이다. 한 글자도 고치지 않았다 — 표현 다듬기·
> 중복 병합·오탐 주석 삽입 어느 것도 하지 않았다. sha256 `42a494dc00a2c5cd8fd3241dcfe84b1542eb45cce91acbdcf8596ca586fa51a6`.

````text
# Codex Adversarial Review

Target: branch diff against d04ad98
Verdict: needs-attention

NO-SHIP. 인증 무효화 실패와 계약 정본 위반을 포함해 8건의 차단 위험이 있다. 지적은 모두 cc7268c에 고정한 정적 확인/추론이다. 선택한 Gradle 테스트는 샌드박스가 ~/.gradle의 .lck 생성을 거부해 시작되지 않았고 변이도 실행하지 못했다. git diff --check d04ad98..cc7268c는 통과했다. 검토 중 HEAD가 외부 변경으로 2c4a44fd20aa로 이동했지만 관련 코드·계약 파일은 바뀌지 않았으며, Codex는 파일을 수정하지 않았다. SQL 소유 조건, 삭제 트랜잭션, core 경계, 개인정보 로깅 및 세마포어 permit 경로에서는 별도 차단점을 찾지 못했다.

Findings:
- [high] 삭제된 계정의 토큰이 인증 경계를 계속 통과한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt:105-116)
  [정적 확인] AuthenticationInterceptor는 authService.authenticate() 결과를 바로 인증 사용자로 설정하지만, 이 함수는 JWT만 검증한다. 삭제 계정 확인은 별도 readUser()에만 있으며 새 /workspaces 경로는 호출하지 않는다(AuthenticationInterceptor.kt:66-70). 사용자 삭제 시 workspace가 cascade되므로 GET은 200 빈 목록, POST는 FK 위반→StorageException→500(JdbcWorkspaceRepository.kt:111-117), PATCH/DELETE는 404가 된다. 계약 easy-doc-v1.yaml:299-302,1484-1492의 계정 삭제도 동일한 401이라는 요구와 정면으로 충돌한다. SQL·스택은 고정 오류 본문 때문에 노출되지 않지만 폐기된 자격증명이 승인되고 삭제 여부가 상태 코드로 구분된다.
  Recommendation: 모든 보호 요청에서 JWT 검증 직후 사용자 존재를 확인하고, 삭제 계정은 컨트롤러 진입 전에 무효 토큰과 동일한 401·본문·헤더로 종료하라. 실제 DB에서 토큰 발급 후 사용자를 삭제하고 네 workspace 오퍼레이션 모두를 검증하는 회귀를 추가하라.
- [high] 구현이 정본 계약을 읽지 않고 정책 값을 복제한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/workspace/WorkspaceNameRules.kt:30-40)
  [정적 확인] 이름 상한 50과 두 detail 문구가 애플리케이션 코드에 그대로 박혀 있다. 추가로 WorkspaceController.kt:107-114는 사적 헤더 값을 복제하고, GlobalExceptionHandler.kt:286-289와 AuthEndpointReachTest.kt:262-265는 field_missing의 msg/type을 서로 똑같이 하드코딩한다. 후자는 계약 예시만 바꿔도 구현과 테스트가 함께 옛 값으로 초록일 수 있다. N-11 변이는 불일치를 탐지할 뿐 구현이 계약을 소비하게 만들지 않으므로, 계약 파일 하나가 정본이라는 필수 조건을 만족하지 않는다.
  Recommendation: YAML에서 빌드 시 타입 안전한 정책 산출물을 생성하거나 infrastructure에서 파싱·검증한 값을 application/api에 주입하라. 구현과 테스트의 상한·문구·헤더·경로 변수 리터럴을 제거하고 계약 변경 하나만으로 런타임 값이 바뀌는지를 검증하라.
- [high] 소유권 시간 채널 게이트가 거의 2배의 지속적 격차도 허용한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/WorkspaceEndpointReachTest.kt:228-240)
  [정적 추론] 테스트는 경로당 21표본의 중앙값 비율이 2.0 미만이면 통과한다(WorkspaceEndpointReachTest.kt:365-382,559-569). 공격자는 반복 평균으로 훨씬 작은 지속 격차도 증폭할 수 있는데, 이 게이트는 10~90% 차이를 모두 승인한다. ‘행을 읽은 뒤 소유자를 비교’하는 의도적 취약 구현을 넣어 이 테스트가 실패하는 음성 대조도 없다. 현재 SQL 소유 조건 자체는 안전해 보이지만, 시간까지 은닉한다는 선언을 이 테스트가 보장하지 못한다.
  Recommendation: 읽은 뒤 소유자를 비교하는 구현을 음성 대조로 만들어 반드시 실패시키고, 충분한 반복과 분포/신뢰구간 기반의 위협 모델상 허용치를 사용하라. 동시에 SQL 또는 query trace로 owner 조건과 동일 DB 왕복 구조를 강제하라.
- [medium] P-16 계약 파서가 읽지 못한 allOf 갈래를 조용히 버린다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:273-290)
  [정적 확인] requiredOf는 allOf 항목을 filterIsInstance<Map<*, *>>()로 거른다. 기존 유효 갈래가 하나라도 required를 제공하면 추가된 비매핑 갈래는 사라지고 non-empty 방어도 통과한다. 이는 주석의 ‘아무 갈래도 무시하지 않는다’는 주장과 반대다. 같은 fail-open 형태가 pathParameters의 filterIsInstance에도 있다(ContractSpec.kt:330-339). 따라서 계약 구조가 손상되거나 지원하지 않는 노드가 추가돼도 파서 게이트가 초록일 수 있다.
  Recommendation: 모든 allOf 항목이 매핑이며 정확히 지원하는 schema/ref 형태인지 먼저 검증하고 하나라도 아니면 실패하라. 스칼라·빈 매핑·지원하지 않는 갈래를 주입하는 음성 대조와 OpenAPI 구조 검증을 CI에 추가하라.
- [medium] 계약 정정이 실제 오류 본문 측정 범위와 모순된다 (contracts/easy-doc-v1.yaml:495-512)
  [정적 확인] 계약은 컨테이너 응답 6종 중 1종만 측정했고 5종이 미측정이라고 선언한다. 그러나 ContractErrorBodyReachTest.kt:233-237의 @EnumSource는 RawHttp.kt:255-307에 있는 6종 전부를 실행하고, 공용 단언은 상태·JSON Content-Type·detail 단일 키·입력 비반사를 검사한다. 같은 계약의 x-improvements도 easy-doc-v1.yaml:2424에서 여전히 ‘파싱 단계 거절 7종’이라 쓴다. 4a25a7c가 수와 분류를 일부만 고쳐 정본의 게이트 상태가 사실과 다르다.
  Recommendation: x-unmeasured와 OQ-1의 7종 서술을 실제 6종 전건 측정 상태에 맞춰 고치고, 측정 목록을 산문이 아닌 기계 판독 가능한 집합으로 만들어 enum 및 본문 테스트와 정확히 대조하라.
- [medium] 배압 회귀가 한 계정 집단만 과부하돼도 균일하다고 통과한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/PasswordHashingBackpressureReachTest.kt:112-116)
  [정적 확인] 테스트는 과부하 응답을 계정 라벨로 묶은 뒤 모든 본문의 distinct 개수만 1인지 본다. byAccount에 ‘있는 이메일’과 ‘없는 이메일’이 모두 존재하는지는 단언하지 않는다. 따라서 한 집단만 500이고 다른 집단은 전부 401이어도 본문 하나뿐이라 통과하며, 바로 그 상태 코드 분포가 계정 존재 열거 신호가 된다.
  Recommendation: 과부하 부분집합의 키가 두 계정 라벨을 모두 정확히 포함하고 각 집단에 최소 한 건이 있음을 단언하라. 반복된 균형 부하에서 상태 코드 분포와 응답 시간도 집단별로 비교하라.
- [medium] 동시 삭제 테스트가 교착·타임아웃·500을 정상 거절로 오인한다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcWorkspaceRepositoryTest.kt:202-219)
  [정적 확인] 각 삭제 결과를 runCatching(...).isSuccess 하나로 축약하므로 두 번째 요청이 ConflictException이 아니라 교착, 타임아웃, StorageException으로 실패해도 false가 된다. 다른 요청이 성공해 한 행이 남으면 현재 두 단언을 모두 만족한다. 또한 barrier는 서비스 호출 전만 맞춰 잠금/개수 판정의 실제 중첩을 보장하지 않는다. 잠금 구현이 퇴행해 스케줄링상 우연히 직렬 실행돼도 초록일 수 있다.
  Recommendation: 결과 객체를 보존해 정확히 한 성공과 last-workspace ConflictException 한 건을 단언하라. 두 SQL 세션을 latch로 개수 판정 지점에 고정하는 결정적 동시성 시험을 추가하고 반복 실행하라.
- [medium] 설계된 배압이 미처리 서버 오류와 ERROR 로그로 출하된다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt:118-130)
  [정적 확인] PasswordHashingOverloadedException은 의도된 용량 제어인데 최종 Exception 핸들러로 들어가 매 요청 ERROR 로그와 HTTP 500을 만든다. 계약 easy-doc-v1.yaml:1557-1565가 열거한 InternalError 원인에는 이 상황이 없고, escalation 문서도 이 불일치와 500/503 결정 미완료를 명시한다. 부하 시 정상적인 배압이 서버 결함으로 관측되어 사용자 재시도 판단과 운영 경보를 왜곡하고 로그 폭주를 만든다.
  Recommendation: 출시 전에 500/503 및 detail에 대한 계약 결정을 확정하고 예외를 명시적으로 매핑하라. 용량 압력을 예상 가능한 로그 수준·메트릭으로 분리하고 catch-all ERROR 경로를 타지 않게 하라.

Next steps:
- 삭제 계정 인증을 전역 보호 경계에서 fail-closed로 고치고 네 workspace 오퍼레이션의 실제 DB 회귀를 추가한다.
- 계약 YAML에서 생성·주입되는 단일 정책 산출물로 상한·문구·헤더·경로 변수를 통합한다.
- 배압 응답 결정을 확정한 뒤 계정 양 집단, 시간 분포, 동시 삭제 예외 종류를 검증하도록 게이트를 강화한다.
- 계약의 6종 측정 상태와 P-16/P-21 파서를 fail-closed로 정정한다.
- 쓰기 가능한 Gradle 환경에서 선택 테스트, 음성 변이, ./gradlew build --no-daemon을 재실행한다. 현재 결과를 성공 실행으로 간주하면 안 된다.
````

---

## 4. 정리(가공) — Claude 색인

> **이 구획은 §3 원문과 다른 성격이다.** 여기서도 옳고 그름은 판정하지 않는다 — 심각도는
> codex 가 붙인 값을 그대로 옮겼고, 재분류·병합·오탐 표시를 하지 않았다. 이 표는 §3 을
> **찾아보기 쉽게** 만든 색인일 뿐이며, 판정은 `03_workspaces_cross.md` 의 몫이다.

### 4.1 지적 8건 목록 (codex 심각도 그대로)

| # | codex 심각도 | 지적 | codex 근거 파일·라인 | codex 자기표시 |
|---|---|---|---|---|
| 1 | **high** | 삭제된 계정의 토큰이 인증 경계를 계속 통과한다 | `application/.../auth/AuthService.kt:105-116` (부수 인용 `AuthenticationInterceptor.kt:66-70` · `JdbcWorkspaceRepository.kt:111-117` · 계약 `:299-302,1484-1492`) | 정적 확인 |
| 2 | **high** | 구현이 정본 계약을 읽지 않고 정책 값을 복제한다 | `application/.../workspace/WorkspaceNameRules.kt:30-40` (부수 인용 `WorkspaceController.kt:107-114` · `GlobalExceptionHandler.kt:286-289` · `AuthEndpointReachTest.kt:262-265`) | 정적 확인 |
| 3 | **high** | 소유권 시간 채널 게이트가 거의 2배의 지속적 격차도 허용한다 | `api/src/test/.../WorkspaceEndpointReachTest.kt:228-240` (부수 인용 `:365-382,559-569`) | **정적 추론** |
| 4 | medium | P-16 계약 파서가 읽지 못한 `allOf` 갈래를 조용히 버린다 | `api/src/test/.../support/ContractSpec.kt:273-290` (부수 인용 `:330-339` = `pathParameters`) | 정적 확인 |
| 5 | medium | 계약 정정이 실제 오류 본문 측정 범위와 모순된다 | `contracts/easy-doc-v1.yaml:495-512` (부수 인용 `ContractErrorBodyReachTest.kt:233-237` · `RawHttp.kt:255-307` · `easy-doc-v1.yaml:2424`) | 정적 확인 |
| 6 | medium | 배압 회귀가 한 계정 집단만 과부하돼도 균일하다고 통과한다 | `api/src/test/.../PasswordHashingBackpressureReachTest.kt:112-116` | 정적 확인 |
| 7 | medium | 동시 삭제 테스트가 교착·타임아웃·500 을 정상 거절로 오인한다 | `infrastructure/src/test/.../JdbcWorkspaceRepositoryTest.kt:202-219` | 정적 확인 |
| 8 | medium | 설계된 배압이 미처리 서버 오류와 ERROR 로그로 출하된다 | `api/.../error/GlobalExceptionHandler.kt:118-130` (부수 인용 계약 `:1557-1565` · escalation 문서) | 정적 확인 |

**심각도별 건수: high 3 · medium 5 · low 0 = 8건.** codex 판정 `needs-attention` / "NO-SHIP."

### 4.2 리더가 지정한 축 5개에 대한 매핑

축은 리더의 1단계 호출 지정이고, 아래는 codex 지적이 어느 축에서 나왔는지 **위치만** 표시한 것이다.
"codex 가 이 축을 통과시켰다"는 뜻이 아니다 — codex 는 축별 합격 판정을 내리지 않았다.

| 축 | codex 지적 | codex 가 "별도 차단점 없음" 이라 요약에 적은 것 |
|---|---|---|
| ① 소유권 은닉 | #1(삭제 계정 토큰 경로) · #3(시간 채널 게이트) | **SQL 소유 조건** |
| ② 동시성·무결성 | #7(동시 삭제 테스트) | **삭제 트랜잭션** |
| ③ 계약 준수 | #2(정책 값 복제) · #4(파서 fail-open) · #5(측정 범위 모순) · #8(배압 500·계약 미열거) | — |
| ④ auth-fixes2 배치 | #6(배압 회귀 집단 미단언) · #8(배압 매핑) · #2 일부(`field_missing` msg/type 하드코딩) | **세마포어 permit 경로** |
| ⑤ 도달 범위 | #3 · #4 · #5 · #6 · #7 (전부 "장치가 도는 것처럼 보이나 재지 못한다" 형태) | **core 경계** · **개인정보 로깅** |

codex 요약 원문(§3 4번째 줄)에 있는 그대로: *"SQL 소유 조건, 삭제 트랜잭션, core 경계,
개인정보 로깅 및 세마포어 permit 경로에서는 별도 차단점을 찾지 못했다."*

### 4.3 리더가 명시한 항목 중 codex 가 다루지 않은 것

지적이 없었다는 사실을 그대로 적는다. **Claude 가 대신 지적을 만들어 채우지 않았다.**

| 리더 지정 항목 | codex 출력에서의 취급 |
|---|---|
| ① 목록·삭제·PATCH 의 SQL WHERE 우회 경로 | 지적 없음 — 요약에 "SQL 소유 조건 … 별도 차단점 없음" |
| ① 1.031배 주장 재현 | **재현 안 됨** — Gradle 미실행(§5). #3 은 테스트 코드의 문턱 설계에 대한 정적 추론이다 |
| ② FK 409 방벽 | 개별 지적 없음 (#7 은 테스트 쪽) |
| ② 목록 정렬 결정성 `created_at, id` | 지적 없음 |
| ③ F3 `name` DTO 애너테이션 0 | 애너테이션 부재 자체에 대한 지적 없음. #2 는 상한 값이 코드에 박힌 점 |
| ③ X-F10 공백만 422 문자열 | 지적 없음 |
| ③ X-A3 인증 우선순위(두 축) | 지적 없음 |
| ③ 404/409/422 본문 값 미반향 | 지적 없음 — #1 에 "고정 오류 본문 때문에 SQL·스택은 노출되지 않는다" 서술 |
| ③ P-17 경로 인라인 예시 | 지적 없음 (#4 는 P-16 `allOf` 와 `pathParameters`) |
| ③ 지침 8 "대상 없음" 처리 정직성 | 지적 없음 |
| ④ C-1 `POST /health` 변이 → 빨강 | **변이 미실행**(§5). 지적 없음 |
| ④ M-1 난수 더미 두 번 조립 다름·비용 동일 | 지적 없음 |
| ④ C-2 누락·null·루트 배열 실제 응답 | 지적 없음. #2 가 `field_missing` 의 msg/type 하드코딩만 언급 |
| ④ 루트 배열 타입명 미노출 | 지적 없음 |
| ⑤ 음성 대조 13건 재현 | **전건 미재현**(§5) |
| ⑤ 은폐형 확대(`@Suppress`·baseline·`.gitignore`) | 지적 없음 |

### 4.4 전제 확인 필요

이 회차에는 **기계적으로 확인 가능한 전제 오류가 없었다** — §1.4 대로 인용 파일 14개가 전부
실재하고 라인 범위도 파일 길이 안이다. 다만 아래 둘은 `migration-reviewer` 가 내용까지
확인해야 할 자리로 남긴다(Claude 는 옳고 그름을 판정하지 않았다).

- **#5** 는 계약 `:2424` 가 *"파싱 단계 거절 7종"* 이라 쓴다고 주장한다. 이 배치의 정정
  커밋 `4a25a7c` 가 `:654-661` 을 6종으로 고쳤으므로, **같은 계약 안 두 자리의 수가
  일치하는지**가 이 지적의 성립 조건이다. 내용 대조는 하지 않았다.
- **#1** 은 계약 `:299-302,1484-1492` 가 *"계정 삭제도 동일한 401"* 을 요구한다고 주장한다.
  구현 산출물 `03_kotlin-implementer_workspaces.md` §5 ⑥ 은 같은 경로를 **"결함으로 단정하지
  않고 등재"** 로 다뤘다 — 계약 해석이 갈리는 자리다. 양쪽 근거가 병기돼야 한다.

### 4.5 codex 가 낸 Next steps 5건 (원문 §3 말미, 재기술 없이 위치만 표시)

`03_workspaces_cross.md` 가 권고 열을 채울 때의 입력이다. 이 에이전트는 채택 여부를
판정하지 않았다.

---

## 5. 미실행·실패 항목

**이 회차의 가장 큰 제약: 동적 검증이 하나도 돌지 않았다.** codex 는 이 사실을 스스로
요약과 Next steps 에 명시했다 — *"선택한 Gradle 테스트는 샌드박스가 `~/.gradle`의 `.lck`
생성을 거부해 시작되지 않았고 변이도 실행하지 못했다."* / *"현재 결과를 성공 실행으로
간주하면 안 된다."*

| 항목 | 상태 |
|---|---|
| `./gradlew :api:test --tests kr.easydoc.api.WorkspaceContractTest ...` | **실패 2회** (exit 1). 샌드박스가 `~/.gradle` 의 `.lck` 파일 생성을 거부 |
| 음성 대조 13건 재현 (N-11a~N-19 + 구현 변이 3) | **미실행** — 위 Gradle 제약 |
| C-1 `POST /health` 변이 | **미실행** — 위 Gradle 제약 |
| 1.031배 시간비 실측 재현 | **미실행** — 위 Gradle 제약 |
| M-1 난수 더미 두 번 기동 대조 | **미실행** — 위 Gradle 제약 |
| `rg -n "fun assertContractErrorBody" ...` | 실패 1회 (exit 1) — 패턴 미매치로 보이며 codex 가 후속 명령으로 우회 |
| `rg --files -g 'AGENTS.md' ... && git ...` | 실패 1회 (exit 1) — 리뷰 착수 단계의 저장소 탐색, codex 가 개별 명령으로 재시도 |
| `git diff --check d04ad98..cc7268c` | **통과** (codex 요약에 기재) |

**이것은 게이트 21 과 같은 제약의 재발이다.** 게이트 21 회차에서도 codex 는 Gradle lock 생성
거부로 테스트를 실행하지 못했고, 리더의 이번 지시도 그 사실을 전제로 *"`git show`+정적·jshell 로
가능한 것만"* 이라 적었다. codex 는 그 범위를 지켰고, **지적 8건 전부에 `[정적 확인]` 또는
`[정적 추론]` 표시를 스스로 달았다.** 어느 지적도 실행 증거를 갖지 않는다.

**codex 리뷰 누락은 없다** — 리뷰 자체는 종료 코드 `0` 으로 완주했고 출력이 비어 있지 않다.
누락된 것은 리뷰가 아니라 **리뷰 안의 동적 재현**이다. 이 둘을 섞어 읽으면 안 된다.

**재시도하지 않은 이유**: §7 의 "1회 재시도" 는 도구 실패(exit 3·4·5) 대상이다. 이 회차는
exit `0` 이고 codex 가 응답을 완주했으므로 재시도 조건이 아니다. Gradle 제약은 codex 샌드박스의
쓰기 권한 문제이고 재호출로 달라지지 않는다 — 같은 인자로 다시 부르면 같은 제약에 걸린다.
**동적 재현이 필요하면 그것은 `kotlin-implementer`·`parity-verifier` 레인이 쓰기 가능한
환경에서 돌려야 할 일**이며, 이 에이전트가 codex 를 대신해 실행하고 그 결과를 codex 리뷰로
적는 것은 §7 이 금지한 행위다.
