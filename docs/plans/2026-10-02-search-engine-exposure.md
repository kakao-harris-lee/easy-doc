# 검색 엔진 노출 준비 — 기본 페이지(`/`) 하나만 색인

작성일: 2026-10-02 · 상태: **PR #176 머지(`753f7920`)·파일럿 반영·호스트 nginx 적용 완료(2026-10-02).** 남은 운영자 단계(§7)는 서치콘솔 DNS TXT·네이버 서치어드바이저 토큰·배포 뒤 색인 요청. 적용 시 확인한 사실: `www.easydoc.kr`은 공개 DNS A 레코드가 없고 인증서 SAN도 `easydoc.kr`뿐이라, www 301은 HTTP 80에서만 실효가 있다. 이후 www A 레코드는 추가됐으나 인증서는 그대로다. **사용자 결정(2026-10-02): www용 인증서를 추가하지 않고 현 301 구성을 유지한다.** `http://www`는 301로 apex에 도달하고, `https://www` 직접 링크만 TLS 경고를 본다. 구글봇은 인증서 오류 주소를 색인하지 않고 랜딩 canonical이 apex라 SEO 영향은 없다. 필요해지면 www 블록에만 Let's Encrypt 무료 인증서를 붙이는 것이 다음 선택지다. 사용자 결정(2026-10-02): 대상 엔진은 **구글 + 네이버**, 범위는 **기술 준비만**(새 콘텐츠 없음), 노출 대상은 **기본 페이지 `/` 하나**. SPA 한계가 분명하므로 다른 페이지는 노출하지 않으며, 필요한 정보는 기본 페이지에 있다.

## 1. 현재 상태 (코드 확인, 2026-10-02)

| 항목 | 현재 | 근거 |
|---|---|---|
| 정적 메타 | title·description·OG·Twitter·canonical·`lang="ko"` 있음 | `frontend/index.html` |
| canonical | 모든 경로가 `https://easydoc.kr/`를 정본으로 선언 | `frontend/index.html:12` |
| robots.txt / sitemap.xml | 없음 | `frontend/public/` |
| 라우트별 title/meta | 없음(헬멧류 미사용, `document.title` 변경 없음) | `frontend/src` grep |
| 구조화 데이터(JSON-LD) | 없음 | `frontend/index.html` |
| 소유 확인 태그(서치콘솔·서치어드바이저) | 없음 | `frontend/index.html` |
| OG 이미지 | 320×320 아이콘(`/icons/icon-320.png`) | `frontend/index.html:28-31` |
| 렌더링 | 전부 클라이언트 렌더링. JS 없이 보면 `<div id="root">`만 있음 | `frontend/src/main.tsx` |
| `/` 화면 | 로그인 전 `LandingPage`, 로그인 후 `UploadPage`를 한 주소에서 분기 | `frontend/src/pages/HomePage.tsx` |
| 서빙 | 호스트 nginx(443, `/etc/nginx/sites-available/easydoc_kr`) → 컨테이너 nginx(`127.0.0.1:3100`) → `dist/` 정적 파일. 모든 경로를 `index.html` 200으로 응답(soft 404) | `frontend/nginx.conf:35-43`, `compose.pilot.yml:55-60` |
| 호스트명 | `easydoc.kr`·`www.easydoc.kr` 둘 다 리다이렉트 없이 서빙 | 호스트 nginx `server_name` |
| Docker 빌드 | `node:22-alpine`에서 `npm run build`. 브라우저 없음 | `frontend/Dockerfile:1-17` |
| 정체성 문장 | `SERVICE_DEFINITION` 한 곳에서 관리. `index.html`의 description은 같은 문장을 손으로 복사 | `frontend/src/content/identity.ts` |

## 2. 목표와 비목표

**목표**
- 구글봇·네이버 Yeti가 **JS 실행 없이** `/`의 제목·정의 문장·입력 예시를 HTML에서 읽을 수 있다.
- `/`만 색인되고 그 외 경로는 크롤·색인 대상에서 명시적으로 빠진다.
- 서치콘솔·서치어드바이저에 사이트를 등록해 `/`의 색인 상태를 확인할 수 있다.
- `www.easydoc.kr`은 `easydoc.kr`로 301 된다.

**비목표(이번 범위 밖)**
- `/guide`·`/terms`·`/privacy` 등 다른 공개 페이지의 색인·프리렌더링.
- 새 설명 페이지·키워드 콘텐츠 작성, 가이드 분할.
- 모든 라우트의 soft 404 수정(색인 대상이 `/` 하나라 색인 품질에 영향이 없다. §8 후속).
- 분석 도구(GA 등) 도입.

## 3. 설계 결정

### 3.1 `/`는 빌드 시 프리렌더링한 `landing.html`, 나머지는 기존 SPA 셸

- `vite build` 뒤에 **Node 전용 SSR 빌드**로 `LandingPage`를 `renderToStaticMarkup`하여 `dist/index.html`(셸)을 템플릿으로 `dist/landing.html`을 만든다. 브라우저(Playwright)를 쓰지 않으므로 `node:22-alpine` 빌드 스테이지에서 그대로 동작한다.
- 컨테이너 nginx의 `location = /`를 `try_files /landing.html /index.html =404`로 바꾼다(`frontend/nginx.conf:35-38`). 그 외 경로는 지금처럼 `index.html` 셸.
- 프리렌더가 빠진 이미지로도 홈이 죽지 않도록 `/`는 `landing.html` → `index.html` 순으로 떨어진다(홈 404보다 색인되지 않는 셸이 낫다). `/landing.html` 직접 접근은 `/`의 중복 주소이므로 그 location에 `X-Robots-Tag: noindex`를 붙인다.
- 클라이언트 마운트는 `createRoot(...).render` 그대로 둔다(`frontend/src/main.tsx`). React가 프리렌더 내용을 교체하므로 hydration 불일치 문제가 없다. 대신 **로그인한 사용자가 `/`에 들어오면 JS 로드 전 랜딩 내용이 잠깐 보인다** — 지금은 빈 화면이 보이는 구간이라 수용한다(§6 위험 1).
- `LandingPage`만 렌더하고 `App` 셸(헤더·푸터·`AuthProvider`)은 렌더하지 않는다. 셸은 인증 상태 fetch에 묶여 있어 SSR 대상이 아니다. `Link`는 `StaticRouter`로 감싼다(react-router v7의 import 경로는 구현 시 확인).
- 대안으로 검토한 것: ⑴ Playwright 스냅샷 — alpine에 Chromium이 없어 빌드 이미지가 커진다. ⑵ `index.html`에 정적 랜딩 본문을 직접 적기 — 모든 딥링크에서 랜딩이 번쩍이고 `LandingPage.tsx`와 문구가 갈라진다. ⑶ `noscript` 본문 — 네이버 Yeti가 JS를 일부 실행하므로 효과가 불확실하고 ⑵와 같은 drift가 생긴다. 모두 기각.

### 3.2 색인 범위는 "셸 = noindex, 랜딩 = index"로 고정

- `frontend/index.html`(셸)에 `<meta name="robots" content="noindex">`를 넣고 **canonical을 뺀다**(noindex 페이지가 `/`를 canonical로 가리키는 모순 제거). OG·Twitter 태그는 딥링크 공유 미리보기용으로 남긴다.
- 프리렌더 스크립트가 `landing.html`을 만들 때 `noindex` 메타를 제거하고 `<link rel="canonical" href="https://easydoc.kr/">`와 `<meta name="robots" content="index,follow">`를 넣는다. 런타임 코드로 메타를 바꾸지 않는다.
- `frontend/public/robots.txt`: `User-agent: *` 아래 `Allow: /`를 두고 `Disallow`는 `/api/` 하나만 둔다. 마지막 줄에 `Sitemap: https://easydoc.kr/sitemap.xml`. **색인 제어는 robots가 아니라 셸의 `noindex` 메타가 맡는다** — 크롤을 막은 경로는 크롤러가 `noindex`를 읽을 수 없고, 그 주소가 다른 페이지에서 링크돼 있으면(`/guide`·`/signup`은 랜딩 본문에서 링크된다) 본문 없는 항목으로 색인될 수 있다. robots로 막을 것은 색인 의도가 없는 백엔드 API 크롤뿐이다. `$`·`*` 와일드카드에도 의존하지 않는다(네이버 지원 여부가 불확실).
- `sitemap.xml`은 `public/`에 두지 않는다 — 프리렌더 스크립트가 빌드 때 `dist/sitemap.xml`로 직접 쓴다. 내용은 `<loc>https://easydoc.kr/</loc>` 하나이고 `lastmod`는 빌드 날짜다(정적 파일에 손으로 박지 않는다).

### 3.3 메타·구조화 데이터는 한 출처에서

- 프리렌더 스크립트가 `landing.html`의 `description`·`og:description`·`twitter:description`을 `SERVICE_DEFINITION`으로 채워 `identity.ts`와의 수동 복사를 없앤다. 셸의 description은 그대로 둔다(noindex라 영향 없음).
- JSON-LD는 `landing.html`에만 `Organization`(name·url·logo)과 `WebSite`(name·url·inLanguage `ko`) 두 개를 넣는다. 사업자명·주소는 `frontend/src/components/AppLayout.tsx` 푸터가 쓰는 값을 그대로 쓴다. `SoftwareApplication`·`FAQPage`는 근거 콘텐츠가 없어 넣지 않는다.
- OG 이미지: 1200×630 PNG `frontend/public/og/landing.png`를 추가하고 `landing.html`과 셸의 `og:image`·`twitter:card`(`summary_large_image`)를 바꾼다. 기존 `landing-document-flow.svg`와 로고를 소재로 designer가 만들고 바이너리를 커밋한다. 생성 도구는 저장소 의존성에 추가하지 않는다.

### 3.4 소유 확인과 호스트 리다이렉트는 운영자 단계

- 구글 서치콘솔: **도메인 속성 + DNS TXT** 권장(www·http 변형을 한 속성으로 묶는다). DNS 접근이 어려우면 `landing.html`·셸 `<head>`에 `google-site-verification` 메타를 넣는다.
- 네이버 서치어드바이저: `naver-site-verification` 메타를 `frontend/index.html` 템플릿에 넣는다(셸과 랜딩 모두에 실린다). 토큰은 비밀값이 아니라 템플릿에 직접 적는다. 등록 후 robots.txt 수집 확인 → 사이트맵 제출 → `/` 수집 요청.
- 호스트 nginx(저장소 밖, `/etc/nginx/sites-available/easydoc_kr`)의 443 server에 `if ($host = www.easydoc.kr) { return 301 https://easydoc.kr$request_uri; }`(또는 별도 server 블록). **운영 변경이라 사용자 승인 뒤 사용자가 직접 적용한다.**

## 4. 구현 조각 (Sonnet 실행 에이전트, 되돌릴 수 있는 수직 조각)

같은 파일을 만지는 조각은 직렬, 나머지는 병렬 가능. 모두 `feature/search-engine-exposure` 한 브랜치·한 PR로 모은다(관련 변경이 nginx·빌드·템플릿에 걸쳐 있어 분리하면 중간 상태가 깨진다).

| # | 조각 | 파일 | 선행 |
|---|---|---|---|
| A | **프리렌더 스파이크 → 구현.** `frontend/src/prerender/landing-entry.tsx`(`StaticRouter` + `LandingPage` → `renderToStaticMarkup`), `frontend/scripts/prerender-landing.mjs`(템플릿 읽기 → `#root` 안에 주입, `noindex` 제거, canonical·`index,follow`·description·JSON-LD 주입, `sitemap.xml`의 `lastmod` 채우기, `<h1>`과 `SERVICE_DEFINITION`이 결과에 없으면 **빌드 실패**). `package.json` `build`에 `vite build --ssr ... --outDir dist-ssr` + 스크립트 실행 추가(`dist-ssr`은 이미 `.gitignore`). 주입 함수는 순수 함수로 분리해 Vitest로 고정한다(TDD: 템플릿·마크업 → 결과 HTML에 canonical 1개, noindex 0개, description = `SERVICE_DEFINITION`). | `frontend/src/prerender/**`, `frontend/scripts/**`, `frontend/package.json`, `frontend/vite.config.ts`(필요 시) | — |
| B | **셸 메타 정리.** `frontend/index.html`에 `noindex` 메타 추가, canonical 제거, `og:image`를 `/og/landing.png` 1200×630으로, `twitter:card`를 `summary_large_image`로. 소유 확인 메타는 사용자가 토큰을 주면 같은 조각에서 추가. | `frontend/index.html` | — |
| C | **robots·sitemap.** `frontend/public/robots.txt`. `sitemap.xml`은 `public/`에 두지 않고 조각 A의 스크립트가 `dist/sitemap.xml`로 쓴다. | `frontend/public/**` | — |
| D | **nginx.** `location = /`를 `try_files /landing.html =404`로. `robots.txt`·`sitemap.xml`은 정적 파일로 그대로 서빙되는지 확인. | `frontend/nginx.conf` | A |
| E | **OG 이미지.** designer가 1200×630 PNG 제작·커밋. | `frontend/public/og/landing.png` | — |
| F | **e2e 1건.** Playwright `request.get('/')` 본문에 `<h1`·`SERVICE_DEFINITION`·`rel="canonical"`이 있고 `noindex`가 없음, `request.get('/guide')` 본문에 `noindex`가 있고 canonical이 없음. e2e가 nginx 컨테이너를 통하는 구성(`compose.e2e.yml`)에서만 의미가 있으므로 `playwright.config.ts`의 `FRONTEND_ORIGIN`이 nginx를 가리키는지 먼저 확인하고, Vite dev 서버를 쓰는 구성이면 이 조각을 compose 기반 수동 검증(§5)으로 대체하고 그 사실을 PR에 적는다. | `frontend/e2e/seo.spec.ts` | A, D |
| G | **문서.** `README`/운영 문서에 서치콘솔·서치어드바이저 등록 절차와 www 리다이렉트 적용 메모(writer). | `docs/**` | — |

구현 모델은 Sonnet 실행 에이전트, 리뷰는 별도 패스의 `code-reviewer`(sonnet). 결제·인증·마이그레이션을 건드리지 않으므로 Codex 심판 대상이 아니다.

## 5. 수용 기준 (모두 명령으로 확인 가능한 것만)

프런트 검증(PR 전):
1. `cd frontend && npm run check && npm run test -- --run && npm run build` 모두 0 종료. 빌드 산출물에 `dist/landing.html`·`dist/robots.txt`·`dist/sitemap.xml`이 있다.
2. `grep -c '<h1' frontend/dist/landing.html` ≥ 1, `grep -c '행정·복지·법률 안내문을' frontend/dist/landing.html` ≥ 1(본문과 description 두 곳 이상).
3. `grep -c 'rel="canonical"' frontend/dist/landing.html` = 1, `grep -c 'noindex' frontend/dist/landing.html` = 0, `grep -c 'noindex' frontend/dist/index.html` = 1, `grep -c 'rel="canonical"' frontend/dist/index.html` = 0.
4. `grep -c '<loc>https://easydoc.kr/</loc>' frontend/dist/sitemap.xml` = 1이고 `<lastmod>`가 `YYYY-MM-DD` 형식.
5. `docker compose -f compose.yml config`와 `docker compose -f compose.yml -f compose.ci.yml --profile ci config` 통과. `docker build --target check frontend`가 alpine에서 브라우저 없이 통과.
6. 로컬 compose(`compose.yml`)로 올린 nginx에서 `curl -s http://127.0.0.1:<port>/`가 `landing.html` 내용(`<h1` 포함), `curl -s .../guide`가 셸(noindex 포함), `curl -sI .../robots.txt`·`.../sitemap.xml`이 200.

파일럿 반영 뒤(사용자 승인·배포 후):
7. `curl -s https://easydoc.kr/ | grep -c '<h1'` ≥ 1 (JS 없이 본문이 보인다).
8. `curl -sI https://www.easydoc.kr/`가 301이고 `Location: https://easydoc.kr/`.
9. 구글 서치콘솔 URL 검사에서 `https://easydoc.kr/`가 "색인 생성 가능"(또는 "색인됨"), 렌더링된 HTML에 `<h1>` 포함. 리치 결과 테스트에서 JSON-LD 오류 0.
10. 네이버 서치어드바이저에서 사이트 소유 확인 완료, robots.txt 수집 "성공", 사이트맵 제출 상태 "정상", `/` 수집 요청 완료.

## 6. 위험과 완화

| 위험 | 완화 |
|---|---|
| 로그인 사용자가 `/`에서 랜딩이 잠깐 보임(JS 로드 전) | 지금도 빈 화면 구간이 있어 체감 차이는 "빈 화면→랜딩 한 컷". 수용. 불만이 오면 `landing.html`에 인증 쿠키 유무로 본문을 숨기는 인라인 스크립트를 후속으로 검토(§8). |
| SSR 빌드에서 `LandingPage` 의존(lucide·Badge·Link)이 Node에서 실패 | 조각 A를 스파이크로 먼저 돌려 30분 안에 렌더 성공을 확인한 뒤 나머지 조각을 시작. 실패 시 `LandingPage`를 순수 표현 컴포넌트로 좁히는 범위 안에서만 수정. |
| 프리렌더 결과가 비어도 빌드가 통과 | 스크립트가 `<h1>`·정의 문장 부재 시 non-zero 종료(조각 A). |
| 네이버가 `$`·와일드카드 robots 규칙을 다르게 해석 | 접두사 `Disallow` 목록만 사용. |
| 셸 `noindex` 때문에 딥링크 공유 미리보기가 사라진다는 오해 | OG는 유지하므로 카카오톡·슬랙 미리보기는 그대로. noindex는 검색 색인만 막는다. |
| 서치콘솔 DNS TXT 추가·호스트 nginx 변경은 저장소 밖 운영 변경 | 계획에 운영자 단계로 분리하고 사용자 승인·직접 적용으로 둔다. 적용 뒤 §5-8·9·10으로 확인. |
| 파일럿 재배포(`docker_startup.sh restart`)는 운영 변경 | 사용자 승인 뒤에만. PR 머지와 배포를 분리. |
| OG 이미지 제작이 늦어져 PR이 막힘 | 조각 E는 독립. 늦으면 `og:image`를 기존 320 아이콘으로 두고 머지, 이미지는 후속 PR. |

## 7. 운영자(사용자) 단계 체크리스트

- [ ] 구글 서치콘솔 도메인 속성 생성 → DNS TXT 등록(또는 메타 토큰 전달)
- [ ] 네이버 서치어드바이저 사이트 등록 → `naver-site-verification` 토큰 전달
- [ ] 호스트 nginx에 www→apex 301 적용 후 `nginx -t && systemctl reload nginx`
- [ ] PR 머지 뒤 파일럿 재배포 승인
- [ ] 배포 뒤 서치콘솔 URL 검사·색인 요청, 서치어드바이저 사이트맵 제출·수집 요청(§5-9·10)

## 8. 후속(이번 범위 밖, 기록만)

- 모든 라우트의 soft 404 수정(nginx가 알려진 라우트만 200, 그 외 `index.html`을 404 상태로).
- 로그인 사용자용 `/` 랜딩 플래시 억제.
- `/guide`·`/terms`·`/privacy` 노출이 필요해지면 조각 A의 엔트리에 라우트를 추가하는 방식으로 확장(스크립트는 라우트 목록을 받도록 설계).
- 검색 유입 측정(서치콘솔 실적 리포트로 시작, 별도 분석 도구는 필요 시).
- sitemap `lastmod`가 빌드 날짜라 랜딩 내용이 그대로여도 재배포마다 바뀐다 — 크롤러에 잘못된 변경 신호가 되면 랜딩 소스의 커밋 날짜로 바꾼다.
