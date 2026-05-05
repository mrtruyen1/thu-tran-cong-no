package com.thutran.congno

import android.graphics.Rect
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// Text kèm tọa độ lấy từ Accessibility
data class UiText(val text: String, val rect: Rect)
data class DebtRow(val date: String, val amount: String)
data class DebtResult(val customerName: String, val total: String, val details: List<DebtRow>, val warning: String = "")

object Parser {
    private val vi = Locale("vi", "VN")
    private val dateRegex = Regex("""\d{2}/\d{2}/\d{4}""")
    private val moneyRegex = Regex("""-?\d{1,3}(?:[,.]\d{3})+|-?\d+""")
    private val invoiceRegex = Regex("""^(HD|HDO|TT)[A-Z0-9._-]+$""", RegexOption.IGNORE_CASE)

    private data class ParseResult(val result: DebtResult?, val error: String = "")
    private data class Anchor(val code: String, val box: UiText)
    private data class RowParsed(val code: String, val date: String, val amount: Long, val debtLeft: Long?)
    private data class StopInfo(val stopY: Int, val error: String = "")

    fun parse(items: List<UiText>, template: String): Pair<DebtResult?, String> {
        val clean = normalizeItems(items)
        val joined = clean.joinToString("\n") { it.text }

        // Parser có 2 chế độ:
        // - Tab Công nợ: lấy các dòng Bán hàng còn nợ.
        // - Màn Lập phiếu thu: khi bấm TẠO LẠI sẽ lấy các dòng Cần thu trong Chi tiết phiếu thu.
        val isReceiptScreen = joined.contains("Lập phiếu thu", true) &&
                (joined.contains("Chi tiết phiếu thu", true) || joined.contains("Cần thu", true) || joined.contains("Nợ sau", true))

        val isDebtTab = joined.contains("Công nợ", true) &&
                (joined.contains("Nợ cần thu", true) || joined.contains("Bán hàng", true) || joined.contains("Nợ còn", true)) &&
                !isReceiptScreen

        val parsed = when {
            isReceiptScreen -> parseReceiptScreen(clean)
            isDebtTab -> parseDebtTab(clean)
            else -> ParseResult(null)
        }

        if (parsed.error.isNotBlank()) return null to parsed.error
        val result = parsed.result ?: return null to ""
        return result to buildMessage(result, template)
    }

    fun parseOcrLenient(items: List<UiText>, template: String): Pair<DebtResult?, String> {
        val clean = normalizeItems(items)
        val joined = clean.joinToString("\n") { it.text }
        val isReceiptScreen = joined.contains("Lập phiếu thu", true) &&
                (joined.contains("Chi tiết phiếu thu", true) || joined.contains("Cần thu", true) || joined.contains("Nợ sau", true))
        val isDebtTab = joined.contains("Công nợ", true) &&
                (joined.contains("Nợ cần thu", true) || joined.contains("Bán hàng", true) || joined.contains("Nợ còn", true)) &&
                !isReceiptScreen
        val parsed = when {
            isReceiptScreen -> parseReceiptScreen(clean)
            isDebtTab -> parseDebtTab(clean, strictTotal = false)
            else -> ParseResult(null, "OCR chưa thấy đúng màn hình Công nợ/Lập phiếu thu.")
        }
        if (parsed.error.isNotBlank()) return null to parsed.error
        val result = parsed.result ?: return null to "OCR chưa ghép được dữ liệu công nợ."
        return result to buildMessage(result, template)
    }

    private fun normalizeItems(items: List<UiText>): List<UiText> {
        return items
            .map { UiText(it.text.trim(), Rect(it.rect)) }
            .filter { it.text.isNotBlank() }
            .filter { it.rect.width() > 0 && it.rect.height() > 0 }
            .filter { !isOwnOverlayText(it.text) }
            .filter { !isPreviewContentText(it.text) }
    }

    private fun parseDebtTab(items: List<UiText>, strictTotal: Boolean = true): ParseResult {
        val appBottom = findBottomActionTop(items)
        val appItems = items.filter { it.rect.top < appBottom }
        val sorted = appItems.sortedWith(compareBy<UiText> { it.rect.centerY() }.thenBy { it.rect.left })

        val name = extractName(appItems).ifBlank { "quý khách" }
        val headerTotal = extractDebtTotal(appItems)

        val anchors = findInvoiceAnchors(sorted)
            .filter { it.box.rect.centerY() > 300 && it.box.rect.centerY() < appBottom }
            .sortedBy { it.box.rect.centerY() }

        if (anchors.isEmpty()) return ParseResult(null)

        val stopInfo = findPaymentStopY(sorted, anchors, appBottom)
        if (stopInfo.error.isNotBlank()) return ParseResult(null, stopInfo.error)
        val stopY = stopInfo.stopY

        val rows = mutableListOf<RowParsed>()
        val hdAnchors = anchors.filter {
            (it.code.startsWith("HD") || it.code.startsWith("HDO")) && it.box.rect.centerY() < stopY
        }

        for (i in hdAnchors.indices) {
            val anchor = hdAnchors[i]
            val nextY = hdAnchors.getOrNull(i + 1)?.box?.rect?.centerY() ?: stopY
            val rowTop = anchor.box.rect.centerY() - 28
            val rowBottom = minOf(nextY - 8, stopY - 8, anchor.box.rect.centerY() + 150)
            val rowItems = sorted.filter { it.rect.centerY() in rowTop..rowBottom }

            val row = parseSaleRow(anchor.code, rowItems) ?: continue
            if (row.debtLeft != null && row.debtLeft <= 0L) continue
            if (rows.none { it.code == row.code }) rows.add(row)
        }

        if (rows.isEmpty()) return ParseResult(null)

        val sum = rows.sumOf { it.amount }
        val total = if (headerTotal > 0L) headerTotal else sum

        // 4 + 5: Không cho tạo tin sai. Nếu tổng chi tiết không khớp tổng nợ thì báo lỗi rõ ràng.
        // Đây cũng là cảnh báo thiếu dòng: thường do KiotViet chưa render đủ hoặc màn hình chỉ đang nhìn thấy một phần.
        if (strictTotal && headerTotal > 0L && sum != headerTotal) {
            val missing = headerTotal - sum
            val hint = if (missing > 0L) {
                " Có thể còn thiếu ${formatMoney(missing)} vì app chỉ lấy phần đang nhìn thấy."
            } else {
                " Có thể đã nhặt thừa dữ liệu, vui lòng kiểm tra lại màn hình."
            }
            return ParseResult(
                null,
                "Lỗi: Tổng chi tiết ${formatMoney(sum)} khác Nợ cần thu ${formatMoney(headerTotal)}.${hint}"
            )
        }

        val warning = buildVisibleOnlyWarning(rows.size, headerTotal, sum, stopY, appBottom)

        return ParseResult(
            DebtResult(
                customerName = name,
                total = formatMoney(total),
                details = rows.map { DebtRow(it.date, formatMoney(it.amount)) },
                warning = warning
            )
        )
    }

    private fun buildVisibleOnlyWarning(rowCount: Int, headerTotal: Long, sum: Long, stopY: Int, appBottom: Int): String {
        // 6: App không tự kéo xuống nữa, chỉ lấy các dòng KiotViet đang render trên màn hình.
        // Nếu tổng đã khớp thì cho tạo tin, nhưng vẫn ghi chú ở popup để người dùng biết đây là chế độ an toàn.
        if (headerTotal > 0L && sum == headerTotal) {
            return "Chế độ an toàn: chỉ lấy phần đang nhìn thấy; tổng chi tiết đã khớp Nợ cần thu."
        }
        if (stopY == appBottom && rowCount > 0) {
            return "Cảnh báo: chưa thấy dòng Thanh toán; nếu còn dữ liệu bên dưới, hãy kiểm tra lại."
        }
        return "Chế độ chỉ lấy phần đang nhìn thấy."
    }

    private fun parseSaleRow(code: String, row: List<UiText>): RowParsed? {
        val joined = row.joinToString("\n") { it.text }
        if (!joined.contains("Bán hàng", true)) return null
        if (joined.contains("Thanh toán", true)) return null

        val date = row.mapNotNull { dateRegex.find(it.text)?.value }.firstOrNull() ?: return null
        val debtLeft = extractDebtLeft(row)
        val amount = extractSaleAmount(row) ?: return null
        if (amount <= 0L) return null
        return RowParsed(code, date, amount, debtLeft)
    }

    private fun extractSaleAmount(row: List<UiText>): Long? {
        // Trường hợp KiotViet gom chung 1 node: "Bán hàng\n280,000\nNợ còn: 835,670"
        row.firstOrNull { it.text.contains("Bán hàng", true) }?.let { saleNode ->
            val segment = saleNode.text
                .substringAfter("Bán hàng", "")
                .substringBefore("Nợ còn", "")
                .substringBefore("No con", "")
            moneyValues(segment).firstOrNull { it > 0L }?.let { return it }
        }

        val saleBox = row.firstOrNull { it.text.contains("Bán hàng", true) }
        val debtBox = row.firstOrNull { it.text.contains("Nợ còn", true) || it.text.contains("No con", true) }
        val saleY = saleBox?.rect?.centerY() ?: row.minOfOrNull { it.rect.centerY() } ?: return null
        val debtY = debtBox?.rect?.centerY() ?: (saleY + 95)
        val screenRight = row.maxOfOrNull { it.rect.right } ?: 720
        val rightStart = (screenRight * 0.55).toInt()

        // Số tiền bán hàng là số riêng ở cột phải, nằm giữa chữ "Bán hàng" và dòng "Nợ còn".
        // KHÔNG loại theo debtLeft vì có dòng amount = debtLeft, ví dụ 99,000 / Nợ còn 99,000.
        val candidates = row
            .filter { it.rect.centerX() >= rightStart }
            .filter { it.rect.centerY() in (saleY - 5)..(debtY - 1) }
            .filter { !it.text.contains("Bán hàng", true) }
            .filter { !it.text.contains("Nợ còn", true) && !it.text.contains("No con", true) }
            .filter { !it.text.contains("Thanh toán", true) }
            .flatMap { item -> moneyValues(item.text).map { item to it } }
            .filter { (_, value) -> value > 0L }
            .sortedWith(compareBy<Pair<UiText, Long>> { abs(it.first.rect.centerY() - saleY) }.thenByDescending { it.first.rect.left })

        return candidates.firstOrNull()?.second
    }

    private fun findPaymentStopY(items: List<UiText>, anchors: List<Anchor>, appBottom: Int): StopInfo {
        val tt = anchors.firstOrNull { it.code.startsWith("TT") && it.box.rect.centerY() < appBottom }
            ?: return StopInfo(appBottom)

        val y = tt.box.rect.centerY()
        val nextAnchorY = anchors.firstOrNull { it.box.rect.centerY() > y }?.box?.rect?.centerY() ?: (y + 150)
        val row = items.filter { it.rect.centerY() in (y - 25)..minOf(nextAnchorY - 8, y + 150) }
        val debtLeft = extractDebtLeft(row)

        if (debtLeft != null && debtLeft != 0L) {
            return StopInfo(y, "Lỗi: Có dòng Thanh toán nhưng Nợ còn khác 0. Vui lòng kiểm tra lại công nợ.")
        }
        return StopInfo(y)
    }

    private fun findInvoiceAnchors(items: List<UiText>): List<Anchor> {
        val raw = mutableListOf<Anchor>()
        for (item in items) {
            val firstLine = item.text.lines().firstOrNull()?.trim() ?: item.text.trim()
            val code = firstLine.split(Regex("\\s+")).firstOrNull()?.uppercase(Locale.US) ?: ""
            if (invoiceRegex.matches(code)) raw.add(Anchor(code, item))
        }
        return raw.groupBy { it.code }.map { (_, list) -> list.minBy { it.box.rect.left } }
    }

    private fun extractDebtLeft(row: List<UiText>): Long? {
        for (item in row) {
            if (item.text.contains("Nợ còn", true) || item.text.contains("No con", true)) {
                val after = item.text.substringAfter(":", item.text.substringAfter("Nợ còn", item.text))
                if (after.trim() == "0") return 0L
                moneyValues(after).firstOrNull()?.let { return it }
            }
        }
        return null
    }

    private fun extractDebtTotal(items: List<UiText>): Long {
        val label = items
            .filter { it.text.contains("Nợ cần thu", true) }
            .minByOrNull { it.rect.top } ?: return 0L

        // Nếu cùng node: "Nợ cần thu: 835,670 đã 115 ngày"
        moneyValues(label.text.substringAfter("Nợ cần thu", label.text))
            .filter { it >= 1000L }
            .maxOrNull()?.let { return it }

        val y = label.rect.centerY()
        val sameLine = items
            .filter { abs(it.rect.centerY() - y) < 45 }
            .filter { !it.text.contains("bản ghi", true) }
            .filter { !it.text.contains("ngày", true) || moneyValues(it.text).any { v -> v >= 1000L } }

        return sameLine.flatMap { moneyValues(it.text) }
            .filter { it >= 1000L }
            .maxOrNull() ?: 0L
    }

    private fun findBottomActionTop(items: List<UiText>): Int {
        val buttons = items
            .filter { it.text.equals("Điều chỉnh", true) || it.text.equals("Thanh toán", true) }
            .filter { it.rect.top > 1000 }
            .sortedBy { it.rect.top }
        return buttons.firstOrNull()?.rect?.top ?: Int.MAX_VALUE
    }

    private fun parseReceiptScreen(items: List<UiText>): ParseResult {
        val name = extractReceiptName(items).ifBlank { "quý khách" }
        val total = extractReceiptTotal(items)
        val sorted = items.sortedWith(compareBy<UiText> { it.rect.centerY() }.thenBy { it.rect.left })
        val anchors = findInvoiceAnchors(sorted)
            .filter { it.code.startsWith("HD") || it.code.startsWith("HDO") }
            .sortedBy { it.box.rect.centerY() }

        val rows = mutableListOf<DebtRow>()
        for (i in anchors.indices) {
            val a = anchors[i]
            val nextY = anchors.getOrNull(i + 1)?.box?.rect?.centerY() ?: (a.box.rect.centerY() + 140)
            val row = sorted.filter { it.rect.centerY() in (a.box.rect.centerY() - 20)..(nextY - 8) }
            val date = row.mapNotNull { dateRegex.find(it.text)?.value }.firstOrNull() ?: continue
            val canThu = row.firstNotNullOfOrNull { item ->
                if (item.text.contains("Cần thu", true) || item.text.contains("Can thu", true)) {
                    moneyValues(item.text).firstOrNull { it > 0L }
                } else null
            } ?: continue
            rows.add(DebtRow(date, formatMoney(canThu)))
        }

        if (total <= 0L && rows.isEmpty()) return ParseResult(null)
        val finalTotal = if (total > 0L) total else rows.sumOf { parseMoney(it.amount) ?: 0L }
        return ParseResult(DebtResult(name, formatMoney(finalTotal), rows.distinct(), warning = "Chế độ chỉ lấy phần đang nhìn thấy"))
    }


    private fun extractReceiptName(items: List<UiText>): String {
        // FIX DỨT ĐIỂM TÊN Ở MÀN "LẬP PHIẾU THU"
        // Nguyên tắc: chỉ lấy tên trong card khách hàng ngay dưới tiêu đề,
        // ưu tiên text nằm cùng vùng với dòng "Nợ:" / ngày giờ.
        // Không lấy từ popup preview, nút Lưu, nút Tạo lại, app bar hoặc template tin nhắn.
        val candidateLines = mutableListOf<Pair<String, Rect>>()

        fun addCandidate(raw: String, rect: Rect) {
            raw.split('\n', '\r')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line -> candidateLines.add(line to rect) }
        }

        items.forEach { item -> addCandidate(item.text, item.rect) }

        val debtY = items.firstOrNull { it.text.contains("Nợ:", true) }?.rect?.centerY()
        val dateY = items.firstOrNull { dateRegex.containsMatchIn(it.text) }?.rect?.centerY()

        val topLimit = 135
        val bottomLimit = when {
            debtY != null -> debtY + 15
            dateY != null -> dateY + 15
            else -> 340
        }

        fun isGoodReceiptName(text: String): Boolean {
            val s = text.trim()
            if (s.length !in 3..70) return false
            if (s.any { it.isDigit() }) return false
            if (s.contains(":")) return false
            if (s.contains("/")) return false
            if (s.contains("http", true)) return false
            if (s.equals("Lưu", true)) return false
            if (isOwnOverlayText(s) || isPreviewContentText(s)) return false

            val lower = s.lowercase(vi)
            val junk = listOf(
                "lập phiếu thu", "thu từ khách", "tiền mặt", "chi tiết phiếu thu", "tiền thu",
                "cần thu", "nợ", "nợ sau", "thanh toán hóa đơn", "cộng vào tk khách", "ghi chú",
                "di chuyển lên", "quay lại", "đóng", "hủy", "menu", "more options", "tạo lại", "copy",
                "xem trước công nợ", "quý khách", "thông tin chuyển khoản", "shop xin gửi",
                "tổng công nợ", "xin cảm ơn", "trần lệ thu", "bidv"
            )
            if (junk.any { lower == it || lower.contains(it) }) return false

            // Tên khách KiotViet thường có ít nhất 2 từ. Cho phép tiền tố 1 chữ như "C ...", "E ...".
            val words = s.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size < 2) return false
            return true
        }

        // Ưu tiên vùng card khách hàng: dưới app bar, trước dòng ngày/Nợ, cột trái/phần giữa.
        val cardNames = candidateLines
            .filter { (_, r) -> r.centerY() in topLimit..bottomLimit }
            .filter { (_, r) -> r.left in 35..520 && r.right <= 650 }
            .map { it.first }
            .filter { isGoodReceiptName(it) }
            .distinct()
            .sortedWith(
                compareByDescending<String> { scoreName(it) }
                    .thenByDescending { it.length }
            )
        if (cardNames.isNotEmpty()) return fixName(cardNames.first())

        // Fallback: lấy text tốt nhất trong nửa trên màn hình nhưng vẫn cấm toàn bộ popup/template.
        val fallback = candidateLines
            .filter { (_, r) -> r.centerY() in 135..360 && r.left in 35..620 }
            .map { it.first }
            .filter { isGoodReceiptName(it) }
            .distinct()
            .maxByOrNull { scoreName(it) }

        return fixName(fallback ?: "")
    }

    private fun extractReceiptTotal(items: List<UiText>): Long {
        val debtLine = items.firstOrNull { it.text.contains("Nợ:", true) }
        if (debtLine != null) {
            moneyValues(debtLine.text.substringAfter("Nợ:", debtLine.text)).firstOrNull { it >= 1000L }?.let { return it }
            val y = debtLine.rect.centerY()
            items.filter { abs(it.rect.centerY() - y) < 45 }
                .flatMap { moneyValues(it.text) }
                .filter { it >= 1000L }
                .maxOrNull()?.let { return it }
        }
        val noSau = items.firstOrNull { it.text.contains("Nợ sau", true) || it.text.contains("No sau", true) }
        if (noSau != null) {
            val y = noSau.rect.centerY()
            return items.filter { abs(it.rect.centerY() - y) < 55 }
                .flatMap { moneyValues(it.text) }
                .filter { it >= 1000L }
                .maxOrNull() ?: 0L
        }
        return 0L
    }

    private fun extractName(items: List<UiText>): String {
        // Tên khách phải lấy ở thanh tiêu đề KiotViet, không lấy contentDescription của nút Back
        // như "Di chuyển lên" và không lấy text trong popup preview của app mình.
        val headerCandidates = items.filter {
            val t = it.text.trim()
            val y = it.rect.centerY()
            t.length in 2..70 &&
                    y in 65..160 &&
                    it.rect.left >= 75 &&
                    it.rect.right <= 620 &&
                    !t.contains("/") && !t.contains(":") && !t.any { c -> c.isDigit() } &&
                    !isJunkName(t)
        }

        headerCandidates
            .maxByOrNull { scoreHeaderName(it) }
            ?.text
            ?.let { return fixName(it) }

        // Fallback cho một vài màn KiotViet đặt tiêu đề hơi thấp/cao hơn.
        val fallback = items.filter {
            val t = it.text.trim()
            val y = it.rect.centerY()
            t.length in 2..70 &&
                    y in 55..220 &&
                    it.rect.left >= 75 &&
                    !t.contains("/") && !t.contains(":") && !t.any { c -> c.isDigit() } &&
                    !isJunkName(t)
        }
        return fixName(fallback.maxByOrNull { scoreHeaderName(it) }?.text ?: "")
    }

    private fun isPreviewContentText(text: String): Boolean {
        val l = text.trim().lowercase(vi)
        return l.contains("xem trước công nợ") ||
                l.startsWith("kính gửi") ||
                l.startsWith("đến ngày") ||
                l == "chi tiết:" ||
                l.startsWith("quý khách vui lòng") ||
                l.startsWith("thông tin chuyển khoản") ||
                l == "trần lệ thu" ||
                l.startsWith("bidv") ||
                l.startsWith("xin cảm ơn")
    }

    private fun isOwnOverlayText(text: String): Boolean {
        val t = text.trim().lowercase(vi)
        return t == "copy" || t == "hủy" || t == "huy" || t == "tạo lại" || t == "📋 tạo lại" || t == "auto"
    }

    private fun isJunkName(s: String): Boolean {
        val l = s.lowercase(vi)
        return listOf(
            "lập phiếu thu", "thông tin", "lịch sử", "công nợ", "tích điểm", "bán hàng",
            "thanh toán", "nợ cần thu", "bản ghi", "xem trước", "tạo lại", "thu từ khách",
            "tiền mặt", "ghi chú", "chi tiết phiếu thu", "thanh toán hóa đơn", "cộng vào tk khách",
            "nợ sau", "tiền thu", "điều chỉnh", "di chuyển lên", "navigate up",
            "quay lại", "back", "chỉnh sửa", "sửa", "thêm", "menu", "more options"
        ).any { l.contains(it) }
    }

    private fun scoreHeaderName(item: UiText): Int {
        val s = item.text.trim()
        var score = 1000
        // Tên khách ở KiotViet thường nằm trên app bar, bắt đầu khoảng x=90-130.
        score -= abs(item.rect.left - 105)
        score -= abs(item.rect.centerY() - 105) * 2
        score += s.length * 3
        if (s.any { it in 'À'..'ỹ' }) score += 40
        if (s.split(Regex("\\s+")).size >= 2) score += 35
        return score
    }

    private fun scoreName(s: String): Int {
        var score = s.length
        if (s.any { it in 'À'..'ỹ' }) score += 20
        if (s.split(Regex("\\s+")).size >= 2) score += 15
        return score
    }

    private fun moneyValues(text: String): List<Long> = moneyRegex.findAll(text).mapNotNull { parseMoney(it.value) }.toList()

    private fun parseMoney(raw: String): Long? {
        val t0 = raw.trim()
        if (t0.isBlank()) return null
        val hasSep = t0.contains(",") || t0.contains(".")
        val cleaned = t0
            .replace(",", "")
            .replace(".", "")
            .replace("đ", "", true)
            .replace("₫", "")
            .replace(Regex("[^0-9-]"), "")
        if (cleaned.isBlank() || cleaned == "-") return null
        val v = cleaned.toLongOrNull() ?: return null
        // Bỏ các số nhỏ không có dấu phân tách như 11 bản ghi, 115 ngày, số 5 sót OCR/accessibility.
        if (!hasSep && v in 1..999) return null
        return v
    }

    fun formatMoney(value: Long): String = "%,dđ".format(Locale.US, value)

    fun formatMoney(raw: String): String = parseMoney(raw)?.let { formatMoney(it) } ?: if (raw.endsWith("đ")) raw else "${raw}đ"

    private fun fixName(raw: String): String {
        // Giữ nguyên tên khách như KiotViet/OCR trả về, không tự sửa chính tả,
        // không tự đổi hoa/thường, không tự xoá chữ đầu.
        // Chỉ trim khoảng trắng đầu/cuối để tránh xuống dòng thừa trong tin nhắn.
        return raw.trim()
    }

    fun buildMessage(r: DebtResult, template: String): String {
        val today = SimpleDateFormat("dd/MM/yyyy", vi).format(Date())

        // Chi tiết công nợ chỉ hiện STT + ngày + số tiền.
        // Ví dụ: 1. 05/02/2026: 280,000đ
        val details = r.details.mapIndexed { index, row ->
            "${index + 1}. ${row.date}: ${row.amount}"
        }.joinToString("\n")

        return template
            .replace("{ten}", r.customerName)
            .replace("{ngay}", today)
            .replace("{tong}", r.total)
            .replace("{chitiet}", details)
            .trim()
    }
}
