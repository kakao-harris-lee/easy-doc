# Phase 0 — 암호 호환 spike (scope: `crypto-spike`)

**작성 주체:** `privacy-gate`
**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md` §4.3 / §5 Phase 7 / §6 Crypto 게이트
**바뀐 전제(사용자 승인, 2026-08-12):** §9-2 보존할 운영/파일럿 DB 없음(빈 DB 시작) · §9-1 제품 런타임만 Kotlin화, 오프라인 Python 도구는 독립 검증 oracle로 존치

> 이 문서는 단계별로 갱신된다. 각 단계가 끝나는 즉시 기록하며, 실행하지 않은 것은
> "미검증"으로 남긴다. 실제 키·개인정보는 담지 않는다 — 모든 값은 합성이다.

**진행 상태**

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 선택지 비교와 권고 (코드 실행 없음) | 완료 — **선택지 3(혼합) 권고** |
| 2 | Argon2 양방향 실측 | 완료 — 양방향 통과, 재해시 판정 불일치 1건 |
| 3 | JWT 양방향 실측 | 완료 — 양방향 통과, 기본 clock skew 함정 1건 |
| 4 | 암호화(Fernet) 실측 | 완료 — 양방향+tamper 통과, 기본 TTL 함정 1건, 라이브러리 조달 유보 |

**요약 판정**

| 대상 | 정방향 | 역방향 | tamper | 종료 조건 |
|---|---|---|---|---|
| Fernet | 8/8 | 5/5 | 5/5 | **충족** (구현체 선택은 리더 승인 대기) |
| Argon2 | 13/13 | 4/4 | — | **충족** |
| JWT | 17/17 (nimbus·auth0 양쪽) | 4/4 | — | **충족** |

**차단 없음.** 세 spike 모두 호환 가능성이 실측으로 확인됐다. 발견된 3건은 전부
"라이브러리 기본값을 그대로 쓰면 Python과 갈린다" 유형으로, Phase 3·4 구현 시
명시적 설정으로 닫아야 하는 항목이며 현재 위반할 코드가 존재하지 않는다.
따라서 `00_privacy-gate_blocking.md`는 생성하지 않는다.

**Phase 3·4로 이월되는 필수 조치 3건** (해제 조건은 각 절에 기재)

| # | 항목 | 기본값을 쓰면 생기는 일 | 절 |
|---|---|---|---|
| A | Argon2 재해시 판정을 전체 파라미터 동등성으로 | 파라미터 변경 시 이관이 조용히 멈춤 | §2.4 |
| B | JWT clock skew를 0으로 명시 | 만료 토큰이 최대 59초 더 통용 | §3.4 |
| C | Fernet 복호화 Validator의 TTL 무력화 | **업로드 60초 뒤 모든 문서가 안 읽힘** | §4.3 |

C가 가장 무겁다 — 갓 만든 토큰으로만 테스트하면 통과해 버리므로 회귀 테스트
설계까지 함께 지정했다.

---

## 1단계 — 판단: 빈 DB에서 암호 호환은 무엇을 위한 것인가

### 1.1 전제 변화가 무너뜨린 것과 남긴 것

계획 §4.3의 첫 문장은 이렇게 쓰였다.

> 현재 DB 본문은 Python `cryptography`의 Fernet 토큰이다. 이 호환성을 증명하지 못하면
> Kotlin API가 기존 문서를 읽을 수 없으므로 가장 먼저 spike한다.

§9-2 승인으로 **"기존 문서"가 존재하지 않게 됐다.** 따라서 §4.3이 상정한 위험 —
정방향(Kotlin이 Python 암호문을 읽는다) 실패로 기존 문서를 못 읽는 사고 — 는
**소멸했다.** 절체 시점에 `documents.source_text_encrypted`·
`conversions.*_encrypted`에 행이 0건이면 읽을 옛 암호문 자체가 없다.

무너지지 않고 남은 것은 **역방향**이다. 근거는 §5 Phase 7 두 문장이다.

> 롤백은 Kotlin API·worker를 모두 정지한 뒤 수행한다. Kotlin DB 작업을 ARQ로 다시
> 등록하는 검증된 one-shot 도구를 준비하고, 관찰 기간에는 Python 이미지·Redis·기존
> 키를 보존한다. **Kotlin이 새 암호 방식으로 쓰기 시작하는 변경은 이 관찰 기간 뒤에만
> 허용한다.**

즉 관찰 기간(1~2주) 동안 축적되는 것은 **Kotlin이 쓴 암호문**이고, 롤백이 발동하면
그것을 읽어야 하는 주체는 **되살아난 Python 런타임**이다. 판정 대상은 정방향이
아니라 역방향이다.

**역방향이 구체적으로 어디서 터지는가** (합성 시나리오):

1. 절체 후 D+3, 사용자가 문서를 올린다 → Kotlin이 `source_text_encrypted`에 쓴다
2. D+5, 즉시 중단 기준에 걸려 롤백 발동
3. Phase 7이 지시한 one-shot 도구가 `pending` 변환을 ARQ에 다시 등록한다
   (이 도구 자체는 ID만 다루므로 복호화 불필요)
4. **되살아난 Python worker가 그 작업을 집는다** → `app/workers/tasks.py` 경로에서
   `TextCipher.decrypt(source_text_encrypted)` 호출 → 여기서 형식이 갈리면
   `StorageError("저장된 문서를 읽을 수 없습니다")`로 전건 실패
5. 사용자에게는 D+3~D+5 사이 업로드분 전체가 영구 실패로 보인다

4번이 역방향 호환의 실제 접점이다. **복호화 실패가 조용하지 않다는 점이 그나마
다행이지만**(`app/privacy/crypto.py:60-65`가 `InvalidToken`을 `StorageError`로
올린다), 실패 시점이 롤백 직후 — 이미 사고 대응 중 — 이라는 것이 문제다.

### 1.2 세 선택지 비교 — 기준은 "롤백 창에서 무엇이 깨지는가"

#### 선택지 1 — Fernet 유지

| 항목 | 평가 |
|---|---|
| 롤백 창 역방향 | **깨지지 않는다.** Python `TextCipher`가 무수정으로 읽는다 |
| 롤백 창 Python 재기동 후 쓰기 | 같은 형식이므로 재-절체 시에도 단일 형식 유지 |
| 데이터 이관 비용 | 0 (빈 DB) |
| Python 쪽 신규 코드 | **0줄** |
| 스키마 변경 | 없음 (`key_version`은 이미 존재) |
| 남는 위험 | **JVM Fernet 구현의 조달** — §4.3-2가 "유지보수 상태와 보안 검토가 가능한" 구현을 요구한다. JVM Fernet 라이브러리 생태계는 얇고 bus factor가 낮다 |

핵심 장점은 **롤백이 데이터 형식 문제를 전혀 건드리지 않는 순수 런타임 교체가
된다**는 점이다. 사고 대응 중에 검증할 것이 하나 줄어든다.

핵심 리스크는 의존성 조달 하나로 좁혀지고, 그것은 두 갈래로 나뉜다.

- **1-a. 서드파티 JVM Fernet 라이브러리 채택** — §4.3-2가 요구한 "유지보수 상태와
  보안 검토 가능성"을 실제로 확인해야 한다. 최종 릴리스 시점·미해결 보안 이슈·
  코드 규모를 기록으로 남긴 뒤에만 채택한다. (4단계 실측 대상)
- **1-b. JDK 표준 primitive로 Fernet 토큰 조립을 자체 구현** — Fernet은 알고리즘이
  아니라 **고정된 wire format**이다(`0x80` 버전 바이트 | 8바이트 BE 타임스탬프 |
  16바이트 IV | AES-128-CBC/PKCS7 암호문 | HMAC-SHA256 32바이트). 필요한 primitive는
  전부 `javax.crypto`에 있다(`AES/CBC/PKCS5Padding`, `Mac("HmacSHA256")`).

  1-b가 §4.3-4의 "직접 암호 알고리즘을 즉흥 구현하지 않는다" 금지에 걸리는지는
  **판단이 갈리는 회색지대다.** 보수적으로 양쪽 해석을 병기한다.
  - 위반 해석: 암호 관련 코드를 직접 쓰는 것 자체가 금지 대상이다
  - 비위반 해석: 금지 대상은 *알고리즘 설계*이고, 여기서는 공개된 고정 포맷을
    표준 primitive로 조립할 뿐이며 §9-1로 **Python 구현이 차분 검증 oracle로
    남는다**는 조건이 붙는다
  - 필수 방어: HMAC 비교는 반드시 `MessageDigest.isEqual`(constant-time),
    TTL 검증은 하지 않는다(Python `TextCipher.decrypt`가 ttl 없이 호출하므로
    JVM 쪽이 TTL을 강제하면 동작이 갈린다), 실패는 단일 예외로 정규화

  **1-a와 1-b 중 무엇을 쓸지는 리더 결정 사항이다.** 이 문서는 4단계 실측으로
  1-a의 조달 가능성 근거만 제공한다.

#### 선택지 2 — 표준 AEAD로 새 시작 (AES-GCM 등)

과제가 지시한 대로 **Python 쪽 구현 비용을 계산한다.** 결론부터: 겉보기 LOC는 작고
실제 비용은 크다.

| 비용 항목 | 규모 | 성격 |
|---|---|---|
| ① Python AEAD 복호화기 (`app/privacy/crypto.py`에 scheme 분기 + AES-GCM 리더) | 40~60줄 | 작음 |
| ② envelope 포맷 설계 + 보안 검토 (nonce 배치·길이, AAD에 무엇을 묶을지, key id 인코딩, 버전 바이트) | LOC 아님 | **큼** — 설계 산출물과 리뷰가 필요 |
| ③ `encryption_scheme` 컬럼을 **절체 전에** 스키마에 넣고 Python SQLAlchemy 모델까지 반영 | 모델 2개 + Alembic 1건 | 중간. **삭제 예정 런타임(§9-1 대상)에 신규 코드를 넣는 역행** |
| ④ 롤백 이미지 재빌드 + 역방향 E2E 재검증, 그리고 **절체 전 동결** | 절차 | **큼** |
| ⑤ 롤백 후 Python이 이어서 쓰는 형식 문제 | 설계 | **가장 큼 — 아래 참조** |

⑤가 결정적이다. 롤백은 "Python이 Kotlin 데이터를 읽고 끝"이 아니라 **Python이
읽고 나서 계속 쓰는 상태로 복귀**하는 것이다. 그러면 두 갈래 모두 이중 형식으로
귀결된다.

- 롤백한 Python이 **Fernet으로 쓴다** → 테이블에 `aes-gcm`(Kotlin 산) 행과
  `fernet-v1`(롤백 Python 산) 행이 섞인다. 재-절체 시 Kotlin은 **두 리더를 모두**
  가져야 한다
- 롤백한 Python이 **AES-GCM으로 쓴다** → Python 쪽에 복호화기뿐 아니라 **암호화기와
  nonce 관리까지** 필요하다. ①의 40~60줄 추정이 무너진다

즉 **선택지 2는 이중 스킴 지원을 회피하지 못하고 오히려 양쪽 런타임에 강제한다.**
선택지 1/3 대비 일이 줄지 않고 늘어난다. 빈 DB로 이관 비용이 0이라는 장점은
운영 데이터가 없는 데서 오는 것이고, 그 장점은 선택지 3도 똑같이 누린다.

추가로 **선택지 2는 계획 §5 Phase 7 본문과 정면으로 충돌한다** — "Kotlin이 새 암호
방식으로 쓰기 시작하는 변경은 이 관찰 기간 뒤에만 허용한다". 절체 첫날부터
AES-GCM으로 쓰는 것이 바로 그 금지 대상이다. 채택하려면 **계획 문구 개정이
선행돼야 하고 그것은 리더 권한이다.**

#### 선택지 3 — 혼합 (관찰 기간 Fernet, 롤백 창이 닫힌 뒤 전환)

| 항목 | 평가 |
|---|---|
| 롤백 창 역방향 | 선택지 1과 동일 — 깨지지 않는다 |
| 계획 정합성 | §4.3-3(관찰 기간 `fernet-v1` 읽기/쓰기 유지)·§4.3-5(`encryption_scheme`+`key_version` 병행 관리)·§5 Phase 7 금지 조항을 **모두 그대로 만족**한다 |
| 전환 시점 비용 | 관찰 기간 중 생성된 행만 대상 — 빈 DB에서 출발하므로 1~2주치. Phase 8에서 Python 런타임이 사라진 뒤라 **단일 런타임 내부 변경**이 된다 |
| 선행 조건 | `encryption_scheme` 컬럼이 **첫 Flyway 마이그레이션에 additive로** 들어가 있어야 한다 |

선택지 1과의 실질 차이는 "나중에 옮길 것을 지금 준비해 두는가" 하나다. 그 준비
비용은 컬럼 하나(§4.3-5가 이미 요구)이고, 그것이 없으면 나중 전환이 **스키마 변경을
동반하는 작업**으로 커진다.

### 1.3 권고

> **선택지 3(혼합)을 권고한다.** 구체적으로:
>
> 1. **관찰 기간 종료 시점까지 Kotlin의 읽기·쓰기 모두 `fernet-v1`로 고정한다.**
>    Phase 7 금지 조항을 문자 그대로 지킨다
> 2. **`encryption_scheme` 컬럼을 Phase 1 첫 Flyway 마이그레이션에 additive로 넣고,
>    기본값 `'fernet-v1'`을 server default로 박는다.** §4.3-5 요구사항이며, 이것이
>    없으면 Phase 8 이후 전환이 스키마 변경을 동반한다. 추가만 하고 **관찰 기간 중
>    값은 `fernet-v1` 한 종류만 쓴다**
> 3. **`key_version`은 지금처럼 쓰기 경로에서 계속 채우되, Kotlin에서는 읽기 경로에도
>    실제로 물린다.** (현재 Python은 모델 default로 쓰기만 하고 읽는 코드가 없다 —
>    §1.4 참조)
> 4. **AES-GCM 등 표준 AEAD로의 전환은 Phase 8(Python 런타임 제거) 이후 별건으로
>    분리한다.** 그 시점에는 단일 런타임이므로 역방향 요구가 사라지고, §4.3-4가 말한
>    "별도 승인 작업"의 형태에 자연스럽게 맞는다
>
> **선택지 2는 권고하지 않는다.** 빈 DB의 이점(이관 비용 0)은 선택지 3도 동일하게
> 누리는 반면, 선택지 2만 (a) 이중 스킴을 양쪽 런타임에 강제하고 (b) 삭제 예정
> Python 런타임에 신규 암호 코드를 추가하며 (c) 계획 §5 Phase 7 개정을 선행 조건으로
> 요구한다. 얻는 것 없이 롤백 창의 검증 부담만 늘린다.

**권고에 붙는 미확정 하나** — 선택지 3은 JVM Fernet 구현 조달을 전제한다. 그
조달이 1-a(서드파티)인지 1-b(JDK primitive 자체 조립)인지는 4단계 실측 결과를 보고
**리더가 결정**한다. 두 갈래 모두 실패하는 경우에만 선택지 2를 재검토 대상으로
올린다.

### 1.4 판단 과정에서 함께 확인된 사항 (Phase 1 이후로 넘김)

이 spike의 직접 대상은 아니지만 §4.3-5 판정에 걸리므로 기록한다.

| 항목 | 확인 결과 | 근거 |
|---|---|---|
| `key_version` **쓰기** 경로 | 존재 — 단, 모델 default로만 채워진다 | `app/models/document.py:59-61`, `app/models/conversion.py:80-83` (`default=CURRENT_KEY_VERSION, server_default=text("1")`) |
| `key_version` **읽기** 경로 | **애플리케이션 코드에 없다.** `app/repositories/`·`app/services/`·`app/workers/` 어디서도 참조하지 않으며, 참조하는 곳은 테스트 2곳뿐 | `grep -rn "CURRENT_KEY_VERSION\|key_version" app/repositories/ app/services/ app/workers/` → 0건. `tests/repositories/test_documents.py:89,150`에서만 단언 |
| `encryption_scheme` | **스키마에 없다.** §4.3-5의 향후 요구사항 | `grep -rn "encryption_scheme" app/` → 0건 |

`key_version`이 쓰이기만 하고 읽히지 않는 상태는 **현재로선 위반이 아니다** —
컬럼의 목적이 "키 교체 시 대상 선별"(`app/privacy/crypto.py:18-23`)이고 키 교체가
아직 없었기 때문이다. 다만 Kotlin 포팅 시 이 컬럼을 그냥 흘려보내면 복호화 경로가
키 세대를 무시하게 되므로, **Phase 4 감사 항목으로 이월한다.**

### 1.5 parity 검증 방식에 대한 선결 사항 (`parity-verifier` 앞)

Fernet 암호문은 **바이트 비교가 불가능하다.** 토큰에 8바이트 타임스탬프와 16바이트
랜덤 IV가 들어가므로 같은 평문·같은 키라도 매 호출 결과가 다르다
(`app/privacy/crypto.py:50` 주석이 같은 내용을 명시). 따라서 crypto 도메인
parity fixture는 **round-trip(교차 복호화) 방식**이어야 하며, 산출 바이트 해시로
잡으면 안 된다. 이는 `kotlin-implementer`가 doc-spike에서 zip 컨테이너에 대해 낸
같은 종류의 지적(`00_kotlin-implementer_doc-spike.md` 미해결 (2))과 성격이 같다.

---

## 2단계 — Argon2 양방향 실측

**판정: 양방향 호환 확인 — 단, 재해시 판정 로직에 불일치 1건 (Phase 3 필수 조치)**

### 2.1 실행 환경과 대상

| 항목 | 값 |
|---|---|
| JVM | Temurin 21.0.4+7 LTS |
| Gradle / Kotlin | 9.1.0 / 2.2.0 |
| Kotlin 측 구현 | `org.springframework.security:spring-security-crypto:6.4.2` (`Argon2PasswordEncoder`) + `org.bouncycastle:bcprov-jdk18on:1.78.1` + `org.springframework:spring-jcl:6.2.1` |
| Python 측 oracle | `argon2-cffi 25.1.0`, `app/services/auth.py::_HASHER` 를 **직접 import** 해 사용 |
| fixture | `dump_parity_fixtures.py --domain argon2` 14건 (실행 성공) + 재해시 경계 탐침 7건 자체 생성 |

Kotlin 인코더 설정은 `Argon2PasswordEncoder(16, 32, 4, 65536, 3)`이다. `m=65536,
t=3, p=4`는 `app/services/auth.py:59`에서 직접 읽었고, salt 16B·hash 32B는 fixture
PHC의 base64 길이(22자·43자)에서 역산해 확인했다(추측하지 않았다).

### 2.2 정방향 — Python이 만든 PHC를 Kotlin이 검증

**fixture 14건 중 PHC 대상 13건 전건 일치.** 검증 결과(`verified`)와 파싱
(`type`/`version`/`m,t,p`/salt·hash 길이) 모두 Python 기대값과 같다.

특히 통과한 것:

| 케이스 | 확인된 동작 |
|---|---|
| `argon2-verify-hangul` / `-long` / `-empty` / `-symbols` | UTF-8 바이트 그대로 해싱 — 인코딩 차이 없음 |
| `argon2-hangul-nfd-mismatch` | NFD 표기는 **거부됨**. Kotlin 쪽이 유니코드 정규화를 몰래 걸지 않는다 |
| `argon2-legacy-verify` (`m=8192,t=2,p=2`) | 현재 설정과 다른 파라미터의 PHC를 **PHC 안의 값으로** 검증. 설정값을 가정해 파싱하지 않는다 |
| `argon2-tampered-phc` | 거부 |
| `argon2-invalid-phc` (`"not-a-phc-string"`) | `matches`가 예외를 밖으로 흘리지 않고 `false` 반환 — "불일치"와 "해시 깨짐"이 응답으로 구분되지 않는다(§보안 원칙 1 유지) |

### 2.3 역방향 — Kotlin이 만든 PHC를 Python이 검증

Kotlin이 4종 비밀번호(ASCII·한글·빈 값·제어문자 포함)로 PHC를 발급하고
`app/services/auth.py::_HASHER`가 검증했다. **4건 전건 PASS.**

| 확인 항목 | 결과 |
|---|---|
| `_HASHER.verify(kotlin_phc, pw)` | 4/4 `True` |
| `_HASHER.check_needs_rehash(kotlin_phc)` | 4/4 `False` — Kotlin 산출물이 현재 파라미터와 동일 |
| 틀린 비밀번호 거부 | 4/4 `VerificationError` |
| PHC prefix | 4/4 `$argon2id$v=19$m=65536,t=3,p=4$` — Python 산출 prefix와 동일 |

**롤백 창 판정**: 관찰 기간 중 Kotlin이 만든 사용자 계정의 비밀번호 해시를 롤백한
Python이 그대로 검증한다. Argon2는 역방향 위험이 **없다.**

### 2.4 불일치 1건 — 재해시 판정 조건 (`needs_rehash`)

**공식 fixture 14건은 전부 통과하므로 이 불일치는 fixture로 드러나지 않는다.**
경계를 직접 탐침해 발견했다.

원인은 두 구현의 판정 방식이 다르다는 것이다.

- **Python** `argon2-cffi`의 `check_needs_rehash`: 현재 파라미터와 PHC 파라미터의
  **전체 튜플 동등성** 비교(type·version·salt_len·hash_len·t·m·p)
- **Kotlin** Spring `Argon2PasswordEncoder.upgradeEncoding`: `memory < 설정값
  || iterations < 설정값` — **memory와 iterations만, 그것도 "미만"으로만** 본다

실측 결과 (`password` 동일, PHC 파라미터만 변경. 전건 `verified`는 양쪽 `true`):

| 탐침 | PHC 파라미터 | Python | Kotlin | |
|---|---|---|---|---|
| `probe-same-as-current` | `m=65536,t=3,p=4` | `false` | `false` | 일치 |
| `probe-weaker-memory` | `m=32768,t=3,p=4` | `true` | `true` | 일치 |
| `probe-parallelism-differs` | `m=65536,t=3,p=2` | `true` | **`false`** | **불일치** |
| `probe-stronger-memory` | `m=131072,t=3,p=4` | `true` | **`false`** | **불일치** |
| `probe-stronger-iterations` | `m=65536,t=4,p=4` | `true` | **`false`** | **불일치** |
| `probe-hash-len-differs` | hash_len=64 | `true` | **`false`** | **불일치** |
| `probe-salt-len-differs` | salt_len=32 | `true` | **`false`** | **불일치** |

집계: 대조 필드 일치 31 / 불일치 6. 불일치 6의 내역은 **위 5개 탐침의
`needs_rehash` 5건 + `argon2-current-parameters` 1건**이며, 후자는 불일치가 아니다 —
이 케이스는 `input`이 비어 있어(파싱할 PHC가 없다) Kotlin 산출물에 항목이 생기지
않았고, 내 대조 스크립트가 그것을 `MISSING`으로 셌다. 해당 케이스가 선언한 값
(`t=3, m=65536, p=4`)은 Kotlin 인코더 설정과 같고 §2.3의 역방향 PHC prefix
(`$argon2id$v=19$m=65536,t=3,p=4$`)로 실제 확인됐다.

**따라서 실제 불일치는 `needs_rehash` 5건뿐이고, 공식 fixture 13건은 전건
일치한다.**

**영향 판정 — 지금은 사고가 아니다. 그러나 조용히 정책이 죽는 종류다.**

- 현재 운영에는 영향이 없다. 살아 있는 해시는 전부 `m=65536,t=3,p=4`이고 그 경우
  양쪽 모두 `false`다. 빈 DB에서 시작하므로 legacy 해시도 없다
- 위험은 **파라미터를 바꾸는 날** 나타난다. 특히 `parallelism`만 조정하거나
  `salt_len`/`hash_len`을 바꾸면 Kotlin은 **아무도 재해시하지 않는다.** 로그도
  오류도 남지 않고 이관만 멈춘다 — `argon2-legacy-verify` fixture의 설명이 경고한
  "이관이 멈춘다"가 정확히 이 상태다
- 보안 방향은 "덜 재해시함"이므로 즉시 취약점은 아니다. 반대 방향(불필요한 재해시
  폭주)이 아닌 것은 다행이다

**차단하지 않는다.** Phase 0 spike의 판정 대상(호환 가능성)은 충족됐고, 이것은
Phase 3 구현 시 명시적으로 처리하면 되는 항목이다.

**해제 조건 (Phase 3 `kotlin-implementer` 앞)**: `upgradeEncoding`을 그대로 쓰지
말고, PHC를 파싱해 `(type, version, m, t, p, salt_len, hash_len)` **전체 동등성**으로
재해시를 판정하는 함수를 직접 두고, 위 7개 탐침을 회귀 테스트로 고정할 것. 검증
방법은 이 절의 표를 그대로 재현하면 된다.

### 2.5 부수 관찰 — 로깅

`Argon2PasswordEncoder.matches`는 PHC 파싱 실패 시 `WARNING`으로 `"Malformed
password hash"`와 **전체 스택트레이스**를 남긴다(실측 확인). 다만 예외 메시지는
`"Invalid encoded Argon2-hash"`로 **PHC 값 자체를 담지 않으므로 불변식 #4(평문 로그)
위반은 아니다.** Phase 3에서 이 로거의 레벨·출력 형태를 확인 대상으로 이월한다 —
스택트레이스가 운영 로그를 채우는 것은 별개 문제다.

---

## 3단계 — JWT 양방향 실측

**판정: 양방향 호환 확인 — 단, 라이브러리 기본 clock skew가 함정 (Phase 3 필수 조치)**

### 3.1 `app/services/auth.py`에서 읽은 실제 값 (추측 아님)

| 항목 | 값 | 근거 |
|---|---|---|
| `_ALGORITHM` | `HS256` | `auth.py:45` |
| `_TOKEN_TYPE` | `access` (페이로드 `typ` 클레임, JOSE 헤더 `typ`와 별개) | `auth.py:47-50` |
| require 클레임 | `["sub", "exp", "typ"]` | `auth.py:238` |
| `MIN_JWT_SECRET_BYTES` | `32` — 미만이면 `ConfigurationError`로 **기동 경로에서 차단** | `auth.py:43`, `auth.py:167-170` |
| 클레임 구성 | `sub`(uuid 문자열)·`exp`·`typ` 셋뿐. 이메일 등 개인정보 없음 | `auth.py:270-278` |
| 실패 정규화 | `PyJWTError`·`TypeError`·`ValueError` → 전부 `InvalidCredentialsError` 동일 메시지 | `auth.py:246-248` |
| PyJWT 버전 | 2.13.0 | 실측 |

### 3.2 정방향 — Python 발급 토큰을 Kotlin이 검증

fixture 18건 중 토큰 대상 17건. **nimbus-jose-jwt 9.41.2 · auth0 java-jwt 4.4.0
양쪽 모두 17/17 일치** (outcome + subject).

통과한 공격/경계 케이스: `jwt-alg-none`(알고리즘 혼동), `jwt-alg-rs256-header`,
`jwt-forged-signature`, `jwt-tampered-payload`, `jwt-wrong-secret`,
`jwt-missing-{sub,exp,typ}`, `jwt-wrong-typ`, `jwt-non-uuid-subject`, `jwt-garbage`,
`jwt-secret-exactly-min-bytes`(32B 통과), `jwt-secret-one-byte-short`(31B →
`configuration_error`).

### 3.3 `exp` 경계 — 과제가 지목한 질문에 대한 답

> **PyJWT는 `exp <= now`를 만료로 보는데 JVM 라이브러리는 흔히 `exp < now`를 쓴다 —
> 정확히 `exp` 시점에서 갈리는지 확인하라**

**갈리지 않는다. 단, clock skew를 0으로 명시했을 때만 그렇다.**

`verify_at`을 `exp-2 … exp+2`로 훑으며 예외 타입까지 확인했다(결과만 보고 판정하면
다른 이유로 거부된 것을 만료로 오독할 수 있어 메커니즘을 함께 봤다).

| `now - exp` | PyJWT | nimbus (skew=0) | auth0 (leeway=0) |
|---|---|---|---|
| `-2s` | 유효 | ACCEPT | ACCEPT |
| `-1s` | 유효 | ACCEPT | ACCEPT |
| **`0s`** | **만료** | **REJECT** `BadJWTException: Expired JWT` | **REJECT** `TokenExpiredException` |
| `+1s` | 만료 | REJECT | REJECT |
| `+2s` | 만료 | REJECT | REJECT |

즉 두 라이브러리 모두 `exp <= now`를 만료로 본다 — PyJWT와 같다. 계획이 우려한
off-by-one은 **이 버전 조합에서는 발생하지 않는다.**

### 3.4 실제 함정 — nimbus 기본 clock skew 60초

경계가 맞는 것은 내가 `maxClockSkew = 0`을 **명시했기 때문이다.** 기본값으로 두면
결과가 달라진다. 라이브러리 기본 설정만으로 다시 측정했다.

```
nimbus DefaultJWTClaimsVerifier 기본 maxClockSkew = 60초
```

| `now - exp` | nimbus (기본) | auth0 (기본) | PyJWT |
|---|---|---|---|
| `+0s` | **ACCEPT** | REJECT | 만료 |
| `+30s` | **ACCEPT** | REJECT | 만료 |
| `+59s` | **ACCEPT** | REJECT | 만료 |
| `+60s` | REJECT | REJECT | 만료 |

**`DefaultJWTClaimsVerifier`를 기본값으로 쓰면 만료 토큰이 최대 59초 더 통용된다.**
`jwt-exp-boundary-exact` fixture가 정확히 이 지점을 겨냥해 만들어졌고, 기본값
구현은 그 fixture에서 실패한다.

주의를 더할 근거: Spring Security의 `NimbusJwtDecoder`가 쓰는
`JwtTimestampValidator`도 **기본 clock skew가 60초**다. Phase 3에서 Spring Security의
JWT 지원을 그대로 채택하면 같은 함정을 다시 밟는다. auth0 java-jwt는 기본 leeway가
0이라 이 문제가 없다.

**판정: 잠정 위반이 아니라 "구현 시 필수 설정" 항목으로 본다.** 아직 Kotlin 구현이
없으므로 위반할 코드 자체가 없다. 다만 기본값에 기대면 확실히 어긋나므로 §4.5의
"기본값에 의존은 위반으로 본다"와 같은 취급을 JWT에도 적용한다.

**해제 조건 (Phase 3 `kotlin-implementer` 앞)**: 만료 검증의 clock skew를 **0으로
명시**하고(`maxClockSkew = 0` 또는 auth0 `acceptLeeway(0)`), `jwt-exp-boundary-exact`
와 `jwt-exp-boundary-one-second-before` 두 fixture를 회귀 테스트로 고정할 것.

### 3.5 역방향 — Kotlin 발급 토큰을 Python이 검증

Kotlin(nimbus)이 `sub`·`exp`·`typ` 3클레임 HS256 토큰을 2개 subject로 발급하고,
`dump_parity_fixtures.py verify-jwt`가 Python으로 검증했다.

```
역방향 검증 통과 (verify-jwt): 4건
[기록] jwt-verify.verified.json (status: pass)
```

4건 = subject 2종 × {유효 시점(`exp-1`) → `ok`, 만료 시점(`exp+60`) →
`invalid_credentials`}. **만료 동작까지 역방향으로 확인됐다.**

> 이 `*.verified.json`은 스킬 문서가 명시한 대로 **판정의 입력이 아니라 실행
> 기록**이다. 게이트 판정은 `compare_parity.py`가 자체 검증기로 다시 돌려서 한다.
> 여기서는 Phase 0 spike의 근거로만 인용한다.

**롤백 창 판정**: 관찰 기간 중 Kotlin이 발급한 액세스 토큰을 롤백한 Python이 그대로
검증한다. 시크릿만 보존되면 JWT는 역방향 위험이 **없다.** (토큰 수명이 30분이므로
롤백 후 최대 30분이면 자연 소멸한다는 점도 위험을 더 줄인다.)

---

## 4단계 — 암호화(Fernet) 실측

**판정: 양방향 + tamper 전건 통과 — 단, 기본 TTL이 함정이고 라이브러리 유지보수
상태에 유보가 붙는다**

### 4.1 대상

| 항목 | 값 |
|---|---|
| JVM 구현 | `com.macasaet.fernet:fernet-java8:1.5.0` |
| Python 기준 | `cryptography 50.0.0`, `app/privacy/crypto.py::TextCipher` |
| fixture | `dump_parity_fixtures.py --domain crypto` 9건 (토큰 대상 8 + roundtrip 요청 1) |

### 4.2 정방향 — Python 토큰을 Kotlin이 복호화: **8/8 일치**

| 케이스 | Python | Kotlin(TTL 비활성) | 평문 |
|---|---|---|---|
| `crypto-decrypt-ascii` | ok | ok | 일치 |
| `crypto-decrypt-hangul` | ok | ok | 일치 |
| `crypto-decrypt-empty` | ok | ok | 일치 |
| `crypto-decrypt-long` | ok | ok | 일치 |
| `crypto-decrypt-emoji-and-control` | ok | ok | 일치 |
| `crypto-tampered` | invalid_token | invalid_token | — |
| `crypto-wrong-key` | invalid_token | invalid_token | — |
| `crypto-garbage` | invalid_token | invalid_token | — |

한글·빈 문자열·긴 값·제어문자 평문이 바이트 단위로 복원됐다.

### 4.3 함정 — `fernet-java8` 기본 Validator는 TTL 60초

```
fernet-java8 기본 Validator: TTL=PT1M, maxClockSkew=PT1M
```

Python `TextCipher.decrypt`는 `self._fernet.decrypt(data)`를 **ttl 인자 없이**
호출한다(`app/privacy/crypto.py:61`) — 즉 타임스탬프를 검증하지 않는다. 반면
`fernet-java8`의 기본 `Validator`는 **생성 후 60초가 지난 토큰을 만료로 거부**한다.

기본 Validator로 같은 fixture를 돌린 결과:

| 케이스 | Python | Kotlin(라이브러리 기본) |
|---|---|---|
| `crypto-decrypt-ascii` | ok | **`TokenExpiredException`** |
| `crypto-decrypt-hangul` | ok | **`TokenExpiredException`** |
| `crypto-decrypt-empty` | ok | **`TokenExpiredException`** |
| `crypto-decrypt-long` | ok | **`TokenExpiredException`** |
| `crypto-decrypt-emoji-and-control` | ok | **`TokenExpiredException`** |

**유효 토큰 5건 전부 실패한다.** 이것을 그대로 구현하면 **업로드 60초 뒤부터 모든
문서가 읽히지 않는다** — 보존 기간 30일 정책이 통째로 무의미해지고, 증상은
`StorageError("저장된 문서를 읽을 수 없습니다")`로만 나타나 원인 추적이 어렵다.

§4.5가 파서에 대해 "기본값에 의존은 위반으로 본다"고 한 것과 같은 취급을 여기에도
적용한다.

**해제 조건 (Phase 4 `kotlin-implementer` 앞)**: 복호화 경로의 Validator에서 TTL과
maxClockSkew를 **명시적으로 무력화**하고(문서 본문 암호문은 시간 제한 대상이 아니다),
`crypto-decrypt-*` fixture를 **생성 후 60초 이상 지난 상태로** 회귀 테스트에
고정할 것 — 갓 만든 토큰으로만 테스트하면 이 결함이 통과한다.

부수 관찰: `Token.validateAndDecrypt`는 **TTL을 HMAC 검증보다 먼저** 확인한다.
그래서 기본 설정에서는 `crypto-wrong-key`조차 서명 실패가 아니라
`TokenExpiredException`으로 거부된다. 최종 판정(`invalid_token`)은 같지만, 실패
사유로 분기하는 코드를 쓰면 동작이 갈린다.

### 4.4 역방향 — Kotlin 토큰을 Python이 복호화: **5/5 통과**

```
역방향 검증 통과 (verify-crypto): 5건
[기록] crypto-verify.verified.json (status: pass)
```

`crypto-roundtrip-request`가 요청한 평문 5종(ASCII·한글·빈 값·긴 한글·제어문자)을
Kotlin이 암호화하고 Python이 전건 복원했다.

> 입력 스키마 주의(다음 사용자를 위한 기록): `verify-crypto`는 최상위 JSON **객체**의
> `cases` 배열을 요구하고, 각 항목의 평문 필드명은 `plaintext`가 아니라
> **`expected_plaintext`**다. `verify-jwt`도 최상위 객체를 요구한다.

**롤백 창 판정 — 이 spike의 핵심 질문에 대한 답**: 관찰 기간 중 Kotlin이 암호화해
저장한 문서 본문을, 롤백한 Python `TextCipher`가 **수정 없이 복호화한다.**
§1.1에서 제시한 역방향 시나리오(D+3 업로드 → D+5 롤백 → Python worker가 복호화)는
**깨지지 않는다.** 선택지 1/3의 전제가 실측으로 확인됐다.

### 4.5 tamper test — 5개 위치 전부 거부

Kotlin이 만든 토큰의 각 구획에서 1비트를 뒤집고 Python `TextCipher.decrypt`에
넣었다.

| 변조 위치 | Python 판정 |
|---|---|
| version byte (offset 0) | 거부 (`StorageError`) |
| timestamp (offset 4) | 거부 (`StorageError`) |
| IV (offset 12) | 거부 (`StorageError`) |
| ciphertext (offset 30) | 거부 (`StorageError`) |
| HMAC (마지막 바이트) | 거부 (`StorageError`) |
| 무변조 대조군 | 정상 복호화 |

전부 `StorageError`로 정규화됐다 — 실패 사유가 응답으로 구분돼 새지 않는다.
§6 Crypto 게이트의 "tamper test 통과" 요건을 충족한다.

### 4.6 §4.3-2가 요구한 "유지보수 상태와 보안 검토 가능성" — **유보**

§4.3-2는 JVM Fernet 구현의 유지보수 상태를 확인하라고 요구한다. 실측 결과:

| 항목 | 값 | 출처 |
|---|---|---|
| 최신 버전 | **1.5.0** | `maven-metadata.xml` |
| 최신 릴리스 시각 | **2020-09-26** | jar `Last-Modified` 헤더 |
| `lastUpdated` | `20200926005017` | `maven-metadata.xml` |
| 경과 | **약 5년 11개월 무릴리스** | |
| 코드 규모 | 클래스 10개 (`Key`, `Token`, `Validator`, 예외 6종 등) | jar 목록 |

**기능은 통과했으나 조달 판정은 "유보"다.** 5년 11개월 무릴리스는 §4.3-2가 말한
"유지보수 상태"를 만족한다고 보기 어렵다. 다만 완화 요인이 세 가지 있다.

1. **Fernet 사양이 고정돼 있다.** 스펙이 움직이지 않으므로 기능 갱신 필요가 낮다
2. **코드가 작다**(클래스 10개) — §4.3-2가 요구한 "보안 검토가 가능한" 조건은 오히려
   충족한다. 전량 읽고 검토하는 것이 현실적이다
3. **§9-1로 Python 구현이 남는다** — 차분 검증 oracle을 영구히 쓸 수 있다

**이것은 privacy-gate가 단독 판정할 사항이 아니다.** §9 결정 3("Fernet JVM 호환
구현 승인 여부")이 사용자·리더 승인 항목으로 이미 잡혀 있으므로, 위 실측을 근거로
**리더에게 판단을 넘긴다.** 선택지는 §1.2의 1-a(이 라이브러리 채택 + 전량 코드 검토
조건)와 1-b(JDK primitive로 자체 조립 + 이 라이브러리를 교차 검증용으로만 사용)다.

**어느 쪽이든 이 spike가 확인한 것은 바뀌지 않는다 — JVM에서 Fernet 상호운용은
가능하고, 롤백 창의 역방향도 깨지지 않는다.** 남은 것은 구현체 선택이다.

### 4.7 AEAD(선택지 2)는 미검증

빈 DB·역방향 분석(§1.2)에서 선택지 2를 권고하지 않기로 결론이 났고, Fernet 경로가
실측으로 열렸으므로 **AES-GCM envelope 실측은 수행하지 않았다.** 리더가 선택지 2를
채택하기로 판단하면 별도 spike가 필요하다 — 특히 §1.2 ⑤(롤백 후 Python 쓰기 형식)의
설계 결정이 선행되어야 한다.
