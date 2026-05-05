package com.thutran.congno

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import android.app.Activity

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24,24,24,24) }
        val templateEdit = EditText(this).apply { setText(Prefs.getTemplate(this@MainActivity)); minLines = 9; textSize = 16f }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.logo_tt)
            adjustViewBounds = true
            maxHeight = 180
            layoutParams = LinearLayout.LayoutParams(180, 180)
        })
        root.addView(TextView(this).apply { text = "Thu Trần Công Nợ Pro - FIX STABLE"; textSize = 22f })
        root.addView(TextView(this).apply { text = "Fix: đợi màn ổn định, đọc theo vị trí dòng, không lấy nhầm năm/mã hóa đơn."; textSize = 16f })
        root.addView(Button(this).apply { text = "MỞ QUYỀN TRỢ NĂNG"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        root.addView(Button(this).apply { text = "MỞ QUYỀN NÚT NỔI"; setOnClickListener { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } })
        root.addView(TextView(this).apply {
            text = "Độ trễ khi vào tab Công nợ:"
            textSize = 16f
        })

        val delayValuesMs = (1..20).map { it * 100 }
        val delayLabels = delayValuesMs.map { String.format(java.util.Locale.US, "%.1f giây", it / 1000.0) }
        val delaySpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, delayLabels)
            val currentIndex = delayValuesMs.indexOf(Prefs.getAutoDelayMs(this@MainActivity)).let { if (it >= 0) it else 4 }
            setSelection(currentIndex)
        }
        root.addView(delaySpinner)
        root.addView(Button(this).apply {
            text = "LƯU ĐỘ TRỄ"
            setOnClickListener {
                val delayMs = delayValuesMs.getOrElse(delaySpinner.selectedItemPosition) { 500 }
                Prefs.setAutoDelayMs(this@MainActivity, delayMs)
                Toast.makeText(this@MainActivity, "Đã lưu độ trễ ${delayLabels[delaySpinner.selectedItemPosition]}", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(TextView(this).apply { text = "Mẫu tin nhắn:" })
        root.addView(templateEdit)
        root.addView(Button(this).apply { text = "LƯU MẪU TIN NHẮN"; setOnClickListener { Prefs.setTemplate(this@MainActivity, templateEdit.text.toString()); Toast.makeText(this@MainActivity, "Đã lưu mẫu", Toast.LENGTH_SHORT).show() } })
        root.addView(Button(this).apply { text = "KHÔI PHỤC MẪU MẶC ĐỊNH"; setOnClickListener { templateEdit.setText(Prefs.defaultTemplate); Prefs.setTemplate(this@MainActivity, Prefs.defaultTemplate) } })
        setContentView(root)
    }
}
