import type { SourceRef } from "@/lib/types";

interface SourceCardProps {
  source: SourceRef;
  index: number;
}

export default function SourceCard({ source, index }: SourceCardProps) {
  return (
    <a
      id={`source-${index}`}
      href={source.dartUrl}
      target="_blank"
      rel="noopener noreferrer"
      className="group flex scroll-mt-24 flex-col gap-1 rounded-lg border border-zinc-200 bg-white p-4 transition-colors hover:border-blue-400 hover:bg-blue-50/50 dark:border-zinc-700 dark:bg-zinc-900 dark:hover:border-blue-500 dark:hover:bg-blue-950/30"
    >
      <div className="flex items-center gap-2">
        <span className="flex h-5 min-w-5 items-center justify-center rounded bg-blue-100 px-1 text-[11px] font-semibold text-blue-700 dark:bg-blue-900/50 dark:text-blue-300">
          {index}
        </span>
        <span className="font-semibold text-zinc-900 dark:text-zinc-100">{source.corpName}</span>
      </div>
      <p className="text-sm text-zinc-600 dark:text-zinc-400">
        {source.reportNm}
        {source.sectionTitle && ` · ${source.sectionTitle}`}
      </p>
      <span className="mt-1 inline-flex items-center gap-1 text-xs font-medium text-blue-600 group-hover:underline dark:text-blue-400">
        DART 원문 보기
        <svg
          xmlns="http://www.w3.org/2000/svg"
          viewBox="0 0 20 20"
          fill="currentColor"
          className="h-3 w-3"
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
