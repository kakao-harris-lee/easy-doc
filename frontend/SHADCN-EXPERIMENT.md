# shadcn/ui 화면 실험

브랜치: experiment/shadcn-studio

## 선택한 템플릿

[AdminCN Free](https://shadcnstudio.com/templates/admin-dashboard/admincn-free)의 카드·폼 구성을 Easy-Doc의 첫 화면과 새 변환 화면에 적용한다. Next.js 런타임, 데모 데이터, 관리자 메뉴는 가져오지 않고 기존 React/Vite 화면과 API 흐름에 필요한 컴포넌트만 이식한다.

원본: https://github.com/shadcnstudio/shadcn-nextjs-admincn-admin-template-free

참조 커밋: dd1afd2f3a794ea9e746197de314f81d43670ea1

## 컴포넌트와 테마

- src/components/shadcn/: 원본의 shadcn/ui Card, Button, Input, Textarea를 가져온 구성.
- 기존 Button과 Badge의 로딩·상태 API는 유지한다.
- 입력 높이는 44px 이상, 본문 입력은 16px, 초점 외곽선은 기존 공통 3px 규칙을 따른다.
- 기존 CSS 의미 토큰과 html[data-theme] 설정을 사용한다.
- components.json 및 @/ 별칭으로 후속 shadcn/ui 컴포넌트를 추가할 수 있다.
- 원본 MIT 고지는 licenses/shadcn-studio-MIT.txt에 보존한다.

## 실행과 검증

이 디렉터리에서 npm ci 후 npm run dev -- --port 5175 --strictPort를 실행한다. 첫 화면은 로그인 없이 볼 수 있다. 새 변환 화면은 기존 API 서버와 로그인 계정이 필요하며 VITE_API_BASE_URL로 서버 주소를 설정한다.

필수 검증: npm run check, npm run test -- --run, npm run build. 실제 문서 변환과 유료 LLM 호출은 이 UI 실험의 검증에 포함하지 않는다.
