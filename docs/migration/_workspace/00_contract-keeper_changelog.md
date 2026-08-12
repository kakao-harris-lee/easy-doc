# 계약 변경 이력 (contract-keeper)

`contracts/easy-doc-v1.yaml`이 **정본**이다. 이 파일은 "무엇이 왜 바뀌었고 누구에게
알려야 하는지"만 남긴다 — 스펙 전문을 옮겨 적지 않는다(두 벌이 갈리면 정본을 잃는다).

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
