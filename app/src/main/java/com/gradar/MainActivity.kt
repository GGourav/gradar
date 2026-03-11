package com.gradar

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gradar.databinding.ActivityMainBinding
import com.gradar.service.RadarOverlayService
import com.gradar.service.RadarVpnService

class MainActivity : AppCompatActivity() {

    private var binding: ActivityMainBinding? = null
    private var isVpnConnected = false

    private val vpnLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateOverlayStatus()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
            setupUI()
        } catch (e: Exception) {
            Toast.makeText(this, "UI Error: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun setupUI() {
        binding?.apply {
            btnStartRadar.setOnClickListener {
                if (isVpnConnected) stopRadar() else startRadar()
            }
            
            btnSettings.setOnClickListener {
                Toast.makeText(this@MainActivity, "Settings coming in Step 4", Toast.LENGTH_SHORT).show()
            }
            
            tvVersion.text = "Version ${BuildConfig.VERSION_NAME}"
        }
        
        updateOverlayStatus()
    }

    private fun startRadar() {
        // Check overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
            return
        }
        
        // Check VPN permission
        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        try {
            // Start VPN service
            val vpnIntent = Intent(this, RadarVpnService::class.java).apply {
                action = RadarVpnService.ACTION_START
            }
            startService(vpnIntent)
            
            // Start overlay service
            val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
                action = RadarOverlayService.ACTION_START
            }
            startService(overlayIntent)
            
            isVpnConnected = true
            updateUI()
            
            Toast.makeText(this, R.string.radar_started, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Start error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopRadar() {
        try {
            val vpnIntent = Intent(this, RadarVpnService::class.java).apply {
                action = RadarVpnService.ACTION_STOP
            }
            startService(vpnIntent)
            
            val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
                action = RadarOverlayService.ACTION_STOP
            }
            startService(overlayIntent)
            
            isVpnConnected = false
            updateUI()
            
            Toast.makeText(this, R.string.radar_stopped, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Stop error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun updateUI() {
        binding?.apply {
            btnStartRadar.text = if (isVpnConnected) {
                getString(R.string.stop_radar)
            } else {
                getString(R.string.start_radar)
            }
            
            tvVpnStatus.text = if (isVpnConnected) {
                getString(R.string.vpn_connected)
            } else {
                getString(R.string.vpn_disconnected)
            }
        }
    }

    private fun updateOverlayStatus() {
        binding?.apply {
            tvOverlayStatus.text = if (Settings.canDrawOverlays(this@MainActivity)) {
                getString(R.string.overlay_permission_granted)
            } else {
                getString(R.string.overlay_permission_required)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayStatus()
    }
}
