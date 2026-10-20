# 심사용 결제 노출 제한과 이미지 대체 텍스트

## 완료 기준과 범위

- 일반 사용자는 가격·제공량을 볼 수 있지만 테스트 결제 시작 버튼, 동의 입력, 테스트 안내를 보지 않는다.
- 기존 승인용 작업 공간은 서버가 `purchase_enabled: true`를 반환할 때만 카드 등록 경로를 사용한다. 권한 값이 누락되어도 Toss 테스트 경로는 닫는다.
- 공개 이용 가이드와 이용약관에서는 테스트 결제 안내를 제거한다. 심사용 결제 화면과 기록의 실제 청구 없음 안내는 유지한다.
- 홈 소개 그림과 로고의 빈 `alt`를 실제 내용을 설명하는 문구로 채운다. 기존 스크린리더 그룹 설명은 유지해 중복 낭독을 피한다.
- 구현 범위는 React 화면·공개 콘텐츠다. 기존 서버 허용 목록 정책은 변경하지 않으며 관련 Kotlin 단위 테스트로 검증한다. HTTP 계약과 실결제 활성화는 변경하지 않는다.
- Codex가 구현 및 최종 diff 리뷰를 수행한다. 수정 반복 상한은 2회이며 유료 외부 모델 호출은 하지 않는다.

## 운영 설정 확인

2026-10-20 운영 API 컨테이너의 실제 환경에서 `provider=toss_test`, `mock_enabled=false`, `purchase_enabled=false`, `auto_charge_enabled=false`와 심사용 작업 공간 한 곳의 허용 목록을 확인했다. 해당 작업 공간 소유자는 기존 Toss 심사 계정이다. 카드 등록 세션·구독·결제 내역은 각 1건이며 모두 그 작업 공간에 속한다. 개인정보와 작업 공간 식별자는 문서에 기록하지 않는다.

`TossBillingService.begin`은 작업 공간 소유권 검증 후 전역 구매 설정 또는 허용 목록을 검사한다. 승인 처리도 같은 구매 제한을 사용하고 갱신은 별도 자동 청구 설정을 확인한다. 운영의 전역 구매 차단을 유지해야 심사 계정 제한이 유지된다. 추후 실결제 전환은 별도 작업이다.

## 검증

- Frontend: `npm run check && npm run test -- --run && npm run build`
- Backend: `./gradlew build` (실제 PG 호출 없이 기존 대역을 사용하는 허용 목록 회귀 테스트 포함)
- Compose: `docker compose -f compose.yml -f compose.pilot.yml config --quiet`
- 브라우저: 일반 계정/심사 계정 응답별 결제 진입, 홈의 밝은/어두운 테마, 모바일 폭 확인
- 배포 후: 검색용 원본 HTML과 브라우저 DOM의 빈 alt 여부, API 상태 확인

네이버 가이드: https://searchadvisor.naver.com/guide/seo-help
