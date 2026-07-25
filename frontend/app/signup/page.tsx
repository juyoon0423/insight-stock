"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { ApiError, signup } from "@/lib/api";
import { validateEmail, validateNickname, validateSignupPassword } from "@/lib/authValidation";

const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: "입력 내용을 다시 확인해주세요.",
  EMAIL_ALREADY_EXISTS: "이미 가입된 이메일입니다. 로그인해주세요.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
};

interface FieldErrors {
  email?: string;
  password?: string;
  nickname?: string;
}

export default function SignupPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [nickname, setNickname] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();

    const errors: FieldErrors = {
      email: validateEmail(email) ?? undefined,
      password: validateSignupPassword(password) ?? undefined,
      nickname: validateNickname(nickname) ?? undefined,
    };
    setFieldErrors(errors);
    if (errors.email || errors.password || errors.nickname) return;

    setFormError(null);
    setIsSubmitting(true);
    try {
      await signup({ email, password, nickname });
      router.push("/");
    } catch (err) {
      if (err instanceof ApiError) {
        setFormError(ERROR_MESSAGES[err.errorCode] ?? err.message);
      } else {
        setFormError("서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.");
      }
    } finally {
      setIsSubmitting(false);
    }
  }

  return (
    <div className="flex flex-1 justify-center bg-zinc-50 dark:bg-black">
      <main className="w-full max-w-sm px-4 py-10 sm:px-6">
        <header className="mb-8 text-center">
          <h1 className="text-2xl font-bold text-zinc-900 dark:text-zinc-50">회원가입</h1>
          <p className="mt-1 text-sm text-zinc-500 dark:text-zinc-400">
            WatchList 등 개인화 기능을 쓰려면 계정이 필요합니다.
          </p>
        </header>

        <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-4">
          <div>
            <label htmlFor="email" className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              이메일
            </label>
            <input
              id="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={isSubmitting}
              placeholder="you@example.com"
              className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
            />
            {fieldErrors.email && (
              <p className="mt-1 text-xs text-red-700 dark:text-red-400">{fieldErrors.email}</p>
            )}
          </div>

          <div>
            <label htmlFor="password" className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              비밀번호
            </label>
            <input
              id="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isSubmitting}
              placeholder="8자 이상"
              className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
            />
            {fieldErrors.password && (
              <p className="mt-1 text-xs text-red-700 dark:text-red-400">{fieldErrors.password}</p>
            )}
          </div>

          <div>
            <label htmlFor="nickname" className="mb-1 block text-sm font-medium text-zinc-700 dark:text-zinc-300">
              닉네임
            </label>
            <input
              id="nickname"
              type="text"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              disabled={isSubmitting}
              placeholder="2~20자"
              className="w-full rounded-lg border border-zinc-300 bg-white px-4 py-3 text-sm text-zinc-900 placeholder:text-zinc-400 focus:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-zinc-500 disabled:opacity-60 dark:border-zinc-700 dark:bg-zinc-900 dark:text-zinc-100 dark:placeholder:text-zinc-500"
            />
            {fieldErrors.nickname && (
              <p className="mt-1 text-xs text-red-700 dark:text-red-400">{fieldErrors.nickname}</p>
            )}
          </div>

          {formError && (
            <div className="rounded-lg border border-red-200 bg-red-50 p-3 dark:border-red-900/50 dark:bg-red-950/30">
              <p className="text-sm text-red-700 dark:text-red-400">{formError}</p>
            </div>
          )}

          <button
            type="submit"
            disabled={isSubmitting}
            className="rounded-lg bg-zinc-900 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-zinc-100 dark:text-zinc-900 dark:hover:bg-zinc-300"
          >
            {isSubmitting ? "가입 중..." : "회원가입"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-zinc-500 dark:text-zinc-400">
          이미 계정이 있으신가요?{" "}
          <Link href="/login" className="font-medium text-zinc-900 hover:underline dark:text-zinc-100">
            로그인
          </Link>
        </p>

        <Link
          href="/"
          className="mt-8 block text-center text-xs text-zinc-400 transition-colors hover:text-zinc-600 dark:hover:text-zinc-300"
        >
          ← 메인으로 돌아가기
        </Link>
      </main>
    </div>
  );
}
