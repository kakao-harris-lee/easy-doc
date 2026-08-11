---
name: api-contract-freeze
description: easy-doc HTTP API 계약을 동결·검증할 때 반드시 사용. 14개 엔드포인트의 메서드·경로·상태 코드·오류 본문 {"detail":...}·snake_case 필드·JWT sub/exp/typ·Cache-Control no-store·X-Content-Type-Options·Location 헤더·RFC 5987 Content-Disposition·CORS 노출 헤더·변환 상태값·입력 상한을 검증 가능한 체크 항목으로 고정한다. contracts/easy-doc-v1.yaml을 만들거나 고칠 때, FastAPI OpenAPI·계약 파일·frontend/src/api/types.ts 3자 대조를 할 때, contract test를 작성·보완할 때, Kotlin API 응답이 계약과 맞는지 재검증할 때, React 타입을 OpenAPI 생성 타입으로 교체할 때, 계약 변경을 승인·기록할 때 사용. 이 스킬은 계약이 무엇이어야 하는지를 정의한다 — 같은 대상(JWT·no-store·오류 본문)이라도 Python과 Kotlin이 같은 값을 내는지 실행해 증명하는 일은 python-kotlin-parity, Kotlin 코드를 쓸 때 따르는 구현 규약은 kotlin-spring-conventions, 보안 불변식 감사와 차단 판정은 migration-safety-gate가 맡는다.
---

# API 계약 동결 절차 (easy-doc v1)

이 스킬의 요점 하나: **계획 문서를 맹신하지 않고 코드를 기준으로 삼는다.**
`docs/plans/2026-08-11-kotlin-react-migration.md` §2.2는 계약의 의도를 적은 것이고,
실제로 지켜야 하는 값은 `app/api/*.py`와 `app/main.py`에 있다. 둘이 어긋나면 코드가 이기고,
어긋난 지점은 반드시 보고서에 남긴다.

## 1. 실제 엔드포인트 표 (2026-08-11 코드 확인)

FastAPI가 노출하는 경로는 **정확히 14개**다. 다만 그중 하나는 `/health`이므로
**제품 엔드포인트는 13개 + 헬스 체크 1개**다. 계획 §2.2의 "14개 HTTP 엔드포인트"는
`/health`를 포함해야만 맞는 수다. 계약 파일에도 `/health`를 포함시켜 14개를 유지한다.

확인 명령(아래 §3)의 출력과 이 표가 다르면 코드가 바뀐 것이므로 표를 갱신하고 보고한다.

| # | 메서드 | 경로 | 성공 | 인증 | 주요 오류 | 비고 |
|---|---|---|---|---|---|---|
| 1 | POST | `/auth/signup` | 201 | 불필요 | 422(입력), 409(이메일 중복) | 응답 `{id, email}` — 해시 미포함. 캐시 금지 헤더(이메일이 실린다) |
| 2 | POST | `/auth/login` | 200 | 불필요 | 422, 401(자격증명) | 응답 `{access_token, token_type, expires_in}`. 캐시 금지 헤더 — **본문이 액세스 토큰 자체다** |
| 3 | GET | `/auth/me` | 200 | 필요 | 401 | 응답 `{id, email}`. 캐시 금지 헤더(1과 같은 스키마) |
| 4 | POST | `/documents` | **202** | 필요 | 422(빈 본문·4,000자 초과·형식·추출 실패), **413**(10MB 초과), 401, 404(남의 workspace_id), **502**(큐 등록 실패), 503(설정 미비·큐 미준비) | JSON 또는 multipart. **`Location: /conversions/{id}`** |
| 5 | GET | `/documents` | 200 | 필요 | 422(limit/offset 범위), 401, 404(남의 workspace_id) | `limit` 1~100 기본 20, `offset` ≥0 기본 0, `workspace_id` 선택. 캐시 금지 헤더 |
| 6 | DELETE | `/documents/{document_id}` | **204** | 필요 | 422(UUID 형식), 401, 404 | 본문 없음 |
| 7 | GET | `/conversions/{conversion_id}` | 200 | 필요 | 422, 401, 404 | 캐시 금지 헤더. `masked_items[].original`에 실제 개인정보 포함 |
| 8 | PUT | `/conversions/{conversion_id}` | 200 | 필요 | 422(빈 값·4,000자 초과), 401, 404, **409**(아직 done 아님) | 응답은 7과 같은 스키마 — 따라서 **캐시 금지 헤더도 7과 같다**(§2.7 해결 1) |
| 9 | GET | `/conversions/{conversion_id}/export` | 200 | 필요 | 422(`format` 누락·미지원 값), 401, 404, 409 | `format=docx\|txt\|hwpx` **필수**. 파일 바이트 + `Content-Disposition` |
| 10 | GET | `/workspaces` | 200 | 필요 | 401 | 첫 항목이 기본 작업 공간. 캐시 금지 헤더 |
| 11 | POST | `/workspaces` | **201** | 필요 | 422(이름 규칙), 401, 409(같은 이름) | 캐시 금지 헤더 |
| 12 | PATCH | `/workspaces/{workspace_id}` | 200 | 필요 | 422, 401, 404, 409(같은 이름) | PUT 아님 |
| 13 | DELETE | `/workspaces/{workspace_id}` | **204** | 필요 | 422, 401, 404, 409(문서 있음·마지막 하나) | React에는 호출부가 없다(§5 참고) |
| 14 | GET | `/health` | 200 | 불필요 | — | `{"status":"ok"}` |

## 2. 동결 항목 체크리스트

각 항목은 **테스트로 확인 가능한 형태**로 적었다. 계약 테스트가 없는 항목은 동결된 것이 아니다.

### 2.1 상태 코드

- [ ] 가입 성공 = **201** (200 아님)
- [ ] 업로드 접수 = **202** (201 아님 — 변환은 아직 시작 전)
- [ ] 작업 공간 생성 = **201**
- [ ] 문서 삭제·작업 공간 삭제 = **204**, 본문 길이 0
- [ ] 입력 검증 실패 = **422** (Spring 기본값 400 아님)
- [ ] 지원하지 않는 파일 형식·추출 실패 = **422** (415 아님 — Content-Type이 아니라 본문 자체를 처리 못 하는 상황)
- [ ] 업로드 크기 초과 = **413** — *계획 §2.2 목록에 없는 코드다. 반드시 계약 파일에 넣는다*
- [ ] 이메일 중복·이름 중복·상태 충돌(완료 전 검수 저장, 비어 있지 않은/마지막 작업 공간 삭제) = **409**
- [ ] 인증 실패(헤더 누락·위조·만료·삭제된 계정 전부) = **401** + `WWW-Authenticate: Bearer`
- [ ] 남의 자원 접근 = **404** (403 아님 — 존재 사실 자체를 숨긴다)
- [ ] LLM·큐 장애 = **502**, 설정 미비·큐 미준비 = **503**, 저장소·미처리 예외 = **500**
  - 큐 관련 코드가 둘로 갈리므로 주의한다. **큐에 등록을 시도했다가 실패**하면 `QueueUnavailableError` → **502**(`app/services/documents.py`의 enqueue 실패 경로). **큐 의존성 자체가 배선되지 않았으면**(lifespan이 Redis에 붙지 못한 상태) `ConfigurationError` → **503**(`app/api/deps.py`의 큐 provider). 둘을 하나로 합치면 "일시적 장애라 재시도할 값어치가 있는가"라는 클라이언트 판단이 무너진다

### 2.2 오류 본문

- [ ] 본문 최상위 키는 항상 `detail` 하나
- [ ] 도메인 예외 → `detail`은 **문자열**(한국어 사용자 문구)
- [ ] 검증 실패(422) → `detail`은 **객체 배열** `[{loc: string[], msg: string, type: string}]`
- [ ] 검증 실패 응답에 **입력값이 없다**(`input`, `ctx` 키 금지). 비밀번호가 응답 본문과 액세스 로그에 남는 경로다
- [ ] 미매핑 도메인 예외 = 500 + `{"detail": "요청을 처리하지 못했습니다"}`
- [ ] 도메인 밖 예외 = 500 + `{"detail": "서버 오류가 발생했습니다"}`
- [ ] `Content-Type: application/json` (`application/problem+json` 아님)

`detail`이 문자열과 배열의 union이라는 점이 계약의 핵심이다.
`frontend/src/api/client.ts`의 **`readErrorMessage` 함수**가 문자열 분기와 배열 분기를
나란히 두어 두 모양을 모두 처리하므로, 한쪽만 구현하면 화면에서
"요청을 처리하지 못했습니다" 폴백만 나오고 진짜 사유가 사라진다.
(코드 위치는 함수 이름으로 찾는다 — 행 번호는 코드가 움직이면 곧 틀린 값이 된다.)

### 2.3 필드 이름·형식

- [ ] 모든 JSON 필드는 **snake_case** (`access_token`, `document_id`, `char_count`, `has_more`, `retention_expires_at`, `missing_placeholders`, `provider_name`, `input_tokens`, `failure_code`, `document_count`, `edited_text`, `reviewed_at`, `masked_items`, `source_format`, `workspace_id`, `expires_in`, `token_type`, `conversion_id`)
- [ ] 식별자는 UUID 문자열
- [ ] 시각은 ISO 8601 문자열
- [ ] 변환 상태는 `pending | processing | done | failed` 소문자 4개뿐 (DB CHECK 제약과 동일 집합)
- [ ] 내보내기 형식은 `docx | txt | hwpx` (`pdf`·구버전 `hwp` 없음)

### 2.4 인증

- [ ] `Authorization: Bearer <token>`
- [ ] JWT 알고리즘 **HS256**
- [ ] 페이로드 클레임은 `sub`(사용자 UUID 문자열), `exp`, `typ` **세 개뿐**. 이메일 등 개인정보를 넣지 않는다
- [ ] `typ` 값은 `"access"` — 값이 다르면 거부
- [ ] `sub`/`exp`/`typ`가 **모두 있어야** 유효. `exp` 없는 토큰은 영구 자격증명이 되므로 필수
- [ ] 서명 키 최소 길이 32바이트(HS256 해시 출력 크기) 검사 유지
- [ ] 로그인 성공 시에만 Argon2 재해시. 기존 PHC 문자열은 그대로 검증 가능해야 한다
- [ ] Python 발급 토큰을 Kotlin이 읽고, Kotlin 발급 토큰을 Python이 읽는 **양방향** fixture

### 2.5 헤더

- [ ] `Cache-Control: no-store` + `X-Content-Type-Options: nosniff` — 붙는 곳 **10개**:
      `GET /documents`, `GET /conversions/{id}`, **`PUT /conversions/{id}`**,
      `GET /conversions/{id}/export`, `GET /workspaces`, `POST /workspaces`,
      `PATCH /workspaces/{id}`, **`POST /auth/signup`**, **`POST /auth/login`**,
      **`GET /auth/me`**
      — `PUT /conversions/{id}`는 2026-08-11에 추가됐다(§2.7 해결 1). 조회와 같은 응답
      스키마를 쓰는 자리가 생기면 헤더도 함께 따라가야 한다는 뜻이다
      — `/auth` 3개도 같은 날 추가됐다(§2.7 해결 2). 이유는 응답에 실리는 값이다:
      `POST /auth/login`은 **Bearer 토큰 자체**를 내보내므로 캐시된 사본이 다른
      사용자에게 나가면 그대로 계정 탈취가 되고, `POST /auth/signup`·`GET /auth/me`는
      이 프로젝트가 개인정보로 취급하는 **이메일**을 싣는다. §2.4가 JWT 페이로드에
      이메일을 넣지 않기로 못박은 것과 같은 판단이 응답 본문에도 적용된다
      — 이 목록은 **개인정보·자격증명이 실리는 응답 전체**다. 새 엔드포인트가 사용자
      콘텐츠나 토큰을 내보내면 목록에 추가하고 개수를 갱신한다
- [ ] `POST /documents` 202 응답에 **`Location: /conversions/{conversion_id}`**
- [ ] 내보내기 미디어 타입:
      docx → `application/vnd.openxmlformats-officedocument.wordprocessingml.document`,
      txt → `text/plain; charset=utf-8`(charset 필수 — 없으면 한글이 깨진다),
      hwpx → `application/hwp+zip`
- [ ] `Content-Disposition: attachment; filename="easy-read.<ext>"; filename*=UTF-8''<percent-encoded>`
      — ASCII 대체 이름과 RFC 5987 확장을 **둘 다** 보낸다. 헤더 값은 latin-1만 담을 수
      있어 한글 이름은 `filename*`에만 실린다. React `parseFilename`이
      `filename\*=UTF-8''` 부분만 읽는다
- [ ] CORS: `allow_origins` 설정값(기본 `http://localhost:5173`),
      `allow_credentials=false`, 메서드 `GET, POST, PUT, PATCH, DELETE`,
      요청 헤더 `Authorization, Content-Type`,
      **노출 헤더 `Content-Disposition, Location`**
- [ ] 노출 헤더가 빠지면 브라우저 JS가 파일명과 접수 주소를 읽지 못한다. 서버가 보내는
      것과 브라우저가 읽을 수 있는 것은 다른 문제이므로 **브라우저 컨텍스트에서** 확인한다

### 2.6 입력 상한

- [ ] 변환 대상 본문 **4,000자** 초과 → 422 (`MAX_CONVERTIBLE_CHARS`)
- [ ] 검수 수정본도 같은 4,000자 상한
- [ ] 업로드 파일 **10MB**(`10 * 1024 * 1024`) 초과 → **413**
- [ ] zip 컨테이너(docx·hwpx) 압축 해제 예산 = 업로드 상한의 5배. 초과 → 422
- [ ] 지원 확장자 `docx | pdf | hwpx`(대소문자 무시). 구버전 `doc`은 전용 안내 문구와 함께 422
- [ ] 텍스트 레이어 없는 PDF·암호화 PDF 거절
- [ ] 목록 `limit` 1~100, `offset` ≥ 0 — 범위 밖은 422

### 2.7 결정 기록

동결은 "현재 동작을 그대로 굳힌다"가 기본이지만, 아래는 **현재 코드가 의도와 어긋나
보이는 지점**이라 굳히기 전에 판단이 필요했던 항목이다. 임의로 고치지 말고 결정을 받는다.

#### 해결됨

1. **`PUT /conversions/{id}`도 캐시 금지 헤더를 낸다** (2026-08-11 결정 — 굳히지 않고 고침).
   이 응답은 `GET`과 같은 스키마라 `masked_items[].original`에 실제 개인정보가 실리는데
   `response.headers.update(...)` 호출이 빠져 있었다(`app/api/documents.py`의
   `update_conversion`). **계약상 `Cache-Control: no-store` + `X-Content-Type-Options:
   nosniff`를 낸다**로 확정하고 구현을 고쳤다 — 개인정보가 담긴 응답이 캐시되는 쪽을
   계약으로 굳힐 이유가 없기 때문이다. 회귀 테스트는
   `tests/api/test_documents.py::test_검수_저장_응답은_캐시하지_않는다`.
   Kotlin 구현도 이 헤더를 내야 하며, 없으면 계약 위반이다.

2. **`/auth` 3개 엔드포인트도 캐시 금지 헤더를 낸다** (2026-08-11 결정 — 굳히지 않고 고침).
   `POST /auth/signup`·`POST /auth/login`·`GET /auth/me`는 `PRIVATE_RESPONSE_HEADERS`를
   **import조차 하지 않은** 상태였다(`app/api/auth.py`). 해결 1을 반영한 뒤 전수 대조에서
   드러난 누락이다. 위험이 문서 응답보다 크다 — `POST /auth/login` 응답 본문은 **Bearer
   토큰 자체**라 캐시된 사본 하나가 그대로 계정 탈취 수단이 되고, 나머지 둘은 이메일을
   싣는다. §2.4가 "JWT 페이로드에 이메일 등 개인정보를 넣지 않는다"고 못박고
   `app/api/errors.py` 모듈 docstring도 "이메일·비밀번호·문서 본문이 응답이나 액세스
   로그로 새지 않게 한다"고 적는데, 같은 값이 응답 본문에서는 캐시 가능한 상태로 나가는
   것은 일관되지 않는다. `app/api/workspaces.py`가 "작업 공간 이름도 사용자가 적은
   콘텐츠"라며 헤더 범위를 넓힌 전례가 있으므로 `/auth`만 빠진 것은 의도된 예외가 아니라
   정책 전파 누락으로 판단했다. **계약상 세 응답 모두 `Cache-Control: no-store` +
   `X-Content-Type-Options: nosniff`를 낸다**로 확정하고 구현을 고쳤다. 회귀 테스트는
   `tests/api/test_auth.py::test_가입_응답은_캐시하지_않는다`,
   `::test_로그인_응답은_캐시하지_않는다`, `::test_내_정보_응답은_캐시하지_않는다`.
   Kotlin 구현도 이 헤더를 내야 하며, 없으면 계약 위반이다.
   상수는 `app/api/documents.py`에 그대로 두고 `auth.py`가 import한다 —
   `workspaces.py`가 이미 같은 방식이고, 정의를 옮기면 이 헤더를 감사 대상으로 지목하는
   에이전트 문서들의 참조 경로가 한꺼번에 어긋난다.

#### 미결 (동결 전에 판단할 것)

3. **미처리 500 응답에 CORS 헤더가 없다.** `ServerErrorMiddleware`가 CORS 미들웨어 바깥에
   있어 구조적으로 붙지 않는다(`app/api/errors.py`에 한계로 명시됨). 브라우저는 상태
   코드조차 못 읽고 네트워크 오류로 본다. Kotlin에서는 이 제약이 없으므로 "동등하게
   재현"할지 "개선"할지 결정한다.

## 3. `contracts/easy-doc-v1.yaml` 작성 절차

FastAPI가 만드는 OpenAPI를 **초안**으로 쓰되 그대로 계약으로 삼지 않는다.
자동 생성물은 라우터 시그니처에서 유도할 수 있는 것만 담기 때문에,
동결 항목의 절반 이상이 빠져 있다(§3.2).

### 3.1 초안 추출

```bash
mkdir -p contracts docs/migration/_workspace
uv run python -c "
import yaml
from app.main import app
with open('docs/migration/_workspace/00_contract-keeper_openapi-fastapi.yaml','w',encoding='utf-8') as f:
    yaml.safe_dump(app.openapi(), f, allow_unicode=True, sort_keys=False)
"
```

경로·메서드 목록만 빠르게 확인할 때:

```bash
uv run python -c "
from app.main import app
spec = app.openapi()
rows = sorted((p, m.upper(), sorted(op['responses'])) for p, ops in spec['paths'].items() for m, op in ops.items())
print('count', len(rows))
for p, m, r in rows: print(f'{m:6} {p:40} {r}')
"
```

이 출력이 §1 표와 일치하는지 먼저 확인한다. **개수가 14가 아니면 표부터 갱신한다.**

### 3.2 자동 생성물에서 빠지는 것 — 수기로 채운다

실행해 확인한 결과, FastAPI OpenAPI에는 **200/201/202/204/422만** 선언되어 있다.
다음은 전부 손으로 채워야 한다.

- 401, 404, 409, 413, 502, 503 응답과 각 오류 본문 스키마
- 오류 `detail`의 문자열 / 배열 union
- 모든 응답 헤더: `Location`, `Cache-Control`, `X-Content-Type-Options`,
  `Content-Disposition`, `WWW-Authenticate`
- CORS 정책(OpenAPI가 표현하지 못하므로 `description` 또는 별도 섹션에 산문으로 기록)
- `POST /documents`의 multipart 스키마 — 라우터가 `Request`를 직접 읽어 파싱하므로
  (JSON과 multipart를 한 엔드포인트가 받기 때문) 자동 생성물에 요청 본문 스키마가 없다.
  `file`, `title`, `workspace_id` 파트를 손으로 적는다
- `status` 필드의 enum 값 — 백엔드 Pydantic이 `str`로 선언해 스키마에 `string`으로만
  나온다. React는 4개 리터럴 union으로 좁혀 놓았으므로 **계약 파일에서 enum으로 고정**한다
- 입력 상한(4,000자, 10MB)을 `description`과 제약으로 명시

### 3.3 작성 순서

1. 초안을 `docs/migration/_workspace/`에 뽑는다(계약 파일에 바로 덮어쓰지 않는다).
2. §1 표와 대조해 경로·메서드·성공 코드를 확정한다.
3. §2 체크리스트를 위에서부터 훑으며 빠진 항목을 채운다.
4. §2.7의 결정(해결·미결 모두)을 반영하고, 결정 근거를 해당 스키마의 `description`에 남긴다.
5. `contracts/easy-doc-v1.yaml`로 저장한다. 이 파일이 이후 Kotlin API와 React 타입 생성의
   **단일 기준**이다.
6. 3자 대조(§4)를 돌려 보고서를 낸다.

## 4. 3자 대조 절차

동결의 목적은 "문서를 만드는 것"이 아니라 **세 곳의 진술이 같은지 확인하는 것**이다.

| 축 | 무엇 | 성격 |
|---|---|---|
| ① | FastAPI OpenAPI 산출물 | 현재 구현이 실제로 하는 일 (기계 생성) |
| ② | `contracts/easy-doc-v1.yaml` | 우리가 동결한 약속 (수기) |
| ③ | `frontend/src/api/types.ts` + `client.ts` | 클라이언트가 믿고 있는 것 (수기) |

절차:

1. ①에서 (경로, 메서드, 상태 코드, 응답 스키마 필드명·타입)을 뽑는다.
2. ②에서 같은 항목을 뽑는다.
3. ③에서 인터페이스 필드와 `client.ts`의 실제 호출 경로·메서드·쿼리 파라미터를 뽑는다.
4. 아래 표 형식으로 **불일치만** 보고한다. 일치 항목을 나열하면 진짜 문제가 묻힌다.

```markdown
| 항목 | ① FastAPI | ② 계약 파일 | ③ React | 판정 | 조치 |
|---|---|---|---|---|---|
| ConversionResponse.status | string | enum 4값 | 리터럴 union 4값 | ①이 느슨함 | ②를 기준으로 Kotlin은 enum, ①은 절체 후 소멸 |
```

판정은 셋 중 하나다: **계약 파일을 고친다** / **구현이 계약을 어겼다(버그)** /
**의도된 차이(사유 기록)**. "나중에 본다"는 판정이 아니다.

보고서는 `docs/migration/_workspace/00_contract-keeper_three-way-diff.md`에 남긴다.

### 이미 알려진 3자 차이

대조를 돌리기 전에 아는 것부터 적어 둔다. 이것들이 보고서에 다시 나오면 정상이다.

- **`DELETE /workspaces/{id}`는 ③에 없다.** 백엔드에는 있지만 React에 호출부가 없다.
  UI 범위 밖이라 **의도적으로** 비워 둔 것이다(`client.ts`의 `renameWorkspace` 주석,
  `app/api/workspaces.py` 모듈 docstring). 계약 파일에는 포함하되 React 타입 생성 후에도
  호출 래퍼를 추가하지 않는다.
- **`GET /health`는 ③에 없다.** 화면이 부르지 않는다. 계약에는 남긴다.
- **`status` 필드의 넓이 차이**: ①은 `string`, ③은 4값 union. ②에서 enum으로 좁힌다.
- **오류 응답 스키마가 ①에 거의 없다.** ③은 `detail`의 두 모양을 모두 다룬다.
  ②가 이 union을 정확히 적어야 Kotlin 구현이 재현할 수 있다.
- **`POST /documents`의 multipart 요청 본문이 ①에 없다.** ③에는 `createDocumentFromFile`이
  `file`·`workspace_id` 파트를 보낸다.

## 5. contract test 작성 기준

계약 테스트는 **HTTP 경계에서** 돈다. 서비스 계층 단위 테스트로 대신할 수 없다 —
상태 코드 매핑, 헤더 부착, 직렬화 이름은 전부 경계에서 결정되기 때문이다.

계층별 역할:

| 계층 | 도구 | 검증 범위 |
|---|---|---|
| 계약(경계) | Python: FastAPI `TestClient` / Kotlin: `@SpringBootTest` + MockMvc | 상태 코드, **응답 헤더**, 오류 본문 모양, 필드 이름, enum 값 |
| 통합(DB) | Testcontainers PostgreSQL | 소유권 404, 409 충돌, 트랜잭션 원자성 |
| 브라우저 | compose + 실제 브라우저 | CORS 노출 헤더, 파일 다운로드 파일명, 401 세션 만료 |

**본문만 검증하면 안 되는 이유**: 이 API에서 실제로 깨지기 쉬운 것은 대부분 본문이 아니다.

- `Location` 헤더가 빠지면 202 본문은 멀쩡한데 클라이언트가 폴링 주소를 못 만든다.
- `Cache-Control: no-store`가 빠지면 응답은 정상이고 **프록시가 개인정보를 캐시한다**.
  이것은 200 OK로 나타나는 보안 사고이므로 본문 검증으로는 절대 안 잡힌다.
- `Content-Disposition`의 `filename*` 부분이 빠지면 다운로드는 되고 파일명만 깨진다.
- CORS `expose_headers`가 빠지면 서버 응답은 완전한데 브라우저 JS만 헤더를 못 읽는다.
- 상태 코드가 422 대신 400이면 본문 형태가 같아도 React 분기가 달라진다.

따라서 **엔드포인트 하나당 최소한** ① 성공 경로(상태 코드 + 헤더 + 필드 이름),
② 인증 없음 401, ③ 남의 자원 404, ④ 대표 입력 오류 422를 둔다.
409·413이 있는 엔드포인트는 그것도 추가한다.

추가로 반드시 넣을 테스트:

- 검증 실패 응답 본문에 **제출한 비밀번호 문자열이 없음**을 단언한다. 회귀하면 즉시 잡힌다.
- 401 응답에 `WWW-Authenticate: Bearer`가 있음.
- 남의 자원 접근이 403이 **아님**을 단언한다(404여야 한다).
- Python 발급 JWT를 Kotlin이 수용하고 그 반대도 되는 양방향 fixture 테스트.

같은 계약 테스트를 **Python과 Kotlin 양쪽에서 돌린다.** 절체 전까지 Python suite는
비교 기준이다(계획 §6). 가능하면 요청/기대응답을 데이터로 두고 두 런타임이 같은 표를
읽게 만든다 — 두 벌의 테스트 코드가 서로 다른 것을 검증하기 시작하면 대조가 무의미해진다.

## 6. React 타입 교체 절차 (Phase 6)

1. `contracts/easy-doc-v1.yaml`에서 TypeScript 타입을 생성해 `frontend/src/api/types.ts`를
   **대체**한다. 수기 타입을 유지하면 계약이 바뀔 때마다 손으로 따라가야 하고,
   실제로 지금 `status` 필드처럼 백엔드보다 좁게 적힌 곳이 생긴다.
2. 생성 전 계약 파일에서 `status`·`format` 같은 값 집합이 enum으로 고정되어 있는지
   확인한다. `string`으로 두면 생성 타입이 넓어져 **현재 React보다 타입 안전성이 떨어진다.**
   교체가 퇴보가 되면 안 된다.
3. **`client.ts`는 유지한다.** 이 파일은 계약에서 생성할 수 없는 클라이언트 정책을 담고 있다.
   - 토큰 부착과 `readToken`/`clearToken` 연동
   - 401 수신 시 토큰 폐기 + `unauthorizedHandler` 호출 — 단, **인증된 호출에서만**
     (로그인 실패 401은 세션 만료가 아니다). 생성 코드는 이 구분을 알 수 없다
   - 네트워크 오류를 `status = 0`(`NETWORK_ERROR_STATUS`)의 `ApiError`로 정규화 —
     사용자가 취할 조치가 서버 오류와 다르므로 구분한다
   - `detail`의 문자열/배열 union을 한국어 문구로 바꾸는 `readErrorMessage`
   - FormData일 때 `Content-Type`을 **손대지 않는 것**(boundary가 빠지면 서버가 못 읽는다)
   - `downloadExport`의 blob 처리와 `filename*=UTF-8''` 파싱
   생성기는 이 중 어느 것도 만들어 주지 않는다. 타입만 갈아끼우고 정책은 그대로 둔다.
4. 교체 후 `ExportFormat`처럼 `client.ts`가 import하던 타입 이름이 생성 타입에서 다르면,
   **`client.ts`를 고치기보다 얇은 재수출 모듈을 두는 쪽**을 먼저 검토한다. 유지해야 할
   파일의 변경 면적을 줄이는 것이 목적이다.
5. Kotlin 오류 문구가 현재 한국어 사용자 메시지와 같은지 확인한다(계획 §4.1). 문구가
   바뀌면 화면 텍스트가 바뀌고, React 테스트가 문구를 단언하고 있으면 함께 깨진다.
6. `npm run` 기준의 타입 검사·린트·테스트·프로덕션 빌드가 모두 통과해야 교체 완료다.
   기존 React 테스트(60개 기준선)가 줄면 안 된다.

## 7. 계약 변경 승인 절차

v1 동결 이후 계약을 바꾸려면 아래 판단을 거친다. **"Kotlin에서 이렇게 하는 게 자연스러워서"는
사유가 아니다.** 자연스러움은 클라이언트와 데이터가 이미 존재하기 전의 논거다.

판단 기준:

1. **호환 파괴인가?** 필드 추가는 대개 안전하다. 필드 제거·이름 변경·타입 축소·상태 코드
   변경·enum 값 제거는 파괴다. 파괴적 변경은 절체 전에는 원칙적으로 금지다 — 전환 중에
   계약까지 움직이면 실패 원인이 "포팅 버그"인지 "계약 변경"인지 가릴 수 없다.
2. **버그 수정인가, 설계 변경인가?** §2.7처럼 현재 구현이 정책과 어긋난 경우는 수정 후보다.
   이때도 계약 파일과 contract test를 **먼저** 고치고 구현을 따라오게 한다.
3. **React가 영향을 받는가?** 받는다면 백엔드·계약·프런트를 같은 변경 단위로 묶는다.
4. **관찰 기간 중인가?** 절체 후 관찰 기간(계획 §7)에는 롤백 가능성이 살아 있어야 하므로
   Python이 못 만드는 응답을 Kotlin이 내보내면 안 된다. 계약 변경은 관찰 기간 종료 후로 미룬다.

기록 위치:

- 계약 자체의 변경: `contracts/easy-doc-v1.yaml`에 반영하고, 변경 사유를 해당 스키마의
  `description`에 한 문장으로 남긴다.
- 결정 근거와 대안 비교: `docs/migration/_workspace/{phase}_contract-keeper_{주제}.md`
- 계획 문서와 어긋나는 결정: `docs/plans/2026-08-11-kotlin-react-migration.md`의 해당 절을
  갱신하도록 **제안**한다(프로젝트 Definition of Done의 "문서 갱신 제안"에 해당).
- 파괴적 변경이라면 파일명을 `easy-doc-v2.yaml`로 새로 만들고 v1을 남긴다. 같은 파일을
  덮어쓰면 "무엇이 v1이었는지"를 잃어 롤백 판단이 불가능해진다.
