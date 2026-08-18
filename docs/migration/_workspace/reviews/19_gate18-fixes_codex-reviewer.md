# 게이트 19 · 1단계 codex 독립 리뷰 — `19_gate18-fixes`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`..._cross.md`)의 몫이다.

**어간**: `19_gate18-fixes` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 01:55:01 KST |
| 종료 시각 | 2026-08-19 02:13:31 KST |
| 소요 | **18분 30초** |
| 대상 범위 | `bbe49a1..5d58832` — 커밋 3개 중 **코드 2개**(`e2282b3` · `5d58832`), 변경 파일 4개 |
| 모드 | `adversarial` (focus text 필수 — 새 장치의 빈자리를 찾는 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base bbe49a1`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-msywl718-bciisg` |
| codex session ID | `01a015cc-44b9-7e41-a405-c850b92af8fc` |
| codex 판정 | **`needs-attention`** — "No-ship." |
| codex 실행 셸 명령 | **57건 시작 / 55건 완료 기록(전부 exit 0)** — 완료 줄이 남지 않은 2건은 사유 미기록 |
| focus text 크기 | 11,290 바이트 |
| 지적 건수 | **6건 — high 4 · medium 2 · low 0** |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs bbe49a1
codex-review: 대상 판정 = non-empty (merge-base=bbe49a1a298f, 변경 파일 4개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다.

### 1.2 실행 명령 전문

```bash
SP=<스크래치패드>/gate19
FOCUS="$(cat "$SP/focus_19.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty, 종료 코드 6)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base bbe49a1 --focus "$FOCUS" --dry-run

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base bbe49a1 --focus "$FOCUS"

# 스크립트가 헬퍼로 내보낸 실제 명령:
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base bbe49a1 '<focus text 전문 — §2>'

# 사후 회수 (재실행 아님):
node <헬퍼> status --all
node <헬퍼> result review-msywl718-bciisg
```

**잘림 없음 확인**: `result` 회수본(8,250B)과 캡처한 stdout(8,127B)을 `diff` 한 결과 차이는
**session ID 꼬리 3줄뿐**이었다. 최종 출력은 온전하다.

### 1.3 회차 고유 경로

스크래치패드에 게이트 17·18 회차 파일이 그대로 남아 있어, 옛 회차 결과를 이번 결과로 읽는 사고를
피하려고 이번 회차는 **전용 하위 디렉터리 `gate19/`** 를 만들고 파일명에도 `19` 를 붙였다.

```
gate19/focus_19.txt · dryrun19.txt · dryrun19_err.txt ·
gate19/codex19_out.txt · codex19_err.txt · codex19_exit.txt · codex19_result.txt ·
gate19/start19.txt · end19.txt
```

### 1.4 제공한 맥락 (프롬프트에 **경로·행·해시로만** 지목 — 전사하지 않았다)

리더 지시("cross 의 해당 행·충돌 X1/X4 전문·§8 을 **읽게** 하라 — 전사 금지")에 따라 내용을
옮겨 적지 않고 위치만 줬다. codex 는 실행 로그상 실제로 이 파일들을 `nl -ba … | sed -n` 으로 열었다.

| 자료 | 지목한 위치 |
|---|---|
| 게이트 18 교차 종합 | `docs/migration/_workspace/reviews/18_gate17-fixes_cross.md` — §3.1 `:91`(X1)·`:92`(X2a)·`:93`(X3)·`:94`(X4) / §3.2 `:100`(T1)·`:101`(T2)·`:102`(R3) / **충돌 X1 전문 `:128-163`** / **충돌 X4 전문 `:164-203`** / §5 ⓑ `:255-282` / **§8 `:407-489`** |
| 프로젝트 규칙 정본 | `.claude/skills/kotlin-migration/SKILL.md` "선언한 범위와 실제 도달을 대조한다" 절 (규칙 6개 + 실패 7건) |
| 리더 제약 | "새 기제 금지 — 정확 일치·표기 정직화·기존 방법 교체만" |
| 대상 코드 | `run_gate.sh`(`:23-44`·`:98-100`·`:113`·`:14-58`) · `tests/test_run_gate.py`(`:12-63`·`:67-92`·`:95-113`·`:158-191`·`:193-233`·`:258-271`·`:337-370`·`:373-392`) · `tests/test_parity_ci_gate.py`(`:597-639`·`:727`·`:747`·`:986-1058`·`:1061-`·`:1115-1256`) |
| 대조할 옛 판 | `git show bbe49a1:<세 파일>` |
| 하네스가 겨누는 실물 | `.claude/skills/python-kotlin-parity/scripts/compare_parity.py` · `.github/workflows/ci.yml` |
| 참조만(리뷰 대상 아님) | `df365a4` (원장 docs 커밋) |

**민감 데이터**: 대상은 하네스 스크립트·테스트뿐이며 사용자 문서 본문·암호문·키·개인정보를
프롬프트에 싣지 않았다. 합성 대체가 필요한 항목도 없었다.

---

## 2. 전달한 프롬프트 전문 (focus text · 11,290 바이트)

```
[배경]
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환 중이다. 이 배치에는
제품 코드(Kotlin·계약·프론트) 변경이 없다 — 바뀐 것은 **하네스**, 즉 게이트 명령 러너와 게이트
테스트다. 하네스가 무력하면 이후의 모든 "게이트 통과" 기록이 근거를 잃으므로, 여기서 물어야 할
것은 "동작하는가"가 아니라 "**동작하는 것처럼 보이면서 아무것도 재지 않는 자리가 있는가**"다.

[지켜야 하는 조건 — 채점 기준]

C1. **리더 제약(이 배치에 걸린 명시적 제약)**: "새 기제 금지 — 정확 일치·표기 정직화·기존 방법
    교체만". 즉 새 탐지 기제를 추가해 못 잡던 것을 잡으려 드는 것은 이 배치의 범위 밖이다.
    이 제약을 넘은 코드가 들어왔다면 그 자체가 지적 대상이고, 넘었다면 새로 들어온 기제 자신의
    빈자리도 함께 물어야 한다.

C2. **프로젝트 규칙 "선언한 범위와 실제 도달을 대조한다"** — 정본은
    `.claude/skills/kotlin-migration/SKILL.md` 의 같은 이름 절이다(규칙 6개 + 근거가 된 과거
    실패 7건). **읽어라.** 이 배치의 판정은 그 규칙으로 한다. 특히:
      - 범위는 근거를 넘지 않는다.
      - 장치를 먼저 분류한다(탐지형 / 은폐형 / 강제·표현형 / 범위 선언형).
      - **범위 선언형 장치는 빈 선언에서 통과하면 안 된다.**
      - 은폐형(무시 패턴·억제·면제 조항)은 넓히지 않고 탐지형으로 갈아탄다.
      - 도달 0("이 게이트가 지금 어디서 도는가")을 특히 의심한다.

C3. **게이트 18 교차 종합이 이 배치의 입력이다** —
    `docs/migration/_workspace/reviews/18_gate17-fixes_cross.md`. 다음을 **읽어라**(요약본을 여기
    옮겨 적지 않았다 — 원문을 직접 봐야 한다):
      - §3.1 차단 후보 표: X1 행 `:91` · X2a 행 `:92` · X3 행 `:93` · X4 행 `:94`
      - §3.2 표: T1 행 `:100` · T2 행 `:101` · R3 행 `:102`
      - §4 **충돌 X1 전문** `:128-163`  (두 관점의 근거가 병기돼 있다)
      - §4 **충돌 X4 전문** `:164-203`  (두 관점의 근거가 병기돼 있다)
      - §5 ⓑ `:255-282` (X3 · R18-1 · R18-2 가 같은 결함군이라는 대조)
      - §8 `:407-489` (게이트 17 10항목 종합 판정 + 조치 목록 + 마감)

C4. **표기 정직성**: 러너·테스트의 주석·docstring·커밋 메시지가 선언한 범위는 그 장치가 실제로
    도달하는 범위와 같아야 한다. 못 잡는 것은 "못 잡는다"로 적혀야 하고, "(실측)"이라 적힌 주장은
    실제로 실측된 것이어야 한다. 게이트 18 R18-1 이 바로 "(실측)" 주장의 절반이 재현되지 않은
    사례이고, 이번 배치가 그것을 고쳤다고 주장한다.

C5. 하네스 스크립트·테스트는 파이썬 3.12+, `uv run pytest` / `ruff` / `mypy . .claude` 로 검증된다.
    러너는 bash 스크립트이고 `bash -n` 까지만 검사된다(shellcheck 도달은 0으로 이월된 상태다).

[대상]

변경 범위: `bbe49a1..5d58832` (branch diff). 코드 커밋 2개 —
  · `e2282b3` — 러너 잔여를 두 종류로 정직화 · "(실측)" 오기 삭제 · `RUN_GATE_PATH` 손잡이 제거 ·
                CI 배선 탐지를 `yaml.safe_load` 로 교체 · LIMIT 테스트 15건 추가
  · `5d58832` — `EXPECTED_MAINLINE_PHRASES` 정확 일치 · `_root_helper_calls` 호출 측 완전성 ·
                문구·helper 결속을 소스 grep 에서 AST 문자열 상수로 교체
  · `df365a4` — 원장(docs) 커밋. **참조만 하고 리뷰 대상으로 세지 않는다.**

파일과 짚어야 할 자리(행 번호는 `5d58832` 기준):

1) `.claude/skills/kotlin-migration/scripts/run_gate.sh` (118행)
   - 머리 주석 ⒝ — 못 잡는 잔여를 **두 종류**(가)/(나)로 다시 적은 대목 `:23-44`
   - 마커 주석 `:98-100` · zero-work 오류 문구 `:113`
   - 계약·자기 도달 선언 `:14-58`

2) `tests/test_run_gate.py` (392행)
   - 러너 계약 대조표 docstring `:12-63`
   - `_RUNNER` `:67-69` · 프로세스 헬퍼 `_invoke` `:72-92`
   - `test_runner_exists_tracked_and_parses` `:95-113`
   - LIMIT (가) 9종 parametrize `:158-191`
   - LIMIT (나) 2종 + `BASH_ENV` 선점 `:193-233`
   - `test_subshell_command_is_work_not_zero_work` `:258-271`
   - CI 배선 탐지(새 `yaml.safe_load` 판) `:337-370`
   - 러너 밖 파이프 LIMIT `:373-392`

3) `tests/test_parity_ci_gate.py` (1320행)
   - `_MAINLINE_HELPERS` `:597-616` · `EXPECTED_MAINLINE_HELPERS` `:618`
   - `_MAINLINE_ROOTS` `:622` · `_MAINLINE_PHRASES` `:625-632`
   - **신설** `EXPECTED_MAINLINE_PHRASES` `:634-639` (+ 그 위 주석)
   - `_MAINLINE_PHRASES` 소비처 `:727` · `:747`
   - `_call_sites` `:986-1006` · `_HELPER_SUFFIXES` `:1008` ·
     **신설** `_DYNAMIC_LOOKUP_NAMES` `:1010-1014` · **신설** `_root_helper_calls` `:1017-1058`
   - **신설** `_asserted_output_phrases` `:1061-` (AST 로 `assert "…" in output` 문자열 상수만 수집)
   - 완전성 테스트 `:1115-1256` — 특히 표 크기 단언 `:1164`, 문구 개수 단언 `:1170`,
     호출 측 완전성 `:1184-1226`, AST 결속 3단언 `:1234-1256`

4) 대조할 옛 판: `git show bbe49a1:tests/test_run_gate.py`,
   `git show bbe49a1:tests/test_parity_ci_gate.py`,
   `git show bbe49a1:.claude/skills/kotlin-migration/scripts/run_gate.sh`

5) 이 하네스가 겨누는 실물: `.claude/skills/python-kotlin-parity/scripts/compare_parity.py`
   (본류 `main` · `compare_file` 과 helper 들), `.github/workflows/ci.yml`

[질문 — 축 3개]

■ 축① **음성 대조 재현**
게이트 17·18 회차에서 쓴 것과 같은 방법으로 하라 — `git show` 로 대상을 꺼내고 **메모리 변이 또는
일회용 사본**으로 돌린다. **저장소 실물을 수정하지 마라.** 레인이 다음 5건을 보고했다. 각각
재현되는가? 재현되지 않으면 무엇이 관측됐는지 그대로 적어라.

  ⒜ X1 — `tests/test_parity_ci_gate.py` 의 `_MAINLINE_PHRASES` 를 `= ()` 로 **직접 치환**
        → 보고: **1 failed** (새 `EXPECTED_MAINLINE_PHRASES` 단언이 홀로 빨개진다)
  ⒝ X2a — helper 규약 이름(`*_problem` / `*_problems` / `*_additions`)을 **비교기 밖 외부 모듈**에
        정의하고 `compare_parity.py` 의 `main` 에 import·호출로 배선 → 보고: **1 failed**
  ⒞ T1 — 표의 문구를 단언하는 `assert "…" in output` 한 줄을 **주석 처리하고 `pass` 로 대체**
        → 보고: **1 failed** (옛 판은 소스 grep 이라 주석이 남아 통과했다)
  ⒟ X4 — 추적 실물 `run_gate.sh` 의 zero-work 판정 블록을 `if false` 로 무력화
        → 보고: **3 failed**. 그리고 **같은 파손 상태에서 옛 손잡이
        `RUN_GATE_PATH=<온전한 사본>` 을 환경변수로 줘도 여전히 3 failed** (손잡이가 사라졌으므로)
  ⒠ T2 — `.github/workflows/ci.yml` 의 **블록 스칼라(`run: |`) 안**에 러너 호출을 심기
        → 보고: **1 failed**. 그리고 `- run:` 한 줄 형태로 심기 → 보고: **1 failed**

그리고 이 다섯 밖에서 묻는다: **잡아야 하는데 안 잡히는 변이가 있는가?** 특히 이번에 새로 붙은
각 단언(`:1164` · `:1170` · `:1184-1226` · `:1234-1256`, 그리고 `test_run_gate.py` 의 새 테스트들)을
**하나씩 개별적으로** 무력화했을 때 어디에서도 빨개지지 않는 자리를 찾아라.

■ 축② **리더 제약 준수와 표기 정직성**
  1. **새 기제가 들어왔는가?** (C1) 들어왔다면 무엇이고, 그 기제 자신의 빈자리는 무엇인가.
     `_root_helper_calls`·`_asserted_output_phrases`·`_DYNAMIC_LOOKUP_NAMES`·`yaml.safe_load` 판
     CI 탐지가 "기존 방법 교체"인지 "새 기제 추가"인지 판단하고 근거를 적어라.
  2. **러너 머리 주석 ⒝(`:23-44`)의 "두 종류" 서술이 실제 도달과 맞는가?** (가)·(나) 어느 쪽에도
     들어가지 않는 **세 번째 종류**가 있는가. 특히 `:41` 의 선언 —
     "러너는 협조하는 호출자를 위한 장치이지 적대적 호출자를 막는 장치가 아니다" — 가 (나) 종류를
     정직하게 덮는 **범위 선언**인지, 아니면 도달 실패를 정당화로 바꿔 앞으로 같은 결함을
     보이지 않게 만드는 **은폐형**인지 판정하라(C2 의 장치 분류).
  3. `:113` 의 zero-work 오류 문구가 이 러너의 실제 탐지 범위와 같은가.
  4. LIMIT 테스트 15건은 대표 입력마다 **rc 0 을 단언**한다(= "못 잡는다"를 고정한다). 이 단언이
     나중에 그 종류를 실제로 닫았을 때 **갱신을 강제하는가**, 아니면 개선을 막는 방향으로
     굳히는가. 그리고 15건이 (가)·(나) 두 종류를 대표하기에 **충분한 표본인가**.
  5. 레인이 **잔여 3건**을 보고했다. 코드·주석에 문서화돼 있는지, 그리고 문서화 말고 **강제자가
     있는지**(없다면 없다는 사실이 정직하게 적혀 있는지) 확인하라:
       (1) `EXPECTED_MAINLINE_PHRASES` 는 **개수만** 세므로 같은 길이의 다른 문자열 15개로
           통째 교체하면 못 잡는다
       (2) `_MAINLINE_ROOTS` **밖에** 새 본류 함수가 생기면 `_root_helper_calls` 가 못 본다
       (3) `_DYNAMIC_LOOKUP_NAMES` 가 실제로 지목한 3종보다 **넓다**
  6. 커밋 메시지 `e2282b3` · `5d58832` 의 주장 중 코드가 뒷받침하지 않는 문장이 있는가
     (특히 "실측"·"전건"·"모든" 같은 범위 어휘).

■ 축③ **게이트 18 항목 종결 판정**
C3 의 cross 원문을 읽고, **X1 · X2a · X3 · X4 · T1 · T2 각각**에 대해
**해소 / 부분 해소 / 미해소** 중 하나로 판정하고 근거(파일·행)를 적어라.
특히 게이트 18 에서 **충돌**이었던 두 항목을 따로 다뤄라 — cross `:128-163`(X1)과
`:164-203`(X4)에 두 관점의 근거가 병기돼 있다. 물을 것은 이것이다:

  · **X1** — 이 수정이 cross 에 적힌 **두 관점 각각에서** 닫히는가? 한쪽 관점에서만 닫히고
    다른 쪽 경로가 열려 있다면 그 경로를 지목하라.
  · **X4** — 손잡이 제거가 두 해석을 **모두 무효화**하는가? 그리고 손잡이를 없애면서 잃은 것이
    있는가 — `test_run_gate.py:51-56` 이 옛 판 음성 대조를 "옛 커밋 worktree 안에서 이 파일을
    돌리는 방식"으로 대체한다고 적는데, **그 대체가 실제로 가능한가**(옛 커밋의 테스트 파일이
    아니라 현재 테스트 파일을 옛 러너에 겨누는 일이 이 구조에서 성립하는가).

[보고 형식]
지적마다 심각도(high/medium/low), 파일·행, **재현 절차와 관측값**을 적어라. 근거가 전제에
의존하면 그 전제를 명시하라. **지적할 것이 없는 축은 "지적 없음"이라고 그대로 적어라** —
없는 지적을 만들어 채우지 마라. 축①의 5건은 재현 여부를 **개별로** 표로 적어라.
```

---

## 3. codex 원문 출력 — **무편집**

> 아래는 `codex19_out.txt` 전문이다. 한 글자도 고치지 않았다. Claude 의 주석·판정·표시가
> 이 구획 안에 **섞여 있지 않다**. 오탐으로 보이는 진술, 전제가 틀려 보이는 진술, 프로젝트
> 규칙과 어긋나는 권고도 그대로 남겼다.

```
# Codex Adversarial Review

Target: branch diff against bbe49a1
Verdict: needs-attention

No-ship. 음성 대조: ⒜ X1 직접 빈 튜플은 0!=15로 실패했고 표 자체를 비운 경로도 0!=10으로 실패했다. ⒝ main의 직접 external_problems 호출은 1개 단언에서 실패했다. ⒞ 주석+pass 변이는 옛 grep에는 남지만 현재 AST 결속에서 실패했다. ⒟ zero-work 블록 무력화는 RUN_GATE_PATH 유무 모두 관련 테스트 3개가 실패했다(샌드박스의 heredoc 임시파일 EPERM 1건은 별도 환경 실패). ⒠ 블록 스칼라와 `- run:` 배선 모두 탐지됐다. 그러나 추가 변이에서 import alias와 새 root의 외부 helper, 같은 길이의 가짜 문구 15개, 환경변수 간접 CI 배선이 모두 통과했다. 게이트 18 판정은 X1 해소, X2a 부분 해소, X3 부분 해소, X4 해소, T1 해소, T2 부분 해소다. X4의 현재 테스트를 옛 worktree에 복사하면 `__file__` 기준으로 옛 러너를 겨누므로 대체 절차는 성립한다. LIMIT의 rc=0 단언은 개선 시 갱신을 강제할 뿐 개선을 금지하지는 않지만, 범위의 완전성은 보장하지 않는다.

Findings:
- [high] 새 호출측 AST 탐지기는 리더 제약을 넘고도 X2a를 닫지 못한다 (tests/test_parity_ci_gate.py:1010-1058)
  `_root_helper_calls`와 `_DYNAMIC_LOOKUP_NAMES`는 bbe49a1에 없었고, 기존 탐지의 표현 교체가 아니라 이전에 못 보던 외부 helper 호출을 새로 잡기 위한 탐지 장치다. 따라서 5d58832의 “새 기제는 만들지 않았다”는 C1과 맞지 않는다. 메모리 AST 변이에서 `main -> external_mod.external_problems()`는 off-table AssertionError를 냈지만, `from external_mod import external_problems as run_external_check; main -> run_external_check()`와 `main -> post_main_stage -> external_problems()`는 완전성 테스트가 PASS했다. 반대로 helper와 무관한 `vars(object())`를 main에 추가하면 `main: vars(...)` 때문에 실패했다. 즉 흔한 alias·새 root는 false-green이고, 근거보다 넓은 동적 호출 금지는 false-positive다.
  Recommendation: 현재 배치에서는 X2a 종결 주장을 철회한다. 새 탐지를 유지하려면 리더 승인을 받은 별도 배치에서 import binding과 실제 main 호출 그래프를 추적하고, 동적 호출 거부는 helper 해석에 관여한 경우로 한정해 alias·새 root·무관한 vars 대조를 고정한다.
- [high] 러너의 ‘두 종류’ 밖에서 실제 게이트 실패가 marker+rc 0으로 사라진다 (.claude/skills/kotlin-migration/scripts/run_gate.sh:30-42)
  동일한 `bash -o pipefail -o nounset -o functrace`와 DEBUG trap으로 실행한 결과 `/usr/bin/true || uv run pytest`, `if false; then uv run pytest; else /bin/echo skipped; fi`, `false &`, `echo $(false)`가 모두 marker=true, rc=0이었다. 이들은 외부 작업이 0인 (가)도 아니고 trap/nounset을 무력화한 (나)도 아니다. 정상적인 조건 분기·백그라운드·명령 치환에서 게이트가 건너뛰거나 실패한 세 번째 구조다. “협조하는 호출자” 면책은 능동적 무력화에는 범위 선언일 수 있지만 이 비적대적 구조까지 덮지는 못한다. 또한 113행은 두 형태만 안다고 쓰지만 빈 `for`와 함수 정의도 child rc=0/marker=false여서 실제로 차단된다. X3은 표기 정정만 부분 해소됐다.
  Recommendation: 새 탐지 기제를 추가하지 않는 현재 제약에서는 ‘foreground 최종 상태만 전달하며 조건부 미실행·비동기·중첩 실패는 보지 못한다’는 세 번째 종류를 명시하고 LIMIT 대조를 추가한다. 113행 오류도 실제로 잡는 형태를 닫힌 두 항목처럼 표현하지 않도록 고친다.
- [high] 신규 완전성 단언은 제거되면 의도한 파손과 함께 조용히 통과한다 (tests/test_parity_ci_gate.py:1115-1256)
  AST로 단언을 개별 제거한 뒤 대응 변이를 적용했다. 1164행 제거+기대 행수 9, 1170행 제거+빈 문구, 1184~1226행 제거+빈 roots, 1234~1256행 제거+출력 단언 주석화가 모두 완전성 테스트 PASS였다. 저장소 전수 검색에서도 이 단언·helper를 검사하는 외부 테스트는 없었다. 즉 장치 안의 자기 단언만 존재하며, 장치를 떼었을 때 밖에서 깨져야 한다는 정본 규칙 5·6을 충족하지 못한다.
  Recommendation: 이 파일 밖의 독립 회귀에서 각 enforcement group을 제거한 사본에 대응 파손을 적용해 실패를 요구하거나, 그러한 독립 강제자가 승인될 때까지 X1/X2a/T1을 완전 종결로 기록하지 않는다.
- [high] LIMIT 범위 선언은 빈 선언에서 실패하지 않는다 (tests/test_run_gate.py:155-214)
  `_KIND_A_ENTERS_BUT_ZERO_WORK`와 `_KIND_B_CALLER_NEUTRALIZES`는 범위를 열거하는 장치지만 별도의 non-empty/정확 개수 단언이 없다. pyproject에는 `empty_parameter_set_mark = fail_at_collect`가 없고, 설치된 pytest의 기본은 빈 parameter set을 skip으로 만든다. 따라서 어느 튜플을 `()`로 바꾸면 해당 종류의 전 표본이 0개가 되어도 전체 CI는 초록일 수 있다. 독립적인 테스트 개수 강제자도 없다. 이는 C2의 “범위 선언형은 빈 선언에서 통과하면 안 된다”를 직접 위반한다.
  Recommendation: 현재 배치에서는 15건을 강제된 범위 근거로 세지 않는다. 별도 승인을 받아 외부 scope-reach 테스트가 두 튜플의 non-empty/expected identity를 고정하거나 pytest 빈 parameter set을 collect failure로 만들기 전까지 표기를 문서화-only로 낮춘다.
- [medium] EXPECTED_MAINLINE_PHRASES는 정확 문구가 아니라 개수만 고정한다 (tests/test_parity_ci_gate.py:1168-1174)
  `_MAINLINE_PHRASES`를 `bogus-0`부터 `bogus-14`까지 같은 길이의 튜플로 교체한 메모리 변이에서 완전성 테스트가 PASS했다. 두 대조군도 이 튜플만 순회하므로 실제 helper 문구를 더 이상 부정하지 않는다. 빈 튜플 경로는 0!=15로 닫혔으므로 게이트 18 X1의 두 충돌 관점은 해소됐지만, ‘파생 상수 정확 일치’라는 커밋 표현과 달리 내용 결속은 없다. 이 잔여는 커밋 메시지에만 있고 코드 주석에는 명시적인 강제자가 없다.
  Recommendation: 길이 대신 `_MAINLINE_HELPERS`의 문구와 독립적으로 명명한 추가 5문구로 만든 canonical tuple 전체와 값 equality를 단언하고, 같은 길이의 가짜 문자열 전건 교체가 실패하는 음성 대조를 둔다.
- [medium] YAML 파싱은 여전히 ‘CI 배선 0’ 전체가 아니라 literal run 값만 본다 (tests/test_run_gate.py:337-365)
  ci.yml에 `GATE_RUNNER: .claude/skills/kotlin-migration/scripts/run_gate.sh`를 env로 두고 step을 `run: bash "$GATE_RUNNER" "true"`로 추가한 메모리 YAML 변이에서 테스트가 PASS했다. 파서는 모든 job/step의 `run` 값은 읽지만 env, local composite action, reusable workflow를 해석하지 않고 `run_gate.sh` literal substring만 찾는다. 보고된 블록 스칼라와 `- run:`은 각각 실패하므로 T2의 직접 경로는 닫혔지만, 러너 주석과 테스트 이름의 포괄적인 ‘CI 배선 0’ 주장은 실제 도달보다 넓다.
  Recommendation: 현재 제약에서는 주장을 ‘ci.yml의 직접 run 값에 literal run_gate.sh 배선 0’으로 좁힌다. 전체 CI 배선을 주장하려면 별도 승인 후 env·local action·reusable workflow까지 해석하는 독립 검사를 마련한다.

Next steps:
- C1 위반인 호출측 새 탐지의 유지 여부를 먼저 리더가 결정하고, 유지 시 alias/new-root/과대탐지 음성 대조를 추가한다.
- 러너 한계에 세 번째 종류를 명시하고 X3을 부분 해소로 유지한다.
- 쓰기 가능한 환경에서 `uv run pytest` 전체를 재실행한다. 이번 환경에서는 uv cache 접근이 EPERM이었지만 `bash -n`, ruff check/format, mypy 137파일, 무-temp 표적 pytest 1건은 통과했다.
```

---

## 4. 정리(가공) — Claude 색인. **판정이 아니다**

> 이 구획은 §3 원문을 **찾아보기 쉽게 목록화한 것뿐**이다. 심각도는 codex 가 붙인 값을
> 그대로 옮겼고, 옳고 그름·중복 여부·오탐 여부는 **판정하지 않았다**. 종합은
> `migration-reviewer` 2차 호출의 몫이다.

### 4.1 지적 목록 (codex 표기 그대로 · 원문 순서)

| # | 심각도 | 제목(codex 표현) | codex 가 준 위치 |
|---|---|---|---|
| X19-1 | **high** | 새 호출측 AST 탐지기는 리더 제약을 넘고도 X2a를 닫지 못한다 | `tests/test_parity_ci_gate.py:1010-1058` |
| X19-2 | **high** | 러너의 '두 종류' 밖에서 실제 게이트 실패가 marker+rc 0으로 사라진다 | `.claude/skills/kotlin-migration/scripts/run_gate.sh:30-42` |
| X19-3 | **high** | 신규 완전성 단언은 제거되면 의도한 파손과 함께 조용히 통과한다 | `tests/test_parity_ci_gate.py:1115-1256` |
| X19-4 | **high** | LIMIT 범위 선언은 빈 선언에서 실패하지 않는다 | `tests/test_run_gate.py:155-214` |
| X19-5 | medium | EXPECTED_MAINLINE_PHRASES는 정확 문구가 아니라 개수만 고정한다 | `tests/test_parity_ci_gate.py:1168-1174` |
| X19-6 | medium | YAML 파싱은 여전히 'CI 배선 0' 전체가 아니라 literal run 값만 본다 | `tests/test_run_gate.py:337-365` |

**합계: 6건 — high 4 · medium 2 · low 0.** 판정: `needs-attention` / "No-ship."

경로·행 번호는 **codex 가 쓴 그대로 옮겼다.** 다시 세거나 보정하지 않았다.
(참고: 프롬프트가 준 행 지목과 codex 가 답에 쓴 행 지목이 일부 다르다 — 예 `:1010-1058` vs
프롬프트 `:1010-1014`+`:1017-1058`, `:155-214` vs 프롬프트 `:158-191`+`:193-233`,
`:337-365` vs 프롬프트 `:337-370`, `:30-42` vs 프롬프트 `:23-44`. **어느 쪽도 고치지 않았다.**)

### 4.2 축① 음성 대조 재현 — codex 가 보고한 결과 (원문 요지 그대로)

| 레인 보고 | codex 결과 (§3 Verdict 문단 · 원문 표현) |
|---|---|
| ⒜ X1 `_MAINLINE_PHRASES = ()` → 1 failed | "직접 빈 튜플은 **0!=15로 실패**했고 **표 자체를 비운 경로도 0!=10으로 실패**했다" |
| ⒝ X2a 외부 모듈 helper 를 `main` 에 배선 → 1 failed | "main의 **직접 external_problems 호출은 1개 단언에서 실패**했다" |
| ⒞ T1 `assert "…" in output` → 주석+`pass` → 1 failed | "주석+pass 변이는 옛 grep에는 남지만 **현재 AST 결속에서 실패**했다" |
| ⒟ X4 zero-work 무력화 → 3 failed(손잡이 없음) | "zero-work 블록 무력화는 **RUN_GATE_PATH 유무 모두 관련 테스트 3개가 실패**했다(샌드박스의 heredoc 임시파일 EPERM 1건은 별도 환경 실패)" |
| ⒠ T2 블록 스칼라 안 러너 심기 → 1 failed | "**블록 스칼라와 `- run:` 배선 모두 탐지**됐다" |

**5건 전부 재현됐다고 codex 가 보고했다.** 이 요약은 codex 문장을 옮긴 것이며 Claude 가
독립 재현하지 않았다(이 에이전트의 역할 밖 — §5 참조).

codex 가 **추가로 돌린 변이 중 "통과했다"고 보고한 것** (원문 표현):
"import alias와 새 root의 외부 helper, 같은 길이의 가짜 문구 15개, 환경변수 간접 CI 배선이
모두 통과했다" — 각각 X19-1 · X19-5 · X19-6 본문에 재현 절차가 적혀 있다.
그리고 X19-3 이 "1164행 제거+기대 행수 9, 1170행 제거+빈 문구, 1184~1226행 제거+빈 roots,
1234~1256행 제거+출력 단언 주석화가 **모두 완전성 테스트 PASS**"를 보고한다.

### 4.3 축② 리더 제약·표기 정직성 — codex 응답이 걸린 자리

| 프롬프트 질문 | codex 가 답한 곳 |
|---|---|
| ②-1 새 기제가 들어왔는가 | X19-1 — "`_root_helper_calls`와 `_DYNAMIC_LOOKUP_NAMES`는 bbe49a1에 없었고 … **새로 잡기 위한 탐지 장치다**. 따라서 5d58832의 '새 기제는 만들지 않았다'는 **C1과 맞지 않는다**" |
| ②-2 "두 종류" 서술 / "협조하는 호출자" 선언의 성격 | X19-2 — "**세 번째 구조다**" · "'협조하는 호출자' 면책은 능동적 무력화에는 범위 선언일 수 있지만 **이 비적대적 구조까지 덮지는 못한다**" |
| ②-3 `:113` 오류 문구 | X19-2 후단 — "113행은 두 형태만 안다고 쓰지만 **빈 `for`와 함수 정의도** child rc=0/marker=false여서 실제로 차단된다" |
| ②-4 LIMIT 15건의 rc 0 단언 | Verdict 문단 — "개선 시 **갱신을 강제할 뿐 개선을 금지하지는 않지만, 범위의 완전성은 보장하지 않는다**" + X19-4 |
| ②-5 잔여 3건의 문서화·강제자 | (1) → X19-5("커밋 메시지에만 있고 코드 주석에는 명시적인 강제자가 없다") / (2) → X19-1("새 root의 외부 helper … 통과") / (3) → X19-1("근거보다 넓은 동적 호출 금지는 **false-positive**") |
| ②-6 커밋 메시지의 과대 어휘 | X19-1("'새 기제는 만들지 않았다'") · X19-5("'파생 상수 정확 일치'라는 커밋 표현과 달리 내용 결속은 없다") · X19-6("포괄적인 'CI 배선 0' 주장은 실제 도달보다 넓다") |

### 4.4 축③ 게이트 18 항목 종결 — codex 판정 (원문 그대로)

> "게이트 18 판정은 **X1 해소, X2a 부분 해소, X3 부분 해소, X4 해소, T1 해소, T2 부분 해소**다."

| 항목 | codex 판정 | codex 가 덧붙인 단서 |
|---|---|---|
| X1 (게이트 18 **충돌**) | **해소** | X19-5: "빈 튜플 경로는 0!=15로 닫혔으므로 **게이트 18 X1의 두 충돌 관점은 해소됐지만**, … 내용 결속은 없다" |
| X2a | **부분 해소** | X19-1: alias·새 root 는 false-green / 동적 호출 금지는 false-positive |
| X3 | **부분 해소** | X19-2: "X3은 **표기 정정만** 부분 해소됐다" |
| X4 (게이트 18 **충돌**) | **해소** | Verdict: "X4의 현재 테스트를 옛 worktree에 복사하면 `__file__` 기준으로 옛 러너를 겨누므로 **대체 절차는 성립한다**" |
| T1 | **해소** | X19-3 이 "X1/X2a/T1을 완전 종결로 기록하지 않는다"를 권고로 덧붙인다 |
| T2 | **부분 해소** | X19-6: "T2의 **직접 경로는 닫혔지만**" 포괄 주장은 넓다 |

### 4.5 전제 확인 필요 (Claude 는 확인하지 않았다 — 표시만 남긴다)

codex 진술이 의존하는 전제 중, 이 에이전트가 **검증하지 않은 채 그대로 넘기는** 것들이다.
삭제하지 않았고 옳고 그름도 판정하지 않았다. `migration-reviewer` 가 코드로 대조할 대상이다.

1. X19-4 — "pyproject에는 `empty_parameter_set_mark = fail_at_collect`가 없고, 설치된 pytest의
   기본은 빈 parameter set을 **skip**으로 만든다". 설정 파일과 pytest 판의 실제 동작 확인 필요.
2. X19-3 — "저장소 전수 검색에서도 **이 단언·helper를 검사하는 외부 테스트는 없었다**".
   `tests/test_harness_scope_reach.py` 등이 어디까지 닿는지 확인 필요.
3. X19-2 — `/usr/bin/true || uv run pytest`, `if false; then …; else /bin/echo skipped; fi`,
   `false &`, `echo $(false)` 의 marker/rc 관측값. 러너 실물로 재현 확인 필요.
4. X19-2 후단 — "빈 `for`와 함수 정의도 child rc=0/marker=false여서 **실제로 차단된다**".
   게이트 18 cross §3.2 T6 행("함수 정의 전용 rc 2 — 사실")과 같은 대상인지 확인 필요.
5. X19-6 — env 간접 배선(`GATE_RUNNER` 환경변수) 변이가 PASS 했다는 관측. 새 CI 탐지기의
   실제 코드 경로 확인 필요.
6. Verdict 문단 — "X4의 현재 테스트를 옛 worktree에 복사하면 `__file__` 기준으로 옛 러너를
   겨눈다". `test_run_gate.py:67-69` 의 `REPO = Path(__file__).resolve().parents[1]` 해석 확인 필요.
7. 행 번호 불일치(§4.1 말미) — codex 가 답에 쓴 행 범위가 프롬프트가 준 범위와 다른 자리들.

### 4.6 §4.6 축(선언 범위 대 실제 도달)에서 codex 가 낸 지적

스킬 §4.6 은 "codex 가 이 축에서 아무것도 지적하지 않았으면 그 사실을 그대로 기록한다"를
요구한다. **이번 회차는 지적이 있었다** — X19-1·X19-2·X19-3·X19-4·X19-6 이 모두 이 축이다
(선언한 범위 ↔ 실제 도달, 빈 선언 통과, 장치를 떼도 안 깨짐, 근거보다 넓은 범위).
Claude 가 대신 만들어 채운 항목은 **없다**.

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| codex CLI 호출 | **성공 — 재시도 없음.** 1회 실행, 종료 코드 `0` |
| 출력 잘림 | **없음.** `result` 회수본과 stdout 의 차이는 session ID 꼬리 3줄뿐 |
| exit 7(리뷰 대상 0건) | 해당 없음 — 사전 판정 `non-empty`(변경 파일 4개) |
| codex 셸 명령 | 57건 시작 / 55건 완료 기록(전부 exit 0). **완료 줄이 남지 않은 2건**은 헬퍼 로그에 사유가 없다. 추측으로 보완하지 않았다 |
| codex 가 스스로 보고한 환경 제약 | 원문 Next steps 3번 — "이번 환경에서는 **uv cache 접근이 EPERM**이었지만 `bash -n`, ruff check/format, mypy 137파일, 무-temp 표적 pytest 1건은 통과했다". 그리고 축① ⒟ 의 "샌드박스의 heredoc 임시파일 EPERM 1건은 **별도 환경 실패**". **전체 `uv run pytest` 재실행은 codex 환경에서 수행되지 않았다** |
| 이 에이전트의 독립 재현 | **하지 않았다.** codex 결과의 옳고 그름 판정·재계측은 이 역할의 범위 밖이다(§4.5 에 전제 확인 대상만 표시) |
| 저장소 실물 변경 | **없다.** 커밋하지 않았고 `00_progress.md` 에 손대지 않았다 |
| privacy-gate 차단 통보 | 없음. 프롬프트에 사용자 문서 본문·암호문·키·개인정보 없음 |

---

## 6. 다음 단계 (프로토콜)

이 파일은 게이트 19 **1단계** 산출물이다. 게이트는 이것만으로 닫히지 않는다.

1. 1단계 — `migration-reviewer` 의 독립 리뷰 `19_gate18-fixes_migration-reviewer.md` 가 같은 어간으로 존재해야 한다.
2. 2단계 — 두 파일의 존재 확인.
3. 3단계 — **`migration-reviewer` 재호출**로 `19_gate18-fixes_cross.md` 작성(대조만, 새 지적 금지).

이 에이전트는 여기서 멈춘다. **codex 원본을 가공하지 않은 상태로 전달**하는 것이 역할의 전부다.
