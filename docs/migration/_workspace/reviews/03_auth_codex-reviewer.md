# 게이트 20 · 1단계 codex 독립 리뷰 — `03_auth`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`03_auth_cross.md`)의 몫이다.

**어간**: `03_auth` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 09:01:14 KST |
| 종료 시각 | 2026-08-19 09:19:08 KST |
| 소요 | **17분 54초** (헬퍼 기록 `17m 53s`) |
| 대상 범위 | **`e91ecdd~1..fc21750`** — 커밋 16개, 변경 파일 51개 |
| 모드 | `adversarial` (focus text 필수 — 인증·계약·게이트 무력화 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base e91ecdd~1`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-mszbtbev-0vbef2` |
| codex session ID | `01a01752-7c36-77e1-9f9e-3662f25af4b5` (turn `01a01752-7db8-7ac3-8105-a2a616cec951`) |
| codex 판정 | **`needs-attention`** — "출하 차단." |
| codex 실행 셸 명령 | **77건 시작 / 67건 완료 기록(완료 줄은 전부 exit 0)** — 완료 줄이 남지 않은 10건은 사유 미기록 |
| focus text 크기 | 13,880 바이트 |
| 지적 건수 | **6건 — high 3 · medium 3 · low 0** |
| codex 출력 크기 | 7,536 바이트 |

### 1.1 base 를 `e91ecdd~1` 로 잡은 근거

리더의 지정 문자열은 `e91ecdd..fc21750`(15 커밋)이지만, 같은 지시가 ⓐ 로 **`e91ecdd` 자신을
리뷰 대상 3커밋 중 하나**로 명시한다(`e91ecdd`·`e600861`·`e7f9bdb` — "개수 → 내용 결속 → 내용
정확 일치"의 첫 단계가 `e91ecdd`다). git 범위 표기 `e91ecdd..` 는 `e91ecdd` 자신의 diff 를
**제외**하므로, 그대로 쓰면 지시가 리뷰하라고 지목한 커밋이 대상에서 빠진다. 지목된 커밋을
포함시키기 위해 `--base e91ecdd~1` 을 썼고 대상은 16 커밋이 됐다. 범위를 좁히는 쪽이 아니라
넓히는 쪽으로 어긋났음을 여기 기록한다.

### 1.2 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs e91ecdd~1
codex-review: 대상 판정 = non-empty (merge-base=445c5cf2035e, 변경 파일 51개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다. `merge-base=445c5cf2035e` 는
`e91ecdd~1` 의 전체 해시다.

### 1.3 리뷰 중 HEAD 이동 (사실 기록)

리뷰 실행(09:01:14–09:19:08) **도중** 저장소 HEAD 가 이동했다.

| 시각 | 사건 |
|---|---|
| 09:01:14 | 리뷰 시작. HEAD = `fc21750` (dry-run 대상 판정도 이 상태에서 측정 — 51 파일) |
| 09:10:40 | **HEAD → `6ece404`** — `docs(contract): auth 계약 테스트 독립 검증 …` (병렬 레인 `contract-keeper` 산출물). 변경 3파일 전부 `docs/migration/_workspace/**` **문서 전용**, 코드 0줄 |
| 09:19:08 | 리뷰 종료 |

codex 는 이 이동을 **스스로 감지해 요약에 적었다** — "체크아웃이 이후 문서 전용 6ece404로
이동했지만 대상 blob은 fc21750과 동일함을 재확인했다"(§3 요약 6행). 이 문장의 검증은 이
에이전트가 하지 않는다.

리뷰 종료 후 작업 트리 확인: **수정된 추적 파일 0건.** untracked 는 `.playwright-mcp/`,
`.doc` 2건(리뷰 전부터 존재), 그리고 병렬 레인 산출물 2건
(`03_auth_migration-reviewer.md`·`03_security_privacy-gate.md`)뿐이다. focus text 의
"작업 트리를 오염시키지 마라" 지시대로 codex 가 남긴 변조 파일은 없다.

### 1.4 실행 명령 전문

```bash
SP=<스크래치패드>/gate20
FOCUS="$(cat "$SP/focus_20.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty, 종료 코드 6)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base e91ecdd~1 --focus "$FOCUS" --dry-run

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base e91ecdd~1 --focus "$FOCUS"

# 스크립트가 헬퍼로 내보낸 실제 명령:
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
     adversarial-review --base 'e91ecdd~1' '<focus text 전문 — §2>'

# 사후 회수 (재실행 아님):
node <헬퍼> status --all
```

### 1.5 제공한 맥락 (전사하지 않고 **읽게** 했다)

focus text 는 아래를 **파일·행·해시로 지목만** 했고 본문을 옮겨 적지 않았다. codex 가 직접 읽었다.

| 자료 | 지목 방식 |
|---|---|
| `contracts/easy-doc-v1.yaml` | `/auth/*` 약 760-858행, `x-request-field-constraints` 약 386-424행, `ValidationFailed` 약 1516-1518행 (+ "행 번호는 안내다, 실제 파일에서 확인하라") |
| `.claude/skills/migration-safety-gate/SKILL.md` | I-8 · I-9 항목 지목 |
| `docs/migration/_workspace/03_contract-keeper_auth-test-spec.md` | 커밋 `05862fa` |
| `docs/migration/_workspace/03_kotlin-implementer_auth.md` | 커밋 `fc21750` — "이 문서의 주장을 사실로 받아들이지 말고 **검증 대상**으로 다뤄라" |
| 리뷰 대상 커밋 16개 | ⓐ 하네스 3커밋 / ⓑ auth 구현 으로 구분 제시 |

**민감 데이터 미포함**: focus text 에는 실제 암호문·키·사용자 문서·개인정보가 없다. 예시로 쓴
문자열은 합성 값(`u@example.test`, `δοκιμή@example.test`, `user@bücher.test`)뿐이다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
## 배경

Python/FastAPI 런타임을 Kotlin/Spring Boot로 교체하는 전환이다. 이번 범위는 Phase 3(인증·작업
공간)이며, 제품의 외부 HTTP 계약과 개인정보 보호 정책을 보존해야 한다. 판정 기준은 "Python과 같은
값이 나오는가"가 **아니다** — Python은 폐기 대상이고 인증·비밀번호는 신규 생성이므로 Python 대조를
요구하지 않는다. 기준은 아래 명세 파일이 요구하는 계약·불변식을 충족하는가다.

리뷰 대상 diff: `e91ecdd~1..fc21750` (커밋 16개). 두 덩어리다.
- ⓐ 하네스 3커밋(`e91ecdd`·`e600861`·`e7f9bdb`) — `tests/test_parity_ci_gate.py`만 건드린다.
- ⓑ Phase 3 auth 구현(나머지) — `backend-kotlin/`의 core/application/infrastructure/api 4계층.

## 반드시 직접 읽어라 (여기 전사하지 않았다 — 파일·행만 지목한다)

- **계약 정본**: `contracts/easy-doc-v1.yaml`
  - `/auth/*` 경로 정의 — 약 760-858행
  - `x-request-field-constraints` (필드별 상한·측정 축·강제 계층) — 약 386-424행
  - `ValidationFailed` 스키마 — 약 1516-1518행
  - 행 번호는 안내다. 실제 파일에서 확인하고, 어긋나면 실제 위치를 근거로 삼아라.
- **암호·인증 불변식 정본**: `.claude/skills/migration-safety-gate/SKILL.md` — **I-8**(Argon2 PHC
  검증과 로그인 성공 시에만 재해시), **I-9**(JWT 발급·검증의 정확성). 이 두 항목의 본문이 채점
  기준이다.
- **테스트 명세**: `docs/migration/_workspace/03_contract-keeper_auth-test-spec.md` (커밋 `05862fa`)
  — 케이스 표, OQ-3 파서 요건, 계층 지목.
- **구현자 자기 산출물**: `docs/migration/_workspace/03_kotlin-implementer_auth.md` (커밋 `fc21750`)
  — 대응표, 음성 대조 N-1~N-8, 스스로 신고한 결함. 이 문서의 주장이 코드와 일치하는지 **검증
  대상**으로 다뤄라. 문서가 "했다"고 적은 것을 사실로 받아들이지 마라.

## 실행 규칙

- 셸을 써서 재현·확인해도 좋다. 다만 **작업 트리를 오염시키지 마라** — 변이(mutation) 재현은
  `git show`, 임시 디렉터리 사본, 메모리 내 패치로 하고, 변조된 파일을 저장소에 남기지 마라.
  되돌릴 때 `cp` 로 덮어쓰지 마라(이 저장소에서 사고가 난 이력이 있다).
- 파일·행·커밋 해시로 근거를 지목하라. 추정이면 추정이라고 밝혀라.

---

# 축 ① 인증 정확성 (I-8 / I-9)

대상: `backend-kotlin/infrastructure/.../auth/Argon2PasswordHasher.kt`, `Argon2Phc.kt`,
`JwtAccessTokens.kt`, `AuthConfiguration.kt`, `application/.../auth/AuthService.kt`,
`core/.../user/PasswordHash.kt`.

찾아라:
1. **재해시 판정의 범위.** I-8이 요구하는 재해시 조건을 이 구현이 실제로 만족하는가 —
   비교하는 파라미터가 무엇무엇인지 코드에서 열거하고, PHC 문자열이 담을 수 있는 파라미터
   중 **비교되지 않는 것**을 지목하라. 어느 하나라도 빠지면 약한 파라미터로 만들어진 해시가
   영구히 약한 채로 남는다. 재해시가 **로그인 성공 시에만** 일어나는가(실패 경로에서 재해시하면
   오프라인 오라클이 된다).
2. **PHC 파싱의 방어.** 변형된/잘린/알고리즘이 다른/파라미터가 비정상적으로 큰 PHC 문자열이
   들어왔을 때 예외·무한 자원 소비·검증 우회가 생기는 경로. `$argon2i`/`$argon2d` 혼동,
   salt/hash 길이 0, 정수 오버플로, base64 패딩 차이.
3. **JWT 검증.** I-9가 요구하는 것 대비 — clock skew(허용 오차)가 0인가, `exp` 만료가 실제로
   거부되는가, 서명 알고리즘이 고정돼 `alg:none`/HMAC↔RSA 혼동이 불가능한가, `typ`/`sub`
   클레임 검증이 있는가, 토큰이 서명만 맞으면 통과하는 자리가 있는가.
4. **비밀키 출처.** 서명 키가 어디서 오는가 — 하드코딩·기본값 fallback·짧은 키 허용·개발용
   기본값이 프로덕션에서 살아남는 경로. 키가 없을 때 애플리케이션이 **조용히 뜨는가**, 실패하는가.
5. **누출.** 비밀번호 평문·해시·이메일이 로그·예외 메시지·오류 응답 본문·스택트레이스·메트릭
   태그로 나가는 경로. 이 저장소 규칙은 "로그에 문서 본문·개인정보를 남기지 않는다"이다.
6. **사용자 존재 누출.** 로그인 실패 경로가 "사용자 없음"과 "비밀번호 틀림"에서 **동형**인가 —
   응답 본문·상태 코드뿐 아니라 **소요 시간**도 본다. 사용자가 없을 때 Argon2 검증을 건너뛰면
   응답 시간 차이로 계정 존재가 샌다. 코드에서 두 경로의 작업량을 비교해 답하라.

# 축 ② 계약 준수

대상: `api/.../auth/AuthController.kt`, `AuthDtos.kt`, `AuthenticationInterceptor.kt`,
`AuthenticatedEndpoints.kt`, `config/WebMvcConfig.kt`, `config/EasyDocProperties.kt`,
`application/.../auth/CredentialRules.kt`.

계약이 요구하는 것(계약 파일에서 직접 확인하라):
- 입력 길이·형식 위반의 오류 본문은 `{"detail": "<문자열>"}` 이고 `detail`이 **배열이 아니다**.
  Bean Validation(`@Size`/`@NotBlank`/`@Email`)으로 구현하면 배열이 나가고 문구가 영문이 되어
  계약 위반이다. 계약의 `x-request-field-constraints`는 auth 필드의 강제 **계층**을 지정한다.
- 각 필드의 측정 축(`fields[].measured_on`)이 있다 — 어떤 필드를 원시 문자열로 재고 어떤 필드를
  정규화 후에 재는지가 계약에 적혀 있다.

찾아라:
1. auth 요청 DTO에 `@Size`/`@NotBlank`/`@Email`이 **단 하나라도** 남아 있는가. 있으면 어느
   필드인지, 그것이 나가는 응답 본문이 계약의 `detail` 문자열 형태를 깨는지.
2. 길이·형식 판정이 계약이 지정한 **계층**에서 일어나는가, 그리고 **정규화(trim/lowercase 등)
   전인가 후인가**가 계약의 `measured_on`과 일치하는가. 어긋나면 어느 입력에서 판정이 갈리는지
   구체적 입력값으로 보여라(예: 앞뒤 공백이 붙은 최대 길이 문자열).
3. email의 **길이 위반**과 **형식 오류**가 계약이 요구하는 대로 같은 문구를 내는가, 아니면
   구분되어 열거 공격에 쓸 정보를 주는가.
4. `Cache-Control: no-store` 및 전역 보안 헤더가 **auth 응답 전부**(성공·401·409·422·500 포함)에
   붙는가. 헤더를 붙이는 장치가 `add`가 아니라 `set`인지(중복 헤더), 그리고 응답당 개수가
   계약과 맞는지.
5. 오류 본문에 Spring 기본 `/error` 경로 정보나 `ProblemDetail` 필드(`type`/`title`/`status`/
   `instance`)가 새는 경로가 **하나라도** 있는가. 인터셉터보다 앞에서 끝나는 요청(디스패처
   예외, 404, 415, 405, 잘못된 JSON, 너무 큰 본문)을 특히 보라.
6. 401이 계약이 정의한 갈래대로 나가는가(토큰 없음 / 토큰 무효). 409 중복 가입의 조건과
   본문이 계약과 맞는가. 경합(같은 이메일 동시 가입)에서 DB 제약 위반이 500으로 새는가.

# 축 ③ 계약 테스트가 실제로 계약에 도달하는가

대상: `api/src/test/.../support/ContractSpec.kt`, `AuthContractTest.kt`,
`AuthEndpointReachTest.kt`, `AuthUnavailableContractTest.kt`,
`AuthenticationCoverageContractTest.kt`, `RequestFieldConstraintLayerTest.kt`,
`ConfigurationPropertiesBindingTest.kt`, `support/AuthSliceBeans.kt`, `support/TestJwt.kt`.

이 테스트들의 주장은 "기대값을 코드에 적어 둔 것이 아니라 **계약 파일을 파싱해서** 가져온다"이다.
검증하라:
1. `ContractSpec`이 `contracts/easy-doc-v1.yaml`을 **런타임에 실제로 읽는가**, 아니면 읽는
   척하고 상수를 반환하는가. 파일을 못 찾거나 키가 없을 때 **테스트가 실패하는가**, 아니면
   기본값으로 넘어가거나 `assumeTrue`/`@Disabled`/try-catch로 **스킵되는가**. 스킵되면
   계약을 지워도 초록불이다.
2. 필드 조회가 **이름으로** 이뤄지는가. 존재하지 않는 필드명을 물으면 실패하는가, null/기본값을
   주는가. 인덱스나 순서에 의존해 계약 파일에서 항목 순서만 바꿔도 다른 값을 읽는 자리가 있는가.
3. 계약에 **상한이 두 벌** 적힌 자리가 있다면, 테스트가 그 두 벌을 서로 대조하는가 — 한쪽만
   읽으면 두 벌이 갈려도 알 수 없다.
4. 테스트가 **HTTP 경계**에서 측정하는가. 특히 **401 케이스**: 인터셉터·필터·예외 핸들러를
   거치지 않고 컨트롤러나 서비스를 직접 불러 "401이 나올 것"이라고 단언하는 대리 측정이 있는가.
   MockMvc standalone 설정이 실제 애플리케이션의 필터 체인·메시지 컨버터·예외 매퍼와 다른
   구성을 쓰고 있다면, 그 차이 때문에 프로덕션에서만 깨지는 조항을 지목하라.
5. 구현자 문서가 열거한 **음성 대조 N-1~N-8**을 골라서 **실제로 재현하라**(전부가 아니어도 좋다 —
   가장 의심스러운 3~4개). 즉 그 결함을 코드에 도로 주입했을 때 지목된 테스트가 정말 빨개지는가.
   빨개지지 않는 항목이 있으면 그것이 이 축의 핵심 지적이다. 재현은 임시 사본/메모리에서 하고
   작업 트리를 되돌려 놓아라.
6. 문서가 "실행 소스가 실재한다"고 주장하는 항목(X-F11~F13 등)의 소스가 실제로 존재하고
   그 경로가 테스트에서 참조되는가.

# 축 ④ 하네스 3커밋 — `tests/test_parity_ci_gate.py`

`e91ecdd`·`e600861`·`e7f9bdb` 세 커밋은 "개수로 묶던 검사를 내용으로 묶는다"는 취지다.
핵심 상수는 `EXPECTED_DYNAMIC_LOOKUP_NAMES`(약 1022행)와 `_MAINLINE_PHRASES`(약 625행)다.

찾아라:
1. 이 검사들이 막겠다고 선언한 **세 가지 우회**를 실제로 막는가 — 실행해서 확인하라.
   (a) **동일 개수 치환**: 이름/문구를 개수는 그대로 두고 다른 것으로 바꿔치기.
   (b) **복사본 치환**: 원본 대신 사본을 만들어 그것을 가리키게 하기.
   (c) **builtin 치환**: 기대 이름을 파이썬 builtin 등 어디에나 있는 이름으로 바꿔 "실재한다"
       검사를 공짜로 통과시키기.
   각 변이를 실제로 주입해 테스트가 빨개지는지 보고, **빨개지지 않는 변이**를 지목하라.
2. 이 세 커밋이 **새로운 우회 통로를 열었는가**. 새 상수·새 헬퍼가 그 자신은 아무 검사도 받지
   않는 자리에 있는가(상수를 통째로 비우거나 `= frozenset()`으로 만들면 어떤 테스트가 깨지는가).
3. 검사가 **자기 자신에게서 기대값을 끌어오는 순환**이 있는가 — 검사 대상 모듈에서 이름을 읽어
   같은 모듈의 상수와 비교하는 구조는 양쪽을 함께 고치면 통과한다.

# 축 ⑤ 선언한 범위 대 실제 도달 · 보안 불변식

1. 이 변경이 새로 선언한 "전역"·"모든"·"항상"을 열거하고, 그 강제 수단이 **닿지 않는 경로**를
   찾아라. 인터셉터/필터가 시작조차 하지 않는 요청, 등록에서 빠진 경로 패턴, 예외 상황.
   `AuthenticatedEndpoints.kt`가 "인증이 필요한 엔드포인트"를 선언한다면, 그 선언과 실제로
   인터셉터가 적용되는 경로 집합이 **같은가** — 새 엔드포인트를 추가했을 때 선언에서 빠지면
   무인증으로 열리는가, 아니면 실패하는가(기본값이 열림인가 닫힘인가).
2. **이메일 정규화의 ASCII 좁힘.** 계약이 형식을 규정하지 않은 자리를 구현이 좁혔는지 보라.
   애플리케이션이 `lowercase()`(또는 로케일 의존 소문자화)를 쓰고 DB가 `lower()`/CHECK 제약을
   쓴다면, 두 소문자화가 **비ASCII 입력에서 갈리는가**. 갈리면 어떤 입력에서 CHECK 제약 위반이
   500으로 터지는지 구체적 문자로 보여라. 터키어 I, 그리스어 시그마, 전각 문자, 유니코드
   정규화(NFC/NFKC) 차이를 확인하라. 로케일 의존 `toLowerCase()`가 쓰였는가.
3. **`kotlin-reflect` 실행 의존 추가**(커밋 `7a75f29`)의 정당성. 그 처방이 고쳤다는 결함이
   실재하는가, 그리고 그 결함의 **회귀 테스트**(`ConfigurationPropertiesBindingTest`)가
   의존을 도로 빼면 실제로 빨개지는가 — 아니면 통과하는가. 이 의존이 없으면 무엇이 깨지는지
   구체적으로 답하라.
4. **레이어 분리.** 컨트롤러에 비즈니스 로직이 있는가(라우터 → 서비스 → 리포지터리). core
   모듈이 Spring·DB 의존 없이 유지되는가.
5. **JDBC 리포지터리**: `JdbcUserRepository`/`JdbcWorkspaceRepository`의 SQL 조립이 문자열
   연결로 되어 있는가(주입), nullable 컬럼과 Kotlin 타입이 어긋나 `!!`/플랫폼 타입으로 NPE가
   나는 자리, 가입이 사용자+작업 공간을 **한 트랜잭션**으로 만드는가(`@Transactional`이
   자기 호출·private 메서드에 붙어 프록시가 안 먹는 자리 포함).
6. **정직성 점검.** 구현자 문서가 "대상 없음"·"해당 없음"으로 처리한 항목이 정말 대상이 없는
   것인가, 아니면 대상을 좁게 잡아 비운 것인가.

---

## 출력 형식

각 지적마다: 심각도(high/medium/low), 축 번호, 파일·행, 무엇이 깨지는가(공격자·사용자에게
일어나는 일), 근거(읽은 코드 / 실행한 재현). 재현을 시도했으나 실패했으면 그 사실도 적어라.
지적할 것이 없는 축은 "지적 없음"이라고 명시하라 — 채우려고 만들지 마라.
```

---

## 3. codex 원문 출력 — **무편집**

> 아래는 `codex-review.sh` 의 stdout 전문(7,536 바이트)이다. 한 글자도 고치지 않았다.
> 줄바꿈·표기·용어·행 번호 전부 codex 가 쓴 그대로다.

```text
# Codex Adversarial Review

Target: branch diff against e91ecdd~1
Verdict: needs-attention

출하 차단. 지정 범위 e91ecdd~1..fc21750에서 인증 타이밍 누출, 공개 엔드포인트를 이용한 요청 스레드 고갈, HTTP 타입 계약 위반, 보호 경로의 fail-open 강제, 하네스 순환 기대값, 미승인 이메일 정책을 확인했다. 체크아웃이 이후 문서 전용 6ece404로 이동했지만 대상 blob은 fc21750과 동일함을 재확인했다. 하네스 변이는 메모리에서 재현했다. N-3/N-4/N-7/N-8 및 kotlin-reflect 제거 실행은 읽기 전용 환경이 Gradle lock/temp 생성을 거부해 수행하지 못했다.

Findings:
- [high] 축 ① — 존재하지 않는 계정은 Argon2를 건너뛰어 원격 열거가 가능하다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt:86-92)
  commit b87de0b의 로그인은 이메일 조회가 null이면 즉시 401을 던지지만, 존재하는 계정의 틀린 비밀번호는 64MiB Argon2 검증을 수행한다. 응답 바이트가 같아도 처리 비용이 크게 달라진다. 계약 x-auth.failure_uniformity(299-302행)는 없는 사용자도 동일 비용의 더미 해시를 검증하라고 명시하며, 구현자 문서도 이 결함을 226행에서 인정한다. 공격자는 반복 측정으로 가입 이메일을 식별할 수 있다. 근거는 읽은 분기이며 부하 실행은 환경 제한으로 수행하지 못했다.
  Recommendation: 정책과 동일한 더미 PHC를 준비해 사용자 부재 경로에서도 반드시 verify를 한 번 수행하라. 아래 Argon2 실행 격리와 rate limit을 함께 적용해 더미 검증이 DoS를 확대하지 않게 하라.
- [high] 축 ① — Argon2 세마포어가 무제한으로 요청 스레드를 점유한다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/Argon2PasswordHasher.kt:116-122)
  commit 3da2d51의 hash/verify는 요청 스레드에서 실행되고, 허용량을 넘으면 Semaphore.acquire()에서 시간 제한 없이 대기한다. 코드 주석도 56행에서 요청 스레드 블로킹을 인정한다. 공개 signup에 유효한 신규 이메일을 병렬 제출하면 기본 4건만 계산하고 나머지 Tomcat 스레드는 모두 대기해 /health와 다른 API까지 멈춘다. 이는 I-8 123행의 '해싱이 요청 스레드를 잠그지 않는지' 조건을 직접 위반한다.
  Recommendation: Argon2를 전용 bounded executor/bulkhead로 옮기고 큐 길이와 대기 시간을 제한하라. 포화 시 429/503으로 빠르게 실패시키고, 동시 메모리 사용 상한은 유지하라.
- [high] 축 ③·⑤ — 보호 경로 검사는 실제 컨트롤러를 발견하지 않아 새 API가 무인증으로 열릴 수 있다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthenticationCoverageContractTest.kt:49-55)
  commit f9ee3e6의 테스트는 계약 보호 경로를 실제 RequestMapping과 대조하지 않고 수기 implementedPaths()와 교집합한다. 새 보호 컨트롤러를 구현하면서 implementedPaths와 AuthenticatedEndpoints 양쪽을 빠뜨리면 49-55행의 대상에서 제외되고, 'remaining' 테스트는 목록을 출력만 한 뒤 /auth/me 부재만 검사한다. 프로덕션 WebMvcConfig 53-56행은 열거된 패턴에만 인터셉터를 적용하므로 그 엔드포인트의 기본값은 공개다. 현재 세 auth 경로 자체는 일치하지만 다음 보호 엔드포인트 추가 시 실제 인증 우회가 초록불로 남는다.
  Recommendation: RequestMappingHandlerMapping에서 실제 method/path 집합을 런타임 추출해 계약 security 및 인터셉터 적용 집합과 정확히 대조하라. 가능하면 공개 경로 allowlist 외에는 기본 거부하도록 강제 방식을 뒤집어라.
- [medium] 축 ② — 숫자·불리언 auth 필드가 문자열로 강제 변환되어 타입 불일치 계약을 우회한다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthDtos.kt:33-49)
  commit 0b81fa6의 DTO는 String 생성자 파라미터만 선언하고 Jackson scalar coercion을 금지하지 않는다. 락파일의 Jackson 3.1.4 bytecode를 확인한 결과 CoercionConfigs 기본값은 TryConvert이고 StringDeserializer는 숫자·불리언에 getValueAsString()을 사용한다. 저장소와 Spring Boot jar에는 이를 Fail로 바꾸는 설정이 없다. 따라서 {"email":"u@example.test","password":12345678}은 비밀번호 "12345678"로 가입될 수 있지만, 계약 ValidationFailed 1510행은 타입 불일치를 422 배열로 요구한다. 테스트는 필드 누락만 다뤄 이 경로를 놓친다. HTTP 실행은 temp 생성 제한으로 못 했으므로 런타임 결과는 의존성 bytecode에 근거한 추론이다.
  Recommendation: Textual 논리 타입에 대한 Integer/Float/Boolean coercion을 CoercionAction.Fail로 설정하거나 엄격한 DTO 역직렬화를 사용하라. signup/login 각각에 숫자·불리언 필드의 실제 HTTP 422 배열 테스트를 추가하라.
- [medium] 축 ④ — EXPECTED_DYNAMIC_LOOKUP_NAMES가 검사 대상과 같은 파일에 있어 함께 변조하면 게이트가 공허하게 통과한다 (tests/test_parity_ci_gate.py:1218-1229)
  e7f9bdb는 실제 집합과 기대 집합을 인접 상수로 두고 단순 동등 비교한다. 작업 트리를 쓰지 않은 메모리 변이 결과는 baseline PASS, 동일 개수 builtin으로 실제 집합만 치환 FAIL, 문구 복사본 치환 FAIL이었지만, 실제·기대 집합을 함께 abs/all/any/ascii/bin/bytearray/bytes로 치환하면 PASS했고 둘 다 빈 집합이어도 PASS했다. 따라서 선언한 단일 변이는 막지만 새 기대 상수 자체는 독립적으로 강제되지 않아 AST 우회 감시가 통째로 비어도 CI가 초록색이다.
  Recommendation: 기대 집합을 같은 테스트 모듈의 가변 쌍으로 두지 말고 독립 정본 또는 별도 meta-gate에 결속하라. 양쪽 동시 치환·alias·동시 공집합을 주입하는 음성 테스트를 추가하라.
- [medium] 축 ⑤ — 계약에 없는 ASCII 이메일 정책이 국제 이메일을 일방적으로 거부한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/CredentialRules.kt:43-60)
  commit b87de0b는 계약이 이메일 문법을 정의하지 않았는데도 ASCII 전용 정규식을 제품 정책으로 확정한다. 예를 들어 δοκιμή@example.test, user@bücher.test, 터키어 İ가 든 주소는 정규화 후 81행에서 422가 된다. 이는 Kotlin/PostgreSQL 소문자화 차이로 인한 500을 사전 거부로 피하지만, 그 대신 계약·승인 없이 사용자 집합을 좁힌다. 구현자 문서 225행도 이를 미결 정책으로 신고했다.
  Recommendation: 허용할 이메일 문법과 IDN/EAI 정책을 계약에서 먼저 결정하라. ASCII 제한이 승인되면 명시적 계약과 경계 테스트로 고정하고, 아니라면 정규화 및 PostgreSQL 제약을 같은 단위로 수정하라.

Next steps:
- 타이밍 균일화와 Argon2 실행 격리를 하나의 보안 수정으로 처리한다.
- 엄격한 JSON 타입 처리와 실제 매핑 기반 default-deny 인증 커버리지를 추가한다.
- 하네스 기대값을 독립 정본에 결속하고 이메일 허용 정책을 계약으로 결정한다.
- 쓰기 가능한 임시 worktree에서 전체 Kotlin 테스트, N-3/N-4/N-7/N-8 변이, kotlin-reflect 제거 회귀를 다시 실행한다.
```

> **무편집 검증**: 위 블록을 스크립트 stdout 원본(`<스크래치패드>/gate20/codex_stdout.txt`)과
> `diff` 로 대조해 **0줄 차이**를 확인했다(검증 절차는 §5.4). 초판에서 마지막 Recommendation 의
> 한 구절이 옮기다 빠졌던 것을 이 대조가 잡아내 정정했다.

---

## 4. 정리 (가공) — Claude 색인

> **이 구획은 codex 원문이 아니다.** 지적을 찾아보기 쉽게 나열만 했다.
> 옳고 그름 판정·심각도 재부여·중복 병합·오탐 표시는 **하지 않았다** — `migration-reviewer`
> 2차 호출의 몫이다. 심각도·축·파일·행은 codex 표기를 그대로 옮겼고 다시 세지 않았다.

### 4.1 지적 색인 (6건)

| # | 심각도 | codex 표기 축 | 요지 (codex 표현) | codex가 준 파일·행 | 근거의 성격 (codex 자기 진술) |
|---|---|---|---|---|---|
| C1 | high | 축 ① | 존재하지 않는 계정은 Argon2를 건너뛰어 원격 열거가 가능하다 | `application/.../auth/AuthService.kt:86-92` | "읽은 분기이며 부하 실행은 환경 제한으로 수행하지 못했다" |
| C2 | high | 축 ① | Argon2 세마포어가 무제한으로 요청 스레드를 점유한다 | `infrastructure/.../auth/Argon2PasswordHasher.kt:116-122` | 코드 읽기 (I-8 123행 인용) |
| C3 | high | 축 ③·⑤ | 보호 경로 검사는 실제 컨트롤러를 발견하지 않아 새 API가 무인증으로 열릴 수 있다 | `api/src/test/.../AuthenticationCoverageContractTest.kt:49-55` (+ `WebMvcConfig` 53-56행) | 코드 읽기. codex 자신이 "현재 세 auth 경로 자체는 일치한다"고 병기 |
| C4 | medium | 축 ② | 숫자·불리언 auth 필드가 문자열로 강제 변환되어 타입 불일치 계약을 우회한다 | `api/.../auth/AuthDtos.kt:33-49` | **추론** — "HTTP 실행은 temp 생성 제한으로 못 했으므로 런타임 결과는 의존성 bytecode에 근거한 추론이다" |
| C5 | medium | 축 ④ | `EXPECTED_DYNAMIC_LOOKUP_NAMES`가 검사 대상과 같은 파일에 있어 함께 변조하면 게이트가 공허하게 통과한다 | `tests/test_parity_ci_gate.py:1218-1229` | **메모리 변이 실행** — 4가지 변이 결과를 명시 |
| C6 | medium | 축 ⑤ | 계약에 없는 ASCII 이메일 정책이 국제 이메일을 일방적으로 거부한다 | `application/.../auth/CredentialRules.kt:43-60` (+ 81행) | 코드 읽기 |

### 4.2 codex가 인용한 외부 근거 — **전제 확인 필요**

아래는 codex 가 **판정 근거로 인용한 위치**다. 이 에이전트는 인용의 정확성을 검증하지 않았다.
`migration-reviewer` 2차 호출에서 실물과 대조할 것.

| 인용처 | codex가 적은 위치 | codex가 적은 내용 |
|---|---|---|
| `contracts/easy-doc-v1.yaml` | `x-auth.failure_uniformity` **299-302행** | "없는 사용자도 동일 비용의 더미 해시를 검증하라고 명시" |
| `contracts/easy-doc-v1.yaml` | `ValidationFailed` **1510행** | "타입 불일치를 422 배열로 요구" |
| `.claude/skills/migration-safety-gate/SKILL.md` | I-8 **123행** | "해싱이 요청 스레드를 잠그지 않는지" |
| `docs/migration/_workspace/03_kotlin-implementer_auth.md` | **226행** | 구현자가 C1(타이밍) 결함을 스스로 인정 |
| `docs/migration/_workspace/03_kotlin-implementer_auth.md` | **225행** | 구현자가 C6(ASCII 이메일)을 미결 정책으로 신고 |
| `Argon2PasswordHasher.kt` | **56행** 주석 | 구현자가 요청 스레드 블로킹을 인정 |

**주의**: focus text 가 제시한 계약 행 번호(`x-request-field-constraints` 약 386-424행,
`ValidationFailed` 약 1516-1518행)와 codex 가 인용한 행 번호(299-302, 1510)가 다르다.
focus 는 "행 번호는 안내다, 실제 파일에서 확인하라"고 지시했으므로 codex 가 실물에서 다시
찾았을 가능성과 잘못 읽었을 가능성이 모두 열려 있다. **어느 쪽인지 이 에이전트는 판정하지
않는다.**

### 4.3 축별 codex 응답 유무 (원문 기준 집계만)

| 축 | codex 지적 | 비고 (codex 진술 그대로) |
|---|---|---|
| ① 인증 정확성 | **2건** (C1·C2) | 재해시 전체 파라미터 동등성·JWT skew 0·비밀키 출처·로그 누출에 대해서는 **지적이 없다.** codex 는 "지적 없음"이라고 명시하지도 않았다 |
| ② 계약 준수 | **1건** (C4) | `@Size`/`@NotBlank`/`@Email` 0, `no-store`/전역 헤더, `/error` 누출, 401 두 갈래, 409 중복에 대해서는 지적이 없다 |
| ③ 계약 테스트 도달 | **1건** (C3, 축 ⑤와 공동 표기) | `ContractSpec` 파싱·이름 조회·P-7 두 벌 대조·MockMvc 대리 측정에 대해서는 지적이 없다. **N-1~N-8 재현은 §5 대로 미수행** |
| ④ 하네스 3커밋 | **1건** (C5) | 메모리 변이 4종 실행 결과를 원문에 기재 |
| ⑤ 도달 범위·보안 불변식 | **2건** (C3 공동·C6) | `kotlin-reflect` 정당성·레이어 분리·JDBC SQL 조립·트랜잭션 원자성·지침 8 정직성에 대해서는 지적이 없다 |

focus text 는 "지적할 것이 없는 축은 '지적 없음'이라고 명시하라"고 요구했으나 codex 출력에는
그 문장이 없다. 따라서 위 "지적이 없다"는 **codex 가 검토하고 문제없다고 판단했다는 뜻이
아니라, 출력에 해당 항목이 나타나지 않았다는 사실만을 뜻한다.**

---

## 5. 미실행·실패 항목

### 5.1 codex 가 스스로 신고한 미실행 (원문 인용)

| 항목 | codex가 밝힌 사유 (원문) |
|---|---|
| **음성 대조 N-3 / N-4 / N-7 / N-8 재현** | "N-3/N-4/N-7/N-8 및 kotlin-reflect 제거 실행은 읽기 전용 환경이 Gradle lock/temp 생성을 거부해 수행하지 못했다." |
| **`kotlin-reflect` 제거 회귀 실행** | 위와 동일 |
| **전체 Kotlin 테스트 실행** | Next steps 4행: "쓰기 가능한 임시 worktree에서 전체 Kotlin 테스트 … 를 다시 실행한다." |
| **C1 타이밍 부하 측정** | "근거는 읽은 분기이며 부하 실행은 환경 제한으로 수행하지 못했다." |
| **C4 실제 HTTP 요청 실행** | "HTTP 실행은 temp 생성 제한으로 못 했으므로 런타임 결과는 의존성 bytecode에 근거한 추론이다." |

**축 ③의 핵심 요구(N-1~N-8 재현)는 따라서 이번 회차에서 충족되지 않았다.** codex 가 실제로
변이를 주입해 재현한 것은 **축 ④(하네스 `tests/test_parity_ci_gate.py`)뿐**이며, 그것도
작업 트리가 아니라 **메모리 변이**로 수행했다(변이 4종의 PASS/FAIL 결과는 §3 C5 원문에 있음).

### 5.2 실행 흔적의 공백

- codex 셸 명령 **77건 시작 / 67건 완료 기록**. 완료 줄이 남지 않은 **10건**의 사유는
  stderr 에 기록되지 않았다. 완료 줄이 남은 67건은 전부 `exit 0` 이다.
- codex 출력에 focus 가 요구한 "지적 없음" 명시 문장이 없다(§4.3 참조).

### 5.3 이 에이전트가 하지 않은 것

- codex 지적의 옳고 그름 판정, 심각도 재부여, 중복 병합, 오탐 표시 — 전부 하지 않았다.
- codex 가 인용한 행 번호·문서 주장의 실물 대조 — 하지 않았다(§4.2 에 "전제 확인 필요"로 표시).
- 코드 수정 — 하지 않았다. 커밋 — 하지 않았다. `00_progress.md` — 건드리지 않았다.
- `migration-reviewer`·`privacy-gate` 산출물 — 작성하지 않았다.

### 5.4 무편집·전문 일치 검증 절차 (실행 기록)

§3(codex 원문)과 §2(프롬프트 전문)를 눈으로 훑지 않고 **`diff` 로 대조**했다. 산출물에서 블록을
다시 추출해 원본 파일과 비교하는 방식이라, 옮겨 적다 생긴 누락이 통과하지 못한다.

```bash
F=docs/migration/_workspace/reviews/03_auth_codex-reviewer.md
SP=<스크래치패드>/gate20

# §3 — codex 원문 블록 추출 후 스크립트 stdout 원본과 대조
sed -n '<블록 범위>p' "$F" > "$SP/extracted_block.txt"
diff -u "$SP/codex_stdout.txt" "$SP/extracted_block.txt"    # → 0줄 차이

# §2 — focus 블록 추출 후 실제로 넘긴 focus 파일과 대조
sed -n '<블록 범위>p' "$F" > "$SP/extracted_focus.txt"
diff -u "$SP/focus_20.txt" "$SP/extracted_focus.txt"        # → 0줄 차이
```

결과: **양쪽 0줄 차이.** 초판 §3 에서 마지막 medium 지적의 Recommendation 한 구절
(`ASCII 제한이 승인되면 명시적 계약과 경계 테스트로 고정하고,`)이 옮기다 빠져 있었고, 이 대조가
그것을 잡아내 정정했다. 육안 확인만 했다면 이 산출물은 "무편집"이라고 적힌 채 편집된 원문을
`migration-reviewer` 에 넘겼을 것이다.

### 5.5 재호출 없음

exit 0 · 출력 7,536바이트로 1회 실행에 성공했다. **재시도는 필요하지 않았고 수행하지 않았다.**
⚠ codex 리뷰 누락 해당 없음.

---

## 6. 다음 단계 (프로토콜상)

이 파일은 게이트 20 **1단계**의 codex 산출물이다. 같은 어간의 짝
`docs/migration/_workspace/reviews/03_auth_migration-reviewer.md` 가 병렬 레인에서 생성되었음을
확인했다(untracked 상태). 3단계는 `migration-reviewer` **2차 호출**이 두 파일을 대조해
`docs/migration/_workspace/reviews/03_auth_cross.md` 를 작성하는 것이며, 그 호출에서 새 지적을
만들지 않는다. Phase 3 종료 판정은 오케스트레이터의 몫이다.
