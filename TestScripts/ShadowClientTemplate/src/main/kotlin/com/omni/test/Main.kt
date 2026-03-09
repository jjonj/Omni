package com.omni.test

/**
 * SHADOW KOTLIN CLIENT TEMPLATE
 * Use this to test Android logic files directly on Windows.
 */

// --- ANDROID SHIMS ---
// Re-implement or mock Android classes used by your target logic
object Log {
    fun d(tag: String, msg: String) = println("[$tag][DEBUG] $msg")
    fun i(tag: String, msg: String) = println("[$tag][INFO] $msg")
    fun e(tag: String, msg: String, e: Throwable? = null) {
        println("[$tag][ERROR] $msg")
        e?.printStackTrace()
    }
}

fun main(args: Array<String>) {
    println("Shadow Kotlin Client started.")
    println("1. Add real source paths to build.gradle.kts")
    println("2. Instantiate your target logic here.")
    println("3. Run via .\\OmniSync.Android\\gradlew.bat -p TestScripts\\ShadowClientTemplate run")
}
