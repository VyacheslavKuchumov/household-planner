package app.householdplanner.auth

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.security.KeyPairGenerator
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.sql.DriverManager
import java.util.UUID
import org.flywaydb.core.Flyway
import scala.collection.mutable.ListBuffer
import scala.util.Using
import zio.*
import zio.http.*
import zio.test.*

object AuthIntegrationSpec extends ZIOSpecDefault:
  private val password = "test-password-12345"
  private val adminPassword = "new-admin-password-12345"
  private final class TestMailer extends Mailer:
    val messages = ListBuffer.empty[(String, String)]
    def sendPasswordReset(email: String, url: String): Unit = messages.synchronized { messages += ((email, url)); () }
  private final case class Context(db: Database, repo: AuthRepository, service: AuthService, admin: Principal, mailer: TestMailer, initialAdmin: SessionTokens, schema: String)
  private def setup(): Context =
    val url = sys.env("TEST_DB_URL")
    val user = sys.env.getOrElse("TEST_DB_USER", "postgres")
    val pass = sys.env.getOrElse("TEST_DB_PASSWORD", "postgres")
    val schema = "auth_test_" + UUID.randomUUID().toString.replace("-", "")
    Flyway.configure().dataSource(url, user, pass).schemas(schema).defaultSchema(schema)
      .locations("classpath:db/migration").load().migrate()
    val config = new HikariConfig()
    config.setJdbcUrl(url); config.setUsername(user); config.setPassword(pass); config.setSchema(schema)
    val db = new Database(new HikariDataSource(config))
    try
      val repo = new AuthRepository(db)
      repo.bootstrap("admin@example.test")
      val initial = repo.openSession(UUID.fromString(repo.findLogin("admin").get.profile.id), repo.findLogin("admin").get.passwordHash.get)
      repo.changePassword(Principal(initial.user, initial.sessionId), repo.findLogin("admin").get.passwordHash.get, Passwords.hash(adminPassword))
      val pair = KeyPairGenerator.getInstance("RSA"); pair.initialize(2048)
      val keys = pair.generateKeyPair()
      val jwt = new JwtTokens(keys.getPrivate.asInstanceOf[RSAPrivateKey], keys.getPublic.asInstanceOf[RSAPublicKey], "test", "api", "test-key")
      val mailer = new TestMailer()
      val service = new AuthService(repo, jwt, mailer, "http://localhost:5173")
      val adminSession = repo.openSession(UUID.fromString(initial.user.id), repo.findLogin("admin").get.passwordHash.get)
      Context(db, repo, service, Principal(adminSession.user, adminSession.sessionId), mailer, initial, schema)
    catch
      case e: Throwable => db.close(); throw e
  private val contextLayer = ZLayer.scoped {
    ZIO.acquireRelease(ZIO.attemptBlocking(setup())) { ctx => ZIO.attemptBlocking {
      ctx.db.close()
      Using.resource(DriverManager.getConnection(sys.env("TEST_DB_URL"), sys.env.getOrElse("TEST_DB_USER", "postgres"), sys.env.getOrElse("TEST_DB_PASSWORD", "postgres"))) { c =>
        Using.resource(c.createStatement())(_.execute(s"DROP SCHEMA ${ctx.schema} CASCADE"))
      }
    }.orDie }
  }
  private def register(ctx: Context): Task[SessionTokens] =
    val suffix = UUID.randomUUID().toString.take(8)
    for
      _ <- ctx.service.register(RegisterInput(s"user_$suffix", s"$suffix@example.test", "Новый участник", password))
      session <- ctx.service.login(LoginInput(s"user_$suffix", password))
    yield session
  private def principal(ctx: Context, session: SessionTokens): Task[Principal] = ctx.service.principal(ctx.service.jwt.issue(session.user.id, session.sessionId))

  def spec =
    val tests = suite("PostgreSQL authentication integration")(
      test("bootstrap requires a password change and does not restore the default password on restart") {
        for
          ctx <- ZIO.service[Context]
          _ <- ZIO.attemptBlocking(ctx.repo.bootstrap("admin@example.test"))
          old <- ctx.service.login(LoginInput("admin", "admin")).either
          invalidated <- principal(ctx, ctx.initialAdmin).either
        yield assertTrue(ctx.initialAdmin.user.passwordChangeRequired, old.isLeft, invalidated.isLeft, !ctx.admin.profile.passwordChangeRequired)
      },
      test("pending access, profile updates and member attempts to invoke admin routes") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          p <- principal(ctx, session)
          profile <- ctx.service.updateProfile(p, ProfileInput("Мой никнейм"))
          workspace <- ctx.service.workspace(p).either
          denied <- ctx.service.setStatus(p, UUID.fromString(session.user.id), "approve").either
        yield assertTrue(profile.nickname == "Мой никнейм", workspace.isLeft, denied.isLeft)
      },
      test("approval is applied to existing sessions and household queries are membership scoped") {
        for
          ctx <- ZIO.service[Context]
          first <- register(ctx)
          second <- register(ctx)
          _ <- ctx.service.setStatus(ctx.admin, UUID.fromString(first.user.id), "approve")
          _ <- ctx.service.setStatus(ctx.admin, UUID.fromString(second.user.id), "approve")
          one <- principal(ctx, first)
          two <- principal(ctx, second)
          household <- ZIO.attemptBlocking(ctx.repo.createHousehold(one, HouseholdInput("Наш дом", "Asia/Yekaterinburg")))
          listOne <- ZIO.attemptBlocking(ctx.repo.households(one))
          listTwo <- ZIO.attemptBlocking(ctx.repo.households(two))
        yield assertTrue(one.profile.status == "ACTIVE", listOne.map(_.id).contains(household.id), listTwo.isEmpty)
      },
      test("refresh rotation persists replay revocation and invalidates the replacement JWT session") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          rotated <- ctx.service.refresh(session.refreshToken)
          replay <- ctx.service.refresh(session.refreshToken).either
          replacement <- principal(ctx, rotated).either
          replacementRefresh <- ctx.service.refresh(rotated.refreshToken).either
        yield assertTrue(replay.isLeft, replacement.isLeft, replacementRefresh.isLeft)
      },
      test("concurrent refresh attempts cannot create independent live chains") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          outcomes <- ZIO.collectAllPar(List.fill(2)(ctx.service.refresh(session.refreshToken).either))
          stillLive <- principal(ctx, session).either
        yield assertTrue(outcomes.count(_.isRight) == 1, outcomes.count(_.isLeft) == 1, stillLive.isLeft)
      },
      test("changing a password requires the current password and revokes every prior session") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          p <- principal(ctx, session)
          wrong <- ctx.service.changePassword(p, PasswordInput("wrong", "another-password-1234")).either
          _ <- ctx.service.changePassword(p, PasswordInput(password, "another-password-1234"))
          revoked <- principal(ctx, session).either
          old <- ctx.service.login(LoginInput(p.profile.username, password)).either
          replacement <- ctx.service.login(LoginInput(p.profile.username, "another-password-1234"))
        yield assertTrue(wrong.isLeft, revoked.isLeft, old.isLeft, replacement.user.status == "PENDING")
      },
      test("administrator reset sends a one-time link, revokes sessions and does not approve a pending account") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          _ <- ctx.service.resetByAdmin(ctx.admin, UUID.fromString(session.user.id))
          url <- ZIO.attempt(ctx.mailer.messages.last._2)
          raw = url.split("token=")(1)
          _ <- ctx.service.completeReset(ResetInput(raw, "recovered-password-123"))
          reused <- ctx.service.completeReset(ResetInput(raw, "other-password-12345")).either
          revoked <- principal(ctx, session).either
          loggedIn <- ctx.service.login(LoginInput(session.user.username, "recovered-password-123"))
        yield assertTrue(url.startsWith("http://localhost:5173/#/reset-password?token="), reused.isLeft, revoked.isLeft, loggedIn.user.status == "PENDING")
      },
      test("suspension revokes sessions immediately and self suspension is refused") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          _ <- ctx.service.setStatus(ctx.admin, UUID.fromString(session.user.id), "approve")
          _ <- ctx.service.setStatus(ctx.admin, UUID.fromString(session.user.id), "suspend")
          revoked <- principal(ctx, session).either
          login <- ctx.service.login(LoginInput(session.user.username, password)).either
          self <- ctx.service.setStatus(ctx.admin, UUID.fromString(ctx.admin.profile.id), "suspend").either
          _ <- ctx.service.setStatus(ctx.admin, UUID.fromString(session.user.id), "reactivate")
          back <- ctx.service.login(LoginInput(session.user.username, password))
        yield assertTrue(revoked.isLeft, login.isLeft, self.isLeft, back.user.status == "ACTIVE")
      },
      test("expired reset links and SMTP failures never report successful recovery") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          _ <- ctx.service.resetByAdmin(ctx.admin, UUID.fromString(session.user.id))
          raw <- ZIO.attempt(ctx.mailer.messages.last._2.split("token=")(1))
          _ <- ZIO.attemptBlocking(ctx.db.connection { c =>
            Sql.update(c, "UPDATE password_reset_token SET requested_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' WHERE token_hash=?", OpaqueTokens.hash(raw))
          })
          expired <- ctx.service.completeReset(ResetInput(raw, "expired-password-123")).either
          failing = new AuthService(ctx.repo, ctx.service.jwt, new Mailer {
            def sendPasswordReset(email: String, url: String): Unit = throw new java.io.IOException("offline")
          }, "http://localhost:5173")
          sendFailed <- failing.resetByAdmin(ctx.admin, UUID.fromString(session.user.id)).either
          remaining <- ZIO.attemptBlocking(ctx.db.connection { c =>
            Sql.one(c, "SELECT count(*) FROM password_reset_token WHERE user_id=? AND revoked_at IS NULL AND consumed_at IS NULL AND expires_at>now()", UUID.fromString(session.user.id))(_.getLong(1)).get
          })
        yield assertTrue(expired.isLeft, sendFailed.left.toOption.exists { case e: ApiError => e.status == 503; case _ => false }, remaining == 0)
      },
      test("HTTP routes deny missing authentication, pending admin access and refresh without CSRF") {
        for
          ctx <- ZIO.service[Context]
          session <- register(ctx)
          config = AppConfig(
            dbUrl = "unused", dbUser = "unused", dbPassword = "unused", poolSize = 1,
            issuer = "test", audience = "api", keyId = "test-key", privateKey = java.nio.file.Path.of("unused"), publicKey = java.nio.file.Path.of("unused"),
            publicUrl = "http://localhost:5173", allowedOrigins = Set("http://localhost:5173"), cookieSecure = false, cookieSameSite = "Lax",
            smtpHost = "unused", smtpPort = 1025, smtpUser = "", smtpPassword = "", smtpStartTls = false,
            mailFrom = "unused@example.test", bootstrapEmail = "admin@example.test"
          )
          routes = new Api(ctx.service, config).routes
          unauthenticated <- ZIO.scoped(routes.runZIO(Request.get("/api/admin/users")))
          denied <- ZIO.scoped(routes.runZIO(Request.get("/api/admin/users").addHeader("Authorization", "Bearer " + ctx.service.jwt.issue(session.user.id, session.sessionId))))
          csrfDenied <- ZIO.scoped(routes.runZIO(Request.post("/api/auth/refresh", Body.empty).addHeader("Origin", "http://localhost:5173")))
          originDenied <- ZIO.scoped(routes.runZIO(Request.get("/api/auth/csrf").addHeader("Origin", "https://attacker.example")))
        yield assertTrue(unauthenticated.status == Status.Unauthorized, unauthenticated.rawHeader("Set-Cookie").isEmpty, denied.status == Status.Forbidden,
          csrfDenied.status == Status.Forbidden, originDenied.status == Status.Forbidden)
      },
      test("audit and pagination use persisted records") {
        for
          ctx <- ZIO.service[Context]
          page <- ctx.service.users(ctx.admin, None, "", 0, 2)
          audit <- ctx.service.audit(ctx.admin)
        yield assertTrue(page.items.size == 2, page.total >= 3, audit.exists(_.action == "BOOTSTRAP_PASSWORD_CHANGED"), audit.exists(_.action == "PASSWORD_RESET_COMPLETED"))
      }
    ).provideShared(contextLayer) @@ TestAspect.sequential
    if sys.env.contains("TEST_DB_URL") then tests else tests @@ TestAspect.ignore
