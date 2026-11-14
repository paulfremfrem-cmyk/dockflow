package com.kinko.dockflow.ui

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kinko.dockflow.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("instamod_prefs", Context.MODE_PRIVATE)

        initToggles()
    }

    private fun initToggles() {
        // Instagram Toggles
        binding.switchFloatingNav.isChecked = prefs.getBoolean("enable_floating_nav", true)

        binding.switchFloatingNav.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_floating_nav", isChecked).apply()
        }
        // Threads Toggles
        binding.switchThreadsFloatingNav.isChecked = prefs.getBoolean("enable_threads_floating_nav", true)

        binding.switchThreadsFloatingNav.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("enable_threads_floating_nav", isChecked).apply()
        }
    }
}
