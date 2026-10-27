package app.householdplanner.auth

import java.sql.SQLException
import java.util.UUID
import zio.*

final class AuthService(val repository: AuthRepository, val jwt: JwtTokens, mailer: Mailer, publicUrl: String):
  private lazy val dummyHash = Passwords.hash(OpaqueTokens.generate())
  private val loginLimiter = new RateLimiter()
  private def blocking[A](f: => A): Task[A] = ZIO.attemptBlocking(f)
  def register(input: RegisterInput): Task[Profile] = blocking {
    val valid = Validation.registration(input)
    repository.register(valid, Passwords.hash(valid.password))
  }.mapError {
    case e: SQLException if e.getSQLState == "23505" => ApiError(409, "ACCOUNT_EXISTS", "Логин или email уже занят")
    case other => other
  }
  def login(input: LoginInput): Task[SessionTokens] = blocking {
    val login = input.login.trim.toLowerCase(java.util.Locale.ROOT)
    if login.length > 254 || input.password.length > 256 then throw ApiError.unauthorized
    loginLimiter.check("login:" + java.util.Base64.getEncoder.encodeToString(OpaqueTokens.hash(login)), 10)
    val stored = repository.findLogin(login)
    val hash = stored.flatMap(_.passwordHash).getOrElse(dummyHash)
    val valid = Passwords.matches(input.password, hash)
    if !valid || stored.isEmpty || stored.get.passwordHash.isEmpty then
      throw ApiError(401, "INVALID_CREDENTIALS", "Неверный логин или пароль")
    val account = stored.get
    AccessPolicy.profile(account.profile)
    repository.openSession(UUID.fromString(account.profile.id), hash)
  }
  def principal(token: String): Task[Principal] = blocking {
    val (uid, sid) = jwt.verify(token)
    repository.authenticate(uid, sid)
  }
  def refresh(token: String): Task[SessionTokens] = blocking {
    if !OpaqueTokens.valid(token) then throw ApiError.unauthorized
    repository.rotate(token)
  }
  def profile(principal: Principal): Task[Profile] = blocking {
    AccessPolicy.profile(principal.profile)
    principal.profile
  }
  def updateProfile(principal: Principal, input: ProfileInput): Task[Profile] = blocking {
    AccessPolicy.passwordReady(principal.profile)
    repository.updateNickname(principal, Validation.nickname(input.nickname))
  }
  def changePassword(principal: Principal, input: PasswordInput): Task[Unit] = blocking {
    Validation.password(input.newPassword)
    val current = repository.findUser(UUID.fromString(principal.profile.id))
    val old = current.passwordHash.getOrElse(throw ApiError.unauthorized)
    if input.currentPassword.length > 256 || !Passwords.matches(input.currentPassword, old) then
      throw ApiError(400, "CURRENT_PASSWORD_INVALID", "Текущий пароль указан неверно")
    if Passwords.matches(input.newPassword, old) then throw ApiError.invalid("Новый пароль должен отличаться от текущего")
    repository.changePassword(principal, old, Passwords.hash(input.newPassword))
  }
  def completeReset(input: ResetInput): Task[Unit] = blocking {
    if !OpaqueTokens.valid(input.token) then throw ApiError(400, "RESET_INVALID", "Ссылка недействительна или срок её действия истёк")
    repository.completeReset(input.token, Passwords.hash(Validation.password(input.newPassword)))
  }
  def resetByAdmin(principal: Principal, targetId: UUID): Task[Unit] =
    blocking {
      AccessPolicy.admin(principal.profile)
      repository.issueReset(principal, targetId)
    }.flatMap { (id, email, raw) =>
      // A URL fragment keeps the secret out of HTTP request paths and access logs.
      blocking(mailer.sendPasswordReset(email, s"$publicUrl/#/reset-password?token=$raw")).catchAll { _ =>
        blocking(repository.revokeReset(id)) *> ZIO.fail(ApiError(503, "MAIL_UNAVAILABLE", "Не удалось отправить письмо. Попробуйте позже"))
      }
    }
  def users(principal: Principal, status: Option[String], search: String, offset: Int, limit: Int): Task[UserPage] = blocking {
    AccessPolicy.admin(principal.profile)
    if status.exists(s => !Set("ACTIVE", "PENDING", "SUSPENDED", "REJECTED").contains(s)) ||
        search.length > 100 || offset < 0 || limit < 1 || limit > 100 then throw ApiError.invalid("Некорректные параметры списка")
    repository.listUsers(status, search, offset, limit)
  }
  def setStatus(principal: Principal, id: UUID, action: String): Task[Profile] = blocking {
    AccessPolicy.admin(principal.profile)
    repository.setStatus(principal, id, action)
  }
  def audit(principal: Principal): Task[List[AuditEntry]] = blocking {
    AccessPolicy.admin(principal.profile)
    repository.auditLog()
  }
  def logout(principal: Principal, all: Boolean): Task[Unit] = blocking(repository.logout(principal, all))
  def workspace(principal: Principal): Task[MessageResponse] = blocking {
    AccessPolicy.workspace(principal.profile)
    MessageResponse("Доступ к планировщику открыт")
  }
