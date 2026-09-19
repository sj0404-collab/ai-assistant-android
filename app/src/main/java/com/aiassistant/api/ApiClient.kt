package com.aiassistant.api

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

enum class AiProvider {
    OPENROUTER,
    OPENAI,
    ANTHROPIC,
    LOCAL
}

object ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun sendRequest(
        provider: AiProvider,
        apiKey: String,
        prompt: String,
        fileName: String,
        fileBytes: ByteArray?
    ): String = withContext(Dispatchers.IO) {
        when (provider) {
            AiProvider.OPENROUTER -> sendToOpenRouter(apiKey, prompt, fileName, fileBytes)
            AiProvider.OPENAI -> sendToOpenAI(apiKey, prompt, fileName, fileBytes)
            AiProvider.ANTHROPIC -> sendToAnthropic(apiKey, prompt, fileName, fileBytes)
            AiProvider.LOCAL -> processLocally(prompt, fileName, fileBytes)
        }
    }

    private fun sendToOpenRouter(
        apiKey: String,
        prompt: String,
        fileName: String,
        fileBytes: ByteArray?
    ): String {
        val key = apiKey.ifEmpty { getApiKeyFromPrefs("openrouter") }

        val messages = JsonObject().apply {
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        if (fileBytes != null) {
                            add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", "data:application/octet-stream;base64," +
                                        Base64.encodeToString(fileBytes, Base64.NO_WRAP))
                                })
                            })
                        }
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "File: $fileName\n\n$prompt")
                        })
                    })
                })
            })
        }

        val body = JsonObject().apply {
            addProperty("model", "google/gemini-2.0-flash-001")
            add("messages", messages.get("messages"))
        }

        val request = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("HTTP-Referer", "https://aiassistant.app")
            .addHeader("X-Title", "AI Assistant")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        return parseOpenRouterResponse(responseBody)
    }

    private fun sendToOpenAI(
        apiKey: String,
        prompt: String,
        fileName: String,
        fileBytes: ByteArray?
    ): String {
        val key = apiKey.ifEmpty { getApiKeyFromPrefs("openai") }

        val messages = JsonObject().apply {
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", com.google.gson.JsonArray().apply {
                        if (fileBytes != null && fileName.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)$"))) {
                            add(JsonObject().apply {
                                addProperty("type", "image_url")
                                add("image_url", JsonObject().apply {
                                    addProperty("url", "data:image/${getFileExtension(fileName)};base64," +
                                        Base64.encodeToString(fileBytes, Base64.NO_WRAP))
                                })
                            })
                        }
                        add(JsonObject().apply {
                            addProperty("type", "text")
                            addProperty("text", "File: $fileName\n\n$prompt")
                        })
                    })
                })
            })
        }

        val body = JsonObject().apply {
            addProperty("model", "gpt-4o")
            add("messages", messages.get("messages"))
            addProperty("max_tokens", 4096)
        }

        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        return parseOpenAIResponse(responseBody)
    }

    private fun sendToAnthropic(
        apiKey: String,
        prompt: String,
        fileName: String,
        fileBytes: ByteArray?
    ): String {
        val key = apiKey.ifEmpty { getApiKeyFromPrefs("anthropic") }

        val content = com.google.gson.JsonArray().apply {
            if (fileBytes != null && fileName.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)$"))) {
                add(JsonObject().apply {
                    addProperty("type", "image")
                    add("source", JsonObject().apply {
                        addProperty("type", "base64")
                        addProperty("media_type", "image/${getFileExtension(fileName)}")
                        addProperty("data", Base64.encodeToString(fileBytes, Base64.NO_WRAP))
                    })
                })
            }
            add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", "File: $fileName\n\n$prompt")
            })
        }

        val body = JsonObject().apply {
            addProperty("model", "claude-sonnet-4-20250514")
            addProperty("max_tokens", 4096)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    add("content", content)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", "2023-06-01")
            .addHeader("content-type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw Exception("Empty response")

        if (!response.isSuccessful) {
            throw Exception("HTTP ${response.code}: $responseBody")
        }

        return parseAnthropicResponse(responseBody)
    }

    private fun processLocally(
        prompt: String,
        fileName: String,
        fileBytes: ByteArray?
    ): String {
        val fileInfo = buildString {
            appendLine("=== File Analysis ===")
            appendLine("Name: $fileName")
            appendLine("Size: ${fileBytes?.size ?: 0} bytes")
            appendLine("Type: ${getFileType(fileName)}")
            appendLine()
            appendLine("=== Local Processing ===")
            appendLine("This is a local mode (no API key required).")
            appendLine("File received and ready for processing.")
            appendLine()
            appendLine("To use AI capabilities, please configure an API key in Settings.")
        }
        return fileInfo
    }

    private fun parseOpenRouterResponse(response: String): String {
        val json = JsonParser.parseString(response).asJsonObject
        return json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
    }

    private fun parseOpenAIResponse(response: String): String {
        val json = JsonParser.parseString(response).asJsonObject
        return json.getAsJsonArray("choices")
            .get(0).asJsonObject
            .getAsJsonObject("message")
            .get("content").asString
    }

    private fun parseAnthropicResponse(response: String): String {
        val json = JsonParser.parseString(response).asJsonObject
        return json.getAsJsonArray("content")
            .get(0).asJsonObject
            .get("text").asString
    }

    private fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "txt")
    }

    private fun getFileType(fileName: String): String {
        val ext = getFileExtension(fileName).lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp" -> "Image"
            "pdf" -> "PDF Document"
            "zip", "rar", "7z" -> "Archive"
            "txt", "md" -> "Text"
            "json" -> "JSON"
            "xml" -> "XML"
            "csv" -> "CSV"
            else -> "File ($ext)"
        }
    }

    private fun getApiKeyFromPrefs(provider: String): String {
        return ""
    }
}
