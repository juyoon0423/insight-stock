package com.insightstock.insightstock.api.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// 문자열을 UTF-8로 인코딩했을 때의 바이트 수 상한. @Size는 "글자 수"를 세기 때문에
// 바이트 기준 한도(예: BCrypt의 72바이트)를 지키지 못한다 - 한글은 한 글자가 3바이트라
// 25글자만 돼도 72바이트를 넘는다. null은 통과시킨다(필수 여부는 @NotBlank가 담당).
@Documented
@Constraint(validatedBy = MaxUtf8BytesValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface MaxUtf8Bytes {

    int value();

    String message() default "입력이 너무 깁니다.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
