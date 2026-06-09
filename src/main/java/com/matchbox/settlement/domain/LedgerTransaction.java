package com.matchbox.settlement.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="transactions")
@Getter
@Setter
@NoArgsConstructor
public class LedgerTransaction{
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; 

    @Column(nullable = false)
    private String type; 

    @Column(nullable = false)
    private Instant createdAt; 

}
