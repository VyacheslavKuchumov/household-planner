package app.householdplanner.auth

import java.net.URI
import java.nio.file.{Files, Path}

final case class AppConfig(
  dbUrl: String, dbUser: String, dbPassword: String, poolSize: Int,
  issuer: String, audience: String, keyId: String, privateKey: Path, publicKey: Path,
  publicUrl: String, allowedOrigins: Set[String], cookieSecure: Boolean, cookieSameSite: String,
  smtpHost: String, smtpPort: Int, smtpUser: String, smtpPassword: String,
  smtpStartTls: Boolean, mailFrom: String, bootstrapEmail: String
)

object AppConfig:
  def load(): AppConfig =
    def env(key: String, default: String): String = sys.env.getOrElse(key, default)
    def required(key: String): String = sys.env.get(key).filter(_.nonEmpty)
      .getOrElse(throw IllegalArgumentException(s"Missing required setting: $key"))
    def readSecret(path: String): String = Files.readString(Path.of(path)).stripSuffix("\n").stripSuffix("\r")
    def secret(key: String): String = sys.env.get(s"${key}_FILE") match
      case Some(path) => readSecret(path)
      case None => required(key)
    val publicUrl = env("APP_PUBLIC_URL", "http://localhost:5173").stripSuffix("/")
    val origin = URI.create(publicUrl)
    require(Set("http", "https").contains(origin.getScheme) && origin.getHost != null && origin.getPath.isEmpty && origin.getRawQuery == null && origin.getFragment == null && origin.getUserInfo == null,
      "APP_PUBLIC_URL must be an HTTP(S) origin without path")
    val secure = env("COOKIE_SECURE", "true").toBoolean
    val sameSite = env("COOKIE_SAME_SITE", "Lax")
    require(Set("Lax", "Strict", "None").contains(sameSite), "Invalid COOKIE_SAME_SITE")
    require(sameSite != "None" || secure, "SameSite=None requires secure cookies")
    val pool = env("DB_POOL_SIZE", "10").toInt
    require(pool >= 1 && pool <= 100, "DB_POOL_SIZE must be between 1 and 100")
    val allowed = env("ALLOWED_ORIGINS", publicUrl).split(",").map(_.trim).filter(_.nonEmpty).toSet
    require(allowed.nonEmpty, "ALLOWED_ORIGINS must not be empty")
    allowed.foreach { value =>
      val uri = URI.create(value)
      require(Set("http", "https").contains(uri.getScheme) && uri.getHost != null && uri.getPath.isEmpty &&
        uri.getRawQuery == null && uri.getFragment == null && uri.getUserInfo == null,
        "ALLOWED_ORIGINS must contain exact HTTP(S) origins")
    }
    AppConfig(
      env("DB_URL", "jdbc:postgresql://localhost:5433/household_planner_dev"),
      env("DB_USER", "household_dev"), secret("DB_PASSWORD"), pool,
      env("JWT_ISSUER", "household-planner"), env("JWT_AUDIENCE", "household-planner-api"),
      env("JWT_KEY_ID", "primary"), Path.of(required("JWT_PRIVATE_KEY_FILE")), Path.of(required("JWT_PUBLIC_KEY_FILE")),
      publicUrl, allowed,
      secure, sameSite, required("SMTP_HOST"), env("SMTP_PORT", "587").toInt,
      env("SMTP_USER", ""), sys.env.get("SMTP_PASSWORD_FILE").map(p => readSecret(p))
        .getOrElse(env("SMTP_PASSWORD", "")),
      env("SMTP_STARTTLS", "true").toBoolean, env("MAIL_FROM", "Household Planner <noreply@example.com>"),
      env("BOOTSTRAP_ADMIN_EMAIL", "admin@localhost.invalid")
    )
