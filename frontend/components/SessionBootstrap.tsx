"use client";

import { useEffect } from "react";
import { bootstrapSession } from "@/lib/api";

// 앱 첫 진입/새로고침 시 1회 - localStorage의 refresh token으로 access token을
// 조용히 재발급한다(성공하면 로그인 상태 복원, 실패/토큰 없으면 비로그인 상태로 정리).
// 화면에 아무것도 그리지 않는 부트스트랩 전용 컴포넌트.
export default function SessionBootstrap() {
  useEffect(() => {
    bootstrapSession();
  }, []);

  return null;
}
