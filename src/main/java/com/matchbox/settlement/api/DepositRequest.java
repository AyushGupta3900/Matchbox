package com.matchbox.settlement.api;

import jakarta.validation.constraints.NotNull;

public record DepositRequest(
        @NotNull Long accountId,     
        @NotNull Integer assetId,
        @NotNull String amount       
) {}