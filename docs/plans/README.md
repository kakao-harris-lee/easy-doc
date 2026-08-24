# 활성 계획 인덱스

이 디렉터리에는 **현재 Kotlin/Spring Boot 백엔드와 React 프런트엔드에 적용되는 계획만** 둔다.

## 읽는 순서

1. 제품 범위와 우선순위: [`../master-plan.md`](../master-plan.md)
2. 현재 구현/미구현 목록: [`../kotlin-redevelopment-backlog.md`](../kotlin-redevelopment-backlog.md)
3. 현재 스프린트: [`2026-08-24-sprint-k1-kotlin-mvp-completion.md`](2026-08-24-sprint-k1-kotlin-mvp-completion.md)
4. 완료된 전환 기록: [`archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md`](archive/transition/2026-08-24-python-removal-for-kotlin-redevelopment.md)

## 상태 규칙

- 활성 계획의 경로와 명령은 Kotlin/Gradle 또는 React/npm 기준이어야 한다.
- Python/FastAPI/pytest/uv/arq를 실행 전제로 한 문서는 활성 계획으로 취급하지 않는다.
- 과거 구현 기록은 [`archive/python-era/`](archive/python-era/)에 보관하며, 현재 작업의 완료 근거로 재사용하지 않는다.
- 완료 표시는 현재 코드와 자동 검증으로 확인된 것만 사용한다. 과거 Python 구현의 완료 상태는 Kotlin 완료로 승계하지 않는다.
