package com.target.retailcart.app.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/** JPA mapping of the {@code cart_item} table: item id and quantity only (D7). */
@Entity
@Table(name = "cart_item")
class CartItemEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "cart_id", nullable = false)
  private CartEntity cart;

  @Column(name = "item_id", nullable = false, length = 32)
  private String itemId;

  @Column(nullable = false)
  private int quantity;

  @Column(name = "added_at", nullable = false)
  private Instant addedAt;

  protected CartItemEntity() {}

  CartItemEntity(CartEntity cart, String itemId, int quantity, Instant addedAt) {
    this.cart = cart;
    this.itemId = itemId;
    this.quantity = quantity;
    this.addedAt = addedAt;
  }

  String getItemId() {
    return itemId;
  }

  int getQuantity() {
    return quantity;
  }

  void setQuantity(int quantity) {
    this.quantity = quantity;
  }

  Instant getAddedAt() {
    return addedAt;
  }
}
