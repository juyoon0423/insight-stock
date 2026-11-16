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

// 화면 구성 근거: docs/frontend-design.md 3-4절 - 답변은 "각주가 달린 문서"처럼,
// 넓은 화면(lg 이상)에서는 본문과 근거 공시(각주 목록)를 2단으로 나란히 둔다.
export default function ResultPanel({ status, data, errorMessage, onRetry }: ResultPanelProps) {
  // 질문 전에는 아무것도 그리지 않는다 - 예전 안내 문구("답변에는 항상 출처가 함께 표시됩니다")는
  // 페이지 제목 아래 설명과 같은 말이라 뺐다(docs/frontend-design.md 8절).
  if (status === "idle") return null;

  if (status === "loading") {
    return (
      <div role="status" className="mt-12 max-w-2xl">
        <span className="sr-only">답변을 만드는 중입니다…</span>
        <div className="animate-pulse space-y-3" aria-hidden="true">
          <div className="h-4 w-full rounded-sm bg-line" />
          <div className="h-4 w-11/12 rounded-sm bg-line" />
          <div className="h-4 w-2/3 rounded-sm bg-line" />
        </div>
      </div>
    );
  }

  if (status === "error") {
    return (
      <div role="alert" className="alert-danger mt-12 flex max-w-2xl flex-col items-start gap-3">
        <p>{errorMessage}</p>
        <button type="button" onClick={onRetry} className="btn-secondary">
          다시 시도
        </button>
      </div>
    );
  }

  if (!data) return null;

  const hasSources = data.sources.length > 0;

  return (
    <section
      aria-labelledby="answer-heading"
      className="mt-12 grid gap-10 lg:grid-cols-[minmax(0,1fr)_20rem] lg:gap-12"
    >
      <article className="min-w-0 max-w-2xl">
        <h2 id="answer-heading" className="mb-3 text-sm font-semibold text-ink-muted">
          답변
        </h2>
        <div className="rounded-md border border-line bg-surface px-5 py-5 sm:px-6">
          {/* citedNumbers가 없는 구버전 백엔드 응답이면 빈 배열로 - 배지 없이 본문만 보인다 */}
          <AnswerText answer={data.answer} citedNumbers={data.citedNumbers ?? []} />
        </div>

        {!hasSources && (
          // 빈 상태는 분위기보다 방향 - 왜 답하지 않았는지와 다음에 할 일을 같이 보여 준다.
          <div className="mt-4 border-l-2 border-line-strong pl-4 text-sm">
            <p className="font-semibold text-ink">관련 공시를 찾지 못했습니다</p>
            <p className="mt-1 text-ink-muted">
              공시에 근거가 없는 질문(주가 예측, 해외 기업, 수집 기간 밖의 공시)에는 답하지
              않습니다.
            </p>
            <p className="mt-1 text-ink-muted">
              공시에 나온 회사명과 사건(예: 합병, 공급계약, 주식 소각)을 넣어 다시 물어보세요.
            </p>
          </div>
        )}
      </article>

      {hasSources && (
        <aside aria-labelledby="sources-heading" className="min-w-0">
          <h2 id="sources-heading" className="mb-3 text-sm font-semibold text-ink-muted">
            근거 공시 <span className="tabular-nums">{data.sources.length}</span>건
          </h2>
          <ol className="space-y-2">
            {data.sources.map((source, i) => (
              <li key={`${source.rceptNo}-${i}`}>
                <SourceCard source={source} index={i + 1} />
              </li>
            ))}
          </ol>
        </aside>
      )}
    </section>
  );
}
