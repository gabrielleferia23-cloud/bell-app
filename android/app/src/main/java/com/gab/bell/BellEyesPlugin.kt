package com.gab.bell

import android.graphics.BitmapFactory
import android.util.Base64
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.tasks.Tasks
import com.getcapacitor.JSArray
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Lets Bell see: it reads the words in a picture and names the things in it, from a scanned page or a camera photo. The readers are built into the app, so it works offline and the picture
 * never leaves the phone.
 */
@CapacitorPlugin(name = "BellEyes")
class BellEyesPlugin : Plugin() {
    private val reader by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    private fun decode(call: PluginCall): android.graphics.Bitmap? {
        val data = call.getString("image") ?: return null
        return try {
            val bytes = Base64.decode(data.substringAfter(","), Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) { null }
    }

    /** Looks at a camera photo: what things are in it, and any words written on it. */
    @PluginMethod
    fun look(call: PluginCall) {
        val bitmap = decode(call) ?: return call.reject("bad_image")
        val image = InputImage.fromBitmap(bitmap, 0)
        val words = reader.process(image)
        val things = labeler.process(image)
        Tasks.whenAllComplete(words, things).addOnCompleteListener {
            val ret = JSObject()
            ret.put("text", if (words.isSuccessful) words.result.textBlocks.joinToString("\n\n") { it.text } else "")
            val labels = JSArray()
            if (things.isSuccessful) for (l in things.result) {
                labels.put(JSObject().put("text", l.text).put("confidence", l.confidence.toDouble()))
            }
            ret.put("labels", labels)
            if (!words.isSuccessful && !things.isSuccessful) call.reject("look_failed") else call.resolve(ret)
        }
    }

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
