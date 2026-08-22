# 04_documents-c6 — Claude 단독 리뷰 (1차 · 교차 대조 미수행)

**회차**: 1차 독립 리뷰. **codex 산출물을 읽지 않았다**(같은 시점에 codex 레인이 독립 리뷰를 쓴다 —
리더 지시). 교차 종합은 3단계 재호출에서 `..._cross.md` 로 한다. **이 파일만으로 Phase 종료 조건
충족을 보고하지 않는다.**

**심판 대상**: `65882bc`(X1 처방 + β 8건) · `e2038dd`(대조 프로브 + 파국적 백트래킹) ·
`318bd36`(C6 본체). 범위 밖 15커밋(`reviews/xx_harness*` 가 이미 심판)은 심판하지 않고 **전제로만**
읽었다. 문서 전용 `fd00c38`·`0075743` 면제.

**리뷰 축**: 리더가 지정한 셋 — ⑴ 보안·개인정보 불변식 ⑵ 외부 HTTP 계약 ⑶ 게이트·탐지기 자신 +
「선언한 범위와 실제 도달」. 나머지 두 축(parity 위험 · Kotlin/Spring 관용성)은 **이번 회차의 범위가
아니다**(미검토 — 리더 지정).

**참조**: 계획 §2.2·§2.3·§3.2·§4.5·§4.6·§5 Phase 4·Phase 7 즉시 중단 기준·§6 Contract 게이트 ·
`.claude/skills/kotlin-migration/SKILL.md` 「선언한 범위와 실제 도달을 대조한다」(규칙 6개 + 실패 7건)
및 새 리뷰 게이트 절 · `contracts/easy-doc-v1.yaml`(무수정 확인) ·
`04_contract-keeper_documents-test-spec.md`(CR 표 · X-D1) ·
`04_kotlin-implementer_conversion-read.md` / `..._conversion-read-backlog.md` / `..._c6-preconditions.md` ·
`00_progress.md` L-㉘.

**내가 직접 실행한 것**: `uv run pytest tests/test_kotlin_gate_reach.py` → **281 passed · skip 0 ·
21.02s**(커밋 시점 「279 passed · 2 skipped」가 예고한 대로, 새 표·새 상한이 HEAD 이력에 들어와 두 축이
이제 실제로 돈다). 그 밖은 소스 읽기와 grep 실측이다. **변이(음성 대조)는 수행하지 않았다 — 코드 수정
금지 · 공유 작업 트리 · 미커밋 5건.**

---

## 1. 심각도 척도와 이 회차의 셈법

`agents/migration-reviewer` 의 척도를 그대로 쓴다. **차단**은 두 갈래를 같은 무게로 센다 —
**① 사건**(§5 Phase 7 즉시 중단 기준에 해당하는 일이 실제로 일어나는 경로) / **② 장치**(그 사건을
탐지·차단하는 게이트가 무력화된 상태). 차단 항목에는 **마감**(그 게이트가 처음 실제로 쓰이는 Phase)을
함께 적고, **착수 차단 여부의 판정은 리더에게 넘긴다.**

이 회차의 결론을 먼저 적는다 — **① 사건은 하나도 찾지 못했다.** 소유권 은닉·평문 노출·마스킹 범주
확대에 해당하는 실제 경로를 셋 다 뒤졌고, 새 조회 경로는 실 PostgreSQL·실 두 계정·실 키로 404 를
낸다(CR-7·CR-8). **찾은 것은 전부 ② 장치 쪽**이고, 그중 셋을 차단으로 올린다.

---

## 2. 축 ⑴ — 보안·개인정보 불변식

### S-1 [차단 ② · 마감 이 게이트] 대조 프로브가 여섯 실사용 자리의 **어댑터**를 지나지 않는다

`e2038dd` 는 「합침이 만든 새 단일 실패점」을 정확히 진단하고 `DocumentListReachTest` 에 대조 프로브를
세웠다. 그런데 **프로브가 지나는 것과 실사용 자리가 지나는 것이 다르다.**

- 프로브는 `OwnershipConcealment.Observation(...)` **생성자를 직접** 부른다
  (`backend-kotlin/api/src/test/kotlin/kr/easydoc/api/DocumentListReachTest.kt:167-197`).
- 실사용 여섯 자리는 전부 `assertIndistinguishable(label, HttpResponse, HttpResponse)` →
  `observe(response)` 를 지난다
  (`support/OwnershipConcealment.kt:31-35`, `observe` 는 `:18-19`).
  실측 호출자: `DocumentListReachTest:163` · `DocumentEndpointReachTest:230` ·
  `ConversionReadReachTest:210` · `DocumentDeleteReachTest:134` ·
  `WorkspaceEndpointReachTest:178`·`:281`.

`observe` 를 `Observation(response.statusCode(), ByteArray(0), emptySet())` 로 만드는 **한 줄**이면:
두 팔이 **대칭으로** 뭉개지므로 여섯 자리는 전부 초록이고 실제로는 **상태 코드만** 비교한다. 프로브는
`Observation` 을 손으로 만들므로 그 변이를 볼 수 없다. grep 실측: `observe` 를 직접 검증하는 케이스
**0건**.

즉 e2038dd 는 단일 실패점을 **없앤 것이 아니라 `assertIndistinguishable` 에서 `observe` 로 옮겼다.**
X1-1 이 판정을 한 벌로 합친 그 순간의 위험이 그대로 남아 있고, 이 파일은 스스로 적은 대로 JUnit
애너테이션이 없어 개수·단언 하한 표 분모 밖이다.

- **악용 비용** 1줄 · **자동 탐지** 없음.
- **왜 ② 인가**: 여섯 자리 중 둘이 §5 Phase 7 즉시 중단 기준(「타 사용자 노출·404 위반」)의 유일한
  실행 관측이다. 그 관측이 상태 코드만 남으면 본문 문구로 존재를 흘리는 사건이 초록으로 통과한다.

### S-2 [차단 ② · 마감 이 게이트] `VARIABLE_HEADERS` 는 은폐형 면제 목록이고 증가를 재는 것이 0이다

`support/OwnershipConcealment.kt:9` — `val VARIABLE_HEADERS: Set<String> = setOf("date")`. 이 집합은
**양쪽에서 빠지는** 헤더 이름이므로, 누출 헤더 이름 한 낱말을 더하면 여섯 자리의 헤더 축이 그 헤더에
대해 눈이 먼다.

- 이 파일은 테스트 클래스 분모 밖(e2038dd 가 스스로 적은 사실)이고, `RATCHET_SCALAR_PINS` ·
  `RATCHET_TABLE_PINS` · `RATCHET_CEILING_PINS` **어디에도 없다**(grep 실측: `tests/`·`.claude/` 에
  `OwnershipConcealment` 언급 0건).
- `CLAUDE.md` 규칙 4 는 **은폐형은 ⑴이 참이어도 넓히지 않는다 — 탐지형으로 갈아탄다**고 정하지만, 그
  「넓힘」을 재는 장치가 없다. X1-1 이 네 파일에 갈려 있던 것을 여기 합쳤으므로 **한 낱말의 영향 범위가
  네 배로 커졌다.**
- **악용 비용** 1낱말 · **자동 탐지** 없음.

### S-3 [판정 필요] P1 은 헤더 **이름** 집합만 본다 — 선언은 「응답 구별 불가」다

`support/OwnershipConcealment.kt:58-64` 가 비교하는 것은 `headerNames` 집합이다. 값은 비교하지 않는다.
본문 바이트 동일성이 `content-length` 를 간접으로 묶지만, **값으로만 갈리는 헤더**(두 팔에서 다른
`Vary`·`ETag`·향후 진단 헤더)는 초록이다.

X1-1 처방이 지목한 셋이 「상태 · 원시 바이트 · 헤더 이름 집합」이므로 **처방 준수 자체는 참**이다. 문제는
KDoc 첫 줄이 「소유권 은닉의 성질 P1 — 「응답 구별 불가」의 판정 한 벌」이라는 **전칭**을 쓴다는 것이다.
값 축이 필요한지는 `privacy-gate` 의 판정 사항이라 여기서 정하지 않고 올린다.

### S-4 [판정 필요 · **리더 판정 ⑴ 평가**] M-3 ⒜ 「부분」 정정은 옳다 — 근거를 하나 더 얻는다

**리더의 판정(「조건 ⒜ 부분 미충족 · 강제자 0 · 악용 비용 호출 한 줄」)은 실측으로 옳다.** 더 나쁘지도
덜 나쁘지도 않으나, **강제자 0 의 성질이 다르다** — 「아직 안 만들었다」가 아니라 **구조적으로 그렇다.**

실측 셋:

1. 제품 호출자는 정말 하나다. grep 전수(`backend-kotlin --include='*.kt'`, `/src/test/` 제외):
   `EnvelopeRotation.kt:34`(`lockSourceText`) · `:45`(`lockEnvelope`) 뿐. 선언은
   `DocumentPorts.kt:61`·`:129`, 구현은 `JdbcDocumentRepository.kt:59`·`JdbcConversionRepository.kt:62`.
2. **그 두 문장은 `OwnershipPredicateGuardTest.EXPECTED_UNGUARDED` 에 이미 들어 있다** —
   `JdbcConversionRepository.kt | SELECT [conversions]` 와 `JdbcDocumentRepository.kt | SELECT [documents]`
   (`infrastructure/src/test/.../db/OwnershipPredicateGuardTest.kt:397-406`).
3. 그 인구조사가 세는 것은 **SQL 문장**이고 **호출자**가 아니다(`Scanner.scan` 이 제품 소스의 SQL 청크를
   훑는다, `:281-349`). 따라서 **이미 핀에 든 문장을 새 사용자 경로에서 부르는 편집은 인구조사에 아무
   변화도 만들지 않는다.** 「호출 한 줄 · 탐지 0」은 가드의 설계에서 곧바로 따라 나온다.

**리더 판정에 더할 것 하나**: 같은 성질이 `JdbcWorkspaceRepository.kt | SELECT [documents]`
(`EXPECTED_UNGUARDED` 첫 항목, 실측 `JdbcWorkspaceRepository.kt:126`
`SELECT count(*) FROM documents WHERE workspace_id = :workspaceId`)에도 있다. 즉 「호출 한 줄로 남의
행에 닿는 이미-핀된 문장」은 **둘이 아니라 셋**이다.

C7 에 배정한 구조 단언(「이 두 포트는 `EnvelopeRotation` 에서만 참조된다」)이 정확히 그 빈자리를
겨눈다 — 배정은 타당하다. **다만 대상을 셋으로 넓혀야 하고**, 그 사이 창의 크기는 「한 줄 · 탐지 0」이다.

### S-5 [차단 ② · 마감 이 게이트] `EXPECTED_UNGUARDED` 에 **상한 라쳇이 없다** — 새 기제를 만들면서 가장 필요한 자리를 넣지 않았다

X5 가 이 커밋에서 `RATCHET_CEILING_PINS`(「현재 ≤ 이력 최솟값」)라는 **상한 라쳇 기제를 새로 만들었다.**
그 표에 든 항목은 `SCANNER_TIME_BUDGET_SECONDS` **하나**다
(`tests/test_kotlin_gate_reach.py:831-833`).

그런데 이 저장소에서 「올라가면 보호가 줄어드는 값」의 교과서적 예는 **미방어 SQL 문장 목록의 길이**다.
현재 상태:

- `EXPECTED_UNGUARDED` 는 `isEqualTo` 정확 열거라 항목을 **더하면** 그 한 줄이 diff 에 남는다. 그러나
  **판정하는 실행이 없다** — 새 소유자 인자 없는 사용자 경로 SELECT + `EXPECTED_UNGUARDED` 한 줄 =
  **2줄 · 전건 초록**.
- 어느 라쳇 표에도 없다(grep 실측: `RATCHET_SCALAR_PINS` 의 Kotlin 튜플 다섯은
  `SensitiveToStringReachTest.MIN_PRODUCTION_CLASSES` · `StatementCountingPremiseTest.MIN_PORT_ADAPTERS` ·
  `FlywayBaselineGuardTest.MIN_CRITICAL_STATEMENTS` · `PostprocessTest.MIN_NEGATIVE_CASES` ·
  `JdbcDocumentStoreTest.MIN_DOCUMENT_COLUMNS` 이고 `OwnershipPredicateGuardTest` 는 없다).

**β-08 과 같은 칸이다.** β-08 에서 리더는 「침묵을 diff 에 남는 허위 사유 문장으로 바꾸는 것」과
「침묵」이 다른 칸이라고 판정하면서도, **지금 상태로 백로그에 두는 처분은 어느 독해에서도 지지되지
않는다**고 결론했고 **차단으로 확정**했다. 같은 기준을 그대로 적용하면 이 항목도 그 칸이다 — 그리고
이쪽은 지키는 것이 「테스트 개수」가 아니라 **소유권 우회 문장의 개수**다.

- **악용 비용** 2줄 · **자동 탐지** 없음(부분 탐지: 리뷰가 diff 를 본다는 것뿐).

### S-6 [권고] `ConversionDtos.kt:26` 의 「유일한 호출」이 **거짓 전칭**이다

```kotlin
// 이 저장소에서 가린 값이 평문 문자열이 되는 **유일한** 호출이다.
original = item.original.reveal(),
```

grep 전수(`.reveal()`, `/build/` 제외)로 제품 소스의 같은 성질 호출이 **셋**이다 —
`api/.../ConversionDtos.kt:27` · `infrastructure/.../MaskedItemCodec.kt:28`(`encode` 가 저장용 JSON 에
원값을 넣는다) · `core/.../Masking.kt:364`(자리표시자를 원값으로 되돌린다). 테스트 쪽에도
`api/src/test/.../support/DocumentSliceFakes.kt:308` 이 있다.

문면을 좁혀 읽으면(「`MaskedItemView.original` 이 **응답 본문**의 평문 문자열이 되는 유일한 자리」)
참이지만, 적힌 것은 「이 저장소에서 … 유일한 호출」이다. 개인정보 감사자가 `reveal()` 자리를 셀 때 읽는
문장이므로 **거짓 전칭의 대가가 크다.** 이 회차의 규칙(「범위 선언형은 빈 선언에서 통과하면 안 된다」)과
같은 계열이고, 강제자는 없다.

### S-7 [검토함 — 지적 없음] 복호화 평문이 로그·응답 밖으로 나가지 않는다

- `ConversionQueryService.open`(`:50-54`)은 열이 NULL 이면 복호화하지 않는다. `ContentCipher.decrypt` 는
  트랜잭션 밖에서 부르고 읽기 한 문장만 경계 안이다(`:25`).
- 실패 로그가 값을 담지 않는다: `JdbcConversionRepository.malformedPlaceholders` 는 컬럼 이름 + 사유
  토큰(`not-an-array`·`element-not-a-string`) 또는 **예외 클래스 이름**만
  (`:149-176`, `exc::class.java.simpleName`). `MaskedItemCodec.malformed` 도 같다(`:40`,`:71-74`).
- `toString` 넷을 손으로 썼다 — `ConversionView`(본문 없음, 개수만) · `StoredConversion`(같음) ·
  `ConversionResponse`(본문 둘을 표식 + 길이, 마스킹 항목은 개수만) · `MaskedItemResponse`(원값 마스크).
  `SensitiveToStringReachTest` 핀 53→57 로 넷이 그 인구조사 분모에 들었고, 산출물 §6 이 그 가드가 실제로
  둘을 잡았다고 적는다.
- 오류 문구 둘(`요청을 처리하지 못했습니다` / `저장된 변환 결과를 읽을 수 없습니다`)은 값을 담지 않는다.

### S-8 [검토함 — 지적 없음] 마스킹 범주가 2종보다 넓게 적힌 자리 없음

구현 `MaskCategory` = RRN·CARD(`MaskedItemCodec.CATEGORY_KEYS:78-82` 가 그 둘만 매핑) / 계약
`MaskedItemResponse.category.enum` = `["주민등록번호","카드번호"]`(`contracts/easy-doc-v1.yaml:2390`).
그리고 이 커밋이 **완전성 케이스를 새로 세웠다** —
`ConversionReadContractTest:118-129` 가 계약 enum ⊇ `MaskCategory.entries` 를
`containsExactlyInAnyOrderElementsOf` 로 잰다. 이것이 N-26(계약을 좁히면 심는 것과 재는 것이 함께 줄어
자기 일관되게 통과) 의 정확한 처방이다.

### S-9 [권고] 계약 `placeholder.pattern` 이 범주 이름을 정규식 안에 복제하고, 그 복제를 재는 것이 없다

`contracts/easy-doc-v1.yaml:2400` — `^\[\[(주민등록번호|카드번호)[0-9]+\]\]$`. S-8 의 완전성 케이스는
`category.enum` 만 본다. 범주가 늘면 `enum` 은 빨개지고 **pattern 은 조용하다**(그 뒤 CR-5 가 새 범주를
심으려 할 때 간접으로 잡히지만, 그것은 이 pattern 을 겨눈 판정이 아니다). `missing_placeholders.items.pattern`
(`:2470` 부근)도 같은 복제다.

---

## 3. 축 ⑵ — 외부 HTTP 계약

계약 파일은 이 범위에서 **무수정**임을 확인했다(`git show 318bd36 --stat` 에 `contracts/` 없음. 산출물
§5 의 sha256 진술은 직접 재현하지 않았다).

### K-1 [차단 ② · 마감 이 게이트] **X-D1 하한선 셋째 자리(CR-1)가 하한선을 재지 않는다 — G-β X2 에서 리더가 이미 뒤집은 판정의 재발**

`ConversionReadContractTest` 가 `@WebMvcTest` + **`@Import(PrivateResponseHeadersConfig::class, AuthSliceBeans::class)`**
(`:35-36`)로 **전역 헤더 필터를 컨텍스트에 넣는다.** 그 config 는 모든 응답에 두 헤더를 무조건
`setHeader` 하는 서블릿 필터를 등록한다
(`api/src/main/.../config/PrivateResponseHeadersConfig.kt:24-30`, `:49-59`).

따라서 CR-1 의 `assertPrivateHeaders`(`:216-222`)가 재는 것은 **「컨트롤러의 개별 부착」과 「전역 필터」의
OR** 이다. `ConversionController.kt:31-32` 의 두 `.header(...)` 줄을 지워도 전건 초록이다.

**형제 자리는 정확히 반대로 되어 있다.** `DocumentListHeaderFloorTest` 는
- 그 config 를 **넣지 않고**(`:19-22` — *"전역 장치를 뺀 컨텍스트에서 잰다"*),
- **전역 장치가 실제로 없음을 확인하는 대조 케이스**까지 둔다(`:54-66` — *"있으면 위 단언이 개별 부착을
  재지 못한다"*).

그 케이스는 G-β X2 에서 **리더가 판정을 뒤집어** 바닥에 넣은 것이다(`00_progress.md` L-㉗ /
`8475e2a`). 즉 이 결함 종류는 이미 한 번 판정됐고, C6 이 같은 형태를 다시 만들었다.

**계약과 명세가 마감을 못 박고 있다**:
- `contracts/easy-doc-v1.yaml:1114-1117` — *"전역 규칙이 있는데도 이 목록을 남기는 이유: 필터가
  제거되거나 체인 순서가 어긋나도 **고위험 경로에서 테스트가 먼저 깨져야** 하기 때문이다(2026-08-12
  리더 판정 부수 결정 1). 이 10곳의 개별 contract test는 삭제하지 않는다."*
- `:1132` — `GET /conversions/{conversion_id}` 가 그 10 곳에 있다.
- `04_contract-keeper_documents-test-spec.md:515` — *"DL-1·CR-1·CU-1·CE-2의 사적 헤더 **개별** 단언
  (X-D1 하한선 남은 4곳) | **해당 엔드포인트를 구현하는 그 커밋** | 리더 판정 부수 결정 1."*
  **이 커밋이 그 커밋이다.**
- `:199` — *"CR-1이 X-D1 하한선의 셋째 자리다."* 테스트의 `@DisplayName`(`:56`)도 그렇게 선언한다.

**부수 — 10 곳을 케이스에 대응시키는 인구조사가 0이다.** `ContractSpec.privateResponseHeaderTargets()`
의 소비자는 `RequestFieldConstraintLayerTest:162` 하나이고 `/auth` 세 항목의 `contains` 만 본다. 그래서
「하한선 자리마다 하한선 케이스가 있는가」를 재는 실행이 없고, 그것이 이 누락이 조용히 통과한 기제다.
**열거를 열거로 지키는 형태**이므로 처방은 인구조사여야 한다(리뷰어는 처방하지 않는다 — 판정은 리더).

- **악용 비용** 2줄(컨트롤러 헤더 삭제) · **자동 탐지** 없음. 사건이 되려면 전역 필터도 함께 깨져야
  하므로 **2중 결함**이다 — 그러나 하한선 조항의 존재 이유가 정확히 「2중이 되게 하는 것」이다.

### K-2 [권고 → 판정 필요] `PUT /conversions/{conversion_id}` 의 **미선언 405 표면이 이 커밋에서 새로 생긴다**

C6 전에는 그 경로에 매핑이 없어 어떤 메서드든 404 였다. 지금은 `GET` 매핑이 있으므로
`PUT /conversions/{id}`(계약이 선언하지만 미구현)은 **405 + `Allow: GET`** 이 된다.

- 계약의 `put` 오퍼레이션 응답 목록(`:1543-1600`)에 **405 가 없다**. 계약은 405 를 「프레임워크 오류
  핸들러가 만드는 것」으로 재분류하고 별도 조항을 만들지 않기로 했다(`x-405-reclassification`, `:1056-1077`)
  — 그 처분 자체는 이 커밋의 결함이 아니다.
- **문제는 인증 순서다.** 메서드 매칭은 핸들러 매핑에서 일어나므로 405 는 **인터셉터 체인이 시작되기
  전에 확정된다**. 즉 **무인증 호출자도** 그 경로의 구현된 메서드 집합을 알게 된다. 계약에 정확히 같은
  계열의 **열린 판정**이 있다 — `x-auth-order-open`(「무인증 요청에도 415가 나간다 — 401보다 앞선다」,
  `status: 판정_대기`, `escalated_to: 리더`, `:369-395`). C6 이 그 계열의 표면을 넓혔는데 그 사실이
  어디에도 적히지 않았다.
- 계약의 `not_reached` 진술(*"`PUT /conversions/{conversion_id}`는 재지 못했다 — **구현이 없다**"*, `:382`)이
  **부분적으로 낡았다** — 경로는 이제 있고 405 팔은 잴 수 있다.
- 자원의 **존재**는 흘리지 않으므로(405 는 경로 단위) 차단은 아니다. `AuthenticatedEndpoints` 의 새 한
  줄이 「경로의 두 메서드를 함께 덮는다」고 적은 것(`:9-12`)은 인터셉터 관점에서는 참이지만, **405 는
  인터셉터 앞이라 그 문장이 405 팔에 대해서는 성립하지 않는다.**

### K-3 [판정 필요 · **β-12 판단 평가**] multipart 팔 유보는 옳다 — JSON 팔 쪽에 셋이 빠졌다

**「계약이 흡수를 요구하므로 `contract-keeper` 개정 선결」이라는 판단은 옳다.** 실측:
`contracts/easy-doc-v1.yaml:2353-2358` 의 `DocumentFileRequest.workspace_id` 가 *"빈 문자열은 미지정과
같게 다룬다"* 로 적혀 있다. 구현만 고치면 DC-7 이 **계약과 반대되는 기대**를 들게 되고, 그것은 이
저장소가 반복해 겪은 형태(테스트가 계약 대신 자기 기대와 대조)다. 근거 지목이 정확하고 거부도 옳다.

**JSON 팔을 닫은 쪽에 셋이 빠졌다.**

1. **같은 엔드포인트의 같은 필드가 팔에 따라 다르게 동작하게 됐다** — JSON `workspace_id: ""` → 422 /
   multipart `workspace_id=""` → 미지정. 「계약에 이 팔에 대한 조항이 0 이라 무수정으로 닫힌다」는
   근거가, 그대로 **그 갈림도 계약 밖**이라는 뜻이다. 계약이 표현하지 못하는 자리이므로
   `contract-keeper` 에 올려야 한다(개정 없이 갈림만 남으면 다음 사람이 어느 쪽을 버그로 읽을지 알 수
   없다).
2. **`x-change-policy.procedure` 2번의 기록이 없다** — *"React 영향을 먼저 확인한다 … '아마 괜찮다'는
   확인이 아니다"*. 산출물 §4 ① 에 React 영향 문장이 0줄이다. 내가 대신 실측했다:
   `frontend/src/api/client.ts:155` 이 `if (workspaceId !== null)` 이므로 **빈 문자열은 걸러지지 않고
   실린다**(`undefined` 가 아니라 `null` 만 본다). 오늘 그 값이 `""` 가 되는 화면 경로는 찾지
   못했으나(선택값이 `workspace/storage.ts` 를 지난다), 「걸러지지 않는다」는 사실은 실측이다. 판정은
   `contract-keeper`·리더.
3. **처방의 도달이 선언보다 넓다(안전한 방향)** — `JsonRequestStrictnessConfig:23-24` 는
   `LogicalType.OtherScalar` 전체에 `EmptyString → Fail` 을 건다. Jackson 은 `UUID` 를 이 범주로
   분류하므로 대상은 `workspace_id` 하나가 아니라 **그 논리 타입의 모든 요청 필드**다. 오늘 그 범주의
   요청 필드는 `DocumentTextRequest.workspaceId`(`DocumentDtos.kt:26`) 하나뿐이라 관측 차이는 없고
   fail-closed 라 방향도 안전하다. **기록만 필요하다** — 선언(β-12 한 자리)과 도달(논리 타입 전체)이
   다르고, 다음에 UUID·URI 요청 필드가 생기면 그 필드의 422 가 이 커밋에서 온다는 사실이 어디에도 없다.

### K-4 [수정 필요 · 마감 이 게이트] **CR-2 의 오라클이 자기 자신이다 — N-26 의 두 번째 사례이고 이번엔 처방이 없다**

`ConversionReadReachTest:47-65`:

```kotlin
val declaredStatuses = ContractSpec.schemaEnum(STATUS_SCHEMA)
val observed = declaredStatuses.map { status -> … forceStatus(conversionId, status) … }
assertThat(observed.toSet()).isEqualTo(declaredStatuses.toSet())
```

**심는 것과 기대하는 것이 같은 표현이다.** 계약 enum 을 `[pending, done]` 으로 좁히면 두 값만 밟고 두
값을 기대해 **초록**이다. 이것은 §5 음성 대조표의 **N-26 이 실측한 바로 그 형태**다 —
*"계약에서 범주를 읽어 그 범주로만 심으므로 심는 것과 재는 것이 함께 줄어 자기 일관되게 통과한다"*.

형제 자리(`MaskCategory`)에는 이 커밋이 완전성 케이스를 세웠다(S-8). **`ConversionStatus` 에는 없다** —
실측: `grep -rn 'ConversionStatus.entries' backend-kotlin --include='*.kt'` → **0건**. 즉 「몰랐다」가
아니고 **같은 형태를 한 자리만 닫았다.** 그 사실이 산출물 어디에도 잔여로 적혀 있지 않다(§7 R-1~R-8 에
없다).

부수 둘:
- `observed` 의 비어 있지 않음 단언이 케이스 안에 없다. 유일한 보호는 새 헬퍼
  `ContractSpec.schemaEnum` 의 `require(values.isNotEmpty())` — 「빈 enum」만 막고 **「원소 1개」는 막지
  않는다**.
- 최종 단언은 제품 동작에 대해 거의 동어반복이다. `forceStatus` 가 계약 리터럴을 `status` 컬럼에 SQL 로
  심고, `ConversionDtos.kt:64`(`status = view.status.wireName`)가 그것을 되돌려 준다. **제품이
  `processing`·`done`·`failed` 를 실제로 만든다는 것은 재지 않는다**(그 주체는 Phase 5 워커다).
  §2 표가 이 케이스를 「계약 `ConversionStatus.enum` **각 값**을 실제로 밟고」로 적은 것은 **문면이 도달보다
  넓다**.

### K-5 [수정 필요 · 마감 이 게이트] **CR-4 의 절반은 실패할 수 없다**

`ConversionReadReachTest:97-120`:

```kotlin
forceStatus(conversionId, FAILED_STATUS, failureCode = "ProviderUnavailable")
…
val code = response[FAILURE_CODE_PROPERTY]?.toString()
…
assertThat(code).doesNotContain("안내문").doesNotContain(body)
```

`code` 는 테스트가 직접 SQL 로 심은 리터럴 `"ProviderUnavailable"` 이다. 두 `doesNotContain` 은 **제품
동작과 무관하게 항상 참**이다. `isNotBlank()` 와 `maxLength` 단언도 같은 이유로 테스트가 고른 값에 대한
것이다.

산출물 §2 는 CR-4 를 *"실패 코드가 비어 있지 않고 계약 `maxLength` 안이며 본문 문장을 담지 않는다 —
**통과**"* 로 적었다. **「본문 문장을 담지 않는다」는 재지 못했다.** 그 성질의 주체는 쓰는 쪽(Phase 5
워커)이고 아직 없으므로, 오늘 잴 수 있는 것은 「형식·상한 왕복」까지다.

이 항목은 **선언 범위 대 실제 도달**의 표준형이고, `DisplayName` 이 그 전칭을 **강조 표기까지 붙여**
적고 있어(`**본문·모델 응답이 담기지 않는다**`) 다음 사람이 그 축이 서 있다고 읽는다.

### K-6 [권고] CR-6 도 같은 성질이다

`ConversionReadReachTest:158-173` — 유실 라벨을 테스트가 계약 enum 에서 조립해 SQL 로 심고 계약
`pattern` 과 맞춘다. 제품의 라벨 생성 경로는 이 케이스를 지나지 않는다. 재는 것은 **계약↔계약 일관성 +
`jsonb` 왕복**이고, 그 자체로 값이 있다 — 다만 §2 의 「통과」가 「자리표시자 생성이 계약대로다」로 읽히지
않게 다시 적어야 한다.

### K-7 [권고] DD-5 HTTP 팔의 셋째 근거가 **핀이 아니라 부등식**이다

`ConversionReadReachTest:252-260` — 매핑 없는 경로의 404 본문과 `isNotEqualTo` 로 비교한다. 매핑 부재를
실제로 확인했다(제품 매핑 전수: `/auth/**`·`/workspaces/**`·`/health`·`/documents*`·
`/conversions/{conversion_id}`·`${server.error.path:/error}` — 와일드카드 매핑 0건). 근거 셋은 성립한다.
다만 일반 404 본문(`{"detail":"Not Found"}`)을 **못박지 않으므로**, 두 본문이 함께 바뀌면 부등식이
유지되며 통과한다. 산출물 §3 이 「같아지면 이 케이스가 빨개진다」고 적은 것은 참이다 — 「달라진 채로 둘
다 바뀌는」 갈래만 남는다.

### K-8 [검토함 — 지적 없음] 계약 준수의 나머지

- **13필드 평면 · snake_case**: `ConversionResponse` 열세 필드 전부 `@get:JsonProperty` 명시
  (`ConversionDtos.kt:37-49`). CR-1 의 최상위 키 집합 `isEqualTo(schemaRequired("ConversionResponse"))`
  가 Jackson 네이밍 사고를 잡는 자리이고(P-33), N-27 음성 대조가 그것을 실측했다.
- **200 선언 헤더 전수**: `ConversionReadContractTest:69-86` 이 `responseHeaderNames` 를 읽고 **빈 선언
  거부 단언**을 앞에 둔다(`isNotEmpty`).
- **422(400 아님)**: 번역 지점을 확인했다 — `error/GlobalExceptionHandler.kt` `handleTypeMismatch`
  (`:141-157`) → `validationError`(`:188-201`)가 *"Spring 기본값은 400 이지만 계약은 422 다"* 로
  `HttpStatus.UNPROCESSABLE_ENTITY` 를 준다. CR-9(`:133-138`)는 422 를 **정확히** 못박고 계약이 그것을
  선언하는지도 함께 본다(`assertDeclaredStatus:224-238`), `detail` 배열 · 항목 키 집합 정확 일치까지
  단언한다(`:241-254`). 공백 세그먼트 흡수는 `TypedValueSlotInterceptor` 가 따로 닫는다.
- **401 균일성**: CR-10 이 실제로 헤더를 보내지 않는다(`getRequest` 가 토큰 null 이면 헤더를 안 붙인다).
  `WWW-Authenticate` 값은 계약 컴포넌트 `const` 에서 읽는다.
- **소유권 404 에 403 경로 없음**: 새 경로의 예외는 `NotFoundException` 하나
  (`ConversionQueryService.kt:26`)이고 문구는 계약 404 예시에서 온다(`DocumentMessages.kt:45`).
- **`Cache-Control`·`X-Content-Type-Options` 값**: 컨트롤러 상수와 전역 필터 상수가 같은 값이고 계약
  `components/headers` 의 `const` 와 대조된다(K-1 은 **누가 붙였는지**의 문제이고 **값**의 문제가 아니다).

---

## 4. 축 ⑶ — 게이트·탐지기 자신 + 「선언한 범위와 실제 도달」

> 이 절의 전제: 지난 두 회차에 「거짓 초록」이 여덟 번 잡혔고 여덟 번 다 도달이 선언보다 좁았다. 같은
> 종류가 남아 있다고 전제하고 찾았다. **찾았고, 셋은 차단 칸이다.**

### 규칙 4 분류 (장치를 먼저 분류한다)

| 장치 | 종류 | 빈 선언에서 실패하는가 | 판정 |
|---|---|---|---|
| 새 시간 예산 축 | 탐지형 | 해당 없음(합계 비교) | **G-1 — 사고의 절반이 축 밖** |
| β-08 핀 멤버십 이력 대조 | 탐지형 | **예** (`assert current, "…비었다…"`, `:2494-2497`) | 기제는 옳다 · **G-2 — 자기 커버리지 목록이 무보호** |
| X5 삼분할 + `_bound_direction` | 탐지형(사유 문장 → 실행 성질) | **예** (`assert declared`) | **G-3 — 한 줄 간접으로 우회 · G-4 — 정수만** |
| X1-1 `OwnershipConcealment` | 강제·표현형(판정 합침) | 해당 없음 | **S-1·S-2·S-3** |
| X1-2 「거절 경로의 문장 수」 | 탐지형 | (범위 밖 — 65882bc 는 이 회차 안이나 X1-2 는 목록 팔 확장이고 음성 대조가 산출물에 있다) | 검토함 — 지적 없음 |
| `e2038dd` 대조 프로브 | 탐지형 | 해당 없음 | **S-1** |
| `EXPECTED_UNGUARDED` 인구조사 | 탐지형 + **면제성 정확 열거** | **예** (`requireNonEmpty` + 빈 루트 실측) | **S-5 — 상한 라쳇 없음** |
| `TIMED_SCANNERS`·`RATCHET_PIN_TABLES` | **범위 선언형** | **아니오 — 목록에서 빼기를 막는 것이 없다** | **G-2** |
| `VARIABLE_HEADERS` | **은폐형** | 해당 없음 | **S-2 — 넓힘을 막는 것이 0** |

**은폐형을 넓힌 자리**: `S-2` 하나(`VARIABLE_HEADERS` 를 네 파일에서 한 곳으로 합쳐 **영향 범위**가
넓어졌다 — 목록 자체는 안 넓혔다). `@Suppress`·`# noqa`·`.gitignore` 를 이 범위에서 넓힌 자리는 **찾지
못했다**. L-㉘ 이 적은 대로 `@Suppress("LargeClass")` 후보는 **버리고** 케이스 압축으로 갔다.

### G-1 [차단 ② · 마감 이 게이트] **시간 예산 축은 사고의 절반을 구조적으로 못 본다**

`tests/test_kotlin_gate_reach.py:2604-2640` — 스캐너 다섯을 **캐시를 비우고 각각 한 번** 부른 시간의
합을 예산 30s 와 비교한다.

그런데 `e2038dd` 가 실측한 사고는 **두 부분**이었다(그 커밋 메시지가 스스로 적는다):
1. 한 `findall` 205.9s — **이 축이 잡는다**(900× 변이가 42.98s 로 빨개진 실측이 있다).
2. *"`_kotlin_main_sources`·`_kotlin_declared_names`·`_named_enforcer_census` 에 캐시도 붙였다
   (없어서 **전수 스캔이 두 번 돌았다**)"* — **이 축이 원리적으로 못 본다.**

`@functools.cache` 한 줄을 지우면 게이트 전체가 호출 횟수만큼 느려지는데, 축은 여전히 「한 번 호출」만
재므로 **초록**이다. 그리고 축은 캐시를 **일부러 비우고** 재므로 캐시 유무를 관측할 수단이 원천적으로
없다. 656.74s 중 205.9s 만 설명되므로 남은 ~450s 가 바로 그 형태일 가능성이 있고, **⑤-b 재현 실패가
그것과 맞물린다**(아래 §5 ⑶).

- **악용 비용** 데코레이터 **한 줄** · **자동 탐지** 없음.
- **왜 ② 인가**: 이 축이 무력해지면 CI `quality` 잡 15분을 이 검사 하나가 먹고, 그 잡의 **모든** 가드가
  실행되지 않는다. 그 잡에는 `scan_privacy_invariants.py`(BLOCK 게이트, `ci.yml:118`)와 pytest 8종이
  들어 있다 — 즉 마스킹·평문 저장·소유권 가드의 실행 기회가 함께 사라진다. L-㉘ 이 이 결함 종류를
  「가드가 예산을 먹어 다른 가드를 죽인다」로 새로 정의하고 **차단 칸**으로 판정했으므로, 그 축의 절반이
  비어 있는 것도 같은 칸이다.
- **도달은 확인했다**(도달 0 아님): `ci.yml:198`(quality) · `:374`(kotlin) 두 잡에 경로 명시로
  배선돼 있고, 내가 직접 돌려 281 passed 를 봤다.

### G-2 [차단 ② · 마감 이 게이트] `TIMED_SCANNERS` 와 `RATCHET_PIN_TABLES` **자신**이 어느 라쳇·인구조사에도 없다

둘 다 **문자열 튜플**이라 `_module_int_constants`(정수만) 밖이고, 세 라쳇 표 어디에도 없다. grep 실측:
`RATCHET_PIN_TABLES` 의 참조는 선언(`:2455`)과 `parametrize`(`:2458`) **둘뿐**이다.

- **`TIMED_SCANNERS`(`:890-896`)에서 이름 하나를 지우면** 그 스캐너가 시간 축 밖으로 나간다 — **한
  낱말 · 탐지 0**. KDoc 은 *"이름으로 두면 그 함수가 사라졌을 때 `getattr` 이 끊어 **조용한 축소가 안
  된다**"* 고 적지만, 실제로 막는 것은 **함수 삭제**이고 **목록에서 빼기**는 막지 못한다. 선언이 도달보다
  넓다. (테스트 안의 `assert function is not None` 주석도 *"`TIMED_SCANNERS` 를 조용히 줄이는 편집을
  여기서 끊는다"* 라고 적는데, 그 단언은 목록에 **남아 있는** 이름만 검사하므로 그 문장은 거짓이다.)
- **`RATCHET_PIN_TABLES`(`:2455`)에서 `"RATCHET_CEILING_PINS"` 를 지우면** 상한 표의 이력 대조가
  사라진다 — **한 낱말 · 탐지 0**. 이것은 **β-08 이 고친 결함의 재귀 형태**다: β-08 의 근거는
  *"라쳇 기제 전체가 이 파일 한 곳이고 … 튜플을 지우면 저장소 전체에서 빨개지는 것이 없었다"* 였고,
  리더는 그 항목을 **차단으로 확정**했다. 새 장치가 자기 커버리지 목록에 같은 빈자리를 만들었다.

**규칙 4 의 「빈자리가 구조적으로 재발하면 그 종류만큼 넓힌다」에 걸린다** — 열거할 수 없는 자리를
**종류로** 댈 수 있다: **「이 파일에서 다른 검사의 분모를 정하는 문자열 목록 전부」**(`TIMED_SCANNERS` ·
`RATCHET_PIN_TABLES` · 앞으로 생기는 것). 그리고 이 종류는 **탐지형으로 갈아탈 수 있다**(문자열 튜플도
`_pin_tuples_in` 과 같은 방식으로 이력 대조가 가능하다) — 즉 은폐형 예외가 아니다.

### G-3 [수정 필요 · 마감 이 게이트] `_bound_direction` 의 실행 판정이 **직접 구문 비교**까지만 도달한다

X5 의 채택 근거는 *"사유 문장은 그 사유가 참인지 재는 실행이 0"* 이었고, 실측은 *"방향 있는 새 하한
상수를 그럴듯한 사유와 함께 `NON_RATCHET_PINS` 에 넣는 변이가 **두 줄에 197 passed**"* 였다. 처방은
AST 판정이다(`:2318-2374`).

그런데 판정은 상수 **이름**이 `ast.Compare` 의 피연산자로 **직접** 나타날 때만 방향을 읽는다. 한 줄의
간접이면 방향이 `"none"` 이 된다:

```python
MIN_FOO = 12                    # 새 하한 상수
...
limit = MIN_FOO                 # ← 이 한 줄
assert observed >= limit        # 이름이 `names` 에 없다 → MIN_FOO 의 방향은 "none"
```

그러면 `NON_RATCHET_PINS` 에 그럴듯한 사유와 함께 넣을 수 있고 삼분할이 통과한다. **X5 가 막았다고 적은
「두 줄」이 「세 줄」이 됐을 뿐이다.** 함수 인자·딕셔너리 조회·`max()`/`min()` 경유도 같다.

음성 대조(`test_방향_판정기가_하한과_상한과_무방향을_가른다`, `:2568-2602`)는 **직접 세 형태만** 먹이므로
이 갈래를 드러낼 수 없다 — 「그 장치를 떼면 무엇이 깨지는가」는 확인됐지만 「무엇이 안 깨지는가」는
확인되지 않았다.

- **악용 비용** 2~3줄 · **자동 탐지** 없음.
- 부수(권고): 그 케이스의 `relative` 지역 변수는 계산(`:2581`) 뒤 `del relative`(`:2591`)로 버려진다 —
  죽은 코드다. 린터를 달래려 남은 것으로 **추정**한다. 판정에는 영향이 없다.

### G-4 [수정 필요] `test_이_파일의_수치_상수가_전부_분류돼_있다` 의 **이름이 도달보다 넓다**

`_module_int_constants`(`:2288-2316`)는 `bool` 을 빼고 **정수만** 센다
(`isinstance(value.value, int)`), 그리고 KDoc 이 *"⑶ 실수를 정수로 오인한다(`= 0.05` 에서 `0` 을
읽는다)"* 를 정규식의 함정으로 들며 AST 로 갔다고 적는다. 결과: **실수 문턱은 삼분할 밖이다.**

오늘 이 파일에 모듈 최상위 실수 상수는 0건이다(내 grep 실측). 그러나 SKILL.md 규칙 3 의
「**오늘 0건으로 닫지 않는다**」가 바로 X5 채택의 근거였으므로 같은 기준이 여기에도 걸린다. 그리고 실제로
이 저장소가 쓰는 문턱 가운데 **가장 자주 문제가 된 것이 실수**다 — 아래 §5 ⑵.

케이스 이름과 실패 메시지가 「이 파일의 수치 상수」라고 전칭하므로, 다음 사람이 실수 문턱을 이 파일에
넣으면서 분류를 빠뜨려도 조용하다.

### G-5 [수정 필요] **이 파일의 규범 머리(「재는 것 일곱」)가 자기 내용과 어긋난다 — 한 커밋 안에서 세 문면이 갈렸다**

`tests/test_kotlin_gate_reach.py`:

| 자리 | 적힌 것 |
|---|---|
| `:62-65` (머리 「재는 것 일곱」 항목 3) | *"종전에는 이 앞에 「개수 상수」(`TEST_CLASS_COUNT`, 목록과 정확 일치)가 따로 있었다. … **없앴다** — 규칙 7."* |
| `:131` (발견 파서 절) | *"개수 축은 `MIN_TEST_CLASSES` 하한이 진다(2026-08-21 이전에는 `TEST_CLASS_COUNT` 정확 일치가 함께 있었고, **규칙 7 로 없앴다**)."* |
| `:343-362` (그 상수의 KDoc) | *"이 상수를 없앴다가 **되돌렸다** — 하한은 이것을 대신하지 못한다. … 실측: 선언 111 · 하한 105 → **6 개까지는 파일과 선언을 함께 지워도 라쳇이 울리지 않는다.** 그중 82 개는 `FLOOR_TEST_CLASSES` 에도 없어 바닥도 못 막는다."* |
| `:363` / `:1046` | `TEST_CLASS_COUNT = 111` / `assert len(TEST_CLASSES) == TEST_CLASS_COUNT` |

**머리는 「없앴다」, 상수는 「되돌렸다」, 코드는 살아 있다.** 위험은 문서 오류가 아니라 **다음 편집자가
머리를 읽고 그 상수를 다시 없애는 것**이고, 그러면 그 KDoc 이 실측한 **6 개 창**이 다시 열린다(그중 82
개는 바닥도 안 지킨다). 이 파일은 게이트의 규범 정본이므로 머리의 거짓 진술을 게이트 자신의 결함으로
센다.

- **악용 비용** 0(선의의 편집이 결함을 만든다) · **자동 탐지** 없음. 이 저장소에 「4축 사본의 동기화
  강제자」(`ed3df31`)가 있는데 그것이 이 자리를 덮지 못한다는 것도 함께 올린다.

### G-6 [권고] `TEST_CLASSES` 에 새 항목이 정렬 밖에 들어갔다

`:280` — `kr.easydoc.application.document.ConversionQueryServiceTest` 가
`…conversion.ConvertDocumentUseCaseTest` 와 `…conversion.RepairDecisionTest` **사이**에 있다. 정렬을
재는 케이스가 없다(중복만 잰다, `:1044`). 오늘 무해하나, 정렬이 깨진 목록에서는 중복·누락이 눈으로 안
잡히고 이 목록은 111 항목이다.

### G-7 [검토함 — 지적 없음] 빈 선언 통과는 **찾지 못했다**

**범위 선언형이 빈 선언에서 실패하는가**를 항목별로 확인했다:
- `test_라쳇_핀_목록이_이력에서_줄지_않았다` — `assert current, "표를 통째로 비우는 편집이 이 대조를
  공허하게 만들 수는 없다"`(`:2494-2497`). **끊는다.**
- `test_이_파일의_수치_상수가_전부_분류돼_있다` — `assert declared, "…이 대조는 아무것도 재지 않는다"`.
  **끊는다.**
- `test_상한_상수가_이력_최솟값보다_높지_않다` — 현재 파일에서 상수를 못 찾으면 실패. 이력이 없으면
  `skip`(커밋 시점 2 skipped 의 정체이고, HEAD 에서는 실제로 돈다 — 내 실행에서 skip 0).
- `OwnershipPredicateGuardTest` — `requireNonEmpty` + **빈 루트 실측**(`emptyRoot()`) 둘로 끊고, 모듈
  전수 기여(`선언된 모듈이 전부 분모에 들어 있다`)까지 본다. 이 가드의 분모 방어는 이 저장소에서 가장
  단단한 편이다.
- `ConversionReadContractTest` 의 계약 읽기 셋(`schemaEnum`·`schemaPropertyEnum`·`schemaPropertyPattern`)
  전부 `require(...isNotEmpty())` / `error(...)` 를 들고, 헤더 선언 케이스는 `isNotEmpty` 를 앞에 둔다.

**측정이 대리 경로에서 이뤄진 자리**: G-1(캐시 비운 단발 호출로 「게이트가 느려지는가」를 대신 잰다) ·
K-1(전역 필터가 있는 컨텍스트로 「개별 부착이 있는가」를 대신 잰다) 둘.
**판정이 대리 지표로 이뤄진 자리**: §5 ⑵(유휴 3회 초록을 「깜박임이지 회귀가 아니다」의 근거로 쓴다).
**검사의 기준이 검사 대상 자신에게서 나온 자리**: K-4(CR-2) · K-5(CR-4) · K-6(CR-6) · S-1(프로브).
**판정하는 코드가 자기 자신을 검사 대상에 넣었는가**: `tests/test_kotlin_gate_reach.py` 는
`THIS_TEST_PATH` 로 스스로를 분모에 넣고 `mypy . .claude` 범위 안이다(CLAUDE.md 이력 2026-08-13).
그러나 **자기 커버리지 목록 둘은 넣지 않았다** — G-2.

---

## 5. 리더가 스스로 적은 것 셋 — 평가

### ⑴ M-3 ⒜ 를 「완료」에서 「부분」으로 정정한 판정 — **옳다**

실측이 판정을 지지한다(S-4). 세 가지를 더한다:
- **강제자 0 은 구조적이다.** 인구조사가 문장을 세고 호출자를 세지 않으므로, 이미 핀에 든 문장을 새
  사용자 경로에서 부르는 편집은 **원리적으로** 신호를 만들 수 없다. 「아직 안 만든 강제자」가 아니라
  「이 설계로는 만들 수 없는 강제자」이므로 C7 의 구조 단언이 대체물이 아니라 **필수**다.
- **대상은 둘이 아니라 셋**이다(`JdbcWorkspaceRepository.kt | SELECT [documents]` 포함).
- 「레인은 자기가 만든 경로에 대해 참을 적었고 조건 문면이 그보다 넓었다」는 리더의 진단은 이 회차에서
  **네 번 더** 재현된다 — K-1·K-4·K-5·G-2. 같은 형태가 한 회차에 다섯이면 **개별 지적이 아니라 종류**로
  다룰 근거가 된다(규칙 4 ⑴).

### ⑵ 시간 축 거짓 양성(부하 중 1.536 > 1.5, 유휴 3회 초록)에도 그 축을 남긴 판단 — **남기는 것은 옳고, 처분이 미완이다**

**남기는 것은 옳다.** 떼면 그 성질(존재 여부가 응답 시간으로 새지 않는가)을 재는 것이 0이 된다.

**미완인 것 — 문서 자신이 예고한 실패 경로에 강제자가 0이다.** 이 커밋의 문서는 두 곳에서 이렇게 적는다:
*"예산을 실측에 가깝게 조이면 러너 부하가 곧 거짓 빨강이 되고, 그때 고치는 법은 예산을 올리는 것이라
축이 스스로 무력해진다 — **R-10 이 시간 축에서 겪은 그 문제다.**"* 그 진단이 옳으므로 처방은 「다음
인상이 diff 에 신고로 남게 하는 것」이다. 실측한 현재 상태:

- 문턱 `MAX_TIMING_RATIO = 1.5` 는 **네 파일에 각각 사본**으로 있다 —
  `AuthEndpointReachTest.kt:565` · `DocumentDeleteReachTest.kt:434` ·
  `WorkspaceEndpointReachTest.kt:538` · `AesGcmContentCipherTest.kt:625`.
- **어느 라쳇 표에도 없다** (grep 실측: `tests/test_kotlin_gate_reach.py` 에 `TIMING` 0건).
- 실수(`1.5`)이므로 G-4 에 따라 삼분할·인구조사 밖이다 — Kotlin 쪽이라 애초에 이 파일의 인구조사 대상도
  아니지만, `RATCHET_SCALAR_PINS` 는 Kotlin 상수를 다섯 개 담고 있으므로 **담을 수 있었다.**

즉 X5 가 이 커밋에서 상한 라쳇 기제를 새로 만들고 항목을 하나 넣는 동안, **바로 그 회차에 거짓 양성이
관측된 문턱**은 그 표에 들어가지 않았다. 사본이 넷이라 인상은 국소 편집 한 줄이다.

- **판정: 수정 필요.** 마감은 「그 문턱이 다음에 깜박이는 때」이고 그것은 예측할 수 없으므로 실질적으로
  **지금**이다.
- 부수: 「깜박임 대 회귀」의 판별이 **유휴 3회 초록**이라는 대리 지표로 닫혔다. `deleteOwned` 를 건드리지
  않았다는 diff 근거가 함께 있어 오늘 결론은 맞다고 본다 — 다만 그 판별 절차가 사람의 재실행이라는 사실은
  잔여로 남는다(측정을 부하 독립으로 만드는 것 — CPU 시간·중위수 N회 — 은 백로그 후보이고 판정은 리더).

### ⑶ ⑤-b 재현 실패(83배 사건 정규식이 1.87s)를 원인 미확정으로 남긴 처리 — **남긴 것은 옳고, 잔여의 크기가 잘못 적혔다**

**남긴 것은 옳다.** 숨기지 않았고, 축의 값을 「그 패턴을 잡는다」에서 **「스캐너가 느려지면 잡는다」**로
낮춰 다시 적은 것도 정확한 재기술이다.

**잘못 적힌 것 — 잔여를 「배수」로 적었는데 실제로는 「형태」다.** 산출물 §7 R-4 는 잔여를
*"24배 미만의 잠식은 잡지 않는다(500× = 23s 초록 실측)"* 로 적는다. 그것은 참이지만 **더 큰 잔여를
가린다**:

- 재현이 실패했으면 「656.74s 의 원인이 `TIMED_SCANNERS` 안에 있었는가」도 **미확정**이다. 205.9s 는
  설명되지만 나머지 ~450s 는 아니다.
- 그리고 같은 커밋(`e2038dd`)이 **두 번째 원인을 명시적으로 고쳤다** — *"캐시도 붙였다(없어서 전수
  스캔이 두 번 돌았다)"*. **그 형태는 이 축이 원리적으로 못 본다**(G-1). 즉 축 밖에 있는 것은
  「24배보다 완만한 잠식」이 아니라 **「같은 사고의 나머지 절반」**이다.

- **판정: 수정 필요**(원인 미확정 자체를 잔여로 남기는 것은 정당하다 — 잔여의 문면이 실제보다 작게
  적혀 있는 것이 결함이다). R-4 를 「배수 하한」과 「캐시 제거형은 축 밖」 둘로 갈라 적어야 한다.

---

## 6. Phase 종료 조건 대비 현황 (§5 Phase 4 · §6 Contract 게이트 · 계획 §7.2 C6)

| 조건 | 상태 | 근거 |
|---|---|---|
| C6 `GET /conversions/{conversion_id}` 구현 | **충족** | 컨트롤러·DTO·유스케이스·저장소·조립 전부 있고 실 PostgreSQL 로 200/404/401 을 낸다 |
| CR-1~CR-10 전건 통과 | **실행됐다 · 문면이 도달보다 넓다** | 실행 자체는 커밋 메시지의 JUnit XML 진술(내가 직접 재현하지 않음). **CR-1(하한선 부분)·CR-2·CR-4·CR-6 의 §2 문면이 도달보다 넓다** — K-1·K-4·K-5·K-6 |
| §6 Contract 게이트 「status/body/header/error 가 v1 spec 과 일치」 | **부분** | status·body·error 충족(K-8). **header 는 하한선 축이 미측정**(K-1) |
| §2.3 소유권 은닉 404(403 금지) | **충족(사건 없음)** | CR-7·CR-8 이 실 DB·실 두 계정으로 잰다. 403 경로 없음 |
| §5 Phase 7 즉시 중단 기준 해당 사건 | **없음** | 셋 다 뒤졌다 — 타 사용자 노출·평문 로그/응답·마스킹 범주 확대 모두 실제 경로를 찾지 못했다 |
| M-3 해제 조건 ⒜ | **부분 미충족(리더 판정 유지)** | S-4. 대상은 셋 |
| M-3 해제 조건 ⒝ | **미착수** | 이 단위 밖(앞 레인이 ⒝ 커밋으로 미뤘다) |
| β-12 | **부분** | JSON 팔 닫힘 · multipart 팔은 `contract-keeper` 개정 선결(K-3, 판단 옳음) |
| β-08 · X5 · X4 · 새 시간 축 | **실행되나 각각 새 빈자리** | G-1·G-2·G-3·G-4 |
| 하네스 게이트 도달 | **충족** | `ci.yml:198`·`:374` 배선 확인 + 직접 실행 281 passed · skip 0 |

**미해결 항목(심각도 순)**

| # | 항목 | 심각도 | 마감 | 악용 비용 × 자동 탐지 |
|---|---|---|---|---|
| G-1 | 시간 예산 축이 캐시 제거형을 원리적으로 못 본다 | **차단 ②** | 이 게이트 | 데코레이터 1줄 × **없음** |
| G-2 | `TIMED_SCANNERS`·`RATCHET_PIN_TABLES` 가 무보호 | **차단 ②** | 이 게이트 | 1낱말 × **없음** |
| S-1 | 대조 프로브가 `observe` 어댑터를 지나지 않는다 | **차단 ②** | 이 게이트 | 1줄 × **없음** |
| S-5 | `EXPECTED_UNGUARDED` 에 상한 라쳇 없음 | **차단 ②** | 이 게이트 | 2줄 × **없음**(리뷰 diff 만) |
| S-2 | `VARIABLE_HEADERS` 은폐형의 증가를 재는 것이 0 | **차단 ②** | 이 게이트 | 1낱말 × **없음** |
| K-1 | X-D1 하한선 셋째 자리가 하한선을 재지 않는다 | **차단 ②**(2중 결함이라 **판정 필요**) | 이 커밋(명세 `:515`) | 2줄 × **없음** |
| K-4 | CR-2 오라클 자기참조 · `ConversionStatus` 완전성 케이스 0 | 수정 필요 | 이 게이트 | 계약 1줄 × **없음** |
| K-5 | CR-4 의 절반이 실패 불가 · §2 가 「통과」로 적었다 | 수정 필요 | 이 게이트 | — × — (거짓 초록) |
| G-3 | `_bound_direction` 을 한 줄 간접으로 우회 | 수정 필요 | 이 게이트 | 2~3줄 × **없음** |
| G-5 | 게이트 파일의 규범 머리가 자기 내용과 어긋난다 | 수정 필요 | 이 게이트 | 0(선의의 편집) × **없음** |
| ⑵ | `MAX_TIMING_RATIO`(사본 4) 에 라쳇 없음 | 수정 필요 | 지금 | 1줄 × **없음** |
| ⑶ | R-4 의 잔여 문면이 실제보다 작다 | 수정 필요 | 이 게이트 | — × — |
| G-4 | 삼분할이 정수만 — 이름은 「수치 상수」 | 수정 필요 | 실수 문턱이 이 파일에 들어오는 때 | 1줄 × **없음** |
| K-3 | JSON/multipart 갈림이 계약 밖 · React 확인 미기록 | 판정 필요 | `contract-keeper` 개정 시 | — × — |
| K-2 | `PUT /conversions/{id}` 405 표면 신설이 미기록 | 판정 필요 | `x-auth-order-open` 판정 시 | — × — |
| S-3 | P1 이 헤더 값을 안 본다(선언은 「응답 구별 불가」) | 판정 필요(`privacy-gate`) | — | — × — |
| S-4 | M-3 ⒜ — 대상 셋 · 강제자 0 은 구조적 | 판정 필요(리더 판정 유지) | C7 | 1줄 × **없음** |
| S-6 | `ConversionDtos.kt:26` 의 「유일한 호출」이 거짓 전칭 | 권고 | — | — × — |
| S-9 | `placeholder.pattern` 의 범주 복제를 재는 것 없음 | 권고 | 범주가 늘 때 | — × — |
| K-6 | CR-6 도 계약↔계약 | 권고 | — | — × — |
| K-7 | DD-5 셋째 근거가 핀이 아니라 부등식 | 권고 | — | — × — |
| G-6 | `TEST_CLASSES` 정렬 밖 항목 · 정렬 케이스 없음 | 권고 | — | — × — |
| G-3부수 | `del relative` 죽은 코드 | 권고 | — | — × — |

**심각도와 착수 차단은 별개 축이다.** 차단으로 올린 여섯에 마감을 적었고, **착수 차단 여부의 판정은
리더에게 넘긴다.** 「아직 안 쓰이는 게이트니 차단이 아니다」로 스스로 낮추지 않았다 — G-1·G-2 의
게이트는 **지금 CI 에서 돈다**(내가 실행으로 확인).

---

## 7. 미실행 · 확인 불가 (없는 근거를 추정으로 메우지 않는다)

1. **Gradle 전체 빌드를 재실행하지 않았다.** 공유 작업 트리 · 미커밋 5건(c5 잔여) · 병렬 레인 때문이다.
   CR 표의 「전건 통과 · 미실행 0」은 산출물 §6 과 커밋 메시지의 JUnit XML 진술을 근거로 읽었고 **직접
   확인하지 못했다.** 리더 독립 재실행(exit 0 · 89/89)도 그 진술로만 알고 있다.
2. **변이(음성 대조)를 하나도 수행하지 않았다** — 코드 수정 금지. **S-1·S-2·S-5·G-1·G-2·G-3·G-5·K-1 은
   소스 읽기와 grep 실측에 근거한 판단이며, 「고치기 전 초록」의 실행 관측이 없다.** 각 항목의 기제는
   본문에 적었으므로 리더가 하나씩 실행으로 가를 수 있다.
3. **K-1 은 전제 하나에 의존한다(추정 표시).** `@WebMvcTest` + `@Import(FilterRegistrationBean 을 담은
   config)` 에서 MockMvc 가 그 필터를 실제로 태우는가. 근거는 Spring Boot 의
   `SpringBootMockMvcBuilderCustomizer` 가 `Filter` 빈과 `FilterRegistrationBean` 을 함께 수집한다는
   것이고, 정황 근거는 `DocumentListHeaderFloorTest:54-66` 이 「config 를 안 넣으면 헤더가 없다」를
   실측으로 고정한다는 사실이다(즉 넣으면 있다). **실행으로 확인하지 못했다.** 그 전제가 거짓이면 K-1 은
   성립하지 않으므로, **리더에게 이 한 가지를 실행으로 갈라 주기를 요청한다** — 확인법은
   `ConversionController.kt:31-32` 를 지우고 `ConversionReadContractTest` 만 돌리는 것이다.
4. **R-7(`api` 테스트가 `infrastructure` main 을 본다)을 재판정하지 않았다.** 산출물이 리더 판정
   사항으로 올렸고 CR-5 가 그 노출을 이용한다. 이번 회차 축(셋) 밖이다.
5. **parity 위험 축 · Kotlin/Spring 관용성 축은 미검토**(리더가 축을 셋으로 지정). 따라서
   `MaskedItemCodec` 저장 형식의 요구사항 충족, 모듈 경계·트랜잭션 경계·detekt 처분의 적정성은 **이
   회차가 판정하지 않았다.** `ConversionQueryService` 분리와 `data class` 선택의 사유는 산출물 §1 에만
   있고 코드에서는 트림으로 사라졌다(§8) — 그 사실만 기록한다.
6. **codex 산출물(`04_documents-c6_codex-reviewer.md`)을 읽지 않았다.** 1차 독립 리뷰이므로 정상이며
   실패로 기록하지 않는다. 교차 대조표는 이 회차에서 만들지 않는다.
7. **범위 밖 15커밋은 심판하지 않았다.** 다만 그것들이 만든 정책(주석 트림)이 이 범위에 남긴 자리 하나를
   사실로 기록한다 — `api/src/test/.../support/DocumentSliceFakes.kt:272` 에서
   `StubDocumentTextExtractor` 의 「대역이 제품 규칙(`SourceFormat.ofUploadFilename`)을 재사용하는 사유」
   KDoc 이 `/** 파일 추출 대역. */` 한 줄로 접혔다. 산출물 §8 이 같은 종류 4건(새 파일 KDoc 축약)을 이미
   리더에게 올렸다. **처분은 리더**이고, 이 리뷰는 「사유를 소스 주석에 쓰라」고 요구하지 않는다.
8. **찾지 못한 것을 명시한다**: 은폐형(`@Suppress`·`# noqa`·전역 무시 패턴)을 이 범위에서 **넓힌 자리는
   찾지 못했다.** 계약을 좁힌 자리, 마스킹 범주를 2종보다 넓게 적은 자리, 평문이 로그·응답으로 나가는
   경로, 403 이 새는 경로도 **찾지 못했다.**
