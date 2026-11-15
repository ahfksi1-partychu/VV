-- MyCarHub 동기화 서버 검증 (마이그레이션 적용 후 SQL Editor에서 실행).
-- 가짜 익명 사용자 4명을 만들어 페어링·RLS·덮어쓰기 규칙을 확인하고, 마지막에 ROLLBACK 하므로 아무 데이터도 남지 않는다.
-- 성공하면 "DRIVE SYNC CHECK: ALL PASSED" 알림, 실패하면 "FAIL: ..." 오류로 멈춘다.

begin;

do $$
declare
    car1 uuid := gen_random_uuid();
    phone1 uuid := gen_random_uuid();
    car2 uuid := gen_random_uuid();
    attacker uuid := gen_random_uuid();
    g1 uuid;
    v_pid uuid;
    v_code text;
    v_key text;
    n int;
    now_ms bigint := (extract(epoch from now()) * 1000)::bigint;
begin
    insert into auth.users (id, aud, role) values
        (car1, 'authenticated', 'authenticated'), (phone1, 'authenticated', 'authenticated'),
        (car2, 'authenticated', 'authenticated'), (attacker, 'authenticated', 'authenticated');

    -- 차량 1: 페어링 코드
    perform set_config('request.jwt.claims', json_build_object('sub', car1, 'role', 'authenticated')::text, true);
    perform set_config('role', 'authenticated', true);
    select c.pairing_id, c.code into v_pid, v_code from public.create_pairing('car1-public-key') c;
    if v_code !~ '^[0-9]{6}$' then raise exception 'FAIL: code format %', v_code; end if;
    select m.group_id into g1 from public.sync_members m where m.user_id = car1;
    if g1 is null then raise exception 'FAIL: car member row not visible'; end if;

    -- 직접 접근 차단: 페어링 표
    begin
        perform 1 from public.sync_pairings;
        raise exception 'FAIL: sync_pairings readable';
    exception when insufficient_privilege then null;
    end;

    -- 공격자: 틀린 코드 → 빈 결과, 레코드 0개, 그룹에 쓰기 불가
    perform set_config('request.jwt.claims', json_build_object('sub', attacker, 'role', 'authenticated')::text, true);
    select count(*) into n from public.claim_pairing(case when v_code = '000000' then '000001' else '000000' end, 'attacker-key');
    if n <> 0 then raise exception 'FAIL: wrong code accepted'; end if;
    select count(*) into n from public.sync_records;
    if n <> 0 then raise exception 'FAIL: attacker sees records'; end if;
    begin
        insert into public.sync_records (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
            values (g1, 'todo', 'x', now_ms, 'a', 'n', 'c');
        raise exception 'FAIL: attacker inserted into other group';
    exception when insufficient_privilege then null;
    end;
    select count(*) into n from public.pairing_status(v_pid);
    if n <> 0 then raise exception 'FAIL: attacker sees pairing status'; end if;

    -- 휴대폰 1: 맞는 코드 → 차량 공개키, 그러나 확인 전에는 레코드 접근 불가
    perform set_config('request.jwt.claims', json_build_object('sub', phone1, 'role', 'authenticated')::text, true);
    select c.car_public_key into v_key from public.claim_pairing(v_code, 'phone1-public-key') c;
    if v_key is distinct from 'car1-public-key' then raise exception 'FAIL: claim did not return car key'; end if;
    begin
        insert into public.sync_records (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
            values (g1, 'todo', 'early', now_ms, 'p', 'n', 'c');
        raise exception 'FAIL: unconfirmed phone inserted';
    exception when insufficient_privilege then null;
    end;

    -- 같은 코드 재사용 불가 (공격자)
    perform set_config('request.jwt.claims', json_build_object('sub', attacker, 'role', 'authenticated')::text, true);
    select count(*) into n from public.claim_pairing(v_code, 'attacker-key');
    if n <> 0 then raise exception 'FAIL: code reused'; end if;

    -- 차량 1: 공개키 확인 후 승인
    perform set_config('request.jwt.claims', json_build_object('sub', car1, 'role', 'authenticated')::text, true);
    select s.phone_public_key into v_key from public.pairing_status(v_pid) s;
    if v_key is distinct from 'phone1-public-key' then raise exception 'FAIL: status phone key'; end if;
    perform public.confirm_pairing(v_pid);

    -- 휴대폰 1: 이제 쓸 수 있다 (car_item 포함)
    perform set_config('request.jwt.claims', json_build_object('sub', phone1, 'role', 'authenticated')::text, true);
    insert into public.sync_records (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
        values (g1, 'car_item', 'item-1', now_ms, 'phone', 'n1', 'c1'),
               (g1, 'todo', 'todo-1', now_ms, 'phone', 'n2', 'c2'),
               (g1, 'parking', 'spot-1', now_ms, 'phone', 'n3', 'c3');
    begin
        insert into public.sync_records (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
            values (g1, 'unknown', 'z', now_ms, 'phone', 'n', 'c');
        raise exception 'FAIL: unknown kind accepted';
    exception when check_violation then null;
    end;
    begin
        insert into public.sync_records (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
            values (g1, 'todo', 'future', now_ms + 3 * 86400000, 'phone', 'n', 'c');
        raise exception 'FAIL: future timestamp accepted';
    exception when raise_exception then
        if sqlerrm like 'FAIL:%' then raise; end if;
    end;

    -- 옛 변경은 무시, 새 변경은 반영 (upsert)
    insert into public.sync_records as r (group_id, kind, record_id, updated_at, origin, nonce, ciphertext)
        values (g1, 'car_item', 'item-1', now_ms - 1000, 'car', 'old', 'old')
        on conflict (group_id, kind, record_id) do update
        set updated_at = excluded.updated_at, origin = excluded.origin, nonce = excluded.nonce, ciphertext = excluded.ciphertext, deleted = excluded.deleted;
    select count(*) into n from public.sync_records where record_id = 'item-1' and ciphertext = 'c1';
    if n <> 1 then raise exception 'FAIL: stale write overwrote newer'; end if;
    insert into public.sync_records as r (group_id, kind, record_id, updated_at, deleted, origin, nonce, ciphertext)
        values (g1, 'car_item', 'item-1', now_ms + 1000, true, 'car', 'del', 'del')
        on conflict (group_id, kind, record_id) do update
        set updated_at = excluded.updated_at, origin = excluded.origin, nonce = excluded.nonce, ciphertext = excluded.ciphertext, deleted = excluded.deleted;
    select count(*) into n from public.sync_records where record_id = 'item-1' and deleted;
    if n <> 1 then raise exception 'FAIL: newer delete not applied'; end if;

    -- 삭제 불가 (deleted=true 로만)
    begin
        delete from public.sync_records where record_id = 'todo-1';
        raise exception 'FAIL: delete allowed';
    exception when insufficient_privilege then null;
    end;

    -- 차량 1은 휴대폰이 쓴 레코드를 본다
    perform set_config('request.jwt.claims', json_build_object('sub', car1, 'role', 'authenticated')::text, true);
    select count(*) into n from public.sync_records where group_id = g1;
    if n <> 3 then raise exception 'FAIL: car sees % records (expected 3)', n; end if;

    -- 다른 그룹(차량 2)은 볼 수도 바꿀 수도 없다
    perform set_config('request.jwt.claims', json_build_object('sub', car2, 'role', 'authenticated')::text, true);
    perform public.create_pairing('car2-public-key');
    select count(*) into n from public.sync_records;
    if n <> 0 then raise exception 'FAIL: other group sees records'; end if;
    update public.sync_records set ciphertext = 'hacked', updated_at = now_ms + 5000 where group_id = g1;
    get diagnostics n = row_count;
    if n <> 0 then raise exception 'FAIL: other group updated records'; end if;
    select count(*) into n from public.sync_members where group_id = g1;
    if n <> 0 then raise exception 'FAIL: other group sees members'; end if;

    -- 로그인하지 않은 요청(anon)
    perform set_config('request.jwt.claims', json_build_object('role', 'anon')::text, true);
    perform set_config('role', 'anon', true);
    begin
        perform 1 from public.sync_records;
        raise exception 'FAIL: anon can read records';
    exception when insufficient_privilege then null;
    end;
    begin
        perform public.create_pairing('anon-key');
        raise exception 'FAIL: anon can call create_pairing';
    exception when insufficient_privilege then null;
    end;

    -- 차량 1 해제 → 그룹·레코드 삭제
    perform set_config('role', 'authenticated', true);
    perform set_config('request.jwt.claims', json_build_object('sub', car1, 'role', 'authenticated')::text, true);
    perform public.leave_sync();
    perform set_config('role', 'postgres', true);
    select count(*) into n from public.sync_records where group_id = g1;
    if n <> 0 then raise exception 'FAIL: records remain after car left'; end if;

    raise notice 'DRIVE SYNC CHECK: ALL PASSED';
end;
$$;

rollback;
