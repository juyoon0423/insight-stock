import { splitAnswerByCitations } from "@/lib/citations";

// 홈 첫 화면의 "답변 미리보기" - 질문하기 전에 이 서비스의 답이 어떤 모양인지(본문 속 출처 번호 + 근거 공시 목록) 보여 준다.
// 문구와 출처는 실제로 받은 응답에서 그대로 가져왔다: 질문 "하이드로리튬이 작년보다 돈을 얼마나 벌었는지,
// 사업부문별로 어떻게 다른가?"(2026-11-16 로컬 캡처, 원본은 저장소 밖 lighthouse-reports/answers-after/q6.json).
// 숫자와 문장은 바꾸지 않았고, 길어서 첫 문단 전체와 둘째 문단의 한 문장만 골랐다(생략 부분은 "…"로 표시).
// 정적 예시라 API를 부르지 않고, 배지는 링크가 아니다(실제 답변의 #source-N 앵커와 겹치지 않게).
const PREVIEW_QUESTION = "하이드로리튬이 작년보다 돈을 얼마나 벌었는지, 사업부문별로 어떻게 다른가?";
const PREVIEW_ANSWER =
  "하이드로리튬의 2025년 당기순손실은 -30,041백만원으로, 전년 대비 손실이 96.4% 증가하였습니다. 이는 리튬사업 초기 볼륨이 저조하여 영업손실이 발생하고, 사업 초기 수익성 저조로 인해 유형자산 손상평가 반영에 따른 영업외 손실이 증가한 결과입니다[1].\n\n… 리튬사업부문에서의 매출은 수산화리튬과 탄산리튬을 제조 및 판매한 것으로, 제품매출이 3,127백만원, 상품매출이 242백만원입니다[2][3].";
const PREVIEW_CITED = [1, 2, 3];
const PREVIEW_SOURCES = [
  { corpName: "하이드로리튬", reportNm: "[기재정정]사업보고서 (2025.12)", sectionTitle: "3. 재무상태 및 영업실적" },
  { corpName: "하이드로리튬", reportNm: "[기재정정]사업보고서 (2025.12)", sectionTitle: "4. 매출 및 수주상황" },
  { corpName: "하이드로리튬", reportNm: "[기재정정]사업보고서 (2025.12)", sectionTitle: "3. 재무상태 및 영업실적" },
];

export default function AnswerPreview() {
  // 배지 판단은 실제 답변과 같은 함수로 - 미리보기와 실제 화면의 번호 처리가 어긋나지 않게
  const segments = splitAnswerByCitations(PREVIEW_ANSWER, PREVIEW_CITED);

  return (
    <aside
      aria-labelledby="preview-heading"
      className="rounded-md border border-line bg-surface p-5 break-keep wrap-anywhere"
    >
      <h2 id="preview-heading" className="text-sm font-semibold text-ink">
        답변 미리보기
      </h2>
      <p className="mt-1 text-xs text-ink-subtle">예시: 실제로 받은 답변의 일부</p>
      <p className="mt-3 text-sm font-semibold text-pretty text-ink-muted">Q. {PREVIEW_QUESTION}</p>

      <p className="mt-2 text-sm leading-[1.8] whitespace-pre-wrap text-pretty text-ink">
        {segments.map((segment, i) =>
          segment.type === "citation" ? (
            <span key={i} className="cite-badge cite-badge-inline mx-0.5">
              <span className="sr-only">출처 </span>
              {segment.sourceIndex}
            </span>
          ) : (
            <span key={i}>{segment.content}</span>
          ),
        )}
      </p>

      <h3 className="mt-4 border-t border-line pt-3 text-xs font-semibold text-ink-muted">근거 공시 3건</h3>
      <ol className="mt-2 space-y-2">
        {PREVIEW_SOURCES.map((source, i) => (
          <li key={i} className="flex items-baseline gap-2 border-l-2 border-line pl-3 text-xs">
            <span aria-hidden="true" className="cite-badge">
              {i + 1}
            </span>
            <span>
              <span className="sr-only">출처 {i + 1}: </span>
              <span className="font-semibold text-ink">{source.corpName}</span>
              <span className="block text-ink-muted">{source.reportNm}</span>
              <span className="block text-ink-subtle">{source.sectionTitle}</span>
            </span>
          </li>
        ))}
      </ol>
    </aside>
  );
}
