package app.householdplanner.auth

import jakarta.mail.{Authenticator, Message, PasswordAuthentication, Session, Transport}
import jakarta.mail.internet.{InternetAddress, MimeMessage}
import java.util.Properties

trait Mailer:
  def sendPasswordReset(email: String, url: String): Unit

final class SmtpMailer(config: AppConfig) extends Mailer:
  def sendPasswordReset(email: String, url: String): Unit =
    val properties = new Properties()
    properties.setProperty("mail.smtp.host", config.smtpHost)
    properties.setProperty("mail.smtp.port", config.smtpPort.toString)
    properties.setProperty("mail.smtp.auth", config.smtpUser.nonEmpty.toString)
    properties.setProperty("mail.smtp.starttls.enable", config.smtpStartTls.toString)
    properties.setProperty("mail.smtp.starttls.required", config.smtpStartTls.toString)
    properties.setProperty("mail.smtp.ssl.checkserveridentity", "true")
    properties.setProperty("mail.smtp.connectiontimeout", "10000")
    properties.setProperty("mail.smtp.timeout", "10000")
    properties.setProperty("mail.smtp.writetimeout", "10000")
    val auth = new Authenticator():
      override def getPasswordAuthentication(): PasswordAuthentication =
        new PasswordAuthentication(config.smtpUser, config.smtpPassword)
    val session = Session.getInstance(properties, auth)
    val message = new MimeMessage(session)
    message.setFrom(new InternetAddress(config.mailFrom))
    message.setRecipient(Message.RecipientType.TO, new InternetAddress(email, true))
    message.setSubject("Сброс пароля — Household Planner", "UTF-8")
    message.setText(s"""Администратор запросил сброс пароля вашего аккаунта.

Установить новый пароль: $url

Ссылка действует 30 минут и может быть использована только один раз.
Если вы не ожидали это письмо, обратитесь к администратору.
""", "UTF-8")
    Transport.send(message)
