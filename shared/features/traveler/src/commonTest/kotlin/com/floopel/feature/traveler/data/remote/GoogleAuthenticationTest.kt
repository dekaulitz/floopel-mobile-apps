package com.floopel.feature.traveler.data.remote

import com.floopel.feature.traveler.core.network.*

import com.floopel.feature.traveler.domain.model.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.domain.repository.*
import com.floopel.feature.traveler.feature.auth.*
import com.floopel.feature.traveler.feature.legal.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*

class GoogleAuthenticationTest {
 @Test fun existingAccountUsesOnlyNativeTokens() = runTest {
  val f=GoogleFixture();f.onboarding=false
  f.session.loginGoogle("provider-token")
  assertTrue(f.session.view.value.signedIn);assertEquals("refresh",f.stored)
  assertEquals(1,f.logins);assertFalse(f.session.view.value.googleOnboardingRequired)
  f.client.close()
 }
 @Test fun profileProofDoesNotUnlockOrPersistAndCompletionOmitsEmailPassword() = runTest {
  val f=GoogleFixture();f.session.loginGoogle("provider-token")
  assertFalse(f.session.view.value.signedIn);assertTrue(f.session.view.value.googleOnboardingRequired);assertNull(f.stored)
  f.session.completeGoogle(f.draft)
  assertTrue(f.session.view.value.signedIn);assertEquals(1,f.completions)
  assertFalse(f.completionBody!!.containsKey("email"));assertFalse(f.completionBody!!.containsKey("password"))
  assertEquals("terms-hash",f.completionBody!!.getValue("consent").jsonObject.getValue("terms_hash").jsonPrimitive.content)
  f.client.close()
 }
 @Test fun validationAllowsCorrectionButUncertainCompletionRequiresFreshSignIn() = runTest {
  val f=GoogleFixture();f.session.loginGoogle("provider-token")
  f.completionStatus=HttpStatusCode.UnprocessableEntity
  f.session.completeGoogle(f.draft);assertTrue(f.session.view.value.googleOnboardingRequired);assertFalse(f.session.view.value.signedIn)
  f.completionStatus=HttpStatusCode.InternalServerError
  f.session.completeGoogle(f.draft);assertFalse(f.session.view.value.googleOnboardingRequired);assertNull(f.stored)
  f.session.completeGoogle(f.draft);assertEquals(2,f.completions)
  f.client.close()
 }
 @Test fun cancelledExpiredOrLateProofCannotCreateSession() = runTest {
  val f=GoogleFixture();f.session.loginGoogle("provider-token");f.session.cancelGoogleOnboarding()
  f.session.completeGoogle(f.draft);assertEquals(0,f.completions)
  f.expiry="2000-01-01T00:00:00Z";f.session.loginGoogle("provider-token");f.session.completeGoogle(f.draft)
  assertFalse(f.session.view.value.googleOnboardingRequired);assertEquals(401,f.session.view.value.failure!!.status)
  f.expiry="2099-01-01T00:00:00Z";f.wait=CompletableDeferred()
  val login=launch {f.session.loginGoogle("provider-token")};runCurrent();f.session.logout();f.wait!!.complete(Unit);login.join()
  assertFalse(f.session.view.value.googleOnboardingRequired);assertFalse(f.session.view.value.signedIn)
  f.client.close()
 }
 @Test fun invalidTokenAndMalformedOnboardingFailClosed() = runTest {
  val f=GoogleFixture();f.loginStatus=HttpStatusCode.Unauthorized;f.session.loginGoogle("bad")
  assertFalse(f.session.view.value.signedIn);assertNull(f.stored)
  f.loginStatus=HttpStatusCode.OK;f.expiry="invalid";f.session.loginGoogle("bad")
  assertFalse(f.session.view.value.googleOnboardingRequired);assertFalse(f.session.view.value.signedIn)
  f.client.close()
 }
}
private class GoogleFixture {
 var stored:String?=null;var onboarding=true;var logins=0;var completions=0
 var expiry="2099-01-01T00:00:00Z";var completionStatus=HttpStatusCode.OK;var loginStatus=HttpStatusCode.OK
 var completionBody:JsonObject?=null;var wait:CompletableDeferred<Unit>?=null
 private val tokens="""{"token_type":"Bearer","access_token":"access","access_expires_at":"2099-01-01T00:00:00Z","refresh_token":"refresh","refresh_expires_at":"2099-02-01T00:00:00Z"}"""
 val draft=RegistrationDraft("ignored","ignored","First","Last","traveler","1990-01-01","ID",RegistrationReferences(LegalDocument("terms","1","en","Terms","terms-hash"),LegalDocument("privacy","1","en","Privacy","privacy-hash"),listOf(NationalityOption("ID","Indonesia"))))
 val client=HttpClient(MockEngine {request->
  assertNull(request.headers[HttpHeaders.Cookie]);assertNull(request.headers["Idempotency-Key"])
  var status=HttpStatusCode.OK
  val data=when(request.url.encodedPath){
   "/api/mobile/v1/auth/google"->{logins++;wait?.await();status=loginStatus;assertEquals("ANDROID",request.headers["X-App-Platform"]);assertNull(request.headers[HttpHeaders.Authorization]);if(onboarding) """{"status":"needs_profile","onboarding_token":"proof","expires_at":"$expiry"}""" else """{"tokens":$tokens}"""}
   "/api/mobile/v1/auth/google/complete"->{completions++;status=completionStatus;assertEquals("Bearer proof",request.headers[HttpHeaders.Authorization]);assertEquals("ANDROID",request.headers["X-App-Platform"]);completionBody=Json.parseToJsonElement((request.body as TextContent).text).jsonObject;"""{"tokens":$tokens}"""}
   else->"""{"xp_total":0,"level":{"level":1,"code":"explorer","name":"Explorer","xp_floor":0,"xp_to_next_level":1000,"policy_revision":1},"badges":[]}"""
  }
  respond("""{"meta":{"code":"OK"},"error":null,"data":$data}""",status,headersOf(HttpHeaders.ContentType,"application/json"))
 })
 val session=TravelerSession(NativeApi(client,"https://fixture.invalid",AppPlatform.ANDROID),object:RefreshCredentialStore{
  override fun read()=stored
  override fun write(credential:String){stored=credential}
  override fun clear(){stored=null}
 },"en")
}
