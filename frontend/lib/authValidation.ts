// 백엔드 Bean Validation 규칙(backend/.../api/dto/SignupRequest.java,
// LoginRequest.java)과 정확히 일치시킨다 - 메시지도 동일한 문구를 써서 클라이언트
// 검증과 서버 검증이 항상 같은 말을 하게 한다.
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

export function validateEmail(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return "이메일을 입력해주세요.";
  if (!EMAIL_PATTERN.test(trimmed)) return "올바른 이메일 형식이 아닙니다.";
  return null;
}

// 로그인은 백엔드도 @NotBlank만 검증한다(가입 시점의 최소 길이 정책이 나중에
// 바뀌어도 기존 계정으로 로그인은 계속 가능해야 하므로).
export function validateLoginPassword(value: string): string | null {
  if (!value) return "비밀번호를 입력해주세요.";
  return null;
}

export function validateSignupPassword(value: string): string | null {
  if (!value) return "비밀번호를 입력해주세요.";
  if (value.length < 8) return "비밀번호는 8자 이상이어야 합니다.";
  return null;
}

export function validateNickname(value: string): string | null {
  const trimmed = value.trim();
  if (!trimmed) return "닉네임을 입력해주세요.";
  if (trimmed.length < 2 || trimmed.length > 20) return "닉네임은 2~20자여야 합니다.";
  return null;
}
