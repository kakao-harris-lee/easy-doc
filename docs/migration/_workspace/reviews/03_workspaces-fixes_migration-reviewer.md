# 게이트 23 (`03_workspaces-fixes`) — Claude 독립 리뷰 (1회차)

**작성:** migration-reviewer / **일자:** 2026-08-19
**회차:** **1차 — 독립 리뷰.** codex 산출물을 입력으로 쓰지 않았다(1차에서 codex 부재는 정상이므로 실패로
기록하지 않고 재요청도 하지 않았다). 교차 대조표는 이 문서에 없다 — 2차 `..._cross.md` 의 몫이다.
**대상 범위:** `7205d37..e9502a6` (11커밋) — ⓐ `ea36330` 스캐너 처방 · ⓑ `0fe654c` 계약(D-2 · M-405b) ·
ⓒ 게이트 22 조치 9커밋(`fa87aed`~`bfbfc71`)

> **`03_security-scanner_privacy-gate.md` 의 지위.** 리더가 이 파일을 **리뷰 대상**(`ea36330` 에 포함된
> 산출물)으로 지정했으므로 읽었다. **판정을 입력으로 받지 않았다** — 아래 §1 B-1 의 음성 대조 5종은
> 그 문서의 대조 A~E 를 인용하지 않고 **독립 재현**한 결과이고, 결론이 그 문서와 갈린다.

> **리뷰 중 트리가 움직였다(사실 등재).** 착수 시 `HEAD = e9502a6` 였고, 작성 중
> `16f3f48`(*"docs(contract): Phase 3 React↔Kotlin 연결 E2E 범위 산정과 실행 계획"*)이 얹혔다 —
> `docs/migration/_workspace/03_contract-keeper_react-e2e-plan.md` **1파일 343줄 추가, 코드 0줄.**
> **리뷰 범위 밖이며 이 문서의 판정에 넣지 않았다.** 같은 시각 `03_workspaces-fixes_codex-reviewer.md`
> 가 작업 트리에 나타났으나 **읽지 않았다**(1차 독립 리뷰의 규약). 커밋 0 · 코드 수정 0 ·
> `00_progress.md` 무접촉.
>
> **작성 중 다른 레인이 브랜치를 세 번 전진시켰다(사실 등재).** 마감 시점 `HEAD = a1e1925` —
> `16f3f48`(E2E 범위 산정 문서) · `203831d`(프런트 E2E 하네스) · `a1e1925`(CI `e2e` 잡 신설).
> **`backend-kotlin`·`contracts`·`.claude` 변경 0**(`git diff --stat e9502a6..a1e1925` 로 확인) —
> 즉 **이 리뷰가 판정하는 코드·계약·게이트는 하나도 움직이지 않았다.** 리뷰 범위 밖이고 판정에 넣지 않는다.
> 중간에 `frontend/` 툴링 6파일이 미커밋 상태로 보였으나 `frontend/src/` 는 무접촉이었고 이후 커밋됐다 —
> 스캐너 실행 관측(스캔 루트가 `frontend/src` 까지다)은 영향을 받지 않는다.
>
> **이 회차의 변이는 전부 메모리 사본 또는 일회용 worktree(`e9502a6` 고정)에서 했고 본 트리에 쓰지
> 않았다(규칙 5).** 복원은 `git checkout` + worktree 제거이고 **`cp` 미사용**, 제거 전
> `sha256(contracts/easy-doc-v1.yaml) = 7877d263…` 동일 확인. 마감 시점 본 트리의 추적 파일 수정 **0건**,
> 이 문서만 미추적으로 남는다. 커밋 0 · `00_progress.md` 무접촉 · codex 산출물 미열람.

**참조한 계획 문서 절:** §2.2(엔드포인트 계약) · §2.3(보안·개인정보) · §3.1·§3.2(모듈 경계) ·
§4.4(트랜잭션) · §5 Phase 3·Phase 7(즉시 중단 기준) · §6(검증 게이트)
**참조한 정본:** `CLAUDE.md` 규칙 3·4·5 · `kotlin-migration` §「선언한 범위와 실제 도달을 대조한다」 ·
`migration-safety-gate` I-5(소유권 404)·I-7~I-9 · `contracts/easy-doc-v1.yaml` · `00_progress.md`(읽기만)

---

## 0. 요약 — 심각도별 건수

| 심각도 | 건수 | 항목 |
|---|---|---|
| **차단** | **1** | **B-1**(②장치) — `OWNERSHIP-403` 정밀화가 **진짜 403 유출 4형태의 탐지를 잃었다**. 이 배치가 만든 회귀 |
| 수정 필요 | 5 | F-1(D-2 앵커의 숫자→예시 이름 매핑이 계약에 묶여 있지 않다) · F-2(A-3 마스킹에 종류 탐지기 0) · F-3(X-1 시간 축 미측정을 「시간 동형 유지」로 적었다) · **F-4(구조 축 게이트의 도달이 repository 메서드까지 — 실증됨)** · F-5(X-4 음성 대조 수치가 범위 표기 없이 과소 표기) |
| 권고 | 12 | A-1·A-2·A-3(§1) · A-10·A-5(§2) · A-6(§3) · A-7·**A-13**(§4) · A-11·A-12·A-8(§5) · A-9(§6) |
| 판정 필요 | 1 | J-2(스캔 루트의 `tests/**`·`.claude/**` 비대칭) |
| *(취하)* | 1 | **J-1 → A-13.** `max_connections` 400 이 누수를 가리는가 — **실측으로 닫혔다**(Spring 컨텍스트 캐시 32 하드 캡 → 점근선 320 < 400). 내 의심의 전제가 틀렸으므로 판정 필요를 유지하지 않는다 |

**게이트 22 조치 항목별 해소는 §8**, **Phase 3 종료 조건 대비는 §9**, **미실행은 §10**.

**한 줄 판정.** 게이트 22 의 지적 대부분은 **실제로 그 결함을 닫았고** 대체로 탐지형·계약 결속형이라
이 하네스의 규약과 맞는다. 그러나 **같은 배치가 BLOCK 게이트 하나에 새 구멍을 뚫었고**(B-1), 그 구멍은
게이트 22 가 열었던 X-9 와 **정확히 같은 층**(장치)의 문제다.

---

## 1. 도달 범위 점검 — 다섯 축을 가로지르는 필수 구획

> 이 구획은 비워 두지 않는다. 지적이 없으면 **「검토함 — 지적 없음」**, 보지 못했으면 **「미검토(사유)」**로
> 적는다. 둘은 전혀 다른 정보다.

### B-1 [**차단 · ②장치**] `OWNERSHIP-403` 정밀화가 **진짜 403 유출 4형태의 탐지를 잃었다** — 이 배치가 만든 회귀

**마감: 즉시.** (게이트가 지금 CI 에서 돌고 있고, Phase 4 가 소유 자원 — 문서·변환 — 을 대량 추가한다.)

**무엇을 선언했나.** `ea36330` 이 `OWNERSHIP_403_INERT` 세 형태를 패턴 앞에 두고 소비형으로 뺐다.
선언된 무손실 근거는 한 문장이다(`scan_privacy_invariants.py:428` KDoc · privacy-gate 산출물 §3 표):

> **그 자리는 403 응답을 보낼 수 없고, 보낼 수 있는 자리는 여전히 전부 잡힌다.**

③(상수 선언)에 대해서는 더 강하게 적었다 — *"③ 이 특히 무손실이다 — 선언을 빼도 `status(FORBIDDEN)`
같은 사용처는 `FORBIDDEN` 토큰으로 여전히 매치된다."*

**실제 도달 — 독립 재현.** 스캐너 모듈을 적재해 `rule.pattern.finditer` + `rule.refine` 를 옛 패턴과
같은 줄에 나란히 돌렸다(파일 무접촉, 트리 무변경).

| 줄 (프로덕션 형태) | 옛 패턴 | **새 패턴** | |
|---|---|---|---|
| `ResponseEntity.status(403)` | 1 | 1 | 잡힘 |
| `ResponseEntity.status(HttpStatus.FORBIDDEN)` | 1 | 1 | 잡힘 |
| `private const val FORBIDDEN = 403` | 2 | 0 | 의도된 제외 (사용처가 잡힌다) |
| `ResponseEntity.status(FORBIDDEN)` | 1 | 1 | 잡힘 |
| **`private const val OWNER_MISMATCH = 403`** | 1 | **0** | **잃음** |
| **`ResponseEntity.status(OWNER_MISMATCH)`** | 0 | **0** | **토큰이 아예 없다** |
| **`val q7x9k2 = 403`** | 1 | **0** | **잃음** |
| **`private val zk4m1p: Int = 403`** | 1 | **0** | **잃음** |
| **`ResponseEntity.status(`403`)`** (백틱 식별자, main) | 1 | **0** | **잃음** |
| **`FORBIDDEN_STATUS = 403`** (Python 모듈 상수) | 1 | **0** | **잃음** |
| `assertThat(...).isEqualTo(FORBIDDEN)` | 1 | 1 | 잡힘 (진짜 신호 보존 — 옳다) |

**그리고 종단으로도 재현된다.** 독립 재현 결과, `backend-kotlin/api/.../WorkspaceController.kt` 의
`delete()` 에 **비소유자 403 분기를 실제로 심고** CI 와 동일한 명령
(`uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py`, `--no-fail` 없음)을
돌렸을 때:

| 주입 | 옛 스캐너 | **새 스캐너** |
|---|---|---|
| `status(403)` | exit 1 · 검출 | exit 1 · 검출 |
| `status(HttpStatus.FORBIDDEN)` | exit 1 · 검출 | exit 1 · 검출 |
| `const val FORBIDDEN = 403` + `status(FORBIDDEN)` | exit 1 · 검출 | exit 1 · 검출 |
| **`const val OWNER_MISMATCH = 403` + `status(OWNER_MISMATCH)`** | **exit 1 · 검출** | **exit 0 · 미검출** |
| **`val q7x9k2 = 403` + `status(q7x9k2)`** | **exit 1 · 검출** | **exit 0 · 미검출** |
| **`private val zk4m1p: Int = 403` + 사용처** | **exit 1 · 검출** | **exit 0 · 미검출** |
| **`status(`403`)`** | **exit 1 · 검출** | **exit 0 · 미검출** |
| `isEqualTo(FORBIDDEN)`(403 을 **기대**하는 단언 = 진짜 신호) | exit 1 · 검출 | exit 1 · 검출 |
| `@ApiResponse(responseCode = "403")` | exit 1 · 검출 | exit 1 · 검출 |

**리더가 지시한 다섯째 음성 대조 — 이름 되돌림 — 은 성립한다.** `SIGNUP_PASSWORD_FIELD` 를
옛 이름 `SIGNUP_PASSWORD` 로 되돌리면 **exit 1 · `[BLOCK] SECRET-LITERAL` 1건**이 재현된다.
즉 개명이 **부하를 지고 있고**, `SECRET-LITERAL` 탐지 자체는 손대지 않았다(난수꼴 `jwtSecret`
리터럴 주입도 그대로 검출). 옛 이름의 잔존 참조도 0이다(코드 3+3 전건 치환, `tests/` 참조 없음 —
남은 것은 되돌리지 말라는 KDoc 경고와 리뷰 문서뿐). **이 건은 지적 없음.**

**즉 「보낼 수 있는 자리는 여전히 전부 잡힌다」가 거짓이다.** ③ 의 무손실은 **상수의 이름이
`FORBIDDEN` 일 때만** 성립하는 성질이고, 그것을 증명한 회귀(`N11 상수 선언 + 사용처`)도 그 이름 하나로
쓰여 있다. 이름을 바꾸면 **선언은 소비되고 사용처에는 토큰이 없어** 유출이 통째로 사라진다.
②도 같다 — 근거는 「JUnit 테스트 이름」인데 대안은 **모든 백틱 구간**이라, Kotlin main 소스의
백틱 식별자까지 닿는다.

**이것이 왜 차단(②장치)인가.**
1. 막으려는 사건이 §5 Phase 7 **즉시 중단 기준**(타 사용자 노출 · 404 위반)이고, 그 사건을 탐지하는
   유일한 상시 게이트가 이 규칙이다(`migration-safety-gate` I-5).
2. **이 배치가 만든 회귀다.** 옛 패턴은 네 형태를 전부 잡았다. 정밀화의 이득(오탐 7건 제거)과
   손실(진짜 4형태)이 같은 커밋에 들어 있다.
3. `CLAUDE.md` 규칙 4 — **범위는 근거를 넘지 않는다.** 근거는 「이 저장소의 8건이 전부 불활성」인데,
   범위는 「선언 키워드가 있는 모든 `= 403`」·「토큰을 품은 모든 백틱 구간」으로 나갔다.
   이 방향의 결함은 **지적이 늦게 나온다** — 다음 사고를 보이지 않게 만들기 때문이다.
4. **오늘 도달 0 은 방어가 아니다.** 저장소에 403 을 내는 프로덕션 코드가 없다는 census 는 참이지만,
   게이트의 목적이 바로 「없던 것이 생기는 순간」을 잡는 것이다. Phase 4 가 소유 자원을 늘린다.

**은폐형이 아니라는 점은 인정한다.** 마커 0 · 경로 면제 0 · 예산 인상 0 · 심각도 강등 0 이고,
소비형이라 같은 줄의 다른 403 은 남는다(`rule.hardened is None`·`rule.sanctioned == ()` 실측 확인).
**방향은 옳고 폭이 근거를 넘었다.** 처방도 좁다 — ③ 을 **선언된 식별자 자신이 `403`/`FORBIDDEN`
토큰을 품을 때만**으로 조이면(②가 라벨에 이미 요구하는 조건과 같다) c2·d·d2 가 되살아나고,
② 를 애너테이션 라벨과 `fun` 위치 백틱으로 한정하면 h 가 되살아난다. **그리고 그 네 형태를
`tests/test_privacy_scanner.py` 형태 목록에 `blocks=True` 로 넣어야 한다** — 지금 표는
`P4 백틱 함수명 → blocks=False` 로 **미검출을 회귀로 고정**하고 있다.

### A-1 [권고] 제외 집계가 **1건 적게** 보고된다 — 그 숫자를 근거로 삼은 설계 자신을 흠집낸다

옛 스캐너 대비 사라진 적중은 **7건**인데 리포트의 「2차 판정으로 제외」는 **`OWNERSHIP-403 … 6건`**이다
(본 트리에서 직접 실행해 확인 — exit 0, BLOCK 0, 제외 `OWNERSHIP-403` 6 · `SECRET-LITERAL` 1).
원인은 `WorkspaceEndpointReachTest.kt:591` 한 줄에서 옛 패턴이 2매치(`FORBIDDEN`, `403`)를 냈는데
새 결합 패턴은 1매치로 소비하기 때문이다. ② 의 설계 주석이 *"규칙이 눈감은 양을 재는 그 숫자가
거짓이 된다"* 를 근거로 백틱 대안을 조인 만큼, 같은 숫자가 1 어긋나는 것은 적어 둘 값이 있다.

### A-2 [권고] `xfail_strict` 가 전역 설정에 없다 — 「선언된 0」의 정직성은 **이번 3건에 한해** 참이다

`tests/` 전체의 `xfail` 마커는 3건이고 **전건 `strict=True`**, 비-strict 는 0 이다
(`uv run pytest tests/test_privacy_scanner.py -v` → 112 passed · 7 xfailed · **xpass 0** · skip 0).
`HTTP_403_FORBIDDEN`·`SC_FORBIDDEN` 미도달을 조용한 0 이 아니라 `xfail(strict=True)` 로 **선언**한 것은
이 하네스의 규약대로다 — **정직성 확인.**

다만 `pyproject.toml [tool.pytest.ini_options]` 에 `xfail_strict` 가 없다. 즉 strict 는 **마커마다의
규율**이지 강제자가 아니고, 다음에 붙는 `xfail` 은 기본값(비-strict)으로 조용해진다.
한 줄(`xfail_strict = true`)이 그 자리를 닫는다. **범위 선언형은 빈 선언에서 통과하면 안 된다**(규칙 3).

### J-2 [판정 필요] 스캔 루트가 `backend-kotlin/**/src/test/**` 는 훑고 `tests/**`·`.claude/**` 는 훑지 않는다

`SCAN_ROOTS = ["app", "backend-kotlin", "scripts", "frontend/src"]` · `SKIP_PARTS` 에 점 디렉터리 없음
(루트 목록에 없어서 빠진다). 실행 열거 237파일 — `app` 53 · Kotlin main 59 · **Kotlin test 68** ·
`frontend/src` 40 · `scripts` 5.

- **Kotlin 테스트는 훑고 Python 테스트(`tests/**`)는 안 훑는다.** 정확히 그 비대칭이 X-9 의 원인이었다
  (Kotlin 테스트의 집행 단언이 BLOCK 이 됐다). 어느 쪽이 옳은지는 **정책 판단**이다.
- **`.claude/**` 미크롤링**은 스캐너 자신이 자기 규칙 문자열에 걸리는 것을 막는 의도로 보이나,
  그 아래 실제 Python 스크립트가 산다. `CLAUDE.md` 2026-08-13 항목이 **mypy 도달**에서 같은
  형태를 이미 한 번 고쳤다(`uv run mypy . .claude`) — 스캐너 도달은 그 정정을 받지 않았다.

**이 배치가 만든 것이 아니다.** 사실로만 등재하고 판정을 넘긴다.

### 그 밖의 도달 범위 점검 — **검토함**

| 점검 항목 | 결과 |
|---|---|
| 「전역·모든·항상」 새 선언 | **검토함 — 지적 1건.** 추가 줄 전수 grep. 대부분 산문 서술이고, **범위 선언**은 둘 — ⑴ `AuthService.authenticate` KDoc 「보호된 모든 요청이 지나는 유일한 목」 → **강제자 실재 확인**(§3 통과), ⑵ `DeletedAccountTokenReachTest` 「계약이 보호한다고 선언한 다섯 자리를 **전부** 친다」 → **수기 열거 · 강제자 없음**(A-5) |
| 게이트가 지금 어디서 도는가 (도달 0 의심) | **검토함 — §7 이 정본.** 스캐너·Kotlin 빌드·parity 가 CI 에서 실제로 돌았음을 `gh` 로 직접 관측 |
| 대리 경로 측정 | **검토함 — 지적 1건**(F-4, 구조 축이 repository 메서드에서만 돈다). `CountingDataSource` 자체는 **프로덕션 클래스** `JdbcWorkspaceRepository` 를 그대로 계측한다 — 재구현 대체물이 아니다 |
| 검사의 기준이 검사 대상 자신에게서 나오는가 | **검토함 — 지적 1건**(F-1, D-2 숫자→예시 이름 매핑이 계약이 아니라 `ContractSpec` 에 있다). 나머지 기대값은 계약 파일에서 읽는다 |
| 대리 지표로 판정 | **검토함 — 지적 1건**(F-3, 「시간 동형 유지」가 다른 게이트의 수치다) |
| 규칙·패턴 범위가 근거보다 넓은가 | **검토함 — B-1.** 이 회차의 최대 지적이 정확히 이 항목이다 |
| 음성 대조가 붙어 있는가 | **검토함 — §5.** 이 배치의 음성 대조 주장 4묶음을 독립 재현했다(§5 표) |
| 은폐형(무시 패턴·억제·면제·`.gitignore`) 확대 | **검토함 — 0건.** 추가 줄 전수 grep: 새 마커 0 · `--no-fail` 0 · 경로 면제 0 · 심각도 강등 0 · `.gitignore`·`.github` **무변경**(`git diff --stat` 0) · **detekt·ktlint·Gradle 설정 무변경**(`*.gradle.kts`·`gradle/`·detekt·ktlint diff 0) · **새 `@Suppress` 0건**. `# type: ignore` 2건은 전부 사유 주석 동반 |
| 판정 코드가 자기 자신을 검사 대상에 넣는가 | **부분 — J-2·A-3.** 스캐너는 `mypy . .claude` 안에 있다(2026-08-13 정정). 그러나 스캐너 **회귀 파일**은 모듈을 `ModuleType` 으로 받아 `scanner.<attr>` 접근이 mypy 에 보이지 않는다(A-3) |

### A-3 [권고] 스캐너 회귀 파일의 `ModuleType` 타이핑이 mypy 를 무력화한다 — 이미 한 자리가 썩어 있다

`tests/test_privacy_scanner.py:84` 가 `scanner._is_candidate(...)` 를 부르는데 **그 함수는 스캐너에
없다**(모듈 전체 grep — 정의 0, 언급은 KDoc 1곳). 호출자가 0 이라 조용하다.
privacy-gate 가 §5.3 에 **스스로 등재했다 — 그 정직성은 인정한다.**

여기서 더할 것은 **왜 안 걸렸는가**다. `_load_scanner()` 가 `ModuleType` 을 돌려주므로 mypy 는
모듈 속성 접근을 검사하지 않는다(같은 파일의 **객체** 속성 접근 `rule.hardened`·`rule.sanctioned` 는
mypy 가 잡아 `# type: ignore[attr-defined]` 가 붙어 있다 — 대조가 선명하다).
즉 CI `ci.yml:147` 이 *"이 파일이 지키는 것은 CI BLOCK 게이트의 **동작 전부**"* 라고 적은 그 파일에서,
**게이트 함수 이름이 사라져도 타입 검사가 침묵한다.**

영향은 오늘 제한적이다 — 부르면 `AttributeError` 로 **시끄럽게** 깨진다(조용한 통과가 아니다).
그래서 권고다. 처방은 얇다: `_load_scanner()` 반환에 `Protocol` 을 씌우거나, 죽은 헬퍼를 지운다.

### A-4 [**검토함 — 지적 없음**] `LOG-BODY` 회귀는 무사하다 — A-3 이 커버리지 구멍은 **아니다**

A-3 을 과대평가하지 않기 위해 확인했다. `tests/test_privacy_scanner.py` 는 `LOG-BODY` 를
`scanner.scan(...)` 경로로 6곳에서 돌린다(`:141`·`:287`·`:343`·`:551`·`:592`·`:777`).
죽은 헬퍼가 덮던 판정은 **다른 경로로 실제 검증되고 있다**. **지적 없음.**

---

## 2. 계약 준수

### 통과 확인 (실행·대조 근거)

| 대조 | 결과 |
|---|---|
| **D-2 조항 실재** | `contracts/easy-doc-v1.yaml` `paths./workspaces/{workspace_id}.delete.description` 에 *"**둘 다 해당하면 2(마지막 남은 작업 공간)를 낸다**"* 신설. 409 두 갈래 번호(1=문서 잔존 / 2=마지막 하나)가 같은 `description` 안에 있다 |
| **`:2424` M-405b 정정 완전성** | **계약 파일 안에 비면제 「7종」 잔존 0.** 전수 grep 결과 남은 8곳은 전부 `x-changelog`(기록 · 명시 면제) 또는 `x-phase3-measurement` 의 **자기 재분류 서술**("2026-08-19까지 7종이었다")이다. `x-improvements` OQ-1 은 6종으로 정정됐고 정정 표시까지 달렸다 |
| **M-405 가 선언한 후속 정정** | changelog M-405 가 *"`00_contract-keeper_test-plan.md` §3 **X-D2c** 행 … 별도 문서 커밋에서 정정한다"* 로 열어 둔 자리를 확인했다 — **이미 닫혀 있다**(그 행이 「필터에 못 닿는 것 **6종**」 + 405 재분류 명시). **선언한 정정 범위와 실제 도달이 일치한다** |
| **X-1 의 401 갈래 선택** | 계약 `components/responses/Unauthorized` 는 예시가 둘이다 — `no_header`("인증이 필요합니다") · `invalid_token`("이메일 또는 비밀번호가 올바르지 않습니다"). `description` 이 *"토큰이 있으나 유효하지 않으면"* 을 후자로 못박는다. 삭제 계정은 **토큰이 있는** 경우이므로 `invalid_token` 이 맞다. 테스트도 `ContractSpec.responseExampleDetail(Unauthorized, invalid_token)` 으로 계약에서 읽는다 — **옳은 쪽** |
| **P-16 fail-open 수정 ↔ 명세 정의** | 명세 §4 P-16 은 *"`WorkspaceListItem` 의 `allOf` 합성 `required`"* 이고 §4-2 성질 6 이 *"`allOf` 갈래를 순회해 `required` 를 합집합으로"* 다. 수정은 `requiredOf` 의 `allOf` 갈래에서 **비매핑 항목을 버리지 않고 `error()`** 로 끊는다 — 정의와 정합. `pathParameters` 에도 같은 형태를 적용했다 |
| **오류 형식 · snake_case · 헤더** | 이 배치가 응답 형태를 바꾼 곳은 **X-1 의 401 하나**이고, 그 401 은 기존 `GlobalExceptionHandler` 경로를 그대로 탄다(새 핸들러 0). `WorkspaceDtos` 변경은 `toString()` 오버라이드뿐 — `@JsonProperty` 무변경, 직렬화 값 무변경 |

### F-1 [**수정 필요**] D-2 앵커가 읽는 것은 **숫자뿐**이고, 숫자→예시 이름 매핑이 계약에 묶여 있지 않다

**마감: D-2 문면 또는 409 갈래 열거를 다음에 손대는 커밋(늦어도 Phase 3 종료 전).**

`ContractSpec.deletionRefusalPrecedenceExample()` 은 계약 산문에서 `둘 다 해당하면 (\d)` 를 집어
**매치가 정확히 1건임을 단언**한 뒤(좋다 — `defaultWorkspaceName` 의 `find` 첫 매치 위험을 되풀이하지
않는다), 그 숫자를 다음으로 바꾼다:

```kotlin
"1" -> HAS_DOCUMENTS_EXAMPLE
"2" -> LAST_ONE_EXAMPLE
```

**이 매핑은 계약의 1./2. 열거 순서를 옮겨 적은 것이고, 그 순서를 읽는 코드가 없다.**
계약이 열거를 재배열하면(1 = 마지막 하나, 2 = 문서 잔존) D-2 조항의 「2」는 이제 `has_documents` 를
뜻하는데 `ContractSpec` 은 여전히 `last_one` 을 돌려주고, 구현이 안 바뀌었으니 **WD-9 는 초록**이다.
즉 **계약이 순서를 뒤집었는데 테스트가 옛 순서로 통과한다** — WD-9 의 KDoc 이 *"이름을 여기 적으면
계약이 순서를 뒤집어도 이 테스트가 옛 순서로 통과한다"* 라고 경계한 바로 그 상태가, 한 층 위에 남아 있다.

**실행으로 확인했다.** 계약 파일의 해당 `description` 을 읽어 `PRECEDENCE_PATTERN` 과 매핑을 그대로
재현하고, 네 상태에서 앵커가 무엇을 돌려주는지 쟀다(계약 파일 무접촉 — 메모리 사본에만 변이).

| 상태 | 앵커 산출 | WD-9 |
|---|---|---|
| 현행 | `last_one` | 초록 (구현과 일치) |
| **채택된 변이 ⑵** — 조항 숫자 `2`→`1` | `has_documents` | **빨강** ← 결속의 증거, 성립한다 |
| **채택된 변이 ⑶** — 조항 문장 제거 | `FAIL(매치 0건)` | **빨강** ← 성립한다 |
| **고르지 않은 변이 ⑷** — 계약의 **열거 1./2. 를 맞바꿈**(조항 숫자는 `2` 그대로) | `last_one` | **초록** ← **계약의 뜻은 `has_documents` 로 바뀌었는데 테스트가 옛 순서로 통과한다** |

**음성 대조가 이 자리를 덮지 못한 이유**도 같다. 채택된 3변이는 ⑴ 구현 순서 뒤집기 ⑵ **조항의 숫자**
2→1 ⑶ 조항 삭제인데, 셋 다 **열거 자체는 건드리지 않는다.** 「선언한 범위(계약이 정본)와 실제 도달
(조항의 숫자까지)」의 어긋남이고, 고르지 않은 네 번째 변이가 그것을 드러낸다.

**처방은 한 줄이다.** 조항이 이미 `**둘 다 해당하면 2(마지막 남은 작업 공간)를 낸다**` 로 **이름을 함께
적고 있다.** 숫자 대신 그 괄호 안 문구를 앵커로 삼거나, 열거 항목 N 의 첫 구절을 함께 읽어 대조하면
매핑이 계약에서 온다.

### A-10 [권고] X-4 는 **codex 가 지목한 두 자리**를 닫았고, 같은 형태가 `ContractSpec` 에 셋 더 있다

수정 커밋의 주석은 원리로 적혀 있다 — *"이 파서가 읽을 줄 모르는 갈래(스칼라 등)가 조용히 사라진다 …
'아무 갈래도 무시하지 않는다'는 주장의 반대였다."* 그 원리를 **두 자리**(`requiredOf` 의 `allOf`,
`pathParameters`)에 적용했다. 파일 전수 조사 결과 같은 형태가 남은 곳:

| 자리 | 형태 | 하류 방어 | 판정 |
|---|---|---|---|
| `headerComponentsByName()` `:158·:162·:166` + `collectHeaderRefs` `:196` | `paths`→오퍼레이션→`responses` 순회에서 비매핑 항목을 버리고, **`$ref` 가 아닌 인라인 헤더 선언을 `?: return@forEach` 로 건너뛴다** | `require(found.isNotEmpty())` 와 `globalHeaderValues()` 의 `error()` 가 **정본을 못 찾는 경우만** 잡는다 | **실질 미해소** — 인라인으로 선언된 헤더는 「같은 이름이 서로 다른 컴포넌트를 가리키면 실패」 대조를 **지나치지 않는다.** 계약 내부 정합의 구멍이고, 나간 바이트에는 오늘 영향이 없다 |
| `requestFieldConstraint()` `:237` | `filterIsInstance` 후 `firstOrNull ?: error(...)` | **`error()` 가 곧바로 터진다** | 해소 불요 |
| `errorDetailUnionTypes()` `:388` | `oneOf` 비매핑 갈래를 버린다 | 소비자가 `union.hasSize(2)` 를 건다 | 백스톱 있음 |

**수정 필요로 올리지 않는 이유**는 아래 둘에 실제 방어가 있음을 확인했기 때문이고, 첫째 자리도 **오늘
나간 바이트에 닿지 않는다.** 다만 「원리로 적고 두 자리에 적용」은 이 하네스가 반복해 만나는 형태이므로
등재한다. **마감: 계약에 인라인 헤더 선언이 처음 생기는 커밋.**

### A-5 [권고] `DeletedAccountTokenReachTest` 의 「보호 자리 **전부**」가 수기 열거다

`/** 계약이 보호한다고 선언한 다섯 자리를 전부 친다. */` — 오늘 참이다(계약 `security` 선언 경로 =
`/auth/me` · `/workspaces` · `/workspaces/{workspace_id}` = 오퍼레이션 5). 그러나 목록이 손으로 적혀 있고,
`AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` 와 대조하는 장치가 없다.

**불변식 자체는 안 깨진다** — 확인이 `authenticate()` 한 곳에 있고 그 함수의 프로덕션 호출자는
`AuthenticationInterceptor` 하나뿐이며(전수 grep 확인), 목록↔계약 대조를
`AuthenticationCoverageContractTest` 가 양방향으로 강제한다. 깨지는 것은 **이 테스트가 재는
「다섯이 서로 구분되지 않는다」의 범위**다 — Phase 4 가 문서 경로를 붙이면 「전부」가 조용히 거짓이 된다.
`PROTECTED_PATH_PATTERNS` 에서 유도하면 닫힌다. **마감: Phase 4 보호 엔드포인트 추가 커밋.**

---

## 3. 보안 불변식

> `privacy-gate` 산출물을 판정 입력으로 쓰지 않았다. 보안 축의 최종 판정 권한은 `privacy-gate` 에 있고,
> 아래는 Claude 관점의 독립 확인이다.

### 통과 확인 — X-1 은 **결함을 실제로 닫았다**

| 확인 항목 | 결과 · 근거 |
|---|---|
| **삭제 즉시 반영** | `AuthService.authenticate` 가 `accessTokens.verify` 직후 `users.exists(userId)` 를 부르고, 거짓이면 `InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)`. **캐시 없음**(코드에 캐시 계층 0) → 삭제가 다음 요청에 반영된다 |
| **매 요청 1회 · 유일한 목** | `authenticate` 의 프로덕션 호출자는 `AuthenticationInterceptor.kt:76` **하나뿐**(전수 grep). 인터셉터는 `WebMvcConfig:56` 에서 `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` 에 붙고, 그 목록은 `AuthenticationCoverageContractTest` 가 계약 `security` 와 대조한다 — 코드로 확인한 단언 넷: ⑴ `declared ⊆ 계약 보호 경로`(공개 경로를 잠그면 빨강) ⑵ `declared ⊇ **서비스되는** 보호 경로`(컨트롤러만 만들고 목록을 잊으면 빨강 — **대상 범위가 `RequestMappingHandlerMapping` 에서 온다**) ⑶ 한 경로 안에서 `security` 가 갈리지 않음(경로 패턴 인터셉션의 전제) ⑷ 서비스되는 오퍼레이션 전건이 계약의 공개∪보호로 분류됨. **선언(「보호된 모든 요청이 지나는 유일한 목」)과 도달이 일치한다** |
| **`exists` 가 이메일을 적재하지 않음** | `SELECT 1 FROM users WHERE id = :id` — 컬럼 0개. PK 인덱스만 탄다. `findById` 로 대신했다면 매 보호 요청이 이메일을 힙에 올렸다 |
| **401 문구 갈래** | 계약 `invalid_token` 예시 — §2 표 참조. **옳다** |
| **위조 토큰의 형태가 옳은가** | `TestJwt.withBrokenSignature` 는 헤더·페이로드를 **그대로 두고 서명 첫 바이트의 1비트만 뒤집는다.** 구조는 유효하고 클레임(`sub`)도 같아, 갈리는 것이 **서명 검증 실패 하나**다 — 이 대조가 재려는 것과 정확히 맞는다 |
| **위조 토큰과 바이트 동일** | `DeletedAccountTokenReachTest` 둘째 케이스가 상태·본문·헤더 이름 집합을 `TestJwt.withBrokenSignature` 응답과 대조. 문구 상수가 `AuthService`·`JwtAccessTokens` 두 모듈에 **각각 선언**돼 있으나(중복), 첫 케이스가 계약 예시와, 둘째가 위조와 묶여 **둘의 분기를 테스트가 잡는다** |
| **반대쪽(살아 있는 동안)** | 첫 케이스가 삭제 **전에** 다섯 경로가 401 이 **아님**을 먼저 단언한다 — 「무엇을 보내도 401 인 구현」과 구분된다. `AuthServiceTest` 도 같은 형태(살아 있을 때 통과 → 삭제 후 예외) |
| **X-23 (KDoc 선언 ↔ 도달)** | `AuthenticationInterceptor` KDoc 의 「계정 삭제」가 이제 실제로 도달하고, KDoc 이 *"확인을 걷어내려면 이 문장도 함께 지워야 한다"* 로 두 자리를 묶었다 |
| **X-1 의 도달 증거** | `WorkspaceContractTest` 가 소유자를 `UUID.randomUUID()` 로 쓰던 16개 호출부를 `newOwner()`(계정 실제 생성)로 바꿔야 했다. **고치기 전 구현에서 유령 계정이 통과하고 있었다는 사실 자체가 증거다** — 이 자기 폭로는 값이 크다 |

### F-3 [**수정 필요**] 「시간 동형 — 유지됐다」가 **다른 게이트의 수치**다 — X-1 이 만든 시간 축은 재지 않았다

**마감: Phase 3 종료 전.**

구현 산출물 §1-3 의 제목은 「시간 동형 — 유지됐다」이고 근거로 **1.023·1.031·1.039·1.103** 을 든다.
그 수치는 **소유권 404 타이밍 게이트**(없는 자원 vs 타인 자원)의 비다. X-1 이 새로 만든 시간 축은
그것이 아니다:

| 경로 | `verify` | `exists`(DB 왕복) | |
|---|---|---|---|
| 위조·만료 토큰 → 401 | 실패 | **돌지 않는다** | |
| 삭제 계정 유효 토큰 → 401 | 통과 | **1회** | **+1 왕복** |
| 살아 있는 계정 → 200 | 통과 | 1회 | |

**즉 「위조 401」과 「삭제 401」 사이에 구조적 시간 차가 생겼다.** 그리고 이 배치의 위조 토큰은
헤더·페이로드가 **같고 서명 1비트만 다르므로**, 두 경로의 차이는 정확히 **DB 왕복 1회**다 — 잰다면
잡음이 적은 깨끗한 측정이 됐을 자리다. `DeletedAccountTokenReachTest` 는
**바이트 축만** 잰다(상태·본문·헤더 이름). 리더가 지시한 「없는 사용자 / 있는 / 위조 토큰 세 경로 HTTP
경계 실측」은 **수행되지 않았고**, 산출물에는 그 사실이 없다.

**실질 위험은 낮다** — 새는 정보는 「이 토큰이 한때 유효하게 서명됐다」이고, 그 토큰을 가진 자는 이미
알고 있다(서명 키가 없으면 유효 서명을 만들 수 없다). 그래서 **차단이 아니다.**
결함은 **판정의 형태**다 — 다른 축의 실측을 「시간 동형 유지」라는 제목 아래 두면, 다음 사람은 이 축이
검토됐다고 읽는다. **「측정했으니 게이트도 산다」의 정확한 형태**이며, cross(22) §3-3 이 ⓐ 로 ⓒ 를 닫지
말라고 한 것과 같은 구조다.

**처방 두 갈래.** ⑴ 세 경로를 실제로 재고 결과를 적는다, 또는 ⑵ **재지 않았다고 적는다**(오늘의 §2-3 이
시간 축 음성 대조 부재를 그렇게 등재한 것과 같은 형태 — 그 절은 정직하다). 어느 쪽이든 §1-3 의 제목과
근거의 불일치는 고쳐야 한다.

### F-2 [**수정 필요**] A-3 마스킹은 **네 자리를 고쳤고 그 종류를 잡는 탐지기는 0**이다

**마감: Phase 4 첫 문서 DTO 커밋**(문서 제목·파일명이 같은 형태다).

고친 것은 정확하다 — `Workspace`·`WorkspaceResponse`·`WorkspaceListItemResponse`·`WorkspaceNameRequest`
의 `toString()` 이 이름을 `***` 로 가리고, `WorkspaceListing`·`WorkspaceListResponse` 는 구성 요소를
타고 따라온다(`WorkspaceNameLeakTest` 가 `listOf(listing).toString()` 과 문자열 템플릿까지 확인).
**직렬화 값은 그대로**이고 그 구분을 `WorkspaceDtoLeakTest` 가 양쪽으로 단언한다 — **응답의 `name` 은
계약 required 이므로 이쪽이 맞다.** 로그 경로 실측: 작업 공간 경로의 로거 **0개**(`api`·`application`·
`infrastructure` 전수 grep — 로거는 `GlobalExceptionHandler`·`ContractErrorReportValve`·`AuthService`·
`FlywayBaselineGuard` 넷뿐이고 모두 식별자·클래스명만 보간). **「오늘 도달 0」 선언은 정직하다.**

**빠진 것은 강제자다.** 두 테스트가 **클래스 넷을 손으로 나열**한다. Phase 4 가
`DocumentResponse(title=…)`·`ExportRequest(filename=…)` 를 만들면 기본 `toString()` 이 그대로 돌아오고
아무것도 걸리지 않는다. 그런데 같은 저장소가 이 형태를 **이미 두 번 놓쳤고**(`AuthenticatedEndpoints`
KDoc 이 *"열거식 목록은 이 저장소가 이미 두 번 놓친 형태다(사적 응답 헤더 10곳 중 4곳 누락)"* 라고 적고,
그래서 그쪽은 **계약에서 유도**한다), `CLAUDE.md` 규칙 4 는 **빈자리를 종류로 댈 수 있으면 그 종류만큼
넓히되 탐지형으로** 가라고 한다. 종류는 명확하다 — **사용자가 적은 문자열을 필드로 가진 `data class`**.

**처방:** `kr.easydoc.core.workspace`·`kr.easydoc.api.workspace`(→ Phase 4 에서 document 패키지 추가)의
`data class` 중 계약이 사용자 콘텐츠로 분류한 필드를 가진 것을 **반사로 열거해** `toString()` 에 값이
없음을 단언한다. 그러면 새 DTO 가 그 커밋에서 걸린다.

### A-6 [권고] `/auth/me` 가 사용자 행을 **두 번** 읽는다

`authenticate` 의 `exists`(SELECT 1) + `readUser` 의 `findById`(SELECT *) = 2회.
성능이지 보안이 아니고, `readUser` 의 `null` 갈래를 남긴 근거(*"인터셉터 밖에서도 불릴 수 있고,
존재 확인이 인증 경계에 있다는 사실에 기대어 `!!` 를 쓰면 두 자리의 결합이 타입에서 사라진다"*)는
타당하다. **사실로만 등재한다** — 최적화가 필요해지면 그때 `authenticate` 가 사용자를 실어 나르는
형태를 고르면 된다.

### 보안 축 — 이 배치가 건드리지 않은 것

`migration-safety-gate` I-1(마스킹 선행)·I-2(LLM 호출 상한)·I-7(AEAD)·I-8(Argon2)·I-9(JWT) 에 닿는
코드 변경 **0건**(전 파일 diff 확인). **미검토가 아니라 대상 없음.**
I-5(소유권 404)는 코드 무변경이나 **그 탐지 게이트가 바뀌었고**, 그것이 B-1 이다.

---

## 4. Kotlin/Spring 관용성

### 통과 확인

| 항목 | 결과 |
|---|---|
| **모듈 경계** | `core/Workspace.kt` 에 더한 것은 `toString()` 과 `const val NAME_MASK` — Spring·DB 의존 0. `api/WorkspaceDtos.kt` 가 `core.workspace.Workspace.NAME_MASK` 를 참조(api→core, 허용 방향). `CountingDataSource` 는 `infrastructure/src/test` 에 있다 — 프로덕션 소스 아님 |
| **`exists` 질의 인덱스** | `WHERE id = ?` 는 PK. `SELECT 1` 이라 index-only. 새 인덱스 불필요 |
| **트랜잭션 경계** | 무변경. `SpringTransactionRunner` 는 KDoc 만 늘었다 |
| **Flyway** | 마이그레이션 파일 **무변경**. A-2 장치가 스키마를 `pg_constraint` 로 **직접 묻는다** — 마이그레이션 파일을 문자열로 읽지 않으므로 *"적용된 것"* 을 재는 것이 맞다 |
| **LLM SDK 타입 유출** | 대상 없음(이 배치에 LLM 코드 0) |

### A-7 [권고] `CountingDataSource` 가 **두 번째 커넥션 풀**을 만든다 — `max_connections` 압력의 일부다

`JdbcWorkspaceRepositoryTest.prepare()` 가 `CountingDataSource(dataSource())` 로 **새 DataSource** 를
잡고 `JdbcClient.create(counting)` 로 두 번째 저장소를 만든다. 계측 자체는 옳지만(§5), 같은 배치가
`max_connections` 를 올린 것과 **같은 자원**을 더 쓴다. 사실로 등재.

### A-13 [권고 · **1차 내에서 판정 필요 → 권고로 내렸다**] `max_connections` 400 은 **유계**다 — 내 의심이 실측으로 닫혔다

`PostgresTestSupport` 가 컨테이너를 `withCommand("postgres","-c","max_connections=400")` 로 띄운다.
KDoc 이 기제를 **정확히** 적었다 — 컨테이너 1개 : 캐시된 컨텍스트마다 HikariCP 풀(기본 최대 10),
`@SpringBootTest` 는 프로퍼티 조합이 다르면 컨텍스트를 새로 만들어 캐시에 남긴다. X-1 이 클래스 하나를
더하자 기본 100 을 넘겨 3건이 빨개졌다(개별 실행은 통과 — 정확한 진단이다).

**내가 처음 제기한 의심은 이것이었다** — 상한을 올리는 것은 증상 제거이고, 컨텍스트가 무한정 늘면
다음에 또 넘겨 또 올리게 되며, 세는 장치가 0 이다. **실측이 그 의심의 전제를 깼다.**

| 항목 | 값 | 출처 |
|---|---|---|
| `@SpringBootTest` 클래스 | **10**(api) + 1(worker — 별도 JVM·별도 컨테이너) | 계수 |
| 실제 생성된 Hikari 풀 | **`HikariPool-1` ~ `HikariPool-10`** | api 테스트 JVM 로그 실측 |
| HikariCP 최대 풀 | **10**(기본값 — `maximum-pool-size` 선언 0, `api/src/test/resources` 부재) | 설정 확인 |
| **실측 피크** | **101 backends** (`pg_stat_activity` 1초 간격 샘플링, `--rerun-tasks` 전 구간) | 실측 |
| 100 으로 되돌렸을 때 | exit 1 · **3건 red · 3 × `too many clients already`** — 산출물의 「3건」과 **정확히 일치** | 실측 |
| **구조적 상한** | Spring TestContext 캐시가 `spring.test.context.cache.maxSize` = **32** 로 하드 캡(어디서도 미override) → **32 × 10 = 320** + Flyway·admin 일시분 ≈ **340 < 400** | 설정 확인 |
| 모듈 간 누적 | **없음** — Gradle 이 모듈 테스트 태스크마다 JVM 을 포크하고 **각각 자기 컨테이너**를 받는다(한 실행에서 컨테이너 3개 관측) | 실측 |

**즉 「무한정 늘어나 다음에 또 넘긴다」는 시나리오가 성립하지 않는다.** 컨텍스트 캐시가 32 에서
막히므로 한 컨테이너의 진짜 점근선은 **320** 이고, 400 은 그 위다. **Phase 4 여유도 실재한다** —
현재 피크 101 에서 400 까지 컨텍스트 구성 약 29개분.

**그리고 기각된 대안이 실제로 작동하지 않는다는 것도 확인됐다.** 두 변형 모두 **43건 red**:
테스트 스코프 `application.yml` 은 메인 설정을 **통째로 가려** Flyway·mvc 설정을 잃고,
Gradle `systemProperty` 로 풀을 2로 조이면 `Connection is not available … (total=2, active=2)` 로 죽는다 —
12스레드 배압 홍수와 `FOR UPDATE` 동시 삭제가 **정당하게 2개 넘는 커넥션을 쓴다.**
**작성자의 기각 근거가 옳았다.**

**그래서 판정 필요에서 권고로 내린다.** 남는 것은 정확성이 아니라 **문면**이다 — KDoc 이 400 을
일화("이번에 넘겼다")로만 정당화해 매직 넘버로 읽힌다. **32(캐시 캡) × 10(Hikari 기본) = 320** 을
적으면 이 값을 무효로 만드는 상수 둘이 이름으로 드러난다. 부수로, 작성자도 나도 **중간값(4~6)을
시험하지 않았다** — 피크를 절반으로 줄이면서 위 두 테스트에 여유를 남기는 갈래가 미검토로 남는다.

**CI 에서 이 값이 처음 도는 것은 `e9502a6` 실행이며 `kotlin` 잡이 success 다**(§7).

---

## 5. 테스트 적정성 — 음성 대조와 단언의 검출력

### 5-1. 코드 대조로 확인한 것 (실행 불요)

| 주장 | 판정 | 근거 |
|---|---|---|
| **X-7** 예외 종류 보존 | **성립** | `runCatching{}` 결과 객체를 보존해 `outcomes.count { it.isSuccess } == 1` + `failures.singleElement().isInstanceOf(ConflictException)`. 종전 `containsExactlyInAnyOrder(true,false)` 는 교착·타임아웃·`StorageException` 도 만족했다 — **정확히 그 구멍이 닫혔다.** 그리고 이 단언이 A-4(격리 수준 전제)의 **장치**가 된다: REPEATABLE READ 로 올리면 40001 → 500 이 되어 `ConflictException` 단언이 깨진다 |
| **X-6** 집단 단언 | **성립** — 다만 A-8 | `byAccount.keys` 가 두 라벨을 **정확히** 포함해야 한다. 종전 본문 distinct 단언만으로는 「한 집단 전부 500 · 다른 집단 전부 401」이 통과했다. 라벨을 상수로 뽑아 「양쪽이 다 과부하됐는가」와 이름을 공유시킨 것도 옳다 |
| **A-2** FK 단정 전제 | **성립** | `pg_constraint` `contype='f' AND confrelid='workspaces'::regclass` — **적용된 스키마**를 묻는다. 마이그레이션 파일 문자열 읽기를 명시적으로 기각한 근거가 정확하다 |
| **X-4** 파서 fail-open | **성립(지목된 자리)** | `filterIsInstance<Map<*,*>>()` → `flatMapIndexed` + `as? Map<*,*> ?: error(...)`. `pathParameters` 도 `mapIndexed` + 같은 형태. codex #4 가 지목한 두 자리는 **닫혔다.** 파일 전수 조사에서 같은 형태 3자리가 더 나왔다 → **A-10** |
| **WD-9 ↔ 명세 §2-4** | **완전 대응** | 명세가 요구한 셋 — 409 · `detail` 이 **마지막 하나 갈래**와 같음 · **문서 잔존 갈래와 다름** · **삭제되지 않았음을 후속 조회로 확인** — 이 테스트가 전건 단언한다(`assertDeclaredStatus` · `isEqualTo(pathExampleDetail(last_one))` · `isNotEqualTo` 대조 · `hasSize(1)`) |
| **WD-9 의 상태가 진짜인가** | **성립** | `insertDocument` 가 `documents` 에 **실제 행**을 넣는다(`workspace_id` FK 포함) — 「마지막 하나 + 문서 있음」이 시뮬레이션이 아니다. 두 409 예시가 계약에 **서로 다른 값**으로 실재하므로 `isNotEqualTo` 가드도 의미가 있다 |
| **D-2 앵커의 결합 범위** | **성립** | `deletionRefusalPrecedenceExample()` 의 소비자는 **WD-9 하나뿐**(전수 grep) — 조항 변이가 다른 케이스로 번지지 않는다는 「과잉 결합 없음」 주장이 구조적으로 뒷받침된다 |
| **`refusalFor` ↔ 계약 D-2** | **일치** | `ownedWorkspaceCount <= 1` 을 먼저 본다 → 겹치면 「마지막 하나」. 계약 *"둘 다 해당하면 2(마지막 남은 작업 공간)"* 와 같다 |
| **`CountingDataSource` 가 대리 구현인가** | **아니다** | `DataSource by delegate` + `Connection` JDK 프록시로 `prepareStatement`·`createStatement`·`prepareCall` 만 센다. 계측 대상은 **프로덕션 클래스** `JdbcWorkspaceRepository.rename` 그대로다 — 재구현 대체물이 아니다. `InvocationTargetException` 을 벗겨 다시 던지는 것도 옳다(안 그러면 `DuplicateKeyException` 단언이 껍데기를 본다) |
| **구조 축이 개수 자체를 못박는가** | **성립** | `containsExactly(1,1,1)` — 「셋이 같다」만 걸면 셋 다 2문장인 구현도 통과한다는 근거가 정확하다 |

### 5-2. 독립 재현 — **술어 층 + Gradle 층 전건 실행**

두 층에서 재현했다. **술어 층**은 판정 로직을 옮겨 메모리 사본에만 변이를 넣었고(트리 무접촉),
**Gradle 층**은 일회용 worktree(`e9502a6` 고정)에서 실제로 테스트를 빨갛게 만들었다.
복원은 전부 `git checkout` + worktree 제거이고 **`cp` 미사용**, 제거 전
`sha256(contracts/easy-doc-v1.yaml) = 7877d263…` 가 본 트리와 동일함을 확인했다(규칙 5).

**기준선(정상 트리 전체 빌드): `./gradlew test` exit 0 · 691 케이스 · 0 red.**
구현 산출물 §7-1 의 모듈별 합(core 359 + application 44 + infrastructure 111 + api 174 + worker 3 = 691)과
**정확히 일치한다** — 산출물의 테스트 수 보고는 독립 확인됐다.

#### Gradle 층 결과

| 주장 | 판정 | 실측 |
|---|---|---|
| **X-3ⓒ** 구조 축 변이(읽고 나서 비교) | **재현 성립** | exit 1 · `JdbcWorkspaceRepositoryTest` **11건 중 정확히 1건** red, 과잉 결합 0. 메시지 `없음=1 타인=1 내것=2` — 주장과 같다 |
| **WD-9 (a)** `refusalFor` 두 갈래 뒤집기 | **재현 성립** | exit 1 · **691 중 1건**(WD-9)만 red |
| **WD-9 (b)** 계약 숫자 `2`→`1`, **구현 무변경** | **재현 성립 — 결속의 증거** | exit 1 · **691 중 1건**만 red. `git status` 가 ` M contracts/easy-doc-v1.yaml` **하나뿐**인 상태에서 빨개졌다 — 테스트가 **계약을 읽는다**. 강제 수단도 실재한다: `build.gradle.kts:146-149` 가 계약 파일을 **모든 테스트 태스크의 선언된 입력**으로 걸어, YAML 만 고쳐도 5개 태스크가 재실행된다 |
| **WD-9 (c)** D-2 문장 제거 | **재현 성립** | exit 1 · 1건 red · 메시지가 주장한 문자열과 **축자 일치**(`…조항이 0 건이다 — D-2 가 사라졌거나 둘로 갈렸다`) |
| **X-6** 집단 단언 | **재현 성립 + 사전 대조까지** | 프로덕션을 변이(`AuthService.login` 부재 계정 갈래의 `verifyAgainstDummy` 제거 = 실제 열거 결함)해 새 단언 red: `과부하가 한 계정 집단에만 걸렸다 … 집단별 개수: {있는 이메일=4}`. **같은 변이에서 옛 단언(본문 distinct)만 되돌리면 exit 0 초록** — 강화가 실제로 부하를 진다 |
| **X-7** 예외 종류 보존 | **재현 성립 + 사전 대조까지** | `delete` 가 `StorageException` 을 던지도록 변이 → red(`Expecting … ConflictException but was StorageException`). **같은 변이에서 옛 `runCatching{}.isSuccess` 로 되돌리면 초록** |
| **X-4 / P-16** 파서 fail-open | **재현 성립 — 다만 수치가 다르다(F-5)** | 옛 파서 + 손상 계약 **exit 0 · 691/691 초록**(fail-open 실증). 새 파서 + 같은 계약 **exit 1 · 20 red**. 새 파서 + 정상 계약 **691/691 초록 — 거짓 양성 0** |
| **`max_connections`** | **상한 인상이되 유계** | A-13 참조 — 실측 피크 101, 구조적 상한 ≈ 320 |

**주장 여섯 건이 전건 성립했고, 그중 둘(X-6·X-7)은 「고치기 전 단언이 같은 변이에서 초록이었다」까지
확인됐다** — 음성 대조의 음성 대조이고, 이 하네스가 요구하는 형태다.

**⑴ X-4 / P-16 파서 fail-open — 재현 성립.** `ContractSpec.requiredOf`(`allOf` 갈래)와
`pathParameters` 의 옛 판·새 판을 그대로 옮겨, 계약에 **스칼라 갈래 2곳**(`WorkspaceListItem.allOf`,
`paths./workspaces/{workspace_id}.parameters`)을 주입하고 돌렸다.

| 계약 | 옛 파서 | 새 파서 |
|---|---|---|
| 정상 | `P-16 required = [created_at, document_count, id, name]` · `P-21 params = [workspace_id]` | **동일** ← 거짓 양성 0 |
| **손상**(스칼라 2곳) | **동일한 결과를 그대로 낸다** ← **fail-open 실증** | **중단** — `WorkspaceListItem 의 allOf[2] 가 매핑이 아니다: 주입된-스칼라-갈래` / `… parameters[1] 가 매핑이 아니다: …` |

**옛 파서가 손상된 계약에서도 정상과 **글자 하나 다르지 않은** 결과를 낸다** — 구현 산출물이 보고한
「종전 파서 + 손상 계약 → 16건 전건 초록」과 정합하고, codex #4 의 지적이 구조적으로 참임을 확인한다.
새 파서는 **주입한 스칼라를 메시지에 그대로 담아** 끊는다.

**⑵ D-2 앵커 — 재현 성립 + 빈자리 하나 발견.** §2 F-1 의 표가 그 결과다(채택된 변이 ⑵·⑶ 은
빨강으로 성립, 고르지 않은 변이 ⑷ 은 초록으로 남는다).

**⑶ Gradle 층 재현은 위 표가 정본이다.** 술어 층 결과와 **어긋난 항목이 없다** — P-16 은 두 층에서
같은 결론(옛 파서 fail-open / 새 파서 중단)이고, D-2 는 술어 층이 예측한 빨강 셋이 Gradle 층에서
그대로 났다. 술어 층으로 Gradle 층을 대체한 자리는 없다.

### F-4 [**수정 필요**] 구조 축 게이트의 도달이 **repository 메서드까지**다 — 선언은 「소유 조건이 SQL 을 떠났는가」

**마감: 서비스 계층에 조회가 추가되는 커밋(늦어도 Phase 4 문서 API).**

시간 축 KDoc 이 구조 축을 이렇게 소개한다 — *"그것을 재는 것은 구조 축이다 …
「소유 조건이 SQL 을 떠났는가」"*. 그런데 실제로 세는 것은

```kotlin
counting.countStatements { countedWorkspaces.rename(owner, id, "새 이름") }
```

즉 **`JdbcWorkspaceRepository.rename` 한 메서드**다. 오늘은 `WorkspaceService.rename` 이
`workspaces.rename(...) ?: throw NotFoundException(...)` 한 줄이라 두 층의 문장 수가 같다(확인함).
그러나 **소유 조건이 SQL 을 떠나는 방식은 둘**이고 게이트는 하나만 본다:

| 이탈 형태 | 구조 축이 잡는가 |
|---|---|
| repository 가 `SELECT` 로 소유자를 읽고 Kotlin 에서 비교 | **잡는다**(내것 2문장) |
| **service 가 먼저 `findById`/`listOwned` 로 소유를 확인**하고 repository 는 그대로 | **잡지 못한다** — repository 는 여전히 1문장 |

**두 번째는 가상의 형태가 아니고, 실행으로 확인했다.** 일회용 worktree 에서 소유 판정을
`WorkspaceService.rename` 으로 올려 — `listOwned()` 선행 조회가 **내 자원일 때만** SELECT 를 하나 더
내도록, 즉 X-3ⓒ 가 잡겠다고 선언한 것과 **구조적으로 같은 결함**을 만들었다. 결과:

| 게이트 | 결과 |
|---|---|
| `JdbcWorkspaceRepositoryTest`(구조 축) | **11/11 초록 · exit 0** |
| `WorkspaceEndpointReachTest`(시간 축 포함) | **22/22 초록 · exit 0** |

**두 게이트를 모두 빠져나간다.** 구조 축은 repository 만 보므로 못 보고, 시간 축은 그 크기를 못 잡는다
(§2-3 이 이미 등재한 성질). 즉 「소유 조건이 SQL 을 떠났는가」라는 **선언된 주제 자체가 한 층 위에서는
검사되지 않는다.**

Phase 4 문서 API 는 `workspace_id` 소유를 확인한 뒤 문서를 다루는 구조가 자연스럽고, 그 확인이 앉는
자리가 정확히 서비스 계층이다.

**차단(②장치)으로 올리지 않은 근거를 밝힌다.** 형태는 B-1 과 같다 — 게이트의 선언된 주제에 실증된
우회가 있다. 다르게 본 것은 **남는 사건의 크기**다. 이 변이에서도 소유권 응답의 관측 채널
(상태·본문·헤더 바이트 동일)은 **독립적으로 살아 있고 실제로 초록이었다** — 응답이 정말 균일했기
때문이다. 빠져나간 것은 **부수 채널(구조·시간) 축 하나**이고, 그 축은 §2-3 이 이미 「배 단위만 잡는다」로
한계를 적어 둔 축이다. 반면 B-1 은 「타 사용자 노출」 본체를 잡는 유일한 상시 게이트였다.
**형태를 결정적으로 보면 차단이 되므로, 그 판단은 리더에게 명시적으로 올린다.**

**처방은 얇다** — 계측을 `service.rename(...)` 으로 올리거나(오늘 값은 그대로 1), 서비스 층 케이스를
한 벌 더 둔다. 지금 고치는 편이 싸다: 오늘 값이 1 이라 케이스를 추가해도 초록이고,
Phase 4 에서 값이 갈리는 순간 걸린다.

### F-5 [**수정 필요**] X-4 음성 대조의 수치가 **범위 표기 없이** 적혀 결합을 실제보다 좁게 보이게 한다

**마감: `workspaces` 종결 전** (X-11/F-2 와 같은 자리·같은 이유).

구현 산출물 §3-1 의 표는 이렇게 적는다 — *"종전 파서 + 손상된 계약 → **16건 전건 초록**"* /
*"새 파서 + 같은 계약 → **5건 빨강**(P-16 · WL-2 · WR-6 · WR-7 · WD-6)"*.

전체 스위트로 재현한 실측:

| 대조 | 산출물 표기 | **실측** |
|---|---|---|
| 옛 파서 + 손상 계약 | 16건 전건 초록 | **691/691 초록 · exit 0** |
| 새 파서 + 손상 계약 | **5건** 빨강 | **20건** 빨강 — `WorkspaceContractTest` **5/16**(열거된 다섯과 **정확히 일치**) + `WorkspaceEndpointReachTest` **14/22**(`itemPath()` 헬퍼 경유) + `DeletedAccountTokenReachTest` **1/2** |
| 새 파서 + 정상 계약 | 16건 전건 초록 | **691/691 초록 — 거짓 양성 0** |

**틀린 수가 아니라 범위가 빠진 수다** — 둘 다 `WorkspaceContractTest` 안의 값인데 한정 없이 적혀 있다.
**두 방향 모두 과소 표기**이고 그것이 위험한 방향이다: fail-open 이 실제보다 작아 보이고(16 vs 691),
새 파서의 검출 범위도 좁아 보인다(5 vs 20).

**이 배치가 방금 같은 종류를 고쳤다는 점이 이 지적의 근거다.** X-11/F-2 정정문이 스스로 적었다 —
*"목록이 틀리면 「과잉 결합 없음」이 무엇을 확인한 진술인지 알 수 없다."* 같은 문장이 여기 그대로
적용된다. 한 줄 한정어(「`WorkspaceContractTest` 기준」)면 닫힌다.

### A-12 [권고] X-7 의 실패 메시지가 **출력되지 않는다** — `withFailMessage` 가 `singleElement()` 를 넘지 못한다

```kotlin
assertThat(failures)
    .withFailMessage("둘째 요청이 ConflictException 이 아닌 것으로 실패했다: %s", failures)
    .singleElement()
    .isInstanceOf(ConflictException::class.java)
```

변이 실행으로 확인된 실제 출력은 AssertJ 의 기본 문구(`Expecting actual throwable to be an instance of
ConflictException but was StorageException`, 내비게이션 라벨 `[List check single element]`)이고,
**저자가 쓴 진단 문장은 어디에도 나타나지 않는다.** `withFailMessage` 는 내비게이션(`singleElement()`)
이후의 단언으로 전파되지 않는다.

단언 자체는 옳게 동작하므로(X-7 은 성립) 권고다. 다만 이 배치는 실패 메시지에 **처방을 담는 것**을
규율로 삼았고(A-2 의 *"제약을 늘렸다면 delete 의 예외 분류를 함께 고친다"*), 그 규율이 여기서만
도달 0 이다. `assertThat(failures.single())` 로 바꾸거나 메시지를 내비게이션 뒤로 옮기면 닫힌다.

### A-11 [권고] 「16 호출부 / 13 빨강」의 차이가 **상태 코드를 단언하지 않는 케이스**를 가리킨다

산출물 §1-4 가 「유령 계정으로 **16번** 통과」와 「**13건**이 빨개졌다」를 같은 문단에 단위 없이 적었다.
계수해 봤다 — `newOwner()` **호출부 16**(정의 1건 제외), 그것을 쓰는 **`@Test` 는 14개**.
즉 14 중 13 이 빨개졌고 **하나는 401 로도 통과했다.**

14개를 훑으면 후보가 하나다 — **WC-11**:

```kotlin
@DisplayName("WC-11 오류 응답의 Content-Type 이 JSON 이다 (X-C4 / E-3)")
fun `오류 응답이 JSON 이다`() {
    assertJsonContentType(createWorkspace(newOwner(), "   "))
    assertJsonContentType(postJson(COLLECTION_PATH, newOwner(), "{}"))
}
```

**상태 코드를 단언하지 않는다.** 401 응답도 JSON 이므로 이 케이스는 422 를 재는 것처럼 보이면서
**아무 오류에서나 통과한다.** X-1 이 우연히 그것을 드러냈다.

수정 필요로 올리지 않는 이유: 이 케이스가 **선언한 것**(`X-C4`/`E-3` = 오류 응답의 Content-Type)은
실제로 맞고, 422 자체는 WC-4·WC-9 가 `assertDeclaredStatus` 로 못박는다. 다만 **어느 오류를 대상으로
재는지가 고정돼 있지 않다** — `assertDeclaredStatus` 한 줄이면 닫힌다.
**정확한 판별은 실행이 필요하다**(§5-2) — 여기서는 「14 중 13」이라는 사실과 유일한 구조적 후보까지만 적는다.

### A-8 [권고] X-6 의 둘째 단언은 **실패할 수 없다**

```kotlin
assertThat(byAccount.values)
    .withFailMessage("한 집단의 과부하 표본이 0 건이다: %s", ...)
    .allSatisfy { assertThat(it).isNotEmpty() }
```

`byAccount` 는 `groupBy` 산출물이라 **빈 리스트를 값으로 갖는 일이 없다.** 이 단언은 항상 참이다.
실제로 일하는 것은 바로 위 `containsExactlyInAnyOrder(ABSENT_LABEL, KNOWN_LABEL)` 이고, 그것으로
codex #6 이 지적한 결함(한 집단만 과부하)은 **닫힌다**. 문제는 실패할 수 없는 단언이 **별도 보장인 것처럼**
실패 메시지까지 달고 서 있다는 점이다 — 「검사했다」의 수를 부풀리는 형태다. **지우거나,
`overloaded` 원본에서 집단별 개수를 세어 하한을 거는 형태로 바꾼다.**

---

## 6. 산출물 정직성

| 항목 | 판정 |
|---|---|
| **F-2(N-18) 정정** | **해소.** `03_kotlin-implementer_workspaces.md` §3-1 이 12 → **16**, 빠졌던 넷(WR-6·WR-7·WD-6 + 「소유권 404 응답 시간 동형」)을 명시. **Claude 13 과 ck 16 이 어긋난 것이 아니라 재현 방법의 좁기가 달랐다**는 사실과 ck 자신의 명세 결함까지 함께 적었다 — 정직하다. 명세(`03_contract-keeper_workspaces-test-spec.md`) N-18 행도 「16건」으로 동기화됐고 「이 행이 정본」을 선언했다 |
| **F-1(X-2) 판정 문구 정정** | **해소.** *"상한이 코드에 복제돼 있지 않다"* → *"상한의 **복제본이 계약에 묶여 있다**"* 로 고치고, `WorkspaceNameRules.kt` 에 값 `50` 이 두 번·문구 두 종이 **그대로 있다**는 사실을 적었다(codex #2 를 인용). 리더 판정(강제 범위 = 테스트 기대값까지)과 채택하지 않은 codex 처방도 명시 |
| **F-3(스캐너 검사 표)** | **부분 해소.** §7 표에 스캐너가 **CI 와 동일 명령**(`--no-fail` 없음)으로 들어갔고 exit 0 이 기록됐다. 다만 「이번 배치부터 §7 의 표에 넣는다」는 **산문 약속**이고 강제자가 없다 — 다음 산출물이 빼도 아무것도 깨지지 않는다. 실질 강제자는 CI 스텝이고 그쪽은 실재한다(§7) |
| **§2-3 「남는 사실」** | **정직.** 시간 축 자체의 음성 대조가 여전히 없다는 것을, 구조 축이 그 자리를 덮었음에도 **별도 축의 장치**라고 구분해 등재했다. cross(22) §3-3 의 요구를 정확히 따랐다 |
| **§8 「하지 않은 것」 8행** | **정직.** X-8·R-2·A-1·X-2 런타임·X-2a·계약 문면 3건·`00_progress.md`·시간 축 음성 대조를 이유와 함께 열거. 계약 파일과 원장 무접촉을 선언했고 **실제로 그렇다**(`0fe654c` 는 contract-keeper 커밋, `00_progress.md` diff 0) |
| **§7-3 음성 대조 격리** | **규칙 5 준수 확인.** 일회용 worktree + `git worktree remove`, `cp` 미사용, 복원 후 sha256 3건 + `git worktree list` 확인을 기록했다 |
| **X-3ⓒ 자기 반증** | **이 배치에서 가장 값이 큰 정직성.** 리더가 지시한 음성 대조를 만들었더니 **빨강이 나오지 않았고**, 그 사실을 숨기지 않고 3회 수치(1.013·1.090·1.051)와 함께 적은 뒤 축을 구조로 갈아탔다. KDoc 에도 *"이 게이트는 「소유 조건이 SQL 을 떠났는가」를 재지 않는다"* 를 남겼다 |
| **§1-4 수치 표현** | **소소한 흠.** 「유령 계정으로 **16번** 통과」와 「**13건**이 빨개졌다」가 같은 문단에 있는데, 16 은 호출부 수이고 13 은 테스트 수다. 단위가 적혀 있지 않다 |

### A-9 [권고] 시간 축 KDoc 의 「그물의 눈이 배 단위」와 문턱 1.5 가 어긋난다 — 다만 **안전한 방향**이다

리더가 지목한 자리를 확인했다. KDoc `:244` 는 *"잡는 크기가 배 단위라는 사실을 여기 적어 둔다"* 인데
`MAX_TIMING_RATIO` 는 **1.5**(50% 격차부터 잡는다). **선언이 장치보다 크지 않다** — 오히려
**과소 선언**이라 위험한 방향이 아니다. 문면만 맞추면 된다.

**여유 수치의 근거 집합도 한 줄 어긋난다.** KDoc 은 *"독립 측정 셋이 1.068 · 1.041 · 1.007 이었고 …
여유가 약 40% 남는다"* 로 적는데, **같은 배치의 산출물 §1-3 이 X-1 이후 재측정으로 1.023·1.031·1.039·
1.103 을 보고한다.** 최고값 **1.103** 을 넣으면 여유는 약 **36%**다. 차이는 작지만, 인용한 세 값이
**X-1 이 왕복을 하나 더 넣기 전**의 측정이라는 점이 적혀 있지 않다 — 근거 집합이 자기 배치의 최신
측정을 빼고 있다. **측정 방법 자체는 건전하다**(경로당 21표본 + 경로별 워밍업 1건 폐기, 고정 시드
교차 순서, 중앙값 비교 — 코드로 확인).

같은 KDoc 안에 남은 **모순 한 쌍**이 더 눈에 띈다. 첫 문단(`:214-216`)이 여전히
*"「먼저 읽고 나서 소유자를 비교한다」는 구현은 … **그 차이가 시간에 남는다**"* 라고 적는데,
`:233-238` 이 **그 문장을 실측으로 반증**한다(*"그 변이가 이 테스트를 통과한다"*). 둘 다 살아 있으면
첫 문단에서 멈춘 사람은 거짓을 읽는다. 첫 문단을 「그렇게 기대했으나 실측은 아래」로 고치면 닫힌다.

---

## 7. CI 실행 관측 (직접 · `gh`)

**게이트 22 의 B-1(X-9) 이 요구한 관측을 이 회차가 직접 수행했다.** 짐작을 붙이지 않는다.

| run | headSha | 시각(UTC) | run 결론 | `quality` | `kotlin` | `frontend` | `llm-lane` |
|---|---|---|---|---|---|---|---|
| `32211120665` | `6fe4357` | 08-19 03:09 | **failure** | **failure**(스캐너 red · 후속 8스텝 skip) | — | — | — |
| `32215743807` | **`ea36330`** | 08-19 04:26 | **cancelled** | **success** | **success** | **success** | **cancelled**(30분 · 스텝 8) |
| `32218223676` | **`e9502a6`**(HEAD) | 08-19 05:07 | **cancelled** | **success** | **success** | **success** | **cancelled**(30분 · 스텝 8) |

**두 run 이 같은 모양이다** — 세 잡 success · `llm-lane` 만 30분에 취소되어 run 결론이 `cancelled`.

**사실 셋.**
1. **X-9 는 잡 층위에서 닫혔다.** `quality` 17스텝 전건 success — 스텝 9(스캐너)부터 스텝 17
   (`uv run pytest`)까지, 게이트 22 에서 skip 됐던 8스텝이 **전부 실행되고 통과했다**. 그중에는
   **스캐너 자신의 회귀**(스텝 13)와 `alembic upgrade head`(스텝 11)도 있다.
2. **run 층위는 여전히 초록이 아니다.** `ea36330` 의 run 결론은 **`cancelled`**이지 success 가 아니다 —
   `llm-lane` 잡의 스텝 8(`-m llm` 실제 API 호출)이 30분(04:26→04:56)에 취소됐다.
   리더 전제의 「run 32215743807 quality **success**」는 **잡 층위에서 정확**하다. 그러나
   **run 배지는 빨강/회색이다** — 「CI 초록」으로 보고하면 대리 지표가 된다.
   (`llm-lane` 은 기존 Phase 5 항목 — 리더가 범위 밖으로 지정.)
3. **HEAD(`e9502a6`)의 세 잡이 success 다.** `max_connections=400`·X-1 이 더한 컨텍스트가
   **CI 러너에서 실제로 기동했다**는 근거이기도 하다(A-13).
4. **`llm-lane` 은 두 run 모두 스텝 8(`-m llm` 실제 API 호출)에서 30분에 취소됐다.**
   이 배치가 만든 것이 아니고(리더가 Phase 5 항목으로 지정) 이 회차의 판정에 넣지 않는다.
   다만 **run 배지가 두 번 연속 초록이 아니라는 사실**은 「CI 초록」이라는 요약이 다시 쓰이지 않도록
   적어 둔다.

**로컬 독립 재현 1건.** 본 트리 HEAD 에서 CI 와 동일 명령으로 스캐너를 직접 돌렸다 —
**exit 0 · BLOCK 0 · 「2차 판정으로 제외」 `OWNERSHIP-403` 6건 · `SECRET-LITERAL` 1건.**
(이 6 이 7 이어야 한다는 것이 A-1 이다.)

---

## 8. 게이트 22 조치 항목별 해소 상태

**「해소」는 Claude 1차 판정이다.** 근거 없는 「해소」는 「부분」으로 내렸다.

| 항목 | 조치 | 판정 | 근거 · 남은 것 |
|---|---|---|---|
| **X-1** 삭제 계정 유효 토큰 | `fa87aed`·`be363c8` | **해소** | 인증 경계 한 곳 · 캐시 0 · `exists` 이메일 미적재 · 다섯 경로 401 수렴 + 위조와 바이트 동일 + **살아 있을 때의 반대쪽** · 유일 목의 강제자 실재(§3). **잔여: 시간 축 미측정(F-3) · 「전부」가 수기 열거(A-5)** |
| **X-23** 인터셉터 KDoc | 〃 | **해소** | 선언이 도달을 얻었고 두 자리를 KDoc 이 묶었다 |
| **X-3ⓑ** 문턱 2.0→1.5 | `b37012c` | **해소** | `MAX_TIMING_RATIO = 1.5`, auth 게이트와 동일. 실측 여유 근거 기록 |
| **X-3ⓒ** 음성 대조 | 〃 | **해소(축 전환)** | 시간 축 변이가 빨강이 아니었음을 **실측으로 밝히고** 구조 축(`CountingDataSource`)으로 갈아탔다. 구조 축 변이는 **11건 중 정확히 1건** red 로 재현됨. **잔여: 구조 축 도달이 repository 메서드까지 — 같은 결함을 서비스 계층에 두면 두 게이트가 모두 초록임이 실증됐다(F-4) · 시간 축 자체의 음성 대조는 여전히 없음(§2-3 에 등재 — 정직)** |
| **X-4** 파서 fail-open | `9e2ce96` | **해소(지목된 두 자리)** | `allOf`·`parameters` 의 비매핑 항목을 `error()` 로 끊는다(인덱스 표시 포함). 명세 P-16 정의와 정합(§2). 옛 파서 fail-open 을 **691/691 초록**으로 실증. **잔여: 같은 형태 3자리 중 하나가 실질 미해소(A-10) · 음성 대조 수치가 범위 표기 없이 과소 표기(F-5)** |
| **X-6** 배압 집단 단언 | 〃 | **해소** | `containsExactlyInAnyOrder(두 라벨)` 이 실제 결함을 닫는다. **프로덕션 변이(부재 계정의 더미 해시 제거)로 red 재현 + 같은 변이에서 옛 단언만 되돌리면 초록** — 강화가 부하를 진다. **잔여: 둘째 단언이 실패 불가(A-8)** |
| **X-7** 동시 삭제 예외 보존 | 〃 | **해소** | 성공 정확히 1건 + 실패가 `ConflictException` 단일. **`StorageException` 변이로 red 재현 + 같은 변이에서 옛 불리언 단언으로 되돌리면 초록.** **잔여: 실패 메시지가 출력되지 않는다(A-12)** |
| **X-8** 배압 500·ERROR | 〃 | **해소(범위대로)** | **코드 무변경**이 옳다(escalate ④ 판정 전). KDoc 에 비율(93.3%)만 사실로 더했다 |
| **X-12 / WD-9 / D-2** | `0fe654c`·`c663714` | **부분 해소** | 계약 조항 신설 + WD-9 + P-22 앵커(매치 유일성 단언 포함). **변이 3건 전건 재현** — 특히 계약 YAML 만 고친 (b) 가 691 중 WD-9 하나만 빨갛게 만들어 **결속이 실증됐다**(`build.gradle.kts:146-149` 가 계약을 모든 테스트 태스크의 선언된 입력으로 건다). **잔여: 숫자→예시 이름 매핑이 계약에 묶여 있지 않다(F-1)** |
| **A-2** FK 하나뿐 전제 | `30cc405` | **해소** | `pg_constraint` 직접 질의 — 적용된 스키마를 잰다. 실패 메시지가 처방을 담는다 |
| **A-3** 이름 `toString()` | 〃 | **부분 해소** | 네 자리 + 두 모듈 회귀 + 직렬화 값 보존 단언. **잔여: 종류를 잡는 탐지기 0(F-2)** |
| **A-4** READ COMMITTED 전제 | 〃 | **해소** | KDoc 에 EPQ 의존을 적고, **X-7 조치가 그 전제를 장치로 잡는다.** 결속을 코드로 확인했다 — `JdbcWorkspaceRepository` 가 잡는 예외는 `DuplicateKeyException`·`DataIntegrityViolationException` **둘뿐**이므로 직렬화 실패(40001 → Spring `CannotSerializeTransactionException`)는 잡히지 않고 500 으로 나간다. 그러면 X-7 의 `isInstanceOf(ConflictException)` 이 빨개진다. **문면과 장치가 같은 것을 가리킨다** |
| **X-11 / F-2** N-18 16건 | `bfbfc71` | **해소** | §6 |
| **X-2 / F-1** 판정 문구 | 〃 | **해소** | §6 |
| **F-3** 스캐너 검사 표 | `3466f6d`+산출물 | **부분 해소** | §6 (산문 약속 · 실질 강제자는 CI) |
| **X-9 / B-1(22)** CI red | `ea36330` | **해소 — 그러나 대가가 있다** | §7 로 잡 층위 초록 직접 관측. **그 처방이 §1 B-1(23) 을 만들었다** |
| **A-1** 개수 상한 | — | **미해소(의도)** | 코드 변경 0 · 사실만 기록 · 계약 소유자와 리더 판단 |
| **X-25 / R-2** 교환비 | — | **미해소(의도)** | 값 무변경 · 현재 값과 유도(`W×P/H ≈ 10건`)를 표로 적음 · 사용자 판단 대기 |
| **X-5 · X-5b · X-15** 계약 문면 | `0fe654c` 일부 | **부분** | `:2424`(X-5b) 는 **해소**(§2 전수 grep). X-5(`x-unmeasured` 과소 표기)·X-15(C-2(i))는 **미해소** — contract-keeper 소유 |
| **X-2a** `field_missing` 기대값 | — | **미해소(의도)** | X-2 판정에 종속 · 마감 Phase 4 |

---

## 9. Phase 종료 조건 대비 현황 (계획 §5 Phase 3)

원장 `00_progress.md` 「Phase 3」 표 7행 기준. **이 회차는 원장을 읽기만 했다(무접촉).**

| 종료 조건 | 이 배치의 이동 | 여전히 `아니오` 인 이유 |
|---|---|---|
| Spring JDBC repository 와 트랜잭션 경계 | `exists` 추가 · A-2 장치 · 구조 축 계측 | 문서·변환 repository 미착수 |
| Argon2·JWT·가입과 기본 작업 공간 원자 생성 | **게이트 21 조치 배치가 이 범위에서 완주**(X-6·X-7·M-1 등) | 게이트 22·23 잔여가 남았고, R-2 용량 결정이 사용자 판단 대기 |
| `/auth/*` · `/workspaces/*` 엔드포인트 | **X-1 로 인증 경계가 실제로 다섯 경로를 덮는다** | Phase 4 문서 경로 미착수 |
| **소유권을 숨기는 404와 unique/check/FK 오류 매핑** | A-2 가 FK 단정의 전제를 장치로, 구조 축이 소유 판정의 왕복 구조를 고정 | **B-1 이 이 행의 탐지 게이트를 약화시켰다.** unique/check/FK 매핑은 workspaces 범위만 |
| React ↔ Kotlin E2E | 없음 | 프런트 무접촉 |
| contract test·React 테스트 통과 | WD-9 신설 · P-22 · P-16 fail-open 차단 | 나머지 11 엔드포인트 계약 테스트 없음 · React 미대조 |
| 계약 개선 3자 동일 + 근거 기록 | D-2·M-405b 가 근거(G2·G1)·`react_impact`·`affected_tests` 와 함께 기록됨 | **3자 중 React 대조 없음**(D-2 는 호출부 부재로 영향 0 — 근거 기록됨) |

**판정 권고(리더 몫).** ⑴ **B-1 을 닫기 전에는 「소유권 404」 행을 움직이지 않는 것**이 맞다 —
그 행의 상시 강제자가 지금 구멍을 가졌다. ⑵ 나머지 여섯 행은 이 배치로 **전진했으나 여전히
`아니오`**이고, 이 회차가 뒤집을 근거를 만든 행은 없다.

---

## 10. 미실행 · 확인 불가 항목

| 항목 | 이유 |
|---|---|
| **A-11 의 「13 vs 14」 실제 케이스** | Gradle 층 재현 범위에 넣지 않았다. WC-11 이 유일한 **구조적** 후보라는 것까지만 확인했고, 실제로 어느 케이스가 401 로 통과했는지는 X-1 이전 상태를 되돌려 돌려야 확정된다 |
| **`max_connections` 중간값(4~6)** | 작성자도 이 회차도 시험하지 않았다(A-13). 극단 둘(기본 10 유지 / 2로 조이기)만 실측됐다 | 대상: X-3ⓒ 구조 축 변이(읽고 나서 비교 → 내것 2문장 → 빨강) · WD-9 3변이 · P-16 손상 계약에서 **빨강이 정확히 5건**인지 · X-6 한 집단만 과부하 변이 · X-7 다른 예외 타입 변이 · A-11 이 지목한 「13 vs 14」의 실제 케이스. **술어 층 재현 2건은 수행했다**(§5-2) — 그것으로 Gradle 층을 대체하지 않는다. **결과가 나오면 2차의 입력으로 넘긴다** |
| **`llm-lane` 결론** | 두 run 모두 스텝 8 에서 30분에 cancelled — 기존 Phase 5 항목(리더 지정 범위 밖) |
| **골든셋** | 대상 없음 — 프롬프트·스타일 규칙·LLM 설정 무접촉 |
| **parity 도메인** | 대상 없음 — 이 범위에 도메인 로직 무접촉. **미검토가 아니라 대상 없음** |
| **프런트엔드** | `frontend/src/api/` 무변경(diff 0). 대조하지 않았다 |
| **240 동시 배압 재현** | 재현하지 않았다(예산). R-2 표의 값은 **인용**이고 이 회차의 실측이 아니다 |
| **`x-unmeasured`(X-5) 후속** | contract-keeper 소유. 이 회차는 `:2424`(X-5b) 만 전수 확인 |
| **codex 관점** | **1차이므로 정상.** 부재를 실패로 기록하지 않는다 |

---

## 11. 2차(교차 종합) 재호출에 넘기는 것

- **B-1 을 codex 가 봤는가.** codex 가 스캐너 정밀화의 손실 축을 짚었는지, 아니면 다른 축을 봤는지가
  이 회차 교차 대조의 최대 관심이다. **어느 쪽이든 B-1 의 근거(독립 재현 2층 — 술어 층 + CI 명령 종단)는
  지우지 않는다.**
- **F-1(D-2 매핑)·F-2(A-3 탐지기)·F-3(시간 축)·F-4(구조 축 층위)·A-5·A-10** 은 전부
  「선언 범위 대 실제 도달」 계열이다.
  codex 가 같은 자리를 다른 이름으로 짚었을 가능성이 높으므로 **같은 대상은 한 행으로 묶되 양쪽 원문을
  각주로 남긴다.**
- **J-2(스캔 루트 비대칭)** 만 판정 필요로 올린다. **J-1 은 취하했다** — 실측이 전제를 깼으므로
  판정 필요를 유지하지 않았다(A-13). 이 취하 자체를 2차 교차표에 한 행으로 남긴다: codex 가 같은
  자리를 다르게 봤다면 그 근거가 살아 있어야 한다.
- **Gradle 층 재현은 이 1차 안에서 완료됐다**(§5-2). 구현 산출물의 음성 대조 주장 **여섯 건이 전건
  성립**했고, 그 과정에서 **새 지적 셋**(F-4 의 실증 · F-5 · A-12)과 **내 지적 하나의 취하**(J-1→A-13)가
  나왔다. 2차는 이 결과를 **이미 반영된 상태로** 받는다 — 별도 이월 없음.
- **F-4 의 심각도는 형태로 보면 차단(②장치)이다.** 남는 사건의 크기를 근거로 수정 필요에 두었고
  그 판단 근거를 §5 에 전부 적었다. **리더가 형태를 결정적으로 보면 차단으로 올라간다** — 2차 교차표에
  이 갈림을 한 행으로 세운다.
- **게이트 22 의 충돌·판정 미결 항목**(X-2 강제 범위 · X-5 · X-13 · X-15 · X-16 · X-25)은
  이 배치에서 **의도적으로 열려 있다.** 리더 판단이 내려졌는지 2차에서 확인하고, 없으면 그대로 다시 올린다.
