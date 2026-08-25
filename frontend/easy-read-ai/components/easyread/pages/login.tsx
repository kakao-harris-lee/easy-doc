'use client'

import * as React from 'react'
import { Link, useNavigate, useLocation } from 'react-router-dom'
import { Logo } from '../ui/logo'
import { Button } from '../ui/button'
import { Badge } from '../ui/badge'
import { FormField, Input } from '../ui/field'
import { SkipLink } from '../ui/layout-bits'
import { useAuth, homePathForRole, demoAccounts, type Role } from '../lib/auth'
import { ArrowRight, ShieldCheck, Building2, UserRound, LogIn } from 'lucide-react'

const roleMeta: Record<Role, { icon: typeof ShieldCheck; label: string; desc: string }> = {
  admin: { icon: ShieldCheck, label: '플랫폼 운영자', desc: '운영 관리자 콘솔로 이동합니다.' },
  org: { icon: Building2, label: '기관 담당자', desc: '여러 사용자를 관리하고 변환합니다.' },
  individual: { icon: UserRound, label: '개인 사용자', desc: '바로 변환 작업 공간으로 이동합니다.' },
}

export function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const { login, loginAs } = useAuth()

  const [email, setEmail] = React.useState('')
  const [password, setPassword] = React.useState('')
  const [error, setError] = React.useState('')

  const redirectTo = (location.state as { from?: string } | null)?.from

  function go(user: ReturnType<typeof loginAs>) {
    if (!user) return
    navigate(redirectTo ?? homePathForRole(user.role), { replace: true })
  }

  function onSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (!email.trim()) return setError('이메일을 입력해 주세요.')
    if (!password) return setError('비밀번호를 입력해 주세요.')
    const user = login(email, password)
    if (!user) return setError('이메일 또는 비밀번호가 올바르지 않습니다.')
    go(user)
  }

  return (
    <div className="flex min-h-dvh flex-col bg-background">
      <SkipLink />
      <header className="border-b border-border bg-card/95 backdrop-blur">
        <div className="mx-auto flex h-16 w-full max-w-6xl items-center justify-between px-4 sm:px-6">
          <Link to="/" className="rounded-md focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring">
            <Logo />
          </Link>
          <Link to="/">
            <Button variant="ghost" size="sm">
              홈으로
            </Button>
          </Link>
        </div>
      </header>

      <main id="main" className="mx-auto grid w-full max-w-5xl flex-1 items-center gap-10 px-4 py-12 sm:px-6 lg:grid-cols-2">
        {/* Login form */}
        <section className="mx-auto w-full max-w-sm">
          <div className="flex flex-col gap-1.5">
            <Badge tone="primary" withIcon={false} className="w-fit">
              로그인
            </Badge>
            <h1 className="text-2xl font-extrabold tracking-tight text-foreground">
              다시 오신 것을 환영합니다
            </h1>
            <p className="text-[15px] leading-relaxed text-muted-foreground">
              계정으로 로그인하면 역할에 맞는 화면으로 이동합니다.
            </p>
          </div>

          <form onSubmit={onSubmit} className="mt-6 flex flex-col gap-4" noValidate>
            <FormField id="login-email" label="이메일" required>
              <Input
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="name@organization.go.kr"
                autoComplete="username"
              />
            </FormField>
            <FormField id="login-password" label="비밀번호" required>
              <Input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="비밀번호"
                autoComplete="current-password"
              />
            </FormField>

            {error && (
              <p role="alert" className="rounded-[10px] bg-danger-surface px-3 py-2 text-sm font-medium text-danger">
                {error}
              </p>
            )}

            <Button type="submit" className="w-full">
              <LogIn className="size-4" aria-hidden="true" />
              로그인
            </Button>
          </form>
        </section>

        {/* Demo quick logins */}
        <section aria-label="데모 계정 빠른 로그인" className="rounded-[16px] border border-border bg-card p-6">
          <h2 className="text-sm font-bold uppercase tracking-wide text-muted-foreground">
            데모 계정으로 바로 체험
          </h2>
          <p className="mt-1 text-sm leading-relaxed text-muted-foreground">
            프로토타입 시연용 계정입니다. 아래에서 역할을 선택하면 바로 로그인됩니다.
          </p>
          <div className="mt-4 flex flex-col gap-3">
            {demoAccounts.map((a) => {
              const meta = roleMeta[a.role]
              return (
                <button
                  key={a.id}
                  type="button"
                  onClick={() => go(loginAs(a.role))}
                  className="group flex items-center gap-3 rounded-[12px] border border-border bg-background px-4 py-3 text-left transition-colors hover:border-[color:var(--primary)]/40 hover:bg-secondary focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-ring"
                >
                  <span
                    className={
                      a.role === 'admin'
                        ? 'flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-admin-surface text-admin'
                        : 'flex size-10 shrink-0 items-center justify-center rounded-[10px] bg-accent text-accent-foreground'
                    }
                  >
                    <meta.icon className="size-5" aria-hidden="true" />
                  </span>
                  <span className="flex flex-1 flex-col">
                    <span className="text-[15px] font-bold text-foreground">{meta.label}</span>
                    <span className="text-sm text-muted-foreground">{meta.desc}</span>
                    <span className="mt-0.5 text-xs text-muted-foreground">{a.email}</span>
                  </span>
                  <ArrowRight className="size-4 shrink-0 text-muted-foreground transition-transform group-hover:translate-x-0.5" aria-hidden="true" />
                </button>
              )
            })}
          </div>
        </section>
      </main>
    </div>
  )
}
