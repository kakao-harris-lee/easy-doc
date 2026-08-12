# 계약 변경 이력 (contract-keeper)

`contracts/easy-doc-v1.yaml`이 **정본**이다. 이 파일은 "무엇이 왜 바뀌었고 누구에게
알려야 하는지"만 남긴다 — 스펙 전문을 옮겨 적지 않는다(두 벌이 갈리면 정본을 잃는다).

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
