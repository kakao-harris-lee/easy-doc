# L-1 잔여 판정 + 저장 정의역 조항 — 계약 레인 판정 기록

**작성**: `contract-keeper` · 2026-08-20 · **C3 착수 직전**
**착수 HEAD**: `66f008b` · **커밋**: 하지 않았다 (리더 검토 후)
**전신**: `04_contract-keeper_gate26-contract-actions.md` §2-3·§2-4·§7 미결 ⑴⑵ ·
교차 종합 `reviews/04_documents_cross.md` §3 행 2·§11 · 구현 계획 §9 질문 ②④·§6.3·§6.4

**성격**: 리더가 지시한 판정 **3건**을 내리고 계약을 개정했다. 계약 파일과 계약 명세만
고쳤다 — **`backend-kotlin/**` 는 한 줄도 건드리지 않았다**(§5 실측). 구현 레인이 해야 할
일은 §4 에 파일·심볼 단위로 있다.

**이 회차가 스스로 잡은 것 하나**: 판정 2 의 첫 답이 **틀렸다.** 계약 문면을 믿고 DC-19 의
무대를 「저장 암호화 키 미배선」으로 옮기려다, 그 줄도 죽어 있음을 코드 실측으로 발견해
답을 바꿨다(§2-4). 계약 문면에 대한 거짓 전제를 저작 레인의 실측이 잡은 **두 번째** 사례다.

---

## 0. 한 줄 요약

| 판정 | 답 | 근거 | 계약 변경 |
|---|---|---|---|
| **1 — `QueueUnavailableException` → 502 매핑** | **내린다.** 매핑도, 오퍼레이션 선언도, 컴포넌트도 전부 | **G1** — 던지는 제품 코드 **0곳**, 502 를 낼 수 있는 오퍼레이션 **0개** | `'502'` 선언 · `BadGateway` 컴포넌트 · `EnqueueFailed` 두 자리 · 순서 문장 · **`x-retired-responses` 신설** |
| **2 — `ServiceUnavailable` 큐 줄과 DC-19** | **(나) 조항을 내린다.** DC-19 의 무대는 **인증 서명 키**로 옮긴다. **덤으로 형제 줄(저장 암호화)도 죽어 있어 함께 내렸다** | **G1** ×2 — 큐 배선 설정 예외 **0곳** · 저장 암호화는 **2026-08-19 리더 판정으로 기동 fail-fast** | 두 줄 + 예시 둘 삭제 · 남은 줄의 적용 범위 정정 |
| **3 — X1 거부 문구** | **전용 조항을 더한다.** 본문은 **거절(422)**, 제목은 **정제** | **G2** — `migration-safety-gate` I-7 round-trip 요구 | **`x-stored-text-domain` 신설** · 422 예시 추가 · `x-title-policy` 에 서로게이트 정제 + 사유 |

**`info.version`**: 1.2.0 → **1.3.0**. **소유자 단독 판단이 아니다** — 리더 지시로 집행했다.

---

## 1. 판정 1 — `QueueUnavailableException` → 502 를 **함께 내린다**

### ① 고른 답

**502 를 계약에서 통째로 내린다.** 세 자리(502 조항 · `EnqueueFailed` 값 · 순서 문장)에
더해 **컴포넌트 `BadGateway` 까지** 내렸고, 되살아나지 않게 **`x-retired-responses`** 를
신설했다.

「매핑은 두고 오퍼레이션 선언만 내린다」를 고르지 않았다. 그러면 `BadGateway` 가 참조
없는 고아가 되고, 구현은 계약이 선언하지 않은 상태 코드를 낼 수 있는 채로 남는다.

### ② 근거 — 실행한 명령과 출력

**E1. 그 예외를 던지는 제품 코드가 있는가.**

```
$ grep -rn "throw QueueUnavailableException" backend-kotlin --include="*.kt"
  (출력 없음)   exit=1
```

**0곳이다.** 전체 언급을 봐도 살아 있는 것은 타입 선언과 매핑뿐이다.

```
$ grep -rn "QueueUnavailableException" backend-kotlin --include="*.kt"
core/.../exceptions/DomainExceptions.kt:73:class QueueUnavailableException(message: String) : EasyDocException(message)
api/src/test/.../support/ErrorProbeController.kt:19: (import)
api/src/test/.../support/ErrorProbeController.kt:187: "queue" to { QueueUnavailableException("…") }
api/src/main/.../error/GlobalExceptionHandler.kt:12: (import)
api/src/main/.../error/GlobalExceptionHandler.kt:383: is QueueUnavailableException,
```

**살아 있는 유일한 생성 지점이 테스트 프로브다.** 즉 이 매핑은 **자기 테스트만이 도달하는
조항**이었다 — 하네스 규칙 3 이 특히 의심하라는 형태다.

**E2. 그 예외가 왜 필요 없어졌는가 — 등록은 저장과 한 트랜잭션이다.**

```
$ sed -n '188,203p' application/.../document/DocumentService.kt
    val sealed = cipher.encrypt(PlainBody(text), documentId, EncryptedField.DOCUMENT_SOURCE_TEXT)
    storage.documents.insert(ownerId, draft, sealed)
    val conversion = storage.conversions.insertPending(…)
    storage.queue.enqueue(conversionId)
```

셋 다 `transaction.inTransaction { … }` 안이다(계획 §9.2-bis **D-m**, 리더 지시).

```
$ sed -n '32,44p' infrastructure/.../queue/JdbcConversionQueue.kt
    override fun enqueue(conversionId: UUID) {
        jdbc.sql("INSERT INTO conversion_jobs … ON CONFLICT (conversion_id) DO NOTHING")
            .param(…).update()
    }
```

**도메인 예외로 감싸지 않는다.** INSERT 가 실패하면 Spring 의 `DataAccessException` 이
올라오고, 그것은 도메인 예외가 아니므로 최후 핸들러에 착지한다.

**E3. 그때 실제로 나가는 것은 500이다.**

```
$ sed -n '96,110p;130,137p' api/.../error/GlobalExceptionHandler.kt
    val mapping = mappingFor(exception)
    if (mapping == null) { … return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(UNMAPPED_DOMAIN_MESSAGE)) }
    …
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception): ResponseEntity<Any> {
        return jsonError(HttpStatus.INTERNAL_SERVER_ERROR, ErrorResponse(UNEXPECTED_MESSAGE))
```

두 문구 모두 계약 `InternalError` 의 **선언된 예시**다(`unmapped_domain` · `unexpected`).
**교차 종합 행 2 가 양쪽 레인 일치로 보고한 「실제 실패는 500으로 나간다」와 같다.**

**E4. 502 의 둘째 사유(`LLMProviderError`)도 도달이 없다.**

```
$ git show HEAD:contracts/easy-doc-v1.yaml | grep -n "LLM\|Llm"
375:    2. **마스킹 우회** — 마스킹은 LLM 전송 직전(워커)의 일이라 …
1715:          - `LLMProviderError`(및 하위 `LLMTruncatedError`·`LLMEmptyResultError`)
```

**계약이 LLM 을 말하는 자리는 `BadGateway` 하나뿐이고**, 375 행이 그 이유를 스스로 적는다 —
**LLM 은 워커의 일이다.** 14개 오퍼레이션 중 동기로 LLM 을 부르는 것이 없고, LLM 실패는
HTTP 상태가 아니라 `ConversionResponse.failure_code` 값으로 사용자에게 간다
(React `failureMessages.ts` 가 `LLMProviderError` 를 **실패 코드**로 다루는 것이 그 증거다).

**E5. 502 를 선언한 오퍼레이션은 하나뿐이었고, 컴포넌트 참조도 그 하나뿐이다.**

```
$ git show HEAD:contracts/easy-doc-v1.yaml | grep -nE "^\s+'502':"
1040:        '502': { $ref: '#/components/responses/BadGateway' }
$ git show HEAD:contracts/easy-doc-v1.yaml | grep -c "responses/BadGateway"
1
```

**개정 후 검증** (`$ref` 무결성 · 고아 컴포넌트 · 폐기 코드 재등장):

```
$ uv run python …/validate_contract.py
YAML parse OK; version = 1.3.0
dangling refs: []
declared statuses: ['200','201','202','204','401','404','409','413','422','500','503']
  retired 502: declared at []  -> OK
example == section detail: True
```

고아 컴포넌트 출력 **0건**(스크립트가 있으면 `ORPHAN component:` 를 찍는다).

### ③ 고르지 않은 것의 대가

| 고르지 않은 답 | 대가 |
|---|---|
| **매핑을 둔다 (계약 조항만 삭제)** | `BadGateway` 가 **참조 0인 고아 컴포넌트**로 남는다(게이트 26 ⓓ 가 지목한 자리). 더 나쁜 것은 구현이 **계약이 선언하지 않은 502** 를 낼 수 있는 채로 남는다는 것 — 계약이 정하는 것은 나간 바이트인데 그 바이트가 계약 밖이 된다. `ErrorContractTest` 의 두 행은 초록으로 남지만 그 초록이 **자기 프로브만 도달하는 조항**을 지킨다 |
| **매핑도 조항도 둔다 (현행 유지)** | 게이트 26 이 이미 판정한 대로 「도달 0인 조항」이 그대로 남는다. 그리고 C2 가 등록을 트랜잭션 안에 넣은 지금, 계약 문면(*"이미 커밋된 변환을 표시한 뒤 502"*)은 **구현이 만들 수 없는 상태를 요구**한다 — 교차 종합 행 2 가 합의 Major 로 올린 것이 정확히 이것이다 |
| **502 를 500 대신 쓰도록 구현을 바꾼다** | 되돌릴 것이 없는 실패에 「재시도할 값어치가 있다」는 신호를 붙이게 된다. 등록이 실패하면 **아무것도 접수되지 않았으므로** 클라이언트가 할 일은 「같은 문서를 다시 올리기」이지 「폴링 재시도」가 아니다. 그리고 요구가 없다 — G 근거를 지목할 수 없다 |
| **`BadGateway` 를 남겨 Phase 5 를 대비한다** | 대비할 대상이 없다(E4). 정말 동기 하위 시스템 호출이 생기면 그때 세우는 것이 옳고, `x-retired-responses.if_it_should_return` 이 그때 갖춰야 할 네 조각을 적어 두었다 |

### ④ 부수 판정 — 순서 문장과 멱등 문장은 **성격이 다르다**

| 문장 | 처분 | 사유 |
|---|---|---|
| 검사 순서 꼬리 `→ 커밋 → 큐 등록(502)` | **재서술** | 순서가 실제로 바뀌었다(등록이 커밋 **앞**이다). DC-16c 가 이 순서를 고정하므로 문면이 낡으면 케이스가 낡는다 |
| *"등록은 작업 id를 변환 id로 고정해 멱등하다"* | **유지** | **여전히 참이고 구현이 그것을 지킨다** — `ON CONFLICT (conversion_id) DO NOTHING`. 이 문장은 Redis 전제가 아니라 **중복 LLM 호출 방어의 첫 겹**이다(§5 Phase 7 즉시 중단 기준). 함께 지웠으면 방어 근거가 사라졌을 것이다 |
| `failure_code` 의 `EnqueueFailed` 요구 | **삭제 + 폐기 표시** | 그 값을 낼 수 있는 경로가 없다. 폐기 사실을 남기지 않으면 React 부채(`failureMessages.ts`)가 왜 죽었는지 알 수 없다 |

---

## 2. 판정 2 — `ServiceUnavailable` 의 큐 줄: **(나) 내린다.** 그리고 형제 줄도

### ① 고른 답

**(나) 조항을 내린다.** 그리고 gate26 §2-3 이 요구한 대로 **X-C6 의 둘째 팔과 DC-19 를 함께
정했다**:

- **X-C6 을 「503 ≠ 500」으로 재정의**한다. **두 팔 모두 도달이 실재한다.**
- **DC-19 의 무대는 「인증 서명 키가 계약 하한 미만인 구성」**이다. C-P 계층은 유지된다.
- **DC-18 은 500 + 전량 롤백**을 재는 자리가 된다.

**계약에서 함께 내린 것이 하나 더 있다 — 「저장 암호화 키 미설정 → 문서 API 전체」.**
리더가 지목하지 않았고 나도 처음에는 그것을 DC-19 의 새 무대로 쓰려 했다. 재 보니 죽어 있었다.

### ② 근거 — 실행한 명령과 출력

**E6. 큐 배선에 대한 설정 예외는 없다.**

```
$ grep -rn "throw ConfigurationException" backend-kotlin --include="*.kt" | grep "/src/main/"
infrastructure/.../crypto/AesGcmContentCipher.kt:153       ← 쓰기 키 부재 (방어)
infrastructure/.../crypto/CryptoConfiguration.kt:202       ← 기동 자기점검
infrastructure/.../llm/LlmProviderConfiguration.kt:87      ← LLM 벤더 설정
infrastructure/.../llm/AnthropicProvider.kt:110, :189      ← LLM 벤더 설정
infrastructure/.../auth/JwtAccessTokens.kt:89, :156, :161  ← 서명 키
```

**여덟 자리 전건. 큐에 관한 것이 하나도 없다.** 큐는 `conversion_jobs` 테이블이고 접속은
같은 `JdbcClient` 이므로 **배선이라고 부를 것을 갖지 않는다.** 「큐만 미배선」은 독립한
구성 상태로 존재하지 않는다.

**E7. `app/api/deps.py` 의 `ConfigurationError` → 503 이 Kotlin 에서 무엇에 대응하는가**
(리더가 지시한 실측). 위 여덟 자리를 계약의 네 줄에 대응시키면:

| 계약 `ServiceUnavailable` 의 줄 | Kotlin 대응 | 도달하는가 |
|---|---|---|
| JWT 서명 키 미설정·하한 미만 | `JwtAccessTokens.kt:89·156·161` | **예.** `AuthUnavailableContractTest` 가 이미 실행으로 증명(가입·로그인·`/auth/me`·`/workspaces` 503, 같은 구성에서 `/health` 200) |
| 저장 암호화 키 미설정 | `AesGcmContentCipher.kt:153` (방어) · `CryptoConfiguration.kt:202` (기동) | **아니다 — §2-4** |
| 큐(Redis) 미배선 | **없음** | **아니다** |
| DB 세션 팩토리 미배선 | **확인하지 않았다** | **미측정 — 남겼다(§2-5)** |

**E8. LLM 쪽 세 자리는 이 판정의 대상이 아니다.** 워커(Phase 5)의 일이고 14개 오퍼레이션
중 그것을 동기로 부르는 것이 없다(§1 E4). 계약이 LLM 설정 실패를 503 으로 선언한 자리도 없다.

### ③ 고르지 않은 것의 대가

| 고르지 않은 답 | 대가 |
|---|---|
| **(가) 트리거를 재정의해 조항을 유지** | **재정의할 상태가 없다.** 「설정 미배선」으로 바꾸면 그 설정이 무엇인지 대야 하는데 큐에는 설정이 없다(E6). 「DataSource 미배선」으로 바꾸면 그것은 넷째 줄이고, 큐 고유의 줄이 아니게 된다 — 같은 사실을 두 줄로 적는 것이라 **두 벌이 갈릴 자리**만 늘린다. 그리고 DC-19 는 여전히 무대가 없다 |
| **(나′) 큐 줄만 내리고 형제 줄은 그대로** | **DC-19 를 무대 없는 케이스로 다시 만든다.** 실제로 이 회차가 그 답을 먼저 골랐고 §2-4 의 실측이 막았다. 형제 줄을 재지 않았으면 계약·명세·구현 지시가 **전부 존재하지 않는 구성**을 가리킨 채 커밋됐을 것이다 |
| **(다) X-C6 축 자체를 폐기** | 남은 구분(503 ↔ 500)이 **실재하고 사용자 행동이 다르다**(운영이 고친다 ↔ 다시 올린다). 축을 지우면 큐 등록 실패가 503 으로 나가는 구현도, 설정 문제가 500 으로 나가는 구현도 아무 데서도 안 걸린다 |
| **DC-19 를 폐기하고 X-A5(WL-7) 에 흡수** | 검토했고 고르지 않았다. 502 가 없어진 지금 `POST /documents` 가 선언하는 5xx 는 500·503 둘뿐이고 **DC-18 이 「503이 아니다」를 단언한다.** 그 부정 단언이 **이 오퍼레이션에서 한 번도 나온 적 없는 코드**를 상대로 서면, 계약의 503 선언 자체가 여기서 도달 0이 된다 |

### ④ 첫 답이 틀렸다 — 형제 줄도 죽어 있었다

**처음 고른 답**: DC-19 의 무대를 **「저장 암호화 키 미배선 → 문서 API 전체」**로 옮긴다.
계약이 그렇게 적고 있었고, 업로드 경로가 실제로 `cipher.encrypt` 를 부르므로 그럴듯했다.

**막은 실측:**

```
$ sed -n '104,114p' infrastructure/.../crypto/CryptoConfiguration.kt   (KDoc)
 * ## 기동을 막는다 (게이트 25 F-2·F-3·X8, 리더 판정 2026-08-19)
 * … 저장 암호화 키가 없거나 **틀리면** … **성공한 채로 열 수 없는 행을 남긴다**. 뒤엣것은 되돌릴 수 없다.
 * 그래서 기동 시점에 세 가지를 확인하고, 하나라도 어긋나면 **앱을 띄우지 않는다**.

$ sed -n '200,206p' …/CryptoConfiguration.kt
        if (problems.isNotEmpty()) {
            throw ConfigurationException("저장 암호화 설정이 기동 자기점검을 통과하지 못했다. 앱을 띄우지 않는다. …")

$ sed -n '224,236p' …/CryptoConfiguration.kt      (writeKeyProblems)
        if (properties.writeKeyVersion in cipher.loadedKeyVersions) emptyList() else listOf(
            "쓰기 세대 v… 의 키가 적재되지 않았다 … 이대로 뜨면 첫 업로드가 503 이 된다")
```

**마지막 줄이 이 자리의 표본이다.** 실패 메시지가 *"이대로 뜨면 첫 업로드가 503 이 된다"*
고 말하는데, **바로 그 검사가 앱을 띄우지 않으므로 그 503 은 영원히 오지 않는다.**
2026-08-19 리더 판정이 규약을 뒤집었고 **계약도 이 메시지도 옛 규약을 들고 있었다.**

한 겹 더: 그 방어 경로가 낼 문구조차 계약과 갈려 있었다.

```
$ grep -rn "MISSING_WRITE_KEY_MESSAGE" backend-kotlin --include="*.kt"
…/AesGcmContentCipher.kt:298:  const val MISSING_WRITE_KEY_MESSAGE = "문서 암호화 키가 설정되어 있지 않습니다"
# 계약 no_cipher 예시:                                              "문서 저장이 설정되지 않았습니다"
$ grep -rn "no_cipher\|문서 저장이 설정되지 않았습니다" backend-kotlin frontend/src tests
  (출력 없음)
```

**둘이 다른데 아무 데서도 걸리지 않았다** — 나가지 않는 문구는 대조되지 않기 때문이다.
**이것이 도달 0인 조항이 조용히 갈리는 방식**이고, 그래서 조항과 예시를 함께 내렸다.

**그래서 최종 답**: `POST /documents` 의 503 은 **인증 경로**에서 온다. 업로드 고유의 503
구성은 **없다**.

### ⑤ 재지 않아 내리지 않은 것 — 넷째 줄

「DB 세션 팩토리 미배선 → 전체」는 **같은 계열로 의심된다**(DataSource 가 없으면 컨텍스트
조립이 실패할 것이다). **그러나 기동 동작을 실제로 돌려 확인하지 않았다.**

**재지 않은 것을 지우지 않는다.** 앞 두 줄은 코드로 확인했고 이 줄은 못 했다 — 그 차이가
지운 것과 남긴 것을 가른다. 계약에 그 사실을 **문면으로 남겼다**(「내리지 않았다.
재지 않았기 때문이다」). 명세 **O-21** 이 같은 내용을 담고, **O-22**(`/health` 의 Redis 문면,
게이트 26 ⓖ)와 **O-14** 와 **한 단위로 묶기를 리더에게 요청**한다 — 셋 다 「폐기된
아키텍처를 가리키는 진단 서술」이라는 같은 종류다.

---

## 3. 판정 3 — X1 거부 문구: **전용 조항을 더한다.** 본문은 거절, 제목은 정제

### ① 고른 답

**`x-stored-text-domain` 을 신설**해 저장 본문의 **정의역**을 조항으로 세운다
(422 · `detail` **문자열** · 고정 문구 · `applies_to` 세 팔). `POST /documents` 422 예시에
`undecodable_text` 를 더했다.

**제목은 이 조항의 대상이 아니다.** `x-title-policy.rule` 에 **서로게이트 제거**를 더해
**정제**로 처분하고, 왜 두 처분이 다른지를 `x-surrogate-note` 에 적었다.

### ② 근거 — 실행한 명령과 출력

**E9. 요구는 실재하고 지목할 수 있다.** `PlainBody` 의 KDoc 이 요구와 마감 자리를 스스로 적는다.

```
$ sed -n '40,78p' core/.../crypto/StoredContent.kt
 * `String.toByteArray(UTF_8)` 는 **짝 없는 UTF-16 서로게이트를 인코딩할 수 없어 `?`로 바꿔 버린다** …
 * 태그 검증은 통과하므로 **인증에는 성공하면서 사용자 문서가 조용히 영구 손상된다.**
 * … 여기서 거부하면 `ContentCipher` 의 round-trip 불변식이 *"PlainBody 로 만들 수 있는 모든 값"* 에
 *   대해 전건으로 참이 된다.
 * … HTTP 매핑(422)과 사용자 문구를 어떻게 낼지는 **문서 업로드 경로가 생기는 단위에서 계약과 맞춘다**
 *   — 지금 고정하는 것은 예외 타입과 메시지뿐이다.
   const val UNPAIRED_SURROGATE_MESSAGE: String = "문서 본문에 텍스트로 저장할 수 없는 문자가 있습니다"
```

**「계약과 맞추는 단위」가 C3 이다.** 요구는 `migration-safety-gate` **I-7 의 round-trip**이고,
계약이 적을 것은 그 요구의 **바깥 얼굴**(상태 코드와 문구)이다.

**E10. 성질은 있고 오라클이 없었다.** 개정 전 계약에서:

- `POST /documents` 422 예시 4갈래 — 빈 본문 · 길이 초과 · 미지원 형식 · 필드 누락. **없다.**
- `x-input-limits` — 길이·바이트·형식만. **없다.**
- `x-request-field-constraints.fields[]` — `limit`·`measured_on`·`detail` 뿐. **정의역 축이 없다.**

`examples` 가 전칭이 아니므로 **계약 위반은 아니었다.** 그러나 계약에 문구가 없으면
DC-24 가 그 문구를 **코드에 복제**하고, 그러면 계약과 구현이 갈려도 자기 사본과 대조해
초록이다 — `x-filename-charset` 을 세운 것과 **같은 갈래**이고, 바로 위 §2-4 가 그 결과를
실물로 보여 준다(나가지 않는 문구가 갈린 채 아무 데서도 안 걸렸다).

**E11. 도달 경로 — 오늘 저장 경로의 `PlainBody` 생성 지점 전건.**

```
$ grep -rn "PlainBody(" application/src/main infrastructure/src/main api/src/main
application/.../document/DocumentService.kt:191   ← 업로드 본문 (사용자 입력)
infrastructure/.../crypto/AesGcmContentCipher.kt:186 ← 복호화 결과 (저장된 값)
infrastructure/.../document/MaskedItemCodec.kt:68   ← 우리가 만든 JSON
```

**사용자 입력이 들어오는 자리는 `DocumentService.kt:191` 하나**이고 그것이 C3 이 여는
경로다. `edited_text`(#8)는 아직 없다 — 그래서 `applies_to` 의 그 팔에 **측정 상태 표식**을
달아 「선언은 했으나 아직 재지 않았다」를 조항 자신이 들고 있게 했다.

**E12. 제목은 정의역 검사를 지나지 않는다 — 그리고 처분이 다르다.**

```
$ grep -rn "resolveTitle\|stripControlChars" core/src/main application/src/main
core/.../document/TitleRules.kt:67:  fun resolveTitle(given: String?): String = sanitizeName(given.orEmpty().trim()) ?: FALLBACK_TITLE
core/.../document/TitleRules.kt:76:  takeCodePoints(stripControlChars(raw), MAX_TITLE_LENGTH).trim().ifEmpty { null }
core/.../text/TextNormalization.kt:34: fun stripControlChars(text: String) = CONTROL_CHARS.replace(text, "")
```

`stripControlChars` 는 서로게이트를 지우지 않는다. **제목은 평문 열**이라 그대로 JDBC 로 가고,
드라이버가 UTF-8 로 쓰는 시점에 **치환(조용한 손상) 또는 500** 으로 갈린다. 둘 다 요구 위반이다.

계약이 이미 정한 제목의 처분은 **정제**다:

```
$ grep -n "max_title_length" contracts/easy-doc-v1.yaml   (개정 전)
341:  max_title_length: 255   # **사용자가 준 제목에만** 걸린다 — 자른다(거절하지 않는다).
# x-title-policy.rule: 제어문자를 걷어내고 … **자른다**(거절하지 않는다). … 남는 것이 없으면 고정 문구
```

### ③ 고르지 않은 것의 대가

| 고르지 않은 답 | 대가 |
|---|---|
| **기존 갈래에 흡수(빈 본문 또는 추출 실패 문구 재사용)** | 사용자가 **고칠 수 없는 안내**를 받는다. 「본문이 비어 있습니다」는 거짓이고(본문은 있다), 「추출 실패」는 붙여넣기 모드에 해당하지 않는다. 그리고 흡수하려면 **구현이 문구를 바꿔야** 하므로 「조항을 안 더한다」가 오히려 코드 변경을 부른다 |
| **조항 없이 두고 `examples` 가 전칭이 아님에 기댄다** | 계약 위반은 아니지만 **오라클이 없다.** DC-24 가 문구를 코드에 복제하고, parity 레인은 판정 근거가 없어 이 갈래를 불일치로 올릴 수도 없다. §2-4 가 그 상태의 결말을 실물로 보여 준다 |
| **`x-request-field-constraints.fields[]` 에 항목을 하나 더한다** | 그 배열의 원소는 `limit`·`measured_on`·`detail` 모양이고 **P-34·P-35 가 그 모양을 읽는다.** 상한이 없는 항목을 끼우면 기존 파서가 깨지거나 조용히 건너뛴다. 길이와 정의역은 **다른 축**이라 같은 배열에 넣는 것 자체가 틀린 모델링이다 |
| **제목도 422 로 거절 (계획 §6.4 의 잠정 처분)** | **같은 필드에서 「길이는 자르고 문자는 거절」로 갈린다.** 사용자는 **라벨 하나 때문에 문서 접수를 거절당한다** — 잃는 것이 문서인 본문과 달리, 제목에서 잃는 것은 의미 없는 코드유닛 하나다. 계획 §6.4 는 `x-title-policy` 가 서기 **전**에 쓰인 잠정 처분이고, 그 절이 제목의 처분을 **정제**로 확정했다 |
| **제목을 그대로 둔다** | 조용한 손상 또는 원인을 알 수 없는 500. 요구 위반이다 |

### ④ 이 판정이 **하지 않은** 것

- **어느 층에서 거르는지 정하지 않았다.** 계약이 정하는 것은 나간 바이트뿐이며
  `x-what-this-does-not-say` 에 그 사실을 적었다. 요청 파싱에서 막든 `PlainBody` 에서 막든
  계약은 가리지 않는다 — 다만 **저장되지 않는다**는 결과는 요구한다.
- **`edited_text`(#8) 팔을 측정으로 선언하지 않았다.** 구조상 같은 경로를 지나는 것은
  사실이므로 `applies_to` 에 적되 **표식으로 미측정임을 들고 있게** 했다. 빈 선언으로
  통과하지 않게 하는 것이 그 표식의 일이다.
- **`x-open-asymmetry`(측정 축 비대칭)와 섞지 않았다.** 그것은 **길이** 축의 미결이고
  이것은 **정의역** 축이다. 판정 대기 중인 항목에 새 사실을 얹지 않는다.

---

## 4. `kotlin-implementer` 지시 목록 — 파일·심볼 단위

**이 레인은 `backend-kotlin/**` 를 한 줄도 고치지 않았다.** 아래는 **계약과 같은 커밋(C3)**
에 들어가야 한다. 계약만 들어가면 아래 두 자리가 그 순간 **계약 밖**이다.

### 4-1. 반드시 바꾼다 (계약 개정이 직접 요구)

| # | 파일 | 심볼·자리 | 무엇을 |
|---|---|---|---|
| **K-1** | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/error/GlobalExceptionHandler.kt` | `mappingFor` 의 `is LlmProviderException, is QueueUnavailableException -> HttpStatus.BAD_GATEWAY` 갈래 (`:381-386`) | **갈래를 삭제한다.** 계약이 502 를 선언하는 오퍼레이션이 **0개**이므로 어떤 응답도 502 여서는 안 된다. 삭제하면 두 예외는 `else -> null` 로 떨어져 **500 `unmapped_domain`** 이 되고, 그 문구는 계약 `InternalError` 의 선언된 예시다. 위 `when` 의 주석(*"LLM·큐 장애 → 502"*)도 함께 지운다 |
| **K-2** | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/ErrorContractTest.kt` | `@CsvSource` 의 `"queue,               502"` (`:57`) · `"llm-truncated,       502"` (`:58`) | **두 행을 삭제하거나 500 으로 고친다.** 어느 쪽이든 **502 를 기대하는 단언이 남으면 안 된다.** 표 머리 주석 *"app/api/errors.py 의 _MAPPINGS 순서 그대로"* 도 손본다 — 그 표는 이제 **정본이 아니다**(Python 은 폐기 대상이고 기준은 계약이다) |
| **K-3** | `backend-kotlin/api/src/test/kotlin/kr/easydoc/api/support/ErrorProbeController.kt` | `DOMAIN_EXCEPTIONS` 의 `"queue" to { QueueUnavailableException(…) }` (`:187`) + `import` (`:19`) | **항목과 import 를 삭제한다.** K-4 를 하면 컴파일이 강제한다 |
| **K-4** | `backend-kotlin/core/src/main/kotlin/kr/easydoc/core/exceptions/DomainExceptions.kt` | `class QueueUnavailableException` (`:73`) 과 그 KDoc | **삭제한다.** 던지는 제품 코드가 **0곳**이고(§1 E1) 남길 근거가 없어졌다. 남기면 다음 사람이 「매핑이 없네」라며 K-1 을 되살린다. 파일 머리 KDoc 의 *"`LLMProviderError` 매핑을 타고 502가 된다"*(`:8`) 예시도 고친다 — **그 매핑이 없어진다** |
| **K-5** | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/document/DocumentPorts.kt` | `ConversionQueue` KDoc (`:212` 부근) | *"계약 조항의 처분은 계약 레인의 판정 사항이다"* → **판정 결과를 가리키게** 고친다(`x-retired-responses`). 미결 표시가 남으면 닫힌 것이 열려 보인다 |
| **K-6** | `backend-kotlin/api/src/main/kotlin/kr/easydoc/api/auth/AuthenticatedEndpoints.kt` | `PROTECTED_PATH_PATTERNS` (`:35-40`) | **문서·변환 경로를 더한다.** 오늘 목록은 `/auth/me`·`/workspaces`·`/workspaces/{workspace_id}` **셋뿐**이라 `POST /documents` 는 인증 인터셉터에 닿지도 않는다. **DC-19·DC-20·DC-21(X-A3)이 전부 이 한 줄에 달려 있다** |
| **K-7** | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/crypto/CryptoConfiguration.kt` | `writeKeyProblems` 의 실패 문구 (`:234`) | *"이대로 뜨면 첫 업로드가 503 이 된다"* → **사실과 다르다.** 그 검사가 앱을 띄우지 않으므로 그 503 은 오지 않는다. 운영자가 읽는 문구이므로 고친다 (예: *"이대로는 첫 업로드가 실패하므로 띄우지 않는다"*). **동작 변경 아님 — 문면만** |

### 4-2. 새로 세운다 (명세가 요구하는 단언)

| # | 자리 | 무엇을 |
|---|---|---|
| **K-8** | `api` 모듈 계약 테스트 (`UploadFormatContractTest`·`TitlePolicyContractTest` 와 같은 형태 — **YAML 파서 배선을 셋으로 만들지 마라**) | **P-39 전역 단언**: 계약 `x-retired-responses[].status` 의 **전건**이 `paths` 의 어느 `responses` 키에도 없다. **+ 「목록이 비어 있지 않다」를 함께 단언한다** — 없으면 빈 목록에서 공허 통과다(N-33) |
| **K-9** | 같은 자리 | **P-38 배선**: `x-stored-text-domain` 의 `detail`·`detail_shape`·`status`·`applies_to` 를 읽는다. **`detail` 이 `POST /documents` 422 예시 `undecodable_text` 와 같은지도 단언한다** — 두 자리가 갈리면 오라클이 둘이 된다(N-31) |
| **K-10** | HTTP 경계 (C-I) | **DC-24**: 짝 없는 서로게이트 본문(JSON `\u` 이스케이프 · 파일 모드) → **422 · 문자열 `detail` · 값은 계약에서 읽는다 · 후속 목록에서 그 문서 0건**. 상태 코드만 재면 「422 는 냈는데 이미 넣었다」가 지나간다 |
| **K-11** | HTTP 경계 (C-I) | **DC-25**: 서로게이트가 든 **제목** + 정상 본문 → **접수된다(422 아님)** · 조회한 `title` 에 그 문자가 없다 · 남는 것이 없으면 `x-title-policy.fallback_title`. **DC-24 와 짝이다** |
| **K-12** | HTTP 경계 (C-I, 실 DB) | **DC-18**: `ConversionQueue` 빈을 **INSERT 실패를 흉내 내는 것**으로 갈아 끼우고 업로드 → **500(502·503 아님)** · 문자열 `detail` · **후속 `GET /documents` 에서 그 문서 0건.** 던지는 예외는 실제와 같은 계열(`DataAccessException` 하위)로 둔다 — 도메인 예외를 던지면 다른 갈래를 재게 된다 |
| **K-13** | C-P (속성 주입 컨텍스트) | **DC-19**: `AuthUnavailableContractTest` 와 **같은 구성**(짧은 서명 키)에 `POST /documents` 를 한 경로 더한다. **503**(500도 401도 아님) · 문자열 `detail` · `ContractSpec.responseStatuses("/documents","post")` 가 503 을 담는지 함께 확인 |
| **K-14** | `core` (§6.4 · 계획 지시) | **제목 정제에 서로게이트 제거를 더한다.** `PlainBody` 가 쓰는 판정을 `core` 에서 공개해 `TitleRules.sanitizeName` 이 **같은 규칙**을 쓰게 한다 — 같은 사실을 두 곳에 적지 않는다. **제목에서는 던지지 않고 걷어낸다**(계약 `x-title-policy.x-surrogate-note`) |
| **K-15** | 파서 레지스트리(C2 `ParserNodeRegistryTest`) | **총수를 갱신한다**: 세 명세 정의 행 **36 → 39**(documents 15 → 18) · `ContractSpec.kt` 전용 등재 **1** · 합집합 **37 → 40**, `P-1`~`P-40` 연속 |

### 4-3. 하지 말아야 할 것

- **502 를 어떤 응답으로도 내지 마라.** 계약이 그 상태를 폐기했고 K-8 이 전역으로 잰다.
- **`x-stored-text-domain` 의 문구를 코드에 적지 마라.** 구현 상수(`UNPAIRED_SURROGATE_MESSAGE`)와
  계약이 갈리면 K-9·K-10 이 빨강이고, **그것이 그 케이스의 일이다.**
- **본문 거절과 제목 정제를 한 함수로 합치지 마라.** 합치면 DC-24·DC-25 중 하나가 빨강이다(N-34).
- **`ServiceUnavailable` 의 남은 줄을 근거로 저장 암호화 503 테스트를 만들지 마라** — 그 구성은
  앱이 뜨지 않는다(§2-4). 그 자리는 `CryptoStartupVerificationTest` 가 이미 **기동 실패**로 잰다.

---

## 5. 음성 대조 설계 — 이 개정을 되돌리면 무엇이 빨개져야 하는가

**일회용 worktree 에서만 한다. 하네스 규칙 5 — `cp` 로 복원하지 않는다**
(바이트 백업 → `Path.write_bytes` → sha256 대조, 또는 `git -C <worktree> checkout --` 후 해시 대조).
**게이트 명령은 파이프 없이** 돌리고, 필요하면 `.claude/skills/kotlin-migration/scripts/run_gate.sh` 를 경유한다.

### 5-1. 계약 값을 되돌리는 대조 (명세 §4-4 의 N-31~N-34 · 신설)

| # | worktree 에서 바꿀 것 | **빨개져야 하는 것** | **깨지지 않아야 하는 것** | 안 깨지면 무엇이 틀린 것인가 |
|---|---|---|---|---|
| **N-31** | `x-stored-text-domain.detail` 값 변경 | DC-24 + **두 자리 일치 단언**(예시 ↔ 절) | 나머지 422 케이스 전건 | P-38 이 배선되지 않았다 — 문구가 코드에서 온다 |
| **N-32** | `x-stored-text-domain.detail_shape` 를 배열 쪽으로 | **DC-24 의 타입 단언만** | **DC-24 의 상태 코드 단언** | 모양이 코드에 적혀 있다. 이 짝이 갈리지 않으면 배열 `detail` 이 지나간다 |
| **N-33** | `x-retired-responses` 에 아직 쓰는 코드(`503`)를 한 줄 더한다 / **목록을 비운다** | 앞은 K-8 의 「전건이 `paths` 에 없다」, 뒤는 K-8 의 **「목록이 비어 있지 않다」** | 서로 | 목록이 코드에 복제됐거나 **빈 선언에서 공허 통과**한다 |
| **N-34** | `x-title-policy.rule` 에서 서로게이트 제거 조항을 뺀다 | **DC-25** | **DC-24** | 본문 거절과 제목 정제가 한 값으로 묶여 있다 |

### 5-2. 개정 자체를 되돌리는 대조 — **이 판정이 옳았는지를 재는 축**

| # | 되돌리는 것 | 빨개져야 하는 것 | 이 대조가 증명하는 것 |
|---|---|---|---|
| **R-1** | 계약에 `'502': BadGateway` 와 컴포넌트를 되살린다 (구현은 그대로) | **K-8** — 폐기 목록의 502 가 `paths` 에 다시 나타난다 | 폐기가 **문장이 아니라 실행**으로 지켜진다. 이것이 초록이면 `x-retired-responses` 는 주석일 뿐이다 |
| **R-2** | K-1 을 되돌려 `QueueUnavailableException` → 502 매핑을 되살린다 (계약은 개정판) | **DC-18**(500 기대) · K-2 로 고친 `ErrorContractTest` 행 | 계약과 구현이 **같은 커밋**에 묶였다. 한쪽만 되돌려 초록이면 두 기준이 갈려 있다 |
| **R-3** | K-14 를 되돌려 제목 정제에서 서로게이트 제거를 뺀다 | **DC-25 만** | DC-24 가 함께 깨지면 두 축이 한 코드로 묶인 것 — 하드코딩과 같은 등급의 결함 |
| **R-4** | K-10 의 「저장되지 않는다」 후속 단언만 지운다 | **아무것도 안 깨져야 한다**(그것이 문제다) | **역방향 확인**: 이 단언이 없으면 「422 는 내고 이미 넣은」 구현이 통과한다. 지웠을 때 아무 데서도 안 걸린다는 사실 자체가 그 단언이 유일한 강제자임을 보인다 |
| **R-5** | K-6 을 되돌려 `PROTECTED_PATH_PATTERNS` 에서 문서 경로를 뺀다 | **DC-19 · DC-20 · DC-21** | 인증 도달이 **경로 목록 한 줄**에 달려 있다는 사실을 실행으로 고정한다. 이것이 초록이면 업로드 표면이 토큰 없이 탐색된다 |

### 5-3. 이 회차가 **실행하지 않은** 것 — 「미실행」으로 적는다

1. **Gradle 미실행.** `:api:test` 를 한 번도 돌리지 않았다. 이 레인은 YAML·마크다운만 고쳤고
   컴파일 대상이 없다. **위 음성 대조는 전부 설계이며 실측이 아니다** — 실행은 C3 구현 커밋의 몫이다.
2. **`openapi-spec-validator` 미실행.** 환경에 없다(`ModuleNotFoundError`, 1회 재시도 포함).
   대신 **직접 쓴 검증 스크립트**로 ⑴ YAML 파싱 ⑵ `$ref` 무결성(dangling 0) ⑶ **고아 컴포넌트 0**
   ⑷ 폐기 코드가 `paths` 에 없음 ⑸ 예시 ↔ 절 문구 일치를 확인했다(§1 ②). **OpenAPI 문법 전수
   검증은 받지 않았다** — 구조 변경이 「키 삭제 + `x-` 절 추가」뿐이라 위험이 낮다고 판단했으나
   **검증받지 않았다는 사실은 사실이다.**
3. **`ServiceUnavailable` 넷째 줄(DB) 미측정** — §2-5.
4. **DC-18c(큐 멱등)의 Phase 5 이월 표기 형식 미정** — 게이트 26 §5 미실행 3번 그대로다.
5. **L-2(목록 tie-break) 미착수** — 이 요청의 범위 밖이다. 편집 지점은 게이트 26 §3 에 확정돼 있다.

---

## 6. 워킹 트리 — 이 레인이 바꾼 파일

```
contracts/easy-doc-v1.yaml
docs/migration/_workspace/04_contract-keeper_documents-test-spec.md
docs/migration/_workspace/00_contract-keeper_changelog.md
docs/migration/_workspace/04_contract-keeper_l1-residual-verdict.md   (이 파일, 신규)
```

**`backend-kotlin/**` · `frontend/**` · `00_progress.md` · `reviews/**` 무접촉.**
`git status` 에 보이는 다른 항목(`backend-kotlin/.../OwnershipPredicateGuardTest.kt` ·
`04_kotlin-implementer_ownership-guard-plan.md` · `reviews/04_security-documents_privacy-gate.md` ·
`.playwright-mcp/` · 한글 `.doc` 2건)은 **전부 다른 레인의 것**이다 — 이 레인은 그중 어느 것도
만들지도 고치지도 않았다. 세션 도중에도 바뀌므로 **스냅샷**으로 읽는다.
제어문자 전수 검사 **0건**(변경 3파일 직접 스캔 — C0 비개행 · DEL · C1 전 구간).
명세 §0 값 유출 grep **1건**(그 코드 블록 자신 — 규약이 기대하는 값).

---

## 7. 통보

| 대상 | 내용 |
|---|---|
| **`kotlin-implementer`** | **§4 전체.** K-1~K-7 은 계약 개정이 직접 요구하는 변경이고 **계약과 같은 커밋(C3)** 이어야 한다. K-8~K-15 는 명세가 요구하는 새 단언이다. **게이트 26 §8 ⑶ 의 「지금 고치지 마라」는 해제됐다** — 그 커밋이 지금이다. **K-6 을 빠뜨리면 DC-19·DC-20·DC-21 이 전부 무대 없이 남는다** |
| **`parity-verifier`** | **의도된 차이 3건이 늘었다**: ⑴ 큐 등록 실패의 상태 코드(Python 502 ↔ 계약 500) ⑵ 큐 미배선 503(Python 있음 ↔ 계약 없음) ⑶ `failure_code` 의 큐 등록 실패 값(Python 있음 ↔ 계약 없음). **셋 다 아키텍처가 달라 무대가 사라진 자리**이므로 불일치가 아니라 갈림 원장의 「의도된 차이」다. **새로 범위에 드는 것**: 저장 정의역 거절의 `detail` 문구와 그 **타입**(문자열) |
| **`privacy-gate`** | ⑴ **`x-stored-text-domain` 신설** — I-7 round-trip 요구의 **바깥 얼굴**을 계약이 갖게 됐다. 문구는 입력값을 담지 않는다(예외 메시지 규약과 같은 근거) ⑵ **제목의 서로게이트 정제**(K-14) — 평문 열에 남는 값의 정의역이 좁아진다. **범주 축소가 아니라 정제 범위 확대**다 ⑶ `ServiceUnavailable` 개정은 **보안 불변식을 건드리지 않는다** — 소유권 은닉·`no-store`·오류 본문 어느 것도 무변경 |
| **리더** | ⑴ **판정 3건 집행 완료.** `info.version` 1.2.0 → 1.3.0. **소유자 단독이 아니라 리더 지시로** 집행했다(`escalate_to_leader` ④) ⑵ **범위를 하나 넘었다 — 보고한다.** 「저장 암호화 키 미설정 → 503」 줄을 함께 내렸다. 지목받지 않았으나 **DC-19 의 새 무대로 쓰려던 바로 그 줄이었고 실측하니 죽어 있었다**(§2-4). 되돌리려면 그 줄과 `no_cipher` 예시를 복원하고 DC-19 를 다시 여는 편집이다 — **다른 개정과 독립적으로 되돌릴 수 있다** ⑶ **O-21·O-22 처리 단위 지정 요청** — `ServiceUnavailable` 넷째 줄(DB)과 `/health` 의 Redis 문면. **O-14 와 묶기를 제안한다** ⑷ **미실행 5건** — §5-3. 특히 **음성 대조는 전부 설계이고 실측이 아니다** |
