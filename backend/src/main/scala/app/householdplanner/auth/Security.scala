package app.householdplanner.auth

import com.nimbusds.jose.{JOSEObjectType, JWSAlgorithm, JWSHeader}
import com.nimbusds.jose.crypto.{RSASSASigner, RSASSAVerifier}
import com.nimbusds.jwt.{JWTClaimsSet, SignedJWT}
import com.password4j.{Argon2Function, Password}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.{KeyFactory, MessageDigest, SecureRandom}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.security.spec.{PKCS8EncodedKeySpec, X509EncodedKeySpec}
import java.time.{Clock, Instant}
import java.util.{Base64, Date, UUID}
import scala.collection.mutable
import scala.util.Try

object Passwords:
  def hash(value: String): String = Password.hash(value).addRandomSalt(16).withArgon2().getResult
  def matches(value: String, encoded: String): Boolean =
    Try(Password.check(value, encoded).with(Argon2Function.getInstanceFromHash(encoded))).getOrElse(false)

object OpaqueTokens:
  private val random = new SecureRandom()
  def generate(): String =
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  def hash(value: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
  def valid(value: String): Boolean = value.matches("[A-Za-z0-9_-]{43}")
  def same(a: String, b: String): Boolean = MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

final class JwtTokens(
  privateKey: RSAPrivateKey, publicKey: RSAPublicKey,
  issuer: String, audience: String, keyId: String, clock: Clock = Clock.systemUTC()
):
  val lifetimeSeconds = 600L
  require(publicKey.getModulus.bitLength >= 2048, "RSA key must be at least 2048 bits")
  require(privateKey.getModulus == publicKey.getModulus, "JWT signing and verification keys do not match")
  def issue(userId: String, sessionId: UUID): String =
    val now = clock.instant()
    val claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(userId)
      .claim("sid", sessionId.toString).jwtID(UUID.randomUUID().toString)
      .issueTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(lifetimeSeconds))).build()
    val jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
      .`type`(new JOSEObjectType("at+jwt")).keyID(keyId).build(), claims)
    jwt.sign(new RSASSASigner(privateKey))
    jwt.serialize()

  def verify(token: String): (UUID, UUID) =
    try
      if token.length > 8192 then throw ApiError.unauthorized
      val jwt = SignedJWT.parse(token)
      val header = jwt.getHeader
      if header.getAlgorithm != JWSAlgorithm.RS256 || header.getType != new JOSEObjectType("at+jwt") ||
          header.getKeyID != keyId || !jwt.verify(new RSASSAVerifier(publicKey)) then throw ApiError.unauthorized
      val claims = jwt.getJWTClaimsSet
      val now = clock.instant()
      if claims.getIssuer != issuer || claims.getAudience == null || !claims.getAudience.contains(audience) ||
          claims.getExpirationTime == null || !claims.getExpirationTime.toInstant.isAfter(now) ||
          claims.getIssueTime == null || claims.getIssueTime.toInstant.isAfter(now.plusSeconds(30)) ||
          !claims.getExpirationTime.toInstant.isAfter(claims.getIssueTime.toInstant) ||
          claims.getExpirationTime.toInstant.isAfter(claims.getIssueTime.toInstant.plusSeconds(lifetimeSeconds)) ||
          claims.getJWTID == null ||
          (claims.getNotBeforeTime != null && claims.getNotBeforeTime.toInstant.isAfter(now)) then throw ApiError.unauthorized
      (UUID.fromString(claims.getSubject), UUID.fromString(claims.getStringClaim("sid")))
    catch case _: Exception => throw ApiError.unauthorized

object JwtTokens:
  private def pem(path: Path): Array[Byte] =
    val value = Files.readString(path).replaceAll("-----[^-]+-----", "").replaceAll("\\s", "")
    Base64.getDecoder.decode(value)
  def load(config: AppConfig): JwtTokens =
    val factory = KeyFactory.getInstance("RSA")
    new JwtTokens(
      factory.generatePrivate(new PKCS8EncodedKeySpec(pem(config.privateKey))).asInstanceOf[RSAPrivateKey],
      factory.generatePublic(new X509EncodedKeySpec(pem(config.publicKey))).asInstanceOf[RSAPublicKey],
      config.issuer, config.audience, config.keyId
    )

final class RateLimiter(clock: Clock = Clock.systemUTC(), capacity: Int = 10000):
  private val windows = mutable.Map.empty[String, (Long, Int)]
  def check(key: String, limit: Int, windowSeconds: Long = 60): Unit = synchronized {
    val now = clock.instant().getEpochSecond
    windows.filterInPlace((key, entry) => entry._1 > now)
    if !windows.contains(key) && windows.size >= capacity then
      throw ApiError(429, "RATE_LIMITED", "Слишком много запросов, попробуйте позже")
    val (until, count) = windows.getOrElse(key, (now + windowSeconds, 0))
    if count >= limit then throw ApiError(429, "RATE_LIMITED", "Слишком много запросов, попробуйте позже")
    windows.update(key, (until, count + 1))
  }
