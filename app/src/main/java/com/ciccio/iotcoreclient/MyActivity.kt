package com.ciccio.iotcoreclient

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.cicciosgamino.iotcore.*


private val TAG = MyActivity::class.java.simpleName

class MyActivity : Activity() {

    // IotCore Params
    private val TOPICS = listOf<String>("topic-one", "topic-two")

    private lateinit var iotClient: IotCoreClient


    private val connectionParams = ConnectionParams(
        ...
    )

    // Connection Callback
    private val connectionCallback = object:ConnectionCallback {
        override fun onConnected() {
            Log.d(TAG, "@ACTIVITY >> CONNECTED to Google IoT Core ")
        }

        override fun onDisconnected(reason: Int) {
            Log.d(TAG, "@ACTIVITY >> DISCONNECTED from Google IoT Core : $reason")
        }
    }

    // Command Listener
    private val commandListener = object:OnCommandListener {
        override fun onCommandReceived(subFolder: String, commandData: ByteArray) {
            TODO("not implemented") //To change body of created ...
            Log.d(TAG, "@ACTIVITY >> Command Received  ")
        }
    }
    // Configuration Listener
    private val configurationListener = object:OnConfigurationListener {
        override fun onConfigurationReceived(configurationData: ByteArray) {
            TODO("not implemented") //To change body of created ...
            Log.d(TAG, "@ACTIVITY >> Configuration Received  ")

        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        iotClient = IotCoreClient(
            connectionCallback,
            connectionParams,
            TOPICS,
            commandListener,
            configurationListener
        )

        Log.d(TAG, "@MSG >> ACTIVITY.CREATED")
    }

    // onStart()
    override fun onStart() {
        super.onStart()

        // App Started
        Log.d(TAG, "@MSG >> ACTIVITY.STARTED")


        while(true) {

            // Connect
            iotClient.connect()

            // TEST - Device Status
            // iotClient.publishDeviceState("ONLINE")

            // TEST - Telemetry Event
            val telEvent: TelemetryEvent = TelemetryEvent(
                "@MSG ${System.currentTimeMillis()}".toByteArray(),
                "/tappetificio",
                1)
            iotClient.publishTelemetry(telEvent)

            Thread.sleep(60_000L)

        }

    }

    override fun onStop() {

        iotClient.disconnect()

        super.onStop()

        Log.d(TAG, "@MSG >> ACTIVITY.STOPPED")
    }

    // onDestroy()
    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "@MSG >> ACTIVITY.DESTROYED")
    }
}