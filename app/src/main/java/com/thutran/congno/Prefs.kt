package com.thutran.congno

import android.content.Context

object Prefs {
    private const val P = "thu_tran_cong_no"
    private const val K_TEMPLATE = "template"
    private const val K_AUTO_DELAY_MS = "auto_delay_ms"

    val defaultTemplate = """
Kính gửi **{ten}**,

Đến ngày {ngay}, tổng công nợ của quý khách là: **{tong}**

Chi tiết:
{chitiet}

Quý khách vui lòng kiểm tra và thanh toán giúp.

Thông tin chuyển khoản:
Trần Lệ Thu
BIDV 0384882883

Xin cảm ơn!
""".trimIndent()

    fun getTemplate(context: Context): String =
        context.getSharedPreferences(P, Context.MODE_PRIVATE).getString(K_TEMPLATE, defaultTemplate) ?: defaultTemplate

    fun setTemplate(context: Context, template: String) {
        context.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString(K_TEMPLATE, template).apply()
    }

    fun getAutoDelayMs(context: Context): Int =
        context.getSharedPreferences(P, Context.MODE_PRIVATE).getInt(K_AUTO_DELAY_MS, 500).coerceIn(100, 2000)

    fun setAutoDelayMs(context: Context, delayMs: Int) {
        context.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
            .putInt(K_AUTO_DELAY_MS, delayMs.coerceIn(100, 2000))
            .apply()
    }
}

