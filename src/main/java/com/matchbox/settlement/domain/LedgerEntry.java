package com.matchbox.settlement.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="ledger_entries")
@Getter
@Setter
@NoArgsConstructor
public class LedgerEntry{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; 

    @Column(nullable = false)
    private Long transactionId;

    @Column(nullable = false)
    private Long accountId;

    @Column(nullable = false)
    private Integer assetId; 

    @Column(nullable = false)
    private Long amount; 

    @Column(nullable = false)
    private String entryType; 

    @Column(nullable = true)
    private Long refId; 

    @Column(nullable = false)
    private Instant createdAt; 

}
