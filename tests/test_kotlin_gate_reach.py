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
import subprocess
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
TEST_CLASS_COUNT = 108

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
    #   형제 `DocumentListHeaderFloorTest` 는 넣지 않았다: 그 레인이 범위를 「이 커밋이
    #   만든 한 자리」로 명시해 뒀고, 바닥에 넣는 것은 재지 않은 범위를 선점하는 편집이다.
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
#: | **소스 축** | 소스의 `@Test` 애너테이션 수 | **선언이 사라지는 편집**(메서드 삭제). |
#: |  | | Gradle 없이 돌아 `quality` 잡에서도 잡는다 |
#: |  | | (덮지 못함: 선언은 남고 **안 도는** 편집) |
#: | **리포트 축** | Gradle JUnit XML 의 **실행된** 케이스 수 | **안 도는 편집 전부** — |
#: |  | | `@Disabled`·`assumeTrue`·태그 제외·`--tests` 필터·JVM 인자. |
#: |  | | 기제를 하나도 열거하지 않는다 |
#: |  | | (덮지 못함: 리포트가 없는 실행 경로 — `quality` 잡) |
#:
#: 소스 축은 `test_바닥_클래스의_테스트_개수가_하한_아래로_내려가지_않는다`,
#: 리포트 축은 `test_바닥_클래스가_리포트에서_하한만큼_실제로_돌았다` 다.
#:
#: **소스 축을 남긴 이유**가 마지막 칸이다 — `quality` 잡에는 Gradle 이 없어 리포트가 없고,
#: 그 잡에서도 「메서드가 사라졌다」는 잡혀야 한다.
#:
#: ## 잡지 못하는 것 (정직하게)
#:
#: **메서드 껍데기를 남기고 단언만 비우는 편집**은 두 축 모두 통과한다(개수도 그대로, 실행도
#: 된다). 그 자리를 덮는 진짜 답은 **변이 테스트(pitest)** 다 — 「이 가드를 변이시켰을 때
#: 죽이는 테스트가 어딘가에 있는가」를 물으므로 어느 클래스·메서드가 그것을 담는지 알 필요가
#: 없다. 도입은 범위 밖이고 개선 백로그 B-19 에 있다.
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
MIN_FLOOR_CLASSES = 28

MIN_TESTS_IN_FLOOR_CLASS: dict[str, int] = {
    "kr.easydoc.api.AuthenticationCoverageContractTest": 5,
    "kr.easydoc.api.ContractErrorBodyReachTest": 11,
    "kr.easydoc.api.DocumentBodyLogLeakReachTest": 1,
    "kr.easydoc.api.DocumentDeleteReachTest": 14,
    "kr.easydoc.api.DocumentListReachTest": 10,
    "kr.easydoc.api.NamedReferenceGuardTest": 12,
    "kr.easydoc.api.PrivateResponseHeadersReachTest": 7,
    "kr.easydoc.api.RequestFieldConstraintLayerTest": 7,
    "kr.easydoc.api.RequestFieldRejectionLayerTest": 5,
    "kr.easydoc.api.RequestFieldRejectionReachTest": 4,
    "kr.easydoc.api.SensitiveToStringReachTest": 5,
    "kr.easydoc.api.SourceScanFormsProbe": 5,
    "kr.easydoc.api.ValueSlotInvariantReachTest": 3,
    "kr.easydoc.api.WorkspaceEndpointReachTest": 22,
    "kr.easydoc.core.CoreModuleBoundaryTest": 1,
    "kr.easydoc.core.ParityDeclarationSyncTest": 4,
    "kr.easydoc.core.crypto.PlainBodyTest": 5,
    "kr.easydoc.core.privacy.MaskedTextGatewayTest": 4,
    "kr.easydoc.core.privacy.ProvenanceCreationSitesTest": 6,
    "kr.easydoc.infrastructure.crypto.AesGcmContentCipherTest": 22,
    "kr.easydoc.infrastructure.crypto.CryptoStartupVerificationTest": 11,
    "kr.easydoc.infrastructure.db.EnvelopeColumnWriteGuardTest": 9,
    "kr.easydoc.infrastructure.db.FlywayBaselineGuardTest": 10,
    "kr.easydoc.infrastructure.db.OwnershipPredicateGuardTest": 11,
    "kr.easydoc.infrastructure.db.StatementCountingPremiseTest": 4,
    "kr.easydoc.infrastructure.document.EnvelopeRotationConcurrencyTest": 3,
    "kr.easydoc.infrastructure.document.JdbcDocumentStoreTest": 22,
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

#: 표 형태의 라쳇 핀 — 값이 여럿이라 **키별로** 이력 최댓값과 대조한다.
RATCHET_TABLE_PIN: tuple[str, str] = (THIS_TEST_PATH, "MIN_TESTS_IN_FLOOR_CLASS")

#: **이 파일의 수치 상수 중 라쳇이 아닌 것** — 그 사유를 값과 함께 남긴다.
#:
#: `TEST_CLASS_COUNT` 는 `len(TEST_CLASSES)` 와 **정확 일치**로 비교되므로 값만 내리면
#: 즉시 빨개진다(실측: 1 내렸을 때 `test_테스트_클래스_선언이_비어_있지_않다` 가 지목).
#: 그래서 이력 대조가 필요 없다 — **자기 권위가 아니라 목록이 권위**인 상수다.
#: Kotlin 쪽 `EXPECTED_SOURCE_DECLARATIONS` 도 같은 성질이다(실측: 1 내리면 RED).
NON_RATCHET_PINS: dict[str, str] = {
    "TEST_CLASS_COUNT": "len(TEST_CLASSES) 와 정확 일치 — 값만 내리면 즉시 빨개진다",
}


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


def _declared_test_count(fqcn: str) -> int | None:
    """[fqcn] 선언 구간 안의 JUnit 테스트 애너테이션 수. 선언을 못 찾으면 `None`.

    구간 정의는 [_discovered_test_classes] 와 **같다**(열 0 의 최상위 선언 사이). 두 벌이
    되면 한쪽만 고쳐지는 날 서로 다른 것을 세면서 둘 다 초록이 된다.
    """
    paths = _discovered_test_classes().get(fqcn, [])
    if not paths:
        return None
    text = paths[0].read_text(encoding="utf-8")
    simple = fqcn.rsplit(".", 1)[1]
    marks = list(TOP_LEVEL_DECLARATION.finditer(text))
    for index, mark in enumerate(marks):
        if mark.group(1) != simple:
            continue
        end = marks[index + 1].start() if index + 1 < len(marks) else len(text)
        return len(TEST_ANNOTATION.findall(text[mark.start() : end]))
    return None



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
    것만 보되, **판정하지 못한 목록을 출력한다** — 조용히 건너뛰면 그 플래그가 은폐형이 된다.
    """
    executed, _, seen = _report_counts()
    require_all = bool(os.environ.get(REQUIRE_REPORT_ENV))
    floor = sorted(MIN_TESTS_IN_FLOOR_CLASS)

    if require_all:
        assert seen, (
            f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 Gradle 테스트 리포트가 없다.\n"
            f"  리포트 디렉터리: {[str(d) for d in _test_report_dirs()]}\n"
            "  이 스텝은 Kotlin 테스트 뒤에 돌아야 한다."
        )
        targets = floor
    else:
        targets = [fqcn for fqcn in floor if fqcn in seen]
        unjudged = [fqcn for fqcn in floor if fqcn not in seen]
        print(
            f"리포트 축 — 이번 리포트로 판정하지 못한 바닥 클래스 "
            f"{len(unjudged)}개: {unjudged or '없음'}"
        )

    # **요구 모드에서 분모가 비면 공허 통과다.** 바닥 목록이 비는 편집은 리더 핀이 막지만,
    # 이 축의 분모가 0 이 되는 경로를 여기서도 되짚는다.
    if require_all:
        assert targets, "요구 모드인데 판정 대상이 0 이다 — 바닥 개수표가 비었다."

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
    if not seen:
        # 리포트가 없으면 이 축은 판정하지 못한다. 요구 모드의 부재 판정은 위 케이스가 진다.
        print("건너뜀 대조 — Gradle 리포트가 없어 판정하지 못했다.")
        return

    assert not skipped, (
        "Gradle 리포트에 **건너뛴** 테스트가 있다:\n"
        + "\n".join(f"  - {k}: {v}건" for k, v in sorted(skipped.items()))
        + "\n  `@Disabled`·`assumeTrue`/`assumeFalse`·`@EnabledOnOs`·태그 제외가\n"
        "  전부 여기 나타난다.\n"
        "  강제자를 끄는 가장 값싼 편집이 한 줄이므로, 그 한 줄이 게이트에 보여야 한다."
    )


def _git_revisions(rel_path: str) -> list[str]:
    """`rel_path` 를 건드린 리비전들. 이력이 없으면 빈 목록."""
    result = subprocess.run(
        ["git", "rev-list", "HEAD", "--", rel_path],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
    )
    return result.stdout.split() if result.returncode == 0 else []


def _blob_at(rev: str, rel_path: str) -> str:
    """그 리비전의 파일 내용. 그 리비전에 파일이 없으면 빈 문자열."""
    result = subprocess.run(
        ["git", "show", f"{rev}:{rel_path}"],
        cwd=REPO_ROOT, capture_output=True, text=True, check=False,
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
    """이력이 없을 때의 처분. **조용히 건너뛰지 않는다.**"""
    assert not _require_mode(), (
        f"{REQUIRE_REPORT_ENV} 가 켜져 있는데 라쳇 이력 대조를 할 수 없다: {reason}\n"
        "  이 검사의 기준점은 **git 이력**이다. CI 의 checkout 에 `fetch-depth: 0` 이 없으면\n"
        "  기본값 1 로 이력이 없고, 그러면 이 축은 존재만 하고 도달이 0 이다."
    )
    print(f"라쳇 이력 대조 — 판정하지 못했다: {reason}")


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
        seen = [
            value
            for rev in _git_revisions(rel_path)
            if (value := _scalar_in(_blob_at(rev, rel_path), name)) is not None
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


def test_바닥_개수표의_값이_이력_최댓값_아래로_내려가지_않는다() -> None:
    """표 형태의 라쳇도 **키별로** 되짚는다 — 값 하나만 내리는 편집이 같은 한 줄이다."""
    reason = _history_unavailable()
    if reason is not None:
        _report_or_fail_history(reason)
        return

    rel_path, name = RATCHET_TABLE_PIN
    current = _table_in((REPO_ROOT / rel_path).read_text(encoding="utf-8"), name)
    assert current, f"{rel_path}::{name} 표가 비었다 — 이 대조는 아무것도 재지 않는다."

    history: dict[str, int] = {}
    for rev in _git_revisions(rel_path):
        for key, value in _table_in(_blob_at(rev, rel_path), name).items():
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


def test_이_파일의_수치_상수가_전부_분류돼_있다() -> None:
    """**정확 분할** — 라쳇으로 지키는 것과 그렇지 않은 것 중 하나여야 한다.

    새 수치 핀을 더하면서 분류를 빠뜨리면 그 상수는 다시 「값 자신이 권위인」 상태가 되고,
    그것이 이 축이 겨눈 결함이다. 사유 없이 비라쳇으로 두는 길도 막는다 —
    [NON_RATCHET_PINS] 는 값이 사유 문자열이다.
    """
    declared = {
        match.group(1)
        for match in re.finditer(
            r"^([A-Z_]+)(?:: [^=]+)? = \d+$",
            (REPO_ROOT / THIS_TEST_PATH).read_text(encoding="utf-8"),
            re.MULTILINE,
        )
    }
    assert declared, "이 파일에서 수치 상수를 하나도 찾지 못했다 — 이 대조는 아무것도 재지 않는다."

    ratcheted = {name for path, name in RATCHET_SCALAR_PINS if path == THIS_TEST_PATH}
    classified = ratcheted | set(NON_RATCHET_PINS)
    missing = sorted(declared - classified)
    stale = sorted(classified - declared - {RATCHET_TABLE_PIN[1]})

    assert not missing and not stale, (
        "이 파일의 수치 상수 분류가 어긋났다.\n"
        f"  분류되지 않은 상수(라쳇인지 아닌지 적히지 않았다): {missing or '없음'}\n"
        f"  분류에만 있고 파일에 없는 상수: {stale or '없음'}\n"
        "  새 수치 핀은 RATCHET_SCALAR_PINS 나 NON_RATCHET_PINS 중 하나에 **사유와 함께** 넣어라."
    )
    assert all(NON_RATCHET_PINS.values()), (
        "NON_RATCHET_PINS 의 사유가 빈 항목이 있다 — 사유 없는 면제는 이 저장소가 금지한 형태다."
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
