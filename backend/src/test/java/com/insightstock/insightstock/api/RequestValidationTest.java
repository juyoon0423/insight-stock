package com.insightstock.insightstock.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.insightstock.insightstock.api.dto.AnswerRequest;
import com.insightstock.insightstock.api.dto.SignupRequest;
import com.insightstock.insightstock.search.HybridSearchService;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

// 공개 API의 입력 상한을 검증한다. 스프링 컨텍스트 없이 Bean Validation만 직접 돌린다 -
// 컨트롤러의 @Valid/@Validated가 실제로 쓰는 것과 같은 제약 어노테이션을 그대로 평가한다.
class RequestValidationTest {

    private static final ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    private static final Validator validator = factory.getValidator();

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    // ---- /answers ----

    @Test
    void 질문은_500자까지_허용한다() {
        assertThat(validator.validate(new AnswerRequest("가".repeat(500), null))).isEmpty();
    }

    @Test
    void 질문이_500자를_넘으면_거부한다() {
        assertThat(messages(validator.validate(new AnswerRequest("가".repeat(501), null))))
                .containsExactly("질문은 500자 이하로 입력해주세요.");
    }

    @Test
    void answers의_topK는_20까지_허용하고_21부터_거부한다() {
        assertThat(validator.validate(new AnswerRequest("질문", 20))).isEmpty();
        assertThat(messages(validator.validate(new AnswerRequest("질문", 21))))
                .containsExactly("topK는 20 이하여야 합니다.");
    }

    // ---- /search ----

    @Test
    void 검색어는_500자까지_허용하고_넘으면_거부한다() throws Exception {
        assertThat(validateSearchParams("가".repeat(500), 50)).isEmpty();
        assertThat(messages(validateSearchParams("가".repeat(501), null)))
                .containsExactly("검색어는 500자 이하로 입력해주세요.");
    }

    // ---- 회원가입 ----

    @Test
    void 비밀번호는_72바이트까지_허용한다() {
        assertThat(validator.validate(signup("a".repeat(72)))).isEmpty();
        // 한글 24자 = 72바이트
        assertThat(validator.validate(signup("가".repeat(24)))).isEmpty();
    }

    @Test
    void 비밀번호가_72바이트를_넘으면_거부한다() {
        assertThat(messages(validator.validate(signup("a".repeat(73))))).hasSize(1).first().asString().contains("너무 깁니다");
        // 한글 25자는 글자 수로는 짧지만 75바이트다 - 글자 수 제한(@Size)으로는 못 막는 경우.
        assertThat(messages(validator.validate(signup("가".repeat(25))))).hasSize(1).first().asString().contains("너무 깁니다");
    }

    @Test
    void 이_검증이_없으면_BCrypt가_72바이트_초과_비밀번호에_예외를_던진다() {
        // 검증을 넣은 이유 자체를 고정해둔다 - 이 예외가 GlobalExceptionHandler까지 올라가면 500이 된다.
        assertThatThrownBy(() -> new BCryptPasswordEncoder().encode("가".repeat(25)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private SignupRequest signup(String password) {
        return new SignupRequest("user@example.com", password, "닉네임");
    }

    private Set<ConstraintViolation<SearchController>> validateSearchParams(String q, Integer topK) throws Exception {
        // 실제 호출 전에 제약만 평가하므로 HybridSearchService는 필요 없다.
        SearchController controller = new SearchController((HybridSearchService) null, 10);
        Method search = SearchController.class.getMethod("search", String.class, Integer.class);
        return validator.forExecutables().validateParameters(controller, search, new Object[] {q, topK});
    }

    private static <T> Set<String> messages(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(ConstraintViolation::getMessage).collect(Collectors.toSet());
    }
}
