package com.target.retailcart.app.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.product.ItemAvailability;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AvailabilityPolicyTest {

  private final AvailabilityPolicy policy = new AvailabilityPolicy();

  @ParameterizedTest(name = "units={0}, threshold={1} -> available={2}, max={3}")
  @CsvSource({
    "312, 20, true, 20", // real sample: threshold caps the cart
    "5, 20, true, 5", // low stock: never allow more than is in stock
    "312, 0, true, 312", // no threshold: stock is the only cap
    "0, 20, false, 0", // sold out
    "-3, 20, false, 0", // defensive: bad data treated as sold out
  })
  void appliesMinOfStockAndThreshold(int units, int threshold, boolean available, int max) {
    var availability = new ItemAvailability(ItemId.of("1"), units, threshold);

    assertThat(policy.isAvailable(availability)).isEqualTo(available);
    assertThat(policy.maxAllowed(availability)).isEqualTo(max);
  }
}
