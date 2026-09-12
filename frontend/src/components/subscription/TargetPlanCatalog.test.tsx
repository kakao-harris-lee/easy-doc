import { render, screen, within } from '@testing-library/react'
import { expect, it } from 'vitest'

import { TargetPlanCatalog } from './TargetPlanCatalog'

it('연구안 목표 플랜 구성을 판매 가격이 아니라고 표시하며 체크아웃 버튼이 없다', () => {
  render(<TargetPlanCatalog />)
  expect(screen.getByRole('heading', { name: '목표 플랜 구성' })).toBeInTheDocument()
  expect(screen.getByText('연구안 · 판매 가격이 아닙니다.')).toBeInTheDocument()

  const catalog = screen.getByRole('list', { name: '목표 플랜 구성' })
  expect(within(catalog).getByText('Basic')).toBeInTheDocument()
  expect(within(catalog).getByText('99,000원')).toBeInTheDocument()
  expect(within(catalog).getByText(/200크레딧/)).toBeInTheDocument()
  expect(within(catalog).getByText('Pro')).toBeInTheDocument()
  expect(within(catalog).getByText('290,000원')).toBeInTheDocument()
  expect(within(catalog).getAllByText('HWPX 내려받기').length).toBeGreaterThan(0)
  expect(within(catalog).getByText('Enterprise')).toBeInTheDocument()
  expect(within(catalog).getByText(/1,000,000원/)).toBeInTheDocument()
  expect(within(catalog).getByText('부서 계정 공유')).toBeInTheDocument()
  expect(within(catalog).getByText('CSAP 대응 옵션')).toBeInTheDocument()

  expect(screen.getByText(/페이지 상당량은 비교용/)).toBeInTheDocument()
  expect(screen.getByText(/정책 확정 필요/)).toBeInTheDocument()
  expect(screen.queryByRole('table')).not.toBeInTheDocument()
  expect(screen.queryByRole('button')).not.toBeInTheDocument()
  expect(screen.queryByText(/문의하기|견적 요청|지금 시작/)).not.toBeInTheDocument()
})
