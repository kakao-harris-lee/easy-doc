# 게이트 24 (`03_phase3-close`) — Claude 독립 리뷰 (1차)

**작성:** migration-reviewer / **일자:** 2026-08-19
**회차:** **1차 — 독립 리뷰.** codex 산출물은 이 회차의 입력이 아니다(리더가 병렬로 띄운다).
교차 대조표는 만들지 않으며, codex 부재를 실패로 기록하지 않는다.
**대상 범위:** `9b9d8ad..2a4523d` (7커밋)
— `01d78a1`(스캐너) · `b401039`(원장·가드) · `f51295b`·`b529108`·`560c292`·`b9097f6`(Kotlin 4) · `2a4523d`(산출물)
**참조 계획 문서 절:** §2.2(계약) · §2.3(보안 불변식) · §3.1·§3.2(모듈·기술 고정) · §4.5·§4.6(parity 위험) ·
§5 Phase 3·Phase 7(즉시 중단 기준) · §6(게이트) · `CLAUDE.md` 규칙 3·4·5 ·
`kotlin-migration` 「선언한 범위와 실제 도달을 대조한다」 · `migration-safety-gate` I-3·I-5·I-7~I-9
**입력 산출물:** `reviews/03_workspaces-fixes_cross.md`(게이트 23 정본 §6-② 조치 목록 11) ·
`03_kotlin-implementer_workspaces-fixes2.md` · `03_kotlin-implementer_workspaces-fixes.md`(정정판) ·
`reviews/03_security-scanner_privacy-gate.md` · `00_progress.md`(**읽기만**)

**이 회차가 하지 않은 것:** 코드 수정 0 · 커밋 0 · `00_progress.md` 무접촉 · `contracts/` 본 트리 무접촉 ·
다른 리뷰어 산출물 무접촉(읽기만) · Phase 종료 **판정 없음**(재료만 낸다).

**규칙 5 준수.** 모든 음성 대조는 일회용 worktree(`/tmp/g24rv`, `2a4523d` 고정)에서 했다. 복원은
`git checkout` + `git worktree remove --force` 이며 **`cp` 미사용**. 복원 후 sha256 3건이 본 트리와 동일:
`WorkspaceService.kt = 49cf6f7d…` · `AuthService.kt = b17cf15f…` · `contracts/easy-doc-v1.yaml = 7877d263…`
(구현자가 보고한 값과 같다) · `00_progress.md = 83b0cbe3…`. worktree 제거 후 본 트리 추적 파일 수정 **0건**.

---

## 0. 한 줄 요약과 심각도 집계

**이 배치의 네 조치는 전부 「그 결함을」 닫았다** — 넷 다 **내가 독립으로 변이를 만들어 재현**했고
구현자가 보고한 실패 건수·메시지와 정확히 일치했다(과잉 결합 0). 스캐너 네 형태도 전건 복원됐다.

그러나 **닫힘의 근거 문면 쪽에서 새 결함이 넷 나왔다.** 셋은 이 배치 자신이 F-3·F-5 로 고친 것과
**같은 종류**(범위·근거를 실제보다 좁거나 넓게 적음)이고, 하나는 **계약이 자기 안에서 두 가지를
말하는데 그중 한쪽만 인용해 판단의 근거로 삼은 것**이다. 그 하나(R-1)에 **Phase 3 종료 조건 행 3 의
개폐가 직접 걸려 있다.**

| 심각도 | 건수 | 항목 |
|---|---|---|
| **차단** | **0** | — |
| **수정 필요** | **4** | **R-1**(401 균일화 근거가 계약 두 조항 중 한쪽에만 선다) · **R-2**(원장 행 4 근거가 없는 파일을 지목) · **R-5**(탐지기 제외 사유가 미강제 불변식) · **T-3**(산출물 §6 이 조치 목록의 절반을 열거하지 않음) |
| **권고** | **6** | R-3(가드 주석 사유가 같은 커밋과 어긋남) · R-4(인용 CI run 전체 결론 `cancelled`) · R-6(A-1 제외 집계 6 vs 7 — 마감 넘김) · C-3(F-1 D-2 앵커 미조치) · K-2(계측이 프로덕션 풀 밖) · T-2(항목 9 묶음 최소 6건 미해소) |
| **판정 필요** | **1** | **S-2** — `sendError(SC_FORBIDDEN)` 미탐이 **CI 동일 명령에서 실측 재현**됐다. 게이트 23 충돌 Ⅱ 가 「정직한 선언 ↔ 실제 미탐」으로 갈렸는데 **둘 다 참**이었다 |
| *(검증 통과 — 지적 아님)* | 8 | S-1 · S-3 · S-4 · C-2 · K-1 · K-3 · P-1 · T-1 |

**차단 0 의 근거.** §5 Phase 7 즉시 중단 기준(①사건)에 닿는 경로를 이 배치에서 찾지 못했고,
게이트 무력화(②장치)도 새로 생기지 않았다 — 반대로 이 배치는 잃었던 장치 하나를 되찾았다(S-1).
S-2 는 **기존 결함의 실측 확인**이지 이 배치가 만든 회귀가 아니므로 차단으로 올리지 않고
**판정 필요**로 privacy-gate·리더에 넘긴다.

---

## 1. 도달 범위 점검 — 다섯 축을 가로지르는 필수 구획

> 이 구획은 비워 두지 않는다. 지적이 없으면 **「검토함 — 지적 없음」**, 보지 못했으면 **「미검토(사유)」**.

### R-1 [**수정 필요**] 계약이 401 균일화 열거를 **두 곳에서 다르게** 적는다 — 무헤더 제외의 근거가 한쪽에만 선다

**마감: Phase 3 종료 전** (게이트 23 조치 4 의 마감). **수신자: contract-keeper → 리더.**

구현 산출물 §4-1 은 무헤더를 균일화 대상에서 뺀 근거로 *"계약 `x-auth.failure_uniformity` 는
**토큰 만료·위조·계정 삭제**를 같은 401·같은 메시지로 묶고 … **무헤더는 그 열거에 없고**"* 를 든다.
계약을 직접 읽었다. **그 조항에 대해서는 참인데, 계약에는 같은 것을 말하는 조항이 하나 더 있고
그쪽에는 무헤더가 들어 있다.**

| 조항 | 위치 | 열거 | 무헤더 |
|---|---|---|---|
| `x-auth.failure_uniformity` | `contracts/easy-doc-v1.yaml:299-302` | 이메일 부재 · 비밀번호 불일치 · 토큰 만료 · 위조 · 계정 삭제 | **없음** |
| `components/responses/Unauthorized.description` | `contracts/easy-doc-v1.yaml:1495-1498` | **헤더 누락** · 토큰 위조 · 만료 · 용도 불일치 · 계정 삭제 | **있음** |

둘째 조항의 문장은 이렇다 — *"**헤더 누락**·토큰 위조·만료·용도 불일치·계정 삭제를 **모두 같은 401,
같은 메시지로 처리한다**"*. 그리고 **세 줄 뒤에서 자기 자신을 반증한다** — *"메시지는 두 가지가
나온다: 헤더가 아예 없으면 `"인증이 필요합니다"` …"*(`:1500`, 「실측 확인 2026-08-12」 병기).

**세 가지 사실을 갈라 둔다.**

1. 구현자가 인용한 조항만 보면 무헤더 제외는 **정당하다.** 판단 자체를 뒤집지 않는다.
2. 그러나 구현 산출물 §4-1 은 둘째 조항을 **문구가 다르다는 근거로만** 인용하고, **같은 블록의
   첫 문장이 헤더 누락을 균일화 열거에 넣고 있다는 사실은 적지 않았다.** 근거를 고를 때 반대편
   조항을 병기하지 않은 것은, 이 배치가 F-3 로 고친 결함(다른 축의 수치를 이 축의 근거로 씀)과
   **같은 층의 문제**다.
3. **판정에 직접 걸린다.** 구현자 실측(§4-3)은 세 갈래 비를 `2.356 → 1.007~1.036` 으로 닫았고
   **네 갈래 비는 `2.575 → 2.801~2.983` 으로 키웠다**(스스로 정직하게 기재). 둘째 조항이 정본이면
   Phase 3 행 3 의 미해결 항목(「401 갈래 시간이 갈린다」, 마감 Phase 3 종료 전)은 닫히기는커녕
   **열거된 집합 위에서 악화**한다.

**나는 어느 쪽이 정본인지 판정하지 않는다** — 계약 조항의 소유자는 contract-keeper 이고 최종 판단은
리더다. 다만 **계약이 자기 안에서 갈린 채로 남아 있고 그 사실이 어느 산출물에도 없다**는 것은
행 7(계약 개선 3자 동일 + 근거 기록)의 입력이기도 하다 — 3자 동일 이전에 **계약 1자가 자기와 다르다.**

### R-2 [**수정 필요**] 원장 행 4 의 근거가 **그 단언이 없는 파일**을 지목한다

**마감: 행 4 판정 전.** **수신자: 리더(원장 소유).**

`00_progress.md` Phase 3 표 행 4(소유권 404)의 근거 열, `b401039` 이 **새로 적은** 문장:

> `WorkspaceContractTest` WR-3·WD-2 가 소유권 거절에 **404 이고 403 이 아님**을 `isNotEqualTo(FORBIDDEN)` 로 명시하고

전수 grep 결과 그 넷은 **전부 다른 파일**에 있다:

| 앵커 | 실제 위치 |
|---|---|
| `WR-3 타인 소유 작업 공간 → 404 이고 **403 이 아니다**` | `WorkspaceEndpointReachTest.kt:181` |
| `WD-2·WD-3 타인 자원 삭제 → 404(403 아님)…` | `WorkspaceEndpointReachTest.kt:354` |
| `isNotEqualTo(FORBIDDEN)` | `WorkspaceEndpointReachTest.kt:189` · `:362` |
| `private const val FORBIDDEN = 403` | `WorkspaceEndpointReachTest.kt:591` |

`WorkspaceContractTest.kt` 에는 `FORBIDDEN` 문자열이 **0건**이다.

**도달 자체는 실재한다** — `WorkspaceEndpointReachTest` 는 `api` 모듈 테스트라 `ci:kotlin` 에서 돈다.
틀린 것은 **근거 위치**이고, 이 행을 나중에 검증하려는 사람이 지목된 파일을 열면 아무것도 없다.
「근거 없는 `예`는 `아니오`로 취급한다」는 이 표의 규칙이 근거 **위치**의 정확성을 전제로 선다.

### R-3 [권고] 가드 주석이 적은 「`ci:quality` 미기재 사유」가 같은 커밋의 자기 근거와 어긋난다

`tests/test_harness_scope_reach.py`(`b401039` 추가분) ⑷:

> `ci:quality`(스캐너 `OWNERSHIP-403`)를 **함께 적지 않은** 이유는 그 게이트가 이 배치에서 네 형태의
> 탐지를 잃어 **조치 대기 중**이라, 지금 적으면 근거를 넘는 선언이 되기 때문이다.

같은 커밋의 원장 행 4 근거 열은 **복원이 이미 끝났다**고 적는다 — *"→ **복원 완료 `01d78a1`** …
다만 그 커밋은 아직 리뷰를 받지 않았다"*. `b401039` 의 부모가 `01d78a1` 이므로 **「조치 대기 중」은
그 시점에 거짓**이고, 실제 사유는 **「리뷰 미수령」**이다.

**방향은 안전한 쪽이다**(선언을 근거보다 **좁게** 적었다 — 규칙 4). 그러나 사유가 사실과 다르면
다음 사람이 「복원됐으니 이제 적어도 된다」와 「리뷰가 와야 적는다」 중 어느 문턱인지 알 수 없다.

### R-4 [권고] 원장이 승격 근거로 인용한 CI run 의 **전체 결론은 `cancelled`** 다

실측(`gh run view 32222249150`):

| 항목 | 값 |
|---|---|
| headSha | `b3f76b25f01a…` ✓ 원장과 일치 |
| 잡 `e2e` | **success** ✓ |
| 잡 kotlin · quality · frontend | success ✓ |
| 잡 `llm-lane` | **cancelled** |
| **run 전체 conclusion** | **`cancelled`** |

원장은 *"llm-lane 진행 중"* 으로 적었다 — **기록 시점에는 사실**이다. 다만 나중에 이 run 을 여는
사람은 `cancelled` 배지를 본다. 행 5 승격의 실질 근거(잡 `e2e` success)는 **전건 재확인됐으므로
승격 자체는 선다.** 문면만 갱신 대상이다.

**부수 관측(이 배치 자신의 CI).** run `32225305372`(headSha `2a4523d` — **이 리뷰의 대상 HEAD**):
quality **success** · kotlin **success** · frontend **success** · e2e 진행 중.
즉 스캐너 복원분과 Kotlin 4커밋이 **CI 에서 실제로 초록**이다(구현자 §7 의 로컬 exit 0 을 CI 층에서 확인).

### R-5 [**수정 필요**] 탐지기의 제외 사유가 **강제되지 않는 불변식**이다 — 이 배치가 스캐너에서 고친 것과 같은 형태

**마감: Phase 4 첫 문서 DTO 커밋.** **수신자: `kotlin-implementer`.**

`SensitiveToStringReachTest` KDoc 이 「`String` 이 아닌 파라미터는 대상이 아니다」의 사유로 이렇게 적는다:

> `Secret`·`MaskedText` 처럼 값을 감싸는 타입이 **자기 `toString()` 에서 이미 가리기 때문**이고,
> 숫자·enum·UUID 는 콘텐츠를 담지 못한다.

**앞 절반은 오늘의 다섯 래퍼에 대해 참이고**(`Secret`·`PasswordHash`·`MaskedText`·`ModelDraft`·
`ReviewedBody` 전부 재정의 확인), **그 성질을 강제하는 장치는 없다.** 스캐너 ③ 이 *"선언을 빼도
사용처가 토큰으로 잡힌다"* 를 무조건형으로 적었다가 이번 게이트에서 정정한 것과 **정확히 같은 구조**다
— 조건부 성질을 제외의 무조건 사유로 쓰는 것.

**음성 대조로 확인했다**(일회용 worktree, `:api:test --tests "*SensitiveToStringReachTest*"`):

| 주입 | 결과 |
|---|---|
| ⓐ `@JvmInline value class ProbeTitle(val v: String)` — 새 래퍼, 기본 `toString()` | **초록** (미검출) |
| ⓑ 그 래퍼를 든 `data class ProbeDocResponse2(id, title: ProbeTitle)` | **초록** (미검출) |
| ⓒ `class ProbePlainHolder(val title: String)` — `data class` 아님 | **초록** — KDoc §「막지 못하는 것」에 **선언돼 있다** |
| ⓓ `data class ProbeMemoResponse(id, memo: String)` — 이름 규약 밖 | **초록** — **선언에 없다** |

- **ⓐⓑ 가 이 지적의 본체다.** Phase 4 가 문서 제목·파일명을 값 타입으로 감싸는 것은 이 저장소의
  기존 관용구(`MaskedText`·`ModelDraft`)이고, 그때 새 래퍼가 재정의를 빠뜨리면 **DTO 도 래퍼도
  둘 다 검사 밖**이다. 처방은 넓히는 것이 아니라 **탐지형 하나 추가**로 족하다 — 「`kr.easydoc.**`
  의 `@JvmInline value class` 중 `String` 을 감싸는 것은 `toString()` 이 값을 내지 않는다」.
  `Masking.kt` 가 이미 그 사실을 **주석으로 열거**하고 있으므로(`value class 셋`) 근거는 서 있다.
- **ⓓ 는 [권고]** — 이름 규약의 내재적 한계이고 `@UserContent` 가 메우라고 있는 자리다. 다만
  KDoc 「막지 못하는 것」 목록이 ⓒ 만 적고 ⓓ 를 적지 않아, **선언한 빈자리보다 실제 빈자리가 넓다.**

### R-6 [권고] A-1(제외 집계)이 **마감을 넘겨 미해소**다 — 수치를 정확히 잰다

게이트 23 조치 목록의 A-1 마감은 「**1 과 동시**」(= 이 배치)였다. 스캐너 모듈을 직접 적재해
`OWNERSHIP-403` 패턴을 전 스캔 파일에 돌렸다:

```
리포트가 세는 inert 매치 수 : 6
그 구간이 삼킨 403 토큰 수  : 7
```

리포트는 여전히 `OWNERSHIP-403 … 6건` 이다. 원인은 A-1 이 진단한 그대로 —
`WorkspaceEndpointReachTest.kt:591`(`private const val FORBIDDEN = 403`) 한 줄이 토큰 **둘**을
품는데 결합 패턴이 **한 매치**로 소비한다. ② 의 설계 주석이 *"규칙이 눈감은 양을 재는 그 숫자가
거짓이 된다"* 를 근거로 백틱 대안을 조인 만큼, **같은 숫자가 1 어긋난 채로 남아 있다.**

### 이 배치가 새로 들인 「전역·모든·항상」과 은폐형 — **전수 확인, 0건**

- **은폐형 0.** 추가된 줄 전수 grep(`@Suppress`·`noqa`·`type: ignore`·`privacy-allow`·`@Disabled`·
  `@Ignore`·`@Tag`·`exclude`·`baseline`·`.gitignore`) — 코드 쪽 신규 억제·면제·무시 패턴 **0건**.
  유일한 `# type: ignore[attr-defined]` 추가는 신설 구조 회귀 안의 동적 적재 모듈 접근이고 사유가
  붙어 있다(기존 A-3 와 같은 형태 — T-2 에 이월로 적는다).
- **`@UserContent` 는 넓히기만 한다 — 실증.** 「면제로 쓸 수 없다」는 선언을 실행으로 확인했다.
  `RepairPrompt` 에서 애너테이션을 떼자 `KNOWN_SENSITIVE_TYPES` 바닥이 빨개졌다 —
  `민감 판정 기준이 아래 타입에 닿지 않는다: [RepairPrompt]`. **면제 시도가 red 로 끝난다.**
- **범위 선언형이 비어 있지 않다 — 실증.** `MIN_PRODUCTION_CLASSES = 60` 하한과
  `containsAll(KNOWN_SENSITIVE_TYPES)` 바닥이 둘 다 실제로 판정을 낸다(위 면제 시도가 그 증거).
- **가드 자신의 음성 대조.** `tests/test_harness_scope_reach.py` 가 도달 표기 삭제를 실제로 잡는지
  확인했다 — 행 6 에서 `ci:e2e` 하나를 빼자 **3건 red**(`test_판정이_실제로_행을_보고_있다` 외 2),
  본 트리 복원 후 sha256 동일. **도달 0 아님** — 이 가드는 `tests/` 아래라 CI `quality` 에서 돈다.

---

## 2. 보안 불변식 — I-3 · I-5 · `migration-safety-gate`

> **보안 축의 최종 차단 권한은 `privacy-gate`** 에 있다. 아래는 그 판정의 입력이다.

### S-1 [검증 통과] 스캐너 네 형태 전건 복원 — **독립 재현**, 그리고 ② 좁힘이 만든 새 미탐 **없음**

CI 와 **같은 명령**(`uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py`)을
쓰고, 술어 층이 아니라 **실제 Kotlin 프로덕션 파일**(`backend-kotlin/api/src/main/kotlin/kr/easydoc/api/Probe403.kt`)에
형태를 하나씩 넣고 종단으로 잰 뒤 삭제했다. 기준선: 무주입 전수 스캔 **exit 0**.

| # | 형태 | exit | OWNERSHIP-403 BLOCK 히트 |
|---|---|---|---|
| F1 | `const val OWNER_MISMATCH = 403` + 사용처 | **1** | **1** |
| F2 | `val q7x9k2 = 403` + 사용처 | **1** | **1** |
| F3 | `private val zk4m1p: Int = 403` + 사용처 | **1** | **1** |
| F4 | `` status(`403`) `` (백틱 식별자 인자) | **1** | **1** |

**네 형태 전건 BLOCK.** 구현자·privacy-gate 보고와 일치한다.

**② 를 `fun` 자리로 좁힌 것이 새 미탐을 만들지 않는가** — 좁힘은 **제외를 줄이는** 방향이라 정의상
미탐을 만들 수 없지만, 소비 구간이 옮겨 가며 같은 줄의 진짜 403 을 삼킬 가능성은 실측으로 확인했다:

| # | 형태 | exit | 읽을 점 |
|---|---|---|---|
| N4 | `` val `403` = 1 `` + 사용처 (`fun` 밖 백틱) | **1** (히트 2) | 백틱이 `fun` 밖이면 **더 이상 제외되지 않는다** |
| N5 | `` fun `403 이 아니다`(): Int = 403 `` | **1** | 이름은 소비되고 **같은 줄의 진짜 403 은 남는다** |
| M5 | `` fun `403 아님`() { return 403 } `` (다음 줄) | **1** | 〃 |
| M6 | `` fun `403 아님`(): String = status(`403`) `` | **1** | 한 줄에 백틱 둘 — 이름만 소비, 인자는 잡힘 |
| N6 | `` fun `403 이 아니다`(): Int = 200 `` | **0** | 순수 라벨 — **의도된 제외** |
| P7 확인 | KDoc 인라인 백틱 | (전수 exit 0) | 주석은 어휘 층 `_advance` 가 코드에서 제거하므로 규칙에 닿지 않는다 — 코드로 확인 |

**이름 관문의 방향도 실측했다** — 「제외해도 어딘가에서 잡힌다」가 조건이 아니라 성질이 됐는지:

| # | 형태 | exit | 읽을 점 |
|---|---|---|---|
| N1 | `const val FORBIDDEN = 403` + 사용처 | **1** | 선언은 제외되고 **사용처의 `FORBIDDEN` 토큰**이 잡는다 |
| N2 | `const val FORBIDDEN_STATUS = 403` + 사용처 | **1** | `\b` 경계가 안 서므로 **선언이 BLOCK 으로 남는다** — 설계대로 |
| N3 | `const val forbidden = 403`(소문자) + 사용처 | **1** | 토큰이 대소문자 구분이라 사용처는 안 잡히고 **선언이 잡는다** |
| M8 | `var FORBIDDEN = 403` + 사용처 | **1** | `va[lr]` 갈래 확인 |
| M3 | `val code = 400 + 3` + 사용처 | **0** | 산술 — 정밀화 이전부터의 한계, 이 배치와 무관 |
| M4 | `val code = "40" + "3"` | **0** | 〃 |

**구조 회귀도 실물이다.** 신설된 `test_불활성_상수_제외는_탐지와_같은_토큰_조각을_쓴다` 가
탐지 패턴과 이름 관문이 `_403_TOKEN` **한 조각**에서 나오는지를 문자열로 고정한다 — 형태 목록이
결과를 재고 이 테스트가 **이유**를 재는 이중 구조다. 방향이 옳다.

### S-2 [**판정 필요**] xfail 두 형태는 **CI 동일 명령에서도 실측 미탐**이다 — 게이트 23 충돌 Ⅱ 는 배타적 대립이 아니었다

**마감: Phase 4 소유 자원(문서·변환) 진입 전.** **수신자: `privacy-gate` → 리더.**

게이트 23 충돌 Ⅱ 는 Claude 「정직한 선언」 ↔ codex 「같은 실제 미탐」으로 갈렸고 리더 판정 ⑵ 는
「xfail 유지」였다. 나는 **어느 쪽이 옳은지를 새로 주장하지 않고 제3의 근거만 더한다** — 합성 술어가
아니라 **실제 Kotlin 프로덕션 파일에 넣고 CI 와 같은 명령**으로 쟀다.

| # | 형태 | exit | 히트 |
|---|---|---|---|
| X2 | `response.sendError(HttpServletResponse.SC_FORBIDDEN)` — **진짜 403 을 내는 줄** | **0** | **0** |
| X1 | `status.HTTP_403_FORBIDDEN` 계열 사용처 | **0** | **0** |
| X3 | `status(org.springframework.http.HttpStatus.FORBIDDEN)` (대조군) | **1** | 1 |
| M1 | `const val HTTP_403_FORBIDDEN = 403` + 사용처 | **1** | 1 — **리터럴이 있으면 잡힌다** |
| M2 | `const val SC_FORBIDDEN = 403` + 사용처 | **1** | 1 — 〃 |

**읽을 점 셋.**

1. **두 관점이 배타적이지 않았다.** xfail 은 **정직한 선언이면서 동시에 실제 미탐**이다.
   M1·M2 가 잡히는 것은 저장소 안에서 `= 403` 리터럴로 **선언**할 때뿐이고, X1·X2 처럼
   **라이브러리 상수를 쓰기만 하면** 선언이 없어 아무 데서도 안 잡힌다.
2. **오늘 유출은 없다** — 프로덕션 403 코드 0건(전수 census). 잃은 것이 아니라 **원래 없던 탐지**다.
3. **그러나 그 형태가 이 저장소의 사각지대가 아니다.** `sendError` 는 이 저장소가 이미 다루는
   관용구다(`PrivateResponseHeadersReachTest:151` 이 `HttpServletResponse.sendError()` 의 `/error`
   재디스패치를 명시적으로 잰다). 서블릿 API 를 직접 쓰는 층 —
   `AuthenticationInterceptor`(`HandlerInterceptor`) · `PrivateResponseHeadersFilter`
   (`OncePerRequestFilter`) · Tomcat Engine 밸브 — 이 이미 셋 있고, **소유권 판정이 그 층으로 올라가면
   `sendError(SC_FORBIDDEN)` 이 자연스러운 표현**이다. Phase 4 가 소유 자원을 대량 추가한다.

**처방 후보(넓히지 않는 형태)** — `\b` 경계를 푸는 것은 KDoc 이 적은 대로 오탐 무리를 들이므로
기각이 옳다. 대신 **실측된 세 이름만** 토큰에 더하는 갈래가 있다(`sendError` · `SC_FORBIDDEN` ·
`HTTP_403_FORBIDDEN`). 범위가 근거를 넘지 않는다. **판단은 privacy-gate·리더의 몫이고 나는 짓지 않는다.**

### S-3 [검증 통과] 401 균일화 — 새 오라클 없음 · 예외 미삼킴 · **구조 회귀 음성 대조 독립 재현**

- **nil UUID 조회가 오라클을 만들지 않는다.** `users.exists(ABSENT_USER_PROBE_ID)` 의 반환은
  **어디에도 쓰이지 않고** 곧바로 `throw failure` 다(`AuthService.kt`). `UUID(0L, 0L)` 는 v4 가 낼 수
  없는 값이고, 검증 실패한 토큰의 `sub` 를 쓰지 않은 두 사유(미검증 입력을 질의 인자로 삼지 않음 /
  공격자가 고른 식별자의 존재를 묻는 통로)는 옳다.
- **예외를 삼키지 않는다** — `exists` 호출이 `try` 밖이라 DB 장애가 성공 경로와 실패 갈래에서
  **똑같이** 전파된다. 「DB 장애 중에는 위조만 401」 채널이 생기지 않는다.
- **회귀의 결속이 둘이다** — 문장 수 균일 + 「더미 조회가 실패를 성공으로 만들지 않는다」.
  둘째가 없으면 nil UUID 행이 우연히 생기는 날 통과가 뒤집힌다. 옳은 짝이다.
- **위조 토큰 도구의 자기 정정이 산술적으로 맞다** — HS256 서명 32바이트 = base64url 43글자,
  마지막 글자의 하위 2비트는 어느 바이트에도 실리지 않는다. `withBrokenSignature` 가 **디코드 후
  첫 바이트의 1비트**를 뒤집는 형태로 고쳐졌고, 둘째 테스트가 그 토큰이 실제로
  `InvalidCredentialsException` 으로 끝남을 함께 건다.

**음성 대조 — 균일화 제거(일회용 worktree)**

```
AuthenticationWorkUniformityTest > 토큰이 든 401 갈래와 성공 갈래가 같은 수의 SQL 문을 낸다 FAILED
  인증 경계가 도는 SQL 문 수가 갈리거나 1 가 아니다 —
  {유효 토큰(성공)=1, 삭제 계정=1, 위조 서명=0, 만료 토큰=0, JWT 형식 아님=0}
2 tests completed, 1 failed
```

**구현자 보고와 정확히 일치**하고 **과잉 결합 0**(2건 중 1건만 빨강). 실패 메시지가 채널의 모양을
그대로 보여 준다. 시간이 아니라 구조로 건 판단은 옳다 — 같은 배치가 시간 축 게이트의 한계를
실측했고(X-3ⓒ 변이가 비 1.013~1.090), **흔들리는 게이트는 곧 꺼진다**는 근거가 이 저장소 안에 있다.

**다만 시간 축 수치 자체는 1관점(구현자)뿐이다** — 나는 101 표본 실측을 재현하지 않았다(§5 미실행).
방향은 privacy-gate 기록 ① 과 일치한다. **네 갈래 비 악화의 해석은 R-1 에 걸려 있다.**

### S-4 [검증 통과] `toString` 종류 탐지기 — 종류 탐지 · fail-closed · 면제 불가 **전건 독립 재현**

| 대조 | 결과 |
|---|---|
| **새 DTO** `data class ProbeDocumentResponse(id: UUID, title: String)` (목록 어디에도 없음) | **4건 중 1건 red** — `kr.easydoc.api.ProbeDocumentResponse (민감 필드: [title])` |
| `valueFor` 가 모르는 타입(`BigDecimal`)을 든 민감 DTO | **red — 끊는다**: *"…타입 java.math.BigDecimal 을 이 탐지기가 만들 줄 모른다"* (조용한 건너뜀 없음) |
| `@UserContent` 를 `RepairPrompt` 에서 **제거**(면제 시도) | **red** — `민감 판정 기준이 아래 타입에 닿지 않는다: [RepairPrompt]` |
| 기준선 | `SensitiveToStringReachTest` 2/2 · `AuthDtoLeakTest` 2/2 초록 |

- **「종류를 잡는다」가 실행으로 성립한다** — 첫 행이 요점이다. 그 타입은 어떤 테스트에도,
  `KNOWN_SENSITIVE_TYPES` 바닥에도 없는데 탐지기가 잡았다.
- **판정을 「재정의가 있는가」로 하지 않은 것이 옳다** — `data class` 는 컴파일러가 `toString()` 을
  언제나 선언하므로 반사로는 구분되지 않고, 형식만 갖춘 재정의도 통과하지 못한다.
- **6타입 전건 실재 확인** — `User`·`UserResponse`(이메일 → `CONTENT_MASK`) · `SentenceIssue`(문장·낱말 →
  길이·표식) · `RepairPrompt`(프롬프트 전문 → 길이) · `Outcome.Body`·`Adoption`(본문 → 길이).
  **직렬화는 가리지 않는다** — `AuthDtoLeakTest` 가 두 축(`toString` 은 가림 / 응답 값은 그대로)을
  각각 단언한다. 계약 `UserResponse.required = [id, email]` 과 어긋나지 않는다.
- **표식을 한 상수(`CONTENT_MASK`)에서 파생시킨 것**이 옳다 — 타입마다 다른 문자열이면 확인하는
  쪽이 목록을 갖게 되고 그 순간 새 타입이 조용히 빠진다. `Workspace.NAME_MASK` 도 그 하나로 모았다.
- **빈자리 두 종은 R-5 로 올린다.**

### 그 밖의 보안 축

- **마스킹 선행 불변식(I-1)** — 이 배치가 건드리지 않았다. `Masking.kt`·`MaskedText` 게이트웨이 diff 0.
- **평문 로그(I-3)** — 이 배치는 로거를 추가하지 않았다. TRACE 프레임워크 로거 3종(privacy-gate 기록 ③)은
  **여전히 미조치**이고 마감이 「Phase 4 문서 본문 진입 전」이다. `toString()` 재정의로 원리상 막을 수
  없는 층이라는 구현자 판단은 옳다(바인딩 파라미터·원시 요청 바이트).
- **소유권 은닉(I-5)** — S-1 로 상시 강제자가 복원됐고, S-2 가 남은 빈자리다.

---

## 3. 계약 준수 — §2.2 · §6

### C-1 → **R-1** 참조 (401 균일화 범위와 계약 열거의 불일치)

### C-2 [검증 통과] union 정확 일치 — **세 방향** 음성 대조 독립 재현 (구현자가 하지 않은 방향 하나 포함)

| # | 주입 | 결과 |
|---|---|---|
| U2 | `oneOf` 에 **스칼라 갈래** 추가 | **33건 중 2건 red**(S-11 · WC-10) — `ErrorResponse.detail 의 oneOf[2] 가 매핑이 아니다 … 주입된-스칼라-갈래` |
| U3 | `oneOf` 에 **유효한 세 번째 갈래**(`type: object`) 추가 | **정확히 2건 red** — `계약이 선언한 갈래 [string, array, object] 와 실제 관측 [string, array] 가 다르다` |
| **U4** | `type: string` → **`type: integer`** 로 치환 (**구현자 미실행 방향**) | **정확히 2건 red** — `[integer, array] ↔ [string, array]` |

**U4 가 커밋 메시지의 주장을 검증한다** — *"계약이 `string` 을 `integer` 로 바꿔도 통과하는 상태였다"*.
그 방향이 실제로 닫혔다. 세 방향 모두 **과잉 결합 0**(같은 두 케이스만 빨강).

- **파서와 소비자를 함께 고친 것이 옳다.** 파서만 고치면 소비자의 복제본
  (`containsExactlyInAnyOrder("string","array")`·`hasSize(2)`)이 계약 변경을 따라가지 않는다.
- **`observedDetailType` 이 대응을 한 곳에 모으고 모르는 모양에서 끊는 것**이 옳다 —
  `isInstanceOf(String::class.java)` 로 두면 계약의 어휘와 JVM 타입의 대응이 테스트마다 복제된다.
- **「계약 `ValidationFailed` 갈래 집합과 일치하는가」에 대한 답**: `ValidationFailed` 는
  `components/responses` 의 응답 정의이고 `schema: $ref ErrorResponse` 로 **같은 스키마**를 가리킨다.
  즉 갈래 집합은 `ErrorResponse.detail.oneOf` **하나뿐**이고 별도 집합이 존재하지 않는다 —
  대조 대상이 동일하므로 불일치할 자리가 없다.
- **계약 복원**: `git checkout`, sha256 `7877d263a36d5fefdba0f86375ca3dabfc1d778b24d778cabb6ba52484977c4d`
  가 본 트리와 동일(구현자 보고값과도 동일).

### C-3 [권고 · **미해소 이월**] F-1(D-2 앵커)이 마감(Phase 3 종료 전)에 닿았는데 손대지 않았다

`ContractSpec.kt:341-345` 는 그대로다:

```kotlin
return when (val choice = matches.single().groupValues[1]) {
    "1" -> HAS_DOCUMENTS_EXAMPLE
    "2" -> LAST_ONE_EXAMPLE
```

계약 산문에서 뽑는 것은 **숫자뿐**이고 숫자→예시 이름 매핑은 여전히 Kotlin 코드 안에 있다.
매치 유일성 단언(`require(matches.size == 1)`)은 좋은 형태로 남아 있다. **게이트 23 조치 6 의
마감이 「Phase 3 종료 전」이고 이 게이트가 그 마지막 자리다.**

### 그 밖의 계약 축 — 검토함

- **snake_case·`{"detail": …}`·`no-store`·`nosniff`·`Location`·RFC 5987·CORS 노출 헤더** — 이 배치의
  diff 에 응답 형태 변경 0. `AuthDtos.kt` 변경은 `toString()` 재정의 한 줄뿐이고 직렬화 무영향
  (`AuthDtoLeakTest` 가 양쪽으로 단언).
- **`Unauthorized` 두 문구(`no_header` / `invalid_token`)** — 인터셉터가
  `AUTHENTICATION_REQUIRED_MESSAGE = "인증이 필요합니다"` 로 계약 예시와 같은 값을 쓴다(확인).
  Bearer 스킴 비교가 대소문자 무시(RFC 9110)인 것도 옳다.
- **401 균일화 범위가 계약 열거와 일치하는가** — **R-1 에서 갈린다.**

---

## 4. parity 위험 — §4.5 · §4.6

### P-1 [검토함 — **대상 없음**]

- **프롬프트 문자열·스타일 규칙·어려운 말 목록·마스킹·정규화·보정 채택·문서 추출·내보내기의 값과
  생성 로직에 변경 0.** 세 도메인 파일의 diff 를 줄 단위로 확인했다 —
  `Prompts.kt`(`RepairPrompt` 에 `@UserContent` + `toString()`) · `StyleRules.kt`(`SentenceIssue` 에
  `toString()`) · `ConvertDocumentUseCase.kt`(`Outcome.Body`·`Adoption` 에 `toString()`).
  **추가된 것은 전부 `toString()`·KDoc·애너테이션뿐**이고 필드·생성자·계산 경로에 손댄 줄이 없다.
- 따라서 **골든셋 실행 대상 없음**이라는 구현자 판정이 옳다. `parity/fixtures/` diff 0.
- **한글 종성·문장 분리·정규식 경계·POI 추출** — 이 배치가 닿지 않았다.
- **`SentenceIssue.toString()` 이 진단값을 남기는 선택**(`kind`·`reason` 유지, `sentence` 는 길이,
  `word` 는 `없음`/표식)은 옳다 — 앞 둘은 우리가 만든 고정 문구다. `word == null` 을 표식과 구분해
  남긴 것도 진단상 값이 있고 콘텐츠를 흘리지 않는다.

---

## 5. Kotlin/Spring 관용성 — §3.1 · §3.2

### K-1 [검증 통과] F-4 계측 진입점 이동 — **음성 대조 독립 재현**, 배선 정당성 확인

**변이:** `WorkspaceService.rename` 이 `listOwned()` 로 소유를 먼저 확인하고 내 자원일 때만 저장소를 부른다
(F-4 가 실증한 바로 그 우회).

```
JdbcWorkspaceRepositoryTest > 이름 변경 요청 하나가 소유 결과와 무관하게 같은 수의 SQL 문을 낸다 FAILED
  이름 변경 요청의 SQL 문 수가 소유 결과에 따라 갈리거나 1 가 아니다 — 없음=1 타인=1 내것=2.
  소유 조건이 WHERE 를 떠났거나(저장소), 유스케이스가 선행 조회를 얹었다(서비스).
13 tests completed, 1 failed
```

**구현자 보고(13중 1 red · `없음=1 타인=1 내것=2`)와 완전히 일치**하고 과잉 결합 0.

**주목할 점 — 무엇이 이 변이를 잡았는가.** 이 변이는 **「없음 ↔ 타인」 은닉을 깨지 않는다**(둘 다 1).
잡아 낸 것은 「셋이 같다」가 아니라 **절대값 못박기**(`containsExactly(1,1,1)`)다. 산출물 §1-2 가 적은
설계 사유(*"「셋이 같다」만으로는 부족해 개수 자체를 못박는다"*)가 **실행으로 확인된다** — 사유와
장치가 같은 것을 가리킨다.

**배선 정당성.** 트랜잭션 관리자가 같은 `CountingDataSource` 를 받는다
(`SpringTransactionRunner(TransactionTemplate(DataSourceTransactionManager(counting)))`) — 다른 것을 주면
`delete` 의 `FOR UPDATE` 가 계측되지 않은 커넥션에서 돌아 **세는 대상과 도는 대상이 갈린다.**
KDoc 이 그 사실을 적었고 코드가 그대로다. 기준선에서 `delete` 거절 1/1 · 성공 3 · `list` 1 이 전부 초록
(13건 중 12 통과, 빨간 하나는 내 변이가 만든 것).

**대상을 넷으로 넓힌 것**이 게이트 23 표 3b(도달이 `rename` 1 오퍼레이션뿐)도 함께 닫는다.
`lockForDeletion` 을 별도 케이스로 만들지 않고 `delete` 의 두 정수(1 / 3)로 분해한 판단도 옳다 —
표면을 늘리는 대신 **요청 단위 정수**로 덮으면 F-4 가 지적한 층위 문제가 재발하지 않는다.

### K-2 [권고 · **이월**] 계측이 여전히 **프로덕션 커넥션 풀 밖**이다 — KDoc 은 정직하나 강제자가 없다

- `CountingDataSource` 가 감싸는 것은 `DriverManagerDataSource`(테스트 전용)이고 프로덕션 HikariCP
  경로는 계측되지 않는다(게이트 23 표 3c — codex 지적, 이 배치에서 안 움직였다).
- **KDoc 이 codex C-3 을 정확히 문서화한 것은 좋다** — 세는 것은 실행이 아니라 **문장 생성**이고,
  `createStatement()` 하나에 SQL 둘을 태우면 계수기는 1 을 본다. *"저장소가 `JdbcClient` 를 벗어나는
  순간 이 계수기의 전제가 깨진다 — 그때 이 KDoc 을 함께 고쳐야 한다"* 까지 적었다.
- **그러나 그것을 강제하는 장치는 없다.** 저장소가 raw JDBC 로 내려가면 KDoc 만 낡고 게이트는
  조용히 초록이다. 「전제가 깨지면 KDoc 을 고쳐라」는 사람에게 거는 규율이지 탐지형이 아니다.
- A-7(`CountingDataSource` 가 두 번째 풀을 만든다 — `max_connections` 압력)도 그대로다.

### K-3 [검토함 — 지적 없음] 모듈 경계 · 트랜잭션 경계 · 배선

- **`core` 의 Spring·DB 비의존 유지** — 신설 `core/privacy/UserContent.kt` 는 순수 Kotlin 애너테이션
  (`@Target(CLASS)` · `@Retention(RUNTIME)`)이고 import 0. `CONTENT_MASK` 는 `const val String`.
- **탐지기의 자리** — `api` 테스트에 둔 것이 옳다(다섯 모듈 중 넷을 런타임에 싣는 유일한 모듈).
  Spring 의존은 `PathMatchingResourcePatternResolver`·`CachingMetadataReaderFactory` 뿐이고
  main 소스가 아니다. `RUNTIME` 유지 사유(적재된 클래스를 읽는다)도 정확하다.
- **`moduleBoundaryCheck`** — CI `kotlin` 잡 success(run `32225305372`, headSha `2a4523d`).
- **`AuthService.authenticate` 의 `try/catch` 범위**가 `accessTokens.verify` 한 줄로 좁고
  `InvalidCredentialsException` 만 잡는다 — 넓게 잡아 다른 실패를 401 로 바꾸는 형태가 아니다.

---

## 6. 테스트 적정성 — §6

### T-1 [검증 통과] 이 배치가 신설한 장치 **4종 전부 독립 음성 대조 재현**

| 장치 | 내가 만든 변이 | 결과 | 구현자 보고와 |
|---|---|---|---|
| F-4 구조 축(서비스 경계) | `rename` 에 `listOwned()` 선행 조회 | 13중 **1 red** · `없음=1 타인=1 내것=2` | **일치** |
| `toString` 종류 탐지기 | 새 DTO `ProbeDocumentResponse(id, title)` | 4중 **1 red** · `(민감 필드: [title])` | **일치** |
| 〃 (fail-closed) | `BigDecimal` 파라미터 주입 | **끊음**(IllegalStateException) | 구현자 미실행 — **추가 확인** |
| 〃 (면제 불가) | `@UserContent` 제거 | **red**(바닥 목록) | 구현자 미실행 — **추가 확인** |
| union 정확 일치 | 스칼라 갈래 / 유효 3번째 갈래 / **`string`→`integer`** | 각각 33중 **정확히 2 red** | 앞 둘 일치 · 셋째 **추가 확인** |
| 401 균일화 | 균일화 제거 | 2중 **1 red** · `{성공=1, 삭제=1, 위조=0, 만료=0, 형식오류=0}` | **일치** |
| 스캐너 네 형태 | 네 형태 각각 프로덕션 파일 주입 | 전건 **exit 1 · BLOCK 1** | **일치** |
| 도달 가드 | 원장에서 `ci:e2e` 표기 1개 제거 | **3 red** | 구현자 미실행 — **추가 확인** |

**과잉 결합은 전 항목 0**(빨개진 것이 정확히 그 장치가 겨눈 케이스뿐).

**이 배치가 새로 쓴 `withFailMessage` 는 실제로 출력된다** — U3·U4·F-4 변이 실행에서 저자 문구가
그대로 찍히는 것을 확인했다(내비게이션 뒤가 아니라 단언 직전에 붙였다). A-12 가 지적한 형태를
**새 코드에서는 반복하지 않았다**.

### T-2 [권고 · **미해소 이월**] 게이트 23 항목 9 묶음 중 **최소 6건이 마감(Phase 3 종료 전)에 손대지 않은 채 닿았다**

| 게이트 23 | 항목 | 상태 | 확인 근거 |
|---|---|---|---|
| 1b | `xfail_strict` 전역 미설정 | **미해소** | `pyproject.toml [tool.pytest.ini_options]` 에 `testpaths`·`asyncio_mode`·`markers` 만 |
| 8 | A-5 「보호 자리 **전부**」 수기 열거 | **미해소** | `DeletedAccountTokenReachTest` 무변경 (마감은 Phase 4 라 정상 이월) |
| 9 | A-1 제외 집계 6 vs 7 | **미해소** | **R-6** — 실측 재확인 |
| 10 | A-3 `ModuleType` 타이핑이 mypy 무력화 | **미해소 · 1건 증가** | `tests/test_privacy_scanner.py:82` 그대로 + **신설 구조 회귀에 같은 형태 1건 추가** |
| 13 | A-8 X-6 둘째 단언 실패 불가 | **미해소** | `PasswordHashingBackpressureReachTest` 무변경 |
| 14 | A-12 `withFailMessage` 미출력 | **미해소** | `JdbcWorkspaceRepositoryTest:401-403` 의 `withFailMessage(...).singleElement()` 그대로 — **같은 파일을 이 배치가 고쳤는데 그 줄은 안 건드렸다** |
| 15 | A-11 WC-11 상태 코드 미단언 | **미해소** | `WorkspaceContractTest` 의 WC-11 무변경 |
| 16 | A-9 시간 축 KDoc 문면 모순 | **미해소** | `WorkspaceEndpointReachTest` 무변경(`:223` 「배 단위」 ↔ `:624` `MAX_TIMING_RATIO = 1.5`) |
| 17 | A-7 두 번째 커넥션 풀 | **미해소** | K-2 |

**전부 권고 등급이므로 개별로는 착수를 막지 않는다.** 여기 적는 이유는 **마감이 「Phase 3 종료 전」이고
이 게이트가 그 마지막 자리**이기 때문이다 — 리더가 「이 아홉을 Phase 4 로 미룬다」를 **명시적으로 정하지
않으면** 마감이 아무 신호 없이 지나간다.

### T-3 [**수정 필요**] 산출물 §6 「남긴 것」이 게이트 23 조치 목록의 **절반을 열거하지 않는다**

**마감: Phase 3 종료 판정 전.** **수신자: `kotlin-implementer` → 리더.**

`03_kotlin-implementer_workspaces-fixes2.md` §6 이 든 것은 6건이다 — 표 5 잔존 2자리 · 표 18 TRACE ·
표 2b `/auth/me` 이중 조회 · 네 갈래 시간 · `data class` 한정 · `.value` 로거.

게이트 23 cross §6-② 조치 목록 **11건**과 맞대면 다음이 **어느 구획에도 없다**:

| 조치 | 항목 | 마감 | §6 언급 |
|---|---|---|---|
| 6 | 표 7 — D-2 앵커를 이름 문구로 | **Phase 3 종료 전** | **없음** |
| 9 | 표 1b·8·9·10·13·14·15·16·17 · A-13 | **Phase 3 종료 전** | **없음** |
| 10 | 표 11 — 스캔 루트 비대칭 | 리더 판정 | **없음** |
| 11 | 표 20 — 계정 삭제 잔여 조건 | 계정 삭제 기능 커밋 | **없음** |

「하지 않은 것」을 적는 구획이 조치 목록보다 좁으면 **닫힌 것처럼 읽힌다.** 이것은 이 배치가 F-5 로
고친 결함(*"범위 표기 없이 적혀 fail-open 이 실제보다 작아 보이고"*)과 **같은 종류**이고, 방향도 같다 —
**과소 표기**. 산출물 §0 의 「남긴 것(의도)」이 한 줄뿐인 것도 같은 이유다.

**§6 이 든 6건 자체는 정직하다** — 특히 네 갈래 비 악화를 숨기지 않고 표에 적은 것,
`CountingDataSource` 전제가 깨지는 조건을 KDoc 에 적은 것, 「스스로 통과를 선언하지 않는다」(§8)를
명시한 것은 이 하네스의 규약대로다. **문제는 목록의 완전성이지 적힌 것의 정확성이 아니다.**

---

## 7. 게이트 23 조치 항목별 종결 (cross §6-② 11항목)

| # | 항목 | 마감 | **판정** | 근거 |
|---|---|---|---|---|
| 1 | 표 1 — 스캐너 네 형태 복원 + 회귀 `blocks=True` (+1a) | 즉시 | **부분 해소** | 네 형태 **전건 BLOCK 독립 재현**(S-1) · 회귀 N14~N19 `blocks=True` · 구조 회귀 신설. **1a 는 미해소** — xfail 유지(리더 판정 ⑵)이나 **CI 동일 명령에서 실측 미탐 확인**(S-2) → 판정 필요로 재상신 |
| 2 | 표 3a — 구조 축을 서비스 층까지 | Phase 4 | **해소** | K-1 음성 대조 독립 재현. 표 3b(1 오퍼레이션)도 함께 닫힘 |
| 3 | 표 4·4a — `toString` + 종류 탐지기 | Phase 4 첫 문서 DTO/로깅 커밋 | **해소** | S-4 — 종류 탐지·fail-closed·면제 불가 전건 재현. **빈자리 2종은 R-5 로 신규 등재** |
| 4 | 표 2·2a — 401 타이밍 + §1-3 문면 | Phase 3 종료 전 | **부분 해소** | 문면 정정 **해소**(축 ⓐ/ⓑ 로 갈라 적고 privacy-gate 기록 ① 표 삽입) · 세 갈래 비 **닫힘**(구조 회귀 재현 S-3). **네 갈래 비 악화 + 근거 조항 불일치 = R-1(수정 필요)** |
| 5 | 표 6 — X-4 수치에 범위 한정어 | workspaces 종결 전 | **해소** | `workspaces-fixes.md` §3-1 이 두 열(`WorkspaceContractTest` 기준 / 전체 스위트 691·20)로 갈렸다 |
| 6 | 표 7 — D-2 앵커를 이름 문구로 | **Phase 3 종료 전** | **미해소** | C-3 — `ContractSpec.kt:341-345` 무변경 |
| 7 | 표 5 — `ContractSpec` 잔존 fail-open 3자리 | 인라인 헤더·새 `oneOf` 갈래 진입 커밋 | **부분 해소** | `errorDetailUnionTypes` **닫힘**(C-2, 세 방향 재현). 나머지 2자리(`headerComponentsByName`·`collectHeaderRefs`)는 §6 에 등재 이월(마감 미도래) · `requestFieldConstraint():237` 은 A-10 판정대로 해소 불요 |
| 8 | 표 18 — TRACE 로거 3종 | Phase 4 본문 진입 전 | **미해소(이월 정상)** | §6-2 에 등재. 마감 미도래 |
| 9 | 표 1b·8·9·10·13·14·15·16·17·A-13 | **Phase 3 종료 전** | **미해소 (최소 6건 확인)** | T-2 — 등재조차 되지 않았다(T-3) |
| 10 | 표 11 — 스캔 루트 비대칭 | 리더 판정 | **미해소** | 리더 판정 미수령. `SCAN_ROOTS = ["app","backend-kotlin","scripts","frontend/src"]` 무변경 |
| 11 | 표 20 — 계정 삭제 잔여 조건 | 계정 삭제 기능 커밋 | **미해소(이월 정상)** | privacy-gate 소관. 마감 미도래 |

**집계: 해소 3 · 부분 3 · 미해소 5**(그중 마감 미도래 2, **마감 도달·경과 3** — 6·9·1a).

---

## 8. Phase 3 종료 조건 대비 현황 — **사실만** (판정은 리더)

`00_progress.md` Phase 3 표 7행 기준. **이 회차는 원장을 읽기만 했다.**

### 8-1. `아니오` 6행 — 이 배치와 게이트 24 로 닫히는가 / 남는 것의 마감

| # | 종료 조건 | 이 배치의 이동 | **이 게이트로 닫히는가** | **남는 것과 그 성격** |
|---|---|---|---|---|
| **1** | Spring JDBC repository·트랜잭션 경계 | F-4 닫힘(K-1) · 구조 축이 4 오퍼레이션으로 · 트랜잭션 관리자 배선 교정 | **아니오** | **문서·변환 repository 미착수 → Phase 4 종속** · K-2(프로덕션 풀 밖 계측, 권고) → **남는 것이 전부 Phase 4** |
| **2** | Argon2·JWT·원자 생성 | **이 배치 무접촉** | **아니오** | **R-2 교환비 용량 결정 — 사용자 판단 대기.** Phase 4 가 아니라 **사용자 결정**이 마감이다 |
| **3** | `/auth/*`·`/workspaces/*` | 세 갈래 시간 비 `2.356→1.007~1.036` · 구조 회귀 신설 | **R-1 판정에 달렸다** | 미해결 항목이 「401 갈래 시간, **마감 Phase 3 종료 전**」이다. **좁은 조항(`x-auth`)이 정본이면 닫힌다**(그래도 Phase 4 문서 경로 미착수는 남는다). **넓은 조항(`Unauthorized`)이 정본이면 네 갈래 비가 `2.575→2.8~3.0` 으로 악화해 열린다** |
| **4** | 소유권 404 + unique/check/FK 매핑 | **표 1 복원분(`01d78a1`)이 이 게이트에서 처음 리뷰됐다** — 네 형태 전건 재현(S-1) | **아니오** | ⓐ **S-2 — `sendError(SC_FORBIDDEN)` 실측 미탐(판정 필요, 마감 Phase 4 진입 전)** ⓑ R-2·R-3 근거 문면(수정 필요·권고) ⓒ check 제약 → 오류 본문 매핑은 **문서·변환 자원이 없어 Phase 4 종속** |
| **6** | contract test·React 테스트 통과 | 표 5 의 한 자리(union) 닫힘(C-2) | **아니오** | ⓐ **C-3 — D-2 앵커, 마감 Phase 3 종료 전**(Phase 4 아님) ⓑ 나머지 11 엔드포인트 계약 테스트 없음 → **Phase 4** ⓒ 표 5 잔존 2자리 → 인라인 헤더 진입 커밋 ⓓ **CI 원격 캐시 거동 여전히 0관점** |
| **7** | 계약 개선 3자 동일 + 근거 기록 | `1회성:` 산출물 2건 **실재 확인** | **아니오** | ⓐ **R-1 이 새로 여는 것 — 계약 자신의 두 조항이 갈리고 그 사실이 어느 산출물에도 없다**(3자 동일 이전의 1자 불일치) ⓑ ⑥ 이메일 ASCII — **사용자 판단** ⓒ ⑯ 3필드 → Phase 4 ⓓ 타입 교체 → Phase 6 |

**「남는 것이 전부 Phase 4 인가」에 대한 답 — 아니오.** 여섯 행 중 **다섯 행**에
Phase 4 종속이 아닌 잔여가 있다:

- **사용자 판단 대기**: 행 2(R-2 교환비) · 행 7(⑥ 이메일 ASCII → OQ-E2)
- **리더 판정 대기**: 행 3(R-1 — 어느 조항이 정본인가) · 행 4(S-2 판정 필요, 표 11 스캔 루트)
- **마감이 「Phase 3 종료 전」인데 미해소**: 행 6(C-3 D-2 앵커) · 행 4·6 에 걸린 T-2 묶음
- **Phase 4 종속만 남는 행**: **행 1 하나**

### 8-2. 종료 조건 두 행(원문 조각)의 근거 충족

| 조각 | 근거 실재 | 확인 |
|---|---|---|
| 행 6 `ci:kotlin` · `ci:frontend` · `ci:e2e` | **실재** | `.github/workflows/ci.yml` 에 `quality`·`frontend`·`kotlin`·`e2e`(`:621-681`)·`llm-lane` 다섯 잡. run `32225305372`(headSha `2a4523d`) 에서 kotlin·quality·frontend **success** |
| 행 7 `1회성:` 두 산출물 | **실재** | `03_contract-keeper_auth-verification.md`(19,946 B) · `03_contract-keeper_workspaces-verification.md`(27,438 B) |
| 행 5 승격 근거 | **실재** | run `32222249150` headSha `b3f76b25` · 잡 `e2e` **success** ✓. **run 전체 결론은 `cancelled`**(R-4) |

### 8-3. 도달 표기 `EXPECTED_REACH_TOKENS = 65` — **검증됨**

- `uv run pytest tests/test_harness_scope_reach.py` → **37 passed, exit 0**(원장 커밋 메시지와 일치).
- **가드가 고무도장이 아님을 음성 대조로 확인** — 행 6 에서 `ci:e2e` 표기 하나를 빼자 **3건 red**.
- 증분 셋(⑴ 행 5 신설 · ⑵ 행 6 `ci:e2e` · ⑶ 행 7 `1회성:`)은 **셋 다 「없던 실행이 생겼다」 방향**이고
  정체성 집합에 행 5 키가 같은 diff 에 들어갔다 — 승격과 실행 경로 신설이 함께 신고된다. 규약대로다.
- **행 4 `ci:kotlin` 표기의 근거는 실재하나 지목이 틀렸다** — R-2.

---

## 9. 미실행 · 확인 불가

- **codex 리뷰 — 이 회차의 입력이 아니다.** 1차(독립 리뷰)이므로 부재가 정상이고 재요청하지 않았다.
  교차 대조표는 만들지 않았다. 2차(교차 종합) 재호출에서 `..._cross.md` 를 쓴다.
- **privacy-gate 게이트 24 감사 미수령.** 보안 축 최종 판정 권한은 privacy-gate 에 있다 —
  **S-2 는 그 판정을 요청하는 항목**이고 내가 판정하지 않는다.
- **401 시간 축 실측을 재현하지 않았다.** 구현자 표(표본 각 101 · 워밍업 20라운드 · 교차 순서)는
  **1관점뿐**이다. 나는 대신 **구조 축(SQL 문 수)** 을 재현했다 — 그 축이 이 배치가 회귀로 건 것이고,
  잡음이 없다. 시간 수치의 방향은 privacy-gate 기록 ① 과 일치한다. **네 갈래 비 2.8~3.0 도 미재현.**
- **`MAX_TIMING_RATIO = 1.5` 로 걸러지는 실제 공격 시나리오** — 게이트 23 §7-2 에서 이월된 미대조가
  **그대로 유효**하다. 어느 관점도 답하지 않았다.
- **240 동시 배압(R-2)** 미재현 — 인용값이다.
- **전체 Kotlin 스위트 699건을 직접 재실행하지 않았다.** CI `kotlin` 잡 success(run `32225305372`,
  headSha `2a4523d`)로 대신했고, 관련 클래스 5종
  (`SensitiveToStringReachTest`·`AuthDtoLeakTest`·`AuthContractTest`·`WorkspaceContractTest`·
  `JdbcWorkspaceRepositoryTest`·`AuthenticationWorkUniformityTest`)은 직접 실행했다.
  **모듈별 건수(core 359·application 44·infrastructure 115·api 178·worker 3 = 699)는 미검산.**
- **`e2e` 잡의 HEAD 결과** — 리뷰 시점 `in_progress`. `2a4523d` 의 e2e 결론은 미관측.
- **프런트엔드** — `frontend/**` diff 0. 대조하지 않았다.
- **부호 반전 단언의 둘째 이후 인자·역배치**(`assertThat(FORBIDDEN).isNotEqualTo(x)`) —
  ① 이 첫 인자만 소비함은 확인했고(그래서 그 형태는 **잡힌다** = 오탐 쪽), 역배치의 **오탐 부담**은
  재지 않았다.
- **`max_connections` 중간값(4~6)** · **A-11 의 「13 vs 14」 실제 케이스 확정** — 게이트 23 이월, 미실행.
- **행 7 의 「React 3자 동일」** — contract-keeper 가 게이트 22 에서 수기 대조한 결과를 읽었을 뿐
  내가 재현하지 않았다.

---

## 10. 수신자별 조치 제안 (심각도 · 마감)

| 우선 | 항목 | 심각도 | 마감 | 수신자 |
|---|---|---|---|---|
| 1 | **R-1** — 계약 두 조항의 401 균일화 열거 불일치 판정 + 근거 병기 | **수정 필요** | **Phase 3 종료 전**(행 3 개폐가 걸림) | **리더** → `contract-keeper` |
| 2 | **S-2** — `sendError(SC_FORBIDDEN)` 실측 미탐의 처분 | **판정 필요** | **Phase 4 소유 자원 진입 전** | **`privacy-gate`** → 리더 |
| 3 | **R-2** — 원장 행 4 근거 위치 정정(`WorkspaceContractTest` → `WorkspaceEndpointReachTest`) | **수정 필요** | 행 4 판정 전 | **리더**(원장 소유) |
| 4 | **T-3** — 산출물 §6 에 조치 6·9·10·11 등재 | **수정 필요** | Phase 3 종료 판정 전 | `kotlin-implementer` |
| 5 | **R-5** — 값 감싸는 타입의 `toString()` 규율을 탐지형으로 | **수정 필요** | Phase 4 첫 문서 DTO 커밋 | `kotlin-implementer` |
| 6 | **C-3** — D-2 앵커를 이름 문구로 | 권고 | **Phase 3 종료 전**(마감 도달) | `contract-keeper`/`kotlin-implementer` |
| 7 | **T-2** — 항목 9 묶음 9건: 지금 닫을지 Phase 4 로 명시 이월할지 | 권고 | **리더가 재지정** | 리더 → `kotlin-implementer` |
| 8 | **R-6** — 제외 집계를 삼킨 토큰 수로(6 → 7) | 권고 | 리더가 재지정(마감 경과) | 스킬 소유자 |
| 9 | **R-3** — 가드 주석 사유를 「리뷰 미수령」으로 | 권고 | 행 4 판정 시 | 리더 |
| 10 | **R-4** — 인용 run 의 전체 결론 병기 | 권고 | 행 5 문면 갱신 시 | 리더 |
| 11 | **K-2** — `JdbcClient` 이탈을 KDoc 이 아니라 장치로 | 권고 | Phase 4 | `kotlin-implementer` |
| 12 | 표 11(스캔 루트) · 표 20(계정 삭제 잔여) | 이월 | 리더 판정 / 기능 커밋 | 리더 · `privacy-gate` |

**범용 품질 축(성능·유지보수성 일반)은 이 리뷰의 범위가 아니다** — 필요하면 글로벌 `multi-review` 를
별도로 돌릴 것을 권고한다.

---

## 11. 다음 회차(2차 · 교차 종합)를 위한 메모

- 이 파일은 **1차 산출물**이다. 2차에서 `03_phase3-close_codex-reviewer.md` 와 대조해
  `03_phase3-close_cross.md` 를 쓴다. **어간 `03_phase3-close` 는 세 파일이 같아야 한다.**
- 2차에서는 **새 지적을 만들지 않는다.** 종합 중 눈에 띈 것은 `..._cross.md` 의
  「종합 중 발견 — 미교차」 구획에 분리한다.
- **codex 에 특히 물을 값이 있는 자리**(내 단독 관측이라 대조가 필요한 것):
  R-1(계약 두 조항) · S-2(실측 미탐의 성격 — codex 가 게이트 23 에서 이미 「같은 실제 미탐」이라
  했으므로 **내 실측이 그쪽 손을 든 셈**이다) · R-5(래퍼 타입 빈자리) · T-3(§6 완전성).
