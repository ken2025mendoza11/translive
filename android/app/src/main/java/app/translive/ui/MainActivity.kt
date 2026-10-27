package app.translive.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.translive.data.Dictionary
import app.translive.data.Prefs
import app.translive.data.TargetApp
import app.translive.engine.LookupChain

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)
        val phrases = Dictionary.load(this)
        setContent { AppScreen(prefs, phrases, this) }
    }
}

private val Navy = Color(0xFF14204A)
private val Paper = Color(0xFFF3F6FB)
private val Brand = Color(0xFF1676E8)

@Composable
private fun AppScreen(prefs: Prefs, phrases: Map<String, String>, activity: MainActivity) {
    var screen by remember { mutableIntStateOf(if (prefs.onboarded()) 1 else 0) }
    var tick by remember { mutableIntStateOf(0) }
    val apps = remember(tick) { prefs.apps() }
    Column(
        Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TransLIVE", color = Navy, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
        Text("Chinese → English", color = Color(0xFF5D6B86))
        when (screen) {
            0 -> Onboarding { prefs.setOnboarded(); screen = 1 }
            1 -> Home(activity, prefs, apps, { screen = 2 }, { screen = 3 }, { tick++ })
            2 -> Settings(prefs, phrases.size) { screen = 1 }
            else -> Sample(phrases, prefs) { screen = 1 }
        }
    }
}

@Composable
private fun Onboarding(done: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    Text("Step ${step + 1} of 3", color = Brand, fontWeight = FontWeight.Bold)
    when (step) {
        0 -> Text("English appears inside the shopping app you already opened. Most phrases come from a dictionary on the phone.", color = Navy)
        1 -> Text("Two permissions: Accessibility reads Chinese already on screen in the apps you choose. It does not record typing or passwords. Display over other apps draws the English. Touches pass through.", color = Navy)
        else -> Text("Order, chat, account, payment and address screens stay on the phone and are never uploaded. Only anonymous phrase pairs can be shared, and only if you leave Contribute on.", color = Navy)
    }
    Button(onClick = { if (step < 2) step++ else done() }, modifier = Modifier.fillMaxWidth()) {
        Text(if (step < 2) "Continue" else "I understand")
    }
}

@Composable
private fun Home(
    activity: MainActivity,
    prefs: Prefs,
    apps: List<TargetApp>,
    openSettings: () -> Unit,
    openSample: () -> Unit,
    refresh: () -> Unit,
) {
    val access = accessibilityOn(activity)
    val overlay = Settings.canDrawOverlays(activity)
    Text(
        if (prefs.paused()) "Paused" else if (access) "Translating is on" else "Turn on accessibility",
        color = Color.White,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(if (prefs.paused()) Color(0xFFE23D3D) else Color(0xFF12945F), RoundedCornerShape(18.dp)).padding(16.dp),
    )
    Button(onClick = { prefs.setPaused(!prefs.paused()); refresh() }) { Text(if (prefs.paused()) "Resume" else "Pause") }
    if (!access) Button(onClick = { activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
    if (!overlay) Button(onClick = {
        activity.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${activity.packageName}")))
    }) { Text("Allow display over other apps") }
    Text("Translate apps", fontWeight = FontWeight.Bold, color = Navy)
    apps.forEach { app ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(app.label, color = Navy, fontWeight = FontWeight.Bold)
                Text(if (app.enabled) app.mode else "Not translated", color = Color(0xFF5D6B86), fontSize = 12.sp)
            }
            Switch(checked = app.enabled, onCheckedChange = {
                prefs.saveApps(apps.map { if (it.pkg == app.pkg) it.copy(enabled = !it.enabled) else it })
                refresh()
            })
        }
    }
    Button(onClick = openSample) { Text("Try sample phrases") }
    Button(onClick = openSettings) { Text("Settings") }
}

@Composable
private fun Settings(prefs: Prefs, count: Int, back: () -> Unit) {
    var ocr by remember { mutableStateOf(prefs.ocr()) }
    var currency by remember { mutableStateOf(prefs.currency()) }
    Text("Settings", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Translate image text", color = Navy)
        Switch(checked = ocr, onCheckedChange = { ocr = it; prefs.setOcr(it) })
    }
    Text("Prices also in $currency", color = Navy)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("PHP", "USD", "EUR", "NONE").forEach { code ->
            Button(onClick = { currency = code; prefs.setCurrency(code) }) { Text(if (code == "NONE") "Off" else code) }
        }
    }
    Text("Offline dictionary 2026.10.27 · $count phrases", color = Navy)
    Text("Never uploaded: Orders, Chat, Account, Payment, Address. Still translated on the phone.", color = Color(0xFF5D6B86))
    Text("Cloud translation stays off until you add your own proxy. Keys are never stored in the app.", color = Color(0xFF5D6B86))
    Button(onClick = back) { Text("Back") }
}

@Composable
private fun Sample(phrases: Map<String, String>, prefs: Prefs, back: () -> Unit) {
    val samples = listOf("立即领取", "规则", "先用后付", "首件立减", "已拼1.2万件", "¥3.9", "下场12点开抢", "包邮")
    Text("Sample shop phrases", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
    samples.forEach { zh ->
        val hit = LookupChain.translate(zh, prefs.glossary(), phrases, emptyMap(), prefs.currency(), false)
        Text("$zh  →  ${hit.en}", color = Navy)
    }
    Button(onClick = back) { Text("Back") }
}

private fun accessibilityOn(activity: MainActivity): Boolean {
    val cr = activity.contentResolver
    if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) return false
    val flat = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    return flat.contains(activity.packageName)
}
