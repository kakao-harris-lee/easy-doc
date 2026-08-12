# 02 기준 전환 — codex stop-time 게이트 지적 (리더 확인)

**출처**: codex stop-time 리뷰 게이트 (세션 종료 시점 자동 실행). 지적 원문은
훅 피드백 한 줄로만 전달됐고 파일로 남지 않았다 — `~/.codex/`, `/tmp`, 저장소
어디에도 산출물이 없다. 원문: **"parity 게이트가 계약 위반과 낡은 갈림 원장을
통과시킵니다."**

**리더 확인 방법**: `compare_parity.py`를 읽어 두 주장을 각각 대조했다.
**코드를 읽어 확인한 것이고 재현 실행은 하지 않았다.** 아래 근거는 전부 정적
독해다. 실행 재현은 조치 단계에서 붙인다.

**대상 커밋**: `49ea2eb`
**판정**: 두 지적 모두 **성립한다.**

---

## S-1 (high) — parity 게이트는 계약을 읽지 않는다

`check_placeholder_scheme`(`compare_parity.py:482`)은 자리표시자의 범주를
**fixture가 스스로 넘긴 `categories` 인자**와 대조한다:

```python
categories = [str(name) for name in call.arg("categories", [])]
...
category = next((name for name in categories if body.startswith(name)), None)
```

그 `categories`는 `dump_parity_fixtures.py`가 써 넣는다. 즉 **생성기가 선언한
집합을 생성기가 만든 fixture로 검사한다.**

실측: `grep -rn "easy-doc-v1\|contracts/" .claude/skills/python-kotlin-parity/scripts/`
→ **일치 0건.** parity 하네스의 어떤 코드도 `contracts/easy-doc-v1.yaml`을 읽지
않는다.

**왜 문제인가.** 계약은 `MaskedItemResponse.category`를 `enum: ["주민등록번호",
"카드번호"]`로, 자리표시자를 `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`로 못박았다.
생성기가 언젠가 `["RRN","CARD"]`로 흘러가면 **parity 게이트는 통과하고 API는
계약을 위반한다.** 두 문서가 같은 값을 말하는지 확인하는 장치가 없고, 지금
일치하는 것은 사람이 한 번 대조했기 때문이다.

이 하네스가 반복해 맞은 실패 양상 그대로다 — **저장소 안의 파일이 저장소 자신에
대한 기준이 된다.** 이번엔 fixture가 자기 범주 집합의 기준이다.

## S-2 (high) — `reference_divergence: "expected"`가 원장 추적에서 통째로 빠진다

`compare_file`(`compare_parity.py:1255` 부근):

```python
if case.get("reference_divergence") == "expected":
    if agrees:
        result.problems.append("... 의도한 갈림이 사라졌다 ...")
    continue                      # ← 갈렸을 때는 여기로 빠진다
```

`continue`가 **`result.ledger[case_id] = observed` 앞에서** 걸린다. 따라서
"의도된 갈림" 케이스는:

- 원장에 기록되지 **않는다**
- `reference_problems()`가 호출되지 **않는다**
- 갈림의 **내용이 바뀌어도**(다른 이유로 다르게 갈려도) 아무도 모른다

잡히는 것은 **갈림이 사라진 경우 하나뿐**이다. 즉 이 필드는 "기록되지 않은
갈림은 코드 1" 규칙에 대한 **자기 선언식 면제**이고, 그 선언은 게이트가 읽는
바로 그 저장소 파일에 적힌다. 한 단어를 붙이면 불일치 보고가 사라진다.

이번 커밋이 그 필드를 실제로 편집했다는 사실이 이것을 이론이 아니라 실제
경로로 만든다 — `masking-scope-out-*` 3건에서 선언을 걷어냈다.

**부수 지적**: 원장 대조 루프는 fixture 케이스만 돈다. fixture에서 사라졌거나
`expected`로 바뀐 케이스의 **낡은 원장 항목은 다시 방문되지 않아** 조용히
남는다. `--record-reference`로 다시 쓸 때만 정리된다.

---

## 조치 순서 (지금 고치지 않는 이유)

리뷰 게이트 1단계가 **같은 커밋을 대상으로 실행 중**이다(codex-reviewer,
migration-reviewer). 지금 `compare_parity.py`를 고치면 두 리뷰가 읽는 코드가
발밑에서 바뀌어 **리뷰 결과가 무효**가 된다.

따라서 이 문서는 **3단계 교차 종합의 세 번째 입력**으로 넣는다. 두 리뷰어의
산출물과 함께 대조한 뒤 한 묶음으로 조치한다.

두 리뷰어에게 이 지적을 주입하지 않았다 — 주입하면 독립성이 사라지고, 특히
S-1·S-2는 두 리뷰어에게 이미 던진 질문("게이트가 약해졌는가", "위조·자기승인
경로가 새로 열리지 않았는가")의 사정거리 안에 있다. **독립적으로 같은 것을
찾는지가 그 자체로 정보다.**

## 미확인

- 두 결함의 **실행 재현**을 하지 않았다. S-1은 생성기의 `categories`를 영문으로
  바꿔 게이트가 통과하는지, S-2는 `expected` 케이스의 산출물을 바꿔 아무 보고가
  없는지 — 조치 전에 각각 재현 케이스를 만든다. 재현 없이 고치면 고쳤다는 것도
  증명되지 않는다.
- codex stop-time 리뷰의 **원문 전문을 확보하지 못했다.** 위 두 항목은 훅이 준
  한 줄에서 출발해 리더가 코드로 재구성한 것이라, codex가 실제로 지적한 범위와
  다를 수 있다. 더 있었다면 이 문서는 그 부분집합이다.
