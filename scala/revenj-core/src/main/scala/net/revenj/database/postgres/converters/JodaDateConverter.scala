package net.revenj.database.postgres.converters

import java.sql.PreparedStatement

import net.revenj.database.postgres.{PostgresBuffer, PostgresReader, PostgresWriter}
import org.joda.time.LocalDate
import org.postgresql.util.PGobject

//TODO: this will be removed eventually
object JodaDateConverter extends Converter[LocalDate] {

  val MIN_DATE = new LocalDate(1, 1, 1)

  override def serializeURI(sw: PostgresBuffer, value: LocalDate): Unit = {
    serialize(sw.tempBuffer, 0, value)
    sw.addToBuffer(sw.tempBuffer, 10)
  }

  private def serialize(buf: Array[Char], start: Int, value: LocalDate): Unit = {
    val year = value.getYear
    if (year > 9999) throw new IllegalArgumentException(s"Invalid year detected: $value. Only dates up to 9999-12-31 are allowed")
    NumberConverter.write4(year, buf, start)
    buf(start + 4) = '-'
    NumberConverter.write2(value.getMonthOfYear, buf, start + 5)
    buf(start + 7) = '-'
    NumberConverter.write2(value.getDayOfMonth, buf, start + 8)
  }

  def setParameter(sw: PostgresBuffer, ps: PreparedStatement, index: Int, value: LocalDate): Unit = {
    val pg = new PGobject
    pg.setType("date")
    val buf = sw.tempBuffer
    serialize(buf, 0, value)
    pg.setValue(new String(buf, 0, 10))
    ps.setObject(index, pg)
  }

  val dbName = "date"

  def default() = MIN_DATE

  override def parseRaw(reader: PostgresReader, start: Int, context: Int): LocalDate = parseDate(reader, start)

  private def parseDate(reader: PostgresReader, cur: Int): LocalDate = {
    if (cur == '\\' || cur == '"') {
      throw new RuntimeException("Negative dates are not yet implemented.")
    }
    val buf = reader.tmp
    buf(0) = cur.toChar
    reader.fillTotal(buf, 1, 9)
    if (buf(4) != '-') {
      parseDateSlow(buf, reader)
    } else {
      reader.read()
      new LocalDate(NumberConverter.read4(buf, 0), NumberConverter.read2(buf, 5), NumberConverter.read2(buf, 8))
    }
  }

  private def parseDateSlow(buf: Array[Char], reader: PostgresReader): LocalDate = {
    var foundAt = 4
    while (foundAt < buf.length && buf(foundAt) != '-') {
      foundAt += 1
    }
    if (foundAt == buf.length) {
      throw new RuntimeException("Invalid date value.")
    }
    val year = NumberConverter.parsePositive(buf, 0, foundAt)
    val newBuf = reader.tmp
    var i = foundAt + 1
    while (i < buf.length) {
      newBuf(i - foundAt - 1) = buf(i)
      i += 1
    }
    i = buf.length - foundAt - 1
    while (i < 5) {
      newBuf(i) = reader.read().toChar
      i += 1
    }
    reader.read()
    new LocalDate(year, NumberConverter.read2(newBuf, 0), NumberConverter.read2(newBuf, 3))
  }

  override def parseCollectionItem(reader: PostgresReader, context: Int): LocalDate = {
    val cur = reader.read()
    if (cur == 'N') {
      reader.read(4)
      MIN_DATE
    } else {
      parseDate(reader, cur)
    }
  }

  override def parseNullableCollectionItem(reader: PostgresReader, context: Int): Option[LocalDate] = {
    val cur = reader.read()
    if (cur == 'N') {
      reader.read(4)
      None
    } else {
      Some(parseDate(reader, cur))
    }
  }

  override def toTuple(value: LocalDate): PostgresTuple = {
    new LocalDateTuple(value)
  }

  private class LocalDateTuple(val value: LocalDate) extends PostgresTuple {
    val mustEscapeRecord = false

    val mustEscapeArray = false

    def insertRecord(sw: PostgresWriter, escaping: String, mappings: Option[(PostgresWriter, Char) => Unit]): Unit = {
      serialize(sw.tmp, 0, value)
      sw.writeBuffer(10)
    }

    override def buildTuple(quote: Boolean): String = {
      if (quote) {
        val buf = new Array[Char](12)
        buf(0) = '\''
        serialize(buf, 1, value)
        buf(11) = '\''
        new String(buf, 0, 12)
      } else {
        val buf = new Array[Char](10)
        serialize(buf, 0, value)
        new String(buf, 0, 10)
      }
    }
  }

}
