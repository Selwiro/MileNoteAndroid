package com.milenote.android.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * 里程志图标集（24dp 视口 / 1.65dp 描边 / 圆头圆角）。
 *
 * 来源：
 * - 能对应上 Web 版 `app.mjs` `const paths=` 的图标直接复用其 SVG path（底栏、分类、常用操作）。
 * - Web 版没有的图标（记录、相机、搜索、分享、日历、维修、罚单、美容、返回等）按
 *   Material Symbols Rounded 24dp 几何手写为**同风格描边**路径，保证一套图标视觉一致。
 *
 * 用法：`Icon(MileNoteIcons.Records, contentDescription = "记录")`。
 * 所有 `ImageVector` 都设置 `autoMirror`（需要时），由 `Icon` 的 tint 统一着色，
 * 因此不要在业务里给图标写死颜色。**不引入 material-icons-extended（34MB）**。
 */
object MileNoteIcons {

    // -----------------------------------------------------------------------------------------
    // 底栏 4 项
    // -----------------------------------------------------------------------------------------

    /** 总览（Web `home`）。 */
    val Home: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Home",
            "M3 10 12 3l9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z",
        )
    }

    /** 费用记录（清单/账本纸，区别于「罚单」的锯齿票据）。 */
    val Records: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Records",
            "M6 3h12v18H6z",
            "M9 8h6M9 12h6M9 16h4",
        )
    }

    /** 油耗（Web `fuel` 加油枪）。 */
    val Fuel: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Fuel",
            "M4 21V4h9v17M4 10h9M2 21h13M16 5l4 4v8a2 2 0 0 1-4 0v-4h-3M18 7v4h2",
        )
    }

    /** 保养（Web `wrench`）。 */
    val Maintenance: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Maintenance",
            "M14 6a5 5 0 0 0-6 6l-5 5a2 2 0 0 0 4 4l5-5a5 5 0 0 0 6-6l-3 3-4-4z",
        )
    }

    /** 总览（选中：实心）。 */
    val HomeFilled: ImageVector by lazy {
        filledIcon(
            name = "MileNote.HomeFilled",
            "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
        )
    }

    /** 费用记录（选中：实心列表）。 */
    val RecordsFilled: ImageVector by lazy {
        filledIcon(
            name = "MileNote.RecordsFilled",
            "M4 6h16v2H4zM4 11h16v2H4zM4 16h10v2H4z",
        )
    }

    /** 油耗（选中：实心加油枪）。 */
    val FuelFilled: ImageVector by lazy {
        filledIcon(
            name = "MileNote.FuelFilled",
            "M19.77 7.23l.01-.01-3.72-3.72L15 4.56l2.11 2.11c-.94.36-1.61 1.26-1.61 2.33 0 1.38 1.12 2.5 2.5 2.5.36 0 .69-.08 1-.21v7.21c0 .55-.45 1-1 1s-1-.45-1-1V14c0-1.1-.9-2-2-2h-1V5c0-1.1-.9-2-2-2H6c-1.1 0-2 .9-2 2v16h10v-7.5h1.5v5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V9c0-.69-.28-1.32-.73-1.77zM12 10H6V5h6v5zm6 0c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z",
        )
    }

    /** 保养（选中：实心扳手）。 */
    val MaintenanceFilled: ImageVector by lazy {
        filledIcon(
            name = "MileNote.MaintenanceFilled",
            "M22.7 19l-9.1-9.1c.9-2.3.4-5-1.5-6.9-2-2-5-2.4-7.4-1.3L9 6 6 9 1.6 4.7C.4 7.1.9 10.1 2.9 12.1c1.9 1.9 4.6 2.4 6.9 1.5l9.1 9.1c.4.4 1 .4 1.4 0l2.4-2.4c.4-.4.4-1 0-1.3z",
        )
    }

    /** 车辆（Web `car`）。 */
    val Car: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Car",
            "M4 10l2-6h12l2 6M3 10h18v8H3zM5 18v3m14-3v3M6 14h2m8 0h2",
        )
    }

    /** 钱包（Web `wallet`）。 */
    val Wallet: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Wallet",
            "M4 5h15v15H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h13M15 11h6v5h-6z",
        )
    }

    // -----------------------------------------------------------------------------------------
    // 9 类费用（固定顺序：油费/保险/保养/过路费/停车费/维修/罚单/汽车用品/汽车美容）
    // -----------------------------------------------------------------------------------------

    val CategoryFuel: ImageVector get() = Fuel
    val CategoryInsurance: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Category.Insurance",
            "m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z",
            "M8 12l3 3 5-6",
        )
    }
    val CategoryMaintenance: ImageVector get() = Maintenance
    val CategoryToll: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Category.Toll",
            "M8 3 4 21M16 3l4 18M12 3v3m0 4v4m0 4v3",
        )
    }
    val CategoryParking: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Category.Parking",
            "M6 21V3h7a5 5 0 0 1 0 10H6",
        )
    }
    val CategoryRepair: ImageVector by lazy {
        // 维修：锤子（与「保养」的扳手区分）。
        strokeIcon(
            name = "MileNote.Category.Repair",
            "M13 3l8 8-4 4-8-8z",
            "M11 9 3 17l4 4 8-8",
        )
    }
    val CategoryTicket: ImageVector by lazy {
        // 罚单：法槌（Material Symbols `gavel` 的描边版）。
        strokeIcon(
            name = "MileNote.Category.Ticket",
            "M13.5 3.5l7 7-3.5 3.5-7-7z",
            "M11 9 3 17",
            "M6 21h12",
        )
    }
    val CategoryGoods: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Category.Goods",
            "M4 7h16v14H4z",
            "M8 7V5a4 4 0 0 1 8 0v2",
        )
    }
    val CategoryBeauty: ImageVector by lazy {
        // 汽车美容：星芒（Material Symbols `auto_awesome` 的描边版）。
        strokeIcon(
            name = "MileNote.Category.Beauty",
            "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z",
            "M18.5 15l.6 1.9 1.9.6-1.9.6-.6 1.9-.6-1.9-1.9-.6 1.9-.6z",
        )
    }

    /** 9 类费用图标，顺序与 `MileNoteCategoryNames` 完全一致。 */
    val Category: List<ImageVector> = listOf(
        CategoryFuel,
        CategoryInsurance,
        CategoryMaintenance,
        CategoryToll,
        CategoryParking,
        CategoryRepair,
        CategoryTicket,
        CategoryGoods,
        CategoryBeauty,
    )

    // -----------------------------------------------------------------------------------------
    // 常用操作
    // -----------------------------------------------------------------------------------------

    /** 新增（Web `plus`）。 */
    val Add: ImageVector by lazy {
        strokeIcon(name = "MileNote.Add", "M12 5v14M5 12h14")
    }
    val Plus: ImageVector get() = Add

    /** 相机 / OCR。 */
    val Camera: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Camera",
            "M4 8h3l2-3h6l2 3h3v11H4z",
            "M12 17a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7z",
        )
    }

    /** 导入（箭头入托盘，Web `download`）。 */
    val Import: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Import",
            "M12 3v13m-5-5 5 5 5-5M4 17v4h16v-4",
        )
    }

    /** 导出（箭头出托盘，Web `upload`）。 */
    val Export: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Export",
            "M12 16V3m-5 5 5-5 5 5M4 15v6h16v-6",
        )
    }

    val Upload: ImageVector get() = Export
    val Download: ImageVector get() = Import

    /** 删除（Web `trash`）。 */
    val Delete: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Delete",
            "M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7",
        )
    }

    /** 编辑（Web `edit`）。 */
    val Edit: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Edit",
            "m15 4 5 5M3 21l5-1L21 7l-5-5L3 15z",
        )
    }

    /** 搜索。 */
    val Search: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Search",
            "M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14z",
            "M16.5 16.5 21 21",
        )
    }

    /** 设置（Web `settings`）。 */
    val Settings: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Settings",
            "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        )
    }

    /** 勾选（Web `check`）。 */
    val Check: ImageVector by lazy {
        strokeIcon(name = "MileNote.Check", "m5 12 4 4L19 6")
    }

    val CheckCircle: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.CheckCircle",
            "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z",
            "m8 12 2.5 2.5L16 9",
        )
    }

    /** 右箭头。 */
    val ChevronRight: ImageVector by lazy {
        strokeIcon(name = "MileNote.ChevronRight", "m9 5 7 7-7 7", autoMirror = true)
    }

    /** 下箭头（折叠区）。 */
    val ChevronDown: ImageVector by lazy {
        strokeIcon(name = "MileNote.ChevronDown", "m5 9 7 7 7-7")
    }

    /** 返回（左箭头）。 */
    val Back: ImageVector by lazy {
        strokeIcon(name = "MileNote.Back", "M20 12H4m6-6-6 6 6 6", autoMirror = true)
    }

    /** 前进（右箭头，Web `arrow`）。 */
    val Forward: ImageVector by lazy {
        strokeIcon(name = "MileNote.Forward", "M5 12h14m-5-5 5 5-5-5", autoMirror = true)
    }

    /** 分享。 */
    val Share: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Share",
            "M18 3a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
            "M6 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
            "M18 15a3 3 0 1 0 0 6 3 3 0 0 0 0-6z",
            "M8.6 10.4l6.8-2.8M8.6 13.6l6.8 2.8",
        )
    }

    /** 日历。 */
    val Calendar: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Calendar",
            "M4 5h16v16H4z",
            "M4 10h16M8 3v4M16 3v4M8 14h2M14 14h2M8 17h2M14 17h2",
        )
    }

    /** 提醒（Web `bell`）。 */
    val Bell: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Bell",
            "M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4",
        )
    }

    /** 公路（Web `road`）。 */
    val Road: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Road",
            "M8 3 4 21M16 3l4 18M12 3v3m0 4v4m0 4v3",
        )
    }

    /** 图表（Web `chart`）。 */
    val Chart: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Chart",
            "M4 3v17h17M8 15v-4m5 4V7m5 8v-6",
        )
    }

    /** 关闭。 */
    val Close: ImageVector by lazy {
        strokeIcon(name = "MileNote.Close", "M6 6l12 12M18 6 6 18")
    }

    /** 减号（数字步进器）。 */
    val Minus: ImageVector by lazy {
        strokeIcon(name = "MileNote.Minus", "M5 12h14")
    }

    /** 信息（提示条 / 说明）。 */
    val Info: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Info",
            "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z",
            "M12 11v5M12 8h.01",
        )
    }

    /** 警告（即将到期 / 异常）。 */
    val Warning: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Warning",
            "M12 3 2.5 20h19z",
            "M12 9v5M12 17.5h.01",
        )
    }

    /** 错误（过期 / 失败）。 */
    val Error: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Error",
            "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z",
            "M12 7v6M12 16.5h.01",
        )
    }

    /** 更多（三个实心圆点）。 */
    val MoreVert: ImageVector by lazy {
        filledIcon(
            name = "MileNote.MoreVert",
            pathData = "M12 8.2a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z" +
                "M12 13.6a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z" +
                "M12 19a1.6 1.6 0 1 0 0-3.2 1.6 1.6 0 0 0 0 3.2z",
        )
    }

    /** 筛选。 */
    val FilterList: ImageVector by lazy {
        strokeIcon(name = "MileNote.FilterList", "M4 6h16M7 12h10M10 18h4")
    }

    /** 锁定（WebDAV 加密）。 */
    val Lock: ImageVector by lazy {
        strokeIcon(name = "MileNote.Lock", "M6 10h12v11H6zM9 10V7a3 3 0 0 1 6 0v3")
    }

    /** 可见性（密码显示）。 */
    val Visibility: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Visibility",
            "M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12z",
            "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6z",
        )
    }

    /** 位置 / 地点。 */
    val Location: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Location",
            "M12 21s7-6.2 7-11a7 7 0 1 0-14 0c0 4.8 7 11 7 11z",
            "M12 12a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z",
        )
    }

    /** 汽车美容星芒（独立入口）。 */
    val Sparkle: ImageVector get() = CategoryBeauty

    // -----------------------------------------------------------------------------------------
    // 设置页专用图标（单色描边，与设计规范一致）
    // -----------------------------------------------------------------------------------------

    /** 外观 / 皮肤（调色板）。 */
    val Palette: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Palette",
            "M12 3c-4.97 0-9 3.58-9 8 0 4.42 4.03 10 9 10 1.66 0 2.5-1.09 2.5-2.3 0-.6-.24-1.14-.62-1.55-.37-.4-.6-.94-.6-1.55 0-1.24 1.01-2.1 2.26-2.1H18c2.21 0 3-1.34 3-3 0-4.42-4.03-8-9-8z",
            "M7 12.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
            "M10.5 8.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
            "M15 8.5a1 1 0 1 0 0-2 1 1 0 0 0 0 2z",
        )
    }

    /** 费用分类与项目（标签）。 */
    val Tag: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Tag",
            "M20.6 13.4 11 3H4v7l9.6 9.6a2 2 0 0 0 2.8 0l4.2-4.2a2 2 0 0 0 0-2.8z",
            "M7.5 7.5h.01",
        )
    }

    /** 备份与还原（归档盒）。 */
    val Archive: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Archive",
            "M3 5h18v4H3zM5 9v10h14V9M10 13h4",
        )
    }

    /** WebDAV 同步（云）。 */
    val Cloud: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Cloud",
            "M17.5 18H7a4 4 0 0 1-.4-8A6 6 0 0 1 18.6 8.6 4.7 4.7 0 0 1 17.5 18z",
        )
    }

    /** 费用 CSV（表格文件）。 */
    val FileText: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.FileText",
            "M6 3h8l4 4v14H6zM14 3v4h4M9 12h6M9 16h6",
        )
    }

    /** 检查更新（刷新）。 */
    val Refresh: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Refresh",
            "M20 12a8 8 0 1 1-2.34-5.66M20 4v4h-4",
        )
    }

    /** 官网（地球）。 */
    val Globe: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Globe",
            "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z",
            "M3 12h18",
            "M12 3c2.5 2.5 3.5 6 3.5 9s-1 6.5-3.5 9c-2.5-2.5-3.5-6-3.5-9s1-6.5 3.5-9z",
        )
    }

    /** GitHub 仓库（代码）。 */
    val Code: ImageVector by lazy {
        strokeIcon(
            name = "MileNote.Code",
            "m8 7-5 5 5 5M16 7l5 5-5 5M14 4l-4 16",
        )
    }

    /** 底栏 4 项图标：总览 / 记录 / 油耗 / 保养。 */
    val BottomNav: List<ImageVector> = listOf(Home, Records, Fuel, Maintenance)
}

// ---------------------------------------------------------------------------------------------
// 构建工具：统一 24dp 视口 + 1.65dp 描边 + 圆头圆角（与 Web 版 style.css `.icon svg` 一致）。
// ---------------------------------------------------------------------------------------------

private const val ICON_STROKE_WIDTH = 1.65f

private fun baseBuilder(name: String, autoMirror: Boolean): ImageVector.Builder =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
        autoMirror = autoMirror,
    )

/** 描边风格图标（fill 为空，由 `Icon` 的 tint 着色）。 */
private fun strokeIcon(
    name: String,
    vararg pathData: String,
    autoMirror: Boolean = false,
): ImageVector = baseBuilder(name, autoMirror).apply {
    pathData.forEach { path ->
        addPath(
            pathData = addPathNodes(path),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = ICON_STROKE_WIDTH,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}.build()

/** 实心风格图标（仅用于圆点等小元素）。 */
private fun filledIcon(
    name: String,
    pathData: String,
    autoMirror: Boolean = false,
): ImageVector = baseBuilder(name, autoMirror).apply {
    addPath(
        pathData = addPathNodes(pathData),
        fill = SolidColor(Color.Black),
        stroke = null,
    )
}.build()
