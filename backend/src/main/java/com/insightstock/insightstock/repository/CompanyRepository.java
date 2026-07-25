package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Company;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByCorpCode(String corpCode);

    // WatchList 등록용 자동완성 - 결과 개수를 20건으로 상한한다(짧은 검색어는 다건 매치될 수 있음).
    List<Company> findTop20ByCorpNameContainingIgnoreCaseOrderByCorpNameAsc(String corpNamePart);
}
