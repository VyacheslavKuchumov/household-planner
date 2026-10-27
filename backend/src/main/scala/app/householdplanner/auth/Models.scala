package app.householdplanner.auth

import java.time.Instant
import java.util.UUID
import zio.json.*

final case class ApiError(status: Int, code: String, message: String) extends Exception(message)
object ApiError:
  def unauthorized = ApiError(401, "UNAUTHENTICATED", "Войдите в аккаунт заново")
  def forbidden = ApiError(403, "FORBIDDEN", "Недостаточно прав для этого действия")
  def invalid(message: String) = ApiError(400, "VALIDATION_ERROR", message)
  def missing = ApiError(404, "NOT_FOUND", "Запись не найдена")

final case class Profile(
  id: String, username: String, email: String, nickname: String,
  status: String, isAdmin: Boolean, passwordChangeRequired: Boolean,
  createdAt: String
) derives JsonCodec

final case class StoredUser(profile: Profile, passwordHash: Option[String])
final case class Principal(profile: Profile, sessionId: UUID)
final case class LoginInput(login: String, password: String) derives JsonCodec
final case class RegisterInput(username: String, email: String, nickname: String, password: String) derives JsonCodec
final case class ProfileInput(nickname: String) derives JsonCodec
final case class PasswordInput(currentPassword: String, newPassword: String) derives JsonCodec
final case class ResetInput(token: String, newPassword: String) derives JsonCodec
final case class TokenResponse(accessToken: String, expiresIn: Long, user: Profile) derives JsonCodec
final case class ErrorResponse(code: String, message: String, requestId: String) derives JsonCodec
final case class MessageResponse(message: String) derives JsonCodec
final case class CsrfResponse(csrfToken: String) derives JsonCodec
final case class UserPage(items: List[Profile], total: Long, offset: Int, limit: Int) derives JsonCodec
final case class AuditEntry(id: String, actor: Option[String], target: Option[String], action: String, occurredAt: String) derives JsonCodec
final case class SessionTokens(user: Profile, sessionId: UUID, refreshToken: String, expiresAt: Instant)
final case class HouseholdInput(name: String, timezone: String) derives JsonCodec
final case class HouseholdSummary(id: String, name: String, timezone: String, role: String, memberCount: Long) derives JsonCodec

object AccessPolicy:
  def profile(user: Profile): Unit =
    if !Set("PENDING", "ACTIVE").contains(user.status) then
      throw ApiError(403, "ACCOUNT_UNAVAILABLE", "Доступ аккаунта закрыт. Обратитесь к администратору")
  def passwordReady(user: Profile): Unit =
    profile(user)
    if user.passwordChangeRequired then
      throw ApiError(403, "PASSWORD_CHANGE_REQUIRED", "Сначала смените временный пароль")
  def workspace(user: Profile): Unit =
    passwordReady(user)
    if user.status != "ACTIVE" then
      throw ApiError(403, "ACCOUNT_PENDING", "Аккаунт ожидает подтверждения администратора")
  def admin(user: Profile): Unit =
    workspace(user)
    if !user.isAdmin then throw ApiError.forbidden

object Validation:
  def nickname(value: String): String =
    val result = value.trim
    if result.isEmpty || result.length > 80 || result.exists(c => Character.isISOControl(c)) then
      throw ApiError.invalid("Никнейм должен содержать от 1 до 80 символов")
    result
  def password(value: String): String =
    if value.length < 12 || value.length > 256 then
      throw ApiError.invalid("Пароль должен содержать от 12 до 256 символов")
    value
  def registration(input: RegisterInput): RegisterInput =
    val username = input.username.trim.toLowerCase(java.util.Locale.ROOT)
    val email = input.email.trim.toLowerCase(java.util.Locale.ROOT)
    if !username.matches("[a-z][a-z0-9_]{2,31}") then
      throw ApiError.invalid("Логин: от 3 до 32 латинских букв, цифр и символов _")
    if email.length > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") then
      throw ApiError.invalid("Укажите корректный email")
    try
      val address = new jakarta.mail.internet.InternetAddress(email, true)
      address.validate()
      if address.getAddress != email || address.isGroup then throw ApiError.invalid("Укажите корректный email")
    catch case _: jakarta.mail.internet.AddressException => throw ApiError.invalid("Укажите корректный email")
    input.copy(username = username, email = email, nickname = nickname(input.nickname), password = password(input.password))
