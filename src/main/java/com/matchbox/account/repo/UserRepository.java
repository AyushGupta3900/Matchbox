package com.matchbox.account.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.matchbox.account.domain.User;

public interface UserRepository extends JpaRepository<User,Long>{
}
