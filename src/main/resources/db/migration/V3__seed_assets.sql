INSERT INTO assets (symbol, scale, name) VALUES
    ('USD', 2, 'US Dollar'),
    ('BTC', 8, 'Bitcoin')
ON CONFLICT (symbol) DO NOTHING;