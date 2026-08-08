/**
 * 백엔드 Pydantic 스키마와 1:1로 맞춘 타입.
 *
 * 필드 이름은 백엔드가 내려주는 snake_case를 그대로 쓴다 — 경계에서 이름을 바꾸면
 * 어떤 필드가 서버에서 온 것인지 추적이 끊기고, 스키마가 바뀌었을 때 타입 검사가
 * 잡아주지 못하는 구간이 생긴다.
 *
 * 원본: app/api/auth.py, app/api/documents.py
 */

/** 변환 상태. 백엔드 conversions.status CHECK 제약과 같은 값 집합이다. */
export type ConversionStatus = 'pending' | 'processing' | 'done' | 'failed'

/** 내보내기 형식. 백엔드 ExportFormat과 같다. */
export type ExportFormat = 'docx' | 'txt'

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
  source_format: string
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
