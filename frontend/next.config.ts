import type { NextConfig } from "next";

// 백엔드에 도메인+TLS(https://insightstock.site)가 붙은 뒤로는 브라우저가 백엔드를
// HTTPS로 직접 호출한다(NEXT_PUBLIC_API_BASE_URL). 예전에 Mixed Content를 우회하려고
// 두었던 rewrite 프록시는 제거했다 - 배경은 docs/deploy.md 10절 참고.
const nextConfig: NextConfig = {};

export default nextConfig;
