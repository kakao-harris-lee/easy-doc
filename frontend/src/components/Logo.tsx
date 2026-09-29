/** 머리말·탭 제목에 쓰는 서비스명. 화면 문구가 갈라지지 않게 한곳에서 읽는다. */
export const SERVICE_NAME = 'EASY-DOC AI'

/** `compact`는 lg 미만에서 아이콘을 32px로 줄이고 부제를 감춘다. */
export function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <span className="inline-flex items-center gap-2 sm:gap-3">
      <img
        src="/icons/icon-320.png"
        alt=""
        width={44}
        height={44}
        className={
          compact
            ? 'size-8 rounded-lg shadow-sm ring-1 ring-primary/15 lg:size-11 lg:rounded-xl'
            : 'size-11 rounded-xl shadow-sm ring-1 ring-primary/15'
        }
        aria-hidden="true"
      />
      <span className="flex flex-col leading-none">
        <strong
          className={
            compact
              ? 'text-base font-black tracking-[-0.03em] text-primary max-[359px]:hidden lg:text-[17px]'
              : 'text-[17px] font-black tracking-[-0.03em] text-primary'
          }
        >
          {SERVICE_NAME}
        </strong>
        <small
          className={
            compact
              ? 'mt-1 hidden text-xs font-semibold tracking-tight text-foreground/75 lg:block'
              : 'mt-1 text-xs font-semibold tracking-tight text-foreground/75'
          }
        >
          쉬운 글 초안 도구
        </small>
      </span>
    </span>
  )
}
