# C7 — `PUT /conversions/{conversion_id}` 검수 저장 · 리서치·계획

**작성:** kotlin-implementer / **일자:** 2026-08-23 / **HEAD:** `c87ea0c` / **성격:** 착수 전 계획. **코드 0줄.**
**근거 규칙:** 프로젝트 `CLAUDE.md` 「구현 전 리서치·계획 (필수)」 — ①라이브러리·프레임워크 리서치
②기구현 확인 ③계획 작성을 **순서대로** 끝내고 그 결과를 남긴다. 이 문서가 ③이고, ①은 §1, ②는 §2다.

**입력**

| 무엇 | 어디 |
|---|---|
| 상위 계획의 C7 행·게이트 덩어리 G-γ | `docs/migration/_workspace/04_kotlin-implementer_documents-plan.md` §7.2 |
| 계약 정본 | `contracts/easy-doc-v1.yaml` |
| 계약 케이스 CU-1~CU-11 + 횡단 X-A1·X-B1·X-C2·X-D1·X-F2·X-F9·X-L2 | `docs/migration/_workspace/04_contract-keeper_documents-test-spec.md` |
| **선행 계약 판정 3건 (P-1·X-8·X-12) 및 C7 이 닿는 조항 목록** | `docs/migration/_workspace/04_contract-keeper_c7-rulings.md` |
| **CU-11 재번호 결과** (contract-keeper 가 이 계획 작성 시점에 작업 중) | `docs/migration/_workspace/04_contract-keeper_documents-test-spec.md` (갱신본) |
| 게이트 `04_documents-c6r2` 이월 4건 (X-8·X-13·X-14·X-15) | `docs/migration/_workspace/reviews/04_documents-c6r2_cross.md` §2.2·§7.2·§7.3 |
| 구현 규약 | `.claude/skills/kotlin-spring-conventions` |

**인용 규약 — 노드 경로가 정본이고 행 번호는 참고다.** 근거는 판정 산출물 §4-2 ㉢이다. 이 단위의
리더 브리프와 테스트 스펙이 인용한 행 번호가 **전부 낡았고**(PUT 은 `:1123-1175` 가 아니라 `:1543-1599`,
`fields[?edited_text]` 는 `:420-424` 가 아니라 `:798-802` — `:420-424` 는 오늘 **`x-cors`** 다), 어긋남이
일정 오프셋이 아니라 되밀 수도 없다. **틀린 행을 따라간 사람이 「없다」가 아니라 실재하는 다른 조항을
읽는다** — 조용히 틀리는 형태다. 그래서 이 계획은 노드 경로로만 부르고, 행 번호는 `c87ea0c` 실측일
때만 괄호로 덧붙인다.

**값을 전사하지 않는다.** 계약이 소유한 값(상한 숫자·`detail` 문구·헤더 값·enum 원소·미디어 타입)은
**노드 경로로 부른다.** 손으로 옮긴 요약이 원본과 갈린 실측이 이 저장소에 이미 있다.

**무접촉 확인** — 이 배치는 이 파일 하나만 만든다. `backend-kotlin/**`·`contracts/**`·`.claude/**`·
`app/**`·`tests/**`·`frontend/**`·`reviews/**`·`00_progress.md` 를 한 줄도 건드리지 않았고 Gradle 을
돌리지 않았다.

---

## 0. 이 커밋의 경계

| 안 | 밖 |
|---|---|
| 계약 **`paths./conversions/{conversion_id}.put`** 전건 (CU-1~CU-11) | `GET .../export` — `export` 단위 |
| `edited_text` 정규화·판정·봉인 저장·`reviewed_at` | 자리표시자 복원(`restoreForExport`) — `export` 단위 |
| `ReviewedBody` **프로덕션 생성 지점 첫 등재** | `ModelDraft` 프로덕션 생성 지점 (Phase 5 워커가 이미 씀) |
| 유보 해제 3건 (사적 헤더 하한선 · F3 `edited_text` · `x-stored-text-domain` `edited_text` 팔) | 계약 파일 편집 — **contract-keeper 레인** (§10 D) |
| 게이트 `04_documents-c6r2` 이월 4건 (X-8·X-13·X-14·X-15) | 하네스 단위로 배치된 이월 (X-4·X-7·X-9·X-10·X-11·X-16) |
| 계약 판정 3건의 구현 몫 (P-1 재명명·X-8 수정·X-12 KDoc) | 계약 문면 개정 2건 (§2-4 ㉠㉡) — 리더 승인 뒤 별 커밋 |

**Python 은 정답이 아니다.** `CLAUDE.md` 와 master-plan 6.2 대로 판정 기준은 요구사항·정책이다.
다만 `app/services/documents.py` 의 `save_review` 는 **계약이 위임한 판정 순서**(정규화 → 빈 값 →
길이 → 소유권 → 상태)를 실제로 담고 있는 유일한 기술이라, §4.2 에서 그 순서를 근거로 인용하고
**갈리게 정한 자리는 그 사실을 표시**한다.

---

## 1. 라이브러리·프레임워크 리서치 (`CLAUDE.md` 필수 순서 ①)

**방법.** 하위 조사 에이전트 1건(읽기 전용)에 **학습 기억 사용 금지**를 지시하고 context7 MCP +
공식 레퍼런스로 확인하게 했다. 확인하지 못한 것은 **「확인 실패」로 그대로 남긴다** — 추측으로
메우면 그 자리가 조용히 결함이 된다. 보고 파일을 만들지 않고 결론을 이 절에 흡수했다(§1.5 가 그
확인 실패 목록이며, 이 저장소 안의 정본이다).

**버전 실측** (`backend-kotlin/gradle/libs.versions.toml` · `backend-kotlin/build.gradle.kts`):
Kotlin **2.3.21** / Spring Boot **4.1.0** → BOM 관리분 Spring Framework **7.0.8** · Jackson **3.1.4** ·
JUnit **6.0.3**. `allWarningsAsErrors.set(true)` 가 **켜져 있다**(`build.gradle.kts:77`, 루트 `subprojects`
블록 한 곳. `buildSrc`·convention plugin 없음). `freeCompilerArgs` 선언은 **0건**이다.

### 1.1 `JdbcClient` 조건부 UPDATE — 새로 만들 것이 없다

- `StatementSpec.update(): Int` 의 반환값이 **영향 행 수**라는 것이 javadoc 명시다. 0행 감지의
  표준 형태는 `.update() > 0` 이고, **저장소에 이미 그 패턴이 5건 있다**(§2 표 5·9).
- `MappedQuerySpec.optional()` 은 0행에서 `Optional.empty()`(예외 없음), 2행 이상에서
  `IncorrectResultSizeDataAccessException`. `single()` 은 0행에서 `EmptyResultDataAccessException`.
  기존 저장소가 `findOwnedResult` 에 `optional().orElse(null)` 을, `insertPending` 에 `single()` 을
  쓰는 것이 이 구분과 정확히 맞는다.
- `DefaultJdbcClient` 소스(v7.0.8) 기준 `update()` 는 `executeUpdate()` 경로, `query()` 는
  `executeQuery()` 경로다. **따라서 `UPDATE … RETURNING` 은 `update()` 가 아니라 `query()` 로 받아야
  한다.** 저장소의 `RETURNING` 5건이 전부 그렇게 돼 있다.
- **직접 구현하는 자리와 사유**: 「0행이 404 인가 409 인가」를 단일 문장으로 가르는 방식은 Spring 이
  답을 주는 문제가 아니다(§1.5 A-2). §4.4 가 그 설계를 정하며, 그 자리가 이 커밋에서 라이브러리를
  쓰지 않고 직접 정하는 유일한 지점이다.

### 1.2 Bean Validation — **이 필드에는 쓰지 않는다**. 계약이 금지한다

- 예외 타입은 Framework 7.0 에서 바뀌지 않았다. `MethodArgumentNotValidException` 은 deprecate 되지
  않았고 `HandlerMethodValidationException` 으로 통합되지도 않았다. 전자는 `@Valid @RequestBody` 같은
  **파라미터 개별 검증**, 후자는 제약 애너테이션이 **메서드 파라미터에 직접** 붙은 경우다.
- **커스텀 `ConstraintValidator` 로 「정규화 후 측정」을 표현하는 것은 문법상 가능하지만 이 계약에서
  쓸 수 없다.** DTO 필드에 붙은 커스텀 제약도 `@Valid @RequestBody` 실패 경로를 그대로 타므로 응답이
  `MethodArgumentNotValidException` → **배열 `detail`** 이 된다. 계약은 이 필드의 위반을 **문자열
  `detail`** 로 못박았다(`x-request-field-constraints.fields[?edited_text].layer: service` ·
  `.put.responses.422` 의 두 예시). 즉 애너테이션으로 가는 길은 **전부** 계약 위반이다.
- 저장소가 그 갈림을 이미 코드로 고정해 뒀다 — `GlobalExceptionHandler.handleMethodArgumentNotValid`
  / `handleHandlerMethodValidationException` 이 **배열**, `EasyDocException` 매핑이 **문자열**
  (`ErrorResponse(detail: String)` / `ValidationErrorResponse(detail: List<ValidationErrorItem>)`).
- **필수 필드 누락(CU-9)은 Bean Validation 없이 이미 성립한다.** `JsonRequestStrictnessConfig` 가
  `withValueNulls(Nulls.FAIL)` 을 전역으로 걸어 두어, 누락·`null` 이 Jackson `InvalidNullException` 으로
  떨어지고 `handleHttpMessageNotReadable` → `bodyReadItem` 이 `type="missing"` 배열 항목을 만든다.
- **귀결: `ConversionReviewRequest` DTO 에 `@Valid` 도 `@NotNull` 도 붙이지 않는다.** 길이·빈 값·
  정의역 판정은 전부 `application` 층이다. 이것은 이 프로젝트의 관행이 아니라 **F3 판정이 강제하는
  형태**이며, `RequestFieldRejectionLayerTest` 가 응답 `detail` 모양으로 그 층을 계속 잰다.

### 1.3 `@ConsistentCopyVisibility` — **경고 없이 쓸 수 있다** (X-13 의 전제)

Kotlin **소스 v2.3.21** 실측(`compiler/util/src/org/jetbrains/kotlin/config/LanguageVersionSettings.kt`):

```
ErrorAboutDataClassCopyVisibilityChange(KOTLIN_2_5, enabledInProgressiveMode = true, "KT-11914")  // phase 2
DataClassCopyRespectsConstructorVisibility(sinceVersion = null, "KT-11914")                        // phase 3
```

- phase 2(경고→에러)는 **2.5 이후**다. 우리 LV 2.3 + `-progressive` 미사용이므로 아직 경고 단계.
- phase 3(기본 동작 전환)은 `sinceVersion = null` — 어떤 언어 버전에서도 기본으로 켜지지 않는다.
  즉 **애너테이션이 여전히 필요하고, `REDUNDANT_ANNOTATION` 경고도 나지 않는다.**
- 따라서: `private constructor` **만** 붙이면 phase 1 선언부 경고가 나고 `allWarningsAsErrors` 때문에
  빌드가 깨진다. `@ConsistentCopyVisibility` 를 함께 붙이면 ⑴ 경고가 사라지고 ⑵ `copy()` 가 생성자
  가시성을 따라간다. **`-Xconsistent-data-class-copy-visibility` 플래그는 불필요하다**(클래스 단위
  애너테이션으로 충분). `@ExposedCopyVisibility` 는 반대편(옛 동작 유지)이고 stdlib 문서가
  `ConsistentCopyVisibility` 를 권한다.
- **Jackson 영향 없음.** 애너테이션이 `@Retention(SOURCE)` 라 런타임 영향이 0이고, 영향은 오직
  `private constructor` 에서 온다. 대상 3개는 전부 **응답** DTO 이고 `@get:JsonProperty` 로 게터를
  읽으므로 생성자 가시성과 무관하다. (요청 DTO 였다면 Jackson 3.1.4 의
  `MapperFeature.CAN_OVERRIDE_ACCESS_MODIFIERS` 기본값 `true` + 명시 `@JsonCreator` 로 열린다 —
  저장소의 요청 DTO 가 이미 그 형태다.)
- **미실행**: `./gradlew compileKotlin` 으로 경고 유무를 음성 대조하지 않았다(이 배치는 코드 0줄).
  구현 시 `private constructor` 만 붙인 상태로 한 번 컴파일해 경고가 나는지(양성), 애너테이션 추가
  후 사라지는지(음성)를 확인하면 이 판정이 실측으로 닫힌다 — **§7.3 음성 대조 표에 넣었다.**

### 1.4 415 — 프레임워크 기본 경로 그대로다 (CU-11(415) 의 전제)

Framework **v7.0.8** 소스 `HttpMediaTypeNotSupportedException` 실측:

- `getStatusCode()` = 415. javadoc 이 *"POSTs, **PUTs**, or PATCHes"* 로 PUT 을 명시한다.
- `getHeaders()` 가 **`Accept` 응답 헤더를 지원 미디어 타입으로 채운다**(지원 목록이 비면
  `HttpHeaders.EMPTY`). `Accept-Patch` 는 **PATCH 일 때만** — 즉 우리 PUT 응답에는 붙지 않는다.
- `GlobalExceptionHandler` 는 이 예외를 **오버라이드하지 않는다.** `ResponseEntityExceptionHandler`
  기본 경로를 타고 `createResponseEntity` 가 마지막에 본문을 계약 모양으로 만든다
  (`ContractErrorBody` 가 아니면 `ErrorResponse(reasonPhraseOf(status))`, `application/json`).
  이것이 계약 `x-unsupported-media-type.detail_source` 의 「세 경로가 같은 함수 하나로 만든다」와
  같은 자리다. **한국어로 바꾸지 않는다.**
- **오늘 저장소에 `Accept` 응답 헤더를 재는 테스트가 0건이다.** 기존 415 테스트 둘
  (`PrivateResponseHeadersReachTest`·`ContractErrorBodyReachTest`)은 프로브 컨트롤러를 쓰고 사적
  헤더 2종과 본문 모양만 본다. CU-11(415)이 그 빈자리를 처음 채운다.

### 1.5 확인 실패 — 추측으로 메우지 않은 것

| # | 무엇 | 상태 |
|---|---|---|
| **A-1** | `UPDATE … RETURNING` 을 `JdbcClient` 로 받는 방법이 **Spring 문서에 없다.** 레퍼런스 JDBC 장·javadoc·context7 검색 모두 "RETURNING" 언급 0. `KeyHolder` 는 `JdbcTemplate` 절에만 있다 | 「`query()` 로 받는다」는 **소스 코드 근거(executeQuery 경로) + 저장소 기구현 5건**에서 나온 것이지 문서 권고가 아니다. **이 커밋은 `RETURNING` 을 쓰지 않는다**(§4.4) — 그래서 이 실패가 구현을 막지 않는다 |
| **A-2** | **404/409 를 단일 문장으로 가르는 Spring 권장 방식이 문서에 없다** | Spring 이 답을 주는 문제가 아니다. §4.4 가 우리 설계로 정하고 그 근거를 적는다 |
| **A-3** | PostgreSQL JDBC 드라이버의 `UPDATE … RETURNING` 지원을 **PostgreSQL 쪽 문서로 확인하지 않았다** | 위와 같이 이 커밋에 쓰지 않으므로 미해결로 두어도 된다 |
| **B-1** | `HandlerMethodValidationException` 이 `MethodArgumentNotValidException` 을 대체한다는 서술을 **Framework 7 문서에서 찾지 못했다** | 「대체되지 않았다」는 deprecated 부재 + 레퍼런스가 둘을 나란히 설명한다는 **음성 근거**로만 확인됐다. §1.2 의 결론은 이 항목에 의존하지 않는다(둘 다 배열이므로) |
| **B-2** | 예외 → 상태 코드 표(옛 `DefaultHandlerExceptionResolver` 표)를 현재 레퍼런스에서 찾지 못했다 | §1.4 의 415 사실은 **v7.0.8 태그 소스**에서 나왔다 |
| **C-1** | kotlinlang.org 산문은 phase 2·3 을 *"Supposedly 2.1 or 2.2 / 2.2 or 2.3"* 로만 적고 확정 버전을 주지 않는다 | §1.3 판정은 **문서가 아니라 컴파일러 소스**로 냈다. 그 사실을 여기 남긴다 |
| **D-1** | 415 응답에 `Accept` 헤더가 실제로 붙는지 **실행으로 재지 않았다** | CU-11(415)이 그 실측이다. 계획이 근거로 든 것은 v7.0.8 소스뿐 |

---

## 2. 기구현 확인 (`CLAUDE.md` 필수 순서 ②) — **중복 구현은 결함이다**

경로는 전부 `backend-kotlin/` 기준 상대이고, 저장소 절대 경로는 `/Users/harris/Development/private/easy-doc/backend-kotlin/` 이 앞에 붙는다.

### 2.1 그대로 재사용하는 것

| # | 무엇 | 위치 | C7 에서 쓰는 자리 |
|---|---|---|---|
| R-1 | `ReviewedBody` — `@JvmInline value class ReviewedBody(val value: String)`. `init` 없음, 팩터리 없음, `toString` 은 길이만 | `core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:338-342` | §4.5. **프로덕션 생성 지점 0건**이 오늘 상태이고 C7 이 첫 하나를 만든다 |
| R-2 | `PlainBody` — `init` 이 `hasUnpairedSurrogate` 로 정의역을 끊고 `UNPAIRED_SURROGATE_MESSAGE` 를 던진다 | `core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent.kt:24-42` | §4.2 정의역 판정. **새 타입을 만들지 않는다** |
| R-3 | `hasUnpairedSurrogate(text: String): Boolean` — `core` 에서 **public** | `core/src/main/kotlin/kr/easydoc/core/text/Surrogates.kt:42` | R-2 를 통해 간접 사용. 직접 부를 필요 없다 |
| R-4 | `stripControlChars(text: String): String` — 패턴 `[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]`(탭·개행·복귀 유지). **서로게이트는 지우지 않는다** | `core/src/main/kotlin/kr/easydoc/core/text/TextNormalization.kt:10`·`:7` | §4.2 정규화. 계약 `fields[?edited_text].measured_on` 이 요구하는 그 연산 |
| R-5 | `normalizeWorkspaceName` — `stripControlChars(raw).trim()`. **`application` 층이 `core` 정규화를 부르는 선례** | `application/src/main/kotlin/kr/easydoc/application/workspace/WorkspaceNameRules.kt:43` | §4.2 가 따를 형태(단 `trim` 여부는 다르다 — §4.2 참조) |
| R-6 | `ConversionStatus`(`wireName`·`exposesResult`) · `ConversionView`(`carriesResult`) | `core/.../document/ConversionStatus.kt` · `ConversionView.kt:8-38` | §4.4 상태 판정 · §4.6 응답 조립 |
| R-7 | `ConversionRepository` 포트 4메서드와 `ConversionEnvelope`·`ConversionCiphertexts`·`StoredConversion` | `application/.../document/DocumentPorts.kt:78-138` | §4.3·§4.4 가 **다섯째 메서드 하나**만 더한다 |
| R-8 | `rewriteEnvelope` 의 SQL 형태 — 세 암호문 열 + 봉투 두 값을 **한 UPDATE**, 낙관 조건 6개(`IS NOT DISTINCT FROM`) | `infrastructure/.../document/JdbcConversionRepository.kt:77-110` | §4.4 의 UPDATE 가 **이 형태를 그대로 물려받는다** |
| R-9 | `lockEnvelope` 의 `FOR NO KEY UPDATE` 와 그 잠금 모드 선택 근거 | 같은 파일 `:62-74` · 근거는 documents-plan §9.2-ter | §4.4 잠금 |
| R-10 | `findOwnedResult` — 소유 술어가 **조인 위에 한 문장**(`FIND_OWNED_SQL`) | 같은 파일 `:216-226` | §4.4 응답 재조회 |
| R-11 | `ConversionQueryService.read` / `completed` / `beforeDone` / `open` | `application/.../document/ConversionQueryService.kt:20-73` | §4.6. **응답 조립을 복제하지 않고 이것을 부른다** |
| R-12 | `ContentCipher` 포트(`writeScheme`·`writeKeyVersion`·`encrypt`·`decrypt`) | `application/.../crypto/ContentCipher.kt` | §4.3. **포트를 넓히지 않는 설계를 고른다** |
| R-13 | `EncryptedField.CONVERSION_EDITED_TEXT` (`conversions.edited_text_encrypted`) | `core/.../crypto/StoredContent.kt:93` | §4.3 AAD 결속 |
| R-14 | `TransactionRunner.inTransaction` (포트) / `SpringTransactionRunner`(`TransactionTemplate`) | `application/.../auth/AuthPorts.kt:132-134` · `infrastructure/.../db/SpringTransactionRunner` | §4.4 트랜잭션 경계 |
| R-15 | `ConversionController` — 경로 상수 `CONVERSION_ITEM_PATH`·`CONVERSION_ID_VARIABLE`, 사적 헤더 2종 **개별 부착** | `api/src/main/kotlin/kr/easydoc/api/document/ConversionController.kt` | §4.6. **새 컨트롤러를 만들지 않고 이 클래스에 `@PutMapping` 을 더한다** |
| R-16 | `ConversionResponse.of(view)` + `require(exposesResult ‖ !carriesResult)` fail-closed | `api/.../document/ConversionDtos.kt:36-84` | §4.6 |
| R-17 | `GlobalExceptionHandler` 의 도메인 예외 매핑(`InvalidInputException`→422 · `NotFoundException`→404 · `ConflictException`→409) 과 `createResponseEntity` 계약 본문 조립 | `api/.../error/GlobalExceptionHandler.kt` | §4.4·§4.7. **새 매핑을 더할 필요가 없는지**가 §10 A-3 |
| R-18 | `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` 에 `/conversions/{conversion_id}` 가 **이미 있다** | `api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndpoints.kt:12` | **이 파일은 손대지 않는다.** 그 한 줄이 경로의 두 메서드를 함께 덮는다고 KDoc 이 못박았다 |
| R-19 | `ContractSpec`(계약 파서, `schemaRequired`·`requestFieldConstraint`·`responseHeaderNames`·`schemaEnum`·`pathVariable` 등) · `ServedOperations`(스프링 핸들러 매핑에 직접 묻는다) | `api/src/test/kotlin/kr/easydoc/api/support/` | §7 전건. **기대값을 코드에 적지 않고 계약에서 읽는다** |
| R-20 | `ConversionReadReachTest` 의 픽스처 헬퍼 — `createDocument(token, text): Pair<UUID, UUID>`(`:412-413`) · `markDone`(`:346`) · `forceStatus`(`:378`) · `sealed`(`:391`) · `newAccount`(`:403`) · SQL 은 companion 상수 리터럴에 두고 `%s` 만 채우는 규약(`:360-362`) | `api/src/test/kotlin/kr/easydoc/api/ConversionReadReachTest.kt` | §7.1 C-I 층. **픽스처를 새로 만들지 않는다** |
| R-21 | `ConversionQueryServiceTest` 의 `World` 대역(`seedPending`·`demoteTo`·`seedResults`) — Spring·DB 없이 도는 순수 단위 | `application/src/test/kotlin/kr/easydoc/application/document/ConversionQueryServiceTest.kt:158-` | §7.1 단위 층 |
| R-22 | `run_gate.sh` — **인용형 셸 명령 1개**만 받는다. 인자 0개·2개·빈 문자열은 `exit 2`, 자식이 실행 단계에 진입하지 않아도 `exit 2` | `.claude/skills/kotlin-migration/scripts/run_gate.sh` | §8 |

### 2.2 반드시 손대야 하는 자리 — **안 고치면 빨개진다**

| # | 자리 | 무엇을 | 안 하면 |
|---|---|---|---|
| **T-1** | `core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt` `ALLOWED["ReviewedBody"]`(`:44-48`) | 새 프로덕션 어댑터 경로 + 호출 수 **1** 추가 | `허용하지 않은 생성 지점이 없다` 빨강. 실패 문구가 규약을 명시한다 |
| **T-2** | `api/src/test/kotlin/kr/easydoc/api/PrivateHeaderFloorCensusTest.kt` `NOT_YET_IMPLEMENTED`(`:330-334`) 에서 PUT 줄 삭제 + `driveSuccess` `when`(`:156-207`)에 PUT 성공 조립 추가 | 유보 해제 | ⑴ `유보한 자리는 아직 매핑이 없다` 가 **매핑이 생기는 순간** 빨강 ⑵ 유보만 지우면 `driveSuccess` 의 `error(…)` 팔로 터진다 |
| **T-3** | `api/src/test/kotlin/kr/easydoc/api/support/ContractEnumerationFloors.kt` `MIN_PRIVATE_HEADER_TARGETS` 는 그대로(10). **`PrivateHeaderFloorCensusTest` 의 `MIN_FLOOR_CENSUS_TARGETS` 8→9, `MAX_DEFERRED_FLOOR_TARGETS` 2→1** | 라쳇 상환 | 상향하지 않으면 **구현한 자리를 다시 유보로 되돌려도 초록**이다(하한 아래만 막으므로 창이 남는다 — 규칙 8 이 지목한 형태) |
| **T-4** | `api/src/test/kotlin/kr/easydoc/api/RequestFieldRejectionLayerTest.kt` `PINNED_WITHOUT_DTO`(`:214`) → **빈 집합** + `RequestFieldProbes` 에 `edited_text` 프로브·`FIELD_SHAPES` 배선 | **X-F9 마감** (F3 다섯 필드의 마지막) | `계약 필드 전부가 다뤄진다` 가 세 단언 모두에서 빨강. 실패 문구가 *"프로브를 배선하고 핀에서 지워라 — 그 커밋이 F3 마감이다"* 로 이미 지시한다 |
| **T-5** | `infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/EnvelopeColumnWriteGuardTest.kt` 의 **문장 수 인구조사**(오늘 파일 3 · 문장 5) | 새 UPDATE 1건 반영 → 6 | 인구조사 불일치로 빨강 (설계 의도대로 — 새 암호문 쓰기가 리뷰에 올라온다) |
| **T-6** | `tests/test_kotlin_gate_reach.py` — `TEST_CLASSES` + `TEST_CLASS_COUNT`(오늘 112, `len()` 정확 일치) + `MIN_TEST_CLASSES`(오늘 111) | 새 테스트 클래스 수만큼 | `선언한 테스트 클래스와 트리에서 발견한 것이 정확히 일치한다` 빨강 |
| **T-7** | 같은 파일 `MIN_TESTS_BY_NAMED_ENFORCER` + `MIN_ASSERTIONS_BY_CLASS` | `ConversionController` KDoc 이 새 테스트 이름을 지목하면 `_named_enforcer_census` 가 **양쪽 핀을 자동 요구**한다. 두 표의 키 집합 정확 일치 불변식도 함께 | 핀 누락으로 빨강 |
| **T-8** | 계약 `x-unsupported-media-type.x-measured.not_reached` · `x-stored-text-domain.applies_to[?edited_text].status`(`pending`) | **contract-keeper 레인.** C7 이 실측을 제공하고 계약 편집은 그쪽이 한다 | 두 팔이 열린 채면 **Phase 4 `documents` 종료 조건 미충족**(판정 §4-1 이 명시) |

### 2.3 없어서 새로 만드는 것 — **최소 목록**

| # | 무엇 | 어디 | 왜 기존 것으로 안 되는가 |
|---|---|---|---|
| N-1 | `ConversionReviewRequest` 요청 DTO (`edited_text`) | `api/.../document/ConversionDtos.kt` | 저장소에 **없다**. `RequestFieldRejectionLayerTest.PINNED_WITHOUT_DTO` 가 그 부재를 정확 열거 핀으로 고정하고 있다 |
| N-2 | `ConversionReviewService` (유스케이스) | `application/.../document/` | `ConversionQueryService` 는 읽기 전용이고 트랜잭션 안에서 쓰기를 하지 않는다. 쓰기를 그 클래스에 얹으면 그 클래스가 **어느 하한 표에도 없는** 자리에 놓인다(§10 A-5) |
| N-3 | 포트 메서드 **둘** — `lockOwnedForReview(ownerId, conversionId): LockedConversion?` · `saveReview(expected, scheme, keyVersion, ciphertexts): Boolean` | `application/.../document/DocumentPorts.kt` `ConversionRepository` | 기존 4메서드 중 `edited_text` 를 쓰는 것이 **없다**. `lockEnvelope` 는 소유권을 보지 않고 상태를 돌려주지 않는다 |
| N-4 | `LockedConversion(status, envelope)` 반환 타입 | 같은 파일 | `StoredConversion` 은 행 봉투를 **열마다 세 번** 들고 있어 「행의 세대」를 직접 물을 수 없다. 그 값을 열에서 유도하는 것은 열이 전부 NULL 인 경우에 무너진다 |
| N-5 | 409·422 문구 상수 | `application/.../document/DocumentMessages.kt` | 오늘 `CONVERSION_NOT_FOUND_MESSAGE` 만 있다. 값의 정본은 계약 `.put.responses.409.examples.not_done` · `.put.responses.422.examples.{empty,too_long}` 이고, **테스트는 계약에서 읽어 대조한다** |
| N-6 | 계약·실경로 테스트 클래스 2개 (§7.1) | `api/src/test/kotlin/kr/easydoc/api/` | 기존 `ConversionRead*Test` 는 GET 전용이고 하한 핀이 그 이름에 걸려 있다 |

---

## 3. 계약 조항 — **노드 경로가 정본이다**

C7 이 닿는 조항의 정본 목록은 `04_contract-keeper_c7-rulings.md` §4-1 이다. 여기서는 **설계를 직접
구속하는 것만** 다시 부른다(값은 옮기지 않는다).

| 축 | 노드 경로 | 이 계획의 어느 절이 지는가 |
|---|---|---|
| 성공·응답 스키마 | `paths./conversions/{conversion_id}.put.responses.200` → `ConversionResponse` (GET 과 **같은 스키마**), 키 집합은 `ConversionResponse.required` | §4.6 |
| 초안 보존 | `.put.description` · `ConversionResponse.properties.edited_text.description` | §4.3 · §4.4 |
| 상태 충돌 | `.put.responses.409` · `.put.description` | §4.4 |
| 소유권 은닉 | `.put.responses.404` | §4.4 |
| 입력 스키마 | `ConversionReviewRequest`(`required: [edited_text]`, **`maxLength` 없음**, `x-service-constraint.{max_length,measured_on}`) | §4.2 · §1.2 |
| 정규화 선행 | `.put.description` · `ConversionReviewRequest.properties.edited_text.description` | §4.2 |
| 길이·`detail` 모양 | `x-request-field-constraints.fields[?edited_text]`(`layer: service`) | §4.2 · §1.2 |
| 422 예시 두 갈래 | `.put.responses.422.examples.{empty,too_long}` | §4.2 |
| **저장 정의역** | `x-stored-text-domain`(`layer/status/detail_shape/detail`) + `.applies_to[?edited_text].status: pending` — **C7 이 이 팔을 닫는다** | §4.2 · T-8 |
| **415 미측정 팔** | `x-unsupported-media-type`(`detail_source`·`response_header_accept`) + `.x-measured.not_reached` — **C7 이 이 팔을 닫는다** | §4.7 · T-8 |
| 사적 헤더 | `x-private-response-headers.applies_to` (PUT 은 하한선 10곳 중 하나) | §4.6 · T-2 |
| 인증 | `.put.security` · `.put.responses.401`(+`WWW-Authenticate`) | §4.6 |
| 오류 본문 보편성 | `x-error-body-universality` | §4.4 |

---

## 4. 설계

### 4.1 층 배치와 호출 흐름

```
api          ConversionController.@PutMapping   ← 사적 헤더 2종 개별 부착 · DTO 만
              └ ConversionReviewRequest(edited_text: String?)      ← @Valid 없음 (§1.2)
application  ConversionReviewService.save(ownerId, conversionId, submitted: ReviewedBody)
              ├ 정규화·판정 (§4.2)            ← core 순수 함수만
              └ transaction.inTransaction {
                   lockOwnedForReview  → 404 / 409 판정 (§4.4)
                   cipher.encrypt      → 세 열의 최종 값 (§4.3)
                   saveReview          → 조건부 UPDATE, 0행 = fail-closed (§4.4)
                   queryService.read   → 응답 조립 (§4.6, R-11 재사용)
                 }
infrastructure JdbcConversionRepository.{lockOwnedForReview, saveReview}
```

`ReviewedBody` 를 **유스케이스 시그니처에 두는 것이 요점이다** — 그러면 모델 초안(`ModelDraft`)이나
아무 `String` 으로 이 유스케이스를 부를 수 없고, 「사람이 제출한 값만 `edited_text` 에 들어간다」가
규율이 아니라 **타입**이 된다(`kotlin-spring-conventions` §4.2 의 `MaskedText` 와 같은 기제).

### 4.2 정규화와 판정 순서 — CU-4 · CU-5 · CU-6 · CU-7

**연산 정의.** 정규화 = `stripControlChars` **하나뿐이다.** `trim` 하지 않는다.
근거는 계약 `x-request-field-constraints.fields[?edited_text].measured_on` 이 제어문자 제거만 적었고,
같은 표에서 `WorkspaceNameRequest.name` 은 「제어문자 제거 **+ 앞뒤 공백 제거**」로 **다르게** 적었다는
것이다. 두 값이 다르게 적힌 것을 같게 구현하면 계약이 구분한 것을 지운다.

**판정 순서** (Python `app/services/documents.py` `save_review` 가 담고 있는 순서와 같다):

| # | 판정 | 대상 | 위반 시 | 계약 지목 |
|---|---|---|---|---|
| 1 | 제어문자 제거 | 원시 값 | — | `.put.description` · `ConversionReviewRequest.properties.edited_text.description` |
| 2 | **빈 값** | 정규화 값을 `trim` 한 결과가 비었는가 | 422 문자열 | `.put.responses.422.examples.empty` |
| 3 | **길이** | 정규화 값(**`trim` 하지 않은** 것)의 **코드 포인트 수** | 422 문자열 | `.put.responses.422.examples.too_long` · `fields[?edited_text].limit` |
| 4 | 소유권 | — | 404 | `.put.responses.404` |
| 5 | 상태 | `status == done` 인가 | 409 | `.put.responses.409` |
| 6 | **저장 정의역** | 짝 없는 서로게이트 (`PlainBody.init`) | 422 문자열 | `x-stored-text-domain` |

- **2 와 3 의 대상이 다르다.** 빈 값 판정만 `trim` 을 거치고 길이 판정은 거치지 않는다. 「공백만 담긴
  수정본」은 빈 값이지만, 앞뒤 공백은 **저장되는 값에 그대로 남는다.** 이것이 CU-4(제어문자만 → 빈 값
  갈래)와 CU-7(저장된 값이 정규화 값)을 동시에 만족시키는 유일한 조합이다.
- **길이는 코드 포인트로 센다.** `String.length`(UTF-16 코드 단위)가 아니다 — documents-plan D-n 이
  같은 이유(서로게이트 쌍을 한가운데서 세지 않는다)로 이미 `charCountOf` 를 정했다. **그 함수가
  `DocumentService` 안에 `private` 이면 `application` 공용 자리로 올린다**(§10 A-1).
- **CU-6 이 스키마 층 구현을 배제한다.** 원시 길이는 상한 초과인데 제어문자를 걷어내면 이하인 입력이
  **통과**해야 한다. `@Size(max=…)` 든 커스텀 `ConstraintValidator` 든 스키마 층은 이 요구를 표현할 수
  없거나(전자) 표현해도 `detail` 이 배열이 된다(후자, §1.2). **DC-11 과 CU-6 은 대비 쌍이다** —
  `text` 는 원시 측정이라 같은 입력이 거절돼야 하고, 이 두 축이 한 값으로 뭉개졌는지를 음성 대조
  N-25 가 잰다(§7.4).
- **6 이 4·5 뒤에 오는 것은 의도다.** `PlainBody` 는 암호화 직전에 만들어지므로 정의역 판정이 소유권
  뒤다. `POST /documents` 에서 계약이 같은 배치를 **명문으로 의도라고 적었다**(`.post.description` 의
  검사 순서 조항). PUT 에는 그 명문이 없다 — **그래서 §10 D-2 로 올린다.** 판정이 없는 동안 이 배치를
  택하는 근거는 두 오퍼레이션이 같은 저장 경로를 지난다는 것이고, 그 선택을 산출물에 표시한다.
- **2·3 이 4 보다 앞이라 남의 자원에 대한 잘못된 본문이 404 가 아니라 422 를 받는다.** 이것은 존재를
  누설하지 않는다 — 422 는 **모든** 식별자에 대해 같은 응답이기 때문이다. CU-8(타인 소유 → 404)은
  유효한 본문으로 재므로 이 순서와 충돌하지 않는다.

### 4.3 봉투 문제 — **이 커밋에서 가장 큰 설계 판단**

**문제.** `encryption_scheme`·`key_version` 은 **행 단위** 라벨이고 세 암호문 열 전부에 걸린다
(`JdbcConversionRepository.toStored`·`toEnvelope` 가 한 쌍의 값으로 세 `EncryptedContent` 를 만든다).
그리고 세대가 AAD 에 실린다. 따라서 **`edited_text_encrypted` 만 새 세대로 쓰고 행 라벨을 올리면
나머지 두 열이 영영 열리지 않는다.** `EnvelopeColumnWriteGuardTest` 가 정확히 이 사고를 막으려고
「암호문 열을 SET 하는 UPDATE 는 봉투 두 값도 SET 한다」를 탐지형으로 강제한다(documents-plan D-r).

**후보 셋.**

| 후보 | 성립하는가 | 대가 | 채택 |
|---|---|---|---|
| **(가)** 행 세대로 봉인 — 옛 키로 새 평문을 암호화 | 예 | **옛 키로 새 평문을 쓴다.** 회전의 동기가 키 유출이면 새 검수본을 유출된 키 아래 넣는 것이다. 게다가 `ContentCipher` 포트를 「세대를 골라 암호화」로 넓혀야 한다 | **기각** — 위 한 줄이 결정적이다 |
| **(나)** 매 검수 저장마다 세 열 전부를 쓰기 세대로 재봉인 | 예 | 공통 경로에서도 `easy_text_encrypted` 바이트가 바뀐다 → I-13 「초안 보존」 단언이 **암호문 동일성**을 못 쓰게 된다(가장 날카로운 탐지기를 잃는다). 최고 민감도 열(`masked_items_encrypted`)을 쓸 이유 없이 매번 쓴다 | 기각 |
| **(다)** **쓰기 세대로 통일하되, 행이 이미 그 세대면 나머지 두 열의 바이트를 그대로 되쓴다** | 예 | 파라미터를 계산하는 Kotlin 에 분기가 하나 생긴다. SQL 은 **한 모양**이다 | **채택** |

**(다)의 형태.**

- 잠근 행의 봉투가 `cipher.writeScheme`/`writeKeyVersion` 과 **같으면**: `easy`·`masked` 파라미터는
  **읽어 온 바이트 그대로**, `edited` 만 새로 봉인. → 공통 경로에서 `easy_text_encrypted` 바이트가
  **바뀌지 않는다**(I-13 암호문 동일성 단언 유지).
- **다르면**(회전 지연 창): 두 열을 열어 `PlainBody` 로 받고 **그 `PlainBody` 를 다시 봉인**한다
  (`MaskedItemCodec` 으로 디코드했다가 재인코드하지 않는다 — JSON 재직렬화는 평문을 바꿀 수 있다).
  행이 기회주의적으로 최신 세대로 올라간다.
- **어느 갈래에서도 옛 키로 암호화하지 않는다.** `ContentCipher` 포트를 **넓히지 않는다**(R-12).
- SQL 은 `rewriteEnvelope`(R-8)와 **같은 모양** — 세 열 + 봉투 두 값을 한 UPDATE 로. 여기에
  `reviewed_at` 과 상태 조건이 붙는다(§4.4). `EnvelopeColumnWriteGuardTest` 를 문언·의도 양쪽에서
  만족한다.

**초안 보존은 두 겹으로 단언한다** — ⑴ 공통 경로에서 `easy_text_encrypted` **바이트가 같다**
⑵ 회전 지연 갈래를 포함해 **복호화한 평문이 같다**. ⑵만 두면 (나)로 미끄러져도 초록이고, ⑴만 두면
회전 지연 갈래가 검사받지 않는다.

### 4.4 트랜잭션 경계 · 잠금 · 조건부 UPDATE · 0행의 처분

```
transaction.inTransaction {
    val locked = conversions.lockOwnedForReview(ownerId, conversionId)   // SELECT … FOR NO KEY UPDATE OF c
        ?: throw NotFoundException(CONVERSION_NOT_FOUND_MESSAGE)          // 404 — 없는 것과 남의 것을 구분하지 않는다
    if (locked.status != ConversionStatus.DONE) throw ConflictException(…) // 409
    val ciphertexts = seal(locked.envelope, normalized)                    // §4.3 (다)
    if (!conversions.saveReview(locked.envelope, writeScheme, writeKeyVersion, ciphertexts)) {
        throw StorageException(…)                                          // 0행 = 잠금 전제가 깨졌다 (아래)
    }
    query.read(ownerId, conversionId)                                      // §4.6
}
```

- **잠금 모드는 `FOR NO KEY UPDATE`** 다(`FOR UPDATE` 가 아니다). 회전 경로가 이미 그것을 쓰고,
  근거는 documents-plan §9.2-ter — 키 열을 바꾸지도 행을 지우지도 않으므로 `FOR KEY SHARE`(FK 검사가
  잡는 잠금)와 충돌할 이유가 없다. 조인 위에서 잠그므로 **`OF c`** 를 명시해 `documents` 행을 함께
  잠그지 않는다.
- **소유 술어가 SQL 안에 보인다.** `lockEnvelope`(소유권 없음)를 재사용하지 않는 이유가 이것이다 —
  이 저장소의 소유권 은닉은 「모든 조회에 `WHERE … AND user_id = ?` 가 빠짐없이 붙는다」로 성립하고,
  `OwnershipPredicateGuardTest` 가 그것을 본다.
- **낙관적 조건을 잠금과 **함께** 둔다.** 잠금이 서 있으면 조건은 언제나 참이므로 잉여처럼 보이지만,
  documents-plan §9.2-ter 가 실측으로 확인한 것이 정확히 그 반대다 — 트랜잭션이 열리지 않으면
  `FOR NO KEY UPDATE` 의 잠금은 문장이 끝나는 순간 풀리고, 그 상태에서 잠금은 아무것도 막지 않으며
  **그 사실을 알려 주지도 않는다.** 낙관적 조건은 **잠금 전제가 깨졌음을 알리는 fail-closed 카나리**다.
- **0행의 처분 = `StorageException`(500).** 409 로 접지 않는다. 잠금을 쥔 뒤에는 상태도 암호문도 바뀔
  수 없으므로 0행은 「사용자가 기다렸다 다시 하면 되는 일」이 아니라 **우리 전제가 깨진 일**이다.
  409 로 접으면 사용자에게 거짓 안내가 나가고 서버 결함이 정상 흐름으로 위장된다.
  - **Python 은 여기서 갈린다** — `save_review` 가 `False` 를 받으면 같은 409 를 던진다. 그쪽은 잠금
    없이 조건부 UPDATE 만 쓰므로 0행이 실제로 「워커가 상태를 바꿨다」였고, 그 구현에서는 409 가 맞다.
    **우리는 잠금을 쥐므로 같은 값이 다른 뜻이 된다.** 기준은 요구사항이지 Python 출력이 아니다
    (`CLAUDE.md`). 이 갈림을 산출물에 기록한다.
  - CU-3(409)은 5단계가 낸다. 「검사와 UPDATE 사이에 상태가 바뀌면 같은 409」라는 documents-plan §8.2
    한 줄은 **잠금 없는 설계를 전제한 문장**이므로, 이 계획이 그 자리를 위와 같이 바꾼다.
- **`saveReview` 의 WHERE 에 `status = 'done'` 을 함께 둔다.** 잠금 아래에서 잉여지만 위와 같은 이유의
  카나리이고, 「상태를 보지 않고 덮어쓰는」 갈래를 SQL 수준에서 없앤다.
- **`reviewed_at` 은 DB 시계**(`now()`)로 UPDATE 문 안에서 찍는다. 애플리케이션 시계는 프로세스마다
  어긋나고 검수 시각은 집계 기준값이다(documents-plan §9.1 이 이미 정한 것).
- **`updated_at` 은 건드리지 않는다** — 트리거가 없고 `DEFAULT now()` 는 INSERT 에만 걸린다. 검수 저장이
  이 열을 올려야 하는지는 요구가 없다. **§10 D-3 으로 올린다**(결정하지 않고 현행 유지).
- LLM 호출·문서 파싱 같은 긴 작업이 이 트랜잭션 안에 **없다**. 안에 있는 것은 SQL 3문장과 AES-GCM
  연산뿐이다.

### 4.5 `ReviewedBody` — 프로덕션 생성 지점 첫 등재

- **생성 지점은 정확히 하나**: `api` 의 PUT 어댑터가 요청 DTO 의 `edited_text` 를 읽어
  `ReviewedBody(...)` 로 감싸 유스케이스에 넘긴다. `ProvenanceCreationSitesTest.ALLOWED` 에 그 파일
  경로와 호출 수 **1** 을 더한다(T-1).
- **정규화 전에 감싸는가 후에 감싸는가.** 감싼 뒤 정규화하면 `ReviewedBody` 를 벗겼다 다시 감싸는
  자리가 생겨 생성 지점이 둘이 된다. **원시 문자열을 감싸 넘기고, 정규화는 유스케이스가 그 값에
  적용한 뒤 저장용 `PlainBody` 로 옮긴다.** 생성 지점 1개가 유지된다.
- **`export` 단위와의 긴장을 미리 적어 둔다.** `restoreForExport(draft, reviewed: ReviewedBody?, …)` 는
  저장에서 읽어 온 값을 `ReviewedBody` 로 감싸야 하는데, 그것은 **두 번째 생성 지점**이다. 오늘
  `ProvenanceCreationSitesTest` 의 실패 문구는 *"ReviewedBody 는 사람이 제출한 edited_text 를 읽는
  어댑터 한 곳뿐이다"* 로 「한 곳뿐」을 못박는다. **C7 은 그 문구를 고치지 않는다** — C7 이 만드는
  상태는 정확히 「한 곳」이고 참이다. 둘째 자리의 정당성(그 열에 쓰는 것이 검수 저장뿐이므로 열 자체가
  provenance 를 진다)은 `export` 단위가 판정할 몫이며, **§10 D-4 로 올린다.**

### 4.6 응답 · DTO · 헤더

- **응답 조립을 복제하지 않는다.** UPDATE 뒤 같은 트랜잭션에서 `ConversionQueryService.read` 를 부른다
  (R-11). 계약이 *"응답은 GET 과 같은 스키마다"* 라고 적은 것을 **같은 코드**로 만족시키는 형태이고,
  덤으로 CU-7(저장된 값이 정규화 값)을 **프로덕션 경로 자신이** 증명한다.
  - 대가: `read` 가 복호화를 트랜잭션 **안**에서 하게 된다. 오늘 `ConversionQueryServiceTest:134` 가
    「복호화가 트랜잭션 밖」을 고정하는데, 그것은 `read` 단독 호출의 성질이고 이 재사용이 그 단언을
    깨지 않는다(중첩 `inTransaction` 은 `TransactionTemplate` 기본 전파 REQUIRED 로 기존 트랜잭션에
    합류한다). **미확인 — §10 A-4.** 4,000자 AES-GCM 은 마이크로초 단위라 커넥션 점유는 문제가 아니다.
- **컨트롤러는 기존 `ConversionController` 에 `@PutMapping(CONVERSION_ITEM_PATH)` 를 더한다**(R-15).
  새 컨트롤러를 만들면 경로 상수가 두 벌이 된다.
- **사적 헤더 2종은 개별 부착**한다. 전역 필터가 있어도 개별 단언을 지우지 않는 이유는 X-D1 이 이미
  적었다 — 이 응답에는 `masked_items[].original` 로 **실제 개인정보가 실린다.**
- **`ConversionResponse` 는 그대로 재사용**한다(R-16). `of` 의 fail-closed `require` 는 이 경로에서
  도달 0 이다(`done` 인 자원만 200 을 내므로). 판정 §4-2 ㉡ 이 그 도달 0 을 결함으로 읽지 않는다고
  이미 못박았으므로 새 강제자를 만들지 않는다.

### 4.7 415 — CU-11(415)이 계약의 마지막 미측정 팔을 닫는다

- 새 코드를 쓰지 않는다. `GlobalExceptionHandler` 가 `HttpMediaTypeNotSupportedException` 을
  오버라이드하지 않는 **현재 상태가 곧 구현**이고(§1.4), 검증만 새로 붙는다.
- 케이스가 재는 것 셋: ⑴ 415 (**422 가 아님**) ⑵ `detail` **문자열** ⑶ **`Accept` 응답 헤더가 이
  오퍼레이션의 `requestBody.content` 키 집합에서 유도한 값과 같다.** ⑶ 을 **계약에서 읽어** 대조한다 —
  `x-unsupported-media-type.response_header_accept` 가 *"코드에 적지 않는다"* 를 명시했다.
- 계층은 **C-R**(실 소켓)이다. 프로브 컨트롤러가 아니라 **실제 오퍼레이션**으로 재야 그 팔이 닫힌다.
- `x-unsupported-media-type.x-auth-order-open`(무인증에도 415 가 401 보다 앞선다)은 **판정 대기**이고
  계약이 *"케이스도 쓰지 않는다"* 로 못박았다. **유효한 토큰으로만 잰다.**

### 4.8 마스킹 불변식 영향 — **검수 본문은 LLM 으로 가지 않는다** (확인함)

- `application/document` 에 `LlmProvider` 의존이 **없다.** 이 경로가 부르는 것은 `ContentCipher`·
  `ConversionRepository`·`MaskedItemReader`·`TransactionRunner` 넷뿐이고, 그중 어느 것도 provider 를
  참조하지 않는다. C7 은 그 목록에 다섯째를 더하지 않는다.
- 마스킹 선행 불변식(`CLAUDE.md` 아키텍처 규칙 2)이 요구하는 것은 「LLM 에 가기 전에 마스킹」이고,
  **이 경로는 LLM 에 가지 않으므로 그 순서의 적용 대상이 아니다.** documents-plan §9.1 이 업로드
  경로에 대해 적은 것과 같은 구분이다 — 이 단위가 지키는 것은 **암호화 선행**이다.
- 다만 **`MaskedTextGatewayTest` 를 약하게 만들지 않는다** — C7 이 `core` 의 마스킹 타입이나 게이트를
  건드리지 않으므로 그 테스트의 분모가 바뀌지 않아야 한다. 바뀌면 그 자체가 신호다(§7.4).
- 검수 본문에는 **자리표시자가 든 채로** 저장된다(원문 복원은 내보내기 전용). C7 은 복원 함수를
  부르지 않는다.

### 4.9 로그 규칙

- 기록 대상은 **conversion id · 상태 · 실패 코드** 뿐이다. 본문·길이 외 값·정규화 전후 값·`detail`
  문구 어느 것도 로그에 넣지 않는다.
- **예외 메시지를 로깅하지 않는다.** 특히 제약 위반은 PostgreSQL 이 DETAIL 에 **실패한 행 전체**를
  담으므로, 기존 `DocumentStorageLog.constraintViolation` 규약(SQLSTATE 만)을 그대로 쓴다.
- `ReviewedBody`·`PlainBody`·`EncryptedContent` 의 `toString` 이 이미 길이·바이트 수만 남긴다. 새 타입
  `LockedConversion` 에도 같은 규약을 적용한다(식별자·상태·세대만).
- **표 18 TRACE 카나리**는 C3 에서 이미 도달했다. C7 은 그 장치의 분모에 새 경로를 더하는 쪽이며,
  카나리 요청 목록에 PUT 을 넣을지는 §10 A-6.

---

## 5. 이월·판정 반영 (게이트 `04_documents-c6r2` → C7)

### 5.1 계약 판정 3건 — **확정 갈래로 적는다** (양 갈래 서술 불필요)

| 판정 | 결론 | C7 이 하는 일 |
|---|---|---|
| **P-1** | 500 저장 문구는 **비규범 예시**다. 규범은 성질 넷(500 · 문자열 · 고정 · 단일 키) | 단언을 **지우지 않고 재명명**한다 — 「계약 준수」가 아니라 **예시 신선도 대조**. 방향을 뒤집어 *"예시가 낡았다 — 예시를 갱신하라. 계약 위반이 아니다"* 를 실패 문구에 담고, **`examples.storage` 노드가 없으면 실패**하게 한다(fail-closed). `DomainExceptions.kt` KDoc 의 「예시와 같아야 한다」는 규범 주장을 「계약이 저장소에 위임한 값이고, 바뀌면 계약 예시를 함께 갱신한다」로 교체한다 |
| **X-8** | `detail` 모양은 **결함의 종류**에 묶인다. 전송 팔에 묶이지 않는다. `workspace_id` 형식 오류는 두 팔 모두 **422 문자열** | `DocumentDtos` JSON 팔의 `workspaceId: UUID?` → **`String?`**, `DocumentService.parseWorkspaceId`(`:153-158`) 를 `private` 에서 재사용 가능하게 열어 두 팔이 같은 함수를 쓴다. `earlierStageStatus` 는 그대로. **계약 개정을 기다리지 않는다** — 오늘 성립하는 구현 결함이다 |
| **X-12** | **KDoc 을 좁힌다.** 계약 예시는 그대로 | `DecryptionFailedException` KDoc 이 담을 것 셋 — ⑴ 원인은 구분하지 않는다(유지) ⑵ **문구는 자원을 특정한다**(「구분하지 않는다」로 적지 않는다) ⑶ 함정: 변환 결과가 아닌 봉투의 복호화 실패가 동기 응답으로 나가게 되면 이 문구를 다시 판정한다 |

**세 건 모두 `.kt` 주석에 리뷰 ID·날짜·커밋을 넣지 않는다**(§9).

### 5.2 이월 4건

| ID | 무엇 | 처방 | 배치 |
|---|---|---|---|
| **X-8** | 위 표 | 위 표 + **테스트 3건**: DC-27 JSON 팔(형식 오류 `workspace_id` → 422 문자열, 값이 multipart 팔과 **같음**) · DC-26 JSON 팔 복합(본문 길이 초과 + 형식 오류 → 길이 문구) · **두 팔 교차 단언**(`detail` 타입과 값이 같다를 **한 단언**에. 팔별로 따로 재면 갈림이 다시 조용히 들어온다) | **C7a** |
| **X-13** | `ConversionResponse`·`DocumentCreatedResponse`·`DocumentListItemResponse` 3개 DTO 의 주 생성자가 `of` 의 `require` 를 우회한다 | 주 생성자 `private` + `@ConsistentCopyVisibility` **3개 일괄**(§1.3 이 무경고를 확인) | **C7a** |
| **X-14** | CR-3b 의 두 기대값이 SQL 리터럴과 companion 상수 **두 곳**에 따로 적혀 있어 SQL 만 고치면 `doesNotContain(STORED_MODEL)` 이 공허해진다 | `ConversionReadReachTest.MARK_DONE_SQL` 에 `%s` 둘 추가해 기대값을 **한 곳**에서 오게 한다 | **C7a** |
| **X-15** | `beforeDone` 의 `id`·`document_id` **오배정이 미검사** | 단언 두 줄 — CR-3b 가 이미 `Pair<documentId, conversionId>`(R-20 `createDocument`)를 받으므로 재료가 있다 | **C7a** |

---

## 6. 커밋 분할 — **C7a / C7b 로 가른다**

| # | 커밋 | 내용 | 게이트 |
|---|---|---|---|
| **C7a** | `fix(kotlin): POST /documents JSON 팔 detail 모양 + 이월 정리` | X-8(구현+테스트 3) · X-13(3 DTO) · X-14 · X-15 · P-1 단언 재명명·fail-closed · X-12 KDoc | **G-γ** |
| **C7b** | `feat(kotlin): PUT /conversions/{id} — 검수 저장` | §4 전건 · CU-1~CU-11 · 유보 해제 T-1~T-3 · X-F9 마감 T-4 · T-5~T-7 인구조사 | **G-γ** |

**왜 한 커밋이 아닌가.**

1. **X-8 은 다른 오퍼레이션의 계약 위반 수정이다.** `POST /documents` 의 JSON 팔이고, 자기 음성 대조
   (DC-26·DC-27 JSON 케이스)를 데리고 온다. PUT 본체와 섞으면 게이트 회차가 「무엇이 무엇을 고쳤는지」
   를 가릴 수 없다 — 계약 위반 해소와 신규 오퍼레이션이 한 diff 에 들어가면 리뷰가 둘 다 얕게 본다.
2. **X-13 이 `ConversionResponse` 를 바꾼다.** C7b 가 그 DTO 를 쓰므로, 먼저 최종 형태로 만들어 두면
   C7b 는 완성된 타입 위에 쓴다. 순서를 뒤집으면 같은 파일이 두 커밋에서 두 이유로 바뀐다.
3. **둘 다 G-γ 한 덩어리 안이다.** 리뷰 주기가 늘어나지 않는다 — 게이트 25 판정 ③ 이 막으려던 것은
   「10커밋 사이에 들어온 것을 아무도 못 보는」 상태이고, 두 커밋을 한 회차로 묶는 것은 그것이 아니다.

**계약 명세 §6 「같은 변경 단위 요건」 준수**: 각 기능을 만드는 커밋 안에 그 기능의 단언이 함께 들어간다.
C7a 의 X-8 테스트 3건은 C7a 에, C7b 의 CU 전건은 C7b 에.

**어간은 리더가 정한다.** 이 계획은 회차 이름을 짓지 않는다.

---

## 7. 검증 계획

### 7.1 층 배치 (계약 명세 §5 준수 — 업로드·다운로드가 아니어도 실 소켓이 필요한 자리를 가린다)

| 층 | 도구 | C7 에서 무엇을 |
|---|---|---|
| **단위** | JUnit, Spring·DB 없음 | 정규화·빈 값·길이(코드 포인트)·경계값 판정 · `ReviewedBody` 시그니처 강제 · §4.3 (다)의 두 갈래 파라미터 계산 |
| **application 단위** | R-21 `World` 대역 확장 | 판정 순서 6단계 · 404/409 갈래 · 0행 fail-closed · 초안 보존(평문) |
| **DB** | Testcontainers PostgreSQL | 조건부 UPDATE 0행 · `FOR NO KEY UPDATE` 직렬화 · `reviewed_at` 이 **DB 시계** · 초안 보존(**암호문 바이트**) · 회전 지연 갈래 · 회전 동시 실행 |
| **C-M** | `@WebMvcTest` + MockMvc | CU-9(필수 필드 누락 → 배열 `detail`, 항목 키 정확히 3) · 경로 변수 비UUID → 422 배열 |
| **C-R** | `@SpringBootTest(RANDOM_PORT)` + 실 소켓 | **CU-11(415)** — `Accept` 헤더는 목으로 재현되지 않으면서 통과한다 · **CU-11(401)** — `WWW-Authenticate` |
| **C-I** | 위 + Testcontainers | CU-1~CU-8 전건 |

> **CU-11 이 오늘 스펙에서 두 케이스에 중복 부여돼 있다**(415 와 401). contract-keeper 가 재번호 중이며,
> 이 계획은 그때까지 **「CU-11(415)」·「CU-11(401)」로 의미를 병기**한다. 재번호 결과가 나오면 그
> 산출물(`04_contract-keeper_documents-test-spec.md` 갱신본)의 ID 로 갈아탄다. **번호를 이 계획이 새로
> 짓지 않는다** — 그러면 세 번째 벌이 생긴다.

### 7.2 케이스 ↔ 층 ↔ 계약 노드

| ID | 무엇을 재나 | 층 | 계약 노드 |
|---|---|---|---|
| CU-1 | 200 · 사적 헤더 2종(값·**개수**) · 응답 키 집합 = `ConversionResponse.required` | C-I | `.put.responses.200` · `x-private-response-headers.applies_to` |
| CU-2 | **초안 필드 보존** + 수정본 필드만 변함 + 검수 시각이 채워짐 | C-I | `.put.description` · `ConversionResponse.properties.edited_text` |
| CU-3 | 미완료 → **409**(422·404 아님) · `detail` 이 409 예시와 같다 | C-I | `.put.responses.409` |
| CU-4 | 제어문자만 → 422 · `detail` **문자열** · **빈 값 갈래** | C-I | `.put.responses.422.examples.empty` |
| CU-5 | 정규화 후 상한 **초과** → 422 문자열 / **정확히 상한** → 통과 | C-I | `fields[?edited_text]` (**X-F2**) |
| CU-6 | 원시 초과 · 정규화 후 이하 → **통과** (DC-11 의 대비 쌍) | C-I | `fields[?edited_text].measured_on` · `x-service-constraint.measured_on` (**X-F9**) |
| CU-7 | 저장 뒤 재조회 값이 **정규화 값**이다 | C-I | `.put.description` |
| CU-8 | 타인 소유 → 404 · **403 이 아님을 명시 단언** | C-I | `.put.responses.404` (**X-B1**) |
| CU-9 | 필수 필드 누락 → 422 · `detail` **배열** · 항목 키 정확히 3 | C-M | `ConversionReviewRequest.required` (**X-C2**) |
| CU-10 | PATCH 는 이 경로 계약에 없다 — **개별 케이스를 두지 않는다.** 서비스되는 (경로, 메서드) 집합이 계약 `paths` 와 같다는 단언(P-15, `ServedOperations`)이 겸한다 | C-M | `paths` 전체 |
| **CU-11(415)** | 415(422 아님) · `detail` 문자열 · **`Accept` = `requestBody.content` 키 집합 유도** | **C-R** | `x-unsupported-media-type` (**X-L2**) |
| **CU-11(401)** | 401 · `WWW-Authenticate` | **C-R** | `.put.responses.401` (**X-A1**) |
| 신설 S-1 | 저장 정의역 — 짝 없는 서로게이트 수정본 → 422 문자열 · `detail` = `x-stored-text-domain.detail` | C-I | `x-stored-text-domain.applies_to[?edited_text]` (**팔 마감**) |
| 신설 S-2 | 초안 보존 **암호문 바이트** 동일 (공통 경로) | DB | I-13 |
| 신설 S-3 | 회전 지연 갈래 — 행 세대 ≠ 쓰기 세대일 때 세 열이 쓰기 세대로 올라가고 **평문 셋이 전부 보존**된다 | DB | §4.3 |
| 신설 S-4 | 회전과 검수 저장이 동시에 돌아도 **잃는 쓰기가 없다** (`EnvelopeRotationConcurrencyTest` 형태 확장) | DB | documents-plan §9.2-ter |
| 신설 S-5 | 0행 → **500**(409 아님) — 잠금 전제 카나리 | application 단위 | §4.4 |

### 7.3 「떼면 무엇이 깨지는가」 — 음성 대조

| 장치 | 떼면 / 바꾸면 | 깨지지 **않아야** 하는 것 |
|---|---|---|
| 정규화(`stripControlChars`) 호출 | CU-4 · CU-6 · CU-7 빨강 | DC-11(`text` 원시 측정)은 **그대로**여야 한다 — 두 축이 한 값으로 뭉개졌으면 결함 |
| 길이 판정에서 `trim` 을 **추가** | 「앞뒤 공백이 저장된다」가 빨강 | CU-4(공백만 → 빈 값)는 그대로 |
| 빈 값 판정에서 `trim` 을 **제거** | CU-4 빨강 | CU-5·CU-6 그대로 |
| 코드 포인트 → `String.length` | 서로게이트 쌍이 든 경계값 케이스 빨강 | 나머지 길이 케이스 전부 초록(그래서 **경계값 케이스가 반드시 있어야** 이 변이가 잡힌다) |
| `status = done` 검사 제거 | CU-3 빨강 | CU-8(404) 그대로 |
| SQL 의 `AND status = 'done'` 제거 | 잠금 없는 컨텍스트의 카나리 케이스 빨강 | 정상 경로 전부 초록 (**그래서 카나리 케이스가 없으면 이 조건은 재지 않는 장치다**) |
| 낙관적 조건 제거 | S-4·S-5 빨강 | 정상 경로 초록 |
| `FOR NO KEY UPDATE` 제거 | S-4 빨강 | — |
| 소유 술어 제거 | CU-8 · X-B2(없는 것과 남의 것의 **응답 바이트가 같다**) 빨강 | — |
| `easy_text` 파라미터를 새 평문으로 | CU-2 · S-2 · S-3 빨강 | — |
| §4.3 (다)의 「같은 세대면 바이트 그대로」 분기 제거 (= (나)로 후퇴) | **S-2 빨강** | CU-2(평문 보존)는 초록 — **그래서 S-2 없이는 (나)로 미끄러져도 안 잡힌다** |
| `ContentCipher` 를 「세대를 골라 암호화」로 넓힘 (= (가)) | 포트 시그니처 대조 단언이 빨강이어야 한다 — **그 단언을 이 커밋이 만든다** | — |
| 사적 헤더 개별 부착 제거 | CU-1 빨강 (전역 필터가 있어도) | — |
| `ReviewedBody` 를 유스케이스 시그니처에서 `String` 으로 | 「모델 초안으로 이 유스케이스를 부를 수 없다」 컴파일 단언이 빨강 | — |
| `ProvenanceCreationSitesTest.ALLOWED` 항목 제거 | `허용하지 않은 생성 지점이 없다` 빨강 | — |
| `PINNED_WITHOUT_DTO` 를 비우지 않음 | `계약 필드 전부가 다뤄진다` 빨강 | — |
| **`private constructor` 만 붙이고 `@ConsistentCopyVisibility` 생략** (§1.3 실측 대조) | `allWarningsAsErrors` 로 **컴파일 실패**(양성) → 애너테이션 추가 시 통과(음성) | — |
| `Accept` 헤더 기대값을 코드 상수로 | 계약 노드 변이 시 빨개지지 않는다 — **계약에서 읽는지**를 N-40 형태 변이로 확인 | — |

**과잉 결합도 함께 본다** — 각 변이에서 깨지는 것이 표의 케이스뿐인가.

### 7.4 계약 값 음성 대조 (일회용 worktree · `cp` 로 복원하지 않는다 — 규칙 5)

| # | 변이 | 빨개져야 하는 것 |
|---|---|---|
| N-25 | `fields[?text].measured_on` 변경 | **DC-11 만.** CU-6 은 **깨지지 않아야** 한다 (C3 에서 이미 한 쪽 팔을 밟았고 **C7 이 대비 쌍을 닫는다**) |
| N-27 | `ConversionResponse.required` 키 제거 | CR-1 · **CU-1** |
| N-40 계열 | `x-unsupported-media-type` 의 `detail_source`/`response_header_accept` 변이 | CU-11(415) |
| 신설 | `x-stored-text-domain.detail` 변경 | S-1 |
| 신설 | `.put.responses.409.examples.not_done` 변경 | CU-3 |
| 신설 | `paths./conversions/{conversion_id}.put` 노드 삭제 | CU-10 이 겸하는 (경로, 메서드) 집합 단언 + `AuthenticationCoverageContractTest` |

복원은 `git checkout --` + `shasum -a 256` 대조 + worktree 제거로 한다.

---

## 8. 게이트 명령 — **전부 `run_gate.sh` 경유, 파이프 금지**

R-22 대로 인자는 **인용된 셸 명령 문자열 하나**다. 러너 호출 자체를 파이프에 태우면 무효가 된다.

```
.claude/skills/kotlin-migration/scripts/run_gate.sh 'cd backend-kotlin && ./gradlew ktlintCheck detekt build --continue --rerun-tasks'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'cd backend-kotlin && ./gradlew moduleBoundaryCheck'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest -q tests/test_kotlin_comment_budget.py'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest -q tests/test_kotlin_gate_reach.py'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest -q tests/test_harness_scope_reach.py'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run ruff check .'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run mypy . .claude'
.claude/skills/kotlin-migration/scripts/run_gate.sh 'uv run pytest'
```

- **`parityHarness` 는 도메인을 건드릴 때만.** C7 은 `core` 의 마스킹·프롬프트·스타일 규칙을 건드리지
  않으므로 **해당 없음**이 예상값이다. 건드리게 되면 그 자체가 계획 이탈 신호이고 §9.2 형태로 기록한다.
- **골든셋(`uv run pytest tests/golden`) 은 해당 없음** — 프롬프트·스타일 규칙·LLM 설정을 건드리지
  않는다. 건드리면 돌리고 결과를 보고한다.
- **Python 게이트 3종은 무변경 확인용이다.** `app/**` 을 건드리지 않았으므로 그대로 통과해야 한다.
  깨졌다면 건드리지 말아야 할 것을 건드린 것이다.
- **경고를 남긴 채 통과로 보고하지 않는다**(§6 Build 게이트 통과 기준). 실행하지 못한 검사는 「통과」가
  아니라 **「미실행」**으로 적는다.

---

## 9. `.kt` 주석 규약 (`CLAUDE.md`)

- **리뷰 ID(X-8·P-1·CU-6 …)·날짜·커밋 SHA·실측 로그·사건 이력·기각한 대안을 `.kt` 에 옮기지 않는다.**
  그것들은 이 계획과 게이트 산출물, 커밋 메시지에 둔다. 제품과 **테스트 `.kt` 에 같은 규약이 적용된다**
  (2026-08-23 X-16 — 갈린 것은 예산의 분모뿐이고 무엇을 써도 되는가는 갈리지 않았다).
- `.kt` 에 남길 것: 코드만으로 드러나지 않는 **불변식·외부 계약·함정**만, 가장 가까운 선언에 짧게.
  이 커밋에서 그에 해당하는 것 — ⑴ 봉투가 행 단위이고 세대가 AAD 에 실린다 ⑵ 낙관적 조건은 잠금
  전제의 fail-closed 카나리다 ⑶ `ReviewedBody` 생성 지점은 하나다 ⑷ `Accept` 기대값은 계약에서 읽는다.
- 기존 설명을 고칠 때는 **교체·압축**한다. 회고 절을 덧붙이지 않는다(X-12 KDoc 이 그 형태다).
- 테스트 의도는 `@DisplayName`·함수명·단언으로 먼저 표현한다.
- 완료 전 `uv run pytest -q tests/test_kotlin_comment_budget.py` 를 통과시킨다.

---

## 10. 모르는 것 · 판정이 필요한 것 — **추측으로 메우지 않는다**

### A. 구현 레인이 착수 직후 실측으로 닫을 것 (리더 판정 불필요)

| # | 무엇 | 어떻게 닫나 |
|---|---|---|
| A-1 | `charCountOf`(코드 포인트 계수)가 `DocumentService` 안에 `private` 인가 | 실측 후 `application` 공용 자리로 올린다. **두 번째 구현을 만들지 않는다** |
| A-2 | `PrivateHeaderFloorCensusTest` 는 `@WebMvcTest` 슬라이스다. PUT 성공을 몰려면 **`done` 상태 변환**이 슬라이스 대역에 있어야 한다 | `AuthSliceBeans` 형태의 인메모리 대역을 확장한다. 확장할 수 없으면 그 사실을 **미해결로 보고**하고 유보를 임의로 지우지 않는다 |
| A-3 | `ConflictException` → 409 매핑이 `GlobalExceptionHandler` 에 **이미 있는가** | 실측. 없으면 더한다 |
| A-4 | `SpringTransactionRunner.inTransaction` 중첩 시 기존 트랜잭션에 **합류**하는가 | 실측(전파 설정 확인 + 통합 테스트). 합류하지 않으면 §4.6 재사용 형태를 바꾼다 |
| A-5 | 새 테스트 클래스를 `MIN_TESTS_BY_NAMED_ENFORCER` 에 자동 등재시킬 것인가 | `ConversionController` KDoc 이 이름을 지목하면 자동 등재된다. **지목한다** — 지목하지 않으면 `TEST_CLASSES` 한 줄뿐이라 메서드를 지워도 안 잡힌다(X-7 이 지목한 구조) |
| A-6 | 표 18 TRACE 카나리 요청 목록에 PUT 을 넣을 것인가 | 넣는다. 카나리가 그 경로를 지나지 않으면 빨개지지 않는데, 그 상태가 「측정한 것처럼 보이는 통과」다 |

### B. 리더 판정이 필요한 것

| # | 무엇 | 왜 지금 | 이 계획의 잠정 전제 |
|---|---|---|---|
| **B-1** | **§4.3 후보 (다) 채택 승인.** 검수 저장이 행을 기회주의적으로 최신 세대로 올린다 — 즉 **사용자 요청 경로가 회전 작업을 일부 수행한다** | 보안 불변식 축(①)이고 `privacy-gate` 소관과 겹친다. (가)를 기각한 근거(옛 키로 새 평문을 쓰지 않는다)가 옳은지 확인이 필요하다 | (다)로 간다. **`privacy-gate` 에 통보한다** |
| **B-2** | **0행 → 500 (409 아님).** Python 과 의도적으로 갈리는 지점 | 사용자에게 보이는 상태 코드가 바뀐다. 계약은 500 을 선언하고 있어 위반은 아니다 | 500. 갈림을 산출물에 기록하고 `parity-verifier` 에 통보 |
| **B-3** | **C7a / C7b 분할 승인**과 어간 | 어간은 리더가 정한다(계획이 이름을 짓지 않는다) | 2커밋, 한 게이트 회차(G-γ) |
| **B-4** | `MIN_FLOOR_CENSUS_TARGETS` 8→9 · `MAX_DEFERRED_FLOOR_TARGETS` 2→1 (라쳇 상환, T-3) | X-10 이 **여섯 행 전부 리더 핀**으로 올려 둔 것과 같은 종류다 | 상향한다 |
| **B-5** | **`x-open-asymmetry` 의 성격 변화** — `text`(원시 측정)와 `edited_text`(정규화 측정)가 **같은 저장 경로·같은 정의역**을 지나게 되므로, 비대칭이 「입력 검사의 차이」에서 **「같은 열에 서로 다른 정의역의 값이 공존한다」**로 바뀐다 (판정 §4-2 ㉠) | C7 이 해소할 **의무는 없다.** 그러나 **아는 상태로 착수해야** 한다 — 모르는 채로 두면 `export` 단위에서 처음 터진다 | 해소하지 않는다. 계약이 미결로 표시해 둔 대로 두고, `parity-verifier` 와 `contract-keeper` 에 성격 변화를 통보 |

### C. contract-keeper 판정에 의존하는 갈래

| # | 무엇 | 의존 형태 |
|---|---|---|
| **C-1** | **CU-11 재번호** (415 / 401). 오늘 같은 ID 가 두 케이스에 붙어 있고 추적 표(X-A1·P-42·N-40)가 **양쪽 의미로** 인용한다 | 재번호 전에 C7 케이스를 쓰면 산출물이 두 벌 갈린다. **판정 §4-3 이 「C7 케이스를 쓰기 전에」로 순서를 정했다.** 이 계획은 의미 병기로 버티고, 재번호 결과 파일의 ID 로 갈아탄다 |
| **C-2** | 계약 문면 개정 **2건**(§2-4 ㉠ JSON 팔 `workspace_id` 처분 명시 · ㉡ `ValidationFailed` 의 「타입 불일치」 경계) | **C7 을 막지 않는다.** 리더 승인 뒤 별 커밋. X-8 구현은 개정과 무관하게 오늘 성립한다 |
| **C-3** | `x-unsupported-media-type.x-measured.not_reached` 와 `x-stored-text-domain.applies_to[?edited_text].status` 의 **계약 편집** | C7 이 **실측을 제공**하고 편집은 contract-keeper 가 한다. 두 팔이 열린 채면 Phase 4 종료 조건 미충족 |
| **C-4** | `x-unsupported-media-type.x-auth-order-open`(무인증 415 vs 401) — **판정 대기** | 계약이 *"케이스도 쓰지 않는다"* 로 못박았다. **유효 토큰으로만 잰다.** 판정이 나면 그때 케이스를 연다 |

### D. 계약에 답이 없어 이 계획이 정하고 표시하는 것

| # | 무엇 | 이 계획의 선택 | 근거 |
|---|---|---|---|
| **D-1** | PUT 의 **검사 순서**가 계약에 명문으로 없다(`POST /documents` 에는 있다) | 정규화·빈 값·길이(422) → 소유권(404) → 상태(409) → 정의역(422) | Python `save_review` 가 담은 순서 + `POST /documents` 의 「저장 정의역이 소유권 뒤」가 의도라는 계약 명문. **contract-keeper 에 조항 신설을 요청한다** |
| **D-2** | 저장 정의역 422 와 길이 422 가 **같은 오퍼레이션에서 순서가 갈린다**(전자는 소유권 뒤, 후자는 앞) | 그대로 둔다 | 두 판정의 위치가 구조적으로 다르다(`PlainBody` 는 암호화 직전). 복합 결함 케이스로 그 사실을 **명시 단언**한다 |
| **D-3** | 검수 저장이 `conversions.updated_at` 을 올려야 하는가 | **올리지 않는다**(현행 유지) | 요구가 없고, 트리거도 없다. 회전이 그 열을 건드리지 않기로 한 판단과 같은 자리 |
| **D-4** | `export` 가 `ReviewedBody` 를 만들면 **두 번째 생성 지점**이 된다 — `ProvenanceCreationSitesTest` 실패 문구가 「한 곳뿐」을 못박고 있다 | C7 은 문구를 **고치지 않는다**(C7 이 만드는 상태는 정확히 한 곳이고 참이다) | 둘째 자리의 정당성은 `export` 단위와 `privacy-gate` 가 판정할 몫 |

---

## 11. 산출물 · 통보

| 대상 | 무엇 |
|---|---|
| `docs/migration/_workspace/04_kotlin-implementer_c7.md` | 완성 모듈 · 의도적으로 다르게 구현한 지점과 사유(**B-2 0행 처분** · **D-1 검사 순서** · **§4.3 (다)**) · 미포팅 잔여 · 게이트 명령 실행 결과(미실행은 「미실행」) |
| `docs/migration/_workspace/04_kotlin-implementer_c7_improvement-backlog.md` | 발견했으나 **적용하지 않은** 개선 후보 |
| `parity-verifier` | 완성 모듈과 대응 Python 원본 경로(`app/services/documents.py` `save_review`) · **Python 과 갈린 자리 2건**(0행 처분 · 잠금 도입) · B-5 `x-open-asymmetry` 성격 변화 |
| `privacy-gate` | **B-1**(사용자 경로가 회전을 겸한다) · `ReviewedBody` 첫 프로덕션 생성 지점 · 저장 정의역 팔 마감 |
| `contract-keeper` | **C-1 재번호 결과 수령** · **C-3 두 팔 실측 제공** · **D-1 조항 신설 요청** |
| 리더 | §10 B 다섯 건 |

**리뷰 축 판정** — 이 변경이 닿는 7축 중 **①보안·개인정보 불변식**(`ReviewedBody` provenance · AEAD
봉투 · 소유권 은닉 404 · no-store) · **②외부 HTTP 계약**(신규 오퍼레이션 + X-8 계약 위반 수정) ·
**③게이트·탐지기 자신**(사적 헤더 하한선 유보 해제 · F3 마감 · 인구조사 갱신) **셋에 닿는다.**
**⑥문서 추출·내보내기 무결성은 인접**이다 — 저장 정의역이 내보내기가 터지지 않는 전제이지만 C7 이
내보내기 렌더링을 건드리지는 않는다. **따라서 반드시 게이트 회차를 돌린다**(codex 독립 리뷰 필수).
