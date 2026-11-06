package com.floopel.feature.traveler.domain.model
import kotlin.test.*
class TravelerAgeTest {
 @Test fun ageGateUsesWholeYearsAndRejectsInvalidDates() {
  assertTrue(isAdultTraveler("2008-10-19","2026-10-19"))
  assertFalse(isAdultTraveler("2008-10-20","2026-10-19"))
  assertFalse(isAdultTraveler("2026-10-19","2026-10-19"))
  assertFalse(isAdultTraveler("2007-02-29","2026-10-19"))
  assertFalse(isAdultTraveler("2000-01-01","invalid"))
  assertFalse(isAdultTraveler("2008-02-29","2026-02-28"))
  assertTrue(isAdultTraveler("2008-02-29","2026-03-01"))
 }
}
