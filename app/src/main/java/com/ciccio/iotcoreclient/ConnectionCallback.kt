package com.ciccio.iotcoreclient

abstract class ConnectionCallback {

    companion object {
        /** Could not determine the source of the error.  */
        const val REASON_UNKNOWN = 0
        /** The parameters used to connect to Cloud IoT Core were invalid.  */
        const val REASON_NOT_AUTHORIZED = 1

        /** The device lost connection to Cloud IoT Core.  */
        const val REASON_CONNECTION_LOST = 2

        /** Timeout occurred while connecting to the MQTT bridge.  */
        const val REASON_CONNECTION_TIMEOUT = 3

        /** The client closed the connection.  */
        const val REASON_CLIENT_CLOSED = 4
    }

    /** Invoked when the Cloud IoT Core connection is established.  */
    abstract fun onConnected()

    /**
     * Invoked when the Cloud IoT Core connection is lost.
     *
     * @param reason the reason the connection was lost
     */
    abstract fun onDisconnected(reason: Int)
}