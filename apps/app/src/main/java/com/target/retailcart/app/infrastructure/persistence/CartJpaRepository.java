package com.target.retailcart.app.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CartJpaRepository extends JpaRepository<CartEntity, UUID> {

  /** Loads a cart and its lines in one query (no N+1). */
  @Query("select c from CartEntity c left join fetch c.items where c.id = :id")
  Optional<CartEntity> findWithItems(@Param("id") UUID id);
}
