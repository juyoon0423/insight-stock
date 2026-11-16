import type { Metadata, Viewport } from "next";
import AuthStatusBar from "@/components/AuthStatusBar";
import SessionBootstrap from "@/components/SessionBootstrap";
import "./globals.css";

// 웹폰트는 쓰지 않는다. IBM Plex Sans KR(400, 600)을 next/font로 넣어 봤지만 한글 구간 파일이
// 약 100개(약 900KB) 내려와 모바일 LCP가 0.76초 → 8.95초(중앙값)로 늘었다. 미리 정한 되돌림
// 기준(LCP +0.2초 초과)에 걸려 OS 한글 글꼴 스택으로 되돌렸다 - globals.css의 --font-sans,
// 측정 기록은 docs/frontend-lighthouse.md 3절.
export const metadata: Metadata = {
  title: "InsightStock",
  description: "DART 공시 기반 하이브리드 검색 · RAG 답변 서비스",
};

// 모바일 브라우저 주소창 색을 페이지 바탕(paper 토큰)과 맞춘다.
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#f6f7f9" },
    { media: "(prefers-color-scheme: dark)", color: "#0f141b" },
  ],
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="flex min-h-full flex-col">
        {/* 키보드 사용자가 상단 바를 건너뛰고 본문으로 바로 가는 링크 - 포커스될 때만 보인다 */}
        <a
          href="#main-content"
          className="sr-only focus:not-sr-only focus:fixed focus:left-4 focus:top-3 focus:z-50 focus:rounded-md focus:bg-surface focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-ink"
        >
          본문으로 바로가기
        </a>
        <SessionBootstrap />
        <AuthStatusBar />
        {children}
      </body>
    </html>
  );
}
