# 휴대폰 인증 후속 정리 — PR #123 리뷰에서 남긴 5건

작성일 2026-09-16 · 근거: PR #123 리뷰 판정의 「조치하지 않은 지적」. 다섯 항목 모두 동작
문제가 아니라 중복·불필요 코드·잠금 안 비용이다. 외부 HTTP 계약은 바뀌지 않는다.

## 범위

| # | 항목 | 현재 | 정리 |
|---|---|---|---|
| 1 | 결제 자격 게이트 복제 | `SubscriptionService.checkout`·`TossBillingService.beginBilling`이 이메일→휴대폰 인증 판정을 각자 인라인 | `application/subscription/PaymentEligibility.kt`의 순수 함수 하나로 합치고 두 서비스가 부른다 |
| 2 | HMAC-SHA256 3중 구현 | `SignupGrantEmailHasher`·`PhoneFingerprintHasher`·`SensSignature`가 각자 `Mac.getInstance` | `core/security/HmacSha256`(JDK만 사용, Spring 없음) 하나로 모으고 세 곳은 인코딩(hex·base64)만 고른다 |
| 3 | 프런트 OTP 흐름 중복·컴포넌트 분리 | `AccountSettingsPage`가 휴대폰 인증 상태 6개를 직접 들고, 재발송 쿨다운·코드 입력 정제가 `EmailVerificationPage`와 따로 | `useResendCooldown` 훅·`oneTimeCode` 정제 함수를 공유하고, 휴대폰 절을 `PhoneVerificationSection` 컴포넌트로 뺀다 |
| 4 | 읽지 않는 쿠키 | `easydoc_phone_verified`를 배너가 쓰기만 하고 아무도 읽지 않음 | 모듈·useEffect·테스트 단언·개인정보처리방침 §9 행을 함께 지운다 |
| 5 | 잠금 안 집계 조회 | `PhoneVerificationService.confirm`이 사용자 행 잠금 안에서 `listOwned`(문서 수 집계 조인)로 첫 워크스페이스 id만 얻음 | `WorkspaceRepository.findDefaultId(ownerId)`(집계 없는 단일 행 조회) 추가 |

## 결정

- **게이트 문구는 그대로 둔다.** 「이메일 인증 후 결제하세요」「휴대폰 인증 후 카드를 등록하세요」 네 문구가 사용자에게 이미 나가고 있으므로, 공유 함수는 행동(`PaymentAction`: 결제 / 카드 등록)을 받아 같은 문구를 만든다.
- **HMAC 헬퍼는 `core`에 둔다.** `Secret`이 이미 `core/security`에 있고 세 호출자 중 하나(`SensSignature`)가 `infrastructure`라 `application`에 두면 의존 방향이 맞지 않는다. `core`는 JDK `javax.crypto`만 쓰므로 Spring·DB 없이 테스트된다. 알려진 벡터(RFC 4231)로 고정한다.
- **휴대폰 재발송에 쿨다운 UI가 생긴다.** 훅을 공유하면서 휴대폰 절도 이메일 화면처럼 429의 `retry_after`를 카운트다운으로 보여준다. 이것이 이번 정리에서 유일한 화면 동작 변화이며, 기존 문구·role·라벨은 바꾸지 않는다(e2e `support/app.ts`의 선택자 유지).
- **개인정보처리방침은 초안이라 버전을 올리지 않는다.** `privacy-2026-09-16-draft`, 미게시. §9의 쿠키 행과 「휴대폰 인증 안내 쿠키를 차단해도…」 문장만 지운다.
- **저장소 새 메서드는 `null` 반환.** 소유 워크스페이스가 없으면 `null`이고, `confirm`은 지금처럼 `StorageException`을 던진다(가입 시 기본 워크스페이스가 항상 생기므로 도달하지 않는 방어선).

## 레인

- **백엔드(1·2·5)** — 한 실행 에이전트가 직렬로. `PhoneVerificationService.kt`를 2와 5가 같이 만지므로 나누지 않는다.
- **프런트(3·4)** — 다른 실행 에이전트가 병렬로. 두 레인은 디렉터리·빌드 도구가 겹치지 않는다.
- 리뷰: `code-reviewer`(sonnet) + `privacy-gate`(HMAC·개인정보처리방침이 닿음). 계약 무변경이라 `contract-keeper`·`migration-reviewer`는 부르지 않는다.

## 검증

```bash
cd backend-kotlin && ./gradlew build
cd frontend && npm run check && npm run test -- --run && npm run build
```
