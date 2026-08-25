# CLAUDE.md — Easy-Read AI MVP 개발 지침

공공기관용 쉬운 글 자동 변환 SaaS다. 제품 범위와 정책의 기준은 `docs/master-plan.md`, 구현할 기능의 현재 목록은 `docs/kotlin-redevelopment-backlog.md`, 외부 HTTP 계약의 기준은 `contracts/easy-doc-v1.yaml`이다.

## 현재 목표

현재 단계는 Lean MVP 개발이다. 기존 Kotlin/Spring Boot 백엔드와 React 프론트엔드의 미완성 상태에서, 사용자가 요청한 MVP 기능을 작은 수직 단위로 완성한다.

- 제품 런타임은 Kotlin/Spring Boot 하나다.
- Python 구현이나 비교용 parity 체계를 새로 만들지 않는다.
- PG 결제, RAG 사전, 어드민 등 MVP 밖 기능은 사용자가 명시하지 않으면 구현하지 않는다.
- 현재 코드와 backlog가 다르면 코드를 먼저 확인하고, 차이를 보고한 뒤 요청 범위만 처리한다.

## 모델·비용 정책

- **구현 모델은 Sonnet 5다.** 코드 작성, 테스트, 디버깅, 리팩터링, 설정 변경, 문서 갱신을 Opus로 타이핑하지 않는다.
- 기본 모델은 `.claude/settings.json`의 `sonnet`이며, 구현 중 상위 모델로 자동 승격하지 않는다.
- **Opus 5는 계획·아키텍처 판단과 오케스트레이션에 허용한다.** Opus 세션이 파일을 직접 고치는 대신, 범위를 나눠 Sonnet 실행 에이전트에 위임하고 결과를 종합한다. 「생각은 Opus, 타이핑은 Sonnet」이 기본형이다.
- 계획은 Codex를 우선 사용해도 충분하다.
- 모델 fallback으로 비용이 불명확해지는 구성을 쓰지 않는다. 재귀 위임(서브에이전트가 다시 서브에이전트를 띄우는 것)을 하지 않는다.
- 비대화된 자동 실행(`claude -p`)에는 호출자가 `--max-budget-usd`를 반드시 지정한다. 저장소가 임의 예산을 정하지는 않으며 한도 없는 자동 실행을 허용하지 않는다.
- 외부 LLM 실제 호출과 유료 평가 실행은 사용자가 비용과 범위를 승인한 경우에만 수행한다.

## 위임 원칙

- **구현은 서브에이전트에 위임한다.** 주 에이전트는 요청 범위를 읽고 경계를 나눠 지침을 주고, 실행 에이전트가 파일을 고친다. 실행 에이전트의 모델은 Sonnet 5다.
- 위임 단위는 **되돌릴 수 있는 수직 조각**이다. 한 조각은 한 계층(계약·마이그레이션·core·application·infrastructure/api·프런트) 안에서 닫히거나, 닫히지 않으면 그 이유를 지침에 적는다.
- 서로 의존하지 않는 조각은 병렬로 위임해도 된다. 같은 파일을 만지는 조각은 직렬로 둔다.
- 위임 자체도 비용이다 — 한두 줄 수정·오타·설정 변경·단순 조회는 주 에이전트가 직접 처리한다.
- 저작과 검토는 다른 패스로 둔다. 구현한 에이전트가 같은 컨텍스트에서 자기 결과를 승인하지 않는다.
- 기존 Gradle/npm/Compose 검증을 사용한다. 에이전트용 검증 하네스, fingerprint, 별도 리뷰 루프를 새로 만들지 않는다.
- 동일 사실을 확인하려고 저장소 전체를 반복 탐색하지 않는다. 요청 경로 → 관련 테스트 → 계약 순으로 한 번씩 좁혀 읽고, 알아낸 사실은 위임 지침에 실어 서브에이전트가 다시 찾지 않게 한다.

## 작업 방식

1. 요청과 관련된 현재 코드, 테스트, 계약, backlog를 먼저 확인한다.
2. **TDD를 기본 순서로 사용한다.** 실패하는 테스트로 요구사항을 고정한 뒤 최소 구현으로 통과시키고, 마지막에 중복과 이름을 정리한다. 버그 수정은 재현 테스트가 먼저다.
3. 이미 있는 구현을 재사용하고 요청한 범위를 넘어 기능을 확장하지 않는다.
4. 라이브러리 선택이나 버전별 API가 중요한 경우에만 공식 문서로 확인한다.
5. 작은 작업은 바로 구현한다. 별도 계획 문서는 사용자가 요청했거나 여러 모듈·데이터 변경이 얽힌 경우에만 작성한다.
6. 관련 검증을 실행하고 실행하지 못한 항목은 명시한다.
7. 기능 구현시 리서치를 먼저 하고 라이브러리, 프레임워크를 적극 활용, 기존 구현된 부분 활용으로 바퀴를 새로 개발하지 않는다.

## 프로젝트 경계와 토큰 사용

- 루트 `AGENTS.md`와 작업 디렉터리의 `AGENTS.md`를 먼저 적용한다.
- 백엔드 작업은 `backend-kotlin/`, 프런트 작업은 `frontend/` 안에서 닫는다.
- 공개 API 변경일 때만 `contracts/easy-doc-v1.yaml`과 양쪽 소비자를 함께 연다.
- 상태·문서·계획 요청에서 제품 코드나 별도 검증 하네스를 만들지 않는다.
- 탐색은 요청 경로 → 관련 테스트 → 계약 순으로 좁게 진행한다. 저장소 전체 스캔은 통합 변경에서만 한다.

## 설계와 의존성 규칙

- 의존성은 constructor injection으로 전달한다. 서비스 내부에서 구체 구현을 `new`로 만들거나 전역 singleton, service locator, 정적 mutable 상태로 찾지 않는다.
- Spring `@Configuration`은 composition root다. 구현 선택과 decorator 조립은 여기서만 하고 도메인·유스케이스 코드에 Spring 조건문을 넣지 않는다.
- 외부 연동은 port/interface + adapter, 교체 가능한 정책은 strategy, 횡단 관심사(관측·캐시·재시도·감사)는 decorator로 분리한다.
- 객체 생성을 factory로 숨길 때도 반환 타입은 interface로 유지한다. 상속보다 조합을 우선한다.
- 클래스와 함수는 변경 이유가 하나가 되도록 작게 유지한다. 여러 유스케이스를 한 서비스에 계속 붙이는 god service를 만들지 않는다.
- 도메인 의미가 있는 문자열·숫자는 value object와 enum으로 표현하고 `Map`, `Any`, boolean flag 조합을 공개 경계에 사용하지 않는다.
- I/O, 시간, 난수, 외부 SDK, repository는 테스트 대역으로 교체할 수 있어야 한다.
- 외부 호출은 timeout을 필수로 두고 재시도 책임은 한 계층만 가진다. 작업 큐와 SDK가 동시에 재시도하지 않는다.
- 중복 요청과 worker 재실행을 전제로 idempotency를 설계하고, 장시간 외부 호출을 DB transaction 안에서 실행하지 않는다.
- 경계 입력은 fail-fast로 검증하되 사용자 오류와 시스템 오류를 타입으로 구분한다. 예외를 정상 분기 제어에 사용하지 않는다.

## 상수와 구성 관리

- 모델 ID, provider 선택, 토큰 단가, timeout, retry, batch 크기, feature flag처럼 운영 중 바뀔 수 있는 값은 코드에 박지 않고 타입이 있는 `@ConfigurationProperties`로 받는다.
- 운영자가 런타임에 변경하거나 tenant별로 달라야 하고 변경 이력·감사가 필요한 정책은 DB로 관리한다. MVP에서 배포 단위 변경이면 구성 파일/환경변수로 시작한다.
- 비밀값은 환경변수 또는 secret manager로만 주입한다. 구성 파일에는 값이 아니라 참조만 둔다.
- API 경로, wire protocol 버전, 계약상 고정 enum처럼 코드와 함께 바뀌어야 하는 불변식만 코드 상수로 허용하고 테스트로 고정한다.
- 숫자·문자열 literal을 추가할 때는 불변식, 구성값, 데이터 중 어디에 속하는지 먼저 분류한다. 출처 없는 magic number를 두지 않는다.

## 기술 스택

- Backend: Kotlin, Spring Boot, Gradle 멀티모듈
- Database: PostgreSQL + pgvector, Flyway
- 비동기 처리: PostgreSQL lease 기반 작업 큐
- Frontend: React, TypeScript, Vite
- LLM: `core`의 `LlmProvider` 인터페이스와 `infrastructure` 어댑터

## LLM provider와 관측

`LlmProvider`가 아래 예시의 `TextTransformer` 역할을 이미 담당하므로 같은 인터페이스를 새로 만들지 않는다. 서비스는 구체 벤더를 알지 못하고, 설정이 strategy를 선택하며 metrics decorator가 선택된 provider를 감싼다.

```text
[Conversion Service] -> [MetricsLlmProviderDecorator] -> [LlmProvider]
                                                        ├─ [OpenAiProvider]
                                                        └─ [AnthropicProvider]
```

- OpenAI와 Anthropic 요청·응답의 차이는 각 adapter 안에서 공통 `LlmCompletion`으로 정규화한다.
- 공통 결과에는 본문, provider, 실제 응답 model, 입력·출력 토큰, 종료 사유, 지연 시간, 설정 단가 기반 예상 비용을 포함한다.
- 비용은 `Double`이 아니라 `BigDecimal`로 계산한다. 모델 가격은 자주 바뀌므로 코드 상수가 아니라 구성값이며, 미설정은 0달러가 아니라 `null`이다.
- decorator는 성공과 실패 모두 provider·지연·outcome을 관측한다. 성공 시에만 model·token·예상 비용을 기록한다.
- 관측 로그와 메트릭에 prompt, 변환 본문, 응답 본문, API 키, 예외 메시지를 넣지 않는다.
- OpenAI Responses API는 `store=false`를 명시한다. Anthropic Messages API는 `anthropic-version`을 고정하고 두 adapter 모두 SDK/HTTP 계층의 자동 재시도를 사용하지 않는다.
- 새 provider는 adapter contract test와 설정 선택 테스트를 먼저 추가한 뒤 구현한다. 실제 외부 API를 단위 테스트에서 호출하지 않는다.

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
docker compose -f compose.yml config
docker compose -f compose.yml -f compose.ci.yml --profile ci config
```

외부 서비스, 실제 LLM 호출, 배포, push는 사용자 승인 없이 수행하지 않는다.

## 완료 보고

완료 보고에는 구현한 MVP 범위, 변경한 주요 파일, 실행한 검증과 결과, 남은 미구현 항목을 포함한다. 미구현 항목을 이번 작업의 완료처럼 표현하지 않는다.
