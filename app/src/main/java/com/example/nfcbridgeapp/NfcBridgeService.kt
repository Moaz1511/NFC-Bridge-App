package com.example.nfcbridgeapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter

import com.skjolber.nfc.external.Acr122uDevice
import com.skjolber.nfc.external.ExternalNfcDevice
import com.skjolber.nfc.external.ExternalNfcDeviceListener
import com.skjolber.nfc.external.ExternalNfcManager
import com.skjolber.nfc.external.ExternalNfcProvider
import com.skjolber.nfc.external.ExternalNfcTag
import com.skjolber.nfc.external.ExternalNfcTagListener

class NfcBridgeService : Service(), ExternalNfcTagListener {

    companion object {
        private const val TAG = "NfcBridgeService"
        private const val NOTIFICATION_CHANNEL_ID = "NFC_SERVICE_CHANNEL"
        private const val NOTIFICATION_ID = 1
        var isServiceRunning = false
    }

    private lateinit var usbManager: UsbManager
    private var nfcProvider: ExternalNfcProvider? = null
    private var externalNfcManager: ExternalNfcManager? = null

    // Device listener to handle device attach/detach
    private val externalNfcDeviceListener = object : ExternalNfcDeviceListener {
        override fun onAttached(device: ExternalNfcDevice) {
            Log.d(TAG, "NFC Device attached: ${device.name}")
            try {
                nfcProvider = device.createProvider(this@NfcBridgeService)
                nfcProvider?.setExternalNfcTagListener(this@NfcBridgeService)
                nfcProvider?.start()
                Toast.makeText(this@NfcBridgeService, "NFC Provider Started", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start provider", e)
                stopSelf()
            }
        }

        override fun onDetached(device: ExternalNfcDevice) {
            Log.d(TAG, "NFC Device detached: ${device.name}")
            nfcProvider?.stop()
            nfcProvider = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Start the ExternalNfcManager
        externalNfcManager = ExternalNfcManager(this, externalNfcDeviceListener)
        externalNfcManager?.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        createNotificationChannel()
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("External NFC Active")
            .setContentText("ACR122U Bridge Service is running.")
            .setSmallIcon(R.drawable.ic_stat_nfc)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    override fun onTagDetected(tag: ExternalNfcTag) {
        Log.d(TAG, "NFC Tag detected: ${tag.id?.toHexString() ?: "(null ID)"}")
        
        // Attempt to broadcast to any app listening for NFC intents
        broadcastNfcIntents(tag)
    }

    private fun broadcastNfcIntents(externalTag: ExternalNfcTag) {
        val ndefMessage = externalTag.ndefMessage
        val tagId = externalTag.id

        // 1. NDEF_DISCOVERED (Highest priority)
        if (ndefMessage != null) {
            val ndefIntent = Intent(NfcAdapter.ACTION_NDEF_DISCOVERED).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(NfcAdapter.EXTRA_NDEF_MESSAGES, arrayOf(ndefMessage))
                putExtra(NfcAdapter.EXTRA_ID, tagId)
                // We cannot easily create a real android.nfc.Tag object without internal APIs, 
                // but many apps just look for EXTRA_NDEF_MESSAGES
            }
            try {
                // Try starting an activity first (to "wake up" apps)
                startActivity(ndefIntent)
                Log.d(TAG, "Started Activity for NDEF_DISCOVERED")
            } catch (e: Exception) {
                // If no activity found, just broadcast it
                sendBroadcast(ndefIntent)
                Log.d(TAG, "Broadcasted NDEF_DISCOVERED (No Activity found)")
            }
        }

        // 2. TECH_DISCOVERED (Fallback)
        val techIntent = Intent(NfcAdapter.ACTION_TECH_DISCOVERED).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(NfcAdapter.EXTRA_ID, tagId)
            // Some apps look for this to handle raw tags
        }
        sendBroadcast(techIntent)

        // 3. TAG_DISCOVERED (General)
        val tagIntent = Intent(NfcAdapter.ACTION_TAG_DISCOVERED).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(NfcAdapter.EXTRA_ID, tagId)
        }
        sendBroadcast(tagIntent)

        Toast.makeText(this, "NFC Tag Forwarded to System!", Toast.LENGTH_SHORT).show()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "NFC Bridge Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nfcProvider?.stop()
        externalNfcManager?.stop()
        isServiceRunning = false
        Log.d(TAG, "NFC Bridge Service stopped.")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

// Extension function for logging byte arrays
fun ByteArray.toHexString() = joinToString("") { "%02x".format(it) }