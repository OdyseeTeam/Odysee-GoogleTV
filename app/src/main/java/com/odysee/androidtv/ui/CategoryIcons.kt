package com.odysee.androidtv.ui

object CategoryIcons {
    fun iconSvg(id: String?, title: String?, colorHex: String): String =
        applyColor(getCategoryIconSvg(id, title), colorHex)

    private fun applyColor(svg: String, colorHex: String): String =
        svg
            .replace("currentColor", colorHex)
            .replaceFirst("<svg ", "<svg color=\"$colorHex\" ")

    private fun getCategoryIconSvg(id: String?, title: String?): String {
        val key = id.orEmpty().lowercase()
        val text = title.orEmpty().lowercase()
        val combined = "$key $text"

        return when {
            combined.contains("following") -> CATEGORY_ICON_MAP.getValue("following")
            combined.contains("watchlater") || combined.contains("watch later") -> CATEGORY_ICON_MAP.getValue("watchlater")
            combined.contains("home") -> CATEGORY_ICON_MAP.getValue("home")
            combined.contains("featured") -> CATEGORY_ICON_MAP.getValue("featured")
            combined.contains("discover") || combined.contains("explore") -> CATEGORY_ICON_MAP.getValue("discover")
            combined.contains("trend") || combined.contains("hot") || combined.contains("popular") -> CATEGORY_ICON_MAP.getValue("trending")
            combined.contains("news") || combined.contains("politic") -> CATEGORY_ICON_MAP.getValue("news")
            combined.contains("gaming") || combined.contains("game") -> CATEGORY_ICON_MAP.getValue("gaming")
            combined.contains("music") -> CATEGORY_ICON_MAP.getValue("music")
            combined.contains("comedy") || combined.contains("funny") -> CATEGORY_ICON_MAP.getValue("comedy")
            combined.contains("science") || combined.contains("tech") -> CATEGORY_ICON_MAP.getValue("science")
            combined.contains("education") || combined.contains("learn") -> CATEGORY_ICON_MAP.getValue("education")
            combined.contains("sports") -> CATEGORY_ICON_MAP.getValue("sports")
            combined.contains("wildwest") || combined.contains("wild west") -> CATEGORY_ICON_MAP.getValue("wildwest")
            combined.contains("popculture") || combined.contains("pop culture") -> CATEGORY_ICON_MAP.getValue("popculture")
            combined.contains("finance") || combined.contains("money") -> CATEGORY_ICON_MAP.getValue("finance")
            combined.contains("universe") -> CATEGORY_ICON_MAP.getValue("universe")
            combined.contains("lifestyle") || combined.contains("life style") -> CATEGORY_ICON_MAP.getValue("lifestyle")
            combined.contains("spirituality") || combined.contains("spiritual") -> CATEGORY_ICON_MAP.getValue("spirituality")
            combined.contains("spooky") || combined.contains("horror") -> CATEGORY_ICON_MAP.getValue("horror")
            else -> CATEGORY_ICON_MAP.getValue("default")
        }
    }

    private fun buildStrokeCategoryIcon(viewBox: String, content: String, strokeWidth: String = "1.8"): String =
        """<svg class="category-icon-svg" xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox" fill="none" stroke="currentColor" stroke-width="$strokeWidth" stroke-linecap="round" stroke-linejoin="round" focusable="false" aria-hidden="true">$content</svg>"""

    private fun buildFillCategoryIcon(viewBox: String, content: String): String =
        """<svg class="category-icon-svg category-icon-svg-filled" xmlns="http://www.w3.org/2000/svg" viewBox="$viewBox" fill="currentColor" stroke="none" focusable="false" aria-hidden="true">$content</svg>"""

    private val CATEGORY_ICON_MAP: Map<String, String> = mapOf(
        "following" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>"""
        ),
        "watchlater" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/>"""
        ),
        "featured" to buildFillCategoryIcon(
            "0 0 22 22",
            """<path d="M11 3L13.2627 8.73726L19 11L13.2627 13.2627L11 19L8.73726 13.2627L3 11L8.73726 8.73726L11 3Z"/>"""
        ),
        "discover" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<circle cx="12" cy="12" r="10"/><polygon points="16.24 7.76 14.12 14.12 7.76 16.24 9.88 9.88 16.24 7.76"/>"""
        ),
        "trending" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<polyline points="23 6 13.5 15.5 8.5 10.5 1 18"/><polyline points="17 6 23 6 23 12"/>"""
        ),
        "gaming" to buildStrokeCategoryIcon(
            "0 0 20 20",
            """<path d="M18 5.49925L10.1096 10L18 14.5007C16.4248 17.1904 13.4811 19 10.1096 19C5.07849 19 1 14.9706 1 10C1 5.02944 5.07849 1 10.1096 1C13.4811 1 16.4248 2.80956 18 5.49925Z"/>""",
            "1.6"
        ),
        "news" to buildStrokeCategoryIcon(
            "0 0 21 18",
            """<path d="M17.7553 6.50001L19.7553 6.00001M17.7553 11L19.7553 11.5M16.2553 2.00001L17.3262 1M3.17018 8.10369L2.98445 8.23209C2.85036 8.32478 2.70264 8.3958 2.56048 8.47556C1.88883 8.85235 1.38281 9.7222 1.52367 10.5694C1.6624 11.4038 2.3113 12.0619 3.14392 12.2112L4.75526 12.5L4.75528 14.5L5.30241 16.292C5.43083 16.7126 5.81901 17 6.25882 17H8.69504M3.17018 8.10369L12.2582 2.84235M3.17018 8.10369L4.00718 12.1694L14.0948 12.5372M8.69504 17H9M8.69504 17L7.75527 14.5L7.75529 12.5M12.2553 2.00001L13.2553 7.50001L14.2553 13.5M14.1875 8.6648C14.8624 8.53243 15.3022 7.87802 15.1698 7.20313C15.0375 6.52824 14.383 6.08843 13.7082 6.22079"/>""",
            "1.5"
        ),
        "science" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<path d="M6 2h8"/><path d="M8 2v8l-5 9a2.3 2.3 0 0 0 2 3h14a2.3 2.3 0 0 0 2-3l-5-9V2"/><line x1="7" y1="14" x2="17" y2="14"/><path d="M9 18a1 1 0 1 0 0.01 0"/><path d="M14 17.5a1.6 1.6 0 1 0 0.01 0"/>"""
        ),
        "music" to buildStrokeCategoryIcon(
            "0 0 19 20",
            """<path d="M6.5 14.5V5.26667L17.5 2V12.5M7 16C7 17.6569 5.65685 19 4 19C2.34315 19 1 17.6569 1 16C1 14.3431 2.34315 13 4 13C5.65685 13 7 14.3431 7 16ZM18 14C18 15.6569 16.6569 17 15 17C13.3431 17 12 15.6569 12 14C12 12.3431 13.3431 11 15 11C16.6569 11 18 12.3431 18 14Z"/>""",
            "1.7"
        ),
        "comedy" to buildStrokeCategoryIcon(
            "0 0 19 20",
            """<path d="M6.00003 12.5C7.54095 14.8536 10.6667 15.7483 13.5 12.5M8.50003 8C7.50003 7 6.00003 7 5.00003 7.99998M14.5 7.99999C13.25 6.99997 12 7.00001 11 8M1 2C5.92105 3.78947 13.0789 3.34211 18 2V4.80013C18 9.80277 16.5622 15.1759 12.4134 17.9713C10.3659 19.3508 8.5887 19.4007 6.26359 17.7683C2.35369 15.0233 1 9.95156 1 5.17427V2Z"/>""",
            "1.6"
        ),
        "sports" to buildStrokeCategoryIcon(
            "0 0 21 20",
            """<path d="M3.21009 5.08508C6.58582 7.0833 10.5321 12.6392 8.49668 18.4082M17.7408 14.398C13.2297 12.6201 10.8457 6.80095 13.2476 1.69871M19.5 10C19.5 14.9706 15.4706 19 10.5 19C5.52944 19 1.5 14.9706 1.5 10C1.5 5.02944 5.52944 1 10.5 1C15.4706 1 19.5 5.02944 19.5 10Z"/>""",
            "1.6"
        ),
        "education" to buildStrokeCategoryIcon(
            "0 0 20 15",
            """<path d="M3 5.99999L3 12M3 12L4 14H2L3 12ZM16 6.99999V10.85L10.5 14L5 10.85V6.99999M10.4583 1.00317L2.68056 5.77776L10.4583 9.9658L18.2361 5.77776L10.4583 1.00317Z"/>""",
            "1.7"
        ),
        "popculture" to buildStrokeCategoryIcon(
            "0 0 20 15",
            """<path d="M4.26667 8.61538C3.34211 5.52692 2 1 2 1L6.53333 1C6.53333 2.65 7.66667 4.3 9.36667 4.3L9.36667 2.65L9.93333 3.2L11.0667 3.2L11.6333 2.65L11.6333 4.3C13.9 4.3 15.0333 1.55 15.0333 1L19 1C18.5526 2.65 17.6579 7.21923 17.3 8.61538C15.6 8.61538 11.6333 8.7 10.5 12C9.36667 8.7 5.96667 8.61538 4.26667 8.61538Z"/>""",
            "1.6"
        ),
        "universe" to buildStrokeCategoryIcon(
            "0 0 21 20",
            """<circle cx="9.5" cy="9" r="6"/><path d="M4.5 11.5C1.99463 14.4395 1.38564 15.8881 1.99998 16.5C2.80192 17.2988 7.02663 14.7033 11.0697 10.6443C15.1127 6.58533 17.7401 2.64733 16.9382 1.84853C16.3751 1.28769 15 1.5 12.5 3.5"/>""",
            "1.7"
        ),
        "finance" to buildStrokeCategoryIcon(
            "0 0 20 20",
            """<path d="M12.5 7.5C12 7 11.3 6.5 10.5 6.5M10.5 6.5C8.50001 6.5 7.62294 8.18441 8.5 9.5C9.5 11 12.5 10 12.5 12C12.5 14.0615 10 14.5 8 13M10.5 6.5L10.5 5M10.5 14V15.5M19.5 10C19.5 14.9706 15.4706 19 10.5 19C5.52944 19 1.5 14.9706 1.5 10C1.5 5.02944 5.52944 1 10.5 1C15.4706 1 19.5 5.02944 19.5 10Z"/>""",
            "1.7"
        ),
        "lifestyle" to buildStrokeCategoryIcon(
            "0 0 19 17",
            """<path d="M1 6L3.31818 4.63636M18 6L9.5507 1.02982C9.51941 1.01142 9.48059 1.01142 9.4493 1.02982L5.47368 3.36842M1.98421 16H6.26842C6.32365 16 6.36842 15.9552 6.36842 15.9V9.73636C6.36842 9.68114 6.41319 9.63636 6.46842 9.63636H12.5316C12.5868 9.63636 12.6316 9.68114 12.6316 9.73636V15.9C12.6316 15.9552 12.6764 16 12.7316 16H17.4632M6.36842 12.8182H1.98421M17.4632 12.8182H12.6316M17.4632 9.18182H1.98421M13.5263 6H5.02632M3.31818 4.63636V1.55455C3.31818 1.49932 3.36295 1.45455 3.41818 1.45455H5.37368C5.42891 1.45455 5.47368 1.49932 5.47368 1.55455V3.36842M3.31818 4.63636L5.47368 3.36842M9.94737 3.72727H9.05263"/>""",
            "1.5"
        ),
        "spirituality" to buildStrokeCategoryIcon(
            "0 0 18 17",
            """<path d="M9.534 1.01686C5.82724 3.21661 4.60556 8.00479 6.80531 11.7116C9.00506 15.4183 13.7932 16.64 17.5 14.4402"/><path d="M17.2232 15.0203C17.2232 10.7099 13.729 7.21571 9.41869 7.21571C5.10835 7.21571 1.61414 10.7099 1.61414 15.0203"/><path d="M1.49996 14.6408C5.26677 16.7361 10.0189 15.381 12.1142 11.6142C14.2095 7.84744 12.8544 3.09528 9.08765 1"/>""",
            "1.5"
        ),
        "horror" to buildStrokeCategoryIcon(
            "0 0 20 21",
            """<path d="M15.3317 17.2515C17.5565 15.6129 19 12.975 19 10C19 5.02944 16.5 1 10 1C3.5 1 1 5.02944 1 10C1 12.975 2.44351 15.6129 4.66833 17.2515C4.2654 17.5204 4 17.9792 4 18.5C4 19.3284 4.67157 20 5.5 20H6.7C6.86569 20 7 19.8657 7 19.7V18.3C7 18.1343 7.13431 18 7.3 18H8.7C8.86569 18 9 18.1343 9 18.3V19.7C9 19.8657 9.13431 20 9.3 20H10.7C10.8657 20 11 19.8657 11 19.7V18.3C11 18.1343 11.1343 18 11.3 18H12.7C12.8657 18 13 18.1343 13 18.3V19.7C13 19.8657 13.1343 20 13.3 20H14.5C15.3284 20 16 19.3284 16 18.5C16 17.9792 15.7346 17.5204 15.3317 17.2515Z"/><path d="M8 8C8 9.10457 7.10457 10 6 10C4.89543 10 4 9.10457 4 8C4 6.89543 4.89543 6 6 6C7.10457 6 8 6.89543 8 8Z"/><path d="M16 8C16 9.10457 15.1046 10 14 10C12.8954 10 12 9.10457 12 8C12 6.89543 12.8954 6 14 6C15.1046 6 16 6.89543 16 8Z"/><path d="M9.06674 12.4247C9.3956 11.5703 10.6044 11.5703 10.9333 12.4247L11.2089 13.1408C11.461 13.7958 10.9775 14.5 10.2756 14.5H9.72437C9.02248 14.5 8.53899 13.7958 8.79111 13.1408L9.06674 12.4247Z"/>""",
            "1.5"
        ),
        "wildwest" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<path d="M12.546,23.25H11.454A10.7,10.7,0,0,1,2.161,7.235L3.75,4.453V2.25A1.5,1.5,0,0,1,5.25.75h3a1.5,1.5,0,0,1,1.5,1.5v3a2.988,2.988,0,0,1-.4,1.488L7.37,10.211a4.7,4.7,0,0,0,4.084,7.039h1.092a4.7,4.7,0,0,0,4.084-7.039L14.646,6.738a2.988,2.988,0,0,1-.4-1.488v-3a1.5,1.5,0,0,1,1.5-1.5h3a1.5,1.5,0,0,1,1.5,1.5v2.2l1.589,2.782A10.7,10.7,0,0,1,12.546,23.25Z"/><path d="M20.25 4.5L18 4.5"/><path d="M6 4.5L3.75 4.5"/>""",
            "1.6"
        ),
        "home" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<path d="M1 11L12 2L23 11"/><path d="M3 10V20a1 1 0 0 0 1 1h5a1 1 0 0 0 1-1v-5a1 1 0 0 1 1-1h2a1 1 0 0 1 1 1v5a1 1 0 0 0 1 1h5a1 1 0 0 0 1-1V10"/>""",
            "1.7"
        ),
        "default" to buildStrokeCategoryIcon(
            "0 0 24 24",
            """<rect x="1" y="5" width="15" height="14" rx="2" ry="2"/><polygon points="23 7 16 12 23 17 23 7"/>""",
            "1.7"
        ),
    )
}
