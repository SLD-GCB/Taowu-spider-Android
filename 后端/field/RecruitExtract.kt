package com.taowu.backend.field

/**
 * 招聘信息抽取器 —— 移植自 PC 版 `潜龙文本抽取模型/infer_core.py` 的纯正则链路
 * （`rule_extract` 在无模型时的本地兜底）+ `config._x_company`。
 *
 * 与通用 `SchemaExtract` 并列：招聘模板 engine=recruit 时走这里，其余走 SchemaExtract。
 * 输入整页文本，输出 9 个业务字段 + job_url/source。
 */
object RecruitExtract {

    private val COMPANY_SUFFIX = listOf(
        "有限公司", "有限责任公司", "股份公司", "集团有限公司", "集团公司",
        "科技有限公司", "信息技术有限公司", "网络科技有限公司",
        "实业有限公司", "文化传媒有限公司", "教育科技有限公司",
        "医疗科技有限公司", "生物科技有限公司", "制药有限公司",
        "餐饮管理有限公司", "酒店管理有限公司", "物业管理有限公司",
        "物流有限公司", "建筑工程有限公司", "装饰工程有限公司",
        "设计有限公司", "咨询有限公司", "服务有限公司", "贸易有限公司",
        "发展有限公司", "投资有限公司", "管理有限公司", "控股有限公司",
        "合伙企业", "外资企业", "事务所", "工作室",
    )
    private val COMPANY_WEAK_SUFFIX = listOf(
        "公司", "集团", "科技", "实业", "网络", "信息", "文化", "传媒", "教育", "医疗",
        "餐饮", "酒店", "物业", "物流", "建筑", "装饰", "设计", "咨询", "服务", "贸易",
        "发展", "投资", "管理", "生物", "制药", "化工", "机械", "电子", "电气", "自动化",
        "软件", "数据", "云计算", "电子商务", "新媒体", "广告", "影视", "娱乐", "旅游",
        "金融", "保险", "房地产", "连锁", "控股",
    )
    private val CITIES = listOf(
        "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "重庆",
        "天津", "苏州", "西安", "长沙", "郑州", "东莞", "青岛", "沈阳", "宁波",
        "昆明", "合肥", "大连", "福州", "厦门", "济南", "无锡", "佛山", "珠海",
        "中山", "惠州", "温州", "常州", "嘉兴", "南通", "泉州", "绍兴", "烟台",
        "南宁", "贵阳", "兰州", "海口", "三亚", "哈尔滨", "长春", "太原",
        "南昌", "石家庄", "呼和浩特", "乌鲁木齐", "拉萨", "银川", "西宁",
    )
    private val JOB_LABEL_SKIP = setOf(
        "岗位名称", "职位名称", "岗位基础信息", "岗位职责与要求",
        "岗位职责", "职位描述", "工作内容", "公司信息", "公司名称",
        "企业名称", "福利信息", "福利待遇", "联系方式", "页面全文",
        "工作地点", "薪资范围", "学历要求", "工作经验",
    )
    private val WELFARE = Regex(
        "(六险一金|七险一金|五险一金|五险|社保|公积金|补充公积金|带薪年假|年假|" +
            "节日福利|健康体检|团建|餐补|住宿补贴|交通补贴|通讯补贴|交通补助|话补|" +
            "年终奖金|年终奖|绩效奖金|高额提成|定期体检|包吃住|包吃|包住|食宿全包|" +
            "高温补贴|晚班补贴|全勤奖|夜班补助|年终分红|团队旅游|防寒补贴|" +
            "持证补贴|工龄补贴|工龄津贴|节日花礼|野外补贴|防护用品|" +
            "岗位补贴|夜班津贴|文化补贴|专项津贴|双休|单休|大小周|弹性工作|弹性工时|" +
            "带薪学术交流|包教包会|技能入股|产品内购福利|节日红包|" +
            "带薪休假|观影福利|住宿免费|手艺补贴|户外补贴|下午茶福利|下午茶|零食|" +
            "宠物用品内购|带薪轮休|年度体检|晚班补助|飞行补贴|意外险|" +
            "股票期权|期权|股权|免费班车|班车|培训|晋升|时间灵活|朝九晚五|朝九晚六|" +
            "不打卡|远程办公|在家办公|生日福利|不加班)",
        RegexOption.DOT_MATCHES_ALL,
    )

    private fun clean(text: String): String =
        text.replace("\n", " ").replace("\r", " ").replace("\t", " ").trim()

    /** 抽取全部招聘字段。ctx 提供 job_url/source；hints 为页面脚本里的结构化线索（优先于正则）。 */
    fun extract(
        text: String,
        url: String = "",
        site: String = "",
        hints: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val t = clean(text)
        fun pref(hint: String?, v: String) = if (!hint.isNullOrBlank()) hint else v
        return linkedMapOf(
            "企业名称" to pref(hints["company"], company(t)),
            "岗位名称" to pref(hints["job"], jobName(t)),
            "工作地点" to pref(hints["location"], location(t)),
            "薪资范围" to pref(hints["salary"], salary(t)),
            "学历要求" to pref(hints["education"], education(t)),
            "工作经验" to pref(hints["experience"], experience(t)),
            "岗位职责" to duties(t),
            "福利信息" to welfare(t),
            "联系方式" to contact(t),
            "job_url" to url,
            "source" to site,
        )
    }

    // ── 各字段 ────────────────────────────────────────────────────

    private fun company(text: String): String {
        for (pfx in listOf("【公司信息】", "【企业名称】", "【公司名称】", "公司名称：", "企业名称：", "招聘单位：", "用人单位：")) {
            val i = text.indexOf(pfx)
            if (i >= 0) {
                val s = text.substring(i + pfx.length).split("【")[0].trim().take(60)
                if (s.length >= 2) return s
            }
        }
        Regex("([\u4e00-\u9fa5a-zA-Z（）()·]{2,60})(" + COMPANY_SUFFIX.joinToString("|") + ")")
            .find(text)?.let { return (it.groupValues[1] + it.groupValues[2]).trim() }
        Regex("([\u4e00-\u9fa5a-zA-Z（）()·]{3,40})(" + COMPANY_WEAK_SUFFIX.joinToString("|") + ")")
            .find(text)?.let { return (it.groupValues[1] + it.groupValues[2]).trim() }
        for (pfx in listOf("公司：", "企业：", "公司:", "企业:")) {
            val i = text.indexOf(pfx)
            if (i >= 0) {
                val s = text.substring(i + pfx.length).split("【")[0].split(" ")[0].trim().take(40)
                if (s.length >= 2) return s
            }
        }
        return ""
    }

    private fun jobName(text: String): String {
        Regex("(?:岗位名称|职位名称|招聘职位|职位)[：:]\\s*([^，。\\n]{2,30})").find(text)?.let { return it.groupValues[1].trim() }
        Regex("【([^】]{2,30})】").find(text)?.let {
            val v = it.groupValues[1].trim()
            if (v !in JOB_LABEL_SKIP) return v
        }
        for (sep in listOf("，", ",", "。", ".", "；")) {
            if (text.contains(sep)) {
                val first = text.split(sep)[0].trim()
                if (first.length in 2..30 && listOf("地点", "薪资", "学历", "经验", "福利", "联系").none { it in first }) {
                    return first
                }
            }
        }
        return ""
    }

    private fun location(text: String): String {
        Regex("(?:工作地点|上班地点|办公地点|工作地址|base|Base|BASE)(?:[：:是为在]\\s*)?([\u4e00-\u9fa5]{2,10}?(?:市|区|县|省|新区|开发区|高新区|街道|镇)?)")
            .find(text)?.let { if (it.groupValues[1].length >= 2) return it.groupValues[1].trim() }
        Regex("(?:位于|坐落于)\\s*([\u4e00-\u9fa5]{2,10}?(?:市|区|县))").find(text)?.let { return it.groupValues[1].trim() }
        val head = text.take(300)
        for (city in CITIES.sortedByDescending { it.length }) if (city in head) return city
        return ""
    }

    private fun salary(text: String): String {
        val patterns = listOf(
            Regex("(\\d{1,3}(?:\\.\\d)?(?:\\s*[kK])?\\s*[-~至到]\\s*\\d{1,3}(?:\\.\\d)?\\s*[kK千](?:\\s*[/每]\\s*月)?)"),
            Regex("(\\d{1,3}(?:\\.\\d)?\\s*万?\\s*[-~至到]\\s*\\d{1,3}(?:\\.\\d)?\\s*万\\s*(?:/|每)?\\s*[年月])"),
            Regex("(\\d{4,6}\\s*[-~至到]\\s*\\d{4,6}\\s*元)"),
            Regex("(底薪\\s*\\d{1,3}(?:\\.\\d)?\\s*[kK千]\\s*\\+\\s*(?:提成|绩效|奖金))"),
            Regex("(面议|薪资面议|待遇面议)"),
        )
        for (r in patterns) r.find(text)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun education(text: String): String =
        Regex("(博士|硕士|研究生|MBA|EMBA|本科|统招本科|全日制本科|大专|全日制大专|中专|中技|高中|初中|学历不限|不限学历|学历无要求|学历无硬性要求|学历不作要求)")
            .find(text)?.groupValues?.get(1)?.trim() ?: ""

    private fun experience(text: String): String {
        val patterns = listOf(
            Regex("(\\d{1,2}\\s*[-~至到]\\s*\\d{1,2}\\s*年(?:以上)?(?:相关)?(?:工作)?(?:经验|经历)?)"),
            Regex("(\\d{1,2}\\s*年\\s*以\\s*上(?:相关)?(?:工作)?(?:经验|经历)?)"),
            Regex("(\\d{1,2}\\s*年(?:相关)?(?:工作)?(?:经验|经历))"),
            Regex("([一二三四五六七八九十]+\\s*年\\s*以\\s*上(?:相关)?(?:工作)?(?:经验|经历)?)"),
            Regex("(应届(?:生|毕业生)?|经验不限|不限经验|无需经验|无经验要求|有无经验均可|接受应届|欢迎应届)"),
        )
        for (r in patterns) r.find(text)?.let { return it.groupValues[1].trim() }
        return ""
    }

    private fun duties(text: String): String {
        var t = Regex("\\d+[~-]\\s*\\d+\\s*元").replace(text, "")
        t = Regex("\\d+[~-]\\d+").replace(t, "")
        val filterWords = listOf("经验", "学历", "应届生")
        val lines = t.split("。", "，")
        val leadPat = Regex("(?:主要负责|负责|专门负责|配合|对.*?进行|专注|每日完成|协助|把控)\\s*(.+)", RegexOption.DOT_MATCHES_ALL)
        for (line0 in lines) {
            val line = line0.trim()
            if (filterWords.any { it in line } || "【" in line) continue
            leadPat.find(line)?.let { return it.groupValues[1].trim() }
        }
        for (line0 in lines) {
            val line = line0.trim()
            if (filterWords.any { it in line } || "【" in line || line.length < 8) continue
            return line.take(500)
        }
        return ""
    }

    private fun welfare(text: String): String {
        val seen = LinkedHashSet<String>()
        for (m in WELFARE.findAll(text)) seen.add(m.value)
        return seen.joinToString("、")
    }

    private fun contact(text: String): String {
        val phones = Regex("1[3-9]\\d{9}").findAll(text).map { it.value }.toList()
        if (phones.isNotEmpty()) return phones.joinToString("、")
        Regex("(?:电话|手机|联系电话|联系方式|联系人|Tel|Phone|联系)[：:\\s]*([\\d\\-+（）() ]{7,20})")
            .find(text)?.let { return it.groupValues[1].trim() }
        return ""
    }
}
