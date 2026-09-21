-- Repair only the known corrupted nickname from the original admin seed import.
-- Hex literals make this repair independent of the terminal's text encoding.
-- Safe to run repeatedly; customized nicknames and other users are untouched.
SET NAMES utf8mb4;

UPDATE beauty_saas.sys_user
SET nickname = CONVERT(0xE7AEA1E79086E59198 USING utf8mb4)
WHERE id = 1
  AND username = 'admin'
  AND HEX(nickname) = 'C3A7C2AEC2A1C3A7C290E280A0C3A5E28098CB9C';
