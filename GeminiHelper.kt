package com.cheatoverlay

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GeminiHelper {

    private const val MODEL = "gemini-1.5-flash"
    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    fun askWithImage(bitmap: Bitmap, apiKey: String): String {
        return try {
            // Compress bitmap ke base64
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, baos)
            val imageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

            // Build request JSON
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            // Text prompt
                            put(JSONObject().apply {
                                put("text", buildPrompt())
                            })
                            // Image
                            put(JSONObject().apply {
                                put("inline_data", JSONObject().apply {
                                    put("mime_type", "image/png")
                                    put("data", imageBase64)
                                })
                            })
                        })
                    })
                })
                // Safety settings longgar
                put("safetySettings", JSONArray().apply {
                    listOf("HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH",
                           "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"
                    ).forEach { cat ->
                        put(JSONObject().apply {
                            put("category", cat)
                            put("threshold", "BLOCK_NONE")
                        })
                    }
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.3) // Low temp = lebih fokus/akurat
                    put("maxOutputTokens", 1024)
                })
            }

            // HTTP request
            val url = URL("$ENDPOINT?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 30000
            }

            OutputStreamWriter(conn.outputStream).use {
                it.write(requestBody.toString())
                it.flush()
            }

            val responseCode = conn.responseCode
            val responseText = if (responseCode == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText() ?: "Error $responseCode"
            }

            if (responseCode == 200) {
                parseGeminiResponse(responseText)
            } else {
                "❌ API Error $responseCode: ${responseText.take(200)}"
            }

        } catch (e: Exception) {
            "❌ Error: ${e.message}"
        }
    }

    private fun buildPrompt(): String {
        return """Ini adalah soal ujian. Jawab dengan SINGKAT dan TEPAT.

Format jawaban:
- Kalau multiple choice: langsung tulis huruf + jawaban (contoh: "A. Jakarta")
- Kalau isian: langsung tulis jawabannya
- Kalau essay: jawab ringkas 2-3 kalimat
- Kalau ada soal hitungan: tampilkan langkah singkat + hasil akhir

PENTING: Jawab dalam bahasa yang sama dengan soal. Jangan bertele-tele."""
    }

    private fun parseGeminiResponse(json: String): String {
        return try {
            val obj = JSONObject(json)
            val candidates = obj.getJSONArray("candidates")
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            parts.getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            "⚠️ Parse error: ${e.message}\n\nRaw: ${json.take(300)}"
        }
    }
}
