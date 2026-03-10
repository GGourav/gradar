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

/**
 * Main Activity - Entry point for G Radar
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isVpnConnected = false
    private var isOverlayShowing = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(this)) {
            startOverlayService()
        } else {
            Toast.makeText(this, R.string.overlay_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnStartRadar.setOnClickListener {
            if (isVpnConnected) stopRadar() else startRadar()
        }

        binding.btnSettings.setOnClickListener {
            Toast.makeText(this, "Settings will be implemented in Step 4", Toast.LENGTH_SHORT).show()
        }

        binding.tvVersion.text = getString(
            R.string.version_format,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
    }

    private fun checkPermissions() {
        updateOverlayPermissionStatus()
    }

    private fun startRadar() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val vpnIntent = VpnService.prepare(this)
        if (vpnIntent != null) {
            vpnPermissionLauncher.launch(vpnIntent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val intent = Intent(this, RadarVpnService::class.java).apply {
            action = RadarVpnService.ACTION_START
        }
        startService(intent)
        
        isVpnConnected = true
        updateUI()
        startOverlayService()
        Toast.makeText(this, R.string.radar_started, Toast.LENGTH_SHORT).show()
    }

    private fun startOverlayService() {
        val intent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_START
        }
        startService(intent)
        isOverlayShowing = true
    }

    private fun stopRadar() {
        val vpnIntent = Intent(this, RadarVpnService::class.java).apply {
            action = RadarVpnService.ACTION_STOP
        }
        startService(vpnIntent)

        val overlayIntent = Intent(this, RadarOverlayService::class.java).apply {
            action = RadarOverlayService.ACTION_STOP
        }
        startService(overlayIntent)

        isVpnConnected = false
        isOverlayShowing = false
        updateUI()
        Toast.makeText(this, R.string.radar_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun updateOverlayPermissionStatus() {
        val hasOverlayPermission = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (hasOverlayPermission) {
            getString(R.string.overlay_permission_granted)
        } else {
            getString(R.string.overlay_permission_required)
        }
    }

    private fun updateUI() {
        binding.btnStartRadar.text = if (isVpnConnected) {
            getString(R.string.stop_radar)
        } else {
            getString(R.string.start_radar)
        }
        
        binding.tvVpnStatus.text = if (isVpnConnected) {
            getString(R.string.vpn_connected)
        } else {
            getString(R.string.vpn_disconnected)
        }
    }

    override fun onResume() {
        super.onResume()
        updateOverlayPermissionStatus()
    }
}
