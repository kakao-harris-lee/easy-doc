# 05_scope-reach — codex 독립 리뷰 (1회차)

> 이 문서는 `codex-reviewer` 산출물이다. **codex 출력은 무편집 원문**이며, Claude의 정리는 §5 「정리(가공)」 구획에만 있다.
> 판정·심각도 조정·중복 병합·오탐 표시는 하지 않는다 — 그것은 `migration-reviewer`의 교차 대조(`05_scope-reach_cross.md`)와 리더의 몫이다.

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-13 06:20~06:46 KST (2회 병렬) |
| 회차 | 1회차 (이 scope의 첫 codex 리뷰) |
| 대상 커밋 | `34a2b923694c362825a1c83daf88a48918c4d557` 기준 working-tree 변경 7파일 |
| 리뷰 대상 | `.claude/skills/kotlin-migration/SKILL.md` · `CLAUDE.md` · `.github/workflows/ci.yml` · `README.md` · `.claude/agents/kotlin-implementer.md` · `docs/migration/_workspace/00_progress.md` · `tests/test_harness_scope_reach.py` |
| 모드 | `adversarial` × 2 (축을 갈라 병렬 호출) |
| 스크립트 종료 코드 | **A = 0 · B = 0** (둘 다 리뷰 근거로 유효) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) |
| codex CLI | `codex-cli 0.147.0; advanced runtime available` / node v22.21.1 |
| 인증 | ChatGPT login active (`auth.loggedIn = true`, verified) |
| thread id (A) | `019ff7e8-33e5-7c13-a06e-136e90289360` |
| thread id (B) | `019ff7e8-4caf-7eb3-b5ba-0ff278aa9ed1` |
| `{phase}_{scope}` 어간 | `05_scope-reach` — **리더 지정값을 그대로 사용** (아래 §5 절차 기록 참조) |

### 1.1 리뷰 대상 격리 — 왜 실저장소에서 직접 돌리지 않았는가

실저장소 작업 트리에서 `--scope working-tree`로 돌리면 대상 판정이
`non-empty (staged 1 / unstaged 6 / untracked 74)`가 된다. 이 **untracked 74건은 전부 리뷰 대상이 아니다** —
`docs/golden/`(76MB, PDF·HWPX 원문) · `docs/golden-drafts/`(48개 JSON 중 45개가 24KB 미만이라 헬퍼가 **본문을 그대로 컨텍스트에 싣는다**) · `.playwright-mcp/` 로그다.
헬퍼는 working-tree 리뷰에서 untracked 파일 본문을 싣기 때문에(`git.mjs formatUntrackedFile`),
① 실제 리뷰 대상 7파일이 공공기관 문서 본문 1.5MB에 묻히고 ② 리뷰와 무관한 문서 본문이 외부 도구 호출에 실린다.

그래서 **격리 클론**을 만들어 변경분만 단일 커밋으로 올리고 `--base HEAD~1`로 리뷰했다.

```
git clone --local --no-hardlinks <repo> <scratch>/review-clone
git -C <clone> checkout 34a2b923694c362825a1c83daf88a48918c4d557
git -C <repo> diff HEAD > changeset.patch      # staged + unstaged 전부
git -C <clone> apply --index changeset.patch
git -C <clone> commit -m "harness: scope-reach rule 4 replacement + rule 3 execution-path enforcement"
```

클론의 파일 경로·라인 번호는 실저장소의 **변경 적용 후 상태와 동일**하므로 codex가 인용한 `파일:라인`은 그대로 대조 가능하다.
클론에는 실저장소 `.venv`를 심볼릭 링크해 codex가 `pytest`·`mypy`·`ruff`를 **실제로 실행해 검증**할 수 있게 했다(아래 원문에 실행 결과가 인용돼 있다).

### 1.2 스크립트가 stderr에 찍은 대상 판정 (A·B 동일)

```
codex-review: 리뷰 대상 = branch diff vs HEAD~1
codex-review: 대상 판정 = non-empty (merge-base=34a2b923694c, 변경 파일 7개 (branch 모드는 커밋된 변경만 센다))
codex-review: 헬퍼 = /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs
codex-review: 출처 = plugins cache (최신 버전 자동 선택)
codex-review: 모드 = adversarial-review / scope = auto(미지정) / base = HEAD~1
```

### 1.3 실행 명령 전문

```bash
# A
cd <clone> && ./.claude/skills/codex-review/scripts/codex-review.sh \
  adversarial --base HEAD~1 "$(cat focus_A.txt)"
# → EXIT_A=0

# B
cd <clone> && ./.claude/skills/codex-review/scripts/codex-review.sh \
  adversarial --base HEAD~1 "$(cat focus_B.txt)"
# → EXIT_B=0
```

### 1.4 제공한 맥락

- 하네스 규칙 전문(`SKILL.md` 「선언한 범위와 실제 도달을 대조한다」 절)과 그 요약(`CLAUDE.md`)
- 교체 **전** 규칙 4 문구(횟수 기준)를 프롬프트에 인용 — 새 기준과 정면 비교시키기 위함
- 근거 표 7건의 존재와 위치, 특히 6번(품질 합격선 CI 도달 0)의 기제(`-m llm` 마커 + `addopts = "-m 'not llm'"`)
- 어휘 6종의 정의와 취지, 규칙 5(음성 대조)·규칙 6(자기 검사) 원문
- 저장소 커밋 전 게이트 순서(ruff → mypy → pytest)와 `quality` 잡
- 클론 전체(codex가 `00_progress.md` 45행, 테스트 761줄, `ci.yml`, `pyproject.toml`을 직접 읽고 실행)

**민감 데이터 미포함 확인**: 프롬프트에 사용자 문서 본문·암호문·키·개인정보 없음. 골든셋 문서는 §1.1의 격리로 컨텍스트에서 제외됐다.

---

## 2. 전달한 프롬프트 전문 — 호출 A (규칙 설계 축)

```
[배경] 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 중이고, `.claude/**` 의 스킬·에이전트 정의가 그 전환의 게이트(하네스)다. 이번 변경은 제품 코드가 아니라 **게이트를 만드는 규칙 자체**를 고친다. 리뷰 대상은 HEAD 커밋 하나(`--base HEAD~1`)다.

[지켜야 하는 조건 — 채점 기준]
1. 하네스 규칙은 **다음 사람이 혼자 읽고 적용할 때 판정이 갈리지 않아야** 한다. 판정이 사람마다 갈리면 그것은 규칙이 아니라 수사이고, 사후 합리화의 통로가 된다.
2. 이 저장소는 같은 형태의 실패를 **일곱 번** 겪었다 — 선언한 범위와 실제 도달 범위가 다른데 아무도 재지 않았다. 7건은 `.claude/skills/kotlin-migration/SKILL.md` 의 「무엇이 실제로 났는가」 표에 있다. 새 규칙은 **그 7건을 전부 올바르게 재판정**할 수 있어야 한다.
3. 규칙 4는 "범위는 근거를 넘지 않는다"의 판정 기준이다. **교체 전 기준은 횟수**였다: "열거식이 실제로 두 번 누락된 실측이 있으면 전역이 정당하고, 한 번 겪은 일에는 그 한 자리만 막는다." 이번 변경은 이 횟수 기준을 폐기하고 **두 질문**으로 바꿨다 — ⑴ 빈자리가 구조적으로 재발하는가 ⑵ 그 장치가 탐지로 작동하는가 은폐로 작동하는가.
4. **전문은 `SKILL.md` 한 곳**이고 `CLAUDE.md` 는 그 요약이다. 이 하네스는 같은 문장을 여러 곳에 복제했다가 복사본이 갈리는 실패를 이미 겪었고, 그래서 "정의를 한 곳에만 둔다"가 명시 규칙이다.
5. 규칙 3은 `00_progress.md` 표에 `실행 경로` 열을 신설하고 어휘 6종(`ci:<잡>` / `local:<명령>` / `1회성:<경로>` / `결정:<날짜>` / `안 돎` / `미배선`)을 정본으로 고정한다. 어휘는 "돌지 않는 게이트를 근거로 종료 조건을 닫는 것"을 막기 위한 것이다.

[대상 파일]
- `.claude/skills/kotlin-migration/SKILL.md` — 「선언한 범위와 실제 도달을 대조한다」 절 전체(규칙 1~6, 근거 표 7행, 「어디에 적용하는가」)
- `CLAUDE.md` — "범위 대조" 문단과 변경 이력 2행
- `docs/migration/_workspace/00_progress.md` — `실행 경로` 열이 추가된 표 4개(Phase 0/1/2 종료 조건 표 + 「아직 돌리지 않은 검증 게이트」 표), 총 45행

[질문 — 아래 세 축만 본다. 각 지적에 파일·라인과 재현 방법을 붙여라]

축 1. **규칙 4의 새 기준이 실제로 판정 가능한가.**
- "구조적으로 재발한다"와 "우연히 한 번 났다"를 다음 사람이 **같은 답으로** 가를 수 있는가? 가를 수 없는 반례를 실제로 만들어 보여라 — 같은 사실관계를 놓고 양쪽 결론이 다 성립하는 사례.
- "탐지 / 은폐"의 이분법이 완전한가? 하나의 장치가 **양쪽 다인** 경우, 어느 쪽도 아닌 경우, 문맥에 따라 뒤바뀌는 경우가 있는가? 있다면 규칙은 그때 무엇을 답하는가.
- 두 질문 ⑴과 ⑵가 **서로 반대 답을 낼 때**(구조적 재발인데 은폐형 장치일 때) 규칙은 우선순위를 정하는가? 정하지 않는다면 그 공백에서 무슨 일이 벌어지는가.
- 폐기된 **횟수 기준과 정면 비교**하라. 횟수 기준은 기계적이고 조작 가능했다. 새 기준은 판정 가능성을 잃고 유연성만 얻은 것인가, 아니면 실제로 더 나은가? 어느 쪽이든 근거를 대라.
- **역방향 검증**: 표의 근거 7건 각각에 새 기준 두 질문을 적용했을 때, 각 행에 이미 적힌 결론과 **같은 결론**이 나오는가? 어긋나는 행이 있으면 지목하라. 특히 5번(`mypy .` 점 디렉터리)과 7번(`.gitignore *.jso`)을 서로 대조하라.

축 2. **어휘 6종의 설계가 면죄부를 발급하는가.**
- `1회성:` 와 `결정:` 은 "돌지 않아도 되는 행"을 정당화하는 탈출구가 될 수 있는가? `00_progress.md` 에서 `충족 = 예` 인 행이 어느 어휘로 닫혀 있는지 **직접 세어** 분포를 보고하고, 그 분포가 규칙 3의 취지("도달 0을 의심한다")와 정합적인지 판정하라.
- `local:` 은 정의상 "CI 도달 0"을 뜻한다. 그런데 `local:` 로 적힌 행이 `충족 = 예` 로 닫히는 것은 허용된다. 이 조합이 근거 표 6번(품질 합격선이 CI 도달 0인데 "합격선 확정"으로 보고됨)과 무엇이 다른가?
- 어휘에 **빠진 상태**가 있는가? 실제 게이트가 가질 수 있는 상태 중 6종으로 표현되지 않아 억지로 밀어 넣게 되는 것을 찾아라.
- `ci:<잡>` 은 "배선이지 관측이 아니다"라고 문서가 명시한다. 이 구분이 실제로 유지되는가, 아니면 표를 읽는 사람이 `ci:` 를 "돌았다"로 오독하게 되는가?

축 3. **전문(SKILL.md)과 요약(CLAUDE.md)의 정합.**
- 두 문서가 **같은 규약**을 서술하는 자리를 전부 찾아 한 줄씩 대조하라. 특히 이 변경으로 신설된 `실행 경로` 규약과 **그 규약 자신의 실행 경로**를 두 문서가 각각 무엇이라고 적는지 확인하라. 값이 어긋나는 자리가 있으면 어느 쪽이 참인지 저장소를 실제로 확인해 판정하라.
- 규칙 4가 "정의를 한 곳에만 둔다"를 요구하는데, 이번 변경이 스스로 그 요구를 어긴 자리가 있는가?
- `00_progress.md` 가 어휘 정본을 SKILL.md로 가리키는 포인터 4개가 실제로 같은 자리를 가리키는가?
```

## 3. 전달한 프롬프트 전문 — 호출 B (강제 장치 축)

```
[배경] 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 중이고, `.claude/**` 의 스킬·에이전트 정의가 그 전환의 게이트(하네스)다. 이번 변경은 하네스 규칙 하나("선언한 범위와 실제 도달을 대조한다")에 **실행 강제 장치**를 신설한다. 리뷰 대상은 HEAD 커밋 하나(`--base HEAD~1`)다.

[지켜야 하는 조건 — 채점 기준]
1. 이 저장소가 일곱 번 겪은 실패의 형태는 하나다 — **선언한 범위와 실제 도달 범위가 다른데 아무도 재지 않았다.** 그중 6번은 "품질 합격선을 CI가 강제한다"고 선언했으나 차단축 3개가 전부 `-m llm` 마커이고 `pyproject.toml` 의 `addopts = "-m 'not llm'"` 이라 **CI 도달이 0**이었다.
2. 따라서 이번에 신설한 장치는 **자기 자신이 같은 실패를 반복하지 않아야** 한다. 구체적으로: (a) 그 장치가 실제로 어디서 도는가, (b) 장치를 떼면 정확히 무엇이 깨지는가(음성 대조), (c) 장치가 **탐지처럼 보이면서 아무것도 재지 않는 상태**가 아닌가.
3. 하네스 규칙 5: "닫은 뒤 음성 대조를 붙인다. 장치를 떼면 정확히 무엇이 깨지는지 확인한다. 안 깨지면 그 장치는 검증된 것이 아니다."
4. 하네스 규칙 6: "자기 자신을 검사 대상에 넣는다. 판정하는 코드일수록 그렇다."
5. `mypy --strict` 통과가 이 저장소의 커밋 전 필수 게이트다. 커밋 전 순서는 ruff → mypy → pytest 이고 CI(`.github/workflows/ci.yml` 의 `quality` 잡)가 같은 순서를 강제한다.

[대상 파일]
- `tests/test_harness_scope_reach.py` — 신설 761줄. `00_progress.md` 의 `실행 경로` 열 규약을 실행으로 강제한다고 선언한다
- `.github/workflows/ci.yml` — `quality` 잡의 mypy 명령이 `uv run mypy . .claude/skills/python-kotlin-parity/scripts` 에서 `uv run mypy . .claude` 로 바뀌었다
- `CLAUDE.md`, `README.md`, `.claude/agents/kotlin-implementer.md` — 같은 mypy 명령 3곳
- `docs/migration/_workspace/00_progress.md` — 검사 대상이 되는 표 4개
- `.claude/skills/kotlin-migration/SKILL.md` — 「어디에 적용하는가」의 `실행 경로` 어휘 정본과 "이 검사가 못 잡는 것" 문단

[질문 — 아래 세 축만 본다. 각 지적에 파일·라인과 **재현 명령**을 붙여라]

축 1. **`tests/test_harness_scope_reach.py` 가 무엇을 강제하고 무엇을 못 하는가.**
- 이 파일은 스스로 "답의 형식을 강제하지 답을 강제하지 않는다"는 취지의 한계를 적는다. 그 자백이 **정직한 한계 표기**인지, 아니면 **게이트가 아닌 것을 게이트라 부르는 것**인지 판정하라. 판정 근거를 코드에서 대라.
- 각 검사를 **속이는 최소 편집**을 찾아라. 문서를 평범하게 편집하는 것만으로(적대적 조작이 아니라) 검사를 통과하면서 규약의 취지를 위반하는 경로가 남아 있는가? 남아 있다면 정확한 편집 내용을 제시하라.
- `ci:<잡>` 검증은 잡 **이름의 실재**만 본다. 그 잡이 그 행의 게이트를 실제로 돌리는지는 보지 않는다. 이 간극으로 통과하는 행이 `00_progress.md` 에 실제로 있는가? 있으면 지목하라.
- 검사가 **대상 표를 찾지 못했는데 통과**하거나, **행이 0개인데 통과**하는 경로가 있는가? (표 제목이 바뀌거나 표가 이동했을 때)
- 파싱이 순수 함수와 저장소 실물 판정으로 나뉘어 있다. 합성 입력 음성 대조가 **실물 판정 경로를 실제로 덮는가**, 아니면 두 경로가 갈라져 합성 쪽만 검증되고 실물 쪽은 검증되지 않는가?
- 이 테스트 자신이 `mypy --strict` 와 ruff 를 통과하는가? 실제로 돌려서 확인하라.

축 2. **규칙 3의 새 장치가 근거 6번을 다시 막는가.**
- 근거 6번(품질 합격선의 CI 도달 0)과 **똑같은 일이 다시 일어난다고 가정**하라. `실행 경로` 열 + 이 테스트가 그것을 잡는가? 실제로 `00_progress.md` 에서 그 사건에 해당하는 행을 찾아 어떤 값으로 적혀 있는지 확인하고, 그 값이 검사를 통과하는지 코드로 확인하라.
- 잡지 못한다면 SKILL.md 의 「이 검사가 못 잡는 것」 문단이 그 한계를 **정확히** 기술하는가? 기술이 실제 코드 동작보다 넓거나 좁은 자리가 있는가?
- 그 문단은 한계의 **원인**을 "행이 겹쳐 있다"로 진단하고, 대응으로 "행을 갈라야 한다"를 제시하되 이번 변경에서는 하지 않았다. **이 진단이 옳은가?** 다른 원인은 없는가? 대안(검사를 넓힌다 / 행을 가른다 / 이 장치를 폐기한다)을 각각 평가하고, 어느 쪽이 이 저장소의 조건에서 옳은지 근거와 함께 답하라. 검사를 넓히면 무엇이 새로 걸리는지 실제로 세어 보라.
- 이 장치를 **통째로 떼면 정확히 무엇이 깨지는가**? 떼도 아무것도 깨지지 않는 부분이 있으면 지목하라.

축 3. **mypy 도달 수정과 자기 적용.**
- 개별 경로 열거(`.claude/skills/python-kotlin-parity/scripts`)에서 루트 명시(`.claude`)로 바꾼 것이 **과한가 부족한가**. `.claude` 아래에 향후 서드파티 Python(플러그인 캐시, 벤더 스크립트, 생성 코드)이 들어오면 무슨 일이 벌어지는가? 실제로 그럴 수 있는 경로가 지금 있는가?
- 이 변경이 mypy 도달을 실제로 넓혔음을 **직접 확인**하라(옛 명령과 새 명령의 검사 파일 수 대조). 확인에 쓴 명령을 그대로 남겨라.
- 새 명령이 실제로 통과하는가? `.claude` 아래에 지금 타입 오류가 있는가?
- mypy 명령이 CLAUDE.md·README.md·ci.yml·kotlin-implementer.md 네 곳에 복제돼 있다. 이 복제 자체가 이번 변경이 강화한 규칙("정의를 한 곳에만 둔다")과 충돌하는가? 네 곳이 지금 실제로 같은 값인지 확인하라.
- **자기 적용**: 이 커밋 자신이 규칙 1(선언 범위와 실제 도달을 실행으로 대조)·규칙 5(음성 대조)·규칙 6(자기 자신을 검사 대상에)을 지키는가? diff에 남은 근거로 판정하라. 지키지 않은 자리가 있으면 지목하라.
```

---

## 4. codex 원문 출력 (무편집)

아래 두 블록은 codex가 낸 출력 그대로다. 오탈자·영문/한글 혼용·표현·순서를 포함해 **한 글자도 고치지 않았다.**
Claude가 덧붙인 문장은 이 구획에 하나도 없다.

### 4.1 호출 A 원문 — `codex-review.sh adversarial --base HEAD~1` (exit 0)

```text
# Codex Adversarial Review

Target: branch diff against HEAD~1
Verdict: needs-attention

출하 불가: 규칙 4는 상충 사례를 단일 판정하지 못하고, 실행 경로 검사는 거짓 표기도 통과시키며, 요약은 실제 배선 상태와 정면으로 어긋난다.

Findings:
- [high] 규칙 4가 상충·제3분류를 판정하지 못해 7건 역검증에 실패한다 (.claude/skills/kotlin-migration/SKILL.md:212-213)
  ⑴은 구조적 재발이면 넓은 범위를 정당화하지만 ⑵은 은폐형이면 실측 자리만 막으라고 하며 우선순위가 없다. 재현: 7번의 `.jso` 생성 결함이 여러 출력 경로에 재사용되는 생성기라면 ⑴은 전역을, 동일한 `*.jso` ignore 장치는 은폐형이므로 ⑵은 단일 파일만을 요구한다. 같은 사실관계에서 양쪽 결론이 모두 규칙에 부합한다. 또한 1번의 Engine valve는 강제 장치, 2번의 OpenAPI 확장은 표현 장치라 탐지/은폐 어느 쪽도 아니다. raw-socket 검사를 장치로 잡으면 1번은 탐지가 되는 등 분석 대상 선택에 따라서도 답이 바뀐다. 5번 역시 mypy의 모든 점 디렉터리를 구조로 볼지 현재 Python 파일이 있는 `.claude`만 볼지 경계가 정의되지 않는다. 횟수 기준은 조작 가능했지만 기계적이었고, 새 기준은 증명 요건·대상 단위·충돌표 없이 그 판정 가능성을 잃었다.
  Recommendation: 분석 대상과 범위 단위를 먼저 고정하고, 구조적 여부의 필수 증거와 탐지·은폐·강제·표현·혼합 분류를 정의하라. 두 질문의 조합별 우선순위를 표로 명시한 뒤 7개 행 각각의 입력·두 답·최종 범위를 정본에 고정하라.
- [high] 실행 경로 어휘는 도달이 아니라 표기 형식만 검사해 거짓 종료를 허용한다 (.claude/skills/kotlin-migration/SKILL.md:235-250)
  235행은 `충족=예`의 근거가 실제로 돈 경로여야 한다고 하지만, 239·248행은 `ci:`를 관측이 아닌 잡 배선으로 정의한다. 구현도 잡 이름 존재, 추적 파일 존재, 날짜 형식, local 첫 단어 모양만 확인한다(`tests/test_harness_scope_reach.py:243-283`). 재현 결과 `충족=예`에 `ci:quality`, `1회성:README.md`, `결정:2026-08-13`, `local:TBD 예정`을 각각 넣어도 판정 위반이 0건이었다. 현재 45행 중 `예`는 18행이고 토큰은 `ci` 9, `1회성` 8, `결정` 3, `local` 1개이며, 11/18행이 비재실행 표기에 의존한다. 게다가 문서가 최근 CI 관측은 없다고 명시하므로 `ci:`만 적힌 예 행은 실제 근거가 로컬 실행인지 CI인지 나타내지 않는다. `local:`만으로도 예를 허용하므로 근거 6번의 ‘CI 도달 0인데 합격선 확정’도 그대로 재현되며, 250행이 이를 명시적으로 인정한다. CI 배선됐지만 미관측·실패·stale인 상태도 이 열 하나로 표현되지 않는다.
  Recommendation: 배선과 관측 근거를 별도 열로 분리하라. `ci:`는 해당 게이트의 정확한 step/command 배선을 검증하고, 예 판정에는 revision/run 또는 로컬 실행 증거를 요구하라. `1회성:`은 행과 연결된 섹션·digest를, `결정:`은 승인 기록과 승인자를 가리키게 하고, 품질 행은 CI 하네스와 `-m llm` 실제 판정을 별도 행으로 분리하라.
- [high] CLAUDE 요약이 실제 강제자를 ‘미배선’으로 잘못 선언한다 (CLAUDE.md:17)
  이 줄은 `tests/test_harness_scope_reach.py`가 아직 미배선이라고 적지만, 전문은 `ci:quality`로 확정한다(`SKILL.md:252`). 저장소 실물도 테스트 파일이 git 추적 중이고, marker가 없으며, `quality` 잡이 `uv run pytest`를 실행한다(`.github/workflows/ci.yml:71`). 따라서 전문 쪽이 참이다. 다음 작업자가 가장 먼저 읽는 요약이 반대 상태를 말해 활성 게이트를 무시하거나 잘못 보고하게 만들며, 동적 정의를 두 곳에 복제해 스스로 SSOT 규칙을 위반한 실제 드리프트다.
  Recommendation: ‘아직 미배선’ 문장을 제거하고 전문의 `ci:quality` 상태와 맞춰라. 이후 실행 경로 같은 변경 가능한 사실은 CLAUDE.md에 복제하지 말고 SKILL.md의 해당 항목만 가리키게 하라.

Next steps:
- 규칙 4의 조합별 결정표로 7건을 다시 판정한다.
- 실행 배선과 실행 관측을 분리하고 거짓 토큰 재현을 실패 테스트로 고정한다.
- CLAUDE.md의 미배선 문구를 제거해 정본과 일치시킨다.
```

### 4.2 호출 B 원문 — `codex-review.sh adversarial --base HEAD~1` (exit 0)

```text
# Codex Adversarial Review

Target: branch diff against HEAD~1
Verdict: needs-attention

No-ship: the change adds a useful schema linter, but it still cannot detect the exact CI-reach-zero failure it claims to address, and its real integration points lack removal tests. Verified positives: full ruff passes; strict mypy expands from 127 to 128 files and both commands pass; all 19 new tests pass; the four documented mypy commands currently match. `.claude` contains three first-party Python files and no repository-local plugin/cache/vendor/generated path, so the broader root is proportionate today, though future third-party Python there will become a mandatory strict target. Commands used `.venv/bin` because this read-only sandbox prevents uv from creating temporary files.

Findings:
- [high] `ci:<job>` accepts the exact CI-reach-zero failure (tests/test_harness_scope_reach.py:248-254)
  `_vocabulary_problem` returns success as soon as the named job exists; it never inspects that job's steps, pytest markers, or collected tests. The real quality row at `00_progress.md:66` therefore passes with `ci:quality` even though its actual transformation gate is `-m llm`, while `ci.yml:71` runs plain pytest and `pyproject.toml:73` excludes `llm`. Leaving or simplifying that cell to only `ci:quality` recreates incident 6 without a failure. The disclaimer is honest, but it means this is not the required reach gate. `SKILL.md:250` is also too narrow: the blind spot is not only “local-only”; false mixed and CI-only claims pass too. Reproduce: `rg -n '품질 합격선|addopts|run: uv run pytest' docs/migration/_workspace/00_progress.md pyproject.toml .github/workflows/ci.yml && PYTHONDONTWRITEBYTECODE=1 .venv/bin/pytest -s -p no:cacheprovider tests/test_harness_scope_reach.py::test_진행상태표의_실행_경로가_규약을_지킨다 -q` returns one passing test.
  Recommendation: Split the quality row into CI regression logic and real `-m llm` application, keep the latter unsatisfied until it has a CI lane, and bind `ci:` evidence to a canonical command/test identifier that is verified inside the named workflow job. Otherwise rename this explicitly as a syntax linter, not execution-path enforcement.
- [high] Aggregate floors let a gated condition disappear without detection (tests/test_harness_scope_reach.py:99-107)
  The checker has no canonical row identities and uses slack aggregate floors: current `(rows, yes, tokens)=(45,18,52)` versus `(40,15,45)`. Deleting the entire quality-gate row still yields `(44,17,49)` and zero violations. Likewise, `1회성:contracts/easy-doc-v1.yaml` can become unrelated tracked `1회성:README.md`, a decision can become `결정:2099-01-01`, and a heading can be renamed, all with zero violations; `local:TBD 예정` is explicitly asserted to pass at lines 702–704. A total loss of target tables is caught by the separate count test, and emptying any current 11–12-row table breaches today's aggregate floor, but `judge_tables([])` itself returns success and per-table emptiness can be hidden by padding another table. Reproduce the row-loss case: `PYTHONDONTWRITEBYTECODE=1 .venv/bin/python -c 'import runpy; from pathlib import Path; m=runpy.run_path("tests/test_harness_scope_reach.py"); s=Path("docs/migration/_workspace/00_progress.md").read_text(); s="\n".join(x for x in s.splitlines() if not x.startswith("| 품질 합격선")); t=m["select_target_tables"](m["parse_tables"](s)); q=m["read_tracked_files"](Path(".")); c=m["JudgeContext"](m["read_ci_job_names"](Path(".github/workflows/ci.yml").read_text()),lambda p:p in q); print(len(t),sum(len(x.rows) for x in t),len(m["judge_tables"](t,c)))'` prints `4 44 0`.
  Recommendation: Give each required condition a stable canonical ID and verify the expected ID set and per-table nonempty invariants. Validate one-time artifacts and decisions against row-specific evidence instead of accepting any tracked file or calendar date.
- [high] Removing the enforcement leaves no external failure (tests/test_harness_scope_reach.py:735-741)
  The synthetic negatives exercise pure parsing with `_fake_context`; they do not mutate the real progress file or workflow. The only self-presence assertion is inside the file being protected, so deleting that file deletes the assertion too. CI invokes generic `uv run pytest` and never names the required test path; ignoring the file still collects 1036/1041 tests and exits 0. The mypy expansion has the same Rule-5 gap: reverting to the old command succeeds on 127 files because no persistent probe requires the newly reached script. Reproduce: `set -o pipefail; PYTHONDONTWRITEBYTECODE=1 .venv/bin/pytest -s -p no:cacheprovider --collect-only -q --ignore=tests/test_harness_scope_reach.py | tail -n 8` exits 0, and `PYTHONDONTWRITEBYTECODE=1 .venv/bin/mypy --no-incremental --cache-dir=/dev/null . .claude/skills/python-kotlin-parity/scripts` reports success for 127 files.
  Recommendation: Make CI invoke this test path explicitly so absence fails. Add repository-path mutation tests against the real workflow/progress fixtures, and a permanent mypy reach probe or canonical quality script whose removal/reversion is mechanically rejected.
- [medium] The commit's self-reach evidence contradicts the final tree (CLAUDE.md:17)
  This commit adds a statement that the new enforcer is “still unwired,” while `SKILL.md:252` declares it wired to `ci:quality`. That skill also claims collection changed 980→987, but final HEAD collects 1060 tests and ignoring this file collects 1041: the file contributes 19 tests, not 7. The CI comment similarly records old/new mypy counts 126→127, while final commands produce 127→128. The reach exists, but the evidence was not rerun against the final diff, directly violating the new rule's self-application requirement. Reproduce: `rg -n '아직 미배선|전체 스위트|Success: 126' CLAUDE.md .claude/skills/kotlin-migration/SKILL.md .github/workflows/ci.yml`; then run `PYTHONDONTWRITEBYTECODE=1 .venv/bin/pytest -s -p no:cacheprovider --collect-only -q` and the same command with `--ignore=tests/test_harness_scope_reach.py`.
  Recommendation: Correct the contradictory status, replace handwritten counts with reproducible generated evidence from final HEAD, and add a check that the canonical mypy command and harness reach declaration cannot drift across CI and documentation.

Next steps:
- Do not ship until the quality row's real `-m llm` reach is represented separately and mechanically checked against CI.
- Add stable row identities and real-fixture negative mutations, including deletion of the quality row and removal of the test file.
- Centralize the quality/mypy command or add an equality guard; all four copies match now, but nothing enforces continued equality.
- Retain `.claude` as the current mypy root; introduce explicit exclusions only if a concrete third-party/generated subtree is later added.
```

---

## 5. 정리(가공) — Claude가 만든 목록. 원문이 아니다

> **이 구획의 성격**: 위 §4 원문을 항목화한 것뿐이다. 옳고 그름 판정·심각도 재조정·중복 병합·오탐 표시를 하지 않는다.
> **심각도 표기**: `[high]`/`[medium]` 은 **codex가 직접 붙인 라벨**을 그대로 옮긴 것이다.
> 이 프로젝트 척도(Critical ①사건 / ②장치 · Major · Minor)로의 매핑과 마감(첫 실사용 Phase) 판정은
> `codex-review` 스킬 §5에 따라 **교차 종합(`05_scope-reach_cross.md`)에서** 이뤄진다. 여기서 미리 정하지 않는다.

### 5.1 codex 지적 목록

| # | 출처 | codex 라벨 | 지적 요지 (codex 표현) | codex가 준 근거 위치 | 재현/확인 명령 |
|---|---|---|---|---|---|
| C-1 | A | `[high]` | 규칙 4가 상충·제3분류를 판정하지 못해 7건 역검증에 실패한다 | `SKILL.md:212-213` | 원문 §4.1 재현 서술 — 7번 `.jso` 사례에서 ⑴전역·⑵단일파일이 **동시에 규칙에 부합**함을 보이는 사고실험. 1번(Engine valve)·2번(OpenAPI 확장)은 탐지/은폐 어느 쪽도 아니라고 지목 |
| C-2 | A | `[high]` | 실행 경로 어휘는 도달이 아니라 **표기 형식만** 검사해 거짓 종료를 허용한다 | `SKILL.md:235-250`, `tests/test_harness_scope_reach.py:243-283` | `충족=예` 행에 `ci:quality`·`1회성:README.md`·`결정:2026-08-13`·`local:TBD 예정`을 각각 주입 → **판정 위반 0건**. codex 실측 분포: 45행 중 `예` 18행, 토큰 `ci` 9 · `1회성` 8 · `결정` 3 · `local` 1 |
| C-3 | A | `[high]` | `CLAUDE.md` 요약이 실제 강제자를 **'미배선'으로 잘못 선언**한다 (전문은 `ci:quality`) | `CLAUDE.md:17` vs `SKILL.md:252`, `.github/workflows/ci.yml:71` | `rg -n '아직 미배선' CLAUDE.md` 와 `rg -n 'ci:quality' .claude/skills/kotlin-migration/SKILL.md` 대조. codex는 **전문 쪽이 참**이라고 판정(파일 git 추적 중 · marker 없음 · `quality` 잡이 `uv run pytest` 실행) |
| C-4 | B | `[high]` | `ci:<job>` 이 **바로 그 CI-도달-0 실패를 통과시킨다** | `tests/test_harness_scope_reach.py:248-254`, `00_progress.md:66`, `ci.yml:71`, `pyproject.toml:73` | `rg -n '품질 합격선\|addopts\|run: uv run pytest' docs/migration/_workspace/00_progress.md pyproject.toml .github/workflows/ci.yml && .venv/bin/pytest -s -p no:cacheprovider tests/test_harness_scope_reach.py::test_진행상태표의_실행_경로가_규약을_지킨다 -q` → 통과 |
| C-5 | B | `[high]` | **총계 하한(aggregate floor)** 이라 게이트 행 하나가 사라져도 탐지되지 않는다 | `tests/test_harness_scope_reach.py:99-107`, 702–704 | 원문 §4.2에 실행 가능한 한 줄 재현 있음 → 품질 게이트 행 전체 삭제 후 `4 44 0` 출력(위반 0). 현재 `(45,18,52)` vs 하한 `(40,15,45)` |
| C-6 | B | `[high]` | **강제 장치를 떼도 외부에서 아무것도 깨지지 않는다** (규칙 5 음성 대조 미충족) | `tests/test_harness_scope_reach.py:735-741` | `.venv/bin/pytest --collect-only -q --ignore=tests/test_harness_scope_reach.py \| tail -n 8` → exit 0 (1036/1041 수집). mypy도 동일: 옛 명령으로 되돌려도 `127 files` 성공 |
| C-7 | B | `[medium]` | **커밋의 자기 도달 근거가 최종 트리와 어긋난다** | `CLAUDE.md:17`, `SKILL.md:252`, `ci.yml` 주석 | `rg -n '아직 미배선\|전체 스위트\|Success: 126' CLAUDE.md .claude/skills/kotlin-migration/SKILL.md .github/workflows/ci.yml` 후 `--collect-only` 2회 대조 |

### 5.2 codex가 **문제 없다고 확인**한 항목 (동의·긍정 소견 — 원문 §4.2 "Verified positives")

codex B가 실행으로 확인해 **명시적으로 통과**시킨 것들이다. 전부 지적으로 채우지 않기 위해 그대로 옮긴다.

| # | codex가 확인한 내용 | 확인 방식 |
|---|---|---|
| P-1 | `ruff` 전체 통과 | 실제 실행 |
| P-2 | `mypy --strict` 도달이 **실제로 넓어졌고** 두 명령 모두 통과 | 실행 대조 (codex 실측: **127 → 128 files**) |
| P-3 | 신설 테스트 **19건 전부 통과** | 실제 실행 |
| P-4 | mypy 명령이 문서화된 **4곳에서 현재 일치** | 4파일 대조 |
| P-5 | `.claude` 루트 확대는 **현재로선 적정**(proportionate today) — 저장소 로컬 plugin/cache/vendor/generated 경로가 없고 1st-party Python 3개뿐 | `find .claude -name '*.py'` 등 |
| P-6 | 「이 검사가 못 잡는 것」 한계 표기 자체는 **정직하다**(the disclaimer is honest) | 문서 대조 |
| P-7 | (권고) `.claude` 를 mypy 루트로 **유지**하고, 구체적 서드파티/생성 서브트리가 실제로 생길 때만 예외를 넣어라 | 원문 Next steps |

### 5.3 전제 확인 필요 — 리더 브리핑의 수치와 codex 실측이 갈리는 자리

원문을 지우지 않고 **차이만** 적는다. 어느 쪽이 참인지는 판정하지 않는다.

| 항목 | 리더 브리핑 / 변경 문서의 값 | codex 실측값 | 근거 위치 |
|---|---|---|---|
| `충족=예` 행의 어휘 분포 | `1회성:` 7 · `결정:` 2 · `local:` 1 (상시 CI 8) | `ci` 9 · `1회성` 8 · `결정` 3 · `local` 1 (예 18행 / 전체 45행) | 원문 §4.1 C-2 |
| mypy 파일 수 변화 | `ci.yml` 주석: 126 → 127 | **127 → 128** | 원문 §4.2 서두·C-7 |
| 테스트 수집 수 변화 | `SKILL.md`: 전체 스위트 980 → 987 (**+7**) | 최종 HEAD 1060 수집, 이 파일 제외 시 1041 → **이 파일 기여 19건** | 원문 §4.2 C-7 |

### 5.4 절차 기록 — `{scope}` 어간

`kotlin-migration` 스킬의 「`{scope}` 정본 (이 표가 유일한 출처)」 표에 **`scope-reach` 값은 없다.**
같은 표의 규칙은 "표에 없는 값을 쓰지 않는다. 새 대상이 생기면 이 표에 먼저 추가한다"이고,
Phase 밖 하네스 점검은 `harness`(`{phase}` = `xx`)로 규정돼 있다.
다만 같은 절의 다른 규칙이 "**어간은 리더가 정해 내려보낸다**"이므로,
이 산출물은 **리더 지정값 `05_scope-reach`를 그대로 사용**했다(교차 종합이 같은 어간으로 이 파일을 찾아야 하므로).
기존 산출물 `02_criteria-pivot` · `03_rebuild-plan` · `04_quality-gate` 도 정본 표에 없는 값이다.
이 사실만 기록하며, 표를 고칠지 어간을 바꿀지는 판정하지 않는다.

---

## 6. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| codex 호출 실패 | **없음.** A·B 모두 스크립트 종료 코드 `0`, 출력 비어 있지 않음. 재시도 불필요 |
| 출력 잘림 | **없음.** 두 출력 모두 `Findings` → `Next steps`까지 완결 |
| 헬퍼 폴백 | 사용하지 않음. plugins cache 1.0.6 헬퍼가 정상 handshake |
| `--dry-run` 사전 확인 | 수행함 (exit 6, 대상 판정 `non-empty` 확인 후 본 실행) |
| 실저장소 직접 리뷰 | **의도적으로 하지 않음** — §1.1 사유(untracked 74건 오염). 클론 경로·라인은 실저장소와 동일 |
| codex 미지적 축 | 없음. 요청한 6개 도전 지점이 모두 원문에서 다뤄짐 |
| Claude의 대체 리뷰 | **없음.** 이 문서의 §4는 전부 codex 원문이다 |

---

## 7. 다음 단계 (프로토콜)

1. 이 파일과 `05_scope-reach_migration-reviewer.md` **두 파일의 존재**를 리더가 확인한다 (게이트 2단계).
2. `migration-reviewer`를 **재호출**해 두 파일을 `codex-review` 스킬 §5 표로 대조한 `05_scope-reach_cross.md`를 만든다 (3단계).
   그 호출에서 **새 지적을 만들지 않는다.**
3. 심각도 매핑(Critical ①/② · Major · Minor)과 마감(첫 실사용 Phase)은 그 교차 종합에서 정한다.
   Phase 종료 **판정**은 리더의 몫이다.

**codex 리뷰 누락 없음.** 이 게이트의 독립 관점은 확보됐다.
