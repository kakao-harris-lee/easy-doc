# 데이터 보호 불변식 스캐너 BLOCK 8건 — 처방 판정 (privacy-gate)

- 대상: CI run 32211120665 (headSha `6fe4357`) `quality` 잡 「데이터 보호 불변식 스캔」 스텝 실패, 뒤 8스텝 skip
- 재현 시점 HEAD: `cc7268c` — 같은 인자로 로컬 재현 시 BLOCK 8건 / exit 1
- 판정 근거: `CLAUDE.md` 규칙 4(은폐형으로 닫지 않는다), 억제 계약 정본 `07_privacy-gate_masking-verdicts.md` §4-octies·§4-novies·§4-undecies

## 0. 결론

| # | 건 | 처방 | 형태 | 적용 |
|---|---|---|---|---|
| 1 | `SECRET-LITERAL` × 1 (`RequestFieldConstraintLayerTest.kt:229`) | **코드 측 해소 — 상수 이름 변경** | 코드 | 적용함 |
| 2 | `OWNERSHIP-403` × 7 (`WorkspaceEndpointReachTest.kt`) | **규칙 정밀화 (탐지형)** | 탐지 | 적용함 |

**마커는 한 건도 쓰지 않았다.** 예산은 7/7 그대로이고 올리지 않았다.
CI 스텝과 동일 명령 **exit 0**.

## 1. 마커가 처방이 아닌 이유 (두 건 모두)

지시문은 SECRET-LITERAL에 마커가 맞을 수 있다고 봤으나, **억제 계약이 그 통로를 이미 닫아 두었다.**

`scan_privacy_invariants.py`의 `UNMARKABLE_RULES`에 여섯이 들어 있고 그중 **둘이 이번 두 규칙**이다 — `SECRET-LITERAL`, `OWNERSHIP-403`. §4-octies.3의 지정이며 코드가 그것을 자기검사로 강제한다(`markable=True`가 붙으면 import 시점에 `AssertionError`). 계약문:

> 정당한 오탐이 존재할 수 없는 규칙은 `False`로 둔다 — 그 규칙에서 "오탐이니 눌러 달라"가 나오면 그것은 표기가 아니라 **판정 요청**이어야 한다.

따라서 마커를 쓰려면 **면제 대상 규칙 목록에서 규칙을 빼는** 편집이 선행해야 한다. 그것이 정확히 규칙 4가 금지하는 **은폐형(면제 조항 확대)**이다. 부수적으로 예산도 이미 7/7이라 넘긴다 — 넘긴다고 적고 올리지 않았다.

- 근거: `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` `UNMARKABLE_RULES` 및 그 아래 자기검사 루프
- 판정: **마커 = 위반. 두 건 모두 다른 처방이 필요하다.**

## 2. SECRET-LITERAL — 코드 측 해소

### 왜 걸렸나

`const val SIGNUP_PASSWORD = "SignupRequest.password"`. 규칙의 이름 관문은 `password\s*[:=]`로 **왼쪽 경계가 없어** 식별자 접미사에도 걸린다(의도된 설계 — `user_password = …`를 잡기 위함). 값 판정 `looks_like_real_secret`은 문자 클래스 3종(소문자·대문자·`.`) · 길이 22 · 엔트로피 3.97 bits/char로 임계(3종·12자·3.2)를 모두 넘겨 통과했다.

### 처방과 근거

**상수를 `SIGNUP_PASSWORD_FIELD`로 개명**(대칭으로 `SIGNUP_EMAIL` → `SIGNUP_EMAIL_FIELD`). 로직 변경 0, 파일 내부 전용 `private companion object` 상수, 사용처 3+3 전건 치환. `:api:compileTestKotlin` exit 0.

개명이 회피가 아닌 이유: 담긴 것은 **계약 필드 경로**이지 비밀번호가 아니다. 이름이 내용과 어긋나 있었고 개명이 그것을 바로잡는다 — 규칙이 조용해지는 것은 원인이 아니라 결과다. 같은 처방을 저장소가 이미 규정하고 있다: `LLM-RAW-INPUT`(역시 unmarkable)의 오탐 안내가 *"이름을 masked\*로 바꿔 의도를 드러낼 것"*이다. **값-이름 불일치의 정본 처방이 개명**이라는 전례.

되돌림 방지로 선언 자리에 KDoc을 남겼다(왜 접미사가 붙었는지 + 되돌리면 CI가 다시 빨개진다).

### 기각한 갈래

- **탐지 정밀화(값이 점 구분 식별자 경로면 비밀 아님)** — 기각. JWT가 점 구분이고, 숫자 없는 base64 키가 존재할 수 있다(43자 기준 ≈0.05%). **비밀키 BLOCK 규칙을 좁히는 데 쓰기엔 근거가 얇다.** 이번 배치에서 탐지 좁힘은 OWNERSHIP-403 한 건으로 제한했다.
- **임시 마커** — 지시대로 달지 않았고, 위 §1로 애초에 불가.

## 3. OWNERSHIP-403 — 규칙 정밀화 (탐지형)

### 왜 걸렸나 — 구조적 결함

옛 패턴은 `\b(?:403|FORBIDDEN|Forbidden)\b` 하나. **토큰이 보이면 무조건 BLOCK**이라 다음이 전부 걸렸다:

| 줄 | 내용 | 성격 |
|---|---|---|
| 181 · 296 | `@DisplayName("… 403 이 아니다")` | 테스트 이름 |
| 182 | `fun \`타인 자원은 404 이고 403 이 아니다\`()` | 테스트 이름 |
| 189 · 304 | `assertThat(…).isNotEqualTo(FORBIDDEN)` | **불변식 집행** |
| 533 | `private const val FORBIDDEN = 403` | 위 단언이 쓰는 상수 (토큰 2개) |

**404 소유권 은닉 불변식을 지키는 코드가 그 불변식의 게이트에 막혔다.** 규칙의 `false_positive` 주석이 *"테스트의 403 기대값이면 오탐"*이라고 이미 적고 있었는데 심각도는 BLOCK이고 마커도 못 쓴다 — **정당한 오탐에 출구가 없는 게이트**였고, 그 상태의 CI 빨강은 무시·면제로만 풀린다.

전수 census 결과 **저장소의 어떤 생산 코드도 403을 만들어 내지 않는다** — 8건 전부 주석·테스트 이름·집행 단언·상수다.

### 처방

**불활성 형태를 먼저 소비하는 대안**을 패턴 앞에 두고, 그 그룹이 참여했으면 `refine`이 후보에서 뺀다. 세 형태를 뺀 근거는 셋 다 같은 문장이다:

> 그 자리는 403 응답을 보낼 수 없고, **보낼 수 있는 자리는 여전히 전부 잡힌다.**

| 형태 | 뺀 범위 | 무손실 근거 |
|---|---|---|
| ① 부호 반전 단언 | `isNotEqualTo`/`assertNotEquals`/`assertNotSame`/`isNotIn`의 **첫 인자**가 403 토큰일 때 | 부호가 반대다. `isEqualTo(FORBIDDEN)`(403을 **기대**하는 단언 — 진짜 위반 신호)은 그대로 잡힌다 |
| ② 테스트 이름 | `@DisplayName("…403…")` · 백틱 식별자 — **토큰을 품은 것만** | 라벨·식별자는 값이 아니다 |
| ③ 상수 선언 | `val`/`var`/`const`/`let` 키워드가 있거나, Python은 **대문자 상수**만 | 선언은 묶기만 한다. `status(FORBIDDEN)` 같은 사용처는 `FORBIDDEN` 토큰으로 여전히 매치 |

③이 특히 강하다 — **무손실을 실행으로 증명**했다(대조 C).

`refine` 판별식(§4-octies.7) 통과: 이 훅은 **자기 패턴이 직접 소비한 캡처 그룹**이 참여했는지만 본다. 바깥 텍스트도, `_advance`·`_argument_span`의 산출물도 읽지 않는다 — 어휘·구문 층의 정확성에 의존하지 않으므로 `LOG-BODY`가 다섯 갈래를 냈던 형태가 아니다.

### 기각한 더 넓은 갈래 (근거를 넘지 않는다)

- **문자열 리터럴 전체 제외** — *"상태 코드는 세 스택 모두 정수·열거값이지 문자열이 아니다"*는 참이나, `@ApiResponse(responseCode = "403")` · `responses={"403": …}` 같은 **403 응답 선언**을 조용히 삼킨다. 그것은 이 불변식이 봐야 할 신호다. (대조 N10이 이 기각을 회귀로 고정)
- **경로 면제(`tests/` 통째로)** · **심각도 강등(BLOCK→WARN)** — 둘 다 은폐형.
- **`hardened` 창** — 창 안의 다른 403까지 통째로 눌러 **줄 단위 부수 피해**가 난다. 소비형은 그 자리만 뺀다(대조 N12·N13이 이 차이를 잰다).

첫 구현은 백틱 대안을 `` `[^`\n]*` ``로 두었다가 KDoc 인라인 코드를 전부 삼켜 제외 집계가 **1446건**으로 폭증했다. 뺀 것이 없는데도 "규칙이 눈감은 양"이 거짓이 되는 상태 — 리포트가 다음 감사의 입력이므로 토큰을 품은 것만 매치하도록 조였다(최종 6건).

### 부수 수정

`Rule.refine_reason` 신설(필드는 **끝에** 추가 — 위치 인자 밀림 방지 주석 준수). 옛 코드는 모든 `refine` 제외를 `"값의 모양이 불변식 대상이 아님"` 한 문장으로 적었는데, OWNERSHIP-403은 값의 모양을 보지 않는다. 리포트가 무엇을 눈감았는지 잘못 적는 것을 막는다.

## 4. 음성 대조 (정밀화가 진짜 403 반환을 놓치지 않음)

### 4-1. 실제 게이트 종단 대조 — 스캔 루트에 주입 후 CI와 동일 명령

주입 파일은 신규 생성 후 삭제(기존 파일 무수정, 복원 위험 0). 잔여 0 확인.

| 대조 | 주입 | exit | 검출 |
|---|---|---|---|
| 0 기준선 | — | **0** | — |
| A | `ResponseEntity.status(403).build()` | **1** | `[BLOCK] OWNERSHIP-403` 1건 |
| B | `ResponseEntity.status(HttpStatus.FORBIDDEN).build()` | **1** | `[BLOCK] OWNERSHIP-403` 1건 |
| C | `const val FORBIDDEN = 403` **+ 사용처** `status(FORBIDDEN)` | **1** | `[BLOCK] OWNERSHIP-403` 1건 |
| D | 난수꼴 `jwtSecret` 리터럴 | **1** | `[BLOCK] SECRET-LITERAL` 1건 |
| E | 이름만 `SIGNUP_PASSWORD`로 되돌림 | **1** | `[BLOCK] SECRET-LITERAL` 1건 |
| 복원 후 | — | **0** | — |

D·E는 **SECRET-LITERAL 탐지를 건드리지 않았음**의 증명이다 — 규칙은 그대로이고, 개명이 해소의 원인이다(E가 그것을 보인다).

### 4-2. 상시 회귀 — `tests/test_privacy_scanner.py` 형태 목록 19건

`blocks=True` 13건(생산 형태)과 `blocks=False` 6건(집행·명명 형태). **스캐너 본류 `scan()`을 그대로 부른다** — 패턴만 돌리면 `refine`을 건너뛰어 정밀화를 재지 못한다.

잡는 쪽(N1~N13): Spring `status(403)` · `HttpStatus.FORBIDDEN` · `ResponseStatusException` · `@ResponseStatus` · `sendError(403)` · **양성 단언 `isEqualTo(FORBIDDEN)`** · FastAPI `HTTPException(status_code=403)` · Python 속성 대입 `response.status_code = 403` · TS `res.status(403)` · `@ApiResponse(responseCode="403")` · 상수 선언+사용처 · **불활성 형태와 같은 줄의 진짜 반환 2건**.

마지막 둘이 소비형 채택의 근거를 회귀로 고정한다 — 창 억제였다면 통과하지 못한다. `test_정밀화가_소비형이라_줄_전체를_누르지_않는다`가 `hardened is None` · `sanctioned` 비어 있음을 함께 못 박는다.

## 5. 남는 것 (닫지 않았다고 적는다)

1. **`HTTP_403_FORBIDDEN` · `SC_FORBIDDEN` 미도달 — 선언된 0으로 전환.**
   `\b` 경계 때문에 밑줄에 둘러싸인 토큰은 잡히지 않는다. **정밀화 이전부터 그랬고**(옛 패턴 실측: 무적중) 이번 변경과 무관한 **기존 결함**이다. 경계를 풀지 않은 이유: `FORBIDDEN_IN_FILENAME`(파일명 정화) · `FORBIDDEN_ANNOTATIONS`(계약 검사) 등 **HTTP와 무관한 이름**이 전부 BLOCK이 되어, 출구 없는 규칙에 새 오탐 무리를 들인다. 조용한 0 대신 `xfail(strict=True)` 2건으로 **선언**했다 — 누가 탐지에 넣으면 `xpass`로 뒤집혀 시끄러워진다. **넓힐지는 별건 판정 대상.**
2. **개명의 잔여 위험.** 규칙의 이름 관문이 이름 기반이므로, 장래에 `SIGNUP_PASSWORD_FIELD`에 진짜 키를 넣으면 걸리지 않는다. 이름 기반 관문의 내재적 성질이고 개명이 새로 만든 것이 아니다. KDoc이 그 자리를 지킨다.
3. **`tests/test_privacy_scanner.py:73 _log_body_verdict` 가 썩어 있다.** 존재하지 않는 `scanner._is_candidate`를 부르는데 **호출자가 0**이라 조용하다(부르면 `AttributeError`). 이번 변경과 무관하고 게이트도 아니라 손대지 않았다 — 정리는 별건으로 남긴다.
4. **마커 예산 7/7 그대로.** 올리지 않았고 새로 쓰지도 않았다.

## 6. 불변식 판정 (감사 축)

| # | 불변식 | 판정 | 근거 |
|---|---|---|---|
| 5 | 타인 자원은 404 | **준수** | `WorkspaceEndpointReachTest` WR-3·WD-2/3이 `isNotEqualTo(FORBIDDEN)` + 404 + 없는 자원과 본문·헤더 동일까지 단언. **코드는 한 줄도 바꾸지 않았다** |
| — | 게이트 자신의 탐지력 | **준수** | §4 음성 대조 6종 종단 + 회귀 19건 |
| — | 은폐 장치 도입 여부 | **없음** | 마커 0 · 경로 면제 0 · 예산 인상 0 · 심각도 강등 0 |

**차단 사유서 없음** — 8건 전부 오탐이었고, 불변식을 어긴 제품 코드는 발견되지 않았다.
