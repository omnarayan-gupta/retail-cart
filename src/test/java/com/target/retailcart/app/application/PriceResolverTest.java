package com.target.retailcart.app.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.product.ItemPrice;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class PriceResolverTest {

  private static final ItemId ITEM = ItemId.of("54276335");
  private final PriceResolver resolver = new PriceResolver();

  @Test
  void saleTypeUsesSalePrice() {
    var price = new ItemPrice(ITEM, new BigDecimal("17.61"), new BigDecimal("12.39"), "SALE");

    assertThat(resolver.unitPrice(price)).contains(new BigDecimal("12.39"));
  }

  @Test
  void nonSaleTypeUsesRegularPriceEvenIfSaleIsPresent() {
    var price = new ItemPrice(ITEM, new BigDecimal("17.61"), new BigDecimal("12.39"), "REGULAR");

    assertThat(resolver.unitPrice(price)).contains(new BigDecimal("17.61"));
  }

  @Test
  void unknownTypeFallsBackToRegular() {
    var price = new ItemPrice(ITEM, new BigDecimal("17.61"), new BigDecimal("9.99"), "CLEARANCE");

    assertThat(resolver.unitPrice(price)).contains(new BigDecimal("17.61"));
  }

  @Test
  void saleTypeWithoutSalePriceFallsBackToRegular() {
    var price = new ItemPrice(ITEM, new BigDecimal("17.61"), null, "SALE");

    assertThat(resolver.unitPrice(price)).contains(new BigDecimal("17.61"));
  }

  @Test
  void noUsablePriceIsEmpty() {
    assertThat(resolver.unitPrice(new ItemPrice(ITEM, null, null, "SALE"))).isEmpty();
    assertThat(resolver.unitPrice(new ItemPrice(ITEM, new BigDecimal("-1"), null, null))).isEmpty();
  }
}
