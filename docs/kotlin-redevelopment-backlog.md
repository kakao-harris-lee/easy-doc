# Kotlin 재개발 backlog

- 작성일: 2026-08-24
- 목적: 현재 Kotlin/Spring Boot 제품에서 아직 구현되지 않은 기능과 결정이 필요한 항목을 관리한다. 이 문서는 계획이 아니라 **backlog**다. 착수 순서는 `docs/master-plan.md`와 활성 Sprint K1 문서를 따른다.
- 상태 근거: 현재 `backend-kotlin/`, `frontend/`, `contracts/easy-doc-v1.yaml`의 구현과 테스트.

## 1. 미구현 기능 (2026-08-24 현재 코드 기준)

| 기능 | 상태 | 비고 |
|---|---|---|
| `GET /conversions/{conversion_id}/export` (docx·txt·hwpx) | 구현 | `pdf`·구버전 `hwp`는 계약 enum 밖(422). POST export 없음 |
| Worker 작업 처리(리스 획득 → 마스킹 → LLM 호출 → 결과 반영) | 구현 | `worker` 프로필이 lease를 집어 트랜잭션 밖에서 LLM을 호출하고 fencing으로 완료를 쓴다. 로컬 Compose는 `EASYDOC_LLM_PROVIDER=fake`로 유료 호출 없이 상태를 끝낸다 |
| 보존·자동 삭제 정책(기본 30일) | 구현 | worker가 `retention_expires_at` 만료 문서를 배치가 짧아질 때까지 반복 삭제한다. 활성 lease는 건너뛰고, dry-run은 한 배치만 미리본다. 건수 메트릭·문서 ID 감사 로그만 남긴다 |
| 긴 문서 처리(4,000자 초과) | 미판정 | **게이트 ⓪ — 파일럿 착수의 선행 조건**(master-plan §9). 구현이 아니라 **측정과 선택**이 먼저다. 아래 §1.2 |
| 쉬운 말 사전(RAG, pgvector 기반 팝업) | 미구현 | master-plan P0-5. Lean MVP 범위 밖으로 의도적으로 미뤄져 있었다 |
| 골든셋 품질 평가(스타일 규칙 + LLM-as-judge) | 구현 | `./gradlew build`가 스키마·원문 사실·변환 스냅샷 스타일/사실·파일·ID·JSON digest 기준선을 검사한다. LLM-as-judge는 `./gradlew testLlm`이며 비밀값이 없으면 skip |
| 검수 피드백 기록(게이트 ① 판정 근거) | 구현 | `PUT /conversions/{id}/feedback` 멱등 upsert. 배포 의향·품질 만족도·소요 시간 + 자유 의견(AEAD 봉인). 수정률 지표는 저장 시점에 계산해 평문 숫자로 남긴다. `conversion_feedback`은 문서 30일 파기와 **분리**돼 있다(FK 없음). 집계는 `scripts/pilot-report.sql`, 절차는 `docs/pilot-runbook.md` 「게이트 ① 판정」. 조회 API(`GET`)와 재방문 시 이전 값 표시는 범위 밖 |
| 결제(카드·계좌이체·세금계산서), 크레딧 차감 | 미구현 | Lean MVP 범위 밖(master-plan 4.0) |
| 운영자 어드민 | 미구현 | Lean MVP 범위 밖 |

## 1.1 추후 개선 항목 (동작에는 문제 없음)

| 항목 | 현재 상태 | 판단이 필요한 것 |
|---|---|---|
| `DocumentConfiguration`의 `@Suppress("TooManyFunctions")` | 유지 | 파일럿 피드백 빈 둘이 붙으면서 detekt 임계를 넘어 억제를 걸었다. 근거는 클래스 KDoc에 있다 — 조립 지점의 함수 수는 클래스의 복잡도가 아니라 협력자의 수이고, 규칙을 지키려고 조립을 두 클래스로 가르면 「composition root는 하나」라는 성질이 사라진다. 대안은 ⑴ 억제 유지 ⑵ 도메인별 `@Configuration` 분리(그 성질을 포기) ⑶ detekt 임계 조정 셋이다. **협력자가 더 늘어날 때 다시 판단한다** — 억제가 늘어나는 신호를 못 보게 되는 것이 이 항목의 실제 비용이다 |
| `conversion_feedback`의 삭제 경로 | 수기 — `docs/pilot-runbook.md` 「파일럿 종료 정리」 절차뿐이다 | 이 표는 문서 30일 파기(`JdbcExpiredDocumentPurge`)의 사슬 밖이다. 파기는 `documents` → `conversions` → `conversion_jobs`로만 이어지고 피드백 표에는 FK가 없어(판정 근거를 남기려는 의도적 설계) TTL도 purge도 없다 — **아무도 지우지 않으면 영구히 남는다.** 자유 의견은 AEAD로 봉했지만 봉인은 기밀성이지 삭제가 아니고, 그 칸에는 문서 본문 조각이 실제로 들어온다(`V2__conversion_feedback.sql` 주석). 마스킹 범주는 2종뿐이라(master-plan §3.2) 그 조각의 이름·주소·전화번호는 가려지지 않는다. master-plan §3.2는 "기본 보존 30일 후 자동 삭제 … 삭제 요청 시 즉시 파기"를 약속하는데 이 표에는 **둘 다 구현이 없다.** 판단이 필요한 것: ⑴ 제품에 계정 삭제·삭제 요청 처리 경로가 생길 때(지금은 계약 오퍼레이션에 계정 삭제 자체가 없다) 이 표를 그 경로에 포함시킬 것인가 ⑵ 아니면 자유 의견 세 열(`comment_encrypted`·`encryption_scheme`·`key_version`)에만 별도 TTL을 두어 척도 숫자는 남길 것인가. **FK를 되살리는 것은 답이 아니다** — CASCADE는 판정 근거인 표본 자체를 지운다 |
| 키 회전(`EnvelopeRotation`)에 운영 진입점이 없음 | 빈으로 조립돼 있으나 **부르는 곳이 없다** | `DocumentConfiguration.kt:129-145`가 `EnvelopeRotation`을 `@Bean`으로 올리고 세 갈래(`rotateDocument`·`rotateConversion`·`rotateFeedback`)와 동시성 테스트(`EnvelopeRotationConcurrencyTest`)까지 갖췄지만, **프로덕션 코드에서 이 빈을 주입받는 곳이 하나도 없다** — 컨트롤러·스케줄러·CLI·`migrate` 프로필 어디에도 없고 `src/main` 안의 참조는 저 `@Bean` 선언뿐이다. 키 회전은 오늘 테스트에서만 실행된다. **이 PR이 만든 문제가 아니다** — 세 갈래 모두 이전부터 도달 불가였고, 이 PR은 새로 봉인한 열(`conversion_feedback.comment_encrypted`)이 회전 가족에서 빠지는 것을 `EnvelopeRotationTest`의 `EncryptedField` 전수 대조로 막았을 뿐 진입점 상태를 바꾸지 않았다. 왜 중요한가: `core/crypto/StoredContent.kt`가 저장 암호화의 요구 성질로 키 회전을 든다(`migration-safety-gate` I-7). 유출 의심·정기 교체처럼 **실제로 키를 갈아야 하는 사건이 오면 지금은 실행할 수단이 없다.** 판단이 필요한 것: ⑴ 운영 전용 프로필의 배치 태스크 ⑵ 관리자 API ⑶ 일회성 CLI 중 무엇을 진입점으로 둘 것인가, 그리고 **어느 세대까지 회전할지·중단과 재개·진행률 보고·`CONTENDED` 행의 재시도**를 어떻게 다룰 것인가(회전은 행 단위라 한 번에 끝나지 않는다). 함께 볼 것: `EnvelopeRotation.kt:26-33`의 `NOTHING_SEALED` KDoc이 「회전 배치의 집계」를 그 값의 존재 이유로 드는데 **그 배치가 아직 없다** — 진입점이 서면 그 주석이 참이 된다 |
| `documents.title`의 `fallback_title`이 화면에서 도달 불가 | 계약은 유지, 제품 경로로는 안 나옴 | 업로드 화면이 제목을 필수로 만들면서(`frontend/src/pages/UploadPage.tsx` — 공백만 있으면 제출을 막는다) 계약이 정의한 `x-title-policy.fallback_title`(`"제목 없음"`)이 제품 경로로는 도달하지 않는다. **계약 위반은 아니다** — 클라이언트가 서버보다 좁게 받는 것은 허용된다. 다만 계약이 유지 중이라고 적어 둔 동작이 조용히 죽은 상태다. 판단이 필요한 것: ⑴ 계약에서 걷을 것인가(그러면 API 직접 호출자도 제목이 필수가 된다) ⑵ 화면을 되돌려 선택 입력으로 할 것인가 ⑶ 서버 계약으로만 남기고 그대로 둘 것인가 |

## 1.2 긴 문서 처리 — 게이트 ⓪ (2026-08-27 추가)

4,000자(공백 포함) 상한이 파일럿의 실제 문서를 막는다. 실측 근거와 게이트로 세운 이유는 `docs/master-plan.md` §9 「게이트 ⓪」에 있다.

**착수 순서를 뒤집지 말 것.** master-plan §3.2는 2026-08-08에 「분할 변환 필요성은 소멸」로 판정했고(gpt-4.1 기준), 그 절이 남긴 처방은 분할 구현이 아니라 **상한값 재조정**이다. 그런데 그 판정도, 함께 적힌 「2,000자 초과 스타일 통과율 0.11」도 **지금 제품이 쓰는 모델(anthropic·claude-sonnet-5)에서 재측정된 적이 없다.** 그러므로:

- **0. 먼저 잰다 (다른 모든 항목의 선행).** 현재 모델로 골든셋 장문의 출력 팽창비와 스타일 규칙 통과율을 측정한다. 자리는 `./gradlew testLlm` opt-in 레인이며 실제 유료 호출이므로 **사용자 승인이 필요하다**. 이 측정이 나오기 전에는 아래 1~6을 설계하지 않는다 — 상한 상향으로 끝날 수 있는 일에 분할 파이프라인을 먼저 그리는 것이 이 항목의 가장 큰 낭비다.
  - **⚠ 그 레인은 지금 상태로 이 측정을 못 한다 (2026-08-27 확인).** ⑴ `data/golden/documents/` 56건에 4,000자 초과가 **0건**이다(최댓값 3,993자). 상한에 맞춰 수집된 말뭉치라 상한 밖을 잴 재료가 없다. ⑵ `GoldenCorpusLlmEvaluationTest`가 `OPENAI_API_KEY`를 먼저 보고 provider를 고르며 `easydoc.llm` 설정을 읽지 않아, 키가 둘 다 있으면 **제품이 쓰는 모델이 아닌 것**을 잰다.
  - **⚠ 출력 토큰 상한도 함께 걸린다.** `DEFAULT_MAX_TOKENS = 16_000`이 `core/llm/LlmProvider.kt:7`에 **코드 상수로** 박혀 있다. 2026-08-27 실측(1,500자 → 출력 3,902토큰, 2,113자 → 7,479토큰; 최대 2회 호출 합계라 상계치)으로 보면 1만 자는 이 상한을 넘긴다. 넘기면 `LlmTruncatedException` → `ConversionFailureKind.TRUNCATED`로 **변환 실패**다(§3.2 절단 방지 규칙). 즉 `MAX_CONVERTIBLE_CHARS`만 올리면 장문이 전부 실패로 떨어진다. 이 값은 운영 중 바뀔 수 있으므로 코드 상수가 아니라 `@ConfigurationProperties`로 옮긴다(CLAUDE.md 「상수와 구성 관리」).
  - **측정 항목은 셋이다**: 출력 팽창비, 스타일 규칙 통과율, **절단 발생률**. 앞의 둘만 재면 "품질은 괜찮은데 절반이 실패하는" 상태를 통과로 읽는다.
  - 따라서 0번의 실제 작업은 셋이다: **출력 토큰 상한을 구성값으로 옮기기**, **장문 골든 표본을 승인 경로에 넣기**(후보 `022`·`023`·`047`·`050`은 `docs/golden-drafts/`에 남겨 뒀다. 사실 3~6개 채우기와 `022`의 평문 연락처 제거가 선행이고, `golden-baseline.json` 갱신은 리뷰 승인 사항이다)와 **레인이 제품 provider 설정을 따르게 하기**. `docs/golden`의 큰 PDF에서 1만 자 구간 표본을 더 뽑아야 할 수도 있다 — 지금 후보 중 1만 자를 넘는 것은 `022`(21,924자) 하나뿐이다.
- **판정이 갈리는 지점.** 장문에서 팽창비와 스타일 통과율이 버티면 → **새 상한값을 정하고 끝낸다**(아래 6만 수행). 무너지면 → 분할 변환을 착수하고 아래 1~5를 결정한다.

아래는 **분할 변환으로 가기로 판정된 경우에만** 답해야 하는 것들이다.

**상한이 걸려 있는 자리는 셋이다.** `MAX_CONVERTIBLE_CHARS`(`core/document/DocumentLimits.kt`)가 업로드를, 계약 `x-input-limits.max_review_chars`가 검수 수정본을 각각 4,000자로 막고, `DEFAULT_MAX_TOKENS`(`core/llm/LlmProvider.kt:7`)가 출력을 16,000토큰으로 막는다. 첫째만 풀면 1만 자 문서를 변환해 놓고 **검수본을 저장할 수 없고**, 셋째를 안 풀면 **변환 자체가 절단 실패로 끝난다** — 셋은 같은 변경 단위다. 추출기 상한(`MAX_EXTRACTED_CHARS` = 500,000)은 별개이며 지금도 훨씬 넓다.

판단이 필요한 것:

1. **분할 단위.** 문단 경계로 자르는 것이 기본이지만, 표·목록·머리글이 경계에 걸릴 때 무엇을 한 조각으로 볼지 정해야 한다. 4단계에서 만든 원본 구조 보존 내보내기가 문단 id 대응에 기대고 있으므로(`d8bfd03`, `48b5643`), 분할이 그 대응을 깨지 않아야 한다.
2. **마스킹 플레이스홀더의 조각 간 일관성.** 마스킹은 LLM 호출 **전에** 끝나야 한다는 것이 타입 경계로 강제돼 있다. 같은 주민등록번호가 두 조각에 나오면 같은 플레이스홀더를 받아야 하고, 원문-플레이스홀더 대응표는 문서 하나로 합쳐져야 한다.
3. **부분 실패.** 조각 5개 중 3번째만 실패하면 무엇을 사용자에게 보이는가. 지금 `conversions.status`는 문서 단위 4값(`pending`·`processing`·`done`·`failed`)뿐이라 "일부 완료"를 표현할 자리가 없다. 상태를 늘릴지, 조각을 전부 성공해야만 `done`으로 볼지 정해야 한다. **재시도 책임은 한 계층만 갖는다**(CLAUDE.md) — 조각 재시도를 큐와 유스케이스가 동시에 하지 않게 한다.
4. **크레딧 환산.** 1,000자 = 1크레딧(master-plan 4.1 P0-7)은 문서 단위 환산이다. 분할하면 조각마다 프롬프트가 다시 실려 실제 토큰은 글자 수에 비례하지 않는다(2026-08-27 실측: 668자 문서의 입력이 6,695토큰이었다 — 대부분이 프롬프트다). 환산을 글자 수 기준으로 유지할지, 조각 수를 반영할지 정해야 한다.
5. **계약 변경 범위.** 상한을 바꾸면 `contracts/easy-doc-v1.yaml`의 `x-input-limits`, 422 detail 문자열, Kotlin 계약 테스트, `frontend/src/api/`와 업로드 화면 안내가 같은 변경 단위다.
6. **새 상한값.** 무제한이 아니라 새 숫자를 정한다. 근거는 골든셋 LLM 평가의 출력 팽창비다(master-plan §3.2가 이미 그 방법을 정해 두었다).

**절단 방지 규칙은 그대로 유효하다** — 조각 하나라도 토큰 한도에서 잘리면 그 변환은 실패이며 사용자에게 내보내지 않는다(master-plan §3.2).

## 2. 구현 시 반드시 지킬 요구사항

### 2.1 저장 암호화

- **표준 AEAD**를 사용한다. 이미 `infrastructure.crypto.CryptoConfiguration`과 현재 Flyway schema로 구현돼 있다.
- 요구 성질: round-trip, 변조 거부(HMAC/AEAD 태그 검증을 복호화보다 먼저), 키 회전 지원(`encryption_scheme`/`key_version` 컬럼), nonce 재사용 금지.
- 판정 기준은 round-trip, 변조 거부, 키 회전, nonce 재사용 금지 성질이다.

### 2.2 Argon2 (비밀번호 해시)

- **재해시 판정은 전체 파라미터 동등성으로 구현한다.** Spring Security `Argon2PasswordEncoder.upgradeEncoding`의 기본 동작(`memory`·`iterations`의 "미만"만 비교)은 부족하다 — parallelism 변경, hash_len 변경 등에서 재해시 필요 여부를 놓친다. 파라미터 기준(spike 시점): `Argon2PasswordEncoder(16, 32, 4, 65536, 3)`.
- 이미 `infrastructure.auth.Argon2PasswordHasher`/`Argon2Phc`로 구현돼 있음 — 재해시 판정 로직이 전체 파라미터 동등성을 쓰는지 재검토 대상.

### 2.3 JWT

- **clock skew는 0으로 명시한다.** 라이브러리 기본값(예: Nimbus `JWTClaimsVerifier`·Spring `JwtTimestampValidator`의 기본 60초 leeway)을 그대로 두면 만료 토큰이 최대 59초까지 통과한다.
- 경계 케이스(`exp == now`, `exp == now - 1`) 회귀 테스트 유지.
- 이미 `infrastructure.auth.JwtAccessTokens`로 구현돼 있음 — skew 설정이 0으로 명시돼 있는지 재검토 대상.

### 2.4 문서 파싱 (DOCX/PDF/HWPX)

- **DOCX**: Apache POI를 usermodel이 아니라 **OOXML DOM 순회**로 쓰면 블록 추출 결과가 안정적이다(표·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글 처리 필요).
- **HWPX**: DTD/UTF-16 DTD/XXE 차단 필수(StAX 파서 설정). zip bomb 방어 필수(압축 해제 크기 상한 — spike 기준 1GiB 입력을 힙 256MB에서 거부). mimetype 항목이 STORED로 zip 첫 번째여야 한다(개방형 HWPX 스펙). **내보내기는 `hwpxlib` BlankFileMaker로 header.xml·manifest·spine을 채우고**, mimetype만 첫 STORED 항목으로 다시 얹는다.
- **PDF**: PDFBox 사용. `MAX_EXTRACTED_CHARS`·업로드 크기 상한 경계 확인 필요. 암호 걸린 PDF/DOCX 처리 정책 미정 — 결정 필요.
- **DOCX 내보내기 템플릿**: POI로 생성한 DOCX에는 `styles.xml`/theme가 기본으로 없어 Heading 1 등 서식이 사라진다. 템플릿을 저장소에 동봉할지 결정 필요.
- 내보내기 zip 컨테이너의 바이트 단위 동일성은 애초에 불가능한 목표다(압축기 차이) — 비교는 정규화된 텍스트/구조로 한다.

### 2.5 마스킹·개인정보 (master-plan 3.2, CLAUDE.md 아키텍처 규칙 2와 중복 없음 — 여기는 구현 함정만)

- 마스킹 범주 2종(주민등록번호·카드번호)이며, 마스킹 선행 불변식(LLM 전달 전 필수 통과)은 순서 문제이지 범주 문제가 아니다.
- 원문-플레이스홀더 대응표는 인증된 소유자의 검수 조회 응답에만 반환, 로그·목록 응답에는 포함하지 않는다.

## 3. 언어 독립 데이터 위치

- golden JSON 56건, `required_facts` 253개: `data/golden/documents/`
- 프롬프트·스타일 규칙 기준: `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/easyread/` 및 같은 모듈의 스냅샷 테스트
- DOCX/PDF/HWPX 보안 fixture: `backend-kotlin/infrastructure/src/testFixtures/resources/fixtures/ingest/`

## 4. 역사 자료

- Python 시대 스프린트와 CI 기록은 `docs/plans/archive/python-era/`에 보관한다.
- Python 제거 범위와 결정은 `docs/plans/archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md`에 보관한다.
- 역사 자료의 완료 표시는 현재 Kotlin backlog를 닫지 않는다.
