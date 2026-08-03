create table if not exists pledges (
  id text primary key,
  name text not null,
  team text not null,
  content text not null,
  revealed boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_pledges_created on pledges(created_at);
