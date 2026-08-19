# Phase 4 계약 레인 — 게이트 26 집행 기록 (1회차)

**작성**: `contract-keeper` · 2026-08-20
**계획 정본**: `docs/migration/_workspace/04_contract-keeper_documents-contract-plan.md`
**착수 HEAD**: `0643cfd` · **집행 커밋**: `e0f102f`(C1) · 이 기록 자신(`docs` 커밋)
**성격**: 세션이 「진행 중인 커밋 단위까지만」으로 마감됐다. **C1 하나만 집행했다.**
L-1·L-2 는 **조사만 하고 계약 파일을 한 글자도 고치지 않았다** — 그 조사 결과를 여기 남겨
다음 세션이 다시 재지 않게 한다.

---

## 0. 한 줄 요약

| 항목 | 상태 |
|---|---|
| **C1** (K5 · P-22 충돌 해소) | **완료 — `e0f102f`** |
| **L-1** (`EnqueueFailed`/502 조항 개정) | **미착수(계약 무접촉).** 조사 완료 — §2. **단독 집행 불가로 판정**했고 사유가 있다 |
| **L-2** (목록 tie-break 신설) | **미착수(계약 무접촉).** 편집 지점 확정 — §3 |
| C2~C9 | 미착수 — §4 |
| 워킹 트리 | 이 레인 변경 없음 — §6 |

---

## 1. C1 — 집행 완료 (`e0f102f`)

**한 파일만 고쳤다**: `docs/migration/_workspace/04_contract-keeper_documents-test-spec.md`.
`contracts/**`·`backend-kotlin/**`·`frontend/**` 무접촉.

**확정**: `P-22` = `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:409`
의 `deletionRefusalPrecedenceExample()`. documents 명세가 쓰던 인라인 헤더
`schema.examples[0]` 노드는 **P-37** 로 이동. 파서 범위 표기는 전부 **P-23~P-37**.

**계획 §3-2 편집 8건 전건 반영** — `:55` · `:266`(P-37 로 개명 + 표 마지막으로 이동) ·
N-28 · N-30 · §6 표 · §8 통보 · §9 · §4 서문(사후 등재 문단 신설).

**계획에 없던 정정 1건**: 계획 §3-3 규칙 4 가 「정의 총수 36 → 37」로 **한 숫자**를 말했는데
실측하면 두 숫자다.

| 축 | 값 (2026-08-20 · C1 직후 실측) |
|---|---|
| 세 명세의 `^\| \*\*P-N\*\* \|` 정의 행 | **36** (auth 15 · workspaces 6 · documents 15) |
| `ContractSpec.kt` KDoc 라벨 중 **명세 미등재** | **1** (P-22) |
| 합집합 | **37**, `P-1`~`P-37` 연속 |

**두 숫자를 각각 고정하도록** 명세에 적었다. 합집합만 재면 **정의가 명세에서 코드로 새어도
초록**이고, 그것이 K5 가 태어난 경로다.

**첫날 도달(미실행 · 논증)**: 계획 §3-3 규칙 ⑵(미등재 KDoc 라벨 금지)를 오늘 트리에 적용하면
C1 **전**에는 P-22 에서 빨강, C1 **후**에는 초록이다. **Gradle 은 이 세션에서 돌리지 않았다** —
실행 확인은 C2 몫이다(§5).

---

## 2. L-1 — 조사 결과 (계약 무접촉)

리더 요구: ⑴ 조항을 lease 큐에 맞게 개정 ⑵ 와이어 변경·`info.version`·React 영향 **실측**
⑶ 없어지는 갈래가 **정말 도달 불가인지 근거** ⑷ 도달 가능한 잔여가 있으면 **그것만** 남길 것.

⑵⑶⑷ 를 실측했다. **⑴ 은 하지 않았다** — 그 이유가 §2-4 다.

### 2-1. 도달 불가인 것은 「502」가 아니라 **`EnqueueFailed` 상태**다

리더 판정의 전제(같은 트랜잭션이면 「저장은 됐는데 등록은 실패」가 성립하지 않는다)는
**계획 문서가 명문으로 뒷받침한다** — `docs/plans/2026-08-11-kotlin-react-migration.md:283`:
*"문서·변환·작업 행을 같은 DB 트랜잭션에서 저장하면 'DB 커밋 성공, 큐 등록 실패' 간극이 사라진다."*

그런데 **없어지는 것과 남는 것을 정확히 갈라야 한다.**

| 갈래 | 도달 가능성 | 근거 |
|---|---|---|
| **`failure_code = "EnqueueFailed"` 를 단 커밋된 변환** (`:930-931` · `:1998`) | **도달 불가** | 그 상태는 ⓐ 변환 커밋 성공 **∧** ⓑ 그 뒤 등록 실패를 동시에 요구한다. 같은 트랜잭션이면 ⓐ∧ⓑ 가 성립할 수 없다 |
| **`POST /documents` 의 502 응답** (`:989`) | **조건부 도달 불가** — 등록이 같은 트랜잭션에 들어간다는 **아직 코드로 서지 않은 선택**에 달렸다 | 등록이 커밋 전이면 실패 시 전량 롤백이라 아무것도 저장되지 않고, 그때 옳은 코드는 500(`InternalError`)이지 502가 아니다 |
| **`QueueUnavailableException` → 502 매핑 자체** | **오늘 살아 있고 테스트로 고정돼 있다** | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt:381-386` 이 `LlmProviderException`·`QueueUnavailableException` 을 `HttpStatus.BAD_GATEWAY` 로 보내고, `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ErrorContractTest.kt:57` 이 `"queue, 502"` 로 **초록으로 고정**한다 |
| **`BadGateway` 의 둘째 사유 `LLMProviderError`** | 이 배치 밖 | `DomainExceptions.kt:8`·`:71` 이 매핑을 명시한다. 다만 14개 엔드포인트 중 **동기로 LLM 을 부르는 것이 없어** 오늘 어느 오퍼레이션에서도 502 로 나가지 않는다 — 이것은 lease 큐와 무관한 별개 관찰이다 |

**그래서 「그것만 남기는」 정확한 좁힘은**: `EnqueueFailed` **상태**를 계약에서 내리되,
`QueueUnavailableException`→502 **매핑**을 함께 내릴지는 **별개 판정**이다. 둘을 한꺼번에
지우면 살아 있는 구현·초록 테스트를 계약 위반으로 만든다.

### 2-2. blast radius 실측 — 조항 하나가 아니다

| # | 자리 | 문면 | 처분 |
|---|---|---|---|
| ⓐ | `contracts/easy-doc-v1.yaml:927` | 검사 순서 `… → 저장 → 커밋 → 큐 등록(502)` | 꼬리 제거 |
| ⓑ | `:930-931` | `EnqueueFailed` 표시 후 502 · **"등록은 작업 id를 변환 id로 고정해 멱등하다"** | 재서술 |
| ⓒ | `:989` | `'502': BadGateway` — **계약 전체에서 502를 선언한 유일한 오퍼레이션**(실측: `'502'` 1건) | 삭제 시 ⓓ가 따라온다 |
| ⓓ | `:1658-1672` `components/responses/BadGateway` | `$ref` 참조 수 **1건**(=ⓒ). ⓒ를 지우면 **고아 컴포넌트**가 된다 | 함께 판정 |
| ⓔ | `:1998` `ConversionResponse.failure_code.description` | *"큐 등록 실패는 예외 클래스명이 아닌 `EnqueueFailed` 를 쓴다"* | 제거 |
| ⓕ | **`:1680` `ServiceUnavailable`** | **"큐(Redis) 미배선 → 업로드"** | **리더가 지목하지 않은 둘째 Redis 전제** — §2-3 |
| ⓖ | `:1495`·`:1502`·`:1511` `/health` description | Redis 를 진단 대상으로 명시 | 같은 전제. 이 단위 밖(O-14 와 같은 자리) |
| ⓗ | `x-changelog` + `00_contract-keeper_changelog.md` | `x-change-policy.procedure` 5 | 필수 |

### 2-3. 리더가 지목하지 않은 자리 — 503도 같은 전제 위에 서 있다

`ServiceUnavailable:1680` 의 **"큐(Redis) 미배선 → 업로드"** 는 `EnqueueFailed` 와 **같은
Redis/ARQ 전제**다. PostgreSQL lease 큐에서 큐는 **별도 배선을 갖지 않는다** — 큐 테이블은
Flyway 마이그레이션이고, 안 돌았으면 애플리케이션이 기동하지 못한다. 즉 「큐만 미배선」은
**독립한 구성 상태로 존재하지 않고** 같은 절의 "DB 세션 팩토리 미배선 → 전체"에 흡수된다.

**이것이 왜 중요한가**: documents 명세의 **X-C6 축(「502 ≠ 503」)이 DC-18(502)·DC-19(503)
두 팔로만 서 있다**(`04_contract-keeper_documents-test-spec.md:90`·`:91`·`:209`).
L-1 을 502 쪽만 처리하면 **DC-19 가 무대 없는 케이스로 남는다** — 조항은 살아 있는데 그 상태를
만들 방법이 없는, F-4 가 지적한 것과 정확히 같은 형태다. **두 팔을 같은 판정에서 봐야 한다.**

### 2-4. 왜 이번 세션에 집행하지 않았는가

1. **살아 있는 Kotlin 구현·초록 테스트를 계약 위반으로 만든다.** ⓒ를 지우면
   `GlobalExceptionHandler.kt:383` 과 `ErrorContractTest.kt:57` 이 그 순간 계약 밖이 된다.
   이 둘은 `backend-kotlin/**` 이고, 이번 회차 경계는 **`ContractSpec.kt` 외 Kotlin 무접촉**이다.
   계약·구현·테스트가 **같은 변경 단위**여야 하는데 그 단위를 이 레인이 혼자 닫을 수 없다.
2. **범위가 리더 요구보다 넓다** — §2-2 여덟 자리, 그중 ⓕ·ⓖ는 리더가 지목하지 않았다.
   지목된 한 조항만 고치면 계약 안에서 두 절이 갈린다(escalation-503 §3-1 ⓒ가 겪은 것과 같은 형태).
3. **선결 하나가 미확정**: 등록을 같은 트랜잭션에 넣는다는 것이 **구현 계획의 잠정 전제였고**
   (`04_kotlin-implementer_documents-plan.md:669` 는 오히려 반대로 「판정 전까지 계약대로
   커밋 이후 별도 트랜잭션」이라 적었다) 아직 코드로 서지 않았다. 계약이 그 선택을 **확정**하는
   문장을 쓰는 것은 옳지만, 그 확정은 구현 레인과 같은 커밋에 실려야 강제가 된다.

### 2-5. 실측 — 와이어 변경 · React 영향 · `info.version`

| 축 | 실측 결과 |
|---|---|
| **와이어 변경** | **있다.** ⑴ `POST /documents` 응답 상태 집합에서 **502가 사라진다** ⑵ `ConversionResponse.failure_code` 가 **`"EnqueueFailed"` 값을 더는 가질 수 없다**. 필드 이름·타입·헤더·미디어 타입은 **불변** |
| **파괴성** | **비파괴.** 둘 다 **응답 쪽 제약 강화**(소비자가 받는 값의 부분집합화)다. 새 상태 코드나 새 필드를 요구하지 않는다 |
| **React 런타임 영향** | **깨지지 않는다. 그러나 참조는 실재한다** — `frontend/src/conversion/failureMessages.ts:43` 의 `MESSAGES.EnqueueFailed`(reason/advice/`retryable: true`). 조회는 `:62` `return MESSAGES[code] ?? UNKNOWN` 로 **폴백이 있어** 값이 안 와도 예외가 없다. → **런타임 파손 0, 사용되지 않는 항목 1** |
| **React 부채 (Phase 6 회수 대상)** | ⑴ `failureMessages.ts:43-47` `EnqueueFailed` 항목 — 어떤 구현에서도 나오지 않는 코드가 됨 ⑵ `frontend/src/pages/UploadPage.tsx:74` 주석의 `502(변환 서비스)` ⑶ `frontend/src/api/client.test.ts:121` 의 502 픽스처(테스트 전용 — 일반 오류 경로 검증이라 조항과 무관, **건드리지 않는 것이 옳다**). ⑴⑵는 계획 §4.1 「계약 개선이 만든 프런트 부채」와 같은 갈래다 |
| **`info.version`** | **정하지 않았다.** 계획 §7-2 가 신설을 제안한 `x-change-policy.versioning` 이 **아직 계약에 없다**(현행 키 7종에 버전 규칙 없음 — 계획 §1-5 실측). 규칙이 서기 전(C6) 에 이 항목의 bump 를 정하면 그 판단이 규칙보다 먼저 굳는다. **C6 이후에 정한다** |
| **소유자 단독 권한** | **아니다.** `x-change-policy.escalate_to_leader` ④ (배포·운영 동작이 달라지는 변경) 에 걸린다 — 502 갈래 제거는 재시도 신호의 소멸이고, ⓕ까지 가면 운영 판독(503의 뜻)이 바뀐다 |

### 2-6. 다음 세션이 L-1 을 집행할 때의 권고

**한 변경 단위 = 계약(§2-2 ⓐⓑⓒⓓⓔ) + `GlobalExceptionHandler` 매핑 + `ErrorContractTest`
+ 명세 DC-18·DC-19 + `x-changelog`.** 구현 레인과 **동시**여야 한다.
ⓕ(503의 큐 줄)는 **같은 판정에서 함께 답한다** — 남기려면 `Redis` 표기를 지우고 남는 상태를
적시해야 하고, 내리려면 DC-19 와 X-C6 의 둘째 팔을 어떻게 할지 함께 정해야 한다.
ⓖ(`/health`)는 O-14 와 묶어 별건으로 두기를 권고한다.

---

## 3. L-2 — 미착수 (계약 무접촉). 편집 지점은 확정했다

리더가 신설을 판정했고 두 레인이 독립으로 같은 값(`created_at DESC, id DESC`)에 도달했다.
**계약 파일을 고치지 않았다** — L-1 과 같은 커밋에 실릴 예정이었는데 L-1 이 §2-4 로 막혔고,
tie-break 만 단독으로 커밋하면 C3(케이스 DL-12·DL-13 신설)과 갈라진다.

**확정된 편집 지점** (다음 세션이 그대로 쓰면 된다):

| # | 자리 | 지금 | 바꿀 것 |
|---|---|---|---|
| ⑴ | `contracts/easy-doc-v1.yaml:2051` `DocumentListResponse.items.description` | `최신순.` | `최신순(created_at 내림차순, 동률이면 id 내림차순).` |
| ⑵ | `:999` `GET /documents` summary | `내 문서를 최신순으로 조회한다` | 유지(요약은 요약이다). **description 쪽에 전순서 사유 한 줄** — offset 페이지네이션이 전순서 없이 성립하지 않는다 |
| ⑶ | `x-changelog` + `00_contract-keeper_changelog.md` | — | 조항·근거 ID(**G2** 주 · **G4** 보조)·영향 테스트(DL-12·DL-13)·통보 대상 |

**실측 재확인(이 세션)은 하지 않았다** — 계획 §4-5 의 React 실측(`sort(` 0건,
`HistoryPage.tsx:78` 이어붙이기가 오히려 고쳐진다)을 그대로 인용한다. **재측정 미실행.**

---

## 4. 남은 커밋 단위와 선결

| # | 내용 | 담당 | 선결 | 상태 |
|---|---|---|---|---|
| **C1** | P-22 충돌 해소 | contract-keeper | — | **완료 `e0f102f`** |
| **C2** | `ParserNodeRegistryTest` + 음성 대조 N-R1~4 | kotlin-implementer | **C1(충족됨)** | 미착수 — **지금 바로 가능** |
| **L-1 단위** | §2-2 ⓐ~ⓔ(+ⓕ 판정) + 매핑 + `ErrorContractTest` + DC-18/19 | contract-keeper **+** kotlin-implementer **동시** | 등록의 트랜잭션 경계 확정 · ⓕ 판정 | 미착수 |
| **C3** | 77 → 84 케이스(DC-16b·16c·1b·18b·18c·DL-12·13) + **L-2 tie-break** | contract-keeper | **L-1 단위**(DC-18b·18c 의 형태가 502 갈래 처분에 달렸다) · L-2(판정 완료) | 미착수 |
| **C4** | 위임 표식(WR-2) + §5 를 명세 성질로 등재 | contract-keeper | C3 | 미착수 |
| **C5** | `SpecCaseCoverageContractTest` + N-C1~5 | kotlin-implementer | **C4** | 미착수 |
| **C6** | `x-changelog` 전칭 정정 + `versioning` 신설 + 아홉 항목 `reason` | contract-keeper | — (독립) | 미착수 |
| **C7** | `ContractVersioningTest` + N-V1~4 | kotlin-implementer | **C6 과 같은 단위** | 미착수 |
| **C8** | `ContractSpec` `$ref` 재귀·섹션 검증·헤더 정확 일치 + N-H1~6 | kotlin-implementer | — (독립) | 미착수 |
| **C9** | 내보내기 엔드포인트 + CE-5·CE-5t·CE-6 + N-20·N-20b | kotlin-implementer | 내보내기 구현 커밋 | 미착수 |

**DC-18c 는 리더 판정 L-1 로 Phase 5 이월**이다. C3 에 케이스를 넣되 **측정은 Phase 5** 로
표기해야 하며, 그 표기 형식이 아직 정해지지 않았다(§5 미실행 3번).

---

## 5. 미실행 검사 — 「미실행」으로 적는다

1. **Gradle 전건 미실행.** `:api:test --tests '*Contract*'` 를 이 세션에서 **한 번도 돌리지
   않았다**(계획 §9 의 실행 시점은 C2 이후이고, C2 는 미착수). C1 은 마크다운 한 파일이라
   컴파일 대상이 아니다.
2. **`ParserNodeRegistryTest` 미작성 · 음성 대조 N-R1~4 미실행.** §1 의 「첫날 도달」은
   **논증이지 실측이 아니다.**
3. **DC-18c 의 Phase 5 이월 표기 형식 미정.** 케이스 표에 이월 열을 새로 두는지, `계층` 칸에
   적는지 정하지 않았다.
4. **L-2 React 영향 재측정 미실행** — 계획 §4-5 의 값을 인용만 했다.
5. **`openapi-spec-validator` 미실행** — 이 세션은 계약 파일을 고치지 않았으므로 재검증
   대상이 없다.
6. **제어문자 전수 검사**: 변경 파일 2건(`04_..._documents-test-spec.md`, 이 파일)을
   **직접 스캔해 0건**을 확인했다(C0 비개행 · DEL · C1 전 구간). `pytest tests/test_raw_control_chars.py`
   자체는 **미실행**(`tests/**` 는 이 회차 금지 구역이라 읽기·실행 모두 삼갔다).

---

## 6. 워킹 트리

이 레인이 남긴 미커밋 변경 **없음**. `git status` 에 보이는 것은 전부 **다른 레인의 것**이다.
아래는 이 세션 종료 시각(`d84ffcb` 직후) 실측이다 — 세션 도중에도 바뀌었으므로 **스냅샷**으로 읽는다.

```
 M .claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py
 M backend-kotlin/gradle/libs.versions.toml
 M backend-kotlin/infrastructure/build.gradle.kts
 M tests/golden/baseline.py
 M tests/test_raw_control_chars.py
?? .playwright-mcp/
?? docs/(한글 .doc 2건)
```

**이 레인은 위 어느 것도 건드리지 않았다.** 착수 시점에 있던 `M CLAUDE.md` 는 종료 시점에
사라졌는데, 이 레인의 커밋 두 건(`e0f102f`·`d84ffcb`)에 그 파일이 없으므로 **다른 레인이
되돌렸거나 커밋한 것**이다 — 이 레인은 그 경위를 확인하지 않았다.

---

## 7. 리더 판정 — 다음에 먼저 필요해지는 것

**우선순위 1 — L-1 의 잔여 두 갈래.** 리더 판정 L-1 이 조항 개정을 지시했으나, 집행에
**답이 더 필요하다**(§2-3·§2-4):

- **⑴ `QueueUnavailableException`→502 매핑을 함께 내리는가**, 아니면 매핑은 두고
  `POST /documents` 의 502 선언만 내리는가. 후자면 `BadGateway` 는 **고아 컴포넌트**로 남는다.
- **⑵ `ServiceUnavailable:1680` 「큐(Redis) 미배선 → 업로드」와 DC-19 를 어떻게 하는가.**
  이것을 답하지 않으면 X-C6 축이 한 팔로 남는다.

**우선순위 2 — L-3**(위임 표식이 면제 조항인가). C4→C5 의 선결이고, C3 다음에 바로 온다.

**L-4·L-5·L-6 은 아직 필요해지지 않았다** — L-4는 Phase 4 종료 후, L-5는 의존성 추가 시점,
L-6은 C3 착수 시점.

**계획 §10 의 원래 L-1(DC-18c 가 Phase 4 것인가)은 리더가 이미 답했다** — Phase 5 이월.
위 우선순위 1 은 그 답을 집행하려다 **새로 드러난** 잔여다.

---

## 8. 통보

| 대상 | 내용 |
|---|---|
| **`kotlin-implementer`** | ⑴ **C2 선결이 풀렸다**(`e0f102f`). `P-22` 는 **`ContractSpec.kt:409` 그대로 두고 개명하지 마라.** 파서 범위는 `P-23~P-37`. ⑵ 강제자는 **두 숫자를 각각** 고정하라 — 명세 정의 행 **36**, `ContractSpec.kt` 전용 등재 **1**, 합집합 **37**·연속. ⑶ **`GlobalExceptionHandler.kt:383` 의 `QueueUnavailableException`→502 와 `ErrorContractTest.kt:57` 을 지금 고치지 마라** — 계약이 아직 그 매핑을 요구하고 있고, 개정은 계약과 **같은 커밋**이어야 한다(§2-4) ⑷ 큐 **등록의 트랜잭션 경계**를 확정하면 계약 레인에 알려라. `04_kotlin-implementer_documents-plan.md:669` 의 잠정 전제(커밋 후 별도 트랜잭션)는 리더 판정 L-1 로 **폐기 방향**이다 |
| **`parity-verifier`** | 이 회차에 계약 조항 변경 **0**. 계획 §11 의 통보(`parity/fixtures/export/export.json` 의 부재 단언 4건이 공허하다)는 **여전히 유효하고 이 레인이 고치지 않았다** |
| **`privacy-gate`** | 이 회차에 개인정보 불변식 변경 **0** |
| **리더** | §7 우선순위 1 두 갈래. **L-1 은 계약 소유자 단독 집행 불가로 판정했다**(`escalate_to_leader` ④ + 살아 있는 구현·초록 테스트 동반) |
