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
import com.bd.rapidpass.app.utils.NativeLib
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.Locale
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
        const val EXTRA_LAST_ATS = "extra_last_ats"
        const val EXTRA_LAST_CARD_TYPE = "extra_last_card_type"
        const val EXTRA_LAST_SEEN_AT = "extra_last_seen_at"
        const val EXTRA_READER_NAME = "extra_reader_name"
        const val EXTRA_INVESTIGATION_LOG = "extra_investigation_log"
        const val EXTRA_INVESTIGATION_HISTORY = "extra_investigation_history"
        const val EXTRA_STATUS_MESSAGE = "extra_status_message"
        const val EXTRA_LAST_BALANCE = "extra_last_balance"
        const val EXTRA_LAST_JOURNEY = "extra_last_journey"

        private const val VID = 0x072F
        private const val PID = 0x2200
        private const val CCID_HEADER_SIZE = 10
        private const val REMOVAL_CONFIRMATION_READS = 6
        private const val CARD_PRESENCE_GRACE_MS = 5000L
        private const val MAX_HISTORY_ITEMS = 8

        var isServiceRunning = false
    }

    private data class CardSnapshot(
        val uid: String,
        val detectionKeys: Set<String>,
        val ats: String?,
        val cardType: String,
        val seenAt: Long,
        val investigationLog: String,
        val rapidPassBalance: String?,
        val rapidPassJourney: String?,
    )

    private data class ApduResult(
        val commandHex: String,
        val responseHex: String,
        val statusWord: String,
        val payload: ByteArray?,
        val apduResponseHex: String,
    )

    private data class RawApduResult(
        val commandHex: String,
        val responseHex: String,
        val apduResponse: ByteArray,
        val statusWord: String,
        val sw1: Int,
        val sw2: Int,
    )

    private data class DirectTransmitResult(
        val requestHex: String,
        val initialStatusWord: String,
        val initialResponseHex: String,
        val finalStatusWord: String?,
        val finalResponseHex: String?,
        val payload: ByteArray?,
    )

    private data class FelicaPollResult(
        val idm: ByteArray,
        val idmHex: String,
        val systemCode: ByteArray?,
        val systemCodeHex: String?,
        val response: DirectTransmitResult,
    )

    private data class RapidPassCandidate(
        val label: String,
        val pollingSystemCode: ByteArray?,
        val serviceCode: ByteArray,
    )

    private data class RapidPassHistory(
        val logId: Int?,
        val serviceName: String?,
        val fromStation: String?,
        val toStation: String?,
        val fare: Int?,
        val balance: Int?,
        val date: String?,
        val time: String?,
    )

    private data class RapidPassResult(
        val idmHex: String,
        val systemCodeHex: String?,
        val latestBalance: String?,
        val history: List<RapidPassHistory>,
        val journeySummary: String?,
        val investigationLines: List<String>,
    )

    private data class Iso14443aProbeResult(
        val uidHex: String,
        val atsHex: String?,
        val investigationLines: List<String>,
    )

    private data class FelicaProbeResult(
        val idmHex: String,
        val systemCodeHex: String?,
        val investigationLines: List<String>,
    )

    private data class FelicaReadResult(
        val rawFrame: ByteArray,
        val blockData: ByteArray,
        val directTransmit: DirectTransmitResult,
    )

    private lateinit var usbManager: UsbManager
    private var usbDevice: UsbDevice? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val rapidPassNativeLib = NativeLib()
    private var isPolling = false
    private var readerConnected = false
    private var lastUid: String? = null
    private var lastAts: String? = null
    private var lastCardType: String? = null
    private var lastSeenAt: Long = 0L
    private var lastInvestigationLog: String? = null
    private var lastRapidPassBalance: String? = null
    private var lastRapidPassJourney: String? = null
    private val investigationHistory = ArrayDeque<String>()
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
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
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
            clearCardState()
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

            var previousSnapshot: CardSnapshot? = null
            var missedReads = 0
            var lastSuccessfulReadAt = 0L
            while (isPolling) {
                try {
                    var snapshot = readCardSnapshot()
                    if (snapshot == null && previousSnapshot != null && shouldLatchSnapshot(previousSnapshot)) {
                        snapshot = recoverGenericSnapshot(previousSnapshot)
                    }

                    if (snapshot != null) {
                        missedReads = 0
                        lastSuccessfulReadAt = System.currentTimeMillis()
                        val sameCard = previousSnapshot?.let { isSameCard(it, snapshot) } == true
                        previousSnapshot = snapshot
                        if (sameCard) {
                            onTagUpdated(snapshot)
                        } else {
                            onTagDetected(snapshot)
                        }
                    } else if (previousSnapshot != null) {
                        val absenceMs = System.currentTimeMillis() - lastSuccessfulReadAt
                        if (absenceMs < CARD_PRESENCE_GRACE_MS) {
                            Thread.sleep(500)
                            continue
                        }
                        missedReads += 1
                        if (missedReads < REMOVAL_CONFIRMATION_READS) {
                            Thread.sleep(500)
                            continue
                        }
                        previousSnapshot = null
                        missedReads = 0
                        Log.d(TAG, "Card read missed repeatedly; keeping last snapshot until a new card is detected")
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

    private fun powerOnIcc(updateStatus: Boolean = true): ByteArray? {
        val command = byteArrayOf(0x62, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        usbConnection?.bulkTransfer(endpointOut, command, command.size, 1000)
        val response = ByteArray(64)
        val len = usbConnection?.bulkTransfer(endpointIn, response, response.size, 1000) ?: 0
        val payload = if (len > CCID_HEADER_SIZE) response.copyOfRange(CCID_HEADER_SIZE, len) else null
        Log.d(TAG, "ICC power on len=$len payload=${payload?.toHexString(compact = false)}")
        if (len > 0) {
            if (updateStatus) {
                statusMessage = getString(R.string.status_waiting_for_card)
                broadcastStatus()
            }
        } else {
            if (updateStatus) {
                statusMessage = getString(R.string.status_power_on_failed)
                broadcastStatus()
            }
        }
        return payload
    }

    private fun transmitRawApdu(apdu: ByteArray, timeoutMs: Int = 1000): RawApduResult? {
        val out = endpointOut ?: return null
        val input = endpointIn ?: return null
        val connection = usbConnection ?: return null

        val command = ByteArray(CCID_HEADER_SIZE + apdu.size)
        command[0] = 0x6F.toByte()
        command[1] = (apdu.size and 0xFF).toByte()
        command[2] = ((apdu.size shr 8) and 0xFF).toByte()
        command[3] = ((apdu.size shr 16) and 0xFF).toByte()
        command[4] = ((apdu.size shr 24) and 0xFF).toByte()
        apdu.copyInto(command, destinationOffset = CCID_HEADER_SIZE)

        val written = connection.bulkTransfer(out, command, command.size, timeoutMs)
        if (written <= 0) {
            return null
        }

        val responseBuffer = ByteArray(512)
        val len = connection.bulkTransfer(input, responseBuffer, responseBuffer.size, timeoutMs)
        if (len <= CCID_HEADER_SIZE) {
            return null
        }

        val response = responseBuffer.copyOf(len)
        val apduResponse = response.copyOfRange(CCID_HEADER_SIZE, response.size)
        if (apduResponse.size < 2) {
            return null
        }

        val sw1 = apduResponse[apduResponse.size - 2].toInt() and 0xFF
        val sw2 = apduResponse[apduResponse.size - 1].toInt() and 0xFF
        return RawApduResult(
            commandHex = apdu.toHexString(compact = false),
            responseHex = response.toHexString(compact = false),
            apduResponse = apduResponse,
            statusWord = "%02X%02X".format(sw1, sw2),
            sw1 = sw1,
            sw2 = sw2,
        )
    }

    private fun transmitApdu(apdu: ByteArray, timeoutMs: Int = 1000): ApduResult? {
        val raw = transmitRawApdu(apdu, timeoutMs) ?: return null
        val finalRaw = if (raw.sw1 == 0x61) {
            val getResponseApdu = byteArrayOf(
                0xFF.toByte(),
                0xC0.toByte(),
                0x00,
                0x00,
                raw.sw2.toByte(),
            )
            transmitRawApdu(getResponseApdu, timeoutMs) ?: raw
        } else {
            raw
        }

        val payload = if (finalRaw.apduResponse.size > 2) {
            finalRaw.apduResponse.copyOf(finalRaw.apduResponse.size - 2)
        } else {
            null
        }
        return ApduResult(
            commandHex = apdu.toHexString(compact = false),
            responseHex = finalRaw.responseHex,
            statusWord = finalRaw.statusWord,
            payload = payload,
            apduResponseHex = finalRaw.apduResponse.toHexString(compact = false),
        )
    }

    private fun legacyTransmitApdu(apdu: ByteArray, timeoutMs: Int = 1000): ByteArray? {
        val out = endpointOut ?: return null
        val input = endpointIn ?: return null
        val connection = usbConnection ?: return null

        val command = ByteArray(CCID_HEADER_SIZE + apdu.size)
        command[0] = 0x6F.toByte()
        command[1] = (apdu.size and 0xFF).toByte()
        command[2] = ((apdu.size shr 8) and 0xFF).toByte()
        command[3] = ((apdu.size shr 16) and 0xFF).toByte()
        command[4] = ((apdu.size shr 24) and 0xFF).toByte()
        apdu.copyInto(command, destinationOffset = CCID_HEADER_SIZE)

        val written = connection.bulkTransfer(out, command, command.size, timeoutMs)
        if (written <= 0) {
            return null
        }

        val response = ByteArray(300)
        val len = connection.bulkTransfer(input, response, response.size, timeoutMs)
        if (len <= CCID_HEADER_SIZE) {
            return null
        }
        return response.copyOf(len)
    }

    private fun directTransmit(commandData: ByteArray, timeoutMs: Int = 1000): DirectTransmitResult? {
        val apdu = byteArrayOf(
            0xFF.toByte(),
            0x00,
            0x00,
            0x00,
            (commandData.size and 0xFF).toByte(),
        ) + commandData
        val initial = transmitRawApdu(apdu, timeoutMs) ?: return null

        val initialPayload = if (initial.apduResponse.size > 2) {
            initial.apduResponse.copyOf(initial.apduResponse.size - 2)
        } else {
            null
        }

        if (initial.sw1 != 0x61) {
            return DirectTransmitResult(
                requestHex = apdu.toHexString(compact = false),
                initialStatusWord = initial.statusWord,
                initialResponseHex = initial.apduResponse.toHexString(compact = false),
                finalStatusWord = initial.statusWord,
                finalResponseHex = initial.apduResponse.toHexString(compact = false),
                payload = initialPayload,
            )
        }

        val getResponseApdu = byteArrayOf(
            0xFF.toByte(),
            0xC0.toByte(),
            0x00,
            0x00,
            initial.sw2.toByte(),
        )
        val followUp = transmitRawApdu(getResponseApdu, timeoutMs) ?: return null
        val followUpPayload = if (followUp.apduResponse.size > 2) {
            followUp.apduResponse.copyOf(followUp.apduResponse.size - 2)
        } else {
            null
        }

        return DirectTransmitResult(
            requestHex = apdu.toHexString(compact = false),
            initialStatusWord = initial.statusWord,
            initialResponseHex = initial.apduResponse.toHexString(compact = false),
            finalStatusWord = followUp.statusWord,
            finalResponseHex = followUp.apduResponse.toHexString(compact = false),
            payload = followUpPayload,
        )
    }

    private fun readCardSnapshot(): CardSnapshot? {
        return readCardSnapshotInternal(allowResetRetry = true)
    }

    private fun readCardSnapshotInternal(allowResetRetry: Boolean): CardSnapshot? {
        val now = System.currentTimeMillis()

        val legacyUid = getCardUidLegacy()
        if (!legacyUid.isNullOrBlank()) {
            val legacyAts = getCardAtsLegacy()
            return CardSnapshot(
                uid = legacyUid,
                detectionKeys = setOf(legacyUid),
                ats = legacyAts,
                cardType = if (legacyAts.isNullOrBlank()) {
                    getString(R.string.card_type_unknown)
                } else {
                    getString(R.string.card_type_iso14443a)
                },
                seenAt = now,
                investigationLog = buildString {
                    appendLine("Legacy UID path used")
                    appendLine("Card UID: $legacyUid")
                    appendLine("Card ATS: ${legacyAts ?: getString(R.string.card_detail_not_available)}")
                }.trim(),
                rapidPassBalance = null,
                rapidPassJourney = null,
            )
        }

        val uidResult = transmitApdu(byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00), 500)
        val uid = extractUidHex(uidResult)

        val atsResult = transmitApdu(byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x01, 0x00, 0x00), 500)
        val ats = extractAtsHex(atsResult, uid)
        val isoProbe = if (uid == null) probeIso14443aCard() else null

        Log.d(
            TAG,
            "Snapshot probe uidSw=${uidResult?.statusWord} uidPayload=${uidResult?.payload?.toHexString(compact = false)} atsSw=${atsResult?.statusWord} atsPayload=${atsResult?.payload?.toHexString(compact = false)} uid=$uid ats=$ats isoUid=${isoProbe?.uidHex} isoAts=${isoProbe?.atsHex}"
        )

        val felicaProbe = if (uid == null && isoProbe == null) {
            probeFelicaCard()
        } else {
            null
        }
        val rapidPassResult = felicaProbe?.let { readRapidPassCard(it) }
        val fallbackUid = extractFallbackUidHex(uidResult?.payload)
        val resolvedUid = rapidPassResult?.idmHex
            ?: felicaProbe?.idmHex
            ?: isoProbe?.uidHex
            ?: uid
            ?: fallbackUid
            ?: if (allowResetRetry) {
                Log.d(TAG, "No card selected, retrying after ICC power on reset")
                powerOnIcc(updateStatus = false)
                return readCardSnapshotInternal(allowResetRetry = false)
            } else {
                return null
            }
        val resolvedAts = ats ?: isoProbe?.atsHex
        val detectionKeys = buildSet {
            uid?.takeIf { it.isNotBlank() }?.let(::add)
            fallbackUid?.takeIf { it.isNotBlank() }?.let(::add)
            isoProbe?.uidHex?.takeIf { it.isNotBlank() }?.let(::add)
            felicaProbe?.idmHex?.takeIf { it.isNotBlank() }?.let(::add)
            rapidPassResult?.idmHex?.takeIf { it.isNotBlank() }?.let(::add)
        }
        val rapidPassDetected = rapidPassResult != null
        val cardType = when {
            rapidPassDetected -> getString(R.string.card_type_rapid_pass)
            felicaProbe != null -> getString(R.string.card_type_felica)
            !resolvedAts.isNullOrBlank() || isoProbe != null -> getString(R.string.card_type_iso14443a)
            else -> getString(R.string.card_type_unknown)
        }

        val investigationLog = buildString {
            appendLine(
                getString(
                    R.string.investigation_reader_name,
                    usbDevice?.let { getReaderName(it) } ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(getString(R.string.investigation_card_uid, resolvedUid))
            appendLine(getString(R.string.investigation_card_uid_bytes, resolvedUid.chunked(2).joinToString(" ")))
            appendLine(getString(R.string.investigation_card_uid_length, resolvedUid.length / 2))
            appendLine(getString(R.string.investigation_card_type, cardType))
            appendLine(
                getString(
                    R.string.investigation_card_ats,
                    resolvedAts ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_card_seen_at,
                    DateFormat.getDateTimeInstance().format(Date(now))
                )
            )
            appendLine(
                getString(
                    R.string.investigation_uid_apdu,
                    uidResult?.commandHex ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_uid_sw,
                    uidResult?.statusWord ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_uid_response,
                    uidResult?.responseHex ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_ats_apdu,
                    atsResult?.commandHex ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_ats_sw,
                    atsResult?.statusWord ?: getString(R.string.card_detail_not_available)
                )
            )
            appendLine(
                getString(
                    R.string.investigation_ats_response,
                    atsResult?.responseHex ?: getString(R.string.card_detail_not_available)
                )
            )

            rapidPassResult?.investigationLines?.forEach { appendLine(it) }
                ?: felicaProbe?.investigationLines?.forEach { appendLine(it) }
                ?: isoProbe?.investigationLines?.forEach { appendLine(it) }
            append(getString(R.string.investigation_atr_note))
        }

        return CardSnapshot(
            uid = resolvedUid,
            detectionKeys = detectionKeys.ifEmpty { setOf(resolvedUid) },
            ats = resolvedAts,
            cardType = cardType,
            seenAt = now,
            investigationLog = investigationLog,
            rapidPassBalance = rapidPassResult?.latestBalance,
            rapidPassJourney = rapidPassResult?.journeySummary,
        )
    }

    private fun getCardUidLegacy(): String? {
        val apdu = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x00, 0x00, 0x00)
        val response = legacyTransmitApdu(apdu, timeoutMs = 500) ?: return null
        val payload = extractLegacyPayload(response) ?: return null
        if (payload.contentEquals(byteArrayOf(0xD5.toByte(), 0x4B, 0x00))) {
            return null
        }
        return payload.toHexString(compact = true)
    }

    private fun getCardAtsLegacy(): String? {
        val apdu = byteArrayOf(0xFF.toByte(), 0xCA.toByte(), 0x01, 0x00, 0x00)
        val response = legacyTransmitApdu(apdu, timeoutMs = 500) ?: return null
        val payload = extractLegacyPayload(response) ?: return null
        if (payload.contentEquals(byteArrayOf(0xD5.toByte(), 0x4B, 0x00))) {
            return null
        }
        return payload.toHexString(compact = false)
    }

    private fun extractLegacyPayload(response: ByteArray): ByteArray? {
        if (response.size <= CCID_HEADER_SIZE + 2) {
            return null
        }
        val sw1 = response[response.size - 2]
        val sw2 = response[response.size - 1]
        if (sw1 != 0x90.toByte() || sw2 != 0x00.toByte()) {
            return null
        }
        return response.copyOfRange(CCID_HEADER_SIZE, response.size - 2)
    }

    private fun probeIso14443aCard(): Iso14443aProbeResult? {
        val command = byteArrayOf(
            0xD4.toByte(),
            0x4A,
            0x01,
            0x00,
        )
        val response = directTransmit(command, 1000) ?: return null
        val payload = response.payload ?: return null
        if (payload.size < 7 || payload[0] != 0xD5.toByte() || payload[1] != 0x4B.toByte()) {
            return null
        }
        if ((payload[2].toInt() and 0xFF) == 0) {
            return null
        }

        val uidLengthIndex = 6
        if (payload.size <= uidLengthIndex) {
            return null
        }
        val uidLength = payload[uidLengthIndex].toInt() and 0xFF
        val uidStart = uidLengthIndex + 1
        val uidEnd = uidStart + uidLength
        if (uidLength == 0 || payload.size < uidEnd) {
            return null
        }
        val uidBytes = payload.copyOfRange(uidStart, uidEnd)
        val ats = if (payload.size > uidEnd) {
            val atsLength = payload[uidEnd].toInt() and 0xFF
            val atsStart = uidEnd + 1
            val atsEnd = atsStart + atsLength
            if (atsLength > 0 && payload.size >= atsEnd) {
                payload.copyOfRange(atsStart, atsEnd).toHexString(compact = false)
            } else {
                null
            }
        } else {
            null
        }

        return Iso14443aProbeResult(
            uidHex = uidBytes.toHexString(compact = true),
            atsHex = ats,
            investigationLines = listOf(
                "ISO 14443-A poll APDU: ${response.requestHex}",
                "ISO 14443-A poll response: ${response.finalResponseHex ?: response.initialResponseHex}",
                "ISO 14443-A UID: ${uidBytes.toHexString(compact = false)}",
                "ISO 14443-A ATS: ${ats ?: getString(R.string.card_detail_not_available)}",
            ),
        )
    }

    private fun probeFelicaCard(): FelicaProbeResult? {
        val basePoll = pollFelica(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) ?: return null
        Log.d(TAG, "FeliCa probe idm=${basePoll.idmHex} system=${basePoll.systemCodeHex}")
        return FelicaProbeResult(
            idmHex = basePoll.idmHex,
            systemCodeHex = basePoll.systemCodeHex,
            investigationLines = listOf(
                getString(R.string.investigation_rapid_pass_poll_apdu, basePoll.response.requestHex),
                getString(
                    R.string.investigation_rapid_pass_poll_response,
                    basePoll.response.finalResponseHex ?: basePoll.response.initialResponseHex
                ),
                getString(R.string.investigation_rapid_pass_idm, basePoll.idmHex),
                getString(
                    R.string.investigation_rapid_pass_system_code,
                    basePoll.systemCodeHex ?: getString(R.string.card_detail_not_available)
                ),
            ),
        )
    }

    private fun readRapidPassCard(baseProbe: FelicaProbeResult): RapidPassResult? {
        val basePoll = pollFelica(byteArrayOf(0xFF.toByte(), 0xFF.toByte())) ?: return null
        val candidates = listOf(
            RapidPassCandidate("svc-0F-09", null, byteArrayOf(0x0F, 0x09)),
            RapidPassCandidate("svc-09-0F", null, byteArrayOf(0x09, 0x0F)),
            RapidPassCandidate("svc-8F-89", null, byteArrayOf(0x8F.toByte(), 0x89.toByte())),
            RapidPassCandidate("svc-89-8F", null, byteArrayOf(0x89.toByte(), 0x8F.toByte())),
            RapidPassCandidate("svc-0F-22", null, byteArrayOf(0x0F, 0x22)),
            RapidPassCandidate("svc-22-0F", null, byteArrayOf(0x22, 0x0F)),
            RapidPassCandidate("svc-00-40", byteArrayOf(0x40, 0x00), byteArrayOf(0x00, 0x40)),
            RapidPassCandidate("svc-40-00", byteArrayOf(0x40, 0x00), byteArrayOf(0x40, 0x00)),
            RapidPassCandidate("svc-0B-00", byteArrayOf(0x92.toByte(), 0xE4.toByte()), byteArrayOf(0x0B, 0x00)),
            RapidPassCandidate("svc-00-0B", byteArrayOf(0x92.toByte(), 0xE4.toByte()), byteArrayOf(0x00, 0x0B)),
            RapidPassCandidate("svc-17-68", byteArrayOf(0xFE.toByte(), 0x00), byteArrayOf(0x17, 0x68)),
            RapidPassCandidate("svc-68-17", byteArrayOf(0xFE.toByte(), 0x00), byteArrayOf(0x68, 0x17)),
            RapidPassCandidate("svc-0F-17", byteArrayOf(0xFE.toByte(), 0x00), byteArrayOf(0x0F, 0x17)),
            RapidPassCandidate("svc-17-0F", byteArrayOf(0xFE.toByte(), 0x00), byteArrayOf(0x17, 0x0F)),
        )

        val baseLines = baseProbe.investigationLines.toMutableList()

        for (candidate in candidates) {
            val poll = if (candidate.pollingSystemCode != null) {
                pollFelica(candidate.pollingSystemCode) ?: continue
            } else {
                basePoll
            }

            val reads = mutableListOf<FelicaReadResult>()
            for (blockNumber in 0..11) {
                val blockRead = readFelicaBlock(poll.idm, candidate.serviceCode, blockNumber) ?: break
                reads += blockRead
            }
            if (reads.isEmpty()) {
                continue
            }

            val parsed = parseRapidPassCard(reads) ?: continue

            if (parsed.latestBalance.isNullOrBlank() && parsed.history.isEmpty()) {
                continue
            }

            val lines = baseLines.toMutableList()
            lines += getString(R.string.investigation_rapid_pass_candidate, candidate.label)
            lines += getString(
                R.string.investigation_rapid_pass_service_code,
                candidate.serviceCode.toHexString(compact = false)
            )
            reads.take(4).forEachIndexed { index, read ->
                lines += "Rapid Pass block $index raw: ${read.rawFrame.toHexString(compact = false)}"
                lines += "Rapid Pass block $index data: ${read.blockData.toHexString(compact = false)}"
            }
            lines += getString(
                R.string.investigation_rapid_pass_balance,
                parsed.latestBalance ?: getString(R.string.card_detail_not_available)
            )
            lines += getString(R.string.investigation_rapid_pass_history_count, parsed.history.size)

            return parsed.copy(
                idmHex = poll.idmHex,
                systemCodeHex = poll.systemCodeHex,
                investigationLines = lines
            )
        }

        return null
    }

    private fun pollFelica(systemCode: ByteArray): FelicaPollResult? {
        val command = byteArrayOf(
            0xD4.toByte(),
            0x4A,
            0x01,
            0x01,
            0x00,
            systemCode[0],
            systemCode[1],
            0x01,
            0x00,
        )
        val response = directTransmit(command, 1000) ?: return null
        val payload = response.payload ?: return null
        if (payload.size < 21 || payload[0] != 0xD5.toByte() || payload[1] != 0x4B.toByte()) {
            return null
        }
        if ((payload[2].toInt() and 0xFF) == 0) {
            return null
        }

        val idm = payload.copyOfRange(5, 13)
        val systemCodeBytes = if (payload.size >= 23) payload.copyOfRange(payload.size - 2, payload.size) else null
        return FelicaPollResult(
            idm = idm,
            idmHex = idm.toHexString(compact = true),
            systemCode = systemCodeBytes,
            systemCodeHex = systemCodeBytes?.toHexString(compact = false),
            response = response,
        )
    }

    private fun readFelicaBlock(idm: ByteArray, serviceCode: ByteArray, blockNumber: Int): FelicaReadResult? {
        val nativeFrame = ByteArray(16)
        nativeFrame[0] = 0x10
        nativeFrame[1] = 0x06
        idm.copyInto(nativeFrame, destinationOffset = 2)
        nativeFrame[10] = 0x01
        nativeFrame[11] = serviceCode[0]
        nativeFrame[12] = serviceCode[1]
        nativeFrame[13] = 0x01
        nativeFrame[14] = 0x80.toByte()
        nativeFrame[15] = blockNumber.toByte()

        val directCommand = byteArrayOf(0xD4.toByte(), 0x40, 0x01) + nativeFrame
        val result = directTransmit(directCommand, 1000) ?: return null
        val payload = result.payload ?: return null
        if (payload.size < 4 || payload[0] != 0xD5.toByte() || payload[1] != 0x41.toByte() || payload[2] != 0x00.toByte()) {
            return null
        }

        val rawFrame = payload.copyOfRange(3, payload.size)
        if (rawFrame.size < 29) {
            return null
        }
        if (rawFrame[1] != 0x07.toByte()) {
            return null
        }
        if (rawFrame[10] != 0x00.toByte() || rawFrame[11] != 0x00.toByte()) {
            return null
        }
        val blockCount = rawFrame[12].toInt() and 0xFF
        if (blockCount <= 0) {
            return null
        }
        val blockDataStart = 13
        val blockDataLength = blockCount * 16
        if (rawFrame.size < blockDataStart + blockDataLength) {
            return null
        }

        return FelicaReadResult(
            rawFrame = rawFrame,
            blockData = rawFrame.copyOfRange(blockDataStart, blockDataStart + blockDataLength),
            directTransmit = result,
        )
    }

    private fun parseRapidPassCard(reads: List<FelicaReadResult>): RapidPassResult? {
        val parseCandidates = buildList {
            add("block-data" to reads.map { it.blockData }.toTypedArray())
            add("raw-frame" to reads.map { it.rawFrame }.toTypedArray())
            add("first-two-blocks" to reads.take(2).map { it.blockData }.toTypedArray())
            add("first-two-frames" to reads.take(2).map { it.rawFrame }.toTypedArray())

            for (start in reads.indices) {
                val window = reads.drop(start).take(2)
                if (window.isNotEmpty()) {
                    add("window-$start-blocks" to window.map { it.blockData }.toTypedArray())
                    add("window-$start-frames" to window.map { it.rawFrame }.toTypedArray())
                }
            }
        }

        for ((parseMode, payloads) in parseCandidates) {
            val parsed = tryParseRapidPassPayloads(payloads) ?: continue
            return parsed.copy(
                investigationLines = parsed.investigationLines + "Rapid Pass parser mode: $parseMode"
            )
        }

        return null
    }

    private fun tryParseRapidPassPayloads(payloads: Array<ByteArray>): RapidPassResult? {
        return try {
            val rawJson = rapidPassNativeLib.parseTransactionHistory(payloads)
            val parsedJson = JSONObject(rawJson)
            val balance = parsedJson.optNullableString("latestBalance")
            val historyArray = parsedJson.optJSONArray("history")
            val historyItems = mutableListOf<RapidPassHistory>()

            if (historyArray != null) {
                for (index in 0 until historyArray.length()) {
                    val item = historyArray.optJSONObject(index) ?: continue
                    val history = RapidPassHistory(
                        logId = item.optNullableInt("logId"),
                        serviceName = item.optNullableString("serviceName"),
                        fromStation = item.optNullableString("fromStation"),
                        toStation = item.optNullableString("toStation"),
                        fare = item.optNullableInt("fare"),
                        balance = item.optNullableInt("balance"),
                        date = item.optNullableString("date"),
                        time = item.optNullableString("time"),
                    )
                    if (history.logId == null || history.logId != 0) {
                        historyItems += history
                    }
                }
            }

            val latestHistory = historyItems.firstOrNull()
            val journeySummary = latestHistory?.let { history ->
                buildString {
                    val route = listOfNotNull(history.fromStation, history.toStation)
                        .takeIf { it.isNotEmpty() }
                        ?.joinToString(" -> ")
                    val serviceName = history.serviceName?.takeIf { it.isNotBlank() }
                    val fare = history.fare?.toString()
                    val remaining = history.balance?.toString()
                    append(serviceName ?: getString(R.string.card_detail_not_available))
                    if (!route.isNullOrBlank()) {
                        append(" | ")
                        append(route)
                    }
                    if (!fare.isNullOrBlank()) {
                        append(" | Fare ")
                        append(fare)
                    }
                    if (!remaining.isNullOrBlank()) {
                        append(" | Balance ")
                        append(remaining)
                    }
                }
            }

            RapidPassResult(
                idmHex = "",
                systemCodeHex = null,
                latestBalance = balance ?: latestHistory?.balance?.toString(),
                history = historyItems,
                journeySummary = journeySummary,
                investigationLines = emptyList(),
            )
        } catch (error: Throwable) {
            Log.d(TAG, "Rapid Pass parser rejected payload", error)
            null
        }
    }

    private fun extractUidHex(result: ApduResult?): String? {
        val payload = result?.payload ?: return null
        val uidBytes = extractUidBytes(payload)
            ?: extractBestTailIdentifier(payload)
            ?: return null
        return uidBytes.toHexString(compact = true)
    }

    private fun extractFallbackUidHex(payload: ByteArray?): String? {
        val bytes = payload ?: return null
        val compact = bytes.toHexString(compact = true)
        if (compact.isBlank() || compact == "D54B00") {
            return null
        }
        if (bytes.size in 3..16) {
            return compact
        }
        extractBestTailIdentifier(bytes)?.let { return it.toHexString(compact = true) }
        return null
    }

    private fun extractAtsHex(result: ApduResult?, uidHex: String?): String? {
        val payload = result?.payload ?: return null
        if (payload.size < 2) return null

        val atsHex = payload.toHexString(compact = false)
        val compactAts = payload.toHexString(compact = true)
        if (compactAts == "D54B00") return null
        if (!uidHex.isNullOrBlank() && compactAts == uidHex) return null
        return atsHex
    }

    private fun extractUidBytes(payload: ByteArray): ByteArray? {
        if (payload.isEmpty()) return null
        if (payload.contentEquals(byteArrayOf(0xD5.toByte(), 0x4B, 0x00))) return null

        val directMatchLengths = (3..16).toSet()
        if (payload.size in directMatchLengths) {
            return payload
        }

        val markerIndex = payload.indexOfFirstMarker(byteArrayOf(0x81.toByte(), 0x00))
        if (markerIndex >= 0) {
            val candidate = payload.copyOfRange(markerIndex + 2, payload.size)
            if (candidate.size in directMatchLengths) {
                return candidate
            }
        }

        for (length in listOf(8, 7, 4, 10, 6, 5, 9, 12, 11, 3)) {
            if (payload.size > length) {
                val tail = payload.copyOfRange(payload.size - length, payload.size)
                if (tail.any { it.toInt() != 0 }) {
                    return tail
                }
            }
        }

        return null
    }

    private fun extractBestTailIdentifier(payload: ByteArray): ByteArray? {
        if (payload.contentEquals(byteArrayOf(0xD5.toByte(), 0x4B, 0x00))) {
            return null
        }
        val candidateLengths = listOf(8, 7, 10, 4, 12, 11, 9, 6, 5, 3)
        for (length in candidateLengths) {
            if (payload.size >= length) {
                val candidate = payload.copyOfRange(payload.size - length, payload.size)
                if (candidate.contentEquals(byteArrayOf(0xD5.toByte(), 0x4B, 0x00))) {
                    continue
                }
                if (candidate.any { it.toInt() != 0 }) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun ByteArray.indexOfFirstMarker(marker: ByteArray): Int {
        if (marker.isEmpty() || size < marker.size) return -1
        for (start in 0..(size - marker.size)) {
            var matches = true
            for (offset in marker.indices) {
                if (this[start + offset] != marker[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                return start
            }
        }
        return -1
    }

    private fun clearCardState() {
        lastUid = null
        lastAts = null
        lastCardType = null
        lastSeenAt = 0L
        lastInvestigationLog = null
        lastRapidPassBalance = null
        lastRapidPassJourney = null
    }

    private fun isSameCard(previous: CardSnapshot, current: CardSnapshot): Boolean {
        if (previous.detectionKeys.intersect(current.detectionKeys).isNotEmpty()) {
            return true
        }
        return previous.uid == current.uid
    }

    private fun shouldLatchSnapshot(snapshot: CardSnapshot): Boolean {
        return !snapshot.cardType.contains("Rapid Pass", ignoreCase = true) &&
            !snapshot.cardType.contains("FeliCa", ignoreCase = true)
    }

    private fun recoverGenericSnapshot(previousSnapshot: CardSnapshot): CardSnapshot? {
        powerOnIcc(updateStatus = false)
        val recoveredSnapshot = readCardSnapshotInternal(allowResetRetry = false) ?: return null
        return if (isSameCard(previousSnapshot, recoveredSnapshot)) {
            recoveredSnapshot
        } else {
            null
        }
    }

    private fun onTagDetected(snapshot: CardSnapshot) {
        applySnapshot(snapshot, preserveExistingMetadata = false)
        appendInvestigationHistory(snapshot)
        statusMessage = getString(R.string.status_tag_detected, snapshot.uid)
        broadcastStatus()
        toast(statusMessage)
        Log.d(TAG, "Tag detected: ${snapshot.uid}")
    }

    private fun onTagUpdated(snapshot: CardSnapshot) {
        applySnapshot(snapshot, preserveExistingMetadata = true)
        statusMessage = getString(R.string.status_tag_present, snapshot.uid)
        broadcastStatus()
    }

    private fun applySnapshot(snapshot: CardSnapshot, preserveExistingMetadata: Boolean) {
        lastUid = snapshot.uid
        lastAts = snapshot.ats ?: if (preserveExistingMetadata) lastAts else null
        lastCardType = if (
            preserveExistingMetadata &&
            snapshot.cardType == getString(R.string.card_type_unknown) &&
            !lastCardType.isNullOrBlank()
        ) {
            lastCardType
        } else {
            snapshot.cardType
        }
        lastSeenAt = snapshot.seenAt
        lastInvestigationLog = snapshot.investigationLog
        lastRapidPassBalance = snapshot.rapidPassBalance ?: if (preserveExistingMetadata) lastRapidPassBalance else null
        lastRapidPassJourney = snapshot.rapidPassJourney ?: if (preserveExistingMetadata) lastRapidPassJourney else null
    }

    private fun appendInvestigationHistory(snapshot: CardSnapshot) {
        val entry = buildString {
            appendLine(getString(R.string.history_entry_detected, snapshot.uid))
            appendLine(getString(R.string.history_entry_time, DateFormat.getDateTimeInstance().format(Date(snapshot.seenAt))))
            append(snapshot.investigationLog.trim())
        }.trim()

        investigationHistory.addFirst(entry)
        while (investigationHistory.size > MAX_HISTORY_ITEMS) {
            investigationHistory.removeLast()
        }
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
            .putExtra(EXTRA_LAST_ATS, lastAts)
            .putExtra(EXTRA_LAST_CARD_TYPE, lastCardType)
            .putExtra(EXTRA_LAST_SEEN_AT, lastSeenAt)
            .putExtra(EXTRA_READER_NAME, usbDevice?.let { getReaderName(it) })
            .putExtra(EXTRA_INVESTIGATION_LOG, lastInvestigationLog)
            .putStringArrayListExtra(EXTRA_INVESTIGATION_HISTORY, ArrayList(investigationHistory))
            .putExtra(EXTRA_STATUS_MESSAGE, statusMessage)
            .putExtra(EXTRA_LAST_BALANCE, lastRapidPassBalance)
            .putExtra(EXTRA_LAST_JOURNEY, lastRapidPassJourney)
        sendBroadcast(intent)
    }

    private fun getReaderName(device: UsbDevice): String {
        val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            device.productName
        } else {
            null
        }
        return productName ?: String.format(
            Locale.US,
            "ACR122U (%04X:%04X)",
            device.vendorId,
            device.productId
        )
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
                        clearCardState()
                        investigationHistory.clear()
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
        clearCardState()
        investigationHistory.clear()
        statusMessage = getString(R.string.status_service_stopped)
        broadcastStatus()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun ByteArray.toHexString(compact: Boolean): String {
        return if (compact) {
            joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) }
        } else {
            joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
        }
    }

    private fun JSONObject.optNullableString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotBlank() }
    }

    private fun JSONObject.optNullableInt(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        return optInt(name)
    }
}
