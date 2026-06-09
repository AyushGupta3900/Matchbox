package com.matchbox.settlement.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import com.matchbox.settlement.domain.LedgerTransaction;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction,Long> {
    
}
