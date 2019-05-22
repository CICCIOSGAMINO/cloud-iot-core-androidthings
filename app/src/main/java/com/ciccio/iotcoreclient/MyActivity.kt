package com.ciccio.iotcoreclient

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.cicciosgamino.iotcore.ConnectionParams
import com.cicciosgamino.iotcore.IotCoreClient

private val TAG = MyActivity::class.java.simpleName

class MyActivity : Activity() {

    private lateinit var iotClient: IotCoreClient
    private lateinit var connectionParams: ConnectionParams

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Init the iotCore module
        connectionParams = ConnectionParams(
            "project-id",
            "registry-id",
            "device-id",
            "cloud-region"
        )

        // iotClient = IotCoreClient()

        Log.d(TAG, "@MSG >> ACTIVITY.CREATED")
    }

    override fun onStart() {
        super.onStart()

        Log.d(TAG, "@MSG >> ACTIVITY.STARTED")
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "@MSG >> ACTIVITY.DESTROYED")
    }
}