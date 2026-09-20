/** 머리말·탭 제목에 쓰는 서비스명. 화면 문구가 갈라지지 않게 한곳에서 읽는다. */
export const SERVICE_NAME = 'EASY-DOC AI'

export function Logo() {
  return (
    <span className="inline-flex items-center gap-3">
      <img
        src="/icons/icon-320.png"
        alt=""
        width={44}
        height={44}
        className="size-11 rounded-xl shadow-sm ring-1 ring-primary/15"
        aria-hidden="true"
      />
      <span className="flex flex-col leading-none">
        <strong className="text-[17px] font-black tracking-[-0.03em] text-primary">
          {SERVICE_NAME}
        </strong>
        <small className="mt-1 text-xs font-semibold tracking-tight text-foreground/75">
          쉬운 우리말 변환
        </small>
      </span>
    </span>
  )
}
