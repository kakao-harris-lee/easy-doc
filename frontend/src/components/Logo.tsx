/** 머리말·탭 제목에 쓰는 서비스명. 화면 문구가 갈라지지 않게 한곳에서 읽는다. */
export const SERVICE_NAME = 'EASY-DOC AI'

export function Logo() {
  return (
    <span className="inline-flex items-center gap-2.5">
      <img
        src="/icons/icon-320.png"
        alt=""
        width={36}
        height={36}
        className="size-9"
        aria-hidden="true"
      />
      <span className="flex flex-col leading-none">
        <strong className="text-[15px] font-extrabold tracking-tight text-foreground">
          {SERVICE_NAME}
        </strong>
        <small className="mt-0.5 text-[11px] font-medium text-muted-foreground">
          쉬운 우리말 변환
        </small>
      </span>
    </span>
  )
}
