# Phase 3 React ↔ Kotlin 연결 E2E — 구현 산출물

**작성:** 프런트 실행 에이전트 / **일자:** 2026-08-19
**대상 원장 행:** Phase 3 표 행 5 「React 를 Kotlin API 에 연결한 로그인·작업 공간 E2E」
**계획 정본:** `docs/migration/_workspace/03_contract-keeper_react-e2e-plan.md` (16f3f48)
**리더 판정:** OQ-E1 = **새 CI 잡 `e2e` 를 같은 배치에서 배선한다**(`local:` 로 닫지 않는다)
**범위:** `backend-kotlin/**` 무접촉 · `contracts/**` 무접촉 · `app/**` 무접촉 ·
`00_progress.md` 무접촉 · React 제품 코드(`types.ts`·`client.ts`·`pages/`·`components/`) 무수정

> 이 문서는 **실측 산출물**이다. 계획서의 「빨강이어야 한다」가 실제로 빨강인지를 돌려서 적는다.

---

## 0. 요약

| 물음 | 답 |
|---|---|
| 케이스 12건이 실 스택에서 도는가 | **예.** 12/12 통과 — 실 Chromium + 실 Kotlin bootJar + 실 PostgreSQL, 교차 출처(5173 → 8100), nginx 프록시 없음 |
| 제품 코드를 고쳤는가 | **아니오.** `src/**` 0건. 고친 것은 테스트 인프라(`vite.config.ts` 의 vitest `exclude`, tsconfig 참조, eslint·prettier 무시 목록)뿐 |
| 실행 경로 | `ci:e2e` (신설) + `local:frontend/e2e/run-local.sh` |
| 기존 잡·스위트에 영향 | **0.** `quality`·`frontend`·`kotlin`·`llm-lane` 무변경. Vitest 10파일 60건 그대로(변경 전후 동일) |
| 음성 대조 | **6건 전부 예측대로 빨강.** 그중 React 변이 2건은 계획이 「어느 층도 잡지 못한다」고 적은 자리다 |
| 계획을 정정한 것 | **3건** — §3-2 주의(`Cache-Control` 가독성), §3-2 E3 설계(기동 갈래만으로는 핸들러 변이를 못 잡는다), §3-4 `local:`(compose → CI 절차 재현 스크립트) |
| Kotlin 변경 요구 | **0건** (통보 1건은 계획 §5 OQ-E5 그대로 이월) |

---

## 1. 무엇을 만들었나

| 파일 | 몫 |
|---|---|
| `frontend/playwright.config.ts` | chromium 1개 프로젝트 · 워커 1 · `webServer` 가 Vite dev(5173, `--strictPort`)를 띄우고 `VITE_API_BASE_URL` 을 주입 |
| `frontend/e2e/contract.ts` | 계약 파일 파서 — **읽는 값은 셋뿐**(§2). 읽지 않는 값은 `ROUTES` 에 계약 키 경로 주석과 함께 |
| `frontend/e2e/support/network.ts` | 네트워크 관측 두 통로 — `NetworkLog`(페이지 이벤트) · `CdpNetworkLog`(CDP, 프리플라이트·원시 헤더) |
| `frontend/e2e/support/app.ts` | 화면 조작·합성 계정·토큰 심기. 선택자는 label·role 만 쓴다(제품 코드에 `data-testid` 를 심지 않는다) |
| `frontend/e2e/auth.spec.ts` | E1 · E2 · E3 · E12 |
| `frontend/e2e/workspaces.spec.ts` | E4 ~ E9 |
| `frontend/e2e/headers-cors.spec.ts` | E10 · E11 |
| `frontend/e2e/run-local.sh` | CI 절차를 그대로 재현하는 로컬 러너 |
| `.github/workflows/ci.yml` | 잡 `e2e` 신설 |

커밋 3건: `203831d`(하네스) · `a1e1925`(CI) · 이 문서.

---

## 2. 케이스 ↔ 계약 키 경로 대응

**계약 값을 손으로 적지 않는다.** 축 1 세 값만 `contracts/easy-doc-v1.yaml` 을 파싱해 읽고
(계획 §3-6), 나머지는 `e2e/contract.ts` 의 `ROUTES` 한 곳에 **계약 키 경로 주석**으로 지목한다.
`ROUTES` 를 계약에서 파싱하지 않는 이유는 계획 §3-6 그대로다 — 계약을 통째로 파싱하면 이
스위트가 Kotlin 계약 테스트의 약한 복제가 되고, E2E 고유의 값어치를 잃는다.

### 2-1. 계약 파일에서 **읽는** 값 (축 1 — 셋)

| 값 | 계약 키 경로 | 파일 위치 | 쓰는 케이스 |
|---|---|---|---|
| 전역 응답 헤더 2종 | `x-global-response-headers.headers` | `:529`·`:532-533` | **E10** |
| CORS 허용 origin·메서드·요청 헤더 | `x-cors.allow_origins` · `.allow_methods` · `.allow_headers` | `:196`·`:197-199`·`:200`·`:201` | **E11** |
| 빈 이름 422 문구 | `paths./workspaces.post.responses.422…examples.empty.value.detail` | `:1312` | **E7** |

접근자는 키가 없으면 **던진다**(`contract.ts` 의 `at()`). 기본값으로 대체하면 계약이 구조를
바꿨을 때 테스트가 조용히 옛 값으로 통과한다.

### 2-2. 케이스별 대응 (계획 §3-2 표 그대로)

| ID | 시나리오 | 화면 판정 | 네트워크 판정 | 계약 근거(키 경로 / 행) |
|---|---|---|---|---|
| **E1** | 가입 → 자동 로그인 → 홈 | 메뉴 항목 1개·그것이 선택됨. **이름이 서버 목록과 같은지 교차 확인** | 요청 4건이 이 순서: signup 201 → login 200 → me 200 → workspaces 200 | `paths./auth/signup`(`:787`)·`/auth/login`(`:827`)·`/auth/me`(`:862`)·`/workspaces`(`:1254`), 정렬 `:1261-1262` |
| **E2** | 로그인 자격증명 실패 (2단계) | 로그인 화면 유지 · `role="alert"` 문구가 **서버 `detail` 과 같다** | ① 빈 저장소가 그대로 빈다 ② **심어 둔 토큰이 그대로 남는다** · 401 두 건 | `components/responses/Unauthorized`(`:1494-1511`) / `client.ts:128` 의 `token !== null` 거짓 갈래 |
| **E3** | 세션 만료 (2갈래) | A 기동: `/` 진입 → 로그인 화면 · B **사용 중**: 홈에서 생성 시도 → 로그인 화면 | A `/auth/me` 401 → 토큰 삭제 · B `POST /workspaces` 401 → 토큰 삭제 | 같은 401 정의, **반대 갈래.** `AuthProvider.tsx:30-36`·`client.ts:126-131` |
| **E4** | 계정 A·B 소유자 범위 | B 의 메뉴에 A 의 이름이 없다 | 두 목록의 `id` 교집합이 공집합 | `paths./workspaces.get`(`:1255-1263`) |
| **E5** | 작업 공간 생성 | 항목 +1 · 선택이 새 공간 | 201 → 이어서 목록 200 (마지막 두 건) | `paths./workspaces.post`(`:1276-1299`) / `WorkspaceProvider.tsx:91-95` |
| **E6** | 같은 이름 생성 | `role="alert"` 문구가 **비어 있지 않다** + 서버 `detail` 과 같다. **문자열 단언 금지** | 409 · 목록 개수 불변 | `:1301-1305` (예시 없음 — RD-6) |
| **E7** | 빈 이름 / 공백만 이름 | 같은 문단에 **계약 문구**가 그대로 | 422 · `detail` 이 **문자열**(배열 아님) | `:1306-1313` / 모양 규칙 `components/responses/ValidationFailed`(`:1534-1566`) |
| **E8** | 이름 변경 | 항목 이름이 바뀐다 | **PATCH** 200 → 목록 200 (PUT 아님) | `paths./workspaces/{workspace_id}.patch`(`:1322-1350`) |
| **E9** | X-A3 — 만료 토큰 + 빈 이름 | 422 문구가 아니라 **로그인 화면** | **401**(422 아님) | `info.description` 인증 우선순위 절 |
| **E10** | 사적 헤더 브라우저 도달 | — | **모든** API 응답(프리플라이트 포함)에 두 헤더가 **각각 1개** | `x-global-response-headers`(`:529-533`) |
| **E11** | CORS 프리플라이트(PATCH 유발) | — | `OPTIONS` 200 · 허용 origin·메서드가 계약과 같다 · 요청 헤더는 계약의 부분집합 | `x-cors`(`:196-201`) |
| **E12** | 미인증 보호 화면 직접 진입 | 로그인 화면 | **보호 API 요청이 0건**(응답이 아니라 **요청**을 센다) | `RequireAuth.tsx:25-28` — 서버 무관 스모크 |

### 2-3. E2E **대상이 아닌** 것 (계획 §1-3 그대로 — 여기서 만들지 않았다)

| 항목 | 어디 |
|---|---|
| `DELETE /workspaces/{id}` 409 두 갈래·마지막 하나 | 계약 테스트 WD-4·WD-5. 계약이 「호출 래퍼를 만들지 말라」고 **적어 둔** 자리다(`:1377-1380`) |
| `PATCH`·`DELETE` 타인 자원 404 | 계약 테스트 WR-3·WD-2. UI 에서 도달 불가 |
| 정규화 후 길이 판정(제어문자 이름) | 계약 테스트. `window.prompt` 로 제어문자를 넣는 것은 사용자 경로가 아니다 |
| 이메일 형식 규칙 | **OQ-E2 사용자 판단 대기** — 건드리지 않았다 |

---

## 3. 실행 경로

### 3-1. `ci:e2e` — 신설 잡 (리더 판정 OQ-E1)

`.github/workflows/ci.yml` 의 `jobs.e2e`. 기존 네 잡은 **한 줄도 바뀌지 않았다**.

```
postgres 서비스 컨테이너(pgvector/pgvector:pg16, DB easydoc_kotlin)
 → setup-java 21 + setup-gradle → ./gradlew :api:bootJar --no-daemon
 → setup-node 20 + npm ci + npx playwright install --with-deps chromium
 → JWT 서명 키 생성(openssl rand -hex 32 → $GITHUB_ENV, 값 미출력)
 → java -jar … --spring.profiles.active=migrate
 → java -jar … --spring.profiles.active=api (백그라운드) + /health 대기 60초
 → npx playwright test    (Vite dev 서버는 Playwright webServer 가 띄운다)
 → 아티팩트 업로드(if: always()) — playwright-report · test-results · kotlin-api.log
```

**계획 §3-4 (b)의 compose 안을 채택하지 않았다.** compose 는 러너 안에서 이미지를 빌드해
Gradle 캐시가 먹지 않고(빌드 컨텍스트가 따로다) 실패 로그가 컨테이너에 갇힌다. 서비스
컨테이너 + `bootJar` 는 `kotlin` 잡이 이미 쓰는 조합이고, 로컬 러너가 **같은 절차**를
재현한다. (b)가 지키려던 것 — 「`frontend` 잡을 키우지 않는다」 — 는 그대로 지켰다.

**계약 기본값을 덮어쓰지 않는다.** `easydoc.cors-origins` 기본값(`http://localhost:5173`)을
CI 에서 바꾸지 않았다 — 바꾸면 계약 `x-cors.allow_origins` 가 정한 값이 한 번도 실행되지 않는다.

### 3-2. `local:frontend/e2e/run-local.sh`

```bash
frontend/e2e/run-local.sh                 # 전체 (bootJar 빌드 포함)
E2E_SKIP_BUILD=1 frontend/e2e/run-local.sh        # JAR 재사용
E2E_SKIP_STACK=1 E2E_API_BASE_URL=http://localhost:8100 \
  frontend/e2e/run-local.sh --grep "E11"          # 이미 띄운 스택(compose 포함)에 붙인다
```

**게이트 러너 규약 준수** — `set -euo pipefail`, 파이프로 종료 코드를 삼키지 않고
(`status=0; (…) || status=$?; exit "$status"`), `trap` 이 실패 경로에서도 컨테이너·API 를 정리한다.

### 3-3. 로컬 실측 — 서버 기동 명령·포트·env

| 항목 | 값 |
|---|---|
| PostgreSQL | `docker run pgvector/pgvector:pg16` · `127.0.0.1:55432 → 5432` · DB `easydoc_kotlin` · 컨테이너 `easydoc-e2e-pg` (일회용, `docker rm -f` 로 정리) |
| 마이그레이션 | `java -jar backend-kotlin/api/build/libs/easy-doc-api.jar --spring.profiles.active=migrate` → Flyway `applied=2 targetSchemaVersion=2` |
| API | 같은 JAR `--spring.profiles.active=api` · `SERVER_PORT=8100` |
| API env | `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/easydoc_kotlin` · `SPRING_DATASOURCE_USERNAME/PASSWORD=postgres` · `SERVER_PORT=8100` · `EASYDOC_AUTH_JWT_SECRET=<openssl rand -hex 32>` |
| 프런트 | Vite dev `http://localhost:5173` (`--strictPort`), `VITE_API_BASE_URL=http://localhost:8100` |
| 브라우저 | Chromium (Playwright chromium-headless-shell v1234) |

**필수 환경변수는 `EASYDOC_AUTH_JWT_SECRET` 하나다**(실측). `easydoc.auth.*` 의 소유자는
`api` 가 아니라 `infrastructure/auth/AuthConfiguration.kt` 의 `AuthProperties` 이고,
`EasyDocProperties`(`easydoc.cors-origins`·`easydoc.crypto.fernet-key`)는 기본값으로 충분하다 —
Phase 3 범위 엔드포인트가 Fernet 키를 쓰지 않는다. Spring 완화 바인딩이
`EASYDOC_AUTH_JWT_SECRET` → `easydoc.auth.jwt-secret` 을 실제로 잡는 것을 실행으로 확인했다
(가입·로그인·`/auth/me` 가 전부 통과 — 키가 안 잡히면 계약대로 503 이 난다).

### 3-4. 실측 결과

```
[e2e] PostgreSQL 컨테이너 기동 (pgvector/pgvector:pg16, 127.0.0.1:55432)
[e2e] PostgreSQL 준비 완료
[e2e] Kotlin API bootJar 빌드
[e2e] Flyway 마이그레이션 (profile=migrate)
[e2e] Kotlin API 기동 (profile=api, http://localhost:8100)
[e2e] Kotlin API 준비 완료
[e2e] Playwright 실행
  ✓  1 E1 가입 → 자동 로그인 → 홈. 머리말에 기본 작업 공간 1건
  ✓  2 E2 로그인 자격증명 실패 — 세션 저장소를 건드리지 않는다
  ✓  3 E3 세션 만료 — 기동 갈래와 사용 중 갈래 둘 다 로그인 화면으로 간다
  ✓  4 E12 미인증으로 보호 화면에 직접 들어가면 보호 API 호출이 아예 나가지 않는다
  ✓  5 E10 사적 응답 헤더가 프리플라이트까지 포함해 브라우저에 도달한다 (각각 1개씩)
  ✓  6 E11 PATCH 가 프리플라이트를 유발하고, 응답이 계약 CORS 정책과 같다
  ✓  7 E4 계정마다 자기 작업 공간만 본다
  ✓  8 E5 작업 공간을 만들면 새 공간이 선택된다
  ✓  9 E6 같은 이름으로 만들면 409 가 오고 문구가 화면에 남는다
  ✓ 10 E7 빈 이름·공백만 이름은 422 이고 `detail` 이 문자열이다
  ✓ 11 E8 이름을 바꾸면 메뉴 항목이 바뀐다
  ✓ 12 E9 X-A3 — 만료 토큰 + 빈 이름이면 422 가 아니라 401 이다
  12 passed
[e2e] 통과                                                   # 종료 코드 0
```

---

## 4. 음성 대조 — 실측

**대조군 먼저.** 일회용 worktree(`git worktree add --detach`)에서 변이 없이 12/12 통과를
확인한 뒤 변이를 넣었다. 변이 복원은 **`git checkout --` + sha256 대조**로 했다(규칙 5 —
`cp` 를 쓰지 않는다). worktree 는 `git worktree remove --force` 로 폐기했고 본 저장소의
추적 파일 변경은 0건이다.

| # | 변이 | 어디 | 계획의 예측 | **실측** | 판정 |
|---|---|---|---|---|---|
| **M1** | `client.ts:128` 의 `&& token !== null` 삭제 | **React** | E2 만 | **E2 만 빨강** (11 통과). 실패 지점 `auth.spec.ts:116` — 심어 둔 토큰이 `null` 이 됐다 | 예측대로 |
| **M2** | `AuthProvider.tsx:30-36` 401 핸들러 등록 삭제 | **React** | E3 만 | **E3·E9 빨강** (10 통과). E3 은 **갈래 B**(`auth.spec.ts:172`)에서만 깨졌다 — **갈래 A(기동 `/auth/me`)는 통과했다** | 계획보다 **넓다**(§5-2) |
| **M3a** | 계약 `x-global-response-headers.headers.Cache-Control` → `no-cache` | **계약 파일** | E10 | **E10 만 빨강** (11 통과) | 예측대로 |
| **M3b** | 계약 `x-cors.allow_origins[0]` → `http://localhost:5199` | **계약 파일** | E11 | **E11 만 빨강** (11 통과) | 예측대로 |
| **M3c** | 계약 422 `examples.empty.value.detail` 문구 변경 | **계약 파일** | E7 | **E7 만 빨강** (11 통과) | 예측대로 |
| **M4** | 서버 허용 origin 에서 dev 서버를 뺀다 (`EASYDOC_CORSORIGINS_0=http://localhost:9999`, Kotlin 파일 무수정) | **Kotlin 설정** | E11 + 그 뒤 전부 | **E1·E11 둘 다 빨강.** 서버 프리플라이트가 `Origin: 5173` 에 **403**, `Origin: 9999` 에 200 | 예측대로 |

### 이 배치의 핵심 증거

계획 §3-6 이 「**없다 — 어느 층도 잡지 못한다**」로 표시한 자리 셋(M1·M4, 그리고 M2 의
사용 중 갈래)에서 **이 E2E 가 실제로 빨강을 냈다.** 특히:

- **M1** 은 서버 계약 테스트로 **원리상 못 잰다** — 서버는 두 401 에 같은 것을 낸다.
  현행 React 단위 테스트는 `fetch` 를 스텁하므로 서버가 무엇을 내는지와 무관하게 통과한다.
- **M4** 는 서버 테스트가 `Origin` 을 보지 않아 못 잡는다. 이 잡이 **첫 관측자**다.

「전부 빨강」은 M4 하나뿐이고 그것은 설계상 옳다(프리플라이트가 막히면 요청이 안 나간다).
나머지 다섯은 **정확히 겨냥한 케이스만** 빨갛다 — 케이스가 서로 결속돼 있지 않다.

---

## 5. 계획을 정정한 것 — 3건

### 5-1. §3-2 주의 — `Cache-Control` 은 브라우저 JS 로 **읽힌다**

계획은 *"`Cache-Control` 은 CORS `expose_headers` 에 없다(`:201`) — 브라우저 JS 의
`response.headers.get()` 으로는 교차 출처에서 읽히지 않는다"* 고 적었다. **사실이 아니다** —
Fetch 표준의 **CORS-safelisted response-header** 목록(`Cache-Control`·`Content-Language`·
`Content-Length`·`Content-Type`·`Expires`·`Last-Modified`·`Pragma`)에 들어 있어 노출 목록과
무관하게 읽힌다. 못 읽는 것은 `X-Content-Type-Options` 쪽이다.

**결론은 그대로 옳다**(응답 관측으로 재라). 정정한 것은 근거다 — 근거가 반만 맞으면 다음
사람이 「그럼 페이지에서 읽으면 되겠네」로 간다. E10 이 이 사실을 **같은 실행에서 단언**한다:
페이지 스크립트로 요구 헤더를 읽어 보고, safelist 밖이면서 읽히지 않는 헤더가 **적어도 하나**
있어야 한다(없으면 이 케이스의 관측 방식 근거가 사라진다).

계약 `x-cors.expose_headers` 를 넓히지 않는다는 계획의 결론도 유지한다.

### 5-2. §3-2 E3 설계 — 기동 갈래만으로는 핸들러 변이를 못 잡는다

계획의 E3 은 *"유효하지 않은 토큰을 저장소에 심고 진입 → `/auth/me` 401 → 저장된 토큰이
지워진다"* 다. **그 갈래만으로는 M2(핸들러 등록 삭제)를 잡지 못한다** — `AuthProvider` 의
`fetchMe().catch` 가 **스스로** `setStatus('anonymous')` 를 하므로 핸들러가 없어도 화면이
로그인으로 간다. 실측으로 확인했다: M2 에서 갈래 A 는 통과하고 갈래 B 에서 빨강이 났다.

그래서 E3 에 **갈래 B(사용 중 만료)** 를 더했다 — 정상 로그인 뒤 저장 토큰을 무효 값으로
바꾸고 UI 에서 작업 공간 생성을 시도한다. 401 핸들러가 **유일한 통로**인 자리는 여기다.
케이스 수는 12건 그대로다(E3 이 두 갈래를 갖는다).

같은 이유로 E2 도 두 단계다 — 저장소가 빈 상태(계획이 적은 것)와 **토큰이 남아 있는 상태**.
후자가 `token !== null` 분기를 실제로 가르는 자리다. 「토큰이 있는데 화면은 로그인 화면」은
실재하는 상태다: 기동 시 `/auth/me` 가 **연결 실패**로 끝나면 `anonymous` 로 내려가지만
그 실패는 401 이 아니라 토큰이 지워지지 않는다.

### 5-3. §3-4 `local:` — compose 대신 CI 절차 재현 스크립트

계획은 `local:` 을 compose 경로로 정본화했다. 리더가 OQ-E1 을 **CI 잡 신설**로 판정한 뒤에는
정본이 갈리면 안 된다 — 로컬과 CI 가 다른 절차를 밟으면 「로컬에서는 되는데」가 **구조적으로**
생긴다. `run-local.sh` 가 CI 절차를 그대로 따라가고, compose 경로는 `E2E_SKIP_STACK=1` 로
여전히 붙일 수 있게 남겼다.

---

## 6. 실측으로 드러난 것 — 2건 (계획에 없던 자리)

### 6-1. 프리플라이트는 `page.on('response')` 에 **한 건도** 안 나온다

Playwright 의 페이지 수준 이벤트는 CORS 프리플라이트를 보고하지 않는다(실측: PATCH 흐름에서
`preflights()` 0건). 프리플라이트는 페이지가 아니라 네트워크 스택이 보내기 때문이다.

계약 `x-global-response-headers.applies_to` 가 **프리플라이트를 명시적으로 범위에 넣었으므로**,
그 관측으로는 **선언한 범위보다 좁은** 검사가 된다. Chrome DevTools Protocol 의 `Network.*` 로
갈아탔다 — 프리플라이트를 `type: "Preflight"` 로 분리 보고하고
`Network.responseReceivedExtraInfo` 가 원시 헤더까지 준다(`CdpNetworkLog`).

지금 E10 은 프리플라이트 응답에도 두 헤더가 **각각 1개씩** 실리는 것을 확인하며, 「프리플라이트를
한 건도 관측하지 못했다」를 **실패 사유로** 단언한다 — 관측 통로가 조용히 좁아지는 것을 막는다.

두 통로를 하나로 합치지 않았다: 흐름 순서 단언(E1·E5·E8)은 페이지 이벤트로 이미 정확하게
돌고 있어 바꿀 근거가 없다.

### 6-2. Vitest 기본 `include` 가 `e2e/*.spec.ts` 를 수집한다

`**/*.spec.ts` 가 기본값이라 그대로 두면 E2E 파일 3개가 jsdom 에서 통째로 깨진다(실측:
`10 passed | 3 failed`). 더 나쁜 쪽은 그 반대 방향이다 — 같은 명령에 묶이면 `frontend` CI 잡이
Kotlin 서버와 Postgres 를 요구하게 되어 **계획 §3-4 (a)를 뒷문으로 채택**하는 것이 된다
(계획 §4-3 이 경고한 자리). `vite.config.ts` 의 `test.exclude` 에 이 디렉터리만 더했다.
기본 `include` 를 `src/**` 로 좁히지 않은 이유: 좁히면 `src` 밖에 생기는 새 테스트가 조용히
수집에서 빠진다.

---

## 7. 드리프트 6건(계획 §2) — 건드린 것 / 안 건드린 것

| ID | 자리 | 이 배치의 처분 |
|---|---|---|
| **RD-1** | `types.ts:20-23` `CredentialsRequest` 하나로 가입·로그인 겸용 (계약은 두 스키마) | **안 건드림** — Phase 6 타입 교체 회차 입력. 타입 부채는 사용자 판단·Phase 6 몫이다 |
| **RD-2** | `types.ts:34` `token_type: string` (계약은 고정값) | **안 건드림** — 같은 이유 |
| **RD-3** | `types.ts:94` `source_format: string` (계약은 enum) | **안 건드림** — Phase 4 범위 |
| **RD-4** | `validation.ts:13` 이메일 정규식 ↔ Kotlin `CredentialRules` ASCII 한정, 계약 침묵 | **안 건드림** — **OQ-E2 사용자 판단 대기**. E2E 도 이메일 형식 케이스를 만들지 않았다 |
| **RD-5** | `validation.ts:10`·`:16` 길이 상수 = 계약 `fields[].limit` 의 수기 사본 | **안 건드림** — Phase 6 타입 생성이 해소하지 못하는 영구 수기 자리. 기록만 이월 |
| **RD-6** | 409 `detail` 이 계약에 없는데 화면 문구다 | **부분적으로 닿았다 — 제한을 실행으로 고정했다.** E6 이 **문자열을 단언하지 않고** ⑴ 비어 있지 않다 ⑵ 서버가 준 것과 같다, 둘만 잰다. 코드 주석과 이 표가 그 제한의 사유를 남긴다. 침묵 유지 판정(D-1)은 **뒤집지 않았다** |

**타입 부채(RD-1·2·3·5)와 계약 침묵(RD-4·6)의 해소는 이 배치가 손대지 않는다** — Phase 6
및 사용자 판단 몫이다. 이 배치가 한 것은 RD-6 에 대해 **E2E 단언 강도의 상한을 코드로 못박은 것**뿐이다.

---

## 8. 개인정보·보안

| 항목 | 상태 |
|---|---|
| 테스트 계정 | 전부 합성 — `e2e-<crypto.randomUUID()>@example.test`(RFC 6761 예약 도메인, 배달되지 않는다) · 비밀번호 `e2e-synthetic-password` |
| 작업 공간 이름 | `E2E-<uuid8>` · `A-<uuid8>` · `R-<uuid8>` — 실재 기관·문서 이름 0건 |
| 만료/무효 토큰 픽스처 | **운영 비밀키로 서명하지 않는다.** 서명 없는 문자열 `e2e.invalid.token` 하나 — 계약이 헤더 누락·위조·만료·용도 불일치를 **같은 401** 로 못박았으므로 충분하다(계획 §3-5 privacy-gate 주의 항목) |
| CI 비밀 | `openssl rand -hex 32` 로 **실행마다 새로** 만들어 `$GITHUB_ENV` 로만 넘긴다. 저장소에 고정 값 0건, 로그 출력 0건 |
| 아티팩트 | `playwright-report`·`test-results`(추적·스크린샷·`kotlin-api.log`). 화면 스냅샷에 실릴 수 있는 값이 전부 위 합성값이고, 서버 로그는 문서 본문·개인정보를 애초에 남기지 않는다 |
| `.gitignore`/`.prettierignore` | `playwright-report`·`test-results`·`blob-report` 추가(실행 산출물). `.omc` 는 저장소 루트 `.gitignore:21` 과 **같은 범위**를 prettier 쪽에 다시 적은 것이다 — 넓히지 않았다 |

---

## 9. 검증 결과 (각 단독 실행, exit code)

| 명령 | exit | 비고 |
|---|---|---|
| `frontend: npm run check` (`tsc -b` + eslint + prettier) | **0** | e2e 를 `tsconfig.e2e.json` 프로젝트로 타입 검사에 포함 |
| `frontend: npm run test -- --run` | **0** | 10파일 60건 — **변경 전과 같다**(계획 §4-3 요구) |
| `frontend: npm run build` | **0** | e2e 는 `tsconfig.app.json` `include` 밖이라 번들에 안 들어간다 |
| `frontend/e2e/run-local.sh` | **0** | 12/12 통과 |
| `uv run ruff check .` | **0** | |
| `uv run ruff format --check .` | **0** | 153 files |
| `uv run mypy . .claude` | **0** | 137 source files |
| `uv run pytest tests/test_harness_scope_reach.py` | **0** | 37건 — `read_ci_job_names` 가 새 잡 `e2e` 를 읽는다 |

---

## 10. 미결 · 통보

| ID | 항목 | 누구 |
|---|---|---|
| **OQ-E2** | 이메일 형식 규칙 계약 게시 여부(RD-4) — 게이트 20 ⑥ ASCII 정책 판정의 종속물 | **사용자 → 리더** (이 배치 무접촉) |
| **OQ-E5** | `docker-compose.yml` 의 `kotlin-api` healthcheck **주석**이 `/health` 옛 형태를 적고 있다(동작 영향 0) | **`kotlin-implementer`** — 계획 §5 그대로 이월 |
| **N-1** | **원장 갱신은 이 배치가 하지 않는다**(`00_progress.md` 무접촉). Phase 3 표 행 5 의 `실행 경로` 를 `안 돎` → `ci:e2e · local:frontend/e2e/run-local.sh` 로 옮기는 것은 **리더 판정**이다. 표기 검사기(`tests/test_harness_scope_reach.py`)는 새 잡 `e2e` 를 이미 실재 잡으로 읽는다(실측 37건 통과) | **리더** |
| **N-2** | CI 에서 이 잡이 **실제로 초록/빨강을 내는 것은 아직 관측되지 않았다** — 로컬 실측만 있다. 첫 CI 실행 결과가 이 잡의 도달을 확정한다 | **리더** (다음 푸시) |
| **N-3** | 계획 §3-2 주의의 `Cache-Control` 서술이 사실과 다르다(§5-1). 계획 문서는 **contract-keeper 소유**라 이 배치가 고치지 않았다 | **`contract-keeper`** |
| **N-4** | 계획 §3-6 표의 M2 행 예측(「E3 만」)이 실측(E3·E9)과 다르고, E3 의 설계가 그 변이를 못 잡는다(§5-2). 같은 이유로 통보한다 | **`contract-keeper`** |
| **N-5** | Kotlin 변경 요구 **0건**. Phase 3 범위 엔드포인트가 계약대로 응답하는 것을 브라우저 경로에서 확인했다 | **`kotlin-implementer`** — 정보 |

---

## 11. 이 배치가 만든 것 / 만들지 않은 것

**만든 것**: Playwright 하네스 1식(chromium), 케이스 12건, 계약 파서(읽는 값 3), 네트워크 관측
두 통로, 로컬 러너, CI 잡 `e2e`, 음성 대조 6건 실측, 계획 정정 3건.

**만들지 않은 것**: 계약 파일 수정, Kotlin 수정, React 제품 코드 수정, `00_progress.md` 갱신,
삭제·타인 자원·이메일 형식 케이스, Phase 6 항목(생성 타입·a11y·nginx 프록시·전체 흐름 E2E).

**이 하네스가 막지 못하는 것**: 잡 `e2e` 자체의 삭제, `frontend/e2e/` 통째 삭제.
저장소 안의 어떤 파일도 자기 자신에 대한 절대 기준이 될 수 없다 — 최종 방어선은 그 diff 가
리뷰에 올라가는 것이다. (`quality` 잡의 「경로 명시」 규약을 이 잡에 옮겨 붙일지는 리더 판정
대상으로 남긴다 — `npx playwright test` 는 스펙 파일이 0건이면 **실패**하므로 `frontend/e2e/`
전체 삭제는 이미 빨강이지만, 잡 스텝 자체를 지우는 것은 여전히 조용하다.)
