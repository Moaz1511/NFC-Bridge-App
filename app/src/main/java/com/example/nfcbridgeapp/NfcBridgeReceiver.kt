package com.example.nfcbridgeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast


class NfcBridgeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.github.skjolber.nfc.external.TAG_CONNECT" -> {
                Toast.makeText(context, "NFC Tag Connected", Toast.LENGTH_SHORT).show()
            }
            "com.github.skjolber.nfc.external.TAG_DISCONNECT" -> {
                Toast.makeText(context, "NFC Tag Disconnected", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
