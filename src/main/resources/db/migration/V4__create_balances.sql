CREATE TABLE balances(
    account_id bigint NOT NULL REFERENCES accounts(id),
    asset_id int NOT NULL REFERENCES assets(id),
    available bigint NOT NULL DEFAULT 0,
    reserved bigint NOT NULL DEFAULT 0,
    version bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, asset_id),
    CONSTRAINT chk_balances_nonneg CHECK (available >= 0 AND reserved >= 0)
); 