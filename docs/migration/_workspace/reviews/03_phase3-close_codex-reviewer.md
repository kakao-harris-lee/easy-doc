# 게이트 24 · 1단계 codex 독립 리뷰 — `03_phase3-close`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`03_phase3-close_cross.md`)의 몫이다.

**어간**: `03_phase3-close` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 15:57:24 KST (`2026-08-19T06:57:24Z`) |
| 종료 시각 | 2026-08-19 16:10:39 KST (`2026-08-19T07:10:39Z`) |
| 소요 | **13분 15초** |
| 대상 범위 | **`9b9d8ad..2a4523d`** — 커밋 7개, 변경 파일 23개 |
| 모드 | `adversarial` (focus text 필수 — 탐지 장치 4종의 도달 범위·401 시간 채널·Phase 종료 재료라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base 9b9d8ad`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-mszqoi3q-n913h8` |
| codex thread / turn | `01a018cf-7dad-7b22-b5f7-42ebd50716bf` / `01a018cf-7f03-73e2-b8ab-61baddb90b00` |
| job 로그 | `~/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-mszqoi3q-n913h8.log` |
| codex 판정 | **`needs-attention`** — "NO-SHIP: 탐지 장치가 초록인 채 실제 403과 민감 DTO를 놓칠 수 있고, 401 회귀는 계약이 요구하는 시간 균일성을 검증하지 않는다." |
| codex 실행 셸 명령 | **54건 시작 / 54건 종료** — exit 0 **50** · exit 1 **3** · exit 2 **1** (실패 4건 목록은 §5) |
| focus text 크기 | 17,161 바이트 (sha256 `0a5a1620c91ebcf3aea7bbeb21451226ef629914aacaf755a4836dd0eb5f26da`) |
| 지적 건수 | **5건 — high 3 · medium 2 · low 0** |
| codex 출력 크기 | 9,849 바이트 (sha256 `0260672bed335299f529e537cce8ab4982ef6839c884a07c8684ec02e9678e02`) |

### 1.1 base 를 `9b9d8ad` 로 잡은 근거

리더의 지정 문자열 `9b9d8ad..2a4523d` 를 **그대로** 썼다. `9b9d8ad` 는 이 배치의 **직전 상태**(게이트 23
완주 커밋)이지 리뷰 대상 커밋이 아니므로 `~1` 보정을 하지 않았다. `git rev-list --count 9b9d8ad..2a4523d`
가 **7** 을 돌려주며 리더가 명시한 "7 커밋" 과 일치한다. 범위가 어긋나지 않았다.

커밋 배분도 리더 지정과 맞는다.

| 덩어리 | 커밋 |
|---|---|
| ⓐ 스캐너 이름 관문 | `01d78a1` |
| ⓑ 원장 | `b401039` |
| ⓒ Kotlin 4 | `f51295b`(F-4) · `b529108`(toString 종류 탐지기) · `560c292`(union fail-open) · `b9097f6`(401 균일화) |
| ⓓ 산출물 문서 | `2a4523d` |

**재현 조건.** 스크립트는 `--base <ref>` 에서 `merge-base(HEAD, ref)..HEAD` 를 리뷰하므로 base 뿐 아니라
그 시점의 HEAD 도 대상의 일부다. 리뷰 실행 창은 `06:57:24Z ~ 07:10:39Z` 였고 그 사이 HEAD 는
`2a4523d`(`git rev-parse HEAD` 로 실행 전 확인) 였다. 작업 트리에는 추적되지 않는 파일 3건
(`.playwright-mcp/`, `docs/*.doc` 2건)이 있었으나 **branch 모드는 커밋된 변경만 세므로 대상 밖**이다.

### 1.2 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 9b9d8ad
codex-review: 대상 판정 = non-empty (merge-base=9b9d8adbde50, 변경 파일 23개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다. `--dry-run` 선행 실행에서도 같은 두 줄이 나왔다.

### 1.3 리더가 지정한 문서를 codex 가 실제로 읽었는가 (전사 금지 지시의 이행 확인)

리더 지시는 "codex 에게 두 산출물·`reviews/03_workspaces-fixes_cross.md` 조치 목록을 **읽게** 하라(전사
금지)" 였다. focus text 는 네 문서의 내용을 옮겨 적지 않고 **경로와 줄 수만** 주었다(§2 「먼저 읽을 것」 표).

| 문서 | 확인 근거 |
|---|---|
| `reviews/03_workspaces-fixes_cross.md` | 셸 명령 관측 — `07:05:39Z` `nl -ba docs/migration/_workspace/reviews/03_workspaces-fixes_cross.md \| sed -n …` |
| `reviews/03_security-scanner_privacy-gate.md` | 셸 명령 관측 (경로 문자열 일치 1건) |
| `docs/migration/_workspace/00_progress.md` | 셸 명령 관측 2건 — `07:07:42Z` `nl -ba …00_progress.md \| sed -n '1048,1070p'` 등 |
| `contracts/easy-doc-v1.yaml` | 셸 명령 관측 4건 — `07:07:49Z` `sed -n '292,305p;1490,1512p'` 등 |
| `03_kotlin-implementer_workspaces-fixes2.md` | **셸 명령으로는 확인되지 않음.** 다만 codex 출력이 이 문서를 **`:203-217` 라인 지정으로 인용**한다(§3 축 ②). job 로그가 명령 문자열을 잘라 기록하고 관측된 명령 상당수가 `… nl -ba … sed -n …` 체인이라, 체인 뒷부분에서 열렸을 가능성을 **배제할 수도 확정할 수도 없다**. 사실만 적는다 |

**diff 밖까지 읽었다.** codex 가 근거로 인용한 `frontend/src/api/types.ts:20-34` 와 `.github/workflows/ci.yml:578-672`
는 이 배치의 변경 23개 파일에 **들어 있지 않다**. codex 가 diff 를 넘어 저장소를 훑은 결과다.

### 1.4 codex 가 자기 메모리 파일을 읽었다 (독립성 판단 재료 — 판정하지 않는다)

job 로그에 `/Users/harris/.codex/memories/MEMORY.md` 를 여는 명령이 **2건**(`06:57:42Z`, `07:07:25Z`) 관측된다.
이 파일은 이 저장소 밖의 codex 자체 메모리이며 내용을 확인하지 않았다. **이 사실이 독립성에 어떤 영향을
주는지는 판정하지 않고 기록만 한다** — 회차 간 맥락 이월 여부는 `migration-reviewer` 가 대조할 재료다.

---

## 2. 전달한 프롬프트 전문 (focus text)

아래는 `adversarial` 모드에 전달한 focus text **전문**이다. 17,161 바이트,
sha256 `0a5a1620c91ebcf3aea7bbeb21451226ef629914aacaf755a4836dd0eb5f26da`.

Claude 가 이미 의심하는 지점은 넣지 않았다 — 축 ①~④ 는 리더가 지정한 검사 대상이고, 각 축의 하위
질문은 "무엇을 보라"이지 "여기가 문제다"가 아니다. 알려진 제약(소유권 은닉 404, `{"detail":…}`,
snake_case, 본문·개인정보 유출 금지, 선언 범위 = 실제 도달)은 **채점 기준**으로 제시했다.
축 ④ 에는 리더 지시대로 **"판정하지 말고 사실만"** 을 명시했다.

민감 데이터 미포함 확인: focus text 에 실제 암호문·키·사용자 문서 본문·개인정보는 **없다**. 등장하는
값은 커밋 해시·파일 경로·계약 조항 위치·nil UUID(`UUID(0L,0L)`) 뿐이다.

````text
## 배경

이 저장소는 공공기관용 '쉬운 글' 변환 SaaS다. Python/FastAPI 런타임을 Kotlin/Spring Boot(`backend-kotlin/`)로 교체하는 전환 중이며, 제품 동작과 개인정보 정책은 보존해야 한다. Python 출력은 정답이 아니다(폐기 대상) — 판정 기준은 요구사항·계약·정책 불변식이다. 지금은 Phase 3(데이터·인증·작업 공간 API)의 **종료 판정 직전**이고, 이 리뷰가 그 판정 전 마지막 독립 게이트다.

이 배치는 앞선 게이트(23회차)의 교차 종합에서 확정된 지적을 닫으려는 조치 배치다. 커밋 7개: 하네스 스캐너 1(`01d78a1`), 원장 1(`b401039`), Kotlin 4(`f51295b`·`b529108`·`560c292`·`b9097f6`), 산출물 문서 1(`2a4523d`).

## 먼저 읽을 것 (전사하지 않고 경로만 준다 — 직접 열어라)

아래 네 문서가 이 배치의 자기 주장이다. **주장과 코드를 대조하는 것이 이 리뷰의 핵심**이다.

| 문서 | 줄 수 | 무엇 |
|---|---|---|
| `docs/migration/_workspace/reviews/03_workspaces-fixes_cross.md` | 501 | 앞 게이트의 교차 종합 — 이 배치가 닫으려는 지적 목록의 정본 |
| `docs/migration/_workspace/03_kotlin-implementer_workspaces-fixes2.md` | 296 | Kotlin 4커밋의 조치 산출물(F-4·toString·union·401) |
| `docs/migration/_workspace/reviews/03_security-scanner_privacy-gate.md` | 221 | 스캐너 조치 산출물. §7 이 앞선 판정의 **정정 절**이다 |
| `docs/migration/_workspace/00_progress.md` | 1379 | 원장. Phase 3 종료 조건 표는 **L1060 부터** 7행 |

실행 환경 사실: 이 저장소의 Python 도구는 실행 가능하다 — 앞 회차에서 `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py`와 `uv run pytest tests/test_privacy_scanner.py`를 직접 돌린 기록이 있다. **스캐너는 네가 직접 돌려 확인할 수 있다.** 반면 Gradle(`backend-kotlin/`)은 샌드박스에서 `~/.gradle` 락 파일 생성이 막혀 앞 회차에 실행되지 않았다 — 실행이 안 되면 정적 독해로 답하고 **"실행하지 못했다"를 명시하라**. 실행하지 못한 것을 실행한 것처럼 쓰지 마라.

## 지켜야 하는 조건 (채점 기준)

1. **소유권 은닉 404.** 다른 사용자의 자원 접근은 403이 아니라 **404**다. 자원 존재 자체를 숨긴다. `OWNERSHIP-403` 스캐너 규칙은 이 불변식을 **탐지하는 장치**다 — 장치가 무력하면 위반이 나도 아무도 모른다.
2. **401 균일화.** `contracts/easy-doc-v1.yaml`의 `x-auth.failure_uniformity`(**L299-302**)를 직접 읽어라. 그 조항이 무엇을 열거하고 무엇을 요구하는지가 이 리뷰의 판정 기준이다. 계약 본문을 내 요약으로 대체하지 말고 파일을 열어 확인하라.
3. **오류 본문은 `{"detail": ...}`** 고정. Spring 기본 `ProblemDetail` 노출 금지. JSON 필드는 snake_case.
4. **본문·개인정보 유출 금지.** 로그·예외 메시지·`toString()`에 사용자 문서 본문이나 개인정보(이메일 등)가 실려선 안 된다. 로깅은 문서 ID·길이·상태까지.
5. **선언한 범위 = 실제 도달 범위.** 이 저장소의 규칙: 게이트·불변식·규칙을 세우거나 넓힐 때 **선언한 범위와 실제로 도달하는 범위를 실행으로 대조**한다. "전역"·"모든"·"항상"을 근거 없이 쓰지 않고, **도달 0**("이 게이트가 지금 어디서 도는가")을 특히 의심한다. **은폐형 장치**(무시 패턴·억제·면제 조항)는 넓히지 않고 탐지형으로 갈아탄다. 이 배치는 장치를 **네 개** 만들거나 고쳤다(스캐너 이름 관문 / `toString` 종류 탐지기 / union 정확 일치 / F-4 구조 축) — **각 장치마다 이 조건이 걸린다.**
6. **근거 없는 `예`는 `아니오`다.** 원장의 종료 조건 표는 각 행에 실행 경로(어디서 도는가)와 근거(커밋·run id)를 요구한다.

## 대상

### ① 스캐너 이름 관문 — `01d78a1`

- `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` — `_403_TOKEN` 상수 신설, `OWNERSHIP_403_INERT` 제외 패턴의 갈래 ①②③
- `tests/test_privacy_scanner.py` — 회귀 N14~N19 + 구조 회귀
- 산출물 `reviews/03_security-scanner_privacy-gate.md` §7

배경 사실: 앞 게이트에서 이 규칙의 "정밀화"(=제외 패턴)가 **네 가지 형태의 탐지를 잃었다**는 지적이 확정됐다. 이번 커밋이 그 복원 시도다.

### ② 401 균일화 — `b9097f6`

- `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt` — `authenticate()`, `ABSENT_USER_PROBE_ID`
- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/AuthenticationWorkUniformityTest.kt` (신설, 213줄)
- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/CountingDataSource.kt`
- 계약 `contracts/easy-doc-v1.yaml` L299-302

### ③ F-4 구조 축 · toString 종류 탐지기 · union — `f51295b`·`b529108`·`560c292`

- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/JdbcWorkspaceRepositoryTest.kt` (F-4 계측 진입점 이동)
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SensitiveToStringReachTest.kt` (신설, 321줄) · `AuthDtoLeakTest.kt` (신설)
- `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/UserContent.kt` (신설) · `core/user/User.kt` · `core/workspace/Workspace.kt` · `core/easyread/Prompts.kt` · `core/easyread/StyleRules.kt` · `api/auth/AuthDtos.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt` (`errorDetailUnionTypes`)
- `backend-kotlin/application/.../conversion/ConvertDocumentUseCase.kt`

### ④ Phase 3 종료 판정 재료 — `b401039`

- `docs/migration/_workspace/00_progress.md` L1060 부터 **7행** 표. 현재 행 5(React E2E)만 `예`이고 나머지 여섯은 `아니오`
- 계획 문서 원문 종료 조건 두 조각: (a) "contract test와 React 테스트가 Kotlin API에서 통과", (b) "계약 개선이 있었다면 계약 파일·Kotlin·React 3자 동일 + 근거 기록"
- `tests/test_harness_scope_reach.py` (원장 커밋이 함께 건드렸다)

## 질문 — 네 축

### 축 ① 스캐너 이름 관문이 실제로 탐지를 되찾았는가

**직접 돌려서 확인하라.** 정적 독해만으로 답하지 마라.

- `_403_TOKEN` 한 조각에서 탐지 토큰과 제외 갈래 ③의 이름 관문이 함께 파생되는데, 산출물이 **네 형태**(토큰 없는 상수명 + 사용처, 난수 이름, 타입 명시 선언, 백틱 식별자)를 되찾았다고 주장한다. 그 네 형태를 **네가 직접 파일에 심어 스캐너를 돌려** 실제로 BLOCK 되는지 확인하라. 되찾지 못한 형태가 하나라도 있는가?
- 갈래 ②의 백틱을 `fun` 바로 뒤로 한정했다. 이 좁힘이 **새 미탐**을 만드는가 — 백틱 식별자가 `fun` 자리가 **아닌 곳**에서 403을 나르는 형태(프로퍼티 이름, 애너테이션 인자, 맵 키, 백틱 함수명이 여러 줄에 걸쳐 선언되는 경우, `fun`과 백틱 사이에 제네릭/리시버/애너테이션이 끼는 경우)를 만들어 시험하라. 반대로 이 좁힘이 **새 오탐**을 만드는 형태도 찾아라.
- 이름 관문이 `_403_TOKEN`에 `\b`를 붙인 결과 `HTTP_FORBIDDEN`·`FORBIDDEN_STATUS` 같은 이름이 어느 쪽(BLOCK/제외)에 떨어지는지, 그리고 **그 판정이 산출물의 주장과 일치하는지** 확인하라. 산출물이 "사용처도 잡히지 않으므로 BLOCK이 옳다"고 적은 논증이 실제 패턴 동작과 맞는가?
- 전수 스캔에서 오탐이 늘지 않았다고 주장한다(마커 7/7). 실제로 돌려 확인하라. 2차 제외 집계 수가 앞 판과 같은가?
- `xfail` 2건(`HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` 미도달)의 사유가 무엇이고, 그것이 **미탐으로 남은 실제 구멍**인지 아니면 도달 불가능한 형태인지 판단할 재료가 산출물에 있는가? `xfail(strict)`이 언젠가 조용히 통과로 뒤집힐 구조인가?
- **이 규칙 자체가 어디서 도는가?** CI 잡에 배선돼 있는가, 로컬에서만 도는가? 배선을 CI 설정 파일에서 짚어라.

### 축 ② 401 균일화의 정직성

- 계약 L299-302가 **무엇을 열거하는지** 읽고, 이번 조치가 균일화 대상에서 **무헤더 갈래를 의도적으로 제외**한 것이 그 열거와 맞는지 판정하라. 계약이 무헤더를 열거 안에 넣고 있는가, 밖에 두고 있는가? 산출물이 대는 근거(무헤더는 계약이 다른 문구를 준다)가 계약 파일에서 실제로 확인되는가?
- `authenticate()`의 `verify` 실패 갈래가 nil UUID(`UUID(0L,0L)`)로 `users.exists()`를 한 번 돈다. 이것이 **새 오라클을 여는가**? DB 왕복 횟수는 같아도 (a) 인덱스 경로·플랜이 실제 UUID 조회와 다른가, (b) 캐시 적중률이 달라 시간이 갈리는가, (c) 반복 호출로 그 한 행만 뜨거워져 오히려 구분 가능해지는가, (d) 예외가 나는 경로에서 순서·트랜잭션 상태가 성공 경로와 다른가. 근거를 대라.
- `AuthService`가 `InvalidCredentialsException`을 잡아 `exists`를 돌고 다시 던진다. 이 `catch`가 **의도한 것보다 넓은 예외를 삼키거나**, 반대로 `verify`가 다른 타입의 예외를 던지는 경로에서 균일화를 놓치는 자리가 있는가?
- 산출물이 "네 갈래 비가 2.8~3.0으로 **커졌다**"(무헤더 포함 시)는 사실을 적고 있는가, 아니면 세 갈래 비 1.007만 앞세워 그 사실을 묻어 두는가? 조치의 대가를 **정직하게 기록했는지**를 문서에서 확인하고, 묻어 뒀다면 어디서 그런지 지목하라.
- 회귀를 시간이 아니라 **구조**(SQL 문 수 5갈래 전부 1)로 걸었다. 이 구조 단언이 통과하면서도 **시간이 갈릴 수 있는 변이**가 있는가? 반대로 시간이 균일한데 이 단언이 빨개지는 오탐이 있는가? 이 테스트를 통째로 지우면 정확히 무엇이 깨지는가?
- 산출물은 위조 토큰 대조 도구에 결함(하위 2비트)이 있었고 정정했다고 적는다. base64url 인코딩에서 마지막 문자를 바꿀 때 실제로 몇 비트가 바뀌는지 계산해, 정정된 도구가 **실제로 서명을 위조하는지**(=서명이 반드시 깨지는지) 확인하라. 정정이 여전히 틀렸다면 위조 갈래의 측정 전체가 다른 것을 재고 있었다는 뜻이다.

### 축 ③ F-4 구조 축 · toString 탐지기 · union

- **F-4**: 계측 진입점을 저장소 메서드에서 **유스케이스 진입(요청 1개)**으로 올리고 대상을 rename/delete/list 4개로 넓혔다. 이 계수가 **서비스 층에 추가된 SELECT**를 실제로 잡는가? 소유 판정을 서비스로 끌어올리는 변이를 재현 가능한 범위에서 만들어(정적으로라도 정확히 어느 줄을 어떻게 바꾸면 되는지 지목해) 이 단언이 빨개지는지 논증하라. **여전히 빠져나가는 변이**가 있는가 — 캐시 경유, 다른 DataSource 경유, 배치 질의로 합치기, 읽기 전용 복제본, `EXISTS` 서브쿼리로 옮기기 등.
- 트랜잭션 관리자에 **같은 `CountingDataSource`**를 주는 조건이 정당한가? 다른 것을 주면 무엇이 계측에서 빠지는가, 그리고 그 조건이 테스트 설정에서 **강제**되는가 아니면 관례로만 지켜지는가?
- `CountingDataSource`가 세는 것이 **실행이 아니라 문장 생성**이라는 한계가 KDoc에 적혀 있다. 이 한계로 인해 현재 코드에서 **이미** 성립하는 우회가 있는가? (문장 하나에 SQL 둘, `PreparedStatement` 재사용, raw JDBC 하강)
- **toString 탐지기**(`SensitiveToStringReachTest`): 표식을 심어 실제 인스턴스의 `toString()`을 검사하는 방식이다. 이것이 data class 리플렉션 접근의 한계를 실제로 우회했는가? **새 DTO를 추가하면 red가 되는가** — 즉 탐지기가 "종류"를 덮는가 아니면 열거한 타입만 덮는가? 새 민감 타입을 하나 만들어(정적으로 어디에 무엇을 추가해야 하는지 짚어) 탐지기가 잡는지 논증하라.
- `@UserContent`(신설, `core/privacy/UserContent.kt`)가 **면제 조항으로 오용될 구조**인가? 애너테이션을 붙이면 검사에서 빠지는 방향인가, 검사 대상이 되는 방향인가? 누가 이 애너테이션을 새 타입에 붙이도록 강제하는가 — 강제자가 없으면 "붙이는 것을 잊으면 조용히 통과"가 성립한다. 이 저장소 규칙(조건 5)에서 **은폐형 장치는 넓히지 않는다**는 조항에 걸리는가?
- **union 정확 일치**(`ContractSpec.errorDetailUnionTypes`): fail-open을 `error()`로 바꾸고 계약 집합과 **정확 일치**를 요구한다. 음성 대조 세 갈래 — (a) 계약에 타입이 추가됐는데 코드가 모르는 경우, (b) 코드가 계약에 없는 타입을 받는 경우, (c) 파서가 union을 아예 못 찾는 경우 — 각각에서 실제로 빨개지는가? `error()`가 던지는 예외가 테스트 프레임워크에서 **skip이나 통과로 흡수되는** 경로가 있는가? 계약 파일 구조가 바뀌면 파서가 조용히 빈 집합을 돌려주는 자리가 남아 있는가?
- 산출물은 표 5의 나머지 fail-open 2자리(`headerComponentsByName`·`collectHeaderRefs`)를 **의도적으로 남겼다**고 적는다. 그 근거("계약에 인라인 헤더가 처음 생기는 커밋이 마감")가 타당한가, 아니면 지금도 이미 도달 가능한 자리인가?

### 축 ④ Phase 3 종료 판정 재료 — **판정하지 말고 사실만 대라**

이 축에서는 "Phase 3을 종료해도 되는가"를 **판정하지 마라**. 판정은 다른 역할의 몫이다. 필요한 것은 판정의 **재료가 실재하는지**에 대한 사실 확인이다.

- 원장 L1060 표 7행 중 행 5만 `예`다. 그 근거로 적힌 것(로컬 12/12, CI run id `32222249150`·headSha `b3f76b2`·잡 `e2e` success, 음성 대조 6/6, 제품 코드 변경 0)이 **저장소 안에서 확인 가능한가**? `git log`·CI 설정·테스트 파일로 짚을 수 있는 것과 저장소 밖(원격 CI)이라 확인 불가능한 것을 **나눠서** 적어라.
- 나머지 여섯 행의 `아니오`가 이 배치로 닫히는가? 각 행의 "미해결 항목" 칸에 적힌 것과 이 배치의 7커밋이 실제로 만든 것을 대조해, **닫힌 것 / 안 닫힌 것 / 새로 열린 것**을 나눠라.
- 계획 원문 종료 조건 (a) "contract test와 React 테스트가 **Kotlin API에서** 통과"를 닫을 사실이 갖춰졌는가? contract test가 **어느 대상**을 치는지(실제 기동한 Kotlin API인가, mock인가), React 테스트가 Kotlin API를 실제로 치는지 코드로 확인하라.
- 계획 원문 종료 조건 (b) "계약 개선이 있었다면 계약 파일·Kotlin·React **3자 동일** + 근거 기록"을 닫을 사실이 갖춰졌는가? 이 Phase에서 계약이 개선된 항목이 있는가, 있다면 세 곳이 실제로 같은가? `frontend/src/api/types.ts`와 `contracts/easy-doc-v1.yaml`과 Kotlin DTO를 대조하라.
- 원장이 스캐너 복원(`01d78a1`)을 "완료로 등재(리뷰 미수령 병기)"했다. 리뷰를 받지 않은 항목을 완료로 적으면서 그 사실을 병기하는 것이 표에서 **실제로 읽히게** 돼 있는가, 아니면 병기가 다른 줄에 묻혀 표만 보면 완료로 읽히는가?
- 원장 커밋이 `tests/test_harness_scope_reach.py`를 함께 고쳤다. 그 변경이 가드를 **넓혔는가 좁혔는가**? 원장의 표 갱신과 가드 변경이 같은 커밋에 서는 구조가, 가드가 자기 표를 검사하는 **자기 참조**를 만드는가?

## 위반 시 결과

- 축 ①이 실패하면: 소유권 은닉 404 위반이 코드에 들어와도 스캐너가 침묵한다. 다른 사용자의 자원 존재가 403으로 새는 경로가 탐지 없이 통과한다.
- 축 ②가 실패하면: 계정 열거 채널이 시간으로 남는다. 또는 균일화 조치 자체가 새 오라클(존재하지 않는 UUID 조회의 관측 가능한 차이)을 연다.
- 축 ③이 실패하면: 장치는 초록불인데 아무것도 재지 않는다 — 이 저장소가 반복해서 겪은 실패 형태다. 사용자 문서 본문·이메일이 로그로 새는 경로가 탐지 없이 남는다.
- 축 ④가 실패하면: 근거 없는 `예`로 Phase가 종료되고, 미충족 항목이 다음 Phase의 전제로 잘못 깔린다.

## 출력 요구

- 지적마다 **파일 경로와 라인**을 대라.
- **실행한 것과 정적 독해만 한 것을 구분해 표시하라** (`[실행]` / `[정적]`). 실행하지 못한 이유가 있으면 적어라.
- 지적이 없는 축은 "지적 없음"이라고 그대로 적어라. 채우지 마라.
- 축 ④는 판정하지 말고 사실만 적어라.
````

---

## 3. codex 원문 출력 (무편집)

> **이 구획은 codex 가 낸 그대로다.** 요약·정정·심각도 재부여·오탐 표시 어느 것도 하지 않았다.
> 아래 블록은 스크립트 stdout 9,849 바이트를 그대로 옮긴 것이며 sha256 이
> `0260672bed335299f529e537cce8ab4982ef6839c884a07c8684ec02e9678e02` 로 일치한다.
> 잘림 없음 — 출력이 `Next steps:` 목록으로 정상 종료했고 스크립트 종료 코드가 `0` 이다.

````text
# Codex Adversarial Review

Target: branch diff against 9b9d8ad
Verdict: needs-attention

NO-SHIP: 탐지 장치가 초록인 채 실제 403과 민감 DTO를 놓칠 수 있고, 401 회귀는 계약이 요구하는 시간 균일성을 검증하지 않는다.

축 ① [실행]: 실제 scan()에 합성 소스를 주입했다(읽기 전용 샌드박스라 파일 생성 대신 Path.read_text를 대체). N14~N19, HTTP_FORBIDDEN, FORBIDDEN_STATUS, 프로퍼티·애너테이션 인자·맵 키의 403은 모두 BLOCK이었다. 반면 HTTP_403_FORBIDDEN과 SC_FORBIDDEN은 미탐이었고, 제네릭·리시버·줄바꿈이 있는 정상 백틱 함수 선언은 오탐이었다. 전수 스캔은 exit 0, 241파일, 마커 7/7, 2차 제외 6건으로 산출물과 같았다. quality 잡에 스캐너가 배선돼 있으나 알려진 미탐은 strict xfail로 허용된다.

축 ② [정적]: contracts/easy-doc-v1.yaml:299-302는 이메일 부재·비밀번호 불일치·토큰 만료·위조·계정 삭제를 열거하며 무헤더는 포함하지 않는다. 무헤더 제외는 이 기준 및 1500-1510의 두 메시지 예시와 맞는다. 다만 1496-1502의 설명은 같은 메시지라고 쓴 직후 두 메시지라고 써 내부 모순이다. 문서는 네 갈래 비가 2.575에서 2.8~3.0으로 커졌음을 03_kotlin-implementer_workspaces-fixes2.md:203-217에 명시해 숨기지 않았다. 32바이트 HS256 서명은 base64url 43자이고 마지막 글자의 하위 2비트는 미사용이다. 디코딩한 첫 바이트 XOR은 서명 바이트를 실제로 바꾼다. catch 범위도 AccessTokens 계약의 InvalidCredentialsException과 일치했다. 단, SQL 문장 수 회귀는 시간 균일성의 대리값일 뿐이다. Gradle은 ~/.gradle lock 생성 권한 오류로 실행하지 못했다.

축 ③ [정적]: F-4는 지적 없음. WorkspaceService.rename 앞에 listOwned를 추가하면 1문장이 2문장이 되어 현재 단언이 실패하며, repository와 transaction manager 모두 같은 CountingDataSource를 받도록 테스트 설정이 강제한다(JdbcWorkspaceRepositoryTest.kt:63-80). 현재 구현은 JdbcClient만 사용하므로 문장 재사용/raw JDBC 우회는 현재 성립하지 않는다. union 정확 일치도 지적 없음: 계약 추가·코드의 미선언 타입·oneOf 부재 모두 error 또는 집합 불일치로 실패하고 skip 경로는 없다. toString 종류 탐지와 잔존 헤더 파서에는 각각 지적이 있다.

축 ④: 지적 없음. 판정 없이 확인한 사실만 적는다. 원장 7행 중 행 5만 예다. 저장소에서 b3f76b2와 관련 커밋, 12개 Playwright 케이스, PostgreSQL·bootJar·실 API를 기동하는 ci.yml:578-672, 제품 src 변경 0은 확인됐다. 로컬 12/12·음성 대조 6/6·원격 run 32222249150의 success는 문서 기록만 확인됐고, gh 조회는 네트워크 차단으로 검증하지 못했다. 나머지 행은: 행1 F-4만 닫혔고 문서·변환 repository는 남음; 행2 R-2와 Statement-생성 대리계측이 남음; 행3 401 일회성 측정은 개선됐으나 회귀 장치 결함이 남음; 행4 선언한 네 형태는 복원됐으나 두 실사용 상수 미탐이 남음; 행6 oneOf 한 자리는 닫혔으나 헤더 두 자리와 미구현 엔드포인트가 남음; 행7은 이 배치에서 계약·React 변경이 없고 기존 타입 드리프트가 남는다. AuthContractTest와 WorkspaceContractTest는 @WebMvcTest와 가짜 저장소를 사용하고, 별도 ReachTest가 실제 소켓을 사용한다. React E2E는 실제 bootJar를 기동한다. frontend types.ts:20-34의 통합 CredentialsRequest와 넓은 token_type, 계약 1696-1742의 분리 요청·const bearer, Kotlin AuthDtos.kt:34-91은 타입 수준에서 정확히 같지 않다. 스캐너의 리뷰 미수령 사실은 원장 표 1065행 안에 직접 적혀 있다. test_harness_scope_reach.py는 reach 토큰을 62→65로 넓혔지만 파일 자체가 1-5행에서 내용의 진실성은 검사하지 않는다고 명시한다; 표와 기대값을 같은 커밋에서 바꿀 수 있는 자기참조적 형식 가드다. 하네스 테스트는 직접 실행해 37건 통과했다.

Findings:
- [high] [축 ①][실행] 일반적인 403 상수 두 형태가 CI 스캐너를 그대로 통과한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:588)
  실제 scan()에 `raise HTTPException(status.HTTP_403_FORBIDDEN)`과 `response.sendError(HttpServletResponse.SC_FORBIDDEN)`을 넣었지만 둘 다 BLOCK=false였다. tests/test_privacy_scanner.py:1530-1580도 이를 도달 가능한 알려진 미탐으로 선언한다. strict xfail은 향후 수정 시 XPASS를 실패시킬 뿐, 현재 소유권 코드가 이 표준 상수로 403을 반환하는 것을 막지 않는다. 따라서 quality 잡이 초록이어도 타인 자원 존재가 노출될 수 있다.
  Recommendation: HTTP 상태 상수 문맥에서 `HTTP_403_FORBIDDEN`과 `SC_FORBIDDEN`을 탐지하도록 규칙을 확장하고, 이를 xfail이 아닌 필수 양성 회귀로 바꿔라. 비HTTP 식별자 오탐은 문맥 조건과 음성 대조로 통제하라.
- [high] [축 ②][정적] SQL 문장 수가 같아도 401 시간 오라클은 회귀할 수 있다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/auth/AuthenticationWorkUniformityTest.kt:111-118)
  테스트는 다섯 갈래가 모두 문장 생성 1회인지만 단언한다. CountingDataSource.kt:23-39도 실행 횟수·플랜·결과 접근을 세지 않는다고 명시한다. catch에 지연이나 CPU 작업을 추가하거나, 고정 nil UUID와 실제 UUID가 서로 다른 인덱스 페이지·캐시 온도를 갖게 만들어도 단언은 통과한다. 특히 UUID(0,0)는 모든 위조 요청이 공유해 전역적으로 뜨거워질 수 있다. 이는 응답 시간으로도 새지 않아야 한다는 계약 요구를 직접 검증하지 못한다. 현재 실측 문서는 개선을 보이지만 상시 회귀가 아니다. Gradle은 lock 파일 권한 때문에 실행하지 못했다.
  Recommendation: 문장 수 단언은 유지하되 고정 sentinel 대신 요청별 존재 불가능 UUID 또는 동일 분포의 probe를 사용하고, 교차 순서·워밍업·충분한 표본을 갖춘 시간 분포 회귀와 지연 변이 음성 대조를 추가하라.
- [high] [축 ③][정적] toString 탐지기는 새 민감 타입을 자동 발견하지 못한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SensitiveToStringReachTest.kt:126-135)
  후보는 직접 String 필드이면서 타입에 @UserContent가 있거나 필드명이 고정 토큰 목록에 맞을 때만 생성된다. 따라서 `data class ExportEnvelope(val payload: String)`처럼 민감하지만 표식과 알려진 이름이 없는 타입은 통째로 건너뛴다. `@UserContent data class ExportEnvelope(val chunks: List<String>)`도 직접 String이 아니어서 빠진다. 새 타입에 애너테이션을 붙이도록 강제하는 장치가 없으므로, 붙이는 것을 잊으면 기본 data-class toString으로 본문이나 개인정보가 노출돼도 테스트는 초록이다.
  Recommendation: 제품 data class의 String 및 String 컬렉션 필드를 모두 fail-closed 분류하고, 안전·민감 중 하나를 명시하도록 강제하라. 알 수 없는 필드명과 컬렉션을 가진 새 민감 DTO가 반드시 빨개지는 mutation test를 추가하라.
- [medium] [축 ①][실행] 백틱 제외가 합법적인 Kotlin 함수 선언을 오탐한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:493-496)
  제외식은 `fun` 바로 다음의 백틱만 인정한다. 실제 scan()에서 `fun <T> `403 ...`()`, `fun String.`403 ...`()`, `fun\n`403 ...`()`, 애너테이션 뒤 함수 선언이 모두 BLOCK됐다. 이들은 403을 생성하는 코드가 아니라 정상적인 테스트 함수명일 수 있다. 현재 저장소 전수 스캔에는 오탐이 없었지만 새 테스트를 추가하면 억제할 수 없는 quality 실패가 된다.
  Recommendation: 줄바꿈, 타입 파라미터, 리시버와 함수 애너테이션을 포함한 Kotlin 함수명 위치를 인식하도록 제외를 좁고 구문 인지적으로 확장하고 각 형태의 음성 회귀를 추가하라.
- [medium] [축 ③][정적] 첫 인라인 헤더가 추가돼도 파서가 조용히 무시한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:191-203)
  collectHeaderRefs는 헤더 선언에 `$ref`가 없으면 즉시 return@forEach 한다. 기존 참조 헤더가 하나라도 있으므로 headerComponentsByName의 non-empty 검사도 통과한다. 따라서 유효한 인라인 헤더를 현재 계약에 추가하는 음성 변이는 파서의 범위를 늘리지 않는다. 산출물의 '첫 인라인 헤더 커밋에서 고친다'는 일정은 그 커밋을 실패시키는 강제자가 없어 선언 범위와 실제 도달 범위가 어긋난다.
  Recommendation: 지금 인라인 헤더를 파싱하거나, 지원 전까지 `$ref` 없는 모든 헤더 선언에서 명시적으로 실패하라. 인라인 헤더 추가와 malformed response-map 변이를 영구 음성 대조로 추가하라.

Next steps:
- 스캐너의 두 실제 미탐과 Kotlin 백틱 오탐을 함께 고친 뒤 전수 스캔과 전체 tests/test_privacy_scanner.py를 재실행한다.
- 401 균일성을 문장 수뿐 아니라 probe 분포와 시간 변이로 검증하고, 쓰기 가능한 Gradle 환경에서 Kotlin 전체 테스트를 실행한다.
- 민감 DTO 분류와 인라인 헤더 파서를 fail-closed로 바꾸고 새 종류·새 계약 형태 mutation을 추가한다.
- 원격 CI run 32222249150의 head SHA와 e2e 결과는 네트워크 가능한 환경에서 별도로 확인한다.
````

---

## 4. 정리(가공) — 지적 색인

> **이 구획은 Claude 가 만든 색인이다.** §3 원문에서 항목을 옮겨 세어 놓은 것이며,
> 옳고 그름·오탐 여부·중복 병합·심각도 재부여는 **하지 않았다**. 심각도는 codex 가 붙인 라벨 그대로다.
> 표의 「축」·「실행/정적」·「근거」도 codex 자신이 자기 출력에 적은 값을 옮긴 것이다.

### 4.1 지적 5건 (codex 라벨 그대로)

| # | codex 심각도 | 축 | 실행/정적 | 제목(원문) | codex 가 댄 근거 |
|---|---|---|---|---|---|
| X24-1 | **high** | ① | **[실행]** | 일반적인 403 상수 두 형태가 CI 스캐너를 그대로 통과한다 | `scan_privacy_invariants.py:588` · `tests/test_privacy_scanner.py:1530-1580` |
| X24-2 | **high** | ② | [정적] | SQL 문장 수가 같아도 401 시간 오라클은 회귀할 수 있다 | `AuthenticationWorkUniformityTest.kt:111-118` · `CountingDataSource.kt:23-39` |
| X24-3 | **high** | ③ | [정적] | toString 탐지기는 새 민감 타입을 자동 발견하지 못한다 | `SensitiveToStringReachTest.kt:126-135` |
| X24-4 | medium | ① | **[실행]** | 백틱 제외가 합법적인 Kotlin 함수 선언을 오탐한다 | `scan_privacy_invariants.py:493-496` |
| X24-5 | medium | ③ | [정적] | 첫 인라인 헤더가 추가돼도 파서가 조용히 무시한다 | `ContractSpec.kt:191-203` (`collectHeaderRefs`) |

### 4.2 축별 결과 (codex 자신의 서술을 옮김)

| 축 | codex 결과 |
|---|---|
| ① 스캐너 이름 관문 | 지적 **2건**(X24-1 high · X24-4 medium). **유일하게 `[실행]` 이 붙은 축**이다 |
| ② 401 균일화 | 지적 **1건**(X24-2 high). 무헤더 제외·계약 열거·base64url 비트 위치·`catch` 범위·네 갈래 비 정직성은 codex 가 **문제없음으로 서술**했다(§3 축 ② 문단) |
| ③ F-4·toString·union | 지적 **2건**(X24-3 high · X24-5 medium). **F-4 와 union 정확 일치는 codex 가 "지적 없음" 이라고 명시**했다 |
| ④ Phase 3 종료 재료 | **"지적 없음"** — 리더 지시대로 판정 없이 사실만 서술했다(§3 축 ④ 문단, 이 리뷰에서 가장 긴 서술) |

### 4.3 codex 가 "지적 없음" 이라고 **명시**한 것 (채워 넣지 않았다)

`§7 실패 처리` 규약에 따라, codex 가 지적을 내지 않은 자리는 **그대로 기록한다.** Claude 가 대신
지적을 만들어 채우지 않았다.

- 축 ③ **F-4** — "F-4는 지적 없음" (원문). 서비스 층 변이가 단언을 실패시킨다는 것, 트랜잭션 관리자
  동일 `CountingDataSource` 강제, `JdbcClient` 전용이라 raw JDBC 우회가 현재 미성립까지 서술했다
- 축 ③ **union 정확 일치** — "union 정확 일치도 지적 없음" (원문). 음성 대조 3갈래 전건이 실패하고
  skip 경로가 없다고 서술했다
- 축 ④ **전체** — "지적 없음. 판정 없이 확인한 사실만 적는다" (원문)

### 4.4 codex 가 **검증하지 못했다고 스스로 밝힌** 것

- **Gradle 전 구간** — `~/.gradle` lock 파일 권한으로 실행 실패(§5-② 로 실측 확인). 축 ②·③ 의
  Kotlin 지적 3건이 전부 `[정적]` 인 이유다
- **원격 CI** — `gh run view 32222249150` 네트워크 차단(§5-④). run id·headSha·잡 결과는 **문서 기록만**
  확인됐다고 codex 가 명시했다
- 로컬 12/12 · 음성 대조 6/6 도 같은 이유로 **문서 기록만** 확인

### 4.5 전제 확인 필요 (Claude 가 사실 여부를 판정하지 않고 표시만 한다)

아래는 codex 출력에 담긴 **사실 주장**이며, 이 에이전트는 이를 검증하지도 반박하지도 않았다.
`migration-reviewer` 2차 호출이 코드·실행으로 대조할 항목이다. **원문은 §3 에 그대로 있고 여기서
지운 것은 없다.**

1. `scan_privacy_invariants.py:588` — X24-1 의 라인 지목. 이 파일의 해당 라인이 규칙 정의부인지 확인 필요
2. `tests/test_privacy_scanner.py:1530-1580` — 이 파일이 그 길이를 갖는지, 해당 구간이 xfail 선언인지 확인 필요
3. `contracts/easy-doc-v1.yaml:1496-1502` **내부 모순** 주장 — "같은 메시지라고 쓴 직후 두 메시지라고 쓴다".
   이 배치가 건드린 파일이 **아니므로** diff 밖 지적이다
4. `contracts/easy-doc-v1.yaml:1500-1510` 두 메시지 예시 · `1696-1742` 분리 요청·`const bearer`
5. `frontend/src/api/types.ts:20-34` 통합 `CredentialsRequest` · 넓은 `token_type` — Kotlin `AuthDtos.kt:34-91`
   과 "타입 수준에서 정확히 같지 않다"는 주장. **종료 조건 (b) 3자 동일에 직접 걸린다**
6. `.github/workflows/ci.yml:578-672` — e2e 잡이 PostgreSQL·bootJar·실 API 를 기동한다는 주장
7. `00_progress.md` **1065행** 에 스캐너 「리뷰 미수령」이 직접 적혀 있다는 주장
8. `tests/test_harness_scope_reach.py:1-5` 가 "내용의 진실성은 검사하지 않는다"고 명시한다는 주장,
   그리고 reach 토큰 **62→65** 확대
9. `AuthContractTest`·`WorkspaceContractTest` 가 `@WebMvcTest` + 가짜 저장소를 쓰고 실 소켓은 별도
   `ReachTest` 라는 주장. **종료 조건 (a) "contract test 가 Kotlin API 에서 통과" 에 직접 걸린다**
10. 축 ① 실행 방식 — "읽기 전용 샌드박스라 파일 생성 대신 `Path.read_text` 를 대체" 했다는 주입 방법.
    이 방식이 실제 스캐너 경로를 탔는지, `HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` 미탐이 재현되는지 확인 필요
11. 축 ② 서술의 네 갈래 비 **"2.575 → 2.8~3.0"** — 앞 수치의 출처
12. 백틱 오탐 4형태(`fun <T>`, `fun String.`, `fun\n`, 애너테이션 뒤)가 실제로 BLOCK 됐다는 실행 결과

### 4.6 §4.6 축(선언 범위 대 실제 도달)의 결과

이 배치는 장치를 4개 만들거나 고쳤으므로 이 축을 focus 에 **필수 포함**했다(체크리스트 규약).
codex 는 이 축에서 **침묵하지 않았다** — 5건 중 **4건이 이 형태**다.

| 지적 | 선언 | codex 가 말한 실제 도달 |
|---|---|---|
| X24-1 | 스캐너가 OWNERSHIP-403 을 탐지한다 | 표준 상수 2종 미탐, xfail 로 **허용**되어 CI 초록 |
| X24-2 | 401 시간 균일성 회귀를 건다 | 문장 수는 대리값 — 지연·CPU·캐시 온도 변이가 **통과** |
| X24-3 | `toString` 을 **종류로** 막는다 | 표식·고정 이름 목록에 든 것만 — 새 타입은 **건너뜀** |
| X24-5 | 인라인 헤더는 첫 커밋에서 고친다 | 그 커밋을 실패시키는 **강제자가 없다** |

---

## 5. 미실행·실패 항목

### 5.1 codex 셸 명령 실패 4건 (job 로그 실측, 원문 그대로)

| 시각(UTC) | 명령(로그 표기, 잘림 포함) | exit |
|---|---|---|
| `06:58:10.816Z` | `nl -ba .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py …` | **2** |
| `06:58:24.093Z` | `./gradlew --no-daemon :api:test --tests 'kr.easydoc.api.SensitiveToStringReachT…` | **1** |
| `06:58:31.575Z` | `ls -l .venv/bin/python .venv/bin/pytest 2>/dev/null; .venv/bin/python --version…` | **1** |
| `07:04:40.625Z` | `gh run view 32222249150 --json databaseId,headSha,status,conclusion,jobs,url 2>&1` | **1** |

②는 codex 가 축 ②·③ 에서 밝힌 **Gradle 미실행**의 실측 근거다. ④는 축 ④ 의 **원격 CI 미확인**의 근거다.
나머지 50건은 exit 0. **총 54건 시작 / 54건 종료** — 유실된 명령 없음.

### 5.2 이 회차에서 수행하지 못한 것

- **Kotlin 테스트 실행 0건.** codex 의 Kotlin 관련 지적 3건(X24-2·X24-3·X24-5)이 전부 `[정적]` 이다.
  이 축의 실행 관점은 이 산출물에 **없다**
- **원격 CI 관측 0건.** run `32222249150` 의 headSha·잡 결과를 codex 는 확인하지 못했다
- **`03_kotlin-implementer_workspaces-fixes2.md` 열람의 명령 수준 확인 실패** — §1.3 참조. 인용은
  있으나 로그 잘림으로 확정 불가

### 5.3 재시도·누락 여부

- **codex 리뷰 누락 없음.** 1회 실행으로 종료 코드 `0`, 출력 9,849 바이트, 지적 5건을 받았다.
  §7 의 재시도 조건(exit 3·4·5·7, 타임아웃, 빈 응답)에 **해당하지 않는다**
- **출력 잘림 없음** — `Next steps:` 4항목으로 정상 종료
- **회차** — 이 어간(`03_phase3-close`)의 **1회차**다. 같은 어간의 이전 codex 리뷰는 없다.
  직전 회차는 다른 어간(`03_workspaces-fixes`, 게이트 23)이며 그 파일은 덮어쓰지 않았다

### 5.4 민감 데이터 취급

focus text·codex 출력 어디에도 실제 암호문·키·사용자 문서 본문·개인정보가 실리지 않았다.
등장한 식별자는 커밋 해시·파일 경로·계약 조항 위치·nil UUID(`UUID(0L,0L)`)·CI run id 뿐이다.
codex 가 자기 메모리 파일(`~/.codex/memories/MEMORY.md`)을 읽은 사실은 §1.4 에 기록했다.

---

## 6. 수령자에게

- **정본 종합은 이 파일이 아니다.** `migration-reviewer` 2차 호출이 이 파일과
  `03_phase3-close_migration-reviewer.md` 를 §5 표로 대조해 `03_phase3-close_cross.md` 를 낸다
- **§4.5 「전제 확인 필요」 12건은 기각 목록이 아니다.** 이 에이전트가 검증하지 않았다는 표시일 뿐이며,
  근거 없이 기각하지 말 것을 규약이 요구한다
- **codex 가 "지적 없음" 이라고 명시한 세 자리(F-4 · union · 축 ④)는 §4.3 에 그대로 남겼다.**
  Claude 가 대신 채우지 않았다
