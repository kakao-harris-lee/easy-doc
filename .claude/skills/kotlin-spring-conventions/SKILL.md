---
name: kotlin-spring-conventions
description: Easy-Read AI의 Kotlin/Spring Boot 제품 코드를 구현하거나 수정할 때 적용하는 모듈 경계, API, 영속성, 보안, 테스트 규칙. Kotlin을 변경하지 않는 문서·설정·상태 작업에는 사용하지 않는다.
---

# Kotlin/Spring Conventions

## 모듈 경계

- `core`: 순수 도메인 타입과 규칙. Spring·DB·벤더 SDK에 의존하지 않는다.
- `application`: 유스케이스, port, 트랜잭션 경계.
- `infrastructure`: JDBC, 암호화, 문서 파서, LLM provider, 작업 큐 adapter.
- `api`: controller, 요청·응답 DTO, 인증, HTTP 예외 변환.
- `worker`: 큐 소비와 예약 작업의 실행 진입점.

의존 방향은 바깥에서 안쪽으로 향하게 하고, 기존 `moduleBoundaryCheck`를 우회하지 않는다.

## 구현 규칙

- constructor injection과 불변 데이터를 기본으로 사용한다.
- API 입출력은 명시적인 data class로 정의하고 `Map<String, Any>`를 공개 계약에 사용하지 않는다.
- JSON snake_case와 enum 값은 계약 파일을 따른다.
- 도메인 실패는 타입이 있는 예외나 결과로 표현하고 전역 HTTP 매퍼에서 응답으로 변환한다.
- 트랜잭션은 유스케이스 경계에 두고 controller와 순수 도메인 코드에 분산하지 않는다.
- 스키마 변경은 새 Flyway migration으로 추가한다. 이미 적용된 migration을 다시 쓰지 않는다.
- 외부 SDK 타입과 예외를 `infrastructure` 밖으로 노출하지 않는다.

## 보안 규칙

- 문서 본문은 마스킹 이후에만 LLM provider로 전달한다.
- 문서 본문, 개인정보, 비밀번호, 토큰, 키, 암복호화 material을 로그에 남기지 않는다.
- 다른 사용자의 자원은 계약에 따라 404로 숨긴다.
- 암호화와 인증은 표준 라이브러리를 사용하고, 경계값과 변조·만료·잘못된 키 경로를 테스트한다.
- 오류 응답과 보안 헤더는 handler 밖의 실패 경로에서도 유지되는지 확인한다.

## 테스트 규칙

- `core` 규칙은 Spring context 없이 단위 테스트한다.
- application은 fake port로 유스케이스와 트랜잭션 의도를 검증한다.
- infrastructure는 필요한 경우 Testcontainers로 실제 PostgreSQL 동작을 검증한다.
- API는 상태 코드, 본문, 헤더, 인증, 소유권 경계를 contract test로 검증한다.
- LLM 호출 테스트는 `FakeLlmProvider`를 사용하고 실제 provider를 호출하지 않는다.
- 버그 수정은 재현 테스트를 먼저 추가한다.

## 검증

변경 모듈의 test/check를 먼저 실행하고 완료 전 전체 build를 실행한다.

```bash
cd backend-kotlin
./gradlew build
```

검사를 실행하지 못했으면 통과로 표현하지 않는다.
