CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE household (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  name text NOT NULL,
  timezone text NOT NULL DEFAULT 'Asia/Yekaterinburg',
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  display_name text NOT NULL,
  email text NOT NULL UNIQUE,
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE membership (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
  role text NOT NULL CHECK (role IN ('OWNER', 'MEMBER')),
  joined_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (household_id, user_id),
  UNIQUE (id, household_id)
);

CREATE TABLE work_schedule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  membership_id uuid NOT NULL REFERENCES membership(id) ON DELETE CASCADE,
  pattern_type text NOT NULL CHECK (pattern_type IN ('WEEK_5_2', 'CYCLE_2_2')),
  anchor_date date,
  work_days jsonb,
  work_days_per_cycle smallint,
  rest_days_per_cycle smallint,
  shift_start time NOT NULL,
  shift_end time NOT NULL,
  valid_from date NOT NULL,
  valid_until date,
  CHECK (valid_until IS NULL OR valid_until >= valid_from),
  CHECK (
    (pattern_type = 'WEEK_5_2' AND work_days IS NOT NULL AND anchor_date IS NULL)
    OR (pattern_type = 'CYCLE_2_2' AND anchor_date IS NOT NULL AND work_days IS NULL
        AND work_days_per_cycle = 2 AND rest_days_per_cycle = 2)
  )
);

CREATE TABLE availability_window (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  membership_id uuid NOT NULL REFERENCES membership(id) ON DELETE CASCADE,
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (ends_at > starts_at)
);

CREATE TABLE rest_block (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  created_by_membership_id uuid NOT NULL REFERENCES membership(id),
  reserved_for_membership_id uuid REFERENCES membership(id) ON DELETE CASCADE,
  title text NOT NULL,
  kind text NOT NULL CHECK (kind IN ('PERSONAL', 'SHARED', 'VACATION')),
  starts_at timestamptz NOT NULL,
  ends_at timestamptz NOT NULL,
  protected boolean NOT NULL DEFAULT true,
  CHECK (ends_at > starts_at)
);

CREATE TABLE task_template (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  title text NOT NULL,
  recurrence_rule text,
  estimated_minutes integer CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),
  active boolean NOT NULL DEFAULT true
);

CREATE TABLE task_occurrence (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  template_id uuid REFERENCES task_template(id) ON DELETE SET NULL,
  title_snapshot text NOT NULL,
  status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'IN_PROGRESS', 'DONE', 'SKIPPED')),
  due_at timestamptz NOT NULL,
  estimated_minutes integer CHECK (estimated_minutes IS NULL OR estimated_minutes > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE assignment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  occurrence_id uuid NOT NULL REFERENCES task_occurrence(id) ON DELETE CASCADE,
  membership_id uuid NOT NULL REFERENCES membership(id) ON DELETE CASCADE,
  status text NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED', 'ACCEPTED', 'DECLINED', 'DONE')),
  assigned_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (occurrence_id, membership_id)
);

CREATE TABLE completion (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  assignment_id uuid NOT NULL UNIQUE REFERENCES assignment(id) ON DELETE CASCADE,
  completed_by_membership_id uuid NOT NULL REFERENCES membership(id),
  completed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE inventory_item (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  name text NOT NULL,
  unit text NOT NULL,
  quantity numeric(12,3) NOT NULL DEFAULT 0 CHECK (quantity >= 0),
  minimum_quantity numeric(12,3) NOT NULL DEFAULT 0 CHECK (minimum_quantity >= 0),
  best_before date
);

CREATE TABLE budget (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  name text NOT NULL,
  currency char(3) NOT NULL,
  period_start date NOT NULL,
  period_end date NOT NULL,
  CHECK (period_end >= period_start)
);

CREATE TABLE budget_category (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  budget_id uuid NOT NULL REFERENCES budget(id) ON DELETE CASCADE,
  name text NOT NULL,
  limit_minor bigint NOT NULL CHECK (limit_minor >= 0),
  UNIQUE (budget_id, name)
);

CREATE TABLE budget_transaction (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  category_id uuid NOT NULL REFERENCES budget_category(id),
  recorded_by_membership_id uuid REFERENCES membership(id) ON DELETE SET NULL,
  description text NOT NULL,
  amount_minor bigint NOT NULL CHECK (amount_minor >= 0),
  currency char(3) NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE stock_movement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  inventory_item_id uuid NOT NULL REFERENCES inventory_item(id) ON DELETE CASCADE,
  recorded_by_membership_id uuid REFERENCES membership(id) ON DELETE SET NULL,
  transaction_id uuid REFERENCES budget_transaction(id) ON DELETE SET NULL,
  quantity_delta numeric(12,3) NOT NULL CHECK (quantity_delta <> 0),
  occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE shopping_list_entry (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  household_id uuid NOT NULL REFERENCES household(id) ON DELETE CASCADE,
  inventory_item_id uuid REFERENCES inventory_item(id) ON DELETE SET NULL,
  added_by_membership_id uuid REFERENCES membership(id) ON DELETE SET NULL,
  label text NOT NULL,
  quantity numeric(12,3) NOT NULL DEFAULT 1 CHECK (quantity > 0),
  status text NOT NULL DEFAULT 'NEEDED' CHECK (status IN ('NEEDED', 'BOUGHT', 'CANCELLED')),
  created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX availability_window_member_period_idx ON availability_window (membership_id, starts_at, ends_at);
CREATE INDEX rest_block_household_period_idx ON rest_block (household_id, starts_at, ends_at);
CREATE INDEX task_occurrence_household_due_idx ON task_occurrence (household_id, due_at);
CREATE INDEX assignment_member_status_idx ON assignment (membership_id, status);
CREATE INDEX stock_movement_item_time_idx ON stock_movement (inventory_item_id, occurred_at DESC);
CREATE INDEX budget_transaction_category_time_idx ON budget_transaction (category_id, occurred_at DESC);
