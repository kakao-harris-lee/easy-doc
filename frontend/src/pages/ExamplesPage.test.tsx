import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { describe, expect, it } from 'vitest'

import { DOCUMENT_EXAMPLES, EXAMPLE_NOTICE, examplePath } from '../content/documentExamples'
import { DocumentExamplePage, ExamplesPage } from './ExamplesPage'

describe('공개 문서 예시', () => {
  it('가입 없이 세 문서로 이동할 수 있고 자료의 성격을 알린다', () => {
    render(<ExamplesPage />)
    expect(screen.getByText(EXAMPLE_NOTICE)).toBeInTheDocument()
    DOCUMENT_EXAMPLES.forEach((example) => {
      expect(screen.getByRole('link', { name: example.title })).toHaveAttribute(
        'href',
        examplePath(example),
      )
    })
  })

  it.each(DOCUMENT_EXAMPLES)(
    '$category의 원문·쉬운 표현·수정 제안과 실제 변환 조건을 구분한다',
    (example) => {
      render(
        <MemoryRouter initialEntries={[examplePath(example)]}>
          <Routes>
            <Route path="/examples/:exampleId" element={<DocumentExamplePage />} />
          </Routes>
        </MemoryRouter>,
      )
      expect(screen.getByRole('heading', { level: 1, name: example.title })).toBeInTheDocument()
      expect(
        screen.getByText('실제 사용자의 수정 기록이 아닌 편집 제안입니다.'),
      ).toBeInTheDocument()
      expect(screen.getByRole('link', { name: '가상 원문 TXT 내려받기' })).toHaveAttribute(
        'href',
        `/example-files/${example.id}.txt`,
      )
      expect(screen.getByText(/화면에 표시된 크레딧을 사용합니다/)).toBeInTheDocument()
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
