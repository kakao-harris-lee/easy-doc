# 외부 계약 변경 기록

## 2026-10-07 · 2.51.0 · 관리자 운영 복구

- 근거: G2, [승인된 구현 계획](../../plans/2026-10-07-admin-operations-improvements.md).
- 변경: 처리할 일·메일 확인/재발송·금융 요청 상태·오류 상세 API, 의견 필터,
  금융 조치 가능 여부/감사, 목록 결제·환불 요약, 특정 세금계산서/메일 조회.
- 호환성: 기존 경로와 필드는 보존하며 새 경로·선택 필터·응답 필드를 추가한다.
  기존 금융 관리 요청의 revision은 선택적이며 새 프런트는 확인한 값을 보낸다.
- 영향 검증: AdminMonthlyReachTest, BillingNotificationSchedulerTest,
  JdbcAdminBillingStoreTest, JdbcAdminWorkspaceQueryRepositoryTest,
  FeedbackJoinStorageTest와 React 관리자·API 테스트, 기존 Toss Playwright.
- 통합: Kotlin 실행 담당과 React 실행 담당에 경로·타입·시각·상태 의미를 공유했다.
  Kotlin/React만 대상이며 Python 및 parity 구현은 만들지 않는다.
- 실제 통과 여부와 리뷰 결과: [검증·리뷰 기록](../../reviews/2026-10-07-admin-operations-claude-review.md).

## 2026-10-08 · 2.53.0 · 최신 main 통합

- 읽기 수준 3단계의 계약 2.52.0과 관리자 복구 API를 함께 유지한다.
- 기존 읽기 수준 V46은 변경하지 않고 관리자 복구를 V47로 추가한다.
- 환불 작업이 pending이고 공급자 주문이 manual_review이면 작업 큐도 manual_review/심각도2로 분류한다.
- 기존 계약 스키마와 조치 상태 저장은 유지하며 조회 투영·필터·다음 조치만 보완한다.
- 회귀: JdbcAdminBillingStoreTest, AdminOperationsTab.test.tsx 및 통합 전체 검증.
