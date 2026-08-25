'use client'

import * as React from 'react'
import { PageHeader } from '../../ui/layout-bits'
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from '../../ui/card'
import { Button } from '../../ui/button'
import { FormField, Textarea } from '../../ui/field'
import { Badge } from '../../ui/badge'
import { Skeleton, SkeletonBlock } from '../../ui/skeleton'
import { StatusMessage, ErrorState } from '../../ui/feedback'
import { Dialog } from '../../ui/dialog'
import { IconButton } from '../../ui/icon-button'
import { useToast } from '../../ui/toast'
import {
  Wand2,
  Copy,
  RotateCcw,
  Send,
  FileText,
  Sparkles,
  Coins,
} from 'lucide-react'
import { SAMPLE_SOURCE_TEXT, SAMPLE_EASY_TEXT, formatNum } from '../../lib/mock-data'

type Phase = 'idle' | 'loading' | 'error' | 'result'

export function NewConversionPage() {
  const { notify } = useToast()
  const [source, setSource] = React.useState('')
  const [phase, setPhase] = React.useState<Phase>('idle')
  const [draft, setDraft] = React.useState('')
  const [sourceError, setSourceError] = React.useState<string>()
  const [publishOpen, setPublishOpen] = React.useState(false)
  const [published, setPublished] = React.useState(false)
  const timer = React.useRef<number>()

  // simulate whether the next run "fails" — deterministic toggle for demo
  const failNextRef = React.useRef(false)

  React.useEffect(() => () => window.clearTimeout(timer.current), [])

  function runConversion(forceFail = false) {
    if (source.trim().length < 20) {
      setSourceError('변환할 원문을 20자 이상 입력해 주세요.')
      return
    }
    setSourceError(undefined)
    setPhase('loading')
    setPublished(false)
    timer.current = window.setTimeout(() => {
      if (forceFail || failNextRef.current) {
        failNextRef.current = false
        setPhase('error')
      } else {
        setDraft(SAMPLE_EASY_TEXT)
        setPhase('result')
      }
    }, 1600)
  }

  function loadSample() {
    setSource(SAMPLE_SOURCE_TEXT)
    setSourceError(undefined)
  }

  function reset() {
    setSource('')
    setDraft('')
    setPhase('idle')
    setPublished(false)
    setSourceError(undefined)
  }

  const estTokens = Math.max(0, Math.round(source.trim().length * 1.8))

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="새 변환 만들기"
        description="어려운 용어가 포함된 법률·행정·안내 문서 원문을 붙여넣으면 AI가 쉬운 우리말 초안을 만들어 드립니다. 발행 전 반드시 사람이 검토하세요."
        actions={
          <Badge tone="primary" withIcon={false}>
            <Sparkles className="mr-1 size-3.5" aria-hidden="true" />
            AI 초안 · 사람 검토 필수
          </Badge>
        }
      />

      <div className="grid gap-6 lg:grid-cols-2">
        {/* ── Input ─────────────────────────────── */}
        <Card>
          <CardHeader>
            <CardTitle as="h2">원문 입력</CardTitle>
            <CardDescription>공고문·안내문·통지서 등 원문을 붙여넣으세요.</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            <FormField
              id="source"
              label="원문"
              hint="개인정보(주민등록번호, 연락처 등)는 입력하지 마세요."
              error={sourceError}
              required
            >
              <Textarea
                value={source}
                onChange={(e) => setSource(e.target.value)}
                placeholder="예) 제3조(지원대상) ① 이 지침에 따른 지원대상은…"
                className="min-h-52"
              />
            </FormField>

            <div className="flex items-center justify-between rounded-[10px] bg-secondary px-3.5 py-2.5">
              <span className="flex items-center gap-2 text-sm text-muted-foreground">
                <Coins className="size-4 text-primary" aria-hidden="true" />
                예상 사용 토큰
              </span>
              <span className="text-sm font-semibold tabular-nums text-foreground">
                약 {formatNum(estTokens)} 토큰
              </span>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <Button
                onClick={() => runConversion()}
                loading={phase === 'loading'}
                disabled={phase === 'loading'}
              >
                <Wand2 className="size-4" aria-hidden="true" />
                {phase === 'loading' ? '변환 중…' : '쉬운 우리말로 변환'}
              </Button>
              <Button variant="outline" onClick={loadSample} disabled={phase === 'loading'}>
                <FileText className="size-4" aria-hidden="true" />
                예시 원문 넣기
              </Button>
              {source && (
                <Button variant="ghost" onClick={reset} disabled={phase === 'loading'}>
                  <RotateCcw className="size-4" aria-hidden="true" />
                  초기화
                </Button>
              )}
            </div>
          </CardContent>
        </Card>

        {/* ── Output ────────────────────────────── */}
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <div className="flex flex-col gap-1">
              <CardTitle as="h2">AI 초안 (검토용)</CardTitle>
              <CardDescription>내용을 직접 수정한 뒤 발행할 수 있습니다.</CardDescription>
            </div>
            {phase === 'result' && (
              <IconButton
                label="초안 복사"
                size="sm"
                onClick={() => {
                  navigator.clipboard?.writeText(draft)
                  notify({ tone: 'success', title: '초안을 복사했습니다' })
                }}
              >
                <Copy className="size-5" aria-hidden="true" />
              </IconButton>
            )}
          </CardHeader>
          <CardContent className="flex min-h-[22rem] flex-col gap-4">
            {phase === 'idle' && (
              <div className="flex flex-1 flex-col items-center justify-center gap-3 rounded-[12px] border border-dashed border-border py-10 text-center">
                <span className="flex size-12 items-center justify-center rounded-full bg-accent">
                  <Sparkles className="size-6 text-primary" aria-hidden="true" />
                </span>
                <p className="max-w-xs text-[15px] leading-relaxed text-muted-foreground">
                  원문을 입력하고 <b className="text-foreground">변환</b> 버튼을 누르면
                  이곳에 쉬운 우리말 초안이 표시됩니다.
                </p>
              </div>
            )}

            {phase === 'loading' && (
              <SkeletonBlock label="AI가 초안을 생성하는 중입니다" className="flex flex-1 flex-col gap-3">
                <div className="flex items-center gap-2 text-sm font-medium text-primary">
                  <span className="size-4 animate-spin rounded-full border-2 border-primary border-r-transparent" aria-hidden="true" />
                  AI가 쉬운 우리말로 다듬고 있어요…
                </div>
                <Skeleton className="h-5 w-2/5" />
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-11/12" />
                <Skeleton className="h-4 w-4/5" />
                <Skeleton className="mt-3 h-5 w-1/3" />
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-4 w-10/12" />
              </SkeletonBlock>
            )}

            {phase === 'error' && (
              <ErrorState
                title="변환에 실패했습니다"
                description="일시적인 오류로 초안을 만들지 못했습니다. 원문은 그대로 남아 있으니 다시 시도해 주세요."
                onRetry={() => runConversion()}
                className="flex-1"
              />
            )}

            {phase === 'result' && (
              <>
                <StatusMessage tone="warning" title="발행 전 확인하세요">
                  AI가 만든 초안입니다. 사실관계·수치·신청 방법이 원문과 일치하는지
                  담당자가 반드시 검토해야 합니다.
                </StatusMessage>
                <FormField id="draft" label="쉬운 우리말 초안" hint="필요한 부분을 직접 고칠 수 있습니다.">
                  <Textarea
                    value={draft}
                    onChange={(e) => setDraft(e.target.value)}
                    className="min-h-52 leading-relaxed"
                  />
                </FormField>
                <div className="flex flex-wrap items-center gap-2">
                  <Button onClick={() => setPublishOpen(true)} disabled={published}>
                    <Send className="size-4" aria-hidden="true" />
                    {published ? '발행 완료' : '검토 완료 · 발행'}
                  </Button>
                  <Button variant="outline" onClick={() => runConversion()}>
                    <RotateCcw className="size-4" aria-hidden="true" />
                    다시 변환
                  </Button>
                  {published && (
                    <Badge tone="success">발행되었습니다</Badge>
                  )}
                </div>
              </>
            )}
          </CardContent>
        </Card>
      </div>

      <Dialog
        open={publishOpen}
        onClose={() => setPublishOpen(false)}
        title="문서를 발행할까요?"
        description="발행하면 변환 기록에 추가되고 기관 구성원이 열람할 수 있습니다."
        footer={
          <>
            <Button variant="ghost" onClick={() => setPublishOpen(false)}>
              취소
            </Button>
            <Button
              onClick={() => {
                setPublished(true)
                setPublishOpen(false)
                notify({
                  tone: 'success',
                  title: '문서를 발행했습니다',
                  description: '변환 기록에서 확인할 수 있습니다.',
                })
              }}
            >
              <Send className="size-4" aria-hidden="true" />
              발행하기
            </Button>
          </>
        }
      >
        <StatusMessage tone="info">
          검토자가 확인한 최종본이 맞는지 다시 한 번 확인해 주세요. 발행 후에도
          수정할 수 있습니다.
        </StatusMessage>
      </Dialog>
    </div>
  )
}
