package com.target.retailcart.app.application.view;

public enum AvailabilityStatus {
  AVAILABLE,
  /** No units, or the Availability service has no data for the item (D10). */
  UNAVAILABLE,
  /** The Availability service failed or timed out (D11). */
  UNKNOWN
}
