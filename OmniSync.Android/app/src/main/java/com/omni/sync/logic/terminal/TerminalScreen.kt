package com.omni.sync.logic.terminal

/**
 * Minimal VT100/xterm screen emulator used by the Simple terminal mode: it turns raw PTY output
 * (ConPTY cursor moves, erases, scroll regions, alternate screen) into plain text lines with
 * scrollback, without a WebView. Colours and attributes are dropped. Pure Kotlin (no Android
 * dependencies) so it can be tested from a JVM shadow project.
 */
class TerminalScreen(columns: Int = 80, rows: Int = 24, private val maxScrollback: Int = 3000) {
    var columns = columns.coerceAtLeast(2); private set
    var rows = rows.coerceAtLeast(2); private set

    private val scrollback = ArrayDeque<String>()
    private var grid = blankGrid()
    private var cx = 0
    private var cy = 0
    private var wrapPending = false
    private var top = 0
    private var bottom = this.rows - 1
    private var savedX = 0
    private var savedY = 0
    private var mainGrid: Array<CharArray>? = null // set while the alternate screen is active
    private var mainCursor = 0 to 0

    private enum class Mode { TEXT, ESC, CSI, STRING, STRING_ESC, CHARSET }
    private var mode = Mode.TEXT
    private val params = StringBuilder()

    /** Number of times content changed; cheap change detection for UIs. */
    var revision = 0L; private set

    fun resize(columns: Int, rows: Int) {
        this.columns = columns.coerceAtLeast(2)
        this.rows = rows.coerceAtLeast(2)
        reset()
    }

    fun reset() {
        scrollback.clear()
        grid = blankGrid()
        cx = 0; cy = 0; wrapPending = false
        top = 0; bottom = rows - 1
        savedX = 0; savedY = 0
        mainGrid = null
        mode = Mode.TEXT
        params.clear()
        revision++
    }

    fun feed(text: String) {
        for (ch in text) step(ch)
        revision++
    }

    /** Scrollback followed by the visible screen, trailing blanks trimmed. */
    fun lines(): List<String> {
        val screen = grid.map { String(it).trimEnd() }
        val lastUsed = screen.indexOfLast { it.isNotEmpty() }
        return scrollback.toList() + screen.take(lastUsed + 1)
    }

    fun text(): String = lines().joinToString("\n")

    // ---------- parser ----------

    private fun step(ch: Char) {
        when (mode) {
            Mode.TEXT -> text(ch)
            Mode.ESC -> escape(ch)
            Mode.CSI -> {
                if (ch in '@'..'~') {
                    csi(ch, params.toString())
                    params.clear()
                    mode = Mode.TEXT
                } else if (params.length < 64) {
                    params.append(ch)
                }
            }
            Mode.STRING -> when (ch) {
                '\u0007' -> mode = Mode.TEXT
                '\u001b' -> mode = Mode.STRING_ESC
                else -> Unit
            }
            Mode.STRING_ESC -> mode = if (ch == '\\') Mode.TEXT else Mode.STRING
            Mode.CHARSET -> mode = Mode.TEXT
        }
    }

    private fun text(ch: Char) {
        when (ch) {
            '\u001b' -> mode = Mode.ESC
            '\r' -> { cx = 0; wrapPending = false }
            '\n', '\u000b', '\u000c' -> { lineFeed(); wrapPending = false }
            '\b' -> { if (cx > 0) cx--; wrapPending = false }
            '\t' -> cx = minOf(columns - 1, (cx / 8 + 1) * 8)
            else -> if (ch >= ' ' && ch != '\u007f') put(ch)
        }
    }

    private fun escape(ch: Char) {
        mode = Mode.TEXT
        when (ch) {
            '[' -> { params.clear(); mode = Mode.CSI }
            ']', 'P', '_', '^', 'X' -> mode = Mode.STRING
            '(', ')', '*', '+', '#', '%' -> mode = Mode.CHARSET
            '7' -> { savedX = cx; savedY = cy }
            '8' -> { cx = savedX.coerceIn(0, columns - 1); cy = savedY.coerceIn(0, rows - 1); wrapPending = false }
            'D' -> lineFeed()
            'E' -> { cx = 0; lineFeed() }
            'M' -> if (cy == top) scrollDown(1) else if (cy > 0) cy--
            'c' -> reset()
        }
    }

    private fun csi(final: Char, raw: String) {
        val private = raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("=")
        val values = raw.trimStart('?', '>', '=').split(';').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int, default: Int = 1) = values.getOrNull(i)?.takeIf { it > 0 } ?: default
        wrapPending = false
        when (final) {
            'A' -> cy = maxOf(0, cy - arg(0))
            'B', 'e' -> cy = minOf(rows - 1, cy + arg(0))
            'C', 'a' -> cx = minOf(columns - 1, cx + arg(0))
            'D' -> cx = maxOf(0, cx - arg(0))
            'E' -> { cy = minOf(rows - 1, cy + arg(0)); cx = 0 }
            'F' -> { cy = maxOf(0, cy - arg(0)); cx = 0 }
            'G', '`' -> cx = (arg(0) - 1).coerceIn(0, columns - 1)
            'd' -> cy = (arg(0) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> { cy = (arg(0) - 1).coerceIn(0, rows - 1); cx = (arg(1) - 1).coerceIn(0, columns - 1) }
            'J' -> when (values.getOrNull(0) ?: 0) {
                0 -> { clearRow(cy, cx, columns); for (r in cy + 1 until rows) clearRow(r) }
                1 -> { for (r in 0 until cy) clearRow(r); clearRow(cy, 0, cx + 1) }
                2 -> for (r in 0 until rows) clearRow(r)
                3 -> scrollback.clear()
            }
            'K' -> when (values.getOrNull(0) ?: 0) {
                0 -> clearRow(cy, cx, columns)
                1 -> clearRow(cy, 0, cx + 1)
                2 -> clearRow(cy)
            }
            'X' -> clearRow(cy, cx, minOf(columns, cx + arg(0)))
            'P' -> {
                val row = grid[cy]
                val n = minOf(arg(0), columns - cx)
                System.arraycopy(row, cx + n, row, cx, columns - cx - n)
                row.fill(' ', columns - n, columns)
            }
            '@' -> {
                val row = grid[cy]
                val n = minOf(arg(0), columns - cx)
                System.arraycopy(row, cx, row, cx + n, columns - cx - n)
                row.fill(' ', cx, cx + n)
            }
            'L' -> if (cy in top..bottom) shiftDown(cy, arg(0))
            'M' -> if (cy in top..bottom) shiftUp(cy, arg(0), keep = false)
            'S' -> if (!private) scrollUp(arg(0))
            'T' -> if (!private) scrollDown(arg(0))
            'r' -> if (!private) {
                val t = (arg(0) - 1).coerceIn(0, rows - 1)
                val b = (arg(1, rows) - 1).coerceIn(0, rows - 1)
                if (t < b) { top = t; bottom = b } else { top = 0; bottom = rows - 1 }
                cx = 0; cy = 0
            }
            's' -> if (!private) { savedX = cx; savedY = cy }
            'u' -> if (!private) { cx = savedX.coerceIn(0, columns - 1); cy = savedY.coerceIn(0, rows - 1) }
            'h', 'l' -> if (private && values.any { it == 1049 || it == 1047 || it == 47 }) alternate(final == 'h')
        }
    }

    // ---------- screen operations ----------

    private fun put(ch: Char) {
        if (wrapPending) {
            cx = 0
            lineFeed()
            wrapPending = false
        }
        grid[cy][cx] = ch
        if (cx == columns - 1) wrapPending = true else cx++
    }

    private fun lineFeed() {
        if (cy == bottom) scrollUp(1) else if (cy < rows - 1) cy++
    }

    private fun scrollUp(n: Int) = shiftUp(top, n, keep = top == 0 && mainGrid == null)

    private fun scrollDown(n: Int) = shiftDown(top, n)

    /** Moves rows from..bottom up by n; rows leaving the region go to scrollback when keep is set. */
    private fun shiftUp(from: Int, n: Int, keep: Boolean) {
        repeat(minOf(n, bottom - from + 1)) {
            val gone = grid[from]
            if (keep) {
                scrollback.addLast(String(gone).trimEnd())
                while (scrollback.size > maxScrollback) scrollback.removeFirst()
            }
            for (r in from until bottom) grid[r] = grid[r + 1]
            grid[bottom] = CharArray(columns) { ' ' }
        }
    }

    private fun shiftDown(from: Int, n: Int) {
        repeat(minOf(n, bottom - from + 1)) {
            for (r in bottom downTo from + 1) grid[r] = grid[r - 1]
            grid[from] = CharArray(columns) { ' ' }
        }
    }

    private fun clearRow(r: Int, from: Int = 0, to: Int = columns) {
        if (r !in 0 until rows) return
        grid[r].fill(' ', from.coerceIn(0, columns), to.coerceIn(0, columns))
    }

    private fun alternate(enter: Boolean) {
        if (enter && mainGrid == null) {
            mainGrid = grid
            mainCursor = cx to cy
            grid = blankGrid()
            cx = 0; cy = 0
        } else if (!enter && mainGrid != null) {
            grid = mainGrid!!
            mainGrid = null
            cx = mainCursor.first; cy = mainCursor.second
        }
        top = 0; bottom = rows - 1
    }

    private fun blankGrid() = Array(rows) { CharArray(columns) { ' ' } }
}
