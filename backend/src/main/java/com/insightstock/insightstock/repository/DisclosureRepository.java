package com.insightstock.insightstock.repository;

import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.DocumentStatus;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    boolean existsByRceptNo(String rceptNo);

    List<Disclosure> findByDocumentStatus(DocumentStatus documentStatus, Pageable pageable);
}
