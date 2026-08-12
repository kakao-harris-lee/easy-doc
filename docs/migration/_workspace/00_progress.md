# Kotlin 마이그레이션 진행 상태

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md`
**실행 모드:** 초기 실행 (2026-08-11 착수)
**갱신 규칙:** 에이전트는 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다.

> `충족`은 `예`/`아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 쓰지 않는다 — 게이트는 이분법이어야 판정된다.
> 근거가 비어 있는 `예`는 `아니오`로 취급한다.

---

## Phase 0 — 범위·계약 동결

계획 문서 §5 Phase 0. 원문 종료 조건: "Kotlin이 **기존 암호문**을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨."

**종료 조건 문구 정정 (2026-08-12, 리더).** §9-2가 "보존할 DB 없음"으로 확정되면서 **기존 암호문이 존재하지 않는다.** 계획 §4.3은 "기존 DB 본문이 Fernet 토큰이라 호환을 증명 못 하면 기존 문서를 못 읽는다"를 전제로 쓰였고 그 전제가 사라졌다. 실제로 남은 요구는 **역방향**이다 — 계획 §5 Phase 7이 관찰 기간(1~2주) 동안 롤백을 준비하라고 요구하므로, 절체 **후** Kotlin이 쓴 데이터를 롤백한 Python이 읽고 **이어서 쓸 수 있어야** 한다.

따라서 이 Phase의 실질 종료 조건은 다음으로 읽는다:

> **롤백 창에서 Python이 Kotlin 산출물을 읽고 이어서 쓸 수 있는 경로와, 문서 포팅 가능성이 확인됨.**

계획 문서 §5 Phase 0 본문도 같은 취지로 갱신할 것을 제안한다(문서 갱신은 별건).

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| `contracts/easy-doc-v1.yaml` 작성 | 예 | `contracts/easy-doc-v1.yaml` 작성 완료. 14 엔드포인트(제품 13 + `/health`)가 FastAPI 실제 노출 경로와 **차집합 양쪽 공집합**으로 일치. `openapi-spec-validator` → OK. 413·502/503·`detail` union·응답 헤더 5종·multipart 요청 본문·`status` enum·CORS·입력 상한 전부 수기 기입 | **미결 1건(U-1)**: 미처리 500 응답의 CORS 헤더를 Kotlin에서 재현할지 개선할지 (3자 대조 §7). 계약 파일에 `x-cors.x-known-limitation`·v2 후보 `V2-2`로 미결 명시 | 리더(U-1 판단) | contract-keeper |
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 아니오 | 목록·기준만 작성 (`00_contract-keeper_test-plan.md` — 엔드포인트별 세트 14 + 횡단 41종). **테스트 코드는 미구현**이며 실행도 하지 않았다 | 리더 지시로 Phase 0에서는 목록만 세웠다(Kotlin API가 Phase 3부터 생긴다). 추가로 **G-1: Python 기준선에도 없는 공백** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 어떤 테스트도 단언하지 않는다(계약상 10곳 중 2곳). Python에 먼저 채울지 Phase 3에서 양쪽 동시에 넣을지 판단 필요 | 리더(G-1 시점 판단) → Phase 3 kotlin-implementer | contract-keeper |
| FastAPI OpenAPI·계약 파일·React 타입 3자 대조 | 예 | `00_contract-keeper_three-way-diff.md`. 불일치 **21건 + 계획-코드 3건 + 미결 1건**. ①이 런타임과 **다른 값**을 말하는 곳 3건(422의 `input`/`ctx`, `loc` 타입, export의 `application/json`), 누락 6건, 느슨함 5건, 의도된 차이 6건. `DELETE /workspaces/{id}`가 React에 없는 것은 **의도된 차이**로 기록 | U-1(§7)만 미해결 | 리더(U-1 판단) | contract-keeper |
| 대상 DB와 보존할 파일럿 데이터 유무 확인 | 예 | 사용자 확인 (2026-08-12): **보존할 운영/파일럿 DB 없음. 빈 DB로 시작한다.** 이로써 계획 §7이 "변동 폭이 가장 크다"고 지목한 두 변수 중 하나(기존 암호문 호환)가 소멸했다 | - | - | leader |
| 범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python 제거 | 예 | 사용자 승인 (2026-08-12): **제품 런타임만 Kotlin화**(§9-1). Phase 9(오프라인 도구)는 착수하지 않는다. 골든셋 평가·벤치마크·수집·파일럿 리포트 도구는 Python으로 남아 **독립 검증 oracle** 역할을 유지한다 | - | - | leader |
| Fernet JVM 호환 spike | 예 | `00_privacy-gate_crypto-spike.md` §4. `com.macasaet.fernet:fernet-java8:1.5.0` / Temurin 21.0.4 / Gradle 9.1.0. **정방향 8/8**(한글·빈 값·긴 값·제어문자·변조·다른 키·garbage), **역방향 5/5**(`verify-crypto` 통과, `crypto-verify.verified.json` status: pass), **tamper 5/5**(version·timestamp·IV·ciphertext·HMAC 각 1비트 변조 전건 `StorageError` 거부, 무변조 대조군 정상). 즉흥 암호 구현은 하지 않았다 | **(1) 조달 유보** — 이 라이브러리는 최신 1.5.0이 **2020-09-26** 릴리스로 약 5년 11개월 무릴리스(`maven-metadata.xml`·jar `Last-Modified` 실측). §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 채택(코드 전량 검토 조건) vs JDK primitive 자체 조립 중 선택 필요 = **§9 결정 3**. **(2) 필수 조치 C** — 기본 Validator는 TTL 60초라 **유효 토큰 5건 전부 `TokenExpiredException`으로 실패**한다. 그대로 쓰면 업로드 60초 뒤 모든 문서가 안 읽힌다. Phase 4에서 TTL·maxClockSkew 명시적 무력화 + 60초 경과 토큰으로 회귀 테스트 필요. **(3)** AES-GCM(선택지 2)은 미검증 — 권고하지 않아 수행하지 않았다 | 리더(§9 결정 3) | privacy-gate |
| Argon2 PHC 검증 spike | 예 | `00_privacy-gate_crypto-spike.md` §2. `spring-security-crypto:6.4.2`(`Argon2PasswordEncoder(16,32,4,65536,3)`) + `bcprov-jdk18on:1.78.1`. 파라미터는 `app/services/auth.py:59`에서 직접 읽었고 salt 16B·hash 32B는 fixture PHC base64 길이에서 역산. **정방향 13/13**(한글·NFD 불일치 거부·legacy `m=8192,t=2,p=2` 검증·변조·비PHC 문자열이 예외 아닌 `false`), **역방향 4/4**(`app/services/auth.py::_HASHER`가 Kotlin 산출 PHC를 전건 검증, `needs_rehash=false`, 틀린 비밀번호 거부, prefix `$argon2id$v=19$m=65536,t=3,p=4$` 동일) | **필수 조치 A** — 재해시 판정이 갈린다. Python `check_needs_rehash`는 **전체 파라미터 동등성**, Spring `upgradeEncoding`은 **memory·iterations의 "미만"만** 본다. 자체 탐침 7건 중 5건 불일치(parallelism만 다름·더 강한 memory·더 강한 iterations·hash_len·salt_len에서 Python `true` / Kotlin `false`). **공식 fixture 14건으로는 드러나지 않는다.** 지금은 무해하나(살아 있는 해시가 전부 현재 파라미터) 파라미터를 바꾸는 날 **이관이 조용히 멈춘다**. Phase 3에서 전체 동등성 판정 함수로 교체 + 탐침 7건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| JWT 양방향 호환 spike | 예 | `00_privacy-gate_crypto-spike.md` §3. **정방향 17/17을 두 라이브러리에서 각각**(`nimbus-jose-jwt:9.41.2`, `auth0 java-jwt:4.4.0`) — alg=none·RS256 헤더 혼동·서명 위조·페이로드 변조·`sub`/`exp`/`typ` 누락·`typ` 불일치·비UUID sub·32B 시크릿 통과·31B `configuration_error` 전건 일치. **`exp` 경계 질문 해소**: skew 0에서 두 라이브러리 모두 `exp <= now`를 만료로 봐 PyJWT와 같다(`exp-2…exp+2` 훑어 예외 타입까지 확인 — 결과만 보고 오독하지 않도록 메커니즘 대조). **역방향 4/4**(`verify-jwt` 통과, subject 2종 × 유효/만료, `jwt-verify.verified.json` status: pass) | **필수 조치 B** — 경계가 맞은 것은 skew를 0으로 **명시했기 때문**이다. `DefaultJWTClaimsVerifier` 기본 `maxClockSkew`는 **60초**라 기본값으로 두면 만료 토큰이 `+59s`까지 ACCEPT돼 `jwt-exp-boundary-exact` fixture에서 실패한다. Spring Security `NimbusJwtDecoder`의 `JwtTimestampValidator`도 기본 60초라 같은 함정. auth0는 기본 leeway 0이라 무해. Phase 3에서 skew 0 명시 + 경계 fixture 2건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| DOCX/PDF/HWPX 라이브러리 spike | 예 | `00_kotlin-implementer_doc-spike.md`. **§4.5가 경고한 DOCX 위험은 해소됨** — POI를 usermodel이 아니라 OOXML DOM 순회로 쓰면 Python `_docx_blocks`와 **블록 리스트가 완전 일치**한다. 기존 fixture 6개 + 합성 fixture 4개 전부 Python 산출값 일치(거부 메시지 문자열까지). 동등성 6항목 전부 확인(표 제자리·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글). HWPX: DTD/UTF-16 DTD/XXE 차단, 1GiB zip bomb을 힙 256MB에서 거부(힙 증가 0MB), 자체 round-trip·mimetype STORED 첫 항목·생성 결정성 PASS, Python↔Kotlin 패키지 교차 읽기 PASS. 검증 조합: Java 21.0.4 / Gradle 9.1.0 / Kotlin 2.2.0 / POI 5.4.1 / PDFBox 3.0.5 / commons-compress 1.27.1. `uv run pytest tests/ingest -q` 57 passed로 Python 기준선 무손상 | **가능성은 확인됐고 남은 것은 Phase 4 결정·구현이다.** (1) POI 산출 DOCX에 `styles.xml`/`theme` 부재 — Heading 1 서식이 사라짐, 템플릿 정책 결정 필요 (2) zip 컨테이너 바이트는 Python과 동일해질 수 없음(실측 `java=434B` vs `python=348B`) → parity fixture를 바이트 해시로 잡으면 안 됨, `parity-verifier` 합의 필요 (3) StAX DTD 판정을 예외 **메시지**로 하면 로케일 의존 — `DTD` 이벤트 직접 처리로 바꿔야 함 (4) 위조 크기 zip의 사용자 메시지가 Python과 갈림(`손상되었습니다` vs `너무 큽니다`) (5) **미검증**: 실제 한컴/Word 저장 파일, 실제 공공기관 PDF의 pypdf↔PDFBox 동등성, `MAX_EXTRACTED_CHARS`·10MB 경계, 암호 PDF/DOCX 실파일, Spring Boot BOM 적용 후 버전 재정렬 | - | kotlin-implementer |
| 리뷰 게이트 Critical 0건 | 아니오 | 1회차 실행 완료 — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건 (정본은 `_cross.md`). **지적된 Critical 2건은 코드로 닫혔다**: X-1(proof 위조) → `check_external`이 proof 파일을 읽지 않고 검증기를 in-process 실행 후 증거를 덮어씀 / X-2·X-11(fixture 출처) → `provenance_problems()`가 매 비교마다 정본 생성기를 재실행해 대조, `runtime` 검사 추가. 실증 14종 | **재리뷰를 돌리지 않았다.** 수정 자체가 검증받지 않았으므로 이 행은 닫지 않는다. 상충-2(심각도 척도)는 리더가 판정해 반영 완료 — 사건뿐 아니라 **탐지 장치의 무력화도 Critical**로 세되, 심각도와 착수 차단은 별개 축이고 마감은 그 게이트의 첫 실사용 시점이다 | migration-reviewer | leader |

### Phase 0에서 사용자 승인이 필요한 다섯 결정 (계획 §9)

| # | 결정 | 상태 |
|---|---|---|
| 1 | 목표가 "제품 런타임 Kotlin화"인지 "오프라인 도구 포함 Python 완전 제거"인지 | **승인 (2026-08-12)** — 제품 런타임만 Kotlin화. Phase 9 미착수, Python 도구는 독립 oracle로 존치 |
| 2 | 파일럿/보존 대상 DB가 있는지, 유지보수 창을 쓸 수 있는지 | **승인 (2026-08-12)** — 보존 대상 DB 없음. 빈 DB로 시작 |
| 3 | Fernet JVM 호환 구현 승인 여부와 실패 시 재암호화 방식 | **승인 (2026-08-12)** — **JDK primitive로 직접 구현**(~150줄). 아래 상세 |
| 4 | PostgreSQL 작업 큐로 전환하며 Redis를 최종 제거할지 | 미승인 — Phase 5 착수 전까지 결정 |
| 5 | 시각 UI 개편을 이번 전환과 분리하는 원칙 승인 | 미승인 — 계획 §4.1이 이미 분리를 전제하므로 기본값대로 진행하되, Phase 6 착수 전 확인 |

#### 결정 3 상세 — Fernet 직접 구현

**채택하지 않은 것과 이유** (`00_privacy-gate_crypto-spike.md` + JVM Fernet 생태계 조사):

- `com.macasaet.fernet:fernet-java8:1.5.0` — 정방향 8/8·역방향 5/5·tamper 5/5로 **기능은 통과했으나**, 최신 릴리스가 2020-09-26으로 약 5년 11개월 무릴리스라 계획 §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 기본 Validator TTL 60초를 무력화해야 하는데 이는 라이브러리 설계와 반대로 쓰는 것이고, 나중에 누군가 TTL을 켜면 1초 경계 불일치가 되살아난다
- `dev.ercan:fernet:1.0.0` — Python `cryptography`와 **시맨틱이 정확히 일치**(no-TTL 경로에 타임스탬프 검사 없음, 경계·60초 skew 규칙 동일, `MessageDigest.isEqual` 사용)하나 2026-07-26 릴리스로 2주 됐고 star 0·저자 1명·보안 정책 없음·fuzzing 없음. 시민 문서를 다루는 시스템에서 검증되지 않은 단독 저자 암호 라이브러리는 다른 종류의 위험이다
- `io.github.atkawa7:fernet` — **채택 금지.** HMAC 검증에 `Arrays.equals`를 써 첫 불일치에 단락되는 타이밍 사이드채널이 있고, 바로 위 주석은 "using a constant-time comparison function"이라고 적혀 있다. `<scm>` URL이 404라 신고 경로도 없다
- `core-dragonby7k/fernet-java8` — **무단 복제본**(fork 아님, 원본 CI 배지 유지). 검색으로 흘러들지 않도록 기록
- **Kotlin-native 구현은 존재하지 않는다** — 이름이 그런 저장소 하나는 전체 구현이 `TODO("Not implemented yet.")`이고 키 생성기가 urlsafe가 아닌 표준 Base64를 써 스펙에도 어긋난다

**§4.3-4 "즉흥 구현 금지" 해석**: 그 조항은 *호환 실패 시 대안으로 새 암호 방식을 급조하는 것*을 막는다. 여기서는 **공개 스펙(fernet/spec)이 있고, 교차 런타임 fixture로 검증하며, Python oracle이 §9-1로 존치**한다. 즉 "검증 없는 자작"이 아니라 "스펙 구현 + 기계 검증"이므로 조항 위반이 아니다. 다만 이 해석은 리뷰 게이트에서 다시 도전받아야 하며, 구현 시 **JVM Fernet 생태계 전체가 CVE 0건**이라는 사실이 면죄부가 아님을 유의한다.

**구현 요건** (Phase 4 착수 전 확정):
- 토큰 형식: version(0x80)·timestamp(8B BE)·IV(16B)·ciphertext·HMAC-SHA256(32B), Base64URL
- **HMAC 검증을 복호화보다 먼저**, `MessageDigest.isEqual`로 상수 시간 비교
- **TTL 검사 없음** — Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. 이것이 `fernet-java8`에서 실패했던 바로 그 지점이다
- 교차 런타임 fixture(`parity/fixtures/crypto`)로 정방향·역방향·tamper 전건 검증. **60초 경과 토큰을 반드시 포함** — 갓 만든 토큰으로만 테스트하면 TTL 결함이 통과해 버린다

#### 결정 4·5는 아직 열려 있다

Phase 1 착수를 막지는 않는다. 4는 Phase 5(작업 큐)에서, 5는 Phase 6(React 통합)에서 처음 구속력을 갖는다.

---

## Phase 0 종료 판정 — **조건부 종료** (2026-08-12, 리더 + 사용자 승인)

종료 조건 10행 중 **8행 충족, 2행 미충족**. 오케스트레이터 규칙은 "이전 Phase의 종료 조건 행이 전부 `충족 = 예`가 아니면 다음 Phase 에이전트를 호출하지 않는다"이고, 예외는 "미충족인데 진행해야 할 사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 남긴다"이다. **사용자 승인을 받아 Phase 1에 착수한다.**

### 미충족으로 남기는 2행과 닫는 시점

| 미충족 행 | 왜 지금 못 닫는가 | 언제 닫는가 |
|---|---|---|
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 계약 테스트는 HTTP 경계에서 도는데 **Kotlin API가 아직 없다.** 지금 Python에만 채우면 절반이고, 그 절반이 "계약이 검증됐다"는 착각을 준다 | **Phase 3** — `/auth/*`·`/workspaces/*`가 Kotlin에 생기는 시점. 목록과 기준은 `00_contract-keeper_test-plan.md`에 이미 있다 |
| 리뷰 게이트 Critical 0건 | 지적된 Critical 2건은 코드로 닫았으나 **그 수정 자체가 리뷰를 받지 않았다.** 이번 세션에서 "고쳤다고 보고한 직후 codex가 그 수정의 결함을 잡은" 일이 세 번 있었다 | **Phase 1 종료 시** — Kotlin 골격이라는 실제 코드가 생긴 뒤 3단계 게이트를 돌린다. 문서·스크립트만 있는 지금보다 그때 리뷰가 값이 크다 |

### 착수 전 판단이 필요했으나 Phase 1을 막지 않는 항목

- **U-1** — 미처리 500 응답의 CORS 헤더를 Kotlin에서 재현할지 개선할지. Python은 미들웨어 순서 때문에 구조적으로 못 붙이고 **React가 이미 그 동작(`status = 0` → "서버에 연결하지 못했습니다")에 의존한다.** 선택에 따라 화면 분기가 달라지므로 계약 소유자 단독 결정 사항이 아니다. **Phase 3(오류 매핑) 착수 전까지** 정한다. 그전까지 `kotlin-implementer`는 이 경로를 구현하지 않는다
- **G-1** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 **Python 기준선에서도 어떤 테스트가 단언하지 않는다.** 계약상 10곳 중 2곳이 회귀 방지 없이 구현에만 존재한다. Phase 3에서 Kotlin·Python 양쪽에 동시에 넣는다
- **DOCX 내보내기 템플릿** — POI 산출물에 `styles.xml`/`theme`가 없어 Heading 1 서식이 사라진다. 템플릿을 저장소에 동봉할지 **Phase 4 착수 전** 결정
- **내보내기 parity 비교 기준** — zip 컨테이너 바이트는 Python과 동일해질 수 없다(실측 java 434B vs python 348B). 바이트 해시가 아니라 정규화 텍스트로 비교하도록 `parity-verifier`와 합의 필요. **Phase 4 착수 전**

### Phase 1로 넘기는 필수 조치 (spike에서 확정된 구현 지침)

| # | 내용 | 마감 |
|---|---|---|
| A | Argon2 재해시 판정을 **전체 파라미터 동등성**으로 구현. Spring `upgradeEncoding`은 memory·iterations의 "미만"만 봐 Python `check_needs_rehash`와 갈린다. 탐침 7건을 회귀로 고정 | Phase 3 |
| B | JWT 검증에 **clock skew 0을 명시**. `DefaultJWTClaimsVerifier`·`NimbusJwtDecoder` 기본값이 60초라 만료 토큰이 +59초까지 통과한다. 경계 fixture 2건 회귀 고정 | Phase 3 |
| C | Fernet 구현에 **TTL 검사를 넣지 않는다**. Python이 `ttl=None`으로 쓰는 경로와 같아야 한다. **60초 경과 토큰**을 fixture에 반드시 포함 | Phase 4 |
| D | `encryption_scheme` 컬럼을 **Phase 1 첫 Flyway에 additive로** 추가(기본값 `'fernet-v1'`). 관찰 기간엔 `fernet-v1` 고정, AEAD 전환은 Phase 8 이후 단일 런타임 상태에서 별건 | Phase 1 |
| E | parity 게이트의 마지막 구멍 — **Kotlin 테스트 하네스가 `parity/actual/{도메인}/*.json`을 쓰도록 CI에 배선.** 지금은 "산출물을 Kotlin이 만들었다"는 보장이 없다(fixture가 키·시크릿을 공개하므로 Python으로도 같은 파일을 만들 수 있다) | Phase 1 |

---

## Phase 1 — Kotlin 골격과 CI

계획 문서 §5 Phase 1. 원문 종료 조건: "**빈 DB와 기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health`가 응답함.**"

작업 항목 6개를 종료 조건 행으로 쪼갰다. 상세 근거·명령·출력은 `01_kotlin-implementer_skeleton.md`.

| 종료 조건 | 충족 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---|---|---|---|---|
| `backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5개 모듈, 의존 방향) | 예 | `core`/`application`/`infrastructure`/`api`/`worker` 생성. `api`·`worker` 는 `infrastructure` 를 **`runtimeOnly`** 로만 의존해 JDBC·(Phase 5) LLM SDK 타입이 컴파일 시점에 보이지 않는다. `application` 은 `infrastructure` 를 의존하지 않는다. `api`↔`worker` 상호 의존 없음. **`core` 의 Spring·DB 비의존을 `CoreModuleBoundaryTest` 가 실행으로 확인**(7개 클래스 부재: `ApplicationContext`·`SpringApplication`·`JdbcClient`·`Flyway`·`org.postgresql.Driver`·Jackson 2/3 `ObjectMapper`) | `application` 본 소스는 비어 있다(경계만 세움, 유스케이스는 Phase 3~5). 계약은 `application/README.md` | - | kotlin-implementer |
| toolchain·dependency locking·version catalog·ktlint/detekt·테스트 설정 | 예 | Java 21 toolchain(`jvmToolchain(21)`), `allWarningsAsErrors=true`. 락파일 6개 커밋(모듈 5 + settings, 792줄) — `clean build` 가 락 갱신 없이 성공. catalog 가 유일한 버전 선언 지점이고 **BOM 밖에서 버전을 고르는 것은 Kotlin 플러그인·ktlint·detekt 셋뿐**. ktlintCheck·detekt 모두 통과(위반 0). **locking 이 실제 드리프트를 잡았다** — kotlinx-serialization 1.11.0이 테스트 클래스패스 stdlib 만 2.2.21→2.3.20으로 올린 것을 발견해 BOM(1.9.0)에 넘겼다 | 기본값을 벗어난 규칙 2건(ktlint `class-signature` 임계 1→2, detekt `SpreadOperator` off) — 사유는 산출물 §2.4. detekt 1.23.8은 Kotlin 1.9 파서 내장(2.x는 alpha뿐이라 미채택) | - | kotlin-implementer |
| `/health` 가 계약대로 응답 (상수 `{"status":"ok"}`) | 예 | `HealthContractTest` 4건 — 200·`{"status":"ok"}`(strict)·인증 불필요·**캐시 금지 헤더 없음**·DataSource 없이도 200(=의존 서비스 진단 안 함). compose 실측: `HTTP/1.1 200 / Content-Type: application/json / {"status":"ok"}`. Actuator 미도입(계약 14 엔드포인트 밖 경로를 노출하지 않으려고) | - | - | kotlin-implementer |
| 설정 바인딩·구조화 로그·비밀값 마스킹 | 예 | `EasyDocProperties`(`app/config.py` 포팅) + `Secret` 타입(`SecretStr` 대응) + `SecretConverter`. `SecretTest` 7건 — `toString`·문자열 템플릿·**데이터 클래스 필드로 들어가도** 평문 미노출, 값 비의존 `hashCode`, 상수 시간 비교. 구조화 로그는 Dockerfile `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 로 ECS JSON — compose 로그로 확인. `server.error.include-*` 를 전부 `never`/false 로 꺼 스택·입력값이 응답에 실리지 않게 했다 | 설정 값이 실제로 **쓰이는** 곳은 아직 없다(`/health` 는 설정을 읽지 않는다). 사용·검증은 각 기능 Phase | - | kotlin-implementer |
| Testcontainers PostgreSQL + Flyway baseline 구축 | 예 | `V1__python_schema_baseline.sql` 을 **Alembic 을 실제로 돌려**(`uv run alembic upgrade head` → `alembic_version=0006`) 뽑은 스키마로 작성. 지문 대조 **전건 일치**(extension 1 · table 4 · column 32(서수 포함) · constraint 11 · index 11). 회귀는 `PythonSchemaBaselineTest` 4건 + `FlywayBaselineGuardTest` 4건. `baseline-on-migrate=true` 를 쓰지 않고 **지문이 일치할 때만** baseline 하는 `FlywayBaselineGuard` 를 만들었다(§4.2-4). `alembic_version` 은 만들지도 읽지도 쓰지도 않는다(§4.2-7) | Testcontainers 컨테이너가 모듈마다 따로 뜬다(`withReuse` 미적용). 로컬 전체 16초라 지금은 무해 | - | kotlin-implementer |
| **필수 조치 D** — `encryption_scheme` additive 추가 | 예 | **V2에 배치**(V1 아님). 근거: ① V1은 "Python 스키마 재현"이라 신규 컬럼이 들어가면 지문 대조가 성립하지 않는다 ② **결정적** — baseline 은 V1을 건너뛰므로 V1에 넣으면 기존 Alembic DB에서 컬럼이 영원히 안 생긴다 ③ §4.2-5가 "Kotlin 전용 변경은 V2부터"라고 명시. 대상 `documents`·`conversions`, 기본값 `'fernet-v1'`, CHECK 제약 동반. `V2 는 encryption_scheme 을 additive 로 추가한다`·`Python 컬럼만 지정한 INSERT 가 성공한다` 테스트 통과 | 관찰 기간 내내 `fernet-v1` 고정. AEAD 전환은 Phase 8 이후 별건 | - | kotlin-implementer |
| Dockerfile·compose Kotlin profile 추가 (기존 Python 서비스 유지) | 예 | `backend-kotlin/Dockerfile`(멀티스테이지, api·worker bootJar 한 이미지). compose에 `kotlin-migrate`·`kotlin-api`(8100)·`kotlin-worker` 를 `profiles:["kotlin"]` 뒤에 추가 — **기존 Python 서비스 정의를 하나도 바꾸지 않았고** 기본 `docker compose up` 동작이 그대로다. 실측: 두 스택 동시 기동, Kotlin 8100·Python 8000 양쪽 `/health` 200. `kotlin-migrate` exit 0. §4.2-6대로 **DB를 갈랐다**(`easydoc` / `easydoc_kotlin`) — Python DB에 `flyway_schema_history` 0개 확인 | `easydoc_kotlin` 은 기존 볼륨에서 자동 생성되지 않는다(initdb 는 빈 데이터 디렉터리에서만 실행) — 수동 절차 문서화. compose 실행 중 **worker 즉시 종료**를 발견해 `spring.main.keep-alive: true` 로 고쳤다(산출물 §9.5) | - | kotlin-implementer |
| CI에 Kotlin build/test 추가 + 기존 Python/React gate 유지 | 아니오 | `.github/workflows/ci.yml` 에 `kotlin` 잡 추가(9 steps: setup-java 21 · setup-gradle · setup-uv · 이미지 pull · `./gradlew build` · `parityHarness` · 배선 확인 · parity 비교). **기존 `quality`(8 steps)·`frontend`(6 steps) 잡을 건드리지 않았다** — 로컬에서 `ruff`·`ruff format`·`mypy`·`pytest`(820 passed, 68 skipped) 전부 통과 확인 | **CI가 실제 GitHub Actions 에서 도는 것을 확인하지 못했다.** YAML 파싱과 로컬 동등 명령만 검증했다. `gradle/actions/setup-gradle@v4`·러너 Docker 데몬 위 Testcontainers 는 **첫 push 에서 처음 검증된다**. 이 행은 그때 닫는다 | 첫 PR 실행 | kotlin-implementer |
| **필수 조치 E** — Kotlin 테스트가 `parity/actual/` 을 쓰도록 CI 배선 | 아니오 | 배선 구조 완성: `ParityActual`(경로를 시스템 프로퍼티로만 받고 **없으면 던진다**) + `parityHarness` Gradle 태스크(`@Tag("parity")` 만, 저장소 루트로 출력) + 일반 `test` 는 모듈 `build/` 로 격리 + CI 3단계(생성 → 존재·`runtime:kotlin` 확인 → 비교). `ParityActualTest` 5건이 산출물 형식·경로·한글 비이스케이프·거부 조건을 고정. 실측 산출물 `parity/_harness-selfcheck/kotlin.json`(`runtime:kotlin`, JVM 21.0.4 Temurin, Kotlin 2.2.21) | **채우지 못한 것**: `parity/fixtures/` 자체가 없어 `compare_parity.py` 를 **한 번도 돌리지 못했다**(Phase 2). 도메인 산출물은 Phase 2(8개)·Phase 3(jwt·argon2)·Phase 4(crypto). **CI 비교 단계가 종료 코드 2를 통과 처리한다 — Phase 4 종료 시 이 완화를 제거해야 한다** | Phase 2 (fixture 생성) | kotlin-implementer |
| 종료 조건: 빈 DB와 기존 schema snapshot 양쪽에서 기동 + `/health` 응답 | 예 | `ApiStartupOnEmptyDatabaseTest` 2건 + `ApiStartupOnPythonSnapshotTest` 2건. `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient` 로 **실제 소켓**을 친다. 빈 DB → `flyway_schema_history=[1,2]`, 200 `{"status":"ok"}`. 기존 스냅샷(Alembic 0006 상태) → `[1(BASELINE), 2(SQL)]`, `alembic_version=0006` 불변, 200 `{"status":"ok"}`. compose 실측으로도 재확인(산출물 §9) | - | - | kotlin-implementer |

**전체 테스트**: `./gradlew clean build` → **BUILD SUCCESSFUL, tests=48 failures=0** (core 19 · infrastructure 8 · api 18 · worker 3).

### 확정한 버전 조합 (Boot BOM 적용 후)

| 항목 | 값 | spike(§Phase 0) 대비 |
|---|---|---|
| JDK / Gradle | Temurin 21.0.4 / 9.1.0 | 동일 |
| Kotlin | **2.2.21** | 2.2.0 → BOM 정렬 (변경) |
| Spring Boot | **4.0.7** | 신규 확정 |
| Spring Framework / Jackson / JUnit / Testcontainers / Flyway / PG 드라이버 | 7.0.8 / **3.1.4** / **6.0.3** / **2.0.5** / 11.14.1 / 42.7.11 | 전부 BOM 관리 |
| ktlint(플러그인/CLI) / detekt | 14.2.0 / 1.8.0 / 1.23.8 | 신규 |

Boot 4가 spike 이후 바꾼 좌표(실측): `FlywayMigrationStrategy` → `spring-boot-starter-flyway` /
`org.springframework.boot.flyway.autoconfigure`, `@WebMvcTest` → `spring-boot-starter-webmvc-test` /
`org.springframework.boot.webmvc.test.autoconfigure`, Testcontainers → `org.testcontainers:testcontainers-postgresql`.
**Jackson 3.1.4**(패키지 `tools.jackson`)가 관리 버전이라 Phase 2 이후 JSON 처리 시 주의가 필요하다.

### 리더 판단이 필요한 항목

| # | 내용 | 마감 |
|---|---|---|
| P1-1 | **Spring Boot 4.0.7 vs 4.1.0.** 계획 §3.1은 "4.1 계열 후보"라 적었으나 4.0.7을 골랐다. 두 계열 차이는 Kotlin(2.2.21 vs 2.3.21)과 Flyway(11.14.1 vs 12.4.0)뿐이고 나머지는 동일하다. 4.0.7을 고른 이유는 ① Phase 0 문서 spike가 Kotlin **2.2.0** 위에서 POI·PDFBox·commons-compress 를 통과시켰고 2.2.21이 같은 마이너 계열이라 그 결과를 승계할 수 있다 ② 4.0 계열은 패치 7회 누적, 4.1.0은 GA 직후. **4.1.0으로 올릴지 / 계획 문서 문구를 정정할지** 판단 필요. 되돌리는 비용은 작다(catalog 두 줄 + 재빌드) | Phase 2 착수 전 |
| P1-2 | CI parity 비교 단계가 **종료 코드 2를 통과 처리**한다. 지금은 역방향 산출물이 없어 정상이지만 Phase 4 종료 시 이 완화를 제거해야 게이트가 미검증 케이스를 잡는다 | Phase 4 종료 시 |

### Phase 1에서 손대지 않은 것 (지시대로 보류)

- **U-1**(미처리 500 응답의 CORS 헤더) — 리더 판단 전이라 **CORS 자체를 설정하지 않았다**
- 검증 실패(422) 응답의 `detail` **배열** 형태 — 요청 본문을 받는 엔드포인트가 없어 재현 대상이 없다. Phase 3에서 옮길 때 `rejectedValue`(비밀번호 유출 경로)를 반드시 걷어낼 것
- `GlobalExceptionHandler` 의 HTTP 경계 검증 — 도메인 예외를 던지는 엔드포인트가 없어 핸들러를 직접 호출했다(`ErrorContractTest` 10건). HTTP 경계는 Phase 3 contract test
- `app/`·`tests/`·`frontend/`·`scripts/`·`.claude/`·`contracts/` — 읽기만 했다

---

---

## 아직 돌리지 않은 검증 게이트 (계획 §6)

| 게이트 | 상태 |
|---|---|
| Build (Gradle, TypeScript) | **Gradle 실행됨** (Phase 1) — `./gradlew clean build` BUILD SUCCESSFUL (컴파일 + ktlintCheck + detekt + test). TypeScript 는 기존 `frontend` 잡이 그대로 담당 |
| Unit (core, application, React) | **부분 실행** (Phase 1) — core 19건(모듈 경계 7 · Secret 7 · parity 하네스 5) 통과. **도메인 로직은 아직 없다**(마스킹·스타일 규칙 등은 Phase 2). `application` 은 본 소스가 없어 테스트도 없다. React 는 기존 `frontend` 잡 |
| Contract (14 endpoints) | 미실행 — 계약 파일은 **작성됨**(`contracts/easy-doc-v1.yaml`)이나 contract test 미구현. 실행은 Kotlin API가 생기는 Phase 3부터 (`00_contract-keeper_test-plan.md` §5) |
| DB (Testcontainers) | **실행됨** (Phase 1) — pgvector/pgvector:pg16 컨테이너로 8건. V1↔Alembic 지문 대조, V2 additive, baseline 가드 4갈래, 빈 DB·기존 스냅샷 기동. **repository·트랜잭션·SKIP LOCKED 는 아직 없다**(Phase 3·5) |
| Crypto (Python ↔ Kotlin) | 미실행 — fixture **생성기**가 11개 도메인을 지원할 뿐, **`parity/fixtures/` 산출물은 저장소에 존재하지 않는다**(`parity/` 디렉터리 자체가 없음). Kotlin 측도 부재 |
| Document (docx/pdf/hwpx/txt) | 미실행 |
| Worker (lease/retry/crash) | 미실행 |
| Quality (골든셋) | 미실행 |
| Security (소유권·로그·캐시) | 미실행 |
| E2E (compose + browser) | **compose 부분 실행** (Phase 1) — Kotlin api·worker·migrate 3서비스가 Python 스택과 동시 기동, `/health` 200 확인. **browser·업무 흐름은 미실행**(Phase 6) |
| Ops (cutover/rollback) | 미실행 |

**돌리지 않은 게이트를 통과한 것처럼 보고하지 않는다.**

---

## 착수 전 정리된 선행 작업 (하네스 구축 중 확인·수정)

Phase 0 착수 전에 하네스 구축 과정에서 발견해 처리한 항목이다. 마이그레이션 Phase 자체는 아니지만 기준선에 영향을 주므로 남긴다.

| 항목 | 내용 | 상태 |
|---|---|---|
| 계약 사실 정정 | 계획 §2.2에 없는 **413**(10MB 초과) 실재. 오류 본문 `detail`이 문자열/객체배열 **union**. 401에 `WWW-Authenticate: Bearer`. 엔드포인트는 제품 13 + `/health` = 14 | 반영됨 (`api-contract-freeze`) |
| 502/503 구분 | 큐 **등록 실패** = 502(`QueueUnavailableError`), 큐/설정 **미배선** = 503(`ConfigurationError`, `app/api/deps.py`) | 반영됨 |
| `PUT /conversions/{id}` 캐시 헤더 누락 | GET과 같은 스키마라 `masked_items[].original`에 개인정보가 실리는데 `no-store`/`nosniff` 부재 | **완료** — 커밋 `0fafac7`. 회귀 테스트 `tests/api/test_documents.py::test_검수_저장_응답은_캐시하지_않는다` |
| `/auth` 3종 캐시 헤더 누락 | `POST /auth/signup`·`POST /auth/login`·`GET /auth/me`가 `PRIVATE_RESPONSE_HEADERS`를 import조차 하지 않았음. 로그인 응답 본문은 Bearer 토큰 자체, 나머지 둘은 이메일 | **완료** — 커밋 `0fafac7`(같은 커밋). 회귀 테스트 3건 `tests/api/test_auth.py::test_{가입,로그인,내_정보}_응답은_캐시하지_않는다` |
| 캐시 금지 헤더 대상 범위 | 위 두 건으로 대상이 **6개 → 10개**로 늘었다 (documents 4 · workspaces 3 · auth 3). 계약 스킬 §2.5가 정본이고 §1 표는 포인터 표시만 둔다 | **완료** — 코드 확인: `grep -rn "headers.update(PRIVATE_RESPONSE_HEADERS)\|\*\*PRIVATE_RESPONSE_HEADERS" app/api/ \| wc -l` = 10 |
| parity 게이트 공백 | crypto 도메인이 Fernet만 검증. JWT·Argon2 fixture 부재 상태로 Crypto 게이트가 닫혔음 | 수정됨 — 커밋 `e88db3e`. 생성기에 jwt 18건·argon2 14건 추가 (11 도메인). **fixture 산출물은 아직 없음** |
| parity 게이트 우회 | 도메인 디렉터리를 통째로 빼면 "전건 일치"로 통과 | **완료** — 커밋 `e88db3e`. `compare_parity.py`의 `EXPECTED_DOMAINS` 검사 + 도메인 누락 시 exit 1 |
| 리뷰 게이트 1회차 | Phase 0 착수 **전** 점검으로 codex·Claude 독립 리뷰 + 교차 종합을 실행 | **완료** — `reviews/00_pre-phase0_{codex-reviewer,migration-reviewer,cross}.md` 3건. 교차 결과 **합의 9건 · codex 단독 4건 · Claude 단독 23건 · 상충 2건**. 정본은 `_cross.md` |
| 계약 스킬 §1↔§2.5 불일치 | §2.5는 헤더 대상 10개인데 §1 표에는 8개만 표기(`GET /conversions/{id}/export`·`PATCH /workspaces/{id}` 누락). §1만 보고 계약을 쓰면 두 곳이 헤더 요구 없이 동결됨 (X-14) | **완료** — 코드 기준으로 §1을 §2.5에 맞추고, §2.5를 정본으로 선언 |
| 오류 응답 캐시 헤더 | 오류 경로(`app/api/errors.py`)에 헤더를 붙일지 미결 (X-15) | **완료 — 현행 유지 판정**(붙이지 않음). 근거: 오류 본문에 개인정보 없음을 실측 확인. `api-contract-freeze` §2.7 해결 3에 전제 파기 조건과 함께 기록 |
| `status` 필드 넓이 | 백엔드 Pydantic `str` vs React 4값 리터럴 union. OpenAPI 생성 타입으로 그냥 교체하면 타입 안전성 퇴보 | **완료** — `contracts/easy-doc-v1.yaml`의 `components/schemas/ConversionStatus`에 enum 4값으로 고정. **Phase 6 타입 생성은 FastAPI 산출물이 아니라 이 계약 파일에서 한다**(3자 대조 D-1) |
