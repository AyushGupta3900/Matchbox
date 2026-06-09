package com.matchbox.settlement.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.matchbox.settlement.domain.LedgerEntry;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {
    @Query("select coalesce(sum(e.amount), 0) from LedgerEntry e where e.assetId = :assetId")
    long sumAmountByAsset(Integer assetId);
}
