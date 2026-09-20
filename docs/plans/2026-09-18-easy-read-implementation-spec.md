# 쉬운글 개선 구현 명세

작성일: 2026-09-18 · 상태: **R1과 R2 로컬 기능(ER-07) 구현 반영, ER-08 이후 제안 명세**. 현행 계약 `easy-doc-v1.yaml` 2.40.0에 행동 안내 작업·후보·저장·TXT 출력 경로를 반영하고 사용자 화면을 fake E2E로 검증했다. 기본 OFF이며 배포·실제 모델 품질 평가는 아직 수행하지 않았다.

연결: [로드맵](2026-09-18-easy-read-improvement-roadmap.md) · [작업 분할](2026-09-18-easy-read-delivery-plan.md) · [UX](2026-09-18-easy-read-ux-spec.md) · [인수 기준](2026-09-18-easy-read-validation-release.md).

## 1. 요구사항과 비범위

| ID | 요구사항 | 단계 |
|---|---|---|
| REQ-01 | 본문 변경 시 검수·안내문·설명 결과가 이전 버전임을 서버가 판정 | R1 기반 |
| REQ-02 | 누락 의심 신호와 담당자 관계 확인 항목을 구분해 제공 | R1 |
| REQ-03 | 원문 근거가 있는 행동 안내를 별도 생성·편집·저장 | R2 |
| REQ-04 | 공식 명칭 보존 + 첫 등장 문맥 설명 | R3 |
| REQ-05 | 지원되는 표의 대상·단위·행/열·각주 관계 제공 | R4 |
| REQ-06 | 버전별 담당자 확인 이벤트와 변경 이력 | R5 |
| REQ-07 | 필수 정보를 숨기지 않는 선택적 추가 설명 | R6 |
| REQ-08 | 의미와 이용 권리를 검수한 그림·배치 | R7 |
| REQ-09 | 소유권·만료·암호화·과금·중복 실행 방지·관측 | 전 단계 |

비범위: 자격 자동 판정, 법령 실시간 검색, 기관명·날짜의 자동 사실 수정, 개인별 장애 진단, OCR, PDF 생성, 협업 권한·공유 링크, 기존 문서 일괄 재생성. R7의 새 출력 형식은 별도 범위 결정 전까지 포함하지 않는다.

## 2. 기존 코드 연결점

경로는 저장소 루트 기준이다. 기존 파일의 역할을 참고하고 새 책임은 작은 별도 클래스로 분리한다.

| 영역 | 확인한 파일 | 예정 변경 |
|---|---|---|
| 생성·보정 | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt` | 기존 2회 상한 유지, R3 생성 규칙 적용 |
| 규칙·프롬프트 | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/FactPreservation.kt`, `Prompts.kt` | 현행 사실 비교 재사용, 위치를 갖는 검수 신호와 설명 지침 |
| 저장·조회 | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/ConversionReviewService.kt`, `ConversionQueryService.kt` | 버전 비교와 파생 결과 무효화 |
| DB | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/document/JdbcConversionRepository.kt` | CAS/버전·새 저장 포트 adapter |
| API | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/document/ConversionController.kt`, `ConversionDtos.kt` | 버전 필드와 새 자원 컨트롤러 분리 |
| 문서 구조 | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/segment/UnitKind.kt`, `SourceUnits.kt` | 기존 줄 좌표 유지, R4 표 관계 별도 모델 |
| 파서 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/ingest/DocumentExtractors.kt` | 지원 표의 구조 메타 추출 |
| UI·API | `frontend/src/components/ReviewEditor.tsx`, `frontend/src/api/client.ts`, `frontend/src/api/types.ts` | 기존 편집 흐름 + 분리된 검수/안내문 컴포넌트 |
| 문단 대응 | `frontend/src/review/unitMap.ts`, `fingerprint.ts` | 로컬 dirty 처리 재사용, 서버 버전과 구분 |
| 검증 | `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/quality/GoldenCorpusLlmEvaluationTest.kt`, `frontend/e2e/conversion-flow.spec.ts` | 기존 평가·E2E 경로 확장 |

core는 Spring/DB/HTTP에 의존하지 않는다. application은 유스케이스와 port, infrastructure는 암호화·DB·LLM·큐, api/worker는 진입점만 맡는다. 새 타입명·테이블명은 아래 설계명이며 현재 존재하는 것으로 해석하지 않는다.

## 3. 공통 버전·원문 위치

### 3.1 본문 버전

- `ConversionResponse.content_revision`: 서버가 관리하는 정수. 완료 전 0, 첫 완료 1. 기존 완료 행은 마이그레이션에서 1로 초기화한다. JavaScript의 안전한 정수 범위를 넘지 않는다.
- `ConversionResponse.review_capabilities`: `review_support, action_guide, table_relations, review_history, explanations, illustrations` boolean을 갖는 제안 필드. 서버 설정과 지원 범위에서 유도하며 UI는 이것으로 노출을 결정한다. 구버전 응답에 필드가 없으면 모두 false로 처리한다. 별도 프런트 환경변수만으로 유료 생성 버튼을 켜지 않는다.
- 유효 본문은 `edited_text ?? easy_text`. 현행 제어문자 제거·개행 정규화 이후 이 값이 달라질 때만 버전을 1 올린다.
- `PUT /conversions/{id}`에 선택 필드 `expected_content_revision`을 추가한다. 새 UI는 항상 보낸다. 소유자·만료·done 확인 후 잠금 안에서 불일치이면 409, 본문을 쓰지 않는다.
- 레거시 클라이언트의 필드 없는 저장은 유지하되 동일한 버전 증가·파생 결과 무효화를 실행한다. 레거시 저장에는 낙관적 충돌 방지 보장이 없음을 명시한다.
- 같은 본문의 재저장은 버전을 올리지 않는다. `reviewed_at`은 기존 의미대로 저장 시각이며 ‘모든 항목 검수 완료’의 증명이 아니다.
- 본문 저장과 R1 확인 상태/R2 안내문의 무효화는 한 트랜잭션이다. 내보내기도 현재 버전과 검수본을 같은 일관된 읽기로 판정한다.
- 재변환 후보의 도착·미리보기는 서버 버전을 바꾸지 않는다. 후보 적용은 로컬 dirty, 본문 저장 성공 시 서버 버전이 바뀐다.
- 기존 `easy_text_fingerprint`는 그대로 로컬 응답 식별용이다. 새 CAS를 대신하지 않는다.

### 3.2 원문 위치

R1/R2는 저장된 추출 원문을 `splitUnits`로 나눈 0 기반 줄 위치를 쓴다. 원본 파일의 페이지 번호나 법령 조항 번호로 표시하지 않는다. 원문은 문서 수명 동안 불변으로 취급한다. 재업로드는 새 문서다.

`SourceAnchor = { source_unit_indexes, quote }`로 제안한다. indexes는 정렬·중복 없는 유효 범위 목록, quote는 해당 범위에서 실제 찾은 인용이다. 같은 표현이 반복되면 가능한 위치를 모두 보존하거나 ‘위치 특정 불가’로 표시한다. 기존 `FactIssue.value`만으로 첫 검색 결과를 확정 근거로 삼지 않는다. 인용은 저장·로그 정책에서 본문으로 취급한다.

쉬운 글 위치는 `content_revision`과 함께만 유효하다. `segment_map` 신뢰도가 낮거나 없으면 원문 위치만 연결하고 본문 전체 비교로 안내한다. 200단위 초과에서도 체크리스트 사용은 가능해야 한다.

## 4. R1 검수 지원

### 4.1 진단과 사람 확인

`ReviewItem.kind`는 `missing_fact | relation_check`이다. 누락 의심은 기존 규칙 검사 결과로 만들고, 관계 확인은 ‘대상’, ‘모두/하나’, ‘예외’, ‘기한과 행동’, ‘금액과 적용 대상’의 고정 5항목을 항상 제공한다. 규칙으로 찾은 조건 표현은 관련 원문을 보여 주는 보조 신호이며 AND/OR 동등성을 증명하지 않는다.

- `item_id`: 분석 스냅샷 내부 UUID. 본문 조각을 식별자로 사용하지 않는다.
- `rule_code`: 제한된 규칙 식별자. 사용자에게는 별도 한국어 설명을 보여 준다.
- `source_anchors`, `easy_unit_indexes`: 위치를 모르면 빈 배열. 없는 위치를 추정하지 않는다.
- `state`: `needs_review | confirmed | not_applicable`.
- `reason`: 최대 500자 선택 메모. `not_applicable`에는 필수. 모호한 원문은 해결된 것으로 처리하지 않고 `needs_review`와 메모로 남긴다.
- `confirmed_by`, `confirmed_at`: 서버 인증 사용자와 서버 시각. 요청이 지정하지 못한다.

검수 항목이 모두 확인돼도 ‘담당자가 표시된 항목을 확인함’만 의미한다. 누락 신호 0건도 의미 정확도 100%가 아니다. 추가 LLM 호출과 크레딧 차감은 0이다.

### 4.2 분석 스냅샷

`ReviewAssessment`는 `assessment_id`, `content_revision`, `analyzer_version`, `review_revision`, `coverage`, `items`를 갖는다. `coverage`는 `supported | limited`이며 제한 사유는 `mapping_unavailable | signal_limit | ambiguous_source` 배열이다. `supported`도 정해진 규칙을 실행했다는 뜻이다.

신호는 최대 100개 + 고정 5항목. 초과하면 100개만 노출하고 `signal_limit`을 명시한다. 문서 전체 확인 안내는 유지한다. 분석 스냅샷은 같은 conversion/content_revision/analyzer_version 조합에 하나만 만든다. 분석기 버전이 바뀌면 새 스냅샷을 만들고 이전 확인 상태를 이월하지 않는다.

GET은 저장된 상태만 읽는다. POST 분석은 명시적 호출로 최신 저장 본문을 동기 분석한다. 클라이언트는 최초 패널 진입 시 자동으로 이 무과금 POST를 호출할 수 있다. 분석 중 본문 버전이 바뀌면 결과 커밋 전 비교하여 409로 반환한다.

### 4.3 제안 API

아래 경로는 모두 `/conversions/{conversion_id}` 아래다. JSON은 snake_case, bearer 인증, 본문 응답은 `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`를 유지한다.

| 메서드·경로 | 입력 | 성공 응답 | 충돌·예외 |
|---|---|---|---|
| `GET /review-support` | 없음 | 200 `{status: not_generated, assessment: null}` 또는 assessment. status는 ready/stale, 이전 본문/분석기 스냅샷만 있으면 stale | 타인·만료·삭제·토글 OFF 404 |
| `POST /review-support` | `{expected_content_revision}` | 200 최신 분석. 같은 버전/분석기 요청은 기존 스냅샷 반환 | 미완료·버전 충돌 409 |
| `PUT /review-support/items/{item_id}` | `{assessment_id, expected_content_revision, expected_review_revision, state, reason}` | 200 갱신된 전체 assessment, review_revision 증가 | 본문/분석/확인 버전 충돌 409, 없는 item 404 |

구문·필수값·크기 오류는 422, 인증 실패 401, 저장 실패 500/503이며 기존 오류 본문 `{detail}`을 따른다. 409의 `detail`은 ‘본문이 바뀌었습니다. 다시 불러와 주세요’ 또는 ‘검수 표시가 바뀌었습니다. 다시 불러와 주세요’로 명시하고, 서버 상태를 재조회해 화면을 복구한다. 기존 오류 schema 전체를 새 체계로 바꾸지 않는다.

중복 확인 요청에서 이미 같은 state/reason이고 기대 revision만 오래됐다면 새 쓰기를 하지 않고 409 후 재조회한다. 네트워크 불명확 실패에도 UI가 임의로 완료 처리하지 않는다.

## 5. R2 행동 안내

### 5.1 생성 정책

변환 본문과 별도 자원 `ActionGuide`로 둔다. 본문에 자동 삽입하지 않는다. 생성은 done 상태의 **저장된 현재 본문**과 불변 원문을 입력으로 사용한다. 원문을 사실의 기준으로 삼으며 두 입력이 충돌하면 `needs_review`로 표시한다.

한 요청당 LLM 호출 최대 1회, 자동 보정·재시도 0회. 기존 `ConvertDocumentUseCase`를 억지로 재사용하여 2회 호출하게 하지 않고 같은 provider port를 쓰는 별도 `GenerateActionGuideUseCase`를 둔다. 원장의 purpose에는 `action_guide`를 추가하며 DB 제약·집계·관리자 DTO·API 소비자·테스트에 미치는 영향을 함께 조사·반영한다.

ER-05~ER-07에는 D03의 크레딧 예약·정산과 D04의 문서당 provider 시작 작업 3회 상한이 구현됐다. 이는 코드 기본값이며 대외 과금·운영 정책 승인은 아직 없다. R2 제한 노출 전 [검증·출시 계획 §6.1](2026-09-18-easy-read-validation-release.md#61-r2-정책-결정-기록--제한-노출-선행)에 결정과 근거를 기록한다. 현행 기본값은 기존 원문 글자 수 크레딧 산식 1회분을 예약하고, 검증된 안내문 후보를 현재 본문 버전으로 저장해 제공했을 때 소비한다. 제공 전 실패·stale·만료는 예약 반환, 실제 LLM 비용은 실패를 포함해 원장에 남긴다. 제공된 후보를 사용자가 적용하지 않는 경우나 제공 이후 본문 수정으로 stale이 되는 경우에는 자동 환불하지 않는다. 기존 최초 변환·문단 재변환의 과금 의미는 바꾸지 않는다.

### 5.2 생성 출력과 검증

LLM은 schema_version=1의 JSON 후보를 생성한다. 서버는 자유 텍스트를 HTML로 실행하지 않는다. 지원 섹션은 `eligibility, benefits, documents, steps, exceptions, contact` 6개로 고정한다. 각 섹션은 `available | not_in_source | needs_review`, 항목 배열, 원문 anchors를 갖는다.

- available인 항목은 원문 인용이 실제로 존재해야 한다. 범위를 벗어난 index, 맞지 않는 quote, 알 수 없는 section·필드는 거절한다.
- 항목은 섹션당 최대 10개, 각 항목의 본문 최대 500 코드 포인트, 전체 사용자 본문 최대 4,000 코드 포인트다. 서버에서 조용히 자르지 않고 실패 처리한다.
- 원문에 없는 발급처·링크·날짜·자격을 만들지 않는다. `not_in_source`는 ‘원문에 안내가 없습니다’로 표시한다.
- 예외와 기한은 관련 행동 항목의 `cautions`에도 연결한다. 연결 누락·서로 모순되는 조건은 `needs_review`다.
- 구조·인용·기존 사실 규칙 검사는 의미 정확성의 완전한 증명이 아니다. 담당자 검수가 필요하며, 전체 원문의 모든 숫자를 요약에 남겨야 하는 것으로 검사하지 않는다. 안내 항목별 필수 사실 기준을 별도로 고정한다.
- 원문 속 지시문은 데이터로 취급한다. 외부 도구 호출·URL 조회 권한을 부여하지 않는다.

### 5.3 버전·작업 상태

`ActionGuide`는 `guide_id`, `based_on_content_revision`, `guide_revision`, `status`, `sections`, `reviewed_at`, `reviewed_by`를 갖는다. 최초 유효 초안의 guide_revision은 1. 편집 저장 시 증가하고, 같은 본문의 동일 저장은 증가하지 않는다. 본문이 바뀌면 `status=stale`, 확인 상태를 해제한다. 오래된 안내문은 편집/출력을 막고 재생성을 제안하되 사용자가 작성한 내용을 화면에서 확인·복사할 수 있도록 보존 기간 안에 유지한다.

안내문 상태는 `draft | reviewed | stale`이고, 자원이 없을 때만 응답 wrapper의 `not_generated`를 사용한다. 신규 후보 적용은 항상 draft다. 명시적인 확인 저장으로 reviewed가 되며, 내용·근거 수정은 draft로 돌아간다. 확인/해제만 바꿔도 CAS가 잡을 수 있도록 guide_revision을 올린다. 완전히 같은 payload·확인 상태의 반복 저장만 no-op이다.

생성 작업은 `queued → running → succeeded | failed | superseded`로 전이한다. superseded는 입력 본문 변경 때문에 결과를 채택하지 않은 상태다. 기존의 유효 안내문은 새 생성 실패로 지우지 않는다. 작업 성공 뒤 대체 적용은 현재 버전·guide revision을 확인한 저장에서만 이뤄진다. 자동 덮어쓰기를 하지 않고 새 후보를 ‘적용’하도록 한다.

### 5.4 제안 API

| 메서드·경로 | 입력 | 성공 응답 |
|---|---|---|
| `POST /action-guide-jobs` | `{request_id, expected_content_revision, expected_guide_revision}`. request_id는 UUID, 안내문이 없으면 기대 버전은 null | 202 `{job_id,status,based_on_content_revision,reserved_credits}` |
| `GET /action-guide-jobs/{job_id}` | 없음 | 200 상태·based_on_content_revision·candidate_state(current/stale), 성공 시 `{candidate_id, sections}`, 실패 시 제한된 failure_code |
| `GET /action-guide` | 없음 | 200 `{status:not_generated,guide:null}` 또는 현재/이전 안내문 |
| `PUT /action-guide` | `{candidate_id, expected_content_revision, expected_guide_revision, sections, mark_reviewed}`. 직접 편집은 candidate_id=null, 최초 저장은 기대 guide 버전=null | 200 저장된 guide |
| `GET /action-guide/export?guide_revision=N` | 없음 | 200 UTF-8 TXT, attachment |

POST는 같은 소유자/conversion/request_id와 동일 입력이면 기존 job을 반환하며 재과금·재생성하지 않는다. 같은 키에 다른 입력이면 409. 후보는 job 완료 시 현재 본문에 대해 검증·암호화 저장된 결과로서 과금 대상이고, PUT은 그 후보를 사용자 문서로 적용하거나 편집하는 무과금 작업이다. 후보 적용 전에 기존 guide revision이 바뀌면 409, 현재 내용은 보존한다.

재방문용 `GET /action-guide` 응답은 현재 활성 작업의 job_id와 가장 최근 완료 작업의 job_id도 nullable로 포함한다. 서버 상태만으로 작업을 복구할 수 있어야 하며 브라우저 저장소가 유일한 작업 위치가 되면 안 된다. POST 중복 키 확인은 현재 잔액·시도 횟수 검사보다 먼저 수행하되 소유권·만료 확인보다 뒤에 둔다. 이미 완료한 작업의 재조회에도 새 잔액이 필요하지 않다.

GET job은 해당 변환 소유권으로 제한한다. 완료 전 생성은 409, 다른 활성 job 존재는 409, 잔액 부족 402, 생성 횟수 소진 429, 용량 초과 503 + Retry-After, 입력 오류 422, 타인/만료/삭제/OFF 404다. 실패 응답에 provider 원문·프롬프트를 포함하지 않는다.

서버는 PUT의 anchors·문자 상한을 다시 검증한다. `mark_reviewed=true`는 담당자가 원문과 대조했다고 명시한 경우에만 허용한다. 원문에 직접 근거를 지정하지 못한 자유 편집 항목은 `needs_review`로 표시하며, 이를 남긴 채 검수 완료로 저장할 수 없다. 외부 근거 입력 기능은 범위 밖이다. 단, 초안 저장은 가능하다.

export는 현재 본문 버전과 일치하고, 현재 guide_revision이 담당자 확인된 경우만 허용한다. 미확인·stale·revision 불일치는 409다. 이 출력 제한은 별도 행동 안내문에만 적용한다. 기존 본문 내려받기 규칙은 유지한다.

### 5.5 작업 큐와 실행 상한

새 작업은 PostgreSQL의 별도 `action_guide_jobs` 저장소로 관리한다. 기존 conversion 큐에 다른 payload를 넣지 않는다. 리스·fencing·worker 구성 방식은 기존 패턴을 재사용한다. 트랜잭션 밖에서 provider를 호출하고 결과 저장 시 소유권·만료·본문 버전·fencing token을 재검증한다.

설계 초기 상한은 문서당 활성 작업 1개, 계정당 활성 작업 1개, 배포 전체 동시 실행 2개, provider 시간 제한 90초, 출력 토큰 8,192이다. 모두 **제안 기본값**이며 ER-05에서 구성·메모리/비용 검증 후 고정한다. 고객 화면에는 구현 숫자 대신 대기·재시도 안내를 제공한다.

provider 호출 시작을 먼저 영속화한다. 호출 시작 이후 worker가 죽으면 자동으로 다시 호출하지 않고 `failed/outcome_unknown`으로 종료·예약 정산한다. 시작 전 리스 만료만 안전하게 재획득할 수 있다. 사용자의 명시적인 새 요청만 새로운 시도가 된다. 이는 중복 과금 방지를 우선한 선택이며 결과를 잃을 수 있다는 운영 한계를 기록한다.

job 완료·후보 저장·고객 예약 소비/반환은 한 트랜잭션으로 처리한다. 원장 항목은 job_id에 연결한 고유 실행 식별자로 중복 기록을 막는다. 불명확 호출의 토큰·원가는 알 수 없음으로 남기고 0달러 호출로 집계하지 않는다. 현행 원장/집계가 이를 표현하지 못하면 ER-05에서 outcome 또는 비용 완전성 필드를 확장하고 모든 소비자를 함께 수정한다.

시도 횟수는 provider 시작 이후 성공/실패/불명확을 모두 포함한다. 호출 전 취소·거절은 예약을 반환한다. 브라우저 중단은 이미 시작한 서버 작업의 취소가 아니다. 문서 삭제 시 후속 결과를 폐기하고 원장에는 본문 없는 비용 메타만 현행 정책대로 남긴다.

## 6. R3 문맥 설명

별도 요청·추가 호출 없이 기존 변환 프롬프트와 사전 context 선별을 개선한다. 공식 기관·서류·법령명은 유지하고 첫 등장에 짧은 역할 설명을 붙인다. 이후에는 같은 표현을 일관되게 쓴다. 검수되지 않은 사업별 조건과 내부 검수 메모를 생성 자료에 섞지 않는다.

역할 설명은 원문 또는 검수한 사전 정의로 한정한다. 모르면 원문 명칭을 남긴다. 모든 용어에 긴 풀이를 넣지 않고 핵심 개념을 우선한다. 공통 프롬프트 변경은 문서 전체 변환·문단 재변환·보정의 일관성과 비용을 함께 확인한다. API가 변하지 않으면 frontend/계약을 임의로 수정하지 않는다.

## 7. R4 표 구조

기존 `table_cell`은 행·열 정보가 없으므로 새 `TableStructure`를 추가한다. 필드는 `table_id`, `source_unit_indexes`, `row_count`, `column_count`, `cells[{row,column,source_unit_indexes,header_refs}]`, `unit_anchors`, `footnote_anchors`, `support_status`다. 원본 줄 좌표의 의미를 변경하지 않는다.

v1 지원은 DOCX/HWPX의 병합 없는 사각형 표, 한 줄의 열 제목, 최대 100행·20열이다. 복수 헤더·병합·중첩 표·PDF·좌표 유실은 `unsupported`와 사유를 낸다. 표 전체 크기 상한은 기존 업로드/텍스트 상한을 우선한다. 과거 문서는 `unavailable`, 일괄 재추출하지 않는다.

GET 원문 응답에 optional nullable `tables`를 제안하며 계약/API·React 소비자를 함께 반영한다. 값·단위·각주의 출처를 함께 보여 주되 자격을 자동 계산하지 않는다. 화면은 지원 여부와 행/열 제목을 제공하고 값 자체를 담당자 승인 없이 바꾸지 않는다. 구조화 표현이 원본 DOCX/HWPX 내보내기를 깨지 않는지 검증한다.

## 8. R5 이력

R1은 현재 확인 상태만 저장하고 R5에서 append-only `review_events`와 필요한 암호화 스냅샷을 추가한다. 이벤트 유형은 `item_confirmed, item_reopened, item_not_applicable, guide_reviewed, invalidated_by_edit`로 제한한다. actor·server time·content_revision·artifact revision을 기록한다.

`GET /review-history?cursor=...&limit=20`을 제안한다. 최대 50, `(created_at,id)` 기준 불투명 커서, 전체 합계 계산 없음. `GET /review-history/export`는 요청 시점까지의 기록을 TXT로 출력한다. 이번 범위에서는 되돌리기/이전 버전 복원 API가 없다. 이력은 소유자만 보며 문서 삭제·만료와 함께 지운다. 외부 인증서처럼 표현하지 않는다.

## 9. R6 선택 설명과 R7 그림

R6는 `GET /explanations`에서 현재 본문 버전과 연결된 `{term, source_anchors, definition_source, explanation}`을 제공하는 설계를 제안한다. 초기에는 검수한 사전 정의·원문의 설명만 사용하여 추가 모델 호출 없이 파생한다. 근거가 없으면 제공하지 않는다. 본문 수정 후 이전 설명 위치는 즉시 비활성화한다. 필수 조건·금액·기한은 기본 본문에 남는다. 펼침 상태는 세션 UI 상태이며 본문에 자동 저장하지 않는다.

R7은 최대 10종의 승인된 그림 카탈로그부터 시작한다. 항목은 asset_id·용도·대체텍스트·저작권/라이선스·출처·검수자·버전을 갖는다. 원문 행동과 그림 연결은 담당자가 확인한다. 임의 생성 이미지 호출은 포함하지 않는다. v1은 웹 미리보기와 기존 텍스트 출력 유지가 기본안이며 DOCX/HWPX 이미지 삽입은 서식 검증 후 별도 범위로 결정한다.

## 10. 저장·암호화·삭제

| 단계 | 제안 저장 | 수명·보호 |
|---|---|---|
| R1 | conversions.content_revision, review_assessments + 현재 확인 상태 | 본문 인용·메모·payload는 기존 ContentCipher 패턴으로 AEAD 암호화 |
| R2 | action_guide_jobs, action_guide_candidates, action_guides | job 입력은 원문/본문 복사 대신 참조+revision. 결과·실패 상세 본문은 암호화 또는 미저장 |
| R4 | 문서 표 구조 payload | 원문과 함께 암호화; 좌표만으로 본문 유추 가능한 데이터도 본문 취급 |
| R5 | review_events + 제한된 버전 스냅샷 | 문서 수명 내 보관, 원문 재사용용 장기 축적 금지 |

새 암호화 payload마다 record ID/field AAD를 구분하고 키 회전 대상에 등록한다. 평문 메타는 ID·상태·정수 버전·시간·enum·비용/토큰 숫자로 제한한다. 사용자 내용·quote·메모·제목·본문 hash를 로그/메트릭 label에 넣지 않는다.

읽기·쓰기·출력은 파기 배치 전에도 `retention_expires_at`이 지나면 404다. 문서 명시 삭제·만료 파기·회원 탈퇴 모두 관련 자원을 제거한다. FK cascade와 별도 job 정리의 실제 연결을 테스트한다. 작업 중 삭제돼도 결과를 되살리지 않는다. 후보·스냅샷·실패 결과의 파일 캐시는 만들지 않는다.

삭제 트랜잭션은 활성 job과 예약을 잠그고 고객 예약 반환/시도 상태 정리를 먼저 확정한 뒤 본문·파생 자원을 제거한다. 늦게 도착한 provider 결과는 폐기한다. 비용 추적에 필요한 실행 식별자·정산 상태 등 본문 없는 최소 기록은 현행 사용량/회원 탈퇴 정책에 맞춰 남기며, 삭제된 job의 FK 때문에 원장 기록이나 예약 반환이 불가능해지지 않게 설계한다. 계정 삭제 경로의 기존 금융 기록 처리와 실제로 연결되는지 ER-05에서 검증한다.

R5 스냅샷은 문서당 최근 20버전, 각 파생 payload는 64KiB 이하를 초기 제한으로 제안한다. 초과 기록은 오래된 본문 스냅샷부터 제거하되 남은 이벤트는 ‘본문 스냅샷 없음’으로 표시한다. 전체 문서 보존 만료 시 이벤트도 함께 삭제한다. 법적 장기 감사 보존을 제공한다고 주장하지 않는다.

## 11. 기능 설정·호환·관측

제안 설정은 `easydoc.features.review-support`, `action-guide`, `table-relations`, `review-history`, `explanations`, `illustrations`이며 기본 OFF다. R3 프롬프트도 이전 버전을 선택할 수 있는 명시적 버전 설정을 둔다. 실제 이름·환경변수 매핑은 구현 PR에서 확정한다.

서버 기능 OFF면 신규 API는 404, UI는 항목을 숨긴다. 기존 본문 저장·변환·내려받기는 동작한다. 새 필드는 additive로 추가하고 같은 PR의 API 소비자를 갱신한다. migration은 확장 방식으로 적용하며 rollback에 컬럼 DROP을 사용하지 않는다.

허용 관측값: 단계·규칙별 신호 수, 확인/재검수 전이 수, 충돌률, job 상태·지연·토큰·실패 분류, 반환/소비 크레딧, 보존 파기 건수. 문서 ID를 메트릭 label로 넣지 않고 오류 로그에서도 본문 없이 제한된 상관 ID만 사용한다.

검수 지원 실패는 기존 본문 편집을 중단시키지 않는다. 단, 본문 버전 갱신과 파생 결과 무효화가 실패하면 본문 저장 전체를 실패시켜 불일치가 생기지 않게 한다.
