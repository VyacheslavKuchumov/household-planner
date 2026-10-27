INSERT INTO task_template (id, household_id, title, recurrence_rule, estimated_minutes) VALUES
  ('50000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Убраться на кухне', 'WEEKLY', 25),
  ('50000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'Вынести мусор', 'EVERY_2_DAYS', 10)
ON CONFLICT (id) DO NOTHING;

INSERT INTO task_occurrence (id, household_id, template_id, title_snapshot, status, due_at, estimated_minutes) VALUES
  ('60000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000001', 'Убраться на кухне', 'OPEN', date_trunc('day', now()) + interval '1 day' + interval '18 hours', 25),
  ('60000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '50000000-0000-4000-8000-000000000002', 'Вынести мусор', 'OPEN', date_trunc('day', now()) + interval '2 days' + interval '19 hours', 10)
ON CONFLICT (id) DO NOTHING;

INSERT INTO assignment (id, occurrence_id, membership_id) VALUES
  ('70000000-0000-4000-8000-000000000001', '60000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001'),
  ('70000000-0000-4000-8000-000000000002', '60000000-0000-4000-8000-000000000002', '30000000-0000-4000-8000-000000000002')
ON CONFLICT (id) DO NOTHING;

INSERT INTO inventory_item (id, household_id, name, unit, quantity, minimum_quantity) VALUES
  ('80000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Молоко', 'л', 1, 2),
  ('80000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', 'Рис', 'кг', 2.5, 1),
  ('80000000-0000-4000-8000-000000000003', '10000000-0000-4000-8000-000000000001', 'Средство для уборки', 'шт', 1, 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO shopping_list_entry (id, household_id, inventory_item_id, added_by_membership_id, label, quantity) VALUES
  ('90000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '80000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000002', 'Молоко', 2)
ON CONFLICT (id) DO NOTHING;

INSERT INTO budget (id, household_id, name, currency, period_start, period_end)
VALUES ('a0000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', 'Общий бюджет', 'RUB', date_trunc('month', CURRENT_DATE)::date, (date_trunc('month', CURRENT_DATE) + interval '1 month - 1 day')::date)
ON CONFLICT (id) DO NOTHING;

INSERT INTO budget_category (id, budget_id, name, limit_minor) VALUES
  ('b0000000-0000-4000-8000-000000000001', 'a0000000-0000-4000-8000-000000000001', 'Продукты', 3000000),
  ('b0000000-0000-4000-8000-000000000002', 'a0000000-0000-4000-8000-000000000001', 'Дом', 1000000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO budget_transaction (id, category_id, recorded_by_membership_id, description, amount_minor, currency) VALUES
  ('c0000000-0000-4000-8000-000000000001', 'b0000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 'Покупка продуктов', 125000, 'RUB')
ON CONFLICT (id) DO NOTHING;

INSERT INTO stock_movement (id, inventory_item_id, recorded_by_membership_id, transaction_id, quantity_delta) VALUES
  ('d0000000-0000-4000-8000-000000000001', '80000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 'c0000000-0000-4000-8000-000000000001', 1)
ON CONFLICT (id) DO NOTHING;

INSERT INTO rest_block (id, household_id, created_by_membership_id, reserved_for_membership_id, title, kind, starts_at, ends_at, protected) VALUES
  ('e0000000-0000-4000-8000-000000000001', '10000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000001', 'Личное время', 'PERSONAL', date_trunc('day', now()) + interval '3 days' + interval '19 hours', date_trunc('day', now()) + interval '3 days' + interval '22 hours', true),
  ('e0000000-0000-4000-8000-000000000002', '10000000-0000-4000-8000-000000000001', '30000000-0000-4000-8000-000000000002', NULL, 'Совместный отдых', 'SHARED', date_trunc('day', now()) + interval '5 days' + interval '12 hours', date_trunc('day', now()) + interval '5 days' + interval '18 hours', true)
ON CONFLICT (id) DO NOTHING;
