package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Disclosure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    boolean existsByRceptNo(String rceptNo);
}
