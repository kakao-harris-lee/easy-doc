'use client'

import { Link } from 'react-router-dom'
import { SkipLink } from '../ui/layout-bits'
import { Logo } from '../ui/logo'
import { ArrowLeft } from 'lucide-react'

const anchors = [
  { id: 'colors', label: '색상' },
  { id: 'typography', label: '타이포그래피' },
  { id: 'buttons', label: '버튼' },
  { id: 'badges', label: '배지·상태' },
  { id: 'forms', label: '폼 요소' },
  { id: 'feedback', label: '피드백' },
  { id: 'progress', label: '진행 표시' },
  { id: 'overlays', label: '오버레이' },
  { id: 'tabs', label: '탭' },
  { id: 'table', label: '테이블' },
]

export function ShowcaseShell({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen bg-background">
      <SkipLink />
      <header className="sticky top-0 z-30 border-b border-border bg-card/90 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link
            to="/"
            className="flex items-center gap-2 rounded-md text-sm font-medium text-muted-foreground hover:text-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
          >
            <ArrowLeft className="size-4" aria-hidden="true" />
            포털로
          </Link>
          <Logo />
          <span className="hidden text-sm text-muted-foreground sm:inline">디자인 시스템</span>
        </div>
      </header>

      <div className="mx-auto flex w-full max-w-6xl gap-8 px-4 py-8 sm:px-6">
        <nav aria-label="컴포넌트 목록" className="hidden w-48 shrink-0 lg:block">
          <ul className="sticky top-24 space-y-1">
            {anchors.map((a) => (
              <li key={a.id}>
                <a
                  href={`#${a.id}`}
                  className="block rounded-md px-3 py-2 text-[15px] font-medium text-muted-foreground transition-colors hover:bg-secondary hover:text-foreground focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
                >
                  {a.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>

        <main id="main" tabIndex={-1} className="min-w-0 flex-1 focus-visible:outline-none">
          <div className="mb-10">
            <h1 className="text-3xl font-bold text-foreground">디자인 시스템</h1>
            <p className="mt-2 max-w-2xl text-[15px] leading-relaxed text-muted-foreground">
              쉬운 우리말 서비스 전반에서 사용하는 색상, 타이포그래피, 컴포넌트를 한곳에 모았습니다. 모든 요소는 웹 접근성 지침(KWCAG)을 고려해 설계되었습니다.
            </p>
          </div>
          {children}
        </main>
      </div>
    </div>
  )
}
