package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Company;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyRepository extends JpaRepository<Company, Long> {

    Optional<Company> findByCorpCode(String corpCode);
}
