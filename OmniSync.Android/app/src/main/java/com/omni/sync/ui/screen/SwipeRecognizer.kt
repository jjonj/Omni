package com.omni.sync.ui.screen

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.futo.ml.inference.SwipeDecoder
import java.io.File

/** JNI companion for the public FUTO ITrie ABI. This lexicon must outlive SwipeDecoder. */
internal object SwipeNativeTrie {
    init { System.loadLibrary("omni_swipe_trie") }
    external fun createNative(dictionaryPath: String): Long
    external fun destroyNative(handle: Long)
}

/**
 * Opt-in prototype only. No system input method, network calls or persistent typing logs.
 * Model initialization and inference are serialized: SwipeDecoder is not thread-safe.
 */
internal class SwipeRecognizer(private val context: Context) {
    private val mutex = Mutex()
    private var decoder: SwipeDecoder? = null
    private var trieHandle: Long = 0

    suspend fun prepare() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (decoder != null) return@withLock
            val dest = context.getDir("futo_swipe_v1", Context.MODE_PRIVATE)
            val required = listOf(
                "honorable_sturgeon/model_fp32.pte",
                "honorable_sturgeon/metadata.json",
                "magic_macaw/model_fp32.pte",
                "magic_macaw/metadata.json",
                "hungry_jellyfish/context_lm.pte",
                "hungry_jellyfish/metadata.json",
                "hungry_jellyfish/vocab.txt",
                "en_words.tsv"
            )
            for (name in required) {
                val file = File(dest, name)
                file.parentFile?.mkdirs()
                context.assets.open("futo_swipe/$name").use { asset ->
                    if (file.length() != asset.available().toLong()) {
                        val temporary = File(file.absolutePath + ".tmp")
                        temporary.outputStream().use { out -> asset.copyTo(out) }
                        check(temporary.renameTo(file)) { "Failed to stage $name" }
                    }
                }
            }
            val trie = SwipeNativeTrie.createNative(File(dest, "en_words.tsv").absolutePath)
            require(trie != 0L) { "English FUTO lexicon could not be loaded" }
            trieHandle = trie
            try {
                val candidate = SwipeDecoder(
                    encoderPath = File(dest, "honorable_sturgeon/model_fp32.pte").absolutePath,
                    decoderPath = File(dest, "magic_macaw/model_fp32.pte").absolutePath,
                    threads = 2,
                    beamWidth = 300,
                    topK = 4,
                    lmModelPath = File(dest, "hungry_jellyfish/context_lm.pte").absolutePath,
                    lmVocabPath = File(dest, "hungry_jellyfish/vocab.txt").absolutePath
                )
                try {
                    val letters = "abcdefghijklmnopqrstuvwxyz"
                    val positions = letters.map { checkNotNull(SwipeGeometry.center(it)) }
                    check(candidate.setMode(
                        letters = letters,
                        cx = positions.map { it.first }.toFloatArray(),
                        cy = positions.map { it.second }.toFloatArray(),
                        tries = longArrayOf(trie)
                    )) { "Unable to configure English QWERTY recognition" }
                    decoder = candidate
                } catch (e: Throwable) {
                    candidate.close()
                    throw e
                }
            } catch (e: Throwable) {
                SwipeNativeTrie.destroyNative(trieHandle)
                trieHandle = 0
                throw e
            }
        }
    }

    suspend fun recognize(path: List<SwipePoint>, contextWords: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val active = checkNotNull(decoder) { "FUTO Swipe is not ready" }
                check(path.size > 2) { "Swipe too short" }
                active.setContext(contextWords.takeLast(16))
                active.recognize(
                    path.map { it.x }.toFloatArray(),
                    path.map { it.y }.toFloatArray(),
                    path.map { it.ms }.toFloatArray(),
                    topK = 4, beamWidth = 300
                ).map { it.word }.filter { it.isNotBlank() }.distinct()
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            decoder?.close()
            decoder = null
            if (trieHandle != 0L) {
                SwipeNativeTrie.destroyNative(trieHandle)
                trieHandle = 0
            }
        }
    }
}
