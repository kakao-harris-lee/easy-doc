# Kotlin 미완성 상태 스냅샷 (제거 작업 착수 전 기준선)

- 기록 시점: 2026-08-24
- 기준 커밋: `630634240b0b28247ec89a1b63bb18f18f3601b6` (`feat/kotlin-migration-harness`)
- 보존 태그: `pre-python-removal-20260824`
- 목적: `docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md` 실행 중 "Kotlin 비즈니스 기능은 조사 시점보다 추가되거나 임의로 보완되지 않았다"를 사후에 대조하기 위한 기준선. 이 문서는 제거 작업으로 갱신하지 않는다.

## 1. 현재 통과/실패하는 검사 (실측)

| 검사 | 명령 | 결과 | 로그 |
|---|---|---|---|
| Kotlin 빌드·린트·테스트 | `cd backend-kotlin && ./gradlew clean check --no-daemon` | **BUILD SUCCESSFUL** (80 tasks: 20 executed, 60 from cache) | `00_baseline_gradle-check.log` |
| Frontend 타입·린트·포맷 | `cd frontend && npm run check` | **성공** (tsc, eslint, prettier 전부 통과) | `00_baseline_frontend-check.log` |

기존 실패는 없다 — 두 검사 모두 기준선 시점에 전부 통과 상태다. 따라서 "기존 실패는 수정 대상으로 삼지 않는다"는 원칙은 적용할 대상이 없다(=제거 후 새 실패가 생기면 전부 이 작업이 만든 것).

## 2. 구현된 경로 (실측 — `find`/`rg` 결과)

### API 컨트롤러 (`backend-kotlin/api/src/main/kotlin/kr/easydoc/api/`)

| 컨트롤러 | 엔드포인트 |
|---|---|
| `AuthController` | `POST /auth/signup`, `POST /auth/login`, `GET /auth/me` |
| `WorkspaceController` | `GET /workspaces`, `POST /workspaces`, `PATCH /workspaces/{workspace_id}`, `DELETE /workspaces/{workspace_id}` |
| `DocumentController` | `POST /documents` (JSON), `POST /documents` (multipart), `GET /documents`, `DELETE /documents/{document_id}` |
| `ConversionController` | `GET /conversions/{conversion_id}`, `PUT /conversions/{conversion_id}` |
| `HealthController` | `GET /health` |
| `ContractErrorController` | 계약 오류 매핑(전역) |

### 도메인·인프라

- `core/`: 마스킹(`privacy/`), LLM 추상화(`llm/LlmProvider`·`LlmPrompt`·`LlmCompletion`), 텍스트 정규화·스타일 규칙·프롬프트·후처리(parity 대상 6도메인 — 아래 §3 참고)
- `infrastructure/`: `AnthropicProvider`(LLM 어댑터), `JdbcConversionQueue`(PostgreSQL lease 큐 — 큐 자료구조만, 아래 §3 worker 참고), 저장 암호화(AES-GCM 계열, `EncryptionKeyEnv`·`KeyCheckValue`), Argon2/JWT
- `worker/`: **골격뿐** — `WorkerApplication`(Spring Boot 진입점) + `WorkerStartupTest`. 실제 작업 리스 획득·처리 루프·LLM 호출·결과 반영 로직 없음

## 3. 미구현 경로와 기능 (실측 — 계약 대비 공백)

| 항목 | 상태 | 근거 |
|---|---|---|
| `POST/GET /conversions/{conversion_id}/export` (3형식 내보내기) | **미구현** | 컨트롤러 없음. `AuthenticatedEndpoints.kt:10` 주석이 "PUT과 /export는 아직 없다"고 명시 |
| Worker 작업 처리(리스 획득 → LLM 호출 → 결과 반영) | **미구현** | `worker/` 모듈은 Spring Boot 기동 골격만 있고 처리 로직 없음 |
| 보존·자동 삭제 정책(30일) | **미구현** | 스케줄러·배치 코드 없음(grep 결과 없음) |
| 골든셋 품질 평가·judge 채점 | **미구현(Kotlin 쪽)** | 이 기능은 Python `app/easyread/{goldenset,judge}.py`에만 존재했고, 이번 제거 대상. Kotlin 대체물 없음 |
| E2E(로그인→업로드→변환→검수→다운로드→삭제 전체) | **부분** | `.github/workflows/ci.yml`의 `e2e` 잡이 Playwright 12건으로 로그인·워크스페이스 흐름을 검증하나, 문서 업로드~다운로드까지의 전체 흐름은 export 미구현으로 완주 불가 |

## 4. parity 도메인 선언 상태 (제거 직전 — 참고용, 하네스 자체는 제거된다)

`backend-kotlin/parity-domains.txt` 선언 7개: `masking`, `repair-adoption`, `text`, `style`, `style-tables`, `prompts`, `postprocess`, `export`. (`export`는 텍스트 파일명 정제 로직의 parity이며 위 §3의 "내보내기 API 엔드포인트"와는 다른 대상이다 — 파일명 규칙은 `core`에 포팅돼 있고 파일 생성·다운로드 API가 없다는 뜻이다.)

## 5. 이 스냅샷의 용도

- 제거 작업 완료 후 `git diff` 로 `backend-kotlin/**` 변경 목록을 뽑아, 위 §2(구현됨)·§3(미구현)의 경계가 그대로인지 대조한다.
- §3의 미구현 항목은 이 제거 작업이 채우지 않는다 — 별도 Kotlin 재개발 계획의 backlog다.
