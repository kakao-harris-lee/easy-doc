# 07_core-rebuild — codex 독립 리뷰 (1회차)

> 어간 `07_core-rebuild`는 **리더가 1단계 호출에서 지정한 값**을 그대로 썼다. 이 에이전트가 임의로 만든 슬러그가 아니다.
> `docs/migration/_workspace/reviews/` 에 같은 어간의 이전 회차는 없다 — **1회차**다.

---

## 1. 호출 메타데이터

| 항목 | 값 |
|---|---|
| 실행 시각 | 2026-08-13 (codex job 소요 9분 18초) |
| 리뷰 모드 | `adversarial` (→ 헬퍼 `adversarial-review`) |
| 대상 범위 | `c11a404..f73879b` (커밋 5개, 변경 파일 37개) |
| scope / base | scope 미지정(auto) / `--base c11a404` — **base가 주어지면 scope는 무시된다** |
| merge-base 확인 | `git merge-base HEAD c11a404` = `c11a4047b1bc6e2989467283794a3e3700fa0b45` = `c11a404` 자신. HEAD = `f73879b2ae1b600d2a18b200ef02379e8aee7767`. 따라서 리뷰 대상은 요청받은 5개 커밋과 정확히 일치한다 |
| 헬퍼 경로 | `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs` |
| 헬퍼 출처 | plugins cache (버전 1.0.6, 스크립트가 최신 버전 자동 선택) |
| 실행 명령 | `.claude/skills/codex-review/scripts/codex-review.sh adversarial --base c11a404 "<focus 전문 — §2>"` |
| 헬퍼 실제 명령 | `node <헬퍼경로> adversarial-review --base c11a404 '<focus 전문>'` |
| **스크립트 종료 코드** | **`0`** — 리뷰 근거가 되는 값이다 |
| job id | `review-msrkckhy-dchb11` (completed) |
| codex session id | `019ffb58-1888-7232-b6fa-20ca08ca730c` (`codex resume 019ffb58-1888-7232-b6fa-20ca08ca730c`) |
| codex verdict | `needs-attention` |
| 출력 크기 | 6,611 바이트 |

### 스크립트 대상 판정 두 줄 (stderr 원문)

```
codex-review: 리뷰 대상 = branch diff vs c11a404
codex-review: 대상 판정 = non-empty (merge-base=c11a4047b1bc, 변경 파일 37개 (branch 모드는 커밋된 변경만 센다))
```

### 제공한 맥락 목록

focus text 안에 다음을 사실로 실었다. 별도 첨부 파일은 없으며, codex는 저장소를 직접 읽었다.

- 재개발 판정 기준(Python 값 일치가 아니라 요구사항·정책 충족), 예외는 정책 불변식
- 마스킹 선행 불변식과 범주 2종(주민등록번호·카드번호), 전화·이메일·계좌 미마스킹이 **의도**라는 사실
- 계약 조항: LLM 호출 최대 2회, LlmProvider 경유 강제·벤더 SDK 직접 import 금지, 4대 예외, 보정 실패 시 원본 채택, 스타일 규칙 단일 정의
- 테스트 수 실측치(core 228 / infrastructure 41)
- **선언 범위 대 실제 도달 축의 실측 사실 2건**: ① `backend-kotlin/parity-domains.txt` 선언 0개인데 이번 변경이 masking·text·style·prompts·postprocess를 구현했다는 것, 그리고 그 파일 주석이 "선언 X / 산출 O → 빌드 실패"를 `gradlew parityManifestCheck`가 강제한다고 적고 있다는 것 ② `parity/fixtures/masking/masking.json` 존재
- 대상 파일 경로(`core/privacy/Masking.kt`, `core/llm/LlmPrompt.kt`·`LlmProvider.kt`, `core/easyread/Postprocess.kt`·`GlossCollision.kt`·`StyleRules.kt`·`Prompts.kt`, `infrastructure/llm/AnthropicProvider.kt`, `StubAnthropicServer.kt`, `FakeLlmProvider`, `CoreModuleBoundaryTest`, 스냅샷 JSON 2종)

**민감 데이터 미포함 확인**: focus text와 호출 인자에 실제 사용자 문서 본문·실제 주민등록번호/카드번호·암호문·키·개인정보를 싣지 않았다. 마스킹 경계 케이스는 값이 아니라 **범주 이름**(전각 숫자, 잘못된 체크섬, 경계 인접 숫자, 겹치는 매치)으로만 기술했다.

**Claude 결론 미주입 확인**: focus text는 사실(변경 범위·계약 조항·실측 상태)과 채점 기준만 담고, "이 부분이 문제인 것 같다"류의 유도 문장을 넣지 않았다. `parity-domains.txt` 선언 0개는 `cat`으로 확인한 파일 상태이며 판정이 아니다.

---

## 2. 전달한 프롬프트 전문 (focus text)

```
이 저장소는 Python/FastAPI 런타임을 Kotlin/Spring Boot로 **재개발**하는 중이다. 판정 기준은 "Python과 같은 값이 나오는가"가 아니라 "요구사항·정책을 충족하는가"이며, 예외는 정책 불변식뿐이다. 아래 4개 축만 보고, 일반적인 Kotlin 코드 품질(널 안전성·예외 처리·명명) 지적은 하지 마라.

[축 1 — 보안 불변식: 마스킹 선행]
불변식: 사용자 문서 텍스트는 core/privacy/Masking.kt 파이프라인을 통과한 뒤에만 LlmProvider로 전달될 수 있다. 마스킹 범주는 주민등록번호(외국인등록번호 포함)·카드번호 2종이고, 전화·이메일·계좌는 의도적으로 마스킹하지 않는다. 이 불변식은 타입 수준에서 강제된다고 선언돼 있다(core/llm/LlmPrompt.kt, LlmProvider.kt). 찾아라: (1) 미마스킹 원문이 LlmPrompt·LlmProvider·AnthropicProvider 요청 본문에 닿는 경로 — 타입 강제를 우회하는 생성자·팩토리·복사·직렬화 통로가 있는가, (2) 원문 또는 마스킹 대응표(placeholder→원문)가 로그·예외 메시지·스택트레이스·메트릭 태그·HTTP 오류 응답으로 새는 경로, 특히 infrastructure/llm/AnthropicProvider.kt의 오류 처리와 재시도 경로, (3) Masking의 복원(unmask/restore)이 LLM 응답이나 외부 입력에 적용될 수 있는 경로 — 복원은 사람이 제출한 본문으로 한정된다고 선언돼 있다. 위반하면 주민번호·카드번호가 외부 LLM 벤더와 로그로 유출된다.

[축 2 — 계약]
계약 조항: (a) 문서 한 건당 LLM 호출은 변환 1회 + 조건부 보정 1회로 **최대 2회**다. SDK 자체 재시도·HTTP 재시도가 이 상한과 별도로 계측되는가, 아니면 겹쳐서 2회를 넘길 수 있는가. (b) 모든 LLM 호출은 core/llm/LlmProvider.kt 인터페이스를 경유해야 하고 벤더 SDK(anthropic 등)를 core나 서비스 코드에서 직접 import하면 안 된다 — 어댑터는 infrastructure/llm에만 산다. (c) 4대 예외(응답 절단·빈 결과·자리표시자 유실·보정 악화)가 검출되어 정의된 상태로 처리되는가, 그리고 보정이 실패하거나 결과를 악화시키면 원본을 채택하는가(core/easyread/Postprocess.kt, GlossCollision.kt). (d) 스타일 규칙이 core/easyread/StyleRules.kt 한 곳에 정의되고 프롬프트 생성이 같은 정의를 쓰는가, 아니면 Prompts.kt가 규칙을 중복 정의해 두 곳이 갈릴 수 있는가.

[축 3 — 테스트 적정성]
core 228 · infrastructure 41개 테스트가 있다. 찾아라: (1) 구현을 그대로 복사해 항상 통과하는 구조의 테스트 — 특히 PromptTextSnapshotTest·StyleRuleDataSnapshotTest가 대조하는 기준(python-prompt-snapshot.json, python-style-rules-snapshot.json)이 검사 대상 자신에게서 파생된 것인지, 독립적으로 만들어진 것인지, (2) 이 변경이 깨뜨릴 수 있는 동작 중 **어떤 테스트로도 덮이지 않은 것** — 특히 마스킹의 음성 케이스(잘못된 체크섬, 경계에 붙은 숫자, 유니코드 결합/전각 숫자, 매우 긴 입력, 겹치는 매치)와 AnthropicProvider의 실패 경로(타임아웃, 429, 5xx, 잘린 스트림, 잘못된 JSON), (3) FakeLlmProvider(testFixture)가 실제 provider와 다르게 관대해서 프로덕션에서만 깨지는 계약이 있는가, (4) 스텁 서버 테스트(StubAnthropicServer.kt)가 실제 요청 본문·헤더를 단언하는가 아니면 응답만 흉내내는가.

[축 4 — 선언한 범위 대 실제 도달 범위]
이 저장소의 반복 실패는 "장치가 있는데 아무 데도 닿지 않는" 형태다. 실측 사실 두 가지를 준다: (i) backend-kotlin/parity-domains.txt는 Kotlin이 포팅을 끝냈다고 **선언**하는 도메인 목록이고 현재 선언이 0개인데, 이번 변경은 masking·text·style·prompts·postprocess 도메인을 구현했다. 그 파일 주석은 "선언 X / 산출 O → 빌드 실패"를 gradlew parityManifestCheck가 강제한다고 적고 있다. (ii) parity/fixtures/masking/masking.json에 마스킹 케이스가 들어 있다. 물어라: 이번에 구현된 Kotlin 마스킹이 그 fixture와 실제로 대조되는 자리가 **어디서 도는가** — Gradle 태스크·CI 워크플로에서 실행 경로를 짚어라. 아무 데서도 돌지 않는다면 그렇게 말하라. 그리고 CoreModuleBoundaryTest가 "core는 Spring·DB·벤더 SDK를 모른다"를 강제한다고 선언하는데, 그 강제가 실제로 닿는 범위와 닿지 않는 범위(빌드 스크립트 변경, api·worker·infrastructure 모듈, testFixtures 클래스패스)를 구분해 말하라. 이번 변경에서 새로 만들거나 넓힌 게이트·불변식·규칙 중 **떼어내도 아무 테스트가 깨지지 않는 것**을 지목하라.
```

---

## 3. codex 원문 출력 (무편집)

> 아래는 `codex-review.sh` 의 stdout 전문이다. 문구·순서·심각도 표기·줄바꿈을 일절 고치지 않았다.
> 이 구획에 Claude가 쓴 문장은 한 줄도 없다.

```
# Codex Adversarial Review

Target: branch diff against c11a404
Verdict: needs-attention

NO-SHIP: 마스킹 우회·전각 주민번호 유출·복원 결과 로그 노출이 가능하고, 호출 상한/실패 채택 계약과 parity 도달도 닫히지 않았다.

Findings:
- [high] 전각 주민등록번호가 마스킹을 통과한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:153-157)
  패턴의 일반 숫자는 Unicode `\d`이지만 성별코드만 ASCII `[1-8]`이다. 따라서 `９００１０１-１２３４５６７`은 매치되지 않아 Anthropic 요청 본문까지 평문으로 갈 수 있다. 실제 fixture도 이 요구사항 위반을 `known_gap`으로 인정하면서 단언에서 제외한다. Python과 다르게 만들지 않겠다는 이유는 요구사항 기준이라는 이번 판정 원칙과 정면으로 충돌한다.
  Recommendation: 성별코드도 Unicode 숫자의 값이 1~8인지 판정하도록 구현하고, 전각 주민번호가 실제 Anthropic 와이어에 나타나지 않는 독립 보안 회귀 테스트를 추가한다.
- [high] 공개 provenance 래퍼가 마스킹과 복원 불변식을 모두 우회한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:447-464)
  `ModelDraft`와 `ReviewedBody` 생성자가 모두 공개다. 호출자는 `ModelDraft(사용자 원문)`을 `LlmPrompt.forRepair`에 넘겨 미마스킹 주민번호를 provider로 전송할 수 있고, LLM 응답을 `ReviewedBody`로 감싸 `restoreForExport`에 넘겨 모델이 고른 위치에 원문 개인정보를 복원할 수도 있다. 타입 이름은 provenance를 표현할 뿐 발급 주체를 강제하지 않는다.
  Recommendation: 두 타입의 공개 생성을 막고 실제 provider 결과와 사람 검수 제출 경계에서만 발급되는 닫힌 값으로 바꾼다. 원문→보정 프롬프트 및 LLM 응답→복원이 컴파일 또는 통합 테스트에서 거부되는 음성 검사를 추가한다.
- [high] 복원된 주민번호가 기본 toString으로 로그에 노출된다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:119-125)
  `PlaceholderRestoration`은 복원된 최종 본문을 `text: String`으로 가진 data class인데 `toString`을 재정의하지 않았다. 검수본이 있으면 `text`에는 실제 주민번호·카드번호가 들어가므로 예외 문맥이나 구조화 로깅에 객체를 한 번 전달하는 것만으로 평문 전체가 출력된다. 같은 변경의 `LlmPrompt`와 `LlmCompletion`은 이 위험 때문에 명시적으로 본문을 숨기지만 이 타입은 빠졌다.
  Recommendation: 본문을 출력하지 않는 일반 class 또는 redacted `toString`으로 변경하고, 실제 복원된 주민번호를 넣어 문자열화·로그 캡처 어디에도 본문과 대응표가 나타나지 않는 테스트를 추가한다.
- [high] 최대 2회 및 4대 실패 처리 파이프라인이 아직 존재하지 않는다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:253-264)
  어댑터는 `max_tokens` 응답도 정상 `LlmCompletion`으로 반환하며, 저장소에는 이를 실패로 승격할 application 변환 서비스가 없다. 같은 이유로 후처리 후 빈 결과, 자리표시자 유실, 보정 실패·악화 시 원본 채택, 문서당 호출 예산 2회도 실행 경로가 없다. Fake 테스트는 응답 큐 자체만 시험하며 계획이 요구한 파이프라인 계약을 시험하지 않는다. 현재 상태에서는 부분 응답 저장이나 향후 worker 재실행에 따른 상한 초과를 차단할 근거가 없다.
  Recommendation: application에 단일 변환 오케스트레이터를 추가해 호출 예산을 강제하고, truncated·후처리 후 blank·placeholder 유실을 실패 상태로 확정하며, 보정 실패 또는 위반 증가 시 최초 결과를 채택한다. Fake provider로 0/1/2회와 3회 차단, 네 실패 상태, worker 재처리까지 테스트한다.
- [high] Kotlin 마스킹은 canonical fixture와 CI에서 한 건도 대조되지 않는다 (backend-kotlin/build.gradle.kts:178-216)
  변경된 테스트에는 `@Tag("parity")`, fixture 로딩 또는 parity actual 산출이 없고 `parity-domains.txt`도 선언 0개다. `parityManifestCheck`는 선언과 산출이 모두 비면 경고만 남기고 성공하므로 CI의 kotlin 잡은 `masking.json`을 전혀 읽지 않는다. 즉 구현·선언을 같은 커밋에서 강제한다는 주석과 달리, 구현만 추가하고 parity 테스트도 추가하지 않은 현재 변경은 초록이다.
  Recommendation: `masking.json`을 읽어 Kotlin actual을 쓰는 `@Tag("parity")` 테스트와 `masking` 선언을 같은 변경에 추가한다. 구현 파일이 생겼는데 대응 도메인 선언·산출기가 없는 경우도 독립 목록으로 실패시키고, 이후 text/style/prompts/postprocess에도 같은 연결을 만든다.
- [medium] CoreModuleBoundaryTest는 선언한 모듈 경계를 강제하지 못한다 (backend-kotlin/core/src/test/kotlin/kr/easydoc/core/CoreModuleBoundaryTest.kt:23-53)
  테스트는 core의 test runtime에서 열한 개의 정확한 클래스 이름을 `Class.forName`으로 확인할 뿐이다. `compileOnly` 의존성과 core main 참조는 런타임에 클래스가 없어 그대로 통과하며, 다른 SDK 클래스·Gradle configuration·generated source·testFixtures·application/api/worker/infrastructure에는 닿지 않는다. CI도 이 테스트 파일을 별도로 지목하지 않아 파일 자체를 제거해도 일반 Gradle build는 테스트 감소만 남기고 성공한다.
  Recommendation: Gradle configuration과 main bytecode를 검사해 core의 compile/runtime/compileOnly/annotationProcessor 의존성을 좌표·패키지 기준으로 금지한다. 별도로 application/api/worker compileClasspath에서 infrastructure·벤더 타입을 배제하고 testFixtures 범위를 분리해 단언하며, CI에서 경계 검사 경로를 명시 호출한다.

Next steps:
- 전각 주민번호와 공개 provenance 우회를 먼저 차단한다.
- 복원 결과의 문자열화·로그 누출 회귀 테스트를 추가한다.
- application 변환 오케스트레이터와 Fake 기반 최대 2회/4대 실패 계약 테스트를 구현한다.
- masking parity fixture를 Gradle 및 CI 실제 실행 경로에 연결한 뒤 다른 구현 도메인도 선언한다.
- 모듈 경계 검사를 클래스 이름 denylist에서 Gradle configuration·bytecode 검사로 교체한다.
```

---

## 4. 정리(가공)

> 이 구획은 Claude가 만든 목록이다. **codex 지적의 옳고 그름은 판정하지 않는다** — 심각도 재부여·중복 병합·오탐 표시를 하지 않았고, codex가 붙인 `[high]`/`[medium]`을 그대로 옮겼다. 판정과 종합은 `migration-reviewer`와 리더의 몫이다.

| # | codex 심각도 | 지적 요지 | codex가 제시한 근거 위치 (원문 그대로) | 해당 축 |
|---|---|---|---|---|
| 1 | high | 전각 주민등록번호가 마스킹을 통과한다 — 일반 숫자는 Unicode `\d`인데 성별코드만 ASCII `[1-8]`. fixture는 이를 `known_gap`으로 단언에서 제외 | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/privacy/Masking.kt:153-157` | 축 1 (보안 불변식) |
| 2 | high | 공개 provenance 래퍼가 마스킹·복원 불변식을 모두 우회 — `ModelDraft`·`ReviewedBody` 생성자가 공개라 타입이 발급 주체를 강제하지 못함 | `.../privacy/Masking.kt:447-464` | 축 1 |
| 3 | high | 복원된 주민번호가 기본 `toString`으로 로그 노출 — `PlaceholderRestoration`이 data class인데 `toString` 미재정의. `LlmPrompt`·`LlmCompletion`은 숨기는데 이 타입만 빠짐 | `.../privacy/Masking.kt:119-125` | 축 1 |
| 4 | high | 최대 2회 상한과 4대 실패 처리 파이프라인이 아직 존재하지 않음 — `max_tokens` 응답도 정상 `LlmCompletion`으로 반환, 승격할 application 변환 서비스 부재 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/llm/AnthropicProvider.kt:253-264` | 축 2 (계약) · 축 3 (테스트) |
| 5 | high | Kotlin 마스킹이 canonical fixture와 CI에서 한 건도 대조되지 않음 — `@Tag("parity")`·fixture 로딩·actual 산출 없음, 선언 0개, `parityManifestCheck`는 양쪽이 비면 경고만 남기고 성공 | `backend-kotlin/build.gradle.kts:178-216` | 축 4 (선언 범위 대 실제 도달) |
| 6 | medium | `CoreModuleBoundaryTest`가 선언한 모듈 경계를 강제하지 못함 — `Class.forName` 11개 확인뿐이라 `compileOnly`·다른 SDK·Gradle configuration·testFixtures·타 모듈에 미도달. 파일을 제거해도 빌드는 성공 | `backend-kotlin/core/src/test/kotlin/kr/easydoc/core/CoreModuleBoundaryTest.kt:23-53` | 축 4 |

### 축별 커버리지 (요청된 focus가 실제로 답을 받았는가)

리더의 focus 지시는 **계약·보안 불변식·테스트 적정성** 세 축의 교차 검증과 **선언 범위 대 실제 도달** 축을 요구했다. codex 출력이 각 축을 다뤘는지만 기계적으로 대조한 것이며, 답의 타당성은 판정하지 않는다.

| 축 | codex 지적 유무 | 비고 |
|---|---|---|
| 보안 불변식 (마스킹 선행) | 있음 — #1·#2·#3 | 미마스킹 전송 경로·복원 우회·로그 노출 세 갈래로 나뉘어 답이 왔다 |
| 계약 | 있음 — #4 | 최대 2회·4대 예외·보정 실패 시 원본 채택을 한 항목으로 묶어 답했다 |
| 테스트 적정성 | 부분 — #4·#6에 섞여 있음 | **focus에서 명시적으로 물은 다음 항목에 대응하는 별도 지적이 원문에 없다**: PromptTextSnapshotTest·StyleRuleDataSnapshotTest 스냅샷 기준의 독립성(자기 파생 여부), 마스킹 음성 케이스 중 전각 외의 것(잘못된 체크섬·경계 인접 숫자·겹치는 매치·초장문), AnthropicProvider 실패 경로(타임아웃·429·5xx·잘린 스트림·잘못된 JSON), StubAnthropicServer의 요청 본문·헤더 단언 여부. codex는 이 지점들을 조사(`nl -ba ...SnapshotTest.kt`, `...MaskingTest.kt` 등 명령 로그에 있음)했으나 지적으로 올리지 않았다 — **"지적 없음"을 그대로 기록한다. Claude가 대신 지적을 만들어 채우지 않았다.** |
| 선언 범위 대 실제 도달 | 있음 — #5·#6 | focus가 물은 두 대상(parity fixture 도달, CoreModuleBoundaryTest 도달)에 모두 답했다. "떼어내도 아무 테스트가 깨지지 않는 것"에 대해서는 #6에서 `CoreModuleBoundaryTest` 자신을 지목했다 |
| 축 2 (d) 스타일 규칙 단일 정의 | **지적 없음** | `StyleRules.kt`·`Prompts.kt`를 모두 읽었으나(명령 로그 확인) 중복 정의 관련 지적은 원문에 없다 |
| 축 2 (b) 벤더 SDK 직접 import 금지 | **별도 지적 없음** | 전용 grep을 돌렸으나(명령 로그의 `import (com\.a...` 검색) 위반 지적은 원문에 없다. #6이 "그 규칙을 강제하는 장치의 도달 범위" 문제만 다룬다 |

### 전제 확인이 필요한 항목

원문을 지우지 않고, `migration-reviewer`가 판단할 수 있도록 확인 대상만 표시한다.

- **#5의 `backend-kotlin/build.gradle.kts:178-216`** — codex가 이 라인 범위를 `parityManifestCheck` 근거로 지목했다. 라인 번호와 태스크 위치의 대응은 이 에이전트가 재확인하지 않았다(원문 라인은 그대로 옮긴다는 규약).
- **#1의 "fixture가 `known_gap`으로 단언에서 제외한다"** — codex가 `parity/fixtures/masking/masking.json`을 읽고 내린 서술이다. 이 에이전트는 해당 필드 존재 여부를 독립 확인하지 않았다.
- **#4의 "application 변환 서비스가 없다"** — codex가 `find backend-kotlin/application/src/main` 을 실행한 로그가 있다. 이 지적이 "이번 커밋 범위의 결함"인지 "아직 도래하지 않은 Phase의 미구현"인지의 구분은 이 에이전트가 판정하지 않는다.

---

## 5. 미실행·실패 항목

- **없음.** codex 호출은 1회 시도로 성공했고 재시도가 필요하지 않았다.
- 종료 코드 `0`, 대상 판정 `non-empty`, 출력 6,611바이트, verdict `needs-attention` — §7 실패 표의 어느 행에도 해당하지 않는다.
- 출력 잘림 없음. `Next steps:` 목록이 완결된 상태로 끝났고, 헬퍼가 `Turn completion inferred after the main thread finished and subagent work drained.` 로 정상 종료를 보고했다.
- codex 실행 중 명령 1건이 exit 1로 실패했으나(`git show -s --format=fuller 0c377a5 ...` 및 `nl -ba .../LlmPromptTest.kt ...` — 여러 명령을 개행 없이 이어붙인 형태로 보인다) codex가 이후 다른 명령으로 같은 정보를 재수집했고 리뷰는 완주했다. 리뷰 결과 자체의 결손은 관측되지 않았다.

---

## 6. 다음 단계 (게이트 절차상)

이 파일은 리뷰 게이트 **1단계**의 codex 측 산출물이다. 게이트를 닫으려면:

1. `docs/migration/_workspace/reviews/07_core-rebuild_migration-reviewer.md` 가 함께 존재해야 한다 (Claude 독립 리뷰, 병렬 실행분).
2. 두 파일이 모두 존재하면 `migration-reviewer` 를 **2차 호출**해 `07_core-rebuild_cross.md` 를 작성한다 — 대조만 하고 새 지적을 만들지 않는다.

이 에이전트는 판정·수정을 하지 않는다.
