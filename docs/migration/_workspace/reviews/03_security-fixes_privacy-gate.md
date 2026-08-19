# 게이트 20 조치 배치 — 보안 축 재감사 (privacy-gate)

- **대상**: `bf08edd..3c5c8ad` (9 커밋). 핵심 `ea15782`(B-1·C2) · `d22c7df`(C4) · `2adae30`(L-1) · `265b429`(보호 경로 자동 발견)
- **입력**: `reviews/03_security_privacy-gate.md` B-1 해제 조건 3항 · `03_kotlin-implementer_auth-fixes.md`
- **정본**: `migration-safety-gate` I-3·I-5·I-6·I-8·I-9, 계약 `x-auth.failure_uniformity`
- **일자**: 2026-08-19
- **판정 요약**: **B-1 해제.** 통과 5항목 / 수정 필요 1건(M-1, 차단 아님) / 기록 3건(R-1·R-2·L-1 잔여). 확인 불가 0. **신규 차단 0.**

> 모든 판정은 **내가 직접 돌린 실행 결과**다. 구현자 산출물의 실측치를 인용해 닫은 항목은 없다.
> 실측 환경: 일회용 Postgres 컨테이너(`pgvector/pgvector:pg16`, 포트 55433) + `easy-doc-api.jar` 실기동
> + bootJar 클래스패스 직접 탐침 + 일회용 git worktree 3변이.
> 감사 종료 후 `git status --porcelain backend-kotlin` **0건**, 잔여 java 프로세스 **0건**, 컨테이너 제거 완료.

---

## B-1 (로그인 타이밍 열거) — **해제**

해제 조건 3항을 각각 **독립 방법으로** 다시 쟀다.

### ⑴ 더미 PHC 가 현행 정책 파라미터인가 — **통과** (두 방법)

**방법 A — JVM 직접 탐침.** bootJar 를 풀어 `Argon2PasswordHasher` 를 네 정책으로 조립하고
`dummyHash()` 를 읽었다.

```
정책A m=65536,t=3,p=4,s=16,h=32   dummyPHC.params=$argon2id$v=19$m=65536,t=3,p=4   needsRehash(dummy)=false
정책B m=32768,t=3,p=2,s=16,h=32   dummyPHC.params=$argon2id$v=19$m=32768,t=3,p=2   needsRehash(dummy)=false
정책C m=19456,t=2,p=1,s=16,h=32   dummyPHC.params=$argon2id$v=19$m=19456,t=2,p=1   needsRehash(dummy)=false
정책D m=65536,t=3,p=4,s=24,h=48   dummyPHC.params=$argon2id$v=19$m=65536,t=3,p=4   needsRehash(dummy)=false

교차 판정 (다른 정책 해셔가 그 더미를 보면 재해시 대상이어야 한다)
  pol1.needsRehash(pol2.dummy) = true
  pol2.needsRehash(pol1.dummy) = true
  pol1.needsRehash(pol4.dummy) = true   ← salt/hash 길이만 다른 경우도 잡힌다
```

상수 문자열이면 정책 B·C 에서 파라미터가 따라오지 않고 `needsRehash` 가 `true` 가 된다.
`dummyHash()` 의 identity 가 호출마다 같다 — **조립 시 1회 생성**이라 첫 「없는 이메일」 요청이
생성+검증으로 두 배를 쓰지 않는다.

비용까지 대조했다(각 7표본 중앙값):

| 정책 | 더미 검증 | 같은 정책 실해시 검증 | 비 |
|---|---|---|---|
| A | 95.50ms | 105.92ms | 1.109 |
| B | 47.65ms | 46.26ms | 1.030 |
| C | 16.99ms | 17.71ms | 1.042 |

**방법 B — 설정 변경 재기동(게이트 20 에서 쓴 방법).** `--easydoc.auth.argon2.memory-kib=32768
--easydoc.auth.argon2.parallelism=2` 로 재기동:

```
없는 이메일 (더미 PHC)          median=  52.68ms      ← 정책A 때 103.97ms
정책B 로 그 자리에서 가입한 계정 + 틀린 비번   median=  48.50ms
                                        → 비 1.086x
DB 확인: 정책B 하 신규 저장 PHC = $argon2id$v=19$m=32768,t=3,p=2$…
```

더미가 설정을 따라 내려왔다.

### ⑵ 더미에 재해시가 걸리지 않는가 — **통과**

- **구조**: `AuthService.login` 이 `stored == null` 분기에서 `verifyAgainstDummy(password)` 뒤
  **무조건** `throw` 한다(`AuthService.kt:87–92`). `rehashIfOutdated` 는 `stored != null` 경로에만
  있다(`AuthService.kt:100`). 도달 경로가 없다.
- **회귀**: `AuthServiceTest`「계정이 없어도 해시 검증을 **거른다**」가
  `verifiedHashes == [dummy]` **와** `users.rehashed.isEmpty()` 를 같은 실행에서 단언한다.
  반대 방향도 있다 —「계정이 있는 경로는 더미가 아니라 저장된 해시로 검증한다」가
  `verifiedHashes` 에 dummy 가 없음을 단언해 *더미로 「검증한 척」하고 실제 자격증명을 안 보는*
  구현(인증 우회 형태)을 잡는다. 두 방향이 다 있는 것이 좋다.

### ⑶ 회귀가 비율 기준 + 음성 대조 — **통과** (직접 재실측)

HTTP 경계, 워밍업 1건 버림, 각 15표본:

```
case                              codes   median      min       max
없는 이메일                          {401}  103.973ms  100.515  113.890
있는 이메일 + 틀린 비번                {401}  102.256ms   99.730  108.277
있는 이메일 + 맞는 비번                {200}  106.585ms  101.711  113.477

RATIO absent vs wrongpw = 1.017x   (절대 격차 1.72ms)
```

**게이트 20 의 42배 / 95ms 가 1.017배 / 1.72ms 로 소멸했다.** 두 분포의 min–max 구간이
겹치므로 단일 요청은 물론 표본을 쌓아도 판정할 수 없다. 성공/실패도 갈리지 않는다.

L-3b 회귀는 실제로 실행되고 통과한다(api XML, `1.511s`, skipped 0). 판정이 절대값이 아니라
두 경로 중앙값의 **비**이고(`MAX_TIMING_RATIO=4.0`), 시간 축을 요구한다는 사실 자체를
계약 `x-auth.failure_uniformity` 에서 읽는다.

**음성 대조**는 구현자의 worktree 실험을 재검산하는 대신 **독립 축**으로 했다 — 정책을 바꿔
재기동했을 때 없는 이메일 경로의 비용이 따라 움직인다(⑴ 방법 B). 더미 검증이 실제로 도는
증거이고, 도는 것이 없다면 그 값은 움직이지 않는다.

### 남는 채널 판정 (지목받은 넷 + 신규 1)

| 채널 | 실측 | 판정 |
|---|---|---|
| DB 조회 유무 | 1.017배 / 1.72ms | 소멸 |
| 세마포어 대기 | 240 동시에서 absent/present 비 **1.050배**, 500 발생도 무상관(아래 C2) | 없음 |
| 409 signup | 201 107.60ms vs 409 110.48ms → **1.027배** | 없음 (해시를 트랜잭션 밖에서 먼저 하므로 두 경로가 같은 비용을 문다). 409 코드 자체의 노출은 계약 승인 사항 |
| `/auth/me` | 유효 토큰 1.85ms / 위조 서명 0.73ms / 헤더 없음 0.66ms | **오라클 아님** — 갈리는 축이 「서명이 유효한가」이고 유효 서명을 만들려면 비밀키가 필요하다. 계정 존재는 알려 주지 않는다 |
| **신규 — 옛 파라미터 계정** | 아래 R-1 | **기록** |

> **기록 R-1 — 파라미터를 바꾸면 열거 창이 잠시 열린다.**
> 정책 B 로 재기동한 상태에서:
> ```
> 없는 이메일 (더미=정책B)                 median=  52.68ms
> 정책B 계정 + 틀린 비번                    median=  48.50ms    비 1.086x
> 정책A(옛 파라미터) 계정 + 틀린 비번        median=  96.62ms    비 1.834x  (격차 43.9ms)
> ```
> 더미는 **현행 정책**으로 만들고 검증은 **저장 PHC 의 파라미터**로 하므로(I-8 검증 1 이 요구한
> 그대로다), 정책을 바꾼 뒤 **아직 성공 로그인을 하지 않아 옛 PHC 를 들고 있는 계정**은 없는
> 계정과 시간으로 갈린다. 방향은 바꾼 쪽을 따른다(올리면 기존 계정이 더 빠르고, 내리면 더 느리다).
> 소멸 조건은 그 계정의 다음 성공 로그인(재해시)이다.
>
> **차단이 아닌 이유**: 두 요구(같은 검증 비용 / 저장 PHC 에서 파라미터 읽기)가 서로 맞물린
> 자리이고, 어느 한쪽을 포기하지 않고 지우는 방법이 없다. 격차도 42배가 아니라 1.8배다.
> **L-3b 는 이 자리를 절대 보지 못한다** — known 계정을 현행 정책으로 그 자리에서 가입시키기
> 때문이다. 회귀로 덮을 것이 아니라 **운영 지침**으로 남긴다: Argon2 파라미터 변경은
> 재해시가 퍼질 때까지 열거 창을 연다. 리더 판단 사항.

### B-1 판정

**해제.** 해제 조건 3항이 전부 실측으로 닫혔고, 지목받은 잔여 채널 넷 중 어느 것도
계정 존재를 알려 주지 않는다. R-1 은 새 정보이지 B-1 의 재발이 아니다.

---

## M-1 — 더미 PHC 의 **선언된 불변식이 사실이 아니다** (수정 필요 · 차단 아님)

**주장한 곳 셋.**

- `AuthPorts.kt` `dummyHash()` KDoc ⑵ — *"**어떤 비밀번호와도 일치하지 않는다.** `verify` 가
  언제나 `false` 여야 하고, 그래야 재해시 경로에 닿지 않는다"*
- `AuthService.kt:121–123` — *"더미 PHC 는 어떤 비밀번호와도 일치하지 않으므로 언제나 `false` 다"*
- `03_kotlin-implementer_auth-fixes.md` §1 해제조건 2 — *"`verify` 가 언제나 실패하므로
  `rehashIfOutdated` 에 닿지 않는다"*

**실측 (JVM 직접 탐침, 정책 4종 전부에서 동일).** 더미는
`Argon2PasswordHasher.DUMMY_PHC_SOURCE` 상수의 해시다. 그 상수를 `verify` 에 넣으면 **`true`** 다.

```
verify(dummy) for 5 guesses -> false true false false false
                                     ^ DUMMY_PHC_SOURCE 자신
```

**회귀가 이 자리를 보지 못한다.** `Argon2PasswordHasherTest`
「더미 PHC 는 어떤 비밀번호와도 일치하지 않는다 — 재해시 경로에 닿지 않는다」가 단언하는 것은
임의의 두 값(`"correct horse battery"`·`""`)뿐이다. **선언을 깨뜨리는 유일한 입력이 20줄 옆
프로덕션 파일의 `const`** 인데 그 값을 넣어 보지 않는다. 이름이 주장하는 성질에 대해 공허하다.

**지금은 악용 불가.** `verifyAgainstDummy` 는 결과를 버리고 호출부가 무조건 throw 하며,
`stored == null` 이라 발급할 토큰도 없다. 재해시도 제어 흐름상 도달 불가다.

**그래서 왜 남기는가.** 재해시 미도달의 **근거로 적힌 성질**이 거짓이다. 다음 사람이 그 근거에
기대 두 분기를 합치면(`if (!verify(pw, stored?.hash ?: dummy)) throw …` 형태) 성질이 실제로
필요해지고, 그때 이 회귀는 여전히 초록이다. 게이트 20 이 마지막 줄에서 *"코드 주석이 주장하는
바와 런타임 동작이 일치한다"* 로 닫았던 자리가 이번 배치에서 갈렸다.

**회색지대 병기** — 임의로 무해 판정하지 않는다.
- *위반으로 볼 근거*: `CLAUDE.md` Definition of Done 과 게이트 20 의 종결 근거가 「주장과
  런타임의 일치」다. 회귀 이름이 실제 단언 범위보다 넓다.
- *무해로 볼 근거*: 인증 결과가 바뀌지 않고, 고정 더미 비밀번호를 해시하는 것은 Django
  `set_unusable_password` 등 널리 쓰이는 형태다. 그 상수를 알아도 얻는 것이 없다(어떤 계정도
  그 해시를 저장하지 않는다).
- **보수적 판정: 잠정 Minor(수정 필요).** 차단하지 않는다. 최종 처분은 리더.

**해제(수정) 방향 둘.**
- **ⓐ 권고** — 더미를 **기동 시 난수**(예: `SecureRandom` 32바이트)의 해시로 만든다. 조립 1회는
  그대로, 비용도 그대로, **선언이 참이 되고 회귀도 참말이 된다.**
- ⓑ 세 곳의 주석·산출물을 사실대로 고친다(재해시에 닿지 않는 이유는 **제어 흐름**이지
  `verify` 결과가 아니다) + 회귀의 `@DisplayName` 을 실제 단언 범위로 좁힌다.

**수신자**: `kotlin-implementer`(수정), 참조 리더·`migration-reviewer`.
**§5 Phase 7 즉시 중단 기준 해당 여부**: 아니다.

---

## C2 세마포어 초과 500 — **보안 관점 통과** (기록 R-2)

지목받은 셋만 본다. 500↔503 의 구현·계약 판단은 `contract-keeper` 몫이다.

### 새 오라클이 되는가 — **아니다**

240 동시 로그인을 `Barrier` 로 동시에 풀었다(절반 없는 이메일 / 절반 있는 이메일, 순서 셔플):

```
absent   codes={401: 104, 500: 16}  median=4026.4ms
present  codes={401: 100, 500: 20}  median=3835.5ms
RATIO(부하 중) absent/present = 1.050x

500 총 36건 중 absent 16건 — 이항 양측 p ≈ 0.618  (계정 존재와 무관)
```

- **응답 시간**: 부하 중에도 1.05배. 계정 존재를 알려 주지 않는다.
- **500 발생 여부**: 계정 존재와 통계적 무관.
- **본문**: `{"detail":"서버 오류가 발생했습니다"}` — absent·present 가 **같은 바이트**.
  `GlobalExceptionHandler` 의 기존 `InternalError` 고정 문구이고 새 문구를 만들지 않았다. 정보 0.
- **헤더**: 500 에도 `Cache-Control: no-store` · `X-Content-Type-Options: nosniff` 가 붙는다(I-6 유지).
- **대기 상한 값(5000)** 이 응답·로그 어디에도 실리지 않는다(로그 `5000` 적중 0건).
  예외 메시지에는 값이 들어 있지만 백스톱이 `exception::class.java.simpleName` 만 찍는다:
  `처리하지 못한 예외: PasswordHashingOverloadedException` × 36, **스택트레이스 없음.**

### 500 vs 503 의 정보 노출 차이 — **보안상 없다**

둘 다 원인을 말하지 않는 고정 문구이고, 어느 쪽도 계정·자격증명·부하 상태의 내부값을 싣지
않는다. 503 이 클라이언트에게 「재시도 가능」을 더 정확히 알려 주지만, 그 정보는 부하를 만든
당사자에게 이미 관측 가능하다. **이 게이트는 어느 선택도 막지 않는다.** 계약 레인 판단.

> **기록 R-2 — 가용성(불변식 아님).** B-1 수정이 「없는 이메일」 경로를 무료에서 유료로 바꿨고,
> 그만큼 세마포어 포화가 싸졌다. 게이트 20 이 *"함께 판단해야 할 것"* 으로 넘긴 자리다.
> ```
> 60 동시(순차 출발) : 500 0건, 최대 1.89s, /health 중앙값 2.63ms
> 240 동시(동시 출발): 500 36건(15%), 로그인 중앙값 ~3.9s, /health 최대 1244.69ms
> ```
> `/health` 는 끝까지 200 이지만 **최대 1.24초까지 늘어난다** — 게이트 20 의 40 동시 실측에서는
> 1.2ms 를 유지했다. 원인은 permit 이 아니라 **대기 상한 자체**다: 4 permit × 5000ms 는
> 요청 스레드가 5초를 쥔 채 기다리는 것을 허용하므로 Tomcat 스레드(기본 200)가 먼저 마른다.
> 무한 대기보다는 낫고(스레드가 결국 돌아온다) OOM 은 여전히 막힌다. rate limit 을 세울지는
> 게이트 20 이 이미 리더에게 넘긴 항목이고, 여기서는 **수치만 갱신**해 둔다.

---

## C4 Jackson 스칼라 강제 변환 — **통과**

프로브 9종(지목받은 넷 + 5종 추가), 실기동 HTTP:

```
{"email":"c4a…","password":12345678}   -> 422 [{"loc":["body","password"],"msg":"Input should be a valid string","type":"string_type"}]
{"email":true,…}                       -> 422 [{"loc":["body","email"],…}]
{"email":"c4c…","password":null}       -> 422 [{"loc":["body"],"msg":"JSON decode error","type":"json_invalid"}]
{"email":["x"],…}                      -> 422 [{"loc":["body","email"],…}]
{"email":12345,…}                      -> 422 [{"loc":["body","email"],…}]
{"email":"c4f…","password":true}       -> 422 [{"loc":["body","password"],…}]
{"email":"c4g…","password":1.5}        -> 422 [{"loc":["body","password"],…}]
{"email":"c4h…"}                       -> 422 [{"loc":["body"],…json_invalid}]
{"email":"c4i…","password":{"a":1}}    -> 422 [{"loc":["body","password"],…}]
```

- **계정 미생성** — DB 직접 조회에서 `c4*` **0행**(전체 17행이 전부 정상 경로로 만든 계정).
  게이트 20 시점의 「`{"password": 12345678}` → 201」 이 사라졌다.
- **입력값 반향 0** — `12345678`·`true`·`["x"]`·`1.5` 어느 것도 응답 바이트에 없다.
- **Jackson 메시지 미포함** — 응답에 실리는 것은 `loc`(프로퍼티 이름)·고정 `msg`·`type` 뿐이다.
  `bodyReadItem` 이 `MismatchedInputException.path.propertyName` 과 `targetType` 만 읽고
  `exception.message` 를 쓰지 않는다(`GlobalExceptionHandler.kt`).
- **로그 값 0** — 실기동 캡처 209줄에서 `12345678` 0건, `c4*@`·`coerce` 0건, 이메일 0건.

> **관찰(보안 영향 0, 계약 레인).** `{"password":null}` 과 필드 누락이 둘 다
> `loc:["body"]` + `json_invalid` 로 나가 **어느 필드인지 지목하지 않는다.**
> 값 반향이 없으므로 이 게이트는 통과시키되, 계약 `ValidationFailed` 가 의도한 모양인지는
> `contract-keeper` 가 볼 자리다.

---

## 로그 스캐너 · L-1

### 스캐너 — **REAL_EXIT=1**, BLOCK 1건은 기존 오탐

```
$ uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --changed --base bf08edd
검사 범위: 변경분 (bf08edd...HEAD + 작업 트리 + 미추적). 검사 파일 21개.
REAL_EXIT=1
[BLOCK] SECRET-LITERAL 1건 — RequestFieldConstraintLayerTest.kt:229 `PASSWORD = "SignupRequest.password"`
[WARN]  CACHE-HEADER 2건 — 분포 확인용
```

BLOCK 1건은 게이트 20 에서 이미 **오탐**으로 판정한 그 자리다(줄 번호만 172→229 이동).
값이 난수가 아니라 계약 필드 경로 문자열이고, 이번 배치가 만든 것이 아니다. **판정 유지: 오탐.**

### `DUMMY_PHC_SOURCE` — **오탐도 실탐도 아니다. 규칙이 아예 보지 않는다**

`SECRET-LITERAL` 의 식별자 패턴은
`fernet_key|encryption_key|aead_key|cipher_key|jwt_secret|api_key|secret_key|password` 뿐이라
`DUMMY_PHC_SOURCE` 는 **매칭 자체가 일어나지 않는다.** 음성 대조로 확인:

```
const val DUMMY_PHC_SOURCE = "absent-account-uniform-cost"  -> 패턴 미매칭 (규칙이 보지 않음)
const val DUMMY_PHC_SOURCE = "s3cr3t-Xk9#vQ2mLp84"          -> 패턴 미매칭 (난수꼴이어도 안 본다)
const val PASSWORD = "SignupRequest.password"               -> 매칭, looks_like_real_secret=True (엔트로피 3.97)
const val JWT_SECRET = "0123456789abcdef…"                  -> 매칭, looks_like_real_secret=True
```

즉 **「걸리지 않았다」가 「엔트로피 판정으로 걸러졌다」는 뜻이 아니다.**
판정 자체는 옳다 — 그 값은 비밀이 아니고 어떤 계정도 그 해시를 저장하지 않는다.
규칙의 이름 목록을 넓힐지는 스캐너 설계 판단이라 여기서 요구하지 않고 **기록만** 남긴다
(`CLAUDE.md` 「선언한 범위와 실제 도달을 대조한다」 형태의 관찰이다).

### L-1 — **부분 조치. 기록 유지**

`org.springframework.security: INFO` 를 고정해도 **WARN 은 INFO 위**라 억제되지 않는다.
깨진 PHC 2건을 DB 에 주입하고 로그인한 실측:

```
WARN o.s.s.c.argon2.Argon2PasswordEncoder : Malformed password hash
java.lang.IllegalArgumentException: Invalid encoded Argon2-hash
  ... (전체 스택트레이스)
새로 찍힌 로그 139줄 / WARN 2건
```

L-1 이 걱정한 자리 — *"라이브러리 판올림이 메시지에 해시를 싣기 시작하면 조용히 샌다"* —
는 **그대로 열려 있다.** 레벨 고정이 막는 것은 DEBUG/TRACE 가 기본값을 따라 내려오는 경로뿐이고,
유출 후보 출력 자체는 그대로 난다.

현재 값 유출은 **여전히 0** 이다:

| 패턴 | 실기동 로그 209줄 적중 |
|---|---|
| 주입한 `NOT-A-PHC-STRING` | 0 |
| 주입한 `…$TRUNCATED` | 0 |
| 실제 PHC(`$argon2id$v=`) | 0 |
| 평문 비밀번호 | 0 |
| `DUMMY_PHC_SOURCE` 값 | 0 |
| 이메일(`@example.test`) | 0 |
| JWT(`eyJ`) · `Bearer <token>` | 0 |
| C4 주입 정수 `12345678` | 0 |
| users 대상 SQL | 0 |

*양성 대조*: 같은 캡처에 `Tomcat started` 1 · `easy-doc-api` 26 — 로그 경로는 살아 있다.
*argon2 매칭 20건 전수 분해*: 전부 클래스·파일 이름(`Argon2PasswordHasher.kt` 6,
`Argon2EncodingUtils.decode` 2 …), 값 0건.
정책 B 재기동 로그(29줄)도 동일하게 전 패턴 0.

**차단 아님. 기록 유지.** 실효 조치를 원하면 이 로거만 `ERROR` 로 올리거나(진단이 사라진다)
「이 WARN 의 메시지에 PHC 가 실리지 않는다」를 회귀로 묶어야 한다 — **후자를 권고**한다.

---

## 보호 경로 자동 발견 — **통과**

### 게이트 20 항목 5 와의 관계

상충하지 않는다. 게이트 20 이 통과시킨 것은 **단언 ①**(계약이 공개로 선언한 경로를 잠그지
않았는가)이고, 이번에 닫은 것은 **단언 ②의 대상 범위**다. 종전 판은 대상을 수기
`implementedPaths()` 로 들어 교집합했으므로, **컨트롤러만 만들고 두 목록을 다 빠뜨리는 실제
사고 형태(B-6)** 에서 교집합이 그 경로를 밀어내 초록이었다.

다만 기록해 둔다 — 게이트 20 항목 5 의 「좋다」는 **대상 범위를 검사하지 않은 채 내린 평가**였다.
같은 형태를 다시 만들지 않으려면 열거식 목록을 볼 때마다 "이 목록이 어디서 오는가"를 함께 물어야 한다.

### 독립 음성 대조 3변이 (일회용 worktree, `git worktree remove` 로 복원)

| 변이 | 결과 |
|---|---|
| 계약이 보호로 선언한 `/workspaces` 를 서비스하되 `AuthenticatedEndpoints` 에 안 넣음 (**B-6 형태**) | **빨강** — `계약이 보호로 선언했는데 인증이 걸리지 않은 경로: [/workspaces]` (`AuthenticationCoverageContractTest.kt:101`) |
| 계약에 없는 `/not-in-contract` 를 서비스 | **빨강** — `계약이 선언하지 않은 경로를 서비스하고 있다: [/not-in-contract]` |
| **(내가 더한 변이)** `/workspaces` 를 목록에 **넣고** 실기동 | 토큰 없이 `/workspaces` → **401**, `/auth/me` → 401, `/health` → 200 |

세 번째 변이가 필요했던 이유: 계약 테스트가 재는 것은 **목록 멤버십**이지 런타임 인증이 아니다.
그 대조가 없으면 「목록에는 있는데 인터셉터 패턴이 안 걸리는」 상태를 아무도 보지 않는다.
**목록 → 실제 401 까지 도달한다.**

4개 케이스 전부 실행·통과(api XML, skipped 0). 테스트 전용 컨트롤러 제외를 이름 규칙이 아니라
**컴파일 산출물 위치**로 가르고, 유일한 경로 제외인 서블릿 오류 디스패치를 `server.error.path`
에서 읽는 것도 확인했다.

---

## 테스트 실행 결과 (근거)

```
$ ./gradlew :api:test :application:test :infrastructure:test :core:test :worker:test --rerun-tasks
BUILD SUCCESSFUL in 20s / 28 actionable tasks: 28 executed   ← 전부 실행, 캐시 초록 아님

core           tests= 357 failures=0 errors=0 skipped=0
application    tests=  43 failures=0 errors=0 skipped=0
infrastructure tests=  99 failures=0 errors=0 skipped=0
api            tests= 122 failures=0 errors=0 skipped=0
worker         tests=   3 failures=0 errors=0 skipped=0
TOTAL          tests= 624 failures=0 errors=0 skipped=0
```

구현자 산출물의 624 와 일치한다. 핵심 케이스 개별 확인:

```
PASS  1.511s  AuthEndpointReachTest              L-3b 없는 이메일과 있는 이메일의 로그인 응답 시간이 갈리지 않는다
PASS  0.009s  AuthEndpointReachTest              S-9b 숫자·불리언을 문자열 필드에 넣으면 422 배열
PASS  0.002s  AuthenticationCoverageContractTest 서비스 중인 모든 매핑이 계약의 공개·보호 둘 중 하나로 분류된다
PASS  0.002s  AuthenticationCoverageContractTest 보호로 분류된 매핑이 전부 인증 목록에 있고…
PASS  0.001s  AuthenticationCoverageContractTest 한 경로의 모든 오퍼레이션이 같은 security 를 선언한다
PASS  0.002s  AuthenticationCoverageContractTest 아직 구현되지 않은 보호 경로가 목록으로 드러난다
```

---

## 판정 표

| # | 항목 | 판정 |
|---|---|---|
| 1 | **B-1 로그인 타이밍 열거** | **해제** (해제조건 ⑴⑵⑶ 전부 실측으로 닫힘) |
| 1a | 더미 PHC = 현행 정책 파라미터 | 통과 (JVM 탐침 4정책 + 설정 변경 재기동) |
| 1b | 더미에 재해시 미도달 | 통과 (제어 흐름 + 양방향 회귀) |
| 1c | 비율 기준 회귀 + 시간 축 재실측 | 통과 (1.017배 / 1.72ms) |
| 1d | 남는 채널 (DB 조회·세마포어·409·`/auth/me`) | 전부 없음 |
| 1e | 신규 잔여 채널 (옛 파라미터 계정) | **기록 R-1** |
| 2 | **C2 세마포어 초과 500** — 오라클성·본문·헤더·로그 | 통과 |
| 2a | 과부하 배압의 가용성 영향 | **기록 R-2** (불변식 아님) |
| 3 | **C4 스칼라 강제 변환** — 계정 미생성·반향 0·Jackson 메시지 0·로그 0 | 통과 |
| 4 | 로그 스캐너 `--changed --base bf08edd` | REAL_EXIT=1, BLOCK 1건은 기존 오탐 |
| 4a | `DUMMY_PHC_SOURCE` | 규칙이 보지 않음 — 판정은 옳고, 미검사 사실을 기록 |
| 4b | **L-1 security 로그 레벨** | **부분 조치** — WARN 스택트레이스 그대로 출력. 값 유출 0 유지. 기록 유지 |
| 5 | **보호 경로 자동 발견** | 통과 (음성 대조 3변이 + 런타임 401 도달) |
| M-1 | 더미 PHC 의 선언된 불변식이 사실이 아니다 | **수정 필요 (잠정 Minor · 차단 아님)** |

**확인 불가: 없음. 신규 차단: 없음.**

**§5 Phase 7 즉시 중단 기준 해당: 없음.**

Phase 3 `auth` 의 보안 축에서 **B-1 이 막고 있던 종료 조건은 열렸다.** 남는 것은 M-1(수정 권고),
R-1·R-2(운영·리더 판단), L-1 잔여(회귀 권고)이며 어느 것도 배포 승인을 막지 않는다.

---

## 감사 자체의 격리 (규칙 5 / X-9)

- 실기동은 **일회용 컨테이너**(`pg-privgate`, 포트 55433)와 **일회용 포트**(18111·18222)에서 했다.
  사용자의 상시 스택(`easy-doc-postgres-1` 등)에 **접속하지 않았다.**
- 코드 변이는 전부 `git worktree add --detach` 한 일회용 트리에서 했고 `git worktree remove --force`
  로 없앴다. `cp` 복원 없음.
- 종료 상태: `git status --porcelain backend-kotlin` **0건**, 잔여 java 프로세스 **0건**,
  컨테이너 제거 완료, `HEAD` = `3c5c8ad` 그대로.
- **커밋 0건. `00_progress.md` 접촉 0건. `backend-kotlin` 수정 0건.**
- 이 문서에 실제 비밀키·평문 본문·암호문을 옮겨 적지 않았다. 합성 계정과 위치 참조만 썼다.
