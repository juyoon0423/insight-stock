package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.dart.DartApiException;
import com.insightstock.insightstock.dart.DartListClient;
import com.insightstock.insightstock.dart.dto.DartDisclosureItem;
import com.insightstock.insightstock.dart.dto.DartListResponse;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.repository.CompanyRepository;
import com.insightstock.insightstock.repository.DisclosureRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// DART list API를 페이지 단위로 순회하며 신규 공시를 companies/disclosures에 저장한다.
// rcept_no 존재 여부로 중복을 걸러내므로, 매 실행마다 같은 날짜 구간을 다시 조회해도 안전하다.
@Slf4j
@Service
public class DisclosureCollectorService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String STATUS_OK = "000";
    private static final String STATUS_NO_DATA = "013";
    private static final int PAGE_COUNT = 100;

    private final DartListClient dartListClient;
    private final CompanyRepository companyRepository;
    private final DisclosureRepository disclosureRepository;

    public DisclosureCollectorService(
            DartListClient dartListClient,
            CompanyRepository companyRepository,
            DisclosureRepository disclosureRepository) {
        this.dartListClient = dartListClient;
        this.companyRepository = companyRepository;
        this.disclosureRepository = disclosureRepository;
    }

    public void collectToday() {
        LocalDate today = LocalDate.now(KST);
        collect(today, today);
    }

    private void collect(LocalDate beginDate, LocalDate endDate) {
        int pageNo = 1;
        int totalPage = 1;
        int savedCount = 0;
        int skippedCount = 0;

        do {
            DartListResponse response = dartListClient.fetchList(beginDate, endDate, pageNo, PAGE_COUNT);

            if (STATUS_NO_DATA.equals(response.status())) {
                log.info("DART 공시 없음 ({} ~ {})", beginDate, endDate);
                return;
            }
            if (!STATUS_OK.equals(response.status())) {
                throw new DartApiException(
                        "DART list API 오류: status=%s, message=%s".formatted(response.status(), response.message()));
            }

            for (DartDisclosureItem item : response.list()) {
                if (saveIfNew(item)) {
                    savedCount++;
                } else {
                    skippedCount++;
                }
            }

            totalPage = response.totalPage();
            pageNo++;
        } while (pageNo <= totalPage);

        log.info("DART 공시 수집 완료 ({} ~ {}): 신규 {}건, 스킵 {}건", beginDate, endDate, savedCount, skippedCount);
    }

    private boolean saveIfNew(DartDisclosureItem item) {
        if (disclosureRepository.existsByRceptNo(item.rceptNo())) {
            return false;
        }

        Company company = findOrCreateCompany(item);

        Disclosure disclosure = new Disclosure();
        disclosure.setCompany(company);
        disclosure.setRceptNo(item.rceptNo());
        disclosure.setReportNm(item.reportNm().trim());
        disclosure.setRceptDt(item.rceptDt());
        disclosureRepository.save(disclosure);
        return true;
    }

    private Company findOrCreateCompany(DartDisclosureItem item) {
        return companyRepository.findByCorpCode(item.corpCode())
                .map(company -> backfillCorpClsIfMissing(company, item))
                .orElseGet(() -> {
                    Company company = new Company();
                    company.setCorpCode(item.corpCode());
                    company.setCorpName(item.corpName().trim());
                    company.setStockCode(blankToNull(item.stockCode()));
                    company.setCorpCls(item.corpCls());
                    return companyRepository.save(company);
                });
    }

    // corpCode.xml로만 채워진 회사는 corp_cls가 null이다(corpCode.xml에 없는 필드 -
    // dart-findings.md 2-11). list API가 실제 공시로 그 회사를 처음 만나는 시점에
    // corp_cls를 알게 되므로, 이미 존재하는 회사라도 null이면 이번에 채워 넣는다.
    private Company backfillCorpClsIfMissing(Company company, DartDisclosureItem item) {
        if (company.getCorpCls() == null) {
            company.setCorpCls(item.corpCls());
            return companyRepository.save(company);
        }
        return company;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
