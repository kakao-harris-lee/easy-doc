# 게이트 15 · 1단계 codex 독립 리뷰 — `15_phase3-preflight`

> **codex 리뷰 수행 완료 (1차 회차).** 최종 판정을 **수령했다** — 게이트 14와 달리 이번에는
> 구조화된 `findings` 배열이 채워진 완결 산출물이 나왔다. 다만 **1차 시도가 행에 걸려 취소됐고
> 2차 시도(규약상 1회 재시도)가 성공했다**. 두 시도의 출력을 모두 §3에 무편집으로 싣는다.
>
> **codex 사용량 한도는 이 회차에 소진 상태가 아니었다.** 리더 호출문이 전제한 "한도 소진(복구
> 2026-08-20)"은 **게이트 14 시점의 사실**이며, 이번 실행에서는 한도 오류가 발생하지 않았다.
> 따라서 "codex 미경유" 대체 산출물 경로는 **적용하지 않았다** — 실제 codex 출력이 있으므로
> Claude 자작 리뷰로 대체하면 이 게이트의 독립성이 사라진다.

> **회차**: 1차 (게이트 15 · Phase 3 착수 전 준비 배치).
> **어간 출처**: 리더가 1단계 호출에서 지정한 `15_phase3-preflight` 을 그대로 썼다. 임의 슬러그를 만들지 않았다.
> **이 문서의 규약**: §3 은 **codex 원문 무편집**이다. §4 「정리(가공)」만 Claude 가 쓴 구획이며,
> 거기서도 옳고 그름을 판정하지 않는다. 원문 구획에 Claude 가 쓴 줄은 없다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 대상 범위 (리더 지정) | `5797d87..614afed` |
| 실제 리뷰 대상 | branch diff vs `5797d87` (merge-base `5797d875e981`, 변경 파일 **12개**) |
| **범위 대조** | **드리프트 없음** — 실행 시각 `HEAD` = `614afed` 로 리더 지정 범위 끝과 **정확히 일치**. 게이트 14에서 있었던 1파일 차집합이 이번에는 0이다 |
| 모드 | `adversarial-review` |
| 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (최신 버전 자동 선택) |
| codex CLI | `codex-cli 0.147.0` |

### 1.1 시도 이력 (2회)

| 회 | job id | codex session id | 시작 (UTC) | 종료 (UTC) | 결과 |
|---|---|---|---|---|---|
| 1차 | `review-msu1unr8-ryts0n` | `01a0044d-f268-7d63-9e51-64580c5c1eb9` | 2026-08-15T07:23:30Z | 2026-08-15T12:09:24Z (취소) | **행(hang)** — `verifying` 단계에서 멈춤. 마지막 로그 활동 `07:31:54Z` 이후 **4시간 37분 무활동**, elapsed 4h45m 시점에 취소. assistant 메시지 3건, `findings` **전부 빈 배열** |
| 2차 | `review-msuc2j9t-k16qu3` | `01a00553-d810-74c0-8ce0-960133beac2d` | 2026-08-15T12:09:34Z | 2026-08-15T12:19:34Z | **성공** (`EXIT=0`, Duration 10m 0s). assistant 메시지 5건, 최종 `verdict=needs-attention`, `findings` **8건** |

1차의 행은 **한도 오류가 아니다** — 한도 오류는 게이트 14에서처럼 수 초 내 명시적 오류 메시지로
끝난다. 이번 1차는 도구 호출을 계속 수행하다 최종 종합 단계에서 멈췄고, 오류 메시지를 남기지
않았다. 2차는 같은 프롬프트·같은 base 로 재호출했다.

**실행 명령 전문 (양 회차 동일)**

```bash
FOCUS="$(cat <focus 파일>)" && .claude/skills/codex-review/scripts/codex-review.sh \
    adversarial --base 5797d87 --focus "$FOCUS"
```

**스크립트가 stderr 에 찍은 대상 판정 두 줄 (2차, 원문)**

```
codex-review: 리뷰 대상 = branch diff vs 5797d87
codex-review: 대상 판정 = non-empty (merge-base=5797d875e981, 변경 파일 12개 (branch 모드는 커밋된 변경만 센다))
```

### 1.2 제공한 맥락

focus text 안에 다음을 실었다(저장소 파일은 codex 가 직접 읽었다).

- **배경**: Python/FastAPI → Kotlin/Spring Boot 전환이며, 이번 diff 는 제품 기능 코드보다
  **판정 장치**(게이트·가드·회귀 테스트·계약 문서)의 비중이 크다는 것. Python 은 정답이 아니라는 단서(2026-08-12 재개발 전환)
- **채점 기준 8개**: 선언 범위 = 실제 도달 / 자기 채점 금지 / 대리 지표 금지 / 모듈 의존 방향(§3.2) /
  Flyway baseline 규칙(§4.2-4·§4.2-7) / `{"detail": ...}`·snake_case·문자열 대 배열 `detail` /
  정규화 후 판정 / 로그 개인정보 금지
- **대상 파일 목록**: Kotlin·빌드 3개, 하네스·테스트 3개(+함께 읽을 CI·비교기·하한 파일 5개), 계약 2개
- **참고 구현 경로**: `app/easyread/{prompts,style_rules,postprocess}.py`, `app/services/auth.py`,
  `app/api/schemas.py`, `app/services/workspaces.py`, `migrations/versions/0006_workspaces.py`,
  `frontend/src/api/client.ts` — **정답이 아니라 참고**라는 단서를 붙였다
- **질문 6축**: 리더가 지정한 ①~⑤ + `codex-review` 스킬이 요구하는 가로지르는 축 ⑥(선언 범위 대 실제 도달)

### 1.3 프롬프트에 싣지 않은 것

- Claude·다른 리뷰어가 이미 내린 결론, 의심 지점, 이전 회차의 지적 판정
- `migration-reviewer` 산출물 — 이 회차에서는 **보지도 참조하지도 않았다**(리더 지시)
- 사용자 문서 본문·실제 암호문·키·개인정보. 리더 focus 의 ①~⑤ 는 "무엇이 문제다"가 아니라
  **어느 축을 파고들라**는 지정이므로 그대로 축으로 옮겼고, Claude 의 결론은 넣지 않았다

---

## 2. 전달한 프롬프트 전문 (focus text)

양 회차에 동일하게 전달했다.

````text

## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 교체하는 전환 중이다. 제품 동작과
개인정보 보호 정책은 보존해야 하지만, **Python 출력은 정답이 아니다** — Python 은 회귀가 잦아
폐기 대상이고(2026-08-12 재개발 전환), 판정 기준은 "Python 과 같은 값이 나오는가"가 아니라
"요구사항·계약이 요구하는 성질을 만족하는가"다. Python 경로는 참고 구현으로만 준다.

이번 diff 는 Phase 3(인증·작업 공간·JDBC repository) 착수 **직전의 준비 배치**다. 제품 기능
코드보다 **판정 장치**(게이트·가드·회귀 테스트·계약 문서)의 비중이 크다. 그래서 이 리뷰에서
중요한 것은 "동작하는가"보다 **"이 장치가 실제로 무엇을 재고 있으며, 무엇을 재지 못하는가"**다.

## 지켜야 하는 조건 (채점 기준)

아래는 이 저장소가 이미 확정한 요구다. 위반을 찾는 기준으로 쓴다.

1. **선언한 범위 = 실제 도달 범위.** "전역"·"모든"·"항상"이라고 선언한 장치는 그 범위에 실제로
   닿아야 한다. 닿지 않는 경로가 있으면 그 장치는 초록불을 내면서 아무것도 재지 않는다.
   이 저장소는 같은 형태의 실패를 7건 겪었다(필터 미도달, 게이트 CI 도달 0, 검사 도구가
   점 디렉터리를 건너뜀 등).
2. **자기 채점 금지.** 검사의 기준이 검사 대상 자신에게서 나오면 안 된다. 생성기가 만든 값을
   그 생성기의 선언과 대조하는 구조, 구현을 복사한 기대값, 자기가 쓴 파일을 자기가 읽는 검증은
   전부 항상 통과한다.
3. **대리 지표 금지.** 종료 코드 0 을 "검토했다"로, 테스트 통과를 "그 경로가 실제로 돌았다"로,
   지적 0건을 "문제 없음"으로 바꿔 읽는 자리가 있으면 안 된다.
4. **모듈 의존 방향** (계획 §3.2): `core` 는 Spring·DB 의존이 없다. `api`·`worker` 는
   `infrastructure` 를 **런타임에만** 붙인다(`runtimeOnly`). 컴파일 시점에 보이면 api 소스가
   어댑터 타입을 직접 import 할 수 있고, 그러면 의존 방향이 사라진다.
5. **Flyway baseline 규칙** (계획 §4.2-4, §4.2-7): Flyway 이력이 없고 애플리케이션 테이블이
   이미 있을 때만 baseline version 1 을 기록하며, **스키마 지문이 기준선과 정확히 같을 때만**
   기록한다. 다르면 기동을 실패시킨다. `alembic_version` 은 **읽기만** 하고 쓰지 않는다.
   판정한 스키마와 baseline 을 찍는 스키마는 같아야 한다(TOCTOU 금지). 여러 인스턴스가
   동시에 뜨는 상황에서도 baseline 은 한 번만 찍혀야 한다.
   위반하면: 확인되지 않은 스키마 위에 마이그레이션이 쌓이고, 문제는 그 테이블을 처음 건드리는
   요청에서야 터진다.
6. **API 오류 계약**: 오류 본문은 `{"detail": ...}` 형태이고 Spring 기본 `ProblemDetail` 을
   노출하지 않는다. JSON 필드는 snake_case. 요청 **본문** 필드의 길이·형식·빈 값 위반은
   422 + `detail` **문자열**, 스키마 층(쿼리 파라미터 범위·필드 누락·타입 불일치)은
   422 + `detail` **배열**이다. 상태 코드가 같으므로 타입을 보지 않으면 갈린 것을 놓친다.
7. **정규화 후 판정**: 이번에 확정된 다섯 요청 필드 제약은 **정규화(제어문자 제거·트림·소문자화)
   이후의 길이**를 잰다. 원시 값에 거는 스키마 층 제약과는 경계가 다르다.
8. **개인정보**: 로그에는 문서 id·길이·상태만 남는다. 문서 본문·개인정보는 로그·예외 메시지·
   메트릭에 실리지 않는다.

## 대상

커밋 범위 `5797d87..614afed` (6 커밋). 변경 파일:

**Kotlin / 빌드**
- `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt`
  — advisory lock 결속, `verifyAlembicHead` 신설. 같은 디렉터리의 `SchemaFingerprint` 도 함께 읽어라.
- `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuardTest.kt`
- `backend-kotlin/build.gradle.kts` — `moduleBoundaryCheck` 태스크 신설(선언 종류 + compileClasspath 두 축).
  각 모듈의 `build.gradle.kts`(`api`·`worker`·`application`·`core`·`infrastructure`)도 함께 읽어라.

**하네스 / 테스트 (Python)**
- `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py` — Python 상수·프롬프트
  스냅샷 생성기. `--check`(재생성 diff 0)/`--write` 두 모드.
  대상 스냅샷: `backend-kotlin/core/src/test/resources/kr/easydoc/core/easyread/python-*-snapshot.json`
- `tests/test_parity_ci_gate.py` — 게이트 우회 회귀 6종.
- `tests/test_harness_scope_reach.py` — 진척 원장 표의 도달 검사.
- 함께 읽을 것: `.github/workflows/ci.yml`(parity 게이트 step),
  `.claude/skills/python-kotlin-parity/scripts/compare_parity.py`,
  `.github/parity-declared-floor.txt`, `.github/parity-canonical-floor.txt`,
  `backend-kotlin/parity-domains.txt`

**계약**
- `contracts/easy-doc-v1.yaml` — `x-request-field-constraints` 절 신설, 다섯 필드의 OpenAPI
  `maxLength`/`minLength` 제거.
- `docs/migration/_workspace/00_contract-keeper_test-plan.md` — contract test 계획(X-F1·X-F2 개정,
  X-F9·X-F10 신설, X-J2 확정).

**참고 구현(정답 아님 — 요구 충족 여부를 보는 데만 쓴다)**
- `app/easyread/prompts.py`, `app/easyread/style_rules.py`, `app/easyread/postprocess.py`
  (스냅샷 생성기가 호출하는 Python 원본)
- `app/services/auth.py`, `app/api/schemas.py`, `app/services/workspaces.py`
  (다섯 필드 제약의 실제 강제 지점)
- `migrations/versions/0006_workspaces.py` (기준선이 가정하는 Alembic head)
- `frontend/src/api/client.ts` (오류 본문을 읽는 쪽)

## 질문

다음 다섯 축에서 **구체적 반례**(입력·상태·명령·라인)를 찾아라. 일반론적 코드 품질 지적은 빼라.

**① advisory lock 의 잔여 위험.**
`FlywayBaselineGuard` 는 세션 advisory lock 하나로 TOCTOU 와 동시 기동을 함께 닫는다고 선언한다.
잠금을 잡은 연결이 반납·종료·재사용될 때, Flyway 가 자기 트랜잭션·자기 연결을 따로 열 때,
`block()` 안에서 예외가 던져질 때, 커넥션 풀이 그 연결을 회수하거나 유효성 검사로 리셋할 때
잠금이 실제로 어떻게 되는지 추적하라. 잠금이 조기에 풀리는 경로, 반대로 풀리지 않아 다음 기동이
영구 대기하는 경로, 그리고 **잠금이 실제로 보호하지 못하는 구간**(잠금 획득 이전에 이미 읽은 값,
잠금 밖에서 도는 Flyway 내부 동작)이 있는지 보라. 풀 크기가 1 인 경우도 따져라.
`verifyAlembicHead` 가 새로 도입한 판정축이 기존 지문 판정과 모순되거나 서로를 무력화하는
입력 조합이 있는지도 보라(다중 head, 빈 테이블, 이전 리비전, 문자열 정렬).

**② 모듈 경계 단언의 우회.**
`moduleBoundaryCheck` 는 ⓐ `:infrastructure` 를 선언한 configuration 이 허용 목록 안인가
ⓑ compileClasspath 에 없는가 — 두 축으로 의존 방향을 강제한다고 선언한다.
이 두 단언을 **모두 통과하면서** api·worker 소스가 infrastructure 타입을 컴파일 시점에 보는
경로가 있는지 찾아라. 검사 대상이 되지 않는 configuration·소스셋(testFixtures, 다른 컴파일
태스크, kapt/ksp, 플랫폼/BOM, 파일 의존, 복합 빌드), 허용 목록 자체가 넓어서 생기는 구멍,
`declarations` 를 configuration time 에 스냅샷하는 것과 실제 해석 시점의 차이,
그리고 이 태스크가 **어떤 명령에서 실제로 도는가**(`./gradlew build`·`check`·CI 잡)를 짚어라.
`core` 의 Spring 비의존은 이 태스크가 보는가, 아니면 여전히 클래스 존재 검사만인가.

**③ 스냅샷 생성기의 재현성.**
`dump_python_snapshots.py --check` 는 "재생성 diff 0"을 근거로 Kotlin 스냅샷이 Python 과
일치함을 주장한다. 이 주장이 성립하려면 생성이 완전 결정적이어야 한다.
`secrets.token_hex` 를 임시 교체해 문서 id 를 고정하는 방식이 **모든 비결정성을 덮는지** 확인하라 —
다른 난수원(`random`·`uuid`·`os.urandom`·`time`·`id()`), 다른 모듈이 `from secrets import token_hex`
로 이미 바인딩한 경우, 딕셔너리·집합 순서, 로케일·타임존, `PYTHONHASHSEED`, 부동소수 표현,
그리고 예외 발생 시 `finally` 가 원본을 되돌리기 전에 다른 코드가 오염된 상태를 보는 경로.
더 중요한 축: **이 생성기가 이전 스냅샷(`previous`)에서 입력을 읽어 출력을 다시 채운다.**
이 구조에서 diff 0 이 실제로 무엇을 증명하고 무엇을 증명하지 못하는지 판정하라 —
케이스가 통째로 빠지거나, 상수 목록에서 항목이 빠지거나, 스냅샷 파일이 손상됐을 때
`--check` 가 이를 잡는지 반례로 확인하라. 스냅샷을 실제로 소비하는 Kotlin 테스트가
그 값을 단언에 쓰는지, 읽기만 하는지도 보라.

**④ 게이트 우회 회귀 6종의 격리와 유효성.**
`tests/test_parity_ci_gate.py` 는 게이트 우회를 자동 회귀로 고정한다고 선언한다.
각 테스트가 (a) 실물 저장소 파일을 건드리지 않는지 — `tmp_path` 합성 트리·monkeypatch 로
격리됐다고 선언하지만 `_tree` 가 복사하는 목록, `subprocess` 가 상속하는 cwd·환경변수
(`UV_PROJECT_ENVIRONMENT` 가 저장소 `.venv` 를 가리킨다), 절대 경로를 쓰는 모듈 상수,
테스트 실패 시 남는 잔여 상태를 통해 실물이 오염되거나 실물 상태에 결과가 좌우되는 경로가
있는지, (b) **음성 대조가 실제로 성립하는지** — 각 테스트가 막는다고 주장하는 우회를
정말 재현하고 있는지, 아니면 다른 이유로 실패해 통과처럼 보이는지,
(c) `_step_script()` 가 `ci.yml` 에서 뽑아내는 블록이 CI 가 실제로 실행하는 것과 같은지
(앵커 `declared_count=`, 들여쓰기 계산, step 이 여러 개일 때) 를 보라.
"1종 대상 소멸"로 상시화되지 않은 항목이 있다면, 그 자리가 지금 무엇으로 덮이는지 짚어라.

**⑤ 계약 F3 변경의 정합성.**
다섯 요청 필드에서 OpenAPI `maxLength`/`minLength` 를 제거하고 `x-request-field-constraints`
로 옮긴 변경이 계약 문서 **자신의 다른 조항**과, 그리고 contract test 계획과 맞는지 보라.
`x-input-limits`·오류 본문 규칙·`ValidationFailed` 예시·엔드포인트 설명·`x-contrast-case` 로
남긴 `limit`/`offset` 사이에 남은 모순, 제거로 인해 **아무 층에서도 강제되지 않게 된** 제약,
Kotlin 구현자가 이 계약만 읽고 구현했을 때 배열/문자열 `detail` 을 잘못 고를 수 있는 자리,
그리고 X-F9·X-F10 이 실제로 그 갈림을 잡는 단언인지(그 단언만으로 `@Size` 구현이 통과할 수
있는지) 판정하라. `x-request-field-constraints` 는 표준 OpenAPI 키가 아니므로 **생성 도구가
무시한다** — 이 사실이 Phase 6(OpenAPI → React 타입 생성)에서 무엇을 잃게 하는지도 보라.

**⑥ 위 다섯 축을 가로지르는 필수 질문 — 선언 범위 대 실제 도달.**
이번 diff 는 게이트·가드·규칙을 **새로 세우거나 넓히는** 변경이다. 각 장치마다 답하라.

- 이 장치는 **어디서 도는가**? 로컬에서만인가, CI 잡에 배선돼 있는가, 아무 데서도 안 도는가?
  `.github/workflows/ci.yml` 에서 실제 실행 경로를 라인으로 짚어라. 도달 0 인 장치를 특히 의심하라.
- 이 장치를 **제거하면 정확히 무엇이 깨지는가**? 떼어도 아무 테스트가 깨지지 않는 장치를 지목하라.
- 이 장치가 대조하는 **기준이 자기 자신에게서 나오지 않는가**?
- 이 장치의 **범위가 근거보다 넓지 않은가**? 한 건을 막으려고 넣은 광범위한 무시·예외·억제가
  앞으로 같은 사고를 **보이지 않게** 만드는 자리가 있는가?

지적이 없는 축이 있으면 "없다"고 명시하라 — 없는 것을 채워 넣지 마라.
````

---

## 3. codex 원문 출력 (무편집)


> 이 절의 모든 내용은 codex 가 낸 것이다. Claude 가 쓴 줄은 없다.
> 절 제목과 회차 구분선만 이 문서의 구조이며, 그 안의 본문은 원문 그대로다.

### 3.1 1차 시도 — 부분 출력 (행에 걸려 취소됨)

`findings` 는 **세 건 모두 빈 배열**이다. 즉 1차는 구조화된 지적을 한 건도 확정 제출하지 않았다.
`summary` 문장을 지적 확정본으로 읽으면 안 된다.


**1차 assistant 메시지 1/3 — `2026-08-15T07:23:52.621Z`**

```json
{"verdict":"approve","summary":"리뷰 범위와 현재 브랜치 증거를 먼저 고정한 뒤, 요청한 다섯 축별로 실제 실행 경로와 반례를 추적하겠습니다.","findings":[],"next_steps":[]}
```

**1차 assistant 메시지 2/3 — `2026-08-15T07:24:09.392Z`**

```json
{"verdict":"approve","summary":"현재 기억에는 이 저장소의 parity·도달성 게이트에서 과거에 발생한 자기참조/부분 집합 false-green 패턴이 직접 연결되어 있습니다. 이를 현재 diff에 재검증하되, 과거 판정을 현재 사실로 간주하지 않겠습니다.","findings":[],"next_steps":[]}
```

**1차 assistant 메시지 3/3 — `2026-08-15T07:26:20.528Z`**

```json
{"verdict":"needs-attention","summary":"초기 추적만으로도 Flyway 가 advisory lock용 연결을 점유한 채 Flyway 내부에서 별도 연결을 요구해 풀 크기 1에서 기동이 막히는 경로와, `alembic_version` 빈 테이블을 정상으로 사면하는 분기가 확인됐습니다. 나머지 게이트도 실제 소비자와 음성 대조까지 계속 확인 중입니다.","findings":[],"next_steps":[]}
```


1차는 이 지점 이후 `verifying` 단계에서 멈췄고 추가 출력이 없다. **잘림 상태 그대로 기록한다** —
추측으로 이어붙이지 않았다.

---

### 3.2 2차 시도 — 완결 출력

#### 3.2.1 assistant 메시지 5건 (JSON 원문)


**2차 assistant 메시지 1/5 — `2026-08-15T12:09:45.510Z`**

```json
{"verdict":"approve","summary":"검토를 시작합니다. 먼저 easy-doc의 기존 stop-gate 메모에서 이 변경과 직접 겹치는 parity·baseline·도달성 판정 기준만 확인한 뒤, 지정한 커밋 범위와 실제 CI/Gradle 배선을 독립적으로 추적하겠습니다.","findings":[],"next_steps":[]}
```

**2차 assistant 메시지 2/5 — `2026-08-15T12:09:54.744Z`**

```json
{"verdict":"approve","summary":"기존 기록이 이번 diff의 parity 도달성·독립 기준선 문제와 직접 겹치므로 `easy-doc-parity-stop-gate-review` 스킬을 사용합니다. 스킬이 요구하는 독립 canonical invariant, 실제 CI 명령, 음성 대조를 이번 코드에 맞춰 재검증하겠습니다.","findings":[],"next_steps":[]}
```

**2차 assistant 메시지 3/5 — `2026-08-15T12:10:41.303Z`**

```json
{"verdict":"needs-attention","summary":"첫 번째 확정 반례가 나왔습니다. guard는 풀에서 연결 하나를 빌린 채 `flyway.info()/baseline()/migrate()`가 별도 연결을 요구하므로 최대 풀 크기 1에서는 잠금 보호가 아니라 자기 교착이 됩니다. 또한 세션 advisory lock은 같은 키를 자발적으로 잡는 기동끼리만 직렬화할 뿐, 잠금에 참여하지 않는 DDL을 막지 않아 ‘판정한 스키마와 baseline 스키마가 같다’는 선언까지는 증명하지 못합니다.","findings":[{"severity":"high","title":"풀 크기 1에서 Flyway guard가 자기 교착한다","body":"잠금 연결을 반납하지 않은 상태에서 Flyway가 자체 연결을 열기 때문에 두 번째 연결을 얻지 못한다.","file":"backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt","line_start":58,"line_end":73,"confidence":0.98,"recommendation":"하나의 연결을 점유한 채 Flyway를 재진입하지 말고, Flyway가 사용하는 동일한 데이터베이스 잠금/연결 경계 안에서 판정과 baseline을 수행하거나 별도 관리용 DataSource와 명시적 풀 요구사항·타임아웃 테스트를 둔다."}],"next_steps":["나머지 네 축에서 false-green 반례와 실제 CI 도달성을 계속 확인합니다."]}
```

**2차 assistant 메시지 4/5 — `2026-08-15T12:15:20.858Z`**

```json
{"verdict":"needs-attention","summary":"스냅샷 축에서도 false green을 확인했습니다. 현재 `--check`는 20키/13키 일치로 끝나지만, 실행 경로는 스크립트 자신의 사용 예시뿐이고 CI에는 배선되지 않았습니다. 더구나 케이스 목록은 이전 스냅샷을 그대로 입력으로 쓰므로 개별 케이스 삭제를 독립적으로 복원할 기준이 없습니다.","findings":[{"severity":"high","title":"스냅샷 재생성 검사가 CI 도달 0이며 축소된 원장을 그대로 재생산한다","body":"`--check`는 현재 파일과 재생성 결과의 동등성만 보며, 케이스 입력 자체는 `previous`에서 가져온다. 따라서 케이스를 지운 스냅샷이 새 입력 원장이 되고 같은 축소 결과가 재생성된다. CI는 이 명령을 호출하지 않아 스크립트 삭제도 빌드를 깨지 않는다.","file":".claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py","line_start":197,"line_end":210,"confidence":0.99,"recommendation":"케이스 ID와 필수 상수 키를 독립적으로 고정한 manifest를 두고 삭제·부분 축소 음성 대조를 추가하며, CI에서 `--check`를 명시 실행하고 스크립트 존재도 별도 경로로 단언한다."}],"next_steps":["격리된 메모리 프로브로 케이스 삭제와 상수 삭제의 diff-0 여부를 수치로 확인한 뒤 계약·게이트 축을 마감합니다."]}
```

**2차 assistant 메시지 5/5 — `2026-08-15T12:19:34.033Z`** ← **최종**

```json
{"verdict":"needs-attention","summary":"출하 차단. Flyway 잠금은 풀 크기 1에서 기동을 실패시키고 비협조 DDL의 TOCTOU를 막지 못한다. 모듈·스냅샷·parity 장치에는 초록불 우회가 있으며, F3 계약과 테스트 계획도 확정 요구를 완전히 강제하지 않는다. 현행 CI 셸 추출과 tmp_path 파일 격리 자체에서는 별도 blocker를 찾지 못했다.","findings":[{"severity":"high","title":"풀 크기 1에서는 잠금 연결 때문에 Flyway가 기동할 수 없다","body":"guard가 DataSource의 유일한 연결을 빌려 잠근 채 `flyway.info()`, `baseline()`, `migrate()`를 호출한다. `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=1`이면 Flyway가 자체 연결을 얻지 못해 connection timeout 후 기동에 실패한다. 동시성 테스트는 무제한으로 새 연결을 여는 DriverDataSource를 사용해 이 반례를 재현하지 않는다.","file":"backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt","line_start":61,"line_end":71,"confidence":0.99,"recommendation":"잠금용 연결을 Flyway 풀 밖에서 확보하거나 최소 풀 크기를 검증된 구성 계약으로 강제하고, Hikari maximumPoolSize=1인 실제 DataSource 기동 테스트를 추가한다."},{"severity":"high","title":"세션 advisory lock은 선언한 스키마 TOCTOU를 닫지 못한다","body":"잠금은 같은 키를 자발적으로 획득하는 guard끼리만 직렬화한다. 지문 확인 직후 Alembic·운영자·다른 프로세스가 별도 연결에서 `ALTER TABLE users ADD COLUMN nickname text`를 실행해도 PostgreSQL DDL은 이 advisory lock과 충돌하지 않는다. 이후 별도 Flyway 연결이 baseline을 기록하므로 확인하지 않은 스키마 위에 V2가 적용될 수 있다.","file":"backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt","line_start":57,"line_end":71,"confidence":0.98,"recommendation":"Alembic과 모든 스키마 변경 경로가 동일 잠금 규약에 참여하도록 강제하고, 배포 중 비협조 DDL을 금지하는 실행 계약을 둔다. 지문 확인과 baseline 사이의 동시 DDL 음성 테스트도 추가한다."},{"severity":"high","title":"존재하지만 비어 있는 alembic_version이 정상 head로 승인된다","body":"애플리케이션 스키마 지문이 정확한 상태에서 `DELETE FROM alembic_version`을 실행하면 조회 결과가 빈 목록이 되고 이 분기가 그대로 통과한다. Flyway 이력이 없는 해당 DB에는 baseline이 기록되지만, 실제 Alembic revision은 전혀 증명되지 않았다. 신규 테스트는 0007과 테이블 부재만 다뤄 이 상태를 놓친다.","file":"backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt","line_start":169,"line_end":175,"confidence":0.99,"recommendation":"테이블이 존재하면 행이 정확히 하나이고 값이 `0006`일 때만 승인한다. 빈 테이블, NULL 불가 확인, 다중 head를 명시적으로 거부하는 회귀 테스트를 추가한다."},{"severity":"high","title":"moduleBoundaryCheck를 모두 통과하는 컴파일 의존 경로가 남아 있다","body":"선언 목록은 루트 configuration 시점에 즉시 스냅샷되고 `ProjectDependency`만 수집한다. 따라서 `compileOnly(files(project(\":infrastructure\").tasks.named(\"jar\")))` 같은 파일 의존은 선언 축에서 사라지고, resolved artifact의 displayName도 정확히 `project :infrastructure`가 아니어서 두 번째 축도 통과하지만 api 소스는 어댑터 타입을 import할 수 있다. 실제 compile task에 별도 configuration을 더하는 경로도 `compileClasspath` 검사 밖이다. 또한 검사 대상은 api·worker뿐이라 core의 `compileOnly(\"org.springframework:spring-core:…\")`와 미열거 Spring 타입 사용은 기존 Class.forName 테스트를 통과한다.","file":"backend-kotlin/build.gradle.kts","line_start":282,"line_end":320,"confidence":0.95,"recommendation":"자식 프로젝트 평가 이후 선언을 지연 수집하고 모든 Kotlin production compile task의 실제 입력 파일과 component origin을 검사한다. infrastructure jar 파일 의존도 거부하고, core도 모든 production compile configuration에서 Spring·DB group/module을 금지한다."},{"severity":"high","title":"스냅샷 검사는 축소된 previous를 정본으로 재사용하며 CI 도달도 0이다","body":"케이스 입력을 기존 스냅샷 배열에서 그대로 읽으므로 케이스 삭제가 새 원장이 된다. 메모리 프로브에서 system prompt 케이스를 6개에서 5개로 줄여도 재생성 diff가 0이었고, 파생 스타일 키 삭제와 생성기 상수+스냅샷 키 동시 삭제도 모두 diff 0이었다. 현재 `--check`는 20키/13키 일치지만 CI 어디에서도 호출되지 않아 스크립트를 제거해도 build/test가 깨지지 않는다. Kotlin 테스트는 남은 값은 단언하지만 삭제된 케이스의 존재는 복원하지 못한다.","file":".claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py","line_start":197,"line_end":210,"confidence":0.99,"recommendation":"필수 키와 케이스 ID를 독립 manifest로 고정하고 삭제·부분 축소 음성 대조를 추가한다. CI에서 `--check`와 스크립트 경로를 명시 실행해 제거도 실패시키도록 한다."},{"severity":"high","title":"게이트 우회 회귀가 helper만 검사해 실제 비교기 배선 제거를 놓친다","body":"runtime 위조 테스트는 `runtime_problem()`을, fixture 축소 테스트는 `provenance_problems()`를 직접 호출한다. 실제 비교 흐름에서 해당 helper 호출을 제거해도 이 테스트들은 모두 통과하며 비교기는 위조 runtime 또는 축소 fixture를 다시 승인한다. CI도 이 파일을 명시 실행하지 않고 전체 pytest 수집에만 의존하므로 파일 삭제 역시 테스트 한 건 감소로 끝난다.","file":"tests/test_parity_ci_gate.py","line_start":380,"line_end":437,"confidence":0.98,"recommendation":"tmp fixture와 actual을 만든 뒤 비교기 CLI/메인 경로를 실행해 exit 1을 단언하는 end-to-end 음성 대조로 바꾼다. CI에서도 이 테스트 파일을 경로로 명시해 삭제·전건 deselect를 실패시킨다."},{"severity":"high","title":"F3 계약이 2개 필드를 원시 길이로 고정해 정규화 후 판정 요구와 충돌한다","body":"password와 DocumentTextRequest.text는 명시적으로 `measured_on: raw`다. 예를 들어 비밀번호 `\"       a\"`는 원시 8자로 통과하지만 trim 후 1자이며, 3,999자 본문에 제어문자 2개를 붙인 입력은 원시 4,001자로 거절되지만 정규화 후에는 3,999자다. 계약을 그대로 구현하면 확정된 정규화 후 경계를 위반한다. text 비대칭을 Phase 4 미결로 미룬 조항도 이미 확정된 요구를 유예한다.","file":"contracts/easy-doc-v1.yaml","line_start":392,"line_end":424,"confidence":0.99,"recommendation":"다섯 필드 모두에 요구된 필드별 정규화 순서와 정규화 후 경계를 확정하고 raw 판정 및 open asymmetry를 제거한다. 각 필드에 원시 초과/정규화 후 허용과 그 반대 경계 사례를 계약 데이터로 추가한다."},{"severity":"high","title":"F3 테스트 계획은 password의 배열 detail 회귀를 허용하고 생성 도구도 새 제약을 보지 못한다","body":"X-F1·X-F2는 두 문서 필드, X-F9는 email·edited_text·name 세 필드, X-F10은 공백 name만 검사한다. 따라서 password에 `@Size(min=8)`을 사용해 `\"1234567\"`에 422 배열 detail을 내도 이 F3 단언들은 전부 통과한다. 또한 제약은 비표준 `x-service-constraint`/`x-request-field-constraints`뿐이고 실행 소비자가 없어, 이를 제거해도 현재 CI는 깨지지 않으며 Phase 6 OpenAPI 코드 생성은 다섯 필드를 단순 string으로만 보고 길이·정규화·detail 분기를 잃는다.","file":"docs/migration/_workspace/00_contract-keeper_test-plan.md","line_start":133,"line_end":136,"confidence":0.98,"recommendation":"다섯 필드 각각에 실패 경계와 `detail` 문자열 타입을 직접 단언하고 password `@Size` 음성 대조를 추가한다. Phase 6 생성기가 vendor extension을 실제 validator/metadata로 소비하도록 구현하거나 별도 기계가독 계약을 마련한다."}],"next_steps":["Flyway guard를 풀 크기 1, 동시 비협조 DDL, 빈 alembic_version 상태로 회귀 검증한다.","Gradle 경계를 실제 compile task 입력과 core까지 확장한다.","스냅샷 독립 manifest와 CI 배선을 추가하고 parity 회귀를 CLI 종단 테스트로 전환한다.","F3의 다섯 필드 정규화·detail 계약과 Phase 6 생성 소비자를 확정한다."]}
```


#### 3.2.2 스크립트가 출력한 최종 보고 블록 (원문)

```text

# Codex Adversarial Review

Target: branch diff against 5797d87
Verdict: needs-attention

출하 차단. Flyway 잠금은 풀 크기 1에서 기동을 실패시키고 비협조 DDL의 TOCTOU를 막지 못한다. 모듈·스냅샷·parity 장치에는 초록불 우회가 있으며, F3 계약과 테스트 계획도 확정 요구를 완전히 강제하지 않는다. 현행 CI 셸 추출과 tmp_path 파일 격리 자체에서는 별도 blocker를 찾지 못했다.

Findings:
- [high] 풀 크기 1에서는 잠금 연결 때문에 Flyway가 기동할 수 없다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt:61-71)
  guard가 DataSource의 유일한 연결을 빌려 잠근 채 `flyway.info()`, `baseline()`, `migrate()`를 호출한다. `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE=1`이면 Flyway가 자체 연결을 얻지 못해 connection timeout 후 기동에 실패한다. 동시성 테스트는 무제한으로 새 연결을 여는 DriverDataSource를 사용해 이 반례를 재현하지 않는다.
  Recommendation: 잠금용 연결을 Flyway 풀 밖에서 확보하거나 최소 풀 크기를 검증된 구성 계약으로 강제하고, Hikari maximumPoolSize=1인 실제 DataSource 기동 테스트를 추가한다.
- [high] 세션 advisory lock은 선언한 스키마 TOCTOU를 닫지 못한다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt:57-71)
  잠금은 같은 키를 자발적으로 획득하는 guard끼리만 직렬화한다. 지문 확인 직후 Alembic·운영자·다른 프로세스가 별도 연결에서 `ALTER TABLE users ADD COLUMN nickname text`를 실행해도 PostgreSQL DDL은 이 advisory lock과 충돌하지 않는다. 이후 별도 Flyway 연결이 baseline을 기록하므로 확인하지 않은 스키마 위에 V2가 적용될 수 있다.
  Recommendation: Alembic과 모든 스키마 변경 경로가 동일 잠금 규약에 참여하도록 강제하고, 배포 중 비협조 DDL을 금지하는 실행 계약을 둔다. 지문 확인과 baseline 사이의 동시 DDL 음성 테스트도 추가한다.
- [high] 존재하지만 비어 있는 alembic_version이 정상 head로 승인된다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuard.kt:169-175)
  애플리케이션 스키마 지문이 정확한 상태에서 `DELETE FROM alembic_version`을 실행하면 조회 결과가 빈 목록이 되고 이 분기가 그대로 통과한다. Flyway 이력이 없는 해당 DB에는 baseline이 기록되지만, 실제 Alembic revision은 전혀 증명되지 않았다. 신규 테스트는 0007과 테이블 부재만 다뤄 이 상태를 놓친다.
  Recommendation: 테이블이 존재하면 행이 정확히 하나이고 값이 `0006`일 때만 승인한다. 빈 테이블, NULL 불가 확인, 다중 head를 명시적으로 거부하는 회귀 테스트를 추가한다.
- [high] moduleBoundaryCheck를 모두 통과하는 컴파일 의존 경로가 남아 있다 (backend-kotlin/build.gradle.kts:282-320)
  선언 목록은 루트 configuration 시점에 즉시 스냅샷되고 `ProjectDependency`만 수집한다. 따라서 `compileOnly(files(project(":infrastructure").tasks.named("jar")))` 같은 파일 의존은 선언 축에서 사라지고, resolved artifact의 displayName도 정확히 `project :infrastructure`가 아니어서 두 번째 축도 통과하지만 api 소스는 어댑터 타입을 import할 수 있다. 실제 compile task에 별도 configuration을 더하는 경로도 `compileClasspath` 검사 밖이다. 또한 검사 대상은 api·worker뿐이라 core의 `compileOnly("org.springframework:spring-core:…")`와 미열거 Spring 타입 사용은 기존 Class.forName 테스트를 통과한다.
  Recommendation: 자식 프로젝트 평가 이후 선언을 지연 수집하고 모든 Kotlin production compile task의 실제 입력 파일과 component origin을 검사한다. infrastructure jar 파일 의존도 거부하고, core도 모든 production compile configuration에서 Spring·DB group/module을 금지한다.
- [high] 스냅샷 검사는 축소된 previous를 정본으로 재사용하며 CI 도달도 0이다 (.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py:197-210)
  케이스 입력을 기존 스냅샷 배열에서 그대로 읽으므로 케이스 삭제가 새 원장이 된다. 메모리 프로브에서 system prompt 케이스를 6개에서 5개로 줄여도 재생성 diff가 0이었고, 파생 스타일 키 삭제와 생성기 상수+스냅샷 키 동시 삭제도 모두 diff 0이었다. 현재 `--check`는 20키/13키 일치지만 CI 어디에서도 호출되지 않아 스크립트를 제거해도 build/test가 깨지지 않는다. Kotlin 테스트는 남은 값은 단언하지만 삭제된 케이스의 존재는 복원하지 못한다.
  Recommendation: 필수 키와 케이스 ID를 독립 manifest로 고정하고 삭제·부분 축소 음성 대조를 추가한다. CI에서 `--check`와 스크립트 경로를 명시 실행해 제거도 실패시키도록 한다.
- [high] 게이트 우회 회귀가 helper만 검사해 실제 비교기 배선 제거를 놓친다 (tests/test_parity_ci_gate.py:380-437)
  runtime 위조 테스트는 `runtime_problem()`을, fixture 축소 테스트는 `provenance_problems()`를 직접 호출한다. 실제 비교 흐름에서 해당 helper 호출을 제거해도 이 테스트들은 모두 통과하며 비교기는 위조 runtime 또는 축소 fixture를 다시 승인한다. CI도 이 파일을 명시 실행하지 않고 전체 pytest 수집에만 의존하므로 파일 삭제 역시 테스트 한 건 감소로 끝난다.
  Recommendation: tmp fixture와 actual을 만든 뒤 비교기 CLI/메인 경로를 실행해 exit 1을 단언하는 end-to-end 음성 대조로 바꾼다. CI에서도 이 테스트 파일을 경로로 명시해 삭제·전건 deselect를 실패시킨다.
- [high] F3 계약이 2개 필드를 원시 길이로 고정해 정규화 후 판정 요구와 충돌한다 (contracts/easy-doc-v1.yaml:392-424)
  password와 DocumentTextRequest.text는 명시적으로 `measured_on: raw`다. 예를 들어 비밀번호 `"       a"`는 원시 8자로 통과하지만 trim 후 1자이며, 3,999자 본문에 제어문자 2개를 붙인 입력은 원시 4,001자로 거절되지만 정규화 후에는 3,999자다. 계약을 그대로 구현하면 확정된 정규화 후 경계를 위반한다. text 비대칭을 Phase 4 미결로 미룬 조항도 이미 확정된 요구를 유예한다.
  Recommendation: 다섯 필드 모두에 요구된 필드별 정규화 순서와 정규화 후 경계를 확정하고 raw 판정 및 open asymmetry를 제거한다. 각 필드에 원시 초과/정규화 후 허용과 그 반대 경계 사례를 계약 데이터로 추가한다.
- [high] F3 테스트 계획은 password의 배열 detail 회귀를 허용하고 생성 도구도 새 제약을 보지 못한다 (docs/migration/_workspace/00_contract-keeper_test-plan.md:133-136)
  X-F1·X-F2는 두 문서 필드, X-F9는 email·edited_text·name 세 필드, X-F10은 공백 name만 검사한다. 따라서 password에 `@Size(min=8)`을 사용해 `"1234567"`에 422 배열 detail을 내도 이 F3 단언들은 전부 통과한다. 또한 제약은 비표준 `x-service-constraint`/`x-request-field-constraints`뿐이고 실행 소비자가 없어, 이를 제거해도 현재 CI는 깨지지 않으며 Phase 6 OpenAPI 코드 생성은 다섯 필드를 단순 string으로만 보고 길이·정규화·detail 분기를 잃는다.
  Recommendation: 다섯 필드 각각에 실패 경계와 `detail` 문자열 타입을 직접 단언하고 password `@Size` 음성 대조를 추가한다. Phase 6 생성기가 vendor extension을 실제 validator/metadata로 소비하도록 구현하거나 별도 기계가독 계약을 마련한다.

Next steps:
- Flyway guard를 풀 크기 1, 동시 비협조 DDL, 빈 alembic_version 상태로 회귀 검증한다.
- Gradle 경계를 실제 compile task 입력과 core까지 확장한다.
- 스냅샷 독립 manifest와 CI 배선을 추가하고 parity 회귀를 CLI 종단 테스트로 전환한다.
- F3의 다섯 필드 정규화·detail 계약과 Phase 6 생성 소비자를 확정한다.
```

---

## 4. 정리(가공)

**이 구획은 Claude 가 썼다.** §3 원문의 지적을 목록화·색인한 것이며, **옳고 그름·심각도 재부여·
중복 병합·오탐 판정은 하지 않았다.** 심각도는 codex 가 붙인 값을 그대로 옮겼다. 판정과 종합은
`migration-reviewer` 와 리더의 몫이다.

### 4.1 최종 판정 (codex 원문 인용)

- **verdict**: `needs-attention`
- **summary**: "출하 차단. Flyway 잠금은 풀 크기 1에서 기동을 실패시키고 비협조 DDL의 TOCTOU를
  막지 못한다. 모듈·스냅샷·parity 장치에는 초록불 우회가 있으며, F3 계약과 테스트 계획도 확정
  요구를 완전히 강제하지 않는다. 현행 CI 셸 추출과 tmp_path 파일 격리 자체에서는 별도 blocker를
  찾지 못했다."

### 4.2 지적 색인 — 8건 (전부 `high`, codex 부여값)

| # | 심각도 | 제목 | 위치 (codex 지목) | conf | 리더 focus 축 |
|---|---|---|---|---|---|
| C15-1 | high | 풀 크기 1에서는 잠금 연결 때문에 Flyway가 기동할 수 없다 | `FlywayBaselineGuard.kt:61-71` | 0.99 | ① |
| C15-2 | high | 세션 advisory lock은 선언한 스키마 TOCTOU를 닫지 못한다 | `FlywayBaselineGuard.kt:57-71` | 0.98 | ① |
| C15-3 | high | 존재하지만 비어 있는 `alembic_version` 이 정상 head로 승인된다 | `FlywayBaselineGuard.kt:169-175` | 0.99 | ① |
| C15-4 | high | `moduleBoundaryCheck` 를 모두 통과하는 컴파일 의존 경로가 남아 있다 | `build.gradle.kts:282-320` | 0.95 | ② |
| C15-5 | high | 스냅샷 검사는 축소된 `previous` 를 정본으로 재사용하며 CI 도달도 0이다 | `dump_python_snapshots.py:197-210` | 0.99 | ③ + ⑥ |
| C15-6 | high | 게이트 우회 회귀가 helper만 검사해 실제 비교기 배선 제거를 놓친다 | `tests/test_parity_ci_gate.py:380-437` | 0.98 | ④ + ⑥ |
| C15-7 | high | F3 계약이 2개 필드를 원시 길이로 고정해 정규화 후 판정 요구와 충돌한다 | `contracts/easy-doc-v1.yaml:392-424` | 0.99 | ⑤ |
| C15-8 | high | F3 테스트 계획은 password의 배열 detail 회귀를 허용하고 생성 도구도 새 제약을 보지 못한다 | `00_contract-keeper_test-plan.md:133-136` | 0.98 | ⑤ + ⑥ |

`medium`·`low` 지적은 **0건**이다 — codex 가 낮은 심각도를 하나도 붙이지 않았다.

### 4.3 축별 도달 — codex 가 "찾지 못했다"고 명시한 자리

`migration-reviewer` 가 "지적 0건 = 문제 없음"으로 읽지 않도록, codex 가 **명시적으로 면제한
자리**만 옮긴다.

- **④ 중 파일 격리**: "현행 CI 셸 추출과 `tmp_path` 파일 격리 자체에서는 **별도 blocker를 찾지
  못했다**" (최종 summary). 즉 ④ 의 (a) 실물 파일 오염 축과 (c) `_step_script()` 추출 축은
  지적 없이 통과했고, ④ 에서 나온 지적 C15-6 은 (b) **음성 대조 유효성** 축이다.
- 나머지 다섯 축(①②③⑤⑥)에는 전부 지적이 붙었다.

### 4.4 전제 확인 필요

원문을 지우지 않고 여기에만 표시한다 — 판단은 `migration-reviewer` 가 한다.

- **C15-4** 는 `build.gradle.kts:282-320` 을 지목하는데, 이 커밋의 `moduleBoundaryCheck` 블록은
  diff 상 **237-348행** 범위에 있다. 지목 행이 블록 중간을 가리키는지, 다른 판을 읽은 것인지
  대조가 필요하다.
- **C15-7** 은 `contracts/easy-doc-v1.yaml:392-424` 와 `measured_on: raw`, 그리고 "text 비대칭을
  Phase 4 미결로 미룬 조항"을 근거로 든다. 이 회차 수집에서 확인한 것은 `password` 의
  `measured_on: "원시 값 — 정규화하지 않는다"` 한 줄까지이며, `DocumentTextRequest.text` 의
  표기와 Phase 4 유예 조항의 존재 여부는 **이 문서에서 확인하지 않았다**.
- **C15-5** 는 "메모리 프로브에서 system prompt 케이스를 6개에서 5개로 줄여도 재생성 diff 가
  0이었다"는 **실측 주장**을 담고 있다. codex 는 실제로 `.venv/bin/python` 프로브를 실행했다
  (job 로그에 `importlib.util` 프로브 명령 2건). 재현 여부는 판정 단계에서 확인할 사항이다.
- **C15-6** 은 "CI도 이 파일을 명시 실행하지 않고 전체 pytest 수집에만 의존한다"고 한다.
  codex 가 `rg "test_parity_ci_gate|dump_python_snapshots" .github tests pyproject.toml` 을
  실행한 기록이 있다(job 로그).

### 4.5 codex 가 낸 next_steps (원문)

1. Flyway guard를 풀 크기 1, 동시 비협조 DDL, 빈 `alembic_version` 상태로 회귀 검증한다.
2. Gradle 경계를 실제 compile task 입력과 core까지 확장한다.
3. 스냅샷 독립 manifest와 CI 배선을 추가하고 parity 회귀를 CLI 종단 테스트로 전환한다.
4. F3의 다섯 필드 정규화·detail 계약과 Phase 6 생성 소비자를 확정한다.

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| 1차 시도 (`review-msu1unr8-ryts0n`) | **행 → 취소.** `verifying` 단계에서 4h37m 무활동. 부분 출력 3건을 §3.1 에 무편집 보존, `findings` 전부 빈 배열임을 명시. 잘림 상태 그대로 두고 추측 보완하지 않았다 |
| 2차 시도 (`review-msuc2j9t-k16qu3`) | **성공.** 완결 산출물 수령 |
| codex 한도 오류 | **발생하지 않았다.** 리더 호출문이 전제한 한도 소진은 게이트 14 시점의 사실이며 이번 회차에는 재현되지 않았다 |
| "codex 미경유" 대체 산출물 | **작성하지 않았다.** 실제 codex 출력이 있으므로 불필요하며, Claude 자작 리뷰로 대체하면 이 게이트의 독립성이 소멸한다 |
| 민감 데이터 | 프롬프트에 **싣지 않았다.** 대상이 가드·게이트·계약 문서라 합성 값조차 필요 없었다. codex 가 저장소를 직접 읽는 것은 로컬 실행이다 |
| `migration-reviewer` 산출물 참조 | **보지 않았다** (리더 지시 — 1차 독립 리뷰) |
| 이전 회차 맥락 | 게이트 15 는 **1차 회차**라 이전 회차가 없다. 게이트 14 이하의 지적 처리 상태는 맥락으로 제공하지 않았다 |

### 5.1 재현 정보

```
job 로그 (1차): /Users/harris/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-msu1unr8-ryts0n.log
job 로그 (2차): /Users/harris/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-msuc2j9t-k16qu3.log
codex resume (2차): codex resume 01a00553-d810-74c0-8ce0-960133beac2d
```

---

## 6. `migration-reviewer` 와 리더에게

- 이 회차는 **완전한 독립 관점을 수령했다.** 게이트 14와 달리 `findings` 가 채워진 완결 산출물이다.
- **8건 전부 `high`** 이며 codex 자신의 summary 는 "출하 차단"이라는 표현을 쓴다. 다만 **Phase 3
  종료 조건 충족 여부의 판정은 이 문서의 몫이 아니다** — 여기 있는 것은 codex 의 견해이고,
  채택·기각·심각도 조정은 교차 대조와 리더 판정에서 이뤄진다.
- 지적 8건 중 **6건(C15-1·2·3·5·6·8)이 "장치가 초록불을 내면서 재지 못한다"는 형태**로,
  이 저장소가 규칙 4·6으로 다루는 결함 구조와 같은 계열이다. 그 계열로 묶어 볼지는 종합의 판단이다.
- §4.4 의 전제 확인 4건은 **원문을 지우지 않고 표시만** 한 것이다. 원문 삭제는 독립 관점의 정보
  손실이라 하지 않았다.
