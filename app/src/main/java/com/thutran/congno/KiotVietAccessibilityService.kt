package com.thutran.congno

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class KiotVietAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var lastMessage = ""
    private var floating: TextView? = null
    private var floatingParams: WindowManager.LayoutParams? = null
    private var floatingDownX = 0f
    private var floatingDownY = 0f
    private var floatingStartX = 0
    private var floatingStartY = 0
    private var floatingDragging = false
    private var previewView: LinearLayout? = null
    private var lastStableSignature = ""
    private var autoRetryCount = 0
    private var lastFacebookLink: String = ""
    private var lastFacebookCustomerName: String = ""
    private var currentPreviewCustomerName: String = ""
    private var scheduledScanRunnable: Runnable? = null

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Khi popup đang mở thì không tự đọc lại, tránh đọc nhầm chính popup của app.
        // Riêng nếu người dùng chuyển sang tab Thông tin thì đóng popup cũ để không hiểu nhầm là app vừa tạo tin ở tab này.
        if (previewView != null) {
            tryClosePreviewOnInfoTab()
            return
        }
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                autoRetryCount = 0
                scheduleAutoScan(Prefs.getAutoDelayMs(this).toLong())
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                autoRetryCount = 0
                scheduleAutoScan(Prefs.getAutoDelayMs(this).toLong())
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                autoRetryCount = 0
                scheduleAutoScan(Prefs.getAutoDelayMs(this).toLong())
            }
        }
    }

    override fun onInterrupt() {}


    private fun tryClosePreviewOnInfoTab() {
        try {
            val root = rootInActiveWindow ?: return
            val items = mutableListOf<UiText>()
            collectText(root, items)
            val joined = items.joinToString("\n") { it.text }
            if (isKiotVietInfoTab(joined)) {
                removePreview()
                showButton()
            }
        } catch (_: Exception) {
        }
    }

    private fun scheduleAutoScan(delayMs: Long) {
        scheduledScanRunnable?.let { handler.removeCallbacks(it) }
        val task = Runnable { safeScanCurrentScreenAutoPopup() }
        scheduledScanRunnable = task
        // Chờ KiotViet render đủ dữ liệu ở tab Công nợ rồi mới đọc.
        // Tránh lấy nhầm dữ liệu khi vừa chuyển tab quá nhanh.
        handler.postDelayed(task, delayMs.coerceIn(100, 2000))
    }

    private fun safeScanCurrentScreenAutoPopup() {
        try {
            scanCurrentScreenAutoPopup()
        } catch (e: Exception) {
            showButton()
        }
    }

    private fun scanCurrentScreenAutoPopup() {
        if (previewView != null) return
        val root = rootInActiveWindow ?: return
        val items = mutableListOf<UiText>()
        collectText(root, items)

        val joined = items.joinToString("\n") { it.text }

        updateFacebookLinkFromItems(items)

        // Tab Thông tin chỉ dùng để cập nhật tên/link Facebook.
        // Tuyệt đối không tạo báo cáo/cảnh báo công nợ ở màn này.
        if (isKiotVietInfoTab(joined)) {
            lastStableSignature = ""
            autoRetryCount = 0
            scheduledScanRunnable?.let { handler.removeCallbacks(it) }
            removePreview()
            showButton()
            return
        }

        // 1.3: Nếu còn sót text của popup preview thì bỏ qua, không đọc lại chính báo cáo của app.
        if (isOwnPreviewText(joined)) return

        // Chỉ auto hiện thông báo ở tab Công nợ khi đã thấy dòng Bán hàng.
        // Nếu mới vào tab mà KiotViet chưa render dòng bán hàng thì đợi thêm, không lấy nhầm số liệu.
        // Màn Lập phiếu thu chỉ chạy khi người dùng bấm TẠO LẠI.
        if (!isKiotVietDebtTabReadyForAuto(joined)) {
            if (isKiotVietDebtTabLoading(joined) && autoRetryCount < 5) {
                autoRetryCount++
                scheduleAutoScan(Prefs.getAutoDelayMs(this).toLong())
            }
            return
        }

        val signature = items
            .sortedWith(compareBy<UiText> { it.rect.top }.thenBy { it.rect.left })
            .joinToString("|") { "${it.text}@${it.rect.left},${it.rect.top}" }
            .take(1600)

        if (signature == lastStableSignature) return

        val (result, message) = Parser.parse(items, Prefs.getTemplate(this))
        if (result != null && message.isNotBlank()) {
            lastStableSignature = signature
            lastMessage = message
            currentPreviewCustomerName = result.customerName
            showPreviewPopup(message, result.warning)
            showButton()
            return
        }

        // Khi lỗi thật sự (ví dụ công nợ nhiều dòng, tổng chi tiết không khớp) thì hiện popup lỗi thay vì im lặng.
        if (message.startsWith("Lỗi:", true) || message.startsWith("Cảnh báo:", true)) {
            lastStableSignature = signature
            showStatusPopup(message)
            showButton()
            return
        }

        // Vừa vào tab KiotViet đôi khi Accessibility chưa trả đủ node. Thử lại vài lần, không báo lỗi giả.
        if (autoRetryCount < 4) {
            autoRetryCount++
            scheduleAutoScan(170)
        } else {
            showStatusPopup("Cảnh báo: Chưa đọc đủ dữ liệu công nợ. Hãy chờ KiotViet tải xong hoặc bấm TẠO LẠI.")
            showButton()
        }
    }

    private fun forceCreateMessageFromCurrentScreen() {
        removePreview()
        removeButton()
        // Đợi overlay của app biến mất khỏi accessibility tree rồi mới đọc KiotViet phía sau.
        handler.postDelayed({
            try {
                val root = rootInActiveWindow
                if (root == null) {
                    showStatusPopup("Lỗi: Không đọc được màn hình hiện tại. Hãy mở lại tab Công nợ rồi bấm TẠO LẠI.")
                    showButton()
                    return@postDelayed
                }
                val items = mutableListOf<UiText>()
                collectText(root, items)
                createMessageFromItems(items, showToastOnError = true)
            } catch (e: Exception) {
                showStatusPopup("Lỗi: App gặp lỗi khi đọc màn hình. Hãy thử thoát popup KiotViet rồi bấm TẠO LẠI.")
                showButton()
            }
        }, 120)
    }

    private fun createMessageFromItems(items: List<UiText>, showToastOnError: Boolean) {
        val joined = items.joinToString("\n") { it.text }

        updateFacebookLinkFromItems(items)

        // Tab Thông tin chỉ cập nhật tên/link Facebook, không tạo báo cáo và không hiện cảnh báo công nợ.
        if (isKiotVietInfoTab(joined)) {
            if (showToastOnError) {
                Toast.makeText(this, "Tab Thông tin chỉ dùng để đọc link Facebook", Toast.LENGTH_SHORT).show()
            }
            showButton()
            return
        }

        // 1.3: Không parse nếu đang đọc nhầm popup của chính app.
        if (isOwnPreviewText(joined)) {
            if (showToastOnError) {
                Toast.makeText(this, "Đang mở popup xem trước, hãy bấm TẠO LẠI lần nữa", Toast.LENGTH_SHORT).show()
            }
            showButton()
            return
        }

        // Cho phép TẠO LẠI ở tab Công nợ hoặc màn Lập phiếu thu.
        // Màn Lập phiếu thu nhận diện bằng từ khóa "Lập phiếu thu" và sẽ parse các dòng "Cần thu".
        if (!isKiotVietDebtTab(joined) && !isKiotVietReceiptScreen(joined)) {
            if (showToastOnError) {
                Toast.makeText(this, "Chỉ tạo báo cáo khi ở tab Công nợ hoặc màn Lập phiếu thu", Toast.LENGTH_SHORT).show()
            }
            showButton()
            return
        }

        val (result, message) = Parser.parse(items, Prefs.getTemplate(this))
        if (result != null && message.isNotBlank()) {
            lastMessage = message
            currentPreviewCustomerName = result.customerName
            showPreviewPopup(message, result.warning)
            showButton()
        } else if (message.startsWith("Lỗi:", true) || message.startsWith("Cảnh báo:", true)) {
            showStatusPopup(message)
            showButton()
        } else if (showToastOnError) {
            Toast.makeText(this, "Màn hình này chưa đủ dữ liệu để tạo tin nhắn", Toast.LENGTH_SHORT).show()
            showButton()
        } else {
            showButton()
        }
    }

    private fun forceCreateMessageByOcr() {
        removePreview()
        removeButton()
        handler.postDelayed({
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                showStatusPopup("OCR cần Android 11 trở lên. Hãy dùng nút TẠO LẠI theo cách cũ.")
                showButton()
                return@postDelayed
            }
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        val bitmap = try {
                            Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                                ?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (_: Exception) {
                            null
                        } finally {
                            try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        }

                        if (bitmap == null) {
                            showStatusPopup("OCR chưa chụp được màn hình. Hãy tắt/bật lại quyền Trợ năng của app rồi thử lại.")
                            showButton()
                            return
                        }

                        val image = InputImage.fromBitmap(bitmap, 0)
                        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                        recognizer.process(image)
                            .addOnSuccessListener { visionText ->
                                val items = visionText.textBlocks.flatMap { block ->
                                    block.lines.mapNotNull { line ->
                                        val box = line.boundingBox ?: return@mapNotNull null
                                        val text = line.text.trim()
                                        if (text.isBlank()) null else UiText(text, Rect(box))
                                    }
                                }
                                if (items.isEmpty()) {
                                    showStatusPopup("OCR chưa nhận được chữ. Hãy để màn hình KiotViet sáng rõ rồi bấm OCR lại.")
                                    showButton()
                                } else {
                                    createMessageFromOcrItems(items)
                                }
                            }
                            .addOnFailureListener {
                                showStatusPopup("OCR không đọc được chữ. Hãy kiểm tra mạng lần đầu tải ML Kit hoặc dùng TẠO LẠI.")
                                showButton()
                            }
                    }

                    override fun onFailure(errorCode: Int) {
                        showStatusPopup("OCR chưa chụp được màn hình. Hãy tắt/bật lại quyền Trợ năng của app rồi thử lại. Mã lỗi: $errorCode")
                        showButton()
                    }
                })
            } catch (e: Exception) {
                showStatusPopup("OCR gặp lỗi khi chụp màn hình. Hãy dùng TẠO LẠI hoặc tắt/bật lại quyền Trợ năng.")
                showButton()
            }
        }, 160)
    }

    private fun createMessageFromOcrItems(items: List<UiText>) {
        try {
            val joined = items.joinToString("\n") { it.text }
            if (!isKiotVietDebtTab(joined) && !isKiotVietReceiptScreen(joined)) {
                showStatusPopup("OCR đã đọc chữ nhưng chưa thấy tab Công nợ/Lập phiếu thu. Hãy mở đúng màn hình rồi bấm OCR lại.")
                showButton()
                return
            }

            val (result, message) = Parser.parseOcrLenient(items, Prefs.getTemplate(this))
            if (result != null && message.isNotBlank()) {
                // OCR hay bị mất/nhầm ký tự đầu tên do icon mũi tên back che ở thanh tiêu đề.
                // Vì vậy phần TÊN KHÁCH luôn ưu tiên lấy nguyên văn từ Accessibility hiện tại,
                // OCR chỉ dùng để lấy dòng ngày/tiền công nợ.
                val accessibilityItems = mutableListOf<UiText>()
                collectText(rootInActiveWindow, accessibilityItems)
                val originalName = extractHeaderCustomerName(accessibilityItems)
                val finalResult = if (originalName.isNotBlank() && originalName != result.customerName) {
                    result.copy(customerName = originalName)
                } else result
                val finalMessage = if (finalResult !== result) {
                    Parser.buildMessage(finalResult, Prefs.getTemplate(this))
                } else message

                lastMessage = finalMessage
                currentPreviewCustomerName = finalResult.customerName
                showPreviewPopup(finalMessage, finalResult.warning.ifBlank { "OCR: chỉ lấy ngày/tiền; tên khách giữ nguyên theo KiotViet." })
            } else {
                showStatusPopup(if (message.isNotBlank()) message else "OCR đã đọc chữ nhưng chưa ghép được dữ liệu công nợ. Hãy dùng TẠO LẠI theo cách cũ.")
            }
        } catch (_: Exception) {
            showStatusPopup("OCR đã đọc chữ nhưng lỗi khi xử lý dữ liệu. Hãy dùng TẠO LẠI theo cách cũ.")
        } finally {
            showButton()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, out: MutableList<UiText>) {
        if (node == null) return

        // 1.3: Bỏ qua toàn bộ node thuộc overlay của chính app để không đọc lại popup/nút nổi.
        if (node.packageName?.toString() == packageName) return

        val r = Rect()
        node.getBoundsInScreen(r)

        node.text?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            out.add(UiText(it, Rect(r)))
        }

        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotBlank() }?.let {
            out.add(UiText(it, Rect(r)))
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), out)
        }
    }


    private fun isKiotVietDebtTabReadyForAuto(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        return text.contains("công nợ") &&
                text.contains("bán hàng") &&
                !isKiotVietReceiptScreen(joinedText) &&
                !text.contains("thông tin chuyển khoản") &&
                !isOwnPreviewText(joinedText)
    }

    private fun isKiotVietDebtTabLoading(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        return text.contains("công nợ") &&
                (text.contains("nợ cần thu") || text.contains("nợ còn") || text.contains("bản ghi")) &&
                !text.contains("bán hàng") &&
                !isKiotVietReceiptScreen(joinedText) &&
                !isOwnPreviewText(joinedText)
    }

    private fun isKiotVietDebtTab(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        val hasDebtTab = text.contains("công nợ")
        val hasDebtContent = text.contains("nợ cần thu") || text.contains("bán hàng") || text.contains("nợ còn")
        val isWrongScreen = isKiotVietReceiptScreen(joinedText) || text.contains("thông tin chuyển khoản")
        return hasDebtTab && hasDebtContent && !isWrongScreen
    }

    private fun isKiotVietReceiptScreen(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        return text.contains("lập phiếu thu") &&
                (text.contains("chi tiết phiếu thu") || text.contains("cần thu") || text.contains("nợ sau")) &&
                !text.contains("xem trước công nợ")
    }

    private fun isOwnPreviewText(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        return text.contains("xem trước công nợ") ||
                text.contains("thông tin chuyển khoản") ||
                text.contains("bidv 0384882883") ||
                text.contains("xin cảm ơn")
    }

    private fun showButton() {
        if (floating != null) return

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val prefs = getSharedPreferences("floating_button", Context.MODE_PRIVATE)
        val btn = TextView(this).apply {
            text = "📋"
            textSize = 18f
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.argb(210, 230, 230, 230))
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setIncludeFontPadding(false)
            setPadding(10, 6, 10, 6)
            setOnLongClickListener {
                removeButton()
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("x", resources.displayMetrics.widthPixels - 72)
            y = prefs.getInt("y", 160)
        }

        btn.setOnTouchListener { _, event ->
            val lp = floatingParams ?: params
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    floatingDownX = event.rawX
                    floatingDownY = event.rawY
                    floatingStartX = lp.x
                    floatingStartY = lp.y
                    floatingDragging = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - floatingDownX).toInt()
                    val dy = (event.rawY - floatingDownY).toInt()
                    if (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10) {
                        floatingDragging = true
                        lp.x = (floatingStartX + dx).coerceIn(0, resources.displayMetrics.widthPixels - 48)
                        lp.y = (floatingStartY + dy).coerceIn(0, resources.displayMetrics.heightPixels - 48)
                        try { wm.updateViewLayout(btn, lp) } catch (_: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    if (floatingDragging) {
                        prefs.edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                    } else {
                        forceCreateMessageFromCurrentScreen()
                    }
                    true
                }

                else -> false
            }
        }

        try {
            wm.addView(btn, params)
            floating = btn
            floatingParams = params
        } catch (_: Exception) {}
    }

    private fun hasUsableFacebookLink(): Boolean {
        val prefs = getSharedPreferences("customer_link", Context.MODE_PRIVATE)
        val storedCustomer = lastFacebookCustomerName.ifBlank { prefs.getString("facebook_customer", "") ?: "" }
        val link = lastFacebookLink.ifBlank { prefs.getString("facebook_link", "") ?: "" }

        // Chống lỗi lớn: không dùng link Facebook cũ của khách trước.
        // Chỉ hiện COPY + FB khi link đang lưu đúng với khách đang xem preview.
        if (currentPreviewCustomerName.isNotBlank() && storedCustomer.isNotBlank() &&
            !sameCustomerName(currentPreviewCustomerName, storedCustomer)
        ) return false

        if (link.isBlank()) return false
        if (!link.contains("facebook.com", true) && !link.contains("fb.com", true)) return false
        if (link.endsWith("/share/", true) || link.endsWith("/share", true)) return false
        if (link.contains("...", true) || link.contains("…", true)) return false
        if (link.length < 25) return false
        return true
    }

    private fun showPreviewPopup(message: String, warning: String = "") {
        if (message.isBlank()) return
        removePreview()

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            text = if (warning.isNotBlank()) "📋 Xem trước công nợ\n⚠️ $warning" else "📋 Xem trước công nợ"
            textSize = if (warning.isNotBlank()) 16f else 20f
            setTextColor(Color.BLACK)
        }

        val msg = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 12, 0, 12)
        }

        val scroll = ScrollView(this).apply { addView(msg) }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val copy = Button(this).apply {
            text = "COPY"
            setOnClickListener {
                copy(message)
                Toast.makeText(this@KiotVietAccessibilityService, "Đã copy công nợ", Toast.LENGTH_SHORT).show()
                removePreview()
            }
        }

        val canOpenFacebook = hasUsableFacebookLink()

        val copyFacebook = Button(this).apply {
            text = "COPY + FB"
            setOnClickListener {
                copy(message)
                Toast.makeText(this@KiotVietAccessibilityService, "Đã copy, đang mở Facebook", Toast.LENGTH_SHORT).show()
                removePreview()
                openCustomerFacebook()
            }
        }

        val remake = Button(this).apply {
            text = "TẠO LẠI"
            setOnClickListener { forceCreateMessageFromCurrentScreen() }
        }

        val ocr = Button(this).apply {
            text = "OCR"
            setOnClickListener { forceCreateMessageByOcr() }
        }

        val cancel = Button(this).apply {
            text = "HỦY"
            setOnClickListener { removePreview() }
        }

        // Nếu đã đọc được link Facebook hợp lệ: hiện COPY và COPY + FB.
        // Nếu chưa có link Facebook: chỉ hiện COPY, tránh bấm nhầm nút không mở được.
        row.addView(remake)
        row.addView(ocr)
        row.addView(copy)
        if (canOpenFacebook) {
            row.addView(copyFacebook)
        }
        row.addView(cancel)

        container.addView(title)
        container.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        container.addView(row)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94).toInt(),
            (resources.displayMetrics.heightPixels * 0.68).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            wm.addView(container, params)
            previewView = container
        } catch (_: Exception) {
            copy(message)
        }
    }

    private fun showStatusPopup(text: String) {
        removePreview()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 20)
            setBackgroundColor(Color.WHITE)
        }

        val title = TextView(this).apply {
            this.text = "⚠️ Thông báo"
            textSize = 20f
            setTextColor(Color.BLACK)
        }

        val msg = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 14, 0, 14)
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val remake = Button(this).apply {
            this.text = "TẠO LẠI"
            setOnClickListener { forceCreateMessageFromCurrentScreen() }
        }

        val ocr = Button(this).apply {
            this.text = "OCR"
            setOnClickListener { forceCreateMessageByOcr() }
        }

        val cancel = Button(this).apply {
            this.text = "ĐÓNG"
            setOnClickListener { removePreview() }
        }

        row.addView(remake)
        row.addView(ocr)
        row.addView(cancel)
        container.addView(title)
        container.addView(msg)
        container.addView(row)

        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER }

        try {
            wm.addView(container, params)
            previewView = container
        } catch (_: Exception) {
            Toast.makeText(this, text.take(120), Toast.LENGTH_LONG).show()
        }
    }

    private fun removePreview() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        previewView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        previewView = null
    }

    private fun removeButton() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        floating?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        floating = null
        floatingParams = null
    }

    private fun updateFacebookLinkFromItems(items: List<UiText>) {
        val joined = items.joinToString("\n") { it.text }
        val isInfoTab = isKiotVietInfoTab(joined)
        val screenCustomer = extractHeaderCustomerName(items)
        val prefs = getSharedPreferences("customer_link", Context.MODE_PRIVATE)
        val oldCustomer = prefs.getString("facebook_customer", "") ?: ""

        // Nếu đang mở tab Thông tin của khách mới thì xóa link cũ trước.
        // Như vậy sang tab Công nợ sẽ không còn hiện COPY + FB theo link khách trước.
        if (isInfoTab && screenCustomer.isNotBlank() &&
            (oldCustomer.isBlank() || !sameCustomerName(screenCustomer, oldCustomer))
        ) {
            clearFacebookLink()
        }

        val candidate = items.asSequence()
            .map { it.text.trim() }
            .firstOrNull { it.contains("facebook.com", ignoreCase = true) || it.contains("fb.com", ignoreCase = true) }

        if (candidate.isNullOrBlank()) {
            // Đã vào tab Thông tin nhưng không đọc được Facebook mới -> bắt buộc xóa link cũ.
            if (isInfoTab) clearFacebookLink()
            return
        }

        val normalized = normalizeFacebookLink(candidate)
        if (normalized.isBlank() || !isValidFacebookLink(normalized)) {
            if (isInfoTab) clearFacebookLink()
            return
        }

        lastFacebookLink = normalized
        lastFacebookCustomerName = screenCustomer
        prefs.edit()
            .putString("facebook_link", normalized)
            .putString("facebook_customer", screenCustomer)
            .apply()
    }

    private fun isKiotVietInfoTab(joinedText: String): Boolean {
        val text = joinedText.lowercase()
        val hasInfoFields = text.contains("mã kh") ||
                text.contains("ngày sinh") ||
                text.contains("địa chỉ") ||
                text.contains("khu vực") ||
                text.contains("phường xã") ||
                text.contains("chi nhánh") ||
                text.contains("mã số thuế") ||
                text.contains("cmnd/cccd") ||
                text.contains("ghi chú")
        val hasInfoHeader = text.contains("thông tin")
        val isReceipt = isKiotVietReceiptScreen(joinedText)
        val isDebtContent = text.contains("nợ cần thu") || text.contains("bán hàng") || text.contains("nợ còn") || text.contains("chi tiết phiếu thu")
        // Chỉ cần thấy các trường hồ sơ khách là coi là tab Thông tin.
        // Không phụ thuộc tuyệt đối vào chữ tab "Thông tin" vì Accessibility đôi khi không trả tab đang chọn.
        return hasInfoFields && !isReceipt && !isDebtContent && !text.contains("xem trước công nợ") ||
                (hasInfoHeader && hasInfoFields && !isReceipt && !isDebtContent)
    }

    private fun extractHeaderCustomerName(items: List<UiText>): String {
        val joined = items.joinToString("\n") { it.text }
        val junk = listOf(
            "di chuyển lên", "navigate up", "quay lại", "back", "chỉnh sửa", "sửa",
            "thông tin", "lịch sử", "công nợ", "tích điểm", "xem trước công nợ",
            "lập phiếu thu", "thu từ khách", "tiền mặt", "chi tiết phiếu thu", "cần thu",
            "nợ", "nợ sau", "thanh toán hóa đơn", "cộng vào tk khách", "ghi chú",
            "tạo lại", "ocr", "copy", "hủy", "đóng", "quý khách", "shop xin gửi"
        )

        fun goodName(t0: String): Boolean {
            val t = t0.trim()
            if (t.length !in 2..70) return false
            if (t.any { c -> c.isDigit() }) return false
            if (t.contains(":") || t.contains("/")) return false
            if (t.contains("http", true)) return false
            if (junk.any { t.contains(it, true) }) return false
            val words = t.split(Regex("\\s+")).filter { it.isNotBlank() }
            return words.size >= 2
        }

        // Màn Lập phiếu thu: tên khách nằm trong card bên dưới tiêu đề, khoảng y 170..330.
        // Tuyệt đối không lấy tiêu đề "Lập phiếu thu" làm tên khách.
        if (isKiotVietReceiptScreen(joined) || joined.contains("Lập phiếu thu", true)) {
            val receiptCardName = items
                .filter { it.rect.centerY() in 160..335 && it.rect.left in 45..560 && it.rect.right <= 700 }
                .map { it.text.trim() }
                .flatMap { it.split('\n', '\r') }
                .map { it.trim() }
                .filter { goodName(it) }
                .maxByOrNull { it.length }
            if (!receiptCardName.isNullOrBlank()) return receiptCardName
        }

        // Tab Công nợ: tên khách nằm trên thanh tiêu đề.
        val headerName = items
            .filter { it.rect.centerY() in 55..170 && it.rect.left >= 70 && it.rect.right <= 700 }
            .map { it.text.trim() }
            .flatMap { it.split('\n', '\r') }
            .map { it.trim() }
            .filter { goodName(it) }
            .maxByOrNull { it.length }
        if (!headerName.isNullOrBlank()) return headerName

        // Fallback an toàn: nửa trên màn hình, nhưng vẫn loại toàn bộ tiêu đề/nút/template.
        return items
            .filter { it.rect.centerY() in 135..360 && it.rect.left in 35..620 }
            .map { it.text.trim() }
            .flatMap { it.split('\n', '\r') }
            .map { it.trim() }
            .filter { goodName(it) }
            .maxByOrNull { it.length } ?: ""
    }

    private fun clearFacebookLink() {
        lastFacebookLink = ""
        lastFacebookCustomerName = ""
        getSharedPreferences("customer_link", Context.MODE_PRIVATE)
            .edit()
            .remove("facebook_link")
            .remove("facebook_customer")
            .apply()
    }

    private fun sameCustomerName(a: String, b: String): Boolean {
        fun norm(s: String): String = s.lowercase()
            .replace(Regex("\\s+"), " ")
            .replace("**", "")
            .trim()
        return norm(a) == norm(b)
    }

    private fun isValidFacebookLink(link: String): Boolean {
        if (link.isBlank()) return false
        if (!link.contains("facebook.com", true) && !link.contains("fb.com", true)) return false
        if (link.contains("...", true) || link.contains("…", true)) return false
        if (link.endsWith("/share/", true) || link.endsWith("/share", true)) return false
        if (link.length < 25) return false
        return true
    }

    private fun normalizeFacebookLink(raw: String): String {
        var text = raw.trim()
            .replace("…", "")
            .replace("...", "")
            .replace(" ", "")
            .replace("\n", "")

        val match = Regex("(https?://)?(www\\.)?(facebook\\.com|fb\\.com)/[^\\s]+", RegexOption.IGNORE_CASE)
            .find(text)
            ?.value
            ?: return ""

        text = match
        if (!text.startsWith("http://", true) && !text.startsWith("https://", true)) {
            text = "https://$text"
        }
        return text
    }

    private fun openCustomerFacebook() {
        val prefs = getSharedPreferences("customer_link", Context.MODE_PRIVATE)
        val storedCustomer = lastFacebookCustomerName.ifBlank { prefs.getString("facebook_customer", "") ?: "" }
        val link = lastFacebookLink.ifBlank { prefs.getString("facebook_link", "") ?: "" }

        if (link.isBlank()) {
            Toast.makeText(this, "Chưa đọc được link Facebook. Hãy mở tab Thông tin của khách trước.", Toast.LENGTH_LONG).show()
            return
        }

        if (currentPreviewCustomerName.isNotBlank() && storedCustomer.isNotBlank() &&
            !sameCustomerName(currentPreviewCustomerName, storedCustomer)
        ) {
            Toast.makeText(this, "Link Facebook đang là của khách khác. Hãy mở tab Thông tin của khách này để đọc lại.", Toast.LENGTH_LONG).show()
            return
        }

        if (!isValidFacebookLink(link)) {
            Toast.makeText(this, "Link Facebook đang bị rút gọn/chưa hợp lệ. Hãy mở tab Thông tin để app đọc lại link.", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Không mở được Facebook", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("cong_no", text))
    }
}
