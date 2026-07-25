package com.insightstock.insightstock.api;

import com.insightstock.insightstock.api.dto.CompanySearchResponse;
import com.insightstock.insightstock.api.dto.CompanySummary;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.repository.CompanyRepository;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// WatchList 등록용 자동완성 - 조회 로직이 리포지토리 쿼리 하나뿐이라 별도 서비스 없이
// 컨트롤러가 직접 호출한다. 공개 엔드포인트(SecurityConfig 화이트리스트 참고).
@Validated
@RestController
@RequestMapping("/api/v1/companies")
public class CompanyController {

    private final CompanyRepository companyRepository;

    public CompanyController(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @GetMapping
    public CompanySearchResponse search(@RequestParam("q") @NotBlank(message = "검색어를 입력해주세요.") String q) {
        return new CompanySearchResponse(
                companyRepository.findTop20ByCorpNameContainingIgnoreCaseOrderByCorpNameAsc(q).stream()
                        .map(this::toSummary)
                        .toList());
    }

    private CompanySummary toSummary(Company company) {
        return new CompanySummary(
                company.getId(), company.getCorpCode(), company.getCorpName(), company.getStockCode(),
                company.getCorpCls());
    }
}
