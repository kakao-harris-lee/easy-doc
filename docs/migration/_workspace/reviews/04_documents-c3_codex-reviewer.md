# 게이트 28 · 1단계 · codex 독립 리뷰 — `04_documents-c3`

> 이 파일은 `codex-reviewer` 가 쓴 **1단계 산출물**이다. 3단계 교차 종합(`04_documents-c3_cross.md`)의 입력 하나이며,
> 나머지 입력은 같은 시각 독립으로 도는 `04_documents-c3_migration-reviewer.md` 다.
>
> **이 회차에 다른 리뷰어의 산출물을 열지 않았다.** 예외 하나를 정직하게 적는다 — 직전 회차의 **내 레인** 파일
> `reviews/04_documents_codex-reviewer.md` 의 **머리 60줄(호출 메타데이터 서식)만** 읽었다. 지적 본문·§4 정리·
> `..._cross.md`·`..._migration-reviewer.md` 는 열지 않았다.
>
> **이 에이전트는 판정하지 않는다.** codex 지적의 옳고 그름, 심각도 환산, 중복 병합, 오탐 여부는 전부 2단계 이후
> `migration-reviewer` 와 리더의 몫이다. 아래 §3 은 **무편집 원문**이고, §4 는 원문과 분리된 정리 구획이다.
>
> **심각도 라벨은 codex 원문 그대로 둔다**(`critical` / `high` / `medium`). 리더 지시에 따라 `codex-review` 스킬 §5
> 4단계(Critical①/Critical②/Major/Minor/제안)로의 **환산은 3단계 교차 종합에 넘긴다.** 이 문서 어디에도 환산값은 없다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-20 17:30 ~ 17:41 KST |
| 어간 | `04_documents-c3` (**리더가 1단계 호출에서 지정**. 이 에이전트가 짓지 않았다) |
| 산출물 경로 | `docs/migration/_workspace/reviews/04_documents-c3_codex-reviewer.md` |
| 회차 | 이 어간의 **1회차**. 게이트 27 은 `04_documents` 어간을 썼고 그 파일은 덮지 않았다 |
| 리뷰 도구 | codex CLI (헬퍼 경유) |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 버전 | `1.0.6` (plugins cache · 최신 버전 자동 선택) |
| 모드 | `adversarial-review` (2회 모두) |
| base | `66f008b` |
| scope | `auto(미지정)` — `--base` 가 주어져 무시됨 |
| 호출 횟수 | **2회** (focus 축 상한 때문에 분할. 아래 §1.2) |
| 종료 코드 | 호출 1 = **0** · 호출 2 = **0** (둘 다 리뷰 근거로 유효) |
| 출력 크기 | 호출 1 = 8,500바이트 · 호출 2 = 5,060바이트 |
| verdict | 호출 1 = `needs-attention` · 호출 2 = `needs-attention` |
| job id | **헬퍼 1.0.6 이 stderr 에 job id 를 찍지 않았다.** 대신 thread id 를 남긴다 — 호출 1 `01a01e4b-18eb-73a1-a68e-bf9e53c2cc5e` · 호출 2 `01a01e4b-2ab2-7433-8506-dfd5a7da1ea0`. 사후 회수는 `node <헬퍼> status --all` |
| 재시도 | **0회** (실패 없음) |
| 잘림 | **없음** — 두 출력 모두 `Next steps:` 블록까지 정상 종결 |

### 1.1 스크립트가 stderr 에 찍은 대상 판정 두 줄 (2회 동일)

```
codex-review: 리뷰 대상 = branch diff vs 66f008b
codex-review: 대상 판정 = non-empty (merge-base=66f008bc3203, 변경 파일 62개 (branch 모드는 커밋된 변경만 센다))
```

`--dry-run` 선행 확인도 같은 두 줄을 냈다(종료 코드 6). 리뷰 대상 = `66f008b..16df925`, 커밋 11개 · 변경 파일 62개.

### 1.2 왜 2회로 나눴는가

리더가 지목한 자리는 **7개**다. `codex-review` 스킬 §3.5 규칙 4 가 **"한 번에 3~5개 축까지만 — 열 개를 넣으면 전부
얕게 본다"** 로 상한을 두었으므로, 같은 base 에 대해 축을 나눠 두 번 호출했다. 두 호출은 서로의 결과를 보지 않는다.

| 호출 | 축 | 리더 지목 항목 |
|---|---|---|
| **1** | 4축 — 정규식 SQL 가드의 남은 우회 표면 / 정확 열거 핀이 인구조사인가 은폐형인가 / 계약 확장 필드(K-8·K-9)의 공허 통과 / 본문 유출 카나리의 실제 도달 | ①②④⑤ |
| **2** | 3축 — `POST /documents` 검사 순서와 소유권 은닉 / 계약 개정의 blast radius / 원장(`00_progress.md`)의 사실성 | ③⑥⑦ |

두 호출 모두 `codex-review` 스킬 §4.6(선언 범위 대 실제 도달)을 focus 안에 녹였다 — 이번 변경이 게이트·불변식을
**새로 세우고 넓히는** 변경이라 그 축이 필수다.

### 1.3 실행 명령 (인자 그대로)

```
# 호출 1
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 66f008b "<§2.1 프롬프트 전문>"

# 호출 2
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 66f008b "<§2.2 프롬프트 전문>"
```

스크립트가 해석해 실제로 실행한 명령(stderr 기록, focus 본문은 §2 로 접음):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs adversarial-review --base 66f008b '<focus>'
```

### 1.4 민감 데이터

프롬프트에 **사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다.** 실은 것은 저장소 파일 경로, 계약 조항 이름,
커밋 해시, 로거 클래스 이름뿐이다. codex 는 저장소 읽기 권한으로 `rg`·`nl`·`git`·`javap`·`ruby` 를 직접 돌렸고
(호출 1 = 45회 · 호출 2 = 46회), 그 실행은 전부 읽기 전용이었다.

---

## 2. 전달한 프롬프트 전문

### 2.1 호출 1 focus text

```text
이 저장소는 Python/FastAPI(app/)를 Kotlin/Spring Boot(backend-kotlin/)로 교체하는 중이다. Python 은 참고 구현이지 정답이 아니다 — "같은가"가 아니라 "요구사항을 만족하는가"로 판정하라. 이 저장소의 판정 규칙: 게이트·불변식을 세울 때 "선언한 범위"와 "실제 도달 범위"가 같아야 하고, 초록불 자체를 의심해야 한다. 아래 넷을 적대적으로 본다.

(1) 정규식으로 SQL 을 판정하는 가드의 남은 우회 표면.
backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/OwnershipPredicateGuardTest.kt 와 형제 EnvelopeColumnWriteGuardTest.kt 는 제품 소스(src/main)의 .kt 를 전수로 훑어 SQL 문장을 잘라내고, 같은 디렉터리의 SqlComments.strip 으로 SQL 주석을 걷어낸 뒤 정규식으로 "소유 술어가 있다"를 판정한다. 소유 술어 정규식은 user_id 를 이름 붙은 매개변수 또는 물음표 매개변수와 등호로 묶은 자리이고, 문장 경계는 세미콜론 또는 따옴표다. 이 판정이 실제로는 소유 조건이 없는 질의를 "방어 있음"으로 통과시키는(fail-open) 구체적 입력을 찾아라. 이 가드는 이미 한 번 뚫렸다 — 주석 안에 든 죽은 술어를 방어로 셌다(커밋 d1ce78e 가 그 수정이다). 남은 표면을 실제 입력으로 제시하라: 문자열 연결이나 Kotlin 템플릿 보간으로 조립한 SQL, PostgreSQL 달러 인용, 문자열 리터럴 안의 유니코드 이스케이프나 이스케이프된 따옴표, 문자열 리터럴 안에 있어서 SQL 주석이 아닌데 걷어내지는 두 글자 표시(그 반대 방향도), WHERE 밖에 놓인 술어(SELECT 목록·ORDER BY·서브쿼리·CTE·UNION 의 한쪽 갈래에만 있는 경우), 테이블 별칭 재정의로 소유 열이 다른 테이블을 가리키게 되는 경우, OR 로 무력화된 술어, 소유자가 아닌 값에 결속된 매개변수, 한 문자열 리터럴에 담긴 여러 문장, 그리고 문장 경계를 따옴표로 자르기 때문에 술어와 테이블 참조가 서로 다른 조각으로 갈리는 경우. 뚫리면 남의 문서·변환 행을 내주는 질의가 탐지 없이 들어온다.

(2) "핀은 면제 목록이 아니라 인구조사다"라는 선언이 실제로 참인가.
두 가드는 EXPECTED_STATEMENTS / EXPECTED_UNGUARDED 라는 정확 열거 상수와 비교해 다르면 실패한다. 이 구조가 "목록이 늘면 그 diff 가 리뷰에 올라온다"를 실제로 보장하는지 따져라. 핀 문자열이 파일 경로와 SQL 동사와 테이블 이름만 담기 때문에, 같은 파일 안에서 안전하던 질의가 위험하게 바뀌어도 핀이 그대로여서 초록인 경우가 있는가. 서로 다른 두 문장이 같은 핀으로 접혀 하나가 사라져도 드러나지 않는 경우가 있는가. 실패 메시지가 개발자를 "상수를 기계적으로 갱신"하는 쪽으로 유도하는 구조인가. 이 선언이 거짓이면 이 장치는 탐지형이 아니라 은폐형이고, 그것이 이 저장소가 가장 경계하는 형태다.

(3) 계약 확장 필드 배선이 공허하게 통과하는 경로.
contracts/easy-doc-v1.yaml 이 x-retired-responses(폐기된 상태 코드는 어느 오퍼레이션의 responses 에도 나타나서는 안 된다는 전역 단언)와 x-stored-text-domain(저장 텍스트 도메인 선언)을 새로 두었고, backend-kotlin/api/src/test 아래 support/ContractSpec.kt · DocumentContractNodeTest.kt · ParserNodeRegistryTest.kt 가 그것을 잰다. 목록이 비었을 때, 순회 분모가 0 일 때, YAML 키 이름이 오타나 개명으로 사라졌을 때 초록이 되는 경로를 찾아라. "전역"이라고 선언했는데 실제 순회가 일부 오퍼레이션만 도는 경우, 파서가 없는 키에 조용히 빈 컬렉션을 돌려주고 그 위에서 forEach 가 0 회 도는 경우를 포함한다.

(4) 요청 본문 유출 카나리가 실제로 문서 본문 축을 지나는가.
불변식: 사용자 문서 본문과 개인정보는 어떤 로그 레벨에서도 로그에 남으면 안 된다. backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentBodyLogLeakReachTest.kt 를 읽고, 이것이 강제 TRACE 레벨에서 프레임워크 로거(org.apache.coyote.http11.Http11InputBuffer, org.springframework.jdbc.core.StatementCreatorUtils, org.postgresql.core.v3.QueryExecutorImpl)까지 실제로 도달하는지 판정하라. 그리고 이 장치가 "본문이 찍히지 않는다"를 재는 탐지형인지, 아니면 "그 로거를 껐다/레벨을 조였다"를 재는 은폐형인지 구분해서 답하라. 후자면 운영에서 누군가 레벨을 올리는 순간 그대로 유출이고, 장치는 그때 아무 말도 하지 않는다. backend-kotlin/api/src/main/resources/application.yml 의 로거 고정과 실제로 요청 바이트를 찍는 로거가 일치하는지도 함께 보라.

지적할 때는 파일 경로와 라인을 대고, 그 지적이 참일 때 무엇이 새는지 한 줄로 적어라. 이 저장소는 "인용은 실재하나 전제가 틀린 지적"을 매 회차 대조하므로, 코드를 실제로 읽고 판단하라.
```

### 2.2 호출 2 focus text

```text
이 저장소는 Python/FastAPI(app/)를 Kotlin/Spring Boot(backend-kotlin/)로 교체하는 중이다. Python 은 참고 구현이지 정답이 아니다 — "같은가"가 아니라 "요구사항을 만족하는가"로 판정하라. 아래 셋을 적대적으로 본다.

(1) POST /documents 의 검사 순서와 소유권 은닉.
계약(contracts/easy-doc-v1.yaml 의 POST /documents description)이 정한 순서는 크기 판정 → 텍스트 추출 → 본문 길이 판정 → 작업 공간 소유권 확인 → 저장이다. 구현은 backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentController.kt 와 backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentService.kt 에 나뉘어 있다. 실제 실행 순서가 그 순서인지 코드로 확인하고 어긋나는 경로를 지목하라. 특히 다음을 보라. 컨트롤러가 파트를 읽기 전에 서블릿 멀티파트 파서가 이미 전체 바이트를 임시 파일이나 힙에 올리는가 — 그러면 크기 판정이 첫 단계라는 선언이 실제 자원 소비 순서와 다르다. 상한 더하기 1 바이트만 읽는 readNBytes 방식이 경계에서 정확한가 — 상한과 정확히 같은 크기, 상한 더하기 1, 그리고 스트림이 요청한 만큼을 한 번에 주지 않는 경우를 따져라(잘못되면 상한 초과 파일이 정확히 상한으로 읽혀 통과한다). workspace_id 형식 오류와 작업 공간 소유권 확인의 선후. 그리고 남의 작업 공간 식별자를 주었을 때 404 가 아니라 403·422·500 이 나가거나, 오류 문면·응답 헤더·소요 시간으로 그 작업 공간의 존재가 드러나는 경로. 계약 불변식은 이렇다: 남의 자원은 403 이 아니라 404 로 숨긴다. 오류 본문은 detail 키 하나를 갖는 형태이고 JSON 필드는 snake_case 다. Spring 기본 ProblemDetail 을 노출하지 않는다. 제출한 값(파일명·workspace_id·문서 본문)을 오류 메시지에 담지 않는다 — detail 은 응답 본문이자 액세스 로그에 남는 자리다. 업로드 성공은 201 이 아니라 202 이고 Location 헤더가 폴링 주소를 준다.

(2) 계약 개정의 blast radius.
커밋 dc9ef8e 가 계약을 v1.2.0 에서 v1.3.0 으로 올리며 502(BadGateway) 응답을 폐기하고(x-retired-responses 신설) ServiceUnavailable 스키마 관련 두 줄을 삭제했다. 이어 cd127ea 가 구현과 테스트에서 502 를 걷어냈다(api/error/GlobalExceptionHandler.kt, api 테스트의 ErrorContractTest.kt 와 support/ErrorProbeController.kt, core/exceptions/DomainExceptions.kt, infrastructure/crypto/CryptoConfiguration.kt). 그 폐기된 조항을 근거로 서 있던 것이 저장소에 남아 있는지 찾아라. 남은 502 또는 503 매핑, 이제 어떤 핸들러에도 잡히지 않아 Spring 기본 500 응답으로 새는 예외 타입(그 응답은 계약이 금지한 형태다), /health 와 기동 실패 경로, 계약 안의 x-changelog·x-global-response-headers·x-private-response-headers 같은 확장 필드가 폐기된 조항을 여전히 참조하며 자기모순인 자리, frontend/src/api/types.ts 의 React 타입, 그리고 다른 오퍼레이션의 responses. "503 은 유지된다"와 "ServiceUnavailable 스키마 두 줄은 지웠다"가 동시에 참일 수 있는지 계약 파일에서 직접 확인하라 — 참조가 끊긴 스키마 이름이 남아 있으면 계약 자체가 깨진 문서다.

(3) 원장이 이 세션에 대해 적은 것이 사실인가.
docs/migration/_workspace/00_progress.md 는 무엇이 닫혔고 무엇이 미실행인지를 기록하는 이 프로젝트의 정본이다. 커밋 범위 66f008b..16df925 구간에 대해 이 파일이 "닫혔다"·"완주"·"도달"·"전건 실측"·"통과"로 적은 항목을 골라, 그 근거가 실제 저장소에 있는지 대조하라. 적힌 파일 경로·테스트 이름·커밋 해시가 실재하는가. 실재한다면 그 테스트가 실제로 그 항목이 주장하는 것을 재는가, 아니면 이름만 그럴듯하고 다른 것을 재는가. "미실행"으로 적어야 할 것이 "닫힘"으로 적혀 있지 않은가. 반대로 이 세션이 새로 만든 미결 항목이 원장에 빠져 있지 않은가. 원장이 사실과 갈리면 다음 게이트가 잘못된 전제 위에서 돈다 — 이 저장소에서는 그것이 실제 사고의 형태였다.

지적할 때는 파일 경로와 라인을 대고, 그 지적이 참일 때 무엇이 새는지 한 줄로 적어라. 이 저장소는 "인용은 실재하나 전제가 틀린 지적"을 매 회차 대조하므로, 코드를 실제로 읽고 판단하라.
```

---

## 3. codex 원문 출력 — **무편집**

codex 가 낸 바이트를 그대로 옮긴다. 한 글자도 고치거나 줄이지 않았다.
문면이 이 저장소의 규칙과 어긋나 보여도(예: 불변식을 기본 운영 레벨로 축소하라는 권고) 그대로 둔다.

### 3.1 호출 1 원문 (종료 코드 0 · 8,500바이트)

```text
# Codex Adversarial Review

Target: branch diff against 66f008b
Verdict: needs-attention

출하 불가. 현재 게이트는 소유권 없는 SQL과 봉투 불일치 UPDATE를 초록으로 승인할 수 있고, 계약 확장 범위도 실행 케이스와 닫혀 있지 않다. 로그 카나리는 TRACE 유출 경로를 억제한 INFO 상태만 재므로 “어떤 로그 레벨에서도 유출 금지”를 증명하지 못한다.

Findings:
- [critical] 로그 카나리가 TRACE 유출 경로를 실행하지 않아 억제를 탐지로 오인한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentBodyLogLeakReachTest.kt:72-105)
  테스트는 제품 기본 로그 설정으로 앱을 띄우고 root에 appender만 붙일 뿐 어떤 로거도 TRACE로 올리지 않는다. 양성 대조도 자체 로거의 WARN 한 건이라 위험 로거 도달을 증명하지 않는다. application.yml:112-125의 root INFO가 TRACE를 억제하고, 별도 고정은 `org.springframework.web`뿐이라 실제 원시 요청 로거 `org.apache.coyote.http11.Http11InputBuffer`, JDBC 값 로거 `org.springframework.jdbc.core.StatementCreatorUtils`, PostgreSQL 로거와도 일치하지 않는다. 잠긴 Tomcat 11.0.22의 Http11InputBuffer는 TRACE에서 읽은 ByteBuffer를 문자열로 기록하므로 root TRACE 운영 override만으로 현재 요청 본문과 Authorization 헤더가 노출될 수 있다. 이 장치는 “본문을 찍지 않는다”는 탐지형이 아니라 “현재 INFO라 찍힌 이벤트가 없다”는 은폐형이다. 영향: 로그 레벨 상승 시 문서 본문·제목/파일명·비밀번호·액세스 토큰이 그대로 남는다.
  Recommendation: 위험 네임스페이스와 root를 강제로 TRACE/FINEST로 둔 실제 소켓 카나리를 추가하고 각 프레임워크 로거의 양성 이벤트 도달도 확인하라. 그 상태에서도 최종 로그 싱크에 값이 남지 않도록 외부 logging.level override로 우회할 수 없는 방출 차단·필터링을 구현하거나, 불변식을 기본 운영 레벨로 명시적으로 축소하라.
- [high] 소유 술어 정규식이 대상 행을 제한하지 않는 표현도 방어로 승인한다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/OwnershipPredicateGuardTest.kt:347-441)
  TABLE_REFERENCE와 OWNER_PREDICATE를 같은 문자열 조각에서 독립적으로 찾을 뿐, 술어가 WHERE에 있는지, 대상 테이블 별칭을 가리키는지, 모든 UNION 갈래를 지배하는지 확인하지 않는다. 실제 통과 반례는 `SELECT 'user_id = :ownerId', source_text_encrypted FROM documents`, `SELECT d.user_id = :ownerId AS owned, d.source_text_encrypted FROM documents d`, `... WHERE d.user_id = :ownerId OR TRUE`, 소유 술어가 첫 갈래에만 있는 `UNION ALL`, 그리고 `FROM documents d CROSS JOIN workspaces w WHERE w.user_id = :ownerId`다. 모두 현재 정규식상 `hasOwnerPredicate=true`지만 모든 문서 행을 반환할 수 있다. `FROM $table`처럼 테이블을 Kotlin 보간으로 조립하면 분모에서도 빠진다. 영향: 남의 문서·변환 본문 또는 암호문이 소유권 게이트 변경 없이 반환될 수 있다.
  Recommendation: 연결·보간 SQL을 금지하고 실패시키며, 해석된 단일 SQL을 파싱해 각 SELECT/CTE/UNION 갈래의 대상 별칭이 인증 사용자 매개변수에 의해 WHERE에서 지배되는지 검사하라. 위 반례들을 반드시 실패하는 mutation probe로 고정하라.
- [high] 봉투 열 이름을 문자열 값에 넣으면 실제 대입 없이 Envelope 가드를 통과한다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/EnvelopeColumnWriteGuardTest.kt:315-341)
  `assignsColumn`은 SET 절의 SQL 구조가 아니라 임의 텍스트에서 `column =`를 찾는다. 예를 들어 `UPDATE documents SET source_text_encrypted=:cipher, title='encryption_scheme=:scheme, key_version=:version' WHERE id=:id`는 `setsEnvelope=true`가 되지만 PostgreSQL이 실제로 대입하는 봉투 열은 하나도 없다. SqlComments도 따옴표나 달러 인용을 해석하지 않는다. 영향: 새 암호문이 이전 scheme/key_version과 결합되어 해당 문서가 영구적으로 복호화 불가능해질 수 있다.
  Recommendation: 파싱된 최상위 UPDATE SET 대상 목록에서 실제 열 대입을 확인하라. 작은따옴표·달러 인용·이스케이프·보간·다중 문장 반례를 추가하고 파싱하지 못한 SQL은 실패 처리하라.
- [high] 핀은 문장 인구조사가 아니라 충돌 가능한 요약과 총계다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/OwnershipPredicateGuardTest.kt:322-337)
  Ownership 핀은 파일·동사·서로 다른 테이블 집합만 남긴다. 실제 EXPECTED_STATEMENTS에도 동일한 `JdbcWorkspaceRepository.kt | SELECT [documents]`가 두 번 있어 서로 다른 두 질의를 구별하지 못한다. 한 질의에서 실제 소유 조건을 제거하고 같은 핀의 다른 질의에 조건을 추가하면 EXPECTED_STATEMENTS와 EXPECTED_UNGUARDED가 모두 그대로다. 형제 Envelope 가드는 더 거칠게 파일 집합과 전체 문장 수만 고정하며, 제품 소스가 아닌 테스트 SQL까지 센다. 제품 UPDATE 추가와 테스트 UPDATE 제거가 서로 상쇄될 수 있다. 영향: 사용자 경로의 무소유 조회나 암호화 쓰기 표면 변화가 “인구조사 일치” 초록 뒤에 숨는다.
  Recommendation: 각 문장을 충돌 없는 source span/query ID와 정규화된 전체 SQL digest로 고정하고 중복 핀을 금지하라. Envelope 분모는 src/main만 세고 파일별 정확 문장 ID를 비교해야 한다.
- [high] 저장 텍스트의 measured 팔이 실행 케이스와 결속되지 않았다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentContractNodeTest.kt:146-161)
  테스트는 `measuredArms()`가 하나 이상인지 확인할 뿐 각 field를 실행하지 않고, 나머지는 출력만 한다. ContractSpec도 `status == "measured"`가 아닌 임의 문자열을 모두 pending으로 취급한다. 따라서 한 팔의 `measured` 오타, 기존 팔 삭제, 또는 새 `status: measured` 필드 추가가 JSON 붙여넣기 하나만 하드코딩한 실행 테스트를 늘리지 않은 채 초록이다. 현재 PDF 동작을 별도 테스트가 재더라도 이 레지스트리의 field 값과는 기계적으로 연결되지 않는다. 영향: 새 저장 본문 경로가 PlainBody 검증을 우회해 비가역 문자 치환을 저장해도 계약 게이트가 이를 재지 않을 수 있다.
  Recommendation: 상태 값을 닫힌 enum으로 검증하고 field ID의 중복·공백·미등록 값을 거부하라. 선언된 measured field ID 집합과 실제 실행된 시나리오 ID 집합을 정확 일치시켜 각 팔이 422, detail 모양, 비저장을 직접 증명하게 하라.
- [medium] 전역 retired-response 순회가 유효한 Path Item 참조를 조용히 제외한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:127-135)
  operations()는 각 path item의 로컬 키 중 고정 HTTP 메서드만 고르고 `$ref`를 해석하지 않는다. OpenAPI 3.1에서 `/documents: {$ref: '#/components/pathItems/Documents'}`로 옮기고 그 참조 대상 POST responses에 `502`를 넣으면 이 경로는 순회에서 사라진다. 다른 오퍼레이션들이 남아 있으므로 declaredResponseStatuses의 비어 있지 않음 단언도 통과한다. 영향: 폐기된 상태 코드가 일부 오퍼레이션 계약에 복원되어도 전역 게이트가 초록으로 남아 클라이언트 오류 처리 계약이 갈릴 수 있다.
  Recommendation: Path Item `$ref`를 해석해 순회하거나 계약에서 이를 명시적으로 금지하라. 해석 후 정확한 `(path, method)` 집합을 핀으로 고정하고 이해하지 못한 path-item 키나 참조는 실패 처리하라.

Next steps:
- SQL 가드를 구조 파서 또는 정형화된 쿼리 선언으로 교체하고 제시된 우회 입력을 mutation 테스트로 고정한다.
- 핀을 충돌 없는 문장 ID와 전체 SQL digest로 바꾸고 제품 소스 분모를 정확히 고정한다.
- 계약의 operations와 stored-text 팔을 실제 실행 시나리오 집합에 정확 결속한다.
- 강제 TRACE 상태에서 문서·자격증명 카나리 0건을 재현하기 전에는 출하하지 않는다.
```

### 3.2 호출 2 원문 (종료 코드 0 · 5,060바이트)

```text
# Codex Adversarial Review

Target: branch diff against 66f008b
Verdict: needs-attention

출하 불가. 업로드의 실제 자원 소비·검사 순서가 계약과 다르고, v1.3의 503 설명과 진행 원장이 현재 계약 파일과 모순되며, /health는 의존성 장애를 정상으로 보고한다.

Findings:
- [high] 인증과 크기 판정보다 먼저 전체 multipart를 파싱한다 (backend-kotlin/api/src/main/resources/application.yml:57-60)
  `resolve-lazily: false` 때문에 Spring의 `DispatcherServlet.checkMultipart()`가 핸들러와 인증 `HandlerInterceptor`에 도달하기 전에 `request.getParts()`를 호출한다. 따라서 파일은 `readNBytes` 이전에 이미 서블릿 임시 저장소로 스풀되며, 비인증 요청도 디스크·I/O를 소비하고 컨테이너 상한 초과 요청은 계약상 선행해야 할 401 대신 413을 노출할 수 있다. 인증된 요청은 이후 최대 10 MiB+1을 다시 힙으로 복사한다. 결과적으로 오류 상태로 인증 여부 외의 검증 결과가 새고, 비인증 자원 고갈 경로가 열린다.
  Recommendation: 최소한 lazy multipart resolution으로 인증을 파싱보다 앞세우고, 실제 컨테이너를 사용해 비인증 multipart가 임시 파일을 만들지 않고 항상 401인지 검증하라. 크기 판정을 최초 바이트 소비 단계로 보장하려면 전체 스풀 전에 제한하는 스트리밍 resolver 또는 동등한 컨테이너 계층 제한을 도입하라.
- [high] 잘못된 workspace_id가 선행해야 할 파일 오류를 422로 덮는다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentController.kt:115-121)
  Kotlin 인수 평가 순서상 `readBounded(file)` 다음 `parseWorkspaceId(...)`가 실행된 뒤에야 `DocumentService.createFromFile`로 진입한다. 그러므로 10 MiB+1 파일과 잘못된 UUID를 함께 보내면 서비스의 크기 검사에 도달하지 못하고 413 대신 422가 반환된다. 손상된 파일이나 추출 본문 초과도 같은 방식으로 workspace 형식 오류에 가려져, 계약의 크기→추출→본문 길이→소유권 순서를 위반하고 오류 detail·타이밍을 잘못된 분기로 노출한다.
  Recommendation: 원시 workspace_id를 애플리케이션 계층까지 전달하고 크기·추출·본문 길이 검사가 끝난 뒤 파싱 및 소유권 조회를 수행하라. 최대+1, 손상 파일, 추출 본문 초과 각각을 잘못된 workspace_id와 결합한 우선순위 테스트를 추가하라.
- [high] ServiceUnavailable의 DB 조항이 삭제됐지만 계약과 원장은 삭제되지 않았다고 주장한다 (contracts/easy-doc-v1.yaml:1859-1875)
  활성 설명에는 JWT 설정 실패만 남아 있고, 폐기 목록의 DB 세션 팩토리 문장은 취소선으로 제거된 동시에 “내리지 않았다”라고 적혀 있다. 실제 dc9ef8e diff는 기존 네 원인 중 암호화·큐·DB 세 줄을 삭제했지만 `x-changelog` 2372-2381행과 `00_progress.md` 2015행은 두 줄만 삭제하고 DB는 유지했다고 기록한다. 따라서 DB 장애가 503인지, 기동 실패인지, 기본 500인지 정본 자체로 결정할 수 없으며 후속 구현·게이트가 서로 다른 전제로 통과할 수 있다.
  Recommendation: DB 미배선·장애를 실제 기동 및 요청 테스트로 판정한 뒤 503 원인을 복구하거나 명시적으로 폐기하라. 활성 스키마 설명, 폐기 목록, x-changelog, 00_progress.md를 같은 결론으로 수정하고 활성 503 원인 집합을 검사하는 계약 테스트를 추가하라.
- [high] /health가 DB·큐 장애를 무조건 정상으로 은닉한다 (contracts/easy-doc-v1.yaml:1680-1699)
  계약은 `database`와 `queue` 진단을 포함하고 결과에 따라 status를 산출하라고 요구하지만, `HealthController.kt` 35-47행은 검사 없이 `{"status":"ok"}`만 반환한다. 계약 설명에는 현재 PostgreSQL lease queue와 맞지 않는 Redis 문구도 남아 있다. DB나 큐가 기동 후 고장 나도 헬스 체크는 계속 정상으로 보여 트래픽 차단과 장애 탐지를 막으며, 응답 형태도 계약의 필수 `checks` 필드를 충족하지 않는다.
  Recommendation: DB와 PostgreSQL lease queue를 실제로 점검하고 `checks`에서 status를 도출하도록 구현한 뒤 장애 주입 테스트를 추가하라. 진단형 health가 의도가 아니라면 런타임과 일치하도록 계약을 정식 개정하고 Redis 및 헤더 관련 잔여 문구를 제거하라.

Next steps:
- multipart 파싱을 인증 및 스트리밍 크기 제한 뒤로 옮기고 실제 서블릿 컨테이너 테스트를 추가한다.
- workspace_id 파싱을 계약상 네 번째 단계로 이동하고 복합 오류 우선순위를 검증한다.
- v1.3의 DB/503 정책과 /health 동작을 하나의 실행 가능한 계약으로 확정한다.
- 00_progress.md에 위 미결 항목을 기록하고 삭제된 DB 조항 및 실측 범위를 현재 저장소 사실과 맞춘다.
```

---

## 4. 정리(가공) — **판정이 아니다**

이 구획은 §3 원문과 **분리된 가공물**이다. 여기서도 옳고 그름·심각도 환산·중복 병합은 하지 않는다. 하는 일은 셋이다 —
지적을 번호로 세우는 것, 리더가 지목한 7자리에 무엇이 돌아왔는지 대조하는 것, **인용이 실재하는지만** 확인하는 것.

### 4.1 지적 목록 (라벨·문면 모두 codex 원문 그대로)

| # | 호출 | 라벨(원문) | 지적 요지(원문 제목 그대로) | codex 가 댄 근거 위치 |
|---|---|---|---|---|
| **F-1** | 1 | `critical` | 로그 카나리가 TRACE 유출 경로를 실행하지 않아 억제를 탐지로 오인한다 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentBodyLogLeakReachTest.kt:72-105` · `application.yml:112-125` |
| **F-2** | 1 | `high` | 소유 술어 정규식이 대상 행을 제한하지 않는 표현도 방어로 승인한다 | `.../db/OwnershipPredicateGuardTest.kt:347-441` |
| **F-3** | 1 | `high` | 봉투 열 이름을 문자열 값에 넣으면 실제 대입 없이 Envelope 가드를 통과한다 | `.../db/EnvelopeColumnWriteGuardTest.kt:315-341` |
| **F-4** | 1 | `high` | 핀은 문장 인구조사가 아니라 충돌 가능한 요약과 총계다 | `.../db/OwnershipPredicateGuardTest.kt:322-337` |
| **F-5** | 1 | `high` | 저장 텍스트의 measured 팔이 실행 케이스와 결속되지 않았다 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentContractNodeTest.kt:146-161` |
| **F-6** | 1 | `medium` | 전역 retired-response 순회가 유효한 Path Item 참조를 조용히 제외한다 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:127-135` |
| **F-7** | 2 | `high` | 인증과 크기 판정보다 먼저 전체 multipart 를 파싱한다 | `backend-kotlin/api/src/main/resources/application.yml:57-60` |
| **F-8** | 2 | `high` | 잘못된 `workspace_id` 가 선행해야 할 파일 오류를 422 로 덮는다 | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/DocumentController.kt:115-121` |
| **F-9** | 2 | `high` | `ServiceUnavailable` 의 DB 조항이 삭제됐지만 계약과 원장은 삭제되지 않았다고 주장한다 | `contracts/easy-doc-v1.yaml:1859-1875` (+ `x-changelog:2372-2381` · `00_progress.md:2015`) |
| **F-10** | 2 | `high` | `/health` 가 DB·큐 장애를 무조건 정상으로 은닉한다 | `contracts/easy-doc-v1.yaml:1680-1699` · `HealthController.kt:35-47` |

라벨 분포(원문): `critical` 1 · `high` 8 · `medium` 1. 두 호출 모두 verdict `needs-attention`("출하 불가").

### 4.2 리더 지목 7자리 대 codex 응답 — 어디를 봤고 어디는 말이 없는가

**대조표일 뿐 채점표가 아니다.** "말이 없다"가 "문제가 없다"는 뜻이 아니라는 것은 `codex-review` 스킬 §7 이 못박은
그대로이고, 반대로 codex 가 답한 자리라고 해서 그 답이 옳다는 뜻도 아니다.

| 리더 지목 | codex 응답 | 비고 |
|---|---|---|
| ① 세 파일(`OwnershipPredicateGuardTest`·`EnvelopeColumnWriteGuardTest`·`SqlComments`)이 **또 뚫리는 길** | **F-2 · F-3** | 리더가 예시로 든 표면 중 **문자열 조립·별칭 재정의·`WHERE` 밖 술어**를 codex 가 반례로 제시했고, **작은따옴표 문자열 리터럴 안의 열 이름**은 리더 목록에 없던 형태다. `SqlComments` 자체를 단독으로 지적한 항목은 없고 F-3 안에서 "따옴표·달러 인용을 해석하지 않는다"로 언급된다 |
| ② 정확 열거 핀이 **은폐형으로 굴러가는가** | **F-4** | codex 는 "인구조사"라는 선언 자체를 겨냥해 답했다. 형제 장치(Envelope)의 분모가 `src/main` 이 아니라는 별개 주장을 함께 얹었다 |
| ③ `POST /documents` **검사 순서** | **F-7 · F-8** | F-8 은 리더가 물은 계약 순서(크기→추출→길이→소유권)를 정면으로 답했다. F-7 은 리더가 물은 「순서」를 **자원 소비 순서**로 확장해 답한 것이다. **`readNBytes` 경계값**(상한 정확히 / 상한+1 / 부분 읽기)은 물었으나 **단독 지적으로 돌아오지 않았다** |
| ④ K-8·K-9 의 **공허 통과** | **F-6**(K-8) · **F-5**(K-9) | 둘 다 답이 왔다. 다만 codex 가 든 K-8 의 공허 경로는 리더가 예시한 "빈 목록·빈 분모"가 아니라 **Path Item `$ref`** 다 |
| ⑤ **표 18 TRACE 카나리** | **F-1** | 리더가 물은 두 갈래(문서 본문 축을 지나는가 / 은폐형으로 굴렀는가) 중 codex 는 **후자**로 답했다 |
| ⑥ **계약 개정의 blast radius** | **F-9 · F-10** | codex 가 실제로 밟은 것은 리더가 예시한 502 잔여가 아니라 **503 쪽의 문면 불일치**와 `/health` 다. `frontend/src/api/types.ts`·`x-changelog` 잔여 502 를 **결함으로 든 항목은 없다** |
| ⑦ **원장의 사실성** | **F-9 안에서만** | `00_progress.md` 를 단독으로 겨눈 지적은 나오지 않았다. F-9 가 원장 2015행을 계약·changelog 와 함께 「셋이 서로 다른 결론」으로 묶어 인용한다 |

**리더가 지목하지 않았는데 나온 것**: 없다 — F-10(`/health`)은 리더 지목 ⑥ 안에 내가 focus 로 넣은 항목이다.

**codex 가 아무 말도 하지 않은 자리**(있는 그대로 적는다):

- 리더가 "틀렸다고 보이면 최우선 지적"이라 한 **두 값**(`build --rerun-tasks` exit 0 · 79/79 executed, 개인정보 스캐너 exit 0 · BLOCK 0) — **두 호출 어느 쪽도 언급하지 않았다.** codex 는 Gradle 을 돌리지 않았다.
- `8e94847`(`core/text/Surrogates.kt` 신설, `StoredContent` 사본 판정 제거) — 지적 0건.
- `cd127ea` 의 502 제거 자체 — 지적 0건.
- `5038968` 의 `DocumentPorts` 문면 정정 — 지적 0건.
- 문서 커밋 4건(`c981173`·`4c719d3`·`1ee27b3`·`837005f`·`16df925`)의 원장 기록 — F-9 이 인용한 한 줄 외에 지적 0건.

### 4.3 전제 확인 — **인용이 실재하는가만** 잰다

이 저장소가 매 회차 대조하는 것이 「인용은 실재하나 전제가 틀린 지적」이므로, 인용 위치가 실재하고 거기에
codex 가 말한 구조물이 있는지를 **사실로만** 확인했다. **지적이 옳은지는 판정하지 않았다** — 그 판정은 2·3단계 몫이다.

| # | 인용 실재 | 확인한 사실(무판정) |
|---|---|---|
| F-1 | **실재** | `DocumentBodyLogLeakReachTest.kt` 는 `@SpringBootTest(webEnvironment = RANDOM_PORT)` 로 뜨고 root 로거에 `ListAppender` 를 붙인다. 테스트 안에 로그 레벨을 올리는 코드는 없다. 그 파일 KDoc 이 스스로 *"제품 기본 로그 구성 그대로 앱을 띄우고"* 라 적는다. 양성 대조는 자기 로거의 `warn(POSITIVE_CONTROL_MARKER)` 한 건이다. `application.yml` 의 `logging.level` 은 `root: INFO` · `kr.easydoc: INFO` · `org.springframework.web: INFO` · `org.springframework.security: INFO` · `org.flywaydb: INFO` 다 — codex 가 든 세 프레임워크 로거 이름은 이 목록에 **없다** |
| F-2 | **실재** | `TABLE_REFERENCE` 와 `OWNER_PREDICATE` 는 서로 다른 정규식이고 같은 `chunk` 에서 독립으로 찾는다(`accessOf`). `OWNER_PREDICATE` 는 `(?<![A-Za-z0-9_])user_id\s*=\s*(?::[A-Za-z_]\w*|\?)` 이며 `WHERE` 절 여부·별칭·`UNION` 갈래를 보지 않는다. `TABLE_REFERENCE` 는 `FROM|JOIN|UPDATE|INTO` 뒤의 **리터럴 테이블 이름**만 잡는다 |
| F-3 | **실재** | `assignsColumn` 은 `Regex("""(?<![A-Za-z0-9_])$column\s*=""")` 를 SET 절 **텍스트**에 건다. `SqlComments.strip` 은 KDoc 에서 스스로 적듯 `--` 와 블록 주석만 다루고 **작은따옴표 문자열 리터럴·달러 인용을 해석하지 않는다** |
| F-4 | **실재** | `pin` 은 `"$file \| $verb [테이블목록]"` 이다. **`EXPECTED_STATEMENTS` 에 완전히 동일한 문자열이 실제로 두 번 들어 있다** — `…/auth/JdbcWorkspaceRepository.kt \| SELECT [documents]` × 2 |
| F-5 | **실재** | 해당 테스트는 `domain.measuredArms().map { it.field }` 에 `isNotEmpty()` 만 걸고, `pendingArms()` 는 `println` 으로 출력한다(단언 없음) |
| F-6 | **실재** | `ContractSpec.operations()` 는 `map("paths")` 의 각 항목에서 키를 `HTTP_METHODS` 로 거른다. `$ref` 를 해석하는 코드는 없다 |
| F-7 | **실재** | `application.yml` 에 `resolve-lazily: false` 가 있고, 바로 위 주석이 **켜지 않은 이유**를 적는다(*"켜면 상한 초과 예외가 `checkMultipart` 가 아니라 핸들러 안에서 나고, 그러면 예외가 나는 시점이 인증·라우팅 뒤로 밀려…"*). 즉 이 설정은 의도된 선택으로 문서화돼 있다 — codex 는 그 선택의 **결과**를 문제 삼는다 |
| F-8 | **실재** | `createFromFile` 의 명명 인자 목록에서 `bytes = readBounded(file)` 가 `workspaceId = parseWorkspaceId(...)` **앞에 적혀 있다**. `parseWorkspaceId` 는 형식 오류 시 `InvalidInputException` 을 던진다. 서비스의 크기 판정은 `DocumentService.createFromFile` 첫 줄(`if (bytes.size > MAX_UPLOAD_BYTES) throw UploadTooLargeException`)이다 |
| F-9 | **실재 · 인용 넷 모두** | ⑴ HEAD 의 활성 `ServiceUnavailable` 설명(1846-1853행)은 원인을 **JWT 하나만** 열거하고 *"이 상태가 되는 구성은 하나뿐이다"* 라 적는다. `66f008b` 판은 **넷**을 열거했다(JWT·저장 암호화 키·큐(Redis)·DB 세션 팩토리). ⑵ 그 아래 폐기 블록의 머리글은 *"2026-08-20에 **세 줄**을 내렸다"* 인데 셋째 항목은 **취소선이 그어진 채** 본문이 *"**내리지 않았다. 재지 않았기 때문이다.**"* 다. ⑶ `x-changelog` 는 *"`ServiceUnavailable` 에서 도달 0인 **두 줄**과 대응 예시를 내렸다"* · *"넷째 줄(DB 세션 팩토리)은 재지 않았으므로 그대로 두었다"* 로 적는다. ⑷ `00_progress.md` L-⑦ 에 *"O-21 의 DB 줄 미측정 — 재지 않았으므로 지우지 않았다"* 가 있다 |
| F-10 | **실재** | 계약 `/health` description 은 *"**Kotlin은 진단하는 쪽으로 구현한다**"* 와 Redis 문면을 담고 있다. `HealthController` 는 `HealthResponse(status = "ok")` 하나를 반환하며 검사 코드가 없다. `HealthResponse` 스키마는 `required: [status, checks]`(계약 2328행)이고 Kotlin `HealthResponse` 는 `data class HealthResponse(val status: String)` 로 **`checks` 필드가 없다**. 같은 description 이 *"구현 시점: 진단 대상이 실제로 배선되는 Phase에 맞춘다(DB Phase 3, 큐·Redis Phase 5)"* 와 *"확인 못 하는 의존 서비스는 `checks` 에서 **키 자체를 생략**한다"* 도 함께 적는다 — 이 두 문장과 codex 지적의 관계는 **판정하지 않는다** |

**전제 확인 결과 요약: 인용 위치가 실재하지 않는 지적은 0건이다.** 인용이 실재한다는 것과 지적이 옳다는 것은 다른
문제이며, 그 갈림은 이 문서가 판정하지 않는다.

---

## 5. 미실행·실패 항목

**실패 0건.** codex 호출 2회 모두 종료 코드 `0`, 출력 비어 있지 않음, 잘림 없음, 재시도 0회. `⚠ codex 리뷰 누락`
사유는 없다.

**이 에이전트가 이 회차에 실행하지 않은 것** (돌린 것처럼 적지 않는다):

- **Gradle 빌드·테스트를 돌리지 않았다.** 리더가 보고한 `build --rerun-tasks` **exit 0 · 79/79 executed** 를 재실행하지
  않았고, **codex 도 그 값에 관해 아무 말도 하지 않았다.** 그러므로 이 문서에는 그 값을 확인했다는 근거가 없다.
- **개인정보 스캐너를 돌리지 않았다.** 리더가 보고한 **exit 0 · BLOCK 0** 도 같다 — 재실행 0, codex 언급 0.
- **계약 검증 스크립트·`pytest`·`mypy`·`ruff` 미실행.**
- **`.claude/skills/kotlin-migration/scripts/run_gate.sh` 를 쓰지 않았다** — 게이트 명령을 돌리지 않았으므로 부를 일이
  없었다. 파이프를 쓴 게이트 명령도 없다.
- **제품 코드·계약·`00_progress.md` 를 한 바이트도 고치지 않았다.** 이 역할은 리뷰만 한다.
- **codex 가 든 반례를 실제로 실행해 보지 않았다.** F-2·F-3 의 우회 입력은 codex 가 정규식을 읽고(그리고 `ruby` 로
  일부 모사해) 구성한 것이며, 이 에이전트가 Kotlin 가드에 먹여 재현하지 않았다. 재현은 2·3단계 또는 구현 레인의 몫이다.
- **이전 회차(`04_documents_*`)를 맥락으로 프롬프트에 넣지 않았다.** 리더가 이 회차를 **세션 전부**(`66f008b..16df925`,
  62파일)에 대한 새 리뷰로 지정했고, `codex-reviewer` 재호출 지침이 *"리뷰 대상 코드가 그 사이에 크게 바뀌었으면
  이전 회차를 맥락으로 주는 대신 새 리뷰로 취급한다"* 로 그 경우를 규정한다.

**다음 단계**: 이 파일과 `04_documents-c3_migration-reviewer.md` 두 개가 모두 존재하는지 확인한 뒤(2단계),
`migration-reviewer` 를 **재호출**해 `codex-review` 스킬 §5 표로 대조한 `04_documents-c3_cross.md` 를 만든다(3단계).
심각도 환산은 그 호출에서 처음 일어난다.
