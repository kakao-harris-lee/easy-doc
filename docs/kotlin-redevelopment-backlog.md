# Kotlin 재개발 backlog

- 작성일: 2026-08-24
- 목적: 현재 Kotlin/Spring Boot 제품에서 아직 구현되지 않은 기능과 결정이 필요한 항목을 관리한다. 이 문서는 계획이 아니라 **backlog**다. 착수 순서는 `docs/master-plan.md`와 활성 Sprint K1 문서를 따른다.
- 상태 근거: 현재 `backend-kotlin/`, `frontend/`, `contracts/easy-doc-v1.yaml`의 구현과 테스트.

## 1. 미구현 기능 (2026-08-24 현재 코드 기준)

| 기능 | 상태 | 비고 |
|---|---|---|
| `GET /conversions/{conversion_id}/export` (docx·txt·hwpx) | 구현 | `pdf`·구버전 `hwp`는 계약 enum 밖(422). POST export 없음 |
| Worker 작업 처리(리스 획득 → 마스킹 → LLM 호출 → 결과 반영) | 구현 | `worker` 프로필이 lease를 집어 트랜잭션 밖에서 LLM을 호출하고 fencing으로 완료를 쓴다. 로컬 Compose는 `EASYDOC_LLM_PROVIDER=fake`로 유료 호출 없이 상태를 끝낸다 |
| 보존·자동 삭제 정책(기본 30일) | 구현 | worker가 `retention_expires_at` 만료 문서를 배치 삭제한다. 활성 lease는 건너뛰고, dry-run·건수 메트릭·문서 ID 감사 로그만 남긴다 |
| 쉬운 말 사전(RAG, pgvector 기반 팝업) | 미구현 | master-plan P0-5. Lean MVP 범위 밖으로 의도적으로 미뤄져 있었다 |
| 골든셋 품질 평가(스타일 규칙 + LLM-as-judge) | 구현 | `./gradlew build`가 스키마·사실 잔존·스타일 규칙·기준선을 검사한다. LLM-as-judge는 `@Tag("llm")` opt-in이며 비밀값이 없으면 skip |
| 결제(카드·계좌이체·세금계산서), 크레딧 차감 | 미구현 | Lean MVP 범위 밖(master-plan 4.0) |
| 운영자 어드민 | 미구현 | Lean MVP 범위 밖 |

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
