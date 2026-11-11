import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import {
  DOCUMENT_EXAMPLES,
  EXAMPLE_NOTICE,
  EXAMPLES_INTRO,
  examplePath,
} from '../content/documentExamples'
import { DocumentExamplePage, ExamplesPage } from './ExamplesPage'

describe('공개 문서 예시', () => {
  it('가입 없이 세 문서로 이동할 수 있고 자료의 성격을 알린다', () => {
    render(<ExamplesPage />)
    expect(screen.getByRole('heading', { level: 1 })).toHaveClass('page-title')
    expect(screen.getByText(EXAMPLE_NOTICE)).toBeInTheDocument()
    expect(screen.getByText(EXAMPLES_INTRO)).toBeVisible()
    expect(screen.getByRole('link', { name: '내 문서를 쉬운 글로 바꿔 보기' })).toHaveAttribute(
      'href',
      '/',
    )
    DOCUMENT_EXAMPLES.forEach((example) => {
      expect(screen.getByRole('link', { name: example.title })).toHaveAttribute(
        'href',
        examplePath(example),
      )
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
    })
  })

  it.each(DOCUMENT_EXAMPLES)(
    '$category의 원문·쉬운 표현·수정 제안과 실제 변환 조건을 구분한다',
    async (example) => {
      const user = userEvent.setup()
      render(
        <MemoryRouter initialEntries={[examplePath(example)]}>
          <Routes>
            <Route path="/examples/:exampleId" element={<DocumentExamplePage />} />
          </Routes>
        </MemoryRouter>,
      )
      expect(screen.getByRole('heading', { level: 1, name: example.title })).toBeInTheDocument()
      expect(screen.getByText(EXAMPLE_NOTICE)).toBeVisible()
      expect(document.getElementById('example-simplified')).toHaveClass('bg-selection')
      expect(document.getElementById('example-original')).not.toHaveClass('bg-selection')
      expect(screen.getByRole('button', { name: '쉬운 글', pressed: true })).toHaveClass(
        'bg-selection',
        'text-selection-foreground',
      )
      expect(screen.getByRole('region', { name: '예시로 변환 시작하기' })).not.toHaveClass(
        'bg-brand-surface/40',
      )
      expect(screen.getByText(example.editing.after)).not.toBeVisible()
      await user.click(screen.getByText('바뀐 표현과 편집 제안'))
      expect(screen.getByText(example.editing.after)).toBeVisible()
      expect(screen.getByRole('link', { name: '원문 TXT 다운로드' })).toHaveAttribute(
        'href',
        `/example-files/${example.id}.txt`,
      )
      expect(screen.getByText('불러오기는 무료 · 변환 시 크레딧 사용')).toBeVisible()
      const originalButton = screen.getByRole('button', { name: '원문', pressed: false })
      const easyButton = screen.getByRole('button', { name: '쉬운 글', pressed: true })
      await user.click(originalButton)
      expect(originalButton).toHaveAttribute('aria-pressed', 'true')
      expect(easyButton).toHaveAttribute('aria-pressed', 'false')
      expect(
        document.getElementById(originalButton.getAttribute('aria-controls') ?? ''),
      ).toHaveTextContent(example.original.split('\n')[0] ?? '')
      await user.click(easyButton)
      expect(easyButton).toHaveAttribute('aria-pressed', 'true')
    },
  )

  it.each(DOCUMENT_EXAMPLES)(
    '$category 상세는 바꾼 점 설명과 다른 예시 링크를 펼치지 않고 보여준다',
    (example) => {
      render(
        <MemoryRouter initialEntries={[examplePath(example)]}>
          <Routes>
            <Route path="/examples/:exampleId" element={<DocumentExamplePage />} />
          </Routes>
        </MemoryRouter>,
      )
      expect(screen.getByRole('heading', { level: 2, name: '이 예시에서 바꾼 점' })).toBeVisible()
      expect(screen.getByText(example.audience)).toBeVisible()
      example.rationale.forEach((point) => {
        expect(screen.getByText(point)).toBeVisible()
      })
      DOCUMENT_EXAMPLES.filter((other) => other.id !== example.id).forEach((other) => {
        expect(
          screen.getByRole('link', { name: `${other.category} 쉬운 글 예시: ${other.title}` }),
        ).toHaveAttribute('href', examplePath(other))
      })
      expect(screen.getByRole('link', { name: '모든 예시' })).toHaveAttribute('href', '/examples')
      expect(screen.getByRole('link', { name: '내 문서를 쉬운 글로 바꿔 보기' })).toHaveAttribute(
        'href',
        '/',
      )
    },
  )

  it('알 수 없는 문서를 임의의 예시로 대체하지 않는다', () => {
    render(
      <MemoryRouter initialEntries={['/examples/unknown']}>
        <Routes>
          <Route path="/examples/:exampleId" element={<DocumentExamplePage />} />
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: '찾을 수 없는 화면입니다' })).toBeInTheDocument()
  })
})
