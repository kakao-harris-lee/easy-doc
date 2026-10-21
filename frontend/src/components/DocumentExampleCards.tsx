import { DOCUMENT_EXAMPLES, examplePath } from '../content/documentExamples'

export function DocumentExampleCards() {
  return (
    <ul className="grid gap-4 md:grid-cols-3">
      {DOCUMENT_EXAMPLES.map((example) => (
        <li key={example.id} className="rounded-xl border border-border bg-card p-5">
          <p className="text-sm font-semibold text-primary">{example.category}</p>
          <h3 className="mt-2 text-lg font-bold text-foreground">
            <a
              className="underline decoration-primary/40 underline-offset-4"
              href={examplePath(example)}
            >
              {example.title}
            </a>
          </h3>
          <p className="mt-3 text-sm leading-6 text-muted-foreground">{example.summary}</p>
        </li>
      ))}
    </ul>
  )
}
