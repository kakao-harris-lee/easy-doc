# Kotlin 백엔드 범위

이 디렉터리는 독립 Gradle 프로젝트다. 작업 시작 시 요청과 관련된 모듈만 읽는다.

- `core`: 순수 Kotlin 도메인, 값 객체, 정책. Spring/DB/HTTP 의존 금지.
- `application`: 유스케이스, port, 트랜잭션 경계.
- `infrastructure`: DB, 암호화, 파서, LLM, 큐 adapter.
- `api`: HTTP DTO/컨트롤러/보안/구성.
- `worker`: 비동기 실행 진입점.

프런트 화면은 수정하지 않는다. 공개 API 변경이 필요한 경우에만 루트 계약을 먼저 갱신하고 `frontend/src/api/`의 영향 범위를 명시한다. 구현은 TDD로 진행하고 완료 전 `./gradlew build`를 실행한다.

