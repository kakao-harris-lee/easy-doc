# Phase 3 작업 단위 `auth` — 구현 산출물 (kotlin-implementer)

작성 2026-08-19 / 범위 `e91ecdd..9341e69` 중 `7a75f29..9341e69` 8커밋
정본: `contracts/easy-doc-v1.yaml` · `03_contract-keeper_auth-test-spec.md`(05862fa) ·
`migration-safety-gate` I-8·I-9 · 원장 「Phase 3 착수 판정」 §4

> **값을 전사하지 않는다.** 상한·문구·헤더 값·클레임 값은 이 문서에 적지 않고 **파일과
> 심벌로 지목**한다. 기대값은 계약 파일에서 읽는다(§4).

---

## 1. 무엇을 어디에 만들었나

| 모듈 | 파일 | 담당 |
|---|---|---|
| `core` | `core/.../user/User.kt` | `User`(공개 표현) · `StoredUser`(해시 동반) |
| `core` | `core/.../user/PasswordHash.kt` | PHC 래퍼 — 마스킹 `toString`, 상수 시간 비교 |
| `core` | `core/.../workspace/Workspace.kt` | 기본 작업 공간 이름 상수 |
| `application` | `application/.../auth/AuthPorts.kt` | 포트 6종(저장소 2 · 해시 · 토큰 · 트랜잭션) |
| `application` | `application/.../auth/CredentialRules.kt` | F3 두 필드의 **서비스 층** 판정 |
| `application` | `application/.../auth/AuthService.kt` | 가입 · 로그인 · 내 정보 |
| `infrastructure` | `infrastructure/.../auth/Argon2Phc.kt` | PHC 파서(재해시 판정 전용) |
| `infrastructure` | `infrastructure/.../auth/Argon2PasswordHasher.kt` | 해시 · 검증 · 재해시 판정 · 동시 실행 상한 |
| `infrastructure` | `infrastructure/.../auth/JwtAccessTokens.kt` | HS256 발급 · 검증(skew 0) |
| `infrastructure` | `infrastructure/.../auth/JdbcUserRepository.kt` | `users` 접근 |
| `infrastructure` | `infrastructure/.../auth/JdbcWorkspaceRepository.kt` | 기본 작업 공간 생성 |
| `infrastructure` | `infrastructure/.../auth/AuthConfiguration.kt` | `easydoc.auth.*` 바인딩 + 빈 조립 |
| `infrastructure` | `infrastructure/.../db/SpringTransactionRunner.kt` | `TransactionRunner` 포트 구현 |
| `api` | `api/.../auth/AuthDtos.kt` | 요청·응답 DTO (snake_case 명시) |
| `api` | `api/.../auth/AuthController.kt` | 세 엔드포인트 |
| `api` | `api/.../auth/AuthenticationInterceptor.kt` | 인증 + `AuthenticatedUser` 리졸버 |
| `api` | `api/.../auth/AuthenticatedEndpoints.kt` | 보호 경로 목록(계약이 판정) |

**스키마 변경 없음.** `users`·`workspaces` 는 `V1__python_schema_baseline.sql` 그대로이며
V3 마이그레이션을 만들지 않았다 — 필요한 컬럼이 전부 있다.

---

## 2. 계약 조항 ↔ 구현 ↔ 테스트 대응표

값은 적지 않는다. 「계약」 열은 키 경로, 나머지는 파일·심벌이다.

| 계약 | 구현 | 테스트 |
|---|---|---|
| `paths./auth/signup.post` 성공/409/422/503 | `AuthController.signup` | `AuthContractTest` S-1·S-2·S-3·S-9 · `AuthEndpointReachTest` S-1 · `AuthUnavailableContractTest` S-13 |
| `paths./auth/login.post` 성공/401/422/503 | `AuthController.login` | `AuthContractTest` L-2~L-5 · `AuthEndpointReachTest` L-1·L-1b · `AuthUnavailableContractTest` L-6 |
| `paths./auth/me.get` 성공/401/503 | `AuthController.me` | `AuthEndpointReachTest` M-1~M-7·M-6b · `AuthUnavailableContractTest` M-8 |
| `x-request-field-constraints.fields[?email]` | `CredentialRules.requireValidEmail`·`normalizeEmail` | `AuthContractTest` S-3·S-4·S-5·S-2b |
| `x-request-field-constraints.fields[?password]` | `CredentialRules.requireValidPassword` | `AuthContractTest` S-6·S-7·S-8 |
| `ValidationFailed`(문자열/배열 경계) | `GlobalExceptionHandler`(기존) + 위 두 규칙 | `AuthContractTest` S-9·S-11·L-4 |
| `ValidationErrorItem`(키 정확히 3) | 기존 핸들러 | `AuthContractTest` S-9·L-4(`assertValidationItemKeys`) |
| `ErrorResponse`(최상위 키 하나) | 기존 핸들러 + 인터셉터가 `sendError` 회피 | `AuthEndpointReachTest` M-2 |
| `components/responses/Unauthorized` + `WWWAuthenticateBearer` | `InvalidCredentialsException` 매핑(기존) | `AuthContractTest` L-2·L-3 · `AuthEndpointReachTest` M-2·M-3 |
| `components/responses/Conflict` | `JdbcUserRepository.create` | `AuthContractTest` S-2 · `JdbcUserRepositoryTest` |
| `components/responses/ServiceUnavailable` | `JwtAccessTokens.signingKey`·`ensureConfigured` | `AuthUnavailableContractTest` 전건 |
| `x-private-response-headers.applies_to`(auth 3) | `AuthController.private(...)` | `AuthContractTest` S-1 · `AuthEndpointReachTest` L-1·M-1 · `RequestFieldConstraintLayerTest` P-5 |
| `x-global-response-headers`(오류에도 부착) | 기존 필터+밸브 | `AuthContractTest` `assertGlobalHeaders` |
| `UserResponse`·`TokenResponse` 키 집합·`token_type` | `AuthDtos` | `AuthContractTest` S-1 · `AuthEndpointReachTest` L-1 |
| `x-auth.claims`·`algorithm` | `JwtAccessTokens.issue` | `AuthEndpointReachTest` L-1b · `JwtAccessTokensTest` |
| `x-auth.required_claims`·`claim_typ` | `JwtAccessTokens.verify` | `AuthEndpointReachTest` M-4·M-5 · `JwtAccessTokensTest` |
| `x-auth.clock_skew_seconds` | 같음 | `AuthEndpointReachTest` M-6·M-6b·M-7 · `JwtAccessTokensTest` |
| `x-auth.min_secret_bytes` | `JwtAccessTokens.signingKey` | `AuthUnavailableContractTest` |
| `x-auth.rehash_policy` | `Argon2PasswordHasher.needsRehash` + `AuthService.rehashIfOutdated` | `Argon2PasswordHasherTest` · `AuthServiceTest` |
| `x-input-limits` ↔ `fields[].limit` 이중 선언 | — (계약 내부) | `RequestFieldConstraintLayerTest` P-7 |
| 오퍼레이션별 `security` | `AuthenticatedEndpoints` + `WebMvcConfig.addInterceptors` | `AuthenticationCoverageContractTest` |
| 가입 트랜잭션 원자성 | `AuthService.signup` + `SpringTransactionRunner` | `AuthServiceTest` · `JdbcUserRepositoryTest` · `AuthEndpointReachTest` S-1 |

---

## 3. Argon2 파라미터 정책과 재해시 규칙

- **정책은 설정이다** — `AuthProperties.argon2`(접두사 `easydoc.auth.argon2`). 기본값은
  계약 `x-auth.password_hash` 조합이며 코드에 상수로 박지 않는다.
- **`version` 은 설정으로 열지 않는다.** 인코더가 Argon2 v1.3 으로만 해시하므로 다른 값을
  적으면 **모든 로그인이 매번 재해시 대상**이 되고 그 상태는 조용하다. 조립 시점에 끊는다
  (`Argon2PasswordHasher.init`).
- **검증 파라미터는 저장된 PHC 에서 읽는다** — 인코더가 그렇게 한다. 정책을 올린 뒤에도
  옛 해시로 로그인이 된다(`Argon2PasswordHasherTest` 「파라미터를 올려도 옛 해시로 로그인된다」).
- **재해시 판정 = 전체 파라미터 동등성.** 변형·버전·메모리·반복·병렬도·salt 길이·hash 길이
  일곱 중 **하나라도** 다르면 대상이다(`Argon2Policy.matches`). `upgradeEncoding()` 은
  쓰지 않는다 — 「미만」만 보므로 낮춘 방향과 `parallelism`·길이 변경을 놓친다.
- **재해시 시점** — 로그인 **성공 시에만**. 실패한 로그인에서 하면 오프라인 공격자에게 계산
  자원을 태워 준다. **실패해도 로그인을 막지 않는다**(best-effort). 셋 다 `AuthServiceTest` 가 잰다.
- **동시 실행 상한** — `easydoc.auth.max-concurrent-hashes`. 1건당 `memoryKib` 를 계산이
  끝날 때까지 붙들고 있어 상한이 없으면 인증 엔드포인트가 서비스 거부 벡터가 된다(I-8 5).
- **해시 계산은 트랜잭션 밖.** `AuthServiceTest` 「해시는 트랜잭션 밖에서 계산한다」가 깊이 0을 잰다.

---

## 4. JWT 검증 규칙

`JwtAccessTokens.verify` 의 순서와 이유:

1. **알고리즘 고정** — 서명 검증 **전에** 헤더 `alg` 가 HS256 인지 본다. 검증기 자신도
   확인하지만, 검증기를 바꾸는 날 이 조건이 함께 사라지지 않게 코드에 자리를 남긴다.
2. 서명.
3. **만료 — 허용 오차 0.** Nimbus `DefaultJWTClaimsVerifier`·Spring `JwtTimestampValidator`
   기본 60초를 피하려고 **그 검증기들을 쓰지 않고** 직접 판정한다. 조건은 `now < exp` 이며
   `exp` 부재도 거부다(없으면 영구 자격증명).
4. `typ` 일치. 5. `sub` 가 UUID.

실패는 원인을 구분하지 않고 같은 예외·같은 문구다. **원인 체인을 잇지 않는다** — 스택
트레이스에 토큰 조각이 실릴 수 있다. 서명 키 문제만 `ConfigurationException`(→503)이며,
401 로 감추면 배포 사고가 "사용자가 토큰을 잘못 냈다"로 둔갑한다.

---

## 5. 발견한 잠재 결함 — 설정 바인딩 (기존 코드, 커밋 `7a75f29`)

> 요청 범위 밖에서 드러났고 **내 코드가 아니라 기존 코드**의 결함이다. 고치지 않으면
> `easydoc.auth.jwt-secret` 을 넣는 순간 기동이 깨져 auth 작업 자체가 성립하지 않는다.

**기제.** Kotlin 은 주 생성자의 모든 파라미터에 기본값이 있으면 **public 무인자 생성자를
하나 더** 만든다(`javap` 실측: `EasyDocProperties` 에 생성자 3개, 그중 무인자 1). 그러면
non-synthetic 생성자가 둘이라 Spring 이 바인딩 생성자를 **추론하지 못한다**(후보가 정확히
하나일 때만 추론). 남은 경로인 Kotlin 주 생성자 조회는 `kotlin-reflect` 를 요구하는데
실행 클래스패스에 없었다(락파일 실측 0건). 결과는 value object 가 아니라 **JavaBean
바인딩**이고, setter 가 없으므로 `No setter found for property: jwt-secret` 로 끊긴다.

**왜 지금까지 안 보였나.** `JavaBeanBinder` 는 바인딩한 값이 **기존 값과 같으면 예외를
던지지 않는다.** `easydoc.cors-origins`·`easydoc.auth.jwt-expire-minutes` 가 기본값과
같은 값으로 적혀 있어 오래 통과했다. **기본값과 다른 값을 처음 넣은 것이 이번 작업이다.**

**재현.** `easydoc.auth.jwt-secret` 에 아무 값이나 넣고 `@SpringBootTest` 를 띄운다 →
`BindException: Failed to bind properties under 'easydoc.auth'` → `Caused by:
IllegalStateException: No setter found for property: jwt-secret`.

**처방 후보와 판정.**

| 후보 | 판정 |
|---|---|
| `@ConstructorBinding` 부착 | **불가(실측).** Kotlin 이 그 애너테이션을 **무인자 생성자에도 복사**해서 Spring 이 *"declares @ConstructorBinding on a no-args constructor"* 로 거절한다 |
| 기본값 하나 제거 | 클래스마다 손으로 지켜야 하고, 다음 설정 클래스에서 그대로 재발한다 |
| **`kotlin-reflect` 를 실행 의존으로** | **채택.** 결함이 클래스 하나가 아니라 「Kotlin data class + 전 파라미터 기본값」이라는 **형태 전체**의 문제이므로, 형태를 고치는 처방을 골랐다 |

`api`·`worker` 에 `runtimeOnly`, `infrastructure` 에 `testRuntimeOnly` 로 넣었다. 버전은
Boot BOM 이 import 하는 kotlin-bom 이 관리한다(컴파일러 2.3.21 과 갈리지 않는다).

**회귀 장치.** `ConfigurationPropertiesBindingTest` 가 세 설정 클래스에 **기본값과 다른**
값을 실제로 바인딩한다. 기본값을 넣으면 결함이 있어도 초록이므로 **다르게 고른 것이 요점**이고,
중첩 value object(`easydoc.auth.argon2.*`)도 함께 건다.

---

## 6. 음성 대조 결과

**일회용 `git worktree`(`/tmp/edwt`)에서만 했고 복원은 `git checkout` + sha256 대조다
(규칙 5, `cp` 미사용).** 복원 후 계약 파일 해시 일치 확인:
`8f7d4efa31aa3e48b5ff829599c4bb84232b62ef5e5bf0179f099fc4f0e4be92` (worktree ≡ 본 저장소).
worktree 는 제거했고 `git worktree list` 는 본 저장소 하나다.

### 6-1. 계약 값 변경 (명세 §4-4)

| # | 바꾼 노드 | exit | 깨진 것 | 과잉 결합 |
|---|---|---|---|---|
| N-1 | `fields[?password].limit` **상향** | 1 | S-6 | 없음 |
| N-1b | 같은 노드 **하향** | 1 | S-7 · S-8 | 없음(S-8 은 같은 하한을 쓰는 원시 축 케이스라 정상) |
| N-2 | `fields[?email].detail` 한 글자 | 1 | S-3 · S-4 | 없음(S-4 가 S-3 과 문구 동일성을 잰다) |
| N-3 | `CacheControlNoStore.schema.const` | 1 | S-1 · L-1 · M-1 | 없음 |
| N-4 | `x-auth.clock_skew_seconds` 0→120 | 1 | **M-6b** | 없음 |
| N-5 | signup 성공 상태 키 `'201'`→`'202'` | 1 | S-1(C-M·C-R) · S-5 · S-7 · S-8 | 성공 상태를 기대값으로 쓰는 통과 쪽 케이스 전부 — 의도된 결속 |
| N-6 | `TokenResponse.token_type.const` | 1 | L-1 | 없음 |
| N-7 | `x-input-limits.min_password_length` 만 | 1 | P-7 | 없음 |
| N-8 | `applies_to` 에서 auth 한 줄 삭제 | 1 | P-5 | 없음 |

**N-3·N-4 는 첫 실행에서 통과했다(= 결함).** 그 사실과 조치를 커밋 `9341e69` 에 남겼다.

- **N-4 초판이 통과한 이유**: M-6 이 `skew + 1` 만 재서 오차를 120으로 올려도 여전히 만료라
  401 이 그대로 나왔다. **오차 경계 안쪽**을 재는 **M-6b** 를 신설했다 — `exp` 를 정확히
  `skew` 만큼 지난 토큰은 오차가 0이면 거절, 0보다 크면 수용이어야 하고 기대값을 계약에서
  유도한다. 이제 오차를 바꾸면 케이스가 뒤집힌다.
- **N-3 초판이 실측 계층에서 통과한 이유**: `AuthEndpointReachTest` 가 헤더 값을
  `x-global-response-headers.headers` 에서만 읽어 컴포넌트 `const` 변경에 반응하지 않았다.
  P-3 대로 컴포넌트 `const` 에서 읽고, 계약 안 두 절이 갈리지 않는지도 함께 단언한다.

### 6-2. 구현 값 변경 (착수 지침 6)

| 바꾼 것 | exit | 깨진 것 |
|---|---|---|
| `JwtAccessTokens` 만료 판정에 **60초 허용 오차 주입** | 1 | `JwtAccessTokensTest` 「exp 를 1초 지나면 거부한다」·「exp 와 정확히 같은 시각도 거부한다」 |
| `needsRehash` 를 **`upgradeEncoding()` 의 「미만」 비교로 교체** | 1 | 「파라미터를 **낮춘** 정책도 재해시 대상」·「parallelism · salt 길이 · hash 길이만 달라도 재해시 대상」 |
| `SignupRequest.email` 에 **`@Size` 부착** | 1 | `RequestFieldConstraintLayerTest` 「다섯 필드에 길이·형식 Bean Validation 애너테이션이 없다」 |
| `AuthenticatedEndpoints` 목록 **비우기** | 1 | `AuthenticationCoverageContractTest` 「보호 경로 목록이 계약의 security 선언과 정확히 같다」 |

> **F3 부수 관찰 — 지금은 두 층이 막고 있다.** `jakarta.validation` 이 `api` 클래스패스에
> **아예 없어서**(`spring-boot-starter-validation` 미추가) 금지 애너테이션이 컴파일조차
> 되지 않는다. 그래서 위 F3 음성 대조는 **같은 이름의 애너테이션을 직접 선언해** 수행했다.
> 이 1차 방벽은 `limit`/`offset` 이 들어오는 Phase 4 에 사라지므로, 그때부터는
> `RequestFieldConstraintLayerTest` 가 유일한 강제자가 된다.

---

## 7. 명세와 다르게 한 지점 (사유 병기)

| 지점 | 명세 | 실제 | 사유 |
|---|---|---|---|
| **S-1·L-1·M-1 의 계층** | C-M | S-1 은 **양쪽**, L-1·L-1b·M-1 은 **C-R** | ⑴ `expires_in`·`token_type`·클레임 집합은 **실물 설정**에서만 잴 수 있다. 슬라이스 배선이 값을 정해 놓고 그 값을 단언하면 아무것도 검증하지 않는다. ⑵ S-1 은 응답 모양(C-M)과 기본 작업 공간 행 생성(C-R)을 **다른 자리에서** 재야 한다. **C-M 을 지운 것이 아니라 C-R 을 더했다** |
| **S-9·L-4 의 `loc`** | 「거기까지만 단언한다」(O-2) | 그대로 따랐다 | 필드 누락이 Jackson 생성자 실패로 떨어져 `loc` 에 필드 이름이 없다. 조항은 「422·배열·키 정확히 3·입력값 미반향」이고 그것만 잰다 |
| **`spring-boot-starter-validation` 미추가** | (지침 8) | 추가하지 않음 | O-6 대로 auth 에는 대상이 없다. 필드 누락은 Bean Validation 없이도 422 배열이 된다. 마감은 `limit`/`offset` 단위 |

---

## 8. 검사 결과

| 검사 | 명령 | 결과 |
|---|---|---|
| Kotlin 전체 | `./gradlew ktlintCheck detekt build --continue --rerun-tasks` (파이프 없이 단독) | **exit 0** — 캐시 무효화 후 |
| 모듈 경계 | `check` 에 걸린 `moduleBoundaryCheck` | `[api]`·`[worker]` 둘 다 통과 |
| 테스트 건수 | 모듈별 XML 집계 | core 357 · application 41 · infrastructure 95 · api 117 · worker 3 = **613, 실패 0** |
| Python ruff | `uv run ruff check .` | exit 0 |
| Python mypy | `uv run mypy . .claude` | exit 0 (137 files) |
| Python pytest | `uv run pytest` | **1242 passed · 68 skipped · 5 xfailed** |
| Python 무변경 | `git diff e91ecdd..HEAD -- app tests migrations scripts` | 내 커밋 0건(`tests/test_parity_ci_gate.py` 변경은 게이트 19 선행 커밋 소유) |

**골든셋(`uv run pytest tests/golden`)은 이 단위의 대상이 아니다** — 프롬프트·스타일 규칙·
LLM 설정을 건드리지 않았다. `pytest` 전체에 포함되어 함께 돌았다.

---

## 9. 갈림·미결

| # | 내용 | 처분 |
|---|---|---|
| **X13 종속** | S-8(X-F12)의 기대값이 `password.measured_on` 판정에 걸려 있다 | **현행 조항대로 구현**. `AuthContractTest` S-8 이 `constraint.measuresRaw` 를 먼저 단언해, 계약이 바뀌면 **그 자리에서 실패 메시지로 알린다**. 뒤집히면 계약·test-plan·S-8·`CredentialRules` 를 같은 단위로 고친다 |
| **이메일을 ASCII 로 좁혔다** | 계약은 형식을 문자로 규정하지 않는다 | Kotlin `lowercase()` 와 PostgreSQL `lower()` 가 비ASCII 에서 갈리면 `ck_users_email_lowercase` 위반 → 500 이 된다. 갈림 자체를 없애는 쪽을 골랐고 사유를 `CredentialRules` KDoc 에 적었다. **넓혀야 하면 정규화 방식과 DB 제약을 함께 바꾸는 사안**이다 |
| **로그인 타이밍** | 없는 이메일은 해시 검증을 건너뛴다 | 응답 균일성은 계약대로 지킨다. 더미 검증으로 시간을 맞추면 요청당 64MiB 를 태워 **DoS 증폭**이 된다. 개선 후보로만 남긴다(적용 안 함) |
| **`@WebMvcTest` 5개 수정** | — | 슬라이스가 인터셉터를 자동 포함하면서 `AuthService` 를 요구하게 됐다. `AuthSliceBeans` 를 `@Import` 한다. 빈이 없어도 되게(=`getIfAvailable`) 만들지 **않은 것이 의도**다 — 그러면 인증 없이 도는 컨텍스트가 통과한다 |
| **`AuthenticatedEndpoints` 는 열거식** | — | 열거는 지켜지지 않는 형태이므로 **계약이 판정**하게 했다(`AuthenticationCoverageContractTest`, 양방향). 다음 단위는 자기 경로를 **구현하는 그 커밋**에 더한다 |
| **`easydoc.auth.*` 소유자 이동** | — | `EasyDocProperties.auth` → `infrastructure/AuthProperties`. YAML 키·환경변수 이름 불변. `LlmProperties` 전례와 같은 사유 |
| **`Argon2Properties.version` 미개방** | — | 라이브러리가 v1.3 으로만 해시한다. 여는 순간 「모든 로그인이 매번 재해시」라는 조용한 상태가 가능해진다 |

---

## 10. 다음 레인에 넘기는 것

- **`parity-verifier`**: auth 범위에서 반드시 같아야 하는 것은 명세 §8 그대로다. 이 단위가
  만든 **사용자 문구**는 `CredentialRules`(2)·`AuthService`(1)·`JwtAccessTokens`(2)·
  `AuthenticationInterceptor`(1)·`JdbcUserRepository`(1)에 상수로 있다.
- **`privacy-gate`**: I-8·I-9 의 검증 항목별 대응은 §3·§4, 음성 대조 실측은 §6-2.
  **I-8 5(요청 스레드 잠금)** 는 세마포어로만 막았고 **부하 실측은 하지 않았다**.
- **리더**: §5 의 설정 바인딩 결함은 **auth 범위 밖 기존 코드**이며 세 설정 클래스 전부에
  영향한다. §9 의 「이메일 ASCII 한정」은 계약이 규정하지 않은 자리를 구현이 좁힌 것이라
  판정이 필요하면 올린다.
