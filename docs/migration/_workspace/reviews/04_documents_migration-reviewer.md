# 게이트 27 · 1회차 Claude 독립 리뷰 — `04_documents`

**회차**: 1차(독립). **교차 종합이 아니다** — 다른 리뷰어의 산출물을 읽지 않았고 찾지도 않았다.
정본은 3단계 `04_documents_cross.md` 이며, 이 파일만으로 Phase 종료 조건 충족을 보고하지 않는다.

**작성**: 2026-08-20 · `migration-reviewer` · HEAD `6515548`

---

## 1. 리뷰 범위와 참조한 절

### 1.1 대상

| # | 대상 | 무엇 |
|---|---|---|
| 1 | `df0766e` (C1) | 문서 추출기 — docx(POI)·pdf(PDFBox)·hwpx(StAX), 파서 방어 D-4~D-17 |
| 2 | `6515548` (C2) | 문서·변환 저장 경로 — 단일 트랜잭션·봉투·`V5__conversion_jobs.sql`·회전 |
| 3 | `385770e~1..6515548` (28커밋) | 하네스·스캐너·parity·계약 레인 조치분 |
| 4 | `e572476` | L1 규칙 본문 (`.claude/skills/kotlin-migration/SKILL.md`) — 내용으로 읽었다 |
| 5 | `reviews/04_gate25-fixes_cross.md` §10 | 미교차 3건 |
| 6 | `04_leader_compose-smoke.md` | 리더가 이번 회차에 실행한 compose 기동 스모크 |

### 1.2 참조한 계획 문서 절

- `04_kotlin-implementer_documents-plan.md` §1.5 · §4.1~§4.6 · §5(D-1~D-17) · §5.1 · §6.1~§6.5 ·
  §7.1~§7.2 · §8.1~§8.7 · §9(①~⑫) · §9.1 · §9.2(D-a~D-f) · **§9.2-bis(D-g~D-n)** · §10
- `contracts/easy-doc-v1.yaml` — `x-input-limits`(:325-342) · `POST /documents`(:915-995) ·
  `PayloadTooLarge`(:1594-1606) · `MaskedItemResponse`(:1895-1920)
- `00_progress.md` — Phase 4 표(:1518-1523) · 열림 원장(:1405-1406) · 게이트 26 판정(:1642-1711) ·
  인수인계 §1·§3·§6(:1736-1824)
- `04_kotlin-implementer_documents.md` §A~§H · `04_kotlin-implementer_improvement-backlog.md`
- `CLAUDE.md` 「선언한 범위와 실제 도달을 대조한다」 · `codex-review` 스킬 §4·§5

### 1.3 이 리뷰가 **직접 실행한 것** (인용이 아니라 실측)

| 명령 | 결과 |
|---|---|
| `uv run pytest tests/test_kotlin_gate_reach.py -q` | **90 passed** (파이프 없음) |
| `ls backend-kotlin/*/build/test-results/test/TEST-*.xml \| wc -l` | **115** — 실행 대조 축의 대상이 0건이 아니었다 |
| 새 7클래스의 리포트 XML 직접 파싱 | 아래 §5 T-3 |
| `git diff 2ed897d e891a08 -- <V2 경로>` 에서 주석·빈 줄 제외 +/- 라인 수 | **0** — compose 스모크 §3의 근거를 독립 확인 |
| `grep -rn 'supported_upload_formats'` (kt·py 전수) | 계약 파일 밖 적중 **2건, 둘 다 KDoc 주석** |
| 여섯 에이전트 정의에서 L1 규칙 문면 적중 | **전부 0** |

---

## 2. 심각도별 건수

| 심각도 | 건수 | 번호 |
|---|---|---|
| **차단(Critical)** | **1** | CR-1 (② 장치) |
| 수정 필요 | 6 | M-1 ~ M-6 |
| 권고 | 10 | A-1 ~ A-10 |
| 판정 필요 | 2 | J-1 · J-2 |
| 검토함 — 지적 없음 | 8구획 | §3~§7 각 축 말미 |

---

## 3. 축별 지적

### 3.1 계약 준수

#### M-1 (수정 필요 · 마감 **C3**) — 계획 D-13이 요구한 계약 대조 장치가 없는데, 코드는 있다고 적었다

- 계획 §5 D-13: *"구현은 상수를 갖고 **테스트가 계약 `x-input-limits.supported_upload_formats` 에서 읽어 케이스를 유도**(P-26)"*, 음성 대조 *"N-26 형태 — 계약 원소를 빼면 DC-14 가 줄어야 한다"*.
- 실측: `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/document/SourceFormat.kt:21` KDoc —
  *"이 파일과 계약이 갈리면 `DocumentExtractors` 의 계약 대조 테스트가 빨개진다"*.
  그런데 `infrastructure/src/test/.../ingest/DocumentExtractorsTest.kt:67-71` 은 계약 파일을 **읽지 않는다**:
  `assertThat(SourceFormat.UPLOAD_FORMATS.map { it.wireName }).containsExactly("docx", "pdf", "hwpx")`.
  저장소 전수 grep 결과 `supported_upload_formats` 를 읽는 코드·테스트는 **0건**이다.
- 즉 이 테스트는 **enum 드리프트만** 잡고 **계약 드리프트는 못 잡는다.** 계약에 형식이 하나 늘어도 아무것도 빨개지지 않는다. 그런데 KDoc과 `@DisplayName`(*"업로드 형식 집합이 **계약** enum 과 같다"*) 둘 다 계약과 대조한다고 적는다 — 다음 사람이 있는 줄 아는 가드다.
- 게다가 이 미구축이 §9.2 **이탈 기록이 아니라 개선 백로그 B-9**(`04_kotlin-implementer_improvement-backlog.md:130`)로 갔다. 계획이 요구한 장치가 「선택적 개선」으로 재분류된 형태다.
- 처방: ⑴ 문면을 실제에 맞게 낮추거나 ⑵ P-26을 C3에서 실제로 짓는다. **⑴만 하면 D-13은 여전히 미구축이다.**

#### A-1 (권고 · 수신 `contract-keeper` · 마감 C3) — 미뤄 둔 502 처분이 지금 무엇을 열어 두는가

리더 지목 ①에 대한 답이다. C2의 검사 순서 자체는 계약과 **같다**(§3.1 「검토함」 참조). 미뤄 둔 것이 여는 자리는 셋이다.

1. **없어진 것이 502 갈래만이 아니다.** 계약 `POST /documents` description(`:927`)은 *"저장 → **커밋** → 큐 등록(502)"* 이라고 **순서**를 적었다. 구현은 커밋 **전** 등록이다(`DocumentService.store` 안의 `storage.queue.enqueue`). 계약 레인이 C3에서 처분할 대상은 `'502'` 응답 선언(`:989`)과 `EnqueueFailed`(`:930`·`:1998`)만이 아니라 **이 순서 문장**이다. 세 자리 중 하나만 고치면 계약이 다시 자기 자신과 충돌한다.
2. **큐 등록이 실제로 실패하면 사용자는 502가 아니라 500을 받는다.** `JdbcConversionQueue.enqueue` 는 아무 예외도 잡지 않고, `DataIntegrityViolationException` 은 `EasyDocException` 이 아니므로 `GlobalExceptionHandler.handleUnexpected` → **500 `"서버 오류가 발생했습니다"`** 로 나간다. 502로 매핑된 `QueueUnavailableException`(`GlobalExceptionHandler.kt:385`)은 이 경로에서 **던져지지 않는다.** 계약은 502를 선언하고 구현은 500을 낸다 — 어느 쪽으로 정하든 **오늘 양쪽 다 테스트가 없다.**
3. 다만 **누출은 없다** — `handleUnexpected` 는 예외 **타입 이름만** 로깅하고(`:133`), `conversion_jobs` 행에는 사용자 콘텐츠가 없다. 이 갈래는 계약 정합 문제이지 보안 문제가 아니다.

#### A-2 (권고) — 같은 계약 개념 「문자 수」를 두 단위로 잰다

- `core/document/DocumentLimits.kt` 가 *"계약이 말하는 '문자 수' — **코드 포인트 수**다"* 로 단위를 정하고 `charCountOf` 를 `codePointCount` 로 구현했다(C2, D-n).
- 그런데 `infrastructure/ingest/ExtractedTextBuilder.kt::ensureWithinLimit` 은
  `if (builder.length <= MAX_EXTRACTED_CHARS) return` — **UTF-16 코드 단위**다(C1).
- 계약 `x-input-limits` 는 `max_convertible_chars`(:326)와 `max_extracted_chars`(:329)를 같은 절에 나란히 둔다. C2가 단위를 선언하면서 C1 자리를 함께 보지 않았고, §9.2-bis에 이탈 기록도 없다.
- 영향은 BMP 밖 문자가 많은 문서에서만(추출 상한이 선언보다 이르게 발화). 값은 다르되 **단위는 하나여야 한다**는 것이 C2 자신의 판단이다.

#### 계약 준수 — **검토함, 지적 없음**

- **응답 문구 4종이 계약 예시와 글자 그대로 같다**(실측 대조): `EMPTY_BODY_MESSAGE` ↔ `empty_body` ·
  `BODY_TOO_LONG_MESSAGE` ↔ `too_long` · `WORKSPACE_NOT_FOUND_FOR_DOCUMENT_MESSAGE` ↔ `workspace_not_found` ·
  `UPLOAD_TOO_LARGE_MESSAGE` ↔ `PayloadTooLarge.too_large`. `too_long`·`too_large` 는 상수에서 유도하되
  `Locale.ROOT` 로 자릿점을 고정해 실행 로케일이 응답 바이트를 바꾸지 못하게 했다.
- **검사 순서가 계약과 같다**(리더 지목 ①의 앞 절반): 크기(413) → 추출(422) → 본문 길이(422) →
  작업 공간 소유권(404) → 저장. `DocumentServiceTest` 의 「크기 판정이 추출보다 먼저다」·「길이 판정이
  작업 공간 조회보다 먼저다」가 그 순서를 각각 잰다.
- 소유권 실패가 403으로 빠지는 경로 없음 — `WorkspaceLookup.findOwnedId` 가 `null` 을 돌려주고
  `NotFoundException` 으로만 간다. 「없는 것」과 「남의 것」을 구분하지 않는다.
- snake_case·`ProblemDetail` 누출·CORS 노출 헤더 — **이 커밋 범위 밖**(HTTP 표면이 없다). C3 몫.

---

### 3.2 parity 위험

#### A-3 (권고 · 수신 `parity-verifier`) — hwpx 동명 항목이 조용히 덮인다

`ZipBudget.readEntries` 가 결과를 `LinkedHashMap<String, ByteArray>` 로 모은다(`ZipBudget.kt`).
zip은 같은 이름의 항목을 여럿 허용하고 commons-compress `getEntries()` 는 그것들을 각각 돌려주므로,
`Contents/section0.xml` 이 둘인 hwpx에서는 **뒤엣것만 남는다.** 예산은 둘 다 소모하므로 메모리 위험은
없으나, Python `infolist()` 순회는 둘을 각각 처리한다 — 동작이 갈리는 자리이고 DOC-01
「누락 없이 추출」에 닿는다. 갈림 자체를 기록할지 고칠지는 `parity-verifier` 판단.

#### A-4는 §3.3로 (보안 축) · A-5도 §3.3로

#### parity 위험 — **검토함, 지적 없음**

- **§9 질문 ⑪(hwpx 구역 읽기 비대칭)** — 두 자리 모두 청크 읽기로 고쳤고 사유가 `HwpxExtractor` KDoc과
  `ZipBudget.readEntries` KDoc 양쪽에 있다. 요구는 「실제 읽은 바이트로 센다」이지 「Python과 같다」가
  아니라는 판단이 명시적이다.
- **§9 질문 ⑫(`mc:Fallback` 네임스페이스 무관 스킵)** — C1 산출물 §2에 기록됨(범위를 좁힘).
- **`ExtractedTextBuilder` 의 Python 갈림 둘**(`str.splitlines()` 대 `lineSequence()`, `strip()` 의
  `U+00A0`(NBSP) 처리)이 KDoc에 전건 기록되고 좁은 쪽을 고른 사유가 붙어 있다. 백로그 B-8에도 있다.
- **PDF 줄 구분자 고정** — `lineSeparator`/`pageEnd` 기본값이 `System.lineSeparator()` 라 플랫폼 의존이고
  `stripControlChars` 가 `U+000D`(CR) 를 지우지 않는다는 실측까지 KDoc에 적혔다. `"\n"` 고정.
- **한글 코드 포인트 경계** — `takeCodePoints` 가 서로게이트 쌍을 쪼개지 않음을 `TitleRulesTest` 2건이
  단언한다. `varchar(255)` 가 문자 수를 세므로 255 코드 포인트가 컬럼에 정확히 들어간다.
- **파일명이 저장·로그 어디에도 남지 않는다**(D-17) — `SourceFormat.ofUploadFilename` 이
  널·빈값·경로·`..`·점파일을 전부 흡수하고 확장자만 쓰고 버린다.
- 프롬프트 문자열·어려운 말 목록 순서·보정 채택 판정은 **이 커밋 범위 밖**.

---

### 3.3 보안 불변식

> 이 축의 최종 차단 권한은 `privacy-gate` 에 있다. 아래 M-3은 그쪽 판정을 요청하는 항목이다.

#### M-3 (수정 필요 · 마감 **C6** · `privacy-gate` 판정 요청) — 소유권 은닉을 우회할 수 있는 유일한 자리에 강제자가 없다

- `DocumentRepository.loadSourceText(documentId)` 와 `ConversionRepository.loadEnvelope(conversionId)` 는
  **`ownerId` 를 받지 않는다.** 이 저장소에서 소유 조건이 SQL `WHERE` 에 없는 유일한 읽기 경로다.
- 강제 수단은 KDoc 한 문장뿐이다(`DocumentPorts.kt`):
  *"사용자 경로가 이 메서드로 남의 문서를 읽는 일이 없도록 호출자는 회전 유스케이스 하나로 제한한다."*
  실측: 호출자를 제한하는 테스트·아키텍처 규칙·린트 **0건**. 두 포트는 `DocumentConfiguration` 이
  빈으로 내므로 어떤 유스케이스에서도 주입받을 수 있다.
- 위험 경로: C6(`GET /conversions/{id}`)이 계획 §4.6의 「`documents` 조인으로 소유 조건을 WHERE에 합친다」
  대신 `loadEnvelope` 를 쓰면 그 자리에서 **다른 사용자 데이터 노출**(§5 Phase 7 즉시 중단 기준)이 성립한다.
- `CLAUDE.md` 규칙 4 — **범위 선언형은 빈 선언에서 통과하면 안 된다.** 여기는 선언만 있고 검사가 없다.
- 처방 후보: ⑴ 두 메서드를 `EnvelopeRotation` 만 볼 수 있는 별도 포트로 분리, ⑵ 호출부 탐지 회귀
  (`StatementCountingPremiseTest` 와 같은 종류 훑기), ⑶ 응답 계층에서 소유 조인을 강제하는 계약 테스트.
  ⑴이 구조이고 나머지는 탐지다.

#### A-4 (권고 · 마감 Phase 5) — 「행당 봉투 하나」를 워커가 깰 자리가 열려 있다

`ConversionRepository` 에는 결과 세 열을 **쓰는** 메서드가 없다(회전용 `rewriteEnvelope` 뿐).
Phase 5 워커가 `easy_text_encrypted` 를 쓸 때 `encryption_scheme`·`key_version` 을 함께 쓰지 않으면
「행의 세대는 v1인데 새 암호문은 v2」가 되고 그 행은 **영원히 열리지 않는다**(AAD에 세대가 실린다 —
`core/crypto/StoredContent.kt`). 지금 그것을 막는 것은 `ConversionCiphertexts` KDoc 문장뿐이다.
포트를 더하는 커밋이 그 성질을 함께 지도록 이 사실을 원장 열림 항목으로 올릴 것을 권고한다.

#### A-5 (권고) — 낙관적 조건이 `key_version` 만 보는데 `isCurrent` 는 `scheme` 도 본다

`EnvelopeRotation.isCurrent` 는 `scheme == cipher.writeScheme && keyVersion == cipher.writeKeyVersion` 이고,
`rewriteEnvelope` 의 WHERE는 `key_version = :expectedKeyVersion` 뿐이다. **세대는 그대로이고 방식만
바뀌는 회전**에서는 경합 판정(`CONTENDED`)이 발화하지 않는다. 오늘 방식이 하나(`aes256gcm-v1`)라 도달
0이므로 위험은 아니지만, 그 회전에서 `CONTENDED` 가 재현되지 않는다는 사실이 어디에도 없다.

#### 보안 불변식 — **검토함, 지적 없음**

- **평문의 노출면이 타입으로 닫혔다.** 저장소 포트가 `EncryptedContent` 만 받고 그것은
  `ContentCipher.encrypt(PlainBody, …)` 로만 만들어진다 — 암호화를 빠뜨린 저장은 **컴파일되지 않는다.**
  `PlainBody` 는 `infrastructure` 어느 파일에도 나타나지 않는다(`MaskedItemCodec` 의 반환 타입은 예외이나
  그것도 같은 강제를 쓰는 방향이다).
- **AEAD 결속** — `DocumentService.store` 가 UUID를 먼저 만들고 `EncryptedField.DOCUMENT_SOURCE_TEXT` 로
  결속한다. 계획 §4.1의 비대칭(작업 공간은 저장소가 UUID를 만들고 문서는 못 만든다)이 `DocumentDraft`
  KDoc에 명시돼 있다. `DocumentServiceTest` 의 「본문이 행과 컬럼에 결속된다」가 인자를 잰다.
- **평문 로그 0** — `DocumentService`·`EnvelopeRotation` 은 아무것도 로깅하지 않고,
  `DocumentStorageLog` 는 **SQLSTATE 다섯 글자와 상수 테이블명만** 남긴다(원인 사슬 깊이 상한 10).
  PostgreSQL이 제약 위반 DETAIL에 실패한 행 전체를 담는다는 사실이 근거로 적혔고, 백스톱인
  `GlobalExceptionHandler.handleUnexpected` 도 **예외 타입 이름만** 찍는다(`:133`) — 저장소의 좁은
  catch를 벗어난 `DataAccessException` 도 메시지를 흘리지 않는다. 이 두 겹을 실측으로 확인했다.
- **파서 방어**(I-10) — DTD는 파서 수준(`SUPPORT_DTD=false` + 외부 엔터티 2속성), zip 예산은
  **선언 크기를 믿지 않고 실제 읽은 바이트로** 센다(`read(min(chunk, remaining+1))`), OLE2 3분기,
  동시 추출 4, 추출 길이 상한을 **누적 중에** 건다. 예외 메시지는 전부 우리 고정 문구로 바뀌고
  로그에는 형식·바이트 수·사유 코드만 남는다(`ExtractionFailureLog`).
- **키 회전** — `encryption_scheme`·`key_version` 이 두 INSERT 문에 명시되고 `V3` 의 DEFAULT 제거와
  맞물린다(리더 지목 ②의 답은 §6 R-2).
- **마스킹 선행** — 이 경로의 대상이 아니다. 업로드에 마스킹이 없고(원본도 없다) 이 단위가 지키는
  선행은 **암호화 선행**이다. 계획 §9.1과 `DocumentService` KDoc이 그 구분을 적었다 — 게이트 문구를
  이 구분으로 읽어야 한다는 지적에 동의한다.

---

### 3.4 Kotlin/Spring 관용성

#### A-6 (권고) — `listOwned` 가 `JdbcClient.StatementSpec` 의 가변 빌더 동작에 기댄다

```
val statement = jdbc.sql(listSql(...)).param("ownerId", ownerId).param("limit", limit).param("offset", offset)
if (workspaceId != null) statement.param("workspaceId", workspaceId)   // 반환값을 버린다
```
`DefaultJdbcClient.DefaultStatementSpec.param` 이 `this` 를 돌려주기 때문에 오늘 동작한다.
인터페이스 계약은 그것을 약속하지 않는다(반환 타입이 `StatementSpec` 이다). 불변 빌더로 바뀌면
`:workspaceId` 가 바인딩되지 않아 **예외**가 난다 — 조용한 오답이 아니라 시끄러운 실패이므로 권고에 둔다.

#### A-7 (권고 · 마감 Phase 5) — 큐 멱등성은 현재 호출 지점에서 도달 0이다

`JdbcConversionQueue` KDoc: *"`ON CONFLICT (conversion_id) DO NOTHING` … 재시도가 같은 작업을 두 번 넣지
않는 것이 요점이고, 그것이 **중복 LLM 호출**(§5 Phase 7 즉시 중단 기준)을 막는 첫 겹이다."*
그런데 유일한 호출자 `DocumentService.store` 는 매번 새 `UUID.randomUUID()` 를 넘기므로 **충돌이 성립할
수 없다.** 그 「첫 겹」이 실제로 발화하는 경로는 Phase 5 워커의 재등록뿐이고 오늘 그 경로도 테스트도 없다.
장치 자체는 옳다 — 문면이 「오늘 막고 있다」로 읽히는 것이 문제다.

#### Kotlin/Spring 관용성 — **검토함, 지적 없음**

- **모듈 경계** — `core/document/**` 6파일이 Spring·Jackson·JDBC를 하나도 import하지 않고
  `application/document/**` 도 같다. Jackson은 `infrastructure/document/MaskedItemCodec` 에만 있다
  (계획 §4.6 그대로). LLM SDK 타입 유입 0. `moduleBoundaryCheck` 통과는 구현자 보고 인용.
- **트랜잭션 경계** — `TransactionRunner` 포트 + `TransactionTemplate` 이라 자기 호출 프록시 함정이
  구조적으로 없다. 문서·변환·작업 세 행이 한 경계 안이다(§4.4). 도메인 예외가 전부
  `EasyDocException : RuntimeException` 이라 롤백 규칙에도 걸린다(실측).
- **`JdbcClient` 유지** — JPA 애너테이션 0. 새 저장소 셋 전부 `JdbcClient` 생성자 하나.
- **Flyway** — `V5` 는 **신규 테이블 하나**이고 기존 테이블·컬럼·제약을 건드리지 않는다.
  `alembic_version` 미접촉. FK CASCADE가 `documents → conversions → conversion_jobs` 로 이어지고,
  부분 인덱스 술어에 `now()` 를 넣지 않은 이유(IMMUTABLE 아님)까지 주석에 있다.
- **`@Profile("!migrate")`** — 면제가 아니라 **의존성**이라는 사유가 `DocumentConfiguration` KDoc에
  적혔고, 부정 목록을 쓴 이유(새 프로필의 기본은 저장 경로를 갖는 쪽)도 `CryptoConfiguration` 과 같다.
- **`JdbcWorkspaceLookup` 분리** — 한 구상 클래스가 두 포트를 겸하자 `NoUniqueBeanDefinitionException` 이
  났다는 **실측**과 그 경위가 두 파일 KDoc에 있다. 「포트 하나당 구상 클래스 하나」로 간 판단이 옳다.

---

### 3.5 테스트 적정성

#### CR-1 (**차단 · ② 장치** · 마감 **Phase 4 종료 판정 전**) — 원장이 X5/F-5를 「실 DB에서 부분 실패 시 무변화를 잰다」로 닫았는데 그 케이스가 없다

**사실**

- `00_progress.md:1522` (마지막 갱신 주체 **`kotlin-implementer`**):
  *"**X5/F-5 닫혔다** — 포트가 세 열을 함께 받아 단일 UPDATE 를 구조로 강제하고, **실제 DB 에서**
  문장 수 2(SELECT+UPDATE) · NULL 보존 · **부분 실패 시 무변화** · 낙관적 조건을 잰다"*
- 계획 §8.2가 그 네 조건을 전부 **「실 PostgreSQL 이 필요한 것」** 으로 열거했다.
- 실측: `JdbcDocumentStoreTest` 20 케이스 전수에 **부분 복호화 실패 케이스가 없다.**
  회전 관련 실 DB 케이스는 「재암호화가 한 문장이다」·「회전한 행이 새 세대로 열린다」·「대기 중 변환의
  NULL 이 보존된다」·「낙관적 조건이 경합을 가른다」·「문서 원문을 회전한다」 다섯이다.
- 그 조건은 `EnvelopeRotationTest`(대역 저장소)의 「한 열이라도 실패하면 중단한다」가 *"UPDATE 를 부르지
  않는다"* 로만 잰다. **롤백 뒤 행이 실제로 무변화인가는 어느 층에서도 실행되지 않았다.**
- 이 배치 변경(실 DB → 대역)이 **§9.2-bis 이탈 기록에 없다.**

**왜 ② 장치인가**

- 원장 Phase 4 표는 리더가 종료 조건을 판정할 때 읽는 **게이트 기록 자체**다. 그 칸이 「실 DB에서 잰다」로
  적혀 있으면 아무도 다시 세지 않는다 — 검증되지 않은 종결이 검증된 것처럼 통과하는 경로다.
- 그 칸을 **심판 대상 레인이 직접 썼다.** 게이트 25 L-1이 세운 규칙(`e572476`)은
  `docs/migration/_workspace/reviews/**` 만 금지하므로 **종결 판정이 실제로 기록되는 원장을 덮지 못한다**
  (M-6과 짝이다). L-1이 닫으려던 「저작자와 심판자가 같아진다」가 원장에서 그대로 재발했다.
- **오늘 사건은 없다** — `EnvelopeRotation` 은 호출자가 0이고 성질 자체는 트랜잭션 롤백으로 성립할
  가능성이 높다. 차단으로 올리는 것은 **내용이 아니라 기록과 통로**다.

**마감 두 개**

| 무엇 | 마감 |
|---|---|
| 원장 문언 정정(실측과 일치시키거나 「대역 층에서 잰다」로 낮춘다) | **Phase 4 종료 판정 전** — 리더가 이 행을 근거로 쓰기 전 |
| 실 DB 부분 실패 케이스 신설(계획 §8.2대로) | **Phase 5 착수 전** — 회전에 호출자가 생기는 시점 |

착수를 차단하는지는 판정하지 않는다. 리더 몫이다.

#### A-8 (권고) — 48 핀의 KDoc이 실제보다 넓게 적었다

`SensitiveToStringReachTest` 의 `EXPECTED_SOURCE_DECLARATIONS` 46→48 주석:
*"나머지 문서 도메인 타입(`Document`·`Conversion`·`DocumentListing`·`DocumentDraft`·…)은 일반 class 라
이 수에 들어오지 않고, 그중 사용자 콘텐츠를 든 것은 손으로 쓴 `toString()` 을 갖는다 — **그쪽은
「R-10 일반 class 축」이 잰다**."*
실측: R-10 축(`일반 class 의 손으로 쓴 toString 이 값을 찍지 않는다`)의 후보는 「`toString()` 재정의를
**선언한** 일반 class」이므로 이 네 타입은 실제로 후보에 든다 — **문면은 참이다.** 다만 그 축은
바닥 목록(`KNOWN_SENSITIVE_TYPES`·`KNOWN_TEXT_WRAPPERS`)에 이 넷을 이름으로 갖지 않으므로,
누군가 `Document.toString` **재정의를 지우면** 후보에서 빠져 아무것도 빨개지지 않는다(그 방향은
`Any.toString()` 이라 안전하다 — 위험 방향이 아니다). 위험 방향(`data class` 로 바꾸기)은 48 핀이 잡는다.
**결과적으로 막혀 있다.** 다만 「R-10 축이 잰다」는 「이 넷을 이름으로 지킨다」로 오독되기 쉬우니
후보 선정 기준(재정의 선언 유무)을 그 주석에 한 줄 덧붙일 것을 권고한다.

#### A-9 (권고) — X9 통합 테스트의 한계가 그 파일에 없다

`DocumentStorageContextTest` 는 `ApplicationContextRunner` + `withUserConfiguration(CryptoConfiguration,
DocumentConfiguration, IngestConfiguration)` 로 **손으로 조립한 컨텍스트**다. `api`·`worker` 의 실제 Boot
컨텍스트가 `DocumentConfiguration` 을 실제로 스캔해 올리는지는 이 테스트가 재지 않는다
(실측상 `ApiApplication`·`WorkerApplication` 이 `scanBasePackages = ["kr.easydoc"]` 라 올라오기는 한다).
그 파일 KDoc은 여러 한계를 성실히 적으면서 이 한 줄이 없다.

#### A-10 (권고) — 원장 안에서 갱신 시점이 갈린다

- `00_progress.md:1406` 열림 원장에 **K-2가 여전히 「열림」**으로 남아 있다
  (*"K-2 / 기록 ④ — `CountingDataSource` 의 `JdbcClient` 전제를 KDoc 이 아니라 장치로"*).
  같은 파일 `:1522` 는 X9/F-6·X5/F-5를 갱신했고 C2 산출물 §B는 **셋 다** 「닫았다」로 적었다.
- `:1736-1748` 인수인계 §1·§2는 여전히 C2를 「다음 첫 동작」으로 적는다.
- 한 파일 안에서 두 시점이 섞이면 다음 사람이 어느 쪽을 현재로 읽을지 정할 수 없다.

#### 테스트 적정성 — **검토함, 지적 없음**

- **T-3 실행 기록을 직접 확인했다** (인용이 아니다). Gradle 리포트 XML 115건이 있는 상태에서
  `uv run pytest tests/test_kotlin_gate_reach.py -q` → **90 passed**. 새 7클래스의 리포트 실측:

  | 클래스 | tests | failures | skipped |
  |---|---|---|---|
  | `DocumentServiceTest` | 18 | 0 | 0 |
  | `EnvelopeRotationTest` | 11 | 0 | 0 |
  | `TitleRulesTest` | 15 | 0 | 0 |
  | `StatementCountingPremiseTest` | 2 | 0 | 0 |
  | `DocumentStorageContextTest` | 4 | 0 | 0 |
  | `JdbcDocumentStoreTest` | 20 | 0 | 0 |
  | `MaskedItemCodecTest` | 9 | 0 | 0 |

  즉 **핀 78→85는 빈 분모에서 초록이 된 것이 아니다.**
- **X9/F-6이 「503을 잘 낸다」가 아니다** — 리더 지목 ③의 답은 §6 R-3.
- **Testcontainers가 제약·잠금·cascade·시계를 실제로 건드린다**: NOT NULL(봉투 누락) · `V4` CHECK ·
  FK CASCADE 3단 · `retention_expires_at` DB 시계 30일 · `created_at DESC, id DESC` 안정 정렬 ·
  `LEFT JOIN LATERAL` 최신 변환 하나 · 소유자 조건이 작업 공간 필터와 함께 남는가.
- **실패 경로가 있다** — 성공 경로만 있는 모듈이 없다: 큐 등록 실패 롤백 · 남의 작업 공간 404 ·
  키 없는 cipher → `ConfigurationException` · 형식 오류 → 5xx · OLE2 3분기 각각 · zip 예산 초과 ·
  DTD 폭탄 · 손상 파일.
- **`MaskedItemCodec` 9건이 계획 §4.6을 전건 덮는다**(리더 지목 ⑤의 답은 §6 R-5).
- **거짓 빨강을 실측으로 밟고 구조로 막았다** — 산출물 §F.1 ⑵: 저장소와 트랜잭션 관리자가 다른
  `DataSource` 를 보면 롤백이 아무것도 되돌리지 않는다. 테스트 헬퍼 인자를 `DataSource` 하나로 좁혀
  그 갈림을 호출부마다 다시 만들 수 없게 했다. **이것이 이번 회차에서 가장 값진 자기 발견이다.**
- `MaskedItemCodec.encode` 의 `json.writeValueAsString` 이 `try` 밖이다(직렬화 예외 메시지에 값 조각이
  섞일 수 있다 — `decode` 는 그 자리를 막았다). 도달은 Phase 5이므로 **A-8에 흡수하지 않고 여기 기록만** 한다.

---

## 4. 함께 본 범위 — `385770e~1..6515548` 의 나머지 · `e572476` · cross §10

### 4.1 `e572476` — L1 규칙 본문 (내용으로 읽었다)

- 이 커밋은 **`.claude/skills/kotlin-migration/SKILL.md` 한 파일, 삽입 2줄**이다.
  `codex-review` §2.2와 `kotlin-migration` 자신의 면제 표가 **`.claude/**` 는 면제하지 않는다**고
  명시하므로, 지금까지 리뷰를 받지 않은 상태였던 것이 규약 위반이다(리더 판정 ⑤가 이번 범위로 넣었다).
- **규칙 문면 자체는 옳다.** 장치 분류는 **강제·표현형**이고, 근거(실측 3건 `6be9612`·`01d78a1`·`ea36330`)
  대비 범위(모든 조치 레인 × `reviews/**`)가 넓어 보이지만 결함이 **구조적**이다(저작자 == 심판자).
  `CLAUDE.md` 규칙 4가 요구하는 「종류로 댈 수 있으면 그 종류만큼」에 맞는다. **은폐형이 아니다** —
  면제·억제를 더하지 않고 금지를 더했다.

#### M-6 (수정 필요 · 마감 **즉시**) — L1 규칙의 도달이 그 규칙이 묶는 여섯 에이전트 전부에서 0이다

- 실측: `.claude/agents/*.md` 여섯 파일 전수에서 L1 문면 적중 **0**, `reviews/**` 패턴 적중 **0**.
  `privacy-gate.md` 는 「스캐너」·`scan_privacy` 를 **한 번도 언급하지 않고**, 그 파일의 스킬 목록은
  `migration-safety-gate` 하나다 — 즉 규칙의 둘째 조항(*"`privacy-gate` 는 스캐너를 고치지 않는다"*)이
  **겨냥한 바로 그 에이전트에게 도달하지 않는다.**
- 규칙은 오케스트레이터 스킬에만 있으므로 강제자는 **리더의 위임 프롬프트와 리뷰 게이트**뿐이다.
- **이 저장소에 이미 선례가 있다.** `CLAUDE.md` 「구현 전 리서치·계획」 절은 같은 형태를 지적받은 뒤
  *"강제자는 현재 리더의 위임 프롬프트와 리뷰 게이트의 지적 대상 판정이며, **자동 탐지는 없다**
  (2026-08-19 게이트 26 codex C-1 — 전칭 선언에 강제자 0. 이 문장이 없으면 이 절 자신이 아래
  「선언한 범위와 실제 도달을 대조한다」가 금지한 형태가 된다)"* 를 문면에 박았다.
  L1 규칙에는 그 문장이 없다.
- 처방(둘 중 하나 이상): ⑴ 선례대로 강제자를 규칙 문면에 명시, ⑵ 조항을 각 에이전트 정의로 전파
  (특히 `privacy-gate.md` 의 스캐너 조항). ⑴이 최소 비용이고, ⑵ 없이는 `privacy-gate` 가 그 조항을
  읽을 경로가 없다.

#### 그리고 **L1의 금지 범위가 종결 판정이 실제 기록되는 자리를 덮지 않는다**

규칙은 `docs/migration/_workspace/reviews/**` 만 금지한다. 그런데 Phase 종료 조건의 충족·미해결은
**`00_progress.md`** 에 기록되고, 이번 회차에 그 파일의 Phase 4 행을 갱신한 주체가
`kotlin-implementer`(심판 대상)다. **CR-1이 그 빈자리의 첫 실증이다.** 규칙을 넓힐지, 원장 갱신 주체를
리더로 고정할지는 리더 판정 사항으로 올린다.

### 4.2 cross §10 미교차 3건의 현재 상태

| # | 미교차 항목 | 이 회차 판정 | 근거 |
|---|---|---|---|
| ① | 「git 의 텍스트/바이너리 분류」를 기준으로 삼으면 7건 중 1건만 잡는다 | **해소** | `a1d6005` 의 `tests/test_raw_control_chars.py` 가 판정 기준을 **「원시 제어문자 보유」**로 두고 그 근거로 §10-①을 직접 인용한다. git 분류는 **제외 사유가 아니라 가중 사유**로 뒤집혔고, 진짜 바이너리는 「UTF-8 전체 디코드 실패」라는 **성질**로 가른다. 면제 목록 없음 · 0건 훑기 시 실패. 잔여: 분모가 `git ls-files` 라 **미추적 파일은 밖**이다(이 리뷰의 규약도 같은 사실을 적었다) |
| ② | `ci.yml` 의 `kotlin` 잡이 지금 빨간지 확인되지 않았다 | **해소 — 다만 새 열림을 낳았다** | 게이트 26 리더 판정 ⑧이 즉시 확인했고 그 결과가 §4-①로 등재됐다. 파생 열림: 인수인계 §3 순위 6 — *"`llm-lane` 타임아웃 … **품질 차단축 3개의 CI 도달이 0인데 배선돼 있어 도달한 것처럼 보인다**"*. 이 회차가 그것을 다시 재지는 않았다 |
| ③ | 하네스 자기 보고 H(27 passed)와 실측(1 failed / 26 passed)이 갈린다 | **부분 해소 — 원 질문은 답 없이 소멸했다** | `446f946`·`94db3df` 가 분모를 이름 열거 23 → 파생 70으로 바꾸고 `<skipped>` 를 실행에서 빼고 역방향 축을 신설해 스위트 자체가 교체됐다(`1 failed, 26 passed` → `73 passed`, 오늘 85). 기제(거짓 초록·거짓 빨강)는 닫혔다. **그러나 원 질문 — 「어떤 리포트 상태에서 H 가 통과했는가 = 리포트 기반 실행 증거가 언제 참이고 언제 거짓인가」 — 은 재현 대상이 사라져 답이 나오지 않았다.** J-2로 올린다 |

### 4.3 나머지 26커밋 — 요지

리뷰 대상으로 훑되 이번 회차의 무게는 C1·C2에 두었다. 눈에 띈 것 둘만 적는다.

- **`f282ff3`·`aeca7c6`·`a68facd`·`a1d6005`·`7f05652`** 가 원시 제어문자 축을 발생 시점(생성기)·
  기존 산출물·제품 소스·전수 탐지·JSON 쓰기 경로 다섯 자리에서 함께 닫았다. **탐지형 하나 + 발생 차단
  넷**의 조합이고 면제 목록이 없다 — 이 배치의 형태가 좋다.
- **`6040978`**(OWNERSHIP-403 이 논리 줄을 보게 함)과 **`d6abe51`**(값이 빈 키 세대는 kcv 가 있어도
  기동을 막는다)은 둘 다 **은폐 방향이 아니라 탐지 방향**으로 넓힌 변경이다. 후자는 compose 스모크
  A-2에서 **컨테이너에서 처음 관측**됐다(스모크 §2.1) — 장치와 관측이 짝을 이룬 드문 사례다.

---

## 5. compose 기동 스모크 (`04_leader_compose-smoke.md`) — 리더 지목 ⑧

### 5.1 §4 「증명하지 않는 것」의 정직성 — **네 항목은 정직하고 정확하다**

- 「업무 흐름을 재지 않았다」 · 「**CI 도달 0**」 · 「`kotlin-migrate` 는 여전히 환경으로 키를 받는다
  (`docker inspect` 실측, Phase 7 마감 유지, **「compose 층에서도 닫혔다」고 적으면 거짓**)」 ·
  「키는 로컬에서 만든 것이고 검사값만 적었다」 — 넷 다 이 리뷰가 반박할 근거를 찾지 못했다.
- §3의 Flyway 체크섬 정정 근거를 **독립 확인했다**: `git diff 2ed897d e891a08 -- <V2 경로>` 에서
  주석(`--`)과 빈 줄을 제외한 +/- 라인 수가 **0**이다. repair가 「다르게 적용된 것을 같다고 우기는」
  조작이 아니라는 주장은 참이다. 파괴 시도가 승인 게이트에 막힌 사실과 그것이 옳았다는 판단도 정확하다.
- KCV 공개는 문제 없다 — 로컬 생성 키의 검사값이고 `.env` 는 미추적이다.

### 5.2 M-5 (수정 필요 · 마감 **C3 종료 전**) — 빠뜨린 한계 하나

**이 실행은 `269fe28`(C2 **이전**)에서 돌았고, C2가 그 형상을 바꿨다.** §1이 착수 HEAD를 적었으나
§4가 그 결과를 끌어내지 않는다. C2 이후 달라진 것 둘:

1. **`DocumentConfiguration`(`@Profile("!migrate")`)이 `api`·`worker` 컨텍스트에 새로 들어온다.**
   `ContentCipher`·`DocumentTextExtractor`·`TransactionRunner` 를 요구하고 빈 8개를 낸다.
   **compose 층에서 그 조립이 뜬 적이 없다.** (테스트 층에서는 뜬다 — §3.5 T-3.)
2. **`V5__conversion_jobs.sql` 이 새로 생겼다.** §2.3의 *"Flyway 이력 최종: `1`·`2`·`3`·`4` 전건
   `success = true`"* 는 지금 **낡은 값**이고, `V5` 는 Testcontainers에서만 돌았지 compose의
   `kotlin-migrate` 에서는 한 번도 적용되지 않았다.

§5 「P4 닫힘. C2 착수를 막는 선결은 없다」는 **선결 판정으로서 옳다.** 고쳐야 할 것은 §4가
「이 관측은 `269fe28` 형상에 묶인다」를 적지 않아, 다음 사람이 이것을 **현재 배포 형상의 증거**로
읽을 수 있다는 점이다. 한 줄이면 된다.

---

## 6. 도달 범위 점검 (다섯 축을 가로지르는 필수 구획)

> 지적이 없어도 비워 두지 않는다. 「검토함 — 지적 없음」과 「미검토(사유)」는 다른 정보다.

### 6.1 장치 분류 먼저

| 장치 | 분류 | 이 회차 판정 |
|---|---|---|
| 게이트 핀 78→85 (`TEST_CLASSES`) | 탐지형 + 범위 선언형 | **초록이 진짜다** — R-1 |
| K-2 `StatementCountingPremiseTest` | 탐지형 | 비어 있지 않음은 강제됨. **하한이 없다** — M-4 |
| `SensitiveToStringReachTest` 46→48 | 탐지형 + 범위 선언형 | 정확 일치 + 하한 + 바닥 목록. 문면만 손볼 것 — A-8 |
| 봉투 명시 INSERT ↔ `V3` DEFAULT 제거 | 강제형 | **맞물린다 — 음성 대조까지 있다** — R-2 |
| X9/F-6 `DocumentStorageContextTest` | 탐지형(자기점검 가동 여부) | **「503을 잘 낸다」가 아니다** — R-3 |
| X5/F-5 4조건 | 구조 강제형 + 탐지형 | 셋은 구조로 강제됨. **넷째의 층이 이동했고 원장은 옛 층으로 적었다** — CR-1 |
| `MaskedItemCodec` | 강제형(타입) + 탐지형(왕복·고정 문자열) | **검토함, 지적 없음** — R-5 |
| `PoiZipDefenses` 구조 단언 | 강제·표현형 | 한계가 코드·테스트·계획 세 곳에 기록됨 — **모범 사례** |
| `SecureXml` 3속성 | 강제·표현형 | 테스트는 정직. **계획 문면이 거짓** — M-2 |
| D-13 계약 대조 | 탐지형 | **미구축 + 있다고 적힘** — M-1 |
| `loadSourceText` 호출자 제한 | 범위 선언형 | **강제자 0** — M-3 |
| L1 규칙(`e572476`) | 강제·표현형 | **묶는 에이전트 전부에 도달 0** — M-6 |
| compose 스모크 | 관측 | 정직하되 한계 하나 누락 — M-5 |
| `test_raw_control_chars.py` | 탐지형 | 면제 없음 · 0건 실패 · 기준을 성질로 — **검토함, 지적 없음** |

### 6.2 R-1 — 게이트 핀 78→85는 빈 분모에서 초록이 되지 않는다 (리더 지목 ⑥, 셋 중 첫째)

**검토함 — 지적 없음.** 근거는 실행이다.

- 분모가 **이름이 아니라 종류**다: `backend-kotlin/**/src/test/**` 의 최상위 `class`/`object` 중
  JUnit 애너테이션을 품은 것 전부. **면제 목록이 없다.**
- 축이 다섯이고 그중 셋이 빈 분모를 직접 막는다 — ⑴ 선언↔발견 **양방향 정확 일치**,
  ⑵ `TEST_CLASS_COUNT` 별도 상수, ⑸ **리포트 → 선언 역방향**(Gradle이 소스 파서를 교차 검증).
  `test_테스트_클래스_선언이_비어_있지_않다` 가 빈 선언을 명시적으로 거부한다.
- 이 리뷰가 직접 돌렸다: **90 passed**, 리포트 XML 115건 존재 → 실행 대조 축의 대상이 0건이 아니었다.
- 새 7클래스 전건이 리포트에 `failures="0"` · `skipped` 0으로 있다(§3.5 T-3 표).

### 6.3 M-4 (수정 필요 · 마감 **C3**) — K-2 탐지기의 분모에 하한이 없다 (리더 지목 ⑥, 둘째)

구현자가 *"K-2 탐지기의 분모 목록을 눈으로 열거하지는 않았다"* 고 적었다. **이 리뷰가 열거했다.**

`kr.easydoc.application` 포트를 구현한 `kr.easydoc.infrastructure` 구상 클래스(오늘):
`JdbcUserRepository` · `JdbcWorkspaceRepository` · `JdbcDocumentRepository` · `JdbcConversionRepository` ·
`JdbcConversionQueue` · `JdbcWorkspaceLookup` · `SpringTransactionRunner` · `AesGcmContentCipher` ·
비밀번호 해셔 · 토큰 발급기 · `DocumentExtractors` · `ConcurrencyLimitedTextExtractor` 급 — **10건 이상.**

**지적 셋.**

1. **하한이 없다.** `분모가 비어 있지 않다` 는 `isNotEmpty()` 와 「`JdbcClient` 를 받는 어댑터가 하나
   이상」만 요구한다. 크롤이 10+ 에서 **1로 줄어도 두 단언 모두 통과한다.** 같은 저장소의
   `SensitiveToStringReachTest` 는 `MIN_PRODUCTION_CLASSES` **와** 바닥 목록을 둘 다 갖는다 —
   이 자리만 그 규율이 빠졌다. 이 계측이 **소유권 은닉의 구조 축(X-B2)** 근거로 쓰이므로 마감은 C3다.
2. **KDoc 「막지 못하는 것」에 두 항목이 빠졌다.**
   ⑴ `implementsApplicationPort` 가 **직접 선언한 인터페이스만** 본다
   (`generateSequence(type){it.superclass}.flatMap{it.interfaces}`) — 중간 인터페이스가 포트를 상속하면
   분모 밖이다. ⑵ 분모가 `kr.easydoc.infrastructure` 로 한정돼 **`api`·`worker` 의 포트 구현은 처음부터
   밖**이다.
3. 다만 **좋은 점을 함께 적는다**: `load()` 가 적재 실패를 `error()` 로 끊는다
   (*"조용히 건너뛰지 않는다 — 빠진 클래스는 검사받은 것과 구분되지 않는다"*). 이 한 줄이 이 종류
   탐지기에서 가장 자주 비는 자리다.

### 6.4 R-2 — 봉투 2값 명시 INSERT와 `V3` DEFAULT 제거 의도가 맞물리는가 (리더 지목 ②)

**검토함 — 지적 없음.** 맞물린다. 근거 셋.

- `V3` 실측: `ALTER TABLE {documents,conversions} ALTER COLUMN {encryption_scheme,key_version} DROP DEFAULT`
  네 문 + CHECK 도메인 `aes256gcm-v1`. 설계 의도는 *"컬럼을 빠뜨린 INSERT 가 NOT NULL 위반으로 즉시
  시끄럽게 실패한다"* 다.
- C2의 두 INSERT 문이 네 컬럼을 전부 명시하고, 값이 `EncryptedContent`/`ContentCipher.write*` 에서 온다 —
  「암호문은 v2인데 컬럼은 v1」이 타입으로 막힌다.
- **음성 대조가 있다**: `JdbcDocumentStoreTest` 의 「`encryption_scheme` 을 빠뜨린 INSERT 는 NOT NULL
  위반으로 즉시 실패한다」가 실 DB에 컬럼 뺀 INSERT를 던진다. 즉 **`V3` 의 의도가 실제로 발화하는지**를
  잰다. `V4` CHECK도 「도메인 밖 세대는 DB 가 거부한다」로 새 INSERT 경로에서 재확인된다.
- 대기 중 변환도 봉투를 적고(`missing_placeholders` 는 DEFAULT를 그대로 쓴다 — *"이 기본값은 데이터에
  대해 **참**"* 이라는 구분이 명시적이다). 이 구분이 이 커밋에서 가장 정확한 판단 중 하나다.

### 6.5 R-3 — X9/F-6이 「조립된 빈이 실제 키로 돈다」를 증명하는가 (리더 지목 ③)

**검토함 — 증명한다. 「503을 잘 낸다」의 초록이 아니다.**

`DocumentStorageContextTest` 4건이 각각 다른 것을 잰다.

1. 제품 `@Configuration` 셋으로 조립한 컨텍스트가 **실 PostgreSQL에 INSERT** 하고, 컬럼을 직접 읽어
   본문이 없음을 확인하고, 같은 컨텍스트의 `ContentCipher` 빈이 그것을 **다시 연다**.
2. **틀린 KCV를 주면 컨텍스트가 뜨지 않는다** — 이것이 「자기점검이 꺼진 채 통과」와 가르는 축이고,
   계획 §4.4-3이 *"속성 우선순위를 가정하지 않고 실측한다"* 로 요구한 바로 그 케이스다.
3. 키가 없으면 기동을 거부한다(C-P).
4. **쓰기 세대 1 컨텍스트가 쓴 행을 쓰기 세대 2 컨텍스트가 회전**하고 다시 연다. 두 번째 회전은
   `ALREADY_CURRENT` 임까지 단언한다.

키는 실행 시점 `SecureRandom` 이고 KCV는 **제품 코드 `KeyCheckValue.of`** 로 계산한다 — 소스에 리터럴이
없어 스캐너 `SECRET-LITERAL` 에 걸리지 않으면서 F-3 대조 경로가 실제로 돈다. 계획 §4.4-5의 「키를 빼면
업로드 503」이 조립 경로에서 도달 불가가 된 사실도 **§9.2-bis D-h에 사유와 함께 기록**하고 그 갈래를
빈 층(`JdbcDocumentStoreTest.쓰기 키가 없으면 아무것도 남지 않는다`)으로 옮겼다. 한계 하나는 A-9.

### 6.6 R-4 — X5/F-5 4조건이 구조로 강제되는가 (리더 지목 ④)

**셋은 구조로 강제된다. 넷째는 구조가 아니라 실행 순서에 의존하고, 그 실행이 옛 층에 없다.**

| 조건 | 강제 형태 | 판정 |
|---|---|---|
| 단일 UPDATE | 포트가 세 열을 `ConversionCiphertexts` 로 **함께** 받는다. 열별 갱신 메서드가 **없다** | **구조 강제 + 문장 수 계측(실 DB, =2)** — 검토함, 지적 없음 |
| NULL 보존 | 서비스가 `EncryptedContent?` 를 그대로 넘기고 repository가 널을 널로 세팅. `sealedOrNull` 이 빈 배열로 바꾸지 않는다 | **구조 강제 + 실 DB 케이스** — 검토함, 지적 없음 |
| 낙관적 조건 | `WHERE key_version = :expected` | **실 DB 케이스 있음.** 단 scheme 축은 A-5 |
| **실패 시 전체 중단** | 코드 순서(세 열을 **전부 연 뒤** 암호화) — **타입으로 강제되지 않는다** | **CR-1** — 실 DB 케이스 없음, 원장은 있다고 적음 |

넷째가 구조가 아닌 것 자체는 결함이 아니다(순서를 타입으로 강제할 방법이 마땅치 않다).
문제는 **그래서 실행으로 재야 하는데 계획이 지정한 층에서 재지 않았고 원장이 반대로 적혔다**는 점이다.

### 6.7 R-5 — `MaskedItemCodec` (리더 지목 ⑤)

**검토함 — 지적 없음.** 세 축을 각각 확인했다.

- **저장 키 ↔ 계약 enum 분리**: `CATEGORY_KEYS = {RRN→"rrn", CARD→"card"}` 가 저장 형식이고,
  응답은 `MaskCategory.label`(계약 `MaskedItemResponse.category.enum` = `["주민등록번호","카드번호"]`)이다.
  `MaskedItemCodecTest` 가 ⑴ 저장 형태를 **바이트로 못박고** ⑵ 「저장 키와 화면 문구가 **다르다**」를
  명시적으로 단언하며 ⑶ 「모든 범주에 키가 있다」로 범주 증설을 강제한다. 키를 `MaskCategory.name` 에서
  유도하지 않은 사유(enum 이름은 리팩터링 대상, 저장 형식은 아니다)가 KDoc에 있다.
- **`Secret.reveal()` 단일 지점**: `encode` 안 한 곳뿐이다(실측: 이 클래스에서 `reveal()` 1건).
  되읽는 쪽도 `Secret` 으로 다시 감싸므로 평문 `String` 이 클래스 밖으로 나가지 않는다.
- **로그 부재**: 이 클래스는 직접 로깅하지 않고, 실패는 `DocumentStorageLog.malformedStoredValue` 로
  **고정 토큰**(`not-an-array`·`missing-field`·`unknown-category`·예외 **클래스 이름**)만 남긴다.
  파싱 예외의 메시지도 원인 사슬도 잇지 않는다. 「거절 문구가 값을 담지 않는다」 케이스가 그것을 잰다.
- 덤: 형식 오류를 **조용히 빈 목록으로 접지 않고 5xx** 로 올린다 — 검수 화면의 「가린 항목 없음」이
  실패처럼 보이지 않는다는 판단이 원본과 같고 테스트가 있다.

### 6.8 M-2 (수정 필요 · 마감 **즉시**) — C1의 파서 방어 층과 음성 대조 (리더 지목 ⑦)

**층 배치는 계획 §5대로 걸려 있다** — L2 디스패치(`DocumentExtractors`)가 zip 예산과 OLE2 3분기를 지고,
L3 파서가 DTD·POI 설정을 지고, L1이 동시 제한과 추출 길이 상한(누적 중)을 진다.
새 zip 형식을 `SourceFormat.isZipContainer` 에 더하면 방어가 따라오는 구조도 계획대로다.

**「D-d와 같은 형태가 더 있는가」에 대한 답: 있다. 한 건이고, 코드가 아니라 계획 문서 쪽이다.**

- §9.2-bis가 개정한 **D-6** 문면: *"음성 대조: **세 속성 중 하나라도 빼면** billion laughs
  (UTF-8·UTF-16) fixture 가 통과한다."*
- 실측: `SUPPORT_DTD = false` 하나가 파서 수준에서 DOCTYPE을 즉시 끊는다. 따라서
  `IS_SUPPORTING_EXTERNAL_ENTITIES` 나 `ACCESS_EXTERNAL_DTD` 를 빼도 **fixture는 여전히 거부된다.**
  **앞쪽 방어가 뒤쪽 둘을 가리는 — D-d가 이미 한 번 잡은 바로 그 구조다.**
- **코드와 테스트는 정직하다.** `IngestDefensesTest.StAX 속성이 명시돼 있다` 는 세 속성을 **구조로만**
  단언하고 행동을 주장하지 않는다. 거짓은 계획 문면 하나다.
- 처방: D-6 개정문의 음성 대조를 「`SUPPORT_DTD` 를 빼면 fixture 가 통과한다 + 나머지 둘은 구조 단언
  (앞쪽 통제에 가려 행동 음성 대조가 성립하지 않는다)」로 D-d와 같은 형식으로 고친다.

같은 형태를 의심해 함께 본 것 둘 — **둘 다 성립한다, 지적 없음.**
- **D-4(추출 길이 상한)**: C2의 `MAX_CONVERTIBLE_CHARS` 검사가 앞을 가리는 것처럼 보이지만,
  D-4가 겨누는 것은 **거절이 아니라 힙 소모**이고 단위 층 테스트(`ExtractedTextBuilderTest`)가
  누적 중 발화를 직접 잰다. 가림이 아니다.
- **D-14(동시 추출 제한)**: 계획이 「힙으로 재기 어렵다 → 구조 단언 + 동시 진입 최대치」로 미리
  낮춰 잡았고, `IngestDefensesTest` 가 12스레드로 실제 최대치를 재고 **조립이 제한을 두른 구현을 내는지**
  까지 단언한다. 선언과 도달이 같다.

### 6.9 그 밖의 도달 범위 점검 — 항목별

| 점검 항목 | 판정 |
|---|---|
| 「전역·모든·항상」 선언이 닿지 않는 경로 | **지적 있음** — M-1(계약 대조) · M-3(호출자 제한) · M-6(L1) · A-7(큐 멱등) |
| 그 게이트가 **지금 어디서 도는가** (도달 0을 특히 의심) | **지적 있음** — 원장 §6이 *"미푸시 40커밋이라 이 세션 변경분은 **CI 에서 한 번도 안 돌았다**"* 고 적는다. Phase 4 표 `:1521`·`:1522` 의 「실행 경로 = `ci:kotlin`」은 **배선 선언이지 관측이 아니다.** 이 회차의 초록은 전부 로컬 실행이다(구현자·이 리뷰 모두). 별건 「품질 차단축 3개 CI 도달 0」(인수인계 §3 순위 6)과 같은 축이므로 **함께 판정**할 것을 권고 |
| 측정이 **대리 경로**에서 이뤄지지 않았는가 | **지적 있음** — CR-1(실 DB 대신 대역 층) · M-5(C2 이전 형상의 스모크를 현재 형상의 증거로 읽을 여지) |
| 검사의 기준이 **검사 대상 자신에게서** 나오는가 | **지적 있음** — M-1(계약을 읽지 않고 계약 값을 손으로 복사한 리터럴과 대조). 그 밖에는 없다: `MaskedItemCodecTest` 의 「고정 문자열을 디코딩한다」가 인코더·디코더 동시 변경을 잡고, 게이트 핀은 Gradle 리포트라는 **독립 관측**으로 자기 파서를 교차 검증한다 |
| 판정이 **대리 지표**로 이뤄지는가 | **검토함 — 지적 없음.** 이 회차는 종료 코드 0을 「검토했다」로 바꿔 읽는 자리를 찾지 못했다. 산출물 §F가 미실행을 「미실행」으로 적었고(N-23~N-28·compose), 원장 §6이 *"돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다"* 를 유지한다 |
| 규칙·패턴의 범위가 **근거보다 넓은가**(은폐형) | **검토함 — 지적 없음.** 이번 범위의 새 규칙·패턴 중 면제·억제·전역 무시를 더한 것이 없다. `test_raw_control_chars.py` 는 면제 목록을 **명시적으로 거부**하고, `e572476` 은 금지를 더했지 예외를 더하지 않았다. L1의 범위(모든 조치 레인)는 결함이 구조적이므로 근거를 넘지 않는다 |
| **음성 대조가 붙어 있는가** | **대부분 있다.** 봉투 누락 INSERT · `V4` 도메인 밖 세대 · 큐 등록 실패 롤백 · 남의 작업 공간 · 틀린 KCV → 기동 실패 · 키 없음 → 기동 거부 · 쓰기 키 없음 → `ConfigurationException` · 낙관적 조건 0행 · zip 예산 초과 · DTD 폭탄 · OLE2 3분기 문구 상이. **없는 것**: 부분 복호화 실패(CR-1) · 계약 값 변이 N-23~N-28(C3 이후, 산출물이 「미실행」으로 적음 — 정직) |
| 판정하는 코드가 **자기 자신을 검사 대상에 넣었는가** | **검토함 — 지적 없음.** `StatementCountingPremiseTest`·`DocumentStorageContextTest` 등 새 테스트 7건이 전부 `ktlintCheck`·`detekt`·`build` 대상이고 게이트 핀 목록에 등재됐다(85). Python 하네스는 `mypy . .claude` 범위 안이다 |

---

## 7. Phase 종료 조건 대비 현황

> 판정은 리더 몫이다. 아래는 근거만이다.

| Phase 4 종료 조건(`00_progress.md:1518-1523`) | 이 리뷰가 본 바 |
|---|---|
| JSON/multipart 업로드와 제한 처리 | **아니오** — HTTP 표면이 없다(C3). C2가 L1 크기 판정만 당겨 왔다(D-g, 사유 타당) |
| DOCX/PDF/HWPX 추출 | **아니오 유지.** 층 배치는 계획 §5대로 섰다. 「같은 형태가 더 있는가」의 답은 **M-2 한 건**(계획 문면). X1은 여전히 도달 0이고 제목 정의역 판정은 C3 몫 — 산출물 §E가 스스로 정직하게 적었다 |
| 암호화 저장·복호화 조회 | **아니오 유지.** 저장 경로는 섰고 X9/F-6은 실증됐다(R-3). **X5/F-5의 원장 종결 문언이 실측과 다르다(CR-1)** · 복호화 조회는 C6 · X2는 C3 |
| 문서 목록·삭제, 변환 조회·검수 저장 | **아니오** — 목록 유스케이스·질의만 섰고 HTTP·삭제·검수는 C4~C7 |

**미해결 항목 (이 리뷰가 더하는 것)**

| # | 항목 | 심각도 | 마감 |
|---|---|---|---|
| CR-1 | 원장 X5/F-5 종결 문언 ↔ 실측 불일치 + 심판 대상 레인이 기록 | **차단 ②** | Phase 4 종료 판정 전 / 실 DB 케이스는 Phase 5 착수 전 |
| M-1 | D-13 계약 대조 미구축 + 있다고 적힌 KDoc·DisplayName | 수정 필요 | C3 |
| M-2 | 계획 §9.2-bis D-6 음성 대조 문면이 거짓 | 수정 필요 | 즉시 |
| M-3 | `loadSourceText`/`loadEnvelope` 호출자 제한 강제자 0 | 수정 필요 | C6 |
| M-4 | K-2 탐지기 분모에 하한 없음 + KDoc 한계 2항 누락 | 수정 필요 | C3 |
| M-5 | compose 스모크 §4에 「`269fe28` 형상에 묶인다」 누락 | 수정 필요 | C3 종료 전 |
| M-6 | L1 규칙(`e572476`) 도달 0 + 원장을 덮지 않는 금지 범위 | 수정 필요 | 즉시 |

**판정 필요**

| # | 항목 |
|---|---|
| **J-1** | 표 18 TRACE 카나리의 마감 해석(§9.2 D-f). 원장 마감은 「Phase 4 **문서 본문 진입 전**」인데 C2에서 문서 본문은 이미 `DocumentService` → `ContentCipher` → PostgreSQL을 지난다(HTTP만 없다). 「진입」을 HTTP로 읽을지 데이터 경로로 읽을지. 인수인계 §3 순위 2로 이미 올라가 있고 **아직 답이 없다** — 답이 오지 않았다는 이유로 닫지 않는다 |
| **J-2** | cross §10-③의 원 질문 — 「리포트 기반 실행 증거가 언제 참이고 언제 거짓인가」 — 이 스위트 교체로 **재현 대상이 사라져 답 없이 소멸**했다. 소멸로 닫을지, 새 축(예: 요구 모드가 CI에서 실제로 켜지는지의 관측)으로 다시 물을지 |

---

## 8. 미실행 · 확인 불가

**돌린 것처럼 적지 않는다.**

| 항목 | 상태 | 사유 |
|---|---|---|
| `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **미실행(이 리뷰)** | 시간·Docker 비용. 구현자 보고(exit 0, warning 0)는 **인용**이며 이 리뷰의 독립 근거가 아니다. 다만 리포트 XML 115건과 새 7클래스 `failures="0"` 은 직접 확인했다 |
| `./gradlew moduleBoundaryCheck` · `parityHarness` | **미실행(이 리뷰)** | 위와 같음. 모듈 경계는 import 전수 육안 대조로 대신했다(§3.4) |
| `scan_privacy_invariants.py` | **미실행(이 리뷰)** | `privacy-gate` 소관. BLOCK 0 보고는 인용 |
| 계약 음성 대조 N-23~N-28 | **미실행 — 정당** | C3 이후 몫(계획 §8.5). 산출물이 그렇게 적었다 |
| compose 스모크 재실행(C2 형상) | **미실행** | M-5의 대상이다. 이 리뷰가 돌리지 않았다 |
| GitHub Actions 실제 관측 | **확인 불가** | 미푸시 40커밋. 원장 §6이 같은 사실을 적는다 — 이번 회차의 모든 초록은 **로컬 실행**이다 |
| C1 fixture의 바이너리 내용(`sample_rich.docx` 등) | **미검토** | 바이너리라 diff로 읽을 수 없다. `repo-fixtures-oracle.json`·`spike-oracle.json` 참고값 대조는 구현자 보고(갈림 0건) 인용 |
| `frontend/src/api/` | **미검토 — 범위 밖** | C2가 HTTP 표면을 만들지 않아 프론트 계약면 변화 0 |
| `parity-verifier` 리포트 · `privacy-gate` 감사 | **미수령** | 이 회차에는 해당 산출물이 없다. parity 위험·보안 축의 일부 판정은 그만큼 이 리뷰의 코드 독해에 의존한다 |
| 다른 리뷰어의 산출물 | **읽지 않았다 — 의도적** | 1회차 독립 리뷰다. 교차 대조는 3단계에서 한다 |

---

## 9. 각 레인에 보내는 것

| 수신 | 항목 |
|---|---|
| `kotlin-implementer` | M-1(문면 또는 P-26 구축) · M-4(분모 하한·KDoc 2항) · A-2 · A-3 · A-5 · A-6 · A-7 · A-8 · A-9 · CR-1의 실 DB 케이스 |
| `contract-keeper` | A-1 — 502 조항·`EnqueueFailed`·**순서 문장** 세 자리를 한 변경 단위로. 큐 INSERT 실패의 실제 응답이 500이라는 실측을 함께 |
| `privacy-gate` | **M-3**(소유권 은닉 우회 가능 자리의 강제자 0) — 이 축의 판정 우선권은 그쪽이다. A-4도 함께 |
| `parity-verifier` | A-3(hwpx 동명 항목) · A-2(문자 수 단위 갈림)를 갈림 원장에 넣을지 |
| 하네스 레인 | **M-6**(L1 규칙 도달) · M-2(계획 §9.2-bis D-6 문면) |
| 리더 | **CR-1** · M-5 · J-1 · J-2 · 「실행 경로 = `ci:kotlin`」이 배선 선언인지 관측인지의 표기 규약(§6.9) |

---

## 10. 이 회차의 성격에 대한 한 줄

C1·C2는 이 저장소가 지금까지 만든 것 중 **자기 한계를 가장 많이 스스로 적은** 배치다
(§9.2 D-d, §9.2-bis 8건, 산출물 §E 6건, `PoiZipDefenses`·`SecureXml`·`StatementCountingPremiseTest` KDoc의
「막지 못하는 것」). 이 리뷰가 찾은 것 대부분은 **그 자기 기록이 닿지 않은 한 칸 옆**에 있다 —
계획 문면(M-2), 원장 문면(CR-1·A-10), 에이전트 정의(M-6), 그리고 「있다고 적었는데 없는 장치」(M-1).
자기 기록이 촘촘할수록 남은 빈자리는 **문서와 코드 사이의 이음매**로 옮겨간다는 것이 이번 회차의 형태다.
