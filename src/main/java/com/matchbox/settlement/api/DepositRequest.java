package com.matchbox.settlement.api;

import jakarta.validation.constraints.NotNull;

// accountId removed in 0.4 — it now comes from the authenticated JWT, not the request body.
public record DepositRequest(
        @NotNull Integer assetId,
        @NotNull String amount
) {}
