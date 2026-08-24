# Kotlin 재개발 backlog

- 작성일: 2026-08-24
- 목적: `docs/migration/**`(마이그레이션 진행 문서, 2026-08-24 제거)와 이제 없는 Python 구현에만 있던 결정·미해결 항목 중, 아직 유효한 요구사항과 미구현 기능을 옮겨 적는다. 이 문서는 계획이 아니라 **backlog**다 — 착수 순서·우선순위는 `docs/master-plan.md` 4장을 따른다.
- 근거: `docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md`, 제거 직전 `docs/migration/_workspace/00_progress.md`(태그 `pre-python-removal-20260824`에서 전문 확인 가능).

## 1. 미구현 기능 (실측 — `docs/plans/_workspace-python-removal/00_baseline_kotlin-incompleteness-snapshot.md` 참고)

| 기능 | 상태 | 비고 |
|---|---|---|
| `GET/POST /conversions/{conversion_id}/export` (docx·pdf·txt 3형식 다운로드) | 미구현 | 계약(`contracts/easy-doc-v1.yaml`)에는 있다. 컨트롤러 없음 |
| Worker 작업 처리(리스 획득 → 마스킹 → LLM 호출 → 결과 반영) | 미구현 | `infrastructure.queue.JdbcConversionQueue`(큐 자료구조)는 있으나 `worker/` 모듈은 Spring Boot 기동 골격뿐 |
| 보존·자동 삭제 정책(기본 30일) | 미구현 | 스케줄러·배치 없음 |
| 쉬운 말 사전(RAG, pgvector 기반 팝업) | 미구현 | master-plan P0-5. Lean MVP 범위 밖으로 의도적으로 미뤄져 있었다 |
| 골든셋 품질 평가(스타일 규칙 + LLM-as-judge) | 미구현(Kotlin) | Python `app/easyread/{goldenset,judge}.py`에만 있었고 제거됐다. 골든 fixture 자체는 `data/golden/`에 보존(§3) |
| 결제(카드·계좌이체·세금계산서), 크레딧 차감 | 미구현 | Lean MVP 범위 밖(master-plan 4.0) |
| 운영자 어드민 | 미구현 | Lean MVP 범위 밖 |

## 2. 구현 시 반드시 지킬 요구사항 (Python spike로 확인됐던 사항 — Kotlin으로 그대로 적용)

### 2.1 저장 암호화

- **표준 AEAD**(Fernet 호환 불필요 — 2026-08-12 재개발 전환으로 호환 요구 소멸). 이미 `infrastructure.crypto.CryptoConfiguration` + `V2__encryption_scheme.sql`/`V3__encryption_scheme_aead.sql`로 구현됨.
- 요구 성질: round-trip, 변조 거부(HMAC/AEAD 태그 검증을 복호화보다 먼저), 키 회전 지원(`encryption_scheme`/`key_version` 컬럼), nonce 재사용 금지.
- 판정 기준은 값 동일성이 아니라 위 성질 충족(Python 대조 불가 — 이미 제거됨).

### 2.2 Argon2 (비밀번호 해시)

- **재해시 판정은 전체 파라미터 동등성으로 구현한다.** Spring Security `Argon2PasswordEncoder.upgradeEncoding`의 기본 동작(`memory`·`iterations`의 "미만"만 비교)은 부족하다 — parallelism 변경, hash_len 변경 등에서 재해시 필요 여부를 놓친다. 파라미터 기준(spike 시점): `Argon2PasswordEncoder(16, 32, 4, 65536, 3)`.
- 이미 `infrastructure.auth.Argon2PasswordHasher`/`Argon2Phc`로 구현돼 있음 — 재해시 판정 로직이 전체 파라미터 동등성을 쓰는지 재검토 대상.

### 2.3 JWT

- **clock skew는 0으로 명시한다.** 라이브러리 기본값(예: Nimbus `JWTClaimsVerifier`·Spring `JwtTimestampValidator`의 기본 60초 leeway)을 그대로 두면 만료 토큰이 최대 59초까지 통과한다.
- 경계 케이스(`exp == now`, `exp == now - 1`) 회귀 테스트 유지.
- 이미 `infrastructure.auth.JwtAccessTokens`로 구현돼 있음 — skew 설정이 0으로 명시돼 있는지 재검토 대상.

### 2.4 문서 파싱 (DOCX/PDF/HWPX)

- **DOCX**: Apache POI를 usermodel이 아니라 **OOXML DOM 순회**로 쓰면 블록 추출 결과가 안정적이다(표·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글 처리 필요).
- **HWPX**: DTD/UTF-16 DTD/XXE 차단 필수(StAX 파서 설정). zip bomb 방어 필수(압축 해제 크기 상한 — spike 기준 1GiB 입력을 힙 256MB에서 거부). mimetype 항목이 STORED로 zip 첫 번째여야 한다(개방형 HWPX 스펙).
- **PDF**: PDFBox 사용. `MAX_EXTRACTED_CHARS`·업로드 크기 상한 경계 확인 필요. 암호 걸린 PDF/DOCX 처리 정책 미정 — 결정 필요.
- **DOCX 내보내기 템플릿**: POI로 생성한 DOCX에는 `styles.xml`/theme가 기본으로 없어 Heading 1 등 서식이 사라진다. 템플릿을 저장소에 동봉할지 결정 필요.
- 내보내기 zip 컨테이너의 바이트 단위 동일성은 애초에 불가능한 목표다(압축기 차이) — 비교는 정규화된 텍스트/구조로 한다.

### 2.5 마스킹·개인정보 (master-plan 3.2, CLAUDE.md 아키텍처 규칙 2와 중복 없음 — 여기는 구현 함정만)

- 마스킹 범주 2종(주민등록번호·카드번호)이며, 마스킹 선행 불변식(LLM 전달 전 필수 통과)은 순서 문제이지 범주 문제가 아니다.
- 원문-플레이스홀더 대응표는 인증된 소유자의 검수 조회 응답에만 반환, 로그·목록 응답에는 포함하지 않는다.

## 3. 언어 독립 데이터 보존 위치

제거 작업(단계 2)에서 아래로 이동했다 — 정확한 경로·개수·SHA-256은 `docs/plans/_workspace-python-removal/02_data-migration-manifest.md` 참고.

- golden JSON 56건, `required_facts` 253개 → `data/golden/`
- easy-read 변환 예시 6개 → `data/golden/` 또는 `data/examples/`
- 독립 style rule 데이터(13개 키) → `data/style-rules/`
- DOCX/PDF/HWPX 샘플, 위조·과대 ZIP 보안 fixture → `data/document-samples/`

## 4. 참고 — 이제 없는 문서

- `docs/plans/2026-08-11-kotlin-react-migration.md` (마이그레이션 계획 원본) — 제거됨. 필요하면 태그 `pre-python-removal-20260824`에서 열람.
- `docs/migration/_workspace/**`(진행 원장·spike 보고서·리뷰) — 제거됨. 같은 태그에서 열람 가능.
