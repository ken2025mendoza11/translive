package app.translive.share

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.translive.data.Dictionary
import app.translive.data.Prefs
import app.translive.engine.Layer
import app.translive.engine.LookupChain
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lines = mutableStateListOf("Reading the picture…")
        setContent {
            val shown = remember { lines }
            Column(Modifier.padding(20.dp)) {
                Text("Screenshot translation", fontSize = 22.sp, color = Color(0xFF14204A))
                shown.forEach { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
        val uri = intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM) ?: return
        val stream = contentResolver.openInputStream(uri) ?: return
        val bitmap = stream.use { BitmapFactory.decodeStream(it) } ?: return
        val prefs = Prefs(this)
        val dict = Dictionary.load(this)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnSuccessListener { vision ->
            lines.clear()
            if (vision.textBlocks.isEmpty()) lines.add("No Chinese text found in that picture.")
            for (block in vision.textBlocks) {
                val zh = block.text.trim()
                val hit = LookupChain.translate(zh, prefs.glossary(), dict, emptyMap(), prefs.currency(), false)
                val en = if (hit.layer == Layer.NONE) "not in the dictionary yet" else hit.en
                lines.add("$zh → $en")
            }
        }.addOnFailureListener {
            lines.clear()
            lines.add("Could not read that picture. Try a clearer screenshot.")
        }
    }
}
