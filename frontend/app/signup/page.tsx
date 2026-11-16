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
    <div className="flex flex-1 justify-center">
      <main id="main-content" className="w-full max-w-sm px-4 py-12 sm:px-6 sm:py-16">
        <header className="mb-8">
          <h1 className="text-2xl font-semibold text-ink">회원가입</h1>
          <p className="mt-1.5 text-ink-muted">WatchList 등 개인화 기능을 쓰려면 계정이 필요합니다.</p>
        </header>

        <form onSubmit={handleSubmit} noValidate className="flex flex-col gap-5">
          <div>
            <label htmlFor="email" className="mb-1.5 block text-sm font-semibold text-ink">
              이메일
            </label>
            <input
              id="email"
              name="email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              disabled={isSubmitting}
              autoComplete="email"
              spellCheck={false}
              placeholder="you@example.com"
              aria-invalid={fieldErrors.email ? true : undefined}
              aria-describedby={fieldErrors.email ? "email-error" : undefined}
              className="field-input"
            />
            {fieldErrors.email && (
              <p id="email-error" className="mt-1.5 text-sm text-danger">
                {fieldErrors.email}
              </p>
            )}
          </div>

          <div>
            <label htmlFor="password" className="mb-1.5 block text-sm font-semibold text-ink">
              비밀번호
            </label>
            <input
              id="password"
              name="password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isSubmitting}
              autoComplete="new-password"
              placeholder="8자 이상"
              aria-invalid={fieldErrors.password ? true : undefined}
              aria-describedby={fieldErrors.password ? "password-error" : undefined}
              className="field-input"
            />
            {fieldErrors.password && (
              <p id="password-error" className="mt-1.5 text-sm text-danger">
                {fieldErrors.password}
              </p>
            )}
          </div>

          <div>
            <label htmlFor="nickname" className="mb-1.5 block text-sm font-semibold text-ink">
              닉네임
            </label>
            <input
              id="nickname"
              name="nickname"
              type="text"
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              disabled={isSubmitting}
              autoComplete="nickname"
              placeholder="2~20자"
              aria-invalid={fieldErrors.nickname ? true : undefined}
              aria-describedby={fieldErrors.nickname ? "nickname-error" : undefined}
              className="field-input"
            />
            {fieldErrors.nickname && (
              <p id="nickname-error" className="mt-1.5 text-sm text-danger">
                {fieldErrors.nickname}
              </p>
            )}
          </div>

          {formError && (
            <div role="alert" className="alert-danger">
              {formError}
            </div>
          )}

          <button type="submit" disabled={isSubmitting} className="btn-primary w-full">
            {isSubmitting ? "가입 중…" : "회원가입"}
          </button>
        </form>

        <p className="mt-6 text-sm text-ink-muted">
          이미 계정이 있으신가요?{" "}
          <Link href="/login" className="font-semibold text-evidence underline-offset-2 hover:underline">
            로그인
          </Link>
        </p>

        <p className="mt-10 text-sm">
          <Link href="/" className="link-quiet">
            ← 메인으로 돌아가기
          </Link>
        </p>
      </main>
    </div>
  );
}
