import { DOCUMENT_EXAMPLES, examplePath } from '../content/documentExamples'

export function DocumentExampleCards({ heading = 'h3' }: { readonly heading?: 'h2' | 'h3' }) {
  const Heading = heading
  return (
    <ul className="grid gap-4 md:grid-cols-3 md:gap-6">
      {DOCUMENT_EXAMPLES.map((example) => (
        <li key={example.id}>
          <a
            href={examplePath(example)}
            aria-labelledby={`example-card-${example.id}`}
            className="flex h-full min-h-16 items-center justify-between gap-3 rounded-xl border border-border bg-card p-5 transition-[border-color,transform] hover:-translate-y-px hover:border-input motion-reduce:transform-none motion-reduce:transition-none"
          >
            <div className="min-w-0">
              <p className="eyebrow">{example.category}</p>
              <Heading id={`example-card-${example.id}`} className="section-title mt-1">
                {example.title}
              </Heading>
              <p className="supporting-copy mt-2 hidden text-muted-foreground sm:block">
                {example.summary}
              </p>
            </div>
            <span aria-hidden="true" className="shrink-0 text-primary">
              →
            </span>
          </a>
        </li>
      ))}
    </ul>
  )
}
