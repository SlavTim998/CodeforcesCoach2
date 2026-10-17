package com.example.cfcoach

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Public endpoints only; no API key is collected or stored.
data class Contest(val id: Int, val name: String, val phase: String, val start: Long)
data class Member(val handle: String, val rating: Int, val maxRating: Int, val rank: String)
data class Task(val contestId: Int, val index: String, val name: String, val rating: Int, val tags: List<String>, val solvedCount: Int) {
    val url get() = "https://codeforces.com/problemset/problem/$contestId/$index"
}
data class Submission(val contestId: Int, val index: String, val verdict: String, val tags: List<String>, val rating: Int)
data class RatingChange(val contestId: Int, val name: String, val old: Int, val new: Int, val time: Long)
data class Profile(val handle: String, val rating: Int, val maxRating: Int, val rank: String)
data class Snapshot(val profile: Profile, val contests: List<Contest>, val members: List<Member>, val submissions: List<Submission>, val changes: List<RatingChange>, val tasks: List<Task>, val analysis: Analysis)
data class Analysis(val solved: Int, val attempts: Int, val successRate: Int, val weakTags: List<Pair<String, Int>>, val targetRating: Int, val recommendations: List<Task>, val advice: List<String>)

object CfApi {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private suspend fun get(method: String, query: String = ""): JSONObject = withContext(Dispatchers.IO) {
        val url = URL("https://codeforces.com/api/$method" + if (query.isBlank()) "" else "?$query")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 12000; readTimeout = 18000
            setRequestProperty("User-Agent", "CFCoachAndroid/1.0")
        }
        try {
            val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream).bufferedReader().use { it.readText() }
            val json = JSONObject(text)
            if (conn.responseCode !in 200..299 || json.optString("status") != "OK") {
                error(json.optString("comment", "Codeforces HTTP ${conn.responseCode}"))
            }
            json
        } finally { conn.disconnect() }
    }
    suspend fun contests(): List<Contest> = get("contest.list").getJSONArray("result").toObjects { o ->
        Contest(o.getInt("id"), o.getString("name"), o.optString("phase"), o.optLong("startTimeSeconds"))
    }
    suspend fun profile(handle: String): Profile {
        val o = get("user.info", "handles=${enc(handle)}").getJSONArray("result").getJSONObject(0)
        return Profile(o.getString("handle"), o.optInt("rating", 0), o.optInt("maxRating", 0), o.optString("rank", "unrated"))
    }
    suspend fun members(handles: List<String>): List<Member> {
        if (handles.isEmpty()) return emptyList()
        return handles.chunked(50).flatMap { chunk ->
            get("user.info", "handles=${enc(chunk.joinToString(";"))}").getJSONArray("result").toObjects { o ->
                Member(o.getString("handle"), o.optInt("rating", 0), o.optInt("maxRating", 0), o.optString("rank", "unrated"))
            }
        }.sortedByDescending { it.rating }
    }
    suspend fun submissions(handle: String): List<Submission> = get("user.status", "handle=${enc(handle)}&from=1&count=1000")
        .getJSONArray("result").toObjects { o ->
            val p = o.getJSONObject("problem")
            Submission(p.optInt("contestId", 0), p.optString("index"), o.optString("verdict", "UNKNOWN"), p.optJSONArray("tags")?.toStrings() ?: emptyList(), p.optInt("rating", 0))
        }
    suspend fun ratings(handle: String): List<RatingChange> = get("user.rating", "handle=${enc(handle)}")
        .getJSONArray("result").toObjects { o ->
            RatingChange(o.getInt("contestId"), o.optString("contestName"), o.optInt("oldRating"), o.optInt("newRating"), o.optLong("ratingUpdateTimeSeconds"))
        }
    suspend fun problems(): List<Task> {
        val r = get("problemset.problems").getJSONObject("result")
        val stats = mutableMapOf<String, Int>()
        r.getJSONArray("problemStatistics").toObjects { o ->
            val key = "${o.optInt("contestId")}:${o.optString("index")}"; stats[key] = o.optInt("solvedCount")
        }
        return r.getJSONArray("problems").toObjects { o ->
            val id = o.optInt("contestId", 0); val index = o.optString("index")
            Task(id, index, o.optString("name"), o.optInt("rating", 0), o.optJSONArray("tags")?.toStrings() ?: emptyList(), stats["$id:$index"] ?: 0)
        }
    }
}
private fun <T> JSONArray.toObjects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }
private fun JSONArray.toStrings(): List<String> = (0 until length()).map { getString(it) }

object Coach {
    fun analyze(profile: Profile, submissions: List<Submission>, tasks: List<Task>): Analysis {
        fun key(id: Int, index: String) = "$id:$index"
        val byProblem = submissions.filter { it.contestId > 0 && it.index.isNotBlank() }.groupBy { key(it.contestId, it.index) }
        val solvedKeys = byProblem.filterValues { attempts -> attempts.any { it.verdict == "OK" } }.keys
        val failed = submissions.filter { it.verdict != "OK" && it.verdict != "TESTING" && it.verdict != "UNKNOWN" }
        val tagAttempts = mutableMapOf<String, Int>(); val tagSolved = mutableMapOf<String, Int>()
        byProblem.forEach { (_, ss) ->
            val tags = ss.first().tags
            tags.forEach { t ->
                tagAttempts[t] = (tagAttempts[t] ?: 0) + 1
                if (ss.any { it.verdict == "OK" }) tagSolved[t] = (tagSolved[t] ?: 0) + 1
            }
        }
        val weak = tagAttempts.filterValues { it >= 3 }.map { (tag, n) -> tag to (((n - (tagSolved[tag] ?: 0)) * 100) / n) }
            .sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenByDescending { tagAttempts[it.first] ?: 0 }).take(5)
        val target = (if (profile.rating == 0) 900 else profile.rating + 100).coerceIn(800, 3500)
        val preferred = weak.map { it.first }.toSet()
        val recentContests = submissions.map { it.contestId }.toSet()
        val candidates = tasks.asSequence().filter { it.contestId > 0 && it.rating in (target - 200)..(target + 100) && key(it.contestId, it.index) !in solvedKeys }
            .sortedWith(compareByDescending<Task> { it.tags.count(preferred::contains) }.thenByDescending { it.solvedCount })
            .filter { it.contestId !in recentContests || it.solvedCount > 0 }.take(12).toList()
        val advice = buildList {
            add("Цель: задачи рейтинга ${target - 200}–${target + 100}; решай 2–3 в день, затем разбирай редакториалы.")
            if (weak.isNotEmpty()) add("Приоритетные темы: ${weak.take(3).joinToString { it.first }}. Процент неудач рассчитан по последним 1000 посылкам.")
            if (failed.size > submissions.size / 2) add("Много неуспешных посылок: добавь тесты на граничные случаи перед отправкой.")
            add("После каждого соревнования дорешай 1–2 задачи, которые не успел решить во время раунда.")
        }
        return Analysis(solvedKeys.size, byProblem.size, if (byProblem.isEmpty()) 0 else solvedKeys.size * 100 / byProblem.size, weak, target, candidates, advice)
    }
}
