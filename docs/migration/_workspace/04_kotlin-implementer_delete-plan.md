# C5 — `DELETE /documents/{id}` 즉시 파기 · 선행 P-7·P-8·P-9

착수 HEAD `63a5435`. 정본은 옮겨 적지 않는다 —
작업 단위 `04_kotlin-implementer_documents-plan.md` §7.2 C5 행 · 테스트 행
`04_contract-keeper_documents-test-spec.md` DD-1~DD-7 · 계약
`contracts/easy-doc-v1.yaml` `paths./documents/{document_id}.delete`.

---

## 1. 리서치 — 라이브러리·프레임워크

| 필요 | 표준 구현이 있나 | 판단 |
|---|---|---|
| 소유자 조건이 든 단일 행 삭제 | Spring `JdbcClient.sql(...).update()` | **재사용.** `JdbcWorkspaceRepository.delete` 가 이미 같은 형태(`DELETE … WHERE id = :id AND user_id = :ownerId`)다 |
| 변환·작업 행 동시 파기 | **PostgreSQL FK `ON DELETE CASCADE`** — `V1` 의 `fk_conversions_document_id_documents`, `V5` 의 `fk_conversion_jobs_conversion_id_conversions` | **재사용.** 애플리케이션이 두 번 지우지 않는다. 앱 삭제문을 더하면 그 자체가 결함이다(단위 정의) |
| 204 무본문 응답 | `ResponseEntity.noContent()` | **재사용.** `WorkspaceController.delete` 와 같은 형태 |
| 경로 변수 UUID 변환·공백 흡수 방어 | `@PathVariable ... : UUID` + 기존 `TypedValueSlotInterceptor` | **재사용.** 그 인터셉터는 **비문자열 `@PathVariable` 을 선언에서 유도**하므로 새 엔드포인트가 자동으로 대상에 든다(그 KDoc 「열거하지 않는다」) |
| `/health` 의존 서비스 진단 | Spring Boot Actuator `DataSourceHealthIndicator` | **도입하지 않는다.** 기존 `HealthController` KDoc 이 사유를 적어 두었고 그 사유는 여전히 유효하다 — actuator 는 계약에 없는 `/actuator/**` 를 함께 노출하고 응답 모양(`{"status":"UP"}`)이 계약(`{"status":"ok"}`)과 다르다. **대신 그 지표의 방식(검증 질의 한 방)을 `JdbcClient` 로 그대로 쓴다** |
| SQL 문자열 리터럴 판별 | JSqlParser 등 SQL 파서 | **도입하지 않는다.** 두 가드의 KDoc 이 이미 「파서가 아니라 훑개」인 사유를 적었다(어려운 부분 = Kotlin 소스에서 SQL 조각을 잘라 내는 일을 대신해 주지 않고, 파싱 실패가 새 무성 표면이 된다). 리터럴 걷어내기는 주석 걷어내기와 **같은 훑개 한 곳**에 붙인다 |

## 2. 기구현 확인 — 다시 만들지 않는 것

| 이미 있는 것 | 어디 | C5 에서 |
|---|---|---|
| FK CASCADE 연쇄 확인 | `JdbcDocumentStoreTest::삭제가 작업 행까지 연쇄한다` (원시 SQL 로 잰다) | **확장.** 같은 성질을 **제품 포트 경유**로 다시 재고, 소유 술어·0행 갈래를 더한다 |
| 소유권 404 은닉 규약 | `WorkspaceEndpointReachTest` WD-1·WD-2·WD-3·WD-8 + 시간 축(`interleavedNotFoundMedians`, 문턱 1.5) · `DocumentListReachTest` DL-4·DL-9 | **그대로 따른다.** 바이트·헤더 이름 집합·중앙값 비 세 축 |
| 계약에서 기대값 읽기 | `ContractSpec` (`successStatus`·`responseStatuses`·`pathExampleDetail`·`schemaRequired`·`globalHeaderValues`·`headerConst`·`pathParameters`) | **재사용.** 상태 코드·문구·헤더 값을 코드에 적지 않는다 |
| 문서 저장 대역 | `DocumentSliceFakes`(`InMemoryDocumentRepository` 등) | **확장** — 새 포트 메서드 구현 추가 |
| 인증 경로 목록 | `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` | **추가** `/documents/{document_id}` (그 KDoc 규약: 엔드포인트를 만드는 그 커밋에서 더한다) |
| SQL 주석 걷어내기 | `SqlComments.strip` (중첩 블록 주석까지) | **확장** → 문자열 리터럴 걷어내기를 같은 자리에 더한다(P-7) |
| 소유 술어·봉투 열 인구조사 핀 | `OwnershipPredicateGuardTest.EXPECTED_STATEMENTS`/`EXPECTED_UNGUARDED` · `EnvelopeColumnWriteGuardTest.EXPECTED_FILES`/`EXPECTED_STATEMENTS` | **핀 갱신**(새 DELETE 한 문장) |

## 3. 무엇을 만드는가 — 순서와 검증

### 3.1 C5 본체

| # | 자리 | 내용 |
|---|---|---|
| 1 | `application/document/DocumentPorts.kt` | `DocumentRepository.deleteOwned(ownerId, documentId): Boolean`. KDoc — 소유 조건은 SQL `WHERE`, 변환·작업은 **FK CASCADE 가** 지운다(앱이 두 번 지우지 않는다) |
| 2 | `application/document/DocumentMessages.kt` | `DOCUMENT_NOT_FOUND_MESSAGE` — 계약 404 예시 `not_found` |
| 3 | `application/document/DocumentService.kt` | `delete(ownerId, documentId)` — 한 트랜잭션, 0행이면 `NotFoundException` |
| 4 | `infrastructure/document/JdbcDocumentRepository.kt` | `DELETE FROM documents WHERE id = :id AND user_id = :ownerId` |
| 5 | `api/document/DocumentController.kt` | `@DeleteMapping("/documents/{document_id}")` → 204 무본문·`Content-Type` 없음. **개별 사적 헤더를 붙이지 않는다**(계약 하한선 10곳에 없다 — `DELETE /workspaces/{id}` 와 같은 판단). 전역 부착으로 헤더는 나간다 |
| 6 | `api/auth/AuthenticatedEndpoints.kt` | `/documents/{document_id}` 추가 |
| 7 | `api/support/DocumentSliceFakes.kt` | 대역에 `deleteOwned` — 실물과 같은 축(소유자 조건 포함) |

**테스트 배치** (명세 §5 계층 규약)

| ID | 어디 | 계층 |
|---|---|---|
| DD-1·DD-2·DD-3·DD-4·DD-7 + 시간 축 + 공백 경로 조각 | **새 클래스** `DocumentDeleteReachTest` | C-I·C-R(실 소켓 + 실 PostgreSQL) |
| DD-5 (변환·작업 파기) | `DocumentDeleteReachTest`(DB 관측) + `JdbcDocumentStoreTest`(포트 경유) | C-I |
| DD-6 (UUID 아닌 경로 변수 → 422 배열) | `DocumentDeleteReachTest` | 실 소켓 — MockMvc 로 쓰지 않는다(명세 §5-1) |

**DD-5 의 HTTP 팔은 이 커밋에서 닫히지 않는다.** 명세는 *"삭제 후 그 문서의 변환 조회"* 로
적었고 `GET /conversions/{id}` 는 **C6** 다. 오늘 재는 것은 같은 성질의 **저장 상태**다 —
`conversions`·`conversion_jobs` 행이 사라졌음을 DB 에서 직접 관측한다. HTTP 팔은 C6 의 몫으로
보고에 **미실행**으로 적는다(구현이 없는 자리를 404 로 재면 「핸들러가 없어서 404」가
「파기됐으니 404」로 둔갑한다).

### 3.2 P-7 — 두 SQL 가드의 fail-open

기제가 **하나**다: 판정 정규식이 **SQL 문자열 리터럴 안의 텍스트**를 살아 있는 술어/대입으로
읽는다. 그래서 처방도 하나다.

- `SqlComments` → **`LiveSql`** 로 갈아탄다(`infrastructure/src/test/.../db/LiveSql.kt`).
  `of(sql)` = ⑴ 주석 걷어내기(기존 `strip`, 중첩 깊이까지) → ⑵ **작은따옴표 리터럴·달러 인용
  본문을 공백으로** 지우기. 순서가 규칙이다 — 주석을 먼저 지워야 주석 속 아포스트로피가
  유령 리터럴을 열지 않는다.
- 이름을 바꾸는 이유: 객체 KDoc 이 *"PostgreSQL 이 **무시하는 것만** 지운다"* 로 범위를
  선언했는데 문자열 리터럴은 PostgreSQL 이 무시하지 않는다(데이터다). 기능만 늘리면 그
  선언이 거짓이 된다 — 이 회차가 고치는 것과 정확히 같은 형태의 결함이다.
- **분모 방향은 건드리지 않는다.** 두 가드 모두 대상 발견은 원시 청크로 한다(넓은 쪽이
  fail-closed). 판정만 `LiveSql.of` 를 지난다.
- 처분 무게는 원장 P-7 대로 가른다 — #5(봉투)는 미선언 fail-open 이라 차단, #3(소유)은
  반례 5종 중 4종이 KDoc 「막지 못하는 것」에 이미 선언돼 있어 **미선언 1종만** 닫는다.
  나머지 4종(WHERE 밖·별칭 미결속·`OR TRUE`·결속 대상)은 선언을 남긴 채 둔다.

### 3.3 P-8 — `/health` 계약 위반

계약 `HealthResponse.required: [status, checks]` · `checks` = 의존 서비스 이름 → 불리언 ·
`status` 는 **`checks` 에서 유도**(전부 true → `ok`, 하나라도 false → `degraded`) ·
아무것도 배선되지 않으면 `{}` 이고 `ok` · **항상 200** · 진단 상세를 싣지 않는다.

| 자리 | 내용 |
|---|---|
| `application/health/HealthDiagnosis.kt` | `interface DependencyProbe { val dependency: String; fun isReachable(): Boolean }` · `HealthReport(status, checks)` · 유도 규칙 **한 곳** |
| `infrastructure/health/JdbcDependencyProbes.kt` | `database`·`queue` 두 probe. **같은 DataSource 를 공유한다**(계약 산문). 예외는 false 로 접고 **메시지를 로그에 넣지 않는다** |
| `api/health/HealthController.kt` | `ObjectProvider<DependencyProbe>` 로 받는다 — 후보 0개일 때 컨텍스트가 깨지지 않아야 계약의 `{}` 팔이 성립한다. **폐기된 계약 문면 인용을 지운다**(P-9 축과 같은 자리) |
| `api/health/HealthController.kt` `HealthResponse` | `status` + `checks` |

검증: `HealthContractTest`(의존 0 → `{}`·`ok`, 키 집합을 계약에서 읽어 대조) ·
`HealthDiagnosisTest`(application, Spring 없음 — ok/degraded 유도 양방향) ·
`ApiStartupOnEmptyDatabaseTest`·`ApiStartupOnPythonSnapshotTest`(실 배선 → 두 키 true).
**두 기동 테스트의 `{"status":"ok"}` 정확 일치 단언이 함께 바뀐다** — 응답이 바뀌었으므로.

### 3.4 P-9 — 「이름으로 지목한 것이 실재하는가」 탐지기

`api/src/test/kotlin/kr/easydoc/api/NamedReferenceGuardTest.kt`.

**이름을 열거하지 않는다.** 참조 **형태**(백틱 · `[대괄호]` · `@see`)에서 대상을 뽑고
저장소에서 실재를 확인한다. 분모는 `backend-kotlin/**` 의 `.kt` **주석·KDoc** 과 `.yml`
**주석 줄**이다(주장이 사는 자리).

두 축 모두 **모양으로** 정의한다.

| 축 | 후보 모양 | 해소 집합 | 오늘 적중 |
|---|---|---|---|
| **A** 테스트·프로브 지목 | `Test`·`Probe` 로 끝나는 PascalCase | 저장소 Kotlin 선언(`class`/`object`/`interface`) ∪ 파일 이름 | **3** |
| **B** 계약 확장 노드 지목 | `^x-[a-z0-9-]+$` | 계약 파일의 `x-` 키 **전수**(재귀) | **1** |

**범위를 좁힌 사유는 실측이다.** 후보를 「참조 형태 안의 모든 PascalCase」로 넓히면
미해결이 **147개**가 되고 그중 실제 결함은 **3개**다. 나머지 144는 전부 정당한 참조다 —
외부 라이브러리 타입(`POIFSFileSystem`·`DispatcherServlet`), 계약 스키마·컴포넌트 이름
(`ValidationFailed`·`MaskedItemResponse`), Python 원본 이름(`LLMProvider`·`Protocol`),
detekt 규칙 id(`LongParameterList`), HTTP 헤더 이름(`Accept`·`Vary`), Kotlin/JDK 기본형
(`Int`·`ByteArray`), 백틱에 든 산문 조각(`Too`·`Not`·`Large`). 오탐 98%인 탐지기는 곧
**면제 목록**을 낳고, 그것이 규칙 4 ⑵ 가 금지한 은폐형이다. 그래서 **목록을 좁히지 않고
모양을 좁혔다** — 새 테스트 클래스·새 계약 확장 노드는 이름을 적지 않아도 자동으로 든다.

「계약 **산문**을 인용한 문면」은 이 탐지기의 축이 **아니다**. 측정: KDoc 인용 블록(`> `)은
저장소 전체에 **9자리**뿐이고 그중 8은 자기 정의의 재기술(외부 앵커가 없어 대조 대상이
없다), 나쁜 자리는 `HealthController` 하나다. 그 하나는 P-8 에서 손으로 지우고,
같은 KDoc 이 인용하던 성질을 **`HealthContractTest` 가 계약을 읽어** 재게 만든다 —
문면이 아니라 성질이 측정 대상이 된다. 산문 축을 기계화하려면 인용에 앵커를 붙이는 규약이
필요하고, 그 규약 신설은 이 단위 밖이라 보고에 남긴다.

빈 분모는 통과가 아니다 — 형제 가드와 같은 규율로 후보 0건이면 빨강이다.

## 4. 음성 대조 계획

| 변이 | 빨개져야 하는 것 | 깨지지 않아야 하는 것 |
|---|---|---|
| `deleteOwned` SQL 에서 `AND user_id = :ownerId` 제거 | DD-2·DD-3(타인 문서가 지워진다) · `OwnershipPredicateGuardTest`(핀에 새 항목) | 나머지 문서 케이스 |
| `deleteOwned` 0행을 성공으로(예외 없이) 처리 | DD-4(재요청 204) · DD-2 | 삭제 성공 케이스 |
| 앱이 변환을 별도로 지우는 문장을 더한다 | `JdbcDocumentStoreTest` 문장 수 단언 | — |
| `LiveSql` 의 리터럴 걷어내기 제거 | 두 가드의 **리터럴 fail-open 케이스** | 주석 케이스 6건 |
| `HealthController` 에서 `checks` 제거 | `HealthContractTest` 키 집합 · 기동 2건 | — |
| `HealthDiagnosis` 유도를 상수 `ok` 로 | `HealthDiagnosisTest` degraded 갈래 | ok 갈래 |
| 존재하지 않는 `…Test` 이름을 주석에 심는다 | `NamedReferenceGuardTest` 축 A | 축 B |
| 존재하지 않는 `x-…` 노드를 주석에 심는다 | 축 B | 축 A |
| `NamedReferenceGuardTest` 분모를 빈 디렉터리로 | 빈 분모 판정 | — |

판정 기준은 **「겨눈 장치가 그 자리를 짚었는가」**다. 변이가 컴파일·ktlint 를 깨면 그 회차는
판정으로 쓰지 않고 변이를 고쳐 다시 잰다.

## 5. 하지 않는 것

- 계약 파일 수정(일시 변조 후 sha256 대조 복원만).
- 스키마 변경. FK CASCADE 는 이미 `V1`·`V5` 에 있다.
- 교차 리뷰 미배정 항목(#4 분모의 SQL 형태 · #6 봉투 가드 분모의 `src/test` · #7 핀 해상도)
  — 마감이 C5 로 적혀 있으나 리더가 이 회차에 배정하지 않았다. 보고에 미처리로 올린다.
- `FLOOR_TEST_CLASSES`·`MIN_TEST_CLASSES`·`MIN_FLOOR_CLASSES`·`MIN_TESTS_IN_FLOOR_CLASS` 수정.
  새 클래스 둘의 바닥 편입 필요성은 **보고만** 한다.
