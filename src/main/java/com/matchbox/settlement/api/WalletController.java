package com.matchbox.settlement.api;

import com.matchbox.settlement.service.DepositService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/wallet")
@RequiredArgsConstructor
public class WalletController {

    private final DepositService depositService;

    @PostMapping("/deposit")
    public ResponseEntity<Void> deposit(@AuthenticationPrincipal Long accountId, @RequestBody @Valid DepositRequest req) {
        long amount = Long.parseLong(req.amount());
        depositService.deposit(accountId, req.assetId(), amount);  
        return ResponseEntity.status(201).build();
    }
}
