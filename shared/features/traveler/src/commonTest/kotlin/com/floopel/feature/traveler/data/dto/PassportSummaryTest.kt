package com.floopel.feature.traveler.data.dto
import com.floopel.feature.traveler.domain.model.passportSummaryJson
import kotlin.test.*
import kotlinx.serialization.json.*
class PassportSummaryTest {
 @Test fun qrSummaryHasOnlyApprovedFieldsAndNumericValues() {
  val value = Json.parseToJsonElement(passportSummaryJson("traveler_1",2,1250)).jsonObject
  assertEquals(setOf("username","level","total_xp"),value.keys)
  assertEquals("traveler_1",value.getValue("username").jsonPrimitive.content)
  assertEquals(2,value.getValue("level").jsonPrimitive.int)
  assertEquals(1250L,value.getValue("total_xp").jsonPrimitive.long)
  assertFalse(value.getValue("total_xp").jsonPrimitive.isString)
  assertFailsWith<IllegalArgumentException> { passportSummaryJson("",1,0) }
  assertFailsWith<IllegalArgumentException> { passportSummaryJson("traveler",1,-1) }
 }
}
