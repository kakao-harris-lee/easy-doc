# Phase 4 `documents` — 보안 축 판정: M-3 (`lockSourceText`/`lockEnvelope` 의 소유 조건)

- **역할**: `privacy-gate` (스킬 `migration-safety-gate`)
- **고정 리비전**: 감사 중 **트리가 움직였다.** 정직하게 둘 다 적는다.
  - **착수 시점 HEAD**: `66f008bc3203a0091c123cac6fb36f8ab0ebf947` — §1~§6 의 Kotlin 근거를 전부 여기서 땄다.
  - **종료 시점 HEAD**: `c981173a2ed75439759c4f7a916765e6c4911469` (`feat/kotlin-migration-harness`)
  - **두 리비전의 차이**: `docs/migration/_workspace/reviews/` 의 게이트 27 산출물 3건 추가뿐이다.
    `git diff --stat 66f008b..c981173 -- backend-kotlin/ contracts/` → **빈 출력(변경 0)**.
    따라서 **이 판정의 Kotlin 근거는 재확인 없이 새 HEAD 에도 그대로 유효하다.**
  - **주의 — `contracts/easy-doc-v1.yaml` 이 감사 도중 미커밋 수정 상태가 되었다**(`git diff --stat` → 155 삽입 · 27 삭제). **이 역할은 그 파일을 열지도 고치지도 않았다** — 이 문서 한 파일 외에 아무것도 쓰지 않았으므로 편집 주체는 동시에 도는 다른 레인이다(정황상 계약 레인). 그래서 **계약 인용은 줄 번호를 쓰지 않고 키 경로로만 부른다** — 감사 중에 실제로 줄이 최대 +127 밀렸고, 줄 번호로 적었다면 이 문서가 곧 거짓이 된다. 내용 축은 재확인했다: `readConversion` 의 `description`·`responses.404`·`responses.200.headers` 와 `x-private-response-headers` 목록은 **변경되지 않았다**(§2 각주).
- **대상**: 게이트 27 교차 종합 §3 행 14 = **M-3**. 판정 주체가 이 역할로 지정된 항목.
- **범위 밖(병합하지 않는다)**: codex **C-1**(평문 제목 — 저장면). 교차 종합 §8.2 가 정리한 축 차이를 그대로 유지한다. 이 문서는 **접근 통제** 축만 판정한다.

---

## 차단 통보 — **조건부 차단 (C6 에 발효)**

> **오늘 시점 차단 아님. C6 을 여는 순간 차단이 발효한다.**
> 아래 7항은 `privacy-gate` 차단 통보 형식 그대로다. 오늘 진행 중인 작업(C3·C4·C5)을 멈추지 않는다.

1. **위반 불변식** — I-5 (다른 사용자 자원은 403 이 아니라 404 로 은닉한다) / `CLAUDE.md` §2.2 · 계획 §5 Phase 3·7
2. **근거** — `application/src/main/kotlin/kr/easydoc/application/document/DocumentPorts.kt:102`(`lockSourceText`) · `:177`(`lockEnvelope`) 가 `ownerId` 를 받지 않고, 구현(`JdbcDocumentRepository.kt:95-102` · `JdbcConversionRepository.kt:63-70`)의 `WHERE` 에 소유 조건이 없다. 같은 파일 `:57-60` 의 클래스 KDoc 은 **읽기 메서드가 전부 `ownerId` 를 받는다**고 적고 있어 **문면이 거짓**이다. 강제 장치 실측 0개(§5).
3. **재현 경로** — 합성 값으로 §2 에 적었다. 실제 본문·암호문·키를 옮겨 적지 않았다.
4. **영향 범위** — `GET /conversions/{conversion_id}`(C6) · `PUT /conversions/{conversion_id}`(C7) · `GET /conversions/{conversion_id}/export`. 노출 데이터는 `conversions` 의 암호문 세 열 전부이고 그중 `masked_items_encrypted` 는 **자리표시자↔원값 대응표**(§2.3 이 결과물보다 민감하다고 지정한 것)다.
5. **§5 Phase 7 즉시 중단 기준 해당 여부** — **오늘 해당 없음**(호출자 0). **C6 이 이 포트를 쓰면 「다른 사용자 데이터 노출 또는 404 소유권 규칙 위반」에 그대로 해당**하며, 그때는 첫 배포 승인이 막힌다.
6. **해제 조건** — 셋 다 충족해야 한다.
   - ⒜ 변환 암호문을 읽는 **사용자 경로 전용 포트**가 `ownerId` 를 받고, 구현의 소유 조건이 **SQL `WHERE` 안**에 있다(사후 비교 금지 — `conversions` 에 `user_id` 가 없으므로 `documents` 조인이 필요하다, §2.3).
   - ⒝ `documents`·`conversions` 를 읽는 SQL 중 소유 술어가 없는 문장을 **탐지하는 장치**가 서고, 그 장치의 **음성 대조가 제품 코드에서** 성립한다(§5 설계).
   - ⒞ `DocumentPorts.kt:57-60` 의 전칭 문장이 실제 도달과 일치하도록 정정된다(문장만 고치는 것은 해제 조건이 **아니다** — ⒝ 와 함께여야 한다).
   - 검증 방법: ⒜ 는 교차 사용자 통합 테스트(사용자 B 가 사용자 A 의 변환 식별자로 조회 → 404, 403·200 아님), ⒝ 는 §5 의 음성 대조 4건, ⒞ 는 문면과 시그니처 대조.
7. **수신자** — 고칠 주체: `kotlin-implementer`. 참조: 리더(마감 판정) · `contract-keeper`(⒜ 의 404 갈래가 계약 `paths./conversions/{conversion_id}.get.responses.404` 와 일치하는지) · `migration-reviewer`(종합 리뷰에 포함).

---

## 판정 요약

| # | 물음 | 판정 |
|---|---|---|
| 1 | 오늘 사건인가 | **아니오 — 오늘 사건 없음**(제품 호출자 2건 전부 회전 유스케이스, 그 유스케이스의 호출자 0). 단 이 사실을 장치 판정의 근거로 쓰지 않았다(§1.4) |
| 2 | C6 이 쓰면 노출이 성립하는가 | **성립한다.** 경로를 §2 에 적었다. AEAD 가 막지 못한다 — AAD 에 소유자가 없다 |
| 3 | 차단 등급 | **Major (확정)** + **Critical ② 승격 후보(리더 판정 요청)**. `migration-safety-gate` **I-5**. 오늘은 차단 아님, **C6 에 차단 발효** |
| 4 | 처방 | **탐지형 1건이 주(主)** + 강제·표현형 1건 + 범위 선언형 정정 1건. 은폐형 후보 2종을 §4.4 에 명시해 배제 |
| 5 | 음성 대조 | 설계는 §5. **지금 이 자리를 지키는 장치는 0개다** — 실측으로 확인했다 |
| 6 | 마감 | **사건 경계는 C6 그대로**(앞당길 근거 없음). **장치 마감은 C3 이전으로 앞당길 것을 권고**한다 — 근거 셋(§6) |

---

## 0. 실행 환경과 미실행 목록

**돌린 것처럼 적지 않는다.**

| 항목 | 상태 | 비고 |
|---|---|---|
| 소스 전수 grep (호출자·시그니처·SQL) | **실행** | §1·§2 에 명령과 출력 |
| `scan_privacy_invariants.py --rule OWNERSHIP-403` | **실행** | `run_gate.sh` 경유, 파이프 없음. §5.1 |
| `scan_privacy_invariants.py` 전수 | **실행** | 같음. §5.1·§7 |
| Flyway 스키마 확인(`V1` 소유 컬럼) | **실행** | §2.3 |
| 계약 대조(`contracts/easy-doc-v1.yaml`) | **실행 — 두 번** | 착수 시점과, 다른 레인의 미커밋 수정을 발견한 뒤 다시. 인용은 키 경로로만 |
| **동시 편집 감지** | **실행** | 감사 도중 `contracts/easy-doc-v1.yaml` 이 미커밋 수정 상태로 바뀌고 HEAD 가 움직였다. 둘 다 이 판정의 근거를 무효화하지 않음을 확인했다(머리말) |
| **Gradle 테스트 실행** | **미실행** | 이 판정은 코드·계약·스키마 정적 대조로 닫힌다. 그리고 §5 의 음성 대조는 **제품 코드 변조**를 요구하는데 이 역할은 코드를 고치지 않는다(오케스트레이터 「심판문은 심판 대상이 고치지 않는다」). 설계만 넘긴다 |
| **실 DB 교차 사용자 시도** | **미실행** | 해당 엔드포인트가 아직 없다(§1.3). 시도할 표면이 없어 「할 수 있었는데 안 했다」가 아니다 |
| `git stash` | **사용 안 함** | 공유 트리 금지 규약 준수. 옛 판 대조가 필요한 자리가 없었다(작업 트리 = HEAD) |

---

## 1. 판정 ① — 오늘 사건인가

### 1.1 현재 이름·시그니처 (코드에서 직접 확인)

리더가 알려 준 대로 이름이 바뀌어 있었다. `loadSourceText`/`loadEnvelope` 는 **소스에 존재하지 않는다** — 계획 §9.2 D-q 로 `lock*` 이 되었다.

```
$ grep -rn "lockSourceText\|lockEnvelope\|loadSourceText\|loadEnvelope" \
    --include="*.kt" --include="*.kts" --include="*.md" --include="*.yaml" --include="*.py" .
```

`*.kt` 적중 중 **선언**은 둘뿐이다.

- `application/.../document/DocumentPorts.kt:102` — `fun lockSourceText(documentId: UUID): EncryptedContent?`
- `application/.../document/DocumentPorts.kt:177` — `fun lockEnvelope(conversionId: UUID): ConversionEnvelope?`

둘 다 인자가 **행 식별자 하나**다. `ownerId` 는 없다.

### 1.2 호출자 전수 (제품 코드)

| 호출 지점 | 파일·라인 | 성격 |
|---|---|---|
| `documents.lockSourceText(documentId)` | `application/.../document/EnvelopeRotation.kt:105` | 키 회전 유스케이스 `rotateDocument` |
| `conversions.lockEnvelope(conversionId)` | `application/.../document/EnvelopeRotation.kt:122` | 키 회전 유스케이스 `rotateConversion` |

**제품 코드의 호출 지점은 이 둘이 전부다.** 나머지 적중은 전부 테스트(`src/test`)이거나 KDoc 문면이다.

그리고 그 회전 유스케이스 자신의 호출자를 다시 전수했다.

```
$ grep -rn "EnvelopeRotation\|rotateDocument\|rotateConversion" --include="*.kt" backend-kotlin/
```

`src/main` 적중은 **선언(`EnvelopeRotation.kt`)과 빈 정의(`DocumentConfiguration.kt:108-120`) 둘뿐**이고, `rotateDocument`/`rotateConversion` 을 **부르는** `src/main` 코드는 0건이다. 빈 정의의 KDoc 자신이 그렇게 적고 있다(`DocumentConfiguration.kt:104` — *"호출자가 아직 없다"*, 계획 §9 질문 ⑦ 미판정).

### 1.3 HTTP 표면 도달

```
$ grep -n "^  /\|^      operationId:" contracts/easy-doc-v1.yaml     # 계약 14 오퍼레이션
$ cat backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndpoints.kt
```

- `PROTECTED_PATH_PATTERNS` = `/auth/me` · `/workspaces` · `/workspaces/{workspace_id}` **셋뿐**.
- `api/src/main` 의 컨트롤러는 `AuthController` · `WorkspaceController` · `HealthController` · 오류 컨트롤러뿐이고 **문서·변환 컨트롤러가 없다**(C3~C7 미착수).
- `worker/src/main/.../WorkerApplication.kt` 는 24줄 부트 스텁이다(`class WorkerApplication` + `main`). 저장소를 부르지 않는다.

### 1.4 판정과, **낮추지 않은 근거**

> **오늘 사건은 없다.** 두 포트를 부르는 제품 경로는 회전 유스케이스 둘뿐이고, 그 유스케이스를 부르는 제품 경로가 0이며, 문서·변환 HTTP 표면이 아직 서지 않았다.

이것은 **사건 축의 사실 진술**이고, 여기서 멈춘다. 장치 축은 별개로 §3·§5 에서 판정했다. 게이트 27 §2-⑤ 가 기록한 Claude A-5 의 형태(「오늘 도달 0 → 위험 아님」으로 **심각도를 낮춤**)를 반복하지 않기 위해 아래를 명시한다.

- **도달 0 은 이 결함의 크기에 대한 정보가 아니다.** 코드 한 줄도 바꾸지 않은 채 C6 이 이 포트를 부르는 것만으로 사건 축이 열린다(§2). 즉 이 판정의 「Major」는 **결함이 작아서**가 아니라 **경계를 아직 넘지 않아서**다.
- 그리고 이 자리에는 **잘못 도달한 장치**가 하나 있다 — `DocumentPorts.kt:57-60` 의 클래스 KDoc:

  > 소유자 조건이 인터페이스에 박혀 있다 — 읽기 메서드가 **전부** `ownerId` 를 받고 구현은 그것을 `WHERE` 절에 넣는다.

  `DocumentRepository` 의 읽기 메서드는 `listOwned`(:84)와 `lockSourceText`(:102) 둘인데 후자가 받지 않는다. **전칭 문장이 실제 도달과 갈린다** — `CLAUDE.md` 「선언한 범위와 실제 도달을 대조한다」가 금지한 바로 그 형태이고, 같은 파일 `:98` 이 예외를 적어 두어 **파일이 자기 자신과 모순**이다. 이 문장은 이 저장소 안에서 「소유 조건이 어디에 있나」를 찾는 사람이 **가장 먼저 읽는 자리**다.

### 1.5 M-3 이 적은 것보다 표면이 넓다 (신규 관찰)

M-3 원문은 *"소유 조건이 SQL `WHERE` 에 없는 유일한 읽기 경로"* 라고 적었다. **읽기**로 한정하면 맞지만, 소유 조건 없는 **접근** 표면은 넷이다.

| 포트 | 파일·라인 | 종류 | `ownerId` |
|---|---|---|---|
| `DocumentRepository.lockSourceText` | `DocumentPorts.kt:102` | 읽기 | 없음 |
| `DocumentRepository.rewriteEnvelope` | `DocumentPorts.kt:116-120` | **쓰기** | 없음 |
| `ConversionRepository.lockEnvelope` | `DocumentPorts.kt:177` | 읽기 | 없음 |
| `ConversionRepository.rewriteEnvelope` | `DocumentPorts.kt:193-198` | **쓰기** | 없음 |

쓰기 둘은 낙관적 조건(잠근 채 읽은 행 전부)이 붙어 있어 **임의 덮어쓰기**는 어렵지만, 소유 조건이 없다는 성질은 같다. 처방(§4)의 탐지기 분모를 「읽기」가 아니라 **「`documents`·`conversions` 에 닿는 문장」**으로 잡아야 하는 근거다 — 읽기만 보는 탐지기를 세우면 이 표의 절반이 처음부터 탐지 밖이다.

또한 `ConversionRepository`(`DocumentPorts.kt:155-156`)에는 `DocumentRepository` 가 가진 소유권 규약 문장이 **아예 없다.** 그래서 이 인터페이스는 「거짓 선언」이 아니라 「**무선언**」이고, 둘 다 강제자는 0이다.

---

## 2. 판정 ② — C6 이 이것을 쓰면 타 사용자 노출이 성립하는가

> **성립한다.** 그리고 C6 이 이 포트를 고를 **구조적 유인**이 있다.

### 2.1 C6 이 필요로 하는 값과, 오늘 그것을 주는 유일한 포트

계약 `paths./conversions/{conversion_id}.get.responses.200.content.application/json.schema` 는 `ConversionResponse` 이고, 같은 오퍼레이션의 `description` 이 **`masked_items[].original` 에 가려졌던 실제 개인정보가 실리며 소유자 인증을 통과한 조회에서만 나간다**고 못박고 있다. 즉 C6 은 `conversions` 의 암호문 열을 읽어 복호화해야 한다.

> **각주(동시 편집 재확인).** 이 조항은 감사 중 다른 레인이 같은 파일을 편집하는 동안에도 **문면이 바뀌지 않았다.** 재확인한 키 경로 넷: `paths./conversions/{conversion_id}.get.description` · `.responses.200.headers`(`CacheControlNoStore`·`XContentTypeOptions` 참조) · `.responses.404.description` · `.responses.404.content…examples.not_found`(값은 옮겨 적지 않는다).

오늘 `conversions` 의 암호문 세 열을 돌려주는 포트는 **`lockEnvelope` 하나뿐이다.**

```
$ grep -n "interface ConversionRepository" -A 45 backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentPorts.kt
```
→ `insertPending`(쓰기) · `lockEnvelope`(읽기) · `rewriteEnvelope`(쓰기). **변환 한 건을 소유자 범위로 읽는 포트가 없다.**

C6 구현자가 「변환 암호문을 어떻게 읽지」를 물으면 검색 결과가 정확히 하나이고, 그것이 `ownerId` 를 받지 않으며, 클래스 위 KDoc(§1.4)은 이 저장소가 소유 조건을 인터페이스에 박아 두었다고 적고 있다. **잘못된 선택이 가장 자연스러운 선택이 되는 배치**다.

### 2.2 노출 경로 (합성 값)

- 사용자 **A** — 소유자. 변환 식별자를 `CID_A` 라 한다(합성 표기, 실제 값 아님).
- 사용자 **B** — 공격자. 자기 계정으로 정상 로그인해 유효한 토큰을 가진다.

1. B 가 `GET /conversions/CID_A` 를 호출한다. 인증은 통과한다 — **B 는 진짜 사용자**다. I-5 가 401 과 404 를 구분하라고 한 바로 그 자리이고, 여기서 걸러지지 않는다.
2. C6 이 `conversions.lockEnvelope(CID_A)` 를 부른다. SQL 은 `... FROM conversions WHERE id = :id FOR NO KEY UPDATE`(`JdbcConversionRepository.kt:66-71`)다. **소유 술어가 없다.** A 의 행이 그대로 돌아온다. `null` 이 아니므로 404 갈래로 빠지지 않는다.
3. 복호화가 **성공한다.** AAD 는 `AesGcmContentCipher.kt:306-311` 이 만든다:
   `easydoc-aead|{scheme}|{keyVersion}|{table.column}|{recordUUID}`.
   **소유자가 결속에 들어 있지 않다.** 결속 조각은 전부 행 자신의 속성이라, 남이 읽어도 태그 검증이 통과한다. **AEAD 는 이 사고를 막지 못한다** — I-7 이 지키는 것은 변조·다른 키이지 접근 통제가 아니다.
4. 응답 200 이 나간다. 담기는 것은 `ConversionResponse` 의 `easy_text` · `edited_text` · `masked_items`. `masked_items[].original` 은 **마스킹 대응표의 원값**이다(`EncryptedField.CONVERSION_MASKED_ITEMS` KDoc — *"자리표시자↔원값 표라 최고 민감도"*, `StoredContent.kt:204-205`).

결과: 계약이 `paths./conversions/{conversion_id}.get.responses.404` 로 못박은 **「없거나 내 것이 아니다」가 200 이 된다.** 403 이 아니라 200 이므로 은닉 실패보다 한 단계 나쁘다 — 존재가 새는 게 아니라 **내용이 나간다.**

§5 Phase 7 즉시 중단 기준 「다른 사용자 데이터 노출 또는 404 소유권 규칙 위반」에 정면으로 해당한다.

### 2.3 사후 검사로 때울 수 없다 — 두 가지 이유

C6 구현자가 「`lockEnvelope` 로 읽고 나서 소유자를 확인하면 되지 않나」로 갈 수 있다. 두 이유로 막아야 한다.

1. **반환값에 소유 판정 재료가 없다.** `ConversionEnvelope`(`DocumentPorts.kt:148-153`)는 `conversionId` · `scheme` · `keyVersion` · `ciphertexts` 만 든다. `documentId` 조차 없다. 사후 검사를 하려면 **질의를 한 번 더** 던져야 하고, 그 순간 「읽고 나서 비교」 구조가 확정된다 — `DocumentPorts.kt:58-60` 이 *"그 형태는 비교를 잊으면 조용히 남의 자원을 내주고, 잊지 않아도 존재 여부가 응답 시간으로 샌다"* 로 이미 금지한 형태다.
2. **`conversions` 에 소유자 컬럼이 없다.** `V1__python_schema_baseline.sql:108-139` 확인 — `conversions` 는 `document_id` 만 들고 `user_id` 가 없다. 소유는 `conversions.document_id → documents.user_id` 로만 닿는다. 즉 올바른 포트는 **조인을 포함한 단일 질의**여야 한다:
   `... FROM conversions c JOIN documents d ON d.id = c.document_id WHERE c.id = :id AND d.user_id = :ownerId`.
   이 사실은 처방을 「인자 하나 추가」가 아니라 **새 질의**로 만들며, 그래서 C6 에 떠넘기면 그 커밋에서 처음 설계된다.

### 2.4 같은 노출을 상속하는 엔드포인트가 셋이다

M-3 은 C6 만 적었지만 계약상 `conversions` 암호문을 읽는 오퍼레이션은 셋이다.

| 오퍼레이션 | 계약 키 경로 | 읽는 것 |
|---|---|---|
| `readConversion` | `paths./conversions/{conversion_id}.get` | 암호문 세 열 전부 |
| `updateConversion` | `paths./conversions/{conversion_id}.put` | 읽고 `edited_text` 를 쓴다 |
| `exportConversion` | `paths./conversions/{conversion_id}/export.get` | 자리표시자가 원문으로 복원된 최종본 |

셋 다 `x-private-response-headers.applies_to` 목록에 올라 있다 — 계약 자신이 **이 셋을 개인정보 응답으로 분류**했다(재확인: 동시 편집 뒤에도 세 항목 그대로). 처방은 셋 모두를 덮어야 하고, C6 한 곳만 고치는 처방은 **다음 두 커밋에서 같은 판정을 다시 받게 된다.**

---

## 3. 판정 ③ — 차단 등급

### 3.1 `codex-review` §5 척도 대입

| 갈래 | 성립하는가 | 근거 |
|---|---|---|
| **Critical ① (사건)** | **아니오 — 오늘** | 제품 호출자 0, HTTP 표면 0(§1). **C6 이 이 포트를 쓰면 그 커밋에서 성립한다**(§2) |
| **Critical ② (장치)** | **판정 갈림 — 승격 후보로 올린다** | §3.2 |
| **Major** | **예 — 확정** | 불변식이 걸린 자리에 강제자가 0이고, 첫 실사용 시점(C6)이 특정되며, 그 시점에 ① 로 전환된다 |
| **Minor** | 아니오 | — |

### 3.2 Critical ② 승격 여부 — 양쪽 해석을 병기한다

이 역할의 회색지대 규약(*"임의로 무해 판정하지 않는다 … 양쪽 해석과 근거를 병기하고 보수적 판정으로 표시한 뒤 리더에게 판단을 넘긴다"*)에 따라 그대로 적는다.

**승격 쪽 논거.** `codex-review` §5 의 ② 는 *"그 사건을 탐지·차단하는 게이트가 무력화된 상태(검증 없이 통과하는 경로 …)"* 다. 여기에는 **거짓 전칭 선언**이 실재한다(`DocumentPorts.kt:57-60`, §1.4). 그것은 이 저장소에서 「소유 조건이 어디 있나」를 확인하러 오는 사람에게 **닫혀 있다고 답한다.** 즉 사람이 수행하는 감사가 이 문장 때문에 통과한다 — 「검증 없이 통과하는 경로」의 감사 측 등가물이다. `CLAUDE.md` 규칙 4 는 **범위 선언형은 빈 선언에서 통과하면 안 된다**고 못박았고, 이것은 빈 선언보다 나쁜 **거짓 선언**이다.

**유보 쪽 논거.** §5 의 ② 가 든 예시는 전부 **실행되는 장치**(위조 가능한 증거 파일, 0건인데 성공하는 스크립트)다. KDoc 은 실행되지 않으므로 「무력화된 게이트」의 문언 범위에 들어가는지가 확실하지 않다. 그리고 1회차 리뷰와 교차 종합이 이미 **Major** 로 합의해 원장에 올렸다(`00_progress.md:1817`) — 근거의 새로움 없이 심각도를 올리면 등급이 표류한다.

**이 판정의 처분.** **Major 로 확정하고, Critical ② 승격은 리더 판정 사항으로 올린다.** 승격 여부가 **처방과 마감을 바꾸지 않는다**는 점을 명시한다 — 어느 쪽이든 해제 조건은 §0 통보의 ⒜⒝⒞ 이고 장치 마감 권고는 C3 이전(§6)이다. 따라서 이 승격 판정이 아무 작업도 막지 않는다.

**새로 붙는 근거 두 가지**(1회차·교차 종합에 없던 것, 승격 판단의 입력):
- ⓐ 클래스 KDoc 전칭 문장이 **거짓**이라는 사실(1회차는 「호출자 제한이 KDoc 한 문장뿐」이라고만 적었다 — **다른 문장**이다).
- ⓑ AAD 에 소유자가 없어 **암호가 이 사고를 전혀 막지 못한다**는 사실(§2.2-3). 「어차피 암호문이라 괜찮다」는 방어 논리를 미리 차단한다.

### 3.3 `migration-safety-gate` I-항목 대응

| I-항목 | 관련 | 판정 |
|---|---|---|
| **I-5** (404 소유권 은닉) | **정면** | **잠정 위반** — 소유 조건이 질의에 없다. 검증 2 (*"repository 쿼리에 소유자 조건이 `WHERE` 에 있는지"*)에 대해 이 두 포트는 **없음**. 오늘 응답 표면이 없어 사건은 미발생 |
| I-4 (평문 저장 금지) | **아니다** | 이 결함은 저장 형식을 바꾸지 않는다. C-1 과 병합하지 않는 이유(§8.2 교차 종합) |
| I-7 (AEAD 정확성) | **아니다 — 다만 방어가 되지도 않는다** | round-trip·변조 거부·nonce 는 이 결함과 무관하다. §2.2-3 이 확인한 것은 **AEAD 가 접근 통제를 대신하지 못한다**는 사실뿐이며 이는 I-7 위반이 아니다 |
| I-6 (private 응답 헤더) | 인접 | 계약 `x-private-response-headers` 가 문제의 세 오퍼레이션을 이미 등재했다(§2.4). 헤더 축은 별개이고 이 판정에서 **준수/위반을 판정하지 않는다**(그 세 경로가 아직 없다 — **확인 불가**) |

---

## 4. 판정 ④ — 처방

### 4.1 먼저 장치를 분류한다 (`CLAUDE.md` 규칙 4)

**오늘 이 자리에 서 있는 장치의 분류부터 확정한다.**

| 오늘의 장치 | 위치 | 분류 | 도달 |
|---|---|---|---|
| *"소유자 조건이 인터페이스에 박혀 있다 — 읽기 메서드가 전부 …"* | `DocumentPorts.kt:57-60` | **범위 선언형** | **강제자 0, 그리고 문면이 거짓** |
| *"호출자는 회전 유스케이스 하나로 제한한다"* | `DocumentPorts.kt:99-100` | **범위 선언형** | **강제자 0** |
| `ConversionRepository` 의 소유권 규약 | (없음) | — | **선언조차 없음** |

규칙 4 의 처방은 명확하다 — **범위 선언형이 빈(또는 거짓) 선언으로 통과하고 있으면 선언을 넓히는 것이 아니라 탐지형으로 갈아탄다.** 같은 파일에서 같은 처방이 이미 한 번 집행됐다: 계획 §9.2-ter **D-r**(*"§4.3 이 「열 하나짜리 갱신 메서드를 만들지 않는다」를 산문으로만 두었다 → 탐지형 장치 신설"*) → `EnvelopeColumnWriteGuardTest`. **M-3 은 그 D-r 의 이웃 자리이고 같은 결함 구조다.**

### 4.2 후보와 대가

| # | 후보 | 분류 | 무엇을 막나 | 대가 · 한계 |
|---|---|---|---|---|
| **A** | **소유 술어 없는 `documents`·`conversions` 접근 SQL 을 소스 전수에서 탐지**하는 테스트. 분모는 열거하지 않고 파생하고(테이블 이름은 `EncryptedField.wireName` 의 `테이블.컬럼` 에서, 또는 스키마에서), 유지보수 전용 문장은 **정확 열거 고정값**(`EXPECTED_*` 형태)으로 핀 박아 늘거나 줄면 빨개지게 한다 | **탐지형** | 포트든 신규 질의든, 사용자 경로든 아니든 **소유 술어 없는 문장이 새로 생기면 그 diff 에서 빨개진다.** C4·C5·C6·C7·export 를 한 장치가 덮는다 | 문자열 조립 SQL 은 못 본다(`EnvelopeColumnWriteGuardTest` 가 같은 한계를 이미 문서화했다). 고정 목록이 늘 때 **왜 늘었는지**를 리뷰가 봐야 한다 — 그것이 이 장치의 작동 방식이다 |
| **B** | **유지보수 포트를 타입으로 분리.** `lockSourceText`/`lockEnvelope`/`rewriteEnvelope` 를 별도 인터페이스(예: 봉투 유지보수 포트)로 옮기고 **회전 유스케이스에만 주입**한다. 사용자 경로 유스케이스는 그 타입 참조를 아예 갖지 않는다 | **강제·표현형** | 「호출자는 회전 하나」를 KDoc 이 아니라 **타입**이 말한다. 사용자 경로에서 실수로 부르는 것이 컴파일 단계에서 불가능해진다 | Spring 이 그 빈을 컨트롤러에도 주입해 줄 수 있다 — **막지는 못하고 의도적 배선을 요구할 뿐**이다(정직하게 적는다). 회전 테스트 대역이 바뀐다. 인터페이스·빈이 하나 는다 |
| **C** | 사용자 경로용 **소유자 인자 필수 읽기 포트 신설**(`ownerId` + SQL 조인). C6·C7·export 가 그것만 쓴다 | **강제·표현형** | 올바른 경로를 **존재하게** 만든다. §2.1 의 「검색 결과가 하나뿐이라 잘못된 것을 고른다」를 해소 | 이것만으로는 옛 포트가 그대로 남아 여전히 부를 수 있다. **A 또는 B 없이는 강제가 아니다** |
| **D** | `DocumentPorts.kt:57-60` 전칭 문장 정정 + `ConversionRepository` 에 같은 규약 명시 | **범위 선언형** | 감사자가 잘못 읽는 것을 막는다 | **이것만이면 강제자는 여전히 0.** 규칙 4 가 금지한 처분이다 |
| **E** | C6 커밋에 교차 사용자 통합 테스트(사용자 B → 404) | **탐지형(국소)** | I-5 의 실행 증거를 만든다. §6 Security 게이트 「교차 사용자 접근 0」의 근거 | **그 엔드포인트에만 닿는다.** 포트 표면을 덮지 않아 C7·export 에서 같은 판정을 다시 받는다. 필수이나 M-3 의 해답은 아니다 |

### 4.3 권고 — **A 를 주(主)로, C·D 를 같은 변경 단위로 묶는다**

> **권고: A(탐지형) + C(올바른 포트 신설) + D(문면 정정)를 한 변경 단위로. B 는 선택.**

단일 권고를 A 로 고른 이유 셋.

1. **규칙 4 가 지시하는 처분이 정확히 A 다.** 오늘 서 있는 장치가 범위 선언형이고 강제자가 0이며 문면이 거짓이다. 선언을 다듬는 처분(D 단독)은 규칙이 금지한다.
2. **가장 그럴듯한 실패를 B·C 는 못 막는다.** C6 구현자의 가장 자연스러운 실수는 이 포트를 부르는 것이 아니라 **`JdbcConversionRepository` 에 소유 술어 없는 새 질의를 하나 더 쓰는 것**이다. B(타입 분리)도 C(새 포트)도 그 새 질의를 막지 못한다. **A 는 막는다.**
3. **선례가 같은 파일에 있고 이미 집행됐다.** D-r → `EnvelopeColumnWriteGuardTest`. 분모 파생·빈 분모 실패·합성 probe 음성 대조·정확 열거 핀까지 형태가 이미 서 있어 **재사용 가능**하다(`CLAUDE.md` 「기구현 확인」 — 새로 만들 것이 아니라 이웃 장치를 확장하는 편이 맞는지부터 구현 레인이 판단해야 한다).

C 를 함께 묶는 이유: A 만 있으면 C6 구현자는 「탐지기가 빨개지는데 쓸 포트가 없다」는 상태를 만난다. 올바른 길이 없는 금지는 우회를 부른다.
D 를 함께 묶는 이유: 거짓 전칭 문장을 남겨 두면 **다음 감사가 같은 오독을 반복**한다.
B 를 선택으로 둔 이유: 값은 있으나 A 와 겹치는 부분이 크고 대가(인터페이스·빈·테스트 대역)가 실재한다. 리더가 비용을 보고 정할 사안이다.

### 4.4 **은폐형 판정** — 이 둘은 처방이 아니다

| 후보 | 왜 은폐형인가 |
|---|---|
| **`lock` 접두 메서드를 탐지 대상에서 제외하는 규칙** | 전형적인 면제 패턴이다. 채택하면 이후 `lockXxx` 라는 이름을 붙인 모든 질의가 **처음부터 탐지 밖**에 태어난다. 규칙 4: 은폐형은 ⑴이 참이어도 넓히지 않는다 — **탐지형으로 갈아탄다** |
| **`privacy-allow:` 계열 호출 지점 표기로 이 두 포트를 누르는 것** | 스캐너의 표기는 「값의 모양이 오탐」인 자리를 위한 통로이고(스캐너 주석 §4-octies.3), **소유권 우회는 정당한 오탐이 존재할 수 없는 종류**다. 이 자리에서 표기를 쓰면 표기가 새 면제 목록이 된다. 스캐너 자신이 *"그 규칙에서 「오탐이니 눌러 달라」가 나오면 그것은 표기가 아니라 **판정 요청**"* 이라고 적었고, 이 문서가 그 판정 요청의 답이다 |

**경계선 하나를 명시한다.** 후보 A 의 「유지보수 전용 문장 고정 목록」은 겉보기에 면제 목록과 닮았다. 둘을 가르는 것은 **일치 방식**이다.
- **패턴으로 맞추는 예외**(이름 접두·경로 표식·정규식) = **은폐형**. 새 위반이 조용히 그 안에 태어난다.
- **정확 열거를 핀으로 고정**하고 늘거나 줄면 실패시키는 것 = **탐지형**. 목록이 커지는 것 자체가 diff 로 드러나 리뷰에 올라온다. 이 저장소가 `EXPECTED_FILES`/`EXPECTED_STATEMENTS`·`TEST_CLASSES`·`FLOOR_TEST_CLASSES` 에서 쓰는 형태다.

A 를 채택하면 **반드시 후자여야 한다.** 전자로 구현되면 처방 자체가 은폐형이 되고, 그때는 이 판정이 만든 것이 결함이다.

---

## 5. 판정 ⑤ — 음성 대조 설계

### 5.1 먼저: **지금 이 자리를 지키는 장치는 0개다** (실측)

가장 중요한 사실이라 실행 근거를 붙인다.

```
$ .claude/skills/kotlin-migration/scripts/run_gate.sh \
    "uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --rule OWNERSHIP-403 --no-fail"
[run_gate] cmd: uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py --rule OWNERSHIP-403 --no-fail
검사 범위: 전수. 검사 파일 310개.
2차 판정으로 제외한 적중 … - `OWNERSHIP-403` — 불변식을 집행·명명하는 형태(403 을 만들어 낼 수 없는 자리) 6건
[run_gate] exit: 0
```

`OWNERSHIP-403` 은 **`DocumentPorts.kt` · `JdbcConversionRepository.kt` · `JdbcDocumentRepository.kt` 어느 줄도 적중시키지 않았다.** 당연하다 — 이 규칙이 찾는 것은 **403 토큰**이지 소유 술어의 부재가 아니다. 규칙 이름이 `OWNERSHIP-` 으로 시작해 이 자리를 덮는 것처럼 **보이지만** 덮지 않는다. (`CACHE-HEADER` 가 「붙은 줄을 세느라 없는 곳을 못 본다」로 스킬에 이미 기록된 것과 **같은 형태**다: 부재를 찾아야 하는데 존재를 세고 있다.)

나머지 축도 전부 0이었다.

```
$ grep -rn "archunit\|ArchUnit" backend-kotlin/ gradle/      → 0건
```

- 포트 시그니처를 단언하는 테스트: 없음(전수 grep).
- `EnvelopeRotation` 이외의 호출자를 금지하는 실행 장치: 없음.
- 남은 것은 KDoc 문장 둘, 그중 하나는 거짓(§4.1).

**대조군으로 확인한 것** — `listOwned` 쪽은 장치가 **있다**.

| 테스트 | 위치 | 무엇을 잰다 |
|---|---|---|
| `목록이 소유자 범위이고 안정 정렬이다` | `JdbcDocumentStoreTest.kt:352-368` | 남의 문서를 심어 두고(`insertStrangerDocument`) 목록에 섞이지 않는지 |
| `작업 공간 필터에도 소유자 조건이 남는다` | `JdbcDocumentStoreTest.kt:370-383` | 남의 작업 공간을 지목해도 소유자 조건이 남는지 |
| `남의 작업 공간 목록은 404 다` | `DocumentServiceTest.kt:324-325` | 빈 목록이 아니라 404 인지 |

즉 이 저장소는 이 유형의 장치를 **어떻게 세우는지 이미 알고 있고**, 두 포트에만 그것이 없다. 「아직 세울 줄 몰라서」가 아니라 **그 자리만 비어 있다.**

### 5.2 처방 A 를 떼면 무엇이 빨개져야 하는가

**네 갈래 전부 성립해야 한다.** 넷째가 핵심이다 — 앞의 셋만 있으면 「합성 probe 에서만 도는 탐지기」가 되고, 이 저장소는 그 실패를 이미 겪었다(계획 §9.2-quater D-r — *"제품 배선을 재지 않았다"*).

| # | 음성 대조 | 기대 | 무엇을 증명하나 |
|---|---|---|---|
| **N-1** | 합성 probe 로 소유 술어 없는 질의를 심는다: `SELECT easy_text_encrypted FROM conversions WHERE id = :id` | **빨강** | 탐지 성립 |
| **N-2** | 합성 probe 로 소유 술어 있는 질의를 심는다: `... FROM conversions c JOIN documents d ON d.id = c.document_id WHERE c.id = :id AND d.user_id = :ownerId` | **초록** | 과잉 탐지 0 |
| **N-3** | 분모를 0으로 만든다(대상 문장을 하나도 못 찾는 상태) | **빨강** | **빈 분모는 통과가 아니다**(규칙 4 ⑶). `EnvelopeColumnWriteGuardTest.requireNonEmpty` 와 같은 축 |
| **N-4** | **제품 코드**에서 `JdbcDocumentRepository.listSql` 의 `WHERE d.user_id = :ownerId`(`:225`)를 제거한다 | **빨강 — 새 탐지기에서** | **탐지기가 제품 코드에 닿는다**는 증명. 이것 없이는 분모가 probe 뿐이어도 초록이다 |

**N-4 에 딸린 주의.** 오늘 `listSql` 의 소유 술어를 제거하면 **기존 테스트가 이미 빨개진다**(`JdbcDocumentStoreTest:356-363`). 그러므로 N-4 의 판정 기준은 「빨개지는가」가 아니라 **「새 탐지기가 그 줄을 지목하는가」**여야 한다. 실패 메시지에 해당 파일·문장이 나오는지까지 확인해야 대조가 성립한다. 이 구분을 놓치면 기존 테스트의 빨강을 새 장치의 증거로 오독하게 된다.

**처방 B(타입 분리)를 채택할 경우의 음성 대조**: 사용자 경로 유스케이스에서 유지보수 포트를 부르는 코드를 심으면 **컴파일 실패**여야 한다. 이것은 테스트가 아니라 빌드 단계의 대조라, 「빨개지는 것을 확인했다」를 기록으로 남기려면 별도 절차(예: 컴파일 실패를 단언하는 소스 스캔)가 필요하다 — B 를 고를 때 이 비용을 함께 계산해야 한다.

**이 문서가 실행하지 않은 것**: 위 넷 전부 **미실행**이다. 셋(N-1·N-2·N-3)은 아직 존재하지 않는 장치에 대한 것이고, N-4 는 **제품 코드 변조**를 요구하는데 이 역할은 코드를 고치지 않는다. 설계로만 넘긴다. 구현 레인이 실행하고 결과를 남겨야 해제 조건 ⒝ 가 닫힌다.

---

## 6. 판정 ⑥ — 마감 재확인

### 6.1 사건 경계는 **C6 그대로다** — C3 으로 앞당길 근거가 없다

리더가 물은 대로 C3 을 검토했고, **앞당길 근거를 찾지 못했다.**

- C3(`POST /documents`)은 **쓰기**다. `DocumentService.store`(`DocumentService.kt:164-210`)가 부르는 것은 `insert`·`insertPending`·`enqueue` 뿐이고 두 포트를 부르지 않는다.
- C4(`GET /documents`)는 `listOwned` 를 쓴다 — **소유 술어가 이미 SQL 에 있다**(`:225`).
- C5(`DELETE /documents/{id}`)는 새 삭제 포트를 만든다. 두 포트를 쓰지 않는다.
- **C6 이 `conversions` 암호문을 읽는 첫 커밋이다.** 사건 축이 열리는 곳이 정확히 거기다.

C3 이 「문서 본문이 처음 HTTP 로 들어오는 커밋」이라는 사실은 **데이터가 생기는 시점**을 앞당기지만 **접근 경로가 열리는 시점**은 아니다. 이 둘을 섞으면 마감이 근거를 넘는다.

### 6.2 그러나 **장치 마감은 C3 이전을 권고한다** — 근거 셋

사건 경계(C6)와 장치 마감을 분리한다.

1. **비용이 지금 최소이고 이후 단조 증가한다.** 오늘 두 포트의 제품 호출 지점은 2곳(둘 다 `EnvelopeRotation`)이다. C6·C7·export 가 선 뒤에는 고칠 자리가 늘고, 이미 통과한 테스트가 처방의 기준선이 된다.
2. **같은 장치가 C4·C5 를 함께 덮는다.** 특히 **C5 는 파괴적**이다 — `DELETE /documents/{id}` 의 질의에 소유 술어가 빠지면 남의 문서가 삭제되고 FK CASCADE 로 변환까지 함께 파기된다. 읽기보다 나쁘고 **되돌릴 수 없다**(§2.3 30일 보존 정책과 무관하게 즉시 소실). 그 커밋이 C6 보다 **앞**이다. 처방 A 는 그 자리를 덮는다.
3. **나중에 세운 탐지기는 이미 있는 것에 맞춰 재단된다.** 이 저장소의 실패 목록에 그 형태가 있다(`CLAUDE.md` 변경 이력 — 열거형 도달, 게이트 핀 동시 축소). 코드가 없을 때 세운 분모가 정직하다.

**권고 정리**: 사건 축의 마감은 **C6 유지**, 장치 축(해제 조건 ⒝·⒞)의 마감은 **C3 이전으로 앞당김**. 해제 조건 ⒜(올바른 포트)는 C6 이 필요로 하는 것이므로 **C6 과 같은 단위**로 두어도 무방하다.

이 분리를 채택하지 않고 전부 C6 으로 두더라도 **차단 판정은 바뀌지 않는다** — C6 이 열리기 전에 ⒜⒝⒞ 가 닫히면 된다. 앞당김은 비용 최소화 권고이지 차단 조건이 아니다.

---

## 7. 부수 관찰 — M-3 밖 (판정에 넣지 않는다)

**스캐너를 `--rule` 로 좁혀 돌리면 다른 규칙의 억제 표기가 BLOCK 으로 뜬다.**

- `--rule OWNERSHIP-403` 로 돌렸을 때: `[BLOCK] MARKER … 알 수 없는 규칙 id LOG-BODY` **7건**(`FlywayBaselineGuard.kt` 1 · `scripts/collect_*.py` 6).
- 같은 리비전에서 규칙 지정 없이 전수로 돌렸을 때: 같은 7건이 **정상 억제**로 처리되고 BLOCK 0건.

즉 단일 규칙 실행에서 나온 이 BLOCK 은 **실제 위반이 아니라 실행 방식의 산물**이다. 이 판정에는 영향이 없으나, 감사자가 단일 규칙으로 돌리는 일이 흔하고 **BLOCK 출력을 무시하도록 학습시키는 방향**이라 기록해 둔다. 처분은 이 역할의 몫이 아니다(스캐너 소유 레인 판단 — 이 문서는 스캐너를 고치지 않았다).

---

## 8. 이 판정이 확인하지 못한 것

| 항목 | 상태 | 사유 |
|---|---|---|
| I-6(private 헤더)이 C6·C7·export 응답에 붙는가 | **확인 불가** | 그 세 응답이 아직 없다. 계약 `x-private-response-headers:805-807` 에 등재된 사실만 확인 |
| §5 음성 대조 4건의 실제 실행 | **미실행** | 장치가 없고(N-1~N-3), 제품 코드 변조가 필요하다(N-4). 이 역할은 코드를 고치지 않는다 |
| C5 삭제 경로의 소유 술어 | **확인 불가 — 해당 코드 없음** | §6.2-2 에서 위험만 지목했다. 판정은 그 커밋에서 |
| Critical ② 승격 | **리더 판정 대기** | §3.2 에 양쪽 논거 병기 |
| 후보 B(타입 분리) 채택 여부 | **리더 판정 대기** | §4.3 |

---

## 9. 통보 대상과 추적

| 대상 | 내용 |
|---|---|
| **리더(오케스트레이터)** | 조건부 차단 발효 시점(C6) · 장치 마감 앞당김 권고(C3 이전) · Critical ② 승격 판정 요청 · 후보 B 채택 여부 |
| **`kotlin-implementer`** | 해제 조건 ⒜⒝⒞ 와 §5 음성 대조 설계. **A 를 구현할 때 §4.4 의 경계선(정확 열거 핀 ↔ 패턴 면제)을 지킬 것** |
| **`contract-keeper`** | ⒜ 의 404 갈래가 `paths./conversions/{conversion_id}.get.responses.404` 와 일치하는지. C7·export 도 같은 축 |
| **`migration-reviewer`** | 보안 축 감사 결과로 종합 리뷰에 포함 |

**추적**: 이 차단은 C6 이 열리기 전까지 열린 채로 둔다. 수정 보고를 받아도 회신을 그대로 신뢰하지 않고 §5 의 음성 대조 결과를 실행 근거로 받은 뒤에만 해제한다.
