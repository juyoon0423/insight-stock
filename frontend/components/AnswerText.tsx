import { splitAnswerByCitations } from "@/lib/citations";

interface AnswerTextProps {
  answer: string;
  sourceCount: number;
}

export default function AnswerText({ answer, sourceCount }: AnswerTextProps) {
  const segments = splitAnswerByCitations(answer, sourceCount);

  return (
    <p className="whitespace-pre-wrap text-base leading-relaxed text-zinc-800 dark:text-zinc-200">
      {segments.map((segment, i) =>
        segment.type === "citation" ? (
          <a
            key={i}
            href={`#source-${segment.sourceIndex}`}
            className="mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded bg-blue-100 px-1 align-super text-[11px] font-semibold text-blue-700 no-underline hover:bg-blue-200 dark:bg-blue-900/50 dark:text-blue-300 dark:hover:bg-blue-900"
          >
            {segment.sourceIndex}
          </a>
        ) : (
          <span key={i}>{segment.content}</span>
        ),
      )}
    </p>
  );
}
