# 토스 테스트 구독 결제

## 계약과 비용

2026-09-13 공식 문서 확인. 전자결제 계약 완료 전에도 회원가입으로 테스트 상점 키를 받을 수 있다. 공개 문서의 연동 체험 키로도 테스트할 수 있다. 테스트 키 결제는 실제 출금되지 않으며, 테스트 연동을 위해 가입비 결제를 먼저 할 필요는 없다. 신청한 상점의 개별 견적·계약 비용이나 심사 완료 여부를 확인한 것은 아니다.

실제 월 자동결제는 일반 결제 신청만으로 보장되지 않는다. 자동결제(빌링)의 추가 리스크 검토와 계약이 필요하다. 현재 구현은 `toss_test`만 허용하며 라이브 키와 production 프로필을 거절한다. 실서비스 요금·환불 정책·정산·상점 심사·라이브 전환은 별도 확정 사항이다.

- [테스트/라이브 환경](https://docs.tosspayments.com/guides/v2/get-started/environment)
- [빌링 연동과 계약 전 테스트](https://docs.tosspayments.com/guides/v2/billing/integration)
- [API 인증과 멱등키](https://docs.tosspayments.com/reference/using-api/authorization)
- [웹훅](https://docs.tosspayments.com/guides/v2/webhook)

## 설정

API와 worker에 같은 설정을 전달한다. `.env`는 버전 관리하지 않는다.

```dotenv
EASYDOC_PAYMENT_PROVIDER=toss_test
EASYDOC_PAYMENT_MOCK_ENABLED=false
EASYDOC_TOSS_CLIENT_KEY=<API 개별 연동 test_ck_ 키>
EASYDOC_TOSS_SECRET_KEY=<같은 상점의 test_sk_ 키>
```

`local` 또는 `test` 프로필이 필요하다. 결제위젯 `test_gck_`/`test_gsk_` 키와 라이브 키는 사용할 수 없다. `provider=stub`은 기존 내부 mock이며 토스를 호출하지 않는다. 공개 체험 키의 테스트 기록은 사용자의 상점 개발자센터에서 확인할 수 없다. 상점별 로그와 웹훅 검증은 본인 상점의 테스트 키로 교체해야 한다. 키 교체 전 진행 중인 주문과 등록 카드를 정리한다. 기존 키로 만든 주문·빌링키는 다른 상점 키로 조회하거나 승인할 수 없다.

## 사용자 흐름과 서버 처리

1. `/usage`에서 Starter(테스트 월 1,000원/50크레딧) 또는 Pro(테스트 월 3,000원/200크레딧)를 선택한다.
2. 이메일 인증을 확인하고 서버가 등록 세션과 무작위 customerKey를 생성한다. 토스 SDK v2의 카드 등록창에서 입력하며 우리 서버는 카드번호·CVC를 받지 않는다.
3. `/billing/callback`이 authKey/customerKey를 로그인한 사용자의 서버 세션에 결속한다. 주소의 인증 값은 제거하고 nginx 접근 기록에는 쿼리와 Referer를 남기지 않는다.
4. 서버가 빌링키 발급 API를 호출하고 저장 암호화로 보호한다. 주문을 DB에 먼저 기록한 후 빌링 승인 API를 호출한다. 금액과 제공량은 서버에서 결정한다.
5. 승인 확인 후 한 트랜잭션에서 결제 내역, 구독, 월 제공량을 반영한다. 남은 수량은 이월하지 않고 예약량은 보존한다. 중복 콜백과 웹훅은 제공량을 다시 채우지 않는다.
6. worker가 만기 구독을 갱신한다. 놓친 여러 달을 한꺼번에 청구하지 않고 처리 시점부터 한 주기를 연다. 거절되면 `past_due`로 중단한다.
7. 카드 변경은 기존 카드의 갱신을 중단·연결 해제한 뒤 새 카드를 등록한다. 같은 플랜의 남은 이용 기간은 보존하며 즉시 재청구하거나 이용량을 보충하지 않는다. 등록이 취소되면 갱신 중단 상태를 유지하고 새 카드로 구독 재개를 선택할 수 있다.
8. 갱신 중단은 현재 이용 기간을 보존한다. 빌링키 삭제를 재시도 가능한 상태로 기록하며 다음 기간에는 청구하지 않는다.

서버 API: 빌링키 발급, 자동결제 승인, 주문번호 조회, 전체/부분 취소, 빌링키 삭제. 일반 단건 결제의 `/payments/confirm`은 빌링 방식에서 쓰지 않는다. 계좌이체·가상계좌·현금영수증 API도 카드 전용 구독 범위에 없다.

## 복구·환불·증빙

승인 응답이 끊기면 같은 주문을 토스에 조회한다. 결제가 없을 때만 같은 멱등키로 재시도한다. 토스 멱등키 유효기간(15일)을 넘는 자동 재청구를 피하도록 14일 이후에는 조회만 한다. 사용자에게는 결과 확인 중으로 표시한다. 처리 중 주문이 있으면 중복 구매·환불·삭제를 막는다.

관리자는 작업 공간 상세의 테스트 결제 내역에서 전체/부분 환불을 요청한다. 환불 작업 UUID를 재사용하며 실제 잔여 금액을 조회해 중복 취소를 막는다. 환불 자체는 구독/제공량을 바꾸지 않는다. 갱신 중단과 이용량 조정은 별도 작업이며, 상용 환불 정책의 결론으로 사용하지 않는다.

사용자는 결제 내역의 테스트 영수증 확인으로 토스 영수증 링크를 받는다. 테스트 영수증은 실제 세무 증빙이 아니다. 카드 매출전표 흐름을 사용하며 신규 세금계산서 요청 화면은 제공하지 않는다. 과거 요청은 관리자 기록으로만 보존한다.

`POST /payments/toss/webhook`은 알려진 주문의 재조회만 예약한다. 웹훅 본문 금액·상태를 그대로 믿고 제공량을 바꾸지 않는다. `PAYMENT_STATUS_CHANGED`, `CANCEL_STATUS_CHANGED`를 처리한다. 인터넷에서 접근할 수 없는 localhost는 토스 웹훅 수신 URL로 사용할 수 없으므로 본인 상점 키와 공개 HTTPS 테스트 주소가 필요하다. 공개 키만으로 상점 웹훅 전달을 검증했다고 보고하지 않는다.

빌링키·authKey·결제키·영수증 URL은 기존 AES-GCM 봉투 암호화로 보관한다. `rotate-keys`는 결제 두 테이블도 회전하며 낙관적 비교 후 암호문만 갱신해 주문 lease를 보존한다. 미완료 결제나 카드 연결이 남은 계정/작업 공간 삭제는 차단한다.

## 검증

- `TossGatewayTest`: 토스 HTTP 형식, 인증, 멱등키, 오류 분류.
- `TossReachTest`: Kotlin HTTP→PostgreSQL→결제 어댑터 대역. 응답 유실 재조회, 중복 반영 방지, 소유권, 관리자 환불, 해지 만료.
- `TossStoreTest`: 두 결제 봉투의 실제 DB 키 회전과 AAD 바꿔치기 거절.
- `frontend/e2e/toss-billing.spec.ts`: 공개 테스트 키를 사용하는 외부 토스 E2E. 명시적 `E2E_TOSS_TEST=1`일 때만 실행한다. 일반 테스트는 외부 토스를 호출하지 않는다. 실행 결과는 완료 후 아래에 기록한다.

```sh
E2E_TOSS_TEST=1 E2E_API_PROFILES=api,local,e2e EASYDOC_PAYMENT_PROVIDER=toss_test \
  EASYDOC_MAIL_PROVIDER=fake frontend/e2e/run-local.sh toss-billing.spec.ts
```

테스트 키는 명령행에 쓰지 않고 환경변수로 전달한다. E2E는 일회용 DB와 합성 계정, fake LLM을 사용한다. 실제 사용자 카드·유료 LLM 호출은 사용하지 않는다.

## 실제 토스 E2E 결과 (2026-09-13)

공개 개발 문서 체험 키 + 합성 카드 BIN 941088/나머지 0 + 일회용 PostgreSQL에서 통과했다. 카드 등록창 입력, 빌링키 발급, 최초 1,000원 테스트 승인, worker 주기 갱신 1회, 영수증 URL 조회, 각 승인의 400원 부분 환불 및 600원 잔액 환불, 동일 환불 요청 재전송, 구독 갱신 중단과 빌링키 삭제까지 검증했다. 테스트 승인 2건은 모두 전액 테스트 환불했다. 실제 청구·출금은 없다.

실행 로그: 로컬 임시 파일 /tmp/easydoc-toss-e2e.log. 인증 값·빌링키·결제키를 로그/trace/스크린샷에 남기지 않는다. 정기결제는 결제일만 일회용 DB에서 앞당겼고, 승인·조회·환불·키 삭제 자체는 토스 API를 호출했다.

공개 키는 본인 상점 대시보드에 연결되지 않는다. 본인 상점의 테스트 MID/키, 인터넷에서 접근 가능한 HTTPS URL을 설정한 후 토스에서 발생한 실제 웹훅의 수신과 재전송을 별도로 확인해야 한다. 공개 웹훅 엔드포인트의 위조/중복 방어는 서버 통합 테스트로 검증했다.

## 최종 검증 및 로컬 반영 (2026-09-13)

- Backend `./gradlew build --no-parallel --continue`: 성공. JUnit 결과 2,877건, 실패·오류·건너뜀 0건. 로그 `/tmp/easydoc-toss-build-verified.log`.
- Frontend `npm run check`, `npm run test -- --run`, `npm run build`: 성공. 49개 테스트 파일, 619건 통과.
- `docker compose config --quiet`: 성공.
- 최종 산출물로 외부 토스 E2E 재실행: 1건 통과(1.1분). 로그 `/tmp/easydoc-toss-e2e-final.log`.
- localhost의 API·worker·프런트 이미지를 교체하고 세 컨테이너의 healthy 상태를 확인했다. V23/V24 마이그레이션 성공, `/api/health` 및 `/billing/callback` HTTP 200, nginx 설정 검사 성공.
- 로컬은 공개 체험 키의 `toss_test` 모드다. 실결제는 활성화하지 않았다. 키 설정은 Git에서 제외되는 `.env`에만 두었다.
