package app.householdplanner.auth

import java.util.UUID
import zio.*
import zio.http.*
import zio.http.codec.PathCodec.{trailing, uuid}
import zio.json.*

final class Api(service: AuthService, config: AppConfig):
  private val limiter = new RateLimiter()
  private def json[A: JsonEncoder](value: A): Response = Response.json(value.toJson)
  private def message(value: String): Response = json(MessageResponse(value))
  private def status(code: Int): Status = code match
    case 400 => Status.BadRequest
    case 401 => Status.Unauthorized
    case 403 => Status.Forbidden
    case 404 => Status.NotFound
    case 409 => Status.Conflict
    case 429 => Status.TooManyRequests
    case 503 => Status.ServiceUnavailable
    case _ => Status.InternalServerError
  private def cookie(name: String, token: String, maxAge: Long): String =
    s"$name=$token; Path=/api/auth; Max-Age=$maxAge; HttpOnly; SameSite=${config.cookieSameSite}" +
      (if config.cookieSecure then "; Secure" else "")
  private def clearSession(response: Response): Response = response.addHeader("Set-Cookie", cookie("hp_refresh", "", 0))
  private def tokens(value: SessionTokens): Response =
    json(TokenResponse(service.jwt.issue(value.user.id, value.sessionId), service.jwt.lifetimeSeconds, value.user))
      .addHeader("Set-Cookie", cookie("hp_refresh", value.refreshToken,
        math.max(0, java.time.Duration.between(java.time.Instant.now(), value.expiresAt).getSeconds)))
  private def origin(request: Request): Unit =
    if request.rawHeader("Origin").exists(value => !config.allowedOrigins.contains(value)) then
      throw ApiError(403, "ORIGIN_FORBIDDEN", "Источник запроса не разрешён")
  private def csrf(request: Request): Task[Unit] = ZIO.attempt {
    if !request.rawHeader("Origin").exists(config.allowedOrigins.contains) then throw ApiError.forbidden
    val supplied = request.rawHeader("X-CSRF-Token").getOrElse(throw ApiError.forbidden)
    val expected = request.cookie("hp_csrf").map(_.content).getOrElse(throw ApiError.forbidden)
    if !OpaqueTokens.valid(supplied) || !OpaqueTokens.same(supplied, expected) then throw ApiError.forbidden
  }
  private def principal(request: Request): Task[Principal] =
    ZIO.fromOption(request.rawHeader("Authorization").filter(_.startsWith("Bearer ")).map(_.drop(7)))
      .orElseFail(ApiError.unauthorized).flatMap(service.principal)
  private def body[A: JsonDecoder](request: Request): Task[A] =
    if !request.hasJsonContentType then ZIO.fail(ApiError.invalid("Ожидается JSON"))
    else request.body.asString.flatMap { raw =>
      if raw.length > 16384 then ZIO.fail(ApiError.invalid("Слишком большой запрос"))
      else ZIO.fromEither(raw.fromJson[A].left.map(_ => ApiError.invalid("Некорректный формат запроса")))
    }
  private def rate(request: Request, category: String, limit: Int): Task[Unit] = ZIO.attempt {
    // Forwarded headers are deliberately not trusted without a configured trusted proxy boundary.
    val ip = request.remoteAddress.map(_.getHostAddress).getOrElse("unknown")
    limiter.check(s"$category:$ip", limit)
  }
  private def respond(request: Request, clearOnUnauthorized: Boolean = false)(effect: => Task[Response]): UIO[Response] =
    val requestId = UUID.randomUUID().toString
    (ZIO.attempt(origin(request)) *> ZIO.suspendSucceed(effect)).catchAll { error =>
      val response = error match
        case e: ApiError => json(ErrorResponse(e.code, e.message, requestId)).status(status(e.status))
        case _ => json(ErrorResponse("INTERNAL_ERROR", "Не удалось выполнить запрос", requestId)).status(Status.InternalServerError)
      val prepared =
        if response.status == Status.Unauthorized then
          (if clearOnUnauthorized then clearSession(response) else response).addHeader("WWW-Authenticate", "Bearer")
        else response
      error match
        case e: ApiError => ZIO.succeed(if e.status == 429 then prepared.addHeader("Retry-After", "60") else prepared)
        case _ => ZIO.logError(s"Request $requestId failed (${error.getClass.getSimpleName})").as(prepared)
    }.map { response =>
      val secured = response.addHeader("Cache-Control", "no-store").addHeader("X-Content-Type-Options", "nosniff")
        .addHeader("Referrer-Policy", "no-referrer").addHeader("X-Request-Id", requestId)
      request.rawHeader("Origin").filter(config.allowedOrigins.contains) match
        case Some(value) => secured.addHeader("Access-Control-Allow-Origin", value)
          .addHeader("Access-Control-Allow-Credentials", "true").addHeader("Vary", "Origin")
        case None => secured
    }
  private def query(request: Request, key: String): Option[String] = request.url.queryParams.getAll(key).headOption
  private def integer(request: Request, key: String, fallback: Int): Task[Int] =
    query(request, key).fold[Task[Int]](ZIO.succeed(fallback))(s => ZIO.attempt(s.toInt).mapError(_ => ApiError.invalid("Некорректная пагинация")))

  val routes: Routes[Any, Response] = Routes(
    Method.GET / "api" / "health" -> handler { (_: Request) => Response.text("ok") },
    Method.GET / "api" / "ready" -> handler { (r: Request) => respond(r) {
      ZIO.attemptBlocking(service.repository.ready()).mapError(_ => ApiError(503, "NOT_READY", "База данных недоступна")).as(message("ready"))
    } },
    Method.OPTIONS / "api" / trailing -> handler { (_: Path, r: Request) => respond(r) {
      ZIO.succeed(Response(status = Status.NoContent)
        .addHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,OPTIONS")
        .addHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,X-CSRF-Token")
        .addHeader("Access-Control-Max-Age", "600"))
    } },
    Method.GET / "api" / "auth" / "csrf" -> handler { (r: Request) => respond(r) {
      ZIO.attempt {
        val value = OpaqueTokens.generate()
        json(CsrfResponse(value)).addHeader("Set-Cookie", cookie("hp_csrf", value, 2592000))
      }
    } },
    Method.POST / "api" / "auth" / "register" -> handler { (r: Request) => respond(r) {
      for
        _ <- rate(r, "register", 10)
        input <- body[RegisterInput](r)
        profile <- service.register(input)
      yield json(profile).status(Status.Created)
    } },
    Method.POST / "api" / "auth" / "login" -> handler { (r: Request) => respond(r) {
      for
        _ <- rate(r, "login", 100)
        input <- body[LoginInput](r)
        value <- service.login(input)
      yield tokens(value)
    } },
    Method.POST / "api" / "auth" / "refresh" -> handler { (r: Request) => respond(r, clearOnUnauthorized = true) {
      for
        _ <- rate(r, "refresh", 200)
        _ <- csrf(r)
        raw <- ZIO.fromOption(r.cookie("hp_refresh").map(_.content)).orElseFail(ApiError.unauthorized)
        value <- service.refresh(raw)
      yield tokens(value)
    } },
    Method.POST / "api" / "auth" / "logout" -> handler { (r: Request) => respond(r) {
      for
        p <- principal(r)
        _ <- service.logout(p, all = false)
      yield clearSession(message("Вы вышли из аккаунта"))
    } },
    Method.POST / "api" / "auth" / "logout-all" -> handler { (r: Request) => respond(r) {
      for
        p <- principal(r)
        _ <- service.logout(p, all = true)
      yield clearSession(message("Все сессии завершены"))
    } },
    Method.GET / "api" / "me" / "profile" -> handler { (r: Request) => respond(r) {
      principal(r).flatMap(service.profile).map(json(_))
    } },
    Method.PATCH / "api" / "me" / "profile" -> handler { (r: Request) => respond(r) {
      for
        p <- principal(r)
        input <- body[ProfileInput](r)
        result <- service.updateProfile(p, input)
      yield json(result)
    } },
    Method.POST / "api" / "auth" / "change-password" -> handler { (r: Request) => respond(r) {
      for
        _ <- rate(r, "password", 20)
        p <- principal(r)
        input <- body[PasswordInput](r)
        _ <- service.changePassword(p, input)
      yield clearSession(message("Пароль изменён. Войдите заново"))
    } },
    Method.POST / "api" / "auth" / "password-reset" / "complete" -> handler { (r: Request) => respond(r) {
      for
        _ <- rate(r, "reset", 20)
        input <- body[ResetInput](r)
        _ <- service.completeReset(input)
      yield clearSession(message("Пароль обновлён. Войдите заново"))
    } },
    Method.GET / "api" / "workspace" -> handler { (r: Request) => respond(r) {
      principal(r).flatMap(service.workspace).map(json(_))
    } },
    Method.GET / "api" / "households" -> handler { (r: Request) => respond(r) {
      principal(r).flatMap(p => ZIO.attemptBlocking(service.repository.households(p))).map(json(_))
    } },
    Method.POST / "api" / "households" -> handler { (r: Request) => respond(r) {
      for
        p <- principal(r)
        input <- body[HouseholdInput](r)
        result <- ZIO.attemptBlocking(service.repository.createHousehold(p, input))
      yield json(result).status(Status.Created)
    } },
    Method.GET / "api" / "admin" / "users" -> handler { (r: Request) => respond(r) {
      for
        p <- principal(r)
        offset <- integer(r, "offset", 0)
        limit <- integer(r, "limit", 20)
        result <- service.users(p, query(r, "status"), query(r, "search").getOrElse(""), offset, limit)
      yield json(result)
    } },
    Method.POST / "api" / "admin" / "users" / uuid("id") / "approve" -> handler { (id: UUID, r: Request) => respond(r) {
      principal(r).flatMap(service.setStatus(_, id, "approve")).map(json(_))
    } },
    Method.POST / "api" / "admin" / "users" / uuid("id") / "reject" -> handler { (id: UUID, r: Request) => respond(r) {
      principal(r).flatMap(service.setStatus(_, id, "reject")).map(json(_))
    } },
    Method.POST / "api" / "admin" / "users" / uuid("id") / "suspend" -> handler { (id: UUID, r: Request) => respond(r) {
      principal(r).flatMap(service.setStatus(_, id, "suspend")).map(json(_))
    } },
    Method.POST / "api" / "admin" / "users" / uuid("id") / "reactivate" -> handler { (id: UUID, r: Request) => respond(r) {
      principal(r).flatMap(service.setStatus(_, id, "reactivate")).map(json(_))
    } },
    Method.POST / "api" / "admin" / "users" / uuid("id") / "password-reset" -> handler { (id: UUID, r: Request) => respond(r) {
      for
        _ <- rate(r, "admin-reset", 10)
        p <- principal(r)
        _ <- service.resetByAdmin(p, id)
      yield message("Письмо со ссылкой отправлено")
    } },
    Method.GET / "api" / "admin" / "audit" -> handler { (r: Request) => respond(r) {
      principal(r).flatMap(service.audit).map(json(_))
    } },
    Method.ANY / "api" / trailing -> handler { (_: Path, r: Request) => respond(r) {
      ZIO.fail(ApiError.missing)
    } }
  ).handleErrorCause { _ =>
    json(ErrorResponse("INTERNAL_ERROR", "Не удалось выполнить запрос", UUID.randomUUID().toString))
      .status(Status.InternalServerError).addHeader("Cache-Control", "no-store")
  }
