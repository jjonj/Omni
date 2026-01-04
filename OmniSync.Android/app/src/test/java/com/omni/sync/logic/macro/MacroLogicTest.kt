package com.omni.sync.logic.macro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class MacroLogicTest {

    @Test
    fun `test MacroParser parses simple commands`() {
        val parser = MacroParser()
        val script = """
            send Hello
            sleep 1000
            run notepad.exe
            winactivate Notepad
        """.trimIndent()

        val commands = parser.parse(script)

        assertEquals(4, commands.size)
        assertTrue(commands[0] is MacroCommand.Send)
        assertEquals("Hello", (commands[0] as MacroCommand.Send).keys)
        
        assertTrue(commands[1] is MacroCommand.Sleep)
        assertEquals(1000L, (commands[1] as MacroCommand.Sleep).durationMs)
        
        assertTrue(commands[2] is MacroCommand.Run)
        assertEquals("notepad.exe", (commands[2] as MacroCommand.Run).path)
        
        assertTrue(commands[3] is MacroCommand.WinActivate)
        assertEquals("Notepad", (commands[3] as MacroCommand.WinActivate).title)
    }

    @Test
    fun `test MacroParser ignores comments and empty lines`() {
        val parser = MacroParser()
        val script = """
            ; This is a comment
            send Hello
            
            sleep 500
        """.trimIndent()

        val commands = parser.parse(script)

        assertEquals(2, commands.size)
        assertTrue(commands[0] is MacroCommand.Send)
        assertTrue(commands[1] is MacroCommand.Sleep)
    }
}
