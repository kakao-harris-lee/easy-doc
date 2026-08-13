# 08_conversion-usecase — codex 독립 리뷰 (1회차)

> 어간 `08_conversion-usecase`는 **리더가 1단계 호출에서 지정한 값**을 그대로 썼다. 이 에이전트가 임의로 만든 슬러그가 아니다.
> `docs/migration/_workspace/reviews/` 에 같은 어간의 이전 회차는 없다 — **1회차**다.
> 이 문서는 **판정하지 않는다.** codex 원문을 손실 없이 옮기고, 옳고 그름·심각도 재부여·중복 병합은 `migration-reviewer`의 교차 대조와 리더 판정에 넘긴다.

---

## 1. 호출 메타데이터

focus 축이 6개(리더 지정 ①~⑥)라 **한 번에 3~5개** 상한(`codex-review` 스킬 §3.5)을 넘겼다. 6개를 한 프롬프트에 넣으면 전부 얕게 보므로 **두 레인으로 나눠 각각 4축**을 물었다. 두 레인 모두 같은 base·같은 리비전을 대상으로 한다.

| 항목 | 레인 A | 레인 B |
|---|---|---|
| 담당 focus 축 | 리더 ①③ + 4대 예외·채택식 | 리더 ②④⑤⑥ |
| 리뷰 모드 | `adversarial` (→ 헬퍼 `adversarial-review`) | 동일 |
| **스크립트 종료 코드** | **`0`** — 리뷰 근거가 되는 값이다 | **`0`** — 동일 |
| job id | `review-msrnco2w-6242np` (completed) | `review-msrnd77a-m3xqzx` (completed) |
| codex session id | `019ffba5-0d9f-7a52-ba54-ed5f7fd7709d` | `019ffba5-6f11-70e3-b730-db1637c1b815` |
| 소요 | 6분 21초 | 12분 26초 |
| codex verdict | `needs-attention` | `needs-attention` |
| 출력 크기 | 4,885 바이트 | 6,580 바이트 |

공통 항목:

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-13 15:0x~15:15Z (UTC) |
| 대상 범위 | `f73879b..6d8e88c` (커밋 6개, 변경 파일 36개) |
| scope / base | scope 미지정(auto) / `--base f73879b` — **base가 주어지면 scope는 무시된다** |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 1.0.6, 스크립트가 최신 버전 자동 선택) |
| 실행 명령 | `.claude/skills/codex-review/scripts/codex-review.sh adversarial --base f73879b "<focus 전문 — §2>"` |
| 헬퍼 실제 명령 | `node <헬퍼경로> adversarial-review --base f73879b '<focus 전문>'` |

### 1.1 대상 리비전 결속 (리더 지시 항목)

병렬 레인이 리뷰 도중 커밋을 얹었는지 확인하기 위해 **디스패치 시점·중간·수거 시점 3회** 확인했다.

| 확인 시각 (UTC) | `git rev-parse HEAD` | `git merge-base HEAD f73879b` |
|---|---|---|
| 15:02 (디스패치 직전) | `6d8e88c4446070326731d59fa54f1b27d3002c04` | `f73879b2ae1b600d2a18b200ef02379e8aee7767` |
| 15:03 (중간) | `6d8e88c` | (동일) |
| 15:15 (수거 시점) | `6d8e88c4446070326731d59fa54f1b27d3002c04` | `f73879b2ae1b600d2a18b200ef02379e8aee7767` |

`merge-base`가 `f73879b` **자신**이고 HEAD가 `6d8e88c`이므로, 리뷰 대상은 요청받은 6개 커밋과 정확히 일치하며 **리뷰 실행·수거가 끝날 때까지** 고정됐다. **리뷰 구간 내 범위 드리프트 없음.**

#### 수거 이후 HEAD 이동 (사후 관측 — 리뷰 대상에는 영향 없음)

산출물 작성 중 4회차 확인에서 HEAD가 움직인 것을 관측했다. 사실만 적는다.

| 확인 시각 (UTC) | HEAD | 비고 |
|---|---|---|
| 15:19 (산출물 작성 후) | `bed5300019302577362b70bf11dd831e40e5e3c9` | 병렬 레인이 커밋을 얹었다 |

```
$ git log --oneline 6d8e88c..HEAD
bed5300 fix(kotlin): value class 3종의 toString 이 본문을 찍지 않게 한다

$ git merge-base --is-ancestor 6d8e88c HEAD   # → 0 (6d8e88c는 여전히 선형 조상)
```

**이 리뷰의 대상 범위는 `f73879b..6d8e88c`로 변함없이 확정돼 있다.** 두 codex 레인 모두 HEAD가 `6d8e88c`이던 시점의 작업 트리를 읽었고(§1.1의 15:02·15:03·15:15 확인 3회), `bed5300`은 **수거가 끝난 뒤** 올라왔으므로 리뷰 입력에 포함되지 않았다.

기록해 둘 사실 하나 — `bed5300`의 제목은 §3.2 **B-6**(`ModelDraft`·`ReviewedBody`의 기본 `toString`이 본문을 노출한다)이 가리키는 것과 같은 대상을 언급한다. **이 에이전트는 그 커밋이 B-6을 해소했는지 판정하지 않는다.** 커밋 내용을 열어 대조하지도 않았다 — 해소 여부 판정은 `migration-reviewer`의 교차 대조와 리더의 몫이고, 판정하려면 `bed5300` 이후를 대상으로 한 **새 회차**가 필요하다. 나머지 10건은 `bed5300`이 다루는 범위 밖으로 보이나 이 역시 판정이 아니라 관측이다.

대상 커밋 6개 (`git log f73879b..HEAD`):

```
6d8e88c chore(ci): 데이터 보호 불변식 스캔을 quality 잡에 배선하고 원장을 갱신한다
c505ee8 feat(kotlin): provider 조립을 infrastructure 가 소유한다 — 설정도 함께 내린다
23071f2 feat(parity): masking·repair-adoption Kotlin 생산자 배선 — 게이트를 처음 가동시킨다
ff4c323 feat(kotlin): application 변환 유스케이스 — 호출 상한·4대 예외·보정 채택
27fae53 fix(kotlin): 마스킹이 놓치던 표기 변형을 닫고 불변식에 상시 탐지기를 붙인다
d79a522 chore(parity): repair-adoption fixture 추출 — 변환 판정 로직 25건
```

### 1.2 스크립트 대상 판정 두 줄 (stderr 원문)

두 레인 모두 동일하게 출력됐다.

```
codex-review: 리뷰 대상 = branch diff vs f73879b
codex-review: 대상 판정 = non-empty (merge-base=f73879b2ae1b, 변경 파일 36개 (branch 모드는 커밋된 변경만 센다))
```

`--dry-run` 사전 확인에서도 같은 두 줄과 헬퍼 경로가 나왔다(종료 코드 6). 빈 대상(exit 7) 아님이 실행 전에 확정됐다.

### 1.3 제공한 맥락 목록

focus text 안에 다음을 **사실과 채점 기준**으로 실었다. 별도 첨부 파일은 없으며 codex는 저장소를 직접 읽었다.

- 재개발 판정 기준(Python 값 일치가 아니라 요구사항 충족), 예외는 정책 불변식
- CNV-01 호출 상한의 5개 세부(세는 단위=완성 요청 1건 / 루프 아님 / 위반 없으면 1건 / 전송 재전송 별도 계측 / 응답 없이 끝난 호출도 셈)
- CNV-02 4대 예외의 **1차 대 보정 비대칭**과 실패 코드에 본문·응답·파일명 금지
- CNV-04 채택식 두 축과 경계값(자리표시자 존재 여부 / 건수 같으면 채택 / 기각 시 1차 채택 / 토큰은 두 호출 합 / `missing_placeholders` 기준 본문)
- X-2 구현 지시 4항(`Character.digit` 값 판정 / 구분자 상수 확장 U+FF0D·U+2212·U+2013·U+00A0·U+3000 / `\s` 금지 / 마스킹 전 정규화 금지)와 그 근거(복원 파괴)
- 마스킹 범주 2종, 모듈 경계(core Spring 비의존, application infrastructure 비의존, api·worker runtimeOnly), 로그 금지 규칙
- 선언 범위 대 실제 도달 축(§4.6): 크롤링 누락 디렉터리, 근거보다 넓은 제외 패턴, 대상 0건 성공 종료, 음성 대조 가능성, 대리 지표 치환, 장치 제거 시 무엇이 깨지는가

**민감 데이터 미포함 확인**: focus text와 호출 인자에 실제 사용자 문서 본문·실제 주민등록번호/카드번호·암호문·키·개인정보를 싣지 않았다. 마스킹 경계 케이스는 **유니코드 코드포인트 이름**(U+FF0D 전각 하이픈, U+2212 마이너스, U+2013 엔 대시, U+00A0 NBSP, U+3000 전각 공백)으로만 기술했다. codex 원문에 등장하는 `900101-...`·`1234 - 5678 - ...` 류는 **codex가 스스로 만든 합성 예시**이며 실제 값이 아니다.

### 1.4 독립성 관련 관측 사실 (판정 아님)

교차 대조에서 두 리뷰의 합의를 어떻게 무게 잡을지에 영향을 주므로, 관측한 사실만 적는다.

1. **codex는 완전한 콜드 리드가 아니었다.** 두 레인 모두 리뷰 시작 직후 자신의 영속 메모리를 읽었다 — `~/.codex/memories/MEMORY.md`, `~/.codex/memories/skills/easy-doc-parity-stop-gate-*`. 이는 이 저장소가 codex에 준 컨텍스트가 아니라 **codex 자신이 이전 회차에서 축적한 상태**다. 이 에이전트가 프롬프트로 심은 것이 아니다.
2. **레인 B는 코드를 읽는 데 그치지 않고 실행했다.** `kotlin -classpath backend-kotlin/core/build/classes/kotlin/main -e '...'` 로 컴파일된 core 클래스에 직접 입력을 투입했고, `.venv/bin/python -B -c ...` 로 스캐너를 실측했다. 레인 B 지적의 "실제 실행에서 items=0이었다", "exit 0이 재현됐다"는 서술은 그 실행 결과를 가리킨다. 레인 B가 레인 A보다 두 배 오래 걸린 이유이기도 하다.
3. **읽기 전용 준수**: 두 레인 모두 `adversarial-review` 모드이고, 관측된 명령은 `git`·`rg`·`nl`·`sed`·`find`·`kotlin -e`·`python -c` 로 전부 조회·실행이며 저장소 파일 수정은 관측되지 않았다. 수거 시점 `git status`도 리뷰 전과 동일했다.

---

## 2. 전달한 프롬프트 전문

### 2.1 레인 A focus text (원문)

```
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 재개발 중이다. 판정 기준은 "Python 과 같은 값"이 아니라 요구사항 충족이다. 이번 범위의 핵심은 application 변환 유스케이스(ConvertDocumentUseCase)와 parity 하네스 배선이다.

지켜야 하는 계약:
(1) 문서 한 건당 LLM 완성 요청은 최대 2건 — 변환 1건 + 기계 검출된 규칙 위반이 있을 때만 표적 보정 1건. 루프가 아니다: 보정 결과에 위반이 남아 있어도, 보정을 기각한 경우에도 다시 부르지 않는다. 위반이 없으면 1건이다("항상 2건"은 상한을 지키면서 요구를 어긴다 — 크레딧 원가가 두 배로 어긋난다). 전송 계층 재전송(타임아웃·5xx 재시도)은 이 수에 들어가지 않으며 따로 계측한다. 응답 없이 끝난 호출도 1건으로 센다. 상한은 "보통 2회"가 아니라 구조적으로 2회여야 한다 — 검사→호출을 반복하는 구조는 상한이 아니라 기대값일 뿐이다.
(2) 4대 예외는 발생 위치에 따라 결과가 정반대다. 응답 절단 / 빈 결과 / 호출 실패가 1차 호출에서 나면 변환 실패이고 결과를 사용자에게 주지 않는다. 같은 사건이 보정 호출에서 나면 보정을 버리고 1차 결과를 채택하며 변환은 성공이다. 두 위치를 같은 코드로 뭉뚱그리면 반드시 한쪽이 틀린다. 실패는 정의된 실패 코드로만 기록하고 코드·메시지에 문서 본문·모델 응답·파일명을 담지 않는다.
(3) 보정 채택식 = (1차 결과에 있던 자리표시자를 하나도 잃지 않았다) AND (규칙 위반 건수가 늘지 않았다). 자리표시자는 하나만 잃어도 기각이고 판정은 존재 여부이지 위치·순서가 아니다. 1차 결과에 애초에 없던 자리표시자는 "잃은 것"이 아니다. 위반 건수가 같으면 채택한다(경계값). 기각의 결과는 1차 결과 채택이고 "보정본을 썼는가" 표시는 거짓이다. 토큰 사용량 보고는 채택 여부와 무관하게 두 호출의 합이다. missing_placeholders 는 채택된 최종 결과를 기준으로 산출하고 라벨만 담으며 예외로 막지 않는다(기준을 1차 결과로 잡으면 사용자가 받은 본문에 멀쩡히 있는 라벨을 유실로 신고하게 된다).

다음을 찾아라:

A. 상한 2를 우회할 수 있는 경로. 재귀·루프·조기 반환 후 재진입·예외 처리 경로·재시도·CompletionBudget 을 거치지 않는 provider 직접 호출·budget 인스턴스를 새로 만들거나 재설정(reset)할 수 있는 지점을 데이터 흐름을 따라가며 찾아라. 예산 소진 시 IllegalStateException 을 던지는 설계가 실제 방어인지, 아니면 상위 catch 에 삼켜지거나 재시도로 이어져 무력해지는 경로가 있는지 확인하라. 그 예외를 도메인 예외가 아니라 IllegalStateException 으로 둔 선택이 옳은지 근거와 함께 판단하라 — 프로그래밍 오류로 보아 잡히지 않게 하려는 의도와, 잡히면 조용히 넘어가는 위험 중 어느 쪽이 실제 코드에서 성립하는가.

B. (2)의 비대칭이 실제로 코드에서 두 위치로 분기되는가, 아니면 공용 핸들러로 뭉뚱그려져 한쪽이 틀리는가. 보정 호출의 실패가 변환 전체를 실패시키는 경로, 1차 호출 실패가 조용히 성공으로 처리되는 경로, 절단·빈 결과·호출 실패 셋이 구분되지 않고 하나로 접히는 경로를 찾아라.

C. 채택식의 경계값 오류. "하나만 잃어도 기각" / "1차에 없던 자리표시자는 잃은 것이 아니다" / "건수가 같으면 채택"이 코드에서 반대로 구현되거나 부등호가 뒤집힌 자리, 기각 시 토큰 합산이 누락되는 자리, missing_placeholders 의 기준 본문이 1차 결과로 잡힌 자리를 찾아라.

D. parity 하네스 격리. parity fixture 생산자(ParityFixtures, ConversionParityTest, MaskingParityTest)가 fixture 파일의 기대값(expected/단언 필드)을 읽어 그것에 맞추는 경로가 있는가. 생산자는 입력(input)만 봐야 한다 — 기대값을 볼 수 있으면 검증이 자기 자신과 대조하는 구조가 되어 아무것도 재지 못한다. ParityFixtures 가 input 만 노출한다는 설계가 실제 코드에서 성립하는지, JSON 원본 전체를 다시 읽거나 다른 API 로 우회해 기대값에 닿을 수 있는 통로가 남았는지 확인하라. 또한 이 게이트가 지적 0건일 때와 대상 0건일 때를 구분하는지, 검사 대상이 비었는데 성공으로 끝나는 자리가 있는지 보라.

깨지면 무엇이 새는가: 상한 위반은 크레딧 원가와 사용자 과금을 어긋나게 하며 이 프로젝트의 즉시 중단 기준에 해당한다. 예외 비대칭이 틀리면 사용자가 받을 수 있었던 결과를 통째로 잃는다. parity 격리가 깨지면 게이트가 초록불인 채 아무것도 검증하지 않는다.
```

### 2.2 레인 B focus text (원문)

```
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot 로 재개발 중이다. 판정 기준은 "Python 과 같은 값"이 아니라 요구사항 충족이다. 이번 범위에는 마스킹 패턴 수정, value class 기반 불변식 상시 탐지기, provider 조립·설정 소유권 이동, CI 배선이 들어 있다. 상수 축은 "선언한 범위와 실제 도달 범위가 같은가"다.

지켜야 하는 조건:
(1) 마스킹 선행 불변식 — 사용자 문서 텍스트는 마스킹 파이프라인을 통과한 뒤에만 LLM provider 로 전달될 수 있다. 마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종이다. 이번 수정에 내려진 구현 지시는 다음과 같다: 성별코드를 \d 로 바꾸고 매치 후 Character.digit(c, 10) 의 값이 1~8인지 판정해 전각뿐 아니라 모든 유니코드 십진 숫자 체계를 한 번에 덮을 것 / 구분자는 전각 하이픈(U+FF0D)·마이너스(U+2212)·엔 대시(U+2013)·NBSP(U+00A0)·전각 공백(U+3000) 등으로 상수를 확장할 것 / \s 를 쓰지 말 것(개행이 들어가면 서로 다른 줄의 숫자열이 붙어 진짜 과잉 마스킹이 된다. 열거 집합에 개행·캐리지리턴을 넣지 않는다) / 마스킹 전 입력 정규화는 금지(정규화해서 넘기면 MaskedItem.original 이 접힌 값이 되고 내보내기 복원이 사용자 본문을 다른 글자로 되돌린다).
(2) 모듈 경계 — core 모듈은 Spring·DB 의존 없이 유지되어야 하고, application 모듈은 infrastructure 에 의존하지 않아야 한다. api·worker 는 infrastructure 를 runtimeOnly 로만 가져간다.
(3) 로그·예외 메시지·메트릭 태그에 문서 본문과 개인정보를 남기지 않는다. 로깅은 문서 ID·길이·처리 상태까지만이다.

다음을 찾아라:

A. 수정된 마스킹 패턴이 위 구현 지시와 어긋나는 자리, 그리고 수정 후에도 여전히 빠져나가는 표기 변형. 과소 마스킹 방향(주민번호·카드번호가 그대로 통과하는 표기)과 과잉 마스킹 방향(마스킹하면 안 되는 문자열을 잡는 경로, 특히 줄바꿈을 사이에 둔 숫자열 결합) 양쪽을 보라. 매치 후 값 판정과 정규식 매치 범위가 어긋나 자리표시자 치환 인덱스가 밀리는 경로, 복원(restore) 이 원문을 다른 글자로 되돌리는 경로도 확인하라. 새로 추가된 fixture 케이스가 실제로 수정 전 구현에서 실패했을 케이스인지 — 즉 결함을 재현하는 케이스인지, 아니면 이미 통과하던 입력을 추가만 한 것인지 — 를 diff 의 패턴 변경과 대조해 판단하라.

B. value class(inline class)에 붙인 상시 탐지기가 실제로 모든 생성 경로에서 무는가. Kotlin value class 는 정적 constructor-impl 로 컴파일되며 copy·역직렬화·언박싱된 문자열 캐스팅·다른 모듈에서의 생성·리플렉션으로 우회될 수 있다. 탐지기를 거치지 않고 그 래퍼 타입을 만들 수 있는 경로를 전부 찾아라. 가시성 제한(internal/private constructor)이 같은 모듈·같은 컴파일 단위에서 실제로 막는 범위가 선언한 범위와 같은지 확인하라. 그리고 이 탐지기를 제거하면 정확히 어떤 테스트가 깨지는가 — 떼어도 아무것도 깨지지 않으면 그 탐지기는 아무것도 재지 않는 것이다.

C. provider 조립과 설정 소유권을 infrastructure 로 옮긴 변경이 (2)의 의존 방향을 실제로 보존하는가. Spring 컴포넌트 스캔 범위 확장(scanBasePackages), @ConfigurationProperties 등록, 자동 설정이 application 이나 core 를 스캔 대상에 넣어 경계를 우회시키는 부작용을 찾아라. Gradle 의존 선언과 실제 컴파일·런타임 클래스패스가 어긋나는 자리 — runtimeOnly 로 선언했는데 컴파일에 노출되는 경우, 또는 반대로 런타임에 빈이 없어 부팅이 실패하거나 조용히 널 설정으로 도는 경우 — 도 지목하라. 설정값(엔드포인트 URL·타임아웃·상한)이 기본값으로 조용히 채워져 운영에서 의도와 다르게 도는 자리도 보라.

D. 선언한 범위와 실제 도달의 대조. 이 diff 가 CI quality 잡에 배선한 데이터 보호 불변식 스캐너가 실제로 어느 경로에서 도는가. 스캐너가 선언한 검사 범위(어떤 디렉터리·어떤 파일 패턴)와 CI 잡이 실제로 넘기는 인자·작업 디렉터리가 일치하는가. 크롤링·글로브에서 조용히 빠지는 디렉터리(점으로 시작하는 디렉터리 등), 제외 패턴이 근거보다 넓어 앞으로 같은 이상 징후를 보이지 않게 만드는 자리, 검사 대상이 0건인데 성공 코드로 끝나는 자리, 위반을 주입했을 때 실제로 실패하는 구조인지(음성 대조가 가능한지)를 확인하라. 성공/실패 판정을 대리 지표로 바꿔 읽는 자리 — 종료 코드 0을 "검사했다"로, 테스트 통과를 "그 경로가 실제로 돌았다"로 읽는 자리 — 도 지목하라. 이 잡을 통째로 제거하면 무엇이 깨지는가.

깨지면 무엇이 새는가: 마스킹 누락은 주민등록번호·카드번호가 그대로 외부 LLM 으로 전송되는 것이고 이 프로젝트의 즉시 중단 기준이다. 탐지기가 무력하면 그 누락을 아무도 알지 못한다. 모듈 경계가 깨지면 순수 도메인 테스트가 불가능해지고 의존 방향 결정이 무의미해진다. CI 배선이 닿지 않으면 게이트가 초록불인 채 아무것도 검사하지 않는다.
```

---

## 3. codex 원문 출력 (무편집)

> 아래 두 블록은 codex stdout **그대로**다. 한 글자도 고치거나 줄이지 않았고 이 에이전트가 쓴 문장이 섞여 있지 않다.
> 잘림 없음 — 두 출력 모두 `Next steps:` 절까지 온전히 끝났다.

### 3.1 레인 A 원문 (`review-msrnco2w-6242np`, exit 0, 4,885 바이트)

```
# Codex Adversarial Review

Target: branch diff against f73879b
Verdict: needs-attention

NO-SHIP: 문서 단위 호출 상한을 재진입으로 우회할 수 있고, 실제 Anthropic 예외 응답은 실패 분류·토큰 계측을 훼손한다. parity 게이트도 기대값 격리와 0대상 fail-closed가 구조적으로 성립하지 않는다.

Findings:
- [high] 재진입할 때마다 호출 예산이 초기화된다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/conversion/ConvertDocumentUseCase.kt:56-74)
  convert()는 호출마다 새 Pass를 만들고 Pass는 새 CompletionBudget을 만든다. 따라서 첫 시도에서 응답 없이 1회를 소비한 뒤 동일 문서 작업을 큐가 재시도해도 spent=0부터 다시 시작하며, 반복 재진입으로 문서당 2회 상한을 무제한 우회할 수 있다. IllegalStateException은 한 Pass 안의 세 번째 호출만 막으므로 이 경로에서는 발생하지 않는다. 현재 Kotlin worker에는 아직 호출자가 없지만, WorkerApplication은 향후 재시도 정책을 명시하고 있고 이 공개 API에는 같은 작업의 재진입을 식별할 conversion ID나 누적 예산이 없다.
  Recommendation: 논리 conversion ID에 귀속된 누적 완성요청 수를 저장소에서 원자적으로 소비하고 모든 worker 재시도에 이어 전달하라. 동일 작업을 두 번 호출하는 회귀 테스트로 누적 호출이 2를 넘지 않음을 검증하되, provider 내부 전송 재시도는 별도 계측하라.
- [high] 빈 절단 응답이 잘못 분류되고 사용량도 유실된다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:253-264)
  parse()는 finishReason과 usage를 읽지만 LlmCompletion 생성 중 requireText()를 먼저 평가한다. requireText는 빈 content이면 MAX_TOKENS도 LlmEmptyResultException으로 던지므로, 새 유스케이스는 1차 빈 절단을 TRUNCATED가 아니라 EMPTY_RESULT로 기록한다. 예외에는 이미 관측된 usage가 없고 ConvertDocumentUseCase.complete()도 예외 경로에서 토큰을 누계하지 않으므로, 보정 호출의 빈 응답에 토큰이 있어도 최종 성공 결과는 두 호출 합보다 적게 보고한다.
  Recommendation: 빈 본문이어도 finish reason과 usage를 보존하는 구조화된 provider 결과를 반환해 application 계층이 절단 우선으로 분류하게 하라. 최소한 실패 예외에 안전한 토큰 수와 정확한 종류를 포함하고, MAX_TOKENS+빈 content 및 END_TURN+빈 content를 1차·보정 위치에서 비영 토큰으로 통합 테스트하라.
- [high] parity 생산자가 원본 fixture의 기대값에 직접 접근할 수 있다 (backend-kotlin/core/src/testFixtures/kotlin/kr/easydoc/core/parity/ParityFixtures.kt:44-54)
  ParityFixtureCase는 input만 담지만 public root()가 assert·reference가 포함된 원본 fixture 디렉터리를 그대로 반환한다. 같은 테스트 프로세스에는 parity.fixtures.dir도 공개되어 있어 ConversionParityTest나 MaskingParityTest가 JSON을 다시 읽고 기대값을 actual로 복사할 수 있다. 현재 생산자들은 이 우회를 사용하지 않지만, 주장한 격리는 API 규약일 뿐 구조적 경계가 아니며 그런 회귀가 생겨도 게이트는 자기 자신과 대조하며 초록이 된다.
  Recommendation: 생산자 실행 환경에는 기대 필드를 물리적으로 제거한 input-only artifact만 제공하고 전체 fixture는 comparator 프로세스에만 제공하라. root()를 노출하지 말고 생산자에서 raw fixture 경로·파일 API 및 assert/reference/expected 접근을 금지하는 독립 회귀 검사를 추가하라.
- [high] 검사 대상 0건이 CI 성공으로 종료된다 (.github/workflows/ci.yml:341-346)
  CI는 declared_count가 0이면 값을 한 건도 검증하지 않았다고 출력하면서 명시적으로 exit 0 한다. 두 생산자와 parity-domains.txt의 두 선언을 함께 제거하면 Gradle manifest도 declared=produced=empty로 성공하고, self-check는 actual 밖의 파일만 확인하므로 최종 잡이 초록으로 끝난다. 이는 지적 0건과 대상 0건을 종료 상태로 구분하지 못하는 실제 false-green 경로다.
  Recommendation: 한 번 포팅 완료로 선언된 masking과 repair-adoption을 독립적인 비감소 implemented-domain 하한에 고정하고, declared_count=0 또는 필수 producer/산출물 부재를 실패시켜라. 두 생산자와 선언을 함께 제거하는 음성 테스트가 CI를 빨갛게 만들어야 한다.

Next steps:
- 재진입 누적 예산과 Anthropic 빈 응답 통합 테스트를 먼저 추가한다.
- 생산자용 input-only fixture 경계를 분리한다.
- 0개 선언·0개 생산자 mutation이 CI에서 실패하는지 검증한다.
```

### 3.2 레인 B 원문 (`review-msrnd77a-m3xqzx`, exit 0, 6,580 바이트)

```
# Codex Adversarial Review

Target: branch diff against f73879b
Verdict: needs-attention

NO-SHIP: 주민등록번호·카드번호가 그대로 LLM으로 나가는 재현 가능한 표기 두 종류가 남아 있고, value-class 및 CI 탐지기에도 false-green 경로가 있다. Gradle 의존 방향 자체에서는 구체적 위반을 찾지 못했지만 provider 런타임 도달은 테스트가 증명하지 않는다.

Findings:
- [critical] 보충 평면 숫자와 복합 카드 구분자가 마스킹을 우회한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:249-275)
  성별코드 그룹은 정규식상 하나의 Unicode code point지만 Kotlin String에서는 surrogate 2개일 수 있다. 따라서 `900101-𝟏234567`(U+1D7CF)은 `\d`에 매치된 뒤 `singleOrNull()`에서 거부되어 실제 실행에서 items=0으로 통과했다. 카드 패턴도 구분자를 정확히 한 문자만 허용하여 흔한 `1234 - 5678 - 9012 - 3456`과 NBSP+하이픈+NBSP 형식이 items=0이었다. 두 입력 모두 개인정보가 외부 LLM으로 평문 전송되는 즉시 중단 결함이다.
  Recommendation: 성별코드는 `codePointCount == 1`과 `codePointAt(0)` 후 `Character.digit(Int, 10)`으로 판정하라. 카드 구분자는 개행을 제외한 공백 주변의 선택적 하이픈을 유한 문법으로 허용하고 두 입력을 회귀·음성 대조 fixture에 추가하라.
- [high] 생성 지점 탐지기가 허용된 파일의 추가 생성과 별칭을 보지 못한다 (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/privacy/ProvenanceCreationSitesTest.kt:128-149)
  탐지 결과가 호출 위치가 아니라 타입별 `Set<파일 경로>`로 축약된다. 이미 허용된 `ConvertDocumentUseCase.kt`에 `ModelDraft(source)`를 하나 더 넣어 원문을 repair prompt로 보내도 집합은 그대로라 테스트가 통과한다. `import ModelDraft as Draft; Draft(source)`나 constructor reference도 정규식에 잡히지 않는다. 또한 이 테스트를 삭제해도 CI의 일반 `gradlew build`는 테스트 수가 하나 줄어든 채 성공한다.
  Recommendation: 파일 단위가 아니라 AST 기반의 정확한 생성 호출 identity와 개수를 고정하고, 별칭·constructor reference·역직렬화/리플렉션 우회 mutation을 넣어라. CI에서도 해당 테스트 클래스를 명시 실행해 삭제 시 실패하게 하라.
- [high] CI 개인정보 스캐너가 Kotlin 여러 줄 위반을 전부 놓친다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:383-411)
  스캐너는 파일을 `splitlines()`한 뒤 각 줄에만 규칙을 적용한다. 따라서 `provider.complete(\n sourceText\n)`와 `logger.info(\n "{}",\n body\n)` 탐침은 각 줄 모두 미적중이었다. Kotlin의 일반 포매팅만으로 마스킹 우회와 본문 로그가 CI 초록불을 통과한다.
  Recommendation: Kotlin/Python AST 또는 최소한 균형 괄호 기반 다중 줄 호출 분석으로 바꾸고, raw LLM 입력·로그 본문·평문 저장의 여러 줄 음성 대조를 CI에서 실행하라.
- [high] 선언된 스캔 루트가 사라져도 전수 검사로 성공한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:352-362)
  전수 모드에서 존재하지 않는 SCAN_ROOT는 조용히 `continue`한다. 한 루트가 이동하면 나머지만 검사하면서 계속 '전수'로 보고하고, 모든 루트가 없거나 대상 파일이 0개여도 `main`은 `--changed`가 아닌 CI 기본 호출에서 exit 0을 반환한다. HEAD 코드를 대상으로 빈 루트를 주입한 대조에서도 `검사 대상 파일이 없습니다` 뒤 exit 0이 재현됐다.
  Recommendation: 모든 선언 루트의 존재와 루트별 최소 1개 대상 파일을 검증하고, 전수 모드도 총 대상 0건이면 비영 종료하도록 하라.
- [medium] 세로 탭과 폼 피드가 서로 다른 줄·페이지의 숫자를 결합한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:373-408)
  `INVISIBLE_RANGES`가 U+000B(VT)와 U+000C(FF)를 제거한다. 그 결과 `900101<VT>1234567` 및 `900101<FF>1234567`이 탐색 뷰에서 13자리로 붙어 실제 실행에서 RRN으로 마스킹됐다. LF/CR만 확인한 신규 테스트는 이 줄·페이지 경계 과잉 마스킹을 잡지 못한다.
  Recommendation: VT·FF 및 모든 줄/문단 경계 문자는 탐색 뷰에서 제거하지 말고, LF·CR·VT·FF 각각에 대한 비결합 테스트를 추가하라.
- [medium] 본문 value class의 기본 toString이 로그 금지 규칙을 우회한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:625-640)
  `ModelDraft`와 `ReviewedBody`는 본문 String을 감싼 inline class지만 `toString()`을 재정의하지 않아 기본 생성 결과가 underlying value를 포함한다. 신규 유스케이스가 실제 변환문을 `ModelDraft`로 반환하므로 로거 인자·문자열 보간·컬렉션 출력 한 번이면 문서 본문이 노출된다. 같은 변경에서 `PlaceholderRestoration`은 이 위험을 막았지만 두 provenance 래퍼와 `MaskedText`에는 적용하지 않았다.
  Recommendation: 세 value class 모두 길이만 반환하도록 `toString()`을 재정의하고, 직접 호출·문자열 보간·Any 박싱·컬렉션 출력에서 본문이 없다는 테스트를 추가하라.
- [medium] provider 조립 테스트가 실제 Spring 런타임 도달을 확인하지 않는다 (backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/llm/LlmProviderConfigurationTest.kt:10-17)
  테스트 주석은 `ApiStartupWithDatabaseTest`가 configuration scan 도달을 확인한다고 주장하지만 그 테스트는 `/health`와 Flyway만 검사하며 `LlmProvider` 또는 `LlmProperties` 빈을 조회하지 않는다. 현재 어떤 Spring 빈도 provider를 요구하지 않으므로 `@Configuration` 제거, 스캔 범위 축소, runtimeOnly 패키징 누락이 발생해도 API·worker 기동과 이 단위 테스트가 모두 통과할 수 있다.
  Recommendation: API와 worker 실제 ApplicationContext에서 유일한 `LlmProvider` 빈을 조회하고, 비기본 property를 바인딩해 adapter까지 전달되는지 검증하라.

Next steps:
- 두 critical 마스킹 입력을 먼저 차단하고 value-class 및 CI 스캐너에 우회 mutation을 추가하고 API·worker 컨텍스트에서 provider 빈 도달을 검증한다.
```

---

## 4. 정리(가공)

> **이 구획은 Claude가 쓴 것이다.** codex 지적을 목록화·색인만 했고 **옳고 그름을 판정하지 않았다.** 심각도 라벨은 codex가 붙인 것을 그대로 옮겼을 뿐 재부여하지 않았다. 중복으로 보이는 항목도 병합하지 않았다.

### 4.1 지적 목록 (총 11건 — 레인 A 4건, 레인 B 7건)

| # | 레인 | codex 심각도 | 지적 요지 | codex가 제시한 파일·라인 |
|---|---|---|---|---|
| A-1 | A | `[high]` | 재진입할 때마다 호출 예산이 초기화된다 | `application/.../ConvertDocumentUseCase.kt:56-74` |
| A-2 | A | `[high]` | 빈 절단 응답이 잘못 분류되고 사용량도 유실된다 | `infrastructure/.../AnthropicProvider.kt:253-264` |
| A-3 | A | `[high]` | parity 생산자가 원본 fixture의 기대값에 직접 접근할 수 있다 | `core/src/testFixtures/.../ParityFixtures.kt:44-54` |
| A-4 | A | `[high]` | 검사 대상 0건이 CI 성공으로 종료된다 | `.github/workflows/ci.yml:341-346` |
| B-1 | B | `[critical]` | 보충 평면 숫자와 복합 카드 구분자가 마스킹을 우회한다 | `core/.../privacy/Masking.kt:249-275` |
| B-2 | B | `[high]` | 생성 지점 탐지기가 허용된 파일의 추가 생성과 별칭을 보지 못한다 | `core/src/test/.../ProvenanceCreationSitesTest.kt:128-149` |
| B-3 | B | `[high]` | CI 개인정보 스캐너가 Kotlin 여러 줄 위반을 전부 놓친다 | `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:383-411` |
| B-4 | B | `[high]` | 선언된 스캔 루트가 사라져도 전수 검사로 성공한다 | `.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:352-362` |
| B-5 | B | `[medium]` | 세로 탭과 폼 피드가 서로 다른 줄·페이지의 숫자를 결합한다 (과잉 마스킹) | `core/.../privacy/Masking.kt:373-408` |
| B-6 | B | `[medium]` | 본문 value class의 기본 `toString`이 로그 금지 규칙을 우회한다 | `core/.../privacy/Masking.kt:625-640` |
| B-7 | B | `[medium]` | provider 조립 테스트가 실제 Spring 런타임 도달을 확인하지 않는다 | `infrastructure/src/test/.../LlmProviderConfigurationTest.kt:10-17` |

### 4.2 리더 지정 focus 축별 대응 (어느 축이 답을 받았는지의 색인)

| 리더 focus | 다룬 레인 | 대응 지적 | codex가 축을 다뤘는가 |
|---|---|---|---|
| ① 호출 상한 2의 구조적 강제·`IllegalStateException` 타당성 | A | A-1 | 다룸 — 상한과 예외 설계 모두에 서술 있음 |
| ② X-2 수정의 지시 일치 + "25건 중 17건 수정 전 실패" 주장 검증 | B | B-1, B-5 | **부분** — 패턴 적합성은 다뤘으나 **"17건이 수정 전 실패"라는 수치 주장 자체를 명시적으로 검증한 문장은 원문에 없다** |
| ③ parity 생산자 격리(`ParityFixtures`가 `input`만 노출) | A | A-3 | 다룸 |
| ④ 설정 소유권 infrastructure 이관·모듈 경계·`scanBasePackages` 부작용 | B | B-7 | 다룸 — 단 codex는 요약에서 "Gradle 의존 방향 자체에서는 구체적 위반을 찾지 못했다"고 적었다 |
| ⑤ `MaskedText` 상시 탐지기가 value class에서 실제로 무는가 | B | B-2, B-6 | 다룸 |
| ⑥ CI `quality` 잡 스캐너 배선의 선언 범위 일치 | A·B 양쪽 | A-4, B-3, B-4 | 다룸 |

### 4.3 두 레인이 독립적으로 같은 영역을 짚은 자리

프롬프트를 나눠 줬는데도 **레인 A와 레인 B가 각각 CI 게이트의 false-green 경로를 지적했다**(A-4 / B-3·B-4). 다만 두 지적은 **서로 다른 파일과 다른 기제**를 가리킨다 — A-4는 `.github/workflows/ci.yml`의 `declared_count=0` 분기, B-3·B-4는 `scan_privacy_invariants.py`의 줄 단위 스캔과 누락 루트 무시다. 같은 결론의 중복인지 별개 결함인지는 판정하지 않는다.

### 4.4 전제 확인 필요 (원문 삭제 없이 표시만)

codex 원문을 그대로 두고, 검증이 필요해 보이는 전제만 지목한다. **어느 것도 오탐으로 판정하지 않았다.**

- A-1: "현재 Kotlin worker에는 아직 호출자가 없지만"이라는 단서를 codex 스스로 달았다. 실사용 시점 판정(마감)이 필요한 항목으로 보인다.
- A-3: "현재 생산자들은 이 우회를 사용하지 않지만"이라는 단서가 있다. 현행 결함이 아니라 구조 지적이라는 codex 자신의 서술이다.
- B-1·B-5: "실제 실행에서 items=0으로 통과했다", "실제 실행에서 RRN으로 마스킹됐다"는 서술은 §1.4-2의 `kotlin -e` 실행 결과를 가리킨다. **재현 명령 자체는 원문에 실려 있지 않으므로** 재현하려면 별도 실행이 필요하다.
- B-4: "빈 루트를 주입한 대조에서도 재현됐다"도 마찬가지로 codex의 실행 결과이며 재현 명령은 원문에 없다.
- 리더 focus ②의 **"새 케이스 25건 중 17건이 수정 전 실패" 수치 주장은 codex가 확인하지도 반박하지도 않았다.** 이 축은 이번 회차에서 **답을 받지 못한 것으로 기록한다** — 대신 채우지 않는다.

---

## 5. 미실행·실패 항목

- **codex 리뷰 누락 없음.** 두 레인 모두 종료 코드 `0`, 출력 비어 있지 않음, `Next steps:`까지 온전. 재시도 규약(§7 1회 재시도)은 **발동하지 않았다** — 실패·타임아웃·빈 응답 어느 것도 없었다.
- **출력 잘림 없음.** 두 출력 모두 구조가 완결됐다.
- **exit 7(대상 0건) 아님.** 사전 `--dry-run`과 본 실행 모두 `대상 판정 = non-empty (변경 파일 36개)`.
- **코드 수정 없음.** 이 에이전트는 저장소의 어떤 제품 파일도 고치지 않았다. 유일한 쓰기는 이 산출물 파일이다.
- **미답 축 1건**: 리더 focus ②의 "25건 중 17건이 수정 전 실패" 수치 검증(§4.4 마지막 항목). 재호출이 필요하면 그 축만 좁혀 다시 물어야 한다.
- **다른 리뷰어 결론 미참조**: 지시대로 `migration-reviewer` 산출물을 읽지 않고 실행했다. 같은 어간의 `08_conversion-usecase_migration-reviewer.md` 존재 여부도 확인하지 않았다.
- **수거 이후 HEAD 이동 관측 (§1.1)**: 산출물 작성 중 병렬 레인이 `bed5300`을 얹었다. 리뷰 대상 `f73879b..6d8e88c`는 영향받지 않으나, **이 리뷰 결과를 읽을 때 저장소 HEAD는 이미 리뷰 시점보다 앞서 있다.** 교차 대조 시 이 시차를 전제해야 한다.
