# CLAUDE.md — Easy-Read AI MVP 개발 지침

공공기관용 쉬운 글 자동 변환 SaaS다. 제품 범위와 정책의 기준은 `docs/master-plan.md`, 구현할 기능의 현재 목록은 `docs/kotlin-redevelopment-backlog.md`, 외부 HTTP 계약의 기준은 `contracts/easy-doc-v1.yaml`이다.

## 현재 목표

현재 단계는 Lean MVP 개발이다. 기존 Kotlin/Spring Boot 백엔드와 React 프론트엔드의 미완성 상태에서, 사용자가 요청한 MVP 기능을 작은 수직 단위로 완성한다.

- 제품 런타임은 Kotlin/Spring Boot 하나다.
- Python 구현이나 비교용 parity 체계를 새로 만들지 않는다.
- PG 결제, RAG 사전, 어드민 등 MVP 밖 기능은 사용자가 명시하지 않으면 구현하지 않는다.
- 현재 코드와 backlog가 다르면 코드를 먼저 확인하고, 차이를 보고한 뒤 요청 범위만 처리한다.

## 스킬과 에이전트 사용 범위

- 제품 기능 구현: `mvp-development`
- Kotlin/Spring Boot 코드 작성 또는 변경: `kotlin-spring-conventions`
- 외부 API 계약 변경: `api-contract`
- 범위가 큰 제품 기능을 위임할 때만 `mvp-builder` 에이전트 사용

상태 확인, 설명, 문서만 작성, 계획만 작성, 설정 정리, 단순 리팩터링에는 위 스킬과 에이전트를 자동으로 호출하지 않는다. 필요한 파일을 직접 확인하고 요청 자체를 처리한다.

## 작업 방식

1. 요청과 관련된 현재 코드, 테스트, 계약, backlog를 먼저 확인한다.
2. 이미 있는 구현을 재사용하고 요청한 범위를 넘어 기능을 확장하지 않는다.
3. 라이브러리 선택이나 버전별 API가 중요한 경우에만 공식 문서로 확인한다.
4. 작은 작업은 바로 구현한다. 별도 계획 문서는 사용자가 요청했거나 여러 모듈·데이터 변경이 얽힌 경우에만 작성한다.
5. 새 기능에는 테스트를, 버그 수정에는 재현 테스트를 동반한다.
6. 관련 검증을 실행하고 실행하지 못한 항목은 명시한다.

## 기술 스택

- Backend: Kotlin, Spring Boot, Gradle 멀티모듈
- Database: PostgreSQL + pgvector, Flyway
- 비동기 처리: PostgreSQL lease 기반 작업 큐
- Frontend: React, TypeScript, Vite
- LLM: `core`의 `LlmProvider` 인터페이스와 `infrastructure` 어댑터

## 아키텍처 규칙

- 모듈 책임은 `core` → `application` → `infrastructure` → `api`/`worker` 경계를 지킨다.
- `core`는 Spring과 DB 없이 테스트할 수 있어야 한다.
- 컨트롤러는 요청·응답 DTO와 HTTP 변환만 담당한다.
- 유스케이스와 트랜잭션 경계는 `application`에 둔다.
- DB, 암호화, 문서 파서, LLM SDK는 `infrastructure`에 둔다.
- LLM 호출 전에 주민등록번호와 카드번호 마스킹을 완료한다.
- 벤더 SDK 타입을 `core`나 API 응답으로 노출하지 않는다.
- 사용자 문서 본문, 개인정보, 토큰, 암호화 키를 로그에 남기지 않는다.

## API와 데이터 규칙

- 공개 API 변경은 `contracts/easy-doc-v1.yaml`, Kotlin contract test, 프론트 타입·호출부를 같은 변경 단위로 맞춘다.
- JSON 필드는 snake_case를 유지한다.
- 다른 사용자의 자원은 존재를 숨기기 위해 404로 응답한다.
- 오류 응답, 보안 헤더, 입력 상한은 계약 파일을 따른다.
- DB 스키마 변경은 Flyway migration으로 수행하며 기존 migration을 임의로 다시 쓰지 않는다.

## 검증 명령

변경 범위에 맞는 최소 검증을 먼저 실행하고, 완료 전에는 관련 스택의 전체 검증을 실행한다.

```bash
cd backend-kotlin && ./gradlew build
cd frontend && npm run check
cd frontend && npm run test -- --run
cd frontend && npm run build
docker compose config
```

외부 서비스, 실제 LLM 호출, 배포, push는 사용자 승인 없이 수행하지 않는다.

## 완료 보고

완료 보고에는 구현한 MVP 범위, 변경한 주요 파일, 실행한 검증과 결과, 남은 미구현 항목을 포함한다. 미구현 항목을 이번 작업의 완료처럼 표현하지 않는다.
