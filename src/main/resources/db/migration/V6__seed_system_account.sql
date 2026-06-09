INSERT INTO users (email, password_hash, status)
VALUES ('system@matchbox.internal', 'x', 'DISABLED')
ON CONFLICT (email) DO NOTHING;

INSERT INTO accounts (user_id, status)
SELECT id, 'ACTIVE' FROM users WHERE email = 'system@matchbox.internal'
ON CONFLICT (user_id) DO NOTHING;