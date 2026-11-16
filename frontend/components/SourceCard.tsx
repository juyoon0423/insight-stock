import type { SourceRef } from "@/lib/types";

interface SourceCardProps {
  source: SourceRef;
  index: number;
}

// 근거 공시 하나 = 각주 하나. 카드 대신 왼쪽 선과 번호로 표현한다(docs/frontend-design.md 3-4절).
// 답변 속 번호(<a href="#source-N">)를 누르면 브라우저 기본 앵커 이동으로 여기에 오고,
// target: 변형(CSS :target)이 이 항목에 배경색만 더한다 - 스크롤 방식과 JavaScript는 그대로다.
export default function SourceCard({ source, index }: SourceCardProps) {
  return (
    <a
      id={`source-${index}`}
      href={source.dartUrl}
      target="_blank"
      rel="noopener noreferrer"
      className="group block scroll-mt-24 break-keep wrap-anywhere rounded-r-md border-l-2 border-line py-3 pl-4 pr-3 transition-colors hover:border-evidence hover:bg-surface target:border-evidence target:bg-evidence-tint"
    >
      <span className="flex items-baseline gap-2">
        <span
          aria-hidden="true"
          className="cite-badge group-target:bg-evidence group-target:text-on-evidence"
        >
          {index}
        </span>
        <span className="font-semibold text-ink">
          <span className="sr-only">출처 {index}: </span>
          {source.corpName}
        </span>
      </span>
      <span className="mt-1 block text-sm text-ink-muted">{source.reportNm}</span>
      {source.sectionTitle && <span className="block text-sm text-ink-subtle">{source.sectionTitle}</span>}
      <span className="mt-1.5 inline-flex items-center gap-1 text-sm font-semibold text-evidence underline-offset-2 group-hover:underline">
        DART 원문 보기
        <span className="sr-only">(새 창)</span>
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 20 20"
          fill="currentColor"
          className="h-3.5 w-3.5"
          aria-hidden="true"
        >
          <path
            fillRule="evenodd"
            d="M4.25 5.5a.75.75 0 0 0-.75.75v9a.75.75 0 0 0 .75.75h9a.75.75 0 0 0 .75-.75v-4a.75.75 0 0 1 1.5 0v4A2.25 2.25 0 0 1 13.25 17.5h-9A2.25 2.25 0 0 1 2 15.25v-9A2.25 2.25 0 0 1 4.25 4h4a.75.75 0 0 1 0 1.5h-4Z"
            clipRule="evenodd"
          />
          <path
            fillRule="evenodd"
            d="M6.194 13.806a.75.75 0 0 0 1.06 0l7.996-7.995V8.5a.75.75 0 0 0 1.5 0v-4.75a.75.75 0 0 0-.75-.75H11.25a.75.75 0 0 0 0 1.5h2.688l-7.995 7.996a.75.75 0 0 0 0 1.06Z"
            clipRule="evenodd"
          />
        </svg>
      </span>
    </a>
  );
}
