-- Shared phrase library. No user id, device id, or IP.
create table if not exists public.phrases (
  zh_hash text primary key,
  zh text not null,
  en text not null,
  context_type text,
  quality_score int not null default 100,
  uses int not null default 0,
  source_layer text,
  created_at timestamptz not null default now()
);

create table if not exists public.moderation_queue (
  id bigint generated always as identity primary key,
  zh_hash text not null,
  zh text not null,
  en text not null,
  reason text,
  created_at timestamptz not null default now()
);

alter table public.phrases enable row level security;
alter table public.moderation_queue enable row level security;

create policy phrases_read on public.phrases for select using (true);
create policy phrases_insert on public.phrases for insert with check (true);
create policy phrases_score on public.phrases for update using (true) with check (true);

create policy moderation_insert on public.moderation_queue for insert with check (true);
create policy moderation_read on public.moderation_queue for select using (true);
