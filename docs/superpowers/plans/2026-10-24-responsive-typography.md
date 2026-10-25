# Responsive Typography Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 모바일과 PC의 다양한 화면 폭에서 공통 텍스트와 사용자 핵심 화면이 잘리거나 넘치지 않고 자연스럽게 표시되도록 한다.

**Architecture:** `index.css`에 재사용 가능한 타이포그래피 클래스를 정의하고 `PageHeader`와 `Button`에 우선 적용한다. 공개 콘텐츠, 변환·검수, 기록·사용량 화면은 공통 클래스를 소비하면서 각 컴포넌트 안에서 최소한의 축소·줄바꿈·배치 규칙만 추가한다.

**Tech Stack:** React 18, TypeScript 6, Tailwind CSS 4, Vitest, Testing Library, Playwright

## Global Constraints

- 수정 범위는 `frontend/`와 이 계획에 명시한 기존 테스트로 제한한다.
- 관리자 화면, 백엔드, API 계약, 색상, 브랜딩, 기능, 문서 내용은 변경하지 않는다.
- 새 검증 하네스나 Python 구현을 만들지 않는다.
- 버튼과 링크의 최소 터치 높이 44px은 유지한다.
- 표처럼 가로 공간이 필요한 콘텐츠의 국소적 가로 스크롤은 유지하되 페이지 전체 가로 스크롤은 허용하지 않는다.
- 완료 전 `npm run check`, `npm run test -- --run`, `npm run build`를 실행한다.

---

## File Structure

- `frontend/src/index.css`: 공통 `.page-title`, `.section-title`, `.body-copy`, `.supporting-copy`, `.long-text` 규칙을 제공한다.
- `frontend/src/components/PageHeader.tsx`: 업무 화면의 공통 제목·설명·대표 행동에 반응형 규칙을 적용한다.
- `frontend/src/components/ui/Button.tsx`: 모든 공통 버튼이 최소 높이를 유지하면서 긴 문구를 여러 줄로 표시하게 한다.
- `frontend/src/components/Footer.tsx`: 고정된 한 줄 표시 대신 긴 회사·연락처 정보의 줄바꿈을 허용한다.
- `frontend/src/pages/LandingPage.tsx`, `GuidePage.tsx`, `ExamplesPage.tsx`: 공개 화면 제목과 본문에 공통 규칙을 적용한다.
- `frontend/src/components/DocumentExampleCards.tsx`: 예시 카드 제목과 설명의 읽기 규칙을 맞춘다.
- `frontend/src/pages/UploadPage.tsx`, `ConversionPage.tsx`, `ReviewEditor.tsx`: 입력·상태·편집 화면의 텍스트와 컨트롤 배치를 보정한다.
- `frontend/src/pages/HistoryPage.tsx`, `UsagePage.tsx`: 긴 문서 제목과 사용량 안내의 축소 및 줄바꿈을 보정한다.
- 기존 `*.test.tsx`와 `frontend/e2e/a11y.spec.ts`: 클래스 계약과 실제 브라우저 폭을 검증한다.

### Task 1: Shared Typography and Common Controls

**Files:**
- Modify: `frontend/src/index.css:171-348`
- Modify: `frontend/src/components/PageHeader.tsx:42-89`
- Modify: `frontend/src/components/ui/Button.tsx:23-54`
- Modify: `frontend/src/components/Footer.tsx:8-93`
- Test: `frontend/src/components/PageHeader.test.tsx`
- Test: `frontend/src/components/Footer.test.tsx`

**Interfaces:**
- Produces: CSS classes `.page-title`, `.section-title`, `.body-copy`, `.supporting-copy`, `.long-text`.
- Produces: 공통 `Button`은 `size="lg"`에서도 `min-h-12`를 사용하며 텍스트 줄바꿈을 허용한다.
- Consumes: 기존 디자인 토큰과 `CONTAINER`는 그대로 사용한다.

- [ ] **Step 1: 공통 헤더와 푸터의 새 클래스 계약을 실패 테스트로 작성한다**

`PageHeader.test.tsx`의 `제목은 모바일에서만 작게 그린다` 테스트를 다음으로 교체하고 대표 행동 검증을 추가한다.

```tsx
it('제목과 설명에 공통 반응형 텍스트 규칙을 적용한다', () => {
  render(
    <MemoryRouter>
      <PageHeader {...BASE} />
    </MemoryRouter>,
  )

  expect(screen.getByRole('heading', { level: 1 })).toHaveClass('page-title')
  expect(screen.getByText(BASE.description)).toHaveClass('supporting-copy')
})

it('대표 행동은 최소 높이를 유지하면서 긴 문구를 여러 줄로 표시한다', () => {
  render(
    <MemoryRouter>
      <PageHeader {...BASE} action={{ label: '새 문서 변환', to: '/' }} />
    </MemoryRouter>,
  )

  expect(screen.getByRole('link', { name: '새 문서 변환' })).toHaveClass(
    'min-h-11',
    'whitespace-normal',
    'py-2',
  )
  expect(screen.getByRole('link', { name: '새 문서 변환' })).not.toHaveClass('h-11')
})
```

`Footer.test.tsx`의 `사업자 정보 행의 텍스트와 링크를 같은 기준선으로 맞춘다`에서 다음 기대값을 사용한다.

```tsx
expect(representative).toHaveClass('min-w-0', 'break-words')
expect(businessLookup).toHaveClass('whitespace-normal', 'break-words')
```

- [ ] **Step 2: 변경한 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/components/PageHeader.test.tsx src/components/Footer.test.tsx`

Expected: `.page-title`, `.supporting-copy`, `min-h-11`, `whitespace-normal`, `break-words` 클래스가 없어 FAIL한다.

- [ ] **Step 3: 공통 타이포그래피와 컨트롤을 구현한다**

`index.css`의 `@layer components` 시작 부분에 다음을 추가한다.

```css
.page-title {
  @apply font-extrabold tracking-tight text-foreground;
  font-size: clamp(1.5rem, 1.25rem + 1vw, 1.75rem);
  line-height: 1.3;
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.section-title {
  @apply font-extrabold text-foreground;
  font-size: clamp(1.25rem, 1.1rem + 0.6vw, 1.5rem);
  line-height: 1.35;
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.body-copy {
  font-size: clamp(0.9375rem, 0.9rem + 0.15vw, 1rem);
  line-height: 1.75;
  text-wrap: pretty;
  word-break: keep-all;
  overflow-wrap: break-word;
}

.supporting-copy {
  font-size: 0.875rem;
  line-height: 1.6;
  text-wrap: pretty;
  word-break: keep-all;
  overflow-wrap: break-word;
}

.long-text {
  min-width: 0;
  overflow-wrap: anywhere;
}
```

`PageHeader.tsx`의 상수와 클래스를 다음으로 교체한다.

```tsx
const ACTION_LINK_CLASS =
  'inline-flex min-h-11 w-full items-center justify-center gap-2 whitespace-normal rounded-md bg-primary px-4 py-2 text-center text-[15px] font-semibold text-primary-foreground no-underline transition-colors hover:bg-primary-hover sm:w-auto'
```

```tsx
<p className="supporting-copy font-semibold text-accent-foreground">{context}</p>
<h1 id={titleId} className="page-title mt-1">
  {title}
</h1>
<p className="supporting-copy mt-2 max-w-2xl text-muted-foreground">{description}</p>
```

```tsx
<Button
  type="button"
  onClick={action.onClick}
  className="min-h-11 w-full whitespace-normal px-4 py-2 text-center text-[15px] sm:w-auto"
>
```

`Button.tsx`에서 `lg`를 `min-h-12 px-5 text-base`로 바꾸고 공통 클래스에 `min-w-0 whitespace-normal py-2 text-center`를 추가한다.

```tsx
const sizes: Record<Size, string> = {
  sm: 'min-h-11 px-3 text-sm',
  md: 'min-h-11 px-4 text-sm',
  lg: 'min-h-12 px-5 text-base',
}
```

```tsx
'inline-flex min-w-0 items-center justify-center gap-2 whitespace-normal rounded-md py-2 text-center font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-50'
```

`Footer.tsx`의 공통 클래스를 다음으로 바꾼다. 짧은 정책 메뉴에는 기존 `whitespace-nowrap`을 개별적으로 유지하고 긴 사업자·고객지원 문구에는 새 규칙을 적용한다.

```tsx
const POLICY_LINK_CLASS =
  'inline-flex min-h-11 min-w-11 sm:pointer-fine:min-h-6 sm:pointer-fine:min-w-0 items-center whitespace-normal break-words text-muted-foreground underline-offset-2 hover:text-foreground hover:underline'

const INFO_TEXT_CLASS = 'inline-flex min-w-0 items-center break-words'
```

이용 가이드·이용약관·개인정보처리방침 링크에는 각각 `whitespace-nowrap`을 추가하고, `POLICY_LINK_CLASS`를 쓰는 사업자등록번호와 고객지원 링크에는 추가하지 않는다.

- [ ] **Step 4: 공통 컴포넌트 테스트를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/components/PageHeader.test.tsx src/components/Footer.test.tsx`

Expected: 모든 테스트 PASS.

- [ ] **Step 5: 공통 변경을 커밋한다**

```bash
git add frontend/src/index.css frontend/src/components/PageHeader.tsx frontend/src/components/PageHeader.test.tsx frontend/src/components/ui/Button.tsx frontend/src/components/Footer.tsx frontend/src/components/Footer.test.tsx
git commit -m "feat: add responsive typography primitives"
```

### Task 2: Public Content Pages

**Files:**
- Modify: `frontend/src/pages/LandingPage.tsx`
- Modify: `frontend/src/pages/GuidePage.tsx`
- Modify: `frontend/src/pages/ExamplesPage.tsx`
- Modify: `frontend/src/components/DocumentExampleCards.tsx`
- Modify: `frontend/src/index.css:274-347`
- Test: `frontend/src/pages/LandingPage.test.tsx`
- Test: `frontend/src/pages/ExamplesPage.test.tsx`
- Test: `frontend/src/pages/GuidePage.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `.page-title`, `.section-title`, `.body-copy`, `.supporting-copy`.
- Produces: 공개 화면의 `h1`은 `.page-title`, 주요 `h2`는 `.section-title`, 설명은 `.body-copy` 또는 `.supporting-copy`를 사용한다.

- [ ] **Step 1: 공개 화면의 공통 클래스 사용을 실패 테스트로 작성한다**

`LandingPage.test.tsx`에 다음을 추가한다.

```tsx
it('랜딩 제목과 설명에 공통 반응형 규칙을 적용한다', () => {
  renderLanding()

  expect(screen.getByRole('heading', { level: 1 })).toHaveClass('page-title')
  expect(screen.getByText(SERVICE_DEFINITION)).toHaveClass('body-copy')
  expect(screen.getByRole('heading', { name: '뜻은 그대로, 문장은 쉽게' })).toHaveClass(
    'section-title',
  )
})
```

`ExamplesPage.test.tsx`와 `GuidePage.test.tsx`에는 각각 다음 검증을 추가한다.

```tsx
expect(screen.getByRole('heading', { level: 1 })).toHaveClass('page-title')
```

- [ ] **Step 2: 공개 화면 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/pages/LandingPage.test.tsx src/pages/ExamplesPage.test.tsx src/pages/GuidePage.test.tsx`

Expected: 새 공통 클래스 기대값 때문에 FAIL한다.

- [ ] **Step 3: 공개 화면에 공통 규칙을 적용한다**

`LandingPage.tsx`에서 대표 `h1` 클래스를 다음으로 교체한다. 의미를 강제하는 기존 `<br>`은 제거해 화면 폭에 따라 자연스럽게 줄바꿈되게 한다.

```tsx
<h1 aria-label="어려운 글을 누구나 읽기 쉬운 글로" className="page-title mt-5 max-w-xl">
  어려운 글을 <span className="text-primary">누구나 읽기 쉬운 글</span>로
</h1>
<p className="body-copy mt-5 max-w-xl text-muted-foreground">{SERVICE_DEFINITION}</p>
```

랜딩의 주요 섹션 `h2`에는 `section-title mt-2`를 적용하고 기존 `text-2xl font-extrabold leading-tight text-foreground`는 제거한다. 상단 여백이 없는 제목에는 `section-title`만 적용한다. 기존 설명 문단의 `text-sm leading-6`은 `supporting-copy`로 교체하고 색상 클래스는 유지한다.

`ExamplesPage.tsx`의 두 `h1`에는 `page-title mt-2`, `예시로 시작하기`와 원문·쉬운 글 `h2`에는 `section-title`을 적용한다. 원문과 결과 본문에는 `body-copy mt-4 whitespace-pre-line text-foreground`를 적용한다.

`GuidePage.tsx`에는 직접 제목이 없으므로 `index.css`의 가이드 규칙을 다음으로 바꾼다. 사용자 정의 클래스를 `@apply`로 재사용하지 않고 `.page-title`과 같은 값을 명시한다.

```css
.guide-page .legal-document h1 {
  @apply font-extrabold tracking-tight text-foreground;
  font-size: clamp(1.5rem, 1.25rem + 1vw, 1.75rem);
  line-height: 1.3;
  text-wrap: balance;
  overflow-wrap: anywhere;
}

.guide-page .legal-document p,
.guide-page .legal-document li {
  text-wrap: pretty;
  word-break: keep-all;
  overflow-wrap: break-word;
}
```

`DocumentExampleCards.tsx`의 카드 제목에는 `section-title mt-1`, 설명에는 `supporting-copy mt-2 hidden text-muted-foreground sm:block`을 적용한다. 카드의 `h-full min-h-16`은 유지해 높이가 내용에 따라 늘어나게 한다.

- [ ] **Step 4: 공개 화면 테스트와 타입 검사를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/pages/LandingPage.test.tsx src/pages/ExamplesPage.test.tsx src/pages/GuidePage.test.tsx && npm run check`

Expected: 테스트와 검사 PASS.

- [ ] **Step 5: 공개 화면 변경을 커밋한다**

```bash
git add frontend/src/index.css frontend/src/pages/LandingPage.tsx frontend/src/pages/LandingPage.test.tsx frontend/src/pages/GuidePage.tsx frontend/src/pages/GuidePage.test.tsx frontend/src/pages/ExamplesPage.tsx frontend/src/pages/ExamplesPage.test.tsx frontend/src/components/DocumentExampleCards.tsx
git commit -m "feat: improve public page responsive text"
```

### Task 3: Upload, Conversion, and Review Screens

**Files:**
- Modify: `frontend/src/pages/UploadPage.tsx:118-123,696-1111`
- Modify: `frontend/src/pages/ConversionPage.tsx:36-45,90-277`
- Modify: `frontend/src/components/ReviewEditor.tsx:1280-1456,1661-1675`
- Test: `frontend/src/pages/UploadPage.test.tsx`
- Test: `frontend/src/pages/ConversionPage.test.tsx`
- Test: `frontend/src/components/ReviewEditor.test.tsx`

**Interfaces:**
- Consumes: Task 1의 공통 텍스트 클래스와 줄바꿈 가능한 `Button`.
- Produces: 변환·검수 상태와 입력 동작은 그대로 두고 표시 클래스만 변경한다.

- [ ] **Step 1: 긴 텍스트와 반응형 컨트롤에 대한 실패 테스트를 추가한다**

`UploadPage.test.tsx`에 다음 테스트를 추가한다. `renderPage()`는 기존 도우미를 사용한다.

```tsx
it('변환 화면 제목과 안내에 공통 반응형 규칙을 적용한다', () => {
  renderPage()

  expect(screen.getByRole('heading', { name: '문서 변환하기' })).toHaveClass('page-title')
  expect(screen.getByRole('button', { name: '쉬운 글로 바꾸기' })).toHaveClass(
    'min-h-12',
    'whitespace-normal',
  )
})
```

`ReviewEditor.test.tsx`의 기존 `원문이 있으면 원문·쉬운 글 탭으로 나누고 키보드로 옮길 수 있다` 테스트에서 `stubViewport(false)`와 기존 `render(...)` 호출 직뒤에 다음 검증을 추가한다.

```tsx
expect(screen.getByRole('heading', { name: '쉬운 글 확인', level: 1 })).toHaveClass('page-title')
expect(screen.getByRole('tab', { name: '원문' })).toHaveClass('min-h-11', 'whitespace-normal')
```

`ConversionPage.test.tsx`의 기존 변환 실패 화면 테스트에서 `await act(async () => {})` 직뒤에 다음 링크 클래스 검증을 추가한다.

```tsx
expect(screen.getByRole('link', { name: '변환 기록 보기' })).toHaveClass(
  'min-h-11',
  'whitespace-normal',
)
```

- [ ] **Step 2: 변경한 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/pages/UploadPage.test.tsx src/pages/ConversionPage.test.tsx src/components/ReviewEditor.test.tsx`

Expected: 새 공통 클래스와 `min-h-11` 기대값 때문에 FAIL한다.

- [ ] **Step 3: 변환·검수 화면의 표시를 보정한다**

`UploadPage.tsx`의 `h1`에는 `page-title mb-6`을 적용한다. 이메일 인증 배너의 `flex items-center`는 `flex flex-wrap items-start`로 바꾸고 아이콘에 `mt-0.5`를 추가해 긴 안내와 링크가 같은 행을 넘어가도 겹치지 않게 한다. 입력 도구 모음의 레벨 선택 영역에는 `min-w-0`을 유지하고 `select`의 `max-w-full`을 유지한다. 제출 버튼은 다음 클래스를 사용한다.

```tsx
className="min-h-12 w-full sm:w-fit"
```

안내 카드의 `dl` 행은 다음처럼 좁은 폭에서 세로로 쌓이게 한다.

```tsx
<div className="flex flex-col gap-1 sm:flex-row sm:gap-3">
```

`ConversionPage.tsx`의 `CARD_CLASS`는 모바일 여백을 줄인다.

```tsx
const CARD_CLASS = 'rounded-[16px] border border-border bg-card p-4 shadow-card sm:p-6 lg:p-8'
```

두 링크 상수는 동일한 최소 높이와 줄바꿈 규칙을 사용한다.

```tsx
const QUIET_LINK_CLASS =
  'inline-flex min-h-11 items-center justify-center gap-2 whitespace-normal rounded-md border border-input bg-card px-4 py-2 text-center text-[15px] font-semibold text-foreground no-underline transition-colors hover:bg-secondary'
const ACTION_LINK_CLASS =
  'inline-flex min-h-11 items-center justify-center gap-2 whitespace-normal rounded-md bg-primary px-4 py-2 text-center text-[15px] font-semibold text-primary-foreground no-underline transition-colors hover:bg-primary-hover'
```

진행·실패·누락 안내 문단에는 `supporting-copy`를 적용하되 기존 `role`, 상태 색, 조걸부 렌더링은 바꾸지 않는다.

`ReviewEditor.tsx`의 `h1`은 `page-title`을 사용한다. 헤더 설명은 `body-copy text-muted-foreground`로 바꾼다. 검수 탭의 고정 `h-11`은 다음처럼 최소 높이로 바꾼다.

```tsx
'flex min-h-11 flex-1 items-center justify-center whitespace-normal rounded-[10px] px-2 py-2 text-center text-[15px] font-semibold transition-colors sm:px-3'
```

원문·결과 패널의 `p-5`는 `min-w-0 p-4 sm:p-5`로 바꾸고 결과 편집기 힌트에는 `supporting-copy`를 적용한다. `textarea`의 17px 편집 글자 크기와 줄 간격은 읽기·쓰기 경험을 위해 유지한다.

- [ ] **Step 4: 변환·검수 테스트를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/pages/UploadPage.test.tsx src/pages/ConversionPage.test.tsx src/components/ReviewEditor.test.tsx`

Expected: 모든 테스트 PASS.

- [ ] **Step 5: 변환·검수 변경을 커밋한다**

```bash
git add frontend/src/pages/UploadPage.tsx frontend/src/pages/UploadPage.test.tsx frontend/src/pages/ConversionPage.tsx frontend/src/pages/ConversionPage.test.tsx frontend/src/components/ReviewEditor.tsx frontend/src/components/ReviewEditor.test.tsx
git commit -m "feat: improve conversion screen responsive text"
```

### Task 4: History and Usage Screens

**Files:**
- Modify: `frontend/src/pages/HistoryPage.tsx:369-540`
- Modify: `frontend/src/pages/UsagePage.tsx`
- Test: `frontend/src/pages/HistoryPage.test.tsx`
- Test: `frontend/src/pages/UsagePage.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `.body-copy`, `.supporting-copy`, `.long-text`와 공통 `PageHeader`.
- Produces: 긴 문서 제목과 작업 공간 이름이 목록 컨트롤을 밀어 내지 않는다.

- [ ] **Step 1: 긴 제목과 사용량 안내의 실패 테스트를 추가한다**

`HistoryPage.test.tsx`의 `변환 기록` describe에 다음 테스트를 추가한다. 기존 `documentItem`과 `renderPage` 도우미를 사용한다.

```tsx
it('긴 문서 제목이 목록 컨트롤을 밀어 내지 않게 줄바꿈한다', async () => {
  const longTitle = '긴 문서 제목 '.repeat(12).trim()
  vi.mocked(listDocuments).mockResolvedValue({
    items: [documentItem({ id: 'd1', title: longTitle })],
    limit: 20,
    offset: 0,
    has_more: false,
  })
  renderPage()

  expect(await screen.findByRole('link', { name: longTitle })).toHaveClass(
    'long-text',
    'min-w-0',
  )
})
```

`UsagePage.test.tsx`의 `플랜과 사용량` describe에 다음 테스트를 추가한다.

```tsx
it('크레딧 계산 안내에 공통 반응형 규칙을 적용한다', () => {
  render(page())

  expect(screen.getByText(/공백 포함 원문 100자마다/)).toHaveClass(
    'supporting-copy',
    'min-w-0',
  )
})
```

- [ ] **Step 2: 기록·사용량 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/pages/HistoryPage.test.tsx src/pages/UsagePage.test.tsx`

Expected: `.long-text`, `.supporting-copy`가 없어 FAIL한다.

- [ ] **Step 3: 목록과 사용량 안내를 보정한다**

`HistoryPage.tsx`의 `documentTitle`에서 표와 카드 모양을 다음으로 바꾼다.

```tsx
const shape = tableView
  ? 'long-text min-w-0 text-[15px] font-semibold leading-[22px]'
  : 'long-text inline-flex min-h-11 min-w-0 items-center text-[17px] font-bold leading-6'
```

모바일 카드의 제목과 보조 정보는 `pr-16` 안에서 `max-w-full`을 유지하고 보조 정보에는 `supporting-copy text-[13px] text-muted-foreground`를 적용한다. 첫 문서 변환 링크는 `h-11` 대신 `min-h-11 whitespace-normal px-4 py-2 text-center`를 사용한다.

`UsagePage.tsx`의 하단 안내는 다음 구조로 바꿔 긴 설명과 링크 그룹이 독립적으로 줄바꿈되게 한다.

```tsx
<div className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm text-muted-foreground">
  <p className="supporting-copy min-w-0 flex-1 basis-64">
    공백 포함 원문 100자마다 0.1크레딧으로 계산하며, 1자라도 남으면 올림합니다.
  </p>
  <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1">
```

사용량·남은 이용량 숫자에는 `min-w-0 break-words`를 추가하고 설명 문구에는 `supporting-copy`를 적용한다. 숫자 서식과 API 조회 로직은 바꾸지 않는다.

- [ ] **Step 4: 기록·사용량 테스트를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/pages/HistoryPage.test.tsx src/pages/UsagePage.test.tsx`

Expected: 모든 테스트 PASS.

- [ ] **Step 5: 기록·사용량 변경을 커밋한다**

```bash
git add frontend/src/pages/HistoryPage.tsx frontend/src/pages/HistoryPage.test.tsx frontend/src/pages/UsagePage.tsx frontend/src/pages/UsagePage.test.tsx
git commit -m "feat: improve history and usage responsive text"
```

### Task 5: Browser Verification and Full Regression

**Files:**
- Modify: `frontend/e2e/a11y.spec.ts`
- Verify: `frontend/src/index.css`
- Verify: all files changed in Tasks 1–4

**Interfaces:**
- Consumes: Tasks 1–4의 완성된 화면.
- Produces: 실제 브라우저에서 핵심 공개 화면의 페이지 전체 가로 넘침을 검증하는 회귀 테스트.

- [ ] **Step 1: 실제 폭에서 가로 넘침을 검사하는 E2E 테스트를 작성한다**

`frontend/e2e/a11y.spec.ts`에 다음 도우미와 테스트를 추가한다. 공개 경로만 사용하므로 기존 실 서비스 E2E 규약을 유지하면서 인증 데이터에는 의존하지 않는다.

```tsx
async function expectNoPageOverflow(page: Page): Promise<void> {
  const widths = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }))
  expect(widths.content, JSON.stringify(widths)).toBeLessThanOrEqual(widths.viewport)
}

for (const width of [320, 375, 768, 1024, 1440]) {
  test(`공개 화면은 ${width}px에서 페이지 가로 넘침이 없다`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    for (const path of ['/', '/guide', '/examples']) {
      await page.goto(path)
      await expectNoPageOverflow(page)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    }
  })
}

test('공개 화면은 200%에 해당하는 좁은 CSS 폭에서도 본문을 유지한다', async ({ page }) => {
  await page.setViewportSize({ width: 640, height: 900 })
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectNoPageOverflow(page)
  await page.setViewportSize({ width: 320, height: 900 })
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expectNoPageOverflow(page)
})
```

- [ ] **Step 2: E2E 테스트를 실행한다**

Run: `cd frontend && npm run test:e2e -- --grep "공개 화면은"`

Expected: 6개 테스트 PASS. 실제 Kotlin API·PostgreSQL·worker를 쓰는 기존 E2E 스택이 필요하면 실행 가능한 스택에서 수행하고, 실행하지 못한 이유를 완료 보고에 남긴다.

- [ ] **Step 3: 전체 프런트엔드 회귀 검증을 실행한다**

Run: `cd frontend && npm run check && npm run test -- --run && npm run build`

Expected: 세 명령 모두 종료 코드 0.

- [ ] **Step 4: 최종 diff와 범위를 확인한다**

Run: `git status --short && git diff --check && git diff --stat`

Expected: 관리자·백엔드·계약 변경이 없고 공백 오류가 없다. 현재 diff에 대한 검증이 끝난 상태여야 한다.

- [ ] **Step 5: 브라우저 검증 테스트를 커밋한다**

```bash
git add frontend/e2e/a11y.spec.ts
git commit -m "test: verify responsive public page widths"
```
