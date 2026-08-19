# 게이트 21 (`03_auth-fixes`) 교차 종합 조치 — 구현 산출물 (2차)

**작성:** kotlin-implementer / **일자:** 2026-08-19
**입력:** `reviews/03_auth-fixes_cross.md`(정본) · `reviews/03_security-fixes_privacy-gate.md`(M-1·R-2·L-1) ·
`reviews/03_auth-fixes_codex-reviewer.md` §3(C-1·C-2·C-3) · `03_contract-keeper_auth-fixes-verdict.md`(§2-3·§3-4)
· 리더 판정 ⓐ·ⓔ·ⓖ 및 SEC-1·L-1 처분
**범위:** `d04ad98..HEAD` (8커밋) — `00_progress.md` 무접촉 · `contracts/` 무접촉 · `app/` 무접촉

> 값 전사를 하지 않는다. 문구·상한·헤더 값·클레임 값은 파일과 행으로 지목한다.

---

## 0. 조치 요약

| # | 항목 | 관점 | 처방 | 상태 |
|---|---|---|---|---|
| 1 | **codex C-1** 보호 경로 자동 발견이 경로 단위 투영 | codex high | `(경로, 메서드)` 투영으로 계약 오퍼레이션과 정확 일치 | **닫힘 — 변이 확인** |
| 2 | **M-1** 더미 PHC 의 선언된 불변식이 거짓 | pg + codex | 원문을 기동 시 난수로 + 회귀를 **탐지형**으로 교체 | **닫힘 — 변이 확인** |
| 3 | **KTL-2 ≡ R-2** 대기 상한 5s 무근거 | Claude + pg | 250ms + 유도 근거 KDoc | **닫힘(값·근거)** · 재실측은 §3-2 |
| 4 | **TST-2** 배압 응답 HTTP 회귀 0건 | Claude + 2보강 | `PasswordHashingBackpressureReachTest` 신설 | **닫힘** |
| 5 | **SEC-3 + RCH-2** L-1 처방이 지목 위험을 못 막고 장치도 0 | Claude + pg | 레벨 억제 대신 **탐지 회귀** + `application.yml` 주석 정정 | **닫힘 — 변이 확인** |
| 6 | **codex C-2 (i)** 누락·명시 `null` 이 깨진 JSON 과 바이트 동일 | 3관점 | 전역 `Nulls.FAIL` + 예외 타입으로 갈래 분기 → `missing` | **닫힘** |
| 7 | **codex C-2 (ii)** 루트 배열이 내부 DTO 클래스명 노출 | codex 1관점 | `typeLabelOf` 마지막 갈래에서 클래스명 제거 + HTTP 경계 실측 | **닫힘** |
| 8 | **TST-3 ≡ C-2 (iii)** S-9b 값 미단언 | 3관점 | `loc`·`msg`·`type` **값** 단언 + S-9c·S-9d 신설 | **닫힘** |
| 9 | **TST-1 ≡ codex C-3** L-3b 절대 하한 없음·문턱 무근거·순차 5표본 | 2관점 | 절대 하한 + 교차 측정 + 표본 11 + 문턱 1.5 + 수치 출력 | **닫힘 — 변이 확인** |
| 10 | **SEC-4 ≡ ck §1-3** 배압이 「처리하지 못한 예외」 ERROR | 2관점 | 코드 형태 유지, **KDoc 을 사실대로** (리더 판정 ⓐ) | **닫힘(문면)** |
| 11 | **ck §3-4** 계약 7종 vs 열거자 5개 | ck 1관점 | 원시 소켓 **선측정** → 1종 추가(6) · 1종은 **계약이 틀림(G1)** | **부분 — §4에 G1 등재** |
| 12 | 종전 산출물 §0 ②·⑧ 「닫힘」 과대 표기 | Claude TST-4 계열 | §5 에 정정표 | **정정 완료** |

**코드 작업 없음(리더 판정):** ⓐ CON-1 의 500 유지 · ⓔ SEC-2(signup 409) · SEC-1 ≡ R-1(운영 지침 등재).

**커밋(논리 단위)**

| 커밋 | 항목 |
|---|---|
| `07a8bc5` | 1 (C-1) |
| `83791bd` | 2 (M-1) |
| `92a81aa` | 3·4 (대기 상한 + 배압 HTTP 회귀) |
| `0cb0d0b` | 5 (L-1 탐지형) |
| `b97899c` | 6·7·8·10 (C-2 세 갈래 + KDoc 정정) |
| `6fecf9c` | 9 (TST-1) |
| `d7848ad` | 11 (§3-4 측정 결과) |
| `6b212a6` | detekt·ktlint 정리 |

---

## 1. 실결함 셋 — 변이로 재현하고 고친 뒤 되돌려 확인

> 전부 **일회용 worktree**(`git worktree add --detach /tmp/edw`)에서 했다. `cp` 복원 없음 —
> 되돌리기는 `git checkout HEAD -- .` 이고, 마지막에 `git status --porcelain` 0건을 확인한 뒤
> `git worktree remove --force` 로 없앴다(`git worktree list` 에 본 트리 1개만 남는다).
> 변이 6건: X-1(POST /health) · X-2(더미 원문을 상수로) · X-3(더미 검증 제거) ·
> X-4(argon2 파라미터 낮춤) · X-5(저장 PHC 로그) · X-6(캡처를 빈 로거로).
> 그중 넷은 **옛 판/새 판을 같은 변이 위에서 각각** 돌려 갈림을 확인했다.

### 1-1. C-1 — 경로 단위 투영이 계약 밖 **메서드**를 놓친다

**변이:** `HealthController` 에 `@PostMapping("/health")` 추가(계약에는 `GET /health` 만 있다).

| 대상 | 결과 |
|---|---|
| **수정 후 판**(`07a8bc5`) | **빨강 1** — `계약이 선언하지 않은 오퍼레이션을 서비스하고 있다: [POST /health] — 보호인지 공개인지 판정할 근거가 없다` (`AuthenticationCoverageContractTest` 「서비스 중인 모든 (경로, 메서드) 오퍼레이션이 …」) |
| **수정 전 판**(`07a8bc5^` 의 두 파일을 같은 변이 위에 되돌림) | **BUILD SUCCESSFUL — 초록** |

**같은 변이가 옛 판에서 초록, 새 판에서 빨강**이다. codex 가 계약 YAML 투영으로 예측한
`operation_gap=[('POST','/health')]` 가 실제 매핑 표에서도 성립함을 확인했다.

**부가 변이 X-4b(같은 케이스의 반대쪽 확인).** 이 배치의 다른 두 변이 축은 종전 판에서 이미
빨강이었고(계약 밖 **경로** 추가·목록 비우기 — Claude X-6~X-8, privacy-gate 변이 1~3),
이번 변경으로 그 셋이 초록으로 뒤집히지 않았음을 전체 실행(§6)으로 확인했다.

**구현.** `servedPaths(): Set<String>` → `servedOperations(): Set<Pair<String, String>>`.
계약 쪽도 `contractOperationsBySecurity` 로 짝을 유지한다. **메서드 조건이 빈 매핑**
(`@RequestMapping("/x")`)은 계약 어휘 전부로 펼친다 — 실제로 모든 메서드를 받으므로 계약이
선언하지 않은 메서드가 분류 실패로 드러나는 것이 옳다. 메서드 어휘의 정본은
`ContractSpec.HTTP_METHODS` 한 곳이다(두 벌이 되면 어휘 쪽에서 같은 형태가 재발한다).

**보호 목록 대조는 경로 패턴 단위를 유지했다** — 인터셉터가 경로로 걸기 때문이고, 그 전제
(한 경로 안에서 메서드마다 보호가 갈리지 않는다)는 같은 파일의 기존 케이스가 지킨다.
전제가 깨지는 날 그 케이스가 먼저 빨개진다.

**남는 것(이 배치 밖).** codex C-1 후단(Java 컨트롤러·`RouterFunction`·다른 `HandlerMapping`)
≡ Claude RCH-4·RCH-5 는 **손대지 않았다.** 오늘 도달 0(`.java` 소스 0건, `RouterFunction` 0건)
이고 cross §10-5 가 마감을 「다른 모듈/Java/RouterFunction 커밋」으로 잡았다.

### 1-1b. TST-1 — 두 변이가 「옛 판 초록 / 새 판 빨강」을 갈랐다

| 변이 | 옛 판(`6fecf9c^`) | 새 판 | 관측값 |
|---|---|---|---|
| **X-3** `AuthService.login` 에서 `verifyAgainstDummy(password)` 제거 | 빨강(비 축) | **빨강** | 없는 이메일 2.9ms / 틀린 비밀번호 96.8ms → **비 32.8** |
| **X-4** 테스트 프로파일에서 argon2 를 m=1024·t=1·p=1 로 낮춤(= 「흔한 최적화」) | **초록** | **빨강** | 2.6ms / 2.6ms → **비 1.021** — 비만 보면 통과다. 절대 하한이 잡는다 |

X-4 가 TST-1 이 지목한 공허 통과의 재현이다. **비는 1.021 로 완벽해 보이는데 아무것도 지키고
있지 않은 상태**이고, 옛 판은 그것을 초록으로 통과시켰다.

### 1-2. M-1 — 더미 PHC 의 선언된 불변식이 거짓

**변이:** `randomDummySource()` 를 되돌려 `const val DUMMY_PHC_SOURCE` 를 해시하게 한다.

| 대상 | 결과 |
|---|---|
| **수정 후 회귀** | **빨강 1** — `더미 PHC 의 원문이 코드 상수(absent-account-uniform-cost)다 — 아는 값으로 통과하는 해시다` |
| **수정 전 회귀**(같은 변이 위) | **BUILD SUCCESSFUL — 초록** (privacy-gate 가 지적한 「공허함」의 재현) |

**구현 둘.**

1. **원문을 기동 시 난수로.** `SecureRandom` 32바이트의 Base64 를 해시한다
   (`Argon2PasswordHasher.randomDummySource`). 조립 1회 생성·정책 추종은 그대로다.
2. **회귀를 추측 나열에서 탐지로.** 종전 케이스는 임의의 두 값만 넣어, 선언을 깨뜨리는
   **유일한 입력이 20줄 옆 `const`** 인 상태를 보지 못했다. 지금은 프로덕션 클래스가 들고
   있는 **문자열 상수 전부**를 리플렉션으로 모아 입력으로 넣는다. 상수가 하나도 안 잡히면
   탐지가 공허하므로 그 경우도 실패시킨다.

**비용 동일 확인**(privacy-gate 의 비 1.03~1.11 방법 재사용, 운영 파라미터 m=65536·t=3·p=4,
각 7표본 중앙값):

```
PROBE dummy=96.48ms real=91.03ms ratio=1.060
```

privacy-gate 실측 대역(1.03~1.11) 안이다. 난수 원문으로 바꿔도 비용은 움직이지 않는다
(Argon2 비용은 원문 길이가 아니라 파라미터가 정한다).

**주장한 세 자리를 사실대로 고쳤다.** `AuthPorts.dummyHash()` KDoc ⑵ / `AuthService.verifyAgainstDummy`
/ `Argon2PasswordHasher.dummy`. 재해시에 닿지 않는 근거를 **`verify` 결과가 아니라 제어 흐름**
으로 다시 적었다 — 두 분기를 합치는 변경에서 그 성질이 실제로 필요해지는 자리를 명시했다.

### 1-3. L-1 — 레벨 고정이 지목 위험을 막지 못한다

**변이 둘(X-5·X-6)의 결과와 캡처 내용은 §3-3 에 원문으로 남겼다.** 요약 —
저장 PHC 를 찍는 로그 한 줄을 넣으면 **빨강**이고, 캡처 대상을 빈 로거로 바꾸면
**양성 대조가 빨강**이다.

**처방.** `PasswordHashLogLeakReachTest` 신설 — 깨진 PHC 2종(`NOT-A-PHC-STRING`,
잘린 `$argon2id$…$TRUNCATED`)을 DB 에 주입하고 로그인한 뒤, 그 사이 찍힌 **모든 로그**
(메시지 + 예외 체인 + 스택 프레임)를 훑어 유출 후보 4종이 0건인지 본다. **로거를 가리지
않으므로** 다른 라이브러리가 같은 것을 흘려도 잡힌다.

**은폐로 닫지 않았다.** `org.springframework.security` 를 `ERROR` 로 올리는 안은 진단을
없애고 위험을 보이지 않게 할 뿐이다. `application.yml` 의 그 한 줄은 **남기되 주석을
사실대로** 고쳤다 — 그 고정이 막는 것은 DEBUG/TRACE 가 기본값을 따라 내려오는 경로 하나이고,
L-1 이 걱정한 WARN 경로는 **회귀가 정본**이라고 그 자리에 적었다.

---

## 2. 계약 준수 — C-2 세 갈래

### 2-1. (i) 누락·명시 `null` — **기제를 실측으로 확정한 뒤** 고쳤다

종전에는 셋이 바이트 동일이었다(`{"loc":["body"],"msg":"JSON decode error","type":"json_invalid"}`).
기제는 codex 서술대로 — Kotlin 생성자 널 검사의 NPE 가 `ValueInstantiationException`(경로 없음)
으로 감싸여 `MismatchedInputException` 탐색에 걸리지 않는다.

**고치기 전에 Jackson 3 의 예외 분류를 실측했다**(일회용 프로브, 커밋하지 않음). 후보 설정
셋을 각각 태워 본 결과:

| 입력 | `@JsonProperty(required=true)` 만 | **전역 `Nulls.FAIL`** (채택) |
|---|---|---|
| 필드 누락 | `MismatchedInputException` path=[password] | **`InvalidNullException`** path=[password] |
| 명시적 `null` | `InvalidNullException` path=[password] | **`InvalidNullException`** path=[password] |
| 타입 불일치(숫자→문자열) | `InvalidFormatException` path=[password] | `InvalidFormatException` path=[password] |
| 값이 배열·객체 | `MismatchedInputException` path=[email] | `MismatchedInputException` path=[email] |
| 루트 배열·스칼라 | `MismatchedInputException` path=**[]** | `MismatchedInputException` path=**[]** |
| 깨진 JSON | `MismatchedInputException` 없음 | `MismatchedInputException` 없음 |

`required=true` 안은 **누락과 「값이 배열·객체」가 같은 클래스**라 갈래를 가를 수 없었다.
남는 판별자는 예외 **메시지 문면**뿐인데, 그것은 「예외 메시지를 읽지 않는다」 규약과
정면으로 부딪히고 라이브러리 판올림에 조용히 깨진다. 그래서 **버렸다.**

**채택: 전역 `Nulls.FAIL`.** 누락과 명시 `null` 이 둘 다 `InvalidNullException`(프로퍼티 경로
포함)이 되므로 갈래를 **예외 타입**으로 가른다. DTO 마다 애너테이션을 다는 방식을 고르지
않은 이유는 coercion 때와 같다 — 다음 DTO 에서 빠뜨리고, 빠뜨린 상태가 조용하다.

- 설정: `api/src/main/.../config/JsonRequestStrictnessConfig.kt`
  (**이름을 바꿨다** — `JsonCoercionConfig` 는 더 이상 하는 일을 담지 못한다)
- 번역: `GlobalExceptionHandler.bodyReadItem` — `InvalidNullException` → `loc:["body",필드]` ·
  계약 `field_missing` 예시가 든 문구·`type:"missing"`
- 회귀: `AuthEndpointReachTest` **S-9c** — 세 갈래(누락 2건·명시 null 1건)가 각각 그 필드를
  지목하는지, 그리고 **깨진 JSON 은 여전히 `json_invalid` 인지**를 같은 케이스에서 본다
  (셋이 다시 한 모양으로 뭉치면 깨진다)

`contract-keeper` §2-3 이 든 「계약 예시 `field_missing` 이 어떤 요청에서도 나오지 않는다」는
이로써 닫힌다 — 그 모양이 실제로 나간다.

**의도한 어긋남 1건.** Pydantic 은 명시적 `null` 을 `string_type`("Input should be a valid string")
으로, 누락을 `missing` 으로 가른다. 여기서는 **둘 다 `missing`** 이다. 리더 지시가 「계약
`field_missing` 예시 형」이었고, 계약이 둘을 가르는 조항을 두지 않았다. Python 을 정답으로
삼지 않는다는 `CLAUDE.md` 규칙에 따라 **계약 문면을 기준으로 골랐고 그 선택을 여기 적는다.**

### 2-2. (ii) 루트 배열/스칼라의 클래스명 노출 — **HTTP 경계 실측으로 닫았다**

cross §9 가 이 자리를 **0관점(아무도 재지 않음)** 으로 올렸다. `typeLabelOf` 의 마지막 갈래
`else -> "value" to requiredType.simpleName` 을 클래스명을 싣지 않는 고정 어휘로 바꾸고,
`AuthEndpointReachTest` **S-9d** 가 실제 HTTP 응답 바이트에 `SignupRequest`·`kr.easydoc` 가
없는지 본다. `loc`·`type` 값도 함께 건다.

### 2-3. (iii) S-9b 값 미단언

키 집합만 보던 단언에 `loc`·`msg`·`type` **값**을 더했다(세 케이스 각각 어느 필드를 가리키는지
포함). `bodyReadItem` 의 프로퍼티 이름 추출이 깨져 언제나 `["body"]` 를 내면 이제 빨개진다.

---

## 3. 측정 — 리더가 지시한 셋

### 3-1. 세마포어 대기 상한 축소 · 배압 HTTP 경계

**값과 유도.** `AuthProperties.maxHashWaitMillis` 기본값 `5_000` → `250`.
KDoc 에 유도를 적었다 — 대기 상한 `W` 는 곧 대기 줄의 길이다: 통과 가능한 요청 수는
`W × P / H`(해시 1회 `H`≈100ms 실측, 자리 `P`=4). 종전 5000ms 는 **200건**으로 Tomcat 기본
스레드 수와 같아 상한에 닿기 전에 스레드가 먼저 마른다. 250ms 는 **약 10건**이다.

**회귀.** `PasswordHashingBackpressureReachTest`(신설, api). 자리 1 · 대기 1ms · 낮춘 메모리로
12건을 배리어로 동시에 푼다. 재는 것 다섯 —
⑴ 상태 코드가 **계약이 그 오퍼레이션에 선언한 것**인가(상수 대조 아님),
⑵ 본문이 계약 `InternalError` 예시의 고정 문구와 정확히 같고 최상위 키가 `detail` 하나인가,
⑶ 계정 있음/없음의 배압 응답이 **같은 바이트**인가,
⑷ 사적 응답 헤더 2종이 붙는가,
⑸ 대기 상한 값·예외 이름 같은 내부 사정이 본문에 없는가.
반대쪽(정상 401 도 함께 나온다)도 건다 — 전부 배압이면 「어떤 요청이든 500」과 구분되지 않는다.

**이 회귀가 계약 판정 어느 쪽에도 대응한다.** 500 유지면 지금 그대로 붙들고, 503+전용 문구로
개정되면 이 케이스가 먼저 빨개져 바꿀 자리를 가리킨다.

### 3-2. R-2 재실측 (240 동시) — **수치**

일회용 worktree에서 실기동(`@SpringBootTest(RANDOM_PORT)` + 일회용 Postgres)하고, 240건의
로그인을 배리어로 동시에 풀면서 `/health` 를 50ms 간격 60회 샘플링했다. Argon2 는 **운영
파라미터**(m=65536·t=3·p=4), 자리 4개. 대기 상한만 바꿔 세 번 쟀다.

```
wait=5000  codes={401:178, 500: 62}  /health median=2.28ms  max=1517.24ms  total=6656ms
wait=1000  codes={401: 60, 500:180}  /health median=4.91ms  max= 638.41ms  total=4614ms
wait= 250  codes={401: 16, 500:224}  /health median=6.01ms  max= 208.80ms  total=3855ms
```

**`/health` 최대 지연이 1517ms → 209ms 로 7.3배 줄었다.** privacy-gate 가 R-2 로 올린
「240 동시에서 `/health` 최대 1244.69ms」와 같은 자리이고(내 5000ms 측정은 1517ms — 같은
크기), 상한을 낮추면 그 구간이 사라진다. 원인이 permit 이 아니라 대기 상한 자체라는
privacy-gate 의 판독이 그대로 확인된다 — permit 수는 셋 다 4로 동일하다.

**대가를 숨기지 않는다.** 240 동시에서 배압 500 이 62건 → 224건으로 는다. 이것은 상한 축소의
**정의 그대로**다 — 「기다려서 처리」를 「즉시 배압」으로 바꾸는 값이므로, 부하가 상한을 넘는
구간에서 500 비율이 오르는 것이 정상 동작이다. 바뀐 것은 *실패하느냐*가 아니라 *다른 API 를
함께 멈추느냐*다(1517ms → 209ms). 240 동시는 rate limit 이 없는 상태의 상한 시험이고,
그 도입 여부는 게이트 20 이 이미 리더에게 넘긴 항목이다.

**세 점을 함께 남기는 이유**: 250 은 리더 지시(「수백 ms 수준」) 안의 값이지만, 가용성과
성공률의 교환비를 리더가 다시 정하고 싶을 때 1000ms 지점이 필요하다. 값은
`easydoc.auth.max-hash-wait-millis` 로 재배포 없이 바꿀 수 있다.

### 3-3. L-1 탐지 회귀의 양성·음성 대조

**⑴ 이 회귀가 지목 위험을 실제로 본다** — 캡처 내용을 열어 확인했다(일회용 관측):

```
CAPTURE events=7 warnPlus=[
  kr.easydoc.api.PasswordHashLogLeakReachTest/WARN,                      ← 양성 대조 표식
  org.springframework.security.crypto.argon2.Argon2PasswordEncoder/WARN, ← L-1 이 지목한 그 로거
  kr.easydoc.api.PasswordHashLogLeakReachTest/WARN,
  org.springframework.security.crypto.argon2.Argon2PasswordEncoder/WARN]
```

privacy-gate 가 실측한 바로 그 WARN 2건이 캡처 안에 있다. **레벨 고정으로는 억제되지 않는
출력이 이 회귀의 시야에는 들어와 있다.**

**⑵ 음성 대조 X-5** — `AuthService` 에 저장 PHC 를 찍는 로그 한 줄을 넣었다(라이브러리가
판올림으로 해시를 싣기 시작하는 상황의 대역).

```
FAILED  java.lang.AssertionError: 로그에 주입한 깨진 PHC 가 실렸다 — L-1 이 걱정한 유출이 실제로 일어났다
```

**⑶ 양성 대조가 공허하지 않은지 X-6** — 캡처 대상을 아무도 쓰지 않는 로거로 바꿨다.

```
FAILED  java.lang.AssertionError: 표식이 캡처에 없다 — 이 케이스는 아무 로그도 보고 있지 않다
```

즉 「유출 0건」이 **캡처가 비어서 참인 상태**는 통과하지 못한다.

---

## 4. `contract-keeper` §3-4 — 측정 결과와 **G1 근거**

계약 `:654-661` 이 파싱 거절을 7종으로 열거하고 `resolution` 이 「밸브가 7종 **전부**를
덮는다」고 적는데, 상시 회귀 `ContainerRejectedRequest` 열거자는 5개였다. 빠진 둘을
**원시 소켓으로 먼저 쟀다**(일회용 프로브, 실기동 `@SpringBootTest(RANDOM_PORT)`).

```
STAGE 콜론없는헤더줄        -> status=400 cache=[no-store] nosniff=[nosniff] allow=[]
                              ctype=application/json;charset=UTF-8  body={"detail":"Bad Request"}
STAGE 알수없는메서드         -> status=405 cache=[no-store] nosniff=[nosniff] allow=[GET]
                              ctype=application/json               body={"detail":"Method Not Allowed"}
STAGE 알수없는메서드(없는경로) -> status=404 cache=[no-store] nosniff=[nosniff] allow=[]
                              ctype=application/json               body={"detail":"Not Found"}
```

**갈래가 둘로 나뉜다.**

| 계약이 든 종 | 실측 | 판정 | 조치 |
|---|---|---|---|
| 콜론 없는 헤더 줄 → 400 | 400 · Content-Type 에 **`;charset=UTF-8`** · `Allow` 없음 = **밸브가 만든 응답** | 계약대로 **컨테이너 거절** | 열거자 `HEADER_WITHOUT_COLON` 추가 (5 → **6**). `@EnumSource` 두 테스트가 자동으로 덮는다 |
| 알 수 없는 메서드 → 405 | 405 · **`Allow: GET`** · 본문이 **우리 고정 문구** · charset 없음 = **서블릿까지 도달** | **계약 분류가 사실과 다르다 — `reachable_by_filter` 소속** | **계약 무수정**(소유자 아님). 그 경로의 응답을 `PrivateResponseHeadersReachTest` **도달 케이스**로 신설해 붙든다 |

**→ `contract-keeper` 에게(G1 근거).** `unreachable_by_filter.cases` 에서 「알 수 없는 메서드
→ 405」를 `reachable_by_filter` 로 옮기면 계약이 사실과 맞고, 그 순간 **열거자 6 = 계약 6**
이 되어 `resolution` 의 「전부 덮는다」도 참이 된다. 판별 근거 셋을 위에 실측으로 남겼다
(Allow 헤더 유무 · Content-Type 의 charset 유무 · 본문이 우리 문구인지).

**아직 없는 것(정직하게).** 계약 목록과 열거자 집합을 **빌드가 대조하는 장치는 아직 없다.**
지금 넣으면 6 vs 7 로 빨간 채 커밋해야 하고, 「면제 조항」을 달아 통과시키는 것은 이 하네스가
금지한 은폐형이다. **계약이 정정된 뒤 그 대조를 넣는 것**을 다음 단위 작업으로 남긴다.

---

## 5. 종전 산출물(`03_kotlin-implementer_auth-fixes.md`) 정정

리뷰 세 관점이 「본문과 표가 다른 것을 말하고, 원장으로 옮겨질 때 남는 것은 표다」를 지적했다.
**그 파일을 고치지 않고 여기서 정정한다** — 종전 판정의 근거가 된 문서를 사후 편집하면
리뷰가 무엇을 보고 무엇을 적었는지가 사라진다.

| 종전 표기 | 실제 | 근거 |
|---|---|---|
| §0 ② 세마포어 **「닫힘(응답 코드 판정 1건 남음)」** | **부분 해소였다.** 무한 대기만 닫혔고 ⑴ 배압 응답 HTTP 회귀 0건(TST-2) ⑵ 상한값 무근거(KTL-2 ≡ R-2) ⑶ 응답 코드 근거의 비대칭(CON-1)이 남아 있었다 | cross §4-3 · §8 ② |
| §0 ⑧ L-1 **「닫힘」** | **미해소였다.** `root: INFO` 이므로 그 한 줄은 실효 0이고, 지목 위험(WARN + 스택트레이스)은 그대로 열려 있었다. 그 줄을 지켜 주는 테스트도 0건이었다 | cross §6(2관점 합의) · privacy-gate 실측 |
| §5-3 「빨강 27건이 **5개** 클래스」 | **7개** 클래스 | Claude TST-4 · ck §3-1 |
| §5-3 「원시 소켓 **7종** 포함」 | **5건**(총계 27 은 정확). 이 배치로 열거자가 **6개**가 됐으므로 다음 측정부터는 6건이다 | ck §3-1 정정 |
| §1 해제조건 2 「`verify` 가 언제나 실패하므로 재해시에 닿지 않는다」 | **근거가 틀렸다.** `verify` 는 상수 원문에서 `true` 였고, 재해시 미도달의 실제 근거는 **제어 흐름**이다 | privacy-gate M-1 |
| §10 표 4 「슬라이스는 `JsonCoercionConfig` 를 들이지 않는다」 | 클래스 이름이 `JsonRequestStrictnessConfig` 로 바뀌었다. 슬라이스 미포함이라는 사실과 그 판단은 그대로 | 이 배치 |

---

## 6. 검사 결과

### 6-1. Kotlin — `./gradlew ktlintCheck detekt build --continue --rerun-tasks`

```
BUILD SUCCESSFUL in 24s
81 actionable tasks: 81 executed     ← 캐시 초록 아님. --continue 라 실패가 있었으면 전부 모아 보고된다
```

**단독 종료 코드 `0`**(파이프 없이 `echo $?` 로 확인). warning 정책 포함 통과.

모듈별 건수(`build/test-results/test/*.xml` 합계):

| 모듈 | tests | failures | skipped |
|---|---|---|---|
| core | 357 | 0 | 0 |
| application | 43 | 0 | 0 |
| infrastructure | 99 | 0 | 0 |
| **api** | **129** | 0 | 0 |
| worker | 3 | 0 | 0 |
| **합계** | **631** | **0** | **0** |

게이트 20 조치 시점 624 → **631**(+7). 내역: 배압 HTTP 1 · 로그 유출 1 · S-9c 1 · S-9d 1 ·
알 수 없는 메서드 405 도달 1 · `ContainerRejectedRequest` 열거자 1개 추가 × `@EnumSource`
테스트 2곳 = 2.

`moduleBoundaryCheck`:

```
[worker] 모듈 경계 확인: 선언 종류 + compileClasspath 양쪽 통과.
[api]    모듈 경계 확인: 선언 종류 + compileClasspath 양쪽 통과.
BUILD SUCCESSFUL
```

### 6-2. Python (건드리지 않았으므로 그대로 통과해야 한다)

```
uv run ruff check .        → All checks passed!                      (exit 0)
uv run mypy . .claude      → Success: no issues found in 137 source files  (exit 0)
uv run pytest -q           → 1243 passed, 68 skipped, 5 deselected, 5 xfailed
```

`tests/golden` 은 프롬프트·스타일 규칙·LLM 설정을 건드리지 않았으므로 별도 실행 대상이
아니고, 위 전체 실행에 포함돼 있다.

### 6-3. 실행하지 못한 것

| 항목 | 사유 |
|---|---|
| **CI 원격 캐시 거동** | 로컬에서 잴 수 없다. cross §9 의 0관점 항목 그대로 |
| **React 대조** | 이 배치는 `frontend/` 무접촉. 오류 본문 모양이 바뀌었으므로(누락·null 이 배열 갈래로) `client.ts` 의 `readErrorMessage` 가 두 모양을 모두 처리하는지는 **이미 그렇다고 알려져 있으나 이 배치에서 재지 않았다** |

---

## 7. 남기는 것 — 열린 목록

> cross §10 의 마감별 분류를 그대로 잇는다. **이 배치가 닫지 않은 것을 빠짐없이 적는다.**

### 7-1. 리더 판정으로 코드 작업이 없던 것

| # | 항목 | 상태 |
|---|---|---|
| 1 | **CON-1 ↔ ck ①** 배압의 500 유지 · `InternalError`/`ServiceUnavailable` 설명 개정 | 리더가 escalate ④(사용자 판단)로 넘김. **코드는 500 그대로**, 다만 거짓이 된 KDoc(`GlobalExceptionHandler` 백스톱)은 사실대로 고쳤다 |
| 2 | **SEC-2** signup 409 계정 열거 | 계약 승인 노출 — 사용자 판단. 코드 무변경 |
| 3 | **SEC-1 ≡ R-1** 옛 파라미터 계정의 시간 격차(1.834x / 43.9ms) | 운영 지침 등재. 코드로 덮을 수 없는 자리다(같은 검증 비용 ↔ 저장 PHC 에서 파라미터 읽기가 맞물린다). **오늘 도달 0**, 파라미터를 바꾸는 날 열린다 |

### 7-2. 이 배치가 **의도적으로 남긴** 것

| # | 항목 | 사유 · 마감 |
|---|---|---|
| 4 | **C-1 후단** — Java 컨트롤러(`classes/java/main`)·`RouterFunction`·다른 `HandlerMapping` 이 조용히 빠진다 (≡ RCH-4·RCH-5) | 오늘 도달 0(`.java` 0건). 다른 모듈/Java 가 컨트롤러를 갖는 커밋에서 함께 판정 |
| 5 | **계약 7종 ↔ 열거자 대조 장치** | 계약 정정(G1, §4)이 선행돼야 한다. 면제 조항으로 통과시키지 않는다 |
| 6 | **CON-2** enum 갈래 coercion | Phase 4 첫 enum 요청 필드 커밋. 오늘 대상 필드 0 |
| 7 | **반대 방향 coercion**(숫자 필드로 오는 문자열) | 요청 DTO 에 비문자열 필드 0(ck 가 리플렉션으로 실측). Phase 4 첫 비문자열 필드 커밋 |
| 8 | **RCH-3** 「그 값은 설정에서 온다」가 빈 선언에서 통과 | 권고. privacy-gate 는 반대 견해(통과로 셈). 이번 배치에서 판정하지 않았다 |
| 9 | **RCH-1** `inputs.file` 선언 문구가 실제(`subprojects`)보다 넓다 | 문구 정정. 이 배치 범위 밖(빌드 스크립트 무접촉) |
| 10 | **TST-5** F3 강제자가 클래스를 못 찾은 필드를 조용히 건너뛴다 | Phase 4 해당 DTO 커밋 |
| 11 | **TST-6** H-1 새 케이스 둘째 단언이 부분 문자열 | Phase 4 (Python 하네스 쪽) |
| 12 | **N-10** `headerComponentsByName` 방어 `require` 가 음성 대조를 밟지 않는다 | 방어 `require` 라 성질 5 위반은 아니다. 명세 등재만 |
| 13 | **KTL-1** 더미 조립이 기동 실패 경로를 넓힌다 | 난수화로 조립 단계가 하나 늘었다(같은 성격). privacy-gate 는 같은 사실을 이점으로 읽는다. 양쪽 근거 병기 |
| 14 | **CI 원격 캐시 거동** · **`InternalError` 개정 blast radius** · **React 전면 대조** | cross §9 의 0관점 항목. 이 배치도 재지 않았다 |

### 7-3. 이 배치가 **새로 연** 것

| # | 항목 | 성격 |
|---|---|---|
| 15 | **전역 `Nulls.FAIL` 의 blast radius** — `null` 을 받아야 하는 선택 필드가 생기면 그 필드에 `Nulls.SET` 을 **명시적으로** 달아 열어야 한다 | 의도한 설계(여는 것이 눈에 보인다). Phase 4 첫 선택 필드에서 판정 |
| 16 | **`InvalidNullException` 분기가 Jackson 내부 분류에 기댄다** | 판올림으로 그 클래스가 안 나오면 타입 불일치 갈래로 **조용히 내려앉는다**(필드는 계속 가리킨다). S-9c 가 `type:"missing"` 을 값으로 단언하므로 **빌드가 먼저 알린다** — 조용하지 않다 |
| 17 | **대기 상한 250ms 는 해시 1회 ~100ms 를 전제한다** | 파라미터를 크게 올리면 정상 부하에서도 배압이 난다. 그때 두 값을 함께 판정해야 한다 |
| 18 | **`JsonCoercionConfig` → `JsonRequestStrictnessConfig` 이름 변경** | 종전 리뷰 산출물들이 옛 이름으로 이 파일을 지목한다 |

**이 배치가 하지 않은 것:** 계약 파일 수정 0건. `app/` 수정 0건. `00_progress.md` 접촉 0건.
빌드 스크립트 수정 0건.
