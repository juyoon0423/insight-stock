package com.insightstock.insightstock.collector;

import com.insightstock.insightstock.dart.DartApiException;
import com.insightstock.insightstock.dart.DartDocumentClient;
import com.insightstock.insightstock.dart.XmlTextExtractor;
import com.insightstock.insightstock.domain.Disclosure;
import com.insightstock.insightstock.domain.DocumentStatus;
import com.insightstock.insightstock.repository.DisclosureRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

// PENDING 상태 disclosure를 골라 document API로 원문을 받아 정제한 뒤 raw_content에 저장한다.
// 상태 전이: PENDING -> FETCHED(ZIP 다운로드 성공) -> PARSED(정제까지 성공).
// status=014(원문 없음)는 NO_DOCUMENT로, 그 외 예외는 FAILED로 terminal 처리한다.
@Slf4j
@Service
public class DocumentCollectorService {

    private static final String STATUS_NO_DOCUMENT = "014";
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    private static final Pattern STATUS_PATTERN = Pattern.compile("<status>(\\d+)</status>");
    private static final Pattern MESSAGE_PATTERN = Pattern.compile("<message>(.*?)</message>");

    private final DartDocumentClient dartDocumentClient;
    private final DisclosureRepository disclosureRepository;

    public DocumentCollectorService(DartDocumentClient dartDocumentClient, DisclosureRepository disclosureRepository) {
        this.dartDocumentClient = dartDocumentClient;
        this.disclosureRepository = disclosureRepository;
    }

    public void collectPending(int limit) {
        List<Disclosure> targets = disclosureRepository.findByDocumentStatus(
                DocumentStatus.PENDING, PageRequest.of(0, limit, Sort.by("id")));

        if (targets.isEmpty()) {
            log.info("처리할 PENDING 공시가 없습니다.");
            return;
        }

        log.info("PENDING 공시 {}건 처리 시작", targets.size());
        for (Disclosure disclosure : targets) {
            processDisclosure(disclosure);
        }
    }

    private void processDisclosure(Disclosure disclosure) {
        String rceptNo = disclosure.getRceptNo();
        try {
            byte[] body = dartDocumentClient.fetchDocument(rceptNo);

            if (!isZip(body)) {
                String errorXml = new String(body, StandardCharsets.UTF_8);
                String status = extractStatus(errorXml);
                if (STATUS_NO_DOCUMENT.equals(status)) {
                    disclosure.setHasDocument(false);
                    disclosure.setDocumentStatus(DocumentStatus.NO_DOCUMENT);
                    disclosureRepository.save(disclosure);
                    log.info("원문 없는 공시 (rcept_no={}): {}", rceptNo, extractMessage(errorXml));
                    return;
                }
                throw new DartApiException(
                        "document API 오류 (rcept_no=%s): status=%s".formatted(rceptNo, status));
            }

            String xml = extractXmlFromZip(body, rceptNo);
            disclosure.setDocumentStatus(DocumentStatus.FETCHED);
            disclosureRepository.save(disclosure);

            String cleanedText = XmlTextExtractor.extract(xml);

            disclosure.setHasDocument(true);
            disclosure.setRawContent(cleanedText);
            disclosure.setCharLength(cleanedText.length());
            disclosure.setDocumentStatus(DocumentStatus.PARSED);
            disclosureRepository.save(disclosure);
            log.info("공시 원문 수집 완료 (rcept_no={}): {}자", rceptNo, cleanedText.length());
        } catch (Exception e) {
            log.error("공시 원문 수집 실패 (rcept_no={})", rceptNo, e);
            disclosure.setDocumentStatus(DocumentStatus.FAILED);
            disclosureRepository.save(disclosure);
        }
    }

    private boolean isZip(byte[] bytes) {
        if (bytes == null || bytes.length < ZIP_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < ZIP_MAGIC.length; i++) {
            if (bytes[i] != ZIP_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    private String extractXmlFromZip(byte[] zipBytes, String rceptNo) throws IOException {
        String entryName = rceptNo + ".xml";
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().equals(entryName)) {
                    return new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        throw new DartApiException("ZIP 안에 %s 항목이 없습니다.".formatted(entryName));
    }

    private String extractStatus(String errorXml) {
        Matcher matcher = STATUS_PATTERN.matcher(errorXml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String extractMessage(String errorXml) {
        Matcher matcher = MESSAGE_PATTERN.matcher(errorXml);
        return matcher.find() ? matcher.group(1) : "";
    }
}
