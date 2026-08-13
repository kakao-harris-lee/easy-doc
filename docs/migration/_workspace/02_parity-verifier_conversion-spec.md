# repair-adoption 도메인 명세 (Phase 2) — 변환 오케스트레이션 요구 성질

작성: `parity-verifier` (2026-08-13)
집행 대상: `docs/migration/_workspace/03_rebuild-extraction-list.md` **§G — 변환 오케스트레이션**
수신: `kotlin-implementer` (application 변환 유스케이스 구현의 입력 명세)
fixture: `parity/fixtures/repair-adoption/repair-adoption.json` — **25 케이스·단언 75개**, 생성기 `dump_parity_fixtures.py::build_repair_adoption`
참고 Python: `app/services/conversion.py` (156줄) — **정답이 아니라 참고값이다**
요구사항 상세의 정본: `00_requirements-inventory.md` **§3.1** (CNV-01·CNV-02·CNV-04)

> **한 줄 요약.** §G가 "코드에만 있다"고 지목한 변환 판정 로직 넷(호출 상한·4대 예외·보정 채택·`missing_placeholders`)을 기계가독 fixture 25건과 인벤토리 §3.1 서술로 옮겼다. `missing_placeholders`는 **새 도메인을 만들지 않고 `repair-adoption`에 배정**했다(§4). 검출 실증 6종 중 **1종이 확장 전 fixture를 통과해** 케이스 1건을 추가로 넣었다(§5.3) — 커버리지 검토가 실제로 구멍을 하나 찾았다는 뜻이다.

---

## 0. 무엇이 바뀌었나

| | 착수 전 | 지금 |
|---|---|---|
| fixture | 미생성 (생성기에 케이스 7건) | **생성됨 · 25 케이스 · 단언 75개** |
| 덮는 요구 | 보정 채택 정책 + 호출 상한(시나리오 2건) | + **4대 예외 전건** + **호출 계측 지점** + **자리표시자 유실 보고** |
| 인벤토리 CNV-01·02·04 | 요구사항 한 줄씩, 판정식 없음 | **§3.1 (가)~(마)** 판정식·검출 조건·결과 상태 |
| `missing_placeholders` 도메인 | 미배정 (`00_progress.md` Phase 2 표가 지목) | **`repair-adoption` 배정** — 근거 §4 |
| 도메인 범위 | 이름 그대로 "보정 채택" | **변환 오케스트레이션 전체** (이름은 유지 — §4.3) |

**기존 7 케이스는 손대지 않았다.** id·description·input·assert·reference 전부 동일하고 상대 순서도 보존된다(실측 §5.1).

---

## 1. 요구사항

정본: `docs/master-plan.md` §3.3(변환 호출 계약·품질 신뢰 체계) + 계획 §2.3·§4.6 + 인벤토리 CNV-01·CNV-02·CNV-04.

> 문서 1건을 쉬운 글로 바꿀 때 **LLM 완성 요청은 최대 2건**(변환 1 + 기계 검출된 위반이 있을 때만 표적 보정 1, 루프 없음)이고, **보정은 결과를 나쁘게 만들지 않을 때만 채택**하며, **보정 실패가 변환 실패가 되지 않고**, 결과에서 사라진 자리표시자는 **막지 않고 보고**한다.

판정식·검출 조건의 서술 정본은 **인벤토리 §3.1**이다. 이 문서에 다시 적지 않는다 — 같은 규칙을 두 곳에 두면 어긋난 쪽이 실제 회귀를 덮는다.

실패 등급:

| 등급 | 실패 형태 | 결과 |
|---|---|---|
| **즉시 중단**(계획 §5 Phase 7) | 변환 1건에 LLM 호출 3회 이상 | 비용·지연 상한이 사라진다. 크레딧 원가 산정(master-plan 5장)이 무너지고 장애 시 폭주한다 |
| **차단** | 보정 실패가 변환 실패로 번짐 | 사용자는 **받을 수 있었던 결과마저** 잃는다. 크레딧은 이미 차감됐다 |
| **차단** | 절단·빈 결과를 성공으로 넘김 | 사용자는 성공 응답을 받고 본문 일부가 사라진 결과를 받는다. 조용한 누락이라 아무도 모른다 |
| **차단** | 자리표시자 유실 목록이 틀림 | 과잉 보고 → 멀쩡한 결과가 내보내기 409로 막힌다. 과소 보고 → 연락처가 빠진 문서가 그대로 나간다 |
| **차단** | 보정이 자리표시자를 잃었는데 채택 | 원문 복원이 깨진다(INV-03 대응표가 가리킬 자리가 없어진다) |

---

## 2. Kotlin이 만족해야 할 성질 (케이스 목록)

fixture가 값으로 검사한다. **방법을 지시하지 않는다** — 어떻게 만족시킬지는 `kotlin-implementer`가 정한다.

### 2.1 보정 채택 정책 8건 — 순수 판정 (`equals_derived: repair_policy`)

입력은 `{original, candidate, placeholders}`, 산출물은 `{accepted, original_issue_count, candidate_issue_count}`. 비교기가 **산출물이 스스로 보고한 건수**를 입력으로 정책을 다시 계산해 `accepted`를 대조한다. 건수 자체가 맞는지는 `style` 도메인의 질문이다 — 두 질문을 섞으면 실패했을 때 어느 쪽이 원인인지 알 수 없다.

| # | 케이스 id | 고정하는 성질 | 기존/신규 |
|---|---|---|---|
| 1 | `repair-improves` | 위반이 줄면 채택 | 기존 |
| 2 | `repair-worsens` | 위반이 늘면 기각 | 기존 |
| 3 | `repair-equal-count` | **같은 건수는 채택**(경계값) | 기존 |
| 4 | `repair-loses-placeholder` | 자리표시자를 잃으면 기각 | 기존 |
| 5 | `repair-placeholder-absent-in-original` | 1차에 없던 자리표시자는 "잃은 것"이 아니다 | 기존 |
| 6 | `repair-partial-placeholder-loss` | **하나만 잃어도** 기각(위반은 줄었더라도) | **신규** |
| 7 | `repair-keeps-placeholders-and-improves` | 자리표시자를 지키며 개선하면 채택 — **과잉 거부 가드** | **신규** |
| 8 | `repair-placeholder-reordered` | 유실 판정은 **존재 여부**이지 위치가 아니다 | **신규** |

**왜 6·7·8을 넣었나.** 기존 5건은 "잃음 vs 안 잃음"과 "건수 증가 vs 감소·동수"의 대각선만 짚어, 세 가지 잘못된 구현을 통과시켰다 — ⑴ 자리표시자 가드를 **전부-아니면-전무**로 구현(6이 막는다), ⑵ **자리표시자가 든 결과는 아예 보정하지 않음**(7이 막는다), ⑶ 위치·인덱스로 대조(8이 막는다). ⑴·⑵는 §5.2에서 스탠드인으로 실증했다.

### 2.2 변환 시나리오 15건 — 대본 있는 런타임 동작

| # | 케이스 id | 고정하는 성질 | 덮는 요구 |
|---|---|---|---|
| 9 | `repair-call-budget-clean` | 위반이 없으면 호출 1회 | CNV-01 |
| 10 | `repair-call-budget-violations` | 위반이 있어도 호출 ≤ 2회 | CNV-01 |
| 11 | `conversion-truncated-first-call-fails` | 1차 절단 → **변환 실패**, 결과 미전송, 보정으로 덮지 않음(호출 1회) | CNV-02·CNV-03 |
| 12 | `conversion-empty-first-call-fails` | 1차 빈 결과 → 변환 실패 | CNV-02 |
| 13 | `conversion-provider-error-first-call-fails` | 1차 호출 실패 → 변환 실패 | CNV-02 |
| 14 | `conversion-repair-truncated-keeps-original` | 보정 절단 → **1차 채택**, 변환 성공, `repaired=false` | CNV-02·CNV-04 |
| 15 | `conversion-repair-empty-keeps-original` | 보정 빈 결과 → 1차 채택 | CNV-02·CNV-04 |
| 16 | `conversion-repair-provider-error-keeps-original` | 보정 호출 실패 → 1차 채택. 실패한 호출도 **상한에는 셈한다** | CNV-01·CNV-04 |
| 17 | `conversion-repair-worsens-keeps-original` | 악화 → 1차 채택 + **토큰은 두 호출의 합**(200/75) | CNV-04 |
| 18 | `conversion-repair-loses-placeholder-keeps-original` | 자리표시자 유실 → 1차 채택 + 유실 목록 빈 채로 | CNV-02·CNV-04 |
| 19 | `conversion-repair-accepted` | 채택되면 보정문이 최종 결과·`repaired=true` — **과잉 거부 가드** | CNV-04 |
| 20 | `conversion-no-repair-loop` | 보정 결과에 위반이 남아도 **정확히 2회**에서 멈춘다 | CNV-01 |
| 21 | `conversion-transport-retry-not-counted` | 전송 3회·논리 호출 1회 — **분리 계측** | CNV-01 |
| 22 | `missing-placeholders-preserved` | 자리표시자를 지키면 유실 목록은 빈 배열 — **과잉 보고 가드** | CNV-02·INV-03 |
| 23 | `missing-placeholders-dropped-reported` | 지워지면 라벨을 담되 **예외로 막지 않는다**(변환은 성공) | CNV-02·INV-03 |
| 24 | `missing-placeholders-basis-is-adopted-text` | 기준 본문은 **채택된 최종 결과** | CNV-02 |
| 25 | `missing-placeholders-partial-reports-only-lost` | 둘 중 하나만 사라지면 **사라진 것만** 보고 | CNV-02·INV-03 |

11~13과 14~16이 **같은 사건 × 다른 위치**의 2×3 행렬이다. 이 비대칭이 §G가 "4대 예외"라고 부른 것의 실체이며, 한 위치만 케이스로 두면 두 위치를 같은 코드로 뭉뚱그린 구현이 절반을 통과한다.

### 2.3 방향 가드

| 방향 | 무엇을 막는가 | 어디서 |
|---|---|---|
| `over`(과잉) | 호출을 더 하는 것 | `at_most` 3건(#10·#20·#21) |
| `under`+`over` | 값이 요구와 다른 것(양방향) | `equals_field` 64건 · `equals_derived` 8건 |

**한 방향으로만 재는 실패**를 케이스 설계에서 따로 챙긴 자리 셋 — #7(보정을 아예 안 쓰는 구현), #19(보정을 항상 버리는 구현), #22(유실 목록을 늘 채우는 구현). 셋이 없으면 "안전한 쪽으로 아무것도 안 하는" 구현이 나머지를 전부 통과한다. §5.2 `always-reject-repair` 변형이 그 실증이다.

---

## 3. 하네스 계약 (Kotlin 쪽 배선 명세) — **이 절이 정본**

`kotlin-implementer`가 `parity/actual/repair-adoption/repair-adoption.json`을 만들 때의 입력·산출물 형식이다. fixture와 **같은 파일명·같은 상대 경로**여야 하고 최상위 `runtime`은 `kotlin`이다.

### 3.1 케이스 입력의 세 형태

케이스는 `input`의 모양으로 갈린다.

| 모양 | 판별 | 하네스가 할 일 |
|---|---|---|
| **정책** | `original`·`candidate`·`placeholders` | 채택 판정 함수만 부른다. provider 불필요 |
| **레거시 시나리오** | `scenario`만 있음 (2건) | 시나리오 이름대로 문서를 **하네스가 지어** 변환 1건을 돌린다 |
| **대본 시나리오** | `scenario` + `source_text` + `provider_script` | 대본대로 응답하는 fake provider로 변환 1건을 돌린다 |

레거시 2건(#9·#10)은 대본이 없다. 확장하면서 통일할 수도 있었으나 **기존 케이스 불변** 원칙을 지켰다(masking 22케이스 확장 전례). 4대 예외는 "어느 호출에서 무엇이 일어났는가"가 곧 성질이라 그 자유도를 남길 수 없어 신규 케이스만 대본을 갖는다.

### 3.2 `provider_script`

배열의 n번째 원소가 n번째 완성 요청의 결과다.

```json
{"text": "...", "truncated": false, "input_tokens": 0, "output_tokens": 0}
{"error": "provider"}
```

- `truncated: true` — provider가 "출력 상한에서 잘렸다"는 **사실**을 보고한 것이다. 실패로 만들지 재시도할지는 변환 쪽 정책이다.
- `{"error": "provider"}` — 그 호출이 응답 없이 실패한다.
- `transport_attempts_per_call`(선택, 기본 1) — provider **어댑터 안에서** 같은 완성 요청을 몇 번 전송하는지. 변환 쪽에는 보이지 않아야 한다.
- **대본이 소진된 뒤 호출을 시도하면 하네스가 실패해야 한다.** 이것이 호출 상한의 1차 방어선이다(관대한 fake provider면 게이트가 대신 잡지만, 그때는 `llm_calls` 값으로만 드러난다 — §5.2 `repair-loop` 두 변형 비교).

### 3.3 산출물 필드

```json
{
  "outcome": "ok" | "error",
  "failure_kind": null | "truncated" | "empty_result" | "provider_error",
  "llm_calls": 2,
  "transport_attempts": 2,
  "repaired": false,
  "easy_text": "..." | null,
  "missing_placeholders": [],
  "input_tokens": 200,
  "output_tokens": 75
}
```

- `llm_calls` — **완성 요청 수**다. 전송 시도가 아니다(#21).
- `transport_attempts` — 어댑터가 실제로 전송한 횟수. #21에서만 단언하지만, 두 수를 따로 들고 있는 것 자체가 CNV-01의 "분리 계측"이다.
- `easy_text` — 실패 시 **`null`을 실어 보낸다**(키를 빼지 않는다). 계약이 `ConversionResponse`에서 요구하는 "키는 항상 있고 값이 null일 수 있다"와 같은 규약이다.
- `failure_kind` — **요구 수준 이름이지 예외 클래스명이 아니다.** 계약의 `failure_code`와 다른 이름을 쓴 이유는 §6 갈림 후보 ②.
- 정책 케이스는 `{accepted, original_issue_count, candidate_issue_count}`만 낸다.

---

## 4. `missing_placeholders` 도메인 배정 판단

`00_progress.md` Phase 2 표의 「placeholder 보존 검사 포팅」 행이 *"변환 결과 전체의 `missing_placeholders` 산출은 어느 정본 도메인에도 배정돼 있지 않다. 도메인을 늘릴지 기존 도메인에 케이스를 넣을지 착수 시 판정"*이라고 남겨 둔 건이다.

### 4.1 판정 — **`repair-adoption`에 케이스로 넣는다. 도메인을 신설하지 않는다.**

근거 넷.

1. **한 번의 하네스 실행이 둘을 동시에 낸다.** 변환 1건을 돌리면 `llm_calls`·`repaired`·`easy_text`·`missing_placeholders`가 한꺼번에 나온다. 도메인을 쪼개면 같은 시나리오를 두 번 돌려 절반씩 보고하게 되고, 두 실행이 어긋나도 아무도 모른다.
2. **두 요구가 서로의 입력이다.** 케이스 #18·#24가 그 실증이다 — 유실 목록의 **기준 본문**은 보정 채택 결정의 **결과**다. 도메인을 나누면 이 상호작용을 어느 쪽 fixture에도 적을 수 없다.
3. **`export`는 다른 함수다.** `export` 도메인이 덮는 것은 내보내기 시점의 **복원**(`restore_placeholders`)이고, 이것은 변환 시점의 **유실 산출**이다. 산출 로직을 내보내기 도메인에 넣으면 `source` 필드가 거짓이 되고, 내보내기 회귀를 조사할 때 변환 케이스가 섞여 나온다.
4. **도메인 신설의 비용이 실익보다 크다.** 새 도메인은 `BUILDERS`·`.github/parity-canonical-floor.txt`·`backend-kotlin/parity-domains.txt`·Kotlin parity 테스트·progress 표를 함께 늘리고, `EXPECTED_DOMAINS`가 커져 전체 게이트가 그만큼 더 오래 미충족으로 남는다. 얻는 것은 이름의 정확성뿐인데, 그것은 아래 4.3으로 더 싸게 얻는다.

`00_progress.md`가 이 행의 관련 도메인으로 **이미 `repair-adoption`·`export` 둘을 적어 둔 것**도 같은 방향이다 — 신설은 애초에 표가 기대한 선택지가 아니었다.

### 4.2 배정한 케이스

#22·#23·#24·#25 (§2.2). 넷이 각각 다른 방향을 막는다 — 과잉 보고 / 과소 보고 / 기준 본문 / 부분 유실.

### 4.3 도메인 **이름**은 바꾸지 않는다

`repair-adoption`이라는 이름은 이제 범위보다 좁다. 그런데 이름 변경은 `.github/parity-canonical-floor.txt`의 규칙상 **삭제 + 추가**이고(그 파일 (3)항), 삭제 방향은 게이트 축소로 취급돼 리뷰가 근거를 요구한다 — 이름을 예쁘게 하려고 치를 값이 아니다. 대신 셋으로 대응한다.

- fixture 헤더 `requirement` 한 줄이 범위의 정본이다(전송 재전송·4대 예외·유실 보고까지 명시).
- 생성기 docstring에 *"이름은 `repair-adoption`이지만 범위는 변환 오케스트레이션 전체"*를 못박았다.
- 인벤토리 §3.1과 이 문서가 같은 말을 한다.

**이것을 "이름이 범위를 숨긴다"로 읽지 말 것을 권한다** — 판정 범위의 정본은 이름이 아니라 `requirement`이고, 게이트는 그것을 읽는다. 다만 다음에 도메인 키를 새로 만들 일이 생기면 그때는 `conversion`으로 나누는 편이 나을 수 있고, 그 판단은 신설 비용을 이미 치르는 시점에 하면 된다.

---

## 5. 커버리지 검토와 실측

### 5.1 확장 안전성 (masking 22케이스 확장 전례 적용)

| 확인 | 방법 | 결과 |
|---|---|---|
| 기존 7 케이스 불변 | `git show HEAD:` 생성기로 확장 전 fixture를 따로 뽑아 id 기준 대조 | **id·description·input·assert·reference 전건 동일**, 삭제 0건 |
| 상대 순서 보존 | 확장 후 fixture에서 기존 id만 추려 순서 비교 | **보존**(신규 정책 3건이 정책군 뒤·예산군 앞에 들어가 절대 인덱스만 밀렸다) |
| 재현성 | 같은 명령으로 두 번 덤프 후 diff | **`generated_at`(벽시계) 외 바이트 동일** |
| 정본 대조 | fixture에서 단언 1개를 손으로 지우고 비교기 실행 | **종료 코드 1** — ``- `conversion-transport-retry-not-counted` **정본과 다르다** — $.assert: 길이 4 != 3`` + 재생성 명령 출력 |
| 기존 스위트 무손상 | `uv run pytest` | **1061 passed, 68 skipped, 5 deselected** |
| 린트·타입 | `uv run ruff check` · `ruff format --check` · `uv run mypy . .claude` | 전부 통과 (`Success: no issues found in 129 source files`) |

fixture 지문: `sha256:bf68db12723f092b96bc3528e55fd3bb0c3bec3ac4002bb05d3a5911a7085ffc` (30,697 B).

**부수 관측 — 진행 문서 편집이 범위 가드에 걸렸다.** `00_progress.md` fixture 행의 `실행 경로` 를 두 명령으로 나눠 적었더니 `tests/test_harness_scope_reach.py::test_판정이_실제로_행을_보고_있다` 가 실패했다(`실행 경로 표기가 53개다 (기대 52)`). 가드 상수(`EXPECTED_REACH_TOKENS`)를 올리는 대신 **명령 하나로 합쳤다** — 생성기의 `--domain` 은 `action="append"` 라 `--domain masking --domain repair-adoption` 이 실제로 도는 단일 명령이고, 도메인이 늘어도 이 자리는 한 줄로 남는다. 범위 가드 상수는 리뷰가 봐야 할 자리라 부수 효과로 건드리지 않았다. (합친 명령을 실제로 돌려 masking fixture 내용이 `generated_at` 외 무변경임을 확인하고 원상 복구했다.)

### 5.2 검출 실증 — 스탠드인 7종

스탠드인은 `parity/fixtures/repair-adoption/repair-adoption.json`을 읽어 `parity/actual/`을 만드는 Python 대역이다. **대조군을 먼저 세웠다** — 요구사항대로 다시 구현한 `faithful`과 현행 `ConversionService`를 그대로 돌린 `python-verbatim`이 **25건 전건 동일**한 산출을 냈다. 즉 아래 변형이 잡히는 것은 fixture가 성질을 재기 때문이지 대역이 이상해서가 아니다.

명령은 전부 `--fixture parity/fixtures --actual <스탠드인> --only-domain repair-adoption`.

| 스탠드인 | 흉내 낸 결함 | 결과 |
|---|---|---|
| `faithful` | 요구사항대로 구현 | **종료 코드 3**(부분 검증 통과) · 성질 25건·단언 75개 충족 · 참고 갈림 0건 |
| `python-verbatim` | 현행 `ConversionService` 그대로 | **종료 코드 3** — §5.4 참고 |
| `always-reject-repair` | 보정을 절대 채택하지 않음("안전하게" 1차만 쓴다) | 코드 **1** · **8건 지목** (`repair-improves`·`repair-equal-count`·`repair-placeholder-absent-in-original`·`repair-keeps-placeholders-and-improves`·`repair-placeholder-reordered`·`conversion-repair-accepted` 등) |
| `all-or-nothing-placeholder-guard` | 자리표시자를 **전부** 잃었을 때만 기각 | 코드 **1** · 1건 (`repair-partial-placeholder-loss`) |
| `repair-failure-propagates` | 보정 실패를 1차와 같이 변환 실패로 올림 | 코드 **1** · 3건 (`conversion-repair-{truncated,empty,provider-error}-keeps-original`) |
| `transport-counted-as-calls` | 전송 시도를 호출 수로 계측 | 코드 **1** · 1건 2단언 (`llm_calls` 가 1 여야 하는데 3 / 상한 2 초과) |
| `missing-from-first-draft` | 유실 목록을 **1차 결과** 기준으로 산출 | 코드 **1** · 1건 (`missing-placeholders-basis-is-adopted-text`) — **확장 전에는 코드 3, §5.3** |
| `repair-loop` (엄격 provider) | 위반이 없어질 때까지 재보정 | 코드 **1** — 대본 소진으로 하네스가 죽어 산출물이 없다(`Kotlin 결과 파일 없음`) |
| `repair-loop` (관대 provider) | 위 + 대본 소진 시 마지막 응답 반복 | 코드 **1** · 4건 (`at_most` 상한 2 초과: 3 / `llm_calls` 가 2 여야 하는데 100 / 토큰 8040·3015) |

`repair-loop`을 두 벌로 돌린 이유: **하네스가 죽는 것과 게이트가 잡는 것은 다른 방어선**이고, 관대한 fake provider를 쓰면 첫 번째가 사라진다. 둘 다 종료 코드 1이지만 관대 쪽에서만 "어느 성질이 깨졌는지"가 나온다.

### 5.3 커버리지 검토가 실제로 찾은 구멍 (확장 중 자체 발견)

**`missing-from-first-draft` 변형이 확장 24건 전부를 통과했다(종료 코드 3).** 유실 목록을 최종 본문이 아니라 1차 결과 기준으로 산출하는 구현인데, 24건 중 어느 것도 그 둘을 **다른 값**으로 만드는 상황을 만들지 않았기 때문이다. 채택 가드가 자리표시자를 잃은 후보를 기각하므로 "채택된 결과"와 "1차 결과"의 자리표시자 집합은 대개 같다.

둘이 갈리는 유일한 형태는 **1차가 잃은 자리표시자를 보정이 되살리고 그 보정이 채택되는** 경우다. `missing-placeholders-basis-is-adopted-text`(#24)를 그 형태로 넣어 닫았고, 같은 변형이 이제 종료 코드 1로 지목된다.

기록해 두는 이유: fixture를 확장할 때 "케이스를 늘렸다"가 곧 "커버리지가 늘었다"가 아니다. **결함을 주입해 실제로 빨개지는지 보기 전까지는 늘어난 것이 케이스 수뿐일 수 있다.**

### 5.4 현행 Python 실측 (2026-08-13)

`python-verbatim`이 25건을 전건 충족했다 — **이 시점의 `app/services/conversion.py`는 위 성질 집합을 전부 만족한다.** 이것은 "Python이 정답"이라는 뜻이 아니라 "여기 적은 성질에 관해서는 현행 구현과 요구사항이 갈리지 않는다"는 뜻이고, 판정 근거는 여전히 성질이다. **인용할 때 측정 시점을 함께 적는다.**

주요 관측(참고):

| 시나리오 | 호출 | `repaired` | 최종 본문 | 유실 목록 | 토큰 |
|---|---|---|---|---|---|
| 위반 없음 | 1 | false | 1차 | `[]` | (120,45) |
| 보정 채택 | 2 | true | 보정문 | `[]` | (200,75) |
| 보정 악화 | 2 | false | 1차 | `[]` | **(200,75)** — 버린 호출도 합산 |
| 1차 절단·빈 결과·호출 실패 | 1 | — | **없음(실패)** | — | — |
| 보정 절단·빈 결과·호출 실패 | 2 | false | 1차 | `[]` | — |
| 보정 자리표시자 유실 | 2 | false | 1차 | `[]` | — |
| 자리표시자 지워짐(1차) | 1 | false | 1차 | `["[[주민등록번호1]]"]` | — |
| 둘 중 하나 지워짐 | 1 | false | 1차 | `["[[카드번호1]]"]` | — |

### 5.5 참고 갈림 원장

**기록할 갈림이 0건이라 원장을 만들지 않았다.** 참고값(`reference`)이 있는 케이스는 정책 8건뿐이고 대조군이 전건 `agree`였다. `parity/reference-ledger/repair-adoption.json`은 **실제 Kotlin 산출물이 처음 들어오는 시점**에 만든다 — 지금 만들면 Python 대역이 Python 대역과 일치한다는 기록만 남는다.

시나리오 17건에 참고값을 싣지 않은 이유: **실패 경로의 결과를 Python은 값이 아니라 예외로 낸다.** `reference`에 담으려면 사람이 손으로 인코딩해야 하고, 그러면 그것은 참고값이 아니라 **두 번째 기대값**이 된다. 대신 현행 동작 실측을 §5.4 표로 남겼다.

**한계**: 그래서 이 17건에서는 Kotlin↔Python 갈림이 원장에 자동 기록되지 않는다. 다만 관측 가능한 필드를 거의 전부 `equals_field`로 못박아 두었으므로, 갈리면 원장이 아니라 **성질 불충족(코드 1)**으로 드러난다.

---

## 6. 갈림 후보 — Python 동작이 요구사항에 못 미쳐 보이는 자리

**어느 쪽도 fixture로 단언하지 않았다.** 지금 단언하면 Kotlin에 요구사항이 아직 정하지 않은 것을 요구하게 된다. 판정을 받아야 할 자리이지 게이트가 조용히 넘길 자리가 아니다.

### ① 보정 악화 가드가 **본문 손실을 보지 않는다** — 개선 후보

판정식(인벤토리 §3.1 (다))이 보는 축은 **자리표시자 존재**와 **규칙 위반 건수** 둘뿐이다. 그래서 보정이 문서 절반을 지워도 위반 건수가 줄면 채택된다 — 실제로 위반은 **본문이 짧을수록 줄기 쉬우므로**, 이 판정식은 축약을 개선으로 오인하는 방향으로 기울어 있다.

- **왜 위험한가**: 골든셋의 `required_facts`(253개)는 팩트 보존을 요구하고(STY-03 절대 팩트축), 사용자에게는 성공 응답으로 보인다. 자리표시자만 지키면 통과하므로 마스킹 대상이 없는 문서에서는 가드가 사실상 존재하지 않는다.
- **왜 지금 단언하지 않는가**: "본문을 잃지 않았다"의 기준이 요구사항에 없다. 길이 비율? 팩트 잔존? 두 답의 비용이 크게 다르고, 잘못 고르면 정상적인 축약형 보정을 전부 기각해 보정이 죽는다(케이스 #7이 막으려는 것과 같은 방향의 실패다).
- **누가 정하나**: 리더 판정 + STY-03 팩트축과의 관계 정리. 정해지면 이 도메인에 케이스로 들어온다.
- **`known_gap` 케이스로 넣지 않은 이유**: `known_gap` 케이스도 최소 하나의 단언이 필요한데, 여기서 걸 수 있는 것은 `original_issue_count` 같은 **`style` 도메인의 질문**뿐이다. 그것을 걸면 이 도메인이 두 질문을 섞게 되어 실패했을 때 원인을 가를 수 없다. 그래서 fixture가 아니라 인벤토리 §3.1 (다) 마지막 줄과 이 절에 남겼다.

### ② 계약의 `failure_code`가 **구현을 되짚는 규칙**이다 — `contract-keeper`로 넘김

`contracts/easy-doc-v1.yaml::ConversionResponse.failure_code`는 enum 대신 규칙을 준다:

> 실패 사유 코드 = 예외 클래스명. 큐 등록 실패는 예외 클래스명이 아닌 `"EnqueueFailed"`를 쓴다.

- **문제**: 값의 정본이 계약이 아니라 **Python 클래스 이름**이다. 새로 쓰는 Kotlin이 이 계약을 지키려면 예외 클래스를 `LLMTruncatedError`·`LLMEmptyResultError`·`LLMProviderError`로 **베껴 이름 지어야** 한다. 그것은 CLAUDE.md 「하지 말 것」의 *"명세가 있는 것을 확인하겠다고 Python 코드 읽기"*와 *"Python 출력을 정답으로 삼기"* 둘 다에 걸린다. 게다가 Python을 지우면(§ Phase 8) 이 규칙은 **가리킬 대상이 없어진다**.
- **왜 계약을 고치지 않았나**: 계약 파일은 이 에이전트가 수정하지 않는다. `contract-keeper`의 몫이다.
- **fixture가 지금 하는 것**: 요구 수준 이름 `failure_kind`(`truncated`·`empty_result`·`provider_error`)로 **셋을 구분하는가**만 판정한다. 문자열이 무엇이어야 하는가는 판정하지 않는다.
- **닫는 방법**: 계약이 `failure_code`를 열거하면 fixture를 그 값으로 재생성하고 `failure_kind`를 그 이름으로 바꾼다. 인벤토리 **§9-E**에 미확정 계측기로 올려 두었다.

### ③ 보정은 **1차가 이미 잃은 자리표시자를 되살릴 의무가 없다** — 기록만

채택 가드는 "1차에 있던 것을 잃었는가"만 본다. 1차가 이미 잃었다면 보정 후보에도 없는 것이 정상으로 취급되고, 결과적으로 그 자리표시자는 유실 목록에 남는다.

요구사항 관점에서 잘못이라 보기 어렵다 — 유실은 예외가 아니라 검수 경고이고(인벤토리 §3.1 (마)), 보정 프롬프트는 지적된 위반만 고치라고 지시한다. **설계 선택으로 기록만 한다.** 다만 되살아나는 경우가 있고 그때 유실 목록이 올바르게 비어야 한다는 것은 케이스 #24가 못박는다.

---

## 7. `kotlin-implementer`에게 넘기는 것

1. **읽을 것**: 이 문서 §3(하네스 계약) → 인벤토리 §3.1(판정식) → fixture 25건. 순서를 지키면 fixture가 왜 그 모양인지 알고 읽게 된다.
2. **기대값을 손으로 옮겨 적지 마라.** Kotlin 테스트가 fixture를 읽어 돌아야 한다. 옮겨 적으면 두 구현이 아니라 두 벌의 사람 해석을 비교하게 된다.
3. **`backend-kotlin/parity-domains.txt`에 `repair-adoption`을 적는 커밋과 산출물을 만드는 커밋은 같은 커밋이어야 한다** — `parityManifestCheck`가 양방향으로 깨뜨린다.
4. **fake provider는 엄격하게 만들어라.** 대본이 소진된 뒤의 호출에서 던지지 않으면 호출 상한의 1차 방어선이 사라진다(§5.2).
5. **두 수를 따로 들어라** — 완성 요청 수와 전송 시도 수. 어댑터가 재시도를 하든 안 하든 변환 쪽 계측은 흔들리지 않아야 한다.
6. **`easy_text`를 실패 시 `null`로 실어라.** 키를 빼면 게이트가 "경로가 산출물에 없다"로 막는다(계약의 null 규약과 같은 이유다).

---

## 8. 이 명세가 판정하지 않는 것

- **규칙 위반 건수가 옳은지** — `style` 도메인의 질문이다. 이 도메인은 "같은 건수를 받았을 때 같은 결정을 내리는가"만 본다.
- **프롬프트 문면** — `prompts` 도메인.
- **후처리가 무엇을 벗기는지** — `postprocess` 도메인. 여기서는 "후처리 뒤 비면 실패"만 본다.
- **변환 결과가 읽기 쉬운지** — `tests/golden`의 몫이다. 성질은 "가려졌는가"를 말할 수 있어도 "쉬운가"를 말하지 못한다.
- **실패가 HTTP로 어떻게 나가는지** — 계약·contract test의 몫이다(CNV-03이 Phase 5에서 다시 본다).
- **그 산출물을 정말 Kotlin이 만들었는가** — CI 배선(`parityHarness`) 전까지 증명되지 않는다. 이 명세의 실증은 전부 Python 스탠드인으로 한 것이다.

---

## 부록 — 재현 명령

```bash
# fixture 생성 (재생성 시 diff가 곧 변경 목록)
uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain repair-adoption

# 부분 검증 (게이트 아님 — 종료 코드 3)
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual --only-domain repair-adoption

# Phase 2 종료 판정 — 전체 게이트 (도메인 지정 없이, 종료 코드 0만 게이트를 닫는다)
uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual
```

---

## 9. 후속 (2026-08-14) — 조건부 판정 표기(C-21)와 C-20 처분

### 9.1 C-21 — 이 도메인의 "충족"은 `style` 위에 서 있다

**사실.** 채택 정책 8건은 `equals_derived: repair_policy`로 판정하고, 그 유도는 산출물이 **스스로 보고한** `original_issue_count`·`candidate_issue_count`를 입력으로 쓴다(§2.1). 그 건수가 옳은지는 `style` 도메인의 질문인데, **`style`은 아직 선언되지 않았다**(`backend-kotlin/parity-domains.txt`에 `masking`·`repair-adoption` 둘뿐).

**따라서 이 도메인의 통과는 조건부다** — *"같은 건수를 받았을 때 같은 결정을 내린다"*까지만 참이고, 건수 자체가 틀리면 채택 결정도 함께 틀린다. 게이트는 그것을 잡지 못한다.

**왜 이 구조를 그대로 두는가.** 두 질문을 한 케이스에 섞으면 실패했을 때 어느 쪽이 원인인지 알 수 없다(§8 첫 줄과 같은 이유). 분리는 옳고, **분리했다는 사실이 기록되지 않은 것**이 결함이었다.

**표기한 곳** — fixture `requirement` 헤더에 넣었다. 케이스 설명이나 이 문서에만 적으면 게이트 산출물을 읽는 사람에게 닿지 않는다.

> **조건부 판정 주의(C-21)**: … 그 건수가 옳은지는 `style` 도메인의 질문인데 `style`은 아직 선언되지 않았다(미포팅). 따라서 이 도메인의 '충족'은 **'같은 건수를 받았을 때 같은 결정을 내린다'까지만** 참이고, 건수 자체가 틀리면 채택 결정도 함께 틀린다. `style` 선언 전까지 이 조건은 열려 있다

**닫히는 조건**: `style` 도메인이 선언되고 값 판정이 돌면 자동으로 닫힌다. 그때 이 문단과 `requirement` 문장을 함께 지운다.

### 9.2 C-20 — 레거시 시나리오 2건 대본화: **하지 않는다**

`repair-call-budget-clean`·`repair-call-budget-violations` 두 케이스는 `{"scenario": ...}`만 주고 문서 구성을 하네스에 맡긴다(§3.1). 이를 `provider_script` 형태로 통일하자는 제안(C-20, 선택)에 대한 처분이다.

**하지 않는다. 사유 둘.**

1. **기존 케이스의 `input`을 바꾸는 변경이다.** 이 fixture 계열이 확장할 때마다 지켜 온 규약("기존 케이스 id·input·expected·순서 불변")을 정면으로 어긴다. 규약을 한 번 어기면 다음 확장에서 "이번에도 이유가 있다"가 반복된다.
2. **커버리지가 늘지 않는다.** 두 케이스가 고정하는 성질(위반 없으면 호출 1회 / 있어도 2회 이하)은 대본 있는 케이스가 이미 **더 강하게** 판정한다 — `missing-placeholders-preserved`가 `llm_calls == 1`을, `conversion-repair-accepted`·`conversion-no-repair-loop`가 `llm_calls == 2`를 정확히 못박는다. 대본화는 표기를 통일할 뿐 새로 잡는 결함이 없다.

**대신 남기는 것**: 입력이 세 형태라는 사실은 §3.1 표에 이미 있다. 하네스가 그 분기를 다뤄야 한다는 것이 유일한 비용이고, 그 비용은 이미 치러졌다(`ConversionParityTest`가 돌고 있다).

**되돌릴 조건**: 레거시 2건이 실제로 애매해서 하네스가 잘못된 문서를 짓는 일이 생기면 그때 대본화한다. 그 경우 판정이 흔들린 것이므로 규약보다 우선한다.
