package com.example.numberorigindesk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

class InvestigationPdfTest {
    @Test
    fun formatsSavedInvestigationForReadableExport() {
        val report = JSONObject()
            .put("createdAtUtc", "2026-07-29T20:00:00Z")
            .put("number", "+12025550123")
            .put("formattedInternational", "+1 202-555-0123")
            .put("numberingRegion", "United States")
            .put("countryCode", "US")
            .put("numberType", "FIXED_LINE_OR_MOBILE")
            .put("numberingAssignment", JSONObject().put("callingCode", "+1").put("areaCode", "202"))
            .put(
                "authenticity",
                JSONObject()
                    .put("status", "unverified")
                    .put("summary", "Caller identity is not verified.")
                    .put("indicators", JSONArray().put("Caller ID can be spoofed.")),
            )
            .put(
                "scamPhoneReport",
                JSONObject()
                    .put("country", "United States")
                    .put("location", "DISTRICT OF COLUMBIA")
                    .put("retrievedAtUtc", "2026-07-29T20:01:00Z")
                    .put("sourceUrl", "https://www.reportedcalls.com/2025550123")
                    .put("reportSummary", "7 consumers reported this number."),
            )
            .put("callLogEvidence", JSONObject().put("matchingCalls", 2).put("records", JSONArray()))
            .put("disclaimer", "Not a live caller location.")

        val text = formatInvestigationReport(report, 1).joinToString("\n")

        assertTrue(text.contains("Investigation 1"))
        assertTrue(text.contains("+1 202-555-0123"))
        assertTrue(text.contains("Scam Phone report:"))
        assertTrue(text.contains("7 consumers reported this number."))
        assertTrue(text.contains("Matching calls: 2"))
        assertTrue(text.contains("Not a live caller location."))
    }
}