package app.householdplanner.auth

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.sql.{Connection, PreparedStatement, ResultSet, Timestamp}
import java.time.Instant
import java.util.UUID
import scala.util.Using

final class Database(val source: HikariDataSource) extends AutoCloseable:
  def connection[A](f: Connection => A): A = Using.resource(source.getConnection)(f)
  def transaction[A](f: Connection => A): A = connection { c =>
    c.setAutoCommit(false)
    try
      val result = f(c)
      c.commit()
      result
    catch
      case error: Throwable =>
        try c.rollback() catch case _: Throwable => ()
        throw error
  }
  def close(): Unit = source.close()

object Database:
  def open(config: AppConfig): Database =
    val pool = new HikariConfig()
    pool.setJdbcUrl(config.dbUrl)
    pool.setUsername(config.dbUser)
    pool.setPassword(config.dbPassword)
    pool.setMaximumPoolSize(config.poolSize)
    pool.setConnectionTimeout(10000)
    pool.setPoolName("household-planner")
    new Database(new HikariDataSource(pool))

object Sql:
  private def bind(statement: PreparedStatement, args: Seq[Any]): Unit =
    args.zipWithIndex.foreach { (value, offset) =>
      val index = offset + 1
      value match
        case null => statement.setObject(index, null)
        case v: UUID => statement.setObject(index, v)
        case v: Instant => statement.setTimestamp(index, Timestamp.from(v))
        case v: Array[Byte] => statement.setBytes(index, v)
        case v => statement.setObject(index, v)
    }
  def query[A](c: Connection, sql: String, args: Any*)(row: ResultSet => A): List[A] =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, args)
      Using.resource(statement.executeQuery()) { result =>
        val rows = List.newBuilder[A]
        while result.next() do rows += row(result)
        rows.result()
      }
    }
  def one[A](c: Connection, sql: String, args: Any*)(row: ResultSet => A): Option[A] = query(c, sql, args*)(row).headOption
  def update(c: Connection, sql: String, args: Any*): Int =
    Using.resource(c.prepareStatement(sql)) { statement =>
      bind(statement, args)
      statement.executeUpdate()
    }
