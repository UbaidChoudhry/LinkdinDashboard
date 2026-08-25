insert into exclude_word (word, added_at) values
  ('Senior',   '2026-01-01T00:00:00Z'),
  ('Sr',       '2026-01-01T00:00:00Z'),
  ('Staff',    '2026-01-01T00:00:00Z'),
  ('Principal','2026-01-01T00:00:00Z'),
  ('Lead',     '2026-01-01T00:00:00Z'),
  ('Manager',  '2026-01-01T00:00:00Z'),
  ('Director', '2026-01-01T00:00:00Z'),
  ('Intern',   '2026-01-01T00:00:00Z');

insert into filter_state (id, filter_version) values (1, 1);

insert into circuit_state (id, state) values (1, 'closed');
