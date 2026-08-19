# 게이트 20 (`03_auth`) 교차 종합 조치 — 구현 산출물

**작성:** kotlin-implementer / **일자:** 2026-08-19
**입력:** `reviews/03_auth_cross.md`(정본) · `reviews/03_security_privacy-gate.md` B-1 해제 조건 3항 ·
`03_contract-keeper_auth-verification.md` §5 · 리더 판정 ①~⑥
**범위:** `bf08edd..HEAD` (7커밋) — `00_progress.md` 무접촉

> 값 전사를 하지 않는다. 문구·상한·헤더 값·클레임 값은 파일과 행으로 지목한다.

---

## 0. 조치 요약

| # | 항목 | 처방 | 상태 |
|---|---|---|---|
| ① | B-1 / codex C1 / Claude C-3 — 로그인 타이밍 | 더미 PHC 검증 + HTTP 경계 비(比) 회귀 + 음성 대조 | **닫힘** |
| ② | codex C2 / Claude S-5 — Argon2 세마포어 무한 대기 | `tryAcquire(상한)` + 배압 예외, ①과 같은 커밋 | **닫힘**(응답 코드 판정 1건 남음 → §2-3) |
| ③ | codex C3 ≡ Claude T-2 — 보호 경로 자동 발견 | 매핑 표에서 발견, 손 목록 제거 | **닫힘** |
| ⑭ | contract-keeper §5 — 계약이 Gradle 선언 입력 아님 | 모든 테스트 태스크의 `inputs.file` | **닫힘** |
| ⑧ | Claude C-1 — 422·401 계약 대조 8건 | `assertDeclaredStatus` 11자리 | **닫힘** |
| ⑨ | Claude C-2 — X-D2 5건 중 2건 | S-3·S-9·L-4·M-2 보강 | **닫힘** |
| ⑩ | Claude C-6 — 헤더 `const` 강제자 auth 3곳 | 계약에서 유도(P-3b·P-4b), 5개 테스트 전환 | **닫힘** |
| ⑪ | Claude T-1 — F3 snake_case·도달 지표 | 두 표기 매칭 + 프로퍼티 단위 도달 + 미매칭 실패 | **닫힘** |
| ⑫ | Claude T-7 — 음성 대조 반대 방향 | `03_kotlin-implementer_auth.md` §6-2 정정 + 재실측 | **닫힘** |
| ④ | codex C4 — Jackson scalar coercion (0관점 검증) | **실측 → 사실** → coercion 차단 + 계약 배열 모양 | **닫힘** |
| ⑬ | Claude H-1 — 탐지기 양성 경로 미실행 | 양성 케이스 1건 | **닫힘** |
| — | privacy-gate L-1 — security 로그 레벨 | `application.yml` 고정 | **닫힘** |

**커밋(논리 단위):** `ea15782` ①②/ `265b429` ③/ `2660252` ⑭/ `8b5ede6` ⑧⑨⑩⑪/ `d22c7df` ④/
`618fb89` ⑬/ `2adae30` L-1.

---

## 1. ① 로그인 타이밍 균일화 (privacy-gate 해제 조건 3항)

**처방한 것.**

| 자리 | 파일·행 |
|---|---|
| 포트에 더미 PHC 선언 | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthPorts.kt` `dummyHash()` |
| 계정 부재 경로가 실제 `verify` 를 지난다 | `.../application/auth/AuthService.kt` `login` · `verifyAgainstDummy` |
| 더미를 **현행 정책 파라미터**로 만든다 | `.../infrastructure/auth/Argon2PasswordHasher.kt` `dummy` 프로퍼티 |
| 분기 단위 회귀 2건 | `.../application/src/test/.../AuthServiceTest.kt` 「계정 부재도 해시 검증을 지난다」·「계정이 있으면 저장된 해시로 검증한다」 |
| 더미가 정책을 따르는지 · 통과 불가인지 | `.../infrastructure/src/test/.../Argon2PasswordHasherTest.kt` 2건 |
| **HTTP 경계 시간 축 회귀** | `.../api/src/test/.../AuthEndpointReachTest.kt` L-3b |

**해제 조건 3항 대조.**

1. **더미는 현행 정책 파라미터인가** — 조립 시점에 인코더로 만들므로 정책이 바뀌면 함께 바뀐다.
   단언은 `Argon2PasswordHasherTest` 「더미 해시가 현행 정책을 따른다」 — PHC 파싱이 되고
   `needsRehash(dummy)` 가 `false` 다(파라미터 집합 전체가 현행과 같다는 뜻).
   상수 문자열을 돌려주는 구현이면 두 단언이 모두 깨진다.
2. **더미에 재해시를 걸지 않는다** — `verify` 가 언제나 실패하므로 `rehashIfOutdated` 에 닿지
   않는다. `AuthServiceTest` 가 `rehashed` 가 비어 있음을 함께 단언한다.
3. **절대값이 아니라 두 경로의 비** — L-3b 는 워밍업 1건을 버리고 각 5표본의 중앙값을 내
   그 비를 본다. 조항이 시간 축을 요구한다는 사실 자체도 계약에서 읽는다(그 문장이 지워지면
   케이스가 먼저 깨져 재판정을 강제한다).

**음성 대조 (일회용 worktree · `git checkout --` 복원 + sha256 대조).**

```
AuthService.login 에서 verifyAgainstDummy(password) 한 줄 제거
  → AuthEndpointReachTest L-3b FAILED
     "없는 이메일 2.2ms / 틀린 비밀번호 90.5ms (비 41.6배)"
  → 복원 sha256 일치: True
```

privacy-gate 가 실측한 **42배**(2.3ms vs 97ms)와 같은 자리를 같은 크기로 재현했다.
수정 후 정상 실행에서는 이 케이스가 초록이다.

**남는 것:** L-3b 는 실물 Argon2 가 도는 `@SpringBootTest` 계층에만 있다. 슬라이스의 가짜
해시는 비용이 0 이라 이 축을 잴 수 없다 — 그것이 이 케이스를 그 계층에 둔 이유다.

---

## 2. ② Argon2 세마포어 대기 상한 (①과 같은 커밋)

**왜 같은 커밋인가.** ①의 처방이 「계정 없음」 경로를 무료에서 유료로 바꾼다. 그 경로가
permit 을 소비하기 시작하므로 대기 줄이 길어지고, 종전 `acquire()` 는 **무기한**이었다.

### 2-1. 처방

`Argon2PasswordHasher.withPermit` 가 `tryAcquire(maxWaitMillis, MILLISECONDS)` 를 쓰고,
넘기면 `PasswordHashingOverloadedException` 을 던진다. 설정은
`easydoc.auth.max-hash-wait-millis`(`AuthConfiguration.AuthProperties`). 조립 시점에 양수 검증.

동시 실행 수 상한은 그대로다 — OOM 방어(I-8 검증 5)는 유지되고, 바뀐 것은 **대기의 상한**뿐이다.

### 2-2. 회귀

`Argon2PasswordHasherTest` 2건 — 「대기 상한이 있어야 한다」(조립 거부) ·
「대기 상한을 넘기면 예외다」. 후자는 permit 1개·상한 1ms·해시 1건이 그보다 오래 걸리는
파라미터에서 네 스레드를 `CyclicBarrier` 로 동시에 풀어 놓는다. 종전 `acquire()` 였다면
전부 성공해 실패 목록이 비고, 그 상태를 실패 메시지가 지목한다.

### 2-3. ⚠ 판정 필요 — 상한 초과 시의 응답 코드

리더 판정 ②는 *"계약이 `/auth/login` 에 선언한 상태 코드 안에서만 정하라"* 였다.
계약 선언은 **200 · 401 · 422 · 500 · 503**이다.

**고른 것: 500.** 도메인 예외로 만들지 않아 `GlobalExceptionHandler` 의 백스톱이
`InternalError` 의 **기존 고정 문구**로 낸다 — 새 상태 코드도 새 문구도 만들지 않았다.

**고르지 않은 것과 사유:**

- **503** — 의미상 과부하에 더 가깝지만, 계약의 `ServiceUnavailable` **설명이 원인을
  `ConfigurationError` 로 한정**하고 네 예시가 전부 설정 문제다. 여기에 과부하를 넣으려면
  그 서술을 고쳐야 하고 그것은 계약 개정이다(권한: `contract-keeper`).
- **401** — 과부하가 「비밀번호가 틀렸다」로 둔갑한다. 자격증명을 보지도 않은 채 실패를
  자격증명 탓으로 돌리는 것이라 진단이 막힌다.

**요청:** 「과부하 배압에 503 + 전용 문구를 계약에 넣을지」는 계약 소유자 판정이다.
넣기로 하면 이 자리는 도메인 예외 한 줄 + 매핑 한 줄로 바뀐다. 심각도 라벨은 사용자 판단 대기.

---

## 3. ③ 보호 경로 검사의 자동 발견 (codex C3 ≡ Claude T-2)

`AuthenticationCoverageContractTest` 를 다시 썼다(`api/src/test/.../AuthenticationCoverageContractTest.kt`).

- 대상 범위를 `RequestMappingHandlerMapping` 에서 **발견**한다. 수기 `implementedPaths()` 삭제.
- 「공개 목록 ∪ 보호 목록 = 전체 매핑」을 정확 일치로 단언 — 어느 쪽에도 없는 매핑은 실패.
- 보호로 분류된 매핑이 전부 `AuthenticatedEndpoints` 에 있는지 별도 단언.
- **테스트 전용 컨트롤러 제외는 이름 규칙이 아니라 산출물 위치로** 한다(`api` 모듈 main
  클래스 파일이 있는가). 규칙은 다음 사람이 어기고 위치는 빌드가 정한다.
- **유일한 경로 제외**인 서블릿 오류 디스패치도 손으로 적지 않고 `server.error.path` 에서
  읽으며, 계약이 그 경로를 API 로 선언하지 않았음을 별도 케이스가 확인한다(선언되는 날
  제외 자체를 다시 판단하게 된다).

**음성 대조 (worktree).**

| 변이 | 결과 |
|---|---|
| 계약이 보호로 선언한 경로를 구현하되 인증 목록에 안 넣는다(**B-6 형태**) | **빨강** — *계약이 보호로 선언했는데 인증이 걸리지 않은 경로: [/workspaces]* |
| 계약에 아예 없는 경로를 서비스한다 | **빨강** — *계약이 선언하지 않은 경로를 서비스하고 있다* |

**첫 행이 종전 장치에서 초록이던 자리다**(리뷰 B-6). 리더 판정 ⑥대로 privacy-gate 항목 5의
「좋다」와 상충하지 않는다 — 그쪽은 단언 ①(공개 경로를 잠그지 않았는가)을 본 것이고,
닫은 것은 단언 ②의 **대상 범위**다. ①은 그대로 살아 있다.

---

## 4. ⑭ 계약 파일을 Gradle 선언 입력으로 (contract-keeper §5)

`backend-kotlin/build.gradle.kts` — `apiContractFile` 을 `tasks.withType<Test>` 전체의
`inputs.file` 로 걸었다. 경로 민감도는 `NONE`(절대 경로가 지문에 들어가면 다른 기계의 빌드
캐시가 재사용되지 않아 캐시를 끄는 것과 같아진다).

**api 만이 아니라 전체 테스트 태스크에 건 이유는 도달 범위다** — 다음 모듈이 계약을 읽기
시작할 때 이 선언을 옮겨 적어야 한다면, 옮겨 적지 않은 채 같은 결함이 되살아난다.

**4조건 재현 (일회용 worktree).**

| 조건 | 처방 전 | 처방 후 |
|---|---|---|
| 같은 명령 1차 | 실행 | 실행 |
| 같은 명령 2차(변경 없음) | UP-TO-DATE | UP-TO-DATE |
| **계약 파일만 바꾸고 같은 명령** | **UP-TO-DATE (실행 0)** | **실행** |
| `cleanTest` 후 **처음 보는** 계약 내용으로 재실행 | (해당 없음) | **실행** (FROM-CACHE 아님) |
| 계약을 원상 복구하고 재실행 | — | FROM-CACHE — 같은 지문의 결과라 정상 |
| 양성 대조: 계약 `/auth/login` `'401'`→`'403'` | — | **FAILED** (`AuthContractTest.kt:212`) |

**범위 한계 (contract-keeper 의 것을 이어받는다):** 잰 것은 **로컬 증분 실행과 로컬 빌드
캐시**다. CI 원격 캐시의 지문 재사용은 관측하지 않았다.

---

## 5. ⑧⑨⑩⑪ 계약 대조 확장

### 5-1. ⑧ C-1 — 상태 코드를 응답과 계약 양쪽에

`AuthContractTest.assertDeclaredStatus` · `AuthEndpointReachTest.assertDeclaredStatus`.
적용 자리: S-2 · S-2b · S-3 · S-4 · S-6 · S-9 · S-11 · L-2 · L-4 · M-2 · M-3.

**음성 대조:**

| 변이 | 처방 전(리뷰 실측) | 처방 후 |
|---|---|---|
| 계약 `/auth/signup` `'422'` → `'400'` | **빨강 0** | **빨강 6** (S-3·S-4·S-6·S-9·S-11·S-9b) |
| 계약 `/auth/me` `'401'` → `'403'` | **빨강 0** | **빨강 2** (M-2·M-3) |

### 5-2. ⑨ C-2 — X-D2 5건

S-3·S-9·L-4 에 `assertGlobalHeaders`, M-2 에 `assertPrivateHeaders` 를 붙였다.
M-2 가 핵심이다 — 컨테이너·인터셉터가 만드는 401 이 계약 `x-failure-mode-shift` 가
*"실행해 봐야만 잡힌다"* 고 적은 자리다.

### 5-3. ⑩ C-6 — 헤더 `const` 강제자를 전 경로로

`ContractSpec` 에 **P-3b**(`headerComponentsByName` — 응답 선언의 `$ref` 에서 헤더 이름 →
컴포넌트를 유도)와 **P-4b**(`globalHeaderValues`)를 더했다. 손으로 적던 이름→컴포넌트 표
두 벌(`AuthContractTest`·`AuthEndpointReachTest`)이 사라졌다.

값 리터럴을 쓰던 다섯 테스트를 전부 계약 읽기로 바꿨다 — `PrivateResponseHeadersContractTest` ·
`PrivateResponseHeadersReachTest` · `ErrorContractTest` · `FrameworkErrorContractTest` ·
`HealthContractTest`. 계약 안 두 절(전역 절 ↔ 컴포넌트 `const`)의 일치도 별도 케이스로 잰다.

**음성 대조** — `components/headers/CacheControlNoStore.schema.const` 변이:

| | 빨강 |
|---|---|
| 처방 전(리뷰 실측 N-3) | **3** (auth 3곳) |
| 처방 후 | **27** (5개 테스트 클래스 전역 · 원시 소켓 7종 포함) |

### 5-4. ⑪ T-1 — F3 매칭과 도달 지표

`RequestFieldConstraintLayerTest` —
⑴ 계약의 snake_case 를 Kotlin camelCase 로 함께 조회한다(변환은 이 한 곳뿐).
⑵ 생성자 파라미터는 **이름으로 거른다**(이름을 못 읽는 산출물이면 종전 동작 유지 — 놓치는
쪽보다 시끄러운 쪽).
⑶ 도달은 「클래스를 찾았는가」가 아니라 **「프로퍼티를 찾았는가」**로 센다.
⑷ 클래스는 있는데 프로퍼티가 없으면 **실패**로 드러낸다.

**음성 대조:** 계약에 `SignupRequest.pass_phrase` 항목을 추가 →
**빨강** *"계약 필드와 프로퍼티가 맞지 않는다 — SignupRequest.pass_phrase"*.
종전 코드였다면 클래스가 있으므로 `covered` 가 올라가 초록이었다.

> **관찰(고치지 않음).** getter 매칭이 `ignoreCase = true` 라 `pass_word` 같은 이름은
> `getPassword` 에 우연히 붙는다. 종전부터 있던 성질이고 현재 계약 필드에서는 오탐·누락을
> 만들지 않는다. 좁히면 Kotlin 의 `is`-프로퍼티 게터 같은 예외를 함께 봐야 하므로,
> **Phase 4 의 첫 snake_case 필드 커밋에서 실측과 함께 판정**하기를 권고한다.

### 5-5. ⑫ T-7 — 산출물 정정

`03_kotlin-implementer_auth.md` §6-2 의 「목록 비우기 → exit 1」 행을 **정정 표기와 함께
재기술**했다(지우지 않았다). 그 변이는 과잉 보호를 만들 뿐 미보호 방향을 겨누지 않는다.
같은 표에 §3 의 두 변이(실제 사고 형태)를 넣었다.

---

## 6. ④ codex C4 — Jackson scalar coercion (0관점 검증분 실측)

**먼저 실측했다. 사실이었고, 한 건은 codex 가 적은 것보다 나빴다.**

| 요청(요지) | 수정 전 | 수정 후 |
|---|---|---|
| `password` 자리에 정수 | **201 — 계정이 만들어졌다** | 422 배열 |
| `email` 자리에 불리언 | 422 **문자열** detail(형식 오류로 둔갑) | 422 배열 |
| `email` 자리에 정수 | 422 문자열 detail | 422 배열 |
| `password` 자리에 불리언 | 422 문자열 detail(길이 미달로 둔갑) | 422 배열 |

수정 후 항목 키 집합은 계약 `ValidationErrorItem.required` 와 정확히 같고,
`loc` 이 어느 필드인지 가리킨다.

**처방:**
- `api/src/main/.../config/JsonCoercionConfig.kt` — `JsonMapperBuilderCustomizer` 로
  `LogicalType.Textual` 대상의 `Integer`·`Float`·`Boolean` 강제를 `Fail` 로. DTO 애너테이션이
  아니라 매퍼 설정인 이유는 도달 범위다.
- `GlobalExceptionHandler.bodyReadItem` — 본문 읽기 실패를 **타입 불일치 / 파싱 실패** 두
  갈래로 가른다. 타입 불일치는 **경로(프로퍼티 이름)와 목표 타입만** 읽는다.
  **예외 메시지를 쓰지 않는다** — Jackson 메시지에는 거절된 값이 실린다.
- 회귀는 실물 스택(`AuthEndpointReachTest` S-9b). 슬라이스에서 재면 `@WebMvcTest` 가 평범한
  `@Configuration` 을 넣지 않으므로 **테스트가 직접 들여온 배선**을 재게 된다. S-9b 는
  거절된 값이 응답 바이트에 반향되지 않는 것도 함께 본다.

**범위 한계:** 반대 방향(숫자·불리언 필드로 들어오는 문자열)은 **현재 요청 DTO 에 비문자열
필드가 하나도 없어 잴 대상이 없다.** Phase 4 의 첫 비문자열 필드 커밋에서 같은 판정을 한다.

---

## 7. ⑬ H-1 — 탐지기 양성 경로 (Python)

`tests/test_parity_ci_gate.py::test_동적_조회_탐지가_실제로_잡는다`.
새 기제를 더하지 않았다 — 기존 `_root_helper_calls` 에 `getattr` 와 함수 안 `import` 를 담은
대역 모듈을 먹여 목록이 비지 않는지, 본류 함수 밖까지 번지지 않는지를 잰다.

**음성 대조:** 탐지기의 `getattr` 분기를 제거 → **1 failed, 36 passed**.
빨개진 것은 새 케이스 하나뿐이고 기존 「비어 있음」 단언은 그대로 초록이었다 —
그 단언이 공허했다는 H-1 의 주장이 실행으로 확인된다. 복원 sha256 일치 True.

---

## 8. L-1 — security 로그 레벨

`backend-kotlin/api/src/main/resources/application.yml` 의 `logging.level` 에
`org.springframework.security` 고정. 현재 메시지는 상수라 값 유출이 없고, 고정의 목적은
라이브러리 판올림이 기본값을 따라 내려오는 경로를 끊는 것이다.

---

## 9. 검사 결과

| 게이트 | 명령 | 결과 |
|---|---|---|
| Kotlin | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` | **exit 0** · 81 tasks 전부 executed(캐시 초록 아님) |
| 테스트 | 모듈별 | core 357 · application 43 · infrastructure 99 · api 122 · worker 3 = **624**, 실패 0 · skipped 0 |
| 모듈 경계 | `./gradlew moduleBoundaryCheck` | exit 0 — api·worker 양쪽 통과 |
| Python lint | `uv run ruff check .` / `ruff format --check .` | exit 0 / exit 0 |
| Python 타입 | `uv run mypy . .claude` | exit 0 — 137 files |
| 하네스 테스트 | `uv run pytest tests/test_parity_ci_gate.py tests/test_harness_scope_reach.py` | exit 0 — **74 passed** |

**개인정보 스캔** (`scan_privacy_invariants.py --changed --base bf08edd`): `REAL_EXIT=1`.
BLOCK 1건은 **이번 배치가 만든 것이 아니다** — `RequestFieldConstraintLayerTest` 의
`PASSWORD = "SignupRequest.password"` 이고 값이 난수가 아니라 계약 필드 경로 문자열이라
privacy-gate 가 이미 오탐으로 판정한 자리다(그쪽 항목 3). 이번에 더한 상수
(`Argon2PasswordHasher.DUMMY_PHC_SOURCE`)는 규칙에 걸리지 않았다.

**직전 게이트 대비 테스트 수:** privacy-gate 가 잰 610 → **624** (+14).

---

## 10. 남기는 것

| # | 내용 | 수신 |
|---|---|---|
| 1 | **세마포어 상한 초과의 응답 코드** — 500(현행) ↔ 503+전용 문구(계약 개정 필요) | `contract-keeper` 판정 → 리더 |
| 2 | **반대 방향 coercion**(숫자 필드로 오는 문자열) — 대상 없음, Phase 4 첫 비문자열 필드 커밋 | 다음 단위 |
| 3 | **F3 getter 매칭의 `ignoreCase`** — 현재 오탐·누락 0, Phase 4 첫 snake_case 필드에서 판정 | 다음 단위 |
| 4 | 슬라이스(`@WebMvcTest`)는 `JsonCoercionConfig` 를 들이지 않는다 — 의도. 슬라이스에서 타입 불일치를 재는 케이스를 새로 만들면 **실물이 아닌 배선**을 재게 된다 | 다음 사람 |
| 5 | 리더 판정 ③(X6 표기)·⑤(하네스 동반 편집 처분)은 **코드 작업 없음** — 원장 레인 | 리더 |

**이 배치가 하지 않은 것:** 계약 파일 수정 0건. `app/` 수정 0건. `00_progress.md` 접촉 0건.
