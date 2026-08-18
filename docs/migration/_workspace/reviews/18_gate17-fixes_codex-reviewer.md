# 게이트 18 · 1단계 codex 독립 리뷰 — `18_gate17-fixes`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`..._cross.md`)의 몫이다.

**어간**: `18_gate17-fixes` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 00:27:44 KST |
| 종료 시각 | 2026-08-19 00:47:14 KST |
| codex 소요 | **18분 55초** (헬퍼 보고) |
| 대상 범위 | `36b5ed4..318069b` — 커밋 2개 (`107c8a5` · `318069b`), 변경 파일 6개 |
| 모드 | `adversarial` (focus text 필수 — 새 장치의 빈자리를 찾는 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base 36b5ed4`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-msyth3iy-r2mefm` |
| codex session ID | `01a0157c-76c2-7ef1-81d9-076576d4d339` |
| codex 판정 | **`needs-attention`** — "No-ship: four independent false-green/scope-integrity defects remain" |
| codex 실행 셸 명령 | 46건 (완료 44 · 실패 2) |
| focus text 크기 | 9,627 바이트 |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 36b5ed4
codex-review: 대상 판정 = non-empty (merge-base=36b5ed496939, 변경 파일 6개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 사전 거부 단계에서 확인됐다.

### 1.2 실행 명령 전문

```bash
SP=<스크래치패드>
FOCUS="$(cat "$SP/focus_18.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty, 종료 코드 6)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 36b5ed4 --focus "$FOCUS" --dry-run

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 36b5ed4 --focus "$FOCUS"

# 스크립트가 헬퍼로 내보낸 실제 명령:
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base 36b5ed4 '<focus text 전문 — §2>'

# 사후 회수 (재실행 아님):
node <헬퍼> status --all
node <헬퍼> result review-msyth3iy-r2mefm
```

**잘림 없음 확인**: `result` 회수본(7,886B)과 캡처한 stdout(7,763B)을 `diff` 한 결과 차이는
**session ID 꼬리 2줄뿐**이었다. 최종 출력은 온전하다.

### 1.3 회차 고유 경로 (스크래치패드 잔존 파일 함정 회피)

게이트 17 회차의 `codex_out.txt`·`codex_exit.txt`·`codex_err.txt`(8/18 23:53)가 스크래치패드에
**그대로 남아 있다.** 같은 이름을 재사용하면 옛 회차 결과를 이번 결과로 읽을 위험이 있어
이번 회차는 전부 `18` 접미 경로를 썼다.

```
focus_18.txt · dryrun18.txt · codex18_out.txt · codex18_err.txt · codex18_exit.txt
job18_result.txt · status18_before.txt / status18_after.txt · worktree18_before.txt · start18.txt
```

### 1.4 제공한 맥락 목록

리더 지시대로 **전사하지 않고 지목만** 했다. codex 가 직접 열어 읽었다.

- `docs/migration/_workspace/reviews/17_gate16-fixes_cross.md`
  (sha256 `2edbcf5154c8f7bba2d2a671136d2fe5360bdeebbc25374bcf63e2ff6f1054e0`) —
  §3 교차 대조표 60-86행의 **행 ①②③④⑤⑥⑦·⑨·⑬·⑭**, §5 겹침 판정 117-207행, §9.2 조치 목록 342-367행
- 채점 기준의 정본으로 `.claude/skills/kotlin-migration/SKILL.md` "선언한 범위와 실제 도달을
  대조한다" 절을 **지목**하고 "갈리면 그 절이 맞다"고 명시 (focus 의 요약은 보조)
- 커밋 해시: `36b5ed4` · `107c8a5` · `318069b` · 옛 판 `d0a5255`
- 파일·행 지목: `run_gate.sh`(머리 주석 + 본체 68-109), `tests/test_run_gate.py`,
  `tests/test_parity_ci_gate.py`(596·617·621·624·1002-1100),
  `dump_python_snapshots.py`, `tests/test_python_snapshot_guard.py`,
  `compare_parity.py`(정의 1892 · 유일 호출부 2072), `SKILL.md` 규칙 5
- 테스트 실행 경로 둘(`.venv/bin/pytest` · `uv run pytest`)과 "결과가 갈리면 그 사실을 적어라"

### 1.5 민감 데이터

focus text 와 리뷰 대상에 **사용자 문서 본문·실제 암호문·키·개인정보 없음.** 대상은 전부 하네스
셸 스크립트·테스트·생성기·리뷰 산출물이다. 합성 대체가 필요한 값도 없었다.

### 1.6 저장소 무결성 — 착수 전/후 대조 (사실 기록)

- HEAD 는 착수·종료 모두 **`318069b`** — 이번 회차에는 HEAD 이동이 없었다(게이트 17 §1.5 와 다름).
- `git status --porcelain` 착수 전/후 **완전 동일**(기존 untracked 3건뿐, `diff` 무출력).
- 변경 대상 6개 파일 모두 `git diff HEAD` **무출력** — codex 가 저장소를 변조하지 않았다.
- **worktree 1개가 스크래치패드에 존재한다**: `.../scratchpad/wt-gate18` (318069b, detached HEAD).
  이것은 **codex 가 만든 것이 아니다** — 생성 시각 `00:25:29` 로 이번 리뷰 착수(`00:27:44`)보다
  **앞서며**, 착수 전 스냅샷(`worktree18_before.txt`)에 이미 들어 있었다. 병렬 레인(1단계에서
  같이 도는 `migration-reviewer`)이 같은 세션 스크래치패드를 쓰는 것으로 보인다.
  **사실만 적는다 — 영향 판정은 하지 않는다.**
- codex 자신도 최종 출력 마지막 줄에서 같은 취지를 보고했다(§3.1 "Repository integrity" 줄).

---

## 2. 전달한 프롬프트 전문 (focus text)

> 스킬 §3.5 의 네 블록(배경 / 지켜야 하는 조건 / 대상 / 질문) 구조를 따랐다.
> Claude 가 이미 의심하는 지점은 넣지 않았고, 커밋이 **스스로 주장한 실측치**만
> "믿지 말고 재현하라"는 채점 기준으로 제시했다.

````text
## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환 중이고, 제품 동작과
개인정보 보호 정책을 보존해야 한다. 지금 리뷰하는 변경에는 **Kotlin 코드가 한 줄도 없다.**
대상은 그 전환을 지키는 **하네스(게이트 장치) 자체** — 게이트 러너 셸 스크립트, 게이트의
완전성 테스트, 스냅샷 생성기·가드다.

이 하네스에는 되풀이되는 실패 형태가 하나 있다: **장치가 초록불을 내는데 아무것도 재지 않는다.**
검사 대상 0건인데 성공으로 끝나는 스크립트, 빈 선언에서 통과하는 완전성 검사, 면제 목록으로
회귀를 비껴가는 테스트. 그래서 이 리뷰의 질문은 "코드가 괜찮은가"가 아니라
**"이 장치가 자기가 잰다고 적은 것을 실제로 재는가, 아니면 재는 척하는가"** 다.

## 지켜야 하는 조건 (채점 기준)

이 저장소의 규칙(`.claude/skills/kotlin-migration/SKILL.md` 의 "선언한 범위와 실제 도달을
대조한다" 절이 정본이다 — 직접 열어 읽어라. 아래는 채점에 쓰라고 요약한 것이고, 갈리면 그 절이 맞다):

1. **범위는 근거를 넘지 않는다.** "전역"·"모든"·"항상"을 실행으로 대조하지 않고 쓰지 않는다.
2. **장치를 분류한다** — 탐지형(어긋남을 드러낸다) / 은폐형(무시 패턴·면제 조항·억제) /
   강제·표현형 / 범위 선언형. **은폐형은 넓히지 않고 탐지형으로 갈아탄다.**
3. **범위 선언형 장치는 빈 선언에서 통과하면 안 된다.** 선언 집합이 비었을 때 초록불이면
   그 장치는 아무것도 재지 않는다.
4. **자기 도달을 정직하게 적는다** — 장치의 머리 주석이 장치가 하는 일보다 크게 적혀 있으면
   그 자체가 결함이다. 못 잡는 것은 "못 잡는다"로 이름 붙여 남긴다.
5. **대리 지표 금지** — 종료 코드 0 을 "검토했다"로, 테스트 통과를 "그 경로가 돌았다"로,
   지적 건수를 변경 여부로 바꿔 읽지 않는다.

위반 시 결과: 이 장치들은 Kotlin 재개발이 요구사항·정책(마스킹 선행, 큐레이션 데이터 영구 손실
방지 등)을 지켰는지 판정하는 **근거**로 쓰인다. 장치가 무력하면 사건이 나도 아무도 모른 채
Phase 가 통과된다.

## 대상

저장소 루트: `/Users/harris/Development/private/easy-doc` (브랜치 `feat/kotlin-migration-harness`)
리뷰 범위: `36b5ed4..318069b` — 커밋 2개.

- `107c8a5` — 게이트 러너 zero-work 탐지
  - `.claude/skills/kotlin-migration/scripts/run_gate.sh` (머리 주석 계약 + 본체 68-109행)
  - `tests/test_run_gate.py` (파일 docstring 에 계약 문장↔테스트 대응표, 23건)
  - `.claude/skills/kotlin-migration/SKILL.md` 규칙 5 (1줄)
- `318069b` — 완전성 테스트를 정확 일치·탐지형·결속으로
  - `tests/test_parity_ci_gate.py` — `_MAINLINE_HELPERS`(596행~) · `EXPECTED_MAINLINE_HELPERS=10`(617행)
    · `_MAINLINE_ROOTS`(621행) · `_MAINLINE_PHRASES`(624행) · 완전성 테스트 본체(1002-1100행)
  - `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py` (중복 이름 실패, 메시지 갈래)
  - `tests/test_python_snapshot_guard.py`

**이 배치가 닫는다고 주장하는 것** — 아래 파일을 직접 열어 해당 부분만 읽어라(전문 전사는 하지 마라):
`docs/migration/_workspace/reviews/17_gate16-fixes_cross.md`
(sha256 `2edbcf5154c8f7bba2d2a671136d2fe5360bdeebbc25374bcf63e2ff6f1054e0`)
- §3 교차 대조표 60-86행 중 **행 ①②③④⑤⑥⑦·⑨·⑬·⑭**
- §5 겹침 판정 117-207행
- §9.2 조치 목록 342-367행

옛 판 회수: `git show d0a5255:.claude/skills/kotlin-migration/scripts/run_gate.sh`
비교기: `.claude/skills/python-kotlin-parity/scripts/compare_parity.py`
(`reference_problems` 정의 1892행 · 유일 호출부 2072행)
테스트 실행: `.venv/bin/pytest` 또는 `uv run pytest` (둘 다 있다. 결과가 갈리면 그 사실을 적어라)

**저장소를 변조하지 마라.** 실험은 `git show` 로 꺼낸 사본, 임시 디렉터리, 또는 네 프로세스
안의 메모리 변이로 하고, 끝나면 작업 트리를 착수 시점 그대로 두어라(커밋·stash·파일 수정 금지).

## 질문 — 이 세 축만 본다 (다른 축은 이번 회차 범위 밖이다)

### 축 ① 음성 대조 재현 — 주장이 사실인지 네가 직접 확인하라

커밋 메시지가 다음을 실측했다고 주장한다. **믿지 말고 재현하라.** 재현되면 재현됐다고,
안 되면 무엇이 달랐는지 적어라.

- 새 `tests/test_run_gate.py` 를 **옛 판 러너**(`d0a5255`)에 돌리면 **정확히 3건** 실패한다 —
  주석 전용 · 백슬래시-개행 · 미설정 변수. 새 판에서는 23 passed.
  (테스트 파일에 `RUN_GATE_PATH` 손잡이가 있다. 그 손잡이를 쓰는 것 자체가 결과를 바꾸는지도 봐라.)
- 옛 판 러너에 그 세 입력을 직접 주면 셋 다 rc 0 이었다.
- `tests/test_parity_ci_gate.py` 완전성 테스트의 세 변이:
  ⒜ `_MAINLINE_HELPERS` 를 `{}` 로 → 실패해야 한다
  ⒝ 표에서 행 1개 제거 + `EXPECTED_MAINLINE_HELPERS` 를 9 로 (옛 면제 경로 재현) → 실패해야 한다
  ⒞ `compare_parity.py:2072` 의 `reference_problems` 호출선 제거 → **대응 본류 테스트가 빨개져야** 한다

### 축 ② 새 장치의 자기 빈자리 — 네가 직접 변이시켜 찾아라

이 하네스에서 **세 회차 연속** 같은 일이 났다: 새 완전성 장치를 세우면서 그 장치 자신에게는
같은 기준을 적용하지 않아, 다음 게이트에서 "빈 선언/면제로 통과"가 발견됐다. 이번 배치가
세운 새 단언들이 그 자리에 또 있는지 **네가 변이를 만들어** 확인하라.

새 단언 목록: `EXPECTED_MAINLINE_HELPERS = 10` 정확 일치 · AST 호출부 성질 단언 ·
`_MAINLINE_ROOTS = ("main", "compare_file")` · `_MAINLINE_PHRASES` 문구 결속 ·
러너의 DEBUG trap zero-work 마커.

구체적으로 답할 것:

1. **`_MAINLINE_ROOTS` 가 비면**(`()`) 어떤 단언이 **공허하게 참**이 되는가? 그 상태에서
   완전성 테스트는 초록인가? 다른 상수(`EXPECTED_MAINLINE_HELPERS`·`_MAINLINE_PHRASES`)에
   대해서도 같은 질문을 던져라 — **빈 선언에서 통과하는 것이 하나라도 있는가.**
2. **DEBUG trap 마커 기제**가 다음 입력에서 어떻게 되는가 — 서브셸 `( ... )`, 명령 치환
   `$( ... )` / 백틱, `eval`, **함수 정의만 있는 입력**(`f() { :; }`), `:` 단독,
   `if false; then ...; fi`, `for x in ; do ...; done`, here-doc 만, `{ ; }`,
   trap/set 자체만 있는 입력. **마커를 위조하거나 선점할 수 있는가**(`RUN_GATE_MARKER` 를
   명령 안에서 덮어쓰기, 마커 파일에 미리 쓰기, `trap - DEBUG` 를 명령 첫 줄에서 해제하기,
   preamble 자체를 무력화하기)?
3. **러너가 rc 0 을 내는 zero-work 입력**이 머리 주석이 인정한 잔여("값이 빈 문자열로 *설정된*
   변수 하나") 말고 **더 있는가.** 있으면 입력과 실측 rc 를 적어라.
4. 각 새 단언에 대해: **그 단언을 지우면 정확히 어떤 변이가 초록으로 통과하게 되는가.**
   지워도 아무 변이가 통과하지 않는(= 아무것도 막지 않는) 단언이 있으면 지목하라.
5. AST 호출부 대조가 **놓치는 호출 형태**가 있는가 — 간접 호출(`getattr`·딕셔너리 디스패치·
   데코레이터·별칭 대입·`functools.partial`), 다른 모듈 경유, 동적 임포트. 놓친다면 그 경로로
   본류 helper 를 추가하고도 표를 안 고칠 수 있는가?

### 축 ③ 표기 정직성 — 적힌 것과 하는 것이 같은가

1. `run_gate.sh` 머리 주석의 **계약 문장 12줄**이 각각 대응 테스트를 갖는가?
   `tests/test_run_gate.py` docstring 의 대응표에 적힌 테스트 이름이 **실재하고 그 문장을 실제로
   재는지** 대조하라. 표에만 있고 없는 테스트, 있지만 다른 것을 재는 테스트를 찾아라.
2. 실측에서 깨져 지웠다는 주장("빈 확장까지 잡는다")의 **흔적이 남아 있는가.** 머리 주석·
   오류 메시지·테스트 이름·docstring 어디든 아직 장치가 하는 일보다 크게 적힌 문장이 있는가.
   (본체 104행 오류 메시지가 무엇을 열거하는지 특히 보라.)
3. 자식 셸을 `-o nounset` 으로 돌리는 것은 **계약 변경**이다(옛 판에서 통과하던 명령이 이제
   비-0). 그 변경이 머리 주석·테스트에 **명시**돼 있는가? 이 변경으로 **정상 게이트 명령이
   깨지는 경우**가 있는가?
4. `SKILL.md` 규칙 5 가 값을 옮겨 적지 않고 **지목만** 하는가? (이 커밋이 이중 기재를 지웠다고
   주장한다.) 지목이 가리키는 곳에 실제로 그 값이 있는가 — 지목만 남기고 정본이 없으면
   그것도 결함이다.

## 출력 형식

지적마다 **파일:행**, **재현 절차**(네가 실제로 돌린 명령), **관측된 결과**, **위반한 조건 번호**,
심각도를 적어라. 축 ① 은 재현 여부를 항목별로 명시하라(재현됨 / 재현 실패 / 시도 못 함).
확인하지 못한 것은 확인 못 했다고 적어라 — 추측으로 메우지 마라. 지적할 것이 없는 축이 있으면
"지적 없음"이라고 명시하라.
````

---

## 3. codex 원문 출력 (무편집)

> **이 구획에는 Claude 가 쓴 문장이 한 줄도 없다.** 오탐 표시·심각도 조정·중복 병합·번역
> 어느 것도 하지 않았다. 아래는 `codex18_out.txt` 전문이다.

### 3.1 최종 출력 전문

```text
# Codex Adversarial Review

Target: branch diff against 36b5ed4
Verdict: needs-attention

No-ship: four independent false-green/scope-integrity defects remain. Empty phrase scope passes, external helpers bypass completeness, DEBUG misclassifies empty command substitutions, and RUN_GATE_PATH can redirect almost the whole suite while the default-target test stays green.

Findings:
- [high] Empty `_MAINLINE_PHRASES` makes both controls vacuously pass (tests/test_parity_ci_gate.py:624-632)
  Reproduction: an in-memory probe set `_MAINLINE_PHRASES=()` and invoked the completeness test plus both control-test bodies. Observed: completeness PASS; both controls also PASS even when their supplied output contained `원장이 낡았다`. The only consumers are the loops at lines 720 and 740, so an empty declaration measures zero phrases. Impact: helper failures can leak into normal/control executions without invalidating their claimed attribution. Violates conditions 3 and 5.
  Recommendation: Assert that `_MAINLINE_PHRASES` is non-empty and exactly equals an independently derived expected set. Add a mutation test that replaces it with `()` and requires failure.
- [high] External or dynamically imported helpers bypass the completeness gate (tests/test_parity_ci_gate.py:979-1035)
  Reproduction: an in-memory probe attached an external-module `external_problems`, routed `main()` through `importlib.import_module(...).external_problems()`, then ran the unchanged completeness test. Observed: the helper executed and returned a failure, but completeness PASS because discovery requires `member.__module__ == comparer.__name__` and call-site analysis accepts only direct `ast.Name` calls. Same-module alias/getattr/dict/partial calls generally become zero-call failures, but other-module calls, dynamic imports, nested helpers, and decorators returning non-function callables remain invisible. Impact: a modularization can add a mainline policy helper without a table row or regression test. Violates conditions 1 and 4.
  Recommendation: Either reject unresolved/external calls reachable from `main` and `compare_file`, or analyze the resolvable import/call graph. Add mutations for external modules, dynamic imports, nested functions, and callable-returning decorators.
- [high] DEBUG marker treats empty command substitutions as executed work (.claude/skills/kotlin-migration/scripts/run_gate.sh:90-104)
  Reproduction: the exact preamble and child flags were run in memory with `$()` and empty backticks. Observed for both: child rc 0, DEBUG marker present, therefore runner rc 0. No command exists inside either substitution; DEBUG fires for the outer syntactic simple command before expansion. `eval ''`, `:`, `if false`, `trap - DEBUG`, and `set +u` likewise return green while checking no gate target. Yet the header limits the admitted residue to one empty-valued variable, and line 104 claims `빈 확장 등` are detected. The contract table has no command-substitution test despite line 28 naming that form. Violates conditions 4 and 5.
  Recommendation: Either detect empty command substitutions or narrow the contract to “at least one DEBUG event,” explicitly listing semantic no-ops and empty substitutions as limitations. Add `$()`, backtick, eval, colon, conditional, and trap/set cases to `test_run_gate.py`.
- [high] Default-target test hides an active `RUN_GATE_PATH` override (tests/test_run_gate.py:94-116)
  Reproduction: set `RUN_GATE_PATH=/redirected/not-the-repo-runner.sh`, invoked `test_default_target_is_the_tracked_repo_file` with its normal MonkeyPatch, then restored it. Observed: the test PASSed because it deletes the override itself; afterward `_runner()` again returned the redirected path used by subsequent tests. In the old-runner probe this produced the claimed 20 pass/3 fail count while this one test alone inspected the current repository runner. Impact: a stale or injected CI/local environment can test a different script while the suite still claims the tracked default was tested. Violates conditions 4 and 5.
  Recommendation: Make default/CI execution fail if `RUN_GATE_PATH` is present. Isolate old-runner negative controls behind an explicit fixture or option so every test in each run shares one declared target.

Next steps:
- Axis 1 — old-runner suite: reproduced numerically using the unchanged 23 test bodies with only subprocess path injection: 20 passed, exactly 3 failed (`comment_only`, `backslash_newline_only`, `unset_variable`). Caveat: the default-target test deliberately switched back to HEAD, as finding 4 explains.
- Axis 1 — direct old-runner inputs: reproduced from `git show d0a5255:...` in process memory. Comment-only, backslash-newline, and unset `$GATE_CMD` each returned rc 0.
- Axis 1 — current `23 passed`: attempted but not confirmed. `.venv/bin/pytest` failed before collection because no writable temporary directory; `uv run pytest` separately failed with uv-cache access denied. The two prescribed runners therefore differed, but neither reached tests.
- Axis 1 — completeness mutations: `_MAINLINE_HELPERS={}` failed; removing `reference_problems` plus setting expected count to 9 failed; suppressing the line-2072 call effect removed `원장이 낡았다`, so the mapped mainline assertion would fail. All three claims reproduced in memory.
- Axis 2 — empty declarations: `_MAINLINE_ROOTS=()` failed, although its `not in_roots` check becomes vacuous; `EXPECTED_MAINLINE_HELPERS=0` failed; `_MAINLINE_PHRASES=()` passed and is blocking finding 1.
- Axis 2 — marker matrix: comment-only and backslash-newline mapped to rc 2; subshell rc 0; function-definition-only and empty `for` rc 2; `$()`, empty backticks, `eval ''`, `:`, `if false`, `trap - DEBUG`, `set +u`, and the documented empty variable mapped to rc 0; `{ ; }` was a syntax error rc 2. Here-doc verification was inconclusive because Bash itself could not create its temporary file in this sandbox.
- Axis 2 — assertion removal probes: removing exact-count allowed `EXPECTED_MAINLINE_HELPERS=9` drift; removing the unlisted-helper property allowed reference-row removal plus expected 9; removing the one-call/root assertion allowed duplicate call sites and empty roots; removing phrase binding allowed a table phrase to drift to another comparer literal. The marker assertion's removal is covered by the three old-runner false greens.
- Axis 2 — marker tampering: overwriting `RUN_GATE_MARKER` in the first user command does not prevent the initial DEBUG write; truncating it afterward causes a false rc 2. `BASH_ENV` was observed executing before the preamble with the marker path visible, so it could prefill/neutralize the marker on a writable filesystem, but that complete write-based bypass could not be executed in this read-only sandbox.
- Axis 3 — all test names listed in the docstring exist. The missing substantive correspondence is command substitution: the header names it, but the table maps only a subshell test, and empty substitutions are false green. Line 104's `빈 확장 등` is also broader than measured behavior.
- Axis 3 — nounset is explicitly documented and tested. It is a real compatibility change: `if [ -n "$OPTIONAL_GATE_FLAG" ]; then ...; fi` returned old rc 0 and new-child rc 127 when unset. No current repository consumer of `run_gate.sh` was found, so no present gate break was established.
- Axis 3 — SKILL.md rule 5 now points to the runner header without copying its values, and that canonical header exists. No issue found there.
- Repository integrity: final hashes of all six changed files equal their HEAD blobs. Git status contains exactly the same three pre-existing untracked entries seen at review start; no repository file was modified.
```

### 3.2 codex 중간 진행 메시지 6건 (무편집 — **헬퍼가 잘라서 기록했다**)

헬퍼 stderr 에 `[codex] Assistant message captured:` 로 남은 것이며, **헬퍼가 말줄임(`...`)으로
잘랐다.** 전문은 회수 불가(§5.2). 잘린 상태 그대로 옮긴다.

```text
[codex] Assistant message captured: {"verdict":"approve","summary":"리뷰를 시작합니다. 정본으로 지정된 `kotlin-migration` 스킬을 먼저 전부 읽고, easy-doc...

[codex] Assistant message captured: {"verdict":"approve","summary":"정본 파일 출력이 메모리 검색 결과와 합쳐져 잘렸습니다. 정본은 부분독해로 처리하지 않고, 줄 범위를 나눠 E...

[codex] Assistant message captured: {"verdict":"approve","summary":"정본의 핵심 규칙은 확인했습니다: 범위 선언형은 빈 선언에서 실패해야 하고, 새 장치를 제거했을 때 장치 밖에...

[codex] Assistant message captured: {"verdict":"needs-attention","summary":"실행 환경 차이가 확인됐습니다. 이 세션은 읽기 전용이라 사용할 수 있는 임시 디렉터리가 없고,...

[codex] Assistant message captured: {"verdict":"needs-attention","summary":"핵심 음성 대조는 메모리 변이로 재현됐습니다. 특히 `_MAINLINE_PHRASES=()`가 ...

[codex] Assistant message captured: {"verdict":"needs-attention","summary":"No-ship: four independent false-green/scope-integrity...
```

### 3.3 codex 가 실행한 셸 명령 (집계 — **헬퍼가 각 줄을 잘라서 기록했다**)

- 총 **46건** 실행 (`Running command`), 완료 44 · 실패 2
- 실패 2건은 codex 가 최종 출력에서 스스로 보고한 것과 일치한다:
  `.venv/bin/pytest ... tests/test_run_gate.py`(exit 1) · `uv run pytest ... tests/test_run_gate.py`(exit 2)
- 명령 유형: `git show d0a5255:...`, `git diff --unified=30 36b5ed4..318069b`, `nl -ba`+`sed -n` 범위 읽기,
  `rg -n` 심볼 검색, `.venv/bin/python -c '<importlib 메모리 변이 프로브>'` 다수,
  `git status --short`, 6개 파일 해시 대조
- 각 줄이 헬퍼에 의해 약 90자에서 잘려 있어 **명령 전문은 회수 불가**(§5.2)

---

## 4. 정리 (가공) — 색인

> 여기부터는 **Claude 색인**이다. 원문(§3)과 구획이 다르다.
> **옳고 그름은 판정하지 않는다** — 심각도는 codex 가 부여한 값을 그대로 옮겼고,
> 재분류·병합·기각을 하지 않았다.

### 4.1 지적 4건 색인 (codex 부여 심각도 그대로)

| # | codex 심각도 | 지적 | 근거 위치 (codex 표기 그대로) | codex 가 지목한 위반 조건 |
|---|---|---|---|---|
| X18-1 | **high** | `_MAINLINE_PHRASES` 가 비면 완전성 테스트와 **양쪽 control 테스트가 공허하게 통과** — 빈 선언에서 초록 | `tests/test_parity_ci_gate.py:624-632` (소비처 720·740행) | 조건 3·5 |
| X18-2 | **high** | **외부 모듈·동적 임포트 helper 가 완전성 게이트를 우회** — 발견이 `member.__module__ == comparer.__name__` 로 제한되고 호출부 분석이 직접 `ast.Name` 만 받는다 | `tests/test_parity_ci_gate.py:979-1035` | 조건 1·4 |
| X18-3 | **high** | DEBUG 마커가 **빈 명령 치환을 실행된 작업으로 오분류** — `$()`·빈 백틱·`eval ''`·`:`·`if false`·`trap - DEBUG`·`set +u` 가 rc 0. 머리 주석은 잔여를 "빈 값 변수 하나"로 한정하고 104행은 `빈 확장 등`을 잡는다고 적었다 | `.claude/skills/kotlin-migration/scripts/run_gate.sh:90-104` (계약 문장 28행) | 조건 4·5 |
| X18-4 | **high** | `RUN_GATE_PATH` 가 활성인데 **기본 대상 테스트가 그것을 가린다** — 테스트가 스스로 override 를 지워 통과하고, 이후 테스트는 다시 우회 경로를 쓴다 | `tests/test_run_gate.py:94-116` | 조건 4·5 |

**codex 총평 원문**: `Verdict: needs-attention` /
"No-ship: four independent false-green/scope-integrity defects remain."

### 4.2 축 ① 재현 결과 — codex 자기 보고 (원문 §3.1 "Next steps" 발췌, 판정 없음)

| 축 ① 항목 | codex 보고 |
|---|---|
| 옛 판(`d0a5255`)에 새 테스트 23건 | **재현됨(수치 일치)** — "20 passed, exactly 3 failed (`comment_only`, `backslash_newline_only`, `unset_variable`)". 단 codex 는 단서를 달았다 — 기본 대상 테스트가 의도적으로 HEAD 로 되돌아간다(X18-4) |
| 옛 판 세 입력 직접 실행 rc 0 | **재현됨** — 주석 전용·백슬래시-개행·미설정 `$GATE_CMD` 각각 rc 0 (`git show` 로 꺼내 메모리에서) |
| 새 판 `23 passed` | **재현 실패(확인 못 함)** — `.venv/bin/pytest` 는 쓰기 가능 임시 디렉터리가 없어 수집 전 실패, `uv run pytest` 는 uv 캐시 접근 거부. **두 경로가 갈렸고 어느 쪽도 테스트에 도달하지 못했다** |
| 완전성 변이 ⒜ `{}` | **재현됨** — failed |
| 완전성 변이 ⒝ 행 제거 + 상수 9 | **재현됨** — failed |
| 완전성 변이 ⒞ `reference_problems` 호출선 제거 | **재현됨** — `원장이 낡았다` 가 사라져 대응 본류 단언이 실패한다 |

### 4.3 축 ② — codex 가 직접 만든 변이 결과 (원문 발췌)

- **빈 선언 통과 여부**: `_MAINLINE_ROOTS=()` → **failed**(다만 `not in_roots` 검사는 공허해진다고 부기) ·
  `EXPECTED_MAINLINE_HELPERS=0` → **failed** · **`_MAINLINE_PHRASES=()` → passed** (X18-1 의 근거)
- **마커 행렬 (codex 실측 rc)**: 주석 전용 rc 2 · 백슬래시-개행 rc 2 · 서브셸 rc 0 ·
  함수 정의 전용 rc 2 · 빈 `for` rc 2 · **`$()` / 빈 백틱 / `eval ''` / `:` / `if false` /
  `trap - DEBUG` / `set +u` / 문서화된 빈 변수 → rc 0** · `{ ; }` 는 문법 오류 rc 2 ·
  here-doc 은 **미확정**(샌드박스에서 bash 가 임시 파일을 못 만듦)
- **단언 제거 프로브**: 정확 일치 제거 → 상수 9 드리프트 허용 · 미등재 helper 성질 제거 →
  reference 행 제거 + 상수 9 허용 · 한 호출부/root 단언 제거 → 중복 호출부와 빈 roots 허용 ·
  문구 결속 제거 → 표 문구가 다른 비교기 리터럴로 드리프트 허용
- **마커 위조**: 첫 사용자 명령에서 `RUN_GATE_MARKER` 덮어써도 최초 DEBUG 기록은 못 막는다 ·
  이후 truncate 하면 **거짓 rc 2** · **`BASH_ENV` 가 preamble 보다 먼저 실행되며 마커 경로가
  보였다** — 쓰기 가능 파일시스템이면 선점·무력화 가능하나 읽기 전용 샌드박스라 완주 못 함

### 4.4 축 ③ — codex 결과 (원문 발췌)

- docstring 대응표의 **테스트 이름은 전부 실재**한다
- 빠진 실질 대응은 **명령 치환** — 머리 주석 28행이 그 형태를 거명하는데 표는 서브셸 테스트만
  매핑하고, 빈 치환은 거짓 초록이다. 104행 `빈 확장 등` 도 실측 동작보다 넓다
- `nounset` 은 **명시·테스트돼 있다.** 실제 호환성 변경임은 확인 —
  `if [ -n "$OPTIONAL_GATE_FLAG" ]; then ...; fi` 가 옛 rc 0 / 새 자식 rc 127.
  다만 **저장소에 `run_gate.sh` 소비자가 없어** 현재 깨지는 게이트는 입증되지 않았다
- **`SKILL.md` 규칙 5 — 지적 없음.** 값을 옮기지 않고 지목만 하며 지목 대상 정본이 실재한다

### 4.5 전제 확인 필요 — `migration-reviewer` 가 코드로 대조할 것

> **이 목록은 새 지적이 아니다.** codex 진술이 기댄 전제 중 이 회차에서 실행으로 확정되지
> 않은 것만 모았다. 옳고 그름은 판정하지 않았다.

1. **codex 샌드박스가 읽기 전용이었다.** 쓰기 가능 임시 디렉터리 부재로 ⑴ 새 판 `23 passed`
   미확인, ⑵ here-doc 마커 거동 미확정, ⑶ `BASH_ENV` 기반 마커 선점 우회 **미완주**.
   세 항목은 codex 가 스스로 "확인 못 함"으로 적었다 — 환경 제약이지 부재 증명이 아니다.
2. **X18-4 와 축 ① 첫 항목의 관계.** codex 는 "20 passed / 3 failed" 를 재현했다면서 동시에
   그 수치가 X18-4(기본 대상 테스트가 스스로 override 를 지움) 때문에 생긴 것이라고 적었다.
   `RUN_GATE_PATH` 주입 방식이 커밋 메시지의 음성 대조 방식과 같은지 대조가 필요하다.
3. **`_MAINLINE_ROOTS=()` 가 failed 인데 "공허해진다"는 부기.** codex 는 결과(failed)와 성질
   (공허) 을 함께 적었다 — 어느 단언이 그 실패를 냈고 어느 검사가 공허해지는지는 분리 확인 대상.
4. **"저장소에 `run_gate.sh` 소비자가 없다"** (축 ③ nounset). 이 진술이 `.github/workflows/` ·
   스킬 문서 · 에이전트 정의 전체를 훑은 결과인지는 최종 출력에 근거가 없다.
5. **X18-2 의 "same-module alias/getattr/dict/partial calls generally become zero-call failures"** —
   codex 는 이들을 "대체로 0-호출 실패가 된다"고 적어 우회가 아니라고 분류했다. 그 분류가
   실제 코드 경로와 맞는지는 대조 대상.
6. **X18-1 이 지목한 소비처 720·740행**이 `_MAINLINE_PHRASES` 의 **유일한** 소비처인지.

---

## 5. 미실행·실패 항목

### 5.1 codex 호출 실패·재시도

**없다.** 1회 호출로 종료 코드 `0`, 판정 `needs-attention`, 출력 7,763바이트를 얻었다.
재시도하지 않았고 **⚠ codex 리뷰 누락에 해당하지 않는다.**

### 5.2 전문을 회수하지 못한 것

- **중간 진행 메시지 6건** — 헬퍼가 말줄임으로 잘라 stderr 에 기록했다. `result <job-id>` 는
  최종 출력만 돌려주므로 전문 회수 경로가 없다. 잘린 상태 그대로 §3.2 에 실었다.
- **실행 셸 명령 46건의 전문** — 같은 이유로 각 줄이 약 90자에서 잘렸다. §3.3 은 집계와 유형만이다.
- 추측으로 이어붙이지 않았다.

### 5.3 codex 가 답하지 않았거나 확인하지 못한 것

- 새 판 `23 passed` 확인 (샌드박스 제약)
- here-doc 입력의 마커 거동 (bash 임시 파일 생성 불가)
- `BASH_ENV` 경유 마커 선점 우회의 완주 (읽기 전용 파일시스템)
- focus 축 ②-5 의 하위 항목 중 **데코레이터·`functools.partial`** 은 최종 출력에서 X18-2
  본문에 한 줄로 묶여 언급됐을 뿐 개별 실측 결과는 제시되지 않았다
- `dump_python_snapshots.py` 와 `tests/test_python_snapshot_guard.py`(게이트 17 표 ⑥·⑦ 대응분)에
  대한 **지적이 최종 출력에 없다.** codex 가 해당 파일을 읽은 명령은 stderr 에 있으나
  (`nl -ba .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py`),
  "지적 없음"이라는 명시적 진술도 없다 — **답이 오지 않은 것으로 기록한다.**

### 5.4 이 회차에서 하지 않은 것 (경계 준수)

- codex 지적의 옳고 그름 판정, 심각도 재부여, 중복 병합, 표현 다듬기, "오탐 같다" 주석 삽입 —
  **하지 않았다.**
- Claude 자신의 리뷰를 수행하지 않았다. `migration-reviewer` 산출물을 만들지 않았다.
- 코드를 고치지 않았다. 커밋하지 않았다. `00_progress.md` 를 건드리지 않았다.
- 저장소를 변조하지 않았다 — 착수 전/후 `git status` 동일, 6개 파일 `git diff HEAD` 무출력.
  `cp` 복원 없음. 스크래치패드 `wt-gate18` worktree 는 **착수 전부터 있던 것**으로 이 회차가
  만들지 않았다(§1.6).

---

## 6. `migration-reviewer` 에게

- 이 파일이 **codex 원본**이다. §3 은 무편집, §4·§5 는 Claude 색인 — 구획이 섞이지 않았다.
- 2차 교차 종합은 같은 어간 **`18_gate17-fixes`** 로 `..._cross.md` 를 만든다.
- 상충이 생기면 **어느 쪽도 지우지 말고** 양쪽 근거를 병기할 것.
- §4.5 의 6건은 **기존 지적의 근거 검증**이지 새 지적이 아니다.
- §5.3 마지막 항목(`dump_python_snapshots.py`·`test_python_snapshot_guard.py` 무응답)은
  "지적 없음"이 **아니라** "답이 오지 않음"이다 — 이 둘을 같게 읽으면 게이트 17 표 ⑥·⑦ 이
  교차 검증 없이 닫힌 것으로 오독된다.
