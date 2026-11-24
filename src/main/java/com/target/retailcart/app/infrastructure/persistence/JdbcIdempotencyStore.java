package com.target.retailcart.app.infrastructure.persistence;

import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.idempotency.IdempotencySettings;
import com.target.retailcart.app.application.idempotency.IdempotencyStore;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for {@link IdempotencyStore}. Plain JDBC, because the core of it is one
 * statement JPA can't express: {@code insert ... on conflict do nothing}, which lets exactly one of
 * several concurrent requests with the same key claim it, enforced by the primary key.
 */
@Repository
class JdbcIdempotencyStore implements IdempotencyStore {

  private final JdbcTemplate jdbc;

  JdbcIdempotencyStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public Claim claim(String key, String fingerprint, Instant now, IdempotencySettings settings) {
    // A key past its TTL is forgotten, even if the clean-up job hasn't removed it yet.
    jdbc.update(
        "delete from idempotency_key where id = ? and created_at <= ?",
        key,
        Timestamp.from(now.minus(settings.ttl())));

    int inserted =
        jdbc.update(
            """
            insert into idempotency_key (id, fingerprint, status, created_at, locked_at)
            values (?, ?, 'IN_PROGRESS', ?, ?)
            on conflict (id) do nothing
            """,
            key,
            fingerprint,
            Timestamp.from(now),
            Timestamp.from(now));
    if (inserted == 1) {
      return new Claim.Claimed();
    }

    var existing =
        jdbc.query(
            "select * from idempotency_key where id = ?", JdbcIdempotencyStore::toClaim, key);
    if (existing.isEmpty()) {
      return new Claim.InProgress(fingerprint); // released between our insert and select
    }
    Claim claim = existing.getFirst();
    if (claim instanceof Claim.InProgress inProgress
        && inProgress.fingerprint().equals(fingerprint)
        && takeOverAbandoned(key, now, settings)) {
      return new Claim.Claimed();
    }
    return claim;
  }

  /** Compare-and-set on the lease, so only one waiting request can take over a dead claim. */
  private boolean takeOverAbandoned(String key, Instant now, IdempotencySettings settings) {
    return jdbc.update(
            """
            update idempotency_key set locked_at = ?
            where id = ? and status = 'IN_PROGRESS' and locked_at <= ?
            """,
            Timestamp.from(now),
            key,
            Timestamp.from(now.minus(settings.lease())))
        == 1;
  }

  @Override
  @Transactional
  public void complete(String key, CartItemResult result) {
    jdbc.update(
        """
        update idempotency_key
        set status = 'COMPLETED', cart_id = ?, item_id = ?, quantity = ?, item_count = ?,
            cart_created = ?
        where id = ?
        """,
        result.cartId().value(),
        result.itemId().value(),
        result.quantity(),
        result.itemCount(),
        result.cartCreated(),
        key);
  }

  @Override
  @Transactional
  public void release(String key) {
    jdbc.update("delete from idempotency_key where id = ? and status = 'IN_PROGRESS'", key);
  }

  private static Claim toClaim(ResultSet row, int rowNum) throws SQLException {
    String fingerprint = row.getString("fingerprint");
    if (!"COMPLETED".equals(row.getString("status"))) {
      return new Claim.InProgress(fingerprint);
    }
    return new Claim.Completed(
        fingerprint,
        new CartItemResult(
            new CartId(row.getObject("cart_id", UUID.class)),
            ItemId.of(row.getString("item_id")),
            row.getInt("quantity"),
            row.getInt("item_count"),
            row.getBoolean("cart_created")));
  }
}
