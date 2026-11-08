package com.milenote.android.navigation

/**
 * 应用图标长按快捷方式（res/xml/shortcuts.xml 里的自定义 action）。
 *
 * MainActivity 在 onCreate/onNewIntent 解析 action，写入 `AppContainer.pendingShortcut`，
 * 由界面消费后打开对应入口（记一笔 / 油费识别）。
 */
enum class AppShortcut {
    NEW_ENTRY,
    OCR,
    ;

    companion object {
        const val ACTION_NEW_ENTRY = "com.milenote.android.SHORTCUT_NEW_ENTRY"
        const val ACTION_OCR = "com.milenote.android.SHORTCUT_OCR"

        /** action 不是本应用的快捷方式时返回 null（普通启动不受影响）。 */
        fun fromAction(action: String?): AppShortcut? = when (action) {
            ACTION_NEW_ENTRY -> NEW_ENTRY
            ACTION_OCR -> OCR
            else -> null
        }
    }
}
