---
name: api-contract
description: Easy-Read AI의 공개 HTTP API 경로, 상태 코드, 요청·응답, 오류, 헤더 또는 인증 계약을 추가하거나 변경할 때 사용한다. 내부 구현만 바뀌고 외부 계약이 그대로인 작업에는 사용하지 않는다.
---

# API Contract

외부 HTTP 계약의 정본은 `contracts/easy-doc-v1.yaml`이다. Kotlin API와 React는 이 파일에 맞춘다.

## 변경 원칙

- 계약 변경은 사용자 요구, `docs/master-plan.md` 정책, 확인된 결함 중 하나에 근거해야 한다.
- Spring 기본 동작이나 구현 편의만으로 공개 계약을 바꾸지 않는다.
- 상태 코드, 오류 본문, 헤더도 JSON schema와 같은 계약으로 다룬다.
- 다른 사용자의 자원은 404로 숨기고, 민감 응답의 보안 헤더를 유지한다.
- enum과 입력 상한을 자유 문자열이나 암묵적 기본값으로 넓히지 않는다.

## 작업 흐름

1. 계약 파일과 현재 Kotlin controller·DTO·contract test·React 소비 코드를 확인한다.
2. 변경 전후의 사용자 영향과 호환성 영향을 정리한다.
3. 계약 파일을 먼저 또는 같은 변경 단위에서 수정한다.
4. Kotlin 구현과 contract test를 맞춘다.
5. React 타입, API client, 관련 화면이 영향을 받으면 함께 수정한다.
6. backend contract test와 frontend 검사로 양쪽을 검증한다.

계약을 바꾸지 않는 내부 리팩터링에는 이 절차를 적용하지 않는다.

## 확인 항목

- 경로와 HTTP 메서드
- 성공·오류 상태 코드
- 인증과 소유권 경계
- 요청·응답 필드, nullability, enum
- 오류 본문의 형태
- `Cache-Control`, `X-Content-Type-Options`, `Location`, `Content-Disposition`, CORS 노출 헤더
- 본문·업로드 크기 제한과 지원 형식

스펙만 수정하거나 구현만 수정한 상태로 완료하지 않는다. 프론트 영향이 없으면 확인한 근거를 보고한다.
