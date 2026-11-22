-- Carts store only item ids and quantities: never prices or item details (D7).

create table cart (
    id         uuid        primary key,
    version    bigint      not null,          -- optimistic locking (D26)
    created_at timestamptz not null,
    updated_at timestamptz not null           -- last write; drives expiry (D21)
);

-- Expiry sweeps scan by last write.
create index idx_cart_updated_at on cart (updated_at);

create table cart_item (
    id        bigserial   primary key,
    cart_id   uuid        not null references cart (id) on delete cascade,
    item_id   varchar(32) not null,
    quantity  integer     not null check (quantity > 0),
    added_at  timestamptz not null,
    -- One line per item (D9): a duplicate add merges into the existing line.
    constraint uq_cart_item_cart_item unique (cart_id, item_id)
);
