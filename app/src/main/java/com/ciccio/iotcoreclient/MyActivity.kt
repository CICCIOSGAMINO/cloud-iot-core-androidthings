package com.ciccio.iotcoreclient

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.*
import com.cicciosgamino.iotcore.*


private val TAG = MyActivity::class.java.simpleName

class MyActivity : Activity() {

    // Test Coroutines Scope
    private val mainScope = CoroutineScope(Dispatchers.Default)

    // IotCore Params
    private val TOPICS = listOf<String>("topic-one", "topic-two")

    private lateinit var iotClient: IotCoreClient


    private val connectionParams = ConnectionParams(
    ...
    )

    // Connection Callback
    private val connectionCallback = object:ConnectionCallback {
        override fun onConnected() {
            Log.d(TAG, "@ACTIVIYT >> CONNECTED to IoT Service")
        }

        override fun onDisconnected(reason: Int) {
            Log.d(TAG, "@ACTIVITY >> DISCONNECTED from IoT Service : $reason")
        }
    }

    // Command Listener
    private val commandListener = object:OnCommandListener {
        override fun onCommandReceived(subFolder: String, commandData: ByteArray) {
            TODO("not implemented") //To change body of created ...
            Log.d(TAG, "@ACTIVITY >> COMMAND ")
        }
    }
    // Configuration Listener
    private val configurationListener = object:OnConfigurationListener {
        override fun onConfigurationReceived(configurationData: ByteArray) {
            TODO("not implemented") //To change body of created ...
            Log.d(TAG, "@ACTIVITY >> CONFIGURATION ")

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

    override fun onStart() {
        super.onStart()

        // App Started
        Log.d(TAG, "@MSG >> ACTIVITY.STARTED")

        // Connect to Google Cloud IoT Core
        iotClient.connect()

        // val telEvent: TelemetryEvent = TelemetryEvent("Hello World".toByteArray(), "/boo", 1)
        // iotClient.publish("tappetificio", "Hello World".toByteArray(), 1)

        // TEST send device state
        mainScope.launch {

            while(true) {

                iotClient.publishDeviceState("ONLINE".toByteArray())
                delay(20_000)
            }


        }



    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "@MSG >> ACTIVITY.DESTROYED")
    }
}