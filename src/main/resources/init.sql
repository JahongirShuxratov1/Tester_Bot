-- Drop existing tables if they exist
DROP TABLE IF EXISTS test_scores CASCADE;
DROP TABLE IF EXISTS tests CASCADE;
DROP TABLE IF EXISTS users CASCADE;
DROP SEQUENCE IF EXISTS test_id_seq;

-- Create fresh sequence
CREATE SEQUENCE test_id_seq START 1;

-- Create tables with proper structure
CREATE TABLE tests (
    id SERIAL PRIMARY KEY,
    test_id VARCHAR(20) UNIQUE NOT NULL,
    pdf_path VARCHAR(255) NOT NULL,
    answers TEXT NOT NULL,
    time_limit INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    chat_id BIGINT PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    is_admin BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE test_scores (
    id SERIAL PRIMARY KEY,
    test_id VARCHAR(20) REFERENCES tests(test_id) ON DELETE CASCADE,
    username VARCHAR(255) NOT NULL,
    score INTEGER NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Add default admin


INSERT INTO users (chat_id, username, is_admin)
VALUES (5226031192, 'DefaultAdmin', TRUE)
ON CONFLICT (chat_id) DO UPDATE
SET is_admin = TRUE;

-- Grant permissions
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO postgres;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO postgres;
  