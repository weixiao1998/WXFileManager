package top.weixiaoweb.wxfilemanager

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import top.weixiaoweb.wxfilemanager.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        
        // Handle TabLayout selection
        binding.tabLayout.addOnTabSelectedListener(object : com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> if (navController.currentDestination?.id != R.id.navigation_local) {
                        navController.navigate(R.id.navigation_local)
                    }
                    1 -> if (navController.currentDestination?.id != R.id.navigation_smb) {
                        navController.navigate(R.id.navigation_smb)
                    }
                }
            }
            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })

        // Sync TabLayout with NavController
        navController.addOnDestinationChangedListener { _, destination, _ ->
            when (destination.id) {
                R.id.navigation_local -> binding.tabLayout.getTabAt(0)?.select()
                R.id.navigation_smb -> binding.tabLayout.getTabAt(1)?.select()
            }
        }

        // Handle Search and Sort clicks - these will be handled by the current fragment
        binding.btnSearch.setOnClickListener {
            val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main)
                ?.childFragmentManager?.fragments?.firstOrNull()
            if (fragment is top.weixiaoweb.wxfilemanager.ui.local.LocalFragment) {
                fragment.showSearchDialog()
            } else if (fragment is top.weixiaoweb.wxfilemanager.ui.smb.SmbFragment) {
                fragment.showSearchDialog()
            }
        }

        binding.btnSort.setOnClickListener {
            val fragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_activity_main)
                ?.childFragmentManager?.fragments?.firstOrNull()
            if (fragment is top.weixiaoweb.wxfilemanager.ui.local.LocalFragment) {
                fragment.showViewSettingsDialog()
            } else if (fragment is top.weixiaoweb.wxfilemanager.ui.smb.SmbFragment) {
                fragment.showViewSettingsDialog()
            }
        }

        // Hide default ActionBar to use custom top bar
        supportActionBar?.hide()
        
        handleIntent(intent)
        
        requestPermissions()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val path = intent.getStringExtra("navigate_path")
        val isSmb = intent.getBooleanExtra("is_smb", false)
        
        if (path != null) {
            val navController = findNavController(R.id.nav_host_fragment_activity_main)
            if (isSmb) {
                // Navigate to SMB and then we need a way to tell SmbFragment to load path
                // For simplicity, let's use a shared event or argument
                val bundle = Bundle().apply { putString("start_path", path) }
                navController.navigate(R.id.navigation_smb, bundle)
            } else {
                val bundle = Bundle().apply { putString("start_path", path) }
                navController.navigate(R.id.navigation_local, bundle)
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            // Request WRITE_EXTERNAL_STORAGE for older versions if needed
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        return navController.navigateUp() || super.onSupportNavigateUp()
    }
}
