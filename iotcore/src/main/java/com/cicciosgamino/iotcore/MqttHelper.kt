package com.cicciosgamino.iotcore

class MqttHelper {

    fun getRedeableReasonCode(code: Int): String {
        return when(code) {
            3       ->  "Mqtt Broker Unavailable"
            32101   ->  "Client Already Disconnected"
            32111   ->  "Client Closed"
            else -> "Code NOT found"
        }
    }
}