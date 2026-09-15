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

## 문서 처리·메일 설정

- `EASYDOC_MAX_CONCURRENT_EXTRACTIONS`: 프로세스별 동시 추출 수. 기본값은 `4`이며 양수만 허용한다. 추출 문자 수·압축 해제량의 계약상 보안 상한은 바꾸지 않는다.
- `EASYDOC_MAIL_TEMPLATES_LOCATION`: 인증·비밀번호·변환 완료 메일의 UTF-8 properties 파일 위치. 기본값은 `classpath:mail/notifications.properties`다. 외부 파일은 `file:/etc/easydoc/notifications.properties`처럼 지정한다.

메일 문구는 [기본 템플릿](infrastructure/src/main/resources/mail/notifications.properties)을 복사해 수정한다. 모든 항목과 본문의 `{code}`, `{minutes}`, `{title}`, `{url}` 등 해당 템플릿의 변수는 유지해야 한다. 제목에는 변수를 넣을 수 없다. 설정은 기동 시 읽으므로 변경 후 프로세스를 재시작한다. 기본 템플릿의 `\n`은 줄바꿈이다.

추출·내보내기 구현은 전략 인터페이스를 구현하고 해당 팩토리에 등록한다. 공통 레지스트리가 지원 형식의 누락·중복을 조립 시 검사한다.

## 검증

```bash
./gradlew build
```

실제 LLM을 호출하는 `testLlm`은 비용 승인을 받은 경우에만 별도로 실행한다.
