# 리뷰 게이트 20 · 1단계 — Claude 독립 리뷰 (1회차)

**작성:** migration-reviewer / **일자:** 2026-08-19 / **회차:** 1차(독립) — codex 산출물 없음이 정상
**대상:** `e91ecdd..fc21750` 중 실제 검토 범위는 **`e91ecdd~1..fc21750` 16커밋**
 — ⓐ 하네스 3커밋 `e91ecdd`·`e600861`·`e7f9bdb`(리뷰 미수령, 원장 리더 결정 9)
 — ⓑ Phase 3 auth 구현 12커밋 `7a75f29..fc21750`
**정본:** `contracts/easy-doc-v1.yaml` · `03_contract-keeper_auth-test-spec.md`(05862fa) ·
`migration-safety-gate` I-8·I-9 · `kotlin-spring-conventions` · 원장 「게이트 19 후속」·「Phase 3 착수 판정」

> **범위 주의.** 리더가 지정한 범위 `e91ecdd..fc21750`은 git 표기상 `e91ecdd`를 **제외**하는데,
> 같은 지시가 `e91ecdd`를 하네스 3커밋의 하나로 지목한다. 지시의 의도(3커밋 전부 리뷰)를 따라
> **`e91ecdd~1..fc21750`으로 읽었다.** 표기 정정을 리더에게 올린다.

---

## 1. 리뷰 범위와 참조한 절

| 축 | 참조 |
|---|---|
| 계약 준수 | `contracts/easy-doc-v1.yaml` — `/auth/*` `:760-858` · `x-auth` `:263-306` · `x-request-field-constraints` `:332-424` · `x-global-response-headers` `:519-...` · `x-private-response-headers` `:696-720` · `components/headers` `:1436-1453` · `responses` `:1455-1584` · `schemas` `:1585-1711` / 계획 §2.2 · §6 Contract 게이트 |
| parity 위험 | 계획 §4.5·§4.6 — **Phase 3 auth는 대상 밖**(인증·비밀번호는 명세 신규, `CLAUDE.md`). Python 무변경만 확인 |
| 보안 불변식 | `migration-safety-gate` I-8·I-9 / 계획 §2.3 / `CLAUDE.md` 보안 규칙 |
| Kotlin/Spring | 계획 §3.1·§3.2 / `kotlin-spring-conventions` |
| 테스트 적정성 | 계획 §6 / 명세 §2 케이스 표 · §4 파서 요건 · §5 계층 지목 · §6 커밋 단위 |
| 도달 범위 | `kotlin-migration` 「선언한 범위와 실제 도달을 대조한다」 (정본) |

**읽은 것**: `backend-kotlin/{core,application,infrastructure,api}` 의 auth 신규·수정 전건,
`api/build.gradle.kts`·5개 락파일, `api/src/main/resources/application.yml`,
`V1__python_schema_baseline.sql`, `tests/test_parity_ci_gate.py` 3커밋 diff, 원장 관련 절.

**독립 재현으로 확인한 것**: Python 무변경, 계약 인용 라인 전수, `jakarta.validation` 클래스패스 부재,
`kotlin-reflect` 락파일 배선, CORS 기제(필터/`addCorsMappings`), 음성 대조 재현(§6).

---

## 2. 축별 지적

심각도 척도: **차단(①사건 / ②장치)** · **수정 필요**(Phase 종료 조건 미충족) · **권고** · **판정 필요**.
「마감」은 그 게이트·코드가 **처음 실제로 쓰이는 시점**이며, 착수 차단 여부는 판정하지 않는다.

### 2-1. 계약 준수

#### C-1 [수정 필요] 422·401을 계약과 대조하지 않는 케이스가 다수 — 「상태 코드도 계약에서 읽는다」 규약이 절반만 도달
**마감: Phase 3 종료 전** / 관련: 명세 §0 상태 코드 예외 조항 · §4 P-1 · §6 Contract 게이트

명세 §0은 상태 코드를 숫자로 적는 것을 허용하면서 **조건**을 달았다 — *"테스트는 상태 코드도 계약에서
읽어 대조한다(§4 P-1·P-2)"*. 그 대조가 붙은 케이스는 다섯뿐이다.

| 대조 있음 | 대조 없음 (하드코딩 상수만) |
|---|---|
| S-2 `AuthContractTest.kt:68` · L-2 `:212` · S-13/L-6/M-8 `AuthUnavailableContractTest.kt:84` | S-3 `:91` · S-4 `:101` · S-6 `:123` · S-8 `:147`(성공 쪽은 대조) · S-9 `:155` · S-11 `:179-180` · L-4 `:237` · **M-2 `AuthEndpointReachTest.kt:116`** |

`UNPROCESSABLE_CONTENT = 422`(`:400`)·`UNAUTHORIZED = 401`(`:399`, ReachTest `:320`)는 코드에만 산다.

**실측으로 확정했다**(§6-1 A-X1·A-X2, 캐시 무효화 + api 전건 117 실행):

| 변이 | 결과 |
|---|---|
| `paths./auth/signup.post.responses` 의 `'422'` → `'400'` | **exit 0 · 빨강 0** — 아무것도 못 잡는다 |
| `paths./auth/me.get.responses` 의 `'401'` → `'403'` | **exit 0 · 빨강 0** — 아무것도 못 잡는다 |
| `paths./auth/login.post.responses` 의 `'401'` → `'403'`(양성 대조) | **빨강 — L-2** (대조가 붙은 자리는 잡는다) |

**고치는 비용은 한 줄**이다(`assertThat(ContractSpec.responseStatuses(path, method)).contains("422")`),
그리고 그 한 줄이 S-2·L-2에는 이미 있다. 규약이 같은 파일 안에서 갈렸다.

> **타 레인 대조.** `contract-keeper`가 6ece404 §1-1에서 **같은 자리를 독립으로 지적**했다
> (「422와 `/auth/me` 401은 상수만 쓰고 계약 대조가 없다」, 심각도 **낮음**). 그쪽은 코드 대조로,
> 이쪽은 **변이 실행**으로 도달했다. 심각도 판정이 갈린다 — 그쪽은 「응답 자체는 여전히 단언되므로
> 구현 회귀는 잡힌다. 새는 것은 계약 조항의 소실뿐」이라 낮음, 이쪽은 **P-1이 이 배치의 종료
> 조건(§4 파서 요건)에 들어 있고 그 요건이 절반만 도달했다**는 이유로 수정 필요다. 어느 쪽도
> 지우지 않고 병기해 리더에게 올린다.

#### C-2 [수정 필요] X-D2(오류 응답에도 전역 헤더)가 명세가 지목한 5건 중 2건만 구현
**마감: Phase 3 종료 전** / 관련: 명세 §3 X-D2 행 · 계약 `x-global-response-headers.applies_to`

명세 §3은 X-D2의 auth 배치를 **S-2·S-3·S-9·L-2·M-2**로 지목했다. 실제로 `assertGlobalHeaders`를
부르는 것은 **S-2(`AuthContractTest.kt:70`)와 L-2(`:215`)뿐**이다. S-3(`:88-93`)·S-9(`:152-158`)는
부르지 않고, **M-2(`AuthEndpointReachTest.kt:113-126`)는 상태·`WWW-Authenticate`·본문 키 집합·
`Content-Type`만 재고 사적 헤더 2종을 아예 보지 않는다.**

M-2가 특히 아프다 — 그 케이스는 **실측 계층(C-R)에서 컨테이너가 만드는 401**을 재는 자리이고,
전역 필터·밸브가 그 경로까지 닿는지는 계약 `x-failure-mode-shift`가 *"실행해 봐야만 잡힌다"*고 적은
바로 그 성질이다. 헤더를 안 보는 M-2는 그 성질의 절반을 비워 둔다.

#### C-6 [수정 필요] 헤더 값 `const`의 강제자가 **auth 3곳뿐**이다 — 전역 헤더 계약 테스트는 계약을 읽지 않는다
**마감: Phase 3 종료 전** / 근거: §6-1 A-N3 실측 · `PrivateResponseHeadersContractTest`

음성 대조 N-3(`components/headers/CacheControlNoStore.schema.const`를 `no-store` → `no-cache`)을
**api 전건 117개**로 돌린 결과 빨강은 셋뿐이다 — `AuthContractTest` S-1 · `AuthEndpointReachTest`
L-1 · M-1. 즉 이번 배치가 새로 붙인 세 자리다.

**초록으로 남은 것 중 하나가 `PrivateResponseHeadersContractTest`의
「G-D 값이 정확히 `no-store` / `nosniff` 다」이다** — 이름이 값 정확성을 재겠다고 선언하는데,
그 값을 **계약이 아니라 코드 리터럴에서** 가져온다. 계약 `x-global-response-headers`가 「Kotlin
런타임이 내보내는 **모든** 응답」을 요구하는 조항인데, 그 조항의 값 강제자는 auth 3곳으로 한정돼
있고 전역을 담당하는 테스트 쪽이 오히려 계약과 끊겨 있다.

**선언(전역)과 도달(auth 3곳)이 갈린 자리이며, 이번 배치가 그것을 드러냈다.** OQ-3 파서 규제가
새 테스트에만 적용되고 기존 테스트로는 넓혀지지 않은 결과다. `ContractSpec.headerConst`가 이미
있으므로 교체 비용은 낮다.

#### C-3 [판정 필요 — 차단 후보 ①+②] `failure_uniformity`의 **응답 시간** 축이 구현·테스트 양쪽에서 빠졌다
**마감: Phase 3 종료 전** / 근거: 계약 `x-auth.failure_uniformity` `:299-302` · `AuthService.kt:86-92`

계약 조항 전문(`:299-302`)은 네 문장이고 **마지막 문장이 타이밍이다**:

> 이메일 부재·비밀번호 불일치·토큰 만료·위조·계정 삭제를 **모두 같은 401과 같은 메시지**로 처리한다.
> 구분하면 계정 열거 공격의 단서가 된다. **사용자가 없을 때도 더미 해시로 같은 검증 비용을 치러
> 응답 시간으로도 새지 않게 한다.**

구현은 그 문장을 만족하지 않는다 — `AuthService.login`이

```kotlin
val stored = users.findByEmail(normalizeEmail(email))
    ?: throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)   // :87-88
if (!passwords.verify(password, stored.passwordHash)) { ... }           // :90
```

로 **없는 이메일이면 Argon2 검증(64MiB · 수십 ms)을 건너뛴다.** 등록 여부가 응답 시간으로 샌다.

세 겹으로 조용하다.
1. **계약이 고쳐지지 않았다.** 구현자 산출물 §9는 "더미 검증으로 시간을 맞추면 요청당 64MiB를 태워
   **DoS 증폭**이 된다. 개선 후보로만 남긴다(적용 안 함)"고 적었다. 그 판단 자체는 근거가 있다 —
   그러나 조항을 **거절하는 결정은 contract-keeper의 권한**이고, 지금은 계약에 조항이 살아 있는 채
   구현만 갈렸다.
2. **명세가 그 축을 케이스로 내리지 않았다.** 명세 §2-2 L-3과 §3 X-A2는 「상태·본문·`WWW-Authenticate`」
   세 축만 유도한다. 계약 조항이 네 축인데 파생된 케이스가 세 축이다.
3. **테스트가 세 축만 잰다.** `AuthContractTest.kt:220-230`(L-3)·`AuthServiceTest`「없는 이메일과
   틀린 비밀번호가 같은 예외·같은 문구다」 어디에도 시간 축이 없다.

**⑷ 계약 소유자도 이 조항을 다르게 읽고 있다.** 리뷰 도중 들어온 `contract-keeper` 산출물
(6ece404, `03_contract-keeper_auth-verification.md` §9 말미)이 이렇게 적는다 —

> **부하·타이밍**(로그인 응답 시간 균일성)은 재지 않았다. **계약은 응답의 동일성만 요구하고**
> 구현 레인이 타이밍 축을 개선 후보로만 남긴 것은 **그 조항 밖이다.**

**계약 본문과 어긋난다.** `:299-302`의 마지막 문장이 타이밍을 명시적으로 요구하므로, 「조항 밖」이
아니라 **조항 안인데 아무도 재지 않은** 자리다. 이 오독이 조항 → 케이스 유도 단계에서 축 하나가
사라진 기제로 보인다(명세 L-3·X-A2가 세 축만 유도한 것과 같은 방향).

**처분은 둘 중 하나여야 한다** — ⑴ 더미 검증을 구현(DoS는 세마포어·레이트리밋으로 따로 다룬다),
⑵ contract-keeper가 근거(G1~G4)와 함께 조항을 개정하고 그 사실을 원장에 등재. **지금 상태(조항 살아
있음 + 구현 갈림 + 장치 0 + 소유자 오독)는 어느 쪽도 아니다.**

심각도를 스스로 정하지 않고 올린다. ① 쪽으로 보면 계정 열거는 §5 Phase 7의 "타 사용자 노출"에
직접 열거되지는 않았으나 계약이 명시적으로 막으라고 적은 정보 유출이고, ② 쪽으로 보면 계약 조항
하나가 케이스 유도 단계에서 **소리 없이 사라진** 자리다(선언 4축 → 도달 3축).

#### C-4 [권고] 401·409·503의 `detail` **문구**가 어디에서도 계약과 대조되지 않는다
**마감: Phase 4 착수 전**(parity 레인이 §8 통보를 입력으로 받기 전)

`detail` 값을 계약에서 읽어 비교하는 것은 **S-3·S-4·S-6뿐**이며 그 근거는 `fields[].detail`이라는
**조항**이다. 나머지는 타입만 본다 — S-2/409(`assertDetailIsString`), L-2/401(같음),
S-13·L-6·M-8/503(`isInstanceOf(String)`), M-2/401(키 집합만). 계약이 그 문구들을 `examples`에만
두었고 명세 O-2가 *"`examples`는 조항이 아니다"*로 정리했으므로 **테스트 쪽 판단은 규약상 일관된다.**

갈리는 것은 **명세 §8 통보**다 — parity-verifier에게 *"auth 범위에서 반드시 같아야 하는 것: …
`detail` **타입**(문자열/배열)과 **문구 전문**"*이라고 적었다. 반드시 같아야 한다고 선언한 것의
도달이 **0**이다. 둘 중 하나로 정리해야 한다 — `examples`를 조항으로 승격하거나, §8 통보에서
「문구 전문」을 빼거나.

**부수 관찰(같은 자리).** M-2의 본문 단언은 **키 집합**이라, 인증을 `sendError(401)`로 내는 구현으로
바꿔도 `ContractErrorController`가 `{"detail":"Unauthorized"}`를 만들어 **그대로 통과한다.** E-2
조항(「어떤 구현 수단으로 나가든 계약 **본문**」) 자체는 그것으로 충족되므로 테스트가 틀린 것은
아니다 — 다만 그때 사용자에게 나가는 문구는 한국어에서 영문 사유구로 바뀌고, React
`readErrorMessage`가 그것을 그대로 뿌린다. 이 갈림을 잡는 장치도 위와 같은 자리에 없다.

#### C-5 [권고] 계약 상한(255)과 DB 컬럼 폭(`varchar(255)`) 사이에 대조가 없다
**마감: 상한을 바꾸는 커밋 / 늦어도 Phase 4**

P-7(`RequestFieldConstraintLayerTest.kt:94-102`)은 계약 **안의** 두 벌(`x-input-limits.max_email_length`
`:321` ↔ `fields[?email].limit` `:389`)만 잰다. **세 번째 사본**인 `users.email character varying(255)`
(`V1__python_schema_baseline.sql:36`)은 비교 대상에 없다.

그리고 경계를 정확히 재는 유일한 케이스 S-5(`AuthContractTest.kt:108-115`)는 **C-M(인메모리 저장소)**
에서만 돈다 — 실 DB를 쓰는 `AuthEndpointReachTest`에는 상한 케이스가 없다. 즉 계약 상한을 256으로
올리면 **전건 초록이고 운영에서 첫 가입이 500**이다. 명세 §4-3이 *"상한을 고치는 사람이 한 곳만
고치는 것이 이 저장소에서 가장 흔한 드리프트"*라고 적은 바로 그 형태이며, 그 대조가 두 곳에서
멈췄다.

---

### 2-2. parity 위험

**검토함 — 지적 없음.** Phase 3 auth는 parity 대상이 아니다(인증·비밀번호는 Python을 보지 않고
명세에서 신규 작성 — `CLAUDE.md`, 구현자 `AuthService` KDoc `:16-18`이 그 결정을 인용).

**Python 무변경 실측**(구현자 §8 주장의 독립 확인):

```
$ git diff --stat e91ecdd~1..fc21750 -- app tests migrations scripts pyproject.toml
 tests/test_parity_ci_gate.py | 60 ++++++++++++++++++++++++++++++++++++++++++--
 1 file changed, 58 insertions(+), 2 deletions(-)
```

`app/`·`migrations/`·`scripts/`·`pyproject.toml` **0건**. 유일한 Python 변경은 하네스 3커밋
소유분이며 §5에서 따로 본다. 구현자 §8 표의 「Python 무변경」 행은 정확하다.

---

### 2-3. 보안 불변식

> `privacy-gate`가 정본이며 판정이 갈리면 그쪽을 따른다. 여기서는 이 배치가 I-8·I-9의 어느 항목에
> 닿는지를 지목한다.

#### S-1 [검토함 — 지적 없음] Argon2 재해시 = 전체 파라미터 동등성 (I-8, 필수조치 A)
`Argon2Policy.matches`(`Argon2PasswordHasher.kt:25-32`)가 변형·버전·메모리·반복·병렬도·salt 길이·
hash 길이 **일곱을 전부** `&&`로 묶는다. `upgradeEncoding()`은 쓰이지 않는다(전 저장소 검색: 호출 0).
테스트가 **양방향**을 건다 — 「올린 정책」·「**낮춘** 정책」·「parallelism·salt·hash 길이만 달라도」.
「읽을 수 없는 해시는 재해시 대상」까지 있다(조용히 「최신」으로 두지 않는다). 검증 파라미터는
인코더가 PHC에서 읽고, 「파라미터를 올려도 옛 해시로 로그인된다」가 그것을 잰다(I-8 검증 1).
`version`을 설정으로 열지 않고 조립 시점에 끊는 판단(`init :68-70`)도 근거가 코드에 있다.

#### S-2 [검토함 — 지적 없음] JWT clock skew 0 · 알고리즘 고정 · 실패 미구분 (I-9, 필수조치 B)
`JwtAccessTokens.verify`(`:94-145`)의 순서가 ① `alg == HS256`(**서명 검증보다 먼저**) → ② 서명 →
③ `exp`(부재도 거부, `:128`) → ④ `typ` → ⑤ `sub` UUID다. 만료 판정은
`!clock.instant().isBefore(expiresAt)`(`:129`) — Nimbus `DefaultJWTClaimsVerifier`·Spring
`JwtTimestampValidator`를 **쓰지 않고** 직접 판정해 기본 60초 오차를 피한다. 실패는 전부 같은
예외·같은 문구이며 **원인 체인을 잇지 않는다**(`invalidCredentials()` `:166`). 서명 키 문제만
`ConfigurationException`(→503)으로 갈라 배포 사고가 401로 둔갑하지 않는다.
`JwtAccessTokensTest` 13건이 `alg:none`·다른 키·클레임 누락·`exp` 경계 3점(직전/정확히/직후)을 덮는다.

#### S-3 [검토함 — 지적 없음] 비밀번호·해시·**이메일**의 로그·응답 유출 0
- `PasswordHash`(`core/user/PasswordHash.kt`) — `toString()` 마스킹(길이도 안 알림), 상수 시간
  `equals`, 값에 의존하지 않는 `hashCode`.
- `StoredUser`·`IssuedAccessToken` — **data class가 아니다**(자동 `toString()` 회피). `SignupRequest`·
  `LoginRequest`·`TokenResponse`는 `toString()` 재정의.
- `AuthService` 로그 2줄 — `userId`와 **예외 타입 이름**만(`:129`·`:135-139`). 예외 객체를
  로거에 넘기지 않는다.
- `JdbcUserRepository` — PostgreSQL 제약 위반의 `DETAIL`에 실패한 행 전체가 실리므로 도메인 예외로
  갈아 끼우고 **원인 체인을 끊는다**(`:77-84`). `JdbcUserRepositoryTest`「예외 메시지와 원인 체인에
  이메일·해시가 실리지 않는다」가 실측.
- `UserResponse`가 `password_hash`·`created_at`을 담지 않고, S-1의 **「정확히」 키 집합** 단언이 그것을 겸한다.
- 응답 바이트 반향 0 — S-10·L-5가 UTF-8 바이트로 비교(`contentAsString`의 ISO-8859-1 함정을 피한 것도 맞다).

#### S-4 → **C-3**(응답 시간 균일성). 이 축의 최대 지적이며 계약 준수와 겹쳐 그쪽에 적었다.

#### S-5 [권고] Argon2 세마포어에 타임아웃이 없다 — OOM을 스레드 고갈로 바꿨을 뿐일 수 있다
**마감: Phase 7 첫 배포 전** / 근거: `Argon2PasswordHasher.kt:82-83, 116-123` · I-8 검증 5

`Semaphore(maxConcurrentHashes, true)`, 기본 4. `permits.acquire()`는 **무기한 대기**다. 로그인
폭주가 오면 Tomcat 요청 스레드(기본 200)가 전부 이 세마포어에 묶이고, 그 순간 **`/health`도 응답하지
못한다.** 계약 `ServiceUnavailable`이 *"설정이 비어도 `/health`로 배포 상태를 진단할 수 있어야 한다"*
는 전제로 기동을 막지 않기로 한 것과 정면으로 어긋나는 상태다.

OOM은 확실히 막았다. 다만 실패 양상이 「프로세스 사망」에서 「전 엔드포인트 무응답 + 헬스체크 실패 →
오케스트레이터 재시작 루프」로 옮겨간 것이므로, `tryAcquire(timeout)` → 503으로 **경계**를 주는 편이
계약의 의도에 맞는다. 구현자 §10이 **"부하 실측은 하지 않았다"**를 스스로 등재했다 — 그 등재는 정직하고
이 지적은 그 등재를 판정으로 바꾸자는 것이다. `privacy-gate`의 I-8 5 판정 대상으로 넘긴다.

#### S-6 [권고] 보호 경로의 CORS preflight가 401이 되지 않는지를 재는 케이스가 없다
**마감: Phase 4**(보호 엔드포인트가 느는 시점)

지금은 **안전하다.** CORS를 `WebMvcConfigurer.addCorsMappings`가 아니라 `CorsFilter`
(`CorsConfig.kt:65-82`, order `CORS_FILTER_ORDER`)로 붙였고, 그 필터가 preflight를 체인에서 끝내므로
`AuthenticationInterceptor`에 닿지 않는다. 이것은 우연이 아니라 `CorsConfig` KDoc `:17-21`이 적은
의도적 선택이다.

그런데 그 안전을 **재는 케이스가 없다.** `CorsContractTest`의 preflight 4건은 전부 `/health`를 겨눈다.
Spring MVC는 preflight 요청에서도 `HandlerExecutionChain`의 인터셉터 목록을 유지하므로, 인증을
필터로 옮기거나 `addCorsMappings`로 되돌리는 날 **브라우저의 인증 요청이 전건 깨지는데 아무것도
빨개지지 않는다.** `/auth/me` OPTIONS → 200 케이스 하나면 닫힌다.

---

### 2-4. Kotlin/Spring 관용성

#### K-1 [검토함 — 지적 없음] 모듈 경계·의존 방향 (계획 §3.2)
`core`(`User`·`PasswordHash`·`Workspace`)와 `application`(`AuthPorts`·`AuthService`·`CredentialRules`)에
Spring import **0**. `AuthService`에 애너테이션 0이고 조립은 `infrastructure/AuthConfiguration`이 한다.
`api`는 `infrastructure`를 `runtimeOnly`로만 의존해 `JwtAccessTokens`·`JdbcClient` 타입을 컴파일
시점에 보지 못하며, 그 격리가 `TestJwt`에서 **부산물로 확인된다** — `nimbus-jose-jwt`가 api 테스트
컴파일 클래스패스에 없어 JDK `Mac`·`Base64`로 직접 조립했고, 그 결과 **검증기와 위조기가 다른
구현**이 됐다(「검사 기준이 검사 대상 자신에게서 나오는」 형태를 피한 자리다). `easydoc.auth.*`
소유자를 `api` → `infrastructure`로 옮긴 것도 `LlmProperties` 전례와 같은 사유이고 접두사 중복이 없다.
`moduleBoundaryCheck`가 `check`에 걸려 있다.

#### K-2 [검토함 — 지적 없음] 트랜잭션 경계 (계획 §4.4 · conventions §6.2)
`TransactionRunner` 포트 + `SpringTransactionRunner`(`TransactionTemplate`). `@Transactional` 프록시를
쓰지 않아 자기 호출 함정이 없고, 경계 선언이 유스케이스에 남는다. **해시 계산은 경계 밖**
(`AuthService.kt:61` → `:63` 순서)이며 `AuthServiceTest`「해시는 트랜잭션 밖에서 계산한다」가 깊이 0을
잰다. 사용자와 기본 작업 공간이 같은 커밋인 것은 `JdbcUserRepositoryTest`「작업 공간 생성이 실패하면
사용자도 저장되지 않는다」(Testcontainers)와 `AuthEndpointReachTest` S-1(실 DB `count(*)`)이 양쪽에서
잰다. `create`가 선조회 대신 유일 인덱스 위반을 번역하는 것도 경합에 옳다.

#### K-3 [권고 — 초판 「수정 필요」에서 내림. 실측이 우려 ⑵를 반증했다] `kotlin-reflect` 처방에 음성 대조가 없었다
**마감: Phase 3 종료 전(기록만)** / 근거: `ConfigurationPropertiesBindingTest.kt:72-86` · 구현자 §5·§6-2 · §6-2 GROUP C 실측

발견 자체는 좋은 작업이다 — 기제 설명(`javap` 실측, `@ConstructorBinding` 불가 실측, JavaBeanBinder가
같은 값이면 조용한 것)이 정확하고, 처방을 클래스 하나가 아니라 **형태 전체**에 건 판단도 옳다.
락파일 실측으로 배선을 확인했다: `kotlin-reflect:2.3.21`이 `api`·`worker`의
`productionRuntimeClasspath,runtimeClasspath,testRuntimeClasspath`에, `infrastructure`의
`testRuntimeClasspath`에 있다.

두 가지가 걸린다.

**⑴ 음성 대조가 없다.** 구현자 §6-2의 구현 값 변이 **4건**(skew 주입 · `upgradeEncoding` 교체 ·
`@Size` 부착 · 보호 목록 비우기)에 **「`kotlin-reflect` 제거」가 없다.** 이 배치가 세운 장치 중
유일하게 음성 대조를 받지 않은 것이다. §5가 서술한 재현 절차(`@SpringBootTest` + 비기본값 →
`BindException`)를 되돌리는 방향으로 한 번 돌리면 되는 일이었다.

**⑵ 「재는 경로가 대리 아닌가」는 실측으로 반증됐다 — 초판 우려를 철회한다.**
`ConfigurationPropertiesBindingTest`가 `Binder`를 **손으로 조립해** `Bindable.of(type)`으로 재는
반면(`:81-83`) 결함이 드러난 실경로는 `@ConfigurationPropertiesScan` → `ConfigurationPropertiesBinder`
이고 둘은 다른 `BindConstructorProvider`를 쓴다 — 그래서 대리 측정일 것으로 의심했다.
**직접 재 봤고, 아니었다**(§6-2 GROUP C):

| 레인 | `runtimeOnly(libs.kotlin.reflect)` 제거 시 |
|---|---|
| 선언된 장치 `ConfigurationPropertiesBindingTest` | **빨강** — `BindException: Failed to bind properties under 'easydoc.auth.argon2' to …Argon2Properties` (Spring 컨텍스트 없이 잡는다. 다만 최초 실패가 **중첩** value object에서 난다) |
| `@SpringBootTest` 레인 | **빨강** — `AuthEndpointReachTest.initializationError` + `AuthUnavailableContractTest` 5건. 근인 체인: `ConfigurationPropertiesBindException` → `BindException … 'easydoc.auth'` → `IllegalStateException: No setter found for property: jwt-secret` |

**두 레인이 독립적으로 발화한다.** 선언된 장치가 진짜 강제자이고, 부트 레인은 「기동 자체가 죽는다」를
함께 보여 주는 두 번째 그물이다. 남는 것은 **구현자 §6-2 표에 이 변이가 없다**는 사실뿐이므로
권고로 내리고, 처분은 산출물 §6-2에 한 행 추가다.

#### K-4 [권고] `application.yml`의 두 줄이 여전히 **기본값과 동일**하다 — 은폐 조건이 그대로 남았다
**마감: Phase 3 종료 전** / 근거: `api/src/main/resources/application.yml`

```yaml
easydoc:
  cors-origins:
    - http://localhost:5173     # EasyDocProperties 기본값과 동일
  auth:
    jwt-expire-minutes: 60      # AuthProperties 기본값과 동일
```

구현자 §5가 *"`easydoc.cors-origins`·`easydoc.auth.jwt-expire-minutes`가 기본값과 같은 값으로 적혀
있어 오래 통과했다"*고 지목한 **바로 그 두 줄이 그대로다.** 원인(kotlin-reflect 부재)은 고쳤으니 지금은
무해하다. 그러나 **운영 설정 파일이 「바인딩이 실제로 동작한다」를 증언하지 못하는 상태**는 유지된다 —
값이 기본값과 같으므로 두 줄을 지워도 동작이 같고, 남겨 두면 다음 사람에게 "설정이 실려 있다"로 읽힌다.
지우는 쪽을 권고한다(기본값이면 적을 이유가 없다).

#### K-5 [권고] 인터셉터 도입으로 모든 `@WebMvcTest`가 `AuthSliceBeans`에 묶였고, 그 스텁은 인증을 무조건 통과시킨다
**마감: Phase 4**

`WebMvcConfig`가 `WebMvcConfigurer`라 모든 `@WebMvcTest` 슬라이스에 자동 포함되고 `AuthService`를
요구한다. 그래서 `/auth`와 무관한 슬라이스 5개가 `@Import(..., AuthSliceBeans::class)`를 붙였다
(`CorsContractTest`·`ErrorContractTest`·`HealthContractTest` 등). 빈을 선택적으로 만들지 않은 것은
의도이고 그 판단은 옳다(§9).

남는 것은 스텁의 성질이다 — `StubAccessTokens.ensureConfigured()`는 무동작이고 `verify()`는
`stub-token:<uuid>` 접두사만 보고 통과시킨다(`AuthSliceBeans.kt:147-157`). 앞으로 **보호 엔드포인트의
슬라이스 테스트**가 이 스텁 위에서 "인증을 통과"하게 되므로, 「슬라이스에서 인증 자체를 단언하지
않는다」는 경계가 산문에만 있으면 다음 사람이 넘는다. `AuthSliceBeans` KDoc이 그 경계를 적었으니
지금은 충분하되, Phase 4에서 보호 엔드포인트 슬라이스가 생길 때 다시 볼 자리로 등재한다.

#### K-6 [권고] KDoc 두 곳이 존재하지 않는 심벌을 **정본으로** 지목한다
`core/user/User.kt`(email 프로퍼티 주석)와 `application/auth/AuthPorts.kt:15`가
*"정규화 규칙의 정본은 `application`의 `EmailNormalization`"*이라고 적는데 그런 심벌은 없다.
실제 정본은 `application/auth/CredentialRules.kt`의 `normalizeEmail`(`:70`). 값이 아니라 **정본의
위치**를 가리키는 참조라, 틀리면 다음 사람이 다른 곳을 고친다. `ktlint`·`detekt`는 KDoc 링크의
실재를 보지 않으므로 초록으로 통과했다.

#### K-7 [권고] 이메일 ASCII 좁힘의 **사유**에 회귀 장치가 없다
**마감: Phase 4**

**좁힘 자체는 계약 위반이 아니다** — `SignupRequest.email`(`:1663-1671`)은 `type: string`뿐이고
형식을 문자 집합으로 규정하지 않는다. 사유(Kotlin `lowercase()`와 PostgreSQL `lower()`가 비ASCII에서
갈리면 `ck_users_email_lowercase` 위반 → 500)도 실재하는 위험이고
`V1__python_schema_baseline.sql:42`에 그 제약이 있다. 판단은 타당하다.

없는 것은 **그 사유를 지키는 장치**다. `EMAIL_PATTERN`(`CredentialRules.kt:55-61`)을 넓히는 순간
500 경로가 되살아나는데, **비ASCII 이메일이 422로 거절된다는 단언이 어디에도 없다.**
`JdbcUserRepositoryTest`「대문자가 섞인 이메일은 CHECK 제약에 걸려 500 계열로 끊긴다」는 저장소 층의
사실을 고정할 뿐 API 층 방어를 고정하지 않는다. 케이스 한 줄(`signup("한글@example.test") → 422`)이면
정규식을 넓히는 커밋에서 먼저 깨진다. 리더 판정이 필요하면 올린다는 구현자 §10의 제안에 동의하되,
**판정 이전에 현행 동작을 고정하는 것**이 먼저다.

---

### 2-5. 테스트 적정성

#### T-1 [수정 필요] F3 강제자의 필드 매칭이 snake_case→camelCase를 옮기지 않는다 — 지금 성립하는 이유가 코드가 적은 이유와 다르다
**마감: Phase 4 `ConversionReviewRequest` 구현 커밋** / 근거: `RequestFieldConstraintLayerTest.kt:51-56, 124-143`

`forbiddenAnnotationsOn`은 세 갈래로 찾는다.

```kotlin
target.declaredFields.filter { it.name == property }                                  // :130-132
target.declaredMethods.filter { it.name.equals("get${property.titlecase}", true) }     // :133-135
target.declaredConstructors.forEach { c -> c.parameterAnnotations.flatMap { … } }      // :136-140
```

계약의 `field` 값은 **snake_case**다(`ConversionReviewRequest.edited_text`). Kotlin 프로퍼티는
`editedText`(+`@JsonProperty("edited_text")`)가 될 것이므로 첫 두 갈래는 `edited_text` /
`getEdited_text`를 찾아 **둘 다 0건**이 된다. 실제로 잡는 것은 세 번째 갈래뿐인데, 그것은
**프로퍼티로 거르지 않고 그 클래스의 모든 생성자 파라미터 애너테이션을 쓸어 담는다.**
즉 검사는 (아마) 성립하지만 **코드가 적은 이유로 성립하지 않고**, 지목 필드명이 틀린 위반 메시지가 나온다.
지금 걸린 두 필드(`email`·`password`)는 우연히 camelCase와 같아 이 갈림이 드러나지 않았다.

**같은 자리에 두 번째 문제가 있다.** 도달 지표가 잘못된 축을 센다 —

```kotlin
val target = classes.firstOrNull { it.simpleName == simpleName } ?: return@forEach
covered += qualified          // :54  ← 클래스를 찾으면 올라간다
…
assertThat(covered).withFailMessage("… 검사 도달이 0 이다").isNotEmpty()   // :65-67
```

`covered`는 **클래스 발견**에서 올라가고 프로퍼티 도달은 세지 않는다. 프로퍼티 매칭이 전건 0이어도
「도달이 0이다」 단언은 통과한다. **조용히 0이 될 수 있는 축을 재지 않는 도달 지표**이고, 이 하네스가
반복해 겪은 형태 그대로다.

부수로, `FORBIDDEN_ANNOTATIONS`가 **단순 이름 9종 allowlist**(`:180-181`)라 선언("길이·형식 Bean
Validation 애너테이션이 없다")보다 좁다. 커스텀 제약(`@ValidEmail`)이나 목록 밖 표준 애너테이션은
지나간다. KDoc이 "같은 일을 하는 것들"이라 적어 인지는 하고 있으므로 **권고 수준**으로만 덧붙인다.

#### T-2 [수정 필요] 인증 커버리지 대조의 **대상 범위**를 두 번째 손 목록이 정한다 — KDoc의 서술이 사실과 반대다
**마감: Phase 4 첫 신규 보호 엔드포인트 커밋** / 근거: `AuthenticationCoverageContractTest.kt:49-55, 76-84, 99-105`

클래스 KDoc(`:14-17`)이 세운 문제의식은 정확하다 — *"열거식 목록은 이 저장소가 이미 두 번 놓친
형태다… 그래서 판정을 사람의 기억이 아니라 **계약**에 맡긴다."* 그런데 그 판정의 **대상 범위**를
다시 손 목록이 정한다.

```kotlin
val implementedProtected = contractProtected intersect implementedPaths()   // :50
assertThat(declared).containsAll(implementedProtected)                       // :51-55
…
private fun implementedPaths(): Set<String> =
    setOf("/auth/signup", "/auth/login", ME_PATH, "/health")                 // :105  ← 손 목록
```

새 보호 엔드포인트를 만들면서 `AuthenticatedEndpoints`와 `implementedPaths()`를 **둘 다** 빠뜨리면,
그 경로는 `implementedProtected`에 들어가지 않으므로 단언 ②가 요구하지 않는다. 세 번째 테스트
(`:76-84`)는 `println` + `doesNotContain(ME_PATH)`뿐이라 잡지 않는다. **결과는 "인증 없이 도는 API"이고,
그 상태는 KDoc이 적은 대로 조용하다.**

**KDoc `:101-103`이 그 사실을 반대로 적는다** — *"손으로 적지만 계약 대조의 기준이 아니라 대상
범위다 — 여기 빠뜨리면 위 단언이 **느슨해지는 것이 아니라** 세 번째 테스트의 「남은 경로」 목록에
그대로 나타난다."* 실제로는 느슨해지고, 세 번째 테스트는 단언이 아니라 출력이다. 틀린 서술이
「이미 덮였다」로 읽히는 것이 이 항목을 코드 결함과 함께 올리는 이유다.

**실측으로 확정했다**(§6-2 B-5·B-6).

| 프로브 | 결과 |
|---|---|
| B-5 — `/documents`를 `implementedPaths()`에 **넣고** `AuthenticatedEndpoints`에는 안 넣음 | **빨강** — `계약이 보호로 선언했는데 인증이 걸리지 않은 경로: [/documents]`. 장치는 대상 범위 안에서 정상 작동한다 |
| B-6 — 둘 **다** 빠뜨림(실제 사고 형태) | **초록.** `:50`의 `intersect`가 `/documents`를 범위 밖으로 밀어내 `:55` 단언 ②가 공허하게 통과하고, `:47` 단언 ①도 `declared` 무변경이라 통과하며, `:83`은 `ME_PATH`만 본다 |

**게이트가 자기 동기가 되는 시나리오에서 스스로를 면제한다.** `AuthenticatedEndpoints`를 잊는 커밋이
바로 `implementedPaths()`를 잊는 커밋이고, 그 짝은 초록이다.

부수 방벽은 하나 있다 — `AuthenticatedUserArgumentResolver`(`:117`)가 인터셉터를 거치지 않은 요청에서
`error(...)`를 던져 500이 된다. 다만 그것은 **핸들러가 `AuthenticatedUser`를 파라미터로 받을 때만**이고,
선언된 장치가 아니라 우연한 부작용이다.

**고치는 방향 하나**: `implementedPaths()`를 손 목록이 아니라 **`RequestMappingHandlerMapping`에서
유도**하면(스프링 컨텍스트가 실제로 매핑한 경로 집합) 두 번째 손 목록이 사라진다. 그러면 「구현했다」의
정의가 사람 기억이 아니라 실행 상태가 된다.

#### T-3 [권고] 동시 실행 상한이 **실제로 동시성을 제한하는지**를 재는 테스트가 없다
`Argon2PasswordHasherTest`에 있는 것은 「동시 실행 상한이 0 이하면 조립에서 막힌다」 하나다. 세마포어가
실제 계산 구간을 감싸는지, 상한이 지켜지는지는 재지 않는다. 구현자 §10이 「부하 실측 미실시」로 등재했다.
S-5와 짝이며 `privacy-gate` I-8 5로 넘긴다.

#### T-4 [권고] M-6b의 경계값 선택이 skew > 0에서는 구현이 맞아도 빨개진다
`AuthEndpointReachTest.kt:196-205`. `exp = now - skew`인 토큰을 만들고 `skew > 0`이면 **성공**을
기대하는데, 토큰 조립과 서버 판정 사이에 경과 시간이 있어 실제로는 `now' - exp > skew`가 된다.
계약이 0인 지금은 항상 옳게 돌지만(0에서는 `exp = now`이고 판정 시점이 그 이후라 확실히 거절),
skew를 0보다 크게 바꾸는 날 **구현이 계약대로여도 이 케이스가 실패한다.** N-4가 잡은 공백을 메운
케이스라는 목적은 달성했으므로 권고에 둔다 — `exp = now - skew + 여유`로 두면 그 취약함이 없어진다.

#### T-7 [수정 필요] 「보호 목록 비우기」 음성 대조가 **겨눈 방향의 반대**를 잰다
**마감: Phase 3 종료 전(산출물 정정)** / 근거: §6-2 B-4 실측 · 구현자 §6-2 4행

구현자 §6-2의 마지막 행은 이렇게 적는다 — *「`AuthenticatedEndpoints` 목록 **비우기** → exit 1 →
`AuthenticationCoverageContractTest` 「보호 경로 목록이 계약의 security 선언과 정확히 같다」」*.

실행하면 그 테스트가 실제로 빨개진다. **그런데 목록을 비우는 변이가 만드는 상태는 「보호가 빠진
엔드포인트」가 아니라 그 반대다** — Spring `addPathPatterns(emptyList())`는 include 패턴이 없다는
뜻이라 인터셉터가 **모든 요청**에 걸린다. 실측: api 117건 중 **65건 실패**이고 대부분이
`/health` → `Status expected:<200> but was:<401>` 같은 **과잉 보호 부수 피해**다.

즉 이 음성 대조는 「장치가 미보호를 잡는다」를 보이지 않는다. 미보호 방향을 실제로 겨눈 것은
이 리뷰의 B-5이며, **B-6이 그 방향의 진짜 사고 형태에서는 초록임을 보였다**(T-2). 산출물 §6-2의
그 행은 「목록 비우기 → 빨강」으로 읽히면 **T-2가 이미 덮였다는 오독**을 낳는다.

- 과잉 결합 65건 자체는 결함이 아니다(인터셉터가 실제로 전 경로에 붙었으니 옳게 깨졌다).
- 문제는 **그 변이가 재려던 성질을 재지 못한다**는 것이고, 처분은 §6-2 행을 B-5·B-6 형태로 교체하는 것이다.

#### T-5 [검토함 — 지적 없음] 명세 §5 계층 지목 준수
M-2~M-7이 전부 `AuthEndpointReachTest`(`@SpringBootTest(RANDOM_PORT)` + `java.net.http` 실제 소켓)에
있다. MockMvc로 낸 401은 L-2 하나이고 그것은 명세가 C-M으로 지목한 자리(어드바이스가 만드는 응답)라
어긋나지 않는다. 명세와 다른 두 자리(S-1을 **양쪽**으로, L-1·L-1b·M-1을 C-R로)는 구현자 §7이
사유를 병기했고 그 사유(실물 설정에서만 잴 수 있는 값 / 슬라이스가 정한 값을 다시 단언하는 꼴)가
타당하다 — **C-M을 지운 것이 아니라 C-R을 더했다**는 서술도 코드와 일치한다.

#### T-6 [검토함 — 지적 없음] 실패 경로 커버리지
성공 경로만 있는 모듈이 없다. `JwtAccessTokensTest` 13건 중 9건이 거절 경로, `Argon2PasswordHasherTest`
10건 중 6건이 재해시·거절·조립 실패, `JdbcUserRepositoryTest` 9건 중 4건이 중복·CHECK 위반·롤백,
`AuthServiceTest` 10건 중 5건이 실패·best-effort 경로다.

---

## 3. 도달 범위 점검 결과

> 다섯 축을 가로지르는 **필수** 구획. 지적 없음과 미검토를 구분해 적는다.
> 기준 전문은 `kotlin-migration` 「선언한 범위와 실제 도달을 대조한다」.

| # | 점검 항목 | 결과 |
|---|---|---|
| R-1 | 새 「전역/모든/항상」 선언의 미도달 경로 | **지적 3건** — **T-2**(「계약의 security 선언과 정확히 같다」 ← 두 번째 손 목록. B-6 실측) · **T-1**(「api 모듈의 컴파일된 클래스 전수」·「다섯 필드」 ← snake_case 프로퍼티 매칭 0 가능) · **C-6**(계약 `x-global-response-headers`의 「모든 응답」 ← 값 `const` 강제자 auth 3곳. N-3 실측) |
| R-2 | 그 게이트가 **지금 어디서 도는가** (도달 0 의심) | **지적 1건 — R-6a**(아래). 나머지: `RequestFieldConstraintLayerTest`·`AuthenticationCoverageContractTest`·`ConfigurationPropertiesBindingTest`는 `:api:test`에 있어 `ci:kotlin`에서 돈다(`@Disabled`·태그 제외 0 확인). **다만 §7-1 ⑴** — 계약 파일이 Gradle 선언 입력이 아니라 「계약만 바뀐 변경」에는 도달이 0이 될 수 있다(contract-keeper 6ece404 §5 단독 발견) |
| R-3 | 대리 경로에서 잰 자리 | **초판 K-3을 의심했으나 실측으로 반증**(§6-2 C-1 — 손으로 조립한 `Binder`도 `@SpringBootTest` 레인도 둘 다 빨강). **지적 없음** |
| R-4 | 검사 기준이 검사 대상 자신에게서 나오는가 | **검토함 — 지적 없음.** `ContractSpec`이 계약 파일을 직접 읽고(하드코딩 기대값은 상태 코드 상수와 헤더 이름→컴포넌트 이름 사상뿐), `TestJwt`가 제품과 **다른 구현**(JDK `Mac`)으로 토큰을 조립한다. `AuthSliceBeans`의 스텁 수명(`STUB_LIFETIME_SECONDS = 1`)이 계약값이 **아님**을 명시하고 `expires_in` 단언을 실물 계층으로 보낸 것도 이 함정을 정확히 피한 자리다 |
| R-5 | 대리 지표로 판정하는가 | **지적 1건 — T-1**(도달 지표가 클래스 발견을 세고 프로퍼티 도달을 세지 않는다). 부수: `AuthenticationCoverageContractTest`의 「남은 경로」와 `RequestFieldConstraintLayerTest`의 「미구현 필드」가 **`println` + 좁은 단언**이라 기록이지 판정이 아니다 — 둘 다 코드가 그렇게 적어 두었으므로 오독은 아니나, 그 기록을 읽는 사람이 없으면 도달 0이다 |
| R-6 | 규칙·패턴의 범위가 근거보다 넓은가 (은폐형) | **검토함 — 지적 없음.** 이 배치는 무시 패턴·전역 예외·억제 조항을 **하나도** 추가하지 않았다. `@Suppress`는 `AuthService.kt:131`의 `TooGenericExceptionCaught` 한 건이고 범위가 catch 하나이며 사유가 옆에 있다. `.gitignore`·ktlint/detekt baseline 변경 0 |
| R-7 | 음성 대조가 붙어 있는가 (떼면 무엇이 깨지는가) | **지적 3건** — **K-3**(`kotlin-reflect` 처방에 음성 대조 없었음 — 이 리뷰가 실행해 닫음) · **T-7**(있는 음성 대조 하나가 **반대 방향**을 잰다) · **R-6a**(절차가 CI에 없다). 반대로 **잘 붙은 자리**도 실측했다 — N-1~N-8 8건과 구현 값 3건이 전부 겨눈 대로 빨강이고 과잉 결합이 없다(§6) |
| R-8 | 판정하는 코드가 자기 자신을 검사 대상에 넣었는가 | **검토함 — 지적 없음.** `ContractSpec`·`TestJwt`·`AuthSliceBeans`는 `api/src/test`에 있어 `ktlintCheck`·`detekt`·컴파일 대상이다. `RequestFieldConstraintLayerTest`가 훑는 것은 `api/build/classes/kotlin/main`이므로 자기 자신(test 소스셋)은 대상 밖인데, 이 경우는 **의도가 맞다**(제품 DTO를 재는 검사다) |

### R-6a [권고 / 판정 필요] 음성 대조가 CI에 없다 — `ContractSpec` 성질 4의 도달이 사람에게 달려 있다
**마감: 판정 필요** / 근거: `ContractSpec.kt:26` · 명세 §4-2 4번·§4-4

`ContractSpec` 스스로 적는다 — *"성질 4(읽은 값을 실제 단언에 쓴다)는 이 파일이 보장할 수 없다 —
**음성 대조가 그것을 잰다.**"* 그런데 그 음성 대조는 **사람이 손으로 도는 절차**다. N-1~N-8 표는 명세에,
실측 결과는 구현자 산출물 §6에 산문으로 있을 뿐, 실행 가능한 형태가 아니고 CI에 배선돼 있지 않다.

즉 「계약 값을 바꾸면 테스트가 깨진다」는 성질은 **다음 회차에 누군가 손으로 다시 돌리지 않으면
도달이 0**이 된다. 그리고 N-3·N-4가 첫 실행에서 통과했다는 사실이, 이 절차가 실제로 결함을 잡는
장치라는 것을 동시에 증명한다 — **잡히는 장치를 사람 기억에 매달아 둔 상태**다.

한편 리더 결정 ⑴(러너 동결)이 「하네스 자기 검사를 더 넓히지 않는다」이고 예외 ⓑ가 「Phase 3 제품
코드를 재는 장치」다. 이 항목은 **제품 계약 테스트의 강제자**이므로 ⓑ에 해당해 보이나, 「음성 대조를
자동화하는 장치」는 다시 그 장치의 도달을 묻게 되는 재귀(결정 ⑶이 끊기로 한 형태)에 가깝다.
**어느 쪽인지 스스로 정하지 않고 리더 판정으로 올린다.**

### R-7a [검토함 — 사실 확인] 구현자 §6-2의 「F3 1차 방벽」 보고는 정확하다
독립 확인:
- `api/build.gradle.kts`에 `spring-boot-starter-validation` **없음**.
- `api/gradle.lockfile`의 `jakarta.*` 항목은 `jakarta.annotation-api`(전 클래스패스),
  `jakarta.activation-api`·`jakarta.xml.bind-api`(test 전용) **셋뿐** — `jakarta.validation-api` 0건.
  따라서 금지 애너테이션은 **컴파일조차 되지 않는다**는 서술이 맞고, 음성 대조를 「같은 이름의
  애너테이션을 직접 선언해」 수행한 것도 불가피했으며 `FORBIDDEN_ANNOTATIONS`가 **단순 이름**으로
  비교하므로 그 대체는 유효하다.
- Phase 4에 사라진다는 예측도 맞다 — 계약 `x-input-limits.list_limit`(`:318`)이 `limit`/`offset`을
  **스키마 층**(배열 `detail`)으로 못박았으므로 그때 `spring-boot-starter-validation`이 들어온다.
  그 시점부터 `RequestFieldConstraintLayerTest`가 유일 강제자가 된다는 것도 맞다 — **단 그 유일
  강제자의 도달에 T-1이 있다.** 두 사실을 함께 읽어야 한다.

### R-7b [검토함 — 판정] 원장 X6 강제자 축의 상태 변화
원장 `:554`·`:660`이 X6 강제자 축을 `안 돎` · 마감 「Phase 3 해당 DTO 구현 커밋」으로 들고 있다.
**auth 2필드에 한해 실행 소스로 바뀌었다** — `RequestFieldConstraintLayerTest`가 컴파일 산출물을
훑고, 음성 대조(`@Size` 부착 → 빨강)가 있으며, 대상 목록을 계약에서 읽는다.
`git grep -E 'X-F1[123]'`가 Markdown 2파일뿐이던 상태는 해소됐다(코드가 ID를 인용하지는 않으나
계약 노드를 직접 읽으므로 결속은 ID보다 강하다).

**나머지 세 필드**(`DocumentTextRequest.text`·`ConversionReviewRequest.edited_text`·
`WorkspaceNameRequest.name`)는 각자의 DTO 커밋으로 이월되며, **T-1 때문에 그 이월분의 강제력은
지금 상태로는 보장되지 않는다.** 원장 표기는 **「부분 해소 — auth 2필드, 나머지 3필드 이월 + T-1
선결」**이 정확하고, 「해소」로 적으면 과대 표기다(게이트 16이 같은 행에서 이미 겪은 정정이다).

### R-7c [권고] 착수 지침 8의 「대상 없음」은 정직하나, 마감을 든 것이 산문 2곳뿐이다
`AuthController`에 `@RequestParam`·`@PathVariable`이 **0**이므로
`handleHandlerMethodValidationException`에 auth 대상이 없다는 명세 O-6·구현자 §7의 서술은 **사실이다.**
「없는 것을 없다고 적었다」는 점에서 처리는 정직하다.

남는 것은 마감의 형태다 — 그 마감(`limit`/`offset` 단위)을 들고 있는 것이 명세 §3·구현자 §7이라는
**산문 2곳**이고 실행 가능한 강제자가 0이다. X6가 정확히 같은 형태로 세 회차를 갔다. Phase 4 착수
시점에 이 항목이 「이미 덮였다」로 읽히지 않도록 원장 이월 목록에 올린다.

---

## 4. Phase 3 종료 조건 대비 현황

> **이 절은 1차 산출물 기준이므로 Phase 종료 판정의 근거가 아니다.** 판정 정본은 2차 `..._cross.md`다.

### 4-1. auth 계약 케이스 대응 (지시문의 「27건」 = 실제 30건)

리더 지시문의 「27건」은 명세 §2 초판 머리글의 수치다. **실제 케이스는 30건**(signup 15 · login 7 ·
me 8)이고, 27은 하위 케이스 3건(S-1b·S-2b·L-1b)을 뺀 값이다. 음성 대조 N-4가 **M-6b 1건을 신설**해
지금은 31건이다. *(contract-keeper가 6ece404 §4에서 같은 정정을 독립으로 냈다 — 「30건, 1단계의
27건은 집계 오류」. 두 레인의 셈이 일치한다.)*

| 판정 | 건수 | 케이스 |
|---|---|---|
| **대응 — 명세대로** | 26 | S-1·S-1b·S-2·S-2b·S-3·S-4·S-5·S-6·S-7·S-8·S-9·S-10·S-11·S-12·S-13 / L-1·L-1b·L-4·L-5·L-6 / M-1·M-3·M-4·M-5·M-7·M-8 |
| **대응 — 계층을 올림(사유 병기, C-M 유지)** | (위에 포함) | S-1(양쪽) · L-1·L-1b·M-1(C-R) — 구현자 §7 |
| **부분 — 조항 축 하나가 빠짐** | 2 | **L-3**(상태·본문·헤더는 재고 **응답 시간** 미측정 → C-3) · **M-2**(본문·`WWW-Authenticate`·`Content-Type`은 재고 **전역 헤더** 미측정 → C-2) |
| **신설** | 1 | **M-6b**(N-4가 드러낸 공백 — 오차 경계 **안쪽**. 다만 T-4) |
| **미대응** | **0** | — |
| 파서 요건 P-1~P-12 | 12/12 실재 | `ContractSpec.kt` — P-1 `:89-107` · P-2 `:110-119` · P-3 `:140` · P-4 `:143-144` · P-5 `:147` · P-6 `:157-169`(**이름으로 조회**, 인덱스 아님) · P-7 `:172` · P-8 `:177-182` · P-9 같음 · P-10 `:185-188` · P-11 `:191-194` · P-12 `:199-203` |
| 파서 성질 §4-2 1~5 | 1·2·3·5 충족 / **4는 음성 대조에 위임**(R-6a) | 1: `error(...)`·`require(...)`로 실패, 기본값·스킵 0 / 2: `easydoc.kotlin.source.root`에서 유도 / 3: `field` 이름 조회 / 5: 읽는 노드 = 요구한 12개 |

**계약 인용 라인 전수 대조 — 명세 §0의 「2026-08-19 실측」 주장 검증.** 21개 키 경로의 실제 행을 전부
확인했고 **불일치 0건**이다: `x-auth` `:263`(algorithm `:265` · claims `:266` · claim_typ `:268` ·
required_claims `:269` · min_secret_bytes `:270` · default_lifetime_seconds `:271` ·
clock_skew_seconds `:272` · x-clock-skew `:273` · rehash_policy `:286` · failure_uniformity `:299`) ·
`x-input-limits` `:307`(max_email_length `:321` · min_password_length `:322`) ·
`x-request-field-constraints` `:332`(fields `:386`, email `:387-391`, password `:392-396`) ·
`x-global-response-headers` `:519`(headers `:521`) · `x-private-response-headers` `:696`
(headers `:701` · applies_to `:704`, 10항목 `:705-714`) · `/auth/signup` `:760` · `/auth/login` `:800` ·
`/auth/me` `:835` · `components/headers` `:1436`(CacheControlNoStore `:1437` · XContentTypeOptions
`:1444` · WWWAuthenticateBearer `:1450`) · `responses` `:1455`(Unauthorized `:1457` · Conflict `:1475` ·
ValidationFailed `:1497` · ServiceUnavailable `:1562`) · `schemas` `:1585`(ErrorResponse `:1590` ·
ValidationErrorItem `:1615` · SignupRequest `:1659` · LoginRequest `:1681` · UserResponse `:1688` ·
TokenResponse `:1696`). **명세의 인용은 신뢰할 수 있다.**

### 4-2. 미해결 항목

| # | 항목 | 심각도 | 마감 | 실측 근거 |
|---|---|---|---|---|
| C-1 | 422·401을 계약과 대조하지 않는 케이스 8건 | 수정 필요 | Phase 3 종료 전 | §6-1 A-X1·A-X2(초록) + A-X3(양성 대조 빨강) |
| C-2 | X-D2가 5건 중 2건만(특히 M-2) | 수정 필요 | Phase 3 종료 전 | 코드 대조 |
| **C-3** | `failure_uniformity` **응답 시간** 축 — 계약·구현·테스트·소유자 해석 네 곳이 갈림 | **판정 필요(차단 후보 ①+②)** | Phase 3 종료 전 | 계약 `:299-302` ↔ `AuthService.kt:86-92` |
| C-4 | 401·409·503 문구의 강제자 0 ↔ 명세 §8 통보의 「문구 전문」 | 권고 | Phase 4 착수 전 | 코드 대조 |
| C-5 | 계약 상한 ↔ DB 컬럼 폭 대조 없음 | 권고 | 상한 변경 시 / Phase 4 | 코드 대조 |
| **C-6** | 헤더 값 `const` 강제자가 auth 3곳뿐 — 전역 헤더 테스트가 계약을 안 읽는다 | 수정 필요 | Phase 3 종료 전 | §6-1 N-3 전건 117 실행 |
| S-5 | Argon2 세마포어 무기한 대기 → `/health` 동반 마비 | 권고 | Phase 7 배포 전 | 코드 대조(부하 미실측) |
| S-6 | 보호 경로 preflight 케이스 없음 | 권고 | Phase 4 | 코드 대조 |
| K-3 | `kotlin-reflect` 회귀 장치에 음성 대조가 없었다 (**대리 경로 우려는 반증됨**) | **권고**(초판 수정 필요에서 내림) | Phase 3 종료 전(산출물 기록) | §6-2 C-1 — 양 레인 빨강 |
| K-4 | `application.yml` 두 줄이 기본값과 동일(은폐 조건 잔존) | 권고 | Phase 3 종료 전 | 파일 대조 |
| K-5 | 슬라이스 인증 스텁이 무조건 통과 | 권고 | Phase 4 | 코드 대조 |
| K-6 | KDoc이 없는 심벌(`EmailNormalization`)을 정본으로 지목(2곳) | 권고 | Phase 3 종료 전 | 심벌 검색 0건 |
| K-7 | 이메일 ASCII 좁힘에 회귀 장치 0 | 권고 | Phase 4 | 코드 대조(contract-keeper §6 조건 3과 일치) |
| T-1 | F3 강제자의 snake_case 필드 매칭 + 도달 지표 축 오류 | 수정 필요 | Phase 4 해당 DTO 커밋 | 코드 대조 |
| T-2 | 인증 커버리지의 두 번째 손 목록 + KDoc 반대 서술 | 수정 필요 | Phase 4 첫 신규 보호 EP | §6-2 B-5(빨강) vs **B-6(초록)** |
| T-3 | 동시 실행 상한 미측정 | 권고 | Phase 7 | — |
| T-4 | M-6b 경계값이 skew>0에서 취약 | 권고 | skew 변경 시 | 코드 대조 |
| **T-7** | 「보호 목록 비우기」 음성 대조가 겨눈 방향의 반대를 잰다 | 수정 필요 | Phase 3 종료 전(산출물 정정) | §6-2 B-4 — 65/117 과잉 보호 |
| R-6a | 음성 대조가 CI에 없다 — `ContractSpec` 성질 4의 도달이 사람에게 달렸다 | 권고 / **판정 필요** | 판정 필요 | — |
| R-7c | 착수 지침 8 마감의 강제자 0 | 권고 | Phase 4 | 코드 대조 |
| **H-1** | 하네스 — 탐지 집합의 **양성 경로**가 한 번도 실행되지 않는다 | 권고 / **판정 필요** | 판정 필요 | 참조 전수 + §6-3 |
| H-2 | 하네스 — `hasattr` 단언의 잔여 역할이 어디에도 안 적혀 있다 | 권고 | — | §6-3 M2 |
| H-3 | 하네스 — 기대 집합이 대상 6줄 아래 같은 리터럴(같은 hunk) | 권고 | — | `:1012-1014` ↔ `:1022-1024` |

**차단 0 · 수정 필요 6**(C-1·C-2·C-6·T-1·T-2·T-7) **· 권고 13 · 판정 필요 3**(C-3·R-6a·H-1).
**검토함(지적 없음) 12** — P-1(parity) · S-1·S-2·S-3(보안 3축) · K-1·K-2 · T-5·T-6 ·
R-2(도달: 게이트 실행 위치) · R-4(자기 참조) · R-6(은폐형) · R-8(자기 검사 포함) · R-7a·R-7b(사실 확인 2).
**미검토 — §7-2에 6항목**.

> **차단 0으로 적은 근거.** 이 배치에서 §5 Phase 7 즉시 중단 기준(①)에 해당하는 경로를 찾지
> 못했고, 게이트 무력화(②)로 볼 만한 자리 중 **T-2·H-1**이 후보였으나 둘 다 **지금 실사용
> 상태에서는 사고가 나지 않고** 마감이 이후 Phase다. **C-3만은 ① 후보로 남겨 판정을 올린다** —
> 계정 열거는 계약이 명시적으로 막으라고 적은 유출이고, 「낮춰 놓으면 마감 없이 사라진다」는
> 규칙에 따라 스스로 권고로 내리지 않았다.

---

## 5. 하네스 3커밋 판정 (`e91ecdd` · `e600861` · `e7f9bdb`)

원장 리더 결정 9에 따라 이 게이트가 처음 보는 3커밋이다. 대상은 `tests/test_parity_ci_gate.py`
1파일이며 제품 코드에 닿지 않는다. **일회용 worktree에서 8변이를 독립 재현했다**(§6-3).

### 5-1. 판정

| 축 | 판정 |
|---|---|
| ① `_DYNAMIC_LOOKUP_NAMES` 내용 정확 일치 (`e7f9bdb`) | **유효 — 단독 편집 전건 차단.** 빈 집합·7 junk·builtin 치환 셋 다 빨강 |
| ⑥ 문구 결속·유일성 (`e91ecdd`·`e600861`) | **유효.** 복사본 치환·미관측 문구·표 문구 이탈·테스트 이름 재지목 넷 다 빨강 |
| 동반 편집(기대 집합을 함께 고침) | **초록 — 이미 등재된 한계**(리더 결정 ⑶ ⑧). 새 지적이 아니다 |
| **탐지의 양성 경로** | **⚠ H-1 — 새 지적.** 아래 |
| 회귀 여부 | 없음. 기준 36 passed 유지, 전체 스위트 무영향 |

**세 커밋은 자기가 선언한 것을 실제로 한다.** 「개수 → 부분집합 → 내용 정확 일치」의 최종형이
단독 치환 통로를 전부 닫는다는 것을 실측으로 확인했다.

### 5-2. H-1 [권고 / 판정 필요] 탐지 집합의 **양성 경로**가 한 번도 실행되지 않는다
**마감: 판정 필요** / 근거: `tests/test_parity_ci_gate.py:1012`·`:1058`·`:1218`·`:1245`

세 커밋이 지킨 것은 **「집합이 조용히 좁아지지 않는다」**이다. 지키지 않는 것이 하나 남는다 —
**「그 집합으로 실제로 잡는다」**.

전수 확인:

- `_DYNAMIC_LOOKUP_NAMES`의 기능적 사용처는 **`:1058` 하나**(`_root_helper_calls` 안의 탐지 집합).
- `_root_helper_calls`의 호출처는 **`:1240` 하나**이고, 그 `dynamic_lookups` 결과는 **오직 비어
  있음으로만** 단언된다(`:1245`).
- `getattr(...)`를 담은 비교기를 먹여 **실제로 잡히는지 재는 테스트는 저장소 어디에도 없다.**

따라서 AST 순회에 결함이 있어 **무엇도 잡지 못해도** `assert not dynamic_lookups`는 공허하게
참이다. R19-1이 지적한 「빈 선언에서 공허하게 참」의 **한 겹 위**이고, 세 커밋은 집합의 내용만
고정했지 그 집합을 쓰는 기제의 발화를 고정하지 않았다.

**리더 결정 ②·③·⑧ 어디에도 등재돼 있지 않다.** ②는 별칭·`partial`·import alias(우회), ③은
`_MAINLINE_ROOTS` 밖의 새 root, ⑧은 단언 자체의 외부 강제자 부재다. 이것은 **탐지기의 양성
경로 미실행**이라 셋과 다른 종류다.

처분 판정을 스스로 하지 않는다 — 리더 결정 ⑴이 「러너·완전성 장치·표기 검사기에 **새 검사를 더하지
않는다**」이므로 양성 케이스 추가는 동결 대상으로 보이고, 그렇다면 처분은 **등재**다. 다만 이것은
「하네스가 하네스를 재는」 재귀가 아니라 **기존 탐지기가 작동하는지**를 한 번 재는 것이라 성격이
다르다. 리더 판정으로 올린다.

### 5-3. H-2 [검토함 — 관찰] `e600861`의 builtin 실재 단언은 죽지 않았다, 다만 발화 조건이 바뀌었다
`:1224-1230`의 `hasattr(builtins, name)` 단언은 **단독 편집에서는 절대 발화하지 않는다** —
`:1218`의 내용 정확 일치가 먼저 걸린다(M2 실측이 이것을 보였다: junk 7종이 `hasattr`이 아니라
정체성 단언에 잡혔다).

**그래도 죽은 코드가 아니다.** 두 집합을 **함께** 고치면서 실재하지 않는 이름을 넣는 경우의
유일한 잔여 방벽이다. 즉 동반 편집의 구멍을 「실재하는 다른 builtin으로의 치환」으로 **좁힌다.**

이 사실이 코드에도 원장에도 적혀 있지 않다. 다음 사람이 `e600861`의 절반을 「`e7f9bdb`가 흡수했다」로
읽고 지울 수 있으므로 **주석 한 줄**을 권고한다.

### 5-4. H-3 [권고] 기대 집합이 대상 집합에서 **6줄 아래**에 같은 리터럴로 있다
`:1012-1014`(`_DYNAMIC_LOOKUP_NAMES`) ↔ `:1022-1024`(`EXPECTED_DYNAMIC_LOOKUP_NAMES`) — 두 사본의
내용이 **문자 그대로 같고** 여섯 줄 떨어져 있다.

원장 「게이트 19 후속」 §1이 이 장치의 효력을 *"어떤 치환도 기대 집합을 같은 커밋에서 함께 고쳐야
통과하며 **그 편집이 diff로 리뷰에 올라간다**"*로 적었다. 그런데 두 사본이 인접해 있어 동반 편집이
**같은 hunk 안**에 들어간다 — diff 가시성 장치로서 가장 약한 배치다. 전례로 든
`test_harness_scope_reach.py`의 정체성 키 집합이 어떤 배치인지 대조하면 판단이 선다.
`EXPECTED_DYNAMIC_LOOKUP_NAMES`는 **이 파일 밖 어디에서도 참조되지 않는다**(전 저장소 grep:
정의 `:1022` · 단언 `:1218` · 그 단언의 실패 메시지 f-string `:1220-1221` · 원장 산문. CI 스크립트 0).

---

## 6. 음성 대조 독립 재현

**절차(규칙 5).** 두 개의 일회용 `git worktree`를 `fc21750`에 붙여 만들었고, 변이는 한 번에 한
파일씩(`git diff --stat`으로 확인) 적용했다. 복원은 전건 `git checkout -- <path>` + `shasum -a 256`
**본 저장소 대조**이며 **`cp`를 쓰지 않았다.** 게이트 명령은 **파이프 없이** 돌려 종료 코드를 직접
받았다. 두 worktree 모두 제거했고 `git status --porcelain`이 비었음을 확인했다.
`ckwt`(타 세션 소유)는 건드리지 않았다.

**캐시 무효화.** 계약 파일 변이는 전건 `--rerun`/`--rerun-tasks`로 돌렸다 — 사유는 §7-1에 있다.

### 6-1. 계약 값 변이 (명세 §4-4 + 이 리뷰의 추가 프로브)

기준선: `:api:test` 117 + `:infrastructure:test` 95 = **212건, 실패 0**(구현자 §8의 모듈별 건수와 일치).
복원 해시 `8f7d4efa31aa3e48b5ff829599c4bb84232b62ef5e5bf0179f099fc4f0e4be92` — **전 프로브 일치**.

| # | 바꾼 노드 | exit | 관측한 빨강 | 판정 |
|---|---|---|---|---|
| N-3 | `CacheControlNoStore.schema.const` | 1 | S-1 · L-1 · M-1 | 구현자 §6-1 및 contract-keeper §3과 **일치**. **단 → C-6** |
| N-4 | `x-auth.clock_skew_seconds` 0→120 | 1 | **M-6b 하나** | 일치. **M-6은 초록으로 확인** — `skew + 1`만 재므로 계약보다 엄격한 구현도 통과한다. **M-6b 신설이 이 결속을 혼자 진다** |
| N-2 | `fields[?email].detail` 한 글자 | 1 | S-3 · S-4 | 일치 |
| N-7 | `x-input-limits.min_password_length`만 | 1 | P-7 (`비밀번호 하한이 x-input-limits 와 fields[].limit 에서 갈렸다`) | 일치 |
| **A-X1**(신규) | signup `responses.'422'` → `'400'` | **0** | **없음** | **→ C-1.** 전건 117 실행 확인 |
| **A-X2**(신규) | `/auth/me` `responses.'401'` → `'403'` | **0** | **없음** | **→ C-1.** 같음 |
| **A-X3**(신규·양성 대조) | login `responses.'401'` → `'403'` | 1 | L-2 | A-X1·A-X2의 초록이 캐시 산물이 아님을 보증 |

### 6-2. 구현 값 변이 (착수 지침 6 + 이 리뷰의 추가 프로브)

| # | 바꾼 것 | exit | 관측한 빨강 | 판정 |
|---|---|---|---|---|
| B-1 | `JwtAccessTokens` 만료 판정에 60초 오차 주입 | 1 | **api**: M-6 · M-6b · M-3(「만료가 401이 아니다」) / **infrastructure**: `exp 를 1초 지나면 거부한다` · `exp 와 정확히 같은 시각도 거부한다` | **두 계층이 독립으로 잡는다.** 단위 테스트만 잡는 상태가 아니다 |
| B-2 | `needsRehash` → `upgradeEncoding()` | 1 | 10건 중 3건 — `읽을 수 없는 해시` · `parallelism·salt·hash 길이만 달라도` · `파라미터를 **낮춘** 정책도` | 정확히 `upgradeEncoding`이 **구조적으로 못 보는** 셋. 「**올린** 정책」은 초록(그쪽이 다루는 유일한 방향) — 결속이 정밀하다 |
| B-4 | `AuthenticatedEndpoints` 목록 비우기 | 1 | 117 중 **65** — 대부분 `/health` 401 등 **과잉 보호 부수 피해** | **→ T-7.** 겨눈 방향의 반대를 잰다 |
| **B-5**(신규) | 보호 경로를 `implementedPaths()`에는 넣고 목록에는 안 넣음 | 1 | `보호 경로 목록이 계약의 security 선언과 정확히 같다` | 장치는 **범위 안에서** 정상 |
| **B-6**(신규·정적) | 둘 **다** 빠뜨림 = 실제 사고 형태 | — | **초록**(코드 대조) | **→ T-2.** 게이트가 자기 동기 시나리오에서 자기를 면제한다 |
| **C-1**(신규) | `runtimeOnly(libs.kotlin.reflect)` 제거 (+ 락 재생성) | 1 | ⑴ `ConfigurationPropertiesBindingTest` 빨강(`BindException … 'easydoc.auth.argon2'`) ⑵ `AuthEndpointReachTest.initializationError` + `AuthUnavailableContractTest` 5건(`No setter found for property: jwt-secret`) | **→ K-3 우려 ⑵ 반증.** 선언된 장치가 실제 강제자다 |

### 6-3. 하네스 3커밋 변이

기준선 `uv run pytest tests/test_parity_ci_gate.py` → **exit 0 · 36 passed**.
복원 해시 `607eb242d817c428e0e7b250db30ba357e60e572ca988447a4477e3e95245e19` — **전 8회 일치**.

| # | 변이 | exit | 결과 |
|---|---|---|---|
| M1 | `_DYNAMIC_LOOKUP_NAMES` 빈 집합 | 1 | 빨강 `:1218` — `빠짐 [7종] / 추가 []` |
| M2 | 7개 junk(비-builtin) 치환 | 1 | 빨강 `:1218`. **`hasattr` 단언(`:1224`)은 도달 전** → H-2 |
| M3 | `eval` → `open`(실재 builtin) | 1 | 빨강 `:1218` — `빠짐 ['eval'] / 추가 ['open']` |
| **M4** | **두 집합을 함께** `eval`→`open` | **0** | **초록.** 전체 스위트도 `1242 passed · 68 skipped · 5 deselected · 5 xfailed`로 기준과 **바이트 동일** — 저장소 어디도 못 잡는다. **리더 결정 ⑶ ⑧로 이미 등재된 한계** |
| M5 | `_MAINLINE_PHRASES`에 복사본 | 1 | 빨강 `:1212` — 유일성 단언 |
| M6 | 미관측 문구로 치환 | 1 | 빨강 `:1206` — 결속 단언 |
| **M7**(신규) | `_MAINLINE_HELPERS`의 표 문구 이탈 | 1 | 빨강 `:1206` — 파생 문구가 목록에 없으면 결속 단언이 먼저 잡는다 |
| **M8**(신규) | 표의 테스트 이름을 무관한 테스트로 재지목 | 1 | 빨강 `:1300` — `…의 **문자열 상수**에 structural_problems 가 없다` |

**M4는 새 지적이 아니다.** 원장 「게이트 19 후속」 §2 리더 결정 ⑶이 ⑧(단언 외부 강제자 0)을
**「한계」로 등재하고 수정하지 않기로** 명시했고, 그 처분의 근거(「테스트 파일 안 단언의 밖은
diff 리뷰다」)가 이 실측 결과와 정확히 일치한다. **등재대로 열린 채임을 확인**한 것으로 적는다.

---

## 7. 미실행·확인 불가 항목

### 7-1. 내 프로토콜의 결함 두 건 (실행 중 드러남 — 결과 해석에 영향)

| # | 사실 | 조치 |
|---|---|---|
| ⑴ | **계약 파일이 Gradle 테스트 태스크의 선언 입력이 아니다.** 계약만 바꾸고 같은 명령을 돌리면 `:api:test FROM-CACHE` · **exit 0**이 나온다(실측). 내 초판 지시에는 `--rerun-tasks`가 없었다 | 지시를 정정해 **전 계약 변이를 `--rerun`/`--rerun-tasks`로 재실행**했고, `<testcase>` 수로 실제 실행을 확인했다. §6-1의 값은 정정 후 값이다. **이 결함 자체는 contract-keeper가 6ece404 §5에서 먼저 등재했고 나는 독립으로 부딪혔다 — 내 발견으로 적지 않는다** |
| ⑵ | **Gradle `--tests`는 명령줄의 마지막 태스크에만 걸린다.** `:api:test :infrastructure:test --tests …`는 api를 **무필터 전건**으로 돌린다 | 결과 해석에 유리한 방향이라 그대로 뒀다(더 넓게 돌았다). §6-1의 「전건 117」이 그 결과다. 다만 「지정한 범위를 쟀다」로 읽으면 안 되므로 적는다 |

### 7-2. 재지 않은 것

| 항목 | 사유 |
|---|---|
| **로그인 응답 시간 실측** | C-3은 **코드 경로 대조**로 판정했다. 실제 시간 차를 재지 않았다 — 차가 얼마인지는 심각도 판정에 필요하나 이 회차 범위를 넘는다 |
| **Argon2 세마포어 부하 실측** | S-5·T-3. 구현자도 재지 않았다(§10 자진 등재) |
| **CI에서의 캐시 재사용** | §7-1 ⑴의 실측은 **로컬 증분 + 로컬 빌드 캐시**다. CI 원격 캐시가 같은 지문을 재사용하는지는 관측하지 않았다(contract-keeper §5도 같은 한계를 적었다) |
| **`ktlintCheck`·`detekt`·전체 `build`·Python 게이트 재현** | 구현자 §8이 보고한 exit 0 / 613건 / `1242 passed`를 **재현하지 않았다.** 부분 확인만 했다 — api 117 + infrastructure 95 = 212(§6-1 기준선, 구현자 수치와 일치) · `test_parity_ci_gate.py` 36(§6-3) · 전체 Python 스위트 `1242 passed · 68 skipped · 5 deselected · 5 xfailed`(§6-3 M4 부수 확인). **`5 deselected`는 구현자 §8 행에 없다** — `pyproject.toml:73`의 `addopts = "-m 'not llm'"` 때문이며 문서된 정책(`CLAUDE.md` 테스트 규칙)이라 결함이 아니다. 다만 로컬 수치가 llm 마커 5건을 **제외한 값**이라는 사실은 적어 둔다 |
| **프런트엔드 영향** | `frontend/src/api/`를 보지 않았다. 이 배치가 계약 형태를 바꾸지 않았으므로 대상이 없다고 판단했으나 **확인하지는 않았다** |
| **`privacy-gate` 감사 결과** | 이 회차에 존재하지 않는다. I-8·I-9 축은 **내 리뷰 관점**이며 판정 정본이 아니다. 보안 축 판정이 갈리면 `privacy-gate`를 따른다 |
| **codex 산출물** | **1차(독립) 회차이므로 없는 것이 정상이다.** 실패로 기록하지 않으며 교차 대조표를 만들지 않았다 |

### 7-3. 타 레인 산출물과의 대조 — **교차 대조가 아니다**

리뷰 도중 `contract-keeper`의 2단계 검증(`6ece404`, `03_contract-keeper_auth-verification.md`)이
들어왔다. **이것은 codex 독립 리뷰가 아니라 같은 계열(Claude) 다른 레인의 산출물**이므로 교차
대조로 쓰지 않는다. 내 §2~§4는 그 문서를 읽기 **전에** 기록했고, 대조 결과만 여기 적는다.

| 항목 | 나 | contract-keeper | 처분 |
|---|---|---|---|
| 422·`/auth/me` 401 계약 대조 없음 | **C-1 — 수정 필요** (변이 실행으로 확정) | §1-1 — **심각도 낮음**(기록) | **심각도가 갈린다.** 양쪽 근거 병기, 리더 판정 |
| 이메일 ASCII 좁힘 | K-7 — 좁힘 자체는 위반 아님 · **회귀 장치 0**이 문제 | §6 — **허용 판정** + 조건 3(비ASCII 거절을 재는 단언 없음) | **결론 일치.** 조건 3 ≡ 내 K-7 |
| `failure_uniformity` 타이밍 축 | **C-3 — 조항 안인데 미측정** | §9 — *"계약은 응답의 **동일성만** 요구"*로 **조항 밖**이라 적음 | **정면으로 갈린다.** 계약 `:299-302` 문면이 내 쪽을 지지한다. 리더 판정 |
| 원장 X6 강제자 축 | R-7b — **부분 해소**(auth 2필드) · T-1이 나머지 3필드의 강제력을 위협 | §0 ⑵·§8 — **닫혔다** | **표기가 갈린다.** 게이트 16이 같은 행에서 이미 「해소 → 부분 해소」 정정을 겪었다. 리더 판정 |
| Gradle 캐시 도달 결함 | **못 찾았다** — 프로토콜 정정 중 부딪혔을 뿐(§7-1 ⑴) | **§5 신규 결함으로 등재** | **그쪽 단독 발견.** 내 발견으로 적지 않는다. 마감 권고 Phase 3 종료 전에 동의한다 |
| 명세 케이스 총계 | 30건(주 27 + 하위 3)으로 셈 | §4 — 30건으로 **정정**(1단계 「27건」은 집계 오류) | **일치.** 리더 지시문의 「27건」은 명세 초판 수치다 |
| 음성 대조 N-1·N-1b의 P-7 | 미실행(N-7만 실행) | §3 주 — 구현자 표에 **P-7 누락**, 기록의 불완전 | 그쪽 관측을 그대로 인용한다 |

---

## 8. 리더에게

- **정본은 이 문서가 아니다.** Phase 3 종료 조건 충족 여부는 2차 `03_auth_cross.md`를 근거로 보고한다.
- **2차(교차 종합) 재호출이 필요하다.** 입력: `03_auth_codex-reviewer.md` + 이 파일.
- **표기 정정 1건** — 리뷰 범위 `e91ecdd..fc21750`은 `e91ecdd`를 제외한다. 실제로 본 것은
  `e91ecdd~1..fc21750`이다.
- **판정을 올린 것 4건** — C-3(심각도 · contract-keeper와 정면 충돌) · R-6a(음성 대조 CI 배선이
  리더 결정 ⑴ 동결 대상인가) · H-1(하네스 양성 경로 미실행이 동결 대상인가) · R-7b(X6 표기가
  「해소」인가 「부분 해소」인가 — contract-keeper와 갈림).
