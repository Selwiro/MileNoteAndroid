package com.milenote.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

/**
 * Markdown 解析单测（纯 JVM，不依赖 Compose 运行时）。
 *
 * 覆盖检查更新弹层实际会用到的语法：Release 正文里的标题 / 列表 / 行内代码 / 粗体、
 * 以及软换行合并（中文不加空格、英文加空格）这类容易回归的细节。
 */
class MarkdownTextTest {

    /**
     * 解析器曾经在「空行」分支漏了指针自增，导致整个测试进程卡死。
     * 加超时规则让这类死循环直接失败而不是挂住 CI。
     */
    @get:Rule
    val timeout: Timeout = Timeout.seconds(10)

    @Test
    fun `parses headings lists quote code and rule into blocks`() {
        val source = """
            ## 本次更新
            ### 新增
            - **WebDAV 可选加密上传**：设置 → WebDAV 同步新增「加密上传」；
              远端为 `drive-ledger-encrypted` v1 密文。
            - 第二项
            1. 第一步
            2. 第二步
            > 引用一行
            ```
            versionCode: 93
            ```
            ---
        """.trimIndent()

        val blocks = parseMarkdown(source)

        assertEquals(
            listOf(
                MarkdownBlock.Heading(2, "本次更新"),
                MarkdownBlock.Heading(3, "新增"),
                MarkdownBlock.BulletList(
                    listOf(
                        "**WebDAV 可选加密上传**：设置 → WebDAV 同步新增「加密上传」；远端为 " +
                            "`drive-ledger-encrypted` v1 密文。",
                        "第二项",
                    ),
                ),
                MarkdownBlock.OrderedList(1, listOf("第一步", "第二步")),
                MarkdownBlock.Quote("引用一行"),
                MarkdownBlock.CodeBlock("versionCode: 93"),
                MarkdownBlock.Rule,
            ),
            blocks,
        )
    }

    @Test
    fun `parses release note body like GitHub`() {
        val source = """
            versionCode: 93

            ## 本次更新
            ### 新增
            - **WebDAV 可选加密上传**：设置 → WebDAV 同步新增「加密上传」与独立加密密码；
              远端为 `drive-ledger-encrypted` v1 密文（AES-256-GCM / PBKDF2-SHA256 600000 次）。
            ### 修复
            - 读不出时显示「No valid fields detected」，引导手动填写。
            ## 质量
            - 单测 **domain 267 + app 126 全绿**；i18n 自动漏译检查 0 违规。
        """.trimIndent()

        val blocks = parseMarkdown(source)

        assertEquals(MarkdownBlock.Paragraph("versionCode: 93"), blocks.first())
        assertTrue(blocks.contains(MarkdownBlock.Heading(2, "本次更新")))
        assertTrue(blocks.contains(MarkdownBlock.Heading(3, "新增")))
        assertTrue(blocks.contains(MarkdownBlock.Heading(3, "修复")))
        val bullets = blocks.filterIsInstance<MarkdownBlock.BulletList>()
        assertEquals(3, bullets.size)
        // 列表项换行后的缩进续行应并进同一项，中文之间不补空格。
        assertTrue(bullets[0].items[0].endsWith("（AES-256-GCM / PBKDF2-SHA256 600000 次）。"))
        assertTrue(bullets[0].items[0].contains("；远端为 `drive-ledger-encrypted`"))
    }

    @Test
    fun `parses inline bold italic strike code and links`() {
        val spans = parseInlineMarkdown(
            "**粗体** 与 `code` 和 *斜体*、~~删除~~，见[发布页](https://example.com/release)" +
                " 以及 <https://selwiro.github.io/MileNoteAndroid/>",
        )

        assertEquals("粗体", spans[0].text)
        assertTrue(spans[0].bold)
        assertTrue(spans.any { it.text == "code" && it.code })
        assertTrue(spans.any { it.text == "斜体" && it.italic })
        assertTrue(spans.any { it.text == "删除" && it.strike })
        val link = spans.single { it.text == "发布页" }
        assertEquals("https://example.com/release", link.url)
        val autoLink = spans.single { it.text == "https://selwiro.github.io/MileNoteAndroid/" }
        assertEquals("https://selwiro.github.io/MileNoteAndroid/", autoLink.url)
    }

    @Test
    fun `keeps nested styles inside bold`() {
        val spans = parseInlineMarkdown("**含 `code` 的粗体**")
        assertEquals(listOf("含 ", "code", " 的粗体"), spans.map { it.text })
        assertTrue(spans.all { it.bold })
        assertTrue(spans[1].code)
    }

    @Test
    fun `does not treat underscores inside words as italic`() {
        val spans = parseInlineMarkdown("drive_data_encrypted 与 _强调_")
        assertEquals("drive_data_encrypted 与 ", spans[0].text)
        assertEquals("强调", spans[1].text)
        assertTrue(spans[1].italic)
    }

    @Test
    fun `unclosed markers stay as plain text`() {
        val spans = parseInlineMarkdown("**没闭合的粗体 和 `code")
        assertEquals("**没闭合的粗体 和 `code", spans.joinToString("") { it.text })
        assertTrue(spans.all { !it.bold && !it.code })
    }

    @Test
    fun `soft joins Chinese without space and English with space`() {
        assertEquals("中文中文", softJoin("中文", "中文"))
        assertEquals("结束。下一句", softJoin("结束。", "下一句"))
        assertEquals("the full", softJoin("the", "full"))
        assertEquals("23.7 20.2 MB", softJoin("23.7", "20.2 MB"))
    }

    @Test
    fun `blank lines separate paragraphs and lists`() {
        val blocks = parseMarkdown("第一段\n第二行\n\n- 项一\n- 项二\n\n第三段")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph("第一段第二行"),
                MarkdownBlock.BulletList(listOf("项一", "项二")),
                MarkdownBlock.Paragraph("第三段"),
            ),
            blocks,
        )
    }
}
