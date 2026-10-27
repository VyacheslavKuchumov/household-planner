ALTER TABLE app_user
  ADD COLUMN username text,
  ADD COLUMN account_status text NOT NULL DEFAULT 'PENDING',
  ADD COLUMN email_verified_at timestamptz,
  ADD COLUMN approved_at timestamptz,
  ADD COLUMN approved_by_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  ADD COLUMN password_change_required boolean NOT NULL DEFAULT false;

ALTER TABLE app_user
  ADD CONSTRAINT app_user_account_status_check
    CHECK (account_status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
  ADD CONSTRAINT app_user_approval_pair_check
    CHECK ((approved_at IS NULL) = (approved_by_user_id IS NULL));

CREATE UNIQUE INDEX app_user_username_unique_idx ON app_user (lower(username)) WHERE username IS NOT NULL;
CREATE INDEX app_user_status_created_idx ON app_user (account_status, created_at DESC);

CREATE TABLE platform_role (
  code text PRIMARY KEY,
  description text NOT NULL
);

INSERT INTO platform_role (code, description)
VALUES ('PLATFORM_ADMIN', 'Manage platform accounts and settings');

CREATE TABLE user_role (
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role_code text NOT NULL REFERENCES platform_role(code) ON DELETE RESTRICT,
  granted_by_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  granted_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (user_id, role_code)
);

CREATE INDEX user_role_role_idx ON user_role (role_code, user_id);

CREATE TABLE password_reset_token (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  requested_by_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  token_hash bytea NOT NULL UNIQUE,
  requested_at timestamptz NOT NULL DEFAULT now(),
  expires_at timestamptz NOT NULL,
  consumed_at timestamptz,
  revoked_at timestamptz,
  CHECK (expires_at > requested_at)
);

CREATE INDEX password_reset_user_pending_idx
  ON password_reset_token (user_id, expires_at)
  WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE TABLE admin_audit_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  actor_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  target_user_id uuid REFERENCES app_user(id) ON DELETE SET NULL,
  action text NOT NULL CHECK (action IN (
    'ACCOUNT_APPROVED', 'ACCOUNT_REJECTED', 'ACCOUNT_SUSPENDED',
    'ACCOUNT_REACTIVATED', 'PASSWORD_RESET_ISSUED', 'PASSWORD_RESET_COMPLETED',
    'PLATFORM_ROLE_GRANTED', 'PLATFORM_ROLE_REVOKED', 'BOOTSTRAP_PASSWORD_CHANGED'
  )),
  occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX admin_audit_target_time_idx ON admin_audit_log (target_user_id, occurred_at DESC);
