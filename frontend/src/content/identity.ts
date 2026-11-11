/** 머리말·탭 제목·구조화 데이터에 쓰는 서비스명. 문구가 갈라지지 않게 한곳에서 읽는다. */
export const SERVICE_NAME = 'EASY-DOC AI'
/**
 * 검색 결과에서 서비스의 용도를 설명하는 제목. 서비스명보다 찾는 말을 앞에 둔다 —
 * 검색 결과에서 잘리는 뒤쪽에 브랜드가 남아도 무엇을 하는 서비스인지는 읽힌다.
 * `index.html`의 title·og:title·twitter:title도 이 문장과 같다(`shell-index.test.ts`).
 */
export const SERVICE_PAGE_TITLE = `쉬운 글 변환 AI · 공공기관 안내문, HWPX·DOCX·PDF 편집 | ${SERVICE_NAME}`
export const GUIDE_PAGE_TITLE = `쉬운 글 변환 이용 가이드 | ${SERVICE_NAME}`
export const GUIDE_DESCRIPTION =
  '무료 체험부터 읽기 수준 선택, 원문 비교와 수정, HWPX·DOCX 내려받기까지 EASY-DOC AI 이용 방법을 확인하세요.'
/** 서비스 정체성 문장. 화면마다 변형 없이 이 값을 쓴다. */
export const SERVICE_DEFINITION =
  '어려운 글을 짧은 문장과 쉬운 표현으로 다시 씁니다. 내가 읽을 때도, 다른 사람에게 전할 때도 활용하세요.'
/**
 * 랜딩의 검색 결과 설명문. 정체성 문장으로 시작해 구체적인 쓰임 한 문장을 더한다.
 *
 * 화면에 그리는 문장은 `SERVICE_DEFINITION` 그대로다(DESIGN §6.0) — 이 값은
 * description 계열 메타에만 쓰고, 검색 결과에서 잘리지 않는 앞 문장은 둘이 같다.
 */
export const LANDING_META_DESCRIPTION = `${SERVICE_DEFINITION} 가정통신문·공공기관 안내문·고객 안내문을 쉬운 말로 고치고, HWPX·DOCX·PDF 문서를 원문과 비교하며 다듬을 수 있습니다.`
/** 로그인·가입 소개 영역의 짧은 제목. */
export const AUTH_INTRO_TITLE = '어려운 글을 누구나 읽기 쉬운 글로'
/** 초안 고지 문장. */
export const DRAFT_NOTICE =
  'AI가 다시 쓴 글은 초안입니다. 사용하기 전에 중요한 내용과 날짜·금액·조건이 원문과 맞는지 확인해 주세요.'
