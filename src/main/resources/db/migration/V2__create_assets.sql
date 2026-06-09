CREATE TABLE assets(
    id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol text NOT NULL UNIQUE,
    scale smallint NOT NULL CHECK (scale>=0),
    name text NOT NULL
);