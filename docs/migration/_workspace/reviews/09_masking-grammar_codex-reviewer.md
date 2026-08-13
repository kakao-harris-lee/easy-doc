# 게이트 09 `masking-grammar` — codex 독립 리뷰 (1회차)

> 이 문서는 **codex 원문 보존이 목적**이다. §3이 무편집 원문이고, §4는 Claude가 만든 별도 구획이다.
> §4에서도 옳고 그름·심각도 재부여·오탐 판정을 하지 않는다. 교차 대조와 판정은 `migration-reviewer` 2차 호출과 리더의 몫이다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 회차 | **1회차** (`09_masking-grammar` 어간의 첫 codex 리뷰. 같은 어간 선행 파일 없음) |
| 어간 출처 | **리더가 1단계 호출에서 지정**(`09_masking-grammar`). 이 에이전트가 만든 슬러그가 아니다 |
| 실행 시각 | 2026-08-14 07:02:01 KST 시작 → 07:1x 완료 (**소요 약 9분**) |
| 종료 코드 | **`0`** — 리뷰 근거로 유효 |
| 판정 | `Verdict: needs-attention` / 본문 첫 줄 `NO-SHIP` |
| 모드 | `adversarial-review` (focus text 포함) |
| scope / base | `scope = auto(미지정)` / `base = 6d8e88c` — **base 지정 시 scope는 무시되고 branch diff로 간다** |
| job id | **`review-mss2cqg6-meyu76`** (session `c1d0e70e-2b70-4679-bfb5-dd5973b18438`) |
| codex 세션 | thread `019ffd25-886f-7660-b49d-6a926d36b0bb` |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 내림차순 자동 선택, **1.0.6**) |
| codex CLI | `codex-cli 0.147.0` / node v22.21.1 |
| 출력 크기 | 7,069 바이트 — **잘림 없음**(`Next steps` 4항까지 완결) |
| 재시도 | **없음** (1차 실행 성공) |

### 1.1 스크립트가 stderr에 찍은 대상 판정 두 줄 (원문)

```
codex-review: 리뷰 대상 = branch diff vs 6d8e88c
codex-review: 대상 판정 = non-empty (merge-base=6d8e88c44460, 변경 파일 32개 (branch 모드는 커밋된 변경만 센다))
```

### 1.2 실행 명령

```bash
.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 6d8e88c "$(cat <focus 파일>)"
```

헬퍼로 전개된 실제 명령(스크립트 stderr `실행 명령 =` 줄):

```
node /Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs \
  adversarial-review --base 6d8e88c '<§2의 focus 전문>'
```

focus text 전문은 §2에 그대로 옮겨 두었다.

### 1.3 대상 리비전 결속 — **HEAD가 이동했다 (요청 범위와 실제 리뷰 범위가 다르다)**

리더가 지정한 범위는 `6d8e88c..c61c94e`다. 그런데 세션 시작 시점 HEAD였던 `c61c94e`가 **리뷰 실행 전에 한 커밋 앞으로 이동**했다.

| 시각(KST) | 사건 |
|---|---|
| 06:58:11 | `c61c94e` 커밋 (리더가 지정한 범위의 끝) |
| **07:00:29** | **`6e80357` 커밋 — 이 에이전트가 만들지 않았다** |
| 07:02:01 | codex 리뷰 실행 시작 (이 시점 HEAD = `6e80357`) |

`--base <ref>`는 `merge-base(HEAD, <ref>)..HEAD`를 리뷰하므로, **실제 리뷰 대상은 `6d8e88c..6e80357`**이다 — 요청 범위 + `6e80357` 1커밋.

`6e80357`의 diff 전량:

```
docs/migration/_workspace/00_progress.md | 3 ++-
1 file changed, 2 insertions(+), 1 deletion(-)
```

- 변경 파일 수는 두 범위 모두 **32개로 동일**하다(`00_progress.md`가 이미 요청 범위 안에 있어 파일 집합이 늘지 않았다). 그래서 스크립트의 대상 판정 줄만으로는 두 범위를 구분할 수 없다 — 위 커밋 시각으로 확정했다.
- 초과분은 `docs/migration/_workspace/**` 산출물 1건이며, codex-review 스킬 §2.2의 리뷰 면제 항목에 해당한다. **실행 코드·하네스 스크립트·fixture·CI 설정은 요청 범위와 동일하다.**
- 이 사실을 판정으로 바꾸지 않는다. 초과분이 리뷰 결과에 영향을 주었는지는 기록하지 않고, 범위가 어긋났다는 사실만 남긴다.

**리뷰 종료 후 추가 이동** (리뷰 결과에는 영향 없음 — 스냅샷은 `6e80357`에서 고정됐다):

| 커밋 | 내용 | 리뷰 대상 코드 접촉 |
|---|---|---|
| `7052b43` | `09_masking-grammar_migration-reviewer.md` 신규 + `00_progress.md` 1줄 | **없음** (docs 2파일) |

`migration-reviewer` 1단계 산출물이 이 커밋으로 존재하게 됐다(1단계 완료 조건 충족). **이 에이전트는 그 파일을 읽지 않았다** — 병렬·독립 실행 규약상 상대 레인의 결론을 참조하지 않는다.

### 1.4 맥락 노출 — codex가 읽을 수 있었던 선행 레인 결론

리뷰 대상 diff **안에** 다음 문서가 포함돼 있다. codex는 저장소를 직접 읽으므로 이 문서들에 접근할 수 있었다.

- `docs/migration/_workspace/reviews/08_conversion-usecase_codex-reviewer.md`
- `docs/migration/_workspace/reviews/08_conversion-usecase_migration-reviewer.md`
- `docs/migration/_workspace/reviews/08_conversion-usecase_cross.md`
- `docs/migration/_workspace/07_privacy-gate_masking-verdicts.md` (판정 §4-ter~§4-sexies 전문)
- `docs/migration/_workspace/00_progress.md` (리더 판정 절)

즉 이번 회차의 codex는 **완전한 백지 상태가 아니다.** 직전 게이트의 Claude 레인 결론과 privacy-gate 판정문을 읽을 수 있는 조건에서 리뷰했다. 이는 diff 자체가 그 문서들의 변경을 포함하기 때문에 발생한 구조적 조건이며 배제할 수 없었다. 독립성 가중치를 어떻게 볼지는 교차 대조 단계의 판단 사항이므로 여기서는 사실만 적는다. (focus text에는 *"문서(docs/\*\*)는 주장의 출처일 뿐 근거가 아니다 — 근거는 코드와 CI 설정이다"*를 넣어 두었다. 전문은 §2.)

### 1.5 제공한 맥락 목록

focus text로 주입한 것(§2 전문):

- 재개발 판정 기준(요구사항 충족, Python 호환 불요), 이번 diff가 "차단 해소 주장 배치"라는 사실
- 불변식 4종: 마스킹 선행 / 마스킹 2종 범주와 누락·과잉 양방향 결함 / core의 Spring·DB 비의존 / 문서당 최대 2회 호출
- 축 1~4의 **대상 파일 경로**와 **선언 내용**(SEP 문법 정의, 성별코드 코드포인트 판정, VT·FF 제외, 69케이스·선언 하한, 논리 줄·refine 훅·루트 부재, 빈 본문 보존·생성 지점 봉쇄)
- 축 5: 선언 범위 대 실제 도달 (codex-review 스킬 §4.6 — 게이트를 세우거나 넓히는 변경이므로 필수 포함)

**주지 않은 것**: Claude·privacy-gate가 이번 배치에 대해 내린 결론, 의심 지점, 예상 결함. focus는 "이것이 선언됐다 / 이것을 확인하라"는 형태로만 썼다.

### 1.6 민감 데이터 점검

호출 전 fixture를 검사했다. `parity/fixtures/masking/masking.json`의 카드번호 후보 5종은 **전부 Luhn 무효**이고 RRN 후보 7종은 순차 숫자 패턴(`900101-1234567`, `1234567890123` 등)이다. 실제 암호문·키·사용자 문서·개인정보는 대상에 없다. 합성 값 치환이 필요한 자리는 없었다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```text
이 저장소는 Python/FastAPI 제품을 Kotlin/Spring Boot로 재개발하는 중이다. 판정 기준은 "Python과 같은 값이 나오는가"가 아니라 "요구사항·정책을 충족하는가"다. Python 호환은 요구하지 않는다. 이번 diff는 직전 리뷰 게이트가 낸 차단 지적을 닫겠다고 주장하는 배치다. 그 주장이 실제로 참인지, 그리고 닫으면서 새로 연 구멍이 있는지를 본다. 문서(docs/**)는 주장의 출처일 뿐 근거가 아니다 — 근거는 코드와 CI 설정이다.

지켜야 하는 불변식(단정문):
- 사용자 문서 텍스트는 마스킹 파이프라인을 통과한 뒤에만 LLM provider로 전달된다. 로그·예외 메시지·toString·메트릭에는 문서 id·길이·상태만 남는다.
- 마스킹 대상은 주민등록번호(외국인등록번호 포함)·카드번호 2종이다. 누락은 개인정보 유출이고, 과잉 마스킹은 접수번호 같은 필수 정보를 파괴한다. 양쪽 다 결함이며 한쪽을 고치며 다른 쪽을 여는 것도 결함이다.
- core 모듈은 Spring·DB에 의존하지 않는다.
- 문서 한 건당 LLM 호출은 변환 1회 + 조건부 보정 1회로 최대 2회다.

축 1 — 마스킹 구분자 문법의 경계
대상: backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt, backend-kotlin/core/src/main/kotlin/kr/easydoc/core/text/UnicodeRegex.kt
이 배치는 구분자 문법을 다음 하나로 동결했다고 선언한다:
    SEP := (?: SPACE? HYPHEN SPACE? | SPACE? )
    SPACE = 현행 SPACE_CHARS 상수, HYPHEN = 현행 HYPHEN_CHARS 상수
    RRN = 6자리 SEP 성별코드1자리+6자리, CARD = 4자리 SEP 4자리 SEP 4자리 SEP 4자리
선언된 성질은 "SEP은 최대 3문자로 유한하다"와 "RRN과 CARD가 같은 상수 하나를 공유한다"이다.
정규식을 직접 읽어 구현이 이 선언과 일치하는지 확인하고, 구분자 자리에 올 수 있는 문자열 조합을 전수로 열거하라. 나누어 답하라 — (a) 문법이 받아들이는데 받아들이면 안 되는 조합, (b) 문법이 거부하는데 현실의 정당한 표기인 조합. 하이픈이 연속하는 경우, 공백과 하이픈이 번갈아 반복되는 경우, 구분자 자리에 보이지 않는 문자(ZWSP·SHY 등)가 오는 경우, 그리고 탐색 뷰에서 접히는(제거되는) 문자와 구분자 문법이 겹칠 때 무엇이 결합되는지를 각각 따져라. 뷰에서 문자가 제거된 뒤 좌표를 원문으로 되돌리는 매핑이 어긋나 엉뚱한 구간을 가리는 경로도 본다.
성별코드 판정이 코드포인트 기준으로 바뀌었다고 선언한다. BMP 밖 숫자(보충 평면)가 자릿수로 세어지거나 성별코드로 받아들여지는 경로가 남았는지, 그리고 보충 평면 숫자가 구분자 자리나 6자리·4자리 그룹 안에 올 때 어떻게 판정되는지 확인하라.
UnicodeRegex의 보이지 않는 문자 범위에서 VT(U+000B)·FF(U+000C)를 뺐다고 선언한다. 그 결과 줄·페이지 경계를 넘어 숫자가 결합되는 경로가 남았는지, 반대로 정당한 표기가 깨지는지 확인하라.
위반 시: 주민번호·카드번호가 마스킹 없이 LLM으로 나가거나, 정상 문서의 필수 숫자가 자리표시자로 파괴된다.

축 2 — fixture와 선언 하한의 자기참조
대상: parity/fixtures/masking/masking.json, .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py, .github/parity-declared-floor.txt, .github/workflows/ci.yml
이 배치는 masking fixture를 69케이스로 늘리고 도메인별 최소 케이스 수를 .github/parity-declared-floor.txt에 "선언 하한"으로 고정했다고 주장한다.
물을 것: 이 하한 검사가 대조하는 기준이 검사 대상 자신에게서 나오는가? fixture 생성기와 기대값이 같은 출처에서 나와 항상 통과하는 자리가 있는가? 하한 파일과 fixture를 함께 줄이면 검사가 통과하는가 — 통과한다면 그 탈출구가 하한 장치의 목적을 무력화하지 않는가? 케이스를 지웠을 때 CI가 실제로 빨개지는 경로를 워크플로 파일에서 짚어라. 케이스 수만 세고 케이스의 내용이 무의미해져도(예: 기대값이 전부 동일, 입력이 전부 중복) 검사가 통과하는가? 검출을 실증한다는 케이스가 실제로 무엇을 실증하는지, 통과 조건이 구현을 그대로 복사한 것은 아닌지 확인하라.
위반 시: 커버리지가 조용히 줄어도 게이트가 초록으로 남는다.

축 3 — 스캐너의 선언 범위 대 실제 도달
대상: .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py, tests/test_privacy_scanner.py, tests/fixtures/privacy-scanner-probes/MultilineProbe.kt, .github/workflows/ci.yml
이 스캐너는 평문 로그·마스킹 우회를 잡는 게이트다. 이 배치는 (a) 다중 줄 호출을 "논리 줄"로 결합해 보고, (b) 안전 멤버 접근을 걸러 내는 refine 훅을 넣고, (c) 선언한 스캔 루트가 없으면 실패하게 했다고 주장한다.
물을 것: 논리 줄 결합에 상한이 있다면 그 상한을 넘는 호출은 검사되지 않고 조용히 통과하는가? 문자열 리터럴·주석·중첩 괄호·이스케이프·raw 문자열·문자열 템플릿이 결합 판정을 속이는가? refine 훅의 접근 사슬 매칭이 시작과 끝을 모두 고정하는가, 아니면 매칭 이후 잔여 사슬이 검사되지 않는가? 안전 멤버 목록에 위험 멤버를 숨길 수 있는가(이름 겹침, 대소문자, 접두어 일치, 유니코드 식별자)? 위험한 값이 지역 변수·확장 함수·연산자·문자열 템플릿·구조분해를 거치면 탐지를 벗어나는가? 이 스캐너의 비영 종료 코드가 CI에서 실제로 잡 실패가 되는가 — continue-on-error, || true, 파이프, 리다이렉트로 삼켜지는 자리를 워크플로에서 짚어라. 스캔 루트는 존재하나 그 아래 대상 파일이 0개인 경우는 어떻게 판정되는가?
위반 시: 문서 본문이 로그로 나가는 코드가 게이트를 통과한다.

축 4 — provider 결과 보존과 생성 지점 봉쇄
대상: backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt, backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/llm/AnthropicProviderResponseTest.kt, backend-kotlin/application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt, backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt
선언: 응답 본문이 비어 있어도 종료 사유(finish reason)와 토큰 사용량을 잃지 않는다. 그리고 마스킹 우회를 막는 타입의 생성 지점이 허용목록으로 봉쇄돼 있다.
물을 것: 빈 본문, 최대 토큰 도달, 형식 오류 응답, 필드 누락, null 사용량에서 종료 사유와 사용량이 유실되거나 0으로 대체되는 경로가 있는가? 그 검증이 실제 JSON 역직렬화 계층을 통과하는가, 아니면 이미 파싱된 객체를 손으로 만들어 확인하는 테스트라 실제 파싱 버그를 못 잡는가? 사용량이 유실되면 문서당 최대 2회 회계가 어떻게 틀어지는가?
생성 지점 허용목록 테스트가 "파일 몇 개"가 아니라 "호출 지점 개수와 identity"로 세는가? 별칭 import, 생성자 참조(::), 역직렬화, 리플렉션, copy(), 복사 생성자, 하위 클래스, 테스트 소스셋으로 허용목록을 우회하는 경로가 있는가? 감시 대상 타입 4종이 각각 독립 단언을 받는가, 아니면 하나가 통과하면 나머지가 검사되지 않는 구조인가? 이 테스트를 지우거나 이름을 바꾸면 CI가 실패하는가?
위반 시: 호출 상한 회계가 틀어지거나, 마스킹되지 않은 텍스트를 담은 타입이 만들어진다.

축 5 (앞의 넷을 가로지른다) — 선언한 범위 대 실제 도달
이 diff는 게이트·불변식·규칙을 새로 세우거나 넓힌다. 각 장치마다 답하라: 이 장치를 지금 실행하는 경로가 실제로 있는가 — 로컬에서만 도는가, CI 잡에 배선됐는가, 아무 데서도 안 도는가? CI 설정 파일에서 실행 경로를 직접 짚어라. 이 장치를 통째로 제거하면 정확히 어떤 테스트가 깨지는가 — 떼어도 아무것도 깨지지 않는 장치를 지목하라. 성공 판정이 대리 지표로 대체된 자리를 찾아라(종료 코드 0을 "검토했다"로, 지적 0건을 "위반 없음"으로, 테스트 통과를 "그 경로가 실제로 실행됐다"로). "전부"·"모든"·"항상"이라고 선언한 범위 중 강제 수단이 닿지 않는 경로를 짚어라. 그리고 한 건의 오탐을 막으려고 넣은 예외·억제·무시 패턴이 근거보다 넓어 앞으로 같은 종류의 진짜 위반을 보이지 않게 만드는 자리를 찾아라.

보고 형식: 지적마다 파일 경로와 라인, 재현 입력 또는 실행 경로, 위반 시 결과를 적어라. 확신도가 낮은 항목은 낮다고 표시하되 생략하지 마라. 지적할 것이 없는 축은 "없음"이라고 명시하라.
```

---

## 3. codex 원문 출력 (무편집)

> 아래는 `codex-review.sh`의 표준 출력 전문이다. 줄바꿈·표기·용어를 포함해 **한 글자도 바꾸지 않았다.** Claude의 주석은 이 구획에 한 줄도 없다.

```
# Codex Adversarial Review

Target: branch diff against 6d8e88c
Verdict: needs-attention

NO-SHIP. 축 1의 literal SEP는 정확히 301개 조합(빈 값 1, SPACE/HYPHEN 각 6, SPACE-HYPHEN·HYPHEN-SPACE 각 36, SPACE-HYPHEN-SPACE 216)을 허용하며 연속 하이픈과 반복 교대는 거부한다. 그러나 탐색 뷰와 결합하면 상한·숫자 경계가 무너진다. 근거로 확정할 현실 표기 거부, 보충평면 숫자 계수, 좌표 환원, VT/FF 비결합 자체의 추가 결함은 없었다. 축 2~4에는 각각 fixture 하한 자기참조, 스캐너 fail-open, provider 회계 및 provenance 봉쇄 결함이 있다. core의 Spring/DB 독립성과 직선형 최대 2-call 예산에는 추가 지적이 없다. 관련 장치는 CI에 배선돼 있지만 아래 mutation을 실패시키지 못한다.

Findings:
- [high] 탐색 뷰가 SEP 상한과 숫자 경계를 우회한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:509-560)
  SEP 자체는 최대 3문자지만 searchView가 ZWSP·SHY를 개수 제한 없이 제거한 뒤 뷰 매치를 원문 매치와 합집합한다. 현재 빌드에서 `접수 900101<ZWSP><ZWSP>1234567 끝`과 SHY 2개 변형 모두 RRN 1건으로 마스킹됐다. 또한 `1<ZWSP>900101-1234567`은 접힌 뷰에서는 선행 숫자 때문에 거부되지만 원문 경로가 suffix를 마스킹한다. 따라서 선언상 거부돼야 할 긴 raw separator나 더 긴 숫자열 내부가 자리표시자로 파괴될 수 있다. 좌표 배열 자체가 아니라 두 검색 의미를 합치는 방식의 결함이다.
  Recommendation: 접힌 뷰를 단일 판정 기준으로 삼고 제거 문자 인접 전후의 실제 숫자 경계를 검증하라. 원문으로 환원한 separator에도 총 길이 상한을 적용하고, ZWSP/SHY 2개 및 선행 숫자+ZWSP를 음성 테스트로 추가하라.
- [high] 논리 줄 스캐너가 긴 호출과 raw string에서 평문 로그를 놓친다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:510-579)
  MAX_LOGICAL_LINE_SPAN=40을 채우면 열린 호출을 실패 처리하지 않고 다음 줄을 새 논리 줄로 시작한다. 41번째 줄에 `draft.value`를 둔 `logger.info(` 호출은 논리 줄 시작 `[1,41,42]`, LOG-BODY 적중 `[]`로 재현됐다. `_depth_after`의 quote 상태도 매 물리 줄마다 초기화되어 Kotlin raw string 안의 `)`가 호출을 조기에 닫는다. `logger.info(""" ... ) ... ${draft.value} ... """)` 탐침 역시 적중 `[]`였다. CI는 스캐너 종료 코드를 직접 사용하므로 이 코드가 추가돼도 후보 0건, exit 0으로 통과하고 문서 본문이 로그로 나간다.
  Recommendation: 문자열·주석 상태를 물리 줄 사이에 유지하는 lexer로 논리 호출을 구성하라. 40줄에 도달했는데 괄호가 열려 있으면 BLOCK하거나 호출 prefix를 유지한 중첩 창으로 계속 검사하고, 두 재현 입력을 CI에 고정하라.
- [high] provenance 생성 지점 가드는 감시 타입과 호출 identity를 자기 allowlist에서 얻는다 (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt:195-235)
  creationSites는 감시 대상을 독립 상수가 아니라 `ALLOWED.keys`에서 가져온다. 따라서 ALLOWED에서 ReviewedBody 항목을 삭제하면 기존 직접 생성도 전부 검사 대상에서 사라지고 각 테스트의 루프도 이를 단언하지 않는다. 또한 개수는 생성자 호출 수가 아니라 `createsType`이 true인 물리 줄 수여서 한 줄에 호출을 추가해도 증가하지 않으며, 허용된 `ModelDraft(draft)`를 `ModelDraft(rawSource)`로 교체해도 identity와 인자를 보지 않는다. STRING_LITERAL 제거는 실행되는 Kotlin 문자열 템플릿 안의 생성도 숨긴다. CI가 테스트 클래스를 명시 실행해도 이 자기참조 mutation은 초록이고, 가짜 ReviewedBody가 개인정보 복원을 허용하거나 raw ModelDraft가 보정 provider로 나갈 수 있다.
  Recommendation: ALLOWED와 독립된 EXPECTED_TYPES를 두고 정확히 ModelDraft·ReviewedBody를 각각 단언하라. Kotlin AST/PSI로 호출별 위치와 허용된 인자 provenance를 검사하고 문자열 템플릿 표현식도 보존하라. allowlist 타입 삭제, 같은 줄 추가 호출, 허용 지점의 raw-source 치환 mutation을 모두 실패 테스트로 고정하라.
- [medium] 선언 하한은 케이스 수나 identity를 전혀 고정하지 않는다 (.github/workflows/ci.yml:359-386)
  CI의 declared-floor 검사는 도메인 이름 집합만 `comm`으로 비교한다. masking이 남아 있으면 69건에서 2건으로 줄어도 통과한다. 실제로 builder를 `plain`과 `rrn-hyphen` 두 건으로 바꾼 메모리 mutation에서 `structural_problems=[]`, `provenance_problems=[]`였고, 69개 ID를 단 두 입력의 복제로 채운 경우도 두 검사가 모두 비었다. Kotlin 생산자도 fixture 입력을 그대로 순회하므로 generator·fixture·reference ledger를 함께 갱신하면 축소된 범위가 CI의 새 정본이 된다. 현재 파일은 최소 케이스 수가 아니라 두 도메인 이름만 보존한다.
  Recommendation: 도메인별 최소 개수뿐 아니라 독립적으로 소유되는 필수 case ID와 입력/단언 fingerprint를 CI에 고정하라. 입력 중복과 단언 다양성도 검사하고, generator와 fixture를 함께 줄이는 mutation이 실패하는 회귀 테스트를 추가하라.
- [medium] 누락되거나 null인 provider 사용량이 정상적인 0으로 대체된다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:252-263)
  실제 JSON 계층은 통과하지만 `usage`, `input_tokens`, `output_tokens`가 누락·null·비정수여도 `asInt(0)`으로 수용한다. 예를 들어 model/content/`stop_reason=max_tokens`만 있고 `usage:null`인 HTTP 200 응답은 절단 사실은 남지만 토큰은 0으로 보고된다. `stop_reason` 누락도 OTHER로 접혀 빈 응답의 실패 분류가 달라진다. 추가된 테스트는 항상 완전한 usage 객체를 보내므로 이 경로를 검증하지 않는다. 결과적으로 호출 수 1/2는 남아도 실패하거나 버린 보정 호출의 원가가 조용히 사라진다.
  Recommendation: 성공 응답의 content, stop_reason, usage 및 두 토큰 필드의 타입·비음수 값을 필수로 검증하고 결손은 LlmProviderException으로 실패시켜라. null·누락·문자열·음수 usage를 StubAnthropicServer JSON 테스트에 추가하라.

Next steps:
- effective raw separator와 접힌 숫자 경계의 정책을 먼저 확정하고 마스킹 음성 테스트를 추가한다.
- case ID·내용 fingerprint를 generator 밖의 독립 CI 기준에 고정한다.
- 스캐너 논리 줄 파서를 fail-closed로 교체한 뒤 두 우회 입력을 상시 탐침으로 둔다.
- provider 응답 schema와 provenance 감시 대상·호출 identity를 독립적으로 강제한다.
```

**원문 끝.** 이후는 Claude가 만든 구획이다.

---

## 4. 정리(가공)

> **이 구획은 codex 원문이 아니다.** 지적을 목록화하고 focus 축에 대응시킨 것뿐이며, 옳고 그름·심각도 재부여·오탐 판정·중복 병합을 하지 않았다. 심각도 표기는 **codex가 붙인 `[high]`/`[medium]`을 그대로 옮긴 것**이고, 이 하네스의 Critical①/②·Major 등급으로 번역하지 않았다 — 그 번역은 교차 대조 단계의 일이다.
> 파일 경로와 라인은 **codex가 준 값을 그대로** 옮겼다. 다시 세거나 검증하지 않았다.

### 4.1 지적 5건

| # | codex 심각도 | 요지 (codex 표현) | 근거 파일·라인 (codex 제시) | focus 축 |
|---|---|---|---|---|
| K-1 | `high` | 탐색 뷰가 SEP 상한과 숫자 경계를 우회한다 | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:509-560` | 축 1 |
| K-2 | `high` | 논리 줄 스캐너가 긴 호출과 raw string에서 평문 로그를 놓친다 | `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:510-579` | 축 3 |
| K-3 | `high` | provenance 생성 지점 가드는 감시 타입과 호출 identity를 자기 allowlist에서 얻는다 | `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt:195-235` | 축 4 (+축 5 자기참조) |
| K-4 | `medium` | 선언 하한은 케이스 수나 identity를 전혀 고정하지 않는다 | `.github/workflows/ci.yml:359-386` | 축 2 |
| K-5 | `medium` | 누락되거나 null인 provider 사용량이 정상적인 0으로 대체된다 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:252-263` | 축 4 |

### 4.2 codex가 재현했다고 적은 실측 (원문에 적힌 것만 옮김)

codex가 스스로 실행했다고 서술한 재현 입력·결과다. 이 에이전트는 **재현을 시도하지 않았고 검증하지도 않았다.**

| # | codex가 적은 재현 | codex가 적은 결과 |
|---|---|---|
| K-1 | `접수 900101<ZWSP><ZWSP>1234567 끝` 및 SHY 2개 변형 | "모두 RRN 1건으로 마스킹됐다" |
| K-1 | `1<ZWSP>900101-1234567` | "접힌 뷰에서는 선행 숫자 때문에 거부되지만 원문 경로가 suffix를 마스킹한다" |
| K-2 | 41번째 줄에 `draft.value`를 둔 `logger.info(` 호출 | "논리 줄 시작 `[1,41,42]`, LOG-BODY 적중 `[]`" |
| K-2 | `logger.info(""" ... ) ... ${draft.value} ... """)` | "적중 `[]`" |
| K-4 | builder를 `plain`·`rrn-hyphen` 두 건으로 바꾼 메모리 mutation | "`structural_problems=[]`, `provenance_problems=[]`" |
| K-4 | 69개 ID를 단 두 입력의 복제로 채움 | "두 검사가 모두 비었다" |
| K-5 | `usage:null`, `stop_reason=max_tokens`인 HTTP 200 | "절단 사실은 남지만 토큰은 0으로 보고된다" |

### 4.3 codex가 **지적 없음**이라고 명시한 자리

focus의 *"지적할 것이 없는 축은 「없음」이라고 명시하라"*에 대한 응답이다. **codex가 명시한 것을 그대로 옮긴다. Claude가 대신 지적을 만들어 채우지 않았다**(codex-review 스킬 §7).

- 축 1 하위 4항: *"근거로 확정할 현실 표기 거부, 보충평면 숫자 계수, 좌표 환원, VT/FF 비결합 자체의 추가 결함은 없었다."*
  - 즉 리더 focus ①이 지목한 **보충 평면 숫자**·**좌표 환원**·**VT·FF 비결합**과 "정당한 표기를 거부하는가"에 대해 codex는 추가 결함을 찾지 못했다고 적었다.
- **연속 하이픈·반복 교대**: *"literal SEP는 정확히 301개 조합 … 을 허용하며 연속 하이픈과 반복 교대는 거부한다."* — 리더 focus ①이 예시로 든 "하이픈 2개 연속, 공백+하이픈+공백+하이픈"에 대한 codex의 서술이다. codex는 이를 결함으로 올리지 않았다.
- **core 모듈 경계**: *"core의 Spring/DB 독립성 … 에는 추가 지적이 없다."*
- **최대 2회 호출 예산**: *"직선형 최대 2-call 예산에는 추가 지적이 없다."*
- **CI 배선 여부 자체**: *"관련 장치는 CI에 배선돼 있지만 아래 mutation을 실패시키지 못한다."* — 축 5의 "어디서 도는가"에는 배선을 인정하고, "제거하면 무엇이 깨지는가"에서 결함을 올렸다.

### 4.4 focus 축 대비 응답 커버리지 (사실 대조만)

| focus 축 | codex 응답 | 비고 |
|---|---|---|
| 축 1 마스킹 문법 경계 | 지적 1건(K-1) + 4개 하위항 "없음" 명시 | 리더 focus ①의 SEP 경계 조합은 301개로 열거했다고 서술 |
| 축 2 fixture·선언 하한 | 지적 1건(K-4) | 리더 focus ②의 *"하한까지 함께 축소 = exit 0(설계된 탈출구)"*의 정당성 여부는 원문에 **직접 답이 없다.** 관련 서술은 *"generator·fixture·reference ledger를 함께 갱신하면 축소된 범위가 CI의 새 정본이 된다"* |
| 축 3 스캐너 잔여 표면 | 지적 1건(K-2) — 상한(40줄)·raw string | 리더 focus ③의 `(?!\.)` refine 훅 뒤 새 우회, exit 2/3 CI 배선에는 **별도 지적 없음**. exit 코드는 *"CI는 스캐너 종료 코드를 직접 사용하므로"*로 언급됨 |
| 축 4 C-08·C-05 재개방 | 지적 2건(K-3 provenance, K-5 usage) | 리더 focus ④의 "infrastructure 실제 파싱 계층 검증 여부"에 codex는 *"실제 JSON 계층은 통과하지만"*이라 적고 별개 결손(누락·null 수용)을 올렸다. "4표면 각각 독립 테스트인가"에는 K-3에서 `ALLOWED.keys` 파생을 지적 |
| 축 5 선언 범위 대 실제 도달 | 독립 항목으로 분리하지 않고 **K-2·K-3·K-4에 녹여 답함** | 세 건 모두 "장치가 mutation을 실패시키지 못한다" 형태. 종합 문단이 이를 명시 |

### 4.5 전제 확인 필요

원문의 서술 중 이 에이전트가 **확인하지 않은** 사실 주장이다. 삭제하지 않고 그대로 두며, 참·거짓 판정은 하지 않는다.

- K-1의 `Masking.kt:509-560` 라인 범위와 `searchView`의 합집합 동작
- K-2의 `MAX_LOGICAL_LINE_SPAN=40` 값과 `_depth_after`의 quote 상태 초기화 서술
- K-3의 `ALLOWED.keys` 파생, `createsType` 물리 줄 계수, `STRING_LITERAL` 제거 서술
- K-4의 `ci.yml:359-386` `comm` 비교 서술
- K-5의 `asInt(0)` 및 `stop_reason` 누락 시 `OTHER` 접힘 서술
- **K-3의 타입 열거**: codex는 *"정확히 ModelDraft·ReviewedBody를 각각 단언하라"*로 **2종**을 들었다. 리더 focus ④는 *"provenance 탐지기 4표면"*이라 했다. 두 수가 다른 이유는 원문에 없다

### 4.6 원문에 언급이 없는 요청 항목

리더가 이번 게이트 대상으로 든 것 중 codex 원문이 다루지 않은 것이다. **"문제 없음"이 아니라 "원문에 없음"으로만 기록한다.**

- `bed5300` value class 3종 `toString` (게이트 08의 판정 5 / C-06 검증 대기분)
- `3934f06` known_gap 소멸·표기 변형 축 (C-12 구현분 검증 대기분)
- `919198a`의 **빈 본문에서 finishReason 보존** 자체 — K-5는 인접하지만 *usage 결손 수용*을 지적한 것이고 빈 본문 보존의 성립 여부는 서술하지 않았다
- `5ac039f`의 `_SAFE_ACCESS` 종단 고정(`(?!\.)`) — K-2는 논리 줄 결합 쪽이고 refine 훅 종단은 다루지 않았다
- 경계 짝 fixture(`rrn-space-one`/`keeps-rrn-space-two`)와 검출 실증 5종의 개별 검토
- `8a756fc`의 C-18(클래스 표면 허용목록) 부분

---

## 5. 미실행·실패 항목

| 항목 | 상태 |
|---|---|
| codex 리뷰 실패·누락 | **해당 없음.** 1차 실행이 exit 0으로 정상 종료, 출력 완결(잘림 없음). §7 재시도 규약 미발동 |
| 출력 잘림 | 없음 — `Next steps` 4항까지 완결 |
| 회차 | 1회차. 같은 어간 선행 리뷰 없음 → 이전 지적 해소 여부를 맥락으로 주는 절차는 해당 없음 |
| 요청 범위와 실제 리뷰 범위 | **어긋남 1건** — `6e80357`(docs 1파일, +2/−1) 초과 포함. §1.3 |
| 리뷰 독립성 조건 | **완전한 백지 아님** — 선행 게이트 08의 세 산출물과 privacy-gate 판정문이 diff 안에 있어 codex가 읽을 수 있었다. §1.4 |
| codex 지적의 근거 검증 | **미수행 — 이 에이전트의 역할이 아니다.** §4.5의 전제 확인은 `migration-reviewer` 교차 대조와 리더의 몫 |
| codex 재현 실측의 제3자 재현 | **미수행.** §4.2의 재현 입력·결과는 codex 서술을 옮긴 것이며 이 에이전트가 돌려 보지 않았다 |
| 민감 데이터 유출 | 없음 — fixture 합성값 확인(§1.6). `privacy-gate` 차단 통보 수신 없음 |
| 코드 수정 | **없음.** 이 에이전트는 지적을 코드로 고치지 않는다 |

---

## 6. 다음 단계 (절차 안내 — 판정 아님)

1. `docs/migration/_workspace/reviews/09_masking-grammar_migration-reviewer.md`가 존재하는지 확인한다(1단계 완료 조건).
2. `migration-reviewer`를 **2차 호출**해 이 파일과 위 파일을 codex-review 스킬 §5 표로 대조하고 `09_masking-grammar_cross.md`를 만든다. 2차 호출에서는 새 지적을 만들지 않는다.
3. Phase 종료 판정은 오케스트레이터가 `..._cross.md`를 근거로 내린다. 이 문서는 그 판정의 입력이며 **자체로는 판정이 아니다.**
