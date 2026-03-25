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
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
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

        const val ACTION_START = "com.example.nfcbridgeapp.action.START"
        const val ACTION_REFRESH_READER = "com.example.nfcbridgeapp.action.REFRESH_READER"
        const val ACTION_REQUEST_STATUS = "com.example.nfcbridgeapp.action.REQUEST_STATUS"
        const val ACTION_STATUS_UPDATE = "com.example.nfcbridgeapp.action.STATUS_UPDATE"

        const val EXTRA_SERVICE_RUNNING = "extra_service_running"
        const val EXTRA_READER_CONNECTED = "extra_reader_connected"
        const val EXTRA_LAST_UID = "extra_last_uid"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"

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
    private var readerConnected = false
    private var lastUid: String? = null
    private var statusMessage: String = ""

    override fun onCreate() {
        super.onCreate()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        registerUsbReceiver(IntentFilter(ACTION_USB_PERMISSION))
        registerUsbReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED))
        registerUsbReceiver(IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_REQUEST_STATUS -> {
                broadcastStatus()
                return START_STICKY
            }

            ACTION_REFRESH_READER -> {
                ensureForeground()
                findAndConnectDevice(forceReconnect = true)
                return START_STICKY
            }

            else -> {
                ensureForeground()
                findAndConnectDevice(forceReconnect = false)
                return START_STICKY
            }
        }
    }

    private fun ensureForeground() {
        if (isServiceRunning) return

        isServiceRunning = true
        statusMessage = getString(R.string.status_service_started)
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        broadcastStatus()
    }

    private fun registerUsbReceiver(filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun findAndConnectDevice(forceReconnect: Boolean) {
        if (forceReconnect) {
            disconnectDevice()
        }

        val currentDevice = usbDevice
        if (currentDevice != null && readerConnected && usbManager.hasPermission(currentDevice)) {
            statusMessage = getString(R.string.status_reader_ready)
            broadcastStatus()
            return
        }

        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == VID && it.productId == PID
        }

        if (device == null) {
            readerConnected = false
            lastUid = null
            statusMessage = getString(R.string.status_reader_not_found)
            broadcastStatus()
            toast(statusMessage)
            return
        }

        requestPermission(device)
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            connectToDevice(device)
            return
        }

        statusMessage = getString(R.string.status_waiting_permission)
        broadcastStatus()

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
            flags
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                readerConnected = false
                statusMessage = getString(R.string.status_open_failed)
                broadcastStatus()
                return
            }

            val iface = findUsbInterface(device)
            if (iface == null || !connection.claimInterface(iface, true)) {
                connection.close()
                readerConnected = false
                statusMessage = getString(R.string.status_claim_failed)
                broadcastStatus()
                return
            }

            val bulkOut = findEndpoint(iface, UsbConstants.USB_DIR_OUT)
            val bulkIn = findEndpoint(iface, UsbConstants.USB_DIR_IN)
            if (bulkOut == null || bulkIn == null) {
                connection.releaseInterface(iface)
                connection.close()
                readerConnected = false
                statusMessage = getString(R.string.status_endpoint_missing)
                broadcastStatus()
                return
            }

            usbDevice = device
            usbConnection = connection
            usbInterface = iface
            endpointOut = bulkOut
            endpointIn = bulkIn
            readerConnected = true
            statusMessage = getString(R.string.status_reader_connected)
            broadcastStatus()
            startPolling()
            toast(statusMessage)
            Log.d(TAG, "Reader connected and ready.")
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed", e)
            readerConnected = false
            statusMessage = getString(R.string.status_connect_failed, e.message ?: "unknown")
            broadcastStatus()
        }
    }

    private fun findUsbInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            val bulkIn = findEndpoint(candidate, UsbConstants.USB_DIR_IN)
            val bulkOut = findEndpoint(candidate, UsbConstants.USB_DIR_OUT)
            if (bulkIn != null && bulkOut != null) {
                return candidate
            }
        }
        return null
    }

    private fun findEndpoint(iface: UsbInterface, direction: Int): UsbEndpoint? {
        for (index in 0 until iface.endpointCount) {
            val endpoint = iface.getEndpoint(index)
            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK && endpoint.direction == direction) {
                return endpoint
            }
        }
        return null
    }

    private fun startPolling() {
        if (isPolling) return

        isPolling = true
        executor.execute {
            powerOnIcc()

            var previousUid: String? = null
            while (isPolling) {
                try {
                    val uid = getCardUid()
                    if (uid != null && uid != previousUid) {
                        previousUid = uid
                        onTagDetected(uid)
                    } else if (uid == null && previousUid != null) {
                        previousUid = null
                        lastUid = null
                        statusMessage = getString(R.string.status_waiting_for_card)
                        broadcastStatus()
                    }

                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    isPolling = false
                } catch (e: Exception) {
                    Log.e(TAG, "Polling failed", e)
                    statusMessage = getString(R.string.status_polling_error, e.message ?: "unknown")
                    broadcastStatus()
                    isPolling = false
                }
            }
        }
    }

    private fun powerOnIcc() {
        val command = byteArrayOf(0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        usbConnection?.bulkTransfer(endpointOut, command, command.size, 1000)
        val response = ByteArray(64)
        usbConnection?.bulkTransfer(endpointIn, response, response.size, 1000)
        statusMessage = getString(R.string.status_waiting_for_card)
        broadcastStatus()
    }

    private fun getCardUid(): String? {
        val apdu = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
        val ccidHeader = byteArrayOf(
            0x6F.toByte(), 0x05, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
        )
        val command = ccidHeader + apdu
        usbConnection?.bulkTransfer(endpointOut, command, command.size, 500)

        val response = ByteArray(64)
        val len = usbConnection?.bulkTransfer(endpointIn, response, response.size, 500) ?: 0
        if (len > 12 && response[10].toInt() != 0) {
            val uidBytes = response.copyOfRange(10, len - 2)
            return uidBytes.joinToString("") { "%02X".format(it) }
        }
        return null
    }

    private fun onTagDetected(uid: String) {
        lastUid = uid
        statusMessage = getString(R.string.status_tag_detected, uid)
        broadcastStatus()
        toast(statusMessage)
        Log.d(TAG, "Tag detected: $uid")
    }

    private fun disconnectDevice() {
        isPolling = false
        usbInterface?.let { iface ->
            usbConnection?.releaseInterface(iface)
        }
        usbConnection?.close()
        usbConnection = null
        usbInterface = null
        usbDevice = null
        endpointIn = null
        endpointOut = null
        readerConnected = false
    }

    private fun broadcastStatus() {
        val intent = Intent(ACTION_STATUS_UPDATE)
            .setPackage(packageName)
            .putExtra(EXTRA_SERVICE_RUNNING, isServiceRunning)
            .putExtra(EXTRA_READER_CONNECTED, readerConnected)
            .putExtra(EXTRA_LAST_UID, lastUid)
            .putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
        sendBroadcast(intent)
    }

    private fun toast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun getUsbDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as? UsbDevice
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getUsbDeviceExtra(intent)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                        connectToDevice(device)
                    } else {
                        statusMessage = getString(R.string.status_permission_denied)
                        broadcastStatus()
                    }
                }

                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getUsbDeviceExtra(intent)
                    if (device?.vendorId == VID && device.productId == PID) {
                        statusMessage = getString(R.string.status_reader_attached)
                        broadcastStatus()
                        findAndConnectDevice(forceReconnect = false)
                    }
                }

                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getUsbDeviceExtra(intent)
                    if (device?.vendorId == VID && device.productId == PID) {
                        disconnectDevice()
                        lastUid = null
                        statusMessage = getString(R.string.status_reader_detached)
                        broadcastStatus()
                        toast(statusMessage)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        disconnectDevice()
        unregisterReceiver(usbReceiver)
        executor.shutdownNow()
        isServiceRunning = false
        lastUid = null
        statusMessage = getString(R.string.status_service_stopped)
        broadcastStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
