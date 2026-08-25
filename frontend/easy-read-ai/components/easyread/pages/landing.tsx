'use client'

import * as React from 'react'
import { Link } from 'react-router-dom'
import { Logo } from '../ui/logo'
import { Button } from '../ui/button'
import { Badge } from '../ui/badge'
import { SkipLink } from '../ui/layout-bits'
import { useAuth, homePathForRole } from '../lib/auth'
import {
  ArrowRight,
  Sparkles,
  ShieldCheck,
  FileCheck2,
  Users,
  Gauge,
  Building2,
  UserRound,
  LogOut,
} from 'lucide-react'

const audience = [
  {
    to: '/app',
    icon: Building2,
    eyebrow: '기관 담당자',
    title: '팀과 함께 쓰는 변환 작업 공간',
    desc: '여러 사용자를 한 기관으로 관리하고, 문서를 변환·검토·발행하는 과정을 팀이 함께 진행합니다.',
    cta: '작업 공간 열기',
    tone: 'primary' as const,
  },
  {
    to: '/app',
    icon: UserRound,
    eyebrow: '개인 사용자',
    title: '바로 시작하는 문서 변환',
    desc: '로그인하면 곧바로 변환 화면으로 이동합니다. 어려운 문서를 붙여넣고 쉬운 우리말 초안을 받아 보세요.',
    cta: '변환 시작하기',
    tone: 'primary' as const,
  },
]

const highlights = [
  { icon: Sparkles, title: '쉬운 우리말 자동 변환', desc: '어려운 용어를 초등 수준의 문장으로 다시 씁니다.' },
  { icon: FileCheck2, title: '검토 후 발행', desc: '원문과 변환문을 나란히 비교하고 승인해 발행합니다.' },
  { icon: Gauge, title: '토큰 기반 사용량', desc: '기관별 토큰 잔량과 월 사용 추이를 한눈에 봅니다.' },
  { icon: Users, title: '팀 협업', desc: '편집자·검토자 역할을 나누어 함께 작업합니다.' },
]

export function LandingPage() {
  const { user, logout } = useAuth()

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <SkipLink />
      <header className="border-b border-border bg-card/95 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <Logo />
          <div className="flex items-center gap-2">
            {user?.role === 'admin' && (
              <Link to="/admin">
                <Button size="sm" className="bg-admin text-white hover:bg-admin/90">
                  <ShieldCheck className="size-4" aria-hidden="true" />
                  관리자 콘솔
                </Button>
              </Link>
            )}

            {user ? (
              <>
                <Link to={homePathForRole(user.role)}>
                  <Button size="sm">
                    작업 공간으로
                    <ArrowRight className="size-4" aria-hidden="true" />
                  </Button>
                </Link>
                <Button variant="ghost" size="sm" onClick={logout}>
                  <LogOut className="size-4" aria-hidden="true" />
                  <span className="hidden sm:inline">로그아웃</span>
                </Button>
              </>
            ) : (
              <>
                <Link to="/login">
                  <Button variant="ghost" size="sm">
                    로그인
                  </Button>
                </Link>
                <Link to="/login">
                  <Button size="sm">
                    시작하기
                    <ArrowRight className="size-4" aria-hidden="true" />
                  </Button>
                </Link>
              </>
            )}
          </div>
        </div>
      </header>

      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-12 sm:px-6 sm:py-16">
        <section className="flex flex-col items-start gap-5">
          <Badge tone="primary" withIcon={false}>
            법률 · 행정 · 안내 문서 · 쉬운 우리말 변환 서비스
          </Badge>
          <h1 className="max-w-3xl text-4xl font-extrabold leading-tight tracking-tight text-foreground text-balance sm:text-5xl">
            어려운 문서를 <span className="text-primary">누구나 이해하는</span> 쉬운 우리말로
          </h1>
          <p className="max-w-2xl text-lg leading-relaxed text-muted-foreground text-pretty">
            Easy-Read AI는 어려운 용어를 포함하고 있는 여러 법률, 행정, 안내 문서 등을
            AI로 쉽게 바꾸고, 담당자가 검토·발행하는 과정을 지원합니다.
          </p>
          <div className="flex flex-wrap items-center gap-3">
            <Link to={user ? homePathForRole(user.role) : '/login'}>
              <Button>
                {user ? '작업 공간으로 이동' : '지금 시작하기'}
                <ArrowRight className="size-4" aria-hidden="true" />
              </Button>
            </Link>
            {!user && (
              <Link to="/login">
                <Button variant="outline">로그인</Button>
              </Link>
            )}
          </div>
        </section>

        <section
          aria-label="이용 대상"
          className="mt-10 grid gap-5 md:grid-cols-2"
        >
          {audience.map((a) => (
            <Link
              key={a.eyebrow}
              to={user ? a.to : '/login'}
              className="group flex flex-col gap-4 rounded-[16px] border border-border bg-card p-6 shadow-[0_1px_2px_rgba(20,33,31,0.04)] transition-all hover:-translate-y-0.5 hover:border-[color:var(--primary)]/40 hover:shadow-lg focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
            >
              <span className="flex size-12 items-center justify-center rounded-[12px] bg-accent text-accent-foreground">
                <a.icon className="size-6" aria-hidden="true" />
              </span>
              <div className="flex flex-col gap-1.5">
                <span className="text-sm font-semibold text-muted-foreground">{a.eyebrow}</span>
                <h2 className="text-xl font-bold text-foreground">{a.title}</h2>
                <p className="text-[15px] leading-relaxed text-muted-foreground text-pretty">
                  {a.desc}
                </p>
              </div>
              <span className="mt-auto inline-flex items-center gap-1.5 text-[15px] font-semibold text-primary">
                {a.cta}
                <ArrowRight className="size-4 transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
              </span>
            </Link>
          ))}
        </section>

        <section aria-label="주요 기능" className="mt-14">
          <h2 className="flex items-center gap-2 text-sm font-bold uppercase tracking-wide text-muted-foreground">
            <Building2 className="size-4" aria-hidden="true" />
            주요 기능
          </h2>
          <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            {highlights.map((h) => (
              <div
                key={h.title}
                className="flex flex-col gap-2 rounded-[12px] border border-border bg-card px-5 py-5"
              >
                <h.icon className="size-5 text-primary" aria-hidden="true" />
                <h3 className="text-base font-bold text-foreground">{h.title}</h3>
                <p className="text-sm leading-relaxed text-muted-foreground">{h.desc}</p>
              </div>
            ))}
          </div>
        </section>
      </main>

      <footer className="border-t border-border bg-card">
        <div className="mx-auto w-full max-w-6xl px-4 py-6 text-sm text-muted-foreground sm:px-6">
          프로토타입 · 실제 문서가 저장되거나 외부로 전송되지 않습니다. 모든 데이터는 화면 시연용입니다.
        </div>
      </footer>
    </div>
  )
}
