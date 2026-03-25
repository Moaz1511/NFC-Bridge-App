package com.example.nfcbridgeapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
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
    }

    private lateinit var binding: ActivityCardDetailsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCardDetailsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            finish()
        }

        val uid = intent.getStringExtra(EXTRA_UID).orEmpty()
        val ats = intent.getStringExtra(EXTRA_ATS)
        val cardType = intent.getStringExtra(EXTRA_CARD_TYPE)
        val seenAt = intent.getLongExtra(EXTRA_SEEN_AT, 0L)
        val readerName = intent.getStringExtra(EXTRA_READER_NAME)

        val uidBytes = if (uid.isBlank()) {
            getString(R.string.card_detail_not_available)
        } else {
            uid.chunked(2).joinToString(" ")
        }
        val uidLength = if (uid.isBlank()) 0 else uid.length / 2
        val seenAtText = if (seenAt > 0L) {
            DateFormat.getDateTimeInstance().format(Date(seenAt))
        } else {
            getString(R.string.card_detail_not_available)
        }

        binding.tvUidValue.text = if (uid.isBlank()) {
            getString(R.string.card_detail_not_available)
        } else {
            uid
        }
        binding.tvUidBytesValue.text = uidBytes
        binding.tvUidLengthValue.text = if (uidLength == 0) {
            getString(R.string.card_detail_not_available)
        } else {
            getString(R.string.card_details_uid_length_value, uidLength)
        }
        binding.tvCardTypeValue.text = cardType ?: getString(R.string.card_type_unknown)
        binding.tvAtsValue.text = ats ?: getString(R.string.card_detail_not_available)
        binding.tvScannedAtValue.text = seenAtText
        binding.tvReaderValue.text = readerName ?: getString(R.string.card_detail_not_available)
    }
}
