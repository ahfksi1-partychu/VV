-- MyCarHub Drive To-Do 차량 ↔ 휴대폰 동기화 (Supabase / Postgres). 설계: docs/DRIVE_SYNC.md
--
-- 서버는 암호문만 저장한다 (주차 위치·물건·할 일 내용은 기기 안에서 AES-256-GCM으로 암호화). 서버가 아는 것:
-- 그룹·기기 역할(car/phone), 레코드 종류·id·수정 시각·삭제 여부, 암호문 크기, 접속 시각.
--
-- 인증: 기기마다 Supabase 익명 로그인(Anonymous Sign-In) 사용자 1개 (기기별 액세스 토큰 + 갱신 토큰).
--       6자리 페어링 코드는 5분 동안 한 번만 쓰이는 짝짓기용이고, 장기 인증에는 쓰지 않는다.
-- 접근 제어: 모든 테이블에 RLS. 레코드는 '확인된 같은 그룹 구성원'만 읽고 쓸 수 있다. 다른 사용자의 데이터는 보이지 않는다.
-- 삭제: 레코드를 지우지 않고 deleted=true 로 덮어쓴다 (삭제 정책 없음). 그룹을 해제하면 그룹의 레코드가 모두 지워진다.
--
-- 여러 번 실행해도 된다: 표·색인·시퀀스는 없을 때만 만들고, 함수·트리거·정책·권한은 다시 정의한다 (기존 레코드는 그대로).
-- 전체가 한 트랜잭션이라 중간에 실패하면 아무것도 바뀌지 않는다.

begin;

create extension if not exists pgcrypto with schema extensions;

-- ---------------------------------------------------------------------------
-- 테이블
-- ---------------------------------------------------------------------------

create table if not exists public.sync_groups (
    id uuid primary key default gen_random_uuid(),
    created_at timestamptz not null default now()
);

-- 기기(익명 사용자) ↔ 그룹. 기기 하나는 그룹 하나에만. 휴대폰은 차량 화면에서 확인 번호를 확인해야 confirmed.
create table if not exists public.sync_members (
    group_id uuid not null references public.sync_groups (id) on delete cascade,
    user_id uuid not null references auth.users (id) on delete cascade,
    role text not null check (role in ('car', 'phone')),
    confirmed boolean not null default false,
    joined_at timestamptz not null default now(),
    primary key (group_id, user_id),
    unique (user_id)
);

-- 페어링 제안 (차량이 만든다). 코드는 해시로만 저장, 5분 뒤 만료, 한 번만 쓸 수 있다.
create table if not exists public.sync_pairings (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.sync_groups (id) on delete cascade,
    code_hash text not null,
    car_public_key text not null check (char_length(car_public_key) <= 200),
    phone_public_key text check (char_length(phone_public_key) <= 200),
    phone_user uuid references auth.users (id) on delete cascade,
    expires_at timestamptz not null,
    claimed_at timestamptz
);
create index if not exists sync_pairings_open on public.sync_pairings (code_hash) where claimed_at is null;

-- 코드 입력 시도 기록 (무차별 대입 제한)
create table if not exists public.sync_pairing_attempts (
    user_id uuid not null,
    at timestamptz not null default now()
);
create index if not exists sync_pairing_attempts_user on public.sync_pairing_attempts (user_id, at);
create index if not exists sync_pairing_attempts_at on public.sync_pairing_attempts (at);

create sequence if not exists public.sync_records_seq;

-- 동기화 레코드 (할 일·주차 기록·차에 둔 물건 하나당 한 줄). 내용은 암호문.
create table if not exists public.sync_records (
    group_id uuid not null references public.sync_groups (id) on delete cascade,
    kind text not null,
    record_id text not null check (char_length(record_id) between 1 and 64),
    updated_at bigint not null,
    deleted boolean not null default false,
    origin text not null check (char_length(origin) <= 64),
    nonce text not null check (char_length(nonce) <= 32),
    ciphertext text not null check (char_length(ciphertext) <= 16384),
    seq bigint not null default nextval('public.sync_records_seq'),
    author uuid default auth.uid(),
    server_at timestamptz not null default now(),
    primary key (group_id, kind, record_id)
);
alter sequence public.sync_records_seq owned by public.sync_records.seq;
create index if not exists sync_records_pull on public.sync_records (group_id, seq);

-- 레코드 종류: 이름을 붙여 다시 정의한다 (이전에 만든 표도 car_item을 받도록)
alter table public.sync_records drop constraint if exists sync_records_kind_check;
alter table public.sync_records add constraint sync_records_kind_check check (kind in ('todo', 'parking', 'car_item'));

-- ---------------------------------------------------------------------------
-- 규칙
-- ---------------------------------------------------------------------------

-- 확인된 같은 그룹 구성원인지 (RLS에서 사용)
create or replace function public.is_sync_member(g uuid) returns boolean
    language sql stable security definer set search_path = ''
as $$
    select exists (
        select 1 from public.sync_members m
        where m.group_id = g and m.user_id = auth.uid() and m.confirmed
    );
$$;

-- 레코드 덮어쓰기 규칙 (앱의 SyncMerge와 같음): 더 나중 수정 시각만 반영, 같은 시각이면 삭제 우선, 그다음 origin 큰 쪽.
-- 옛 변경·같은 변경을 다시 보내면 조용히 무시한다 (중복·되살아남 방지). 시계가 하루 넘게 앞선 값은 거부.
-- 번호(seq)·작성자·서버 시각은 기기가 보낸 값을 쓰지 않고 여기서 정한다.
create or replace function public.sync_records_guard() returns trigger
    language plpgsql set search_path = ''
as $$
begin
    if new.updated_at > (extract(epoch from now()) * 1000)::bigint + 86400000 then
        raise exception 'updated_at is in the future';
    end if;
    if tg_op = 'UPDATE' then
        if new.group_id <> old.group_id or new.kind <> old.kind or new.record_id <> old.record_id then
            raise exception 'record key is immutable';
        end if;
        if (new.updated_at, new.deleted, new.origin) <= (old.updated_at, old.deleted, old.origin) then
            return null;
        end if;
    end if;
    new.seq := nextval('public.sync_records_seq');
    new.author := auth.uid();
    new.server_at := now();
    return new;
end;
$$;

drop trigger if exists sync_records_guard on public.sync_records;
create trigger sync_records_guard
    before insert or update on public.sync_records
    for each row execute function public.sync_records_guard();

-- ---------------------------------------------------------------------------
-- 권한과 행 단위 접근 제어 (RLS)
-- ---------------------------------------------------------------------------

alter table public.sync_groups enable row level security;
alter table public.sync_members enable row level security;
alter table public.sync_pairings enable row level security;
alter table public.sync_pairing_attempts enable row level security;
alter table public.sync_records enable row level security;

-- 로그인하지 않은 요청(anon)은 아무것도 못 한다
revoke all on public.sync_groups, public.sync_members, public.sync_pairings,
    public.sync_pairing_attempts, public.sync_records from anon;
-- 페어링 표는 아래 함수로만 다룬다 (권한·정책 없음 = 직접 접근 불가)
revoke all on public.sync_pairings, public.sync_pairing_attempts from authenticated;
revoke all on public.sync_groups, public.sync_members from authenticated;
grant select on public.sync_groups, public.sync_members to authenticated;
revoke all on public.sync_records from authenticated;
grant select, insert, update on public.sync_records to authenticated;
-- 레코드를 넣을 때 번호(seq 기본값·트리거의 nextval)는 넣는 사용자 권한으로 계산되므로 usage만 준다 (번호를 읽거나 바꿀 수는 없음)
revoke all on sequence public.sync_records_seq from anon, authenticated;
grant usage on sequence public.sync_records_seq to authenticated;

drop policy if exists "member reads own group" on public.sync_groups;
create policy "member reads own group" on public.sync_groups
    for select to authenticated using (public.is_sync_member(id));

drop policy if exists "member reads group members" on public.sync_members;
create policy "member reads group members" on public.sync_members
    for select to authenticated using (user_id = auth.uid() or public.is_sync_member(group_id));

drop policy if exists "member reads records" on public.sync_records;
create policy "member reads records" on public.sync_records
    for select to authenticated using (public.is_sync_member(group_id));

drop policy if exists "member inserts records" on public.sync_records;
create policy "member inserts records" on public.sync_records
    for insert to authenticated with check (public.is_sync_member(group_id));

drop policy if exists "member updates records" on public.sync_records;
create policy "member updates records" on public.sync_records
    for update to authenticated
    using (public.is_sync_member(group_id))
    with check (public.is_sync_member(group_id));
-- delete 권한·정책 없음: 레코드 삭제는 deleted=true 로만

-- ---------------------------------------------------------------------------
-- 페어링 함수 (security definer: 위 표에 직접 접근하지 않고 이 함수로만)
-- ---------------------------------------------------------------------------

-- 차량: 페어링 코드를 만든다 (주차 중 화면에 표시). 차량 그룹이 없으면 만든다.
-- 새 코드를 만들면 이전 페어링 시도(코드 입력 전·확인 전)는 모두 무효가 되고, 확인되지 않은 휴대폰은 그룹에서 빠진다.
create or replace function public.create_pairing(car_public_key text)
    returns table (pairing_id uuid, code text, expires_at timestamptz)
    language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    g uuid;
    c text;
    h text;
    new_id uuid;
    expires timestamptz := now() + interval '5 minutes';
begin
    if uid is null then raise exception 'not signed in'; end if;
    if create_pairing.car_public_key is null or char_length(create_pairing.car_public_key) > 200 then
        raise exception 'bad key';
    end if;
    select m.group_id into g from public.sync_members m where m.user_id = uid and m.role = 'car';
    if g is null then
        if exists (select 1 from public.sync_members m where m.user_id = uid) then
            raise exception 'this device is paired as a phone';
        end if;
        insert into public.sync_groups default values returning id into g;
        insert into public.sync_members (group_id, user_id, role, confirmed) values (g, uid, 'car', true);
    end if;
    delete from public.sync_members m where m.group_id = g and m.role = 'phone' and not m.confirmed;
    delete from public.sync_pairings p where p.group_id = g;
    loop
        c := lpad(((('x' || encode(extensions.gen_random_bytes(4), 'hex'))::bit(32)::bigint) % 1000000)::text, 6, '0');
        h := encode(extensions.digest(c, 'sha256'), 'hex');
        exit when not exists (
            select 1 from public.sync_pairings p where p.code_hash = h and p.claimed_at is null and p.expires_at > now()
        );
    end loop;
    insert into public.sync_pairings (group_id, code_hash, car_public_key, expires_at)
        values (g, h, create_pairing.car_public_key, expires)
        returning id into new_id;
    return query select new_id, c, expires;
end;
$$;

-- 휴대폰: 차량 화면의 코드를 입력한다. 사용자당 10분에 5번, 전체 1분에 60번까지만 시도할 수 있다.
-- 성공해도 차량에서 확인 번호를 확인하기 전(confirmed=false)에는 레코드에 접근할 수 없다.
-- 이 휴대폰이 이전에 코드를 입력하고 확인받지 못한 상태였다면 그 시도는 버리고 새로 입력한다.
create or replace function public.claim_pairing(code text, phone_public_key text)
    returns table (pairing_id uuid, car_public_key text)
    language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    p public.sync_pairings%rowtype;
begin
    if uid is null then raise exception 'not signed in'; end if;
    if claim_pairing.phone_public_key is null or char_length(claim_pairing.phone_public_key) > 200 then
        raise exception 'bad key';
    end if;
    delete from public.sync_pairing_attempts a where a.at < now() - interval '1 day';
    if (select count(*) from public.sync_pairing_attempts a where a.user_id = uid and a.at > now() - interval '10 minutes') >= 5
        or (select count(*) from public.sync_pairing_attempts a where a.at > now() - interval '1 minute') >= 60 then
        raise exception 'too many attempts';
    end if;
    -- 확인받지 못한 이전 시도 정리 (확인된 휴대폰·차량은 먼저 동기화를 해제해야 한다)
    delete from public.sync_pairings s where s.phone_user = uid;
    delete from public.sync_members m where m.user_id = uid and m.role = 'phone' and not m.confirmed;
    if exists (select 1 from public.sync_members m where m.user_id = uid) then
        raise exception 'this device is already paired';
    end if;
    insert into public.sync_pairing_attempts (user_id) values (uid);
    select * into p from public.sync_pairings s
        where s.code_hash = encode(extensions.digest(claim_pairing.code, 'sha256'), 'hex')
          and s.claimed_at is null and s.expires_at > now()
        for update;
    -- 틀린 코드: 오류를 내면 시도 기록까지 취소되므로, 빈 결과로 돌려준다 (앱은 '코드가 맞지 않음'으로 표시)
    if not found then return; end if;
    update public.sync_pairings s
        set phone_public_key = claim_pairing.phone_public_key, phone_user = uid, claimed_at = now()
        where s.id = p.id;
    insert into public.sync_members (group_id, user_id, role, confirmed) values (p.group_id, uid, 'phone', false);
    return query select p.id, p.car_public_key;
end;
$$;

-- 차량: 휴대폰이 코드를 입력했는지 확인하고 휴대폰 공개키를 받는다 (확인 번호 계산용)
create or replace function public.pairing_status(pairing_id uuid)
    returns table (claimed boolean, phone_public_key text, expires_at timestamptz)
    language plpgsql security definer set search_path = ''
as $$
begin
    return query
        select s.claimed_at is not null, s.phone_public_key, s.expires_at
        from public.sync_pairings s
        join public.sync_members m on m.group_id = s.group_id and m.user_id = auth.uid() and m.role = 'car'
        where s.id = pairing_status.pairing_id;
end;
$$;

-- 차량: 두 화면의 확인 번호가 같다고 사용자가 확인하면 휴대폰을 활성화한다 (코드 입력 후 10분 안에만).
-- 이전 휴대폰은 그룹에서 빠진다 (휴대폰 1대).
create or replace function public.confirm_pairing(pairing_id uuid)
    returns void
    language plpgsql security definer set search_path = ''
as $$
declare
    p public.sync_pairings%rowtype;
begin
    select s.* into p from public.sync_pairings s
        join public.sync_members m on m.group_id = s.group_id and m.user_id = auth.uid() and m.role = 'car'
        where s.id = confirm_pairing.pairing_id
          and s.claimed_at is not null and s.claimed_at > now() - interval '10 minutes'
          and s.phone_user is not null;
    if not found then raise exception 'no claimed pairing'; end if;
    delete from public.sync_members m where m.group_id = p.group_id and m.role = 'phone' and m.user_id <> p.phone_user;
    update public.sync_members m set confirmed = true where m.group_id = p.group_id and m.user_id = p.phone_user;
    delete from public.sync_pairings s where s.id = p.id;
end;
$$;

-- 차량: 확인 번호가 다르면 거절한다 (그 휴대폰을 그룹에서 뺀다)
create or replace function public.reject_pairing(pairing_id uuid)
    returns void
    language plpgsql security definer set search_path = ''
as $$
declare
    p public.sync_pairings%rowtype;
begin
    select s.* into p from public.sync_pairings s
        join public.sync_members m on m.group_id = s.group_id and m.user_id = auth.uid() and m.role = 'car'
        where s.id = reject_pairing.pairing_id;
    if not found then return; end if;
    delete from public.sync_members m where m.group_id = p.group_id and m.user_id = p.phone_user and not m.confirmed;
    delete from public.sync_pairings s where s.id = p.id;
end;
$$;

-- 동기화 해제: 휴대폰은 그룹에서 빠지고, 차량이 해제하면 그룹과 서버의 레코드가 모두 지워진다. 기기 안의 데이터는 그대로.
create or replace function public.leave_sync()
    returns void
    language plpgsql security definer set search_path = ''
as $$
declare
    uid uuid := auth.uid();
    g uuid;
    r text;
begin
    select m.group_id, m.role into g, r from public.sync_members m where m.user_id = uid;
    if g is null then return; end if;
    if r = 'car' then
        delete from public.sync_groups s where s.id = g;
    else
        delete from public.sync_pairings s where s.phone_user = uid;
        delete from public.sync_members m where m.user_id = uid;
    end if;
end;
$$;

revoke all on function public.is_sync_member(uuid), public.create_pairing(text), public.claim_pairing(text, text),
    public.pairing_status(uuid), public.confirm_pairing(uuid), public.reject_pairing(uuid), public.leave_sync()
    from public, anon;
grant execute on function public.is_sync_member(uuid), public.create_pairing(text), public.claim_pairing(text, text),
    public.pairing_status(uuid), public.confirm_pairing(uuid), public.reject_pairing(uuid), public.leave_sync()
    to authenticated;

commit;
