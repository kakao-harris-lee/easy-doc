# Phase 00 · pre-phase0 — Claude 독립 리뷰 (1회차)

**작성:** `migration-reviewer` / 2026-08-11
**회차:** **1차 — 독립 리뷰.** codex 산출물을 참조하지 않았고, 이 회차에서는 교차 대조표를 만들지 않는다.
`codex-reviewer`가 같은 범위를 병렬로 독립 리뷰 중이므로 codex 산출물 부재는 **정상이며 실패로 기록하지 않는다.**
교차 종합은 2차 재호출에서 `00_pre-phase0_cross.md`로 낸다. **이 파일만으로 Phase 종료 조건 충족을 보고하지 않는다.**

**리뷰 범위:** 이번 세션 변경 전체 — 추적 변경 5개 파일(`app/api/auth.py`, `app/api/documents.py`,
`tests/api/test_auth.py`, `tests/api/test_documents.py`, `CLAUDE.md`)과 미추적 하네스 일체
(`.claude/agents/*.md` 6개, `.claude/skills/*/SKILL.md` 6개, 번들 스크립트 3개,
`.claude/skills/codex-review/references/focus-library.md`, `docs/migration/_workspace/00_progress.md`).

**참조 기준:** 계획 문서 `docs/plans/2026-08-11-kotlin-react-migration.md` §2.2(외부 계약)·§2.3(내부 정책)·
§3.1~§3.2(목표 아키텍처)·§4.3(암호)·§4.5(문서 파서)·§4.6(LLM·골든)·§5 Phase 0/Phase 7(즉시 중단 기준)·§6(검증 매트릭스).
`contracts/easy-doc-v1.yaml`이 아직 없으므로 **계약 준수 축의 채점 기준은 `.claude/skills/api-contract-freeze/SKILL.md`를 대리 기준으로** 삼았다.

## 심각도 기준과 총평

심각도는 계획 §5 Phase 7의 **즉시 중단 기준** 해당 여부로 가른다.

| 심각도 | 정의 | 건수 |
|---|---|---|
| **차단(Critical)** | §5 Phase 7 즉시 중단 기준에 해당 | **0** |
| **수정 필요(Major)** | Phase 종료 조건 미충족 | **12** |
| **권고(Minor)** | 그 외 | **8** |
| **판정 필요** | 심각도를 이 회차에서 확정할 수 없음 | **3** |

**차단 0건이다.** §5 Phase 7의 여섯 기준(Fernet 복호화 실패 / 타 사용자 데이터 노출·404 위반 /
마스킹 전 본문 전송 / 중복 LLM 호출·작업 유실 / 문서 fixture 불일치 / 골든 품질·최대 2회 호출 위반)에
해당하는 사실은 하나도 확인되지 않았다. 이번 세션 변경은 **개인정보 응답 캐시 금지 범위를 넓히고
하네스를 정비한 것**이며, 방향과 근거 모두 타당하다.

다만 이번 세션에서 codex가 두 번 잡은 것이 모두 **"게이트가 검증하지 않고도 통과하는" 결함**이었으므로
같은 종류를 적대적으로 더 찾았고, **같은 계열이 6건 더 남아 있다**(H-1 ~ H-5, A-2). 그중 H-1은
실행으로 재현했다. 하네스 무결성이 이번 회차의 핵심 지적이다.

---

## 축 1 — 계약 준수

### C-1 [권고] `api-contract-freeze` §1 엔드포인트 표가 §2.5 헤더 목록과 어긋난다

§2.5(SKILL.md:94-98)는 캐시 금지 헤더가 붙는 곳을 **10개**로 열거하고, 실제 코드도 정확히 10개다(C-4).
그런데 **§1 표는 그중 8개에만** "캐시 금지 헤더"를 적었다. 빠진 두 행:

| 행 | 근거 | 표의 비고란 | 코드 |
|---|---|---|---|
| 9 · `GET /conversions/{id}/export` | SKILL.md:31 | "파일 바이트 + `Content-Disposition`" — 헤더 언급 없음 | `app/api/documents.py:381` 에 부착됨 |
| 12 · `PATCH /workspaces/{workspace_id}` | SKILL.md:34 | "PUT 아님" — 헤더 언급 없음 | `app/api/workspaces.py:113` 에 부착됨 |

§1은 스스로를 "실제 엔드포인트 표 (2026-08-11 코드 확인)"로 규정하고, 스킬 서두(SKILL.md:8-11)가
"계획 문서를 맹신하지 않고 코드를 기준으로 삼는다"고 선언한다. **§1만 읽고 Kotlin을 구현하면 두 곳이 빠진다.**
계약 문서가 코드보다 뒤처진 것이 아니라, 같은 문서의 두 절이 서로 어긋난 경우다.

### C-2 [판정 필요] 계약이 **오류 응답**의 캐시 헤더를 규정하지 않는다

실측(아래 축 3 S-2의 프로브)에서 401·422 응답은 전부 `cache-control` 헤더가 **없다**.
§2.5는 헤더 부착을 **엔드포인트 단위**로만 동결했고 `(엔드포인트, 상태 코드)` 쌍으로는 적지 않았다.
지금은 오류 본문에 개인정보가 없으므로(S-2에서 실증) 무해하지만, **Python과 Spring의 기본 거동이
정반대라 포팅 시 자동으로 갈라진다**(축 2 P-1). 계약이 이 지점을 말하지 않으면 어느 쪽도 위반이 아니게 되어
parity 판정이 불가능하다. → `contract-keeper`에 스펙 보강 요청.

### C-3 [검토함 — 지적 없음] CORS 노출 헤더

`app/main.py:59` — `expose_headers=["Content-Disposition", "Location"]`.
§2.5(SKILL.md:117-120)의 요구와 일치. `allow_credentials=False`, 메서드 5종, 요청 헤더 2종도 일치.

### C-4 [검토함 — 지적 없음] 헤더 부착 지점 수 = 계약 목록 수

코드 전수 확인 결과 `PRIVATE_RESPONSE_HEADERS` 부착 지점은 정확히 **10개**이고 §2.5 목록과 1:1로 대응한다.
auth 3(`auth.py:66,80,92`) + documents 4(`documents.py:282,321,343,381`) + workspaces 3(`workspaces.py:83,96,113`).

**다만 지시받은 "7 → 10"은 사실과 다르다.** `git show HEAD` 기준 이전 상태는 documents 3 + workspaces 3 = **6개**였고,
이번 세션에 `PUT /conversions/{id}` 1개와 `/auth` 3개가 더해져 10개가 됐다. 즉 **6 → 10**이다.
계약 문서·진행 문서 어디에도 "7"은 적혀 있지 않으므로 문서 오류는 아니지만, 지시 근거가 된 숫자이므로 정정해 둔다.

---

## 축 2 — parity 위험

### P-1 [수정 필요] 오류 경로의 헤더 거동이 Python과 Spring에서 **반대**다 — 순진한 포팅이 계약을 깬다

Python 핸들러는 주입된 `Response`에 헤더를 쓰지만, 예외가 나면 `app/api/errors.py:65-70`의
`_make_handler`가 **새 `JSONResponse`를 만들어** 그 헤더를 버린다.

실증: `login`은 헤더를 서비스 호출 **전에** 설정하는데(`auth.py:80`), 자격증명 실패 401 응답의
`cache-control`은 `None`이었다. 즉 Python에서는 헤더 설정 위치와 무관하게 오류 응답에 헤더가 붙지 않는다.

Spring MVC는 반대다. `HttpServletResponse`에 쓴 헤더는 이후 `@ExceptionHandler`가 만든 응답에도 **남는다**.
따라서 컨트롤러 앞머리에서 헤더를 세팅하는 자연스러운 포팅은 401/404/409/422 응답에 `no-store`를 붙여
**Python이 내지 않는 헤더를 내게 된다.**

이 위험을 키우는 것이 **Python 쪽의 설정 위치가 제각각이라는 점**이다:

| 위치 | 핸들러 |
|---|---|
| 서비스 호출 **전** | `auth.py:66`(signup), `:80`(login), `:92`(me), `documents.py:282`(list_documents), `workspaces.py:83,96,113` |
| 서비스 호출 **후** | `documents.py:321`(read_conversion), `:343`(update_conversion) |

Python에서는 둘 다 결과가 같아 무해하지만, 포터는 **두 가지 의도**로 읽는다. Spring에서는 이 차이가 곧
오류 응답 헤더의 유무 차이가 된다. → `kotlin-implementer`에 전달, C-2와 함께 `contract-keeper`가 스펙을 고정할 것.

### P-2 [수정 필요] JWT `exp` 경계는 fixture로 **고정돼 있다(통과)** — 그러나 그것을 실행할 Kotlin 쪽 전제가 규약에 없다

지시받은 질문("PyJWT `exp <= now` vs JVM `exp < now` 경계가 fixture로 고정돼 있는가")의 답은 **예**다.
`dump_parity_fixtures.py:686-692`:

```
"exp-boundary-exact",
"기준 시각이 exp와 같으면 만료다 (PyJWT: exp <= now). "
"JVM 라이브러리는 exp < now로 보는 것이 많아 여기서 갈린다",
```

`exp-boundary-one-second-before`(:679-684)와 짝을 이루고, 기대값은 실제 `AuthService.resolve_token`을
고정 시계 아래에서 돌려 만든다(`outcome()`, :639-660). **이 축은 잘 설계돼 있다.**

**그러나** 이 세 케이스(`exp-boundary-exact`, `exp-boundary-one-second-before`, `expired`)를 Kotlin에서
돌리려면 Kotlin `AuthService`가 **주입 가능한 `Clock`을 받아야 한다.** 그 요구는
`python-kotlin-parity/SKILL.md:181`("Kotlin 하네스도 `Clock.fixed(...)`로 같은 시각을 넣는다")에만 있고,
**정작 구현자가 따르는 `kotlin-spring-conventions`에는 시각 주입에 대한 언급이 한 줄도 없다**(grep 확인).
`Instant.now()`를 내부에서 부르는 구현이 나오면 경계 케이스를 아예 돌릴 수 없어, Phase 3 이후 재작업이 된다.
역방향 `verify_jwt`도 `verify_at`을 **필수**로 요구한다(:1158-1160).

### P-3 [수정 필요] argon2 도메인에 **역방향(external) 케이스가 없다**

`crypto`와 `jwt`는 `_external(...)` 케이스와 전용 검증 명령을 갖는다:

```
PROOF_NAMES = {                                   # dump_parity_fixtures.py:1062-1065
    "verify-crypto": ("crypto-roundtrip-request", "verify-crypto.verified.json"),
    "verify-jwt":    ("jwt-roundtrip-request",    "verify-jwt.verified.json"),
}
```

**argon2에는 둘 다 없다.** `build_argon2`(:833-1012)의 14개 케이스는 전부
"Python이 만든 PHC를 Kotlin이 검증한다" 한 방향뿐이다.

이것이 특히 문제인 이유는 argon2가 **솔트 때문에 출력 문자열 비교가 원천적으로 불가능**하다는 점이다
(코드 주석 :847-848이 직접 그렇게 적는다). 즉 "Kotlin이 만든 PHC를 Python이 읽을 수 있는가"는
**역방향 실행 외에 증명할 방법이 아예 없는데, 그 수단이 없다.**

운영상 필요한 방향이다: 절체 후 Kotlin이 신규 가입자의 PHC를 만들고, 계획 §4.3 3항이 관찰 기간 동안
롤백 가능성을 유지하라고 요구한다. 롤백 시 Python이 Kotlin 생성 PHC를 검증하지 못하면
**그 기간에 가입한 사용자 전원이 로그인하지 못한다.** I-8의 영향 문구("기존 사용자 전원이 로그인하지
못한다")와 같은 성격의 사고가 반대 방향으로 열려 있다.

### P-4 [검토함 — 현재 무해] `verify_crypto`에는 `verify_jwt`와 달리 시각 고정이 없다

`verify_jwt`는 `verify_at` 누락을 실패로 처리하지만(:1158-1160) `verify_crypto`(:1112-1136)에는 대응 장치가 없다.
확인 결과 `app/privacy/crypto.py:61`이 `self._fernet.decrypt(data)`를 `ttl` 인자 **없이** 부르므로
현재는 시각 의존성이 없다. **지금은 무해하다.** Fernet TTL을 켜는 변경이 들어오면 이 검증이
실행 시각에 따라 통과/실패를 오가게 되므로 그때 함께 고쳐야 한다.

### P-5 [권고] `verify_crypto`의 예외 처리가 입력 파일 결함을 암호 실패로 뭉갠다

```python
except (StorageError, Exception) as exc:   # dump_parity_fixtures.py:1127
```

`Exception`이 `StorageError`를 포함하므로 튜플이 무의미하고, `case["key"]`·`case["token"]` 누락에서 나는
`KeyError`까지 "복호화 실패"로 기록된다. 입력 파일이 깨진 것과 암호가 호환되지 않는 것은
**대응이 전혀 다른 사건**인데 증거 파일에 같은 문구로 남는다.

---

## 축 3 — 보안 불변식

### S-1 [검토함 — 지적 없음] 개인정보·자격증명이 실리는데 헤더가 없는 핸들러는 **남아 있지 않다**

`app/api/*.py` 전 핸들러(14개) 전수 확인. 헤더가 **없는** 4개는 모두 정당하다:

| 핸들러 | 헤더 없음의 근거 |
|---|---|
| `POST /documents` (202) | 본문이 `document_id`·`conversion_id`·`status`·`char_count`뿐 — 사용자 콘텐츠 없음 |
| `DELETE /documents/{id}` (204) | 본문 없음 (`documents.py:306`) |
| `DELETE /workspaces/{id}` (204) | 본문 없음 (`workspaces.py:133`) |
| `GET /health` | 본문 `{"status":"ok"}` |

이번 세션의 `/auth` 3개 추가는 **실제 누락을 메운 것**이 맞다. `POST /auth/login` 본문이 Bearer 토큰
자체라는 판단(`api-contract-freeze` §2.7 해결 2)은 타당하며, §2.4가 JWT 페이로드에 이메일을 금지한 것과
일관된다.

### S-2 [검토함 — 지적 없음] 오류 응답·422 검증 실패 본문에 입력값이 새지 않는다

마커 문자열을 심어 6종을 실측했다. **전부 미노출**(본문 + 헤더 전체를 검색):

| 상태 | 프로브 | 본문 | 마커 누출 |
|---|---|---|---|
| 422 | 이메일 형식 오류 | `{"detail":"이메일 형식이 올바르지 않습니다"}` | 없음 |
| 422 | 짧은 비밀번호 | `{"detail":"비밀번호는 8자 이상이어야 합니다"}` | 없음 |
| 422 | 이메일 타입 오류(int) | `{"detail":[{"loc":["body","email"],"msg":"Input should be a valid string","type":"string_type"}]}` | 없음 |
| 422 | 필드 누락 | `{"detail":[{"loc":["body","email"],"msg":"Field required","type":"missing"}]}` | 없음 |
| 401 | UUID 형식 오류 | `{"detail":"인증이 필요합니다"}` | 없음 |
| 401 | 자격증명 오류 | `{"detail":"이메일 또는 비밀번호가 올바르지 않습니다"}` | 없음 |

`app/api/errors.py:125-138`이 `input`·`ctx`를 버리고 `loc`/`msg`/`type`만 남기는 설계가 실제로 동작한다.

**잔여 위험**: `msg`는 `str(error.get("msg",""))`로 **그대로 통과**한다. 값을 메시지에 넣는 커스텀 validator가
생기면 그 경로로 샌다. 이것을 고정하는 테스트가 없다(S-3).

### S-3 [수정 필요] 계약이 **필수로 지정한** "비밀번호 미에코" 회귀 테스트가 없다

`api-contract-freeze/SKILL.md:306`은 "추가로 반드시 넣을 테스트"의 첫 항목으로
"검증 실패 응답 본문에 **제출한 비밀번호 문자열이 없음**을 단언한다. 회귀하면 즉시 잡힌다"를 지정한다.
현재 이 단언을 하는 테스트가 없다. S-2에서 지금 동작이 옳음은 실증했지만 **고정돼 있지 않다.**

### S-4 [판정 필요] 전수 스캔이 **exit 1**이고 BLOCK 후보 2건이 미판정 상태다

```
scan_privacy_invariants.py            → FULL SCAN EXIT=1
scan_privacy_invariants.py --changed  → CHANGED SCAN EXIT=0 (검사 파일 2개)
```

| 규칙 | 위치 | 1차 판단 |
|---|---|---|
| `XML-DTD` (BLOCK) | `app/easyread/bokjiro.py:21` — `import xml.etree.ElementTree as ET` | 사람 판정 필요. 업로드 문서 파서가 아니라 오프라인 도구 경로로 보이나, DTD 차단 여부는 확인해야 한다 |
| `SECRET-LITERAL` (BLOCK) | `frontend/src/api/client.test.ts:85` — `password: 'wrongpassword'` | **명백한 오탐** (테스트 리터럴 13자) |

둘 다 이번 세션 변경과 무관한 기존 코드지만, `migration-safety-gate`는 "증거 없는 통과는 없다"며
`docs/migration/_workspace/{phase}_privacy-gate_scan.md`를 요구하는데 **그 파일이 존재하지 않는다.**
Phase 0 종료 판정 전에 두 건의 판정과 근거가 기록돼야 한다. → `privacy-gate` 소관.

---

## 축 4 — 하네스 자체의 무결성 (이번 회차 핵심)

> 판정 기준: "이 게이트를 통과시키면서 실제로는 아무것도 검증하지 않는 방법이 있는가."

### H-1 [수정 필요] 역방향 검증 증거 파일이 **위조 가능하다** — 실행으로 재현함

`compare_parity.py`는 값 비교로 닫을 수 없는 역방향 케이스를 **실행 증거 파일**로 판정한다(:204-254).
`ran_in_actual` 검사(:220-225)로 "Kotlin 결과에 기대값을 베껴 넣는" 경로는 잘 막았다.
**그런데 증거 파일 자체를 손으로 쓰는 경로가 열려 있다.**

재현 절차(스크래치 디렉터리, 저장소 미변경):

1. `dump_parity_fixtures.py --domain jwt` 로 fixture 생성
2. `actual`을 fixture의 `expected` **복사**로 작성 (Kotlin 실행 없음)
3. `verify-jwt.verified.json`을 손으로 6줄 작성 — `verify-jwt`를 돌린 적 없고, `actual` 필드는
   `"존재하지-않는-경로.json"`으로 채움

결과:

```
[일치] jwt · jwt.json — 17건
부분 검증 통과(게이트 아님): 도메인 1/1 / 값 비교 17건 / 외부 검증 1건 / 미검증 0건 / …
EXIT=0
```

**"외부 검증 1건"** — 위조 증거가 그대로 인정됐다. `check_external`은 증거 파일의 `fixture_case`·`status`·
`checked`만 읽고, **`actual` 경로의 존재 여부도, 내용 해시도, `verified_at` 신선도도 검사하지 않는다.**

(위 실행은 `--only-domain`이라 "게이트 아님"으로 정확히 표시됐다. 다만 그 라벨은 *범위*에 대한 것이고,
`check_external`이 위조 증거를 인정한 것은 범위와 무관한 코드 경로다.)

닫는 법: `_write_proof`(:1068-1097)가 `--actual` 파일의 sha256을 함께 기록하고, `check_external`이
그 해시를 재계산해 대조하면 손으로 쓴 증거는 통과하지 못한다.

### H-2 [수정 필요] fixture의 **출처 검증이 선언만 있고 구현이 없다**

`dump()`는 fixture에 `"generator": "dump_parity_fixtures.py"`와 `"generated_at"`을 써 넣는다(:1043-1044).
**`compare_parity.py`는 이 두 필드를 한 번도 읽지 않는다.** 실제 검사는 `pair.domain not in BUILDERS`
하나뿐인데(:310), 손으로 쓴 파일도 `"domain": "jwt"` 한 줄이면 이를 만족한다.

그런데 바로 그 자리의 문구는 이렇게 적혀 있다(:312-313):

> 생성기 없이 손으로 만든 fixture는 Python 실행 결과라는 보장이 없다

**선언이 구현보다 강하다.** 읽는 사람은 출처가 검증된다고 믿게 된다.

### H-3 [수정 필요] `scan_privacy_invariants`는 **0개 파일을 검사하고 exit 0**을 낸다

```python
files = iter_files(args.changed)
if not files:
    print("검사 대상 파일이 없습니다.")
    return 0                       # scan_privacy_invariants.py:331-334
```

`--changed`로 돌릴 때 변경분이 `SCAN_ROOTS`(`app`, `backend-kotlin`, `scripts`, `frontend/src`) 밖에만 있으면
— 예컨대 `.claude/`·`docs/`·`tests/`만 바뀐 회차 — **아무것도 검사하지 않고 통과한다.**
`tests/`가 SCAN_ROOTS에 없으므로 테스트만 고친 변경은 항상 이 상태다.

같은 하네스의 `compare_parity.py`는 **정확히 이 함정을 알고 막았다**:

```python
if total_considered == 0:
    print(f"[검증 없음] {summary} — 비교한 케이스가 0건이다. 통과로 보고하지 않는다")
    return 1                       # compare_parity.py:614-616
```

**두 게이트가 같은 질문에 반대로 답한다.** parity 쪽이 배운 교훈이 privacy 쪽에 반영되지 않았다.

### H-4 [수정 필요] `CACHE-HEADER` 규칙이 **측정 능력을 잃었다** — 이번에 고친 결함이 바로 이 규칙의 담당 영역이다

전수 스캔 실측: `CACHE-HEADER` 적중 **1건**. `app/api/documents.py:50`의 상수 정의 한 줄뿐이다.
헤더를 실제로 붙이는 10개 지점은 `response.headers.update(PRIVATE_RESPONSE_HEADERS)` 형태라
정규식(`Cache-Control|no-store|nosniff|X-Content-Type-Options`)에 걸리지 않는다.

규칙이 선언한 목적은 이것이다(:184-185):

> 누락 탐지가 아니라 **분포 확인**용이다. 개인정보 응답 수 대비 헤더 지정 지점이 적으면 빠진 곳이 있다.

상수로 묶인 지금 이 값은 **엔드포인트가 10개든 1개든 항상 1**이다. 분포 정보가 0이다.
Kotlin에서 인터셉터·필터로 구현하면 역시 1이 된다.

문제의 핵심: **이번 세션에 고친 결함(`/auth` 3개 누락)이 정확히 이 규칙이 담당하는 종류**인데,
실제로 찾아낸 것은 사람의 전수 대조였고(§2.7 해결 2가 "전수 대조에서 드러난 누락"이라 적는다)
규칙은 그대로 남았다. 다음에 같은 누락이 생겨도 하네스는 침묵한다.

### H-5 [수정 필요] 스캔 규칙이 **줄 단위**라 Kotlin 관용 표기를 통째로 놓친다

같은 위반을 한 줄/여러 줄로 써서 실측했다:

| 코드 형태 | `LOG-BODY` | `LLM-RAW-INPUT` |
|---|---|---|
| `logger.error("변환 실패: {}", sourceText)` (한 줄) | **적중** | — |
| 같은 호출을 인자마다 줄바꿈 | **미적중** | — |
| `provider.complete(\n  prompt = sourceText,\n)` | — | **미적중** |

모든 규칙이 `for number, line in enumerate(lines)`로 **한 줄씩** 검사한다(:259-270).
인자가 여럿인 로그·호출을 여러 줄로 쓰는 것은 Kotlin에서 관용적이고 포매터가 유도하는 형태다.
**감사 대상이 Kotlin인데 Kotlin의 지배적 표기를 못 본다.**

`LLM-RAW-INPUT`은 BLOCK 등급이며 계획 §5 Phase 7의 즉시 중단 기준(마스킹 전 본문 전송)에 직결된다.

### H-6 [권고] `LLM-VENDOR-SDK`의 `sanctioned`에 경로가 아닌 **식별자**가 섞여 있다

```python
("app/llm/", "backend-kotlin/infrastructure/", "/llm/provider/", "LlmProvider")   # :112
```

검사는 `any(allowed in posix for allowed in rule.sanctioned)`(:267) — 경로 **부분 문자열** 매칭이다.
따라서 경로에 `LlmProvider`가 들어간 파일은 **어댑터 경계 밖이어도** 전부 면제된다
(예: `backend-kotlin/api/.../LlmProviderHelper.kt`). 스킬 자신이 "이 목록 자체가 감사 대상이다"라고
적었으므로(:68-69) 지적해 둔다.

### H-7 [권고] `LLM-RAW-INPUT`의 부정 전방탐색이 너무 쉽게 꺼진다

```python
r"\.complete\s*\(\s*(?![^)]*mask)[^)]*\b(?:source_text|sourceText|raw_text|…)\b"   # :119-121
```

인자 어디에든 `mask`가 있으면 규칙 전체가 꺼진다 — `maskingEnabled = false` 같은 인자로도 꺼진다.
변수명 목록도 좁아 `text`·`content`·`input`·`body`로 이름 지으면 걸리지 않는다.

### H-8 [권고] `float_tol` 인자에 상한이 없다

```python
if head == "float_tol" and arg:
    tolerance = float(arg)          # compare_parity.py:114-115
```

`float_tol:1e309`면 모든 수치 비교가 무조건 통과한다. `FORBIDDEN` 목록(:89-96)을 둔 취지—
"이걸 켜는 순간 검증이 통과를 위한 의식이 된다"—와 어긋나는 구멍이 허용 규칙 쪽에 남아 있다.
현재 fixture가 float를 쓰지 않아 **잠재적**이다.

### H-9 [수정 필요] `codex-review.sh`가 **focus 없는 adversarial**을 막지 않는다

스킬 §3.2는 이렇게 규정한다:

| 모드 | focus text |
|---|---|
| `review` | **불가** — 헬퍼가 거부한다 |
| `adversarial` | **필수**. focus 없는 adversarial은 review의 열화판이다 |

스크립트는 앞의 절반만 강제한다:

```bash
if [ "$MODE" = "review" ] && [ -n "$FOCUS" ]; then   # codex-review.sh:139-141
  die "오류: review 모드는 focus text를 받지 않는다…"
```

**역방향 검사가 없다.** `codex-review.sh adversarial` 만으로 실행되어 "adversarial 리뷰를 돌렸다"는
기록을 남기면서 실제로는 focus 없는 열화 리뷰가 된다. 계약·parity·보안 위험 영역(§3.2가 열거한 6개)에서
이 상태로 돌면 게이트가 형식만 남는다.

### H-10 [수정 필요] `--dry-run`이 "지적 없음"으로 기록될 수 있다

`--dry-run`은 exit 0으로 끝나고(:267-270), 진단 출력은 **전부 stderr**다(:242-265).
따라서 **stdout은 완전히 비어 있고 종료 코드는 0**이다.

여기에 스킬 §7의 실패 처리표가 겹친다:

| 증상 | 판별 | 대응 |
|---|---|---|
| 출력이 비어 있음 | 지적 0건 | "지적 없음"을 그대로 기록한다 |

두 규정이 만나면 **`--dry-run` 실행 결과가 "codex 리뷰 통과, 지적 없음"으로 기록된다.**
스크립트는 `exec "${CMD[@]}"`(:273)로 넘겨 출력이 비었는지 검증하는 지점이 없고,
헬퍼가 실패하며 빈 출력을 내는 경우도 같은 결과가 된다.

---

## 축 5 — 테스트 적정성

### T-1 [검토함 — 통과] 추가된 회귀 테스트 4건은 **실제로 회귀를 잡는다**

변이 검사로 확인했다. 저장소 파일을 고치지 않고, `PRIVATE_RESPONSE_HEADERS`를 런타임에 `{}`로 치환
(3개 모듈 모두 — `auth.py`·`workspaces.py`가 `from ... import`로 이름을 복사해 갖기 때문)한 뒤 재실행:

| 상태 | 결과 |
|---|---|
| 변이 전 | **7 passed** |
| 변이 후 | **7 failed** (전부 `KeyError: 'cache-control'`) |

이번에 추가된 4건(`test_가입/로그인/내_정보_응답은_캐시하지_않는다`, `test_검수_저장_응답은_캐시하지_않는다`)이
모두 포함된다. **헤더를 지우면 반드시 실패한다** — 공허한 테스트가 아니다.

### T-2 [수정 필요] 동결된 10개 중 **8개만** 캐시 헤더 테스트가 있다

| 엔드포인트 | 테스트 |
|---|---|
| `POST /auth/signup` · `POST /auth/login` · `GET /auth/me` | `tests/api/test_auth.py:260,269,280` |
| `GET /documents` · `GET /conversions/{id}` · `PUT /conversions/{id}` · `GET .../export` | `tests/api/test_documents.py` (4건) |
| `GET /workspaces` | `tests/api/test_workspaces.py:136` |
| **`POST /workspaces`** | **없음** |
| **`PATCH /workspaces/{workspace_id}`** | **없음** |

두 곳은 코드에 헤더가 있고(`workspaces.py:96,113`) §2.5도 열거하지만 **회귀 테스트가 없다.**
계약 §5는 "계약 테스트가 없는 항목은 동결된 것이 아니다"(SKILL.md:40)라고 못박는다.
이번 세션이 계약 목록을 10개로 확정했으므로, 그 10개를 고정하는 테스트도 10개여야 한다.

### T-3 [수정 필요] = S-3. 계약이 필수로 지정한 "비밀번호 미에코" 단언 테스트 부재.

---

## 축 6 — 에이전트/스킬 정의의 실행 가능성

### A-1 [검토함 — 통과] 프론트매터·상호 참조의 뼈대는 건전하다

- 에이전트 6개 전부 YAML 프론트매터 유효, `name:`이 파일명과 정확히 일치, `model: opus` 통일,
  `tools:` 미지정(전체 도구 상속). `privacy-gate.md`만 description을 따옴표로 감쌌는데
  값에 `Cache-Control: no-store`의 `: `가 들어가므로 **필요한 처리**다.
- 스킬 6개의 `name:`이 디렉터리명과 일치.
- 프로즈에 등장하는 에이전트·스킬 중 **존재하지 않는 것은 없다.** 글로벌 `multi-review`·`rule-checker`도 실재하며
  "글로벌"로 정확히 표기됐다.
- 번들 스크립트 3개 전부 실재하고 `codex-review.sh`는 실행 권한이 있다.
- 수치 주장이 코드와 일치한다: 골든 문서 56개, parity 도메인 11개(순서까지), 스캔 규칙 12개,
  FastAPI 노출 경로 14개, `provider.complete(` 호출 지점 2개, `PRIVATE_RESPONSE_HEADERS` 부착 10곳.

### A-2 [수정 필요] Phase 종료 판정을 강제하는 도구가 **하나도 없다**

하네스의 스크립트는 3개뿐이고, 어느 것도 다음을 검사하지 않는다:

- `..._codex-reviewer.md`가 실제로 존재하는지 (= codex 리뷰를 정말 받았는지)
- `..._cross.md`가 존재하는지 (= 교차 종합 3단계를 돌았는지)
- `00_progress.md`에서 `충족=예`인 행의 `근거` 칸이 비어 있지 않은지

리뷰 게이트 3단계(`codex-review/SKILL.md:22-34`)도, "근거가 비어 있는 `예`는 `아니오`로 취급한다"
(`00_progress.md:8`)도 **전부 산문 규약**이다.

이번 세션에 codex가 잡은 두 결함이 모두 "검증 없이 통과"였다는 점에 비추면,
**게이트 조합 자체가 같은 계열의 결함을 갖고 있다.** 개별 스크립트는 잘 만들어졌는데,
"어떤 스크립트가 어떤 종료 코드로 돌았고 그 증거가 어디 있는가"를 확인하는 층이 없다.

### A-3 [수정 필요] 리뷰 산출물 `{scope}` 슬러그가 **세 갈래로 갈렸다** — 이 게이트를 직접 깬다

`{phase}_{scope}_{reviewer}.md` 형식 자체는 모든 문서가 합의하지만, `{scope}` 값이 문서마다 다르다.
`codex-reviewer.md:25`는 자신이 정본이라 선언하는데, **다른 두 문서가 그 값을 쓰지 않는다.**

| Phase | `codex-reviewer.md:27-36` (자칭 정본) | `codex-review/SKILL.md:250` | `kotlin-migration/SKILL.md` · `00_progress.md` |
|---|---|---|---|
| 0 | `contract-yaml` | `contract` | — |
| 2 | `core-domain` | — | **`domain`** (SKILL.md:214, `00_progress.md:46` → `02_domain_cross.md`) |
| 3 | `auth-jdbc` | `auth` | — |
| 4 | `document-crypto` | `crypto` | **`crypto`** (SKILL.md:164) |
| 5 | `worker-lease` | `worker` | — |
| 6 | `frontend-contract` | `frontend` | — |

**결과**: Phase 2 게이트에서 `codex-reviewer`는 `02_core-domain_codex-reviewer.md`를 쓰는데,
오케스트레이터의 3단계는 `02_domain_cross.md`를 만든다. **`..._cross.md`가 자기 입력 두 개와 어간을 공유하지 않는다.**
파일명 규약을 둔 목적("파일명만 보고 누가 쓴 것인지 알 수 있어야 출처를 되짚을 수 있다",
`codex-review/SKILL.md:255`)이 정확히 무너진다. 2차 교차 종합 호출이 입력 파일을 못 찾는 실패로 바로 이어진다.

### A-4 [수정 필요] 오케스트레이터가 **존재하지 않는 도구**를 지시한다

`kotlin-migration/SKILL.md:29`·`:172`가 `TaskCreate` / `TaskList` / `TaskUpdate` 사용을 지시하는데
**현재 도구 표면에 없다.** (`TeamCreate`는 `:27`에서 "이 환경에 `TeamCreate`는 없다"고 올바르게 처리했으므로,
같은 확인이 이 세 개에는 이뤄지지 않은 것으로 보인다.) `SendMessage`·`Agent`(+`run_in_background`) 참조는 유효하다.

### A-5 [권고] 3자 대조 보고서 파일명이 두 갈래다

- `contract-keeper.md:77`·`:118` → `00_contract-keeper_drift.md`
- `api-contract-freeze/SKILL.md:261` → `00_contract-keeper_three-way-diff.md`

같은 산출물이다. 스킬을 따라 쓰면 에이전트가 재호출 때 다시 읽지 않는 파일이 된다.

### A-6 [권고] 계획 문서 §번호 오인용 3건

| 위치 | 인용 | 실제 |
|---|---|---|
| `api-contract-freeze/SKILL.md:354` | "관찰 기간(계획 **§7**)" | §7은 "예상 일정과 인력". 관찰 기간은 **§5 Phase 7** |
| `kotlin-spring-conventions/SKILL.md:262` | "중복 LLM 호출 — 계획 **§7**의 즉시 중단 기준" | 즉시 중단 기준은 **§5 Phase 7** |
| `api-contract-freeze/SKILL.md:35` | "React에는 호출부가 없다(**§5** 참고)" | 해당 서술은 자기 문서 **§4**(L267) |

### A-7 [권고] Phase 종료 조건을 잘못 옮겨 적은 곳 2건

| 위치 | 서술 | 실제 계획 |
|---|---|---|
| `contract-keeper.md:46` | "§5 Phase 0의 **종료 조건**이 계약을 contract test로 고정하는 것" | 그것은 Phase 0의 **작업 항목**. 종료 조건은 "암호문을 Kotlin에서 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨" |
| `kotlin-implementer.md:71` | "§5 Phase **2·4**의 종료 조건이 모듈 단위 동등성" | Phase 2는 맞으나 Phase 4는 **종단 간**("업로드→조회→검수→3형식 다운로드→삭제 통과, 평문이 DB·로그에 없음") |

### A-8 [권고] 리뷰 게이트 면제 목록이 두 문서에서 다르다

`codex-review/SKILL.md:40-45`는 4종(문서만/주석·오타/포매팅/`_workspace/**` 산출물)을 면제한다.
`kotlin-migration/SKILL.md:166`은 **"스킬/에이전트 정의 수정"을 면제에 추가**하고 `_workspace/**`를 뺐다.
양쪽 다 "면제 기준을 넓히면 게이트가 형식화된다"고 적는다.
**이번 세션의 변경 대부분이 바로 "스킬/에이전트 정의 수정"에 해당한다** — 오케스트레이터 쪽 규정을 따르면
이 리뷰 자체가 면제 대상이 된다. 판정이 필요하다.

### A-9 [권고] `codex-reviewer` 에이전트가 편집 권한을 갖는다

역할은 "가공하지 않은 원본 상태로 전달"인데 `tools:` 미지정이라 Edit/Write를 포함한 전체 도구를 상속한다.
`Bash, Read, Write`로 좁히면 권한이 역할과 일치한다.

### A-10 [권고] 에이전트↔스킬 산출물 목록이 서로를 덮지 않는다

- `migration-safety-gate/SKILL.md:209`가 요구하는 `{phase}_privacy-gate_scan.md`가 `privacy-gate.md:70-72`의 출력 목록에 없다.
- `api-contract-freeze/SKILL.md:189`가 쓰는 `00_contract-keeper_openapi-fastapi.yaml`이 `contract-keeper.md:72-78`에 없다.
- `contract-keeper.md:122`의 `00_contract-keeper_changelog.md`를 스킬 §7 "기록 위치"가 정의하지 않는다.

---

## Phase 종료 조건 대비 현황

이번 세션 변경은 Phase 0 **착수 전 하네스 정비**이므로 Phase 0 종료 조건을 움직이지 않는다.
`00_progress.md`의 10개 종료 조건은 전부 `아니오`이며 **그 기록은 정확하다.**

`00_progress.md`에서 **사실과 어긋나는 행**(→ `leader` 갱신 필요):

| 행 | 현재 기록 | 사실 |
|---|---|---|
| :71 | `PUT /conversions/{id}` 캐시 헤더 — **수정 진행 중** | **완료.** `documents.py:343` + `test_documents.py:935` 통과 |
| :73 | parity 게이트 우회 — **수정 진행 중** | **완료.** `compare_parity.py:75`(`EXPECTED_DOMAINS`)·`:605`(도메인 누락 exit 1) |
| — | **`/auth` 3개 헤더 추가가 표에 아예 없다** | :71보다 나중이고 더 큰 변경(로그인 응답은 Bearer 토큰 자체)인데 누락 |
| :51, :72 | "fixture 11 도메인은 **준비됨**" | **`parity/` 디렉터리가 존재하지 않는다.** 생성기(빌더)는 준비됐고 산출물은 미생성. 문구가 산출물 존재로 읽힌다 |

`00_progress.md:8`이 "근거가 비어 있는 `예`는 `아니오`로 취급한다"고 정한 기준을 산출물 주장에도
같이 적용해야 한다.

---

## 미실행 · 확인 불가 항목

| 항목 | 사유 |
|---|---|
| codex 독립 리뷰와의 교차 대조 | **1차이므로 정상.** `codex-reviewer` 병렬 진행 중, 2차에서 수행 |
| `contracts/easy-doc-v1.yaml` 대조 | 파일 미작성(Phase 0 산출물). `api-contract-freeze/SKILL.md`를 대리 기준으로 사용 |
| Kotlin/Spring 관용성 축의 **코드** 검증 | `backend-kotlin/` 미생성. 스킬 문서 수준에서만 검토(P-2, A-4) |
| parity fixture 11개 도메인의 실제 내용 | `parity/` 미생성. `jwt` 도메인만 스크래치에 생성해 확인(18건) |
| 골든셋 영향 | 이번 변경이 프롬프트·스타일 규칙·LLM 설정을 건드리지 않으므로 해당 없음 |
| `XML-DTD` BLOCK 후보의 최종 판정 | `privacy-gate` 소관(S-4) |
| 전체 게이트(11 도메인 전수) 위조 재현 | `jwt` 단일 도메인으로만 재현. `check_external`의 코드 경로는 범위와 무관하므로 결론은 같다고 판단하나, 전수 재현은 하지 않았다 |

---

## 다음 회차(2차 교차 종합) 입력

- `docs/migration/_workspace/reviews/00_pre-phase0_codex-reviewer.md` (codex 원본, 가공 전)
- 이 파일

2차에서는 **대조만 하고 새 지적을 만들지 않는다.** 종합 중 발견한 것은
`00_pre-phase0_cross.md`의 "종합 중 발견 — 미교차" 구획에 분리해 남긴다.
