-- Cartoon e-copy pairs. WRITTEN ONLY — NOT APPLIED to production.
-- Private bucket, per-user paths, RLS. Clients receive signed URLs from the edge function;
-- this migration does not create a public bucket or a public object policy.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('cartoon-ecopies', 'cartoon-ecopies', false, 5242880, array['image/jpeg', 'image/png'])
on conflict (id) do update
  set public = false,
      file_size_limit = excluded.file_size_limit,
      allowed_mime_types = excluded.allowed_mime_types;

create table public.cartoon_artworks (
  id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  original_path text not null,
  output_path text,
  preset text not null check (preset in ('light', 'storybook')),
  style_preset text not null check (style_preset in ('storybook_handdrawn', 'cartoon')),
  decoration_level text not null check (decoration_level in ('off', 'light', 'sparkle')),
  model text,
  spec_version text not null check (char_length(spec_version) between 1 and 32),
  status text not null check (status in ('pending', 'ready', 'fallback_crop', 'deleting')),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint cartoon_artworks_original_path check (
    original_path in (
      user_id::text || '/' || id::text || '/original.jpg',
      user_id::text || '/' || id::text || '/original.png'
    )
  ),
  constraint cartoon_artworks_output_path check (
    output_path is null or output_path in (
      user_id::text || '/' || id::text || '/output.jpg',
      user_id::text || '/' || id::text || '/output.png'
    )
  )
);

create index cartoon_artworks_user_created_idx
  on public.cartoon_artworks (user_id, created_at desc);

alter table public.cartoon_artworks enable row level security;
alter table public.cartoon_artworks force row level security;

revoke all on table public.cartoon_artworks from public, anon;
grant select, insert, update, delete on table public.cartoon_artworks to authenticated;
grant all on table public.cartoon_artworks to service_role;

create policy cartoon_artworks_select on public.cartoon_artworks
  for select to authenticated
  using ((select auth.uid()) = user_id);

create policy cartoon_artworks_insert on public.cartoon_artworks
  for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy cartoon_artworks_update on public.cartoon_artworks
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create policy cartoon_artworks_delete on public.cartoon_artworks
  for delete to authenticated
  using ((select auth.uid()) = user_id);

-- Owner folder is the first path segment: {user_id}/{artwork_id}/original.jpg
create policy cartoon_ecopies_select on storage.objects
  for select to authenticated
  using (
    bucket_id = 'cartoon-ecopies'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy cartoon_ecopies_insert on storage.objects
  for insert to authenticated
  with check (
    bucket_id = 'cartoon-ecopies'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy cartoon_ecopies_update on storage.objects
  for update to authenticated
  using (
    bucket_id = 'cartoon-ecopies'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  )
  with check (
    bucket_id = 'cartoon-ecopies'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );

create policy cartoon_ecopies_delete on storage.objects
  for delete to authenticated
  using (
    bucket_id = 'cartoon-ecopies'
    and (storage.foldername(name))[1] = (select auth.uid())::text
  );
