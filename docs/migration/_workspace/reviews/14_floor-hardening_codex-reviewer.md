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

---

## 완전 재실행 (2026-08-15) — **완전 수령**

> # ✅ codex 리뷰 **완주 — 최종 판정 수령**
>
> 위 §1~§5 는 **1차(부분 수령) 기록이며 한 글자도 고치지 않았다.** 이 절은 그 아래 덧붙인
> **2차(완전 재실행)** 이다. 리더가 게이트 14 의 완전 재실행을 Phase 3 착수 조건으로 이월했고,
> codex CLI 가 현재 정상 작동하므로 같은 대상·같은 프롬프트로 다시 돌렸다.
>
> **스크립트 종료 코드 `0`**, assistant 메시지 5건, 마지막 메시지에 **`findings` 6건**(high 5 ·
> medium 1)이 채워졌다. 1차의 "확정 findings 0건 · 축 ④ 전부 미수령" 상태는 이 회차로 해소됐다.
>
> **한도 오류는 발생하지 않았다.** 1차 산출물이 적은 "복구 2026-08-20" 전제는 이 실행에서
> 재현되지 않았다 — 그 문장은 1차 시점의 사실로만 읽어야 한다.
>
> **이 절의 규약도 위와 같다**: `R.3` 은 **codex 원문 무편집**이고, `R.4` 「정리(가공)」만
> Claude 가 쓴 구획이며 거기서도 옳고 그름을 판정하지 않는다. 원문 구획에 Claude 가 쓴 줄은 없다.

> **회차**: 2차 (완전 재실행). **어간**: 1차와 같은 `14_floor-hardening` 을 그대로 썼다 —
> 새 슬러그를 만들지 않았다. 2차 교차 종합이 같은 어간으로 이 파일을 찾는다.

---

### R.1 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 (UTC) | 2026-08-15T12:38:05Z 시작 → 12:52:46Z 종료 (**14분 41초**) |
| 회차 | **2차 — 완전 재실행** |
| 모드 | `adversarial-review` |
| base / scope | `--base 4dafde5` / scope = auto(미지정, base 지정 시 무시됨) |
| **실제 대상** | **`4dafde5..f079492` — 변경 파일 14개 (1차와 동일)** |
| 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` (f079492 판 — 현재 HEAD 판과 `git diff` 0) |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` (1차와 동일) |
| codex CLI | `codex-cli 0.147.0` (1차와 동일) |
| job id | `review-msud37gn-ct03ci` |
| codex thread id | `01a0056d-f45c-7493-a596-0850d378a59b` |
| turn id | `01a0056d-f5d5-79e3-bd60-94929aeb6797` |
| job 로그 | `.../state/wt-gate14-1ca6e32c9ea0a829/jobs/review-msud37gn-ct03ci.log` |
| job 로그 저장소 사본 | **`docs/migration/_workspace/reviews/14_floor-hardening_codex-rerun.log`** (28,695바이트 · 개인정보 패턴 검사 0건) |
| **스크립트 종료 코드** | **`0`** — `codex-review` 스킬 표 기준 "리뷰가 실제로 돌았고 비어 있지 않은 출력을 남겼다" |
| 명령 실행 | **30회** |
| assistant 메시지 | 5건 (마지막이 최종 · `findings` 6건) |

#### R.1.1 대상 범위를 어떻게 복원했는가 — **워크트리로 고정**

현재 저장소 HEAD 는 `614afed` 로, 원 회차 **이후** 게이트 14 지적을 다루는 커밋들
(`787bf69`·`b13d502`·`42f9e20`·`0f95a6c` 등)로 진행해 있다. 이 상태에서 `--base 4dafde5` 를
그대로 돌리면 대상이 `4dafde5..614afed` 로 벌어져 **원 회차와 다른 코드**를 리뷰하게 된다.
리더 지시가 "대상 범위를 임의로 바꾸지 마라"이므로, **`f079492` 에 분리 워크트리를 만들어
그 안에서 실행**했다.

```bash
git worktree add --detach <워크트리> f079492
ln -s <저장소>/.venv <워크트리>/.venv        # 1차와 같은 인터프리터를 쓸 수 있게 둔다
cd <워크트리>
FOCUS="$(cat <focus 파일>)" && .claude/skills/codex-review/scripts/codex-review.sh \
    adversarial --base 4dafde5 --focus "$FOCUS"
```

**스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)**

```
codex-review: 리뷰 대상 = branch diff vs 4dafde5
codex-review: 대상 판정 = non-empty (merge-base=4dafde540846, 변경 파일 14개 (branch 모드는 커밋된 변경만 센다))
```

**§1 에 적힌 1차의 판정 두 줄과 글자 그대로 같다.** 대상 14파일도 같다:

```
.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
.claude/skills/python-kotlin-parity/scripts/compare_parity.py
.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py
.github/parity-case-floor.txt
.github/workflows/ci.yml
docs/migration/_workspace/00_progress.md
docs/migration/_workspace/02_kotlin-implementer_masking-grammar.md
docs/migration/_workspace/07_privacy-gate_masking-verdicts.md
parity/fixtures/export/export.json
parity/fixtures/masking/masking.json
parity/fixtures/style/style.json
parity/reference-ledger/masking.json
tests/test_parity_ci_gate.py
tests/test_privacy_scanner.py
```

**지정 범위(`4dafde5..bbbdb6b`, 13파일)가 아니라 1차의 실제 대상(14파일)을 택한 이유**를 사실로만
적는다 — 두 회차의 입력을 같게 두어야 "새로 나온 지적 / 안 나온 지적"이 **입력 차이가 아니라
모델 출력 차이**로 읽힌다. 차집합 1건은 1차와 같은 `docs/migration/_workspace/00_progress.md`
이며 `codex-review` 스킬 §2.2 리뷰 면제 범주다. **지정 13파일 누락 0.**

#### R.1.2 프롬프트 동일성 — **바이트 단위로 같다**

focus text 를 새로 쓰거나 다듬지 않았다. **위 §2 의 코드펜스에서 바이트 단위로 추출**해 그대로
넘겼다.

| 항목 | 값 |
|---|---|
| 길이 | 6,435 바이트 / 30줄 |
| sha256 | `ef18b03f6491e14279d6ded12403fb88d488c3c8dd54770c2a489408e47c8d12` |

따라서 **§2 가 이 회차의 프롬프트 전문이기도 하다** — 아래에 중복해 싣지 않는다.

#### R.1.3 프롬프트에 싣지 않은 것

- **1차의 codex 언급(C-1~C-5)** 과 1차 §4 「정리」의 어떤 문장도 싣지 않았다.
- Claude·다른 리뷰어가 이미 내린 결론, 의심 지점, 심각도 판정.
- **`migration-reviewer` 의 게이트 14 산출물** — 리더 지시에 따라 **열람하지 않았다.**
- 사용자 문서 본문·실제 암호문·키·개인정보 (대상이 하네스 스크립트라 합성 값도 불필요했다).

**1차 결과를 맥락으로 주지 않은 이유(사실 기록)**: 에이전트 규약의 재호출 지침은 "이전 회차에서
지적된 항목이 어떻게 처리됐는지를 맥락으로 준다"인데, **1차는 확정 `findings` 가 0건**이라 처리
여부를 물을 대상이 없다. 리더 요청도 "완전 재실행"이다. 그래서 **같은 입력으로 독립 재측정**하는
쪽을 택했다. 이 선택을 기록만 하며, 적절성 판정은 하지 않는다.

---

### R.2 전달한 프롬프트 전문

**위 §2 와 동일하다** (sha256 `ef18b03f…`, 6,435바이트). 중복 게재하지 않는다.

---

### R.3 codex 원문 출력 (무편집)

> 이 절의 모든 내용은 codex 가 낸 것이다. **Claude 가 쓴 줄은 없다.**
> 절 제목과 메시지 구분 머리말만 이 문서의 구조이며, 그 안의 본문은 원문 그대로다.

#### R.3.1 assistant 메시지 5건 (JSON 원문)

**메시지 1/5 — `2026-08-15T12:38:19.330Z`**

```json
{"verdict":"approve","summary":"검토를 시작합니다. 먼저 이전 easy-doc 하네스 리뷰 기억에서 이번 축과 직접 겹치는 근거만 확인한 뒤, 4dafde5 대비 실제 diff·CI 호출 경로·테스트를 따라가며 각 우회 문자열을 실행해 판정을 측정하겠습니다.","findings":[],"next_steps":["메모리 관련 근거를 짧게 조회","대상 diff와 CI 도달 경로 추적","반례 문자열 및 종료 코드 실측","파일·라인별 차단 사유 정리"]}
```

**메시지 2/5 — `2026-08-15T12:38:42.787Z`**

```json
{"verdict":"approve","summary":"easy-doc parity stop-gate review 지침이 이번 변경과 직접 일치해 적용합니다. 이 지침 때문에 정본 완전성의 empty/partial 경로와 실제 CI 소비자를 우선 확인하며, 과거 결론은 현재 증거로 재검증합니다.","findings":[],"next_steps":["현재 SHA·작업트리·4dafde5 diff 확인","정본/선언/표시 파일 변이 실측","파일명·privacy 반례 실행"]}
```

**메시지 3/5 — `2026-08-15T12:40:49.990Z`**

```json
{"verdict":"approve","summary":"현재까지 전체 하한은 CI의 8/8 경로에서 실제 비교기까지 도달하지만, 표시 파일의 `reached` 값은 비어 있지 않기만 하면 되어 의미 없는 기록도 통과합니다. 파일명 두 갈래는 집합상 상충 조건을 만드는지 실제 표지 집합과 checker 판정으로 확인 중이며, 규칙 두 벌의 드리프트는 방향별로 따로 검증합니다.","findings":[],"next_steps":["표지 집합·상충 여부 계산","privacy Unicode/regex 반례 실행","대상 테스트와 실제 CI 호출 연결 검증"]}
```

**메시지 4/5 — `2026-08-15T12:50:45.448Z`**

```json
{"verdict":"needs-attention","summary":"실측 반례가 확보됐습니다. 현재 8/40 표지 규칙 자체는 union·순서 변경·절단 상수를 막지만, 금지 파일명 문자를 직접 검사하지 않고, 생성기/비교기 규칙 드리프트와 `allow_empty`가 독립 하한을 무효화할 수 있습니다. privacy 사유는 `!!`와 Mc 결합부호 두 자로 실제 적중을 억제하면서 진단 0건이었습니다. 마지막으로 CI 하한 함수의 유일한 호출선은 새 테스트가 통합 경로로 실행하지 않습니다.","findings":[{"severity":"high","title":"파일명 금지 집합이 산출물 검사에 연결되지 않음","body":"직접 checker를 호출했을 때 `?`, `*`, `<`, `>`, U+0085를 기존 정상 파일명에 삽입해도 실패 목록이 모두 비었습니다.","file":".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py","line_start":2040,"line_end":2059,"confidence":0.99,"recommendation":"비교기 소유 금지 집합을 산출물에 직접 적용하는 단언을 추가하십시오."}],"next_steps":["남은 반례를 파일·라인과 CI 영향으로 압축","정적 연결과 diff 무결성 최종 확인","차단 JSON 확정"]}
```

**메시지 5/5 — `2026-08-15T12:52:45.408Z`**

```json
{"verdict":"needs-attention","summary":"No-ship: 하네스가 금지 파일명, 의미 없는 privacy 억제, 정본 자기채점, 독립 하한 제거를 초록으로 만들 수 있고 전체 게이트의 실제 호출선도 테스트로 고정되지 않았다.","findings":[{"severity":"high","title":"금지 파일명 집합의 상당 부분이 산출물 검사에 도달하지 않는다","body":"`dangerous`는 `*`, `?`, `<`, `>`와 대부분의 C0/C1 문자를 누락한다. 비교기의 `FILENAME_FORBIDDEN`도 제목 표지를 자르는 데만 쓰이고 실제 파일명을 거부하지 않는다. 기존 정상 결과의 `docx.filename`에 각각 `?`, `*`, `<`, `>`, U+0085를 삽입해 `run_assertions`를 실행한 결과 모두 실패 목록 `[]`였다. 따라서 선언된 파일명 안전 요구를 위반해도 parity가 초록이다.","file":".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py","line_start":2040,"line_end":2059,"confidence":0.99,"recommendation":"비교기 소유의 전체 금지 집합을 실제 `filename`에 직접 적용하는 checker를 추가하고, 각 C0/C1 및 `*?<>` 반례를 Kotlin 산출물 대상으로 고정하라."},{"severity":"high","title":"파일명 두 갈래는 생성기를 함께 약화하면 자기채점으로 사라진다","body":"현재 8/40 규칙에서는 union·역순·7자 절단·40자 경계 상수가 실패하고, 표지가 빈 `all-forbidden`도 fallback과 모든 외부 표지 absent가 남는다. 그러나 이 양성/음성 단언의 존재 기준이 모두 `build_export` 자신이다. `contains_derived`와 외부표지 `absent` 블록을 제거하고 fixture를 재생성하면 케이스 ID와 도메인 under/over 형태 검사는 그대로 통과한다. 직접 해당 단언들만 제거한 fixture에 상수 `쉬운 글.<fmt>`와 상수 헤더를 넣었더니 7개 파일명 케이스가 전부 `[]`로 통과했다. 또한 비교기 쪽 `TITLE_MARKER_LENGTH`만 1로 갈라 놓으면 `쉬운 글_기_신_안_e_p_보_최_가_입.<fmt>`가 7개 모두 통과해, '두 규칙이 갈리면 자기 신고된다'는 주석도 성립하지 않는다.","file":".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py","line_start":2076,"line_end":2164,"confidence":0.99,"recommendation":"제품 구현과 독립된 단일 파일명 요구 모듈을 생성기와 비교기가 공유하게 하고, 각 export 케이스에 필수 checker 종류가 존재한다는 독립 invariant와 규칙 한쪽만 변이하는 회귀 테스트를 추가하라."},{"severity":"high","title":"의미 없는 문장부호가 개인정보 적중을 진단 없이 억제한다","body":"`visible_length`는 제외 목록에 없는 모든 카테고리를 정보로 센다. 실제 일치하는 `LOG-BODY` Marker/Hit에 사유 `!!` 또는 `..`를 주자 visible length 2, `has_visible_reason=True`, 억제 후 남은 hit `{}`, `marker_problems=[]`였다. Mc 결합부호 U+093E 두 개와 이모지 두 개도 같은 결과다. 반면 RTL 제어(Cf), 태그 문자(Cf), 이형태 선택자(Mn), 미할당(Cn), 서로게이트(Cs)는 올바르게 억제 실패와 진단을 내며, 억제와 진단은 같은 함수를 사용한다. `MARKER_RE`도 줄 끝까지 사유를 잡고 블록 주석 종료를 제외하며 한 줄 다중 표기를 분리하지만, 그 결과 `privacy-allow: LOG-BODY @deadbeef — !!` 자체가 CI에서 유효한 면제가 된다.","file":".claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py","line_start":154,"line_end":204,"confidence":1.0,"recommendation":"사유에 최소 하나 이상의 Unicode Letter 또는 Number를 요구하고, 문장부호·기호·Mc 단독 사유가 적중을 보존하면서 marker 진단도 발생시키는 통합 테스트를 추가하라."},{"severity":"high","title":"전체 게이트 하한의 유일한 실제 호출선을 새 테스트가 검증하지 않는다","body":"새 테스트는 명시적으로 실제 비교 구간을 보지 않고 `full_gate_floor_problems`를 직접 호출한다. 실제 CI 도달은 `compare_parity.main`의 한 줄뿐이다. 그 호출을 제거해도 `tests/test_parity_ci_gate.py`의 새 테스트는 전부 그대로 통과하고, 워크플로 추출 테스트도 배너 이후 반환 코드를 의도적으로 무시한다. CI에서는 현재 8/8 분기가 비교기를 호출하지만, 이 연결을 떼거나 비교 분기를 배너 뒤에서 종료해도 저장소 테스트가 깨지지 않는다. 테스트 파일 자체도 quality 잡의 일반 `pytest` 수집에만 의존해 삭제 시 조용히 사라진다.","file":"tests/test_parity_ci_gate.py","line_start":10,"line_end":28,"confidence":0.99,"recommendation":"실제 `main` 또는 추출한 전체 CI step을 실행해 표시 파일 삭제 시 비제로 종료를 확인하고, `tests/test_parity_ci_gate.py`를 CI에서 경로 지정으로 별도 실행하라."},{"severity":"high","title":"`allow_empty`가 반드시 비어서는 안 되는 style 하한까지 면제한다","body":"두 style floor 단언 모두 모든 케이스에 `allow_empty=True`가 붙는다. `style-too-long`의 현재 독립 length floor는 실제로 1개지만, 유도 함수를 빈 목록으로 변이하고 산출물을 `sentences=[]`, `length_violations=[]`, `comma_violations=[]`로 주자 전체 단언이 `[]`로 통과했다. 변이 전에는 `contains_derived`가 정확히 실패했다. 즉 독립 입력 하한을 제거해도 self-reported sentences 기반 검사만 남아 생산자의 전량 누락을 함께 0으로 채점한다. `allow_empty`의 허용 규칙·케이스를 고정하는 테스트도 없다.","file":".claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py","line_start":1262,"line_end":1275,"confidence":0.99,"recommendation":"실제로 빈 하한인 케이스에만 예외를 붙이고, `allow_empty`를 허용할 rule/case 조합을 비교기에서 제한하라. `too-long`·`too-many-commas`에 빈 producer 결과를 넣는 음성 테스트도 추가하라."},{"severity":"medium","title":"전체 게이트 표시는 보이지 않는 날짜를 받아들이고 `--only`에서는 아예 검사되지 않는다","body":"`reached`는 `strip()` 후 truthy인지밖에 보지 않아 `reached: U+200B`, `domains: 8`을 넣은 직접 호출이 문제 `[]`로 통과했다. 또한 `scoped=True`는 표시와 선언을 읽기 전에 즉시 반환하며, 이 우회는 테스트가 정상 동작으로 고정한다. 현재 CI는 `--only`를 쓰지 않으므로 표시만 삭제하면 빨개지고, 선언 하한의 삭제·공백·단독 축소도 각각 CI 또는 비교기에서 빨개진다. 다만 실제 선언과 선언 하한을 함께 줄이고 표시 파일을 삭제하면 부분 분기로 내려가 종료 코드 3이 다시 성공으로 읽힌다. BUILDERS 축소는 canonical floor를 함께 고치지 않으면 빨개지지만, 모든 기준 파일을 동반 수정하는 강등에는 기계적으로 요구되는 근거가 없다.","file":".claude/skills/python-kotlin-parity/scripts/compare_parity.py","line_start":1297,"line_end":1367,"confidence":0.97,"recommendation":"`reached`를 실제 ISO 날짜로 검증하고 저장소 상태 하한은 `--only` 여부와 무관하게 항상 실행하라. 전체 게이트 강등에는 변경 불가능한 CI 정책이나 별도 승인 증거를 요구하라."}],"next_steps":["privacy 사유를 Letter/Number 기반으로 닫고 문장부호·Mc 반례를 추가한다.","파일명 금지 집합을 실제 산출물에 직접 적용하고 제목 규칙을 단일 독립 출처로 만든다.","export checker 존재와 style 비공백 하한을 독립 mutation test로 고정한다.","전체 parity CI 분기와 `compare_parity.main`을 실행하는 통합 테스트를 명시적으로 배선한다.","전체 게이트 표시·강등 계약을 invocation-independent하고 검증 가능한 증거로 바꾼다."]}
```

#### R.3.2 스크립트가 출력한 최종 보고 블록 (원문)

```text
# Codex Adversarial Review

Target: branch diff against 4dafde5
Verdict: needs-attention

No-ship: 하네스가 금지 파일명, 의미 없는 privacy 억제, 정본 자기채점, 독립 하한 제거를 초록으로 만들 수 있고 전체 게이트의 실제 호출선도 테스트로 고정되지 않았다.

Findings:
- [high] 금지 파일명 집합의 상당 부분이 산출물 검사에 도달하지 않는다 (.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:2040-2059)
  `dangerous`는 `*`, `?`, `<`, `>`와 대부분의 C0/C1 문자를 누락한다. 비교기의 `FILENAME_FORBIDDEN`도 제목 표지를 자르는 데만 쓰이고 실제 파일명을 거부하지 않는다. 기존 정상 결과의 `docx.filename`에 각각 `?`, `*`, `<`, `>`, U+0085를 삽입해 `run_assertions`를 실행한 결과 모두 실패 목록 `[]`였다. 따라서 선언된 파일명 안전 요구를 위반해도 parity가 초록이다.
  Recommendation: 비교기 소유의 전체 금지 집합을 실제 `filename`에 직접 적용하는 checker를 추가하고, 각 C0/C1 및 `*?<>` 반례를 Kotlin 산출물 대상으로 고정하라.
- [high] 파일명 두 갈래는 생성기를 함께 약화하면 자기채점으로 사라진다 (.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:2076-2164)
  현재 8/40 규칙에서는 union·역순·7자 절단·40자 경계 상수가 실패하고, 표지가 빈 `all-forbidden`도 fallback과 모든 외부 표지 absent가 남는다. 그러나 이 양성/음성 단언의 존재 기준이 모두 `build_export` 자신이다. `contains_derived`와 외부표지 `absent` 블록을 제거하고 fixture를 재생성하면 케이스 ID와 도메인 under/over 형태 검사는 그대로 통과한다. 직접 해당 단언들만 제거한 fixture에 상수 `쉬운 글.<fmt>`와 상수 헤더를 넣었더니 7개 파일명 케이스가 전부 `[]`로 통과했다. 또한 비교기 쪽 `TITLE_MARKER_LENGTH`만 1로 갈라 놓으면 `쉬운 글_기_신_안_e_p_보_최_가_입.<fmt>`가 7개 모두 통과해, '두 규칙이 갈리면 자기 신고된다'는 주석도 성립하지 않는다.
  Recommendation: 제품 구현과 독립된 단일 파일명 요구 모듈을 생성기와 비교기가 공유하게 하고, 각 export 케이스에 필수 checker 종류가 존재한다는 독립 invariant와 규칙 한쪽만 변이하는 회귀 테스트를 추가하라.
- [high] 의미 없는 문장부호가 개인정보 적중을 진단 없이 억제한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:154-204)
  `visible_length`는 제외 목록에 없는 모든 카테고리를 정보로 센다. 실제 일치하는 `LOG-BODY` Marker/Hit에 사유 `!!` 또는 `..`를 주자 visible length 2, `has_visible_reason=True`, 억제 후 남은 hit `{}`, `marker_problems=[]`였다. Mc 결합부호 U+093E 두 개와 이모지 두 개도 같은 결과다. 반면 RTL 제어(Cf), 태그 문자(Cf), 이형태 선택자(Mn), 미할당(Cn), 서로게이트(Cs)는 올바르게 억제 실패와 진단을 내며, 억제와 진단은 같은 함수를 사용한다. `MARKER_RE`도 줄 끝까지 사유를 잡고 블록 주석 종료를 제외하며 한 줄 다중 표기를 분리하지만, 그 결과 `privacy-allow: LOG-BODY @deadbeef — !!` 자체가 CI에서 유효한 면제가 된다.
  Recommendation: 사유에 최소 하나 이상의 Unicode Letter 또는 Number를 요구하고, 문장부호·기호·Mc 단독 사유가 적중을 보존하면서 marker 진단도 발생시키는 통합 테스트를 추가하라.
- [high] 전체 게이트 하한의 유일한 실제 호출선을 새 테스트가 검증하지 않는다 (tests/test_parity_ci_gate.py:10-28)
  새 테스트는 명시적으로 실제 비교 구간을 보지 않고 `full_gate_floor_problems`를 직접 호출한다. 실제 CI 도달은 `compare_parity.main`의 한 줄뿐이다. 그 호출을 제거해도 `tests/test_parity_ci_gate.py`의 새 테스트는 전부 그대로 통과하고, 워크플로 추출 테스트도 배너 이후 반환 코드를 의도적으로 무시한다. CI에서는 현재 8/8 분기가 비교기를 호출하지만, 이 연결을 떼거나 비교 분기를 배너 뒤에서 종료해도 저장소 테스트가 깨지지 않는다. 테스트 파일 자체도 quality 잡의 일반 `pytest` 수집에만 의존해 삭제 시 조용히 사라진다.
  Recommendation: 실제 `main` 또는 추출한 전체 CI step을 실행해 표시 파일 삭제 시 비제로 종료를 확인하고, `tests/test_parity_ci_gate.py`를 CI에서 경로 지정으로 별도 실행하라.
- [high] `allow_empty`가 반드시 비어서는 안 되는 style 하한까지 면제한다 (.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py:1262-1275)
  두 style floor 단언 모두 모든 케이스에 `allow_empty=True`가 붙는다. `style-too-long`의 현재 독립 length floor는 실제로 1개지만, 유도 함수를 빈 목록으로 변이하고 산출물을 `sentences=[]`, `length_violations=[]`, `comma_violations=[]`로 주자 전체 단언이 `[]`로 통과했다. 변이 전에는 `contains_derived`가 정확히 실패했다. 즉 독립 입력 하한을 제거해도 self-reported sentences 기반 검사만 남아 생산자의 전량 누락을 함께 0으로 채점한다. `allow_empty`의 허용 규칙·케이스를 고정하는 테스트도 없다.
  Recommendation: 실제로 빈 하한인 케이스에만 예외를 붙이고, `allow_empty`를 허용할 rule/case 조합을 비교기에서 제한하라. `too-long`·`too-many-commas`에 빈 producer 결과를 넣는 음성 테스트도 추가하라.
- [medium] 전체 게이트 표시는 보이지 않는 날짜를 받아들이고 `--only`에서는 아예 검사되지 않는다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:1297-1367)
  `reached`는 `strip()` 후 truthy인지밖에 보지 않아 `reached: U+200B`, `domains: 8`을 넣은 직접 호출이 문제 `[]`로 통과했다. 또한 `scoped=True`는 표시와 선언을 읽기 전에 즉시 반환하며, 이 우회는 테스트가 정상 동작으로 고정한다. 현재 CI는 `--only`를 쓰지 않으므로 표시만 삭제하면 빨개지고, 선언 하한의 삭제·공백·단독 축소도 각각 CI 또는 비교기에서 빨개진다. 다만 실제 선언과 선언 하한을 함께 줄이고 표시 파일을 삭제하면 부분 분기로 내려가 종료 코드 3이 다시 성공으로 읽힌다. BUILDERS 축소는 canonical floor를 함께 고치지 않으면 빨개지지만, 모든 기준 파일을 동반 수정하는 강등에는 기계적으로 요구되는 근거가 없다.
  Recommendation: `reached`를 실제 ISO 날짜로 검증하고 저장소 상태 하한은 `--only` 여부와 무관하게 항상 실행하라. 전체 게이트 강등에는 변경 불가능한 CI 정책이나 별도 승인 증거를 요구하라.

Next steps:
- privacy 사유를 Letter/Number 기반으로 닫고 문장부호·Mc 반례를 추가한다.
- 파일명 금지 집합을 실제 산출물에 직접 적용하고 제목 규칙을 단일 독립 출처로 만든다.
- export checker 존재와 style 비공백 하한을 독립 mutation test로 고정한다.
- 전체 parity CI 분기와 `compare_parity.main`을 실행하는 통합 테스트를 명시적으로 배선한다.
- 전체 게이트 표시·강등 계약을 invocation-independent하고 검증 가능한 증거로 바꾼다.
```

---

### R.4 정리(가공)

> **여기부터는 Claude 가 쓴 구획이다.** §R.3 원문의 지적을 목록화·색인한 것이며,
> **옳고 그름·심각도 재부여·중복 병합·오탐 판정은 하지 않았다.** 심각도와 confidence 는
> codex 가 붙인 값을 그대로 옮겼다. 판정과 종합은 `migration-reviewer` 와 리더의 몫이다.

#### R.4.1 최종 판정 (codex 원문 인용)

- **verdict**: `needs-attention`
- **summary**: "No-ship: 하네스가 금지 파일명, 의미 없는 privacy 억제, 정본 자기채점, 독립 하한
  제거를 초록으로 만들 수 있고 전체 게이트의 실제 호출선도 테스트로 고정되지 않았다."

#### R.4.2 지적 색인 — 6건 (심각도·conf 는 codex 부여값)

| # | 심각도 | 제목 | 위치 (codex 지목) | conf | focus 축 |
|---|---|---|---|---|---|
| R-1 | **high** | 금지 파일명 집합의 상당 부분이 산출물 검사에 도달하지 않는다 | `dump_parity_fixtures.py:2040-2059` | 0.99 | ② |
| R-2 | **high** | 파일명 두 갈래는 생성기를 함께 약화하면 자기채점으로 사라진다 | `dump_parity_fixtures.py:2076-2164` | 0.99 | ② |
| R-3 | **high** | 의미 없는 문장부호가 개인정보 적중을 진단 없이 억제한다 | `scan_privacy_invariants.py:154-204` | **1.0** | ③ |
| R-4 | **high** | 전체 게이트 하한의 유일한 실제 호출선을 새 테스트가 검증하지 않는다 | `tests/test_parity_ci_gate.py:10-28` | 0.99 | ④ |
| R-5 | **high** | `allow_empty` 가 반드시 비어서는 안 되는 style 하한까지 면제한다 | `dump_parity_fixtures.py:1262-1275` | 0.99 | ④ |
| R-6 | medium | 전체 게이트 표시는 보이지 않는 날짜를 받아들이고 `--only` 에서는 아예 검사되지 않는다 | `compare_parity.py:1297-1367` | 0.97 | ① |

**심각도 분포 — high 5 · medium 1 · low 0.**

#### R.4.3 축별 도달 — 네 축 모두에 지적이 붙었다

프롬프트가 요구한 **"지적이 없는 축은 없다고 명시하라"** 에 해당하는 자리는 **없다** — 네 축
전부 지적이 나왔다. 다만 codex 는 **축 안에서 "막힌다"고 명시한 갈래**를 본문에 적었다.
`migration-reviewer` 가 이를 무지적으로 읽지 않도록 **원문 인용**으로만 옮긴다.

- **축 ① (전체 게이트 하한)** — "현재 CI 는 `--only` 를 쓰지 않으므로 표시만 삭제하면 빨개지고,
  선언 하한의 삭제·공백·단독 축소도 각각 CI 또는 비교기에서 빨개진다." / "BUILDERS 축소는
  canonical floor 를 함께 고치지 않으면 빨개지지만, 모든 기준 파일을 동반 수정하는 강등에는
  기계적으로 요구되는 근거가 없다."
- **축 ② (파일명 두 갈래)** — "현재 8/40 규칙에서는 union·역순·7자 절단·40자 경계 상수가
  실패하고, 표지가 빈 `all-forbidden` 도 fallback 과 모든 외부 표지 absent 가 남는다."
- **축 ③ (억제 사유)** — "반면 RTL 제어(Cf), 태그 문자(Cf), 이형태 선택자(Mn), 미할당(Cn),
  서로게이트(Cs)는 올바르게 억제 실패와 진단을 내며, **억제와 진단은 같은 함수를 사용한다.**"
  / "`MARKER_RE` 도 줄 끝까지 사유를 잡고 블록 주석 종료를 제외하며 한 줄 다중 표기를
  분리하지만, 그 결과 `privacy-allow: LOG-BODY @deadbeef — !!` 자체가 CI 에서 유효한 면제가 된다."

#### R.4.4 1차 부분 수령(C-1~C-5)과의 대조 — **사실 대조만 한다**

> 어느 쪽이 옳은지 판정하지 않는다. **대응 관계와 부재만 적는다.**

| 1차 언급 | 재실행분에서 | 대응 |
|---|---|---|
| **C-1 전단** — 전체 하한이 CI 에 실제 배선돼 있다 | 메시지 3 "전체 하한은 CI 의 8/8 경로에서 실제 비교기까지 도달하지만…", R-6 본문 "현재 CI 는 `--only` 를 쓰지 않으므로 표시만 삭제하면 빨개지고…" | **재확인** (확정 findings 가 아니라 "막히는 갈래" 서술로) |
| **C-1 후단** — 동기화 축소 편집은 자동 실패가 아니라 리뷰 신호에만 의존 | **R-6 에서 확정 `findings` 로 나왔다** — "실제 선언과 선언 하한을 함께 줄이고 표시 파일을 삭제하면 부분 분기로 내려가 **종료 코드 3 이 다시 성공으로 읽힌다**", "모든 기준 파일을 동반 수정하는 강등에는 **기계적으로 요구되는 근거가 없다**" | **재확인 + 구체화** (1차는 파일·라인 미제시, 재실행분은 `compare_parity.py:1297-1367`) |
| **C-2** — `..` 와 Mc 결합표 2자가 `visible_length=2` 로 억제 통과하고 `marker_problems=[]` | **R-3 에서 확정 `findings` 로 나왔다** — `!!` 와 `..`, **Mc 결합부호 U+093E 두 개**, **이모지 두 개**까지 | **재확인 + 확장.** 단 **Mc 코드포인트가 다르다** — 1차 `U+0D3E`(Malayalam) / 재실행 `U+093E`(Devanagari). 이모지 갈래는 1차에 없다 |
| **C-3** — RTL 제어·태그·이형태 선택자·미할당·서로게이트는 `0` 으로 거절 | **R-3 본문에 그대로** (카테고리 표기까지 붙어서: Cf·Cf·Mn·Cn·Cs) | **재확인** |
| **C-4** — union 상수는 각 케이스의 `absent` 에 걸린다(= 그 방향은 막힌다) | **R-2 본문에 그대로** — "union·역순·7자 절단·40자 경계 상수가 실패하고" | **재확인 + 변형 3종 추가 판정**(역순·7자 절단·40자 경계는 1차에 없다) |
| **C-5** — 생성기 `title_markers` 가 **빈 목록으로 갈리는 방향**은 **단언 자체가 사라져** 비교기가 자기 신고하지 못한다 | **이 형태로는 나오지 않았다.** 재실행분 R-2 는 같은 축에서 **다른 드리프트**를 든다 — "비교기 쪽 `TITLE_MARKER_LENGTH` 만 1로 갈라 놓으면 … 7개 모두 통과해, '두 규칙이 갈리면 자기 신고된다'는 주석도 성립하지 않는다". 또 빈 표지 케이스에 대해서는 "표지가 빈 `all-forbidden` 도 **fallback 과 모든 외부 표지 absent 가 남는다**"고 적는다 | **미재현.** 두 진술의 관계(같은 결론의 다른 경로인지, 서로 다른 판정인지) 판정은 **`migration-reviewer` 의 몫**이다 |

**재실행분에만 있는 지적** — 1차 부분 수령에 대응 언급이 **없다**:

- **R-1** (금지 파일명 집합 `*?<>`·C0/C1 대부분이 산출물 검사에 미도달)
- **R-4** (전체 게이트 하한의 유일한 실제 호출선을 새 테스트가 검증하지 않음)
- **R-5** (`allow_empty` 가 style 하한까지 면제)
- **R-2 의 자기채점 갈래** (생성기·비교기의 단언 블록을 **함께** 제거하면 사라진다) — 1차의 C-4·C-5
  어느 쪽과도 다른 진술이다

**축 ④ 는 1차에서 전부 미수령이었다.** 재실행에서 **R-4·R-5 두 건**이 붙었다. 프롬프트가 축 ④ 에서
정면으로 물은 세 질문 중,

1. "테스트가 장치를 실제로 호출하는가, 아니면 장치를 복사한 기대값과 대조하는가" → **R-4 가 답한다.**
2. "`allow_empty` 가 앞으로 조용히 번질 경로가 있는가" → **R-5 가 답한다.**
3. "떼어도 아무 테스트가 깨지지 않는 코드를 지목하라" → **별도 항목으로는 오지 않았다.** 다만 R-4
   본문이 "그 호출을 제거해도 … 전부 그대로 통과한다" 및 "테스트 파일 자체도 quality 잡의 일반
   `pytest` 수집에만 의존해 삭제 시 조용히 사라진다"로 그 형태를 답한다. **이를 3번의 답으로 셀지는
   판정 사항이라 여기서 정하지 않는다.**

#### R.4.5 전제 확인 필요

> 원문은 지우지 않고 **여기에만** 표시한다. 판단은 `migration-reviewer` 가 한다.

- **R-4 의 지목 행 `tests/test_parity_ci_gate.py:10-28` 은 이 커밋 판에서 코드가 아니라 모듈
  docstring 이다.** 그 구획은 테스트가 **스스로 적은 한계 선언**("이 테스트는 step 의 **가드
  구간**만 판정한다 … 그 뒤의 실제 비교는 보지 않는다")이다. codex 가 그 자기 신고 문장을 근거로
  든 것인지, 다른 행을 가리키려 한 것인지 대조가 필요하다.
- **R-1 의 두 집합 주장은 이 커밋 판에서 문자열로 확인 가능한 형태다** — `dangerous`(2040-2050)는
  `/ \ .. " U+0000 U+001F U+007F : |` 9개이고, `_FORBIDDEN_IN_FILENAME_SET`(2055-2059)은
  C0 전체·C1 전체·`"\/:*?<>|` 이다. 다만 "`run_assertions` 를 실행한 결과 모두 실패 목록 `[]`
  였다"는 **실측 주장**의 재현은 판정 단계에서 확인할 사항이다.
- **R-2·R-5 는 변이(mutation) 실측 주장**을 담는다(단언 블록을 제거하고 fixture 재생성 / 유도
  함수를 빈 목록으로 변이). codex 는 `python3 -c 'import importlib.util …'` 프로브를 여러 차례
  실행했다(job 로그 — 총 명령 30회). 재현 여부는 판정 단계 몫이다.
- **R-6 의 행 범위 시작 `compare_parity.py:1297` 은 `read_full_gate_mark` 정의 행이다.** 끝 행
  1367 이 `full_gate_floor_problems` 까지 덮는지는 대조가 필요하다.
- **R-3 의 `confidence` 는 `1.0`** 이다 — 이 회차에서 codex 가 1.0 을 붙인 **유일한** 항목이다.
- codex 는 `.venv` 존재를 확인한 뒤에도 대부분의 프로브를 **시스템 `python3`** 로 돌렸다
  (job 로그). 1차는 `.venv/bin/python` 을 썼다. 인터프리터 차이가 유니코드 카테고리 판정에
  영향을 주는지는 판정 단계에서 볼 사항이다.

#### R.4.6 codex 가 낸 next_steps (원문)

1. privacy 사유를 Letter/Number 기반으로 닫고 문장부호·Mc 반례를 추가한다.
2. 파일명 금지 집합을 실제 산출물에 직접 적용하고 제목 규칙을 단일 독립 출처로 만든다.
3. export checker 존재와 style 비공백 하한을 독립 mutation test 로 고정한다.
4. 전체 parity CI 분기와 `compare_parity.main` 을 실행하는 통합 테스트를 명시적으로 배선한다.
5. 전체 게이트 표시·강등 계약을 invocation-independent 하고 검증 가능한 증거로 바꾼다.

---

### R.5 미실행 · 실패 항목

| 항목 | 결과 |
|---|---|
| codex 호출 | **1회로 완주.** 종료 코드 **0** · 14분 41초 · 명령 30회 · assistant 5건 |
| 규약상 1회 재시도 | **불필요** (첫 시도가 성공) |
| 사용량 한도 | **발생하지 않았다.** 1차 산출물의 "복구 2026-08-20" 전제는 이 실행에서 재현되지 않았다 |
| 잘림·부분 응답 | **없다.** 최종 assistant 메시지에 `findings` 6건이 채워졌고 스크립트 최종 보고 블록도 완결이다 |
| Claude 의 대리 리뷰 | **하지 않았다.** codex 출력이 실재하므로 대체 산출물 경로를 쓰지 않았다 |
| `migration-reviewer` 게이트 14 산출물 | **열람하지 않았다** (리더 지시). 재실행분의 교차 종합은 **별도 호출** 몫이다 |
| 1차 기록 | **한 글자도 지우지 않았다.** 위 §1~§5 원문 그대로 |
| 워크트리 | `f079492` detached 워크트리에서 실행한 뒤 **`git worktree remove` 로 정리했다.** job 로그는 그 전에 저장소로 복사해 두었다(위 「job 로그 저장소 사본」). **저장소 작업 트리(`614afed`)는 건드리지 않았다** — 재현이 필요하면 R.1.1 의 명령을 그대로 다시 쓰면 된다 |
| `00_progress.md` 원장 | **갱신하지 않았다** (리더 일괄 갱신) |
| 커밋 | **하지 않았다** (리더 지시) |

---

### R.6 `migration-reviewer` 와 리더에게

1. **이 회차는 완전 수령이다.** 1차의 "확정 `findings` 0건 · 축 ④ 전부 미수령" 상태는 해소됐다.
   1차 §4.3 「codex 가 답하지 않은 것」에 열거된 항목 중 축 ④ 두 질문과 축 ① 무력화 경로 판정이
   이번에 왔다.
2. **입력은 1차와 같다.** 같은 14파일(`4dafde5..f079492`), 같은 focus 6,435바이트
   (sha256 `ef18b03f…`). 따라서 두 회차의 출력 차이는 **입력 차이가 아니다.**
3. **재실행분이 보는 코드는 `f079492` 다.** 원 회차 **이후** 커밋(`787bf69`·`b13d502`·`42f9e20`
   등)은 이 대상에 **들어 있지 않다.** 그 커밋들이 R-1~R-6 을 이미 고쳤는지는 **이 에이전트가
   판정하지 않는다** — 교차 종합에서 다룰 사항이다.
4. **1차의 C-5 는 재실행분에서 같은 형태로 나오지 않았다**(R.4.4 마지막 행). 1차 원문도 재실행분
   원문도 지우지 않았다. 두 진술이 양립하는지의 판정은 `migration-reviewer` 몫이다.
5. **R-3 은 보안 축(§2.3 · 억제 표기)이고 codex 가 confidence 1.0 을 붙인 유일한 항목**이다.
   보안 축 판정이 갈리면 `privacy-gate` 가 우선한다는 규약이 이 항목에 걸린다.
