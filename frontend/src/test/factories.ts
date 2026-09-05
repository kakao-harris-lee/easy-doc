/** 테스트에서 쓰는 API 응답 만들기. 필요한 필드만 덮어써서 의도를 드러낸다. */

import type {
  ConversionResponse,
  DocumentListItem,
  DocumentSourceResponse,
  SegmentMap,
  SegmentMapUnit,
  UserResponse,
  WorkspaceListItem,
} from '../api/types'
import type { AuthContextValue } from '../auth/context'
import type { DocumentSource, SourceFailure } from '../review/sourceText'
import type { WorkspaceContextValue } from '../workspace/context'

/** 로그인 사용자 표현. 기본값은 인증까지 끝난 계정이다. */
export function userResponse(overrides: Partial<UserResponse> = {}): UserResponse {
  return {
    id: 'u1',
    email: 'user@example.com',
    email_verified: true,
    identities: [],
    ...overrides,
  }
}

/**
 * 인증 컨텍스트 값. 화면 테스트가 `AuthProvider` 대신 이 값을 직접 꽂는다 —
 * `workspaceContext`와 같은 이유다(화면이 보는 것은 상태이지 그 상태를 만든 요청이 아니다).
 */
export function authContextValue(overrides: Partial<AuthContextValue> = {}): AuthContextValue {
  return {
    status: 'authenticated',
    user: userResponse(),
    signIn: () => Promise.resolve(),
    signUp: () => Promise.resolve(),
    signInWithSocialProvider: () => Promise.resolve(userResponse()),
    signOut: () => undefined,
    refreshMe: () => Promise.resolve(),
    ...overrides,
  }
}

/** 변환 조회 응답. 기본값은 "완료된 검수 대상". */
export function conversion(overrides: Partial<ConversionResponse> = {}): ConversionResponse {
  return {
    id: 'c1',
    document_id: 'd1',
    status: 'done',
    // 기본값은 붙여넣기다 — 원본 파일이 없으니 유지할 서식도 없고, TXT로 내려받는다.
    source_format: 'text',
    export_format: 'txt',
    // 계약에서 이 배열이 비어 있지 않은 원본은 PDF뿐이다 — 기본값(붙여넣기)은 늘 빈
    // 배열이고, PDF 선택 테스트는 이 필드를 직접 덮어쓴다.
    export_format_choices: [],
    format_preservation: { status: 'not_applicable', details: [] },
    easy_text: '신청은 3월 2일부터 할 수 있어요. 등록번호는 [[주민등록번호1]]이에요.',
    edited_text: null,
    reviewed_at: null,
    // 계약에서 이 키는 늘 있고 값만 null이 될 수 있다 — 목에서 키를 빼면 화면이
    // "서버가 주지 않는 값"을 상대로 통과해 버린다.
    feedback_submitted_at: null,
    // category는 서버가 주는 한국어 문자열 그대로다 — 자리표시자에 그대로 박히는
    // 복원 키라서(`[[주민등록번호1]]`) 영문 코드로 바꿀 수 없다. 범주는 2종뿐이다
    // (주민등록번호·카드번호, 2026-08-12 축소).
    masked_items: [
      { category: '주민등록번호', placeholder: '[[주민등록번호1]]', original: '900101-1234567' },
    ],
    missing_placeholders: [],
    model: 'test-model',
    provider_name: 'fake',
    input_tokens: 10,
    output_tokens: 20,
    failure_code: null,
    // 기본값은 null이다 — 대부분의 기존 테스트는 대응표를 다루지 않으므로 옛 단일
    // 에디터 경로(§6 S3 "segment_map: null 렌더")를 그대로 탄다. 대응표가 필요한
    // 테스트만 `segmentMap()`으로 명시해 덮어쓴다.
    segment_map: null,
    ...overrides,
  }
}

/** `segment_map.units`의 항목 하나. 기본값은 확인된(high) 1:1 대응이다. */
export function segmentMapUnit(overrides: Partial<SegmentMapUnit> = {}): SegmentMapUnit {
  return {
    easy_unit_index: 0,
    source_unit_indexes: [0],
    confidence: 'high',
    ...overrides,
  }
}

/**
 * 원문-쉬운 글 문단 대응표. `units`를 넘기면 그 길이가 `easy_unit_count`가 된다 —
 * 계약이 요구하는 「배열 길이 == easy_unit_count」를 목에서도 어기지 않기 위해서다.
 */
export function segmentMap(overrides: Partial<SegmentMap> = {}): SegmentMap {
  const units = overrides.units ?? [segmentMapUnit()]
  return {
    source_unit_count: 1,
    easy_unit_count: units.length,
    ...overrides,
    units,
  }
}

/** 작업 공간 목록 한 줄. */
export function workspaceItem(overrides: Partial<WorkspaceListItem> = {}): WorkspaceListItem {
  return {
    id: 'w1',
    name: '기본 작업 공간',
    created_at: '2026-08-01T00:00:00Z',
    document_count: 0,
    ...overrides,
  }
}

/**
 * 작업 공간 컨텍스트 값. 화면 테스트가 제공자 대신 이 값을 직접 꽂는다 —
 * 화면이 보는 것은 상태이지 그 상태를 만드는 요청이 아니다.
 */
export function workspaceContext(
  overrides: Partial<WorkspaceContextValue> = {},
): WorkspaceContextValue {
  return {
    workspaces: [workspaceItem()],
    currentId: 'w1',
    error: null,
    select: () => undefined,
    create: () => Promise.resolve(),
    rename: () => Promise.resolve(),
    ...overrides,
  }
}

/** 문서 목록 한 줄. */
export function documentItem(overrides: Partial<DocumentListItem> = {}): DocumentListItem {
  return {
    id: 'd1',
    title: '재난지원금 안내',
    source_format: 'text',
    char_count: 1200,
    created_at: '2026-08-07T01:00:00Z',
    retention_expires_at: '2026-09-06T01:00:00Z',
    conversion_id: 'c1',
    status: 'done',
    reviewed_at: null,
    feedback_submitted_at: null,
    ...overrides,
  }
}

/** GET /documents/{id}/source 응답. 기본값은 붙여넣기 원문. */
export function documentSource(
  overrides: Partial<DocumentSourceResponse> = {},
): DocumentSourceResponse {
  const source_text = overrides.source_text ?? '신청은 3월 2일부터 가능합니다.'
  return {
    document_id: 'd1',
    source_format: 'text',
    char_count: source_text.length,
    ...overrides,
    source_text,
  }
}

/**
 * 원문 패널에 꽂을 상태.
 *
 * 화면 테스트가 훅 대신 이 값을 직접 넘긴다 — 패널이 보는 것은 상태이지 그 상태를 만든
 * 요청이 아니다. 세 갈래를 따로 두는 이유는 §9다: 로딩·원문·실패는 서로 다른 화면이고,
 * 테스트에서도 그 셋을 헷갈리지 않게 이름으로 갈라 둔다.
 */
export function sourceReady(text = '원문입니다.'): DocumentSource {
  return { state: { status: 'ready', text }, retry: () => undefined }
}

export function sourceLoading(): DocumentSource {
  return { state: { status: 'loading' }, retry: () => undefined }
}

export function sourceFailed(
  failure: SourceFailure = 'not_found',
  retry: () => void = () => undefined,
): DocumentSource {
  return { state: { status: 'failed', failure }, retry }
}
