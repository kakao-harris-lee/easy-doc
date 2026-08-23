# C7 — `PUT /conversions/{conversion_id}` 검수 저장 · 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-23 / **계획:** `04_kotlin-implementer_c7-plan.md`(`9c41de8`)
**커밋:** **C7a `e937f94`** · **C7b `9df7039`** / **게이트 덩어리:** G-γ

**선행 입력:** `04_contract-keeper_c7-rulings.md`(P-1·X-8·X-12 판정) ·
`reviews/04_documents-c6r2_cross.md`(이월 X-8·X-13·X-14·X-15) ·
`04_contract-keeper_documents-test-spec.md`(CU 표 · 2026-08-23 재번호본)

**CU-11 재번호 대응표** — 계획 문서는 415 를 「CU-11(415)」로 병기했다. 재번호(`a1fc790`) 이후
**415 는 CU-12**, **401 은 CU-11** 이다. 이 산출물과 코드는 재번호본을 따른다.

---

## 1. 완성한 것

### 1.1 C7a — 이월 정리 (`e937f94`)

| ID | 무엇 | 어디 |
|---|---|---|
| **X-8** | `POST /documents` JSON 팔의 `workspace_id` 를 `UUID?` → `String?` 로 내리고 multipart 팔과 **같은** `DocumentService.parseWorkspaceId` 를 공유 | `api/document/DocumentDtos.kt` · `DocumentController.kt` · `application/document/DocumentService.kt` |
| | DC-29(JSON 팔 단독) · DC-30(복합 결함) · DC-31(두 팔 교차, **한 단언**) | `api/…/DocumentEndpointReachTest.kt` |
| **X-13** | `ConversionResponse`·`DocumentCreatedResponse`·`DocumentListItemResponse` 주 생성자 `private` + `@ConsistentCopyVisibility` | `ConversionDtos.kt` · `DocumentDtos.kt` |
| **X-14** | `MARK_DONE_SQL` 의 `model`·`provider_name` 리터럴을 `%s` 로 단일 소싱 **+ 그 값을 완료 조회에서 양성으로 못박음** | `api/…/ConversionReadReachTest.kt` |
| **X-15** | CR-3b 에 `id`·`document_id` 오배정 단언 두 줄 | 같음 |
| **P-1** | 저장 실패 문구 대조를 「계약 준수」 → **예시 신선도 대조**로 재명명(방향 역전 + fail-closed) | 같음 · `core/exceptions/DomainExceptions.kt` |
| **X-12** | `DecryptionFailedException` KDoc 을 좁힘 — 원인은 구분하지 않되 **문구는 자원을 특정한다** | `DomainExceptions.kt` |

### 1.2 C7b — `PUT /conversions/{conversion_id}` (`9df7039`)

| 층 | 무엇 | 어디 |
|---|---|---|
| `api` | `ConversionReviewRequest` DTO(**Bean Validation 없음**) · `@PutMapping` · 사적 헤더 2종 개별 부착 · `ReviewedBody` **유일한 프로덕션 생성 지점** | `api/document/ConversionDtos.kt` · `ConversionController.kt` |
| `application` | `ConversionReviewService` — 정규화·판정·잠금·저장·응답 재조회 | `application/document/ConversionReviewService.kt` |
| | 포트 `lockOwnedForReview` · `saveReview` · 반환 타입 `LockedConversion` · 문구 상수 4 | `DocumentPorts.kt` · `DocumentMessages.kt` |
| `infrastructure` | `LOCK_OWNED_FOR_REVIEW_SQL`(`FOR NO KEY UPDATE OF c`) · `SAVE_REVIEW_SQL` · 행 매퍼를 `ConversionRows` 로 분리 · 빈 배선 | `JdbcConversionRepository.kt` · `DocumentConfiguration.kt` |

**대응 Python 원본:** `app/services/documents.py` 의 `save_review`(참고 자료 — §4 가 갈린 자리를 적는다).

---

## 2. 계약이 요구한 마감 두 팔 — **실측값**

계약 파일은 **편집하지 않았다.** 아래는 contract-keeper 가 노드를 갱신할 때 쓸 실측이다.

| 계약 노드 | 오늘 값 | C7b 실측 | 닫는 케이스 |
|---|---|---|---|
| `x-unsupported-media-type.x-measured.not_reached` | *"`PUT /conversions/{conversion_id}` 는 재지 못했다 — 구현이 없다"* | **쟀다.** 유효 토큰 + `text/plain` → **415**(422 아님) · `detail` **문자열** · `Accept` 가 그 오퍼레이션 `requestBody.content` 키 집합(`application/json` 하나)에서 유도한 값과 같음 | **CU-12** |
| `x-stored-text-domain.applies_to[?edited_text].status` | `pending` | **`measured`.** 짝 없는 서로게이트가 든 수정본 → 422 · `detail` 문자열 · 값이 `x-stored-text-domain.detail` 과 같음 · **거절 뒤 행이 바뀌지 않음** | 저장 정의역 케이스 |

> **`x-auth-order-open` 은 건드리지 않았다** — 판정 대기이고 계약이 *"케이스도 쓰지 않는다"* 로
> 못박았다. **유효한 토큰으로만** 쟀다.

---

## 3. 게이트 실행 결과 (전부 `run_gate.sh` 경유 · 파이프 없음)

| 검사 | exit |
|---|---|
| `cd backend-kotlin && ./gradlew ktlintCheck detekt build --continue` | **0** |
| `cd backend-kotlin && ./gradlew moduleBoundaryCheck` | **0** |
| `uv run pytest -q tests/test_kotlin_comment_budget.py` | **0** |
| `uv run pytest -q tests/test_kotlin_gate_reach.py` (312 passed) | **0** |
| `uv run pytest -q tests/test_harness_scope_reach.py` (46 passed) | **0** |
| `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **0** |
| `uv run ruff check .` | **0** |
| `uv run mypy . .claude` (145 files) | **0** |
| `uv run pytest -q` (1671 passed · 68 skipped) | **0** |

**`parityHarness` · `tests/golden` 은 해당 없음** — `core` 의 마스킹·프롬프트·스타일 규칙을
건드리지 않았다. **미실행이 아니라 대상 밖**이다.

---

## 4. Python 과 **의도적으로 갈린 지점** (→ `parity-verifier` 통보)

| # | 자리 | Python | C7b | 근거 |
|---|---|---|---|---|
| **D-1** | 조건부 UPDATE 가 0행일 때 | `ConflictError` → **409** | `StorageException` → **500** | Python 은 잠금이 없어 0행이 실제로 「워커가 상태를 바꿨다」였다. C7b 는 `FOR NO KEY UPDATE` 를 쥐므로 0행은 **잠금 전제가 깨진 것**이고, 409 로 접으면 서버 결함이 정상 흐름으로 위장된다. **리더 판정 B-2 승인** |
| **D-2** | 동시성 | 잠금 없음 | 잠금 + 낙관적 조건 | 회전과 직렬화한다. 잠금이 서지 않은 상태는 조용한 덮어쓰기가 아니라 **0행**으로 드러난다 |
| **D-3** | 봉투 | 행 봉투 개념 없음(V2~V4 는 Kotlin 시대) | 세 열을 언제나 같은 세대로 | 세대가 AAD 에 실려 라벨과 열이 어긋난 행은 영영 열리지 않는다 |

**정규화·길이·빈 값·409·404 문구는 Python 과 같다** — 계약이 그 값을 소유하고 있고 그 값에서 읽었다.

---

## 5. 음성 대조 — **전건 실행, 읽기 판정 없음**

복원은 `cp` 가 아니라 **주입의 역치환**으로 하고 **sha256 대조**로 증명했다(규칙 5).

### 5.1 구현 장치 11건 → **11 빨강**

| 뗀 것 | 빨개진 것 |
|---|---|
| `stripControlChars` 호출 | `ConversionReviewReachTest` |
| 빈 값 판정의 `isBlank` → `isEmpty` | `ConversionReviewServiceTest` |
| 길이를 코드 포인트 → UTF-16 코드 단위 | `ConversionReviewServiceTest` |
| 0행을 409 로 접기 | `ConversionReviewServiceTest` |
| 초안 열에 검수본 쓰기 | `ConversionReviewReachTest` |
| 같은 세대 분기 제거(후보 (나)로 후퇴) | `ConversionReviewReachTest` |
| SQL `AND status = :requiredStatus` | `ConversionReviewStorageTest` |
| SQL 소유 술어 `document_id IN (…)` | `ConversionReviewStorageTest` |
| `FOR NO KEY UPDATE OF c` | `EnvelopeRotationConcurrencyTest` |
| 낙관적 조건(암호문 세 열 대조) | `ConversionReviewStorageTest` |
| `reviewed_at = now()` → 고정 타임스탬프 | `ConversionReviewReachTest` |

### 5.2 계약 값 5건 → **5 빨강** (일회용 worktree, 제거함)

`ConversionResponse.required` 에서 `edited_text` 제거 → CU-1 / PUT 409 예시 문구 → CU-3 /
PUT 422 `empty` 예시 문구 → CU-4 / `x-stored-text-domain.detail` → 저장 정의역 케이스 /
`fields[?edited_text].limit` → CU-5·CU-6.

**과잉 결합 확인**: `edited_text.limit` 변이에서 **DC-11 이 사는 `DocumentEndpointReachTest` 는
초록을 유지**했다 — 원시 축과 정규화 축이 한 값으로 뭉개지지 않았다는 실측이다.

### 5.3 **첫 회차에 5건이 초록이었다** — 그것이 고친 것

처음 돌렸을 때 11건 중 5건이 **빨개지지 않았다.** 원인과 처분:

| 초록이던 것 | 원인 | 처분 |
|---|---|---|
| 빈 값 `trim` | 하네스가 **잘못된 테스트**를 겨눴다(공백 케이스는 서비스 단위 테스트에 있다) | 대조 대상 수정 |
| 코드 포인트 | 표본이 전부 BMP 라 `length` 와 `codePointCount` 가 같았다 | **BMP 밖 경계 케이스 신설** |
| SQL 상태 조건 · 소유 술어 · 낙관적 조건 | 유스케이스가 먼저 막아 **대역에서는 조건을 지워도 초록**이다 | **`ConversionReviewStorageTest` 신설** — 실물 SQL 에서만 잴 수 있는 자리 |
| `FOR NO KEY UPDATE` | 동시성 테스트가 검수 저장을 **손수 흉내** 내고 있어 제품 SQL 을 지나지 않았다 | 흉내를 **제품 경로로 교체** |

> **X-14 는 「고쳐도 여전히 초록」이었다.** SQL 리터럴만 바꿔도 `doesNotContain(STORED_MODEL)` 은
> 심은 값이 응답에 없기만 하면 참이라 **공허하게 통과**한다. 단일 소싱은 갈림을 **없애지만
> 탐지하지는 못한다** — 그래서 완료 조회에서 그 값을 **양성으로** 못박은 뒤에야 빨개졌다.

---

## 6. 계획 대비 이탈

| # | 계획이 적은 것 | 실제 | 사유 |
|---|---|---|---|
| **E-1** | T-5 `EnvelopeColumnWriteGuardTest` 기준값 **파일 3 · 문장 5** | **틀렸다.** 착수 시점 실측은 **파일 4 · 문장 6**, C7b 뒤 **파일 5 · 문장 9** | 계획이 읽기로 짚었다. 실측으로 다시 잡았다 |
| **E-2** | `EnvelopeRotationConcurrencyTest` 는 손대지 않음 | 손수 흉내 낸 `saveEditedText` 를 **제품 경로**(`ConversionReviewService`)로 교체 | 흉내가 제품과 갈려도 그 파일이 초록이다 — §5.3 이 그것을 실측했다 |
| **E-3** | DB 층 케이스를 `ConversionReviewReachTest` 안에 | **`ConversionReviewStorageTest` 신설** | SQL `WHERE` 조건은 유스케이스가 먼저 막아 다른 층에서는 잴 수 없다 |
| **E-4** | (없음) | `saveReview` 의 쓰기 인자를 `ConversionEnvelope` 하나로 묶음 | detekt `LongParameterList` 가 울렸고, 그 신호가 옳게 가리킨 것은 **라벨과 열을 따로 받으면 어긋난 조합을 만들 수 있다**는 것이다 |
| **E-5** | (없음) | `JdbcConversionRepository` 의 행 매퍼를 `ConversionRows` 로 분리 | detekt `TooManyFunctions`. 임계값을 올리는 대신 **접근과 매핑을 갈랐다** |
| **E-6** | (없음) | `SAVE_REVIEW_SQL` 에 소유 술어 추가 | `MAX_UNGUARDED_STATEMENTS` 여유 0. **라쳇을 올리는 대신 술어를 더했다** — 회전 UPDATE 가 소유 술어 없이 남는 것은 회전에 「내 것」이 없어서지 같은 자리가 아니다 |
| **E-7** | (없음) | 주석 예산 두 축이 한계에 닿아 KDoc 압축 | §8 참조 |

---

## 7. 유보 해제·인구조사 갱신

| 자리 | 전 | 후 |
|---|---|---|
| `PrivateHeaderFloorCensusTest.NOT_YET_IMPLEMENTED` | PUT · export **둘** | **export 하나** (조사 9/10) |
| `RequestFieldRejectionLayerTest.PINNED_WITHOUT_DTO` | `ConversionReviewRequest.edited_text` | **빈 집합 — X-F9 마감** |
| `ProvenanceCreationSitesTest.ALLOWED["ReviewedBody"]` | 테스트 2곳 (프로덕션 **0**) | 프로덕션 **1** + 테스트 4곳 |
| `EnvelopeColumnWriteGuardTest` | 파일 4 · 문장 6 | 파일 5 · 문장 9 |
| `OwnershipPredicateGuardTest.EXPECTED_STATEMENTS` | 11 | 13 (**둘 다 소유 술어 있음** — `MAX_UNGUARDED_STATEMENTS` 7 그대로) |
| `SensitiveToStringReachTest.EXPECTED_SOURCE_DECLARATIONS` | 57 | 58 |
| `tests/test_kotlin_gate_reach.py` `TEST_CLASS_COUNT` | 112 | **116** (새 클래스 4) |

---

## 8. 주석 예산 — **리더 판정이 필요한 자리**

**두 축이 모두 한계에 닿았다.** 착수 시점 여유는 제품 **1,150자** · 테스트 약 **1,300자**였는데
C7b 가 필요로 한 것은 제품 2,577 · 테스트 3,124 였다.

처분: **예산을 올리지 않고 압축했다**(규칙 8 — 인상 시점은 Phase 경계이고 리더가 올린다).
압축은 두 종류로 갈린다.

1. **내 것 압축** — 같은 불변식을 더 짧게. 정보 손실 없음.
2. **규약 위반 제거** — `.kt` 규약이 금지하는 형태를 걷어냈다. 둘 다 C7b 가 이미 편집하는 파일이다.
   - `DocumentPorts.kt` 파일 머리 — Python 원본 대응 이력 · **기각한 대안**(`DocumentTextExtractor` 를 합치지 않은 이유) · 계획 §4.1 근거 ⑴⑵⑶ 열거
   - `StoredContent.kt` — **`## 2026-08-12 재개발 전환이 바꾼 것`** 날짜 표제와 그 아래 서사
   두 자리 모두 **불변식은 남기고 이력만** 걷었다.

> **판정이 필요한 것**: 이 압축은 이번만 통했다. 다음 오퍼레이션(`export`)은 같은 여유가 없다.
> G-γ 종결 시 예산을 올릴지, 아니면 `.kt` 규약 위반이 남은 파일을 계속 걷어낼지 정해야 한다.
> **이 산출물은 어느 쪽도 선택하지 않았다.**

---

## 9. 마스킹 불변식 — **검수 본문은 LLM 으로 가지 않는다** (확인함)

- `application/document` 에 `LlmProvider` 의존이 **없다.** `ConversionReviewService` 가 부르는 것은
  `ConversionRepository`·`ContentCipher`·`ConversionQueryService`·`TransactionRunner` 넷뿐이다.
- 이 경로가 지키는 선행 불변식은 **암호화 선행**이다. 마스킹 선행의 대상은 Phase 5 워커다
  (documents-plan §9.1 의 구분과 같다).
- 검수 본문은 **자리표시자가 든 채로** 저장된다 — 복원은 내보내기 전용이고 C7 은 그 함수를 부르지 않는다.
- `MaskedTextGatewayTest`·`ProvenanceCreationSitesTest` 의 분모가 좁아지지 않았다(전자 무변경, 후자는 넓어졌다).

## 9.1 로그 규칙

기록은 conversion id · 상태 · 실패 코드뿐이다. 새 타입 `LockedConversion` 의 `toString` 은
식별자·상태·세대만 남기고, `ConversionReviewRequest` 는 길이만 남긴다. 제약 위반은 기존
`DocumentStorageLog.constraintViolation`(SQLSTATE 만) 규약을 그대로 쓴다. 개인정보 스캐너 exit 0.

> **스캐너 오탐 하나를 문면으로 피했다** — CU-8 의 실패 문구가 `403` 을 산문으로 담아
> `OWNERSHIP-403` BLOCK 후보로 잡혔다. **단언은 그대로 두고**(`isNotEqualTo(FORBIDDEN)` 유지)
> 문구에서 숫자를 뺐다. 테스트가 재는 것은 바뀌지 않았다.

---

## 10. 리더 판정이 필요한 것

| # | 무엇 | 상태 |
|---|---|---|
| **L-1** | **주석 예산**(§8) — G-γ 종결 시 인상할 것인가, 규약 위반 제거를 계속할 것인가 | **미결** |
| **L-2** | A-4 `SpringTransactionRunner.inTransaction` 중첩 전파 — **통합 테스트로 동작은 확인**했다(`ConversionReviewReachTest` 13건이 중첩 호출로 통과). 다만 「합류한다」를 **전파 설정으로 직접 단언한 케이스는 없다** | 동작 확인 · 명시 단언 없음 |
| **L-3** | `x-open-asymmetry`(B-5) — `text`(원시)와 `edited_text`(정규화)가 이제 **같은 열·같은 정의역**을 지난다. C7 이 해소할 의무는 없으나 성격이 바뀌었다 | **등재만** |

## 10.1 contract-keeper 통보

| # | 무엇 |
|---|---|
| **C-1** | §2 두 팔의 **실측 제공** — `x-unsupported-media-type.x-measured.not_reached` 와 `x-stored-text-domain.applies_to[?edited_text].status` 를 갱신할 근거. **계약 파일은 내가 편집하지 않았다** |
| **C-2** | **`paths./conversions/{conversion_id}.put.responses.404` 에 `examples` 가 없다** — CU-8 은 그래서 상태 코드와 응답 구별 불가능성으로만 잰다. GET 404 에는 예시가 있어 **두 오퍼레이션이 비대칭**이다. 계약 빈자리로 올린다 |
| **D-1 요청** | **PUT 의 검사 순서 조항이 없다.** `POST /documents` 에는 명문이 있다. C7b 가 택한 순서(§1.2)를 조항으로 굳힐지 판정 요청 |

## 10.2 privacy-gate 통보

- **B-1 (다) 채택** — 사용자 요청 경로가 행을 기회주의적으로 최신 세대로 올린다. 후보 (가)(옛 키로 새 평문 봉인)를 기각한 근거는 §1.2 이고, `AesGcmContentCipher.encrypt` 가 `writeKeyVersion` 으로만 봉인해 **(가)는 포트를 넓히지 않으면 불가능**하다 — (다)가 구조로 강제된다.
- `ReviewedBody` 첫 프로덕션 생성 지점 등재 · 저장 정의역 팔 마감 · UPDATE 자신에 소유 술어 추가.

---

## 11. 미포팅·범위 밖

- `GET /conversions/{id}/export` — `export` 단위. `NOT_YET_IMPLEMENTED` 에 유보로 남아 있다.
- `x-auth-order-open` 케이스 — 판정 대기라 계약이 금지했다.
- 계약 개정 2건(rulings §2-4 ㉠㉡) — 리더 승인 뒤 contract-keeper 의 별 커밋.
- 테스트 스펙의 낡은 행 번호 지목(rulings §4-2 ㉢) — 하네스 단위.

## 12. 리뷰 축 판정

**①보안·개인정보 불변식**(`ReviewedBody` provenance · AEAD 봉투 세대 · 소유권 은닉 404 · SQL 소유
술어 · no-store) · **②외부 HTTP 계약**(신규 오퍼레이션 + X-8 위반 해소) · **③게이트·탐지기 자신**
(유보 해제 · 인구조사 5개 갱신 · 라쳇 대신 술어 추가) **셋에 닿는다.** ⑥은 인접(저장 정의역이
내보내기 무결성의 전제이나 렌더링은 건드리지 않았다). **codex 독립 리뷰 필수.**
