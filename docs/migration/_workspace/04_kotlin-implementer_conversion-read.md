# C6 — `GET /conversions/{conversion_id}` 산출물

**계획**: `04_kotlin-implementer_c6-plan.md`.
**착수 리비전**: 리더가 준 것은 `fd00c38` 이었으나 작업 중 `0075743` 이 들어왔다(§8 참고).
**정본**: 계획 §7.2 C6 행 · 테스트 명세 CR 표 · 계약 `paths./conversions/{conversion_id}.get`.

> **계약 파일은 무수정이다.** 음성 대조로 일시 변조한 뒤 복원했고 sha256 을 §5 에 적었다.

---

## 1. 완성 모듈과 대응 Python 원본

| 모듈 | 새/수정 | 대응 Python 원본 |
|---|---|---|
| `core/document/ConversionView.kt` | 신설 | `app/services/documents.py::ConversionView` |
| `application/document/DocumentPorts.kt` — `StoredConversion` · `ConversionRepository.findOwnedResult` · `MaskedItemReader` | 수정 | `app/repositories/conversions.py` 의 소유 조인 조회 |
| `application/document/ConversionQueryService.kt` | 신설 | `app/services/documents.py::DocumentService.get_conversion` |
| `infrastructure/document/JdbcConversionRepository.kt` — `findOwnedResult`·`toStored`·`placeholderLabels` | 수정 | 같음 |
| `infrastructure/document/MaskedItemCodec.kt` — `MaskedItemReader` 구현 | 수정 | `app/services/documents.py::deserialize_masked_items` |
| `api/document/ConversionDtos.kt` · `ConversionController.kt` | 신설 | `app/api/documents.py::read_conversion` |
| `api/auth/AuthenticatedEndpoints.kt` | 수정 | `app/api/deps.py` 의 보호 경로 |

**의도적으로 다르게 구현한 지점**

1. **`ConversionQueryService` 를 `DocumentService` 와 분리했다.** Python 은 한 서비스에 다 있었다.
   detekt `LongParameterList` 가 조립 지점에서 울렸고, 이 저장소의 처분 규약은 **문턱을 올리거나
   억제를 넓히지 않고 구조를 바꾸는 것**이다(`DocumentStorage` KDoc 의 선례 · `CLAUDE.md` 규칙 4 ⑵).
   신호가 가리킨 것이 실제로 참이었다 — 다른 집합체(`conversions`), 다른 협력자(`MaskedItemReader`),
   반대 방향(봉인 대 개봉).
2. **`ConversionView`·`StoredConversion` 을 `data class` 로 두고 `toString()` 을 손으로 썼다.**
   계약 필드가 열셋이라 일반 클래스 주 생성자는 detekt 문턱을 넘고, 그 규칙은 `data class` 를
   제외한다(실측: 같은 열세 필드의 `ConversionResponse` 는 걸리지 않는다). `DocumentListItemResponse`
   가 같은 자리에서 같은 선택을 한 선례다.
3. **`ModelDraft`/`ReviewedBody` 로 감싸지 않았다.** 두 타입은 **출처**를 뜻하고 DB 에서 되읽은
   값은 그 출처의 사본이다. 감싸면 `ProvenanceCreationSitesTest` 허용목록에 「저장소에서 읽은
   값을 사람의 제출로 선언하는」 자리가 생긴다.
4. **`missing_placeholders` 파싱을 `MaskedItemCodec` 이 아니라 저장소에 두었다.** 그 컬럼은
   평문 `jsonb` 이고 봉인 대상이 아니다 — 코덱에 끌어들이면 *"결과는 반드시 암호화해서
   저장한다"* 는 그 클래스의 규약이 흐려진다.
5. **`ContentCipher.decrypt` 를 트랜잭션 밖에서 부른다.** 읽기 한 문장만 경계 안이다.

## 2. CR-1 ~ CR-10 전건

| # | 층 | 어디에 | 결과 |
|---|---|---|---|
| **CR-1** | C-M | `ConversionReadContractTest` — 200 · 사적 헤더 값·**개수** · 최상위 키 == `ConversionResponse.required` · 마스킹 항목 키/범주/패턴 · 계약 200 선언 헤더 전수 | **통과** |
| **CR-2** | C-I | `ConversionReadReachTest` — 계약 `ConversionStatus.enum` **각 값**을 실제로 밟고 관측 집합 == 계약 집합 · 상태별 키 집합 == `required` | **통과** |
| **CR-3** | C-I | 〃 — 완료 전 상태 전부에서 배열 둘이 `[]`(널 아님) · 본문 둘이 널 | **통과** |
| **CR-4** | C-I | 〃 — 실패 코드가 비어 있지 않고 계약 `maxLength` 안이며 본문 문장을 담지 않는다 | **통과** |
| **CR-5** | C-I | 〃 — **실물 코덱·실물 키**를 거쳐 저장한 대응표가 계약 키 집합·범주 집합(정확 일치)·패턴으로 나온다 + 원값이 실제로 실린다 | **통과** |
| **CR-6** | C-I | 〃 — 유실 라벨 각 원소가 계약 `items.pattern` | **통과** |
| **CR-7** | C-I | 〃 — 타인 변환 404 · **403 아님 명시 단언** · `detail` == 계약 404 예시 | **통과** |
| **CR-8** | C-I | 〃 — `OwnershipConcealment.assertIndistinguishable`(상태·**원시 바이트**·헤더 이름 집합) | **통과** |
| **CR-9** | C-M | `ConversionReadContractTest` — UUID 아닌 경로 변수 → 422 · `detail` 배열 · 항목 키 == `ValidationErrorItem.required` | **통과** |
| **CR-10** | C-R | `ConversionReadReachTest` — 401 · `WWW-Authenticate` · 본문 키 == `ErrorResponse.required` | **통과** |

**미실행 없음.** 그 밖에 더한 것: 완료 변환의 본문 봉인 왕복(HTTP 표면), 마스킹 범주 enum
완전성(§4 N-26 이 그것 없이는 통과한다).

## 3. DD-5 의 HTTP 팔 — 구별의 근거

C5 가 유보한 이유는 「핸들러가 없어서 404」가 「파기됐으니 404」로 둔갑한다는 것이었다.
구현이 생겼으므로 **세 근거를 함께 관측**해 가른다(`ConversionReadReachTest`):

1. **같은 URL 이 삭제 전에 200 이다.** 매핑 부재라면 삭제 전에도 404 이므로 200 → 404 의
   전이 자체가 「매핑은 있고 자원이 사라졌다」를 뜻한다.
2. **본문이 계약 404 예시 문구다**(`변환 결과를 찾을 수 없습니다`). 매핑되지 않은 경로는 그
   문구를 낼 수 없다.
3. **매핑 없는 경로의 404 와 본문이 다르다.** 같은 형태의 URL 뒤에 존재하지 않는 조각을
   붙인 요청을 함께 보내 두 본문을 대조한다. 같아지면 이 케이스가 빨개지고, 그때 근거 2 도
   무의미해졌다는 뜻이다.

`DocumentDeleteReachTest` 의 **저장 상태 축은 남는다** — 이쪽이 「행이 사라졌다」, 저쪽이
「그 사실이 응답으로 나타난다」를 잰다. 계약이 약속한 것은 조회 실패가 아니라 파기다.

## 4. 리더 판정 4건 + 새 축

### ① β-12 — 처방 ⓑ, **부분 마감**

**JSON 본문 팔을 닫았다**(가장 넓은 흡수 · `""` 와 `" "` 둘 다). `JsonRequestStrictnessConfig`
에 `LogicalType.OtherScalar` 의 `EmptyString → Fail` 한 줄. 계약에 이 팔에 대한 조항이 **0** 이라
계약 무수정으로 닫힌다.

**multipart 팔은 닫지 못했다 — 계약이 흡수를 요구한다.** `DocumentFileRequest.workspace_id` 가
*"빈 문자열은 미지정과 같게 다룬다"* 로 적혀 있어, 구현만 고치면 DC-7 이 **계약과 반대되는
기대**를 들게 된다. 그것은 이 저장소가 반복해 겪은 형태(테스트가 계약 대신 자기 기대와 대조)라
고르지 않았다. **`contract-keeper` 의 조항 개정이 선결이다** — 그 한 줄이 바뀌면 제품 편집은
`DocumentController.parseWorkspaceId` 한 줄이다.

### ② β-08 — 튜플 삭제를 **탐지**로 잡았다

`test_라쳇_핀_목록이_이력에서_줄지_않았다` — `RATCHET_SCALAR_PINS`·`RATCHET_CEILING_PINS`·
`RATCHET_TABLE_PINS` 의 **멤버십**을 git 이력과 대조한다(`현재 ⊇ 이력 합집합`).

**Cβ-10(인구조사 + 사유 있는 면제표)을 고르지 않은 사유**: 그 처방은 침묵을 **diff 에 남는
허위 사유 문장**으로 바꿀 뿐이고 그 사유가 참인지 재는 실행이 다시 0 이 된다 — X5 가 방금
같은 형태를 걷어냈다. 분모를 **git 이력**으로 두면 ⑴ 공격자가 PR diff 안에서 고칠 수 없고
⑵ 새 면제 조항이 생기지 않으며(은폐형 회피) ⑶ 판정이 실행이다. 항목을 **더하는** 편집은
언제나 통과한다.

### ③ X5 — AST 교체

`test_이_파일의_수치_상수가_전부_분류돼_있다` 의 판정을 **사유 문장에서 실행 성질로** 옮겼다.
`_module_int_constants`(AST 인구조사) + `_bound_direction`(순서 비교 연산자로 하한/상한/무방향
판정, 두 방향으로 쓰이면 fail-closed). 분할이 **삼분할**이 되면서 상한 표
`RATCHET_CEILING_PINS`(현재 ≤ 이력 최솟값)가 신설됐다 — 예산·문턱은 **올라가면** 보호가 줄기
때문이고, 그 방향을 하한 표에 섞으면 이력 대조가 절반을 반대로 판정한다.

판정기 자신의 음성 대조(`test_방향_판정기가_하한과_상한과_무방향을_가른다`)도 함께 세웠다 —
판정기가 언제나 `"none"` 을 돌려주는 변이에서 삼분할이 조용히 통과하기 때문이다.

### ④ X4 — DD-5 보강(조인 없는 셈법)

`DocumentDeleteReachTest.orphanJobRows(conversionId)` 신설 — `conversion_jobs` 에 **직접**
술어를 걸어 센다. 삭제 전에 변환 식별자를 붙잡아 두는 `createDocumentAndConversion` 을 함께
더했다. 기존 `jobRows(documentId)` 는 `INNER JOIN` 이라 `conversions` 행이 사라지면 **항상 0**
이므로, 파기 후 단언을 그것으로 하면 앞 단언의 논리적 결과일 뿐이다. 그 함수는 **삭제 전**
관측(연쇄 첫 고리가 실제로 있었다)에만 남겼고, 그 한계를 KDoc 에 적었다.

### ⑤ 새 축 — 게이트 스캐너의 실행 시간

`SCANNER_TIME_BUDGET_SECONDS = 30` + `test_게이트_스캐너의_실행_시간이_예산_안이다`.
캐시를 비우고 스캐너 다섯을 한 번씩 부른 시간을 재고, 넘으면 **어느 스캐너가 얼마를 썼는지**
열거한다. 상한이므로 `RATCHET_CEILING_PINS` 가 올림을 막는다.

**재는 범위와 재지 않는 범위**: 스캐너 원시 함수만 잰다. 「pytest 파일 전체의 벽시계」를 파일
자신이 재면 실행 순서·병렬 실행·캐시 상태에 흔들리고, 흔들리는 축은 곧 문턱을 올려 무력해진다.
**git 이력 대조의 `subprocess` 왕복은 재지 않는다** — 저장소 크기·디스크에 좌우되고 오늘 실측이
1~2초라 예산 근거가 얇다(잔여 R-6).

**상대 이상치 축은 세우지 않았다 — 실측 근거.** 오늘 개별 스캐너의 퍼짐이
`최댓값/중앙값 = 0.628/0.153 ≈ 4.1` 이다. 문턱을 그 위에 두면 사고 좌표(한 함수 205.9s)는
잡지만 **예산 축이 이미 잡는다** — 새로 잡는 것이 0 이다. 아래로 조이면 스캐너가 하나
늘거나 줄 때 중앙값이 움직여 거짓 빨강이 나고, 고치는 법이 문턱 올리기라 축이 스스로
무력해진다(R-10 이 시간 축에서 겪은 그 문제).

**거짓 양성 대가**: 실측 합계 1.261s(개별 0.073 / 0.058 / 0.628 / 0.350 / 0.153) → 예산 30s 는
여유 **약 24배**. 대신 **탐지 하한도 약 24배**다(§5 의 500× 초록 / 900× 빨강 실측). 사고는
83배였으므로 잡히고, 그보다 완만한 잠식은 잡지 않는다 — 그것이 이 문턱의 값이다.

## 5. 음성 대조 표

판정 기준은 **「겨눈 장치가 그 자리를 짚었는가」**다. 변조는 전부 **일회용 git worktree** 안에서
했고 측정 뒤 제거했다. 계약 파일 sha256: 변조 전·복원 후·`HEAD` blob 이 모두
`5963dc5b89b13b91e44a9bb2da2b35edcd58692a60c9ae5588c739510a9576da` 로 **일치**.

| # | 변이 | 고치기 전 | 고친 뒤(짚음) |
|---|---|---|---|
| **N-1** | `findOwnedResult` SQL 에서 `AND d.user_id = :ownerId` 제거 | — (이 커밋이 그 문장을 만든다) | **세 곳**: `OwnershipPredicateGuardTest`(핀 밖으로 이동) · **CR-7** *"404 이 아니다: {…전체 응답 본문…}"* · **CR-8** *"없는 것은 404, 남의 것은 200 — 상태 코드가 존재 여부를 흘린다"* |
| **N-27** | `ConversionResponse.required` 에서 `failure_code` 제거 | — | **CR-1 빨강**(최상위 키 집합 불일치) |
| **N-26** | `MaskedItemResponse.category.enum` 에서 `카드번호` 제거 | **CR-5 초록** — 계약에서 범주를 읽어 그 범주로만 심으므로 심는 것과 재는 것이 **함께 줄어 자기 일관되게 통과**한다(실측) | 신설 완전성 케이스 **빨강** — *"계약 [주민등록번호] / 구현 [주민등록번호, 카드번호]"* |
| **β-08** | `RATCHET_SCALAR_PINS` 에서 Kotlin 튜플 삭제 **+ 그 상수 9→5** | **1 failed(무관한 내 선언 누락)만** — `MIN_NEGATIVE_CASES` 를 짚은 것이 **없다** | `test_라쳇_핀_목록이…[RATCHET_SCALAR_PINS]` **빨강** — 경로와 이름을 지목 |
| **X5** | 방향 있는 새 하한 상수를 그럴듯한 사유와 함께 `NON_RATCHET_PINS` 에 넣기(2줄) | (`c6-preconditions` §3.3 실측: **두 줄에 197 passed**) | `test_이_파일의_수치_상수가…` **빨강** — *"lower: AST 판정 [… MIN_PROBE_CASES …] / 선언 […]"* 로 **양방향** 지목 |
| **⑤** | 스캐너가 파일마다 반복 훑는 루프 결함(=캐시 누락의 확대판) | 120× → 12s 초록 / 500× → 23s **초록** | 900× → **빨강** *"게이트 스캐너가 42.98s 를 썼다 — 예산 30s"* + `_kotlin_declared_names: 42.05s` 로 **범인 지목** |
| **⑤-b** | 사고 당시의 파국적 백트래킹 정규식을 **그대로** 되돌리기 | — | **재현되지 않았다** (1.87s, 초록). 그 특정 패턴은 오늘 소스에서 더는 느리지 않다 — 그래서 ⑤ 의 값은 「그 패턴을 잡는다」가 아니라 **「스캐너가 느려지면 잡는다」**에 있다 |
| **X4** | (스키마 변경 필요 — **미실행**, §7 R-3) | — | 대신 **양성 대조**가 있다: `orphanJobRows` 가 삭제 **전** 1, 삭제 **후** 0 을 관측한다(같은 케이스 안) |

**함께 관측된 것**: N-26 에서 기존 CR-5 케이스가 초록이었다는 사실이 완전성 케이스를 세운
직접 근거다. 그것 없이는 「계약에서 읽는다」가 **좁아지는 방향으로는 공허**하다.

## 6. 검사 결과

| 게이트 | 명령 | 결과 |
|---|---|---|
| Kotlin | `./gradlew --no-build-cache --rerun-tasks build`(ktlint+detekt+test 포함) | **BUILD SUCCESSFUL** (2m 22s, exit 0) — §8 의 병렬 편집 이후 재실행분은 그 절에 적는다 |
| 하네스 게이트 | `uv run pytest tests/test_kotlin_gate_reach.py` | **279 passed · 2 skipped** |
| 하네스 게이트(요구 모드) | `KOTLIN_GATE_REACH_REQUIRE_REPORT=1 KOTLIN_GATE_REACH_RUN_STARTED_AT=<빌드 앞에서 박은 표식>` | **279 passed · 2 skipped** |
| 통합 러너 | `uv run python .claude/skills/kotlin-migration/scripts/quality_gate_local.py` | §8 |

**2 skipped 의 정체**: 새 표(`RATCHET_CEILING_PINS`)와 새 상한 상수가 **아직 이력에 없다**.
커밋 뒤 HEAD 가 그것을 포함하면 대조가 선다 — 기존 스칼라 핀도 같은 성질이라 새 동작이 아니다
(`c6-preconditions` §7-3 이 예고한 그대로다).

**detekt 가 잡아 준 것(전부 구조로 고쳤다, 억제 0)**: `LongParameterList`×3 ·
`ThrowsCount`(`placeholderLabels`) · `ReturnCount`(슬라이스 대역) · `MaxLineLength`×7.

**기존 가드가 잡아 준 것**: `EnvelopeColumnWriteGuardTest` 가 내 실측 테스트의 `UPDATE` 가
봉투 두 값을 함께 쓰지 않는 것을 잡았다(테스트 소스도 그 규약의 대상이다).
`SensitiveToStringReachTest` 가 `ConversionResponse`·`StoredConversion` 의 `toString` 누출을
잡았다 — 그 과정에서 `GeneratedToStringProbes` 의 정수 표본 `0` 이 `EncryptedContent` 의 도메인
검사(`1..32767`)를 통과하지 못해 **판정 불가**가 되던 것도 함께 드러나 `1` 로 고쳤다.

## 7. 그래도 증명하지 못하는 것

| # | 증명하지 못한 것 | 악용 비용 | 자동 탐지 |
|---|---|---|---|
| **R-1** | **X-E3(빈 배열 대 널)의 음성 대조.** 타입이 non-null `List` 라 「널을 내보내는」 변이는 컴파일되지 않고, DTO 를 nullable 로 바꾸는 변이는 worktree 재동기화 중 앵커가 어긋나 측정하지 못했다 | DTO 두 줄 + 매핑 두 줄 | **부분** — 타입이 널을 막고 CR-3 가 값을 관측하지만, 「CR-3 이 빨개질 수 있다」는 실행 관측이 없다 |
| **R-2** | **CR-5 의 저장 형식 독립성.** `api` 테스트가 제품 `MaskedItemCodec` 을 직접 쓴다. 그것이 실물 형식을 재는 근거이면서, 그 클래스가 틀리면 **쓰기와 읽기가 같이 틀려** 왕복이 통과한다 | 코덱 한 줄 | **부분** — `MaskedItemCodecTest` 가 형식 자체를 별도로 잰다 |
| **R-3** | **X4 의 고아 작업 행.** 실현 경로가 FK 를 떼는 마이그레이션 하나뿐이고 그것은 §4.2 가 이 단위 밖으로 미룬 스키마 변경이다 | 마이그레이션 한 줄 | **없음** — 스키마 지문에 `conversion_jobs` 가 한 줄도 없다(`c6-preconditions` §3.2) |
| **R-4** | **⑤ 의 탐지 하한.** 24배 미만의 잠식은 잡지 않는다(500× = 23s 초록 실측) | 스캐너를 20배 느리게 | **없음** — 그 구간은 CI 잡 예산 안이라 증상도 없다 |
| **R-5** | **⑤ 가 재지 않는 비용.** git `subprocess` 왕복(`_blob_at` 등)은 예산 밖이다. 이력 대조 케이스가 늘면 그 비용이 조용히 는다 | 라쳇 핀 추가 | **없음** |
| **R-6** | **β-08 의 우회.** 튜플과 상수를 **같은 커밋에서 함께** 지우면 이력 대조가 「그 상수가 사라졌다」로 통과한다. 그것이 정당한 삭제와 구별되지 않는다 | 두 파일 | **부분** — 상수를 지우면 그 상수가 지키던 대조가 따로 빨개지는 경우가 많다(전부는 아니다) |
| **R-7** | **`api` 모듈 경계 선언과 도달의 어긋남.** `api/build.gradle.kts` 의 `runtimeOnly` 주석은 *"api 소스가 JDBC·암호화·LLM SDK 타입을 컴파일 시점에 볼 수 없게 막는다"* 로 적혀 있는데, `testFixtures(project(":infrastructure"))` 가 그 모듈의 **main 산출물을 함께 노출**한다(실측: `api` 테스트에서 `MaskedItemCodec`·`JdbcClient` 가 컴파일된다). 차단은 **main 소스에만** 성립한다 | — | **없음** — 이 단위는 그 사실을 이용했고(CR-5 가 실물 코덱을 쓴다) 고치지 않았다. 처분은 리더/리뷰 몫이다 |
| **R-8** | **`ConversionQueryService` 분리의 대가.** `DocumentService` KDoc 이 「문서 등록」으로 좁아졌지만 `list`·`delete` 는 그대로 남아 있어 그 클래스의 이름과 내용이 여전히 완전히 맞지 않는다 | — | **없음** — 개선 백로그로 넘겼다 |

## 8. 병렬 편집 충돌 — 리더 확인 필요

**작업 중 다른 레인이 같은 작업 트리를 편집했다.** 관측된 것:

1. `HEAD` 가 `fd00c38` → **`0075743`** 으로 움직였다(리더가 준 착수 리비전과 다르다).
2. `tests/test_kotlin_gate_reach.py` 에서 **`TEST_CLASS_COUNT` 와 그 대조가 삭제**되고
   `NON_RATCHET_PINS` 가 비워졌다(SKILL.md 규칙 7 인용). 그 편집의 KDoc 이 **내 X5 장치를
   근거로 인용**하므로 조율된 편집으로 읽었고, 되돌리지 않았다. 내 삼분할은 빈 표에서도
   성립한다(관측 쪽도 비어야 통과).
3. **내 새 파일들의 KDoc 이 대폭 축약됐다** — `ConversionDtos.kt` 142→78행,
   `ConversionController.kt` 116→49행, `ConversionView.kt` 79→27행,
   `ConversionQueryService.kt` 108→55행. `AuthContractTest.kt` 는 삭제됐고 `CLAUDE.md`·
   `.claude/skills/**` 도 수정됐다.
4. 그 결과 **§1 의 「의도적으로 다르게 구현한 지점」 4건의 사유가 코드에서 사라졌다.**
   이 문서가 그 사유의 유일한 기록이다.

**되돌리지 않은 이유**: 그 레인의 편집은 규칙을 인용한 의도적 작업이고, 내가 프로즈를 되살리면
두 레인이 같은 파일에서 서로를 덮는다. **다만 두 가지를 리더에게 올린다** — ⑴ 같은 커밋 단위에
두 레인이 동시에 쓰는 상태가 이번 회차의 측정을 두 번 무효화했다(빌드 4 는 두 Gradle 실행이
같은 `test-results` 를 써서 XML 쓰기 실패로 죽었고, 요구 모드 게이트 1회차는 파일이 편집 중간
상태였다), ⑵ 축약이 남긴 문면과 이 문서가 갈리면 **이 문서가 나중**이다.

## 9. M-3 해제 조건 ⒜ 처리

**닫았다.** `ConversionRepository.findOwnedResult(ownerId, conversionId)` 가 소유자 인자를
**시그니처로 요구**하고, 구현이 `documents` 조인과 `d.user_id = :ownerId` 를 **한 문장에** 담는다.
`lockEnvelope` 는 회전 전용으로 남고, 두 메서드가 나란히 있는 것이 규약의 형태다 —
조회 경로가 실수로 `lockEnvelope` 를 부르면 컴파일은 되지만 CR-7·CR-8 이 200 을 받아 빨개진다.

`OwnershipPredicateGuardTest` 의 인구조사에 새 SELECT 가 한 줄 늘었고 **미방어 목록에는 늘지
않았다** — 두 목록이 서로 다른 사건을 잡는다는 것이 여기서 다시 관측된다.

`documents` 쪽에는 소유자 인자 포트가 **여전히 없다.** 없어도 되는 이유(그 암호문을 읽는
사용자 경로가 계약에 없다)와, 그런 경로가 생기면 새 포트를 만들라는 지시를 포트 KDoc 에 남겼다.

**⒝(감시 테이블에 `workspaces` 추가)는 이 단위가 하지 않았다** — 앞 레인이 결정만 하고 코드는
⒝ 커밋으로 미뤘고, 리더 지시도 ⒜ 만 이 단위의 마감으로 지목했다. 그 표를 넓히면
`EXPECTED_STATEMENTS`·`EXPECTED_UNGUARDED` 가 함께 늘어 이 단위의 diff 에 무관한 변경이 섞인다.

## 10. 리더에게 — 판정이 필요한 것

1. **β-12 multipart 팔** — `contract-keeper` 가 `DocumentFileRequest.workspace_id` 조항을
   개정해야 닫힌다(§4 ①). 개정 없이 구현만 고치는 것은 이 레인이 거부했다.
2. **R-7 — `api` 테스트가 `infrastructure` main 을 본다.** 빌드 주석의 선언과 실제 도달이
   갈려 있다. 좁히면 CR-5 가 실물 저장 형식을 쓸 수 없게 되므로 **선택**이다.
3. **§8 병렬 편집** — 같은 작업 트리에 두 레인이 동시에 쓰는 상태의 처분.
4. **`MIN_TEST_CLASSES` 인상**은 하지 않았다(리더 핀). 다른 레인이 인상 시점을 「Phase 경계」로
   바꾼 문면을 넣었으므로 그 규약을 따랐다.
