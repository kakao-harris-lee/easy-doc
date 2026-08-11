# Phase 00 / scope `pre-phase0` — codex 독립 리뷰

> 이 문서는 `codex-reviewer`가 작성한다. **codex 출력은 무편집 원문으로 보존한다.**
> 판정·심각도 부여·종합은 `migration-reviewer`(교차 대조)와 오케스트레이터의 몫이다.

**최신 회차: 2회차 (2026-08-11 23:50 KST 시작).** 1회차는 산출물 없이 취소됐다(§1.1).

---

## 1. 호출 메타데이터

### 1.1 1회차 — 취소 (산출물 없음)

| 항목 | 값 |
|---|---|
| 회차 | 1회차 |
| 실행 시각(시작) | 2026-08-11 23:05 KST |
| scope | `working-tree` (base 미지정) |
| job id | `review-msoqg1fd-iuscrg` → 이후 `review-msormt2c-yorb2y` (failed, 9m 0s) |
| 결과 | **취소 — codex 출력 없음** |

취소 사유: `.claude/`와 `docs/migration/`이 당시 git 미추적이라 `--scope working-tree` diff에 추적 파일 5건(`CLAUDE.md`, `app/api/auth.py`, `app/api/documents.py`, `tests/api/test_auth.py`, `tests/api/test_documents.py`)만 포함됐다. 리뷰 대상의 본체인 하네스(스킬 스크립트·에이전트 정의)가 diff로 전달되지 않아 codex가 파일을 하나씩 직접 열어 탐색했고, 33분 경과 시점에 결과 없이 취소했다. **이 회차의 codex 출력은 존재하지 않는다.**

### 1.2 2회차 — 본 리뷰

| 항목 | 값 |
|---|---|
| 회차 | 2회차 |
| 실행 시각(시작) | 2026-08-11 23:50:56 KST |
| 대상 범위 | `main`...`HEAD` branch diff — 커밋 2건, 25 files / +6,386 −7 |
| 대상 커밋 | `0fafac7` fix: 개인정보·자격증명 응답에 캐시 금지 헤더 추가<br>`e88db3e` feat: Kotlin 마이그레이션 하네스 구성 (에이전트 6·스킬 6) |
| 브랜치 | `feat/kotlin-migration-harness` |
| 모드 | `adversarial-review` |
| scope | **`--base main` (branch 모드)** — `--base`를 주면 `--scope`는 무시된다 |
| 스킬 스크립트 | `.claude/skills/codex-review/scripts/codex-review.sh` |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 내림차순 자동 선택, v1.0.6) |
| job id | `review-msos2nv7-lqf2mh` |
| codex session id | `019ff14e-26da-7091-ba96-33c4538cd816` |
| job 로그 | `/Users/harris/.claude/plugins/data/codex-openai-codex/state/easy-doc-40cce15c488d0114/jobs/review-msos2nv7-lqf2mh.log` |

### 1회차 대비 변경점

두 커밋으로 하네스가 **전부 커밋됐다.** 따라서 `--base main` branch diff에 6,386줄 전체가 실려 codex가 전수 탐색 없이 변경분만 읽는다. 실제로 job 시작 38초 시점에 이미 `verifying` 페이즈에 진입해 `codex-review.sh`·`scan_privacy_invariants.py`·`app/api/*` diff를 읽고 있었다 — 1회차의 탐색 병목이 해소된 것으로 관측된다.

### 실행 명령 (스크립트 인자 그대로)

```bash
FOCUS="$(cat <focus.txt>)"
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base main --focus "$FOCUS"
```

`--dry-run`이 출력한 전개 명령:

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base main '<focus text 전문 — §2>'
```

### 제공한 맥락

- 저장소 성격(공공기관 문서 변환 SaaS, Python/FastAPI → Kotlin/Spring Boot 전환 예정)
- 리뷰 대상의 본질이 "게이트가 실제로 위반을 막는가"라는 점
- 저장소 불변식 5개(마스킹 선행 / `no-store`+`nosniff` / 소유권 은닉 404 / 로그 개인정보 금지 / Fernet·Argon2·JWT 호환)
- 적대적 축 5개(A: `compare_parity.py` 우회 경로와 증거 파일 신뢰성, B: `scan_privacy_invariants.py` 12개 규칙 실효성, C: `codex-review.sh` 조용한 통과, D: 캐시 헤더 누락 잔존·422 입력값 유출, E: 회귀 테스트 실효성)

### 독립성 보호 조치

`docs/migration/_workspace/reviews/00_pre-phase0_migration-reviewer.md`(Claude 측 1회차 리뷰, 564줄)가 diff에 포함돼 있다. 이것을 codex가 읽으면 교차 검증이 무의미해지므로 **focus text에 "`docs/migration/_workspace/reviews/` 아래 파일은 읽지 말고 참고하지 마라"를 명시**했다. 이 에이전트도 해당 파일을 열지 않았다.

### 민감 데이터 취급

focus text에 사용자 문서 본문·실제 암호문·키·개인정보를 포함하지 않았다. 불변식 서술과 파일 경로만 제시했다.

---

## 2. 전달한 프롬프트(focus text) 전문

```
배경: 이 저장소는 공공기관용 문서 변환 SaaS다. 현재 Python/FastAPI로 구현돼 있고 앞으로 Kotlin/Spring Boot로 런타임을 교체할 예정이다. 이 diff(main 대비 2개 커밋)는 아직 Kotlin 코드가 아니라 그 전환을 감시할 **하네스**(에이전트 정의, 스킬 문서, 검증 스크립트)와 그에 딸린 Python 측 수정이다. 즉 리뷰 대상의 본질은 "이 게이트들이 실제로 위반을 막는가"다.

지켜야 하는 불변식(위반 시 사용자 데이터가 유출·유실된다):
1. 사용자 문서 원문은 개인정보 마스킹 파이프라인을 통과한 뒤에만 LLM provider로 전달된다.
2. 개인정보·자격증명이 실리는 HTTP 응답에는 `Cache-Control: no-store`와 `X-Content-Type-Options: nosniff`가 반드시 붙는다.
3. 다른 사용자의 자원 접근은 403이 아니라 404로 존재 자체를 은닉한다.
4. 로그에 문서 본문·개인정보를 남기지 않는다. 문서 id·길이·상태까지만.
5. 기존 Fernet 암호문·Argon2 PHC 해시·JWT는 Kotlin 구현에서도 그대로 읽혀야 한다.

리뷰 제외: `docs/migration/_workspace/reviews/` 아래 파일은 다른 리뷰어의 리뷰 결과다. 읽지 말고 참고하지 마라. 독립적으로 결론을 내야 교차 검증이 성립한다.

적대적으로 캐물을 축 (이 하네스에서 이미 두 번 "게이트가 아무것도 검증하지 않고도 성공"하는 결함이 나왔다. 같은 종류가 더 있다고 가정하고 찾아라):

A. `.claude/skills/python-kotlin-parity/scripts/compare_parity.py` — parity 통과를 위조할 수 있는 남은 경로를 찾아라. 특히: (a) 검사 대상 도메인이 비어 있거나 일부만 있어도 종료 코드 0이 나오는 경로, (b) 외부 검증 증거 파일(`*.verified.json`)이 **실제 실행의 산물임을 무엇이 보장하는가** — 사람이 손으로 써 넣은 증거 파일과 진짜 실행 결과를 이 스크립트가 구분할 수 있는가, 구분할 수 없다면 그 신뢰 경계를 정확히 지목하라, (c) 불일치가 있는데도 exit 0이 되거나, 예외가 삼켜져 성공으로 보이는 경로.

B. `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py` — 12개 규칙이 실제 위반을 잡는지 규칙별로 검증하라. 특히: (a) 검사한 파일이 0개인데도 "위반 없음"으로 성공하는가, (b) 줄 단위 정규식이라 여러 줄에 걸친 위반(체이닝, 변수 경유, 멀티라인 호출)을 놓치는가 — 각 규칙마다 우회하는 구체적 코드 형태를 제시하라, (c) 주석·문자열 안의 가짜 일치로 오탐을 내거나, 반대로 위반 코드가 규칙 패턴을 살짝 벗어나 전부 통과하는가.

C. `.claude/skills/codex-review/scripts/codex-review.sh` — 리뷰를 실제로 돌리지 않았는데 "리뷰 통과" 기록이 남을 수 있는 경로를 찾아라. 헬퍼 탐색 실패, 인증 실패, 빈 출력, 종료 코드 전파 누락을 포함해서 본다.

D. `app/api/auth.py`, `app/api/documents.py` — 개인정보나 자격증명이 응답 본문에 실리는데 캐시 금지 헤더가 붙지 않은 핸들러가 **아직 남아 있는가**. 이 커밋이 6개에서 10개로 확대했지만 전수인지 확인하라. 또한 422 검증 실패 응답 본문에 사용자가 보낸 입력값(비밀번호, 이메일, 파일명)이 그대로 실려 나가는가.

E. `tests/api/test_auth.py`, `tests/api/test_documents.py`에 추가된 회귀 테스트가 **실제로 회귀를 잡는가**. 헤더를 지우면 실패하는가, 아니면 구현을 그대로 복사해 항상 통과하는 구조인가. 커버되지 않은 엔드포인트가 있는가.

각 지적에는 파일 경로와 라인 번호, 그리고 그 결함으로 무엇이 새거나 통과되는지를 적어라. 확신이 없으면 확신 없음을 밝히되 지적 자체는 남겨라.
```

---

## 3. codex 원문 출력 (무편집)

```
# Codex Adversarial Review

Target: branch diff against main
Verdict: needs-attention

출하 불가. Python 응답 헤더와 422 정제는 전수 대조상 적절하고 대상 회귀 테스트 8개도 통과했지만, 핵심 하네스 세 곳 모두 실제 검증 없이 성공할 수 있다.

Findings:
- [critical] 전체 parity 게이트가 손으로 축소한 fixture와 비-Kotlin 결과를 신뢰한다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:292-405)
  fixture 검사는 cases가 비어 있지 않고 id·domain 형식이 맞는지만 확인하며, actual의 `runtime` 값은 전혀 읽지 않는다. 따라서 BUILDERS의 11개 도메인 이름별로 기대값과 actual이 같은 가짜 케이스 하나씩만 두면 원래 수백 개 경계·변조 케이스가 사라져도 missing=0, considered>0으로 `전건 일치`/exit 0이 된다. 실제로 단일 masking fixture와 `runtime: not-kotlin` 결과가 1건만으로 exit 0이 되는 것을 재현했다. Fernet·Argon2·JWT 호환성과 마스킹 동등성이 실행되지 않은 채 승인될 수 있다.
  Recommendation: 비교 시 BUILDERS를 다시 실행해 정본 fixture의 전체 case ID·입력·기대값을 대조하고, 축소·수정된 fixture를 거부하라. 더 강하게는 비교기가 nonce를 발급하고 Gradle/Kotlin runner를 직접 실행해 actual을 생성하도록 만들어 수동 JSON을 신뢰 경계 밖으로 밀어내라.
- [critical] `*.verified.json`은 손작성 파일과 실제 역방향 실행 증거를 구분하지 못한다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:204-254)
  검증기는 sibling JSON에서 `fixture_case`, `status == pass`, `checked >= required` 세 값만 확인한다. `script`, `actual`, `verified_at`, actual 해시나 실행 nonce는 확인하지 않으므로 사람이 `{"fixture_case":"jwt-roundtrip-request","status":"pass","checked":2}`를 쓰면 통과한다. 번들 verifier도 임의 actual의 건수만 세며 fixture가 요구한 키·평문·subject·고유 case 집합과 연결하지 않아, Python이 만든 토큰이나 중복 케이스로 진짜 proof 파일을 만들 수도 있다. 정확한 신뢰 경계는 'actual 디렉터리에 JSON을 쓸 수 있는 주체' 전부이며, Kotlin 실행 여부는 보장되지 않는다.
  Recommendation: proof 파일을 입력으로 받지 말고 comparator가 Kotlin runner와 Python verifier를 한 프로세스 흐름에서 실행하라. 최소한 fixture/actual SHA-256, 실행 nonce, Kotlin build SHA, verifier SHA와 정확한 case ID·입력 집합을 proof에 결합하고 CI의 깨끗한 작업 공간에서만 생성·검증하라.
- [high] 부분 parity 검증이 문서 경고와 무관하게 exit 0을 반환한다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:614-621)
  `--only`, `--only-domain`, 단일 fixture, 도메인 디렉터리는 10개 도메인을 생략해도 마지막에 0을 반환한다. 단일 masking 가짜 케이스로 실행했을 때 출력은 '기대 집합 11개 중 10개는 돌리지 않았다'고 경고하면서 실제 종료 코드는 0이었다. 종료 코드만 보는 CI·에이전트는 이를 전체 통과로 기록할 수 있고, 파일 상단의 '0은 전체 도메인 존재 시에만'이라는 계약과도 모순된다.
  Recommendation: 부분 성공에는 별도 비zero 종료 코드(예: 3)를 사용하고, 전체 게이트 전용 명령을 분리하라. 자동화는 구조화된 `scope=full`, 도메인·케이스 수와 함께 성공을 판정하게 하라.
- [high] `--changed`는 커밋된 브랜치 변경을 0개 검사 후 성공시킨다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:205-334)
  변경 파일을 `git diff --name-only HEAD`와 untracked에서만 얻으므로 main 대비 이미 커밋된 Kotlin/Python 변경은 전부 제외된다. 현재 브랜치에서 실제 main 대비 app 변경이 있는데도 `--changed` 실행은 '검사 대상 파일이 없습니다.'와 exit 0을 반환했다. 에이전트가 구현을 커밋한 뒤 이 권장 명령을 실행하면 보안 코드를 한 줄도 읽지 않고 게이트가 성공한다.
  Recommendation: `--base <ref>`를 필수화해 `git diff <base>...HEAD`와 working tree를 합치고, 파일 0개는 비zero로 실패시켜라. 실제 스캔 파일 목록과 base/head SHA를 증거에 기록하라.
- [high] 12개 privacy 규칙 모두 간단한 실제 코드 형태로 우회된다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:73-270)
  스캐너가 파일을 줄별로 잘라 각 정규식을 독립 적용한다. 실제 패턴 검색으로 다음 12개 형태가 모두 False임을 확인했다: LOG-BODY `logger.info(\n body)`; LOG-FSTRING `logger.info(\n f"{body}")`; EXC-BODY `raise InvalidInput(\n body)`; LLM-VENDOR-SDK `__import__("openai")`; LLM-RAW-INPUT `provider.complete(\n user=source_text)`; OWNERSHIP-403 `status(400 + 3)`; PLAINTEXT-PERSIST `entity.sourceText = sourceText`; SECRET-LITERAL `credential="0123456789abcdef"`; XML-DTD aliased `DBF.newInstance()`; ZIP-NO-BUDGET `JarInputStream(input)`; CACHE-HEADER `return SensitiveResponse(email)`; RETENTION-PURGE `cleanupOldDocuments(...limit=1000)`. 또한 `backend-kotlin/infrastructure/` 전체가 vendor SDK 규칙에서 면제된다. 반대로 현재 전수 실행은 테스트의 `password: 'wrongpassword'`와 안전하게 감싼 ElementTree import를 BLOCK으로 잡아 exit 1을 냈고, 문자열·멀티라인 docstring 내부도 오탐한다. 즉 민감한 멀티라인·변수 경유 위반은 통과시키면서 정상 기준선은 실패시킨다.
  Recommendation: Python AST와 Kotlin/Java parser 기반으로 호출·인자·할당 흐름을 최소 함수 단위로 분석하라. CACHE/RETENTION처럼 '존재'가 아니라 '누락'을 찾아야 하는 규칙은 엔드포인트·스케줄러를 열거해 각 항목의 필수 속성을 검증하라. 위 12개 우회 및 주석·문자열 오탐을 하네스 회귀 테스트로 고정하라.
- [high] codex wrapper는 빈 가짜 helper의 exit 0을 실제 리뷰 성공으로 전파한다 (.claude/skills/codex-review/scripts/codex-review.sh:148-273)
  `CODEX_COMPANION`은 읽을 수 있기만 하면 helper로 신뢰되고, 마지막에는 출력을 캡처·검증하지 않고 그대로 exec한다. `CODEX_COMPANION=/dev/null ...codex-review.sh adversarial --base main --focus probe`가 리뷰 출력 없이 exit 0이 되는 것을 재현했다. `--dry-run`도 의도적으로 실행하지 않고 0을 반환한다. helper 미발견과 정상적인 nonzero 인증 실패는 전파되지만, 빈 출력·오류 문구만 출력하고 0을 반환한 인증 실패·깨진 JSON·잘못된 helper는 구분하지 못해 빈 리뷰 파일이 통과 기록으로 남을 수 있다.
  Recommendation: 자식 출력을 임시로 캡처해 non-whitespace 여부를 확인하고, `--json`에서는 스키마·review/job ID·완료 상태를 검증한 뒤에만 0을 반환하라. helper의 실제 CLI handshake를 확인하고 dry-run에는 성공과 구분되는 종료 코드를 사용하라.
- [medium] 무한대 float tolerance로 실제 숫자 불일치를 일치 처리할 수 있다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:104-116)
  `float_tol:<값>`을 유한·비음수인지 검증하지 않고 그대로 `float()`로 읽는다. `float_tol:inf`에서 `equal(1.0, 999999.0, inf)`가 True가 되는 것을 재현했다. fixture 수정이 허용되는 현재 신뢰 모델과 결합하면 숫자 출력이 전부 달라도 exit 0으로 위조할 수 있다.
  Recommendation: `math.isfinite(tolerance)`와 `0 <= tolerance <=` 도메인별 상한을 강제하고, `inf`, `-inf`, `nan`, 과도한 tolerance를 거부하는 회귀 테스트를 추가하라.

Next steps:
- 위 세 하네스 스크립트에 empty/partial/shrunk fixture, 위조 proof, `float_tol:inf`, empty helper, clean committed branch를 다루는 자동 회귀 테스트를 먼저 추가한다.
- parity 증거 생성을 clean CI 안의 단일 오케스트레이션으로 묶어 fixture·Kotlin 실행·Python 역검증 사이의 nonce와 해시를 연결한다.
- privacy `--changed`의 base를 명시하고 0-file을 실패시킨 뒤, 12개 규칙을 AST/구조 기반 검사 또는 실행형 보안 계약 테스트로 대체한다.
- Python 헤더 패치와 422 입력값 제거는 유지한다. 현재 auth/documents의 민감 성공 응답 전수와 추가 테스트에서는 별도 차단 결함을 찾지 못했다.
```

---

## 4. 정리(가공)

> 아래는 §3 원문의 지적을 **목록화만** 한 것이다. 옳고 그름 판정, 심각도 재부여, 중복 병합, 표현 다듬기를 하지 않았다.
> 심각도 라벨은 codex가 붙인 값(`critical`/`high`/`medium`)을 그대로 옮겼다. 파일·라인도 codex가 준 값 그대로이며 다시 세지 않았다.

**codex 총평 (원문 인용)**: "출하 불가. Python 응답 헤더와 422 정제는 전수 대조상 적절하고 대상 회귀 테스트 8개도 통과했지만, 핵심 하네스 세 곳 모두 실제 검증 없이 성공할 수 있다."

**verdict**: `needs-attention`

| # | 심각도(codex) | 지적 요지 | 근거 위치(codex 제시) | 대응 축 |
|---|---|---|---|---|
| 1 | critical | parity 게이트가 손으로 축소한 fixture와 비-Kotlin 결과를 신뢰한다. `runtime` 값을 읽지 않아 도메인별 가짜 케이스 1건씩이면 exit 0 | `compare_parity.py:292-405` | A |
| 2 | critical | `*.verified.json`이 손작성 파일과 실제 역방향 실행 증거를 구분하지 못한다. `fixture_case`/`status`/`checked` 3개 값만 확인 | `compare_parity.py:204-254` | A |
| 3 | high | 부분 parity 검증(`--only`, `--only-domain`, 단일 fixture)이 10개 도메인을 생략해도 exit 0 | `compare_parity.py:614-621` | A |
| 4 | high | `--changed`가 `git diff --name-only HEAD` 기반이라 이미 커밋된 브랜치 변경을 0개 검사하고 성공 | `scan_privacy_invariants.py:205-334` | B |
| 5 | high | 12개 privacy 규칙 전부가 멀티라인·변수 경유 형태로 우회됨. 동시에 정상 기준선을 오탐으로 실패시킴 | `scan_privacy_invariants.py:73-270` | B |
| 6 | high | codex wrapper가 빈 가짜 helper의 exit 0을 리뷰 성공으로 전파. 출력을 캡처·검증하지 않고 exec | `codex-review.sh:148-273` | C |
| 7 | medium | `float_tol`이 유한·비음수 검증 없이 `float()`로 파싱돼 `float_tol:inf`가 모든 숫자를 일치 처리 | `compare_parity.py:104-116` | A |

### codex가 재현했다고 명시한 항목

원문에서 codex가 "재현했다"·"확인했다"로 표현한 것만 옮긴다. 재현의 타당성은 판정하지 않는다.

- 단일 masking fixture + `runtime: not-kotlin` 결과 1건으로 exit 0 (#1)
- 부분 실행 시 "기대 집합 11개 중 10개는 돌리지 않았다" 경고와 함께 종료 코드 0 (#3)
- 현 브랜치에서 main 대비 app 변경이 있는데도 `--changed`가 "검사 대상 파일이 없습니다." + exit 0 (#4)
- 12개 규칙 우회 형태가 모두 False (#5) — 원문에 규칙별 구체 코드 형태가 나열돼 있다
- 전수 실행이 테스트의 `password: 'wrongpassword'`와 안전하게 감싼 ElementTree import를 BLOCK으로 잡아 exit 1 (#5, 오탐 방향)
- `CODEX_COMPANION=/dev/null` 로 리뷰 출력 없이 exit 0 (#6)
- `float_tol:inf` 에서 `equal(1.0, 999999.0, inf)` → True (#7)

### 축 D·E에 대한 codex의 결론

- 축 D(캐시 헤더 잔존 누락·422 입력값 유출), 축 E(회귀 테스트 실효성): codex는 **차단 결함을 찾지 못했다**고 적었다. 원문 마지막 줄: "Python 헤더 패치와 422 입력값 제거는 유지한다. 현재 auth/documents의 민감 성공 응답 전수와 추가 테스트에서는 별도 차단 결함을 찾지 못했다."
- 이 부정 결과도 그대로 기록한다. 지적이 없다는 사실 자체가 교차 대조의 입력이다.

### 전제 확인 필요

원문을 삭제하지 않고 그대로 두되, `migration-reviewer`가 확인할 지점만 표시한다. 이 에이전트는 옳고 그름을 판정하지 않는다.

- #5의 "`backend-kotlin/infrastructure/` 전체가 vendor SDK 규칙에서 면제된다" — `backend-kotlin/`은 아직 존재하지 않는 디렉터리다(Phase 1 산출물). 규칙의 면제 경로 설정에 대한 지적인지, 존재하는 코드에 대한 지적인지 전제 확인 필요.
- #1의 "BUILDERS의 11개 도메인"과 #3의 "기대 집합 11개 중 10개" — 도메인 수의 근거가 `compare_parity.py` 내부 상수인지 확인 필요.
- codex는 `parity/` 디렉터리 존재 여부를 확인하는 명령을 실행했다(job 로그). fixture가 아직 없는 상태에서의 지적인지 여부가 #1·#2 해석에 영향을 줄 수 있다.

---

## 5. 미실행·실패 항목

### 실행 결과

**codex 리뷰는 완료됐다.** 취소·타임아웃·부분 응답·잘림 없음.

| 항목 | 값 |
|---|---|
| 종료 | 정상 완료 (`EXIT=0`, job status `completed`) |
| 시작 | 2026-08-11 23:50:56 KST |
| 종료 | 2026-08-11 23:59:43 KST |
| 소요 | 8분 47초 |
| 20분 취소 기준 | **미도달** — 취소하지 않았다 |
| 출력 | 8,599 bytes / 35 lines, 지적 7건. 잘림 없음 |

1회차 대비 소요가 33분+(취소) → 8분 47초로 줄었다. `--base main` branch diff로 하네스 전체가 전달돼 codex가 전수 탐색을 하지 않아도 됐기 때문으로 관측된다.

### 미실행·미확인 항목

- **1회차 codex 출력 없음** — §1.1 참조. 1회차는 결과 없이 취소됐으므로 회차 간 지적 비교는 불가능하다. 본 문서의 지적은 전부 2회차 산출이다.
- **축 D의 "422 입력값 유출" 중 파일명 경로** — codex 원문은 auth/documents의 민감 성공 응답과 422 처리를 다뤘다고 적었으나, 업로드 파일명이 422 본문에 실리는지에 대한 개별 언급은 원문에 없다. 확인됐는지 여부가 원문만으로는 판별되지 않는다.
- **codex 실행 중 pytest 1회 실패** — job 로그상 `.venv/bin/pytest ... tests/api/test_...`가 1회 exit 1로 실패한 뒤, 이후 다른 인자의 실행이 exit 0으로 완료됐다. codex는 최종적으로 "대상 회귀 테스트 8개도 통과했다"고 적었다. 첫 실패의 원인은 원문에 설명되지 않았고, 이 에이전트도 추정하지 않는다.

### 독립성 관련

- codex에 `docs/migration/_workspace/reviews/` 열람 금지를 focus text로 지시했다. job 로그에서 codex가 `-g '!docs/migration/_workspace/reviews/**'` 제외 패턴을 쓴 명령이 관측된다. 다만 전 구간에서 해당 파일을 읽지 않았음을 이 산출물만으로 100% 보장하지는 못한다.
- 이 에이전트는 `00_pre-phase0_migration-reviewer.md`를 열지 않았다.

### 다음 단계 (스킬 §2.1 3단계)

이 문서는 1단계(병렬 독립 실행)의 codex 측 산출물이다. `00_pre-phase0_migration-reviewer.md`와 함께 **`migration-reviewer` 2차 호출**로 교차 대조해 `00_pre-phase0_cross.md`를 만들어야 게이트가 닫힌다. 심각도 확정·상충 표시·권고는 그 단계의 몫이다.
