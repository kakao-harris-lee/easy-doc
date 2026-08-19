# 게이트 25 (`04_crypto`) — Claude 독립 리뷰 (1차)

**작성:** migration-reviewer / **일자:** 2026-08-19
**회차:** **1차 — 독립 리뷰.** codex 산출물은 이 회차의 입력이 아니다(리더가 병렬로 띄운다).
codex 부재는 이 회차의 정상 상태이며 실패로 기록하지 않는다. 교차 대조표는 2차(`04_crypto_cross.md`)에서 만든다.

**대상 범위:** `76f6863..9c7aa03` (17커밋) — ⓐ Phase 3 조건 처리 10커밋 · ⓑ 계약 `765a377` · ⓒ crypto 5커밋

**참조 계획 문서 절:** §2.2(계약) · §2.3(보안 불변식) · §3.1·§3.2(모듈·기술 고정) · §4.2(Flyway) · §4.3(2차 개정) ·
§4.5·§4.6(parity 위험) · §5 Phase 4(`:411-420`) · §5 Phase 7(즉시 중단 기준) · §6(게이트)
**암호 정본:** `migration-safety-gate` **I-7**(AEAD) · **I-8**(scheme/key_version)

**입력 산출물:** `04_kotlin-implementer_crypto.md`(구현자 §1~§8) · `04_contract-keeper_documents-test-spec.md` ·
`00_contract-keeper_changelog.md` · `contracts/easy-doc-v1.yaml` · `00_progress.md`(읽기만)

**이 회차가 하지 않은 것:** 코드 수정 0 · 커밋 0 · `00_progress.md` 무접촉 · 본 트리 무접촉 ·
codex·privacy-gate 산출물 미열람(지시).

**규칙 5 준수.** 모든 음성 대조는 일회용 worktree(`scratchpad/cryptomut`, `9c7aa03` 고정)에서 했다.
복원은 **`git checkout --`** 로만 했고(`cp` 0회), 변조 대상 5파일의 sha256 을 본 저장소와 대조해
전건 일치를 확인했다. 본 저장소 `git status` 는 리뷰 시작 시점과 동일(미추적 3건만).

---

## 0. 한 줄 요약과 심각도 집계

**ⓒ crypto 5커밋의 암호 코어는 건전하다.** 표준 JCA AEAD 하나만 쓰고(프리미티브 조립 0),
nonce 는 매 호출 `SecureRandom` 96비트, 태그 128비트, 복호화 실패는 단일 예외·고정 메시지·cause 미연결이다.
**구현자 산출물 §6 의 음성 대조 A·B·C·E 를 내가 독립으로 재현했고 4건 전부 보고와 일치**했다
(§2 표). 모듈 건수 729(core 359 / application 44 / infrastructure 135 / api 188 / worker 3, RED 0)도
`--rerun-tasks` 로 **정확히 재현**됐다.

지적은 **11건 + 계약 8건**이고, **차단은 0건**이다.

| 심각도 | 건수 | 항목 |
|---|---|---|
| **차단(②장치)** | **1** | **L-1** — 심판 산출물을 **피심판 커밋이 편집**했다 |
| **수정 필요** | **9** | R-1 · R-2 · R-3(암호) / C-1 · C-2 · C-3(계약) / H-1 · H-2 · L-3(하네스) |
| **권고** | **19** | R-4 ~ R-11 / C-4 ~ C-8 / H-3 ~ H-6 / L-4 · L-5 |
| **판정 필요** | **0** | — |
| **미실행** | §5.2 | ktlint/detekt 전체 · Python 전체 게이트 · M-3b 시간 실측 |

**차단 1 의 근거는 ②(장치)뿐이고 ①(사건)은 0이다.**

- ①**사건 0** — `documents`·`conversions` 에 행을 쓰는 경로가 아직 0이고
  (§7ⓔ, `find */main -name "*Controller*"` → Auth·Health·Workspace·ContractError 넷뿐),
  암호문·평문이 실제로 흐르는 자리가 없다. §5 Phase 7 즉시 중단 기준에 닿는 경로를 찾지 못했다.
- ②**장치 1** — **L-1**(§4.5). 게이트 판정의 근거 파일인 `reviews/` 를 **그 판정의 대상인
  커밋이 편집**했다. 규칙대로 "Kotlin 코드가 아직 0줄이어도 ② 는 차단으로 올린다."
  **착수 차단 여부의 판정은 리더에게 넘긴다** — 이번 §8 의 **내용은 정직하고**
  리더 재판정을 인용하므로, 해악은 이 사례가 아니라 **통로**다.

**차단 승격을 검토했으나 멈춘 항목 하나** — R-3(`wireName` 무탐지). 근거는 §2 R-3 에 적었고,
리더가 다르게 판정할 여지가 있어 그 논거를 그대로 남긴다.

**구현자 산출물의 수치 주장은 내가 재현한 범위에서 전부 참이었다**(§2 · §4 · §4.5).

---

## 1. 도달 범위 점검 — 다섯 축을 가로지르는 필수 구획

**이 배치가 새로 들인 「전역·모든·항상」 — 전수 확인.**

| 선언 | 위치 | 실제 도달 | 판정 |
|---|---|---|---|
| "네 값 **어느 하나라도** 다르면 태그 검증이 실패한다"(AAD 4축) | `AesGcmContentCipher.kt:52` · 산출물 §2.2·§3-123 | **4축 중 2축(행·컬럼)만** 성질로 검증된다. `scheme`·`keyVersion` 은 **형식 고정**만 있다 | **R-1 수정 필요** |
| "복호화 실패는 **전부** `DecryptionFailedException` 하나" | `AesGcmContentCipher.kt:72` · `DomainExceptions.kt` KDoc | 7갈래 실측 단일화(MUT-E1·E2 재현). 단 catch 가 `GeneralSecurityException` 한정이라 `ProviderException` 은 **새어 나간다** | **R-4 권고** |
| "평문·암호문·키 재료는 **한 조각도** 로그에 넣지 않는다" | `AesGcmContentCipher.kt:78` | `AesGcmContentCipher` 경로는 양성 대조 포함 캡처로 확인. **`CryptoConfiguration` 의 로그와 MDC 는 캡처 밖** | **R-11 권고** |
| "이 문자열은 스키마 변경만큼 무겁다 — 이름만 다듬는 변경을 하지 않는다"(`wireName`) | `StoredContent.kt:107-109` | **강제자 0.** 산문 금지뿐이고, 값을 바꿔도 729건 전건 GREEN(MUT-I) | **R-3 수정 필요** |
| "기동은 막지 않는다 / 그 값이 필요한 요청만 거절" | `CryptoConfiguration.kt:45-47` | `AesGcmContentCipher` 수준은 단위 테스트가 덮는다. **빈 조립 자체는 테스트 0** | **R-2 수정 필요** |
| "`forbidden` 의 문자가 **하나도** 없다"(`x-filename-charset`) | `contracts/easy-doc-v1.yaml:1243` | **도달 0.** 이 노드를 읽는 코드 0건, `ContractSpec` 접근자 없음, 내보내기 엔드포인트 미구현 | **C-1 수정 필요** |
| "**오늘 적합한 어떤 구현에서도** 나가는 바이트가 달라지지 않는다" | `contracts/easy-doc-v1.yaml:2186-2188` | 근거는 **단수 구현 1개**인데 선언은 적합 구현 전칭. 그리고 이것이 `info.version` 을 안 올리는 **유일한** 근거다 | **C-2 수정 필요** |
| "`components/headers` 의 **세 항목은 전부** `schema.const`" | `contracts/easy-doc-v1.yaml:2181` · `:1233` | **있다.** `ContractHeaderDeclarationTest.kt:67-88` 이 Component 갈래 전부에 단언 | 검토함 — 지적 없음 |
| "모든 응답"(`x-global-response-headers.applies_to:554`) | 기존 조항 | 이 diff 밖. 게이트 24 에서 다뤘다 | 범위 밖 |

**은폐형 확대 — 0건.** 이 배치는 무시 패턴·억제·면제 조항을 새로 넓히지 않았다.
`.gitignore`·`detekt`·`ruff` 억제 추가 0, `@Suppress` 추가 0, 스캐너 제외 확대 0.
`build.gradle.kts` 의 `excludeTags("llm")` 은 기존 값이고 변경 없다.
**반대 방향이 하나 있다** — `MigrationCatalog` 는 하드코딩 열거를 **디스크 유도**로 바꿔
탐지형을 넓혔다(은폐형 아님). 그 잔여를 산출물 §4.4 마지막 문단이 스스로 적었고,
**나의 MUT-F′ 가 그 정직성을 실측으로 확인했다**(V3 삭제 시 `FlywayBaselineGuardTest` 는 실제로 초록).

**게이트가 지금 어디서 도는가 — 도달 0 의심.**

| 게이트 | CI 배선 | 확인 방법 |
|---|---|---|
| `AesGcmContentCipherTest`(17) · `EncryptionSchemeSchemaTest`(3) | **돈다.** `ci.yml:245` `./gradlew build` = 컴파일+ktlint+detekt+test, `:240` 이 `pgvector/pgvector:pg16` 을 미리 받고 Testcontainers 가 러너 Docker 를 쓴다 | `build.gradle.kts:120-123` 에 `excludeTags("llm")` 만 있고 db/crypto 태그 제외 없음 |
| `M-3b`(시간 축) | `:api:test` 소속이라 `build` 에 든다 | 다만 **CI 에서 한 번이라도 초록으로 관측됐는지는 미확인**(§5) |
| `CryptoConfiguration` | **도달 0** — 이 클래스를 부르는 테스트가 저장소에 **0건** | `grep -rn "CryptoConfiguration\|contentCipher" --include=*.kt` 중 `main/kotlin` 제외 시 0건 → **R-2** |
| `x-filename-charset` | **도달 0** | `grep -rn "filename-charset" backend-kotlin` → 0건 → **C-1** |
| 스캐너 `OWNERSHIP-403` | **부분 도달.** 로컬 상수 2형태는 잡고 **라이브러리 상수는 못 잡는다**(`HttpURLConnection.HTTP_FORBIDDEN` 주입 → BLOCK 0), 로컬 `= 403` 은 **선언 줄만** | → **L-3** |
| `reviews/`(게이트 판정 근거) | **쓰기 주체가 격리돼 있지 않다** — 저작 커밋이 심판 산출물을 편집한 이력 3건 | `git log --follow -- reviews/03_security-scanner_privacy-gate.md` → **L-1(차단)** |

---

## 2. 보안 불변식 — I-7 · I-8 (이 배치의 핵심 축)

### 2.0 음성 대조 — **독립 재현 결과** (일회용 worktree, 산출물 §6 대조)

산출물이 주장한 5건 중 **crypto 소관 4건(A·B·C·E)을 전부 독립 재현**했다. 표는 내가 관측한 값이다.

| # | 주입 | 산출물 주장 | **내 실측** | 일치 |
|---|---|---|---|---|
| A | `updateAAD` 두 줄 제거 | 3 red (결속값 · 실패갈래 · 표준 JCA) | **3 red, 같은 3건** | ✅ |
| B | `random.nextBytes(nonce)` 제거 | 1 red (같은 평문 2회) | **1 red, 같은 1건** | ✅ |
| C | 실패 원인별 분화 | 7건 | **원인 연결(E1) → 1 red / 갈래 분화(E2) → 3 red**. 주장한 "7건" 은 **케이스 수가 아니라 그 케이스가 세는 갈래 수**로 읽어야 맞다 | ⚠ 표기(R-9) |
| E | `V3` 파일 삭제 | 3 red + `FlywayBaselineGuardTest` 는 초록 | **3 red, 같은 3건 + 가드 초록** | ✅ |
| — | `PlainBody.toString` 재정의 삭제 | §6 "toString 게이트 재확인 — `PlainBody` 가 검사받는다" | **1 red** (`값을 감싸는 타입의 toString…`) — 주장 참 | ✅ |

> **C(E2) 3 red 의 내역**: `모르는 키 세대를 거부한다` · `실패 갈래가 서로 구분되지 않는다` ·
> `잘못된 키 재료는 그 세대만 뺀다`. 산출물의 "7건" 은 §3 대응표(`:118`)가 세는
> **7갈래**(태그·모르는 세대·길이 미달·모르는 방식·다른 컬럼·다른 행·다른 키)와 같은 수이므로,
> 케이스 수로 오독될 표기다(R-9).

**E 재현 중 내가 한 번 오판했다가 실측으로 정정한 자리를 남긴다.** §6-E 가 지목한
`방식과 키 세대를 적지 않은 INSERT 는 실패한다` 를 `EncryptionSchemeSchemaTest` 안에서 찾다 실패해
"존재하지 않는 테스트" 로 판단했으나, 그 케이스는 **`PythonSchemaBaselineTest.kt:120`** 에 있다.
두 클래스를 함께 돌리자 **정확히 3 red** 가 났다. 산출물은 옳고, 표가 클래스를 적지 않은 것이
오독 원인이다(R-9).

### R-1 [**수정 필요**] AAD 4축 중 `scheme`·`keyVersion` 에 **음성 대조가 없다** — 성질이 아니라 형식만 고정돼 있다

**마감: Phase 4 종료 전 (I-7 검증 5 의 증거).** **수신자: `kotlin-implementer` → `privacy-gate`.**

산출물 §2.2·§3(`:123`)과 `AesGcmContentCipher.kt:52` 는 *"네 값 어느 하나라도 다르면 태그 검증이
실패한다"* 를 4축 전건으로 적는다. `결속값이 어긋나면 거부한다` 가 실제로 4갈래를 돌리고 4갈래 다
거부되므로 **선언은 참이다.** 문제는 **초록의 이유**다.

- `방식 컬럼 조작`(`AesGcmContentCipherTest.kt:205-208`) — `AesGcmContentCipher.kt:128` 의
  **이른 동등성 관문**(`content.scheme != AES_256_GCM_V1 → throw`)이 AAD 에 닿기 전에 끊는다.
- `키 세대 컬럼 조작`(`:201-204`) — 세대 2 는 `KEY_GEN_2` 라 **키가 달라서** 태그가 깨진다.
  AAD 의 `keyVersion` 조각이 없어도 결과가 같다.

**독립 재현(일회용 worktree).** 제품 AAD 에서 한 조각을 빼고, `표준 AES-256-GCM 으로 열린다` 가
독립 재조립하는 문자열도 **함께 정정**했다(리팩터링 시 자연스럽게 동반되는 공동 편집이다):

| 변이 | 결과 |
|---|---|
| MUT-A: AAD 에서 `\|$scheme` 제거 + 테스트 재조립 정정 | **17/17 전건 GREEN** |
| MUT-B: AAD 에서 `\|$keyVersion` 제거 + 테스트 재조립 정정 | **17/17 전건 GREEN** |

즉 두 축의 결속을 **잃어도 빨개지는 케이스가 하나도 없다.** 오늘 그 두 축을 붙잡고 있는 것은
`표준 AES-256-GCM 으로 열린다`(`:313-335`) 하나인데, 그 케이스는 **형식 고정**이라
제품과 테스트를 함께 고치면 조용히 지나간다.

**성질 자체는 살아 있다는 점을 함께 적는다** — MUT-C(AAD 전부 제거)는 3 red 다. 그리고
`keyVersion` 결속은 재현 불가능한 방어가 아니다: **두 세대에 같은 키 재료를 넣는 오설정**
(회전 중 복사·붙여넣기)에서는 `keyVersion` 만이 두 세대를 가르고, `CryptoConfiguration.kt:90` 의
`putIfAbsent` 는 **세대 번호 중복만** 잡고 **키 재료 중복은 보지 않는다.** 그래서 이 축은
장식이 아니라 실제 방어이고, 그만큼 음성 대조가 필요하다.

**처방(넓히지 않는 형태, 둘 중 하나면 족하다).**
1. **키 재료를 공유하는 두 세대**를 만든 뒤 v1↔v2 재라벨이 거부되는지 보는 케이스 1건 —
   `keyVersion` 축을 **격리**한다.
2. **known-answer 테스트(KAT)** 1건 — 고정 키·고정 nonce·고정 AAD → 고정 암호문 바이트.
   이 하나가 R-1 의 두 축과 아래 **R-3**(`wireName`)까지 동시에 닫는다.
   산출물이 "키를 소스에 적지 않는다"(`AesGcmContentCipherTest.kt:49-53`)를 이유로 KAT 를
   피한 것은 타당하나, **KAT 키는 비밀이 아니다** — 고정 ASCII 문자열의 SHA-256 으로 유도하면
   스캐너 `SECRET-LITERAL`(값의 모양이 기준)에 걸리지 않으면서 결정성을 얻는다.

**`scheme` 축은 처방을 강제하지 않는다.** 이른 관문이 이미 막고 있어 AAD 의 `scheme` 은
심층 방어다. 다만 산출물 §2.2·§3 이 4축을 **같은 무게**로 적고 있으므로,
**어느 축이 무엇으로 강제되는지**를 표에 갈라 적는 것만으로도 이 지적은 닫힌다.

### R-2 [**수정 필요**] `CryptoConfiguration` 은 **테스트가 0건**이고, 산출물 §7ⓔ 의 잔여 선언이 실제보다 좁다

**마감: 다음 단위(문서 저장 경로) 착수 전.** **수신자: `kotlin-implementer` → 리더.**

```
grep -rn "CryptoConfiguration|contentCipher" backend-kotlin --include=*.kt | grep -v main/kotlin
→ 0건
grep -rn "ContentCipher" backend-kotlin --include=*.kt  → main 소스 4파일뿐, 테스트 0
```

`CryptoConfiguration.kt:85-99` 는 ⑴ `@Bean` 조립 ⑵ **중복 세대 dedup(`putIfAbsent`, "먼저 적힌
것을 쓴다")** ⑶ 경고 한 줄 ⑷ `SecureRandom` 주입을 진다. **넷 다 어떤 테스트도 부르지 않는다.**
`ConfigurationPropertiesBindingTest.kt:71-90` 은 `EncryptionProperties` **바인딩**만 보고
빈 조립은 보지 않는다. 어떤 Spring 컨텍스트 테스트도 `ContentCipher` 빈의 **존재**를 단언하지 않는다
(`ApiStartupWithDatabaseTest` 4건은 `/health`·Flyway·`alembic_version` 만 본다).

**⑵ 는 성질이 데이터에 남는 종류다** — 같은 세대를 두 번 적었을 때 **어느 키가 사용자 데이터를
암호화하는가**를 정하는 분기이고, 그 답이 지금 아무 데서도 고정돼 있지 않다.

**산출물 §7ⓔ 가 적은 잔여는 `worker` 한쪽뿐이다** — *"`worker` 프로필의 암호화 빈이 실제로
조립되는지 … 확인하는 테스트가 없다"*. 사실은 **`api` 쪽도 같고, `CryptoConfiguration` 자체가
무테스트**다. **선언한 잔여가 실제 잔여보다 좁다** — 이 저장소가 반복해 고쳐 온 형태다.

**처방.** ⑴ `contentCipher()` 를 직접 부르는 단위 테스트 2건(중복 세대 first-wins · 값 없는 세대 제외),
⑵ api·worker 컨텍스트 각각에서 `ContentCipher` 빈 존재 단언 1건. 셋 다 컨테이너 없이 된다.

### R-3 [**수정 필요**] `EncryptedField.wireName` 변경 탐지기 **0** — 저장 데이터를 영구히 못 읽게 만드는 변경이 조용히 지나간다

**마감: 다음 단위(첫 INSERT) 착수 전.** **수신자: `kotlin-implementer` → `privacy-gate`.**

`StoredContent.kt:107-109` 가 스스로 적는다: *"컬럼이 바뀌면 여기도 함께 바뀌어야 한다 — 그때
기존 암호문이 열리지 않으므로, **이 문자열은 스키마 변경만큼 무겁다.** 이름만 다듬는 변경을 하지 않는다."*

**이 선언의 강제자는 0이다.** 독립 재현(MUT-I): `DOCUMENT_SOURCE_TEXT` 의 `wireName` 을
`"documents.source_text_encrypted"` → `"documents.source_text_enc"` 로 바꾸고
`:core:test :application:test :infrastructure:test :api:test` 전부를 돌렸다 →
**RED 0 / 전건 GREEN.** 모든 테스트가 같은 실행 안에서 암호화하고 복호화하므로,
**저장된 과거 행**과의 정합은 어느 케이스도 보지 않는다.

**결과의 성질.** 이 변경이 배포되면 그 컬럼의 **기존 행 전부가 영구히 열리지 않는다.** 실패는
`DecryptionFailedException` 단일 예외라 원인이 드러나지 않고(I-7 검증 3 의 의도된 결과),
쓰기 시점에는 아무 증상이 없으며 **읽기 시점에 비로소** 나타난다.

**차단 승격을 검토했으나 멈춘 근거(리더 판단용으로 남긴다).**
- 승격 논거 — CLAUDE.md 는 **범위 선언형** 장치가 *"빈 선언에서 통과하면 안 된다"* 고 못박는다.
  이것은 KDoc 산문 금지 하나뿐인 **빈 선언**이다.
- 멈춘 논거 — ⑴ 아직 저장된 행이 0이라 **사건**이 성립하지 않는다. ⑵ 이 선언은 무언가를
  "통과"로 보고하는 게이트가 아니라 주의 문구라, "무력화된 장치"(②)의 정의에 정확히 들어맞지 않는다.
- 따라서 **수정 필요 + 마감을 첫 INSERT 앞에 둔다.** 리더가 ②로 볼 여지가 있다고 본다.

**처방.** R-1 처방 2(KAT)가 이것도 함께 닫는다. 최소안은 네 `wireName` 리터럴을 못박는 회귀 1건이다.

### R-4 [권고] `decrypt` 의 catch 가 `GeneralSecurityException` 한정 — `ProviderException` 은 **단일 예외 규율 밖으로 새어 나간다**

`AesGcmContentCipher.kt:141` `catch (ignored: GeneralSecurityException)`. JVM 으로 계층을 확인했다:

```
ProviderException instanceof GeneralSecurityException? false
ProviderException instanceof RuntimeException?         true
```

`java.security.ProviderException` 은 JCA 공급자가 내부 오류에 쓰는 표준 예외이고
`GeneralSecurityException` 이 **아니다.** SunJCE 소프트웨어 경로에서는 거의 나지 않지만,
PKCS#11·HSM·FIPS 공급자로 바꾸는 순간 실재한다. 그때 `decrypt` 는 **다른 타입·다른 메시지**로
빠져나가 `GlobalExceptionHandler.kt:393` 의 `StorageException` 매핑에도 걸리지 않고
`else -> null` 로 떨어진다 — 즉 **본문 형태가 달라지고** 그 차이가 곧 oracle 이다.

"복호화 실패는 **전부** 하나" 라는 선언의 범위가 catch 절의 실제 도달보다 넓다.
처방: `ProviderException` 을 catch 에 명시하거나(권장 — 범위를 근거만큼만 넓힌다),
`RuntimeException` 전체를 삼키지는 말 것.

### R-5 [권고] oracle 판정에서 **시간 축이 빠져 있다** — 같은 배치가 401 에는 그 기준을 적용했다

`실패 갈래가 서로 구분되지 않는다`(`:255-307`)는 `(타입, 메시지, cause)` 3튜플만 본다.
같은 배치의 `858347d`(M-3b)는 401 세 갈래에 대해 *"본문·헤더가 같다는 것은 시간 축의 **대리값**"*
이라 적고 응답 시간 비 회귀를 세웠다. **같은 논리가 `decrypt` 에는 적용되지 않았다.**

구조적으로 세 갈래(모르는 방식·없는 키 세대·길이 미달)는 **암호 연산 전에** 끊고,
태그 실패는 **전체 GCM 복호 후에** 난다 — 지연이 원리적으로 다르다.

**공격 가치는 낮게 본다** — `decrypt` 의 입력은 전부 DB 행에서 오고 공격자가 고르는 값이
닿지 않는다(오늘 기준). 그래서 **권고**로 둔다. 다만 산출물 §2.3 표가
*"원인이 응답·로그·**지연** 어느 축으로든 구분돼 나가면 그것이 복호화 oracle"* 이라
**지연을 명시**하므로, 선언과 검사 범위가 어긋난 자리로는 남는다.
처방: 지연을 재거나(비용 큼), 선언에서 지연 축의 현재 미검증을 명시하거나 둘 중 하나.

### R-6 [권고] **세대 번호는 그대로 두고 키 값만 바꾸는 오설정**을 알아챌 장치가 없다

`application.yml`(api `:52-60`, worker `:30-38`)에서 `write-key-version` 은 **환경변수**
(`EASYDOC_ENCRYPTION_WRITE_KEY_VERSION`)로 바뀌지만 `keys` 목록은 **YAML 편집**이 필요하다.
회전의 두 반쪽이 서로 다른 기제로 통제된다.

- 세대만 올리고 키를 안 더하면 → `encrypt` 가 503. **fail-closed 다**(문제 없음).
- **`EASYDOC_ENCRYPTION_KEY_V1` 의 값만 갈아끼우면** → 옛 v1 행 전부가 열리지 않고,
  새 행은 같은 `key_version=1` 로 다른 키로 쓰인다. **탐지 장치가 없고**, 증상은
  `DecryptionFailedException` 단일 예외라 운영자에게 원인이 보이지 않는다.
- api·worker 가 서로 다른 키를 들어도 알아채는 장치가 없다(yml 주석이 위험만 적어 둔다).

처방(비밀 미노출): 기동 로그에 세대별 **키 지문**(HMAC/SHA-256 앞 8자 등)을 남긴다.
`AesGcmContentCipher.kt:103` 이 이미 "몇 세대를 적재했다" 를 찍으므로 자리는 있다.

### R-7 [권고] `SCHEME_COLUMN_WIDTH = 16` 은 스키마에서 온 값의 **손 전사**다

`EncryptionSchemeSchemaTest.kt:97`. 같은 파일의 다른 두 케이스는 **실제 DB 의
`pg_get_constraintdef`** 를 읽는데, 이 케이스만 `varchar(16)` 을 상수로 베껴 온다.
컬럼 폭이 넓어지면 이 단언은 조용히 낡는다. 이 케이스는 **DB 를 띄우고도 쓰지 않는다** —
`information_schema.columns.character_maximum_length` 를 읽으면 전사가 사라진다.

### R-8 [권고] `ApiStartupWithDatabaseTest.kt:44` 의 `@DisplayName` 이 **"V1·V2"** 로 남아 있다

같은 커밋(`e891a08`)이 단언을 `containsExactlyElementsOf(MigrationCatalog.versions)` 로 바꿔
실제로는 V1·V2·V3 를 검사하는데, 표시 이름은 그대로다. `FlywayBaselineGuardTest` 쪽 이름은
같은 커밋에서 *"V1 뒤의 마이그레이션만 적용한다"* 로 고쳐졌으므로 **같은 커밋 안의 비대칭**이다.
`MigrationCatalog` 를 도입한 이유(*"산문이 거짓말을 시작한다"*)에 정확히 걸린다.

### R-9 [권고] 산출물 §6 음성 대조 표가 **테스트 클래스를 적지 않아** 오독을 만든다

E 행의 세 케이스는 **두 클래스에 흩어져 있고**(`EncryptionSchemeSchemaTest` 2 +
`PythonSchemaBaselineTest` 1), C 행의 "7건" 은 케이스 수가 아니라 갈래 수다.
나는 이 표를 근거로 한 번 오판했다가 실측으로 정정했다(§2.0). 표에 클래스명을 넣는 것만으로
닫힌다. **내용은 옳다** — 정정 요구가 아니라 표기 요구다.

### R-10 [권고] 전역 `toString` 게이트가 **일반 class 에는 닿지 않는다** — `EncryptedContent` 가 그 첫 사례다

독립 재현: `EncryptedContent.toString()` 재정의를 지우고 `SensitiveToStringReachTest` →
**4/4 GREEN**(MUT-H). 같은 변이로 `AesGcmContentCipherTest` 는 **1 red**(MUT-H2,
`평문·암호문 래퍼의 toString 이 값을 내지 않는다`). 즉 **오늘은 덮여 있다.**

`ProductClasses` KDoc 이 대조 범위를 `data class`·`value class` 로 **정직하게 선언**하므로
거짓 선언은 아니다. 다만 이 배치가 **암호문을 든 일반 class** 를 처음 들였고,
같은 형태가 늘면 각 모듈의 지역 테스트에 의존하게 된다. 게이트를 넓힐지는
그 게이트 소유 레인의 판단이므로 **사실만 남긴다**.

### R-11 [권고] 로그 누출 캡처가 **`CryptoConfiguration` 의 로그와 MDC** 를 보지 않는다

`암호화 경로가 로그로 새지 않는다`(`:375-408`)는 ROOT 에 `ListAppender` 를 달고
`AesGcmContentCipher` 경로만 돌린다. `CryptoConfiguration.kt:91` 의 중복 세대 경고는
**호출되지 않는다**(R-2 와 같은 뿌리). `render`(`:445-454`)는 `formattedMessage` +
예외 사슬만 모으고 **MDC 는 보지 않는다.** 오늘 두 자리 다 값을 싣지 않는 것은 코드로 확인했으나,
확인의 근거가 **테스트가 아니라 내 눈**이다.

부수 관측(지적 아님): `logback-test.xml` 이 없어 root 는 logback 기본값(DEBUG)이라 INFO 도
캡처된다. 나중에 테스트 로그 설정을 넣으면 양성 대조가 WARN 하나뿐이라 INFO 줄이 조용히
감사 밖으로 나간다. 그때 양성 대조를 레벨별로 두면 된다.

### 그 밖의 보안 축 — **검토함**

| 항목 | 확인 | 판정 |
|---|---|---|
| nonce 난수원 | `SecureRandom` (`AesGcmContentCipher.kt:84`, `CryptoConfiguration.kt:98`). 12바이트, 매 `encrypt` 호출마다 `nextBytes`. 96비트를 고른 근거(GCM 이 접지 않는 유일한 길이)도 정확 | 검토함 — 지적 없음 |
| nonce 재사용 경로 | 인스턴스 공유 `SecureRandom` 은 스레드 안전. 재시도·같은 트랜잭션 두 컬럼 모두 `encrypt` 를 다시 부르므로 새 nonce. **키+nonce 쌍이 두 번 쓰이는 경로를 찾지 못했다** | 검토함 — 지적 없음 |
| AAD 모호성 | 구분자 `\|` 가 네 조각 어디에도 들어갈 수 없다는 논거가 참(scheme 은 관문이 상수로 고정, keyVersion 은 Int, wireName 은 열거 상수, UUID 는 정규 표기) | 검토함 — 지적 없음 |
| 단일 예외·단일 메시지 | `DecryptionFailedException` 은 인자를 받지 않고 `MESSAGE` 상수 하나. cause 미연결. MUT-E1·E2 로 강제 확인 | 검토함 — 지적 없음(단 R-4) |
| 키 길이 검증 | 32바이트 아니면 그 세대 제외 + 경고(값 없음). base64 파싱 실패도 같은 처리. **경고에 키가 반향되지 않음을 캡처로 확인** | 검토함 — 지적 없음 |
| 키 회전 읽기/쓰기 | `키를 회전해도 옛 세대를 읽는다`(`:219-234`)가 v1 쓰기 → v2 배선 → v1 읽기 + v2 쓰기를 전부 본다 | 검토함 — 지적 없음 |
| `PlainBody` 직렬화 누출 | `@JvmInline value class` 지만 `toString` 재정의가 있고, 게이트가 실제로 닿는다(MUT-G 1 red). **Jackson 경로는 오늘 0** — DTO 어디에도 쓰이지 않는다 | 검토함(오늘 기준) |
| `DecryptionFailedException` → 500 | `GlobalExceptionHandler.kt:393` `is StorageException -> 500`, 헤더 없음, 본문은 `jsonError` 가 `application/json` + `ContractErrorBody`. 메시지는 저장소 고정 문자열 | 검토함 — 지적 없음 |
| 소스에 키 리터럴 | 없다. 실행 시점 `SecureRandom` 생성. 스캐너 exit 0, crypto 파일 리포트 0건(재현) | 검토함 — 지적 없음 |
| `EncryptionKeyProperties.value` 의 보호 | 필드 이름 `value` 는 `SENSITIVE_NAME_TOKENS` 에 없다(게이트 KDoc `:58-61` 이 자격증명 토큰을 **의도적으로** 뺐고 그 절반을 `Secret` 래퍼 타입 검사에 맡긴다고 적는다). 오늘 `Secret` 이라 래퍼 검사에 든다. **타입을 `String` 으로 바꾸는 회귀는 `ConfigurationPropertiesBindingTest.kt:87` 의 `.reveal()` 이 컴파일 단계에서 막는다** — 다만 그 고정은 **부수적**이지 게이트의 의도가 아니다 | 검토함 — 지적 없음(부수 고정) |
| I-8(`encryption_scheme`) | V3 가 CHECK 를 `aes256gcm-v1` 로 옮기고 `fernet-v1` 을 **제거**. 두 테이블 모두. DEFAULT 제거가 실제로 강제됨을 내가 별도 프로브로 재확인(카탈로그 `default=null nullable=NO` ×4, 컬럼 누락 INSERT → `null value in column "key_version" … violates not-null`) | **해제 확인** |

---

## 3. 계약 준수 — §2.2 · §6 (ⓑ `765a377`)

### C-1 [**수정 필요**] `x-filename-charset`(G2) — 집합은 **바이트 동일이 참**이나, 그 동일성의 **도달이 0**이고 조항이 금지한 형태가 유일한 실행 단언이다

**마감: 내보내기 엔드포인트를 구현하는 커밋.** **수신자: `contract-keeper` · `kotlin-implementer`.**

- **동일성 자체는 참이다**(실측): `contracts/easy-doc-v1.yaml:1241` 의 `forbidden` 과
  `core/easyread/Export.kt:59-60` 의 `FORBIDDEN_IN_FILENAME` 은 **원시 문자열 38자가 동일**하고,
  `0..0x10FFFF` 전 범위로 전개했을 때 **양쪽 74 코드포인트, 대칭차 공집합**이다.
- **그 동일성을 대조하는 실행 검사는 0건이다.** `grep -rn "filename-charset" backend-kotlin` → 0.
  `ContractSpec` 에 접근자 없음. 게다가 `FORBIDDEN_IN_FILENAME` 은 **`private`** 이라
  테스트가 읽을 수조차 없다. 근거는 산문 3곳뿐(커밋 메시지 · changelog `:1160` · 계약 `:1261-1263`).
- **조항이 존재 이유로 든 형태가 지금 도는 유일한 형태다.** 계약 `:1257-1258`·changelog `:1153-1155`
  가 *"경계 테스트가 금지 집합을 코드에 복제한다 … 자기 사본과 대조해 초록"* 을 문제로 지목하는데,
  오늘 실제로 도는 `ExportTest.kt:34·52·104` 가 정확히 그 손열거다.
- **구체적 커버리지 구멍**: 역슬래시 `\`(U+005C)가 금지 집합에 있는데 **어떤 실행 테스트의 입력에도
  없다.** `ExportTest.kt` Filename 절(`:25-113`)의 문자열 리터럴 전수 확인 결과 리터럴 역슬래시 0건.
  `parity/fixtures/export/export.json`(12케이스)도 `\`·DEL·C1·`:*?<>|` 전부 미포함.

### C-2 [**수정 필요**] `x-changelog:2186-2188` 의 범위가 **근거를 넘고**, 그것이 `info.version` 을 올리지 않는 **유일한 근거**다

**마감: 이 조항을 근거로 다음 계약 개정을 판정하기 전.** **수신자: `contract-keeper`.**

선언은 *"**오늘 적합한 어떤 구현에서도** 나가는 바이트가 달라지지 않는다"*(전칭)인데,
제시된 근거는 *"구현은 이미 이 성질을 만족한다"*(**단수 구현 1개**)다.
반례가 실재한다 — C0+DEL 만 걷어내는 구현(`Export.kt:55` 가 "앞선 판" 으로 지목한 바로 그 형태)은
조항 신설 **전에는 적합**했고 신설 후 **부적합**이며 나가는 바이트가 달라진다.
규칙 4("범위는 근거를 넘지 않는다")에 정면으로 걸리고, 이 선언이 **버전을 안 올리는 유일한 근거**라
값이 붙는 자리다.

### C-3 [**수정 필요**] **P-22 식별자 충돌** — 같은 레지스트리에서 두 노드가 같은 ID 를 쓴다

**마감: Phase 4 문서 API 착수 전.** **수신자: `contract-keeper`.**

- `04_contract-keeper_documents-test-spec.md:266` 이 **P-22** = "응답 인라인 헤더의
  `schema.examples` 첫 원소" 로 **신설**.
- 그런데 P-22 는 이미 쓰이고 있다 — `api/src/test/.../support/ContractSpec.kt:409`
  (`deletionRefusalPrecedenceExample()`, 도입 근거 `03_kotlin-implementer_workspaces-fixes.md:217`).
- 원인: 스펙 §4 서문(`:258-260`)이 기존 범위를 **P-16~P-21** 로 적는데,
  구현 레인이 이미 P-22 까지 갔고 명세 문서가 그것을 기록하지 않았다.

### C-4 [권고] `:2201-2203` "CE-5·CE-6 은 그 커밋에 들어간다" 에 **강제자가 없다**

내보내기 엔드포인트가 CE-5·CE-6 없이 들어와도 실패하는 검사가 존재하지 않는다.
같은 성질이 77케이스 전체에 있다 — `grep -rnE '\b(DC|DL|DD|CR|CU|CE)-[0-9]+' backend-kotlin` → **0건**.
§6 이 "구현하는 그 커밋에 들어간다" 로 설계한 의도된 상태이나, **의도를 지키는 장치가 없다.**

### C-5 [권고] 테스트 명세 §0 의 "값을 전사하지 않는다" 선언이 **카디널리티 전사에는 닿지 않는다**

§0 의 자체 grep 은 실제로 통과한다(바이트 상한·오류 문구·파일명 문자 목록 0건 — 재현 확인).
§0 `:30` 이 상태 코드에 **면제 조항**을 스스로 뒀다. 그러나 면제도 없고 검사도 닿지 않는 전사가 남는다:
검증 항목 키 **3**(`:110`·`:127`·`:161`·`:205` ← yaml `:1728`) · 마스킹 범주 **2**(`:138`·`:276` ← `:1911`) ·
사적 헤더 **2**(7곳 ← `:550-551`) · 하한선 **10곳** · `components/headers` **3**.
`ValidationErrorItem.required` 에 키가 하나 늘면 4곳이 조용히 틀린다.

### C-6 [권고] X-A2 는 정본에서 닫혔는데 **원장이 아직 미처리로 들고 있다**

`00_contract-keeper_test-plan.md:104` 가 이 커밋에서 정정됐고(사유 `:158-169`),
계약 쪽은 `dec3124` 가 이미 정합시켰다. 그런데 `00_progress.md:1392`(게이트 24 잔여 #12)와
`:1456`(⑥)이 **여전히 X-A2 를 미처리로 등재**한다. 정본을 고치고 인용처가 반대로 드리프트하는 것은
X-A2 정정이 막으려던 바로 그 기제다. (나는 원장을 읽기만 했다.)

### C-7 [권고] 행 번호 드리프트 3건

`765a377` 이 `:1230` 이후를 41줄 밀어 `x-open-asymmetry` 가 `:413-424` → **`:431-442`** 로 이동.
옛 위치를 그대로 든 문서 — `03_kotlin-implementer_phase3-preflight.md:205` ·
`03_contract-keeper_react-e2e-plan.md:131` · `reviews/15_phase3-preflight_cross.md:62`.
스펙 §0 `:38-40` 이 밀림을 예고하고 "갈리면 키 경로가 이긴다" 로 처리했으나 이 셋은 갱신되지 않았다.

### C-8 [권고] `x-open-asymmetry` 는 **미결임을 숨기지 않으나**, 근거가 폐기 대상 런타임 실측 위에 서 있다

정직성 판정은 **문제 없음**이다 — `:418`·`:423` 이 두 축의 `measured_on` 을 명시하고,
`:439-442` 가 *"이 계약은 그 비대칭을 해소하지 않는다"* 로 미결을 못박으며,
스펙 `:475`·`:519` 도 판정을 내리지 않는다고 적는다. 선택지 셋은 계약이 아니라
스펙 `:495-501` 에 있고, 권고 (나)의 한계까지 `:503-517` 에 적혀 있다. **결정된 것처럼 읽히지 않는다.**

걸리는 자리는 하나 — `:434` 의 근거가 *"`create_from_text` 가 `strip_control_chars` 를 부르지 않는다"*
라는 **Python 실측**이다. 스펙 `:482-493` 이 "그 근거의 실체가 달라졌다(Kotlin 에서 500 이 재현되지
않음)" 를 2026-08-19 실측으로 적었는데, **계약 절 자체는 이 커밋에서 갱신되지 않았다.**
계약만 읽는 사람은 없어진 런타임의 동작을 근거로 읽는다.

### 그 밖의 계약 축 — **검토함**

| 항목 | 확인 | 판정 |
|---|---|---|
| 인라인 헤더 유지 판정(X24-5) | disposition-type 문제가 아니라 **인라인 선언 vs `components/headers`** 문제. 근거는 `:2172-2184` — G1~G4 무근거이고, *"탐지를 넓혀 해결된 자리를 구조 변경으로 되돌리지 않는다"*. **판정이 테스트로 고정돼 있다**(`ContractHeaderDeclarationTest.kt:57-66`, `INLINE_HEADERS` 2건). 재현: `:api:test --tests '*Contract*'` → **113/0** | 검토함 — 지적 없음 |
| 77 케이스 개수 | 실제로 **정확히 77**(DC 23 + DL 11 + DD 7 + CR 10 + CU 11 + CE 15) | 검토함 — 지적 없음 |
| 행 번호 인용 정확성 | 45개 표본 대조 → **전부 정확** | 검토함 — 지적 없음 |
| yaml diff 무손실 | 107 insertions / **0 deletions** — `:2148-2149` 의 "하나도 바뀌지 않았다" 는 참 | 검토함 — 지적 없음 |
| React 영향 | `frontend/src/api/client.ts:250-266` `parseFilename` 은 `decodeURIComponent` 만 하고 문자 집합 검사 없음. `Location` 응답 헤더를 읽는 코드 0건 | 검토함 — 지적 없음 |
| snake_case · `{"detail":…}` · no-store · 404 은닉 | 이 배치는 새 엔드포인트를 들이지 않았다. `GlobalExceptionHandler` 변경 0 | 대상 없음 |

---

## 4. parity 위험 · Kotlin/Spring 관용성 · 테스트 적정성 · ⓐ 하네스 레인

### 4.1 parity 위험 — **대상 없음** (§4.5·§4.6)

**Python 무변경 확인.** `git diff --stat 76f6863..9c7aa03 -- app/` → **변경 0.**
`tests/` 변경 2파일은 ⓐ 하네스 레인(`test_harness_scope_reach.py`, `test_privacy_scanner.py`)이고
제품 동작이 아니다. 문서 fixture·정규식·한글 처리·프롬프트·POI 추출·파일명 정규화 —
**이 단위가 건드린 자리가 하나도 없다.** 구현자 §1 의 *"Python 코드를 열지 않았다"* 와 일치한다.
Phase 4 문서 fixture 는 다음 단위 소관.

### 4.2 Kotlin/Spring 관용성 — §3.1 · §3.2

| 항목 | 확인 | 판정 |
|---|---|---|
| 모듈 3분할 | `core/crypto/StoredContent.kt`(타입만, JCA·Spring·JDBC 0) → `application/crypto/ContentCipher.kt`(포트, import 는 core + `java.util.UUID` 뿐) → `infrastructure/crypto/*`(JCA·Spring). §3.2 그대로 | 검토함 — 지적 없음 |
| `core` 가 Spring·DB 를 끌어들이지 않는가 | `core/build.gradle.kts` 는 `platform(libs.spring.boot.bom)` 을 **테스트 제약용으로만** 쓴다(jar 0개 추가). `moduleBoundaryCheck` **BUILD SUCCESSFUL** 재현 | 검토함 — 지적 없음 |
| 설정 소유 위치 | `easydoc.crypto.fernet-key` 를 **물려받지 않고 대체**(`easydoc.encryption`). 옛 이름을 재사용하면 Fernet 키가 32바이트가 아니라 조용히 버려진다는 사유가 정확 | 검토함 — 지적 없음 |
| `EncryptedField` 4종 설계 | 컬럼 4개 = 열거 4개. `documents.source_text_encrypted` · `conversions.{easy_text,masked_items,edited_text}_encrypted` — 스키마와 일치 확인 | 검토함(단 R-3) |
| `key_version smallint` vs `Int` | 산출물 §7ⓑ 가 스스로 적었다. 도메인 타입이 컬럼보다 넓다. 실무 도달 불가 | 검토함 — 산출물 자기 신고 정확 |
| V3 DEFAULT 제거의 강제 기제 | **런타임 NOT NULL** 이다(컴파일 아님). 내 프로브가 실측: 카탈로그 4행 전부 `default=null nullable=NO`, 컬럼 누락 INSERT → `null value in column "key_version" … violates not-null constraint` | 검토함 — 지적 없음 |
| V2 체크섬 변경의 한계 | 감수 근거(보존할 DB 없음)와 "배포 이후에는 못 쓴다" 가 V2·V3 양쪽 헤더에 적혀 있다. 탐지자는 **Flyway 자신의 checksum mismatch**(기동 실패)이고 fail-closed 다. `FlywayBaselineGuard` 지문은 **V1 만 적용한 상태**라 V3 와 무관 — 확인 | 검토함 — 지적 없음 |
| `MigrationCatalog` 하드코딩 제거 | 아홉 자리의 `containsExactly("1","2")` 를 디스크 유도로 교체. **소스 디렉터리**를 읽는 근거(리소스 복사 누락을 잡는다)가 옳다. 남는 잔여(파일 삭제 시 기대도 준다)를 §4.4 가 자기 신고했고 **MUT-F′ 로 그 정직성 확인** | 검토함 — 지적 없음 |
| `documents.id` DB 기본값 | **없다**(`V1:74 id uuid NOT NULL`). AAD 가 결속하는 행 UUID 를 애플리케이션이 정하므로 "DB 가 만든 다른 id 에 결속" 하는 사고 경로가 구조적으로 닫혀 있다 | 검토함 — 지적 없음 |
| `CryptoConfiguration` 조립 | **→ R-2** | 수정 필요 |

### 4.3 테스트 적정성 — §6

| 항목 | 확인 |
|---|---|
| 모듈 건수 | 산출물 §6 의 **729(359/44/135/188/3)** 를 `--rerun-tasks` 로 **정확히 재현**. RED 0 · skip 0 |
| 실패 경로 | crypto 17건 중 거부·실패 케이스가 8건. 성공 경로만 있는 모듈 아님 |
| Testcontainers 실물 | `EncryptionSchemeSchemaTest`·`PythonSchemaBaselineTest` 가 실제 PostgreSQL 에 CHECK·NOT NULL·INSERT 를 건다 |
| 모킹 0 | `AesGcmContentCipherTest` 는 JCA 를 모킹하지 않는다. `FixedNonceRandom` 은 **음성 대조 전용 난수원**이지 암호 경로 대체가 아니다 |
| 기준의 독립성 | `표준 AES-256-GCM 으로 열린다` 가 **제품 코드를 부르지 않고** JCA 로 재조립 — 자기 대조 아님 |
| 판정 코드의 자기 포함 | `MigrationCatalog`·`ProductClasses` 등 판정 코드가 ktlint·detekt·컴파일 범위 안(같은 `build`) |
| **빈 자리** | R-1(AAD 2축) · R-2(`CryptoConfiguration` 0건) · R-3(`wireName`) · C-4(77케이스 0건) |

---

### 4.4 ⓐ 하네스 레인 — `SourceScanFormsProbe` / 소스 파서 (`eb075f1`·`70d4122`)

`ProductClasses` 의 소스 파서와 그 도달 범위를 못박는 `SourceScanFormsProbe` 는 이 배치의
**범위 선언형 장치**다(KDoc `:36-64` 이 "잡는다 / 못 잡는다" 를 목록으로 선언하고,
프로브가 *"산문이 아니라 실측"* 이라 적는다). 그 선언과 실제 도달을 대조했다.

**프로브가 실제로 못박는 것은 넓다** — 7 Form 17 형태(맨 선언·가시성 수식어·`data object`·
애너테이션 4갈래·중첩 4종·`companion object` 3단계·여러 줄 주 생성자·`constructor` 분리형·
본문 없는 선언), 과잉 탐지 트랩 4종, fatal 3종. **KDoc 「못 잡는 것」 ⑴~⑷ 도 프로브가 고정한다.**
이 자체는 이 저장소가 요구하는 형태(선언을 실측으로 고정)를 제대로 지킨 사례다.

아래는 **선언 목록에 없는 미도달**이다.

### H-1 [**수정 필요**] `fun interface` 가 중첩 프레임이 되면 이름을 잃는다 — **제품에 실례가 있고**, 실패 메시지가 **틀린 원인**을 지목한다

**마감: 다음 `fun interface` 안에 `data class`·`value class` 가 들어가는 커밋.**
**수신자: `kotlin-implementer`(게이트 소유 레인).**

`ProductClasses.kt:301-303` 의 `MODIFIERS` 에 **`fun` 이 없다**(직접 확인:
`public|private|internal|protected|open|final|abstract|sealed|inner|enum|annotation|expect|actual|external|override|lateinit|const|suspend|operator|infix|inline|tailrec`).
그래서 `PREFIX`(`:308`)가 `fun ` 을 소화하지 못하고, `HEAD`(`:317-325`)의 세 갈래 중
**타입 갈래가 아니라 멤버 키워드 갈래**(`|(fun|val|var|init|typealias)\b`)가 `fun interface` 줄을 잡는다
→ `TYPE_NAME` 이 `null` → **이름 없는 프레임**이 열린다.

**제품 실례 확인:** `core/src/main/kotlin/kr/easydoc/core/easyread/Prompts.kt:221`
`fun interface DocumentIdGenerator {`.

오늘은 그 안에 `data class` 가 없어 아무 일도 없다.

**직접 재현(MUT-K, 합성 소스가 아니라 제품의 그 타입에).** 일회용 worktree 에서
`Prompts.kt:221` 의 `fun interface DocumentIdGenerator` 안에 `data class Payload(val title: String)`
한 줄을 넣고 `:api:test --tests "*SensitiveToStringReachTest*"` 를 돌렸다
(결과 XML 갱신 시각이 실행 시각과 일치함을 확인 — §5.3 ⑵ 의 교훈 적용).

**같은 실행에서 두 케이스가 같은 타입을 서로 다른 이름으로 부른다:**

| 축 | 산출된 이름 | 옳은가 |
|---|---|---|
| **소스 파서**(`declaredInMainSources`) | `kr.easydoc.core.easyread.Payload` | ✗ 중첩 사슬을 잃었다 |
| **적재**(`onTestRuntimeClasspath`) | `kr.easydoc.core.easyread.DocumentIdGenerator.Payload` | ✓ |

즉 결함은 **소스 파서 한쪽에만** 있다. 게이트는 **시끄럽게 빨개진다**(그 점은 안전하다).

**문제는 진단이다.** 실제로 나온 메시지(`SensitiveToStringReachTest.kt:181-182`):

> 탐지 범위가 **선언보다 좁다.** 원인은 셋 중 하나다 — ⑴ 그 모듈이 `api` 테스트 런타임에 없다 …
> ⑵ 클래스패스 필터가 제품 산출물을 걸렀다 … ⑶ 소스 파서가 중첩 사슬을 잘못 이었다
> (**함수 본문 안의 지역 `data class`** 가 그렇다). `ProductClasses` KDoc 「못 잡는 것」 ⑷ 를 보라.

셋 중 어느 것도 원인이 아니다. `fun interface` 는 KDoc ⑴~⑷ 어디에도 없고,
⑶ 을 따라간 사람은 **있지도 않은 지역 선언**을 찾게 된다.

> 부수 관측: 내 프로브가 필드명을 `title` 로 둔 탓에 `민감 필드를 든 data class…` 도 함께 빨개졌다.
> 그것은 프로브의 부산물이지 H-1 의 일부가 아니다 — 다만 **적재 쪽 이름이 옳다는 증거**로 쓴다.

처방: `MODIFIERS` 에 `fun` 을 더하고(범위를 근거만큼만 넓힌다), `SourceScanFormsProbe` 에
`fun interface` 프레임 Form 1건을 더한다.

### H-2 [**수정 필요**] 같은 이름의 최상위 + 지역 선언이 `70d4122` 의 중복 FQCN 단언을 **오진으로** 울린다

**마감: Phase 4 종료 전.** **수신자: `kotlin-implementer`.**

`70d4122` 가 신설한 다중집합 중복 검사(`SensitiveToStringReachTest.kt:159-169`)는
`declared.groupBy { it.binaryName }.filterValues { it.size > 1 }` 이다. 그런데 파서는
지역 선언의 사슬을 함수 몸통까지 잇지 못하므로(KDoc ⑷), **한 파일 안에 최상위 `data class Local`
과 함수 안 `data class Local` 이 있으면 둘 다 `pkg.Local` 로 계산된다** → 중복 단언이 울린다.
실제 바이너리 이름은 `pkg.Local` 과 `pkg.FileKt$f$Local` 로 **다르다.**

메시지는 *"JVM 은 이 중 클래스패스에서 이긴 하나만 적재한다 … 이름을 갈라라"* 인데
그런 모호성은 존재하지 않는다. **탐지형 장치의 거짓 양성**이라 방향은 안전하지만,
거짓 양성이 반복되면 다음 사람이 이 단언을 신뢰하지 않게 된다 — 이 저장소가
게이트를 세울 때 가장 경계한 결과다. `SourceScanFormsProbe` 의 모든 Form 이 고유 이름을 써서
**다중성 자체가 프로브 밖**이다.

### H-3 [권고] 이름 그룹이 **비ASCII 식별자를 배제**한다 — 이 저장소의 관행과 정면으로 만난다

`HEAD`(`:321-322`)의 이름 그룹은 `([A-Za-z_]\w*)`. Kotlin `\w` 는 기본적으로 ASCII 이므로
`data class 사용자요청(...)` 이나 백틱 식별자는 **스캔 0건 — 조용한 누락**이다.
이 저장소는 한글 식별자를 실제로 쓴다(프로브 자신이 `` fun `잡는 형태`() ``,
테스트 메서드명이 전부 한글). 오늘 main 소스의 **타입 이름**은 전부 ASCII 라 실해는 없다.

조용한 누락의 영향은 제한적이다 — 소스 스캔은 클래스패스 스캔의 **교차 검증**이고,
누락된 타입도 `toString` 게이트 본체에는 여전히 든다. 누락은 하한
(`MIN_SOURCE_DECLARATIONS`, 실측 44)을 갉는 방향으로만 작용한다.

### H-4 [권고] 프로브 자신의 은폐 경로 — `assertForms` 가 **같은 `label` 두 Form 을 조용히 합친다**

`SourceScanFormsProbe.kt:60-62`:

```kotlin
val actual   = cases.associate { it.label to scan(it.source)… }
val expected = cases.associate { it.label to it.expected.sorted() }
```

`associate` 는 **뒤엣것이 이긴다.** 라벨이 겹치면 actual·expected **양쪽에서 같이** 떨어져 나가
대조가 성립하고 **초록이 유지된다** — 즉 Form 하나가 검사에서 사라져도 아무 신호가 없다.
오늘은 라벨이 전부 다르다. `associateBy` 대신 크기 검사(`check(cases.distinctBy { it.label }.size == cases.size)`)
한 줄이면 닫힌다. **판정하는 코드가 자기 자신을 검사 대상에 넣지 않은 자리**다.

### H-5 [권고] `sourceRoots()` 의 도달이 **Gradle 루트 한 단계**뿐이고, 하한이 그것을 잡지 못한다

`ProductClasses.kt:109-124` 는 루트 바로 아래의 `*/src/main/kotlin` 만 훑는다.
중첩 모듈(`services/auth/src/main/kotlin`)이 생기면 **0건으로 조용히 빠지고**,
`require(roots.isNotEmpty())`(`:120`)는 루트가 하나만 있어도 통과한다.
오늘 5모듈이 전부 1단계라 잠복 상태다. 「모듈 이름을 열거하지 않는다 — 새 모듈이 저절로
들어온다」(`:70` 주석)의 범위가 **한 단계 깊이까지**임을 KDoc 이 적지 않는다.

### H-6 [권고] KDoc 「못 잡는 것」 목록이 **실제 미도달보다 좁다**

⑴~⑷ 넷을 열거하고 *"넷 다 오늘 main 소스에 0 건"* 이라 적는데, 위 H-1(`fun interface`) ·
H-2(다중성) · H-3(비ASCII)이 목록에 없다. 특히 H-1 은 **제품에 실례가 있으므로**
*"넷 다 0건"* 이라는 문장과 나란히 두면 독자가 "미도달은 전부 0건" 으로 읽는다.
KDoc 이 정직하게 목록을 둔 것 자체는 옳으므로 — **목록을 늘리는 것이 처방이지 걷어내는 것이 아니다.**

---

### 4.5 ⓐ 하네스 레인 — 원장 · 스캐너 · CI 관측

> 이 절의 A·B·C1~C3 은 **병렬 검증 레인의 실측을 리더가 릴레이**해 받았다. 아래 L-1 은
> 릴레이 요지에만 기대지 않고 **내가 본 저장소에서 직접 확인**했다(명령·출력 병기).

### L-1 [**차단 — ②장치**] 심판 산출물(`reviews/`)을 **피심판 커밋이 편집**했다 — 게이트 근거가 위조 가능한 통로에 있다

**마감: 다음 게이트 판정을 `reviews/` 근거로 내리기 전(= 이 게이트 25 판정 자체).**
**수신자: 리더 · `privacy-gate`.**

**직접 확인:**

```
$ git log --oneline --follow -- docs/migration/_workspace/reviews/03_security-scanner_privacy-gate.md
6be9612 fix(harness): 403 밑줄 결합 상수 2형태를 명시 토큰으로 잡는다 (게이트 24 ⓐ)
01d78a1 fix(scanner): OWNERSHIP-403 제외에 이름 관문을 건다 — 네 형태 탐지 복원
ea36330 fix(gate): 스캐너 BLOCK 8건 — OWNERSHIP-403 정밀화 + 상수 개명

$ git show --stat 6be9612
 .../scripts/scan_privacy_invariants.py             |  33 +++-
 .../reviews/03_security-scanner_privacy-gate.md    | 122 +++++++++++++++++++
 tests/test_privacy_scanner.py                      |  95 ++++++++++----
```

`6be9612` 는 **스캐너를 고치는 커밋**이면서, 같은 커밋으로 **그 스캐너를 심판한
`privacy-gate` 산출물**에 §8 「재판정」 122줄을 덧붙였다. 그 §8 은 §5-1·§7-4 의
**옛 판정을 좁혀 다시 적는다** — 즉 **심판받는 쪽이 심판문을 개정**했다.

**왜 ② 인가.** `reviews/` 는 리더가 Phase 게이트를 판정할 때 읽는 **증거 기반**이고,
이 에이전트의 규약도 *"리더에게 게이트 판정 근거로 올리는 정본은 `..._cross.md`"* 라 적는다.
저작 레인이 그 디렉터리에 쓸 수 있으면 **"재판정: 통과" 를 스스로 적는 경로가 열려 있다.**
프로젝트 `CLAUDE.md` 와 전역 규약이 금지한 **자기 승인**의 구조적 형태다.
차단 척도의 ② 예시 *"위조 가능한 증거 파일"* 에 그대로 해당한다.

**한 번이 아니라 종류다(규칙 4).** 같은 파일을 건드린 앞선 두 커밋(`01d78a1`·`ea36330`)도
`fix(scanner)`·`fix(gate)` 로 **구현 레인 모양**이다. 열거 가능한 한 자리가 아니라
**「저작 커밋이 reviews/ 를 쓴다」는 종류**가 반복되고 있다.

**이번 사례의 내용은 정직하다** — §8 이 *"옛 판정을 지우지 않는다 … 번복이 아니라 범위 분리"*
라 적고 리더 재판정을 인용한다. **그래서 착수 차단 여부는 리더 판단으로 넘긴다.**
내가 차단으로 올리는 것은 **내용이 아니라 통로**다.

**처방 후보(넓히지 않는 형태).** ⑴ `reviews/` 를 저작 레인의 쓰기 대상에서 빼고 재판정은
**심판 레인의 새 파일**(예: `03_security-scanner_privacy-gate_v2.md`)로 받는다.
⑵ 그것이 과하면, 최소한 원장이 **"이 커밋이 심판 산출물을 편집했다"를 기록**하게 한다 —
지금은 **원장에 기록이 없다.**

### L-2 [판정 자료] ⓐ 10커밋의 독립 리뷰 수령 — **10/10 미수령**

커밋된 `reviews/` 기준으로 아래 10커밋 중 **독립 리뷰를 받은 것은 0건**이다.
이번 게이트 25 의 3산출물(codex · 이 파일 · privacy-gate)이 **첫 리뷰**이고,
원장의 「Phase 4 착수 조건 1」이 그대로 남아 있다.

| 커밋 | 리뷰 수령 |
|---|---|
| `6be9612` · `dec3124` · `44eec3f` · `eb075f1` · `70d4122` | 미수령 |
| `f3de501` · `3ea1983` · `70ec78f` | 미수령 |
| `b66fa46` · `7fb47ee` (원장) | 미수령 |

내 `ls docs/migration/_workspace/reviews/` 결과와 일치한다 — 최신 파일이 `03_phase3-close_*` ·
`03_workspaces_*` 계열이고 `04_*` 는 이번 게이트가 처음이다.
**지적이 아니라 리더가 요청한 판정 자료다.**

### L-3 [**수정 필요**] 스캐너 403 처방이 **종류가 아니라 인스턴스 2개**만 넓혔고, 남은 미도달을 **선언하지도 않았다**

**마감: Phase 4 문서 소유권 경로(403/404) 진입 전.** **수신자: `privacy-gate` → 리더.**

릴레이된 실측:

- 변이 ⑴ `SC_FORBIDDEN` 토큰 삭제 → `pytest` **4 failed (RED)** — 추가분은 회귀로 고정돼 있다.
- 변이 ⑵ `response.sendError(HttpURLConnection.HTTP_FORBIDDEN)` **주입 → OWNERSHIP-403 BLOCK 0.**
- 로컬 `= 403` 4형태는 **선언 줄만** 잡히고 **사용처는 무적중**.

`6be9612` 커밋 메시지는 *"맨 `sendError` 는 토큰이 아니다(범위는 근거를 넘지 않는다)"* 로
남는 미도달을 `sendError(computed)` 하나로 한정했다. **그 한정이 라이브러리 상수에는 성립하지 않는다** —
`HttpURLConnection.HTTP_FORBIDDEN` 은 계산값이 아니라 상수인데 무적중이다.
게다가 **이번에 더한 `SC_FORBIDDEN` 자신이 라이브러리 상수**라, 같은 종류가
**다음 라이브러리 상수에서 그대로 재발**한다. 규칙 4 의 판정 기준(횟수가 아니라 결함의 구조)으로
보면 이것은 **구조적 재발**이고, 그러면 넓힘은 **인스턴스가 아니라 종류만큼**이어야 한다.

**두 번째 문제 — 잔여가 선언돼 있지 않다.** 같은 커밋이 `xfail(strict)` 2건을 정상 통과로
전환하면서, 새로 확인된 미도달(라이브러리 403 상수)에 대해서는 `xfail(strict)` 도
`reached=False` 선언도 **0건**이다. 미도달이 **아무 데도 적히지 않은 상태**가 됐다 —
전환 전보다 나빠진 축이다.

### L-4 [권고] 원장 `:1419` 「연속 2실행 success」가 **job 결론과 run 결론을 한 행에서 섞는다** — 게이트 24 R-4 의 재발

릴레이된 `gh run view` 실측: run `32229496368`(`f3de501`) · run `32230037832`(`3ea1983`) 모두
**e2e job 은 success**(각 3m53s / 4m21s, 12 passed)이고 수치도 문서 주장과 일치한다 —
"설치 53초"(apt 39초 + 브라우저 14초, 캐시 miss) · "브라우저 설치 skipped"(캐시 hit) **전건 참**.

그러나 **두 run 의 전체 conclusion 은 `cancelled`** 다(llm-lane concurrency 취소).
원장 `:1419` 는 "연속 2실행 success" 라고만 적어 **job/run 표기가 한 행에서 갈린다.**
게이트 24 R-4 에서 같은 형태를 이미 지적했다(원장이 승격 근거로 인용한 run 의 전체 결론이 `cancelled`).
**같은 자리가 다시 났으므로 표기 규약을 정하는 편이 낫다** — "job success / run cancelled(사유)".

### L-5 [권고] 원장 `:1405-1406` 열거가 `eb075f1`·`70d4122` 를 빠뜨렸다

Minor. 이후 열린 범위 표기로 구조 교정됐다.

### 검증 통과 — ⓐ 변이 2건

| 항목 | 실측 | 판정 |
|---|---|---|
| **C2** toString value class 1번 파라미터 | 첫 파라미터가 value class 인 DTO 에 누출 주입 → **RED 2건**(`SensitiveToStringReachTest.kt:115`·`:131`), 대조군 GREEN | **A-3′ 실제로 닫힘** |
| **C3** 바이너리 이름 중복 선언 | 동일 FQCN 2선언 → **RED**(`:161`) | 닫힘. 단 **단방향(declared→loaded)** 부기 — 내 **H-2**(거짓 양성)와 같은 뿌리 |

---

## 5. Phase 4 종료 조건 대비 현황 / 미실행·확인 불가

### 5.1 Phase 4 종료 조건(§5 `:411-420`) 대비

| 조건 | 현황 |
|---|---|
| 저장 암호화 round-trip·변조 거부 (I-7) | **이 단위에서 충족.** 17건 + 음성 대조 4건 독립 재현. 잔여 R-1(2축 격리 증거) |
| `encryption_scheme`/`key_version` 회전 (I-7-5 · I-8) | **잠정 위반 해제 확인.** V3 가 CHECK·DEFAULT 를 정정하고 실측 프로브로 강제 확인. 잔여 R-6(키 값 무단 교체 무탐지) |
| 문서 API·파서·내보내기 | **미착수**(산출물 §7ⓔ). 이 단위 범위 밖 |
| "평문이 DB·로그에 없음"(실 업로드→변환→내보내기 후 로그 전문 grep) | **미실행 — 경로 없음.** 산출물 §7ⓔ 가 정직하게 신고. 이 단위가 확인한 것은 암호 서비스 자신의 로그뿐 |
| `conversions` 세 컬럼 ↔ 행당 세대 하나 | **리더 판정 완료(②행 단위 재암호화)**, 배선은 다음 단위. 이 단위 코드는 어느 쪽도 막지 않는다 — `ContentCipher` 에 세대 인자가 없어 저장 계층이 규율을 진다는 §7ⓐ 의 진단이 코드와 일치함을 확인 |

### 5.2 미실행·확인 불가 (이 회차)

| # | 항목 | 사유 |
|---|---|---|
| 1a | ⓐ `SourceScanFormsProbe` 4형태 + 소스 파서 도달 | **완료 — §4.4(H-1~H-6).** H-1 은 제품 타입에 직접 재현(MUT-K) |
| 1b | ⓐ **스캐너 명시 토큰**(`6be9612`) 변이 | **완료 — L-3.** 삭제 시 4 failed(RED) / 라이브러리 상수 주입 시 BLOCK 0 |
| 1c | ⓐ **toString value class 1번 파라미터** 변이 | **완료 — §4.5 검증 통과 C2**(RED 2건, 대조군 GREEN). `PlainBody` 경로는 내가 MUT-G 로 별도 재현 |
| 1d | ⓐ **바이너리 이름 중복 선언** 변이 | **완료 — §4.5 C3**(RED). 거짓 양성 갈래는 내 H-2 |
| 1e | ⓐ **헤더 인라인 주입**(`44eec3f` 파서 fail-closed) 변이 | **미실행.** 인라인 선언 고정 자체는 `ContractHeaderDeclarationTest` 113/0 재현으로 확인했으나, **파서를 다시 열어 두는 변이는 돌리지 않았다** |
| 2 | **CI run 실재** | **완료 — L-4.** 두 run 실재·수치 전건 일치. M-3b 의 **CI 초록 관측은 여전히 미확인**(커밋이 오늘자라 해당 run 없음) |
| 3 | **원장 정직성** | **완료 — L-1·L-2·L-4·L-5.** `7fb47ee` 기록은 정확(HEAD `b66fa46` = `7fb47ee`^) |
| 4 | `ktlintCheck detekt build --continue --rerun-tasks` **exit 0** 주장 | 미실행. 나는 `:*:test`(729건 GREEN)와 `moduleBoundaryCheck` 만 재현했다 |
| 5 | Python 전체 게이트(`ruff`·`mypy . .claude`·`pytest` 1281 passed) | 미실행. **`app/**` 무변경만 diff 로 확인** |
| 6 | M-3b 의 101표본 시간 실측(비 1.027) | 미실행. 코드·상수(101/20/1.5/시드 20260819)와 계약 참조 방식은 읽어 확인 |
| 7 | 두 세대에 **같은 키 재료**를 넣었을 때의 실동작 | 미실행(R-1 처방 후보라 구현 레인 몫으로 남긴다) |
| 8 | `worker` 컨텍스트의 암호화 빈 조립 | 미실행 — 테스트가 존재하지 않는다(R-2) |

### 5.3 이 회차에서 정정한 내 오판 2건

§2.0 에 적었다. `04_kotlin-implementer_crypto.md:249`(§6-E)가 지목한 케이스를 클래스 하나에서만
찾다 "존재하지 않는 테스트" 로 판단했으나, `PythonSchemaBaselineTest.kt:120` 에 실재하며
두 클래스를 함께 돌리면 **주장대로 정확히 3 red** 다. **산출물이 옳고 내 첫 판단이 틀렸다.**
남기는 이유는 R-9(표기)의 근거이자, 이 회차의 실측이 어디서 흔들렸는지를 2차가 볼 수 있게 하기 위해서다.

**⑵ 무효 실험 1건(MUT-J) — 결과를 쓰지 않았다.** `EncryptionKeyProperties.value` 의 타입을
`Secret` → `String` 으로 바꿔 `SensitiveToStringReachTest` 의 도달을 재려 했으나,
`ConfigurationPropertiesBindingTest.kt:87` 의 `.reveal()` 때문에 **`:api:compileTestKotlin` 이 실패**했다.
그런데 내가 읽은 `TEST-…SensitiveToStringReachTest.xml` 은 **직전 기준선 실행의 잔재**였고
"4/4 GREEN" 으로 보였다. `gradle exit=1` 을 함께 확인하지 않았다면 **컴파일도 안 된 변이를
「게이트가 못 잡는다」로 보고할 뻔했다.** 이 리뷰가 다른 레인에 요구하는 것과 같은 결함
(대리 경로에서 측정하고 그 결과를 통과 근거로 삼음)이라 그대로 적어 둔다.
교훈은 `04_crypto_cross.md` 로 넘긴다 — **변이 실험은 종료 코드와 산출물 타임스탬프를 함께 봐야 한다.**

---

## 6. 수신자별 요약

| 수신자 | 항목 |
|---|---|
| `kotlin-implementer` | **R-1**(AAD 2축 음성 대조 — KAT 1건이면 R-3 까지 동시 해결) · **R-2**(`CryptoConfiguration` 테스트) · **R-3**(`wireName` 탐지기) · R-4 · R-6 · R-7 · R-8 · R-9 · **H-1**(`MODIFIERS` 에 `fun`) · **H-2**(중복 단언 거짓 양성) · H-3 · H-4 · H-5 · H-6 |
| `privacy-gate` | R-1 · R-3 · R-4 · R-5 · R-6 (I-7 판정 우선권은 privacy-gate 에 있다) · 산출물 §7ⓓ(I-7 예시명 `aes-gcm-v1` vs 실제 `aes256gcm-v1`) · **L-1**(자기 산출물이 편집당한 쪽) · **L-3**(403 탐지 종류 확대) |
| `contract-keeper` | **C-1** · **C-2** · **C-3** · C-4 · C-5 · C-7 · C-8 |
| 리더 | **L-1 차단의 착수 차단 여부 판정**(통로 vs 이번 내용) · **L-2 리뷰 수령 판정(10/10 미수령)** · **R-3 의 차단 승격 여부**(논거 양쪽 병기) · L-4(원장 job/run 표기 규약) · L-5 · C-6(원장 X-A2 미처리) · §5.2 의 미실행 항목 · toString 게이트 확대 여부(R-10)는 게이트 소유 레인 배정 |
| `parity-verifier` | 대상 없음(§4.1). C-1 의 `\`·C1 입력 커버리지 0 은 fixture 축에도 걸린다 |

**2차(교차 종합) 재호출이 필요하다.** codex 산출물(`04_crypto_codex-reviewer.md`)이 도착하면
이 파일과 대조해 `04_crypto_cross.md` 를 쓴다. **Phase 4 게이트 판정은 그 정본으로 올린다** —
이 1차 산출물만으로 종료 조건 충족을 보고하지 않는다.
