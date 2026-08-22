"""Kotlin **테스트 클래스**가 실재하고 CI 에서 실제로 **돌았는지** 대조한다.

## 왜 이 장치가 필요한가 (게이트 25 H4 — codex 단독 지적)

`./gradlew build` 는 **있는 테스트를 전부** 돌린다. 그래서 테스트 파일을 지우면
스위트는 그대로 초록이고, 사라진 것은 아무 데서도 신고되지 않는다. `ci.yml` 은 이
경계를 스스로 선언해 두었고(`:263-264`), 그 선언대로 클래스별 `--tests` 스텝은 core
탐지기 **둘**에만 붙어 있었다.

이 파일은 그 자리를 Python 하네스로 닫는다 — Kotlin 을 한 줄도 건드리지 않고, `quality`
잡의 "경로 명시" 규약과 같은 방식으로.

## [2026-08-19 게이트 26] 세 결함을 닫았다 — 이 장치는 **양방향으로** 못 믿을 상태였다

교차 종합 `reviews/04_gate25-fixes_cross.md` §2.2·§5.1 이 재현한 것:

- **codex B-1 — 거짓 빨강.** 실행 대조가 JUnit XML 의 `testsuite@name` 을 FQCN 으로
  읽었다. 그 속성에는 `@DisplayName`(이 저장소는 한국어)이 들어간다.
  HEAD 에서 요구 모드가 `1 failed, 26 passed` 로 실패했다.
- **Claude T-1 — 거짓 초록.** `tests` 속성은 `skipped` 를 포함한다. 가드를 지우지 않고
  `@Disabled` 한 줄로 **끄기만** 하면 `tests > 0` 이 참이라 통과했다. 그리고 리포트가
  아예 없으면 `pytest.skip` 으로 통과했다.
- **Claude T-2 / R-10 · codex B-6·B-7 — 미도달.** 대상이 `GUARD_CLASS_FAMILIES`
  (접미사 11개) **열거**라, 이 장치를 세운 바로 그 배치가 만든 가드 4건이 밖에 있었다.
  그중 `AesGcmContentCipherTest` 는 I-7 회귀 전건을 드는 파일이다.

**처방의 성격이 세 번째에서 갈렸다.** 리더 판정 ③ 과 2026-08-14 mypy 수정이 같은 판단을
내렸다 — *"열거를 손으로 늘리는 처방은 다음 게이트에서 또 벌어지므로 **구조로 고친다**"*.
게이트 25 는 열거를 `ci.yml` 에서 이 파일로 **옮겼을 뿐**이었고, 그 불완전성이 같은
배치 안에서 드러났다. 그래서 이번에는 **분모를 열거하지 않는다.**

## 무엇을 분모로 삼는가 — **이름이 아니라 종류**

`backend-kotlin/**/src/test/**` 의 최상위 `class`/`object` 중 **JUnit 테스트 애너테이션을
품은 것 전부**가 분모다. 「가드다움」은 이름으로 판정할 수 없으므로 판정하지 않는다 —
가드는 테스트 클래스의 부분집합이고, 전체를 잡으면 부분집합은 자동으로 들어온다.
`*Test` 접미사조차 없는 `SourceScanFormsProbe` 가 이 정의로 들어오는 것이 그 증거다.

이 정의에는 **면제 목록이 없다.** 「가드가 아닌 것을 근거와 함께 뺀다」는 갈래도
검토했으나 채택하지 않았다 — 그것은 `CLAUDE.md` 규칙 4 ⑵ 가 금지하는 **은폐형**(면제
조항)이고, 그 목록이 커질수록 이 장치가 보는 것이 줄어든다.

## [2026-08-20 게이트 27] codex C-5 — **하한이 없어 동시 축소가 통과했다**

`TEST_CLASS_COUNT` 는 `TEST_CLASSES` 와 **서로** 맞는지만 보는 상수였다. 그래서 탐지기
파일을 지우면서 목록과 개수를 **함께** 줄이면 모든 대조가 통과했다 — 85 라는 정확 일치는
독립 분모가 아니라 **같은 수기 선언의 자기 일치**였다. 게다가 실패 안내문이 그 우회
경로를 그대로 안내하고 있었다(*"파일을 지웠다면 … 도 함께 고쳐야 하고"*).

처방은 같은 저장소의 `SensitiveToStringReachTest` 형태다 — **하한 상수**
(`MIN_TEST_CLASSES`)와 **바닥 목록**(`FLOOR_TEST_CLASSES`)을 둘 다 둔다. 안내문도 "선언을
줄이는 것은 이 대조를 통과시키는 방법이지 결함을 고치는 방법이 아니다"로 고쳤다.

## 재는 것 일곱

1. **선언 ↔ 발견 정확 일치.** 종류로 훑어 나온 집합이 `TEST_CLASSES` 와 **양방향으로**
   같아야 한다. 파일을 지우면 declared 쪽이 남고, 테스트를 새로 넣고 선언을 빠뜨리면
   discovered 쪽이 남는다. **빈 선언은 통과할 수 없다**(SKILL.md 규칙 4 ⑶).
2. **내용 결속.** 선언한 FQCN 마다 `package` 줄과 타입 선언이 그 이름 그대로 있는
   파일이 **정확히 하나** 있어야 한다.
3. **개수 하한.** 파일과 선언을 **함께** 지우는 편집은 위 1·2 로 잡히지 않는다.
   `MIN_TEST_CLASSES` 가 그 축을 **밖에서** 되짚는다 — 라쳇이라 「함께 줄이기」로
   만족시킬 수 없다. **인상 시점은 Phase 경계다**(SKILL.md 규칙 8 — 라쳇 상환).
   *[2026-08-21] 종전에는 이 앞에 「개수 상수」(`TEST_CLASS_COUNT`, 목록과 정확 일치)가
   따로 있었다. 그것이 막는다고 적힌 것을 이 하한이 이미 막으므로 없앴다 — 규칙 7.*
3-b. **바닥 목록.** 다른 판정이 근거로 인용하는 탐지기는 `FLOOR_TEST_CLASSES` 에 있고,
   그것이 선언에서 빠지면 빨개진다. **바닥이지 천장이 아니다** — 새 테스트를 여기 적을
   필요는 없다.
4. **실행 대조.** Gradle 리포트 XML 의 **`testcase@classname`** 으로 집계하고
   **`<skipped>` 는 실행으로 세지 않는다.** `testsuite@name` 은 `@DisplayName` 이라
   FQCN 이 아니다(B-1). `tests` 속성은 skipped 를 포함한다(T-1).
4-a. **이름 축.** 개수만 보면 **파라미터화 여유분이 하한을 가린다.** 평문 `@Test` 의
   리포트 표시명이 실재하는지 이름으로 짚는다(아래 게이트 β 항목 β-20).
5. **리포트 → 선언 역방향.** 리포트에 나오는 클래스는 **전부 선언에 있어야 한다.**
   위 1 의 발견 파서가 조용히 놓친 클래스를 Gradle 자신이 신고하는 축이다 — 파서와
   리포트가 서로를 교차 검증한다.
6. **신선도.** 리포트가 **이번 실행**의 것인지 본다(β-02).
7. **배선.** `ci.yml` 이 이 대조를 **정확한 argv·잡·모드**로 돌리는지 본다(β-19).

## [2026-08-21 게이트 G-β] 차단 넷을 닫았다 — 이 장치는 **자기 신선도와 배선을 몰랐다**

교차 종합 `reviews/04_documents-c4c5_cross.md` §10.1·§11.1 이 차단으로 든 것 넷.
각 항목의 「고치기 전 실측」은 그 항목을 닫는 코드의 KDoc 에 적었다.

- **β-02 — 「돌았다」와 「이번에 돌았다」가 구별되지 않았다.** 리포트는 Gradle 태스크의
  출력이라 `UP-TO-DATE`·`FROM-CACHE` 에서 그대로 남거나 복원된다. 게다가 선언 입력이
  비대칭이어서(계약 파일만 `inputs.file`) 스캐너가 읽는 파일이 바뀌어도 태스크가 돌지
  않았다. 처방 셋: ⑴ `build.gradle.kts` 가 소스 트리·선언 파일·parity fixture 를 선언
  입력으로 건다 ⑵ CI 전체 빌드에 `--no-build-cache` ⑶ 빌드 **앞에서** 박는
  [RUN_MARKER_ENV] 표식과 `testsuite@timestamp` 대조.
- **β-19 — 배선이 문자열 포함으로 측정됐다.** `|| true`·`if: false`·`continue-on-error`·
  외부 파이프 한 줄이 전부 「배선됐다」였다. 지금은 [GATE_ARGV] 정확 일치 + [GATE_WIRING]
  잡·모드 고정이고, 언급하지만 정확하지 않은 스텝은 **지목해 실패**시킨다.
- **β-20 — 계수가 주석을 셌고 리포트는 invocation 을 셌다.** 계수는 이제
  [_blank_comments_and_strings] 를 통과한 뒤에만 세고, 리포트는 **이름으로** 짚는다.
- **β-23 — 분모 0 이 통과했다.** 리포트 축과 라쳇 이력 축 양쪽에 있었다. 아래 절 참고.

## 분모 0 의 처분 — 「없어도 된다」와 「없으면 실패」를 갈라 적는다 (β-23)

앞선 판은 `KOTLIN_GATE_REACH_REQUIRE_REPORT` 가 꺼져 있으면 판정하지 못한 목록을
`print` 하고 통과했다. **그 문장은 통과한 테스트의 stdout 이라 pytest 가 삼킨다** —
실측(2026-08-21): 리포트 디렉터리를 통째로 옮기고 돌렸더니 바닥 28개 전건이 판정
불가인데 **151 passed · exit 0**, 그 문장은 `-rP` 를 붙여야 보였다.

지금은 상태가 셋이고 **어느 쪽도 조용한 통과가 아니다**([_report_state]).

- **`required`**(CI `kotlin` 잡) — 리포트가 없으면 실패, 분모 0 도 실패, 선언 **전건**에
  실행 기록과 신선도를 요구한다.
- **`present`**(로컬·부분 리포트) — 리포트가 있다는 것은 Gradle 이 돌았다는 뜻이다.
  그런데 판정 대상이 0 이면 이 축이 조용히 무효화된 것이므로 **실패**다.
- **`absent`**(`quality` 잡 — Gradle 이 없어 **원리적으로** 리포트가 없다) — 여기만
  「없어도 된다」이고, 그 허용의 근거는 **하나**다: 요구 모드로 이 축을 지는 실행 경로가
  실재한다. [_require_reports_are_carried_elsewhere] 가 같은 실행 안에서 그 실재를
  확인하므로 **빈 선언에서 통과하지 않는다** — 요구 모드 배선이 사라지면 실패한다.
  판정 불가는 `print` 가 아니라 **경고**로 낸다(경고는 pytest 요약에 남는다).

**라쳇 이력 축은 「없어도 된다」가 아니다.** git 이력은 선언된 실행 경로 전부에서 존재하므로
(`ci:quality`·`ci:kotlin` 은 `fetch-depth: 0`, 로컬은 전체 클론) 부재는 **모든 모드에서
실패**다([_report_or_fail_history]). 그 전제를 상시 빨강으로 만들지 않는 배선은
`test_CI_가_라쳇_기준점을_공급한다` 가 되짚는다.

## 이 파일이 닫지 않는 것 (적어 둔다)

- **파일과 선언과 개수를 한 커밋에서 함께 지우는 편집**은 리뷰 diff 가 최종 방어선이다.
  `ci.yml:263-264` 가 같은 자리에서 같은 문장을 적었다. **한 칸 더 옮기지 않는다.**
- **발견 파서는 최상위 선언을 열 0 의 `class`/`object` 로 찾는다.** 여러 줄 문자열
  안에 열 0 짜리 선언 모양이 들어 있으면 유령 선언이 생긴다 — 그때는 discovered 에만
  남아 **빨개진다**(조용하지 않다). 도입 시점 실측: 발견 집합이 Gradle 리포트의 클래스
  집합과 **정확히 일치**했다(양쪽 차집합 0). 개수를 여기 적지 않는 이유는 그것이 다음
  커밋에 곧바로 거짓이 되기 때문이다 — 개수 축은 `MIN_TEST_CLASSES` 하한이 진다
  (2026-08-21 이전에는 `TEST_CLASS_COUNT` 정확 일치가 함께 있었고, 규칙 7 로 없앴다).
"""

from __future__ import annotations

import ast
import functools
import os
import re
import shlex
import subprocess
import time
import warnings
from datetime import UTC, datetime
from pathlib import Path
from xml.etree import ElementTree

import pytest
import yaml

REPO_ROOT = Path(__file__).resolve().parents[1]
BACKEND_KOTLIN = REPO_ROOT / "backend-kotlin"
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"

#: `ci.yml` 이 명시해야 하는 이 파일의 경로. 파일을 옮기면 여기도 함께 고쳐야 한다.
THIS_TEST_PATH = "tests/test_kotlin_gate_reach.py"

#: 리포트 **전건 요구**를 켜는 환경 변수. CI 의 kotlin 잡이 build 뒤에 켠다.
REQUIRE_REPORT_ENV = "KOTLIN_GATE_REACH_REQUIRE_REPORT"

#: **이번 실행의 표식** — 리포트가 「이번에 만들어진 것」인지 판정하는 기준 시각 (β-02).
#:
#: ## 왜 필요한가
#:
#: 아래 실행 대조는 `build/test-results/test/*.xml` 을 읽는다. 그런데 Gradle 은 그 XML 을
#: **태스크 출력**으로 다루므로 `UP-TO-DATE` 면 그대로 두고 `FROM-CACHE` 면 **복원한다.**
#: 이 저장소는 `org.gradle.caching=true` 이고 CI 빌드에 `--rerun-tasks`·`--no-build-cache`
#: 가 없었다. 실측(2026-08-21):
#:
#:   1. `./gradlew :core:test --rerun-tasks` → XML `timestamp="…T08:06:19.523Z"`
#:   2. `rm -rf core/build/test-results/test` 뒤 `./gradlew :core:test`
#:      → `> Task :core:test FROM-CACHE`, XML `timestamp` **그대로 08:06:19.523Z**,
#:        파일 mtime 만 복원 시각으로 갱신됨
#:
#: 즉 **mtime 은 신선도의 근거가 될 수 없고**(복원이 갱신한다), `timestamp` 속성은
#: 내용이라 캐시를 그대로 따라온다. 그래서 판정은 `timestamp` 로 한다.
#:
#: ## 표식을 어디서 박는가
#:
#: CI `kotlin` 잡이 **전체 빌드 앞에서** 현재 UTC 시각을 `$GITHUB_ENV` 에 박는다.
#: 요구 모드에서 이 값이 없거나 파싱되지 않으면 **실패**다(fail-closed) — 표식이 조용히
#: 사라지면 이 축이 존재만 하고 도달이 0 이 되기 때문이다. 배선 자체는 아래
#: `test_CI_가_이_대조를_경로_명시로_배선했다` 가 잡·스텝 순서까지 고정한다.
RUN_MARKER_ENV = "KOTLIN_GATE_REACH_RUN_STARTED_AT"

#: **테스트 클래스임을 드러내는 JUnit 애너테이션.** 이름이 아니라 종류다 —
#: `@Nested` 안에만 있어도 바깥 선언 구간에 들어오므로 함께 잡힌다.
#:
#: **주석·문자열 안의 애너테이션은 세지 않는다** (β-20). 이 정규식을 원문에 그대로 걸면
#: `// @Test` 한 줄이 계수를 유지시킨다 — 실측(2026-08-21, 고치기 전):
#: `ContractErrorBodyReachTest.kt:304` 의 `@Test` 를 `// @Test` 로 바꿨더니 소스 축 계수가
#: **11 그대로**, 리포트 축은 파라미터화 여유분(invocation 16 ≥ 하한 11) 때문에 초록,
#: 이 파일의 게이트가 **151 passed**. 그래서 계수는 [_blanked] 를 통과한 뒤에만 센다.
TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")

#: 한 번의 실행이 **여러 invocation** 을 만드는 애너테이션. 리포트의 `testcase@name` 이
#: 메서드 이름이 아니라 invocation 표시명이므로 이름으로 짚을 수 없다 — 개수로만 센다.
GENERATED_TEST_ANNOTATION = re.compile(
    r"@(?:ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
)

#: 평문 `@Test` — 리포트에 **정확히 하나의 `testcase`** 로 나타나고 이름을 짚을 수 있다.
PLAIN_TEST_ANNOTATION = re.compile(r"@Test\b")

#: 함수 선언 줄. [_blanked] 텍스트에만 건다(주석·문자열 배제).
#: 백틱 이름(`fun \`… …\`()`)과 보통 식별자를 모두 받는다 — 백틱 안에 인용부호를 쓴 이름은
#: 이 저장소에 없다(실측: `grep 'fun \`[^\`]*["\']'` → 0건).
FUNCTION_DECLARATION = re.compile(
    r"^[ \t]*(?:(?:private|internal|public|protected|open|override|suspend|inline)[ \t]+)*"
    r"fun[ \t]+(?:`(?P<quoted>[^`]+)`|(?P<plain>\w+))",
)

#: `@DisplayName("…")` 의 표시명. 값은 **원문**에서 읽는다 — [_blanked] 는 문자열 내용을
#: 공백으로 지우므로, 어느 자리가 진짜 애너테이션인지는 blanked 로 정하고 값은 원문에서 뜬다.
DISPLAY_NAME_AT = re.compile(r"@DisplayName[ \t]*\([ \t]*\"(?P<name>(?:\\.|[^\"\\])*)\"")

#: 열 0 의 최상위 `class`/`object` 선언. 중첩 선언은 들여쓰기되므로 자연히 빠진다.
TOP_LEVEL_DECLARATION = re.compile(
    r"^(?:(?:public|internal|private|open|abstract|sealed|final|data|enum|value|annotation)\s+)*"
    r"(?:class|object)\s+(\w+)",
    re.MULTILINE,
)

#: Kotlin 테스트 클래스의 **정본 목록**. 종류 정의로 훑어 확정했다(게이트 26).
#:
#: 이 목록은 **분모가 아니라 선언**이다 — 분모는 위 종류 정의가 정하고, 여기 적힌 것과
#: 정확히 일치해야 한다. 새 테스트 클래스를 만들면 이 목록에 넣어야 초록이 되고,
#: 그것이 「이번 커밋이 검사 범위에 무엇을 넣었는가」를 리뷰에 드러내는 값이다.
TEST_CLASSES: tuple[str, ...] = (
    "kr.easydoc.api.ApiStartupOnEmptyDatabaseTest",
    "kr.easydoc.api.ApiStartupOnPythonSnapshotTest",
    "kr.easydoc.api.AuthContractTest",
    "kr.easydoc.api.AuthDtoLeakTest",
    "kr.easydoc.api.AuthEndpointReachTest",
    "kr.easydoc.api.AuthUnavailableContractTest",
    "kr.easydoc.api.AuthenticationCoverageContractTest",
    "kr.easydoc.api.CanaryProbeRedactionTest",
    "kr.easydoc.api.ConfigurationPropertiesBindingTest",
    "kr.easydoc.api.ContainerRejectionCoverageContractTest",
    "kr.easydoc.api.ContractErrorBodyReachTest",
    "kr.easydoc.api.ContractHeaderDeclarationTest",
    "kr.easydoc.api.ConversionReadContractTest",
    "kr.easydoc.api.ConversionReadReachTest",
    "kr.easydoc.api.CorsContractTest",
    "kr.easydoc.api.DeletedAccountTokenReachTest",
    "kr.easydoc.api.DocumentBodyLogLeakReachTest",
    "kr.easydoc.api.DocumentContractNodeTest",
    "kr.easydoc.api.DocumentContractTest",
    "kr.easydoc.api.DocumentDeleteReachTest",
    "kr.easydoc.api.DocumentDtoLeakTest",
    "kr.easydoc.api.DocumentEndpointReachTest",
    "kr.easydoc.api.DocumentEnqueueFailureReachTest",
    "kr.easydoc.api.DocumentListContractTest",
    "kr.easydoc.api.DocumentListHeaderFloorTest",
    "kr.easydoc.api.DocumentListReachTest",
    "kr.easydoc.api.ErrorContractTest",
    "kr.easydoc.api.FrameworkErrorContractTest",
    "kr.easydoc.api.HealthContractTest",
    "kr.easydoc.api.MigrateProfileWithoutEncryptionKeyTest",
    "kr.easydoc.api.NamedReferenceGuardTest",
    "kr.easydoc.api.ParserNodeRegistryTest",
    "kr.easydoc.api.PasswordHashLogLeakReachTest",
    "kr.easydoc.api.PasswordHashingBackpressureReachTest",
    "kr.easydoc.api.PrivateResponseHeadersContractTest",
    "kr.easydoc.api.PrivateResponseHeadersReachTest",
    "kr.easydoc.api.RequestFieldConstraintLayerTest",
    "kr.easydoc.api.RequestFieldRejectionLayerTest",
    "kr.easydoc.api.RequestFieldRejectionReachTest",
    "kr.easydoc.api.SensitiveToStringReachTest",
    "kr.easydoc.api.SourceScanFormsProbe",
    "kr.easydoc.api.TitlePolicyContractTest",
    "kr.easydoc.api.UploadFormatContractTest",
    "kr.easydoc.api.ValueSlotInvariantReachTest",
    "kr.easydoc.api.WorkspaceContractTest",
    "kr.easydoc.api.WorkspaceDtoLeakTest",
    "kr.easydoc.api.WorkspaceEndpointReachTest",
    "kr.easydoc.application.auth.AuthServiceTest",
    "kr.easydoc.application.conversion.ConversionParityTest",
    "kr.easydoc.application.conversion.ConvertDocumentUseCaseTest",
    "kr.easydoc.application.document.ConversionQueryServiceTest",
    "kr.easydoc.application.conversion.RepairDecisionTest",
    "kr.easydoc.application.document.DocumentServiceTest",
    "kr.easydoc.application.document.EnvelopeRotationTest",
    "kr.easydoc.application.health.HealthDiagnosisTest",
    "kr.easydoc.core.CoreDomainsParityTest",
    "kr.easydoc.core.CoreModuleBoundaryTest",
    "kr.easydoc.core.ExportParityTest",
    "kr.easydoc.core.ParityActualTest",
    "kr.easydoc.core.ParityDeclarationSyncTest",
    "kr.easydoc.core.SecretTest",
    "kr.easydoc.core.WorkspaceNameLeakTest",
    "kr.easydoc.core.crypto.PlainBodyTest",
    "kr.easydoc.core.document.TitleRulesTest",
    "kr.easydoc.core.easyread.ExportTest",
    "kr.easydoc.core.easyread.GlossCollisionTest",
    "kr.easydoc.core.easyread.PostprocessTest",
    "kr.easydoc.core.easyread.PromptInjectionGuardTest",
    "kr.easydoc.core.easyread.PromptTextSnapshotTest",
    "kr.easydoc.core.easyread.PromptsTest",
    "kr.easydoc.core.easyread.StyleRuleDataSnapshotTest",
    "kr.easydoc.core.easyread.StyleRulesTest",
    "kr.easydoc.core.llm.FakeLlmProviderTest",
    "kr.easydoc.core.llm.LlmCompletionTest",
    "kr.easydoc.core.llm.LlmPromptTest",
    "kr.easydoc.core.privacy.MaskedTextGatewayTest",
    "kr.easydoc.core.privacy.MaskingParityTest",
    "kr.easydoc.core.privacy.MaskingTest",
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest",
    "kr.easydoc.core.text.SurrogatesTest",
    "kr.easydoc.core.text.TextNormalizationTest",
    "kr.easydoc.infrastructure.auth.Argon2PasswordHasherTest",
    "kr.easydoc.infrastructure.auth.AuthenticationWorkUniformityTest",
    "kr.easydoc.infrastructure.auth.JdbcUserRepositoryTest",
    "kr.easydoc.infrastructure.auth.JdbcWorkspaceRepositoryTest",
    "kr.easydoc.infrastructure.auth.JwtAccessTokensTest",
    "kr.easydoc.infrastructure.crypto.AesGcmContentCipherTest",
    "kr.easydoc.infrastructure.crypto.CryptoProfileExemptionTest",
    "kr.easydoc.infrastructure.crypto.CryptoStartupVerificationTest",
    "kr.easydoc.infrastructure.crypto.EncryptionSchemeSchemaTest",
    "kr.easydoc.infrastructure.db.EnvelopeColumnWriteGuardTest",
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest",
    "kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest",
    "kr.easydoc.infrastructure.db.PythonSchemaBaselineTest",
    "kr.easydoc.infrastructure.db.StatementCountingPremiseTest",
    "kr.easydoc.infrastructure.document.DocumentStorageContextTest",
    "kr.easydoc.infrastructure.document.EnvelopeRotationConcurrencyTest",
    "kr.easydoc.infrastructure.document.JdbcDocumentStoreTest",
    "kr.easydoc.infrastructure.document.MaskedItemCodecTest",
    "kr.easydoc.infrastructure.ingest.DocumentExtractorsTest",
    "kr.easydoc.infrastructure.ingest.DocxExtractorTest",
    "kr.easydoc.infrastructure.ingest.ExtractedTextBuilderTest",
    "kr.easydoc.infrastructure.ingest.ExtractionLoggingTest",
    "kr.easydoc.infrastructure.ingest.HwpxExtractorTest",
    "kr.easydoc.infrastructure.ingest.IngestDefensesTest",
    "kr.easydoc.infrastructure.ingest.PdfExtractorTest",
    "kr.easydoc.infrastructure.ingest.ZipBudgetTest",
    "kr.easydoc.infrastructure.llm.AnthropicProviderRequestTest",
    "kr.easydoc.infrastructure.llm.AnthropicProviderResponseTest",
    "kr.easydoc.infrastructure.llm.LlmProviderConfigurationTest",
    "kr.easydoc.worker.WorkerStartupTest",
)

#: 선언 **개수**를 목록과 따로 적는다. 파일과 선언을 함께 지우는 편집이 두 자리에
#: 흔적을 남기게 하는 장치다. 목록을 고쳤으면 여기도 고쳐야 한다.
#:
#: ## [2026-08-21] 이 상수를 없앴다가 **되돌렸다** — 하한은 이것을 대신하지 못한다
#:
#: 없앤 근거는 "`MIN_TEST_CLASSES` 라쳇이 「함께 줄이기」를 이미 막는다" 였다. **그 근거가
#: 틀렸다.** 라쳇은 「함께 줄이기」를 막는 게 아니라 **하한 아래로 내려가는 것만** 막는다.
#: 실측(2026-08-21): 선언 111 · 하한 105 → **6 개까지는 파일과 선언을 함께 지워도 라쳇이
#: 울리지 않는다.** 그중 82 개는 `FLOOR_TEST_CLASSES` 에도 없어 바닥도 못 막는다.
#:
#: 음성 대조로 확인했다 — `AuthContractTest` 의 선언과 `.kt` 파일을 **함께** 지웠을 때 잡은
#: 것은 라쳇도 바닥도 아니라 [test_리포트에_나온_클래스는_전부_선언에_있다] 하나였고, 그것은
#: **Gradle 리포트 XML**, 즉 오래된 빌드 산출물이다. 재빌드하면 그 클래스가 리포트에서
#: 사라져 **조용해진다.** 실제로 그 축은 같은 날 기준선에서 빨간 상태였다(리포트가 트리와
#: 어긋나 있었다) — 가드가 아니라 우연이다.
#:
#: **SKILL.md 규칙 8(라쳇 상환)이 이 창을 넓힌다.** 하한을 Phase 경계에서만 올리므로
#: Phase 안에서 하한과 실측의 간격이 **자란다.** 그래서 이 정확 일치 축은 상환 규약과 함께
#: 쓸 때 **덜** 필요해지는 게 아니라 **더** 필요해진다. 둘은 중복이 아니다:
#: 하한은 「얼마 아래로는 못 간다」, 이 상수는 「한 개도 조용히 못 준다」.
TEST_CLASS_COUNT = 111

#: 선언 개수의 **하한**. 「목록과 개수가 서로 맞는가」(두 수기 선언 사이의 일관성)가 아니라
#: "그 수가 **얼마 아래로는 내려갈 수 없는가**"를 본다. 게이트 27 codex C-5 가 지적한 것이
#: 정확히 그 빈자리였다: 탐지기 파일을 지우면서 목록과 개수를 **함께** 줄이면 모든 대조가
#: 통과했다.
#:
#: **인상 시점은 Phase 경계다** (2026-08-21, SKILL.md 규칙 8 — 라쳇 상환). 종전에는 제품
#: 커밋마다 「그 커밋 직전 실측」으로 올렸고, 그 결과 아래 이력이 하루에 다섯 줄 늘었다.
#: 목적(조용한 축소 차단)은 경계 재기준화로 보존되므로 **Phase 안의 제품 커밋은 이 값을
#: 건드리지 않는다.** 방향은 여전히 [RATCHET_SCALAR_PINS] 가 git 이력 최댓값으로 강제한다 —
#: 낮출 수 없고, 덜 자주 올리는 것은 그 축을 어기지 않는다.
#:
#: 값의 근거는 실측이다 — 게이트 27 의 대상 리비전 `6515548` 에서 이 저장소가 가졌던 수가
#: 85 다. 그 아래로 내려가는 것은 「정리」가 아니라 **축소**이므로, 낮추려면 이 상수를 고치는
#: 별도의 diff 와 사유가 필요하다.
#:
#: 85 → 91 (2026-08-20, `POST /documents` 커밋): 그 커밋 **직전**의 실측이 91 이다.
#: 하한은 라쳇이라 올리기만 한다 — 옛 값을 그대로 두면 그 사이 13개를 조용히 지워도
#: 이 축이 울리지 않는다. 올리는 것은 「이만큼은 이미 있었다」는 사실의 기록이다.
#:
#: 91 → 99 (2026-08-21, 리더): `GET /documents` 커밋 **직전**의 실측이 99 다. 앞 항목과
#: 같은 규율이고, 라쳇을 올리지 않으면 그 사이 늘어난 8개를 조용히 지워도 이 축이
#: 울리지 않는다.
#:
#: 99 → 103 (2026-08-21, 리더): F3 조치 커밋 `dc67ba5` **직전**의 실측이 103 이다.
#:
#: 103 → 104 (2026-08-21, 리더): R-6 커밋 `276e2a5` **직전**의 실측이 104 다.
#:
#: 104 → 105 (2026-08-21, 리더): C5 커밋 `a687de8` **직전**의 실측이 105 다.
MIN_TEST_CLASSES = 105

#: **바닥 목록** — 사라지면 다른 게이트의 결론이 함께 무너지는 탐지기들.
#:
#: `TEST_CLASSES` 전체와 달리 이 목록은 `containsAll` 방향으로만 쓰인다: 새 테스트를 여기
#: 적을 필요는 없고, **여기 있는 것이 빠지는 것만** 막는다. `SensitiveToStringReachTest` 의
#: `KNOWN_SENSITIVE_TYPES` 와 같은 규율이고, 그 파일의 표현을 그대로 쓴다 — "이 목록은
#: 바닥이지 천장이 아니다".
#:
#: 고르는 기준은 「가드다움」이 아니다(그것은 이름으로 판정할 수 없다). **다른 판정의 근거로
#: 인용되는 탐지기**다 — 개인정보 노출면·암호 불변식·계약 본문·범위 도달을 재는 것들.
FLOOR_TEST_CLASSES: tuple[str, ...] = (
    "kr.easydoc.api.AuthenticationCoverageContractTest",
    "kr.easydoc.api.ContractErrorBodyReachTest",
    # 2026-08-20 (리더, 게이트 28 P-2): 원장 조건 18(강제 TRACE 에서 문서 본문 유출 0)을
    #   닫는 근거이고 리더 판정 P-2 가 그것을 인용한다 — 이 목록의 기준(「다른 판정의
    #   근거로 인용되는 탐지기」)에 정면으로 든다. 형제 `CanaryProbeRedactionTest` 는
    #   넣지 않았다: 그것은 판정의 근거가 아니라 **근거의 보호막**이고, 보호막까지 넣으면
    #   기준이 이 파일이 명시적으로 배제한 「가드다움」으로 슬며시 바뀐다. 잔여 성질은
    #   이 항목이 계속 진다 — 그 케이스가 `residualCanaryFragments()` 를 직접 단언한다.
    "kr.easydoc.api.DocumentBodyLogLeakReachTest",
    # 2026-08-21 (리더, C5): **즉시 파기 경로의 소유권 은닉 정본**이고 결과가 **복구 불가**다.
    #   되돌릴 수 없는 연산의 은닉이 무보호로 남는 것은 이 목록의 기준에 정면으로 든다.
    #   바이트·헤더 이름 집합·응답 시간 세 축을 실 소켓 + 실 PostgreSQL 에서 잰다.
    "kr.easydoc.api.DocumentDeleteReachTest",
    # 2026-08-21 (구현 레인, C4 R-7): **등재 사유가 바뀌었다.** 종전 사유는 「값 자리 불변식의
    #   유일한 강제자」였는데, 그 불변식은 이제 `ValueSlotInvariantReachTest` 가 진다 —
    #   이 항목에 그렇게 적어 두면 **죽은 포인터**가 된다(클래스는 남고 메서드만 지우면
    #   모든 게이트가 초록이었다. 실측: R-7 첫 줄).
    #   남기는 사유는 따로 있다: **DL-4·DL-9 가 목록 오퍼레이션의 소유권 은닉을 잰다** —
    #   타인 소유 항목 0건, 남의 작업 공간에 404(빈 목록 아님), 그리고 「없는 것과 남의 것의
    #   응답 바이트가 같다」(X-B2). `privacy-gate` 의 소유 술어 감사가 그 결론을 인용한다.
    # 2026-08-21 (리더, G-β X2 — **판정을 뒤집었다**): 종전에 넣지 않은 근거는 「레인이
    #   범위를 한 자리로 좁혔으니 재지 않은 범위를 선점하지 않는다」였다. **그 논리가 두
    #   가지를 뒤섞었다** — 바닥은 **파일이 지워지는 것**을 막고, 속성의 **주장 범위**를
    #   넓히는 것과 별개다. 그리고 codex 가 `build.gradle.kts` 의 태그 제외로 이 파일이
    #   **한 줄에 침묵할 수 있음**을 짚었다(β-20 이 그 축을 이름 축으로 닫았다).
    #   두 인자 기준의 차단 칸(한 줄 × 탐지 0)이므로 넣는다.
    "kr.easydoc.api.DocumentListHeaderFloorTest",
    "kr.easydoc.api.DocumentListReachTest",
    # 2026-08-21 (리더, C5 P-9): 「주석·KDoc·설정이 이름으로 지목한 테스트·클래스·계약
    #   문면이 실재하는가」 — 그 **종류**의 유일한 강제자다. L-③ 이 걸어 둔 재개봉 조건이
    #   실제로 발동해 승격된 종류이고, 이 세션이 다섯 자리 → 여덟 자리로 늘렸다.
    #   오늘 4자리를 짚어 전부 실 결함이었다(이 세션이 만든 하나 포함).
    "kr.easydoc.api.NamedReferenceGuardTest",
    "kr.easydoc.api.PrivateResponseHeadersReachTest",
    # 2026-08-21 (리더, C4): F3(요청 다섯 필드에 Bean Validation 금지)을 지킨 것은 둘이었다 —
    #   `RequestFieldConstraintLayerTest` 의 애너테이션 부재 스캔과, 「`validation` 이
    #   클래스패스에 없어 **달 수조차 없다**」는 사실. C4 가 그 의존성을 들여 **두 번째를
    #   영구히 없앴고**, 같은 커밋의 음성 대조가 첫 번째의 구멍을 실측했다(`@Valid` +
    #   열거 밖 제약 → 스캔 초록). 그래서 이 파일이 사라지면 F3 의 결론이 함께 무너진다 —
    #   이 목록의 기준(「다른 판정의 근거로 인용되는 탐지기」)에 정면으로 든다.
    #   (종전 이 자리에 「형제 `DocumentListHeaderFloorTest` 는 넣지 않았다 — 레인이 범위를
    #   한 자리로 좁혔으니 재지 않은 범위를 선점하지 않는다」가 있었다. **G-β X2 에서 그
    #   판정을 뒤집었고 그 문장은 이제 거짓이라 지웠다.** 사유는 이 목록에서
    #   `DocumentListHeaderFloorTest` **바로 앞** 주석에 있다 — 바닥은 파일이 지워지는 것을
    #   막고, 속성의 주장 범위를 넓히는 것과
    #   별개다. 편입만 하고 이 선언을 그대로 두었더니 stop-time 게이트가 그 모순을 잡았다.)
    # 2026-08-21 (리더, C4 R-1~R-4): 아래 둘을 함께 넣는다. F3 은 이제 **세 장치가 각각
    #   다른 구멍**을 덮고 그 분담이 실측으로 확인됐다 — `RequestFieldConstraintLayerTest`
    #   만 「경계가 계약보다 느슨한 제약」을 잡고(바이트 축의 관측창은 경계 ±1 이라
    #   `@CodePointLength(max=100)` 대 계약 상한 50 에서 발화하지 않는다),
    #   `RequestFieldRejectionReachTest` 만 임포트 안 된 `@Configuration` 의 `@Bean`
    #   필터와 톰캣 밸브를 본다(슬라이스 축은 못 본다 — C4-R1 도달 표). 어느 하나가
    #   사라지면 나머지 둘이 덮지 않는 자리가 조용히 열린다.
    "kr.easydoc.api.RequestFieldConstraintLayerTest",
    "kr.easydoc.api.RequestFieldRejectionLayerTest",
    "kr.easydoc.api.RequestFieldRejectionReachTest",
    "kr.easydoc.api.SensitiveToStringReachTest",
    "kr.easydoc.api.SourceScanFormsProbe",
    # 2026-08-21 (구현 레인, C4 R-7 — 리더가 명시 허가한 추가): 「성공 응답은 요청이 지정한
    #   값을 반영한다 — 반영할 것이 없으면 성공하지 못한다」의 **유일한** 강제자다. 사라지면
    #   `TypedValueSlotInterceptor` 를 지워도 아무도 모르고, `?limit=` 이 조용히 기본값으로
    #   흡수되며 `?workspace_id=` 이 작업 공간 필터를 지우던 상태로 되돌아간다.
    #   **이 항목이 R-7 의 처방 자체다** — 종전에는 이 불변식이 두 클래스의 메서드로 살아
    #   바닥의 알갱이(클래스)와 보호 대상의 알갱이(메서드)가 어긋나 있었고, 메서드만 지우는
    #   편집이 전 게이트를 통과했다(실측).
    "kr.easydoc.api.ValueSlotInvariantReachTest",
    # 2026-08-21 (구현 레인, C4 R-7): **등재 사유가 바뀌었다.** 종전 사유(경로 변수의 계약
    #   미선언 상태 코드)는 `ValueSlotInvariantReachTest` 로 옮겼다 — 여기 남겨 두면
    #   죽은 포인터다(위 항목과 같은 이유).
    #   남기는 사유: **WR-3·WR-4 가 소유권 은닉의 정본 케이스**다 — 404 이고 403 이 아니며,
    #   없는 자원과 타인 자원의 응답이 상태·본문 바이트·헤더 이름 집합까지 같고, 그 차이가
    #   **응답 시간으로도 새지 않는다**. 세 축 모두 `privacy-gate` 판정이 인용한다.
    "kr.easydoc.api.WorkspaceEndpointReachTest",
    "kr.easydoc.core.CoreModuleBoundaryTest",
    "kr.easydoc.core.ParityDeclarationSyncTest",
    "kr.easydoc.core.crypto.PlainBodyTest",
    "kr.easydoc.core.privacy.MaskedTextGatewayTest",
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest",
    "kr.easydoc.infrastructure.crypto.AesGcmContentCipherTest",
    "kr.easydoc.infrastructure.crypto.CryptoStartupVerificationTest",
    "kr.easydoc.infrastructure.db.EnvelopeColumnWriteGuardTest",
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest",
    "kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest",
    "kr.easydoc.infrastructure.db.StatementCountingPremiseTest",
    "kr.easydoc.infrastructure.document.EnvelopeRotationConcurrencyTest",
    "kr.easydoc.infrastructure.document.JdbcDocumentStoreTest",
    "kr.easydoc.infrastructure.ingest.IngestDefensesTest",
)


#: **바닥 클래스별 `@Test` 개수 하한** — 핀의 알갱이를 보호 대상의 알갱이에 맞춘다.
#:
#: ## 왜 있는가 (C4 R-7)
#:
#: `FLOOR_TEST_CLASSES` 는 **클래스 이름**을 지킨다. 그런데 그 항목들이 지키기로 선언한 것은
#: 대개 클래스가 아니라 **그 안의 단언 몇 개**다. 알갱이가 어긋나면 **메서드만 지우는 편집이
#: 전부 통과한다** — 2026-08-21 실측: 값 자리 불변식의 메서드와 전용 보조만 지우고 서식을
#: 정리했더니 `ktlintCheck detekt build moduleBoundaryCheck parityHarness` 가
#: **exit 0**, 이 파일의 게이트가 **112 passed** 였다.
#:
#: 그 결함은 R-6 만의 것이 아니라 **종류**다 — 「바닥이 클래스 단위인데 보호 대상이 메서드
#: 단위인 모든 항목」. 위 목록에서 자기 클래스가 그 속성 하나만 담은 것은 소수다. 그래서
#: 종류만큼 넓힌다.
#:
#: ## 이름을 열거하지 않는다
#:
#: 메서드 **이름**을 적는 길로 가지 않았다. 그것은 범위 선언형이고 이름을 바꿀 때마다
#: 흔들린다. 여기 적는 것은 **개수**이고, 값은 현재 실측에서 유도했다.
#:
#: ## 라쳇 규율 — `MIN_TEST_CLASSES` 와 같다
#:
#: 올리는 것은 자유(사실의 기록)이고, **내리려면 그 diff 와 사유가 필요하다.** 키 집합은
#: `FLOOR_TEST_CLASSES` 와 **정확히 일치**해야 한다 — 바닥에 클래스를 더하면서 개수를
#: 빠뜨리면 그 항목이 다시 「클래스만 지켜지는」 상태가 되고, 그것이 이 장치가 겨눈 결함이다.
#:
#: ## 이 표를 읽는 축이 **둘**이다 (C4 R-8)
#:
#: 같은 하한을 두 관측면이 쓴다. 무엇을 덮는지가 다르므로 둘 다 있다.
#:
#: | 축 | 세는 것 | 덮는 것 | 덮지 못하는 것 |
#: |---|---|---|---|
#: | **소스 축** | **주석·문자열을 비운** 소스의 `@Test` 수 | **선언이 사라지는 편집**(삭제) |
#: |  | | 과 **주석 처리**(`// @Test`). |
#: |  | | Gradle 없이 돌아 `quality` 잡에서도 잡는다 |
#: |  | | (덮지 못함: 선언은 남고 **안 도는** 편집) |
#: | **리포트 축** | Gradle JUnit XML 의 **실행된** 케이스 수 | **안 도는 편집 전부** — |
#: |  | | `@Disabled`·`assumeTrue`·태그 제외·`--tests` 필터·JVM 인자. |
#: |  | | 기제를 하나도 열거하지 않는다 |
#: |  | | (덮지 못함: 리포트가 없는 실행 경로 — `quality` 잡) |
#: | **이름 축** | 평문 `@Test` 의 리포트 표시명 | 개수 축이 **파라미터화 여유분에 가리는** |
#: |  | (β-20) | 자리. 실측: 소스 11 · invocation 16 · 하한 11 이라 |
#: |  | | 평문 하나가 태그 제외로 빠져도 15 ≥ 11 로 초록이었다 |
#: |  | | (덮지 못함: invocation 생성 테스트의 개별 케이스 — |
#: |  | | 표시명이 invocation 마다 달라 이름으로 짚을 수 없다. |
#: |  | | 그쪽은 「메서드마다 최소 1건」으로만 본다) |
#:
#: 소스 축은 `test_바닥_클래스의_테스트_개수가_하한_아래로_내려가지_않는다`,
#: 리포트 축과 이름 축은 `test_바닥_클래스가_리포트에서_하한만큼_실제로_돌았다` 다
#: (이름 축의 분모가 조용히 줄지 않게 하는 것은
#: `test_테스트_메서드_파서가_애너테이션_전수를_덮는다` 다).
#:
#: **소스 축을 남긴 이유**가 그 칸이다 — `quality` 잡에는 Gradle 이 없어 리포트가 없고,
#: 그 잡에서도 「메서드가 사라졌다」는 잡혀야 한다.
#:
#: ## 잡지 못하는 것 (정직하게)
#:
#: **메서드 껍데기를 남기고 단언만 비우는 편집**은 세 축 모두 통과한다(개수도 그대로, 이름도
#: 그대로, 실행도 된다). 그 자리를 덮는 진짜 답은 **변이 테스트(pitest)** 다 — 「이 가드를
#: 변이시켰을 때 죽이는 테스트가 어딘가에 있는가」를 물으므로 어느 클래스·메서드가 그것을
#: 담는지 알 필요가 없다. 도입은 범위 밖이고 개선 백로그 B-19 에 있다.
#:
#: **종전에 여기 적혀 있던 「`@Disabled` 를 구분하지 않는다」는 이제 거짓이다** — 리포트 축이
#: 그것을 덮는다. 그 문면을 지우지 않고 갱신한 이유는 죽은 포인터를 남기지 않기 위해서다.
#: **바닥 목록의 크기 하한** — 규칙 4 ⑶ 의 뿌리를 막는다.
#:
#: `FLOOR_TEST_CLASSES` 도 `MIN_TESTS_IN_FLOOR_CLASS` 도 **범위 선언형**이고, 그 최대 위험은
#: 좁게 선언되는 것이 아니라 **아무것도 선언되지 않은 채 초록이 되는 것**이다. 실측(2026-08-21,
#: 리더): 두 표를 **함께 비우면** 세 대조가 전부 통과했다 —
#:
#:   1. `set(FLOOR) - set(TEST_CLASSES)` 는 빈 바닥에서 차집합이 비어 통과
#:   2. 키 집합 정확 일치는 **둘 다 비면** 통과
#:   3. `parametrize(sorted(MIN_TESTS_IN_FLOOR_CLASS))` 는 **빈 딕셔너리면 케이스가 0개**라
#:      그 테스트가 아예 돌지 않는다
#:
#: 이 저장소의 선례가 그대로다 — parity 게이트는 선언 도메인 0개에서 exit 0 이었고, 표
#: 판정기는 대상 표가 0개일 때 위반 0건을 냈다.
#:
#: 키 집합이 바닥과 정확히 일치하도록 이미 묶여 있으므로, **바닥이 비지 않음을 보장하면
#: 개수표도 함께 비지 못한다.** 그래서 하한은 여기 하나만 둔다.
#:
#: 값의 근거는 실측이다 — R-7 커밋 `ea32728` 시점의 바닥 항목 수가 26 다. `MIN_TEST_CLASSES`
#: 와 같은 **라쳇**이라 올리기만 하고, 낮추려면 별도의 diff 와 사유가 필요하다.
#:
#: 26 → 28 (2026-08-21, 리더, C5): `DocumentDeleteReachTest`·`NamedReferenceGuardTest` 편입.
#:
#: 28 → 29 (2026-08-21, 리더, G-β X2): `DocumentListHeaderFloorTest` 편입.
MIN_FLOOR_CLASSES = 29

MIN_TESTS_IN_FLOOR_CLASS: dict[str, int] = {
    "kr.easydoc.api.AuthenticationCoverageContractTest": 5,
    "kr.easydoc.api.ContractErrorBodyReachTest": 11,
    "kr.easydoc.api.DocumentBodyLogLeakReachTest": 1,
    "kr.easydoc.api.DocumentDeleteReachTest": 14,
    "kr.easydoc.api.DocumentListHeaderFloorTest": 2,
    "kr.easydoc.api.DocumentListReachTest": 11,
    "kr.easydoc.api.NamedReferenceGuardTest": 16,
    "kr.easydoc.api.PrivateResponseHeadersReachTest": 7,
    "kr.easydoc.api.RequestFieldConstraintLayerTest": 7,
    "kr.easydoc.api.RequestFieldRejectionLayerTest": 5,
    "kr.easydoc.api.RequestFieldRejectionReachTest": 5,
    "kr.easydoc.api.SensitiveToStringReachTest": 5,
    "kr.easydoc.api.SourceScanFormsProbe": 5,
    "kr.easydoc.api.ValueSlotInvariantReachTest": 6,
    "kr.easydoc.api.WorkspaceEndpointReachTest": 22,
    "kr.easydoc.core.CoreModuleBoundaryTest": 1,
    "kr.easydoc.core.ParityDeclarationSyncTest": 4,
    "kr.easydoc.core.crypto.PlainBodyTest": 5,
    "kr.easydoc.core.privacy.MaskedTextGatewayTest": 4,
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest": 6,
    "kr.easydoc.infrastructure.crypto.AesGcmContentCipherTest": 22,
    "kr.easydoc.infrastructure.crypto.CryptoStartupVerificationTest": 11,
    "kr.easydoc.infrastructure.db.EnvelopeColumnWriteGuardTest": 12,
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest": 10,
    "kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest": 13,
    "kr.easydoc.infrastructure.db.StatementCountingPremiseTest": 4,
    "kr.easydoc.infrastructure.document.EnvelopeRotationConcurrencyTest": 3,
    "kr.easydoc.infrastructure.document.JdbcDocumentStoreTest": 24,
    "kr.easydoc.infrastructure.ingest.IngestDefensesTest": 6,
}


#: **라쳇 성질을 가진 수치 핀** — 값 자신이 권위이므로 **값만 보고는 낮아졌는지 알 수 없다.**
#:
#: ## 왜 있는가 (C4 R-9)
#:
#: R-8 이 「한 줄로 강제자를 끄는」 경로를 닫았지만 하나가 남았다: **하한 값을 내리기.**
#: 실측(2026-08-21, 신선한 리포트 기준): 아래 상수들을 **1 씩 내려도 전 게이트가 초록**이다.
#: `@Disabled` 한 줄과 정확히 같은 성질이고(한 줄 · 자동 신호 전부 초록), 그것을 못 봐준
#: 기준이 여기에도 적용된다.
#:
#: ## 외부 기준점이 필요하다 — **git 이력의 최댓값**을 쓴다
#:
#: 저장소 안에서는 원리적으로 닫히지 않는다(값이 권위다). 후보 둘을 재서 골랐다.
#:
#: - **채택: 이력 최댓값.** 「이 상수는 이 파일 이력에서 가졌던 최댓값보다 작을 수 없다」.
#:   낮추려면 이력을 고쳐야 하므로 한 줄로는 안 된다. **모든 이벤트에서 돈다**(push·PR).
#:   비용 실측: 대상 7개 파일의 관련 리비전 합 **64개**, `git show` 64회로 1~2초.
#:   CI 비용은 `fetch-depth: 0`(전체 클론) — 이 저장소 `.git` 은 **112MB**, 커밋 477개다.
#: - **버림: 기준 브랜치(`origin/main`) 대조.** 이력 전체가 필요 없어 더 싸지만 **도달이
#:   조건부**다 — 이 저장소 CI 는 `push: branches: [main]` 과 `pull_request` 로 돌고,
#:   base ref 는 PR 이벤트에만 있다. main 으로의 push 에서는 그 검사가 무의미해지므로
#:   `실행 경로` 를 `ci:<잡>(조건:PR 이벤트)` 로 적어야 한다. 이력 축은 그 조건이 없다.
#:
#: ## 이력이 없을 때 — **조용히 건너뛰지 않는다**
#:
#: 얕은 클론이거나 그 경로의 리비전이 0 이면 판정할 수 없다. 요구 모드
#: (`KOTLIN_GATE_REACH_REQUIRE_REPORT`)에서는 **실패**하고, 꺼져 있으면 **판정하지 못한
#: 목록을 출력**한다. R-8 에서 세운 구분과 같다 — 플래그는 **대상 범위를 넓히는 스위치**이지
#: 검사를 켜는 스위치가 아니다.
#:
#: ## 정당한 상향은 막지 않는다
#:
#: 조건이 「현재 ≥ 이력 최댓값」이므로 올리는 편집은 언제나 통과한다. 라쳇을 못 쓰게 만들면
#: 이 장치가 지키려던 규율 자체를 없애는 것이다.
RATCHET_SCALAR_PINS: tuple[tuple[str, str], ...] = (
    (THIS_TEST_PATH, "MIN_TEST_CLASSES"),
    (THIS_TEST_PATH, "MIN_FLOOR_CLASSES"),
    (
        "backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SensitiveToStringReachTest.kt",
        "MIN_PRODUCTION_CLASSES",
    ),
    (
        "backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/"
        "StatementCountingPremiseTest.kt",
        "MIN_PORT_ADAPTERS",
    ),
    (
        "backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/FlywayBaselineGuardTest.kt",
        "MIN_CRITICAL_STATEMENTS",
    ),
    (
        "backend-kotlin/core/src/test/kotlin/kr/easydoc/core/easyread/PostprocessTest.kt",
        "MIN_NEGATIVE_CASES",
    ),
    (
        "backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/document/JdbcDocumentStoreTest.kt",
        "MIN_DOCUMENT_COLUMNS",
    ),
)

#: **제품 주석이 이름으로 지목한 테스트 클래스**의 `@Test` 개수 하한 (β-03 · β-24).
#:
#: ## 왜 있는가
#:
#: `FLOOR_TEST_CLASSES`·`MIN_TESTS_IN_FLOOR_CLASS` 는 「다른 판정의 근거로 인용되는 탐지기」를
#: 지킨다. 그런데 **제품 소스의 주석이 이름으로 「이것이 그 성질을 잰다」고 지목한 클래스**는
#: 그 목록과 다른 집합이고, 교차 종합이 실측으로 그 어긋남을 짚었다 —
#: `DocumentListContractTest`(Claude β-03) · `DocumentContractNodeTest`·`HealthContractTest`
#: (codex β-24) 가 두 표 밖이었다. 그 클래스에서 메서드만 지우면 제품 주석의 주장이 거짓이
#: 되는데 **자동 신호가 0** 이었다(악용 비용 한 줄 × 자동 탐지 0 = 차단 칸).
#:
#: ## 목록이 아니라 **인구조사**다
#:
#: 키 집합을 손으로 정하지 않는다. `backend-kotlin/**/src/main/**` 의 주석·KDoc 에서
#: 참조 형태(백틱 · 대괄호 · `@see`)로 지목된 `…Test`/`…Probe` 이름을 뽑아 그것이 실제
#: 테스트 클래스로 해소되면 분모에 든다([_named_enforcer_census]). 그래서 **새 지목이
#: 자동으로 분모에 들고**, 핀이 없으면 빨개진다(fail-closed).
#:
#: `MIN_TESTS_IN_FLOOR_CLASS` 와 **겹치지 않는 정확 분할**이다 — 바닥 표가 이미 지키는
#: 클래스를 두 번 적지 않는다. 그 분할을
#: `test_명명된_강제자가_전부_개수_핀을_갖는다` 가 양방향으로 대조한다.
#:
#: ## 잡지 못하는 것
#:
#: `MIN_TESTS_IN_FLOOR_CLASS` 와 같다 — 껍데기를 남기고 단언만 비우는 편집. 그 축은
#: [MIN_ASSERTIONS_BY_CLASS] 가 부분적으로, 변이 테스트(백로그 B-19)가 온전히 진다.
#:
#: 값의 근거는 실측이다 — 2026-08-21 각 클래스의 현재 선언 수를 판정 장치
#: ([_declared_test_count]) 에 직접 물어 적었다(`grep` 이 아니다 — 규칙 2).
MIN_TESTS_BY_NAMED_ENFORCER: dict[str, int] = {
    "kr.easydoc.api.ConfigurationPropertiesBindingTest": 1,
    "kr.easydoc.api.ConversionReadContractTest": 5,
    "kr.easydoc.api.ConversionReadReachTest": 10,
    "kr.easydoc.api.DeletedAccountTokenReachTest": 2,
    "kr.easydoc.api.DocumentContractNodeTest": 15,
    "kr.easydoc.api.DocumentDtoLeakTest": 6,
    "kr.easydoc.api.DocumentListContractTest": 9,
    "kr.easydoc.api.HealthContractTest": 6,
    "kr.easydoc.api.MigrateProfileWithoutEncryptionKeyTest": 2,
    "kr.easydoc.api.PasswordHashingBackpressureReachTest": 1,
    "kr.easydoc.api.UploadFormatContractTest": 3,
    "kr.easydoc.core.easyread.PostprocessTest": 4,
    "kr.easydoc.core.easyread.StyleRuleDataSnapshotTest": 7,
    "kr.easydoc.core.privacy.MaskingTest": 62,
    "kr.easydoc.infrastructure.auth.Argon2PasswordHasherTest": 14,
    "kr.easydoc.infrastructure.auth.JdbcWorkspaceRepositoryTest": 13,
    "kr.easydoc.infrastructure.crypto.CryptoProfileExemptionTest": 4,
    "kr.easydoc.infrastructure.db.PythonSchemaBaselineTest": 4,
    "kr.easydoc.infrastructure.document.MaskedItemCodecTest": 9,
    "kr.easydoc.infrastructure.ingest.DocumentExtractorsTest": 12,
    "kr.easydoc.infrastructure.llm.LlmProviderConfigurationTest": 6,
}

#: **클래스별 단언 토큰 수 하한** — 「껍데기를 남기고 단언만 비우는」 편집을 잡는다 (β-04).
#:
#: ## 왜 개수 표가 하나 더 필요한가
#:
#: `MIN_TESTS_IN_FLOOR_CLASS` 는 `@Test` **선언 수**만 본다. 그래서 교차 종합이 실측으로
#: 세 갈래를 짚었다 — ⑴ 단언 비우기 ⑵ 무해한 단언으로 교체 ⑶ 위험 케이스 삭제 + 더미 추가.
#: 셋 다 선언 수가 그대로다.
#:
#: 이 표는 그중 **⑴ 을 잡고 ⑶ 을 부분적으로** 잡는다. `assert…(` 모양의 토큰 수가 줄면
#: 빨개지기 때문이다. **⑵ 는 잡지 못한다** — 단언을 무해한 것으로 바꾸면 개수가 그대로다.
#: 그 잔여의 정공법은 변이 테스트이고, 사용자 결정으로 백로그 B-19(1순위)에 있다. 여기서
#: 그것을 끌어오지 않는다.
#:
#: ## 어휘가 아니라 **모양**이다
#:
#: 금지·허용 목록을 두지 않는다. `assert` 로 시작하는 식별자 뒤에 여는 괄호가 오는 **모양**을
#: 센다(`NamedReferenceGuardTest` 가 이름 목록 대신 모양을 고른 것과 같은 규율). 주석과 문자열은
#: [_blanked] 가 이미 비웠으므로 `// assertThat(` 한 줄은 세어지지 않는다.
#:
#: ## 이 모양이 **보지 못하는** 자리 — 정직하게 적는다
#:
#: MockMvc DSL(`andExpect { status { isNotFound() } }`)은 `assert` 토큰이 없다. 그 형태만
#: 쓰는 클래스에서는 이 축의 하한이 낮아 방어력이 약하다. 실측(2026-08-21): 저장소 853개
#: `@Test` 본문 중 **86 개**가 직접 `assert` 토큰이 없고, 같은 파일 안 호출을 전이적으로
#: 따라가도 **75 개**가 남는다. 그래서 「모든 본문이 단언에 도달한다」 축은 **오탐 8.8% 로
#: 시작**하고, 오탐 축은 곧 면제 목록을 낳아 규칙 4 ⑵ 의 거부권에 걸린다 — 그 갈래를 버리고
#: 클래스별 개수 하한을 골랐다. 아래 어느 클래스도 토큰 0 이 아니다(실측).
#:
#: 키 집합은 [MIN_TESTS_IN_FLOOR_CLASS] ∪ [MIN_TESTS_BY_NAMED_ENFORCER] 와 **정확히 같다** —
#: 유도되는 집합이므로 새 클래스가 그 둘에 들면 여기에도 들어야 하고, 빠지면 빨개진다.
MIN_ASSERTIONS_BY_CLASS: dict[str, int] = {
    "kr.easydoc.api.AuthenticationCoverageContractTest": 8,
    "kr.easydoc.api.ConfigurationPropertiesBindingTest": 9,
    "kr.easydoc.api.ContractErrorBodyReachTest": 16,
    "kr.easydoc.api.ConversionReadContractTest": 21,
    "kr.easydoc.api.ConversionReadReachTest": 34,
    "kr.easydoc.api.DeletedAccountTokenReachTest": 12,
    "kr.easydoc.api.DocumentBodyLogLeakReachTest": 32,
    "kr.easydoc.api.DocumentContractNodeTest": 40,
    "kr.easydoc.api.DocumentDeleteReachTest": 44,
    "kr.easydoc.api.DocumentDtoLeakTest": 15,
    "kr.easydoc.api.DocumentListContractTest": 30,
    "kr.easydoc.api.DocumentListHeaderFloorTest": 4,
    "kr.easydoc.api.DocumentListReachTest": 29,
    "kr.easydoc.api.HealthContractTest": 13,
    "kr.easydoc.api.MigrateProfileWithoutEncryptionKeyTest": 4,
    "kr.easydoc.api.NamedReferenceGuardTest": 30,
    "kr.easydoc.api.PasswordHashingBackpressureReachTest": 14,
    "kr.easydoc.api.PrivateResponseHeadersReachTest": 18,
    "kr.easydoc.api.RequestFieldConstraintLayerTest": 15,
    "kr.easydoc.api.RequestFieldRejectionLayerTest": 14,
    "kr.easydoc.api.RequestFieldRejectionReachTest": 12,
    "kr.easydoc.api.SensitiveToStringReachTest": 11,
    "kr.easydoc.api.SourceScanFormsProbe": 6,
    "kr.easydoc.api.UploadFormatContractTest": 4,
    "kr.easydoc.api.ValueSlotInvariantReachTest": 35,
    "kr.easydoc.api.WorkspaceEndpointReachTest": 57,
    "kr.easydoc.core.CoreModuleBoundaryTest": 1,
    "kr.easydoc.core.ParityDeclarationSyncTest": 9,
    "kr.easydoc.core.crypto.PlainBodyTest": 7,
    "kr.easydoc.core.easyread.PostprocessTest": 4,
    "kr.easydoc.core.easyread.StyleRuleDataSnapshotTest": 18,
    "kr.easydoc.core.privacy.MaskedTextGatewayTest": 5,
    "kr.easydoc.core.privacy.MaskingTest": 123,
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest": 6,
    "kr.easydoc.infrastructure.auth.Argon2PasswordHasherTest": 22,
    "kr.easydoc.infrastructure.auth.JdbcWorkspaceRepositoryTest": 19,
    "kr.easydoc.infrastructure.crypto.AesGcmContentCipherTest": 35,
    "kr.easydoc.infrastructure.crypto.CryptoProfileExemptionTest": 6,
    "kr.easydoc.infrastructure.crypto.CryptoStartupVerificationTest": 6,
    "kr.easydoc.infrastructure.db.EnvelopeColumnWriteGuardTest": 14,
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest": 18,
    "kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest": 15,
    "kr.easydoc.infrastructure.db.PythonSchemaBaselineTest": 7,
    "kr.easydoc.infrastructure.db.StatementCountingPremiseTest": 7,
    "kr.easydoc.infrastructure.document.EnvelopeRotationConcurrencyTest": 7,
    "kr.easydoc.infrastructure.document.JdbcDocumentStoreTest": 56,
    "kr.easydoc.infrastructure.document.MaskedItemCodecTest": 14,
    "kr.easydoc.infrastructure.ingest.DocumentExtractorsTest": 19,
    "kr.easydoc.infrastructure.ingest.IngestDefensesTest": 13,
    "kr.easydoc.infrastructure.llm.LlmProviderConfigurationTest": 7,
}

#: 표 형태의 라쳇 핀 — 값이 여럿이라 **키별로** 이력 최댓값과 대조한다.
#:
#: 셋 다 「내려가면 보호가 줄어드는」 하한 표다. 새 표를 만들면서 여기 적지 않으면 그 표는
#: 다시 「값 자신이 권위인」 상태가 되고, 그것이 라쳇 축이 겨눈 결함이다.
RATCHET_TABLE_PINS: tuple[tuple[str, str], ...] = (
    (THIS_TEST_PATH, "MIN_TESTS_IN_FLOOR_CLASS"),
    (THIS_TEST_PATH, "MIN_TESTS_BY_NAMED_ENFORCER"),
    (THIS_TEST_PATH, "MIN_ASSERTIONS_BY_CLASS"),
)

#: **상한 라쳇 핀** — 값이 **올라가면** 보호가 줄어드는 상수. `현재 ≤ 이력 최솟값` 을 요구한다.
#:
#: ## [RATCHET_SCALAR_PINS] 와 방향이 반대다
#:
#: 저 표는 하한(`관측 ≥ 상수`)이라 **내리는** 편집이 보호를 줄인다. 여기 오는 것은
#: 예산·문턱처럼 `관측 < 상수` 로 쓰이는 값이고, **올리는** 편집이 보호를 줄인다. 두 방향을
#: 한 표에 섞으면 이력 대조가 절반을 반대로 판정하므로 표를 따로 둔다.
#:
#: 방향은 손으로 적지 않는다 — [_bound_direction] 이 **AST 로** 판정하고,
#: `test_이_파일의_수치_상수가_전부_분류돼_있다` 가 그 판정과 이 표를 대조한다.
RATCHET_CEILING_PINS: tuple[tuple[str, str], ...] = (
    (THIS_TEST_PATH, "SCANNER_TIME_BUDGET_SECONDS"),
)

#: **이 파일의 수치 상수 중 라쳇이 아닌 것** — 방향이 **없는** 것들이다.
#:
#: `TEST_CLASS_COUNT` 는 `len(TEST_CLASSES)` 와 **정확 일치**로 비교되므로 값만 내리면
#: 즉시 빨개진다(실측: 1 내렸을 때 `test_테스트_클래스_선언이_비어_있지_않다` 가 지목).
#: 그래서 이력 대조가 필요 없다 — **자기 권위가 아니라 목록이 권위**인 상수다.
#: Kotlin 쪽 `EXPECTED_SOURCE_DECLARATIONS` 도 같은 성질이다(실측: 1 내리면 RED).
#:
#: ## 사유 문장은 이제 **게이트가 아니다** (X5, 리더 판정 2026-08-21)
#:
#: 종전에는 이 표의 유일한 강제가 「사유가 비어 있지 않은가」였다. 그것은 **그 사유가 참인지
#: 재는 실행이 0** 이라는 뜻이고, 실측으로 방향 있는 새 하한 상수를 그럴듯한 사유와 함께
#: 여기 넣는 변이가 **두 줄에 197 passed** 였다(`c6-preconditions` §3.3).
#:
#: 그래서 판정을 **실행 성질**로 옮겼다 — [_bound_direction] 이 그 상수가 순서 비교(`< <= > >=`)에
#: 쓰이는지를 AST 로 보고, 쓰이면 여기 둘 수 없다. 사유 문장은 남기지만 그것이 통과의 근거는
#: 아니다. `ast` 프로토타입이 손으로 적은 분할을 **오차 없이 재현**한 것이 교체의 근거다.
NON_RATCHET_PINS: dict[str, str] = {
    "TEST_CLASS_COUNT": "len(TEST_CLASSES) 와 정확 일치 — 값만 내리면 즉시 빨개진다",
}

#: **게이트 스캐너의 실행 시간 예산(초).** 상한이므로 [RATCHET_CEILING_PINS] 가 지킨다.
#:
#: ## 왜 있는가 (2026-08-21 리더 판정 ⑤ — 차단 칸)
#:
#: `c6-preconditions` §2.8 이 실측한 사고: 이 파일의 정규식 하나에 파국적 백트래킹이 들어가
#: 게이트 전체가 **7.93s → 656.74s(83배)** 가 됐고, 한 `findall` 이 **205.9s** 였다. CI
#: `quality` 잡 예산이 **15분**이므로 그대로 두면 **이 검사 하나가 그 잡을 먹어 다른 가드를
#: 죽인다.** 악용 비용은 정규식 한 줄이고 그것을 잡는 자동 탐지는 **없었다** — 사람이 첫
#: 실행 304.86s 를 보고도 결함으로 읽지 못했고, 병렬 레인이 656.74s 를 재서야 드러났다.
#:
#: 즉 이것은 「가드가 못 잡는다」가 아니라 **「가드가 예산을 먹어 다른 가드를 죽인다」**이고,
#: 그래서 축을 세운다.
#:
#: ## 무엇을 재는가 — 그리고 **재지 않는 것**
#:
#: 이 파일의 **스캐너 원시 함수**(전수 훑기 다섯)만 잰다. 「pytest 파일 전체의 벽시계」를
#: 파일 자신이 재려 하면 실행 순서·병렬 실행·캐시 상태에 따라 값이 흔들리고, 흔들리는 축은
#: 곧 문턱을 올려 무력화된다. 스캐너를 직접 부르면 **결정적이고 원인이 지목된다.**
#:
#: **재지 않는 것**: git 이력 대조의 `subprocess` 왕복(`_blob_at` 등). 그쪽은 저장소 크기와
#: 디스크에 좌우되고, 오늘 실측이 1~2초라 예산을 정할 근거가 얇다. 그 자리는 잔여로 남긴다.
#:
#: ## 값의 근거와 거짓 양성 대가
#:
#: 2026-08-21 실측 합계 **1.261s**(개별: 0.073 / 0.058 / 0.628 / 0.350 / 0.153). 예산을 30초로
#: 두면 여유가 **약 24배**다. 부하가 큰 러너에서 전체가 5~10배 느려져도 예산 안이고, 위 사고의
#: 좌표(한 함수 205.9s)는 **즉시** 걸린다. 반대로 예산을 실측에 가깝게 조이면 러너 부하가
#: 곧 거짓 빨강이 되고, 그때 고치는 법은 예산을 올리는 것이라 축이 스스로 무력해진다 —
#: R-10 이 시간 축에서 겪은 그 문제다. 그래서 **느슨하게 두고 라쳇으로 올림을 막는다.**
SCANNER_TIME_BUDGET_SECONDS = 30

#: 실행 시간을 재는 스캐너 이름. **캐시를 비우고** 한 번씩 부른다.
#:
#: 목록이 아니라 **모듈 속성 이름**으로 두는 이유: 함수를 여기 직접 참조하면 선언 순서에
#: 묶이고, 이름으로 두면 그 함수가 사라졌을 때 `getattr` 이 끊어 **조용한 축소**가 안 된다.
TIMED_SCANNERS: tuple[str, ...] = (
    "_kotlin_test_sources",
    "_kotlin_main_sources",
    "_discovered_test_classes",
    "_kotlin_declared_names",
    "_named_enforcer_census",
)


def _kotlin_test_sources() -> list[Path]:
    """`backend-kotlin/**/src/test/**` 의 Kotlin 소스. 빌드 산출물은 뺀다."""
    return sorted(
        path
        for path in BACKEND_KOTLIN.rglob("*.kt")
        if "build" not in path.parts and "src" in path.parts and "test" in path.parts
    )


def _blank_comments_and_strings(text: str) -> str:
    """주석과 문자열 리터럴의 **내용을 공백으로** 지운다. 길이·줄 구조는 보존한다 (β-20).

    ## 왜 삭제가 아니라 공백 치환인가

    이 파일의 발견 파서는 **열 0 의 최상위 선언**을 경계로 쓴다. 지워서 길이를 바꾸면
    열 위치와 오프셋이 어긋나 그 경계가 조용히 틀어진다. 공백으로 덮으면
    `re.MULTILINE` 의 `^`, 열 0, 그리고 원문 오프셋이 전부 그대로 유효하므로
    `@DisplayName("…")` 의 값을 **원문에서** 같은 자리로 되읽을 수 있다.

    다루는 것: 줄 주석 `//`, 블록 주석 `/* */`(**중첩** — Kotlin 은 중첩을 허용한다),
    원시 문자열 `\"\"\"…\"\"\"`, 일반 문자열 `"…"`(백슬래시 이스케이프), 문자 `'…'`.

    **다루지 않는 것(정직하게)**: 문자열 템플릿 `${…}` 안의 코드. 템플릿 안에 테스트
    애너테이션을 쓰는 코드는 문법상 성립하지 않으므로(애너테이션은 식이 아니다) 이
    한계가 계수에 닿지 않는다.
    """
    out = list(text)
    length = len(text)
    index = 0
    depth = 0

    def blank(start: int, end: int) -> None:
        for cursor in range(start, end):
            if out[cursor] != "\n":
                out[cursor] = " "

    while index < length:
        if depth:
            if text.startswith("/*", index):
                depth += 1
                blank(index, index + 2)
                index += 2
                continue
            if text.startswith("*/", index):
                depth -= 1
                blank(index, index + 2)
                index += 2
                continue
            blank(index, index + 1)
            index += 1
            continue
        if text.startswith("/*", index):
            depth = 1
            blank(index, index + 2)
            index += 2
            continue
        if text.startswith("//", index):
            stop = text.find("\n", index)
            stop = length if stop < 0 else stop
            blank(index, stop)
            index = stop
            continue
        if text.startswith('"""', index):
            stop = text.find('"""', index + 3)
            stop = length if stop < 0 else stop + 3
            blank(index, stop)
            index = stop
            continue
        char = text[index]
        if char in {'"', "'"}:
            cursor = index + 1
            while cursor < length and text[cursor] != char:
                if text[cursor] == "\\":
                    cursor += 1
                cursor += 1
            stop = min(cursor + 1, length)
            blank(index, stop)
            index = stop
            continue
        index += 1
    return "".join(out)


@functools.cache
def _source_pair(path_key: str) -> tuple[str, str]:
    """(원문, 주석·문자열을 비운 텍스트). 오프셋이 서로 정확히 대응한다.

    한 실행 안에서 파일은 바뀌지 않으므로 캐시한다 — 파라미터화 케이스가 108회 이상
    같은 트리를 훑기 때문에 캐시가 없으면 어휘 분석 비용이 그만큼 곱해진다.
    """
    raw = Path(path_key).read_text(encoding="utf-8")
    return raw, _blank_comments_and_strings(raw)


def _blanked(path: Path) -> str:
    return _source_pair(str(path))[1]


def _raw(path: Path) -> str:
    return _source_pair(str(path))[0]


def _declared_package(path: Path) -> str | None:
    """`package` 선언. **주석 안의 `package` 줄은 세지 않는다** — 어휘 분석 뒤에 본다."""
    match = re.search(r"^package\s+([\w.]+)", _blanked(path), re.MULTILINE)
    return match.group(1) if match else None


@functools.cache
def _discovered_test_classes() -> dict[str, list[Path]]:
    """**종류로** 훑어 나온 테스트 클래스 → 그 이름을 선언한 파일들.

    선언 구간은 「이 최상위 선언부터 **다음 최상위 선언 직전까지**」다. 중괄호를 세지
    않는 이유는 문자열·주석 안의 괄호를 함께 세야 하고, 그 어휘 분석이 틀리면 구간이
    조용히 어긋나기 때문이다. 열 0 을 경계로 삼으면 그 위험이 없다 — 중첩 선언은
    들여쓰기되므로 바깥 구간에 남는다.

    FQCN 은 파일 안의 `package` 선언에서 만든다 — 경로에서 유추하면 경로와 패키지가
    어긋난 파일을 조용히 다른 이름으로 등록한다.

    **주석·문자열을 비운 텍스트를 본다** (β-20). 원문을 보면 KDoc 이 예시로 적은
    `@Test` 나 문자열 안의 선언 모양이 계수·발견에 섞인다.
    """
    found: dict[str, list[Path]] = {}
    for path in _kotlin_test_sources():
        text = _blanked(path)
        package = _declared_package(path)
        if package is None:
            continue
        marks = list(TOP_LEVEL_DECLARATION.finditer(text))
        for index, mark in enumerate(marks):
            end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
            if TEST_ANNOTATION.search(text[mark.start() : end]):
                found.setdefault(f"{package}.{mark.group(1)}", []).append(path)
    return found


def test_테스트_클래스_선언이_비어_있지_않다() -> None:
    """**빈 선언에서 통과하면 안 된다** (SKILL.md 규칙 4 ⑶).

    범위 선언형 장치의 최대 위험은 좁게 선언되는 것이 아니라 아무것도 선언되지 않은
    채 초록이 되는 것이다. 이 저장소는 그 형태를 두 번 실측했다(parity 게이트 · 표 판정기).
    """
    assert TEST_CLASSES, "테스트 클래스 선언이 비었다 — 아래 대조가 전부 0건 검사가 된다."
    assert len(set(TEST_CLASSES)) == len(TEST_CLASSES), (
        f"선언에 중복이 있다: {sorted({n for n in TEST_CLASSES if TEST_CLASSES.count(n) > 1})}"
    )
    assert len(TEST_CLASSES) == TEST_CLASS_COUNT, (
        f"선언 개수({len(TEST_CLASSES)})가 상수({TEST_CLASS_COUNT})와 다르다.\n"
        "  목록을 정당하게 고쳤다면 이 상수도 함께 고쳐라 — 두 자리에 나는 diff 가 "
        "'테스트 클래스를 하나 뺐다'는 신고이고, 그것이 이 상수의 값어치다."
    )


def test_선언_개수가_하한_아래로_내려가지_않는다() -> None:
    """**두 수기 선언의 자기 일치만으로는 부족하다** (게이트 27 codex C-5).

    위 대조는 `TEST_CLASSES` 와 `TEST_CLASS_COUNT` 가 **서로** 맞는지만 본다. 그래서 탐지기
    파일을 지우면서 둘을 **함께** 줄이면 전부 통과했다 — 85 라는 정확 일치는 독립 분모가
    아니라 같은 수기 선언의 자기 일치였다.

    하한은 그 축을 밖에서 되짚는다. 값을 낮추려면 **이 상수를 고치는 별도의 diff** 가 필요하고,
    그 diff 는 "검사 범위를 줄였다"는 신고다.

    **두 축은 중복이 아니다** (2026-08-21, 없앴다 되돌린 뒤 적는다). 하한은 「얼마 아래로는
    못 간다」를 보고 이 위의 정확 일치는 「한 개도 조용히 못 준다」를 본다. 하한만 남기면
    실측과 하한 사이의 간격만큼(당시 6 개) 조용한 삭제 창이 열리고, SKILL.md 규칙 8 의
    라쳇 상환은 그 간격을 Phase 안에서 **자라게** 한다.
    """
    assert len(TEST_CLASSES) >= MIN_TEST_CLASSES, (
        f"선언한 테스트 클래스가 {len(TEST_CLASSES)} 개다 — 하한 {MIN_TEST_CLASSES} 아래다.\n"
        "  탐지기와 선언을 함께 줄이는 편집은 위 대조를 통과하지만 여기서 걸린다.\n"
        "  줄인 것이 정당하다면 MIN_TEST_CLASSES 를 고치는 diff 와 그 사유를 함께 남겨라."
    )


def test_바닥_목록의_탐지기가_선언에_남아_있다() -> None:
    """**다른 판정이 근거로 인용하는 탐지기**는 조용히 사라질 수 없다.

    `SensitiveToStringReachTest` 가 `KNOWN_SENSITIVE_TYPES` 로 하는 것과 같은 규율이다.
    이 목록은 **바닥이지 천장이 아니다** — 새 테스트를 여기 적을 필요는 없고, 여기 있는 것이
    빠지는 것만 막는다.
    """
    missing = sorted(set(FLOOR_TEST_CLASSES) - set(TEST_CLASSES))
    assert not missing, (
        f"바닥 목록의 탐지기가 선언에서 빠졌다: {missing}\n"
        "  이 탐지기들은 개인정보 노출면·암호 불변식·계약 본문·범위 도달 판정의 근거로 "
        "인용된다 — 사라지면 그 판정들이 함께 무너진다.\n"
        "  정말 지웠거나 이름을 바꿨다면 FLOOR_TEST_CLASSES 도 함께 고쳐라."
    )


def test_선언한_테스트_클래스와_트리에서_발견한_것이_정확히_일치한다() -> None:
    """**정확 일치**다. 하한도 부분집합도 아니다.

    - 파일을 지우면 → 선언에만 남아 불일치.
    - 새 테스트를 넣고 선언을 빠뜨리면 → 발견에만 남아 불일치. 검사 대상이 **조용히
      늘지도 줄지도** 않게 하는 쪽이다.

    분모는 이름 families 가 아니라 **JUnit 애너테이션을 품은 최상위 선언**이다 —
    families 를 좁히면 declared 와 discovered 가 **함께** 조용해지던 자리(R-10)를
    분모에서 없앴다.
    """
    discovered = set(_discovered_test_classes())
    declared = set(TEST_CLASSES)

    missing = sorted(declared - discovered)
    extra = sorted(discovered - declared)
    assert not missing and not extra, (
        "선언한 테스트 클래스와 트리에서 발견한 것이 다르다.\n"
        f"  선언에만 있다(파일이 사라졌다): {missing or '없음'}\n"
        f"  트리에만 있다(선언이 빠졌다): {extra or '없음'}\n"
        "  **선언 쪽이 남았다면 먼저 '왜 그 파일이 사라졌는가'를 답하라.** 선언을 지우는 것은\n"
        "  이 대조를 통과시키는 방법이지 결함을 고치는 방법이 아니다 — 탐지기와 선언을 함께\n"
        "  줄이는 편집이 게이트 27 codex C-5 가 지목한 우회 경로이고, MIN_TEST_CLASSES 와\n"
        "  FLOOR_TEST_CLASSES 가 그 경로를 밖에서 되짚는다.\n"
        "  트리 쪽이 남았다면 새 테스트를 선언에 더하라 — 검사 범위가 조용히 늘지 않게 하는 쪽이다."
    )


#: Kotlin 문자열 이스케이프 → 실제 문자. `unicode_escape` 코덱을 쓰지 않는다 —
#: 그것은 한글을 latin-1 경유로 망친다(표시명이 전부 한국어인 저장소다).
_KOTLIN_ESCAPES = (
    ("\\\\", "\\"),
    ('\\"', '"'),
    ("\\n", "\n"),
    ("\\t", "\t"),
    ("\\r", "\r"),
    ("\\$", "$"),
)


def _unescape_kotlin_string(literal: str) -> str:
    text = literal
    for escaped, plain in _KOTLIN_ESCAPES:
        text = text.replace(escaped, plain)
    return text


def _annotated_functions(blanked: str) -> list[tuple[int, int, str]]:
    """애너테이션 블록이 붙은 함수 선언들. (블록 시작 오프셋, 블록 끝 오프셋, 함수 이름).

    ## 왜 정규식 한 줄이 아니라 줄 스캐너인가

    첫 판은 `(?:^[ \\t]*@[^\\n]*\\n)+` 로 애너테이션 줄이 이어진 블록을 잡았다. 그러면
    **인자가 여러 줄인 애너테이션**에서 블록이 끊긴다 — 실측: `CoreModuleBoundaryTest` 의
    `@ValueSource(strings = [ … ])` 가 20줄이라 파서가 그 클래스의 테스트를 **0개**로 셌고,
    `test_테스트_메서드_파서가_애너테이션_전수를_덮는다` 가 그것을 잡았다(어휘 계수 1 대 파서 0).

    그래서 줄을 훑으면서 **괄호 깊이**로 애너테이션 인자의 연속을 따라간다. 문자열과 주석은
    이미 [_blank_comments_and_strings] 가 비웠으므로 괄호 세기가 안전하다 — 문자열 안의
    괄호를 함께 세는 위험이 없다.
    """
    found: list[tuple[int, int, str]] = []
    offset = 0
    block_start: int | None = None
    depth = 0
    for line in blanked.splitlines(keepends=True):
        stripped = line.strip()
        if block_start is None:
            if stripped.startswith("@"):
                block_start = offset
                depth = line.count("(") - line.count(")")
        elif depth > 0 or stripped.startswith("@"):
            depth += line.count("(") - line.count(")")
        elif not stripped:
            pass  # 애너테이션과 `fun` 사이의 빈 줄은 블록을 끊지 않는다
        else:
            match = FUNCTION_DECLARATION.match(line)
            if match is not None:
                found.append(
                    (block_start, offset, match.group("quoted") or match.group("plain") or "")
                )
            block_start = None
            depth = 0
        offset += len(line)
    return found


def _declaration_region(fqcn: str) -> tuple[Path, int, int] | None:
    """[fqcn] 의 (파일, 선언 시작 오프셋, 끝 오프셋). 선언을 못 찾으면 `None`.

    구간 정의는 [_discovered_test_classes] 와 **같다**(열 0 의 최상위 선언 사이). 두 벌이
    되면 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다 — 그래서 계수 축과
    이름 축이 **이 함수 하나**를 공유한다.
    """
    paths = _discovered_test_classes().get(fqcn, [])
    if not paths:
        return None
    path = paths[0]
    text = _blanked(path)
    simple = fqcn.rsplit(".", 1)[1]
    marks = list(TOP_LEVEL_DECLARATION.finditer(text))
    for index, mark in enumerate(marks):
        if mark.group(1) != simple:
            continue
        end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
        return path, mark.start(), end
    return None


def _declared_test_count(fqcn: str) -> int | None:
    """[fqcn] 선언 구간 안의 JUnit 테스트 애너테이션 수. 선언을 못 찾으면 `None`.

    **주석·문자열을 비운 텍스트에서 센다** (β-20) — `// @Test` 한 줄이 계수를 유지시키던
    자리를 없앤다.
    """
    region = _declaration_region(fqcn)
    if region is None:
        return None
    path, start, end = region
    return len(TEST_ANNOTATION.findall(_blanked(path)[start:end]))


def _declared_test_methods(fqcn: str) -> tuple[tuple[str, ...], int] | None:
    """[fqcn] 의 (**평문 `@Test` 의 리포트 표시명**, invocation 생성 테스트 수).

    ## 왜 개수가 아니라 이름인가 (β-20)

    리포트 축은 종전에 **invocation 수**를 하한과 비교했다. 그런데 파라미터화 테스트
    하나가 여러 invocation 을 만들므로 그 여유분이 하한을 가린다 — 실측(2026-08-21,
    `ContractErrorBodyReachTest`): 평문 `@Test` 10 + `@ParameterizedTest` 1 = 소스 11,
    리포트 invocation **16**, 하한 11. 그래서 평문 케이스 하나가 실행에서 빠져도
    `16 - 1 = 15 ≥ 11` 로 초록이었다.

    이름으로 짚으면 그 여유분이 사라진다. 평문 `@Test` 는 리포트에 **정확히 하나의
    `testcase`** 로 나타나고 그 `name` 은 `@DisplayName` 값(없으면 함수 이름)이다.
    invocation 생성 애너테이션(`@ParameterizedTest` 등)은 표시명이 invocation 마다 달라
    이름으로 짚을 수 없으므로 **개수만** 세고 「메서드마다 최소 1 invocation」으로 본다.

    ## 파서가 놓치면 조용해지지 않는다

    이 파서는 「애너테이션 줄이 이어진 블록 + `fun` 선언」 모양을 본다. 그 모양을 벗어난
    선언(여러 줄로 쪼갠 애너테이션 인자 등)이 생기면 여기서 조용히 빠질 수 있다. 그래서
    `test_테스트_메서드_파서가_애너테이션_전수를_덮는다` 가 **파서가 찾은 수 == 어휘 계수**
    를 클래스마다 대조한다 — 파서가 놓치면 그 대조가 먼저 빨개진다.
    """
    region = _declaration_region(fqcn)
    if region is None:
        return None
    path, start, end = region
    blanked = _blanked(path)[start:end]
    raw = _raw(path)[start:end]

    plain: list[str] = []
    generated = 0
    for block_start, block_end, name in _annotated_functions(blanked):
        block = blanked[block_start:block_end]
        if not TEST_ANNOTATION.search(block):
            continue
        generated += len(GENERATED_TEST_ANNOTATION.findall(block))
        plain_count = len(PLAIN_TEST_ANNOTATION.findall(block))
        if plain_count == 0:
            continue
        display = DISPLAY_NAME_AT.search(raw[block_start:block_end])
        reported = _unescape_kotlin_string(display.group("name")) if display else name
        plain.extend([reported] * plain_count)
    return tuple(plain), generated


#: 참조 형태 — 백틱 인용 · `[대괄호]` KDoc 링크 · `@see`. Kotlin 쪽 축 A 와 **같은 형태**를
#: 본다(`NamedReferenceGuardTest` KDoc). 두 번째 판독기라는 사실은
#: `test_명명된_강제자_인구조사가_해소된다` 가 「해소되지 않는 이름은 빨강」으로 되짚는다.
NAMED_REFERENCE = re.compile(
    r"(?:`([A-Za-z_][\w.]*)`|\[([A-Za-z_][\w.]*)\]|@see\s+([A-Za-z_][\w.]*))"
)

#: 테스트·프로브 이름의 접미. Kotlin 축 A 의 `TEST_SUFFIXES` 와 같은 값이다.
NAMED_ENFORCER_SUFFIXES = ("Test", "Probe")

#: Kotlin 선언 머리. 인구조사에서 **제품 타입**(테스트가 아닌 것)을 가려내는 데 쓴다.
#:
#: ## 수식어를 매칭하지 않는다 — **파국적 백트래킹**을 실측으로 밟았다
#:
#: 첫 판은 선언 앞의 수식어를 `^\s*(?:[\w@\[\]().,\s]*?\b)?…` 로 받았다. 게으른 문자
#: 클래스가 줄바꿈을 포함한 `\s` 를 담은 채 `re.MULTILINE` 로 걸려, **모든 줄머리에서 파일
#: 끝까지 되짚었다.** 실측(2026-08-21): 이 파일의 게이트 전체가 **7.93s → 656.74s**(83배),
#: `DocumentBodyLogLeakReachTest.kt`(41,699바이트) 한 파일의 `findall` 하나가 **205.9s**.
#: CI `quality` 잡의 예산은 15분이므로 그대로 두면 그 잡을 이 검사 하나가 먹는다.
#:
#: 그래서 **키워드와 이름만** 본다. 수식어를 읽을 필요가 없다 — 필요한 것은 「이 이름이
#: 저장소에 선언돼 있는가」뿐이고, `enum class`·`annotation class`·`data class` 도 그 안의
#: `class <이름>` 으로 잡힌다. `::class.java` 는 `class` 뒤가 `.` 이라 걸리지 않고, 주석과
#: 문자열은 [_blanked] 가 이미 비웠다.
KOTLIN_DECLARATION = re.compile(r"\b(?:class|interface|object)\s+(\w+)")

#: 단언 토큰의 **모양** — `assert` 로 시작하는 식별자 + 여는 괄호. 목록이 아니라 모양이다.
ASSERTION_TOKEN = re.compile(r"\bassert[A-Za-z]*\s*\(")


@functools.cache
def _kotlin_main_sources() -> list[Path]:
    """`backend-kotlin/**/src/main/**` 의 Kotlin 소스. 빌드 산출물은 뺀다."""
    return sorted(
        path
        for path in BACKEND_KOTLIN.rglob("*.kt")
        if "build" not in path.parts and "src" in path.parts and "main" in path.parts
    )


def _comment_text(text: str) -> str:
    """주석·KDoc **내용만** 남긴다. 코드 본문과 문자열 리터럴은 버린다.

    분모를 주석으로 좁히는 이유는 Kotlin 축 A 와 같다 — 코드에서 이름이 틀리면 컴파일러가
    먼저 잡고, 이 결함은 **주석에서만** 살아남는다.
    """
    out: list[str] = []
    index = 0
    length = len(text)
    depth = 0
    start = 0
    while index < length:
        if depth:
            if text.startswith("/*", index):
                depth += 1
                index += 2
                continue
            if text.startswith("*/", index):
                depth -= 1
                if depth == 0:
                    out.append(text[start:index])
                index += 2
                continue
            index += 1
            continue
        if text.startswith("/*", index):
            depth = 1
            start = index + 2
            index += 2
            continue
        if text.startswith("//", index):
            stop = text.find("\n", index)
            stop = length if stop < 0 else stop
            out.append(text[index + 2 : stop])
            index = stop
            continue
        if text[index] == '"':
            index += 1
            while index < length and text[index] != '"':
                index += 2 if text[index] == "\\" else 1
            index += 1
            continue
        index += 1
    return "\n".join(out)


@functools.cache
def _kotlin_declared_names() -> set[str]:
    """저장소 Kotlin 소스(main·test)가 선언한 타입 이름 전부 — 단순 이름."""
    names: set[str] = set()
    for path in _kotlin_main_sources() + _kotlin_test_sources():
        names.update(KOTLIN_DECLARATION.findall(_blanked(path)))
    return names


@functools.cache
def _named_enforcer_census() -> tuple[dict[str, list[str]], list[str]]:
    """제품 주석이 이름으로 지목한 **테스트 클래스** 인구조사.

    **결과를 캐시한다** — 두 케이스가 각각 부르므로 캐시가 없으면 전수 스캔이 두 번 돈다
    (형제 `_discovered_test_classes` 와 같은 규율). 소비자는 읽기만 한다.

    돌려주는 것은 `(fqcn → 지목한 파일들, 해소되지 않은 이름들)` 이다. 두 번째가 비어 있지
    않으면 그 이름은 테스트 클래스도 제품 선언도 아니다 — 죽은 포인터이므로 **빨강**이다
    (Kotlin 축 A 도 같은 자리를 짚는다. 두 판독기가 같은 결론을 내야 한다).
    """
    discovered = _discovered_test_classes()
    by_simple: dict[str, list[str]] = {}
    for fqcn in discovered:
        by_simple.setdefault(fqcn.rsplit(".", 1)[1], []).append(fqcn)
    declared = _kotlin_declared_names()

    census: dict[str, list[str]] = {}
    unresolved: list[str] = []
    for path in _kotlin_main_sources():
        for match in NAMED_REFERENCE.finditer(_comment_text(path.read_text(encoding="utf-8"))):
            raw = match.group(1) or match.group(2) or match.group(3)
            name = raw.split(".")[-1]
            if not name[:1].isupper() or not name.endswith(NAMED_ENFORCER_SUFFIXES):
                continue
            candidates = by_simple.get(name, [])
            if len(candidates) == 1:
                census.setdefault(candidates[0], []).append(str(path.relative_to(REPO_ROOT)))
            elif candidates:
                unresolved.append(
                    f"{name} — 같은 단순 이름의 테스트 클래스가 "
                    f"{len(candidates)} 개다: {candidates}"
                )
            elif name not in declared:
                unresolved.append(
                    f"{name} — 저장소에 그 이름의 선언이 없다 ({path.relative_to(REPO_ROOT)})"
                )
            # 제품 타입으로 해소되는 이름(`DependencyProbe` 등)은 테스트가 아니므로 분모 밖이다.
    return census, unresolved


def _assertion_tokens(fqcn: str) -> int | None:
    """[fqcn] 선언 구간 안의 단언 토큰 수. 선언을 못 찾으면 `None`.

    구간은 [_declaration_region] 이 정한다 — 계수 축·이름 축과 **같은 파서**다.
    """
    region = _declaration_region(fqcn)
    if region is None:
        return None
    path, start, end = region
    return len(ASSERTION_TOKEN.findall(_blanked(path)[start:end]))


def test_명명된_강제자_인구조사가_해소된다() -> None:
    """제품 주석이 지목한 `…Test`/`…Probe` 이름이 **전부 해소된다** (β-03 · β-24).

    해소되지 않는 이름은 죽은 포인터다. Kotlin 축 A(`NamedReferenceGuardTest`)도 같은 자리를
    짚으므로 **두 판독기가 같은 결론**을 내야 하고, 갈리면 한쪽 파서가 틀린 것이다.

    분모 0 은 통과가 아니다 — 인구조사가 비면 아래 두 케이스가 아무것도 재지 않는다.
    """
    census, unresolved = _named_enforcer_census()

    assert not unresolved, (
        "제품 주석이 지목한 테스트·프로브 이름이 해소되지 않았다:\n"
        + "\n".join(f"  - {x}" for x in unresolved)
        + "\n  주석이 없는 것을 근거로 들면 읽는 사람은 그 자리가 지켜진다고 믿고 넘어간다."
    )
    assert census, (
        "제품 소스의 주석에서 테스트·프로브 지목을 하나도 찾지 못했다 — 인구조사가 비었으므로\n"
        "  아래 두 케이스의 분모가 0 이다. 참조 형태 정규식이나 분모 경로가 틀렸다."
    )


def test_명명된_강제자가_전부_개수_핀을_갖는다() -> None:
    """**정확 분할** — 지목된 클래스는 바닥 표나 명명 표 중 정확히 하나에 있다 (β-03 · β-24).

    한쪽에만 있어야 하고 어느 쪽에도 없으면 빨강이다. 실측(2026-08-21, 고치기 전):
    `DocumentListContractTest`·`DocumentContractNodeTest`·`HealthContractTest` 가 두 표 밖이라
    그 클래스에서 메서드를 지워도 **자동 신호가 0** 이었다.
    """
    census, _ = _named_enforcer_census()
    floor = set(MIN_TESTS_IN_FLOOR_CLASS)
    named = set(MIN_TESTS_BY_NAMED_ENFORCER)

    overlap = sorted(floor & named)
    unpinned = sorted(set(census) - floor - named)
    stale = sorted(named - set(census))

    assert not overlap, (
        f"두 개수 표에 같은 클래스가 있다: {overlap}\n"
        "  분할이 겹치면 어느 표가 그 클래스를 지키는지가 흐려진다 — 바닥 표 쪽만 남겨라."
    )
    assert not unpinned, (
        "제품 주석이 이름으로 지목했는데 개수 핀이 없는 클래스가 있다:\n"
        + "\n".join(f"  - {x} — 지목한 파일: {census[x]}" for x in unpinned)
        + "\n  그 주석은 「이것이 그 성질을 잰다」는 주장이다.\n"
        "  메서드만 지우면 그 주장이 거짓이 되는데\n"
        "  아무도 알아채지 못한다. MIN_TESTS_BY_NAMED_ENFORCER 에 현재 선언 수를 적어라\n"
        "  (판정 장치에 물어서 — `grep` 이 아니다)."
    )
    assert not stale, (
        f"명명 표에 있는데 제품 주석이 더는 지목하지 않는 클래스가 있다: {stale}\n"
        "  주석이 사라졌거나 이름이 바뀌었다. 핀도 함께 정리해야 이 표가 실제 분모를 말한다."
    )


@pytest.mark.parametrize("fqcn", sorted(MIN_TESTS_BY_NAMED_ENFORCER))
def test_명명된_강제자의_테스트_개수가_하한_아래로_내려가지_않는다(fqcn: str) -> None:
    """지목된 클래스에서도 **메서드만 지우는 편집**을 잡는다 (β-03 · β-24)."""
    expected = MIN_TESTS_BY_NAMED_ENFORCER[fqcn]
    actual = _declared_test_count(fqcn)

    assert actual is not None, (
        f"{fqcn} 의 선언을 찾지 못해 테스트 개수를 세지 못했다. **판정 불가는 통과가 아니다.**"
    )
    assert actual >= expected, (
        f"{fqcn} 의 테스트 개수가 {actual} 개다 — 하한 {expected} 아래다.\n"
        "  제품 주석이 이 클래스를 이름으로 지목해 「그 성질을 잰다」고 적었다.\n"
        "  줄인 것이 정당하다면 이 숫자를 고치는 diff 와 사유를 함께 남겨라."
    )


def test_단언_개수_표가_두_개수_표의_합집합과_같다() -> None:
    """키 집합을 **유도**한다 — 새 클래스가 개수 표에 들면 단언 표에도 들어야 한다 (β-04)."""
    expected = set(MIN_TESTS_IN_FLOOR_CLASS) | set(MIN_TESTS_BY_NAMED_ENFORCER)
    actual = set(MIN_ASSERTIONS_BY_CLASS)
    assert expected, "두 개수 표가 모두 비었다 — 이 대조는 아무것도 재지 않는다."

    missing = sorted(expected - actual)
    extra = sorted(actual - expected)
    assert not missing and not extra, (
        "단언 개수 표의 키 집합이 두 개수 표의 합집합과 다르다.\n"
        f"  단언 하한이 빠졌다(단언 비우기가 잡히지 않는다): {missing or '없음'}\n"
        f"  개수 표에서 빠진 항목의 단언 하한이 남았다: {extra or '없음'}\n"
        "  개수 표에 클래스를 더하면 그 클래스의 현재 단언 토큰 수도 함께 적어라."
    )


@pytest.mark.parametrize("fqcn", sorted(MIN_ASSERTIONS_BY_CLASS))
def test_단언_토큰_수가_하한_아래로_내려가지_않는다(fqcn: str) -> None:
    """**껍데기를 남기고 단언만 비우는 편집**을 잡는다 (β-04).

    잡지 못하는 것은 [MIN_ASSERTIONS_BY_CLASS] KDoc 에 적었다 — 무해한 단언으로 **교체**하는
    편집은 개수가 그대로다(백로그 B-19, 변이 테스트).
    """
    expected = MIN_ASSERTIONS_BY_CLASS[fqcn]
    actual = _assertion_tokens(fqcn)

    assert actual is not None, (
        f"{fqcn} 의 선언을 찾지 못해 단언을 세지 못했다. **판정 불가는 통과가 아니다.**"
    )
    assert actual >= expected, (
        f"{fqcn} 의 단언 토큰이 {actual} 개다 — 하한 {expected} 아래다.\n"
        "  `@Test` 개수가 그대로여도 단언이 사라지면 그 클래스는 껍데기다.\n"
        "  단언을 다른 클래스·보조로 옮겼다면 옮긴 쪽 값을 올리고 이 값을 함께 고쳐라 —\n"
        "  두 값을 같은 diff 에 두면 총합이 줄었는지 보인다."
    )


def test_바닥_목록이_비지_않는다() -> None:
    """바닥 목록의 크기가 하한 아래로 내려가지 않는다 — 규칙 4 ⑶.

    이 케이스가 없으면 `FLOOR_TEST_CLASSES` 와 `MIN_TESTS_IN_FLOOR_CLASS` 를 **함께 비우는**
    편집이 모든 대조를 통과한다(실측). 범위 선언형이 빈 선언에서 초록이 되는 것을 막는
    자리이고, 근거는 `MIN_FLOOR_CLASSES` 의 주석에 있다.
    """
    assert len(FLOOR_TEST_CLASSES) >= MIN_FLOOR_CLASSES, (
        f"바닥 목록이 {len(FLOOR_TEST_CLASSES)} 개다 — 하한 {MIN_FLOOR_CLASSES} 아래다.\n"
        "  바닥에서 항목을 빼는 것은 「정리」가 아니라\n"
        "  **다른 판정의 근거를 무보호로 두는 일**이다.\n"
        "  줄여야 한다면 MIN_FLOOR_CLASSES 를 고치는 별도의 diff 와 사유가 필요하다."
    )
    assert len(set(FLOOR_TEST_CLASSES)) == len(FLOOR_TEST_CLASSES), "바닥 목록에 중복이 있다"


def test_바닥_개수_하한이_바닥_목록과_같은_집합을_덮는다() -> None:
    """**키 집합 정확 일치.** 바닥에 클래스를 더하면서 개수를 빠뜨리면 그 항목은 다시
    「클래스만 지켜지는」 상태가 되고, 그것이 이 장치가 겨눈 결함이다(C4 R-7).
    """
    floor = set(FLOOR_TEST_CLASSES)
    counted = set(MIN_TESTS_IN_FLOOR_CLASS)

    missing = sorted(floor - counted)
    extra = sorted(counted - floor)
    assert not missing and not extra, (
        "바닥 목록과 개수 하한의 키 집합이 다르다.\n"
        f"  개수 하한이 빠졌다(메서드 삭제가 잡히지 않는다): {missing or '없음'}\n"
        f"  바닥에서 빠진 항목의 개수가 남았다: {extra or '없음'}\n"
        "  바닥에 클래스를 더하면 그 클래스의 현재 `@Test` 수를 함께 적어라."
    )


@pytest.mark.parametrize("fqcn", sorted(MIN_TESTS_IN_FLOOR_CLASS))
def test_바닥_클래스의_테스트_개수가_하한_아래로_내려가지_않는다(fqcn: str) -> None:
    """**메서드만 지우는 편집**을 잡는다 — 클래스는 남고 단언이 사라지는 자리다.

    잡지 못하는 것은 `MIN_TESTS_IN_FLOOR_CLASS` KDoc 에 적었다(껍데기를 남기고 단언만
    비우는 편집 → pitest, 백로그 B-19).
    """
    expected = MIN_TESTS_IN_FLOOR_CLASS[fqcn]
    actual = _declared_test_count(fqcn)

    assert actual is not None, (
        f"{fqcn} 의 선언을 찾지 못해 테스트 개수를 세지 못했다.\n"
        "  파일이 사라졌거나 패키지가 바뀌었다. **판정 불가는 통과가 아니다.**"
    )
    assert actual >= expected, (
        f"{fqcn} 의 테스트 개수가 {actual} 개다 — 하한 {expected} 아래다.\n"
        "  **클래스가 남아 있어도 그 안의 단언이 사라지면**\n"
        "  그 클래스가 지키기로 선언한 것이 사라진다.\n"
        "  줄인 것이 정당하다면(예: 다른 클래스로 뽑아냈다) 이 숫자를 고치는\n"
        "  diff 와 사유를 함께 남겨라 — 뽑아낸 쪽 클래스를 바닥과 이 표에\n"
        "  함께 등재했는지도 확인하라."
    )


@pytest.mark.parametrize("fqcn", sorted(MIN_TESTS_IN_FLOOR_CLASS))
def test_테스트_메서드_파서가_애너테이션_전수를_덮는다(fqcn: str) -> None:
    """이름 축의 파서가 **하나도 놓치지 않았음**을 계수 축으로 되짚는다 (β-20).

    이름 축([_declared_test_methods])은 「애너테이션 줄 블록 + `fun` 선언」 모양을 본다.
    그 모양을 벗어난 선언이 생기면 이름 축의 분모가 **조용히 줄어든다** — 그러면
    「이름이 리포트에 있다」가 참인 채로 실제 메서드는 빠져 있을 수 있다.

    그래서 파서가 찾은 테스트 수(평문 + invocation 생성)가 어휘 계수와 **정확히 같아야**
    한다. 두 계산이 같은 [_declaration_region] 을 쓰지만 세는 방식이 다르므로, 파서가
    놓치면 여기가 먼저 빨개진다. **판정 불가는 통과가 아니다.**
    """
    methods = _declared_test_methods(fqcn)
    assert methods is not None, f"{fqcn} 의 선언을 찾지 못했다 — 이름 축이 아무것도 재지 못한다."
    plain, generated = methods
    counted = _declared_test_count(fqcn)
    assert counted is not None, f"{fqcn} 의 애너테이션을 세지 못했다."
    assert len(plain) + generated == counted, (
        f"{fqcn}: 이름 축 파서가 {len(plain) + generated}개를 찾았는데 어휘 계수는 {counted}개다.\n"
        f"  평문 `@Test` {len(plain)}개 · invocation 생성 {generated}개.\n"
        "  파서가 어떤 선언 모양을 놓쳤다는 뜻이다 — 이름 축의 분모가 조용히 줄었으므로\n"
        "  ANNOTATED_FUNCTION 정규식을 그 모양까지 덮게 고쳐라. 이름 축을 좁히는 것은 "
        "이 대조를 통과시키는 방법이지 결함을 고치는 방법이 아니다."
    )
    assert len(set(plain)) == len(plain), (
        f"{fqcn}: 평문 `@Test` 의 리포트 표시명에 중복이 있다: "
        f"{sorted({n for n in plain if plain.count(n) > 1})}\n"
        "  같은 표시명이 둘이면 하나가 사라져도 이름 축이 알아채지 못한다 — "
        "`@DisplayName` 을 갈라 적어라."
    )


@pytest.mark.parametrize("fqcn", TEST_CLASSES)
def test_클래스마다_그_이름을_선언한_파일이_하나다(fqcn: str) -> None:
    """**내용 결속** — 파일이 있다는 것만으로는 부족하다.

    이름만 바꿔치기(다른 클래스를 같은 파일명으로) · 사본 늘리기(같은 FQCN 을 두 곳에)를
    가른다. 파일 안의 `package` 선언과 타입 선언이 FQCN 과 **글자 그대로** 같아야 한다.
    """
    package, simple = fqcn.rsplit(".", 1)
    candidates = _discovered_test_classes().get(fqcn, [])

    assert candidates, f"{fqcn} 을 선언한 파일이 없다 — 테스트가 사라졌거나 패키지가 바뀌었다."
    assert len(candidates) == 1, (
        f"{fqcn} 을 선언한 파일이 {len(candidates)}개다: {[str(p) for p in candidates]}\n"
        "  사본이 생기면 하나를 지워도 이 대조가 통과한다."
    )

    path = candidates[0]
    source = path.read_text(encoding="utf-8")
    assert re.search(
        rf"^\s*(?:internal\s+|private\s+|open\s+|abstract\s+)*(?:class|object)\s+{re.escape(simple)}\b",
        source,
        re.MULTILINE,
    ), f"{path} 안에 `{simple}` 타입 선언이 없다 — 파일만 남고 내용이 갈렸다."
    assert _declared_package(path) == package, f"{path} 의 package 선언이 {fqcn} 과 다르다."


def _test_report_dirs() -> list[Path]:
    return sorted(BACKEND_KOTLIN.glob("*/build/test-results/test"))


def _report_execution() -> tuple[dict[str, int], set[str]]:
    """Gradle 리포트에서 (FQCN → **실행된** 케이스 수, 리포트에 나온 FQCN 전부) 를 낸다.

    ## 두 결함을 여기서 닫는다

    - **`testcase@classname` 으로 집계한다**(codex B-1). `testsuite@name` 은
      `@DisplayName` 이 들어가는 자리라 FQCN 이 아니다 — 이 저장소는 한국어 표시명을
      쓰므로 중첩 클래스가 통째로 미실행으로 오판됐다.
    - **`<skipped>` 는 실행으로 세지 않는다**(Claude T-1). JUnit XML 의 `tests` 속성은
      skipped 를 포함하므로, `@Disabled` 한 줄로 끈 클래스가 `tests > 0` 으로 통과했다.

    중첩 클래스(`@Nested`)의 `classname` 은 `FQCN$Inner` 이므로 `$` 앞으로 접는다.
    """
    executed, _, seen = _report_counts()
    return executed, seen


def _report_counts() -> tuple[dict[str, int], dict[str, int], set[str]]:
    """(FQCN → **실행된** 케이스 수, FQCN → **건너뛴** 케이스 수, 리포트에 나온 FQCN 전부).

    **파서를 한 벌만 둔다.** 실행 수와 건너뜀 수를 각각 훑는 함수를 만들면 한쪽만 고쳐지는
    날 서로 다른 것을 세면서 둘 다 초록이 된다 — 이 저장소가 반복해 겪은 형태다.
    [_report_execution] 은 이 함수에 위임한다.
    """
    executed: dict[str, int] = {}
    skipped: dict[str, int] = {}
    seen: set[str] = set()
    for report_dir in _test_report_dirs():
        for report in report_dir.glob("TEST-*.xml"):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                outer = (case.get("classname") or "").split("$", 1)[0]
                if not outer:
                    continue
                seen.add(outer)
                if case.find("skipped") is None:
                    executed[outer] = executed.get(outer, 0) + 1
                else:
                    skipped[outer] = skipped.get(outer, 0) + 1
    return executed, skipped, seen


def _report_case_names() -> dict[str, set[str]]:
    """FQCN → 리포트에 **실행된** `testcase@name` 집합 (β-20 이름 축).

    중첩 클래스는 `$` 앞으로 접어 바깥 클래스에 합친다 — 계수 축과 같은 규약이다.
    """
    names: dict[str, set[str]] = {}
    for report_dir in _test_report_dirs():
        for report in report_dir.glob("TEST-*.xml"):
            for case in ElementTree.parse(report).getroot().iter("testcase"):
                outer = (case.get("classname") or "").split("$", 1)[0]
                if not outer or case.find("skipped") is not None:
                    continue
                names.setdefault(outer, set()).add(case.get("name") or "")
    return names


def _report_suite_timestamps() -> list[tuple[Path, str | None]]:
    """(리포트 파일, `testsuite@timestamp`). 신선도 판정의 유일한 근거다 (β-02).

    파일 mtime 을 쓰지 않는 이유는 실측이다 — `FROM-CACHE` 복원은 mtime 을 **복원 시각으로
    갱신**하면서 `timestamp` 속성은 원래 값을 그대로 되살린다. 그래서 mtime 은 항상 신선해
    보이고 `timestamp` 만 진실을 말한다.
    """
    stamps: list[tuple[Path, str | None]] = []
    for report_dir in _test_report_dirs():
        for report in sorted(report_dir.glob("TEST-*.xml")):
            stamps.append((report, ElementTree.parse(report).getroot().get("timestamp")))
    return stamps


def _parse_instant(value: str) -> datetime | None:
    """ISO-8601 순간. `Z` 접미를 받아들이고, 시간대가 없으면 UTC 로 읽는다."""
    try:
        parsed = datetime.fromisoformat(value.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return parsed if parsed.tzinfo else parsed.replace(tzinfo=UTC)


# ─────────────────────────────────────────────────────────────────────────────
# 리포트 축의 **상태 셋** — 「없어도 된다」와 「없으면 실패」를 갈라 선언한다 (β-23)
#
# 옛 판은 두 상태(요구 모드 on/off)였고, off 에서 **분모 0 을 `print` 하고 통과**했다.
# 그 `print` 는 통과한 테스트의 stdout 이라 pytest 가 삼킨다 — 실측(2026-08-21):
# 리포트 디렉터리를 통째로 옮기고 비요구 모드로 돌렸더니 바닥 28개 전건이 「판정하지 못함」
# 인데 **151 passed · exit 0**, 그리고 그 문장은 `-rP` 를 붙여야 보였다.
#
# 상태를 셋으로 가른다.
#
#   `required`  — 요구 모드. 리포트가 **있어야 하고** 분모가 0 이면 실패다.
#   `present`   — 리포트가 있다. 그러면 Gradle 이 돌았다는 뜻이므로 **분모 0 은 실패다**
#                 (이 축이 조용히 무효화된 상태이고, 그것이 이 항목이 겨눈 결함이다).
#   `absent`    — 리포트가 하나도 없다. `quality` 잡에는 Gradle 이 없어 **원리적으로**
#                 없는 자리이므로 여기만 「없어도 된다」다. 단 **빈 선언에서 통과하지
#                 않는다** — 그 허용의 근거는 「요구 모드로 이 축을 지는 다른 실행 경로가
#                 실재한다」 하나이고, [_require_reports_are_carried_elsewhere] 가 그
#                 실재를 같은 실행 안에서 되짚는다. 요구 모드 배선이 사라지면 이 상태는
#                 통과하지 못한다. 그리고 판정 불가는 `print` 가 아니라 **경고**로 낸다 —
#                 경고는 pytest 요약에 남는다.
# ─────────────────────────────────────────────────────────────────────────────

REPORT_REQUIRED = "required"
REPORT_PRESENT = "present"
REPORT_ABSENT = "absent"


def _report_state(seen: set[str]) -> str:
    if _require_mode():
        return REPORT_REQUIRED
    return REPORT_PRESENT if seen else REPORT_ABSENT


def _require_reports_are_carried_elsewhere(axis: str) -> None:
    """리포트 부재를 허용하는 **유일한 근거**를 같은 실행 안에서 확인한다 (β-23).

    「없어도 된다」가 빈 선언이 되지 않게 하는 자리다. 요구 모드 배선이 사라지면 이 축은
    어디서도 도달하지 않으므로, 그때는 부재를 허용하지 않고 **실패**한다.
    """
    jobs = _wired_require_mode_jobs()
    assert jobs, (
        f"{axis}: Gradle 리포트가 없어 판정할 수 없는데, 요구 모드로 이 축을 지는 CI 배선도 없다.\n"
        f"  `{REQUIRE_REPORT_ENV}` 를 env 로 켜고 {THIS_TEST_PATH} 를 **정확한 argv** 로 돌리는 "
        "스텝이 있어야만 리포트 부재가 허용된다 — 그 스텝이 없으면\n"
        "  이 축은 존재만 하고 도달이 0 이다."
    )
    warnings.warn(
        f"{axis}: Gradle 리포트가 없어 이번 실행에서는 판정하지 못했다 "
        f"(이 축은 요구 모드 잡 {jobs} 이 진다).",
        stacklevel=2,
    )


def test_리포트가_선언한_클래스를_실제로_실행했다() -> None:
    """파일이 있는 것과 **돌았다**는 것은 다르다.

    **skip 하지 않는다.** 요구 모드가 꺼져 있어도 리포트에 실재하는 클래스는 여기서
    잰다 — 부분 리포트에서도 거짓이 없고, 가장 값싼 공격(`@Disabled` 로 끄기)이
    로컬에서도 즉시 빨개진다. 요구 모드가 켜지면 대상이 **선언 전건**으로 넓어지고,
    리포트 부재 자체가 실패가 된다.
    """
    executed, seen = _report_execution()
    state = _report_state(seen)

    if state == REPORT_ABSENT:
        _require_reports_are_carried_elsewhere("실행 대조")
        return
    if state == REPORT_REQUIRED:
        assert seen, (
            f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 Gradle 테스트 리포트가 없다.\n"
            f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}\n"
            "  이 스텝은 Kotlin 테스트 뒤에 돌아야 한다 — 리포트가 없으면 실행 대조는 "
            "아무것도 재지 않는다. 조용히 초록이 되는 것이 이 종류의 빈자리다."
        )
        targets = list(TEST_CLASSES)
    else:
        # 로컬 리포트는 마지막에 돌린 것만 남는다(실측: `:core:test --tests X` 한 번이면
        # 그 모듈 리포트가 통째로 그 하나로 바뀐다). 그 상태를 "안 돌았다"로 읽으면 상시
        # 오경보가 되고, 상시 오경보는 결국 이 스텝을 꺼서 게이트를 잃게 한다.
        targets = [fqcn for fqcn in TEST_CLASSES if fqcn in seen]

    # **분모 0 은 실패다** (β-23). 리포트가 있다는 것은 Gradle 이 돌았다는 뜻이므로,
    # 그런데도 대상이 0 이면 이 축이 조용히 무효화된 것이다.
    assert targets, (
        f"실행 대조의 판정 대상이 0 이다 (상태: {state}).\n"
        f"  리포트에 나온 클래스 {len(seen)}개가 선언 {len(TEST_CLASSES)}개와\n"
        "  하나도 겹치지 않는다.\n"
        "  리포트가 다른 저장소·다른 패키지의 것이거나 선언이 통째로 갈렸다 — "
        "**분모 0 은 통과가 아니다.**"
    )

    silent = sorted(fqcn for fqcn in targets if executed.get(fqcn, 0) <= 0)
    assert not silent, (
        f"선언한 테스트 클래스가 리포트에 **실행된** 기록이 없다: {silent}\n"
        f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}\n"
        "  파일은 있는데 아무도 돌리지 않았거나(`--tests` 필터·태그), 돌긴 했는데 전건이 "
        "skipped 다(`@Disabled`·`assumeTrue(false)`). 둘 다 게이트가 존재만 하고 도달이 0 이다."
    )


def test_바닥_클래스가_리포트에서_하한만큼_실제로_돌았다() -> None:
    """**개수를 「선언된 것」이 아니라 「실제로 돈 것」으로 센다** (C4 R-8).

    소스 축(`test_바닥_클래스의_테스트_개수가...`)은 애너테이션을 세므로 **`@Disabled` 한 줄로
    끈 메서드를 그대로 센다.** 실측(2026-08-21, 고치기 전): 바닥 클래스의 케이스 하나에
    `@Disabled` 를 달거나 `assumeTrue(false)` 를 넣어도 `build` **exit 0** · 이 게이트
    **141 passed** 였다. 개수도 클래스도 그대로이므로 어느 축도 울리지 않는다.

    **비활성화 기제를 열거하지 않는다.** `@Disabled`·`@DisabledIf*`·`@EnabledOnOs`·
    `assumeTrue`/`assumeFalse`·태그 제외·`--tests` 필터·JVM 인자가 전부 같은 일을 하는데,
    그 목록은 닫히지 않는다(규칙 4 ⑵ — 은폐형은 넓히지 말고 탐지형으로 갈아탄다).
    결과는 **「그 테스트가 안 돌았다」 하나로 수렴**하므로 리포트에서 그것만 본다.

    요구 모드가 켜지면 대상이 **바닥 전건**으로 넓어진다. 꺼져 있으면 리포트에 실재하는
    것만 보되, **분모가 0 이면 실패한다** — 옛 판은 그 자리를 `print` 로 넘겼고 그 문장은
    통과한 테스트의 stdout 이라 아무도 보지 못했다(β-23).

    ## 개수만 보지 않는다 — **이름으로 짚는다** (β-20)

    `actual >= 하한` 은 파라미터화 여유분이 하한을 가린다. 실측:
    `ContractErrorBodyReachTest` 는 소스 11(평문 10 + 파라미터화 1) · 리포트 invocation 16 ·
    하한 11 이라, 평문 케이스 하나가 실행에서 빠져도 15 ≥ 11 로 초록이었다. 그래서 이 축은
    **평문 `@Test` 의 표시명이 리포트에 실재하는가**를 함께 본다.
    """
    executed, _, seen = _report_counts()
    case_names = _report_case_names()
    state = _report_state(seen)
    floor = sorted(MIN_TESTS_IN_FLOOR_CLASS)

    if state == REPORT_ABSENT:
        _require_reports_are_carried_elsewhere("바닥 실행 대조")
        return
    if state == REPORT_REQUIRED:
        assert seen, (
            f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 Gradle 테스트 리포트가 없다.\n"
            f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}\n"
            "  이 스텝은 Kotlin 테스트 뒤에 돌아야 한다."
        )
        targets = floor
    else:
        targets = [fqcn for fqcn in floor if fqcn in seen]

    # **분모 0 은 실패다** (β-23). 요구 모드에서는 바닥 개수표가 빈 경우이고, 리포트가 있는
    # 비요구 모드에서는 「Gradle 이 돌았는데 바닥 클래스가 하나도 안 돌았다」는 뜻이다.
    assert targets, (
        f"바닥 실행 대조의 판정 대상이 0 이다 (상태: {state}).\n"
        f"  바닥 개수표 {len(MIN_TESTS_IN_FLOOR_CLASS)}개 · 리포트에 나온 클래스 {len(seen)}개.\n"
        "  리포트가 있는데 바닥이 하나도 겹치지 않으면 이 축은 아무것도 재지 않은 것이다 — "
        "**분모 0 은 통과가 아니다.** 바닥 클래스를 포함하는 실행으로 다시 돌려라."
    )

    short = {fqcn: (executed.get(fqcn, 0), MIN_TESTS_IN_FLOOR_CLASS[fqcn]) for fqcn in targets}
    short = {k: v for k, v in short.items() if v[0] < v[1]}
    assert not short, (
        "바닥 클래스가 리포트에서 하한만큼 **돌지 않았다**:\n"
        + "\n".join(f"  - {k}: 실행 {v[0]} / 하한 {v[1]}" for k, v in sorted(short.items()))
        + "\n  메서드가 사라졌거나(소스 축도 함께 빨개진다),\n"
        "  남아 있는데 **안 돌았다**\n"
        "  (`@Disabled`·`assumeTrue`·태그 제외·`--tests` 필터). 뒤쪽이 이 축의 몫이다.\n"
        "  줄인 것이 정당하다면 MIN_TESTS_IN_FLOOR_CLASS 를 고치는 diff 와 사유를 함께 남겨라."
    )

    missing: dict[str, list[str]] = {}
    thin: dict[str, tuple[int, int]] = {}
    for fqcn in targets:
        methods = _declared_test_methods(fqcn)
        if methods is None:
            continue
        plain, generated = methods
        reported = case_names.get(fqcn, set())
        absent = sorted({name for name in plain if name not in reported})
        if absent:
            missing[fqcn] = absent
        # invocation 생성 테스트는 이름으로 짚을 수 없으므로 「메서드마다 최소 1건」으로 본다.
        leftover = len(reported - set(plain))
        if leftover < generated:
            thin[fqcn] = (leftover, generated)

    assert not missing, (
        "소스가 선언한 평문 `@Test` 가 리포트에 **실행 기록으로 없다** (이름 축, β-20):\n"
        + "\n".join(
            f"  - {k}:\n" + "\n".join(f"      · {n}" for n in v) for k, v in sorted(missing.items())
        )
        + "\n  개수 축은 파라미터화 여유분에 가려 초록일 수 있다 —\n"
        "  이 축은 이름을 짚으므로 가려지지 않는다.\n"
        "  원인 후보: 그 메서드가 안 돌았다(`@Disabled`·태그 제외·`--tests` 필터) / 리포트가 "
        "이번 소스보다 낡았다(β-02 신선도 축을 함께 보라) / `@DisplayName` 을 고치고 테스트를 "
        "다시 돌리지 않았다."
    )
    assert not thin, (
        "invocation 생성 테스트(`@ParameterizedTest` 등)가 리포트에 하나도 나타나지 않았다:\n"
        + "\n".join(
            f"  - {k}: 평문 밖 invocation {v[0]}건 / 생성 테스트 {v[1]}개"
            for k, v in sorted(thin.items())
        )
        + "\n  메서드마다 최소 1 invocation 을 요구한다 — 인자 공급원이 빈 집합이면 그 테스트는 "
        "선언만 있고 아무것도 재지 않는다."
    )


def test_요구모드_리포트가_이번_실행에서_만들어졌다() -> None:
    """**「돌았다」와 「이번에 돌았다」는 다르다** (β-02).

    ## 고치기 전 실측 (2026-08-21)

    ⑴ 이 세션은 Gradle 을 한 번도 돌리지 않은 채 요구 모드 대조를 **151 passed** 로 통과했다 —
      읽은 리포트는 `timestamp="…T06:36:08Z"`, 즉 앞선 실행의 산출물이다.
    ⑵ `./gradlew :core:test --rerun-tasks` (XML `timestamp=…T08:06:19.523Z`) → 리포트 디렉터리
      삭제 → `./gradlew :core:test` 는 `> Task :core:test FROM-CACHE` 로 끝나고 복원된 XML 의
      `timestamp` 가 **08:06:19.523Z 그대로**였다. 파일 mtime 만 복원 시각으로 갱신됐다.
    ⑶ 그리고 선언 입력이 비대칭이었다 — 테스트가 실행 시점에 읽는
      `backend-kotlin/parity-domains.txt` 에서 도메인 한 줄을 지웠는데 `./gradlew :core:test` 가
      **UP-TO-DATE** 로 끝났다(그 상태에서 `--rerun-tasks` 를 주면 `ParityDeclarationSyncTest`
      가 `["export"]` 를 지목하며 **실패**한다 — 양성 대조).

    즉 「게이트가 돌았다」가 **캐시 복원과 구별되지 않았다.**

    ## 무엇으로 판정하는가

    `testsuite@timestamp` 는 **내용**이라 캐시를 그대로 따라오고, mtime 은 복원이 갱신하므로
    쓸 수 없다(⑵ 실측). 그래서 CI 가 전체 빌드 **앞에서** 박은 표식([RUN_MARKER_ENV])보다
    모든 리포트의 `timestamp` 가 **같거나 나중**이어야 한다.

    요구 모드에서 표식이 없거나 파싱되지 않으면 **실패**다 — 표식이 조용히 사라지면 이 축이
    존재만 하고 도달이 0 이 되고, 그것이 이 저장소가 반복해 겪은 형태다.

    요구 모드가 아니면 표식이 없는 것이 정상이므로(로컬 실행) 그 상태는 판정하지 않고
    경고만 남긴다. 로컬에서 이 축을 재려면 표식을 손으로 주면 된다:
    `KOTLIN_GATE_REACH_RUN_STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)`.
    """
    stamps = _report_suite_timestamps()
    marker_raw = os.environ.get(RUN_MARKER_ENV, "").strip()

    if not _require_mode() and not marker_raw:
        warnings.warn(
            f"신선도 축 — {RUN_MARKER_ENV} 가 없어 판정하지 못했다 "
            "(요구 모드 밖에서는 정상이다. 재려면 그 변수를 UTC ISO-8601 로 주어라).",
            stacklevel=1,
        )
        return

    assert marker_raw, (
        f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 {RUN_MARKER_ENV} 가 없다.\n"
        "  이 표식이 없으면 리포트가 **이번 실행의 것인지 복원된 것인지 구별할 수 없다** — "
        "그 구별이 이 축의 전부다.\n"
        "  CI `kotlin` 잡의 전체 빌드 **앞** 스텝에서 "
        '`echo "KOTLIN_GATE_REACH_RUN_STARTED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$GITHUB_ENV"` '
        "로 박아라."
    )
    marker = _parse_instant(marker_raw)
    assert marker is not None, (
        f"{RUN_MARKER_ENV} 를 순간으로 읽지 못했다: {marker_raw!r}\n"
        "  ISO-8601 이어야 한다(예: 2026-08-21T08:06:19Z). **판정 불가는 통과가 아니다.**"
    )

    assert stamps, (
        f"{RUN_MARKER_ENV} 가 주어졌는데 리포트가 하나도 없다 — 신선도를 판정할 대상이 0 이다.\n"
        f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}"
    )

    unparsed = [
        str(path) for path, value in stamps if value is None or _parse_instant(value) is None
    ]
    assert not unparsed, (
        "리포트에 `testsuite@timestamp` 가 없거나 읽히지 않는다 —\n"
        "  신선도를 판정할 근거가 사라졌다:\n" + "\n".join(f"  - {path}" for path in unparsed)
    )

    stale = [
        (path, value)
        for path, value in stamps
        if (parsed := _parse_instant(str(value))) is not None and parsed < marker
    ]
    assert not stale, (
        f"리포트가 **이번 실행보다 앞선다** (표식 {marker.isoformat()}):\n"
        + "\n".join(f"  - {path.name}: {value}" for path, value in sorted(stale)[:20])
        + (f"\n  … 그리고 {len(stale) - 20}건 더" if len(stale) > 20 else "")
        + "\n  Gradle 이 그 테스트 태스크를 **다시 돌리지 않았다** — `UP-TO-DATE` 이거나 "
        "빌드 캐시에서 `FROM-CACHE` 로 복원했다.\n"
        "  그 상태의 XML 은 「이번 실행이 이 테스트를 돌렸다」의 근거가 될 수 없다.\n"
        "  고치는 법: 전체 빌드에 `--no-build-cache` 를 주고(필요하면 `--rerun-tasks`), "
        "표식 스텝이 빌드 **앞**에 있는지 확인하라."
    )


def test_리포트에_건너뛴_테스트가_없다() -> None:
    """**건너뜀 0** — 바닥뿐 아니라 리포트에 나온 **전부**에 건다.

    범위를 바닥으로 좁히지 않은 근거는 실측이다: 신선한 전체 `build` 의 Kotlin 리포트에서
    실행 1,062 · 건너뜀 **0** 이었다(2026-08-21). `@Tag("llm")` 제외는 발견 단계에서
    빠지므로 리포트에 아예 나오지 않는다 — 즉 오늘 이 저장소에 **정당한 건너뜀이 없다.**
    근거가 그러하므로 범위도 그만큼이다.

    이 단언은 **하한 표가 필요 없다** — 어떤 기제로 껐든 건너뜀으로 나타나기 때문이다.
    정말 하나를 끄고 싶으면 이 단언을 고치는 diff 와 사유가 남는다(그것이 이 축의 값이다).
    """
    _, skipped, seen = _report_counts()
    if _report_state(seen) == REPORT_ABSENT:
        # 리포트가 없으면 이 축은 판정하지 못한다. **조용히 반환하지 않는다** (β-23) —
        # 허용의 근거(요구 모드 배선)를 확인하고 경고를 남긴다.
        _require_reports_are_carried_elsewhere("건너뜀 대조")
        return

    assert not skipped, (
        "Gradle 리포트에 **건너뛴** 테스트가 있다:\n"
        + "\n".join(f"  - {k}: {v}건" for k, v in sorted(skipped.items()))
        + "\n  `@Disabled`·`assumeTrue`/`assumeFalse`·`@EnabledOnOs` 가 여기 나타난다.\n"
        "  **태그 제외는 여기 나타나지 않는다** — 종전 문면은 그것도 여기 나타난다고 적었으나\n"
        '  실측(2026-08-21)으로 거짓이었다: 평문 `@Test` 하나에 `@Tag("llm")` 을 달고\n'
        "  `:api:test` 를 돌렸더니 그 케이스가 XML 에 **아예 없고** skipped 는 0 이었다.\n"
        "  태그 제외를 잡는 것은 이 축이 아니라 이름 축이다\n"
        "  (`test_바닥_클래스가_리포트에서_하한만큼_실제로_돌았다`).\n"
        "  강제자를 끄는 가장 값싼 편집이 한 줄이므로, 그 한 줄이 게이트에 보여야 한다."
    )


#: 이름 변경 판정의 유사도 문턱. git 기본값은 50% 다.
#:
#: 낮추는 이유는 실측이다 — `SqlComments.kt` → `LiveSql.kt` 의 유사도가 **38%** 라
#: 기본값에서는 이름 변경으로 잡히지 않고 이력이 끊긴다(`R038` 로 확인).
#:
#: **틀리는 방향이 안전하다.** 이름 변경을 과잉 판정하면 무관한 파일의 이력이 섞여 라쳇 하한이
#: **높아진다** — 거짓 빨강이고, 고치는 방법이 핀을 올리는 것(라쳇의 정상 동작)이다. 반대로
#: 과소 판정하면 이력이 끊겨 **거짓 초록**이 되고 그것이 β-11 이 겨눈 결함이다.
RENAME_SIMILARITY = "25%"


def _git_revision_paths(rel_path: str) -> list[tuple[str, str]]:
    """`(리비전, **그 리비전에서의 경로**)` 목록. 최신 순. 이력이 없으면 빈 목록.

    ## 왜 경로를 함께 돌려주는가 (β-11)

    옛 판은 `git rev-list HEAD -- <경로>` 로 리비전만 모으고 각 리비전의 내용을
    **현재 경로**로 읽었다. 그래서 파일 이름이 바뀌면 두 번 끊긴다 — ⑴ 이름 변경 이전
    리비전이 목록에 오지 않고 ⑵ 올랐더라도 `git show <rev>:<현재 경로>` 가 빈 문자열이다.
    귀결: **이름 변경과 값 내리기를 한 커밋에 넣으면 라쳇이 초록**이다(교차 종합 β-11).

    실측(2026-08-21): `LiveSql.kt` 의 `git rev-list` 리비전이 **1** 개이고, 그 파일은
    `SqlComments.kt` 에서 이름이 바뀐 것이다.

    `--follow` 는 경로 하나에만 쓸 수 있고 그것이 이 함수의 형태다. `--name-status` 를 함께
    읽어 리비전마다의 **post-image 경로**를 잡는다(이름 변경 줄은 `R<유사도>  옛  새` 이므로
    마지막 칸이 그 리비전의 경로다).
    """
    result = subprocess.run(
        [
            "git",
            "log",
            "--follow",
            f"--find-renames={RENAME_SIMILARITY}",
            "--format=%x00%H",
            "--name-status",
            "HEAD",
            "--",
            rel_path,
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return []
    found: list[tuple[str, str]] = []
    for chunk in result.stdout.split("\0")[1:]:
        lines = [line for line in chunk.splitlines() if line.strip()]
        if not lines:
            continue
        revision = lines[0].strip()
        names = [line for line in lines[1:] if "\t" in line]
        # 이름 줄이 없는 커밋(병합 등)은 직전에 알아낸 경로를 그대로 쓴다 — 가장 최근이
        # 현재 이름이고, 이름 변경을 만나기 전까지는 바뀌지 않는다.
        path = names[0].split("\t")[-1].strip() if names else (found[-1][1] if found else rel_path)
        found.append((revision, path))
    return found


def _history_truncated(rel_path: str) -> str | None:
    """이력이 **끊겼는지** 판정한다. 온전하면 `None`, 끊겼으면 사유.

    가장 오래된 리비전에서 그 파일이 `A`(추가)로 나타나야 이력이 온전하다. 이름 변경(`R`)
    으로 끝나면 `--follow` 가 그 이상 따라가지 못한 것이고, 그 상태의 최댓값은 **일부의
    최댓값**이다. `_git_revision_paths` 가 이름 변경을 넘어가는 것과 별개의 방어다 —
    유사도 문턱 아래의 이름 변경이 남아 있을 수 있다.
    """
    result = subprocess.run(
        [
            "git",
            "log",
            "--follow",
            f"--find-renames={RENAME_SIMILARITY}",
            "--format=%x00%H",
            "--name-status",
            "HEAD",
            "--",
            rel_path,
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    if result.returncode != 0:
        return f"{rel_path} 의 이력을 읽지 못했다: {result.stderr.strip()[:200]}"
    chunks = [chunk for chunk in result.stdout.split("\0")[1:] if chunk.strip()]
    if not chunks:
        return f"{rel_path} 의 리비전이 0 이다"
    oldest = [line for line in chunks[-1].splitlines() if "\t" in line]
    if not oldest:
        return f"{rel_path} 의 가장 오래된 리비전에 파일 상태 줄이 없다"
    status = oldest[0].split("\t")[0].strip()
    if not status.startswith("A"):
        return (
            f"{rel_path} 의 이력이 `{status}` 에서 끊겼다 — "
            "가장 오래된 리비전이 「추가」가 아니다. "
            f"유사도 {RENAME_SIMILARITY} 아래의 이름 변경이 남아 있으면 "
            "이력 최댓값이 **일부의 최댓값**이다."
        )
    return None


def _git_revisions(rel_path: str) -> list[str]:
    """`rel_path` 를 건드린 리비전들. 이름 변경도 따라간다([_git_revision_paths])."""
    return [revision for revision, _ in _git_revision_paths(rel_path)]


def _blob_at(rev: str, rel_path: str) -> str:
    """그 리비전의 파일 내용. 그 리비전에 파일이 없으면 빈 문자열."""
    result = subprocess.run(
        ["git", "show", f"{rev}:{rel_path}"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    return result.stdout if result.returncode == 0 else ""


def _scalar_in(text: str, name: str) -> int | None:
    match = re.search(rf"\b{re.escape(name)}\s*=\s*(\d+)", text)
    return int(match.group(1)) if match else None


def _table_in(text: str, name: str) -> dict[str, int]:
    block = re.search(rf"{re.escape(name)}[^=]*=\s*\{{(.*?)\n\}}", text, re.DOTALL)
    if block is None:
        return {}
    return {key: int(value) for key, value in re.findall(r'"([^"]+)":\s*(\d+),', block.group(1))}


#: 순서 비교 연산자. 이 셋 중 하나가 걸린 비교는 **방향이 있다**.
_LOWER_WHEN_RIGHT = (ast.GtE, ast.Gt)
_UPPER_WHEN_RIGHT = (ast.LtE, ast.Lt)


def _module_int_constants(path: str) -> dict[str, int]:
    """모듈 최상위의 **정수 상수** 인구조사 — `UPPER_SNAKE = <정수>` 를 AST 로 찾는다.

    정규식이 아니라 AST 인 이유(X5, 리더 판정 2026-08-21): 정규식은 ⑴ 주석 처리된 선언과
    문자열 안의 모양을 구분하지 못하고 ⑵ `private const val` 같은 변형에 새 갈래를 요구하고
    ⑶ 실수를 정수로 오인한다(`= 0.05` 에서 `0` 을 읽는다). 그 함정 넷은 `c6-preconditions`
    §3.4 가 Kotlin 쪽에서 실측한 것이고, Python 쪽에서는 `ast` 가 그것을 **전부** 없앤다.

    `bool` 은 뺀다 — 파이썬에서 `True` 는 `int` 의 인스턴스지만 하한·상한이 아니다.
    """
    tree = ast.parse((REPO_ROOT / path).read_text(encoding="utf-8"))
    found: dict[str, int] = {}
    for node in tree.body:
        if isinstance(node, ast.Assign):
            targets: list[ast.expr] = list(node.targets)
        elif isinstance(node, ast.AnnAssign):
            targets = [node.target]
        else:
            continue
        value = node.value
        if not isinstance(value, ast.Constant) or not isinstance(value.value, int):
            continue
        if isinstance(value.value, bool):
            continue
        for target in targets:
            if isinstance(target, ast.Name) and re.fullmatch(r"[A-Z][A-Z0-9_]*", target.id):
                found[target.id] = value.value
    return found


def _bound_direction(path: str) -> dict[str, str]:
    """상수마다 `"lower"` / `"upper"` / `"none"` 을 **실행 성질로** 판정한다.

    판정 규칙: 그 이름이 **순서 비교**(`< <= > >=`)의 한쪽에 나타나면 방향이 있다. 상수가
    오른쪽이고 연산자가 `>=`·`>` 면 `관측 ≥ 상수` 이므로 **하한**, `<=`·`<` 면 **상한**이다.
    왼쪽에 있으면 반대로 읽는다. `==`·`!=` 만 쓰이면 방향이 없다 — 값만 바꿔도 즉시 깨지므로
    이력 대조가 필요 없다는 것이 그 뜻이다.

    **한 상수가 두 방향으로 쓰이면 끊는다.** 그 상태에서 어느 쪽 라쳇을 걸어도 절반은 반대로
    판정하므로, 조용히 한쪽을 고르지 않고 판정 불가로 드러낸다(규칙 4 ⑶ — 판정 불가는
    통과가 아니다).
    """
    tree = ast.parse((REPO_ROOT / path).read_text(encoding="utf-8"))
    names = set(_module_int_constants(path))
    directions: dict[str, set[str]] = {}

    def note(name: str, direction: str) -> None:
        directions.setdefault(name, set()).add(direction)

    for node in ast.walk(tree):
        if not isinstance(node, ast.Compare):
            continue
        operands = [node.left, *node.comparators]
        for index, op in enumerate(node.ops):
            left, right = operands[index], operands[index + 1]
            if isinstance(right, ast.Name) and right.id in names:
                if isinstance(op, _LOWER_WHEN_RIGHT):
                    note(right.id, "lower")
                elif isinstance(op, _UPPER_WHEN_RIGHT):
                    note(right.id, "upper")
            if isinstance(left, ast.Name) and left.id in names:
                if isinstance(op, _LOWER_WHEN_RIGHT):
                    note(left.id, "upper")
                elif isinstance(op, _UPPER_WHEN_RIGHT):
                    note(left.id, "lower")

    resolved: dict[str, str] = {}
    for name in sorted(names):
        found = directions.get(name, set())
        if not found:
            resolved[name] = "none"
        elif len(found) == 1:
            resolved[name] = next(iter(found))
        else:
            raise AssertionError(
                f"{path}::{name} 이 하한과 상한 **양쪽**으로 쓰인다 — 어느 라쳇을 걸어도 "
                "절반은 반대로 판정한다. 상수를 둘로 나누어라."
            )
    return resolved


def _history_unavailable() -> str | None:
    """이력을 읽을 수 없는 사유. 읽을 수 있으면 `None`."""
    if (REPO_ROOT / ".git" / "shallow").exists():
        return "얕은 클론이다(.git/shallow 존재) — fetch-depth 가 0 이 아니다"
    if not _git_revisions(THIS_TEST_PATH):
        return f"{THIS_TEST_PATH} 의 리비전이 0 이다 — 이력이 없다"
    return None


def _require_mode() -> bool:
    return bool(os.environ.get(REQUIRE_REPORT_ENV))


def _report_or_fail_history(reason: str) -> None:
    """이력이 없으면 **모든 실행 모드에서 실패한다** (β-23 · X3).

    옛 판은 요구 모드에서만 실패하고 그 밖에서는 `print` 하고 반환했다 — 실측(2026-08-21,
    고치기 전): `_history_unavailable` 이 얕은 클론 사유를 내는 상태에서 두 이력 대조가
    **조용히 통과**했다. 분모 0 이 통과하는 경로가 리포트 축뿐 아니라 여기에도 있었다.

    **여기는 「없어도 된다」가 아니다.** 리포트 부재는 `quality` 잡에 Gradle 이 없다는
    구조적 사실이지만, git 이력 부재는 그런 사실이 아니다 — 선언된 실행 경로 전부
    (`ci:quality`·`ci:kotlin` 은 `fetch-depth: 0`, 로컬은 전체 클론)에서 이력이 **있다.**
    없다면 실행 환경이 선언과 어긋난 것이므로 fail-closed 가 맞다. 그 선언 자체는
    `test_CI_가_라쳇_기준점을_공급한다` 가 `ci.yml` 에서 되짚는다.
    """
    raise AssertionError(
        f"라쳇 이력 대조를 할 수 없다: {reason}\n"
        "  이 검사의 기준점은 **git 이력**이다. `actions/checkout` 의 기본\n"
        "  `fetch-depth` 는 1 이라\n"
        "  이력이 없고, 그러면 이 축은 존재만 하고 도달이 0 이다.\n"
        "  **판정 불가는 통과가 아니다** — 얕은 클론이면 `fetch-depth: 0` 을 주어라."
    )


def test_라쳇_상수가_이력_최댓값_아래로_내려가지_않는다() -> None:
    """**값 자신이 권위인 하한**을 외부 기준점(git 이력)으로 되짚는다 (C4 R-9).

    실측(고치기 전): 아래 상수들을 1 씩 내려도 전 게이트가 초록이었다. `@Disabled` 한 줄과
    같은 성질(한 줄 · 자동 신호 전부 초록)이므로 같은 기준으로 닫는다.

    **정당한 상향은 통과한다** — 조건이 「현재 ≥ 이력 최댓값」이다.
    """
    reason = _history_unavailable()
    if reason is not None:
        _report_or_fail_history(reason)
        return

    assert RATCHET_SCALAR_PINS, "라쳇 핀 선언이 비었다 — 이 대조는 아무것도 재지 않는다."

    lowered: list[str] = []
    unjudged: list[str] = []
    for rel_path, name in RATCHET_SCALAR_PINS:
        current = _scalar_in((REPO_ROOT / rel_path).read_text(encoding="utf-8"), name)
        if current is None:
            unjudged.append(f"{rel_path}::{name} — 현재 파일에서 그 상수를 찾지 못했다")
            continue
        truncated = _history_truncated(rel_path)
        if truncated is not None:
            unjudged.append(f"{rel_path}::{name} — {truncated}")
            continue
        # **리비전마다 그 시점의 경로로 읽는다** (β-11) — 현재 이름으로 읽으면 이름 변경
        # 이전 리비전이 전부 빈 문자열이 되어 최댓값이 조용히 낮아진다.
        seen = [
            value
            for rev, path_at in _git_revision_paths(rel_path)
            if (value := _scalar_in(_blob_at(rev, path_at), name)) is not None
        ]
        if not seen:
            unjudged.append(f"{rel_path}::{name} — 이력에서 그 상수를 한 번도 찾지 못했다")
            continue
        if current < max(seen):
            lowered.append(f"{rel_path}::{name} — 현재 {current} < 이력 최댓값 {max(seen)}")

    assert not unjudged, (
        "라쳇 핀을 판정하지 못했다 — **판정 불가는 통과가 아니다**:\n"
        + "\n".join(f"  - {x}" for x in unjudged)
        + "\n  이름이 바뀌었거나 파일이 옮겨졌다면 RATCHET_SCALAR_PINS 도 함께 고쳐라."
    )
    assert not lowered, (
        "라쳇 상수가 **이력 최댓값보다 낮다**:\n"
        + "\n".join(f"  - {x}" for x in lowered)
        + "\n  하한을 내리는 것은 그 클래스가 지키기로 선언한 것을 조용히 줄이는 일이다.\n"
        "  정말 내려야 한다면 이 검사가 막는다 — 근거를 리뷰에 올려 이 표에서 그 항목을 빼거나,\n"
        "  실측이 정말 줄었다는 사실을 커밋 메시지로 남겨라(이력은 고쳐지지 않는다)."
    )


@pytest.mark.parametrize("pin", RATCHET_TABLE_PINS, ids=lambda pin: pin[1])
def test_바닥_개수표의_값이_이력_최댓값_아래로_내려가지_않는다(pin: tuple[str, str]) -> None:
    """표 형태의 라쳇도 **키별로** 되짚는다 — 값 하나만 내리는 편집이 같은 한 줄이다."""
    reason = _history_unavailable()
    if reason is not None:
        _report_or_fail_history(reason)
        return

    rel_path, name = pin
    truncated = _history_truncated(rel_path)
    assert truncated is None, f"{rel_path}::{name} 의 이력을 끝까지 읽지 못했다 — {truncated}"
    current = _table_in((REPO_ROOT / rel_path).read_text(encoding="utf-8"), name)
    assert current, f"{rel_path}::{name} 표가 비었다 — 이 대조는 아무것도 재지 않는다."

    history: dict[str, int] = {}
    for rev, path_at in _git_revision_paths(rel_path):
        for key, value in _table_in(_blob_at(rev, path_at), name).items():
            history[key] = max(history.get(key, 0), value)
    assert history, (
        f"{rel_path}::{name} 을 이력에서 한 번도 찾지 못했다 — 판정 불가는 통과가 아니다."
    )

    lowered = {k: (current.get(k), v) for k, v in history.items() if current.get(k, 0) < v}
    assert not lowered, (
        f"{name} 의 값이 **이력 최댓값보다 낮다**:\n"
        + "\n".join(f"  - {k}: 현재 {c} < 이력 최댓값 {h}" for k, (c, h) in sorted(lowered.items()))
        + "\n  키가 사라진 경우도 여기 걸린다 —\n"
        "  항목을 빼는 것은 그 클래스를 무보호로 두는 일이다."
    )


@pytest.mark.parametrize("pin", RATCHET_CEILING_PINS, ids=lambda pin: pin[1])
def test_상한_상수가_이력_최솟값보다_높지_않다(pin: tuple[str, str]) -> None:
    """**예산·문턱은 올라가면 보호가 줄어든다** — 하한 라쳇의 거울상이다.

    조건은 `현재 ≤ 이력 최솟값`. 정당한 하향(더 조이는 편집)은 언제나 통과한다.
    """
    reason = _history_unavailable()
    if reason is not None:
        _report_or_fail_history(reason)
        return

    rel_path, name = pin
    current = _scalar_in((REPO_ROOT / rel_path).read_text(encoding="utf-8"), name)
    assert current is not None, f"{rel_path}::{name} — 현재 파일에서 그 상수를 찾지 못했다"
    truncated = _history_truncated(rel_path)
    assert truncated is None, f"{rel_path}::{name} 의 이력을 끝까지 읽지 못했다 — {truncated}"

    seen = [
        value
        for rev, path_at in _git_revision_paths(rel_path)
        if (value := _scalar_in(_blob_at(rev, path_at), name)) is not None
    ]
    if not seen:
        # 새로 만든 상한은 이력에 없다 — 커밋 뒤 HEAD 가 그것을 포함하면 대조가 선다.
        # 기존 스칼라 핀과 같은 성질이므로 새 동작이 아니다(`c6-preconditions` §7-3).
        pytest.skip(f"{rel_path}::{name} 이 이력에 아직 없다 — 이 커밋이 그것을 만든다")

    assert current <= min(seen), (
        f"상한 상수가 **이력 최솟값보다 높다**: {rel_path}::{name} — "
        f"현재 {current} > 이력 최솟값 {min(seen)}.\n"
        "  예산을 올리는 것은 그 축이 지키기로 선언한 것을 조용히 줄이는 일이다.\n"
        "  정말 올려야 한다면 근거(실측)를 커밋 메시지로 남겨라 — 이력은 고쳐지지 않는다."
    )


def _pin_tuples_in(text: str, name: str) -> set[tuple[str, str]]:
    """소스 텍스트에서 `(경로, 이름)` 튜플 표를 **AST 로** 읽는다.

    정규식이 아닌 이유: 이 표의 항목은 줄바꿈으로 접히고 경로가 암시적 문자열 이어붙이기로
    조립되며 첫 칸이 `THIS_TEST_PATH` 같은 **이름**이다. 세 형태를 정규식으로 받으면 그
    정규식 자신이 조용히 놓치는 표면이 된다.

    파싱할 수 없는 리비전(문법이 달랐던 옛 판 등)은 빈 집합이다 — 그 처분은 호출자가
    「이력에서 한 번도 찾지 못했다」로 판정한다.
    """
    try:
        tree = ast.parse(text)
    except SyntaxError:
        return set()

    strings: dict[str, str] = {}
    table: ast.expr | None = None
    for node in tree.body:
        if isinstance(node, ast.Assign):
            targets: list[ast.expr] = list(node.targets)
        elif isinstance(node, ast.AnnAssign):
            targets = [node.target]
        else:
            continue
        for target in targets:
            if not isinstance(target, ast.Name):
                continue
            value = node.value
            if target.id == name:
                table = value
            elif isinstance(value, ast.Constant) and isinstance(value.value, str):
                strings[target.id] = value.value

    def literal(node: ast.expr) -> str | None:
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        if isinstance(node, ast.Name):
            return strings.get(node.id)
        return None

    if not isinstance(table, (ast.Tuple, ast.List)):
        return set()
    found: set[tuple[str, str]] = set()
    for element in table.elts:
        if not isinstance(element, (ast.Tuple, ast.List)) or len(element.elts) != 2:
            continue
        left, right = literal(element.elts[0]), literal(element.elts[1])
        if left is not None and right is not None:
            found.add((left, right))
    return found


RATCHET_PIN_TABLES = ("RATCHET_SCALAR_PINS", "RATCHET_CEILING_PINS", "RATCHET_TABLE_PINS")


@pytest.mark.parametrize("table", RATCHET_PIN_TABLES)
def test_라쳇_핀_목록이_이력에서_줄지_않았다(table: str) -> None:
    """**핀 튜플을 지우는 편집을 잡는다** — β-08 / B-21 (2026-08-21 리더 판정: 차단으로 확정).

    ## 무엇이 빈자리였나 (실측)

    라쳇 기제 전체가 이 파일 한 곳이고, `test_이_파일의_수치_상수가_전부_분류돼_있다` 의
    정확 분할은 **자기 파일의 상수만** 되짚는다. 그래서 다른 파일(Kotlin)을 가리키는 튜플을
    [RATCHET_SCALAR_PINS] 에서 지우면 저장소 전체에서 빨개지는 것이 **없었다** —
    `c6-preconditions` §3.4 의 음성 대조: 튜플 삭제 `exit 0 · 196 passed`, 튜플 삭제 +
    상수 하향도 `exit 0 · 196 passed`, **지목 없음**.

    ## 왜 면제표가 아니라 이력인가

    처방 후보였던 「Kotlin 상수 인구조사 + 사유 있는 면제표」는 침묵을 **diff 에 남는 허위
    사유 문장**으로 바꿀 뿐이고, 그 사유가 참인지 재는 실행이 다시 0 이 된다(X5 가 방금
    같은 형태를 걷어냈다). 여기서 쓰는 분모는 **git 이력**이다 — 공격자가 PR diff 안에서
    고칠 수 없고, 새 면제 조항을 만들지 않으며(은폐형 회피), 판정이 실행이다.

    조건은 `현재 ⊇ 이력 합집합`. 항목을 **더하는** 편집은 언제나 통과한다.
    """
    reason = _history_unavailable()
    if reason is not None:
        _report_or_fail_history(reason)
        return

    truncated = _history_truncated(THIS_TEST_PATH)
    assert truncated is None, f"{THIS_TEST_PATH} 의 이력을 끝까지 읽지 못했다 — {truncated}"

    current = _pin_tuples_in((REPO_ROOT / THIS_TEST_PATH).read_text(encoding="utf-8"), table)
    assert current, (
        f"{table} 를 현재 파일에서 읽지 못했다(또는 비었다) — 표를 통째로 비우는 편집이 "
        "이 대조를 공허하게 만들 수는 없다."
    )

    history: set[tuple[str, str]] = set()
    for rev, path_at in _git_revision_paths(THIS_TEST_PATH):
        history |= _pin_tuples_in(_blob_at(rev, path_at), table)
    if not history:
        pytest.skip(f"{table} 이 이력에 아직 없다 — 이 커밋이 그것을 만든다")

    removed = sorted(history - current)
    assert not removed, (
        f"{table} 에서 **이력에 있던 핀이 사라졌다**:\n"
        + "\n".join(f"  - {path}::{name}" for path, name in removed)
        + "\n  핀을 지우면 그 상수는 다시 「값 자신이 권위인」 상태가 되고, 같은 커밋에서\n"
        "  값을 내려도 저장소 전체에서 빨개지는 것이 없다(β-08 실측: exit 0 · 196 passed).\n"
        "  정말 지워야 한다면 그 상수가 사라졌기 때문일 것이다 — 그러면 이 목록과 상수를\n"
        "  **같은 커밋에서** 지우고 사유를 커밋 메시지에 남겨라(이력은 고쳐지지 않는다)."
    )


def test_이_파일의_수치_상수가_전부_분류돼_있다() -> None:
    """**정확 삼분할** — 하한 라쳇 / 상한 라쳇 / 방향 없음 중 하나여야 한다.

    ## 판정이 사유 문장에서 **실행 성질**로 옮겨왔다 (X5, 2026-08-21 리더 판정)

    종전 판정은 「[NON_RATCHET_PINS] 의 사유가 비어 있지 않은가」였다. 그 강제는 사유가
    **참인지** 재지 않으므로, 방향 있는 새 하한 상수를 그럴듯한 사유와 함께 그 표에 넣는
    변이가 **두 줄에 197 passed** 였다(`c6-preconditions` §3.3 실측).

    이제 방향을 [_bound_direction] 이 AST 로 판정한다 — 그 상수가 `< <= > >=` 로 비교되는지를
    **실제 코드에서** 본다. 그래서 위장 면제가 통과하지 못하고, 「오늘 방향 있는 면제가 0 건」
    이라는 사실에 기대지도 않는다(규칙 3 — 「오늘 0건」으로 닫지 않는다).

    교체 비용이 유지 비용보다 크지 않다는 것도 실측이다 — `ast` 프로토타입 35줄이 손으로 적은
    분할을 **오차 없이 재현**했고 실행 성질로 대조 불가능한 항목은 **0 건**이었다.
    """
    declared = _module_int_constants(THIS_TEST_PATH)
    assert declared, "이 파일에서 수치 상수를 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다."

    directions = _bound_direction(THIS_TEST_PATH)
    by_direction = {
        "lower": {name for name, kind in directions.items() if kind == "lower"},
        "upper": {name for name, kind in directions.items() if kind == "upper"},
        "none": {name for name, kind in directions.items() if kind == "none"},
    }
    classified = {
        "lower": {name for path, name in RATCHET_SCALAR_PINS if path == THIS_TEST_PATH},
        "upper": {name for path, name in RATCHET_CEILING_PINS if path == THIS_TEST_PATH},
        "none": set(NON_RATCHET_PINS),
    }

    mismatches = [
        f"  {kind}: AST 판정 {sorted(by_direction[kind])} / 선언 {sorted(classified[kind])}"
        for kind in ("lower", "upper", "none")
        if by_direction[kind] != classified[kind]
    ]
    assert not mismatches, (
        "이 파일의 수치 상수 분류가 **실행 판정과 어긋난다**:\n"
        + "\n".join(mismatches)
        + "\n  하한(`관측 ≥ 상수`)은 RATCHET_SCALAR_PINS, 상한(`관측 < 상수`)은\n"
        "  RATCHET_CEILING_PINS, 방향 없는 것(`==` 로만 쓰임)은 NON_RATCHET_PINS 다.\n"
        "  분류를 고치는 것이 아니라 **왜 방향이 그렇게 읽혔는지**를 먼저 보라."
    )
    assert all(NON_RATCHET_PINS.values()), (
        "NON_RATCHET_PINS 의 사유가 빈 항목이 있다 — 사유는 이제 게이트가 아니지만 "
        "다음 사람이 읽을 기록이므로 비워 두지 않는다."
    )


def test_방향_판정기가_하한과_상한과_무방향을_가른다(tmp_path: Path) -> None:
    """**[_bound_direction] 자신의 음성 대조** — 판정기가 세 갈래를 실제로 가르는가.

    이 케이스가 없으면 판정기가 언제나 `"none"` 을 돌려주는 변이에서 위 삼분할이
    **조용히 통과한다**(모든 상수가 방향 없음 → NON_RATCHET_PINS 에 다 들어가면 초록).
    합성 소스로 세 형태를 먹여 셋이 각각 발화함을 실행으로 고정한다.
    """
    probe = tmp_path / "probe.py"
    probe.write_text(
        "\n".join(
            [
                "LOWER = 3",
                "UPPER = 9",
                "EXACT = 5",
                "def f(observed):",
                "    assert observed >= LOWER",
                "    assert observed < UPPER",
                "    assert observed == EXACT",
            ]
        ),
        encoding="utf-8",
    )
    relative = str(probe.relative_to(probe.anchor)) if probe.is_absolute() else str(probe)
    # `_bound_direction` 은 `REPO_ROOT` 기준 상대 경로를 받는다. 합성 파일을 그 밑에 두지
    # 않으므로 `os.path.relpath` 로 되짚는다 — 판정 대상은 내용이고 위치가 아니다.
    import os as _os

    resolved = _os.path.relpath(probe, REPO_ROOT)
    assert _module_int_constants(resolved) == {"LOWER": 3, "UPPER": 9, "EXACT": 5}
    assert _bound_direction(resolved) == {"LOWER": "lower", "UPPER": "upper", "EXACT": "none"}, (
        "판정기가 세 갈래를 가르지 못한다 — 이 상태에서는 위 삼분할이 공허하다."
    )
    del relative


def test_게이트_스캐너의_실행_시간이_예산_안이다() -> None:
    """**가드가 CI 예산을 먹어 다른 가드를 죽이는 것**을 잡는다 (2026-08-21 리더 판정 ⑤).

    근거와 예산의 출처는 [SCANNER_TIME_BUDGET_SECONDS] 주석에 있다. 여기서 하는 일은
    **캐시를 비우고** 스캐너 다섯을 한 번씩 부른 시간을 재는 것이다.

    ## 상대 이상치 축은 **세우지 않았다** — 실측 근거

    리더가 후보로 준 둘 중 하나를 실측으로 버렸다. 오늘 개별 스캐너의 퍼짐은
    `최댓값/중앙값 = 0.628/0.153 ≈ 4.1` 이다. 문턱을 그 위(예: 20)에 두면 위 사고의 좌표
    (한 함수 205.9s)는 잡지만 **그것은 예산 축이 이미 잡는다** — 즉 새로 잡는 것이 없다.
    반대로 문턱을 조이면 스캐너가 하나 늘거나 줄 때 중앙값이 움직여 거짓 빨강이 나고,
    그때 고치는 법은 문턱을 올리는 것이라 축이 스스로 무력해진다. **잡는 것이 없고 흔들리는
    축은 덮는 범위만 부풀린다** — 그래서 세지 않았다.
    """
    scanners = []
    for name in TIMED_SCANNERS:
        function = globals().get(name)
        assert function is not None, (
            f"{name} 이 이 모듈에 없다 — 스캐너가 사라졌거나 이름이 바뀌었다. "
            "TIMED_SCANNERS 를 조용히 줄이는 편집을 여기서 끊는다."
        )
        scanners.append((name, function))

    elapsed: dict[str, float] = {}
    for name, function in scanners:
        cache_clear = getattr(function, "cache_clear", None)
        if cache_clear is not None:
            cache_clear()
        started = time.perf_counter()
        function()
        elapsed[name] = time.perf_counter() - started

    total = sum(elapsed.values())
    assert total < SCANNER_TIME_BUDGET_SECONDS, (
        f"게이트 스캐너가 {total:.2f}s 를 썼다 — 예산 {SCANNER_TIME_BUDGET_SECONDS}s 를 넘었다.\n"
        + "\n".join(
            f"  - {name}: {value:.2f}s"
            for name, value in sorted(elapsed.items(), key=lambda item: -item[1])
        )
        + "\n  이 파일의 게이트는 CI `quality`(15분)·`kotlin`(25분) 두 잡의 스텝이다.\n"
        "  느려진 검사는 조용히 **잡 타임아웃**으로만 나타난다 — 그때는 원인을 알 수 없다.\n"
        "  가장 흔한 원인은 정규식의 파국적 백트래킹이다(실측: 7.93s → 656.74s)."
    )


def test_리포트에_나온_클래스는_전부_선언에_있다() -> None:
    """**역방향 교차 검증** — 발견 파서가 조용히 놓친 클래스를 Gradle 이 신고한다.

    위 「선언 ↔ 발견」 대조는 발견 파서가 옳다는 전제 위에 선다. 파서가 어떤 클래스를
    못 보면 선언에서도 빠지고 대조는 초록이다 — 그것이 families 열거가 겪은 형태다.
    Gradle 리포트는 **파서와 독립한 관측**이므로 그 전제를 밖에서 되짚는다.

    리포트가 없으면 대상이 0건이다. **조용히 지나가지 않는다** (β-23) — 부재를 허용하는
    근거(요구 모드 배선)를 확인하고 경고를 남긴다.
    """
    _, seen = _report_execution()
    if _report_state(seen) == REPORT_ABSENT:
        _require_reports_are_carried_elsewhere("역방향 대조")
        return
    undeclared = sorted(seen - set(TEST_CLASSES))
    assert not undeclared, (
        f"Gradle 이 돌린 클래스가 선언에 없다: {undeclared}\n"
        "  발견 파서가 이 클래스를 못 봤다는 뜻이다 — 선언을 늘리기 전에 파서가 왜 "
        "못 봤는지부터 확인하라(패키지 선언 부재 · 열 0 이 아닌 선언 · Java 소스 등)."
    )


def _ci_jobs() -> dict[str, list[dict[str, object]]]:
    """`ci.yml` 의 잡 이름 → 스텝 목록.

    **문자열 분할로 세지 않는다.** 첫 판이 그랬다가 곧바로 빈자리를 만들었다 —
    스텝 앞 주석 블록은 `- name:` 으로 자르면 **앞 스텝의 조각**에 붙는데, 그 주석이
    이 파일 경로를 언급하고 있어서 quality 잡의 스텝을 통째로 지워도 대조가 초록이었다
    (게이트 25 자체 음성 대조에서 검출). 주석이 배선을 대신 증명하는 형태이므로
    **YAML 로 파싱해 `run` 문자열만** 본다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    jobs: dict[str, list[dict[str, object]]] = {}
    for job_name, job in (document.get("jobs") or {}).items():
        jobs[str(job_name)] = [step for step in (job.get("steps") or []) if isinstance(step, dict)]
    return jobs


#: 이 대조를 돌리는 **정확한 argv**. 문자열 포함이 아니라 이것과 정확히 같아야 한다 (β-19).
GATE_ARGV: tuple[str, ...] = ("uv", "run", "pytest", THIS_TEST_PATH)

#: **종료 코드를 감추거나 실행을 건너뛰게 하는 셸 요소.** 하나라도 있으면 그 줄은 argv 로
#: 인정하지 않는다 — `|| true` 한 줄, 외부 파이프 한 줄이 「배선됐다」로 읽히던 자리다.
EXIT_HIDING_TOKENS: tuple[str, ...] = ("|", "&", ";", "`", "$(", ">", "<", "\n")

#: 잡 이름까지 고정한다. 「어느 잡에서 어떤 모드로 도는가」가 배선의 내용이므로, 잡이
#: 바뀌면(예: 요구 모드가 값싼 잡으로 내려가 리포트 없이 돌면) 배선이 달라진 것이다.
#:
#: 값은 (잡 이름, 요구 모드 env 값) 이고 `None` 은 「env 로 켜지 않는다」다.
#: **빈 선언은 통과할 수 없다** — 아래 대조가 이 표의 비어 있지 않음을 먼저 단언한다.
GATE_WIRING: tuple[tuple[str, str | None], ...] = (
    ("quality", None),
    ("kotlin", "1"),
)


def _logical_lines(run: str) -> list[str]:
    """`run` 본문을 논리 줄로 쪼갠다. 이어 붙인 줄(`\\`)은 하나로, 주석·빈 줄은 버린다."""
    joined = run.replace("\\\n", " ")
    lines = [line.strip() for line in joined.splitlines()]
    return [line for line in lines if line and not line.startswith("#")]


def _exact_argv(line: str) -> tuple[str, ...] | None:
    """셸 요소가 섞이지 않은 줄만 argv 로 만든다. 섞였으면 `None`."""
    if any(token in line for token in EXIT_HIDING_TOKENS):
        return None
    try:
        return tuple(shlex.split(line))
    except ValueError:
        return None


def _step_rejection(job_name: str, job: dict[str, object], step: dict[str, object]) -> str | None:
    """이 스텝이 **정확한 배선**이 아닌 사유. 정확하면 `None`.

    사유를 문자열로 돌려주는 이유는 실패 메시지가 그 자리를 **지목**해야 하기 때문이다 —
    판정 기준은 「빨개졌는가」가 아니라 「겨눈 자리를 짚었는가」다.
    """
    lines = _logical_lines(str(step.get("run") or ""))
    if len(lines) != 1:
        return f"`run` 이 논리 줄 {len(lines)}개다 — 이 대조는 명령 한 줄로만 배선한다: {lines}"
    argv = _exact_argv(lines[0])
    if argv is None:
        return (
            f"셸 요소가 섞여 종료 코드를 신뢰할 수 없다: {lines[0]!r}\n"
            f"      금지: {' · '.join(EXIT_HIDING_TOKENS[:-1])}\n"
            "      (파이프·`|| true`·리다이렉션·명령 치환)"
        )
    if argv != GATE_ARGV:
        return f"argv 가 정확히 일치하지 않는다: {list(argv)} != {list(GATE_ARGV)}"
    if "if" in step:
        return f"스텝에 `if:` 가 있다 ({step['if']!r}) — 조건 한 줄로 실행을 건너뛸 수 있다"
    if step.get("continue-on-error"):
        return "스텝에 `continue-on-error` 가 켜져 있다 — 빨강이 잡을 죽이지 않는다"
    if "if" in job:
        return f"잡에 `if:` 가 있다 ({job['if']!r}) — 잡 자체가 건너뛰어질 수 있다"
    if job.get("continue-on-error"):
        return f"잡 `{job_name}` 에 `continue-on-error` 가 켜져 있다"
    return None


def _gate_step_candidates() -> list[tuple[str, dict[str, object], dict[str, object]]]:
    """`ci.yml` 에서 이 파일 경로를 **언급하는** 모든 스텝. 정확성 판정은 하지 않는다.

    후보를 따로 모으는 이유는 「언급하지만 정확하지 않은」 스텝을 **조용히 무시하지 않고
    지목**하기 위해서다. 옛 판은 그런 스텝을 배선으로 세었다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    found: list[tuple[str, dict[str, object], dict[str, object]]] = []
    for job_name, job in (document.get("jobs") or {}).items():
        if not isinstance(job, dict):
            continue
        for step in job.get("steps") or []:
            if isinstance(step, dict) and THIS_TEST_PATH in str(step.get("run") or ""):
                found.append((str(job_name), job, step))
    return found


def _steps_running_this_file() -> list[tuple[str, dict[str, object]]]:
    """이 파일을 **정확한 argv 로, 종료 코드를 감추지 않고** 돌리는 스텝만 낸다 (β-19).

    옛 판은 `THIS_TEST_PATH in run` 이었다. 그래서 `|| true` 한 줄, `if: false` 한 줄,
    `continue-on-error: true` 한 줄, 외부 파이프 한 줄이 전부 「배선됐다」로 판정됐다 —
    실측(2026-08-21, 고치기 전): 그 여섯 변이 전부 이 파일의 게이트가 **151 passed · exit 0**.
    """
    return [
        (job_name, step)
        for job_name, job, step in _gate_step_candidates()
        if _step_rejection(job_name, job, step) is None
    ]


def _require_report_flag(step: dict[str, object]) -> str:
    """스텝의 `env` 에서 요구 모드 값을 읽는다. `env` 가 없거나 매핑이 아니면 빈 문자열."""
    env = step.get("env")
    if not isinstance(env, dict):
        return ""
    return str(env.get(REQUIRE_REPORT_ENV) or "")


def _wired_require_mode_jobs() -> list[str]:
    """요구 모드로 이 대조를 **정확히** 돌리는 잡 이름. 리포트 부재의 허용 근거가 이것이다."""
    return [job for job, step in _steps_running_this_file() if _require_report_flag(step)]


def test_CI_가_이_대조를_경로_명시로_배선했다() -> None:
    """**장치 밖에서 무언가 깨져야 한다** (SKILL.md 규칙 6).

    이 파일 안에만 단언을 두면 파일과 함께 사라진다. `ci.yml` 이 이 경로를 명시해
    돌리는지, 그리고 실행 대조가 **요구 모드**로 켜지는지를 여기서 되짚는다.

    ## 문자열 포함이 아니라 argv·잡·스텝 고정이다 (β-19)

    옛 판은 `run` 문자열에 경로가 들어 있으면 배선으로 셌다. 그것은 **명령 실행의 대리
    측정**이고, 실측으로 여섯 가지 한 줄 우회가 전부 통과했다(§ [_steps_running_this_file]).
    지금은 ⑴ argv 가 [GATE_ARGV] 와 정확히 같아야 하고 ⑵ 종료 코드를 감추는 셸 요소가
    없어야 하고 ⑶ 스텝·잡에 `if:`·`continue-on-error` 가 없어야 하고 ⑷ 잡 이름과 요구
    모드 값이 [GATE_WIRING] 과 정확히 일치해야 한다.

    `continue-on-error` 를 「예상된 빨강」의 표기로 쓰지 않는다 — 그것은 종료 코드를 감추는
    은폐형이다. 예상된 빨강은 종료 코드가 아니라 **문서로** 표기한다.
    """
    candidates = _gate_step_candidates()
    assert candidates, (
        f"ci.yml 이 {THIS_TEST_PATH} 를 경로로 명시해 돌리지 않는다 — `uv run pytest` "
        "전체 수집만 믿으면 파일이 지워졌을 때 수집 0 으로 조용히 통과한다."
    )

    rejected = [
        (job_name, step, reason)
        for job_name, job, step in candidates
        if (reason := _step_rejection(job_name, job, step)) is not None
    ]
    assert not rejected, (
        "이 대조를 언급하지만 **정확한 배선이 아닌** 스텝이 있다 — 이런 스텝은 "
        "「배선됐다」의 근거가 될 수 없다:\n"
        + "\n".join(
            f"  - 잡 `{job_name}` · 스텝 {step.get('name') or '(이름 없음)'}\n      {reason}"
            for job_name, step, reason in rejected
        )
        + "\n  고치는 법: `run:` 을 정확히 "
        + f"`{' '.join(GATE_ARGV)}` 한 줄로 두고,\n"
        "  `if:`·`continue-on-error`·파이프·`|| true` 를 없애라."
    )

    assert GATE_WIRING, "배선 선언이 비었다 — 이 대조는 아무것도 재지 않는다."
    actual = sorted(
        (job, _require_report_flag(step) or None) for job, step in _steps_running_this_file()
    )
    expected = sorted(GATE_WIRING)
    assert actual == expected, (
        "이 대조의 배선(잡 이름 × 요구 모드)이 선언과 다르다.\n"
        f"  선언: {expected}\n"
        f"  실제: {actual}\n"
        "  요구 모드(env 값 '1')는 Gradle 리포트가 있는 잡에서, 평문은 값싼 잡에서 돌아야 한다 — "
        "요구 모드가 리포트 없는 잡으로 내려가면 리포트 부재 자체가 상시 실패가 되고, "
        "평문이 사라지면 파일 삭제가 Gradle 빌드를 기다린 뒤에야 드러난다."
    )


#: 전체 빌드 호출. `./gradlew build` · `./gradlew :core:build` 처럼 **필터 없는 build 태스크**만
#: 잡는다. `--build-cache`(앞이 `-`) · `build/test-results`(뒤가 `/`) 는 걸리지 않는다.
GRADLE_FULL_BUILD = re.compile(r"gradlew\b[^\n]*?(?<![\w:.\-])(?::[\w.:\-]+:)?build(?![\w/\-])")

#: **리포트를 덮어쓰는** Gradle 호출. `--tests` 필터가 붙은 test 태스크는 그 모듈의
#: `build/test-results/test/` 를 필터에 걸린 클래스만으로 **다시 쓴다**(실측: `:core:test
#: --tests X` 한 번이면 core 리포트가 통째로 그 하나로 바뀐다).
GRADLE_TEST_FILTER = re.compile(r"gradlew\b.*--tests\b")


def _report_overwriting_lines(run: str) -> list[str]:
    """`run` 본문에서 리포트를 덮어쓰는 줄만 낸다. 이어 붙인 줄(`\\`)은 하나로 본다."""
    joined = run.replace("\\\n", " ")
    return [line.strip() for line in joined.splitlines() if GRADLE_TEST_FILTER.search(line)]


def test_요구모드_대조_앞에_리포트를_덮는_스텝이_없다() -> None:
    """**순서가 아니라 제약을 강제한다** (게이트 28 C-2).

    ## 무엇이 났는가

    `kotlin` 잡의 스텝 순서가 이랬다 — ⑴ `./gradlew build`(전 모듈 테스트, core 리포트를
    전건으로 채움) ⑵ `:core:test --tests …ProvenanceCreationSitesTest` ⑶ `:core:test
    --tests …MaskedTextGatewayTest` ⑷ 요구 모드 대조. ⑵⑶ 이 core 리포트를 **한 클래스로
    덮어쓰므로** ⑷ 는 덮인 것을 읽고 **결정론적으로 실패**했다(run 32333596159·
    32309434868 연속 빨강). 미실행 목록에 `ProvenanceCreationSitesTest` **자신**이 들어
    있었던 것이 그 기제의 서명이다.

    ## 왜 순서만 바꾸지 않는가

    `ci.yml` 은 「이 스텝이 `build` **뒤에** 있어야 하는 이유」를 이미 적어 두었는데,
    「그 사이에 리포트를 덮는 것이 없어야 한다」는 제약은 **적히지도 강제되지도 않았다.**
    순서만 고치면 다음 사람이 스텝 하나를 사이에 끼워 같은 일을 조용히 되풀이한다.
    그래서 제약 자체를 **탐지형**으로 세운다.

    ## 이름 면제를 두지 않는다

    「이 스텝 이름은 예외」 같은 패턴 면제는 `CLAUDE.md` 규칙 4 ⑵ 가 금지한 은폐형이고,
    면제 하나가 곧 이 장치의 구멍이다. 사이에 `--tests` 필터를 건 gradlew 호출이 있으면
    이름이 무엇이든 **실패**하고, 실패 메시지가 그 스텝을 잡 이름·번호·이름·해당 줄로
    **지목**한다(판정 기준은 「빨개졌는가」가 아니라 「지목했는가」다).

    ## 이 테스트가 닫지 않는 것

    - `--tests` 없이 리포트를 지우는 스텝(`rm -rf build/test-results`·`clean`). 오늘
      그런 스텝은 없고, 넓히려면 근거가 생긴 뒤에 넓힌다(규칙 4 ⑴ — 범위는 근거를
      넘지 않는다).
    - 이 테스트 자신의 삭제. 저장소 안의 어떤 파일도 자기 자신에 대한 절대 기준이 될 수
      없다 — 다만 이 파일의 삭제는 `ci.yml` 의 두 경로 명시 스텝이 exit 4 로 신고한다.
    """
    require_steps = [
        (job, step) for job, step in _steps_running_this_file() if _require_report_flag(step)
    ]
    assert require_steps, (
        f"{REQUIRE_REPORT_ENV} 를 env 로 켜고 {THIS_TEST_PATH} 를 돌리는 스텝이 없다 — "
        "제약을 걸 대상 자체가 사라졌다. kotlin 잡의 build 뒤에 배선하라."
    )

    jobs = _ci_jobs()
    for job_name, require_step in require_steps:
        steps = jobs[job_name]
        require_index = steps.index(require_step)

        build_indexes = [
            index
            for index, step in enumerate(steps[:require_index])
            if GRADLE_FULL_BUILD.search(str(step.get("run") or ""))
        ]
        assert build_indexes, (
            f"`{job_name}` 잡의 요구 모드 대조(스텝 {require_index + 1}: "
            f"{require_step.get('name') or '(이름 없음)'}) **앞에** 전체 빌드가 없다.\n"
            "  요구 모드는 리포트 부재를 실패로 판정한다 — 리포트를 만드는 `./gradlew build` "
            "가 앞에 없으면 이 대조는 언제나 빨갛다."
        )

        offenders = [
            (index, steps[index], line)
            for index in range(build_indexes[-1] + 1, require_index)
            for line in _report_overwriting_lines(str(steps[index].get("run") or ""))
        ]
        assert not offenders, (
            f"`{job_name}` 잡에서 전체 빌드(스텝 {build_indexes[-1] + 1})와 요구 모드 대조"
            f"(스텝 {require_index + 1}) **사이**에 테스트 리포트를 덮어쓰는 스텝이 있다:\n"
            + "\n".join(
                f"    스텝 {index + 1}: {step.get('name') or '(이름 없음)'}\n      {line}"
                for index, step, line in offenders
            )
            + "\n  `--tests` 필터를 건 test 태스크는 그 모듈의 build/test-results 를 필터에 "
            "걸린 클래스만으로 다시 쓴다.\n"
            "  그 뒤에서 도는 실행 대조는 **덮인 리포트**를 읽어 선언 전건을 미실행으로 "
            "신고한다(게이트 28 C-2, run 32333596159).\n"
            "  고치는 방법: 그 스텝을 요구 모드 대조 **뒤로** 옮겨라. `No tests found for "
            "given includes` 로 존재를 재는 목적은 순서와 무관하다."
        )


#: 이번 실행 표식을 `$GITHUB_ENV` 에 박는 줄. 변수 이름과 대상 파일 **둘 다** 봐야 한다 —
#: 이름만 보면 주석에 적힌 것이 배선을 대신 증명한다(게이트 25 가 그 형태를 실측했다).
RUN_MARKER_STAMP = re.compile(
    rf"{re.escape(RUN_MARKER_ENV)}=[^\n]*>>\s*\"?\$(?:\{{)?GITHUB_ENV",
)

#: 빌드 캐시를 끄는 플래그. 이것이 없으면 테스트 태스크가 `FROM-CACHE` 로 복원되고,
#: 복원된 XML 은 「이번 실행이 돌렸다」의 근거가 되지 못한다(β-02 실측 ⑵).
BUILD_CACHE_OFF: tuple[str, ...] = ("--no-build-cache", "--rerun-tasks")


def test_CI_가_이번_실행_표식과_캐시_금지를_배선했다() -> None:
    """신선도 축의 **배선**을 되짚는다 (β-02).

    표식이 없으면 `test_요구모드_리포트가_이번_실행에서_만들어졌다` 가 요구 모드에서
    실패하므로 그 축은 fail-closed 다. 그러나 「표식을 박는 스텝이 빌드 **앞에** 있는가」와
    「전체 빌드가 캐시를 끄고 도는가」는 그 테스트가 스스로 알 수 없다 — 그래서 여기서
    `ci.yml` 을 읽어 두 가지를 잡·스텝 순서로 고정한다.

    ## 왜 `--rerun-tasks` 가 아니라 `--no-build-cache` 를 기본으로 받는가

    `--rerun-tasks` 는 컴파일까지 전부 다시 돌려 잡 시간을 크게 늘린다. CI 는 매번 새
    체크아웃이라 프로젝트 `build/` 가 없고, 남는 재사용 경로는 `~/.gradle` 의 **빌드
    캐시** 하나다 — `--no-build-cache` 가 정확히 그것을 끊는다. 둘 중 하나면 통과시키고,
    실제로 신선한지는 표식 축이 **실행마다** 판정한다(선언이 아니라 측정이 근거다).
    """
    jobs = _ci_jobs()
    require_jobs = _wired_require_mode_jobs()
    assert require_jobs, (
        f"{REQUIRE_REPORT_ENV} 를 정확한 argv 로 켜는 스텝이 없다 — 신선도 축을 걸 자리가 없다."
    )

    for job_name in require_jobs:
        steps = jobs[job_name]
        require_index = next(
            index
            for index, step in enumerate(steps)
            if THIS_TEST_PATH in str(step.get("run") or "") and _require_report_flag(step)
        )
        build_indexes = [
            index
            for index, step in enumerate(steps[:require_index])
            if GRADLE_FULL_BUILD.search(str(step.get("run") or ""))
        ]
        assert build_indexes, f"`{job_name}` 잡의 요구 모드 대조 앞에 전체 빌드가 없다."

        first_build = build_indexes[0]
        marker_indexes = [
            index
            for index, step in enumerate(steps[:first_build])
            if RUN_MARKER_STAMP.search(str(step.get("run") or ""))
        ]
        assert marker_indexes, (
            f"`{job_name}` 잡에서 전체 빌드(스텝 {first_build + 1}) **앞에** "
            f"{RUN_MARKER_ENV} 를 `$GITHUB_ENV` 에 박는 스텝이 없다.\n"
            "  표식이 빌드보다 **뒤**에 있으면 복원된 리포트도 표식보다 나중이 되어 이 축이 "
            "언제나 초록이 된다 — 순서가 이 축의 전부다.\n"
            '  예: run: echo "'
            + RUN_MARKER_ENV
            + '=$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$GITHUB_ENV"'
        )

        for index in build_indexes:
            run = str(steps[index].get("run") or "")
            assert any(flag in run for flag in BUILD_CACHE_OFF), (
                f"`{job_name}` 잡의 전체 빌드(스텝 {index + 1}: "
                f"{steps[index].get('name') or '(이름 없음)'})가 빌드 캐시를 끄지 않는다.\n"
                f"  이 저장소는 `org.gradle.caching=true` 다 — 캐시를 켜 둔 채로는 테스트 "
                "태스크가 `FROM-CACHE` 로 복원되고, 복원된 XML 은 원래 `timestamp` 를 그대로 "
                "되살린다(실측).\n"
                f"  {' 또는 '.join(BUILD_CACHE_OFF)} 중 하나를 주어라."
            )


def test_CI_가_라쳇_기준점을_공급한다() -> None:
    """라쳇 이력 대조의 기준점이 **실행 환경에 실재함**을 배선으로 고정한다 (β-23 · X3).

    `_report_or_fail_history` 를 모든 모드에서 실패로 바꿨으므로, 이력이 없는 환경에서는
    이 파일의 게이트가 통째로 빨개진다. 그 fail-closed 가 상시 빨강이 되지 않으려면
    이 대조를 돌리는 **모든 잡**의 checkout 이 `fetch-depth: 0` 이어야 한다 — 그 선언을
    여기서 되짚는다. 「이력이 있어야 한다」와 「이력을 공급한다」가 서로 다른 파일에 적혀
    있으면 한쪽만 고쳐지는 날 조용히 어긋난다.
    """
    document = yaml.safe_load(CI_WORKFLOW.read_text(encoding="utf-8"))
    running_jobs = {job for job, _ in _steps_running_this_file()}
    assert running_jobs, "이 대조를 돌리는 잡이 없다 — 배선 대조가 아무것도 재지 않는다."

    shallow: list[str] = []
    for job_name in sorted(running_jobs):
        job = (document.get("jobs") or {})[job_name]
        checkouts = [
            step
            for step in (job.get("steps") or [])
            if isinstance(step, dict) and str(step.get("uses") or "").startswith("actions/checkout")
        ]
        if not checkouts:
            shallow.append(f"{job_name}: checkout 스텝이 없다")
            continue
        for step in checkouts:
            options = step.get("with")
            depth = options.get("fetch-depth") if isinstance(options, dict) else None
            if str(depth) != "0":
                shallow.append(f"{job_name}: fetch-depth={depth!r} (0 이어야 한다)")

    assert not shallow, (
        "이 대조를 돌리는 잡의 checkout 이 전체 이력을 가져오지 않는다:\n"
        + "\n".join(f"  - {entry}" for entry in shallow)
        + "\n  라쳇 핀은 **값 자신이 권위**라 저장소 안에서는 낮아졌는지 알 수 없고, 유일한 "
        "외부 기준점이 git 이력이다.\n"
        "  `fetch-depth: 0` 이 사라지면 그 축은 존재만 하고 도달이 0 이 된다 — 그래서 이제 "
        "이력 부재는 **모든 모드에서 실패**이고, 이 배선이 그 실패를 상시 빨강으로 만들지 "
        "않는 전제다."
    )


# ─────────────────────────────────────────────────────────────────────────────
# 회귀 음성 대조 — **한 줄 우회를 합성 입력으로 고정한다**
#
# 위 대조들은 실물 트리·실물 `ci.yml` 을 본다. 그 자체로는 「지금 이 트리에서 참」일 뿐이고,
# 판정 로직이 다시 느슨해졌을 때 알려 주지 않는다(트리에 그 변이가 없으니까). 아래는 변이를
# **합성 입력으로** 넣어 판정기가 그것을 짚는지 재는 축이다 — G-β 에서 손으로 잰 것을
# 회귀로 고정한다.
# ─────────────────────────────────────────────────────────────────────────────

#: 합성 Kotlin 소스. 실물 파일을 건드리지 않고 어휘 분석기를 시험한다.
_SYNTHETIC_SOURCE = '''package kr.easydoc.probe

/**
 * KDoc 이 예시로 `@Test` 를 적는다 — 세면 안 된다.
 */
class Probe {
    // @Test 주석 처리된 애너테이션 — 세면 안 된다
    @Test
    @DisplayName("도는 것")
    fun `도는 것`() {
        val 문서 = "문자열 안의 @Test — 세면 안 된다"
        val 원시 = """여러 줄 문자열 안의 @Test — 세면 안 된다"""
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = [
            "여러 줄 애너테이션 인자 — 블록이 끊기면 이 메서드를 놓친다",
        ],
    )
    @DisplayName("여러 줄 인자")
    fun `여러 줄 인자`(값: String) = Unit
}
'''


def test_어휘_분석이_주석과_문자열_안의_애너테이션을_세지_않는다() -> None:
    """β-20 의 **한 줄 우회**(`// @Test`)를 합성 입력으로 고정한다.

    실측(고치기 전): `ContractErrorBodyReachTest.kt:304` 의 `@Test` 를 `// @Test` 로 바꿨더니
    소스 축 계수가 11 그대로였고 이 파일의 게이트가 151 passed 였다. 그 자리를 정규식 하나로
    되돌리는 편집이 다시 가능하므로, 판정기 자신을 합성 입력으로 잰다.
    """
    raw_count = len(TEST_ANNOTATION.findall(_SYNTHETIC_SOURCE))
    lexed_count = len(TEST_ANNOTATION.findall(_blank_comments_and_strings(_SYNTHETIC_SOURCE)))
    assert raw_count == 6, f"합성 입력의 전제가 깨졌다 — 원문 적중 {raw_count}건(6 이어야 한다)"
    assert lexed_count == 2, (
        f"어휘 분석 뒤 테스트 애너테이션이 {lexed_count}개다 — 2 여야 한다.\n"
        '  주석(`// @Test`·KDoc)과 문자열(`"… @Test …"`·`"""… @Test …"""`) 안의 적중이 '
        "다시 세어지고 있다. 그러면 `// @Test` 한 줄로 하한을 통과시킬 수 있다."
    )


def test_메서드_파서가_여러_줄_애너테이션_인자를_넘어간다() -> None:
    """실측으로 잡힌 파서 결함을 고정한다 (β-20).

    첫 판은 애너테이션 블록을 `(?:^[ \\t]*@[^\\n]*\\n)+` 로 잡아 `@ValueSource(strings = [ … ])`
    처럼 인자가 여러 줄인 애너테이션에서 블록이 끊겼다 — `CoreModuleBoundaryTest` 의 테스트를
    **0개**로 셌고 `test_테스트_메서드_파서가_애너테이션_전수를_덮는다` 가 그것을 잡았다.
    """
    blanked = _blank_comments_and_strings(_SYNTHETIC_SOURCE)
    found = _annotated_functions(blanked)
    names = [name for _, _, name in found]
    assert names == ["도는 것", "여러 줄 인자"], (
        f"파서가 찾은 함수: {names} — 둘 다 찾아야 한다.\n"
        "  둘째가 빠지면 여러 줄 애너테이션 인자에서 블록이 끊긴 것이다."
    )
    plain = [name for start, end, name in found if PLAIN_TEST_ANNOTATION.search(blanked[start:end])]
    generated = [
        name for start, end, name in found if GENERATED_TEST_ANNOTATION.search(blanked[start:end])
    ]
    assert plain == ["도는 것"] and generated == ["여러 줄 인자"], (
        f"평문·생성 분류가 어긋났다: 평문 {plain} · 생성 {generated}"
    )


#: `ci.yml` 스텝 모양의 합성 입력. (사유가 담아야 할 낱말, 스텝, 잡)
_WIRING_MUTATIONS: tuple[tuple[str, dict[str, object], dict[str, object]], ...] = (
    ("셸 요소", {"run": f"{' '.join(GATE_ARGV)} || true"}, {}),
    ("셸 요소", {"run": f"{' '.join(GATE_ARGV)} | tail -5"}, {}),
    ("셸 요소", {"run": f"{' '.join(GATE_ARGV)} > /dev/null"}, {}),
    ("if:", {"run": " ".join(GATE_ARGV), "if": False}, {}),
    ("continue-on-error", {"run": " ".join(GATE_ARGV), "continue-on-error": True}, {}),
    ("argv", {"run": "uv run pytest tests/"}, {}),
    ("논리 줄", {"run": f"echo 시작\n{' '.join(GATE_ARGV)}"}, {}),
    ("잡", {"run": " ".join(GATE_ARGV)}, {"continue-on-error": True}),
    ("잡", {"run": " ".join(GATE_ARGV)}, {"if": False}),
)


@pytest.mark.parametrize(("needle", "step", "job"), _WIRING_MUTATIONS)
def test_배선_판정기가_한_줄_우회를_짚는다(
    needle: str, step: dict[str, object], job: dict[str, object]
) -> None:
    """β-19 의 **여섯 가지 한 줄 우회**를 합성 입력으로 고정한다.

    실측(고치기 전): `|| true`·`if: false`·`continue-on-error: true`·외부 파이프를 실물
    `ci.yml` 에 심은 여섯 변이 전부가 이 파일의 게이트를 **151 passed · exit 0** 으로
    통과했다. 판정이 `THIS_TEST_PATH in run` 이었기 때문이다.

    **「빨개졌는가」가 아니라 「짚었는가」로 판정한다** — 사유 문자열이 그 자리를 가리켜야 한다.
    """
    reason = _step_rejection("probe", job, step)
    assert reason is not None, f"이 스텝을 정확한 배선으로 판정했다: {step} / 잡 {job}"
    assert needle in reason, f"사유가 그 자리를 짚지 않는다: {reason!r} (기대 낱말: {needle!r})"


def test_배선_판정기가_정상_스텝을_거절하지_않는다() -> None:
    """양성 대조 — 판정기가 무엇이든 거절하면 위 음성 대조는 아무것도 증명하지 않는다."""
    healthy: tuple[dict[str, object], ...] = (
        {"run": " ".join(GATE_ARGV)},
        {"run": f"  {' '.join(GATE_ARGV)}  \n"},
    )
    for step in healthy:
        assert _step_rejection("probe", {}, step) is None, f"정상 스텝을 거절했다: {step}"
