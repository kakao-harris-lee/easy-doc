# 게이트 26 — parity 레인 몫: fixture 원시 제어문자 표기 교정

**작성:** parity-verifier / **일자:** 2026-08-20
**입력:** `04_harness_gate26-actions.md` §3.3(경계 밖 4건 표 중 parity 몫 2건) ·
`tests/test_raw_control_chars.py`(하네스 레인이 신설한 전수 탐지 장치) ·
`02_privacy-gate_control-char-verdict.md`
**착수 시점 HEAD:** `aeca7c6` — 과제문이 적은 `0ce88b4` 가 아니다. 그 사이 리더가
`reviews/**` 의 원시 제어문자 2건을 직접 닫았다(`aeca7c6`). **기준선은 `aeca7c6` 이고,
착수 시점에 남은 위반은 이 레인 몫 2건뿐이었다**(전수 스캔 실측).

**범위:** `parity/**` · `.claude/skills/python-kotlin-parity/**`
**무접촉:** `tests/test_raw_control_chars.py`(하네스 레인 소유 — **손대지 않았다**) ·
`docs/migration/_workspace/reviews/**` · `00_progress.md` · `CLAUDE.md` ·
`backend-kotlin/**` · `parity/reference-ledger/**`(무변조 확인) ·
`.claude/skills/migration-safety-gate/**`(다른 레인 — §7 에 보고만)

**규칙 5 준수:** 음성 대조는 일회용 `git worktree`(`wt-p26`)에서 했고 실험 종료 후
`git worktree remove` 로 지웠다. 본 저장소는 변조하지 않았다. 복원은 `git checkout --`
+ **sha256 대조**(`cp`·`stash` 미사용). 게이트 명령은 전부 `run_gate.sh` 경유다.

---

## 1. 무엇이 문제였나 — 미관이 아니다

| 파일 | 문자 | 개수 |
|---|---|---|
| `parity/fixtures/export/export.json` | 원시 `0x7f` (DEL) | 21 |
| `parity/fixtures/text/text.json` | 원시 `0x7f` (DEL) | 1 |

`export.json` 은 **같은 배열의 형제들이 `\u0000`·`\u001f` 로 이스케이프돼 있는데 DEL 만
원시 바이트**였다. fixture 자신의 표기가 일관되지 않으므로 ⑴ 재생성하는 사람이 그 바이트를
재현하지 못하면 조용히 갈리고 ⑵ `grep`·민짜 `diff`·`file` 같은 도구가 파일을 잘못 분류하며
⑶ 리뷰 diff 가 그 자리를 보여주지 못한다. JSON 파서에게 `\u007f` 와 원시 DEL 은 **같은
문자**이므로 이것은 **값 보존 수정**이다.

**기제는 부주의가 아니다.** 파이썬 인코더는 C0(`0x00`~`0x1F`)만 이스케이프하고 **DEL 은
`ensure_ascii=True` 여도 원시로 흘려보낸다** — DEL 은 ASCII 라 비-ASCII 이스케이프 경로에
걸리지 않고, JSON 규격상 문자열 안에서 합법이라 필수 이스케이프 대상도 아니다. 즉 생성기가
정상 동작해도 이 자리는 반드시 원시 바이트가 된다.

---

## 2. 고친 자리 — 파일이 아니라 **생성기**

과제문의 제약대로 fixture 파일을 손편집하지 않았다. 손편집은 ⑴ 정본 대조(`provenance_problems`)에
exit 1 로 잡히고 ⑵ 다음 재생성이 원시 바이트를 되돌려 놓는다.

**`.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py`**

- `CONTROL_CODEPOINTS` 신설 — C0 전부에서 TAB·LF·CR 을 빼고 DEL 을 더한 집합.
  `tests/test_raw_control_chars.py` 의 `CONTROL_BYTES` 와 **같은 집합**이다(그쪽이 탐지,
  여기가 발생 차단).
- `dump_json(payload)` 신설 — 이 하네스가 파일에 쓰는 JSON 의 **유일한 직렬화 경로**.
  인코더 출력에 치환표를 걸고, **끝에서 결과를 되짚어** 원시 제어문자가 남으면
  `RuntimeError` 로 죽는다(치환표가 조용히 좁아지는 경로 차단 — §4 N4 로 실측).
- `dump()` 의 `write_text(json.dumps(...))` → `write_text(dump_json(payload))`.

**왜 출력 전체에 치환을 걸어도 안전한가:** JSON 구조 문자(`{`·`,`·`"`·들여쓰기·구조상의
개행)는 전부 이 집합 밖이고, 문자열 안의 제어문자는 이 시점에 이미 인코더가 이스케이프했거나
(C0) 원시로 남아 있다(DEL). 바뀌는 것은 **문자열 리터럴 안의 원시 제어문자**뿐이다.
TAB·LF·CR 을 집합에서 빼는 것이 결정적이다 — 들여쓰기가 만드는 구조상의 개행을
이스케이프하면 파일이 깨진다.

**`compare_parity.py`** — `write_ledger` 도 같은 `dump_json` 을 쓰게 배선했다. 지금 원장은
경로·해시·`declared` 만 담아 제어문자가 들어올 자리가 없지만, `note`·`domain` 이 그것을 들면
fixture 와 똑같이 원시 바이트가 된다. **직렬화를 두 벌로 적으면 한쪽만 고쳐지고, 조용해지는
쪽은 늘 안 고쳐진 쪽이다.**

그 뒤 **재생성**했다(8 도메인 전부).

---

## 3. 반드시 보일 것 — 실측

### 3.1 값 무변경 증명 (요구 ①)

수정 전 파일을 스크래치에 보관해 두고, 재생성 뒤 **파싱해서** 비교했다.

| 도메인 | 값 동일(`generated_at` 제외 deep-equal) | 케이스 수 | id·순서 | 단언 수 | 원시 제어문자 전→후 |
|---|---|---|---|---|---|
| export | **예** | 12 = 12 | 동일 | 159 = 159 | 21 → 0 |
| masking | **예** | 85 = 85 | 동일 | 308 = 308 | 0 → 0 |
| postprocess | **예** | 8 = 8 | 동일 | 12 = 12 | 0 → 0 |
| prompts | **예** | 4 = 4 | 동일 | 18 = 18 | 0 → 0 |
| repair-adoption | **예** | 25 = 25 | 동일 | 75 = 75 | 0 → 0 |
| style | **예** | 10 = 10 | 동일 | 40 = 40 | 0 → 0 |
| style-tables | **예** | 1 = 1 | 동일 | 10 = 10 | 0 → 0 |
| text | **예** | 8 = 8 | 동일 | 8 = 8 | 1 → 0 |

`expected`·`input`·`assert`·`reference` 를 포함한 문서 전체의 deep-equal 이며,
`id` 목록과 그 **순서**를 따로 한 번 더 셌다. **바이트는 달라지고 값은 같다.**

### 3.2 재현성 (요구 ②)

`--out` 을 스크래치로 돌려 **두 번** 뽑았다.

```
r1 exit=0 / r2 exit=0
8 도메인 전부: 바이트 동일 = True (generated_at 제외 동일 = True)
저장소 커밋 대상 판 == 재실행 산출물 (generated_at 제외): 일치
```

두 실행이 같은 초에 끝나 `generated_at` 까지 같았으므로 **민짜 바이트도 동일**했다.
`generated_at` 을 마스킹한 대조도 함께 냈다(그 필드가 유일한 비결정 자리다).

### 3.3 정본 대조 통과 + 손편집이 여전히 exit 1 인가 (요구 ③)

**전체 게이트 (파이프 없음, 종료 코드 직접 확인):**

```
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual
→ exit 0
마지막 줄: 요구 성질 충족: 도메인 8/8 / 성질 판정 153건(단언 630개) / 판정 보류 0건
          / 참고 갈림 37건 / 미검증 0건 / 불충족 0건 / 도메인 누락 0개 / 파일 8개
```

정본 대조(`provenance_problems`)는 게이트 안에서 돈다 — 실패했다면 위 줄이 나오지 않는다.

**손편집 탐지가 약해지지 않았음** — 본 저장소를 건드리지 않고 스크래치의 fixture 사본을
변조해 같은 게이트에 넣었다.

| 대조 | 변조 | 결과 |
|---|---|---|
| T1 | `export-filename-hangul` 의 `assert` 1개 삭제(성질 검사 회피 시도) | **exit 1** — `$.assert: 길이 21 != 20` |
| T2 | `text-drops-del` 의 `input.text` 를 손으로 바꿈(값 위조) | **exit 1** — `$.input.text: 기대 … / 실제 …` |
| T3 | **표기만** 되돌림(`\u007f` → 원시 DEL, 값 동일) | **exit 0** |

**T3 을 숨기지 않고 적는다.** 정본 대조는 파싱한 **값**을 본다 — 그래서 표기만 바꾼 편집은
잡지 못하고, **잡지 않는 것이 맞다**(그것이 이 조치가 값 보존임의 다른 얼굴이다).
표기 축을 지키는 것은 `tests/test_raw_control_chars.py` 이고, 그 축은 §4 N3 이 실측했다.
**두 장치가 서로 다른 축을 덮고, 어느 쪽도 상대의 구멍을 메운다고 주장하지 않는다.**

### 3.4 다른 도메인 무손상 (요구 ④)

재생성 전후를 줄 단위로 diff 했다.

| 도메인 | 총 차이 줄 | `generated_at` 제외 |
|---|---|---|
| export | 44 | **42** (= `"\u007f",` 치환 21쌍) |
| text | 4 | **2** (= 치환 1쌍) |
| masking · postprocess · prompts · repair-adoption · style · style-tables | 각 2 | **각 0** |

제어문자가 없는 6 도메인은 `generated_at` 한 줄 외에 **한 글자도 바뀌지 않았다.**

### 3.5 음성 대조 (요구 ⑤ + 추가 1건)

일회용 워크트리 `wt-p26`(HEAD `aeca7c6` + 이 레인의 미커밋 패치)에서 했다.

| 대조 | 변조 | 결과 |
|---|---|---|
| 0 | 수정판 그대로 | `tests/test_raw_control_chars.py` **3 passed** |
| **N3** | 생성기의 escape 를 되돌림(`dump_json` 을 민짜 `json.dumps` 로) + 재생성 | **1 failed** — `parity/fixtures/export/export.json 21개 [0x7f]` · `parity/fixtures/text/text.json 1개 [0x7f]` |
| **N4** | 치환표에서 DEL 만 뺌(표가 조용히 좁아지는 형태) | **재생성 exit 1** — `RuntimeError: 직렬화 결과에 원시 제어문자가 남았다 (0x7f)`. 어긋난 파일이 **디스크에 쓰이기 전에** 죽는다 |
| 복원 | `git checkout -- .` → 패치 재적용 → sha256 대조 | 4개 파일 전부 **본 저장소와 일치**, 잔여 0 |

N3 이 재현한 위반 목록은 착수 시점의 실측과 **글자 하나까지 같다**. 즉 이 조치가
그 두 건을 닫은 원인이며, 되돌리면 정확히 그만큼 되살아난다.

### 3.6 검사 실측 (요구 ⑥) — 전부 `run_gate.sh` 경유

```
uv run pytest tests/test_raw_control_chars.py   → 3 passed                    exit 0
uv run pytest                                    → 1385 passed · 68 skipped
                                                   · 5 deselected · 5 xfailed  exit 0
uv run ruff check .                              → All checks passed!          exit 0
uv run ruff format --check .claude tests         → 88 files already formatted  exit 0
uv run mypy . .claude                            → Success: 139 source files   exit 0
전체 parity 게이트 (파이프 없음)                  → 요구 성질 충족: 8/8         exit 0
전수 원시 제어문자 스캔 (720개 텍스트 추적 파일)   → 위반 0개
```

착수 시점의 `uv run pytest` 는 `1384 passed · 1 failed` 였다(`04_harness_gate26-actions.md`
§3.6 이 예고한 상태). **이 조치로 그 1 failed 가 닫혔고 스위트 전체가 초록이다.**

---

## 4. 원장 무변조 확인

원장 직렬화 경로도 갈아 끼웠으므로 회귀가 없는지 실측했다 — 스크래치 원장 디렉터리에
`--record-reference` 를 돌려 새 경로로 다시 쓰게 하고, 커밋된 원장과 대조했다.

```
masking · postprocess · prompts · repair-adoption · style · style-tables · text
  → recorded_at 제외 **바이트 동일**
export → 커밋된 원장 없음(전 12건 `agree` 라 원장이 필요 없다 — 게이트도 그렇게 판정한다)
본 저장소 parity/reference-ledger/** : git status 변경 0건
```

**이 실행은 판정이 아니다** — `--record-reference` 가 붙었고 종료 코드 4로 끝났다.
게이트 근거로 인용하지 않으며, §3.3 의 플래그 없는 실행이 판정이다.

---

## 5. 자기 정정 — **이 회차가 같은 결함을 두 번 저질렀다 (열한·열두 번째 사례)**

숨기지 않고 적는다. §2 의 주석을 쓰면서 *"형제들이 (이스케이프 표기) 로 돼 있는데"* 라고
설명하는 자리에 **이스케이프 대신 그 글자를 그대로 넣었다** — 생성기 소스에 원시 `0x00`·
`0x1f`·`0x7f` 세 개가 들어갔다.

| 항목 | 값 |
|---|---|
| 발견 방법 | 편집 직후 자기 바이트 스캔(이 조치의 판정 집합을 그대로 씀) |
| 상태 | 142,543바이트 · 원시 제어문자 3개(오프셋 5225·5230·5836) |
| 정정 | 바이트 치환(`Path.write_bytes`) → 142,558바이트, sha256 `4620e1ab…` → `2145158e…`, 잔여 0 |
| 정정 방식 | 그 글자를 **타이핑하지 않는** 경로로만 — 바이트 값에서 표기를 계산해 치환했다 |

**그리고 이 문서를 쓰면서 또 저질렀다(열두 번째).** §1·§3.3·§3.4 에서 *"형제들이 (표기) 로
돼 있는데"* · *"파서에게 (표기) 와 원시 DEL 은 같은 문자"* 를 적는 자리에 다시 그 글자가
들어갔다 — 초판 14,604바이트에 원시 `0x00`·`0x1f`·`0x7f`×3, 총 5개(오프셋 1658·1663·
2035·7189·7896). 같은 방식으로 정정했다(14,629바이트, sha256 `775ef505…` → `a79d10a3…`,
잔여 0).

**두 번 다 「이스케이프를 쓰라」고 적는 문장 자신이었다.** 앞선 열 사례와 형태가 같고,
이번 둘은 **원시 제어문자를 없애는 작업 중에** 났다. 손으로 지키는 규율로는 닫히지 않는다는
것이 이 열두 건의 결론이며, 그래서 판정은 매번 **전 바이트 스캔**으로만 했다.

**이것이 `tests/test_raw_control_chars.py` 의 논거를 다시 확인한다.** 하네스 레인이 센
열 번째에 이어 **열한 번째**이고, 다섯 번 모두 **「제어문자에 관해 쓰는 도중」** 났다.
이번에는 **원시 제어문자를 없애는 코드를 쓰는 도중에** 났다는 점에서 가장 선명한 사례다.

---

## 6. 미실행으로 남긴 것 (돌리지 않은 것을 돌린 것처럼 적지 않는다)

- **Kotlin 하네스 재실행(`./gradlew`)** — **미실행.** §3.3 의 게이트는 작업 트리에 이미
  있던 `parity/actual/**` 를 읽었다. fixture 의 **값**이 바뀌지 않았음을 §3.1 이 증명하므로
  Kotlin 산출물을 다시 만들 이유가 없고, `backend-kotlin/**` 는 다른 레인이 동시 작업 중이라
  건드리지 않았다.
- **표준 한계** — 산출물을 Kotlin 이 만들었다는 보장은 CI 배선 전까지 없다. `parity/actual`
  최상위 `runtime` 선언은 손으로 적을 수 있는 문자열이다.
- **`tests/golden`** — 이 변경은 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았으므로
  대상이 아니다. `uv run pytest` 전체에 포함된 범위까지가 실행분이다.

---

## 7. 리더에게 남기는 것

| # | 항목 | 성격 |
|---|---|---|
| 1 | **같은 기제의 잠복 자리 하나 — 내 경계 밖** | **보고만.** `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py:364` 가 고친 것과 **똑같은** `json.dumps(ensure_ascii=False)` 로 **추적 파일**을 쓴다 (`backend-kotlin/core/src/test/resources/kr/easydoc/core/easyread/python-{prompt,style-rules}-snapshot.json`). **지금은 제어문자가 0개라 초록**이지만, 스냅샷 대상에 제어문자가 한 글자 들어오는 순간 오늘의 fixture 와 같은 상태가 된다. 그 스크립트는 privacy-gate 레인 소유라 손대지 않았다 — `dump_json` 과 같은 조작 한 줄이면 닫힌다 |
| 2 | **`parity/actual/**` 는 `.gitignore:36` 이 덮는다** | **정보.** 그래서 전수 탐지 장치의 분모(추적 파일) 밖이다. 오늘 실측으로 그 8개 파일에 원시 제어문자는 0개이고 애초에 DEL 이 출력에 살아남지 않는다(정규화가 지운다). 추적으로 바뀌는 날 이 자리를 다시 봐야 한다 |
| 3 | **정본 대조는 표기 축을 잡지 않는다** (§3.3 T3) | **설계.** 값 축은 `provenance_problems`, 표기 축은 `tests/test_raw_control_chars.py` 로 나뉜다. 어느 쪽도 상대를 대신하지 않으므로 **둘 다 CI 에 배선돼 있어야** 이 자리가 닫힌다 |
| 4 | `00_progress.md` 반영 | **이 레인이 하지 않았다** — 무접촉 지시를 지켰다 |
