# 게이트 25 — 하네스 몫 3건 조치 기록

**작성:** 하네스 레인 / **일자:** 2026-08-19
**입력:** `reviews/04_crypto_cross.md`(ⓕ·ⓖ·H4·L1) · `reviews/04_crypto_migration-reviewer.md`(C1·L-1·H-1) ·
`reviews/04_crypto_codex-reviewer.md`(H4·B-7) · `reviews/03_security-scanner_privacy-gate.md` §8
**범위:** `.claude/skills/kotlin-migration/SKILL.md` · `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` ·
`tests/test_privacy_scanner.py` · `tests/test_kotlin_gate_reach.py`(신설) · `.github/workflows/ci.yml`
**무접촉:** Kotlin 제품 코드 · `contracts/` · `00_progress.md`(다른 레인 병렬)
**규칙 5 준수:** 모든 변조 실험은 일회용 `git worktree` 두 개에서 했다. 본 저장소는 변조하지 않았고
`cp`·`stash` 를 쓰지 않았다. 워크트리 간 전달은 `git diff` + `git apply`, 복원은 `git checkout -- .`.

| # | 커밋 | 대상 |
|---|---|---|
| 1 | `e572476` | SKILL.md — 심판문 자기 편집 금지 |
| 2 | `aad5ca5` | 스캐너 403 종류 전환 + 회귀 |
| 3 | `8f3730f` | Kotlin 가드 실재·실행 대조 장치 + `ci.yml` |

---

## 1. L1 — 심판문 자기 편집 (처방 ⑴ 채택)

`SKILL.md` 「리뷰 게이트 (필수)」 절에 한 문단을 더했다. **다른 문장은 건드리지 않았다.**

> **심판문은 심판 대상이 고치지 않는다**: 조치(구현·처방 적용)를 한 레인은 같은 커밋으로
> `docs/migration/_workspace/reviews/**` 를 편집하지 않는다. 재판정·자기 정정은 새 감사 회차의
> 새 파일이나 교차 종합에서만 한다. … 아울러 **`privacy-gate` 는 스캐너를 고치지 않는다 — 감사와
> 처방까지이고, 적용은 하네스 레인의 몫이다.**

근거 3건은 교차 종합 §4-① 이 코드 대조로 확정한 것을 그대로 인용했다(`6be9612`·`01d78a1`·`ea36330` —
셋 다 `fix(...)` 저작 레인 커밋이면서 같은 커밋으로 `reviews/` 를 편집).

**이 배치가 그 새 규칙의 첫 적용이다.** privacy-gate 가 §8 에서 낸 403 처방을 이번에는 하네스 레인이
적용했고, `reviews/**` 는 한 글자도 건드리지 않았다.

**검증:** `tests/test_harness_scope_reach.py` 단독 **exit 0**(37 passed).
공유 트리에서 재면 다른 레인의 미커밋 `00_progress.md` 때문에 3건이 빨갛게 나오므로,
일회용 워크트리에 `git checkout e572476 -- SKILL.md` 로 이 변경만 얹어 쟀다.
(그 3건은 이 변경과 무관하다 — 그 시점 `HEAD` 도 같은 3건이 빨갰고, 이 테스트는 SKILL.md 를 읽지 않는다.
그 뒤 해당 레인이 커밋하면서 해소됐다.)

---

## 2. 스캐너 403 — 종류로 닫았다 (C1 ≡ codex B-7, 같은 원인)

### 2.1 무엇이 문제였나

`_403_TOKEN` 은 **이름 열거**였다. 게이트 23 이 두 이름을 더했고(`HTTP_403_FORBIDDEN`·`SC_FORBIDDEN`),
게이트 25 에서 **그 다음 라이브러리 상수**가 곧바로 무적중으로 드러났다(`HttpURLConnection.HTTP_FORBIDDEN`).
같은 열거가 **반대 방향으로도** 샜다 — 열거된 이름이 문자열·후행 주석 안에 있어도 출구 없는 BLOCK 이었다.

**두 결함은 같은 원인의 두 얼굴이다.** 어휘를 보지 않는 이름 열거는 열거 안에서 과잉 적중하고
열거 밖에서 무적중한다. 두 관점이 서로를 읽지 않은 채 같은 구조적 처방에 도달했다.

### 2.2 무엇을 바꿨나 — 비대칭 두 갈래

| 갈래 | 무엇을 보나 | 자리를 묻는가 | 왜 |
|---|---|---|---|
| **식별자** `_403_NAME` | `FORBIDDEN`/`Forbidden` 을 품은 식별자 **전체**(종류) | **묻는다** — `_403_STATUS_SITE` | 종류로 넓히는 대가를 자리로 치른다 |
| **숫자 리터럴** | `\b403\b` | 묻지 않는다 | 이름이 403 을 안 품은 상수(`const val OWNER_MISMATCH = 403`)를 잡는 통로가 그 **선언 줄** 하나뿐이다 |

응답 자리는 둘이다 — ⓐ 호출 인자(`sendError(`·`status(`·`ResponseStatusException(`·`ResponseStatus(`·
`HTTPException(`·`ResponseEntity(`·양성 단언 `isEqualTo(` 등) ⓑ 값 산출(`return`/`throw`/`raise`/`->`/초기화 `=`).

**ⓑ 가 없으면 좁아진다.** `fun deny(): HttpStatus = HttpStatus.FORBIDDEN` 은 호출을 거치지 않고,
옛 판은 그것을 잡고 있었다. ⓑ 는 변수 경유 한 단계도 함께 닫는다(`val deny = HttpStatus.FORBIDDEN`).

**은폐 장치 0** — 마커 0 · 경로 면제 0 · 예산 인상 0 · 심각도 강등 0. 오탐 해소는 면제가 아니라
**자리 제한의 부산물**이다. 뺀 것은 신호가 아니라 **신호가 아닌 자리**다.

**적재 시점 자기검사 신설** — ③(상수 선언 제외)이 이름으로 빼는 것은 **전부** 응답 자리 탐지에
다시 잡혀야 무손실이다. 목록을 손으로 적지 않고 `_403_TOKEN` 에서 **뽑아** 대조한다(사본이 갈리는
것이 게이트 23 ⓐ 가 겪은 형태라서다).

### 2.3 음성 대조 — CI 동일 명령, 전수(`--rule` 없이)

주입 파일은 **신규 생성 후 삭제**(기존 파일 무수정). 일회용 워크트리에서 옛 판/새 판을 `git apply` 로 갈아 끼웠다.

| 대조 | 주입한 줄 | 옛 판 | **새 판** |
|---|---|---|---|
| 0 기준선 | — | exit 0 | **exit 0** |
| M | `response.sendError(HttpURLConnection.HTTP_FORBIDDEN)` | **미검출** | **검출** |
| V | `fun denyValue(): HttpStatus = HttpStatus.FORBIDDEN` | 검출 | 검출(유지) |
| F1 | `val label = "HTTP_403_FORBIDDEN"` | **오탐 BLOCK** | **통과** |
| F2 | `val order = 1 // SC_FORBIDDEN 설명` | **오탐 BLOCK** | **통과** |
| 주입 제거 | — | exit 0 | **exit 0** |

**실트리 전수 리포트는 옛 판과 바이트 동일**(검사 파일 수만 252 → 253 — 다른 레인이 그 사이 추가한
Kotlin 테스트 파일 하나. 이 변경과 무관). 즉 이 전환은 저장소의 실제 트리에서 후보를 하나도
새로 만들지 않았고, 하나도 잃지 않았다.

### 2.4 회귀 — 형태 목록 25 → 38, 열거 테스트를 종류 테스트로 교체

| 회귀 | 무엇을 잰다 |
|---|---|
| **G1~G7** | 넓힘. 라이브러리 상수 2형태 · 값 산출 3형태(`return`·초기화·`->`) · 예외 타입 이름 · Django 생성자 |
| **Q1~Q6** | 자리 제한의 대가. 문자열·후행 주석·파일명 정화 상수 사용처·`in` 연산·**비교(`==`)**·토큰 품은 합법 함수명 |
| **N1~N25 / P1~P9** | 옛 형태 전건 유지. `blocks` 값이 하나도 뒤집히지 않았다 |
| `test_403_식별자_탐지가_이름_열거가_아니다` | **옛 열거 테스트의 대체.** 저장소 어디에도 없는 이름(`ZZ_UNSEEN_LIB_FORBIDDEN_STATUS`)이 응답 자리에서 잡히고, 문자열 안에서는 안 잡히는지 — 결과가 아니라 **기제**를 잰다 |
| `test_응답_자리_목록이_403_과_무관한_호출을_삼키지_않는다` | `sendError(404)`·`status(500)`·`return HttpStatus.OK` 가 후보가 아님 — 자리 자체를 표지로 삼지 않았다는 증거 |
| `test_불활성_상수_제외는_탐지와_같은_토큰_조각을_쓴다` | 갱신. 숫자 갈래 존치 + `_403_TOKEN` → 응답 자리 **포함 관계** |

### 2.5 닫지 않은 종류 — `xfail(strict=True)` 로 **선언**한다

| # | 형태 | 왜 안 닫았나 |
|---|---|---|
| **D1** | `response.sendError(base + 3)` — 계산값 | 문면에 403 표지가 없다. 완전한 해소는 정의-사용 추적(파서)이 필요하고 근거가 없다. 계산의 출처가 `= 403` 리터럴이면 그 선언 줄이 BLOCK 으로 남는다(N14~N16) |
| **D2** | `statuses.add(HttpStatus.FORBIDDEN)` — 응답 자리 밖 호출 인자 | 자리 제한의 정확한 대가다. 넓히려면 그 호출이 왜 응답 자리인지 실측 근거가 있어야 한다 |

`strict=True` 라 누가 이 형태를 탐지에 넣으면 `xpass` 로 뒤집혀 **시끄러워진다**.
게이트 23 이 잔여를 선언 없이 남겼다가 게이트 25 에서 같은 자리가 다시 발견된 것이 이 장치의 근거다.

### 2.6 ★ 리더 확인 요청 — `@ApiResponse(responseCode = "403")`

리더 지시의 완료 기준은 이것을 **통과** 목록에 두었다. **이번 구현에서는 BLOCK 으로 남았고,
그 이유는 두 가지다.**

1. **지시한 기제로는 닿지 않는다.** 지시문은 *"호출 자리 밖의 `FORBIDDEN` **단어**는 보지 않음"* 으로
   기제를 규정했다. `@ApiResponse(responseCode = "403")` 이 걸리는 것은 **단어가 아니라 숫자 리터럴**
   `403` 이다. 그리고 **숫자 갈래는 자리를 물을 수 없다** — 물으면 같은 지시의 다른 완료 기준
   (`const val X = 403` + 사용처 4형태 BLOCK)이 통째로 깨진다. 그 네 형태를 잡는 유일한 통로가
   **선언 줄의 숫자 리터럴**이고, 선언 줄은 응답 자리가 아니다. 즉 두 완료 기준은 **양립하지 않는다.**
2. **privacy-gate 가 이 자리를 기각으로 명시했다**(§3 「기각한 더 넓은 갈래」): *"문자열 리터럴 전체 제외는
   `@ApiResponse(responseCode = "403")` 같은 **403 응답 선언**을 조용히 삼킨다. 그것은 이 불변식이 봐야 할
   신호다."* 회귀 N10 이 그 기각을 고정하고 있다.

**어느 쪽도 지우지 않고 병기한다**(CLAUDE.md 리뷰 게이트 규약). 리더가 ①을 유지하려면
N10 을 `blocks=False` 로 뒤집는 별건 판정이 필요하고, 그때 잃는 것은 「계약이 403 을 선언하는데
아무도 안 보는 상태」의 탐지다. **이 배치는 탐지를 잃지 않는 쪽으로 두었다.**

### 2.7 실측

```
uv run pytest tests/test_privacy_scanner.py tests/test_harness_scope_reach.py
  → 180 passed · 7 xfailed · exit 0        (직전 166 passed · 5 xfailed)
uv run ruff check .claude tests            → exit 0
uv run ruff format --check .claude tests   → exit 0
uv run mypy . .claude                      → Success, 137 files, exit 0
전수 스캔(CI 동일 명령)                     → exit 0
```

---

## 3. H4 — Kotlin 게이트 파일을 삭제해도 CI 가 통과하던 자리

### 3.1 문제

`./gradlew build` 는 **있는 테스트를 전부** 돌린다. 파일을 지우면 스위트는 그대로 초록이고 사라진
것은 아무 데서도 신고되지 않는다. `ci.yml:263-264` 가 그 경계를 스스로 선언해 두었고, 선언대로
클래스별 `--tests` 스텝은 **core 탐지기 둘**에만 붙어 있었다. 그 사이 Phase 3~4 에서 가드가 늘어
**21개가 그 상태**였다.

### 3.2 장치 — `tests/test_kotlin_gate_reach.py` (Kotlin 무접촉)

**후보 목록은 코드에서 실제로 열거해 확정했다** — 23개. 리더가 든 후보와 대조하면:
`EncryptionSchemaTest` 는 실제 이름이 **`EncryptionSchemeSchemaTest`** 이고, 리더 목록에 없던
`ContractErrorBodyReachTest`·`DeletedAccountTokenReachTest`·`PasswordHashingBackpressureReachTest`·
`PrivateResponseHeadersReachTest`·`AuthDtoLeakTest`·`WorkspaceDtoLeakTest`·`WorkspaceNameLeakTest`·
`CoreModuleBoundaryTest`·`ParityDeclarationSyncTest`·`PromptInjectionGuardTest`·
`PromptTextSnapshotTest`·`StyleRuleDataSnapshotTest`·`ProvenanceCreationSitesTest`·
`MaskedTextGatewayTest` 가 같은 families 로 발견됐다.

| 축 | 무엇을 닫나 |
|---|---|
| **정확 일치** | 선언(23) ↔ families 스캔 발견 집합이 **양방향**으로 같아야 한다. 파일 삭제 → 선언 쪽 잔여. 선언 없는 새 가드 → 발견 쪽 잔여(가드가 **조용히 늘지도** 않는다). **빈 선언은 트리가 비지 않는 한 통과 불가**(규칙 4 ⑶) |
| **내용 결속** | FQCN 마다 `package` 줄과 타입 선언이 글자 그대로인 파일이 **정확히 하나**. 이름 치환·사본 방어 |
| **개수 상수** | 파일과 선언을 함께 지우면 `GUARD_CLASS_COUNT` 도 고쳐야 한다 — diff 가 두 자리에 난다 |
| **실행 대조** | Gradle 리포트 XML 에 tests > 0. 중첩 클래스(`FQCN$…`)도 센다 |
| **자기 배선** | `ci.yml` 이 이 경로를 실제로 돌리는지 + 요구 모드를 **`env` 로** 켜는지. 장치 **밖** 축이다(규칙 6) |

**실행 대조를 환경 변수로 켜는 이유.** 로컬 리포트는 마지막에 돌린 것만 남는다 — 실측으로,
다른 레인이 `:core:test --tests …PlainBodyTest` 를 한 번 돌리자 `core` 리포트 디렉터리가 통째로
그 하나로 바뀌었다. 그 상태를 「가드가 안 돌았다」로 읽으면 상시 오경보가 되고, **상시 오경보는
결국 이 스텝을 끄게 만든다.** 대조가 뜻을 갖는 곳은 전체 빌드 직후뿐이므로 거기서만 켠다.
변수를 빼면 대조가 모든 잡에서 조용히 skip 이 되므로, 그 자리는 **`ci.yml` 의 `env` 매핑을 읽는**
별도 축이 지킨다.

### 3.3 `ci.yml` 배선

- **quality 잡** — `uv run pytest tests/test_kotlin_gate_reach.py`(경로 명시). Gradle 없이 되는
  선언 ↔ 트리 대조를 값싼 잡에서 먼저 돌린다. 가드 파일 삭제는 여기서 먼저 빨개진다.
- **kotlin 잡** — `build` 뒤, `working-directory: .` + `env: KOTLIN_GATE_REACH_REQUIRE_REPORT: "1"`.
  방금 만들어진 리포트로 실행 대조를 강제한다.

잡 순서·아티팩트 전달은 **필요 없었다** — 같은 워크플로의 kotlin 잡이 이미 `uv sync --locked` 로
Python 환경을 갖고 있고, 리포트는 같은 러너의 디스크에 있다.

### 3.4 음성 대조 — 9종 (일회용 워크트리, 본 저장소 무변조)

| 대조 | 변조 | 결과 | 잡은 축 |
|---|---|---|---|
| 0 | — | **exit 0** (26 passed · 1 skipped) | 기준선 |
| A | 가드 파일 1개 삭제 | **exit 1** (2 failed) | 정확 일치 + 내용 결속 |
| B | 파일 + 선언 삭제, 개수 상수 유지 | **exit 1** | 개수 상수 |
| C | 클래스 선언만 개명(파일 유지) | **exit 1** | 내용 결속 |
| D | `GUARD_CLASSES = ()` | **exit 1** (2 failed) | 빈 선언 · 정확 일치 |
| E | `ci.yml` 에서 요구 모드 `env` 제거 | **exit 1** | 자기 배선 |
| F | quality 잡 경로 명시 스텝 제거 | **exit 1** | 자기 배선 |
| F2 | kotlin 잡 요구 모드 스텝 제거 | **exit 1** | 자기 배선 |
| G | 요구 모드 ON + 리포트 부재 | **exit 1** | 실행 대조 fail-closed |
| H | 요구 모드 ON + 리포트 완비(중첩 형태 절반) | **exit 0** (27 passed) | **초록 경로 확인** — 상시 빨강이 아니다 |
| I | 요구 모드 ON + 가드 1개의 실행 기록만 제거 | **exit 1** | 실행 대조 |
| 복원 | — | **exit 0** | 잔여 0 |

**F 는 첫 판이 초록이었다.** 배선 확인을 `"      - name:"` 문자열 분할로 세었는데, 스텝 앞 주석
블록은 잘리면 **앞 스텝의 조각**에 붙는다. 그 주석이 이 파일 경로를 언급하고 있어서 quality 잡
스텝을 통째로 지워도 「경로 명시 스텝이 하나 있다」가 참이 됐다 — **주석이 배선을 대신 증명하는
형태**다. 게이트 16~19 가 잡은 것과 같은 종류라 YAML 파싱(`run`·`env` 만 읽는다)으로 갈아탔고,
그 뒤 F·F2·E 가 전부 빨개졌다. 이 문단은 그 사실을 남기려고 적는다 — **새 기제의 첫 자기 빈자리를
음성 대조가 잡았다.**

### 3.5 이 장치가 닫지 않는 것 (적어 둔다)

1. **가드인데 families 이름을 안 쓰는 클래스**는 발견되지 않는다. 손으로 선언에 넣어야 하고,
   넣지 않으면 조용하다. families 를 넓히는 것은 그 이름의 가드가 실재한다는 근거가 생겼을 때 한다.
2. **파일·선언·개수를 한 커밋에서 함께 지우는 편집** — 리뷰가 최종 방어선이다.
   `ci.yml:263-264` 가 같은 자리에서 같은 문장을 적었고, **한 칸 더 옮기지 않는다.**

### 3.6 실측

```
uv run pytest tests/test_kotlin_gate_reach.py tests/test_privacy_scanner.py \
              tests/test_harness_scope_reach.py
  → 206 passed · 1 skipped · 7 xfailed · exit 0
uv run ruff check .claude tests            → exit 0
uv run ruff format --check .claude tests   → exit 0
uv run mypy . .claude                      → Success, 138 files, exit 0
```

`skipped` 1건은 실행 대조다(로컬 = 요구 모드 OFF). CI 의 kotlin 잡에서 켜진다.

---

## 4. 리더에게 남기는 것

| # | 항목 | 성격 |
|---|---|---|
| 1 | **`@ApiResponse(responseCode = "403")` 를 통과로 바꿀 것인가** (§2.6) | **판정 필요.** 지시의 두 완료 기준이 양립하지 않는다. 지금은 탐지를 잃지 않는 쪽(BLOCK 유지) |
| 2 | 스캐너 미도달 2종(D1 계산값 · D2 응답 자리 밖 인자) | 선언 완료(`xfail(strict)`). 넓힘은 근거가 생길 때 |
| 3 | 가드 클래스 families 밖 가드 | 선언 완료(§3.5-1). 새 가드가 families 이름을 쓰면 자동으로 강제된다 |
| 4 | 원장(`00_progress.md`) 반영 | **이 레인이 하지 않았다** — 다른 레인 병렬 작업 중이라 무접촉 지시를 지켰다 |
