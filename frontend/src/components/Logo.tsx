export function Logo() {
  return (
    <span className="inline-flex items-center gap-2.5">
      <span
        className="flex size-9 items-center justify-center rounded-[10px] bg-primary text-primary-foreground"
        aria-hidden="true"
      >
        <svg viewBox="0 0 24 24" fill="none" className="size-5">
          <path
            d="M4 5.5A1.5 1.5 0 0 1 5.5 4h13A1.5 1.5 0 0 1 20 5.5v9A1.5 1.5 0 0 1 18.5 16H9l-4 4v-4H5.5"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
          />
          <path
            d="m8.5 10.2 2.2 2.2 4.3-4.6"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
      </span>
      <span className="flex flex-col leading-none">
        <strong className="text-[15px] font-extrabold tracking-tight text-foreground">
          Easy-Read AI
        </strong>
        <small className="mt-0.5 text-[11px] font-medium text-muted-foreground">
          쉬운 우리말 변환
        </small>
      </span>
    </span>
  )
}
