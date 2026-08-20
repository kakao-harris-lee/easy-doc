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
3. **개수 상수.** 파일과 선언을 **함께** 지우는 편집은 위 1·2 로 잡히지 않는다.
   그때 `TEST_CLASS_COUNT` 를 같이 고쳐야 하므로 diff 가 두 자리에서 난다.
3-a. **개수 하한.** 3 은 두 수기 선언 사이의 일관성일 뿐이라 **함께 줄이면 통과한다.**
   `MIN_TEST_CLASSES` 는 그 축을 밖에서 되짚는다.
3-b. **바닥 목록.** 다른 판정이 근거로 인용하는 탐지기는 `FLOOR_TEST_CLASSES` 에 있고,
   그것이 선언에서 빠지면 빨개진다. **바닥이지 천장이 아니다** — 새 테스트를 여기 적을
   필요는 없다.
4. **실행 대조.** Gradle 리포트 XML 의 **`testcase@classname`** 으로 집계하고
   **`<skipped>` 는 실행으로 세지 않는다.** `testsuite@name` 은 `@DisplayName` 이라
   FQCN 이 아니다(B-1). `tests` 속성은 skipped 를 포함한다(T-1).
5. **리포트 → 선언 역방향.** 리포트에 나오는 클래스는 **전부 선언에 있어야 한다.**
   위 1 의 발견 파서가 조용히 놓친 클래스를 Gradle 자신이 신고하는 축이다 — 파서와
   리포트가 서로를 교차 검증한다.

## `pytest.skip` 을 쓰지 않는다 (T-1 의 둘째 얼굴)

앞선 판은 `KOTLIN_GATE_REACH_REQUIRE_REPORT` 가 꺼져 있으면 실행 대조를 통째로
`skip` 했다. 그래서 리포트가 없는 모든 실행이 **재지 않은 채 통과**했다.
지금은 두 상태를 이렇게 가른다 — **어느 쪽도 skip 이 아니다.**

- **요구 모드 ON**(CI `kotlin` 잡, 전체 빌드 직후) — 리포트가 없으면 실패하고,
  선언한 **전건**에 실행 기록을 요구한다.
- **요구 모드 OFF**(로컬·`quality` 잡) — **리포트에 실재하는 클래스에 대해서만**
  실행 기록을 요구한다. 부분 리포트에서도 거짓 없이 재고, `@Disabled` 는 여기서도
  잡힌다. 로컬 리포트가 마지막에 돌린 것만 남는 성질(실측: `:core:test --tests X`
  한 번이면 그 모듈 리포트가 통째로 그 하나로 바뀐다)이 오경보를 만들지 않는다.

**남는 자리(정직하게 적는다).** 리포트가 **하나도 없는** 상태에서 요구 모드가 꺼져
있으면 4번 축의 대상이 0건이 된다. 그 상태를 닫는 것은 요구 모드이고, 요구 모드가
`ci.yml` 에서 사라지지 않게 하는 것은 아래 배선 확인이다. 1·2·3·5 는 리포트 없이도
전부 돈다.

## 이 파일이 닫지 않는 것 (적어 둔다)

- **파일과 선언과 개수를 한 커밋에서 함께 지우는 편집**은 리뷰 diff 가 최종 방어선이다.
  `ci.yml:263-264` 가 같은 자리에서 같은 문장을 적었다. **한 칸 더 옮기지 않는다.**
- **발견 파서는 최상위 선언을 열 0 의 `class`/`object` 로 찾는다.** 여러 줄 문자열
  안에 열 0 짜리 선언 모양이 들어 있으면 유령 선언이 생긴다 — 그때는 discovered 에만
  남아 **빨개진다**(조용하지 않다). 도입 시점 실측: 발견 집합이 Gradle 리포트의 클래스
  집합과 **정확히 일치**했다(양쪽 차집합 0). 개수를 여기 적지 않는 이유는 그것이 다음
  커밋에 곧바로 거짓이 되기 때문이다 — 개수는 `TEST_CLASS_COUNT` 가 진다.
"""

from __future__ import annotations

import os
import re
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

#: **테스트 클래스임을 드러내는 JUnit 애너테이션.** 이름이 아니라 종류다 —
#: `@Nested` 안에만 있어도 바깥 선언 구간에 들어오므로 함께 잡힌다.
TEST_ANNOTATION = re.compile(r"@(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")

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
    "kr.easydoc.api.CorsContractTest",
    "kr.easydoc.api.DeletedAccountTokenReachTest",
    "kr.easydoc.api.DocumentBodyLogLeakReachTest",
    "kr.easydoc.api.DocumentContractNodeTest",
    "kr.easydoc.api.DocumentContractTest",
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
    "kr.easydoc.api.ParserNodeRegistryTest",
    "kr.easydoc.api.PasswordHashLogLeakReachTest",
    "kr.easydoc.api.PasswordHashingBackpressureReachTest",
    "kr.easydoc.api.PrivateResponseHeadersContractTest",
    "kr.easydoc.api.PrivateResponseHeadersReachTest",
    "kr.easydoc.api.RequestFieldConstraintLayerTest",
    "kr.easydoc.api.RequestFieldRejectionLayerTest",
    "kr.easydoc.api.SensitiveToStringReachTest",
    "kr.easydoc.api.SourceScanFormsProbe",
    "kr.easydoc.api.TitlePolicyContractTest",
    "kr.easydoc.api.UploadFormatContractTest",
    "kr.easydoc.api.WorkspaceContractTest",
    "kr.easydoc.api.WorkspaceDtoLeakTest",
    "kr.easydoc.api.WorkspaceEndpointReachTest",
    "kr.easydoc.application.auth.AuthServiceTest",
    "kr.easydoc.application.conversion.ConversionParityTest",
    "kr.easydoc.application.conversion.ConvertDocumentUseCaseTest",
    "kr.easydoc.application.conversion.RepairDecisionTest",
    "kr.easydoc.application.document.DocumentServiceTest",
    "kr.easydoc.application.document.EnvelopeRotationTest",
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
TEST_CLASS_COUNT = 103

#: 선언 개수의 **하한**. `TEST_CLASS_COUNT` 와 역할이 다르다 — 저쪽은 "목록과 개수가
#: 서로 맞는가"(두 수기 선언 사이의 일관성)이고, 이쪽은 "그 수가 **얼마 아래로는 내려갈 수
#: 없는가**"다. 게이트 27 codex C-5 가 지적한 것이 정확히 그 빈자리였다: 탐지기 파일을
#: 지우면서 목록과 개수를 **함께** 줄이면 모든 대조가 통과했다.
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
MIN_TEST_CLASSES = 99

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
    "kr.easydoc.api.PrivateResponseHeadersReachTest",
    # 2026-08-21 (리더, C4): F3(요청 다섯 필드에 Bean Validation 금지)을 지킨 것은 둘이었다 —
    #   `RequestFieldConstraintLayerTest` 의 애너테이션 부재 스캔과, 「`validation` 이
    #   클래스패스에 없어 **달 수조차 없다**」는 사실. C4 가 그 의존성을 들여 **두 번째를
    #   영구히 없앴고**, 같은 커밋의 음성 대조가 첫 번째의 구멍을 실측했다(`@Valid` +
    #   열거 밖 제약 → 스캔 초록). 그래서 이 파일이 사라지면 F3 의 결론이 함께 무너진다 —
    #   이 목록의 기준(「다른 판정의 근거로 인용되는 탐지기」)에 정면으로 든다.
    #   형제 `DocumentListHeaderFloorTest` 는 넣지 않았다: 그 레인이 범위를 「이 커밋이
    #   만든 한 자리」로 명시해 뒀고, 바닥에 넣는 것은 재지 않은 범위를 선점하는 편집이다.
    "kr.easydoc.api.RequestFieldRejectionLayerTest",
    "kr.easydoc.api.SensitiveToStringReachTest",
    "kr.easydoc.api.SourceScanFormsProbe",
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


def _kotlin_test_sources() -> list[Path]:
    """`backend-kotlin/**/src/test/**` 의 Kotlin 소스. 빌드 산출물은 뺀다."""
    return sorted(
        path
        for path in BACKEND_KOTLIN.rglob("*.kt")
        if "build" not in path.parts and "src" in path.parts and "test" in path.parts
    )


def _declared_package(path: Path) -> str | None:
    match = re.search(r"^package\s+([\w.]+)", path.read_text(encoding="utf-8"), re.MULTILINE)
    return match.group(1) if match else None


def _discovered_test_classes() -> dict[str, list[Path]]:
    """**종류로** 훑어 나온 테스트 클래스 → 그 이름을 선언한 파일들.

    선언 구간은 「이 최상위 선언부터 **다음 최상위 선언 직전까지**」다. 중괄호를 세지
    않는 이유는 문자열·주석 안의 괄호를 함께 세야 하고, 그 어휘 분석이 틀리면 구간이
    조용히 어긋나기 때문이다. 열 0 을 경계로 삼으면 그 위험이 없다 — 중첩 선언은
    들여쓰기되므로 바깥 구간에 남는다.

    FQCN 은 파일 안의 `package` 선언에서 만든다 — 경로에서 유추하면 경로와 패키지가
    어긋난 파일을 조용히 다른 이름으로 등록한다.
    """
    found: dict[str, list[Path]] = {}
    for path in _kotlin_test_sources():
        text = path.read_text(encoding="utf-8")
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
    executed: dict[str, int] = {}
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
    return executed, seen


def test_리포트가_선언한_클래스를_실제로_실행했다() -> None:
    """파일이 있는 것과 **돌았다**는 것은 다르다.

    **skip 하지 않는다.** 요구 모드가 꺼져 있어도 리포트에 실재하는 클래스는 여기서
    잰다 — 부분 리포트에서도 거짓이 없고, 가장 값싼 공격(`@Disabled` 로 끄기)이
    로컬에서도 즉시 빨개진다. 요구 모드가 켜지면 대상이 **선언 전건**으로 넓어지고,
    리포트 부재 자체가 실패가 된다.
    """
    executed, seen = _report_execution()
    require_all = bool(os.environ.get(REQUIRE_REPORT_ENV))

    if require_all:
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

    silent = sorted(fqcn for fqcn in targets if executed.get(fqcn, 0) <= 0)
    assert not silent, (
        f"선언한 테스트 클래스가 리포트에 **실행된** 기록이 없다: {silent}\n"
        f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}\n"
        "  파일은 있는데 아무도 돌리지 않았거나(`--tests` 필터·태그), 돌긴 했는데 전건이 "
        "skipped 다(`@Disabled`·`assumeTrue(false)`). 둘 다 게이트가 존재만 하고 도달이 0 이다."
    )


def test_리포트에_나온_클래스는_전부_선언에_있다() -> None:
    """**역방향 교차 검증** — 발견 파서가 조용히 놓친 클래스를 Gradle 이 신고한다.

    위 「선언 ↔ 발견」 대조는 발견 파서가 옳다는 전제 위에 선다. 파서가 어떤 클래스를
    못 보면 선언에서도 빠지고 대조는 초록이다 — 그것이 families 열거가 겪은 형태다.
    Gradle 리포트는 **파서와 독립한 관측**이므로 그 전제를 밖에서 되짚는다.

    리포트가 없으면 대상이 0건이다. 그 상태는 위 테스트의 요구 모드가 닫는다.
    """
    _, seen = _report_execution()
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


def _steps_running_this_file() -> list[tuple[str, dict[str, object]]]:
    """`ci.yml` 에서 이 파일을 **경로로 명시해 돌리는** 스텝을 (잡 이름, 스텝) 으로 낸다."""
    return [
        (job_name, step)
        for job_name, steps in _ci_jobs().items()
        for step in steps
        if THIS_TEST_PATH in str(step.get("run") or "")
    ]


def _require_report_flag(step: dict[str, object]) -> str:
    """스텝의 `env` 에서 요구 모드 값을 읽는다. `env` 가 없거나 매핑이 아니면 빈 문자열."""
    env = step.get("env")
    if not isinstance(env, dict):
        return ""
    return str(env.get(REQUIRE_REPORT_ENV) or "")


def test_CI_가_이_대조를_경로_명시로_배선했다() -> None:
    """**장치 밖에서 무언가 깨져야 한다** (SKILL.md 규칙 6).

    이 파일 안에만 단언을 두면 파일과 함께 사라진다. `ci.yml` 이 이 경로를 명시해
    돌리는지, 그리고 실행 대조가 **요구 모드**로 켜지는지를 여기서 되짚는다.
    두 스텝 중 하나라도 빠지면 대조가 반쪽이 된다.

    이 파일을 통째로 지웠을 때 깨지는 것도 그 두 스텝이다 — `uv run pytest <경로>` 는
    수집 대상이 없으면 exit 4 로 끝난다(게이트 26 음성 대조 K).
    """
    steps = _steps_running_this_file()

    assert steps, (
        f"ci.yml 이 {THIS_TEST_PATH} 를 경로로 명시해 돌리지 않는다 — `uv run pytest` "
        "전체 수집만 믿으면 파일이 지워졌을 때 수집 0 으로 조용히 통과한다."
    )

    requiring = [job for job, step in steps if _require_report_flag(step)]
    plain = [job for job, step in steps if not _require_report_flag(step)]

    # 실행 대조의 **전건 요구**를 켜는 스텝이 있어야 한다. 변수 이름이 주석에만 적혀
    # 있으면 전건 요구는 영원히 안 걸린다 — 그래서 `env` 매핑에서 값을 읽는다.
    assert requiring, (
        f"{REQUIRE_REPORT_ENV} 를 env 로 켜고 {THIS_TEST_PATH} 를 돌리는 스텝이 없다 — "
        "선언 전건에 대한 실행 요구가 어디서도 걸리지 않는다. kotlin 잡의 build 뒤에 배선하라."
    )
    # 그리고 값싼 잡에서도 한 번 돌아야 한다 — 테스트 파일 삭제는 Gradle 빌드를 기다리지
    # 않고 드러나는 편이 낫다.
    assert plain, (
        f"{THIS_TEST_PATH} 가 요구 모드 스텝에서만 돈다 — 선언 ↔ 트리 대조는 Gradle 없이도 "
        "되므로 quality 잡에도 경로 명시로 두어 삭제가 먼저 드러나게 하라."
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
