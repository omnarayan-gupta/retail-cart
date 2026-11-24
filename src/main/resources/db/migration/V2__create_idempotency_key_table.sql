-- Idempotency keys for cart adds (D6). A row is claimed (IN_PROGRESS) before the add runs and
-- holds the result once it succeeds (COMPLETED). Failed requests delete their row.
create table idempotency_key (
    id           varchar(255) primary key,       -- the client's Idempotency-Key header
    fingerprint  char(64)     not null,          -- SHA-256 of method, path and body values
    status       varchar(16)  not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    created_at   timestamptz  not null,          -- drives the 24h TTL
    locked_at    timestamptz  not null,          -- start of the current claim (lease)
    -- the stored result, set once COMPLETED
    cart_id      uuid,
    item_id      varchar(32),
    quantity     integer,
    item_count   integer,
    cart_created boolean
);

-- Clean-up sweeps scan by age.
create index idx_idempotency_key_created_at on idempotency_key (created_at);
