package com.matchbox.settlement.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matchbox.settlement.domain.Balance;
import com.matchbox.settlement.domain.BalanceId;

public interface BalanceRepository extends JpaRepository<Balance,BalanceId> {
    
}
