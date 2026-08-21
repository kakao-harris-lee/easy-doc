# C5 — `DELETE /documents/{id}` 즉시 파기 · 선행 P-7·P-8·P-9 산출물

착수 HEAD `63a5435`. 계획은 `04_kotlin-implementer_delete-plan.md`.
정본은 옮겨 적지 않는다 — 작업 단위 정의는 `04_kotlin-implementer_documents-plan.md` §7.2 C5 행,
테스트 행은 `04_contract-keeper_documents-test-spec.md` DD-1~DD-7, 계약은
`contracts/easy-doc-v1.yaml`.

---

## 1. 완성 모듈과 자리

| 자리 | 무엇 | 대응 Python 원본 |
|---|---|---|
| `application/document/DocumentPorts.kt` | `DocumentRepository.deleteOwned(ownerId, documentId): Boolean` | `app/repositories/documents.py::delete` |
| `application/document/DocumentMessages.kt` | `DOCUMENT_NOT_FOUND_MESSAGE` | `app/services/documents.py` 의 404 문구 |
| `application/document/DocumentService.kt` | `delete(ownerId, documentId)` — 한 트랜잭션, 0행 → `NotFoundException` | `app/services/documents.py::delete_document` |
| `infrastructure/document/JdbcDocumentRepository.kt` | `DELETE FROM documents WHERE id = :id AND user_id = :ownerId` | 같음 |
| `api/document/DocumentController.kt` | `@DeleteMapping("/documents/{document_id}")` → 204 무본문 | `app/api/documents.py::delete_document` |
| `api/auth/AuthenticatedEndpoints.kt` | `/documents/{document_id}` 등재 | `app/api/deps.py` 의 의존성 |
| `application/health/HealthDiagnosis.kt` (신규) | `DependencyProbe` 포트 · `HealthReport` · `status` 유도 | **원본 없음 — 요구사항 신규**(`app/main.py::health` 는 상수 응답) |
| `infrastructure/health/HealthProbeConfiguration.kt` (신규) | `database`·`queue` probe | 같음 |
| `api/health/HealthController.kt` | `status` + `checks` · 폐기 인용 제거 | 같음 |
| `infrastructure/db/LiveSql.kt` (신규, `SqlComments.kt` 대체) | 주석 + **문자열 리터럴** 걷어내기 | 하네스 — 원본 없음 |
| `api/NamedReferenceGuardTest.kt` (신규) | P-9 탐지기 두 축 | 하네스 — 원본 없음 |

**의도적으로 Python 과 다르게 구현한 것**

1. **`/health` 가 진단한다.** Python 은 상수 `{"status": "ok"}` 를 낸다. 계약이
   *"Kotlin 은 진단하는 쪽으로 구현한다"* 로 명시했고(2026-08-12 개정, `x-change-policy` G2),
   `CLAUDE.md` 가 *"Python 출력을 정답으로 삼지 않는다"* 로 못박은 자리다. 포팅이 아니라 **신규**.
2. **`Boolean` 반환.** 저장소가 「지울 것이 없었다」를 삼키면 404/204 를 가를 수 없고, 계약이
   요구한 **비멱등** 동작(DD-4)이 성립하지 않는다.
3. **애플리케이션이 변환을 지우지 않는다.** FK CASCADE(`V1`·`V5`)가 세 테이블을 비운다.

**미포팅 잔여**

- **DD-5 의 HTTP 팔** — 명세는 *"삭제 후 그 문서의 변환 조회 → 404"* 인데
  `GET /conversions/{id}` 가 **C6** 다. 구현이 없는 자리를 404 로 재면 「핸들러가 없어서
  404」가 「파기됐으니 404」로 둔갑하므로 그 팔을 **쓰지 않았다**. 같은 성질을 저장 상태로
  잰다(§3). **C6 가 이 팔을 닫아야 한다.**
- **리뷰 교차 대조 #4·#6·#7** — 마감이 C5 로 적혀 있으나 이 회차에 **배정되지 않았다**(§7).

---

## 2. DD-1~DD-7 전건 결과

전건 **실행·통과**. 클래스는 `api/.../DocumentDeleteReachTest.kt`(C-R·C-I, 실제 소켓 + 실 PostgreSQL).

| ID | 케이스 | 결과 |
|---|---|---|
| **DD-1** | 204 · **본문 길이 0** · 사적 헤더 2종 **있음**(개수까지) | 통과 |
| DD-1 인접 | 204 에 `Content-Type` 없음 | 통과 |
| **DD-2** | 타인 소유 → 404 · **403 아님 명시 단언** · `detail` == 계약 404 예시 | 통과 |
| DD-2 인접 | 404 뒤에도 타인 문서·변환이 **그대로** | 통과 |
| **DD-3** | 없는 것 ⇄ 타인 것: 상태·**본문 바이트**·헤더 이름 집합 동일 | 통과 |
| DD-3 셋째 축 | 두 404 의 응답 시간 중앙값 비 < 1.5 (교차 측정, 표본 21) | 통과 |
| **DD-4** | 삭제 직후 재요청 → 404(204 아님) · `detail` == 계약 예시 | 통과 |
| **DD-5** | 문서·변환·**작업** 행이 함께 사라진다(저장 상태 관측) | 통과 — **HTTP 팔은 C6** |
| DD-5 인접 | 연쇄가 타인 행까지 넘지 않는다 | 통과 |
| **DD-6** | UUID 아닌 경로 변수 → 422 · `detail` **배열** · 항목 키 == `ValidationErrorItem.required` | 통과 |
| DD-6 인접 | 공백뿐인 경로 조각(`%20`) → 422(400 아님) | 통과 |
| **DD-7** | 토큰 없음 → 401 · `WWW-Authenticate` | 통과 |
| DD-7 인접 | 위조 토큰 + 잘못된 UUID → **401**(422 아님, X-A3) | 통과 |
| DD-7 인접 | 토큰 없는 삭제가 **아무것도 지우지 않는다** | 통과 |

**미실행 0건.** 단 DD-5 는 명세가 지정한 **HTTP 관측면이 아니라** 저장 상태로 쟀다 —
그 사실을 위 표와 §1 잔여에 적었다.

**「계약에서 읽었다」의 증명**: 계약 파일의 `not_found` 예시 문구를 일시 변조하니
**DD-2·DD-4 만** 빨개졌다(§6 표 마지막 줄). 리터럴을 복제한 단언이면 초록이었을 자리다.

---

## 3. FK CASCADE 확인 방법 · 「앱이 두 번 지우지 않음」의 근거 · 파기의 DB 쪽 근거

### 3.1 연쇄가 실제로 도는가

`V1__python_schema_baseline.sql` 의 `fk_conversions_document_id_documents … ON DELETE CASCADE` 와
`V5__conversion_jobs.sql` 의 `fk_conversion_jobs_conversion_id_conversions … ON DELETE CASCADE` 가
이어져 있다. **선언을 읽는 것으로 끝내지 않고** 두 층에서 실행으로 확인한다.

| 어디 | 무엇 |
|---|---|
| `DocumentDeleteReachTest::삭제가 변환과 작업 행까지 파기한다` | HTTP 로 삭제하고 **세 테이블 행 수**를 DB 에서 직접 센다 |
| `JdbcDocumentStoreTest::포트 경유 삭제가 한 문장으로 연쇄한다` | 제품 포트로 삭제하고 문서·변환·작업 행이 전부 사라짐을 확인 |
| `JdbcDocumentStoreTest::삭제가 작업 행까지 연쇄한다`(기존) | 손 SQL 로 스키마의 성질만 확인 |

`conversion_jobs` 는 문서 식별자를 갖지 않으므로(작업 id 가 변환 id 다) **조인으로 센다** —
`conversion_jobs j JOIN conversions c ON c.id = j.conversion_id WHERE c.document_id = …`.
변환 행이 함께 사라지므로 삭제 후 조인 결과가 0 이다.

### 3.2 애플리케이션이 두 번 지우지 않는다

근거 셋이고, 셋 다 **실행**이다.

1. **문장 수 = 1** — `JdbcDocumentStoreTest::포트 경유 삭제가 한 문장으로 연쇄한다` 가
   `CountingDataSource` 로 유스케이스가 내는 문장을 센다. 변환 삭제문을 더하면 2, 「읽고 나서
   지운다」로 바꾸면 2 다. 연쇄는 서버 안에서 일어나 클라이언트 문장 수에 나타나지 않는다 —
   그것이 CASCADE 를 고른 이유이자 이 정수가 근거가 되는 이유다.
2. **대역 관측** — `DocumentServiceTest::삭제가 변환을 따로 지우지 않는다` 가 변환 저장소·큐
   대역이 **불리지 않았음**을 단언한다(Spring·DB 없이).
3. **시그니처** — `ConversionRepository` 에 삭제 메서드가 **없다.** 앱이 변환을 지우려면 포트를
   먼저 늘려야 하고 그 편집이 diff 에 드러난다.

### 3.3 파기가 실제로 일어났음의 DB 쪽 근거 (응답이 아니라 저장 상태)

`DocumentDeleteReachTest::삭제가 변환과 작업 행까지 파기한다` 가 **삭제 전에도** 잰다:

- `documents` 1행 · `conversions` 1행 · `conversion_jobs` 1행
- `octet_length(source_text_encrypted) > 0` — **지울 것이 실제로 있었다**

삭제 후 셋 다 0. 이 전/후 쌍이 없으면 「삭제 후 0건」이 「애초에 0건」과 구분되지 않는다.
행이 사라졌다는 것이 곧 **암호문·봉투가 사라졌다**는 것이다 — 표시만 남기는 구현이면 첫
단언이 1 이다. 「즉시 파기는 표시가 아니라 파기다」의 관측면이 여기다.

---

## 4. 404 은닉의 세 축

| 축 | 어디 | 어떻게 |
|---|---|---|
| **바이트** | `DocumentDeleteReachTest::없는 것과 남의 것이 구분되지 않는다` | 상태 코드 + `response.body()` **문자열 동일** |
| **헤더** | 같은 케이스 | 헤더 **이름 집합** 동일(`date` 만 제외 — 값이 매 응답 달라진다) |
| **시간** | `DocumentDeleteReachTest::소유권 404 의 응답 시간이 갈리지 않는다` | 두 경로를 **교차**로 21회씩(경로별 첫 건 버림), 중앙값 비 < **1.5**. **1.5배 이상의 격차를 잡는다** — 양성 대조로 확인(§9.2) |

문턱 1.5 는 `WorkspaceEndpointReachTest` 와 **같은 값**이다. 같은 성질을 재는 두 게이트의
문턱이 다르면 다음 사람이 어느 쪽을 기준으로 삼을지 알 수 없다.

**시간 축이 잡지 못하는 것을 그 KDoc 에 적었다** — 형제 게이트의 실측대로, 소유 조건을 SQL
`WHERE` 에서 빼고 「읽은 뒤 Kotlin 에서 비교」로 바꾼 변이는 이 문턱을 통과한다(비
1.013·1.090·1.051). 그 변이를 잡는 것은 **구조 축**이고 둘이다:
`OwnershipPredicateGuardTest` 의 정확 열거 핀과 `JdbcDocumentStoreTest` 의 문장 수 단언.
음성 대조 M1 이 그 셋 중 어디가 짚는지를 실측으로 보인다(§6).

삭제는 파괴적이라 형제 게이트처럼 같은 자원을 반복해 두드릴 수 없다. **두 경로 모두 404 로
끝나는 요청만** 쓰는 것이 그 해결이고, 재려는 것이 정확히 그 두 404 의 차이이기도 하다.

---

## 5. 선행 항목 셋

### 5.1 P-9 — 「이름으로 지목한 것이 실재하는가」 탐지기

`api/src/test/kotlin/kr/easydoc/api/NamedReferenceGuardTest.kt`. 이름을 열거하지 않고
**참조 형태**(백틱 · `[대괄호]` · `@see`)에서 대상을 뽑아 실재를 확인한다. 분모는
`backend-kotlin` 아래 `.kt` 의 **주석·KDoc** 과 `.yml` 의 **주석 줄** — 주장이 사는 자리다.

| 축 | 후보 모양 | 해소 집합 |
|---|---|---|
| **A** | `Test`·`Probe` 로 끝나는 PascalCase | 저장소 Kotlin 선언(class/object/interface) ∪ **파일 이름** |
| **B** | `x-` + 소문자·숫자·붙임표 | `ContractSpec.extensionNodeNames()` — 계약의 `x-` 키 **재귀 전수**(62개) |

**오늘 실제로 짚은 자리 = 4.** 전부 실 결함이었고 전부 고쳤다.

| 자리 | 지목한 이름 | 처분 |
|---|---|---|
| `application/.../DocumentPorts.kt` | 없는 테스트 이름 | 실제 강제자 `DocumentContractNodeTest`(P-39 케이스)로 정정 |
| `api/src/main/resources/application.yml` | 없는 테스트 이름 | 실제 강제자 `DocumentEndpointReachTest`(multipart 상한 대조)로 정정 |
| `api/.../support/AuthSliceBeans.kt` | 없는 테스트 이름 | 실제 클래스 `JdbcWorkspaceRepositoryTest` 로 정정 |
| `api/.../CorsContractTest.kt` | 계약에 없는 확장 노드 | 현재 노드 `x-cors.x-unhandled-500-cors` 로 정정 |

원장 L-⑪ P-9 가 실측한 다섯 자리 중 넷이 이 넷이고, 다섯째(`DocumentPorts.kt:57-60` 거짓
전칭)는 이전 회차에 이미 문면이 고쳐져 있었다. **이 세션이 하나를 더 만들었고 탐지기가
그것도 잡았다** — `DocumentSliceFakes` KDoc 이 아직 만들지 않은 `DocumentDeleteReachTest` 를
지목한 상태였다(그 자체가 이 탐지기의 첫 실사용 결과다).

**범위를 좁힌 사유 — 실측이다.** 후보를 「참조 형태 안의 모든 PascalCase」로 넓히면 미해결이
**147개**이고 실제 결함은 **3개**다. 나머지 144는 정당한 참조다: 외부 라이브러리 타입
(`POIFSFileSystem`·`DispatcherServlet`), 계약 스키마·컴포넌트 이름(`ValidationFailed`·
`MaskedItemResponse`), Python 원본 심볼(`LLMProvider`·`Protocol`), detekt 규칙 id
(`LongParameterList`), HTTP 헤더 이름(`Accept`·`Vary`), Kotlin/JDK 기본형(`Int`·`ByteArray`),
백틱에 든 산문 조각(`Too`·`Not`·`Large`). **오탐 98% 인 탐지기는 곧 면제 목록을 낳고**, 그것이
규칙 4 ⑵ 가 금지한 은폐형이다. 그래서 **목록을 좁히지 않고 모양을 좁혔다** — 새 테스트
클래스와 새 계약 확장 노드는 이름을 적지 않아도 자동으로 후보에 든다.

**덮지 않는 것**(그 파일 KDoc 에 전부 적었다): 계약 **산문** 인용 · 이름은 실재하나 주장이
거짓인 경우 · SCREAMING_CASE 상수와 함수·프로퍼티 이름 · `docs` 산문 · 이 파일 자신의 삭제.
산문 축의 측정 근거: KDoc 인용 블록은 저장소 전체에 **9자리**이고 8은 자기 정의의 재기술이라
대조할 앵커가 없다. 나쁜 자리 하나가 `HealthController` 였고 P-8 에서 손으로 걷어냈다 —
그 KDoc 이 주장했던 성질을 이제 `HealthContractTest` 가 **계약을 읽어** 잰다. 개선 후보는
백로그 **B-22**.

**부수 규약 하나**: 폐기된 이름을 백틱으로 다시 적으면 이 가드가 정당하게 잡는다(실측 —
이 회차의 정정 주석 하나가 그 자리에서 빨개졌다). 이력은 참조 형태 없이 산문으로 적거나
계약 `x-changelog` 를 가리킨다. 그 규약을 탐지기 KDoc 에 적었다.

### 5.2 P-7 — SQL 가드 둘의 fail-open

**기제가 하나**였다: 판정 정규식이 **SQL 문자열 리터럴 안의 텍스트**를 살아 있는 술어/대입으로
읽는다. 그래서 처방도 하나다.

- `SqlComments` → **`LiveSql`** 로 갈아탔다. `of(sql)` = ⑴ 주석 걷어내기(중첩 깊이까지) →
  ⑵ **작은따옴표 리터럴·달러 인용 본문을 공백으로**. 순서가 규칙이다 — 주석을 먼저 지워야
  주석 속 아포스트로피가 유령 리터럴을 열지 않는다.
- **이름을 바꾼 이유**: 종전 객체의 KDoc 이 범위를 *"PostgreSQL 이 **무시하는 것만** 지운다"*
  로 선언했는데 문자열 리터럴은 무시되지 않는다(값으로 평가된다). 기능만 늘리면 그 선언이
  거짓이 되고, 그것이 이 회차가 고치는 결함과 **같은 형태**다.
- **분모 방향은 건드리지 않았다.** 두 가드 모두 대상 발견은 원시 청크로 한다(넓은 쪽이
  fail-closed). 판정만 `LiveSql.of` 를 지난다. 그 갈라치기를 두 KDoc 에 다시 적었다.

| 항목 | 리더 판정 | 이 회차 처분 |
|---|---|---|
| **#5 봉투 가드**(문자열 리터럴) | **차단 유지** — 미선언 fail-open, 결과가 복구 불가 행 | **닫았다.** 케이스 3건 신설(리터럴 · `''` escape · 리터럴 뒤 살아 있는 대입) |
| **#3 소유 가드** | **Major** — 반례 5종 중 4종이 KDoc 에 이미 선언, **미선언 1종만** 차단 축 | **미선언 1종을 닫았다.** 케이스 2건 신설. 선언된 넷은 KDoc 에 **갈래를 명시**해 남겼다(백로그 **B-23**) |

핀 갱신: `OwnershipPredicateGuardTest.EXPECTED_STATEMENTS` 에 새 `DELETE [documents]` 한 줄.
**`EXPECTED_UNGUARDED` 에는 들어가지 않는다** — 소유 술어가 있기 때문이고, 두 목록이 서로
다른 사건을 잡는다는 것이 여기서 관측된다. 봉투 가드의 `EXPECTED_FILES`·`EXPECTED_STATEMENTS`
는 변화 없다(새 문장이 `UPDATE` 가 아니다).

### 5.3 P-8 — `/health` 계약 위반

**실 위반 둘을 함께 고쳤다.**

1. **필드 부재.** 계약 `HealthResponse.required: [status, checks]` 인데 구현은
   `HealthResponse(val status: String)` 였다. 이제 `checks: Map<String, Boolean>` 를 싣고
   `status` 를 **`checks` 에서 유도**한다(전부 true → `ok`, 하나라도 false → `degraded`).
   `status` 를 인자로 받는 통로를 두지 않았다 — 받을 수 있으면 계약이 금지한 「둘이 어긋난
   응답」을 만들 수 있다.
2. **폐기된 계약 문면 인용.** KDoc 이 *"v1 은 현행대로 동결"* 을 인용했는데 그 문면은
   2026-08-12 개정으로 **정반대**가 됐다. 인용을 걷어내고 계약의 **자리만** 가리킨다.

배선: `application/health/HealthDiagnosis.kt`(포트 + 유도 규칙, Spring 없음) ·
`infrastructure/health/HealthProbeConfiguration.kt`(`database`·`queue` — 같은 DataSource 를
공유하지만 **별개 진단**이다. 커넥션은 살아 있는데 `conversion_jobs` 가 없거나 권한이 없는
상태가 실재하고, 그것이 배포 진단으로서 이 엔드포인트의 존재 이유다) ·
`api/health/HealthController.kt`(`ObjectProvider` 로 받는다 — 후보 0개일 때 컨텍스트가 깨지면
계약의 `{}` 팔을 잴 자리가 사라진다).

**진단 상세를 싣지 않는다**: probe 반환 타입이 `Boolean` 이라 예외 메시지·호스트·버전이
들어갈 통로가 타입에 없다. 실패는 `false` 로 접고 **아무것도 로깅하지 않는다** — JDBC 예외
메시지에 접속 URL 이 실리고, 이 엔드포인트는 인증 없이 누구나 부르므로 호출 빈도가 통제되지
않는다.

**두 팔을 서로 다른 층이 잰다**: `HealthContractTest`(DataSource 없음 → `{}`·`ok`, 키 집합을
계약에서 읽어 대조) · `HealthDiagnosisTest`(유도 규칙 7건, Spring 없음) ·
`ApiStartupOnEmptyDatabaseTest`·`ApiStartupOnPythonSnapshotTest`(실 배선 → 두 키 **true**;
키 이름도 계약 `checks.examples` 에서 읽는다). 후자 둘의 종전 `{"status":"ok"}` 정확 일치
단언을 갱신했다 — 응답이 바뀌었으므로.

**이 변경이 다른 게이트를 둘 흔들었고, 둘 다 설계대로 흔들렸다.**
⑴ `SensitiveToStringReachTest` 의 선언 전수 핀 52 → 53(`HealthReport` 신설). ⑵ 같은 탐지기가
`checks` 를 **「모르는 타입」으로 끊었다** — 저장소 최초의 `Map` 필드다. `slotFor` 에 맵 갈래
(`mapSlot` — 키와 값 **양쪽**에 표본을 심는다)를 더했다. 그 끊김이 그 탐지기의 설계다: 새
타입이 들어오면 조용히 검사 밖에 남는 대신 게이트가 빨개진다.
⑶ `FrameworkErrorContractTest` 의 `/health` 본문 단언을 STRICT → LENIENT 로 바꿨다 —
그 케이스가 재는 것은 **내용 협상이 없다**는 것뿐이고 본문 모양은 계약을 읽는
`HealthContractTest` 의 몫이다(리터럴을 두면 계약이 필드를 더할 때 협상 축이 함께 빨개진다).

---

## 6. 음성 대조 표

**판정 기준**: 「무언가 빨개졌다」가 아니라 **「겨눈 장치가 그 자리를 짚었는가」**.
전 회차 **컴파일 손상 0** — 판정이 오염된 회차가 없다.
복원은 `cp`·`git stash` 없이 바이트 백업 + `Path.write_bytes`, **전건 sha256 일치**.

| 변이 | 겨눈 것 | 실제로 짚은 것 | 판정 |
|---|---|---|---|
| **M1** `deleteOwned` SQL 에서 `AND user_id = :ownerId` 제거 | DD-2·DD-3 + 소유 가드 핀 | DD-2(2건) · DD-3 · `JdbcDocumentStoreTest` 소유·비용 케이스 · **`OwnershipPredicateGuardTest` 핀** | **적중** — 시간 축은 침묵(그 KDoc 이 예고한 대로) |
| **M2** 0행을 예외 없이 성공 처리 | DD-4 · DD-2 | DD-2 · **DD-4** · `DocumentServiceTest::지울 것이 없으면 404` | **적중** |
| **M3** `LiveSql.of` 에서 리터럴 걷어내기 제거 | 두 가드의 **리터럴 케이스만** | 봉투 가드 2건(리터럴 · `''` escape) · 소유 가드 1건(리터럴) | **적중** — 주석 케이스 6건과 「리터럴 뒤 살아 있는 술어/대입」 2건은 **초록 유지**(과잉 탐지 아님을 함께 증명) |
| **M4** `/health` 진단을 상수(빈 목록)로 | 기동 2건 | `ApiStartupOnEmptyDatabaseTest` · `ApiStartupOnPythonSnapshotTest` | **적중** — `HealthContractTest` 는 **초록 유지**(그 팔이 `{}` 이므로 옳다. 두 팔이 실제로 분리됨) |
| **M5** `status` 유도를 상수 `ok` 로 | `HealthDiagnosisTest` degraded 갈래 | degraded 2건(불리언 갈래 · 던지는 probe 갈래) | **적중** — ok 갈래 4건 초록 유지 |
| **M6** 없는 `…Test` 이름을 KDoc 에 심는다 | P-9 축 A | **축 A 만** | **적중** — 축 B 초록(두 축 독립) |
| **M7** 없는 `x-…` 노드를 KDoc 에 심는다 | P-9 축 B | **축 B 만** | **적중** — 축 A 초록 |
| **계약 변조** `not_found` 예시 문구 교체 | 「계약에서 읽었다」의 증명 | **DD-2 · DD-4 만** | **적중** — 리터럴 복제라면 초록이었을 자리 |

sha256 대조(변이 전 → 복원 후, 전건 일치):

| 파일 | sha256 |
|---|---|
| `JdbcDocumentRepository.kt` | `1825f9598784f6f08b6640de5fb5006935af3199d013d77570eedb4006aaa3c6` |
| `DocumentService.kt` | `a93b54c063a5746975c4a0e7f230b890dcb0fdf446853dd5dd1e7ca9edd29fe4` |
| `LiveSql.kt` | `e8168feb2c0c86db3b04681985e154107b9505d9435392bc4e34c267f8e48bdb` |
| `HealthController.kt` | `939a2244ad17ca3a94d677cc26243f8b369344ea7fa460cfe3a75d2ba2b4b003` |
| `HealthDiagnosis.kt` | `e446336ce73b19b94b191ec0edac6842710d8580366e554cb163c7b30f5f1a04` |
| `DocumentPorts.kt` | `e350d94fa7eb6a9b7d98cef99bedbc399158d24568379c5a03de9e290f4c3621` |
| `contracts/easy-doc-v1.yaml` | `5963dc5b89b13b91e44a9bb2da2b35edcd58692a60c9ae5588c739510a9576da` |

---

## 7. 그래도 무엇을 증명하지 못하는가 — **악용 비용**으로 적는다

| 남은 것 | 왜 안 닫혔나 | 악용 비용 | 결과 크기 |
|---|---|---|---|
| **DD-5 의 HTTP 팔** — 「변환 조회가 404」 | `GET /conversions/{id}` 가 C6 | — (사건이 아니라 미측정) | 저장 상태 축이 이미 같은 성질을 잰다. **C6 가 닫아야 한다** |
| **소유 술어 판정의 선언된 fail-open 4갈래**(`WHERE` 밖·별칭·`OR TRUE`·결속 대상) | 훑개의 한계. 파서는 새 무성 표면을 만든다 | **한 줄 편집**(`… OR TRUE`) | **남의 문서 노출.** 다만 넷 다 「우연히 그렇게 되는」 형태가 아니라 SQL 안에 남아 diff 에 보인다 → 백로그 B-23 |
| **`LiveSql` 이 조립 SQL 을 못 읽는다** | 문자열 연결·보간으로 만든 SQL 은 훑개 밖 | **한 줄 편집** | 두 가드 KDoc 첫 항목이 이미 선언한 한계 |
| **P-9 산문 인용 축** | 앵커 규약이 없고 오늘 대상이 1건 | **한 줄 편집** | 「읽는 사람이 잘못된 근거를 믿는다」. 사용자 데이터가 아니다 → 백로그 B-22 |
| **P-9 이름은 실재하나 주장이 거짓인 경우** | 「그 클래스가 그 성질을 재는가」는 변이 테스트의 물음 | **한 줄 편집** | 같음 → 백로그 B-19 |
| **시간 축이 「소유 조건이 SQL 을 떠났는가」를 못 잡는다** | 인덱스 적중/불발 차이가 밀리초 잡음에 묻힌다(형제 게이트 3회 실측) | **큰 diff**(질의 구조 변경) | 구조 축 둘이 덮는다(M1 이 실측) |
| **204 응답에 개별 헤더 부착이 없다** | 계약 하한선 10곳에 이 오퍼레이션이 없다 | **한 줄 편집**(전역 필터 순서 변경) | DD-1 이 개수까지 단언하므로 그 편집은 즉시 빨개진다 |
| **삭제 후 WAL·백업의 잔여** | 「행이 사라졌다」는 논리 삭제까지다. 물리 파기(`VACUUM`·PITR 보존)는 이 층 밖 | 운영 접근 필요 | **범위 밖** — 운영 정책이 정할 자리이고, 계약이 약속한 것은 서비스 응답면의 파기다 |
| **#4 소유 술어 분모의 SQL 형태**(`DELETE…USING`·`UPDATE…FROM`·콤마 조인) | **이 회차에 배정되지 않았다** | 큰 diff | 오늘 새 `DELETE` 는 `DELETE FROM documents` 라 분모에 **든다**(핀에 올랐다) |
| **#6 봉투 가드 분모에 `src/test` 혼재** | 배정되지 않았다 | 한 줄 편집 | 총계 상쇄 가능 |
| **#7 핀 해상도**(동형 두 문장의 맞바꿈) | 배정되지 않았다 | 큰 diff | 새 `DELETE` 는 그 파일에서 유일한 `DELETE` 라 오늘 맞바꿀 짝이 없다 |

**보고 사항 둘 (리더 판정 필요)**

1. **바닥 편입 후보 셋** — 편입은 리더가 판정한다. 사유만 적는다.
   - `NamedReferenceGuardTest` — P-9 종류의 **유일한** 강제자다. 사라지면 「이름으로 지목한
     것이 실재한다」의 결론이 함께 무너지고, 원장 L-⑪ P-9 판정이 인용할 근거가 없어진다.
     바닥 목록의 기준(「다른 판정의 근거로 인용되는 탐지기」)에 정면으로 든다.
   - `DocumentDeleteReachTest` — DD-2·DD-3 과 시간 축이 **즉시 파기 경로의 소유권 은닉 정본
     케이스**다(`WorkspaceEndpointReachTest` WR-3·WR-4 와 같은 근거이고, 이쪽은 결과가
     **복구 불가**라는 점만 다르다).
   - `HealthDiagnosisTest` — 넣지 않는 편을 권한다. 계약 조항 하나의 유도 규칙이고 다른
     판정이 그것을 인용하지 않는다.
   `MIN_TESTS_IN_FLOOR_CLASS` 는 **건드리지 않았다**(키 집합이 바닥과 정확 일치로 묶여 있어
   편입 판정과 함께 정해야 한다).
2. **`JdbcDocumentStoreTest` 가 detekt `LargeClass` 문턱에 닿았다.** 이 회차에 케이스를
   다섯에서 **둘로 병합**해 통과시켰다(병합 자체는 손실이 없다 — 단언은 전부 남았다). 다음
   단위가 이 클래스에 케이스를 더하면 분할이 필요하고, 분할은 두 번째 Testcontainers DB 와
   40줄 설정 복제를 뜻한다. 그 시점의 판단을 미리 올린다.

---

## 8. 실행한 검사 전부와 종료 코드

**자기 커밋 뒤 재실행 결과는 §8.2 다** — 커밋 전 결과만 적고 끝내지 않는다.

### 8.1 커밋 전

| 검사 | 명령 | 종료 코드 |
|---|---|---|
| Kotlin 전 게이트 | `./gradlew ktlintCheck detekt build moduleBoundaryCheck parityHarness --continue --rerun-tasks` | **0** (330 tests) |
| 게이트 도달(요구 모드) | `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 uv run pytest tests/test_kotlin_gate_reach.py` | **0** (149 passed) |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **0** (BLOCK 0) |
| Python 린트 | `uv run ruff check .` | **0** |
| Python 타입 | `uv run mypy . .claude` | **0** (139 files) |
| Python 테스트 | `uv run pytest` | **0** (1478 passed · 68 skipped · 5 deselected · 5 xfailed) |

`tests/golden` 은 별도로 돌리지 않았다 — 이 회차는 프롬프트·스타일 규칙·LLM 설정을 건드리지
않았고, 위 `uv run pytest` 가 그 디렉터리를 포함한다.

의존성은 **추가하지 않았다.** version catalog·락파일 변경 0 — `git status` 로 확인했고,
그러므로 compile ↔ test 클래스패스 갈림도 **0**(갈릴 재료가 없다).

계약 파일: 음성 대조로 **일시 변조 후 복원**했고 sha256 이 일치한다(§6). `git status` 에
`contracts/` 변경 없음.

### 8.2 커밋 후 재실행

| 검사 | 종료 코드 |
|---|---|
| `./gradlew ktlintCheck detekt build moduleBoundaryCheck parityHarness --continue --rerun-tasks` | **0** |
| `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 uv run pytest tests/test_kotlin_gate_reach.py` | **0** |
| `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **0** |
| `uv run ruff check .` · `uv run mypy . .claude` · `uv run pytest` | **0** / **0** / **0** |

---

# R-10 — stop-time codex 게이트 지적 둘 (거짓 초록 허용)

착수 HEAD `a427855`. codex 가 여섯 번째로 막은 자리이고, 둘 다 **새로 세운 강제자 자신**의 결함이다.

## 9.1 R-10-① 축 B 가 점 경로를 검증하지 않았다

**결함** — `Scanner` 가 참조를 조각으로 나눠 **평탄한 이름 집합**(62개)에 대조했다. 그래서
부모·자식으로 이어진 참조에서 자식이 **계약 어디에든** 있으면 통과했다 — 그 부모 아래에
없어도 초록이다. **노드 이름의 변경·이동이 이 종류의 가장 흔한 형태**이므로, 부모가 살아
있고 자식이 옮겨 간 참조가 정확히 그 구멍으로 빠진다.

**측정 (전/후)** — 실재하는 최상위 노드 둘(`x-cors` + `x-changelog`)을 부모·자식으로 이어
붙인 가짜 경로를 `DocumentPorts.kt` 주석에 심었다.

| 회차 | 코드 | 결과 |
|---|---|---|
| **고치기 전** (`a687de8` 내용으로 두 파일 복귀) + `--rerun-tasks` | 평탄 이름 대조 | **exit 0 · 빨개진 케이스 없음 — 거짓 초록 확인** |
| **고친 뒤** + `--rerun-tasks` | 경로 대조 | **exit 1 · 축 B 가 `x-cors.x-changelog` 를 지목** |

**측정 자체가 한 번 무효였다 — 그 사실을 적는다.** 첫 두 시도는 `--rerun-tasks` 없이 돌려
Gradle 이 `:api:test` 를 **UP-TO-DATE 로 건너뛰었고**, 실패 줄만 필터하니 「exit 0」이 나와
초록으로 보였다. 고친 뒤의 코드로도 같은 결과가 나온 덕에 알아챘다. 이것이 이 저장소가
전에 밟은 **리포트 축** 결함과 같은 형태이고, 하마터면 「돌지 않은 초록」을 근거로 적을
뻔했다. 위 표의 두 회차는 **둘 다** `--rerun-tasks` 로 다시 잰 것이다.

**처분**

- `ContractSpec.keyChains()` 신설 — 계약의 **연속된 키 경로** 전수(4,741개). 어느 깊이에서
  시작해도 되도록 각 노드의 전체 경로에 대해 **모든 접미사**를 넣는다(주석은 정본을 가리킬 때
  절대 경로를 적지 않는다 — `x-service-constraint.measured_on` 처럼 중간 노드에서 시작한다).
  **리스트를 지날 때 경로 조각을 더하지 않는다** — OpenAPI 의 배열은 이름 있는 층이 아니다.
- `extensionNodeNames()` 는 이제 `keyChains()` 에서 **점 없는 것**만 골라 낸다 — 훑기를 두 벌
  두지 않는다(두 벌이면 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다).
- `NamedReference` 에 `chain` 을 더했다. 축 A 는 `name`(머리), 축 B 는 `chain`(경로)로 판정한다.
- 참조 조각에서 **대괄호와 그 뒤를 지운다**(`fields[0]` → `fields`). 실측: 이 정규화 없이는
  점 참조 **43종 중 6종이 오탐**이고 여섯 다 대괄호 형태 하나였다.
- **실패 메시지가 머리가 아니라 경로를 찍는다.** 실측으로 밟았다 — 머리만 찍으니 실재하는
  부모 이름이 세 번 나열되고 정작 없는 것은 그 아래 자식이었다. 「무엇이 없는가」를 잘못
  가리키는 메시지는 고치는 사람을 엉뚱한 자리로 보낸다.

**부수 발견** — 고친 직후 이 가드가 **자기 KDoc 을 짚었다.** 가짜 경로를 예시로 참조 형태
(백틱)에 적어 두었기 때문이다. 앞서 세운 규약(*"폐기된 이름을 이력으로 적을 때 참조 형태를
쓰지 않는다"*)이 **가짜 경로 예시에도 그대로 적용**된다는 것을 실측으로 확인했고, 세 자리의
산문을 참조 형태 없이 고쳐 썼다. 케이스는 그 조합을 리터럴로 적지 않고 **계약에서 찾는다**
(`fakeExtensionPath()` — 계약이 노드를 옮기면 리터럴이 우연히 참이 되어 케이스가 조용히
무력해지는 것을 막는다).

**새 케이스 4건** — 경로 해소(가짜 경로) · **점 참조 분모 0 이면 빨강**(경로 판정이 한 번도
돌지 않는 상태에서 초록인 것은 「경로가 옳다」가 아니라 「경로를 안 봤다」다) · 멤버가 붙은
참조도 머리를 검사한다 · 배열 첨자를 오탐하지 않는다.

## 9.2 R-10-② 시간 축이 빨개질 수 있음을 증명했다

**결함** — 이 축에 대한 증거가 「성질이 성립할 때 초록」과 「성질이 깨졌을 때도 초록」
(M1 침묵) 둘뿐이었다. **빨개진 관측이 하나도 없는 축은 초록이 아무 뜻이 없고**, 그런데도
「세 축」의 하나로 세어져 덮는 범위를 부풀렸다.

**양성 대조** — `deleteOwned` 가 두 팔 모두 존재 여부 SELECT 를 돌리고(대칭 작업) **행이
있을 때만** 정해진 시간만큼 멈추게 했다(`LockSupport.parkNanos`). 「자원이 있으나 내 것이
아닐 때 더 일한다」를 **크기를 정해** 흉내 낸 것이다. 전 회차 `--rerun-tasks`.

| 주입 | 없음 팔 | 타인 팔 | 비 | 결과 |
|---|---|---|---|---|
| 20.0ms | 6.85ms | 33.85ms | 4.94 | **빨강** |
| 4.0ms | 3.78ms | 9.25ms | 2.45 | **빨강** |
| 2.0ms | 3.77ms | 6.78ms | 1.80 | **빨강** |
| 1.5ms | 3.67ms | 5.76ms | 1.57 | **빨강** |
| 1.0ms | 3.40ms | 5.17ms | 1.52 | **빨강** |
| 0.5ms | — | — | — | 초록 |
| 0.25ms | — | — | — | 초록 |

**탐지 하한 = 0.5~1.0ms 사이**이고, 기준선(없음 팔 중앙값) 약 3ms 에서 그것은 **문턱 그대로
= 1.5배 이상의 격차**다.

**거짓 양성 — 관측 0.** 주입 없는 기준선 **7회**(통과 확인 3회 + 문턱을 1.0 으로 낮춰 수치를
읽은 4회): 비 1.018 · 1.039 · 1.062 · 1.110. **최대 1.110**, 문턱 1.5 까지 여유 약 **26%**.

**종결 = 「잡는다」.** 리더가 제시한 셋 중 첫째다. 축을 남기고 KDoc 의 범위 선언을
**「1.5배 이상의 격차를 잡는다」**로 실측 표와 함께 적었다. 문턱은 형제 게이트와 **같은
1.5** 로 두었으므로 「왜 갈리는지」를 적을 일이 없다(갈리지 않았다).

**함께 적은 한계** — 하한은 **배수로 고정되고 절대값은 기준선에 따라 커진다.** 응답이
느려지면 잡는 절대 격차도 같은 비율로 커진다. 그래서 KDoc 에 적은 것은 절대 밀리초가 아니라
배수다. 그리고 **소유 조건이 SQL 을 떠난 변이는 여전히 잡지 못한다** — 위 표로 말하면 그
변이가 만드는 격차가 1.5배에 못 미친다(M1 침묵이 그 관측이다). 그것을 재는 것은 구조 축 둘이다.

## 9.3 축 A 멤버 참조 — 범위 판단

**넣지 않았다. 조용히 빼지 않고 KDoc 「막지 못하는 것」에 근거와 함께 적었다.**

⑴ P-9 가 승격한 종류는 「이름으로 지목한 **테스트·클래스**」이고 머리가 해소되면 읽는 사람은
그 파일에 도달한다 — 죽은 멤버 포인터는 「자리를 못 찾는다」가 아니라 「그 파일 안에서 못
찾는다」로 약하다. ⑵ 멤버 해소에는 Kotlin 선언 형태의 **어휘**가 필요하다(`fun`·`val`·`var`·
중첩 클래스·`companion`·백틱 한국어 식별자) — 그 어휘가 곧 규칙 4 ⑵ 가 금지한 열거이고,
빠뜨린 형태마다 오탐이 된다. ⑶ 실측(2026-08-21): 이 형태가 오늘 **9종**이고 그중 최소 둘은
산문 조각이다(`SourceScanFormsProbe.fun` · `JdbcDocumentStoreTest.쓰기` — 키워드와 한국어
산문이 점 뒤에 붙은 것). **오탐 22% 로 시작하는 축은 곧 면제 목록을 부른다.**

**머리 검사는 멤버가 붙어도 그대로 돈다** — 그 사실을 케이스 하나가 실행으로 고정한다
(`멤버가 붙은 참조도 머리를 검사한다`).

## 9.4 바닥 개수 핀 — **판정 장치에게 물어** 갱신했다

`grep` 으로 세지 않았다(리더가 그것으로 틀렸다). `tests/test_kotlin_gate_reach.py` 의
`_declared_test_count` 를 직접 호출해 값을 받았고, 갱신 후 전 항목이 판정 장치와 **정확히
일치**함을 같은 함수로 재확인했다.

| 클래스 | 이전 | 갱신 | 사유 |
|---|---|---|---|
| `NamedReferenceGuardTest` | 12 | **16** | R-10-① 케이스 4건 |
| `OwnershipPredicateGuardTest` | 11 | **13** | C5 의 리터럴 케이스 2건(리더가 올리지 않은 잔여) |
| `EnvelopeColumnWriteGuardTest` | 9 | **12** | C5 의 리터럴 케이스 3건(같음) |
| `JdbcDocumentStoreTest` | 22 | **24** | C5 의 삭제 케이스 2건(같음) |
| `DocumentDeleteReachTest` | 14 | 14 | 변화 없음(리더 값이 정확했다) |

`FLOOR_TEST_CLASSES`·`MIN_TEST_CLASSES`·`MIN_FLOOR_CLASSES` 는 **건드리지 않았다**.

## 9.5 실행한 검사 전부와 종료 코드 (R-10, 커밋 후 재실행)

| 검사 | 종료 코드 |
|---|---|
| `./gradlew ktlintCheck detekt build moduleBoundaryCheck parityHarness --continue --rerun-tasks` | **0** |
| `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 uv run pytest tests/test_kotlin_gate_reach.py` | **0** (151 passed) |
| `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` | **0** (BLOCK 0) |
| `uv run ruff check .` | **0** |
| `uv run mypy . .claude` | **0** |
| `uv run pytest` | **0** (1480 passed · 68 skipped · 5 deselected · 5 xfailed) |

계약 파일 무수정 — R-10 에서는 일시 변조도 하지 않았다. 의존성·락파일 변경 0.
복원은 전건 바이트 백업 + `write_bytes` + sha256 대조(§9.6).

## 9.6 R-10 복원 sha256 (전건 일치)

| 파일 | 용도 | sha256 |
|---|---|---|
| `DocumentPorts.kt` | 가짜 경로 심기(전/후 2회) | `e350d94fa7eb6a9b7d98cef99bedbc399158d24568379c5a03de9e290f4c3621` |
| `ContractSpec.kt` | `a687de8` 내용으로 임시 복귀 | `4bd9c0f0e0a02f5aafae1c49dcf126e14e1d3d2ac452d3833d5bd74650abf722` |
| `NamedReferenceGuardTest.kt` | 〃 | `a69f17dec0656ff30e4197c4cdceed9347d84737699a63f90989eef2802a4b55` |
| `JdbcDocumentRepository.kt` | 지연 주입 7회 | `1825f9598784f6f08b6640de5fb5006935af3199d013d77570eedb4006aaa3c6` |
| `DocumentDeleteReachTest.kt` | 문턱 강제 하향(수치 읽기) | `cfe5467155f595163e865822515cb7bdd7d4086bbfe734d4876a05631231ff89` |

## 9.7 R-10 이후에도 증명하지 못하는 것 — 악용 비용

| 남은 것 | 왜 | 악용 비용 | 결과 크기 |
|---|---|---|---|
| **축 B 가 「경로는 맞지만 뜻이 틀린」 인용을 못 잡는다** | 경로 실재까지가 이 축의 물음이다 | **한 줄 편집** | 읽는 사람이 옳은 자리를 보고 틀린 뜻을 읽는다. 그 축은 변이 테스트(B-19)의 물음 |
| **축 A 멤버 참조** | §9.3 의 판단. KDoc 에 선언했다 | **한 줄 편집** | 「그 파일 안에서 못 찾는다」 — 파일에는 도달한다 |
| **시간 축이 1.5배 미만 격차를 못 잡는다** | 잡음 대비 분해능. 실측 하한 0.5~1.0ms | **큰 diff**(질의 구조 변경) | 구조 축 둘이 덮는다(M1 이 실측) |
| **시간 축의 절대 하한이 기준선과 함께 커진다** | 비로 판정하므로 응답이 느려지면 절대 격차 하한도 커진다 | 배포 환경 변화(편집 아님) | 그 사실을 KDoc 에 적었고, 문턱 대신 배수로 선언했다. 절대 상한을 핀으로 걸면 부하 환경에서 깜박여 그 대가가 이득보다 크다 |
| **점 참조 분모 4,741 체인의 메모리·시간** | 계약이 커지면 선형으로 는다 | — | 오늘 측정 대상 43종에 대해 체감 0(테스트 전체 2초) |
| **`--rerun-tasks` 없이 재면 UP-TO-DATE 로 건너뛴다** | Gradle 의 최신성 판정이다 | **명령에서 플래그 하나 빼기** | 이 회차에 실제로 한 번 오측했다. 세션 규율이 그 플래그를 요구하는 이유이고, 자동 강제자는 **없다** |
