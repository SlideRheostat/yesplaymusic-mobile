package com.litemusic.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.litemusic.Api
import com.litemusic.R

/** 扫码登录：生成网易云登录二维码并轮询扫码状态 */
class LoginActivity : AppCompatActivity() {

    private lateinit var qrImage: ImageView
    private lateinit var qrStatus: TextView

    private var qrKey: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling || qrKey.isEmpty()) return
            Api.qrCheck(qrKey) { r ->
                if (isFinishing || !polling) return@qrCheck
                when (r.getOrNull()) {
                    801 -> qrStatus.text = getString(R.string.qr_waiting)
                    802 -> qrStatus.text = getString(R.string.qr_scanned)
                    803 -> {
                        Toast.makeText(this@LoginActivity, R.string.qr_success, Toast.LENGTH_SHORT).show()
                        afterLogin()
                    }
                    800 -> {
                        qrStatus.text = getString(R.string.qr_expired)
                        polling = false
                        qrImage.setOnClickListener { startQr() }
                    }
                    else -> qrStatus.text = getString(R.string.qr_waiting)
                }
                if (polling) handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        qrImage = findViewById(R.id.qr_image)
        qrStatus = findViewById(R.id.qr_status)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        qrImage.setOnClickListener { startQr() }
        startQr()
    }

    override fun onStart() {
        super.onStart()
        // 从后台返回时恢复二维码轮询（onStop 会暂停轮询，否则回来后状态永远停滞）
        if (qrKey.isNotEmpty()) {
            polling = true
            handler.removeCallbacks(pollRunnable)
            handler.post(pollRunnable)
        }
    }

    override fun onStop() {
        polling = false
        handler.removeCallbacks(pollRunnable)
        super.onStop()
    }

    private fun startQr() {
        qrStatus.text = getString(R.string.loading)
        Api.qrKey { r ->
            if (isFinishing || isDestroyed) return@qrKey
            r.onSuccess { key ->
                if (key.isEmpty()) {
                    qrStatus.text = getString(R.string.error_network)
                    return@onSuccess
                }
                qrKey = key
                val content = "https://music.163.com/login?codekey=$key"
                qrImage.setImageBitmap(makeQr(content, 512))
                qrStatus.text = getString(R.string.qr_tip)
                polling = true
                handler.removeCallbacks(pollRunnable)
                handler.postDelayed(pollRunnable, 2000)
            }.onFailure {
                qrStatus.text = it.message
            }
        }
    }

    private fun makeQr(content: String, size: Int): Bitmap {
        val hints = hashMapOf<EncodeHintType, Any>(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints)
        // 一次性写入整块像素，避免 512×512 次 setPixel 在低配设备上卡顿
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[i++] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }

    private fun afterLogin() {
        LikedStore.refresh()
        setResult(RESULT_OK)
        finish()
    }
}
