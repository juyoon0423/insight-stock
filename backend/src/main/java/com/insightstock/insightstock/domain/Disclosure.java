package com.insightstock.insightstock.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "disclosures")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Disclosure {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    // Company 쪽에는 반대 방향 컬렉션을 두지 않는다(단방향). 회사당 공시 수가 많아질 수 있어
    // 굳이 컬렉션으로 들고 있기보다 조회는 Repository 쿼리로 하는 편이 실무에서 더 흔하다.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", nullable = false)
    private Company company;

    @Column(name = "rcept_no", nullable = false, unique = true)
    private String rceptNo;

    @Column(name = "report_nm", nullable = false)
    private String reportNm;

    @Column(name = "rcept_dt", nullable = false)
    private String rceptDt;

    @Column(name = "has_document", nullable = false)
    private boolean hasDocument;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_status", nullable = false)
    private DocumentStatus documentStatus = DocumentStatus.PENDING;

    @Column(name = "char_length")
    private Integer charLength;

    @Column(name = "raw_content")
    private String rawContent;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Disclosure가 자기 Chunk들의 생애주기를 관리하는 애그리게이트 루트 역할을 한다.
    // 재청킹 시 "기존 청크 삭제 후 재생성"이 흔한 흐름이라 orphanRemoval로 그 편의를 준다.
    @OneToMany(mappedBy = "disclosure", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Chunk> chunks = new ArrayList<>();
}
