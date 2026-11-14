CREATE TABLE IF NOT EXISTS accounts (
    id VARCHAR(255) PRIMARY KEY,
    balance BIGINT NOT NULL CHECK (balance >= 0)
);

CREATE TABLE IF NOT EXISTS processed_transactions (
    transaction_id VARCHAR(255) PRIMARY KEY,
    operation_type VARCHAR(16) NOT NULL,
    primary_account_id VARCHAR(255) NOT NULL REFERENCES accounts(id),
    secondary_account_id VARCHAR(255) REFERENCES accounts(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
