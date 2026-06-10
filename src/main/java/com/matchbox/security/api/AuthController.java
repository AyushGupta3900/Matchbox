package com.matchbox.security.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.matchbox.account.domain.Account;
import com.matchbox.account.domain.User;
import com.matchbox.account.repo.AccountRepository;
import com.matchbox.account.repo.UserRepository;
import com.matchbox.security.JwtService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    @PostMapping("/register")
    public ResponseEntity<Void> register(@RequestBody @Valid RegisterRequest req) {
        String hash = passwordEncoder.encode(req.password());
        User usr = new User();
        usr.setEmail(req.email());
        usr.setPasswordHash(hash);
        usr.setStatus("ACTIVE");
        usr.setCreatedAt(Instant.now());
        userRepository.save(usr);
        Account acc = new Account();
        acc.setUserId(usr.getId());
        acc.setStatus("ACTIVE");
        acc.setCreatedAt(Instant.now());
        accountRepository.save(acc);
        return ResponseEntity.status(201).build();
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody @Valid LoginRequest req) {
        User usr = userRepository.findByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "email or password incorrect"));
        if (!passwordEncoder.matches(req.password(), usr.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "email or password incorrect");
        }
        Long accountId = accountRepository.findByUserId(usr.getId()).orElseThrow().getId();
        String token = jwtService.issue(accountId, "TRADER");
        return ResponseEntity.ok(Map.of(
                "access_token", token,
                "token_type", "Bearer",
                "expires_in", 900));
    }
}
