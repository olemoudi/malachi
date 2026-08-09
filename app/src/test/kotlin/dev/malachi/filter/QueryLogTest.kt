package dev.malachi.filter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class QueryLogTest {

    private fun record(
        domain: String,
        packageName: String? = null,
        blocked: Boolean = false,
        count: Int = 1,
        lastSeenMs: Long = 0,
    ) = QueryRecord(
        domain = domain,
        packageName = packageName,
        blocked = blocked,
        source = if (blocked) RuleSource.LIST else RuleSource.NONE,
        detail = "",
        count = count,
        lastSeenMs = lastSeenMs,
    )

    @Test
    fun `the same pair is counted, not repeated`() {
        // An app resolving one tracker forty times is one fact; forty lines of it would push
        // the domain you were looking for off the end of the list.
        var records = emptyList<QueryRecord>()
        repeat(3) { i ->
            records = QueryLogState.merge(
                records,
                record("ads.example.com", "com.example.game", lastSeenMs = i.toLong()),
                max = 10,
            )
        }
        assertEquals(1, records.size)
        assertEquals(3, records.single().count)
        assertEquals(2L, records.single().lastSeenMs)
    }

    @Test
    fun `the same domain in two apps stays two rows`() {
        var records = QueryLogState.merge(emptyList(), record("ads.example.com", "com.a"), max = 10)
        records = QueryLogState.merge(records, record("ads.example.com", "com.b"), max = 10)
        assertEquals(2, records.size)
    }

    @Test
    fun `the newest verdict replaces the old one`() {
        // A domain the user has just allowed must stop reading as blocked, while the history of
        // how often it was seen is kept.
        var records = QueryLogState.merge(
            emptyList(),
            record("ads.example.com", "com.a", blocked = true),
            max = 10,
        )
        records = QueryLogState.merge(records, record("ads.example.com", "com.a", blocked = false), max = 10)
        assertEquals(1, records.size)
        assertFalse(records.single().blocked)
        assertEquals(2, records.single().count)
    }

    @Test
    fun `a merged row moves to the front`() {
        var records = QueryLogState.merge(emptyList(), record("old.example.com", "com.a"), max = 10)
        records = QueryLogState.merge(records, record("new.example.com", "com.a"), max = 10)
        records = QueryLogState.merge(records, record("old.example.com", "com.a"), max = 10)
        assertEquals("old.example.com", records.first().domain)
    }

    @Test
    fun `the cap drops the oldest rows`() {
        var records = emptyList<QueryRecord>()
        repeat(5) { i -> records = QueryLogState.merge(records, record("d$i.example.com"), max = 3) }
        assertEquals(3, records.size)
        assertEquals("d4.example.com", records.first().domain)
        assertTrue(records.none { it.domain == "d0.example.com" })
    }

    @Test
    fun `percentages are of what was actually seen`() {
        assertEquals(0, QueryLogState().blockedPercent)
        assertEquals(25, QueryLogState(blocked = 1, total = 4).blockedPercent)
        assertEquals(3, QueryLogState(blocked = 1, total = 4).allowed.toInt())
    }

    @Test
    fun `grouping by app puts the most recent app first`() {
        val state = QueryLogState(
            records = listOf(
                record("a.example.com", "com.old", lastSeenMs = 10),
                record("b.example.com", "com.new", lastSeenMs = 90),
                record("c.example.com", "com.old", lastSeenMs = 20),
            ),
        )
        val grouped = state.byApp()
        assertEquals("com.new", grouped.first().first)
        assertEquals(2, grouped.first { it.first == "com.old" }.second.size)
        // Within a group, newest first too.
        assertEquals("c.example.com", grouped.first { it.first == "com.old" }.second.first().domain)
    }

    @Test
    fun `an unattributed lookup groups on its own`() {
        val state = QueryLogState(
            records = listOf(record("a.example.com", null, lastSeenMs = 1), record("b.example.com", "com.a", lastSeenMs = 2)),
        )
        assertTrue(state.byApp().any { it.first == null })
    }
}
