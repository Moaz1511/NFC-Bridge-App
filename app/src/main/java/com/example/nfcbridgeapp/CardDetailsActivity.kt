package com.example.nfcbridgeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.nfcbridgeapp.databinding.ActivityCardDetailsBinding
import java.text.DateFormat
import java.util.Date

class CardDetailsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_UID = "extra_uid"
        const val EXTRA_ATS = "extra_ats"
        const val EXTRA_CARD_TYPE = "extra_card_type"
        const val EXTRA_SEEN_AT = "extra_seen_at"
        const val EXTRA_READER_NAME = "extra_reader_name"
        const val EXTRA_BALANCE = "extra_balance"
        const val EXTRA_JOURNEY = "extra_journey"
    }

    private lateinit var binding: ActivityCardDetailsBinding
    private var investigationVisible = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != NfcBridgeService.ACTION_STATUS_UPDATE) return

            renderDetails(
                serviceRunning = intent.getBooleanExtra(NfcBridgeService.EXTRA_SERVICE_RUNNING, false),
                readerConnected = intent.getBooleanExtra(NfcBridgeService.EXTRA_READER_CONNECTED, false),
                uid = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_UID),
                ats = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_ATS),
                cardType = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_CARD_TYPE),
                seenAt = intent.getLongExtra(NfcBridgeService.EXTRA_LAST_SEEN_AT, 0L),
                readerName = intent.getStringExtra(NfcBridgeService.EXTRA_READER_NAME),
                balance = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_BALANCE),
                journey = intent.getStringExtra(NfcBridgeService.EXTRA_LAST_JOURNEY),
                message = intent.getStringExtra(NfcBridgeService.EXTRA_STATUS_MESSAGE),
                investigationLog = intent.getStringExtra(NfcBridgeService.EXTRA_INVESTIGATION_LOG),
                investigationHistory = intent.getStringArrayListExtra(NfcBridgeService.EXTRA_INVESTIGATION_HISTORY),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        binding.btnToggleInvestigation.setOnClickListener {
            investigationVisible = !investigationVisible
            updateInvestigationVisibility()
        }

        renderDetails(
            serviceRunning = NfcBridgeService.isServiceRunning,
            readerConnected = false,
            uid = intent.getStringExtra(EXTRA_UID),
            ats = intent.getStringExtra(EXTRA_ATS),
            cardType = intent.getStringExtra(EXTRA_CARD_TYPE),
            seenAt = intent.getLongExtra(EXTRA_SEEN_AT, 0L),
            readerName = intent.getStringExtra(EXTRA_READER_NAME),
            balance = intent.getStringExtra(EXTRA_BALANCE),
            journey = intent.getStringExtra(EXTRA_JOURNEY),
            message = null,
            investigationLog = null,
            investigationHistory = null,
        )
        updateInvestigationVisibility()
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

    private fun renderDetails(
        serviceRunning: Boolean,
        readerConnected: Boolean,
        uid: String?,
        ats: String?,
        cardType: String?,
        seenAt: Long,
        readerName: String?,
        balance: String?,
        journey: String?,
        message: String?,
        investigationLog: String?,
        investigationHistory: ArrayList<String>?,
    ) {
        val safeUid = uid?.takeIf { it.isNotBlank() }
        val uidBytes = if (safeUid == null) {
            getString(R.string.card_detail_not_available)
        } else {
            safeUid.chunked(2).joinToString(" ")
        }
        val uidLength = if (safeUid == null) 0 else safeUid.length / 2
        val seenAtText = if (seenAt > 0L) {
            DateFormat.getDateTimeInstance().format(Date(seenAt))
        } else {
            getString(R.string.card_detail_not_available)
        }

        binding.tvServiceStateValue.text = if (serviceRunning) {
            getString(R.string.service_running)
        } else {
            getString(R.string.service_stopped)
        }
        binding.tvReaderStateValue.text = if (readerConnected) {
            getString(R.string.reader_connected)
        } else {
            getString(R.string.reader_disconnected)
        }
        binding.tvLiveMessageValue.text = message ?: getString(R.string.card_detail_not_available)

        binding.tvUidValue.text = safeUid ?: getString(R.string.card_detail_not_available)
        binding.tvUidBytesValue.text = uidBytes
        binding.tvUidLengthValue.text = if (uidLength == 0) {
            getString(R.string.card_detail_not_available)
        } else {
            getString(R.string.card_details_uid_length_value, uidLength)
        }
        val resolvedCardType = cardType ?: getString(R.string.card_type_unknown)
        val isRapidPass = resolvedCardType.contains("Rapid Pass", ignoreCase = true)
        binding.tvCardTypeValue.text = resolvedCardType
        binding.tvBalanceLabel.visibility = if (isRapidPass) View.VISIBLE else View.GONE
        binding.tvBalanceValue.visibility = if (isRapidPass) View.VISIBLE else View.GONE
        binding.tvJourneyLabel.visibility = if (isRapidPass) View.VISIBLE else View.GONE
        binding.tvJourneyValue.visibility = if (isRapidPass) View.VISIBLE else View.GONE
        if (isRapidPass) {
            binding.tvBalanceLabel.text = getString(R.string.card_details_balance_label)
            binding.tvJourneyLabel.text = getString(R.string.card_details_journey_label)
            binding.tvBalanceValue.text = balance ?: getString(R.string.card_detail_not_available)
            binding.tvJourneyValue.text = journey ?: getString(R.string.card_detail_not_available)
        }
        binding.tvAtsValue.text = ats ?: getString(R.string.card_detail_not_available)
        binding.tvScannedAtValue.text = seenAtText
        binding.tvReaderValue.text = readerName ?: getString(R.string.card_detail_not_available)
        binding.tvInvestigationValue.text = investigationLog ?: getString(R.string.investigation_empty)
        binding.tvHistoryValue.text = investigationHistory
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "\n\n")
            ?: getString(R.string.investigation_history_empty)
    }

    private fun updateInvestigationVisibility() {
        binding.cardInvestigation.visibility = if (investigationVisible) {
            android.view.View.VISIBLE
        } else {
            android.view.View.GONE
        }
        binding.btnToggleInvestigation.text = if (investigationVisible) {
            getString(R.string.hide_investigation)
        } else {
            getString(R.string.show_investigation)
        }
    }
}
