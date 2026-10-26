# Production Start 유료 결제 출시 설계

- 작성일: 2026-10-26
- 대상: easy-doc의 Production 첫 유료 판매
- 확정 상품: Start 월 정기결제, 월 99,000원(KRW), 월 50크레딧
- 문서 성격: 승인된 구현 설계이며 운영 배포나 실결제 실행 기록이 아니다.

## 문제와 현재 상태

현재 코드에는 Start의 Toss 결제 흐름이 이미 상당히 구현되어 있다. 서버의 `SubscriptionPlanCatalog`는 Start 한 플랜을 월 99,000원·50크레딧으로 정의하고, 카드 등록부터 최초 승인, 구독·크레딧 활성화, 갱신, 환불과 복구까지 기존 흐름이 존재한다. 이번 작업은 이 결제 엔진을 다시 만드는 작업이 아니다.

사용자 `/usage`와 관리자 작업공간 화면은 같은 `SubscriptionCard`를 사용한다. 이 컴포넌트는 서버가 반환한 `mock_enabled`, `toss_enabled`, `billing_environment`, `purchase_enabled`로 `billingAvailable`을 계산하며, 활성 구독이 없고 `billingAvailable=false`이면 **“구독 서비스 준비 중”**을 표시한다. 따라서 실제 Production 판매가 가능필 때도 심사용 설정이 남아 있으면 사용자와 관리자가 모두 준비 중 화면을 보게 된다.

현재 서버는 전역 구매 설정뿐 아니라 `EASYDOC_PAYMENT_ALLOWED_WORKSPACES` 기반 심사 작업공간 허용 목록을 구매 시작·승인 및 구독 개요의 구매 가능 값에 반영한다. Production 첫 출시에서는 이 심사용 제한을 판매 조건에서 제거해야 한다.

환경 경계도 현재보다 명확해야 한다. 로컬 Compose는 `api,local`과 `worker,local`을 사용하지만 공개 배포용 `compose.pilot.yml`도 같은 `local` 프로필을 사용한다. 현재 설정은 `stub`, `toss_test`, `toss_live`를 지원하고 키 접두사와 mock의 일부 조합을 검사하지만, Production은 반드시 `toss_live`와 HTTPS 공개 URL을 사용하도록 강제하는 별도 시작 검증이 필요하다.

공개 문구에는 아직 유료 적용일을 별도 공지로 확정한다는 출시 전 표현이 남아 있다. 실제 Start 판매를 여는 같은 릴리스에서 약관, 동의 문구, 이용 안내의 상품 조건과 적용 시점을 일치시켜야 한다.

## 목표/비목표

### 목표

1. Production에서 로그인한 모든 사용자가 자신의 작업공간에서 Start 월 정기결제를 구매할 수 있게 한다.
2. 판매 상품을 Start 월 99,000원·50크레딧 하나로 고정하고 Basic과 Pro는 판매하지 않는다.
3. Production 심사 작업공간 허용 목록을 판매 가능 조건에서 제거하고, 구매 가능 여부를 서버가 일관되게 결정하게 한다.
4. 로컬 개발과 Production의 Spring 프로필 및 시작 검증을 명시적으로 분리한다.
5. 기존 Toss 결제·복구·환불 흐름과 HTTP 스키마를 유지하면서 판매 범위, 환경 안전성, 화면과 법적 고지를 출시 상태에 맞춘다.
6. PR 리뷰와 운영자 승인을 통과한 실제 릴리스에서만 유료 판매가 활성화되도록 배포 게이트를 정의한다.

### 비목표

- Basic 또는 Pro의 판매, 판매 일정 약속, 새 요금제 모델 도입
- 관리자별·작업공간별 판매 토글 또는 새로운 판매 권한 관리 기능
- 새 outbox, 새 결제 아키텍처, 결제 엔진 또는 불필요한 API 재작성
- 이번 PR에서의 실제 Production 배포, 운영 환경 설정 변경, 라이브 키 주입
- 실제 유료 결제, 자동 갱신 청구, 환불 등 실거래 실행
- 승인된 범위를 벗어난 상품, 할인, 연간 계약, 플랜 변경 또는 크레딧 충전 기능

## 확정 요구사항

1. **판매 상품은 Start뿐이다.** 가격은 월 **KRW 99,000원**, 제공량은 월 **50크레딧**이다. 기존 `start` 식별자와 서버 플랜 카탈로그를 사용하며 새 플랜 모델을 만들지 않는다.
2. **판매 대상은 로그인한 모든 사용자다.** 사용자가 소유한 작업공간에 대한 기존 인증·소유권 검사는 유지하되, Production의 심사 작업공간 허용 목록이 구매 시작, 승인, 구독 개요의 판매 가능 여부를 제한하지 않는다.
3. **Production 신규 구매와 자동 갱신은 릴리스 시 활성화된다.** 기존 구매·자동 청구 설정을 사용하며, Production 판매 상태는 `purchaseEnabled=true`, `autoChargeEnabled=true`다. 운영 장애 시에는 같은 기존 설정으로 신규 구매와 자동 갱신을 끌 수 있어야 한다.
4. **Production에서는 Start만 판매 카탈로그와 결제 선택지에 표시한다.** Basic과 Pro는 Production 화면에서 판매 대상으로 노출하지 않으며, 이용 가이드와 법적 고지에서도 출시 예정 상품처럼 약속하지 않는다.
5. **로컬 기본 결제 제공자는 `stub`다.** 로컬 기본값은 실제 청구가 없는 준비 상태이며, 기존 명시적 mock 및 `toss_test` 개발·회귀 경로는 유지한다.
6. **Production 결제 제공자는 `toss_live`뿐이다.** `stub`, `toss_test`, mock 결제, 테스트 키, 실패 시뮬레이션을 Production에서 허용하지 않는다.
7. **API와 worker는 같은 결제 환경을 사용한다.** 공개 배포 Compose는 두 프로세스와 일회성 worker 프로필 포함 설정에 `local` 대신 `production`을 적용한다.
8. **기존 HTTP 스키마가 유지되는 한 계약을 변경하지 않는다.** 기존 구독 개요 필드와 Toss 세션·콜백 계약으로 판매 상태를 표현하고 새 필드나 경로를 추가하지 않는다.
9. **실제 Production 전개와 실거래 검증은 별도 운영 게이트다.** PR 리뷰 후 운영자가 승인하고 수행하며, 에이전트는 실제 결제나 환불을 실행하지 않는다.

## 환경 매트릭스와 시작 검증

### 확정 환경

| 환경 | Spring 프로필 | 결제 제공자 | 키와 mock | 공개 URL과 콜백 | 판매 결과 |
|---|---|---|---|---|---|
| 이 개발 머신의 로컬 기본값 | `api,local`, `worker,local` | `stub` | 실제 청구 없음. 기본은 mock 비활성 준비 상태이며 명시적 로컬 mock 경로는 회귀 테스트용으로 유지 | 로컬 HTTP 허용 | 실제 판매 없음. 서버가 이용 불가를 반환하면 준비 중 문구 표시 |
| 로컬 선택값 | `local` 또는 자동화 `test` | `toss_test` | 유효한 `test_ck_`·`test_sk_` 테스트 키 쌍만 허용. 라이브 키와 혼합 금지 | 개발용 콜백 허용 | Toss 테스트 흐름만 수행하며 실결제 아님 |
| 자동화 테스트 | `test` | `stub` 또는 `toss_test` | stub/mock 회귀와 테스트 키 경로 허용. `toss_live` 및 실제 라이브 과금 거부 | 테스트 전용 값 | 실제 판매 없음 |
| Production | `api,production`, `worker,production` | `toss_live`만 허용 | 유효한 `live_ck_`·`live_sk_` 라이브 키 쌍 필수. `mockEnabled=false` 필수 | `EASYDOC_APP_PUBLIC_BASE_URL`은 HTTPS 공개 URL 필수. 여기서 생성하는 `/api/payments/toss/billing/callback`도 HTTPS 공개 URL이어야 함 | Start만 모든 로그인 사용자에게 판매. 신규 구매·자동 갱신 활성 |

Production의 라이브 MID와 키 쌍은 운영자가 Toss 상점 계약 및 개발자센터에서 확인한 같은 라이브 환경의 값이어야 한다. 서버 시작 검증은 필수값 존재, 라이브 키 형식, 환경별 조합을 확인하며, 키의 상점 유효성 확인을 이유로 시작 시 실제 결제 호출을 수행하지 않는다.

### 시작 검증 순서와 거부 조건

1. 공통 설정 검증은 API와 worker가 각각 시작할 때 적용한다. HTTP 요청 접수, worker 작업 수행 또는 Toss 네트워크 호출보다 먼저 실패해야 한다.
2. `local` 또는 `test`에서 `provider=toss_live`이면 즉시 시작을 실패시킨다. 로컬에서 라이브 키를 넣거나 라이브 과금 조합을 만들어도 이를 우회 경로로 허용하지 않으며, Toss gateway 호출은 0회여야 한다.
3. `production`에서 `provider`가 `toss_live`가 아니면 시작을 실패시킨다. `stub`, `toss_test`, 테스트 키, mock 활성화를 각각 거부한다.
4. `production`에서 라이브 클라이언트 키 또는 시크릿 키가 비어 있거나 라이브 형식이 아니면 시작을 실패시킨다. 테스트·라이브 키 혼합도 거부한다.
5. `production`에서 공개 기준 URL이 없거나 HTTPS가 아니거나 로컬호스트 주소이면 시작을 실패시킨다. 기존 공개 기준 URL로 생성되는 카드 등록 콜백과 복귀 URL이 같은 HTTPS 공개 출처를 사용하는지 검증한다.
6. 설정 오류는 프로필, 제공자, 설정 항목명과 위반 사유만 알려 주는 정제된 오류로 실패시킨다. 키 원문, Secret 내용, 콜백의 인증 토큰, 환경변수 전체 값은 오류와 로그에 포함하지 않는다.
7. `compose.yml`의 로컬 실행은 `local`을 유지한다. 공개 배포에 사용하는 `compose.yml + compose.pilot.yml` 조합은 API·worker·일회성 worker 프로필 포함 설정이 모두 `production`을 사용해야 하며 `local`을 상속하지 않는다.

비밀값은 서버의 `Secret` 경계 안에서만 다룬다. 설계·구현·검증 과정에서 `.env`를 읽거나 복사하지 않고, 키를 문서·테스트 출력·커밋·로그에 기록하지 않는다.

## 판매 상태와 UI

### 서버 판매 상태

- Production의 유효한 라이브 설정에서는 서버가 모든 로그인 사용자의 Start 구매 가능 상태를 동일하게 반환한다. 이전 심사 허용 목록에 없던 작업공간도 같은 결과를 받는다.
- `EASYDOC_PAYMENT_ALLOWED_WORKSPACES`는 Production 판매 여부를 좁히거나 넓히는 입력으로 사용하지 않는다. 구매 시작과 승인 완료도 같은 정책을 적용해 화면은 열었는데 서버 완료만 거부되는 불일치를 만들지 않는다.
- 기존 구독, 결제 내역, 미확정 상태, 수동 확인 상태의 표현은 유지한다. 판매 개방이 기존 구독의 제공량이나 결제 상태를 다시 계산하지는 않는다.
- 새 API 가용성 필드를 추가하지 않고 기존 구독 개요의 서버 값으로 화면 상태를 결정한다.

### 사용자 `/usage`와 관리자 화면

- 두 화면은 계속 같은 서버 구독 개요와 `SubscriptionCard`의 가용성 판단을 사용한다. 관리자 화면에만 별도의 준비 중 상태나 판매 토글을 두지 않는다.
- Production에서 Start를 이용할 수 있고 활성 구독이 없으면 **“구독 서비스 준비 중”** 대신 선택된 플랜이 없다는 상태와 Start 구매 경로를 표시한다. 관리자 화면에는 같은 가용성을 반영하되 기존처럼 사용자 결제 버튼은 제공하지 않는다.
- **“구독 서비스 준비 중”**과 신규 구독 접수 준비 문구는 서버가 실제로 신규 결제를 제공하지 않을 때만 표시한다. 로컬 기본 `stub` 준비 상태가 대표적인 예다. Production의 잘못된 결제 설정을 이 문구로 숨기지 않고 시작 검증에서 실패시킨다.
- Production 판매 카탈로그에는 Start만 보이고 **“Start 월 99,000원 정기결제 시작”** 경로만 제공한다. Basic·Pro 카드, 가격, 선택 버튼과 향후 판매 암시는 표시하지 않는다.
- 로컬 stub과 Toss 테스트 화면에서는 실제 금액이 청구되지 않는다는 기존 안내를 유지한다. Production 라이브 화면에는 그 테스트 전용 문구를 표시하지 않는다.
- 동의 체크, 처리 중, 승인 확인 중, 수동 확인, 재시도, 카드 변경, 갱신 중단, 결제 내역과 영수증 진입의 기존 상호작용을 유지한다.

## 기존 결제 흐름 보존

다음 기존 흐름을 그대로 유지하고, 변경은 Production 판매 자격과 환경 검증에 필요한 경계로 제한한다.

1. Start 플랜 조회와 서버 측 가격·제공량 결정
2. 유료 조건 동의와 동의 기록
3. Toss 카드 등록 세션 생성, 공개 콜백 수신, 인증된 복귀·재개
4. 빌링키 발급과 환경 일치 검증
5. 최초 99,000원 청구와 서버 측 승인 결과 검증
6. 하나의 트랜잭션에서 구독 활성화와 50크레딧 지급
7. 결제 내역과 카드 영수증(매출전표) 제공
8. 월 갱신, 확정 거절 후 기존 재시도 일정과 중단 처리
9. 구독 갱신 중단과 이미 결제한 이용 기간 유지
10. 카드 교체, 기존 카드 보호, 삭제·재처리
11. 부분·전체 환불, 환불 확인, 크레딧 회수 또는 확보 해제
12. 웹훅 수신 후 서버 재조회, 중복 이벤트 처리, 미확정 주문의 수동 확인
13. 결제 알림과 운영자의 기존 관리·복구 경로

콜백이나 웹훅의 도착 자체를 승인 사실로 신뢰하지 않는다. 기존처럼 주문, 금액, 환경, 승인 시각과 공급자 조회 결과를 확인한 뒤에만 성공을 확정한다. 새 outbox나 새 결제 상태 기계를 도입하지 않고, 기존 트랜잭션·잠금·재조회·복구 경계를 변경하지 않는 것을 원칙으로 한다.

## 오류/보안

- **승인 전 성공 금지:** Toss 승인이 서버에서 확정되기 전에는 결제 성공 화면, 활성 구독, 이용 기간 연장, 크레딧 지급을 먼저 만들지 않는다.
- **중복 안전:** 콜백, 콜백 재개, 웹훅, 최초 청구 재시도, 갱신 재시도, 환불 재시도는 기존 주문·작업 키와 잠금으로 멱등 처리한다. 같은 승인으로 구독이나 크레딧을 두 번 활성화하지 않는다.
- **불확실한 결과:** 타임아웃이나 응답 손실로 승인·환불 결과를 확정할 수 없으면 새 주문을 만들지 않고 기존 주문을 재조회한다. 자동으로 확정할 수 없으면 `manual_review`로 유지하고 운영자가 공급자 거래와 원장을 대조한다.
- **환경 격리:** 저장된 세션·주문·카드·웹훅의 결제 환경과 현재 프로세스 환경이 다륩으면 공급자 작업을 수행하지 않는다. 테스트 기록을 라이브로 승격하지 않는다.
- **비밀 보호:** 클라이언트 키와 시크릿 키는 서버 `Secret`으로 유지하고 `toString`, 설정 오류, 예외, 로그에서 마스킹한다. 문서와 테스트에는 실제 키를 넣지 않는다.
- **공개 경로:** Production의 공개 기준 URL과 카드 등록 콜백은 HTTPS를 강제한다. 콜백의 state·resume 같은 인증 값은 로그나 설정 진단에 출력하지 않는다.
- **실패 폐쇄:** 설정이 불완전하거나 환경이 모순되면 판매를 제한적으로 여는 대신 시작을 거부한다. Production에서 mock이나 stub으로 대체 기동하지 않는다.

## 약관/고지

1. Start 결제 화면의 동의 문구, 이용약관, 이용 가이드, 개인정보처리방침의 관련 고지는 같은 릴리스에서 함께 갱신한다.
2. 모든 유료 조건은 **월 KRW 99,000원, 월 50크레딧, 실제 승인일부터 한 달 이용, 등록 카드로 매월 자동 결제**라는 동일한 내용을 사용한다.
3. 갱신 중단 시 다음 결제가 중단되고 이미 결제한 이용 기간은 유지된다는 조건을 일치시킨다. 환불은 적용 법령, 공개 약관, 결제·이용 내역에 따라 고객 지원에서 확인한다는 기존 기준을 유지하며 임의의 환불액이나 보장을 새로 만들지 않는다.
4. 현재 약관의 **“유료 서비스 적용일: 출시 전 공고로 확정”**, **“확정 전에는 신규 실결제를 받지 않습니다”** 등 별도 공지 후 유료 시작을 전제로 한 문구는 실제 판매를 여는 같은 릴리스에서 교체한다. 실제 릴리스 일자를 유료 서비스 적용일로 명시하고, 약관 버전과 관련 고지도 그 릴리스의 상태를 가리키게 한다.
5. 기존 동의 기록 구조는 유지한다. 사용자가 동의하는 화면 문구와 해당 시점의 약관이 같은 Start 조건을 나타내야 하며, 동의 없는 라이브 구매를 허용하지 않는다.
6. Basic·Pro는 판매 범위 밖임을 명확히 하고, 가격·제공량·출시 시점을 약속하는 표현을 만들지 않는다.
7. 로컬 stub·테스트 결제의 무청구 안내와 Production 실결제 안내를 혼용하지 않는다. 실제 라이브 승인 후에는 카드 영수증과 결제 내역을 기존 방식으로 안내한다.

## 테스트

구현 PR에서는 실제 라이브 결제 없이 다음 검증을 완료한다.

1. **로컬·test 라이브 거부:** `local`과 `test` 각각에서 `toss_live` 시작이 실패하고 Toss 네트워크 gateway 호출이 0회인지 확인한다. 라이브 키 사용 및 환경 불일치 조합도 같은 방식으로 거부한다.
2. **Production 필수 설정:** `toss_live`, 라이브 키 쌍, HTTPS 공개 기준·콜백 URL, mock 비활성 조합은 시작 검증을 통과한다. 제공자 누락, `stub`, `toss_test`, 테스트 키, 키 누락·혼합, mock 활성, HTTP·로컬호스트 URL은 각각 실패한다. 오류 출력에 비밀 원문이 없는지 확인한다.
3. **모든 사용자 Start 판매:** 이전 심사 허용 목록에 속하지 않은 서로 다른 작업공간도 Production에서 Start 구매 가능 응답과 구매 시작·승인 자격을 받는다. 허용 목록 값이 Production 결과를 바꾸지 않음을 확인한다.
4. **Start 단일 판매:** Production 화면과 서버 플랜 응답의 실제 판매 대상이 Start 하나이며 가격 99,000원과 50크레딧이 일치한다. Basic·Pro 구매 시도는 새 경로가 생기지 않고 기존 서버 플랜 검증에서 거부된다.
5. **로컬 회귀:** 기본 `stub` 준비 상태, 명시적 stub/mock 결제, `toss_test` 카드 등록과 무청구 안내가 기존처럼 동작한다.
6. **결제 회귀:** 동의, 카드 등록, 콜백 캡처·재개, 빌링키 발급, 최초 승인, 서버 검증, 구독·크레딧의 원자적 활성화, 영수증, 갱신, 재시도, 갱신 중단, 카드 교체를 기존 테스트로 확인한다.
7. **복구·환불 회귀:** 중복 콜백·웹훅·재시도의 멱등성, 승인·환불 미확정 시 재조회와 `manual_review`, 부분·전체 환불, 크레딧 회수·확보 해제, 관리자 복구 흐름을 확인한다.
8. **사용자·관리자 문구:** Production 가용 상태의 `/usage`와 관리자 화면에 준비 중 문구가 나오지 않는다. 로컬 기본 `stub` 등 실제 불가 상태에서는 두 화면에 준비 중 문구가 나온다. 관리자에게 사용자 구매 버튼이 생기지 않는다.
9. **약관·고지 회귀:** Start 가격, 제공량, 자동 갱신, 갱신 중단, 환불 안내와 적용일이 화면·약관·가이드에서 일치하며, 별도 공지 후 유료 시작이라는 이전 문구와 Basic·Pro 판매 약속이 없다.
10. **계약 확인:** 기존 HTTP 스키마를 그대로 사용하고 계약 변경이 없음을 최종 diff에서 확인한다. 스키마 변경 없이 계약 파일, DTO 경계, `frontend/src/api/`를 새로 바꾸지 않는다.

필수 검증 명령은 다음과 같다.

```bash
cd backend-kotlin && ./gradlew build
cd frontend && npm run check && npm run test -- --run && npm run build
docker compose config
docker compose -f compose.yml -f compose.pilot.yml config --quiet
```

Compose 검증에서는 로컬 조합이 `local`, 공개 배포 조합이 `production`을 사용하는지 API·worker·일회성 worker 포함 프로필까지 확인한다. 검증용 키는 테스트 전용 값을 사용하고 실제 `.env`나 라이브 키는 읽지 않는다.

## 배포/운영 게이트

### 코드 릴리스 게이트

1. 구현, 테스트, 약관·고지, Compose 변경이 하나의 PR에서 함께 리뷰된다.
2. PR 리뷰 승인과 운영자의 실제 출시 승인이 모두 있은 뒤에만 Production 릴리스를 수행한다.
3. 실제 릴리스가 이루어지면 별도 후속 코드 활성화 없이 Production의 Start 판매가 즉시 열린다. PR 작성이나 병합 사실만으로 운영 반영이 완료된 것으로 보지 않는다.
4. 이번 설계와 후속 구현 PR에는 운영 배포, 운영 `.env` 변경, 라이브 키 주입, Toss 상점 설정 변경, 실제 결제·환불이 포함되지 않는다. 이 작업들은 승인된 운영자가 별도 출시 절차에서 수행한다.

### 운영자 출시 체크리스트

- [ ] Production API·worker·일회성 worker 프로필이 `production`이며 공개 배포 Compose가 `local`을 포함하지 않는다.
- [ ] Toss 개발자센터에서 실제 상점의 라이브 MID와 클라이언트·시크릿 키 쌍이 같은 상점·라이브 환경에 속함을 확인했다.
- [ ] 라이브 키를 값 노출 없이 서버 Secret 경로로 주입했고, Production 시작 검증이 통과했다.
- [ ] `PAYMENT_STATUS_CHANGED`와 `BILLING_DELETED` 웹훅이 실제 라이브 상점의 공개 경로에 등록되고 공급자 전달이 확인됐다.
- [ ] 공개 기준 URL과 `/api/payments/toss/billing/callback`이 HTTPS로 외부에서 도달하며, 인증 토큰이 로그에 남지 않는다.
- [ ] 신규 구매와 자동 갱신이 활성 상태이며 Start 99,000원·50크레딧 외 판매 경로가 없다.
- [ ] 약관 적용일·버전·동의 문구·이용 안내가 실제 릴리스와 일치한다.
- [ ] SMTP 발신 설정과 실제 수신을 확인했다. 테스트 메일은 결제 성공이나 실거래 알림의 증거로 사용하지 않는다.
- [ ] 미확정 주문, 수동 확인, 알림 실패를 담당할 운영자와 대응 경로를 확인했다.

### 최초 실거래와 장애 대응

최초 실결제와 환불은 PR 리뷰와 운영자 승인 후 운영자가 수행한다. 에이전트는 이를 실행하지 않는다.

- [ ] 운영자가 Start 99,000원 실결제 1건을 수행하고 Toss 승인, 서버 승인 시각, 구독 기간, 50크레딧 지급, 원장, 카드 영수증, 알림을 대조한다.
- [ ] 운영자가 같은 거래의 승인된 환불 절차를 수행하고 Toss 환불 결과, 서버 환불 기록, 크레딧 회수·확보 해제, 다음 갱신 중단 여부, 알림을 대조한다.
- [ ] 첫 갱신 전까지 미확정 주문·재시도·알림 상태를 관찰하고, 결과가 불명확하면 추가 청구 없이 기존 주문을 조회한다.

장애 시에는 기존 `purchaseEnabled`와 `autoChargeEnabled`를 각각 `false`로 바꿔 신규 구매와 새 자동 갱신을 중단한다. 기존 승인 기간, 승인·환불 조회, 환불, 카드 삭제와 수동 복구 경로는 유지한다. 라이브 장애를 피하기 위해 `stub`이나 `toss_test`로 전환하지 않는다. 롤백은 라이브 거래와 현재 DB 마이그레이션에 호환되는 수정 릴리스로 수행하고, 테스트 전용 이미지나 이전 비호환 이미지로 되돌리지 않는다.

## 영향 파일 후보(후보임을 명시)

아래는 현재 코드에서 확인한 **변경 후보**다. 구현 시 최소 diff 원칙에 따라 실제 변경 파일을 확정하며, 모든 후보를 수정해야 한다는 뜻은 아니다. 결제 엔진과 API 계약을 광범위하게 다시 작성하기 위한 목록이 아니다.

| 영역 | 후보 경로 | 변경 성격 |
|---|---|---|
| 공통 결제 설정 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/SubscriptionConfiguration.kt` | `local`·`test`·`production` 제공자, 키, mock 조합의 시작 검증 |
| 공개 URL 검증 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/app/AppProperties.kt` | Production HTTPS 공개 기준 URL 검증 경계 |
| Toss 설정 연결 | `backend-kotlin/infrastructure/src/main/kotlin/kr/easydoc/infrastructure/subscription/TossConfiguration.kt` | API·worker 공통 환경 설정 연결과 Production 허용 목록 정책 제거 |
| 구매 자격 | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/TossBillingService.kt` | Production 구매 시작·승인에서 심사 허용 목록 제거, 기존 결제 흐름 보존 |
| 구독 개요 | `backend-kotlin/application/src/main/kotlin/kr/easydoc/application/subscription/SubscriptionService.kt` | 사용자·관리자가 공유하는 서버 판매 가능 값 일치 |
| API 설정 | `backend-kotlin/api/src/main/resources/application.yml` | 명시적 프로필별 결제·공개 URL 설정 |
| worker 설정 | `backend-kotlin/worker/src/main/resources/application.yml` | API와 동일한 Production 결제 환경 설정 |
| 백엔드 테스트 | `backend-kotlin/infrastructure/src/test/kotlin/kr/easydoc/infrastructure/subscription/PaymentConfigurationTest.kt` | 라이브 거부, Production 필수 설정, 비밀값 미노출 |
| 구매 정책 테스트 | `backend-kotlin/application/src/test/kotlin/kr/easydoc/application/subscription/TossBillingServiceTest.kt` | 모든 사용자 Start 판매와 기존 결제·복구 회귀 |
| 공개 배포 Compose | `compose.pilot.yml` | `api,production`, `worker,production`, 일회성 worker 포함 프로필 전환 |
| 공통 구독 UI | `frontend/src/components/subscription/SubscriptionCard.tsx` | 서버 가용성 기반 준비 문구와 Production Start 판매 상태 |
| 플랜 UI | `frontend/src/components/subscription/TargetPlanCatalog.tsx` | Production Start 단일 노출, 동의·갱신·환불 문구 일치 |
| 약관 | `frontend/src/content/legal/terms-of-service.md` | 실제 유료 적용 상태와 Start 조건, 별도 공지 전제 문구 교체 |
| 개인정보 고지 | `frontend/src/content/legal/privacy-policy.md` | 유료 거래 관련 적용 상태를 같은 릴리스에 맞춤 |
| 이용 안내 | `frontend/src/content/guide/user-guide.md` | Start 단일 판매와 Basic·Pro 비약속 표현 정리 |
| 프런트 테스트 | `frontend/src/components/subscription/SubscriptionCard.test.tsx`, `frontend/src/components/subscription/TargetPlanCatalog.test.tsx`, `frontend/src/pages/UsagePage.test.tsx`, `frontend/src/pages/admin/AdminWorkspacesTab.test.tsx` | 사용자·관리자 가용성, 준비 문구, Start 단일 판매 회귀 |
| 운영 문서 | `docs/pilot-runbook.md` | 라이브 MID·키·웹훅·HTTPS·SMTP, 최초 실거래·환불, 장애 중단·롤백 절차 |

`contracts/easy-doc-v1.yaml`과 `frontend/src/api/`는 스키마가 바뀌지 않는 현재 설계의 변경 대상이 아니다. 기존 Toss 게이트웨이, 콜백 저장소, 웹훅, 환불 저장소와 마이그레이션도 새 아키텍처 없이 유지한다.

## 완료 기준

1. Production에서 로그인한 모든 사용자가 심사 허용 목록과 무관하게 Start 월 99,000원·50크레딧을 구매할 수 있다.
2. Production 판매 화면과 실제 구매 경로에는 Start만 있고 Basic·Pro 판매나 약속이 없다.
3. Production 구매 시작·승인·구독 개요에 심사 작업공간 허용 목록이 영향을 주지 않는다.
4. 사용자 `/usage`와 관리자 화면이 같은 서버 가용성을 사용하며, 실제 불가 상태에서만 준비 중 문구를 표시한다.
5. 로컬 기본 `stub`과 명시적 mock·`toss_test` 경로는 유지되고, `local`·`test`의 `toss_live`는 네트워크 호출 전에 시작이 거부된다.
6. Production은 `toss_live`, 유효한 라이브 키 쌍, mock 비활성, HTTPS 공개·콜백 URL 없이 시작하지 않는다. 공개 배포 Compose의 API·worker·일회성 worker가 `production`을 사용한다.
7. 카드 등록부터 환불·수동 확인까지 기존 결제 흐름과 트랜잭션·멱등성·보안 불변조건이 유지된다.
8. 설정 오류와 로그에 키·Secret·콜백 인증 값이 노출되지 않고, 설계·구현·테스트 과정에서 `.env`를 읽거나 복사하지 않는다.
9. 약관, 동의, 이용 안내가 Start 가격·제공량·자동 갱신·갱신 중단·환불 조건과 실제 적용일에 일치하며 별도 공지 후 유료 시작이라는 이전 표현이 제거된다.
10. 백엔드, 프런트엔드, 로컬·공개 배포 Compose 검증과 지정된 결제 회귀 테스트가 최종 diff에서 통과한다.
11. HTTP 스키마 변경 없이 구현되어 계약과 API 클라이언트 경계가 유지된다.
12. PR 리뷰와 운영자 승인 전에는 Production 변경이 없고, 실제 최초 결제·환불은 승인된 운영자만 수행한다. 실제 Production 전개와 실거래는 이번 PR의 완료 실적으로 주장하지 않는다.
