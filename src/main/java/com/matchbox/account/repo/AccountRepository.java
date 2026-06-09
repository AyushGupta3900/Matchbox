package com.matchbox.account.repo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.matchbox.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, Long> {
    @Query("select a.id from Account a join User u on u.id = a.userId " +
            "where u.email = 'system@matchbox.internal'")
    Long findSystemAccountId();
}
