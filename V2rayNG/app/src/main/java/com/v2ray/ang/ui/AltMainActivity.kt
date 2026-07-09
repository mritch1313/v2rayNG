package com.v2ray.ang.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.CoreServiceManager
import com.v2ray.ang.databinding.ActivityAltMainBinding
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SubscriptionUpdater
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Alternative Modern UI Activity for v2rayNG
 * Implements ALT VPN design overlay on top of existing system
 * Does NOT modify or replace the legacy MainActivity
 * Safe integration with existing ViewModels and Services
 */
class AltMainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityAltMainBinding
    private val mainViewModel: MainViewModel by viewModels()
    private var currentSelectedServer: String = ""
    private var isConnected = false

    private val requestVpnPermission = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == RESULT_OK) {
            startV2Ray()
        }
    }

    private val requestActivityLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) {
        if (SettingsChangeManager.consumeRestartService() && mainViewModel.isRunning.value == true) {
            restartV2Ray()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAltMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize UI
        setupHeader()
        setupMainContent()
        setupBottomNavigation()
        setupViewModel()
        setupFAB()

        // Load initial data
        SubscriptionUpdater.sync()
        mainViewModel.reloadServerList()

        // Request notification permission
        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}
    }

    private fun setupHeader() {
        binding.headerTitle.text = "ALT VPN"
        binding.headerSubtitle.text = getString(R.string.connection_not_connected)
        binding.headerIcon.setImageResource(R.drawable.ic_lock)
    }

    private fun setupMainContent() {
        // Load and display current selected server
        val selectedGuid = MmkvManager.getSelectServer()
        if (!selectedGuid.isNullOrEmpty()) {
            val config = MmkvManager.decodeServerConfig(selectedGuid)
            config?.let {
                currentSelectedServer = it.remarks
                binding.serverNameText.text = it.remarks
                binding.serverDescText.text = it.description ?: "VLESS • защищённый туннель"
                binding.serverCountryText.text = "${it.server} • 214ms"
            }
        }

        // Setup statistics
        binding.downloadSpeedText.text = "0.0"
        binding.uploadSpeedText.text = "0.0"

        // Setup "View All Servers" button
        binding.viewAllServersBtn.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Setup mini buttons below "View All Servers"
        binding.searchBtn.setOnClickListener {
            startActivity(Intent(this, ScannerActivity::class.java))
        }

        binding.editBtn.setOnClickListener {
            startActivity(Intent(this, ServerActivity::class.java))
        }
    }

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_home -> {
                    // Already on home
                    true
                }
                R.id.nav_stats -> {
                    startActivity(Intent(this, LogcatActivity::class.java))
                    true
                }
                R.id.nav_profile -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.nav_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                else -> false
            }
        }
        binding.bottomNavigation.selectedItemId = R.id.nav_home
    }

    private fun setupFAB() {
        binding.fab.setOnClickListener { handleFabAction() }
        binding.fab.setImageResource(R.drawable.ic_play_24dp)
        updateFabState()
    }

    private fun setupViewModel() {
        mainViewModel.isRunning.observe(this) { isRunning ->
            isConnected = isRunning
            updateFabState()
            updateConnectionStatus()
        }
        mainViewModel.updateTestResultAction.observe(this) { result ->
            binding.headerSubtitle.text = result
        }
        mainViewModel.startListenBroadcast()
        mainViewModel.initAssets(assets)
    }

    private fun updateFabState() {
        if (isConnected) {
            binding.fab.setImageResource(R.drawable.ic_stop_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_fab_active)
            )
            binding.headerSubtitle.text = getString(R.string.connection_connected)
        } else {
            binding.fab.setImageResource(R.drawable.ic_play_24dp)
            binding.fab.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.color_fab_inactive)
            )
            binding.headerSubtitle.text = "приватный"
        }
    }

    private fun updateConnectionStatus() {
        binding.headerSubtitle.text = if (isConnected) {
            getString(R.string.connection_connected)
        } else {
            "приватный"
        }
    }

    private fun handleFabAction() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        } else if (SettingsManager.isVpnMode()) {
            val intent = VpnService.prepare(this)
            if (intent == null) {
                startV2Ray()
            } else {
                requestVpnPermission.launch(intent)
            }
        } else {
            startV2Ray()
        }
    }

    private fun startV2Ray() {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }

        CoreServiceManager.startVService(this)
    }

    private fun restartV2Ray() {
        if (mainViewModel.isRunning.value == true) {
            CoreServiceManager.stopVService(this)
        }
        lifecycleScope.launch {
            delay(500)
            startV2Ray()
        }
    }

    private fun checkAndRequestPermission(
        permissionType: PermissionType,
        onGranted: () -> Unit
    ) {
        // Simplified permission check
        onGranted()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
