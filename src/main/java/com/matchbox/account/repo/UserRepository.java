package com.matchbox.account.repo;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.matchbox.account.domain.User;

public interface UserRepository extends JpaRepository<User,Long>{
    Optional<User> findByEmail(String email);
}
