# Easy-Read AI Kotlin + React 전환 계획

> 작성일: 2026-08-11  
> 대상 기준: `main` (`5948810`)  
> 목표: 기존 제품 동작과 개인정보 보호 정책을 보존하면서 Python/FastAPI 런타임을 Kotlin으로 교체하고, 이미 구현된 React UI를 새 백엔드 계약에 안정적으로 연결한다.

## 1. 결론

이 프로젝트의 프런트엔드는 이미 React + TypeScript + Vite다. 따라서 프런트엔드를 다시 만드는 작업은 하지 않는다. 기존 로그인, 업로드, 변환 검수, 기록, 작업 공간 화면과 테스트를 유지하고 API 계약 관리와 접근성만 보강한다.

전환의 주 작업은 다음 세 가지다.

1. Python/FastAPI API와 ARQ 워커를 Kotlin/Spring Boot API·워커로 교체한다.
2. PostgreSQL의 기존 데이터와 암호문, JWT, Argon2 해시, API 응답을 호환한다.
3. 문서 추출·내보내기와 쉬운 글 변환 품질이 기존 골든셋보다 나빠지지 않았음을 증명한 뒤 트래픽을 전환한다.

권장 방식은 **점진 전환(Strangler) + 짧은 최종 절체**다. API 경로 일부를 장기간 두 구현에 나누는 방식은 인증·암호화·큐 소유권을 복잡하게 하므로, 도메인 로직은 병행 구현하되 실제 쓰기 트래픽은 검증 완료 후 API와 워커를 함께 전환한다.

## 2. 현재 기준선

### 2.1 현재 구성

| 영역 | 현재 구현 | 전환 판단 |
|---|---|---|
| 웹 UI | React 18.3, TypeScript, Vite, React Router | 유지 |
| API | Python 3.12, FastAPI, Pydantic | Kotlin으로 교체 |
| 데이터 접근 | SQLAlchemy async, PostgreSQL 16, Alembic | Spring JDBC, Flyway로 교체 |
| 비동기 작업 | ARQ + Redis | PostgreSQL 기반 내구성 작업 큐로 교체 권장 |
| LLM | OpenAI·Anthropic provider 추상화 | 동일 인터페이스를 Kotlin으로 포팅 |
| 문서 처리 | docx·pdf·hwpx 추출, docx·txt·hwpx 내보내기 | JVM 구현으로 포팅 |
| 개인정보 | 마스킹 후 LLM 전송, Fernet 암호화 저장 | 데이터 호환성 게이트 필수 |
| 배포 | API·worker·migrate 동일 Python 이미지, React nginx 이미지 | Kotlin API·worker 동일 빌드 산출물 + React nginx 유지 |

현재 제품 런타임 Python 코드는 약 8,169줄이고, React 소스는 약 4,063줄이다. 2026-08-11 로컬 기준으로 백엔드는 `810 passed, 68 skipped, 4 deselected`, React는 `60 passed`이며 TypeScript·ESLint·Prettier 검사와 production build도 통과했다. DB·외부 LLM 조건이 없는 로컬 결과이므로 실제 운영 E2E 증거와는 구분한다.

### 2.2 보존해야 할 외부 계약

현재 14개 HTTP 엔드포인트를 Kotlin 전환의 v1 계약으로 동결한다.

- 상태 코드: 가입 201, 업로드 202, 삭제 204, 입력 오류 422, 충돌 409, 인증 오류 401, 소유권 은닉 404, 의존 서비스 오류 5xx
- 오류 본문: `{"detail": ...}` 형태 유지
- JSON 필드: 기존 `snake_case` 유지
- 인증: `Authorization: Bearer`, JWT HS256, 기존 `sub`·`exp`·`typ` 의미 유지
- 개인정보 응답: `Cache-Control: no-store`, `X-Content-Type-Options: nosniff` 유지
- 업로드 접수: `Location` 응답 헤더 유지
- 파일 다운로드: 미디어 타입과 RFC 5987 `Content-Disposition` 유지
- CORS: 허용 origin·메서드·헤더·노출 헤더 유지
- 접근 통제: 다른 사용자의 자원은 403이 아니라 404로 응답
- 변환 상태: `pending | processing | done | failed` 유지
- 입력 제한: 본문 4,000자, 파일 10MB, 지원 형식 docx·pdf·hwpx 유지

계약 파일 `contracts/easy-doc-v1.yaml`을 먼저 만들고 FastAPI OpenAPI 결과와 현재 React 타입을 대조한다. 이후 Kotlin API와 React 타입 생성의 단일 기준으로 사용한다. Spring 기본 오류 응답인 `ProblemDetail`은 그대로 노출하지 않고 현재 `detail` 계약에 맞춘 전역 예외 매퍼를 둔다.

### 2.3 반드시 보존할 내부 정책

- 원문은 마스킹한 뒤에만 LLM으로 보낸다.
- LLM 호출은 변환 1회 + 조건부 보정 1회, 최대 2회다.
- 응답 절단, 빈 결과, 자리표시자 유실, 보정 악화 판정은 현재와 동일해야 한다.
- AI 초안과 검수 수정본은 별도 보존하며 수정률 KPI 원천을 유지한다.
- 원문·결과·마스킹 대응표는 평문으로 DB나 로그에 남기지 않는다.
- 보존 만료 파기는 매일 04:00 KST, 500건 단위로 수행한다.
- 삭제는 문서와 변환을 함께 파기하며, 문서가 든 작업 공간과 마지막 작업 공간은 삭제하지 않는다.
- HWPX는 최소한 생성 후 자체 추출기로 다시 읽어 본문이 일치해야 한다. 한컴 오피스 실제 호환성은 별도 사람 검증으로 남는다.

## 3. 목표 아키텍처

```text
브라우저
  └─ nginx :8080
      ├─ /, assets ── React + TypeScript 정적 파일
      └─ /api/* ───── Kotlin Spring Boot API
                           ├─ PostgreSQL: 사용자·문서·변환·작업
                           └─ LLM 요청 등록

Kotlin Worker
  ├─ PostgreSQL 작업 lease 획득
  ├─ 마스킹 → LLM → 규칙 검사 → 조건부 보정
  └─ 암호화 결과·상태 저장
```

### 3.1 권장 기술 선택

| 항목 | 선택 | 이유 |
|---|---|---|
| JVM | Java 21 | 장기 지원 기반과 넓은 라이브러리 호환성 확보 |
| 언어 | Kotlin 2.2 이상 | Spring Boot 공식 Kotlin 요구 범위 준수; 실제 버전은 Boot BOM으로 정렬 |
| 프레임워크 | Spring Boot 4.1 계열 후보 | 현재 공식 안정 버전 기준으로 spike 후 고정 |
| 웹 | Spring MVC | JPA/JDBC와 문서 라이브러리가 blocking이므로 WebFlux 전면 도입 이득이 작음 |
| DB | Spring `JdbcClient` 또는 Spring Data JDBC | 현재 스키마·소유권 조건·잠금 SQL을 명시적으로 보존하기 쉬움 |
| 마이그레이션 | Flyway | Kotlin 런타임과 Gradle/Boot 통합, SQL 이력 명시 |
| 테스트 | JUnit, Kotest 선택 사용, Testcontainers, MockWebServer/WireMock | 단위·DB·외부 API 계약 분리 |
| LLM | OpenAI·Anthropic 공식 Java SDK를 provider 어댑터 안에서만 사용 | 기존 벤더 추상화 보존 |
| 문서 | Apache POI, Apache PDFBox, 안전한 ZIP/XML 파서 | JVM 생태계에서 docx·pdf·hwpx 처리 |
| 관측 | Micrometer + 구조화 로그 | 상태·지연·실패 코드만 기록하고 본문은 금지 |

정확한 라이브러리 버전은 구현 시작 시 호환성 spike를 통과한 조합을 Gradle lockfile과 version catalog에 고정한다. Spring Boot·Kotlin·Jackson·LLM SDK를 각자 임의 버전으로 섞지 않는다.

### 3.2 Gradle 구조

```text
backend-kotlin/
  settings.gradle.kts
  gradle/libs.versions.toml
  core/             # 마스킹, 스타일 규칙, 프롬프트, 후처리, 도메인 타입
  application/      # 인증, 문서, 작업 공간, 변환 유스케이스
  infrastructure/   # JDBC, 암호화, 문서 파서, LLM provider, 작업 큐
  api/              # Spring MVC, 인증 필터, 오류·응답 계약
  worker/           # 변환 worker, 보존 만료 scheduler
```

`core`는 Spring과 DB 의존성 없이 테스트 가능하게 둔다. API와 worker는 `application`과 `infrastructure`를 공유하지만 서로의 실행 진입점에는 의존하지 않는다. 최종 컨테이너 이미지는 같은 JAR/레이어를 사용하고 실행 profile만 `api`, `worker`, `migrate`로 구분한다.

## 4. 주요 설계 결정

### 4.1 React UI는 유지하고 계약 경계만 교체

현재 화면과 라우트는 그대로 유지한다.

- `/login`, `/signup`, `/`, `/conversions/:id`, `/history`
- `AuthProvider`, `WorkspaceProvider`, polling, 미저장 변경 경고
- 로그인·업로드·검수·내보내기·기록·작업 공간 동작

변경 대상은 다음으로 제한한다.

1. 수기 타입인 `frontend/src/api/types.ts`를 OpenAPI 생성 타입으로 대체한다.
2. `frontend/src/api/client.ts`의 토큰, 401 처리, 네트워크 오류, 파일 다운로드 래퍼는 유지한다.
3. Kotlin 오류 응답을 현재 한국어 사용자 메시지로 바꾸는 어댑터를 검증한다.
4. polling 중 상태 변화를 `aria-live`로 알리고, 키보드 초점·레이블·대비를 KWCAG 기준으로 점검한다.
5. 시각 디자인 개편은 API 전환과 분리한다. React 메이저 버전 업그레이드도 Kotlin 절체 후 별도 작업으로 진행한다.

### 4.2 데이터베이스와 Flyway 인수

기존 테이블·컬럼·제약 이름은 첫 절체 때 바꾸지 않는다. ORM 교체와 스키마 재설계를 동시에 하지 않는다.

1. 실제 대상 DB의 schema-only dump와 `alembic_version`을 수집한다.
2. Alembic `0001~0006`의 기대 스키마와 실제 스키마를 비교한다. README에 `0003` 제자리 수정 이력이 있으므로 파일만 믿지 않는다.
3. 빈 DB용 `V1__python_schema_baseline.sql`을 만든다.
4. 기존 DB는 schema checksum이 일치할 때만 Flyway baseline version 1을 기록한다.
5. Kotlin 전용 변경은 `V2`부터 추가한다.
6. 절체 시점부터 한 환경에서 Alembic과 Flyway를 함께 실행하지 않는다.
7. Python 제거 전까지 `alembic_version`은 보존하고 Kotlin이 수정하지 않는다.

모든 초기 변경은 additive로 한다. 기존 컬럼 삭제·이름 변경·타입 축소는 Python 제거와 관찰 기간 종료 후 별도 마이그레이션으로 미룬다.

### 4.3 암호화 호환성 게이트

현재 DB 본문은 Python `cryptography`의 Fernet 토큰이다. 이 호환성을 증명하지 못하면 Kotlin API가 기존 문서를 읽을 수 없으므로 가장 먼저 spike한다.

1. Python에서 한글·ASCII·빈 값·긴 값·변조 값·다른 키의 교차 런타임 fixture를 만든다.
2. 유지보수 상태와 보안 검토가 가능한 JVM Fernet 구현이 모든 fixture와 tamper 검증을 통과하는지 확인한다.
3. 통과하면 절체·롤백 관찰 기간에는 `fernet-v1` 읽기/쓰기를 유지한다.
4. 통과하지 못하면 직접 암호 알고리즘을 즉흥 구현하지 않는다. 유지보수 창에서 Python 이관 도구로 표준 AEAD envelope로 재암호화하고, 행 수·키 버전·복호화 샘플을 검증하는 별도 승인 작업으로 분리한다.
5. 향후 암호 방식 변경을 위해 `encryption_scheme`과 `key_version`을 함께 관리한다.

기존 Argon2 PHC 문자열도 Kotlin에서 그대로 검증해야 한다. 로그인 성공 시에만 새 파라미터로 재해시하는 현재 정책을 유지하고, JWT는 Python 발급 토큰을 Kotlin이 읽고 Kotlin 발급 토큰을 Python이 읽는 양방향 fixture로 검증한다.

### 4.4 비동기 작업 큐

ARQ의 Redis 내부 직렬화 형식을 Kotlin에서 흉내 내지 않는다. 권장 최종 구조는 PostgreSQL `conversion_jobs` 테이블과 lease 기반 worker다.

예시 필드:

- `conversion_id` PK/FK
- `state`, `attempts`, `next_attempt_at`
- `lease_owner`, `lease_until`
- `created_at`, `updated_at`

문서·변환·작업 행을 같은 DB 트랜잭션에서 저장하면 “DB 커밋 성공, 큐 등록 실패” 간극이 사라진다. worker는 `FOR UPDATE SKIP LOCKED`로 작업을 가져가고 lease 만료 시 재처리한다. 완료된 변환은 다시 LLM을 호출하지 않도록 기존 idempotency 검사를 유지한다.

- 도메인 실패, 잘린 결과, provider 설정 오류: `failed`로 확정하고 자동 재시도하지 않는다.
- DB·일시 네트워크 오류: 제한된 횟수와 backoff로 재시도한다.
- 로그: conversion id, 상태, 시도 횟수, failure code만 기록한다.
- 보존 파기: 04:00 KST scheduler + PostgreSQL advisory lock으로 다중 worker 중복 실행을 막고 500건씩 commit한다.

전환 후 Redis가 다른 기능에 쓰이지 않는 것을 다시 확인한 뒤 Redis 서비스와 볼륨을 제거한다. 관찰 기간 전에는 롤백을 위해 보존한다.

### 4.5 문서 추출·내보내기

| 형식 | Kotlin 후보 | 필수 검증 |
|---|---|---|
| DOCX 입력·출력 | Apache POI XWPF | 본문, 표, 머리글·바닥글, 텍스트박스, 문단 줄바꿈 |
| PDF 입력 | Apache PDFBox | 페이지 순서, 텍스트 없는 PDF 거절, 크기·페이지 제한 |
| HWPX 입력·출력 | ZIP + namespace-aware StAX/JAXP | DTD/외부 엔터티 차단, zip bomb 제한, 자체 round-trip |
| TXT 출력 | JVM UTF-8 | BOM 없음, 제어문자 제거 |

현재 Python docx 추출기가 비공개 XML 요소까지 직접 순회하므로 단순 POI 텍스트 추출만으로는 동등하지 않을 수 있다. 기존 `tests/ingest/fixtures`와 골든 문서를 양쪽 구현에 넣고 정규화된 텍스트를 비교한다. 포팅 불가능한 요소는 조용히 누락하지 말고 지원 한계 또는 실패로 명시한다.

입력 10MB, 압축 해제 예산, 제어문자 제거, DTD 거절, 스캔 PDF 거절, HWPX mimetype·ZIP 엔트리 순서 조건도 테스트로 옮긴다.

### 4.6 LLM과 쉬운 글 품질

Kotlin `LlmProvider`는 provider SDK 응답을 다음 공통 타입으로만 노출한다.

- text
- provider/model
- input/output token
- finish reason 및 truncated 여부

프롬프트와 246개 수준의 어려운 말 사전, 마스킹 규칙, 스타일 검사, 보정 채택 규칙을 우선 byte-for-byte 또는 정규화 동등하게 포팅한다. 품질 개선을 섞지 않는다.

전환 게이트는 다음 순서다.

1. Python/Kotlin 동일 fake provider 단위 테스트
2. 현재 `tests/golden/documents`의 56개 골든 문서 스키마·팩트·스타일 규칙 검사
3. 고정 응답 fixture로 보정 호출 횟수·채택 결과 비교
4. 실제 provider 소량 비교: 모델, 파라미터, max token, Anthropic effort가 동일한지 확인
5. 전체 LLM 골든 평가는 별도 비용 승인 후 실행하고 기존 기준선보다 악화되지 않아야 함

LLM SDK 자체의 자동 retry와 worker retry가 겹쳐 호출 수가 늘지 않도록 한 계층만 재시도 책임을 가진다. 문서당 최대 2회라는 제품 계약은 네트워크 재전송과 별도로 메트릭에 드러나야 한다.

## 5. 단계별 실행 계획

### Phase 0. 범위·계약 동결 — 3~5일

- `contracts/easy-doc-v1.yaml` 작성
- API 응답, 헤더, 오류, 인증, 권한, 입력 상한을 contract test로 고정
- 대상 DB와 보존할 파일럿 데이터 유무 확인
- “런타임만 Kotlin”과 “오프라인 도구까지 Python 제거” 범위 승인
- Fernet·Argon2·JWT, DOCX/PDF/HWPX 라이브러리 spike

**종료 조건**: 암호문을 Kotlin에서 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨. 확인되지 않으면 일정 산정부터 다시 한다.

### Phase 1. Kotlin 골격과 CI — 1주

- `backend-kotlin` Gradle 멀티모듈 생성
- Java/Kotlin toolchain, dependency locking, ktlint/detekt, 테스트 설정
- `/health`, 설정 바인딩, 구조화 로그, 비밀값 마스킹
- Testcontainers PostgreSQL과 Flyway baseline 구축
- Dockerfile과 compose의 Kotlin profile 추가
- CI에 Kotlin build/test를 추가하되 기존 Python/React gate 유지

**종료 조건**: 빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health`가 응답함.

### Phase 2. 순수 도메인 로직 포팅 — 1.5~2주

- 개인정보 마스킹
- 텍스트 정규화·제어문자 제거
- 프롬프트 렌더링과 동적 어려운 말 목록
- 스타일 규칙, 보정 채택, placeholder 보존 검사
- 내보내기 파일명과 Content-Disposition 생성
- Python/Kotlin 공용 JSON fixture 및 differential test 작성

**종료 조건**: 외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄.

### Phase 3. 데이터·인증·작업 공간 API — 1.5~2주

- Spring JDBC repository와 트랜잭션 경계
- Argon2, JWT, 가입과 기본 작업 공간 원자 생성
- `/auth/*`, `/workspaces/*`
- 소유권을 숨기는 404와 unique/check/FK 오류 매핑
- React를 Kotlin API에 연결한 로그인·작업 공간 E2E

**종료 조건**: 기존 React 테스트와 API contract test가 Kotlin API에서도 통과함.

### Phase 4. 문서 API·암호화·내보내기 — 2~3주

- JSON/multipart 업로드와 제한 처리
- DOCX/PDF/HWPX 추출
- 암호화 저장·복호화 조회
- 문서 목록·삭제, 변환 조회·검수 저장
- DOCX/TXT/HWPX 내보내기
- 기존 fixture와 cross-runtime 암호화·문서 differential test

**종료 조건**: 실제 PostgreSQL에서 업로드 → 조회 → 검수 → 3형식 다운로드 → 삭제가 모두 통과하고 평문이 DB·로그에 없음.

### Phase 5. LLM provider·worker·보존 파기 — 2~2.5주

- OpenAI/Anthropic provider 어댑터
- PostgreSQL job table, lease, retry/backoff, crash recovery
- API 저장과 작업 등록의 단일 트랜잭션
- 04:00 KST 보존 파기와 다중 worker 잠금
- fake provider 통합 테스트와 실제 provider 소량 smoke

**종료 조건**: worker 강제 종료·재기동 시 중복 결과나 이중 LLM 완료가 없고, 실패 코드·재시도 정책이 기존 계약과 일치함.

### Phase 6. React 통합·접근성·전체 E2E — 1~1.5주

- OpenAPI 생성 타입으로 React 수기 타입 교체
- 업로드 polling, 실패 메시지, 세션 만료, 미저장 경고 검증
- 키보드 탐색, focus, label, aria-live, 색 대비 점검
- nginx 동일 origin `/api` 프록시와 개발 CORS 검증
- compose 기반 가입 → 업로드 → 변환 → 검수 → 다운로드 → 기록 → 삭제 E2E

**종료 조건**: React check/test/build와 브라우저 E2E가 모두 Kotlin 스택에서 통과함.

### Phase 7. 절체·관찰·롤백 — 절체 1일 + 관찰 1~2주

1. DB 백업, 행 수, 상태별 conversion 수, 스키마 checksum 기록
2. 신규 업로드를 잠시 중단
3. ARQ queue를 비우고 `pending/processing` 잔존 작업을 확인
4. Python API·worker 중지
5. Flyway 신규 migration 적용
6. Kotlin API·worker 기동
7. 전용 synthetic 계정으로 전체 smoke 실행
8. React 트래픽 연결 후 업로드 재개
9. 실패율·p95 지연·pending 체류·LLM 호출 수·내보내기 실패를 관찰

즉시 중단 기준:

- 기존 Fernet 문서 복호화 실패
- 다른 사용자 데이터 노출 또는 404 소유권 규칙 위반
- 마스킹 전 본문이 LLM이나 로그로 전송됨
- 중복 LLM 호출, 작업 영구 유실, pending 무한 체류
- 문서 추출·내보내기 주요 fixture 불일치
- 골든 품질 바닥 또는 최대 2회 호출 계약 위반

롤백은 Kotlin API·worker를 모두 정지한 뒤 수행한다. Kotlin DB 작업을 ARQ로 다시 등록하는 검증된 one-shot 도구를 준비하고, 관찰 기간에는 Python 이미지·Redis·기존 키를 보존한다. Kotlin이 새 암호 방식으로 쓰기 시작하는 변경은 이 관찰 기간 뒤에만 허용한다.

### Phase 8. Python 런타임 제거와 문서 동기화 — 1주

- `app/`, ARQ, FastAPI, SQLAlchemy, Alembic 런타임 제거
- Python API/worker Dockerfile과 compose 서비스 제거
- Redis가 무사용임을 확인하고 서비스·환경변수·볼륨 정의 제거
- README, master-plan 6장, 운영 runbook, 장애 대응 명령을 Kotlin 기준으로 갱신
- Python용 비밀값·의존성·CI gate 정리

오프라인 골든셋·벤치마크·수집·파일럿 리포트 도구는 런타임 절체 후 별도 판단한다. 완전한 “저장소 Python 0”이 목표라면 아래 추가 단계가 필요하다.

### Phase 9. 오프라인 도구 Kotlin 전환 — 추가 2~3주, 선택

- `scripts/benchmark.py`, `collect_*`, `pilot_report.py`
- `easyread/goldenset.py`, `judge.py`, `collection.py`, `bokjiro.py`
- 관련 fixture·CLI·리포트 형식

독립 검증 oracle 역할을 하는 Python 골든 도구는 Kotlin 런타임이 안정될 때까지 남겨 두는 편이 안전하다. 동일 결과가 확인된 뒤 제거한다.

## 6. 검증 매트릭스

| 게이트 | 검증 내용 | 통과 기준 |
|---|---|---|
| Build | Gradle, TypeScript | warning 정책 포함 모두 성공 |
| Unit | core, application, React | 신규 Kotlin 테스트 + 기존 React 60개 이상 통과 |
| Contract | 14 endpoints | status/body/header/error가 v1 spec과 일치 |
| DB | PostgreSQL Testcontainers | 제약, 트랜잭션, 잠금, cascade, timezone 일치 |
| Crypto | Python ↔ Kotlin | Fernet/JWT/Argon2 양방향 fixture와 tamper test 통과 |
| Document | docx/pdf/hwpx/txt | 기존 fixture 추출·round-trip 동등, 제한 우회 없음 |
| Worker | lease/retry/crash | 유실·이중 완료 없음, failure code와 retry 구분 일치 |
| Quality | 골든셋 | 스타일·팩트·judge가 승인 기준선보다 악화되지 않음 |
| Security | 소유권·로그·캐시 | 교차 사용자 접근 0, 평문 로그 0, private header 유지 |
| E2E | compose + browser | 핵심 사용자 여정 전부 성공 |
| Ops | cutover/rollback | 리허설 환경에서 양방향 절차 성공 |

기존 Python 테스트 878개를 줄 단위로 모두 번역하는 것이 목표는 아니다. 각 테스트가 보장하던 행동을 계약·도메인·통합·E2E 계층에 재배치하고, 누락된 보장 목록이 0인지 추적표로 관리한다. Python suite는 절체까지 계속 실행해 비교 기준으로 사용한다.

## 7. 예상 일정과 인력

런타임 전환은 약 **11~14 person-weeks**, 오프라인 도구까지 제거하면 **13~17 person-weeks**로 본다. 문서 호환성과 Fernet spike 결과에 따라 변동 폭이 가장 크다.

- 1명 순차 수행: 런타임 절체 12~16주 + 관찰 1~2주
- 2명 병렬 수행: 백엔드/문서·품질과 React/계약·E2E를 나누면 7~10주 + 관찰 1~2주

일정 단축을 위해 품질·보안 gate를 생략하지 않는다. 시각 UI 개편, React 메이저 업그레이드, DB 재설계, 새로운 결제·RAG 기능은 이 일정에 포함하지 않는다.

## 8. 완료 정의

### 런타임 전환 완료

- 운영·compose 경로에 Python API/worker가 없다.
- React가 Kotlin API만 호출한다.
- 기존 사용자, Argon2 해시, JWT 전환 정책, 문서와 암호문이 보존된다.
- 핵심 사용자 E2E와 계약·보안·품질 gate가 통과한다.
- Kotlin worker의 유실·중복·재시도·파기 동작이 운영 지표로 확인된다.
- rollback 리허설과 운영 문서가 완료된다.

### 저장소 전체 전환 완료

- 위 조건에 더해 골든 평가, 벤치마크, 수집, 파일럿 리포트 CLI가 Kotlin으로 전환된다.
- `pyproject.toml`, `uv.lock`, Python CI와 Python 소스가 제거된다.
- Python을 독립 oracle로 사용한 마지막 parity 결과가 보관된다.

## 9. 착수 전 승인할 결정

1. 목표가 “제품 런타임 Kotlin화”인지 “오프라인 도구를 포함한 Python 완전 제거”인지
2. 파일럿/보존 대상 DB가 있는지, 유지보수 창을 사용할 수 있는지
3. Fernet JVM 호환 구현 승인 여부와 실패 시 재암호화 방식
4. PostgreSQL 작업 큐로 전환하며 Redis를 최종 제거할지
5. 시각 UI 개편을 이번 전환과 분리하는 원칙 승인

이 다섯 결정을 Phase 0에서 고정한 뒤 구현 일정을 확정한다.

## 10. 공식 기술 근거

- Spring Boot Kotlin 지원 및 버전 요구: <https://docs.spring.io/spring-boot/reference/features/kotlin.html>
- Spring MVC와 WebFlux 선택 기준: <https://docs.spring.io/spring/reference/languages/kotlin/getting-started.html>
- Spring Boot 시스템 요구사항: <https://docs.spring.io/spring-boot/system-requirements.html>
- Flyway Spring Boot 통합: <https://documentation.red-gate.com/flyway/reference/usage/community-plugins-and-integrations/community-plugins-and-integrations-spring-boot>
- OpenAI 공식 Java SDK: <https://github.com/openai/openai-java>
- Anthropic 공식 Java SDK: <https://github.com/anthropics/anthropic-sdk-java>
