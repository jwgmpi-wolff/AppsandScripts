package com.example.numberorigindesk

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import io.michaelrocks.libphonenumber.android.PhoneNumberUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Ink = Color(0xFF17211B)
private val Paper = Color(0xFFF4F1E9)
private val Panel = Color(0xFFFFFDF8)
private val Accent = Color(0xFF146C4F)
private val Muted = Color(0xFF68736D)
private val Warning = Color(0xFFFFF5D9)

data class SearchLink(val title: String, val url: String, val inAppScamLookup: Boolean = false)
data class Investigation(
    val status: String,
    val summary: String,
    val indicators: List<String>,
    val publicSearches: List<SearchLink>,
)

data class CallLogSummary(
    val matches: Int,
    val incoming: Int,
    val outgoing: Int,
    val missed: Int,
    val blockedOrRejected: Int,
    val totalDurationSeconds: Long,
    val lastSeenMillis: Long?,
    val records: List<CallEvidence>,
)

data class CallEvidence(
    val timestampMillis: Long,
    val disposition: String,
    val durationSeconds: Long,
    val presentation: String,
    val phoneAccountId: String,
)

data class SavedInvestigation(
    val id: String,
    val number: String,
    val region: String,
    val authenticity: String,
    val createdAtMillis: Long,
    val evidenceCount: Int,
    val hasScamReport: Boolean,
)

data class LookupResult(
    val canonicalNumber: String,
    val formattedInternational: String,
    val callingCode: String,
    val countryCode: String,
    val region: String,
    val numberType: String,
    val disclaimer: String,
    val areaCode: String?,
    val investigation: Investigation,
)

data class ScamLookupResult(
    val country: String,
    val location: String,
    val reportSummary: String,
    val sourceUrl: String,
    val retrievedAtMillis: Long,
)

internal fun parseScamLookupDocument(
    document: org.jsoup.nodes.Document,
    sourceUrl: String,
    retrievedAtMillis: Long,
): ScamLookupResult {
    val assignment = document.select("table tr").mapNotNull { row ->
        val label = row.selectFirst("th")?.text()?.trim()?.lowercase() ?: return@mapNotNull null
        val value = row.selectFirst("td")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        label to value
    }.toMap()
    val reportSummary = document.select("h2")
        .firstOrNull { it.text().startsWith("Is ", ignoreCase = true) && it.text().contains("scam", ignoreCase = true) }
        ?.nextElementSibling()
        ?.selectFirst("p")
        ?.text()
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: error("Scam Phone did not return a report summary for this number.")

    return ScamLookupResult(
        country = assignment["country"] ?: "Not reported",
        location = assignment["location"] ?: "Not reported",
        reportSummary = reportSummary,
        sourceUrl = sourceUrl,
        retrievedAtMillis = retrievedAtMillis,
    )
}

sealed interface LookupState {
    data object Idle : LookupState
    data object Loading : LookupState
    data class Success(val result: LookupResult) : LookupState
    data class Failure(val message: String) : LookupState
}

sealed interface ScamLookupState {
    data object Idle : ScamLookupState
    data object Loading : ScamLookupState
    data class Success(val result: ScamLookupResult) : ScamLookupState
    data class Failure(val message: String) : ScamLookupState
}

class MainActivity : ComponentActivity() {
    private lateinit var phoneNumberUtil: PhoneNumberUtil
    private val investigationStore by lazy { InvestigationStore(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        phoneNumberUtil = PhoneNumberUtil.createInstance(this)
        setContent {
            MaterialTheme {
                NumberOriginScreen(
                    validate = ::validateNumber,
                    lookup = ::lookupNumber,
                    lookupScamReports = ::lookupScamReports,
                    loadCallHistory = ::loadCallHistory,
                    saveInvestigation = investigationStore::save,
                    loadSavedInvestigations = investigationStore::list,
                    exportInvestigations = investigationStore::exportPdf,
                    openUrl = ::openUrl,
                )
            }
        }
    }

    private fun validateNumber(value: String): Boolean = runCatching {
        phoneNumberUtil.isValidNumber(phoneNumberUtil.parse(value, null))
    }.getOrDefault(false)

    private fun lookupNumber(value: String): LookupResult {
        val phone = phoneNumberUtil.parse(value, null)
        require(phoneNumberUtil.isValidNumber(phone)) { "Enter a valid number with its country calling code." }

        val canonicalNumber = phoneNumberUtil.format(phone, PhoneNumberUtil.PhoneNumberFormat.E164)
        val countryCode = phoneNumberUtil.getRegionCodeForNumber(phone).orEmpty()
        val region = countryCode.takeIf { it.length == 2 }
            ?.let { Locale("", it).displayCountry }
            ?.takeIf { it.isNotBlank() }
            ?: countryCode.ifBlank { "Non-geographic numbering plan" }
        val areaCode = if (phone.countryCode == 1) phone.nationalNumber.toString().take(3) else null
        val encodedQuery = Uri.encode("\"$canonicalNumber\" spam scam")

        return LookupResult(
            canonicalNumber = canonicalNumber,
            formattedInternational = phoneNumberUtil.format(phone, PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL),
            callingCode = "+${phone.countryCode}",
            countryCode = countryCode.ifBlank { "N/A" },
            region = region,
            numberType = phoneNumberUtil.getNumberType(phone).name,
            disclaimer = "Numbering-plan assignment only. Not a live caller, device, or network location. Caller ID may be spoofed.",
            areaCode = areaCode,
            investigation = Investigation(
                status = "unverified",
                summary = "Number validity and numbering assignment do not verify the identity or network origin of the caller.",
                indicators = listOf(
                    "Caller ID can be spoofed even when the displayed number is valid.",
                    "Only the originating and terminating providers can perform an authoritative call traceback.",
                    "STIR/SHAKEN attestation, when exposed by a provider, is stronger evidence than caller ID but is not proof of identity.",
                ),
                publicSearches = listOf(
                    SearchLink("Live Google abuse-report search", "https://www.google.com/search?q=$encodedQuery"),
                    SearchLink("Live Bing abuse-report search", "https://www.bing.com/search?q=$encodedQuery", inAppScamLookup = true),
                    SearchLink("Current NANPA area-code reports", "https://www.nanpa.com/reports/reports-npa"),
                    SearchLink("File an FCC complaint", "https://consumercomplaints.fcc.gov/hc/en-us"),
                    SearchLink("Report fraud to the FTC", "https://reportfraud.ftc.gov/"),
                ),
            ),
        )
    }

    private suspend fun lookupScamReports(number: String): ScamLookupResult = withContext(Dispatchers.IO) {
        val phone = phoneNumberUtil.parse(number, null)
        val nationalNumber = phone.nationalNumber.toString()
        require(phone.countryCode == 1 && nationalNumber.length == 10) {
            "The Scam Phone lookup currently supports ten-digit NANP numbers only."
        }

        val sourceUrl = "https://www.reportedcalls.com/$nationalNumber"
        val document = Jsoup.connect(sourceUrl)
            .userAgent("NumberOriginDesk/1.0")
            .timeout(10_000)
            .maxBodySize(1_000_000)
            .get()
        parseScamLookupDocument(document, sourceUrl, System.currentTimeMillis())
    }

    private fun openUrl(url: String): Boolean {
        val browserPackages = listOf("com.android.chrome", "com.sec.android.app.sbrowser", "com.microsoft.emmx")
        val baseIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        browserPackages.forEach { packageName ->
            if (runCatching { startActivity(Intent(baseIntent).setPackage(packageName)) }.isSuccess) return true
        }
        return runCatching { startActivity(baseIntent) }.isSuccess
    }

    private suspend fun loadCallHistory(number: String): CallLogSummary = withContext(Dispatchers.IO) {
        val target = phoneNumberUtil.parse(number, null)
        val defaultRegion = phoneNumberUtil.getRegionCodeForNumber(target)
        val columns = arrayOf(
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.NUMBER_PRESENTATION,
            CallLog.Calls.PHONE_ACCOUNT_ID,
        )
        var matches = 0
        var incoming = 0
        var outgoing = 0
        var missed = 0
        var blockedOrRejected = 0
        var totalDurationSeconds = 0L
        var lastSeenMillis: Long? = null
        var scanned = 0
        val records = mutableListOf<CallEvidence>()

        contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            columns,
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
            val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
            val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
            val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
            val presentationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER_PRESENTATION)
            val phoneAccountIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (cursor.moveToNext() && scanned < 500) {
                scanned += 1
                val candidate = cursor.getString(numberIndex) ?: continue
                val isMatch = runCatching {
                    val parsedCandidate = phoneNumberUtil.parse(candidate, defaultRegion)
                    phoneNumberUtil.isNumberMatch(target, parsedCandidate) != PhoneNumberUtil.MatchType.NO_MATCH
                }.getOrDefault(false)
                if (!isMatch) continue

                matches += 1
                val date = cursor.getLong(dateIndex)
                val type = cursor.getInt(typeIndex)
                val duration = cursor.getLong(durationIndex)
                if (lastSeenMillis == null || date > lastSeenMillis!!) lastSeenMillis = date
                totalDurationSeconds += duration
                when (type) {
                    CallLog.Calls.INCOMING_TYPE -> incoming += 1
                    CallLog.Calls.OUTGOING_TYPE -> outgoing += 1
                    CallLog.Calls.MISSED_TYPE -> missed += 1
                    CallLog.Calls.BLOCKED_TYPE, CallLog.Calls.REJECTED_TYPE -> blockedOrRejected += 1
                }
                if (records.size < 20) {
                    records += CallEvidence(
                        timestampMillis = date,
                        disposition = callDisposition(type),
                        durationSeconds = duration,
                        presentation = numberPresentation(cursor.getInt(presentationIndex)),
                        phoneAccountId = cursor.getString(phoneAccountIndex)?.takeIf { it.isNotBlank() } ?: "Unknown account",
                    )
                }
            }
        }

        CallLogSummary(matches, incoming, outgoing, missed, blockedOrRejected, totalDurationSeconds, lastSeenMillis, records)
    }

    private fun callDisposition(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "Voicemail"
        else -> "Other"
    }

    private fun numberPresentation(presentation: Int): String = when (presentation) {
        CallLog.Calls.PRESENTATION_ALLOWED -> "Allowed"
        CallLog.Calls.PRESENTATION_RESTRICTED -> "Restricted"
        CallLog.Calls.PRESENTATION_PAYPHONE -> "Payphone"
        CallLog.Calls.PRESENTATION_UNKNOWN -> "Unknown"
        else -> "Unspecified"
    }
}

@Composable
private fun NumberOriginScreen(
    validate: (String) -> Boolean,
    lookup: (String) -> LookupResult,
    lookupScamReports: suspend (String) -> ScamLookupResult,
    loadCallHistory: suspend (String) -> CallLogSummary,
    saveInvestigation: suspend (LookupResult, CallLogSummary?, ScamLookupResult?) -> SavedInvestigation,
    loadSavedInvestigations: suspend () -> List<SavedInvestigation>,
    exportInvestigations: suspend (Uri) -> Unit,
    openUrl: (String) -> Boolean,
) {
    var number by remember { mutableStateOf("") }
    var state: LookupState by remember { mutableStateOf(LookupState.Idle) }
    var callLogSummary by remember { mutableStateOf<CallLogSummary?>(null) }
    var callLogMessage by remember { mutableStateOf<String?>(null) }
    var savedInvestigations by remember { mutableStateOf<List<SavedInvestigation>>(emptyList()) }
    var showSaved by remember { mutableStateOf(false) }
    var storageMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val callLogPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val current = (state as? LookupState.Success)?.result
        if (granted && current != null) {
            scope.launch {
                callLogMessage = "Analyzing this device..."
                runCatching { loadCallHistory(current.canonicalNumber) }
                    .onSuccess { callLogSummary = it; callLogMessage = null }
                    .onFailure { callLogMessage = "The device call history could not be read." }
            }
        } else {
            callLogMessage = "Call-log access was not granted. Public searches remain available."
        }
    }
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            scope.launch {
                runCatching { exportInvestigations(uri) }
                    .onSuccess { storageMessage = "Saved investigations exported." }
                    .onFailure { storageMessage = "The report could not be exported." }
            }
        }
    }

    LaunchedEffect(Unit) {
        savedInvestigations = loadSavedInvestigations()
    }

    fun analyzeCallHistory() {
        val current = (state as? LookupState.Success)?.result ?: return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            scope.launch {
                callLogMessage = "Analyzing this device..."
                runCatching { loadCallHistory(current.canonicalNumber) }
                    .onSuccess { callLogSummary = it; callLogMessage = null }
                    .onFailure { callLogMessage = "The device call history could not be read." }
            }
        } else {
            callLogPermission.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Paper) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
        ) {
            Text("NUMBER INTELLIGENCE", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text("Inspect number assignment", color = Ink, fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp)
            Spacer(Modifier.height(10.dp))
            Text("Validate an international number and inspect its numbering-plan assignment and spoofing caveats.", color = Muted, lineHeight = 23.sp)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { showSaved = !showSaved },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
            ) {
                Text(if (showSaved) "Hide saved investigations" else "Saved investigations (${savedInvestigations.size})")
            }
            if (showSaved) {
                SavedInvestigationsPanel(
                    investigations = savedInvestigations,
                    export = { exportDocument.launch("number-origin-investigations.pdf") },
                )
            }
            storageMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Muted, fontSize = 13.sp)
            }
            Spacer(Modifier.height(30.dp))

            OutlinedTextField(
                value = number,
                onValueChange = { number = it.take(64) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Phone number") },
                placeholder = { Text("+1 202 555 0123") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    callLogSummary = null
                    callLogMessage = null
                    if (!validate(number.trim())) {
                        state = LookupState.Failure("Enter a valid number with its country calling code.")
                    } else {
                        state = LookupState.Loading
                        scope.launch {
                            state = runCatching { lookup(number.trim()) }
                                .fold({ LookupState.Success(it) }, { LookupState.Failure(it.message ?: "Lookup failed.") })
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = state !is LookupState.Loading,
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) {
                if (state is LookupState.Loading) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("Inspect", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.height(24.dp))
            when (val current = state) {
                is LookupState.Failure -> Text(current.message, color = MaterialTheme.colorScheme.error)
                is LookupState.Success -> ResultPanel(
                    result = current.result,
                    lookupScamReports = lookupScamReports,
                    callLogSummary = callLogSummary,
                    callLogMessage = callLogMessage,
                    analyzeCallHistory = ::analyzeCallHistory,
                    save = { scamReport ->
                        scope.launch {
                            runCatching { saveInvestigation(current.result, callLogSummary, scamReport) }
                                .onSuccess {
                                    savedInvestigations = loadSavedInvestigations()
                                    storageMessage = "Investigation saved locally."
                                }
                                .onFailure { storageMessage = "The investigation could not be saved." }
                        }
                    },
                    openUrl = openUrl,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun ResultPanel(
    result: LookupResult,
    lookupScamReports: suspend (String) -> ScamLookupResult,
    callLogSummary: CallLogSummary?,
    callLogMessage: String?,
    analyzeCallHistory: () -> Unit,
    save: (ScamLookupResult?) -> Unit,
    openUrl: (String) -> Boolean,
) {
    var publicLinkMessage by remember(result.canonicalNumber) { mutableStateOf<String?>(null) }
    var scamLookupState: ScamLookupState by remember(result.canonicalNumber) { mutableStateOf(ScamLookupState.Idle) }
    var pendingScamNumber by remember(result.canonicalNumber) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("PARSED RESULT", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(result.region, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Text(result.countryCode, color = Muted, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(20.dp))
        Fact("International", result.formattedInternational)
        Fact("Calling code", result.callingCode)
        result.areaCode?.let { Fact("Area code", it) }
        Fact("Number type", result.numberType.replace('_', ' '))
        Spacer(Modifier.height(18.dp))
        Text(
            result.disclaimer,
            modifier = Modifier.fillMaxWidth().background(Warning).padding(14.dp),
            color = Color(0xFF604C21),
            fontSize = 13.sp,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(24.dp))
        Text("CALLER AUTHENTICITY", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text(result.investigation.status.uppercase(), color = Ink, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(result.investigation.summary, color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        result.investigation.indicators.forEach { indicator ->
            Text("• $indicator", modifier = Modifier.padding(top = 7.dp), color = Ink, fontSize = 13.sp, lineHeight = 18.sp)
        }
        if (result.investigation.publicSearches.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("LIVE PUBLIC CHECKS", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("Opens current external results. Reports are leads, not proof of caller identity or location.", modifier = Modifier.padding(top = 6.dp), color = Muted, fontSize = 13.sp)
            result.investigation.publicSearches.forEach { search ->
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        if (search.inAppScamLookup) {
                            pendingScamNumber = result.canonicalNumber
                            scamLookupState = ScamLookupState.Loading
                            scope.launch {
                                try {
                                    val actionNumber = pendingScamNumber ?: error("The phone number is no longer available.")
                                    scamLookupState = runCatching { lookupScamReports(actionNumber) }
                                        .fold(
                                            { ScamLookupState.Success(it) },
                                            { ScamLookupState.Failure(it.message ?: "The Scam Phone lookup failed.") },
                                        )
                                } finally {
                                    pendingScamNumber = null
                                }
                            }
                        } else {
                            publicLinkMessage = if (openUrl(search.url)) null else "No browser could open this link."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !search.inAppScamLookup || scamLookupState !is ScamLookupState.Loading,
                    shape = RoundedCornerShape(4.dp),
                ) {
                    if (search.inAppScamLookup && scamLookupState is ScamLookupState.Loading) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(search.title)
                    }
                }
            }
            when (val scamState = scamLookupState) {
                is ScamLookupState.Success -> {
                    Spacer(Modifier.height(18.dp))
                    Text("SCAM PHONE REPORTS", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Fact("Country", scamState.result.country)
                    Fact("Location", scamState.result.location)
                    Text(
                        scamState.result.reportSummary,
                        modifier = Modifier.padding(top = 12.dp),
                        color = Ink,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Text(
                        "Retrieved ${DateFormat.getDateTimeInstance().format(Date(scamState.result.retrievedAtMillis))}",
                        modifier = Modifier.padding(top = 8.dp),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    OutlinedButton(
                        onClick = { openUrl(scamState.result.sourceUrl) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        shape = RoundedCornerShape(4.dp),
                    ) {
                        Text("Open source report")
                    }
                }
                is ScamLookupState.Failure -> {
                    Spacer(Modifier.height(8.dp))
                    Text(scamState.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                else -> Unit
            }
            publicLinkMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text("DEVICE CALL HISTORY", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Text("Optional and analyzed only on this device. Call records are not uploaded.", color = Muted, fontSize = 13.sp, lineHeight = 19.sp)
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = analyzeCallHistory,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
        ) {
            Text("Analyze matching calls")
        }
        callLogMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Muted, fontSize = 13.sp)
        }
        callLogSummary?.let { summary ->
            Spacer(Modifier.height(12.dp))
            Fact("Matching calls", summary.matches.toString())
            Fact("Incoming / outgoing", "${summary.incoming} / ${summary.outgoing}")
            Fact("Missed", summary.missed.toString())
            Fact("Blocked / rejected", summary.blockedOrRejected.toString())
            Fact("Total connected", "${summary.totalDurationSeconds} sec")
            Fact(
                "Last seen",
                summary.lastSeenMillis?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "No match",
            )
            if (summary.records.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("LOCAL EVIDENCE · NEWEST FIRST", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                summary.records.forEachIndexed { index, record ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                        Text("${index + 1}. ${record.disposition}", color = Ink, fontWeight = FontWeight.Bold)
                        Text(
                            DateFormat.getDateTimeInstance().format(Date(record.timestampMillis)),
                            color = Muted,
                            fontSize = 13.sp,
                        )
                        Text(
                            "${record.durationSeconds} sec · ${record.presentation} · ${record.phoneAccountId}",
                            color = Muted,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = { save((scamLookupState as? ScamLookupState.Success)?.result) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
        ) {
            Text(
                if (scamLookupState is ScamLookupState.Success) "Save investigation and scam report" else "Save investigation",
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Muted, fontSize = 13.sp)
        Text(value, color = Ink, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun SavedInvestigationsPanel(
    investigations: List<SavedInvestigation>,
    export: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("SAVED REPORTS", color = Accent, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        if (investigations.isEmpty()) {
            Text("No investigations saved yet.", modifier = Modifier.padding(top = 8.dp), color = Muted, fontSize = 13.sp)
        } else {
            investigations.forEach { investigation ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
                    Text(investigation.number, color = Ink, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        "${investigation.region} · ${investigation.authenticity.uppercase()} · ${investigation.evidenceCount} call records" +
                            if (investigation.hasScamReport) " · Scam report" else "",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(investigation.createdAtMillis)),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }
            }
            OutlinedButton(onClick = export, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(4.dp)) {
                Text("Export PDF report")
            }
        }
    }
}

class InvestigationStore(private val context: Context) {
    private val directory = File(context.filesDir, "investigations")

    suspend fun save(
        result: LookupResult,
        callLog: CallLogSummary?,
        scamReport: ScamLookupResult?,
    ): SavedInvestigation = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val createdAtMillis = System.currentTimeMillis()
        val id = "$createdAtMillis-${UUID.randomUUID()}"
        val report = JSONObject()
            .put("schemaVersion", 3)
            .put("id", id)
            .put("createdAtUtc", java.time.Instant.ofEpochMilli(createdAtMillis).toString())
            .put("number", result.canonicalNumber)
            .put("formattedInternational", result.formattedInternational)
            .put("numberingRegion", result.region)
            .put("countryCode", result.countryCode)
            .put("numberType", result.numberType)
            .put(
                "numberingAssignment",
                JSONObject()
                    .put("callingCode", result.callingCode)
                    .put("areaCode", result.areaCode ?: JSONObject.NULL)
                    .put("tracksCurrentLocation", false),
            )
            .put("disclaimer", result.disclaimer)
            .put(
                "authenticity",
                JSONObject()
                    .put("status", result.investigation.status)
                    .put("summary", result.investigation.summary)
                    .put("indicators", JSONArray(result.investigation.indicators)),
            )
            .put("callLogEvidence", callLog?.toJson() ?: JSONObject.NULL)
            .put("scamPhoneReport", scamReport?.toJson() ?: JSONObject.NULL)

        val temporary = File(directory, "$id.tmp")
        val destination = File(directory, "$id.json")
        temporary.writeText(report.toString(2), Charsets.UTF_8)
        check(temporary.renameTo(destination)) { "Could not finalize investigation report." }
        report.toSavedInvestigation()
    }

    suspend fun list(): List<SavedInvestigation> = withContext(Dispatchers.IO) {
        if (!directory.exists()) return@withContext emptyList()
        directory.listFiles { file -> file.extension == "json" }
            ?.mapNotNull { file -> runCatching { JSONObject(file.readText(Charsets.UTF_8)).toSavedInvestigation() }.getOrNull() }
            ?.sortedByDescending { it.createdAtMillis }
            ?: emptyList()
    }

    suspend fun exportPdf(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val reports = directory.listFiles { file -> file.extension == "json" }
            ?.sortedBy { it.name }
            ?.map { file -> JSONObject(file.readText(Charsets.UTF_8)) }
            ?: emptyList()
        check(reports.isNotEmpty()) { "There are no saved investigations to export." }

        val document = PdfDocument()
        val output = requireNotNull(context.contentResolver.openOutputStream(uri)) { "Could not open report destination." }
        try {
            output.use {
                renderPdf(document, reports)
                document.writeTo(it)
            }
        } finally {
            document.close()
        }
    }

    private fun renderPdf(document: PdfDocument, reports: List<JSONObject>) {
        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.rgb(23, 33, 27)
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        val headingPaint = Paint(bodyPaint).apply {
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val sectionPaint = Paint(bodyPaint).apply {
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val pageWidth = 595
        val pageHeight = 842
        val margin = 44f
        val contentWidth = pageWidth - (margin * 2)
        val bottom = pageHeight - margin
        var pageNumber = 0
        var page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
        var canvas = page.canvas
        var verticalPosition = margin

        fun startPage() {
            document.finishPage(page)
            page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
            canvas = page.canvas
            verticalPosition = margin
        }

        fun drawLine(text: String, paint: Paint = bodyPaint, spacingAfter: Float = 4f) {
            var remaining = text.ifBlank { " " }
            do {
                if (verticalPosition + paint.textSize + spacingAfter > bottom) startPage()
                val characterCount = paint.breakText(remaining, true, contentWidth, null).coerceAtLeast(1)
                var breakAt = characterCount
                if (characterCount < remaining.length) {
                    val whitespace = remaining.lastIndexOf(' ', characterCount - 1)
                    if (whitespace > 0) breakAt = whitespace
                }
                val line = remaining.substring(0, breakAt).trimEnd()
                canvas.drawText(line, margin, verticalPosition + paint.textSize, paint)
                verticalPosition += paint.textSize + spacingAfter
                remaining = remaining.substring(breakAt).trimStart()
            } while (remaining.isNotEmpty())
        }

        drawLine("Number Origin Desk", headingPaint, 8f)
        drawLine("Saved investigation report", sectionPaint, 4f)
        drawLine("Generated: ${java.time.Instant.now()}", bodyPaint, 14f)
        reports.forEachIndexed { index, report ->
            if (index > 0) drawLine(" ", bodyPaint, 8f)
            formatInvestigationReport(report, index + 1).forEach { line ->
                val paint = if (line.startsWith("Investigation ") || line.endsWith(":")) sectionPaint else bodyPaint
                drawLine(line, paint, if (line.endsWith(":")) 3f else 4f)
            }
        }
        document.finishPage(page)
    }

    private fun CallLogSummary.toJson(): JSONObject {
        val evidence = JSONArray()
        records.forEach { record ->
            evidence.put(
                JSONObject()
                    .put("timestampUtc", java.time.Instant.ofEpochMilli(record.timestampMillis).toString())
                    .put("disposition", record.disposition)
                    .put("durationSeconds", record.durationSeconds)
                    .put("presentation", record.presentation)
                    .put("phoneAccountId", record.phoneAccountId),
            )
        }
        return JSONObject()
            .put("matchingCalls", matches)
            .put("incoming", incoming)
            .put("outgoing", outgoing)
            .put("missed", missed)
            .put("blockedOrRejected", blockedOrRejected)
            .put("totalDurationSeconds", totalDurationSeconds)
            .put("records", evidence)
    }

    private fun ScamLookupResult.toJson(): JSONObject = JSONObject()
        .put("country", country)
        .put("location", location)
        .put("reportSummary", reportSummary)
        .put("sourceUrl", sourceUrl)
        .put("retrievedAtUtc", java.time.Instant.ofEpochMilli(retrievedAtMillis).toString())

    private fun JSONObject.toSavedInvestigation(): SavedInvestigation {
        val createdAt = java.time.Instant.parse(getString("createdAtUtc")).toEpochMilli()
        val callLog = optJSONObject("callLogEvidence")
        return SavedInvestigation(
            id = getString("id"),
            number = getString("number"),
            region = getString("numberingRegion"),
            authenticity = getJSONObject("authenticity").getString("status"),
            createdAtMillis = createdAt,
            evidenceCount = callLog?.optJSONArray("records")?.length() ?: 0,
            hasScamReport = optJSONObject("scamPhoneReport") != null,
        )
    }
}

internal fun formatInvestigationReport(report: JSONObject, index: Int): List<String> {
    val assignment = report.optJSONObject("numberingAssignment") ?: JSONObject()
    val authenticity = report.optJSONObject("authenticity") ?: JSONObject()
    val scamReport = report.optJSONObject("scamPhoneReport")
    val callLog = report.optJSONObject("callLogEvidence")
    val lines = mutableListOf(
        "Investigation $index",
        "Created: ${report.optString("createdAtUtc", "Not reported")}",
        "Number: ${report.optString("formattedInternational", report.optString("number", "Not reported"))}",
        "Numbering assignment:",
        "Region: ${report.optString("numberingRegion", "Not reported")}",
        "Country code: ${report.optString("countryCode", "Not reported")}",
        "Calling code: ${assignment.optString("callingCode", "Not reported")}",
        "Area code: ${if (assignment.isNull("areaCode")) "Not reported" else assignment.optString("areaCode")}",
        "Number type: ${report.optString("numberType", "Not reported")}",
        "Authenticity:",
        "Status: ${authenticity.optString("status", "unverified").uppercase()}",
        authenticity.optString("summary", "No authenticity summary was saved."),
    )
    authenticity.optJSONArray("indicators")?.let { indicators ->
        repeat(indicators.length()) { indicatorIndex -> lines += "- ${indicators.optString(indicatorIndex)}" }
    }
    scamReport?.let {
        lines += listOf(
            "Scam Phone report:",
            "Country: ${it.optString("country", "Not reported")}",
            "Location: ${it.optString("location", "Not reported")}",
            "Retrieved: ${it.optString("retrievedAtUtc", "Not reported")}",
            "Source: ${it.optString("sourceUrl", "Not reported")}",
            it.optString("reportSummary", "No public complaint summary was returned."),
        )
    }
    callLog?.let {
        lines += listOf(
            "Device call history:",
            "Matching calls: ${it.optInt("matchingCalls")}",
            "Incoming: ${it.optInt("incoming")}",
            "Outgoing: ${it.optInt("outgoing")}",
            "Missed: ${it.optInt("missed")}",
            "Blocked or rejected: ${it.optInt("blockedOrRejected")}",
            "Total connected duration: ${it.optLong("totalDurationSeconds")} seconds",
        )
        it.optJSONArray("records")?.let { records ->
            repeat(records.length()) { recordIndex ->
                val record = records.optJSONObject(recordIndex) ?: return@repeat
                lines += "${recordIndex + 1}. ${record.optString("timestampUtc")} | ${record.optString("disposition")} | " +
                    "${record.optLong("durationSeconds")} sec | ${record.optString("presentation")}"
            }
        }
    }
    lines += listOf(
        "Important limitation:",
        report.optString("disclaimer", "This report does not establish caller identity or live location."),
    )
    return lines
}