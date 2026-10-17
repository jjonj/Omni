package com.omni.test

import com.omni.sync.logic.terminal.TerminalScreen
import kotlin.system.exitProcess

private val failures = mutableListOf<String>()

private fun check(name: String, actual: Any?, expected: Any?) {
    if (actual == expected) println("  PASS $name")
    else { println("  FAIL $name\n    expected: $expected\n    actual:   $actual"); failures += name }
}

private const val E = "\u001b"

fun main() {
    println("Plain text and wrapping")
    TerminalScreen(10, 4).apply {
        feed("hello\r\nworld")
        check("two lines", lines(), listOf("hello", "world"))
        feed("\r\n0123456789AB")
        check("wraps at column limit", lines().takeLast(2), listOf("0123456789", "AB"))
    }

    println("Cursor positioning and erase (ConPTY redraw)")
    TerminalScreen(20, 5).apply {
        feed("${E}[2J${E}[1;1Hfirst line${E}[2;1Hsecond${E}[1;7H${E}[Kroot")
        check("CUP + EL overwrite", lines(), listOf("first root", "second"))
        feed("${E}[2;1H${E}[2Kreplaced${E}[?25l${E}[38;2;255;0;0mred${E}[0m")
        check("SGR and private modes ignored", lines(), listOf("first root", "replacedred"))
    }

    println("Scrollback")
    TerminalScreen(10, 3).apply {
        feed((1..6).joinToString("\r\n") { "line$it" })
        check("lines scroll into history", lines(), (1..6).map { "line$it" })
    }

    println("Alternate screen")
    TerminalScreen(10, 3).apply {
        feed("shell\r\n")
        feed("${E}[?1049h${E}[Hfullscreen")
        check("alt screen shows only its content", lines(), listOf("fullscreen"))
        feed("${E}[?1049l")
        check("leaving restores main screen", lines(), listOf("shell"))
    }

    println("Scroll region (TUI status line stays put)")
    TerminalScreen(10, 4).apply {
        feed("${E}[4;1Hstatus${E}[1;3r${E}[1;1Ha\r\nb\r\nc\r\nd")
        check("region scrolls, status kept", lines().takeLast(4), listOf("b", "c", "d", "status"))
    }

    println("OSC title and split escape sequences")
    TerminalScreen(20, 3).apply {
        feed("${E}]0;window title\u0007ok${E}[")
        feed("3Gx")
        check("OSC skipped, CSI split across chunks", lines(), listOf("okx"))
    }

    println("Delete / insert chars")
    TerminalScreen(10, 2).apply {
        feed("abcdef${E}[1;2H${E}[2P")
        check("DCH", lines(), listOf("adef"))
        feed("${E}[2@XY")
        check("ICH", lines(), listOf("aXYdef"))
    }

    println(if (failures.isEmpty()) "\nALL PASSED" else "\nFAILED: $failures")
    exitProcess(if (failures.isEmpty()) 0 else 1)
}
