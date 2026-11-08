package com.milenote.android.i18n

/**
 * 数据值 → 显示文案的映射。
 *
 * 账本里存的是**中文规范值**（分类名、保养方案标题、项目名、皮肤名等），
 * 它们参与筛选、配色、导入匹配、跨端备份，**不能**被翻译或改写。
 * 英文界面只在这里做「显示层」映射；自定义分类 / 自定义项目没有映射，
 * 原样显示用户输入的内容。
 */
object DataLabels {

    private val CATEGORY_EN: Map<String, String> = mapOf(
        "油费" to "Fuel",
        "保险" to "Insurance",
        "保养" to "Maintenance",
        "过路费" to "Tolls",
        "停车费" to "Parking",
        "维修" to "Repairs",
        "罚单" to "Fines",
        "汽车用品" to "Accessories",
        "汽车美容" to "Detailing",
        // 旧分类（归一为「保养」），仅兼容历史数据展示。
        "维修保养" to "Maintenance",
    )

    private val THEME_EN: Map<String, String> = mapOf(
        "里程蓝" to "Blue",
        "森林绿" to "Forest",
        "丁香紫" to "Lilac",
        "暖日橙" to "Amber",
        "石墨灰" to "Graphite",
    )

    private val SERVICE_ITEM_EN: Map<String, String> = mapOf(
        // 12 项内置保养方案（DEFAULT_SERVICE_PLAN）
        "首保" to "First service",
        "机油机滤" to "Oil & filter",
        "空气滤芯" to "Air filter",
        "空调滤芯" to "Cabin filter",
        "冷却液" to "Coolant",
        "制动液" to "Brake fluid",
        "灰滤" to "Ash filter",
        "火花塞" to "Spark plugs",
        "轮胎" to "Tires",
        "冷却模块" to "Cooling module",
        "变速器油" to "Transmission fluid",
        "皮带" to "Belt",
        // 项目库（SERVICE_PROJECT_GROUPS）
        "补胎" to "Tire repair",
        "更换电瓶" to "Battery replacement",
        "钣金修复" to "Body repair",
        "喷漆" to "Painting",
        "洗车" to "Car wash",
        "打蜡" to "Waxing",
        "抛光" to "Polishing",
        "镀晶" to "Ceramic coating",
        "内饰清洁" to "Interior cleaning",
        "交强险" to "Compulsory insurance",
        "车损险" to "Collision coverage",
        "三者险" to "Third-party liability",
        // 演示账本里的自定义项目（仅展示，参与匹配的值仍是中文）
        "天窗轨道润滑" to "Sunroof track lubrication",
        "车窗异响处理" to "Window rattle fix",
        "自定义保养项目" to "Custom service item",
        "自定义维修项目" to "Custom repair item",
    )

    private val REGION_EN: Map<String, String> = mapOf(
        "北京" to "Beijing", "天津" to "Tianjin", "河北" to "Hebei", "山西" to "Shanxi",
        "内蒙古" to "Inner Mongolia", "辽宁" to "Liaoning", "吉林" to "Jilin",
        "黑龙江" to "Heilongjiang", "上海" to "Shanghai", "江苏" to "Jiangsu",
        "浙江" to "Zhejiang", "安徽" to "Anhui", "福建" to "Fujian", "江西" to "Jiangxi",
        "山东" to "Shandong", "河南" to "Henan", "湖北" to "Hubei", "湖南" to "Hunan",
        "广东" to "Guangdong", "深圳" to "Shenzhen", "广西" to "Guangxi", "海南" to "Hainan",
        "重庆" to "Chongqing", "四川" to "Sichuan", "贵州" to "Guizhou", "云南" to "Yunnan",
        "西藏" to "Tibet", "陕西" to "Shaanxi", "甘肃" to "Gansu", "青海" to "Qinghai",
        "宁夏" to "Ningxia", "新疆" to "Xinjiang",
    )

    /** 内置分类名 → 英文；自定义分类原样返回（中英文模式下都不变）。 */
    fun category(name: String?): String {
        val raw = name.orEmpty()
        if (!I18n.english) return raw
        return CATEGORY_EN[raw] ?: raw
    }

    /** 皮肤中文名 → 英文；未知值原样返回。 */
    fun theme(label: String?): String {
        val raw = label.orEmpty()
        if (!I18n.english) return raw
        return THEME_EN[raw] ?: raw
    }

    /** 保养方案 / 项目库名称 → 英文；用户自定义项目原样返回。 */
    fun serviceItem(name: String?): String {
        val raw = name.orEmpty()
        if (!I18n.english) return raw
        return SERVICE_ITEM_EN[raw] ?: raw
    }

    /** 油价地区（省份，如「云南」）→ 英文；地级市等自定义地区原样返回。 */
    fun region(name: String?): String {
        val raw = name.orEmpty()
        if (!I18n.english) return raw
        return REGION_EN[raw] ?: raw
    }

    /** 车辆名：空值 / 默认名「我的爱车」→ "My car"；其他自定义名原样返回。 */
    fun vehicleName(name: String?): String {
        val raw = name.orEmpty()
        if (raw.isNotBlank() && raw != "我的爱车") return raw
        return if (I18n.english) "My car" else "我的爱车"
    }
}
