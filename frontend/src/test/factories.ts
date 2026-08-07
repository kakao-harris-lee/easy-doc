/** 테스트에서 쓰는 API 응답 만들기. 필요한 필드만 덮어써서 의도를 드러낸다. */

import type { ConversionResponse, DocumentListItem } from '../api/types'

/** 변환 조회 응답. 기본값은 "완료된 검수 대상". */
export function conversion(overrides: Partial<ConversionResponse> = {}): ConversionResponse {
  return {
    id: 'c1',
    document_id: 'd1',
    status: 'done',
    easy_text: '신청은 3월 2일부터 할 수 있어요. 전화번호는 [[전화번호1]]이에요.',
    edited_text: null,
    reviewed_at: null,
    masked_items: [{ category: 'phone', placeholder: '[[전화번호1]]', original: '010-1234-5678' }],
    missing_placeholders: [],
    model: 'test-model',
    provider_name: 'fake',
    input_tokens: 10,
    output_tokens: 20,
    failure_code: null,
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
    ...overrides,
  }
}
