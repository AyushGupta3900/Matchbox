CREATE TABLE transactions (
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type       text        NOT NULL
                           CHECK (type IN ('DEPOSIT','WITHDRAW','ORDER_RESERVE','RELEASE','TRADE_SETTLE')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id bigint      NOT NULL REFERENCES transactions(id),
    account_id     bigint      NOT NULL REFERENCES accounts(id),
    asset_id       int         NOT NULL REFERENCES assets(id),
    amount         bigint      NOT NULL CHECK (amount <> 0),  
    entry_type     text        NOT NULL
                               CHECK (entry_type IN ('DEPOSIT','WITHDRAW','RESERVE','RELEASE','SETTLE')),
    ref_id         bigint,
    created_at     timestamptz NOT NULL DEFAULT now()
);


CREATE INDEX idx_ledger_entries_account_asset_time
    ON ledger_entries (account_id, asset_id, created_at);


CREATE INDEX idx_ledger_entries_transaction
    ON ledger_entries (transaction_id);
