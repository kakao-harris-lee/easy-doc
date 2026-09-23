# ER-15 — R7 그림 카탈로그(권리·의미·대체텍스트 목록) 구현 보고

작성일: 2026-09-23 · 브랜치 `feat/er-15-r7-illustration-catalog` · 리비전 `7a592a55` + `b188108e`

## 범위

[실행 계획](../plans/2026-09-18-easy-read-delivery-plan.md)의 ER-15 「그림 권리·의미·대체텍스트 목록, 최대 10종 수동 매핑 예시」와 [인수 기준](../plans/2026-09-18-easy-read-validation-release.md) AC-R7-a 「그림 카탈로그 등록 → 이용 권리·출처·대체텍스트 검토 → 검수되지 않은 항목은 선택 불가」를 닫는다. 배치·출력(ER-16, AC-R7-b)은 이 범위 밖이다.

## 설계 결정

- **카탈로그는 저장소 리소스다.** `backend-kotlin/infrastructure/src/main/resources/illustrations/catalog.json`과 SVG 10개. 어드민 등록 화면은 MVP 밖이라 만들지 않았고, 「등록 → 검토」는 카탈로그 파일 편집과 커밋으로 한다. 사전 색인(`easy_dict.index.json`)과 같은 방식이다.
- **그림은 직접 그린 픽토그램이다.** `viewBox 0 0 64 64` 선 그림, 외부 참조·스크립트·래스터 없음, 라이선스 CC0-1.0. 임의 생성 이미지 호출은 명세대로 없다.
- **검수 상태가 게이트다.** 도메인 `Illustration`은 `reviewed`면 검수자·일자가 반드시 있고 `unreviewed`면 둘 다 없다. `IllustrationCatalog.selectable()`은 `reviewed`만 남긴다. 유스케이스는 꺼짐·미존재·미검수를 **같은 404**로 낸다(존재 은닉).
- **최대 10종은 도메인 불변식이다.** `IllustrationCatalog.MAX_ENTRIES = 10`, 초과나 id 중복은 카탈로그 로드 자체가 실패한다. 리소스 로더는 lazy가 아니라 기동 시 즉시 로드·검증하므로 잘못된 카탈로그는 부팅 실패로 드러난다.
- **이미지 엔드포인트는 공개다.** `<img src>`는 Bearer 헤더를 보낼 수 없고 카탈로그는 사용자 데이터가 아니다. `AuthenticatedEndpoints`에 `/illustrations`만 넣고 `/illustrations/{asset_id}/image`는 뺐다 — 인터셉터가 하위 경로를 덮지 않는다는 이 저장소의 성질이 여기서는 필요한 쪽으로 작용한다. 응답에는 `X-Content-Type-Options: nosniff`·`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`·`Content-Disposition: inline`을 붙인다.
- **`Cache-Control`은 `no-store`다.** 지침은 `public, max-age=86400`이었으나 이 저장소는 전역 필터가 모든 응답에 `no-store`를 박고, 계약 테스트가 「같은 헤더 이름은 같은 선언」을 강제한다(다른 값을 선언하면 28개 테스트 클래스가 깨지는 것을 실행 에이전트가 실측). 공개 캐싱은 그 전역 불변식을 다시 설계하는 별도 결정이라 이번엔 따르지 않았다.
- **`IllustrationAssetId`는 value class가 아니다.** `SensitiveToStringReachTest`의 표본 생성기가 클래스패스의 모든 value class 생성자를 임의 문자열로 호출해 `require`가 게이트 밖에서 터지므로, `BusinessNumber`와 같은 일반 class + private 생성자 패턴을 썼다.
- **검수자 표기.** 카탈로그 10건 모두 `reviewed_by: harris.lee`, `reviewed_at: 2026-09-23`. 이 커밋을 검수 행위로 본 오케스트레이터 결정이며, 머지 전에 항목을 `unreviewed`로 돌리면 그 항목은 목록·이미지에서 빠진다.

## 변경 파일

- 계약 `contracts/easy-doc-v1.yaml` 2.44.0: `GET /illustrations`, `GET /illustrations/{asset_id}/image`, 스키마 `IllustrationCatalogResponse`·`Illustration`·`IllustrationPurpose`. 공백뿐인 `asset_id`는 전역 `TypedValueSlotInterceptor`가 422로 끊어 그 코드도 선언했다.
- core `kr.easydoc.core.illustration` — `Illustration`·`IllustrationCatalog`·`IllustrationAssetId`·enum 2종(+테스트).
- application `kr.easydoc.application.illustration` — 포트 `IllustrationCatalogSource`, 유스케이스 `IllustrationsService`(+테스트).
- infrastructure `kr.easydoc.infrastructure.illustration` — `IllustrationsProperties`·`ResourceIllustrationCatalogSource`·`IllustrationsConfiguration`(+테스트, 잘못된 카탈로그 테스트 리소스), 카탈로그 리소스. `DocumentConfiguration.reviewCapabilitiesFor`에 `illustrations` 연결.
- api `kr.easydoc.api.illustration.IllustrationsController`(+DTO, `IllustrationsContractTest`). `AuthenticatedEndpoints`, `AuthSliceBeans`, `SensitiveToStringReachTest`(선언 수 331→336), `GeneratedToStringProbes.INERT_VALUES`, `CorsConfig`·`ContractHeaderDeclarationTest`(CSP 헤더).
- 설정 `api`·`worker` `application.yml`(`EASYDOC_ILLUSTRATIONS_ENABLED`, 기본 false), `compose.e2e.yml`(e2e에서만 true).
- frontend `api/types.ts`·`client.ts`(`getIllustrations`·`illustrationImageUrl`), `components/IllustrationsPanel.tsx`(+테스트), `ReviewEditor.tsx` 게이팅(+테스트), `e2e/illustrations.spec.ts`, `e2e/contract.ts`, `e2e/run-local.sh`.

## 검증

- 백엔드 `./gradlew build` — BUILD SUCCESSFUL. core 765 / application 602 / infrastructure 1,100 / api 740 / worker 63 = 3,270개 테스트, 실패 0. ktlint·detekt·moduleBoundaryCheck 포함.
- 프런트 `npm run check` exit 0, `npm run test -- --run` 59개 파일/765개 테스트 통과, `npm run build` 성공.
- Compose `compose.yml + compose.e2e.yml config` 통과.
- e2e `frontend/e2e/run-local.sh --grep "R7 그림 목록"`(일회용 스택, fake LLM): **1 통과 / 0 실패**. 첫 실행은 카드 안 예문 `<li>`까지 `listitem`으로 세어 20개로 실패했고(화면은 정상 렌더), `img` 수로 세도록 스펙을 고쳐(`b188108e`) 통과했다.
- 유료 provider 호출 0회. 실제 LLM 호출·배포·외부 전송 없음.

## 리뷰 (리비전 `7a592a55`, 저자와 다른 패스, 모두 sonnet)

마이그레이션 변경이 없어 migration-reviewer는 부르지 않았다.

| 레인 | 판정 |
|---|---|
| code-reviewer | BLOCKER 0 · HIGH 0 · MEDIUM 0 · LOW 2 — 머지 가능 |
| privacy-gate | BLOCKER 0 · HIGH 0 · MEDIUM 0 · LOW 0 — 머지 가능(준수 7, 확인 불가 0) |
| contract-keeper | BLOCKER 0 · HIGH 0 · MEDIUM 0 · LOW 0 — 통과(additive 확인, 3면 11개 필드 일치) |

리뷰어가 코드로 확인한 것 중 중요한 것: 인증 인터셉터가 `addPathPatterns` 정확 매칭이라 이미지 하위 경로가 인증 밖이라는 주장이 맞음(`WebMvcConfig`), `asset_id` 패턴이 `/`·`.`을 거부해 경로 탐색이 불가하고 로더가 요청마다 파일을 열지 않음, SVG 10개에 스크립트·외부 참조·`<use>` 0건, `INERT_VALUES` 등록이 타입 한정이라 다른 민감 타입 탐지에 영향 없음, 계약 삭제 줄이 version과 `illustrations` description 추가 2줄뿐.

code-reviewer LOW 2건은 후속 커밋으로 닫았다: ① `ResourceIllustrationCatalogSource.image()`가 캐시한 `ByteArray`를 복사 없이 내보내던 것을 `copyOf()`로 바꾸고 테스트 추가, ② `mappingExamples` 상한(3) 위반 테스트 추가. 조치 후 `./gradlew build` 통과(core 766 / infrastructure 1,101).

리뷰어가 확인 불가로 남긴 것: 빌드·테스트·e2e 실행(세 레인 모두 읽기 전용) — 실행 결과는 위 「검증」의 오케스트레이터·실행 에이전트 기록이다. contract-keeper가 본 시점의 미커밋 e2e 스펙 수정은 `b188108e`로 커밋됐다.

## 남은 한계

- ER-16(그림 배치·미리보기·파일 출력 범위 결정)은 미착수다. 화면은 「본문에 넣기와 파일 출력은 다음 단계에서 지원합니다」로 명시한다.
- 카탈로그 「등록·검토」는 파일 편집과 커밋이지 제품 화면이 아니다. 운영자가 화면에서 등록하려면 어드민 범위가 필요하다.
- 이미지 응답은 `no-store`라 브라우저 캐시를 쓰지 못한다. 10개·각 1KB 미만이라 실질 영향은 작다.
- 이 세션의 Claude Code 환경은 MDM 관리형 프록시 때문에 GitHub에 닿지 못해, 푸시·PR 생성·PR 코멘트는 사용자 터미널에서 실행한다.
