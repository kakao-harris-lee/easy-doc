# 게이트 25 · 1단계 codex 독립 리뷰 — `04_crypto`

> 어간 `04_crypto`는 **리더가 1단계 호출에서 지정한 값**을 그대로 썼다(`kotlin-migration` 스킬 `{scope}` 정본 표 기준). 2단계 `04_crypto_migration-reviewer.md`, 3단계 `04_crypto_cross.md`가 같은 어간을 공유해야 게이트가 닫힌다.

이 문서는 **codex 출력을 가공하지 않고 보존**한다. §3이 원문(무편집)이고, §4는 원문과 분리된 색인이다. 지적의 옳고 그름·심각도 재부여·중복 병합·오탐 판정은 이 문서에서 하지 않는다 — `migration-reviewer`의 2차 교차 종합과 리더의 몫이다.

**축 5개를 호출 2회로 나눴다.** `codex-review` 스킬 §3.5가 focus를 "한 번에 3~5개 축"으로 제한하는데, 리더가 지정한 5축은 각각 하위 항목이 5~8개로 밀도가 높아 한 번에 넣으면 전부 얕게 본다. 호출 A는 축 ①②③(crypto 단위 ⓒ), 호출 B는 축 ④⑤(하네스 ⓐ·계약 ⓑ)를 맡았다. 두 호출의 리뷰 대상 diff 범위는 `76f6863..9c7aa03`으로 동일하고 focus만 다르다.

---

## 1. 호출 메타데이터

| 항목 | 호출 A (축 ①②③) | 호출 B (축 ④⑤) |
|---|---|---|
| 실행 시각 | 2026-08-19 18:29 KST 착수 / 10m 34s 소요 | 2026-08-19 18:29 KST 착수 / 11m 28s 소요 |
| 모드 | `adversarial` (스킬 §3.2 — 저장 암호화는 `adversarial` 필수 영역) | `adversarial` |
| base / scope | `--base 76f6863` (scope 무시, branch diff) | `--base 76f6863` |
| **리뷰 대상 판정** (스크립트 stderr 원문) | `codex-review: 리뷰 대상 = branch diff vs 76f6863`<br>`codex-review: 대상 판정 = non-empty (merge-base=76f686387a86, 변경 파일 42개 (branch 모드는 커밋된 변경만 센다))` | 동일 (같은 base·같은 HEAD) |
| **스크립트 종료 코드** | **0** | **0** |
| verdict | `needs-attention` | `needs-attention` |
| job id | `review-mszw3pa6-lmrp5n` | `review-mszw3r0j-ct4b3n` |
| codex session id | `01a0195a-74aa-7c23-bc0b-2d3e00048753` | `01a0195a-7d7d-76c0-b9fd-2e2481cfc833` |
| 재조회 | `codex resume 01a0195a-74aa-7c23-bc0b-2d3e00048753` | `codex resume 01a0195a-7d7d-76c0-b9fd-2e2481cfc833` |
| codex가 실행한 셸 명령 수 | 62 (실패 3) | 85 (실패 4) |

공통:

- 헬퍼: `/Users/harris/.claude/plugins/cache/openai-codex/codex/1.0.6/scripts/codex-companion.mjs`
- 헬퍼 출처: `plugins cache (최신 버전 자동 선택)` — 버전 1.0.6
- HEAD: `9c7aa03fea6d66e9d0429a270120d2a79b44f6c7`, merge-base `76f686387a86`
- 실행 명령: `.claude/skills/codex-review/scripts/codex-review.sh adversarial --base 76f6863 "$(cat <focus 파일>)"`
- 실제 전개: `node <헬퍼> adversarial-review --base 76f6863 '<focus 전문>'`

**제공한 맥락** — 전사하지 않고 저장소 경로를 지목해 codex가 직접 읽게 했다(리더 지시).

| 파일 | 호출 A | 호출 B |
|---|---|---|
| `docs/migration/_workspace/04_kotlin-implementer_crypto.md` | ○ | |
| `.claude/skills/migration-safety-gate/SKILL.md` (I-7·I-8) | ○ | |
| `docs/plans/2026-08-11-kotlin-react-migration.md` §4.3 | ○ | |
| `docs/migration/_workspace/reviews/03_phase3-close_cross.md` | ○ | ○ |
| `.claude/skills/kotlin-migration/SKILL.md` "선언한 범위와 실제 도달을 대조한다" 절 | | ○ |
| `docs/migration/_workspace/04_contract-keeper_documents-test-spec.md` | | ○ |
| `docs/migration/_workspace/00_contract-keeper_changelog.md` | | ○ |
| `contracts/easy-doc-v1.yaml` | | ○ |

**민감 데이터**: 프롬프트에 사용자 문서 본문·실제 암호문·키·개인정보를 싣지 않았다. focus 전문은 §2에 그대로 있어 대조 가능하다.

---

## 2. 전달한 프롬프트 전문

### 2.1 호출 A (축 ①②③ — AEAD 정확성 / 스키마·마이그레이션 / 테스트 증명력)

```
이 저장소의 저장 암호화(AEAD) 신규 구현과 그 스키마·테스트를 심사한다. Python Fernet 과의 암호문 호환은 요구하지 않는다 — 2026-08-12 재개발 전환으로 롤백 대상이 사라졌다. 요구는 "표준 AEAD 로서 올바른가"다.

먼저 다음 파일을 저장소에서 직접 읽어라(요약본이 아니라 원본을 읽어야 한다):
- docs/migration/_workspace/04_kotlin-implementer_crypto.md — 구현 레인 산출물. 키 규약과 "음성 대조(negative control)" 주장 목록이 여기 있다
- .claude/skills/migration-safety-gate/SKILL.md — 불변식 정본. 특히 I-7(저장 암호화)·I-8(스키마)
- docs/plans/2026-08-11-kotlin-react-migration.md 의 4.3 절
- docs/migration/_workspace/reviews/03_phase3-close_cross.md — 직전 게이트의 미해결 항목

심사 대상 파일:
- backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent.kt
- backend-kotlin/core/src/main/kotlin/kr/easydoc/core/exceptions/DomainExceptions.kt
- backend-kotlin/application/src/main/kotlin/kr/easydoc/application/crypto/ContentCipher.kt
- backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipher.kt
- backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/CryptoConfiguration.kt
- backend-kotlin/api/src/main/kotlin/kr/easydoc/api/config/EasyDocProperties.kt
- backend-kotlin/api/src/main/resources/application.yml, backend-kotlin/worker/src/main/resources/application.yml
- backend-kotlin/infrastructure/src/main/resources/db/migration/V2__encryption_scheme.sql, V3__encryption_scheme_aead.sql
- backend-kotlin/infrastructure/src/testFixtures/kotlin/kr/easydoc/infrastructure/MigrationCatalog.kt
- 테스트: AesGcmContentCipherTest.kt, EncryptionSchemeSchemaTest.kt, FlywayBaselineGuardTest.kt, PythonSchemaBaselineTest.kt

세 축만 본다. 다른 축은 무시하라.

[축 1] AEAD 정확성. 다음이 지켜져야 한다 — 위반 경로를 파일·라인으로 지목하라.
(1) round-trip 이 어떤 입력에서도 깨지지 않는다(빈 바이트열, 1바이트, 매우 큰 입력, 비ASCII·한글 UTF-8, 널 바이트 포함).
(2) nonce 는 매 호출 암호학적 난수 12바이트이며, 같은 키로 nonce 가 재사용될 수 있는 경로가 0이다. AES-GCM 에서 nonce 가 겹치면 인증 키가 노출되고 평문이 복원된다. SecureRandom 인스턴스 생성·재사용 방식, 스레드 안전성, Cipher 객체 공유 여부, fork/컨테이너 복제 시 시드 문제까지 보라.
(3) 인증 태그는 128비트이며 잘린 태그를 허용하지 않는다.
(4) AAD 가 스킴·키버전·테이블.컬럼·행 UUID 를 결속하여, 다른 행·다른 컬럼·다른 key_version 의 암호문을 그 자리에 이식하면 복호화가 반드시 거부된다. AAD 문자열 구성에 구분자 혼동(delimiter injection)이 가능한 자리 — 예컨대 구성요소 안에 구분자와 같은 문자가 들어가 서로 다른 조합이 같은 AAD 로 접히는 경우 — 가 있는지 보라.
(5) 복호화 실패가 원인을 구분시키지 않는다. 길이 오류·태그 불일치·스킴 불일치·키 부재·AAD 불일치·base64 형식 오류가 모두 같은 예외 타입·같은 고정 메시지로 끝나야 하고, cause 체인·스택 트레이스·로그·응답 본문·처리 시간 어느 것으로도 구분되면 안 된다(복호화 oracle 금지). 조기 반환(early return)으로 특정 실패만 태그 검증 전에 빠져나가 타이밍이 갈리는 자리를 특히 보라.
(6) 키는 설정에서 오고 길이(256비트)가 검증되며, 키 바이트가 로그·예외 메시지·toString·스택·메트릭·설정 덤프(Spring actuator 포함)로 나가는 경로가 0이다.
(7) 키 회전: 구버전 암호문을 읽을 수 있고, 신규 쓰기는 항상 최신 버전을 쓰며, 알 수 없는 버전은 실패한다. 활성 키 선택이 "설정에 있는 것 중 가장 큰 버전"인지, 아니면 별도 지정인지 확인하고 그 규칙이 애매하거나 조용히 잘못된 키를 고를 수 있는지 보라.
(8) 평문을 담는 래퍼 타입이 toString·data class 자동 생성 메서드·직렬화·예외 메시지로 내용을 노출하지 않는다. equals/hashCode 가 평문에 대해 타이밍 노출을 만드는지도 보라.
깨지면 사용자 문서를 영구히 읽지 못하거나 변조된 암호문이 그대로 복호화된다.

[축 2] 스키마·마이그레이션. V3 는 encryption_scheme 컬럼에 CHECK 제약으로 'aes256gcm-v1' 만 허용하고 DEFAULT 를 제거했다. V2 는 주석만 정정됐고 그 결과 Flyway 체크섬이 바뀌었다.
(1) DEFAULT 제거가 실제로 "INSERT 경로가 스킴 값을 반드시 명시하게" 강제하는가? 컴파일 타임이나 테스트로 강제되는가, 아니면 런타임에 처음 실패하는가? 값을 빠뜨린 INSERT 가 통과할 수 있는 경로가 있는가?
(2) 이미 적용된 마이그레이션의 체크섬 변경은 기존 DB 에서 Flyway 검증 실패를 일으킨다. 이 저장소는 "보존해야 할 운영 DB 가 없다"를 전제로 V2 를 편집했다. FlywayBaselineGuardTest 와 MigrationCatalog 가 이 전제를 강제하는가, 아니면 전제가 깨져도 통과하는가? 이 방식이 첫 배포 이후에는 쓸 수 없다는 한계가 코드나 문서에 기록돼 있는가?
(3) key_version 의 SQL 타입(smallint)과 Kotlin 타입의 범위가 어긋나 조용한 절단·오버플로가 가능한가?
(4) conversions 테이블에는 암호문 컬럼이 여러 개인데 key_version 은 행당 하나다. 이 설계에서 한 행의 컬럼들이 서로 다른 키로 암호화된 상태가 될 수 있는 경로(부분 재암호화 중단, 예외, 트랜잭션 경계)가 있는가? 있다면 복호화가 어떻게 실패하는가?
(5) V3 의 "기존 행이 0건임을 확인하는 DO 블록"이 실제로 무엇을 보장하며, 행이 있을 때 어떻게 동작하는가?

[축 3] 테스트의 증명력. 구현 레인은 다음 "음성 대조"를 주장한다 — AAD 를 제거하면 3건 red, nonce 를 고정하면 1건 red, 예외 분기를 되살리면 7건 red, 401 응답시간 균일화를 제거하면 M-3b 가 red(관측 비 2.399), V3 를 삭제하면 3건 red.
(1) 각 주장이 코드에서 실제로 재현 가능한가? 그 장치를 떼면 정말 그 테스트가 실패하는가, 아니면 테스트가 다른 이유로 통과·실패하는가? 재현 불가능한 주장을 지목하라.
(2) 테스트가 실제 JCA 경로를 타는가, 아니면 mock·fake 로 대체돼 암호 동작을 검증하지 않는가?
(3) 키 회전 테스트가 실제로 서로 다른 키 2개를 쓰는가, 아니면 같은 키에 다른 라벨만 붙였는가?
(4) 테스트가 구현을 그대로 복사해 항상 통과하는 구조인가? 기대값이 구현 코드에서 유도되는 자리가 있는가?
(5) 이 변경이 깨뜨릴 수 있는 동작 중 어떤 테스트로도 덮이지 않은 것은 무엇인가? 특히 위 축1의 (1)~(8) 중 테스트가 없는 항목을 열거하라.
(6) 응답 시간 비(ratio) 로 타이밍 균일성을 검증하는 테스트가 CI 부하에 따라 위양성/위음성이 되는 구조인가? 임계값 1.5 가 무엇을 보장하고 무엇을 보장하지 못하는가?
```

### 2.2 호출 B (축 ④⑤ — 하네스 게이트 실제 도달 / 계약과 그 범위)

```
이 저장소의 검증 하네스(테스트 게이트·스캐너·CI)와 HTTP 계약 명세 변경을 심사한다. 하네스는 이 마이그레이션의 게이트 자체이므로, "동작하는 것처럼 보이면서 실제로는 아무것도 재지 않는" 구조가 최대 위험이다.

먼저 다음 파일을 저장소에서 직접 읽어라(요약본이 아니라 원본을 읽어야 한다):
- .claude/skills/kotlin-migration/SKILL.md 의 "선언한 범위와 실제 도달을 대조한다" 절 — 판정 규칙 정본
- docs/migration/_workspace/reviews/03_phase3-close_cross.md — 직전 게이트 결과와 미해결 항목
- docs/migration/_workspace/04_contract-keeper_documents-test-spec.md, docs/migration/_workspace/00_contract-keeper_changelog.md — 계약 레인 산출물
- contracts/easy-doc-v1.yaml — 계약 정본

심사 대상 파일:
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/SensitiveToStringReachTest.kt, SourceScanFormsProbe.kt, ContractHeaderDeclarationTest.kt, AuthEndpointReachTest.kt, WorkspaceEndpointReachTest.kt
- backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt, GeneratedToStringProbes.kt, ProductClasses.kt
- .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py, tests/test_privacy_scanner.py, tests/test_harness_scope_reach.py
- .github/workflows/ci.yml
- contracts/easy-doc-v1.yaml

두 축만 본다. 저장 암호화(AEAD) 구현 자체는 다른 리뷰가 맡으므로 여기서는 무시하라.

[축 4] 하네스 게이트의 실제 도달. 이번 변경은 다음을 했다고 주장한다 — 개인정보 스캐너의 403 토큰 집합에 HTTP_403_FORBIDDEN·SC_FORBIDDEN 을 명시 토큰으로 추가(xfail 소멸), toString 노출 탐지기를 소스 텍스트 스캔에서 Kotlin 반사 기반으로 전환, 소스 선언과 적재된 클래스의 대조를 JVM 바이너리 이름으로 수행하고 동일 FQCN 중복 선언을 실패로 처리, 계약 헤더 파서를 갈래별 fail-closed 로, CI 의 e2e 브라우저 설치를 apt 와 다운로드로 나누고 상한·캐시를 부여.
(1) 403 토큰을 명시 목록으로 추가한 방식이 오탐을 만들지 않는가? FORBIDDEN 이 들어간 다른 식별자(예: 다른 의미의 상수·주석·문자열)를 잘못 잡거나, 반대로 열거에 없는 동등 표현을 놓치는가? 열거 방식이 다음에 또 빈자리를 만드는 구조인가 — 구조로 잡는 대안이 있는가?
(2) toString 탐지기가 반사로 바뀌면서 실제로 도달 범위가 넓어졌는가 좁아졌는가? Kotlin value class 의 1번 파라미터가 인라인되는 경우, data class 가 아닌 클래스, 상속받은 toString, 컴패니언·중첩·익명 클래스, 컴파일러가 합성한 메서드를 잡는가? 반사가 클래스를 적재하지 못해 조용히 건너뛰는 경로가 있는가?
(3) 소스 선언과 적재 클래스를 대조하는 검사에서, 소스 파서가 중첩 클래스·주석·문자열 리터럴·멀티라인 선언을 잘못 읽어 대조가 헐거워지는 자리가 있는가? "중복 선언을 실패로 본다"가 실제로 어떤 경우를 잡고 어떤 경우를 놓치는가?
(4) 계약 헤더 파서의 fail-closed 가 모든 갈래에서 실제로 닫히는가? 파싱할 수 없는 형태를 만났을 때 조용히 통과하는 갈래가 남았는가? 헤더 선언이 인라인으로 남은 항목과 참조로 바뀐 항목이 서로 다른 코드 경로를 타면서 한쪽만 검사되는가?
(5) 이 게이트들은 어디서 도는가? CI 워크플로 파일에서 각 검사의 실제 실행 경로를 짚어라. 로컬에서만 돌고 CI 에 배선되지 않은 검사, 마커·태그로 제외되는 테스트, 크롤링 범위에서 빠지는 디렉터리가 있는가?
(6) CI 의 e2e 설치 단계가 apt 갈래와 다운로드 갈래로 나뉘고 캐시가 붙었다. 캐시 적중 시와 미적중 시 두 갈래가 모두 실제로 관측·검증되는가? 캐시 키가 잘못돼 낡은 브라우저를 쓰거나, 상한(timeout)에 걸렸을 때 실패가 아니라 조용한 통과로 끝나는 경로가 있는가?
(7) 이 장치들 각각을 제거하면 정확히 무엇이 깨지는가? 떼어도 아무 테스트가 깨지지 않는 장치를 지목하라.
(8) 성공/실패 판정이 대리 지표로 이뤄지는 자리 — 종료 코드 0 을 "검사했다"로, 테스트 통과를 "그 경로가 돌았다"로, 지적 0건을 "문제 없음"으로 바꿔 읽는 자리 — 를 찾아라.

[축 5] 계약 명세와 그 범위. 이번 변경은 contracts/easy-doc-v1.yaml 에 파일명 문자 집합 조항(x-filename-charset)을 신설하고, 일부 헤더 선언을 인라인으로 유지하기로 판정했으며, 기존 항목 하나(X-A2)를 정정했다.
(1) 새 조항이 선언한 문자 집합·금지 집합이 실제 코드(내보내기 파일명 생성 상수)와 일치하는가? 두 곳의 값을 직접 대조해 어긋나는 문자를 지목하라. 어긋나면 계약이 구현을 표현하지 못한다.
(2) 계약 파일이 "모든 응답"·"전역"·"항상" 같은 전칭 선언을 하는 자리가 있는가? 그 선언을 검증하는 수단이 실제로 모든 경로에 닿는가, 아니면 열거된 일부만 검사하는가? 이번 변경으로 새로 들어온 전칭 선언을 지목하라.
(3) 검사 대상에서 빼는 억제·면제·무시 패턴이 이번 변경에 들어왔는가? 근거가 되는 사례보다 넓은 범위를 덮어, 앞으로 같은 결함이 보이지 않게 되는 자리가 있는가?
(4) 계약 명세가 대조하는 기준이 검사 대상 자신에게서 나오지 않는가? 계약이 코드에서 생성되고 그 코드와 대조되는 순환, 또는 테스트 스펙 문서가 구현을 복사한 자리가 있는가?
(5) 헤더 선언을 일부만 인라인으로 남긴 판정에 일관된 기준이 있는가, 아니면 자리마다 다른가? 기준이 없으면 다음 항목이 어느 쪽으로 갈지 알 수 없다.
(6) 새로 작성된 테스트 명세(77 케이스)가 계약 조항 전부를 덮는가? 계약에는 있는데 명세에 대응 케이스가 없는 조항, 반대로 명세에만 있고 계약에 근거가 없는 케이스를 열거하라.
```

---

## 3. codex 원문 출력 (무편집)

> 아래 두 구획은 codex 출력을 **한 글자도 고치지 않고** 옮긴 것이다. 오탈자·경로 표기·라인 번호·판단 모두 원문 그대로다. Claude가 쓴 문장은 이 구획에 한 줄도 없다.

### 3.1 호출 A 원문 — job `review-mszw3pa6-lmrp5n`

```
# Codex Adversarial Review

Target: branch diff against 76f6863
Verdict: needs-attention

출하 불가. 실제 왕복 손실·평문 직렬화·복호화 타이밍 oracle을 재현했고, 행 단위 key_version 설계는 정상 키 회전과 부분 갱신을 양립시키지 못한다. 마이그레이션 불변성과 401 음성 대조도 CI 증명력이 부족하다.

Findings:
- [high] 문자열 변환 때문에 round-trip이 비가역적으로 깨진다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipher.kt:117-140)
  암호 계층이 임의 바이트가 아니라 String만 받고 UTF-8로 암호화한 뒤 다시 String으로 만든다. 실제로 `x\uD800y`를 왕복시키면 `x?y`로 바뀌었다. 따라서 표준 AEAD 바이트 round-trip을 제공하지 못하며, 파서가 비정상 UTF-16 코드 단위를 만든 경우 인증에는 성공하면서 사용자 문서가 영구 손상된다. 현재 표본에는 1바이트·본문 NUL·이 반례가 없다.
  Recommendation: 암호 포트를 ByteArray 기반으로 바꾸고 방어적 복사를 사용하라. 텍스트만 허용한다면 UTF-8 CharsetEncoder를 REPORT 모드로 두어 쓰기 전에 거부하고, 빈 값·1바이트·NUL·다중 MB·비정상 surrogate 회귀를 추가하라.
- [high] PlainBody가 Jackson을 통해 평문 전체를 직렬화한다 (backend-kotlin/core/src/main/kotlin/kr/easydoc/core/crypto/StoredContent.kt:36-39)
  toString만 가렸지만 public `value` 프로퍼티는 직렬화에 그대로 노출된다. 저장소의 Jackson Kotlin 모듈로 직접 확인한 결과 `PlainBody("PII-900101-1234567")`가 JSON 문자열 `"PII-900101-1234567"`로 출력됐다. 향후 복호화 결과가 응답 DTO나 구조화 로그에 들어가면 래퍼가 보호 장치가 되지 못하고 본문 전체가 유출된다.
  Recommendation: PlainBody를 웹 직렬화에서 fail-closed로 차단하고, API 응답 타입이 이를 포함하지 못하도록 경계 테스트를 추가하라. 값 접근도 명시적인 제한 API로 좁히고 Jackson Kotlin 모듈을 사용한 음성 테스트를 고정하라.
- [high] 복호화 조기 분기가 측정 가능한 oracle을 만든다 (backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/AesGcmContentCipher.kt:126-144)
  미지원 scheme·키 부재·길이 부족은 JCA 태그 검증 전에 즉시 반환하지만 bad tag/AAD는 Cipher 초기화와 doFinal까지 수행한다. 타입·메시지·cause가 같아도 처리량이 다르다. 직접 5,000회 측정한 중앙값은 미지원 scheme 1,250ns, bad tag 8,125ns로 약 6.5배였다. 현재 테스트는 타입·문구·cause만 비교하므로 이 위반을 초록으로 통과시킨다.
  Recommendation: 알 수 없는 scheme/key와 짧은 입력에도 dummy key와 정규화된 입력으로 동일한 GCM 검증 작업을 수행한 뒤 하나의 실패로 귀결하라. 각 실패 갈래의 시간 분포를 비교하고 조기 반환 복원 시 실패하는 음성 대조를 추가하라.
- [high] 행 단위 key_version은 부분 갱신과 키 회전을 양립시키지 못한다 (backend-kotlin/application/src/main/kotlin/kr/easydoc/application/crypto/ContentCipher.kt:41-62)
  conversions의 세 암호문은 key_version 하나를 공유하지만 encrypt는 기존 행 세대를 받지 않고 항상 전역 writeKeyVersion만 사용한다. v1로 초안·마스킹 값을 쓴 뒤 v2 회전 후 검수본만 저장하면, 행 버전을 v2로 바꿀 때 기존 두 컬럼이 열리지 않고 v1로 유지하면 신규 v2 암호문이 열리지 않는다. 부분 재암호화 실패나 검수 저장은 사용자 데이터를 영구적으로 읽지 못하게 만들 수 있으며 이를 검증하는 저장·트랜잭션 테스트도 없다.
  Recommendation: 컬럼별 scheme/key_version을 저장하거나, 기존 행 세대로 암호화하는 API와 행 전체 원자적 재암호화 중 하나를 확정하라. 중간 예외를 주입해 세 컬럼과 메타데이터가 함께 롤백되는 PostgreSQL 통합 테스트를 추가하라.
- [medium] 테스트 카탈로그가 적용된 마이그레이션의 체크섬 불변성을 전혀 고정하지 않는다 (backend-kotlin/infrastructure/src/testFixtures/kotlin/kr/easydoc/infrastructure/MigrationCatalog.kt:39-55)
  MigrationCatalog는 현재 디렉터리의 파일명과 연속 번호만 읽는다. V2 내용을 다시 편집해도 버전 목록은 같고, 모든 테스트 DB는 편집된 현재 V2로 새로 만들어지므로 CI는 통과한다. 실제로 구 V2가 적용된 DB만 Flyway checksum mismatch로 기동 불능이 된다. 이번 편집은 '보존 DB 없음'이라는 외부 전제에 의존하며, 첫 배포 후 같은 행위를 막는 코드 게이트는 없다.
  Recommendation: 첫 배포 기준의 마이그레이션 체크섬 manifest를 커밋하고 이후 기존 Vn 파일 변경을 CI에서 거부하라. 구 V2 checksum이 기록된 flyway_schema_history 업그레이드 fixture도 추가해 이번 일회성 경계를 명시적으로 고정하라.
- [medium] M-3b 비율 테스트는 타이밍 균일성이나 음성 대조 재현성을 증명하지 못한다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/AuthEndpointReachTest.kt:421-443)
  판정은 세 중앙값의 max/min이 1.5 이하인지 한 번 확인할 뿐 통계적 분리 가능성·절대 격차·신뢰구간을 보지 않는다. 1.49배 차이도 통과하며 반복 요청으로는 충분히 구분될 수 있고, 공통 HTTP/DB 지연은 실제 분기 비용을 가려 위음성을 만든다. 반대로 CI의 일시적 부하는 위양성을 만들 수 있다. 따라서 균일화 제거 시 관측한 2.399는 특정 실행 결과이지 상설로 재현되는 음성 대조가 아니다.
  Recommendation: 일반 CI에는 결정적인 동일-work 단언과 지연 변이 음성 테스트를 두고, 타이밍 검사는 격리 환경에서 다중 실행·절대 격차·분포 또는 신뢰구간을 함께 판정하라.

Next steps:
- 네 high 항목을 수정한 뒤 실제 PostgreSQL 저장·부분 실패·키 회전 경로까지 재감사한다.
- A/B/C/D/E 음성 대조를 재현 가능한 패치나 mutation task로 저장소에 고정한다.
- 쓰기 가능한 CI에서 지정 Gradle 테스트와 전체 Kotlin 빌드를 다시 실행한다.
```

### 3.2 호출 B 원문 — job `review-mszw3r0j-ct4b3n`

```
# Codex Adversarial Review

Target: branch diff against 76f6863
Verdict: needs-attention

출하 금지. 파일명 문자 집합 자체와 구현 정규식은 일치하고 e2e 캐시 양쪽 성공 기록도 있지만, 새 계약 절은 의미 검사가 없고 Kotlin 게이트는 삭제돼도 CI가 통과한다. toString·헤더·403 검사는 여전히 조용한 누락 또는 오탐 경로를 가진다.

Findings:
- [high] 새 파일명 계약 절을 실행 코드가 전혀 검증하지 않는다 (contracts/easy-doc-v1.yaml:1238-1271)
  `x-filename-charset`의 정규식은 현재 `Export.kt`와 정확히 같지만, 실행 테스트나 `ContractSpec`이 `forbidden`·`measured_on`을 읽지 않는다. CE-5·CE-6·P-23·N-20은 문서에만 있는 미래 항목이다. 따라서 이 절을 삭제하거나 C1 범위를 훼손해도 현재 CI는 YAML 구문만 읽고 통과하며, 계약을 구현과 대조한다는 설명이 성립하지 않는다.
  Recommendation: 이 변경 단위에 계약에서 금지 집합을 읽어 HTTP `filename*` 디코딩 결과를 검사하는 CE-5·CE-6과 C1 단독 변이 N-20을 구현하고 `ci:kotlin`에서 실행하라.
- [high] 새 Kotlin 게이트 파일을 삭제해도 CI가 실패하지 않는다 (.github/workflows/ci.yml:242-268)
  CI는 `./gradlew build`만으로 새 API 테스트를 포괄 수집한다. 바로 아래 주석도 전체 수집은 테스트 파일 삭제 시 그대로 녹색임을 인정하지만, 별도 `--tests` 존재 확인은 기존 core 탐지기 두 개에만 있다. `SensitiveToStringReachTest`나 `ContractHeaderDeclarationTest`를 삭제하면 테스트 수만 줄고 외부 장치가 실패하지 않아, 게이트 자체 제거를 검출하지 못한다.
  Recommendation: 새 게이트 클래스를 각각 독립 `--tests` 단계로 선택 실행하거나, 외부 manifest가 필수 게이트 클래스와 테스트 수를 검증하게 하라.
- [high] 민감 이름 목록 밖의 String 필드는 toString 검사에서 조용히 제외된다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/GeneratedToStringProbes.kt:84-99)
  후보는 텍스트 필드가 있어도 이름이 고정 토큰 목록에 들거나 클래스에 `@UserContent`가 있을 때만 생성된다. 따라서 문서가 직접 예로 든 `data class ExportEnvelope(payload: String)` 같은 새 DTO는 기본 `toString()`으로 값을 그대로 출력해도 `planted`가 비어 검사 대상에서 사라진다. 소스↔클래스 대조는 클래스 존재만 확인하므로 이 누락을 보완하지 않는다.
  Recommendation: 텍스트를 담을 수 있는 모든 data class를 기본 민감 대상으로 삼고, 제외가 필요하면 근거가 검증되는 명시적 `@NonSensitive` 분류를 요구하라.
- [high] 소스 대조 파서가 유효 선언 세 형태를 아예 세지 않는다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ProductClasses.kt:49-58)
  파서는 멀티라인 애너테이션 뒤 같은 줄 선언, 줄 중간 선언, use-site 애너테이션 선언을 조용히 놓친다고 명시한다. `SourceScanFormsProbe`도 이를 실패가 아니라 `emptyList()`로 고정한다. 특히 런타임 클래스패스에 없는 worker 모듈에서 이런 선언을 쓰면 선언 집합과 적재 집합 양쪽에서 사라져 중복·미적재 대조가 모두 녹색일 수 있다.
  Recommendation: Kotlin compiler/PSI 또는 class 산출물 기반으로 선언을 수집하라. 임시로는 비코드 제거 후 모든 `data/value class` 토큰 수와 파싱 결과를 대조해 지원하지 않는 형태를 fail-closed로 중단하라.
- [high] 77개 명세가 저장 순서·멱등성·정렬 계약을 검증하지 않는다 (docs/migration/_workspace/04_contract-keeper_documents-test-spec.md:73-95)
  계약은 작업 공간 생략 시 가장 먼저 만든 공간 선택, 소유권 확인 전 저장 금지, 큐 작업 ID를 conversion ID로 고정한 멱등성, 문서 최신순을 선언한다. 그러나 DC-1은 선택된 공간을, DC-16은 거절 후 DB 무변화를, DC-18은 작업 ID와 재시도 중복을 단언하지 않고 DL 케이스에도 최신순 단언이 없다. 잘못된 공간에 남는 문서, 중복 큐 작업, 역순 목록이 모두 명세를 통과할 수 있다.
  Recommendation: 두 작업 공간을 만든 기본 선택 사례, 타인 공간 404 뒤 문서·변환 수 불변, 동일 conversion ID 재시도 시 큐 등록 1건, 생성 시각 역순 목록 사례를 C-I 테스트로 추가하라.
- [medium] 헤더 파서의 응답 참조 해석은 한 단계 뒤 다시 조용히 열린다 (backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ContractSpec.kt:246-263)
  `resolveResponse`는 응답 `$ref`를 정확히 한 번만 따라가며, 반환된 컴포넌트가 다시 `$ref`이면 `collectHeaders`가 `headers` 부재로 즉시 반환한다. OpenAPI 3.1의 참조 연쇄가 추가되면 해당 헤더 전체가 사라져도 기존 다섯 이름의 하한선이 남아 테스트가 통과한다. 이는 모든 갈래가 fail-closed라는 선언과 다르다.
  Recommendation: 로컬 `$ref`를 재귀적으로 해석하되 순환과 잘못된 section을 실패시키고, 연쇄 response ref 및 Path Item ref 변이 테스트를 추가하라.
- [medium] 403 명시 토큰은 문자열과 후행 주석까지 차단한다 (.claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py:1523-1534)
  스캐너는 어휘 분석 없이 원시 줄에 토큰 정규식을 적용하고 줄 전체가 주석일 때만 건너뛴다. 따라서 `val x = "HTTP_403_FORBIDDEN"`이나 `val x = 1 // SC_FORBIDDEN 설명`도 억제 불가능한 `OWNERSHIP-403` BLOCK이 된다. 반면 토큰 목록 밖의 동등 상태 표현은 구조적으로 유도되지 않는다. 추가된 P8·P9는 긴 식별자만 검사해 이 오탐을 다루지 않는다.
  Recommendation: 언어별 문자열·주석을 제거한 코드 토큰이나 실제 응답 생성 호출의 상태 인자를 검사하고, 문자열·후행 주석 음성 사례와 별칭·계산 상태 양성 사례를 회귀에 추가하라.

Next steps:
- 파일명 계약 소비자와 음성 변이를 현재 변경 단위에 배선한다.
- 새 Kotlin 게이트마다 독립 CI 존재 확인을 추가한다.
- toString 분류와 소스 선언 수집을 fail-closed 구조로 바꾼다.
- 누락된 상태 변화·멱등성 계약 사례를 77개 명세에 보강한다.
```

---

## 4. 정리(가공)

> **이 구획은 Claude가 만든 색인이다.** 원문(§3)과 분리돼 있으며, 여기서도 옳고 그름·오탐 여부·심각도 재부여·중복 병합은 하지 않는다. 심각도 라벨은 **codex가 붙인 `[high]`/`[medium]`을 그대로 옮긴 것**이고, `codex-review` 스킬 §5의 Critical/Major 척도로 환산하지 않았다 — 환산은 2차 교차 종합의 몫이다.

### 4.1 지적 색인 (총 13건 — high 9 / medium 4)

| # | 호출 | codex 심각도 | 지적 요지 (원문 제목) | 근거 위치 (codex 표기 그대로) | 리더 축 |
|---|---|---|---|---|---|
| A-1 | A | high | 문자열 변환 때문에 round-trip이 비가역적으로 깨진다 | `AesGcmContentCipher.kt:117-140` | ① |
| A-2 | A | high | PlainBody가 Jackson을 통해 평문 전체를 직렬화한다 | `StoredContent.kt:36-39` | ① |
| A-3 | A | high | 복호화 조기 분기가 측정 가능한 oracle을 만든다 | `AesGcmContentCipher.kt:126-144` | ① |
| A-4 | A | high | 행 단위 key_version은 부분 갱신과 키 회전을 양립시키지 못한다 | `ContentCipher.kt:41-62` | ①② |
| A-5 | A | medium | 테스트 카탈로그가 적용된 마이그레이션의 체크섬 불변성을 전혀 고정하지 않는다 | `MigrationCatalog.kt:39-55` | ② |
| A-6 | A | medium | M-3b 비율 테스트는 타이밍 균일성이나 음성 대조 재현성을 증명하지 못한다 | `AuthEndpointReachTest.kt:421-443` | ③ |
| B-1 | B | high | 새 파일명 계약 절을 실행 코드가 전혀 검증하지 않는다 | `contracts/easy-doc-v1.yaml:1238-1271` | ⑤ |
| B-2 | B | high | 새 Kotlin 게이트 파일을 삭제해도 CI가 실패하지 않는다 | `.github/workflows/ci.yml:242-268` | ④ |
| B-3 | B | high | 민감 이름 목록 밖의 String 필드는 toString 검사에서 조용히 제외된다 | `GeneratedToStringProbes.kt:84-99` | ④ |
| B-4 | B | high | 소스 대조 파서가 유효 선언 세 형태를 아예 세지 않는다 | `ProductClasses.kt:49-58` | ④ |
| B-5 | B | high | 77개 명세가 저장 순서·멱등성·정렬 계약을 검증하지 않는다 | `04_contract-keeper_documents-test-spec.md:73-95` | ⑤ |
| B-6 | B | medium | 헤더 파서의 응답 참조 해석은 한 단계 뒤 다시 조용히 열린다 | `ContractSpec.kt:246-263` | ④ |
| B-7 | B | medium | 403 명시 토큰은 문자열과 후행 주석까지 차단한다 | `scan_privacy_invariants.py:1523-1534` | ④ |

### 4.2 codex가 **실행으로 재현했다고 기술한** 항목

원문에 실측값·실행 결과가 명시된 것만 옮긴다. 재현 성공 여부의 **판정은 하지 않는다** — codex의 서술과, 헬퍼 로그에 남은 명령 실행 흔적을 병기한다.

| # | codex가 기술한 실측 | 헬퍼 로그의 대응 실행 흔적 |
|---|---|---|
| A-1 | "실제로 `x\uD800y`를 왕복시키면 `x?y`로 바뀌었다" | `kotlin -e 'val s="x\uD800y"; val b=s.toByteArray(Charsets.UTF_8); val r=Stri...'` (exit 0) |
| A-2 | "저장소의 Jackson Kotlin 모듈로 직접 확인한 결과 `PlainBody("PII-900101-1234567")`가 JSON 문자열 `"PII-900101-1234567"`로 출력됐다" | `JACKSON_DB=$(find ~/.gradle/caches/modules-2/files-2.1/com.fasterxm...` — 1회 exit 1 후 재시도 실행 |
| A-3 | "직접 5,000회 측정한 중앙값은 미지원 scheme 1,250ns, bad tag 8,125ns로 약 6.5배였다" | `CORE_CLASSES=.../backend-kotlin/core/bui...` 계열 (1회 exit 1, 이후 exit 0) |
| A-2/A-4 보조 | (클래스 시그니처 확인) | `javap -classpath backend-kotlin/core/build/classes/kotlin/main -p kr.easydoc.co...` (exit 0) |
| B-7 | 스캐너 토큰 정규식을 문자열·후행 주석 사례에 적용 | `python3 -c 'import re; token=r"(?:403\|HTTP_403_FORBIDDEN\|SC_FORBIDDEN\|FORBIDDE...'` (exit 0) |
| B-5 | 77 케이스 명세 파싱·집계 | `python3 -c 'import re,pathlib,collections; p=pathlib.Path("docs/migration/_wor...'` (exit 0) |

codex 스스로 **재현되지 않는다고 기술한** 항목: A-6 — 구현 레인이 주장한 "균일화 제거 시 2.399"에 대해 원문은 "특정 실행 결과이지 상설로 재현되는 음성 대조가 아니다"라고 적었다.

### 4.3 전제 확인 필요

codex 서술이 사실과 다른 전제에 서 있는지 **이 문서에서는 판정하지 않는다.** 아래는 2차 종합이 확인해야 할 자리만 표시한 것이며, 원문은 §3에서 손대지 않았다.

- **A-3의 타이밍 실측 환경** — 원문은 5,000회 측정 중앙값 1,250ns / 8,125ns를 제시한다. 이 측정이 어떤 하네스로 이뤄졌는지 원문에 명시되지 않았다. 측정 코드·JIT 워밍업 조건 확인 필요.
- **A-1의 `x?y` 관측** — `kotlin -e` 한 줄 실행 결과이며, 실제 `AesGcmContentCipher` 경로를 태운 것인지 문자열 왕복만 확인한 것인지 원문만으로는 갈리지 않는다.
- **A-4의 "세 암호문"과 컬럼 구성** — 리더 지시에 따르면 이 지점은 이미 **구현 레인이 갈렸고 리더가 "행 단위 재암호화 ②"로 판정**한 자리다. codex는 그 판정을 모르는 상태에서 독립적으로 같은 지점을 지적했다. 판정과의 정합은 2차 종합에서 다룰 사항.
- **B-2의 `.github/workflows/ci.yml:242-268`** — codex는 "바로 아래 주석도 전체 수집은 테스트 파일 삭제 시 그대로 녹색임을 인정한다"고 기술한다. 그 주석의 실제 문언 확인 필요.
- **B-3의 `data class ExportEnvelope(payload: String)`** — 원문은 "문서가 직접 예로 든"이라고 하는데, 어느 문서의 예시인지 명시되지 않았다. 실재 DTO가 아니라 예시일 가능성 확인 필요.
- **B-4의 worker 모듈 경로** — "런타임 클래스패스에 없는 worker 모듈"이라는 전제의 사실 여부 확인 필요.

### 4.4 리더가 지정한 축 중 codex가 지적을 내지 않은 항목

**Claude가 대신 지적을 만들어 채우지 않는다**(`codex-review` 스킬 §7). 아래는 "codex가 이 자리에서 지적을 내지 않았다"는 사실의 기록이며, 문제가 없다는 뜻이 아니다.

- 축 ① 중 **nonce 난수원·길이·재사용 금지, 태그 길이(128비트), AAD 바꿔치기 거부** — 별도 지적 없음. (다만 A-3이 같은 파일의 복호화 분기를 다룬다)
- 축 ① 중 **키 설정 출처·길이 검증·키 바이트 로그 0** — 별도 지적 없음.
- 축 ② 중 **V3 DEFAULT 제거의 INSERT 강제, `key_version smallint` vs Kotlin `Int` 범위, V3의 행 0 DO 블록** — 별도 지적 없음.
- 축 ③ 중 **실 JCA 경로 여부(모킹 0), 키 회전 테스트의 키 2개 실재** — 별도 지적 없음.
- 축 ④ 중 **403 명시 토큰 추가 후 `FORBIDDEN_*` 경계 유지, toString 반사 전환이 value class 1번 파라미터(`MaskingResult`) 실례를 잡는지, 바이너리 이름 중복 선언 실패·파서 중첩 정정, CI e2e 상한·캐시 두 갈래** — B-7이 403 토큰의 **오탐** 방향을 다루고 B-4가 파서 형태 누락을 다루나, 리더가 지정한 위 하위 항목 각각에 대응하는 지적은 없다. e2e 캐시에 대해서는 원문 요약이 "e2e 캐시 양쪽 성공 기록도 있지만"이라고만 적었다.
- 축 ⑤ 중 **`x-filename-charset`이 `Export.kt` 상수와 동일한가(실측)** — codex는 B-1에서 "정규식은 현재 `Export.kt`와 정확히 같지만"이라고 **일치를 확인**한 뒤, 그것을 검증하는 실행 코드가 없다는 쪽으로 지적을 돌렸다.
- 축 ⑤ 중 **인라인 유지 판정의 G 근거, X-A2 정정, 새 "전역/모든/항상"·은폐형** — 별도 지적 없음.

---

## 5. 미실행·실패 항목

- **codex의 Gradle 테스트 실행이 실패했다.** 호출 A에서 `./gradlew :infrastructure:test --tests 'kr.easydoc.infrastructure.crypto.AesGcm...'`가 **exit 1**로 끝났다. 이어 codex는 `command -v gradle || true; ls -l ~/.gradle/wrapper/dists/gradle-9.1....`로 환경을 조회했고, 최종 출력의 Next steps에 **"쓰기 가능한 CI에서 지정 Gradle 테스트와 전체 Kotlin 빌드를 다시 실행한다"**를 남겼다. 따라서 **§3.1의 지적은 Kotlin 테스트 스위트를 실제로 돌린 결과가 아니다** — 정적 읽기와 개별 `kotlin -e`/`javap`/classpath 실행에 근거한다. 리더가 지정한 축 ③의 "음성 대조 재현(3 red / 1 red / 7 red / M-3b red / 3 red)"은 **codex가 스위트를 돌려 확인한 것이 아니다.**
- 호출 A에서 셸 명령 3건, 호출 B에서 4건이 non-zero로 끝났다. 대부분 재시도로 이어졌으나(§4.2), 각 실패의 원인은 헬퍼 로그가 명령을 잘라 기록해 원문만으로는 확정되지 않는다.
- **codex 출력 잘림 없음.** 두 호출 모두 `Findings:` 목록과 `Next steps:`가 온전히 끝났고 스크립트 종료 코드 0이다.
- **⚠ codex 리뷰 누락 없음** — 두 호출 모두 정상 수행됐다. 재시도는 필요하지 않았다.
- **범위 밖으로 두고 손대지 않은 것**: `00_progress.md` 무접촉, 커밋 없음, `migration-reviewer`·`privacy-gate` 산출물 미생성.
