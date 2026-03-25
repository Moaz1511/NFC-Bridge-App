package com.example.nfcbridgeapp

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.usb.*
import android.nfc.*
import android.os.*
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors

class NfcBridgeService : Service() {

    companion object {
        private const val TAG = "NfcBridgeService"
        private const val NOTIFICATION_CHANNEL_ID = "NFC_BRIDGE_CHANNEL"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_USB_PERMISSION = "com.example.nfcbridgeapp.USB_PERMISSION"
        
        // ACR122U IDs
        private const val VID = 0x072F
        private const val PID = 0x2200

        var isServiceRunning = false
    }

    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val executor = Executors.newSingleThreadExecutor()
    private var isPolling = false

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver(IntentFilter(ACTION_USB_PERMISSION))
        registerUsbReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        isServiceRunning = true
        startInForeground()
        findAndConnectDevice()
        return START_STICKY
    }

    private fun registerUsbReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun findAndConnectDevice() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            if (device.vendorId == VID && device.productId == PID) {
                requestPermission(device)
                return
            }
        }
        Toast.makeText(this, "ACR122U not found. Please connect via OTG.", Toast.LENGTH_LONG).show()
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
        } else {
            val permissionIntent = PendingIntent.getBroadcast(
                this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(device, permissionIntent)
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device) ?: return
            val iface = device.getInterface(0)
            
            if (connection.claimInterface(iface, true)) {
                usbDevice = device
                usbConnection = connection
                usbInterface = iface
                
                // Endpoints: 0 is Out, 1 is In
                endpointOut = iface.getEndpoint(0)
                endpointIn = iface.getEndpoint(1)

                startPolling()
                Toast.makeText(this, "ACR122U Connected!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Device connected and interface claimed.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
        }
    }

    private fun startPolling() {
        if (isPolling) return
        isPolling = true
        executor.execute {
            // 1. Power on ICC (CCID Command 0x62)
            powerOnIcc()
            
            var lastId: String? = null
            
            while (isPolling) {
                val tagId = getCardUid()
                if (tagId != null && tagId != lastId) {
                    lastId = tagId
                    onTagDetected(tagId)
                } else if (tagId == null) {
                    lastId = null // Tag removed
                }
                Thread.sleep(500) // Poll every 500ms
            }
        }
    }

    private fun powerOnIcc() {
        val command = byteArrayOf(0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        usbConnection?.bulkTransfer(endpointOut, command, command.size, 1000)
        val response = ByteArray(64)
        usbConnection?.bulkTransfer(endpointIn, response, response.size, 1000)
    }

    private fun getCardUid(): String? {
        // CCID Transfer Block + Get UID APDU (FF CA 00 00 00)
        val apdu = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
        val ccidHeader = byteArrayOf(
            0x6F.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val command = ccidHeader + apdu
        
        usbConnection?.bulkTransfer(endpointOut, command, command.size, 500)
        
        val response = ByteArray(64)
        val len = usbConnection?.bulkTransfer(endpointIn, response, response.size, 500) ?: 0
        
        // CCID response is 10 bytes header + Data + SW1/SW2
        if (len > 12 && response[10].toInt() != 0) {
            val uidBytes = response.copyOfRange(10, len - 2)
            return uidBytes.joinToString("") { "%02x".format(it) }
        }
        return null
    }

    private fun onTagDetected(uid: String) {
        Log.d(TAG, "Tag Detected: $uid")
        
        // Create standard Android NFC Intents
        val intent = Intent(NfcAdapter.ACTION_TAG_DISCOVERED).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(NfcAdapter.EXTRA_ID, uid.decodeHex())
        }
        
        // Also try NDEF discovered if we had a message (omitted for brevity)
        sendBroadcast(intent)
        
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, "NFC Tag Detected: $uid", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, "NFC Bridge", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("NFC Bridge Active")
            .setContentText("Listening for ACR122U tags...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { connectToDevice(it) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    if (device?.vendorId == VID && device?.productId == PID) {
                        disconnectDevice()
                    }
                }
            }
        }
    }

    private fun disconnectDevice() {
        isPolling = false
        usbConnection?.close()
        usbConnection = null
        usbDevice = null
        Toast.makeText(this, "ACR122U Disconnected", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectDevice()
        unregisterReceiver(usbReceiver)
        executor.shutdownNow()
        isServiceRunning = false
    }

    override fun onBind(intent: Intent?) = null

    private fun String.decodeHex(): ByteArray {
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
