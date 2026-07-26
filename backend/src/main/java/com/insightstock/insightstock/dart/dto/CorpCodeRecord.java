package com.insightstock.insightstock.dart.dto;

// corpCode.xml <list> 항목 하나. corp_cls는 이 파일에 없는 필드라 포함하지 않는다
// (dart-findings.md 2-11). stockCode는 비상장(빈 값)이면 null.
public record CorpCodeRecord(String corpCode, String corpName, String stockCode) {
}
