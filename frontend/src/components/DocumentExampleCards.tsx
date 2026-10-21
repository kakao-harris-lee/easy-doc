import { DOCUMENT_EXAMPLES, examplePath } from '../content/documentExamples'

export function DocumentExampleCards({ heading = 'h3' }: { readonly heading?: 'h2' | 'h3' }) {
  const Heading = heading
  return (
    <ul className="grid gap-2 md:grid-cols-3 md:gap-4">
      {DOCUMENT_EXAMPLES.map((example) => (
        <li key={example.id}>
          <a
            href={examplePath(example)}
            aria-labelledby={`example-card-${example.id}`}
            className="flex h-full min-h-16 items-center justify-between gap-3 rounded-xl border border-border bg-card p-4 hover:bg-accent"
          >
            <div className="min-w-0">
              <p className="text-xs font-semibold text-primary">{example.category}</p>
              <Heading
                id={`example-card-${example.id}`}
                className="mt-1 text-base font-bold leading-6 text-foreground"
              >
                {example.title}
              </Heading>
              <p className="mt-2 hidden text-sm leading-6 text-muted-foreground sm:block">
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
