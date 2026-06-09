package com.matchbox.settlement.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Objects;
import lombok.*;

@Embeddable
@Getter 
@Setter 
@NoArgsConstructor
public class BalanceId implements Serializable {
    private Long accountId;  
    private Integer assetId;    

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BalanceId that)) return false;
        return Objects.equals(accountId, that.accountId) && Objects.equals(assetId, that.assetId);
    }
    @Override public int hashCode() { return Objects.hash(accountId, assetId); }
}