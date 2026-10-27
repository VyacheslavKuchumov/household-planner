ALTER TABLE app_user
  ADD COLUMN password_hash text;

COMMENT ON COLUMN app_user.password_hash IS
  'Argon2id password hash; NULL means password enrollment is incomplete.';

CREATE TABLE auth_session (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  created_at timestamptz NOT NULL DEFAULT now(),
  last_seen_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  revoked_at timestamptz,
  CHECK (expires_at > created_at)
);

CREATE INDEX auth_session_user_active_idx
  ON auth_session (user_id, expires_at)
  WHERE revoked_at IS NULL;

CREATE TABLE refresh_token (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id uuid NOT NULL REFERENCES auth_session(id) ON DELETE CASCADE,
  replaced_by_token_id uuid REFERENCES refresh_token(id),
  token_hash bytea NOT NULL UNIQUE,
  issued_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  used_at timestamptz,
  revoked_at timestamptz,
  CHECK (expires_at > issued_at),
  CHECK (replaced_by_token_id IS NULL OR replaced_by_token_id <> id)
);

CREATE INDEX refresh_token_session_expiry_idx
  ON refresh_token (session_id, expires_at);
