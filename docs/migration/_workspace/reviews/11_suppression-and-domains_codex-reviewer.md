# 게이트 11 — codex 독립 리뷰 (1단계)

> 어간 `11_suppression-and-domains` 는 **리더가 1단계 호출에서 지정한 값**을 그대로 쓴다.
> 이 문서는 `codex-reviewer` 산출물이다. **codex 원문은 §3 에 무편집으로 수록**한다.
> Claude 의 판정·심각도 부여·중복 병합·표현 다듬기·"오탐 같다" 주석 삽입은 하지 않는다.
> 교차 대조와 종합은 `migration-reviewer` 2차 호출(`11_suppression-and-domains_cross.md`)의 몫이다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | **1회차** (재시도 없음 — 첫 실행이 exit 0) |
| 실행 시각 | 2026-08-14 12:43 KST 착수 · **소요 11m 3s** (헬퍼 보고값) |
| 대상 범위 | `56a70c1..cd23aec` (branch diff, `--base 56a70c1`) |
| **스크립트 종료 코드** | **`0`** — `codex-review` §3.1 표에서 **리뷰 근거로 성립하는 유일한 값** |
| 리뷰 대상 (stderr 원문) | `codex-review: 리뷰 대상 = branch diff vs 56a70c1` |
| 대상 판정 (stderr 원문) | `codex-review: 대상 판정 = non-empty (merge-base=56a70c1e7ea9, 변경 파일 42개 (branch 모드는 커밋된 변경만 센다))` |
| 모드 | `adversarial-review` |
| scope | `auto(미지정)` — `--base` 지정이므로 scope 무시 |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 버전 · 출처 | `1.0.6` / plugins cache (최신 버전 자동 선택) |
| job id | `review-msse9ol1-zamzxx` |
| codex session id | `019ffe56-e888-7b11-8ea9-7ad918db5a9c` |
| codex 가 보고한 Target | `branch diff against 56a70c1` (출력 3행) — 지정 base 와 일치 |
| codex Verdict | `needs-attention` (출력 4행) |
| 지적 건수 | **7건** — `[high]` 4 · `[medium]` 3 |

**실행 명령 전문**

```bash
FOCUS="$(cat <focus 파일>)" \
  && .claude/skills/codex-review/scripts/codex-review.sh adversarial --base 56a70c1 "$FOCUS"
```

헬퍼로 나간 실제 명령 (스크립트 stderr `실행 명령 =` 줄 그대로):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base 56a70c1 '<focus 전문 — §2>'
```

`run_in_background` 로 띄웠다 — 소요 11분으로 Bash 도구 기본 타임아웃(120s)에 잘렸을 실행이다.

### 1.1 대상 리비전 결속 — **드리프트 없음**

리더가 지정한 리뷰 대상은 커밋 범위 `56a70c1..cd23aec` 이고, 리뷰 실행 시점의 `HEAD` 가
**정확히 `cd23aec`** 였다. `--base` 는 `merge-base(HEAD, <ref>)..HEAD` 를 대상으로 삼으므로
지정 범위와 실제 대상이 일치한다.

```
$ git rev-parse HEAD
cd23aecae1ccb0540530213014b8cf807417b050

$ git merge-base HEAD 56a70c1
56a70c1e7ea92dbdf059a854196e5e22c5c3dcae      → merge-base == base ref 자신

$ git diff --name-only 56a70c1..cd23aec | wc -l
42                                            → 스크립트 stderr 의 "변경 파일 42개" 와 일치
```

**수거 시 Target 줄 대조 (지난 회차 오인 회수 방지 절차)**: codex 출력 3행이
`Target: branch diff against 56a70c1` 로, 지정한 base 와 같다. 지난 회차(게이트 10)는 지정 범위
끝(`56a70c1`)과 실행 시점 `HEAD`(`525fc96`)가 갈려 코드 대상 동일성을 별도로 실측해야 했다.
**이번 회차는 그 대조가 불필요하다** — 두 리비전이 같다.

작업 트리에 untracked 3건(`.playwright-mcp/`, `.doc` 2건)이 있었으나 `--base` 지정 시
scope 는 무시되고 **커밋된 변경만** 대상이 되므로 리뷰 대상에 들어가지 않았다.

### 1.2 범위 구성 — 관측 사실

범위 내 커밋 10건. 커밋 제목 접두사로 가르면 `docs:` 5건 · 코드 5건이다.

| 커밋 | 시각(KST) | 제목 |
|---|---|---|
| `525fc96` | 08-14 08:41 | docs: 원장 — 게이트 09 이후 수정 파동 기록·게이트 10 착수·열린 판정 갱신 |
| `4e68bc2` | 08-14 11:32 | docs: 게이트 10 1단계 산출물 2건 |
| `ca37a89` | 08-14 11:46 | docs: 게이트 10 교차 종합 정본 + 리더 판정 5건 (J-1~J-5) |
| `70065d4` | 08-14 11:49 | fix(scanner): LOG_CALL 이 `log.` 을 못 보던 것을 고친다 (R-3) |
| `b05c4c5` | 08-14 11:51 | docs: privacy-gate §4-octies — refine 훅의 탐지형 대체 설계 (J-2) |
| `d9ca846` | 08-14 11:57 | fix(scanner): 한 줄의 두 번째 호출과 중첩 블록 주석 (R-1·R-2 + M-02 보완) |
| `0371400` | 08-14 11:58 | docs: 게이트 10 스캐너 배치 기록 — 같은 종류가 세 자리에 남아 있었다 |
| `ad6ab92` | 08-14 12:10 | chore(parity): 케이스 정체성 하한 + 잔여 5도메인 fixture ready 전환 |
| `7525db2` | 08-14 12:15 | refactor(scanner): 억제를 중앙 휴리스틱에서 호출 지점 가시 표기로 (§4-octies) |
| `cd23aec` | 08-14 12:29 | feat(parity): 잔여 5도메인 생산자 배선 — 선언 2 → 7 |

> 호출 지시문은 이 범위를 "docs 4 + 코드 6" 으로 적었고 세 번째 묶음에서 `0371400` 을
> 코드 커밋 쪽에 열거했다. 제목 접두사 기준 실측은 `docs:` 5 · 코드 5 다 (`0371400` 은
> `docs/migration/_workspace/00_progress.md` 단독 변경). **어느 쪽이 옳은지 판정하지 않고
> 관측만 적는다** — 리뷰 대상 리비전 범위 자체는 양쪽이 동일하다.

변경 파일 42개의 코드/문서 분리 (리뷰 대상에는 둘 다 포함된다):

- **비 `docs/` 33개** — 스캐너 1 · parity 스크립트 2 + SKILL 1 · 하한 파일 2 · Kotlin 7 ·
  `parity/fixtures` 8 · `parity/reference-ledger` 6 · `scripts/` 2 · 탐침 fixture 2 · 스캐너 테스트 1
- **`docs/` 9개** — `_workspace` 6 + `reviews/` 3 (게이트 10 산출물 3건)

### 1.3 제공한 맥락 목록

focus text 로 주입한 조건은 전부 아래 정본에서 인용했다. codex 는 저장소를 읽을 수 있으므로
파일 경로를 지목했고, 실제로 코드를 열고 **탐침을 직접 실행**했다(§3 원문에 재현 결과가 있다).

| 정본 | 무엇을 인용했나 |
|---|---|
| `docs/migration/_workspace/07_privacy-gate_masking-verdicts.md` §4-octies | 표기 문법, 남용 방어 a~f, `Rule.markable` 배정 12종, 3층 단방향과 억제 층 금지 4종, 불변량 3종, 해제 조건 6종, refine 판별식, 예산 초기값 문언 |
| `docs/migration/_workspace/reviews/10_detector-redesign_cross.md` §2.1·§11.1 | 차단 4건 R-1~R-4 의 기제와 처방 |
| `docs/migration/_workspace/02_parity-verifier_case-identity.md` | 케이스 id 하한, `!deferred` 꼬리표, `verdict_pending` 4필드, 분리 집계, 비대칭(추가 자유/삭제·개명 차단), `--only` 예외 |
| `docs/migration/_workspace/02_parity-verifier_domain-fixtures.md` | 5도메인 하네스 계약, `length_violations`·`comma_violations`, prompts 난수 id 재현성, style-tables `equals_field`/`contains_all` 분리, postprocess 양방향 |
| `.claude/skills/codex-review` §4.6 | 선언 범위 대 실제 도달 6개 질문 (게이트를 세우거나 넓히는 변경이므로 **필수** 축) |
| `CLAUDE.md` | 재개발 판정 기준(요구사항 충족), 정책 불변식(마스킹 선행·로그 금지·소유권 404) |

**민감 데이터 미포함 확인**: focus text 에 사용자 문서 본문·실제 암호문·키·개인정보를 넣지
않았다. 규칙 id·필드명·파일 경로·정책 상수(50자·쉼표 2개)만 실었다.

---

## 2. 전달한 프롬프트 전문 (focus text)

> `codex-review` §3.5 "focus 는 우리 계약을 codex 에 주입하는 통로"에 따라 작성했다.
> 리더가 지정한 4축을 축 1~4 로 옮기고, §4.6(선언 범위 대 실제 도달)을 다섯 번째 묶음으로
> 얹었다 — 이번 배치가 게이트·불변식을 새로 세우고 넓히는 변경이므로 §4.6 은 **필수**다.
> 조건은 단정문으로 주고 판단은 codex 에 맡겼으며, **Claude 가 이미 의심하는 지점은 넣지
> 않았다.** 아래는 전달한 전문 그대로다.

````text
배경. 이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 재개발 중이다. 판정 기준은 "Python 과 같은 값이 나오는가"가 아니라 "요구사항·정책을 충족하는가"다. 단 정책 불변식(사용자 문서 텍스트는 마스킹 파이프라인을 통과한 뒤에만 LLM provider 로 전달된다, 로그에 문서 본문·개인정보를 남기지 않는다, 소유권 은닉 404)은 형태가 지정돼 있어 그대로 유지해야 한다. 이 배치는 **제품 코드가 아니라 게이트(장치) 자체**를 크게 고친다 — 개인정보 스캐너(.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py), parity 비교기·생성기(.claude/skills/python-kotlin-parity/scripts/{compare_parity.py,dump_parity_fixtures.py}), 하한 선언 파일(.github/parity-*-floor.txt), 그리고 Kotlin parity 생산자(backend-kotlin/core/src/test/kotlin/kr/easydoc/core/CoreDomainsParityTest.kt). 게이트가 무력해지면 사건이 나도 아무도 모르므로, "동작하는가"가 아니라 "동작하는 것처럼 보이면서 아무것도 재지 않는 자리가 있는가"를 물어라.

지켜야 하는 조건 — 네 묶음. 각 묶음은 이 저장소의 설계 문서가 못박은 것이고, 이번 diff 가 그것을 실제로 이행했는지가 채점 대상이다.

[축 1] 직전 게이트에서 차단으로 판정된 4건이 실제로 닫혔는가, 그리고 새 표기 체계 자체가 남용 표면을 만들지 않는가.
- R-1: 한 논리 줄에 로그 호출이 둘 이상일 때 첫 적중 하나만 판정하면 안 된다. 모든 호출을 각각 판정하고, 하나라도 안전하지 않으면 후보로 남아야 한다(이전에는 search() 가 첫 적중만 꺼내 refine 에 넘겨, 앞이 안전하면 뒤가 통째로 미검사였다).
- R-2: 블록 주석은 중첩 깊이로 추적해야 한다. Boolean 상태로 추적하면 첫 종료 표시에서 닫혀 주석 꼬리가 코드로 재유입되고 그 괄호가 인자 구간을 조기 폐쇄한다. **참조용 주석 제거기(테스트 쪽)도 같은 모델이면 둘이 같이 틀려서 단언이 통과한다** — 두 구현이 독립적인지 확인하라.
- R-3: 로그 호출 탐지 정규식이 이 저장소가 실제로 쓰는 수신자 이름을 봐야 한다. 이전 패턴 `_?logger?\.` 는 `logge` + 선택적 `r` 로 읽혀 `log.` 을 한 번도 보지 않았다. 지금 패턴이 실제 사용 형태(log. / _log. / LOG. / logger. / kotlin-logging 람다형 / slf4j fluent)에서 무엇을 보고 무엇을 못 보는지 열거하라.
- R-4: parity 비교기의 보류 마커(옛 `known_gap`)는 어느 게이트도 읽지 않는 자유 문자열이었다. 지금은 `verdict_pending` 이 reason/owner/deadline/referred_by 넷을 필수로 요구하고 하나라도 비면 막아야 하며, 보류 케이스는 「성질 판정 N건」 집계에서 분리돼야 한다.
- **표기 체계의 남용 표면**(이것이 이 축의 핵심이다): 억제가 중앙 휴리스틱에서 호출 지점 가시 표기(`# privacy-allow: <RULE-ID> — <사유>`)로 옮겨졌다. 다음 우회 경로를 각각 검사하라 — (a) 고아 표기 실패 검사(적중을 내지 않는 줄의 표기는 실패)를 우회하는 방법, (b) 예산 상한 파일·상수를 같은 커밋에서 올려 표기를 무한히 늘리는 경로와 그 편집이 리뷰에 보이는지, (c) `Rule.markable=False` 목록을 축소해 표기 금지 규칙을 표기 가능으로 바꾸는 경로와 그것을 막는 검사가 있는지, (d) 표기 인식이 **물리 줄** 기준인지 **논리 줄** 기준인지 — 다중 줄 호출·논리 줄 결합·문자열 안에 표기 문자열이 들어간 경우·표기를 붙인 줄과 적중이 보고되는 줄이 어긋나는 경우에 표기가 의도보다 넓게 또는 좁게 듣는 자리, (e) 사유 필수 검사를 공백·유니코드 공백·주석 기호 반복 등으로 통과시키는 경로, (f) 규칙 한정(그 줄의 그 규칙만 억제)이 깨져 같은 줄의 다른 규칙 적중까지 눌리는 경로.

[축 2] 억제 층 이관의 충실도 — 설계 대비 구현.
설계가 요구한 해제 조건은 여섯이다. ① `LOG-BODY` 의 refine 과 `_SAFE_MEMBERS`/`_SAFE_QUALIFIERS`/`_SAFE_ACCESS`/`log_body_is_real_candidate`/금지 멤버 자기검사 일체 삭제. ② 표기 문법과 방어 a~f 실재, `Rule.markable` 배정이 제안대로 — True: LOG-BODY·LOG-FSTRING·EXC-BODY·CACHE-HEADER·ZIP-NO-BUDGET·RETENTION-PURGE / False: LLM-RAW-INPUT·OWNERSHIP-403·PLAINTEXT-PERSIST·SECRET-LITERAL·LLM-VENDOR-SDK·XML-DTD. ③ `suppress(hits, ∅) == hits` 속성 테스트 실재. ④ 리포트가 억제 항목을 `파일:줄 — 규칙 — 사유` 로 **전건** 출력(개수만 찍으면 안 된다 — 이전에 개수만 남아 R-1 이 리포트 안에서 보이지 않았다). ⑤ 음성 대조 8종이 상시로 돌 것. ⑥ 스캐너 전수 exit 0 이고 **표기 개수 = 예산 상한**.
- 방어 a 의 문언은 "예산 초기값은 이관 시점 실제 개수 + 0 — 여유를 주지 않는다"이고, 이관 절차가 지목한 실물은 `scripts/collect_golden.py` 세 줄이다(초기값 3). 이번 diff 의 실제 상한값과 실제 표기 개수를 세어 그 문언과 대조하고, 차이가 있으면 그 차이가 무엇으로 정당화되는지 diff 안에서 근거를 찾아라. 근거가 없으면 그렇게 적어라.
- 층 분리는 3층 단방향이고 억제 층에 네 가지 금지가 걸려 있다 — 재파싱 금지, 표기 추출을 스스로 하지 않음(주석 판별은 어휘 층의 일이다. 억제 층이 자기 주석 파서를 가지면 중첩 주석 결함을 그대로 되산다), 검출 루프 안에서 호출되지 않음(검출 전량이 나온 뒤 한 번), 물리 줄을 특정할 수 없는 적중은 억제 대상이 아님(닫힘). 억제 층이 보는 필드는 `(rule_id, path, physical_line)` 뿐이어야 한다. 이 넷 중 실제로 위반되는 경로를 코드에서 짚어라.
- `suppress(hits, ∅) == hits` 속성 테스트가 **실제로 무엇을 재는지** 확인하라 — 입력 생성 범위가 좁아 항등을 자명하게 만들거나, 억제 층이 아니라 그 층을 감싼 껍데기를 부르거나, 실제 스캔 경로와 다른 함수를 부르면 이 단언은 통과하면서 불변량을 지키지 않는다.
- 새 refine 판별식("refine 의 입력이 어휘·구문 층의 정확성에 의존하면 금지, 자기 완결적 캡처 그룹의 값만 보면 허용")이 코드에 못박혀 있는지, 그리고 남긴 `SECRET-LITERAL` 의 refine 이 그 판별식을 실제로 통과하는지 검사하라.

[축 3] 5도메인 Kotlin 생산자의 격리와 정확성.
- 하네스 계약: 산출물은 `parity/actual/{도메인}/{도메인}.json`, 최상위 `runtime: "kotlin"`, fixture 와 같은 파일명. 선언(`backend-kotlin/parity-domains.txt`)·`.github/parity-declared-floor.txt`·생산자가 같은 커밋에 있어야 `parityManifestCheck` 가 산다.
- `style` 도메인은 `length_violations`·`comma_violations` 를 **기계가 읽을 수 있는 형태**로 내야 한다. 한국어 산문 필드(`issues[].reason`)를 되파싱해 판정하면 문구를 손대는 날 게이트가 조용히 깨진다. 지금 구현이 되파싱에 의존하는 자리가 있는지 보라. 또 정책 상수(문장 50자·쉼표 2개)는 비교기가 자기 힘으로 들고 있어야 한다 — 구현에서 읽어 오면 구현이 자기 자신을 채점한다.
- `prompts` 도메인은 요청마다 난수 문서 id 를 넣는데(prompt injection 방어), 그 값이 fixture 의 reference 에 들어가면 정본 대조(생성기 재실행 후 전량 비교)가 영구히 깨진다. 이번 diff 는 생성기에서 id 를 고정하고 비교기에서 같은 자리를 정규화한다고 한다. **그 왕복(마스킹 → 되돌린 원문을 재마스킹)이 fixture 기대값과 프로덕션 실제 호출 순서를 정말 같게 만드는지** 데이터 흐름을 따라가 확인하라. 특히 정규화가 판정을 덮어 실제 결함까지 가리는 자리, 그리고 고정 id 가 프로덕션 경로로 새는 자리를 찾아라. 마스킹 선행 불변식(원문이 마스킹을 우회해 provider·로그·예외 메시지로 흐르는 경로)이 이 왕복 코드에서 깨지는지도 함께 보라.
- `StyleRuleKind` enum 이 새로 들어왔고 기본값이 없어 4개 생성 지점이 컴파일로 강제된다고 주장한다. 이 추가가 **프롬프트 문안과 API 계약을 바꾸지 않았다**는 주장이 참인지 검증하라 — 직렬화 이름이 응답·프롬프트 문자열로 새는 자리, enum 값이 한국어 산문 필드를 대체하면서 스냅샷 테스트가 덮던 문안이 바뀐 자리, 기본값 없음이 실제로 전 생성 지점을 강제하는지(when 절의 else, 팩토리 우회, 리플렉션·역직렬화 경로).
- `style-tables` 도메인에서 집합 순서로 갈림 1건이 처리됐다. 순서를 판정하지 않기로 한 결정이 이 도메인의 요구 성질을 실제로 약화시키는지 — 즉 순서가 요구사항에 걸리는 자리가 있는지 확인하라. 큐레이션 표는 `contains_all`(누락 금지·추가 허용), 정책 상수는 값 동일(`equals_field`)이 설계다.

[축 4] 케이스 정체성 장치의 우회 경로.
`.github/parity-case-floor.txt` 가 케이스 id 151개를 하한으로 잡고, 보류 상태는 줄 끝 ` !deferred` 꼬리표로 기억한다. 추가는 통과, 삭제·개명은 실패가 설계다.
- 다음 우회를 각각 검사하라 — (a) 케이스 id 를 바꾸면서 같은 커밋에서 하한 파일도 같이 고치는 경로가 어떤 신호를 남기는가, (b) `!deferred` 꼬리표만 지우면 보류 케이스가 조용히 「성질 판정」 수로 넘어가는가(이전 구현의 실제 구멍이었다), (c) `--only` 로 단일 케이스를 돌릴 때 이 검사를 건너뛰는 예외가 다른 실행 경로에서도 열려 CI 에서 하한 검사가 통째로 꺼지는 경로가 있는가, (d) 옛 `known_gap` 키가 남아 있어도 막는다는 마이그레이션 가드가 실제로 모든 도메인 파일에 닿는가, (e) 케이스 id 는 그대로 두고 단언을 무르게 바꾸는 편집을 이 하한이 못 잡는다면, 그것을 받는다고 선언된 장치(fixture 정본 대조)가 실제로 CI 에서 도는가.

선언한 범위와 실제 도달의 대조 — 위 네 축 전부에 다음을 함께 물어라. 이 저장소는 같은 형태의 실패를 반복해서 겪었고(필터가 도달하지 않음, 게이트가 CI 에 배선되지 않아 도달 0, 검사 기준이 검사 대상 자신에게서 나옴, 성공 코드를 "검토했다"로 읽음, 범위가 근거보다 넓은 억제가 이상 징후를 은폐), 그래서 이 축이 매 회차 필수다.
- 이번 diff 가 세우거나 넓힌 게이트·검사·불변식이 **어디서 도는가**? 로컬 전용인가, CI 잡(.github/workflows/ci.yml)에 실제로 배선돼 있는가, 아무 데서도 안 도는가? 배선돼 있다면 그 잡이 실패를 실제로 빨간불로 만드는가(경고만 찍고 exit 0 인 경로).
- 이번 diff 의 검사들이 대조하는 **기준이 검사 대상 자신에게서 나오지 않는가**? 생성기가 만든 fixture 를 그 생성기의 선언과 대조하는 구조, 구현을 복사한 기대값, 자기가 쓴 파일을 자기가 읽는 검증을 찾아라.
- 성공/실패 판정이 **대리 지표**로 이뤄지는 자리가 있는가? 지적 건수를 변경 여부로, 종료 코드 0 을 "검토했다"로, 테스트 통과를 "그 경로가 실제로 돌았다"로 바꿔 읽는 자리.
- 이번 diff 가 추가한 장치를 각각 제거하면 **정확히 무엇이 깨지는가**? 떼어도 아무 테스트가 깨지지 않는 장치를 지목하라.
- 선언 파일 3종(`backend-kotlin/parity-domains.txt` 7개 · `.github/parity-declared-floor.txt` · `.github/parity-case-floor.txt` 151)이 **빈 선언·0건 상태에서 통과하지 않는지** 확인하라. 이 저장소는 "아직 시작 안 했다"와 "방금 껐다"가 같은 로그를 내던 실패를 이미 겪었다.

질문. 위 조건을 위반하는 경로, 조건은 만족하나 검증이 빠진 자리, 그리고 놓친 경계 조건을 찾아라. 코드에 남아 있는 죽은 억제 흔적(삭제됐어야 할 헬퍼·상수·목록)도 지목하라. 각 지적에 파일 경로와 줄 번호, 그리고 그것이 틀렸을 때 무엇이 새는지를 함께 적어라. 조건을 만족한다고 판단한 자리도 "검토했고 지적 없음"으로 명시적으로 적어라 — 침묵과 통과를 구분할 수 있어야 한다.
````

---

## 3. codex 원문 출력 — **무편집**

> 아래는 `codex-review.sh` 표준출력 전문이다. 한 글자도 고치지 않았다. 요약·병합·
> 표현 다듬기·주석 삽입을 하지 않았으며, 이 저장소 규칙과 어긋나 보이는 제안이
> 있더라도 그대로 둔다(그 판단은 종합 단계의 몫이다).

**수신 바이트**: 7,474 · **종료 코드**: `0` · **job**: `review-msse9ol1-zamzxx`

````text
# Codex Adversarial Review

Target: branch diff against 56a70c1
Verdict: needs-attention

출하 보류. 직접 공격 입력으로 개인정보 로그 억제, style 자기채점, case-floor 도달성 우회를 재현했다. 검토 결과 R-2 중첩 깊이·독립 대조, 금지된 LOG-BODY 휴리스틱 삭제, markable 배정, 빈 표기 인덱스 항등성, 억제 전건 리포트, 전수 스캔 exit 0과 표기 7/예산 7, 7개 Kotlin 산출물 선언, 현재 151개 floor와 보류 2건은 별도 지적이 없다.

Findings:
- [high] 표기 하나가 같은 논리 줄의 다른 개인정보 로그까지 억제한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:979-993)
  `_is_candidate`는 호출별 결과가 아니라 논리 줄 전체에 대한 Boolean 하나를 반환하며 첫 후보에서 종료한다. 이후 스캐너는 `(규칙, 파일, 시작 물리 줄)` 적중 하나만 만들므로 억제도 호출이 아니라 줄 전체에 적용된다. `안전한 count 로그; draft.value 로그 // privacy-allow: LOG-BODY — count only` 탐침은 `marker_problems=[]`, `kept={}`로 끝나 뒤쪽 개인정보 로그까지 사라졌다. R-1을 표기 층에서 그대로 재도입하며 실제 본문 로그가 CI에 나타나지 않는다.
  Recommendation: 적중을 호출별 위치까지 분리하고 표기를 그 호출 하나에만 결합하라. 최소한 한 물리·논리 줄에 같은 규칙 호출이 여러 개면 표기 억제를 금지하는 회귀 탐침을 추가하라.
- [high] 151개 case floor 중 export 12건은 CI에서 전혀 검사되지 않는다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:976-1013)
  `case_floor_problems`는 전달된 pair만 순회하고 해당 도메인의 floor가 없으면 그냥 `continue`한다. CI는 현재 선언된 7개 도메인만 비교기에 넘기지만 정본과 floor에는 여덟 번째 `export`가 있으며 12개 ID가 있다. 실제 pair 수집에서 `export_reached=False`였으므로 export 생성기·fixture에서 ID를 삭제하거나 개명해도 floor와 정본 대조 모두 CI에 도달하지 않는다. 더 나아가 floor를 `bogus/placeholder` 한 줄로 바꾼 탐침도 전체 8개 pair에 대해 문제 0건이었다.
  Recommendation: actual 비교 범위와 분리된 무조건 실행 구조 게이트를 두어 모든 BUILDERS 도메인의 fixture와 floor를 대조하라. floor의 모든 도메인이 정본에 존재하고 각 정본 도메인에 비어 있지 않은 floor가 있는지도 강제하라.
- [high] style 생산자가 문장을 버리면 길이·쉼표 정책이 모두 통과한다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:701-731)
  독립 유도기는 fixture 입력이 아니라 Kotlin 산출물이 스스로 보고한 `sentences`를 기준으로 기대 위반 목록을 만든다. 51자 입력에 `sentences=[]`, `length_violations=[]`, `comma_violations=[]`를 준 탐침은 단언 실패 0건이었다. 따라서 문장 분리기가 일부 또는 전부를 잃는 결함이 생기면 위반 대상도 함께 사라져 길이·쉼표 정책이 통과한다. 참고 원장을 갱신하면 값 갈림까지 기록으로 흡수되어 요구사항 차단축이 남지 않는다.
  Recommendation: 최소한 `sentences`가 비어 있지 않은 입력을 손실 없이 덮는다는 독립 불변식을 먼저 강제하라. 고정 fixture에서는 독립적으로 선언한 문장 집합 또는 입력에서 유도한 검증 가능한 분할을 기준으로 위반 목록을 계산하라.
- [high] 큐레이션 사전은 표제어만 검사하고 쉬운 말 값은 전부 무시한다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:630-647)
  `contains_all`의 dict 분기는 required 항목이 키로 존재하는지만 검사한다. style-tables 생성기도 `DIFFICULT_WORD_REPLACEMENTS`의 키 목록만 required로 기록한다. 실제 탐침에서 `금일→어제`, `접수→버려라`처럼 값을 훼손해도 단언 실패가 없었다. 이 값들은 보정 프롬프트와 뜻풀이 충돌 검사에 사용되므로 잘못된 설명이나 의미 왜곡이 제품에 들어가도 요구사항 게이트는 통과한다.
  Recommendation: dict용 `contains_all`은 필수 key-value 쌍을 비교하되 추가 키만 허용하도록 바꾸고, 생성기는 전체 필수 매핑을 전달하라. 대표값 몇 개를 훼손하는 음성 대조도 추가하라.
- [medium] pending 도메인은 옛 known_gap 마이그레이션 가드를 건너뛴다 (.claude/skills/python-kotlin-parity/scripts/compare_parity.py:1080-1105)
  `spec_shape_problems`는 `pending_spec`이면 케이스를 순회하기 전에 반환하므로 `verdict_pending_problems`가 실행되지 않는다. 현재 `export`가 바로 pending 도메인이다. export 케이스에 `known_gap`을 삽입한 탐침은 `structural_problems=[]`였지만 같은 케이스를 가드에 직접 넣으면 거부됐다. CI의 7도메인 필터까지 겹쳐 옛 자유 문자열이 이 도메인에 다시 들어와도 아무 신호가 없다.
  Recommendation: pending 여부와 무관한 레거시 키·보류 메타데이터 검증을 조기 반환 앞으로 옮기고, 모든 fixture 파일을 대상으로 상시 실행하라.
- [medium] 저장소에 이미 있는 LoggerFactory 체인 호출이 로그 탐지 범위 밖이다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:165-179)
  LOG-BODY 패턴은 `log/_log/logger/_logger.<level>(...)`만 인식한다. `LOG.info(...)`, kotlin-logging의 `logger.info { ... }`, SLF4J fluent 호출은 잡지 못한다. 특히 저장소에는 이미 `ContractErrorReportValve.kt:104-106`의 `LoggerFactory.getLogger(...).debug(...)` 체인 형태가 있지만 reach 테스트도 이름 붙은 수신자의 직접 괄호 호출만 찾아 이 실물을 보지 않는다. 현재 인자는 타입명이라 안전하지만 같은 관용구에 본문 인자가 추가되면 게이트 도달은 0이다.
  Recommendation: 대문자 수신자, LoggerFactory 체인, kotlin-logging 람다, SLF4J fluent 형태를 탐지 모델과 독립 reach 탐침 양쪽에 추가하라. 현재 체인 호출도 저장소 실물 대조군으로 고정하라.
- [medium] 보이지 않는 문자 하나로 필수 표기 사유를 우회할 수 있다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:143-160)
  사유는 단순히 `.strip()` 후 비었는지만 검사한다. `privacy-allow: LOG-BODY — U+200B` 탐침은 화면상 사유가 비어 있지만 `marker_problems=[]`였고 LOG-BODY 적중을 완전히 억제했다. `//` 같은 주석 기호만으로도 같은 검사를 통과한다. 따라서 호출 지점 면제에 검토 가능한 근거를 남긴다는 방어가 형식적으로만 존재한다.
  Recommendation: format/control/zero-width 문자를 제거한 뒤 사람이 볼 수 있는 문자나 영숫자가 남는지 검증하고, 주석 구분자·구두점만인 사유도 거부하는 회귀 테스트를 추가하라.

Next steps:
- 호출별 억제와 표기 사유 공격 탐침을 추가한 뒤 개인정보 스캐너 전수를 다시 실행한다.
- actual 생성과 무관한 전체 fixture/floor/legacy-marker 구조 게이트를 CI에 먼저 배선한다.
- style 문장 보존과 큐레이션 key-value 단언을 독립 기준으로 고친 뒤 Kotlin parity 하네스와 비교기를 재실행한다.
````

---

## 4. 정리 (가공) — **판정이 아니다**

> 이 구획은 §3 원문을 목록화한 것이다. **옳고 그름·심각도 재부여·중복 병합·오탐 판정을
> 하지 않는다.** 심각도 칸은 codex 가 붙인 `[high]`/`[medium]` 라벨을 그대로 옮긴 것이고,
> 파일·라인은 codex 가 준 값을 **다시 세지 않고 그대로** 옮겼다. 축 대응은 focus text 의
> 어느 묶음에 위치상 걸리는지를 적은 **사무적 배치**이며, 지적의 타당성 판단이 아니다.

### 4.1 지적 7건

| # | 심각도(codex) | 지적 (codex 표제 그대로) | 근거 파일·라인 (codex 제공) | focus 축 |
|---|---|---|---|---|
| C-1 | `[high]` | 표기 하나가 같은 논리 줄의 다른 개인정보 로그까지 억제한다 | `scan_privacy_invariants.py:979-993` | 축 1 (d)(f) · 축 2 층 분리 |
| C-2 | `[high]` | 151개 case floor 중 export 12건은 CI에서 전혀 검사되지 않는다 | `compare_parity.py:976-1013` | 축 4 · §4.6 도달 |
| C-3 | `[high]` | style 생산자가 문장을 버리면 길이·쉼표 정책이 모두 통과한다 | `compare_parity.py:701-731` | 축 3 style · §4.6 자기채점 |
| C-4 | `[high]` | 큐레이션 사전은 표제어만 검사하고 쉬운 말 값은 전부 무시한다 | `compare_parity.py:630-647` | 축 3 style-tables |
| C-5 | `[medium]` | pending 도메인은 옛 known_gap 마이그레이션 가드를 건너뛴다 | `compare_parity.py:1080-1105` | 축 4 (d) · 축 1 R-4 |
| C-6 | `[medium]` | 저장소에 이미 있는 LoggerFactory 체인 호출이 로그 탐지 범위 밖이다 | `scan_privacy_invariants.py:165-179` (+ `ContractErrorReportValve.kt:104-106`) | 축 1 R-3 · §4.6 도달 |
| C-7 | `[medium]` | 보이지 않는 문자 하나로 필수 표기 사유를 우회할 수 있다 | `scan_privacy_invariants.py:143-160` | 축 1 (e) · 축 2 방어 c |

**codex 가 직접 실행한 재현 탐침** (원문에 적힌 것만 옮긴다):

| # | codex 가 보고한 탐침과 결과 |
|---|---|
| C-1 | `안전한 count 로그; draft.value 로그 // privacy-allow: LOG-BODY — count only` → `marker_problems=[]`, `kept={}` |
| C-2 | 실제 pair 수집에서 `export_reached=False` / floor 를 `bogus/placeholder` 한 줄로 바꾼 탐침 → 8개 pair 전체 문제 0건 |
| C-3 | 51자 입력에 `sentences=[]`, `length_violations=[]`, `comma_violations=[]` → 단언 실패 0건 |
| C-4 | `금일→어제`, `접수→버려라` 로 값 훼손 → 단언 실패 없음 |
| C-5 | export 케이스에 `known_gap` 삽입 → `structural_problems=[]` / 같은 케이스를 가드에 직접 넣으면 거부 |
| C-7 | `privacy-allow: LOG-BODY — U+200B` → `marker_problems=[]`, LOG-BODY 적중 완전 억제 |

### 4.2 codex 가 **명시적으로 "별도 지적 없음"** 이라고 적은 항목 (8건)

> §4.6 은 "codex 가 이 축에서 아무것도 지적하지 않았으면 그 사실을 그대로 기록한다"를
> 요구한다. 아래는 codex 요약 6행에 **명시된** 무지적 항목이며, **침묵과 구분된다.**

1. R-2 중첩 깊이 · 독립 대조
2. 금지된 LOG-BODY 휴리스틱 삭제
3. `markable` 배정
4. 빈 표기 인덱스 항등성 (`suppress(hits, ∅) == hits`)
5. 억제 전건 리포트
6. 전수 스캔 exit 0 과 **표기 7 / 예산 7**
7. 7개 Kotlin 산출물 선언
8. 현재 151개 floor 와 보류 2건

### 4.3 codex 가 **아무 말도 하지 않은** 항목 — 침묵 (Claude 가 대신 채우지 않는다)

> focus text 로 명시적으로 물었으나 §3 원문 어디에도 언급이 없는 항목이다. "지적 없음"이
> **아니라** "다뤄지지 않음"이다. 둘을 섞으면 교차 대조에서 합의로 오독된다.

| focus 축 질문 | 원문 내 언급 |
|---|---|
| 축 2 — **예산 3 → 7 이탈의 정당성** (설계 문언 "이관 시점 실제 개수 + 0", 지목 실물 3줄) | 요약이 "표기 7/예산 7 은 별도 지적 없음"으로 **정합성만** 적었다. **설계 문언(초기값 3)과의 차이 자체는 다루지 않음** |
| 축 1 (b) — 예산 상한을 같은 커밋에서 올리는 경로 | 언급 없음 |
| 축 1 (c) — `markable=False` 목록 축소 경로 | 배정 자체는 무지적(§4.2-3). **축소 경로**는 언급 없음 |
| 축 2 — refine 판별식이 코드에 못박혔는지 · `SECRET-LITERAL` 이 판별식을 통과하는지 | 언급 없음 |
| 축 3 — **prompts 왕복(마스킹 → 되돌린 원문 재마스킹)** 이 fixture 기대와 프로덕션 순서를 같게 만드는가 | 언급 없음 |
| 축 3 — 고정 `<ID>` 가 프로덕션 경로로 새는가 | 언급 없음 |
| 축 3 — **`StyleRuleKind` enum 이 프롬프트 문안·계약을 바꾸지 않았다는 주장** | 언급 없음 |
| 축 3 — **style-tables 집합 순서 갈림 1건** 처리의 적정성 | 언급 없음 (C-4 는 값 검사 부재로 대상이 다르다) |
| 축 4 (b) — `!deferred` 꼬리표만 지우는 경로 | 언급 없음 (보류 2건은 무지적으로만 적힘) |
| 축 4 (c) — `--only` 예외가 다른 실행 경로로 번지는가 | 언급 없음 |
| 축 4 (e) — 단언을 무르게 바꾸는 편집을 받는다는 fixture 정본 대조가 CI 에서 도는가 | C-2 가 **export 한정**으로 정본 대조 미도달을 적었을 뿐, 이 질문 자체는 다루지 않음 |
| §4.6 — 장치를 제거하면 정확히 무엇이 깨지는가 (떼어도 안 깨지는 장치 지목) | 언급 없음 |
| §4.6 — 선언 파일 3종이 **빈 선언·0건에서 통과하지 않는지** | 언급 없음 |
| 축 1 R-1 — `finditer` 수정 자체의 충실도 | 독립 항목으로는 언급 없음. C-1 이 *"R-1을 표기 층에서 그대로 재도입"* 이라고 적으며 간접 참조 |

### 4.4 전제 확인 — **기계적 존재 검사만** (내용 판단 아님)

> codex 가 인용한 경로·수치가 실재하는지만 확인했다. **지적의 옳고 그름은 판단하지
> 않는다.** `codex-review` §7 이 "사실과 다른 전제로 보여도 삭제하지 않고 '전제 확인
> 필요'만 덧붙인다"고 정한 절차에 따른 것이며, 이번에는 **어긋난 전제가 발견되지 않았다.**

| 인용 | 검사 | 결과 |
|---|---|---|
| `scan_privacy_invariants.py:143-160`·`:165-179`·`:979-993` | 파일 존재 · 총 1240행 | 실재 · 인용 범위 모두 in-range |
| `compare_parity.py:630-647`·`:701-731`·`:976-1013`·`:1080-1105` | 파일 존재 · 총 2008행 | 실재 · 인용 범위 모두 in-range |
| `ContractErrorReportValve.kt:104-106` | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/` 에 존재 · 총 184행 | 실재 · in-range |
| "export … 12개 ID" | `.github/parity-case-floor.txt` 의 `^export/` 줄 수 | **12** — 일치 |
| "151개 case floor" | 주석·빈 줄 제외 엔트리 수 | **151** — 일치 |

**전제 확인 필요로 남기는 항목: 없음.**

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| codex CLI 호출 | **성공** — 1회차 exit 0, 재시도 없음 |
| 출력 잘림 | **없음** — `Next steps` 3항목까지 온전히 수신(7,474바이트). 중단 표지 없음 |
| 빈 리뷰 (exit 7) | 해당 없음 — 사전 대상 판정 `non-empty (변경 파일 42개)` |
| 도구 타임아웃 | 해당 없음 — `run_in_background` 로 11m 3s 완주 |
| 민감 데이터 유출 | **없음** — focus text 에 문서 본문·암호문·키·개인정보 미포함 (§1.3) |
| ⚠ codex 리뷰 누락 | **해당 없음** |

**리뷰 대상에서 제외된 것**: 작업 트리 untracked 3건(`.playwright-mcp/`, `.doc` 파일 2건).
`--base` 지정이므로 커밋된 변경만 대상이며, 이 3건은 이 배치의 산출물이 아니다.

---

## 6. 다음 단계 (프로토콜)

1. `migration-reviewer` 1차 산출물 `11_suppression-and-domains_migration-reviewer.md` 존재 확인
2. `migration-reviewer` **2차 호출**로 두 산출물을 `codex-review` §5 표에 대조해
   `11_suppression-and-domains_cross.md` 작성 — **2차에서 새 지적을 만들지 않는다**
3. Phase 종료 **판정**은 오케스트레이터 몫이다. 이 문서는 판정하지 않는다

이 에이전트는 코드를 수정하지 않았고, 다른 리뷰어의 결론을 참조하지 않았다.
