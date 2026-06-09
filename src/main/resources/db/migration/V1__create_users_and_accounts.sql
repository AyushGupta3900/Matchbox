CREATE TABLE users(
    id            bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    email         text        NOT NULL UNIQUE,
    password_hash text        NOT NULL,
    status        text        NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE','DISABLED')),
    created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts(
    id         bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id    bigint      NOT NULL UNIQUE REFERENCES users(id),  
    status     text        NOT NULL DEFAULT 'ACTIVE'
                           CHECK (status IN ('ACTIVE','DISABLED')),
    created_at timestamptz NOT NULL DEFAULT now()
);