# Kotlin 마이그레이션 진행 상태

**기준 문서:** `docs/plans/2026-08-11-kotlin-react-migration.md`
**실행 모드:** 초기 실행 (2026-08-11 착수)
**갱신 규칙:** 에이전트는 자기가 담당한 행만 고치고 `마지막 갱신 주체`에 자기 이름을 적는다. 다른 에이전트의 행은 건드리지 않는다.

> `충족`은 `예`/`아니오` 둘 중 하나만 쓴다. `진행 중`·`대체로`는 쓰지 않는다 — 게이트는 이분법이어야 판정된다.
> 근거가 비어 있는 `예`는 `아니오`로 취급한다.

---

## 재개발 전환 (2026-08-12 2차) — 아래 역사 행 중 일부가 무효화됨

**사용자 결정 + 리더 판정.** 방향이 **전환(포팅)에서 재개발(rebuild)**로 바뀌었다. Python은 폐기 대상이다. 근거·상세는 계획 `docs/plans/2026-08-11-kotlin-react-migration.md`의 "2026-08-12 (2차)" 변경 이력. 이 파일의 아래 역사 행은 **고치지 않는다**(파일 규약) — 대신 여기서 무엇이 무효화됐는지 못 박는다.

**호환성 요구 소멸로 무효화된 행(역사 참고로만 남긴다 — 게이트로 쓰지 말 것):**

- **Phase 0 「Fernet JVM 호환 spike」 · 「결정 3 상세 — Fernet 직접 구현」 전체 · 필수조치 C(Fernet TTL·60초 토큰)** — 롤백이 없어 Fernet 자체가 불필요. **표준 AEAD 신규 구현**(계획 §4.3). crypto spike의 역방향·tamper **호환** 검증도 근거 소멸.
- **Phase 0 결정표 결정 3(Fernet 직접 구현)** → **무효.** 결정 2(보존 DB 없음)는 유효하되 그 함의가 "재암호화 창 불필요"에서 "**롤백·호환성 자체가 소멸**"로 확장됐다. 결정 1의 "Python 도구는 독립 oracle로 존치"는 "**한시 존치 후 이식/폐기**"로 바뀐다(계획 Phase 9 개정).
- **JWT·Argon2의 「양방향」·「역방향」·「기존 PHC 그대로 검증」 프레이밍** — Python이 Kotlin 산출물을 읽을 요구가 없다. crypto·jwt·argon2 parity의 `compat` 판정 모드 근거 소멸.
- **검증 게이트 표 「Crypto (Python ↔ Kotlin)」 → 「Kotlin 단독」**, **「Ops (cutover/rollback)」 → 「첫 배포·파일럿 관찰(롤백 없음)」**.
- **Phase 0 「종료 조건 문구 정정」의 '역방향/롤백 창' 읽기(37~45행)** — 절체 후 Python이 이어 쓸 요구가 없다(롤백 소멸). Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다: **표준 AEAD·Argon2·JWT 구현 경로(round-trip·변조 거부·필수조치 A·B — migration-safety-gate I-7·I-8·I-9) + 문서 포팅 가능성**. "기존 암호문 읽기"·"역방향 이어쓰기"는 종료 조건 아님.
- **「기준 전환 재판정」 §의 `[호환]` 근거 행(아래)** — spike 행이 '예'로 남는 근거는 이제 **가능성 확인 + 필수조치 A·B(migration-safety-gate I-7·I-8·I-9)**이지 "롤백 창 값 동일성"이 아니다. 판정(예)은 유지, 근거만 재기술.

**무효화되지 '않는' 것(혼동 주의 — 순수 정확성 문제라 그대로 유효):**

- **필수조치 A(Argon2 재해시 전체 파라미터 동등성)·B(JWT clock skew 0 경계)** — 이 둘은 *호환*이 아니라 *정확성* 문제다. 재개발 Argon2·JWT에도 그대로 적용된다. 탐침 7건·경계 fixture 2건 회귀 유지.
- **필수조치 D(`encryption_scheme` additive)** — 컬럼은 남기되 의미가 "롤백 호환"에서 **"향후 키 회전용"**으로 바뀐다. 처음부터 `'fernet-v1'`이 아니라 새 AEAD scheme 값(예: `'aes-gcm-v1'`)으로 쓴다.
- **계약·보안 불변식(마스킹 선행·no-store·404 소유권·평문 미저장)**, **DB baseline/Flyway 구조**, **contract 3자 대조·CORS·오류 계약(C-1/C-2/C-3)** — 재개발 기준(요구사항·계약·React)에서 그대로 유효.

**신설 게이트 — 추출 완료(Python 폐기 선행 조건):** 재개발에는 Python 차분 그물이 없어, 코드에만 있던 판단(프롬프트 전문·스타일 상수·**치환 비문 실측 튜닝** `eae75c7`/`0894854`/`a4c9fd9`·골든셋 채점 기준)을 지우기 전에 명세·fixture로 옮겨야 한다. 목록·폐기 게이트는 **`03_rebuild-extraction-list.md`**. 이 게이트가 0으로 닫히기 전 `app/**`·`tests/**` 삭제 금지.

**품질 문제는 이 전환이 해결하지 않는다:** 골든셋 통과율(실수집 52.8%·전체 64.3%)·judge·장문 절벽은 언어 무관이며 프롬프트·긴 문서 전략의 문제다(`02_quality-baseline.md`). Phase 0 합격선 확정 + Phase 2 이후 프롬프트 작업으로 이월. **[3차 갱신]** 합격선 확정은 **완료**됐다(기제로 확정 — 위 Phase 0 표). 남은 이월분은 프롬프트·긴문서 작업이고, 그 성과를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6). **52.8%는 "괜찮다"가 아니라 코퍼스 난이도 판정이며 KPI 0.90은 목표선으로 살아 있다.**

---

## Phase 0 — 범위·계약 동결

계획 문서 §5 Phase 0. 원문 종료 조건: "Kotlin이 **기존 암호문**을 안전하게 읽을 경로와 문서 포팅 가능성이 확인됨."

**종료 조건 문구 정정 (2026-08-12, 리더).** §9-2가 "보존할 DB 없음"으로 확정되면서 **기존 암호문이 존재하지 않는다.** 계획 §4.3은 "기존 DB 본문이 Fernet 토큰이라 호환을 증명 못 하면 기존 문서를 못 읽는다"를 전제로 쓰였고 그 전제가 사라졌다. 실제로 남은 요구는 **역방향**이다 — 계획 §5 Phase 7이 관찰 기간(1~2주) 동안 롤백을 준비하라고 요구하므로, 절체 **후** Kotlin이 쓴 데이터를 롤백한 Python이 읽고 **이어서 쓸 수 있어야** 한다.

따라서 이 Phase의 실질 종료 조건은 다음으로 읽는다:

> **롤백 창에서 Python이 Kotlin 산출물을 읽고 이어서 쓸 수 있는 경로와, 문서 포팅 가능성이 확인됨.**

> **[2026-08-12 2차 개정 무효화]** 위 '역방향/롤백 창' 종료 조건은 **폐기됐다** — 롤백을 포기해(상단 재개발 masthead) 절체 후 Python이 이어 쓸 요구가 없다. Phase 0 실제 종료 조건은 계획 §5 Phase 0(2차)·SKILL.md와 같다(표준 AEAD·Argon2·JWT 구현 경로 + 문서 포팅 가능성 — migration-safety-gate I-7·I-8·I-9). 위 두 문단(39~43행)은 **1차 개정 기록으로만** 남긴다.

~~계획 문서 §5 Phase 0 본문도 같은 취지로 갱신할 것을 제안한다(문서 갱신은 별건).~~ → 계획 §5 Phase 0은 2차 개정으로 갱신됨(이 정정과 정합).

> `실행 경로` 열의 어휘 정본은 `.claude/skills/kotlin-migration/SKILL.md` 의 「선언한 범위와 실제 도달을 대조한다」 절 → 「어디에 적용하는가」 → Phase 종료 판정 항목이다. 정의를 여기에 복제하지 않는다.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| `contracts/easy-doc-v1.yaml` 작성 | 예 | `1회성:contracts/easy-doc-v1.yaml` | `contracts/easy-doc-v1.yaml` 작성 완료. 14 엔드포인트(제품 13 + `/health`)가 FastAPI 실제 노출 경로와 **차집합 양쪽 공집합**으로 일치. `openapi-spec-validator` → OK. 413·502/503·`detail` union·응답 헤더 5종·multipart 요청 본문·`status` enum·CORS·입력 상한 전부 수기 기입 | **U-1 해소 (2026-08-12)** — 리더 결정 + 사용자 승인으로 **개선 수용**(Python 동작을 재현하지 않는다). 계약 파일의 `x-cors.x-known-limitation` → `x-cors.x-unhandled-500-cors`("결정됨 — Python과 의도적으로 다름")로 대체, `components.responses.InternalError.description` 정정, v2 후보 `V2-2` **종결**, `x-changelog` 항목 추가. `info.version`은 1.0.0 유지. 근거 `00_contract-keeper_changelog.md` | - | contract-keeper (2026-08-12) |
| 응답·헤더·오류·인증·권한·입력 상한을 contract test로 고정 | 아니오 | `안 돎` | 목록·기준만 작성 (`00_contract-keeper_test-plan.md` — 엔드포인트별 세트 14 + 횡단 48종). **테스트 코드는 미구현**이며 실행도 하지 않았다 | 리더 지시로 Phase 0에서는 목록만 세웠다(Kotlin API가 Phase 3부터 생긴다). 추가로 **G-1: Python 기준선에도 없는 공백** — `POST /workspaces`·`PATCH /workspaces/{id}`의 캐시 헤더를 어떤 테스트도 단언하지 않는다(계약상 10곳 중 2곳). Python에 먼저 채울지 Phase 3에서 양쪽 동시에 넣을지 판단 필요.<br>**2026-08-12 OQ-1 판정 반영** — 사적 헤더가 **모든 응답**에 붙게 되면서 테스트 계획의 헤더 축이 바뀌었다: ① 오류 응답의 **부정** 단언이 **긍정**으로 뒤집힘(Kotlin 한정 — Python 부정 단언은 그대로 옳다), ② 열거 10곳 개별 단언은 **하한선으로 유지**(삭제 금지), ③ 신규 — DELETE 204·`/health`·프리플라이트·**헤더 중복 부착 부재**(H-2), ④ 신규 — 컨테이너 레벨 응답 도달 여부(**H-1**). 체크 항목 정본 `.claude/skills/api-contract-freeze` §5.1·§5.2.<br>**2026-08-12 H-1 실측 반영** — ④가 **실측 완료**로 닫혔다(X-D2c). 필터 1층으로는 파싱 단계 거절 7종에 못 닿아 **Tomcat Engine 밸브를 더한 2층**이 됐고, 테스트 축에 X-D2d(밸브 음성 대조)·X-D2e(Tomcat 결합 인지)가 추가됐다. 부수로 **오류 본문 사각지대**가 드러나 X-C7·X-C8(`sendError`→`/error` 본문이 `{"detail":…}`가 아니다)·X-C9(컨테이너 응답 본문 **미측정**)가 신설됐다. 계약 정본 `x-error-body-universality`, 절차 정본 스킬 §5.3 | 리더(G-1 시점 판단) → Phase 3 kotlin-implementer | contract-keeper (2026-08-12) |
| FastAPI OpenAPI·계약 파일·React 타입 3자 대조 | 예 | `1회성:docs/migration/_workspace/00_contract-keeper_three-way-diff.md` | `00_contract-keeper_three-way-diff.md`. 불일치 **21건 + 계획-코드 3건 + 미결 1건**. ①이 런타임과 **다른 값**을 말하는 곳 3건(422의 `input`/`ctx`, `loc` 타입, export의 `application/json`), 누락 6건, 느슨함 5건, 의도된 차이 6건. `DELETE /workspaces/{id}`가 React에 없는 것은 **의도된 차이**로 기록 | **없음** — 유일하게 남았던 U-1(§7)이 2026-08-12 리더 결정으로 종결(위 행) | - | contract-keeper (2026-08-12) |
| 대상 DB와 보존할 파일럿 데이터 유무 확인 | 예 | `결정:2026-08-12` | 사용자 확인 (2026-08-12): **보존할 운영/파일럿 DB 없음. 빈 DB로 시작한다.** 이로써 계획 §7이 "변동 폭이 가장 크다"고 지목한 두 변수 중 하나(기존 암호문 호환)가 소멸했다 | - | - | leader |
| 범위 승인: 런타임만 Kotlin화 vs 오프라인 도구까지 Python 제거 | 예 | `결정:2026-08-12` | 사용자 승인 (2026-08-12): **제품 런타임만 Kotlin화**(§9-1). Phase 9(오프라인 도구)는 착수하지 않는다. 골든셋 평가·벤치마크·수집·파일럿 리포트 도구는 Python으로 남아 **독립 검증 oracle** 역할을 유지한다 | - | - | leader |
| Fernet JVM 호환 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §4. `com.macasaet.fernet:fernet-java8:1.5.0` / Temurin 21.0.4 / Gradle 9.1.0. **정방향 8/8**(한글·빈 값·긴 값·제어문자·변조·다른 키·garbage), **역방향 5/5**(`verify-crypto` 통과, `crypto-verify.verified.json` status: pass), **tamper 5/5**(version·timestamp·IV·ciphertext·HMAC 각 1비트 변조 전건 `StorageError` 거부, 무변조 대조군 정상). 즉흥 암호 구현은 하지 않았다 | **(1) 조달 유보** — 이 라이브러리는 최신 1.5.0이 **2020-09-26** 릴리스로 약 5년 11개월 무릴리스(`maven-metadata.xml`·jar `Last-Modified` 실측). §4.3-2의 "유지보수 상태"를 만족한다고 보기 어렵다. 채택(코드 전량 검토 조건) vs JDK primitive 자체 조립 중 선택 필요 = **§9 결정 3**. **(2) 필수 조치 C** — 기본 Validator는 TTL 60초라 **유효 토큰 5건 전부 `TokenExpiredException`으로 실패**한다. 그대로 쓰면 업로드 60초 뒤 모든 문서가 안 읽힌다. Phase 4에서 TTL·maxClockSkew 명시적 무력화 + 60초 경과 토큰으로 회귀 테스트 필요. **(3)** AES-GCM(선택지 2)은 미검증 — 권고하지 않아 수행하지 않았다 | 리더(§9 결정 3) | privacy-gate |
| Argon2 PHC 검증 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §2. `spring-security-crypto:6.4.2`(`Argon2PasswordEncoder(16,32,4,65536,3)`) + `bcprov-jdk18on:1.78.1`. 파라미터는 `app/services/auth.py:59`에서 직접 읽었고 salt 16B·hash 32B는 fixture PHC base64 길이에서 역산. **정방향 13/13**(한글·NFD 불일치 거부·legacy `m=8192,t=2,p=2` 검증·변조·비PHC 문자열이 예외 아닌 `false`), **역방향 4/4**(`app/services/auth.py::_HASHER`가 Kotlin 산출 PHC를 전건 검증, `needs_rehash=false`, 틀린 비밀번호 거부, prefix `$argon2id$v=19$m=65536,t=3,p=4$` 동일) | **필수 조치 A** — 재해시 판정이 갈린다. Python `check_needs_rehash`는 **전체 파라미터 동등성**, Spring `upgradeEncoding`은 **memory·iterations의 "미만"만** 본다. 자체 탐침 7건 중 5건 불일치(parallelism만 다름·더 강한 memory·더 강한 iterations·hash_len·salt_len에서 Python `true` / Kotlin `false`). **공식 fixture 14건으로는 드러나지 않는다.** 지금은 무해하나(살아 있는 해시가 전부 현재 파라미터) 파라미터를 바꾸는 날 **이관이 조용히 멈춘다**. Phase 3에서 전체 동등성 판정 함수로 교체 + 탐침 7건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| JWT 양방향 호환 spike | 예 | `1회성:docs/migration/_workspace/00_privacy-gate_crypto-spike.md` | `00_privacy-gate_crypto-spike.md` §3. **정방향 17/17을 두 라이브러리에서 각각**(`nimbus-jose-jwt:9.41.2`, `auth0 java-jwt:4.4.0`) — alg=none·RS256 헤더 혼동·서명 위조·페이로드 변조·`sub`/`exp`/`typ` 누락·`typ` 불일치·비UUID sub·32B 시크릿 통과·31B `configuration_error` 전건 일치. **`exp` 경계 질문 해소**: skew 0에서 두 라이브러리 모두 `exp <= now`를 만료로 봐 PyJWT와 같다(`exp-2…exp+2` 훑어 예외 타입까지 확인 — 결과만 보고 오독하지 않도록 메커니즘 대조). **역방향 4/4**(`verify-jwt` 통과, subject 2종 × 유효/만료, `jwt-verify.verified.json` status: pass) | **필수 조치 B** — 경계가 맞은 것은 skew를 0으로 **명시했기 때문**이다. `DefaultJWTClaimsVerifier` 기본 `maxClockSkew`는 **60초**라 기본값으로 두면 만료 토큰이 `+59s`까지 ACCEPT돼 `jwt-exp-boundary-exact` fixture에서 실패한다. Spring Security `NimbusJwtDecoder`의 `JwtTimestampValidator`도 기본 60초라 같은 함정. auth0는 기본 leeway 0이라 무해. Phase 3에서 skew 0 명시 + 경계 fixture 2건 회귀 고정 필요 | Phase 3 kotlin-implementer | privacy-gate |
| DOCX/PDF/HWPX 라이브러리 spike | 예 | `1회성:docs/migration/_workspace/00_kotlin-implementer_doc-spike.md` | `00_kotlin-implementer_doc-spike.md`. **§4.5가 경고한 DOCX 위험은 해소됨** — POI를 usermodel이 아니라 OOXML DOM 순회로 쓰면 Python `_docx_blocks`와 **블록 리스트가 완전 일치**한다. 기존 fixture 6개 + 합성 fixture 4개 전부 Python 산출값 일치(거부 메시지 문자열까지). 동등성 6항목 전부 확인(표 제자리·텍스트박스·SDT·`w:ins`/`w:delText`·`mc:Fallback`·`a:t`/`m:t`·linked 머리글). HWPX: DTD/UTF-16 DTD/XXE 차단, 1GiB zip bomb을 힙 256MB에서 거부(힙 증가 0MB), 자체 round-trip·mimetype STORED 첫 항목·생성 결정성 PASS, Python↔Kotlin 패키지 교차 읽기 PASS. 검증 조합: Java 21.0.4 / Gradle 9.1.0 / Kotlin 2.2.0 / POI 5.4.1 / PDFBox 3.0.5 / commons-compress 1.27.1. `uv run pytest tests/ingest -q` 57 passed로 Python 기준선 무손상 | **가능성은 확인됐고 남은 것은 Phase 4 결정·구현이다.** (1) POI 산출 DOCX에 `styles.xml`/`theme` 부재 — Heading 1 서식이 사라짐, 템플릿 정책 결정 필요 (2) zip 컨테이너 바이트는 Python과 동일해질 수 없음(실측 `java=434B` vs `python=348B`) → parity fixture를 바이트 해시로 잡으면 안 됨, `parity-verifier` 합의 필요 (3) StAX DTD 판정을 예외 **메시지**로 하면 로케일 의존 — `DTD` 이벤트 직접 처리로 바꿔야 함 (4) 위조 크기 zip의 사용자 메시지가 Python과 갈림(`손상되었습니다` vs `너무 큽니다`) (5) **미검증**: 실제 한컴/Word 저장 파일, 실제 공공기관 PDF의 pypdf↔PDFBox 동등성, `MAX_EXTRACTED_CHARS`·10MB 경계, 암호 PDF/DOCX 실파일, Spring Boot BOM 적용 후 버전 재정렬 | - | kotlin-implementer |
| 리뷰 게이트 Critical 0건<br>→ **범위를 좁혀 판정**: "Phase 2 착수를 막는 Critical 0건" | 아니오 | `1회성:docs/migration/_workspace/reviews/02_criteria-pivot_cross.md` | **[criteria-pivot 2026-08-12 재판정] 예→아니오.** 3회차 리뷰(`02_criteria-pivot_cross.md` §6.2)가 이 행이 2회차 근거로 닫힌 **뒤** Phase 2 착수 차단 ② 5건을 새로 냈다 — X-05·X-06(privacy 레인)·X-09~X-12(parity 레인)·X-08(본 인벤토리 레인). 하나라도 열려 있으면 "Phase 2 착수를 막는 Critical 0건"은 거짓이다. blocked-by는 그 세 레인. 상세는 아래 「기준 전환 재판정」. 이하 근거는 2회차 시점의 역사 기록이다 — 2회차(Phase 1 골격) 실행 완료 — `reviews/01_skeleton_{codex-reviewer,migration-reviewer,cross}.md` 3건 (정본은 `_cross.md`). 1회차 수정(pre-phase0 X-1·X-2)이 2회차 리뷰를 실제로 받았고, 2회차가 새로 지적한 차단 중 **Phase 2 작업에 닿는 것은 parity CI(2회차 X-2) 하나**였으며 `01_kotlin-implementer_parity-ci-fix.md`로 닫혔다. 함께 닫힌 것: C-1·C-2·C-3(`01_kotlin-implementer_error-cors-fix.md`), T-5=P1-1 판정(`01_kotlin-implementer_boot41-upgrade.md`), F-8 확인(Phase 0 §9-2 "보존할 DB 없음"이 이미 답). **판정 근거는 교차 종합 §7.1·§8** — Phase 2는 순수 도메인 로직 포팅이라 HTTP·DB·CORS 경계를 쓰지 않으므로 심각도가 높다는 이유만으로 착수를 막는 것은 과잉이라는 권고를 채택했다 | **나머지 차단 지적은 사라지지 않는다** — 마감이 명시된 미결 원장(§Phase 1 종료 판정)으로 이월했고 각 Phase 착수 게이트에서 다시 센다. 원 판정("Critical 0건")을 그대로 쓴 것이 아니라 **범위를 좁혀** 닫은 것임을 명시한다. 상충-2(심각도 척도)는 1회차에 리더가 판정해 반영 완료 — 사건뿐 아니라 **탐지 장치의 무력화도 Critical**로 세되, 심각도와 착수 차단은 별개 축이고 마감은 그 게이트의 첫 실사용 시점이다 | - | leader (2026-08-12) |
| 전역 요구사항 인벤토리 1차본 작성·승인 (계획 §5 Phase 0 · §1.1) | 아니오 | `1회성:docs/migration/_workspace/00_requirements-inventory.md` | **[criteria-pivot 신설 2026-08-12]** 커밋 49ea2eb가 Phase 0 종료 조건에 "요구사항 인벤토리 1차본 승인"을 넣었으나 산출물이 없어 행조차 세우지 못했다(리뷰 A-1/X-08 — "미충족 0"이 항목 0개에서 참이 되던 구멍). **1차본 작성 완료** → `00_requirements-inventory.md`(항목 39 + 확인방법 미확정 4건 명시). 게이트가 이제 실제 항목을 가리킨다 | 1차본은 존재하나 **승인 미완**(충족=예는 승인까지 요구) | 리더·사용자 승인 | criteria-pivot 재판정 (2026-08-12) |
| 품질 합격선 **기제** 확정·승인 (계획 §5 Phase 0 · §4.6 게이트2·5)<br>*직전 행 제목: "합격선 **수치** 확정·승인" — 확정된 것이 수치가 아니므로 제목을 고쳤다* | 예 | `ci:quality` · `ci:llm-lane(조건:.github/llm-lane-paths.txt)` · `결정:2026-08-12` | **[2026-08-12 3차 갱신, 아니오→예]** 요지는 **합격선을 절대 수치가 아니라 기제로 확정**했다는 것이다 — 수치를 못 정해 우회한 것이 아니라, 고정할 수 없는 것(채점 모델)과 코퍼스 난이도에 좌우되는 것(통과율)을 차단축에서 분리한 결과다. **사용자 결정 4건**: ① 하한선의 출처 = **이 저장소의 직전 기록 측정치**(상대 — Python이 아니다) ② 실수집 52.8%는 **코퍼스 난이도**로 판정 — 목표를 낮춘 것이 아니며 **KPI 0.90은 목표선으로 존속**한다 ③ **judge에 차단 권한을 주지 않는다** ④ **통과하는 실행도 수치를 남긴다**. **두 차단축** — 필수 정보 보존 **절대**(누락 0건, 결정적, LLM-as-judge 미사용) / 규칙 통과율 **상대**(직전 기록 대비 하락 0, 코퍼스·판정 기준 지문이 다르면 비교 불가로 차단). **구현·검증·커밋됨** (`c43cae5`) — `tests/golden/baseline.py`(지문·상대 판정)·`tests/golden/report.py`(통과 실행도 기록)·`app/easyread/goldenset.py`의 `find_fact_losses`(절대 팩트축). **리더 직접 실행 실측**: `pytest` **916 passed / 68 skipped**, `pytest tests/golden` **63 passed**, mypy **122 files**, 지문·기록·하락 재현 **14건** 통과. 인벤토리 §9-A의 네 공백(코퍼스 고정 → 지문 2축 / 통과 시 기록 → `report.py` / 채점자 고정 → 결정 ③으로 **소멸** / 축별 허용치 → 팩트축 절대 0·스타일축 상대화) **전부 처분**. 계획 반영: §4.6 게이트2·5 재작성, 게이트5 단서 미결 **종결**, codex Q6 **확정**(담당 = **Phase 5 종료 게이트**, Phase 7 진입 전 필수), §6 검증 매트릭스 Quality 행 교체 | **ⓐ `tests/golden/baseline.json` 미기록** — LLM 키가 없어 실측할 수 없었고 **수치를 지어내지 않았다.** 지금은 "기준선 없음 → 차단, 기록 필요"로 떨어지며 **이것이 설계된 정상 상태**다. 첫 기록: `GOLDEN_RECORD_BASELINE=1 GOLDEN_PROVIDER=anthropic uv run pytest tests/golden -m llm`. **ⓑ 절대 팩트 게이트가 첫 실행에서 통과하지 못할 공산** — 마지막 저장 실행(2026-08-08)의 팩트 잔존 90.1%·14문서 누락이고 실손실과 `accept` 목록 공백의 비율은 **정적 판정 불가**. 게이트를 낮출 사유가 아니라 프롬프트 문제이며 첫 `-m llm` 실행이 삼분류 근거를 만든다. **ⓒ 날조(fidelity=1)에 결정적 차단 없음 — 리더 판정 대기.** 역방향 축(출력의 숫자·금액·날짜가 원문에 존재하는가)은 합의 범위 밖의 새 차단 게이트라 짓지 않았다. **ⓓ 독립 리뷰 미실시.** **적용 범위의 한계(오독 금지)**: 게이트 로직·배선은 매 CI 기본 스위트에서 돌지만(재현 40건, LLM 호출 없음) **실제 변환문에 대한 적용은 `-m llm` 레인 전용**이다 — 변환 없이는 검사할 대상이 없다. "매 커밋 차단"이 아니다 | ⓐ LLM 키 / ⓒ 리더 판정 / ⓓ 리뷰 레인 | leader (2026-08-12 3차) |

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
| 리뷰 게이트 Critical 0건 | 지적된 Critical 2건은 코드로 닫았으나 **그 수정 자체가 리뷰를 받지 않았다.** 이번 세션에서 "고쳤다고 보고한 직후 codex가 그 수정의 결함을 잡은" 일이 세 번 있었다 | **Phase 1 종료 시** — Kotlin 골격이라는 실제 코드가 생긴 뒤 3단계 게이트를 돌린다. 문서·스크립트만 있는 지금보다 그때 리뷰가 값이 크다 → **2026-08-12 닫힘.** 예정대로 Phase 1 종료 시점에 2회차 게이트를 돌렸고, "Phase 2 착수를 막는 Critical 0건"으로 **범위를 좁혀** 판정했다(위 표) |

**2026-08-12 갱신.** 위 2행 중 아래 행이 닫혀 Phase 0 종료 조건은 **10행 중 9행 충족 / 1행 미충족**이 됐다.
남은 1행(contract test)의 마감은 그대로 Phase 3다. 위 "8행 충족" 문장은 2026-08-12 판정 시점의 기록이므로 고치지 않는다.

### 기준 전환 재판정 (criteria-pivot, 2026-08-12)

커밋 49ea2eb가 판정 기준을 "Python 출력 일치"에서 "요구사항 충족"으로 바꾸면서 **이미 닫힌 Phase의 종료 조건을 소급 개서**했다. 리뷰 02_criteria-pivot A-1/X-08의 지적에 따라 닫힌 판정 전부를 새 기준으로 다시 봤다.

**Phase 0 표 변동 3건**
- **신설 2행(둘 다 아니오)** — 커밋이 Phase 0 종료 조건에 넣은 "요구사항 인벤토리 1차본 승인"·"품질 합격선 승인"을 행으로 세웠다. 인벤토리는 1차본(`00_requirements-inventory.md`)을 만들어 게이트가 실제 항목을 가리키게 했으나 **승인 미완**이고, 품질 합격선은 **수치 자체가 없다**.
- **1행 뒤집음(예→아니오)** — "리뷰 게이트 Critical 0건". 2회차(Phase 1 골격) 리뷰로 닫힌 뒤 3회차(02_criteria-pivot)가 Phase 2 착수 차단 ② 5건을 새로 냈다(X-05·X-06·X-08·X-09~X-12). blocked-by: privacy 레인(X-05·X-06)·parity 레인(X-09~X-12)·본 인벤토리 레인(X-08). X-08의 계측기 공백은 이 1차본으로 닫혔고, 남은 것은 승인 판정과 네 건(병렬 레인 진행 중)이다.

**재판정 후 Phase 0 집계: 12행 중 8행 충족 / 4행 미충족.** (직전 기록 "10행 중 9행"은 기준 전환 전 판정이라 위 문장들과 함께 남긴다 — 고치지 않는다.)

> **[2026-08-12 3차 갱신]** 위 4행 중 **「품질 합격선 확정·승인」이 닫혔다**(아니오→예 — 위 Phase 0 표). 합격선을 **절대 수치가 아니라 기제로** 확정하고 계측기를 구현·검증·커밋했다(`c43cae5`). **현재 집계: 12행 중 9행 충족 / 3행 미충족**(남은 3행 = contract test 고정 / 리뷰 게이트 Critical 0건 / 인벤토리 1차본 승인). 위 "8행 충족" 문장은 판정 시점의 기록이므로 고치지 않는다. 이 행에 붙은 미해결 4건(ⓐ `baseline.json` 미기록 · ⓑ 첫 실행 미통과 공산 · ⓒ 날조 차단 부재 리더 판정 대기 · ⓓ 독립 리뷰 미실시)은 **행을 닫은 것과 별개로 살아 있다** — 행이 판정하는 것은 "합격선이 확정·승인됐는가"이지 "골든 품질이 합격했는가"가 아니다. 후자를 판정하는 자리는 **Phase 5 종료 게이트**로 확정됐다(계획 §4.6 codex Q6).

**뒤집지 않은 행 — 새 기준에서도 예가 유지되는 근거(‘Python과 같다’ 근거는 재기술)**
- **`[호환]` Fernet·JWT·Argon2 spike + DB baseline(필수조치 D)** — 계획 변경 이력·CLAUDE.md가 지정한 **기준 전환 예외**다. **[2026-08-12 2차 정정]** 아래 '롤백 창 값 동일성' 근거는 **폐기**됐다(상단 재개발 masthead) — spike '예'는 유지하되 근거는 **가능성 확인 + 필수조치 A·B·D(migration-safety-gate I-7·I-8·I-9)**다. 이하 문장은 1차 재판정 기록이다. 롤백 창에서 값이 같아야 하는 것 자체가 요구사항이므로 "Python과 같다"가 정당한 근거다. 예 유지. (단 spike는 **가능성** 판정이고 실제 구현·필수조치 A·B·C는 Phase 3·4 미결로 이미 원장에 있다.)
- **`[계약]` contract yaml·3자 대조·리뷰 차단 C-1/C-2/C-3·/health** — 계약은 요구사항이 요구하는 인터페이스다. Python(FastAPI)에 맞춘 부분은 계약 요구이고, 의도적으로 다르게 간 U-1은 "개선 수용"으로 기록됐다 — Python을 oracle로 삼지 않는다. 예 유지.
- **DOCX/PDF/HWPX spike** — 근거가 "Python `_docx_blocks` 완전 일치"라 **근거를 다시 댄다**: 요구사항(인벤토리 DOC-01, 필수 정보 누락 없는 추출)은 더 강한 결과(블록 동일성)로부터 **a fortiori** 충족된다. 동일성은 향후 합격 바가 아니며 갈림은 Difference 게이트가 분류한다(계획 §4.5 — 미결 원장의 바이트 해시 금지·2.3.21 재검증과 정합). 포팅 **가능성** 판정으로서 예 유지.
- **구조·기동 행(Gradle 멀티모듈·toolchain·설정 바인딩·Dockerfile·Testcontainers·빈DB/스냅샷 기동)** — Python 출력 품질과 무관한 구조·기동 요구다. 기준 전환의 영향을 받지 않는다. 예 유지.

**Phase 1 전면 재검토 — 뒤집음 0.** Phase 1 종료 조건 행은 전부 구조·기동·호환성·계약 근거이고 "Python 출력 품질 일치"로 닫힌 행이 없다. 이미 아니오인 2행(GitHub Actions 미검증·필수조치 E fixture 부재)은 새 기준에서도 아니오다. **Phase 2 표는 전부 미착수(아니오)라 뒤집을 닫힌 판정이 없다.**

**Phase 2 착수 함의:** X-08 계측기 공백은 닫혔으나 ① 인벤토리 1차본 **승인**, ② 품질 합격선 **수치**, ③ 나머지 차단 4건(X-05·X-06·X-09~X-12, 병렬 레인)이 남는다. Phase 2 착수 가부는 이 인벤토리 레인 단독으로 판정하지 않는다 — 리더 몫이다.
> **[2026-08-12 3차 갱신]** ②는 닫혔다 — 다만 확정된 것은 **수치가 아니라 기제**다(위 표 행). 남는 것은 ①과 ③이다.

### 착수 전 판단이 필요했으나 Phase 1을 막지 않는 항목

- ~~**U-1** — 미처리 500 응답의 CORS 헤더를 Kotlin에서 재현할지 개선할지~~ → **2026-08-12 결정됨: 개선 수용**(리더 판정 + 사용자 승인). 착수 전 전제였던 "React가 그 동작(`status = 0`)에 의존한다"는 **실측으로 무너졌다** — `grep -rn "NETWORK_ERROR_STATUS|status === 0|\.status" frontend/src` 결과 `status = 0` 경로에 의존하는 화면 분기가 없고, 모든 화면이 `caught instanceof ApiError ? caught.message : <폴백>` 한 모양이다. 상태로 갈리는 자리는 `client.ts`의 401 하나뿐이다. 바뀌는 것은 사용자에게 보이는 **문구뿐**("서버에 연결하지 못했습니다…" → "서버 오류가 발생했습니다"). 되돌리려면 `CorsFilter`를 감싸 **일부러 나쁜 동작을 만드는 코드**를 영구히 남겨야 한다. 근거 `00_contract-keeper_changelog.md`, 비용 비교 `01_kotlin-implementer_error-cors-fix.md` §5. **범위**: 달라지는 것은 CORS 응답 헤더의 유무뿐이며 상태 코드(500)·본문(`{"detail": "서버 오류가 발생했습니다"}`)이 갈리면 그것은 의도된 차이가 아니라 계약 위반이다
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

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 종료 조건 | 충족 | 실행 경로 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|
| `backend-kotlin` Gradle 멀티모듈 생성 (§3.2의 5개 모듈, 의존 방향) | 예 | `ci:kotlin` | `core`/`application`/`infrastructure`/`api`/`worker` 생성. `api`·`worker` 는 `infrastructure` 를 **`runtimeOnly`** 로만 의존해 JDBC·(Phase 5) LLM SDK 타입이 컴파일 시점에 보이지 않는다. `application` 은 `infrastructure` 를 의존하지 않는다. `api`↔`worker` 상호 의존 없음. **`core` 의 Spring·DB 비의존을 `CoreModuleBoundaryTest` 가 실행으로 확인**(7개 클래스 부재: `ApplicationContext`·`SpringApplication`·`JdbcClient`·`Flyway`·`org.postgresql.Driver`·Jackson 2/3 `ObjectMapper`) | `application` 본 소스는 비어 있다(경계만 세움, 유스케이스는 Phase 3~5). 계약은 `application/README.md` | - | kotlin-implementer |
| toolchain·dependency locking·version catalog·ktlint/detekt·테스트 설정 | 예 | `ci:kotlin` | Java 21 toolchain(`jvmToolchain(21)`), `allWarningsAsErrors=true`. 락파일 6개 커밋(모듈 5 + settings, 792줄) — `clean build` 가 락 갱신 없이 성공. catalog 가 유일한 버전 선언 지점이고 **BOM 밖에서 버전을 고르는 것은 Kotlin 플러그인·ktlint·detekt 셋뿐**. ktlintCheck·detekt 모두 통과(위반 0). **locking 이 실제 드리프트를 잡았다** — kotlinx-serialization 1.11.0이 테스트 클래스패스 stdlib 만 2.2.21→2.3.20으로 올린 것을 발견해 BOM(1.9.0)에 넘겼다 | 기본값을 벗어난 규칙 2건(ktlint `class-signature` 임계 1→2, detekt `SpreadOperator` off) — 사유는 산출물 §2.4.<br>**2026-08-12 사실 정정** — detekt 1.23.8의 내장 파서는 Kotlin **1.9가 아니라 2.0.21**이다(`detekt-parser` POM 실측). 같은 종류의 간격이 ktlint에도 있고 Phase 1 문서에 언급이 없었다 — ktlint-cli 1.8.0은 **2.2.21**을 내장한다. Boot 4.0.7 시절엔 컴파일러도 2.2.21이라 우연히 같았고 **2.3.21로 올리면서 갈렸다**. 둘 다 올릴 곳이 없다(ktlint 1.8.0/플러그인 14.2.0이 최신, detekt 2.x는 `dev.detekt` 좌표에 alpha만). 남는 위험은 "Kotlin 2.3 신문법을 쓰면 그때 파싱 실패"인데 **태스크 실패로 드러나므로 조용히 틀리는 종류가 아니다**. 근거 `01_kotlin-implementer_boot41-upgrade.md` §6 | - | kotlin-implementer (2026-08-12) |
| `/health` 가 계약대로 응답 (상수 `{"status":"ok"}`) | 예 | `ci:kotlin` | `HealthContractTest` 4건 — 200·`{"status":"ok"}`(strict)·인증 불필요·**캐시 금지 헤더 없음**·DataSource 없이도 200(=의존 서비스 진단 안 함). compose 실측: `HTTP/1.1 200 / Content-Type: application/json / {"status":"ok"}`. Actuator 미도입(계약 14 엔드포인트 밖 경로를 노출하지 않으려고) | - | - | kotlin-implementer |
| 설정 바인딩·구조화 로그·비밀값 마스킹 | 예 | `ci:kotlin` · `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | `EasyDocProperties`(`app/config.py` 포팅) + `Secret` 타입(`SecretStr` 대응) + `SecretConverter`. `SecretTest` 7건 — `toString`·문자열 템플릿·**데이터 클래스 필드로 들어가도** 평문 미노출, 값 비의존 `hashCode`, 상수 시간 비교. 구조화 로그는 Dockerfile `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` 로 ECS JSON — compose 로그로 확인. `server.error.include-*` 를 전부 `never`/false 로 꺼 스택·입력값이 응답에 실리지 않게 했다.<br>**2026-08-12** — `easydoc.cors-origins` 가 `CorsConfig` 에서 **실제로 소비**되면서 바인딩 경로가 `CorsContractTest` 로 실행된다(리뷰 T-1 부분 해소) | `Secret` 필드(`jwtSecret`·`fernetKey`)와 `SecretConverter` 의 바인딩은 여전히 미실행 — 값을 쓰는 기능이 Phase 3·4에 온다 | - | kotlin-implementer (2026-08-12) |
| **리뷰 차단 C-1·C-2·C-3** — 오류 계약의 HTTP 경계 검증과 CORS | 예 | `ci:kotlin` | 상세는 `01_kotlin-implementer_error-cors-fix.md`. **C-1**: `GlobalExceptionHandler` 가 `ResponseEntityExceptionHandler` 를 상속해 프레임워크 예외 20종의 **상태 코드는 위임**하고 **본문만** `createResponseEntity` 한 곳에서 `{"detail": ...}`·`application/json` 으로 덮는다. `detail` 은 상태 코드의 표준 사유 문구만 쓴다(Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있다). `WebMvcConfig` 로 내용 협상을 끄고(FastAPI 는 협상하지 않는다) 검증 실패를 **422 + `[{loc,msg,type}]`** 로 되돌렸다(`input`·`ctx` 미노출). **C-2**: `ErrorContractTest` 를 핸들러 직접 호출 → `@WebMvcTest`+MockMvc 로 이전하고 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)를 세웠다. **고치기 전 8건 실패 → 고친 뒤 31건 전건 통과**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다). **C-3**: `CorsConfig` 의 `CorsFilter`(order HIGHEST_PRECEDENCE) — origin 설정값·credentials false·메서드 5종·요청 헤더 2종·**노출 헤더 `Content-Disposition, Location`**·max-age 600. `addCorsMappings` 가 아니라 필터인 이유는 Starlette 미들웨어처럼 **라우팅 밖**이어야 404·405 에도 헤더가 붙기 때문(실측 일치). 살아 있는 두 컨테이너 16 케이스 대조: C-1 세 케이스 **전건 일치**, CORS 5 케이스 일치 | 남은 차이 5건 전부 기록·분류함 — **U-1(미처리 500 의 CORS 헤더)은 2026-08-12 리더 결정으로 종결**(개선 수용), `GET /health/` 307 vs 404는 판단 대기, **범위 밖 3건**(`OPTIONS` Origin 없음 = 리뷰 C-4, `HEAD /health`, preflight 본문).<br>**C-2 의 8건 중 1건은 고치지 않고 테스트를 철회했다** — `컨트롤러가 적어 둔 캐시 금지 헤더가 오류 응답으로 새지 않는다`. 서블릿 API 에 헤더 삭제가 없고(`setHeader(name, null)` 무시) `response.reset()` 은 CORS 헤더까지 지운다. 강제 가능한 규칙은 "쓰지 않는 것"뿐이며, 이 사실이 계약 §2.7-3 재작성(규칙 1 + 단언 A·B)의 근거가 됐다. 남아 있는 보호: "핸들러 자신이 캐시 헤더를 붙이지 않는다" 단언 7건.<br>`handleHandlerMethodValidationException` 은 `spring-boot-starter-validation` 이 없어 **HTTP 경계 미검증**(의존성 추가는 동시 작업 중인 빌드 스크립트를 건드려야 해 보류) | - | kotlin-implementer (2026-08-12) |
| Testcontainers PostgreSQL + Flyway baseline 구축 | 예 | `ci:kotlin` | `V1__python_schema_baseline.sql` 을 **Alembic 을 실제로 돌려**(`uv run alembic upgrade head` → `alembic_version=0006`) 뽑은 스키마로 작성. 지문 대조 **전건 일치**(extension 1 · table 4 · column 32(서수 포함) · constraint 11 · index 11). 회귀는 `PythonSchemaBaselineTest` 4건 + `FlywayBaselineGuardTest` 4건. `baseline-on-migrate=true` 를 쓰지 않고 **지문이 일치할 때만** baseline 하는 `FlywayBaselineGuard` 를 만들었다(§4.2-4). `alembic_version` 은 만들지도 읽지도 쓰지도 않는다(§4.2-7) | Testcontainers 컨테이너가 모듈마다 따로 뜬다(`withReuse` 미적용). 로컬 전체 16초라 지금은 무해 | - | kotlin-implementer |
| **필수 조치 D** — `encryption_scheme` additive 추가 | 예 | `ci:kotlin` | **V2에 배치**(V1 아님). 근거: ① V1은 "Python 스키마 재현"이라 신규 컬럼이 들어가면 지문 대조가 성립하지 않는다 ② **결정적** — baseline 은 V1을 건너뛰므로 V1에 넣으면 기존 Alembic DB에서 컬럼이 영원히 안 생긴다 ③ §4.2-5가 "Kotlin 전용 변경은 V2부터"라고 명시. 대상 `documents`·`conversions`, 기본값 `'fernet-v1'`, CHECK 제약 동반. `V2 는 encryption_scheme 을 additive 로 추가한다`·`Python 컬럼만 지정한 INSERT 가 성공한다` 테스트 통과 | 관찰 기간 내내 `fernet-v1` 고정. AEAD 전환은 Phase 8 이후 별건 | - | kotlin-implementer |
| Dockerfile·compose Kotlin profile 추가 (기존 Python 서비스 유지) | 예 | `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | `backend-kotlin/Dockerfile`(멀티스테이지, api·worker bootJar 한 이미지). compose에 `kotlin-migrate`·`kotlin-api`(8100)·`kotlin-worker` 를 `profiles:["kotlin"]` 뒤에 추가 — **기존 Python 서비스 정의를 하나도 바꾸지 않았고** 기본 `docker compose up` 동작이 그대로다. 실측: 두 스택 동시 기동, Kotlin 8100·Python 8000 양쪽 `/health` 200. `kotlin-migrate` exit 0. §4.2-6대로 **DB를 갈랐다**(`easydoc` / `easydoc_kotlin`) — Python DB에 `flyway_schema_history` 0개 확인 | `easydoc_kotlin` 은 기존 볼륨에서 자동 생성되지 않는다(initdb 는 빈 데이터 디렉터리에서만 실행) — 수동 절차 문서화. compose 실행 중 **worker 즉시 종료**를 발견해 `spring.main.keep-alive: true` 로 고쳤다(산출물 §9.5) | - | kotlin-implementer |
| CI에 Kotlin build/test 추가 + 기존 Python/React gate 유지 | 아니오 | `ci:kotlin` · `ci:quality` · `ci:frontend` | `.github/workflows/ci.yml` 에 `kotlin` 잡 추가(9 steps: setup-java 21 · setup-gradle · setup-uv · 이미지 pull · `./gradlew build` · `parityHarness` · 배선 확인 · parity 비교). **기존 `quality`(8 steps)·`frontend`(6 steps) 잡을 건드리지 않았다** — 로컬에서 `ruff`·`ruff format`·`mypy`·`pytest`(820 passed, 68 skipped) 전부 통과 확인 | **CI가 실제 GitHub Actions 에서 도는 것을 확인하지 못했다.** YAML 파싱과 로컬 동등 명령만 검증했다. `gradle/actions/setup-gradle@v4`·러너 Docker 데몬 위 Testcontainers 는 **첫 push 에서 처음 검증된다**. 이 행은 그때 닫는다 | 첫 PR 실행 | kotlin-implementer |
| **필수 조치 E** — Kotlin 테스트가 `parity/actual/` 을 쓰도록 CI 배선 | 아니오 | `ci:kotlin` | 배선 구조 완성: `ParityActual`(경로를 시스템 프로퍼티로만 받고 **없으면 던진다**) + `parityHarness` Gradle 태스크(`@Tag("parity")` 만, 저장소 루트로 출력) + 일반 `test` 는 모듈 `build/` 로 격리 + CI 3단계(생성·선언 대조 → 존재·`runtime:kotlin` 확인 → 비교). `ParityActualTest` 5건이 산출물 형식·경로·한글 비이스케이프·거부 조건을 고정. 실측 산출물 `parity/_harness-selfcheck/kotlin.json`(`runtime:kotlin`, JVM 21.0.4 Temurin, Kotlin 2.2.21).<br>**2026-08-12 X-1·X-2 수정 (`01_kotlin-implementer_parity-ci-fix.md`)** — 판정 범위를 디렉터리 유무가 아니라 버전 관리 선언 `backend-kotlin/parity-domains.txt` 에서 가져오고, 그 선언을 Gradle `parityManifestCheck` 가 실제 산출물과 **양방향 대조**한다(선언 O/산출 X·선언 X/산출 O·json 0건 전부 빌드 실패). `parityActualClean` 이 매 실행 전 `parity/actual/` 을 비워 stale 산출물 통과를 막는다. **종료 코드 2 사면은 제거**했고(실측: exit 2 는 "선언한 도메인의 역방향 산출물 미생성"일 때만 난다), 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 분리**했다. 선언이 정본 11개를 덮으면 좁히기가 자동으로 사라진다. 실증 14종(CI 셸 8 + Gradle 6): 현재 상태 exit 0 / Phase 2 흉내 exit 0(값 21건 대조) / 선언했는데 산출물 없음 exit 1 / 값 불일치 exit 1 / fixture 트리 삭제 exit 1 / 전체 게이트 exit 0(값 101건 + 외부 2건). Python 게이트 무손상(820 passed).<br>**2026-08-12 가드 2종 추가 (`01_parity-canonical-floor.md`)** — ① **정본 0개 가드**: `canonical_count == 0`이면 exit 1(`ci.yml:197`). `--list`가 exit 0인데 출력만 비는 경로는 `pipefail`이 못 잡고, 그대로 두면 "11개를 안 봤다"는 경고가 "0개를 안 봤다"로 바뀌어 무검증이 통과한다. ② **정본 하한**: `.github/parity-canonical-floor.txt`(초기값 정본 11개) + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 통과시키고 **삭제만 막는다** — 축소가 "전체 게이트 통과"로 위장되던 경로가 닫혔다. 실증 12종: 11→3 축소가 선언 0개·선언 3개 양쪽에서 exit 1, 가드 제거 변형 4종은 전부 exit 0(막고 있는 것이 정확히 이 비교임을 확인), 하한 파일 삭제·비움도 exit 1 | **채우지 못한 것**: `parity/fixtures/` 는 여전히 저장소에 없다(Phase 2 산출물). 실증에 쓴 Kotlin 산출물은 Python 스탠드인이며 실제 Kotlin 구현으로 도는 것은 Phase 2 첫 도메인에서 처음 확인된다. **GitHub Actions 러너 실행 미검증**(로컬 bash 3.2 재현). `parityManifestCheck` 는 도메인 입도까지만 봐서 **X-5 의 모듈↔도메인 대응 단언은 열려 있다**. 하한도 도메인 **이름**만 보므로 도메인 안의 케이스 축소는 잡지 못하고, 같은 커밋에서 정본과 하한을 함께 줄이는 것은 원리적으로 막을 수 없다(최종 방어선은 `.github/` diff 를 사람이 읽는 리뷰 게이트다). 미결 원장 `P1-2`(종료 코드 2 완화)는 **해소 확인**(아래 사실 정정) | Phase 2 (fixture 생성 · 첫 push 에서 러너 검증) | kotlin-implementer (2026-08-12) |
| 종료 조건: 빈 DB와 기존 schema snapshot 양쪽에서 기동 + `/health` 응답 | 예 | `ci:kotlin` | `ApiStartupOnEmptyDatabaseTest` 2건 + `ApiStartupOnPythonSnapshotTest` 2건. `@SpringBootTest(RANDOM_PORT)` + JDK `HttpClient` 로 **실제 소켓**을 친다. 빈 DB → `flyway_schema_history=[1,2]`, 200 `{"status":"ok"}`. 기존 스냅샷(Alembic 0006 상태) → `[1(BASELINE), 2(SQL)]`, `alembic_version=0006` 불변, 200 `{"status":"ok"}`. compose 실측으로도 재확인(산출물 §9).<br>**2026-08-12 Boot 4.1.0 업그레이드 후 재확인** — 같은 4건이 그대로 통과하고, Flyway 11→12(유일한 메이저 상승)에 대해 **11.14.1이 쓴 `flyway_schema_history` 를 12.4.0이 `Successfully validated 2 migrations` 로 수용**하는 것까지 compose 로그로 확인했다(체크섬 재계산 요구 없음). 근거 `01_kotlin-implementer_boot41-upgrade.md` §5·§8-4·§8-5 | - | - | kotlin-implementer (2026-08-12) |

**전체 테스트**: `./gradlew build` → **BUILD SUCCESSFUL, tests=75 failures=0** (core 19 · infrastructure 8 · api 45 · worker 3).
2026-08-12 리뷰 C-1·C-2·C-3 수정으로 48 → 75 (api 18 → 45: `ErrorContractTest` 10→18 경계 이전 ·
`FrameworkErrorContractTest` 9 신규 · `CorsContractTest` 10 신규). 기존 48건은 전건 유지.
**Boot 4.1.0 업그레이드 후에도 같은 75건이 그대로 통과한다** — 업그레이드 전(4.0.7) 기준선을 먼저 찍고 대조했다.

### 확정한 버전 조합 (Boot 4.1.0 BOM 적용 후 — 2026-08-12 갱신)

| 항목 | 값 | 이전(4.0.7) 대비 |
|---|---|---|
| JDK / Gradle | Temurin 21.0.4 / 9.1.0 | 동일 |
| Kotlin | **2.3.21** | 2.2.21 → BOM `kotlin.version` 정렬 (변경) |
| Spring Boot | **4.1.0** | 4.0.7 → **P1-1 판정 이행**. 4.1 계열 안정판은 4.1.0 하나뿐(Maven Central 메타데이터 실측) |
| Flyway | **12.4.0** | 11.14.1 → **유일한 메이저 상승**. §5 에서 따로 검증 |
| kotlinx-serialization | **1.11.0** | 1.9.0 → BOM |
| Spring Framework / Jackson / JUnit / Testcontainers / PG 드라이버 | 7.0.8 / 3.1.4 / 6.0.3 / 2.0.5 / 42.7.11 | **전부 동일** (BOM 관리) |
| ktlint(플러그인/CLI) / detekt | 14.2.0 / 1.8.0 / 1.23.8 | 동일 (§위 표의 사실 정정 참고) |

Boot 4가 spike 이후 바꾼 좌표(실측)는 4.1.0에서도 그대로다: `FlywayMigrationStrategy` → `spring-boot-starter-flyway` /
`org.springframework.boot.flyway.autoconfigure`, `@WebMvcTest` → `spring-boot-starter-webmvc-test` /
`org.springframework.boot.webmvc.test.autoconfigure`, Testcontainers → `org.testcontainers:testcontainers-postgresql`.
**Jackson 3.1.4**(패키지 `tools.jackson`)가 관리 버전이라 Phase 2 이후 JSON 처리 시 주의가 필요하다.

**업그레이드 결과 (`01_kotlin-implementer_boot41-upgrade.md`)** — **코드 변경 0줄.** 바뀐 파일은 version catalog 와
락파일 5개뿐이고, `clean build` tests=75 failures=0 · ktlint/detekt 위반 0 · 컴파일 경고 0(`allWarningsAsErrors=true`) ·
Python 게이트 820 passed 가 업그레이드 전과 동일하다. Kotlin 2.3.21이 실제로 런타임까지 갔다는 증거는
`parity/_harness-selfcheck/kotlin.json` 의 `kotlinVersion` 필드(JVM 이 `KotlinVersion.CURRENT` 를 읽어 쓴다)다.

두 가지가 부수 성과다.

- **Jackson 2가 클래스패스에서 제거됐다.** Flyway 12가 Jackson 2 → 3으로 갈아타면서, 4.0.7에서 api `runtimeClasspath` 에
  Jackson 2 databind 와 3 databind 가 **동시에** 올라와 있던 상태가 해소됐다. 직렬화 라이브러리 두 벌은 나중에
  오류 본문·`Content-Disposition` 같은 계약 지점에서 "어느 `ObjectMapper` 가 잡혔는가"로 번지기 쉬운 배치였다.
- **락파일 재생성 절차를 확립했다.** 증분 `--write-locks` 는 계열이 바뀌는 업그레이드에서 **stale 제약을 남긴다** —
  KGP 2.3이 더 이상 해석하지 않는 `kotlinCompilerClasspath` 의 락 항목이 2.2.21로 남아 2.3.21 요청을 **강등**시켰다
  (`dependencyInsight` 로 확인). 조치: 락파일 5개를 지우고 `clean build --write-locks --no-build-cache` 로 재생성.
  `clean` 과 `--no-build-cache` 가 함께 없으면 태스크가 up-to-date 로 건너뛰어 락파일이 실제보다 **비어 있게** 생성된다.

### 리더 판단이 필요했던 항목 — 둘 다 해소 (2026-08-12)

| # | 내용 | 결과 |
|---|---|---|
| P1-1 | **Spring Boot 4.0.7 vs 4.1.0.** 계획 §3.1은 "4.1 계열 후보"라 적었으나 4.0.7을 골랐다 (2차 리뷰 K-3 = 교차 T-5, 마감 Phase 2 착수 전) | **해소 — 4.1.0으로 올렸다.** 되돌릴 이유를 찾지 못했다(코드 0줄, 이동·폐기 좌표 0건, 테스트 75건 유지, 메이저 상승인 Flyway 12는 별도 검증). 되돌리는 비용은 시간에 비례해 커지므로 판단을 미루지 말라는 리뷰 권고를 따랐다 |
| P1-2 | CI parity 비교 단계가 **종료 코드 2를 통과 처리**한다 (마감 Phase 4 종료 시) | **해소 — 사면 자체가 제거됐다.** 아래 사실 정정 참고 |

### Phase 1에서 손대지 않은 것 (지시대로 보류)

- ~~**U-1**(미처리 500 응답의 CORS 헤더) — 리더 판단 전이라 **CORS 자체를 설정하지 않았다**~~
  → **2026-08-12 갱신**: CORS 는 구현했다(리뷰 C-3). **U-1 경로에는 헤더를 붙이는 코드도 떼는 코드도 넣지 않았고 테스트로도 고정하지 않았다.** 다만 `CorsFilter` 가 체인 앞에서 헤더를 쓰므로 **기계적으로 "개선" 쪽 값이 나온다**(측정: 미처리 500 에 `Access-Control-Allow-Origin`·`Expose-Headers` 있음)
  → **2026-08-12 종결**: 리더가 그 값을 **확정**했다(개선 수용, 사용자 승인). 계약 파일과 changelog 에 "의도된 차이"로 기록됐고, parity 대조에서 이 헤더 불일치는 차단 사유가 아니다. `kotlin-implementer` 는 이 결정을 **테스트로 고정**할 것 — 현재 이 경로에는 긍정 단언도 부정 단언도 없다
- ~~검증 실패(422) 응답의 `detail` **배열** 형태~~ → **2026-08-12 구현**. 상태 코드 422 + `[{loc,msg,type}]`, `rejectedValue`·`input`·`ctx` 미노출을 HTTP 경계 테스트로 고정. **`msg`/`type` 문자열은 Pydantic 과 바이트 동일할 수 없다**(검증 엔진이 다르다) — 계약이 동결한 것은 상태 코드·키 구성·입력값 부재이고 문구는 그 아래라는 판단이며 `contract-keeper` 확인이 필요하다
- ~~`GlobalExceptionHandler` 의 HTTP 경계 검증~~ → **2026-08-12 이전 완료**(리뷰 C-2). `@WebMvcTest`+MockMvc + 테스트 전용 `ErrorProbeController`. Phase 3 는 이 위에 **실제 엔드포인트의** 401·404·409·422·500 을 얹으면 된다
- **성공 응답의 캐시 금지 헤더는 Phase 3 에서 `ResponseEntity` 에 붙여야 한다** — 컨트롤러가 `HttpServletResponse` 에 직접 쓰면 예외가 나도 그 헤더가 오류 응답에 남는다(Python 과 반대 거동). 서블릿 API 에 헤더 삭제가 없고 `response.reset()` 은 CORS 헤더까지 지워 대안이 되지 못한다. 이 제약을 `GlobalExceptionHandler` KDoc 에 적어 두었다
- `spring-boot-starter-validation` 미추가 — 의존성·락파일·빌드 스크립트가 동시 작업 중이라 열지 않았다. 그래서 `handleHandlerMethodValidationException` 은 **미검증**이다. Phase 3 에서 입력 상한과 함께 회귀 고정
- `app/`·`tests/`·`frontend/`·`scripts/`·`.claude/`·`contracts/` — 읽기만 했다
  (이후 같은 라운드에서 `contract-keeper` 가 자기 소유 파일인 `contracts/easy-doc-v1.yaml` 과
  `.claude/skills/api-contract-freeze/SKILL.md` 를 U-1·§2.7-3 반영으로 고쳤다. `app/`·`tests/`·`frontend/` 는 무손상)

---

## Phase 1 종료 판정 — **조건부 종료** (2026-08-12, 리더)

계획 §5 Phase 1의 **명시 종료 조건**("빈 DB·기존 schema snapshot 양쪽에서 Kotlin 앱이 기동되고 `/health` 가 응답함")은
**충족**이며, Boot 4.1.0 업그레이드 후에도 재확인됐다.

**"리뷰 게이트 Critical 0건" 행은 "Phase 2 착수를 막는 Critical 0건"으로 범위를 좁혀 판정하고 충족으로 닫는다.**
근거는 2회차 교차 종합(`reviews/01_skeleton_cross.md` §7.1·§8)이다 — Phase 2는 순수 도메인 로직 포팅이고
종료 조건이 "외부 API·DB 없이 실행하는 parity suite"라서 **HTTP·DB·CORS 경계를 쓰지 않는다.**
실제로 Phase 2를 막던 것은 parity CI(2회차 X-2) 하나였고 그것이 닫혔다. 심각도가 높다는 이유만으로
착수를 막는 것은 과잉이라는 교차 종합의 권고를 채택했다.

**나머지 차단 지적은 마감이 명시된 미결 원장으로 이월한다.** 이렇게 하면 (a) 충족된 종료 조건을 인위적으로
열어 두지 않고, (b) 차단 항목이 마감 없이 사라지지 않으며, (c) Phase 2가 실제로 막히는 한 건만 선행 조건이 된다.

> **조건부인 이유** — Phase 1 표에 `아니오` 2행이 남아 있다: CI가 실제 GitHub Actions에서 도는 것 미검증(첫 push),
> 필수 조치 E의 fixture 산출물 부재(Phase 2). 둘 다 이 저장소 안에서는 닫을 수 없고 마감이 명시돼 있다.

### 이번 라운드에 닫힌 것

| 항목 | 내용과 근거 |
|---|---|
| **C-1** 프레임워크 예외 500 | 상태 코드는 프레임워크에 위임(`ResponseEntityExceptionHandler` 상속)하고 **본문만** `createResponseEntity` 한 곳에서 계약대로 덮는다. `detail` 은 상태 코드의 **표준 사유 문구**만 쓴다 — Spring `ProblemDetail.detail` 은 예외 메시지에서 유도돼 요청 본문 조각이 실릴 수 있기 때문이다. 살아 있는 컨테이너에서 404 · 405+`Allow: GET` · `Accept: application/xml` 에도 200 확인 |
| **C-2** 오류 계약 테스트가 HTTP 경계를 안 봄 | 핸들러 직접 호출 → `@WebMvcTest`+MockMvc + 테스트 소스셋 전용 `ErrorProbeController`(`/__probe`, 운영 JAR 에 없음)로 이전. **새 테스트를 먼저 넣어 8건 실패를 확인한 뒤 고쳤고**(도메인 매핑 12건은 전후 모두 통과 = 새 테스트가 정확히 C-1 범위만 새로 잡았다) → **31건 전건 통과**. 8건 중 1건은 고칠 수 없어 테스트를 철회했고 그 근거가 계약 §2.7-3 재작성으로 이어졌다(위 표 참고) |
| **CORS** (C-3) | `addCorsMappings` 가 아니라 **`CorsFilter`**(order HIGHEST_PRECEDENCE) — Starlette 미들웨어와 같은 **라우팅 밖** 위치여야 404·405에도 헤더가 붙는다(Python 실측 일치). 노출 헤더 `Content-Disposition`·`Location` 을 **preflight·실요청 양쪽에서** 확인. 회귀 테스트 10건 |
| **parity CI** (2회차 X-1·X-2) | 판정 범위를 **"디렉터리 유무"에서 "버전 관리되는 선언"으로** 옮겼다. `backend-kotlin/parity-domains.txt` + Gradle `parityManifestCheck` 양방향 대조(선언 O/산출 X · 선언 X/산출 O · json 0건 전부 빌드 실패) + `parityActualClean`. **exit 2 사면은 제거** — 실측 결과 그 조건은 발생하지 않았고, exit 2는 "선언해 놓고 역방향 산출물을 안 만든" 상태에서만 난다. 사면은 `--only-domain` 부분 검증(exit 3)으로 옮겨 **탐지(Gradle 단계)와 사면(비교 단계)을 다른 CI 단계에 배치**했다 |
| **정본 0개 가드** | `canonical_count == 0` 이면 exit 1. `--list` 가 exit 0 인데 출력만 비는 경로는 `pipefail` 이 못 잡는다 |
| **정본 하한** | `.github/parity-canonical-floor.txt` + **비대칭 검사**(현재 정본 ⊇ 스냅샷). 추가는 자유, **삭제는 차단** |
| **Boot 4.1.0** (P1-1) | Kotlin 2.3.21. **코드 변경 0.** 락파일 재생성 절차 확립(증분 `--write-locks` 는 stale 제약을 남긴다). Jackson 2가 클래스패스에서 제거됨 |
| **U-1** 미처리 500의 CORS 헤더 | **개선 수용**(리더 판정 + 사용자 승인). React 분기 실측 결과 `status = 0` 에 의존하는 화면이 없다 |
| **계약 §2.7-3** | 실행 불가능한 종료 조건을 **"규칙 1(사적 헤더는 `ResponseEntity` 에만) + 단언 A·B"** 로 재작성 |

### 사실 정정 (기존 기재가 틀렸다)

- **`P1-2`**(CI parity 비교 단계가 exit 2를 통과 처리) — **해소됨.** exit 2 사면 **자체가 제거**됐다.
  마감을 "Phase 4 종료 시"로 잡았던 것은 그 완화를 그때까지 유지한다는 전제였는데, 실측 결과 그 사면이
  겨냥한 조건("아직 포팅하지 않았다")은 애초에 exit 2를 내지 않았다 — exit 1을 낸다. 사면할 이유가 없었다.
- **detekt 1.23.8의 내장 Kotlin 파서는 1.9가 아니라 2.0.21이다**(`detekt-parser` POM 실측).
  그리고 **ktlint-cli 1.8.0은 2.2.21을 내장해 이제 컴파일러(2.3.21)와 다르다** — 4.0.7 시절엔 우연히 같아서
  Phase 1 문서에 기재되지 않았다.

### 미결 원장 (마감 명시) — 각 Phase 착수 게이트에서 다시 센다

| 항목 | 마감 |
|---|---|
| Flyway 지문 TOCTOU + Alembic head 미확인 (2회차 codex #3·#4 / 교차 F-3·F-5) — 지문 판정·baseline·migrate 가 각각 별도 연결이고 어떤 잠금에도 덮이지 않는다. `alembic_version` **읽기**는 계획이 금지한 적이 없다(구현자의 자기부과 제약이었다).<br>**[게이트 15 재판정 2026-08-15] 부분 해소 — 충족 아니오.** 원 지적의 TOCTOU 축은 `f9d78e0` 으로 닫혔고(잠금이 판정 구간을 실제로 지킨다는 것이 음성 대조로 실측됨), 남은 4건과 판정 근거는 아래 「게이트 15 후속」 §1 재판정 표가 정본이다 | ~~Phase 3 착수 전~~ **Phase 3 첫 기동/배포** |
| `CoreModuleBoundaryTest` 우회 (2회차 codex #5) — `compileOnly` + 목록 밖 타입이면 통과한다. `api`·`worker` 가 `infrastructure` 를 `runtimeOnly` 로 유지하는지 **단언하는 코드가 0건**이라, `runtimeOnly` → `implementation` 한 글자 변경에 아무 테스트도 깨지지 않는다.<br>**[게이트 15 재판정 2026-08-15] 부분 해소 유지 — 충족 아니오.** `ef7b4a8` 이 경계 강제를 세웠으나 X4·X4b 가 미수정이다. 상세는 아래 「게이트 15 후속」 §1 | ~~Phase 3 착수 전~~ **Phase 3 모듈 추가 시** |
| ~~provenance·external이 **같은 변경 가능 소스를 신뢰** (2회차 codex #2 / 교차 X-3)~~ — **판정 완료 (2026-08-12, 리더).** 아래 상세 | ~~Phase 2 착수 전~~ **판정됨** |
| crypto 음성 케이스가 정본 대조에서 빠짐 (H-3 = 교차 X-4) — `VOLATILE_INPUT_FIELDS` 가 도메인 단위라 crypto 의 `input`(`{key, token}`)이 통째로 빠지고 음성 3건의 `expected` 가 동일하다. **`crypto-tampered.token` 을 쓰레기로 바꿔도 게이트가 닫힌다.** 해법은 같은 파일 안 `argon2` 방식(파생 성질을 `expected` 에 담기)에 이미 실증돼 있다 | Phase 4 종료 전 |
| 계약의 요청 길이 제약 5개가 계약 자신의 422 규칙과 충돌 (F3 = 교차 C-5) — 코드에서 이 다섯은 스키마 제약이 아니라 서비스 계층 규칙이라 422 **문자열** `detail` 인데, 계약은 스키마 실패를 422 **배열**로 못박았다. 셋은 코드보다 엄격하기까지 하다(코드는 정규화 **후** 길이를 잰다).<br>**판정 완료 — `d03e5e8`** (다섯을 서비스 층 규칙으로 확정, `x-request-field-constraints` 신설 — `measured_on` normalized 3 / raw 2. `text`/`edited_text` 비대칭은 `x-open-asymmetry` 로 Phase 4 미결). **판정이 원장에 미반영이던 것을 게이트 15 X14 로 반영한다.** 검증 공백은 `526bfeb`(X-F11~13 신설), 정반대 단언은 `f9d78e0`(X6) 으로 메웠다. **`measured_on: raw` 2필드의 지위는 X13 으로 사용자 판단 대기**(아래 「게이트 15 후속」 §3) | ~~Phase 3 착수 전~~ **판정됨** (raw 2필드 심사만 X13) |
| 계약 multipart `contentType` 제약이 구현에 없음 (F2 = 교차 C-6) — 성실히 구현하면 `.hwpx` 업로드가 깨지고 **그 구현이 contract test 를 통과한다**(`.hwpx` 는 브라우저가 `application/octet-stream` 을 보내고, `application/hwp+zip` 은 내보내기 mimetype 이다) | Phase 4 착수 전 |
| **G-1** `POST /workspaces`·`PATCH /workspaces/{id}` 캐시 헤더 테스트 공백 — Python 기준선에도 없다(계약상 10곳 중 2곳).<br>**판정 완료 — `d03e5e8`** (단순화: Kotlin 계약 테스트가 10곳을 한 번에 덮는다 — Python 기준선에 따로 채우지 않는다). **판정이 원장에 미반영이던 것을 게이트 15 X14 로 반영한다** | Phase 3 (구현 시 집행) |
| ~~계약 §2.7-3 **규칙 1**을 Phase 3에서 **실측**해 성립 확인~~ — **폐기 (2026-08-12 리더 판정, OQ-1).** 규칙 1(사적 헤더는 `ResponseEntity`로만 싣고 필터 금지)이 전역 부착과 정면 충돌해 내려갔다. 이 항목의 자리는 아래 **H-1**이 대신한다 | ~~Phase 3 종료 전~~ **폐기** |
| ~~**H-1** 전역 헤더 필터가 **컨테이너 레벨 응답까지 닿는가 — 미실측**~~ — **실측 완료 (2026-08-12, kotlin-implementer / Tomcat 11.0.22 · Boot 4.1.0).** 원시 소켓 + `@SpringBootTest(RANDOM_PORT)`로 측정. **필터에 닿음**: 핸들러 없는 404 · 415 · 413 · 프리플라이트 OPTIONS 200 · `sendError`→`/error` 503. **필터에 못 닿음 7종**: 요청 대상 금지 문자 400 · 콜론 없는 헤더 줄 400 · 요청 줄 파손 400 · 헤더 상한 초과 400 · `Host` 없음 400 · 알 수 없는 HTTP 버전 505 · 알 수 없는 메서드 405 — 파싱 단계에서 거절돼 **필터 체인이 시작조차 하지 않는다**(배치로 고칠 수 없다). **해결: 계약을 좁히지 않고 강제 수단을 넓혔다** — Tomcat Engine 밸브가 7종 전부를 덮음을 계측 확인, 음성 대조(밸브 제거 시 malformed 3건만 깨짐)까지 돌렸다. 리더 재심 불필요. **후보 원인 ⓐ `shouldNotFilterErrorDispatch()`는 기각** — 그것과 `DispatcherType.ERROR`를 둘 다 기본값으로 되돌려도 헤더가 남는다(필터가 `chain.doFilter` 앞에서 쓰고 Tomcat이 포워딩에서 버퍼만 비운다). ⚠️ **회귀는 원시 소켓으로 유지** — MockMvc로 옮기면 "측정한 것처럼 보이는 통과"가 나온다. ⚠️ **밸브는 Tomcat 결합** — 컨테이너 교체 시 기동 시점에 깨진다(계약 `x-container-coupling`) | ~~Phase 3 종료 전~~ **완료** |
| **H-4** 오류 본문이 **경로를 가리지 않는가** — H-1 실측의 **범위 밖 부수 발견.** `sendError` → `/error` 응답이 `{"detail":…}`가 아니라 Spring `BasicErrorController` 기본 본문 `{"timestamp","status","error","path"}`를 낸다. 계약의 "오류 본문 최상위 `detail` 하나"는 계획 §2.2 **불변식**인데 이 경로가 사각지대였다. **지금은 운영 코드가 `sendError`를 안 불러 안 드러난다 — Phase 3에서 인증 필터가 401을 `sendError`로 내는 것이 가장 흔한 구현이고 그 순간 깨진다**(401은 React `client.ts`의 세션 만료 분기라 화면 동작까지 바뀐다). 계약에 `x-error-body-universality` 신설, 검증 E-1~E-3 = 테스트 X-C7·X-C8. **구현 수단은 규정하지 않는다** — 계약이 정하는 것은 나간 바이트뿐이다. 남은 미측정: 위 7종의 **본문 모양**(E-4 = X-C9) — 이번 실측은 헤더만 봤다. 만족시킬 수 없으면 계약을 좁히지 말고 리더 재심 | Phase 3 종료 전 |
| **H-2** 전역 필터 도입에 따른 **헤더 중복 부착** — 필터와 컨트롤러 `ResponseEntity`가 같은 헤더를 둘 다 실으면 `Cache-Control: no-store, no-store`가 나가 계약의 `const` 제약에 위반된다. 값만 보는 단언은 통과하므로 **개수까지** 단언해야 한다(`header().stringValues(...)`). 필터는 `add`가 아니라 `set` | Phase 3 |
| **문서 spike 재검증** — Phase 0 spike 는 Kotlin **2.2.0** 조합에서 통과한 것이다. 현재 2.3.21이므로 POI 5.4.1·PDFBox 3.0.5·commons-compress 1.27.1 조합으로 **DOCX 동등성 7항목을 다시 확인**해야 한다. 이번에 확인한 것은 좌표 해석과 컴파일뿐이다 | Phase 4 착수 전 |
| DOCX 내보내기 템플릿 동봉 여부 — POI 산출물에 `styles.xml`/`theme` 가 없어 Heading 1 서식이 소실된다 | Phase 4 착수 전 |
| 내보내기 parity 를 **바이트 해시가 아닌 정규화 텍스트로** — zip 컨테이너 바이트는 Python 과 같아질 수 없다(실측 java 434B vs python 348B). `parity-verifier` 합의 필요 | Phase 4 착수 전 |
| `handleHandlerMethodValidationException` **HTTP 경계 미검증** — `spring-boot-starter-validation` 부재. 입력 상한과 함께 회귀 고정 | Phase 3 |
| parity 하네스 배선 주의 — 디렉터리 비교는 `actual_root / fixture_path.relative_to(fixture_root)` 로 짝짓는다(`compare_parity.py:713-715`). 즉 **actual 파일명이 fixture 파일명과 같아야** 한다 — `masking.json` 이나 `kotlin.json` 이 아니다 | Phase 2 배선 시 |
| `resolveAndLockAll` 태스크 도입 · Gradle 플러그인 클래스패스 locking · Gradle 10 deprecation 경고 — 셋 다 기존 상태이거나 별건이며 구현자가 코드에 넣지 않았다 | 승인 대기 |
| **다음 회차 리뷰 focus 를 계약·보안 불변식·테스트 적정성 축으로** — 이번 codex focus 를 5축(모듈 경계·Flyway·`encryption_scheme`·CI 게이트·parity 우회)으로 좁힌 탓에 그 세 축의 Claude 지적 **17건(C-1~C-9, S-1~S-5, T-1~T-6)이 단일 관점 판정으로 남아 교차 검증을 받지 못했다** | Phase 2 리뷰 |
| CI가 실제 GitHub Actions 에서 도는 것 미검증 — YAML 파싱과 로컬 동등 명령만 검증했다. `::error::`/`::notice::` 렌더링, `setup-gradle@v4`, 러너 Docker 위 Testcontainers 는 첫 push 에서 처음 검증된다 | 첫 push |

### 판정 — codex #2 / 교차 X-3 (2026-08-12, 리더)

교차 종합 §7.1은 이 항목을 **"Phase 2 착수 전 필수"**로 놓았고 §8은 "4건을 닫은 시점에 리뷰 게이트 행을 판정"하라고 권고했다. 실제로 닫힌 것은 3건이고 이 판정만 열려 있었으므로 지금 내린다. (직전 기재의 마감 `Phase 2 종료 전`은 리더가 교차 문서보다 느슨하게 옮겨 적은 것이었다 — 정정한다.)

**판정: 이 항목은 "닫을 수 있는 결함"이 아니라 "관리해야 하는 한계"다. 추가 방어를 넣지 않고 현재 상태로 Phase 2에 착수한다.**

근거:

1. **원리적으로 닫히지 않는다.** 지적의 요지는 "생성기와 검증기를 같은 diff에서 함께 약화시키면 못 잡는다"이다. 그런데 무엇을 정본으로 삼든 그 정본도 저장소 안에 있고 같은 커밋에서 함께 바뀔 수 있다. codex 권고대로 manifest를 `BUILDERS`에서 분리해도 **분리된 manifest가 새 정본이 될 뿐**이며, 이번 세션이 그 연쇄를 이미 네 번 겪었다(proof 파일 → fixture 출처 → 도메인 디렉터리 → 정본 목록). 한 칸 더 옮기는 것은 방어가 아니라 이동이다.

2. **이미 세운 것이 "조용한 축소"를 막는다.** `.github/parity-canonical-floor.txt`의 비대칭 검사가 정본 축소를 차단하고(추가는 자유, 삭제는 exit 1 + 사라진 도메인 이름 출력), 하한 파일 자체를 줄이려면 `.github/` diff가 남는다. `01_parity-canonical-floor.md`가 그 성격을 정확히 적었다 — **"이 검사는 축소를 불가능하게 만들지 않고 조용할 수 없게 만든다."**

3. **최종 방어선은 코드가 아니라 리뷰 게이트다.** `codex-review` 스킬이 `.claude/**` 수정을 게이트 면제에서 **뺀** 것이 이 장치다(확장자가 `.md`여도 "문서 수정"이 아니다). 이번 세션의 지적이 전부 하네스에서 나온 것이 그 판단의 값어치를 보였다. 생성기·검증기·정본을 함께 건드리는 커밋은 정의상 하네스 수정이므로 반드시 리뷰를 지난다.

**채택하지 않은 codex 권고와 이유**

- *정본 manifest를 `BUILDERS`에서 분리* — 위 1의 이유로 기각. 다만 **분리 자체가 무해하므로**, Phase 2에서 도메인이 늘 때 `parity-domains.txt`·`parity-canonical-floor.txt`·`BUILDERS` 셋을 한 커밋에서 고치게 되면 그 커밋은 리뷰에서 특별히 다룬다.
- *알려진 손상 산출물을 먹여 exit 1을 단언하는 adversarial 테스트* — **부분 채택하되 별건으로 돌린다.** 이번 세션의 실증(손으로 쓴 proof → 2, 축소 fixture → 1, `runtime: not-kotlin` → 1, 역방향 패딩 → 1, 정본 0개 → 1, 정본 부분 삭제 → 1)이 전부 **일회성 수동 확인**이라 회귀로 고정돼 있지 않다. 지금은 사람이 다시 뚫어야 알 수 있다. 이를 자동 회귀로 만드는 것은 실질 가치가 있으나 Phase 2 착수를 막을 이유는 없다 → 아래 원장에 별건으로 추가.

**Phase 2 착수를 막지 않는 이유**: Phase 2는 순수 도메인 로직 포팅이고, 이 항목이 실제 피해를 내려면 "누군가 하네스와 정본을 함께 약화시키는 커밋을 만들고 그것이 리뷰를 통과"해야 한다. 그 시나리오의 방어는 리뷰 게이트이지 코드가 아니다.

| 별건으로 추가 | 마감 |
|---|---|
| ~~**게이트 우회 시나리오를 자동 회귀로 고정** — 이번 세션에서 수동 실증한 6종(손으로 쓴 proof / 축소 fixture / `runtime` 위조 / 역방향 패딩 / 정본 0개 / 정본 부분 삭제)이 회귀 테스트로 남아 있지 않다. 게이트가 다시 뚫려도 CI가 모른다~~ — **해소 (2026-08-15).** `42f9e20` 이 회귀 6종을 세웠고, 게이트 15 X2 가 연 **배선·CI 축**을 `04ced00` 이 닫았다(본류 회귀 4건 + `ci.yml` 경로 명시). 음성 대조: 본류 호출부 `compare_parity.py:1984`(runtime)·`:2282`(provenance) 를 지우면 **새 테스트만** 빨강, 기존 helper 테스트는 전부 초록 | ~~Phase 3 착수 전~~ **해소됨** |

---

## Phase 2 — 순수 도메인 로직 포팅

계획 문서 §5 Phase 2. 원문 종료 조건: "**외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄.**"

~~**전부 미착수다.**~~ → **2026-08-13 재개발 착수** (커밋 `0c377a5..3934f06`). 아래 표는 판정 기준이며, 근거 없는 `예`는 `아니오`로 취급한다는 규칙이 그대로 적용된다. 리뷰 게이트·판정 기록은 표 아래 「Phase 2 진행 기록 (리더)」 절.
`관련 정본 도메인`은 `dump_parity_fixtures.py --list` 의 11개 중 이 Phase 가 덮는 8개를 배정한 것이다
(나머지 `crypto`·`jwt`·`argon2` 는 Phase 3·4).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 종료 조건 | 충족 | 실행 경로 | 관련 정본 도메인 | 근거 | 미해결 항목 | blocked-by | 마지막 갱신 주체 |
|---|---| --- |---|---|---|---|---|
| 개인정보 마스킹 포팅 (`app/privacy/masking.py`) | 예 | `ci:kotlin` | `masking` | `core/privacy/Masking.kt` + 생산자 `core/src/test/.../privacy/MaskingParityTest.kt`. **parity 실측(2026-08-13): 성질 31건·단언 114개 충족**(exit 3 = 부분 검증 통과). **privacy-gate 판정 X-2 반영** — 유니코드 인식 패턴 안의 ASCII 전용 리터럴 두 종류를 닫았다(종류 A: RRN 성별코드를 `Character.digit` 값 판정으로 / 종류 B: 구분자 집합을 하이픈 6종·공백 6종으로, `\s` 미사용). 음성→양성 실측: 새 케이스 25건 중 **17건이 수정 전 패턴에서 실패**하고 수정 후 전건 통과(나머지 8건은 과잉 마스킹 회귀 가드라 수정 전에도 통과해야 정상). 참고 갈림 1건(`masking-known-gap-rrn-fullwidth`)은 이 개선이 만든 것이며 `parity/reference-ledger/masking.json` 에 기록됨 | ~~fixture 쪽 후속은 `parity-verifier` 몫~~ → `3934f06` 으로 처리됨(31→57 케이스). **[리더 재판정 2026-08-14, 게이트 08 C-12] 예 → 아니오.** 게이트 08 교차 종합이 차단 ①사건 **C-01** 을 세웠다 — 보충 평면 숫자(1 code point = surrogate 2문자를 `singleOrNull()` 이 거부)와 복합 카드 구분자(` - `·NBSP+하이픈+NBSP)가 **실측 items=0 으로 통과**(codex B-1, 컴파일된 core 에 실입력 투입). 차단 사건이 열린 행을 `예` 로 둘 수 없다. `EXPECTED_MET_YES_KEYS` 에서 이 행을 같은 커밋에서 제거했다 — 그 diff 가 판정 범위 변경의 리뷰 신호다. ~~재판정 경로: privacy-gate 묶음 판정 → 구현 → fixture 반영~~ → **전부 완료**(§4-ter 판정 → `c6e65a0` 구현 → `1301367` 동결, §4-sexies 해제). **[사유 갱신 2026-08-14, 게이트 09 Z-원장 지적]** 이 행이 지금 `아니오` 인 이유는 닫힌 C-01 이 아니라: ① 판정 1 해제조건 4(`docs/golden/` 실문서 마스킹 건수 대조) 미실시 ② 게이트 09(이 배치 자체의 리뷰) 미종결 — 닫힌 사유를 남기면 다음 사람이 그것만 보고 `예` 로 뒤집는다 | privacy-gate (해제조건 4) · 게이트 09 종결 | leader (2026-08-14) |
| 텍스트 정규화·제어문자 제거 포팅 | 예 | `ci:kotlin` | `text` | `core/text/TextNormalization.kt`·`TextWhitespace.kt` + `TextNormalizationTest` (커밋 `0c377a5`) — **코드·단위 테스트는 ci:kotlin 에서 돈다.** 08 리뷰 m-4(원장 과소 신고) 반영해 사실만 기재 — 충족이 아니오인 이유는 오른쪽 | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92` fixture · `cd23aec` 생산자+선언, 8/8 값 일치). 남은 것: 게이트 11 종결 | 게이트 11 | leader (2026-08-14 2차) |
| 프롬프트 렌더링과 동적 어려운 말 목록 포팅 | 예 | `ci:kotlin` · `ci:quality` | `prompts` | `core/easyread/Prompts.kt`·`DifficultWords.kt` + `PromptsTest`·`PromptTextSnapshotTest`·`PromptInjectionGuardTest` (커밋 `81f1d84`) — 스냅샷은 07 리뷰가 Python 독립 재추출로 **전건 일치** 확인. 08 리뷰 m-4 반영.<br>**[게이트 15, 2026-08-15] 승격 유지 — 근거의 뒤 조각이 배선됐다.** X-9 마감의 원문은 *"스냅샷 생성기 커밋 **+ CI 재생성 diff**"* 두 조각이었고, 뒤 조각은 도달 0이었다(cross X1 — Claude P-A·codex C15-5 **독립 합의**). `04ced00` 이 `ci.yml` **quality 잡**에 `dump_python_snapshots.py --check` 스텝을 배선해 닫았다(생성기 실물 경로 `.claude/skills/migration-safety-gate/scripts/dump_python_snapshots.py` — JDK 가 아니라 `uv` 환경이 필요해 kotlin 잡이 아니다). **리더 판정: 마감 축소가 아니라 CI 배선 완성으로 승격을 유지한다.** 삭제 탐지력 자체의 회귀는 `tests/test_python_snapshot_guard.py`(경로 명시 — `4cba492`) | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, 왕복 재마스킹으로 X-7 가드 무우회, 4/18 값 일치 — 문면 전문의 정본은 스냅샷). ~~X-9~~ → **해소**(`ef7b4a8` 생성기 + `04ced00` CI 배선). 게이트 11 종결 · 게이트 14 재실행분 교차 종합 | 게이트 11 · 게이트 14 교차 종합 | leader (2026-08-15, 게이트 15) |
| 스타일 규칙 포팅 (`app/easyread/style_rules.py`) | 예 | `ci:kotlin` · `ci:quality` | `style` · `style-tables` | `core/easyread/StyleRules.kt`·`GlossCollision.kt` + `StyleRulesTest`·`StyleRuleDataSnapshotTest`·`GlossCollisionTest` (커밋 `0c377a5`) — 사전 246(순서 포함)·파생 표·패턴 글로스 123, 07 리뷰 독립 재추출 **전건 일치**. 08 리뷰 m-4 반영.<br>**[게이트 15, 2026-08-15] 승격 유지 — 근거의 뒤 조각이 배선됐다.** 위 프롬프트 행과 같은 근거다(`04ced00` — `ci:quality` 의 `dump_python_snapshots.py --check`). 이 행에 걸린 파생 키 `GLOSS_COLLISION_PATTERN_GLOSSES`(123 항목) 단독 삭제가 **재생성 diff 0** 이던 것도 같은 커밋이 닫았다(무조건 재생성 → `[갈림]`·exit 1) | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, `StyleRuleKind` 기계가독 필드 신설 — 계약·프롬프트 문안 불변 3중 확인). ~~X-9~~ → **해소**(`ef7b4a8` 생성기 + `04ced00` CI 배선). ~~C-3·C-4~~ → **해소**(`f73b8bd` 비교기 강화). 게이트 11 종결 · 게이트 14 재실행분 교차 종합 | 게이트 11 · 게이트 14 교차 종합 | leader (2026-08-15, 게이트 15) |
| 보정 채택 판정 포팅 | 예 | `ci:kotlin` | `repair-adoption` | `application/conversion/RepairDecision.kt`(판정식) + `ConvertDocumentUseCase.kt`(오케스트레이션) + 생산자 `application/src/test/.../conversion/ConversionParityTest.kt`. **parity 실측(2026-08-13): 성질 25건·단언 75개 전건 충족**. 호출 상한 2는 사후 계수가 아니라 **직선 코드 + `CompletionBudget`** 으로 강제한다 — 관대한 fake(응답 10건)를 줘도 정확히 2회에서 멈추고 8건이 남는 것을 단위 테스트가 단언. 전송 재전송과 완성 요청을 **따로 계측**(`FakeLlmProvider.transportAttempts`) | **갈림 후보 2건은 여전히 판정 대기** — ① 채택 판정식이 본문·팩트 손실을 보지 않는다(리더) ② 계약 `failure_code` 가 enum 대신 '예외 클래스명' 규칙이라 Kotlin 은 요구 수준 이름(`truncated`/`empty_result`/`provider_error`)을 쓴다(`contract-keeper`, §9-E) | - | kotlin-implementer (2026-08-13) |
| placeholder 보존 검사 포팅 | 예(변환 시점 산출만) | `ci:kotlin` | `repair-adoption` · `export` | `ConvertDocumentUseCase` 가 **채택된 최종 본문 기준**으로 산출하고 예외로 막지 않는다. `missing-placeholders-*` 4건 포함 25건 충족(2026-08-13). 내보내기 시점의 **복원**(`export`)은 별건이며 미포팅 | ~~**확인 필요**: `missing_placeholders` 산출이 어느 정본 도메인에도 미배정~~ → **2026-08-13 배정 판정 완료.** 도메인을 **신설하지 않고** `repair-adoption` 에 케이스 4건(`missing-placeholders-preserved`·`-dropped-reported`·`-basis-is-adopted-text`·`-partial-reports-only-lost`)으로 넣었다. 근거 4가지와 도메인 이름을 바꾸지 않은 이유는 `02_parity-verifier_conversion-spec.md` §4. `export` 는 내보내기 시점의 **복원**(`restore_placeholders`)을 그대로 맡는다 — 같은 자리표시자를 다루지만 다른 함수·다른 시점이다. **남은 미해결은 포팅 자체**(Kotlin 미구현) | - | parity-verifier (2026-08-13) |
| 내보내기 파일명·`Content-Disposition` 생성 포팅 | 아니오 | `ci:kotlin` | `export` | **배선 완료(2026-08-15)** — `dcfe04e`(core `Export.kt`: 파일명 정제·RFC 5987 ext-value·TXT 바이트, 복원은 `restoreForExport` 정본 유지) + `0c8516a`(단언 117개 ready — 값이 아니라 경계로) + 생산자·선언(`2839ec8`). 생산자 12/12 정본 일치. 바이트 해시는 TXT `content_sha256_hex` 하나뿐(zip 컨테이너 없음 — 미결 원장 준수) | 게이트 12 잔여: #4(파일명 over 방향 차단 단언 0 — 전부 대체 이름이어도 원장 갱신만으로 초록, parity-verifier 수정 중) · C-6(`take(80)` 선언 대 도달, kotlin-implementer 수정 중) | 게이트 12 잔여 | leader (2026-08-15, #8 집행) |
| LLM 응답 후처리 포팅 | 예 | `ci:kotlin` | `postprocess` | `core/easyread/Postprocess.kt` + `PostprocessTest` (커밋 `81f1d84`) — 후처리 29건(`keep_*` 9) 07 리뷰 독립 재추출 **전건 일치**. 08 리뷰 m-4(다섯 조각 내내 아무도 이 행을 안 건드림 — 과소 신고 방향) 반영 | ~~fixture 미생성·미선언~~ → **배선 완료**(`ad6ab92`·`cd23aec`, 8/12 값 일치·빈 결과 처분 경계 명시). 남은 것: 게이트 11 종결 | 게이트 11 | leader (2026-08-14 2차) |
| Python/Kotlin 공용 JSON fixture 생성 (`parity/fixtures/`) | 예 — 8/8 생성 · 8/8 ready | `local:uv run python .claude/skills/python-kotlin-parity/scripts/dump_parity_fixtures.py --domain masking --domain repair-adoption` | 전 도메인 | `parity/fixtures/masking/masking.json` **생성됨** (**83 케이스·단언 299개** — 2026-08-14 6차 개정 기준. 14 → 22(유니코드) → 31(보이지 않는 문자) → 57(표기 변형) → 69(구분자 문법) → **83**(TAB 전환·경계축)). 재현성 실측: 두 번 뽑아 `generated_at`(벽시계) 외 **바이트 동일**. 확장 시 기존 14 케이스의 `id`·`input`·`expected`·순서·`description` **전부 불변** 확인. 정본 대조(`provenance_problems`) 통과 — 새 케이스 기대값 손편집도 exit 1(`정본과 다르다`). 검출 실증 7종 — 정상 산출물 exit 3 / 값 1건 변조 exit 1 / 케이스 1건 누락 exit 1(`미실행`) / 자리표시자 번호 어긋남 exit 1 / `runtime` 비-kotlin exit 1 / **fixture 기대값 손편집 exit 1** / **fixture 케이스 삭제 exit 1**. **유니코드 확장 검출 실증 5종**(JVM 스탠드인, 대조군이 실제 `mask_text`와 22건 전건 일치함을 먼저 확인) — `java-default`(`\d`=`[0-9]`) exit **1**/6건 지목 / `java-ucc`(`UNICODE_CHARACTER_CLASS`만) exit **1**/1건 / `rrn-widened`(`[1-8]` 확대) exit **1**/1건 / `pre-strip-control`(마스킹 전 제어문자 제거) exit **1**/2건 / `faithful` exit **3**. **네 결함 모두 확장 전 14 케이스로는 0건 검출** — 공백이 실재했음의 증거다. CI 셸 실측 4종 — 현재 상태(선언 0개) exit 0(미가동 경고) / 선언 masking + 산출물 없음 exit **1** / 선언 masking + 산출물 정상 exit 0(부분 게이트 notice) / 산출물 틀림 exit 1. 명세는 `02_parity-verifier_masking-spec.md`(§3.3·§6.4·§6.5).<br>**2026-08-13 `repair-adoption` 생성 — 추출 목록 §G(변환 오케스트레이션) 집행.** `parity/fixtures/repair-adoption/repair-adoption.json` **25 케이스·단언 75개**(`equals_field` 64 · `equals_derived` 8 · `at_most` 3), 생성기 케이스 7건에서 **18건 확장**. 확장 안전성 실측 — 기존 7건의 `id`·`description`·`input`·`assert`·`reference` **전건 동일**·삭제 0·상대 순서 보존(확장 전 fixture를 `git show HEAD:` 생성기로 따로 뽑아 대조), 재현성 2회 덤프 `generated_at` 외 **바이트 동일**, 정본 대조 손편집 exit **1**(`$.assert: 길이 4 != 3`), `uv run pytest` **1061 passed/68 skipped** 무손상, ruff·`mypy . .claude` 통과. **검출 실증 7종**(대조군 먼저: 요구사항대로 구현한 `faithful` 과 현행 `ConversionService` 를 그대로 돌린 `python-verbatim` 이 25건 **전건 동일 산출** → 둘 다 exit **3**) — `always-reject-repair`(보정을 절대 채택 안 함) exit **1**/8건 · `all-or-nothing-placeholder-guard` exit **1**/1건 · `repair-failure-propagates`(보정 실패를 변환 실패로) exit **1**/3건 · `transport-counted-as-calls` exit **1**/1건 2단언 · `missing-from-first-draft` exit **1**/1건 · `repair-loop` 엄격 provider exit **1**(하네스 사망→산출물 없음)/관대 provider exit **1**/4건. **커버리지 검토가 자체 구멍 1건을 찾았다** — `missing-from-first-draft` 가 확장 24건을 통과(exit 3)해서 `missing-placeholders-basis-is-adopted-text` 를 추가로 넣어 닫았다(케이스 수 증가 ≠ 커버리지 증가의 실증). **`missing_placeholders` 도메인 배정 판정 완료** — 신설하지 않고 `repair-adoption` 에 케이스 4건으로 넣었다(근거: 하네스 1회 실행이 둘을 동시에 산출 · 보정 채택 결과가 유실 목록의 기준 본문 · `export` 는 복원이라 다른 함수 · 신설 비용 > 실익). 인벤토리 **§3.1** 신설(CNV-01·02·04 판정식)·**§9-E** 추가(실패 코드 문자열 정본 부재 → `contract-keeper`). 명세는 `02_parity-verifier_conversion-spec.md`.<br>**2026-08-14 masking 4차 개정 — `privacy-gate` 판정 1(`07_privacy-gate_masking-verdicts.md` §1) 집행.** **31 → 57 케이스 / 단언 114 → 214개.** 판정서가 실측으로 가른 두 결함(**종류 A** 성별코드 ASCII 리터럴 · **종류 B** 구분자 ASCII 리터럴) 중 **종류 B가 fixture에 0건**이었다 — 전각 하이픈 한 글자로 주민등록번호·카드번호가 **양쪽 다** 통과하는 상태였고, 이 도메인은 그때 '가장 잘 덮인 도메인'으로 분류돼 있었다. 신설 26건 = 종류 A 2 + 구분자 변형 RRN 9·CARD 9 + 성별코드 9·0 거부 가드 4(ASCII 2·전각 2) + 줄 갈림 가드 2(복귀 RRN·개행 CARD). **`known_gap` 1건을 검출 단언으로 전환해 이 도메인의 `known_gap`이 0건이 됐다** — 옛 제외 사유 *"Kotlin에 Python보다 넓은 구현을 요구하게 되므로"* 는 2026-08-12 재개발 전환으로 실효했고, *"판정 범위 밖"* 은 판정서 §1.3이 받아 **범주 포함**으로 닫았다. `input`·`reference` 를 그대로 두고 단언만 바꿔 `masking-rrn-unicode-digit-fullwidth` 로 이월(실측: `input 동일 True`·`reference 동일 True`). **참고 갈림 21건** — 요구사항이 현행 Python보다 넓어 갈리는 것이 정상이므로 `reference_divergence: "expected"` 로 선언하고 원장 `parity/reference-ledger/masking.json` 에 기록했다. 기준이 어느 쪽인지는 fixture `requirement` 헤더가 선언한다. 원장 절차 3단계 실측 — `--record-reference` exit **4**(`원장 변경 2건 / 원장 지적 22건 … 게이트를 닫지 않는다`) → diff 커밋 → 플래그 **없이** 재실행 exit **3**. 선언이 면제가 아님도 실측했다(선언 21건을 두고 원장 없이 돌리면 exit **1**). 확장 안전성 — 기존 30건 `id`·`description`·`input`·`assert`·`reference` **전건 동일**·상대 순서 보존, 사라진 기존 케이스는 전환된 1건뿐, 재현성 2회 덤프 `generated_at` 외 **바이트 동일**. **검출 실증 6종 — 스탠드인이 아니라 `./gradlew parityHarness` 가 만든 진짜 Kotlin 산출물에 회귀를 주입했다**(대조군 = 원본, exit **3**·불충족 0) — `ascii-separators-only`(= 현행 Python) exit **1**/18건 · `gender-ascii-literal` exit **1**/3건 · `gender-no-value-check`(넓히고 값 판정 누락 → 과잉) exit **1**/4건 · `separator-includes-newline`(`\s` 뭉뚱그림) exit **1**/3건 · `card-separators-forgotten`(구분자 집합이 두 벌로 갈라짐) exit **1**/9건. 마지막 것이 **RRN·CARD 대칭 배치가 없으면 아무것도 안 잡힌다**는 실증이다. Kotlin 재생성 `./gradlew parityHarness parityManifestCheck` exit 0(57건·`runtime: kotlin`), 선언 2도메인 동시 exit **3**(82건·289단언·불충족 0), `uv run pytest` **1061 passed/68 skipped** 무손상, ruff·`mypy . .claude`(129 files) 통과. **X-15(스냅샷 내 값 동일성 단언)** — 프롬프트 스냅샷 16건(system 6·user 5·repair 5)을 전수 훑어 표기 변형 29종·자리표시자 모양·Python 재계산 불일치를 검사한 결과 **전건 안전(잠복)**이고, 검사가 벙어리가 아님을 음성 대조로 확인했다(전각 하이픈 합성 입력 → `0xff0d` 검출). **코드는 고치지 않았다** — `_note` 는 생성기가 없는 생성물이라 손편집이 규약 위반이고(X-9), 단언 자체는 Kotlin 코드다. **X-9와 한 조각으로 처리하도록 `kotlin-implementer` 에 이관**하고 이 레인은 경계만 선언했다: **마스킹 요구 성질의 정본은 `parity/fixtures/masking/masking.json` 이고 프롬프트 스냅샷은 마스킹의 정본이 아니다.** 명세는 `02_parity-verifier_masking-spec.md` **§8**.<br>**2026-08-14 masking 5차 개정 — `privacy-gate` 판정 6(§4-ter · C-01·C-10·C-11) 집행.** **57 → 69 케이스 / 단언 214 → 255개 / 참고 갈림 21 → 29건.** 4차 개정이 구분자 **문자 집합**만 넓히고 **반복 상한**을 두지 않은 것이 반대 방향 결함을 만들었다(리뷰 08 Y-2 → C-10) — 열 맞춤 공백이 인접 칸의 두 숫자열을 결합 마스킹한다. 판정문이 준 유한 문법 `SEP := (?: SPACE? HYPHEN SPACE? \| SPACE? )` 하나가 과잉(C-10)과 남은 누락(C-01②)을 **동시에** 닫았고, 가르는 기준은 문자 종류가 아니라 **개수**다(자리당 0~1개 = 구분자, 2개 이상 = 정렬). 동결 12건 = 12탐침 신규 8(나머지 4는 기존 케이스가 담당) + 보충 평면 양성·음성 2(C-01① — 정규식은 코드포인트로 세고 가드는 UTF-16 `Char` 로 세던 **정합성** 결함) + VT·FF 비결합 2(C-11). `rrn-space-one`(가림)과 `keeps-rrn-space-two`(안 가림)가 **경계 짝**이다. **기존 57건은 하나도 뒤집히지 않았다** — 문법이 좁아지는 변경이라 전수 훑어 '자리당 공백 2개 이상' 입력이 **0건**임을 먼저 확인했다. 참고 갈림 8건 추가, 그중 **4건은 처음으로 `Python 이 과잉 마스킹하는` 방향**이다(`keeps-rrn-space-two/five` · `keeps-vt/ff-split-digits`) — 요구사항이 Python 보다 넓기만 한 게 아니라 **좁기도 하다**. **검출 실증 5종 — 수정 전 문법을 진짜 Kotlin 산출물에 주입**(대조군 exit **3**·불충족 0) — `pre-fix-unbounded-repetition` exit **1**/4건 · `pre-fix-char-gender-guard` exit **1**/1건 · `pre-fix-single-char-separator` exit **1**/3건 · `pre-fix-vt-ff-folded` exit **1**/2건 · `sep-not-shared-with-card` exit **1**/1건(해제조건 2 `SEP` 상수 공유를 **구현 밖에서** 검증하는 자리). **판정문 문구 불일치 1건을 회신 요청으로 올렸다** — §4-ter.4 조건 5가 *"2개 이상은 `absent`, 0~1개는 `present`"* 로 적었는데 이 하네스 검사 어휘로는 **반대**다(`absent` = 가려졌다). 정본인 §4-ter.2 12탐침 표의 `기대` 열을 따랐고 **판정문 파일은 건드리지 않았다**(privacy-gate 소유). 명세 §9.2.<br>**C-02(게이트 08 차단 ②장치) 집행** — parity CI 가 `declared_count=0` 에서 경고 + exit 0 이던 것을 닫았다. `.github/parity-declared-floor.txt` **신설**(선언 하한, 정본 하한과 같은 비대칭 검사) + 선언 0개를 **실패로 승격**. **만드는 중에 도달 0 을 스스로 잡았다** — 0개 검사를 하한 검사 뒤에 두었더니 어떤 경로로도 도달하지 않아 진단이 사라졌고(동작은 exit 1 이나 원인 문구가 다름), 순서를 앞으로 옮겨 각 검사가 고유 진단을 갖게 했다. **CI 셸 음성 대조 7종**(step `run:` 블록 212줄을 그대로 추출해 저장소 사본에서 실행) — 현재 상태 exit **0** / 선언 0개 exit **1** / 선언 1개로 축소 exit **1** / **선언+생산자 동시 제거 exit 1**(C-02 가 요구한 음성 테스트. 이전에는 Gradle `parityManifestCheck` 가 **불일치**만 봐서 둘을 함께 지우면 아무 데서도 안 걸렸다) / 하한 파일 삭제 exit **1** / 하한 비움 exit **1** / 하한까지 함께 축소 exit **0**(설계된 탈출구 — `.github/` 를 고쳐야 하므로 조용할 수 없다). 상세는 `01_parity-canonical-floor.md` **§7**.<br>**2026-08-14 masking 6차 개정 — `privacy-gate` 판정 8(§4-septies · K-1+M-06, 리더 승인) 집행.** **69 → 83 케이스 / 단언 255 → 299개 / 참고 갈림 29 → 32건 / `known_gap` 0 → 2건.** **게이트가 `masking-rrn-tab` 하나로 red 였다** — 결함이 아니라 승인된 **방향 전환**이고 fixture 가 옛 요구를 들고 있었다(TAB 이 `SPACE_CHARS` 에서 빠졌다). 그 자체가 "fixture 가 요구사항의 정본"이라는 규약이 작동한 증거다 — 방향이 바뀌면 fixture 가 먼저 빨개진다. **TAB 전환 4건**: `masking-rrn-tab` → `masking-keeps-rrn-tab`(입력 불변·방향만 뒤집음, `keeps-` 접두는 명명 규약) + 두 열 RRN·표 4열 카드·금액 4열 신규 3건(표 4열이 통째로 카드번호가 되던 과잉이 닫힌 자리). **이 전환은 누락 방향이고 그 대가를 케이스 설명에 명시했다** — `900101<TAB>-<TAB>1234567` 은 이제 안 가려지며 `keeps-rrn-space-two`(공백 2칸)와 **같은 자리**다. TAB 만 예외로 두면 "어느 조판 문자로 벌어졌는지에 따라 결과가 갈린다"가 되살아난다. **경계축 12케이스 동결** — 접기는 분리축(`SEP` 개수)에는 영향이 없고(폭 0 문자는 폭을 만들지 않는다) **경계축(lookaround)에서만 갈린다**(합집합이라 한쪽만 성립해도 가린다 → 폭 0 문자 **한 개**가 "긴 숫자열의 일부"라는 거부 근거를 무효화. 1,120조합 중 90건, 90/90 한 종류). **양성 3 · 음성 짝 3 을 반드시 인접 배치**했고(짝의 두 입력은 폭 0 문자 하나만 다르다) 판정 사유를 fixture 서술에 그대로 남겼다 — 따로 두면 다음 사람이 음성만 보고 "경계 검사가 있다"고 읽어 합집합을 지운다. 교차 3건(가시 0·1칸 가림 / 2칸 안 가림) + 비접기 경계 3건(CR·VT·FF)은 5차 개정분이 담당. **감수한 과잉 표면 2건을 `known_gap` 으로 기록** — `2021 2022 2023 2024`(연도 4열)가 카드번호로 가려지고 5열이면 앞 4개만 가려진다. **줄일 수 없는 모호성**이라(`1234 5678 9012 3456` 이 표준 표기라 공백 한 칸을 빼면 진짜 누락) `privacy-gate` 가 **판정하지 않고** 넘겼고 **교차 검증이 없다**(실측 1건). `absent` 를 걸면 과잉을 요구사항으로 굳혀 카드 패턴을 좁히는 개선이 회귀로 잡히고, `present` 를 걸면 판정되지 않은 방향을 이 레인이 대신 정하는 것이라 **어느 쪽도 단언하지 않았다**. ⚠ 이 자리는 "판정 요청을 받고 명시적으로 판정하지 않기로 한" 상태라 명세 §2.4 의 `known_gap` 정의("아직 판정을 요청하지 않은 자리")와 어긋난다 — 숨기지 않고 §10.3 에 적었다. **M-08(fixture 중복 입력 미검출) — 검출을 넣었다.** 같은 입력은 같은 산출물을 내므로 두 케이스가 같은 것을 두 번 재고, 성질은 안 늘면서 케이스 수만 늘어 **커버리지 대리 지표를 부풀린다**(리포트의 `성질 판정 N건`). 손편집은 정본 대조가 이미 막으므로 이 검사가 받는 것은 **생성기에 중복이 들어오는 경로**다. 현재 두 도메인 중복 **0건**(예방적). 음성 대조 — 생성기에 같은 입력 케이스를 주입하면 exit **1**(`입력이 masking-empty 와 같다`), 복원 후 exit **3**. **Z-7(C-02 음성 대조가 1회성·대리 경로) — 갈라서 처리했다.** 상시 테스트로 구현한 것: 가드 구간(정본·선언 하한, 선언 0개) → `tests/test_parity_ci_gate.py` **7종·1.3초**, `ci.yml` 을 **매 실행 다시 읽어** step 을 뽑으므로 블록 드리프트가 잡힌다. **테스트 자신의 음성 대조**도 했다 — 선언 하한 대조를 무력화하면 **2건 실패**, 되돌리면 7 passed. 대리 경로로 **남는 것**(한계 선언): ① 워크플로 배선(step 이 어느 job 에 있는지·`if:` 조건·job 이 도는지) ② 러너 환경(ubuntu bash·`uv`) ③ 가드 뒤 비교 구간(`parity/actual/` 이 `.gitignore` 대상이라 있는지에 따라 결과가 달라지는 테스트는 회귀로 못 쓴다). 상세는 `01_parity-canonical-floor.md` **§8**.<br>**2026-08-14 게이트 10 리더 판정 집행 — 케이스 정체성 장치(J-1+J-3) + 잔여 도메인 fixture(J-4).**<br>**① 케이스 정체성 장치.** `.github/parity-case-floor.txt` 신설 — 하한을 **개수가 아니라 케이스 id** 로 둔다(`86c6a99` "개수를 정체성으로 바꾼다"를 그대로 따름). 비대칭: 추가 자유·**삭제와 개명 차단**. **개명이 실증이 됐다** — 보류 케이스 2건 id 를 `known-gap-*` → `deferred-*` 로 바꾸자 게이트가 잡았고 메시지가 `83건을 요구하는데 fixture 에는 83건이 있다` 였다(**개수는 같은데 정체성이 갈린 상태** — 개수 하한이었다면 통과). **`known_gap` → `verdict_pending` 탐지형 전환**(R-4): `reason`·`owner`·`deadline`·`referred_by` **필수**, 옛 키가 남아 있어도 차단. 판정 수에서 **분리**(`성질 판정 106건 / 판정 보류 2건`) + 리포트에 소유자·기한·회부 표. **음성 대조가 구멍을 하나 찾았다** — 마커만 떼면 케이스가 조용히 `성질 판정` 수로 넘어가는데(106→108) 아무것도 안 막았다. 하한 줄에 ` !deferred` 꼬리표를 붙여 **보류 상태까지** 기억하게 해 닫았다. **음성 대조 5종**: 정상 exit 3 / 개명 exit **1** / 케이스 삭제 exit **1** / `owner` 빈 값 exit **1** / 마커 제거 exit **1**. **§2.4 정의 어긋남(직전 회차에 내가 신고) 해소** — 마커의 뜻이 "아무도 안 봤다"에서 **"열린 결정의 소유권"**으로 바뀌어 세 상태(안 봤다/회부돼 대기/회부돼 판정 유보)가 필드로 갈린다. 상세 `02_parity-verifier_case-identity.md`.<br>**② 잔여 도메인 fixture.** `style`·`style-tables`·`prompts`·`postprocess` 를 **pending → ready** 로 올렸다(`text` 는 이미 ready). **fixture 8도메인 · 케이스 151 · 단언 443**. 도메인마다 **판정하지 않는 것**을 먼저 갈랐다 — `style` 은 문장 분리 경계를 판정하지 않고(산출물이 보고한 `sentences` 에 규칙만 다시 적용. 유도 규칙 `style_length_rule`·`style_comma_rule` 신설, 정책 상수 50·2 는 **비교기가 자기 힘으로** 들고 있다), `prompts` 는 **문면 전문을 판정하지 않는다**(그 정본은 프롬프트 스냅샷 — X-15 경계를 반대 방향으로도 지킨 것), `postprocess` 는 빈 결과의 **처분**을 판정하지 않는다(`repair-adoption` 몫). `style-tables` 는 정책 상수만 값으로(`equals_field`) 잡고 큐레이션 표 7종은 포함 관계로(`contains_all`) 봐 **개선이 회귀로 잡히지 않게** 했다. **직전 회차에 내가 넣은 M-08 중복 검사가 나를 먼저 물었다** — `style-tables` 두 케이스의 `input` 이 똑같이 비어 있어 같은 산출물을 두 번 재고 있었고, 한 건으로 합쳤다. **`prompts` 재현성 결함도 이때 드러났다** — 난수 문서 id 가 `reference` 에 들어가 매 덤프 fixture 가 달라졌다(정본 대조가 **영구히 깨지는** 상태). 생성기에서 `<ID>` 로 고정했다. **검출 실증 8종**(대조군 `python-verbatim` 5도메인 전건 exit 3·불충족 0) — `text` 탭 제거 exit **1** / `style` 완전성·건전성 각 exit **1** / `style-tables` 표제어 삭제·정책 상수 변경 각 exit **1** / `prompts` 원칙 제거·자리표시자 파괴 각 exit **1** / `postprocess` 본문 제거 exit **1**. **선언과 생산자는 `kotlin-implementer` 인계**(같은 커밋 규약) — 이 레인은 fixture 와 하네스 계약까지만 만들었다. `export` 는 Kotlin 구현이 없어 pending 유지. 상세 `02_parity-verifier_domain-fixtures.md`.<br>**2026-08-14 게이트 11 비교기 몫(X-2·X-3·X-4·C-4) 집행.** **X-2·X-3(합의 차단)** — 케이스 하한이 `pairs`(비교 범위)를 순회해 부분 게이트에서 **선언 151 중 139만 도달**했고, 선언하지 않은 `export` 12건은 하한·형태·값 어느 것도 안 받았다(12→3 축소해도 지적 0건 재현). 하한 대조 대상을 **fixture 실물 전체**(`floor_scope`)로 바꿨다 — 하한은 "무엇을 비교했는가"가 아니라 **"무엇이 남아 있는가"**의 검사라 비교 범위와 다른 축으로 읽어야 한다. `spec_shape_problems` 의 `pending` 조기 반환도 열어 **중복 입력·보류 마커 형태**는 pending 도 받게 했다(단언을 전제로 하는 둘 — 단언 없는 케이스·방향 가드 — 은 제외. pending 은 정의상 단언이 없어 그것을 걸면 상태 자체가 결함이 된다). **codex 사각 닫음** — 하한에 `bogus/placeholder` 한 줄을 넣어도 지적 0건이었다(없는 도메인을 조용히 건너뜀). 빈 선언을 막는 검사는 있었는데 **무의미한 선언**을 막는 검사가 없었다 — 실물에 없는 도메인 선언을 차단한다. **X-4(충돌·리더 판정)** — 유도 **로직**은 독립이었으나 **입력**이 산출물 자기 보고라, 생산자가 문장을 버리면(`sentences: []`) 유도값도 비어 **양쪽이 사이좋게 0**이 되어 통과했다. 비교기가 **fixture 입력을 직접** 종결부호로 갈라 **더 쪼갤 수 없는 구간**을 얻고 그중 상한 초과분을 **하한**으로 요구하는 `contains_derived` + `style_length_floor`·`style_comma_floor` 를 더했다. 구간 안에 종결부호가 없으므로 **어떤 분리기도 더 잘게 쪼갤 수 없어** 문장 분리를 판정하지 않고도 성립한다(하한이지 정답이 아니다). **C-4(codex 단독)** — `contains_all` 이 표제어만 봐서 뜻풀이를 통째로 바꿔도 통과했다. 값은 프롬프트에 그대로 실려 모델에게 가고 246개는 실측 큐레이션이라 **값이 곧 자산**이다. `contains_entries`(표제어+값 쌍, 추가 허용·변경 차단)를 더했다. **검출 실증 4종**(대조군 = 7도메인 실제 Kotlin 산출물, exit **3**·성질 137건·단언 458개·보류 2건·불충족 0) — `bogus` 선언 exit **1** / `export` 케이스 삭제 exit **1**·9건 지목 / `style` 문장 버림 exit **1**(옛 `equals_derived` 로는 통과하던 형태) / `style-tables` 뜻풀이 변조 exit **1**(옛 `contains_all` 로는 통과, `가료: '치료' → '엉뚱한 뜻풀이'`). **부기 — 복원 사고 1건**: `cp` 백업이 기존 파일을 덮지 않아(`cp -iv` 별칭) 낡은 사본으로 되돌리면서 케이스 하한이 **151 → 108 로 깎였다.** `git status` 로 즉시 발견해 `git checkout` 으로 복원했고(151 확인), 이후 복원은 전부 git 으로만 했다. `CLAUDE.md` 2026-08-13 이력의 실패가 같은 형태로 재현된 것이다. 상세 `02_parity-verifier_case-identity.md` **§9**.<br>**2026-08-14 `export` ready 승격 — 8/8 도메인 ready.** 구현자가 core 포팅(`dcfe04e`)과 생산자까지 만들고도 **정본이 `pending`(12건 전부 단언 없음)이라 선언하지 못하던** 자리를 닫았다(선언하면 exit 2, CI 는 사면하지 않는다). `build_export` 에 단언 **117개** 작성 + `STATUS_READY` 승격. **값이 아니라 경계로 판정한다** — 파일명 정제 **결과**를 못박으면 공백 접기·앞뒤 점 깎기 규칙까지 값으로 고정돼 규칙 개선이 회귀로 잡히므로, 위험 문자 **부재**(`absent`)·확장자 **보존**(`present`)·길이 **상한**(`max_length` 신설, 상한은 `_MAX_FILENAME_STEM` 에서 읽어 리터럴 중복을 피함)만 건다. **`ascii_only` 신설** — RFC 5987 `ext-value` 는 비ASCII를 퍼센트 인코딩해야 하고, 안 하면 "한글이 깨진다"가 아니라 **응답 헤더 자체가 나가지 않는다**. 헤더 전문을 값으로 박으면 인코딩 방식 변경이 회귀가 되므로 요구("US-ASCII 안")만 건다. TXT 는 zip 컨테이너가 없어 **본문 UTF-8 바이트 = 파일**이라 BOM·NUL 부재와 제목 줄 미첨부를 값으로 판정한다. **자리표시자 복원** — 구현 정본을 `Masking.kt::restoreForExport` 하나로 둔 구현자 판단을 **확인했고 옳다**(두 벌이면 한쪽만 고쳐진다). 케이스는 export 에 남겼다 — 성질이 깨졌을 때 피해가 나는 자리가 내보내기이고(자리표시자가 남은 채 기관 밖으로 배포), 마스킹의 `restores_input` 은 마스킹 **시점**의 왕복이라 다른 성질이다. 세 성질을 명시했다: 모르는 자리표시자는 **그대로 둔다**(지어내면 없는 개인정보를 만들고 지우면 본문이 사라진다) · 치환은 **한 번만** 돈다(돌면 사용자 본문이 개인정보로 바뀌는 **주입 경로**) · 등록된 것은 복원. **검출 실증 6종**(대조군 `python-verbatim` exit **3**·12건·117단언·불충족 0) — 경로 구분자 미정제 / 길이 상한 없음 / 헤더에 한글 원문 / 모르는 자리표시자 삭제 / 다중 패스 치환 / BOM 첨부 — **전부 exit 1**. **⚠ 목표값 정정**: 정본 하한이 8개라 export 선언 순간 `declared_count == canonical_count` 가 되어 **전체 게이트**로 돈다 — 통과 조건은 **exit 0**이고 그때부터 3은 실패다. "8/8 exit 3"은 성립하지 않는 조합이다(구현자 지적이 옳다). 케이스 하한의 export 12건은 **이미 들어 있었다** — 직전 X-2 수정(하한 도달을 fixture 실물로) 덕분에 pending 시절에도 지켜지고 있었다. **인계 확인** — 승격 후 구현자 탐지기를 직접 돌려 `ParityDeclarationSyncTest … Expecting empty but was: ["export"]` 로 **실제로 빨개지는 것**을 확인했다. 그 red 가 인계 신호이고, 구현자가 `@Tag("parity")` + 선언 2파일을 같은 커밋에 넣으면 닫힌다. **부수 결함 1건 자체 발견·수정** — 직전 X-2 수정이 **도메인 디렉터리 지정 실행**에서 나머지 7개 하한 도메인을 "실물 없음"으로 오지목했다. 좁혀진 범위에서는 무의미-선언 검사를 하지 않도록 고쳤고 `bogus` 사각이 여전히 닫혀 있음을 함께 확인했다. 상세 `02_parity-verifier_domain-fixtures.md` **§7**.<br>**2026-08-14 masking 7차 개정 — `privacy-gate` §4-decies.4(리더 승인) 집행.** **83 → 84 케이스 / 단언 299 → 304 / 갈림 32 → 35 / `verdict_pending` 2 → 0.** **연도 배열 2건 방향 확정** — 보류를 닫되 **현행 동작 쪽으로 닫지 않았다.** `absent`(가림)로 얼리면 Luhn 도입이 **회귀로 잡히므로** 요구사항 방향 `present`(가리면 안 됨)로 걸었다(§8.2 "단언이 구현보다 앞선다"와 같은 방식). id 를 `deferred-*` → `keeps-*` 로 옮기고 케이스 하한의 ` !deferred` 꼬리표를 지웠다 — **이 도메인의 보류가 0건이 됐다.** 근거는 실측이다: 실문서 카드형 16자리 적중 30건 중 **Luhn 통과 1건**, 단일 공백 4×4 25건은 **전부 Luhn 실패**(그중 14건이 연속·인접 연도). **Luhn 대응 카드 값 교체** — 판정서가 "가장 놓치기 쉬운 자리"로 지목한 함정이다. 기존 표본 `1234-5678-9012-3456` 은 Luhn 실패라 `acceptsLuhn` 이 들어오면 가려지지 않는다. **규모를 실측했다 — 옛 fixture 에서 16자리 카드를 `absent` 로 단언하던 케이스 19건**이고 현행 Kotlin 은 그 값을 가리지 않는다(즉 교체 없이는 19건이 전부 불충족). 전 카드 표본을 `4111-1111-1111-1111` 계열 Luhn 유효값으로 옮겼고 양성 22건 전건 유효를 기계 확인했다. **음성 케이스도 함께 옮겼다** — Luhn 실패값을 남기면 두 겹으로 보호돼 구분자 규칙이 무너져도 통과한다. **신규 음성 1건**(`keeps-card-luhn-invalid`) — 양성을 전부 유효값으로 옮긴 지금 **도입 전후를 가르는 것은 이 케이스 하나뿐**이다. ⚠ **기존 케이스 보존 규약과의 충돌 처리**: 규약의 목적은 "검증하던 것이 조용히 사라지는 것"을 막는 것이지 입력을 성역으로 두는 것이 아니다 — 그대로 두면 오히려 19건이 검증을 멈춘다. **id·서술은 불변, 표본값만 이동**했고(성질은 한 글자도 안 바뀜) 원장 해시 변경이 그 교체를 리뷰에 올린다. **검출 실증** — 대조군 실제 Kotlin 7도메인 exit **3**(성질 140건·단언 467개·보류 0·불충족 0), Luhn 훅을 되돌린 주입 exit **1**/3건. **Kotlin 은 이미 Luhn 을 반영했다**(`Masking.kt::acceptsLuhn`) — 세 신규 케이스가 전부 충족이고 Python 만 갈린다(요구사항 쪽이 Kotlin). **남은 반대 방향 위험**: OCR·오탈자로 한 자리가 깨진 카드는 Luhn 을 통과 못 해 마스킹을 빠져나간다 — 코퍼스 관측 0건이라 fixture 에 고정하지 않았고 파일럿에서 카드 유입이 관측되면 재판정한다. 상세 `02_parity-verifier_masking-spec.md` **§11**.<br>**2026-08-15 게이트 12 비교기 몫(#4·#3) 집행.** **#4(Phase 2 저지)** — export 파일명 축에 **over 방향 차단 단언이 0개**였다. 재현: 파일명 21개를 전부 대체 이름으로 바꿔도 **성질 불충족 0건**(불충족 6건은 전부 원장 갈림) — 사용자 제목이 통째로 사라져도 성질 판정은 초록이었다. 처방은 **X-4에서 만든 입력 유도 하한을 파일명 축으로 옮긴 것**(`contains_derived` + `export_title_markers`). `equals_field` 로 정제 결과를 못박지 않으므로 §7.1의 "규칙 개선이 회귀로 잡힌다" 우려는 걸리지 않는다. 하한이 성립하는 근거 둘 — ① 금지 문자·공백·점으로 **가른 뒤**의 조각이라 정제가 조각 **안**을 건드리지 않는다 ② 제목 앞 40자·조각 8자만 쓰므로 정제(길이를 줄이기만 한다)와 80자 상한을 통과한다. 제목이 전부 금지 문자인 표본은 표지가 비어 하한이 무력하므로 **대체 이름 단언**을 따로 붙였다(별도 케이스로 만들려다 **M-08 중복 검사가 막았다** — 입력이 같아 같은 산출물을 두 번 재는 구조였고 지적이 옳아 합쳤다. 한계: 대체 이름은 계약이 아니라 구현 상수가 기준이다). 검출 실증 — 대조군 exit **3**(12건·141단언·불충족 0) / 전체 대체 이름 exit **1**·6건 / 확장자만 남김 exit **1**·7건. **#3(차단②)** — 전체/부분 게이트가 `declared == canonical`(BUILDERS 키)로 계산돼, **builder 하나만 늘어도 부분 게이트로 내려가 exit 3 사면이 되살아난다.** "8/8은 강제된 성질이 아니라 오늘 참인 상태"라는 판정 그대로였다. `.github/parity-full-gate.txt` 로 **전체 게이트 도달 상태를 하한으로 고정**했고, **자기 무장**으로 만들었다 — 도달했는데 표시가 없으면 exit **1**("고정하라"), 표시가 있는데 내려가면 exit **1**("내려왔다"). 도달하는 순간 비교기가 요구하므로 **도달 0인 채 잠드는 장치**가 되지 않는다. 음성 대조 4종 — 현재(7선언) exit **3** / 8선언·표시 없음 exit **1** / 표시 후 7선언 강등 exit **1** / **가짜 builder 추가(정본 9) exit 1**(리뷰가 지목한 시나리오). **자기 무장이 실제로 발화했다** — 이 작업 중 `export` 가 선언되어(`adb4197`) 선언 8이 됐고, 다음 실행이 `전체 게이트에 도달했다 — 하한을 고정하라`로 **exit 1** 막았다. 사람이 기억해 만드는 것이 아니라 게이트가 요구했다. `.github/parity-full-gate.txt` 를 만든 뒤 강등 음성 대조(선언 7 되돌림)가 `전체 게이트에서 내려왔다: export` 로 막는 것을 확인했다. **그리고 전체 게이트가 닫혔다 — `--only-domain` 없이 돌린 실행이 종료 코드 0**(`요구 성질 충족: 도메인 8/8 / 성질 판정 152건(단언 608개) / 판정 보류 0 / 미검증 0 / 불충족 0`). **이 시점부터 종료 코드 3은 통과가 아니다.** ⚠ **복원 사고 2건째(규칙 5)** — 가짜 builder 프로브를 `git checkout` 으로 되돌리다 같은 파일의 **#4 미커밋 변경을 함께 지웠다.** 즉시 발견·복구했고(grep 0 확인 → 재적용 → 대조군 exit 3 재확인), 지난 `cp` 별칭 사고와 **기제는 다르고 형태는 같다 — 복원 대상이 프로브보다 넓었다.** 규칙으로 옮긴다: 프로브로 제품 파일을 고쳤으면 되돌리기 전에 **미커밋 작업 유무를 먼저 보고**, 있으면 주입한 조각만 역으로 지운다(가장 싼 예방은 프로브 전 커밋). 상세 `02_parity-verifier_case-identity.md` **§10**. **C-21** — repair-adoption 판정이 미선언 `style` 도메인에 의존한다는 **조건부 판정 주의**를 fixture `requirement` 헤더에 표기했다(케이스 설명·문서에만 적으면 산출물을 읽는 사람에게 닿지 않는다). **C-24** — `_harness-selfcheck` 의 `purpose` 가 *"Phase 1 배선 증명 전용"* 이라 거짓이 된 것을 현행화했다(`ParityHarnessSelfCheck.kt`, 하네스 코드라 이 레인 소유). **C-20 — 하지 않는다**: 레거시 시나리오 2건 대본화는 ① 기존 케이스 `input` 을 바꿔 확장 규약을 어기고 ② 커버리지가 늘지 않는다(같은 성질을 대본 케이스가 이미 더 강하게 판정). 사유는 `02_parity-verifier_conversion-spec.md` §9.2. 검증: 두 도메인 재현성 2회 덤프 `generated_at` 외 **바이트 동일** / `./gradlew parityHarness parityManifestCheck ktlintCheck` exit **0** / 선언 2도메인 게이트 exit **3**(94건·330단언·불충족 **0**·미검증 0) / `uv run pytest` **1091 passed·68 skipped** / ruff·`mypy . .claude`(130 files) 통과 | 나머지 6개 도메인 미생성 — **의도적**(도메인은 검증에 들어가는 시점에 뽑는다. 미리 뽑으면 `style_rules.py` 변경 때 아무도 안 보는 도메인이 정본 불일치로 빨개진다). 전체 게이트는 지금 돌리면 exit 1(도메인 누락 10개)이며 이는 사실 그대로다. ~~**fixture 커버리지 공백**~~ → **닫힘(2026-08-12)**. 초판 §6.2의 "유니코드 공백 12종"은 **과소 집계**였다 — 전 코드포인트 재측정 결과 Python `re`의 `\s`는 **29종**이고 Java 기본이 놓치는 것은 6종이 아니라 **23종**, `UNICODE_CHARACTER_CLASS`로도 못 잡는 잔여가 **4종**(U+001C~1F)이다. 남은 한계: 이 게이트도 "그 산출물을 Kotlin이 만들었는가"는 증명하지 못한다(CI 배선이 유일한 방어). **`repair-adoption` 잔여 한계 3건** — ① 시나리오 17건은 참고값(`reference`)이 없어 갈림이 원장에 자동 기록되지 않는다(실패 경로를 Python이 값이 아니라 예외로 내므로 참고값을 지어 넣으면 두 번째 기대값이 된다. 대신 관측 필드를 `equals_field` 로 못박아 갈리면 성질 불충족으로 드러난다) ② 갈림 후보 2건이 **리더·`contract-keeper` 판정 대기** — 보정 악화 가드가 본문·팩트 손실을 보지 않음 / 계약 `failure_code` 가 enum 대신 "예외 클래스명" 규칙이라 값의 정본이 Python 구현이다(§9-E) ③ 도메인 **이름**(`repair-adoption`)이 범위(변환 오케스트레이션 전체)보다 좁다 — 이름 변경은 하한 파일 규칙상 삭제+추가라 하지 않았고, 범위의 정본은 fixture 헤더 `requirement` 다. **masking 잔여 한계 2건** — ① 판정서 §1.7 해제조건 4의 **`docs/golden/` 실문서 마스킹 건수 대조는 이 레인이 하지 않았다**(실문서라 산출물·리포트에 본문이 실릴 위험이 있어 `privacy-gate` §5.5 절차가 맞다) ② 판정서 §1.7 해제조건 3(`UnicodeRegex.kt` KDoc 종류 B 반영)은 `kotlin-implementer` 몫으로 열려 있다 ③ **판정문 §4-ter.4 조건 5의 `absent`/`present` 표기가 이 하네스 어휘와 뒤집혀 있다** — 정본인 12탐침 표를 따랐고 `privacy-gate` 회신 대기. 내가 잘못 읽은 것이면 12건의 방향이 뒤집힌다 ④ **`style` 미선언으로 repair-adoption 판정이 조건부다**(C-21) — 규칙 위반 건수의 정확성은 판정 범위 밖 ~~⑤ `known_gap` 2건이 판정 대기 상태를 `known_gap` 으로 표기~~ → **해소(2026-08-14)** — `verdict_pending` 필수 필드 체계로 교체 ~~⑦ 다섯 신규 도메인은 아직 Kotlin 산출물로 판정된 적이 없다~~ → **해소(2026-08-14)** — 생산자 배선(`cd23aec`, kotlin-implementer) 후 **7도메인 실제 산출물로 판정**했다(성질 137건·단언 458개·불충족 0) ~~⑤·연도 배열 `verdict_pending`~~ → **해소(2026-08-14 §4-decies.4)** — `present` 로 방향 확정, 보류 0건 ⑨ **Luhn 을 통과 못 하는 손상 카드번호는 마스킹을 빠져나간다** — 측정된 이득(오탐 29건 제거)과 가설적 손해(관측 0건)를 같은 무게로 놓지 않은 판정이고, 파일럿 관측 시 재판정 ⑧ **문장 분리를 조작하는 입력은 여전히 하한을 낮춘다**(X-4 잔여) — 하한이 막는 것은 *보고를 버리는 것*이지 *분리를 조작하는 것*이 아니다. 후자를 막으려면 문장 분리를 판정 대상으로 삼아야 하고 그것은 요구사항이 명시적으로 하지 않기로 한 자리다 ⑥ **CI 게이트의 워크플로 배선·러너 환경은 상시 테스트로 덮이지 않는다**(Z-7 한계 선언) — 최종 방어선은 첫 push 의 실제 러너 실행이다 | - | parity-verifier (2026-08-14) |
| 도메인마다 `backend-kotlin/parity-domains.txt` 선언 + Kotlin parity 테스트가 `parity/actual/` 산출 | 예 — 8/8 선언·산출 | `ci:kotlin` | 전 도메인 | **2026-08-13 첫 배선**(masking·repair-adoption, 성질 56건·단언 189개) → **2026-08-14 5도메인 확장**(`cd23aec` — text·style·style-tables·prompts·postprocess 생산자 + 선언 + declared-floor 같은 커밋). 실측: 선언 7개 전부 산출물 확인 · 선언 범위 비교 exit **3**(7/7 · 성질 137건·단언 437개 · 불충족 0 · 판정 보류 2). 입력 하네스 `ParityFixtures`는 fixture 를 파일에서 읽는다. `style`은 기계가독 필드(`length_violations`·`comma_violations`, `StyleRuleKind` 기본값 없음) 신설. 갈림 1건(`style-tables` 집합 순서 — 정렬로 덮지 않음) 원장 기록 | `export` 1개만 미선언(Kotlin 내보내기 미구현 — Phase 4 조각). **부분 게이트(exit 3)는 전체 통과가 아니다.** 게이트 11 지적 계류: C-3(style 생산자 문장 유실 통과)·C-4(사전 값 미검사)·C-2/export 하한 CI 도달 0 — 교차 종합 후 처분 | export 조각 · 게이트 11 | leader (2026-08-14, 게이트 11 Claude 리뷰 원장 모순 지적 반영) |
| **종료 조건**: 외부 API·DB 없이 도는 parity suite 가 양쪽에서 같은 결과 | 예 | `ci:kotlin` | 전 도메인 | **범위 8/8 — 전체 게이트 exit 0, 러너 실측(2026-08-15, run 31854263996 kotlin 잡: 성질 153·단언 630·불충족 0 — 게이트 14 Claude 리뷰가 러너 로그로 확인).** ~~584~~ → ~~608~~ → **630**(게이트 13 배치의 fixture 확장 반영 — 수치 드리프트 두 번째 재발이라 이후 이 칸은 러너 로그를 정본으로 인용한다, 게이트 14 F-2). ~~범위 7/8, exit 1(`parity/actual/export/` 부재)~~(2026-08-14 기록 — export 조각으로 해소) | 충족을 `예` 로 못 올리는 잔여(게이트 12 교차 종합): **A** #1 CARD 겹침 삼킴 회귀(차단①, 수정 중) · **B** CI 러너 도달 — ~~"동기화 완료"는 게이트 13 시점 거짓이었다(7커밋 미푸시)~~ → **2026-08-15 해소 진행**: 푸시(`cb26a77`) + **draft PR #1 생성 → CI 첫 실행 in_progress**(run 31838885595). 결과 확인이 남음 · **C** #4 export over 단언 0(수정 중) · #3 사면 부활은 차단②(마감 지금, 발화는 이후) | 게이트 12 잔여 A·B·C | leader (2026-08-15, #8 집행) |

### Phase 2 진행 기록 (리더, 2026-08-13~14)

**재개발 조각 순서** (사용자 방향 전환 2026-08-12 이후): ① core 도메인+LLM 경계(커밋 `c11a404..f73879b`, 5건 — 사전·스타일·마스킹·복원·프롬프트·주입방어·후처리·LlmProvider·Anthropic 어댑터) → ② application 변환 유스케이스+parity 배선(`f73879b..6d8e88c`, 6건) → ③ 후속 수정(`bed5300` toString 3종, `3934f06` masking fixture 4차). 다음 조각: **api 엔드포인트**.

**리뷰 게이트 이력** (어간은 기존 관례(순번_주제)를 따랐다 — SKILL.md `{scope}` 정본 표와의 정합은 하네스 점검 별건. 게이트 13 지적으로 03~06 역사 행을 소급 기재 — 그 회차들은 재개발 코딩 전 하네스·계획 점검이라 이 표 신설 전에 끝났다. **10~14는 표가 아니라 이 절 아래 산문 문단으로 기록돼 있다** — 표의 03~09 다음이 15인 것은 누락이 아니다):

| 어간 | 대상 | 상태 | 정본 |
|---|---|---|---|
| `03_rebuild-plan` | 재개발 전환 계획(추출 목록·progress) | 계획 심사(codex-plan-reviewer 단독) — needs-attention, 지적은 계획에 반영 | `reviews/03_rebuild-plan_codex-plan-reviewer.md` |
| `04_quality-gate` | 품질 합격선 계측기 | 3단계 완주 | `reviews/04_quality-gate_cross.md` |
| `05_scope-reach` | 실행 경로 규약·표기 검사기 | 3단계 완주 | `reviews/05_scope-reach_cross.md` |
| `06_baseline-guard` | 골든셋 하한선 가드 | 3단계 완주 | `reviews/06_baseline-guard_cross.md` |
| `07_core-rebuild` | `c11a404..f73879b` | **3단계 완주.** 합의 4·codex 단독 2·Claude 단독 12·상충 2(리더가 privacy-gate 회부로 처분). 조치 18항 중 이번 조각에서 X-1·X-2·X-5탐지기·X-6·X-7·X-8·X-16·X-17 처리, X-9(스냅샷 생성기)·X-4(모듈 경계) 등 잔여는 §6 마감대로 | `reviews/07_core-rebuild_cross.md` |
| `08_conversion-usecase` | `f73879b..6d8e88c` | **3단계 완주.** 27건 → 24행: 합의 2 · 부분 합의 1 · 충돌 1(C-09) · codex 단독 7 · Claude 단독 13 — **24행 중 21행이 한쪽만 봤다**(두 모델의 관심 표면이 거의 안 겹침). **종합 차단 성립**: ①사건 C-01(보충 평면 숫자·복합 카드 구분자 실측 통과) + ②장치 3건(C-02 parity 선언 0 exit 0 · C-03 스캐너 다중 줄 미탐지 · C-04 루트 부재 무시 — **별개 결함, 병합 안 함**). 한쪽이 닫은 자리를 다른 쪽이 연 곳 4건(X-8·CNV-02/04·X-5·모듈 경계) 재개방. `bed5300`·`3934f06` 해소 검증은 다음 회차(C-06·C-12 구현분) | `reviews/08_conversion-usecase_cross.md` (정본) |
| `09_masking-grammar` | `6d8e88c..c61c94e` | **3단계 완주** (2026-08-14). 합의 2 · 충돌 3 · codex 단독 3 · Claude 단독 17 — 24행 중 20행이 한쪽만 봄(**한쪽 레인만 돌렸으면 게이트가 닫혔을 구성**). **§4-ter 문법 본체는 두 레인 합의로 해제**(방법 비겹침 — 12탐침 재구성 vs 301조합 전수). **차단 ②장치 3건**: M-01(`_SAFE_ACCESS`가 점 연속만 — ktlint 표준 표기가 안전 판정) · M-02(`--tests` 집합 의미론) · M-03(논리 줄 40줄 fail-open·raw string). 평문 로그 탐지기 우회 세 갈래(M-01·M-03·M-09) → 탐지기 단위 재설계 배치 진행 중. K-1(탐색 뷰×SEP 합집합)은 KDoc이 의도된 설계로 명시 — 결함 통보가 아니라 **privacy-gate 방향 판정**(M-06 TAB과 묶음, 진행 중). 리더 판정: M-04 승격(감시 집합 자기 파생 — 수정, M-02와 같은 배치) · M-07 = C-09 재적용(변경 없음, M-08만 분리 → parity-verifier). codex 미언급 6건(C-06·C-12구현분·C-18 등)은 Claude 단독 근거 해제로 표기 | `reviews/09_masking-grammar_cross.md` (정본) |
| `15_phase3-preflight` | `5797d87..614afed` (6 커밋) | **3단계 완주** (2026-08-15). codex `needs-attention` — findings **8건 전부 high** / Claude **20건**(차단 2 · 수정 필요 8 · 권고 8 · 판정 필요 1 · 적정 1) / cross **23행**(합의 3 · 정면 충돌 2 = X2·X13 · 심각도 충돌 2 = X8·X9 · Claude 단독 13 · codex 단독 3). 독립성은 양쪽 산출물에 각각 기록됐고 범위 드리프트 0(codex 실행 시각 `HEAD` = `614afed` = 리더 지정 범위 끝). 후속 처리 현황은 아래 「게이트 15 후속」 절 | `reviews/15_phase3-preflight_cross.md` (정본) · 1단계 `reviews/15_phase3-preflight_codex-reviewer.md` · 2단계 `reviews/15_phase3-preflight_migration-reviewer.md` — 3종 전부 `c932b4f` 에 보존 |
| `16_gate15-fixes` | `614afed..1cb7bdf` (6 커밋) | **3단계 완주** (2026-08-18, `cc7f53c`). codex `needs-attention` — findings **3건 전부 high**(K16-1~3). **축① 음성 대조 재현은 미수행** — codex 샌드박스가 read-only 라 `mktemp` 가 `Operation not permitted` 로 막혔고, 그래서 이 회차의 음성 대조 3건은 **단일 관점**으로 남는다(cross §9.1). / Claude **21건**(차단② **1** = T-E · 수정 필요 **4** · 권고 **10** · 이월 **6**) / cross **23행**(합의 **8** · 충돌 **1** = F · codex 단독 **2** = B·I · Claude 단독 **12**). **완전 수령의 효과는 「합의 확인」이 아니라 Claude 통과 판정을 되연 것**이다 — codex 단독 B 가 Claude 가 "지적 없음"으로 닫은 구획(R-2)을 좁혔다(게이트 14 재실행분과 같은 형태). **리더 전제 오류 1건 확정** — 프롬프트가 codex 레인에 내려보낸 "새 CI 스텝 **4개**"는 실제 **3개**(`04ced00` 2 + `4cba492` 1, 전부 `quality` 잡)다. **게이트 15 별건 1과 같은 형태**(검증하지 않은 값이 프롬프트를 타고 하위 레인으로 내려감) — 아래 「게이트 16 후속」 §4 ②. **리더 판정 2건**: 충돌 F → **Claude 처방**(하한은 생성기 안에 두고 검사는 생성기 밖 가드가 케이스 이름 집합으로 대조 — 제2 정본 신설 없음) · T-D⑵ → **종류로 닫는다**. 후속 처리 현황은 아래 「게이트 16 후속」 절 | `reviews/16_gate15-fixes_cross.md` (정본) · 1단계 `reviews/16_gate15-fixes_codex-reviewer.md` · 2단계 `reviews/16_gate15-fixes_migration-reviewer.md` — 3종 전부 `cc7f53c` 에 보존 |
| `17_gate16-fixes` | `1cb7bdf..48a791c` (5 커밋) | **3단계 완주** (2026-08-19, `36b5ed4`). codex `needs-attention` — findings **3건 전부 high**(X17-1~3). **게이트 16 이 미수행으로 남긴 축① 음성 대조 재현이 이 회차에 회수됐다** — codex 가 **저장소에 쓰지 않는 경로**(`git show` + `/dev/stdin` + importlib 메모리 변이)로 성공해, 게이트 16 에서 단일 관점이던 것 중 닫히는 것이 닫혔다(cross §6.1·§6.2). / Claude **10건**(차단② **1** = ⑤ 빈 표 통과 · 수정 필요 **1** · 권고 **8**) + 검토함(지적 없음) **6** · 미검토 **0** / cross **16행**(합의 **3** · 충돌 **1** = ⑨ · codex 단독 **3** = ①②⑥ · Claude 단독 **9**). **codex 단독 3건 중 둘이 Claude 의 통과 판정을 되열었다** — ①은 R17-2 「검토함 — 지적 없음」, ⑥은 P17-2 「해소」로 닫은 구획을 각각 좁혔다(게이트 16 과 같은 형태가 **두 번** 재현). 반대 방향도 한 번 있다 — ⑤(차단②)는 codex 가 focus 에서 **물었으나 최종 출력이 답하지 않은** 자리를 Claude 실측이 채웠다. **전제 오류로 무효화된 지적 0건**(cross §7 — 넘긴 전제 6건 + 부속 1 전건 코드 대조. 부속의 `ci.yml` 경로 명시 스텝 **codex 5 vs Claude 7** 은 집계 기준 차이로 **무모순** — 게이트 16 의 리더 전제 오류와 다른 성격이다). **리더 판정 1건**: 충돌 ⑨(`SKILL.md:238` 이중 기재) → **Claude 채택** — 러너 머리 주석을 **지목만** 하는 형태로 축약하고 계약값 전문을 삭제했다(`107c8a5`). 후속 처리 현황은 아래 「게이트 17·18 후속」 절 | `reviews/17_gate16-fixes_cross.md` (정본) · 1단계 `reviews/17_gate16-fixes_codex-reviewer.md` · 2단계 `reviews/17_gate16-fixes_migration-reviewer.md` — 3종 전부 `36b5ed4` 에 보존 |
| `18_gate17-fixes` | `36b5ed4..318069b` (2 커밋) | **3단계 완주** (2026-08-19, `bbe49a1`). codex `needs-attention` — findings **4건 전부 high**(X18-1~4). codex 총평 문면은 "four independent false-green/scope-integrity defects remain" 이고, **4건 전부 이 배치가 방금 세운 하네스 자기 검사 장치**에서 나왔다. / Claude **11건**(차단 **0** · 수정 필요 **5** · 권고 **6**) + 검토함(지적 없음) **10** · 미검토 **1** / cross **19행**(합의 **4** · 충돌 **4** = X1·X4·R7·S2 · codex 단독 **3** = X2a·X5·X6 · Claude 단독 **8**). **Claude 통과 판정을 codex 가 둘 되열었다 — 세 회차 연속이다**: X1(1차 R18-4 「도달 가능한 상태가 아니다」) · X4(1차가 게이트 17 ⑭ 를 「해소」로 판정한 바로 그 관측). 이 회차가 제3 근거로 **양쪽 경로의 성립을 실행 확인**했고 1차 판정은 고쳐 쓰지 않았다. **게이트 17 ⑥·⑦ 은 codex 무응답이라 단일 관점 판정**이다(cross §6.1 — 「지적 없음」으로 읽으면 교차 검증 없이 닫힌 것으로 오독된다). **전제 오류로 무효화된 codex 지적 0건**(cross §6 — 6건 전건 코드 대조. 그중 `BASH_ENV` 마커 선점은 codex 가 샌드박스 제약으로 완주하지 못한 것을 이 회차가 **완주해 우회 성립을 확인**했다 — 보완이 codex 진술을 약화시키지 않고 하나를 완결시켰다). 후속 처리 현황은 아래 「게이트 17·18 후속」 절 | `reviews/18_gate17-fixes_cross.md` (정본) · 1단계 `reviews/18_gate17-fixes_codex-reviewer.md` · 2단계 `reviews/18_gate17-fixes_migration-reviewer.md` — 3종 전부 `bbe49a1` 에 보존 |
| `19_gate18-fixes` | `bbe49a1..5d58832` (코드 2 커밋 + 원장 1 참조) | **3단계 완주** (2026-08-19, `445c5cf`). codex `needs-attention` · "No-ship." — findings **high 4 · medium 2**(X19-1~6). / Claude **11건**(차단② **1** = R19-1 · 수정 필요 **6** · 권고 **4**) + 검토함(지적 없음) **9** · 미검토 **4** / cross **20행**(합의 **6** · 충돌 **1** = ⑤ 리더 제약 위반 여부 · Claude 단독 **5** · codex 단독 **3** · 합의(축)·사례 갈림 **1** = ⑯ · 검토함(대상 아님) **4축**). **전제 오류로 무효화된 codex 지적 0건** — cross §6 이 codex §4.5 「전제 확인 필요」 **7건 전건**을 실행·코드 대조로 확인했다(빈 `parametrize` → skip · 외부 강제자 전수 검색 참조 4건 전부 비-검사 · marker/rc 4입력 · env 간접 배선 33 passed · 행 번호 불일치는 사실이나 **오지목 0건**). **게이트 18 종결 — 미해소 0(양쪽 합의)**: 일치 해소 **2**(X4·T1) · 일치 부분 해소 **1**(X2a) · **갈림 3**(X1·X3·T2). 갈림 셋은 **실측값을 양쪽이 합의하고 이름표가 다르다**(부분 해소 ↔ 해소+별건) — **단 X3 만은 실질이 갈렸다**: codex 단독 ⑩(러너 잔여 (가)/(나) 밖 **세 번째 종류**)은 **Claude 가 보지 않은 새 사실**이다. **최고 심각도 2건이 서로 단독이다** — ①(Claude 단독 **차단**) · ⑧(codex 단독 **high**). 이 사실을 심각도 완화 근거로 쓰지 않았다. 후속 처리 현황은 아래 「게이트 19 후속」 절 | `reviews/19_gate18-fixes_cross.md` (정본) · 1단계 `reviews/19_gate18-fixes_codex-reviewer.md` · 2단계 `reviews/19_gate18-fixes_migration-reviewer.md` — 3종 전부 `445c5cf` 에 보존 |

**privacy-gate 판정** (`07_privacy-gate_masking-verdicts.md`): 판정1~5(§1~§4-bis)에 이어 **§4-ter 묶음 판정(2026-08-14)** — C-01①(보충 평면: 커버리지가 아니라 **정합성 결함** — 정규식은 코드포인트로, 가드는 UTF-16 Char로 셌다)·C-01②+C-10(**충돌 아님** — 유한 문법 `SEP := 공백? 하이픈? 공백?` 하나가 누락·과잉 동시 개선, 판정 기준은 문자 종류가 아니라 **개수**)·C-11(VT·FF 비결합). §4-quater·quinquies — 스캐너 red 오탐 확정(refine 훅 설계)·C-03(논리 줄 결합, AST 기각)·C-04(전수 0건 비영 종료). **§4-sexies 해제 심사 — 6건 해제·1건 재회부**(`_SAFE_ACCESS` 종단 미고정 — "결손은 구현자가 아니라 내 명세에 있다" 자인, `5ac039f`로 닫힘)·`document` 한정자 이탈 승인. 집행 커밋: `c6e65a0..888e3c9` + `5ac039f` + `1301367`. **레인 대기열 비었음. CI quality 스캔 단계 red 해소(전수 exit 0).** 잔여: 판정1 해제조건 4(`docs/golden/` 실문서 건수 대조 — privacy-gate §5.5 절차 몫). **조건 5 표기 리더 확인(2026-08-14)**: §4-ter.4의 "absent/present"가 하네스 어휘와 반대로 읽히는 문제를 parity-verifier가 발견 — 정본은 §4-ter.2 12탐침 표(자리당 공백 2개 이상 = 안 가림)이고 parity-verifier의 동결 방향이 옳다. 판정문 요약 한 줄의 느슨한 표기이지 판정 내용 불일치가 아니다.

**리더 판정 완료**: X-2 소관 = privacy-gate 지정 · X-8 CI 배선 승인(quality 잡) · missing_placeholders 도메인 배정(신설 없이 repair-adoption, parity-verifier 판단 수용) · 설정 소유권 = infrastructure(kotlin-implementer 결정 수용, `LlmProviderConfigurationTest` 가드 확인) · **C-12(2026-08-14)**: masking 행 예 → 아니오 재판정(위 표) — `충족 = 예` 의 의미는 "그 행을 겨냥한 차단 지적이 열려 있지 않음"을 포함한다 · **C-09(2026-08-14)**: parity 생산자 격리는 **규약이다 — 구조 봉쇄 기각.** 근거는 X-3 판정 전례(`정본도 같은 커밋에서 함께 바뀔 수 있다 — 한 칸 더 옮기는 것은 방어가 아니라 이동`)와 실질 방어의 소재: 기대값을 베끼는 생산자는 회귀 주입 검출 실증(`3934f06` — 진짜 Kotlin 산출물에 5종 주입, 전부 exit 1)이 잡고, `ParityFixtures` 를 우회하는 커밋은 하네스 수정이라 리뷰 게이트를 지난다. codex 지적(공개 `root()`·시스템 프로퍼티 우회 가능)은 사실로 인정하고 병기한다 — 닫은 것이 아니라 관리하는 한계다 · **C-15(2026-08-14)**: 과거 이력 rewrite 하지 않는다(ff4c323 단독 비컴파일은 기록으로 남김). 다음 커밋부터 선언·생산자 동일 커밋 규약 준수.

**§4-septies (2026-08-14, privacy-gate K-1·M-06 방향 판정)**: 접기는 개수 원칙의 뒷문이 아니다(분리축 성립 — 1,120조합 열거·컴파일 산출물 리플렉션 직접 호출). 실제 갈림은 경계축 한 종류(폭 0 문자가 "긴 숫자열" 거부 근거를 무효화, 90/90). **방향(누락 우선·합집합) 유지, KDoc 근거는 교체**(현행 근거 두 군데가 틀림 — 새 원칙: 분리축=적극 판정=일관된 한 읽기 / 경계축=거부권=두 읽기 만장일치). **M-06 TAB 수정 유효 — 리더 승인(2026-08-14)**: `SPACE_CHARS`의 `	` 제거(커버리지 축소 승인 — 단위 불일치 해소, 누락 비용은 기 감수 종류와 동일, INV-02 판정에 반영할 것). 실문서 실측: 접기 대상 1,006건 중 K-1 형태 발생 0, 합집합 대 뷰 전용 갈린 파일 0(2.67M자) — 단 붙여넣기 경로는 추출기를 안 거치므로 fixture로 고정(경계축 양성·음성 **짝** 3+3 필수). **신규 관측 1건(교차 검증 없음, 라우팅만)**: `2021 2022 2023 2024`(4×4 연도 배열) → 카드번호 과잉 — 줄일 수 없는 모호성이라 "감수한 과잉 표면"으로 원장 기록 요청 → parity-verifier.

**게이트 09 이후 수정 파동 (2026-08-14)**: 스캐너 재설계 `3570bdc`(M-01·M-03·M-09 — 사슬 파싱·상태 유지 lexer·균형 인자) · `75bfb40`(M-02·M-04 — 탐지기를 끄는 두 방법) · `3727905`(M-10·M-11 — 누락 6종 거절/실려 온 0 수용, usage 보존 절반은 M-12로 Phase 5 이월) · stop-gate 지적 `c2255dc`(주석 안 괄호가 인자 구간을 조기 종결 — "안전 접근이 방패" 기제, 재현이 회부 문구를 정정) · §4-septies 집행 `ac0307e`(TAB 제거·합집합 근거 교체·경계축 3+3) · fixture 동결 `56a70c1`(TAB 방향 전환 — **게이트 exit 3 복귀**, 108건·374단언, 경계축 12·감수 표면 known_gap 2·M-08 중복 검출·Z-7 분할: 상시 7종/한계 선언). **게이트 10(`10_detector-redesign`) 3단계 완주(2026-08-14)** — 합의 2 · Claude 단독 9 · codex 단독 4 · 충돌 0. **차단 4건(전부 ②장치)**: R-1(논리 줄 내 다중 로그 호출 첫 적중만 판정 — **첫 차단급 독립 합의**) · R-2(중첩 블록 주석, codex 단독) · R-3(`_?logger?\.`가 `log.` 못 봄 — 전역 예외 핸들러 탐지 밖, Claude 단독) · R-4(known_gap 게이트 도달 0, Claude 단독). "한 레인만 돌렸다면 어느 쪽도 게이트를 옳게 닫지 못한다." §4-septies 집행 축은 비대칭 수렴으로 충족(Claude 실측 4/4 + codex 무반증). 게이트 09 차단 3건·stop-gate 지적은 **전부 해소 확인**. **리더 판정 5건(2026-08-14)**: J-1+J-3 통합 — 케이스 축 장치는 개수가 아니라 **케이스 ID 정체성** 기준(`86c6a99` 판정 준수), known_gap 탐지형 전환과 한 장치, parity-verifier · J-2 — refine 훅 값 성질 휴리스틱 확장 금지(다섯 갈래 이력), 탐지형 대체 설계 privacy-gate(§4-octies 예정) · J-4 — **잔여 5개 도메인 착수 허용(조건부)**: R-3만 착수 전, 나머지 마감 "5개 도메인 병합 전", 증분 스캔 전환 시 판정 무효, R-4 닫히기 전 `성질 판정 N건`을 종료 근거로 쓰지 않음 · J-5 — C-07 Phase 5 유지. 정본 `reviews/10_detector-redesign_cross.md`.

~~**하네스 미결**: known_gap 정의 어긋남~~ → **해소**(`ad6ab92` — `verdict_pending` 전환: owner·deadline·referred_by 필수·분리 집계·`!deferred` 하한 꼬리표).

**게이트 11(`11_suppression-and-domains`) 3단계 완주(2026-08-14)** — 대상 `56a70c1..cd23aec`. **두 번째 차단급 독립 합의 X-1**(C-1≡N-01): 적중 입도(논리 줄×규칙) vs 표기 키(파일×물리 줄×규칙) 불일치로 표기 하나가 논리 줄 전체를 억제 — §4-octies 중심 주장("조용한 억제 불가") 반증, **설계 결함 선행 판정** → privacy-gate 재판정(§4-novies 예정) → kotlin-implementer 수정. 합의 X-2·X-3(export가 하한·형태·값 어느 검사도 안 받음), 충돌 확정 X-5(LoggerFactory 체인 — 탐지·도달 양쪽 도달 0, 두 정의가 "이름 붙은 수신자" 가정 공유 = Z-1), 충돌 X-4(style 유도 입력 — 리더 판정: 비교기가 출력 본문에서 독립 유도). R-1 해소는 층 한정 표기(검출 층 해소/억제 층 재도입). Claude 단독 판정 6건 교차 0회 — **prompts 왕복(마스킹 선행)·예산 3→7이 다음 회차 1순위**. 게이트 10 차단 4건 중 R-1~R-3 해소·R-4 부분 해소(id축 대체 적정). 정본 `reviews/11_suppression-and-domains_cross.md`. **후속 3레인**: privacy-gate(§4-octies 재판정 X-1·X-5·Z-1) · parity-verifier(X-2·X-3·X-4·C-4 비교기 강화) · kotlin-implementer(export 조각 — 스캐너 동결).

**게이트 12(`12_export-luhn-suppression`) 3단계 완주(2026-08-15)** — 대상 `cd23aec..516c0e9`. 차단 2건 추가: **#1 CARD 겹침 삼킴 = ①사건**(Luhn 배치가 만든 회귀 — 도입 전 14자리 가림 → 0자리, `findAll` 뒤 `filter`) · **#3 부분 게이트 사면 부활 = ②장치**("8/8은 강제된 성질이 아니라 오늘 참인 상태"). **도달 0 처방 정정** — 푸시는 이미 완료였고 트리거가 `push: main`뿐이라 **PR이 유일한 러너 실행 경로**. Phase 2 저지 A(#1)·B(PR)·C(#4 export over 단언)·D(원장) 확정. 집행: `2cf862a`(#1+C-6+N-08, ⚠비교기 변경 혼입)·`6e6fbbf`(full-gate 표시)·`8972c36`(#6 선언 정정)·§4-undecies·§4-duodecies(최종 감사 — 4조합 차분, Security 게이트 통과). **draft PR #1 생성(사용자 승인) → CI 러너 첫 실행**: kotlin·frontend 통과, quality는 ruff format 2파일로 실패 → 리더 수정(`4dafde5`) → 2차 실행 quality 포함 통과. 정본 `reviews/12_export-luhn-suppression_cross.md`.

**게이트 13(`13_regression-and-pins`) 3단계 완주(2026-08-15)** — 대상 `516c0e9..14b9d92`. **세 번째 차단급 독립 합의 X-1**(전체 게이트 하한 검사가 CI의 8/8 경로에서 완전 비활성 — `not selected` 즉시 반환, 리더 판정: 차단② 승격). X-5 충돌(#4 해소 기준)은 **목적 축 채택**(규칙 1 정합). D(원장 신규 오류)·B 정정(7커밋 미푸시)도 확정. 집행: 리더 레인(푸시·PR·원장 L-1) 선행 후 배치 — `142dac6`(N-20 `has_visible_reason`·U-3)·`496319d`(B2·B3 음성 7테스트 상시화)·`bbbdb6b`(B1 선언 하한 기준 재설계+내용 파싱 · B4 두 갈래 · B5 거부-겹침 fixture+N-03 동일 커밋 · U-2·U-3) + §4-terdecies(P1 지표 항진명제 정정 · P2 규약 축소 · P3). 전체 게이트 exit 0(성질 153·단언 630). 사고 2건 기록: 테스트가 제품 파일 덮어씀(4번째 오염, 새 형태 — 격리 수정) · 스테이징 경합 재발(→ `git commit -- <경로>` 규율). 정본 `reviews/13_regression-and-pins_cross.md`.

**게이트 14(`14_floor-hardening`) 3단계 완주(2026-08-15)** — 대상 `4dafde5..bbbdb6b`. **codex 부분 수령**(한도 소진 — 확정 지적 0 = 미수령이지 무지적 아님, 완전 재실행은 Phase 3 착수 조건). **[2026-08-15 갱신] codex 완전 재실행 = 완료.** `c932b4f` 에 보존 — `reviews/14_floor-hardening_codex-reviewer.md` 의 「완전 재실행 (2026-08-15) — **완전 수령**」 절(스크립트 종료 코드 0 · assistant 메시지 5건 · findings **6건 = high 5 · medium 1** · verdict `needs-attention`)과 job 로그 보존본 `reviews/14_floor-hardening_codex-rerun.log`. 1차 부분 수령분은 **삭제하지 않고 병기**한다. ~~**재실행분 교차 종합은 진행 중(별 레인) — 그것이 끝나기 전에는 "게이트 14 완전 재실행" 착수 조건을 닫지 않는다**(수령은 리뷰이지 대조가 아니다).~~ **[2026-08-18 갱신] 교차 종합 완료 (`4dabc0d`) — 게이트 14 종결.** `reviews/14_floor-hardening_cross.md` 의 「완전 재실행분 교차 종합 (2026-08-18)」 절(§R)이 정본이다: **합의 2**(R-1·R-3) · **충돌 2**(R-2⑴·R-5) · **codex 단독 2**(R-4·R-6 잔여), §R.4.5 「전제 확인 필요」 **6건 전부 코드 대조**(전제 오류로 무효화된 지적 **0건**), **「게이트 14 완전 재실행 = Phase 3 착수 조건」 충족 판정**(§R.10). 미해소 항목과 그 마감은 아래 「게이트 16 후속」 §1. 교차 종합 시점 판정은 "종료를 막는 것 = 기록 계열(F-1·F-2·F-12)+N-13·N-14·R-10"이었고 — **그 A 목록이 후속 배치(`b13d502`·`787bf69`·리더 커밋들)로 전건 해소되어 조건부 종료 판정(아래 절)으로 이어졌다.** 러너 재확인: 최종 배치 포함 run 31868504346 — kotlin·quality·frontend success(llm-lane cancelled — Phase 5 항목, 5차 실행으로 대체). 충돌②는 §4-quaterdecies(긍정 목록)로, 억제 층 계열은 §4-quindecies로 종결. 정본 `reviews/14_floor-hardening_cross.md`.

**열린 판정 — 처분 완료분(2026-08-14~15)**: ① **보정 채택 판정식의 본문 손실 축 — 사용자 결정(2026-08-15): ⓒ Phase 5 프롬프트 작업으로 이월.** 채택 판정식은 현행 2축(자리표시자·위반 건수) 유지, 본문 손실 기준 신설 여부는 Phase 5 프롬프트·긴문서 작업에서 골든셋 절대 팩트축과 함께 판정. Phase 2 종료를 막지 않는다 ③ absent/present 문언 — **종결**(§4-decies.3 정정) ④ 연도 배열 — **종결**(§4-decies.4: Luhn 별건 회부 → 리더 승인 → `2839ec8`·`516c0e9` 집행, present 방향).

**열린 판정 — 잔여**: ② Y-4/C-07(재시도를 가로지르는 누계 상한 — "변환 1건"의 요구 해석, **Phase 5**) · Luhn 재판정 조건(파일럿에서 카드 유입 관측 시 — privacy-gate) · X-4 잔여(문장 분리 조작 입력은 하한을 낮춘다 — 한계 선언됨) · **llm-lane 30분 타임아웃**(2026-08-15 러너 실측: `-m llm` 스텝이 30분 상한 초과로 취소 — 키 부재가 아니라 실행 시간. 골든셋 실 API 실행이 이 레인의 목적이므로 상한 상향 또는 문서 표본 분할 필요, **Phase 5 착수 전**) · ~~**codex 한도 소진**(2026-08-20 복구 — 게이트 14는 부분 수령으로 진행, stop-time 게이트도 그때까지 같은 오류)~~ → **해소 (2026-08-15).** 게이트 15 codex 리뷰가 2차 시도에서 `EXIT=0` 으로 성공했고 게이트 14 완전 재실행도 완료됐다(`c932b4f`). 남은 것은 재실행분 **교차 종합**이다.

---

## Phase 2 종료 판정 — **조건부 종료** (2026-08-15, 리더)

계획 §5 Phase 2의 명시 종료 조건("**외부 API·DB 없이 실행하는 parity suite가 동일 결과를 냄**")은 **충족**이다 — 단 "동일 결과"의 기준은 2026-08-12 전환대로 Python 출력이 아니라 **요구사항이 요구하는 성질**이다.

**근거 (실행 경로 포함):**
- 전체 parity 게이트 **8/8 도메인 · 성질 153건 · 단언 630개 · 판정 보류 0 · 불충족 0 · exit 0** — `ci:kotlin` 러너 실측 2회(run **31854263996**, 최종 배치 포함 run **31868504346** — kotlin·quality·frontend 전부 success). 수치 인용 정본은 러너 로그·재현 명령(`parity-full-gate.txt` 규약).
- 참고 갈림은 전건 원장 기록(`parity/reference-ledger/`) — 기준이 요구사항이므로 갈림은 차단이 아니라 기록이다. Python이 과잉 마스킹하는 방향 4건 포함 — 요구사항은 Python보다 넓기도 좁기도 하다.
- 보안: **§6 Security 게이트 통과**(privacy-gate §4-duodecies 최종 감사 + §4-quindecies 통합 재확인 — 억제 층 계열 미해제 0). 마스킹 선행 불변식·2종 범주 유지, 4조합 차분으로 Luhn·재탐색의 상쇄 분해 실측.
- 리뷰: 게이트 07~14 완주(3단계 규약 — 차단급 독립 합의 3회가 각각 실결함을 잡음). **게이트 14는 codex 부분 수령**(한도 소진) — 아래 단서. **[2026-08-15 갱신]** 게이트 **15**(`5797d87..614afed`)도 3단계 완주했고 게이트 14 codex **완전 재실행**이 수령됐다(`c932b4f`) — 이 판정문은 게이트 14 시점 기록이므로 고치지 않고, 갱신분은 단서 1과 아래 「게이트 15 후속」 절에 적는다.

**아니오로 남기는 1행과 마감:**

| 행 | 사유 | 마감 |
|---|---|---|
| L348 내보내기 | F-3·F-4(export 파일명 축의 C1·`*?<>` 무단언 — 코드가 아니라 fixture 커버리지) | **Phase 4 착수 전** |

> **[게이트 15 X14, 2026-08-15] 정면 모순 해소 — 이 표에서 「L344 프롬프트 · L345 스타일 규칙」 행을 뺀다.**
> 원장이 같은 두 행을 Phase 2 표에서는 `예`, 이 표에서는 `아니오` 로 **동시에** 적고 있었다(cross X14 — 이 축은
> codex 프롬프트 대상 파일 목록 밖이라 **교차 검증 없는 Claude 단독 판정**이다). 참인 쪽은 `예` 다: X-9 마감의
> 뒤 조각(CI 재생성 diff)이 `04ced00` 으로 `ci:quality` 에 배선되어 승격 근거가 완결됐다(리더 판정 — **마감
> 축소가 아니라 CI 배선 완성으로 승격 유지**). 근거 전문은 Phase 2 표의 두 행에 있다. **표 제목의 "3행"도
> "1행"으로 고쳤다** — 세지 않는 제목은 다음 사람이 다시 세지 않는다.

**단서 (판정문의 일부):**
1. **codex 게이트 14 부분 수령** — 확정 지적 0건은 무지적이 아니라 미수령이며, 부분 출력만으로도 Claude 해소 판정 안쪽을 한 번 뚫었다(Mc 갈래 — 이후 긍정 목록 교체로 해소). **게이트 14 완전 재실행을 Phase 3 착수 조건으로 이월한다**(codex 한도 복구 2026-08-20 이후 — 리더 선택 ⓑ, 하네스 규약의 기본 경로. ⓐ 크레딧 구매·ⓒ 연기는 사용자에게 열려 있음).<br>**[2026-08-15 갱신] 재실행 = 완료, 착수 조건 = 아직 열림.** codex 완전 재실행이 한도 복구를 기다리지 않고 성공했고(`c932b4f` — high 5·medium 1, 로그 보존본 동봉) 부분 수령분은 병기 보존됐다. **그러나 이 조건이 요구한 것은 "리뷰를 받는 것"이 아니라 "받아서 대조하는 것"이다** — 재실행분 교차 종합이 별 레인에서 진행 중이고, **그것이 끝날 때까지 이 착수 조건은 닫지 않는다.**<br>**[2026-08-18 갱신] 이 단서를 닫는다 — 교차 종합 완료(`4dabc0d`), 착수 조건 「게이트 14 완전 재실행」 충족.** 대조까지 끝났고(§R.3 합의 2·충돌 2·codex 단독 2 / §R.2 전제 6건 대조, 무효화 0) `reviews/14_floor-hardening_cross.md` §R.10 이 충족을 명시 판정했다. **닫는 것은 "조건"이지 "지적"이 아니다** — 미해소 6갈래(R-1 · R-2⑴⑵ · R-5 · R-6⑴⑵ · R-4⑶)는 살아 있고 마감은 **Phase 4 착수 전**이다. 그중 **R-5 는 사용자 판단 대기로 올린다**(L345 승격의 독립 입력 축 — 아래 「게이트 16 후속」 §3 ④). 착수 조건이 요구한 R-4⑴(전체 게이트 하한의 유일 본류 호출선 무검증, Y-7 변이 실측)은 **`48a791c` 로 해소**됐다. 별건 **Z-a·Z-b·Z-c** 는 「게이트 16 후속」 §5 에 이월 등재한다.
2. **privacy-gate 미확인 3건은 "확인하지 않음"이지 "위반 없음"이 아니다** — 불변식 3·5·7~12는 대상 코드가 Phase 3 이후에 생긴다.
3. **Phase 5 이월분**: 보정 채택 본문 손실 축(사용자 결정 ⓒ, 2026-08-15) · C-07(누계 상한 요구 해석) · llm-lane 30분 타임아웃 · Luhn OCR 잔여·실문서 사전확률(같은 파일럿 관찰 창).
4. **미결 원장(마감 명시)은 각 Phase 착수 게이트에서 다시 센다** — Phase 3 착수 전 항목들(Flyway TOCTOU·`CoreModuleBoundaryTest` 우회·게이트 우회 자동 회귀 등 기존 절)이 그대로 살아 있다.<br>**[2026-08-15 갱신 — 게이트 15 재점검을 실제로 수행했다]** 세 항목의 판정이 갈렸다: **게이트 우회 자동 회귀 = 해소**(`42f9e20` + `04ced00`) · **Flyway TOCTOU = 부분 해소**(원 지적은 `f9d78e0` 으로 닫히고 X8·X9·X10·X12 4건이 남아 마감이 **Phase 3 첫 기동/배포**로 이동) · **모듈 경계 우회 = 부분 해소 유지**(X4·X4b, 마감 **Phase 3 모듈 추가 시**). 이 문장의 열거를 그대로 두면 닫힌 것과 열린 것이 한 덩어리로 읽히므로 갈라 적는다. 정본은 아래 「게이트 15 후속」 §1.

다음 조각은 직전 세션이 예고한 **api 엔드포인트**(Phase 3 영역 — 인증·작업 공간·contract test 첫 실행)다. Phase 3 착수는 위 단서 1(게이트 14 재실행분 **교차 종합**)과 Phase 3 착수 전 미결 원장의 재점검을 선행한다 — 후자는 아래 「게이트 15 후속」 절이 수행 결과다.

---

## 게이트 15 후속 — Phase 3 착수 조건 현황 (2026-08-15, 리더)

정본은 `reviews/15_phase3-preflight_cross.md` 다. 이 절은 그 교차 종합의 §5.1·§5.2·§5.3 과 후속 수정 배치
(`614afed..c932b4f`, 7 커밋)를 원장 형식으로 옮긴 것이며, **수치·판정에는 러너 run id 또는 커밋 해시를 함께 적는다**
(수치 인용 규약 — 기준 시점 없는 절대 수치는 다음 커밋에 거짓이 된다).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다. 이 절의 표들은 종료 조건 표가 아니라 **처리 현황
> 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의 대상 4표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다.
> **검사가 닿지 않는 자리에서 어휘가 갈리면 그 갈림이 다음 표로 번진다.**

### §1 — Phase 3 착수 전 미결 3행 재판정 (cross §5.1)

| 원장 항목 | 충족 | 실행 경로 | 근거 | 미해결 항목 | 마감 |
|---|---|---|---|---|---|
| Flyway 지문 TOCTOU + Alembic head 미확인 (`ef7b4a8`) | 아니오 | `ci:kotlin` | **X11 이 `f9d78e0` 으로 닫혔다.** 종전 단언은 `failures.isEmpty()` 하나, 즉 "예외가 안 났음"이었고 일회용 worktree 에서 `withAdvisoryLock` 을 `run` 으로 바꾸자 **4회 연속 초록**(탐지력 0 실측)이었다. 새 단언은 `MigrationStatementTracer` 로 동기화 문장을 걷어낸 **임계 구간의 스레드 간 겹침 0** 을 직접 재고, T-A⑶ 이 재현 0건이라 지적한 TOCTOU 축에 재현 테스트를 더했다 — 잠금 제거 시 **4회 빨강**(겹침·TOCTOU 둘 다), 잠금 유지 시 4회 초록. 즉 **"잠금이 실제로 판정 구간을 지킨다"가 실측됐고 원 지적의 TOCTOU 축은 닫혔다.**<br>**그런데도 `예` 로 올리지 않는다** — 원 종료 조건 문구의 뒤 conjunct 가 「Alembic head **미확인**」이고, X8 이 그 자리를 그대로 열어 둔다(빈 `alembic_version` 을 정상 head 로 승인 → head 는 여전히 증명되지 않는다). X9·X10·X12 는 그 문구 밖이지만 같은 파일·같은 마감이라 함께 센다 | **4건** — X8 승인 조건(빈 `alembic_version`, `FlywayBaselineGuard.kt:175`) · X9 자원 교착(잠금 연결을 쥔 채 Flyway 재진입, 풀 크기 1에서 자기 교착, `:61-71`) · X10 보호 범위 선언(세션 advisory lock 이 **비협조 DDL** 의 TOCTOU 를 못 닫는데 주석 `:57-60` 이 더 넓게 선언, `:57-71`) · X12 관측성(대기 상한·진단 로그 부재 → 기동이 아무 로그 없이 정지, `:93-109`) | ~~Phase 3 착수 전~~ **Phase 3 첫 기동/배포** |
| `CoreModuleBoundaryTest` 우회 / 모듈 경계 (`ef7b4a8`) | 아니오 | `ci:kotlin` | **부분 해소 유지 — codex 관점 반영 후 우회 경로가 늘었다.** cross §2 전제 ①이 codex 인용 4건을 코드 대조로 **전부 사실**로 확정했고(행 인용 `282-320` vs `237-348` 불일치 의심은 **층이 달랐던 것**으로 해소 — 앞은 판정 본체, 뒤는 주석·허용 지도·태스크 등록·`check` 배선 전체), X4 는 Claude K-A 와 codex C15-4 의 **독립 합의**다 | **2건 (하위 3갈래)** — X4 도달 범위(검사 대상이 `api`·`worker` 하드코딩 2모듈뿐 — `application` 무검사, `core` 순수성 미해결, `build.gradle.kts:264-268`) · X4b 판정 우회 3갈래(파일 의존 `compileOnly(files(project(...)))` `:282` · configuration-time 스냅샷 `:282-291` · resolved artifact displayName **정확 일치** 비교 `:318`) | ~~Phase 3 착수 전~~ **Phase 3 모듈 추가 시** |
| 게이트 우회 시나리오 자동 회귀 (`42f9e20`) | 예 | `ci:quality` | **X2 의 배선·CI 축이 `04ced00` 으로 닫혔다.** 종전 회귀 6종은 helper(`runtime_problem`·`provenance_problems`)를 **직접** 불러 본류 배선을 보지 않았고, 본류 유일 호출부 `compare_parity.py:1984`(runtime)·`:2282`(provenance) 한 줄을 지우면 여섯 테스트가 전부 초록인 채 비교기가 위조 runtime·축소 fixture 를 다시 승인했다. 처방은 `main()` 을 그대로 실행하는 **본류 회귀 4건**(위조 runtime·미선언·축소 fixture·대조군) + `ci.yml` 경로 명시(`tests/test_parity_ci_gate.py` — 저장소가 두 파일에 이미 적용해 둔 규약의 세 번째 대상). 음성 대조(일회용 worktree, `git checkout` + sha256 복원): runtime 호출부 제거 → **신규 2건만** 빨강 / provenance 호출부 제거 → **신규 1건만** 빨강, 기존 helper 테스트는 양쪽 변이에서 전부 초록 | - | ~~Phase 3 착수 전~~ **해소됨** |

**판정 방법 한 줄.** 세 행 모두 "고쳤는가"가 아니라 **원 종료 조건 문구가 요구한 것이 충족됐는가**로 갈랐다.
그래서 TOCTOU 는 실측이 났는데도 `아니오` 이고(문구의 뒤 conjunct 가 안 닫혔다), 게이트 우회는 `예` 다
(문구가 요구한 것은 "자동 회귀로 고정"이고 그것이 본류·CI 양쪽에서 닫혔다).

### §2 — Phase 3 착수 차단 6건 처리 현황 (cross §5.2 (가))

| # | 항목 | 처리 | 실행 경로 | 근거·음성 대조 | 상태 |
|---|---|---|---|---|---|
| **X3** | 파이프라인 종료 코드가 검증 근거를 무효화한다 (차단②, Claude 단독 판정) | `65a7eb6` | `local:.claude/skills/kotlin-migration/scripts/run_gate.sh` | 러너 `run_gate.sh` 신설(탐지형) — 명령을 `bash -o pipefail` 아래에서 돌려 파이프 안 어느 단계의 실패든 비-0 으로 전파하고 실행한 명령·종료 코드를 기록한다. 음성 대조: 실패 `pytest \| tail` 이 러너 없이 zsh·bash 모두 **exit 0**(결함 재현), 러너 경유 시 **exit 4** 전파. `SKILL.md` 규칙 5에 "게이트 명령은 러너 경유 또는 파이프 금지" 규약 추가.<br>**병기 — 이 장치의 도달은 `local` 이고, 파이프 사용 자체를 기계로 탐지하는 강제자는 이 저장소에 `0개`다.** 러너를 경유하지 않은 파이프는 못 잡는다. 규약이 방어의 전부이며, 이 사실을 장부에서 지우지 않는다 | 수정 완료 · **강제자 0 은 열린 채** |
| **X2** | 게이트 우회 회귀가 본류 배선을 놓치고 CI 경로 명시도 없다 | `04ced00` | `ci:quality` | 위 §1 세 번째 행과 같은 근거(본류 회귀 4건 + `ci.yml` 경로 명시 + 호출부 제거 음성 대조). 부수로 `4cba492` 가 같은 규약을 `tests/test_python_snapshot_guard.py` 에도 적용했다(네 번째 대상) | 수정 완료 · **심각도는 사용자 판단 대기**(§3 ①) |
| **X14** | 원장이 배치를 따라오지 못했고 두 자리가 정면 모순 | **이 갱신** | `ci:quality` | Phase 2 표(`예`)와 종료 판정 절(`아니오`)이 같은 두 행(L344·L345)을 **동시에** 주장하던 정면 모순을 해소했다 — **참인 쪽은 `예`** 이고 근거는 `04ced00` 의 `ci:quality` 배선이다(위 Phase 2 표 두 행 + 종료 판정 절의 인용 상자). cross 가 지목한 여덟 자리 `:283`·`:284`·`:287`·`:289`·`:326`·`:344-345`·`:408`·`:415` 는 **행 번호가 아니라 내용으로 찾아** 대조했다(cross 작성 시점 번호라 지금은 밀려 있다). 이 축은 codex 프롬프트 대상 파일 목록 밖이라 **교차 검증 없는 단독 판정**이라는 사실을 함께 남긴다(cross §6.1). 표기 검사기는 `ci.yml` quality 잡이 경로 명시로 부른다 | 해소 |
| **X11** | 동시 기동 테스트에 음성 대조가 없어 잠금을 떼도 초록일 수 있다 | `f9d78e0` | `ci:kotlin` | §1 첫 행과 같은 근거(탐지력 0 실측 → 새 단언 4/4 빨강). `MIGRATION_LOCK_KEY` 를 `internal` 로 연 이유도 같은 계열이다 — 탐침이 키를 베껴 두면 키가 바뀌는 날 **다른 잠금을 잡고도 초록**으로 남는다 | 해소 |
| **X6** | F3 금지에 강제자 0 + 정반대 경로가 정상으로 단언돼 있다 | `f9d78e0` (Kotlin 단언) · `526bfeb` (전파) | `ci:kotlin` | `FrameworkErrorContractTest` 가 계약이 **금지한** 조합(`email` 을 `NotBlank` 로 거절)을 정상이라 단언하고 있었다 — 문서가 금지하고 테스트가 허용하면 다음 사람이 읽는 것은 테스트다. 프로브 필드를 계약에 없는 `probe` 로 바꿔 금지 조합 재현을 없애고, 다섯 필드가 내야 하는 반대쪽 모양(`서비스 층 길이 위반은 422 문자열이다`)을 나란히 고정했다. 두 경로 모두 422 라 상태 코드로는 구분되지 않아 **`detail` 의 타입까지** 단언한다. 음성 대조: 문자열 단언을 배열이 나오는 경로로 겨누면 빨강(`Expected a string at JSON path "$.detail" but found: [...]`). 전파 축은 `526bfeb` 가 `03_kotlin-implementer_phase3-preflight.md` §5로 옮겼다(그 전 grep 0건).<br>**[게이트 16 정정, 2026-08-18] 이 행의 「해소」는 과대 표기였다**(cross §5.4·§6.2). X6 의 **항목명이 두 축**을 담는데(ⓐ 강제자 0 · ⓑ 정반대 경로가 정상으로 단언됨) `f9d78e0`·`526bfeb` 가 닫은 것은 **ⓑ와 전파 축뿐**이다. ⓐ를 들고 있는 것은 **바로 아래 X5 행**이고 그 행의 실행 경로는 `안 돎` 이며 원장이 같은 칸에 "고친 것은 계획이고 계약 테스트는 아직 없다"고 정직하게 적었다 — **정보는 원장에 있었으나 상태 열에서 사라졌다.** 세 관점이 같은 방향으로 모였다: Claude 1차 「부분 해소」 · codex K16-3(high) · 이 회차 코드 대조(`git grep X-F1[123]` → Markdown **2파일뿐**, 실행 소스 **0건**). **X3 행이 「수정 완료 · 강제자 0 은 열린 채」로 같은 함정을 피한 형태를 본보기로 썼다** | **부분 해소** — ⓑ(정반대 단언)·전파 축은 닫힘 · **(a) 강제자 0 은 X5(`안 돎`)가 들고 있다** · 마감 **Phase 3 해당 DTO 구현 커밋**(양 레인 합의) |
| **X5** | test-plan 이 `password` 를 한 행도 덮지 않는다 | `526bfeb` | `안 돎` | X-F11(8자 미만 → 422 + `detail` **문자열 타입**, 경계값 양쪽) · X-F12(원시 8자·trim 후 1자 입력이 통과 — **원시 측정 축**) · X-F13(email 정규화 후 255 초과 거절 + 문구가 형식 오류와 동일) 세 행 신설 + 다섯 필드의 거절/통과/측정 커버리지 표. 계약 문구는 **옮겨 적지 않고** 키 경로 + 행으로 지목한다(별건 1의 전사 드리프트 회피).<br>**실행 경로가 `안 돎` 인 이유** — 고친 것은 계획이고 계약 테스트는 아직 없다. 실제 실행은 Phase 3 첫 계약 테스트에서 처음 생긴다 | 계획 수정 완료 · **실행은 Phase 3** |

**X-F12 의 기대값은 X13 판정에 종속된다** — `password` 의 `measured_on: raw` 가 유지되면 "원시 8자 통과"가 맞고,
뒤집히면 그 행의 기대값이 반대가 된다. §3 ② 참조.

### §3 — 사용자 판단 대기 3건 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

| # | 쟁점 | codex 쪽 | Claude 쪽 | 리더 권고 | 상태 |
|---|---|---|---|---|---|
| ① | **X2 의 심각도** — 게이트 우회 회귀 6종 | **high**(= 차단②) — "helper 를 직접 호출하므로 실제 비교 흐름에서 해당 helper 호출을 제거해도 테스트는 모두 통과하며 비교기는 위조 runtime 또는 축소 fixture 를 다시 승인한다. CI 도 이 파일을 명시 실행하지 않아 파일 삭제 역시 테스트 한 건 감소로 끝난다"(C15-6) | **적정 — 지적 없음**(T-B). "격리가 옳고(저장소에 쓰는 테스트가 없다), 대조군 3건이 상시로 남아 **음성 대조가 일회성인 자리를 양성 통제로 상시화**했으며, 음성 대조 대응이 5:5 로 일관된다" | **codex 판정 채택 권고.** 두 관찰은 **다른 층**이고 양쪽 다 사실이다(helper 내부 탐지력 vs helper·본류 배선). 갈림을 가르는 것은 **이 배치가 스스로 선언한 것** — "게이트 우회를 자동 회귀로 **고정한다**" 이고, 그 선언 기준으로는 우회 경로가 helper 안이 아니라 **호출부**에 남아 있었다. 선언한 범위와 실제 도달을 대조하는 규칙이 그대로 적용되는 자리다. **수정은 이미 완료**(`04ced00`)이므로 이 판단은 심각도 라벨과 이력에만 영향을 준다 | **사용자 확인 대기** |
| ② | **X13** — F3 계약의 `measured_on: raw` 2필드 | **high** — "`password` `\"       a\"` 는 원시 8자로 통과하지만 trim 후 1자, 3,999자 본문 + 제어문자 2개는 원시 4,001자로 거절되나 정규화 후 3,999자다. **계약을 그대로 구현하면 확정된 정규화 후 경계를 위반한다.** `text` 비대칭을 Phase 4 로 미룬 조항도 확정된 요구를 유예한다"(C15-7) | **원시를 전제로 수용, 별도 지적 없음**(C-B). "F3 판정 자체는 적정하다 — 근거 G1+G4 가 실측으로 뒷받침되고 대조 사례(`limit`·`offset`)가 판정 범위를 근거 안에 묶는다. **계약 소유자 단독 판정 범위 안이다**" | **전반부 기각 · 후반부 유지 권고.** ⑴ C15-7 의 전제("다섯 필드 제약은 정규화 이후 길이를 잰다")는 **계약이 아니라 리더 focus text 에서 왔다** — codex 산출물 §2 채점 기준 7번이 그렇게 적었는데 계약 자신은 5필드 중 **2필드를 명시적으로 원시**로 적는다(`contracts/easy-doc-v1.yaml:395`·`:400`). 프롬프트 전제 오류이므로 전반부는 기각. ⑵ 후반부(`x-open-asymmetry` 가 `text`/`edited_text` 비대칭을 Phase 4 로 유예)는 **전제와 무관하게 계약 본문에 근거**하므로 독립 항목으로 살린다. **기각해도 원문은 남긴다** — codex 가 본 "계약 내부의 측정 기준이 필드마다 다르다"는 X5·X6 이 지적한 구현 위험과 같은 자리를 가리킨다 | **사용자 확인 대기** (§2 X-F12 기대값이 이 판정에 종속) |
| ③ | **X8·X9 의 심각도** — 같은 사실, 갈린 척도 | **high** — X8 "`DELETE FROM alembic_version` 을 실행하면 이 분기가 그대로 통과한다. 실제 Alembic revision 은 전혀 증명되지 않았다"(C15-3) / X9 "`…HIKARI_MAXIMUM_POOL_SIZE=1` 이면 Flyway 가 자체 연결을 얻지 못해 **connection timeout 후 기동에 실패한다.** 동시성 테스트는 무제한 연결을 여는 `DriverDataSource` 를 써서 이 반례를 재현하지 않는다"(C15-1) | **권고** — X8 "테이블 부재(순수 Kotlin DB — 정상)와 행 0은 성격이 다르다 … 테스트도 `DROP TABLE` 경로만 덮고 빈 테이블 경로는 없다"(K-E) / X9 "`maximum-pool-size` 가 없어 기본 10이므로 **실질 위험은 낮다**. 운영에서 풀을 1~2로 줄이면 교착하므로 설정 주석에 남기는 편이 좋다"(K-C) | **판정 유보 — 어느 쪽이든 마감은 같다.** 사실관계는 양쪽 서술이 일치하고(`FlywayBaselineGuard.kt:61` 에서 연결을 빌려 `:69`·`:71` 에서 Flyway 재진입 / `:175` 의 `actual.isEmpty()` 조기 return) 갈린 것은 **"기본 설정에서 안 터지면 권고인가"**라는 기준뿐이다. 심각도가 high 든 권고든 **마감은 Phase 3 첫 기동/배포**로 같아 착수 판정을 바꾸지 않는다 | **사용자 확인 대기** |

### §4 — 사실 기록 2건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **`ef7b4a8` 이 `build.gradle.kts` ktlint 위반 6건을 안은 채 커밋됐다.** Gradle **up-to-date 캐시**가 빨간 게이트를 가려 로컬에서는 초록으로 보였고, 인접 파일을 고치며 캐시가 풀리자 드러났다. 실제로 잡은 것은 **CI 러너 run `31871678126` 의 kotlin 잡 failure** 다. 해소는 `1cb7bdf`(자동 포맷 아닌 수동 수정 — `//:` 표기의 의도를 도구가 판단할 수 없다), 재검증은 `./gradlew ktlintCheck detekt build --rerun-tasks --continue` exit 0 (**81 actionable tasks 전부 실행 = 캐시 0**, FAILED 0, 530 tests / 0 failures) | 커밋 `ef7b4a8` · CI run **31871678126** · 커밋 `1cb7bdf` | **T-D(X3)와 같은 구조다** — "게이트를 돌렸다"는 근거가 "게이트가 실제로 돌았다"를 뜻하지 않는 자리. X3 은 파이프가 종료 코드를 삼켰고 여기서는 캐시가 실행 자체를 건너뛰었다. 대리 지표를 실물로 읽는 같은 결함이며, `--rerun-tasks` 로 캐시 0 을 확인한 것이 그 자리의 음성 대조다 |
| ② | **최신 CI 실행 run `31889034904`(HEAD `c932b4f`)** — `kotlin`·`quality`·`frontend` **success**, `llm-lane` 은 **30분 타임아웃으로 취소**(cancelled) | CI run **31889034904** | llm-lane 취소는 새 사건이 아니라 **기존 이월 항목의 반복 관측**이다(위 「열린 판정 — 잔여」의 llm-lane 30분 타임아웃, 마감 **Phase 5 착수 전**). 처리 방침은 상한 상향 또는 문서 표본 분할로 기존과 같고, 이 관측이 마감을 앞당기지 않는다 |

### §5 — 이월 항목 추가 (cross §5.2 (다) · §6.2)

| 항목 | 출처 | 마감 |
|---|---|---|
| **focus text 계약 전사 구조** — 리뷰 프롬프트가 계약 요약을 **손으로 옮겨 적는** 구조라 원본과 갈렸고(그 전제가 C15-7 의 근거가 됐다), 이 하네스가 반복해 고쳐 온 레인 간 규약 드리프트와 같은 형태다. 처방 후보: focus text 작성 규약을 "계약 인용은 전사하지 말고 **파일·행으로 지목**"으로 | cross §6.2-1 (별건 — 이 회차 교차 검증 없음) | **다음 게이트** |
| **`ci.yml` 경로 명시가 열거로 유지되는 구조** — `:124`·`:129` 가 두 파일을 이름으로 적고 세 번째가 빠져 X2 의 CI 축이 됐다. `04ced00`·`4cba492` 로 3·4번째를 더했으나 **열거가 근거를 만드는 구조** 자체는 그대로다. CLAUDE.md 규칙 4 가 `mypy` 에서 고친 형태와 같은 자리 — 개별 파일을 계속 더할지, "가드 성격 테스트는 전부 경로 명시"를 **구조로 유도**할지 | cross §6.2-2 (별건) · `4cba492` 커밋 메시지가 같은 이월을 명시 | **다음 게이트** |
| **X1 — 스냅샷의 `app/**` 삭제 후 지위(P-C)가 어느 문서에도 결정으로 없다** | cross X22(= P-C, `dump_python_snapshots.py:258-265`) | **`app/**` 삭제 결정 시점** |
| **X13 — F3 `measured_on` 비대칭** (계약 자신이 Phase 4 로 적었다) | cross §5.2 (다) | **Phase 4** (전반부 기각 여부는 §3 ②) |
| **X7 — `x-request-field-constraints` 는 비표준이라 실행 소비자가 0이고, Phase 6 생성기가 다섯 필드를 단순 string 으로만 본다** | cross X7 (`contracts/easy-doc-v1.yaml:332`·`426-429`) | **Phase 6** |
| **X16 — `EXPECTED_ALEMBIC_HEAD = "0006"` 의 지시 대상이 삭제 대상(`migrations/`) 안에 있다** | cross X16 (`FlywayBaselineGuard.kt:200-201`) | **`app/**` 삭제 시** |
| **X22 — `app/**` 삭제 후 스냅샷의 지위 판정** (위 X1 과 같은 자리를 parity 축에서 본 것) | cross X22 | **`app/**` 삭제 결정 시점** |
| **X15**(OQ-3 계약 파일 직접 파싱인데 파서 0건, 마감 Phase 3 첫 계약 테스트) · **X17**(동시 기동 서술이 worker 설정과 어긋남, Phase 3) · **X18·X19·X20·X21**(권고 4건 — 산문 대응표 · `parity-domains.txt` 머리 주석 어긋남 · 정체성 키 클립이 마크다운 표기 안쪽을 자름 · 미사용 인자) | cross §3.3 · §5.2 (나)(다) | 각 행 명시 마감 · 권고 4건은 미지정 |

**§5 의 X1·X22 가 같은 마감을 갖는 이유** — 둘 다 `app/**` 이 사라지는 **되돌릴 수 없는 창**에 걸려 있다.
`ci:quality` 의 스냅샷 재생성 diff 스텝은 `app/**` 이 지워지면 exit 2 로 죽는데, 그 상태를 사면하지 않은 것이
의도된 설계다(`ci.yml` 주석 — "조용히 지나가면 안 되는 자리"). 사면을 붙이는 순간 이 이월 항목이 소멸한다.

---

## 게이트 16 후속 — 게이트 14 종결과 Phase 3 착수 조건 (2026-08-18, 리더)

정본은 두 파일이다 — `reviews/14_floor-hardening_cross.md` 의 **「완전 재실행분 교차 종합 (2026-08-18)」 절(§R)**
과 `reviews/16_gate15-fixes_cross.md`. 이 절은 그 둘의 판정과 후속 수정 배치(`1cb7bdf..48a791c`, 5 커밋)를
원장 형식으로 옮긴 것이며, **수치·판정에는 러너 run id 또는 커밋 해시를 함께 적는다**(수치 인용 규약 —
기준 시점 없는 절대 수치는 다음 커밋에 거짓이 된다).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다. 「게이트 15 후속」과 마찬가지로 이 절의
> 표들은 종료 조건 표가 아니라 **처리 현황 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의 대상
> 4표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다. **검사가 닿지 않는 자리에서 어휘가 갈리면 그 갈림이
> 다음 표로 번진다.**

### §1 — 게이트 14 종결 (재실행분 교차 종합 `4dabc0d`)

**Phase 2 종료 판정 절의 단서 1이 닫혔다.** 그 단서가 요구한 것은 "리뷰를 받는 것"이 아니라 "받아서
대조하는 것"이었고, 그 대조가 끝났다. 집계는 **합의 2 · 충돌 2 · codex 단독 2**이며, `codex-reviewer` 가
판정하지 않고 넘긴 **§R.4.5 「전제 확인 필요」 6건을 전부 `f079492` 판 코드로 대조**해 **전제 오류로
무효화되는 지적 0건**을 확인했다(4참 · 1부분(재현 불필요) · 1무영향). `reviews/14_floor-hardening_cross.md`
§R.10 이 **「게이트 14 완전 재실행 = Phase 3 착수 조건」 충족**을 명시 판정한다.

**닫은 것은 조건이지 지적이 아니다.** 아래가 그 잔여다.

| # | 항목 | 상태 | 실행 경로 | 근거 | 마감 |
|---|---|---|---|---|---|
| **R-1** | 금지 파일명 집합(`*?<>`·C0/C1 대부분)이 산출물 검사에 도달하지 않는다 | **미해소** | `ci:quality` | Claude **F-4 와 합의**(codex high 0.99). `dump_parity_fixtures.py` 의 `dangerous` 9개 손열거 무변경 · `export.json` 동일 · 파일명을 직접 거부하는 checker 신설 없음 · 테스트 참조 0건(Y-10). **어느 커밋도 이 자리를 건드리지 않았다** | **Phase 4 착수 전** · **L348(내보내기) 재판정의 직접 근거** |
| **R-2 ⑴** | 단언 **종류의 존재**를 고정하는 독립 invariant 가 0건 (자기채점) | **미해소 — 충돌** | `안 돎` | codex: "양성/음성 단언의 존재 기준이 모두 `build_export` 자신" / Claude 1회차 §4: "지적 없음 — `FILENAME_FORBIDDEN` 을 `Export.kt` 에서 읽지 않는 선택은 X-4형 순환 회피로 옳다". **두 진술이 서로 다른 축**이다(원료의 순환 vs **적용 여부**의 순환) — Claude 판정이 codex 축을 덮지 않는다(§R.4.1) | **Phase 4 착수 전** |
| **R-2 ⑵** | 비교기 `TITLE_MARKER_LENGTH` 단독 변이가 자기 신고되지 않는다 | **미해소** | `안 돎` | C-5(생성기 표지 빈 목록)와 **별개다** — C-5 는 비교기 규칙이 **호출되지도 않는** 방향, R-2⑵ 는 **호출되지만 요구가 1자 조각으로 무해해지는** 방향. `b13d502` 의 정정 주석은 「한쪽이 빌 때」 두 경우만 다루고 **양쪽 다 표지를 만드는데 규칙만 갈린 경우는 코드에도 기록에도 없다**(§R.5) | **Phase 4 착수 전** |
| **R-3** | 의미 없는 문장부호·결합 표시가 개인정보 적중을 진단 없이 억제한다 | **해소** (`787bf69`) | `ci:quality` | 부정 목록(`_INVISIBLE_CATEGORIES`) → **긍정 목록**(`L*`/`N*`)으로 갈아탔다 — codex 처방과 같은 처방. 네 갈래(`Po` `!!`·`..` / `Mc` U+093E / 이모지 `So` / `Sm`)가 전부 거부로 바뀌고, 음성 테스트가 **codex 가 이름 붙인 네 카테고리 그대로** 파라미터에 붙었으며, `test_보이는_문자가_긍정_목록으로_정의된다` 가 **부정 목록으로의 회귀 자체**를 막는다. codex confidence **1.0** 은 이 회차 유일값이었고 실제로 여섯 중 유일한 완전 해소다 | — (닫힘) |
| **R-4 ⑴** | 전체 게이트 하한(`full_gate_floor_problems`)의 **유일한 본류 호출선**(`compare_parity.py:2274`)을 어떤 테스트도 검증하지 않는다 | **해소** (`48a791c`) | `ci:quality` | 게이트 14 가 **Y-7 변이 실측**으로 증명한 자리다 — 그 한 줄을 지워도 `test_parity_ci_gate.py` **25 passed** · 전체 스위트 **1191 passed**. `04ced00` 은 같은 처방을 `runtime_problem`·`provenance_problems` 두 helper 에만 줘 **R-4⑴ 은 빠져 있었다.** `48a791c` 가 **종류로 닫았다**(리더 판정 — 아래 §2 표 Q): 같은 `:2274` 변이가 이제 **2 failed** | — (닫힘) |
| **R-4 ⑵** | 테스트 파일이 일반 `pytest` 수집에만 의존해 삭제 시 조용히 사라진다 | **해소** (`04ced00`) | `ci:quality` | `ci.yml` 에 `uv run pytest tests/test_parity_ci_gate.py` 경로 명시 스텝 추가 | — (닫힘) |
| **R-4 ⑶** | 워크플로 추출 테스트가 배너 이후 반환 코드를 의도적으로 무시한다 | **미해소 — 권고** | `ci:quality` | `tests/test_parity_ci_gate.py:123-136`·`:210` 무변경. **사유가 docstring 에 명시돼 있어 은폐가 아니다**("가드 뒤 구간은 `parity/actual/` 이 없어 실패할 수 있으므로 종료 코드를 보지 않는다") | **Phase 4 착수 전** |
| **R-5** | `allow_empty` 가 **반드시 비어서는 안 되는** style 하한까지 면제한다 | **미해소 — 판정 필요** | `안 돎` | codex 는 **케이스 단위**로, Claude 는 **규칙 단위**로 판정해 갈렸다(§R.4.2). 제3 근거 실측: `style-too-long` 의 `reference.length_violations` = **1건(비어 있지 않다)** 인데 `style_length_floor` 단언에 `allow_empty: true` — **불필요한 면제**(Y-8). `comma_violations` 쪽 면제는 정당하다. 비교기 `allow_empty` 를 고정하는 테스트는 **0건**(Y-10 — 유일 적중은 스캐너의 동명이인) | **판정 필요 — 아래 §3 ④** (L345 승격의 독립 입력 축) |
| **R-6 ⑴** | `reached` 가 `strip()` 후 truthy 인지만 본다 (`reached: U+200B` 통과) | **미해소** | `ci:quality` | `compare_parity.py` 무변경(Y-6) · `:1318` 그대로(Y-3). codex medium 0.97 | **Phase 4 착수 전** |
| **R-6 ⑵** | `scoped=True`(`--only`)에서 저장소 상태 하한을 아예 보지 않는다 | **미해소 — 권고** | `안 돎` | 같음. **현재 CI 도달 0** — codex 자신이 "현재 CI 는 `--only` 를 쓰지 않으므로"라 적었다 | **Phase 4 착수 전** |
| **R-6 ⑶** | 4중 동기화 강등에 기계적 근거 요구가 없다 | **종결 — 코드 수정 아님** (`b13d502`) | `안 돎` | 원 cross 충돌①의 선택지 **ⓑ 그대로** — `.github/parity-full-gate.txt` 머리에 **위임을 명시**하고 "최종 방어선은 `.github/` diff 를 사람이 읽는 리뷰 게이트"라 적었다. **다만 판정 주체 기록이 비어 있다**(별건 Z-c, 아래 §5) | — (닫힘) |

**미해소 6갈래의 마감은 전부 Phase 4 착수 전이고, Phase 3(JDBC repository·인증 API)의 대상 코드를 직접
건드리는 것은 하나도 없다.** 착수 조건이 요구한 두 자리 중 **R-4⑴ 은 `48a791c` 로 닫혔고**, **R-5 만
사용자 판단 대기로 남는다.**

### §2 — 게이트 16 후속 수정 배치 현황 (`1cb7bdf..48a791c`)

> 표의 문자(A~W)는 `reviews/16_gate15-fixes_cross.md` §3 교차 대조표의 항목 식별자다.
> **실행 경로는 그 수정이 실제로 도는 자리**이고, 러너 자신의 CI 배선이 0인 사실은 지우지 않는다.

| 표 항목 | 커밋 | 실행 경로 | 근거·음성 대조 | 상태 |
|---|---|---|---|---|
| **A · B · C · D · T** (러너 결함군 + 권고 1) | `d0a5255` | `ci:quality` · `local:.claude/skills/kotlin-migration/scripts/run_gate.sh` | 기제가 **서로 다른 결함 넷**이 한 장치에 모였고, 그 셋을 자동으로 잡았을 계약 테스트가 **0건**이었다 — 각각 닫는다. **C(T-E, 차단②)**: 가드가 `$#` 만 보고 내용을 안 봐 `run_gate.sh ""` / `"   "` 가 **아무것도 실행하지 않고 exit 0** 이었다 → 내용이 공백뿐이면 **exit 2**(빈 호출과 같은 취급). **D(T-F)**: `cmd="$*"` 재조립 후 `bash -c` 재파싱으로 인용 경계·연속 공백이 소실됐다 → 계약을 **단일 문자열 인자 하나**로 좁히고(`$# -ne 1` → exit 2) argv 나열형 사용례를 삭제했다. **B(K16-1)**: 표기가 상태를 둘로 갈랐는데 **제3 상태**가 있었다 — 명령은 경유했는데 파이프가 **러너 밖에서** 구성된 경우(`run_gate.sh 'false' \| tail` → outer 0). 러너가 구조적으로 못 보므로 **고치지 않고 표기에 ⑶을 이름 붙였다**(머리 주석 + SKILL.md 규약 "러너 호출 자체를 파이프에 태우면 무효"). **A(T-G)**: `tests/test_run_gate.py` 신설 — 실물 스크립트를 subprocess 로 불러 ⑴~⑺ 을 고정하고, 못 잡는 ⑶ 은 `LIMIT` 이름으로 **못 잡는다고 적었다**. `ci.yml` quality 잡에 경로 명시 스텝 추가. **T(S-B)**: 머리 주석에 "인자에 비밀값을 넣지 마라(전문이 stdout 에 기록된다)" 전제 기재 — 마스킹 로직 없음(**은폐형 대신 전제**).<br>**음성 대조**(일회용 worktree, 65a7eb6 판 sha256 `97e29331…` 대조 후 실행): 같은 `test_run_gate.py` 를 `RUN_GATE_PATH` 로 **옛 판**에 돌리면 ⑴⑵⑶⑹ **4 failed**, **새 판 12 passed**. 상태 ⑶은 새 판도 outer bash 0·zsh 0 | **수정 완료** · **러너 자신의 CI 배선은 여전히 `0`**(스텝이 배선한 것은 러너의 **계약 테스트**다) · **⑶ 은 못 잡는 채로 이름만 붙었다** |
| **E · G** (하한 완전성) | `48a791c` | `ci:quality` | **E**: `PROMPT_CASE_FLOOR` 섹션 하한이 `()` 로 비면 요구가 없어 **어떤 축소도 통과**했다(양 관점 독립 실측 — Claude `postprocess` 29→1 / codex `system_prompts` 6→1, 각 exit 0). 빈 하한 → `SnapshotError`·**exit 2**(범위 선언형 — **빈 선언에서 통과하지 않는다**). 실물 `--check` 는 여전히 exit 0(diff 0 유지). **G**: 가드를 **그룹 키 입도 → 케이스 이름 입도**로 올려 섹션마다 `set(PROMPT_CASE_FLOOR[sec]) == {실물 케이스 이름}` 을 **양방향 동등**으로 대조.<br>**하한 비대칭을 설계로 명시했다** — `하한 ⊆ 실물` 은 **생성기**가(`SnapshotError`), `실물 ⊆ 하한` 은 **가드 테스트만** 강제한다. 음성 대조 3변이: ① 하한 한 섹션 비우기 → 가드 2건 빨강 + `--check` exit 2 / ② 케이스 1건 표적 삭제 → 가드 빨강 + exit 2 / ③ **하한에 없는 케이스 추가 → 가드 1건 빨강, `--check` exit 0**(설계 — 추가는 가드가 잡는다) | **수정 완료** · **심각도는 사용자 판단 대기**(§3 ⑤) |
| **Q** (본류 회귀 — T-D⑵) | `48a791c` | `ci:quality` | **리더 판정 「종류로 닫는다」의 집행.** 본류 호출부가 한 곳뿐인 게이트 helper **전부**에 `main()` 실행 회귀를 신설했다. **본류 회귀는 8자리가 아니라 9자리다** — `_MAINLINE_HELPERS` 완전성 테스트(비교기 모듈에서 `*_problem(s)`/`*_additions` 최상위 함수를 **자동 열거**해 표와 양방향 대조)가 **첫 실행에서 아홉째를 잡았다**: `structural_problems`(`main:2279`, 유일 호출부)가 cross §6.1 의 8자리 표에서 빠져 있었다. **자동 열거가 손열거의 불완전을 그 자리에서 증명한 것**이고, 이것이 「열거가 근거를 만드는 구조」를 구조로 바꾼 자리다.<br>**음성 대조 9/9** — 호출선 각 1개 제거 → 대응 본류 테스트 빨강(runtime 2 · provenance 1 · structural 2 · spec_shape 1 · case_floor 1 · full_gate 2 · stale_ledger 1 · ledger_write 1 · case_floor_additions 1 failed, 나머지 초록). **`:2274` 변이는 게이트 14 가 "지워도 25 passed / 1191 passed" 를 실측한 바로 그 자리이며 이제 2 failed 다.**<br>**부수 — 별건 Z-b 해소**: `_mainline_tree` 를 상태 인자화(`case_floor`/`full_gate_mark`/`declared_floor`/`actual_from_reference`/`ledger`)해 "전체 게이트 하한이 **항상 통과하도록** monkeypatch 돼 `:2274` 가 영구 미도달" 이던 구조를 걷었다.<br>**[게이트 18 정정, 2026-08-19 — 이 칸의 수치가 낡았다]** 게이트 18 R3 이 「원장 `:548` 이 9자리·9/9 인 채인데 코드는 `EXPECTED_MAINLINE_HELPERS = 10` 이다」를 지적했다(수정 필요 · 마감 「리더의 게이트 18 후속 원장 기재」 = 이 갱신). **본류 회귀는 `318069b` 에서 10자리가 됐다** — 게이트 17 ③(`nested` 면제 목록 = 은폐형)을 **AST 호출부 대조(탐지형)로 갈아타자 열째가 드러났다**: `reference_problems` 를 "중첩 전용"이라 면제해 뒀는데 **유일 호출부가 본류 함수 `compare_file`**(`compare_parity.py:2072`)이고 `runtime_problem` 과 같은 자리였다. 본류 회귀 `test_본류가_낡은_원장_기록을_막는다`("원장이 낡았다") 신설. **음성 대조도 9/9 → 10/10** (`318069b` — 호출선 제거 10종 전부 대응 본류 테스트 + 완전성 테스트 빨강, 양 관점 재현 — 게이트 18 cross §3.3·§7.2). **8→9 를 만든 것이 자동 열거였듯 9→10 을 만든 것은 면제 목록의 탐지형 전환이다** — 두 번 다 「열거가 근거를 만드는 구조」를 구조로 바꾼 자리에서 나왔다 | **수정 완료** · **R-4⑴ 함께 해소** · **[정정] 10자리 · 음성 대조 10/10**(`318069b`) |
| **I · P** (과대 서술) | `35e2d48` | `ci:kotlin` | **로직 변경 0.** 주석·선언 문구만 좁혔다. **I**: `FrameworkErrorContractTest.kt` kdoc 이 다섯 필드를 한데 묶어 "**서비스 층에서 정규화한 뒤** 판정"이라 적었는데 정본은 **2/5 가 원시**다(`easy-doc-v1.yaml:395` password · `:400` text). **값을 옮겨 적어 고치지 않았다** — 그 방식이 이 오류를 만든 방식이다. "서비스 층에서 판정한다"만 남기고 **측정 축의 정본은 `x-request-field-constraints` 의 `fields[].measured_on`** 이라고 지목했다(계약이 바뀌어도 이 kdoc 은 틀려지지 않는다). **P**: `MigrationStatementTracer.kt` 세 자리의 "모든 SQL"·"모두 이 자리를 지난다"·"전부 추적판으로" 선언이 실제 도달보다 넓었다 — 밖에 두 자리(`Statement.getConnection()`·`DatabaseMetaData.getConnection()` 이 돌려주는 원본 커넥션 / `prepareCall` 의 `CallableStatement`)가 남는다. 현재 Flyway·가드 경로는 둘 다 쓰지 않아 임계 구간 측정에 구멍은 없다. 검증: `ktlintCheck`·`detekt`·`test` exit 0(api 76 + infrastructure 63 = **139 tests / 0 failures**) | **수정 완료** |
| **N · W** (전사 금지 규약 · 강제자 표기) | `b93edc4` | `안 돎` | **N**: `526bfeb` 가 세운 「계약 값 전사 금지」 규약이 **그 규약이 다스리는 바로 그 세 행**(X-F11·X-F12·X-F13)에 도달하지 않았다 — 상한 값(8·255)과 `detail` 인라인 주석 전문을 그대로 옮겨 적고 있었다. **규칙 4 의 범위 선언형이 빈 채로 통과한 것**이며, 값이 틀려서가 아니라 **장치가 빈 선언이어서** 고쳤다: 경계를 「하한」·「상한」이라는 **역할 이름**으로 적고 값은 `fields[n].limit` 에서 읽게, 주석은 전문 대신 **위치(`:391`)** 로. **규약이 다스리는 범위도 명시**했다(세 행의 모든 열 + 커버리지 표 + §2 #1 행 ④ 칸). **넓히지 않았다** — X-F1·X-F2·X-F9·X-F10 등 **규약보다 먼저 쓰인 행은 여전히 값을 전사하며 그대로 뒀다**(함께 고치면 규약이 근거보다 넓어진다). 대신 **미해소 사실을 등재**해 전체가 준수 중이라고 읽히는 오독을 막았다. **W**: preflight §5 가 X-F11~13 을 "이 금지의 **강제자**"로 소개하면서 실행 상태를 적지 않았다 — 셋은 Markdown 2파일에만 있고 **실행 소스 0건**이며 Contract 게이트 자체가 `안 돎` 이다. 머리글을 "지금은 아무 데서도 걸리지 않는다 · 전부 계획이다"로 바꾸고 **실행 상태 열**을 신설했다(여섯 항목 전부 `계획 — 실행 소스 0건` + 각자의 마감). 원장 X3 행의 병기 형태를 본보기로 썼고 codex K16-3("Markdown 항목을 강제자라고 분류하지 마라")을 함께 인용했다 | **표기 축 수정 완료** · **강제자 축은 열린 채**(아래 행) |
| **H · J · W**(강제자 축) | — | `안 돎` | **F3 다섯 필드의 실행 가능한 강제자가 0이다.** `git grep -E 'X-F1[123]'` 전 저장소 → **Markdown 2파일뿐**(`00_contract-keeper_test-plan.md` · `03_kotlin-implementer_phase3-preflight.md`), Kotlin·Python 실행 소스 **0건**. 계획을 고치는 것으로는 닫히지 않는다 — **실제 F3 DTO 의 계약 테스트가 생겨야 닫힌다.** codex(K16-3, high 0.99)와 Claude(부분 해소 + 권고 R-10)가 **마감에는 합의**한다: codex "F3 다섯 필드의 실제 구현 테스트가 생기기 전에는 **Phase 3 게이트를 닫지 않는다**" | **열린 채** · 마감 **Phase 3 해당 DTO 구현 커밋**(양 레인 합의) · **착수가 아니라 종료를 막는다** · 심각도는 사용자 판단 대기(§3 ⑥) |

**게이트 16 cross §8.1 이 「Phase 3 착수를 막는 후보」로 올린 러너 결함군 4건(A·B·C·D)은 `d0a5255`
한 커밋으로 닫혔다** — cross 자신이 "4건은 한 커밋으로 닫힌다"고 적었고 그대로 됐다. 착수 차단 여부의
**판정 자체는 아래 §6 이 재료만 모으고 하지 않는다.**

**[게이트 17·18 갱신, 2026-08-19] 이 표의 항목들은 그 뒤 두 번 더 독립 리뷰를 받았다 — 판정이 두 겹으로 쌓였다.**
**⑴ 게이트 17 cross §9.1** 이 이 표의 **게이트 16 13항목**(A·B·C·D·E·F·G·I·N·P·Q·T·W)을 **해소 11 · 부분 2 ·
미해소 0** 으로 종합 판정했다. 다만 **C·G 두 행은 codex 단독 지적(X17-1·X17-3)의 사정권**에 있어 「해소」 유지
여부가 리더 판정 사항으로 남았고, **Q 행에는 그 장치 자신에 지적 4건**(②③④⑤)이 새로 붙었다.
**⑵ 게이트 18 cross §8.1** 이 그 다음 층 — 게이트 17 이 낸 **10항목**(①②③④⑤⑥⑦⑨⑬⑭)을 재판정해
**해소 3 · 부분 해소 5 · 충돌 1 · 미해소 0** 을 냈다. **1차(Claude 단독) 집계는 「해소 8 · 부분 2 · 미해소 0」이었고,
codex 관점이 세 자리를 「해소」에서 「부분」으로 옮기고 한 자리(⑭)를 충돌로 되열었다.**
**두 집계의 대상이 다르다** — ⑴은 게이트 16 항목, ⑵는 게이트 17 항목이다. 같은 표의 재판정이 아니므로
합산하지 않는다. 상세는 아래 「게이트 17·18 후속」 절.

### §3 — 사용자 판단 대기 6건 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

> ①②③ 은 「게이트 15 후속」 §3 에서 이월된 것이고 근거 전문은 그 표에 있다(여기서 요약하지 않는다 —
> 옮겨 적으면 갈린다). ④⑤⑥ 이 이번에 추가된 것이다.

| # | 쟁점 | 출처 | codex 쪽 | Claude 쪽 | 리더 권고 | 상태 |
|---|---|---|---|---|---|---|
| ① | **X2 의 심각도** — 게이트 우회 회귀 6종 | 게이트 15 | **high**(= 차단②) | **적정 — 지적 없음**(T-B) | **codex 판정 채택 권고** (근거 전문은 「게이트 15 후속」 §3 ①) | **사용자 확인 대기** |
| ② | **X13** — F3 계약의 `measured_on: raw` 2필드 | 게이트 15 | **high**(C15-7) | 원시를 전제로 수용(C-B) | **전반부 기각**(프롬프트 전제 오류) **· 후반부 유지** (「게이트 15 후속」 §3 ②) | **사용자 확인 대기** (§2 X-F12 기대값이 이 판정에 종속) |
| ③ | **X8·X9 의 심각도** — 같은 사실, 갈린 척도 | 게이트 15 | **high**(C15-3·C15-1) | **권고**(K-E·K-C) | **판정 유보 — 어느 쪽이든 마감은 같다**(Phase 3 첫 기동/배포) | **사용자 확인 대기** |
| **④** | **R-5 의 처분** — `allow_empty` 가 비지 않는 style 하한까지 면제한다 | **게이트 14 재실행분** | **high 0.99** — "유도 함수를 빈 목록으로 변이하고 산출물을 `sentences=[]`·`length_violations=[]`·`comma_violations=[]` 로 주자 **전체 단언이 `[]` 로 통과했다.** 즉 독립 입력 하한을 제거해도 self-reported sentences 기반 검사만 남아 **생산자의 전량 누락을 함께 0 으로 채점한다.**" | **정당화됐다**(1회차 §3.2 「B4-A 해소」) — "`check_contains_derived` 가 빈 요구를 기본 실패로 바꿨고, **style 하한 둘만 `allow_empty=True` 로 정당화됐다**" | **codex 쪽 처방(ⓐ) 채택 권고 — 단 L345 재판정 여부는 사용자 몫.** 갈림의 원인은 **입도**다: Claude 는 **규칙 단위**로, codex 는 **케이스 단위**로 판정했다. 제3 근거가 codex 쪽 전제를 실측으로 뒷받침한다(`style-too-long` 의 `length_violations` = 1건인데 면제가 붙어 있다 — **불필요한 면제**). **이 판정이 ⓐ 로 나면 L345(스타일 규칙 포팅) 승격의 독립 입력 축이 좁아진다** — `e90cfe4` 가 L345 를 `예` 로 올린 시점에 R-5 는 존재하지 않았다(별건 Z-a). **행을 되돌릴지가 사용자 판단이 필요한 부분**이고, 하한 자체를 고치는 것은 어느 쪽이든 필요하다 | **사용자 확인 대기** |
| **⑤** | **E·G 의 심각도** — floor 빈 선언 통과 + 미등재 미탐지 | **게이트 16** | **high 0.99** | **수정 필요** (마감 `app/**` 삭제 전) | **codex 판정 채택 권고.** 이 결함이 여는 창은 **큐레이션 45건의 영구 손실**이고 **되돌릴 수 없다**(CLAUDE.md — "재개발의 유일한 영구 손실 위험"). 되돌릴 수 없는 창에 걸린 장치의 심각도는 그 창을 기준으로 매기는 것이 이 하네스의 관례다. **수정은 이미 완료**(`48a791c`)이므로 이 판단은 심각도 라벨과 이력에만 영향을 준다. **1차 심각도를 교차 종합이 스스로 올리지 않았다는 사실도 함께 남긴다** | **사용자 확인 대기** |
| **⑥** | **H 의 심각도** — F3 다섯 필드 강제자 0 | **게이트 16** | **high 0.99**(K16-3) | **부분 해소 + 권고**(R-10) | **판정 유보 — 마감에는 양 레인이 합의했다**(Phase 3 해당 DTO 구현 커밋, cross §6.2). 쟁점은 심각도뿐이고, 심각도가 high 든 권고든 **이것이 막는 것은 Phase 3 착수가 아니라 Phase 3 종료**다 — 착수 판정을 바꾸지 않는다 | **사용자 확인 대기** |

**충돌 F 는 리더 판정으로 닫혔다 — 다만 사용자가 뒤집을 수 있다.** 쟁점은 "빈 선언이 통과하는가"(합의됨
— 통과한다)가 아니라 **"`PROMPT_CASE_FLOOR` 를 생성기 안 상수로 둔 배치 자체가 규칙 4 ⑶ 위반인가,
아니면 상류 부재로 정당화되는가"** 였다. **채택한 것은 Claude 처방**(하한은 생성기 안에 두고, 검사는
**생성기 밖 가드 테스트**가 케이스 이름 집합으로 양방향 대조 — 제2 정본 파일을 만들지 않는다).
**기각한 것은 codex 처방의 「생성기 밖 독립 정본 신설」 한 조각뿐**이고, codex 가 요구한 두 성질(빈 선언
거부 · 케이스 정체성 고정)은 **둘 다 집행됐다**(`48a791c`). 판정 근거: ⑴ 케이스 이름 6종을
`app/`·`tests/` 에서 grep 한 결과 **새 가드 테스트 1건 외 0건** — 재계산할 상류가 실제로 없다.
⑵ 상류가 없는 값을 위한 제2 정본은 **이 저장소가 반복해 겪은 이중 기재 드리프트**를 새로 여는 방향이다.
**codex 원문은 `reviews/16_gate15-fixes_cross.md` §5.1 에 전문 보존돼 있고 이 판정은 그것을 지우지 않는다.**

### §4 — 사실 기록 3건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **로컬 게이트 전건 통과** (HEAD `48a791c`) — `ruff check` **exit 0** · `ruff format --check` **exit 0** · `mypy . .claude` **exit 0(137 파일)** · `uv run pytest` 전체 **exit 0(1217 passed)** | HEAD `48a791c` · 각 명령 **단독 실행**(파이프 없음) | 기준 시점 없는 절대 수치는 다음 커밋에 거짓이 되므로 **HEAD 해시와 함께** 적는다. 직전 기록(`dec9229` 시점)은 mypy **136 파일** · pytest **1191 passed** 였다 — 늘어난 것은 `tests/test_run_gate.py` 와 이번 배치의 회귀 테스트들이다 |
| ② | **리더 전제 오류 1건** — 프롬프트가 codex 레인에 내려보낸 "이 배치가 넣은 새 CI 스텝 **4개**"는 틀렸다. 실제 신규는 **3개**(`04ced00` 2 + `4cba492` 1, 전부 `quality` 잡)이며 codex 가 정정한 값이 맞다 | cross §4 #7(커밋별 `ci.yml` 추가 `run:` 실측) · §4.1 | **게이트 15 별건 1과 같은 형태다** — 검증하지 않은 값이 프롬프트를 타고 하위 레인으로 내려갔다. 다만 **두 방어가 모두 작동했다**: codex 레인이 "이 값은 리더 지시문에서 왔고 내가 검증하지 않고 전달했다"고 **전달 경로를 기록**했고, codex 는 그 전제를 받지 않고 **스스로 세어 정정**했다. 이 형태가 두 번 났으므로 이월 항목 「focus text 계약 전사 구조」(「게이트 15 후속」 §5)의 근거가 하나 더 쌓인다 |
| ③ | **게이트 17(`17_gate16-fixes`, 대상 `1cb7bdf..48a791c`) 1단계 진행 중** | 별 레인 (`reviews/17_*` 미생성) | 이번 배치(5 커밋)는 **아직 독립 리뷰를 받지 않았다.** 게이트 16 이 지적한 것을 고친 커밋들이므로 **"고쳤다고 보고한 직후 그 수정의 결함이 잡히는" 이력이 이 저장소에 반복**돼 있다(Phase 0 종료 판정 절 — 세 번). **게이트 17 종결 전에는 이 배치를 근거로 Phase 3 착수를 판정하지 않는다** |

### §5 — 이월 항목 추가 (게이트 14 §R.9 별건 3건 + 게이트 16 §8.3)

| 항목 | 출처 | 상태 | 마감 |
|---|---|---|---|
| **Z-a — 미해소 지적이 이미 `예` 로 올라간 행에 걸린다.** `e90cfe4` 가 L345(스타일 규칙)를 `예` 로 승격했는데 R-5 가 겨눈 것이 **바로 그 판정의 독립 입력 축**(style 하한)이다. 승격 시점에 R-5 는 존재하지 않았다 | 게이트 14 cross §R.9 (**미교차** — 어느 리뷰에도 없는 관찰) | **열림** — R-5 판정(§3 ④)이 ⓐ 로 나면 승격 근거가 좁아진다 | **다음 게이트** (R-5 판정과 동시) |
| **Z-b — 본류 배선 테스트가 자기 눈을 가리는 구조.** `04ced00` 의 `_mainline_tree` 가 `FULL_GATE_PATH`·`DECLARED_FLOOR_PATH` 를 **항상 통과하는 값**으로 monkeypatch 해, 본류 배선 테스트를 아무리 늘려도 **전체 게이트 하한 호출선만은 영구히 보이지 않았다** | 게이트 14 cross §R.9 (미교차) | **해소** (`48a791c`) — `_mainline_tree` 를 상태 인자화해 걷었다. 격리(실물 오염 방지)는 유지하면서 하한 호출선을 재는 변형이 생겼고, `:2274` 변이가 이제 **2 failed** | — (닫힘) |
| **Z-c — 충돌 종결의 판정 주체 기록이 비어 있다.** 원 cross 가 리더 판단으로 올린 충돌 ①·③ 이 모두 **구현 커밋(`b13d502`)의 산출물 문서**로 닫혔다. 내용은 정합하나(각각 ⓑ · C-5 채택) **리더가 판정했다는 기록이 어느 문서에도 없다** — 「리더 판단 요청」이 응답 없이 구현으로 흡수된 형태 | 게이트 14 cross §R.9 (미교차) | **열림** | **다음 게이트** — R-10("미해결 항목이 조용히 사라진다")과 같은 형태인지 판정하고, 충돌 종결에 **판정 주체·일자**를 남기는 규약을 검토 |
| **원장 X3 행의 음성 대조 증거가 인용형만 덮는다** — 원장은 "러너 경유 시 exit 4 전파"라 적는데, 실측상 그것은 **인용형에서만** 참이고 **비인용 외부 파이프(상태 ⑶)에서는 outer 0** 이다 | 게이트 16 cross §9.2-1 (**미교차**) | **부분 완화** — `d0a5255` 가 ⑶ 에 이름을 붙이고 argv 나열형 사용례를 없앴으나, 원장 X3 행의 증거 문장 자체는 그대로다 | **다음 게이트** |
| **`ci.yml` 경로 명시가 열거로 유지되는 구조** | 게이트 15 cross §6.2-2 | **열림** — `d0a5255` 가 다섯 번째(`tests/test_run_gate.py`)를 더했다. **열거가 근거를 만드는 구조 자체는 그대로**이며, 이번 배치가 그 구조를 한 번 더 재현했다 | **다음 게이트** |
| **게이트 16 §8.3 이월분** — **R(C-D)** Phase 배치 이중 등재(Phase 4 착수 전) · **S(P-C)** 스냅샷 2차 소비자(`app/**` 삭제 전) · **M(C-B)** `type:"missing"` 계약 근거 없음(Phase 3 계약 테스트) · **O·U·V**(K-B·K-D·K-E 권고 3, Phase 3 내 — **교차 검증 미수령**, codex 축 ④a·③a 미판정) | 게이트 16 cross §8.2·§8.3 | **열림** | 각 행 명시 마감 |

### §6 — Phase 3 착수 판정 준비 (**아직 판정하지 않는다**)

> **이 절은 판정에 필요한 사실만 모은다.** 착수 여부는 게이트 17 이 종결된 뒤 리더가 판정하고, 그때
> 별도의 판정 절을 세운다. **여기 있는 「충족」 표기를 착수 판정으로 읽지 마라** — 조건 하나하나의
> 상태이지 종합 판정이 아니다.
>
> **[2026-08-19 갱신] 게이트 17·18 이 둘 다 3단계 완주로 종결됐다**(`36b5ed4` · `bbe49a1`). 위 문장이
> 예고한 시점이 왔으나 **이 갱신은 여전히 판정하지 않는다** — 게이트 18 이 낸 잔여 8건의 수정 배치가
> 진행 중이고, **그 배치가 곧 게이트 19 의 대상**이 된다. 판정 재료는 아래 표와 그 뒤 「열린 것 전부」
> 표에 모은다.

| 착수 조건 | 상태 | 실행 경로 | 근거 |
|---|---|---|---|
| **① 게이트 15 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/15_phase3-preflight_cross.md` | 3단계 완주(`c932b4f`) — codex high 8 / Claude 20건 / cross 23행. 후속 처리 현황은 「게이트 15 후속」 절 |
| **② 게이트 14 완전 재실행 + 교차 종합** | **충족** | `1회성:docs/migration/_workspace/reviews/14_floor-hardening_cross.md` | 재실행 완료(`c932b4f` — 종료 코드 0 · findings 6건 = high 5·medium 1 · 로그 보존본), **교차 종합 완료**(`4dabc0d` — 합의 2·충돌 2·codex 단독 2, 전제 6건 대조 무효화 0). §R.10 이 충족을 명시 판정 |
| **③ Phase 3 착수 차단 6건 처리** (게이트 15 cross §5.2 (가)) | **부분** — 6건 중 **3건 해소 · 2건 수정 완료(잔여 병기) · 1건 계획만** | `ci:kotlin` · `ci:quality` · `안 돎` | **해소 3**: X14(원장 정면 모순) · X11(동시 기동 탐지력) · ~~X6~~ → **[정정] X6 은 부분 해소**(§2 표 H·J·W). **수정 완료 2**: X3(러너 신설 — **규약 강제자 0 은 열린 채**, 게이트 16 이 그 러너에서 결함 4건을 새로 잡아 `d0a5255` 로 닫음) · X2(본류 회귀 + CI 경로 명시 — 심각도는 §3 ①). **계획만 1**: X5(test-plan 세 행 신설, 실행 경로 `안 돎` — 실제 실행은 **Phase 3 첫 계약 테스트**). 상세는 「게이트 15 후속」 §2.<br>**[게이트 18 갱신, 2026-08-19 — 사실만]** 「수정 완료 2」의 **두 자리 모두 세 회차 연속 새 지적을 받았다**(게이트 18 cross §8.3). **X3(러너)**: 게이트 16 A·B·C·D → 게이트 17 ① → 게이트 18 X3·X4·X5 + T2·T3·T4·T5 + R18-1·R18-2. **X2(본류 회귀·완전성 장치)**: 표가 9→**10자리**로 넓어지고 음성 대조가 두 겹(호출선 + 완전성)이 됐으나, **그 완전성 장치 자신에 X1·X2a·T1 이 붙었고 원장 값이 낡았다(R3 — 위 §2 Q 행에서 정정)**. 「수정 완료」의 대상 장치가 매 회차 새 빈자리를 낸다는 **사실**만 적는다 — 상태 문구 재판정은 하지 않는다 |
| **④ 게이트 16 종결** | **부분** — 3단계 완주 + 후속 배치 완료, **미해소 잔여 있음** | `1회성:docs/migration/_workspace/reviews/16_gate15-fixes_cross.md` | 3단계 완주(`cc7f53c`). 착수 차단 후보였던 러너 결함군 4건(A·B·C·D)은 `d0a5255` 로 닫힘. **남은 것**: H·I·J·W 강제자 축(마감 **Phase 3 DTO 구현 커밋** — 착수가 아니라 **종료**를 막는다) · N 규약 밖 선행 행(미해소 등재) · §8.3 이월 6건 |
| **⑤ 게이트 17 종결** (`17_gate16-fixes`, 대상 `1cb7bdf..48a791c`) | **종결** (`36b5ed4`) | `1회성:docs/migration/_workspace/reviews/17_gate16-fixes_cross.md` | **3단계 완주** — codex high 3 / Claude 10건(차단② 1) / cross 16행. **직전 기재의 문면 두 개가 사실과 갈렸던 것을 여기서 정리한다**(게이트 17 cross §9.3 Y-1 · 게이트 18 cross §8.3): ⑴ "`reviews/17_*` 미생성"은 **거짓이 됐다** — 3종이 `36b5ed4` 에 실재한다. ⑵ "이번 배치 5 커밋은 아직 독립 리뷰를 받지 않았다"도 **거짓이 됐다** — 그 5 커밋(`1cb7bdf..48a791c`)이 게이트 17 의 대상이었고, **그 지적을 닫은 배치(`107c8a5`·`318069b`)가 다시 게이트 18 의 대상이 되어 독립 리뷰를 받았다**. 갈림은 **원장이 배치를 따라오지 못한 것**이지 판정 오류가 아니다(게이트 15 X14 와 같은 형태). **종결이 「잔여 0」을 뜻하지 않는다** — 게이트 17 10항목의 재판정은 게이트 18 cross §8.1 로 **해소 3 · 부분 해소 5 · 충돌 1 · 미해소 0** 이고, 상세는 「게이트 17·18 후속」 §1 |
| **⑧ 게이트 18 종결** (`18_gate17-fixes`, 대상 `36b5ed4..318069b`) | **종결** (`bbe49a1`) — 산출물 3종 실재, **잔여 8건 수정 배치 진행 중** | `1회성:docs/migration/_workspace/reviews/18_gate17-fixes_cross.md` | **3단계 완주** — codex `needs-attention` high 4(X18-1~4) / Claude 차단 **0** · 수정 필요 5 · 권고 6 / cross 19행(합의 4 · 충돌 4 · codex 단독 3 · Claude 단독 8). 전제 오류로 무효화된 codex 지적 **0건**. **이 행은 게이트 17 cross·게이트 18 cross 가 둘 다 「원장에 없는 칸」으로 지목한 자리다**(게이트 18 cross §8.3 「신설이 필요한 칸」 · §10.1 Y-4) — 이 갱신이 신설한다. **잔여 8건 중 4건은 이미 닫혔다** — X3(+R18-1·R18-2)·X4·T2 가 `e2282b3`(**새 기제 0** — 표기 정직화·손잡이 제거·기존 방법 교체만), R3 이 이 갱신. **X1·X2a·T1 은 parity 레인에서 진행 중**이고 **X5 는 열려 있다**. **잔여는 전부 하네스 자기 검사 장치**이고 Phase 3 대상 코드(JDBC·인증 API·계약 테스트) 지적은 **0건**이다 — 단 그 0 은 **"이번 회차가 Phase 3 코드를 보지 않았다"가 정확한 진술**이다(이 배치의 Kotlin·계약·프론트 diff 0줄). 상세는 「게이트 17·18 후속」 §2 |
| **⑥ Phase 2 종료 판정의 잔여** | **아니오 1행** | `ci:kotlin` | L348(내보내기) — F-3·F-4(export 파일명 축), 마감 **Phase 4 착수 전**. **게이트 14 R-1 이 같은 자리를 겨눈다**(§1 표 — "L348 재판정의 직접 근거"). Phase 3 착수를 막지는 않는다 |
| **⑦ 게이트 14 미해소 6갈래** | **열림 — 마감이 Phase 4** | `ci:quality` · `안 돎` | R-1 · R-2⑴⑵ · R-4⑶ · R-5 · R-6⑴⑵. **어느 것도 Phase 3(JDBC repository·인증 API)의 대상 코드를 직접 건드리지 않는다**(cross §R.10). 착수 조건이 요구했던 두 자리 중 R-4⑴ 은 `48a791c` 로 닫혔고 **R-5 만 사용자 판단 대기**(§3 ④) |
| **⑨ 게이트 19 종결** (`19_gate18-fixes`, 대상 `bbe49a1..5d58832`) | **종결** (`445c5cf`) | `1회성:docs/migration/_workspace/reviews/19_gate18-fixes_cross.md` | **3단계 완주** — codex `needs-attention` · "No-ship." high 4 · medium 2 / Claude 차단② 1(R19-1) · 수정 6 · 권고 4 / cross 20행. **전제 오류 0** · **게이트 18 종결 합성 미해소 0**(갈림 3 중 X1·T2 는 이름표, **X3 은 실질** — codex 단독 ⑩). 상세는 아래 「게이트 19 후속」 절 |
| **⑩ 게이트 19 ①·⑥ 수정 커밋** | **완료** (`e91ecdd` + `e600861` + `e7f9bdb`, 3 커밋) — **리뷰 미수령** | `ci:quality` | 개수 → 부분집합 → **내용 정확 일치**. stop-time 게이트가 같은 우회를 **세 번** 잡아 커밋이 셋이 됐다. **독립 리뷰를 받지 않았고**, 별도 게이트를 열지 않는 대신 **Phase 3 첫 리뷰 게이트 범위에 세 커밋을 포함**한다(리더 결정). 상세는 「게이트 19 후속」 §1 |

**판정하지 않은 이유를 적어 둔다.** ⑤가 진행 중이고, 이 원장이 반영하는 배치(`1cb7bdf..48a791c`)가
**바로 그 게이트의 대상**이다. 자기 배치를 자기 근거로 삼아 착수를 판정하면 **저작자와 심판자가 같아진다**
— 이 하네스가 리뷰 레인을 분리한 이유가 그것이다.

**[2026-08-19 갱신] 같은 이유가 한 층 위로 옮겨 갔을 뿐 그대로 성립한다.** ⑤·⑧ 이 종결됐으나
게이트 18 이 낸 **잔여 8건의 수정 배치가 진행 중**이고 그 배치가 **게이트 19 의 대상**이 된다.
자기 배치를 자기 근거로 삼지 않는다는 규율은 회차가 바뀌어도 같다. **이 갱신도 판정하지 않는다.**

**[2026-08-19 갱신 · 이 절을 닫는다] 게이트 19 가 3단계 완주로 종결됐다**(`445c5cf`). 이 표가 모으던
사실은 전건 모였고, **판정은 아래 「Phase 3 착수 판정」 절이 한다** — 이 절은 더 갱신하지 않는다.
다만 이번에도 **자기 배치를 자기 근거로 삼지 않는다**는 규율이 한 자리에 남는다: 게이트 19 조치
커밋 **`e91ecdd`·`e600861`·`e7f9bdb`** 는 **리뷰 미수령**이라 그 자체를 착수 근거로 쓰지 않고
**다음 게이트 대상으로 넘긴다**(⑩ 행 · 「게이트 19 후속」 §1).

#### §6-a — 판정에 필요한 「열린 것 전부」 (한 표 · 2026-08-19)

> **판정이 아니다.** 착수 판정을 하려면 무엇이 열려 있는지가 한자리에 있어야 하므로 **모으기만** 한다.
> 각 행의 근거·전문은 표시된 절에 있고 **여기로 옮겨 적지 않는다**(전사하면 갈린다).

| 열린 것 | 무엇이 남았는가 | 마감 | 정본 |
|---|---|---|---|
| **L348 (내보내기)** | Phase 2 종료 판정의 유일한 `아니오` 1행 — export 파일명 축(F-3·F-4) | **Phase 4 착수 전** | 「Phase 2 종료 판정」 절 · 「게이트 16 후속」 §1 R-1 |
| **게이트 14 미해소 6갈래** | R-1 · R-2⑴⑵ · R-4⑶ · R-6⑴⑵ (+ **R-5 는 사용자 판단 대기**) | **Phase 4 착수 전** (R-5 는 판정과 동시) | 「게이트 16 후속」 §1 |
| **X6 강제자 축** (H·J·W) | F3 다섯 필드의 **실행 가능한 강제자 0** — 실행 소스 0건, 계획만 있다 | **Phase 3 해당 DTO 구현 커밋** — **착수가 아니라 종료를 막는다** | 「게이트 16 후속」 §2 표 H·J·W |
| **X8·X9·X10·X12 (Flyway)** | 빈 `alembic_version` 승인 · 자원 교착 · 보호 범위 과대 선언 · 관측성 | **Phase 3 첫 기동/배포** | 「게이트 15 후속」 §1 첫 행 |
| **X4·X4b (모듈 경계)** | 검사 대상 2모듈 하드코딩 · 판정 우회 3갈래 | **Phase 3 모듈 추가 시** | 「게이트 15 후속」 §1 둘째 행 |
| **게이트 18 잔여 8건** | **해소 4** — X3(+R18-1·R18-2)·X4·T2 (`e2282b3`) · R3(직전 갱신) / **X1·X2a·T1** — `5d58832` 로 수정, 게이트 19 판정 **부분 해소**(리더 결정 ⑵) / **열림 1** — X5. **전부 하네스 자기 검사 장치**, 제품 코드 0 | **X5 는 러너 실사용 전**(리더 결정 ⑴) | 「게이트 17·18 후속」 §2 · 「게이트 19 후속」 §2 |
| **게이트 19 잔여** | **해소 2** — ①·⑥ (`e91ecdd` + `e600861` + `e7f9bdb`, **리뷰 미수령**) / **한계 등재 3** — ②·③·⑧ (리더 결정 ⑶ — 수정하지 않는다) / **동결 대상** — ⑦⑨⑩⑪⑫⑬⑭⑯ + 게이트 18 이월(리더 결정 ⑴) / **판정 필요 1** — ⑤(§3 ⑪). **전부 하네스 자기 검사 장치**, 제품 코드 **0** | **러너 실사용(CI 배선) 전** · ①⑥ 리뷰는 **Phase 3 첫 게이트** | 「게이트 19 후속」 §1·§2 |
| **사용자 판단 대기 11건** | 게이트 15·14·16 이월 6 + 게이트 18 충돌 4 + **게이트 19 충돌 1**(⑤). **전부 심각도·처방·절차 라벨이고 차단 판정은 없다** — 어느 쪽으로 나든 마감이 바뀌지 않는 것이 다수다 | 각 행 명시 | 「게이트 17·18 후속」 §3 · 「게이트 19 후속」 §3 |

**이 표가 말하지 않는 것.** ⑴ **닫힌 것**은 싣지 않았다(닫힌 것의 근거는 위 표들이 들고 있다).
⑵ **우선순위·차단 여부**를 매기지 않았다 — 그것이 판정이다. ⑶ 「사용자 판단 대기」는 **열려 있으나
차단이 아니다**(각 행의 리더 권고에 그렇게 적혀 있다). **이 셋을 섞어 읽으면 이 표가 판정으로 보인다.**

---

## 게이트 17·18 후속 — 두 회차 종결과 잔여 (2026-08-19, 리더)

정본은 두 파일이다 — `reviews/17_gate16-fixes_cross.md`(`36b5ed4`)와 `reviews/18_gate17-fixes_cross.md`(`bbe49a1`).
이 절은 그 둘의 판정과 후속 수정 배치(`36b5ed4..318069b` 2 커밋 + 게이트 18 조치 `e2282b3`)를 원장 형식으로
옮긴 것이며, **수치·판정에는 러너 run id 또는 커밋 해시를 함께 적는다**(수치 인용 규약 — 기준 시점 없는
절대 수치는 다음 커밋에 거짓이 된다).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다. 「게이트 15·16 후속」과 마찬가지로 이 절의
> 표들은 종료 조건 표가 아니라 **처리 현황 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의 대상
> 4표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다. **검사가 닿지 않는 자리에서 어휘가 갈리면 그 갈림이
> 다음 표로 번진다.**

### §1 — 게이트 17 조치 배치 현황 (`36b5ed4..318069b`)

> 표의 번호(①~⑭)는 `reviews/17_gate16-fixes_cross.md` §3 교차 대조표의 항목 번호다.
> **「해소」 칸은 게이트 18 이 재판정한 값**(cross §8.1)이고, 게이트 17 1차 판정이 아니다.

| 게이트 17 | 커밋 | 실행 경로 | 근거·음성 대조 | 게이트 18 재판정 |
|---|---|---|---|---|
| **① 러너 zero-work → exit 0** (codex X17-1, high) | `107c8a5` | `local:.claude/skills/kotlin-migration/scripts/run_gate.sh` · `ci:quality` | 인자가 비지 않았는데 **자식 bash 가 해석한 뒤 실행할 명령이 0건**이면 exit 0 이었다(`'$GATE_CMD'` 미설정 · 주석 전용 · 백슬래시-개행, 셋 다 rc 0). 원문 공백 검사는 **자식이 해석하기 전만** 봤다. 닫음: 자식을 `-o nounset` 으로 돌리고(미설정 참조 → 비-0), 나머지 둘은 손 파싱 대신 **bash 자신에게 묻는다** — `-o functrace` + DEBUG trap 으로 첫 명령이 실행 단계에 들어갈 때 마커에 1회 기록, rc 0 인데 마커가 비면 exit 2. **음성 대조**: 옛 판(`d0a5255`, sha256 `23d14464…` 대조)에 새 테스트 → **정확히 3 failed**(`comment_only`·`backslash_newline`·`unset_variable`), 옛 판 직접 세 입력 전부 rc 0, 새 판 **23 passed** | **부분 해소** — 세 입력은 닫혔으나 **잔여가 선언보다 넓다**(X3+R18-1+R18-2). 그 잔여는 `e2282b3` 이 **두 종류로 재기술**해 닫았다(§2) |
| **⑨ `SKILL.md:238` 이중 기재** (충돌 — 리더 판정) | `107c8a5` | `안 돎` | **리더 판정으로 Claude 처방 채택.** 규칙 5 문장이 "여기 옮겨 적지 않는다"면서 **계약값·한계 ⑶ 전문을 옮겨 적고** 있었다 — 러너 머리 주석을 **지목만** 하는 형태로 축약하고 값을 삭제했다. codex 는 같은 자리에 "근거와 일치 — 추가 지적 없음"을 냈고 **그 판정문을 지우지 않는다**(cross §4) | **해소 — 양쪽 확인.** 부수 손실(사용 지점의 파이프 함정·비밀값 경고 소실)은 **충돌 S2** 로 §3 ⑩ |
| **⑬ LIMIT 테스트 셸 하나** (T17-6) | `107c8a5` | `ci:quality` | `parametrize` 를 `bash` 하나에서 **`bash`·`sh`·`zsh`(없으면 skip)** 로 넓혔다 — 원 사고 셸이 `zsh` 였는데 커버가 없었다 | **부분 해소(유지)** — 러너를 **부르는** 셸은 여전히 bash 하나다(T5, §2 (나)) |
| **⑭ `RUN_GATE_PATH` 기본값 미단언** (T17-7) | `107c8a5` | `ci:quality` | 손잡이가 검사 대상을 바꾸는데 **기본값 사용을 단언하는 테스트가 0건**이었다 → 미설정 시 기본 경로가 저장소 **추적 실물**(`git ls-files`)이고 sha256 이 추적 경로의 바이트와 같음을 단언하는 테스트 신설 | **충돌 → 후속 배치에서 소멸.** codex X18-4 가 "그 테스트 자신이 손잡이를 지워 나머지 22건이 다른 파일을 재도 초록"임을 보였고, `e2282b3` 이 **손잡이 자체를 제거**해 이 테스트가 없어졌다(§2 X4) |
| **⑤ 완전성 검사가 빈 표에서 통과** (차단②, T17-4) | `318069b` | `ci:quality` | 같은 커밋(`48a791c`)이 **생성기**에는 "빈 선언에서 통과하지 않는다"를 세우면서 **자기 새 표**(`_MAINLINE_HELPERS` 완전성 테스트)에는 적용하지 않았다 → 표 행 수를 `EXPECTED_MAINLINE_HELPERS` 로 **정확 일치** 고정(`test_harness_scope_reach.py` `EXPECTED_ROW_COUNT` 전례). **음성 대조**: 빈 표 `{}` → 1 failed | **부분 해소** — 표 크기는 두 관점으로 닫혔으나 **같은 종류가 파생 상수에 남았다**(X18-1, §2) |
| **③ `nested` 면제 목록(은폐형)** (T17-5 = X17-2 b) | `318069b` | `ci:quality` | **은폐형 → 탐지형 전환.** 면제 목록을 없애고 **AST 호출부 대조**로 갈아탔다(문자열 grep 이 아니라 `ast.Call` 이라 docstring 언급을 세지 않는다). **탐지로 바꾸자 열째가 드러났다** — `reference_problems` 를 "중첩 전용"이라 면제했는데 **유일 호출부가 본류 함수 `compare_file`**(`compare_parity.py:2072`)이었다. 표 **10자리** + 본류 회귀 신설. **음성 대조**: 행 제거 + 상수 9(면제 경로 재현) → 1 failed | **해소** — 단 탐지형의 **도달에 외부 모듈 축이 빈다**(X18-2/X2a, §2) |
| **② 테스트 이름 재사용 승인** (codex X17-2 a) | `318069b` | `ci:quality` | 완전성 검사가 `test_name in module_tests` **포함 여부만** 봐 **기존 테스트 이름 재사용**을 승인했다 → 이름 **유일성** + **결속**(테스트 소스에 helper 이름 존재) 단언 추가. **음성 대조**: 이름 재사용 → 1 failed | **부분 해소** — 유일성은 닫혔고 **결속이 문자열 grep** 이라 주석 한 줄로 충족된다(T1, §2) |
| **④ 표 판정 문구 미대조** (T17-8 = X17-2 c) | `318069b` | `ci:quality` | 표의 판정 문구를 bogus 로 바꿔도 통과했다 → 문구가 **테스트 소스의 `"<문구>" in output` 단언에 실제로 있고 비교기 소스에도 있어야** 한다. f-string 문구 둘(runtime·ledger_write)은 같은 분기의 리터럴로 재결속. **음성 대조**: bogus 문구 → 1 failed · **진짜지만 갈린 문구** → 1 failed | **부분 해소(유지)** — bogus·갈린 문구는 잡고, **주석으로 충족되는 자리**가 남았다(T1) |
| **⑥ 실물 케이스 이름 중복** (codex X17-3, high) | `318069b` | `ci:quality` | 하한 동등 검사가 **집합**으로 접어 중복을 못 봤다(개수 위조 통과) → 생성기는 목록으로 보고 중복 시 `SnapshotError`, 가드도 실물을 목록으로 본다. 회귀 3건 추가. **음성 대조**: 생성기 중복 검사 제거 → 1 failed · 가드 중복 단언 제거 → 1 failed · 실물 `--check` **exit 0 유지**(오탐 0) | **해소 — 단일 관점**(codex 무응답, cross §6.1) |
| **⑦ 키 누락/빈 튜플 동일 메시지** (P17-3) | `318069b` | `ci:quality` | 진단이 같아 **엉뚱한 자리를 고치게 된다** → 하한 키 누락과 빈 튜플의 메시지 갈래 분리. **음성 대조**: 갈래 제거 → 1 failed | **해소 — 단일 관점**(codex 무응답, cross §6.1) |

**두 커밋의 음성 대조 총계**: `107c8a5` **3/3**(옛 판 실패 3건 이름 일치 — 게이트 18 에서 **양 관점 재현**) ·
`318069b` **18/18**(전부 일회용 worktree · `git checkout --` + 적용본 sha256 대조 · worktree 제거 완료).

**이 배치가 손대지 않은 게이트 17 항목 4건** — ⑩(`Connection.unwrap` 세 번째 자리, 마감 **Phase 3 내**) ·
⑪(전사 금지 규약 강제자 0, 마감 **Phase 3 첫 계약 테스트**) · ⑫(preflight 실행 상태 열 수기, 마감
**Phase 3 DTO 구현 커밋**) · ⑮(shellcheck 도달 0, **등재만**). 넷 다 **이 배치의 대상이 아니었다** —
게이트 18 에서도 무변경이고 ⑮ 는 R10 으로 그대로 이월됐다.

### §2 — 게이트 18 잔여 8건 (cross §8.2 (가) 그대로 · 수정 배치 진행 중)

> **마감 문면은 「Phase 3 착수 이전」이지만 8건 전부 하네스 자기 검사 장치다** — 제품 코드(JDBC·인증
> API·계약 테스트) 지적은 **0건**이고, 그 0 은 이 배치의 Kotlin·계약·프론트 diff 가 **0줄**이어서 생긴
> 것이다(cross §9 — "「Phase 3 코드가 깨끗하다」가 아니라 「이번 회차가 Phase 3 코드를 보지 않았다」가
> 정확한 진술"). **리더 제약(수정 배치에 내린 것): 새 기제 금지 — 정확 일치·표기 정직화·기존 방법 교체만.**

| 순위 | 항목 | 심각도 (Claude / codex) | 마감 (리뷰 문면) | 처리 | 실행 경로 |
|---|---|---|---|---|---|
| 1 | **X1** 빈 `_MAINLINE_PHRASES` → 완전성·대조군 **공허 통과** (`test_parity_ci_gate.py:624-632`, 소비처 `:720`·`:740` 이 전부) | **충돌** — 지적 없음 / **high · no-ship** → **판정 필요** | Phase 3 착수 전(§6 X2 첫 실사용) | **진행 중** (parity 레인) | `ci:quality` |
| 2 | **X2a** 외부 모듈·동적 임포트 helper 가 완전성 게이트에 **안 보인다**(발견이 `member.__module__ == comparer.__name__` 로 제한) | — / **high · no-ship** | Phase 3 착수 전 | **진행 중** (parity 레인) | `ci:quality` |
| 3 | **X3 + R18-1 + R18-2** 마커 기제의 **선언이 도달보다 넓다**(잔여 최소 9종) | **수정 필요 ×2** / **high · no-ship** | 러너를 게이트 근거로 처음 쓰기 전 | **해소** (`e2282b3`) | `local:.claude/skills/kotlin-migration/scripts/run_gate.sh` · `ci:quality` |
| 4 | **X4** 기본 대상 테스트가 **활성 `RUN_GATE_PATH` 를 가린다**(그 테스트만 스스로 손잡이를 지운다) | **충돌** — (⑭ 해소 근거) / **high · no-ship** → **판정 필요** | 러너 실사용 전 | **해소** (`e2282b3` — 리더 판정: **손잡이 제거**) | `ci:quality` |
| 5 | **T1** ④ 문구 결속이 **문자열 grep** 이라 주석 한 줄로 충족된다 — 같은 커밋이 ③에서 버린 방법 | **수정 필요** / — | 게이트 17 ④ 마감 승계 | **진행 중** (parity 레인) | `ci:quality` |
| 6 | **T2** CI 배선 탐지기가 `ci.yml` **세 스텝 형식 중 하나만** 본다(`startswith("run:")`) | **수정 필요** / — | 러너를 게이트 근거로 처음 쓰기 전 | **해소** (`e2282b3`) | `ci:quality` |
| 7 | **R3** 원장 `:548` 「9자리·음성 대조 9/9」 ↔ 코드 `EXPECTED_MAINLINE_HELPERS = 10` | **수정 필요** / — | **리더의 게이트 18 후속 원장 기재** | **해소 — 이 갱신** (위 「게이트 16 후속」 §2 Q 행 정정) | `ci:quality` |
| 8 | **X5** `BASH_ENV` 가 preamble 보다 먼저 실행되며 **마커 경로가 보인다** — 쓰기 가능 FS 에서 선점·무력화 가능 | — / (codex 심각도 미부여) → **판정 필요** | 러너 실사용 전 | **열림** — `e2282b3` 이 머리 주석 (나) 종류에 **이름을 붙였을 뿐** 막지 않았다(러너는 협조하는 호출자를 위한 장치다) | `local:…/run_gate.sh` |

**게이트 18 조치 커밋 `e2282b3` — 새 기제 0.** ⑴ **X3+R18-1+R18-2**: 마커가 재는 것은 "명령이 실행 단계에
진입했다"이지 "작업이 있었다"가 아니라는 사실에 맞춰 잔여 선언을 **1종 열거에서 두 종류로** 다시 적었다
(규칙 4 — 종류로 댄다): **(가)** 진입하지만 외부 작업 0(`$()`·백틱·`eval ''`·`:`·`if false`·`X=1`·
`bash -c ""`·`'$V'`(V="")·here-doc→`:`) / **(나)** 호출자가 계약을 **능동적으로 무력화**(`trap - DEBUG`·
`set +u`·`BASH_ENV` 선점 — X5·Y-1 흔적 언급). R18-1 의 "서브셸·명령 치환 (실측)" 주장은 **실측대로
정정**했다(명령 치환은 functrace 유무로 갈리지 않는다 — 부모 단순 명령이 발화한다. 갈리는 것은 서브셸뿐).
`:104` 오류 문구의 **"빈 확장 등" 삭제**. **LIMIT 테스트 15건**이 종류별 대표 입력마다 **rc 0 을 단언**한다 —
잡으려 든 것이 아니라 못 잡는다고 적은 것이다. ⑵ **X4**: `RUN_GATE_PATH` **손잡이 제거** — 모든 테스트가
추적 실물을 겨누고 프로세스 헬퍼는 경로를 **명시 인자**로 받는다(환경변수 아님). **게이트 17 ⑭ 테스트는
소멸**했고 추적 여부 단언은 `test_runner_exists_tracked_and_parses` 로 흡수됐다. **음성 대조**: 추적 실물의
zero-work 판정을 `if false` 로 무력화 → **3 failed**(손잡이 없음) · 같은 파손 + `RUN_GATE_PATH` = 온전한
사본 → **여전히 3 failed** — **codex 가 23 passed 를 낸 경로가 닫혔다.** ⑶ **T2**: 탐지기를 `startswith`
grep 에서 **`yaml.safe_load`** 로 교체(옆 `read_ci_job_names` 와 같은 방식) — 모든 잡·모든 스텝의 `run` 값
전체를 본다. **음성 대조**: 블록 스칼라(`run: |`) 안 심기 → 1 failed · `- run:` 형태 → 1 failed · 옛 방식은
블록 스칼라 미탐지 재현. **검증(각 단독 실행)**: `bash -n` 0 · `ruff check` 0 · `ruff format --check` 0 ·
`mypy . .claude` 0(137 파일) · `pytest tests/test_run_gate.py tests/test_harness_scope_reach.py` 0(**70 passed**).

**(나) 러너 실사용 전 4건** — T3(대조표 자신에게 강제자 0) · T4(대조표가 머리 주석 문장을 전부 덮지
않는다 — Claude 3개 + codex 1개) · T5(zsh 로 러너를 부르는 테스트 0 = ⑬ 잔여) · R7(nounset 명시 도달,
**충돌** — §3 ⑨). **(다) 리더 판단·이월 4건** — S2(**충돌** — §3 ⑩) · T6(커밋 메시지 「(테스트 고정)」 함수
경로 과대) · X6(마커 truncate → 거짓 rc 2, **안전 방향**) · R10·R9(shellcheck 도달 0 · bash 5.x 미검토).

**군집 사실 (판정 아님).** (가) 8건 중 **6건이 「선언이 도달보다 넓다」 한 종류**이고,
**4건(X1·X2a·X3·X4)은 이 배치가 바로 이번에 세운 장치**다. Claude 1차와 codex 총평이 **독립적으로 같은
군집을 지목**했다(codex 문면: "four independent false-green/scope-integrity defects").

### §3 — 사용자 판단 대기 10건 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

> ①~⑥ 은 「게이트 15·16 후속」 §3 에서 이월된 것이고 **근거 전문은 그 표에 있다**(여기서 요약하지 않는다 —
> 옮겨 적으면 갈린다). ⑦~⑩ 이 게이트 18 충돌 4건으로 새로 추가된 것이다.
> **10건 전부 심각도·처방 라벨이고 착수를 차단하는 판정은 하나도 없다.**

| # | 쟁점 | 출처 | codex 쪽 | Claude 쪽 | 리더 권고 | 상태 |
|---|---|---|---|---|---|---|
| ① | **X2 의 심각도** — 게이트 우회 회귀 6종 | 게이트 15 | **high**(= 차단②) | **적정 — 지적 없음**(T-B) | **codex 판정 채택 권고** | **사용자 확인 대기** |
| ② | **X13** — F3 계약의 `measured_on: raw` 2필드 | 게이트 15 | **high**(C15-7) | 원시를 전제로 수용(C-B) | **전반부 기각**(프롬프트 전제 오류) **· 후반부 유지** | **사용자 확인 대기** |
| ③ | **X8·X9 의 심각도** — 같은 사실, 갈린 척도 | 게이트 15 | **high**(C15-3·C15-1) | **권고**(K-E·K-C) | **판정 유보 — 어느 쪽이든 마감은 같다** | **사용자 확인 대기** |
| ④ | **R-5 의 처분** — `allow_empty` 가 비지 않는 style 하한까지 면제 | 게이트 14 재실행분 | **high 0.99** | **정당화됐다**(1차 §3.2) | **codex 처방(ⓐ) 채택 권고 — L345 재판정 여부는 사용자 몫** | **사용자 확인 대기** |
| ⑤ | **E·G 의 심각도** — floor 빈 선언 통과 + 미등재 미탐지 | 게이트 16 | **high 0.99** | **수정 필요** | **codex 판정 채택 권고**(되돌릴 수 없는 창) | **사용자 확인 대기** |
| ⑥ | **H 의 심각도** — F3 다섯 필드 강제자 0 | 게이트 16 | **high 0.99**(K16-3) | **부분 해소 + 권고**(R-10) | **판정 유보 — 마감에는 양 레인이 합의**(Phase 3 **종료**) | **사용자 확인 대기** |
| **⑦** | **X1 — 빈 `_MAINLINE_PHRASES` 의 도달 가능성** | **게이트 18** | **high · no-ship** — "빈 선언은 0개 문구를 잰다. 완전성 테스트와 **대조군 둘 다** 공허 통과하며, 출력에 `원장이 낡았다` 가 있어도 통과했다"(메모리 탐침 재현) | **지적 없음** — "파생 상수는 표 유도 10개 + **하드코딩 5개**라 표가 비어도 5개는 남고, 그 상태는 ⑤a/⑤b 로 먼저 빨개지므로 **도달 가능한 상태가 아니다**"(R18-4 3행) | **판정 필요 — 두 진술이 다른 변이 경로를 가정한다.** Claude 는 **표가 비는 경로**, codex 는 **`:624` 를 직접 편집하는 경로**를 따라갔고 후자에 Claude 의 완화 근거가 작동하지 않는다(표가 온전한 채 파생 상수만 빈다). 제3 근거가 codex 경로 성립을 실행 확인했다(`()` 치환 → 36 passed · 37 passed). **어느 쪽도 지우지 않는다** | **사용자 확인 대기** · 수정은 진행 중(§2 순위 1) |
| **⑧** | **X4 — `RUN_GATE_PATH` 관측의 해석** | **게이트 18** | **high · no-ship** — "그 테스트가 스스로 override 를 지워 통과하고, 그 뒤 `_runner()` 는 다시 리디렉트 경로를 돌려준다. **낡거나 주입된 환경이 다른 스크립트를 재는데도 스위트는 추적 기본값을 쟀다고 주장한다**" | **해소 근거로 사용** — "`RUN_GATE_PATH` 를 옛 판으로 돌려도 이 테스트가 통과한다 — ⑭ 의 `delenv` 가 **실제로 동작한다는 뜻**"(1차 §2.1 → 게이트 17 ⑭ 해소 판정) | **리더 판정 = codex 축 채택, 처방은 「손잡이 제거」**(`e2282b3`). **관측은 동일하고 해석이 반대였으며 둘 다 참**이다 — 제3 근거가 false-green 성립을 확인했다(추적 실물 파손 + 온전한 사본 손잡이 → **23 passed**). 손잡이를 없애 두 해석이 함께 소멸했고 **게이트 17 ⑭ 판정 자체가 사라졌다**. **사용자가 뒤집을 수 있는 것은 「⑭ 를 해소로 남길 것인가」의 이력 라벨이다** | **사용자 확인 대기**(라벨) · 수정 완료 |
| **⑨** | **R7 — `-o nounset` 계약 변경** | **게이트 18** | **지적 없음** — "nounset 은 명시·테스트돼 있다." 단 **깨지는 형태 1종 실측**: `if [ -n "$OPTIONAL_GATE_FLAG" ]; then …; fi` 가 옛 rc 0 → 새 자식 **rc 127** | **권고** — "명시는 머리 주석 한 곳뿐이고 SKILL.md 는 값을 지우며 산문 강제자만 남겼다. **오탐 실측 11종 전부 무영향** … 지적은 **문서 도달**이지 기능이 아니다" | **판정 필요 — 두 리뷰가 서로의 결론을 각각 좁혔다.** Claude 의 11종 목록에 `[ -n "$UNSET" ]` 형태가 없었고 codex 가 그 한 종에서 rc 127 을 실측했다. 「현재 소비자 0」은 이 회차 전수 grep 으로 **합의**됐다. **아래 §4 ① 에 사실로 등재**한다 | **사용자 확인 대기** |
| **⑩** | **S2 — SKILL.md 축약의 부수 손실** | **게이트 18** | **지적 없음** — "규칙 5 는 이제 값을 복사하지 않고 러너 머리 주석을 지목하며 **그 정본이 실재한다.** No issue found there" | **권고 · 손실 기록** — "함께 사라진 것 둘: ⑴ 「러너 호출 자체를 파이프에 태우면 무효다」 — **게이트 16 B 로 실제 사고가 났던 그 함정**, ⑵ 「인자에 비밀값을 넣지 마라」. 대체 강제자는 산문 한 문장이고 **도달 0**" | **판정 필요 — 같은 대상의 다른 축이다.** codex 가 답한 질문은 「지목만 하는가 / 정본이 실재하는가」이고 둘 다 참이다. Claude 가 제기한 것은 「**사용 지점**에서 함정 경고가 사라졌고 대체 강제자 도달이 0」이라는 별개 축이며 **codex 는 그 축을 묻지 않았다.** codex 의 무지적이 Claude 의 축을 덮지 않는다 | **사용자 확인 대기** — 처방 선택 포함 |

**⑦~⑩ 이 하나도 착수를 막지 않는 이유를 적어 둔다.** 넷 다 **심각도 라벨과 처방 선택**의 쟁점이고,
그중 둘(⑧·⑨)은 **수정이 이미 끝났거나 기능 영향이 실측으로 좁혀졌다.** 나머지 둘(⑦·⑩)의 마감은
각각 「§6 X2 게이트 첫 실사용」·「리더 판단」이라 **Phase 3 대상 코드에 닿지 않는다.**

### §4 — 사실 기록 4건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **`-o nounset` 은 계약 변경이다** — 러너를 경유하는 명령에서 **미설정 변수 참조가 이제 실패**로 바뀐다(`'$GATE_CMD'` 가 옛 판에서 빈 문자열로 확장돼 0건 실행·exit 0 이던 것을 닫은 조치의 부수 결과). 값은 bash 판에 따라 다르고(3.2 는 127) **계약은 「비-0」이다.** 선택 변수는 `${VAR:-}` 로 쓰면 통과한다 | `run_gate.sh` 머리 주석 ⒞ (`318069b` 판 `:35-38`) · 게이트 18 R7 충돌 — codex 실측 `if [ -n "$OPTIONAL_GATE_FLAG" ]; then …; fi` **옛 rc 0 → 새 자식 rc 127** · Claude 실측 현실 게이트 명령 **11종 전부 무영향** · 저장소 러너 소비자 **0**(cross §6 ④ 전수 grep) | **러너를 실사용하기 전에 사용자가 알아야 한다.** 지금은 소비자가 0이라 아무 게이트도 깨지지 않지만, 러너에 명령을 태우기 시작하는 순간 **미설정 변수를 쓰던 명령이 조용히 아니라 시끄럽게 죽는다.** 두 리뷰가 서로의 완화 근거를 좁힌 자리이므로(§3 ⑨) 한쪽 결론만 옮겨 적지 않는다 |
| ② | **러너 머리 주석의 「빈 확장까지 잡는다」류 주장이 실측에서 깨져 두 번에 걸쳐 지워졌다.** ⑴ `107c8a5` 이 값이 **빈 문자열로 설정된** 변수(`'$V'`, V="")는 문법상 단순 명령이라 DEBUG trap 이 발화한 뒤 확장이 비어 rc 0 이 됨을 실측하고 **머리 주석의 그 주장을 지운 뒤 잔여로 이름 붙였다**(LIMIT 테스트로 문서화). ⑵ `e2282b3` 이 `:104` 오류 문구의 **"빈 확장 등"을 삭제**하고 잔여를 **두 종류**로 다시 적었다 | `107c8a5` 커밋 본문 「잔여(정직하게)」 · `318069b` 판 머리 주석 ⒝ · `e2282b3`(게이트 18 X3+R18-1+R18-2) | **장치가 하는 일보다 크게 적지 않는다**는 규칙 4 가 **같은 파일에서 두 회차 연속** 적용된 자리다. 두 번 다 고친 방향이 「기제를 더 쌓기」가 아니라 **「선언을 도달까지 줄이기」**였고, 두 번째는 리더 제약(새 기제 금지)이 그것을 명시적으로 강제했다 |
| ③ | **`cp` 가 여전히 `-i` 별칭인 셸이 에이전트 레인에 살아 있다.** 게이트 18 조치 레인이 첫 시도에서 worktree 로의 파일 복사가 **조용히 거절**되는 것을 만났고(검증 **전**에 발견), `write_bytes` + sha256 대조로 바꿔 재실행했다 | `e2282b3` 커밋 본문 음성 대조 절("cp 는 -i 별칭이라 첫 시도가 조용히 거절돼 규칙 5 그대로 Python 으로 바꿨다") | **CLAUDE.md 변경 이력 2026-08-14 의 전제가 부분적으로 틀렸다** — 사용자가 별칭을 제거했다는 통보 뒤에도 **에이전트 셸에는 남아 있는 경우가 있다.** 규칙 5 「복원은 `cp` 로 하지 마라」는 그때 **별칭과 무관한 근거**(사본의 최신성·복원 내용을 증명하지 못한다)로 유지됐는데, 이 관측은 **별칭 자체도 아직 소멸하지 않았다**는 실측 1건이다. 이번에는 검증 전에 잡혀 손실이 없었다 — 그것이 규칙을 유지한 값이다 |
| ④ | **로컬 게이트 전건 통과** (HEAD `318069b`) — `ruff check` **exit 0** · `ruff format --check` **exit 0** · `mypy . .claude` **exit 0** · `uv run pytest` 전체 **exit 0(1232 passed)** · 게이트+가드+표기검사 **85 passed** · `dump_python_snapshots.py --check` **exit 0**(실물 diff 0 유지) | HEAD `318069b` · 각 명령 **단독 실행**(파이프 없음) | 기준 시점 없는 절대 수치는 다음 커밋에 거짓이 되므로 **HEAD 해시와 함께** 적는다. 직전 기록(`48a791c` 시점)은 pytest **1217 passed** 였다 — 늘어난 15건은 이 배치의 회귀·LIMIT·완전성 테스트다. `107c8a5` 시점 mypy **137 파일**. **후속 `e2282b3` 은 전체 pytest 를 다시 돌리지 않았다**(러너·표기검사 70 passed 까지) — 그 사실을 지우지 않는다 |

### §5 — 수렴 표 (게이트 18 cross §9 그대로 · **사실만. 추세 해석은 하지 않는다**)

> **첫 열 머리를 `게이트 회차` 로 적은 이유** — 표기 검사기가 첫 머리 `게이트` 를 「아직 돌리지 않은 검증
> 게이트」 표의 표지로 쓴다. 이 표는 그 표가 아니므로 같은 머리를 쓰지 않는다.

| 게이트 회차 | 대상 | Claude 차단 | codex high | cross 차단후보/high 행 | 그 회차가 새로 세운 장치에서 나온 빈자리 | Phase 3 대상 코드 지적 |
|---|---|---|---|---|---|---|
| **15** `phase3-preflight` | `…614afed` | **2** | **8**(전부 high) | 3 | **해당 없음** — 러너가 아직 없었다 | **다수** — X5·X6·X7·X13·X8~X12 |
| **16** `gate15-fixes` | 게이트 15 후속 배치(6커밋) | **1**(차단② T-E) | **3**(K16-1~3) | 4 | **4** — 게이트 15 X3 로 **신설된 러너**에서 A·B·C·D | **있음** — H·I·J·W(마감 Phase 3 **종료**) · M · N |
| **17** `gate16-fixes` | `1cb7bdf..48a791c` | **1**(차단② ⑤) | **3** | 5 | **5** — 게이트 16 이 세운 **완전성 장치**에서 ②③④⑤ + 고쳐진 **러너**에서 ① | **있음** — ⑩ · ⑪ · ⑫ |
| **18** `gate17-fixes` | `36b5ed4..318069b` | **0** | **4** | 4 | **4 — 4/4 전부** 이 배치가 세운 것: 완전성 장치 신판(X1·X2a) · 러너 zero-work 기제와 그 테스트(X3·X4) | **0** — Kotlin·계약·프론트 **diff 0줄** |

**표에서 곧바로 읽히는 사실 넷 (해석 없이).**
⑴ **Claude 차단 건수는 2 → 1 → 1 → 0** 으로 내려왔다. 게이트 18 이 **처음으로 차단 0** 이다.
⑵ **codex high 건수는 8 → 3 → 3 → 4** 로 내려오다 **이번에 다시 올랐다.** 단조 감소가 아니다.
⑶ **게이트 18 codex high 4건은 전부 "false-green/scope-integrity" 한 종류**이고, **4/4 가 이 배치가 방금
세운 하네스 자기 검사 장치**다. 사용자 데이터 경로·제품 코드 지적은 **0건**이다.
⑷ **Phase 3 대상 코드 지적은 게이트 18 에서 0건**이고 15·16·17 에는 매번 있었다. 이 배치의 Kotlin·계약·
프론트 diff 가 **0줄**이기 때문이다 — **「Phase 3 코드가 깨끗하다」가 아니라 「이번 회차가 Phase 3 코드를
보지 않았다」가 정확한 진술이다.**

**「새 장치 자기 빈자리」의 연속성 (사실).** 게이트 **16·17·18 세 회차 연속**, 직전 배치가 세운 게이트
장치에서 다음 게이트가 새 빈자리를 찾았다. 건수는 **4 → 5 → 4** 다. **단, 종류는 좁아졌다** — 게이트 16 의
4건은 「빈 인자가 통과」·「인용 경계 소실」처럼 **기능 결함**을 포함했고, 게이트 18 의 4건은 전부
**「선언이 도달보다 넓다」 한 종류**다. **이 문단은 사실이고 추세 판정이 아니다** — 「수렴하고 있으니
그만 봐도 된다」로 읽지 마라. 판정 자리는 위 「게이트 16 후속」 §6·§6-a 이고 **그 절도 아직 판정하지 않았다.**

## 게이트 19 후속 — 조치·리더 결정 3건·Phase 3 이관 (2026-08-19, 리더)

정본은 `reviews/19_gate18-fixes_cross.md`(`445c5cf`)다. 이 절은 그 판정과 후속 조치 배치
(**`e91ecdd` + `e600861` + `e7f9bdb`** 3 커밋), 그리고 **리더 결정 3건**을 원장 형식으로 옮긴 것이며, **수치·판정에는 커밋 해시를 함께 적는다**
(수치 인용 규약 — 기준 시점 없는 절대 수치는 다음 커밋에 거짓이 된다).

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다. 「게이트 15·16·17·18 후속」과 마찬가지로
> 이 절의 표들은 종료 조건 표가 아니라 **처리 현황 표**라 표기 검사기(`tests/test_harness_scope_reach.py`)의
> 대상 4표에 들어가지 않는다 — 그래도 같은 어휘를 쓴다.

### §1 — 게이트 19 조치 배치 현황 (`e91ecdd` · `e600861` · `e7f9bdb`)

> 표의 번호(①~⑯)는 `reviews/19_gate18-fixes_cross.md` §3 교차 대조표의 항목 번호다.

| 게이트 19 | 커밋 | 실행 경로 | 근거·음성 대조 | 상태 |
|---|---|---|---|---|
| **① `_DYNAMIC_LOOKUP_NAMES` 빈 선언 통과** (Claude 단독 **차단②**, R19-1) | `e91ecdd` → `e600861` → `e7f9bdb` | `ci:quality` | **개수가 아니라 내용으로 묶었다.** `e91ecdd`: `_REQUIRED_DYNAMIC_LOOKUP_NAMES`(`getattr`·`__import__`·`eval`·`exec` **4종**)를 신설해 `_DYNAMIC_LOOKUP_NAMES` 가 그 넷을 **포함**함을 단언한다 — 비우거나 같은 개수의 다른 이름으로 치환하면 실패한다. 형제 상수(`_MAINLINE_ROOTS`·`_HELPER_SUFFIXES`)와 달리 이 집합은 비어도 "동적 조회 없음" 단언이 **공허하게 참**이었다(cross §6.1 B·V1 = **36 passed**). `e600861`: **핵심 4종 밖의 junk 치환**이 남아 있었다 — 나머지 이름도 `hasattr(builtins, name)` 로 **실재하는 builtin** 이어야 한다(없는 이름은 어떤 호출과도 일치하지 않아 자리만 채운다). `e7f9bdb`: **부분집합 + builtin 실재로도 자유 영역이 남았다** — 나머지 3개를 **다른 builtin**(`len`·`id`·`print`)으로 치환하면 두 단언이 다 참인 채 탐지 범위만 좁아진다 → `_REQUIRED_DYNAMIC_LOOKUP_NAMES`(부분집합)를 **`EXPECTED_DYNAMIC_LOOKUP_NAMES`(내용 정확 일치, 7종)**로 갈아 **자유 영역을 없앴다.** 전례는 `test_harness_scope_reach.py` 의 정체성 키 집합이고, 효과는 같다 — **어떤 치환도 기대 집합을 같은 커밋에서 함께 고쳐야 통과하며 그 편집이 diff 로 리뷰에 올라간다** | **해소** — **리뷰 미수령** |
| **⑥ `EXPECTED_MAINLINE_PHRASES` 가 개수만 고정** (합의, R19-4 ≡ X19-5) | `e91ecdd` → `e600861` | `ci:quality` | 같은 처방을 **문구**에 적용했다. `e91ecdd`: ⑴ 표에서 파생되는 문구가 목록에 실재할 것(`derived_phrases ⊆ _MAINLINE_PHRASES`), ⑵ 목록의 **전 문구**가 이 모듈의 `assert "…" in output` 단언 **∪** 비교기 문자열 상수에 존재할 것. 두 소스의 **합집합**을 쓴 이유는 어느 한쪽만으로는 f-string 조각·합성 문구가 빠지기 때문이고, 하드코딩 5문구까지 이것으로 결속된다. `e600861`: **튜플은 중복을 허용한다** — 하드코딩 5문구를 표 문구의 **복사본**으로 치환하면 개수·결속·부분집합이 **전부 참인 채 대조군만 조용히 좁아졌다** → 문구 **유일성** 단언 추가 | **해소** — **리뷰 미수령** |

**이 배치의 성립 조건 셋을 적어 둔다.** ⑴ **새 기제 0** — 기존 `_asserted_output_phrases`·`_string_constants`
를 재사용했고 새 탐지 축을 세우지 않았다(게이트 18 배치에 내린 리더 제약을 그대로 적용). ⑵ **리더 직접 편집** —
API 과부하로 실행 레인을 스폰할 수 없었고, 대상이 **1파일 소규모**(`tests/test_parity_ci_gate.py`
`e91ecdd` +41 −2 · `e600861` +15 −1 · `e7f9bdb` +16 −13)라 리더가 직접 썼다.
⑶ **stop-time 게이트가 같은 우회를 세 번 잡았다** — **1차**: 초판이 `_DYNAMIC_LOOKUP_NAMES` 를 **개수**로 고정했는데 그것은 **같은 개수의 다른
이름으로 치환하는 우회**를 그대로 남긴다는 지적을 받아 **개수 → 내용**으로 정정했다(`e91ecdd`).
**2차**: `e91ecdd` 뒤에도 **「동일 개수 치환」 통로가 두 개 남아 있다**는 지적을 받아 닫았다(`e600861`) —
문구 목록의 **중복 허용**(하드코딩 5문구를 표 문구 복사본으로 치환)과 핵심 4종 **밖 이름의 junk 치환**.
**3차**: `e600861` 뒤에도 **부분집합 + builtin 실재가 남긴 자유 영역**(비핵심 3개를 다른 builtin 으로
치환)이 지적돼 **내용 정확 일치**로 닫았다(`e7f9bdb`).
**음성 대조** (일회용 worktree · `write_bytes` · `cp` 미사용): `e91ecdd` 4변이(빈 집합 · 7개 junk 치환 ·
문구 치환 2종) · `e600861` 2변이(문구 복사본 · 비-builtin 이름) · **`e7f9bdb` 6변이**(비핵심→builtin ·
핵심→builtin · 빈 집합 · 7 junk · 이름 1개 삭제 · 문구 복사본) — **전부 각 1 failed**, 기준 **36 passed**.

**같은 지적이 세 번 난 것을 사실로 남긴다.** 1차 정정이 「개수 → 내용」이라는 **옳은 방향**이었는데도
그 방향을 **끝까지 적용하지 않아 통로가 두 번 더 남았다** — 개수(1차) → 부분집합(2차) → **내용 정확
일치**(3차). 이것은 cross §9.4 가 기록한 「매 회차 같은 처방을 새 상수에는 적용하지 않아 다시 난다」는
형태가 **한 배치 안에서 두 번 되풀이된 것**이고, **처방을 「절반만」 적용하면 남는 자리가 바로 자유
영역**이라는 것을 세 번에 걸쳐 보여 준 자리다. 최종형(`EXPECTED_DYNAMIC_LOOKUP_NAMES` 내용 정확 일치)은
`test_harness_scope_reach.py` 의 정체성 키 집합이 **이미 쓰던 형태**다 — 전례가 저장소 안에 있었는데
그것을 세 번째에야 가져왔다.

**리뷰 미수령 — Phase 3 첫 리뷰 게이트 범위에 포함한다 (리더 결정).** **`e91ecdd`·`e600861`·`e7f9bdb`
세 커밋 모두 독립 리뷰를 받지 않았다** — **첫 리뷰 게이트 범위 = `e91ecdd` + `e600861` + `e7f9bdb`** 이다.
별도 게이트(20회차)를 열지 않는 이유는 **1줄~수십 줄 정확 일치 누락에 회차 하나를 여는 순환을
끊기 위해서**이고, 「하네스 변경도 리뷰 대상이다」라는 규칙은 **다음 게이트로 지킨다** — 즉 Phase 3 첫 리뷰
게이트의 대상 범위에 **`e91ecdd` + `e600861` + `e7f9bdb`** 를 **명시적으로 포함**시킨다. cross §7 이 이 방향에 대한 **반대 근거 3건과
뒷받침 근거 2건**을 양쪽 병기했고, 「세 조건(⑴ 착수 전 마감 ⑵ 리더 선결정 ⑶ 종결 표기 제한)을 붙이면
방향과 양립한다」가 두 산출물에서 읽히는 사실이다. **세 조건의 수용 방식은 아래 「Phase 3 착수 판정」 절에 적는다.**

### §2 — 리더 결정 3건 (2026-08-19)

> 게이트 19 가 낸 잔여 중 **수정으로 닫지 않고 결정으로 처분한 것**이다. 각 결정은 무엇을 닫고
> 무엇을 열어 두는지를 함께 적는다 — **닫힌 것처럼 보이게 만드는 결정을 쓰지 않는다.**

| # | 결정 | 무엇을 닫는가 | 무엇이 열린 채 남는가 | 근거 |
|---|---|---|---|---|
| **⑴** | **러너(`.claude/skills/kotlin-migration/scripts/run_gate.sh`)를 동결한다.** 하네스 자기 검사를 **더 넓히지 않는다** — 잔여의 마감을 **「러너 실사용(CI 배선) 전」** 한 줄로 통일한다 | 게이트 16~19 네 회차가 러너와 그 테스트에서만 새 빈자리를 낸 순환. **러너는 지금 실사용 0 · CI 배선 0** 이므로 잔여가 어떤 게이트의 근거도 되고 있지 않다 | **⑦**(LIMIT 두 목록 빈 선언 통과, 합의 · codex **high**) · **⑩**(잔여 (가)/(나) 밖 **세 번째 종류**, codex 단독 high) · **⑬**(X4 대체 선언 강제자 0) · **⑫**(`ci.yml` 한 파일만 읽는다) · **⑭**(대조표 `:23`·`:25` 대리 지표) · **X19-2/⑨/⑯**(`:113` 문구 과소 · `:39` 면제 조항) · **X19-6/⑪**(env·local action·reusable 미도달) · 게이트 18 이월 T3·T4·T5·X5·R7·S2·T6·X6·R9·R10 | cross §8(나)(다) — **마감이 전부 「러너 실사용 전」**이고 착수 이전이라 단정된 것이 없다. 실사용 0 은 cross §6 전제 2(전수 검색 참조 4건 전부 비-검사)와 §11.1 이 각각 확인 |
| **⑵** | **X1·X2a·T1 을 「부분 해소」로 표기한다** — codex 조건 3(「독립 강제자가 승인될 때까지 **완전 종결로 기록하지 않는다**」)을 **수용**한다 | 원장이 세 항목을 「완전 종결」로 적어 다음 회차가 그 표기를 근거로 삼는 경로 | **⑧**(신규 완전성 단언 제거 시 조용히 통과 — 외부 강제자 0, codex 단독 **high**)는 **열려 있다.** 그 강제자를 세우는 일은 ⑴ 의 동결 대상이 아니다(러너가 아니라 parity 게이트 축) — 마감은 **§6 X2 게이트 첫 실사용** | cross §5 · §7.1 (다) · codex X19-3 Rec. — Claude 는 X1·T2 를 「해소+별건」, codex 는 「부분 해소」로 이름 붙였고 **잔여 목록은 같다**. 더 보수적인 이름표를 택한다 |
| **⑶** | **②(별칭·`partial`·import alias) · ③(`_MAINLINE_ROOTS` 밖 새 root) · ⑧(단언 외부 강제자 0)은 「한계」로 등재한다** — 수정하지 않는다 | 「테스트 파일 안의 단언을 지키는 또 다른 단언」을 무한히 세우는 재귀. **테스트 파일 안 단언의 밖은 diff 리뷰다** | 셋 다 **열린 한계**로 남는다. ②·③ 은 **합의 · codex high**, ⑧ 은 **codex 단독 high** 이고 **어느 것도 「해소」로 적지 않는다** | **규칙 6 의 경계다** — 도달을 강제하는 장치를 하나 더 세우면 그 장치의 도달을 다시 물어야 한다(게이트 16→17→18→19 가 그 재귀를 네 번 보여 줬다). **여기서 끊고 사람의 diff 리뷰에 맡긴다는 것을 명시적으로 적는다** — 자동 강제자가 있는 척하지 않기 위해서다 |

**⑴ 이 「하네스 자기 검사 확대 금지」를 뜻한다.** 러너·완전성 장치·표기 검사기에 **새 검사를 더하지
않는다.** 예외는 두 가지뿐이다 — ⓐ 러너를 **실제로 CI 에 배선하는 시점**(그때 잔여 전건이 마감을 맞는다),
ⓑ **Phase 3 제품 코드**(JDBC·인증 API·계약 테스트)를 재는 장치. **하네스가 하네스를 재는 회차를 더 열지
않는다**는 것이 이 결정의 내용이다.

### §3 — 사용자 판단 대기 11건 (양쪽 근거 병기 — 어느 쪽도 지우지 않는다)

> ①~⑩ 은 「게이트 17·18 후속」 §3 에서 **그대로 이월**된 것이고 **근거 전문은 그 표에 있다**
> (여기서 요약하지 않는다 — 옮겨 적으면 갈린다). 게이트 19 는 그중 **R7·S2 를 무변경으로 다시 올렸고**
> (cross §8(다) — 「답이 오지 않았다는 이유로 닫지 않는다」), **X4(⑧)는 라벨 쟁점만 남았다.**
> ⑪ 이 게이트 19 충돌 1건으로 새로 추가된 것이다.
> **11건 전부 심각도·처방·절차 라벨이고 착수를 차단하는 판정은 하나도 없다.**

| # | 쟁점 | 출처 | codex 쪽 | Claude 쪽 | 리더 권고 | 상태 |
|---|---|---|---|---|---|---|
| ①~⑩ | **이월 10건** — X2 심각도 · X13 · X8·X9 심각도 · R-5 처분 · E·G 심각도 · H 심각도 · X1 도달 가능성 · X4 라벨 · R7 nounset · S2 SKILL.md 축약 | 게이트 14·15·16·18 | (각 행) | (각 행) | (각 행) | **무변경 · 사용자 확인 대기** — 정본은 「게이트 17·18 후속」 §3 |
| **⑪** | **⑤ — `_root_helper_calls`·`_DYNAMIC_LOOKUP_NAMES` 가 리더 제약(「새 기제 금지 — 정확 일치·표기 정직화·기존 방법 교체만」)을 넘었는가** | **게이트 19** | **high** — "둘 다 `bbe49a1` 에 없었고 기존 탐지의 표현 교체가 아니라 **이전에 못 보던 외부 helper 호출을 새로 잡기 위한 탐지 장치**다. 따라서 「새 기제는 만들지 않았다」는 **C1 과 맞지 않는다.**" Rec.: **현재 배치에서는 X2a 종결 주장을 철회**하고, 유지하려면 **리더 승인을 받은 별도 배치에서** | **새 판정 축은 `_DYNAMIC_LOOKUP_NAMES` 하나** — `_root_helper_calls` 는 **같은 파일 `_call_sites` 의 AST 대조를 호출 측으로 뒤집은 것**이고, `_asserted_output_phrases`·`_string_constants` 는 ③ 이 이미 쓰던 방법이다. **대체로 준수** | **판정 필요 — 제3 근거가 양쪽을 다 참으로 만들었다.** cross §4 가 옛 `_call_sites` ↔ 새 `_root_helper_calls` 를 전문 대조한 결과: **AST 기계는 상속**(Claude 근거) · **호출 노드 `ast.Attribute` 추가와 매칭 기준의 「모듈이 정의한 이름」→「이름 접미사 규약」 전환으로 도달은 확대**(codex 근거). 덧붙은 사실 둘 — ⑴ **「X2a 를 닫아라」와 「새 기제를 만들지 마라」가 구조적으로 충돌한다**(도달을 넓히지 않고 X2a 를 닫는 길은 없다) ⑵ **결과에서는 갈리지 않는다**(양쪽 다 X2a **부분 해소**). **어느 쪽도 지우지 않는다** | **사용자 확인 대기** — 영향 범위는 **「이 배치가 제약을 지켰는가」라는 절차 판정**과 codex 가 권고한 **「X2a 종결 주장 철회」 여부**뿐이다. 위 §2 ⑵ 가 이미 **부분 해소 표기**를 택했으므로 어느 쪽으로 나든 **잔여 목록과 마감은 바뀌지 않는다** |

**⑪ 이 착수를 막지 않는 이유를 적어 둔다.** cross §4 가 명시했듯 **판정 결과가 X2a 잔여 처리에 영향을
주지 않는다** — 양쪽 다 부분 해소로 같고, 리더 결정 ⑵ 가 그 보수적 표기를 이미 채택했다.
남는 것은 **절차 라벨**이며, 그 라벨은 Phase 3 대상 코드에 닿지 않는다.

### §4 — 사실 기록 4건

| # | 사실 | 근거 | 왜 남기는가 |
|---|---|---|---|
| ① | **수렴 표가 발산으로 뒤집혔다** (아래 표) — Claude 차단 **2·1·1·0·1**(게이트 18 의 0 에서 다시 1) · codex high **8·3·3·4·4**(두 회차 연속 4) · cross 차단후보/high **3·4·5·4·8**(**이번이 최대**) · 새 장치 자기 빈자리 **—·4·5·4·10**(이번이 최대) · **Phase 3 대상 코드 지적 다수·있음·있음·0·0** | 게이트 19 cross §9.1 (게이트 18 cross §9 를 이어받음) | 「수렴하고 있으니 그만 봐도 된다」로 읽히던 자리가 **사실로 뒤집혔다.** 다만 뒤집힌 이유가 「결함이 늘었다」가 아니다 — **두 관점의 단독 지적이 겹치지 않고 더해졌다**(Claude 단독 5 + codex 단독 3). 그리고 **10건 전부가 하네스 자기 검사 장치**다. 이 두 사실을 함께 읽지 않으면 표가 제품 위험 신호로 오독된다 |
| ② | **제품 코드가 리뷰 대상에 들어오지 않은 회차가 두 번 연속이다** — 게이트 18·19 둘 다 Kotlin·계약·프론트 **diff 0줄**이고 Phase 3 대상 코드 지적 **0건** | 게이트 19 cross §9.1 4 · §7.2 B | **「Phase 3 코드가 깨끗하다」가 아니라 「이번 회차가 Phase 3 코드를 보지 않았다」가 정확한 진술**이라는 게이트 18 의 문장이 그대로 두 번째 성립했다. **이것이 리더 결정 ⑴(러너 동결)의 실질적 근거**다 — 게이트 회차가 제품이 아니라 하네스를 소비하고 있다 |
| ③ | **API 과부하로 레인 3개가 중단·재개됐다.** 게이트 19 조치(`e91ecdd`·`e600861`·`e7f9bdb`)는 그 결과 **리더 직접 편집**으로 처리됐고, 그래서 **저작자와 심판자가 같아졌다** — 이 배치가 **리뷰 미수령**인 이유다. 그 상태에서 **stop-time 게이트가 같은 우회를 세 번 잡아** 커밋이 셋이 됐다 | `e91ecdd`·`e600861`·`e7f9bdb` 커밋 본문 · §1 성립 조건 ⑵⑶ | **하네스 규율의 예외가 환경 사정으로 발생했다는 사실을 지우지 않는다.** 예외를 정상화하지 않기 위해 **다음 게이트 범위에 세 커밋을 명시 포함**(§1 말미)시켰고, 그 사실을 아래 「Phase 3 착수 판정」 절이 착수 조건 행으로 다시 든다. **stop-time 게이트가 세 번 다 잡았다는 것이 리뷰 대체가 되지는 않는다** — 그것은 같은 세션 안의 검사이고, 독립 리뷰가 겨누는 것은 바로 그 자기 승인이다 |
| ④ | **음성 대조 프로브 자체에 결함이 있었다** (`e7f9bdb` 측정 중). 첫 변이 프로브가 `_DYNAMIC_LOOKUP_NAMES` 를 치환하면서 **`EXPECTED_DYNAMIC_LOOKUP_NAMES` 선언까지 함께 치환**해, 두 변이가 **거짓 통과**로 보였다. **줄 머리 앵커**로 프로브를 고쳐 재측정했고 그 뒤 6변이 전부 1 failed | `e7f9bdb` 커밋 본문 음성 대조 절 | **단언 결함이 아니라 프로브 결함이다** — 이 구분을 적어 두지 않으면 다음 사람이 「그때 그 단언은 통했다 안 통했다 갈렸다」로 읽는다. 그리고 이것은 **음성 대조가 스스로 거짓 통과를 낼 수 있다**는 실측 1건이다: 변이가 **의도한 자리 하나만** 바꿨는지 확인하지 않으면 대조군이 조용히 무력해진다. 같은 종류(선언과 사용처가 같은 문자열을 공유하는 상수)에서 재발할 수 있으므로 **관측 사실로만 등재**하고 처방은 정하지 않는다 |

#### 수렴 표 (게이트 19 cross §9.1 그대로 · **사실만. 추세 해석은 하지 않는다**)

> 첫 열 머리를 `게이트 회차` 로 적은 이유는 「게이트 17·18 후속」 §5 와 같다 — 표기 검사기가 첫 머리
> `게이트` 를 「아직 돌리지 않은 검증 게이트」 표의 표지로 쓴다.

| 게이트 회차 | 대상 | Claude 차단 | codex high | cross 차단후보/high 행 | 그 회차가 새로 세운 장치에서 나온 빈자리 | Phase 3 대상 코드 지적 |
|---|---|---|---|---|---|---|
| **15** `phase3-preflight` | `5797d87..614afed` | **2** | **8** | 3 | **해당 없음** — 러너 부재 | **다수** |
| **16** `gate15-fixes` | `614afed..1cb7bdf` | **1** | **3** | 4 | **4** — 신설 러너 A·B·C·D | **있음** |
| **17** `gate16-fixes` | `1cb7bdf..48a791c` | **1** | **3** | 5 | **5** — 완전성 장치 + 러너 | **있음** |
| **18** `gate17-fixes` | `36b5ed4..318069b` | **0** | **4** | 4 | **4 — 4/4 전부** 이 배치가 세운 것 | **0** — diff 0줄 |
| **19** `gate18-fixes` | `bbe49a1..5d58832` | **1** (①) | **4** (X19-1~4) | **8** | **10** (cross §9.3 열거) | **0** — diff 0줄 |

**로컬 게이트 전건 통과** (HEAD `e7f9bdb`) — `ruff check` **exit 0** · `ruff format --check` **exit 0** ·
`mypy . .claude` **exit 0(137 파일)** · `uv run pytest` 전체 **exit 0(1242 passed · 68 skipped ·
5 deselected · 5 xfailed)**. 각 명령 **단독 실행**(파이프 없음).
직전 기록(`318069b` 시점)은 **1232 passed** 였다 — 늘어난 10건은 `5d58832` 의 회귀 테스트이고,
`e91ecdd`·`e600861`·`e7f9bdb` 는 **기존 테스트 안에 단언을 더하거나 바꾼 것**이라 건수를 늘리지 않는다.
**중간 커밋도 따로 쟀다** — `e91ecdd` 는 일회용 worktree 에서(`ruff`·`format`·`mypy` 137 파일 exit 0 ·
pytest **1242 passed exit 0**), `e600861` 은 그 시점 작업 트리에서(같은 값). **세 시점의 건수가 전부
같다는 것이 「단언만 더했다」의 근거다.** worktree 는 `git worktree remove --force` 로 제거했다
(`git worktree list` 에 본 저장소만 남음).

## Phase 3 착수 판정 — **착수 허용 권고 · 사용자 승인 대기** (2026-08-19, 리더)

> **이 절이 판정한다.** 「게이트 16 후속」 §6·§6-a 가 모은 사실을 근거로 삼으며, 그 절은 이 갱신으로
> **닫혔다**(더 갱신하지 않는다). 판정 근거가 되는 수치·해시는 전부 위 절들이 들고 있고
> **여기로 옮겨 적지 않는다**.

### §1 — 착수 조건 대조

| 착수 조건 | 상태 | 실행 경로 | 근거 |
|---|---|---|---|
| **① 게이트 15 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/15_phase3-preflight_cross.md` | 3단계 완주(`c932b4f`) — 「게이트 16 후속」 §6 ① |
| **② 게이트 14 완전 재실행 + 교차 종합** | **충족** | `1회성:docs/migration/_workspace/reviews/14_floor-hardening_cross.md` | 재실행 완료(`c932b4f`) + 교차 종합 완료(`4dabc0d`), §R.10 명시 판정 — 「게이트 16 후속」 §6 ② |
| **③ Phase 3 착수 차단 6건 처리** | **충족** | `ci:kotlin` · `ci:quality` · `안 돎` | 6건 중 **X6 강제자 축만 열려 있고 그 마감은 Phase 3 DTO 구현 커밋** — **착수가 아니라 종료를 막는다**(§6-a · 「게이트 16 후속」 §2 H·J·W). 나머지 5건은 해소·수정 완료이며 X5(test-plan)의 실행 마감은 **Phase 3 첫 계약 테스트**다 |
| **④ 게이트 16 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/16_gate15-fixes_cross.md` | 3단계 완주(`cc7f53c`) · 잔여의 마감은 전부 Phase 3 **종료** 또는 Phase 4 — 「게이트 16 후속」 §6 ④ |
| **⑤ 게이트 17 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/17_gate16-fixes_cross.md` | 3단계 완주(`36b5ed4`) — 「게이트 16 후속」 §6 ⑤ |
| **⑥ 게이트 18 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/18_gate17-fixes_cross.md` | 3단계 완주(`bbe49a1`) · 잔여 8건은 게이트 19 가 재판정 — 「게이트 16 후속」 §6 ⑧ |
| **⑦ 게이트 19 종결** | **충족** | `1회성:docs/migration/_workspace/reviews/19_gate18-fixes_cross.md` | 3단계 완주(`445c5cf`) · **게이트 18 종결 합성 미해소 0(양쪽 합의)** · 전제 오류 0 — 「게이트 16 후속」 §6 ⑨ |
| **⑧ 게이트 19 ①·⑥ 수정 커밋** | **충족 — 단 리뷰 미수령** | `ci:quality` | **`e91ecdd` + `e600861` + `e7f9bdb`** (차단② ①과 합의 ⑥. stop-time 게이트가 같은 우회를 **세 번** 잡아 3 커밋이 됐다). **독립 리뷰를 받지 않았으므로 세 커밋을 착수의 적극 근거로 쓰지 않는다** — 착수를 막지 않는 이유는 셋 다 **하네스 자기 검사 테스트 1파일**(`tests/test_parity_ci_gate.py`)이고 제품 코드에 닿지 않기 때문이다. 리뷰는 **Phase 3 첫 리뷰 게이트 범위에 포함**한다(「게이트 19 후속」 §1) |

**열린 것 전부(§6-a)의 성질.** 남아 있는 것은 두 종류뿐이다 — ⑴ **하네스 자기 검사 장치**
(게이트 18·19 잔여 · 러너 잔여 · 완전성 장치 잔여. 전부 리더 결정 ⑴⑵⑶ 으로 동결·부분 해소 표기·한계
등재 처분됐고 **제품 코드 지적은 두 회차 연속 0**이다) · ⑵ **마감이 Phase 3 종료 또는 Phase 4 로 명시된
항목**(L348 · 게이트 14 미해소 6갈래 · X6 강제자 축 · Flyway 4건 · 모듈 경계 2건).
**착수 시점을 마감으로 갖는 열린 항목은 없다.** 「사용자 판단 대기 11건」은 전부 심각도·처방·절차
라벨이며 각 행이 스스로 「차단 아님」을 적고 있다.

### §2 — 리더 판정과 승인 대기

**리더 판정: Phase 3 착수 허용 권고.** 착수 조건 8행이 전건 충족이고(⑧ 은 단서 병기), 열린 것 중
착수 시점을 마감으로 갖는 항목이 없다.

**단 아직 착수하지 않는다 — 사용자 승인 대기.** 하네스 규칙이 그렇게 정한다: **「이전 Phase 의 종료
조건 행이 전부 `충족 = 예` 가 아니면 다음 Phase 에이전트를 호출하지 않는다. 미충족인데 진행해야 할
사정이 있으면 사용자 승인을 받고, 승인 사실과 미충족 행을 `00_progress.md` 에 남긴다」**
(`.claude/skills/kotlin-migration/SKILL.md` 「Phase 의존성 강제」 — **그 절이 정본이다**).

**미충족 행은 하나다** — Phase 2 종료 조건 표의 **L348 「내보내기 파일명·`Content-Disposition` 생성
포팅」 = `아니오`**(export 파일명 축 F-3·F-4, 마감 **Phase 4 착수 전**). 게이트 14 R-1 이 같은 자리를
겨눈다. **이 행은 Phase 3 의 대상 코드(JDBC repository·인증 API·계약 테스트)에 닿지 않는다.**

> **승인 시 이 절에 남길 것** — ⑴ **승인 사실**(일자·승인자) ⑵ **미충족 행 = L348**(위 문단)
> ⑶ 승인 시점의 HEAD 해시. **세 가지를 적은 뒤에 Phase 3 에이전트를 호출한다.**
> 승인 없이는 호출하지 않는다 — 규칙이 요구하는 것은 판정이 아니라 **승인 기록**이다.

**승인 기록 (2026-08-19).** ⑴ **사용자가 "Phase 3 착수"로 승인**했다(2026-08-19, 사용자 harris.lee).
⑵ **미충족 행 = Phase 2 L348**(내보내기 파일명·`Content-Disposition` 포팅, `아니오`, 마감 Phase 4 착수 전 —
Phase 3 대상 코드에 닿지 않는다). ⑶ **승인 시점 HEAD = `b3db059`**(원장 갱신 커밋; 코드 HEAD 는 `e7f9bdb`).
이 기록 뒤에 Phase 3 에이전트를 호출한다. 첫 작업 단위는 §4 지침대로 **`auth`**(어간 `03_auth`) — 사용자
repository + Argon2 + JWT + 인증 엔드포인트 + 계약 테스트(OQ-3 직접 파싱). **첫 리뷰 게이트 범위에
`e91ecdd`·`e600861`·`e7f9bdb` 를 포함**한다(§4 지침 9).

### §3 — 리뷰어 조건 3건의 수용 (게이트 19 cross §7.1)

> 두 산출물 중 **「별도 게이트를 열지 말라」는 절차 자체를 반대하는 문장은 없었고**, 반대 근거로
> 읽히는 셋은 전부 **순서·조건**이었다. 셋을 어떻게 수용했는지 한 줄씩 적는다.

| 조건 | 출처 | **수용 방식** |
|---|---|---|
| **⑴ 6항목 마감(「Phase 3 착수 전」) 존중** — "게이트가 근거로 쓰이기 전에 게이트를 믿을 수 있어야 한다" | Claude 1차 §5.2 (cross §7.1 가) | **수용 — 마감을 실행 시점에 결속시켰다.** 리더 결정 ⑴ 이 러너·완전성 장치의 잔여 마감을 **「러너 실사용(CI 배선) 전」**으로 통일했고, 그 장치들은 **지금 실사용 0 · CI 배선 0** 이다. 즉 「믿을 수 있어야 하는 시점」이 아직 오지 않았고, **그 시점 전까지 닫는다**는 원 요구가 그대로 유지된다. 착수 자체를 막지 않는 이유는 마감 기준이 **날짜가 아니라 「첫 실사용」**이기 때문이다 |
| **⑵ 리더 선결정 — 「먼저 리더가 결정하고」** | codex X19-1 Rec. · X19-4 Rec. · Next steps 1 (cross §7.1 나) | **수용 — 결정을 먼저 냈다.** 「게이트 19 후속」 §2 의 **리더 결정 3건**(⑴ 러너 동결 · ⑵ 부분 해소 표기 · ⑶ 한계 등재)이 codex 가 선행을 요구한 그 결정이며, **이 원장 갱신이 그 기록이다.** 충돌 ⑤ 자체(제약 위반 여부)는 결과를 바꾸지 않으므로 **사용자 판단 대기로 올린다**(「게이트 19 후속」 §3 ⑪) |
| **⑶ 완전 종결 표기 금지 — 「독립 강제자가 승인될 때까지 X1/X2a/T1 을 완전 종결로 기록하지 않는다」** | codex X19-3 Rec. (cross §7.1 다) | **수용 — 표기를 보수적인 쪽으로 고정했다.** 리더 결정 ⑵ 가 세 항목을 **「부분 해소」**로 적고, 「게이트 16 후속」 §6-a 의 해당 행도 그렇게 고쳤다. **⑧(외부 강제자 0)은 「열림」으로 남는다** — 리더 결정 ⑶ 이 그것을 **한계로 등재**했지 해소로 적지 않았다 |

### §4 — Phase 3 첫 작업 지침 요약 (승인 시 그대로 내려보낸다)

> **요약이고 정본이 아니다.** 각 항목의 근거·전문은 표시된 절과 산출물에 있으며 **값을 여기로 옮겨
> 적지 않는다**(전사하면 갈린다 — 이 하네스가 이미 겪은 실패). 이미 확정된 지침만 싣는다.

| # | 지침 | 정본 |
|---|---|---|
| **1** | **인증** — Argon2 는 **전체 파라미터 동등성**(단일 값이 아니라 파라미터 집합 전건)으로 판정하고, **JWT 는 skew 0** 으로 검증한다 | `migration-safety-gate` I-8·I-9 (암호·인증 정본) |
| **2** | **F3 다섯 필드에 `@Size` 를 쓰지 않는다** — **서비스 층에서 정규화한 뒤 판정**한다(DTO 애너테이션은 원시값을 재므로 F3 계약의 `measured_on` 축과 어긋난다) | 「게이트 15 후속」 §3 ② · 「게이트 16 후속」 §2 H·J·W |
| **3** | **`limit`/`offset` 은 Bean Validation 으로** 상한·하한을 강제한다 | `api-contract-freeze` 입력 상한 |
| **4** | **OQ-3 — 계약 파일을 직접 파싱**한다(산문 대응표가 아니라 `contracts/easy-doc-v1.yaml` 자체를 읽는 파서. 현재 파서 **0건**이 X15 의 내용이다) | 「게이트 15 후속」 §5 X15 (마감 **Phase 3 첫 계약 테스트**) |
| **5** | **G-1 — 동일 단위로 잰다**(Python 기준선에도 없던 공백. 값 비교가 아니라 단위 일치가 요구다) | `00_contract-keeper_test-plan.md` §5 |
| **6** | **H-2 — 헤더 중복 부착 금지.** 필터는 `add` 가 아니라 `set` 을 쓰고, 단언은 값이 아니라 **개수까지** 본다(`header().stringValues(...)`) | 「Phase 1 종료 판정」 §미결 원장 H-2 |
| **7** | **H-4 — 오류 본문이 경로를 가리지 않게 한다.** `sendError` → `/error` 가 Spring 기본 본문을 내면 계약의 「최상위 `detail` 하나」 불변식이 깨진다 | 같은 표 H-4 |
| **8** | **`handleHandlerMethodValidationException`** 을 명시 처리한다(그러지 않으면 검증 실패가 `{"detail":…}` 계약 밖으로 샌다) | 같은 표 · `api-contract-freeze` 오류 본문 조항 |
| **9** | **첫 리뷰 게이트 범위에 `e91ecdd` + `e600861` + `e7f9bdb` 를 포함**한다 — 리뷰 미수령 **3 커밋**을 다음 게이트가 반드시 본다 | 「게이트 19 후속」 §1 말미 |

## 아직 돌리지 않은 검증 게이트 (계획 §6)

> `실행 경로` 열의 어휘 정본은 위 Phase 0 표의 포인터를 따른다.

| 게이트 | 실행 경로 | 상태 |
|---| --- |---|
| Build (Gradle, TypeScript) | `ci:kotlin` · `ci:frontend` | **Gradle 실행됨** (Phase 1) — `./gradlew clean build` BUILD SUCCESSFUL (컴파일 + ktlintCheck + detekt + test). TypeScript 는 기존 `frontend` 잡이 그대로 담당 |
| Unit (core, application, React) | `ci:kotlin` · `ci:frontend` | **부분 실행** (Phase 1) — core 19건(모듈 경계 7 · Secret 7 · parity 하네스 5) 통과. **도메인 로직은 아직 없다**(마스킹·스타일 규칙 등은 Phase 2). `application` 은 본 소스가 없어 테스트도 없다. React 는 기존 `frontend` 잡 |
| Contract (14 endpoints) | `안 돎` | 미실행 — 계약 파일은 **작성됨**(`contracts/easy-doc-v1.yaml`)이나 contract test 미구현. 실행은 Kotlin API가 생기는 Phase 3부터 (`00_contract-keeper_test-plan.md` §5) |
| DB (Testcontainers) | `ci:kotlin` | **실행됨** (Phase 1) — pgvector/pgvector:pg16 컨테이너로 8건. V1↔Alembic 지문 대조, V2 additive, baseline 가드 4갈래, 빈 DB·기존 스냅샷 기동. **repository·트랜잭션·SKIP LOCKED 는 아직 없다**(Phase 3·5) |
| Crypto (Python ↔ Kotlin) | `안 돎` | 미실행 — fixture **생성기**가 11개 도메인을 지원할 뿐, **`parity/fixtures/` 산출물은 저장소에 존재하지 않는다**(`parity/` 디렉터리 자체가 없음). Kotlin 측도 부재 |
| Document (docx/pdf/hwpx/txt) | `안 돎` | 미실행 |
| Worker (lease/retry/crash) | `안 돎` | 미실행 |
| Quality (골든셋) | `ci:llm-lane(조건:.github/llm-lane-paths.txt)` | 미실행 |
| Security (소유권·로그·캐시) | `ci:kotlin` | 미실행 |
| E2E (compose + browser) | `1회성:docs/migration/_workspace/01_kotlin-implementer_skeleton.md` | **compose 부분 실행** (Phase 1) — Kotlin api·worker·migrate 3서비스가 Python 스택과 동시 기동, `/health` 200 확인. **browser·업무 흐름은 미실행**(Phase 6) |
| Ops (cutover/rollback) | `안 돎` | 미실행 |

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
