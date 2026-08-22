# C6 — `GET /conversions/{conversion_id}` 구현 계획

**정본**: 계획 §7.2 C6 행(`04_kotlin-implementer_documents-plan.md:545`) · 테스트 행
`04_contract-keeper_documents-test-spec.md` CR 표(`:188-197`) · 계약
`contracts/easy-doc-v1.yaml` `paths./conversions/{conversion_id}.get`(`:1512-1542`) ·
`ConversionResponse`(`:2425`) · `MaskedItemResponse`(`:2375`) · `ConversionStatus`(`:2214`).

**착수 리비전**: `fd00c38`. 앞 회차: `..._delete.md` · `..._c6-preconditions.md` · `..._documents.md`.

---

## 1. 라이브러리·프레임워크 리서치 (CLAUDE.md 구현 전 규칙 1)

**새 의존성 0.** 이 단위가 쓰는 것은 전부 이미 카탈로그에 고정된 것들이다.

| 필요 | 쓰는 것 | 이미 있는가 |
|---|---|---|
| 조인을 품은 단일 읽기 질의 | `JdbcClient` (`spring-jdbc`) | 예 — `JdbcDocumentRepository.listOwned` 가 같은 형태(조인 + 소유 술어) |
| 복호화 | `ContentCipher`(포트) / `AesGcmContentCipher` | 예 — C2 |
| 마스킹 대응표 역직렬화 | `MaskedItemCodec.decode` | 예 — C2 가 **양방향**을 이미 만들었다 |
| 응답 직렬화 | Jackson 3 + 필드별 `@get:JsonProperty` | 예 — `DocumentDtos.kt` 규약 |
| 계약 파싱 | `ContractSpec`(snakeyaml) | 예 — 접근자 2개만 추가 |
| 소유권 은닉 판정 | `OwnershipConcealment` | 예 — X1-1 이 만든 한 벌 |
| 매핑 표면 발견 | `ServedOperations` | 예 — β-05 |
| 실 DB 테스트 | Testcontainers + `PostgresTestSupport` | 예 |
| Python 게이트의 방향 추론(X5) | 표준 라이브러리 `ast` | 새 의존성 아님 |

**직접 구현하는 것과 사유**: 조회 결과 뷰 타입(`ConversionView`)과 포트 하나. 라이브러리가
줄 수 있는 것이 아니고, 소유자 인자를 **시그니처로 요구**하는 것이 이 단위의 요구
(`privacy-gate` 해제 조건 ⒜)이기 때문이다.

## 2. 기구현 확인 (CLAUDE.md 구현 전 규칙 2)

재사용하고 **다시 만들지 않는다**:

- `MaskedItemCodec`(저장 형식·범주 키 ↔ 한국어 라벨 매핑) — C2 가 왕복을 이미 못박았다.
- `MaskedItemView`(`core/document/Conversion.kt`) — 계약 `MaskedItemResponse` 3필드에 1:1.
- `ContentCipher.decrypt` + `EncryptedField.CONVERSION_*` 세 값.
- `OwnershipConcealment.assertIndistinguishable` — CR-8 은 **이것을 쓴다**(다섯째 자리 → 여섯째).
- `ContractSpec.pathExampleDetail`·`responseStatuses`·`schemaRequired`·`globalHeaderValues`·
  `pathVariable`·`headerConst` — CR-1·CR-7·CR-9·CR-10 의 기대값 출처.
- `DocumentDeleteReachTest` 의 계정·문서 생성·요청 조립 형태(같은 규약을 따른다).
- `AuthenticatedEndpoints` — 경로 하나 추가.

**새로 만드는 것**: `core/document/ConversionView` · `ConversionRepository.findOwnedResult` ·
`DocumentService.readConversion` · `api/document/ConversionDtos.kt` ·
`DocumentController.readConversion` · 테스트 2클래스.

## 3. 무엇을 어떤 순서로

| # | 단계 | 산출 |
|---|---|---|
| 1 | `core` 뷰 타입 | `ConversionView`(계약 13필드 대응, 본문은 `PlainBody?`·`Secret`) |
| 2 | `application` 포트 | `ConversionRepository.findOwnedResult(ownerId, conversionId)` — **M-3 ⒜** |
| 3 | `infrastructure` 어댑터 | 조인 + `d.user_id = :ownerId` 를 **SQL `WHERE` 안에** |
| 4 | `application` 유스케이스 | `DocumentService.readConversion` — 복호화 3열, 없으면 404 |
| 5 | `api` DTO·컨트롤러 | 키 생략 금지(X-E2) · 빈 배열(X-E3) · 사적 헤더 개별 부착(X-D1 하한선) |
| 6 | 소유 술어 핀 | `OwnershipPredicateGuardTest` 인구조사에 새 SELECT 한 줄 |
| 7 | 계약 접근자 | `ContractSpec.schemaEnum`·`schemaPropertyPattern`(P-31·P-32) |
| 8 | 테스트 | `ConversionReadContractTest`(C-M) · `ConversionReadReachTest`(C-R·C-I) |
| 9 | X4 | `DocumentDeleteReachTest` — 작업 행을 **조인 없이** 센다 |
| 10 | β-12 ⓑ | JSON 팔의 빈 문자열 흡수 차단 (multipart 팔은 §5) |
| 11 | 하네스 | β-08(튜플 삭제 탐지) · X5(AST 방향 추론) · ⑤(실행 시간 축) |
| 12 | 핀 갱신 | `TEST_CLASSES`·`TEST_CLASS_COUNT`·개수·단언 표 |

**왜 이 순서인가**: 저장 → 유스케이스 → HTTP 로 올라가면 실패 원인이 층으로 분리된다.
하네스(11)를 마지막에 두는 이유는 새 테스트 클래스가 확정된 뒤에야 핀 값이 정해지기 때문이다.

## 4. 어떤 테스트로 검증하는가 (명세 §5 계층 배치)

| 케이스 | 층 | 클래스 |
|---|---|---|
| CR-1(키 집합·사적 헤더 **개수**) · CR-9(422 배열) | **C-M** `@WebMvcTest` | `ConversionReadContractTest` |
| CR-2·CR-3·CR-4·CR-5·CR-6 (상태별 모양·마스킹 항목) | **C-I** 실 PostgreSQL | `ConversionReadReachTest` |
| CR-7·CR-8 (소유권 은닉) | C-I | 〃 (`OwnershipConcealment`) |
| CR-10 (401) | **C-R** 실 소켓 | 〃 |
| DD-5 **HTTP 팔** | C-I | 〃 — 파기 전 200 을 함께 관측해 「핸들러 없음」과 가른다 |
| P-31·P-32 fail-closed | 단위 | `DocumentContractNodeTest` |

**떼면 무엇이 깨지는가**

| 장치 | 떼면 |
|---|---|
| SQL 의 `d.user_id = :ownerId` | CR-7·CR-8 빨강 + `OwnershipPredicateGuardTest` 인구조사가 미방어로 옮김 |
| `findOwnedResult` 대신 `lockEnvelope` 사용 | 컴파일은 되지만 CR-7 이 200 을 받아 빨강 |
| 빈 배열 대신 `null` | CR-3 빨강 |
| 키 생략(`NON_NULL`) | CR-1·CR-2 빨강 |
| 마스킹 범주를 코드 리터럴로 | N-26(계약 원소 제거)에서 CR-5 가 **줄어야** 하는데 안 줄면 빨강 |
| 사적 헤더 개별 부착 | CR-1 이 전역 장치보다 **먼저** 깨진다(X-D1 하한선 셋째 자리) |

## 5. 알려진 충돌 — β-12 ⓑ 의 multipart 팔

계약 `DocumentFileRequest.workspace_id`(`:2357`)가 *"빈 문자열은 미지정과 같게 다룬다"*
라고 **요구**한다. ⓑ 의 multipart 편집(`DocumentController.parseWorkspaceId` 1줄)은 그
조항을 거짓으로 만들고, 이 레인은 계약 파일을 고치지 않는다(세션 규율 + 에이전트 규약
「계약을 바꾸지 않는다」).

**처분**: **JSON 팔만 닫는다**(계약 조항 0 · 흡수가 가장 넓다 · `""`·`" "` 둘 다).
multipart 팔은 `contract-keeper` 의 조항 개정이 선결이므로 그 사실을 보고한다.
구현이 계약을 앞질러 가면 DC-7 이 「계약과 반대되는 기대」를 들게 되고, 그것이 이 저장소가
반복해 겪은 형태다.

## 6. 세션 규율

파이프 금지 · 변조는 일회용 worktree · Gradle 은 `--no-build-cache --rerun-tasks` ·
요구 모드 게이트 앞에 `KOTLIN_GATE_REACH_RUN_STARTED_AT` 표식 · 핀 값은 판정 장치에 묻는다 ·
`reviews/**`·`00_progress.md`·리더 핀 3종 무수정 · 푸시 금지.
