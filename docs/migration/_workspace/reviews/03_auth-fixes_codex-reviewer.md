# 게이트 21 · 1단계 codex 독립 리뷰 — `03_auth-fixes`

> 이 파일은 **codex 원본**이다. §3 은 **무편집**이고 §4·§5 는 Claude 색인이다.
> 이 에이전트는 codex 지적의 옳고 그름을 **판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시
> 어느 것도 하지 않았다. 판정과 종합은 `migration-reviewer` 2차 호출(`03_auth-fixes_cross.md`)의 몫이다.

**어간**: `03_auth-fixes` — 리더가 1단계 호출에서 **고정 지정**한 값을 그대로 썼다(임의 슬러그 생성 없음).

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 착수 시각 | 2026-08-19 10:22:59 KST |
| 종료 시각 | 2026-08-19 10:36:33 KST |
| 소요 | **13분 34초** (헬퍼 기록 `13m 33s`) |
| 대상 범위 | **`bf08edd..3c5c8ad`** — 커밋 9개(코드 7 + 원장 1 + 산출물 1), 변경 파일 27개 |
| 모드 | `adversarial` (focus text 필수 — 인증 타이밍·계약·게이트 무력화 축이라 일반 review 로는 초록불을 의심하지 않는다) |
| scope / base | `auto`(미지정) / **`--base bf08edd`** — base 지정 시 scope 는 무시된다 |
| 헬퍼 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 자동 선택, **1.0.6**) |
| **스크립트 종료 코드** | **`0`** — 리뷰가 돌았고 출력이 비어 있지 않다. 이 값일 때만 리뷰 근거가 된다 |
| job id | `review-mszeqg5k-hzt360` |
| codex session ID | `01a0179d-542b-7b92-a7eb-95f0bf7b5e10` (turn `01a0179d-5589-7f62-9e31-3eb50cad54a3`) |
| codex 판정 | **`needs-attention`** — "출하 차단." |
| codex 실행 셸 명령 | **49건 시작 / 40건 완료 / 5건 실패**(실패 목록은 §5) |
| focus text 크기 | 14,002 바이트 |
| 지적 건수 | **3건 — high 1 · medium 2 · low 0** |
| codex 출력 크기 | 6,084 바이트 |

### 1.1 base 를 `bf08edd` 로 잡은 근거

리더의 지정 문자열 `bf08edd..3c5c8ad` 를 **그대로** 썼다. 게이트 20 때와 달리 `~1` 보정을
하지 않은 이유: `bf08edd` 는 이 배치의 **직전 상태**이지 리뷰 대상 커밋이 아니다.
`git log --oneline bf08edd..3c5c8ad` 가 정확히 9개(`ea15782`·`265b429`·`f0c0aac`·`2660252`·
`8b5ede6`·`d22c7df`·`618fb89`·`2adae30`·`3c5c8ad`)를 돌려주며, 이는 리더가 명시한
"9 커밋 — 코드 7 + 원장 1 + 산출물 1" 과 일치한다. 범위가 어긋나지 않았다.

### 1.2 스크립트가 stderr 에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs bf08edd
codex-review: 대상 판정 = non-empty (merge-base=bf08edd0855c, 변경 파일 27개 (branch 모드는 커밋된 변경만 센다))
```

빈 리뷰(exit 7)가 아니었음이 **사전 거부 단계에서** 확인됐다. `--dry-run` 선행 실행에서도
같은 두 줄이 나왔고 종료 코드는 `6`이었다.

### 1.3 리더가 지정한 세 문서를 codex 가 실제로 읽었는가 (전사 금지 지시의 이행 확인)

리더 지시는 "codex 에게 읽게 하라(전사 금지)" 였다. focus text 는 세 문서의 내용을 옮겨 적지
않고 **경로만** 주었다. codex 가 셋 다 전문을 읽었음이 헬퍼 로그로 확인된다.

| 문서 | codex 가 실행한 명령 |
|---|---|
| 교차 종합 | `sed -n '1,528p' docs/migration/_workspace/reviews/03_auth_cross.md` |
| 보안 감사 | `sed -n '1,382p' docs/migration/_workspace/reviews/03_security_privacy-gate.md` |
| 구현 산출물 | `sed -n '1,312p' docs/migration/_workspace/03_kotlin-implementer_auth-fixes.md` |

세 파일의 실제 행 수와 읽은 범위가 일치한다(각각 528·382·312행 이상 요청).

### 1.4 리뷰 중 저장소 상태

| 시각 | 사실 |
|---|---|
| 10:22:59 | 리뷰 시작. HEAD = `3c5c8ad` |
| 10:36:33 | 리뷰 종료. HEAD = `3c5c8ad` — **이동 없음** |

**codex 가 작업 트리를 오염시켰는가: 아니다.** focus text 가 "변이 테스트를 했으면 원상복구하고
`git status --porcelain` 을 찍어 리뷰 전과 같음을 보여라" 를 요구했고, codex 는 마지막에
`git diff --quiet; git diff --cached --quiet; ...` 를 실행해(exit 0) 요약에 결과를 적었다 —
*"최종 git status는 리뷰 전과 동일한 기존 미추적 4개뿐이고 tracked/staged diff는 없다."*

리뷰 종료 후 이 에이전트가 독립 확인한 결과 tracked 수정 0건이다. 리뷰 전후로 늘어난
작업 트리 항목은 **전부 병렬 레인 산출물**이며 codex 와 무관하다.

| 항목 | 출처 |
|---|---|
| `docs/migration/_workspace/03_contract-keeper_auth-fixes-verdict.md` (A) | 병렬 레인 `contract-keeper` |
| `docs/migration/_workspace/03_contract-keeper_auth-test-spec.md` (M) | 병렬 레인 `contract-keeper` |
| `docs/migration/_workspace/reviews/03_security-fixes_privacy-gate.md` (??) | 병렬 레인 `privacy-gate` |
| `.claude/worktrees/` (리뷰 시작 시 untracked → 현재 미표시) | 병렬 레인 worktree |

### 1.5 실행 명령 전문

```bash
SP=<스크래치패드>/gate21
FOCUS="$(cat "$SP/focus_21.txt")"

# 1) --dry-run 으로 헬퍼·대상·명령 확인 (대상 판정 non-empty, 종료 코드 6)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base bf08edd --dry-run "$FOCUS"

# 2) 실제 실행 (종료 코드 0)
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base bf08edd "$FOCUS"
```

헬퍼로 전개된 명령(스크립트가 stderr 에 찍은 것):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base bf08edd '<focus text 전문>'
```

### 1.6 민감 데이터 미포함 확인

프롬프트에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. 인용한 수치
(중앙값 2.3ms vs 97ms, 42배)는 직전 게이트의 **합성 계정 실측 통계**이고 자격증명 값이 아니다.
계약 조항 인용은 공개 스펙 파일(`contracts/easy-doc-v1.yaml`)의 문면이다.

---

## 2. 전달한 프롬프트 전문 (focus text)

아래는 codex 에 전달한 focus text **전문**이다. 요약·발췌가 아니다.

````text
## 배경

이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot(`backend-kotlin/`)로 교체하는 중이다. 제품
동작과 개인정보 보호 정책은 보존해야 하고, HTTP 계약은 `contracts/easy-doc-v1.yaml`이 정본이다.
2026-08-12 결정으로 **Python 출력을 정답으로 삼지 않는다** — 판정 기준은 계약과 요구사항이지
"Python과 같은 값이 나오는가"가 아니다. 암호문·토큰 호환 요구는 소멸했다. 예외는 정책 불변식
(마스킹 선행·no-store·소유권 은닉 404·자격증명 실패 균일성)뿐이다.

리뷰 대상은 Phase 3 `auth` 단위의 **직전 리뷰 게이트가 확정한 결함을 고친 배치**다. 그 게이트의
교차 종합과 보안 감사가 무엇을 요구했는지는 아래 문서에 있다. **먼저 읽어라.** 이 프롬프트는
그 내용을 옮겨 적지 않았다.

- `docs/migration/_workspace/reviews/03_auth_cross.md` — 교차 종합 정본. §7-2가 조치 목록,
  §3이 관점이 갈린 항목의 양쪽 근거다.
- `docs/migration/_workspace/reviews/03_security_privacy-gate.md` — 보안 감사. 맨 앞의 **B-1
  차단 항목**과 그 "해제 조건" 3항이 이번 배치 ①의 채점 기준이다.
- `docs/migration/_workspace/03_kotlin-implementer_auth-fixes.md` — 구현자가 스스로 적은 조치
  산출물. **이 문서의 주장을 사실로 받아들이지 말고 코드와 대조하라.** §10에 스스로 열어 둔
  항목 5건이 있다.

## 지켜야 하는 조건 (채점 기준)

계약과 감사가 정한 것이다. 위반을 찾는 것이 이 리뷰의 목적이다.

1. **자격증명 실패 균일성** — `contracts/easy-doc-v1.yaml`의 `x-auth.failure_uniformity`:
   "이메일 부재·비밀번호 불일치·토큰 만료·위조·계정 삭제를 **모두 같은 401과 같은 메시지**로
   처리한다. 구분하면 계정 열거 공격의 단서가 된다. **사용자가 없을 때도 더미 해시로 같은 검증
   비용을 치러 응답 시간으로도 새지 않게 한다.**" — 축이 셋(상태 코드·본문·**응답 시간**)이다.
   직전 감사가 HTTP 경계에서 중앙값 2.3ms vs 97ms(**약 42배**)를 실측해 차단했다.
2. **Argon2 재해시 금지 경로** — 실패한 로그인에서 재해시를 돌리지 않는다. 재해시는 검증
   **성공** 뒤에만 일어난다.
3. **오류 본문 형식** — 모든 오류는 `{"detail": ...}`이다. Spring 기본 `ProblemDetail`을 노출하면
   안 된다. 입력 검증 실패는 422이며 `detail`이 **배열**(`{loc, msg, type}` 항목)이고, 도메인
   규칙 위반은 422 + `detail`이 **문자열**이다. JSON 필드는 snake_case다.
4. **입력값 비반향** — 거절된 입력값(비밀번호·이메일·본문)은 오류 응답·로그·예외 메시지·메트릭
   어디에도 실리지 않는다. 로그는 문서 id·길이·상태까지만.
5. **`/auth/login`이 계약에 선언한 상태 코드** — 200·401·422·500·503. 그 밖의 코드를 새로
   만들면 계약 개정이고 이 배치의 권한 밖이다.
6. **선언한 범위와 실제 도달의 일치** — 이 저장소의 규칙이다. "전역"·"모든"·"항상"을 선언한
   장치는 실제 도달을 실행으로 대조해야 하고, 도달 0인 게이트를 특히 의심한다. **은폐형**
   (무시 패턴·억제·면제 조항·제외 목록)은 탐지형으로 갈아타야 하며 넓히면 안 된다. **범위
   선언형** 검사는 선언이 비었을 때 통과하면 안 된다.

## 대상

`bf08edd..3c5c8ad` 9커밋(코드 7 + 원장 1 + 산출물 1). 핵심 파일:

- `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/auth/AuthService.kt`
  · `.../auth/AuthPorts.kt`
- `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/auth/Argon2PasswordHasher.kt`
  · `.../auth/AuthConfiguration.kt`
- `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/JsonCoercionConfig.kt` (신규)
  · `.../api/error/GlobalExceptionHandler.kt` · `.../api/auth/AuthenticatedEndpoints.kt`
- `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthenticationCoverageContractTest.kt`
  · `.../AuthEndpointReachTest.kt` · `.../support/ContractSpec.kt` · `.../AuthContractTest.kt`
  · `.../PrivateResponseHeadersContractTest.kt` · `.../RequestFieldConstraintLayerTest.kt`
- `backend-kotlin/build.gradle.kts` · `backend-kotlin/api/src/main/resources/application.yml`
- `tests/test_harness_scope_reach.py` · `tests/test_parity_ci_gate.py`

계약 정본은 `contracts/easy-doc-v1.yaml`. 참고 구현(정답 아님)은 `app/api/auth.py`·
`app/services/auth.py`·`app/api/errors.py`.

## 질문 — 네 축

### 축 ① B-1(로그인 타이밍) 해제가 실제로 성립하는가

- `PasswordHasher.dummyHash()`가 돌려주는 PHC가 **정말 현행 정책 파라미터**로 만들어졌는가?
  구현은 `Argon2PasswordEncoder`가 만든 값을 그대로 쓰는데, 그 인코더의 파라미터가 정책과
  갈리는 경로(설정 미적용, 기본 생성자, 조립 순서, 프로퍼티 바인딩 실패)가 있는가?
  구현자는 `needsRehash(dummy) == false` 단언으로 이것을 보장한다고 주장한다 — **그 단언이
  실제로 파라미터 동등성을 보장하는가, 아니면 우회로 통과할 수 있는가?**
- 더미 PHC에 재해시가 걸리는 경로가 **어떤 순서·예외·동시성에서도** 없는가? 검증이 언제나
  실패한다는 전제가 깨지는 경로(입력이 우연히 일치, 인코더 예외, 정책 변경 후 첫 요청)는?
- 타이밍 회귀 테스트가 **비**로 판정하는데, 워밍업·JIT·GC·CI 부하·병렬 테스트 실행에 견디는가?
  거짓 초록(더미를 빼도 통과)과 거짓 빨강(정상인데 실패) 양쪽 조건을 짚어라. 임계값이 어디서
  오고, 그 값이 결함을 실제로 잡을 만큼 좁은가?
- **더미 경로와 실제 경로에 남은 다른 타이밍 누설**은 무엇인가? DB 조회 유무, 정규화 비용,
  세마포어 대기 여부, 예외 생성 위치, 커넥션 획득, 트랜잭션 경계 — 해시 비용을 맞춰도 이들이
  갈리면 격차가 남는다. 계정이 **삭제된** 경우, 이메일이 상한을 넘는 경우, 정규화 후 빈 문자열이
  되는 경우 각각 어느 경로를 타는가?
- 세마포어 상한 초과가 **500**으로 나가는데, 이것이 새로운 열거 채널이 되는가? 없는 이메일
  경로만 permit을 더 쓰게 되어 부하 상황에서 두 경로의 500 발생률이 갈리는지, 대기 시간
  자체가 계정 존재를 알려 주는지 보라. 그리고 500 응답 본문·헤더가 조건 3·4를 지키는가?

### 축 ② C4 처방(scalar coercion 차단)의 도달 범위와 계약 정합

- `JsonCoercionConfig`가 `JsonMapperBuilderCustomizer`로 coercion을 끈다. 이 설정이 **모든 요청
  본문 역직렬화 경로**에 실제로 닿는가? 닿지 않는 자리를 찾아라 — 다른 `ObjectMapper`/`JsonMapper`
  빈, `@RequestParam`·`@PathVariable`·헤더 바인딩, multipart 파트, `WebMvcTest` 등 슬라이스
  컨텍스트, 커스텀 `HttpMessageConverter`, 테스트가 직접 만든 mapper.
  **구현자는 "모든 요청 본문에 적용된다"고 선언했다 — 그 선언과 실제 도달을 대조하라.**
- `LogicalType.Textual` + `Integer/Float/Boolean` 세 shape만 껐다. 이 선택이 놓치는 입력 모양이
  있는가(`null`, 배열, 객체, `Array`/`Object` shape, 중첩 DTO, `String`→다른 타입)? 실제로
  거절되는 값의 집합이 계약이 요구하는 집합과 같은가?
- 타입 불일치가 만드는 **422 본문이 계약의 `ValidationFailed` 모양과 일치하는가?** `loc` 배열의
  첫 원소·필드 경로·`msg` 문구·`type` 값이 계약이 정한 형태인가, 아니면 새로 발명한 값인가?
  snake_case 규약을 지키는가? 중첩 필드·배열 인덱스에서 `loc`이 어떻게 나오는지 확인하라.
- **반대 방향**(숫자·불리언 필드로 들어오는 문자열)은 어떻게 되는가? 구현자는 "현재 요청 DTO에
  비문자열 필드가 하나도 없어 잴 대상이 없다"고 적었다 — 그 주장이 참인지 DTO를 전수로
  확인하라. 참이 아니면 그 자리는 지금 어떻게 동작하는가?
- `bodyReadItem`이 `MismatchedInputException`에서 경로와 목표 타입만 읽는다고 주장한다. **거절된
  값이 응답·로그·예외 체인 어디로도 새지 않는가?** `path`의 `propertyName`이 클라이언트가 보낸
  임의 키를 그대로 반향할 수 있는가(알 수 없는 필드, 극단적으로 긴 키, 제어문자·비ASCII)?
  `targetType`이 내부 클래스명을 노출하는가? 예외가 `bodyReadItem`이 처리하지 못하는 형태일 때
  떨어지는 백스톱은 무엇을 내보내는가?

### 축 ③ 보호 경로 자동 발견의 완전성

- `AuthenticationCoverageContractTest`가 `RequestMappingHandlerMapping`에서 매핑을 발견하고
  "공개 ∪ 보호 = 전체 매핑"의 **정확 일치**를 단언한다. 이 발견이 **놓치는 매핑 종류**는?
  `@WebMvcTest` 슬라이스가 들이지 않는 컨트롤러, 다른 `HandlerMapping`(라우터 함수, 정적 자원,
  `SimpleUrlHandlerMapping`), 조건부 등록 빈, 다른 모듈의 컨트롤러, `@ControllerAdvice` 경로,
  actuator, `produces`/`method`만 다른 매핑, 패턴 없는 매핑(`patternValues`가 비는 경우).
- 테스트 전용 컨트롤러를 **빌드 출력 위치**(`api/build/classes/kotlin/main`)로 제외한다. 이것이
  **은폐형으로 자랄 수 있는가?** 프로덕션 클래스가 그 디렉터리에 없게 되는 경로(모듈 이름 변경,
  다른 모듈의 컨트롤러, jar 실행, Gradle 출력 경로 변경, 증분 빌드 잔재, 다른 컴파일러 출력)가
  있으면 진짜 API가 조용히 제외된다. 그 상황에서 테스트가 **실패하는가, 초록이 되는가?**
- 하드코딩된 상대 경로 `api/build/classes/kotlin/main`과 시스템 속성 `easydoc.kotlin.source.root`
  의 결합이 어떤 조건에서 어긋나는가? `require(classesDir.isDirectory)`가 없을 때 어떻게 되는가?
- `server.error.path` 단일 제외의 정당성 — 그 값이 없거나 다르게 설정됐을 때, 그리고 실제
  서블릿 오류 디스패치가 그 경로로 오지 않을 때 무슨 일이 생기는가?
- **변이 테스트로 답하라**: (a) 계약에 있고 보호로 선언된 새 컨트롤러를 추가하고
  `AuthenticatedEndpoints`에 넣지 않으면 실패하는가? (b) 계약에 **없는** 경로의 컨트롤러를
  추가하면 실패하는가? (c) `AuthenticatedEndpoints.PROTECTED_PATH_PATTERNS`를 **비우면**
  실패하는가? (d) 계약의 `security` 선언을 뒤집으면 실패하는가? 실행해서 확인하되, 작업 트리를
  오염시키지 말고 확인 후 원상복구하라 — `git status`가 리뷰 전과 같아야 한다.
- `isNotEmpty()` 방어가 "발견 0건"을 막지만, **발견이 1건뿐일 때**(대부분이 조용히 빠졌을 때)도
  통과한다. 범위 선언형 검사가 빈 선언에서 통과하지 않는다는 조건을 이 테스트가 만족하는가?

### 축 ④ Gradle 선언 입력·계약 대조 확대·H-1

- `build.gradle.kts`가 계약 파일을 **모든 테스트 태스크**의 `inputs.file`로 걸고
  `PathSensitivity.NONE`을 준다. 다음 네 조건을 **실행으로 재현**하라:
  (a) 아무것도 바꾸지 않고 두 번 돌리면 UP-TO-DATE인가?
  (b) 계약 파일만 바꾸면 계약 테스트가 **실제로 다시 도는가**?
  (c) `cleanTest` 후, 그리고 빌드 캐시가 있는 상태에서 어떻게 되는가?
  (d) 계약 파일의 값을 바꾸면(예: `/auth/me`의 401을 403으로) 테스트가 **빨강이 되는가**?
  확인 후 원상복구하고 `git status`·`git diff`가 리뷰 전과 같음을 확인하라.
- `PathSensitivity.NONE`이 옳은 선택인가? 파일 **이름**이 바뀌거나 다른 계약 파일로 교체될 때
  지문이 그것을 반영하는가? 파일이 **없을 때** 빌드가 어떻게 되는가?
- 계약 대조 확대(`ContractSpec`의 P-3b/P-4b 등, 상태 8건·전역 헤더 5건·헤더 const 전 경로)가
  **삭제된 손 목록 2개를 완전히 대체하는가?** 손 목록이 덮던 것 중 새 방식이 덮지 못하는 것을
  찾아라. 새 접근자가 계약을 읽지 못하거나 빈 집합을 돌려줄 때 단언이 **공허하게 통과**하는가?
- 헤더 `const` 강제자를 "전 경로"로 넓혔다고 선언한다 — 그 선언과 실제 도달을 대조하라. 강제자가
  닿지 않는 응답 경로(오류 디스패치, 필터 단계 거절, 비동기, 스트리밍, 404/405/415)가 있는가?
- H-1(`tests/test_harness_scope_reach.py`·`tests/test_parity_ci_gate.py`의 양성 경로)이 **진짜
  탐지기를 통과하는가**, 아니면 탐지기를 흉내 낸 별도 경로를 통과하는가? 양성 시료가 탐지기의
  실제 입력 형태와 같은가? 이 테스트를 제거하면 정확히 무엇이 깨지는가?

## 리뷰 방식에 대한 요청

- 각 지적에 **파일 경로와 라인**, 그리고 **재현 방법**(실행한 명령과 관측한 값)을 붙여라.
  실행하지 않고 추론만 한 항목은 그렇게 표시하라 — 두 가지를 섞지 마라.
- 명령을 실행해도 좋으나 **작업 트리를 변경한 채로 두지 마라.** 변이 테스트를 했으면 원상복구하고
  마지막에 `git status --porcelain`을 찍어 리뷰 전과 같음을 보여라.
- 구현 산출물(`03_kotlin-implementer_auth-fixes.md`)의 주장 중 **코드와 어긋나는 것**을 찾으면
  별도로 지적하라. 그 문서는 근거가 아니라 검증 대상이다.
- 지적할 것이 없는 축이 있으면 "없다"고 명시하라. 억지로 채우지 마라.
````

---

## 3. codex 원문 출력 — **무편집**

> 아래 코드블록 안에는 Claude 가 쓴 문장이 **한 줄도 없다.** 오탈자·용어·서식 어느 것도 손대지
> 않았고, 정렬·중복 병합·심각도 조정도 하지 않았다. 스크립트가 stdout 으로 받은 6,084 바이트
> 전부다.

````text
# Codex Adversarial Review

Target: branch diff against bf08edd
Verdict: needs-attention

출하 차단. 축 ②의 오류 번역과 축 ③의 자동 발견에 거짓 초록이 있으며, 축 ①의 타이밍 회귀 기준도 B-1 해제 증거로는 느슨하다. 축 ④ Gradle/H-1에서는 정적 분석상 추가 결함을 찾지 못했지만, 읽기 전용 환경 때문에 변이·캐시 실험은 실행하지 못했다. 구현 산출물의 “더미 검증은 언제나 false” 주장도 알려진 원문을 해시하는 코드와 불일치하지만, 현재 서비스는 결과를 무시하고 401을 던지므로 별도 차단 항목으로 세지 않았다. 최종 git status는 리뷰 전과 동일한 기존 미추적 4개뿐이고 tracked/staged diff는 없다.

Findings:
- [high] 자동 발견이 HTTP 오퍼레이션을 경로 하나로 축약해 계약 밖 공개 메서드를 놓친다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthenticationCoverageContractTest.kt:156-175)
  servedPaths()는 RequestMappingInfo에서 patternValues만 Set<String>으로 남겨 HTTP method·consumes·produces를 모두 버린다. 따라서 계약에 GET /health만 있는데 POST /health를 추가해도 발견 집합은 그대로 /health이고 ‘공개 ∪ 보호 = 전체’가 통과한다. /health는 보호 목록에도 없으므로 계약 밖 POST가 인증 없이 출하될 수 있다. 실행 재현: easy-doc-v1.yaml을 파싱해 실제 오퍼레이션에 ('POST','/health')를 추가한 뒤 이 테스트와 동일하게 path로 투영했다. 관측값은 contract_has_POST_health=False, test_path_projection_passes=True, operation_gap=[('POST','/health')]였다. 추가로 정적 추론상 RequestMappingHandlerMapping만 조회하고 Kotlin main 출력 디렉터리에 class가 있는 핸들러만 남기므로 Java 컨트롤러·RouterFunction·다른 HandlerMapping도 기존 Kotlin 매핑 하나만 남아 있으면 조용히 제외된다.
  Recommendation: 발견·계약 집합을 최소 (HTTP method, normalized pattern) 단위로 정확 대조하고 필요한 경우 consumes/produces도 포함하라. 모든 HandlerMapping 종류를 열거하거나 지원하지 않는 종류가 존재하면 실패시켜라. 출력 경로 기반 제외 대신 테스트 컨트롤러를 별도 컨텍스트로 격리하고, 같은 경로의 새 메서드·Java 컨트롤러·RouterFunction 변이를 필수 음성 대조로 추가하라.
- [medium] null·누락·루트 타입 불일치가 계약 오류로 번역되지 않고 내부 DTO 이름까지 노출한다 (backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt:305-318)
  bodyReadItem은 원인 체인에서 MismatchedInputException만 인정한다. 잠긴 Jackson 3.1.4와 컴파일된 SignupRequest를 jshell --execution local로 실행한 결과, email:null과 email 누락은 모두 ValueInstantiationException(cause=NPE, path=[])이었고 루트 []는 targetType=kr.easydoc.api.auth.SignupRequest, path=[]인 MismatchedInputException이었다. 따라서 null/누락은 {loc:['body'],msg:'JSON decode error',type:'json_invalid'}로 오분류되고, 루트 배열은 msg에 SignupRequest라는 내부 클래스명이 실린다. 새 S-9b 테스트는 detail이 배열인지와 키 집합만 검사해 loc/msg/type 값의 위반을 그대로 통과시킨다. 이는 계약의 필드 누락·타입 불일치 422 배열 의미와 구현 산출물의 ‘경로와 목표 타입만 안전하게 읽는다’ 주장을 충족하지 못한다.
  Recommendation: 필수 creator property의 missing/null을 Jackson 설정이나 DTO 메타데이터에서 명시적으로 실패시키고, ValueInstantiationException도 거절값·예외 메시지를 읽지 않으면서 필드별 loc/type으로 번역하라. 알 수 없는 targetType은 고정된 ‘object/value’ 어휘로만 응답하고 클래스명은 내보내지 마라. null, 누락, 루트 배열/스칼라, 중첩 배열 인덱스에 대해 loc/msg/type 전체 값을 HTTP 경계에서 단언하라.
- [medium] 타이밍 회귀 테스트가 최대 4배 차이를 허용하고 순차 5표본이라 B-1의 잔여 누설을 증명하지 못한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthEndpointReachTest.kt:135-160)
  MAX_TIMING_RATIO=4.0은 계약에서 유도되지 않고 단지 수정 전 42배와 기대값 1 사이에서 임의로 정해졌다. 현행 약 100ms 해시 기준으로 예를 들어 26ms 대 100ms의 반복 관측 가능한 계정 열거 신호도 약 3.85배라 초록이다. 또한 없는 계정 표본 6개를 모두 잰 뒤 존재 계정 표본 6개를 재므로 JIT·GC·열 상태·CI 부하가 한 집단에 몰릴 수 있고, 표본도 경로당 5개뿐이다. 구현자가 실행한 음성 대조는 더미 호출을 완전히 제거한 42배 회귀만 증명하며 부분 비용 드리프트나 DB hit/miss·세마포어 대기 차이를 검출한다는 증거는 아니다. 이 항목은 정적 분석이며, 현재 샌드박스에서 HTTP 타이밍 실행은 하지 못했다.
  Recommendation: 전용 비병렬 성능 테스트에서 두 경로를 무작위로 교차 실행하고 충분한 표본·반복 실행으로 동등성 구간을 판정하라. 4배 허용치를 폐기하고 실제 지터 측정에 근거한 훨씬 좁은 비율과 절대 차이 한계를 함께 사용하라. 더미 제거뿐 아니라 저비용 더미, 추가 DB 지연, 세마포어 경합 변이가 각각 빨강인지 확인하라.

Next steps:
- 위 세 결함을 수정한 뒤 동일 경로 새 HTTP 메서드, Java/RouterFunction 매핑, null·누락·루트 배열, 부분 타이밍 드리프트 변이를 실행한다.
- 쓰기 가능한 격리 worktree에서 요청된 Gradle 4조건과 보호 경로 변이 (a)~(d)를 재실행한다. 현재 `./gradlew :api:test --dry-run --no-daemon`도 Gradle zip.lck 생성이 Operation not permitted로 실패했다.
- 재검증 종료 후 초기 상태와 동일하게 기존 미추적 4개만 남고 tracked/staged diff가 없는지 다시 확인한다.
````

---

## 4. 정리(가공) — Claude 색인

> **여기서부터는 Claude 가 쓴 구획이다.** §3 원문과 명확히 분리하기 위해 절을 나눴다.
> 이 구획에서도 **옳고 그름을 판정하지 않는다** — 심각도는 codex 가 붙인 라벨을 그대로 옮겼고,
> 재부여·중복 병합·오탐 표시·"이미 처리했다" 기각 어느 것도 하지 않았다. 그 판단은
> `migration-reviewer` 2차 호출과 리더의 몫이다.

### 4.1 지적 3건 — 축별 색인

| # | codex 심각도 | 리더 지정 축 | 지목 파일·라인 (codex 표기 그대로) | codex 가 쓴 제목 |
|---|---|---|---|---|
| C-1 | **high** | ③ 보호 경로 자동 발견 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthenticationCoverageContractTest.kt:156-175` | 자동 발견이 HTTP 오퍼레이션을 경로 하나로 축약해 계약 밖 공개 메서드를 놓친다 |
| C-2 | **medium** | ② C4 처방의 도달 | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt:305-318` | null·누락·루트 타입 불일치가 계약 오류로 번역되지 않고 내부 DTO 이름까지 노출한다 |
| C-3 | **medium** | ① B-1 해제 검증 | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthEndpointReachTest.kt:135-160` | 타이밍 회귀 테스트가 최대 4배 차이를 허용하고 순차 5표본이라 B-1의 잔여 누설을 증명하지 못한다 |

**축 ④(Gradle 선언 입력·계약 대조 확대·H-1)에서 codex 는 지적을 내지 않았다.** focus text 가
"지적할 것이 없는 축이 있으면 없다고 명시하라" 를 요구했고, codex 는 요약에서
*"축 ④ Gradle/H-1에서는 정적 분석상 추가 결함을 찾지 못했지만, 읽기 전용 환경 때문에 변이·캐시
실험은 실행하지 못했다"* 고 답했다. **"지적 없음" 과 "검증 미수행" 이 같은 문장에 함께 있다** —
이 구분은 §5 에 다시 적었다. 이 자리를 Claude 가 대신 채우지 않았다.

### 4.2 codex 가 지적으로 세지 **않은** 관측 1건 (요약에만 있음)

원문 요약 4번째 문장이다. 지적 목록에 오르지 않았으나 내용이 축 ①에 직접 걸리므로 색인에 남긴다.

> 구현 산출물의 "더미 검증은 언제나 false" 주장도 알려진 원문을 해시하는 코드와 불일치하지만,
> 현재 서비스는 결과를 무시하고 401을 던지므로 별도 차단 항목으로 세지 않았다.

codex 자신이 "별도 차단 항목으로 세지 않았다" 고 명시했다. 승격·강등 어느 쪽도 하지 않고
그대로 옮긴다.

### 4.3 codex 가 근거로 든 실행 (원문에 적힌 것)

각 지적에 붙은 재현 서술을 **codex 가 쓴 대로** 분류한다. 이 표는 "실행했다/추론했다" 의
codex 자기 신고를 옮긴 것이며, 그 신고의 참·거짓은 판정하지 않았다.

| # | codex 자기 신고 | 원문에 적힌 관측값 |
|---|---|---|
| C-1 | **실행** + 정적 추론 병기 | `contract_has_POST_health=False`, `test_path_projection_passes=True`, `operation_gap=[('POST','/health')]` — 계약 YAML 을 파싱해 `('POST','/health')` 를 추가한 뒤 테스트와 같은 방식으로 path 투영. Java 컨트롤러·RouterFunction·다른 HandlerMapping 부분은 **정적 추론**이라고 스스로 표시 |
| C-2 | **실행** | 잠긴 Jackson 3.1.4 + 컴파일된 `SignupRequest` 를 `jshell --execution local` 로 실행. `email:null` 과 `email` 누락 → `ValueInstantiationException(cause=NPE, path=[])`, 루트 `[]` → `targetType=kr.easydoc.api.auth.SignupRequest, path=[]` 인 `MismatchedInputException` |
| C-3 | **정적 분석** (스스로 명시) | *"이 항목은 정적 분석이며, 현재 샌드박스에서 HTTP 타이밍 실행은 하지 못했다."* |

### 4.4 인용 라인 범위 실재 확인 (사실 확인만)

codex 가 지목한 세 라인 범위가 저장소에 실재하고 서술한 코드와 대응하는지만 확인했다.
**지적 내용의 타당성은 판정하지 않았다.**

| # | 지목 | 실재 | 그 범위에 있는 것 |
|---|---|---|---|
| C-1 | `AuthenticationCoverageContractTest.kt:156-175` (파일 201행) | ○ | `servedPaths()`·`servletErrorPath()`·`isProductionClass()` |
| C-2 | `GlobalExceptionHandler.kt:305-318` (파일 465행) | ○ | `bodyReadItem()` 본체 |
| C-3 | `AuthEndpointReachTest.kt:135-160` (파일 482행) | ○ | 비 단언 + `medianLoginMillis()` |

세 건 모두 일치하므로 **"전제 확인 필요" 표시를 붙일 항목이 없다.**

### 4.5 codex 가 적은 Next steps (원문 그대로)

- 위 세 결함을 수정한 뒤 동일 경로 새 HTTP 메서드, Java/RouterFunction 매핑, null·누락·루트 배열, 부분 타이밍 드리프트 변이를 실행한다.
- 쓰기 가능한 격리 worktree에서 요청된 Gradle 4조건과 보호 경로 변이 (a)~(d)를 재실행한다. 현재 `./gradlew :api:test --dry-run --no-daemon`도 Gradle zip.lck 생성이 Operation not permitted로 실패했다.
- 재검증 종료 후 초기 상태와 동일하게 기존 미추적 4개만 남고 tracked/staged diff가 없는지 다시 확인한다.

---

## 5. 미실행·실패 항목

**⚠ codex 리뷰는 누락되지 않았다** (종료 코드 0, 지적 3건). 아래는 **리뷰 안에서 실행되지 못한
검증 항목**이며, 이 리뷰가 무엇을 덮지 **못했는지**를 표시한다.

### 5.1 focus text 가 요구했으나 실행되지 못한 변이·재현

| 축 | 요구한 것 | 실행 여부 | codex 가 밝힌 사유 |
|---|---|---|---|
| ③ | 변이 (a) 보호 컨트롤러 추가 후 목록 누락 / (b) 계약 밖 경로 추가 / (c) `PROTECTED_PATH_PATTERNS` 비우기 / (d) 계약 `security` 뒤집기 | **미실행** | 읽기 전용 환경 — 쓰기 가능한 격리 worktree 필요 |
| ④ | Gradle 4조건 (a) UP-TO-DATE / (b) 계약만 변경 시 재실행 / (c) `cleanTest`·캐시 / (d) `'401'→'403'` 양성 대조 | **미실행** | 같은 사유. `./gradlew :api:test --dry-run --no-daemon` 자체가 실패 |
| ① | HTTP 경계 타이밍 실측 | **미실행** | *"현재 샌드박스에서 HTTP 타이밍 실행은 하지 못했다"* |

**리더가 지정한 축 4개 중 ①·③·④ 의 실행 검증분이 이 회차에서 닫히지 않았다.** C-1 과 C-2 는
codex 가 다른 수단(계약 YAML 파싱 투영, `jshell`)으로 우회 재현했으나, 축 ④ 의 Gradle 4조건은
어떤 우회로도 재현되지 않았다 — 축 ④ 는 **정적 읽기만** 이뤄졌다.

### 5.2 실패한 셸 명령 5건 (헬퍼 로그 원문)

```
[codex] Command failed: /bin/zsh -lc "rg -n \"null|타입 불일치|coerc|S-9b|password.*null|email.*null\" backend-kotlin/api/... (exit 2)
[codex] Command failed: /bin/zsh -lc "printf '%s\\n' 'import tools.jackson.databind.json.JsonMapper;' 'import tools.j... (exit 1)
[codex] Command failed: /bin/zsh -lc "printf '%s\\n' 'import tools.jackson.databind.json.JsonMapper;' 'import tools.j... (exit 1)
[codex] Command failed: /bin/zsh -lc "class Probe { public static void main(String[] a) { System.out.... (exit 1)
[codex] Command failed: /bin/zsh -lc './gradlew :api:test --dry-run --no-daemon' (exit 1)
```

마지막 줄이 축 ④ 미실행의 직접 원인이다 — codex 는 원문에서 사유를
*"Gradle zip.lck 생성이 Operation not permitted"* 로 적었다.

### 5.3 종료 줄이 남지 않은 명령 4건

시작 **49**건 / 완료 **40**건 / 실패 **5**건 = 45건. **4건은 종료 줄이 헬퍼 로그에 남지 않았다.**
사유는 기록되지 않았다. 어느 명령인지 특정할 수 없어 추측으로 메우지 않는다.

### 5.4 출력 잘림 여부

**잘리지 않았다.** codex 출력은 `Verdict:` → 요약 → `Findings:` 3건 → `Next steps:` 3항으로
구조가 끝까지 닫혀 있고, 스크립트가 stdout 전량(6,084 바이트)을 캡처했다. 백그라운드 실행
출력도 파일로 직접 받아 도구 잘림이 없다.

### 5.5 재시도 여부

**재시도하지 않았다.** 1회차가 종료 코드 `0` 으로 성공했다(§7 공통 원칙의 재시도는 실패 시
조항이다). 회차는 이번이 `03_auth-fixes` 어간의 **1회차**다.

---

## 6. 수신자에게

- **`migration-reviewer` (2차 교차 종합)**: 이 파일의 §3 이 codex 원문이다. §4 는 색인일 뿐이며
  §3 을 대체하지 않는다. 교차 대조표의 **출처** 칸은 §4.1 의 C-1~C-3 을 `codex` 로 표기하면 된다.
  §4.2 의 관측 1건은 codex 가 지적으로 세지 않은 것이므로 그 사실과 함께 다뤄 달라.
- **리더**: codex 판정은 `needs-attention` — 원문 표현으로 **"출하 차단"** 이다. 이 에이전트는
  Phase·단위 종료 가능 여부를 **판정하지 않는다.**
- **축 ④ 의 실행 검증분이 비어 있다**(§5.1). 이 회차가 축 ④ 를 실행으로 닫았다고 읽으면 안 된다.
