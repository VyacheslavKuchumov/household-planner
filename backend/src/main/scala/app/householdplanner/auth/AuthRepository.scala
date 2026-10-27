package app.householdplanner.auth

import java.sql.{Connection, ResultSet}
import java.time.{Duration, Instant}
import java.util.UUID

final class AuthRepository(val db: Database):
  private val userSelect = """SELECT u.*, EXISTS(
    SELECT 1 FROM user_role r WHERE r.user_id=u.id AND r.role_code='PLATFORM_ADMIN') AS is_admin FROM app_user u"""
  private def user(row: ResultSet): StoredUser = StoredUser(
    Profile(row.getObject("id").toString, Option(row.getString("username")).getOrElse(row.getString("email")),
      row.getString("email"), row.getString("display_name"), row.getString("account_status"),
      row.getBoolean("is_admin"), row.getBoolean("password_change_required"), row.getTimestamp("created_at").toInstant.toString),
    Option(row.getString("password_hash"))
  )
  private def byId(c: Connection, id: UUID, lock: Boolean = false): StoredUser =
    Sql.one(c, userSelect + " WHERE u.id=?" + (if lock then " FOR UPDATE OF u" else ""), id)(user)
      .getOrElse(throw ApiError.missing)
  private def current(c: Connection, userId: UUID, sid: UUID): Principal =
    val stored = byId(c, userId)
    val exists = Sql.one(c, "SELECT id FROM auth_session WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now()", sid, userId)(_.getObject(1)).isDefined
    if !exists then throw ApiError.unauthorized
    AccessPolicy.profile(stored.profile)
    Principal(stored.profile, sid)
  private def audit(c: Connection, actor: Option[UUID], target: UUID, action: String): Unit =
    Sql.update(c, "INSERT INTO admin_audit_log(actor_user_id,target_user_id,action) VALUES (?,?,?)", actor.orNull, target, action)
    ()
  private def revoke(c: Connection, userId: UUID): Unit =
    Sql.update(c, "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?", userId)
    Sql.update(c, "UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,now()) WHERE session_id IN (SELECT id FROM auth_session WHERE user_id=?)", userId)
    ()
  private def adminTransaction[A](principal: Principal, targetId: UUID)(f: (Connection, StoredUser) => A): A = db.transaction { c =>
    // Serialize platform administration, then recheck the actor inside the transaction.
    Sql.query(c, "SELECT pg_advisory_xact_lock(7419302)")(_ => ())
    byId(c, UUID.fromString(principal.profile.id), lock = true)
    AccessPolicy.admin(current(c, UUID.fromString(principal.profile.id), principal.sessionId).profile)
    f(c, byId(c, targetId, lock = true))
  }

  def bootstrap(email: String): Unit = db.transaction { c =>
    Sql.query(c, "SELECT pg_advisory_xact_lock(7419301)")(_ => ())
    val alreadyCreated = Sql.one(c, "SELECT completed_at FROM system_bootstrap WHERE name='initial_admin'")(_.getTimestamp(1)).isDefined
    if !alreadyCreated then
      val exists = Sql.one(c, "SELECT user_id FROM user_role WHERE role_code='PLATFORM_ADMIN' LIMIT 1")(_.getObject(1)).isDefined
      if !exists then
        val id = UUID.randomUUID()
        Sql.update(c, """INSERT INTO app_user(id,display_name,username,email,password_hash,account_status,password_change_required)
          VALUES (?,'Администратор','admin',?,?,'ACTIVE',true)""", id, email, Passwords.hash("admin"))
        Sql.update(c, "INSERT INTO user_role(user_id,role_code) VALUES (?,'PLATFORM_ADMIN')", id)
      Sql.update(c, "INSERT INTO system_bootstrap(name) VALUES ('initial_admin') ON CONFLICT DO NOTHING")
  }

  def register(input: RegisterInput, hash: String): Profile = db.transaction { c =>
    val id = UUID.randomUUID()
    Sql.update(c, """INSERT INTO app_user(id,username,email,display_name,password_hash,account_status)
      VALUES (?,?,?,?,?,'PENDING')""", id, input.username, input.email, input.nickname, hash)
    byId(c, id).profile
  }
  def findLogin(login: String): Option[StoredUser] = db.connection { c =>
    Sql.one(c, userSelect + " WHERE lower(u.username)=? OR lower(u.email)=?", login, login)(user)
  }
  def findUser(id: UUID): StoredUser = db.connection(c => byId(c, id))
  def authenticate(userId: UUID, sessionId: UUID): Principal = db.connection(c => current(c, userId, sessionId))

  def openSession(userId: UUID, expectedHash: String): SessionTokens = db.transaction { c =>
    val stored = byId(c, userId, lock = true)
    if !stored.passwordHash.contains(expectedHash) then throw ApiError.unauthorized
    AccessPolicy.profile(stored.profile)
    val sid = UUID.randomUUID()
    val token = OpaqueTokens.generate()
    val expires = Instant.now().plus(Duration.ofDays(30))
    Sql.update(c, "INSERT INTO auth_session(id,user_id,expires_at) VALUES (?,?,?)", sid, userId, expires)
    Sql.update(c, "INSERT INTO refresh_token(session_id,token_hash,expires_at) VALUES (?,?,?)", sid, OpaqueTokens.hash(token), expires)
    SessionTokens(stored.profile, sid, token, expires)
  }

  def rotate(rawToken: String): SessionTokens =
    val result: Either[ApiError, SessionTokens] = db.transaction { c =>
      val meta = Sql.one(c, """SELECT s.user_id,t.session_id FROM refresh_token t
        JOIN auth_session s ON s.id=t.session_id WHERE t.token_hash=?""", OpaqueTokens.hash(rawToken)) { row =>
        (row.getObject("user_id", classOf[UUID]), row.getObject("session_id", classOf[UUID]))
      }
      meta match
        case None => Left(ApiError.unauthorized)
        case Some((uid, sid)) =>
          val stored = byId(c, uid, lock = true)
          val session = Sql.one(c, "SELECT * FROM auth_session WHERE id=? FOR UPDATE", sid) { r =>
            (Option(r.getTimestamp("revoked_at")), r.getTimestamp("expires_at").toInstant)
          }.get
          val token = Sql.one(c, "SELECT * FROM refresh_token WHERE token_hash=? FOR UPDATE", OpaqueTokens.hash(rawToken)) { r =>
            (r.getObject("id", classOf[UUID]), Option(r.getTimestamp("used_at")), Option(r.getTimestamp("revoked_at")), r.getTimestamp("expires_at").toInstant)
          }.get
          if token._2.nonEmpty then
            Sql.update(c, "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,now()) WHERE id=?", sid)
            Sql.update(c, "UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,now()) WHERE session_id=?", sid)
            // Return an error value so revocation commits before the error is raised.
            Left(ApiError.unauthorized)
          else if session._1.nonEmpty || !session._2.isAfter(Instant.now()) || token._3.nonEmpty ||
              !token._4.isAfter(Instant.now()) || !Set("ACTIVE", "PENDING").contains(stored.profile.status) then Left(ApiError.unauthorized)
          else
            val replacement = OpaqueTokens.generate()
            val id = UUID.randomUUID()
            Sql.update(c, "UPDATE refresh_token SET used_at=now() WHERE id=?", token._1)
            Sql.update(c, "INSERT INTO refresh_token(id,session_id,token_hash,expires_at) VALUES (?,?,?,?)", id, sid, OpaqueTokens.hash(replacement), session._2)
            Sql.update(c, "UPDATE refresh_token SET replaced_by_token_id=? WHERE id=?", id, token._1)
            Sql.update(c, "UPDATE auth_session SET last_seen_at=now() WHERE id=?", sid)
            Right(SessionTokens(stored.profile, sid, replacement, session._2))
    }
    result.fold(throw _, identity)

  def logout(principal: Principal, all: Boolean): Unit = db.transaction { c =>
    if all then revoke(c, UUID.fromString(principal.profile.id))
    else
      Sql.update(c, "UPDATE auth_session SET revoked_at=COALESCE(revoked_at,now()) WHERE id=? AND user_id=?", principal.sessionId, UUID.fromString(principal.profile.id))
      Sql.update(c, "UPDATE refresh_token SET revoked_at=COALESCE(revoked_at,now()) WHERE session_id=?", principal.sessionId)
      ()
  }
  def updateNickname(principal: Principal, name: String): Profile = db.transaction { c =>
    val uid = UUID.fromString(principal.profile.id)
    byId(c, uid, lock = true)
    AccessPolicy.passwordReady(current(c, uid, principal.sessionId).profile)
    Sql.update(c, "UPDATE app_user SET display_name=? WHERE id=?", name, uid)
    byId(c, uid).profile
  }
  def changePassword(principal: Principal, expectedHash: String, newHash: String): Unit = db.transaction { c =>
    val uid = UUID.fromString(principal.profile.id)
    val stored = byId(c, uid, lock = true)
    current(c, uid, principal.sessionId)
    if !stored.passwordHash.contains(expectedHash) then throw ApiError.unauthorized
    Sql.update(c, "UPDATE app_user SET password_hash=?,password_change_required=false WHERE id=?", newHash, uid)
    revoke(c, uid)
    Sql.update(c, "UPDATE password_reset_token SET revoked_at=now() WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL", uid)
    audit(c, Some(uid), uid, if stored.profile.passwordChangeRequired then "BOOTSTRAP_PASSWORD_CHANGED" else "PASSWORD_CHANGED")
  }

  def listUsers(status: Option[String], search: String, offset: Int, limit: Int): UserPage = db.connection { c =>
    val where = " WHERE (? IS NULL OR u.account_status=?) AND (u.display_name ILIKE ? OR u.email ILIKE ? OR u.username ILIKE ?)"
    val escaped = search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    val pattern = s"%$escaped%"
    val args: Seq[Any] = Seq(status.orNull, status.orNull, pattern, pattern, pattern)
    val items = Sql.query(c, userSelect + where.replace("? IS NULL", "CAST(? AS text) IS NULL") + " ORDER BY u.created_at DESC,u.id LIMIT ? OFFSET ?", (args ++ Seq(limit, offset))*)(user).map(_.profile)
    val total = Sql.one(c, "SELECT count(*) FROM app_user u" + where.replace("? IS NULL", "CAST(? AS text) IS NULL"), args*)(_.getLong(1)).get
    UserPage(items, total, offset, limit)
  }

  def setStatus(principal: Principal, targetId: UUID, action: String): Profile = adminTransaction(principal, targetId) { (c, target) =>
    val (from, to, auditAction) = action match
      case "approve" => ("PENDING", "ACTIVE", "ACCOUNT_APPROVED")
      case "reject" => ("PENDING", "REJECTED", "ACCOUNT_REJECTED")
      case "suspend" => ("ACTIVE", "SUSPENDED", "ACCOUNT_SUSPENDED")
      case "reactivate" => ("SUSPENDED", "ACTIVE", "ACCOUNT_REACTIVATED")
      case _ => throw ApiError.invalid("Неизвестное действие")
    if target.profile.id == principal.profile.id then throw ApiError.invalid("Нельзя изменить собственный статус")
    if target.profile.status != from then throw ApiError(409, "STATE_CONFLICT", "Статус аккаунта уже изменился. Обновите список")
    if target.profile.isAdmin && to != "ACTIVE" then
      val count = Sql.one(c, """SELECT count(*) FROM app_user u JOIN user_role r ON r.user_id=u.id
        WHERE u.account_status='ACTIVE' AND r.role_code='PLATFORM_ADMIN'""")(_.getLong(1)).get
      if count <= 1 then throw ApiError.invalid("Нельзя отключить последнего администратора")
    if action == "approve" then
      Sql.update(c, "UPDATE app_user SET account_status=?,approved_at=now(),approved_by_user_id=? WHERE id=?", to, UUID.fromString(principal.profile.id), targetId)
    else Sql.update(c, "UPDATE app_user SET account_status=? WHERE id=?", to, targetId)
    if to != "ACTIVE" then revoke(c, targetId)
    audit(c, Some(UUID.fromString(principal.profile.id)), targetId, auditAction)
    byId(c, targetId).profile
  }

  def issueReset(principal: Principal, targetId: UUID): (UUID, String, String) = adminTransaction(principal, targetId) { (c, target) =>
    if !Set("PENDING", "ACTIVE").contains(target.profile.status) then throw ApiError.invalid("Сначала восстановите доступ аккаунта")
    if target.profile.email.endsWith(".invalid") then throw ApiError.invalid("У системного аккаунта не настроен email для восстановления")
    val raw = OpaqueTokens.generate()
    val id = UUID.randomUUID()
    Sql.update(c, "UPDATE password_reset_token SET revoked_at=now() WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL", targetId)
    Sql.update(c, "INSERT INTO password_reset_token(id,user_id,requested_by_user_id,token_hash,expires_at) VALUES (?,?,?,?,?)",
      id, targetId, UUID.fromString(principal.profile.id), OpaqueTokens.hash(raw), Instant.now().plusSeconds(1800))
    audit(c, Some(UUID.fromString(principal.profile.id)), targetId, "PASSWORD_RESET_ISSUED")
    (id, target.profile.email, raw)
  }
  def revokeReset(id: UUID): Unit = db.connection { c =>
    Sql.update(c, "UPDATE password_reset_token SET revoked_at=now() WHERE id=?", id)
    ()
  }
  def completeReset(rawToken: String, newHash: String): Unit = db.transaction { c =>
    val uid = Sql.one(c, "SELECT user_id FROM password_reset_token WHERE token_hash=?", OpaqueTokens.hash(rawToken))(_.getObject(1, classOf[UUID]))
      .getOrElse(throw ApiError(400, "RESET_INVALID", "Ссылка недействительна или срок её действия истёк"))
    val stored = byId(c, uid, lock = true)
    if !Set("PENDING", "ACTIVE").contains(stored.profile.status) then throw ApiError.forbidden
    val id = Sql.one(c, """SELECT id FROM password_reset_token WHERE token_hash=? AND consumed_at IS NULL
      AND revoked_at IS NULL AND expires_at>now() FOR UPDATE""", OpaqueTokens.hash(rawToken))(_.getObject(1, classOf[UUID]))
      .getOrElse(throw ApiError(400, "RESET_INVALID", "Ссылка недействительна или срок её действия истёк"))
    Sql.update(c, "UPDATE password_reset_token SET consumed_at=now() WHERE id=?", id)
    Sql.update(c, "UPDATE app_user SET password_hash=?,password_change_required=false WHERE id=?", newHash, uid)
    Sql.update(c, "UPDATE password_reset_token SET revoked_at=now() WHERE user_id=? AND id<>? AND consumed_at IS NULL", uid, id)
    revoke(c, uid)
    audit(c, None, uid, "PASSWORD_RESET_COMPLETED")
  }
  def auditLog(): List[AuditEntry] = db.connection { c =>
    Sql.query(c, "SELECT * FROM admin_audit_log ORDER BY occurred_at DESC,id DESC LIMIT 100") { r =>
      AuditEntry(r.getObject("id").toString, Option(r.getObject("actor_user_id")).map(_.toString), Option(r.getObject("target_user_id")).map(_.toString), r.getString("action"), r.getTimestamp("occurred_at").toInstant.toString)
    }
  }
  def ready(): Unit = db.connection { c =>
    Sql.one(c, "SELECT count(*) FROM system_bootstrap")(_.getLong(1))
    ()
  }

  def households(principal: Principal): List[HouseholdSummary] = db.connection { c =>
    AccessPolicy.workspace(principal.profile)
    Sql.query(c, """SELECT h.id,h.name,h.timezone,m.role,
      (SELECT count(*) FROM membership members WHERE members.household_id=h.id) AS member_count
      FROM household h JOIN membership m ON m.household_id=h.id WHERE m.user_id=? ORDER BY h.created_at""",
      UUID.fromString(principal.profile.id)) { r =>
      HouseholdSummary(r.getObject("id").toString, r.getString("name"), r.getString("timezone"), r.getString("role"), r.getLong("member_count"))
    }
  }
  def createHousehold(principal: Principal, input: HouseholdInput): HouseholdSummary = db.transaction { c =>
    val uid = UUID.fromString(principal.profile.id)
    byId(c, uid, lock = true)
    AccessPolicy.workspace(current(c, uid, principal.sessionId).profile)
    val name = input.name.trim
    if name.isEmpty || name.length > 100 then throw ApiError.invalid("Название должно содержать от 1 до 100 символов")
    try java.time.ZoneId.of(input.timezone) catch case _: Exception => throw ApiError.invalid("Некорректный часовой пояс")
    val id = UUID.randomUUID()
    Sql.update(c, "INSERT INTO household(id,name,timezone) VALUES (?,?,?)", id, name, input.timezone)
    Sql.update(c, "INSERT INTO membership(household_id,user_id,role) VALUES (?,?,'OWNER')", id, uid)
    HouseholdSummary(id.toString, name, input.timezone, "OWNER", 1)
  }
