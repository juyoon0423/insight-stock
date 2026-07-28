import type { NextConfig } from "next";

// 백엔드가 아직 HTTP(IP 기반, TLS 인증서 없음)라서 브라우저가 HTTPS 페이지에서
// 바로 호출하면 Mixed Content로 차단된다. Vercel 서버가 대신 프록시해서
// 브라우저에는 항상 HTTPS(같은 origin)로만 보이게 한다 - 시연 직전 도메인을
// 구매해 백엔드에 TLS를 붙이면 이 rewrite는 제거하고 직접 호출로 되돌리면 된다.
const BACKEND_ORIGIN = process.env.BACKEND_ORIGIN ?? "http://3.36.124.56";

const nextConfig: NextConfig = {
  async rewrites() {
    return [
      {
        source: "/api/v1/:path*",
        destination: `${BACKEND_ORIGIN}/api/v1/:path*`,
      },
    ];
  },
};

export default nextConfig;
