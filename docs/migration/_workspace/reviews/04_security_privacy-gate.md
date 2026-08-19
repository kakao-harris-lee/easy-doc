# Phase 4 / 작업 단위 `crypto` — 보안 축 리뷰 (I-7 저장 암호화 AEAD)

**작성 주체**: `privacy-gate` (암호·인증 검증 정본, 2026-08-12 이후)
**기준**: `.claude/skills/migration-safety-gate/SKILL.md` **I-7**(6항) · **I-8 검증 5 파생**(스키마) /
계획 §4.3(2차 개정) · §5 Phase 4 / `CLAUDE.md` 보안·데이터 규칙 / 원장 `00_progress.md:1368`
(리더 판정 ⑥ — I-8 해제 조건 3항)
**대상 커밋**: `74ec2b0` · `fcf584b` · `e891a08` · `858347d` · `9c7aa03` (HEAD `9c7aa03`)
**감사 대상 산출물**: `docs/migration/_workspace/04_kotlin-implementer_crypto.md`

> **판정 요약**: **I-7 전 6항 통과.** §5 Phase 7 즉시 중단 기준(「AEAD round-trip 실패 또는 변조가
> 예외 없이 통과」) **해당 없음.** 차단 0건. **수정 필요 7건 · 기록 2건**은 전부 지금 데이터에
> 닿지 않는 자리이고, 마감은 **다음 작업 단위(문서 저장 경로)** 다. 차단 사유서
> (`04_privacy-gate_blocking.md`)는 **만들지 않았다.**

---

## 0. 이 감사가 무엇을 어디서 쟀는가

I-7 검증 1이 *"Kotlin 자체 테스트로 확인한다 — parity 하네스로는 못 본다"* 고 못박았고,
같은 문단이 *"산출물의 `tamper_rejected: true` 같은 필드는 하네스의 자기 신고라 증거가 아니다"*
라고 덧붙인다. **그 규율은 저장소 자신의 테스트에도 똑같이 적용된다** — `AesGcmContentCipherTest`
가 초록이라는 사실은 그 테스트가 무엇을 재는지에 대한 자기 신고다.

그래서 이 감사는 두 층으로 쟀다.

| 층 | 무엇을 | 어디서 | 도구 |
|---|---|---|---|
| ⑴ 저장소 회귀 재실행 | 구현자가 선언한 20건이 실제로 초록인가 | `backend-kotlin` Gradle | `./gradlew :api:test :worker:test :infrastructure:test --rerun-tasks` |
| ⑵ **독립 탐침** | 제품 클래스를 **테스트 하네스를 거치지 않고** 직접 호출 | 저장소 **밖** (scratchpad, `javac` + 리플렉션, 컴파일된 `*/build/classes/kotlin/main` 을 클래스패스로) | `Probe.java`(AEAD 7축) · `Probe2.java`(설정 조립 4축) · `SchemaProbe.java`(Flyway/PostgreSQL 2축) |

⑵ 를 따로 만든 이유는 **범위**다. 저장소 테스트는 변조를 3자리(nonce 첫·암호문 첫·태그 마지막)
표본으로 보는데, 감사는 **전수**를 봐야 판정할 수 있다. 아래 실측치는 명시가 없으면 전부 ⑵ 다.

**Kotlin 소스 무수정 · 커밋 0 · `00_progress.md` 무접촉.** 탐침은 저장소 밖에서 컴파일했고
저장소 안에 파일을 만들지 않았다(구현자가 쓴 일회용 worktree 방식조차 쓰지 않았다 — 읽기만 했다).

**저장소 회귀 재실행 결과**: `BUILD SUCCESSFUL`, 23 tasks executed.

| 모듈 | tests | failures | errors | skipped |
|---|---|---|---|---|
| core | 359 | 0 | 0 | 0 |
| application | 44 | 0 | 0 | 0 |
| infrastructure | 135 | 0 | 0 | 0 |
| api | 188 | 0 | 0 | 0 |
| worker | 3 | 0 | 0 | 0 |
| **계** | **729** | **0** | **0** | **0** |

구현자 산출물 §6 의 집계(729 = 359/44/135/188/3)와 **바이트 단위로 일치**한다. `crypto` 두 클래스는
`AesGcmContentCipherTest` 17건 · `EncryptionSchemeSchemaTest` 3건(전건 통과) 으로 재확인했다.

---

## 1. 탐침 1 — round-trip (I-7 검증 1) → **통과**

임의 길이 · 인코딩 16종을 왕복시켰다. 저장소 테스트가 보는 6종보다 넓다.

| 표본 | 입력 | 출력 | 오버헤드 | 결과 |
|---|---|---|---|---|
| 0B (빈 문자열) | 0B | 28B | 28 | OK |
| 1B | 1B | 29B | 28 | OK |
| 11B (nonce−1) | 11B | 39B | 28 | OK |
| 12B (=nonce) | 12B | 40B | 28 | OK |
| 15B (tag−1) | 15B | 43B | 28 | OK |
| 16B (=tag) | 16B | 44B | 28 | OK |
| 28B (=nonce+tag) | 28B | 56B | 28 | OK |
| 4KiB | 4096B | 4124B | 28 | OK |
| **1MiB ASCII** | 1048576B | 1048604B | 28 | OK |
| **3MiB 한글** | 3145728B | 3145756B | 28 | OK |
| UTF-8 3바이트(한글) | 41B | 69B | 28 | OK |
| UTF-8 4바이트(이모지·ZWJ·국기) | 37B | 65B | 28 | OK |
| 결합 문자(NFD) | 15B | 43B | 28 | OK |
| 제어문자 NUL/개행/탭/CRLF | 8B | 36B | 28 | OK |
| BOM · 방향 제어(U+202E) | 12B | 40B | 28 | OK |
| 고아 서로게이트(U+D800 단독) | 3B | 31B | 28 | **U+FFFD 대체** |

**오버헤드가 전 표본에서 정확히 28B**(nonce 12 + 태그 16)다. 길이·인코딩 어느 축에도 분기가 없다.
암호문에 평문 조각이 보이지 않는 것도 함께 확인했다.

**고아 서로게이트만 원문과 다르다.** 이것은 AEAD 결함이 **아니라** `String.toByteArray(UTF_8)` 의
JVM 표준 동작이다 — 짝 없는 UTF-16 서로게이트는 UTF-8 로 인코딩될 수 없어 U+FFFD 로 대체된다.
Kotlin `String` 을 UTF-8 로 저장하는 어떤 구현도 같다. **판정: 결함 아님.** 다만 문서 추출기가
손상된 파일에서 고아 서로게이트를 만들어 낼 수 있으므로, "저장하면 문자가 바뀔 수 있다"는 사실은
`I-13`(초안 원본 보존) 축에서 다음 단위가 알고 있어야 한다 → **기록 R-2**.

---

## 2. 탐침 2 — nonce 재사용 금지 (I-7 검증 4) → **통과**

I-7 이 *"AEAD 를 새로 쓰기 때문에 생긴 신규 검사 항목"* 으로 지정한 자리라 표본을 크게 잡았다.

| 측정 | 표본 | 결과 |
|---|---|---|
| 같은 키 · 같은 평문 · 같은 결속 반복 | **200,000회** | 서로 다른 nonce **200,000개**, **충돌 0건**. 암호문도 전건 상이 |
| 한 행 4컬럼 교차(같은 트랜잭션 모사) | 4 × 20,000 = **80,000회** | 서로 다른 nonce **80,000개** — **컬럼 간 nonce 공유 없음** |
| 난수원 종류 | 리플렉션으로 필드 직접 확인 | `java.security.SecureRandom` / algorithm=`NativePRNG` / provider=`SUN` |

**코드 추적으로 확인한 것** (탐침이 못 보는 축):

- `AesGcmContentCipher.encrypt:112-113` 이 **호출마다** `ByteArray(12)` 를 새로 만들고
  `random.nextBytes(nonce)` 를 부른다. nonce 를 필드·캐시·카운터에 두는 경로가 없다.
- 재시도 경로 없음 — `encrypt` 는 실패해도 되잡지 않는다. 같은 nonce 로 두 번 봉하는 자리가
  구조적으로 존재하지 않는다.
- **시드 고정 경로가 제품 코드에 없다.** `CryptoConfiguration:97` 이 `SecureRandom()`(인자 없는
  생성자)를 넘기는 유일한 조립 지점이고, `setSeed` 호출은 전 저장소에 0건이다. 생성자가
  난수원을 받는 이유는 KDoc 이 밝힌 대로 **회귀의 음성 대조 전용**이며, 그 대체 구현
  (`FixedNonceRandom`)은 테스트 소스에만 있다.
- 난수원 인스턴스를 **하나** 만들어 공유하는 선택은 옳다. `SecureRandom.nextBytes` 는 스레드
  안전이고, 매 호출 새 인스턴스는 시딩 비용과 엔트로피 고갈 블로킹을 요청 경로에 붙인다.

**음성 대조 재확인**: 저장소 회귀 `난수원을 고정하면 nonce 가 반복된다` 가 초록이므로,
위 200,000건이 초록인 이유가 **난수원 때문**임이 고정돼 있다. 이 케이스가 없으면 위 측정은
"무엇 때문에 통과했는지 모르는 통과"였다.

---

## 3. 탐침 3 — 변조 거부 · AAD 바꿔치기 (I-7 검증 2) → **통과**

### 3.1 비트 변조 — **전수**

| 측정 | 건수 | 거부 | 통과·타 예외 |
|---|---|---|---|
| 31B 암호문(nonce12+ct3+tag16) **전 바이트 × 전 비트** | **248건** | **248건** | **0건** |
| 6,028B 암호문 무작위 1비트 표본 | 3,000건 | 3,000건 | 0건 |

**전 3,248건이 `DecryptionFailedException` 하나로 거부**됐다. nonce 구간·암호문 구간·태그 구간
어디를 뒤집어도 같다. **"인증 없는 암호화"가 아님이 전수로 확정**됐다.

### 3.2 AAD 바꿔치기 — **전수**

I-7 이 지목한 두 공격 갈래(같은 행의 다른 컬럼 / 다른 행의 같은 컬럼)를 포함해 4축을 전수로 쳤다.

| 축 | 시도 | 거부 |
|---|---|---|
| 다른 행 UUID (무작위 100개) | 100 | 100 |
| **다른 컬럼** (`EncryptedField` 나머지 3종 전수) | 3 | 3 |
| 다른 `key_version` (설정에 실재하는 v2 로 라벨 조작) | 1 | 1 |
| 다른 `scheme` (`fernet-v1` · `aes-gcm-v1` · 대문자 · `aes256gcm-v2` · 빈 문자열) | 5 | 5 |
| **계** | **109** | **109 (통과 0)** |

`key_version` 축이 특히 중요하다 — **설정에 실재하는 다른 세대 번호**로 라벨만 바꿔도 거부된다.
즉 두 컬럼은 "적혀만 있는 값"이 아니라 **암호문의 일부**다. 이것이 I-7 검증 5 가 요구한
*"두 컬럼이 읽기/쓰기 경로에서 실제로 쓰이는지"* 의 실측 근거다.

**AAD 구분자 모호성**: 4조각 중 `|` 를 포함할 수 있는 것이 있으면 서로 다른 결속이 같은 문자열로
접힌다. `EncryptedField.wireName` 4종을 전수로 검사해 `|` **0건**을 확인했다. scheme 은 CHECK 가
`aes256gcm-v1` 하나로 좁혔고(§6 실측), keyVersion 은 숫자, UUID 는 정규 표기다. **모호성 없음.**

### 3.3 절단

길이 0..31 을 전수(32건)로 넣어 **31건 거부**. 통과한 1건은 `len = 31`, 곧 **자르지 않은 원본**이라
통과가 정상이다(탐침의 루프가 경계를 포함한 것이며 구현 결함이 아니다). `nonce+tag` 미만은
AES 를 시도하기도 전에 끊기고, 그 이상은 태그 검증에서 끊긴다.

---

## 4. 탐침 4 — 복호화 oracle 금지 (I-7 검증 3) → **통과** (타이밍 1건 수정 필요)

### 4.1 예외 신호 — **완전 동일**

8갈래를 실행해 (타입, 메시지, cause, suppressed, throw 지점)을 전부 수집했다.

```
모르는 방식 / 모르는 키 세대 / 길이 미달(0B) / 길이 미달(27B)
태그 불일치 / 다른 키 / 다른 행 / 다른 컬럼
  →  전건: kr.easydoc.core.exceptions.DecryptionFailedException
           msg="저장된 문서를 읽을 수 없습니다"
           cause=null · suppressed=0 · throw 지점=decrypt-bKW2k-Q
(타입, 메시지, cause) 고유 조합 = 1
```

**스택트레이스 전문**도 확인했다 — `Caused by:` 절이 없고 `AEADBadTagException` 이 어디에도
나타나지 않는다. `catch (ignored: GeneralSecurityException) { throw DecryptionFailedException() }`
(`AesGcmContentCipher.kt:141-144`)이 체인을 끊은 결과이며, Python `raise ... from None` 의
Kotlin 대응으로 정확하다.

`DecryptionFailedException` 은 **인자를 받지 않는 생성자**뿐이라 호출자가 상황별 문구를 넣을 수
없다(`DomainExceptions.kt:116`). 메시지를 고정 상수로 두는 것보다 한 겹 강한 형태다.

### 4.2 타이밍 — **수정 필요 F-1 (낮음)**

p50, 각 갈래 워밍업 20,000회 폐기 후 표본 20,001(홀수).

| 갈래 | p50 | 최소 대비 | 어디서 끊기나 |
|---|---:|---:|---|
| 길이 미달(27B) | 792ns | 1.00 | `if (bytes.size < 28)` — AES 미시도 |
| 길이 미달(0B) | 875ns | 1.10 | 〃 |
| 모르는 방식 | 917ns | 1.16 | `if (scheme != …)` — AES 미시도 |
| 모르는 키 세대 | 917ns | 1.16 | `keys[ver] ?: throw` — AES 미시도 |
| **다른 키** | 2,209ns | 2.79 | 태그 검증 |
| **다른 컬럼** | 2,209ns | 2.79 | 태그 검증(AAD) |
| **태그 불일치** | 2,250ns | 2.84 | 태그 검증 |
| **다른 행** | 2,250ns | 2.84 | 태그 검증(AAD) |
| **전체 최대/최소 비** | | **2.84** | |
| **AEAD 시도 4갈래끼리의 비** | | **1.019** | |

**두 해석을 병기한다** (감사 규약: 회색지대를 임의로 무해 판정하지 않는다).

- **위반 아님 쪽** — 고전적 복호화 oracle(패딩 oracle 류)이 성립하려면 **같은 종류의 실패들
  사이에서** 어느 추측이 더 가까웠는지가 구분돼야 한다. 그 집합(태그·다른 키·다른 행·다른 컬럼)의
  비는 **1.019** 로 완전 균일하다. 갈리는 것은 "AES 를 시도했는가"뿐이고, 그 분기를 결정하는 세 값
  (`scheme` 문자열 · `key_version` 정수 · 바이트 길이)은 **공격자가 넣은 값 자체**다. 비밀에 대한
  정보가 아니다. 게다가 **오늘 도달이 0** 이다 — `EncryptedContent` 를 외부에서 주입할 수 있는
  엔드포인트가 존재하지 않는다(`ContentCipher` 참조가 제품 코드에 아직 없음, §8 참조).
- **위반 쪽** — I-7 검증 3 의 문장은 *"원인이 응답·로그·**지연** 으로 구분돼 나가면 그것이
  oracle 이다"* 이고, **지연을 명시적으로 축에 넣었다.** 2.84배는 이 저장소가 같은 성질의 문제
  (401 세 갈래)에 스스로 세운 문턱 **1.5**(M-3b, `AuthEndpointReachTest`)의 거의 두 배다. 그리고
  "모르는 키 세대"가 빠른 갈래에 있다는 것은 **서버에 어떤 키 세대가 설정돼 있는지**를 타이밍으로
  셀 수 있다는 뜻이다 — 이것은 공격자가 넣은 값이 아니라 **서버 설정에 대한 정보**다.

**보수적 판정: 잠정 「수정 필요(낮음)」.** 차단하지 않는 근거는 도달 0 하나뿐이며, 그 전제는
**문서 조회 경로가 생기는 다음 단위에서 무너진다.** 마감을 그 단위로 건다.

> **해제 조건 F-1**: 다음 중 하나. ⓐ 세 사전 검사(`scheme`·`keyVersion`·길이)를 통과하지 못한
> 경우에도 **더미 키로 AES-GCM 을 한 번 수행**해 지연을 맞춘다. ⓑ 또는 "이 축은 공격자 입력이
> 결정하므로 균일화하지 않는다"를 **근거와 함께 코드 주석·산출물에 명시**하고, `key_version`
> 존재 여부만은 새지 않도록 사전 검사 순서를 조정한다. 어느 쪽이든 **M-3b 와 같은 방식**
> (홀수 표본 · 워밍업 폐기 · 고정 시드 교차 · 문턱 명시)의 상시 회귀로 고정하고, 음성 대조
> (분기를 되돌리면 빨개지는지)를 함께 남긴다. 검증은 privacy-gate 가 같은 탐침으로 재측정한다.

---

## 5. 탐침 5 — 키 회전 · 키 취급 (I-7 검증 5) → **통과** (수정 필요 4건)

### 5.1 회전 자체 → **통과**

| 측정 | 결과 |
|---|---|
| v1 로 봉한 행을 **v2 활성 상태**에서 읽기 | 성공 — 원문 일치 |
| 회전 후 새 쓰기의 `key_version` | **2** (봉투가 실어 나온다) |
| `writeScheme` | `aes256gcm-v1` — **`fernet` 문자열 미포함** |
| 미지 세대 v3 을 가리키는 행 | 거부 (`DecryptionFailedException`, §4.1 과 **같은 신호**) |
| 옛 세대를 설정에서 **뺀** 뒤 옛 행 | 거부 — *회전은 재암호화가 아니다* 가 실측으로 확인됨 |

읽기는 `content.keyVersion`, 쓰기는 `writeKeyVersion` 으로 완전히 갈라져 있다. 포트가
쓰기 세대를 인자로 받지 않으므로(`ContentCipher.kt:47`) **"옛 키로 새로 쓰는" 경로가 타입 수준에서
존재하지 않는다.** 이것이 §8 의 판정 근거가 된다.

### 5.2 잘못된 키 재료 → **통과** (키 바이트 반향 0)

과제가 지정한 "기동 실패 메시지"와 **설계가 다르다** — 이 구현은 기동을 막지 않는다
(`AuthProperties` 와 같은 규약). 그래서 재는 대상을 **기동 로그 전문**으로 바꿔 측정했다.

5종(base64 아님 · 16B · 64B · 빈 값 · 정상 32B)을 한 번에 넣고 로그를 전량 캡처했다.

```
WARN  저장 암호화 키 v1 가 base64 가 아니다. 이 세대를 쓰지 않는다.
WARN  저장 암호화 키 v2 의 길이가 32바이트가 아니다(실제 16). 이 세대를 쓰지 않는다.
WARN  저장 암호화 키 v3 의 길이가 32바이트가 아니다(실제 64). 이 세대를 쓰지 않는다.
INFO  저장 암호화 키 1세대를 적재했다. 쓰기 세대=v5
```

- **키 바이트 반향 0** — 4종(잘못된 base64 리터럴 · 16B base64 · 64B base64 · 정상 키 base64)
  전건이 로그 전문에 없다. 남은 것은 **세대 번호와 기대 길이**뿐이다.
- 적재된 세대를 리플렉션으로 직접 확인: `[5]` — **16B·64B·base64 오류·빈 값이 전부 배제**됐다.
  64B 를 배제하는 것이 중요하다. 조용히 앞 32B 를 잘라 쓰면 설정과 실제가 갈린다.
- **빈 값만 경고가 없다.** `application.yml` 이 `${EASYDOC_ENCRYPTION_KEY_V1:}` 자리표시자를
  미리 적어 두어 개발 기동마다 빈 값이 들어오기 때문이며, 소음이 진짜 오설정을 묻지 않게 하는
  선택이다. 근거가 코드 주석에 적혀 있다(`AesGcmContentCipher.kt:155-158`). **타당.**
- `Secret.toString()` = `**********`. 설정 오류 메시지에도 키 모양 문자열이 없다
  (`문서 암호화 키가 설정되어 있지 않습니다`).
- **`/actuator` 류는 도달 0** — `spring-boot-starter-actuator` 의존이 저장소 전체에 **없다**
  (`build.gradle.kts` · `libs.versions.toml` · `application.yml` 전수 검색 0건). 설정 덤프
  노출 경로가 애초에 존재하지 않는다.

### 5.3 설정 조립 축 — **수정 필요 4건**

`CryptoConfiguration.contentCipher` 를 직접 불러 측정했다.

| # | 측정 | 실측 결과 | 판정 |
|---|---|---|---|
| C1 | 같은 세대 번호를 두 번 적음 | `WARN … 먼저 적힌 것을 쓴다` + **첫 번째 키로만 열림**(두 번째 키로는 거부). 선언과 동작이 일치 | **통과** |
| C2 | `write-key-version: 9` 인데 `keys` 에 v9 없음 | **기동 성공**(WARN 없음) → 첫 `encrypt` 에서 `ConfigurationException`(→503). 그 상태에서도 **v1 행 읽기는 성공** | **수정 필요 F-2** |
| C3 | 형식은 맞고 **값이 틀린** 32B 키로 v1 을 덮음 | **경고 0 · 실패 0 으로 조립**, 새 쓰기도 성공. 기존 행만 조용히 안 열림 | **수정 필요 F-3** |
| C4 | `write-key-version: 70000` / `-1` | 둘 다 암호화 **성공**, 봉투에 그대로 실림 | **수정 필요 F-4** |

**F-2 — 쓰기 키 부재를 기동이 알려 주지 않는다.** 실패가 fail-closed(503)라 **데이터는 안전**하다.
문제는 발견 시점이다: 오설정이 **첫 업로드**까지 조용하고, 그때 나오는 것은 사용자 화면의 503 이다.
`keys` 에 `writeKeyVersion` 이 없다는 것은 **기동 시점에 이미 알 수 있는 사실**이고, WARN 한 줄이면
배포 파이프라인에서 잡힌다. `AuthProperties` 의 "기동은 막지 않는다" 규약을 깨지 않고도 가능하다.

**F-3 — 오타 키가 조용하다(가장 위험).** 회전 절차는 "새 세대를 `keys` 에 더하고
`write-key-version` 을 올린다"인데, 그때 붙여넣기를 한 글자 틀려도 **형식이 맞으면 그대로 적재**된다.
그 뒤로 쓰는 행은 전부 "운영자가 보관 중인 진짜 키로는 열 수 없는" 암호문이 되고, 증상은
**첫 조회 때** `DecryptionFailedException` 으로 나타난다. 그 사이에 쓴 문서는 **되돌릴 수 없다**
— 롤백 런타임이 없고(2026-08-12) 평문 사본도 없다. 이것은 I-7 이 지키려는 것(*"사용자가 올린
문서를 영구히 읽지 못하게 되는"*)과 **같은 종류의 사고**이며, 다만 오늘은 쓰는 경로가 없어 도달 0 이다.

**F-4 — `writeKeyVersion` 범위가 컬럼보다 넓다.** 구현자 산출물 ⓑ 가 적은 것을 실측으로 확정한다.
`70000` 은 암호화까지 통과하고 **저장에서** `smallint out of range` 로 깨진다(§6 실측). `-1` 은
**저장까지 성공**한다 — `key_version` 에는 CHECK 가 없기 때문이다(§6). 컬럼 이름은 "세대"인데
음수 세대가 들어간다.

### 5.4 조립된 빈의 실제 도달 — **기록 R-1**

구현자 산출물 ⓔ 가 *"`worker` 프로필의 암호화 빈이 실제로 조립되는지 확인하는 테스트가 없다"* 로
남긴 자리를 **관측으로 닫았다.** 기동 로그(`저장 암호화 키 N세대를 적재했다`)를 전 모듈 테스트
결과 XML 에서 검색했다.

| 모듈 | 로그가 나온 테스트 클래스 |
|---|---|
| `api` | `ApiStartupOnEmptyDatabaseTest` · `ApiStartupOnPythonSnapshotTest` · `AuthEndpointReachTest` · `AuthUnavailableContractTest` · `ContractErrorBodyReachTest` · `DeletedAccountTokenReachTest` · `PasswordHashLogLeakReachTest` · `PasswordHashingBackpressureReachTest` · `PrivateResponseHeadersReachTest` · `WorkspaceEndpointReachTest` (10) |
| **`worker`** | **`WorkerStartupTest`** ✅ |

`@Bean` 은 기본이 eager singleton 이므로, 이 로그가 나왔다는 것은 `EncryptionProperties` 바인딩과
`AesGcmContentCipher` 생성이 **실제 Spring 컨텍스트에서 성공했다**는 뜻이다. `api`·`worker` 둘 다
`@SpringBootApplication(scanBasePackages=["kr.easydoc"])` + `@ConfigurationPropertiesScan("kr.easydoc")`
이고 `SecretConverter` 가 `@ConfigurationPropertiesBinding` 으로 등록돼 있다. **ⓔ 의 그 항목은 닫힌다.**

**다만 R-1 로 남긴다**: 모든 Spring 컨텍스트에서 로그가 `저장 암호화 키 **0세대**를 적재했다` 다.
테스트 환경에 `EASYDOC_ENCRYPTION_KEY_V1` 이 없어서다. 즉 **조립된 빈을 실제 키로 쓰는 통합
테스트가 0건**이고, 키를 든 경로는 단위 테스트가 직접 생성자를 부르는 자리뿐이다. 문서 저장
경로가 붙는 순간 통합 테스트가 조용히 **503 갈래만** 밟게 될 수 있다. 다음 단위에서 테스트
프로파일에 합성 키를 넣고, 「0세대로 조립된 컨텍스트에서 문서 API 를 때리면 503」 을 **의도한
단언으로** 고정하는 편이 낫다.

---

## 6. 탐침 6 — 스키마 (I-8 파생 / I-7 검증 5) → **통과 · 리더 해제 조건 3항 충족**

**실측 환경**: `pgvector/pgvector:pg16`(저장소 `PostgresTestSupport` 와 같은 이미지) 컨테이너에
Flyway 12.4.0 으로 `V1,V2,V3` 를 실제 적용. 저장소 테스트를 거치지 않은 독립 측정.

### 6.1 적용 결과

```
installed_rank=1  version=1  python schema baseline    checksum=-1070115347  success=t
installed_rank=2  version=2  encryption scheme         checksum= 307400641   success=t
installed_rank=3  version=3  encryption scheme aead    checksum=1086646410   success=t
```

### 6.2 DEFAULT · NOT NULL 실측

| 테이블 | 컬럼 | 타입 | NULL 허용 | **DEFAULT** |
|---|---|---|---|---|
| documents | encryption_scheme | varchar(16) | NO | **(none)** |
| documents | key_version | **smallint** | NO | **(none)** |
| documents | source_text_encrypted | bytea | NO | (none) |
| conversions | encryption_scheme | varchar(16) | NO | **(none)** |
| conversions | key_version | **smallint** | NO | **(none)** |
| conversions | easy_text_encrypted | bytea | **YES** | (none) |
| conversions | masked_items_encrypted | bytea | **YES** | (none) |
| conversions | edited_text_encrypted | bytea | **YES** | (none) |

**DEFAULT 가 네 자리 전부에서 사라졌다.** privacy-gate 03 §5-4 가 지목한 위험
(*"컬럼을 명시하지 않은 INSERT 에 DEFAULT 가 조용히 거짓 값을 채운다"*)의 기제가 제거됐다.

### 6.3 CHECK 실측

```
ck_documents_encryption_scheme_valid    CHECK (((encryption_scheme)::text = 'aes256gcm-v1'::text))
ck_conversions_encryption_scheme_valid  CHECK (((encryption_scheme)::text = 'aes256gcm-v1'::text))
```

`fernet` 문자열이 **양쪽 제약 정의 어디에도 없다.**

### 6.4 INSERT 실측 — 「DEFAULT 부재」를 선언이 아니라 동작으로

| INSERT | 결과 |
|---|---|
| `encryption_scheme`·`key_version` **둘 다 생략** | **실패** — `null value in column "key_version" … violates not-null constraint` |
| `encryption_scheme` **만** 생략 | **실패** — `null value in column "encryption_scheme" …` |
| `key_version` **만** 생략 | **실패** — `null value in column "key_version" …` |
| `encryption_scheme='fernet-v1'` | **실패** — `violates check constraint "ck_documents_encryption_scheme_valid"` |
| `encryption_scheme='aes-gcm-v1'` (I-7 예시명) | **실패** — 같은 CHECK |
| `encryption_scheme='aes256gcm-v1'` | 성공 |
| `conversions` 두 컬럼 생략 | **실패** — `null value in column "key_version" …` |
| **`key_version = -1`** | **성공** ⚠️ |
| `key_version = 32768` | 실패 — `smallint out of range` |

**세 갈래 전부 즉시 · 시끄럽게 실패**한다. "빠뜨린 INSERT 가 NOT NULL 위반으로 즉시 실패한다"는
V3 주석의 주장이 실측으로 확인됐다.

⚠️ **`key_version = -1` 이 저장된다** → **수정 필요 F-4**(§5.3 과 같은 항목의 저장 측 절반).
`encryption_scheme` 에는 CHECK 가 있는데 `key_version` 에는 도메인 제약이 없다 — **같은 목적의 두
컬럼인데 방어가 비대칭**이다. `CHECK (key_version > 0)` 한 줄이면 F-4 의 저장 측이 닫힌다.

### 6.5 V2 체크섬 변경의 한계 — **실측** (기록 R-3)

구현자가 *"배포 이후에는 이 방식을 쓸 수 없다"* 로 적은 한계를, **선언으로 두지 않고 실제로 재현**했다.

1. 빈 DB 에 **`e891a08` 이전의** V1+V2 를 적용 → V2 checksum **1359337517** 기록.
2. 같은 DB 에 **현재** V1+V2′+V3 로 `migrate()`:

```
FlywayValidateException:
  Validate failed: Migrations have failed validation
  Migration checksum mismatch for migration version 2
  -> Applied to database : 1359337517
  -> Resolved locally    : 307400641
```

3. 그 DB 의 스키마 상태를 확인:

```
documents.encryption_scheme  default='fernet-v1'::character varying
documents.key_version        default=1
ck_documents_encryption_scheme_valid  CHECK (… = 'fernet-v1'::text)
```

**결론: 실패 방식이 fail-closed 다.** V3 가 **부분 적용되지 않고**, 스키마는 V2 상태 그대로 남으며,
애플리케이션은 기동하지 못한다. 「반쯤 적용돼 어떤 행은 AEAD 이고 어떤 행은 Fernet 이름」 같은
최악의 상태가 만들어지지 않는다. **보안 판정: 문제 없음.** 운영 부담(로컬 개발 DB 재생성)만
남으며 그 근거(§9 결정 2 — 보존할 운영·파일럿 DB 없음)는 유효하다. **R-3 은 기록이지 결함이 아니다.**

### 6.6 `FlywayBaselineGuard` 지문

**손대지 않은 것이 옳다.** 지문(`python-schema-fingerprint.txt`)은 **V1 만 적용한 상태**를 재는
것이라 V3 추가와 축이 다르고, `EXPECTED_ALEMBIC_HEAD = "0006"` 도 Alembic 축이다. 회귀 재실행에서
`FlywayBaselineGuardTest` **10건** · `PythonSchemaBaselineTest` **4건** 전건 통과를 확인했다.
`PythonSchemaBaselineTest` 가 additive 규칙 대조 축을 「줄 전체」에서 「컬럼 이름·서수·타입」으로
좁힌 것도 타당하다 — 계획 §4.2 가 금지한 것은 컬럼 삭제·개명·타입 축소이고 DEFAULT 는 그 목록에
없다. **범위를 근거에 맞춘 변경이지 회피가 아니다.**

### 6.7 산출물 정정 1건 — **기록 R-4**

`04_kotlin-implementer_crypto.md` §3 은 I-7 대응 테스트가 *"전부 `AesGcmContentCipherTest.kt`(17건)
와 `EncryptionSchemeSchemaTest.kt`(3건)"* 이라고 적었는데, §6 음성 대조 E 는 세 번째 케이스
`방식과 키 세대를 적지 않은 INSERT 는 실패한다` 를 든다. 그 케이스의 실제 위치는
**`infrastructure/src/test/kotlin/kr/easydoc/infrastructure/db/PythonSchemaBaselineTest.kt:120`** 이다.
케이스는 실재하고 내용도 정확하지만(§6.4 가 같은 성질을 독립 측정으로 확인), **§3 대응표를 따라간
감사자는 그 파일을 못 찾는다.** 대응표에 파일 경로를 한 줄 더하면 닫힌다.

---

## 7. 탐침 7 — 평문 경로 (`CLAUDE.md` 보안 규칙 / I-3·I-4 선행) → **통과**

| 측정 | 결과 |
|---|---|
| `PlainBody(박싱).toString()` | `PlainBody(25자)` — 본문 미포함 |
| `PlainBody.toString-impl(String)` (인라인 경로) | `PlainBody(25자)` — 본문 미포함 |
| `EncryptedContent.toString()` | `EncryptedContent(69바이트, aes256gcm-v1, v1)` — 암호문 미포함 |
| `Secret.toString()` | `**********` |
| Jackson 직렬화 경로 | **도달 0** — `PlainBody` 는 `crypto` 패키지 **밖에서 참조 0건**. DTO·응답 스키마에 실린 자리가 없다 |
| `/actuator` 설정 덤프 | **도달 0** — actuator 의존 자체가 없다 |
| `DecryptionFailedException` → HTTP | `StorageException` 하위 → **500**, 본문 `{"detail":"저장된 문서를 읽을 수 없습니다"}` 고정. 입력값·바이트 수·파일명 **0** |

**`@JvmInline value class` 의 알려진 함정을 확인했다.** `PlainBody` 는 인라인되면 JVM 표현이
`String` 이므로 `logger.info("{}", body.value)` 는 타입 보호 **밖**이다. 다만 ⑴ 박싱 경로
(`logger.info("{}", plainBody)`)는 재정의 `toString()` 을 타고, ⑵ `.value` 직접 노출은 스캐너
`LOG-BODY` 가 맡는 자리로 이미 분업이 선언돼 있으며(`SensitiveToStringReachTest` KDoc 「막지 못하는
것 ⑶」), ⑶ **`crypto` 밖 참조가 0** 이라 오늘 그런 줄이 존재하지 않는다. `PlainBody` 는
`SensitiveToStringReachTest` 의 탐지 종류(`@JvmInline value class`)에 들어가 상시 검사를 받는다.

**스캐너**: `scan_privacy_invariants.py --changed --base 76f6863` → 검사 파일 **23개**, **exit 0**.
BLOCK 후보 0. WARN 3건은 전부 **테스트 파일**이고 이 단위와 무관하다
(`ContractHeaderDeclarationTest` 2 = `CACHE-HEADER` 분포 확인용 · `FlywayBaselineGuardTest:345` =
`advisory_lock` 문자열 적중). **`crypto/` 신규 5파일은 리포트에 0건.**

---

## 8. `conversions` 3 암호문 컬럼 ↔ 행당 `key_version` — 리더 판정 ②(행 단위 재암호화) 검토

**I-7 관점에서 ② 가 옳다. 동의한다.** 다만 **네 조건**이 붙어야 옳고, 그 조건은 다음 단위의 착수
조건이다.

### 8.1 ① 을 고르지 않는 것이 옳은 이유

대안 ①(행 세대 고정 — 행 생성 시점 세대를 그 행의 모든 후속 쓰기에 사용)은 I-7 과 **정면으로 부딪힌다.**

- **포트에 「이 세대로 암호화」 인자를 여는 순간, "옛 키로 새로 쓰는" 경로가 생긴다.** 지금은 그
  경로가 **타입 수준에서 존재하지 않는다** — 쓰기 세대의 출처가 `writeKeyVersion` **한 곳**뿐이다
  (`StoredContent.kt:53`, `ContentCipher.kt:47`). 이 불변식을 열면 §5.1 에서 실측한 회전 규율이
  강제가 아니라 관습이 된다.
- **회전의 목적이 무효화된다.** 회전은 「한 키가 덮는 데이터 양을 제한하고 키 유출의 폭발 반경을
  줄이는 것」인데, ① 은 오래 사는 행(=검수를 거친, 사용자가 실제로 보관하는 변환)일수록 옛 키에
  계속 묶어 둔다. **가장 오래 남는 데이터가 가장 오래된 키에 남는다.**

### 8.2 ② 가 반드시 함께 져야 하는 조건 4 (다음 단위 착수 조건)

② 는 「검수 저장 시 세 컬럼을 읽어 복호화하고 최신 세대로 다시 봉해 `key_version` 과 함께 갱신」이다.
이것이 새로 들여오는 위험이 넷 있고, 지금 코드는 어느 것도 강제하지 않는다.

| # | 조건 | 왜 |
|---|---|---|
| **⑴ 단일 트랜잭션 · 단일 UPDATE** | 3 암호문 + `key_version` + `encryption_scheme` 을 **한 UPDATE** 로 쓴다 | AAD 에 세대가 실리므로, 일부 컬럼만 새 세대로 바뀌고 `key_version` 이 안 따라가면(또는 그 반대) **그 행은 영원히 열리지 않는다.** 이것은 I-7 이 말하는 「사용자가 올린 문서를 영구히 읽지 못하게 되는」 사고 그 자체이고, 되돌릴 런타임이 없다 |
| **⑵ NULL 을 NULL 로 보존** | `conversions` 세 컬럼은 **nullable**(§6.2 실측). 재암호화가 NULL 을 `PlainBody("")` 로 바꾸면 안 된다 | 「아직 산출되지 않음」이 「산출됐고 비었음」으로 바뀐다. **I-13**(AI 초안 ↔ 검수 수정본 분리 보존)의 기준선이 조작되고, 수정률 KPI 시계열이 끊긴다 |
| **⑶ 복호화 실패 시 전체 중단** | 세 컬럼 중 하나라도 열리지 않으면 **아무것도 쓰지 않는다** | ② 는 **읽기 실패를 쓰기 경로 안으로 끌어들인다**. 실패를 삼키고 그 컬럼만 건너뛰면 검수 저장 한 번이 초안을 지운다 |
| **⑷ 평문 체류 최소화** | 사용자가 건드리지 않은 컬럼(특히 `masked_items_encrypted`)의 평문이 힙에 올라온다 | 마스킹 대응표는 **자리표시자↔원값 표라 최고 민감도**다(`StoredContent.kt:118`). 재암호화 때문에 매 검수 저장마다 그것을 복호화하는 것은 **새로 생긴 노출 창**이다. 예외 경로·로그·덤프에 실리지 않는지 그 단위에서 다시 감사한다 |

### 8.3 ① 도 ② 도 풀지 못하는 것 — **기록 R-5**

**두 방안 모두 「편집되지 않는 행」을 회전시키지 못한다.** `documents.source_text_encrypted` 는
업로드 후 다시 쓰이지 않으므로 **생성 시점 세대에 영구히 묶인다.** 검수되지 않은 `conversions`
행도 같다. 결과적으로 **옛 세대를 `keys` 목록에서 뺄 수 있는 시점이 오지 않는다** — 뺐다는 것은
그 행들을 버렸다는 뜻이다(§5.1 에서 실측했다).

회전을 "실제로 끝낼" 수 있으려면 **일괄 재암호화 배치**가 필요하고, 그것은 30일 보존 파기(I-11)와
같은 자리에서 도는 편이 자연스럽다. **오늘 만들 것은 아니지만, 「회전 절차」를 문서화할 때
*"회전은 새 쓰기에만 적용되고 기존 행은 남는다"* 를 명시하지 않으면 운영자가 옛 키를 지운다.**
그 한 번이 전량 유실이다.

### 8.4 AAD 세대 일관성 → **문제 없음**

재암호화 시 AAD 는 `encrypt` 가 `writeScheme`/`writeKeyVersion` 으로 **자동 재구성**하므로
(`AesGcmContentCipher.kt:116`), 호출자가 세대를 손으로 맞출 여지가 없다. ⑴ 만 지키면 일관성은 자동이다.

---

## 9. I-7 항목별 판정

| I-7 | 요구 | 판정 | 근거(이 문서 절) |
|---|---|---|---|
| **1** | round-trip | **통과** | §1 — 16종 · 0B~3MiB · 오버헤드 전건 28B |
| **2** | 변조·다른 키·형식 오류 **전건 실패** | **통과** | §3 — 비트 변조 **전수 248/248** + 3,000 표본, AAD 109/109, 절단 전건 |
| **3** | 복호화 oracle 금지 | **통과** (타이밍 F-1 수정 필요) | §4 — 8갈래 (타입,메시지,cause) **1종**, 체인 절단 확인 / 타이밍 비 2.84 |
| **4** | nonce 재사용 금지 | **통과** | §2 — 200,000회 충돌 0, 컬럼 간 공유 0, 시드 고정 경로 0 |
| **5** | `encryption_scheme`·`key_version` 이 실제로 쓰인다(키 회전) | **통과** | §5.1 회전 실행 · §3.2 두 컬럼 라벨 조작 전건 거부 · §6.3 CHECK 실측 |
| **6** | 즉흥 암호 금지(표준 AEAD) | **통과** | `AES/GCM/NoPadding` (JCA). 저장소 회귀 `표준 AES-256-GCM 으로 열린다` 가 **제품 코드를 부르지 않고** JCA 로 재조립해 연다 |
| **I-8 파생** | scheme CHECK·DEFAULT | **통과 · 리더 해제 조건 3항 충족** | §6.2~6.4 |

**리더 판정 ⑥(`00_progress.md:1368`)의 해제 조건 3항 대조**

| 조건 | 판정 | 근거 |
|---|---|---|
| ⑴ CHECK 도메인 확대·DEFAULT 교체 또는 제거 | **충족** | §6.2·6.3·6.4 — DEFAULT 4자리 제거, CHECK 는 `aes256gcm-v1` 단독, `fernet-v1` 거부 실측 |
| ⑵ V2 주석의 무효 근거 3개 정정 | **충족** | `V2__encryption_scheme.sql:21-38` 에 「2026-08-19 정정」 블록 — 세 근거를 하나씩 무효 처리하고 V3 로 포인터 |
| ⑶ 두 컬럼이 읽기·쓰기 경로에서 **실제로 쓰인다**(회전 시나리오 실행) | **충족 (단서)** | §5.1 회전 실행 + §3.2 라벨 조작 거부. **단서**: 이것은 **암호 서비스 층**의 읽기/쓰기다. `documents`·`conversions` 에 대한 **DB 읽기/쓰기 경로는 아직 존재하지 않는다**(§10) |

---

## 10. 이 감사가 **확인하지 못한 것** (준수로 적지 않는다)

| 항목 | 왜 확인 불가 | 언제 |
|---|---|---|
| **I-4** 평문 DB 저장 0 — Testcontainers 에 저장 후 DB 직접 조회로 합성 개인정보 리터럴 0건 | **저장 경로가 없다.** `ContentCipher` 를 부르는 제품 코드가 0 이고 `documents`·`conversions` 에 INSERT 하는 repository 가 없다 | 다음 단위(문서 저장) |
| **I-3** 실제 업로드→변환→내보내기 후 **로그 전문 grep 0건** (Phase 4 종료 조건) | 같은 이유. 이번에 확인한 것은 **암호 서비스 자신의 로그**뿐이다(§5.2·§7) | Phase 4·5 |
| **I-6** private 응답 헤더가 문서 응답에 붙는가 | 문서 엔드포인트가 없다 | 다음 단위 |
| **I-7 ⑷ 의 실운영 축** — 조립된 빈을 **실제 키로** 쓰는 통합 경로 | 모든 Spring 컨텍스트가 **0세대**로 조립된다(§5.4 R-1) | 다음 단위 |

**Phase 4 종료 조건(「평문이 DB·로그에 없음」)은 이 단위로 닫히지 않는다.** 이 단위가 만든 것은
그 조건을 **만족시킬 수 있는 도구**이고, 조건 자체는 저장 경로가 그 도구를 실제로 쓰는지로 판정된다.

---

## 11. 처분 목록

### 수정 필요 (차단 아님 — 마감: **다음 작업 단위**)

| # | 항목 | 근거 | 해제 조건 |
|---|---|---|---|
| **F-1** | 복호화 실패 갈래의 타이밍 비 **2.84**(저장소 자체 문턱 1.5 초과). AEAD 시도 4갈래끼리는 1.019 로 균일 | §4.2 실측 | ⓐ 사전 검사 실패 시에도 더미 AES 수행으로 균일화, **또는** ⓑ 균일화하지 않는 근거를 명시하고 `key_version` 존재 여부만 차단. 어느 쪽이든 **M-3b 방식의 상시 회귀 + 음성 대조** |
| **F-2** | `write-key-version` 이 `keys` 에 없어도 기동이 조용하다 → 첫 업로드에서 503 | §5.3 C2 | 기동 시 WARN 한 줄(기동은 계속). 회귀로 고정 |
| **F-3** | **형식이 맞고 값이 틀린 키가 조용히 적재**된다. 그 사이 쓴 행은 진짜 키로 열 수 없다 | §5.3 C3 | 기동 시 세대별 **자기점검**(encrypt→decrypt 왕복) 또는 **키 지문(KCV)** 을 로그에 남겨 운영자가 대조 가능하게. 지문은 키에서 되돌릴 수 없는 형태여야 한다 |
| **F-4** | `key_version` 도메인 무제약 — `-1` 이 **저장까지 성공**. `70000` 은 암호화 통과 후 저장에서 실패 | §5.3 C4 · §6.4 | 스키마에 `CHECK (key_version > 0)` **또는** 조립 시점 검증. `encryption_scheme` 에는 CHECK 가 있는데 `key_version` 에만 없는 **비대칭**을 없앤다 |
| **F-5** | `conversions` 재암호화(②)의 4조건이 강제되지 않는다 | §8.2 | ⑴ 단일 UPDATE ⑵ NULL 보존 ⑶ 실패 시 전체 중단 ⑷ 평문 체류 최소화 — 각각 회귀로 고정. privacy-gate 재감사 대상 |
| **F-6** | 조립된 빈을 **실제 키로** 쓰는 통합 테스트 0 (전 컨텍스트 0세대) | §5.4 | 테스트 프로파일에 합성 키 배선 + 「0세대 컨텍스트에서 문서 API 는 503」을 **의도한 단언**으로 |
| **F-7** | 구현자 §3 대응표가 `PythonSchemaBaselineTest:120` 케이스의 위치를 적지 않아 §6 음성 대조 E 와 어긋나 보인다 | §6.7 | 대응표에 파일 경로 한 줄 추가 |

### 기록 (진행 가능)

| # | 항목 | 근거 |
|---|---|---|
| **R-2** | 고아 서로게이트는 UTF-8 왕복에서 U+FFFD 로 대체된다(JVM 표준). 문서 추출기가 그런 문자를 만들면 저장 전후 문자가 갈린다 — I-13 초안 원본 보존 축에서 알고 있어야 한다 | §1 |
| **R-3** | V2 체크섬 변경은 **fail-closed** 다 — 기존 이력 DB 는 V3 가 **부분 적용되지 않고** V2 상태로 남으며 앱이 기동하지 못한다. 보안 문제 없음. 「배포 이후엔 못 쓴다」가 실측으로 확인됨 | §6.5 |
| **R-5** | ①·② 어느 쪽도 **편집되지 않는 행**(모든 `documents`, 검수 안 한 `conversions`)을 회전시키지 못한다. 옛 세대를 목록에서 빼는 순간 그 행은 영구 유실 — 회전 절차 문서에 **명시 필수** | §8.3 |
| **R-6** | I-7 검증 5 의 예시명 `aes-gcm-v1` ↔ 실제 `aes256gcm-v1`(리더 지시). 스킬이 「예」로 적은 자리라 **위반 아님**이고, 실제 값이 더 낫다(키 길이 구분). CHECK 가 `aes-gcm-v1` 을 거부함을 실측(§6.4) — 다른 레인이 스킬 문면을 리터럴로 읽으면 어긋나므로 여기에 실제 값을 못박아 둔다 | §6.4 |

### 차단

**없음.** `04_privacy-gate_blocking.md` 를 만들지 않았다.

**§5 Phase 7 즉시 중단 기준 대조**: 「AEAD 복호화 round-trip 실패 또는 변조가 예외 없이 통과됨」
→ **해당 없음** (§1 왕복 16종 전건 성립 · §3 변조 3,357건 전건 거부).

---

## 12. 재현

```bash
# ⑴ 저장소 회귀
cd backend-kotlin && ./gradlew :api:test :worker:test :infrastructure:test --rerun-tasks

# ⑵ 스캐너
uv run python .claude/skills/migration-safety-gate/scripts/scan_privacy_invariants.py \
    --changed --base 76f6863            # → exit 0, 검사 파일 23개

# ⑶ 독립 탐침 (저장소 밖에서 컴파일 · 제품 클래스를 리플렉션으로 직접 호출)
#    Probe.java      AEAD 7축 (round-trip · nonce · 변조전수 · oracle · 회전 · 키 · toString)
#    Probe2.java     설정 조립 4축 (중복 세대 · 쓰기 세대 불일치 · 오타 키 · 범위)
#    SchemaProbe.java  pgvector/pgvector:pg16 + Flyway 12.4.0 (스키마 실측 · 체크섬 한계)
#    classpath = {core,application,infrastructure}/build/classes/kotlin/main
#              + kotlin-stdlib 2.3.21 + slf4j-api + logback + (flyway,postgresql,jackson3)
```

탐침 소스는 세션 scratchpad 에 있고 **저장소에 커밋하지 않았다**. 재실행이 필요하면 이 문서의
각 절에 적힌 측정 정의(표본 수 · 워밍업 · 전수 범위)로 재작성할 수 있다.

**감사 산출물에 실제 평문·암호문·키를 옮겨 적지 않았다.** 로그 표본의 개인정보는 전부 합성값이다.
