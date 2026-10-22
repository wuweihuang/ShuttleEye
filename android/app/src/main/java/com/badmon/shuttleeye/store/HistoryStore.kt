package com.badmon.shuttleeye.store

import android.content.Context
import android.content.SharedPreferences
import com.badmon.shuttleeye.core.Point2
import com.badmon.shuttleeye.core.ScaleCalibration
import com.badmon.shuttleeye.core.Size2
import com.badmon.shuttleeye.core.TrackPoint
import com.badmon.shuttleeye.model.SessionRecord
import org.json.JSONArray
import org.json.JSONObject

/** 历史记录存储（SharedPreferences / JSON，对应 iOS HistoryStore 的 Documents/history.json） */
class HistoryStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): List<SessionRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return try {
            parse(JSONArray(raw))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(record: SessionRecord) {
        val list = ArrayList(load())
        list.add(0, record)
        persist(list)
    }

    fun delete(record: SessionRecord) {
        persist(load().filter { it.id != record.id })
    }

    private fun persist(list: List<SessionRecord>) {
        val arr = JSONArray()
        for (r in list) arr.put(toJson(r))
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    // ---- JSON 映射 ----

    private fun toJson(r: SessionRecord): JSONObject = JSONObject().apply {
        put("id", r.id)
        put("date", r.dateMillis)
        put("source", r.source)
        put("fps", r.fps)
        put("peakKmh", r.peakKmh)
        put("uncertaintyKmh", r.uncertaintyKmh)
        put("smoothedPeakKmh", r.smoothedPeakKmh)
        r.radarEquivalentKmh?.let { put("radar", it) }
        r.dragCoeffK?.let { put("k", it) }
        r.r2?.let { put("r2", it) }
        put("physicsOk", r.physicsConsistent)
        put("calA", JSONArray().put(r.calibration.pixelA.x).put(r.calibration.pixelA.y))
        put("calB", JSONArray().put(r.calibration.pixelB.x).put(r.calibration.pixelB.y))
        put("calM", r.calibration.realDistanceMeters)
        val pts = JSONArray()
        for (p in r.points) {
            pts.put(
                JSONObject()
                    .put("f", p.frameIndex)
                    .put("t", p.time)
                    .put("x", p.center.x)
                    .put("y", p.center.y)
                    .put("c", p.confidence)
                    .put("bw", p.bboxSize.width)
                    .put("bh", p.bboxSize.height)
                    .put("blur", p.blurLength)
                    .put("ip", p.isInterpolated)
            )
        }
        put("points", pts)
        val curve = JSONArray()
        for (row in r.speedCurve) curve.put(JSONArray(row))
        put("curve", curve)
    }

    private fun parse(arr: JSONArray): List<SessionRecord> {
        val out = ArrayList<SessionRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pts = ArrayList<TrackPoint>()
            val pArr = o.optJSONArray("points") ?: JSONArray()
            for (j in 0 until pArr.length()) {
                val p = pArr.getJSONObject(j)
                pts.add(
                    TrackPoint(
                        frameIndex = p.getInt("f"),
                        time = p.getDouble("t"),
                        center = Point2(p.getDouble("x"), p.getDouble("y")),
                        confidence = p.getDouble("c"),
                        bboxSize = Size2(p.getDouble("bw"), p.getDouble("bh")),
                        blurLength = p.optDouble("blur", 0.0),
                        isInterpolated = p.optBoolean("ip", false)
                    )
                )
            }
            val curve = ArrayList<List<Double>>()
            val cArr = o.optJSONArray("curve") ?: JSONArray()
            for (j in 0 until cArr.length()) {
                val row = cArr.getJSONArray(j)
                curve.add(listOf(row.getDouble(0), row.getDouble(1)))
            }
            val a = o.getJSONArray("calA")
            val b = o.getJSONArray("calB")
            out.add(
                SessionRecord(
                    id = o.getString("id"),
                    dateMillis = o.getLong("date"),
                    source = o.getString("source"),
                    fps = o.getDouble("fps"),
                    peakKmh = o.getDouble("peakKmh"),
                    uncertaintyKmh = o.getDouble("uncertaintyKmh"),
                    smoothedPeakKmh = o.getDouble("smoothedPeakKmh"),
                    radarEquivalentKmh = o.optDouble("radar", Double.NaN).takeUnless { it.isNaN() },
                    dragCoeffK = o.optDouble("k", Double.NaN).takeUnless { it.isNaN() },
                    r2 = o.optDouble("r2", Double.NaN).takeUnless { it.isNaN() },
                    physicsConsistent = o.optBoolean("physicsOk", false),
                    calibration = ScaleCalibration(
                        pixelA = Point2(a.getDouble(0), a.getDouble(1)),
                        pixelB = Point2(b.getDouble(0), b.getDouble(1)),
                        realDistanceMeters = o.getDouble("calM")
                    ),
                    points = pts,
                    speedCurve = curve
                )
            )
        }
        return out.sortedByDescending { it.dateMillis }
    }

    companion object {
        private const val PREFS_NAME = "shuttleeye_history"
        private const val KEY_RECORDS = "records_json"
    }
}
