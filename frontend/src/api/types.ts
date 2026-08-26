/**
 * 공개 API 계약과 1:1로 맞춘 wire type.
 *
 * 필드 이름은 백엔드가 내려주는 snake_case를 그대로 쓴다 — 경계에서 이름을 바꾸면
 * 어떤 필드가 서버에서 온 것인지 추적이 끊기고, 스키마가 바뀌었을 때 타입 검사가
 * 잡아주지 못하는 구간이 생긴다.
 *
 * 기준: contracts/easy-doc-v1.yaml
 */

/** 변환 상태. 백엔드 conversions.status CHECK 제약과 같은 값 집합이다. */
export type ConversionStatus = 'pending' | 'processing' | 'done' | 'failed'

/**
 * 내보내기 형식. 계약 `components/schemas/ExportFormat`.
 * **`'pdf'`는 없다** — PDF 렌더러가 없어 서버가 422로 거절한다.
 */
export type ExportFormat = 'docx' | 'txt' | 'hwpx'

/**
 * 문서가 어디서 왔는가. 붙여넣기는 `'text'`, 파일은 소문자 확장자.
 * 계약 `components/schemas/SourceFormat`.
 */
export type SourceFormat = 'text' | 'docx' | 'pdf' | 'hwpx'

/**
 * 원본 서식 유지 상태. 계약 `components/schemas/FormatPreservationStatus`.
 * 오늘 서버가 낼 수 있는 값은 `'not_applicable'` 하나뿐이다 — 유지할 원본 서식이
 * 없다는 뜻이고(붙여넣기이거나 원본 바이트가 남아 있지 않다), 구조 보존이 구현되면
 * 계약과 함께 값이 는다.
 */
export type FormatPreservationStatus = 'not_applicable'

/** 원본 서식 유지 판정. 계약 `components/schemas/FormatPreservation`. */
export interface FormatPreservation {
  status: FormatPreservationStatus
  /** 사용자에게 보여 줄 영향 항목 문구. 개인정보도 본문도 담기지 않는다. */
  details: string[]
}

// --- auth ---

/** POST /auth/signup, POST /auth/login 요청 본문. */
export interface CredentialsRequest {
  email: string
  password: string
}

/** 사용자 공개 표현. 비밀번호 해시는 절대 실리지 않는다. */
export interface UserResponse {
  id: string
  email: string
}

/** POST /auth/login 응답. */
export interface TokenResponse {
  access_token: string
  token_type: string
  /** 유효 기간(초). */
  expires_in: number
}

// --- documents ---

/** POST /documents 요청 본문 (붙여넣기 모드). */
export interface DocumentTextRequest {
  text: string
  title?: string | null
  /** 담을 작업 공간. 없으면 서버가 기본(가장 먼저 만든) 작업 공간에 담는다. */
  workspace_id?: string | null
}

/** POST /documents 응답 (202 — 변환은 아직 시작 전). */
export interface DocumentCreatedResponse {
  document_id: string
  conversion_id: string
  status: ConversionStatus
  /** 공백 포함 문자 수. 크레딧 환산(1,000자 = 1크레딧)의 기준값. */
  char_count: number
}

/** 검수 화면에 보여줄 마스킹 항목. original은 가려졌던 실제 값이다. */
export interface MaskedItemResponse {
  category: string
  placeholder: string
  original: string
}

/** 변환 상태·결과. 완료 전에는 결과 필드가 비어 있다. */
export interface ConversionResponse {
  id: string
  document_id: string
  status: ConversionStatus
  /** 원본 형식. **결과 필드가 아니라 문서 메타라** 완료 전에도 실려 온다. */
  source_format: SourceFormat
  /**
   * 이 변환을 내려받을 때 **써야 하는** 형식. 서버가 `source_format`에서 유도한다.
   * `null`은 「모른다」가 아니라 **「같은 형식으로 내보낼 수단이 없다」**다(원본이 PDF).
   * 그때 다른 형식으로 우회 다운로드를 제시하지 않는다.
   */
  export_format: ExportFormat | null
  /**
   * 서식 유지 상태. `null`은 **서버가 아직 판정하지 않았다**는 뜻이고
   * 「유지 가능」도 「유지 불가」도 아니다 — 상태를 화면에서 지어내지 않는다.
   */
  format_preservation: FormatPreservation | null
  easy_text: string | null
  /** 담당자 검수 수정본. 에디터 초기값은 `edited_text ?? easy_text`. */
  edited_text: string | null
  /** ISO 8601 문자열. */
  reviewed_at: string | null
  masked_items: MaskedItemResponse[]
  missing_placeholders: string[]
  model: string | null
  provider_name: string | null
  input_tokens: number | null
  output_tokens: number | null
  /** 실패 사유 코드(예외 클래스명). 본문·모델 응답은 담기지 않는다. */
  failure_code: string | null
}

/** PUT /conversions/{id} 요청 본문. */
export interface ConversionReviewRequest {
  edited_text: string
}

/** 문서 목록 한 줄 (문서 메타 + 최신 변환 상태). */
export interface DocumentListItem {
  id: string
  title: string
  /** 계약은 2026-08-12부터 enum이었다 — 1.6.0에서 이름 있는 컴포넌트가 되며 타입을 맞췄다. */
  source_format: SourceFormat
  char_count: number
  /** ISO 8601 문자열. */
  created_at: string
  /** ISO 8601 문자열. 보관 만료 시점. */
  retention_expires_at: string
  conversion_id: string | null
  status: ConversionStatus | null
  /** 검수 수정본을 저장한 시각(ISO 8601). null이면 아직 AI 초안 그대로다. */
  reviewed_at: string | null
}

/** GET /documents 응답. 총 개수는 싣지 않는다(has_more로 다음 쪽 유무만 알린다). */
export interface DocumentListResponse {
  items: DocumentListItem[]
  limit: number
  offset: number
  has_more: boolean
}

// --- workspaces ---

/** 작업 공간 한 건 (POST·PATCH 응답). */
export interface WorkspaceResponse {
  id: string
  name: string
  /** ISO 8601 문자열. */
  created_at: string
}

/** 목록 한 줄. 문서 수는 목록 응답에만 실린다. */
export interface WorkspaceListItem extends WorkspaceResponse {
  document_count: number
}

/** GET /workspaces 응답. 첫 번째 항목이 기본 작업 공간이다(가장 먼저 만든 것). */
export interface WorkspaceListResponse {
  items: WorkspaceListItem[]
}

/** POST /workspaces, PATCH /workspaces/{id} 요청 본문. */
export interface WorkspaceNameRequest {
  name: string
}

// --- pilot feedback ---

/**
 * 배포 의향. 파일럿 게이트 ①의 기준 1을 판정하는 값이다(docs/pilot-runbook.md).
 *
 * `as_is`·`with_edits`가 "이 결과물을 다듬어 실제로 배포하겠다"에 해당한다.
 */
export type PublishIntent = 'as_is' | 'with_edits' | 'not_usable'

/**
 * PUT /conversions/{conversion_id}/feedback 요청 본문.
 *
 * 한 변환의 피드백은 1건이고 다시 보내면 덮어쓴다(멱등 upsert).
 */
export interface ConversionFeedbackRequest {
  publish_intent: PublishIntent
  /** 품질 만족도. 1~5 정수. */
  quality_score: number
  /** 이번 건에 들인 시간(분). 0~600 정수. */
  minutes_spent: number
  /** 자유 의견. 서버에서 봉인되므로 집계 스크립트는 읽지 않는다. 비우면 null. */
  comment: string | null
}

/** PUT /conversions/{conversion_id}/feedback 응답. 저장된 값을 그대로 돌려준다. */
export interface ConversionFeedbackResponse {
  conversion_id: string
  publish_intent: PublishIntent
  quality_score: number
  minutes_spent: number
  comment: string | null
  /** ISO 8601 문자열. */
  submitted_at: string
}
