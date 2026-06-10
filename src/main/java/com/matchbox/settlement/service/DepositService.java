package com.matchbox.settlement.service;

import lombok.RequiredArgsConstructor;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.matchbox.account.repo.AccountRepository;
import com.matchbox.settlement.domain.Balance;
import com.matchbox.settlement.domain.BalanceId;
import com.matchbox.settlement.domain.LedgerEntry;
import com.matchbox.settlement.domain.LedgerTransaction;
import com.matchbox.settlement.repo.BalanceRepository;
import com.matchbox.settlement.repo.LedgerEntryRepository;
import com.matchbox.settlement.repo.LedgerTransactionRepository;

@Service
@RequiredArgsConstructor
public class DepositService {

    private final AccountRepository accountRepo;
    private final LedgerTransactionRepository txRepo;
    private final LedgerEntryRepository entryRepo;
    private final BalanceRepository balanceRepo;

    @Transactional
    public void deposit(long userAccountId, int assetId, long amount) {
        if (amount <= 0) throw new IllegalArgumentException();

        Long systemAccountId = accountRepo.findSystemAccountId();

        LedgerTransaction tx = new LedgerTransaction();
        tx.setType("DEPOSIT");
        tx.setCreatedAt(Instant.now());
        tx = txRepo.save(tx);
        Long tId = tx.getId();

        LedgerEntry userEntry = new LedgerEntry();
        userEntry.setAccountId(userAccountId);
        userEntry.setAssetId(assetId);
        userEntry.setAmount(amount);
        userEntry.setEntryType("DEPOSIT");
        userEntry.setCreatedAt(Instant.now());
        userEntry.setTransactionId(tId);
        entryRepo.save(userEntry);

        LedgerEntry systemEntry = new LedgerEntry();
        systemEntry.setAccountId(systemAccountId);
        systemEntry.setAssetId(assetId);
        systemEntry.setAmount(-amount);
        systemEntry.setEntryType("DEPOSIT");
        systemEntry.setCreatedAt(Instant.now());
        systemEntry.setTransactionId(tId);
        entryRepo.save(systemEntry);

        BalanceId bId = new BalanceId();
        bId.setAccountId(userAccountId);
        bId.setAssetId(assetId);
        Balance bal = balanceRepo.findById(bId).orElseGet(() -> {
            Balance b = new Balance();
            b.setId(bId);
            b.setAvailable(0);
            b.setReserved(0);
            return b;
        });
        bal.setAvailable(bal.getAvailable() + amount);
        balanceRepo.save(bal);
    }
}