# 게이트 27 · 1단계 · codex 독립 리뷰 — `04_documents`

> 이 파일은 `codex-reviewer` 가 쓴 **1단계 산출물**이다. 3단계 교차 종합(`04_documents_cross.md`)의 입력 하나이며,
> 나머지 입력은 같은 시각 독립으로 도는 `04_documents_migration-reviewer.md` 다.
> **이 회차에 다른 리뷰어의 산출물을 열지 않았다** — 교차 검증의 독립성을 위해서다.
>
> **이 에이전트는 판정하지 않는다.** codex 지적의 옳고 그름, 심각도 환산, 중복 병합, 오탐 여부는
> 전부 2단계 이후 `migration-reviewer` 와 리더의 몫이다. 아래 §3 은 **무편집 원문**이고,
> §4 는 원문과 분리된 정리 구획이다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-20 09:00 ~ 09:20 KST |
| 어간 | `04_documents` (**리더가 1단계 호출에서 지정**. 이 에이전트가 짓지 않았다) |
| 산출물 경로 | `docs/migration/_workspace/reviews/04_documents_codex-reviewer.md` |
| 리뷰 도구 | codex CLI (헬퍼 경유) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 버전 | `1.0.6` (plugins cache · 최신 버전 자동 선택) |
| 모드 | `adversarial-review` (2회 모두) |
| base | `385770e~1` |
| scope | `auto(미지정)` — `--base` 가 주어져 무시됨 |
| 호출 횟수 | **2회** (focus 축 상한 때문에 분할. 아래 §1.2) |
| 종료 코드 | 호출 1 = **0** · 호출 2 = **0** (둘 다 리뷰 근거로 유효) |
| job id | **헬퍼 1.0.6 이 stderr 에 job id 를 찍지 않았다.** 사후 회수가 필요하면 `node <헬퍼> status --all` 로 조회해야 한다 |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (2회 동일)

```
codex-review: 리뷰 대상 = branch diff vs 385770e~1
codex-review: 대상 판정 = non-empty (merge-base=0ce88b451629, 변경 파일 116개 (branch 모드는 커밋된 변경만 센다))
```

`--dry-run` 선행 확인도 같은 두 줄을 냈다(종료 코드 6).

### 1.2 왜 2회로 나눴는가

리더가 지목한 자리는 **7개**다. `codex-review` 스킬 §3.5 규칙 4 가 **"한 번에 3~5개 축까지만 — 열 개를 넣으면 전부 얕게 본다"** 로 상한을 두었으므로, 같은 base 에 대해 축을 나눠 두 번 호출했다.

| 호출 | 축 | 리더 지목 항목 |
|---|---|---|
| **1** | 5축 — 검사 순서·트랜잭션 경계 / 봉투 2값 명시 INSERT / 재암호화 4조건 / 통합 테스트가 무엇을 증명하는가 / 평문 노출면과 `MaskedItemCodec` | ①②③④⑤ |
| **2** | 4축 — 새 장치의 빈 분모 초록 경로 / 게이트가 어디서 도는가 (+ 미교차 3건) / C1 파서 방어의 층과 음성 대조 / L1 규칙(`e572476`) 문면의 자기 도달 | ⑥⑦ + `e572476` + `04_gate25-fixes_cross.md` §10 |

### 1.3 실행 명령 (인자 그대로)

```
# 호출 1
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 385770e~1 "<§2.1 프롬프트 전문>"

# 호출 2
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 385770e~1 "<§2.2 프롬프트 전문>"
```

스크립트가 해석해 실제로 실행한 명령(stderr 기록):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base '385770e~1' '<focus text>'
```

### 1.4 리뷰 대상 — 실측

`--base 385770e~1` 은 `merge-base(HEAD, 385770e~1)..HEAD` 를 본다. HEAD = `6515548`.

- 변경 파일 **116개**
- 커밋 **27개** — 리더 지시문은 이 범위를 "28커밋"으로 적었다. `git rev-list --count 385770e~1..6515548` 실측은 **27** 이다. **사실 기록이며 판정하지 않는다.**
- 범위 양 끝: `385770e` (docs: 리서치·계획 절의 전칭 선언에 오늘의 강제자를 명시한다) ~ `6515548` (feat(kotlin): 문서·변환 저장 경로 — 단일 트랜잭션과 봉투)
- 리더가 지목한 주 대상 C1 `df0766e` · C2 `6515548` 이 모두 범위 안에 있다.

**`e572476` 은 이 diff 범위 밖이다**(게이트 26 기준선의 조상). 리더 지시대로 **내용으로** 읽게 했다 — 호출 2 의 축 (4) 가 파일 경로(`.claude/skills/kotlin-migration/SKILL.md`)와 규칙 문면을 직접 지목해 codex 가 diff 가 아니라 현재 파일을 읽도록 구성했다.

### 1.5 제공한 맥락 목록

프롬프트에 **값으로 실어 준 것**(codex 는 이 저장소의 계획 문서를 모른다):

- 계약이 못박은 업로드 검사 순서(413 → 422 → 422 → 404 → 저장 → 커밋 → 큐 등록)와 소유권 은닉 404
- `V3` 가 봉투 2열의 DEFAULT 를 없앤 설계 의도와, 세대가 갈리면 AAD 때문에 영구 복호화 불능이 된다는 결과
- 재암호화 4조건(단일 UPDATE · NULL 보존 · 실패 시 전체 중단 · 낙관적 조건)과 각 조건이 깨졌을 때의 결과
- "직전까지 모든 Spring 테스트가 키 없이 떠서 모든 초록이 「503 을 잘 낸다」의 초록이었다"는 사실
- 로그 규약(문서 id·길이·상태·예외 타입까지)과 PostgreSQL 이 DETAIL 에 실패한 행 전체를 담는다는 사실
- `kotlin-migration` 스킬 「선언한 범위와 실제 도달을 대조한다」의 **요지와 실패 3건 예시**(사적 헤더 필터 미도달 · `mypy .` 점 디렉터리 · 품질 게이트 도달 0). 규칙 전문은 옮기지 않고 codex 가 파일을 읽게 두었다
- 파서 방어 조건(DTD 3속성 · `ZipEntry.getSize()` 불신 · 10MB · 누적 길이 상한 · 스캔 PDF 거절 · 동시 추출 제한 · 로그 규약)
- Python 원본 경로(`app/services/documents.py`·`app/repositories/documents.py`·`app/repositories/conversions.py`)와 **"Python 은 폐기 대상이고 정답이 아니다"** 는 단서
- 미교차 3건 중 ②(CI 가 지금 빨간가)와 ①(원시 제어문자 판정 기준의 도달 차이)을 축 (2) 에 값으로 실었다

**프롬프트에 넣지 않은 것**(의도적):

- Claude 나 리더가 이미 의심하는 결론 — 리더 지시문 자신이 *"값을 미리 주지 않는다 — 아래는 어디를 보라는 지목이지 결론이 아니다"* 로 못박았고 그대로 따랐다
- 실제 암호문·키·사용자 문서·개인정보 — 합성 값조차 필요하지 않았다(코드 구조 리뷰라 예시 데이터를 쓰지 않았다). `privacy-gate` 감사 대상 항목이며 **위반 0**
- 다른 리뷰어의 산출물

---

## 2. 전달한 프롬프트 전문

### 2.1 호출 1 — 저장 경로 (5축)

```
배경: 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 중이다. 기존 제품 동작과 개인정보 보호 정책을 보존해야 한다. 이번 변경 덩어리의 핵심은 문서 업로드 저장 경로(backend-kotlin 의 application/document, infrastructure/document, resources/db/migration/V5__conversion_jobs.sql)와 문서 추출기(infrastructure/ingest)다. Python 원본은 app/services/documents.py, app/repositories/documents.py, app/repositories/conversions.py 다 — 다만 Python 은 폐기 대상이고 정답이 아니다. "같은 값이 나오는가"가 아니라 "아래 조건을 만족하는가"를 물어라. 암호문·토큰 호환은 요구하지 않는다.

아래 다섯 축만 본다. 각 축의 문장은 지켜져야 하는 조건이며, 그것을 위반하는 경로를 코드에서 찾아 파일·라인으로 지목하라.

(1) 검사 순서와 트랜잭션 경계. 계약(contracts/easy-doc-v1.yaml 의 paths./documents.post)이 검사 순서를 못박았다 — 파일 크기(413) -> 추출(422) -> 본문 길이(422) -> 작업 공간 소유권(404) -> 저장 -> 커밋 -> 큐 등록. 소유권 확인이 저장보다 먼저여야 거절당한 업로드가 남의 공간이나 기본 공간에 남지 않는다. 소유권 검사는 조회 결과를 필터링하는 방식이 아니라 WHERE 절에 소유 조건이 합쳐진 단일 조회여야 하고, 없으면 403 이 아니라 404 다(자원 존재 자체를 숨긴다). 문서·변환·작업 큐 세 행은 한 트랜잭션에서 함께 성공하거나 함께 실패해야 한다. DocumentService.kt, DocumentPorts.kt, JdbcDocumentRepository.kt, JdbcConversionRepository.kt, JdbcConversionQueue.kt, JdbcWorkspaceLookup.kt, DocumentConfiguration.kt 를 읽고 실제 실행 순서가 계약 순서인지 확인하라. 트랜잭션 경계가 무너지는 자리 — @Transactional 이 private/internal 메서드나 자기 호출에 붙어 프록시가 적용되지 않는 곳, 트랜잭션 밖에서 도는 조회·암호화·큐 등록, 프로그래매틱 트랜잭션 래퍼 안에서 예외가 삼켜져 롤백이 표시되지 않는 곳 — 을 찾아라. 큐 등록을 같은 트랜잭션에 넣은 선택이 잃게 하는 것(커밋 전 가시성, 커넥션 점유 시간, 잠금 순서·데드락, 워커가 커밋 전 행을 잡을 수 있는지)이 있으면 지적하라. 순서가 깨지면 거절당한 업로드가 저장되거나, 저장은 됐는데 영원히 처리되지 않는 문서가 생긴다.

(2) 봉투 2값(encryption_scheme, key_version)의 명시 쓰기. V3 마이그레이션이 이 두 컬럼의 DEFAULT 를 일부러 없앴다 — 빠뜨린 쓰기가 NOT NULL 위반으로 즉시 시끄럽게 실패하게 하려는 설계다. 모든 INSERT/UPDATE 경로가 두 값을 SQL 문에 명시하는지, 그 값이 실제로 암호화에 쓰인 세대와 같은 출처(ContentCipher 의 writeScheme/writeKeyVersion)에서 오는지, 컬럼 기본값이나 DB DEFAULT 에 의존하는 쓰기 경로가 하나라도 남았는지 확인하라. 저장된 세대 값이 실제 암호문의 세대와 갈리면 AAD(associated data)에 세대가 실리므로 그 행은 영원히 복호화되지 않는다 — 사용자 문서의 영구 손실이다. V5__conversion_jobs.sql 이 이 원칙을 깨는 새 DEFAULT 나 nullable 봉투 컬럼을 도입하지 않았는지, 그리고 대기 중 변환 행이 암호문 3열 NULL 인 채로 봉투 2값만 갖는 상태가 이후 UPDATE 와 모순되지 않는지 보라.

(3) 재암호화(EnvelopeRotation.kt 와 ConversionRepository 의 rewriteEnvelope)의 네 조건이 구조로 강제되는가, 아니면 호출 순서에 대한 규율일 뿐이라 다음 호출자가 조용히 깰 수 있는가. 조건 — (a) 세 암호문 열을 단일 UPDATE 로 함께 바꾼다(열 하나짜리 갱신 메서드가 존재하면 안 된다). (b) 원본이 NULL 인 열은 NULL 로 보존한다. 빈 문자열을 암호화해 채우면 없던 내용을 지어낸 것이고 되돌릴 수 없다. (c) 세 열을 전부 복호화한 뒤에 암호화하고, 하나라도 복호화 실패면 UPDATE 를 부르지 않고 전체를 중단·롤백한다. 부분 회전된 행은 복구 불가다. (d) WHERE key_version = :expected 낙관적 조건으로 두 프로세스가 같은 행을 회전할 때 뒤엣것이 0행을 갱신한다. 각 조건이 타입·포트 시그니처로 강제되는지 판정하고, 강제되지 않는 것은 어떤 호출 패턴이 그것을 깨는지 구체적으로 보여라. 또한 평문이 컬렉션·필드·로그·예외 메시지에 체류하는 자리가 있는지 보라. 테스트(EnvelopeRotationTest.kt, JdbcDocumentStoreTest.kt, StatementCountingPremiseTest.kt)가 구현을 그대로 복사해 언제나 통과하는 구조인지, 문장 수를 세는 대조가 실제로 실행된 SQL 문 수를 재는지(아니면 호출 횟수 같은 대리 지표를 재는지) 확인하라.

(4) 통합 테스트가 실제로 무엇을 증명하는가. 이 저장소는 직전까지 모든 Spring 테스트 컨텍스트가 암호화 키 없이 떠서 encrypt 가 언제나 ConfigurationException(503)을 냈다 — 즉 모든 초록이 "503 을 잘 낸다"의 초록이었다. DocumentStorageContextTest.kt, JdbcDocumentStoreTest.kt, backend-kotlin/*/build.gradle.kts 의 테스트 태스크 시스템 속성을 함께 읽고 답하라. 조립된 Spring 빈이 실제 키로 암호화 -> 저장 -> 조회 -> 복호화 왕복을 도는 것이 증명되는가, 아니면 여전히 예외 갈래만 밟는가. 기동 자기점검(verify-on-startup)이 그 컨텍스트에서 실제로 켜진 채 통과했음은 무엇으로 단언되는가 — 켜졌다는 사실 자체를 재는 단언이 있는가, 아니면 "통과했으니 켜졌을 것"이라는 추론인가. Gradle 이 주는 시스템 속성과 테스트가 주는 @DynamicPropertySource/인라인 속성의 우선순위가 기대와 다를 때 자기점검이 꺼진 채 초록이 되는 경로가 있는가. 그것을 가르는 음성 케이스(틀린 KCV·틀린 키를 주면 컨텍스트 기동이 실패한다)가 정상 케이스와 같은 속성 우선순위 경로를 타는가, 아니면 다른 경로라서 실제로는 다른 것을 재는가. 2세대 키 회전 왕복이 조립된 빈으로 도는가.

(5) 평문 노출면과 MaskedItemCodec. 불변식 — 사용자 문서 텍스트와 마스킹 대응표는 평문으로 DB 에 저장되거나 로그에 남으면 안 되고, 로그에는 문서 id·길이·처리 상태·예외 타입까지만 남는다. MaskedItemCodec.kt, DocumentStorageLog.kt, DocumentMessages.kt, SensitiveToStringReachTest.kt 를 읽어라. 마스킹 항목의 저장 직렬화 키와 외부 계약 enum 값이 분리돼 있는가 — 둘을 같은 문자열 상수로 쓰면 한쪽을 바꾸는 순간 다른 쪽이 조용히 깨지고, 저장된 옛 데이터를 읽지 못하게 된다. 평문을 꺼내는 reveal 성격의 호출이 몇 군데인지 세고 각 자리가 정당한지 판정하라. 평문 조각이 새는 후보 — 예외 메시지, JSON 직렬화/역직렬화 오류 메시지, DB 제약 위반 로그(PostgreSQL 은 DETAIL 에 실패한 행 전체를 담는다), toString/data class 자동 생성 toString, 메트릭·트레이스 태그, 정렬·비교용 임시 컬렉션 — 을 데이터 흐름으로 따라가라. Kotlin data class 나 value class 가 민감 타입을 담고 자동 toString 을 갖는 자리가 있으면 지목하라.
```

### 2.2 호출 2 — 범위 대조 · 파서 방어 · 하네스 (4축)

```
배경: 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 중이고, 전환의 게이트를 하네스(테스트·스크립트·CI 잡·스킬 정의)로 세워 왔다. 이 하네스에서 같은 형태의 실패가 일곱 번 났다 — 선언한 범위와 실제 도달 범위가 다른데 아무도 재지 않았다. 예: "모든 응답에 헤더를 붙인다"는 필터가 파싱 단계 거절 응답에는 시작조차 하지 않았고, "저장소 전체를 본다"는 mypy 가 점으로 시작하는 디렉터리를 건너뛰었고, 품질 합격선을 강제한다는 게이트가 CI 어느 잡에도 배선되지 않아 도달이 0 이었다. 그러므로 초록불 자체를 의심하라 — 이 종류의 결함은 "동작하지 않는다"가 아니라 "동작하는 것처럼 보이면서 아무것도 재지 않는다"이다.

아래 네 축만 본다. 각 축의 문장은 지켜져야 하는 조건이며, 그것을 위반하는 자리를 파일·라인으로 지목하라.

(1) 이번 변경이 새로 세우거나 넓힌 탐지 장치들이 빈 분모에서 초록이 되는 경로. 대상 — tests/test_kotlin_gate_reach.py (선언 상수가 78 에서 85 로 늘었다), backend-kotlin 의 SensitiveToStringReachTest.kt (46 에서 48 로 늘었다), StatementCountingPremiseTest.kt 와 CountingDataSource.kt, 그리고 "application 포트를 구현한 infrastructure 구상 클래스를 종류로 훑어 raw JDBC 손잡이 부재를 확인한다"고 선언한 탐지기. 각각에 대해 답하라. 분모를 무엇으로 훑는가(디렉터리 경로 전제, 파일 확장자, 정규식, 패키지 이름, 리플렉션 스캔 범위, 클래스패스). 그 훑기가 0건 또는 부당하게 적은 건수를 낼 수 있는 입력이 있는가 — 모듈이 추가되거나, 소스 루트가 바뀌거나, 클래스가 다른 패키지로 옮겨지거나, 정규식이 잡지 못하는 선언 형태(중첩 클래스, typealias, companion, fun interface, 어노테이션이 메타 어노테이션으로 간접 붙은 경우)가 들어오면. 그리고 0건일 때 실패하는가, 초록이 되는가 — 빈 선언에서 통과하는 장치는 결함이다. 리포트 XML 의 존재나 파일 존재나 클래스 이름 같은 대리 지표를 "그 코드가 실제로 돌았다"로 바꿔 읽는 자리를 찾아라. 마지막으로 각 장치를 통째로 지우면 정확히 무엇이 깨지는지 답하라 — 장치 안에 든 자기 단언은 장치와 함께 사라지므로, 장치 밖에서 아무것도 안 깨지면 그 장치는 검증된 것이 아니다.

(2) 이 게이트들이 지금 어디서 도는가. .github/workflows/ci.yml 을 읽고 위 장치들이 실제로 실행되는 잡 이름과 스텝을 짚어라. 환경변수로만 켜지는 대조(예: KOTLIN_GATE_REACH_REQUIRE_REPORT)가 있으면 그 변수를 실제로 켜는 스텝이 존재하는지, 그 스텝의 working-directory 와 리포트 경로 전제가 맞는지, 변수가 없을 때 그 대조가 실패하는지 조용히 skip 되는지 확인하라. 로컬에서만 도는 게이트, 아무 데서도 안 도는 게이트를 지목하라. 아울러 이 잡들이 현재 HEAD 에서 실제로 통과하는지 판단할 근거가 코드 안에 있는지 보라 — 리포트 XML 을 실행의 증거로 삼는 구조라면 어떤 리포트 상태에서 참이고 어떤 상태에서 거짓이 되는지 답하라. 또 한 가지: 원시 제어문자(C0/DEL)를 추적 파일에서 잡는다고 선언한 장치가 둘 있다(tests/test_raw_control_chars.py 와 .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py). 두 장치의 판정 기준이 서로 같은지, 분모(git ls-files 전수인지, 미추적 파일이 빠지는지, 바이너리로 분류된 파일을 건너뛰는지)가 무엇인지 대조하고, 판정 기준을 "원시 제어문자 보유"로 두는 것과 "git 이 바이너리로 부름"으로 두는 것의 도달 차이를 지적하라.

(3) 문서 파서 방어가 계획한 층에 실제로 걸려 있는가, 그리고 음성 대조가 성립하는가. backend-kotlin/infrastructure 의 ingest 패키지 전체(DocumentExtractors.kt, DocxExtractor.kt, PdfExtractor.kt, HwpxExtractor.kt, SecureXml.kt, OoxmlDom.kt, ZipBudget.kt, PoiZipDefenses.kt, ExtractionLimits.kt, ConcurrencyLimitedTextExtractor.kt, IngestConfiguration.kt, Ole2Diagnosis.kt, ExtractedTextBuilder.kt)와 그 테스트를 읽어라. 지켜져야 하는 것 — XXE/DTD: XML 파싱에서 SUPPORT_DTD=false, IS_SUPPORTING_EXTERNAL_ENTITIES=false, ACCESS_EXTERNAL_DTD="" 가 모두 걸려 billion laughs 가 UTF-8 과 UTF-16 인코딩 모두에서 확장되기 전에 끊긴다. docx 경로에서 DOCTYPE 선언이 거부된다. zip: ZipEntry 가 헤더에 적어 둔 크기를 신뢰하지 않고 실제 압축 해제 바이트를 예산으로 끊어, 위조된 크기 헤더로 힙을 터뜨릴 수 없다. 업로드 바이트 상한(10MB)과 추출 결과 길이 상한이 누적으로 걸려 사후 검사가 아니다. 텍스트가 나오지 않는 스캔 PDF 는 거절되고 페이지 0건과 구분된 문구를 낸다. 동시 추출이 제한돼 건당 수십 MB 예산이 컨테이너 스레드 수만큼 곱해지지 않는다. 로그에는 형식명·바이트 길이·예외 타입만 남고 파일명·본문·라이브러리 예외 메시지는 남지 않는다(라이브러리 메시지에는 임시 경로와 내용 조각이 섞인다). 각 방어가 어느 층(서블릿/서비스/디스패치/파서)에 실제로 걸려 있는지 짚어라. 그리고 음성 대조를 판정하라 — 어떤 방어는 그 앞쪽 방어에 가려서, 그것을 떼도 테스트가 여전히 초록일 수 있다. 그런 자리가 있으면 그 테스트는 그 방어를 재고 있지 않다. XML 파서 보안 설정이 XMLInputFactory/DocumentBuilderFactory 를 만드는 모든 자리에 걸리는지, 아니면 한 곳에만 걸려 있고 다른 진입점이 맨몸인지 확인하라. commons-compress 와 POI 의 zip 방어가 JVM 전역 static 상태에 의존한다면 테스트 격리가 서로를 오염시키는지도 보라.

(4) 하네스 규칙 문면의 자기 도달. .claude/skills/kotlin-migration/SKILL.md 안의 "심판문은 심판 대상이 고치지 않는다" 규칙을 읽어라 — 조치(구현·처방 적용)를 한 레인은 같은 커밋으로 docs/migration/_workspace/reviews/ 아래를 편집하지 않는다, 재판정은 새 감사 회차의 새 파일이나 교차 종합에서만 한다, privacy-gate 는 스캐너를 고치지 않는다(감사·처방까지이고 적용은 하네스 레인의 몫이다). 이 규칙을 강제하는 것이 무엇인지 저장소에서 찾아라 — 실행되는 검사(테스트·훅·CI 스텝)인가, 아니면 문장뿐인가. 문장뿐이면 그 사실을 지적하고, 그런 규칙이 지켜졌는지 사후에 확인할 수 있는 방법이 있는지 답하라. 그리고 리뷰 범위인 커밋들(merge-base 이후 HEAD 까지)에 대해 git log --name-only 로 확인하라 — 코드를 고치는 커밋이 같은 커밋으로 docs/migration/_workspace/reviews/ 아래 파일을 편집한 사례가 이 범위 안에 있는가. 있으면 커밋 해시와 파일을 적어라.
```

---

## 3. codex 원문 출력 (무편집)

> **이 구획에는 Claude 가 쓴 문장이 한 줄도 없다.** 아래는 스크립트 stdout 그대로다.
> 오탐으로 보이는 것, 존재하지 않는 전제, 프로젝트 규칙과 어긋나는 제안도 삭제하지 않았다.

### 3.1 호출 1 원문 (종료 코드 0)

```
# Codex Adversarial Review

Target: branch diff against 385770e~1
Verdict: needs-attention

출하 금지. 실제 Spring 빈의 AES-GCM 저장·복호화와 v2 회전은 검증되지만, 평문 본문 저장, 동결 계약과 다른 큐 실패 의미, 혼합 세대를 허용하는 회전 포트, HWPX 422 우회가 남아 있다.

Findings:
- [critical] 자동 제목이 문서 본문을 평문으로 DB에 복제한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentService.kt:165-177)
  제목을 생략하면 `resolveTitle(title, text)`가 본문 첫 내용 줄을 제목으로 만들고, `DocumentDraft`가 이를 평문 `documents.title`로 저장한다. 원문 암호화와 무관하게 최대 30자의 본문 조각이 DB·백업·검색 표면에 남는다. 통합 테스트도 `source_text_encrypted`만 검사하므로 짧은 probe 본문 전체가 title에 있어도 통과한다.
  Recommendation: 평문 컬럼의 기본 제목을 본문에서 유도하지 말고 고정 비민감 값으로 만들라. 제목도 보호 대상이면 별도 AEAD 컬럼으로 이전하고 전체 행에서 평문 probe가 없음을 검사하라.
- [high] 큐 등록이 커밋 전에 실행되어 POST 계약의 실패 의미가 사라진다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentService.kt:160-185)
  `TransactionTemplate`의 커밋은 콜백 반환 뒤인데 큐 INSERT는 콜백 내부에서 실행된다. 따라서 큐 실패 시 문서·변환도 롤백되며, 계약의 `저장 → 커밋 → 큐 등록`, durable `EnqueueFailed`, 502 경로와 다르다. `JdbcConversionQueue`도 DB 예외를 `QueueUnavailableException`으로 변환하지 않아 실제 실패는 예상하지 못한 500으로 빠진다. 원자적 DB 큐가 의도여도 현재 동결 계약과 양립하지 않는다.
  Recommendation: 원자적 작업 행이 새 요구라면 계약의 커밋 후 등록·502·EnqueueFailed 조항을 명시적으로 개정하고 큐 INSERT 실패 응답을 계약 테스트로 고정하라. 그렇지 않으면 계약대로 커밋 후 등록과 실패 표시를 구현하라.
- [high] 재암호화 포트가 혼합 세대 암호문을 유효한 행으로 기록할 수 있다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentPorts.kt:163-176)
  `rewriteEnvelope`는 목표 scheme/keyVersion과 `ConversionCiphertexts`를 독립 인자로 받는다. 호출자는 v1 암호문과 v2 암호문을 섞고 행 메타데이터를 v2로 지정할 수 있으며, JDBC 구현은 각 `EncryptedContent`의 메타데이터를 버리고 bytes만 기록한다. 이 호출은 한 UPDATE여도 v1 열이 v2 AAD로 읽혀 영구 복호화 불능이 된다. 현재 `EnvelopeRotation`의 올바른 호출 순서가 유일한 방어다.
  Recommendation: 목표 세대와 모든 non-null 암호문의 메타데이터 일치를 생성 시 검증하는 단일 replacement 타입을 사용하고, 원시 rewrite 권한을 `EnvelopeRotation` 밖에 노출하지 마라.
- [medium] HWPX XML 리더 생성 실패가 추출 422 경로를 우회한다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/ingest/HwpxExtractor.kt:78-87)
  `createXMLStreamReader`가 `try` 앞에서 실행된다. 동일한 StAX 보안 속성을 적용한 런타임 대조에서 `encoding="BOGUS"` 선언은 생성 시 `XMLStreamException`을 던졌다. 이 입력은 `broken()`의 `DocumentExtractionException` 변환을 거치지 않아 계약상 추출 오류 422 대신 예상하지 못한 500이 된다.
  Recommendation: 리더 생성까지 동일한 try/catch 안에 넣고, 잘못된 인코딩 선언이 고정 메시지 `DocumentExtractionException`으로 변환되는 음성 테스트를 추가하라.

Next steps:
- 자동 제목의 평문 저장을 제거하고 전체 DB 행 대상 유출 테스트를 추가한다.
- DB 큐 원자성과 동결 POST 계약 중 정본을 결정해 구현·계약·오류 테스트를 일치시킨다.
- 회전 replacement 타입과 호출 권한을 재설계한 뒤 혼합 세대 음성 테스트를 추가한다.
- HWPX 리더 생성 예외를 변환하고 관련 ingest 테스트를 재실행한다.
```

### 3.2 호출 2 원문 (종료 코드 0)

```
# Codex Adversarial Review

Target: branch diff against 385770e~1
Verdict: needs-attention

NO-SHIP. CI 배선은 존재한다: quality 잡은 개인정보 스캐너·비요구 모드 Kotlin 도달 검사·원시 제어문자 검사를 돌리고, kotlin 잡은 Gradle build 뒤 저장소 루트에서 KOTLIN_GATE_REACH_REQUIRE_REPORT=1을 켠다. 요구 모드는 리포트 부재와 skipped 전건을 실패시키지만, 변수 없이 리포트가 없으면 실행 대조 대상은 0건이다. 원시 제어문자 검사는 추적된 텍스트의 C0/DEL 바이트를 보며 미추적 파일과 git-binary+비UTF-8 파일을 제외한다. 개인정보 스캐너는 열거된 루트·확장자를 보고 미추적 파일도 포함하지만 원시 제어문자 규칙 자체가 없어 상호 대체물이 아니다. 현재 범위에는 구현과 reviews/를 같은 커밋에서 고친 사례가 없지만, 핵심 탐지 분모와 파서 음성 대조에서 여전히 조용한 초록 경로가 남아 있다.

Findings:
- [high] 85개 클래스 게이트는 탐지기와 선언을 함께 지우면 초록이 된다 (tests/test_kotlin_gate_reach.py:217-253)
  분모는 backend-kotlin 아래의 .kt 중 경로 구성요소에 src와 test가 모두 있는 파일뿐이며, 파서는 제한된 JUnit 애너테이션을 품은 열 0의 최상위 class/object만 찾는다. 새 Gradle 모듈이 이 루트 밖에 있거나 빌드에 포함되지 않거나, 비표준 소스셋·메타 애너테이션 선언을 파서와 Gradle build가 함께 놓치면 소스 집합과 XML 모두 0건을 보고한다. 개별 Kotlin 탐지기 파일을 삭제하면서 TEST_CLASSES와 TEST_CLASS_COUNT를 같이 줄여도 모든 대조가 통과한다. 외부 단독 실행으로 보호되는 것은 두 privacy 클래스뿐이다. 따라서 85라는 정확 일치는 독립 분모가 아니라 같은 수기 선언의 자기 일치다.
  Recommendation: Gradle settings와 각 프로젝트의 실제 test sourceSets/test task를 독립적으로 열거해 분모를 만들고, 필수 탐지기마다 별도 --tests 실행 또는 변경 불가능한 외부 목록을 둔다. 탐지기 삭제+목록 감소, 새 모듈 미배선, 메타 애너테이션 테스트를 각각 실패시키는 변이 테스트를 추가한다.
- [high] raw JDBC 전제 탐지기가 간접 포트 구현을 분모에서 제외한다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/StatementCountingPremiseTest.kt:93-117)
  스캔은 kr/easydoc/infrastructure 패키지의 클래스패스만 보고, application 포트 판정은 각 클래스·상위 클래스가 직접 선언한 인터페이스 이름만 검사한다. infrastructure 인터페이스가 application 포트를 상속하고 구상 어댑터가 그 중간 인터페이스를 구현하면 인터페이스 폐쇄를 재귀적으로 걷지 않아 어댑터 전체가 빠진다. 기존 JdbcClient 어댑터가 남으므로 비어 있지 않다는 단언도 초록이다. 금지 손잡이도 선언 필드·생성자 매개변수의 정확한 타입 이름만 보므로 DataSource 하위 타입이나 상속 필드도 우회한다. 그 결과 한 문장 생성 횟수가 실제 SQL 수를 대표한다는 트랜잭션 검증 전제가 조용히 무효화될 수 있다.
  Recommendation: application 포트의 재귀적 superinterface 폐쇄와 모든 제품 클래스의 assignability를 기준으로 어댑터를 찾고, 패키지 밖 구현도 실패시킨다. 금지 손잡이는 isAssignableFrom과 상속 필드·생성자 전체로 검사하며, 중간 인터페이스와 DataSource 하위 타입을 쓰는 위반 fixture가 반드시 탐지되는지 검증한다.
- [high] 500k 추출 상한 전에 HWPX 전체 구역 텍스트가 메모리에 만들어진다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/ingest/HwpxExtractor.kt:123-150)
  SectionBlocks는 구역 전체를 무제한 StringBuilder와 List<String>에 누적한 뒤에야 ExtractedTextBuilder로 넘긴다. 따라서 500k 결과 상한은 누적 중단이 아니라 사후 검사다. 10MB 업로드가 50MiB zip 예산까지 확장된 단일 구역을 만들면 큰 문자열·목록이 먼저 할당되고, 동시 추출 4건에서는 이 비용이 곱해진다. DocxExtractor도 blocks(data)를 전부 만든 다음 builder에 전달하는 같은 구조여서 짧은 결과 상한이 힙 보호선이 되지 않는다.
  Recommendation: 파서 이벤트가 예산 인식 sink에 직접 쓰도록 바꾸고 characters/문단 추가 전에 남은 문자 예산을 검사한다. DOCX도 목록을 먼저 만들지 않는 스트리밍 sink로 바꾸고, 단일 문단·단일 HWPX 구역이 500k를 넘는 fixture가 제한 근처에서 즉시 중단되는지 계측한다.
- [medium] SensitiveToString의 48건 정확 일치는 알려진 미탐 선언을 세지 못한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ProductClasses.kt:112-134)
  소스 분모는 Gradle 루트의 직계 자식 */src/main/kotlin과 .kt만 훑는다. 같은 파일의 문서가 여러 줄 애너테이션 뒤 같은 줄 선언, 줄 머리가 아닌 선언, use-site 애너테이션 형태를 조용히 놓친다고 명시한다. 따라서 API 테스트 런타임에 없는 worker나 새 중첩 모듈에 이런 data/value class가 생기면 EXPECTED_SOURCE_DECLARATIONS=48은 그대로이고 런타임 스캔에도 나타나지 않아 전 축이 초록이다. 60개 클래스 하한도 기존 클래스가 남아 있는 한 이를 감지하지 못한다.
  Recommendation: Gradle 프로젝트·sourceSets에서 모든 main 출력/소스 루트를 얻고, 소스 정규식 대신 Kotlin 컴파일 산출물 또는 compiler metadata로 data/value 여부를 판정한다. 적어도 현재 문서화된 세 미탐 형태와 중첩 모듈을 실패시키는 probe를 추가한다.
- [medium] HWPX XML 리더 생성 실패가 정화된 422 경로를 우회한다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/ingest/HwpxExtractor.kt:82-87)
  createXMLStreamReader 호출이 try 밖에 있다. 잘못된 XML 인코딩 선언이나 잘린 BOM처럼 리더 생성 시점에 발생하는 XMLStreamException은 broken()으로 변환되지 않고 라이브러리 예외로 전파된다. 현재 DTD 테스트는 reader.next() 중 실패하는 경로만 재므로 이 음성 대조를 못 본다. 공격 입력은 약속된 DocumentExtractionException·안전한 추출 로그 대신 예상 밖 5xx/관측 공백을 만들 수 있다.
  Recommendation: 리더 생성도 같은 try/catch 안에 넣고 nullable 리더를 finally에서 닫는다. 잘못된 선언 인코딩과 잘린 UTF-8/UTF-16 BOM을 가진 HWPX fixture로 정화된 422 계열 예외, 형식·크기·예외 타입만 포함한 로그, 원문/라이브러리 메시지 부재를 검증한다.
- [medium] POI 전역 방어 테스트가 제품 배선을 스스로 덮어써 음성 대조가 성립하지 않는다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/ingest/IngestDefensesTest.kt:23-32)
  테스트가 첫 동작으로 PoiZipDefenses.apply()를 직접 호출한 뒤 static 값을 확인한다. 이 상태는 JVM 전역으로 남으며 복원되지 않는다. IngestConfiguration의 apply 호출을 삭제해도 이 테스트가 원하는 값을 직접 설치하고, 조립 테스트는 래퍼 타입만 확인하므로 전체 테스트가 초록일 수 있다. 테스트 순서·병렬 실행에 따라 다른 테스트도 오염되어 생산 조립 지점이 실제로 방어값을 설치하는지는 검증되지 않는다.
  Recommendation: 테스트 전 POI 값을 의도적으로 잘못 설정한 뒤 IngestConfiguration의 bean 생성만으로 값이 복구되는지 확인하고 finally/@AfterEach에서 원값을 복원한다. 가능하면 별도 JVM 테스트로 격리하고 생산 apply 호출 삭제 변이가 실패하도록 한다.
- [medium] 심판문 분리 규칙은 실행되는 강제가 아니라 산문뿐이다 (.claude/skills/kotlin-migration/SKILL.md:199)
  규칙은 구현·처방 레인과 reviews/ 편집의 같은 커밋 혼합, privacy-gate의 스캐너 수정까지 금지하지만 이를 검사하는 테스트·훅·CI 스텝이 없다. 현재 merge-base 0ce88b4 이후 HEAD 범위의 review 커밋 두 건은 reviews/ 파일만 고쳐 위반 사례는 없었으나, 이는 git log --name-only로 사후 수동 확인한 결과일 뿐 다음 커밋을 막지 못한다. 특히 레인 역할은 커밋 메타데이터에 결속되지 않아 privacy-gate가 스캐너를 고쳤는지도 자동 판정할 수 없다.
  Recommendation: CI에 base SHA부터 HEAD까지 커밋별 경로 조합을 검사하는 테스트를 추가해 reviews/**와 구현·스캐너 경로의 동시 편집을 실패시킨다. privacy-gate/하네스 레인을 검증하려면 필수 커밋 trailer나 서명된 산출물 메타데이터로 역할을 결속하고 그 역할-경로 행렬도 검사한다.

Next steps:
- 병합을 중단하고 우선 HWPX 누적 메모리 상한과 리더 생성 예외 경계를 수정한다.
- 각 탐지기에 대해 탐지기 삭제+선언 갱신, 새 모듈 미배선, 간접 포트 구현, POI 생산 apply 삭제 변이를 실행해 적어도 하나의 장치 밖 검사가 실패하게 만든다.
- Gradle 리포트를 깨끗이 지운 새 checkout에서 kotlin 잡을 다시 실행한다. 현재 코드는 non-skipped testcase@classname을 실행 증거로 세지만 기존 로컬 XML은 HEAD 결속 증거가 아니다.
```

---

## 4. 정리 (가공)

> **이 구획은 Claude 가 만든 목록이다.** 원문(§3)과 구획이 다르다.
> 여기서도 **옳고 그름을 판정하지 않고, 심각도를 환산하지 않고, 중복을 병합하지 않는다.**
> `codex-review` 스킬 §5 척도(Critical ①사건 / Critical ②장치 / Major / Minor / 제안)로의
> **환산과 마감 판정은 2차 교차 종합(`migration-reviewer`)의 몫**이다 — 게이트 26 회차가 세운 관례를 따른다.

### 4.1 지적 목록 (codex 원문 라벨 그대로)

| # | 호출 | codex 라벨 | 지적 (원문 제목 그대로) | codex 가 적은 근거 파일·라인 |
|---|---|---|---|---|
| C-1 | 1 | `critical` | 자동 제목이 문서 본문을 평문으로 DB에 복제한다 | `application/.../document/DocumentService.kt:165-177` |
| C-2 | 1 | `high` | 큐 등록이 커밋 전에 실행되어 POST 계약의 실패 의미가 사라진다 | `application/.../document/DocumentService.kt:160-185` |
| C-3 | 1 | `high` | 재암호화 포트가 혼합 세대 암호문을 유효한 행으로 기록할 수 있다 | `application/.../document/DocumentPorts.kt:163-176` |
| C-4 | 1 | `medium` | HWPX XML 리더 생성 실패가 추출 422 경로를 우회한다 | `infrastructure/.../ingest/HwpxExtractor.kt:78-87` |
| C-5 | 2 | `high` | 85개 클래스 게이트는 탐지기와 선언을 함께 지우면 초록이 된다 | `tests/test_kotlin_gate_reach.py:217-253` |
| C-6 | 2 | `high` | raw JDBC 전제 탐지기가 간접 포트 구현을 분모에서 제외한다 | `infrastructure/.../db/StatementCountingPremiseTest.kt:93-117` |
| C-7 | 2 | `high` | 500k 추출 상한 전에 HWPX 전체 구역 텍스트가 메모리에 만들어진다 | `infrastructure/.../ingest/HwpxExtractor.kt:123-150` |
| C-8 | 2 | `medium` | SensitiveToString의 48건 정확 일치는 알려진 미탐 선언을 세지 못한다 | `api/.../support/ProductClasses.kt:112-134` |
| C-9 | 2 | `medium` | HWPX XML 리더 생성 실패가 정화된 422 경로를 우회한다 | `infrastructure/.../ingest/HwpxExtractor.kt:82-87` |
| C-10 | 2 | `medium` | POI 전역 방어 테스트가 제품 배선을 스스로 덮어써 음성 대조가 성립하지 않는다 | `infrastructure/.../ingest/IngestDefensesTest.kt:23-32` |
| C-11 | 2 | `medium` | 심판문 분리 규칙은 실행되는 강제가 아니라 산문뿐이다 | `.claude/skills/kotlin-migration/SKILL.md:199` |

**codex 원문 라벨 기준 건수**: `critical` 1 · `high` 5 · `medium` 5 · **합계 11**.
두 호출 모두 `Verdict: needs-attention`.

### 4.2 두 호출이 독립으로 같은 자리를 지목한 것 (병합하지 않고 사실만 적는다)

- **C-4 와 C-9 는 같은 자리**(`HwpxExtractor` 의 `createXMLStreamReader` 가 `try` 밖)를 지목한다. 두 호출은 **서로의 출력을 보지 못하는 별개 실행**이었고 focus 축도 달랐다(호출 1 은 저장 경로 5축, 호출 2 는 파서 방어 축). 라벨은 양쪽 다 `medium` 이고, 라인 범위 표기가 `78-87` 과 `82-87` 로 다르다.
- 그 밖에는 두 호출의 지적이 겹치지 않는다.

### 4.3 리더가 지목한 자리별 — codex 가 무엇을 말했고 무엇을 말하지 않았는가

**codex 가 지적을 내지 않은 축은 그 사실을 그대로 적는다**(`codex-review` 스킬 §7 — Claude 가 대신 지적을 만들어 채우지 않는다).

| 리더 지목 | codex 산출 |
|---|---|
| ① 저장 경로의 순서와 트랜잭션 경계 | **지적 있음** — C-2. 큐 등록의 트랜잭션 위치를 다뤘다. **검사 순서(413→422→422→404) 자체에 대한 지적은 없다** |
| ② 봉투 2값 명시 INSERT 와 `V3` 설계 의도 | **직접 지적 없음.** 다만 C-3 이 회전 경로에서 "행 메타데이터와 암호문 세대가 갈릴 수 있다"고 적었다 |
| ③ X9/F-6 통합 테스트가 무엇을 증명하는가 | **지적 없음.** 호출 1 요약이 *"실제 Spring 빈의 AES-GCM 저장·복호화와 v2 회전은 검증되지만"* 이라고 적었다 |
| ④ X5/F-5 재암호화 4조건이 구조로 강제되는가 | **지적 있음** — C-3 |
| ⑤ `MaskedItemCodec` (저장 키·계약 enum 분리, `reveal()` 자리, 로그) | **`MaskedItemCodec` 에 대한 지적은 없다.** 평문 노출 축에서는 대신 C-1(제목 컬럼)이 나왔다 |
| ⑥ 선언 범위 대 실제 도달 (K-2 · 게이트 핀 78→85 · `SensitiveToStringReachTest` 46→48) | **지적 있음** — C-5(게이트 핀) · C-6(K-2) · C-8(SensitiveToString). 세 장치 전부에 지적이 붙었다 |
| ⑦ C1 파서 방어가 계획 §5 의 층에 걸려 있는가, 음성 대조 성립 | **지적 있음** — C-7(추출 상한이 누적이 아님) · C-9(리더 생성 예외) · C-10(POI 음성 대조). **DTD 3속성·zip 예산·스캔 PDF 거절·동시 추출 제한·로그 규약에 대한 지적은 없다** |
| `e572476` L1 규칙 문면 | **지적 있음** — C-11 |
| `04_gate25-fixes_cross.md` §10 미교차 3건 | ①(제어문자 판정 기준의 도달 차이)와 ②(CI 배선)에 대해 **요약문에서 서술**했다 — 지적 항목(Findings)이 아니라 Verdict 요약 단락에 있다. ③(음성 대조 H 와 실측의 갈림)에 대해서는 `Next steps` 마지막 줄이 *"기존 로컬 XML은 HEAD 결속 증거가 아니다"* 라고 적었다. **판정하지 않고 그대로 옮긴다** |

### 4.4 전제 확인 필요 (내용은 지우지 않는다)

codex 가 사실 주장으로 적은 것 중 **이 에이전트가 검증하지 않은 것**이다. `migration-reviewer` 가 판단한다.

1. C-4 는 *"동일한 StAX 보안 속성을 적용한 런타임 대조에서 `encoding="BOGUS"` 선언은 생성 시 `XMLStreamException` 을 던졌다"* 고 적는다 — codex 가 실제로 실험을 돌렸다는 주장이다. 그 실험의 재현 여부는 확인하지 않았다.
2. C-5 는 *"외부 단독 실행으로 보호되는 것은 두 privacy 클래스뿐이다"* 라고 적는다. `ci.yml` 실측 대조는 하지 않았다.
3. C-11 은 *"현재 merge-base `0ce88b4` 이후 HEAD 범위의 review 커밋 두 건은 reviews/ 파일만 고쳐 위반 사례는 없었으나"* 라고 적는다 — 리뷰 범위 안에서 L1 규칙 **위반은 없었다**는 관측이다. 이 에이전트가 독립으로 세지 않았다.
4. C-2 의 *"`JdbcConversionQueue` 도 DB 예외를 `QueueUnavailableException` 으로 변환하지 않아"* 는 코드 사실 주장이다.
5. C-7 의 *"10MB 업로드가 50MiB zip 예산까지 확장된"* 은 이 저장소의 zip 예산 값에 대한 주장이다.

### 4.5 프로젝트 규칙과 어긋나 보이는 제안 (그대로 기록한다)

`codex-reviewer` 의 경계상 채택 여부를 판정하지 않는다. 다만 종합 단계가 놓치지 않도록 **위치만** 표시한다.

- C-2 의 권고 *"원자적 작업 행이 새 요구라면 계약의 커밋 후 등록·502·EnqueueFailed 조항을 명시적으로 개정하고"* — 계약 개정은 `contract-keeper` 소관이고, 계획 §9.2-bis D-m 이 이 처분을 **C3 의 계약 레인 몫**으로 이미 배정해 두었다. codex 는 그 배정을 모른다.
- C-1 의 권고 *"제목도 보호 대상이면 별도 AEAD 컬럼으로 이전하고"* — 스키마 변경 범위 판단이 걸린다(계획 §10 은 `V5` 를 additive 로 한정한다).
- C-11 의 권고 *"필수 커밋 trailer 나 서명된 산출물 메타데이터로 역할을 결속하고"* — 하네스 정책 변경 제안이다.

---

## 5. 미실행 · 실패 항목

- **codex 호출 실패 0건.** 2회 호출 모두 첫 시도에 종료 코드 `0`. 재시도를 쓰지 않았고, **「codex 리뷰 누락」에 해당하지 않는다.**
- **출력 잘림 없음.** 두 응답 모두 `Findings` → `Next steps` 까지 온전히 끝났다(stdout 4,244바이트 / 9,625바이트).
- **job id 미기록.** 헬퍼 1.0.6 이 stderr 에 job id 를 찍지 않았다. 사후 회수가 필요하면 `node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs status --all` 로 조회해야 한다. **스킬 §6 이 요구하는 기록 항목 중 유일한 결손이다.**
- **게이트 명령을 이 회차에 돌리지 않았다.** 이 에이전트의 역할은 호출·보존이고 판정이 아니다. `gradle build`·`pytest`·스캐너의 현재 상태는 `migration-reviewer` 와 리더의 실행 근거를 따른다. 파이프를 쓴 명령은 없다(종료 코드를 근거로 삼은 명령 자체가 없다).
- **`e572476` 은 diff 범위 밖이라 codex 가 diff 로는 보지 못했다.** 축 (4) 가 파일 경로와 규칙 문면을 지목해 **현재 파일 내용으로** 읽게 했고, C-11 이 그 결과다. 다만 codex 가 `SKILL.md:199` 를 인용한 것이 그 커밋이 더한 두 줄과 같은 자리인지는 **확인하지 않았다**(§4.4 와 같은 성격의 미검증 항목).
- **리뷰 범위 커밋 수 표기 차이.** 리더 지시문 "28커밋" 대 실측 27커밋(§1.4). 사실만 적는다.
- **다른 리뷰어 산출물 미열람.** `04_documents_migration-reviewer.md` 를 열지 않았다.
- **민감 데이터 미포함.** 프롬프트에 실제 암호문·키·사용자 문서·개인정보를 싣지 않았다. 합성 값도 쓰지 않았다(코드 구조 리뷰라 불필요).

---

## 6. 다음 단계 (이 에이전트의 몫이 아닌 것)

1. **2단계** — 리더가 `04_documents_codex-reviewer.md`(이 파일)와 `04_documents_migration-reviewer.md` 두 파일의 존재와 **어간 일치**를 확인한다.
2. **3단계** — `migration-reviewer` 를 **재호출**해 두 산출물을 `codex-review` 스킬 §5 표로 대조하고 `04_documents_cross.md` 를 쓴다. 그 호출에서 **심각도 환산·마감 부여·상충 표시**가 이뤄지며, **새 지적은 만들지 않는다.**
3. Phase 종료 판정은 리더(오케스트레이터)가 `_cross.md` 를 근거로 내린다. **이 파일은 판정이 아니다.**
