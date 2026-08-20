# Phase 4 `documents` 작업 단위 — 리서치·계획

**작성:** kotlin-implementer / **일자:** 2026-08-19 / **성격:** 착수 전 계획. **코드 0줄.**
**근거 규칙:** 프로젝트 `CLAUDE.md` 「구현 전 리서치·계획 (필수)」 — ①라이브러리·프레임워크 리서치
②기구현 확인 ③계획 작성을 **순서대로** 끝내고 그 결과를 남긴다. 이 문서가 ③이다.

**입력**
`contracts/easy-doc-v1.yaml`(정본) · `docs/migration/_workspace/04_contract-keeper_documents-test-spec.md`(77 케이스) ·
`00_progress.md` 「Phase 4」 표 + 「Phase 4 crypto 단위」 §2·§3 + 「아직 돌리지 않은 검증 게이트」 표 ·
`00_kotlin-implementer_doc-spike.md`(Phase 0) · `04_kotlin-implementer_crypto-fixes.md` ·
`03_kotlin-implementer_phase4-preconditions.md` · `00_requirements-inventory.md` §5·§6 ·
`.claude/skills/migration-safety-gate` I-4·I-6·I-7·I-10·I-13 · `docs/plans/2026-08-11-kotlin-react-migration.md` §3.2·§4.2·§4.5

**조사 방법 — 하위 조사 에이전트 2건을 병렬로 띄웠다(읽기 전용).** 근거의 출처가 원장에 남아야 하므로 적는다.

| 조사 | 범위 | 도구 | 산출물이 어디 있나 |
|---|---|---|---|
| **조사 A — 라이브러리 공식 문서** (본문에서 「조사 A」로 부른다) | POI 5.4.1 · PDFBox 3.0.5 · Spring Boot 4 / Spring Framework 7 multipart · JDK 21 XXE 차단. **현재 버전의 API 와 권장 방식**을 확인하고 옛 메이저(POI 4.x·PDFBox 2.x·Boot 3.x)와 갈리는 자리를 표시하게 했다 | context7 MCP + 공식 레퍼런스. **학습 기억 사용 금지**를 지시했고, 확인 못 한 항목은 「확인 실패」로 표시하게 했다 | **저장소에 파일이 없다.** 보고 파일 작성을 금지하고 최종 메시지로 받았다(에이전트 산출물 규약). 결론은 **§1.2 표와 §1.5 에 전문 반영**했고, 그 두 절이 이 저장소 안의 정본이다 |
| **조사 B — Python 원본 인벤토리** | `app/ingest/extractors.py` · `app/services/documents.py` · `app/api/documents.py` · `app/easyread/export.py`·`hwpx.py` · `app/repositories/**` · `tests/{ingest,api,services,repositories}` | 읽기 전용(`app/**` 무수정) | 같음. 반영 위치는 §4.2 · §5 · §9.1 · §9 ⑪⑫ |

> **왜 파일로 남기지 않았나.** 조사 산출물은 **이 계획의 재료**이지 그 자체로 원장에 남길 판정이 아니다.
> 두 벌이 되면 갈린다 — 그래서 결론을 이 문서에 흡수하고, **조사 A 의 확인 실패 항목까지 그대로 옮겼다**(§1.2).
> 재현이 필요하면 같은 질문 목록(§1.2 의 Q-1~Q-12)으로 다시 돌리면 된다.

**무접촉 확인** — 이 배치는 이 파일 하나만 만든다. `backend-kotlin/**`·`contracts/**`·`.claude/**`·
`app/**`·`tests/**`·`frontend/**`·`reviews/**`·`00_progress.md` 를 한 줄도 건드리지 않았고 Gradle 을 돌리지 않았다.

> **값을 전사하지 않는다.** 계약이 소유한 값(상한 숫자·`detail` 문구·헤더 값·enum 원소·미디어 타입·
> 금지 문자 집합)은 **키 경로로 부른다**. 설계 판단에 숫자가 필요한 자리에서도 「계약 `x-input-limits.max_upload_bytes`」
> 처럼 자리를 가리키고 값을 옮기지 않는다. 근거는 계약 명세 §0 과 같다 — 손으로 옮긴 요약이 원본과 갈린 실측이 있다.

---

## 0. 이 단위의 경계 — 무엇을 하고 무엇을 하지 않는가

| 안 | 밖 |
|---|---|
| 계약 **#4 `POST /documents`** · **#5 `GET /documents`** · **#6 `DELETE /documents/{id}`** · **#7 `GET /conversions/{id}`** · **#8 `PUT /conversions/{id}`** | **#9 `GET .../export`** — 원장이 `export 단위`로 갈라 놓았다(§9 질문 ①) |
| 문서 **추출**(docx·pdf·hwpx) 전체 — #4 파일 모드의 전제라 뺄 수 없다 | 문서 **생성**(DOCX·HWPX 렌더링) — `export` 단위 |
| 저장 암호화 **배선**(첫 INSERT·복호화 조회·행 단위 재암호화) | 키 **회전 배치의 호출자**(운영 CLI·스케줄) — §9 질문 ⑦ |
| 큐 **등록** 경로(계약 #4 의 202/502/503 갈래가 실물로 서려면 필요) | 큐 **소비**(lease·worker 폴링) — Phase 5 |
| Python 원본을 참고 자료로 읽기 | `app/**` 수정 — 폐기 대상이자 fixture 참고값의 출처다 |

**Python 은 정답이 아니다.** `CLAUDE.md` 와 master-plan 6.2 대로 판정 기준은 요구사항·정책이고,
동작이 갈리면 어느 쪽이 요구에 맞는지 판단해 기록한다. 예외는 정책 불변식(마스킹 선행·no-store·
소유권 은닉·I-10 파서 방어)이며, 그것들은 값이 아니라 **성질**이라 양쪽에 똑같이 요구된다.

---

## 1. 라이브러리·프레임워크 리서치

### 1.1 이미 실행으로 확인된 것 — 다시 조사하지 않는다 (Phase 0 spike)

`00_kotlin-implementer_doc-spike.md` 가 **실제로 돌려** 확정한 것이다. 아래는 그 결론 중
이 단위의 구현을 직접 구속하는 것만 옮긴다(경위·측정은 그 문서가 정본이다).

| # | 확정 사항 | 구속하는 자리 |
|---|---|---|
| S-1 | **DOCX 입력에 POI usermodel 을 쓰지 않는다.** `XWPFDocument.getParagraphs()/getTables()` 는 표를 뒤로 밀고 텍스트박스·SDT·중첩 표를 못 본다. `getDocument().getBody().getDomNode()` 부터 스택 순회 | `DocxExtractor` |
| S-2 | **머리글·바닥글도 usermodel 금지.** `getHeaderList()` 는 파트 목록이라 「구역별 (머리글, 바닥글)」 순서가 어긋난다. `w:sectPr` 을 문서 순서로 훑고 `headerReference/footerReference[@w:type="default"]` 의 `r:id` 를 `getRelationById()` 로 푼다. `even`/`first` 는 걷지 않는다 | 같음 |
| S-3 | **`w:t` 는 lxml `.text` 의미로 읽는다** — 첫 자식 **요소** 앞까지의 텍스트. DOM `getTextContent()` 는 자손을 전부 모아 의미가 다르다 | 같음 |
| S-4 | **StAX 기본값은 안전하지 않다.** `SUPPORT_DTD`·`IS_SUPPORTING_EXTERNAL_ENTITIES`·`ACCESS_EXTERNAL_DTD` 세 속성을 **명시**한다 | `SecureXml` |
| S-5 | **DTD 거부를 예외 메시지 문자열로 판정하지 않는다.** JDK 예외 메시지가 로케일에 따라 번역된다(실측: 한국어). spike 권고 (b) — `SUPPORT_DTD=true` + 외부 엔터티 차단 + **`XMLStreamConstants.DTD` 이벤트를 직접 받아 우리 예외를 던진다.** Python `expat.StartDoctypeDeclHandler` 와 1:1 구조 | 같음 |
| S-6 | **zip 예산은 중앙 디렉터리를 읽는 구현으로, 실제 읽은 바이트로 센다.** `java.util.zip.ZipInputStream` 은 로컬 헤더만 본다. commons-compress `ZipFile` + `SeekableInMemoryByteChannel` 이 in-memory 대응물이고 **POI 의 전이 의존성**이라 새 의존성이 아니다 | `ZipBudget` |
| S-7 | **PDF 는 `PDFTextStripper.sortByPosition = false`(기본값) 고정.** `true` 로 켜면 다단 결과가 갈린다(실측). 설정을 한 곳에 고정하고 기본값에 기대지 않는다 | `PdfExtractor` |
| S-8 | **zip bomb 1GiB / 힙 256MB 에서 힙 증가량 0MB** — 경계 읽기가 실제로 동작한다(실측) | 검증 설계 |
| S-9 | **PDFBox 3.0.5 는 `Loader.loadPDF(ByteArray)`** — 2.x 의 `PDDocument.load` 와 API 가 다르다 | `PdfExtractor` |
| S-10 | POI 5.4.1 의 `log4j-api`·PDFBox 의 `commons-logging` 은 **바인딩이 없어 경고가 난다.** 로깅 브리지 연결이 필요하다 | version catalog |
| S-11 | **버전 조합**: Java 21 / POI 5.4.1 / PDFBox 3.0.5 / commons-compress 1.27.1. 단 **Boot BOM 없이** 해석한 결과라 BOM 적용 후 다시 잠가야 한다 | version catalog |
| S-12 | spike 는 Kotlin **2.2.0** 위에서 통과했는데 현재 카탈로그는 **2.3.21**(Boot 4.1.0). `libs.versions.toml` 주석이 *"Phase 4 착수 전에 그 spike 의 DOCX 동등성 7항목을 이 조합으로 다시 확인해야 한다"* 고 **이미 예고**했다 | C1 착수 첫 작업 |

**spike 가 축약한 것(포팅 시 채워야 한다, spike §5-5)**: OLE2 3분기 · `_broken()` 로깅 규약 ·
PDF 암호 분기의 실파일 검증 · 10MB·`MAX_EXTRACTED_CHARS` 경계.

### 1.2 공식 문서로 확인한 것 (조사 A 결과)

spike 가 다루지 않았거나 Boot 4.1/Kotlin 2.3 조합에서 재확인이 필요한 자리다.
**학습 기억으로 쓰지 않았다** — context7/공식 레퍼런스로 확인하고, 확인되지 않은 것은
**「확인 실패」로 그대로 남긴다**(추측으로 메우면 그 자리가 조용히 결함이 된다).

| # | 질문 | 확인 결과 | 판정 |
|---|---|---|---|
| **Q-1** | `ZipSecureFile` 정적 설정의 기본값·전역성·예외 | `setMinInflateRatio` **0.01** · `setMaxEntrySize` **0xFFFFFFFF(4GiB−1)** · `setMaxTextSize` **10MB** · `setMaxFileCount` **1000** · `setGraceEntrySize` **100KB**. **전부 JVM 전역 `static`**. 초과 시 `IOException`(메시지에 `Zip bomb detected!`) | **확인됨.** 기본값이 계약 예산보다 **훨씬 헐겁다**(특히 `MAX_ENTRY_SIZE` 4GiB) → 우리 예산에 맞춰 **낮춘다**. 전역이므로 기동 1회 설정 + 테스트 격리 필요 |
| Q-1b | `setMaxFileCount` 가 `InputStream` 경로에서도 강제되는가 | 문서에서 확정하지 못했다 | **확인 실패** → 음성 대조로 잰다(§5 D-8) |
| **Q-2** | POI 의 OOXML DOCTYPE 차단이 기본인가 | **기본으로 차단하며 층이 둘이다** — OPC 인프라(`XMLHelper` 기반 파서 팩토리)와 본문 파트(XMLBeans 로딩). 예외가 층마다 갈린다: `InvalidFormatException` ↔ `POIXMLException`(원인 `XmlException`) | **확인됨.** 예외 **타입으로 단언하지 않는다** — 「거부됐다」와 「본문이 유출되지 않았다」로 단언한다 |
| **Q-3** | PDFBox 3 의 로더와 메모리 상한 | `Loader.loadPDF(byte[])` · `(File)` · `(RandomAccessRead)` (+password·`StreamCacheCreateFunction` 변형). **`InputStream` 오버로드가 없다.** 2.x `MemoryUsageSetting` 은 **제거**됐고 대체물은 ⑴ 읽기: `RandomAccessReadBuffer`(전량 메모리) / `RandomAccessReadBufferedFile`(지연) ⑵ 쓰기·스트림 캐시: `StreamCacheCreateFunction`(`IOUtils.createMemoryOnlyStreamCache()` 등) | **확인됨 — 그리고 나쁜 소식이다.** 대체물은 **쓰기/스트림 캐시**를 관장할 뿐 **읽기 측 상한이 아니다.** 즉 **PDF 메모리 방어에 라이브러리 API 가 없다** → §1.5 설계 지점 3 |
| **Q-4** | 암호 PDF 예외 FQCN | `org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException`(`IOException` 계열). `isEncrypted()`·`setAllSecurityToBeRemoved` 는 현행 | **확인됨** |
| **Q-5** | `PDFTextStripper` 기본값 | `sortByPosition` **false** · `wordSeparator` `" "` · `paragraphStart` `""` · **`lineSeparator` = `System.lineSeparator()`** | **확인됨 — 재현성 결함이다.** 줄 구분자가 **플랫폼 의존**이라 CI(Linux)와 개발기(다른 OS)가 다른 텍스트를 낸다 → §1.5 설계 지점 3 |
| **Q-6** | Boot 4 multipart 속성·기본값 | `enabled` **true** · `max-file-size` **1MB** · `max-request-size` **10MB** · `file-size-threshold` **0B** · `location` `""` · `resolve-lazily` **false** · Boot 4 신설 **`strict-servlet-compliance` false**. `MultipartProperties` 는 Boot 4 에서 패키지가 옮겨졌고 `ignoreUnknownFields=false` 라 **오타가 기동 실패**로 드러난다 | **확인됨 — 그리고 DC-13 이 설정 없이 반드시 깨진다.** `max-request-size` 기본이 계약 업로드 상한과 사실상 같아, **정확히 상한인 파일**이 multipart 경계·헤더 오버헤드 때문에 요청 전체로는 상한을 넘는다 → §1.5 설계 지점 2 |
| **Q-7** | 상한 초과 예외와 그 매핑 | `MaxUploadSizeExceededException extends MultipartException`(Tomcat `IllegalStateException`/`FileCountLimitExceededException` 을 감싼다). Spring 이 컨테이너 초과를 알아내는 방식이 **Tomcat 예외 메시지 문자열 매칭**이다. `ResponseEntityExceptionHandler` 가 이 예외를 처리 목록에 갖고 있고 상태는 **413**. `checkMultipart` 가 `doDispatch` 의 try 안이라 `@ControllerAdvice` 에 **닿는다**. `resolve-lazily=true` 면 발생 지점이 **핸들러 안(첫 파라미터 접근)** 으로 밀린다 | **확인됨 — 그리고 우리 코드에 직접 걸린다.** `GlobalExceptionHandler` 가 이미 `ResponseEntityExceptionHandler` 를 **상속**하므로 413 이 나가되 본문은 `createResponseEntity` 오버라이드가 만드는 **영어 reason phrase** 다(계약 문구가 아니다). 게다가 판정이 **메시지 문자열 매칭**이라 취약하다 → §1.5 설계 지점 2 |
| **Q-8** | Tomcat `maxSwallowSize` | 기본 **2MB**. 초과분을 삼키지 않으면 **연결을 리셋**한다. 속성은 `server.tomcat.max-swallow-size`(`-1` = 무제한) | **확인됨.** 리셋이면 클라이언트가 413 **본문을 못 본다** — DC-12 의 `detail` 단언이 실제 소켓에서만 깨진다(MockMvc 는 통과한다) |
| **Q-9** | `MultipartFile.getOriginalFilename()` | **널 가능**. 빈 문자열일 수 있고, 브라우저에 따라 **경로가 섞여 들어온다**. **정제는 프레임워크가 하지 않는다 — 앱 몫이다** | **확인됨.** 확장자 판별이 널·빈값·경로·`..` 네 갈래를 전부 다뤄야 한다 |
| **Q-10** | `@RequestPart` vs `@RequestParam`, `@Valid` | `@RequestPart` 는 파트를 콘텐츠 타입 인지 변환/검증에 태울 때. `@Valid` 가 붙으면 위반이 `MethodArgumentNotValidException` 으로 나온다 | **확인됨.** 그 예외는 우리가 이미 422 로 오버라이드하고 있다(`handleMethodArgumentNotValid`) — 기본 400 이 아니다 |
| **Q-11** | StAX XXE 차단의 현행 권장 | OWASP 의 **1차 통제는 `XMLInputFactory.SUPPORT_DTD = false`** 이고, 여기에 `IS_SUPPORTING_EXTERNAL_ENTITIES=false` 와 `ACCESS_EXTERNAL_DTD=""` 를 더한다. `SUPPORT_DTD=true` 로 두고 `XMLStreamConstants.DTD` 이벤트를 받아 거부하는 것은 **표준 이벤트라 기술적으로 가능하지만 권장 통제로 문서화돼 있지 않다**(예방이 아니라 탐지다) | **확인됨 — spike S-5 권고와 갈린다** → §1.5 설계 지점 1 |
| **Q-12** | Jackson 3 의 짝 없는 서로게이트 이스케이프 처리 | 문서에서 확정하지 못했다 | **확인 실패** → **C3 착수 전에 케이스 1건으로 실측**한다. 결과에 따라 X1 의 첫 도달이 「JSON 붙여넣기」인지 「PDF」인지 갈린다(§6.1) |

**부수 확인(버전·좌표)**

- Spring Boot 4 BOM 이 `log4j` **2.25.2** 와 `commons-codec` 를 관리한다. 그러나
  **`commons-compress`·`commons-io`·`xmlbeans` 는 BOM 관리 대상이 아니다** → 우리가 고정한다.
- **Spring Framework 7 이 `spring-jcl` 을 제거했다.** 따라서 `commons-logging` 을 **exclude 하면 안 된다** —
  Boot 3 시절의 관용적 exclude 를 그대로 옮기면 `NoClassDefFoundError` 가 난다.
- POI 5.4.1 · PDFBox 3.0.5 좌표는 spike 시점과 같다.
- `XWPFWordExtractor` 는 현행이지만 우리는 쓰지 않는다(S-1). DOCX **생성** 쪽 확인
  (`createStyles()`·`XWPFStyles.addStyle`·`setStyle` 이 **스타일 이름이 아니라 styleId** 를 받는다,
  템플릿 동봉에 공식 권장이 없다)은 **`export` 단위**의 재료라 여기서는 기록만 한다.

### 1.3 version catalog 에 더할 좌표와 그 근거

`gradle/libs.versions.toml` **한 곳**에만 적는다(스킬 §1.60-74). 모듈 스크립트에는 `libs.xxx` 만 쓴다.

| 좌표 | 버전 결정 | 사유 |
|---|---|---|
| `org.apache.poi:poi-ooxml` | **직접 고정**(spike 조합) — Boot BOM 관리 대상 아님 | DOCX 입력. spike 가 통과시킨 조합에 정렬한다(§3.1 「임의 최신을 집지 않는다」) |
| `org.apache.pdfbox:pdfbox` | **직접 고정** | PDF 입력 |
| `org.apache.commons:commons-compress` | **직접 고정**(POI 전이지만 우리가 직접 쓰고, **Boot BOM 관리 대상이 아니다** — 조사 A 확인) | zip 예산이 이 API 에 의존한다. 전이에 기대면 POI 업그레이드가 조용히 API 를 바꾼다 |
| `commons-io` · `xmlbeans` | **직접 고정** — 조사 A 가 **BOM 관리 대상 아님**을 확인했다 | 고정하지 않으면 POI 전이 해석에 맡겨져 스택마다 갈린다(§3.1 「임의 버전으로 섞지 않는다」) |
| `org.apache.logging.log4j:log4j-to-slf4j` | **BOM**(Boot 4 가 log4j **2.25.2** 를 관리한다 — 조사 A 확인) | S-10. POI 의 `log4j-api` 를 slf4j 로 잇는다. `runtimeOnly` |
| commons-logging | **exclude 하지 않는다** | **Spring Framework 7 이 `spring-jcl` 을 제거했다**(조사 A 확인). Boot 3 시절의 관용적 `exclude("commons-logging")` 를 그대로 옮기면 **`NoClassDefFoundError`** 가 난다. slf4j 로 잇고 싶으면 `jcl-over-slf4j` 를 **더하는** 방향으로만 한다 |
| `org.springframework.boot:spring-boot-starter-validation` | **BOM** | 계약이 `limit`/`offset` 을 **스키마 층**으로 못박았다(지침 3). **이 의존성이 들어오는 순간 F3 음성 대조의 1차 방벽이 사라진다** — 명세 §3-1·§6 |

**락파일 갱신은 같은 커밋에서 한다.** dependency locking 을 켜 둔 이유가 「문서 파서처럼 바이트가
곧 결과인 지점의 드리프트 제거」이므로, 좌표를 더하고 락을 나중에 갱신하면 그 사이가 무방비다.

### 1.4 직접 구현하는 것과 그 사유 (라이브러리를 쓰지 않는 자리)

`CLAUDE.md` 리서치 규칙 1 은 직접 구현을 ①라이브러리가 제공하지 않는 성질 ②의존성 정책 위반
두 경우로 한정하고 **사유를 계획에 적을 것**을 요구한다.

| 직접 구현 | 사유 |
|---|---|
| **HWPX 파서** | JVM 생태계에 검증된 HWPX 파서가 없다(spike 가 찾지 못했다). zip+XML 이므로 commons-compress + StAX 로 조립한다 — 이것은 「바퀴 재발명」이 아니라 **표준 라이브러리 둘의 조합**이다 |
| **zip 압축 해제 예산** | commons-compress 는 「실제 읽은 바이트로 세는 예산」을 제공하지 않는다. I-10 검증 3 이 `ZipEntry.getSize()` 신뢰를 명시적으로 금지한다 — 이 성질은 우리가 짜야 한다 |
| **DOCX DOM 순회** | POI 를 쓰되 usermodel 이 아니라 하부 DOM API 를 쓴다(S-1·S-2). 라이브러리를 버리는 것이 아니라 **다른 층위를 쓴다** |
| **OLE2 3분기 진단** | olefile 대응 라이브러리를 새로 들이지 않고 매직 4바이트 + UTF-16LE 스트림 이름 검색으로 가른다. 계약 `x-input-limits.legacy_doc_policy` 가 전용 문구를 요구하고, 그 판정에 필요한 정보가 그 두 조각뿐이다 |

### 1.5 조사 결과가 **바꾼** 설계 지점 셋 — 무엇이 왜 뒤집혔나

> 이 절이 있는 이유: 아래 셋은 **이 계획의 초판(조사 A 이전)이 다르게 적어 두었던 자리**다.
> 사유를 남기지 않으면 다음 사람이 spike 문서나 초판을 근거로 원래대로 되돌린다.

#### 설계 지점 1 — hwpx XML 의 DTD 차단: **`SUPPORT_DTD=false` 로 간다** (spike 권고를 채택하지 않는다)

| | 내용 |
|---|---|
| **초판(= spike S-5 권고 (b))** | `SUPPORT_DTD=true` + 외부 엔터티 차단 + **`XMLStreamConstants.DTD` 이벤트를 직접 받아 우리 예외를 던진다** |
| **바꾼 뒤** | **`SUPPORT_DTD=false`** 를 1차 통제로. 여기에 `IS_SUPPORTING_EXTERNAL_ENTITIES=false` · `ACCESS_EXTERNAL_DTD=""` 를 더한다 |
| **왜** | 조사 A(Q-11): **OWASP 의 StAX 1차 통제가 `SUPPORT_DTD=false`** 다. 이벤트 수신 방식은 표준 이벤트라 **가능하긴 하나 권장 통제로 문서화돼 있지 않다** — **예방이 아니라 탐지**이고, 「우리가 이벤트를 받아 끊는다」는 구현 순서에 의존한다. `CLAUDE.md` 리서치 규칙 1 이 「공식 문서로 현재 권장 방식을 확인한다」이므로 spike 의 실측 편의보다 공식 권장이 이긴다 |
| **spike 가 (b) 를 고른 유일한 이유는 무엇이었나** | **로케일 문제 하나다.** `SUPPORT_DTD=false` 면 `XMLStreamException` 이 나는데 그 메시지가 JDK 로케일에 따라 번역돼(spike 가 한국어 메시지를 실측했다) **「DTD 거부」와 「손상 파일」을 메시지로 가를 수 없다** |
| **그 목적을 어떻게 대신 달성하나** | **사유를 메시지로 가르지 않는다.** 거부는 도메인 예외 하나(`DocumentExtractionException`)로 통일하고, 진단은 예외 **타입**으로만 로깅한다 — 이것은 §5 D-16 이 이미 요구하는 규약과 **같은 규약**이다(라이브러리 메시지를 신뢰하지 않는다). 새 장치가 아니라 기존 규약의 적용이다 |
| **잃는 것 (명시)** | DTD 폭탄과 손상 파일의 **사용자 문구가 같아진다.** ⑴ 계약은 이 구분을 요구하지 않는다(`x-input-limits` 는 `legacy_doc_policy`·`rejected_pdf`·예산·추출 길이만 든다) ⑵ Python 은 전용 문구를 냈으므로 **문구가 갈린다** — spike §5-1 이 위조 zip 에서 이미 같은 종류의 갈림을 기록했고, 기준은 Python 이 아니라 요구사항이다(master-plan 6.2). **이 갈림을 산출물에 기록한다** |
| **보안 성질은 약해지지 않는다** | I-10 검증 2 가 요구하는 것은 「**파서 수준**에서 막는다(바이트 검색 금지)」이고 `SUPPORT_DTD=false` 가 정확히 그것이다. 파서가 DOCTYPE 을 만나는 즉시 끊으므로 **엔터티 확장이 시작되지 않는다** — billion laughs 도 UTF-16 인코딩 DTD 도 같다 |

#### 설계 지점 2 — 413 을 **누가** 만드는가: 컨테이너 판정에 의존하지 않는다

| | 내용 |
|---|---|
| **초판** | 「L0 컨테이너 상한을 계약 상한 **이상**으로 두고 L1 서비스가 정확 경계를 판정한다」 — 방향은 맞았으나 **근거가 없었고 컨테이너 쪽 실패 양상을 몰랐다** |
| **바꾼 뒤** | 같은 방향을 **네 가지 확정 사실 위에** 다시 세우고, 각각에 장치를 붙인다 |
| **⑴ DC-13 이 설정 없이 반드시 깨진다** | `max-request-size` **기본 10MB** 가 계약 업로드 상한과 사실상 같다. **정확히 상한인 파일**은 multipart 경계·파트 헤더 오버헤드 때문에 **요청 전체로는 상한을 넘는다.** `max-file-size`(기본 1MB)만 올리고 `max-request-size` 를 그대로 두면 DC-13 이 컨테이너에서 잘린다 → **둘 다** 올린다 |
| **⑵ 계약의 413 을 컨테이너 판정에 걸지 않는다** | Spring 이 컨테이너 초과를 알아내는 방식이 **Tomcat 예외 메시지 문자열 매칭**이다(조사 A Q-7). 메시지가 바뀌거나 번역되면 **413 이 조용히 500 이 된다.** 그래서 **서비스가 계약 상한을 정확히 재고**(`UploadTooLargeException` → 413 + 계약 문구), 컨테이너 예외는 **backstop** 으로만 매핑한다 |
| **⑶ 우리 코드에 이미 걸려 있다** | `GlobalExceptionHandler` 가 `ResponseEntityExceptionHandler` 를 **상속**하고 그 기반 클래스가 `MaxUploadSizeExceededException` 을 처리 목록에 갖는다 → **413 이 나가되 본문은 우리 `createResponseEntity` 오버라이드가 만드는 영어 reason phrase** 다. 즉 **오늘 이미 계약 문구가 아닌 413 을 낼 수 있다.** 명시 오버라이드가 필요하고 **실측으로 확인**한다(초판은 「없으면 500」으로 적었다 — **틀렸다**) |
| **⑷ 413 본문이 클라이언트에 닿지 않을 수 있다** | Tomcat `maxSwallowSize` 기본 **2MB**. 초과분을 삼키지 않으면 **연결 리셋**이라 클라이언트가 본문을 못 본다. `server.tomcat.max-swallow-size` 를 함께 설정하고 **실제 소켓(C-R)** 으로 본문을 읽는다. MockMvc 는 이 경로를 재현하지 못하면서 통과한다 |
| **덤** | Boot 4 신설 `strict-servlet-compliance`(기본 false)를 **켠다** — 파싱 대상을 `multipart/form-data` 로 좁힌다. **DC-5(대소문자 무시)에는 영향이 없다**(비교는 여전히 대소문자 무시다). 그리고 `MultipartProperties` 가 `ignoreUnknownFields=false` 라 **속성 오타가 기동 실패**로 드러난다 — 설정이 조용히 무시되지 않는다 |

#### 설계 지점 3 — PDF: **재현성 결함 하나와 「메모리 방어 API 없음」**

| | 내용 |
|---|---|
| **초판** | S-7(`sortByPosition=false` 고정)만 적었고, 메모리 상한은 「Q-3 확인 후 `Loader` 설정으로」라고 **있을 것으로 가정**했다 |
| **⑴ 줄 구분자 (신규 발견)** | `PDFTextStripper.lineSeparator` **기본값이 `System.lineSeparator()`** 다 → **플랫폼 의존**. Linux CI 와 다른 OS 개발기가 **서로 다른 추출 텍스트**를 낸다. 게다가 `stripControlChars` 는 `\r`(`\x0D`)를 **지우지 않는다**(패턴이 `\x0B\x0C` 는 지우고 `\x0D` 는 남긴다 — 실측) → **`\r` 이 그대로 저장·응답까지 간다.** 조치: **`lineSeparator("\n")` 를 명시 고정**하고, 그 고정을 음성 대조로 잰다 |
| **⑵ 메모리 상한 API 가 없다** | 2.x `MemoryUsageSetting` 은 제거됐고, 대체물(`StreamCacheCreateFunction`)은 **쓰기·스트림 캐시**만 관장한다. **읽기 측 상한은 라이브러리가 제공하지 않는다.** 따라서 I-10 의 「과대 추출 → OOM」 축에서 **PDF 방어는 전부 앱 책임**이다 — 업로드 바이트 상한 + 추출 길이 상한 + 동시 추출 제한(§5 D-14)이 실제 방어의 전부다. `Loader.loadPDF(byte[])` 를 쓰되(InputStream 오버로드가 **없다**) 그 사실을 KDoc 에 적는다 |
| **⑶ 스캔 PDF 판별에 공식 대안이 없다** | 「추출해 보고 비었으면 거절」이 유일한 방법이고, 폰트·XObject 휴리스틱은 **문서화돼 있지 않다.** D-10 을 그대로 두되 근거를 「공식 대안 부재」로 적는다 |
| **⑷ 적용하지 **않는** 개선 (§9.2 로 등재)** | 페이지 수 상한 · 추출 시간 상한 · `IOUtils.setByteArrayMaxOverride`. 셋 다 I-10 이 요구하지 **않는** 신규 방어다. 계획 §4.6 의 「포팅과 개선을 섞지 않는다」에 따라 **적용하지 않고 후보로 등재**하며, **잔여 위험을 명시**한다 |

---

## 2. 기구현 확인 — 재사용이 기본, 중복 구현은 결함

`backend-kotlin/` 의 다섯 모듈을 실제로 훑은 결과다. **없는 것은 이 단위가 처음 만든다.**

### 2.1 그대로 재사용 — 새로 만들지 않는다

| 자리 | 심벌 | 이 단위에서 어떻게 쓰나 |
|---|---|---|
| `application/crypto/ContentCipher.kt` | `ContentCipher`(`writeScheme`·`writeKeyVersion`·`encrypt`·`decrypt`) | **저장 경로가 이 포트만 안다.** §4 |
| `core/crypto/StoredContent.kt` | `PlainBody`·`EncryptedContent`·`EncryptedField`(4열)·`EncryptionScheme.AES_256_GCM_V1`·`KEY_VERSION_RANGE` | 봉투 3값을 한 타입으로 들고 다닌다 |
| `infrastructure/crypto/` | `AesGcmContentCipher`·`CryptoConfiguration`(기동 자기점검)·`KeyCheckValue` | 조립은 이미 있다. **테스트가 실제 키를 넣는 것**만 새로 한다(§4.4) |
| `core/exceptions/DomainExceptions.kt` | `UnsupportedFormatException`·`DocumentExtractionException`·`UploadTooLargeException`·`QueueUnavailableException`·`NotFoundException`·`ConflictException`·`InvalidInputException`·`StorageException`·`DecryptionFailedException`·`ConfigurationException` | **문서 도메인 예외가 전부 이미 있다.** 새 예외 타입을 만들 이유가 없다 |
| `api/error/GlobalExceptionHandler.kt` | `mappingFor` — 413/422/404/409/502/503/500 매핑, `handleHandlerMethodValidationException`·`handleMissingServletRequestPart`·`handleTypeMismatch` 오버라이드 | 계약 상태 코드가 **이미 전부 배선돼 있다.** 이 단위가 더하는 것은 multipart 상한 예외 하나(Q-7) |
| `api/config/PrivateResponseHeadersConfig.kt` | 필터 + Tomcat 밸브 | 사적 헤더가 **전역 부착**이라 새 엔드포인트에 자동으로 붙는다. 단 X-D1 하한선 4곳은 **개별 단언**이 필요하다(명세 §6) |
| `api/config/CorsConfig.kt` | `EXPOSED_RESPONSE_HEADERS` | `Content-Disposition`·`Location` 이 **이미 선언돼 있다.** 이 단위에서 `Location` 이 처음 실물이 된다(O-20) |
| `api/auth/` | `AuthenticationInterceptor`·`AuthenticatedUser`·`AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS` | 목록에 새 경로 4개를 **같은 커밋에** 등재한다 — `AuthenticationCoverageContractTest` 가 계약과 정확 대조한다 |
| `infrastructure/auth/JdbcWorkspaceRepository.kt` | 소유 조건을 **WHERE 에 합치는** 패턴, `DuplicateKeyException`→409, `DataIntegrityViolationException`→`StorageException`, `RETURNING` 판정 | 문서·변환 repository 가 같은 형태를 따른다 |
| `infrastructure/db/SpringTransactionRunner.kt` | `TransactionRunner` | 업로드 저장의 단일 트랜잭션 |
| `core/privacy/Masking.kt` | `maskText`·`MaskedItem`·`MaskedText`·`ModelDraft`·`ReviewedBody`·`restoreForExport`·`PlaceholderRestoration` | `ReviewedBody` 는 **PUT 검수 저장 어댑터가 유일한 프로덕션 생성 지점**이 된다(INV-01-a) |
| `core/text/TextNormalization.kt` | `stripControlChars` | 제목·검수본 정규화 |
| `core/easyread/Export.kt` | `ExportFormat`·`exportFilename`·`contentDisposition`·`renderTxt`·`ExportFile` | **`export` 단위가 쓴다.** 이 단위는 건드리지 않는다 |
| `api/src/test/.../support/` | `ContractSpec`(P-1~P-21)·`ContractHeaderDeclaration`·`RawHttp`·`AuthSliceBeans`·`ProductClasses`·`GeneratedToStringProbes` | 계약 파서와 게이트를 **확장**한다(§2.3) |
| `infrastructure/src/testFixtures/` | `PostgresTestSupport`·`DatabaseHandle`·`MigrationCatalog` | Testcontainers 재사용 |
| `infrastructure/src/test/.../auth/CountingDataSource.kt` | 문장 생성 수 계측 | **소유권 은닉의 구조 축**을 문서·변환에 그대로 적용한다(시간 축만으로는 못 잡는다는 실측이 그 KDoc 에 있다) |
| 상시 게이트 | `SensitiveToStringReachTest`·`ProvenanceCreationSitesTest`·`MaskedTextGatewayTest`·`RequestFieldConstraintLayerTest`·`AuthenticationCoverageContractTest`·`ContractHeaderDeclarationTest` | 새 DTO·새 경로가 **자동으로 대상이 된다.** 늘어난 대상이 목록에 등재되지 않으면 빨개진다 |

### 2.2 없는 것 — 이 단위가 처음 만든다

| 모듈 | 신설 | Python 원본(참고) |
|---|---|---|
| `core` | `core/document/` — `SourceFormat`(enum) · `ConversionStatus`(enum) · `Document` · `Conversion` · `MaskedItemView` · `DocumentListing` | `app/models/`, `app/services/documents.py` 의 뷰 타입 |
| `core` | `core/document/TitleRules.kt` — 제목 유도(첫 줄 · 어절 경계 · 말줄임 · 상한 자르기 · 대체 제목) | `app/services/documents.py::_resolve_title`·`_shorten_derived_title` |
| `application` | `application/document/DocumentPorts.kt` — `DocumentRepository` · `ConversionRepository` · `DocumentTextExtractor` · `ConversionQueue` | `app/repositories/documents.py`·`conversions.py` |
| `application` | `application/document/DocumentService.kt`(업로드·목록·삭제) · `ConversionReviewService.kt`(조회·검수 저장) · `DocumentMessages.kt` | `app/services/documents.py` |
| `application` | `application/document/EnvelopeRotation.kt` — 행 단위 재암호화 유스케이스(X5 4조건) | 대응 없음 — Python 은 `MultiFernet` 회전을 「범위 밖」으로 뒀다 |
| `infrastructure` | `infrastructure/ingest/` — `DocumentExtractors`(디스패치·확장자·OLE2 진단) · `DocxExtractor` · `PdfExtractor` · `HwpxExtractor` · `ZipBudget` · `SecureXml` · `Blocks`(블록 결합) | `app/ingest/extractors.py` |
| `infrastructure` | `infrastructure/document/JdbcDocumentRepository.kt` · `JdbcConversionRepository.kt` · `MaskedItemCodec`(대응표 JSON) · `DocumentConfiguration.kt` | `app/repositories/*` |
| `infrastructure` | `infrastructure/queue/JdbcConversionQueue.kt` + `V5__conversion_jobs.sql` | `app/queue.py` (형식은 흉내 내지 않는다 — §4.4 lease 설계) |
| `api` | `api/document/DocumentController.kt` · `ConversionController.kt` · `DocumentDtos.kt` · `ConversionDtos.kt` | `app/api/documents.py` |
| 설정 | `api/src/main/resources/application.yml` — multipart 절(오늘 **0건**) | — |

### 2.3 계약 파서(`ContractSpec`)에 더해야 하는 것 — 실측으로 확인한 갈림 둘

명세 §4-1 이 P-22~P-36 을 요구한다. 그중 **기존 접근자를 그대로 못 쓰는 자리 세 곳**을 확인했다.

| 자리 | 사실 | 처분 |
|---|---|---|
| **P-22 식별자 충돌 (K5)** | `ContractSpec.kt:409` 가 이미 **P-22** 를 「D-2 삭제 거절 우선순위 예시」에 쓰고 있는데, 명세 §4-1 은 P-22 를 「응답 인라인 헤더의 `schema.examples[0]`」에 배정했다 | **contract-keeper 레인의 선결 항목이고 마감이 「문서 API 착수 전」이다.** 해소 전에는 C3 을 시작하지 않는다(§9 ⑤) |
| `inputLimit(name)` | `number("x-input-limits", name)` — **정수만** 돌려준다. 그런데 `list_limit`·`list_offset` 은 `{min, max, default}` **매핑**이다 | P-25 는 새 접근자가 필요하다. 기존 것을 고치면 P-7 사용처가 깨진다 |
| `pathParameters(path)` | `schema.format` 이 없으면 `error()` 로 끊는다(fail-closed). `limit`/`offset` 은 `format` 이 없고, 게다가 **경로 수준이 아니라 오퍼레이션 수준** `parameters` 다 | P-25·P-30 은 **오퍼레이션 수준 파라미터 접근자**를 새로 만든다. fail-closed 규약(`44eec3f`)을 그대로 따르되 `format` 을 필수로 요구하지 않는다 |

---

## 3. (a) 착수 전 마감 항목의 현재 상태 — 커밋 `1e685dc..0ce88b4` 실측

> **이 절은 사실만 적는다.** 닫힘 **판정**은 리더와 게이트 26 의 몫이다(원장 §3 의 규율 그대로 —
> 「배치가 끝나면 다음 게이트가 그 커밋들을 리뷰하고, 닫힘은 그 회차의 판정으로만 기록된다」).

### 3.1 원장 §3 「조치 배치」 — 조치 코드가 실재하는가

| 항목 | 원장 마감 | 커밋 | 코드 근거(실측) | 관측 |
|---|---|---|---|---|
| **X1** 짝 없는 서로게이트 쓰기 전 거부 | **documents 단위 착수 전** | `81f37af` | `core/.../crypto/StoredContent.kt` — `PlainBody.init` 이 `hasUnpairedSurrogate` 로 끊고 `UNPAIRED_SURROGATE_MESSAGE` 고정. 회귀 `core/.../crypto/PlainBodyTest.kt` | **실재.** 단 **도달은 오늘도 0** — `PlainBody` 를 만드는 제품 코드가 `AesGcmContentCipher.decrypt` 한 곳뿐이고 그 경로는 원리상 이 예외를 못 던진다(UTF-8 디코딩은 고아 서로게이트를 만들지 않는다). **도달은 이 단위가 연다** → §6 |
| **F-3/X6** 키 지문(KCV) | **documents 단위 착수 전** | `a7f6e30` | `infrastructure/crypto/KeyCheckValue.kt`(고정 입력 AEAD 태그 6바이트 hex) + `CryptoConfiguration.checkValueProblems`·`verify()` fail-fast | **실재.** 단 **테스트 태스크 전역이 `easydoc.encryption.verify-on-startup=false`**(`build.gradle.kts:151`)라, 조립 경로가 CI 에서 실제로 도는 곳은 `CryptoStartupVerificationTest` 하나다 |
| **F-2/X7** 쓰기 키 부재 → 기동 실패 | documents 단위 | `a7f6e30` | `CryptoConfiguration.writeKeyProblems` — `writeKeyVersion !in loadedKeyVersions` 이면 `ConfigurationException` | **실재** |
| **V4 / F-4·X8** `CHECK (key_version > 0)` | **첫 INSERT 전** | `58a292b` + `ccc508e` | `V4__key_version_domain.sql`(사전 확인 DO 블록 + 두 테이블 CHECK) · `EncryptedContent.KEY_VERSION_RANGE`(1..Short.MAX) · DB 강제 확인 테스트 | **실재.** 도메인 타입·조립 검증·스키마 CHECK 3층 |
| **X10** `wireName` 변경 탐지기 | **첫 INSERT 착수 전** | `7be37db` | `EncryptionSchemeSchemaTest:130` — `wireName` ↔ 스키마 `bytea` 컬럼 **양방향** 대조 + 중복 검사 | **실재** |
| **타이밍/X3** + **H11** | documents 단위 | `558936c` · `6431208` | `AesGcmContentCipher.decrypt` 가 조기 반환을 없애고 `uniformCostKey`(기동 시 난수)·`UNIFORM_COST_BYTES` 로 `open()` 을 정확히 1회 돌린다 | **실재.** 산출물 실측 비 1.012(문턱 1.5) |
| **R-4/X11** `decrypt` catch 범위 | Phase 4 내 | `558936c` | `open()` 이 `GeneralSecurityException` + `RuntimeException` 포섭 | **실재** |
| **R-1/X4** AAD 2축 격리 증거 | Phase 4 종료 전 | `558936c` | 키 재료 공유 2세대 케이스 + JCA 미러 케이스 | **실재** |
| **H-1/H1** 소스 파서 `fun` · **U-1** 선언 수 정확 일치 | ⑴다음 중첩 커밋 ⑵Phase 4 내 / Phase 4 내 | `0061c8d` · `185dd89` | `ProductClasses` `MODIFIERS` 에 `fun` · 선언 수 `isEqualTo(46)` | **실재** |
| **R-10** 일반 class `toString` 축 | Phase 4 내 | `185dd89` | 재정의 선언 9건 중 민감 후보 3건 판정. `ExportFile.toString` 이 파일명을 찍던 것을 길이로 교체 | **실재** |
| **스캐너 403**(H6·H7) | **Phase 4 문서 소유권 경로 진입 전** | `aad5ca5` | `scan_privacy_invariants.py` — `_403_TOKEN`(이름 열거)을 불활성 판정 전용으로 물리고 `_403_NAME`(종류) + `_403_STATUS_SITE`(자리)로 전환 | **실재**(하네스 레인) |
| **H4** 게이트 클래스 실재·실행 대조 | Phase 4 내 | `8f3730f` | — | **실재**(하네스 레인) |
| **SKILL L1** 조치 레인의 `reviews/` 쓰기 금지 | Phase 4 내 | `e572476` | `.claude/skills/kotlin-migration/SKILL.md:199` | **실재**(하네스 레인) |
| **I-8** `encryption_scheme` DEFAULT/CHECK | Phase 4 암호 설계 첫 커밋 | `e891a08` | `V3__encryption_scheme_aead.sql` | 실재(범위 밖 — 게이트 25 에서 이미 판정) |
| **X24-2 잔여**(조건 9) 401 3갈래 비율 회귀 | Phase 4 착수 전 | `858347d` | M-3b | 실재(게이트 25 판정 ⑦ 에서 닫힘, codex A-6 단서 병기) |
| **조건 11** 인라인 헤더 처분 | Phase 4 두 엔드포인트 생성 전 | `765a377`(계약) | 명세 §7-1 ⑴ — **인라인 유지**, ⑵ `x-filename-charset` 신설 | 판정 완료(contract-keeper) |

### 3.2 **미조치** — 이 단위가 마감이거나 선결인 것

| 항목 | 원장 마감 | 실측 | 이 계획의 처분 |
|---|---|---|---|
| **X9/F-6** 조립된 빈을 **실제 키**로 쓰는 통합 테스트 0 | documents 단위(blocked-by) | `build.gradle.kts:151` 이 모든 테스트 태스크에 `verify-on-startup=false` 를 주고, **어떤 Spring 테스트 컨텍스트도 `easydoc.encryption.keys` 를 주지 않는다**. `WorkspaceEndpointReachTest` 는 `documents` 행을 **손으로 INSERT** 한다(더미 `bytea`) | **§4.4 가 전담한다** |
| **X5/F-5** 행 단위 재암호화 4조건 | documents 단위 | 포트 시그니처만 준비(crypto-fixes §4-②). 강제 장치 0 | **§4.3 이 전담한다** |
| **X2** `PlainBody` 웹 직렬화 fail-closed | 응답 DTO 신설과 동시 | `crypto` 패키지 밖 `PlainBody` 참조 0건 — **이 단위가 그 「동시」다** | **§4.5** |
| **표 18** 강제 TRACE 에서 프레임워크 로거 3종 미도달 | **Phase 4 문서 본문 진입 전** | `backend-kotlin/**` 에 해당 회귀 0건. `api/application.yml` 의 고정 세 줄 중 어느 것도 `Http11InputBuffer`·`StatementCreatorUtils`·`QueryExecutorImpl` 을 가리키지 않는다 | **C1 에 넣는다** — 이름 열거가 아니라 **탐지형**으로(§8-7) |
| **K-2** `CountingDataSource` 의 `JdbcClient` 전제를 장치로 | Phase 4(raw JDBC 하강 커밋) | KDoc 만 | 이 단위는 `JdbcClient` 를 벗어나지 않을 계획이다. **그 사실 자체를 단언**으로 바꿔 전제를 장치화한다(§8-6) |
| **K5** P-22 식별자 충돌 | **문서 API 착수 전** | `ContractSpec.kt:409` = D-2 / 명세 §4-1 P-22 = 인라인 헤더 examples | **contract-keeper 레인. C3 의 선결 조건**(§9 ⑤) |
| **K1** `x-filename-charset` 소비자 배선 | 내보내기 엔드포인트 커밋 | `Export.kt` 의 `FORBIDDEN_IN_FILENAME` 이 `private`, 계약을 읽는 코드 0 | **`export` 단위** — 이 계획의 범위 밖 |
| **K2·K4·H5** | — | 미조치 | contract-keeper 레인 |

### 3.3 인용 오기 하나 (사실 기록)

리더 지시문과 원장 Phase 4 표 둘째 행이 파서 방어를 **I-11** 로 인용한다.
`.claude/skills/migration-safety-gate/SKILL.md` 실측으로 **XXE·zip bomb·10MB·스캔 PDF 는 I-10** 이고,
**I-11 은 보존 만료 파기(04:00 KST·500건·advisory lock)** 다. 판정에 영향은 없으나(대상이 명확하다)
다음 레인이 「I-11」로 감사 항목을 찾으면 **엉뚱한 절**을 읽는다. 이 계획은 **I-10** 으로 적는다.

---

## 4. (b) 저장 경로 설계 — `ContentCipher` 를 부르는 첫 제품 코드

### 4.1 어느 층이 암호화하는가 — **`application` 서비스**

repository 는 `EncryptedContent` 만 주고받고 `PlainBody` 를 **타입으로 보지 못한다.** 근거 셋.

1. **AAD 가 행 UUID 를 요구한다.** `associatedData(scheme, keyVersion, record, field)` 에 행 식별자가
   들어가므로 **UUID 생성이 암호화보다 앞서야** 하고, 그 순서를 정하는 곳은 유스케이스다.
   (`JdbcWorkspaceRepository.create` 는 repository 가 UUID 를 만드는데, 문서는 그럴 수 없다.
   **이 비대칭을 산출물에 명시한다** — 다음 사람이 「왜 여기만 다른가」를 묻는다.)
2. **평문의 노출면을 좁힌다.** `infrastructure` 가 `PlainBody` 를 들면 JDBC 계층까지 평문 타입이
   퍼지고, X2(웹 직렬화)와 로그 유출의 후보가 그만큼 는다.
3. **회전이 한 유스케이스다.** 「복호 → 재암호 → 단일 UPDATE」에서 repository 는 UPDATE 한 문장만
   진다. 암호를 repository 에 두면 회전이 repository 안에서 트랜잭션·실패 정책까지 지게 된다.

### 4.2 업로드 저장 — 한 트랜잭션, 명시적 봉투

계약 `paths./documents.post.description` 이 검사 순서를 못박았다:
**파일 크기(413) → 추출(422) → 본문 길이(422) → 작업 공간 소유권(404) → 저장 → 커밋 → 큐 등록(502)**.
「작업 공간 확인이 저장보다 먼저」인 이유도 계약이 적었다 — 거절당한 업로드가 기본 공간에 남지 않게.

```
1. (컨트롤러) Content-Type 을 대소문자 무시로 갈라 JSON/multipart 판정   ← DC-5
2. (컨트롤러) multipart: 파트 존재·파일 여부 → 422                        ← DC-6
3. (서비스)  바이트 상한 판정 → UploadTooLargeException → 413             ← DC-12·DC-13
4. (서비스)  추출 → DocumentExtractionException/UnsupportedFormatException → 422  ← DC-14·DC-15
5. (서비스)  본문 길이 판정(원시 값) → InvalidInputException → 422         ← DC-9·DC-10·DC-11
6. (서비스)  transaction.inTransaction {
       workspaceId = 소유 조건을 WHERE 에 합친 조회 ?: NotFoundException   ← DC-16·DC-17
       documentId  = UUID.randomUUID()
       conversionId= UUID.randomUUID()
       sealed = cipher.encrypt(PlainBody(text), documentId, DOCUMENT_SOURCE_TEXT)
       documents.insert(documentId, ..., sealed, cipher.writeScheme, cipher.writeKeyVersion)
       conversions.insertPending(conversionId, documentId, cipher.writeScheme, cipher.writeKeyVersion)
   }
7. (서비스)  커밋 이후 큐 등록 → 실패 시 failure_code 표시 후 502          ← DC-18
```

설계상 확정할 것 넷.

- **`encryption_scheme`·`key_version` 을 INSERT 문에 명시한다.** `V3` 가 DEFAULT 를 없앴으므로
  빠뜨리면 NOT NULL 위반으로 **즉시 시끄럽게** 실패한다 — 그것이 `V3` 의 설계 의도다.
  값은 `ContentCipher.writeScheme`/`writeKeyVersion` 에서 온다. 따라서 **키가 없으면
  `conversions` 행조차 만들 수 없고** 업로드는 `ConfigurationException` → 503 이 된다.
- **`conversions` 행은 암호문 3열이 전부 NULL 인 상태로 태어난다.** 그래도 봉투 2값은 적는다
  (`V3` 주석의 판단 그대로 — 나중에 NULL 해석 규칙을 만드는 것보다 낫다).
- **Python 의 `key_version` 방식을 옮기지 않는다.** Python 은 컬럼 기본값으로만 찍고 이후 UPDATE 가
  갱신하지 않아, 회전이 그 사이에 일어나면 **컬럼이 가리키는 세대와 실제 키가 갈리는 구조**였다
  (실측: `app/repositories/**` 에 `key_version` 이 한 번도 나오지 않고, `key_version` 을 고정하는
  테스트 2줄이 전부 INSERT 직후 검사다). Kotlin 은 `EncryptedContent` 가 세 값을 한 타입으로 묶고
  UPDATE 가 봉투를 함께 쓴다 — **이것은 포팅이 아니라 다른 설계이며 그 사실을 산출물에 적는다.**
- **소유권 확인과 INSERT 는 같은 트랜잭션 안**이다. 잠금은 걸지 않는다 — FK 가 마지막 방어선이고,
  작업 공간 삭제는 문서가 있으면 이미 409 로 막힌다(`JdbcWorkspaceRepository.delete`).

### 4.3 X5 / F-5 — 행 단위 재암호화의 4조건

원장이 연 조건은 **단일 UPDATE · NULL 보존 · 실패 시 전체 중단 · 평문 체류 최소화**다.
포트를 이 형태로 두면 넷이 **구조로 강제된다**.

```kotlin
interface ConversionRepository {
    /** 봉투 3열과 scheme·key_version 을 한 UPDATE 로 바꾼다. 갱신 여부를 돌려준다. */
    fun rewriteEnvelope(
        conversionId: UUID,
        expectedKeyVersion: Int,                 // 낙관적 조건: WHERE key_version = :expected
        scheme: String,
        keyVersion: Int,
        easyText: EncryptedContent?,             // 원본이 NULL 이면 null 을 그대로 넘긴다
        maskedItems: EncryptedContent?,
        editedText: EncryptedContent?,
    ): Boolean
}
```

| 조건 | 강제 방법 | 왜 그 방법인가 |
|---|---|---|
| **단일 UPDATE** | 포트가 세 열을 **함께** 받는다. 열 하나짜리 갱신 메서드를 만들지 않는다 | 행당 세대가 하나라 두 문장으로 나누면 「세대는 v2 인데 한 열은 v1 암호문」인 중간 상태가 생기고, 그 행은 **영원히 열리지 않는다**(AAD 에 세대가 실린다) |
| **NULL 보존** | 서비스가 `EncryptedContent?` 를 그대로 넘기고 repository 는 널을 널로 세팅한다. 빈 문자열을 암호화하지 않는다 | 대기 중 변환은 3열이 NULL 이다. `""` 를 암호화하면 **없던 내용을 지어낸 것**이고, 되돌릴 수 없다 |
| **실패 시 전체 중단** | 서비스가 세 열을 **전부 복호화한 뒤에** 암호화한다. 하나라도 `DecryptionFailedException` 이면 `rewriteEnvelope` 를 부르지 않고 트랜잭션을 롤백한다 | 부분 회전 행은 위와 같은 이유로 복구 불가다 |
| **평문 체류 최소화** | 복호 → 즉시 재암호. 평문을 컬렉션·필드·로그 어디에도 두지 않는다. 회전 로그는 `conversion_id`·건수·소요만 | `CLAUDE.md` 보안 규칙 + I-4 |
| (덤) **동시 회전** | ~~`WHERE key_version = :expected` — 두 프로세스가 같은 행을 잡으면 뒤엣것이 0행을 갱신하고 재시도한다~~ | ~~잠금 없이 경합을 안전하게 만든다~~ **← 이 줄은 반증됐다. §9.2-ter 참조** (막는 것은 회전-대-회전뿐이고, 내용 쓰기는 `key_version` 을 건드리지 않아 조용히 사라진다) |

**이 단위가 만드는 것은 포트·구현·테스트까지다.** 회전을 **누가 언제 부르는가**(운영 CLI·worker
스케줄·마이그레이션)는 §9 질문 ⑦.

### 4.4 X9 / F-6 — 조립된 빈을 **실제 키**로 쓰는 통합 테스트

**오늘 상태(실측):** `build.gradle.kts:151` 이 모든 테스트 태스크에
`easydoc.encryption.verify-on-startup=false` 를 주고, `WorkspaceEndpointReachTest` 를 포함한 모든
Spring 컨텍스트가 `easydoc.encryption.keys` 없이 뜬다 → `AesGcmContentCipher.keys` 가 비어
`encrypt` 가 언제나 `ConfigurationException`(503) 이다. **통합 테스트가 붙어도 503 갈래만 밟는다.**

조치:

1. **키를 소스에 적지 않고 런타임에 만든다.** `@DynamicPropertySource` 에서 `SecureRandom` 으로
   32바이트를 뽑아 base64 로 넣고, **같은 자리에서 `KeyCheckValue.of()` 로 계산한 KCV 를 함께 넣는다.**
   - 리터럴이 없으므로 스캐너 `SECRET-LITERAL` 에 걸리지 않는다.
   - KCV 를 계산해 넣으므로 **F-3 의 대조 경로가 실제로 돈다**(값을 비워 두면 그 경로가 실패한다).
2. **같은 자리에서 `easydoc.encryption.verify-on-startup=true` 를 준다.** 그러면 그 컨텍스트는
   기동 자기점검을 **실제로 통과해야** 뜬다 — X9 가 요구한 「조립된 빈」이 그 순간 증명된다.
3. **속성 우선순위를 가정하지 않고 실측한다.** Gradle 이 준 것은 **시스템 속성**이고 테스트가 주는
   것은 인라인/동적 속성이다. 우선순위가 기대와 다르면 자기점검이 켜지지 않은 채 초록이 된다 —
   **「켜졌는지」를 단언하는 케이스를 함께 둔다**(잘못된 키를 주면 컨텍스트 기동이 실패하는가).
4. **2세대 키를 함께 실어** 회전 왕복(§4.3)도 조립된 빈으로 돈다.
5. **음성 대조**: 키를 빼면 업로드가 503 이 되는 케이스를 별도 컨텍스트(C-P)로 둔다.

> **이 조치가 없으면 §4.2·§4.3 의 모든 초록이 「503 을 잘 낸다」의 초록이다.**

### 4.5 X2 — `PlainBody` 가 응답으로 나가지 않음을 **fail-closed** 로

`PlainBody` 는 `@JvmInline value class(String)` 이라 Jackson 이 그냥 문자열로 직렬화한다.
오늘 도달 0(참조가 `crypto` 패키지 밖에 없다)이지만, **응답 DTO 가 생기는 이 단위가 그 「동시」다.**

- **조치**: `api` 의 요청·응답 DTO 주 생성자 파라미터 타입 집합에 `PlainBody`·`MaskedText`·
  `ModelDraft`·`ReviewedBody`·`EncryptedContent` 가 **하나도 없음**을 단언한다.
  기제는 이미 있다 — `ProductClasses.declaredInMainSources()` + `GeneratedToStringProbes` 가
  주 생성자 파라미터를 반사로 읽고 **모르는 타입을 만나면 끊는다.**
- **왜 「직렬화 금지 애너테이션」이 아닌가**: 애너테이션은 새 DTO 가 안 붙이면 조용히 새고,
  「안전 선언」은 곧 면제 조항이다(`CLAUDE.md` 규칙 4 — 은폐형은 넓히지 않고 탐지형으로 갈아탄다).
  **타입 부재 단언은 반대 방향이다** — 새 DTO 가 그 타입을 들면 그 즉시 빨개진다.

### 4.6 복호화 조회 경로

- `GET /conversions/{id}` — 소유 조건을 **`documents` 조인으로** WHERE 에 합쳐 조회하고
  (`conversions` 에 `user_id` 가 없다), 3열을 복호화해 응답을 만든다.
- 복호화 실패는 `DecryptionFailedException`(= `StorageException`) → **500**. 원인은 구분하지 않는다(I-7).
- **마스킹 대응표(`masked_items_encrypted`)의 형식을 이 단위가 처음 정한다.**
  Python 은 Fernet + JSON 이었고 Kotlin 은 대응이 없다. 결정:
  - 평문 JSON 배열 `[{category, placeholder, original}]` 을 만들고 **그 바이트를 통째로 AEAD 로 봉인**한다.
  - 직렬화기는 `infrastructure/document/MaskedItemCodec` 에 둔다 — `core` 는 Jackson 을 모른다(§3.2).
  - `MaskedItem.original` 은 `Secret` 이므로 코덱이 `reveal()` 하는 자리가 **정확히 한 곳**이고
    그 함수는 로그를 남기지 않는다.
  - `category` 는 계약 `MaskedItemResponse.properties.category.enum` 의 **한국어 값**이 그대로
    화면 문구다 — 코덱이 그 값을 **저장 형식으로** 쓰면 enum 이름을 바꿀 때 옛 행이 안 읽힌다.
    **저장에는 안정된 키를, 응답에는 계약 enum 값을** 쓴다(매핑을 한 곳에 둔다).
  - 쓰기는 Phase 5 워커의 일이지만 **이 단위가 형식과 코덱을 정하고 양방향 테스트를 둔다** —
    읽기만 만들면 워커가 다른 형식을 쓰는 순간 조용히 갈린다.

---

## 5. (c) 문서 파서 보안 — 어느 층에서 거는가와 음성 대조

**정본은 `migration-safety-gate` I-10** 이다(§3.3 의 인용 오기 참고). 층 표기:
**L0** 서블릿 컨테이너 / **L1** API·서비스 / **L2** 추출기 디스패치 / **L3** 파서.

| # | 방어 | 층 | 장치 | 왜 그 층인가 | 음성 대조 — 떼면 무엇이 깨지는가 |
|---|---|---|---|---|---|
| D-1 | 업로드 바이트 상한 | **L0 + L1** | L0: `spring.servlet.multipart.max-file-size`·`max-request-size` 를 계약 상한 **이상**으로. L1: 서비스가 정확 경계를 판정해 `UploadTooLargeException` → 413 | **L0 만이면** 경계값과 `detail` 이 컨테이너 것이 되어 DC-13(정확히 상한 통과)이 구현에 좌우된다. **L1 만이면** 무제한 바이트가 먼저 버퍼링된다 | L1 검사 제거 → DC-12 빨강 / L0 을 상한 미만으로 → DC-13 빨강 |
| D-2 | 상한 초과 예외 매핑 | **L1** | `GlobalExceptionHandler` 에 multipart 상한 예외 핸들러 신설 → 413 + 계약 문구 | 없으면 `handleUnexpected` 가 **500** 을 낸다(오늘 상태) | 핸들러 제거 → DC-12 가 500 |
| D-3 | 초과분 삼키기 | **L0** | `server.tomcat.max-swallow-size` (Q-8) | 삼키지 않으면 연결 리셋이라 **클라이언트가 413 본문을 못 본다** — C-R 에서만 드러난다 | 값을 되돌리면 DC-12 의 `detail` 단언이 소켓에서 빨강 |
| D-4 | 추출 결과 길이 상한 | **L2** | 블록을 이어 붙이는 동안 **누적 길이로** 끊는다 | 사후 검사는 이미 힙에 올라온 뒤다 | 상한 제거 → 「작은 업로드가 수백만 자」 fixture 통과 |
| D-5 | zip 압축 해제 예산 | **L2** | commons-compress `ZipFile` + `SeekableInMemoryByteChannel`, `read(min(chunk, budget+1))` | I-10 검증 3 이 **`ZipEntry.getSize()` 신뢰를 금지**한다. spike S-6 | ⓐ 예산 제거 → `oversized.zip` 통과 ⓑ **경계 없는 읽기 프로브**로 `forged_size.zip` 을 읽어 힙 급증을 실증(취약점 재현) |
| D-6 | DTD·외부 엔터티 (hwpx) | **L3** | `XMLInputFactory` 3속성 명시 + **`XMLStreamConstants.DTD` 이벤트를 받아 우리 예외** | 메시지 매칭은 **로케일에 따라 오분류**된다(spike S-5 실측). Python `expat` 훅과 1:1 | 이벤트 거부 제거 → billion laughs·**UTF-16 인코딩 DTD** fixture 통과 |
| D-7 | DOCTYPE (docx) | **L3** | POI 의 `disallow-doctype-decl` 에 **기대지 않고** 그 동작을 테스트로 고정(Q-2) | 기본값은 업그레이드에서 조용히 바뀐다 | DOCTYPE 주입 docx fixture 가 통과하면 빨강 |
| D-8 | POI 자신의 zip 방어 | **L3** | `ZipSecureFile` 설정을 기동 시 1회(Q-1). **JVM 전역 static 이면** 테스트 격리를 함께 설계 | 전역 상태는 테스트가 서로를 오염시킨다 | 설정 제거 → docx zip bomb fixture 통과 |
| D-9 | PDF 메모리 상한 | **L3** | `Loader.loadPDF` 의 메모리/스트림 캐시 설정(Q-3) | 상한을 못 걸면 I-10 의 OOM 축이 **PDF 쪽에만 열린 채** 남는다 | 상한 제거 후 큰 PDF 로 힙 관측 |
| D-10 | 스캔 PDF 거절 | **L2** | 추출 결과가 공백이면 거절. **페이지 0건과 구분**해 다른 문구 | 사용자가 취할 조치가 다르다 | 거절 제거 → `empty.pdf` 가 빈 문서로 저장 |
| D-11 | 암호 PDF | **L3** | `isEncrypted()` 로 **미리 거르지 않는다** — 소유자 암호만 걸린 공공 PDF 가 흔하고 그것은 정상 추출된다(Python 근거). 진짜 암호만 예외로 걸린다(Q-4) | 미리 거르면 **정상 문서를 거절**한다 | 사전 거름을 넣으면 「소유자 암호 PDF 정상 추출」 케이스가 빨강 |
| D-12 | 구버전 `.doc`(OLE2) | **L2** | 매직 4바이트 + UTF-16LE 스트림 이름 검색 **3분기**(암호 컨테이너 / 구버전 워드 / 미상) | 계약 `x-input-limits.legacy_doc_policy` 가 **전용 문구**를 요구한다. 안내가 같으면 후자의 사용자가 없는 암호를 찾아 헤맨다 | 3분기를 합치면 DC-15 의 전용 문구 케이스 빨강 |
| D-13 | 지원 확장자 집합 | **L2** | 구현은 상수를 갖고 **테스트가 계약 `x-input-limits.supported_upload_formats` 에서 읽어 케이스를 유도**(P-26) | 집합이 늘어도 검사가 안 늘면 새 형식이 **검사 자체를 받지 않는다** | N-26 형태 — 계약 원소를 빼면 DC-14 가 줄어야 한다 |
| D-14 | 동시 추출 제한 | **L1** | 전용 bounded executor 또는 `Semaphore`. Python 은 `CapacityLimiter(4)` | 건당 예산이 수십 MB 인데 컨테이너 스레드는 수백이다 — **곱하면 OOM** | 제한 제거는 힙으로 재기 어렵다 → **구조 단언**(제한 장치가 배선돼 있다) + 동시 진입 최대치를 세는 테스트 |
| D-15 | 파서 예외가 스택트레이스로 새지 않음 | **L1** | 이미 있다 — `server.error.include-*: never` + `GlobalExceptionHandler` | I-10 검증 5 | 기존 회귀가 담당 |
| D-16 | 로그 규약 | **L2** | 형식명 · **바이트 길이** · 예외 **타입**만. 파일명·본문·라이브러리 메시지 금지. `size` 는 언제나 업로드 전체 길이 | `CLAUDE.md` + I-4. 라이브러리 메시지에는 임시 경로가 섞인다 | 로그에 예외 **메시지**를 넣으면 빨개지는 캡처 테스트 |
| D-17 | 파일명을 저장·로깅하지 않는다 | **L1** | 확장자 판별에만 쓰고 버린다. 제목은 사용자 제공 또는 본문 첫 줄 유도 | 파일명 자체가 개인정보일 수 있다(`홍길동_주민등록등본.pdf`) | 제목에 파일명이 들어가면 빨개지는 케이스 |

### 5.1 fixture — 있는 것과 만들어야 하는 것

**`tests/ingest/fixtures/doc-spike/` 는 P1 반출 대상이고 자기완결이다**(그 README 가 명시).
Kotlin test resources 로 **복사**해 온다(원본은 Phase 8 까지 지우지 않는다).

| fixture | 상태 | 이 단위에서의 쓰임 |
|---|---|---|
| `sample.docx`·`sample_table.docx`·`sample_rich.docx`·`sample.pdf`·`empty.pdf`·`sample.hwpx` | 있음 | DOC-01 정확성 6종 |
| `doc-spike/sdt_shape_math.docx` | 있음 | SDT·도형 `a:t`·수식 `m:t` — 루트 6개에 **없는** 케이스 |
| `doc-spike/layout.pdf` | 있음 | 다단 레이아웃(S-7 의 대상) |
| `doc-spike/oversized.zip` | 있음 | 예산 **기제 시험** |
| `doc-spike/forged_size.zip` | 있음 | 예산 **취약점 실증**. 판정은 예외가 아니라 **최대 메모리**로 한다 |
| `doc-spike/repo-fixtures-oracle.json`·`spike-oracle.json` | 있음 | **참고값**(Python 산출). 갈리면 어느 쪽이 DOC-01 에 맞는지 판단해 기록한다 |
| DTD 폭탄(UTF-8·**UTF-16**) hwpx | **없음 — 만든다** | D-6 |
| DOCTYPE 주입 docx | **없음 — 만든다** | D-7 |
| 암호 PDF·암호 OOXML **실파일** | **없음** | D-11 · spike §6-4 의 미검증 항목 |
| **고아 서로게이트를 내는 PDF** | **없음 — 만든다** | §6 (d) |
| 실제 한컴/Word 저장 문서 | **없음** | spike §7-5 가 「확보 권고」로 남긴 것. §9 질문 ⑧ |

깨진 파일·폭탄은 **커밋하지 않고 정상 fixture 를 변형해 즉석 생성**한다(Python `tests/ingest/` 의 방식).

---

## 6. (d) X1 의 도달 확장 — 사용자 파일이 들어오는 순간

### 6.1 어디서 들어오는가 (경로별 판정)

| 경로 | 짝 없는 서로게이트가 들어올 수 있는가 | 근거 |
|---|---|---|
| **docx·hwpx** | **거의 불가** | 값이 well-formed UTF-8 XML 을 거친다. 고아 서로게이트는 UTF-8 로 **인코딩 자체가 불가능**하므로 파서가 거부하거나 대체 문자를 만든다 |
| **PDF** | **가능 — 가장 그럴듯한 도달점** | PDFBox 가 `ToUnicode` CMap 을 따라 **UTF-16BE 코드유닛을 조립**한다. 깨졌거나 악의적인 CMap 이 홀로 있는 상위 서로게이트를 내면 그대로 `String` 에 실린다 |
| **JSON 붙여넣기** | **미확인 — Q-12** | `{"text": "x\ud800y"}`. Jackson 3 이 이 이스케이프를 통과시키면 **이 경로가 X1 의 첫 도달**이고, 파일 업로드보다 먼저 실재한다 |

**Q-12 의 답이 「통과」면 C3 에서, 「거부·치환」이면 C1(PDF fixture)에서 도달이 열린다.**
어느 쪽이든 이 단위 안이다.

### 6.2 어떻게 걸리는가 — 구조로 강제된다

저장 경로에서 `PlainBody` 를 우회할 방법이 **없다**: repository 포트가 `EncryptedContent` 만 받고,
`EncryptedContent` 는 `ContentCipher.encrypt(PlainBody, …)` 로만 만들어진다.
즉 **본문이 저장되려면 반드시 `PlainBody` 생성자를 지난다** — 이것이 X1 도달의 근거다.

### 6.3 사용자에게 나가는 응답 — **계약에 없는 갈래가 하나 생긴다**

`PlainBody` 는 `InvalidInputException` 을 던지고 `GlobalExceptionHandler` 가 **422 · `detail` 문자열**로
매핑한다. 문구는 `PlainBody.UNPAIRED_SURROGATE_MESSAGE` 고정이며 입력값을 담지 않는다.

**그런데 그 문구가 계약 `POST /documents` 422 예시 4갈래(빈 본문·길이 초과·미지원 형식·필드 누락)
어디에도 없고, `x-input-limits` 도 `x-request-field-constraints` 도 이 거절을 적지 않는다.**
`examples` 는 전칭이 아니므로 계약 위반은 아니지만, **계약이 말하지 않는 거절**이 생긴다.

- 판정 전까지는 **리더 판정 ② 문면대로 「거부 + 전용 문구」**로 두고 그 사실을 산출물에 표시한다.
- 조항이 필요한지는 §9 질문 ④.

### 6.4 함께 발견한 것 — **제목 경로는 `PlainBody` 를 지나지 않는다**

`documents.title` 은 **평문 `character varying(255)`** 라 암호화를 거치지 않는다. 따라서
제목에 든 고아 서로게이트는 `PlainBody` 검사를 **통과하지 않고** 그대로 JDBC 로 간다.
`stripControlChars` 는 서로게이트를 지우지 않는다(실측: 패턴이 `\x00-\x08\x0B\x0C\x0E-\x1F\x7F`).

- 결과는 드라이버가 UTF-8 로 인코딩하는 시점에 갈린다 — **치환(`?`)이면 조용한 손상**,
  **오류면 500**. 어느 쪽인지 실측이 필요하다.
- 제목의 바탕은 사용자 제목 또는 **본문 첫 줄**이므로, 본문이 거부되면 제목도 안 생긴다.
  그러나 **사용자가 준 제목**은 본문 검사와 무관하게 들어온다.
- 처분: 제목에도 같은 정의역 판정을 적용한다. 별도 타입을 만들지 말고 **`PlainBody` 가 쓰는
  판정 함수를 `core` 에서 공개**해 제목 정규화가 같은 규칙을 쓰게 한다(같은 사실을 두 곳에 적지 않는다).
- 음성 대조: 제목 판정을 지우면 「고아 서로게이트 제목 → 422」 케이스가 빨강.

### 6.5 X1 음성 대조 계획

| 변이 | 빨개져야 하는 것 | 깨지지 않아야 하는 것 |
|---|---|---|
| `PlainBody.init` 의 검사 제거 | `PlainBodyTest` 2건 · 「고아 서로게이트 본문 → 422」 · (Q-12 가 통과면) 「JSON 이스케이프 → 422」 | 나머지 업로드 케이스 전부 |
| 제목 판정 제거 | 「고아 서로게이트 제목 → 422」만 | 본문 케이스는 **깨지지 않아야** 한다(두 축이 한 값으로 묶이면 결함) |

---

## 7. (e) 작업 순서와 커밋 분할

**리뷰 게이트가 한 덩어리마다 도는 것을 전제**로, 계약 명세 §6 의 「같은 변경 단위 요건」을
어기지 않는 최소 분할이다. **§6 이 요구하는 단언은 그 기능을 만드는 커밋 안에 함께 들어간다.**

### 7.1 선결 조건 (커밋 전에 닫혀야 한다)

| # | 선결 | 소유 | 막는 커밋 |
|---|---|---|---|
| P1 | **Q-1~Q-12 공식 문서 확인** | 이 레인 | C1·C3 |
| P2 | **spike DOCX 동등성 7항목을 Kotlin 2.3.21 / Boot 4.1.0 조합으로 재확인**(S-12, 카탈로그 주석이 예고한 것) | 이 레인 | C1 |
| P3 | **K5 — P-22 식별자 충돌 해소** | `contract-keeper` | C3 |
| P4 | **compose 기동 스모크**(crypto-fixes §5.2 가 「documents 착수 전 권고」로 남긴 것) | 이 레인 | C2 |
| P5 | **§9 질문 ①②③⑦ 리더 판정** | 리더 | ①③ → C3 / ⑦ → C2 |

### 7.2 커밋 분할 — 8커밋 / 게이트 3덩어리 제안

| # | 커밋 | 내용 | 게이트 덩어리 |
|---|---|---|---|
| **C1** | `feat(kotlin): 문서 추출기 — docx·pdf·hwpx 와 파서 방어` | version catalog(POI·PDFBox·commons-compress·로깅 브리지) + 락파일 · `infrastructure/ingest/**` · `application` 포트 `DocumentTextExtractor` · fixture 이관 + 신규 fixture · **§5 D-4~D-17 전건** · **표 18 TRACE 탐지 회귀** | **G-α** |
| **C2** | `feat(kotlin): 문서·변환 저장 경로 — 단일 트랜잭션과 봉투` | `core/document/**` · `application/document/DocumentPorts.kt` · `infrastructure/document/**`(repository·`MaskedItemCodec`) · **§4.2 업로드 저장** · **§4.3 재암호화 4조건** · **§4.4 실제 키 통합 테스트** · **§4.5 X2** · `V5__conversion_jobs.sql` + 큐 등록 | **G-α** |
| **C3** | `feat(kotlin): POST /documents — 두 입력 갈래와 접수` | 컨트롤러·DTO · multipart 설정 · **D-1~D-3** · `AuthenticatedEndpoints` 등재 · **DC-1~DC-23 전건** · **P-22·P-24·P-26·P-27·P-33·P-34·P-35·P-36 + N-23·N-25·N-28** · X-A3(DC-21) · X-B1/B2(DC-16·DC-17) · X-D4(DC-2) · **§6 X1 도달** | **G-β** |
| **C4** | `feat(kotlin): GET /documents — 목록과 페이지 파라미터` | `spring-boot-starter-validation` 도입 · `limit`/`offset` Bean Validation · **DL-1~DL-11**(특히 **DL-5 의 `detail` 타입 단언**) · **P-25 + N-24** · F3 대체 강제자 강화 | **G-β** |
| **C5** | `feat(kotlin): DELETE /documents/{id} — 즉시 파기` | **DD-1~DD-7** · FK CASCADE 로 변환 동시 파기 확인 | **G-β** |
| **C6** | `feat(kotlin): GET /conversions/{id} — 상태와 결과 조회` | 복호화 조회 · 마스킹 항목 응답 · **CR-1~CR-10** · **P-31·P-32 + N-26·N-27** · X-E2/E3/E4 | **G-γ** |
| **C7** | `feat(kotlin): PUT /conversions/{id} — 검수 저장` | 정규화 선행 · 초안 보존 · `reviewed_at` · 조건부 UPDATE · **CU-1~CU-11** · **X-F9 `edited_text` 마감** · `ReviewedBody` 프로덕션 생성 지점 첫 등재 | **G-γ** |
| **C8** | `docs(kotlin): documents 단위 산출물` | `04_kotlin-implementer_documents.md` + 개선 백로그 | — |

**왜 이 순서인가.**

- **C1 이 먼저인 이유**: 가장 크고 HTTP 표면이 없다. 계약 케이스에 얽히지 않아 실패해도 원인이 섞이지 않는다.
- **C2 가 C3 앞인 이유**: `ContentCipher` 배선과 X9 를 HTTP 없이 먼저 증명한다. 여기서 실패하면
  C3 의 계약 케이스가 「저장이 안 되는」 이유로 무더기로 빨개져 원인 분리가 어려워진다.
- **C4 가 `validation` 도입 커밋인 이유**: 명세 §6 이 **「`spring-boot-starter-validation` 이 들어오는 그
  커밋」에 DL-5 의 `detail` 타입 단언**을 요구한다. DL-5 는 `GET /documents` 가 있어야 실행되므로
  **의존성 도입과 목록 구현이 같은 커밋**이어야 한다. C1 에 미리 넣으면 방벽이 사라진 채 세 커밋이 지나간다.
- **C6·C7 이 뒤인 이유**: 둘 다 저장된 행을 읽는다. C2 가 세운 저장 형식이 확정된 뒤가 싸다.

**게이트 덩어리** — G-α(도메인·저장, HTTP 0) / G-β(쓰기 계약) / G-γ(읽기·검수 계약).
셋으로 나누는 근거는 게이트 25 판정 ③ 이 지목한 것과 같다 — **리뷰 주기가 10커밋으로 늘어나면
그 사이에 들어온 것을 아무도 못 본다.** 한 덩어리로 몰지 않는다.

---

## 8. (f) 검증 계획 — 무엇이 무엇을 증명하고, 떼면 무엇이 깨지는가

### 8.1 층 배치 (계약 명세 §5 준수)

| 층 | 도구 | 이 단위에서 |
|---|---|---|
| **단위** | JUnit, Spring 없음 | 제목 유도 규칙 · 블록 결합 · 확장자 판별 · 길이 판정 |
| **파서 보안** | JUnit + fixture, Spring 없음 | §5 D-4~D-13·D-16. **힙 관측이 필요한 것은 전용 태스크에서 `-Xmx` 를 낮춰 돌린다**(spike S-8 의 방식) |
| **DB** | Testcontainers PostgreSQL | 트랜잭션 원자성 · 소유권 WHERE · FK CASCADE · CHECK · 보존 기본값 · 재암호화 4조건 · 조건부 UPDATE |
| **C-M** | `@WebMvcTest` + MockMvc | 422 모양 · 소유권 404 · 헤더 · `Location` 조립 |
| **C-R** | `@SpringBootTest(RANDOM_PORT)` + 실제 소켓 | **업로드 전부** · 401 · multipart 파싱 · 상한 초과 응답 |
| **C-I** | 위 + Testcontainers | 상태별 응답 모양 · 검수 저장 보존 · 삭제 후 조회 |
| **C-P** | 속성이 다른 컨텍스트 | 큐 미배선 503(DC-19) · 암호 키 미배선 503 |

**§5-1 을 어기지 않는다**: 업로드·다운로드 케이스를 MockMvc 로 쓰지 않는다. 컨테이너가 만드는
응답과 헤더 값 인코딩은 목으로 **재현되지 않으면서 통과한다.**

### 8.2 실 PostgreSQL 이 **필요한** 것

| 무엇 | 증명하는 것 |
|---|---|
| 업로드 저장 원자성 | 문서·변환이 함께 있거나 함께 없다. 중간에 예외를 던지는 변이로 확인 |
| `encryption_scheme`·`key_version` 명시 | 컬럼을 빠뜨린 INSERT 가 **NOT NULL 위반**으로 실패한다(`V3` 의 설계 의도) |
| `V4` CHECK | 도메인 밖 세대가 **DB 에서** 거부된다(이미 `ccc508e` 가 있다 — 새 INSERT 경로에도 적용되는지) |
| 소유권 WHERE | 없는 자원과 남의 자원의 **응답 바이트가 같다**(X-B2) + **`CountingDataSource` 로 문장 수 고정**(시간 축은 못 잡는다 — 그 KDoc 의 실측) |
| FK CASCADE | 문서 삭제가 변환을 함께 지운다(DD-5). 애플리케이션이 두 번 지우지 않는다 |
| 조건부 UPDATE(검수 저장) | 검사와 UPDATE 사이에 상태가 바뀌면 **같은 409** 가 난다 |
| 초안 보존 | `easy_text_encrypted` 가 검수 저장 후에도 같다(I-13) |
| 재암호화 4조건 | 단일 UPDATE(문장 수) · NULL 보존 · 부분 실패 시 무변화 · 낙관적 조건 |
| **X9 실제 키 왕복** | 조립된 빈이 실제 키로 저장·조회한다. **2세대 회전 포함** |
| 보존 만료 기본값 | `retention_expires_at` 이 DB 시계 기준 30일(계약 `x-input-limits.retention_days`) |

### 8.3 실 PostgreSQL 이 **필요 없는** 것

파서 보안 전건 · 제목 유도 · 블록 결합 · 길이·경계 판정 · `detail` 모양 · 헤더 선언 ·
계약 파서(P-22~P-36) · 음성 대조 N-23~N-28 중 계약 값 변이 · X2 타입 부재 단언.

### 8.4 항목별 「떼면 무엇이 깨지는가」

§5 의 표가 D-1~D-17 을, §6.5 가 X1 을 이미 적었다. 나머지는 다음과 같다.

| 장치 | 떼면 |
|---|---|
| `encryption_scheme`/`key_version` 명시 INSERT | NOT NULL 위반으로 **즉시** 실패(조용한 통과 없음) — 이것이 「장치」다 |
| `rewriteEnvelope` 단일 UPDATE | 열별 갱신 메서드를 추가하면 **문장 수 단언**이 빨강 |
| NULL 보존 | 빈 문자열을 암호화하면 「대기 중 변환 회전 후 3열 NULL」이 빨강 |
| 실패 시 전체 중단 | 부분 커밋을 허용하면 「한 열이 안 열릴 때 행이 무변화」가 빨강 |
| X9 실제 키 | 키를 빼면 업로드가 503 — 그 케이스를 **함께 두어** 「503 만 밟는 초록」과 구분한다 |
| X2 타입 부재 | DTO 에 `PlainBody` 를 넣으면 빨강 |
| `AuthenticatedEndpoints` 등재 | 계약이 보호로 선언한 경로를 빠뜨리면 `AuthenticationCoverageContractTest` 가 빨강 |
| `ProvenanceCreationSitesTest` | `ReviewedBody` 를 검수 어댑터 밖에서 만들면 빨강 |
| `MaskedTextGatewayTest` | 마스킹 선행 우회 |
| 사적 헤더 개별 단언(X-D1 4곳) | 전역 필터를 지워도 **개별 단언이 먼저** 빨강 |

### 8.5 계약 값 음성 대조 (N-20~N-30 중 이 단위 몫)

명세 §4-4 대로 **일회용 worktree**에서만 하고, 규칙 5 를 따라 `cp` 로 복원하지 않는다
(`git checkout --` + `shasum -a 256` 대조 + worktree 제거).

| # | 이 단위 | 비고 |
|---|---|---|
| N-23 `max_upload_bytes` 변경 → DC-12·DC-13 | **C3** | |
| N-24 `list_limit.max` 변경 → DL-5·DL-6 + P-35 대조 | **C4** | |
| N-25 `fields[?text].measured_on` 변경 → **DC-11 만**(CU-6 은 깨지지 않아야) | **C3**(대비 쌍은 C7) | 두 축이 한 값으로 뭉개졌는지 재는 자리 |
| N-26 `MaskedItemResponse.category.enum` 원소 제거 → CR-5 | **C6** | |
| N-27 `ConversionResponse.required` 키 제거 → CR-1·CU-1 | **C6·C7** | |
| N-28 `/conversions/{id}` 경로 템플릿 변경 → **DC-2** + 그 경로를 쓰는 전건 | **C3** | |
| N-20·N-21·N-22·N-29·N-30 | **`export` 단위** | 범위 밖 |

**과잉 결합도 함께 본다** — 각 변이에서 깨지는 것이 표의 케이스뿐인가.

### 8.6 표 18 — TRACE 로거 3종을 **탐지형**으로

원장 조건 18 의 마감이 「Phase 4 문서 본문 진입 전」이다.

- **이름 3개를 `application.yml` 에 고정하지 않는다.** 그것은 열거이고, 다음 라이브러리에서
  같은 빈자리가 재발한다(`CLAUDE.md` 규칙 4 — 스캐너 403 이 정확히 그 형태로 두 번 실패했다).
- **탐지로 간다**: 로그 레벨을 강제 TRACE 로 올린 컨텍스트에서 **합성 카나리 문자열**을 담은
  요청을 한 번 돌리고, 캡처한 **전체 로그**에 카나리가 0건인지 본다. 카나리는 본문·제목·
  자격증명 축으로 나눈다.
- 이 장치는 로거 이름을 모른다 — **그래서 다음 로거가 늘어도 같이 잡는다.**
- 음성 대조: 억제 설정을 빼면 이 테스트가 빨강이어야 한다. 빨강이 되지 않으면 **카나리가
  그 경로를 지나지 않는 것**이므로 테스트를 고친다(측정한 것처럼 보이는 통과의 전형).

### 8.7 게이트 명령 (모두 실행하고 결과를 산출물에 적는다. 미실행은 「미실행」으로 적는다)

| 검사 | 명령 |
|---|---|
| Kotlin | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` (warning 0 — `allWarningsAsErrors`) |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` |
| parity | `./gradlew parityHarness` (도메인을 건드리면) |
| 개인정보 스캐너 | `uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` |
| Python(무변경 확인) | `uv run ruff check .` · `uv run mypy . .claude` · `uv run pytest` |
| 골든셋 | 프롬프트·스타일 규칙을 건드리지 않으므로 **해당 없음**(건드리면 `uv run pytest tests/golden`) |

---

## 9. (g) 모르는 것 · 리더 판정이 필요한 것

**추측으로 메우지 않는다.** 아래는 전부 이 계획이 **답을 갖고 있지 않은** 자리다.

| # | 질문 | 왜 지금 물어야 하는가 | 이 계획의 잠정 전제 |
|---|---|---|---|
| **①** | **작업 단위 경계 — `#9 export` 가 이 단위인가 별 단위인가.** 계약 명세는 77 케이스를 **한 배치**(#4~#9)로 묶었고 test-plan §5 도 Phase 4 행에 #4~#9 를 넣었는데, **원장 Phase 4 표는 「내보내기」 행의 blocked-by 를 `export 단위`로 갈랐다** | 명세 §6 이 「§4 의 파서 P-22~P-36 + N-20~N-30 을 **이 단위의 첫 계약 테스트 커밋**에」라고 요구하는데, P-23·P-28·P-29·P-30 은 export 전용이다. 경계가 안 정해지면 그 요구를 어느 커밋이 지는지 알 수 없다 | **별 단위**로 잡았다(§0). export 전용 파서 노드는 그쪽 첫 계약 커밋 몫 |
| **②** | **큐 등록이 같은 트랜잭션에 들어가면 계약의 502 갈래가 사라진다.** 계약 `POST /documents` description 은 *"큐 등록에 실패하면 이미 커밋된 변환을 `failure_code = "EnqueueFailed"` 로 표시한 뒤 502"* 라고 적었고 이는 **Redis/ARQ 전제**다. PostgreSQL lease 큐로 옮기면 등록이 **같은 DB·같은 트랜잭션**이라 「저장은 됐는데 등록은 실패」가 **구조적으로 성립하지 않는다** | DC-18 이 그 상태를 재현해야 하는데 재현 대상이 없어진다. 계약 개선 사유일 수 있고 그것은 `contract-keeper` 판정이다 | ~~판정 전까지 계약대로 — 등록을 커밋 이후 별도 트랜잭션으로~~ **폐기(2026-08-20 리더 지시).** 등록을 **같은 트랜잭션**에 둔다. 502 갈래를 만들지 않으며, 계약 조항의 처분은 계약 레인이 C3 에서 한 변경 단위로 처리한다(§9.2-bis D-m) |
| **③** | **`x-open-asymmetry` 판정**(명세 §7-2) + **게이트 15 X13**(`password` 측정 축) | 판정이 (나)로 나면 **DC-11 의 기대값이 뒤집히고** `text` 의 정규화 자리가 바뀐다. C3 을 다시 열어야 한다 | **현행 (가)** — 두 필드의 측정 축을 **합치지 않는다**(명세 §8 통보 ⑹) |
| **④** | **X1 거부 문구가 계약에 없다**(§6.3). 조항을 더할 것인가, 기존 갈래(추출 실패·빈 본문)에 흡수할 것인가 | 리더 판정 ② 가 「거부」로 정했고 §4-㉑ 이 「사용자가 422 를 받는다」를 이미 인정했다. 그런데 그 422 의 `detail` 이 계약 어디에도 없다 | **전용 문구로 거부**하고 그 사실을 산출물에 표시. 조항 추가는 `contract-keeper` 요청 |
| **⑤** | **K5 — P-22 식별자 충돌 해소 시점** | 마감이 「문서 API 착수 전」이고 C3 의 선결이다. 해소 전에 C3 을 쓰면 파서 노드 번호가 두 뜻을 갖는다 | C3 을 시작하지 않는다 |
| **⑥** | **`migrate` 프로필도 암호화 키를 요구하게 됐다**(crypto-fixes §4-①). 뒤집을 것인가 | 이 단위가 compose 스모크를 돌리는 시점(P4)에 실제로 걸린다 | **현행 유지** — 뒤집으려면 지금이 싸다 |
| **⑦** | **키 회전 배치의 호출자가 이 단위인가.** §4.3 은 포트·구현·테스트까지만 계획했다 | 부르는 코드가 없으면 4조건은 「테스트만 부르는 함수」의 성질이 된다 | 이 단위 밖으로 두었다 |
| **⑧** | **실문서 fixture 확보**(spike §7-5). 한컴/Word 로 저장한 실제 파일과 실제 공공기관 PDF | 없으면 「문서 추출·내보내기 주요 fixture 불일치」(§5 Phase 7 즉시 중단 기준)를 절체 전에 검출할 방법이 없다. **hwpx 생성물의 한컴 호환성은 Python 시절에도 미검증**이었고 자동 보장은 「우리 추출기 왕복」까지다 | 합성 fixture 로 진행하고 **한계를 산출물에 선언**한다(DOC-02) |
| **⑨** | **parity 도메인 신설(`ingest`) 여부.** 오늘 `parity/fixtures/` 에 문서 추출 도메인이 없고, `dump_parity_fixtures.py` 의 `BUILDERS` 8개에도 없다 | DOC-01 의 「차분 비교(불일치는 분류, `미확인` 0)」를 어디서 판정할지가 정해지지 않는다. fixture 형식 변경은 `parity-verifier` 와 **합의 후에만** 한다 | 신설하지 않고 `doc-spike` oracle 을 **Kotlin test resources 안의 참고값**으로 쓴다. 도메인 신설은 그 레인에 요청 |
| **⑩** | **DOC-02(조용한 누락 금지)의 판정 장치.** 계획 §4.5 는 「지원 한계로 문서화하거나 실패로 알린다」인데 둘 중 어느 쪽인지는 요소마다 다르다 | 「문서화했다」는 자동 게이트가 아니다. 판정 방법이 없으면 이 요구는 영영 미충족으로 남는다 | 걷지 않는 요소(`even`/`first` 머리글·각주·미주·주석)를 **코드의 선언 상수**로 두고 그 목록이 산출물·테스트와 일치하는지 대조하는 방식을 제안한다 |
| **⑪** | **`_read_hwpx_sections` 의 비대칭**(Python 실측: 디스패치 예산은 64KB 청크인데 구역 읽기만 `read(budget+1)` 한 번 — 구역 하나가 수십 MB 를 한 번에 할당할 수 있다) | 그대로 옮기면 같은 구멍을 물려받는다. **고치면 Python 과 동작이 갈린다**(그 자체는 문제가 아니다 — 기준은 요구사항이다) | **고친다** — 두 자리 모두 청크 읽기. I-10 이 요구하는 성질은 「실제 읽은 바이트로 센다」이지 「Python 과 같다」가 아니다. 이 판단을 산출물에 기록 |
| **⑫** | **`mc:Fallback` 스킵이 네임스페이스 무관**(Python 실측: 로컬 이름 `"Fallback"` 이면 **모든** `*:Fallback` 이 잘린다) | DOC-01 「누락 없이 추출」과 충돌할 수 있다 | 네임스페이스를 확인하도록 **좁힌다**. 근거: 목적은 `mc:AlternateContent` 의 이중 수집 방지 하나이고, 그 범위를 넘는 절단은 조용한 누락이다. 이 판단도 산출물에 기록 |

### 9.1 확인은 됐으나 판단이 갈릴 수 있어 적어 두는 것

- **`iter_pdf_pages`·`extract_pdf_range`·`iter_hwpx_sections` 는 documents 경로가 쓰지 않는다**
  (Python 실측: 유일한 소비자가 골든셋 수집 스크립트). **이 단위에서 포팅하지 않는다** —
  §5 Phase 9 가 「독립 검증 oracle 역할을 하는 Python 골든 도구는 남겨 둔다」고 했고, 그 도구가
  Kotlin 으로 옮겨질 때 함께 필요해진다.
- **업로드 경로에 마스킹이 없다.** Python `app/services/documents.py` 는 `mask_text` 를 import 하지
  않는다 — 마스킹은 워커(Phase 5)의 일이다. **이 단위가 지키는 선행 불변식은 「암호화 선행」**이고
  「마스킹 선행」의 대상은 Phase 5 다. 게이트 문구를 이 구분에 맞춰 읽어야 한다.
- **`GET /documents` 정렬은 `created_at DESC, id DESC`** 여야 한다(Python 근거: `created_at` 이
  트랜잭션 시각이라 동률이면 페이지 경계에서 중복·누락이 난다). `has_more` 는 `limit+1` 로
  판정하고 전수 COUNT 를 하지 않는다(계약 `DocumentListResponse` 가 총 개수를 싣지 않는 이유).
- **목록에 `workspace_id` 를 줘도 `user_id` 조건을 그대로 남긴다** — 소유자 판정을 작업 공간
  소유 여부에 의존시키면 작업 공간 검사를 빠뜨린 호출 하나가 곧바로 남의 문서 노출이 된다.
- **`reviewed_at` 은 DB 시계**(`now()`)로 UPDATE 문 안에서 찍는다 — 애플리케이션 시계는 프로세스마다
  어긋나고 검수 시각은 집계 기준값이다. 목록의 「검수함/초안」 표시도 `status` 가 아니라
  `reviewed_at` 을 본다(`done` 은 「AI 변환이 끝났다」는 뜻일 뿐이다).
- **`IntegrityError` 계열을 SQLSTATE 로 분기하지 않는다.** PostgreSQL 은 제약 위반 DETAIL 에
  **실패한 행 전체(= 암호문·제목)** 를 담는다. 로그에는 SQLSTATE 만 남긴다(I-4).

---

## 9.2 구현하며 이 계획에서 **벗어난 지점** (2026-08-20, C1 착수 중 추가)

> 규율: *"계획에서 벗어나야 할 사정이 생기면 **계획 문서를 먼저 고치고 사유를 적은 뒤** 진행한다."*
> 아래 여섯은 그 절차로 기록한 것이고, 코드는 이 표대로 들어갔다.

| # | 계획이 적었던 것 | 실제로 한 것 | 사유 (실측) |
|---|---|---|---|
| **D-a** | §1.3 — commons-compress **1.27.1** · commons-io **2.18.0**(spike 조합) 고정 | **1.28.0 / 2.20.0** 고정 | 1.27.1/2.18.0 을 넣고 락을 뜨니 **compileClasspath 와 testClasspath 가 갈렸다**(`testcontainers:2.0.5` → `commons-compress:1.28.0` → `commons-io:2.20.0`, 충돌 해소가 높은 쪽을 고른다). 그대로 두면 **파서 테스트가 배포본과 다른 라이브러리를 시험한다** — zip 경계 읽기처럼 바이트가 곧 결과인 자리에서 최악이다. `strictly` 로 끌어내리는 갈래는 검증되지 않은 새 조합(testcontainers 2.0.5 + compress 1.27.1)을 만들 뿐이라 고르지 않았다. spike 가 준 것은 **동작 검증**이고 그 검증은 이 커밋의 추출기 테스트가 **새 조합 위에서 다시 돈다.** 전문은 `libs.versions.toml` 주석 |
| **D-b** | §5 **D-6** 행 — hwpx DTD 를 「`XMLStreamConstants.DTD` 이벤트를 받아 우리 예외」로 | **`SUPPORT_DTD=false`** (§1.5 지점 1 그대로) | §5 표가 **§1.5 이전 판의 처방을 그대로 들고 있었다**(계획 자신의 내부 불일치). §1.5 가 공식 문서 근거로 뒤집은 결론이 이기고, 리더 지시도 「되돌리지 마라」였다. **이 행을 §1.5 에 맞춰 아래에서 고친다** |
| **D-c** | §5 **D-9** 행 — PDF 메모리 상한을 「`Loader.loadPDF` 의 메모리/스트림 캐시 설정(Q-3)」으로 | **그런 설정은 없다.** 앱 책임 셋(업로드 바이트 상한 · 추출 길이 상한 · 동시 추출 제한)으로 대체하고 그 사실을 `PdfExtractor` KDoc 에 적었다 | 같은 종류의 잔재다 — §1.5 지점 3 ⑵ 가 이미 「읽기 측 상한 API 없음」을 확정했는데 §5 표가 옛 문장을 들고 있었다 |
| **D-d** | §5 **D-8** 음성 대조 — 「설정 제거 → docx zip bomb fixture 통과」 | **성립하지 않는다.** `ZipBudget` 이 POI 를 부르기 전에 끊으므로 POI 설정을 지워도 폭탄은 거부된다. **구조 단언**(설정이 실제로 걸려 있는가)으로 바꾸고 그 한계를 `PoiZipDefenses` KDoc 과 `IngestDefensesTest` 에 명시했다 | 방어가 둘 겹친 자리에서 뒤쪽 방어의 행동 음성 대조는 앞쪽 방어에 가려진다. 「음성 대조가 된다」고 적어 두면 그것이 곧 **재지 않은 초록**이다 |
| **D-e** | §2.2 — `core/document/SourceFormat` 은 **C2** 목록 | **C1 에서 만들었다** | C1 의 포트(`DocumentTextExtractor`)가 형식을 함께 돌려주고, 디스패치가 지원 형식 집합을 물어본다. 뒤로 미루면 C1 이 형식을 문자열로 다뤄야 한다 |
| **D-f** | §7.2 — **표 18 TRACE 카나리 회귀**가 C1 | **C1 에 넣지 않았다. 이 단위 안, C3 이전으로 옮긴다** | 원장이 정한 마감은 「**Phase 4 문서 본문 진입 전**」이고 **C1 은 HTTP 표면을 만들지 않는다** — 문서 본문이 들어오는 것은 C3 이다. C1 에 넣으면 카나리가 지날 경로가 기존 인증·작업 공간 요청뿐이라 「문서 본문」축이 비고, 그 상태로 조건 18 을 닫으면 **닫힌 것처럼 보이는 미도달**이 된다. **리더 확인 필요** — 마감 해석을 이렇게 잡아도 되는지 |

### 9.2-bis C2(저장 경로)에서 벗어난 지점 (2026-08-20)

> 같은 규율이다 — **계획을 먼저 고치고 사유를 적은 뒤** 코드가 그대로 들어갔다.

| # | 계획이 적었던 것 | 실제로 한 것 | 사유 (실측) |
|---|---|---|---|
| **D-g** | §7.2 — **D-1~D-3 을 C3** 에 | **D-1 의 L1(서비스 층 바이트 상한 판정)만 C2 로 당겼다.** L0(multipart 설정)·D-2(예외 매핑)·D-3(초과분 삼키기)는 C3 그대로 | §4.2 가 못박은 검사 순서의 **첫 단계**가 파일 크기 판정이다. 유스케이스에서 그것을 빼면 「크기 → 추출 → 길이 → 소유권」 순서를 구현할 수 없고, C3 이 서비스를 다시 열어 순서를 끼워 넣게 된다. L1 은 HTTP 표면이 아니라 리더 지시(「C2 는 HTTP 표면이 없다」)와 충돌하지 않는다 |
| **D-h** | §4.4-5 — *"키를 빼면 업로드가 503 이 되는 케이스를 별도 컨텍스트(C-P)로 둔다"* | **조립 경로에서 그 갈래에 도달할 수 없다.** 두 케이스로 나눴다 — ⑴ 「키 없는 컨텍스트는 **기동을 거부한다**」(`DocumentStorageContextTest`) ⑵ 「쓰기 키가 없는 cipher 로 업로드하면 `ConfigurationException`(→503)이고 아무 행도 남지 않는다」(`JdbcDocumentStoreTest`) | 게이트 26 조치 1 이 자기점검 우회 스위치를 없애면서 **키 없는 Spring 컨텍스트는 뜨지 않는다.** 뜨지 않는 컨텍스트에는 업로드를 시킬 빈이 없다 — 계획이 쓰인 시점의 전제(스위치가 있어 컨텍스트가 뜬다)가 사라졌다. 「503 을 낸다」 자체는 살아 있으므로 그 갈래를 빈 층에서 잰다 |
| **D-i** | §2.2 — `core/document/Conversion` (계약 `ConversionResponse` 대응) | **6필드로 좁혔다** — `id`·`documentId`·`status`·`failureCode`·`createdAt`·`updatedAt`. `missing_placeholders`·`provider_name`·`model`·토큰 수·`reviewed_at` 은 **변환 조회 커밋(C6)** 이 더한다 | 그 필드를 **읽는 코드가 이 커밋에 없다.** 미리 담으면 아무도 시험하지 않는 매핑이 생기고, 틀렸다는 사실은 그것을 처음 쓰는 커밋에서야 드러난다. 부수적으로 detekt `LongParameterList`(생성자 상한 6, 실측)에도 걸렸다 |
| **D-j** | — | `Document` 에서 **`workspaceId` 를 뺐다** | 계약의 어떤 응답(`DocumentCreatedResponse`·`DocumentListItem`)에도 작업 공간 식별자가 없다. 응답에 실리지 않는 값을 타입에 담으면 실수로 실릴 자리가 생긴다(`Workspace` 가 소유자를 담지 않는 것과 같은 규칙) |
| **D-k** | §4.2 — 유스케이스가 저장소 셋을 각각 받는다 | **`DocumentStorage`(문서·변환·큐) 묶음을 신설**하고 유스케이스가 그것을 받는다 | detekt `LongParameterList` 가 먼저 울렸고, 그 신호가 가리킨 것이 실제로 **함께 다뤄야 할 것들이 흩어져 있다**는 사실이었다(셋은 같은 트랜잭션에서 함께 성공·실패해야 한다). 임계값을 늘리는 대신 구조를 바꿨다 |
| **D-l** | §4.2 — 작업 공간 소유 조회를 `JdbcWorkspaceRepository` 가 겸한다(암묵) | **`JdbcWorkspaceLookup` 을 별도 클래스로** 만들었다 | 한 구상 클래스가 두 포트를 겸하게 하자 **같은 타입의 빈이 둘**이 되어 `WorkspaceRepository`·`WorkspaceLookup` 어느 쪽 주입도 모호해졌다(실측: `api`·`worker` 기동 테스트 전건 빨강 — `NoUniqueBeanDefinitionException`). 포트 하나당 구상 클래스 하나로 간다 |
| **D-m** | §9 질문 ② — *"판정 전까지 계약대로: 등록을 커밋 **이후** 별도 트랜잭션으로 두어 502 갈래를 실물로 남긴다"* | **같은 트랜잭션에 두었다.** 502 갈래를 만들지 않았다 | **리더 지시**(2026-08-20, 판정 L-1 의 전제). 계약 `EnqueueFailed`/502 조항의 처분은 계약 레인이 C3 에서 계약·구현·테스트를 한 변경 단위로 묶어 처리한다. 이 계획의 잠정 전제(원자성을 일부러 버림)는 **폐기**한다 |
| **D-n** | — | 문자 수를 **코드 포인트**로 센다(`charCountOf`) — `String.length`(UTF-16 코드 단위)가 아니다. 제목 자르기도 코드 포인트 경계를 지킨다 | 코드 단위로 자르면 **서로게이트 쌍 한가운데를 끊을 수 있고**, 그 결과는 짝 없는 서로게이트다(게이트 25 X1 이 다룬 손상과 같은 것). 제목은 암호화 경로를 지나지 않아 `PlainBody` 검사도 받지 못하므로 자르는 자리에서 막지 않으면 아무 데서도 막히지 않는다 |

**위 D-b·D-c 에 따라 §5 표의 두 행을 고친다** (원문을 지우지 않고 무엇이 왜 바뀌었는지 남긴다):

- **D-6 (개정)** — 층 **L3** / 장치 **`XMLInputFactory` 3속성 명시(`SUPPORT_DTD=false` ·
  `IS_SUPPORTING_EXTERNAL_ENTITIES=false` · `ACCESS_EXTERNAL_DTD=""`)** / 왜 그 층인가: OWASP 의
  StAX 1차 통제이고, 파서가 DOCTYPE 을 만나는 즉시 끊으므로 엔터티 확장이 시작되지 않는다 /
  음성 대조: **세 속성 중 하나라도 빼면** billion laughs(UTF-8·UTF-16) fixture 가 통과한다.
  **잃는 것**: DTD 폭탄과 손상 파일의 사용자 문구가 같아진다(§1.5 지점 1 이 이미 적은 갈림).
- **D-9 (개정)** — 층 **L1** / 장치 **없음(라이브러리 API 부재)**. 실제 방어는 업로드 바이트
  상한(D-1) · 추출 길이 상한(D-4) · 동시 추출 제한(D-14) 셋이고, `PdfExtractor` KDoc 이 그
  사실을 적는다 / 음성 대조: D-4·D-14 의 것을 쓴다(D-9 고유의 것은 없다).

---

## 9.2-ter 게이트 27 — §4.3 이 **틀렸다**: 낙관적 조건은 회전-대-회전만 막는다 (2026-08-20)

> 같은 규율이다 — **계획을 먼저 고치고 사유를 적은 뒤** 코드가 들어갔다. 이번 것은 「계획에서
> 벗어났다」가 아니라 **계획이 적은 근거 자체가 실측으로 반증됐다**는 기록이다.

### 무엇이 반증됐나

§4.3 표의 마지막 줄은 이렇게 적혀 있었다.

> (덤) **동시 회전** | `WHERE key_version = :expected` — 두 프로세스가 같은 행을 잡으면
> 뒤엣것이 0행을 갱신하고 재시도한다 | 잠금 없이 경합을 안전하게 만든다

**「잠금 없이 경합을 안전하게」가 참이 아니다.** 그 조건이 막는 것은 **회전끼리**뿐이다.
`key_version` 은 회전만 바꾸는 열이라, 암호문 열을 쓰는 트랜잭션은 이 값을 건드리지 않는다.
그래서 아래 순서가 성립한다(PostgreSQL 기본 READ COMMITTED).

1. 회전 T1 이 SELECT — v1 암호문 셋을 읽는다
2. 내용 쓰기 T2 가 암호문 열을 새 값으로 UPDATE 하고 **커밋**한다. `key_version` 은 그대로 v1
3. T1 이 UPDATE — `key_version = 1` 이 여전히 참이라 조건을 통과하고, 1단계에서 읽은 **낡은
   값**으로 세 열을 통째로 덮는다
4. T1 은 `ROTATED` 를 돌려준다. **T2 의 쓰기가 아무 신호 없이 사라진다**

3단계의 근거는 PostgreSQL 16 문서 13.2.1 이다 — 잠금을 얻은 뒤 `WHERE` 를 갱신된 행 버전으로
다시 평가하고, *"If so, the second updater proceeds with its operation using the updated
version of the row."* 조건이 `key_version` 하나뿐이면 그 재평가는 **언제나 통과**한다.

**실측(수정 전 HEAD `6515548`)** — `EnvelopeRotationConcurrencyTest` 3건 전부 빨강:

| 케이스 | 기대 | 실제 |
|---|---|---|
| 회전 중 검수 저장 | `edited_text_encrypted` 가 남는다 | **NULL** (회전 결과 `ROTATED`, 행 세대 v2) |
| 잠금 전제가 깨진 상태 | `CONTENDED` | **`ROTATED`** — 사라진 쓰기가 어디에도 드러나지 않는다 |
| 문서 원문 동시 쓰기 | `"다시 쓴 원문"` | **`"원문 본문"`**(낡은 값) |

기존 「낙관적 조건」 케이스(`JdbcDocumentStoreTest`)가 이것을 못 잡은 이유: 그 케이스는 **이미
회전된 행을 옛 세대로 다시 쓰면 0행**을 재는 것이라 회전-대-회전 축에만 서 있다.

> **오늘 그런 쓰기 경로가 없다는 것은 면책이 아니다.** Phase 5 워커(초안·마스킹 대응표)와
> C7(검수 저장)이 곧 만들고, 그때 이 포트 형태가 전제로 깔린다.

### 무엇으로 닫았나 — 후보 셋의 대가와 선택

| 후보 | 닫히는가 | 대가 | 채택 |
|---|---|---|---|
| **(가) 회전의 SELECT 를 행 잠금으로** | 예. 잠금이 서면 다른 트랜잭션은 회전이 커밋할 때까지 그 행을 **쓰지 못한다** | 회전이 그 행의 쓰기를 잠그므로 사용자 요청이 회전 뒤에 줄 선다. 회전 트랜잭션은 복호·재암호(메모리 연산)만 품으므로 구간이 짧다 | **채택** |
| **(나) 낙관적 조건에 암호문을 포함** | 예. 내용이 바뀌면 조건이 깨져 0행 → `CONTENDED` | 바이트 비교 비용, 조건이 길어짐 | **함께 채택** — 이유는 아래 |
| (다) 행 버전 열 신설 | 예 | `V6` 가 필요하고 **모든** 쓰기가 그 열을 올려야 해서 강제자가 또 필요하다. 스키마 변경 없이 닫을 수 있는 것을 스키마로 닫지 않는다 | 기각 |

**(가)만으로는 부족한 이유 — (나)를 함께 넣은 근거.** (가)는 잠금이 **실제로 서 있을 때만**
참이다. 트랜잭션이 열리지 않으면(자동 커밋) `SELECT … FOR NO KEY UPDATE` 의 잠금은 문장이
끝나는 순간 풀리고, 그 상태에서 (가)는 아무것도 막지 않으며 `key_version` 하나짜리 조건은
**그 사실을 알려 주지도 않는다.** (나)는 그 상태를 조용한 덮어쓰기가 아니라 `CONTENDED` 로
드러낸다 — 즉 **(나)는 (가)의 전제가 깨졌음을 알리는 fail-closed 카나리**다. 두 번째 테스트
케이스(`잠금이 서지 않으면 CONTENDED 다`)가 (나)를 실행으로 밟는다. 하나만 넣으면 그 케이스가
설 자리가 없다.

**잠금 모드는 `FOR NO KEY UPDATE` 다(`FOR UPDATE` 가 아니다).** PostgreSQL 16 문서 13.3
표 13.3 의 충돌표에서 `FOR UPDATE` 는 `FOR KEY SHARE` 와도 충돌하는데, 그것은 **이 행을
참조하는 외래 키 검사가 잡는 잠금**이다(`conversion_jobs.conversion_id` →
`conversions.id`, `conversions.document_id` → `documents.id`). 회전은 키 열(`id`)을 바꾸지도
행을 지우지도 않으므로 그만큼 강한 잠금이 필요 없고, `FOR NO KEY UPDATE` 는 평범한 UPDATE·
DELETE·`FOR UPDATE`·`FOR SHARE`·다른 `FOR NO KEY UPDATE` 와는 그대로 충돌한다. 저장소 안의
선례(`JdbcWorkspaceRepository.lockForDeletion`)가 `FOR UPDATE` 인 것은 그쪽이 **DELETE 를**
하기 때문이라 같은 자리가 아니다.

**`CONTENDED` 의 뜻이 바뀐다.** 잠금이 경합을 직렬화하므로 「다른 프로세스가 먼저 회전했다」는
사정은 이제 `CONTENDED` 가 아니라 **`ALREADY_CURRENT`** 로 나온다 — 두 번째 회전은 자기
`SELECT … FOR NO KEY UPDATE` 에서 기다렸다가 갱신된 행(v2)을 받기 때문이다. 남은 `CONTENDED`
는 **「우리가 잠근 채 읽은 그 행이 쓰기 시점에 그대로가 아니다」**, 즉 잠금 전제가 성립하지
않았다는 신호다. 그 뜻을 `RotationOutcome.CONTENDED` 와 두 포트 KDoc 에 적었다.

### D-o ~ D-r (이번 커밋이 계획 대비 바꾼 것)

| # | 계획이 적었던 것 | 실제로 한 것 | 사유 |
|---|---|---|---|
| **D-o** | §4.3 표 — 「(덤) 동시 회전 … 잠금 없이 경합을 안전하게 만든다」 | **삭제·대체.** 회전의 읽기가 `FOR NO KEY UPDATE` 로 행을 잠그고, 낙관적 조건이 봉투 두 값 **+ 암호문 전부**를 본다 | 위 실측 3건. 「잠금 없이」는 회전-대-회전에만 참이었다 |
| **D-p** | §4.3 코드 스케치 — `rewriteEnvelope(conversionId, expectedKeyVersion: Int, …)` | **`rewriteEnvelope(expected: ConversionEnvelope, …)`** / 문서 쪽은 `rewriteEnvelope(documentId, expected: EncryptedContent, …)`. 읽어 온 행 **그 자체**가 쓰기 조건이다 | 정수 하나를 조건으로 넘기면 「무엇과 비교하는가」를 호출자가 고를 수 있고, 실제로 그 자유가 이 결함이었다. 읽은 행을 통째로 넘기면 **조건을 좁게 쓰는 갈래가 없다** |
| **D-q** | 포트 이름 `loadEnvelope` / `loadSourceText` | **`lockEnvelope` / `lockSourceText`** | 이 읽기는 이제 부수 효과(행 잠금)가 있고 **트랜잭션 안에서만** 뜻이 있다. `load` 는 그것을 감춘다 |
| **D-r** | (없음) — §4.3 이 「열 하나짜리 갱신 메서드를 만들지 않는다」를 **산문으로만** 두었다 | **탐지형 장치 신설** — `EnvelopeColumnWriteGuardTest` 가 소스 전수에서 `documents`·`conversions` 를 UPDATE 하는 SQL 을 뽑아, 암호문 열을 SET 하는 문장이 `encryption_scheme`·`key_version` 도 함께 SET 하는지 단언한다 | 그 산문을 지키던 것은 **「지금 그런 메서드가 없다」는 사실뿐이고 탐지기가 0개**였다. Phase 5 워커가 `updateEasyText(id, bytes)` 를 더하는 순간 v1 로 라벨된 행에 v2 암호문이 들어가고 그 행은 영원히 열리지 않는다(AAD 에 세대가 실린다) |

**탐지기의 분모와 그 근거.** 감시 대상 열·테이블을 손으로 열거하지 않는다 —
`EncryptedField` 의 `wireName`(`테이블.컬럼`)에서 **파생**한다. 열거하면 다섯 번째 암호문 열이
생겼을 때 그 열만 영영 탐지 밖에 남는다(`ProvenanceCreationSitesTest.WATCHED_TYPES` 가 같은
이유로 소스와 대조하는 것과 같은 구조).

**빈 분모 실패**: 대상 문장을 하나도 못 찾으면 초록이 아니라 **빨강**이다. 저장소의 parity
게이트가 「선언 도메인 0개에서 exit 0」이었던 것이 정확히 이 결함이라 같은 자리를 만들지 않는다.
`Scanner.requireNonEmpty` 가 그 판정이고, `빈 분모는 통과가 아니다` 케이스가 ⑴ 실제 분모
(파일 3개 · 문장 5건)를 못박고 ⑵ **빈 목록을 주면 실제로 끊기는지**를 실행으로 확인한다.

### 음성 대조 실측 (2026-08-20)

| 무엇을 뗐나 / 심었나 | 무엇이 깨졌나 | 종료 코드 |
|---|---|---|
| **①의 수정 전(HEAD `6515548`)에서 `EnvelopeRotationConcurrencyTest` 실행** | 3/3 빨강. `edited_text_encrypted` 가 NULL(회전 결과 `ROTATED`) · 잠금 카나리가 `ROTATED` · 문서 원문이 낡은 값 | `1` |
| **②의 위반 SQL 주입** — 리더가 이름을 댄 그 메서드 그대로 `JdbcConversionRepository` 에 `updateEasyText(conversionId, bytes)`(`SET easy_text_encrypted = :easyText` 만) 를 더함 | `EnvelopeColumnWriteGuardTest` 6건 중 **2건 빨강**. ⒜ 「봉투를 함께 쓴다」가 위반 문장을 파일·SET 절과 함께 지목 ⒝ 「문장 수 5 → 6」이 인구조사에서 어긋남 | `1` |
| **②의 장치를 뗀 상태 = 새 테스트 클래스를 선언하지 않은 상태** (`tests/test_kotlin_gate_reach.py` 핀 갱신 전) | `test_선언한_테스트_클래스와_트리에서_발견한_것이_정확히_일치한다` · `test_리포트에_나온_클래스는_전부_선언에_있다` 2건 빨강 — 파일을 지우면 반대 방향으로 같은 두 케이스가 운다 | `1` |

주입 복원은 `cp` 가 아니라 **본문 절제 + sha256 대조**로 했다(규칙 5). 주입 전
`8f17fe23cc0982706f5452b71f30e07cdaff95fe16e4702a37f238097ccd6aac` = 복원 후 동일,
`updateEasyText` 잔존 0건.

**②의 스캐너가 막지 못하는 것**(장치 KDoc 에 같은 목록이 있다): 문자열을 조립해 만든
SQL(`"UPDATE ${'$'}table SET …"`) · 저장 프로시저와 DB 클라이언트에서 직접 친 UPDATE ·
이 파일 자신의 삭제. 첫 항목은 **이 파일의 합성 probe 가 쓰는 통로이기도 하다** — 그래서
probe 의 위반 SQL 이 실제 스캔의 분모를 오염시키지 않는다(리터럴로 적었더니 실제로 오염됐고,
그 실측이 이 설계의 근거다). 마지막 항목의 방어선은 위 표 셋째 줄의 선언 대조다.

### `updated_at` — 건드리지 않는 판단을 **유지한다**

§4.3·포트 KDoc 이 *"재암호화는 내용의 변경이 아니다"* 로 `conversions.updated_at` 을 건드리지
않기로 했다. 이번 수정이 그 판단을 흔들 수 있었던 자리는 하나다 — `updated_at` 을 경합 토큰
(낙관적 조건)으로 쓰는 갈래. **쓰지 않기로 했다.** 사유 셋:

1. `updated_at` 은 **대리 지표**다. 지켜야 하는 것은 「우리가 읽은 암호문이 그대로인가」이고,
   그것을 직접 조건에 넣을 수 있는데 대리 지표를 고를 이유가 없다. 대리 지표는 쓰기가
   깜빡하면 조용히 무력해진다 — 그리고 지금 그 열을 **자동으로 올려 주는 장치가 없다**
   (트리거 없음, `DEFAULT now()` 는 INSERT 에만 걸린다).
2. `documents` 에는 **`updated_at` 열 자체가 없다**(V1 baseline). 문서 쪽에 같은 기법을 쓰려면
   스키마를 넓혀야 하고, 그것은 §10 이 배제한 「추가 마이그레이션」이다.
3. 회전이 `updated_at` 을 밀면 워커·화면이 읽는 「이 변환에 무슨 일이 있었나」가 배치 시각으로
   덮인다. 원래 사유가 그대로 살아 있다.

---

## 10. 이 계획이 만들지 **않는** 것

- 품질 개선(프롬프트 문구·어려운 말 사전·스타일 규칙 조정) — 전환 범위 밖.
- 계약 변경 — 읽기만 한다. 필요한 변경은 `contract-keeper` 에 근거를 붙여 올린다.
- 스키마 재설계 — `V5` 는 **additive**(새 테이블)만이고 기존 테이블·컬럼·제약 이름을 바꾸지 않는다.
- `app/**` 수정 — 폐기 대상이지만 fixture 참고값의 출처이고, 삭제는 Phase 8 의 게이트 뒤다.
- 스스로 parity 통과 선언 — 판정은 `parity-verifier` 가 한다.
