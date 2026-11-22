package com.target.retailcart.app.api;

/** Input rules shared by request bodies and path variables. */
public final class ApiConstraints {

  /** Item ids are numeric strings issued by the Item service, e.g. "54276335". */
  public static final String ITEM_ID_PATTERN = "^[0-9]{1,32}$";

  public static final String ITEM_ID_MESSAGE = "must be a numeric item id of 1-32 digits";

  /** Sanity ceiling only; the real limit comes from the Availability service (D19). */
  public static final int MAX_QUANTITY = 9_999;

  private ApiConstraints() {}
}
