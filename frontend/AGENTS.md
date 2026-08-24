# React 프런트엔드 범위

이 디렉터리는 독립 npm 프로젝트다. 서버 내부를 추측하지 않고 `../contracts/easy-doc-v1.yaml`을 외부 API 기준으로 사용한다.

- 화면/상태/접근성은 `src/` 안에서 처리한다.
- HTTP 호출과 wire type은 `src/api/` 한 경계에 둔다.
- 백엔드 Gradle/Kotlin 파일은 수정하지 않는다.
- API 계약 결함을 발견하면 임시 클라이언트 우회보다 계약 변경 필요성을 먼저 기록한다.
- 완료 전 `npm run check`, `npm run test -- --run`, `npm run build`를 실행한다.
