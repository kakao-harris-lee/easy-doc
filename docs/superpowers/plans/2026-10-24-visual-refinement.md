# Visual Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 파란색·노란색 브랜드 정체성을 유지하면서 공통 토큰, 공개 화면, 검수 화면의 색상 의미와 표면 위계를 일관되게 정리하고 두 테마·지정 폭·키보드·reduced motion 회귀를 검증한다.

**Architecture:** `frontend/src/index.css`에 용도 기반 선택 표면과 주행동 active 토큰을 추가하고 공통 `Button`, `Badge`, 메뉴·팝오버 표면이 이를 소비하게 한다. 공개 화면은 기존 React 구조와 Markdown 원본을 유지한 채 Tailwind 클래스와 `index.css`의 표면 규칙만 조정하고, 검수 화면은 기존 상태·편집 로직을 바꾸지 않은 채 작업 표면과 도구 모음의 시각적 위계만 바꾼다. 브라우저 검증은 기존 `theme.spec.ts`의 합성 API와 Playwright 도우미를 확장한다.

**Tech Stack:** React 18, TypeScript 6, Tailwind CSS 4, Vitest 4, Testing Library, Playwright 1.62, Node.js 22.12 이상 27 미만

## Global Constraints

- 수정 범위는 이 계획의 `frontend/` 파일과 기존 Vitest·Playwright 테스트로 제한한다.
- 화면 구조, 정보 순서, 문구, 기능, 라우팅, 입력·저장·재변환 동작, API와 데이터 형식은 변경하지 않는다.
- 관리자 화면을 재설계하지 않으며 `frontend/src/pages/admin/`은 수정하지 않는다. 공통 토큰 변경의 관리자 영향은 기존 전체 Vitest와 빌드로 회귀 확인만 한다.
- 새 런타임, 새 검증 하네스, 새 npm 의존성, Python 구현을 추가하지 않는다. 브라우저 검증은 기존 `frontend/e2e/theme.spec.ts`, `frontend/e2e/a11y.spec.ts` 구조를 사용한다.
- 노란색은 브랜드 배지·짧은 체험 혜택·일러스트 포인트에만 유지하고 선택·활성·일반 hover는 파란 `selection` 토큰을 사용한다. 경고와 완료 블록의 본문은 `text-foreground`로 유지한다.
- 기존 아이콘, 제목, 굵기, 밑줄, ARIA 역할, 노출 조건, 최소 44px 터치 대상, 본문 바로가기, 초점 복귀와 Esc 동작을 유지한다.
- 일반 본문 대비는 4.5:1 이상, 큰 텍스트와 의미 있는 컨트롤 경계·포커스·상태 아이콘 대비는 3:1 이상으로 검증한다.
- `320`, `375`, `768`, `1024`, `1440`px에서 랜딩·가이드·예시·검수 화면을 라이트·다크로 확인하고 페이지 전체 가로 넘침을 허용하지 않는다.
- 색과 테두리의 `transition-colors`만 사용하고 새 움직임은 추가하지 않는다. 기존 `prefers-reduced-motion` 규칙을 유지한다.
- 각 Task는 실패 확인 → 최소 구현 → 통과 확인 순서로 진행하고 커밋한다. 코드가 바뀌면 이전 검증 결과를 최종 diff의 통과 근거로 재사용하지 않는다.
- 완료 검증은 반드시 `cd frontend && npm run check && npm run test -- --run && npm run build`이며 세 명령 모두 종료 코드 0이어야 한다.

---

## File Structure

- `frontend/src/index.css`: `selection`, `selection-foreground`, `primary-active` 토큰, 경고·완료 본문 규칙, 가이드 아코디언과 랜딩 번짐 스타일을 제공한다.
- `frontend/src/components/ui/Button.tsx`: primary·secondary·outline·ghost의 hover·active·disabled 역할을 정의한다.
- `frontend/src/components/ui/Badge.tsx`: 파란 정보·선택 배지가 노란 브랜드 표면을 쓰지 않게 한다.
- `frontend/src/components/AppLayout.tsx`: 데스크톱·모바일 활성 메뉴와 계정 팝오버 표면을 공통 토큰에 연결한다.
- `frontend/src/components/WorkspaceMenu.tsx`, `frontend/src/components/ui/Dialog.tsx`, `frontend/src/components/TermLookupPopover.tsx`: 상위 레이어에 `popover` 표면과 기존 그림자를 적용한다.
- `frontend/src/components/DocumentExampleCards.tsx`: 카드 링크의 기본·hover·active·focus-visible 상태와 고정 테두리 두께를 정의한다.
- `frontend/src/pages/LandingPage.tsx`: 핵심 카드와 설명 카드의 강조를 분리하고 경고 본문·재작성 결과·일러스트 번짐을 조정한다.
- `frontend/src/pages/GuidePage.tsx`, `frontend/src/pages/ExamplesPage.tsx`: 기존 콘텐츠와 상태를 유지하면서 아코디언·핵심 예시·보조 표면에 공통 규칙을 적용한다.
- `frontend/src/components/ReviewEditor.tsx`: 원문·결과 작업 표면, 저장 중심 도구 모음, 경고·완료 안내의 시각 스타일만 바꾼다.
- `frontend/src/components/ReviewSupportPanel.tsx`, `frontend/src/components/ExplanationsPanel.tsx`, `frontend/src/components/IllustrationPlacementPanel.tsx`: 검수 하위 안내 블록의 본문색을 공통 경고 규칙에 맞춘다.
- 기존 `*.test.tsx`: 클래스 계약과 기존 상호작용 보존을 검증한다.
- `frontend/e2e/theme.spec.ts`: 실제 계산된 CSS, 대비, 두 테마, 다섯 폭, hover·active·disabled·focus-visible, reduced motion을 검증한다.

### Final Review Expansion (User-Approved)

- 최종 전체 브랜치 리뷰에서 `frontend/src/components/ReviewFeedback.tsx`, `frontend/src/components/ReconvertCandidateCard.tsx`, `frontend/src/components/ConversionStages.tsx`와 해당 회귀 테스트로 범위를 확장했다. 선택·현재 상태는 모두 `bg-selection text-selection-foreground`를 사용하고 기존 문구·동작·ARIA를 유지한다.
- `ReviewFeedback.test.tsx`에 선택 라디오의 클래스 계약을 추가하고, `ReconvertCandidateCard.test.tsx`와 `ConversionStages.test.tsx`에 집중 회귀 테스트를 추가한다. `Button.test.tsx`는 모든 variant의 `enabled:hover:`·`enabled:active:` 계약을 검증한다.
- 기존 `시각 개선 —` Playwright 12개 테스트 수를 유지한 채 예시 상세 경로, 선택·보조·브랜드·경고·완료 전경 대비, 보조 컨트롤 경계, 파란 포커스 링의 인접 표면 대비, disabled 저장 버튼 hover, 예시 카드·가이드 아코디언·검수 도구의 키보드 이동과 활성화를 보강한다.

### Task 1: Shared Color Roles and Common Controls

**Files:**
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/components/ui/Button.tsx`
- Modify: `frontend/src/components/ui/Badge.tsx`
- Modify: `frontend/src/components/AppLayout.tsx`
- Modify: `frontend/src/components/WorkspaceMenu.tsx`
- Modify: `frontend/src/components/ui/Dialog.tsx`
- Modify: `frontend/src/components/TermLookupPopover.tsx`
- Test: `frontend/src/components/ui/Button.test.tsx`
- Create: `frontend/src/components/ui/Badge.test.tsx`
- Test: `frontend/src/components/AppLayout.test.tsx`
- Test: `frontend/src/components/WorkspaceMenu.test.tsx`
- Test: `frontend/src/components/TermLookupPopover.test.tsx`

**Interfaces:**
- Produces: Tailwind 색상 `bg-selection`, `text-selection-foreground`, `border-selection`, `bg-primary-active`, `border-primary-active`.
- Produces: CSS 클래스 `.notice-warning`, `.notice-warning-title`, `.notice-success`, `.notice-success-title`.
- Produces: `Button`의 `variant="primary" | "secondary" | "outline" | "ghost" | "danger"`와 `size` public 타입은 그대로 유지한다.
- Consumes: 기존 `--background`, `--foreground`, `--card`, `--popover`, `--input`, `--ring`, `--warning`, `--success`와 전역 `:focus-visible`, reduced-motion 규칙.

- [ ] **Step 1: 공통 선택 색과 상호작용 클래스를 실패 테스트로 고정한다**

`Button.test.tsx`의 `describe('Button')` 안에 다음 테스트를 추가한다.

```tsx
it.each([
  ['primary', 'bg-primary', 'active:bg-primary-active'],
  ['secondary', 'bg-card', 'active:bg-muted'],
  ['outline', 'border-input', 'active:bg-muted'],
  ['ghost', 'bg-transparent', 'active:bg-muted'],
] as const)('%s 버튼은 상태별 색 역할을 사용한다', (variant, baseClass, activeClass) => {
  render(
    <Button variant={variant} disabled>
      저장
    </Button>,
  )

  const button = screen.getByRole('button', { name: '저장' })
  expect(button).toHaveClass(baseClass, activeClass, 'disabled:opacity-50')
  expect(button).toBeDisabled()
  expect(button).not.toHaveClass('hover:bg-accent')
})
```

새 `Badge.test.tsx`를 다음 내용으로 작성한다.

```tsx
import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { Badge } from './Badge'

describe('Badge', () => {
  it('파란 정보 배지는 브랜드 노란 선택 표면과 분리한다', () => {
    render(<Badge tone="primary">편집 가능</Badge>)

    const badge = screen.getByText('편집 가능')
    expect(badge).toHaveClass('bg-selection', 'text-selection-foreground')
    expect(badge).not.toHaveClass('bg-accent', 'text-accent-foreground')
  })
})
```

`AppLayout.test.tsx`의 `describe('머리말 구성')` 안에 다음 테스트를 추가한다.

```tsx
it('데스크톱과 모바일 활성 메뉴는 파란 선택 표면과 굵기를 사용한다', async () => {
  const user = userEvent.setup()
  renderLayout({}, '/history')

  const desktop = screen.getByRole('navigation', { name: '주요 메뉴' })
  const desktopCurrent = within(desktop).getByRole('link', { name: '변환 기록' })
  expect(desktopCurrent).toHaveAttribute('aria-current', 'page')
  expect(desktopCurrent).toHaveClass('bg-selection', 'font-bold', 'text-selection-foreground')
  expect(desktopCurrent).not.toHaveClass('bg-accent')

  await user.click(screen.getByRole('button', { name: '메뉴 열기' }))
  const mobile = screen.getByRole('navigation', { name: '주요 메뉴 (모바일)' })
  expect(within(mobile).getByRole('link', { name: '변환 기록' })).toHaveClass(
    'bg-selection',
    'text-selection-foreground',
  )
})
```

같은 파일의 `계정 메뉴` describe에 있는 초점 복귀 테스트에서 팝오버가 열린 직후 아래 검증을 추가한다. 기존 테스트 이름과 Esc 검증은 그대로 둔다.

```tsx
expect(screen.getByRole('button', { name: '로그아웃' }).parentElement).toHaveClass(
  'bg-popover',
  'text-popover-foreground',
  'shadow-lg',
)
```

`WorkspaceMenu.test.tsx`의 `하나의 버튼을 펼치면 작업 공간을 보여준다` 테스트에서 현재 작업 공간 버튼과 팝오버 컨테이너를 다음처럼 검증한다. 기존 `currentId: 'w2'`와 작업 공간 이름은 바꾸지 않는다.

```tsx
expect(screen.getByRole('button', { name: '민원 안내' })).toHaveClass(
  'aria-pressed:bg-selection',
  'aria-pressed:text-selection-foreground',
)
expect(screen.getByRole('group', { name: '작업 공간 목록' }).parentElement?.parentElement).toHaveClass(
  'bg-popover',
  'text-popover-foreground',
)
```

`열리면 이름 입력으로 초점이 간다` 테스트에는 다음을 추가한다.

```tsx
expect(screen.getByRole('dialog')).toHaveClass('bg-popover', 'text-popover-foreground')
```

`TermLookupPopover.test.tsx`의 `선택 후 250ms 뒤 정확히 한 번 조회하고 팝업을 띄운다`에서 마지막 기대값을 다음으로 교체한다.

```tsx
expect(screen.getByRole('dialog', { name: '쉬운 말 후보' })).toHaveClass(
  'bg-popover',
  'text-popover-foreground',
  'shadow-lg',
)
```

- [ ] **Step 2: 공통 컴포넌트 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/components/ui/Button.test.tsx src/components/ui/Badge.test.tsx src/components/AppLayout.test.tsx src/components/WorkspaceMenu.test.tsx src/components/TermLookupPopover.test.tsx`

Expected: `bg-selection`, `text-selection-foreground`, `active:bg-primary-active`, `bg-popover`가 아직 없어 새 기대값이 FAIL한다. 기존 키보드·팝오버 테스트의 실패가 새로 발생하면 구현 전에 원인을 기록하고 계획 범위 밖 동작 변경은 하지 않는다.

- [ ] **Step 3: 용도 기반 토큰과 공통 안내 블록을 구현한다**

`index.css`의 `@theme inline`에서 `--color-primary-hover` 바로 뒤와 `--color-brand-hover` 바로 뒤에 다음을 추가한다.

```css
--color-primary-active: var(--primary-active);
--color-selection: var(--selection);
--color-selection-foreground: var(--selection-foreground);
```

`:root`에서 다음 값을 사용한다. `--popover`와 `--warning-surface`는 기존 선언을 교체하고 나머지는 해당 색 그룹 옆에 추가한다.

```css
--popover: #ffffff;
--primary-active: #0f447a;
--selection: #e7f2fc;
--selection-foreground: #125493;
--warning-surface: #fff7e8;
```

`html[data-theme='dark']`에서는 다음 값을 사용한다.

```css
--popover: #243247;
--primary-active: #c2e3ff;
--selection: #183954;
--selection-foreground: #9cd4ff;
--warning-surface: #2d281f;
```

`::selection`도 브랜드 노란색이 아니라 공통 선택 표면을 소비하게 다음으로 교체한다.

```css
::selection {
  background: var(--selection);
  color: var(--foreground);
}
```

`@layer components`의 `.form-success`를 다음으로 교체하고 `.notice-*` 규칙을 바로 뒤에 추가한다. 상태 아이콘과 제목만 상태색을 쓰고 본문은 기본 전경을 상속한다.

```css
.form-success {
  @apply m-0 rounded-[10px] border border-success/25 bg-success-surface px-3 py-2 text-sm font-medium text-foreground;
}
.notice-warning {
  @apply rounded-[10px] border border-warning/25 bg-warning-surface text-foreground;
}
.notice-warning > svg,
.notice-warning-title {
  @apply text-warning;
}
.notice-success {
  @apply rounded-[10px] border border-success/25 bg-success-surface text-foreground;
}
.notice-success > svg,
.notice-success-title {
  @apply text-success;
}
```

- [ ] **Step 4: 공통 컨트롤과 상위 표면에 토큰을 최소 적용한다**

`Button.tsx`의 `variants`만 다음으로 교체한다. `ButtonProps`, 크기, loading, disabled, 포커스 링 코드는 유지한다.

```tsx
const variants: Record<Variant, string> = {
  primary: 'bg-primary text-primary-foreground hover:bg-primary-hover active:bg-primary-active',
  secondary: 'border border-input bg-card text-foreground hover:bg-secondary active:bg-muted',
  outline: 'border border-input bg-card text-foreground hover:bg-secondary active:bg-muted',
  ghost: 'bg-transparent text-foreground hover:bg-secondary active:bg-muted',
  danger: 'bg-danger text-danger-foreground hover:brightness-95 active:brightness-90',
}
```

`Badge.tsx`의 `primary` 항목만 다음으로 교체한다.

```tsx
primary: 'border-primary/25 bg-selection text-selection-foreground',
```

`AppLayout.tsx`의 `navLinkClass` 반환 클래스를 다음으로 교체하고 계정 메뉴 패널 클래스도 아래처럼 바꾼다. 모바일의 `mobileLinkClass`는 계속 `navLinkClass`를 호출하므로 별도 색 분기를 추가하지 않는다.

```tsx
return cn(
  'flex min-h-11 items-center gap-2 rounded-[10px] px-3 text-[15px] transition-colors',
  isActive
    ? 'bg-selection font-bold text-selection-foreground'
    : 'font-medium text-muted-foreground hover:bg-secondary hover:text-foreground active:bg-muted',
)
```

```tsx
className="absolute right-0 top-[calc(100%+0.5rem)] z-50 w-64 rounded-[12px] border border-border bg-popover p-3 text-popover-foreground shadow-lg"
```

`WorkspaceMenu.tsx`의 작업 공간 선택 버튼 클래스를 다음으로 교체하고 팝오버 컨테이너의 `bg-card text-card-foreground`를 `bg-popover text-popover-foreground`로 바꾼다. `aria-pressed`, 선택 로직, Check 아이콘은 유지한다.

```tsx
className="flex min-h-11 w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm transition-colors hover:bg-secondary aria-pressed:bg-selection aria-pressed:font-semibold aria-pressed:text-selection-foreground"
```

`Dialog.tsx`와 `TermLookupPopover.tsx`의 최상위 패널 클래스에서도 각각 `bg-card text-card-foreground`를 `bg-popover text-popover-foreground`로 교체한다. 기존 `shadow-lg`, 포커스 가두기, Esc, 포털과 위치 계산은 수정하지 않는다.

- [ ] **Step 5: 공통 테스트와 정적 검사를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/components/ui/Button.test.tsx src/components/ui/Badge.test.tsx src/components/AppLayout.test.tsx src/components/WorkspaceMenu.test.tsx src/components/TermLookupPopover.test.tsx && npm run check`

Expected: 지정한 모든 Vitest 테스트와 `tsc -b`, ESLint, Prettier 검사가 PASS한다.

- [ ] **Step 6: 공통 기반을 커밋한다**

```bash
git add frontend/src/index.css frontend/src/components/ui/Button.tsx frontend/src/components/ui/Button.test.tsx frontend/src/components/ui/Badge.tsx frontend/src/components/ui/Badge.test.tsx frontend/src/components/AppLayout.tsx frontend/src/components/AppLayout.test.tsx frontend/src/components/WorkspaceMenu.tsx frontend/src/components/WorkspaceMenu.test.tsx frontend/src/components/ui/Dialog.tsx frontend/src/components/TermLookupPopover.tsx frontend/src/components/TermLookupPopover.test.tsx
git commit -m "feat: separate selection and brand color roles"
```

### Task 2: Public Surfaces, Guide Accordions, and Example Cards

**Files:**
- Modify: `frontend/src/index.css`
- Modify: `frontend/src/components/DocumentExampleCards.tsx`
- Modify: `frontend/src/pages/LandingPage.tsx`
- Modify: `frontend/src/pages/GuidePage.tsx`
- Modify: `frontend/src/pages/ExamplesPage.tsx`
- Test: `frontend/src/pages/LandingPage.test.tsx`
- Test: `frontend/src/pages/GuidePage.test.tsx`
- Test: `frontend/src/pages/ExamplesPage.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `selection`, `selection-foreground`, `primary-active`, `.notice-warning`.
- Produces: `.help-disclosure[open]`은 펼친 항목에만 `selection` 표면을 적용하고, `.landing-glow`는 두 테마에서 크기·불투명도가 제한된 장식 레이어다.
- Preserves: `DocumentExampleCards({ heading = 'h3' }: { readonly heading?: 'h2' | 'h3' })`, `GuidePage`, `ExamplesPage`, `DocumentExampleArticle`의 props·상태·Markdown 데이터·링크와 텍스트.

- [ ] **Step 1: 공개 화면의 표면과 상태 클래스를 실패 테스트로 작성한다**

`LandingPage.test.tsx`의 `결과가 초안이라는 사실을 경고 블록으로 알린다`를 다음으로 교체한다.

```tsx
it('초안 경고는 주황 아이콘과 기본 본문색을 사용한다', () => {
  renderLanding()

  const notice = screen.getByRole('note')
  expect(notice).toHaveTextContent(DRAFT_NOTICE)
  expect(notice).toHaveClass('notice-warning')
  expect(notice).not.toHaveClass('text-warning')
  expect(notice.querySelector('svg')).toHaveAttribute('aria-hidden', 'true')
})
```

`변환 예시와 문서 작업 흐름을 보여 준다` 테스트 뒤에 다음을 추가한다.

```tsx
it('핵심 예시만 파란 강조를 쓰고 설명 카드와 일러스트 번짐은 낮춘다', () => {
  const { container } = renderLanding()

  const example = screen.getByRole('region', { name: '뜻은 그대로, 문장은 쉽게' })
  const fit = screen.getByRole('region', { name: '일상부터 학습과 업무까지' })
  const workflow = screen.getByRole('region', {
    name: '쉬운 글 만들기부터 문서로 저장하기까지',
  })
  const simplified = screen.getByText(/행사에 참여하려면 신청 기간 안에/).closest('figure')

  expect(example).toHaveClass('border-primary/40')
  expect(example).not.toHaveClass('shadow-card')
  expect(fit).not.toHaveClass('shadow-card')
  expect(workflow).not.toHaveClass('shadow-card')
  expect(simplified).toHaveClass('bg-selection')
  expect(simplified).not.toHaveClass('bg-brand-surface/70')
  expect(container.querySelectorAll('.landing-glow')).toHaveLength(2)
  expect(container.querySelector('.landing-glow')).toHaveClass('opacity-40', 'blur-xl')
  expect(container.querySelector('.landing-glow')).not.toHaveClass('blur-2xl')
})
```

`ExamplesPage.test.tsx`의 첫 테스트에서 각 카드 링크에 다음 기대값을 추가하고, `$category의 원문·쉬운 표현·수정 제안과 실제 변환 조건을 구분한다` 테스트의 초기 쉬운 글 상태에 아래 검증을 추가한다.

```tsx
expect(screen.getByRole('link', { name: example.title })).toHaveClass(
  'border',
  'border-border',
  'bg-card',
  'hover:border-primary',
  'hover:bg-selection',
  'active:border-primary-active',
  'active:bg-selection',
  'transition-colors',
)
expect(screen.getByRole('link', { name: example.title })).not.toHaveClass('hover:bg-accent')
```

```tsx
expect(document.getElementById('example-simplified')).toHaveClass('bg-selection')
expect(document.getElementById('example-original')).not.toHaveClass('bg-selection')
expect(screen.getByRole('button', { name: '쉬운 글', pressed: true })).toHaveClass(
  'bg-selection',
  'text-selection-foreground',
)
expect(screen.getByRole('region', { name: '예시로 변환 시작하기' })).not.toHaveClass(
  'bg-brand-surface/40',
)
```

`GuidePage.test.tsx`의 `파일 도움말을 펼치면 기존 다운로드 형식과 제약을 확인할 수 있다` 테스트를 다음으로 교체한다. 아코디언 실제 배경과 인용 본문 대비는 Task 4의 Playwright가 검사하므로 jsdom에 CSS 클래스가 없다는 잘못된 기대값은 두지 않는다.

```tsx
it('파일 도움말을 펼치면 기존 다운로드 형식과 제약을 확인할 수 있다', async () => {
  const user = userEvent.setup()
  const { container } = render(<GuidePage />)
  const text = screen.getByText('DOCX·HWPX → 같은 형식')
  const disclosure = screen.getByText('파일 형식과 다운로드').closest('details')

  expect(text).not.toBeVisible()
  expect(disclosure).toHaveClass('help-disclosure')
  expect(disclosure).not.toHaveAttribute('open')
  expect(container.querySelector('.guide-page > .legal-document > ol')).not.toHaveClass(
    'border',
    'bg-card',
  )

  await user.click(screen.getByText('파일 형식과 다운로드'))

  expect(disclosure).toHaveAttribute('open')
  expect(text).toBeVisible()
  expect(screen.getByText('붙여넣은 글·TXT·PDF → TXT')).toBeVisible()
  expect(screen.getByText('10MB 이하')).toBeVisible()
  expect(screen.getByText(/원본 서식은 .*않습니다/)).toBeVisible()

  await user.click(screen.getByText('파일 형식과 다운로드'))
  expect(text).not.toBeVisible()
})
```

- [ ] **Step 2: 공개 화면 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/pages/LandingPage.test.tsx src/pages/GuidePage.test.tsx src/pages/ExamplesPage.test.tsx`

Expected: `notice-warning`, `bg-selection`, 파란 hover·active, `.landing-glow`, 아코디언 신규 표면 기대값이 FAIL한다.

- [ ] **Step 3: 공통 공개 표면과 가이드 CSS를 구현한다**

`index.css`의 랜딩 이미지 규칙 앞에 다음을 추가한다. 이미지 파일, light/dark 전환 클래스와 대체 텍스트는 바꾸지 않는다.

```css
.landing-glow {
  @apply pointer-events-none absolute rounded-full bg-brand-surface opacity-40 blur-xl;
}
.landing-glow-start {
  @apply left-0 top-12 size-16;
}
.landing-glow-end {
  @apply right-0 bottom-10 size-24;
}
html[data-theme='dark'] .landing-glow {
  opacity: 0.18;
}
```

`.help-disclosure` 그룹과 가이드 전용 규칙을 다음으로 교체한다. `details[open]`은 브라우저 native 상태이므로 새 React 상태나 키보드 로직을 만들지 않는다.

```css
.help-disclosure {
  @apply bg-card text-foreground transition-colors;
}
.help-disclosure[open] {
  @apply bg-selection;
}
.help-disclosure > summary {
  @apply min-h-12 cursor-pointer px-4 py-3 font-semibold text-foreground transition-colors hover:text-primary;
}
.help-disclosure[open] > summary {
  @apply text-selection-foreground;
}
.help-disclosure > summary h2 {
  @apply inline text-base font-semibold;
}
.guide-page .legal-document blockquote {
  @apply my-4 rounded-[10px] border border-warning/25 bg-warning-surface px-4 py-3 text-sm text-foreground;
}
.guide-page .legal-document blockquote p {
  @apply my-0 leading-6 text-foreground;
}
.guide-page > .legal-document > ol {
  @apply my-4 list-decimal space-y-2 py-2 pl-6;
}
```

`DocumentExampleCards.tsx`의 링크 클래스만 다음으로 교체한다. `border` 두께는 모든 상태에서 1px이며 콘텐츠와 화살표는 그대로다.

```tsx
className="flex h-full min-h-16 items-center justify-between gap-3 rounded-xl border border-border bg-card p-4 transition-colors hover:border-primary hover:bg-selection active:border-primary-active active:bg-selection"
```

- [ ] **Step 4: 랜딩·가이드·예시 컴포넌트에 표면 규칙을 적용한다**

`LandingPage.tsx`에서 다음 클래스를 정확히 적용한다. `PRIMARY_LINK`에는 `active:bg-primary-active`, `SECONDARY_LINK`에는 `active:bg-muted`를 각각 추가하고 기존 길이·정렬 클래스는 유지한다.

```tsx
const PRIMARY_LINK =
  'inline-flex min-h-12 items-center justify-center gap-2 rounded-[10px] bg-primary px-5 text-[15px] font-semibold text-primary-foreground transition-colors hover:bg-primary-hover active:bg-primary-active'
const SECONDARY_LINK =
  'inline-flex min-h-12 items-center justify-center rounded-[10px] border border-input bg-card px-5 text-[15px] font-semibold text-foreground transition-colors hover:bg-secondary active:bg-muted'
```

초안 경고는 다음처럼 `notice-warning`을 쓰고 본문의 `text-warning`은 제거한다.

```tsx
<p
  role="note"
  className="notice-warning mt-5 flex max-w-xl items-start gap-2 px-4 py-3 text-[15px] font-semibold leading-6"
>
  <ShieldAlert className="mt-0.5 size-5 shrink-0" aria-hidden="true" />
  {DRAFT_NOTICE}
</p>
```

두 장식 `div`는 이미지와 같은 부모 안에 다음 순서로 둔다.

```tsx
<div aria-hidden="true" className="landing-glow landing-glow-start" />
<div aria-hidden="true" className="landing-glow landing-glow-end" />
```

랜딩 적합 문서·작업 흐름 섹션은 `rounded-[20px] border border-border bg-card p-6 md:p-8`로 통일해 `shadow-card`를 제거한다. 핵심 재작성 예시는 `overflow-hidden rounded-[20px] border border-primary/40 bg-card`, Start 섹션은 `rounded-[20px] border border-primary/40 bg-card p-6 md:p-8`로 바꿔 그림자 없이 파란 테두리만 남긴다. 재작성 후 `figure`와 제목은 다음을 사용한다.

```tsx
<figure className="bg-selection p-6 md:p-8">
  <figcaption className="flex items-center gap-2 text-sm font-semibold text-selection-foreground">
```

`GuidePage.tsx`의 아코디언 묶음만 `<div className="space-y-2">`에서 다음으로 교체한다. 기존 `topics.map`, `details`, `summary`, `h2`, `LegalDocument` 호출은 그대로다.

```tsx
<div className="divide-y divide-border border-y border-border bg-card">
```

가이드 상단 시작하기 링크에는 `transition-colors hover:bg-primary-hover active:bg-primary-active`, 예시 보기 링크에는 `transition-colors hover:bg-secondary active:bg-muted`를 추가한다.

`ExamplesPage.tsx`에서 `ExampleStart`의 섹션은 `${PANEL}`만 사용해 넓은 노란 배경을 제거한다. 쉬운 글 결과 섹션은 `${PANEL} bg-selection`, 선택된 모바일 비교 버튼은 `bg-selection text-selection-foreground`, 선택되지 않은 버튼은 `text-muted-foreground hover:bg-secondary hover:text-foreground`로 바꾼다. 원문 섹션은 `${PANEL}`을 유지한다. 가입하기·로그인 링크에도 랜딩과 같은 active 클래스를 각각 추가한다.

- [ ] **Step 5: 공개 화면 테스트와 정적 검사를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/pages/LandingPage.test.tsx src/pages/GuidePage.test.tsx src/pages/ExamplesPage.test.tsx && npm run check`

Expected: 모든 지정 테스트와 정적 검사가 PASS하며 기존 공개 문구·예시 수·아코디언 수도 그대로 PASS한다.

- [ ] **Step 6: 공개 화면 변경을 커밋한다**

```bash
git add frontend/src/index.css frontend/src/components/DocumentExampleCards.tsx frontend/src/pages/LandingPage.tsx frontend/src/pages/LandingPage.test.tsx frontend/src/pages/GuidePage.tsx frontend/src/pages/GuidePage.test.tsx frontend/src/pages/ExamplesPage.tsx frontend/src/pages/ExamplesPage.test.tsx
git commit -m "feat: refine public page color hierarchy"
```

### Task 3: Review Work Surfaces and Status Messages

**Files:**
- Modify: `frontend/src/components/ReviewEditor.tsx`
- Modify: `frontend/src/components/ReviewSupportPanel.tsx`
- Modify: `frontend/src/components/ExplanationsPanel.tsx`
- Modify: `frontend/src/components/IllustrationPlacementPanel.tsx`
- Test: `frontend/src/components/ReviewEditor.test.tsx`
- Verify: `frontend/src/components/ExplanationsPanel.test.tsx`
- Verify: `frontend/src/components/IllustrationPlacementPanel.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `selection`, `.notice-warning`, `.notice-warning-title`, `.form-success`와 중립 `Button variant="outline"`.
- Produces: 모바일 검수 `tabpanel`의 결과 표면은 `bg-selection border-primary/40`, 원문 표면은 `bg-card border-border`다.
- Preserves: `ReviewEditor`의 `conversion`, `source` props, `Feedback.kind`, 저장·다운로드·재변환·검토 API 호출, 편집 상태와 초점 추적 로직.

- [ ] **Step 1: 검수 표면·도구·상태색을 실패 테스트로 고정한다**

`ReviewEditor.test.tsx`의 `AI 초안임을 알리는 배너를 보여준다` 테스트를 다음으로 교체한다.

```tsx
it('초안 경고는 경고 표면 위에 기본 본문색을 사용한다', () => {
  render(<ReviewEditor conversion={conversion()} source={sourceFailed()} />)

  const notice = screen.getByRole('note')
  expect(notice).toHaveTextContent('AI가 만든 초안입니다')
  expect(notice).toHaveClass('notice-warning')
  expect(notice).not.toHaveClass('text-warning')
})
```

`저장했다는 사실을 두 곳에서 낭독하지 않는다` 테스트의 `success` 기대값 바로 뒤에 다음을 추가한다. 기존 낭독 횟수와 role 검증은 삭제하지 않는다.

```tsx
expect(success).toHaveClass('form-success')
expect(success).not.toHaveClass('text-success')
```

`원문을 불러왔으면 왼쪽 패널에 읽기 전용으로 보여준다` 테스트 뒤에 다음을 추가한다. 모바일 탭이 보이도록 뷰포트를 설정하고 `쉬운 글` 탭을 실제로 선택한 뒤 결과 패널을 검증한다.

```tsx
it('결과 패널만 파란 선택 표면이고 첫 도구는 항상 주행동이다', async () => {
  stubViewport(false)
  const user = userEvent.setup()
  render(<ReviewEditor conversion={conversion()} source={sourceReady('원문입니다.')} />)

  await user.click(screen.getByRole('tab', { name: '쉬운 글' }))
  const result = screen.getByRole('tabpanel', { name: '쉬운 글' })
  expect(result).toHaveClass('border-2', 'border-primary/40', 'bg-selection')
  expect(result).not.toHaveClass('bg-brand-surface')
  expect(screen.getByRole('button', { name: '검수 내용 저장' })).toHaveClass(
    'bg-primary',
    'text-primary-foreground',
  )
  expect(screen.getByRole('button', { name: '검수 내용 저장' })).not.toHaveClass('bg-secondary')
})
```

`원문이 있으면 원문·쉬운 글 탭으로 나누고 키보드로 옮길 수 있다` 기존 테스트에서 원문 탭이 선택된 상태에 다음을 추가한다.

```tsx
expect(screen.getByRole('tabpanel', { name: '원문' })).toHaveClass(
  'border',
  'border-border',
  'bg-card',
)
expect(screen.getByRole('tabpanel', { name: '원문' })).not.toHaveClass('bg-selection')
```

- [ ] **Step 2: 검수 테스트를 실행해 실패를 확인한다**

Run: `cd frontend && npm run test -- --run src/components/ReviewEditor.test.tsx`

Expected: 결과 패널의 `bg-selection`, 초안 `notice-warning`, 저장 버튼의 상시 `bg-primary`가 없어 새 테스트가 FAIL한다. 기존 저장·탭·낭독 테스트는 계속 통과해야 한다.

- [ ] **Step 3: 검수 작업 표면과 첫 주행동을 최소 구현한다**

`ReviewEditor.tsx`의 결과 패널 클래스만 다음으로 교체한다. 원문 패널은 기존 `min-w-0 rounded-[12px] border border-border bg-card p-4 sm:p-5`를 그대로 유지하고 기존 2px 결과 테두리도 유지해 레이아웃이 움직이지 않게 한다.

```tsx
className="min-w-0 rounded-[12px] border-2 border-primary/40 bg-selection p-4 sm:p-5"
```

저장 버튼의 조걸부 variant를 제거하고 다음으로 교체한다. `dirty && 'ring-2 ring-ring/40'`, disabled·loading·저장 호출은 그대로다.

```tsx
variant="primary"
```

모바일 탭의 선택 클래스는 `bg-selection text-selection-foreground`로 바꾸고 `shadow-sm`을 제거한다. 비선택 탭은 `text-muted-foreground hover:bg-secondary hover:text-foreground`를 사용한다. 탭의 role, `aria-selected`, 방향키 처리와 패널 전환은 바꾸지 않는다.

- [ ] **Step 4: 경고·완료 블록의 본문색을 공통 규칙으로 바꾼다**

`ReviewEditor.tsx`의 최상단 초안 경고는 다음 클래스를 사용하고 아이콘·`role="note"`·문구는 유지한다.

```tsx
className="notice-warning flex items-center gap-2 px-3 py-2 text-sm font-semibold sm:px-4 sm:py-3 sm:text-base"
```

같은 파일에서 `자동 검사가 일부만 이루어졌습니다…`와 `저장하면 검토할 부분을 다시 찾습니다.` 두 문단은 `notice-warning p-3 text-sm`을 사용한다. `검토 필요한 문단 …개`는 짧은 제목이므로 기존 `text-warning`을 유지한다. 위치를 찾지 못한 검토 항목 `section`은 `notice-warning p-3`, 그 `h3`는 `notice-warning-title font-semibold`를 사용하고 항목 본문은 `text-foreground`를 유지한다.

저장 충돌 블록은 다음 클래스를 적용해 제목만 주황색, 설명은 기본 전경으로 만든다.

```tsx
<div className="notice-warning mt-4 p-4">
  <p className="notice-warning-title m-0 text-sm font-semibold">
    다른 화면에서 저장한 최신 내용과 충돌했습니다.
  </p>
  <p className="m-0 mt-1 text-sm text-foreground">
```

`feedback`의 `form-success`와 `form-error`, `announce`, role 분기는 바꾸지 않는다. Task 1의 `.form-success`가 완료 표면과 초록 테두리를 유지하면서 본문을 기본 전경으로 그린다.

`ReviewSupportPanel.tsx`의 `현재 본문이 바뀌어…` 경고 문단과 비교 위치를 찾지 못한 안내 상자는 기존 여백을 유지한 채 각각 `notice-warning mt-3 p-3 text-sm`으로 바꾼다. 짧은 `현재 본문과 다름`, `비교 위치를 정확히 찾지 못했습니다.` 제목은 기존 `text-warning`을 유지한다. `ExplanationsPanel.tsx`의 dirty 안내 문단과 `IllustrationPlacementPanel.tsx`의 stale 안내 두 문단도 각각 기존 `mt-3`을 보존한 `notice-warning mt-3 p-3 text-sm`으로 교체한다. 이 파일들의 조걸부 렌더링과 API 로직은 한 줄도 바꾸지 않는다.

- [ ] **Step 5: 검수 및 하위 패널 테스트를 통과시킨다**

Run: `cd frontend && npm run test -- --run src/components/ReviewEditor.test.tsx src/components/ExplanationsPanel.test.tsx src/components/IllustrationPlacementPanel.test.tsx && npm run check`

Expected: 실제 존재하는 세 테스트 파일과 정적 검사가 PASS한다. `ReviewSupportPanel` 변경은 실제 존재하는 관련 테스트와 Task 4 Step 5의 최종 전체 Vitest로 회귀 확인한다. 저장 성공의 기존 단일 낭독, 탭 키보드 이동, 편집·다운로드·재변환 동작 검증도 모두 PASS한다.

- [ ] **Step 6: 검수 화면 변경을 커밋한다**

```bash
git add frontend/src/components/ReviewEditor.tsx frontend/src/components/ReviewEditor.test.tsx frontend/src/components/ReviewSupportPanel.tsx frontend/src/components/ExplanationsPanel.tsx frontend/src/components/IllustrationPlacementPanel.tsx
git commit -m "feat: clarify review work surface hierarchy"
```

### Task 4: Browser Accessibility, Responsive Widths, and Full Regression

**Files:**
- Modify: `frontend/e2e/theme.spec.ts`
- Verify: `frontend/e2e/a11y.spec.ts`
- Verify: all files changed in Tasks 1–3

**Interfaces:**
- Consumes: Tasks 1–3의 완성된 토큰과 화면, 기존 `mockApi`, `seedAuthenticatedTheme`, `selectTheme`, `currentTheme`, `expectNoHorizontalOverflow`, `CONVERSION_ID`.
- Produces: `시각 개선 —`으로 시작하는 12개 Playwright 회귀 테스트. 새 설정 파일·fixture 서버·브라우저 프로젝트는 만들지 않는다.

- [ ] **Step 1: 기존 테마 스위트에 대비·상태 측정 도우미를 작성한다**

`theme.spec.ts`의 `expectNoHorizontalOverflow` 뒤에 다음 함수를 추가한다. 계산된 `rgb()`만 읽고 새 외부 접근성 라이브러리는 사용하지 않는다.

```ts
function channels(color: string): [number, number, number] {
  const values = color.match(/[\d.]+/g)?.map(Number).slice(0, 3)
  if (values?.length !== 3 || values.some((value) => value === undefined)) {
    throw new Error(`RGB 색을 해석할 수 없습니다: ${color}`)
  }
  return values as [number, number, number]
}

function luminance(color: string): number {
  const linear = channels(color).map((value) => {
    const channel = value / 255
    return channel <= 0.04045 ? channel / 12.92 : ((channel + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * linear[0]! + 0.7152 * linear[1]! + 0.0722 * linear[2]!
}

function contrastRatio(foreground: string, background: string): number {
  const first = luminance(foreground)
  const second = luminance(background)
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
}

async function expectTextContrast(locator: Locator, minimum = 4.5): Promise<void> {
  const colors = await locator.evaluate((element) => {
    let current: HTMLElement | null = element
    let background = ''
    while (current !== null) {
      const candidate = getComputedStyle(current).backgroundColor
      if (candidate !== 'transparent' && candidate !== 'rgba(0, 0, 0, 0)') {
        background = candidate
        break
      }
      current = current.parentElement
    }
    return { foreground: getComputedStyle(element).color, background }
  })
  expect(colors.background).not.toBe('')
  expect(contrastRatio(colors.foreground, colors.background)).toBeGreaterThanOrEqual(minimum)
}

async function expectBorderContrast(locator: Locator): Promise<void> {
  const colors = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return { border: style.borderTopColor, background: style.backgroundColor }
  })
  expect(contrastRatio(colors.border, colors.background)).toBeGreaterThanOrEqual(3)
}

async function expectFocusRing(locator: Locator): Promise<void> {
  await locator.focus()
  await expect(locator).toBeFocused()
  const outline = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return { style: style.outlineStyle, width: style.outlineWidth, color: style.outlineColor }
  })
  expect(outline.style).toBe('solid')
  expect(outline.width).toBe('3px')
  expect(outline.color).not.toBe('rgba(0, 0, 0, 0)')
}
```

- [ ] **Step 2: 다섯 폭과 두 테마의 공개·검수 브라우저 테스트를 작성한다**

`test.describe('테마 디자인 브라우저 검증')`의 마지막 테스트 뒤, describe 종료 전에 다음을 추가한다. 각 테스트는 실제 화면을 이동하며 폭·테마·본문 대비·입력 경계를 검사한다.

```ts
for (const width of [320, 375, 768, 1024, 1440]) {
  test(`시각 개선 — 공개 화면 ${width}px 두 테마`, async ({ page }) => {
    await mockApi(page)
    await page.setViewportSize({ width, height: 900 })

    for (const theme of ['light', 'dark'] as const) {
      for (const path of ['/', '/guide', '/examples']) {
        await page.goto(path)
        await selectTheme(page, theme)
        await expect.poll(() => currentTheme(page)).toBe(theme)
        await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
        await expectNoHorizontalOverflow(page)

        if (path === '/') {
          await expectTextContrast(page.getByRole('note'))
          await expectTextContrast(page.getByText(/가정통신문부터/))
          const glowOpacity = await page.locator('.landing-glow').first().evaluate(
            (element) => getComputedStyle(element).opacity,
          )
          expect(glowOpacity).toBe(theme === 'dark' ? '0.18' : '0.4')
        }
        if (path === '/guide') {
          await expectTextContrast(page.locator('.guide-page blockquote'))
          const disclosure = page.locator('details.help-disclosure').first()
          await disclosure.locator('summary').click()
          await expect(disclosure).toHaveAttribute('open', '')
          const background = await disclosure.evaluate(
            (element) => getComputedStyle(element).backgroundColor,
          )
          expect(background).toBe(theme === 'dark' ? 'rgb(24, 57, 84)' : 'rgb(231, 242, 252)')
        }
      }
    }
  })

  test(`시각 개선 — 검수 화면 ${width}px 두 테마`, async ({ page }) => {
    await mockApi(page)
    await seedAuthenticatedTheme(page, 'light')
    await page.setViewportSize({ width, height: 900 })

    for (const theme of ['light', 'dark'] as const) {
      await page.goto(`/conversions/${CONVERSION_ID}`)
      await selectTheme(page, theme)
      await expect(page.getByRole('heading', { name: '쉬운 글 확인' })).toBeVisible()
      await expect.poll(() => currentTheme(page)).toBe(theme)
      await expectNoHorizontalOverflow(page)
      await expectTextContrast(page.getByRole('note'))
      await expectTextContrast(page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)'))
      await expectBorderContrast(page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)'))
      await expectFocusRing(page.getByRole('button', { name: '검수 내용 저장' }))
    }
  })
}
```

- [ ] **Step 3: hover·active·disabled·키보드·모바일 메뉴 테스트를 작성한다**

같은 describe 안에 다음 테스트를 추가한다. 카드 hover는 크기가 아니라 색만 바뀌는지 측정하고 메뉴 Esc와 포커스 복귀는 기존 동작을 실제 키보드로 확인한다.

```ts
test('시각 개선 — 상호작용 상태와 키보드 초점이 유지된다', async ({ page }) => {
  await mockApi(page)
  await page.setViewportSize({ width: 1024, height: 900 })
  await page.goto('/')

  const primary = page.getByRole('link', { name: /체험하기/ })
  await primary.hover()
  await page.mouse.down()
  await expect(primary).toHaveCSS('background-color', 'rgb(15, 68, 122)')
  await page.mouse.up()

  await page.goto('/examples')
  const card = page.locator('main a[href^="/examples/"]').first()
  const before = await card.boundingBox()
  await card.hover()
  const after = await card.boundingBox()
  expect(after).toEqual(before)
  await expect(card).toHaveCSS('background-color', 'rgb(231, 242, 252)')
  await expectFocusRing(card)

  await page.goto('/guide')
  await expectFocusRing(page.locator('details.help-disclosure summary').first())

  await seedAuthenticatedTheme(page, 'light')
  await page.goto('/history')
  const current = page
    .getByRole('navigation', { name: '주요 메뉴', exact: true })
    .getByRole('link', { name: '변환 기록' })
  await expect(current).toHaveAttribute('aria-current', 'page')
  await expect(current).toHaveCSS('background-color', 'rgb(231, 242, 252)')

  const account = page.getByRole('button', { name: '계정 메뉴' })
  await account.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(account).toBeFocused()

  await page.setViewportSize({ width: 375, height: 900 })
  await page.goto('/history')
  const menu = page.getByRole('button', { name: '메뉴 열기' })
  await menu.click()
  const mobileCurrent = page
    .getByRole('navigation', { name: '주요 메뉴 (모바일)' })
    .getByRole('link', { name: '변환 기록' })
  await expect(mobileCurrent).toHaveCSS('background-color', 'rgb(231, 242, 252)')
  await page.keyboard.press('Escape')
  await expect(menu).toBeFocused()

  await page.goto(`/conversions/${CONVERSION_ID}`)
  const editor = page.getByLabel('쉬운 글 결과 (고칠 수 있습니다)')
  await editor.fill('저장 중 disabled 상태를 확인하는 수정본입니다.')
  let releaseSave: (() => void) | undefined
  const saveGate = new Promise<void>((resolve) => {
    releaseSave = resolve
  })
  await page.route(`**/conversions/${CONVERSION_ID}`, async (route) => {
    if (route.request().method() !== 'PUT') {
      await route.continue()
      return
    }
    await saveGate
    await json(route, {
      ...CONVERSION,
      edited_text: '저장 중 disabled 상태를 확인하는 수정본입니다.',
      reviewed_at: '2026-10-24T00:00:00Z',
    })
  })
  const save = page.getByRole('button', { name: '검수 내용 저장' })
  await save.click()
  await expect(save).toBeDisabled()
  await expect(save).toHaveCSS('opacity', '0.5')
  await expect(save).toHaveCSS('cursor', 'not-allowed')
  releaseSave?.()
  await expect(save).toBeEnabled()
})
```

- [ ] **Step 4: reduced motion 테스트를 작성하고 새 브라우저 테스트를 통과시킨다**

같은 describe 안에 다음 테스트를 추가한다.

```ts
test('시각 개선 — reduced motion에서 색 전환만 즉시 끝난다', async ({ page }) => {
  await mockApi(page)
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/examples')
  const card = page.locator('main a[href^="/examples/"]').first()
  await card.hover()

  const durations = await card.evaluate((element) =>
    getComputedStyle(element)
      .transitionDuration.split(',')
      .map((value) => Number.parseFloat(value) * (value.includes('ms') ? 1 : 1000)),
  )
  expect(durations.every((duration) => duration <= 0.001)).toBe(true)
  expect(await card.evaluate((element) => getComputedStyle(element).animationName)).toBe('none')
})
```

Run: `cd frontend && npm run test:e2e -- --grep "시각 개선"`

Expected: 공개 5개 + 검수 5개 + 상호작용 1개 + reduced motion 1개, 총 12개 테스트 PASS. `theme.spec.ts`의 합성 API만 사용하므로 실제 LLM·결제·외부 서비스 호출이 없다. CSS 대비 실패 시 기준을 낮추지 말고 Tasks 1–3의 해당 토큰이나 전경 클래스를 수정한 뒤 그 Task의 단위 테스트부터 다시 실행한다.

- [ ] **Step 5: 기존 키보드·모션 회귀와 전체 프런트엔드 검증을 실행한다**

Run: `cd frontend && npm run test:e2e -- --grep "E14|E15|E18"`

Expected: 3개 기존 Playwright 테스트 PASS. 이 파일은 실제 Kotlin API·PostgreSQL·worker를 사용하는 기존 규약이므로 실행 스택이 없으면 PASS로 기록하지 않고 미실행 사유를 완료 보고에 남긴다.

Run: `cd frontend && npm run check && npm run test -- --run && npm run build`

Expected: 세 명령이 순서대로 모두 종료 코드 0이다. 여기에는 관리자를 포함한 기존 Vitest 전체와 SSR 랜딩 빌드가 포함된다.

- [ ] **Step 6: 최종 diff와 설계 범위를 확인한다**

Run: `git status --short && git diff --check && git diff --stat && git diff -- frontend/src/pages/admin contracts backend-kotlin`

Expected: 공백 오류가 없고 마지막 diff 명령 출력이 비어 있다. 변경 파일은 Tasks 1–4의 Files에만 있고 문구·라우팅·API·관리자 변경이 없다. Task 4의 새 Playwright 테스트는 12개이며 최종 diff에 대해 Step 5를 다시 통과한 상태다.

- [ ] **Step 7: 브라우저 회귀 테스트를 커밋한다**

```bash
git add frontend/e2e/theme.spec.ts
git commit -m "test: verify visual refinement across themes"
```

---

## Spec Coverage Checklist

- 공통 색상 역할·선택 표면·상위 표면·버튼·배지·입력 경계: Task 1, Task 4.
- 데스크톱·모바일 헤더 활성 메뉴와 중립 hover: Task 1, Task 4.
- 경고·완료 블록의 상태 아이콘·제목과 기본 본문색: Tasks 1–3, Task 4.
- 가이드 구분선 중심 아코디언, 펼침 선택 표면, 경고 인용, 순서 목록: Task 2, Task 4.
- 랜딩 유일 주행동·보조 링크, 핵심 예시·Start와 설명 카드 위계, 파란 결과 표면, 일러스트 번짐: Task 2, Task 4.
- 랜딩·예시 공유 카드의 기본·hover·active·focus-visible 및 고정 크기: Task 2, Task 4.
- 예시 핵심 결과와 보조 표면, 모바일 원문·쉬운 글 선택 상태: Task 2.
- 검수 원문·결과 표면, 단일 주행동 도구 모음, 저장 완료·검수 경고, 입력 포커스·disabled: Task 3, Task 4.
- 두 테마 대비, 320·375·768·1024·1440px, 키보드·Esc·초점 복귀, reduced motion, 전체 정적 검사·Vitest·빌드: Task 4.
