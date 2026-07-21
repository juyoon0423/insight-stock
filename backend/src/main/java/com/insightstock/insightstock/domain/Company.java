package com.insightstock.insightstock.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "companies")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Company {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "corp_code", nullable = false, unique = true)
    private String corpCode;

    @Column(name = "corp_name", nullable = false)
    private String corpName;

    @Column(name = "stock_code")
    private String stockCode;

    // DART가 정의하는 외부 분류 코드(유가/코스닥/코넥스/기타)라서 우리가 값의 종류를
    // 통제하지 않는다. 내부 enum으로 못박으면 DART가 코드를 추가/변경할 때 매핑이 깨지므로
    // String으로 그대로 보존한다 (document_status/chunk_type과의 차이점).
    @Column(name = "corp_cls", nullable = false)
    private String corpCls;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
