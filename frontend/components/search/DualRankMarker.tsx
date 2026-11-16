import { toRankPosition } from "@/lib/rankScale";

interface DualRankMarkerProps {
  keywordRank: number | null;
  vectorRank: number | null;
}

export default function DualRankMarker({ keywordRank, vectorRank }: DualRankMarkerProps) {
  const kw = toRankPosition(keywordRank);
  const vec = toRankPosition(vectorRank);

  return (
    <div className="flex flex-col gap-1.5">
      <div className="relative h-1.5 rounded-full bg-line" aria-hidden="true">
        <div
          className="absolute top-1/2 h-px -translate-y-1/2 bg-line-strong"
          style={{
            left: `${Math.min(kw.percent, vec.percent)}%`,
            width: `${Math.abs(vec.percent - kw.percent)}%`,
          }}
        />
        <RankDot position={kw} colorClassName="bg-rank-keyword" />
        <RankDot position={vec} colorClassName="bg-rank-vector" />
      </div>
      <div className="flex justify-between text-xs tabular-nums text-ink-muted">
        <span className="flex items-center gap-1">
          <span className="h-2 w-2 rounded-full bg-rank-keyword" aria-hidden="true" />
          키워드 {keywordRank ? `#${keywordRank}` : "순위 밖"}
        </span>
        <span className="flex items-center gap-1">
          <span className="h-2 w-2 rounded-full bg-rank-vector" aria-hidden="true" />
          벡터 {vectorRank ? `#${vectorRank}` : "순위 밖"}
        </span>
      </div>
    </div>
  );
}

function RankDot({
  position,
  colorClassName,
}: {
  position: ReturnType<typeof toRankPosition>;
  colorClassName: string;
}) {
  return (
    <div
      className={`absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-surface ${colorClassName} ${
        position.outOfRange ? "opacity-40" : ""
      }`}
      style={{ left: `${position.percent}%` }}
    />
  );
}
