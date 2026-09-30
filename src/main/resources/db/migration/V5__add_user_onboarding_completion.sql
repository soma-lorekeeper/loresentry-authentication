ALTER TABLE users ADD COLUMN onboarding_completed_at timestamptz;

UPDATE users SET onboarding_completed_at = created_at;
