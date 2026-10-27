package app.householdplanner.auth

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID
import scala.util.Try
import zio.test.*

object AuthSecuritySpec extends ZIOSpecDefault:
  private lazy val generator =
    val value = KeyPairGenerator.getInstance("RSA")
    value.initialize(2048)
    value
  private lazy val keys = generator.generateKeyPair()
  private val now = Instant.parse("2026-01-01T12:00:00Z")
  private def jwt(at: Instant = now, issuer: String = "test", pair: java.security.KeyPair = keys) =
    new JwtTokens(pair.getPrivate.asInstanceOf[RSAPrivateKey], pair.getPublic.asInstanceOf[RSAPublicKey], issuer, "api", "key-1", Clock.fixed(at, ZoneOffset.UTC))
  private def profile(status: String = "ACTIVE", admin: Boolean = false, forced: Boolean = false) =
    Profile(UUID.randomUUID().toString, "user", "user@example.test", "User", status, admin, forced, now.toString)
  def spec = suite("authentication security")(
    test("JWT signature, issuer, audience and expiration are enforced") {
      val uid = UUID.randomUUID(); val sid = UUID.randomUUID(); val token = jwt().issue(uid.toString, sid)
      val otherKeys = generator.generateKeyPair()
      val otherAudience = new JwtTokens(keys.getPrivate.asInstanceOf[RSAPrivateKey], keys.getPublic.asInstanceOf[RSAPublicKey], "test", "other-api", "key-1", Clock.fixed(now, ZoneOffset.UTC))
      assertTrue(
        jwt().verify(token) == (uid, sid),
        Try(jwt(now.plusSeconds(601)).verify(token)).isFailure,
        Try(jwt().verify(jwt(now.plusSeconds(60)).issue(uid.toString, sid))).isFailure,
        Try(jwt(issuer = "other-issuer").verify(token)).isFailure,
        Try(otherAudience.verify(token)).isFailure,
        Try(jwt(pair = otherKeys).verify(token)).isFailure,
        Try(jwt().verify("garbage")).isFailure
      )
    },
    test("unsigned and substituted JWT headers are rejected") {
      val token = jwt().issue(UUID.randomUUID().toString, UUID.randomUUID())
      val parts = token.split("\\.")
      val unsignedHeader = java.util.Base64.getUrlEncoder.withoutPadding().encodeToString("""{"alg":"none","typ":"at+jwt","kid":"key-1"}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val badType = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).`type`(new JOSEObjectType("JWT")).keyID("key-1").build(), SignedJWT.parse(token).getJWTClaimsSet)
      badType.sign(new RSASSASigner(keys.getPrivate.asInstanceOf[RSAPrivateKey]))
      assertTrue(Try(jwt().verify(s"$unsignedHeader.${parts(1)}.")).isFailure,
        Try(jwt().verify(badType.serialize())).isFailure)
    },
    test("pending users may edit their profile but cannot use the workspace or admin") {
      val pending = profile("PENDING")
      assertTrue(Try(AccessPolicy.passwordReady(pending)).isSuccess,
        Try(AccessPolicy.workspace(pending)).isFailure, Try(AccessPolicy.admin(pending)).isFailure,
        Try(AccessPolicy.admin(profile())).isFailure, Try(AccessPolicy.admin(profile(admin = true))).isSuccess)
    },
    test("forced-password and disabled accounts cannot bypass restrictions with an admin role") {
      assertTrue(
        Try(AccessPolicy.admin(profile(admin = true, forced = true))).isFailure,
        Try(AccessPolicy.passwordReady(profile(forced = true))).isFailure,
        Try(AccessPolicy.profile(profile("SUSPENDED", admin = true))).isFailure,
        Try(AccessPolicy.profile(profile("REJECTED"))).isFailure
      )
    },
    test("Argon2 uses unique salts and rejects wrong passwords") {
      val a = Passwords.hash("a-test-password-123")
      val b = Passwords.hash("a-test-password-123")
      assertTrue(a != b, a.startsWith("$argon2id$"), Passwords.matches("a-test-password-123", a),
        !Passwords.matches("wrong-password", a), !Passwords.matches("x", "invalid-encoding"))
    },
    test("rate limiting expires its window and bounds new identities") {
      class MutableClock extends Clock:
        var value = now
        override def getZone(): ZoneId = ZoneOffset.UTC
        override def withZone(zone: ZoneId): Clock = this
        override def instant(): Instant = value
      val clock = new MutableClock()
      val limiter = new RateLimiter(clock, 2)
      limiter.check("one", 1)
      val blocked = Try(limiter.check("one", 1)).isFailure
      limiter.check("two", 1)
      val full = Try(limiter.check("three", 1)).isFailure
      clock.value = now.plusSeconds(61)
      assertTrue(blocked, full, Try(limiter.check("one", 1)).isSuccess)
    }
  )
