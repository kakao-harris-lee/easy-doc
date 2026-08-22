# 레인 A 최종 보고 — `04_documents-c6r2` Kotlin 차단 해소 (C2~C7 완주)

> 에이전트 보고 원문 보존(리더 전사, 2026-08-23). 계획: `04_kotlin-implementer_harness-unit-laneA-plan.md`. 푸시 안 함.

## 1. 커밋 표

| SHA | 항목 | 파일 | 초록 근거 (명령 + 종료 코드) | 닿는 축 |
|---|---|---|---|---|
| `0265138` | **X-9** Kotlin 라쳇 상수 양방향 인구조사 | `tests/test_kotlin_gate_reach.py` | `uv run pytest -q tests/test_kotlin_gate_reach.py` → exit 0, 299 passed 1 skipped(새 이름 튜플이 이력에 없음 — 이 커밋이 만든다) | ③ |
| `7966ff5` | **X-6·X-1b·X-1a** 하한선 인구조사 3결함 | `PrivateHeaderFloorCensusTest.kt` + 게이트(핀 2) | `./gradlew :api:ktlintCheck :api:test --tests PrivateHeaderFloorCensusTest` → exit 0, tests=4 failures=0 / 커밋 후 게이트 exit 0, 301 passed | ③ + ② |
| `ee92455` | **X-7** 강제자 이름 지목 + 주석 예산 확보 | `DocumentController.kt`, `FrameworkErrorContractTest.kt`, 게이트 | 게이트+예산 → exit 0, 305 passed / `./gradlew :api:ktlintCheck :api:detekt` → exit 0 | ③ |
| `62b76d3` | **X-10** 하한 10행 상향 (여유 0) | 게이트 | 게이트 → exit 0, 303 passed | ③ |
| `95979f3` | **P-3** 계약 열거 접근자 하한 인구조사 | `ContractEnumerationFloors.kt`(신규), `ContractSpec.kt`, 게이트 | `./gradlew :api:ktlintCheck :api:detekt :api:test` → exit 0 / 게이트+예산 → exit 0, 306 passed | ③ + ② |

**C7 전건** (`KOTLIN_GATE_REACH_RUN_STARTED_AT=2026-08-22T16:37:56Z` 를 빌드 앞에서 박고 같은 값으로):
- `./gradlew --no-build-cache --rerun-tasks build parityHarness` → exit 0 (86 tasks executed)
- `KOTLIN_GATE_REACH_REQUIRE_REPORT=1` 요구모드 게이트 → exit 0, 304 passed, 경고 0
- `ruff check .` exit 0 · `mypy . .claude` exit 0 (145 files) · `pytest -q` exit 1 — 1 failed(장부 부트스트랩, 리더가 `01d3c48` 로 닫음) 1666 passed

종료 코드는 파이프 없이 직독. `git add` 경로 명시만. 미커밋 `.kt` 4건·`codex-reviewer.md` 미접촉. `git stash` 미사용. 변이는 저장소 밖 일회용 worktree.

## 2. 계획과 달라진 점

1. **P-3 하한 상수를 `ContractSpec.kt` 가 아니라 새 파일 `ContractEnumerationFloors.kt` 에 뒀다.** 계획대로 넣었더니 detekt `LargeClass` 가 터졌다. 문턱을 올리지 않고 파일을 나눴고 `ContractSpec` 순증은 +1줄.
2. **어휘기에 `blank_strings=False` 갈래를 더했다.** P-3 은 `"x-…"` 를 문자열에서만 세야 하는데 기존 `_blank_comments_and_strings` 는 주석과 문자열을 똑같이 비워 둘을 가를 수 없었다. 어휘기 복제 대신 기존 것에 갈래를 더하고 `_source_pair` 를 3-튜플로 넓혔다(`[0]`·`[1]` 소비자 불변).
3. **계획 §0 의 "기존 Kotlin 핀 6개" 는 실측과 다르다** — 핀 튜플 11개 / 이름 8종. 인구조사 분모를 11로 잡았다.
4. **N-3 을 계획대로(구현 6자리 추가) 하면 「처방 전 초록」이 성립하지 않는다.** 옛 HTTP 프로브가 GET 경로에서 200 을 받아 그 자리에서 빨개진다. 소유권 은닉 404 를 계약이 요구하는 자리(`GET /conversions/{conversion_id}`) 하나를 유보로 옮기는 더 날카로운 변이로 바꿨다 — X-6 이 겨눈 구멍 자체다.

## 3. 음성 대조 결과 (판정 = 겨눈 장치가 그 자리를 지목했는가)

| ID | 처방 전 | 처방 후 — 지목 |
|---|---|---|
| N-1 계약 `applies_to` 5건 삭제 | 초록 — `:api:test` 전체 BUILD SUCCESSFUL exit 0 | ✅ X-1b 정체성 케이스가 5건을 이름으로 + X-1a 분모 하한(3<8) |
| N-2 개수 유지·경로 치환 | 빨강이나 정체성 축이 아니다 — `driveSuccess` 의 `else -> error` 가 잡았을 뿐 | ✅ X-1b 정체성 불일치로 직접 지목 |
| N-3 구현된 자리를 유보로 이동 | 초록 exit 0 | ✅ X-1a 상한(2→3) + 분모(8→7) |
| N-4 유보 자리에 스텁 핸들러(404 유지) | 초록 exit 0 — 옛 프로브가 404 를 「미구현」으로 읽음 | ✅ X-6 `PUT /conversions/{conversion_id} 가 이미 매핑돼 있다` |
| N-5 `methodsOn` 이 언제나 빈 집합 | — (술어 없었음) | ✅ X-6 양의 팔 `구현된 POST /auth/signup 가 엔진 매핑에 없다` |
| N-6 DC-26·DC-27 삭제 | 초록 게이트 exit 0, 298 passed | ✅ 개수 핀 + 단언 핀 `DocumentEndpointReachTest` |
| N-7 다섯째 `MAX_TIMING_RATIO` 사본 | 초록 exit 0 — 기준선과 동일 298 passed(완전 불가시) | ✅ X-9 `TimingRatioCopyProbe.kt::MAX_TIMING_RATIO` |
| N-8 Kotlin 핀 1건 삭제(상수는 남김) | — | ✅ X-9 + 기존 이력 축 |
| N-9 `OwnershipPredicateGuardTest` 케이스 1건 삭제 | 초록 exit 0 — 삭제된 것이 소유권 우회 가드 본체 | ✅ 상향된 개수·단언 하한 |
| N-10 `privateResponseHeaderTargets()` 하한 삭제 | 처방 전 상태 자체가 하한 없음, 게이트 초록 | ✅ P-3 `ContractSpec.kt::privateResponseHeaderTargets — 본문의 MIN_ 참조: 없음` |
| N-11 하한 없는 새 무인자 `x-` 접근자 추가 | 초록 exit 0, 298 passed | ✅ P-3 `ContractSpec.kt::titlePolicyKeys` (fail-closed) |

## 4. 주석 예산 실측 (전/후)

- 옛 단일 총량 축: 129,947(착수) → 129,874(C4 후) → 129,927(최종). 순 −20, 상한 130,000 불변.
- 새 분리 축(`329a964` 이후): 제품 73,700 / 76,000 · 테스트 56,227 / 60,000. `tests/test_kotlin_comment_budget.py` exit 0, 4 passed.
- 상한 미인상. C4 에서 `FrameworkErrorContractTest` 의 이력성 KDoc(날짜·리더 판정 ID·종전 판 전문)을 지웠다 — CLAUDE.md 가 `.kt` 밖에 두라고 정한 종류이고 `@DisplayName` 이 이미 OQ-1 을 적는다.

## 5. 못 끝낸 것

`pytest -q` 1건 빨강 — 장부 부트스트랩(11건 미기재). 리더 몫 → `01d3c48` 로 닫힘.

## 6. 잔여·발견 (판정 필요분 포함)

- **X-9 잔여**: 인구조사 목록(`KOTLIN_RATCHETED_CONSTANT_NAMES`)에 없는 새 이름의 Kotlin 라쳇 상수는 자동 탐지 0. 사유 면제표 갈래는 규칙 4 ⑵로 버렸다.
- **P-3 잔여**: 열거를 읽고 래퍼 타입을 돌려주는 접근자(`ContractSpec.storedTextDomain()`)는 반환 타입 모양으로 안 걸린다. 오늘 그쪽은 `require(isNotEmpty())` 뿐.
- **X-1b 잔여**: 정체성 집합과 계약이 함께 바뀌는 편집은 리뷰 diff 가 최종 방어선.
- **새 발견 ① 게이트 비대칭 (리더 판정 필요)**: 상한 라쳇 축은 이력에 없는 새 상수를 `pytest.skip` 하는데 하한 라쳇 축은 「판정 불가」로 빨갛게 만든다. 그래서 새 하한 Kotlin 상수는 커밋 전 작업 트리에서 반드시 빨갛고 커밋 후에야 초록이다(C3·C6 에서 관측). X-9 가 「상수가 있으면 즉시 핀 요구」라 "먼저 상수만 커밋" 우회도 막힌다. 맞추려면 하한 축을 skip 으로 낮춰야 하는데 축을 무르게 하는 방향이라 고치지 않았다.
- **새 발견 ②**: 레인 A 착수 커밋(`653ddc5`)의 Kotlin 주석 총량은 130,278자로 옛 상한을 넘고 있었다 — 작업 트리의 미커밋 주석 삭제 4건이 그것을 129,947 로 눌러 가리고 있었다. 즉 그 4건은 「하중 없음」이 아니라 커밋되면 예산 초과를 은폐하고 있었다. 레인 B 의 축 분리로 해소됐지만 판정 근거로 남긴다.
