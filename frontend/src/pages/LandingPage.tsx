import { Link } from 'react-router-dom'

import { GUIDE_PATH, LOGIN_PATH, SIGNUP_PATH } from '../routes/paths'

const CONCERNS = [
  {
    who: '정책 안내문을 쓰는 담당자',
    quote:
      '정책 안내문을 만들 때마다 ‘이게 시민들에게 정말 이해될까?’하는 고민이 들어요. 전문 용어를 줄이려 해도 쉽지 않고, 쉽게 쓰려니 시간이 오래 걸려요.',
  },
  {
    who: '공공기관 안내를 읽는 분',
    quote:
      '공공기관 웹사이트에 있는 정보가 너무 어려워서 도와주는 사람 없이는 내용을 이해하기 힘들어요.',
  },
  {
    who: '다양한 손님에게 안내를 전하는 분',
    quote:
      '장애인, 고령자, 어린이 고객을 위한 정보를 제공하고 싶지만, 어떻게 써야 이해하기 쉬운 글이 되는지 잘 모르겠어요.',
  },
] as const

const DIFFERENCES = [
  {
    general: '매번 프롬프트를 다시 쓰고, 그날의 답에 따라 문체가 달라집니다.',
    here: '공공 안내문을 쉬운 글로 푸는 규칙이 이미 들어 있습니다. 초등 고학년 정도의 말과 문장으로 맞춥니다.',
  },
  {
    general: '날짜·금액·대상·조건이 바뀌어도 대화 창에서는 알아채기 어렵습니다.',
    here: '사실관계를 검사하고, 어긋난 초안은 한 번 고칩니다. 그래도 결과는 담당자가 확인하는 AI 초안입니다.',
  },
  {
    general: '나온 글을 한글·워드 파일에 다시 옮겨 적어야 합니다.',
    here: 'DOCX·HWPX·PDF를 올리고, 검수한 글을 올린 형식 그대로 내려받습니다. PDF는 읽기만 합니다.',
  },
  {
    general: '어려운 행정 용어를 그때그때 설명해야 합니다.',
    here: '선택한 낱말의 쉬운 말 후보를 사전에서 보여 줍니다.',
  },
  {
    general: '초안과 원문을 나란히 두고 문단만 다시 쓰는 화면이 없습니다.',
    here: '원문과 초안을 나란히 놓고 직접 고치거나, 문단만 다시 바꿀 수 있습니다.',
  },
] as const

const PRIMARY_LINK =
  'inline-flex min-h-11 items-center justify-center rounded-md bg-primary px-5 text-[15px] font-semibold text-primary-foreground hover:bg-primary-hover'
const SECONDARY_LINK =
  'inline-flex min-h-11 items-center justify-center rounded-md border border-input bg-card px-5 text-[15px] font-semibold text-foreground hover:bg-secondary'

/**
 * 공개 첫 화면(P0-10의 가치 제안). 로그인 없이 열린다.
 *
 * 요금·결제·공공 조달은 아직 제품에 없으므로 적지 않는다. ChatGPT·Gemini로도 초안은
 * 만들 수 있다는 점을 숨기지 않고, 이 화면이 맡는 일(규칙·사실 검사·검수·파일)만
 * 대조한다.
 */
export function LandingPage() {
  return (
    <article className="flex flex-col gap-14 pb-6">
      <header className="max-w-3xl">
        <p className="text-sm font-semibold text-primary">공공 안내문 · 쉬운 우리말 변환</p>
        <h1 className="mt-3 text-[28px] font-extrabold leading-9 tracking-tight text-foreground md:text-4xl md:leading-tight">
          어려운 공공 안내문을, 누구나 읽는 쉬운 글로
        </h1>
        <p className="mt-4 text-[17px] leading-7 text-muted-foreground">
          ChatGPT나 Gemini에 물어봐도 초안은 나옵니다. 다만 배포할 안내문은 사실·형식·검수가
          남습니다. Easy-Read AI는 그 남은 일을 맡는 변환 작업실입니다.
        </p>
        <div className="mt-6 flex flex-wrap gap-3">
          <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
            가입하고 변환해 보기
          </Link>
          <Link to={GUIDE_PATH} className={SECONDARY_LINK}>
            이용 가이드 보기
          </Link>
        </div>
      </header>

      <section aria-labelledby="landing-concerns-heading">
        <h2
          id="landing-concerns-heading"
          className="text-xl font-bold tracking-tight text-foreground"
        >
          이런 고민을 위해 만들었습니다
        </h2>
        <p className="mt-2 max-w-2xl text-sm leading-[22px] text-muted-foreground">
          안내문을 쓰는 담당자와, 그 글을 읽어야 하는 시민·손님의 막힘을 함께 풉니다.
        </p>
        <ul className="mt-6 grid gap-4 md:grid-cols-3">
          {CONCERNS.map((item) => (
            <li
              key={item.who}
              className="flex flex-col rounded-[16px] border border-border bg-card p-5 shadow-card"
            >
              <p className="text-sm font-semibold text-primary">{item.who}</p>
              <blockquote className="mt-3 text-[15px] leading-6 text-foreground">
                <p>“{item.quote}”</p>
              </blockquote>
            </li>
          ))}
        </ul>
      </section>

      <section aria-labelledby="landing-difference-heading">
        <h2
          id="landing-difference-heading"
          className="text-xl font-bold tracking-tight text-foreground"
        >
          일반 AI 채팅과 무엇이 다른가
        </h2>
        <p className="mt-2 max-w-2xl text-sm leading-[22px] text-muted-foreground">
          범용 채팅 구독을 한 번 더 사는 값이 아닙니다. 공공 문서를 검수해 내보내는 흐름에 값을
          매깁니다.
        </p>
        <div className="mt-6 overflow-x-auto rounded-[16px] border border-border bg-card shadow-card">
          <table className="w-full min-w-[36rem] border-collapse text-left text-sm leading-6">
            <caption className="sr-only">
              ChatGPT·Gemini 같은 일반 AI 채팅과 Easy-Read AI의 차이
            </caption>
            <thead>
              <tr className="border-b border-border bg-background text-muted-foreground">
                <th scope="col" className="px-5 py-3 font-semibold">
                  ChatGPT · Gemini
                </th>
                <th scope="col" className="px-5 py-3 font-semibold">
                  Easy-Read AI
                </th>
              </tr>
            </thead>
            <tbody>
              {DIFFERENCES.map((row) => (
                <tr key={row.here} className="border-b border-border last:border-b-0">
                  <td className="px-5 py-4 align-top text-muted-foreground">{row.general}</td>
                  <td className="px-5 py-4 align-top font-medium text-foreground">{row.here}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      <section aria-labelledby="landing-example-heading">
        <h2
          id="landing-example-heading"
          className="text-xl font-bold tracking-tight text-foreground"
        >
          쉬운 글은 요약이 아닙니다
        </h2>
        <p className="mt-2 max-w-2xl text-sm leading-[22px] text-muted-foreground">
          뜻을 줄이지 않고 말을 바꿉니다. 아래는 설명용 문장입니다.
        </p>
        <div className="mt-6 grid gap-4 md:grid-cols-2">
          <figure className="rounded-[16px] border border-border bg-card p-5">
            <figcaption className="text-sm font-semibold text-muted-foreground">
              바꾸기 전
            </figcaption>
            <p className="mt-3 text-[15px] leading-6 text-foreground">
              신청 기한 내에 구비서류를 완비하여 관할 주민센터에 방문·접수하여야 합니다.
            </p>
          </figure>
          <figure className="rounded-[16px] border border-border bg-accent p-5">
            <figcaption className="text-sm font-semibold text-accent-foreground">
              쉬운 글 초안
            </figcaption>
            <p className="mt-3 text-[15px] leading-6 text-foreground">
              기간 안에 서류를 모두 챙겨 주민센터에 가서 신청하세요.
            </p>
          </figure>
        </div>
      </section>

      <section
        aria-labelledby="landing-cta-heading"
        className="rounded-[16px] border border-border bg-card px-6 py-8 shadow-card"
      >
        <h2 id="landing-cta-heading" className="text-xl font-bold tracking-tight text-foreground">
          지금 파일럿으로 열어 두었습니다
        </h2>
        <p className="mt-3 max-w-2xl text-[15px] leading-6 text-muted-foreground">
          지금은 결제를 받지 않습니다. 유료가 되면 이 검수·형식·사실 검사 작업에 값을 매깁니다. 변환
          결과는 언제나 AI 초안이므로, 사실관계와 신청 방법은 담당자가 확인한 뒤 사용해 주세요.
        </p>
        <div className="mt-6 flex flex-wrap gap-3">
          <Link to={SIGNUP_PATH} className={PRIMARY_LINK}>
            가입하고 변환해 보기
          </Link>
          <Link to={LOGIN_PATH} className={SECONDARY_LINK}>
            로그인
          </Link>
        </div>
      </section>
    </article>
  )
}
