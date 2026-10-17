package com.omni.sync.logic.assistant

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

data class QueuedAssistantVoiceRequest(
    val requestId: String,
    val text: String,
    val occurredAtMillis: Long,
    val conversationId: String? = null
)

object AssistantVoiceOutbox {
    private const val PREFS = "assistant_voice_outbox"
    private const val KEY_ITEMS = "items"
    private val gson = Gson()
    private val listType =
        object : TypeToken<MutableList<QueuedAssistantVoiceRequest>>() {}.type

    @Synchronized
    fun enqueue(
        context: Context,
        text: String,
        occurredAtMillis: Long = System.currentTimeMillis(),
        conversationId: String? = null
    ): QueuedAssistantVoiceRequest {
        val item = QueuedAssistantVoiceRequest(
            UUID.randomUUID().toString(),
            text,
            occurredAtMillis,
            conversationId
        )
        val items = load(context)
        items += item
        persist(context, items)
        return item
    }

    @Synchronized
    fun items(context: Context): List<QueuedAssistantVoiceRequest> =
        load(context).toList()

    @Synchronized
    fun remove(context: Context, requestId: String) {
        val items = load(context)
        if (items.removeAll { it.requestId == requestId })
            persist(context, items)
    }

    private fun load(context: Context): MutableList<QueuedAssistantVoiceRequest> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ITEMS, null)
            ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<QueuedAssistantVoiceRequest>>(json, listType)
                ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun persist(
        context: Context,
        items: List<QueuedAssistantVoiceRequest>
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, gson.toJson(items))
            .commit()
    }
}
