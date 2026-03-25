package com.bd.rapidpass.app.utils

class NativeLib {
    companion object {
        init {
            System.loadLibrary("native-lib")
        }
    }

    external fun getConfig(index: Int, subIndex: Int): String

    external fun parseTransactionHistory(rawResponses: Array<ByteArray>): String
}
