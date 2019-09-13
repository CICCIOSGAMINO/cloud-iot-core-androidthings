package com.cicciosgamino.iotcore

import android.app.Activity
import android.os.Bundle
import android.util.Log

private val TAG = MainActivity::class.java.simpleName

class MainActivity : Activity() {

    lateinit var connectionParams: ConnectionParams
    lateinit var iotClient: IotCoreClient

    /**
     * Google Cloud IoT Connection Callback, handle the onConnect and onDisconnect
     * to Google Cloud IoT MQTT Pub/Sub message service
     */
    private val connectionCallback = object: ConnectionCallback {
        override fun onConnected() {
            Log.d(TAG, "@CLOUD_IOT >> CONNECTED to Google IoT Core ")
        }

        override fun onDisconnected(reason: Int) {
            Log.d(TAG, "@CLOUD_IOT >> DISCONNECTED from Google IoT Core : $reason")
        }
    }

    /**
     * Google Cloud IoT Command listener, device can receive command object,
     * check the official doc on Google Cloud IoT
     */
    private val commandListener = object: OnCommandListener {
        override fun onCommandReceived(subFolder: String, commandData: ByteArray) {
            // TODO("not implemented")
            Log.d(TAG, "@CLOUD_IOT >> COMMAND Received  ")
        }
    }

    /**
     * Google Cloud IoT Configuration listener, device can receive a configuration
     * json object too, check the official doc on Google Cloud IoT
     */
    private val configurationListener = object: OnConfigurationListener {
        override fun onConfigurationReceived(configurationData: ByteArray) {
            // TODO("not implemented")
            Log.d(TAG, "@CLOUD_IOT >> CONFIGURATION Received  ")

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "@ACTIVITY >> CREATED")

    }

    override fun onStart() {
        super.onStart()

        // Init the Google Cloud IoT Connection params
        connectionParams = ConnectionParams(
            
        )

        // Init the Google Cloud IoT
        iotClient = IotCoreClient(
            connectionCallback,
            connectionParams,
            listOf(""),
            commandListener,
            configurationListener
        )

        iotClient.connect()
        // iotClient.publishTelemetry(TelemetryEvent("Hello".toByteArray(), "", 1))

        Log.d(TAG, "@ACTIVITY >> STARTED")
    }

    override fun onDestroy() {

        Log.d(TAG, "@ACTIVITY >> DESTROYED")
        super.onDestroy()

    }

}
