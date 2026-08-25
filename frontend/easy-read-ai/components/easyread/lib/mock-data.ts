/**
 * Deterministic local mock data for the Easy-Read AI prototype.
 * No network, no LLM, no persistence — purely for UI states.
 */

export type ConversionStatus =
  | 'draft' // AI 초안 검토 대기
  | 'review' // 검토 중
  | 'published' // 발행 완료
  | 'failed' // 변환 실패

export const conversionStatusLabel: Record<ConversionStatus, string> = {
  draft: '검토 대기',
  review: '검토 중',
  published: '발행 완료',
  failed: '변환 실패',
}

export const conversionStatusTone: Record<
  ConversionStatus,
  'neutral' | 'info' | 'success' | 'danger' | 'warning'
> = {
  draft: 'warning',
  review: 'info',
  published: 'success',
  failed: 'danger',
}

export interface Conversion {
  id: string
  title: string
  category: string
  status: ConversionStatus
  author: string
  createdAt: string
  tokens: number
  readingGrade: string // 예: '초등 4학년'
}

export const conversions: Conversion[] = [
  {
    id: 'CV-2024-0417',
    title: '2024년 청년 월세 특별지원 안내문',
    category: '주거복지',
    status: 'review',
    author: '김서연',
    createdAt: '2024-06-18 14:22',
    tokens: 1240,
    readingGrade: '초등 5학년',
  },
  {
    id: 'CV-2024-0416',
    title: '기초생활수급자 의료급여 자격 변경 통지',
    category: '의료복지',
    status: 'published',
    author: '이준호',
    createdAt: '2024-06-18 11:05',
    tokens: 2010,
    readingGrade: '초등 4학년',
  },
  {
    id: 'CV-2024-0415',
    title: '노인 일자리 및 사회활동 지원사업 모집 공고',
    category: '고용',
    status: 'draft',
    author: '박민지',
    createdAt: '2024-06-17 16:48',
    tokens: 1785,
    readingGrade: '중학 1학년',
  },
  {
    id: 'CV-2024-0414',
    title: '재난적 의료비 지원 신청 서류 안내',
    category: '의료복지',
    status: 'failed',
    author: '정우성',
    createdAt: '2024-06-17 09:31',
    tokens: 0,
    readingGrade: '-',
  },
  {
    id: 'CV-2024-0413',
    title: '장애인 활동지원 급여 신청 방법',
    category: '장애인복지',
    status: 'published',
    author: '김서연',
    createdAt: '2024-06-16 13:12',
    tokens: 1560,
    readingGrade: '초등 4학년',
  },
  {
    id: 'CV-2024-0412',
    title: '한부모가족 아동양육비 지원 대상 확대 안내',
    category: '아동·가족',
    status: 'published',
    author: '이준호',
    createdAt: '2024-06-15 10:47',
    tokens: 1330,
    readingGrade: '초등 5학년',
  },
]

export const SAMPLE_SOURCE_TEXT = `제3조(지원대상) ① 이 지침에 따른 지원대상은 「국민기초생활 보장법」 제7조제1항제1호에 따른 생계급여 수급자로서, 신청일 현재 주민등록상 주소지를 관할 시·군·구에 두고 실제 거주하는 자로 한다.
② 제1항에도 불구하고 부양의무자의 소득·재산 기준을 초과하는 경우에는 지원대상에서 제외할 수 있다. 다만, 부양의무자가 「장애인복지법」에 따른 중증장애인인 경우에는 그러하지 아니하다.`

export const SAMPLE_EASY_TEXT = `누가 신청할 수 있나요?
· 기초생활보장 생계급여를 받는 분이 신청할 수 있어요.
· 신청하는 날, 우리 지역(시·군·구)에 주민등록이 되어 있고 실제로 살고 있어야 해요.

이런 경우에는 지원을 못 받을 수 있어요
· 가족(부양의무자)의 소득이나 재산이 기준보다 많으면 지원 대상에서 빠질 수 있어요.
· 다만, 그 가족이 중증장애인이라면 계속 지원을 받을 수 있어요.`

// ── Billing / usage ────────────────────────────────────────────────
export const usageSummary = {
  plan: '표준형 (기관)',
  tokenBalance: 128_400,
  tokenMonthlyQuota: 300_000,
  renewsOn: '2024-07-01',
  monthlySpend: 340_000, // KRW
  seats: { used: 12, total: 20 },
}

export const monthlyUsage = [
  { month: '1월', tokens: 182_000 },
  { month: '2월', tokens: 201_500 },
  { month: '3월', tokens: 245_000 },
  { month: '4월', tokens: 219_000 },
  { month: '5월', tokens: 268_000 },
  { month: '6월', tokens: 171_600 },
]

export const invoices = [
  { id: 'INV-2406', period: '2024년 6월', amount: 340_000, status: 'paid' },
  { id: 'INV-2405', period: '2024년 5월', amount: 340_000, status: 'paid' },
  { id: 'INV-2404', period: '2024년 4월', amount: 280_000, status: 'paid' },
]

// ── Team ───────────────────────────────────────────────────────────
export interface Member {
  id: string
  name: string
  email: string
  role: '관리자' | '편집자' | '검토자'
  status: '활성' | '초대됨' | '비활성'
}

export const members: Member[] = [
  { id: 'U1', name: '김서연', email: 'seoyeon.kim@welfare.go.kr', role: '관리자', status: '활성' },
  { id: 'U2', name: '이준호', email: 'junho.lee@welfare.go.kr', role: '편집자', status: '활성' },
  { id: 'U3', name: '박민지', email: 'minji.park@welfare.go.kr', role: '검토자', status: '활성' },
  { id: 'U4', name: '정우성', email: 'wooseong.jung@welfare.go.kr', role: '편집자', status: '초대됨' },
  { id: 'U5', name: '한지민', email: 'jimin.han@welfare.go.kr', role: '검토자', status: '비활성' },
]

// ── Admin ──────────────────────────────────────────────────────────
export interface Customer {
  id: string
  org: string
  plan: string
  seats: number
  mrr: number
  status: '정상' | '체험' | '연체' | '해지 예정'
}

export const customers: Customer[] = [
  { id: 'ORG-1042', org: '서울특별시 복지정책과', plan: '기관 표준형', seats: 20, mrr: 340_000, status: '정상' },
  { id: 'ORG-1039', org: '부산광역시 사회복지과', plan: '기관 대용량형', seats: 50, mrr: 780_000, status: '정상' },
  { id: 'ORG-1035', org: '성남시 주민생활지원과', plan: '기관 표준형', seats: 15, mrr: 250_000, status: '연체' },
  { id: 'ORG-1031', org: '대전광역시 복지기획팀', plan: '기관 소형', seats: 8, mrr: 140_000, status: '체험' },
  { id: 'ORG-1028', org: '광주광역시 노인복지과', plan: '기관 표준형', seats: 20, mrr: 340_000, status: '해지 예정' },
]

export interface Subscription {
  id: string
  org: string
  plan: string
  cycle: '월간' | '연간'
  nextBilling: string
  amount: number
  status: '활성' | '취소 예정' | '정지'
}

export const subscriptions: Subscription[] = [
  { id: 'SUB-5501', org: '서울특별시 복지정책과', plan: '기관 표준형', cycle: '연간', nextBilling: '2024-12-01', amount: 3_672_000, status: '활성' },
  { id: 'SUB-5498', org: '부산광역시 사회복지과', plan: '기관 대용량형', cycle: '월간', nextBilling: '2024-07-01', amount: 780_000, status: '활성' },
  { id: 'SUB-5494', org: '성남시 주민생활지원과', plan: '기관 표준형', cycle: '월간', nextBilling: '2024-07-01', amount: 250_000, status: '정지' },
  { id: 'SUB-5490', org: '광주광역시 노인복지과', plan: '기관 표준형', cycle: '연간', nextBilling: '2024-09-01', amount: 3_672_000, status: '취소 예정' },
]

export interface AdminUsageRow {
  org: string
  tokens: number
  cost: number
  conversions: number
}

export const adminUsage: AdminUsageRow[] = [
  { org: '부산광역시 사회복지과', tokens: 512_300, cost: 1_024_600, conversions: 284 },
  { org: '서울특별시 복지정책과', tokens: 341_200, cost: 682_400, conversions: 190 },
  { org: '광주광역시 노인복지과', tokens: 210_400, cost: 420_800, conversions: 118 },
  { org: '성남시 주민생활지원과', tokens: 98_700, cost: 197_400, conversions: 55 },
  { org: '대전광역시 복지기획팀', tokens: 42_100, cost: 84_200, conversions: 24 },
]

export interface TokenAdjustment {
  id: string
  org: string
  amount: number // + grant / - deduction
  reason: string
  operator: string
  date: string
}

export const tokenAdjustments: TokenAdjustment[] = [
  { id: 'ADJ-311', org: '성남시 주민생활지원과', amount: 50_000, reason: '변환 실패 보상', operator: '운영자 최유진', date: '2024-06-17 10:20' },
  { id: 'ADJ-310', org: '대전광역시 복지기획팀', amount: 20_000, reason: '체험 토큰 지급', operator: '운영자 최유진', date: '2024-06-16 15:02' },
  { id: 'ADJ-309', org: '서울특별시 복지정책과', amount: -8_000, reason: '중복 지급 회수', operator: '운영자 강도현', date: '2024-06-14 09:41' },
]

export interface Notice {
  id: string
  title: string
  audience: '전체' | '기관 관리자' | '운영자'
  status: '게시 중' | '예약' | '초안'
  updatedAt: string
}

export const notices: Notice[] = [
  { id: 'N-88', title: '6월 정기 점검 안내 (7/1 02:00~04:00)', audience: '전체', status: '게시 중', updatedAt: '2024-06-18 09:00' },
  { id: 'N-87', title: '쉬운 우리말 변환 품질 개선 업데이트', audience: '기관 관리자', status: '예약', updatedAt: '2024-06-17 17:30' },
  { id: 'N-86', title: '요금제 개편 사전 안내', audience: '기관 관리자', status: '초안', updatedAt: '2024-06-15 11:10' },
]

export interface AuditEntry {
  id: string
  actor: string
  action: string
  target: string
  ip: string
  at: string
}

export const auditLog: AuditEntry[] = [
  { id: 'LOG-9921', actor: '운영자 최유진', action: '토큰 지급', target: '성남시 주민생활지원과 (+50,000)', ip: '10.12.4.21', at: '2024-06-18 10:20:11' },
  { id: 'LOG-9920', actor: '운영자 강도현', action: '구독 정지', target: 'SUB-5494', ip: '10.12.4.08', at: '2024-06-18 09:58:02' },
  { id: 'LOG-9919', actor: '김서연', action: '문서 발행', target: 'CV-2024-0416', ip: '203.0.113.44', at: '2024-06-18 11:05:39' },
  { id: 'LOG-9918', actor: '운영자 최유진', action: '공지 게시', target: 'N-88', ip: '10.12.4.21', at: '2024-06-18 09:00:00' },
  { id: 'LOG-9917', actor: '이준호', action: '로그인', target: '-', ip: '203.0.113.51', at: '2024-06-18 08:41:22' },
]

export function formatKRW(n: number) {
  return n.toLocaleString('ko-KR') + '원'
}

export function formatNum(n: number) {
  return n.toLocaleString('ko-KR')
}
