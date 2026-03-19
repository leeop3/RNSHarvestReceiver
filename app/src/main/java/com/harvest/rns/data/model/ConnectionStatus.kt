package com.harvest.rns.data.model

sealed class ConnectionStatus {
    object Disconnected : ConnectionStatus()
    data class Connecting(val deviceName: String) : ConnectionStatus()
    data class Connected(val deviceName: String, val deviceAddress: String) : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}
