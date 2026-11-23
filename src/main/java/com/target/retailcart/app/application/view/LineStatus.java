package com.target.retailcart.app.application.view;

/** The most important thing a client should know about a line, in priority order. */
public enum LineStatus {
  /** The item can't be ordered now; kept in the cart, excluded from the subtotal (D10). */
  UNAVAILABLE,
  /** Availability couldn't be checked; excluded from the subtotal, total incomplete (D11). */
  AVAILABILITY_UNKNOWN,
  /** No current price; never replaced by an old one (D11). */
  PRICE_UNAVAILABLE,
  /** The limit dropped below the quantity in the cart; not reduced automatically (D22). */
  QUANTITY_EXCEEDS_LIMIT,
  /** Name/image couldn't be loaded; cosmetic only, the line is still priced (D11). */
  DETAILS_UNAVAILABLE,
  OK
}
