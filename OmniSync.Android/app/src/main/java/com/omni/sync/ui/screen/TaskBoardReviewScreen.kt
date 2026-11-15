package com.omni.sync.ui.screen

import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import kotlinx.coroutines.delay
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omni.sync.data.taskboard.MobileReview
import com.omni.sync.data.taskboard.MobileReviewDecision
import com.omni.sync.data.taskboard.MobileReviewPin
import com.omni.sync.data.taskboard.MobileReviewAttachment
import com.omni.sync.data.taskboard.TaskBoardReviewStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64

/**
 * Human-owned review surface; never used for automatic agent approvals.
 * All operations use the synced file store, no remote privileged RPCs.
 */
@Composable
fun TaskBoardReviewScreen(onClose: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var queue by remember { mutableStateOf<List<MobileReview>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var showResolved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var choice by remember { mutableStateOf<String?>(null) }
    val ranking = remember { mutableStateListOf<String>() }
    val pins = remember { mutableStateListOf<MobileReviewPin>() }
    var note by remember { mutableStateOf("") }
    val reviewImages = remember { mutableStateListOf<MobileReviewAttachment>() }
    val context = LocalContext.current
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    uris.take((10 - reviewImages.size).coerceAtLeast(0)).mapIndexed { index, uri ->
                        val mime = context.contentResolver.getType(uri) ?: ""
                        require(mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif")) {
                            "Please choose PNG, JPEG, WebP or GIF images."
                        }
                        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                            input.readNBytes(20 * 1024 * 1024 + 1)
                        } ?: error("Could not read the selected image.")
                        require(bytes.size <= 20 * 1024 * 1024) { "Each image must be under 20 MB." }
                        MobileReviewAttachment("Image ${reviewImages.size + index + 1}", mime, bytes)
                    }
                }
            }.onSuccess { reviewImages.addAll(it) }
                .onFailure { error = it.message ?: "Could not attach gallery images." }
        }
    }
    var pinMode by remember { mutableStateOf(false) }
    var videoPosition by remember { mutableStateOf(0.0) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var focused by remember { mutableStateOf(0) }
    var queueOpen by remember { mutableStateOf(true) }
    var fullscreenVideo by remember { mutableStateOf(false) }
    var playbackError by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { TaskBoardReviewStore.queue(showResolved) } }
                .onSuccess { fresh ->
                    queue = fresh
                    if (selected == null || fresh.none { it.project.root.path + "/" + it.taskId == selected })
                        selected = fresh.firstOrNull()?.let { it.project.root.path + "/" + it.taskId }
                    error = null
                }.onFailure { error = "Unable to read synced review files: ${it.message}" }
        }
    }
    LaunchedEffect(showResolved) { refresh() }
    val review = queue.firstOrNull { it.project.root.path + "/" + it.taskId == selected }
    val artifacts = remember(review?.manifestRevision) {
        review?.round?.optJSONArray("artifacts")?.let { ar ->
            (0 until ar.length()).map { ar.getJSONObject(it) }
        } ?: emptyList()
    }
    LaunchedEffect(selected, review?.manifestRevision) {
        choice = review?.round?.optJSONObject("result")?.optJSONArray("choices")?.optString(0)
        ranking.clear()
        pins.clear()
        note = review?.round?.optJSONObject("result")?.optString("note") ?: ""
        reviewImages.clear()
        pinMode = review?.mode == "markup"
        videoPosition = 0.0
        videoView = null
        webView = null
        fullscreenVideo = false
        playbackError = null
        focused = artifacts.indexOfFirst { it.optString("id") == choice }.coerceAtLeast(0)
    }
    val canAnswer = review != null && review.status == "open" &&
        (review.round.isNull("result") || !review.round.has("result"))
    LaunchedEffect(videoView) {
        while (videoView != null) {
            val current = videoView
            if (current != null) videoPosition = current.currentPosition.coerceAtLeast(0) / 1000.0
            delay(400)
        }
    }

    fun resolve(verdict: String) {
        val current = review ?: return
        if (!canAnswer || saving) return
        if (verdict == "picked" && choice == null) {
            error = "Choose a variant before submitting."
            return
        }
        if (verdict == "ranked" && ranking.size != artifacts.size) {
            error = "Tap every variant to provide a complete ranking."
            return
        }
        saving = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    TaskBoardReviewStore.resolve(current, MobileReviewDecision(
                        verdict = verdict,
                        choices = when (verdict) {
                            "picked" -> listOfNotNull(choice)
                            "approved" -> listOfNotNull(artifacts.firstOrNull()?.optString("id"))
                            else -> emptyList()
                        },
                        ranking = if (verdict == "ranked") ranking.toList() else emptyList(),
                        pins = pins.toList(),
                        note = note,
                        attachments = reviewImages.toList(),
                    ))
                    TaskBoardReviewStore.queue(showResolved)
                }
            }.onSuccess { fresh ->
                queue = fresh
                selected = fresh.firstOrNull()?.let { it.project.root.path + "/" + it.taskId }
                reviewImages.clear()
                error = null
            }.onFailure { error = "Review conflict or save error: ${it.message}. Refresh to retry." }
            saving = false
        }
    }

    fun selectVariant(artifact: JSONObject) {
        if (!canAnswer) return
        val id = artifact.optString("id")
        when (review?.mode) {
            "pick" -> choice = id
            "rank" -> if (id in ranking) ranking.remove(id) else ranking.add(id)
        }
    }

    fun pin(artifact: JSONObject, position: Offset, size: IntSize) {
        if (!canAnswer || size.width <= 0 || size.height <= 0) return
        pins.add(MobileReviewPin(
            x = (position.x.toDouble() / size.width).coerceIn(0.0, 1.0),
            y = (position.y.toDouble() / size.height).coerceIn(0.0, 1.0),
            artifact = artifact.optString("id"),
            timestamp = if (artifact.optString("kind") == "video") videoPosition else null,
            viewportWidth = if (artifact.optString("kind") == "html") size.width else null,
            viewportHeight = if (artifact.optString("kind") == "html") size.height else null,
            scrollX = webView?.scrollX?.toDouble(),
            scrollY = webView?.scrollY?.toDouble(),
        ))
    }

    val openCount = queue.count { it.status == "open" }
    Column(modifier.fillMaxSize()) {
        // Concise heading: back, title + open count, queue toggle, refresh.
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Board", maxLines = 1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (openCount == 1) "1 open" else "$openCount open",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilterChip(selected = queueOpen || review == null, onClick = { queueOpen = !queueOpen },
                label = { Text("Queue · ${queue.size}", maxLines = 1) })
            TextButton(onClick = { refresh() }) { Text("↻", style = MaterialTheme.typography.titleMedium) }
        }
        error?.let {
            Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }
        if (queueOpen || review == null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = showResolved, onClick = { showResolved = !showResolved },
                        label = { Text("Include resolved") })
                    Spacer(Modifier.weight(1f))
                    Text("Human verdicts only", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(queue, key = { it.project.root.path + it.taskId }) { item ->
                        val key = item.project.root.path + "/" + item.taskId
                        ReviewQueueCard(item, isSelected = key == selected, onClick = {
                            selected = key
                            queueOpen = false
                        })
                    }
                }
            }
        }
        if (review == null) {
            Card(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text("No synced reviews available. Sync the board's Reviews folder from your PC.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp))
            }
            return@Column
        }
        val focusIdx = focused.coerceIn(0, (artifacts.size - 1).coerceAtLeast(0))
        val focusedArtifact = artifacts.getOrNull(focusIdx)
        val scroller = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroller).padding(horizontal = 10.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReviewStageHeader(review, canAnswer)
            if (focusedArtifact == null) {
                Text("This round has no artifacts.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val id = focusedArtifact.optString("id", "A")
                val label = focusedArtifact.optString("label", id)
                val active = choice == id || id in ranking
                // Stage caption: focused candidate, pin toggle, contextual hint.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReviewBadge("${focusIdx + 1}/${artifacts.size}",
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    FilterChip(selected = pinMode, onClick = { pinMode = !pinMode },
                        label = { Text(if (pins.isEmpty()) "Pin" else "Pin · ${pins.size}") }, enabled = canAnswer)
                }
                key(review.taskId, id) {
                    ReviewArtifactPreview(review, focusedArtifact, pinMode, videoPosition,
                        pins = pins.withIndex().filter { it.value.artifact == id }.map { it.index + 1 to it.value },
                        onVideoTime = { videoPosition = it }, onVideo = { videoView = it },
                        onWeb = { webView = it }, onVideoError = { playbackError = it },
                        onPin = { pos, size -> pin(focusedArtifact, pos, size) },
                        onApproveSwipe = { direction ->
                            if (review.mode == "approve" && canAnswer)
                                resolve(if (direction > 0) "approved" else "rejected")
                        })
                }
                if (focusedArtifact.optString("kind") == "video") {
                    val playableVideo = focusedArtifact.optString("storage") != "local" &&
                        TaskBoardReviewStore.artifactFile(review, focusedArtifact)?.let {
                            it.isFile && it.extension.lowercase() in setOf("mp4", "webm")
                        } == true
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (playableVideo) OutlinedButton(onClick = {
                            videoPosition = videoView?.currentPosition?.coerceAtLeast(0)?.div(1000.0) ?: videoPosition
                            videoView?.pause()
                            fullscreenVideo = true
                            playbackError = null
                        }) { Text("Full-screen video") }
                        OutlinedButton(onClick = {
                            val board = review.project.root.parentFile?.name.orEmpty()
                            val remote = "https://hub.rancorous.online/gateway/tools/taskboard-review-media/review/" +
                                Uri.encode(board) + "/" + Uri.encode(review.taskId)
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(remote))) }
                                .onFailure { error = "Cannot open OmniHub review: " + it.message }
                        }) { Text(if (playableVideo) "Watch through OmniHub" else "Stream from PC") }
                    }
                    playbackError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(when {
                        !canAnswer -> "Read-only"
                        pinMode -> "Tap or long-press the preview to drop a pin."
                        review.mode == "approve" -> "Swipe preview → approve · ← reject"
                        review.mode == "pick" -> "Choose a candidate, then Pick."
                        review.mode == "rank" -> "Add candidates in order, best first."
                        else -> "Enable Pin to mark points."
                    } + if (focusedArtifact.optString("storage") == "local" ||
                        focusedArtifact.optString("kind") == "url") " · Preview only, full file on PC" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (review.mode == "pick" || review.mode == "rank") {
                    val buttonText = if (review.mode == "pick") {
                        if (choice == id) "Selected ✓" else "Choose $label"
                    } else if (id in ranking) "Rank #${ranking.indexOf(id) + 1} · tap to remove" else "Add to ranking"
                    if (active) FilledTonalButton(onClick = { selectVariant(focusedArtifact) }, enabled = canAnswer,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(buttonText, maxLines = 1) }
                    else OutlinedButton(onClick = { selectVariant(focusedArtifact) }, enabled = canAnswer,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) { Text(buttonText, maxLines = 1) }
                }
                // Horizontal candidate selector strip: tap to focus a candidate on the stage.
                if (artifacts.size > 1) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        artifacts.forEachIndexed { idx, artifact ->
                            val cid = artifact.optString("id", "A")
                            ReviewCandidateChip(
                                index = idx + 1,
                                label = artifact.optString("label", cid),
                                focused = idx == focusIdx,
                                chosen = review.mode == "pick" && choice == cid,
                                rank = if (review.mode == "rank" && cid in ranking) ranking.indexOf(cid) + 1 else null,
                                pinCount = pins.count { it.artifact == cid },
                                onClick = { if (idx != focusIdx) { focused = idx; videoView = null; webView = null } })
                        }
                    }
                }
            }
            if (pins.isNotEmpty() || canAnswer || note.isNotBlank()) {
                Text("Feedback", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            pins.forEachIndexed { i, pin ->
                var pinText by remember(i, pin.x, pin.y) { mutableStateOf(pin.note) }
                val pinArtifactIdx = artifacts.indexOfFirst { it.optString("id") == pin.artifact }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ReviewBadge("${i + 1}", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(pinText, { pinText = it; pins[i] = pin.copy(note = it) },
                        modifier = Modifier.weight(1f), singleLine = true, readOnly = !canAnswer,
                        label = { Text("Pin ${i + 1}" +
                            (if (artifacts.size > 1 && pinArtifactIdx >= 0) " · #${pinArtifactIdx + 1}" else "") +
                            (pin.timestamp?.let { " · %.1fs".format(it) } ?: ""), maxLines = 1) })
                    TextButton(onClick = { pins.removeAt(i) }, enabled = canAnswer, modifier = Modifier.size(48.dp),
                        contentPadding = PaddingValues(0.dp)) { Text("×", style = MaterialTheme.typography.titleMedium) }
                }
            }
            OutlinedTextField(value = note, onValueChange = { note = it },
                modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Overall note") },
                readOnly = !canAnswer)
            if (canAnswer) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { gallery.launch("image/*") }, enabled = !saving && reviewImages.size < 10) {
                        Text("Add images")
                    }
                    if (reviewImages.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text("${reviewImages.size} attached", style = MaterialTheme.typography.labelSmall)
                    }
                }
                reviewImages.forEachIndexed { index, attachment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        val bitmap = remember(attachment) {
                            BitmapFactory.decodeByteArray(attachment.bytes, 0, attachment.bytes.size,
                                BitmapFactory.Options().apply { inSampleSize = 8; inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 })
                                ?.asImageBitmap()
                        }
                        if (bitmap != null) Image(bitmap, attachment.name, Modifier.size(46.dp),
                            contentScale = ContentScale.Crop)
                        Text(attachment.name, Modifier.weight(1f).padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { reviewImages.removeAt(index) }, enabled = !saving) { Text("Remove") }
                    }
                }
            }
        }
        if (fullscreenVideo && focusedArtifact?.optString("kind") == "video") {
            val file = TaskBoardReviewStore.artifactFile(review, focusedArtifact)
            if (file?.isFile == true && file.extension.lowercase() in setOf("mp4", "webm")) {
                Dialog(onDismissRequest = { fullscreenVideo = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Column(Modifier.fillMaxSize().background(Color.Black)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text(focusedArtifact.optString("label", "Video"), color = Color.White,
                                modifier = Modifier.weight(1f), maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { fullscreenVideo = false }) {
                                Text("Close", color = Color.White)
                            }
                        }
                        AndroidView(factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(file.path)
                                setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                                setOnPreparedListener {
                                    seekTo((videoPosition * 1000).toInt())
                                    start()
                                }
                                setOnErrorListener { _, what, extra ->
                                    playbackError = "Cannot decode or play video ($what/$extra). Use the OmniHub stream or transcode to H.264 MP4."
                                    fullscreenVideo = false
                                    true
                                }
                            }
                        }, modifier = Modifier.fillMaxSize())
                    }
                }
            } else {
                fullscreenVideo = false
            }
        }
        // Full-width sticky action bar: prominent primary, More, Reject.
        val primaryLabel = when (review.mode) {
            "pick" -> artifacts.firstOrNull { it.optString("id") == choice }
                ?.let { "Pick " + it.optString("label", it.optString("id")) } ?: "Pick"
            "approve" -> "Approve"
            "rank" -> "Rank"
            else -> "Send"
        }
        Surface(Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 6.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { resolve(when (review.mode) {
                    "pick" -> "picked"
                    "approve" -> "approved"
                    "rank" -> "ranked"
                    else -> "marked"
                }) }, enabled = canAnswer && !saving, modifier = Modifier.weight(2f).heightIn(min = 52.dp)) {
                    Text(if (saving) "Saving…" else primaryLabel, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { resolve("more-variants") }, enabled = canAnswer && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp)) { Text("More", maxLines = 1) }
                OutlinedButton(onClick = { resolve("rejected") }, enabled = canAnswer && !saving,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                    Text("Reject", maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun ReviewCandidateChip(
    index: Int,
    label: String,
    focused: Boolean,
    chosen: Boolean,
    rank: Int?,
    pinCount: Int,
    onClick: () -> Unit,
) {
    val marked = chosen || rank != null
    val shape = MaterialTheme.shapes.small
    Card(onClick = onClick, shape = shape,
        modifier = Modifier.widthIn(min = 72.dp, max = 160.dp).heightIn(min = 48.dp)
            .border(if (focused) 2.dp else 1.dp,
                if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape),
        colors = CardDefaults.cardColors(containerColor = if (marked)
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ReviewBadge("$index", if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                if (marked) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            Column {
                Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(
                    rank?.let { "#$it" },
                    if (chosen) "✓ Chosen" else null,
                    if (pinCount > 0) "$pinCount pin" + (if (pinCount == 1) "" else "s") else null)
                if (meta.isNotEmpty()) Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReviewQueueCard(item: MobileReview, isSelected: Boolean, onClick: () -> Unit) {
    val accent = when {
        item.status != "open" -> TaskBoardColors.muted
        item.mode == "approve" -> TaskBoardColors.green
        item.mode == "pick" -> TaskBoardColors.purple
        item.mode == "rank" -> TaskBoardColors.blue
        else -> TaskBoardColors.orange
    }
    Card(onClick = onClick,
        modifier = Modifier.fillMaxWidth()
            .border(1.dp, accent.copy(alpha = if (isSelected) .8f else .35f),
                RoundedCornerShape(7.dp)),
        shape = RoundedCornerShape(7.dp),
        colors = CardDefaults.cardColors(containerColor = if (isSelected)
            TaskBoardColors.raised else TaskBoardColors.card)) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
            Column(Modifier.weight(1f).padding(horizontal = 9.dp, vertical = 5.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(item.taskId, style = MaterialTheme.typography.labelSmall,
                        color = TaskBoardColors.blue, fontWeight = FontWeight.Bold)
                    Text(item.mode.uppercase(), style = MaterialTheme.typography.labelSmall,
                        color = accent, fontWeight = FontWeight.Bold)
                    Text("R${item.round.optInt("number")}", style = MaterialTheme.typography.labelSmall,
                        color = TaskBoardColors.muted)
                    Spacer(Modifier.weight(1f))
                    Text(if (item.status == "open") "Needs review" else "Resolved",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.status == "open") TaskBoardColors.orange else TaskBoardColors.muted)
                }
                Text(item.title, fontWeight = FontWeight.SemiBold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Text(item.project.name, style = MaterialTheme.typography.labelSmall,
                    color = TaskBoardColors.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReviewStageHeader(review: MobileReview, canAnswer: Boolean) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReviewBadge(review.mode.uppercase(), MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text("${review.taskId} · R${review.round.optInt("number")}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(if (review.status == "resolved") "Resolved · read-only"
                    else if (canAnswer) "Awaiting you" else review.status,
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                    color = if (review.status == "resolved") MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
            }
            review.round.optString("judge").takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            val criteria = review.round.optJSONArray("criteria") ?: JSONArray()
            if (criteria.length() > 0) Text((0 until criteria.length()).joinToString(" · ") { criteria.optString(it) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewBadge(text: String, container: Color, content: Color) {
    Box(Modifier.heightIn(min = 22.dp).background(container, RoundedCornerShape(11.dp))
        .padding(horizontal = 7.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = content)
    }
}

@Composable
private fun ReviewArtifactPreview(
    review: MobileReview,
    artifact: JSONObject,
    pinMode: Boolean,
    videoPosition: Double,
    pins: List<Pair<Int, MobileReviewPin>>,
    onVideoTime: (Double) -> Unit,
    onVideo: (VideoView?) -> Unit,
    onWeb: (WebView?) -> Unit,
    onVideoError: (String) -> Unit,
    onPin: (Offset, IntSize) -> Unit,
    onApproveSwipe: (Int) -> Unit,
) {
    val context = LocalContext.current
    val kind = artifact.optString("kind")
    val localOnly = artifact.optString("storage") == "local"
    val file = TaskBoardReviewStore.artifactFile(review, artifact)
    val canRender = file?.isFile == true
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    var accumulatedSwipe by remember { mutableStateOf(0f) }
    val swipeModifier = if (review.mode == "approve") Modifier.pointerInput(review.taskId) {
        detectHorizontalDragGestures(onHorizontalDrag = { _, amount ->
            accumulatedSwipe += amount
        }, onDragEnd = {
            if (accumulatedSwipe > 110f) onApproveSwipe(1)
            if (accumulatedSwipe < -110f) onApproveSwipe(-1)
            accumulatedSwipe = 0f
        }, onDragCancel = { accumulatedSwipe = 0f })
    } else Modifier
    val stageHeight = (LocalConfiguration.current.screenHeightDp * 0.36f).coerceIn(200f, 340f).dp
    val stageShape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().height(stageHeight).clip(stageShape)
        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .border(if (pinMode) 2.dp else 0.dp,
            if (pinMode) MaterialTheme.colorScheme.tertiary else Color.Transparent, stageShape)
        .then(swipeModifier)) {
        when {
            file == null || !canRender -> {
                Text("Syncing review artifact or poster…",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            kind == "image" || localOnly || kind == "url" -> {
                val bitmap = remember(file.path, file.lastModified()) {
                    runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()
                }
                if (bitmap == null) Text("Syncing preview…", Modifier.align(Alignment.Center))
                else {
                    var zoom by remember(file.path) { mutableStateOf(1f) }
                    Image(bitmap, contentDescription = artifact.optString("label", "Review image"),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom)
                            .pointerInput(file.path) {
                                detectTransformGestures { _, _, change, _ ->
                                    zoom = (zoom * change).coerceIn(1f, 5f)
                                }
                            })
                }
            }
            kind == "video" -> AndroidView(factory = { ctx ->
                VideoView(ctx).apply {
                    setVideoPath(file.path)
                    setMediaController(MediaController(ctx).also { it.setAnchorView(this) })
                    setOnPreparedListener { onVideo(this); it.isLooping = false }
                    setOnErrorListener { _, what, extra ->
                        onVideo(null)
                        onVideoError("Video cannot be decoded or loaded ($what/$extra). Try the OmniHub stream or an H.264 MP4.")
                        true
                    }
                    setOnCompletionListener { onVideoTime(0.0) }
                }
            }, modifier = Modifier.fillMaxSize())
            kind == "html" -> AndroidView(factory = { ctx ->
                WebView(ctx).apply {
                    // No interface/privileged bridge, no file/content cross-origin access.
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.allowFileAccessFromFileURLs = false
                    settings.allowUniversalAccessFromFileURLs = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val uri = request?.url?.toString() ?: return null
                            return if (uri.startsWith("data:") || uri == "about:blank") null
                                else WebResourceResponse("text/plain", "utf-8",
                                    ByteArrayInputStream("Blocked: review HTML has no network/file access".toByteArray()))
                        }
                    }
                    val html = TaskBoardReviewStore.htmlForPreview(file)
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                    onWeb(this)
                }
            }, modifier = Modifier.fillMaxSize())
            else -> Text("Unsupported artifact: $kind", Modifier.align(Alignment.Center))
        }
        if (pinMode) {
            Box(Modifier.fillMaxSize().background(Color.Yellow.copy(alpha = 0.04f))
                .pointerInput(review.taskId, artifact.optString("id")) {
                    size = this.size
                    detectTapGestures(onTap = { onPin(it, size) },
                        onLongPress = { onPin(it, size) })
                })
        }
        if (pins.isNotEmpty()) {
            // Display-only markers at the stored relative point; they do not consume touches.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                pins.forEach { (number, pin) ->
                    Box(Modifier.offset(x = maxWidth * pin.x.toFloat() - 11.dp, y = maxHeight * pin.y.toFloat() - 11.dp)
                        .size(22.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape)
                        .border(1.5.dp, MaterialTheme.colorScheme.onTertiary, CircleShape),
                        contentAlignment = Alignment.Center) {
                        Text("$number", style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiary)
                    }
                }
            }
        }
    }
}
