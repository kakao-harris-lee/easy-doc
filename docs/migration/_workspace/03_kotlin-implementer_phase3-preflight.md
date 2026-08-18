# Phase 3 착수 전 미결 원장 3건 (kotlin-implementer)

작성 2026-08-15

---

## 1. Flyway 지문 TOCTOU + Alembic head 미확인 (codex #3·#4 / F-3·F-5)

### 무엇이 열려 있었나

지문 판정·baseline·migrate 가 **각각 다른 연결**이었고 어떤 잠금에도 덮이지 않았다.

- **TOCTOU** — 지문을 읽은 뒤 baseline 을 찍기 전에 스키마가 바뀌면 **확인하지 않은 스키마에
  baseline 이 찍힌다.** 그 뒤 V2 부터 적용되므로 기준선과 다른 바닥 위에 쌓인다.
- **동시 기동** — api·worker·migrate 프로필이 함께 뜨면 셋이 같은 판정을 동시에 하고 각자
  baseline 을 시도한다.

### 고친 방향

연결 하나를 열어 **세션 advisory lock**(`pg_advisory_lock`)을 잡고, 그 안에서 이력 확인 →
테이블 수 → 지문 대조 → Alembic head → `baseline()` → `migrate()` 를 모두 돌린다. 잠금을 쥔
연결을 열어 둔 채 Flyway 를 부르므로 그 사이에 다른 인스턴스가 끼어들 수 없다.

`pg_advisory_xact_lock` 이 아닌 이유: Flyway 가 자기 트랜잭션을 따로 여는 순간 풀린다.
`finally` 로 반드시 푼다 — 풀지 못한 연결이 풀에 남으면 **다음 기동이 영원히 대기**한다.

Flyway 자신의 이력 테이블 잠금과는 층이 다르다. 그쪽은 이력 쓰기를 지키고, 이쪽은
**"읽고 판정한 것과 기록하는 대상이 같은 스키마"** 를 지킨다.

### Alembic head 확인 — 읽기는 금지된 적이 없다

계획 §4.2-7 이 금지한 것은 `alembic_version` **쓰기**다. 읽지도 않은 것은 구현자의
자기부과 제약이었다.

읽지 않으면 새는 축: 지문은 **스키마 모양**만 본다. 값만 바꾸는 리비전(백필 등)은 지문이
같으므로 통과하고, V1 이 가정한 것과 다른 상태 위에 baseline 이 찍힌다. 그 축을 테스트로
직접 고정했다 — `지문은 리비전 변경에 반응하지 않는다` 가 **지문만으로는 못 잡는다**를
보이고, `리비전이 다르면 실패한다` 가 새 검사가 잡는 것을 보인다. 두 개를 함께 두지 않으면
"이 검사가 필요한가"에 답할 근거가 없다.

`to_regclass` 를 WHERE 절에 넣어 한 문장으로 만들면 안 된다 — PostgreSQL 은 계획 단계에서
relation 을 해석하므로 조건이 걸러 주기 전에 죽는다(실측). 존재를 먼저 따로 묻는다.

### Testcontainers 회귀 5건

리비전 불일치 차단 · 지문의 무반응(축 분리 증명) · `alembic_version` 없는 DB 통과 ·
baseline 후에도 `alembic_version` 불변(읽기만 한다) · **동시 기동 3스레드 직렬화**.

## 2. `CoreModuleBoundaryTest` 우회 (codex #5)

### 무엇이 비어 있었나

기존 테스트는 **core 의 테스트 런타임에 클래스가 있는가**만 본다. 그래서 ⑴ `compileOnly` 로
넣으면 통과 ⑵ 목록 밖 타입이면 통과 ⑶ **`api`·`worker` 가 infrastructure 를 어떻게 붙이는지
아무도 안 봤다.** `runtimeOnly` → `implementation` **한 글자 변경**에 깨지는 테스트가 0건이고,
그 순간 api 소스가 어댑터 타입을 직접 import 할 수 있게 된다.

클래스 존재 검사로는 ⑶ 을 볼 수 없다 — **런타임에는 어느 쪽이든 있기 때문**이다. 그래서
판정 대상을 클래스패스가 아니라 **Gradle configuration 자체**로 옮겼다.

### 두 축을 함께 본다

- **선언 종류** — `:infrastructure` 를 선언한 configuration 이 허용 목록(`runtimeOnly`·
  `testImplementation`·`testRuntimeOnly`) 안인가. 선언을 읽는 것이라 해석이 필요 없다.
- **compileClasspath 부재** — 실제로 컴파일 시점에 안 보이는가. 선언 검사를 우회하는 전이
  노출을 여기서 잡는다.

`check` 에 걸어 `./gradlew build` 가 이 판정을 지난다.

> **판정을 각 소비 모듈 안에서 돌린다.** 바깥 태스크에서 다른 프로젝트의 configuration 을
> 해석하면 Gradle 이 *"exclusive lock 없이 해석했다"* 로 거부한다(실측). 그리고 해석 결과를
> `inputs.files` 로 걸지 않으면 *"`:application:jar` 가 끝나기 전에 mapped value 를 물었다"*
> 로 거부된다 — 클래스패스 해석이 상류 jar 를 요구하기 때문이다. 두 번 다 실측으로 고쳤다.

### 음성 대조

`api` 의 `runtimeOnly` 를 `implementation` 으로 바꾸면 `:api:moduleBoundaryCheck` 가
**실패한다**(실측). 되돌린 뒤 통과를 재확인했다.

> **복원 사고 하나를 적어 둔다.** 음성 대조 뒤 복원 명령이 상대 경로였는데 같은 셸에서
> `cd backend-kotlin` 을 한 뒤라 **변조 파일이 디스크에 남았다.** 즉시 절대 경로로 복원하고
> 내용까지 확인했다. `CLAUDE.md` 규칙 5가 경고한 그 형태이고, 원인은 별칭이 아니라
> **작업 디렉터리 이동**이다.

## 3. X-9 — 스냅샷 생성기 (`app/**` 삭제 전 마감)

`dump_python_snapshots.py`. 프롬프트·스타일 스냅샷은 Kotlin 포팅 판정의 **정본**인데 그것을
만든 절차가 어디에도 없었다 — 값은 있는데 **다시 만들 방법이 없는** 상태였다.

| 갈래 | 출처 |
|---|---|
| 상수 표(사전 246·글로스 123 등) | `style_rules`·`prompts` 에서 **이름으로** 읽는다 |
| 케이스별 `expected` | Python 함수를 **실제로 호출**해 얻는다 |
| 케이스 **입력** | 기존 스냅샷에서 그대로 (사람이 고른 큐레이션이라 유도할 수 없다) |

**재생성 diff 0 을 확인했다** — 프롬프트 20키 · 스타일 13키 전부 일치.

N-13 전례를 따랐다: 이름이 하나라도 없으면 `SnapshotError` 로 끝나고(줄어든 스냅샷은 Kotlin
검사를 통과시킨다), 표준 출력에는 키 이름과 개수만 낸다.

**실측으로 고친 세 자리** — ⑴ `COMMA_CHARS` 는 모듈에서 `_COMMA_CHARS`(비공개)라 별칭 표가
필요했다 ⑵ `GLOSS_COLLISION_PATTERNS` 는 **(글로스, 정규식)** 순서라 반대로 읽었더니
`Pattern is not JSON serializable` 로 죽었다 ⑶ `build_user_prompt`·`build_repair_prompt` 가
매 호출 `secrets.token_hex` 로 새 문서 id 를 박아 **재생성 diff 가 늘 0 이 아니었다** —
스냅샷이 `_fixed_document_id` 를 따로 싣는 이유가 이것이고, 그 값을 되먹여 고정한다.

## 4. 검사 결과

| 검사 | 결과 |
|---|---|
| `./gradlew build` (모듈 경계 판정 포함) | **BUILD SUCCESSFUL** |
| `FlywayBaselineGuardTest` (Testcontainers) | **전건 통과** — 신규 5건 포함 |
| 모듈 경계 음성 대조 | 한 글자 변경 시 **실패** 확인 후 복원 |
| 스냅샷 재생성 | **diff 0** (프롬프트 20키 · 스타일 13키) |
| `pytest` 전체 | **1182 passed · 5 xfailed** |
| `ruff check` · `ruff format --check .` | 통과 (151 files) |

**`mypy` 는 2건이 남아 있고 내 변경이 아니다** — `tests/test_parity_ci_gate.py:369·372`
(`42f9e20`, parity-verifier 소유). 그 파일은 병렬 레인 소관이라 손대지 않았다.
`uv run mypy .claude` 는 통과한다.

---

## 5. 계약 F3 준수 — 다섯 요청 필드에 Bean Validation 길이·형식 제약을 쓰지 않는다

> **2026-08-15 추가 · contract-keeper 전파.** 게이트 15 교차 종합 X6 — F3 판정(`d03e5e8`)이
> 계약 changelog 의 통보표에만 있고 **이 문서에 0건**이었다(실측 `grep -ci` — `F3`·
> `x-request-field-constraints`·`x-service-constraint`·`@Size`·`Bean Validation` 전부 0).
> 통보가 changelog 에서 끝나면 Phase 3 착수 준비 문서를 읽는 사람에게는 도달하지 않는다.

### 무엇이 금지되는가

**다섯 요청 필드에 `@Size`·`@NotBlank`·`@Email`(그 밖의 길이·형식 Bean Validation 제약 포함)을
달지 않는다.** 다섯의 길이·형식·빈 값 판정은 **서비스 층**이 하고, 위반은 422 + **문자열**
`detail`(한국어)이다.

**Bean Validation 전면 금지가 아니다.** `GET /documents` 의 `limit`·`offset` 은 진짜 스키마 층
제약이라 그대로 두고 배열 `detail` 이 정상이다 — 계약이 그 대비를 판정 근거로 명시했다
(`contracts/easy-doc-v1.yaml:377-384` `x-contrast-case`). 금지 대상은 **다섯 필드 한정**이다.

### 계약 근거 — 값은 여기 옮겨 적지 않는다

**다섯이 어느 필드인지, 상한이 얼마인지, 원시를 재는지 정규화 후를 재는지, `detail` 문구가
무엇인지는 이 문서에 전사하지 않는다.** 계약 본문을 직접 읽어라. 손으로 옮긴 계약 요약이
원본과 갈리는 것은 이 하네스가 반복해 고쳐 온 레인 간 규약 드리프트이고, 게이트 15 별건 1이
바로 그 형태였다(리뷰 프롬프트가 옮겨 적은 전제가 계약과 달라 지적 하나의 근거가 흔들렸다).

행 번호는 **2026-08-15 실측**이며 키 경로를 함께 적는다 — 파일이 움직이면 행은 틀리고 키는 남는다.

| 무엇 | 위치 (`contracts/easy-doc-v1.yaml`) |
|---|---|
| **판정 전문**(왜 서비스 층인가 · 근거 G1+G4 · 반대 방향을 택하지 않은 이유 셋) | `:332-431` `x-request-field-constraints` — 특히 `x-decision` `:354-375` |
| **다섯 필드의 layer·limit·`measured_on`·`detail`** ← 구현이 읽을 정본 | `:386-411` `x-request-field-constraints.fields` |
| **`@Size`/`@NotBlank` 구현이 계약 위반이라는 명문** | `:1516-1518` `components/responses/ValidationFailed` |
| 배열/문자열 `detail` 의 **경계 규칙** | `:1508-1514` (같은 절) |
| 스키마 쪽 표식 `x-service-constraint` 와 "`maxLength` 를 쓰지 않는다" 서술 | `:1665`·`:1674`·`:1726`·`:1823`·`:1956` |
| 변경 이력·통보 원문 | `:2029-2083` (`x-changelog` 의 `revision: F3` 항목) · `00_contract-keeper_changelog.md` 「F3」 절 |

### 위반하면 어디서 걸리나

**지금은 아무 데서도 걸리지 않는다.** 아래는 `00_contract-keeper_test-plan.md` §3 의 항목이고,
**전부 계획이다 — 실행 소스가 0건이다.** Contract 게이트 자체가 아직 `안 돎` 이고, 이 여섯은
Markdown 두 파일에만 있다(게이트 16 J·W / codex K16-3: *"Markdown 항목을 강제자라고 분류하지
마라"*). **이 표를 「이미 있는 강제 장치」로 읽지 마라** — 아래 항목이 강제자가 되는 시점은
Kotlin 계약 테스트로 **쓰이는 그 커밋**이고, 그때까지 이 금지를 지키는 것은 이 문서뿐이다.

| 검증 | 무엇을 잡나 | 출처 | **실행 상태** |
|---|---|---|---|
| **X-F11** | `password` 하한 미만 → 422 이고 **`detail` 이 문자열 타입**(배열이 아님) · 하한과 같은 길이는 통과 | **2026-08-15 신설** (게이트 15 X5) | **계획 — 실행 소스 0건** · 마감 **Phase 3 해당 DTO 구현 커밋** |
| **X-F12** | `password` 원시 길이는 하한과 같고 trim 후 미달인 입력이 **통과** — 원시 측정 축 | **2026-08-15 신설** | 같음 |
| **X-F13** | `email` 정규화 후 상한 초과 **거절** + 문구가 형식 오류와 동일 | **2026-08-15 신설** | 같음 |
| X-F1·X-F2 | `text`·`edited_text` 길이 경계 + **`detail` 타입 문자열** | 기존 (F3 때 타입 단언 추가) | **계획 — 실행 소스 0건** · 마감 Phase 4 |
| X-F9 | 원시 초과·정규화 후 이하인 입력이 **통과**(정규화 후를 재는 3필드) | 기존 | **계획 — 실행 소스 0건** · 마감 Phase 3(`email`·`name`) / Phase 4(`edited_text`) |
| X-F10 | `name` 이 공백만인 입력을 문자열 `detail` 로 거절 | 기존 | **계획 — 실행 소스 0건** · 마감 Phase 3 |

**경계 값과 `detail` 문구는 이 표에도 적지 않는다** — 위 「계약 근거」 표에서 계약 본문을 읽어라.
「하한」·「상한」은 `x-request-field-constraints.fields[n].limit` 의 **역할 이름**이지 값이 아니다.

**상태 코드만 보는 단언으로는 잡히지 않는다.** `@Size` 로 구현해도 422 는 그대로 나가고 `detail`
모양과 문구만 바뀐다 — 그래서 위 항목들이 **`detail` 의 타입까지** 건다.

### 지금 반대 경로가 정상으로 단언돼 있다 (X6 나머지 축 — `kotlin-implementer` 소관)

`api` 모듈에는 Bean Validation → 배열 `detail` 경로가 이미 완성돼 있고 테스트가 그것을 옳다고
고정한다 — `FrameworkErrorContractTest` 의 `jsonPath("$.detail[0].loc[0]")` 단언들과,
`GlobalExceptionHandler` 의 `errorTypeOf` 가 미지정 코드를 snake_case 로 흘려보내는 `else` 갈래
(`Size` → `size`)다. **행 번호를 적지 않는다** — 이 두 파일은 게이트 15 지적을 받아 병렬 레인이
지금 수정 중이라 실측 시점의 행이 곧 어긋난다. 심벌로 찾아라.

**그 테스트가 틀린 것은 아니다.** 스키마 층 실패(필드 누락·타입 불일치·`limit`/`offset` 범위)의
배열 `detail` 은 계약이다(`:1510-1511`). 틀리는 것은 **다섯 필드가 그 경로를 타는 것**이고, 두
경로가 같은 422 를 내므로 상태 코드로는 구분되지 않는다. 이 자리를 어떻게 정리할지는 구현 레인
판단이다 — 계약이 정하는 것은 **나간 바이트**(상태 코드 · `detail` 타입 · 문구)까지다.

### 미결 두 자리 — 현행 조항대로 구현하고, 바뀌면 함께 바꾼다

1. **`text` / `edited_text` 정규화 비대칭** — `x-open-asymmetry`(`:413-424`). Phase 4,
   `kotlin-implementer`·`parity-verifier` 공동. 계약은 현재 요구를 있는 그대로 적고 미결로 뒀다.
2. **`password` 의 `measured_on: raw`** — 게이트 15 X13 으로 **리더 판정 대기**. codex 는 이것을
   "확정된 정규화 후 경계 위반"으로 읽었으나, 그 전제는 계약이 아니라 리뷰 프롬프트에서 왔고
   계약 자신은 원시로 명시한다(`:395`). **판정이 날 때까지는 계약 문면이 기준이다** — 임의로
   정규화 후로 구현하지 마라. 뒤집히면 계약·X-F12·구현을 **같은 변경 단위**로 고친다.
