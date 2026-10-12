import type { AnswerResponse } from "@/lib/types";
import AnswerText from "./AnswerText";
import SourceCard from "./SourceCard";

export type ResultStatus = "idle" | "loading" | "success" | "error";

interface ResultPanelProps {
  status: ResultStatus;
  data: AnswerResponse | null;
  errorMessage: string | null;
  onRetry: () => void;
}

export default function ResultPanel({ status, data, errorMessage, onRetry }: ResultPanelProps) {
  if (status === "idle") {
    return (
      <p className="py-16 text-center text-sm text-zinc-400 dark:text-zinc-500">
        DART 공시에 근거한 답변을 받아보세요. 답변에는 항상 출처가 함께 표시됩니다.
      </p>
    );
  }

  if (status === "loading") {
    return (
      <div className="animate-pulse space-y-6 py-6" aria-busy="true" aria-label="답변 생성 중">
        <div className="space-y-2 rounded-lg border border-zinc-200 p-5 dark:border-zinc-800">
          <div className="h-4 w-full rounded bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-4 w-11/12 rounded bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-4 w-2/3 rounded bg-zinc-200 dark:bg-zinc-800" />
        </div>
        <div className="grid gap-3 sm:grid-cols-2">
          <div className="h-20 rounded-lg bg-zinc-200 dark:bg-zinc-800" />
          <div className="h-20 rounded-lg bg-zinc-200 dark:bg-zinc-800" />
        </div>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div className="mt-6 flex flex-col items-start gap-3 rounded-lg border border-red-200 bg-red-50 p-4 dark:border-red-900/50 dark:bg-red-950/30">
        <p className="text-sm text-red-700 dark:text-red-400">{errorMessage}</p>
        <button
          onClick={onRetry}
          className="rounded-md border border-red-300 px-3 py-1.5 text-xs font-medium text-red-700 transition-colors hover:bg-red-100 dark:border-red-800 dark:text-red-300 dark:hover:bg-red-900/40"
        >
          다시 시도
        </button>
      </div>
    );
  }

  if (!data) return null;

  const hasSources = data.sources.length > 0;

  return (
    <div className="space-y-6 py-6">
      <div
        className={
          hasSources
            ? "rounded-lg border border-zinc-200 bg-white p-5 dark:border-zinc-800 dark:bg-zinc-900"
            : "rounded-lg border border-dashed border-zinc-300 bg-zinc-50 p-5 dark:border-zinc-700 dark:bg-zinc-900/50"
        }
      >
        {!hasSources && (
          <div className="mb-3">
            <p className="flex items-center gap-1.5 text-xs font-medium text-zinc-500 dark:text-zinc-400">
              <span aria-hidden="true">🔍</span> 관련 공시를 찾지 못했습니다
            </p>
            <p className="mt-1 text-xs text-zinc-400 dark:text-zinc-500">
              현재 국내 DART 공시 데이터를 기반으로 답변합니다. 해외 기업이나 수집 범위
              밖의 정보는 답변할 수 없습니다.
            </p>
          </div>
        )}
        {/* citedNumbers가 없는 구버전 백엔드 응답이면 빈 배열로 - 배지 없이 본문만 보인다 */}
        <AnswerText answer={data.answer} citedNumbers={data.citedNumbers ?? []} />
      </div>

      {hasSources && (
        <div>
          <h2 className="mb-3 text-sm font-semibold text-zinc-500 dark:text-zinc-400">
            출처 {data.sources.length}건
          </h2>
          <div className="grid gap-3 sm:grid-cols-2">
            {data.sources.map((source, i) => (
              <SourceCard key={`${source.rceptNo}-${i}`} source={source} index={i + 1} />
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
