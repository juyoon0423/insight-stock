"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { type FormEvent, useState } from "react";
import { ApiError, login } from "@/lib/api";
import { validateEmail, validateLoginPassword } from "@/lib/authValidation";

const ERROR_MESSAGES: Record<string, string> = {
  VALIDATION_ERROR: "입력 내용을 다시 확인해주세요.",
  INVALID_CREDENTIALS: "이메일 또는 비밀번호가 올바르지 않습니다.",
  INTERNAL_ERROR: "일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.",
};

interface FieldErrors {
  email?: string;
  password?: string;
}

export default function LoginPage() {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
  const [formError, setFormError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();

    const errors: FieldErrors = {
      email: validateEmail(email) ?? undefined,
      password: validateLoginPassword(password) ?? undefined,
    };
    setFieldErrors(errors);
    if (errors.email || errors.password) return;

    setFormError(null);
    setIsSubmitting(true);
    try {
      await login({ email, password });
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
          <h1 className="text-2xl font-semibold text-ink">로그인</h1>
          <p className="mt-1.5 text-ink-muted">InsightStock 계정으로 로그인하세요.</p>
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
              autoComplete="current-password"
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

          {formError && (
            <div role="alert" className="alert-danger">
              {formError}
            </div>
          )}

          <button type="submit" disabled={isSubmitting} className="btn-primary w-full">
            {isSubmitting ? "로그인 중…" : "로그인"}
          </button>
        </form>

        <p className="mt-6 text-sm text-ink-muted">
          계정이 없으신가요?{" "}
          <Link href="/signup" className="font-semibold text-evidence underline-offset-2 hover:underline">
            회원가입
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
