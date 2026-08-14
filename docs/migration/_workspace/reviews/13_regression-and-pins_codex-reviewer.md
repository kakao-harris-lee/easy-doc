# 게이트 13 · 1단계 codex 독립 리뷰 — `13_regression-and-pins`

> **어간 출처**: 리더가 1단계 호출에서 지정한 `13_regression-and-pins` 를 그대로 썼다. 임의 슬러그를 만들지 않았다.
>
> **회차**: 1회차 (게이트 13). 이전 회차 없음 — 게이트 12(`12_export-luhn-suppression_*`)는 다른 어간이며 다른 대상 범위다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-15 |
| 리뷰 대상 | 커밋 범위 `516c0e9..14b9d92` (branch diff, 변경 파일 17개) |
| 모드 | `adversarial-review` |
| scope / base | `auto`(미지정) / `--base 516c0e9` — base 를 주었으므로 scope 는 무시된다 |
| 실행 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 516c0e9 "$(cat <focus 파일>)"` |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 리뷰 근거로 유효하다 |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택, 1.0.6) |
| 실행 명령 | `node <헬퍼> adversarial-review --base 516c0e9 '<focus 전문>'` |
| thread id | `01a001d6-f039-76d2-b28b-ed579fa796bf` |
| turn id | `01a001d6-f18a-7801-b866-7640fbdef57c` |
| 판정 | `Verdict: needs-attention` |

### 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 516c0e9
codex-review: 대상 판정 = non-empty (merge-base=516c0e93b5d6, 변경 파일 17개 (branch 모드는 커밋된 변경만 센다))
```

### 제공한 맥락 (focus 안에 주입한 것)

- 판정 기준이 "Python 출력 일치"가 아니라 "요구사항·정책 충족"임 (2026-08-12 재개발 전환)
- 마스킹 선행 불변식과 마스킹 범주 2종(주민등록번호·카드번호), 전화·이메일·계좌는 의도적 비마스킹
- 리뷰 대상 파일 경로 전체 (Kotlin 3, 하네스 스크립트 3, 하한 파일 1, 테스트 2)
- 대응 Python 참고 구현 경로 (`app/privacy/masking.py`, `app/easyread/export.py`) — 정답이 아니라 참고임을 명시
- 입력 상한 4,000자
- 혼입 커밋(`2cf862a`)이 커밋 메시지가 설명하지 않는 변경을 담고 있다는 사실

### 민감 데이터 취급

focus 및 리뷰 대상에 실제 사용자 문서 본문·암호문·키·개인정보를 싣지 않았다. 카드번호 예시는 테스트 fixture 의 합성 값(`4111-1111-1111-1111` — 공개 테스트 번호)뿐이다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
Kotlin 재개발(Python/FastAPI → Kotlin/Spring Boot)의 Phase 2 종료 판정 직전 최종 회차다. 판정 기준은 "Python과 같은 값이 나오는가"가 아니라 "요구사항·정책을 충족하는가"다. 아래 세 축만 깊게 본다.

[축 1] 카드번호 마스킹 회귀 수정의 완전성 — backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt 의 신설 acceptedMatches() 와 그것을 쓰는 candidateSpans().

불변식: 이 저장소에서 사용자 문서 텍스트는 마스킹 파이프라인을 통과한 뒤에만 LLM provider로 전달된다. 따라서 마스킹 재현율이 떨어지면 곧바로 카드번호 평문이 외부로 나간다. 그리고 Luhn 검사는 정밀도를 올리는 장치이지 재현율을 깎는 장치가 아니다 — Luhn 도입 전에 가려지던 유효 카드가 도입 후 가려지지 않으면 그것이 회귀다.

다음을 찾아라.
(a) "거부하면 match.range.first + 1 부터 재탐색한다"가 회수하지 못하는 겹침 배치. 3중 이상 겹침, 거부된 매치 하나 안에 유효 카드가 둘 들어 있는 경우, 유효 카드의 시작이 거부된 매치의 시작보다 앞서는 경우, 채택 직후 from 을 match.range.last + 1 로 미는 쪽에서 겹친 다른 유효 카드를 잃는 경우.
(b) 이 재탐색이 패턴의 lookbehind/lookahead(예: (?<!\d), (?!\d))와 상호작용해 유효한 시작 위치를 영구히 탈락시키는 경계. find(text, from) 이 from 앞의 문맥을 어떻게 보는지 확인하라 — Kotlin/Java Matcher.find(int) 의 region·anchoring 의미가 여기서 결정적이다.
(c) 뷰 매칭 경로(searchView() 가 만든 view 와 offsets)에서 재탐색이 돌 때 좌표 환원이 어긋나 span 이 원문의 틀린 구간을 가리키거나 범위를 벗어나는 경로.
(d) 새 테스트가 실제로 무는가. backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt 의 "재현율이 낮아지지 않는다"는 masked 의 숫자 개수가 원문보다 적기만 하면 통과한다 — 유효 카드가 아니라 엉뚱한 구간이 가려져도, 혹은 일부만 가려져도 통과하는 구조가 아닌가. "겹친 유효 카드를 찾는다"의 endsWith 단언이 substring(4,5) 로 구분자를 재구성하는 부분이 입력별로 의도대로 작동하는가.
(e) 성능 주장의 반례. KDoc 은 "재탐색 비용은 룩비하인드가 대부분의 시작 위치를 즉시 탈락시켜 흡수한다"고 선언한다. 거부가 연쇄하는 긴 숫자열(예: 수천 자리 연속 숫자, 4자리 그룹이 수백 개)에서 이 sequence 가 2차 이상으로 커지는 입력을 제시하라. 입력 상한은 4,000자다.

[축 2] 새로 세운 하한 장치가 실제로 무언가를 재는가 — .github/parity-full-gate.txt , .claude/skills/python-kotlin-parity/scripts/compare_parity.py 의 full_gate_floor_problems() , backend-kotlin/core/src/test/kotlin/kr/easydoc/core/ParityDeclarationSyncTest.kt .

이 저장소가 반복해 겪은 실패는 "선언한 범위와 실제 도달 범위가 다른데 아무도 재지 않는 것"이다. 장치가 동작하지 않는 것이 아니라, 동작하는 것처럼 보이면서 아무것도 재지 않는 상태를 찾아라.

(a) full_gate_floor_problems() 가 조용히 아무것도 재지 않게 되는 호출 조건. scoped=True 인 실행, selected 가 빈 목록인 실행, --only-domain 을 아예 주지 않는 실행, BUILDERS 키 집합이 실행 시점에 달라지는 경우. CI 설정에서 이 함수가 실제로 발화하는 실행 경로를 짚어라 — 어느 워크플로 스텝이 --only-domain 을 주는가.
(b) 표시 파일을 삭제하지 않고 무력화하는 경로. 게이트가 Path.exists() 만 보는지, 내용(reached/domains 줄)을 보는지. 파일을 비우거나 주석만 남기거나 domains 값을 낮춰도 통과하는가. .gitignore·CI 체크아웃 설정으로 그 파일이 없는 상태가 되는 경로가 있는가.
(c) ParityDeclarationSyncTest 의 fixtureStatuses() 가 mapNotNull 안에서 return@mapNotNull null 로 디렉터리를 건너뛴다. 이 건너뜀 때문에 검사 대상이 0건이 되어도 테스트가 초록으로 끝나는가. 그렇다면 fixture 디렉터리 이름이나 JSON 파일명이 바뀌기만 해도 이 동기화 검사 전체가 무력해진다.
(d) 이 세 장치를 각각 통째로 제거했을 때 정확히 어떤 테스트·CI 스텝이 실패하는가. 떼어도 아무것도 깨지지 않는 장치를 지목하라.

[축 3] 파일명 축의 over 방향 하한이 우회되는가 — compare_parity.py 의 check_contains_derived() 와 신설 _derive_title_markers() , .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py 의 build_export() .

목적은 "사용자가 올린 제목이 파일명에서 통째로 사라지는 것"(필수 정보 유실)을 차단하는 것이다. 하한은 제목에서 유도한 조각이 파일명 문자열 안에 남아 있기만 요구한다.

(a) _derive_title_markers() 가 빈 목록을 돌려주어 아무것도 요구하지 않는 입력의 범위. build_export() 의 titles 표본 중 몇 건이 그 상태인가. 조각 길이 하한이 2자, 창이 앞 40자, 조각 상한이 8자인 것이 만드는 사각지대.
(b) 표지가 우연히 다른 경로로 충족되는 입력. 대체 이름 문자열, 확장자, 구분자, fixture 의 다른 필드가 표지를 우연히 포함해 파일명을 통째로 대체 이름으로 바꿔도 통과하는 경우.
(c) check_contains_derived() 가 str 도 부분 문자열로 보도록 확장된 것이 다른 규칙(style_length_floor, style_comma_floor 등 DERIVATIONS 의 나머지)의 판정을 느슨하게 만드는가. 이전에는 배열이 아니면 실패였고 지금은 문자열이면 통과 후보가 된다.
(d) 계수 단위 불일치. Export.kt 는 파일명 줄기를 코드포인트 80으로 자르고(takeCodePoints), 하한 유도는 파이썬 str 문자 단위로 앞 40자를 본다. 서로게이트 쌍·결합 문자·정규화 차이로 "정제는 길이를 줄이기만 한다"는 전제가 깨져 표지가 잘려 나가는 입력이 있는가. 또 FILENAME_FORBIDDEN(파이썬 쪽)과 FORBIDDEN_IN_FILENAME(Export.kt 쪽, U+0080–U+009F C1 을 새로 포함)이 서로 다른 집합인 것이 하한 유도와 구현 사이에 만드는 어긋남.

[부가 확인] 커밋 2cf862a 는 커밋 메시지가 설명하지 않는 변경을 함께 담고 있다 — compare_parity.py 의 check_contains_derived 확장과 _derive_title_markers 신설, dump_parity_fixtures.py 의 export 단언 추가. 그 변경이 위 축 3에서 선언한 내용과 실제로 일치하는지 diff 로 확인하고, 선언되지 않은 부수 효과가 있으면 지적하라.

[참고 구현의 위치] Python 원본(app/privacy/masking.py, app/easyread/export.py)은 참고 구현이지 정답이 아니다. 2026-08-12 재개발 전환으로 "Python과 같은 값"은 판정 기준에서 빠졌다. 대조하되 "같은가"가 아니라 "요구사항을 만족하는가"를 물어라. 마스킹 대상 범주는 주민등록번호(외국인등록번호 포함)와 카드번호 2종이며, 전화번호·이메일·계좌번호는 의도적으로 마스킹하지 않는다.
```

---

## 3. codex 원문 출력 (무편집)

> 이 구획에는 Claude 가 쓴 문장이 한 줄도 없다. 아래는 스크립트 표준 출력을 그대로 옮긴 것이다.

### 3.1 최종 리뷰 본문

```
# Codex Adversarial Review

Target: branch diff against 516c0e9
Verdict: needs-attention

출하 보류. 실제 CI에서 전체 게이트 하한이 비활성화되며, 파일명 하한도 입력을 무시하는 단일 상수 구현을 통과시킨다.

Findings:
- [high] 전체 게이트 표시가 실제 전체 CI 실행에서는 검사되지 않는다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:1271-1278)
  `full_gate_floor_problems()`는 `selected`가 비면 즉시 성공한다. 그런데 CI의 8/8 경로는 의도적으로 `--only-domain` 없이 비교기를 호출하므로 `selected=[]`이다. `FULL_GATE_PATH`를 존재하지 않는 경로로 바꾼 직접 호출도 문제 0건을 반환했고, 현재 전체 비교는 exit 0이었다. 따라서 표시 파일을 삭제하거나 비우고 `reached`·`domains`를 변조해도 현재 CI는 실패하지 않는다. 이후 BUILDERS에 새 도메인을 추가하면서 표시 파일을 삭제하면 기존 8개만 비교한 exit 3이 다시 성공 처리된다.
  Recommendation: 루트 fixture를 `--only-domain` 없이 실행한 경우 유효 범위를 canonical 전체로 전달해 표시 파일을 반드시 검사하라. 파일의 `reached`와 `domains`도 파싱·검증하고, 표시 파일 삭제·빈 파일·주석 전용·낮춘 domains·미선언 BUILDERS 추가가 CI를 실패시키는 음성 테스트를 추가하라.
- [medium] 파일명 하한이 모든 제목을 무시하는 하나의 공통 파일명을 통과시킨다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:741-749)
  판정은 제목 표지가 파일명 어디엔가 부분 문자열로 존재하는지만 본다. 현재 7개 제목에서 나온 모든 표지를 합친 동일한 46자 stem을 21개 docx/txt/hwpx 결과에 주입한 변이에서 `run_assertions()`가 전건 실패 0건을 반환했다. 즉 구현이 입력 제목을 전혀 읽지 않아도 고정 문자열에 표본 조각만 우연히 포함하면 초록이다. 또한 `all-forbidden`은 표지가 비고, control 표본은 한 글자 조각이 버려져 일반적인 `입니다`만 요구한다. 이는 사용자가 올린 제목의 정보 유실을 막는다는 선언을 증명하지 못한다.
  Recommendation: export 전용 검사를 분리해 입력과 출력 stem의 연관성을 판정하라. 정제 후 비어 있지 않은 제목은 빈 표지를 금지하고, 서로 다른 제목을 같은 stem으로 반환하는 변이와 런타임 생성 고유 표지를 무시하는 변이가 실패하도록 metamorphic 테스트를 추가하라.

Next steps:
- full-gate 표시 파일 음성 변이들을 실제 CI 호출 형태로 고정한다.
- 모든 export 표본에 동일 파일명을 반환하는 변이를 회귀 테스트로 추가한다.
```

### 3.2 codex 가 남긴 중간 판단 요약 (assistant message capture, 원문)

```
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"정확한 기준 커밋 대비 diff, 게이트 호출 경로, 테스트의 실패 감지력을 순서대로 추적하겠습...
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"현재 worktree의 사용자 미추적 파일은 건드리지 않고, 516c0e9..HEAD의 추적 d...
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"첫 번째 차단 후보가 확인됐습니다. CI는 8/8일 때 비교기를 `--only-domain` 없...
[codex] Assistant message captured: {"verdict":"needs-attention","summary":"출하 보류. 실제 CI에서 전체 게이트 하한이 비활성화되며, 파일명 하한도 입력을 무시하는 단일...
```

> 헬퍼가 요약 문자열을 잘라 기록한다(`...` 로 끝난다). **잘림 표시** — 이어붙이기 위해 추측으로 보완하지 않았다. 잘리지 않은 최종 판단은 §3.1 본문이다.

### 3.3 codex 가 실제로 실행한 명령 (도구 트레이스, 원문)

> 지적의 근거가 실측인지 추론인지를 `migration-reviewer` 가 판단할 수 있도록 트레이스를 남긴다. 헬퍼가 명령 문자열을 잘라 기록하므로 여러 줄이 `...` 로 끝난다(잘림).

```
[codex] Starting Codex task thread.
[codex] Thread ready (01a001d6-f039-76d2-b28b-ed579fa796bf).
[codex] Turn started (01a001d6-f18a-7801-b866-7640fbdef57c).
[codex] Running command: /bin/zsh -lc 'git status --short && git log --oneline --decorate 516c0e9..HEAD && git diff --...
[codex] Running command: /bin/zsh -lc 'git diff --unified=80 516c0e9..HEAD -- backend-kotlin/core/src/main/kotlin/kr/e...
[codex] Running command: /bin/zsh -lc 'git diff --unified=80 516c0e9..HEAD -- .github/parity-full-gate.txt .claude/ski...
[codex] Running command: /bin/zsh -lc 'rg -n "easy-doc|parity|fixtureStatuses|full_gate_floor|title markers|golden bas...
[codex] Running command: /bin/zsh -lc 'git status --short'
[codex] Running command: /bin/zsh -lc 'git log --oneline --decorate 516c0e9..HEAD'
[codex] Running command: /bin/zsh -lc "sed -n '1,240p' /Users/harris/.codex/memories/skills/easy-doc-parity-stop-gate-...
[codex] Running command: /bin/zsh -lc "sed -n '400,442p' /Users/harris/.codex/memories/MEMORY.md"
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt |...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest....
[codex] Running command: /bin/zsh -lc "rg -n \"full_gate_floor_problems|check_contains_derived|_derive_title_markers|D...
[codex] Running command: /bin/zsh -lc "rg -n \"compare_parity\\.py|dump_parity_fixtures|parity-full-gate|--only-domain...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt |...
[codex] Running command: /bin/zsh -lc "find .github -maxdepth 3 -type f -print | sort && rg -n \"compare_parity\\.py|-...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/test/kotlin/kr/easydoc/core/ParityDeclarationSyn...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest....
[codex] Running command: /bin/zsh -lc "rg -n \"acceptedMatches|candidateSpans|searchView|data class Search|find\\(\" b...
[codex] Running command: /bin/zsh -lc 'git show --stat --oneline 2cf862a && git show --format=fuller --unified=60 2cf8...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/build.gradle.kts | sed -n '1,260p'; nl -ba backend-kotlin...
[codex] Running command: /bin/zsh -lc "nl -ba .github/workflows/ci.yml | sed -n '150,490p'"
[codex] Running command: /bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/compare_parity.py | sed -n '...
[codex] Running command: /bin/zsh -lc 'git diff --name-status 516c0e9..HEAD; git diff --unified=20 516c0e9..HEAD -- ba...
[codex] Running command: /bin/zsh -lc "nl -ba .github/workflows/ci.yml | sed -n '248,480p'"
[codex] Running command: /bin/zsh -lc "rg -n \"full_gate_floor_problems|FULL_GATE_PATH|parity-full-gate\" . -g '"'!doc...
[codex] Command failed: /bin/zsh -lc "rg -n \"full_gate_floor_problems|FULL_GATE_PATH|parity-full-gate\" . -g '"'!doc... (exit 1)
[codex] Running command: /bin/zsh -lc "git show --format= --unified=20 2cf862a -- .claude/skills/python-kotlin-parity/...
[codex] Running command: /bin/zsh -lc "rg -n \"def check_contains_derived|def _derive_title_markers|DERIVATIONS|contai...
[codex] Running command: /bin/zsh -lc "rg -n '\"id\": \"export-filename|\"title\"|contains_derived|all-forbidden' pari...
[codex] Running command: /bin/zsh -lc "rg -n \"def build_export|titles:|contains_derived|_FALLBACK_NAME|FILENAME|_MAX_...
[codex] Running command: /bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/compare_parity.py | sed -n '...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/Export.kt |...
[codex] Running command: /bin/zsh -lc "rg -n \"contains_derived\" parity/fixtures -g '*.json' | wc -l; rg -n '\"rule\"...
[codex] Running command: /bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py | se...
[codex] Running command: /bin/zsh -lc "sed -n '1,130p' parity/fixtures/style/style.json; sed -n '130,245p' parity/fixt...
[codex] Running command: /bin/zsh -lc 'rg -n "length_violations|comma_violations|sentences" backend-kotlin/core/src/te...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/Export.kt |...
[codex] Running command: /bin/zsh -lc 'git show 2cf862a''^:.claude/skills/python-kotlin-parity/scripts/compare_parity....
[codex] Running command: /bin/zsh -lc 'git show --format=fuller --no-patch 2cf862a; git show --format= --name-status 2...
[codex] Running command: /bin/zsh -lc "rg -n \"ParityDeclarationSyncTest|full_gate_floor_problems|_derive_title_marker...
[codex] Running command: /bin/zsh -lc 'git ls-files .github/parity-full-gate.txt backend-kotlin/core/src/test/kotlin/k...
[codex] Running command: /bin/zsh -lc "nl -ba .claude/skills/python-kotlin-parity/scripts/compare_parity.py | sed -n '...
[codex] Running command: /bin/zsh -lc 'git log --oneline --follow -- backend-kotlin/core/src/test/kotlin/kr/easydoc/co...
[codex] Running command: /bin/zsh -lc ".venv/bin/python -c ' import importlib.util, json, sys from pathlib import Path...
[codex] Running command: /bin/zsh -lc ".venv/bin/python -c ' import importlib.util, json, sys from pathlib import Path...
[codex] Running command: /bin/zsh -lc "nl -ba backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt |...
[codex] Running command: /bin/zsh -lc 'rg -n "MAX_.*TEXT|4000|4_000|DocumentTextRequest|text.*Size|@Size" backend-kotl...
[codex] Running command: /bin/zsh -lc 'git show --format= --unified=5 2cf862a -- docs/migration/_workspace/02_kotlin-i...
[codex] Running command: /bin/zsh -lc 'rg -n "acceptedMatches|80k|4000|성능|Luhn|overlap|겹" docs/migration/_workspace/re...
[codex] Running command: /bin/zsh -lc 'git check-ignore -v .github/parity-full-gate.txt || true; git ls-files --error-...
[codex] Running command: /bin/zsh -lc 'find parity/actual -maxdepth 2 -type f -print | sort | head -30'
[codex] Running command: /bin/zsh -lc 'git diff --check 516c0e9..HEAD'
[codex] Running command: /bin/zsh -lc "git diff --numstat 516c0e9..HEAD -- backend-kotlin/core/src/test/kotlin/kr/easy...
[codex] Running command: /bin/zsh -lc '.venv/bin/python .claude/skills/python-kotlin-parity/scripts/compare_parity.py ...
[codex] Turn completion inferred after the main thread finished and subagent work drained.
```

---

## 4. 정리(가공)

> **이 구획은 Claude 가 목록화한 것이다.** 옳고 그름은 판정하지 않는다 — 판정은 `migration-reviewer` 의 교차 대조와 리더의 몫이다. 원문은 §3 이다.

### 4.1 지적 항목

| # | codex 심각도 | 지적 | codex 가 제시한 근거 위치 | 리더가 지정한 focus 축 |
|---|---|---|---|---|
| C-1 | high | `full_gate_floor_problems()` 가 실제 CI 의 8/8 경로에서 검사되지 않는다 — CI 는 `--only-domain` 없이 호출하므로 `selected=[]` 이고, 함수는 `selected` 가 비면 즉시 성공한다 | `compare_parity.py:1271-1278` | ② 하한 고정의 우회 |
| C-2 | medium | 파일명 하한(`contains_derived` / `_derive_title_markers`)이 모든 제목을 무시하는 단일 공통 파일명을 통과시킨다 | `compare_parity.py:741-749` | ③ #4 파일명 over 하한의 우회 |

### 4.2 codex 가 실측으로 제시했다고 서술한 것

원문에 실측 서술이 포함된 항목을 그대로 옮긴다. 실측 재현 여부의 확인은 이 에이전트의 범위 밖이다.

- C-1: "`FULL_GATE_PATH` 를 존재하지 않는 경로로 바꾼 직접 호출도 문제 0건을 반환했고, 현재 전체 비교는 exit 0이었다"
- C-2: "현재 7개 제목에서 나온 모든 표지를 합친 동일한 46자 stem을 21개 docx/txt/hwpx 결과에 주입한 변이에서 `run_assertions()` 가 전건 실패 0건을 반환했다"
- C-2 부수: "`all-forbidden` 은 표지가 비고, control 표본은 한 글자 조각이 버려져 일반적인 `입니다` 만 요구한다"

트레이스(§3.3)에 `.venv/bin/python -c '...'` 직접 호출 2건과 `compare_parity.py` 실행 1건이 기록돼 있다.

### 4.3 codex 가 **지적하지 않은** 축 — 그대로 기록한다

리더가 지정한 focus 3축 중 **축 ①(#1 수정의 완전성)에 대해 codex 는 지적을 내지 않았다.** 원문 Findings 2건은 모두 축 ②·③ 이다.

축 ① 하위 항목 (a)~(e) — 3중 겹침, lookaround 상호작용, 뷰 좌표 환원, 테스트 결속력, 선형 성능 주장의 반례 — 어느 것에도 지적이 없다. 트레이스를 보면 codex 는 `Masking.kt` 를 3회, `MaskingTest.kt` 를 3회 열람했고 입력 상한(`4000`)을 검색했으므로 **범위 밖이라 못 본 것은 아니다.** 다만 "검토한 결과 문제 없음"이라는 명시적 진술도 원문에 없다 — 원문에 있는 것은 Findings 2건과 그 요약뿐이다. 이 사실을 그대로 남기며, Claude 가 대신 지적을 만들어 채우지 않았다(스킬 §7).

### 4.4 혼입 커밋(2cf862a) 관련

focus 의 [부가 확인] 항목에 대해 codex 는 **별도 finding 을 내지 않았다.** 다만 트레이스에 혼입 커밋을 직접 조사한 명령이 남아 있다.

- `git show --stat --oneline 2cf862a && git show --format=fuller --unified=60 2cf8...`
- `git show --format= --unified=20 2cf862a -- .claude/skills/python-kotlin-parity/...`
- `git show 2cf862a^:.claude/skills/python-kotlin-parity/scripts/compare_parity....` (변경 전 판과 대조)
- `git show --format=fuller --no-patch 2cf862a; git show --format= --name-status 2...`

C-2 가 그 혼입 변경(`compare_parity.py:741-749` = `check_contains_derived`)을 대상으로 하므로, 혼입분이 검토 범위에 실제로 들어갔다는 신호는 있다. "선언대로인가"에 대한 명시적 판정문은 원문에 없다.

### 4.5 전제 확인이 필요한 지점

원문을 삭제하지 않고 표시만 남긴다 (스킬 §7 — 판단은 `migration-reviewer` 몫).

- **전제 확인 필요**: C-1 은 "CI 의 8/8 경로는 의도적으로 `--only-domain` 없이 비교기를 호출한다"를 전제한다. 이 전제의 참·거짓은 `.github/workflows/ci.yml` 의 실제 스텝에 달려 있다. codex 는 트레이스상 `ci.yml` 을 2회(`150,490p` / `248,480p`) 열람했다.
- **전제 확인 필요**: C-1 의 "표시 파일을 삭제하거나 비우고 `reached`·`domains` 를 변조해도 현재 CI 는 실패하지 않는다"는 위 전제에 의존한다. `.github/parity-full-gate.txt` 의 헤더 주석은 이 장치가 "자기 무장"으로 도달 순간 발화한다고 선언하고 있어, 선언과 codex 의 관측이 어긋나는 자리다.
- **행 번호 대조 필요 없음(그대로 옮김)**: `compare_parity.py:1271-1278`, `compare_parity.py:741-749` 는 codex 가 준 값 그대로다. 다시 세지 않았다.

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| codex CLI 호출 | **성공** (종료 코드 0, 재시도 불필요) |
| 리뷰 대상 판정 | **non-empty** — 변경 파일 17개, exit 7 아님 |
| 출력 잘림 | 최종 리뷰 본문(§3.1)은 **온전하다**. `[codex] Assistant message captured:` 요약 줄과 `Running command:` 줄은 헬퍼가 고정 길이로 잘라 기록하므로 `...` 로 끝난다 — §3.2·§3.3 에 잘림을 표시했고 추측으로 보완하지 않았다 |
| 축 ① 지적 | **0건** — 실패가 아니라 codex 가 내지 않은 것이다 (§4.3) |
| 민감 데이터 유출 | 없음 — focus·대상에 실제 문서 본문·키·개인정보 미포함 |
| 이전 회차 | 없음 (게이트 13 1회차) |

**⚠ codex 리뷰 누락 없음.** 독립 관점이 확보된 상태에서 이 게이트의 1단계가 끝났다.

---

## 6. 다음 단계 (프로토콜)

이 산출물은 **가공 없이** `migration-reviewer` 에게 전달된다. 종합·판정·심각도 부여·기각 판단은 이 에이전트가 하지 않는다.

- 1단계 짝 산출물: `docs/migration/_workspace/reviews/13_regression-and-pins_migration-reviewer.md`
- 3단계 교차 종합(예정): `docs/migration/_workspace/reviews/13_regression-and-pins_cross.md`
