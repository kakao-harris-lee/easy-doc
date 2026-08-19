# Phase 3 `auth` — 보안·개인정보 불변식 감사 (privacy-gate)

- **대상**: `05862fa..fc21750` (9 커밋)
- **정본**: `.claude/skills/migration-safety-gate/SKILL.md` I-3·I-5·I-6·I-8·I-9·I-12, `contracts/easy-doc-v1.yaml` `x-auth`·`Unauthorized`·`Conflict`·`x-global-response-headers`
- **일자**: 2026-08-19
- **판정 요약**: **차단 1건** / 통과 6항목 / 기록 3건. 확인 불가 0.

> 모든 판정은 **실행 결과**를 근거로 한다. 코드 읽기만으로 닫은 항목은 없다.
> 실측은 실 Postgres(pgvector:pg16 컨테이너) + `easy-doc-api.jar` 실기동 + HTTP/원시 소켓 경계에서 했다.
> 감사 종료 후 `backend-kotlin` 변경 0건, 잔여 프로세스 0건.

---

## 차단 1건 — B-1. 로그인 응답 시간이 계정 존재를 알려 준다

| 항목 | 내용 |
|---|---|
| **위반 불변식** | 계약 `x-auth.failure_uniformity` (contracts/easy-doc-v1.yaml:299–302) + I-5 계열(자원 존재 은닉) |
| **판정** | **차단** — 운영 데이터에 닿지 않은 상태에서 발견. 즉시 중단 기준은 아니다(배포 전) |
| **근거 코드** | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt:87–88` |

계약 조항 원문:

> 이메일 부재·비밀번호 불일치·토큰 만료·위조·계정 삭제를 **모두 같은 401과 같은 메시지**로 처리한다.
> 구분하면 계정 열거 공격의 단서가 된다. **사용자가 없을 때도 더미 해시로 같은 검증 비용을 치러
> 응답 시간으로도 새지 않게 한다.**

구현은 마지막 문장을 이행하지 않는다. 사용자가 없으면 Argon2 검증에 **도달하기 전에** 반환한다:

```kotlin
val stored =
    users.findByEmail(normalizeEmail(email))
        ?: throw InvalidCredentialsException(INVALID_CREDENTIALS_MESSAGE)   // ← 해시 검증 없이 즉시 반환

if (!passwords.verify(password, stored.passwordHash)) {                     // ← 여기서만 ~110ms 를 쓴다
```

저장소 전수 검색으로 더미 해시 방어가 **어디에도 없음**을 확인했다
(`grep -rn '더미\|dummy\|timing\|타이밍\|동형' backend-kotlin/*/src` → 상수 시간 **비교** 주석 2건뿐, 더미 해시 0건).

### 실측 (HTTP 경계, 실 Postgres, 기본 설정 m=65536·t=3·p=4)

```
case                         code   elapsed_s
없는 이메일                    401    0.057733   ← 최초 1건은 워밍업
없는 이메일                    401    0.002356
없는 이메일                    401    0.002243
없는 이메일                    401    0.002369
없는 이메일                    401    0.002343
있는 이메일 + 틀린 비번          401    0.107729
있는 이메일 + 틀린 비번          401    0.104452
있는 이메일 + 틀린 비번          401    0.095429
있는 이메일 + 틀린 비번          401    0.093313
있는 이메일 + 틀린 비번          401    0.097372
```

**중앙값 2.3ms vs 97ms — 약 42배, 절대 격차 ~95ms.** 네트워크 지터와 혼동될 여지가 없고,
**단일 요청으로** 판정 가능하다. 독립 측정으로 Argon2 검증 1회 비용 = **109.5ms**
(spring-security-crypto 7.1.0 + bcprov 1.85.2, 계약 파라미터 그대로).

### 왜 기존 테스트가 못 잡았나

`AuthContractTest.kt:227–229` (L-3 "자격증명 실패의 두 갈래가 구분되지 않는다")가 **상태 코드·본문
바이트·`WWW-Authenticate` 만** 대조한다. 계약이 요구한 축 셋 중 **응답 시간이 빠져 있다.**
불변식이 세 축인데 단언이 두 축이라, 세 번째 축의 위반이 초록으로 지나갔다.

### 영향 범위

`POST /auth/login`. 공격자는 임의 이메일 목록으로 로그인을 시도해 응답 시간만으로 가입 여부를
전수 판정할 수 있다. 비밀번호를 몰라도 된다. `/auth/signup` 의 409 는 계약이 승인한 노출이지만
(아래 항목 5), 그쪽은 가입 시도라 관측 가능하고 rate limit 대상이 되는 반면 이 경로는 로그인 실패로만
보인다.

### 해제 조건

1. 사용자 부재 시에도 **고정된 더미 PHC 로 `passwords.verify` 를 실제로 수행**한 뒤 같은 예외를
   던진다. 더미 PHC 는 현행 정책 파라미터로 만든 것이어야 한다 — 파라미터가 다르면 비용이 달라져
   격차가 그대로 남는다. **더미 해시에는 재해시를 걸지 않는다**(I-8 검증 2: 실패한 로그인에서 재해시
   금지).
2. **회귀 테스트를 응답 시간 축에 건다.** L-3 에 시간 단언을 더하거나 별도 케이스를 만든다.
   기대값은 계약에서 유도한다(`x-auth.failure_uniformity`). 판정은 절대값이 아니라 **두 경로의 비**로
   하고, 워밍업 1건을 버린 뒤 중앙값을 쓴다.
3. **음성 대조**: 더미 해시를 뺐을 때 그 테스트가 실제로 빨강이 되는지 확인한다.

### 함께 판단해야 할 것 (해제 조건 아님, 설계 주의)

수정하면 **없는 이메일 로그인도 Argon2 세마포어(기본 4 permits)를 소비한다.** 지금은 무료인 경로가
유료가 된다. 아래 항목 1의 실측대로 세마포어는 지연을 늘릴 뿐 프로세스를 죽이지 않지만
(40 동시 요청에서 login 0.10s→1.02s, `/health` 는 1.2ms 유지), 열거 시도가 그대로 로그인 지연으로
번역된다. **더미 해시를 넣지 않는 선택지는 없다**(계약이 명시했다) — 다만 rate limit 을 별도 항목으로
세울지는 리더 판단 사항이다.

**수신자**: `kotlin-implementer` (수정), 참조 `contract-keeper`(계약 조항 소유자)·`migration-reviewer`·리더

---

## 항목별 판정

### 1. I-8 Argon2 — **통과 (단, 위 B-1 이 이 항목에 걸린다)**

| 검증 | 판정 | 근거 |
|---|---|---|
| 알고리즘 argon2id | 통과 | `Argon2PasswordHasher.kt:64` `require(policy.variant == ARGON2ID)`. 실 DB 실측: `$argon2id$v=19$m=65536,t=3,p=4$…` |
| 파라미터가 설정에서 옴 | 통과 | `AuthConfiguration.kt:49–74` `easydoc.auth.argon2.*`. **실측 검증**: 환경변수 override 가 실제 저장 PHC 를 바꿨다 |
| 라이브러리 기본값 미사용 | 통과 | `defaultsForSpringSecurity_v5_8()`(16MiB·p=1) 미사용. 계약 `x-auth.password_hash` 값과 일치 |
| `version` 을 설정으로 열지 않음 | 통과 (좋은 판단) | `Argon2PasswordHasher.kt:68–70`. 인코더가 v1.3 으로만 해시하므로 다른 값이면 **매 로그인이 재해시 대상**이 된다. 조립 시점에 끊었다 |
| **전체 파라미터 집합 동등성** (필수조치 A) | **통과** | `Argon2Policy.matches` (`Argon2PasswordHasher.kt:25–32`) 가 7축 전부 비교. `upgradeEncoding` 미사용 |
| 검증 파라미터를 PHC 에서 읽음 | 통과 | 실측: 옛 파라미터로 만든 해시로 로그인 **200** |
| 재해시는 로그인 **성공** 시에만 | 통과 | 실측(아래) |
| 재해시 실패가 로그인을 막지 않음 | 통과 | `AuthService.kt:120–141` best-effort + `AuthServiceTest` "재해시 실패가 로그인을 막지 않는다" |
| PHC 파싱 오류 → 500 아닌 401 | 통과 | 실측(아래) |
| 동시 실행 수 제한 | 통과 | 실측(아래) |
| **타이밍 동형성** | **차단** | B-1 |

**실측 — 필수조치 A (재해시 판정의 전체 파라미터 동등성).** 설정을 **낮춰서**(m 65536→32768,
p 4→2) 재기동한 뒤:

```
  [before]
    rehash-a@example.test -> m=65536,t=3,p=4
    rehash-b@example.test -> m=65536,t=3,p=4
  A: 성공 로그인 -> 200
  B: 실패 로그인 -> 401
  [after]
    rehash-a@example.test -> m=32768,t=3,p=2     ← 성공한 쪽만 갱신
    rehash-b@example.test -> m=65536,t=3,p=4     ← 실패한 쪽은 그대로
```

이 케이스는 **Spring `upgradeEncoding` 이었다면 둘 다 놓쳤을 자리다**(그쪽은 "미만"만 본다).
파라미터를 **낮춘** 경우와 `parallelism` 변경을 동시에 걸어 두 결함을 한 번에 재현했다.
동시에 ① 재해시가 성공 시에만 일어나고 ② 옛 파라미터 해시로 로그인이 되는 것(PHC 에서 읽는다)이
같은 실행에서 확인된다.

**실측 — PHC 파싱 오류.** DB 의 `password_hash` 를 직접 훼손하고 로그인:

```
  깨진 해시(NOT-A-PHC-STRING) -> 401 {"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}
  잘린 PHC($argon2id$v=19$m=...$TRUNCATED) -> 401 {"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}
```

500 이 아니라 401 이고, 문구도 정상 실패와 같다. 원인이 응답으로 갈라 나가지 않는다.

**실측 — 동시 실행 제한 (I-8 검증 5).** 기본 `maxConcurrentHashes=4`, 40 동시 로그인:

```
  40건 동시 진행 중 /health : 0.001186s / 0.001284s / 0.001459s
  같은 시점 login          : 1.020483s   (한산할 때 ~0.10s)
```

큐잉이 이론값(40/4 × 0.1s = 1.0s)과 정확히 맞고, **`/health` 가 영향을 받지 않는다.**
메모리는 4 × 64MiB = 256MiB 로 묶인다. OOM 벡터가 지연 저하로 치환됐다 — 의도한 거래다.

### 2. I-9 JWT — **통과 (전건)**

HTTP 경계에서 위조 토큰 14종을 던졌다. **전건 401, 본문 문구 동일.**

```
  alg=none (서명 없음)            -> 401
  alg=none (서명 채움)            -> 401
  HS256 + 다른 키                 -> 401
  alg=HS512 혼동                  -> 401
  alg=RS256 헤더 위조             -> 401
  exp 누락                        -> 401
  sub 누락                        -> 401
  typ 누락                        -> 401
  typ=refresh                     -> 401
  sub 비UUID                      -> 401
  exp 1초 경과  (skew 0)          -> 401
  exp 30초 경과 (기본 60s 함정)    -> 401
  exp 59초 경과 (기본 60s 함정)    -> 401
  유효 서명 + 없는 사용자 sub      -> 401
```

- **clock skew 0 (필수조치 B) — 통과.** `-30s`·`-59s` 는 Nimbus/Spring 기본값 60초를 그대로 뒀다면
  **통과했을** 토큰이다. 둘 다 401 이므로 허용 오차가 실제로 0 이다.
  `JwtAccessTokens.kt:127–131` 이 검증기를 쓰지 않고 직접 판정한다.
  회귀는 `AuthEndpointReachTest` M-6b 가 계약값에서 기대를 **유도**해 고정한다(오차를 바꾸면 뒤집힌다).
- **알고리즘 고정 — 통과.** `JwtAccessTokens.kt:104–107` 이 서명 검증 **전에** `alg` 를 확인한다.
- **클레임에 개인정보 없음 — 통과.** 실 발급 토큰 디코드:
  `header={'alg':'HS256'}`, `payload={'exp':…, 'sub':'2272f3e4-…', 'typ':'access'}`.
  키 집합이 계약 `x-auth.claims` 와 **정확히** 일치하고 이메일 없음.
- **비밀키 하한 (I-9 검증 3 / I-12) — 통과.** 실기동 3회:

  | 설정 | `/health` | signup | login | me |
  |---|---|---|---|---|
  | 미설정 | 200 | **503** | **503** | **503** |
  | 31바이트 | 200 | **503** | **503** | **503** |
  | 32바이트 | 200 | 201 | 200 | 401 |

  경계가 정확히 32 이고, 문구는 계약 `ServiceUnavailable.no_jwt` 와 같은 고정 문자열
  `"인증이 설정되지 않았습니다"` 다(길이도 값도 안 실린다 — `JwtAccessTokens.kt:158–162`).
  기동은 막지 않는다(계약이 정한 대로). **키 값의 로그·응답 유출 0건.**
- **실패 문구 두 갈래 — 통과.** 헤더 부재 `"인증이 필요합니다"` / 그 외 전부
  `"이메일 또는 비밀번호가 올바르지 않습니다"`. 계약 `Unauthorized` 의 `no_header`·`invalid_token`
  두 예시와 일치하고, **무효 사유는 구분되지 않는다.**

### 3. 로그 불변식 (I-3) — **통과 (기록 2건)**

**스캔 — 실제 명령과 종료 코드:**

```
$ uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py \
      --changed --base 05862fa --no-fail
검사 범위: 변경분 (05862fa...HEAD + 작업 트리 + 미추적). 검사 파일 41개.
REAL_EXIT=0
```

- `[BLOCK] SECRET-LITERAL` 1건 — `RequestFieldConstraintLayerTest.kt:172`
  `PASSWORD = "SignupRequest.password"`. **오탐.** 값이 난수가 아니라 **계약 필드 경로 문자열**이다
  (`x-request-field-constraints.fields[].field` 의 식별자). `--no-fail` 없이는 exit 1 이 됐겠지만
  실질 BLOCK 은 0 이다.
- `[WARN] CACHE-HEADER` 36건 — 규칙 설계상 분포 확인용. 누락 판정은 항목 4 에서 실측으로 했다.

**로그 실측 ①(테스트 경로).** Gradle 테스트 XML 의 `system-out`/`system-err` 121,463자.

*양성 대조 먼저* — 이 캡처가 실제 앱 로그를 담고 있는가:
`'Tomcat started'`×6, `'HikariPool'`×12, `'easy-doc-api'`×253, `'Started '`×11. **담고 있다.**

| 패턴 | 적중 |
|---|---|
| 이메일 유사 | **0** |
| JWT (`eyJ…`) | **0** |
| argon2 PHC | **0** |
| 비밀번호 픽스처 | **0** |
| users 대상 SQL | **0** |
| `Bearer <token>` | **0** |

**로그 실측 ②(실서버).** 가입·로그인·위조 토큰 14종·깨진 입력·원시 소켓 7종 등 약 50요청 후
실기동 로그: 이메일 0, JWT 0, PHC 0, 평문 비밀번호 0.

*음성 대조* — 로그 경로가 죽어 있어서 0 이 나온 것은 아닌가: 깨진 PHC 를 주입하자 **278줄이 새로
찍혔다.** 경로는 살아 있다. 그리고 **그 278줄에도 값은 0** 이었다. `argon2|correct-horse|NOT-A-PHC`
매칭 26건을 전수 분해한 결과 **전부 클래스·파일 이름**이다:

```
  12 Argon2PasswordHasher.kt      4 argon2.Argon2PasswordEncoder.matchesNonNull
   4 Argon2PasswordHasher.verify  4 Argon2EncodingUtils.java  ... (값 0건)
  실제 PHC 값($argon2id$v=) : 0
  평문 비밀번호              : 0
  주입한 해시 문자열          : 0
```

**오류 본문 반향 — 0.** 카나리아 비밀번호·이메일을 다섯 경로에 주입했으나 응답 바이트에서
비밀번호 0건. 이메일 1건은 **성공 201 의 `UserResponse`** 였고, 계약
`components/schemas/UserResponse.required=[id,email]` 이 담도록 정한 값이다 — 반향이 아니다.

타입 수준 방어도 확인했다: `PasswordHash.toString()` → `**********`(길이도 안 알려 준다),
`StoredUser`·`IssuedAccessToken` 은 `data class` 가 아니고 `toString()` 재정의,
`SignupRequest`/`LoginRequest`/`TokenResponse` 도 `toString()` 재정의.
`application.yml:53–59` 가 `org.springframework.web: INFO` 를 고정하고
`server.error.include-*` 를 전부 껐다.

> **기록 L-1 (개선 권고).** `Argon2PasswordEncoder` 가 **우리 통제 밖에서** WARN + 전체 스택트레이스를
> 찍는다(`o.s.s.c.argon2.Argon2PasswordEncoder : Malformed password hash`). 현재 예외 메시지는 상수
> `"Invalid encoded Argon2-hash"` 라 값 유출은 없으나, `application.yml` 이
> `org.springframework.security` 로그 레벨을 **고정하지 않는다.** 라이브러리 판올림이 메시지에 해시를
> 싣기 시작하면 조용히 유출된다. 레벨을 명시하거나 이 자리를 회귀로 묶어 두기를 권고한다.

> **기록 L-2 (개선 권고).** Tomcat 이 400 응답 로그에 **요청 대상을 그대로 찍는다**
> (`Invalid character found in the request target [/auth/me<x> ]`). 지금은 자격증명이 헤더에만 실려
> 무해하지만, 토큰·이메일이 쿼리스트링에 오는 엔드포인트가 생기면 그 순간 유출 경로가 된다.
> Phase 4 이후 새 엔드포인트를 설계할 때의 제약으로 남긴다.

### 4. 사적 응답 헤더 (I-6) — **통과 (HTTP 경계 실측)**

**층 확인**: 인터셉터가 아니다. `PrivateResponseHeadersConfig.kt` 가 **2층**으로 붙인다 —
Tomcat Engine 밸브(`PrivateResponseHeadersValve`, 서블릿 미도달 응답까지) + 서블릿 필터
(`HIGHEST_PRECEDENCE`, CORS 필터보다 앞). 둘 다 `add` 가 아니라 **`set`**.

**실측 ① `/auth/*` 13개 상태 변종** — 전건 `no-store` 1개, `nosniff` 1개, `Cache-Control` 헤더 **개수 1**
(이중 부착 0):

```
signup 201 / signup 409 / signup 422(형식) / signup 422(필드누락) /
login 200 / login 401 / me 200 / me 401(헤더없음) / me 401(위조토큰) /
415 / 405 / 404 / 깨진 JSON(422)
→ 전건 no-store=1 nosniff=1 CC헤더수=1
```

**실측 ② 원시 소켓 7종** — 서블릿 필터 체인이 **시작조차 하지 않는** 요청(과거 근거 1번의 그 자리):

```
요청 대상에 금지문자 <      HTTP/1.1 400   no-store=1 nosniff=1
콜론 없는 헤더 줄           HTTP/1.1 400   no-store=1 nosniff=1
요청 줄이 쓰레기            HTTP/1.1 400   no-store=1 nosniff=1
Host 없는 HTTP/1.1        HTTP/1.1 400   no-store=1 nosniff=1
알 수 없는 HTTP 버전        HTTP/1.1 505   no-store=1 nosniff=1
알 수 없는 메서드           HTTP/1.1 405   no-store=1 nosniff=1
헤더 상한 초과              HTTP/1.1 400   no-store=1 nosniff=1
```

밸브 층이 실제로 도달한다. 선언한 범위("모든 응답")와 실제 도달 범위가 같다.

### 5. 소유권 은닉·열거 — **통과 (B-1 제외)**

- **signup 409 는 계약이 정한 것이다.** 계약 파일 파싱 결과
  `POST /auth/signup responses=['201','409','422','500','503']` 이고
  `components/responses/Conflict.examples.duplicate_email` 이 문구까지 못박았다.
  구현 위반이 아니다. 이메일 존재가 이 경로에서 노출되는 것은 **승인된 설계**다.
- **login 실패 문구가 존재/비밀번호를 구분하지 않는다** — 통과. 본문 바이트가 동일함을
  `AuthContractTest` L-3 이 단언하고 실측으로도 확인했다. **단, 시간 축은 갈린다 → B-1.**
- **사용자 ID 노출 범위** — `UserResponse{id,email}` 로 계약 `required` 와 정확히 일치.
  `created_at`·`password_hash` 미포함. `User` 도메인 타입 자체가 해시를 담지 않고
  (`core/user/User.kt:19–23`) 검증용은 `StoredUser` 로 갈라 놨다.
- **보호 경로 목록** — `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS = ["/auth/me"]` 가
  계약의 `security` 선언(`/auth/me` 만 `HTTPBearer`)과 일치하고,
  `AuthenticationCoverageContractTest` 가 계약 파일을 읽어 **양방향** 대조한다(열거 목록을
  손이 아니라 계약이 지키는 구조 — 좋다).
- **인증이 입력 검증보다 먼저** — 인터셉터 `preHandle` 이 인자 해석보다 앞이라 성립.
  필터가 아니라 인터셉터라 계약 밖 경로가 401 이 아닌 **404** 로 남는다(실측 확인).

### 6. 저장 — **통과**

- **컬럼**: `password_hash character varying(255)`. 실 PHC 길이 **97**
  (`$argon2id$v=19$m=65536,t=3,p=4$<22>$<43>`) — 여유 158. 파라미터를 올려도 salt/hash 길이를
  키우지 않는 한 상한에 닿지 않는다.
- **평문 0**: DB 직접 조회에서 `password_hash LIKE '%correct-horse%'` → **0행**.
- **`ck_users_email_lowercase` × Kotlin `lowercase()` 갈림 — 닫혀 있다.**
  `CredentialRules.kt:55–61` 의 이메일 정규식이 **ASCII 로 한정**하므로 Kotlin `lowercase()`(유니코드)와
  PostgreSQL `lower()`(로캘 의존)가 갈릴 입력이 DB 에 도달하지 못한다. **실측 6종 전부 422,
  CHECK 위반 500 경로 0건**:

  ```
  TEST@ÉXAMPLE.com        -> 422 {"detail":"이메일 형식이 올바르지 않습니다"}
  İSTANBUL@example.test   -> 422  (터키어 점 있는 I — Kotlin 이 결합문자로 푸는 자리)
  ＦＵＬＬ@example.test      -> 422  (전각)
  user@münchen.de         -> 422  (IDN)
  ＡＢＣ@ＤＥＦ.com          -> 422
  Σ@example.test          -> 422  (그리스 문자)
  ```

  코드 주석이 이 선택의 사유와 넓힐 때의 조건(정규화 방식과 제약을 함께 바꿔야 한다)까지 적어 뒀다.
- **DB 예외 번역**: `JdbcUserRepository.kt:77–84` 가 `DuplicateKeyException`·
  `DataIntegrityViolationException` 을 도메인 예외로 갈아 끼우고 **원인 체인을 끊는다.**
  PostgreSQL 제약 위반의 `DETAIL` 에는 실패한 행 전체(이메일·해시)가 실리므로 필요한 조치다.

### 7. X-9 파일 격리 / 규칙 5 — **통과**

- 테스트 소스의 `File`/`Files` 접근 7곳을 전수 확인했고 **전부 읽기**다 — 계약 파일
  (`ContractSpec.kt:42`), 컴파일 산출 디렉터리(`RequestFieldConstraintLayerTest.kt:155`),
  parity fixture(`ParityDeclarationSyncTest.kt`), 소스 트리 스캔(`ProvenanceCreationSitesTest.kt`).
  `writeText`·`writeBytes`·`Files.write`·`createTempFile`·`deleteRecursively` **0건**.
- 감사 실행 자체의 격리: 모든 실측은 `/private/tmp/.../scratchpad` 와 임시 Docker 컨테이너에서 했다.
  종료 후 `git status --porcelain backend-kotlin` → **0건**, 잔여 java 프로세스 **0건**,
  컨테이너 제거 완료.

---

## 테스트 실행 결과 (근거)

```
$ ./gradlew :api:test :application:test :infrastructure:test :core:test --rerun-tasks
BUILD SUCCESSFUL in 20s / 23 actionable tasks: 23 executed

api:            tests=117  failures=0  errors=0  skipped=0
application:    tests=41   failures=0  errors=0  skipped=0
infrastructure: tests=95   failures=0  errors=0  skipped=0
core:           tests=357  failures=0  errors=0  skipped=0
TOTAL:          tests=610  failures=0  errors=0  skipped=0
```

`--rerun-tasks` 를 쓴 이유: 최초 실행이 `UP-TO-DATE` 로 건너뛰었다. 캐시 결과는 실행 증거가 아니다.

---

## 판정 표

| # | 항목 | 판정 |
|---|---|---|
| 1 | I-8 Argon2 (알고리즘·설정·전체 파라미터 동등성·재해시 시점·PHC 오류·동시성) | 통과 |
| 1t | I-8 타이밍 동형성 | **차단 (B-1)** |
| 2 | I-9 JWT (skew 0·alg 고정·클레임·키 하한·실패 균일성) | 통과 |
| 3 | 로그 불변식 (I-3) | 통과 (기록 L-1·L-2) |
| 4 | 사적 응답 헤더 (I-6) | 통과 |
| 5 | 소유권 은닉·열거 | 통과 (409 는 계약 승인) |
| 6 | 저장 (스키마·CHECK 갈림·평문) | 통과 |
| 7 | X-9 파일 격리 / 규칙 5 | 통과 |

**확인 불가: 없음.**

Phase 3 `auth` 는 **B-1 해제 전까지 종료 조건을 닫을 수 없다.** 나머지는 I-항목 기준으로 통과이며,
특히 필수조치 A·B 는 둘 다 **실측으로** 확인됐다 — 코드 주석이 주장하는 바와 런타임 동작이 일치한다.
