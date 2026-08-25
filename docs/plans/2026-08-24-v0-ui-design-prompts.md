# Easy-Read AI v0.app 디자인 프롬프트

- 관련 기획: [`2026-08-24-v0-ui-ux-redesign-plan.md`](2026-08-24-v0-ui-ux-redesign-plan.md)
- 사용법: 같은 v0 프로젝트에서 아래 프롬프트를 0 → 1 → 2 → 3 → 4 순서로 입력한다.
- 목적: 디자인 프로토타입. 실제 API, 데이터베이스, LLM, 결제 연동은 하지 않는다.

v0 공식 가이드처럼 전체 제품을 한 번에 생성하지 않고 공통 시스템과 역할별 화면을 점진적으로 만든다. 각 단계가 끝날 때 미리보기를 검토하고 다음 프롬프트를 넣는다.

## Prompt 0 — 공통 기반과 디자인 시스템

```text
You are designing a high-fidelity Korean B2G SaaS prototype called “Easy-Read AI”. It helps public-sector and welfare staff turn complex administrative text into easy-to-read Korean, then review the AI draft before publishing.

Create the shared visual system and application shells only. Do not build backend logic. Do not call any real API, LLM, payment provider, or database. Use realistic local mock data and deterministic UI states.

Technical constraints:
- React 18 + TypeScript components that can be moved into an existing Vite application
- React Router style client-side navigation
- No Next.js server components, route handlers, server actions, or Vercel-only runtime dependencies
- Prefer accessible semantic HTML and reusable components
- If you use Tailwind or shadcn/ui for the prototype, isolate their tokens and components so the design can later be ported to plain CSS
- Korean UI copy only; code identifiers may be English

Brand and visual direction:
- Calm, trustworthy public-service document tool; not a flashy consumer AI app
- Bright background, deep teal or navy primary color, restrained green/yellow/red status colors
- Pretendard with Korean system font fallbacks
- Body text at least 16px, 17px for core work screens, line-height at least 1.6
- 8–12px radii, minimal shadows, clear borders and generous whitespace
- No glassmorphism, neon gradients, 3D charts, decorative AI sparkles, or excessive animation
- Never rely on color alone for status

Accessibility requirements:
- KWCAG 2.2-minded keyboard navigation, visible focus, skip link, landmarks, labels, error association, and screen-reader status text
- Text contrast at least 4.5:1
- Icon-only buttons require an accessible name and visible tooltip
- Respect prefers-reduced-motion
- Desktop, tablet, and 360px mobile layouts

Create two intentionally separate shells:
1. User app shell: logo, “새 변환”, “변환 기록”, “요금 및 사용량”, optional “팀 관리”, account menu, current workspace, remaining-token chip.
2. Internal admin shell: visually distinct “운영자” badge and navigation for dashboard, customers, subscriptions, usage/cost, conversions, token adjustments, notices, audit log. Never show admin navigation in the user shell.

Create a small design-system page showing colors, typography, spacing, buttons, fields, tabs, badges, progress, tables, cards, dialogs, toast/status messages, empty states, skeletons, and error states. All examples should use Korean copy relevant to Easy-Read AI.
```

## Prompt 1 — 사용자 페이지

```text
Continue the existing Easy-Read AI project and keep its shared design system. Build the complete authenticated user experience with realistic Korean mock data. Do not connect real APIs.

User persona:
- Staff at a welfare center, school, community center, or district office
- Not necessarily familiar with AI terminology
- Needs to transform, review, save, and download public information safely

Create these responsive screens:

1. User dashboard
- Primary CTA: “새 문서 변환”
- Usage card: “184 / 200 변환 토큰”, progress bar, “다음 갱신 2026.09.01”
- Short explanation: 1 token = 1,000 Korean characters including spaces
- Five recent documents with title, workspace, status, updated time, and tokens used
- One contextual warning card only when an action is needed

2. New conversion screen
- Desktop two-column layout: input 60%, settings and usage estimate 40%; single-column mobile layout
- Explicit choice between “글 붙여넣기” and “파일 올리기”
- Textarea counter up to 4,000 characters; file types docx, pdf, hwpx; 10MB limit
- Settings expressed in plain Korean, not prompt-engineering terms: target reader, easy-reading level, “짧은 문장”, “어려운 말 풀이”, “중요 정보 유지”
- Advanced settings collapsed by default
- Privacy notice that resident-registration and card numbers are masked before AI processing, plus a warning to avoid other personal information
- Sticky estimate card: characters, expected conversion tokens, current balance, expected remaining balance
- Main button “쉬운 글로 바꾸기”
- Include validation error, insufficient balance, uploading, and disabled states without losing the entered text

3. Processing screen
- Accessible step status: 접수됨 → 개인정보 가림 → 쉬운 글 변환 → 결과 준비
- Calm progress UI, elapsed time, and a message that the user can return via conversion history
- Include delayed and failed states with clear next actions

4. Review screen
- The first message must be “AI가 만든 초안입니다 — 반드시 검토 후 사용하세요.”
- Desktop split view with read-only original on the left and editable easy-text result on the right; stacked mobile layout
- Show unsaved changes, last saved time, and tokens used
- Supporting panels for easy-word recommendations and masked personal information
- Actions: save review, download DOCX, download HWPX, download TXT
- Basic-plan locked HWPX state must explain why and link to plan comparison; do not use a deceptive disabled button alone
- Include missing-placeholder warning and download error states

5. Conversion history
- Search, workspace, status, and date filters
- Table on desktop and readable cards on mobile
- Statuses: 대기 중, 변환 중, 변환 완료, 변환 실패
- Open, download, and delete actions; destructive delete confirmation with document title
- Empty, loading, error, and pagination/load-more states

6. Billing and usage
- Current Basic plan, monthly/annual billing badge, renewal date, payment method summary
- Usage breakdown by workspace and recent token ledger
- Invoice/estimate/tax-document download list using mock documents
- Plan comparison entry point and clear cancellation path

7. Account settings
- Profile, notification preferences, retention-policy explanation, sign out, and account deletion entry point

Keep the user flow focused on: text input → understandable conversion settings → usage estimate → AI processing → easy-text review → download. Never display raw system prompts, provider names, API keys, or LLM input/output token costs to the user.
```

## Prompt 2 — 과금 정책·요금 페이지

```text
Continue the same Easy-Read AI design system. Create a public pricing page and authenticated subscription-management views for Korean public-sector organizations. Use mock interactions only.

Pricing rules:
- Product billing unit is “변환 토큰”. 1 token = 1,000 characters including spaces, rounded up.
- “페이지 상당량” is only a comparison aid. 1 page equivalent = 2,000 characters = 2 conversion tokens.
- Actual deduction is based on extracted character count, never the physical page count.
- Annual prepayment is 20% cheaper than twelve monthly payments.

Plans:
- Basic: small welfare centers and schools; 100 page equivalents / 200 tokens per month; ₩99,000 monthly; ₩950,400 annually, equivalent to ₩79,200 per month; text conversion and easy-word dictionary recommendations.
- Pro: community centers and district-office teams; 500 page equivalents / 1,000 tokens per month; ₩290,000 monthly; ₩2,784,000 annually, equivalent to ₩232,000 per month; all Basic features plus HWPX download.
- Enterprise: city/province integrated departments; unlimited* or negotiated usage; from ₩1,000,000 per month; custom quote; all Pro features plus shared department accounts and a “CSAP 대응 옵션” label. Do not claim that CSAP certification has already been acquired.

Public pricing page requirements:
- Header, concise value proposition, monthly/annual toggle, visible “20% 할인” badge
- Three pricing cards with target customer, allowance, price, major features, and CTA
- Make Pro visually recommended without making Basic look unusable
- Enterprise CTA is “도입 및 견적 문의”
- Immediately below cards, explain token/page conversion with the example “2,450자 문서 = 3토큰”
- Feature comparison table including text conversion, dictionary, HWPX, shared accounts, security consultation, support, and billing documents
- Clearly distinguish “현재 제공”, “도입 예정”, and “문의” feature states. The prototype must not imply that unimplemented features are currently available.
- Current product scope supports HWPX export, not legacy .hwp and not a verified promise of preserving the original layout. Label the feature “HWPX 내려받기” unless a future implementation is explicitly selected.
- FAQ covering actual deduction, unused-token rollover, overage, plan changes, cancellation/refund, tax invoice, and fair-use policy
- Show “정책 확정 필요” badges for VAT, rollover, overage, refund, and grace-period items rather than inventing rules
- Footer area for company and transaction-condition disclosures

Authenticated subscription views:
- Current plan and renewal date
- Remaining allowance and recent deductions
- Monthly versus annual change preview
- Payment method, billing contact, estimate/invoice/tax-document list
- Cancellation path that is as easy to find as upgrade
- Payment-failed, cancellation-scheduled, expired, and Enterprise-manual-contract states

Accessibility:
- Billing toggle must be a real labeled control
- Prices must have explicit billing periods in text, not visual placement only
- Comparison table must remain understandable with a screen reader and on 360px mobile
- Footnotes must be linked to the exact plan claim they qualify
```

## Prompt 3 — 운영자 관리자 페이지

```text
Continue the same Easy-Read AI project, but use the separate internal admin shell. Build a high-density yet accessible operations prototype with Korean mock data. Never expose document source text, converted text, masked values, passwords, API keys, or payment-card details.

Create these responsive admin screens:

1. Operations dashboard
- KPI cards: 활성 기관 38, 유료 구독 31, 이번 달 18,420 변환 토큰, 변환 성공률 97.8%
- 14-day conversion success/failure trend
- Service-token usage versus estimated LLM cost trend; distinguish “변환 토큰” from “LLM 입출력 토큰”
- Action queues: failed payments, low-balance customers, delayed conversions, repeated provider errors
- Every chart must have a plain-text summary or accessible data table

2. Customer organizations
- Searchable/filterable table: organization, plan, contract status, owner email, members, token balance, renewal date
- Customer detail tabs: overview, subscription, usage, members, token ledger, audit history
- Do not include a button to read customer documents

3. Subscriptions and billing
- Filters for monthly/annual/manual Enterprise contracts and active/past-due/canceling/expired status
- Failed-payment queue with retry status and customer-contact history
- Quote requests and billing-document status
- Clear loading, empty, permission-denied, and partial-data states

4. Usage and cost
- Date, organization, plan, and provider filters
- Aggregates for conversion tokens, LLM input tokens, LLM output tokens, call count, estimated cost, and two-call correction rate
- Explain all units in tooltips and table headers
- Export action should open a confirmation containing filters and row count

5. Conversion operations
- Table: conversion ID, organization, status, queued time, processing duration, character count, conversion tokens, LLM calls, failure code
- No source or result text
- Detail drawer with timeline, safe metadata, retry eligibility, and correlation ID
- Retry must require confirmation and explain that duplicate token charging is prevented

6. Token adjustments
- Customer search, current balance, amount increase/decrease, mandatory reason, optional ticket number
- Confirmation dialog showing organization and before/after balance
- Success state with immutable audit-log ID
- Recent adjustment ledger with operator, reason, timestamp, and before/after values

7. Notices and audit log
- Notice draft/schedule/publish states and target audience
- Audit filters by operator, action, target type, and date
- Audit rows must be append-only in the UI and must not offer edit/delete

Use compact tables on desktop and preserve usability on tablet. On small screens, prioritize search, critical status, and safe primary actions. Destructive or financially meaningful actions require a confirmation dialog and never use optimistic success.
```

## Prompt 4 — 최종 UX·접근성 검토

```text
Audit and refine every screen created in this Easy-Read AI prototype. Do not add new product features.

Check and fix:
- User and admin navigation are completely separated
- The primary user journey remains text/file input → conversion settings → token estimate → processing → review → download
- Korean wording avoids unexplained AI and billing jargon
- “변환 토큰”, “LLM 토큰”, and “페이지 상당량” are never confused
- Monthly and annual prices are mathematically correct and the annual discount is exactly 20%
- Enterprise says “CSAP 대응 옵션”, never “CSAP 인증 완료”
- AI-draft warning appears before editable results
- Admin views never reveal document bodies or masked personal values
- Every page has loading, empty, error, and permission/insufficient-balance states where relevant
- Every field has a persistent label and associated help/error text
- Keyboard order, visible focus, skip links, dialogs, tables, live regions, and reduced motion are accessible
- No status is color-only; contrast is at least 4.5:1
- 360px mobile, tablet, 1440px desktop, and 200% zoom layouts do not clip primary actions
- Cancellation is as easy to find as upgrade
- No real API, LLM, payment, or database calls exist
- No Next.js-only runtime dependency blocks migration to React 18 + TypeScript + Vite

At the end, add a review page listing every screen, state, responsive breakpoint, and accessibility behavior implemented. Also list any unresolved policy decisions without inventing answers.
```

## v0 결과 검토 체크리스트

- [ ] 사용자/관리자 셸이 확실히 분리됐는가?
- [ ] 현재 제품의 4,000자·10MB·docx/pdf/hwpx 범위를 지켰는가?
- [ ] `AI 초안`과 사람 검수가 핵심으로 보이는가?
- [ ] 실행 전에 예상 토큰, 실행 후 잔액을 알 수 있는가?
- [ ] Basic/Pro/Enterprise 가격과 연 할인 계산이 정확한가?
- [ ] Enterprise 보안을 인증 완료처럼 과장하지 않았는가?
- [ ] 빈 상태·오류·지연·잔액 부족·권한 없음이 있는가?
- [ ] 관리자 화면에 원문이나 변환문이 없는가?
- [ ] 모바일과 키보드만으로 주요 흐름을 완료할 수 있는가?
- [ ] 실제 연동 없이 목 데이터만 사용하는가?
