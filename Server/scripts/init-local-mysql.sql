-- Run as an administrator on your local MySQL instance.
-- Does not modify existing users, passwords, or tables.
CREATE DATABASE IF NOT EXISTS fragpicker
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Replace the password placeholder locally before executing these lines.
-- Never commit a file containing your actual password.
-- CREATE USER 'fragpicker'@'localhost' IDENTIFIED BY '<set-your-local-password>';
-- GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES
--     ON fragpicker.* TO 'fragpicker'@'localhost';
