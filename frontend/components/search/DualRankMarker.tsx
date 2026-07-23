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
      <div className="relative h-1.5 rounded-full bg-zinc-100 dark:bg-zinc-800">
        <div
          className="absolute top-1/2 h-px -translate-y-1/2 bg-zinc-300 dark:bg-zinc-600"
          style={{
            left: `${Math.min(kw.percent, vec.percent)}%`,
            width: `${Math.abs(vec.percent - kw.percent)}%`,
          }}
        />
        <RankDot position={kw} colorClassName="bg-blue-500" />
        <RankDot position={vec} colorClassName="bg-orange-500" />
      </div>
      <div className="flex justify-between text-[11px] text-zinc-500 dark:text-zinc-400">
        <span className="flex items-center gap-1">
          <span className="h-1.5 w-1.5 rounded-full bg-blue-500" aria-hidden="true" />
          키워드 {keywordRank ? `#${keywordRank}` : "순위 밖"}
        </span>
        <span className="flex items-center gap-1">
          <span className="h-1.5 w-1.5 rounded-full bg-orange-500" aria-hidden="true" />
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
      className={`absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white dark:border-zinc-900 ${colorClassName} ${
        position.outOfRange ? "opacity-40" : ""
      }`}
      style={{ left: `${position.percent}%` }}
    />
  );
}
