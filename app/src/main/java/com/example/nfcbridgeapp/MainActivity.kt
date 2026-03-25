package com.example.nfcbridgeapp // Make sure this matches your package name

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.nfcbridgeapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleService.setOnClickListener {
            if (NfcBridgeService.isServiceRunning) {
                stopNfcService()
            } else {
                startNfcService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    private fun startNfcService() {
        val serviceIntent = Intent(this, NfcBridgeService::class.java)
        startService(serviceIntent)
        updateUI()
    }

    private fun stopNfcService() {
        val serviceIntent = Intent(this, NfcBridgeService::class.java)
        stopService(serviceIntent)
        updateUI()
    }

    private fun updateUI() {
        if (NfcBridgeService.isServiceRunning) {
            binding.tvStatus.text = "Service is Running"
            binding.btnToggleService.text = "Stop NFC Service"
        } else {
            binding.tvStatus.text = "Service is Stopped"
            binding.btnToggleService.text = "Start NFC Service"
        }
    }
}