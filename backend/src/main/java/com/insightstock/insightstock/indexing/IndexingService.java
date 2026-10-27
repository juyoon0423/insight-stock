package com.insightstock.insightstock.indexing;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.Company;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.DocumentStatus;
import com.insightstock.insightstock.repository.DisclosureRepository;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// CHUNKED 상태 disclosure의 청크를 ElasticSearch에 색인한다. content는 BM25 검색
// 대상, corp_name/report_nm 등은 Disclosure/Company에서 가져와 청크 문서에 그대로
// 채워 넣는다(비정규화 - ES 문서는 검색 시 조인 없이 바로 필터링/표시할 수 있어야 함).
//
// ChunkingService에서 겪은 것과 같은 이유로 문서 하나당 독립 트랜잭션을 쓴다: 배치
// 전체를 하나의 트랜잭션으로 묶으면 한 건의 실패가 커넥션을 오염시켜 나머지 문서까지
// 연쇄 실패한다(progress.md 참고).
@Slf4j
@Service
public class IndexingService {

    private final DisclosureRepository disclosureRepository;
    private final ChunkDocumentRepository chunkDocumentRepository;
    private final TransactionTemplate transactionTemplate;

    public IndexingService(
            DisclosureRepository disclosureRepository,
            ChunkDocumentRepository chunkDocumentRepository,
            PlatformTransactionManager transactionManager) {
        this.disclosureRepository = disclosureRepository;
        this.chunkDocumentRepository = chunkDocumentRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public void indexPending(int limit) {
        List<Disclosure> targets = disclosureRepository.findByDocumentStatus(
                DocumentStatus.CHUNKED, PageRequest.of(0, limit, Sort.by("id")));

        if (targets.isEmpty()) {
            log.info("색인할 CHUNKED 공시가 없습니다.");
            return;
        }

        log.info("CHUNKED 공시 {}건 색인 시작", targets.size());
        for (Disclosure disclosure : targets) {
            indexOneInOwnTransaction(disclosure.getId(), disclosure.getRceptNo());
        }
    }

    // 지정한 공시만 색인한다(접수일 구간 백필용 - indexPending()은 상태와 id 순서로만 고른다).
    // CHUNKED가 아닌 공시는 건너뛴다. 반환값은 실제로 색인을 시도한 건수.
    public int indexByIds(List<Long> disclosureIds) {
        int processed = 0;
        for (Long id : disclosureIds) {
            Boolean done = null;
            try {
                done = transactionTemplate.execute(status -> {
                    Disclosure disclosure = disclosureRepository.findById(id).orElse(null);
                    if (disclosure == null || disclosure.getDocumentStatus() != DocumentStatus.CHUNKED) {
                        return false;
                    }
                    indexDisclosure(id);
                    return true;
                });
            } catch (Exception e) {
                log.error("공시 색인 실패 (disclosure_id={})", id, e);
            }
            if (Boolean.TRUE.equals(done)) {
                processed++;
            }
        }
        return processed;
    }

    private void indexOneInOwnTransaction(Long disclosureId, String rceptNo) {
        try {
            transactionTemplate.executeWithoutResult(status -> indexDisclosure(disclosureId));
        } catch (Exception e) {
            log.error("공시 색인 실패 (rcept_no={})", rceptNo, e);
        }
    }

    private void indexDisclosure(Long disclosureId) {
        Disclosure disclosure = disclosureRepository.findById(disclosureId).orElseThrow();
        String rceptNo = disclosure.getRceptNo();
        Company company = disclosure.getCompany();

        List<ChunkDocument> documents = disclosure.getChunks().stream()
                .map(chunk -> toDocument(chunk, disclosure, company))
                .toList();

        // 재색인 시 예전 청크 문서가 고아로 남지 않도록 먼저 정리(첫 색인이면 no-op).
        chunkDocumentRepository.deleteByDisclosureId(disclosureId);
        if (!documents.isEmpty()) {
            chunkDocumentRepository.saveAll(documents);
        }

        disclosure.setDocumentStatus(DocumentStatus.INDEXED);
        disclosureRepository.save(disclosure);

        log.info("공시 색인 완료 (rcept_no={}): {}개 문서", rceptNo, documents.size());
    }

    private ChunkDocument toDocument(Chunk chunk, Disclosure disclosure, Company company) {
        ChunkDocument document = new ChunkDocument();
        document.setId(chunk.getId().toString());
        document.setDisclosureId(disclosure.getId());
        document.setRceptNo(disclosure.getRceptNo());
        document.setCorpCode(company.getCorpCode());
        document.setCorpName(company.getCorpName());
        document.setReportNm(disclosure.getReportNm());
        document.setRceptDt(disclosure.getRceptDt());
        document.setSectionTitle(chunk.getSectionTitle());
        document.setChunkType(chunk.getChunkType());
        document.setChunkSource(chunk.getChunkSource());
        document.setChunkIndex(chunk.getChunkIndex());
        document.setContent(chunk.getContent());
        document.setCreatedAt(chunk.getCreatedAt());
        return document;
    }
}
