package com.milenote.android.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * i18n 自动漏译检查。
 *
 * 约定（见 [I18n] / [tr]）：中文原文即 key，英文在调用点就地给出，
 * 形如 `tr("设置", "Settings")`；带占位符时 `%s` / `%d` 的数量必须一致。
 * 本测试扫描 `app/src/main/kotlin` 与 `domain/src/main/kotlin` 里所有 `tr(` 调用，
 * 随 `:app:testDebugUnitTest` 自动运行，防止新增文案时漏写英文/写错占位符。
 *
 * 解析规则（针对源码文本，不依赖编译产物）：
 * - 跳过注释（含 KDoc）与字符/字符串字面量里的伪 `tr(`，跳过 `fun tr(` 函数定义；
 * - 参数按顶层逗号切分，字符串字面量支持 `+` 拼接，支持多行调用与嵌套 `tr(...)`；
 * - 任一参数不是字符串常量（字符串模板、变量等）就跳过该调用，不判失败；
 * - 调用里的 `tr` 若被 `fun` 修饰视为定义；`obj.tr(...)` 这类限定调用仍按调用检查。
 *
 * 工作目录（Gradle 默认是 `app/`，IDE 可能是工程根或仓库根）不固定：
 * 从 `user.dir` 逐级向上，并在每一级额外尝试 `MileNoteAndroid/` 子目录，
 * 以同时存在 `app/src/main/kotlin` 与 `domain/src/main/kotlin` 作为工程根标志。
 */
class I18nCoverageTest {

    /** 高置信白名单：仅当「文件 + 中文 + 英文」完全一致时豁免，避免误伤/被滥用。 */
    private data class Whitelist(val fileName: String, val zh: String, val en: String)

    private data class TrCall(
        val path: String,
        val line: Int,
        val zh: String?,
        val en: String?,
        val argCount: Int,
    )

    private data class ScanResult(val fileCount: Int, val calls: List<TrCall>)

    private enum class Kind { STRING, IDENT, LPAREN, RPAREN, COMMA, PLUS, DOT, OTHER }

    private data class Token(val kind: Kind, val value: String?, val line: Int)

    /** 懒扫描一次，几个测试共用；源码有问题时在第一个测试里报出根目录错误。 */
    private val scan: ScanResult by lazy { scanAll() }

    @Test
    fun `scanner finds tr calls in app and domain sources`() {
        val result = scan
        val bothLiterals = result.calls.count { it.zh != null && it.en != null }
        val skipped = result.calls.count { it.zh == null || it.en == null }
        println(
            "[i18n] 扫描 ${result.fileCount} 个 Kotlin 文件，解析 ${result.calls.size} 个 tr() 调用；" +
                "中英均为字面量 $bothLiterals 个，非常量参数跳过 $skipped 个",
        )
        // 源码规模兜底：解析器/根目录一旦失效会静默扫到 0 个调用，必须让测试失败。
        assertTrue("扫描到的 tr() 调用过少（${result.calls.size}），疑似源码根目录或解析器失效", result.calls.size >= 1000)
        assertTrue("解析出的中英字面量过少（$bothLiterals），疑似常量串解析失效", bothLiterals >= 1000)
        assertTrue("扫描到的 Kotlin 文件过少（${result.fileCount}）", result.fileCount >= 100)
        val domainCalls = result.calls.count { it.path.startsWith("domain/") }
        assertTrue("domain 层 tr() 调用过少（$domainCalls），疑似未扫描到 ../domain/src/main/kotlin", domainCalls >= 50)
    }

    /**
     * 解析器自检：用临时目录里的人工样例覆盖注释伪调用、多行调用、`+` 拼接、
     * 字符串模板（非常量，应跳过）与 `fun tr(` 定义，证明上面的规则测试不是空转。
     */
    @Test
    fun `scanner handles comments multiline concatenation and templates`() {
        val root = Files.createTempDirectory("i18n-coverage").toFile()
        try {
            val appRoot = File(root, "app/src/main/kotlin/sample").apply { mkdirs() }
            val domainRoot = File(root, "domain/src/main/kotlin/sample").apply { mkdirs() }
            File(appRoot, "Sample.kt").writeText(APP_SAMPLE)
            File(domainRoot, "DomainSample.kt").writeText(DOMAIN_SAMPLE)

            val result = scanDirectories(appRoot, domainRoot, root)
            assertEquals("注释/函数定义/字符串里的伪 tr() 不应计数", 7, result.calls.size)

            val appCalls = result.calls.filter { it.path.startsWith("app/") }
            assertEquals(6, appCalls.size)
            assertEquals(
                listOf("设置", "共 %d 条记录", "漏译", "占位符 %d", "拼接文案", null),
                appCalls.map { it.zh },
            )
            assertEquals(
                listOf("Settings", "%d records", "漏译", "no placeholder", "Concatenated", "dynamic"),
                appCalls.map { it.en },
            )
            assertTrue("行号解析失败：${appCalls.map { it.line }}", appCalls.all { it.line > 0 })
            assertEquals(1, result.calls.count { it.path.startsWith("domain/") })
        } finally {
            root.deleteRecursively()
        }
    }

    /** 工作目录自适应：Gradle（`app/`）、工程根、仓库根都能向上找回 `MileNoteAndroid/`。 */
    @Test
    fun `locates project root from gradle and ide working directories`() {
        val workspace = Files.createTempDirectory("i18n-root").toFile()
        try {
            val project = File(workspace, "MileNoteAndroid")
            File(project, "app/src/main/kotlin").mkdirs()
            File(project, "domain/src/main/kotlin").mkdirs()
            val expected = project.canonicalPath
            for (start in listOf(File(project, "app"), project, workspace)) {
                val roots = locateSourceRoots(start)
                assertEquals("从 $start 找不到工程根", expected, roots.project.canonicalPath)
                assertEquals(File(project, "app/src/main/kotlin").canonicalPath, roots.app.canonicalPath)
                assertEquals(File(project, "domain/src/main/kotlin").canonicalPath, roots.domain.canonicalPath)
            }
        } finally {
            workspace.deleteRecursively()
        }
    }

    /** 规则 1：中文（含 CJK 表意文字）必须配英文；英文里不得再出现中文。 */
    @Test
    fun `every Chinese tr literal has a non-Chinese English translation`() {
        var examined = 0
        val problems = scan.calls.mapNotNull { call ->
            val zh = call.zh ?: return@mapNotNull null
            if (!hasCjkIdeograph(zh)) return@mapNotNull null
            val en = call.en ?: return@mapNotNull null // 非常量参数：按约定跳过
            examined++
            when {
                en.isBlank() ->
                    "${call.path}:${call.line} 缺少英文：tr(\"$zh\", ...)"
                hasCjkText(en) && !isWhitelisted(call, zh, en) ->
                    "${call.path}:${call.line} 英文含中文（疑似沿用中文原文）：tr(\"$zh\", \"$en\")"
                else -> null
            }
        }
        // 防规则空转：真实源码里中文文案远不止这个数，一条都没检查到说明解析失效。
        assertTrue("检查到的中文文案过少（$examined）", examined >= 1000)
        failOn(problems, "中文文案缺少英文/英文含中文")
    }

    /** 规则 2：中文含 CJK 时，中英不得完全相同（典型的「复制粘贴忘翻译」）。 */
    @Test
    fun `Chinese and English literals are not identical`() {
        var examined = 0
        val problems = scan.calls.mapNotNull { call ->
            val zh = call.zh ?: return@mapNotNull null
            val en = call.en ?: return@mapNotNull null
            if (!hasCjkIdeograph(zh)) return@mapNotNull null
            examined++
            if (zh == en) {
                "${call.path}:${call.line} 中英文完全相同：tr(\"$zh\", \"$en\")"
            } else {
                null
            }
        }
        assertTrue("检查到的中英成对文案过少（$examined）", examined >= 1000)
        failOn(problems, "中英文完全相同")
    }

    /** 规则 3：% 格式占位符个数必须一致（`10% AC` 这类普通百分号不计入）。 */
    @Test
    fun `format placeholder counts match between languages`() {
        var checked = 0
        val problems = scan.calls.mapNotNull { call ->
            val zh = call.zh ?: return@mapNotNull null
            val en = call.en ?: return@mapNotNull null
            val zhCount = PLACEHOLDER.findAll(zh).count()
            val enCount = PLACEHOLDER.findAll(en).count()
            if (zhCount > 0 || enCount > 0) checked++
            if (zhCount != enCount) {
                "${call.path}:${call.line} 占位符数量不一致（中文 $zhCount 个 / 英文 $enCount 个）：" +
                    "tr(\"$zh\", \"$en\")"
            } else {
                null
            }
        }
        // 防正则失效空转：真实文案里带占位符的调用远不止这个数。
        assertTrue("识别到含占位符的调用过少（$checked），疑似占位符正则失效", checked >= 50)
        failOn(problems, "% 占位符数量不一致")
    }

    private fun failOn(problems: List<String>, title: String) {
        if (problems.isEmpty()) return
        val total = scan.calls.size
        fail(
            buildString {
                appendLine("[i18n] $title：${problems.size} 处违规（共检查 $total 个 tr() 调用）")
                problems.forEach { appendLine("  $it") }
            },
        )
    }

    private fun isWhitelisted(call: TrCall, zh: String, en: String): Boolean =
        WHITELIST.any { it.fileName == call.path.substringAfterLast('/') && it.zh == zh && it.en == en }

    // ------------------------------------------------------------------ 扫描

    private fun scanAll(): ScanResult {
        val roots = locateSourceRoots()
        return scanDirectories(roots.app, roots.domain, roots.project)
    }

    /** 扫描两个源码根下的所有 `.kt`；[projectRoot] 只用于失败信息里的相对路径。 */
    private fun scanDirectories(appRoot: File, domainRoot: File, projectRoot: File): ScanResult {
        val files = (collectKotlinFiles(appRoot) + collectKotlinFiles(domainRoot))
            .distinct()
            .sortedBy { it.path }
        assertTrue("未找到任何 Kotlin 源文件（app=$appRoot, domain=$domainRoot）", files.isNotEmpty())
        val calls = mutableListOf<TrCall>()
        for (file in files) {
            calls += scanFile(file, file.relativeTo(projectRoot).path)
        }
        return ScanResult(files.size, calls)
    }

    private data class SourceRoots(val project: File, val app: File, val domain: File)

    /** 定位测试发起时工作目录（Gradle 默认 `app/`，IDE 可能是工程根或仓库根）对应的工程根。 */
    private fun locateSourceRoots(): SourceRoots =
        locateSourceRoots(File(System.getProperty("user.dir") ?: ".").absoluteFile)

    /**
     * 定位 `MileNoteAndroid/` 工程根及其 `app/src/main/kotlin` 与 `domain/src/main/kotlin`。
     *
     * 从 [start] 逐级向上找，并在每一级同时尝试本目录和 `MileNoteAndroid/` 子目录，
     * 于是 `MileNoteAndroid/app`（Gradle 单测）、`MileNoteAndroid`（工程根/IDE）、
     * 仓库根（IDE 打开父目录）三种工作目录都能命中。
     */
    private fun locateSourceRoots(start: File): SourceRoots {
        val tried = mutableListOf<String>()
        var dir: File? = start.absoluteFile
        while (true) {
            val current: File = dir ?: break
            for (base in listOf(current, File(current, "MileNoteAndroid"))) {
                tried += base.path
                val app = File(base, "app/src/main/kotlin")
                val domain = File(base, "domain/src/main/kotlin")
                if (app.isDirectory && domain.isDirectory) return SourceRoots(base, app, domain)
            }
            dir = current.parentFile
        }
        throw AssertionError(
            buildString {
                appendLine("找不到 MileNoteAndroid 工程根目录（需要同时存在 app/src/main/kotlin 与 domain/src/main/kotlin）。")
                appendLine("user.dir=${System.getProperty("user.dir")}")
                appendLine("已尝试：")
                tried.forEach { appendLine("  $it") }
            },
        )
    }

    private fun collectKotlinFiles(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun scanFile(file: File, relativePath: String): List<TrCall> {
        val tokens = Lexer(file.readText()).lex()
        val calls = mutableListOf<TrCall>()
        for (i in 0 until tokens.size - 1) {
            val token = tokens[i]
            if (token.kind != Kind.IDENT || token.value != "tr") continue
            if (tokens[i + 1].kind != Kind.LPAREN) continue
            val previous = tokens.getOrNull(i - 1)
            // `fun tr(zh: String, en: String)` 是定义，不是调用；KDoc 示例在注释里已被词法器丢弃。
            if (previous != null && previous.kind == Kind.IDENT && previous.value == "fun") continue
            val args = splitTopLevelArgs(tokens, i + 1) ?: continue
            calls += TrCall(
                path = relativePath,
                line = token.line,
                zh = args.getOrNull(0)?.let(::constantString),
                en = args.getOrNull(1)?.let(::constantString),
                argCount = args.size,
            )
            // 不跳过已消费的 token：第三个及之后的实参里可能还嵌套着 tr(...) 调用。
        }
        return calls
    }

    /** 返回括号内的顶层参数列表；括号不配对（语法错误源码）时返回 null 并跳过。 */
    private fun splitTopLevelArgs(tokens: List<Token>, lparenIndex: Int): List<List<Token>>? {
        val args = mutableListOf<MutableList<Token>>()
        var current = mutableListOf<Token>()
        var depth = 1
        var i = lparenIndex + 1
        while (i < tokens.size) {
            val token = tokens[i]
            when (token.kind) {
                Kind.LPAREN -> {
                    depth++
                    current += token
                }
                Kind.RPAREN -> {
                    depth--
                    if (depth == 0) {
                        args += current
                        return args
                    }
                    current += token
                }
                Kind.COMMA -> if (depth == 1) {
                    args += current
                    current = mutableListOf()
                } else {
                    current += token
                }
                else -> current += token
            }
            i++
        }
        return null
    }

    /**
     * 把参数求值为字符串常量：`"a"`、`"a" + "b"`、`("a" + "b")` 均可；
     * 含字符串模板（`$x` / `${...}`）或其他表达式时返回 null（非常量，跳过不判失败）。
     */
    private fun constantString(tokens: List<Token>): String? {
        if (tokens.isEmpty()) return null
        val sb = StringBuilder()
        var expectString = true
        for (token in tokens) {
            if (expectString) {
                if (token.kind != Kind.STRING || token.value == null) return null
                sb.append(token.value)
                expectString = false
            } else {
                if (token.kind != Kind.PLUS) return null
                expectString = true
            }
        }
        return if (expectString) null else sb.toString()
    }

    /** 极简 Kotlin 词法器：只需要字符串字面量与括号/逗号/加号，注释、字符字面量整体跳过。 */
    private class Lexer(private val src: String) {
        private val tokens = mutableListOf<Token>()
        private var i = 0
        private var line = 1

        fun lex(): List<Token> {
            lexCode(stopAtBrace = false, initialDepth = 0)
            return tokens
        }

        private fun lexCode(stopAtBrace: Boolean, initialDepth: Int) {
            var depth = initialDepth
            while (i < src.length) {
                val c = src[i]
                when {
                    c == '/' && i + 1 < src.length && src[i + 1] == '/' -> skipLineComment()
                    c == '/' && i + 1 < src.length && src[i + 1] == '*' -> skipBlockComment()
                    c == '"' -> lexString()
                    c == '\'' -> skipCharLiteral()
                    isIdentStart(c) -> lexIdentifier()
                    c == '(' -> {
                        tokens += Token(Kind.LPAREN, null, line)
                        i++
                    }
                    c == ')' -> {
                        tokens += Token(Kind.RPAREN, null, line)
                        i++
                    }
                    c == ',' -> {
                        tokens += Token(Kind.COMMA, null, line)
                        i++
                    }
                    c == '+' -> {
                        tokens += Token(Kind.PLUS, null, line)
                        i++
                    }
                    c == '.' -> {
                        tokens += Token(Kind.DOT, null, line)
                        i++
                    }
                    c == '{' -> {
                        if (stopAtBrace) depth++
                        tokens += Token(Kind.OTHER, "{", line)
                        i++
                    }
                    c == '}' -> {
                        if (stopAtBrace) {
                            depth--
                            if (depth == 0) {
                                i++
                                return
                            }
                        }
                        tokens += Token(Kind.OTHER, "}", line)
                        i++
                    }
                    else -> {
                        if (c == '\n') line++
                        i++
                    }
                }
            }
        }

        private fun skipLineComment() {
            while (i < src.length && src[i] != '\n') i++
        }

        private fun skipBlockComment() {
            i += 2
            var depth = 1
            while (i < src.length && depth > 0) {
                when {
                    src.startsWith("/*", i) -> {
                        depth++
                        i += 2
                    }
                    src.startsWith("*/", i) -> {
                        depth--
                        i += 2
                    }
                    else -> {
                        if (src[i] == '\n') line++
                        i++
                    }
                }
            }
        }

        /** 字符字面量不含 `tr(`，也不会影响括号配对，整体跳过。 */
        private fun skipCharLiteral() {
            i++
            while (i < src.length) {
                when (src[i]) {
                    '\\' -> i += 2
                    '\'' -> {
                        i++
                        return
                    }
                    '\n' -> {
                        line++
                        i++
                    }
                    else -> i++
                }
            }
        }

        private fun lexIdentifier() {
            val start = i
            while (i < src.length && isIdentPart(src[i])) i++
            tokens += Token(Kind.IDENT, src.substring(start, i), line)
        }

        private fun lexString() {
            val startLine = line
            val raw = src.startsWith("\"\"\"", i)
            i += if (raw) 3 else 1
            val text = StringBuilder()
            var constant = true
            while (i < src.length) {
                val c = src[i]
                if (raw) {
                    if (src.startsWith("\"\"\"", i)) {
                        i += 3
                        break
                    }
                    if (c == '$') {
                        constant = false
                        readTemplate()
                    } else {
                        if (c == '\n') line++
                        text.append(c)
                        i++
                    }
                } else {
                    when {
                        c == '\\' -> text.append(readEscape())
                        c == '"' -> {
                            i++
                            break
                        }
                        c == '$' -> {
                            constant = false
                            readTemplate()
                        }
                        else -> {
                            if (c == '\n') line++
                            text.append(c)
                            i++
                        }
                    }
                }
            }
            tokens += Token(Kind.STRING, if (constant) text.toString() else null, startLine)
        }

        /** 位于 `$`：`${...}` 递归按代码解析（里面的嵌套 tr() 也能被扫到），`$name` 只跳过标识符。 */
        private fun readTemplate() {
            if (i + 1 < src.length && src[i + 1] == '{') {
                i += 2
                lexCode(stopAtBrace = true, initialDepth = 1)
            } else {
                i++
                while (i < src.length && isIdentPart(src[i])) i++
            }
        }

        private fun readEscape(): String {
            if (i + 1 >= src.length) {
                i++
                return ""
            }
            return when (val escaped = src[i + 1]) {
                'n' -> {
                    i += 2
                    "\n"
                }
                't' -> {
                    i += 2
                    "\t"
                }
                'r' -> {
                    i += 2
                    "\r"
                }
                'u' -> {
                    val hex = src.substring(i + 2, minOf(i + 6, src.length))
                    i = minOf(i + 6, src.length)
                    hex.toIntOrNull(16)?.let { codePoint -> String(Character.toChars(codePoint)) } ?: "u"
                }
                else -> {
                    i += 2
                    escaped.toString()
                }
            }
        }

        private fun isIdentStart(c: Char): Boolean = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

        private fun isIdentPart(c: Char): Boolean = isIdentStart(c) || c in '0'..'9'
    }

    private companion object {
        /** 常见 Java/Kotlin 格式占位符；`%%`（字面百分号）与 `10% AC` 这类普通百分号不匹配。 */
        val PLACEHOLDER = Regex("%(?:\\d+\\$)?[-#+0,(]*\\d*(?:\\.\\d+)?[sdfxXcbeEgGonhH]")

        /**
         * `SettingsScreen` 的「危险区」文案：英文里故意保留中文「删除」——用户需要照着输入这两个字，
         * 不属于漏译。按「文件名 + 中文 + 英文」三元组精确豁免，其余英文含中文仍会失败。
         */
        val WHITELIST = listOf(
            Whitelist(
                fileName = "SettingsScreen.kt",
                zh = "清空全部数据，需输入「删除」确认",
                en = "Wipes all data; type “删除” to confirm",
            ),
        )

        /** 解析器自检用的 app 样例：含注释伪调用、多行调用、`+` 拼接、字符串模板与 `fun tr(` 定义。 */
        val APP_SAMPLE = """
            package sample

            // 注释里的 tr("不应", "count")
            /* 块注释里的 tr("也不应", "count") */

            val a = tr("设置", "Settings")
            val b = tr(
                "共 %d 条记录",
                "%d records",
                count,
            )
            val c = tr("漏译", "漏译")
            val d = tr("占位符 %d", "no placeholder")
            val e = tr("拼接" + "文案", "Concatenated")
            val f = tr("动态 ${'$'}value", "dynamic")

            fun tr(zh: String, en: String) = zh
        """.trimIndent()

        /** 解析器自检用的 domain 样例：证明 `../domain/src/main/kotlin` 也被扫描。 */
        val DOMAIN_SAMPLE = """
            package sample

            val g = tr("领域", "Domain")
        """.trimIndent()

        /** CJK 表意文字：用来判断「这段中文文案是否需要英文」。 */
        fun hasCjkIdeograph(text: String): Boolean = text.any { c ->
            c.code in 0x3400..0x4DBF || c.code in 0x4E00..0x9FFF || c.code in 0xF900..0xFAFF
        }

        /**
         * 英文里不允许出现的 CJK：表意文字 + CJK 标点。
         * 故意不含全角形式区（U+FF00–FFEF）——`＋`、`％` 这类装饰性全角符号在英文里也可能是有意保留的。
         */
        fun hasCjkText(text: String): Boolean = text.any { c ->
            c.code in 0x2E80..0x2EFF ||
                c.code in 0x3000..0x303F ||
                c.code in 0x31C0..0x31EF ||
                c.code in 0x3400..0x4DBF ||
                c.code in 0x4E00..0x9FFF ||
                c.code in 0xF900..0xFAFF ||
                c.code in 0xFE30..0xFE4F
        }
    }
}
