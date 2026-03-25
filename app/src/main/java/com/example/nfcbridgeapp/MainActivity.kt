package com.example.nfcbridgeapp

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nfcbridgeapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var lastUid: String? = null
    private var lastAts: String? = null
    private var lastCardType: String? = null
    private var lastSeenAt: Long = 0L
    private var lastReaderName: String? = null
    private var lastBalance: String? = null
    private var lastJourney: String? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NfcBridgeService.ACTION_STATUS_UPDATE) return

            renderState(
                serviceRunning = intent.getBooleanExtra(NfcBridgeService.EXTRA_SERVICE_RUNNING, false),
                readerConnected = intent.getBooleanExtra(NfcBridgeService.EXTRA_READER_CONNECTED, false),
                uid = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_UID),
                ats = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_ATS),
                cardType = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_CARD_TYPE),
                seenAt = intent.getLongExtra(NfcBridgeService.EXTRA_LAST_SEEN_AT, 0L),
                readerName = intent.getStringExtra(NfcBridgeService.EXTRA_READER_NAME),
                balance = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_BALANCE),
                journey = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_JOURNEY),
                message = intent.getStringExtra(NfcBridgeService.EXTRA_STATUS_MESSAGE)
                    ?: getString(R.string.status_idle)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggleService.setOnClickListener {
            if (NfcBridgeService.isServiceRunning) {
                stopService(Intent(this, NfcBridgeService::class.java))
            } else {
                sendServiceCommand(NfcBridgeService.ACTION_START)
            }
        }

        binding.btnRefreshReader.setOnClickListener {
            sendServiceCommand(NfcBridgeService.ACTION_REFRESH_READER)
        }

        binding.btnCopyUid.setOnClickListener {
            val uid = lastUid
            if (uid.isNullOrBlank()) {
                Toast.makeText(this, R.string.copy_uid_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.uid_label), uid))
            Toast.makeText(this, R.string.copy_uid_success, Toast.LENGTH_SHORT).show()
        }

        binding.btnCardDetails.setOnClickListener {
            openCardDetailsScreen()
        }

        renderState(
            serviceRunning = NfcBridgeService.isServiceRunning,
            readerConnected = false,
            uid = null,
            ats = null,
            cardType = null,
            seenAt = 0L,
            readerName = null,
            balance = null,
            journey = null,
            message = getString(R.string.status_idle)
        )
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
        if (NfcBridgeService.isServiceRunning) {
            sendServiceCommand(NfcBridgeService.ACTION_REQUEST_STATUS)
        }
    }

    override fun onStop() {
        unregisterReceiver(statusReceiver)
        super.onStop()
    }

    private fun registerStatusReceiver() {
        val filter = IntentFilter(NfcBridgeService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
    }

    private fun sendServiceCommand(action: String) {
        val intent = Intent(this, NfcBridgeService::class.java).setAction(action)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun renderState(
        serviceRunning: Boolean,
        readerConnected: Boolean,
        uid: String?,
        ats: String?,
        cardType: String?,
        seenAt: Long,
        readerName: String?,
        balance: String?,
        journey: String?,
        message: String
    ) {
        lastUid = uid
        lastAts = ats
        lastCardType = cardType
        lastSeenAt = seenAt
        lastReaderName = readerName
        lastBalance = balance
        lastJourney = journey

        binding.tvServiceValue.text = if (serviceRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }

        binding.tvReaderValue.text = if (readerConnected) {
            getString(R.string.reader_connected)
        } else {
            getString(R.string.reader_disconnected)
        }

        binding.tvLastUidValue.text = uid ?: getString(R.string.uid_placeholder)
        binding.tvMessageValue.text = message
        binding.btnToggleService.text = if (serviceRunning) {
            getString(R.string.stop_service)
        } else {
            getString(R.string.start_service)
        }
        binding.btnRefreshReader.isEnabled = serviceRunning
        binding.btnCopyUid.isEnabled = !uid.isNullOrBlank()
        binding.btnCardDetails.isEnabled = !uid.isNullOrBlank()
    }

    private fun openCardDetailsScreen() {
        val uid = lastUid
        if (uid.isNullOrBlank()) {
            Toast.makeText(this, R.string.card_details_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        startActivity(
            Intent(this, CardDetailsActivity::class.java)
                .putExtra(CardDetailsActivity.EXTRA_UID, uid)
                .putExtra(CardDetailsActivity.EXTRA_ATS, lastAts)
                .putExtra(CardDetailsActivity.EXTRA_CARD_TYPE, lastCardType)
                .putExtra(CardDetailsActivity.EXTRA_SEEN_AT, lastSeenAt)
                .putExtra(CardDetailsActivity.EXTRA_READER_NAME, lastReaderName)
                .putExtra(CardDetailsActivity.EXTRA_BALANCE, lastBalance)
                .putExtra(CardDetailsActivity.EXTRA_JOURNEY, lastJourney)
        )
    }
}
