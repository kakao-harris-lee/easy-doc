# application 변환 유스케이스 + parity 배선 + 리뷰·판정 회부 조치

> **작성**: `kotlin-implementer` · 2026-08-13 · **어간**: `02` (Phase 2)
> **입력**: `02_parity-verifier_conversion-spec.md` §3 · `00_requirements-inventory.md` §3.1 ·
> `parity/fixtures/repair-adoption/repair-adoption.json`(25건) · `reviews/07_core-rebuild_cross.md` ·
> `07_privacy-gate_masking-verdicts.md`
> **다음 수신자**: `parity-verifier`(모듈 완료 통지 + fixture 후속) · `migration-reviewer`(리뷰) ·
> 리더(설정 소유권 결정 확인)

---

## 0. 한 줄 요약

core 도메인과 LLM 경계를 잇는 **변환 유스케이스**를 `application` 에 세우고, `masking`·`repair-adoption`
두 도메인의 **Kotlin parity 생산자를 처음 배선**해 게이트를 가동시켰다(성질 56건·단언 189개 충족).
회부된 리뷰 조치 6건과 privacy-gate 판정 4건을 같은 조각에서 닫았고, 그중 **X-2(마스킹 표기 변형)는
음성→양성 전환을 실측으로 남겼다** — 새 케이스 25건 중 17건이 수정 전 패턴에서 실패한다.

| 판정·지적 | 처분 | 근거 위치 |
|---|---|---|
| X-1 parity 배선(차단) | **닫음** — 두 도메인 선언 + 생산자, exit 3 | §3 |
| X-2 전각/구분자 표기 변형 | **닫음** — 종류 A·B 수정 + 과잉 마스킹 가드 + KDoc 재정정 | §4 |
| X-5 provenance 래퍼 | **닫음(조건 1·2)** — 규약 명문화 + 생성 지점 허용목록 탐지기 | §5 |
| X-6 `PlaceholderRestoration` 노출 | **닫음** — `toString` 재정의 | §6 |
| X-7 `MaskedText` 상시 탐지기 | **닫음** — 리플렉션 가드 + 음성 대조 | §6 |
| X-8 스캐너 CI 배선 | **닫음** — `quality` 잡, `--no-fail` 없음 | §7 |
| X-11 `baseUrl` 노출 | **선제 충족** — 설정 표면에 없음 + 상시 가드 | §2 |
| X-16 cause 미부착 / X-17 주석 | **닫음** — 단언 3건 / 한 줄 | §6 |

---

## 1. 변환 유스케이스 — 무엇을 어떻게 세웠나

`backend-kotlin/application/src/main/kotlin/kr/easydoc/application/conversion/`

| 파일 | 담는 것 |
|---|---|
| `ConvertDocumentUseCase.kt` | 마스킹 → 프롬프트 → LLM → 후처리 → (조건부 보정 → 채택 판정) → 결과 |
| `RepairDecision.kt` | 채택 판정식(순수). `decideRepairAdoption(original, candidate, placeholders)` |
| `CompletionBudget.kt` | 완성 요청 예산. 상한 상수 `MAX_LLM_CALLS_PER_CONVERSION = 2` |
| `ConversionResult.kt` | 결과 sealed 타입 · `ConversionFailureKind` · `ConversionUsage` |

### 1.1 호출 상한을 **구조로** 강제한 방식 (CNV-01)

명세 §3.1 (가) 2 가 요구한 것은 "보통 2회"가 아니라 **구조적으로 2회**다. 그래서 두 장치를 겹쳤다.

1. **루프가 없다.** `Pass.finish` 는 `if (issues.isEmpty()) keep(draft) else repairOnce(...)` 한 줄이고,
   `repairOnce` 는 이름 그대로 한 번만 부른다. `while` 도 `repeat` 도 없다 — 검사→호출을 반복하는
   구조에 사후 카운터를 붙이면 그것은 상한이 아니라 기대값이다.
2. **`CompletionBudget` 이 3회째 시도에서 터진다.** 도메인 예외가 아니라 `IllegalStateException` 이다 —
   예산 초과는 사용자 입력 문제가 아니라 코드 구조가 바뀐 것이고, 도메인 예외로 감싸면 HTTP 5xx 로
   번역돼 §5 Phase 7 즉시 중단 기준에 해당하는 사건이 평범한 오류로 묻힌다.

**"루프가 아니다"를 재는 방법을 fixture 와 다르게 골랐다.** fixture `conversion-no-repair-loop` 는 대본
2건짜리 엄격 provider 로 재는데, 그러면 3회째에 하네스가 죽어 "터졌다"만 남고 **몇 번 불렀는지는
알 수 없다.** 단위 테스트 쪽은 응답 10건을 주는 **관대한** provider 로 재고 `llmCalls == 2`,
`unusedTurns == 8` 을 단언한다. 두 방어선은 서로를 대신하지 못한다(명세 §5.2 의 `repair-loop`
두 변형 비교와 같은 이유이며, 여기서는 그 둘을 각각 다른 장치가 맡는다).

### 1.2 전송 시도와 완성 요청의 분리 계측

`ConversionUsage.llmCalls` 는 **완성 요청 수**이고, 유스케이스는 전송 시도 수를 **모른다**.
전송 계측은 어댑터의 몫이라 `FakeLlmProvider` 에 `transportAttemptsPerCall`·`transportAttempts` 를
추가하고 하네스가 그 값을 산출물로 옮긴다. 합쳐 세면 상한이 어댑터 재시도 설정에 따라 흔들리고,
모델에게 실제로 몇 번 물었는지도 잃는다(§3.1 (가) 4).

### 1.3 4대 예외 — 같은 사건, 반대 처분

`Outcome` 은 **호출 위치와 무관하게** 같은 어휘(`Rejected(kind)`)로 결과를 낸다. 그것을 변환 실패로
볼지 삼킬지는 **부르는 자리**가 정한다 — `run()` 은 실패로 올리고, `repairOnce()` 는 `?: keep(draft)` 로
삼킨다. 두 위치를 같은 코드로 뭉뚱그리면 반드시 한쪽이 틀린다는 것이 §3.1 (라) 의 지적이라,
**분류는 한 곳에 두고 처분만 갈랐다.**

절단을 빈 결과보다 먼저 본다 — 잘린 응답이 마침 후처리 뒤 비면 두 조건이 함께 성립하는데,
사용자가 취할 조치는 "문서를 나눠 올리기"이지 "다시 시도"가 아니다.

### 1.4 재시도를 만들지 않았다

인계대로 이 계층에도 어댑터에도 재시도가 없다. 재시도 정책 전부는 worker 몫이다.
`AnthropicProviderResponseTest` 의 「어댑터는 스스로 재시도하지 않는다」가 그 사실을 계속 지킨다.

---

## 2. 설정 소유권 결정 — **`infrastructure` 가 provider 조립을 소유한다**

### 2.1 결정

- `EasyDocProperties.LlmProperties`(api) 를 **지우고**, `infrastructure/llm/LlmProviderConfiguration.kt` 에
  `@ConfigurationProperties(prefix = "easydoc.llm") LlmProperties` 를 새로 두었다. YAML 키와 환경변수
  이름은 그대로다.
- 같은 파일의 `@Configuration LlmProviderConfiguration` 이 `LlmProvider` 빈을 만든다.

### 2.2 근거 — 다른 선택지가 없다

조립하려면 **설정값과 구현 클래스를 동시에** 봐야 하는데, 그 둘을 함께 볼 수 있는 모듈이 하나뿐이다.

| 모듈 | 왜 안 되나 |
|---|---|
| `api`·`worker` | `runtimeOnly(project(":infrastructure"))` — 컴파일 시점에 `AnthropicProvider` 타입이 안 보인다. 이 제약은 사고가 아니라 설계다(api 소스가 LLM·JDBC 타입을 보지 못하게 하는 것이 목적) |
| `application` | `infrastructure` 를 아예 의존하지 않는다(포트만 안다) |
| `core` | Spring 을 모른다 |

배선은 `ApiApplication`·`WorkerApplication` 의 `@SpringBootApplication(scanBasePackages = ["kr.easydoc"])`
+ `@ConfigurationPropertiesScan("kr.easydoc")` 로 성립한다. **runtimeOnly 의존만으로 충분하고 모듈 경계
불변은 그대로다** — api·worker 소스는 여전히 그 파일의 어떤 타입도 보지 못한다.

이전 배치가 왜 성립하지 않았는지도 적어 둔다: 설정이 `api` 에 있으면 값을 읽는 쪽과 쓰는 쪽이 서로를
볼 수 없어 **아무도 조립할 수 없는 설정**이었다. 실제로 `AnthropicSettings(` 를 만드는 비테스트 코드가
0건이었던 것이 그 증상이다(privacy-gate §4.1 실측과 일치).

### 2.3 함께 진 조건 — `baseUrl` 을 설정으로 열지 않는다 (X-11 해제 조건 1)

`LlmProperties` 에 `baseUrl` 필드가 **없다.** 누락이 아니라 조건이다 — 문서 본문이 나가는 **대상**을
바꾸는 값이라 설정 한 줄로 평문 `http` 나 제3자 호스트로 돌릴 수 있으면 안 된다. `AnthropicSettings.baseUrl`
은 생성자 기본 인자(컴파일 상수)로 남고 테스트 스텁 서버만 그것을 쓴다.

**주석으로만 두지 않았다.** `LlmProviderConfigurationTest` 의 「설정 표면에 호출 대상을 여는 필드가
없다」가 `LlmProperties` 의 선언 필드 이름을 훑어 `url|uri|endpoint|host|baseurl` 을 잡는다.
Kotlin 리플렉션 대신 자바 리플렉션을 쓴 이유는 이 모듈에 `kotlin-reflect` 명시 의존이 없어,
전이 의존성이 빠지는 날 가드가 조용히 사라지기 때문이다.

**Phase 5 에 남은 것**: 응답 본문 크기 상한(해제 조건 3)과 그 회귀 단언(4). 이번 조각 범위 밖이다.

### 2.4 부수 결정 두 가지

- **기본 모델 `claude-sonnet-5` 유지.** 품질 결정은 골든셋 몫이고, 여기서 값을 바꾸면 통과율 기준선이
  흔들린다.
- **`openaiApiKey` 를 옮기지 않았다.** OpenAI 어댑터가 없어 값이 닿을 곳이 없다. 설정했는데 아무 일도
  일어나지 않는 자리를 만들지 않는다(`LlmOptions` 가 `temperature`·`effort` 를 뺀 것과 같은 판단).
  어댑터가 생기는 조각에서 함께 들어온다.
- **모르는 벤더 이름은 조립 시점에 던진다.** "기동은 막지 않는다"는 **비밀값 누락**에 대한 규칙이고
  (키가 없어도 앱은 뜨고 그 값이 필요한 요청만 거절한다), 열거값 오타는 배포 설정 오류다.
  `AnthropicEffort.from` 이 이미 같은 판단으로 생성 시점에 던지고 있어 두 자리를 같게 뒀다.

---

## 3. parity 배선 (X-1)

### 3.1 무엇을 만들었나

| 자리 | 파일 |
|---|---|
| 입력 하네스 | `core/src/testFixtures/.../parity/ParityFixtures.kt` (`parity.fixtures.dir` 시스템 프로퍼티) |
| `masking` 생산자 | `core/src/test/.../privacy/MaskingParityTest.kt` (`@Tag("parity")`) |
| `repair-adoption` 생산자 | `application/src/test/.../conversion/ConversionParityTest.kt` (`@Tag("parity")`) |
| 선언 | `backend-kotlin/parity-domains.txt` 에 두 줄 — **구현과 같은 커밋** |
| 빌드 배선 | 루트 `build.gradle.kts` 에 `parity.fixtures.dir`·`easydoc.kotlin.source.root` 주입 |

**기대값을 테스트에 베껴 적지 않았다.** `ParityFixtures` 는 케이스의 `input` 만 주고 `assert`·`reference`
는 아예 노출하지 않는다 — 생산자가 기대값을 볼 수 있으면 그것에 맞추는 코드를 쓰게 되고, 그 순간
게이트는 구현이 아니라 베끼기를 검사한다(명세 §7-2).

### 3.2 실측

```
$ cd backend-kotlin && ./gradlew parityHarness
parity 선언 2개 전부 산출물 확인: masking, repair-adoption            BUILD SUCCESSFUL

$ uv run python .claude/skills/python-kotlin-parity/scripts/compare_parity.py \
    --fixture parity/fixtures --actual parity/actual \
    --only-domain masking --only-domain repair-adoption
[충족] masking · masking.json — 성질 31건/단언 114개
[충족] repair-adoption · repair-adoption.json — 성질 25건/단언 75개
부분 검증 통과(게이트 아님): 도메인 2/2 / 성질 판정 56건(단언 189개) / 참고 갈림 1건 /
  미검증 0건 / 불충족 0건 / 도메인 누락 0개 / 파일 2개                 EXIT=3
```

**exit 3 은 부분 검증 통과이지 게이트 통과가 아니다.** 나머지 6개 도메인(`text`·`style`·`style-tables`·
`prompts`·`postprocess`·`export`)은 값 비교를 받지 않으며 CI 가 매 실행 그 목록을 경고한다.

### 3.3 참고 갈림 1건 — 기록했고, 이유는 §4다

`masking-known-gap-rrn-fullwidth` 가 `diverge` 다. **이것은 결함이 아니라 §4 의 개선이 만든 것이다** —
fixture 의 `reference` 는 "전각 RRN 은 마스킹되지 않는다"는 현행 Python 관측값을 담고 있고,
Kotlin 은 이제 마스킹한다. 성질 단언(`restores_input`·`placeholder_scheme`)은 양쪽 다 통과한다.

privacy-gate 판정 §1.6 이 *"전환 시 `reference_divergence` 가 뜨는 것이 정상이며 원장에 사유와 함께
기록한다"* 고 예고한 그대로다. `--record-reference` 로 `parity/reference-ledger/masking.json` 을
만들었고(31건 중 갈림 1건, 본문 없이 경로·SHA-256 만), 기록 후 재실행에서 exit 3 이다.

> **`parity-verifier` 에게 남은 몫**(판정 §1.6): fixture 의 제외 사유 문장 삭제, `known_gap` → `absent`
> 전환, 구분자 변형 신규 케이스 7종. 그 전까지 전각 RRN·구분자 변형을 지키는 것은 **Kotlin 단위
> 테스트뿐**이고, fixture 는 어느 방향도 단언하지 않는다.

### 3.4 하네스 계약을 지킨 지점 (명세 §3.3·§7)

- `easy_text` 는 실패 시 **키를 빼지 않고 `null`** 을 싣는다.
- fake provider 는 **엄격하다** — 대본 소진 시 던진다.
- `llm_calls` 와 `transport_attempts` 를 **따로** 싣는다.
- `failure_kind` 는 `when` 으로 손수 적었다. `name.lowercase()` 로 유도하면 enum 상수 이름을 바꾸는
  리팩터링이 산출물 값을 조용히 바꾸고, 게이트가 구현 변경을 요구 변경으로 착각한다.

---

## 4. X-2 — 마스킹 표기 변형 (privacy-gate 판정 §1)

### 4.1 고친 것

판정이 실측으로 가른 두 종류를 그대로 닫았다. **마스킹 전 정규화는 기각된 방향이라 쓰지 않았고,
패턴만 바꿨다** — 파이프라인 단계가 늘지 않아 `restores_input` 과 마스킹 선행 불변식이 둘 다 그대로다.

| 종류 | 무엇이었나 | 어떻게 닫았나 |
|---|---|---|
| **A** — ASCII 숫자 범위 | RRN 성별코드가 `[1-8]` 이라 전각 성별코드에서 매치가 끊김. 카드번호에는 없음 | 자리를 `(\d)` 로 잡고 **매치된 문자의 십진값**을 `Character.digit(c, 10) in 1..8` 로 판정. 전각뿐 아니라 **모든 유니코드 십진 숫자 체계**가 한 번에 덮인다 |
| **B** — ASCII 구분자 리터럴 | RRN 의 `[ \t]`·`-` 와 CARD 의 `[- ]`. **두 패턴 모두** | 하이픈류 6종·공백류 6종을 상수 둘로 두고 RRN(`공백* 하이픈? 공백*`)·CARD(합친 한 자리)가 **같은 상수에서 파생**해 쓴다 |

두 가지를 판정 지침대로 지켰다.

- **거부된 매치는 구간을 점유하지 않는다.** 판정을 `candidateSpans` 단계에서 하므로 거부된 스팬은
  `spans` 에 들어가지 않고, 뒤이은 CARD 패턴이 같은 자리를 볼 기회를 잃지 않는다.
- **`\s` 를 쓰지 않았다.** 개행·캐리지리턴이 들어가면 서로 다른 줄의 숫자열이 붙어 **진짜 과잉
  마스킹**이 된다.

문자를 소스에 리터럴로 적지 않고 `\uXXXX` 로 적었다 — 전각·대시류는 눈으로 구별되지 않아 리터럴이면
다음 사람이 무엇이 빠졌는지 셀 수 없다.

### 4.2 음성 → 양성 전환 실측 (요청받은 근거)

`MaskingTest.NotationVariants` 25 케이스를 **수정 전 패턴**으로 되돌려 돌렸다(제품 파일을 정확한 문자열
치환으로 바꾸고 `try/finally` 로 복원 — `cp` 를 쓰지 않았다. 복원은 바이트 동일 확인).

```
수정 전 패턴에서: tests/skipped/failures/errors = (25, 0, 17, 0)
수정 후:          25/25 통과
```

**실패한 17건 = 이번 수정이 실제로 닫은 자리**, 통과한 8건 = 원래 성립해야 하는 회귀 가드.

| 종류 | 수정 전 실패 | 내용 |
|---|---|---|
| A | 3건 | 전각 RRN 전체 · 성별코드만 전각 · 아라비아-인도 성별코드 |
| B(RRN) | 9건 | U+FF0D · U+2212 · U+2013 · U+2014 · U+2010 · U+00A0 · U+3000 · U+2007 · U+202F |
| B(CARD) | 5건 | 전각 하이픈 · NBSP · 전각 공백 · 엔 대시 · 전각 숫자+전각 하이픈 |
| **가드(수정 전에도 통과해야 정상)** | **0건 실패** | 전각 성별코드 9·0 거부 4건 · 개행/CR 미결합 3건 · 앞 6자리만 전각 1건 |

가드 8건이 수정 전에도 통과한 것이 중요하다 — **닫은 것이 표기 변형뿐이고 과잉 마스킹 방향으로는
아무것도 넓히지 않았다**는 뜻이다. 특히 성별코드 9·0 거부가 **전각에서도** 성립하는 것은 값 판정으로
바꾼 결과 자동으로 얻어진 것이고, 판정 §1.4 가 "단언 없이 두면 다음 회차에 되돌아간다"고 지목한
자리라 케이스로 못박았다.

### 4.3 KDoc 재정정 (판정 §1.6 후속)

`UnicodeRegex.kt` KDoc 을 **두 번** 고쳤다. 1차(회부 직후)는 결함을 성별코드 하나로 서술해 *"카드번호처럼
패턴이 `\d` 로만 이뤄진 것은 전각도 잡힌다"* 고 적었는데, 판정이 지적한 대로 그것은 **숫자 자리에
대해서만 참**이었다. 2차에서 종류 A·B 를 모두 반영하고 **"이 플래그가 덮는 것은 축약 클래스뿐"** 을
절 제목으로 올렸다 — 고친 자리가 이 파일이 아니라 패턴 쪽이라는 것이 요점이다.

---

## 5. X-5 — provenance 래퍼 (판정 §2.3 조건 1·2)

### 5.1 조건 1 — 규약 명문화 (완료)

`Masking.kt` 에 「provenance 래퍼 사용 규약」 절을 신설했다. 이전 KDoc 은 "무너진다"는 **경고**만 있고
"누가 만들어도 되는가"라는 **규칙**이 없었다. 네 문장을 규칙으로 적었다(만들 수 있는 곳 / 만들면 안 되는
값·자리 / `ModelDraft` 로 감쌀 수 있는 값 / `edited_text` 는 제출 전까지 `null`).

요구사항 대장에도 **INV-01-a** 로 올렸다 — 주석으로만 두면 Phase 4 게이트에서 세어지지 않는다.

### 5.2 조건 2 — 생성 지점 허용목록 탐지기 (완료)

`core/src/test/.../privacy/ProvenanceCreationSitesTest.kt`. `backend-kotlin` 아래 **모든** `.kt`
(`build/` 제외)를 훑어 `ModelDraft(`·`ReviewedBody(` 생성 지점을 허용목록과 대조한다.
검사는 두 방향이다.

1. **허용목록 밖의 생성** → 실패. 풀려면 줄을 더해야 하고 그 diff 가 리뷰에 올라간다.
2. **죽은 허용 줄** → 실패. 낡은 줄은 **조용히 권한을 넓힌다** — 그 파일에 나중에 전혀 다른 맥락으로
   생성이 들어와도 통과하기 때문이다.

**음성 대조**: 허용목록 밖(`TmpProvenanceProbe.kt`)에 두 타입 생성을 심고 돌려 실패를 확인했다
(`ModelDraft 을 허용목록 밖에서 만든다: [.../TmpProvenanceProbe.kt]`). 프로브는 `try/finally` 로 삭제했고
삭제 확인 후 가드가 다시 통과한다.

현재 허용 목록: `ModelDraft` 6곳(프로덕션 1 = `ConvertDocumentUseCase`, 나머지 테스트),
`ReviewedBody` 1곳(테스트뿐). **`ReviewedBody` 의 프로덕션 생성 지점은 0이고 그것이 정상이다** —
검수 제출 API 는 Phase 3~4 다. 지시받은 대로 이번 조각에서 만들 일이 없었다.

### 5.3 남는 한계 (닫은 척하지 않는다)

- `ModelDraft` 자체의 기본 `toString()` 은 여전히 본문을 찍는다(`@JvmInline value class` 의 기본
  동작). 내 결과 타입(`ConversionResult.Converted`)은 `toString` 을 재정의해 길이만 남기지만,
  **누군가 `ModelDraft` 를 직접 로거 인자로 넘기면 본문이 나간다.** 타입 선언 자체는 privacy-gate
  판정 대상이라 이번에 손대지 않았다 — **판정이 필요하면 이 문장이 근거다.**
- 탐지기는 리플렉션·문자열 조립·자기 자신의 삭제를 막지 못한다.

---

## 6. 교차 종합 회부 조치 (X-6·X-7·X-16·X-17)

| # | 조치 | 확인 |
|---|---|---|
| X-6 | `PlaceholderRestoration` 에 `toString` 재정의 — 본문은 길이, 라벨 목록은 건수 | 형제 타입 `LlmPrompt`·`LlmCompletion` 과 같은 방식. 이 타입의 `text` 는 **자리표시자가 진짜 주민등록번호로 되돌아간 본문**이라 저장소에서 가장 위험한 축이다 |
| X-7 | `MaskedTextGatewayTest` 신설 — 생성 진입점 private · companion 통로 1개 · mask 가 실제로 마스킹 | **음성 대조**: `internal fun wrap(masked: String)` 을 되살려 심으니 `[mask, wrap-sDY7kDk]` 로 잡힘. 되돌린 뒤 통과 |
| X-16 | `AnthropicProviderResponseTest` 에 `hasNoCause()` 3건(HTTP 오류·형식 오류·타임아웃) | "키가 새지 않는 다섯 겹" 중 이 한 겹만 단언이 없었다 |
| X-17 | `core/build.gradle.kts` 주석의 존재하지 않는 테스트 이름 정정 | `CoreHasNoSpringOrDbDependencyTest` → `CoreModuleBoundaryTest` |

**X-7 에서 `LlmPromptTest` 의 가드를 그대로 복사하지 않았다.** `MaskedText` 는 `@JvmInline value class`
라 소스 생성자가 JVM 생성자로 남지 않는다 — `declaredConstructors` 의 비합성 항목이 **0개**여서
같은 검사를 복사했다면 대상이 없어 **항상 통과하는 빈 검사**가 됐을 것이다(실측으로 먼저 확인했다).
실제 생성 진입점은 정적 `constructor-impl` 이고 **그 가시성이 소스 생성자의 가시성을 반영한다.**
그 메서드를 찾지 못하면 통과시키지 않고 **던진다** — 대상이 사라진 가드는 통과가 아니라 미검사다.

---

## 7. X-8 — 스캐너 CI 배선 (판정 §3.3, 리더 승인)

`.github/workflows/ci.yml` 의 **`quality` 잡**, `uv run mypy . .claude` 바로 다음에 넣었다.

```yaml
- name: 데이터 보호 불변식 스캔 (BLOCK 후보 0건 유지)
  run: uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py
```

지침대로 **`--changed` 없음 · `--no-fail` 없음 · `kotlin` 잡 아님**(SCAN_ROOTS 도달의 57%가
backend-kotlin 밖이라 좁은 잡에 붙이면 선언한 범위보다 좁은 자리에서 돈다).

**배선 후 실측(이 커밋 시점)**: `검사 범위: 전수. 검사 파일 183개` · **exit 0**.
판정 시점 171개에서 183개로 는 것은 이번 조각이 파일을 더했기 때문이고, BLOCK 후보는 여전히 0이다.

막지 못하는 것: 이 스텝 자체의 삭제. 최종 방어선은 그 diff 가 리뷰에 올라가는 것이고,
**한 칸 더 옮기지 않았다**(판정 §3.3 과 같은 판단).

---

## 8. 갈림·미해결 기록

### 8.1 요구가 아직 정하지 않은 자리 (판정 대기 — 내가 정하지 않았다)

| # | 무엇 | 누구 |
|---|---|---|
| ① | **채택 판정식이 본문·팩트 손실을 보지 않는다.** 보정이 문서 절반을 지워도 위반 건수가 줄면 채택된다. 위반은 본문이 짧을수록 줄기 쉬우므로 판정식이 축약을 개선으로 오인하는 방향으로 기울어 있다. 마스킹 대상이 없는 문서에서는 자리표시자 축이 비어 가드가 사실상 없다. **기준("본문을 잃지 않았다")이 요구사항에 없어 지금 정하면 정상적인 축약형 보정을 전부 기각할 위험** | 리더 |
| ② | **계약 `failure_code` 가 enum 대신 "예외 클래스명" 규칙이다.** 따르면 Kotlin 이 Python 클래스 이름을 베껴야 하고, Python 을 지우면 규칙이 가리킬 대상이 없어진다. 구현은 요구 수준 이름(`truncated`/`empty_result`/`provider_error`)을 쓰고, `ConversionFailureKind` KDoc 에 "계약의 `failure_code` 가 아니다"를 명시했다 | `contract-keeper` (§9-E) |
| ③ | **`ModelDraft.toString()` 이 본문을 찍는다** (§5.3) | `privacy-gate` |

### 8.2 Python 동작과 갈린 자리

| 자리 | 갈림 | 판단 |
|---|---|---|
| 전각·구분자 표기 RRN/카드 | Python 은 놓치고 Kotlin 은 가린다 | **요구사항 쪽이 Kotlin 이다**(privacy-gate 판정 §1.3). fixture 케이스 id `masking-known-gap-rrn-fullwidth` 에 참고 갈림으로 기록 |
| 실패 코드 문자열 | Python 은 예외 클래스명, Kotlin 은 요구 수준 이름 | 계약이 값을 열거하기 전까지 유보(§8.1 ②) |

### 8.3 미포팅 잔여

- `text`·`style`·`style-tables`·`prompts`·`postprocess`·`export` 도메인의 parity 생산자.
  (`prompts`·`postprocess`·`style` 은 core 구현이 이미 있고 **생산자만** 없다 — 다음 조각의 값싼 표적이다.)
- 내보내기 시점 복원(`export` 도메인)·문서 파서·저장소·API 경계.
- X-11 해제 조건 3·4(응답 크기 상한) — Phase 5.

---

## 9. 검사 결과

| 게이트 | 결과 |
|---|---|
| `cd backend-kotlin && ./gradlew build` (ktlint·detekt·test 포함) | **BUILD SUCCESSFUL** (경고 없음 — `allWarningsAsErrors` 유지) |
| `./gradlew parityHarness` | 선언 2개 전부 산출물 확인 |
| `compare_parity.py --only-domain masking --only-domain repair-adoption` | **exit 3** · 성질 56건·단언 189개 충족 · 갈림 1건(기록됨) |
| `uv run ruff check .` · `ruff format --check .` | All checks passed · 145 files already formatted |
| `uv run mypy . .claude` | Success: no issues found in **129 source files** |
| `uv run pytest` | **1061 passed, 68 skipped, 5 deselected** |
| `scan_privacy_invariants.py` | 183파일 · **exit 0** |

**`tests/golden` 은 돌리지 않았다.** 이번 조각은 프롬프트 문구·스타일 규칙·어려운 말 사전을 **바꾸지
않았다**(마스킹 패턴과 오케스트레이션만 건드렸다). 골든셋이 재는 것은 변환 품질이고 마스킹 표기 변형은
그 입력을 바꾸지 않는다 — 골든 문서에 전각 RRN·전각 하이픈 표기가 있으면 마스킹 건수가 늘 수 있으나,
**그 확인은 privacy-gate 해제 조건 4가 `docs/golden/` 실문서로 하기로 한 절차**이므로 여기서 대신하지
않는다. 프롬프트·스타일을 건드리는 다음 조각에서는 반드시 돌린다.

## 10. `00_progress.md` 에서 고친 행

담당 행 3개에 더해 **「개인정보 마스킹 포팅」 행도 고쳤다.** 이번 조각이 그 행의 진위를 바꿨기
때문이다(X-2 수정 + parity 생산자로 `안 돎` 이 거짓이 됐다). 그대로 두면 리더가 Phase 판정에 쓰는
표가 실물과 어긋난다(X-18 과 같은 형태의 결함).

행을 `충족 = 예` 로 올리자 **범위 가드가 잡았다** —
`tests/test_harness_scope_reach.py::test_판정이_실제로_행을_보고_있다` 가 "기대 18개 / 실제 21개"로 실패.
가드를 무르게 만들지 않고 `EXPECTED_MET_YES_KEYS` 에 세 줄을 **정체성 키로** 더했다. 그 diff 가
"판정 범위를 건드렸다"는 신호로 리뷰에 올라가는 것이 그 상수의 값어치다.


---

## 11. 추가 조치 — privacy-gate 판정 5 (§4-bis, 2026-08-14)

내가 §5.3·§8.1 ③으로 올린 `ModelDraft.toString()` 관측이 **종류 전체**로 판정돼 돌아왔다.
회부한 것은 1종이었는데 판정은 3종 + 스캐너 목록이다 — 내가 자기 코드가 닿은 타입만 보고
형제 둘(`MaskedText`·`ReviewedBody`)을 못 본 것이 맞다.

### 11.1 조치 1 — value class 3종 `toString` 재정의

`MaskedText`·`ModelDraft`·`ReviewedBody` 셋 다 길이만 남긴다. 사유는 타입 하나가 아니라
`Masking.kt` 의 **「value class 와 toString」 절**에 뒀다 — 결함이 한 건이 아니라 **종류**이고,
다음에 본문 래퍼를 만드는 사람이 읽을 자리가 거기이기 때문이다.

이 결함이 왜 여기까지 오지 못했는지도 그 절에 적었다: 일반 class·data class 에는 이미 같은
규율이 있었고(`LlmPrompt` 는 `data class` 를 포기하면서까지 KDoc 한 절을 썼다) **value class
셋에만 한 번도 적용되지 않았다.**

### 11.2 조치 3 — 회귀 단언은 "본문 미포함"을 본다

해제 조건 ③이 지정한 형태로 썼다. **"길이 표기가 있다"를 보지 않는다** — 그것은 형식이
바뀌면 조용히 통과하고, `MaskedText(48자) value=...` 같은 출력도 통과시킨다.

본문에 **마스킹 범주 밖이라 정책상 그대로 남는 값**(전화번호·이메일)을 일부러 섞었다.
"마스킹했으니 안전하다"가 성립하지 않는다는 것이 판정 §4-bis.2 근거 B 이고, 단언이 그
사실을 값으로 붙잡고 있어야 한다.

네 노출 경로(문자열 보간 · 명시 호출 · `Any` 인자(로거 형태) · 컬렉션)를 모두 단언한다.
뒤 둘은 value class 가 **박싱되는** 경로라, 재정의가 인라인 자리에서만 듣고 박싱 자리에서
새는 회귀를 여기서만 잡을 수 있다.

**음성 대조**: 재정의 3건을 걷어내고 돌리면 `LeakPrevention` 4건 중 **3건이 실패**한다
(나머지 1건은 `MaskedItem`/`Secret` 을 보는 기존 단언이라 영향 없음). 되돌린 뒤 전건 통과.

### 11.3 조치 2 — 스캐너 `BODY_NAMES` 확장

`draft`·`modelDraft`·`model_draft`·`reviewedBody`·`reviewed_body`·`reviewed`·
`edited_text`·`editedText`·`result` 를 **더했다. 뺀 이름은 없다.**

`reviewed` 가 함정이었다 — 목록에 `review` 가 이미 있었지만 `\b` 경계 때문에 `reviewed` 에는
걸리지 않는다. "비슷한 이름이 있으니 잡히겠지"가 통하지 않는 자리라 주석으로 남겼다.

**해제 조건 ② 실측 — §4-bis.4 탐침 7건에서 MISSED 0:**

```
CAUGHT  logger.info("변환 완료 {}", draft)        ← 확장 전 MISSED
CAUGHT  logger.info("변환 완료 {}", modelDraft)   ← 확장 전 MISSED
CAUGHT  logger.info("변환 완료 {}", reviewed)     ← 확장 전 MISSED
CAUGHT  logger.info("변환 완료 {}", result)       ← 확장 전 MISSED
CAUGHT  logger.info("변환 완료 {}", easyText)
CAUGHT  logger.info("변환 완료 {}", body)
CAUGHT  logger.info("변환 완료 {}", maskedText)
MISSED = 0
```

### 11.4 ⚠ 확장이 만든 BLOCK 후보 3건 — **privacy-gate 판정 필요. 내가 처분하지 않았다**

확장 직후 전수 스캔이 **exit 1**이 됐다. 판정 §4-bis.4 는 *"지금 이 목록을 고치면 즉시
도달한다"* 고만 적었고 이 3건은 예상하지 못한 것으로 보인다.

```
[BLOCK] LOG-BODY (3건)
- scripts/collect_golden.py:126 — print(f"문서 id: {draft.document.id} | 마스킹 후 본문 {draft.stats.source_chars:,}자")
- scripts/collect_golden.py:132 — print(f"마스킹: {detail} (총 {draft.stats.masked_total}건 …)")
- scripts/collect_golden.py:149 — print(f"자동 분류: {draft.stats.auto_category} …)")
```

**세 줄 모두 오탐으로 보인다.** 보간되는 것이 문서 id·글자 수·건수·분류값뿐이고, 이는
`CLAUDE.md` 가 허용목록으로 못박은 *"문서 ID·길이·처리 상태까지만"* 에 정확히 들어간다.
함수 docstring 자체가 *"통계와 다음 단계만 출력한다(본문·제목·마스킹 원문은 출력하지
않는다)"* 다. 이름이 겹친 것은 여기 `draft` 가 `GoldenDraft`(수집 도구의 통계 묶음)여서다.

**그런데 나는 이것을 처분하지 않았다.** 셋 다 내 판단으로 닫을 수 있는 자리가 아니다.

| 가능한 처분 | 왜 내가 하지 않았나 |
|---|---|
| `BODY_NAMES` 에서 `draft` 제거 | 판정이 명시적으로 금지했다 — *"이름을 빼지 말고 더하기만 한다"*. 해제 조건 ②도 깨진다 |
| `sanctioned` 에 `scripts/collect_golden.py` 추가 | **은폐형**이다. `CLAUDE.md` 규칙 4가 은폐형은 넓히지 말라고 했고, 파일 통째 면제라 그 파일의 **진짜** 유출도 함께 가린다 |
| `refine` 훅으로 멤버 접근 걸러내기 | 정밀도 개선이지만 **BLOCK 규칙의 판정 의미를 바꾸는 일**이고, 안전 멤버 허용목록을 잘못 고르면(`draft.document.title` 같은 2단 접근) 진짜 유출이 빠진다. `migration-safety-gate` 소유 파일이자 `privacy-gate` 의 판정 영역이다 |
| `collect_golden.py` 의 지역 변수 이름 변경 | **게이트를 피해 이름을 바꾸는 것**이다. 그 습관이 자리 잡으면 이 탐지기는 다음부터 아무것도 못 잡는다 |

**따라서 현재 CI `quality` 잡의 스캔 단계는 red 다.** 이것을 알리지 않고 넘기지 않는다 —
판정을 요청하며, 내 권고는 **`refine` 훅**이다(경로 면제와 달리 값의 성질로 거르므로
예외 경로를 넓히지 않는다고 스캐너 자신이 `refine` 주석에 적어 두었다). 다만 안전 멤버
목록의 설계는 `privacy-gate` 가 정해야 한다.

### 11.5 부수 발견 — 탐지기가 자기 자신의 `toString` 에 걸렸다

`toString` 재정의를 넣자마자 §5.2 의 `ProvenanceCreationSitesTest` 가 `Masking.kt` 를
**생성 지점으로 오인**했다. `"ModelDraft(${'$'}{value.length}자)"` 라는 **문자열 리터럴 안의**
`ModelDraft(` 를 호출로 읽은 것이다.

허용목록에 `Masking.kt` 를 추가해 닫지 **않았다** — 그러면 선언 파일에서의 진짜 생성이
영원히 조용해진다. 대신 매칭 전에 큰따옴표 문자열 리터럴을 지운다.

**정밀도 개선이 탐지를 줄이지 않았는지 음성 대조**: 문자열 미끼(`"ModelDraft(는 …)"`)와
진짜 생성(`ModelDraft("진짜 생성 지점")`)을 한 파일에 넣으면 **진짜 쪽만 잡힌다.**
남는 한계(문자열 템플릿 안에서 생성하는 경우)는 KDoc 에 적었다.

### 11.6 검사 결과 (이 절 범위)

| 게이트 | 결과 |
|---|---|
| `./gradlew build` | **BUILD SUCCESSFUL** |
| `ruff check` · `ruff format --check` | All checks passed · 145 files already formatted |
| `uv run mypy . .claude` | Success — 129 source files (스캐너 포함) |
| `uv run pytest` | **1061 passed, 68 skipped, 5 deselected** |
| parity (읽기 전용 재확인) | masking **57건/214단언** · repair-adoption 25건/75단언 충족 · exit 3 |
| `scan_privacy_invariants.py` | **exit 1 — §11.4 판정 대기** |

parity 는 `parity-verifier` 가 병렬로 masking fixture 를 31 → 57건으로 확장한 뒤 다시 돌린
것이다. **내 생산자가 확장된 57건을 그대로 처리해 전건 충족한다** — 종류 A·B 수정과 그들의
신규 케이스가 맞물린다는 뜻이다. 참고 갈림 21건은 그 개선이 만든 것으로 그들 원장에 있다.
그들 소유 파일(`dump_parity_fixtures.py`·`parity/fixtures/masking/`·`parity/reference-ledger/`·
`02_parity-verifier_masking-spec.md`)은 읽기만 했고 커밋하지 않았다.


---

## 12. 후속 배치 (2026-08-14)

privacy-gate 판정 §4-ter·§4-quater·§4-quinquies 와 게이트 08 교차 종합 §10(C-05·C-08·C-18)의
집행은 **별도 문서**에 있다 — `02_kotlin-implementer_masking-grammar.md`.

그 배치가 이 문서의 다음 항목들을 갱신한다.

- §4 의 구분자 집합은 **유한 문법 `SEP` 으로 대체**됐다(과잉 마스킹을 열어 둔 채였다).
- §5.3 이 한계로 적어 둔 `ModelDraft.toString()` 은 닫혔고, 형제 `MaskingResult` 도 함께.
- §5.2 의 provenance 탐지기는 파일 단위 집합에서 **호출 개수 + 별칭·ctor 참조 금지 +
  CI 명시 실행**으로 넓어졌다.
- §7 의 스캐너 배선은 **다중 줄 맹점과 루트 부재**가 드러나 두 번 더 고쳐졌다.
- §11.4 가 판정을 요청한 BLOCK 후보 3건은 **refine 훅으로 해소**됐다(CI red 종료).
