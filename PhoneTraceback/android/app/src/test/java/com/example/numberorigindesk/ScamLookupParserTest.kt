package com.example.numberorigindesk

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class ScamLookupParserTest {
    @Test
    fun parsesAssignmentAndPublicReportSummary() {
        val document = Jsoup.parse(
            """
            <table>
              <tr><th>Country</th><td>United States</td></tr>
              <tr><th>Location</th><td>DISTRICT OF COLUMBIA</td></tr>
            </table>
            <h2>Is 202-555-0123 a scam or spam call?</h2>
            <div><p><strong>7</strong> consumers have reported this number to the FTC. Roughly 28% flagged it as a robocall.</p></div>
            """.trimIndent(),
        )

        val result = parseScamLookupDocument(document, "https://www.reportedcalls.com/2025550123", 1234L)

        assertEquals("United States", result.country)
        assertEquals("DISTRICT OF COLUMBIA", result.location)
        assertEquals("7 consumers have reported this number to the FTC. Roughly 28% flagged it as a robocall.", result.reportSummary)
        assertEquals("https://www.reportedcalls.com/2025550123", result.sourceUrl)
        assertEquals(1234L, result.retrievedAtMillis)
    }
}