package com.matchbox.asset.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "assets")
@Getter
@Setter
@NoArgsConstructor
public class Asset {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id; 

    @Column(nullable = false,unique = true)
    private String symbol;

    @Column(nullable = false)
    private Short scale;
    
    @Column(nullable = false)
    private String name;
}
