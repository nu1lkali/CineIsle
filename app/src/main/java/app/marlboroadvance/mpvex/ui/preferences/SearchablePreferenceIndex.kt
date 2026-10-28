package app.marlboroadvance.mpvex.ui.preferences

import app.marlboroadvance.mpvex.R

/**
 * 设置搜索索引（v172 起）—— 由 `tools/gen_pref_index.py` 从各设置页源码抽取，**不要手改**。
 *
 * 为什么需要这一份：手写索引 [SearchablePreferences] 每张页面只登记了「首页那个入口」，
 * 而 `PreferencesScreen.kt` 里内联着上百个配置项（Emby 推荐 / 封面合成 / 随机播放 /
 * 网络浏览 / 数据缓存 / 应用锁 …），标题用的是**中文字面量**而不是 string 资源 ——
 * 两边都对不上，结果就是「很多配置项都搜索不出来」。
 *
 * 抽取口径：源码里的 `title =` 赋值点（`SwitchPreference` / `ListPreference` /
 * `SliderPreference` / `Preference` 与 `ToggleRow` 之类自定义行都算），副标题取紧随
 * 其后的 `summary =` / `subtitle =`，分类取最近一个 `PreferenceSectionHeader`。
 *
 * ⚠️ v174：**只收录「能定位」的行**。定位靠「那一行显示的文字」本身（行上是
 * `Modifier.settingsAnchor(...)`，本文件是 `titleRes` / `title`，同一表达式派生所以
 * 必然一致）。搜得到却点过去没反应，比搜不到更让人困惑，所以定位不了的行干脆不收。
 *
 * ⚠️ 与手写索引**并存**：手写那份带英文关键词（amoled / vulkan / danmaku …），是搜英文的
 *    唯一来源；合并时按 `titleRes` / `title` 去重，手写优先。
 */
internal val generatedSearchablePreferences: List<SearchablePreference> = buildList {
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_title,
        summaryRes = R.string.pref_appearance_summary,
        screenKeywords = listOf("设置", "preferences", "settings"),
        category = "外观",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player,
        summaryRes = R.string.pref_player_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_layout_title,
        summaryRes = R.string.pref_layout_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture,
        summaryRes = R.string.pref_gesture_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.danmaku_title,
        summaryRes = R.string.danmaku_settings_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gsy_player,
        summaryRes = R.string.pref_gsy_player_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_dislike_title,
        summaryRes = R.string.pref_dislike_summary,
        category = "播放与控件",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_folders_title,
        summaryRes = R.string.pref_folders_summary,
        category = "本地媒体库",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder,
        summaryRes = R.string.pref_decoder_summary,
        category = "媒体渲染（解码 / 字幕 / 音频）",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_subtitles,
        summaryRes = R.string.pref_subtitles_summary,
        category = "媒体渲染（解码 / 字幕 / 音频）",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio,
        summaryRes = R.string.pref_audio_summary,
        category = "媒体渲染（解码 / 字幕 / 音频）",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "预测性懒加载",
        summary = "滑到距列表末尾约一屏时就提前加载下一页；关闭后贴近末尾才加载",
        category = "Emby 媒体库与元数据",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "聚合库",
        summary = "把多个同类型媒体库合并成一个视图；只在本 App 内生效，不修改服务器",
        category = "Emby 媒体库与元数据",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "演职员合并",
        summary = "把重复的演员条目归并成一条（写入服务器，所有客户端生效，可撤销）· 需要管理员",
        category = "Emby 媒体库与元数据",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "详情页推荐",
        summary = "详情页显示「推荐」与「同类型推荐」；关闭后详情页不再展示推荐内容",
        category = "Emby 推荐与首页",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "同类型推荐随机选取类型",
        summary = "详情页「同类型」每次从本片所属的多个类型里随机挑一个；关闭则固定用第一个类型",
        category = "Emby 推荐与首页",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "推荐时排除已看过",
        summary = "关闭后看过的片子也会出现在推荐与连播列表里（适合重刷老片）",
        category = "Emby 推荐与首页",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "首页「随便看看」",
        summary = "在首页「最新加入」下面显示一批可连播的推荐；关闭后首页不再请求这批内容",
        category = "Emby 推荐与首页",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "连播列表每行数量",
        summary = "「猜你喜欢」页里「连播列表」那一块，一行放几张海报，当前 \$mixColumns 个。",
        category = "Emby 推荐与首页",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "预解析直链（302）",
        summary = "播放前先取出跳转后的真实地址。用于网盘 / STRM 这类带 302 跳转、",
        category = "Emby 播放与数据同步",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "下载后清除播放进度",
        summary = "下载完一条就把服务器上这条的续播位置清零，「继续观看」不再挂着它。",
        category = "Emby 播放与数据同步",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_media_source_pref_title,
        category = "Emby 播放与数据同步",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "照片预加载张数",
        summary = "前后各预取 \${preloadCount.coerceIn(1, 3)} 张，翻页更快；",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "照片幻灯片间隔",
        summary = "照片浏览器幻灯片播放时，每 \${slideshowInterval.seconds} 秒自动翻到下一张",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "照片浏览器自动播放幻灯片",
        summary = "打开照片即按间隔自动翻页，播放中可随时暂停",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "照片浏览器显示底部缩略图",
        summary = "底部显示一排缩略图，点一下跳到那张",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "照片保存位置",
        summary = "系统相册（Pictures/CineIsle）· 点击更改",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "恢复默认保存位置",
        summary = "改回系统相册的 Pictures/CineIsle",
        category = "Emby 照片浏览与保存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "卡片快捷收藏",
        summary = "媒体库卡片右上角显示可点的收藏心形；关闭后只作「已收藏」只读角标",
        category = "Emby 卡片外观",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "卡片圆角",
        summary = "当前 \${cardCorner}dp · 全 App 的 Emby 卡片统一圆角",
        category = "Emby 卡片外观",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示封面角标",
        summary = "在封面上显示播放进度条 / 作品数等角标；关闭后卡片更干净",
        category = "Emby 卡片外观",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "文件夹图标使用蓝色",
        summary = "媒体库文件夹卡片左下角的类型图标：打开为蓝色，关闭为白色（默认）",
        category = "Emby 卡片外观",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "合成随机封面图",
        summary = "文件夹 / 合集卡片用内部视频的封面拼多宫格；打开后随机取样，",
        category = "Emby 封面合成",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "重新合成策略",
        summary = "随机取样的封面每次都不一样，这一项决定多久换一批。",
        category = "Emby 封面合成",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "合成封面图间隔小时数",
        summary = "当前 \$coverHours 小时 · 取值范围 1~168",
        category = "Emby 封面合成",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "间隔天数",
        summary = "当前 \$coverDays 天 · 取值范围 1~30",
        category = "Emby 封面合成",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "随机播放视频数量",
        summary = "当前 \$randomCount 条 · 用于随机播放、随机播放收藏与视界流",
        category = "Emby 随机播放",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "随机播放默认下钻",
        summary = "当前：单击含子文件夹、长按只播当前目录",
        category = "Emby 随机播放",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示手势提示",
        summary = "点击随机播放 / 随机播放收藏 / 视界流时，提示本次是「含子文件夹」还是",
        category = "Emby 随机播放",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "文件夹封面",
        summary = "文件夹卡片显示目录内第一张图片作为封面；只在滚动到可见时加载，",
        category = "网络浏览",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "网格标题行数",
        summary = "网络浏览网格视图下，文件夹 / 图片 / 视频卡片标题最多显示",
        category = "网络浏览",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        title = "图片缓存",
        summary = "封面等图片的离线缓存（7 天有效）· 当前占用 ",
        category = "数据与缓存",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.app_lock_title,
        summaryRes = R.string.app_lock_summary_on_format,
        category = "安全与应用锁",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced,
        summaryRes = R.string.pref_advanced_summary,
        category = "高级与关于",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_about_title,
        summaryRes = R.string.pref_about_summary,
        category = "高级与关于",
        screen = PreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_amoled_mode_title,
        summaryRes = R.string.pref_appearance_amoled_mode_summary,
        screenKeywords = listOf("外观", "主题", "theme", "appearance", "深色", "浅色", "amoled", "颜色", "卡片"),
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_unlimited_name_lines_title,
        summaryRes = R.string.pref_appearance_unlimited_name_lines_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_show_unplayed_old_video_label_title,
        summaryRes = R.string.pref_appearance_show_unplayed_old_video_label_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_unplayed_old_video_days_title,
        summaryRes = R.string.pref_appearance_unplayed_old_video_days_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_auto_scroll_title,
        summaryRes = R.string.pref_appearance_auto_scroll_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_side_scrollbar_title,
        summaryRes = R.string.pref_appearance_side_scrollbar_summary_on,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_watched_threshold_title,
        summaryRes = R.string.pref_appearance_watched_threshold_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_tap_thumbnail_to_select_title,
        summaryRes = R.string.pref_gesture_tap_thumbnail_to_select_summary,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.appearance_deep_title,
        summaryRes = R.string.appearance_icon_title,
        category = "外观",
        screen = AppearancePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_orientation,
        screenKeywords = listOf("播放器", "player", "播放", "playback"),
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_save_position_on_quit,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_close_after_eof,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_autoplay_next_video,
        summary = "Automatically play next video when current ends",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "连播下一集倒计时",
        summary = "不显示，播完直接切",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "片尾提示下一集",
        summary = "片尾前 \${preferences.nextUpShowSeconds.get()} 秒在右下角提示下一集，点一下立刻切",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "提前多少秒提示",
        summary = "长片尾（带预告 / 片花）建议 60 秒以上",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "提前预热下一集",
        summary = "片尾前 \${preferences.prefetchNextEpisodeSeconds.get()} 秒预热下一集连接，切集更快；不额外占用带宽",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "记住每部剧的播放速度",
        summary = "同一部剧切下一集时沿用上次调过的倍速（电影按单部记）",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "记住每部剧选择的音轨",
        summary = "按「语言+编码+声道」匹配，下一集自动选中同一条音轨",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_remember_subtitle_track_title,
        summaryRes = R.string.pref_remember_subtitle_track_summary_on,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_remember_global_speed_title,
        summaryRes = R.string.pref_remember_global_speed_summary_on,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_auto_skip_intro_title,
        summaryRes = R.string.pref_auto_skip_intro_summary_on,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_auto_skip_outro_title,
        summaryRes = R.string.pref_auto_skip_outro_summary_on,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "GSY 播放器",
        summary = "内核 / 渲染方式 / 显示比例 / 手势 / 全屏与旋转，以及「长按用哪个内核播放」——",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_preload_next_video,
        summaryRes = R.string.i18n_preload_next_video_summary,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_disk_cache,
        summaryRes = R.string.pref_player_disk_cache_summary,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_disk_cache_size,
        summaryRes = R.string.pref_player_disk_cache_size_summary,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_clear_disk_cache,
        summaryRes = R.string.pref_player_clear_disk_cache_summary,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_enable_nav,
        summary = "Show next/previous buttons for all videos in folder",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_remember_brightness,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_remember_series_brightness,
        summaryRes = R.string.pref_player_remember_series_brightness_summary,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "自动画中画",
        summaryRes = R.string.i18n_auto_pip_hint,
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "暂停时保持屏幕常亮",
        summary = "Screen stays awake while video is paused",
        category = "常规",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.show_splash_ovals_on_double_tap_to_seek,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.show_time_on_double_tap_to_seek,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_use_precise_seeking,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_custom_skip_duration_title,
        summaryRes = R.string.pref_player_custom_skip_duration_summary,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_seek_step_title,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_show_player_clock_title,
        summaryRes = R.string.pref_show_player_clock_summary,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_show_player_battery_title,
        summaryRes = R.string.pref_show_player_battery_summary,
        category = "pref_player_seeking_title",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_brightness,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_volume,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_pinch_to_zoom,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_horizontal_swipe_to_seek,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_horizontal_swipe_sensitivity,
        summary = "Current: \${sensitivityPercent}/100 (\${if (sensitivityPercent < 30) ",
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_haptic_feedback,
        summaryRes = R.string.pref_player_haptic_feedback_summary,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_gestures_hold_for_multiple_speed,
        summaryRes = R.string.generic_disabled,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "动态倍速浮层",
        summaryRes = R.string.i18n_speed_overlay_hint,
        category = "pref_player_gestures",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_controls_allow_gestures_in_panels,
        category = "pref_player_controls",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.swap_the_volume_and_brightness_slider,
        category = "pref_player_controls",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_controls_show_loading_circle,
        category = "pref_player_controls",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "左下角悬浮切换按钮",
        summary = "上一条 / 下一条视频的快捷切换；控制条显示时自动隐藏，打开播放队列时也会收起",
        category = "pref_player_controls",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_display_show_status_bar,
        category = "pref_player_display",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示控件时显示导航栏",
        category = "pref_player_display",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_display_reduce_player_animation,
        category = "pref_player_display",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_auto_crop_black_bars_title,
        summaryRes = R.string.pref_auto_crop_black_bars_summary,
        category = "pref_player_display",
        screen = PlayerPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_layout_top_right_controls,
        screenKeywords = listOf("控件", "布局", "按钮", "layout", "controls", "buttons", "进度条"),
        category = "横屏控件",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_layout_bottom_right_controls,
        category = "横屏控件",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_layout_bottom_left_controls,
        category = "横屏控件",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_layout_portrait_bottom_controls,
        category = "竖屏控件",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_appearance_hide_player_buttons_background_title,
        summaryRes = R.string.pref_appearance_hide_player_buttons_background_summary,
        category = "外观",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_display_hide_player_control_time,
        summary = "Custom (\$playerTimeToDisappear ms)",
        category = "外观",
        screen = PlayerControlsPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_player_double_tap_seek_duration,
        summary = "Custom (\${doubleTapSeekDuration}s)",
        screenKeywords = listOf("手势", "gesture", "双击", "滑动", "音量", "亮度"),
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_double_tap_area_width,
        summary = "Current: \${doubleTapSeekAreaWidth}%",
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_double_tap_left_title,
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_single_tap_center_title,
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_double_tap_center_title,
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_double_tap_right_title,
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_use_single_tap_for_center_title,
        summaryRes = R.string.pref_gesture_use_single_tap_for_center_summary,
        category = "pref_gesture_double_tap_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_media_previous,
        category = "pref_gesture_media_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_media_play,
        category = "pref_gesture_media_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_gesture_media_next,
        category = "pref_gesture_media_title",
        screen = GesturePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.danmaku_enable_title,
        summaryRes = R.string.danmaku_enable_summary,
        screenKeywords = listOf("弹幕", "danmaku", "弹幕源", "弹幕库"),
        category = "danmaku_title",
        screen = DanmakuSettingsScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.danmaku_auto_match,
        summaryRes = R.string.danmaku_auto_match_summary,
        category = "danmaku_title",
        screen = DanmakuSettingsScreen,
    ))
    add(SearchablePreference(
        title = "默认播放内核",
        summary = "GSY",
        screenKeywords = listOf("GSY", "播放器", "内核", "渲染", "gsy", "player"),
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "长按用另一个内核播放",
        summary = "GSY",
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "GSY 解码内核",
        summary = "PlayerFactory.setPlayManager，改完**下次起播**生效（当前：\${kernel.title}）",
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "硬解码（IJK MediaCodec）",
        summary = "官方 GSYVideoType.enableMediaCodec()。硬解更省电，遇到花屏 / 黑屏时关掉可回软解",
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "硬解失败自动回退软解",
        summary = "官方 enableSmartMediaCodec()：仅在 IJK 硬解明确失败时重建一次软解",
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "强制解封装器（Exo 内核）",
        summary = "官方 setOverrideExtension。Exo 内核下地址没扩展名、或 DASH 解析失败时手动指定",
        category = "入口与内核",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "渲染方式",
        summary = "官方 GSYVideoType.setRenderType（全局，起播前生效）。",
        category = "画面",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示比例",
        summary = "官方 GSYVideoType.setShowType（全局）。",
        category = "画面",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "画面滤镜（GL）",
        summary = "官方 setEffectFilter，内置效果取自 SDK 的 render/effect。",
        category = "画面",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "循环播放",
        summary = "官方 setLooping",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "起播倍速",
        summary = "官方 setSpeed。播放页底部的玻璃「倍速」按钮可以现场循环切换",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "变速不变调",
        summary = "官方 setSoundTouch。倍速播放时保持音调（更吃性能）；System 内核不支持",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "边播边缓存",
        summary = "官方 setUp 的 cacheWithPlay（ProxyCacheManager）。",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "续播到上次位置",
        summary = "官方 setSeekOnStart。来源带播放进度时从该位置起播；带「从头播放」标记时忽略",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "播完自动退出播放页",
        summary = "官方 VideoAllCallBack.onAutoComplete。关闭则停在最后一帧",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "进入即静音",
        summary = "官方 GSYVideoManager.setNeedMute。播放页底部的玻璃「静音」按钮可随时切换",
        category = "播放行为",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "非全屏响应手势",
        summary = "官方 setIsTouchWiget：左侧上下滑调亮度、右侧上下滑调音量、横滑拖动进度",
        category = "手势",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏响应手势",
        summary = "官方 setIsTouchWigetFull。全屏时手势更顺手，一般保持开启",
        category = "手势",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "横滑 seek 灵敏度",
        summary = "官方 setSeekRatio：越大，横滑同样距离跳得越多（当前 \${",
        category = "手势",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "移动网络提示",
        summary = "官方 setNeedShowWifiTip：用流量播放前先问一句",
        category = "手势",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "控件自动隐藏",
        summary = "官方 setDismissControlTime。播放中无操作多久后收起控件条",
        category = "控件",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏显示屏幕锁",
        summary = "官方 setNeedLockFull。全屏时右侧出现锁按钮，防止误触",
        category = "控件",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "拖动进度条时显示目标时间",
        summary = "官方 setShowDragProgressTextOnSeekBar",
        category = "控件",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "暂停保留最后一帧",
        summary = "官方 setShowPauseCover。关掉后暂停画面可能变黑",
        category = "控件",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "按 Home 键自动进小窗",
        summary = "官方 setAutoEnterEnabled（Android 12+ 才生效）。",
        category = "画中画",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏锁横屏",
        summary = "官方 setLockLand",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "只允许横屏方向变化",
        summary = "官方 setOnlyRotateLand：全屏时只在左右横屏之间翻转，不会掉回竖屏",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "竖屏视频自动竖屏全屏",
        summary = "官方 setAutoFullWithSize：竖拍的片子点全屏不会硬转成横屏",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "跟随系统「自动旋转」",
        summary = "关闭（默认）：播放页内始终响应重力感应，转手机就能转屏；",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏过渡动画",
        summary = "官方 setShowFullAnimation。用 SurfaceView 渲染时动画会失效，属正常",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏隐藏状态栏",
        summary = "官方 setFullHideStatusBar",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "全屏隐藏虚拟按键",
        summary = "官方 setHideKey。全屏时把三大金刚 / 手势条隐掉，画面更完整",
        category = "全屏与旋转",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示系统状态栏",
        summary = "开着时顶栏会落在状态栏下方（避让刘海 / 挖孔）；关掉则顶栏贴屏幕上沿",
        category = "播放页系统栏",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "显示系统导航栏",
        summary = "开着时底部控件条会抬到手势条上方，避免进度条被压住",
        category = "播放页系统栏",
        screen = GsyPreferencesScreen,
    ))
    add(SearchablePreference(
        title = "详情页菜单项",
        summary = "在媒体详情页的「更多」菜单里显示「标记为不喜欢」。",
        screenKeywords = listOf("不喜欢", "dislike", "标记", "屏蔽"),
        category = "标记为不喜欢",
        screen = DislikePreferencesScreen,
    ))
    add(SearchablePreference(
        title = "播放器按钮",
        summary = "在播放器控制栏显示「不喜欢」按钮。关闭会同时从布局中移除它。",
        category = "标记为不喜欢",
        screen = DislikePreferencesScreen,
    ))
    add(SearchablePreference(
        title = "视界流按钮",
        summary = "在视界流（竖屏连播）右侧工具栏显示「不喜欢」按钮。",
        category = "标记为不喜欢",
        screen = DislikePreferencesScreen,
    ))
    add(SearchablePreference(
        title = "不喜欢视频管理",
        summary = "查看已标记的条目、取消标记或删除。",
        category = "标记为不喜欢",
        screen = DislikePreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_profile_title,
        screenKeywords = listOf("解码", "渲染", "decoder", "硬解", "hw", "滤镜", "shader"),
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_try_hw_dec_title,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_gpu_next_title,
        summaryRes = R.string.pref_decoder_gpu_next_summary,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_hdr_title,
        summaryRes = R.string.pref_decoder_hdr_summary,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_vulkan_title,
        summaryRes = R.string.pref_decoder_vulkan_summary,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_debanding_title,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_decoder_yuv420p_title,
        summaryRes = R.string.pref_decoder_yuv420p_summary,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_anime4k_title,
        summaryRes = R.string.pref_anime4k_summary,
        category = "pref_decoder",
        screen = DecoderPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_preferred_languages,
        summaryRes = R.string.not_set_video_default,
        screenKeywords = listOf("字幕", "subtitle", "ass", "字体", "语言"),
        category = "general",
        screen = SubtitlesPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_subtitles_autoload_title,
        summaryRes = R.string.pref_subtitles_autoload_summary,
        category = "general",
        screen = SubtitlesPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.player_sheets_sub_override_ass,
        summaryRes = R.string.player_sheets_sub_override_ass_subtitle,
        category = "general",
        screen = SubtitlesPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.player_sheets_sub_follow_picture,
        summaryRes = R.string.player_sheets_sub_follow_picture_summary,
        category = "general",
        screen = SubtitlesPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_preferred_languages,
        summaryRes = R.string.not_set_video_default,
        screenKeywords = listOf("音频", "audio", "声道", "音量", "音轨"),
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio_pitch_correction_title,
        summaryRes = R.string.pref_audio_pitch_correction_summary,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio_volume_normalization_title,
        summaryRes = R.string.pref_audio_volume_normalization_summary,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio_normalization_strength,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.background_playback_title,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio_channels,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_audio_volume_boost_cap,
        summaryRes = R.string.generic_disabled,
        category = "pref_audio",
        screen = AudioPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_export_settings,
        summaryRes = R.string.i18n_export_settings_hint,
        screenKeywords = listOf("高级", "advanced", "日志", "缓存", "备份", "导入", "导出", "mpv", "配置"),
        category = "备份与恢复",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_import_settings,
        summaryRes = R.string.i18n_import_settings_hint,
        category = "备份与恢复",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_mpv_conf_storage_location,
        category = "MPV 配置",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_mpv_conf,
        summaryRes = R.string.i18n_tap_to_edit_config,
        category = "MPV 配置",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_input_conf,
        summaryRes = R.string.i18n_tap_to_edit_config,
        category = "MPV 配置",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_enable_recently_played_title,
        summaryRes = R.string.pref_advanced_enable_recently_played_summary,
        category = "历史",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_clear_playback_history,
        category = "历史",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_clear_config_cache,
        summaryRes = R.string.i18n_clear_config_cache_hint,
        category = "缓存",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.i18n_clear_thumb_cache,
        summaryRes = R.string.i18n_clear_thumb_cache_hint,
        category = "缓存",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_clear_fonts_cache,
        summaryRes = R.string.i18n_clear_font_cache_hint,
        category = "缓存",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_verbose_logging_title,
        summaryRes = R.string.pref_advanced_verbose_logging_summary,
        category = "日志",
        screen = AdvancedPreferencesScreen,
    ))
    add(SearchablePreference(
        titleRes = R.string.pref_advanced_dump_logs_title,
        summaryRes = R.string.pref_advanced_dump_logs_summary,
        category = "日志",
        screen = AdvancedPreferencesScreen,
    ))
}
