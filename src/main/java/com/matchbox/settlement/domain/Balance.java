package com.matchbox.settlement.domain;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "balances")
@Setter
@Getter
@NoArgsConstructor
public class Balance {
    @EmbeddedId 
    private BalanceId id;

    private long available;
    
    private long reserved;
    @Version 
    private long version;
}
