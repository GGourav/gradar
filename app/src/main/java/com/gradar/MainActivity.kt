package com.gradar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.gradar.service.RadarOverlayService
import com.gradar.service.RadarVpnService

class MainActivity : AppCompatActivity() {

    private var isRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnStart = findViewById<Button>(R.id.btnStart)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvOverlay = findViewById<TextView>(R.id.tvOverlay)

        updateOverlayStatus(tvOverlay)

        btnStart.setOnClickListener {
            if (isRunning) {
                stopRadar()
                btnStart.text = "Start Radar"
                tvStatus.text = "Status: Stopped"
                isRunning = false
            } else {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                    return@setOnClickListener
                }
                
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    startActivityForResult(vpnIntent, 1000)
                } else {
                    startRadar()
                    btnStart.text = "Stop Radar"
                    tvStatus.text = "Status: Running"
                    isRunning = true
                }
            }
        }
    }

    private fun startRadar() {
        startService(Intent(this, RadarVpnService::class.java).setAction("START"))
        startService(Intent(this, RadarOverlayService::class.java).setAction("START"))
        Toast.makeText(this, "Radar Started", Toast.LENGTH_SHORT).show()
    }

    private fun stopRadar() {
        startService(Intent(this, RadarVpnService::class.java).setAction("STOP"))
        startService(Intent(this, RadarOverlayService::class.java).setAction("STOP"))
        Toast.makeText(this, "Radar Stopped", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun updateOverlayStatus(tv: TextView) {
        tv.text = if (Settings.canDrawOverlays(this)) {
            "Overlay: Granted"
        } else {
            "Overlay: Required"
        }
    }

    override fun onResume() {
        super.onResume()
        val tvOverlay = findViewById<TextView>(R.id.tvOverlay)
        updateOverlayStatus(tvOverlay)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1000 && resultCode == Activity.RESULT_OK) {
            startRadar()
            findViewById<Button>(R.id.btnStart).text = "Stop Radar"
            findViewById<TextView>(R.id.tvStatus).text = "Status: Running"
            isRunning = true
        }
    }
}
