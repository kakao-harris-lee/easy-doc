# Easy-Read AI 작업 범위

제품 런타임은 Kotlin/Spring Boot 백엔드와 React/TypeScript 프런트엔드뿐이다. Python 구현, Python 비교 하네스, parity 작업을 만들지 않는다.

## 모델과 비용 상한

- 구현·테스트·디버깅·리팩터링은 Sonnet 5 또는 Codex로 직접 수행한다.
- Opus 5는 명시적으로 요청된 계획·아키텍처 검토 전용이다. Opus가 파일을 수정하거나 구현을 계속하면 안 된다.
- 서브에이전트, 오케스트레이션, 하네스, 스킬 체인, 자동 병렬화는 사용하지 않는다.
- 한 작업에서 계획 모델과 구현 모델을 이어서 실행하지 않는다. 계획 세션은 계획을 전달하고 종료하며 구현은 Sonnet/Codex의 새 작업이다.
- 실제 유료 LLM 호출은 별도 승인 없이는 실행하지 않는다.
- 비대화형 자동 실행은 명시적인 달러 예산 상한 없이 시작하지 않는다.

## 기본 범위

- 요청에 이름이 나온 프로젝트 디렉터리와 직접 연결된 테스트만 먼저 읽는다.
- `backend-kotlin/` 작업은 `frontend/`를 읽거나 고치지 않는다. API 계약이 바뀌는 경우에만 `contracts/`와 필요한 프런트 API 파일까지 범위를 확장한다.
- `frontend/` 작업은 `backend-kotlin/` 내부 구현을 읽거나 고치지 않는다. 서버 동작은 `contracts/easy-doc-v1.yaml`을 기준으로 한다.
- 루트 작업은 Compose, CI, 계약, 공통 문서에 한정한다. 제품 로직을 루트에 추가하지 않는다.
- 계획/문서 요청에서 검증 하네스나 제품 기능을 임의로 구현하지 않는다.

## 계약 변경 단위

외부 HTTP 변경은 `contracts/easy-doc-v1.yaml`, Kotlin 계약 테스트/DTO, `frontend/src/api/`를 한 변경 단위로 맞춘다. 계약이 바뀌지 않으면 다른 프로젝트를 수정하지 않는다.

## 검증

- Backend: `cd backend-kotlin && ./gradlew build`
- Frontend: `cd frontend && npm run check && npm run test -- --run && npm run build`
- Integration: `docker compose config`

각 하위 디렉터리의 `AGENTS.md`가 더 좁은 범위를 정의한다.
