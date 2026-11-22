// Client library for the Target product services (Item, Pricing, Availability).
// Reusable by any service (cart, checkout, orders); has no knowledge of the cart domain.
plugins {
    `java-library`
}

dependencies {
    implementation(platform(libs.spring.boot.bom))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}
