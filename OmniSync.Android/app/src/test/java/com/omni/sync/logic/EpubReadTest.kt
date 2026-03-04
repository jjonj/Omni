package com.omni.sync.logic

import org.junit.Test
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream
import java.util.Scanner

class EpubReadTest {

    @Test
    fun testPullTextFromEpub() {
        val epubPath = """B:\GDrive\Books\Books\Misc\1,001 Facts that Will Scare the S#_t Out - McNeal_ Cary.epub"""
        val epubFile = File(epubPath)
        
        println("Testing EPUB path: $epubPath")
        if (!epubFile.exists()) {
             // Let's try to list the folder contents if it fails
             println("ERROR: File not found. Checking parent directory: " + epubFile.parent)
             File(epubFile.parent ?: ".").listFiles()?.forEach { println(" - " + it.name) }
        }
        assertTrue("EPUB file should exist at $epubPath", epubFile.exists())
        
        var foundText = false
        var textSnippet = ""
        
        ZipInputStream(FileInputStream(epubFile)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name.endsWith(".html") || entry.name.endsWith(".xhtml")) {
                    println("Found content entry: ${entry.name}")
                    
                    val scanner = Scanner(zis)
                    val sb = StringBuilder()
                    // Read up to 1000 characters to find some "real" text
                    while (scanner.hasNextLine() && sb.length < 1000) {
                        val line = scanner.nextLine()
                        // Strip HTML tags roughly for the snippet
                        val cleanLine = line.replace("<[^>]*>".toRegex(), " ").trim()
                        if (cleanLine.isNotEmpty()) {
                            sb.append(cleanLine).append(" ")
                        }
                    }
                    
                    if (sb.isNotEmpty()) {
                        textSnippet = sb.toString().trim()
                        foundText = true
                        break
                    }
                }
                entry = zis.nextEntry
            }
        }
        
        assertTrue("Should have found some text in the EPUB HTML/XHTML files", foundText)
        println("Successfully pulled text from EPUB!")
        println("--- Snippet Start ---")
        println(textSnippet.take(500))
        println("--- Snippet End ---")
    }
}
