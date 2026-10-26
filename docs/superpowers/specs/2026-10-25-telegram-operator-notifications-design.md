# Telegram 운영 알림 설계

## 목표와 범위

이메일/비밀번호 가입, 소셜 최초 가입, 회원 탈퇴와 결제 운영 핵심 이벤트를 지정한 운영자 Telegram 채팅으로 알린다. 알림은 기존 사업 트랜잭션이 성공적으로 커밋된 직후 같은 프로세스에서 동기적으로 한 번 발송을 시도한다.

### 포함

- `application`의 운영 알림 이벤트 모델, 발송 포트, 메시지 생성 및 best-effort 경계
- `infrastructure`의 Telegram Bot API `sendMessage` `RestClient` 어댑터
- 기존 회원·결제 유스케이스의 커밋 후 알림 연결
- 이벤트 선별, 상태 전이 중복 억제, 메시지 형식, 설정과 실패 처리 검증

### 비범위

- durable outbox, 자동 재시도, 실패 큐, 유실 복구, DB 마이그레이션
- worker, 스케줄러, Telegram webhook 또는 공개 HTTP 엔드포인트 추가
- 공개 HTTP API, `contracts/`, `frontend/` 변경
- 기존 `billing_notifications`와 회원용 이메일 동작 변경
- 정기결제 7일 전 예고, 카드 등록·교체 자체에 대한 알림
- 로그인, 기존 소셜 계정 로그인, 소셜 연결·해제, 이메일 인증, 비밀번호 변경 알림

## 현재 구조 근거

- `AuthService.signup`은 사용자, 기본 workspace와 크레딧을 `TransactionRunner.inTransaction` 안에서 생성하고, 반환된 `created`로 커밋 후 이메일 인증 절차를 실행한다. 운영 알림도 같은 커밋 후 위치를 사용한다.
- `SocialLoginService.callback`은 실제 신규 계정 생성에만 `newUserId`를 채운다. 기존 신원 로그인과 동시 최초 콜백 경쟁 뒤 재조회로 복구한 로그인은 `null`이므로 이 값이 가입 알림의 판별 기준이다.
- `DeleteAccountService`는 삭제와 관련 정리를 한 트랜잭션에서 끝낸 뒤 기존 운영자 메일 부수 효과를 실행한다. Telegram 알림은 이 커밋 후 구간에 추가하되 기존 메일은 그대로 둔다.
- `TossBillingService`는 workspace 소유권 잠금과 트랜잭션을 `owned`로 묶고, 주문·구독의 최신 저장 상태를 다시 읽어 제공자 재처리와 멱등 호출을 제어한다. 알림 여부도 제공자 응답이나 호출 횟수가 아니라 이 잠금 안에서 확인한 **이전 상태와 실제 저장 후 상태의 차이**로 결정한다.
- `TransactionRunner`는 현재 `inTransaction`만 제공한다. 별도 after-commit 콜백 확장 대신 각 트랜잭션이 알림에 필요한 불변 사실을 반환하고, `inTransaction`/`owned`가 정상 반환한 뒤 발송하는 기존 코드 형태를 따른다.
- `infrastructure`는 이미 `RestClient`, `Secret`, `@ConfigurationProperties`를 사용한다. Telegram 설정과 어댑터도 같은 모듈 경계를 따른다.

## 선택안과 대안

### 선택안: 커밋 직후 동기 best-effort

1. 사업 트랜잭션 안에서는 대상 전이를 판정하고 알림 사실을 만든다.
2. 트랜잭션이 정상 커밋되어 호출이 반환되면 그 사실을 한 번 동기 발송한다.
3. 발송 및 알림 준비에서 발생한 `RuntimeException`은 알림 경계가 삼킨다.
4. 재시도나 실패 저장은 하지 않는다.

이 방식은 새 저장소와 실행 진입점 없이 승인된 운영 알림만 추가한다. 발송 대기 시간은 요청·배치 작업의 동기 경로에 더해지지만 connect/read timeout으로 상한을 둔다.

### 채택하지 않은 대안

- **durable outbox:** 커밋과 발송 기록의 원자성 및 재시도가 가능하지만 DB 마이그레이션과 worker/스케줄러가 필요하다. 사용자가 선택하지 않았으므로 도입하지 않는다.
- **트랜잭션 안의 synchronous 발송:** Telegram 장애가 DB 커넥션 점유와 사업 트랜잭션에 영향을 주고, 발송 뒤 롤백되면 실제로 존재하지 않는 이벤트를 알릴 수 있어 제외한다.
- **Spring 이벤트의 비동기 처리:** 별도 실행 스레드와 실패·종료 정책이 필요하며 승인된 즉시 동기 방식과 일치하지 않으므로 제외한다.

## 컴포넌트와 데이터 흐름

### `application`

- `OperatorNotificationEvent`: 회원 가입·탈퇴와 결제 이벤트를 구분하는 sealed 이벤트 모델. 공통으로 이벤트 종류, `userId`, `occurredAt`을 갖고 필요한 회원·결제 식별자를 타입별로 제한한다.
- `OperatorNotificationPort`: 완성된 운영 알림을 외부 채널로 전달하는 출력 포트. 사업 서비스는 Telegram API를 알지 않는다.
- `OperatorNotificationService`: 이벤트 수신, 결제 사용자 식별 보강, 이메일 마스킹, plain text 생성, 포트 호출을 담당한다. 진입점의 전체 알림 준비·발송 구간을 `try/catch (RuntimeException)`으로 감싼다.
- `enabled=false`이면 서비스 진입 즉시 반환한다. 결제 workspace 소유자 이메일 조회, 메시지 생성·마스킹, 포트 호출을 모두 수행하지 않는 no-op 경로다.

### `infrastructure`

- `TelegramProperties`: `easydoc.telegram` 설정 바인딩과 기동 검증을 담당한다.
- `TelegramOperatorNotificationAdapter`: `OperatorNotificationPort` 구현체로 Telegram `sendMessage` 한 번을 호출하고 응답을 검증한다.
- `RestClient`에는 JDK request factory의 connect/read timeout을 적용한다. 어댑터 단위 테스트와 로컬 스텁 연결에만 설정 객체 생성자의 `baseUrl` 기본 인자를 주입한다.

### 커밋 후 호출 규칙

- 트랜잭션 블록은 저장 성공 사실과 알림에 필요한 ID·값만 반환한다. 블록 안에서 포트를 호출하지 않는다.
- `AuthService.signup`은 생성된 사용자의 ID·정규화 이메일과 `email` 경로를 커밋 후 전달한다.
- `SocialLoginService.callback`은 `newUserId != null`일 때만 신규 사용자 이메일과 실제 `providerId`를 전달한다. 경쟁 복구 로그인과 기존 신원 로그인은 전달하지 않는다.
- 현재 `LockedAccount`는 `isAdmin`과 `passwordHash`만 갖고 `JdbcAccountDeletionRepository.lockForDeletion`도 이 두 열을 잠금 조회한다. `LockedAccount`에 `email`을 추가하고 JDBC `SELECT … FOR UPDATE`도 `email`을 함께 읽도록 확장한다. `DeleteAccountService`는 별도 사용자 조회 없이 이 잠금 행의 사용자 ID·이메일을 포함한 삭제 결과를 트랜잭션에서 반환하고, 삭제 커밋 후 그 스냅샷과 삭제 완료 시각으로 알린다. 이 변경은 application 저장소 포트와 JDBC 매핑에 국한되며 공개 API 변경이 아니다.
- 결제는 각 `owned` 블록이 **실제 전이가 있을 때만** 이벤트 사실을 반환하고, `owned`가 정상 반환된 직후 발송한다. 예외·롤백·가드 조기 반환으로 블록이 정상 완료되지 않으면 발송하지 않는다. 하나의 커밋에서 서로 다른 대상 이벤트가 실제로 발생하면 각각 한 번씩 발송한다.
- 결제 이벤트의 `userId`는 이미 확보한 workspace 소유자 ID다. 활성화된 경우에만 소유자 이메일 조회와 메시지 보강을 커밋 후 알림 경계 안에서 수행하므로 조회 실패도 결제 결과를 바꾸지 않는다.

## 이벤트와 전이 판정

### 회원 이벤트

| 이벤트 | 발송 조건 | 발송하지 않는 경우 |
|---|---|---|
| `member_signed_up` | `AuthService.signup` 생성 커밋 성공, 경로 `email` | 생성 실패·롤백, 로그인 |
| `member_signed_up` | `SocialLoginService.callback` 생성 커밋 성공 및 `newUserId` 존재, 경로는 실제 social provider | 기존 소셜 로그인, 경쟁 복구 로그인, 연결·해제 |
| `member_deleted` | `DeleteAccountService` 삭제 커밋 성공 | 확인·비밀번호 실패, 관리자 탈퇴 거부, 삭제 롤백 |

이메일 인증 코드 발급 여부와 운영 가입 알림은 독립적이다. 비밀번호 변경과 이메일 인증 자체는 알림 이벤트가 아니다.

### 결제 이벤트

| 이벤트 종류 | 실제 전이와 알림 기준 |
|---|---|
| `payment_approved` | 최초 결제 주문(`charge`)이 미정산 상태에서 `paid`로 전이 |
| `renewal_approved` | 정기 결제 주문(`renewal`)이 미정산 상태에서 `paid`로 전이 |
| `payment_failed` | `charge` 주문이 `pending`/`processing`/`scheduled`에서 `failed`로 전이 |
| `renewal_failed` | `renewal` 주문이 `pending`/`processing`/`scheduled`에서 `failed`로 전이 |
| `refund_approved` | `refund` 주문이 미정산 상태에서 `paid`로 전이 |
| `subscription_canceling` | 구독 상태가 다른 값에서 `canceling`으로 전이 |
| `subscription_expired` | 구독 상태가 다른 값에서 `expired`로 전이 |
| `subscription_past_due` | 구독 상태가 다른 값에서 `past_due`로 전이 |
| `subscription_suspended` | 구독 상태가 다른 값에서 `suspended`로 전이 |
| `payment_manual_review` | 주문 상태가 다른 값에서 `manual_review`로 전이 |

현재 `TossBillingService`의 `expire` 호출은 `expired`와 `past_due`를 만든다. `suspended`는 공통 구독 전이 판정에서 같은 규칙으로 지원하되, 알림을 만들기 위해 새로운 정지 유스케이스나 상태 전이를 추가하지는 않는다.

### 결제 경로별 점검 및 중복 억제

- `finish`: 최신 주문 상태를 workspace 잠금 안에서 다시 읽는다. `charge`/`renewal`은 원 주문의 실제 `paid` 전이만 알리고, `refund`는 원 결제가 아니라 환불 작업 주문의 실제 `paid` 전이를 `refund_approved`로 알린다. `manual_review`에서 최종 승인으로 전이되면 수동 확인 알림과 승인 알림을 각각 남긴다.
- `failed`: 현재 주문이 `pending`/`processing`/`scheduled`일 때만 실패 이벤트를 만든다. 정기결제 재시도마다 새 주문 ID가 발급되므로 서로 다른 재시도 주문의 실패는 각각 알린다. 다음 `scheduled` 주문 생성은 별도 알림이 아니다.
- `waitForCard`: 카드 대기 종료로 `scheduled` 주문이 처음 `failed`가 되면 해당 종류의 실패 이벤트를 만든다. 같은 트랜잭션의 `past_due` 전이는 별도 대상 이벤트다.
- `suspend_pending` 처리: 마지막 재시도 주문은 이미 `failed()`에서 실패 알림을 만들었다. 이후 같은 주문 ID가 `suspend_pending`에서 `failed`로 정리될 때 실패 알림을 다시 만들지 않고, 실제 `past_due` 전이만 알린다.
- `expire`: 저장 전 구독 상태와 요청 상태가 다를 때만 해당 종료·정지 이벤트를 만든다. `active`/`canceling` 복귀와 크레딧 변경만으로는 알리지 않는다.
- `cancel`과 `stopRenewal`: 최신 구독이 실제로 `canceling`이 될 때만 알린다. 이미 `canceling`인 구독 저장, scheduled 정리, session `revoking`/`revoked` 변경은 추가 알림이 아니다.
- `handleUncertain`: 현재 주문이 처음 `manual_review`가 될 때만 알린다. 재시도를 위한 `release`, 이미 `manual_review`인 상태의 재저장, 이미 `paid`/`failed`/`canceled`인 주문은 알리지 않는다.
- `recheck`와 웹훅 재처리: `finish`의 동일한 최신 상태 비교를 통과한 실제 전이만 알린다. 같은 상태 재저장은 알리지 않는다. 웹훅 수신 자체는 알림이 아니다.
- `complete`, `recoverAuthorizations`, `rejectAuthorization`, `revoke`: 카드 등록·교체, 인증 복구, billing key 폐기 자체는 알리지 않는다. 이 경로가 기존 결제 처리 함수를 호출해 위 표의 실제 주문·구독 전이를 커밋한 경우에만 해당 이벤트를 알린다.
- `runDue`: 구독 만료와 각 재시도 주문 처리가 위 규칙을 재사용한다. 주기 조회, 후보 조회, 주문 예약 생성, 7일 전 예고는 알리지 않는다.

결제 메시지의 `orderId`는 전이를 일으킨 주문이다. 구독 상태 이벤트는 해당 구독의 최신 `charge`/`renewal` 주문을 기준 주문으로 사용하고, `planId`·금액·환경은 구독과 그 주문의 커밋된 값을 사용한다. `canceling`처럼 상태 변경 시점에 진행 중인 다른 주문이 있어도 그 주문 ID로 대체하지 않는다.

## 메시지와 보안

### 공통 형식

- 제목은 이벤트 종류를 한국어로 표기하고, `environment=toss_test`인 결제 이벤트만 제목 앞에 `[TEST]`를 붙인다.
- 모든 메시지에 이벤트 종류, 마스킹 이메일, 전체 회원 UUID, 발생 시각을 포함한다.
- 발생 시각은 UTC `Instant`로 보관하고 표시할 때 `Asia/Seoul`로 변환한다. 탈퇴는 이 시각을 삭제 완료 시각으로 표기한다.
- 이메일은 로컬 파트 첫 글자만 남기고 나머지를 정확히 `***`로 대체한다. 예: `h***@example.com`. 도메인은 유지한다.
- 가입 메시지는 `email` 또는 `google`/`kakao`/`naver` 같은 실제 가입 경로를 포함한다.
- 모든 결제 메시지는 `planId`, 정수 금액과 `KRW`, `orderId`, `workspaceId`, `toss_test`/`toss_live` 환경을 포함한다.

### 설정

| 속성 | 기본값과 검증 |
|---|---|
| `easydoc.telegram.enabled` | 기본 `false` |
| `easydoc.telegram.bot-token` | `Secret`으로 보관; 활성화 시 비어 있지 않아야 함 |
| `easydoc.telegram.chat-id` | 활성화 시 비어 있지 않아야 함 |
| `easydoc.telegram.connect-timeout` | 기본 2초; 0보다 커야 함 |
| `easydoc.telegram.read-timeout` | 기본 5초; 0보다 커야 함 |

`enabled=true`인데 필수 설정이 없거나 timeout이 유효하지 않으면 애플리케이션 기동을 실패시킨다. 비활성화 상태에서는 bot token과 chat ID가 없어도 된다. 기본 base URL은 `https://api.telegram.org`이며, 운영 설정 속성으로 노출하지 않고 테스트에서만 로컬 스텁 주소로 교체한다.

토큰은 `Secret`으로 취급하고 properties/settings의 `toString`과 로그에서 마스킹한다. 실제 자격 증명이나 `.env` 값은 코드·문서·테스트에 넣지 않는다.

### Telegram 요청과 응답

- `POST {baseUrl}/bot{token}/sendMessage`를 한 번 호출한다.
- JSON 요청에는 `chat_id`와 plain text `text`만 넣는다. `parse_mode`를 포함하지 않아 HTML/Markdown 파싱을 사용하지 않는다.
- HTTP 오류, connect/read timeout, null 본문, malformed JSON, `ok` 필드가 boolean `true`가 아닌 응답은 모두 실패다.
- 성공 응답 이후에도 재시도·확인 호출을 하지 않는다. Telegram API의 다른 메서드와 webhook은 사용하지 않는다.

## 실패 처리와 로그

- `OperatorNotificationService`는 알림 준비, 메시지 생성, 어댑터 호출의 `RuntimeException`을 한 경계에서 삼킨다. 사업 서비스에는 성공과 동일한 기존 반환값을 돌려준다.
- 발송 실패가 가입·탈퇴·결제의 HTTP 성공 응답, DB 커밋, 크레딧, 구독 상태를 되돌리거나 바꾸지 않는다.
- 실패 시 WARN 로그 한 줄에는 event type, 관련 `userId` 또는 `orderId`, 예외 클래스명만 남긴다. 예외 객체와 stack trace는 그대로 로깅하지 않는다.
- token, 전체 이메일, Telegram 메시지 본문, 제공자 응답 본문, 카드 정보, billing/payment key, chat ID는 로그에 남기지 않는다. URL이나 제공자 오류를 포함할 수 있는 원본 예외 메시지도 남기지 않는다.
- 어댑터는 제공자 응답을 별도 로깅하지 않고 실패를 포트 호출자에게 전달하여 위의 정제된 로그 경계를 통과시킨다.
- 알림이 비활성화된 경우 소유자 이메일 조회와 메시지 생성 없이 즉시 반환하며 외부 호출과 실패 로그도 만들지 않는다.

## 테스트와 검증

### 회원 유스케이스

- `AuthServiceTest`: 이메일 가입 커밋 성공 시 `email` 경로 알림 한 번, 생성 실패·롤백 시 미발송, 발송기 `RuntimeException` 후에도 기존 가입 결과 유지를 검증한다.
- `SocialLoginServiceTest`: provider별 최초 가입만 발송, 이메일 인증 필요 여부와 무관하게 신규 계정이면 발송, 기존 로그인·동시 콜백 경쟁 복구·연결·해제는 미발송, 롤백 시 미발송을 검증한다.
- `DeleteAccountServiceTest`: 잠금 조회가 반환한 `LockedAccount.email`을 별도 조회 없이 사용해 삭제 커밋 후 삭제 시각과 함께 한 번 발송하고, 검증 실패·관리자 거부·롤백 시 미발송, 발송 실패 후에도 탈퇴 결과 유지를 검증한다.
- `JdbcAccountDeletionRepositoryTest`: `lockForDeletion`의 `SELECT … FOR UPDATE`가 `email`, `is_admin`, `password_hash`를 함께 읽고 `LockedAccount`에 정확히 매핑하는지 검증한다. 스키마나 공개 API 변경 없이 기존 잠금·삭제 테스트도 통과해야 한다.

### 결제와 메시지

- `TossBillingServiceTest`: 최초/정기 승인·실패, 환불 승인, `canceling`, `expired`, `past_due`, 공통 전이 판정의 `suspended`, `manual_review`를 각각 검증한다.
- `finish`/`recheck`/웹훅 재처리와 멱등 재호출에서 같은 상태 재저장은 미발송, 서로 다른 재시도 주문 실패는 각각 발송, 마지막 주문의 `suspend_pending` 정리는 중복 실패 미발송을 검증한다.
- `cancel`, `stopRenewal`, `expire`, `handleUncertain`, `waitForCard`, `complete`, `recoverAuthorizations`, `runDue`의 대상 전이와 제외 경로를 검증한다.
- 정기결제 예고, 카드 등록·교체, 카드 폐기, scheduled 생성·release, 기존 소셜 로그인에 해당하는 비대상 호출이 발송기를 호출하지 않음을 확인한다.
- 결제 알림 사용자가 workspace 소유자로 해결되고 plan/금액/order/workspace/environment이 올바른지, `toss_test` 제목에만 `[TEST]`가 붙는지 검증한다.
- 메시지 단위 테스트에서 로컬 파트 길이별 마스킹, 전체 UUID, `Asia/Seoul` 시각, 경로와 결제 필드, plain text 형식을 검증한다.
- 기존 `billing_notifications` 및 회원용 이메일 테스트는 변경 없이 통과해야 한다.

### 어댑터와 설정

- 실제 Telegram 호출 없이 로컬 HTTP 스텁으로 요청 경로와 `chat_id`/`text`만 있는 요청, `parse_mode` 부재, 성공 응답을 검증한다.
- HTTP 실패, `ok=false`, connect/read timeout, null/malformed 응답을 각각 실패로 검증하고 자동 재시도가 없음을 확인한다.
- 설정 바인딩, 기본 `enabled=false`, 활성화 시 필수값 누락과 잘못된 timeout의 기동 실패, 비활성화 시 필수값 부재 허용을 검증한다.
- `enabled=false`에서는 소유자 저장소와 메시지 생성기를 호출하지 않고 즉시 반환하며 발송 포트도 호출하지 않음을 검증한다.
- properties/settings `toString`과 실패 로그에 token·전체 이메일·본문·제공자 응답·결제 비밀값이 없음을 검증한다.
- 전체 검증은 `cd backend-kotlin && ./gradlew build`로 실행한다. Compose나 환경 예시를 바꾸는 구현이면 추가로 `docker compose config`를 실행한다.

## 완료 기준

- 승인된 회원·결제 이벤트는 실제 사업 커밋 후 한 번의 동기 발송을 시도한다.
- 비대상 이벤트, 롤백, 웹훅 재처리·상태 재조회·멱등 재호출의 동일 상태 재저장은 발송하지 않는다.
- 서로 다른 정기결제 재시도 주문의 실패는 각각 알리고, 동일 주문의 후속 정리는 중복 알리지 않는다.
- Telegram 발송 및 알림 준비 실패에도 기존 사업 결과와 트랜잭션 결과가 유지된다.
- 메시지 마스킹·식별자·시각·테스트 결제 표시와 설정 검증, secret/log redaction이 위 규칙을 충족한다.
- 공개 HTTP API, 계약, 프런트엔드, 기존 `billing_notifications`와 회원용 이메일에 변경이 없다.
- 지정한 단위·어댑터·설정 테스트와 `cd backend-kotlin && ./gradlew build`가 통과한다. Compose/환경 예시 변경 시 `docker compose config`도 통과한다.

## 명시적으로 수용하는 제한

- 알림은 durable하지 않다. 커밋 후 프로세스가 충돌하거나 종료되면 해당 알림은 유실될 수 있다.
- Telegram 장애, 네트워크 오류, timeout, 비정상 응답 시 재시도하지 않으므로 알림이 유실될 수 있다.
- 요청이 Telegram에서 처리된 뒤 timeout이 발생하더라도 결과를 재확인하지 않는다. 발송 시도는 최대 한 번이며 전달 성공은 보장하지 않는다.
- 동기 호출이므로 Telegram 응답 지연은 설정한 timeout 범위 안에서 기존 요청·배치 처리 지연에 더해진다.
- 동일 상태 재저장 억제는 저장 상태 비교에 근거하며, 별도 발송 이력을 저장하지 않는다. 유실 복구나 과거 알림 감사는 제공하지 않는다.
