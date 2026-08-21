# C6 착수 전 조치 — 계획

**대상**: G-β 가 「C6 착수 전」으로 배정한 것. 정본은
`reviews/04_documents-c4c5_cross.md` §10·§11.2 와 `reviews/04_security_privacy-gate.md` 「회차 2」 §8,
마감 배정은 `00_progress.md` L-㉗ 이다. **이 레인은 그 셋을 고치지 않는다.**

**기준 리비전**: `1fb5200` (`feat/kotlin-migration-harness`, 원격 반영됨).

---

## 1. 리서치 — 표준 구현을 먼저 찾았다 (`CLAUDE.md` 「구현 전 리서치·계획」)

| 필요한 것 | 표준·기구현 후보 | 채택 | 사유 |
|---|---|---|---|
| 응답 **원시 바이트** 비교 | `java.net.http.HttpResponse.BodyHandlers.ofByteArray()` (JDK 표준) | **채택** | 직접 구현 불필요. 세 자리가 이미 `HttpClient` 를 쓴다 |
| 헤더 이름 집합 | `HttpResponse.headers().map().keys` (JDK) — 세 파일에 **같은 코드가 세 벌** | **한 벌로 합친다** (`support/OwnershipConcealment.kt`) | `VARIABLE_HEADERS` 가 파일마다 따로 선언돼 있어 갈릴 수 있다(실측: `DocumentEndpointReachTest` 만 `content-length` 를 더 뺀다) |
| 「어느 오퍼레이션이 실제로 매핑됐나」 | Spring `RequestMappingHandlerMapping.handlerMethods` — **기구현**이 `AuthenticationCoverageContractTest.servedOperations()` 에 있다 | **뽑아 공유** (`support/ServedOperations.kt`) | 두 벌을 두면 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다 |
| 요청 필드의 **경계 방향** | 계약이 이미 기계가독으로 적었다 — `components.schemas.*.properties.*.x-service-constraint` 의 키가 `max_length` / `min_length` 다. 접근자도 기구현(`ContractSpec.serviceConstraint`, P-20) | **채택** | 계약 개정이 **불필요**하다. β-21 의 「표현 못 하면 fail-closed」 갈래로 가지 않는다 |
| 거절 경로의 문장 수 | `CountingDataSource` + `JdbcDocumentStoreTest.거절 경로의 문장 수` — **기구현** | **확장** | privacy-gate X1-2 가 지정한 처방이 기구현 확장이다 |
| 클래스 선언 구간 파싱 | `_declaration_region(fqcn)` — **기구현** (계수 축·이름 축이 공유) | **재사용** | 새 파서를 두면 β-20 이 없앤 「두 번째 파서」가 되살아난다 |
| 이력 대조에서 rename 추적 | `git log --follow` (git 표준) | **채택** | 단, 리비전마다 **그 시점의 경로**가 필요하므로 `--name-only` 로 함께 읽는다 |
| 변이 테스트(단언 무력화 전수) | pitest | **채택하지 않는다** | 사용자 결정으로 백로그 B-19(1순위). 이 레인이 끌어오면 그 결정을 뒤집는다 |

---

## 2. 항목별 처방과 그 근거

### ① X1 (privacy-gate 판정 — 최우선)

| # | 처방 | 파일 | 닫는 방식 |
|---|---|---|---|
| **X1-1** | P1 전부(상태 · **원시 바이트** · 헤더 이름 집합)를 **세 자리에 같은 단위로** 싣는다 | `support/OwnershipConcealment.kt`(신설) + `DocumentListReachTest`(DL-9 ②) · `DocumentDeleteReachTest`(DD-3) · `WorkspaceEndpointReachTest`(WR-4) | 공유 단언 하나 + 세 자리가 `ofByteArray()` 로 두 팔을 받는다 |
| **X1-2** | P2 를 **결정적 대리**로 잰다 — 기구현 확장 | `JdbcDocumentStoreTest.거절 경로의 문장 수` | 목록 팔 추가(`list(owner, 없음)` 대 `list(owner, 남의것)`) + **남의 작업 공간에 행을 심어 두고도 같음** |
| **X1-3** | 시간 축은 **붙이지 않는다** | — | 근거는 §3 |
| **X1-4** | `workspaces` 를 M-3 ⒝ 감시 테이블에 **넣는다** | 결정만(코드는 ⒝ 커밋) | 근거는 §3 |

### ② §11.2 「C6 착수 전」 β 항목

| # | 처방 | 파일 | 비고 |
|---|---|---|---|
| **β-03·β-24** | 「제품 주석이 이름으로 지목한 테스트 클래스」를 **인구조사로** 뽑아 그 클래스의 `@Test` 수를 핀으로 지킨다 | `tests/test_kotlin_gate_reach.py` — 새 축 + 새 표 | `FLOOR_TEST_CLASSES`·`MIN_TEST_CLASSES`·`MIN_FLOOR_CLASSES` 는 **리더 핀이라 손대지 않는다.** 그래서 처방표의 두 번째 갈래(「모양 판정을 파생 축으로 더한다」)를 택했다 |
| **β-04** | 바닥·명명 클래스의 **단언 토큰 수 하한** | 같은 파일 — 새 축 + 새 표 | 「단언 비우기」를 잡는다. **「무해한 단언으로 교체」는 잡지 못한다** — 그 잔여는 B-19 |
| **β-05** | 값 자리 불변식의 분모를 **계약 × 실제 매핑**으로 만든다 | `ValueSlotInvariantReachTest` + `support/ServedOperations.kt` | 인구조사 케이스 하나 + 부정 팔을 매핑된 오퍼레이션 전수로 |
| **β-10** | X-A3(인증 선행)에 **강제자를 세운다** — 토큰 없는 공백 값 자리가 401 인가 (쿼리 1 · 경로 1) | `ValueSlotInvariantReachTest` | 두 `addInterceptor` 줄 순서를 바꾸면 빨개진다 |
| **β-11** | 라쳇 이력 대조를 **rename 을 따라가게** 고친다 | `tests/test_kotlin_gate_reach.py` `_git_revisions` | `git log --follow` + 리비전별 경로 |
| **β-21** | 경계 **방향을 계약에서 읽고** 관측이 그 방향과 맞는지 단언한다 | `support/RequestFieldProbes.kt` | 계약 개정 불필요(§1) |
| **β-22** | 컨테이너 축을 `RequestFieldProbes` 와 **독립한 계약 oracle** 로 한 번 더 판정한다 | `RequestFieldRejectionReachTest` | 공유 판정 함수가 오판해도 이 축이 빨개진다 |

### ③ 판정 재료 (β-12 · X4 · X5 · β-08) — **고치지 않는다**

병렬 측정 레인 셋에 위임했다. 결과는 산출물 `04_kotlin-implementer_c6-preconditions.md` 에 실측으로 싣는다.

### ④ L-㉖ ⑧ (`--rerun-tasks` 를 빼면 UP-TO-DATE) — 중복 조치 금지 판정

β-02 의 조치(선언 입력 + `--no-build-cache` + 실행 표식 × `testsuite@timestamp`)가 이미 닫았는지 **실측으로** 판정한다. 닫혔으면 새 장치를 만들지 않는다.

---

## 3. 고르지 않은 것과 사유

- **X1-3 시간 축(목록)** — 붙이지 않는다. ⓐ privacy-gate 실측이 **40행에서 1.0955 로 침묵**했고 탐지에는 2,560행 모집단이 필요하다 ⓑ 그 fixture 를 `DocumentListReachTest` 에 넣으면 클래스 하나가 2,560건 업로드를 돌린다 ⓒ X1-2 의 구조 축이 **모집단·잡음·문턱과 무관하게** 같은 변이를 잡는다(privacy-gate §3 실측) ⓓ 정본(I-5 검증 4항)에 응답 시간이 없다. **모집단 근거 없는 시간 축은 태어나면서부터 거짓 초록**이라는 것이 그 회차의 실측 결론이다.
- **pitest(β-04 의 정공법)** — 사용자 결정으로 백로그. 대신 단언 토큰 수 하한을 세우고 **잡지 못하는 종류를 표에 적는다.**
- **`FLOOR_TEST_CLASSES` 편입(β-03 의 첫 갈래)** — 리더 핀이라 손대지 않는다. 필요성은 보고만 한다.
- **계약 개정(β-21 의 `bound: min|max` 신설)** — 불필요하다. 계약이 이미 `x-service-constraint` 의 키 이름으로 방향을 기계가독하게 적었다.
- **「모든 `@Test` 본문이 단언에 도달한다」 축** — 실측으로 버렸다. 직접 토큰 없는 본문 86건 / 같은 파일 안 호출을 전이적으로 따라가도 **75건**이 남는다(MockMvc DSL `andExpect { … }` 등 정당한 형태). 오탐 8.8% 로 시작하는 축은 곧 면제 목록을 부르고, 면제 목록은 은폐형이라 규칙 4 ⑵ 의 거부권에 걸린다.

---

## 4. 검증 계획

1. **고치기 전** — 각 항목의 구멍이 **초록**임을 일회용 worktree 에서 실측한다(변이 주입 → 겨눈 장치가 침묵).
2. **고친 뒤** — 같은 변이에서 **겨눈 장치가 그 자리를 짚는지** 실측한다.
3. 러너 — `uv run python .claude/skills/kotlin-migration/scripts/quality_gate_local.py`. 커밋 **뒤에** 다시 돈다.
4. Gradle 은 `--no-build-cache --rerun-tasks`, 요구 모드 게이트 앞에는 `KOTLIN_GATE_REACH_RUN_STARTED_AT` 을 박는다.
5. 판정 기준은 「무언가 빨개졌다」가 아니라 **「겨눈 장치가 그 자리를 짚었는가」**다.
