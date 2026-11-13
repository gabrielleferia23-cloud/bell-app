package com.gab.bell

import android.graphics.BitmapFactory
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Lets Bell read the words in a picture, such as a scanned page or a photo of
 * a document. The text reader is built into the app, so it works offline and
 * the picture never leaves the phone.
 */
@CapacitorPlugin(name = "BellEyes")
class BellEyesPlugin : Plugin() {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    @PluginMethod
    fun readText(call: PluginCall) {
        val data = call.getString("image") ?: return call.reject("No picture given")
        val bitmap = try {
            val bytes = Base64.decode(data.substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null } ?: return call.reject("bad_image")
        reader.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = result.textBlocks.joinToString("\n\n") { it.text }
                call.resolve(JSObject().put("text", text))
            }
            .addOnFailureListener { e -> call.reject("read_failed: " + (e.message ?: "unknown")) }
    }
}
