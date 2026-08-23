-- 도메인 번호 상태. 두 계정이 함께 쓰는 공용 1행(id='default').
create table if not exists domain_state (
  id         text primary key,
  head       text not null default '',
  prefix     text not null,
  suffix     text not null,
  pad        int  not null default 0,
  num        int  not null,
  updated_at timestamptz not null default now(),
  updated_by text not null default ''
);

-- 계정별 만화 목록.
create table if not exists comics (
  id         text primary key,
  owner      text not null,
  title      text not null,
  path       text not null,
  sort       int  not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index if not exists idx_comics_owner_sort on comics(owner, sort, created_at);
