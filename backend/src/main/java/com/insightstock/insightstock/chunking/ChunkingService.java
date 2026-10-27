package com.insightstock.insightstock.chunking;

import com.insightstock.insightstock.domain.Chunk;
import com.insightstock.insightstock.domain.ChunkSource;
import com.insightstock.insightstock.domain.ChunkType;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.DocumentStatus;
import com.insightstock.insightstock.repository.DisclosureRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

// PARSED 상태 disclosure를 청크로 분할한다. raw_content에 XmlTextExtractor가 남긴
// "[[SECTION]]" 마커 유무로 전략을 분기한다(dart-findings.md 2-5 참고): 마커가 있으면
// 구조 기반 분할, 없으면(정규식 폴백을 탄 문서 - 원본 XML 자체가 깨져 있어 구조를
// 재추정해도 정확도를 보장할 수 없음) 슬라이딩 윈도우만 적용한다.
@Slf4j
@Service
public class ChunkingService {

    private static final String SECTION_MARKER = "[[SECTION]]";
    private static final Pattern SECTION_SPLIT_PATTERN = Pattern.compile("\\n\\[\\[SECTION\\]\\]\\n");
    // 한글 음절/영문/숫자만 "의미 있는 문자"로 세어, 표의 빈 셀만 남은 청크(예: "| | |")를 걸러낸다.
    private static final Pattern MEANINGFUL_CHAR_PATTERN = Pattern.compile("[\\uAC00-\\uD7A3a-zA-Z0-9]");

    private final DisclosureRepository disclosureRepository;
    private final TransactionTemplate transactionTemplate;
    private final int wholeDocumentCharThreshold;
    private final int slidingWindowSize;
    private final int slidingWindowOverlap;
    private final boolean emptyContentFilterEnabled;
    private final int emptyContentMinLength;
    private final double emptyContentMinMeaningfulRatio;

    public ChunkingService(
            DisclosureRepository disclosureRepository,
            PlatformTransactionManager transactionManager,
            @Value("${chunking.whole-document-char-threshold}") int wholeDocumentCharThreshold,
            @Value("${chunking.sliding-window.size}") int slidingWindowSize,
            @Value("${chunking.sliding-window.overlap}") int slidingWindowOverlap,
            @Value("${chunking.empty-content-filter.enabled}") boolean emptyContentFilterEnabled,
            @Value("${chunking.empty-content-filter.min-length}") int emptyContentMinLength,
            @Value("${chunking.empty-content-filter.min-meaningful-ratio}") double emptyContentMinMeaningfulRatio) {
        this.disclosureRepository = disclosureRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.wholeDocumentCharThreshold = wholeDocumentCharThreshold;
        this.slidingWindowSize = slidingWindowSize;
        this.slidingWindowOverlap = slidingWindowOverlap;
        this.emptyContentFilterEnabled = emptyContentFilterEnabled;
        this.emptyContentMinLength = emptyContentMinLength;
        this.emptyContentMinMeaningfulRatio = emptyContentMinMeaningfulRatio;
    }

    public void chunkPending(int limit) {
        List<Disclosure> targets = disclosureRepository.findByDocumentStatus(
                DocumentStatus.PARSED, PageRequest.of(0, limit, Sort.by("id")));

        if (targets.isEmpty()) {
            log.info("청킹할 PARSED 공시가 없습니다.");
            return;
        }

        log.info("PARSED 공시 {}건 청킹 시작", targets.size());
        for (Disclosure disclosure : targets) {
            chunkOneInOwnTransaction(disclosure.getId(), disclosure.getRceptNo());
        }
    }

    // 지정한 공시만 청킹한다(접수일 구간 백필용 - chunkPending()은 상태와 id 순서로만 고른다).
    // 공시를 하나씩 다시 조회하므로 원문이 큰 문서도 한 번에 한 건만 메모리에 올라간다.
    // PARSED가 아닌 공시는 건너뛴다. 반환값은 실제로 청킹을 시도한 건수.
    public int chunkByIds(List<Long> disclosureIds) {
        int processed = 0;
        for (Long id : disclosureIds) {
            Boolean done = null;
            try {
                done = transactionTemplate.execute(status -> {
                    Disclosure disclosure = disclosureRepository.findById(id).orElse(null);
                    if (disclosure == null || disclosure.getDocumentStatus() != DocumentStatus.PARSED) {
                        return false;
                    }
                    chunkDisclosure(id);
                    return true;
                });
            } catch (Exception e) {
                log.error("공시 청킹 실패 (disclosure_id={})", id, e);
            }
            if (Boolean.TRUE.equals(done)) {
                processed++;
            }
        }
        return processed;
    }

    // 문서 하나당 별도 트랜잭션으로 처리한다. 배치 전체를 하나의 트랜잭션으로 묶었을 때,
    // 한 문서의 실패(예: 유니크 제약 위반)가 Postgres 커넥션을 "current transaction is
    // aborted" 상태로 만들어 이후 나머지 모든 문서가 연쇄적으로 실패하는 버그가 있었다 -
    // try/catch로 예외를 흡수해도 트랜잭션 자체가 이미 오염된 상태라 소용없었다.
    private void chunkOneInOwnTransaction(Long disclosureId, String rceptNo) {
        try {
            transactionTemplate.executeWithoutResult(status -> chunkDisclosure(disclosureId));
        } catch (Exception e) {
            log.error("공시 청킹 실패 (rcept_no={})", rceptNo, e);
        }
    }

    private void chunkDisclosure(Long disclosureId) {
        // chunkPending()에서 조회한 엔티티는 트랜잭션 밖(영속성 컨텍스트 없음)이라 그대로
        // 쓰면 지연 로딩 컬렉션 접근 시 LazyInitializationException이 난다. 이 트랜잭션
        // 안에서 다시 조회해야 세션이 붙는다.
        Disclosure disclosure = disclosureRepository.findById(disclosureId).orElseThrow();
        String rceptNo = disclosure.getRceptNo();

        List<Chunk> chunks = buildChunks(disclosure.getRawContent());

        // orphanRemoval로 인한 기존 청크 DELETE는 flush 시점까지 지연되는데, Chunk.id가
        // IDENTITY 생성 전략이라 새 청크의 INSERT는 즉시 실행된다. 재청킹 시 chunk_index를
        // 그대로 재사용하면 DELETE보다 INSERT가 먼저 나가 uk_chunks_disclosure_chunk_index
        // 유니크 제약을 위반한다 - clear() 직후 flush로 DELETE를 먼저 확정시켜 피한다.
        disclosure.getChunks().clear();
        disclosureRepository.saveAndFlush(disclosure);

        int index = 0;
        for (Chunk chunk : chunks) {
            chunk.setChunkIndex(index++);
            chunk.setDisclosure(disclosure);
            disclosure.getChunks().add(chunk);
        }
        disclosure.setDocumentStatus(DocumentStatus.CHUNKED);
        disclosureRepository.save(disclosure);

        if (chunks.isEmpty()) {
            log.warn("공시 청킹 결과 0개 청크 (rcept_no={}) - 필터링으로 전부 제거됨", rceptNo);
        } else {
            log.info("공시 청킹 완료 (rcept_no={}): {}개 청크", rceptNo, chunks.size());
        }
    }

    private List<Chunk> buildChunks(String rawContent) {
        List<Chunk> rawChunks;
        if (rawContent.length() < wholeDocumentCharThreshold) {
            rawChunks = List.of(newChunk(null, rawContent.strip(), ChunkSource.WHOLE_DOCUMENT));
        } else if (rawContent.contains(SECTION_MARKER)) {
            rawChunks = buildSectionChunks(rawContent);
        } else {
            rawChunks = buildSlidingWindowChunks(rawContent, null, ChunkSource.SLIDING_WINDOW);
        }
        return filterLowInformation(rawChunks);
    }

    // raw_content를 [[SECTION]] 마커 기준으로 나눈다. segments[0]은 첫 마커 이전의
    // 머리말(문서명/회사명 등)이라 섹션 제목이 없다. 그 뒤로는 각 segment의 첫 줄이
    // TITLE 텍스트, 나머지가 본문이다(XmlTextExtractor가 남긴 형식 그대로).
    private List<Chunk> buildSectionChunks(String rawContent) {
        String[] segments = SECTION_SPLIT_PATTERN.split(rawContent);
        List<Chunk> chunks = new ArrayList<>();

        String preamble = segments[0].strip();
        if (!preamble.isEmpty()) {
            chunks.add(newChunk(null, preamble, ChunkSource.SECTION));
        }

        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            int newlineIndex = segment.indexOf('\n');
            String title = (newlineIndex == -1 ? segment : segment.substring(0, newlineIndex)).strip();
            String body = (newlineIndex == -1 ? "" : segment.substring(newlineIndex + 1)).strip();
            String content = body.isEmpty() ? title : title + "\n\n" + body;

            if (content.length() <= slidingWindowSize) {
                chunks.add(newChunk(title, content, ChunkSource.SECTION));
            } else {
                chunks.addAll(buildSlidingWindowChunks(content, title, ChunkSource.SECTION));
            }
        }
        return chunks;
    }

    private List<Chunk> buildSlidingWindowChunks(String text, String sectionTitle, ChunkSource source) {
        List<Chunk> chunks = new ArrayList<>();
        int step = slidingWindowSize - slidingWindowOverlap;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + slidingWindowSize, text.length());
            chunks.add(newChunk(sectionTitle, text.substring(start, end).strip(), source));
            if (end == text.length()) {
                break;
            }
            start += step;
        }
        return chunks;
    }

    private List<Chunk> filterLowInformation(List<Chunk> chunks) {
        if (!emptyContentFilterEnabled) {
            return chunks;
        }
        List<Chunk> filtered = new ArrayList<>();
        for (Chunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            int nonBlankLength = content.replaceAll("\\s", "").length();
            if (nonBlankLength < emptyContentMinLength) {
                continue;
            }
            int meaningfulCount = 0;
            Matcher matcher = MEANINGFUL_CHAR_PATTERN.matcher(content);
            while (matcher.find()) {
                meaningfulCount++;
            }
            double ratio = (double) meaningfulCount / nonBlankLength;
            if (ratio < emptyContentMinMeaningfulRatio) {
                continue;
            }
            filtered.add(chunk);
        }
        return filtered;
    }

    private Chunk newChunk(String sectionTitle, String content, ChunkSource source) {
        Chunk chunk = new Chunk();
        chunk.setSectionTitle(sectionTitle);
        chunk.setContent(content);
        chunk.setChunkType(ChunkType.TEXT);
        chunk.setChunkSource(source);
        return chunk;
    }
}
