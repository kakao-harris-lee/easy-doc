# Easy-Read backend

Kotlin/Spring Boot로 구현된 독립 Gradle 프로젝트다. 모듈은 세 개의 코드 계층과 두 개의 실행 진입점으로 나뉜다.

| 모듈 | 책임 |
|---|---|
| `core` | 프레임워크와 무관한 도메인 타입 및 정책 |
| `application` | 유스케이스, 포트, 트랜잭션 경계 |
| `infrastructure` | JDBC, 암호화, 문서 입출력, LLM, 메일 어댑터와 Spring 조립 |
| `api` | HTTP API와 `migrate` 진입점 |
| `worker` | 큐·스케줄 작업과 일회성 운영 명령 |

제품 의존 방향은 `core <- application <- infrastructure`다. `api`와 `worker`는 `application`을 컴파일 의존하고 `infrastructure`는 런타임에만 조립한다.

## 검증

```bash
./gradlew build
```

실제 LLM을 호출하는 `testLlm`은 비용 승인을 받은 경우에만 별도로 실행한다.
