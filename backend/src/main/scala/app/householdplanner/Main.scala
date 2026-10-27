package app.householdplanner

import app.householdplanner.auth.*
import zio.*
import zio.http.Server

object Main extends ZIOAppDefault:
  override def run = ZIO.scoped {
    for
      config <- ZIO.attempt(AppConfig.load())
      db <- ZIO.acquireRelease(ZIO.attemptBlocking(Database.open(config)))(db => ZIO.attemptBlocking(db.close()).orDie)
      repository = new AuthRepository(db)
      _ <- ZIO.attemptBlocking(repository.ready())
      _ <- ZIO.attemptBlocking(repository.bootstrap(config.bootstrapEmail))
      jwt <- ZIO.attempt(JwtTokens.load(config))
      service = new AuthService(repository, jwt, new SmtpMailer(config), config.publicUrl)
      api = new Api(service, config)
      port <- ZIO.attempt(sys.env.getOrElse("PORT", "8080").toInt)
      _ <- ZIO.logInfo(s"Household Planner API listening on port $port")
      _ <- Server.serve(api.routes).provide(Server.defaultWith(_.port(port).disableRequestStreaming(16384)))
    yield ()
  }
