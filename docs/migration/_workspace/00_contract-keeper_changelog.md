# 계약 변경 이력 (contract-keeper)

`contracts/easy-doc-v1.yaml`이 **정본**이다. 이 파일은 "무엇이 왜 바뀌었고 누구에게
알려야 하는지"만 남긴다 — 스펙 전문을 옮겨 적지 않는다(두 벌이 갈리면 정본을 잃는다).

---

## 2026-08-12 · R-1 · 재개발 전환으로 근거를 잃은 호환 요구 정리

| 항목 | 값 |
|---|---|
| 계기 | **2026-08-12 2차 사용자 결정** — Python 코드를 폐기하고 재개발한다. **롤백을 포기**했으므로 Fernet 암호문·JWT·Argon2의 바이트 동일성 요구가 근거를 잃었다. 표준 AEAD를 처음부터 쓴다 |
| 근거 | **G3** (정책 결정 변경) + **G1** (재해시 판정 기준·clock skew 서술이 사실과 달랐다 — Phase 0 실측) |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 와이어 스키마 무변경. 상태 코드·오류 본문·필드 이름·enum이 하나도 안 바뀌었고, 바뀐 것은 인증 검증 규약의 서술과 503 사유의 **명칭**뿐이다 |
| React 영향 | **없음.** `types.ts`·`client.ts`·컴포넌트 어느 쪽도 Argon2 파라미터·JWT 허용 오차·503 사유 명칭을 읽지 않는다. 토큰 만료는 `client.ts`가 401 응답으로만 감지하고 그 계약은 그대로다 |
| 통보 대상 | `kotlin-implementer`(필수조치 A·B는 살아 있는 요구), `parity-verifier`, `privacy-gate`(키 명칭만 변경), 리더 |

### 원칙 — 없어진 것은 "Python과 같은가"이지 "올바른가"가 아니다

호환 프레이밍을 걷어내면서 그 안에 들어 있던 **기능 요구까지 함께 날리면**, Phase 0에서
실측으로 찾은 결함 두 개가 검증 없이 되살아난다. 그래서 세 자리 모두 **삭제가 아니라
재작성**이고, 재작성한 자리마다 무엇이 왜 바뀌었는지를 남겼다.

- **필수조치 A (살아 있음)** — Argon2 재해시 판정을 **전체 파라미터 동등성**으로.
  Spring `Argon2PasswordEncoder.upgradeEncoding()`은 `memory`·`iterations`의 **"미만"만**
  보므로 파라미터를 **낮춘** 경우와 `parallelism`만 바뀐 경우를 놓친다.
- **필수조치 B (살아 있음)** — JWT **clock skew 0**. Nimbus·Spring 기본값 60초를 그냥 두면
  만료된 토큰이 **+59초까지 통과한다**. `exp` 필수 조항이 강제되려면 이것이 함께 있어야 한다.

### 바뀐 조항 (`contracts/easy-doc-v1.yaml`)

| 조항 | 종전 | 지금 |
|---|---|---|
| `x-auth.rehash_policy` | "기존 PHC 문자열은 **그대로 검증 가능해야** 한다" (호환 요구) | 재해시 판정 = 변형·`time_cost`·`memory_cost`·`parallelism` **전체 동등성** (기능 요구). `upgradeEncoding()` 함정 명시 |
| `x-auth.clock_skew_seconds` · `x-clock-skew` | 없음 | **신설.** 허용 오차 0, 기본 60초의 실패 양상 명시 |
| `x-auth.x-rebuild-note` | 없음 | **신설.** 양방향 상호 수용 요구의 폐기와 **무엇이 남았는지** |
| `responses.ServiceUnavailable` | "**Fernet** 키 미설정 → 문서 API 전체" | "**저장 암호화 키** 미설정 → 문서 API 전체" + 개명 사유. **상태 코드(503)·동작·`detail` 문구 불변** |
| `x-global-response-headers.x-python-deviation` | "인정된 이탈 / 관찰 기간의 기록된 차이 / parity 불일치로 올리지 않는다" | **`x-origin-of-the-ten`으로 재작성** — 10곳 목록의 출처만 남기고 parity 지시는 대상 소멸 처리 |
| `info.description` · `x-superseded` · `x-not-a-scope` | 위 키를 참조 | 새 키를 참조하도록 3곳 갱신 |

### 재심 — "Python은 현행 10곳 유지, 인정된 이탈" (OQ-1 부수 결정 2)

**판정: 대상 소멸(moot). 리더 판정을 뒤집은 것이 아니다.**

그 결정이 붙어 있던 지시는 "관찰 기간에 두 런타임의 헤더가 다른 것은 기록된 차이이며
parity 대조에서 불일치로 올리지 않는다"였다. 이 지시는 **Python과 Kotlin이 함께 도는
관찰 기간과 롤백 창**을 전제한다. 롤백을 포기하고 Python을 폐기하면 헤더가 갈릴 상대
런타임이 없으므로, 기록할 차이도 올리지 않을 불일치도 존재하지 않는다.

결론 자체("Python은 고치지 않는다")는 오늘도 그대로다 — 고칠 Python이 남지 않기 때문이다.
그래서 이것은 `x-change-policy.escalate_to_leader` 1번이 금지하는 **판정 뒤집기가 아니라
판정 대상의 소멸**이며, 리더가 이 정리를 직접 지시했다.

**남긴 것**: 10곳 목록의 출처(Python `PRIVATE_RESPONSE_HEADERS`가 붙던 자리). 그 목록을
하한선으로 유지하는 근거는 연혁이 아니라 `x-why-these-ten`의 위험도 기준이므로, 출처가
사라져도 하한선은 그대로다.

### 바뀐 조항 (`.claude/skills/api-contract-freeze/SKILL.md`)

- **§2.4** — Argon2 항목을 전체 파라미터 동등성으로 재작성, JWT 항목을 **계약 준수
  fixture**(clock skew 0 포함)로 교체. 양방향 fixture가 왜 폐기됐고 무엇이 남았는지를
  절 끝에 산문으로 남겼다.
- **§2.5** — "Python은 이 전역 요구를 만족하지 않는다 — 인정된 이탈" 항목을 **대상 소멸**
  정리로 교체. 10곳의 출처만 남기고 grep은 연혁 확인용으로 표시했다.
- **§2.8 판정 9 부수 결정 ②** — 취소선 + 대상 소멸 표시.
- **§5 추가 테스트 목록** — "양방향 fixture" → **JWT 계약 준수 fixture** + **Argon2 재해시
  판정 단위 테스트**.
- **§5.1 대조표의 Python 행** · **§5 맺음** — Python suite가 남지 않는다는 사실 반영.

### 영향받는 contract test (`00_contract-keeper_test-plan.md`)

| ID | 종전 | 지금 |
|---|---|---|
| **X-I1** | Python 발급 JWT를 Kotlin이 수용하고 그 반대도 된다 (X 계층) | `sub`/`exp`/`typ` HS256 수용 + `typ` 오값·`exp` 누락·서명 위조·**만료 직후** 401 (**C 계층**) |
| **X-I2** | 기존 Argon2 PHC가 그대로 검증되고 성공 시에만 재해시 (X 계층) | 파라미터가 **하나라도 다르면** 재해시 (**U 계층** — HTTP 경계에서 안 보인다) |
| 계층 표 | **X. 교차 런타임**(양방향 parity fixture) | 삭제 → **U. 단위** 신설. 폐지 사유 명시 |
| 실행 시점 표 | Phase 2 = X-I1·X-I2 | Phase 2 = 계약 소유 항목 없음, X-I1·X-I2는 **Phase 3**으로 |
| 머리말 · §0-3 · §4 | "Python suite가 비교 기준선" / "두 런타임이 같은 표를 읽는다" / "Python 기준선 커버리지" | 기준선이 아니라 **연혁**으로 재서술. §0-3의 데이터-우선 원칙 자체는 유지 |

### 검증

```bash
uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml
# contracts/easy-doc-v1.yaml: OK
```

---

## 2026-08-12 · H-1 · 강제 수단 2층화 + 오류 본문 사각지대 신설

| 항목 | 값 |
|---|---|
| 계기 | **H-1 실측** — kotlin-implementer가 원시 소켓으로 측정(Tomcat 11.0.22 / Boot 4.1.0) |
| 근거 | **G1** (강제 수단 서술이 사실과 달랐다 — "필터 하나"로는 7종에 안 닿는다) + **G4** (오류 본문 불변식의 "모든"이 검증되지 않은 채 참으로 읽히고 있었다) |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 와이어 스키마가 그대로다. 강제 수단 서술·실측 기록은 React 생성 타입에 영향이 없고, 오류 본문 항목은 **새 요구가 아니라 기존 불변식의 적용 범위 명시**다 (U-1·OQ-1과 같은 판단) |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `privacy-gate`, 리더(미측정 항목) |

### ① 강제 수단: 필터 1층 → 필터 + Tomcat Engine 밸브 2층

**이것은 요구를 좁히는 변경이 아니라 강제 수단을 넓히는 변경**이므로
`x-change-policy.invariants` 축소에 해당하지 않는다. 요구(모든 응답에 `no-store`+`nosniff`)는
2026-08-12 리더 판정 그대로이고, 그것을 실제로 만족시키는 방법만 갱신됐다.

실측이 보인 것:

| | 응답 |
|---|---|
| **필터에 닿음** | 핸들러 없는 404 · 415 · 413 · CORS 프리플라이트 OPTIONS 200 · `sendError` → `/error` ERROR 디스패치 503 |
| **필터에 못 닿음 (7종)** | 요청 대상 금지 문자 400 · 콜론 없는 헤더 줄 400 · 요청 줄 파손 400 · 헤더 상한 초과 400 · `Host` 없음 400 · 알 수 없는 HTTP 버전 505 · 알 수 없는 메서드 405 |

못 닿는 7종은 **요청 줄·헤더 블록 파싱 단계에서 거절돼 서블릿에 매핑되지 않는다** —
필터 체인이 시작조차 하지 않으므로 배치를 어떻게 고쳐도 닿지 않는다.
Tomcat Engine 밸브가 7종 전부를 덮는 것을 계측 확인하고 구현했다.
**음성 대조도 돌렸다** — 밸브를 빼면 malformed 계열 3건만 정확히 깨진다.

`escalate_to_leader` 1번의 순서 ①(닿게 만든다)에서 끝나 리더 재심(②)은 필요 없었다.

**기각된 가설**: 후보 원인으로 지목됐던 `OncePerRequestFilter.shouldNotFilterErrorDispatch()`는
기각됐다. 그 오버라이드와 `DispatcherType.ERROR` 등록을 **둘 다 기본값으로 되돌려도**
헤더가 남는다 — 필터가 `chain.doFilter` 앞에서 헤더를 쓰고 Tomcat이 포워딩에서 버퍼만
비우기 때문이다. **후보를 "이 저장소에서 미확인"으로 적어 둔 것이 여기서 값어치를 했다** —
사실로 적었다면 기각을 기록할 자리가 없고 틀린 원인 설명이 계약에 굳었을 것이다.

**밸브는 Tomcat 결합이며 서블릿 표준이 아니다.** 계획 §3.1이 내장 Tomcat을 고정했으므로
감수 범위지만, 계약을 읽는 사람이 "서블릿 표준 필터면 만족된다"고 읽으면 오독이다.
컨테이너를 바꾸면 **기동 시점에 깨진다(조용히 사라지지 않는다)** — 조용히 사라지는
쪽이었다면 7종이 헤더 없이 나가는 것을 아무도 모르게 된다.

### ② 신설: 오류 본문이 경로를 가리지 않는다

H-1 실측의 **범위 밖 부수 발견**이다. `sendError` → `/error` 응답 본문:

```
{"timestamp":"…","status":503,"error":"Service Unavailable","path":"…"}
```

Spring `BasicErrorController` 기본 본문이고 `{"detail": …}`가 아니다. 최상위 키가 넷이라
`ErrorResponse`의 `additionalProperties: false`와 `required: [detail]` 양쪽을 어긴다.
전역 예외 매퍼는 이 경로를 지나지 않는다 — `sendError`는 예외를 던지지 않기 때문이다.

**지금은 드러나지 않는다**(운영 코드가 `sendError`를 안 부른다). **Phase 3에서 드러난다** —
인증 필터가 401을 `sendError`로 내는 것이 가장 흔한 구현이고, 401은 React `client.ts`가
세션 만료 분기로 쓰는 자리라 `readErrorMessage`가 `detail`을 못 찾아 폴백 문구만 남는다.

조항이 틀렸던 것이 아니라 **"모든"의 범위가 검증되지 않은 채 참으로 읽히고 있었다** —
헤더 쪽(`x-global-response-headers`)과 같은 모양의 문제이고 그래서 근거도 같은 G4다.
검증 가능한 형태(E-1~E-3)로 세웠고, **구현 수단은 규정하지 않았다** — 계약이 정하는 것은
나간 바이트뿐이다.

**미측정을 미측정으로 남겼다**: 위 7종의 **본문 모양**은 보지 않았다(이번 실측은 헤더만).
`x-error-body-universality.x-unmeasured`에 "충족으로도 위반으로도 적지 않는다"로 기록했다.

### 바뀐 조항 (`contracts/easy-doc-v1.yaml`)

- `x-global-response-headers.enforcement` — 2층으로 갱신, 각 층의 실측 도달 범위 명시.
- `x-global-response-headers.x-container-coupling` **신설** — Tomcat 결합 사실.
- `x-global-response-headers.x-phase3-measurement` — "미실측" 산문 → **실측 결과 구조체**
  (방법·MockMvc 무효성·도달/미도달 목록·해결·음성 대조·기각된 가설·남은 것).
- `x-global-response-headers.x-failure-mode-shift` · `x-openapi-expressibility` ⑥ — 갱신.
  **절을 지우지 않았다** — 강제 수단을 건드리는 변경은 매번 같은 방식으로 다시 재야 한다.
- **`x-error-body-universality` 신설** — 요구·사각지대·검증 E-1~E-3·미측정·표현 한계.
- `info.description`(오류 본문 계약) · `components.schemas.ErrorResponse` ·
  `x-change-policy.invariants` — 위 절을 정본으로 가리키게 갱신.
- `x-improvements` — OQ-1 `충족 실측 확인`, **V2-8 신설**(오류 본문 적용 범위).

### 바뀐 조항 (`.claude/skills/api-contract-freeze/SKILL.md`)

- §2.2 — "모든 오류 응답은 경로를 가리지 않는다" 항목 추가.
- §2.5 — 부착 수단 "서블릿 필터 하나" → **필터 + 밸브 2층**, Tomcat 결합 경고.
- §2.8 판정 9 — "아직 닫히지 않았다" → 실측으로 닫힘. **판정 11 신설**(오류 본문 범위).
- §5.1 — 표의 "미실측" 행 갱신, G-E 실측 완료, **G-G 신설**(밸브 음성 대조).
- **§5.2 전면 개정** — "Phase 3 미실측 체크리스트" → **실측 결과 표**(방법·결과·해결·
  기각된 가설·남은 것).
- **§5.3 신설** — 오류 본문 검증 E-1~E-4.

### 영향받는 contract test (`00_contract-keeper_test-plan.md`)

| ID | 상태 |
|---|---|
| X-D2c | 미실측 → **실측 완료.** 회귀는 원시 소켓 유지(MockMvc 금지) |
| X-D2d | **신규** — 밸브 음성 대조 |
| X-D2e | **신규** — Tomcat 결합 인지 |
| X-C7 | **신규** — `sendError` 경로 본문이 `{detail}` 하나 |
| X-C8 | **신규** — 401은 구현 수단을 가리지 않는다 (인증 필터 커밋과 **같은 단위**) |
| X-C9 | **신규·미측정** — 컨테이너 생성 응답의 본문 모양 |

`parity-verifier`에게: **헤더는 두 런타임이 의도적으로 다르지만(기록된 차이), 오류 본문
모양은 의도된 차이가 아니다.** Kotlin이 `{"detail": …}` 밖의 본문을 내면 기록이 아니라
차단 대상이다.

---

## 2026-08-12 · OQ-1 · 사적 응답 헤더 전역 부착 — 리더 판정으로 종결

| 항목 | 값 |
|---|---|
| 판정 | **리더 결정 (근거 G4)** — 열거식 범위를 폐기하고 `Cache-Control: no-store` + `X-Content-Type-Options: nosniff`를 **Kotlin 런타임의 모든 응답**에 필터로 부착 |
| 근거 | 열거식 범위가 이 저장소에서 **이미 두 번** 누락됐다(`PUT /conversions/{id}`, `/auth` 3개). 사람이 매번 기억해야 지켜지는 조항은 지켜지지 않는 형태 = G4의 정의 |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 헤더 추가는 비파괴이고 와이어 스키마가 그대로라 React 생성 타입에 영향이 없다. U-1과 같은 판단 |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `privacy-gate` |
| 판정문 전문 | `docs/migration/_workspace/02_contract-rebase.md` §3.2 |

### OQ-1이 열렸다 닫힌 경로

이 항목의 값어치는 결론이 아니라 **경로**에 있다. 같은 결론이 다른 경로로 나왔다면
기록할 이유가 없었다.

1. **2026-08-12 (이전) — 리더 판정 ①**: 오류 응답에는 헤더를 붙이지 않는다(현행 10곳 유지).
   교차 리뷰 X-15를 거친 명시적 판정이었다. 근거는 둘 — ⓐ 오류 본문에 개인정보가 없다는
   실측, ⓑ Kotlin이 지켜야 할 항목이 늘면 어긋날 여지도 늘고 parity 대조가 불일치로 잡는다.
2. **전제가 무너짐**: 사용자 결정으로 "Python과 같은 값을 낸다"가 목표에서 빠지면서
   위 ⓑ가 통째로 사라졌다. 판정 근거의 절반이 없어진 상태.
3. **계약 소유자의 선택 — 뒤집지 않고 올렸다**: 전제가 사라졌다는 사실은 **재심의 근거이지
   뒤집을 권한이 아니다**. 계약 소유자가 스스로 뒤집으면 앞으로 어떤 리더 결정도 "전제가
   바뀌었다"는 주장 하나로 무효화된다. `x-change-policy.escalate_to_leader` 1번을 적용해
   OQ-1로 등록하고 **재심 전까지 구현은 좁은 쪽(10곳)을 기본값**으로 유지했다.
4. **2026-08-12 — 리더 판정 ②**: 전역 부착 승인. 근거는 ⓐ가 아니라 **G4**다 — ⓐ("오류
   본문에 개인정보가 없다")는 여전히 참이지만 그것은 *얻는 것이 작다*는 근거일 뿐
   *좁게 유지해야 한다*는 근거가 아니다. 비용이 사실상 0인 쪽이 결정을 갈랐다.
5. **계약 소유자의 전제 하나가 정정됨**: 재심 제기 시 적었던 *"넓혔다가 좁히려면 서블릿
   API에 헤더 삭제 수단이 없어 되돌릴 수 없다"*는 **과장이었다.** 좁히는 것은 필터에 조건을
   더하는 일이지 전송된 헤더를 회수하는 일이 아니다. 판정이 틀린 전제 위에 남지 않도록
   계약 파일 `x-resolved-question.x-corrected-premise`에 함께 기록했다.
   다만 **비대칭 판단 자체는 유효**하다 — 좁은 쪽을 기본값으로 두고 재심을 올린 절차는 옳았다.

**§2.3-1이 의도대로 작동한 사례다.** 결과가 같아도 경로가 다르다.

### 바뀐 조항 (`contracts/easy-doc-v1.yaml`)

- **`x-global-response-headers` 신설** — 요구의 정본. 근거 G4, 적용 범위, 강제 수단(필터
  배치 + `add`가 아닌 `set`), 실패 양상 전환, **OpenAPI 표현 한계**, Python 이탈,
  Phase 3 실측 항목.
- **`x-private-response-headers`** — **범위에서 하한선으로 성격 변경.** 10곳 목록과
  `paths` 아래 헤더 선언 10건은 **손대지 않았다.** `does_not_apply_to`(DELETE 204 ·
  `/health` · 모든 오류 응답)는 무효화하고 Python 동작 기술로 이관.
  `x-open-question` → `x-resolved-question`.
- **`info.description`** — "응답 헤더 — 전역 요구" 절 추가. 10건 선언을 범위로 읽지 말라는
  경고 + Python 이탈 명시.
- **`x-change-policy.invariants`** — no-store 항목을 전역 요구로 갱신.
- **`x-improvements`** — OQ-1 `반영됨`(G4), V2-3(DELETE 204) `흡수됨`.

### 바뀐 조항 (`.claude/skills/api-contract-freeze/SKILL.md`)

- **§5의 "규칙 1 + 단언 A·B" 기계장치를 주 수단에서 내렸다.** 규칙 1(사적 헤더는
  `ResponseEntity`로만 싣고 필터 금지)이 전역 부착과 **정면 충돌**하고, 단언 B는 부호가
  뒤집혀 폐기된다.
- **그냥 지우지 않았다.** 옛 기계장치의 실질 문제는 설계가 아니라 **근거가 이 저장소에서
  실측된 적 없다**는 것이었고, 전역 필터도 지금 똑같은 상태다. **검증되지 않은 기계장치를
  검증되지 않은 필터로 바꾸는 것은 개선이 아니다** — 그 자리를 §5.2 Phase 3 실측이 대신한다.
- **§5.1 신설** — 무엇이 뒤집혔고 무엇이 그대로인지 표 + 체크 항목 G-A~G-F.
- **§5.2 신설** — Phase 3 실측 절차. **MockMvc로는 측정할 수 없다**는 점이 핵심이다.
- **§2.5 개정** — "붙는 곳 10개"를 "모든 응답 + 하한선 10곳"으로. 오류 응답의 **부정** 단언
  항목을 폐기하고 부호 반전을 명시.
- **§2.7 해결 3 취소선 처리** — 근거(오류 본문에 개인정보 없음)는 여전히 참이나 범위 관리
  방식이 G4로 폐기됐음을 남김. 살릴 것 하나(`tests/api/test_errors.py`의 부정 단언 3건은
  회귀 검출력이 없다)는 유지.
- **§2.8 판정 9 추가**, §1 표 주석·§7.2 갱신.

### Python은 고치지 않는다 — 인정된 이탈

`app/**`를 수정하지 않았다. Python은 현행 10곳 그대로다. 은퇴 예정 런타임에 전역
미들웨어를 새로 넣는 회귀 위험이 이득보다 크고, 오류 응답에 개인정보가 없다는 실측이
그 위험을 감수할 이유를 없앤다.
**관찰 기간에 두 런타임의 헤더가 다른 것은 결함이 아니라 기록된 차이다** —
`parity-verifier`는 이를 불일치로 올리지 않는다.

### 영향받는 검증

| 구분 | 내용 |
|---|---|
| 뒤집힘 | 오류 응답 헤더 **부정** 단언 → **긍정** 단언 (Kotlin 한정) |
| 유지 | 열거 10곳 개별 단언 (하한선 — 삭제 금지, 리더 판정 부수 결정 1) |
| 유지 | Python `tests/api/test_errors.py`의 부정 단언 — Python 기준선 기술로서 그대로 옳다 |
| 신규 | DELETE 204 두 곳 · `GET /health` · CORS 프리플라이트 헤더 단언 |
| 신규 | **헤더 중복 부착 부재** — 필터와 `ResponseEntity`가 둘 다 실으면 `no-store, no-store`. 값만 보면 통과하므로 **개수까지** 단언 |
| 신규·미실측 | **컨테이너 레벨 응답(400·404·413·415)에 필터가 닿는가** — Phase 3. `00_progress.md` 미결 원장 등록 |

### 검증

`uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → **OK**

---

## 2026-08-12 · v1.1.0 · 계약 성격 개정 — 동결에서 개선 대상으로

| 항목 | 값 |
|---|---|
| 판정 | **정책 결정(G3) + 오기 정정(G1) + 요구사항 반영(G2)** — 사용자 결정으로 "Python 동작 = 계약"이라는 전제가 걷혔다 |
| 근거 | 2026-08-12 사용자 결정 3건: ① Python은 회귀가 잦은 구현이지 기준이 아니며 출력을 맞출 필요가 없다, ② API 계약도 개선 대상이고 명백히 잘못된 계약은 고치고 React도 맞춰 고친다, ③ 마스킹은 주민등록번호·카드번호 2종만 유지 |
| `info.version` | 1.0.0 → **1.1.0** (비파괴 — enum 축소는 응답 필드라 소비자에게 안전하고 `checks`는 필드 추가) |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `privacy-gate`, 리더(OQ-1 재심) |
| 전문 | `docs/migration/_workspace/02_contract-rebase.md` |

### 바뀐 조항

- **`x-change-policy` 신설** — 변경 근거 G1~G4, 못 바꾸는 불변식, 리더 에스컬레이션 대상,
  절차, parity와의 관계. 계약을 바꿀 때 먼저 읽는 절이다.
- **`x-v2-candidates` → `x-improvements`** — 보류함이 아니라 판정 원장. ID(V2-N)는 기존
  리뷰·문서의 인용을 살리려 유지했다.
- **`MaskedItemResponse`** — `category` enum `[주민등록번호, 카드번호]`,
  `placeholder`·`missing_placeholders` pattern 추가, 허구 예시 `"phone"` 제거. 값이
  **한국어**임을 명시(영문 코드로 내면 화면 문구가 영어로 바뀐다).
- **`DocumentListItem.source_format`** — `[text, docx, pdf, hwpx]` enum 고정.
  초판의 "자유 문자열" 서술이 사실과 달랐다.
- **`GET /health` · `HealthResponse`** — 의존 서비스 진단으로 개정. `status`는
  `ok|degraded`, `checks`(이름→불리언) 추가, **degraded여도 200**.
- **`x-private-response-headers`** — 범위 규칙 명시 + 전역 부착 재심(OQ-1)을 미결로 기록.
  **범위 자체는 현행 10곳 그대로다.**
- **`info.description` 인증 우선순위 절** — 근거를 "Python이 그렇다"에서 "인증되지 않은
  호출자에게 검증 의미론을 노출하지 않는다"로 다시 씀.
- 머리말·`info.x-baselined-at`(옛 `x-frozen-at`)·`x-source-runtime` — 동결 전제 제거.

### 리더 판단 대기 — OQ-1

사적 헤더를 모든 응답에 붙일지(전역 필터) 재심 요청. §2.7 해결 3 판정의 근거 절반이
"parity 대조가 불일치로 잡는다"였고 그 전제가 사라졌다. **계약 소유자 단독으로 리더
판정을 뒤집지 않는다** — 전제 변화는 재심 근거이지 뒤집을 권한이 아니다.
**재심 전까지 구현은 현행 10곳 그대로다.**

### 영향받는 검증

- **contract test 추가 3건**: ① `/health`의 `status`↔`checks` 일관성 + degraded에서도 200,
  ② `category`가 2종 밖 값을 내지 않음, ③ `source_format`이 enum 밖 값을 내지 않음.
- **parity**: 불일치가 자동 차단이 아니게 됐다. 의도된 차이 목록 — 미처리 500 CORS 헤더,
  `/health` 진단, 마스킹 2종. **`02_parity-verifier_masking-spec.md`가 5종 기준이라 갱신 필요.**
- **React(고치지 않고 기록)**: `frontend/src/test/factories.ts:12,15`(허구 값 `'phone'`,
  사라진 자리표시자), `frontend/src/components/ReviewEditor.test.tsx:57,63,146,151,156`.
  런타임 코드는 깨지지 않는다 — `ReviewEditor`가 `category`를 렌더링만 하고 exhaustive
  map이 없음을 실측 확인.

### 검증

`uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → **OK**

---

## 2026-08-12 · U-1 · 미처리 500 응답의 CORS 헤더

| 항목 | 값 |
|---|---|
| 판정 | **결함도 위반도 아닌 리더 결정** — Python 동작을 재현하지 않고 개선을 받아들인다 |
| 근거 | 2026-08-12 리더 판정, 사용자 승인 |
| `info.version` | **올리지 않는다** (1.0.0 유지) |
| 통보 대상 | `kotlin-implementer`, `parity-verifier` |

### 바뀐 조항

- `x-cors.x-known-limitation` → **`x-cors.x-unhandled-500-cors`**로 대체.
  "미결 — 리더 판단 대기"에서 **"결정됨 — Python과 의도적으로 다름"**으로 확정하고
  Python 사실 / Kotlin 사실 / 결정 근거 / 범위 / Phase 6 확인 항목을 분리해 담았다.
- `components.responses.InternalError.description` — 미처리 예외에 CORS 헤더가 붙지
  않는다는 단정을 **런타임에 따라 다르다(의도된 차이)**로 고쳤다. 옛 키를 가리키던
  참조도 새 키로 옮겼다.
- `x-v2-candidates` **V2-2** — "미결"에서 **종결**로. v2로 미루지 않고 v1 Kotlin에
  반영한다. 근거는 복사하지 않고 정본 위치만 가리킨다.
- `x-changelog` — 같은 내용의 항목 추가(`revision: U-1`).

### 결정 요지

Kotlin의 `CorsFilter`는 라우팅 밖(서블릿 필터 체인 앞)에 있다. Starlette 미들웨어와
같은 위치이고 `/nope`(404)·405에도 헤더가 붙어야 하므로 그 배치가 옳다 — 그 결과
미처리 500에도 CORS 헤더가 붙는다. Python은 `ServerErrorMiddleware`가 CORS 바깥이라
구조적으로 붙일 수 없다.

되돌리려면 `CorsFilter`를 감싸는 필터가 필요하고, 그것은 **일부러 나쁜 동작을 만드는
코드**를 저장소에 영구히 남긴다는 뜻이다. 반대로 개선을 택하면 브라우저가 응답을 읽어
React가 `ApiError(500, "서버 오류가 발생했습니다")`를 받는다.

**범위**: 달라지는 것은 CORS 응답 헤더의 유무뿐. 상태 코드(500)와 본문
(`{"detail": "서버 오류가 발생했습니다"}`)이 달라지면 의도된 차이가 아니라 계약 위반이다.

### 영향받는 검증

- **parity 대조**: 미처리 500의 CORS 헤더 불일치는 **의도된 차이**로 기록한다. 차단
  사유가 아니다. 그 밖의 500 응답 항목(상태 코드·본문)이 어긋나면 종전대로 차단한다.
- **contract test**: 추가·삭제 없음. 미처리 500의 CORS 헤더를 **부정 단언**하는 테스트가
  Kotlin 쪽에 있으면 그것만 뒤집는다(현재 그런 테스트가 있는지는 확인하지 않았다 —
  `backend-kotlin/`은 이번 작업에서 열지 않았다. `kotlin-implementer`가 확인할 것).

### Phase 6 확인 항목 — React 오류 분기 (실측 완료)

명령: `grep -rn "NETWORK_ERROR_STATUS|status === 0|\.status" frontend/src` (2026-08-12)

**결론: `status = 0` 경로에 의존하는 화면 분기는 없다.**

- `NETWORK_ERROR_STATUS`는 `frontend/src/api/client.ts`(생성 지점, line 31·122)와
  `frontend/src/api/client.test.ts`(단언, line 133)에만 등장한다.
- 화면 컴포넌트 — UploadPage · HistoryPage · ReviewEditor · WorkspaceMenu ·
  WorkspaceProvider · useConversionPolling · CredentialsForm — 는 **전부**
  `caught instanceof ApiError ? caught.message : <폴백 문구>` 한 가지 모양이며
  HTTP 상태를 보지 않는다.
- 상태로 갈리는 자리는 `client.ts`의 `response.status === 401 && token !== null`
  (토큰 폐기 + `unauthorizedHandler`) 하나뿐이고 500과 무관하다.
- `client.test.ts`의 네트워크 오류 테스트는 `fetch`를 직접 reject시키는 방식이라 이
  변경과 무관하게 통과한다 — 진짜 연결 실패 경로는 계속 `status = 0`이다.

따라서 이 결정이 깨뜨리는 화면 분기는 없고, 사용자에게 보이는 **문구만**
"서버에 연결하지 못했습니다…" → "서버 오류가 발생했습니다"로 바뀐다.

Phase 6에서 다시 확인할 것:

1. 위 grep을 재실행해 그 사이 `status === 0` 분기가 새로 생기지 않았는지.
2. Kotlin 미처리 500의 `detail` 문구가 `"서버 오류가 발생했습니다"`인지 —
   계획 §4.1의 오류 문구 어댑터가 이 값을 전제한다.

---

## 2026-08-12 · §2.7-3 Phase 3 종료 조건 재작성 (스킬 문서)

계약 파일(`contracts/easy-doc-v1.yaml`)은 **바뀌지 않았다.** 바뀐 것은
`.claude/skills/api-contract-freeze/SKILL.md` §5의 Phase 3 종료 조건 문구다.
계약 조항(오류 응답에 사적 헤더가 없다)은 그대로이고, 그것을 **어떻게 강제하는지**만
실행 가능한 형태로 고쳤다. 기록을 여기 남기는 이유는 `kotlin-implementer`의 Phase 3
종료 판정 기준이 달라졌기 때문이다.

- **문제**: 옛 종료 조건은 "Kotlin 오류 응답에 헤더가 없음을 계약 테스트로 단언하라"만
  요구했다. 그런데 서블릿 API에는 헤더 삭제가 없고(`removeHeader` 부재)
  `response.reset()`은 필터가 써 둔 CORS 헤더까지 지운다 — `HttpServletResponse`로
  헤더를 붙인 구현에서는 **만족시킬 방법이 없는 조건**이었다(구현자 확인).
- **수정**: 결과만 요구하던 조건을 **규칙 1 + 단언 A·B**로 다시 썼다. 사적 헤더는
  `ResponseEntity`의 헤더로만 싣고(`HttpServletResponse`·`Interceptor`·`Filter`·
  `WebMvcConfigurer` 금지), 성공 응답 10곳의 긍정 단언과 같은 컨트롤러 실패 경로의
  부정 단언으로 그 규칙을 감시한다.
- **검증 상태**: 규칙의 근거(Spring MVC는 `ResponseEntity` 헤더를 컨트롤러 정상 반환
  이후에 응답에 기록한다)는 Servlet·Spring API 문서 수준에서만 확인했고 이 저장소에서
  실행하지 않았다. **Phase 3에서 단언 A·B를 먼저 돌려 실측하고, 어긋나면 §5를 고친 뒤
  Phase 3을 닫는다.**
- 통보 대상: `kotlin-implementer`(구현 규칙), `parity-verifier`(단언 범위).

---

## 2026-08-13 · OQ-2 · OQ-3 · OQ-4 · OQ-5 — 미결 계약 질문 4건 정식 등록 (계약 파일 무변경)

| 항목 | 값 |
|---|---|
| 계기 | 리뷰 게이트 `07_core-rebuild` 교차 종합 §6 순위 9·12·15가 `contract-keeper`로 회부(X-10·X-12·X-13) + `parity-verifier` §G 추출이 회부(`failure_code`) |
| 출처 | `docs/migration/_workspace/reviews/07_core-rebuild_cross.md`(OQ-2·3·4) / `docs/migration/_workspace/02_parity-verifier_conversion-spec.md` §6 갈림 후보 ② + `00_requirements-inventory.md` §9-E(OQ-5) |
| 성격 | **등록이지 개정이 아니다.** `contracts/easy-doc-v1.yaml`은 이번에 **한 글자도 바꾸지 않았다** — `info.version` 1.1.0 유지 |
| 해결 시점 | OQ-2 Phase 4 · OQ-3 Phase 3 · OQ-4 Phase 4 · OQ-5 Phase 5(늦어도 `app/**` 삭제 전) |
| 통보 대상 | `kotlin-implementer`(OQ-3 공동·OQ-5 구현 전제), `parity-verifier`(OQ-4·OQ-5), `privacy-gate`(OQ-2 인접), 리더(OQ-2·OQ-5 판정 가능성) |

### 왜 "등록"이 그 자체로 작업인가

OQ-2는 커밋 `8412b89` 메시지가 *"contract-keeper 에 넘겼다"*고 적은 항목이다. **확인 결과
아무도 받지 않았다** — 등록 직전 실측으로 이 changelog와 `00_contract-keeper_test-plan.md`,
그리고 `contracts/easy-doc-v1.yaml` 세 파일 모두에서 `ambiguous`·`withheld`·`foreign`·
`masked_text`·`[[!` 가 **각각 0회**였다(교차 종합 C-1의 주장과 일치, 독립 재확인).

**인계가 커밋 메시지 안에서만 살아 있는 상태**는 그 자체로 결함이다. 커밋 메시지는 검색 대상이
아니고 원장이 아니며, 다음 Phase가 읽는 문서가 아니다. 이 절의 목적은 답을 정하는 것이 아니라
**질문이 사라지지 않는 자리에 놓는 것**이다. 답은 각 항목의 해결 Phase에서 정한다.

---

### OQ-2 (= 교차 종합 X-10 = `migration-reviewer` C-1) · 해결 Phase 4

**복원 상태 `ambiguous`·`withheld`가 내보내기 409 조건 어디에도 대응되지 않는다.**

#### 확인한 사실 (실측)

`backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt`의
`restoreForExport(draft, reviewed, items)`는 결과를 `PlaceholderRestoration` 5필드로 낸다
(`:119-125` 정의, `:527-548` 산출). 그중 셋이 "복원하지 않았다"를 뜻한다.

| 상태 | 뜻 | 그때 본문에 남는 것 | 현행 계약 대응 |
|---|---|---|---|
| `missing` | 우리가 만든 자리표시자가 본문에서 **사라졌다**(0회) | 그 자리의 정보가 통째로 빠짐 | **있다** — `ConversionResponse.missing_placeholders` + 409 `missing_placeholders` |
| `ambiguous` | 같은 자리표시자가 **2회 이상** 나타났다 → 한 곳도 복원하지 않는다 | `[[주민등록번호1]]`이 **글자 그대로** 남음 | **없다** |
| `withheld` | **검수본이 없어서**(`reviewed == null`) 복원을 보류했다 | 같음 | **없다** |
| `foreign` | 자리표시자 모양이지만 우리가 만들지 않은 토큰 | 그대로 둠(설계된 무동작) | 없음 — 문제 아님 |

계약의 409는 정확히 둘이고 **둘 다 `missing`에 걸린다**(`contracts/easy-doc-v1.yaml:1043-1049`
설명, `:1097-1105` 응답 예시). `ambiguous`인 본문은 자리표시자가 **사라진 게 아니라 늘어난**
것이므로 `missing`이 비어 있고, `withheld`인 본문도 개수 판정을 통과한 것들이라 `missing`이
비어 있다. **둘 다 409에 걸리지 않는다 → 자리표시자가 박힌 문서가 200으로 나간다.**

#### 이것이 이론적 위험이 아닌 이유

유입 경로가 실재한다. 입력에 `[[주민등록번호1]]`이 이미 있으면 마스킹이 이를
`[[!주민등록번호1]]`로 탈출시켜 내보내고(`Masking.kt:222-223`의 `escapeLookalikes`,
`:343`에서 마스킹 입력에 적용), 그 문자열이 **검수 화면에 그대로 보인다.** 검수자가 그 `!`를
오타로 보고 지우면 그 순간 같은 토큰이 둘이 되어 `ambiguous`가 된다. `withheld` 쪽은 더 흔하다 —
**검수를 한 번도 하지 않고 바로 내보내면 항상 발생한다**(Python은 이 경우 초안을 복원했고,
Kotlin은 `8412b89`로 보류하도록 바뀌었다).

#### 미결 질문 (여기서 답하지 않는다)

1. `withheld`가 비어 있지 않을 때 내보내기를 **409로 막는가, 알리며 200으로 내보내는가.**
2. `ambiguous`가 비어 있지 않을 때는 어떻게 하는가. `withheld`와 **같은 처분인가 다른가** —
   `withheld`는 "사람이 아직 안 봤다"(절차 미완)이고 `ambiguous`는 "본문이 우리가 만든
   모양이 아니다"(내용 이상)라 성격이 다르다.
3. 막는다면 `detail` 문구는 무엇인가. 현행 `missing_placeholders` 문구
   (*"변환에서 유실된 개인정보 표시가 있습니다 — 검수 화면에서 수정 후 내보내세요"*)는
   `withheld`에 대해 **사실과 다르다**(유실된 것이 없다).
4. 조회 응답(`ConversionResponse`)에 `missing_placeholders`와 나란히 `ambiguous`를 노출할
   것인가. 노출하면 React `ReviewEditor:143-146`이 쓰는 것과 같은 종류의 경고를 띄울 수 있다.
5. `foreign`은 계약 밖으로 둔다 — 무동작이 설계이고 노출 요구가 없다. **묻지 않기로 한
   것도 판정이므로 적어 둔다.**

#### 답을 미리 좁히지 않기 위한 메모

- `core`는 **판정 수단만** 제공하고 막을지 여부는 호출부(application)에 넘긴다고 스스로
  적어 두었다(`Masking.kt:440-444` 주석). 즉 계약이 어느 쪽을 정하든 core는 그대로다.
  **이 질문은 순수하게 계약 질문이다.**
- 라벨은 개인정보가 아니다(계약이 `missing_placeholders`에 대해 이미 그렇게 판정했다).
  따라서 `ambiguous`·`withheld`를 응답에 싣는 것 자체는 INV-01과 충돌하지 않는다.
- **`privacy-gate` 인접**: 커밋 `8412b89`가 *"복원은 사람 제출 본문에만"*을 감사 항목으로
  추가할지도 함께 물었다. 그것은 I-항목 질문이지 계약 질문이 아니므로 여기서 다루지 않는다.

#### 단독으로 정하지 않는 것

409를 **추가**하는 것은 React 런타임 코드가 의존하는 동작을 바꾼다 —
`frontend/src/components/ReviewEditor.tsx:119`가 내보내기 409를 잡아 백엔드 `detail` 문구를
그대로 보여 준다. 문구만 바뀌면 화면은 깨지지 않지만, **지금까지 내려받히던 문서가 갑자기
막히는 것**은 사용자에게 보이는 동작 변경이다. 처분이 "막는다"로 기울면 계약·백엔드·프런트를
같은 변경 단위로 묶어야 하므로 **리더에게 올린다.**

---

### OQ-3 (= X-12 = C-2) · 해결 Phase 3 · `kotlin-implementer` 공동

**계약이 못박은 값을 실행 검사가 계약 파일에서 읽지 않는다 — 계약 값이 테스트에 수기 복제된다.**

계약은 자리표시자를 앵커까지 못박았다(`contracts/easy-doc-v1.yaml:1680`
`MaskedItemResponse.placeholder`, `:1740` `missing_placeholders.items`:
`^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`). 대응하는 Kotlin 단언은
`backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/MaskingTest.kt:187-188`인데
**문자열 두 개를 하드코딩**한다. 바로 위 `:185-186` 주석은 *"계약이 이 한국어 문자열을 enum으로
못박았다"*고 말하지만 계약 파일을 읽지 않는다.

전수 확인 결과 `backend-kotlin/**`에서 `easy-doc-v1`을 언급하는 자리는 **전부 주석**이다
(`MaskingTest.kt:185`, `Masking.kt:12`·`:173`, `CorsContractTest.kt:18`,
`PrivateResponseHeadersContractTest.kt:19`, `HealthContractTest.kt:18`, `CorsConfig.kt:15`,
`HealthController.kt:11`, `GlobalExceptionHandler.kt:67`·`:418`). **파일을 여는 코드는 0건이다.**

계약을 실제로 파싱하는 코드는 저장소에 있다 —
`.claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py`의 `CONTRACT_PATH`와
`compare_parity.py::check_placeholder_scheme`. 그러나 그 경로는 **도달 0**이다(교차 종합 X-1).
즉 계약↔구현 대조는 현재 **주석으로만 존재한다.**

미결 질문: 계약 파일을 테스트가 **직접 파싱**하게 할 것인가(어느 계층에서·어느 값까지),
아니면 계약에서 **생성한 산출물**을 테스트가 읽게 할 것인가(Phase 6 OpenAPI 생성 타입 교체와
같은 기제). 전자는 즉시 가능하지만 YAML 파서 의존이 붙고, 후자는 프런트와 기제를 공유하지만
Phase 6까지 공백이 남는다.

**계약 조항 자체는 바뀌지 않는다** — 이 항목은 계약의 *내용*이 아니라 *도달*의 문제다.
그래서 `x-change-policy`의 G1~G4 근거가 필요 없다.

---

### OQ-4 (= X-13 = C-3) · 해결 Phase 4

**마스킹된 본문 채널이 계약에 필드로 존재하지 않는다.**

`grep masked_text contracts/easy-doc-v1.yaml` → **0회**(재확인). `[[!` → **0회**.

#### 회부 문구를 그대로 받지 않고 갈라 둔다

리더 회부 문구는 *"`masked_items[].original` 등 `masked_text` 채널"*이라고 묶었으나,
**둘의 상태가 다르다.** 뭉뚱그리면 이미 있는 것을 다시 만들거나 없는 것을 있다고 착각한다.

- **`masked_items[].original` — 이미 계약에 있다.** `contracts/easy-doc-v1.yaml:1657`
  (required), `:1686`(정의), `:1660-1667`(원문이 실리는 근거와 캐시 금지 헤더 연결),
  `:945`·`:601`(고위험 응답 지정). **공백이 아니다.**
- **마스킹된 본문 자체 — 계약에 없다.** LLM에 보내지고 검수 화면 좌측에 보이는 그 문자열이다.
  자리표시자(`[[주민등록번호1]]`)와 **탈출 표기**(`[[!주민등록번호1]]`)가 담기는 채널인데
  계약에 이름이 없다.

#### 지금 계약 위반은 아니다

`easy_text`·`edited_text`에는 pattern 제약이 없으므로(`:1717-1728`) 그 안에 탈출 표기가 실려도
계약을 어기지 않는다. **이 항목은 위반이 아니라 공백이다** — 사용자가 보게 되는 문자열이
계약 밖에 있다는 것.

#### 미결 질문

1. 마스킹 본문을 응답 필드로 **계약화할 것인가**(새 필드) — 아니면 "이 채널은 계약이 기술하지
   않는다"를 명시적으로 적을 것인가. 후자도 판정이다.
2. 탈출 표기 `[[!<범주><n>]]`를 계약 어휘로 **승격할 것인가.** 승격하면 프런트가 이를 알아보고
   검수 화면에서 설명할 수 있고(OQ-2의 유입 경로가 줄어든다), 승격하지 않으면 사용자는 정체
   불명의 `!`를 보고 지운다 — **OQ-2와 이 지점에서 만난다. 두 항목은 함께 판정해야 한다.**
3. 계약화한다면 그 필드가 `original`처럼 고위험(캐시 금지 필수) 범주인가. 마스킹 본문에는
   정의상 주민등록번호·카드번호가 없지만 **전화번호·이메일·계좌번호는 그대로 있다**
   (2026-08-12 범주 축소의 감수 대가). 이 사실이 판정에 들어가야 한다.

---

### OQ-5 (`parity-verifier` §6 갈림 후보 ② = 인벤토리 §9-E) · 해결 Phase 5

**계약이 `failure_code`를 enum이 아니라 "구현을 되짚는 규칙"으로 정의한다.**

현행 조항(`contracts/easy-doc-v1.yaml:1750-1755`):

> 실패 사유 코드 = 예외 클래스명. 큐 등록 실패는 예외 클래스명이 아닌 `"EnqueueFailed"`를 쓴다.

#### 왜 이것이 결함 후보인가

**값의 정본이 계약이 아니라 Python 클래스 이름이다.** 새로 쓰는 Kotlin이 이 계약을 지키려면
예외 클래스를 `LLMTruncatedError`·`LLMEmptyResultError`·`LLMProviderError`로 **베껴 이름
지어야** 하고, 그것은 CLAUDE.md 「하지 말 것」의 *"명세가 있는 것을 확인하겠다고 Python 코드
읽기"*·*"Python 출력을 정답으로 삼기"* 둘 다에 걸린다. 게다가 **Python을 지우면 이 규칙은
가리킬 대상이 없어진다** — 규칙이 자기 정의를 잃는다. 근거 후보는 **G4**(지킬 수 없거나
지켜지지 않는 형태)이며, `x-change-policy`가 요구하는 지목 가능한 근거를 갖췄다.

#### 지금 fixture가 하는 것

`repair-adoption` fixture는 계약 문자열이 아니라 요구 수준 이름
`failure_kind`(`truncated`·`empty_result`·`provider_error`)로 **셋을 구분하는가**만 판정한다.
문자열이 무엇이어야 하는가는 판정하지 않는다. 그래서 CNV-02는 현재 "구분한다"까지만 판정되고
"무엇으로 부르는가"는 **아무도 판정하지 않는다.**

#### 후보안 (병기만 한다 — 확정하지 않는다)

**후보 A — 요구 수준 이름 enum.** `failure_code`를 닫힌 집합으로 열거하고 값을 구현에서
분리한다. `parity-verifier`가 제안한 셋이 출발점이다.

**⚠ 셋으로는 부족하다 — 실제 값은 다섯이다.** 계약과 React가 이미 다루는 코드는
`LLMTruncatedError`·`LLMEmptyResultError`·`LLMProviderError` **셋에 더해**
`ProviderUnavailable`(설정 미배선)·`EnqueueFailed`(큐 등록 실패) **둘**이 있다
(`frontend/src/conversion/failureMessages.ts:23-47`, 계약 `:777`·`:1754`). 뒤의 둘은 변환
파이프라인 실패가 아니라 **배선·인프라 실패**라 요구 수준 이름이 따로 필요하다. 셋만 열거하는
enum은 두 값을 갈 곳 없게 만든다.

**후보 B — 규칙을 유지하되 정본을 계약으로 옮긴다.** "예외 클래스명"이라는 되짚기를 지우고
계약이 문자열을 열거하되 **현행 값 다섯을 그대로 채택**한다. Kotlin은 계약의 문자열을 내면
되고 예외 클래스 이름은 자유다. React 무영향.

**후보 C — 현행 유지.** 근거를 대지 못하면 바꾸지 않는 것이 기본값이다. 다만 이 경우
`app/**` 삭제 시점에 조항이 가리킬 대상을 잃는다는 사실을 명시해야 한다.

#### React 영향 — **런타임 코드가 이 값에 의존한다** (실측)

`frontend/src/conversion/failureMessages.ts`가 코드 문자열 다섯을 **키로 하는 맵**을 갖고,
`frontend/src/pages/ConversionPage.tsx:36`이 `failureMessage(conversion.failure_code)`로
화면 문구·조언·재시도 버튼 노출을 결정한다. 테스트 픽스처만이 아니라 **런타임 경로다.**

**화면은 깨지지 않지만 조용히 나빠진다.** 모르는 코드는 `UNKNOWN`으로 떨어지므로
(`failureMessages.ts:66-71`) 값이 바뀌면 예외가 아니라 **모든 실패가 일반 문구 하나로 뭉개지고**,
`LLMTruncatedError`의 `retryable: false`가 `UNKNOWN`의 `retryable: true`로 뒤집혀
**"문서를 나눠 올리세요"가 "다시 시도하세요"로 바뀐다** — 사용자가 같은 실패를 반복하게 된다.
크래시가 없어서 **테스트로도 잡히지 않는 종류의 회귀**다(`ConversionPage.test.tsx:62`는
`'LLMTruncatedError'` 한 값만 단언한다).

→ 후보 A·B 어느 쪽으로 가든 `failureMessages.ts`를 **같은 변경 단위**에서 함께 고쳐야 한다.
`x-change-policy.escalate_to_leader` 2번(React 런타임 의존)에 해당하므로 **리더 판정 대상**이다.

#### 함께 정리해야 하는 인접 조항

`components/responses/BadGateway`(`:1427-1435`)도 `LLMProviderError`·`QueueUnavailableError`를
이름으로 부른다. 다만 **성격이 다르다** — 그쪽은 "어떤 예외가 502로 매핑되는가"라는 구현 대응표
설명이고, `failure_code`는 **와이어에 실려 나가는 값**이다. 값이 아닌 설명은 같은 문제가
아니므로 **OQ-5의 범위는 `failure_code` 하나로 한정한다.** (인접 조항을 함께 손대고 싶은
충동과 고쳐야 할 결함을 가르는 자리다.)

---

### 계약 파일 변경 없음 — 확인

이번 등록으로 `contracts/easy-doc-v1.yaml`은 바뀌지 않았다. `x-changelog`·`x-improvements`에
항목을 넣는 것은 **조항 확정과 같은 커밋**에서 한다 — 미결 상태를 정본 스펙에 적으면 구현자가
"계약이 이렇게 정했다"로 읽을 여지가 생긴다. 미결의 정본은 이 changelog와
`00_contract-keeper_test-plan.md` §3-1이다.

### 영향받는 검증

`00_contract-keeper_test-plan.md`에 **X-J1~X-J4**를 추가했다. 넷 모두 **조항 미확정**으로
표시했다 — 존재하지 않는 조항을 고정하는 테스트는 쓸 수 없다. 각 항목이 확정되는 Phase에
테스트가 함께 자란다(§5 실행 시점 표에 반영).

### 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **OQ-3 공동 담당** — 계약 파일을 테스트가 직접 읽는 기제. **OQ-5는 구현 전제** — Phase 5 변환 파이프라인을 쓰기 전에 `failure_code` 값 집합이 정해져야 하고, **그전까지 Python 예외 클래스 이름을 베껴 짓지 마라.** OQ-2는 core 무변경(판정 수단은 이미 있다) |
| `parity-verifier` | **OQ-5**: `failure_kind` 3종은 계약 확정 전까지 유효한 임시 이름이다. 확정되면 fixture를 그 값으로 재생성한다 — 다만 **실제 값은 다섯**이므로 `ProviderUnavailable`·`EnqueueFailed`에 해당하는 두 자리를 어떻게 다룰지 함께 판정한다. **OQ-4**: 마스킹 본문 채널이 계약 밖이라 그 채널의 Python↔Kotlin 차이는 현재 계약 위반으로 판정할 근거가 없다 |
| `privacy-gate` | **OQ-2 인접** — 커밋 `8412b89`가 물은 *"복원은 사람 제출 본문에만"*의 I-항목 등재 여부는 계약 질문이 아니라 감사 항목 질문이다. 이 changelog는 그것을 판정하지 않았다 |
| 리더 | **OQ-2**(409 추가는 사용자에게 보이는 동작 변경)와 **OQ-5**(React 런타임 의존)가 `x-change-policy.escalate_to_leader`에 해당한다. OQ-3·OQ-4는 계약 소유자 단독 처리 가능 |

---

## 2026-08-15 · F3 · 요청 길이 제약 5개를 스키마 층에서 서비스 층으로 정정 (Phase 3 착수 판정)

| 항목 | 값 |
|---|---|
| 계기 | 미결 원장 **F3 = 교차 리뷰 C-5**(`reviews/01_skeleton_cross.md:99`·`:350`) — 마감 "Phase 3 착수 전". Phase 3(api 엔드포인트)이 다음 조각이라 리더가 판정을 요청 |
| 판정 | **다섯 전부 서비스 층 규칙으로 유지하고 OpenAPI 길이 키워드를 제거한다** (계약 소유자 단독 판정) |
| 근거 | **G1**(계약이 사실과 다름) + **G4**(계약의 두 조항이 서로 모순이라 동시 만족 불가) |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 올바르게 구현한 런타임이 내보내는 바이트가 달라지지 않는다 |
| React 영향 | **없음** — 문자열 `detail`은 `client.ts:79-81`이 이미 처리하는 경로이고 이번 판정은 그것을 **유지**하는 쪽이다 |
| 통보 대상 | `kotlin-implementer`(Phase 3에서 즉시 걸린다), `parity-verifier`, 리더 |

### 무엇이 충돌이었나

계약은 오류 본문 규칙에서 **스키마 실패 → 422 배열**, **도메인 예외 → 422 문자열**로
갈라 놓았다. 그런데 다섯 요청 필드에 OpenAPI `maxLength`/`minLength`를 달아 두었으니
계약 자신의 규칙대로면 이 다섯의 위반은 **배열**이어야 한다. 실제 판정은 서비스 층이고
결과는 **문자열**이다. **계약이 자기 자신과 충돌했다.**

### 실측 (2026-08-13, 읽기 전용)

**① Pydantic 모델에 길이 제약이 하나도 없다.** `model_json_schema()`로 확인:

```
SignupRequest              길이 제약: 없음
DocumentTextRequest        길이 제약: 없음
ConversionReviewRequest    길이 제약: 없음
WorkspaceNameRequest       길이 제약: 없음
```

다섯 전부 `InvalidInputError` → 422 문자열이다. 상한 **값**은 계약과 코드가 일치했다
(255 / 8 / 4000 / 4000 / 50 전건 동일) — 틀린 것은 값이 아니라 **강제하는 층**이었다.

**② 셋은 계약이 코드보다 엄격했다.** 서비스가 **정규화 후** 길이를 재기 때문이다.
갈림 입력을 실제로 만들어 확인:

| 필드 | 원시 | 정규화 후 | 코드 | 계약(옛 스키마) |
|---|---|---|---|---|
| `email` | 260자(앞 공백 10) | 250자 | **통과** | 거절 |
| `edited_text` | 4,010자(제어문자 11) | 3,999자 | **통과** | 거절 |
| `name` | 55자(제어문자 10) | 45자 | **통과** | 거절 |

**③ 반대 방향도 있었다.** `name`의 `minLength: 1`은 `"   "`(공백 3자)를 통과시키지만
서비스는 `"작업 공간 이름을 입력해 주세요"`로 거절한다. **한 필드가 양방향으로 갈렸다.**

**④ 나머지 둘은 경계가 같다.** `password`(minLength 8)와 `text`(maxLength 4000)는 코드도
원시 값으로 재므로 **어느 입력이 통과하는지는 동일**하다. 이 둘에서 갈리는 것은 `detail`
모양 하나뿐이다 — 그래서 "다섯 전부 틀렸다"가 아니라 "다섯 중 셋은 경계까지, 둘은 모양만"이
정확한 기술이다.

### 왜 반대 방향(코드를 스키마 층으로 옮기기)이 아닌가

이쪽이 진짜 판정이다. 계약을 코드에 맞추는 것과 코드를 계약에 맞추는 것 중 후자를 택할
근거가 있었는지 먼저 봤고, **셋 다 후자를 기각했다.**

1. **요구 자체가 정규화 후 판정을 지정했다.** *"제어문자를 걷어내고 앞뒤 공백을 턴 뒤
   검사한다"*는 이 계약 자신의 문장(`POST /workspaces` 설명)이고, 먼저 걷어내는 **이유**도
   코드 주석에 적혀 있다 — 남겨 두면 docx(XML)가 담지 못해 **내보내기 시점에** 터지고
   사용자는 원인을 알 수 없는 500을 받는다. 스키마 층은 정규화 **전에** 돌기 때문에
   이 요구를 표현할 방법이 없다. G4가 말하는 "지킬 수 없는 형태"다.
2. **사용자에게 보이는 문구가 한국어에서 영어로 바뀐다.** React `readErrorMessage`
   (`frontend/src/api/client.ts:83-90`)는 배열 `detail`에서 `msg`만 뽑아 **그대로 화면에
   올린다.** 스키마 층으로 옮기면 `"작업 공간 이름은 50자 이하여야 합니다"` 대신
   Bean Validation 영문 문구가 사용자에게 나간다. **상태 코드가 그대로라 기존 테스트로는
   잡히지 않는다** — OQ-5의 `failureMessages` 회귀와 정확히 같은 형태의 조용한 품질 저하다.
3. **같은 "길이 위반"의 응답 모양이 필드마다 갈린다.** 경계가 같은 둘만 옮기면 길이 위반이
   어떤 필드에서는 배열, 어떤 필드에서는 문자열로 나가 클라이언트 분기가 예측 불가능해진다.

### 일괄 삭제가 아니다 — 대조 사례

**`limit`·`offset`의 `minimum`/`maximum`은 그대로 뒀다.** 구현이
`Annotated[int, Query(ge=1, le=MAX_PAGE_SIZE)]`로 **실제로 스키마 층에서** 판정하고
422 배열이 나간다(`ValidationFailed.examples.query_range`가 그 예시다).

즉 계약이 통째로 틀렸던 것이 아니라 **코드가 스키마 층에서 재는 자리에서는 맞았고 아닌
자리에서 틀렸다.** 자리마다 실제 강제 지점을 확인한 결과이지 규칙을 일괄 적용한 것이
아니다. 응답 스키마의 `maxLength` 3건(`failure_code` 64 · `DocumentListItem.title` 255 ·
`WorkspaceResponse.name` 50)도 422를 만들지 않으므로 **손대지 않았다.**

### 바뀐 조항 (`contracts/easy-doc-v1.yaml`)

- **`x-request-field-constraints` 신설** — 판정문·근거·대조 사례·필드별 측정 기준의 정본.
  각 필드에 `layer`·`limit`·`measured_on`·`detail`(문구 전문)을 적었다.
- **길이 키워드 5개 제거 → `x-service-constraint`로 대체.** 값을 기계가독으로 잃지 않으면서
  스키마 의미론 주장을 걷어낸다. **생성기가 조용히 틀린 검증기를 만드는 것**이 상한을
  확장 필드로 옮기는 것보다 나쁘다는 판단(`x-error-body-universality`와 같은 기제).
- **`ValidationFailed`에 배열/문자열 경계 규칙 명시** — "어느 모양이 나오는지는 구현 재량이
  아니라 계약이다". `@Size`·`@NotBlank`로 구현하면 계약 위반임을 명시.
- **`x-input-limits`** — `max_email_length`·`min_password_length` 추가,
  `list_limit`·`list_offset`에 "스키마 층" 표시(대조 사례를 값 옆에 남긴다).
- `x-changelog`에 F3 항목.

검증: `uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → **OK**

### 미결로 남긴 것 — `text` vs `edited_text` 비대칭

`edited_text`는 제어문자를 걷어낸 뒤 길이를 재는데 `text`(붙여넣기)는 그러지 않는다
(`create_from_text`가 `strip_control_chars`를 부르지 않음 — 실측). 그런데 먼저 걷어내는
**이유**로 계약이 든 것(docx 내보내기 500)은 두 경로에 똑같이 적용된다.

**해소하지 않았다.** 정규화 범위를 넓히는 것은 계약 문구가 아니라 변환 파이프라인의 판단이고
텍스트 정규화 명세는 parity 레인이 소유한다. 계약은 현재 요구되는 측정 기준을 **있는 그대로**
적고 `x-request-field-constraints.x-open-asymmetry`에 Phase 4 미결로 표시했다.
**바꾸지 않기로 하는 것도 판정이므로 근거를 남긴다** — 여기서 `text`도 정규화 후로 적으면
계약이 구현에 없는 동작을 요구하게 되고, 그것이 F3이 고치고 있는 바로 그 결함이다.

### 영향받는 검증 (`00_contract-keeper_test-plan.md`)

- **X-F1·X-F2 개정** — `detail`이 **문자열**임을 함께 단언한다. 상태 코드만 보면 배열로
  구현해도 통과한다.
- **X-F9 신설** — 정규화 후 경계: 원시 길이는 상한을 넘지만 정규화 후에는 이하인 입력이
  **통과한다**(위 실측 3건이 그대로 케이스다). 이 단언이 없으면 `@Size` 구현이 통과한다.
- **X-F10 신설** — `name`이 `"   "`를 **문자열** detail로 거절한다(`minLength: 1` 방향의 갈림).

### 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **Phase 3에서 즉시 걸린다.** 다섯 필드에 `@Size`·`@NotBlank`·`@Email`을 **쓰지 마라** — 서비스 층에서 정규화한 뒤 재고 도메인 예외로 던진다. 정본은 `x-request-field-constraints`이고 `detail` 문구 전문이 거기 있다. 반대로 `limit`·`offset`은 **스키마 층이 맞다**(Bean Validation 사용) |
| `parity-verifier` | 다섯 필드의 `detail` **문자열 전문**이 계약이다(§6의 "오류 detail 전문 일치" 범위에 포함). `detail`의 **타입**(문자열/배열)도 값 동일성 대상이다 |
| 리더 | **F3 판정 완료 — 미결 원장에서 닫아도 된다.** 리더 판정이 필요한 항목에 해당하지 않아 단독 처리했다: 리더가 판정한 조항을 뒤집지 않고, React 런타임 동작을 바꾸지 않으며(오히려 유지), 보안 불변식을 좁히지 않고, 배포·운영 동작이 그대로다 |

---

## 2026-08-19 · M-405 · 파싱 단계 거절 응답의 분류 정정 (7종 → 6종)

| 항목 | 값 |
|------|-----|
| 계기 | `kotlin-implementer`가 상시 회귀 열거자(`ContainerRejectedRequest`)를 계약 목록과 맞추려다 **빠진 둘을 원시 소켓으로 먼저 쟀고**, 그중 하나가 계약 분류와 갈렸다 (`03_kotlin-implementer_auth-fixes2.md` §4) |
| 근거 | **G1** (계약이 사실과 다름) |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 올바르게 구현한 런타임이 내보내는 바이트가 달라지지 않는다 |
| React 영향 | **없음.** `client.ts`는 405를 분기하지 않는다(401 하나만 분기하고 나머지는 `ApiError`로 감싼다). 이 재분류는 응답 자체를 바꾸지 않는다 |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `migration-reviewer`, 리더 |

### 무엇이 틀렸나

계약 `x-phase3-measurement`는 「요청 줄·헤더 블록 **파싱 단계**에서 거절되어 서블릿에
매핑되지 않는 응답」을 **7종**으로 열거하고, `resolution`이 「밸브가 7종 **전부**를 덮는다」고
적었다. 그 7종 중 **「알 수 없는 메서드 → 405」는 파싱 단계 거절이 아니다.**

**판별 근거 셋 — 전부 실측이고 모두 같은 방향이다.**

| # | 관측 | 무엇을 말하는가 |
|---|---|---|
| ① | `Allow: GET`이 붙는다 | 매핑을 **알아야만** 만들 수 있는 헤더다. 파싱 단계에서 거절된 요청에는 매핑 정보가 없다 |
| ② | `Content-Type`에 `;charset=UTF-8`이 **없다** | 밸브가 만든 응답(콜론 없는 헤더 줄 400)에는 붙는다. **두 응답의 생성자가 다르다** |
| ③ | 본문이 우리 고정 문구다 | 컨테이너 기본 본문이 아니다 — 프레임워크 오류 핸들러를 지났다 |

측정 방법은 실기동 `@SpringBootTest(RANDOM_PORT)` + 원시 소켓(`FROB /health`)이다.
**MockMvc로는 이 축을 잴 수 없다**는 기존 판정이 그대로 적용된다.

### 왜 G1이고 G4가 아닌가

조항이 지킬 수 없는 형태였던 것이 아니라 **한 항목의 소속이 틀렸다.** 그리고 **요구는 하나도
바뀌지 않았다** — 그 405 응답에도 두 헤더가 붙어야 하고 본문은 `ErrorResponse`여야 한다.
바뀐 것은 「무엇이 그것을 붙이는가」의 서술뿐이다. 불변식 축소가 아니므로 리더 재심 사유
(`x-change-policy.escalate_to_leader` ①~④) 어디에도 해당하지 않아 단독 처리했다.

### 별도 응답 조항을 만들지 않았다

이 405는 컨테이너가 아니라 **프레임워크 오류 핸들러**가 만든 응답이므로
`x-error-body-universality`의 ④가 아니라 **①**에 속하고, 그 절이 이미 요구를 건다.
`paths` 아래 선언할 자리는 여전히 없다 — 경로는 있으나 그 메서드의 오퍼레이션이 없어
(경로, 오퍼레이션, 상태 코드) 삼중키가 성립하지 않는다(`x-openapi-expressibility` ②와 같은
자리다). **조항을 새로 만들면 이미 걸려 있는 요구를 두 벌로 만들 뿐이다.**

### 바뀐 조항 (`contracts/easy-doc-v1.yaml`)

- `x-phase3-measurement.unreachable_by_filter` — `note`의 수를 **6종**으로, `cases`에서 405 제거
- 같은 절 `reachable_by_filter` — 405 항목 추가
- **`x-phase3-measurement.x-405-reclassification` 신설** — 판별 근거 셋과 「별도 조항을 만들지
  않는 이유」
- `resolution`·`residual` — 7 → 6
- 같은 수를 인용하던 네 자리: `x-global-response-headers`의 `x-failure-mode-shift` ·
  `enforcement` · `x-container-coupling` · `x-openapi-expressibility` ⑥
- `x-error-body-universality.x-unmeasured` — 대상에서 405 제외 + **「콜론 없는 헤더 줄 400」의
  본문 1종 측정 등재**(최상위 키 `detail` 하나로 관측). **E-4는 닫지 않는다** — 나머지 5종은
  여전히 보지 않았다
- `x-changelog`에 M-405 항목

**옛 `x-changelog` 항목은 고치지 않았다.** 그 항목들은 당시에 무엇을 적었는지의 기록이고,
사후 편집하면 판정의 근거가 사라진다(구현 레인이 `03_kotlin-implementer_auth-fixes2.md` §5에서
밟은 것과 같은 처리다).

검증: `uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml`

### 영향받는 검증

| 대상 | 조치 |
|---|---|
| `ContainerRejectedRequest` 열거자 | 정정 전에는 **열거자 6 vs 계약 7**이라 대조 장치를 넣으면 빨간 채 커밋해야 했고, 면제 조항으로 통과시키는 것은 이 하네스가 금지한 은폐형이다. **이제 6 = 6이므로 열거자↔계약 대조를 빌드에 넣을 수 있다** — 구현 레인 몫이며, 이 정정이 그 전제였다 |
| 405 도달 응답 | `PrivateResponseHeadersReachTest`의 **도달 케이스**로 붙든다(구현 레인이 이미 신설 계획으로 들고 있다) |
| `00_contract-keeper_test-plan.md` §3 **X-D2c** 행 | 7종 열거가 사실과 갈렸다. **별도 문서 커밋에서 정정한다**(계약 개정 커밋은 계약 파일과 이 changelog만 담는다) |

### 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **정정 완료 — 열거자↔계약 대조 장치를 넣을 수 있다.** 계약 목록은 이제 6종이고 열거자와 집합으로 같다. 대조는 **개수가 아니라 집합**으로 걸어라 — 개수만 맞추면 항목이 맞바뀌어도 통과한다. 405 도달 응답은 도달 케이스로 별도로 붙든다 |
| `parity-verifier` | 이 정정은 **나간 바이트를 바꾸지 않는다.** 405 응답의 상태·헤더·본문 요구는 그대로이므로 대조 범위 변경 없음 |
| `migration-reviewer` | 게이트 20 판정 §3-4의 「범위 인접 결함 1건」이 이 개정으로 닫혔다 |
| 리더 | 단독 처리 사유: 요구 무변경 · 불변식 무축소 · React 무영향 · 리더 판정 조항 무접촉. **재심 대상 아님** |

---

## 2026-08-19 · M-405b · M-405 정정 누락 보완 (`x-improvements` OQ-1의 「7종」)

| 항목 | 값 |
|------|-----|
| 계기 | 게이트 22 교차 종합 **ⓗ** — M-405가 같은 수를 인용하는 자리를 훑으면서 `x-improvements[id=OQ-1]` 한 곳을 놓쳤다 |
| 근거 | **G1** (계약이 사실과 다름) — M-405와 같은 근거다 |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 서술 정정이고 나가는 바이트가 그대로다 |
| React 영향 | **없음** |
| 통보 대상 | `kotlin-implementer`, `migration-reviewer`, 리더 |

### 왜 이 한 곳만 남았나 — 면제 목록과 누락은 다르다

M-405는 **`x-changelog`의 옛 항목 2곳을 명시적으로 면제**했다(H-1 항목이 "미도달 7종"이라고
적은 자리 등). 그 면제는 지금도 유효하다 — 옛 `x-changelog` 항목은 **당시에 무엇을 적었는지의
기록**이고, 사후 편집하면 판정의 근거가 사라진다.

**`x-improvements`는 그 면제 목록에 없었다.** 성격이 다르기 때문이다 — 이 절은 개선 후보의
**지금 상태**를 적는 자리이고, OQ-1은 `status: 반영됨`으로 현재를 서술한다. 현재를 서술하는
자리에 옛 수가 남으면 그것은 기록이 아니라 **틀린 값**이다. 즉 이 건은 "면제할지 말지"가
갈린 것이 아니라 **훑을 때 빠뜨린 것**이고, 그래서 보완이 맞다.

### 바뀐 조항

- `x-improvements[id=OQ-1].detail` — "파싱 단계 거절 **7종**" → **6종** + 정정 표시
  (M-405b 표시와 "`x-changelog` 면제와 성격이 왜 다른지" 한 줄)

검증: `uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → OK

### 영향받는 검증

**없다.** 이 절을 읽는 테스트가 없다 — 열거자↔계약 집합 대조는
`x-phase3-measurement.unreachable_by_filter.cases`를 읽고, 그 자리는 M-405에서 이미 6종이다.

---

## 2026-08-19 · D-2 · 작업 공간 삭제 거절 두 갈래가 동시에 해당할 때의 순서

| 항목 | 값 |
|------|-----|
| 계기 | 게이트 22 교차 종합 **X-12**(2관점 합의) — 3자 대조 미결 D-2. 내 2단계 검증 ⑸가 "침묵이 맞지 않다 — 조항 권고"로 판정하고 계약 개정은 다음 단위로 넘겼던 자리다 |
| 근거 | **G2** (요구사항이 요구한다) |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 상태 코드·본문 스키마·필드·enum 무변경, 생성 타입 차이 0 |
| React 영향 | **없음** — `frontend/src/api/client.ts`에 이 오퍼레이션의 호출부가 아예 없다(UI 범위 밖). 409 문구를 읽는 화면도 없다 |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `migration-reviewer`, 리더 |

### 판정 — 구현이 고른 순서가 옳다

`WorkspaceService.refusalFor`는 「마지막 하나」(`ownedWorkspaceCount <= 1`)를 먼저 보고
「문서 있음」을 나중에 본다. **계약 소유자로서 그 순서가 옳다고 판정한다.** 이 개정은 구현을
바꾸라는 것이 아니라 **계약이 그것을 말하게** 하는 것이다.

사용자 안내 흐름으로 가른다. 반대로 고르면 사용자는 안내대로 **문서를 먼저 지우고**
다시 거절당한다 — 그때는 갈래 1이 막겠다고 적은 되돌릴 수 없는 파기가 **그 안내를 따랐기
때문에** 이미 일어난 뒤다. 「마지막 하나」를 먼저 내면 문서를 비워도 결과가 바뀌지 않으므로
사용자는 헛된 파기 대신 **새 작업 공간을 만드는 쪽**으로 안내된다.

### 왜 G2가 서는가 — 침묵도 판정이지만 이 자리는 아니다

같은 검증에서 D-1(409 중복 이름 문구)과 D-3(목록 정렬 동점)은 **침묵 유지**로 판정했다.
이 건만 다른 이유가 셋이다.

1. **지목할 요구사항이 조항 자신 안에 있다.** 갈래 1의 사유("되돌릴 수 없는 파기를 「정리」라는
   이름으로 실행하게 두지 않고 무엇이 사라지는지 사용자가 먼저 보게 한다" — 계획 §2.3)를
   계약이 이미 적어 두었다. 침묵하면 **그 목적을 정확히 깨뜨리는 순서도 계약을 만족한다.**
   말하지 않는 것과 말한 것을 지키지 못하는 것은 다르다.
2. **드문 자리가 아니다.** 겹치는 상태는 「작업 공간이 하나뿐이고 그 안에 문서가 있다」이고,
   기본 작업 공간이 가입 때 하나 만들어지므로 **가입 후 문서를 올리고 두 번째 공간을 만들지
   않은 모든 사용자**의 상태다. 사실상 기본 경로다.
3. **지금 아무도 재지 않는다**(실측). WD-4는 공간을 하나 **더 만든 뒤** 문서를 넣고, WD-5는
   **문서 없는** 마지막 공간을 지운다 — 겹치는 상태를 통과하는 케이스가 **0건**이라 순서를
   뒤집어도 현재 계약 테스트는 전부 초록이다.

### 좁히는 방향이지만 단독 처리다 — 근거

종전에는 두 순서가 **다** 계약을 만족했으므로 이 조항은 자유도 하나를 **좁힌다**. 그런데도
`x-change-policy.escalate_to_leader` ①~④ 어디에도 해당하지 않는다.

| 재심 사유 | 해당 여부 |
|---|---|
| ① 리더가 명시적으로 판정한 조항을 뒤집는가 | **아니다** — 이 자리를 리더가 판정한 적이 없다(그래서 미결 D-2였다) |
| ② React **런타임 코드**가 의존하는 동작인가 | **아니다** — 호출부가 없다 |
| ③ 보안 불변식 축소인가 | **아니다** — 소유권 은닉 404·오류 본문·헤더 어느 것도 건드리지 않는다 |
| ④ 배포·운영 동작이 달라지는가 | **아니다** |

### 바뀐 조항 — 문면 한 곳

- `paths./workspaces/{workspace_id}.delete.description` — 409 두 갈래 서술 뒤에
  **"둘 다 해당하면 2(마지막 남은 작업 공간)를 낸다"** 문단 추가(사유 포함)
- `x-changelog`에 D-2 항목

**blast radius는 이 오퍼레이션 하나다.** 409 응답 객체·두 `detail` 예시·상태 코드는 손대지
않았다 — 바뀐 것은 겹치는 상태에서 **둘 중 어느 예시가 나가는가**뿐이다.

검증: `uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → OK

### 영향받는 검증

| 대상 | 조치 |
|---|---|
| **WD-9 신설** | `03_contract-keeper_workspaces-test-spec.md` §2-4에 등재했다. 겹치는 상태에서 409 `detail`이 **`last_one` 예시와 같고 `has_documents` 예시와 다름**을 함께 단언하고, 후속 조회로 **삭제되지 않았음**을 확인한다. 계층 C-I |
| 기존 WD-4·WD-5 | **바꾸지 않는다.** 두 갈래 각각의 단독 상태를 재는 케이스이고 그 일은 그대로다 |
| N-17(음성 대조) | 그대로. `last_one` 예시를 건드리면 WD-5와 **WD-9**이 함께 빨강이 된다 — 결속이 하나 늘 뿐 지시는 같다 |

### 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **구현 변경 없음** — `refusalFor`의 현재 순서가 계약이 된 것이다. **WD-9을 추가하라**(명세 §2-4). 이 케이스가 없으면 조항이 강제되지 않는다 — 지금 겹치는 상태를 통과하는 케이스가 0건이라 순서를 뒤집어도 전부 초록이다. 순서 판정을 `refusalFor` 한 함수에 모아 둔 것은 그대로 두는 편이 낫다(호출부에 흩으면 순서가 배치의 부산물로 보인다) |
| `parity-verifier` | 계약이 **Python과 의도적으로 다른 조항이 아니다** — Python 쪽 순서를 확인해 갈리면 그것은 「의도된 차이」가 아니라 Kotlin이 정본을 따르는 자리다. 이 조항은 **겹치는 상태에서 나가는 `detail`**만 고정한다 |
| `migration-reviewer` | 게이트 22 X-12 마감 — 「Phase 3 종료 전」 항목에서 닫아도 된다 |
| 리더 | 단독 처리 사유 위 표. **판정 자체를 보고한다**: 구현이 고른 순서(「마지막 하나」 먼저)를 옳다고 판정했고, 근거는 사용자 안내 흐름(헛된 파기 방지)이다 |

---

## 2026-08-19 · G24-R1 · 401 균일화 열거의 내부 모순 정합 (정본 = `x-auth`)

> **이름 주의** — 위 `2026-08-12 · R-1`(재개발 전환 호환 요구 정리)과 **다른 항목**이다.
> 게이트 24 교차 종합의 Claude **R-1**을 가리킨다. 계약 `x-changelog`에도 `G24-R1`로 적었다.

| 항목 | 값 |
|------|-----|
| 계기 | 게이트 24 교차 종합 `reviews/03_phase3-close_cross.md` **ⓒ**·표 행 4 — Claude **R-1**(수정 필요) · codex 관측(① §3 축② "같은 메시지라고 쓴 직후 두 메시지라고 써 내부 모순") · privacy-gate는 같은 블록 **뒷 문장만** 인용. 어느 조항이 정본인지의 판정을 리더가 `contract-keeper`로 넘겼다 |
| 근거 | **G1** (계약이 사실과 다르다 — 내부 모순). 한 조항이 자기 블록 안에서 반증됐다 |
| `info.version` | **올리지 않는다** (1.1.0 유지) — 와이어에 나가는 상태 코드·본문·헤더·두 `detail` 예시가 하나도 안 바뀐다 |
| React 영향 | **없음**(실측 — 아래 절) |
| 통보 대상 | `kotlin-implementer`, `parity-verifier`, `privacy-gate`, `migration-reviewer`, 리더 |

### 무엇이 갈렸었나 — 세 문면

| 위치 | 열거 | 무헤더 |
|---|---|---|
| `x-auth.failure_uniformity` (`:299-302`) | 이메일 부재·비밀번호 불일치·토큰 만료·위조·계정 삭제 | **없음** |
| `components/responses/Unauthorized.description` (`:1495-1498`) | **헤더 누락**·토큰 위조·만료·용도 불일치·계정 삭제 | **있음** |
| 같은 블록 세 줄 뒤 (`:1500-1501`) | "메시지는 **두 가지**가 나온다: 헤더가 아예 없으면 … " | **자기 반증** |

"모두 같은 401, 같은 메시지"와 "메시지는 두 가지가 나온다"는 동시에 참일 수 없다.
구현(`b9097f6`)은 첫째 조항만 인용해 무헤더를 균일화에서 제외했다.

### 판정 — 정본은 `x-auth`다

**정본을 `x-auth`로 고른 이유 셋.**

1. `x-auth`는 인증 요구를 모아 둔 **규범 확장**이고, `components/responses/Unauthorized`는
   그 요구가 **나타나는 응답 표현**이다. 요구와 표현이 갈리면 요구 쪽이 정본이다.
2. 자기 반증을 안고 있던 것은 `Unauthorized` 쪽 문면 **하나뿐**이다.
3. 세 관점의 실질 판단이 **「무헤더 제외가 옳다」로 수렴**했다 — codex는 계약 열거를
   근거로, privacy-gate는 **기제 근거**(무헤더↔토큰있음은 요청자가 만든 상태이므로
   서버 비밀이 담기지 않아 **정보 이득 0**)로, Claude는 판정 유보. 리더가 이 방향을 지시했다.

### 복사를 다시 만들지 않았다 — 드리프트의 기제가 중복이다

종전 문면을 「무헤더만 빼고 나머지는 그대로」 고치는 선택지가 있었다. **택하지 않았다.**
그러면 두 벌 목록이 그대로 남아 다음 개정에서 **또 갈린다** — 이번에 갈린 방식 그대로다.
그래서 `Unauthorized.description`의 열거를 **걷어내고 정본을 가리키게** 했다
(프로젝트 규약: 정본에 값을 옮겨 적지 않는다. `CLAUDE.md` — "옮겨 적었더니 즉시 갈렸고,
그 드리프트가 바로 이 절이 금지하는 것이다").

### 잃은 요구는 0이다 — 열거는 없애지 않고 정본으로 병합했다

| 종전에 어디에만 있었나 | 지금 어디에 있나 |
|---|---|
| `Unauthorized`에만: **용도 불일치**(`typ`) | `x-auth.failure_uniformity`의 「위조(**서명 불일치·필수 클레임 누락·`typ` 용도 불일치**)」 괄호. `claim_typ`(`:268`)·`required_claims`(`:269`)가 이미 같은 것을 요구하고 있었다 |
| `x-auth`에만: 이메일 부재·비밀번호 불일치 | `Unauthorized`의 **둘째 갈래** 서술("… 계정 삭제·이메일 부재·비밀번호 불일치가 전부 같은 401") |

### 무헤더 제외는 불변식 축소가 아니다 — 그래도 단독으로 정하지 않았다

`x-change-policy.invariants`의 "인증 실패 응답의 균일성"이 막는 것은 **「서버만 아는 사실이
응답 차이로 새는 것」**이다. 계정의 존재·삭제 여부, 토큰의 만료·진위가 그것이다. 무헤더↔
토큰있음은 **요청자가 스스로 만든 상태**라 그 사실을 담지 않는다 — 헤더를 붙이지 않은
클라이언트는 자기가 붙이지 않았음을 이미 안다.

그래도 **자유도를 좁히는 방향**이므로 `escalate_to_leader` ③에 걸릴 수 있다고 보고,
단독 판정으로 처리하지 않았다. 근거는 **리더 지시(게이트 24)** + 3관점 수렴이다.

| 재심 사유 | 해당 여부 |
|---|---|
| ① 리더가 명시적으로 판정한 조항을 뒤집는가 | **아니다** — 이 정정 자체가 리더 지시다 |
| ② React **런타임 코드**가 의존하는 동작인가 | **아니다** — 아래 실측 |
| ③ 보안 불변식 축소인가 | **판단 갈릴 수 있어 리더 근거에 얹었다**(위 문단) |
| ④ 배포·운영 동작이 달라지는가 | **아니다** — 나가는 바이트가 그대로다 |

### React 영향 — 없음 (실측)

- `frontend/src/api/client.ts:128` — 401 분기는 `response.status === 401 && token !== null`
  **하나뿐이고 `detail` 문자열을 비교하지 않는다.**
- `readErrorMessage`(`:69-89`) — 문자열 `detail`을 그대로 화면 문구로 넘긴다. 분기 없음.
- grep `인증이 필요합니다` → `frontend/src` **0건**, `backend-kotlin` **1건**
  (`AuthenticationInterceptor.kt:95` 상수, 계약 `no_header` 예시와 같은 값).
- 응답 바이트가 안 바뀌므로 화면에 보이는 문구도 그대로다.

### blast radius

| 대상 | 수 |
|---|---|
| `#/components/responses/Unauthorized` 참조 오퍼레이션 | **12** — 정정 후 위치 `:870`·`:896`·`:961`·`:1030`·`:1068`·`:1111`·`:1155`·`:1244`·`:1291`·`:1318`·`:1369`·`:1418`. **description만 바뀌었고 headers·content·examples는 무변경**이라 12곳 전부 나가는 응답이 동일하다 |
| `x-auth.failure_uniformity` 정의 | **1** (`:299`). Kotlin 인용 3곳(`AuthService.kt:109`·`AuthService` authenticate KDoc·`AuthenticationInterceptor` KDoc) — **전부 좁은 범위를 인용**하고 있어 정정된 문면과 정합한다 |
| 계약 안 「무헤더」 언급 | **1블록** — `Unauthorized` 하나뿐이었다(grep 확인). 흩어진 사본 없음 |

### 검증

`uvx --from openapi-spec-validator openapi-spec-validator contracts/easy-doc-v1.yaml` → **OK**

### 영향받는 검증

| 대상 | 조치 |
|---|---|
| **계약 테스트** | **변경 없음.** L-3(자격증명 갈래)·M-3(토큰 제시 갈래)이 이미 **두 갈래로 나눠** 재고 있고(`03_contract-keeper_auth-test-spec.md` §2, "둘 나온다고 적는다"), 정정된 문면이 그 구조를 그대로 말한다. 단언이 바뀌는 케이스 **0건** |
| **문서 드리프트 1건 잔존** | `00_contract-keeper_test-plan.md` §2 **X-A2** 행이 `x-auth.failure_uniformity`를 인용하면서 열거에 **「헤더 누락」**을 넣고 있다 — 옛 넓은 문면의 잔재다. **이번 커밋 범위 밖**(리더가 계약 파일 + 이 파일로 한정)이라 **별도 문서 커밋에서 정정**한다. 실제 케이스는 이미 좁은 범위로 갈라 재므로 강제되는 동작은 어긋나지 않는다 |
| `AuthenticationWorkUniformityTest` | 구조 단언이 **토큰 제시 갈래**를 묶으므로 정정된 범위와 정합. 무헤더를 넣은 4갈래 시간 비는 이 조항의 측정이 아니다 |

### 원장 Phase 3 표 **행 3** 판정 — 계약상 **세 갈래**로 좁혀진다

행 3의 미해결 항목은 *"4갈래 비(구현자 2.801~2.983 ↔ privacy-gate 2.185, 두 측정이 갈린다)"*
였고, 교차 종합은 그 개폐가 **R-1 판정에 달렸다**고 적었다 — *"좁은 조항(`x-auth`)이 정본이면
시간 축 미해결 항목이 닫힌다. 넓은 조항이 정본이면 4갈래 비 위에서 열린다."*

**정본을 좁은 조항으로 판정했고, 그 범위를 계약이 명시적으로 적게 했다**(`:316-320`:
"균일성 판정의 대상은 토큰·자격증명이 제시된 갈래들 — 무헤더를 함께 넣은 비교는 이 조항이
요구하는 균일성의 측정이 아니다"). 따라서:

| 항목 | 계약상 지위 | 상태 |
|---|---|---|
| **3갈래 비**(토큰 제시: 위조·만료·계정 삭제) | `failure_uniformity`가 **요구한다** | **닫힌다** — 2관점 실측(구현자 1.007~1.036 / privacy-gate **1.003**) |
| **4갈래 비**(무헤더 포함) | 이 조항의 측정이 **아니다** | **행 3에서 뺀다.** 두 측정이 갈린 것(2.801~2.983 ↔ 2.185)은 **계약 미충족이 아니다** — 대조되지 않은 수치로 남기고 기록만 유지한다 |

**단, 행 3을 이 판정만으로 닫지는 않는다.** 행 3에는 **codex X24-2(상시 시간 회귀 부재 —
구조 단언이 시간의 대리값)**가 함께 걸려 있고, 그것은 **오늘 값이 아니라 미래 회귀**를
겨눈 지적이라 이번 계약 정정으로 반박되지 않는다(교차 종합 §5-Ⅰ, 리더 판정 대기).
이 개정이 닫는 것은 **「4갈래 비가 계약 위반인가」 한 갈래**다.

### 통보

| 대상 | 내용 |
|---|---|
| `kotlin-implementer` | **구현 변경 없음.** `b9097f6`이 고른 범위(무헤더 제외)가 계약이 된 것이다. `AuthenticationInterceptor`·`AuthService`의 KDoc 인용은 그대로 유효하다 — 다만 「실패 문구는 두 갈래다」의 근거가 이제 **정본 `x-auth.failure_uniformity`의 범위 조항**이므로, 인용을 갱신할 때 그쪽을 가리키면 된다 |
| `parity-verifier` | 이 조항은 **Python과 의도적으로 다른 자리가 아니다** — 계약 자신의 내부 모순을 없앤 것이라 Python 대조 기준이 바뀌지 않는다. 균일성 대조 대상은 **토큰 제시 갈래**로 한정한다 |
| `privacy-gate` | 무헤더 제외의 **기제 근거(정보 이득 0)**를 계약 문면에 그대로 넣었다(`:309-315`). 균일성 불변식이 좁아진 것이 아니라 **원래 대상이 무엇이었는지**를 적은 것이다. 기록 ①(무자격 `Bearer` 토큰의 DB 왕복 1회 — 가용성)은 **이 조항 밖**이며 배포 전 레이트 리밋 판단으로 남는다 |
| `migration-reviewer` | 게이트 24 **R-1 마감** — 「Phase 3 종료 전」에서 닫아도 된다. 표 행 7("계약 개선 3자 동일 이전에 계약 1자가 자기와 다르다")의 그 1자 모순이 해소됐다 |
| 리더 | **행 3 판정 위 절.** 4갈래 비는 계약상 균일성 측정이 아니므로 행 3에서 뺀다. **행 3 자체는 X24-2(상시 회귀) 때문에 열려 있다.** `00_progress.md`는 지시대로 건드리지 않았다 |
