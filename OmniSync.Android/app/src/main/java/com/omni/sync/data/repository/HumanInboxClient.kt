package com.omni.sync.data.repository

import com.google.gson.JsonObject

/**
 * Read-only Android projection of the existing T3/OpenRig human@kernel inbox.
 * Transport: authenticated OmniHub AgentCall -> T3's existing operator inbox IPC.
 *
 * No Android-owned read flags, approvals, queue acknowledgements, or offline copy.
 * A fetch is a snapshot, not proof that an agent received a reply or a task is Done.
 */
data class HumanInboxEntry(
    val sourceId: String,
    val sourceType: String,
    val sender: String,
    val recipient: String?,
    val subject: String,
    val body: String,
    val createdAt: String,
    val queueState: String,
    val attentionKind: String,
    val requiresAction: Boolean,
    val replyEligible: Boolean,
    val replyUnavailableReason: String?,
    val isReadInT3: Boolean?,
    val t3Folder: String?,
    /** Source-provided destination only; never synthesize a URL from a qitem ID. */
    val deepLink: String?,
    /** The canonical TaskBoard review binding (if any), not an Android decision state. */
    val reviewGateJson: String?,
    val humanFormJson: String?,
)

data class HumanInboxSnapshot(
    val entries: List<HumanInboxEntry>,
    val truncated: Boolean,
    val sourceAvailable: Boolean,
    val note: String?,
)

/**
 * For a future phone view, call [fetchInbox] on a background coroutine. This
 * exposes the same source/ID/state as T3; any visible UI is a separate design task.
 * There are intentionally NO write/ack/reply methods.
 */
class HumanInboxClient(private val signalR: SignalRClient) {
    suspend fun fetchUnread(limit: Int = 50): HumanInboxSnapshot = fetch("unread", limit)

    suspend fun fetchRecentRead(limit: Int = 50): HumanInboxSnapshot = fetch("recent_read", limit)

    /** Combines two distinct T3 local read-state views; not an atomic server cursor. */
    suspend fun fetchInbox(limitPerView: Int = 50): HumanInboxSnapshot {
        val unread = fetchUnread(limitPerView)
        val recent = fetchRecentRead(limitPerView)
        return HumanInboxSnapshot(
            entries = (unread.entries + recent.entries)
                .distinctBy { it.sourceType to it.sourceId }
                .sortedWith(compareByDescending<HumanInboxEntry> { it.createdAt }
                    .thenBy { it.sourceId }),
            truncated = unread.truncated || recent.truncated,
            sourceAvailable = unread.sourceAvailable && recent.sourceAvailable,
            note = unread.note ?: recent.note,
        )
    }

    private suspend fun fetch(view: String, limit: Int): HumanInboxSnapshot {
        require(limit in 1..50) { "Human Inbox limit must be 1..50" }
        val raw = signalR.agentCall("operator.inbox.list", mapOf("view" to view, "limit" to limit))
        require(raw.isJsonObject) { "Invalid Human Inbox response" }
        val response = raw.asJsonObject
        val expectedAction = if (view == "unread") "fetch_unread" else "fetch_recent_read"
        check(response.text("action") == expectedAction &&
            response.text("address") == "human@kernel") {
            "Human Inbox response identity mismatch"
        }
        check(response.get("sourceAvailable")?.asBoolean == true) {
            "Canonical T3/OpenRig Human Inbox source unavailable"
        }
        val items = response.get("items")
        check(items != null && items.isJsonArray) { "Human Inbox items unavailable" }
        val entries = items.asJsonArray.map { element ->
            check(element.isJsonObject) { "Invalid Human Inbox item" }
            val item = element.asJsonObject
            val id = item.text("id").orEmpty()
            val source = item.text("sourceType").orEmpty()
            check(id.isNotBlank() && source.isNotBlank()) {
                "Missing canonical Human Inbox identity"
            }
            val localState = item.obj("localState")
            val review = item.obj("reviewGate")
            HumanInboxEntry(
                sourceId = id,
                sourceType = source,
                sender = item.text("sender").orEmpty(),
                recipient = item.text("recipient"),
                subject = item.text("subject").orEmpty(),
                body = item.text("body").orEmpty(),
                createdAt = item.text("createdAt").orEmpty(),
                queueState = item.text("state").orEmpty(),
                attentionKind = item.text("attentionKind").orEmpty(),
                requiresAction = item.boolean("requiresAction") ?: false,
                replyEligible = item.boolean("replyEligible") ?: false,
                replyUnavailableReason = item.text("replyUnavailableReason"),
                isReadInT3 = localState?.boolean("read"),
                t3Folder = localState?.text("folder"),
                deepLink = item.text("deepLink") ?: item.text("url")
                    ?: review?.text("deepLink") ?: review?.text("url"),
                reviewGateJson = review?.toString(),
                humanFormJson = item.obj("humanForm")?.toString(),
            )
        }
        return HumanInboxSnapshot(
            entries = entries,
            truncated = response.boolean("truncated") ?: true,
            sourceAvailable = true,
            note = response.text("note"),
        )
    }
}

private fun JsonObject.obj(key: String): JsonObject? =
    get(key)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.text(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.boolean(key: String): Boolean? =
    get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isBoolean
    }?.asBoolean
