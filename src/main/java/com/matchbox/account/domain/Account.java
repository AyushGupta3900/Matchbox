package com.matchbox.account.domain;

import java.time.Instant;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name="accounts")
@Getter
@Setter
@NoArgsConstructor
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; 

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;
}
