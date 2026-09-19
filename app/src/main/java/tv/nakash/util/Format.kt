package tv.nakash.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")
fun fmtTime(epochSec: Long): String = HM.format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.systemDefault()))
fun fmtDuration(sec: Long): String { val h = sec / 3600; val m = sec % 3600 / 60; val s = sec % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s) }
/** Wrap Latin/number runs so they don't flip inside Hebrew sentences. */
fun isolate(s: String) = "\u2068$s\u2069"
