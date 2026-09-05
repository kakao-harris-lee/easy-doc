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
 * **`'text'`와 `'txt'`는 다른 값이다** — `'text'`는 붙여넣기(파일이 아니다), `'txt'`는
 * 업로드된 평문(.txt) 파일이다.
 * 계약 `components/schemas/SourceFormat`.
 */
export type SourceFormat = 'text' | 'docx' | 'pdf' | 'hwpx' | 'txt'

/**
 * 원본 서식 유지 상태. 계약 `components/schemas/FormatPreservationStatus`.
 *
 * - `'not_applicable'` — 유지할 원본 서식이 없다(붙여넣기이거나 원본 바이트가 남아
 *   있지 않다). 영구히 참이다.
 * - `'available'` — 원본 구조 그대로 나간다. 짝이 하나라도 어긋나면 이 값이 아니다.
 * - `'partial'` — 일부는 달라진다. 무엇이 얼마나 달라지는지 `details`가 개수로 말한다.
 * - `'failed'` — 같은 형식으로 다시 만들 수 없다. 내려받기도 같은 사유로 실패한다.
 *
 * `'checking'`은 계약에 **없다** — 판정이 조회 한 번 안에서 동기로 끝나 지켜볼 진행
 * 상태가 없다. 아직 판정하지 않은 동안은 `format_preservation` 자체가 `null`이다.
 */
export type FormatPreservationStatus = 'not_applicable' | 'available' | 'partial' | 'failed'

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
  /**
   * 이메일 소유를 확인했는지(2.9.0 신설). 소셜 로그인 계정과 마이그레이션 이전 기존
   * 계정은 항상 참이다. 거짓이면 `createDocument`가 403을 낸다.
   */
  email_verified: boolean
  /**
   * 연결된 소셜 신원의 제공자 목록(2.10.0 신설). 비밀번호 전용 계정, 아직 아무것도
   * 잇지 않은 계정은 빈 배열이다 — `null`이 아니다.
   */
  identities: UserIdentityResponse[]
}

/** `UserResponse.identities`의 항목 하나. 계약 `components/schemas/UserIdentityResponse`. */
export interface UserIdentityResponse {
  provider: OAuthProvider
}

/** POST /auth/login 응답. */
export interface TokenResponse {
  access_token: string
  token_type: string
  /** 유효 기간(초). */
  expires_in: number
}

/** 지원하는 소셜 로그인 제공자. 계약 `enum`은 `google`·`kakao` 둘이다(카카오는 2.13.0 신설). */
export type OAuthProvider = 'google' | 'kakao'

/** POST /auth/oauth/{provider}/start 요청 본문. */
export interface OAuthStartRequest {
  /** SPA가 제공자 인가 뒤 되돌아올 자기 경로. 서버 허용 목록 안의 값만 받는다. */
  redirect_uri: string
}

/** POST /auth/oauth/{provider}/start 응답. */
export interface OAuthStartResponse {
  /** 사용자를 리디렉션할 제공자 인가 URL. */
  authorization_url: string
  /** CSRF 방지 토큰. SPA는 콜백까지 들고 있다가 그대로 되돌려 보낸다. */
  state: string
}

/** POST /auth/oauth/{provider}/callback 요청 본문. */
export interface OAuthCallbackRequest {
  /** 제공자가 발급한 인가 코드. */
  code: string
  state: string
  /** `oauthStart`에 보냈던 것과 같은 값이어야 한다. */
  redirect_uri: string
}

/** POST /auth/email-verification/confirm 요청 본문. */
export interface ConfirmEmailVerificationRequest {
  /** 메일로 받은 6자리 숫자 코드. */
  code: string
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

/**
 * GET /documents/{document_id}/source 응답 — 추출된 원문.
 *
 * 소유자만 볼 수 있고, 남의 것·없는 것·보관 기간이 지나 파기된 것은 모두 404다.
 * 값은 문서 등록 시점에 확정돼 변하지 않으므로 화면은 **한 번만** 가져온다.
 *
 * `source_text`에는 **마스킹 전 개인정보가 그대로 들어 있을 수 있다.** 화면에 그리는
 * 것 외에 저장·로그·분석 이벤트 어디로도 보내지 않는다.
 */
export interface DocumentSourceResponse {
  document_id: string
  source_format: SourceFormat
  /** 공백 포함 문자 수. `DocumentCreatedResponse.char_count`와 같은 기준이다. */
  char_count: number
  source_text: string
}

/** 검수 화면에 보여줄 마스킹 항목. original은 가려졌던 실제 값이다. */
export interface MaskedItemResponse {
  category: string
  placeholder: string
  original: string
}

/**
 * 쉬운 글 단위 하나가 원본 단위에 대응한다는 판정의 신뢰도. 계약
 * `components/schemas/SegmentConfidence`.
 *
 * `high`는 다시 쓰기를 견뎌 살아남는 앵커(마스킹 자리표시자·숫자·날짜·시각·금액·백분율·
 * 연락처·URL)로 뒷받침된 대응이다. `low`는 앵커가 없어 순서 비례 보간으로만 나온
 * 추정이다 — 화면은 `high`만 대응으로 주장하고 `low`는 「대응 확인 불가」로 표시한다.
 */
export type SegmentConfidence = 'high' | 'low'

/**
 * `SegmentMap.units`의 항목 하나 — 쉬운 글 단위 하나와 그것이 대응하는 원본 단위들.
 * 계약 `components/schemas/SegmentMapUnit`.
 */
export interface SegmentMapUnit {
  /** `edited_text ?? easy_text`를 `\n`으로 쪼갠 줄의 0 기반 색인. */
  easy_unit_index: number
  /**
   * 이 쉬운 글 단위가 대응하는 원본 단위(저장된 추출 원문을 `\n`으로 쪼갠 줄)의 0 기반
   * 색인 목록. 0개 이상 — 빈 배열은 「대응하는 원본 단위를 찾지 못했다」는 뜻이다.
   */
  source_unit_indexes: number[]
  confidence: SegmentConfidence
}

/**
 * 원문-쉬운 글 문단 대응표. `ConversionResponse.segment_map`의 본체. 계약
 * `components/schemas/SegmentMap`.
 */
export interface SegmentMap {
  /** 원본 단위(저장된 추출 원문을 `\n`으로 쪼갠 줄) 총수. */
  source_unit_count: number
  /** 쉬운 글 단위 총수 — `units` 배열의 길이와 같다. */
  easy_unit_count: number
  /** 쉬운 글 단위 색인 순서 그대로, `easy_unit_count`개. */
  units: SegmentMapUnit[]
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
   *
   * `null`은 「모른다」가 아니라 **「서버가 하나로 정하지 않는다」**다. 오늘 이 값이
   * 나오는 원본은 PDF뿐이고, 두 갈래로 갈린다 — `export_format_choices`가 비어 있지
   * 않으면 그 배열 중 하나를 사용자가 **직접 골라야** 하고(2.6.0, DESIGN.md §6.5
   * 2026-09-02 재결정), 비어 있으면 「같은 형식으로 내보낼 수단이 없다」는 뜻이라 다른
   * 형식으로 우회 다운로드를 제시하지 않는다.
   */
  export_format: ExportFormat | null
  /**
   * `export_format`이 `null`이고 사용자가 고를 수 있는 형식이 있을 때만 비어 있지 않은
   * 배열이다. 그 밖에는(`export_format`이 값을 냈거나, `null`이지만 고를 형식도 없을
   * 때) 빈 배열이다 — `masked_items`와 같은 규칙으로 `null`이 아니라 `[]`다.
   *
   * 오늘 이 배열이 비어 있지 않은 원본은 PDF 하나뿐이고 값은 `['docx', 'hwpx']`다.
   * 계약 `x-export-format-derivation.choices`가 정본이다.
   */
  export_format_choices: ExportFormat[]
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
  /**
   * 이번 결과에 대한 의견을 보낸 시각(ISO 8601). 보낸 적이 없으면 `null`이다.
   *
   * `reviewed_at`과 **다른 사실**이다 — 저 값은 수정본을 저장한 시각이라, 의견만 보낸
   * 변환에서는 끝까지 `null`로 남는다. 서버는 시각만 돌려주고 의견의 **내용**(배포
   * 의향·점수·소요 시간·자유 의견)은 응답에 싣지 않으므로, 화면도 「언제 보냈는가」
   * 이상을 말하지 않는다.
   */
  feedback_submitted_at: string | null
  masked_items: MaskedItemResponse[]
  missing_placeholders: string[]
  model: string | null
  provider_name: string | null
  input_tokens: number | null
  output_tokens: number | null
  /** 실패 사유 코드(예외 클래스명). 본문·모델 응답은 담기지 않는다. */
  failure_code: string | null
  /**
   * 원문-쉬운 글 문단 단위 대응표(계약 2.12.0, P0-4). `null`이면 ⑴ 변환이 아직
   * 완료되지 않았거나 ⑵ 완료됐지만 원문·본문 중 하나를 서버가 지금 읽을 수 없다는
   * 뜻이다 — 사유 필드를 따로 두지 않는다. `status`와 `easy_text`가 이미 사유를 말한다.
   *
   * **서버에 저장되지 않는다.** 매 조회마다 (원문을 마스킹한 것) 대
   * (`edited_text ?? easy_text`)에서 순수 함수로 유도한다 — 그래서 에디터에서 텍스트를
   * 수정한 뒤에는 이 응답이 낡는다. 화면은 저장(PUT) 후 새 응답이 올 때까지, 또는
   * 문단 나누기·합치기 같은 구조 변화에서만 클라이언트가 국소적으로 재계산해도 된다
   * (서버가 강제하지 않는 클라이언트 재계산).
   *
   * 원문 자체(문자열)는 여기 싣지 않는다 — 화면은 이미 `GET /documents/{id}/source`로
   * 원문을 받고, 본문은 `easy_text`·`edited_text`에 있다. 배열은 색인만 나른다.
   */
  segment_map: SegmentMap | null
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
  /**
   * 이번 결과에 대한 의견을 보낸 시각(ISO 8601). 보낸 적이 없으면 null이다.
   * 수정본 저장과는 별개의 사실이라 `reviewed_at`이 null인 줄에도 값이 있을 수 있다.
   */
  feedback_submitted_at: string | null
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

// --- 문단 재변환 (P0-4 S4/S5, 계약 2.14.0) ---

/**
 * `POST /conversions/{id}/units/{source_unit_index}/reconvert` 요청 본문. 경로의
 * `source_unit_index`는 본문에 되풀이하지 않는다(계획 §4 결정 3).
 */
export interface ReconvertUnitRequest {
  /**
   * 클라이언트가 지금 이 원본 단위에 대응시키고 있는 쉬운 글 단위 색인들. 빈 배열일
   * 수 있다. 서버는 이 값으로 판정하지 않고 응답에 그대로 되울린다.
   */
  easy_unit_indexes: number[]
  /**
   * 에디터 현재 본문의 SHA-256 다이제스트(16진 소문자 64자) —
   * `src/review/fingerprint.ts`의 `computeEasyTextFingerprint`로 만든다.
   */
  easy_text_fingerprint: string
}

/**
 * `POST /conversions/{id}/units/{source_unit_index}/reconvert` 응답 — 재변환 후보.
 *
 * **후보뿐이고 변환 본문(`easy_text`·`edited_text`)에는 아무것도 쓰이지 않는다.** 채택
 * (바꾸기·삽입)은 클라이언트 몫이다(계획 §4 결정 3).
 */
export interface ReconvertUnitResponse {
  /** 다시 변환한 후보 본문. 원본 단위 하나에 대응하는 쉬운 글 텍스트다. */
  candidate_text: string
  /** 요청 경로의 `source_unit_index`를 그대로 되울린다. */
  source_unit_index: number
  /** 요청 `easy_unit_indexes`를 그대로 되울린다. */
  easy_unit_indexes: number[]
  /** 요청 `easy_text_fingerprint`를 그대로 되울린다. */
  easy_text_fingerprint: string
  /** 이 재변환이 실제로 쓴 LLM 호출 수 — 1(보정 불필요) 또는 2(보정 호출까지). */
  llm_calls_used: number
  /** 이 호출을 정산한 뒤 이 문서에 남은 재변환 호출 예산. */
  remaining_call_budget: number
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

// --- dictionary (P0-5, 계약 2.11.0) ---

/**
 * 사전 치환 전략. 계약 `components/schemas/TermStrategy`.
 *
 * `substitute`(지워도 안전) · `gloss`(원어를 남기고 뜻을 덧붙임) · `keep`(손대면 안 됨).
 */
export type TermStrategy = 'substitute' | 'gloss' | 'keep'

/** 오변환 위험도. `high`는 자동 치환 금지 신호다. 계약 `components/schemas/TermRisk`. */
export type TermRisk = 'none' | 'low' | 'high'

/**
 * 조회 후보의 일치 종류. 계약 `components/schemas/TermMatchKind`.
 *
 * `exact` — 매치 표면형이 표제어와 같고 남는 것이 조사뿐. `inflected` — 표면형이 표제어와
 * 다른 활용형·이형태. `compound_part` — 질의 일부만 사전에 있는 복합어 부분 일치(이
 * 갈래는 `applicable`이 항상 거짓이다). 연속 점수(`score`)는 계약에 없다.
 */
export type TermMatchKind = 'exact' | 'inflected' | 'compound_part'

/** `DictionaryLookupCandidate.examples`의 항목 하나. */
export interface DictionaryLookupExample {
  before: string
  after: string
}

/** POST /dictionary/lookup 후보 하나. 계약 `components/schemas/DictionaryLookupCandidate`. */
export interface DictionaryLookupCandidate {
  /** 사전 표제어 원형. */
  term: string
  /** 쉬운 말. */
  easy_term: string
  strategy: TermStrategy
  risk: TermRisk
  definition: string | null
  caution: string | null
  tags: string[]
  examples: DictionaryLookupExample[]
  match_kind: TermMatchKind
  /**
   * 참이면 편집기가 치환 버튼을 낸다 — `strategy === 'substitute'`이고
   * `match_kind !== 'compound_part'`일 때만 참이다.
   */
  applicable: boolean
}

/** 사전 단위 출처 표기. 계약 `components/schemas/DictionaryAttribution`. */
export interface DictionaryAttribution {
  name: string
  license: string
  schema_version: string
}

/** POST /dictionary/lookup 요청 본문. */
export interface DictionaryLookupRequest {
  /** 검수 화면에서 지목한 문자열 하나. 위치 정보는 담지 않는다. */
  text: string
}

/**
 * POST /dictionary/lookup 응답. 계약 `components/schemas/DictionaryLookupResponse`.
 *
 * 후보 0건도 유효한 결과다(404가 아니라 빈 배열).
 */
export interface DictionaryLookupResponse {
  /** 정제된 질의 문자열 그대로(요청 text와 같음, 반향 확인용). */
  query: string
  candidates: DictionaryLookupCandidate[]
  dictionary: DictionaryAttribution
}
