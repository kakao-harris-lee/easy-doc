# 게이트 14 · 1단계 codex 독립 리뷰 — `14_floor-hardening`

> # ⚠ codex 리뷰 **불완전 — 최종 판정 미수령**
>
> **사유: codex 사용량 한도 소진.** 1차 시도는 실측 작업을 상당량 수행한 뒤 `verifying` 단계에서 **중간에 멈췄고**(구조화된 최종 findings 미산출), 규약에 따른 1회 재시도는 4초 만에 한도 오류로 실패했다.
>
> ```
> You've hit your usage limit. Upgrade to Pro (...), visit https://chatgpt.com/codex/settings/usage
> to purchase more credits or try again at Aug 20th, 2026 12:33 PM.
> ```
>
> **이 게이트는 완전한 독립 관점을 받지 못했다.** 다만 **부분 출력은 존재한다** — 1차 시도가 남긴 codex 어시스턴트 메시지 3건이 §3에 무편집으로 있고, 그중 3번째는 실측 반례를 담고 있다. **`findings` 배열은 세 번 모두 비어 있다**(`"findings":[]`) — 즉 codex 는 구조화된 지적을 **한 건도 확정 제출하지 않았다.** §3의 `summary` 문장을 지적 확정본으로 읽으면 안 된다.
>
> `migration-reviewer` 와 리더에게: **두 리뷰가 합의했다고 읽을 수 있는 상태가 아니다.** 교차 대조는 codex 측 입력이 부분인 채로 수행된다. 한도 복구는 **2026-08-20** 이라 Phase 2 종료 판정 전 재실행은 크레딧 구매 없이는 불가능하다.

> **회차**: 1차 (게이트 14 최소 회차 · Phase 2 종료 판정 직전 마지막 리뷰).
> **어간 출처**: 리더가 1단계 호출에서 지정한 `14_floor-hardening` 을 그대로 썼다. 임의 슬러그를 만들지 않았다.
> **이 문서의 규약**: §3 은 **codex 원문 무편집**이다. §4 「정리(가공)」만 Claude 가 쓴 구획이며, 거기서도 옳고 그름을 판정하지 않는다. 원문 구획에 Claude 가 쓴 줄은 없다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 (UTC) | 2026-08-15T00:42:42Z 시작 |
| 모드 | `adversarial-review` |
| base / scope | `--base 4dafde5` / scope = auto(미지정, base 지정 시 무시됨) |
| 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) |
| codex CLI | `codex-cli 0.147.0` |
| job id | `review-mstnj7r1-apdq9r` |
| codex session id | `01a002de-fec6-7981-8705-d9203382d20d` |
| job 로그 | `/Users/harris/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-mstnj7r1-apdq9r.log` |

**실행 명령 전문**

```bash
FOCUS="$(cat <focus 파일>)" && .claude/skills/codex-review/scripts/codex-review.sh \
    adversarial --base 4dafde5 --focus "$FOCUS"
```

**스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)**

```
codex-review: 리뷰 대상 = branch diff vs 4dafde5
codex-review: 대상 판정 = non-empty (merge-base=4dafde540846, 변경 파일 14개 (branch 모드는 커밋된 변경만 센다))
```

### 1.1 Target 줄 대조 — **지정 범위와 실제 대상이 1파일 다르다**

리더 지정은 `4dafde5..bbbdb6b`(13파일)인데 실행 시각에 `HEAD` 가 `f079492` 로 진행해 있어 codex 가 실제로 받은 대상은 `4dafde5..f079492`(14파일)다. 차집합은 한 건이다.

```
docs/migration/_workspace/00_progress.md   ← codex 가 추가로 본 파일
```

- **지정 범위의 13파일은 전부 포함**됐다(누락 0).
- 추가된 1파일은 `docs/migration/_workspace/**` 산출물로 `codex-review` 스킬 §2.2 의 리뷰 면제 대상 범주다.
- 이 사실을 기록만 한다 — 대상 확대가 리뷰 결과에 영향을 줬는지의 판정은 `migration-reviewer` 와 리더의 몫이다.

### 1.2 제공한 맥락

focus text 안에 다음을 실었다(저장소 파일은 codex 가 직접 읽었다).

- 배경: Python/FastAPI → Kotlin/Spring Boot 전환이며 이번 diff 는 제품 코드가 아니라 **판정 하네스**라는 것
- 채점 기준 6개(선언 범위 = 실제 도달 / 자기 채점 순환 금지 / 대리 지표 금지 / 근거를 넘는 범위 금지·은폐형 확대 금지 / 억제 표기 규약 / 장치 제거 시 파급 지목 가능)
- 대상 파일·함수 목록, CI 파일과 4개 하한 파일, 신규·수정 테스트 2개
- 참고 구현 경로(`app/easyread/export.py`·`app/privacy/masking.py`·`Export.kt`·`Masking.kt`)와 **Python 은 정답이 아니라는 단서**(2026-08-12 재개발 전환)

### 1.3 프롬프트에 싣지 않은 것

- Claude·다른 리뷰어가 이미 내린 결론, 의심 지점, 이전 회차의 지적 판정
- 사용자 문서 본문·실제 암호문·키·개인정보 (합성 값도 실을 필요가 없었다 — 대상이 하네스 스크립트다)

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
[배경] 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 전환 중이다. 이번 diff는 제품 코드가 아니라 **그 전환을 판정하는 하네스**다 — parity 비교기, fixture 생성기, 개인정보 스캐너, CI 배선. 하네스의 결함은 "동작하지 않는다"가 아니라 "동작하는 것처럼 보이면서 아무것도 재지 않는다"로 나타난다. 초록불을 결함으로 의심하라.

[지켜야 하는 조건 — 채점 기준]
(1) 게이트·하한·불변식은 선언한 범위와 실제 도달 범위가 같아야 한다. 선언만 있고 도달이 0인 장치는 결함이다.
(2) 검사의 기준이 검사 대상 자신에게서 나오면 안 된다(자기 채점 순환).
(3) 성공 판정을 대리 지표(파일 존재, 종료 코드 0, 단언 개수)로 대체하면 안 된다.
(4) 규칙의 범위는 근거를 넘지 않는다. 특히 은폐형(무시·억제·면제) 장치는 넓히면 안 된다.
(5) 억제 표기 `privacy-allow: <RULE-ID> @<지문8자> — <사유>` 는 사람이 읽을 수 있는 사유가 있을 때만 적중을 누른다. 사유가 무의미하면 억제도 안 되고 진단 오류도 나야 한다 — 둘 중 하나만 하면 조용히 통과하거나 조용히 억제된다.
(6) 장치를 제거하면 정확히 무엇이 깨지는지 지목할 수 있어야 한다.

[대상 파일]
- .claude/skills/python-kotlin-parity/scripts/compare_parity.py — full_gate_floor_problems / read_full_gate_mark / declared_floor_domains / check_contains_derived 의 allow_empty / FILENAME_FORBIDDEN
- .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py — build_export 의 title_markers / markers_by_case / exclusive_by_case, build_style 의 allow_empty
- .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py — visible_length / has_visible_reason / MIN_REASON_VISIBLE / _marker_touches / marker_problems / MARKER_RE
- .github/workflows/ci.yml (parity 잡 분기), .github/parity-full-gate.txt, .github/parity-declared-floor.txt, .github/parity-canonical-floor.txt, .github/parity-case-floor.txt
- 테스트: tests/test_parity_ci_gate.py, tests/test_privacy_scanner.py

[질문 — 4축]

① 전체 게이트 하한. full_gate_floor_problems 는 이제 실행 인자(--only-domain)가 아니라 선언 하한 파일(parity-declared-floor.txt)과 표시 파일(parity-full-gate.txt)의 **내용**을 읽어 판정한다. 이 판정을 무력화하는 편집 경로를 전부 찾아라 — 선언 하한 파일 자체를 줄이거나 비우거나 주석으로 만드는 경로, read_full_gate_mark 의 파싱을 만족시키면서 아무 의미도 기록하지 않는 표시를 만드는 경로, 정본(BUILDERS) 쪽을 건드려 대조를 통과시키는 경로, --only / --only-domain / scoped 조합으로 검사가 건너뛰어지는 경로. 각각에 대해 **CI 가 빨개지는가 / 조용히 통과하는가**를 파일·라인으로 답하라. 무력화되면 부분 게이트로 내려가 종료 코드 3(부분 검증)이 통과로 읽힌다.

② 파일명 하한 두 갈래. dump_parity_fixtures.py 의 build_export 가 케이스마다 두 가지를 건다 — ⑴ 자기 제목에서 뽑은 표지의 contains_derived, ⑵ **남의 제목에만 있는 표지의 absent**. 의도는 "모든 제목의 표지를 이어 붙인 상수 하나를 늘 돌려주는 구현"을 걸러내는 것이다. 이 두 갈래를 **동시에** 만족하면서 입력과 무관한 파일명을 내는 구현이 존재하는가? 부분 union, 표지 순서 섞기, 표지를 자르거나 이어 붙여 absent needle 과의 문자열 일치를 피하는 변형, marker_length(8)·marker_window(40) 경계를 이용한 변형을 구체적으로 시도하라. markers_by_case 가 빈 케이스와 exclusive_by_case 가 빈 케이스에서 각각 무엇이 남는지도 확인하라. 그리고 dump_parity_fixtures 의 title_markers 와 compare_parity 의 export_title_markers 는 **두 벌의 같은 규칙**인데, 갈렸을 때 정말 자기 신고되는지 검증하라.

③ 억제 사유 유효성. has_visible_reason 은 유니코드 카테고리 Cc/Cf/Cs/Co/Cn/Zs/Zl/Zp/Mn/Me 를 보이지 않는 것으로 세고 나머지가 2자 이상이면 통과시킨다. 이 판정을 통과하면서 사람에게 아무 정보도 주지 않는 사유를 만들 수 있는가? 구현자가 docstring 에 스스로 적은 한계(문장부호 Po 만으로 된 사유는 통과)의 실질 위험을 판정하고, 결합 문자·RTL/양방향 제어·태그 문자(U+E0000 블록)·이형태 선택자·미할당 코드포인트·서로게이트도 함께 보라. MARKER_RE 가 사유 부분을 어디까지 잡는지(줄 끝, 주석 종료, 한 줄 다중 표기)도 확인하라. 억제(_marker_touches)와 진단(marker_problems)이 **같은 함수**를 쓰는지, 두 경로 중 한쪽만 통과하는 입력이 있는지 확인하라. 무력화되면 개인정보 적중이 사유 없이 조용히 눌린다.

④ 선언 범위 대 실제 도달(위 세 장치를 가로지른다). 각 장치가 **어디서 도는가**를 ci.yml 의 실제 실행 줄로 짚어라 — 로컬만인가, CI 잡에 배선돼 있는가, 아무 데서도 안 도는가. 새로 추가·수정된 테스트(tests/test_parity_ci_gate.py, tests/test_privacy_scanner.py)가 **장치를 실제로 호출하는가, 아니면 장치를 복사한 기대값과 대조하는가**. check_contains_derived 의 allow_empty 는 빈 요구를 기본 실패로 돌리는 장치인데, 그 예외를 선언한 자리(build_style)가 정당한지, allow_empty 가 앞으로 조용히 번질 경로가 있는지 보라. 마지막으로 이 diff 에서 **떼어도 아무 테스트가 깨지지 않는 코드**를 지목하라.

[참고] Python 참고 구현은 app/easyread/export.py(파일명 정제)·app/privacy/masking.py, Kotlin 은 backend-kotlin/core 의 Export.kt·Masking.kt 다. 단 **Python 은 정답이 아니다**(2026-08-12 재개발 전환) — "같은 값이 나오는가"가 아니라 "요구사항을 만족하는가"를 물어라.

[요구] 실측 가능한 것은 실측하라. 파일을 읽고 정규식·집합 연산을 직접 따라가 **반례를 구체적 문자열로** 제시하라. "가능해 보인다"가 아니라 실제 입력과 그때의 판정 결과를 달라. 지적이 없는 축은 없다고 명시하라.
```

---

## 3. codex 원문 출력 (무편집)

> 이 구획에는 **Claude 가 쓴 문장이 한 줄도 없다.** 아래는 codex 가 낸 것 그대로이며, 잘린 자리는 잘린 채로 둔다.

### 3.1 2차 시도 (`review-mstp66p4-ns3u0j`) — 스크립트 stdout 전문

```
# Codex Adversarial Review

Codex did not return valid structured JSON.

- Parse error: You've hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), visit https://chatgpt.com/codex/settings/usage to purchase more credits or try again at Aug 20th, 2026 12:33 PM.
```

스크립트 stderr 마지막 세 줄:

```
[codex] Codex error: You've hit your usage limit. Upgrade to Pro (https://chatgpt.com/explore/pro), visit https://chatgpt.com/codex/settings/usage to purchase more credits or try again at Aug 20th, 2026 12:33 PM.
[codex] Turn failed.
codex-review: 리뷰 도구가 실패했다 (종료 코드 1). 리뷰 근거가 없다.
```

### 3.2 1차 시도 (`review-mstnj7r1-apdq9r`) — 어시스턴트 메시지 3건 (부분 출력)

**⚠ 이 셋은 중간 진행 메시지다. 최종 구조화 결과가 아니며 `findings` 는 세 번 모두 빈 배열이다.**

메시지 1 — `2026-08-15T00:42:56.133Z`

```json
{"verdict":"needs-attention","summary":"현재 diff와 CI 도달 경로를 직접 추적하고, 하한·파일명·억제 정규식에 구체 반례를 넣어 판정 결과를 실측하겠습니다. easy-doc 하네스의 기존 stop-gate 검토 지침도 먼저 확인해 이번 리뷰 기준과 맞추겠습니다.","findings":[],"next_steps":[]}
```

메시지 2 — `2026-08-15T00:44:48.009Z`

```json
{"verdict":"needs-attention","summary":"초기 추적에서 전체 하한은 CI에 실제 배선돼 있지만 독립 기준은 아닙니다. 특히 선언 하한·정본(BUILDERS)·표시 파일을 함께 축소하는 동기화 편집은 자동 실패가 아니라 리뷰 신호에만 의존합니다. 이제 실제 셸 변이와 파일명 상수 출력 반례로 어느 경로가 조용히 초록인지 좁히고 있습니다.","findings":[],"next_steps":[]}
```

메시지 3 — `2026-08-15T00:52:14.173Z` **(마지막 출력 — 이후 로그 정지)**

```json
{"verdict":"needs-attention","summary":"구체 반례가 확인됐습니다. `privacy-allow ... — ..`와 Malayalam spacing mark 두 글자(`U+0D3E U+0D3E`)는 모두 `visible_length=2`로 승인되어 실제 적중 1건을 억제하고 `marker_problems=[]`가 됩니다. 반대로 RTL 제어·태그·variation selector·미할당·서로게이트는 0으로 거절됩니다. 파일명 쪽은 현재 표지 집합에서는 단일 union 상수가 각 케이스의 `absent`에 걸리지만, 생성기 쪽 `title_markers`가 빈 목록으로 갈리는 방향은 단언 자체가 사라져 비교기가 자기 신고하지 못합니다.","findings":[],"next_steps":[]}
```

### 3.3 1차 시도가 실제로 수행한 작업 (job 로그 원본)

로그 전문은 **`docs/migration/_workspace/reviews/14_floor-hardening_codex-attempt1.log`** 에 보존했다(원본은 취소와 함께 사라질 수 있어 저장소로 복사했다. 개인정보 패턴 검사 완료 — 실제 주민번호·카드번호 없음, `privacy-allow ... — ..` 는 codex 가 만든 합성 탐침 문자열이다). codex 는 **명령 22회**를 실행했다 — diff 열람, `ci.yml` 배선 추적, 4개 하한 파일 확인, `ExportParityTest.kt` 열람, `tests/test_parity_ci_gate.py` 전문 열람, 그리고 `visible_length` 판정을 직접 재는 Python 실행 2회.

마지막 4줄:

```
[2026-08-15T00:51:00.418Z] Command completed: /bin/zsh -lc "PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -c 'import base64;exec(base64.b64dec... (exit 0)
[2026-08-15T00:52:14.172Z] Assistant message captured: {"verdict":"needs-attention","summary":"구체 반례가 확인됐습니다. `privacy-allow ... — ..`와 Malayalam sp...
[2026-08-15T00:52:14.457Z] Running command: /bin/zsh -lc 'PYTHONDONTWRITEBYTECODE=1 .venv/bin/pytest -q -p no:cacheprovider tests/test_pr...
[2026-08-15T00:52:14.769Z] Command failed: /bin/zsh -lc 'PYTHONDONTWRITEBYTECODE=1 .venv/bin/pytest -q -p no:cacheprovider tests/test_pr... (exit 1)
```

이후 **35분간 로그 무증가**. `Phase: verifying` 에서 진행이 멈춘 채 45분을 넘겨 취소했다.

---

## 4. 정리(가공)

> **여기부터는 Claude 가 쓴 구획이다.** 목록화만 하며 **옳고 그름·심각도·중복 여부를 판정하지 않는다.** 판정과 종합은 `migration-reviewer` 와 리더의 몫이다.

### 4.1 codex 가 언급한 항목 (전부 부분 출력에서 나온 것 — 확정 지적 아님)

| # | 축 | codex 가 말한 것 (요지) | 상태 |
|---|---|---|---|
| C-1 | ① 전체 게이트 하한 | 전체 하한이 **CI 에 실제 배선돼 있다**고 봤다. 다만 **독립 기준은 아니다** — 선언 하한·정본(`BUILDERS`)·표시 파일을 **함께 축소하는 동기화 편집**은 자동 실패가 아니라 리뷰 신호에만 의존한다 | 메시지 2. 파일·라인 미제시 |
| C-2 | ③ 억제 사유 | `privacy-allow ... — ..`(마침표 2개)와 **Malayalam spacing mark 2자(`U+0D3E U+0D3E`)** 가 둘 다 `visible_length=2` 로 승인되어 **실제 적중 1건을 억제하고 `marker_problems=[]`** 가 된다 | 메시지 3. 실측했다고 서술 |
| C-3 | ③ 억제 사유 | 반대로 **RTL 제어·태그 문자·variation selector·미할당·서로게이트는 `visible_length=0` 으로 거절**된다 | 메시지 3. 음성 방향 확인 서술 |
| C-4 | ② 파일명 두 갈래 | **현재 표지 집합에서는 단일 union 상수가 각 케이스의 `absent` 에 걸린다** (= 그 방향은 막힌다) | 메시지 3 |
| C-5 | ② 파일명 두 갈래 | 그러나 **생성기 쪽 `title_markers` 가 빈 목록으로 갈리는 방향**은 단언 자체가 사라져 **비교기가 자기 신고하지 못한다** | 메시지 3 |

### 4.2 전제 확인 필요

- C-2 의 `U+0D3E` 는 codex 가 카테고리 `Mc`(spacing combining mark)로 전제한 것으로 읽히는데, 구현의 `_INVISIBLE_CATEGORIES` 는 `Mn`·`Me` 만 제외한다. **이 전제와 실측 재현은 `migration-reviewer` 가 확인할 사항이다** — 이 에이전트는 판정하지 않는다.
- C-1 의 "CI 에 배선돼 있다"와 "독립 기준은 아니다"가 같은 장치의 두 측면인지, 서로 다른 두 지점인지는 원문만으로 갈리지 않는다. 파일·라인이 제시되지 않았다.
- C-4 와 C-5 는 같은 축의 **반대 방향 결론**이다(한쪽은 막힌다, 다른 쪽은 못 잡는다). 병합하지 않고 그대로 둔다.

### 4.3 codex 가 **답하지 않은** 것

`migration-reviewer` 가 "codex 도 문제없다고 했다"로 읽지 않도록 명시한다. 아래는 **무지적이 아니라 미수령**이다.

- **축 ④(선언 범위 대 실제 도달)에 대한 답이 없다.** 테스트가 장치를 실제로 호출하는지, `allow_empty` 확산 경로, "떼어도 아무 테스트가 깨지지 않는 코드" 지목 — 전부 미수령.
- **축 ①의 무력화 경로 열거가 미완이다.** 프롬프트가 요구한 4종(선언 하한 축소 / 표시 파일 파싱 만족 / `BUILDERS` 조작 / `--only`·`scoped` 조합) 각각의 "CI 가 빨개지는가" 판정이 없다.
- **축 ②의 변형 시도 결과가 부분이다.** 부분 union·순서 섞기·`marker_length(8)`·`marker_window(40)` 경계 변형에 대한 개별 답이 없다.
- **파일·라인 근거가 어느 항목에도 붙지 않았다.** §5 교차 대조 표의 「근거 파일·라인」 칸을 codex 출처로 채울 수 없다.
- 프롬프트가 요구한 **"지적이 없는 축은 없다고 명시하라"** 에 대한 답이 없다.

---

## 5. 미실행 · 실패 항목

| 항목 | 결과 |
|---|---|
| 1차 시도 `review-mstnj7r1-apdq9r` | **취소(중단).** 45분 경과 · `Phase: verifying` 에서 35분간 로그 정지. 부분 출력 3건 확보 |
| 2차 시도(규약상 1회 재시도) `review-mstp66p4-ns3u0j` | **실패.** 4초 · 사용량 한도 · 스크립트 종료 코드 **1** |
| 스크립트 종료 코드 | 두 시도 모두 **`0` 이 아니다** → `codex-review` 스킬 표에 따라 **리뷰 근거가 되지 않는다** |
| 3차 이상 재시도 | **하지 않았다.** 규약은 1회 재시도이고, 한도 복구 시각이 **2026-08-20 12:33** 이라 즉시 재시도는 같은 오류를 반복한다 |
| Claude 의 대리 리뷰 | **하지 않았다.** codex 실패를 Claude 판단으로 메우는 것은 이 게이트가 금지하는 바로 그 행위다 |

### 5.1 1차 중단과 2차 한도 오류의 관계 (사실 기록)

1차가 멈춘 시각(`00:52:14` 이후 무증가)과 2차가 즉시 한도 오류를 받은 사실은 **같은 원인일 수 있으나 이 에이전트는 단정하지 않는다.** 확인된 사실만 적는다 — 1차는 오류 메시지 없이 진행이 정지했고, 2차는 명시적 한도 오류를 받았다.

### 5.2 리더에게 필요한 결정

이 에이전트는 판정하지 않는다. 다만 게이트 운영상 갈림이 생겼으므로 선택지를 사실로만 적는다.

1. 크레딧을 구매해 **재실행 후 게이트 14 를 닫는다.**
2. **부분 출력 상태로 교차 대조를 진행**하되, `..._cross.md` 에 「codex 축 ④ 미수령·확정 findings 0건」을 명시한다.
3. **2026-08-20 이후로 게이트 14 를 연기**한다.
