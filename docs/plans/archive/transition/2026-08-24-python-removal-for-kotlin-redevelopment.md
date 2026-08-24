# Python 제거 및 Kotlin 재개발 전환 계획

- 작성일: 2026-08-24
- 기준 브랜치: `feat/kotlin-migration-harness`
- 조사 기준 커밋: `630634240b0b28247ec89a1b63bb18f18f3601b6`
- 성격: 제거 작업 실행 계획

## 1. 결정

이 프로젝트의 목표는 Python 구현을 Kotlin으로 마이그레이션하여 동작 동일성을 증명하는 것이 아니다. 기존 제품 요구사항을 Kotlin/Spring Boot 기반으로 재개발하는 것이다.

따라서 Python 애플리케이션, Python 실행 환경, Python-Kotlin 비교 하네스, Python 결과를 기준으로 삼는 테스트와 문서를 제거한다. 현재 Kotlin 구현은 기능을 추가하거나 보완하지 않고 조사 시점의 불완전한 상태로 유지한다.

이 계획의 완료는 **Python 제거 완료**를 뜻할 뿐, **Kotlin 제품 완성**을 뜻하지 않는다.

## 2. 작업 원칙

1. 제거와 재개발을 분리한다.
   - 이번 작업에서는 Python과 마이그레이션 하네스를 제거한다.
   - 누락 API, 워커 처리, 내보내기, LLM 연동, 보존 정책, 품질 평가, E2E 등 Kotlin 기능을 구현하지 않는다.
2. Python 코드를 Kotlin으로 번역하지 않는다.
   - Python 테스트도 일괄적으로 Kotlin 테스트로 옮기지 않는다.
   - 요구사항이나 독립 계약에 근거한 데이터와 검증만 중립적인 위치 또는 기존 Kotlin 테스트 자원에 보존한다.
3. Kotlin의 현재 불완전성을 숨기지 않는다.
   - 진행표의 미완료 항목을 완료로 바꾸지 않는다.
   - 제거 작업 때문에 실패하는 기존 Kotlin 기능을 임의로 고치지 않는다.
   - Kotlin 비즈니스 로직 변경이 필요해지면 작업을 중단하고 별도 재개발 과제로 분리한다.
4. 사용자 작업과 복구 가능성을 보존한다.
   - 작업 시작 시 커밋, 브랜치, 상태를 기록하고 로컬 보존 태그 또는 브랜치를 만든다.
   - 추적되지 않은 파일은 소유권과 용도가 확인되기 전까지 수정하거나 삭제하지 않는다.
   - `git reset --hard`, 광범위한 `git clean`, 무차별 삭제를 사용하지 않는다.
5. 현재 저장소가 사실의 기준이다.
   - 기존 진행표와 리뷰 문서는 일부 최신 구현을 반영하지 못할 수 있다.
   - 제거 직전의 HEAD와 실제 빌드·테스트 결과를 기준선으로 기록한다.

## 3. 조사 시점 범위

### 3.1 Python 자산

추적 중인 Python 파일은 총 145개다.

| 위치 | 파일 수 | 처리 방향 |
|---|---:|---|
| `.claude/` | 6 | Python 및 parity 전용 에이전트·스킬·스크립트 제거 |
| `app/` | 53 | 전체 제거 |
| `migrations/` | 7 | 전체 제거 |
| `scripts/` | 5 | Python 운영·검증 스크립트 제거 |
| `tests/` | 74 | 독립 요구 데이터 선별 보존 후 전체 제거 |

함께 제거할 Python 도구 및 실행 자산:

- `pyproject.toml`
- `uv.lock`
- `.python-version`
- `alembic.ini`
- 루트 `Dockerfile`
- GitHub Actions의 Python 품질·테스트 작업
- Compose의 Python API, Python worker, Alembic migration, Redis 의존 구성

### 3.2 마이그레이션·parity 자산

- `parity/actual/`, `parity/reference/`, `_harness-selfcheck` 등 실행 산출물과 Python 기준 결과
- `.github/parity-*`
- `.claude/skills/python-kotlin-parity/` 및 관련 에이전트·검증 스크립트
- `backend-kotlin/parity-domains.txt`
- Gradle의 parity manifest/check/task와 대응 Kotlin 테스트 클래스
- Python 출력에만 근거한 prompt/style snapshot
- `docs/migration/**`의 실행 기록, 증빙, 리뷰 묶음
- 기존 Python-Kotlin 마이그레이션 계획 `docs/plans/2026-08-11-kotlin-react-migration.md`

`docs/migration/**`는 조사 시점에 203개 파일, 약 83,258줄이며 이 중 리뷰 문서가 119개, 약 48,484줄이다. 현재 의사결정과 독립 요구사항을 이 문서 및 유지 문서에 반영한 뒤 제거한다. Git 이력과 작업 전 보존 참조가 과거 기록의 복구 수단이 된다.

### 3.3 Kotlin 안의 Python 호환 자산

다음은 Kotlin 기능 자체가 아니라 Python 마이그레이션 또는 호환을 위한 자산이므로 제거 또는 중립화 대상이다.

- `FlywayBaselineGuard.kt`, `SchemaFingerprint.kt`
- `db/baseline/python-schema-fingerprint.txt`
- `V1__python_schema_baseline.sql` 및 Python 스키마 승계를 전제로 한 후속 migration
- `FlywayBaselineGuardTest.kt`, `PythonSchemaBaselineTest.kt`
- Python 이름 또는 Python 산출물에 결합된 테스트 resource와 snapshot
- Kotlin 빌드의 `uv`, Python parity, Python fixture 생성 의존성

단, 이 단계에서도 Kotlin 도메인 로직, API 기능, 저장 동작을 새로 구현하지 않는다.

## 4. 반드시 보존할 항목

### 4.1 제품과 재개발 기반

- `frontend/`
- `backend-kotlin/`
- `contracts/`
- 현재 제품 요구사항, API 계약, 운영·보안·품질 문서
- Kotlin 구현이 이미 직접 사용하는 테스트 fixture
- 요구사항에서 독립적으로 도출할 수 있는 규칙, 입력 샘플, 검증 데이터

목표 구조는 다음과 같다.

```text
frontend/
backend-kotlin/
contracts/
data/                 # 구현 언어에 독립적인 선별 데이터만 존재
docs/                 # 현재 계획과 제품 문서만 존재
```

### 4.2 선별 후 이동할 데이터

Python 테스트 디렉터리를 삭제하기 전에 다음 데이터를 조사하고 중립적인 `data/**` 또는 기존 Kotlin test resource로 이동한다.

- golden JSON 56개
- `required_facts` 253개
- 추적 중인 golden baseline
- easy-read 변환 예시 6개
- 독립적인 style rule 데이터 13개 키
- DOCX/PDF/HWPX 샘플과 위조·과대 ZIP 보안 fixture

이동 조건:

1. 데이터의 근거가 제품 요구사항, 계약 또는 원본 문서여야 한다.
2. Python 실행 결과만을 정답으로 삼은 값은 보존하지 않는다.
3. 이동 전후 파일 수, 레코드 수, SHA-256을 manifest로 비교한다.
4. 이미 Kotlin test fixture에 같은 데이터가 있으면 중복을 만들지 않고 hash로 동일성을 확인한다.
5. ignore된 `tests/golden/041-2026년_국민기초생활보장_사업안내.jso`는 추적 중인 `tests/golden/documents/041-2025년-발달장애인지원-사업안내.json`과 내용·출처를 비교해 중복 여부와 소유권을 확인하기 전에는 삭제하지 않는다.
6. Git에서 추적하지 않는 대용량 원본 문서(`docs/golden`, 조사 시점 약 76 MB)는 외부 보존 위치와 hash manifest를 확정하기 전까지 손대지 않는다.

### 4.3 현재 추적되지 않은 사용자 파일

조사 시점에 확인한 다음 항목은 이 계획의 제거 대상이 아니다.

- `.claude/settings.local.json`
- `.playwright-mcp/`
- `docs/` 아래의 추적되지 않은 사용자 DOC 파일 2개

실행 당일 상태를 다시 확인하고, 추가로 발견되는 추적되지 않은 파일도 동일하게 보존한다.

## 5. 실행 단계

각 단계는 별도 커밋으로 만들고, 단계별 검증이 통과한 뒤 다음 단계로 진행한다. push와 merge는 이 계획의 권한 범위에 포함하지 않는다.

### 단계 0. 기준선 및 복구 지점 고정

1. 다음을 기록한다.
   - 현재 HEAD SHA와 브랜치
   - `git status --short`
   - 추적/비추적 파일 목록
   - Kotlin, frontend, Compose의 현재 검증 결과
2. 현재 커밋을 가리키는 로컬 보존 태그 또는 보존 브랜치를 만든다.
3. Kotlin 미완성 상태를 별도 snapshot 표로 기록한다.
   - 구현된 경로
   - 미구현 경로와 기능
   - 현재 통과/실패하는 검사
4. 기존 실패는 제거 작업의 수정 대상으로 삼지 않고 기준선에 명시한다.

산출물: 작업 시작 manifest, Kotlin 미완성 snapshot, 복구 참조.

### 단계 1. 제거 범위와 문서 기준 전환

1. 이 문서를 활성 계획으로 지정한다.
2. `docs/master-plan.md`에서 Python/FastAPI 이식 및 `uv`/`mypy`/`ruff`/`pytest` 중심 문구를 Kotlin 재개발 및 Kotlin/frontend 검증 기준으로 바꾼다.
3. `CLAUDE.md`와 관련 프로젝트 지침에서 Python-Kotlin parity를 필수 워크플로로 만드는 규칙을 제거한다.
4. 이후 단계에서 삭제할 문서 중 현재 요구사항 또는 결정의 유일한 출처가 있는지 확인하고, 필요한 내용만 유지 문서로 옮긴다.

제약: 문서 전환 중 기능 완료 상태나 제품 계약의 의미를 바꾸지 않는다.

### 단계 2. 언어 독립 데이터 대피

1. 4.2의 후보를 출처별로 분류한다.
   - 제품 요구사항 기반: 보존
   - 원본 입력 샘플: 보존
   - Python 출력 기반 기대값: 삭제
   - 출처 불명: 작업 중단 후 판단 요청
2. 보존 데이터를 `data/**` 또는 Kotlin test resource로 이동한다.
3. 데이터 manifest에 이전 경로, 새 경로, 개수, SHA-256, 근거 문서를 기록한다.
4. Kotlin이 이미 사용하는 fixture의 참조 경로만 새 위치로 변경한다.

제약: 경로 변경 외 Kotlin 테스트의 의미를 강화하거나 약화하지 않는다.

### 단계 3. Kotlin 빌드와 테스트의 Python 의존 제거

1. Gradle에서 parity manifest/check/task와 Python/`uv` 호출을 제거한다.
2. Python 산출물과만 비교하는 Kotlin parity 테스트를 삭제한다.
3. 요구사항·계약에 직접 근거한 기존 Kotlin assertion은 유지하되 이름과 fixture 경로만 중립화한다.
4. Python 이름의 prompt/style snapshot은 다음 기준으로 처리한다.
   - 독립 요구 데이터면 중립 이름으로 이동
   - Python 출력의 고정본이면 삭제
5. GitHub Actions Kotlin job에서 Python 설치와 parity 단계를 제거한다.

허용되는 Kotlin 변경:

- build script 및 task 삭제
- Python 호환 전용 클래스·테스트 삭제
- fixture/resource 경로와 이름 변경
- 삭제된 Python 도구를 가리키는 설정·주석 제거

허용되지 않는 Kotlin 변경:

- controller/service/domain 기능 추가
- API 동작 수정
- 누락 기능 구현
- 기존 비즈니스 버그 수정
- 제거와 무관한 리팩터링

### 단계 4. Python 애플리케이션과 도구 제거

다음을 명시적인 경로 목록으로 삭제한다.

1. `app/`
2. 데이터 대피가 끝난 `tests/`
3. `migrations/`
4. Python 전용 `scripts/`
5. `pyproject.toml`, `uv.lock`, `.python-version`, `alembic.ini`, 루트 `Dockerfile`
6. `.claude/`의 Python/parity 전용 에이전트·스킬·스크립트
7. GitHub Actions의 Python quality/test job
8. Compose의 Python API/worker/migrate 서비스와 Python만을 위한 Redis 의존 구성

Compose의 기본 실행 대상은 Kotlin API와 Kotlin worker로 바꾼다. 다만 worker가 현재 skeleton이라면 그대로 노출하며, 작업 처리 기능을 추가하지 않는다.

### 단계 5. Kotlin DB의 Python 승계 결합 제거

실행 직전에 개발·운영·공유 환경에 승계해야 할 실제 데이터베이스가 없는지 다시 확인한다.

실제 승계 DB가 없을 때만 다음을 수행한다.

1. Python schema fingerprint와 baseline guard를 제거한다.
2. Python 스키마 승계를 전제로 한 Flyway migration을 현재 Kotlin 스키마의 깨끗한 `V1__initial_schema.sql`로 정리한다.
3. 현재 schema의 테이블, 컬럼, 인덱스, 제약조건만 보존한다.
4. 새 테이블, 새 컬럼, 누락 기능용 schema를 추가하지 않는다.
5. 빈 PostgreSQL에 migration을 적용해 현재 Kotlin 애플리케이션이 시작 가능한지만 확인한다.

실제 승계 DB가 하나라도 발견되면 이 단계를 중단한다. 기존 DB의 파괴적 재초기화나 자동 변환은 이 계획 범위가 아니다.

### 단계 6. 마이그레이션 하네스와 과거 증빙 제거

1. `parity/**`, `.github/parity-*`, `backend-kotlin/parity-domains.txt`를 제거한다.
2. 현재 의사결정과 독립 요구사항을 유지 문서에 반영했는지 확인한다.
3. `docs/migration/**`의 parity 실행 결과, 임시 workspace, 리뷰 증빙을 제거한다.
4. 기존 Python-Kotlin 마이그레이션 계획을 제거한다.
5. README, `.gitignore`, `.dockerignore`, `.env.example`, frontend 주석, contract provenance에서 삭제된 Python 경로와 명령을 정리한다.
6. API contract의 의미는 바꾸지 않는다. Python 파일을 출처로 적은 메타데이터만 중립화한다.

### 단계 7. 최종 검증과 인계

#### 제거 검증

```bash
rg --files -g '*.py' -g '*.pyi'
git ls-files pyproject.toml uv.lock alembic.ini .python-version Dockerfile
rg -n 'uv run|pytest|alembic|FastAPI|ARQ|REDIS_URL|python-kotlin-parity|parityHarness|parityManifestCheck' \
  --glob '!docs/plans/2026-08-24-python-removal-for-kotlin-redevelopment.md'
```

세 명령 모두 출력이 없어야 한다. 과거 사실을 설명하는 현재 문서에 Python 용어를 남겨야 한다면, 예외 경로와 이유를 최종 manifest에 명시한다.

#### Kotlin 검증

```bash
cd backend-kotlin
./gradlew clean check --no-daemon
```

기준선에서 이미 실패한 항목은 동일 실패인지 비교한다. 제거 때문에 새 실패가 생겼다면 제거 작업만 수정한다. 제품 기능을 새로 구현해서 통과시키지 않는다.

#### Frontend 검증

```bash
cd frontend
npm run check
npm run test -- --run
npm run build
```

#### 실행 구성 검증

```bash
docker compose config
```

빈 PostgreSQL에서 Flyway와 현재 Kotlin API 시작을 확인한다. 미구현 E2E 흐름이나 worker 업무 처리는 완료 조건으로 삼지 않는다.

#### 데이터 검증

- golden JSON 56개 보존 확인
- `required_facts` 253개 보존 확인
- 추적 baseline의 보존 또는 명시적 폐기 근거 확인
- easy-read 예시 6개 보존 확인
- 보안 문서 fixture의 이동 전후 hash 확인
- 중복 fixture가 생성되지 않았는지 확인

#### Kotlin 불완전성 보존 검증

1. 작업 시작 SHA와 종료 SHA 사이의 Kotlin 소스 변경 목록을 생성한다.
2. 변경 파일마다 다음 중 하나의 사유만 허용한다.
   - Python 호환 전용 코드 삭제
   - build/test wiring 제거
   - resource 경로 또는 중립 명칭 변경
3. controller/service/domain의 기능적 변경이 있으면 완료 처리하지 않는다.
4. 시작 시 작성한 미완성 snapshot의 항목은 완료로 전환되지 않아야 한다.

## 6. 중단 조건

다음 중 하나라도 발생하면 해당 단계의 변경을 확대하지 말고 중단한다.

- 소유권이 불분명한 비추적 파일이나 사용자 파일을 발견함
- 보존 데이터의 개수 또는 hash가 일치하지 않음
- fixture의 독립 요구 근거와 Python 출력 근거를 구분할 수 없음
- 승계가 필요한 실제 또는 공유 데이터베이스를 발견함
- Python 제거를 위해 Kotlin 비즈니스 로직 변경이 필요함
- Kotlin의 기준선 검증 자체가 실패하며 기존 실패인지 판단할 수 없음
- 현재 계약의 유일한 근거가 삭제 대상 문서에만 존재함

중단 시에는 대상, 발견 사실, 필요한 결정을 기록하고 별도 승인을 받는다.

## 7. 권장 커밋 경계

1. `docs: define python removal and kotlin redevelopment scope`
2. `data: preserve implementation-neutral fixtures`
3. `build: detach kotlin checks from python parity`
4. `chore: remove python application and runtime`
5. `db: remove python schema compatibility baseline`
6. `docs: retire migration harness and historical evidence`

각 커밋은 독립적으로 검토할 수 있어야 하며, 데이터 대피와 원본 삭제를 같은 커밋에 섞지 않는다.

## 8. 완료 판정

다음 조건을 모두 만족할 때 제거 작업을 완료로 판정한다.

- 저장소에 실행 가능한 Python 코드와 Python 도구 체인이 없다.
- Kotlin 빌드·테스트·CI·Compose가 Python이나 parity harness에 의존하지 않는다.
- 독립 요구 데이터와 원본 fixture가 manifest로 보존되어 있다.
- Kotlin 비즈니스 기능은 조사 시점보다 추가되거나 임의로 보완되지 않았다.
- Kotlin 미완성 항목은 별도 재개발 backlog로 그대로 남아 있다.
- 문서가 더 이상 Python 마이그레이션을 프로젝트의 활성 목표로 지시하지 않는다.
- 사용자 소유 파일과 작업 전 복구 지점이 보존되어 있다.

이후 Kotlin 기능 재개발은 이 제거 작업과 분리된 계획과 커밋에서 시작한다.
