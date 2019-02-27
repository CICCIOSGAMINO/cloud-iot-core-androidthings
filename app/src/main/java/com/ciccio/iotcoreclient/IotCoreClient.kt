package com.ciccio.iotcoreclient

import android.os.Process
import android.util.Log
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import org.eclipse.paho.client.mqttv3.MqttException
import kotlin.concurrent.thread


/**
 * IotCoreClient manages interactions with Google Cloud IoT Core for a single device.
 *
 * <p>This class provides mechanisms for using Cloud IoT Core's main features. Namely
 * <ul>
 *    <li>Publishing device telemetry</li>
 *    <li>Publishing device state</li>
 *    <li>Receiving configuration changes</li>
 *    <li>Receiving commands</li>
 * </ul>
 *
 * <p>Create a new IotCoreClient using the {@link IotCoreClient.Builder}, and call
 * {@link IotCoreClient#connect()} to initialize the client connection. When you no longer
 * need to send and receive data, call {@link IotCoreClient#disconnect()} to close the connection
 * and free up resources.
 *
 * <p>Track the current connection using {@link IotCoreClient#isConnected()} or register a
 * {@link ConnectionCallback} to listen for changes in the client's conn, and publish data to Cloud
 *
 * <p>Publish data to Cloud IoT Core using {@link IotCoreClient#publishTelemetry(TelemetryEvent)}
 * and {@link IotCoreClient#publishDeviceState(byte[])}. These methods can be used regardless of the
 * client's connection state. If the client is connected, messages are published immediately.
 * Otherwise, if the client is disconnected, messages are stored in memory and sent when the
 * connection is reestablished.
 *
 * <p>Register an {@link OnConfigurationListener} with the client to receive device configuration
 * changes from Cloud IoT Core.
 *
 * <pre class="prettyprint">
 *     IotCoreClient iotCoreClient = new IotCoreClient.Builder()
 *             .setConnectionParams(connectionParams)
 *             .setKeyPair(keyPair);
 *             .setOnConfigurationListener(onConfigurationListener)
 *             .setOnCommandListener(onCommandListener)
 *             .setConnectionCallback(connectionCallback)
 *             .build();
 *     iotCoreClient.connect();
 *     iotCoreClient.publishDeviceState("Hello world!".getBytes());
 * </pre>
 *
 * <p>While disconnected, the client queues all messages for delivery when the connection is
 * restored. To customize the behavior of the offline message queue, call
 * {@link IotCoreClient.Builder#setTelemetryQueue(Queue)} with a queue implementation suited to
 * your application. If no queue implementation is provided, the default queue implementation is a
 * queue that stores up to 1000 telemetry events and drops events from the head of the queue when
 * messages are inserted beyond the maximum capacity.
 */

private val TAG = IotCoreClient::class.java.simpleName

class IotCoreClient(
    mConnectionParams: ConnectionParams,   // Info to connect to Cloud IoT Core
    private val mJwtGenerator: JwtGenerator,  // Generate signed JWT to authenticate on Cloud IoT Core
    private val mMqttClient: MqttClient,
    private val mClientConnectionState: AtomicBoolean, // The connection status from Client prospective
    private var mRunBackgroundThread: AtomicBoolean, // Control the execution of background thred, The thread stops if mRunBackgroundThread is false
    private val mSubscriptionTopics: List<String> = listOf<String>(),   // Subscription topics
    private val mUnsentTelemetryEvent: TelemetryEvent,  // Store telemetry events failed to sent
    private var mTelemetryQueue: Queue<TelemetryEvent>, // Queue<TelemetryEvent?>
    private val mConnectionCallback: ConnectionCallback,
    private var mConnectionCallbackExecutor: Executor,
    private val mOnCommandListener: OnCommandListener,
    private var mOnCommandExecutor: Executor,
    private val mOnConfigurationListener: OnConfigurationListener,
    private var mOnConfigurationExecutor: Executor
){

    // Settings for exponential backoff behavior. These values are from Cloud IoT Core's recommendations at
    // https://cloud.google.com/iot/docs/requirements#managing_excessive_load_with_exponential_backoff
    private val INITIAL_RETRY_INTERVAL_MS: Long = 1000
    private val MAX_RETRY_JITTER_MS = 1000
    private val MAX_RETRY_INTERVAL_MS = (64 * 1000).toLong()

    // Default telemetry queue capacity
    private val DEFAULT_QUEUE_CAPACITY = 1000

    // Quality of service level ( 1 = at least one / 0 = at most one )
    private val QOS_FOR_DEVICE_STATE_MESSAGES = 1

    private lateinit var mqttClient: MqttClient
    // Semaphore for thread
    private val mSemaphore = Semaphore(0)
    private val mCommandsTopicPrefixRegex = String.format(Locale.US, "^%s/?", mConnectionParams.commandsTopic)
    // Background Thread
    private var mBackgroundThread: Thread? = null

    // Queue of unsent telemetry events.
    private val mQueueLock = Any()

    init {

        if(mTelemetryQueue == null) {
            mTelemetryQueue = CapacityQueue<TelemetryEvent>(DEFAULT_QUEUE_CAPACITY, CapacityQueue.DROP_POLICY_HEAD)
        }

        if(mOnConfigurationListener != null && mOnConfigurationExecutor == null) {
            mOnConfigurationExecutor = createDefaultExecutor()
        }

        if(mOnCommandListener != null && mOnCommandExecutor == null) {
            mOnCommandExecutor = createDefaultExecutor()
        }

        if(mConnectionCallback != null && mConnectionCallbackExecutor == null) {
            mConnectionCallbackExecutor = createDefaultExecutor()
        }

        // Init the Mqtt
        try {
            mqttClient = MqttClient(
                mConnectionParams.brokerUrl,
                mConnectionParams.clientId,
                MemoryPersistence()
            )
        } catch (e: MqttException) {
            // According to the Paho documentation, this exception happens when the arguments to
            // the method are valid, but "other problems" occur. Since that isn't particularly
            // helpful, rethrow as an IllegalStateException so public API doesn't depend on Paho
            // library.
            //
            // Paho docs for this method are available at
            // http://www.eclipse.org/paho/files/javadoc/org/eclipse/paho/client/mqttv3/MqttClient.html#MqttClient-java.lang.String-java.lang.String-org.eclipse.paho.client.mqttv3.MqttClientPersistence-
            //
            // Based on the Paho source (https://github.com/eclipse/paho.mqtt.java), it looks
            // like this exception should never happen. The MqttClient constructor throws an
            // MqttException if
            //   1. MemoryPersistence.open throws an exception. This cannot happen.
            //      MemoryPersistence.open says it throws an MqttPersistenceException because it
            //      implements an interface that requires that definition.
            //   2. If there's an exception when sending unsent messages stored in the
            //      MemoryPersistence object. This should never happen because we make a new
            //      MemoryPersistence instance every time we call the MqttClient constructor.
            throw IllegalStateException(e)

        }

        // With Kotlin Object declaration, define the Mqtt Callbacks
        mMqttClient.setCallback(object: MqttCallback {

            override fun connectionLost(cause: Throwable?) {
                // Release the semaphore blocking the background thread so it reconnects to IoT Core
                mSemaphore.release()
                var reason: Int = ConnectionCallback.REASON_UNKNOWN

                if(cause is MqttException) {
                    reason = getDisconnectionReason(cause)
                }
                onDisconnect(reason)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {

                if (message?.payload != null && topic != null) {

                    if(mConnectionParams.configurationTopic.equals(topic, true) &&
                        mOnConfigurationListener != null &&
                        mOnConfigurationExecutor != null) {
                        // Call the client's onConfigurationListner
                        mOnConfigurationExecutor.execute { mOnConfigurationListener.onConfigurationReceived(message.payload) }

                    } else if(topic.startsWith(mConnectionParams.commandsTopic) &&
                        mOnCommandListener != null &&
                        mOnCommandExecutor != null) {
                        // Call the client's OnCommandListener
                        val subFolder = topic.replaceFirst(mCommandsTopicPrefixRegex, "")
                        mOnCommandExecutor.execute {
                            mOnCommandListener.onCommandReceived(subFolder, message.payload)
                        }

                    }

                } // End Null Check

            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
            }

            fun getDisconnectionReason(mqEx: MqttException): Int {

                return when(mqEx.reasonCode){
                    MqttException.REASON_CODE_FAILED_AUTHENTICATION.toInt(),
                        MqttException.REASON_CODE_NOT_AUTHORIZED.toInt()
                        // These cases happen if client use an invalid :
                        // + IoT Core Registry ID
                        // + IoT Core Device ID
                        // + Cloud GCP Region
                        // + Unregistred Signed Key
                            -> ConnectionCallback.REASON_NOT_AUTHORIZED

                    MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> {
                        if (mqEx.cause is EOFException) {
                            // This happens when Paho or Cloud IoT Core close the connection
                            if (mRunBackgroundThread.get()) {
                                // If mRunBackground is true, then Cloud Core IoT closed the connection
                                // Example this cloud happen if the client use an invalid GCP Project
                                // ID, the client exceeds a rate limit set by Cloud IoT Core or if
                                // the MQTT Broker address is invalid
                                return ConnectionCallback.REASON_CONNECTION_LOST
                            } else {
                                // If mRunBackground is false, then the client closed the connection
                                return ConnectionCallback.REASON_CLIENT_CLOSED
                            }
                        }

                        if (mqEx.cause is SSLException) {
                            // This case happens when something goes wrong in the network that
                            // ends an existing connection to Cloud IoT Core (e.g wifi driver resets)
                            return ConnectionCallback.REASON_CONNECTION_LOST
                        }
                        return ConnectionCallback.REASON_UNKNOWN
                    }

                    MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt() -> {
                        // Paho use this reason code for several distinct error cases
                        if(mqEx.cause is SocketTimeoutException) {
                            // This case could happen if MQTT bridge port number is wrong or
                            // there is some other error with the MQTT Bridge that keeps it from responding
                            return ConnectionCallback.REASON_CONNECTION_TIMEOUT
                        }

                        if(mqEx.cause is UnknownHostException) {
                            // This can happens if the client is disconnected from the Internet or if they
                            // use an invalid hostname for the MQTT bridge. Paho doesn't provide a way
                            // to get more information
                            return ConnectionCallback.REASON_CONNECTION_LOST
                        }
                        return ConnectionCallback.REASON_UNKNOWN
                    }

                    MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt(),
                        MqttException.REASON_CODE_WRITE_TIMEOUT.toInt() ->
                            return ConnectionCallback.REASON_CONNECTION_TIMEOUT

                    else -> ConnectionCallback.REASON_UNKNOWN

                }

            }

        })

    }  // End Init


    /**
     * New Chached Thread Pool
     */
    private fun createDefaultExecutor() : Executor {
        return Executors.newCachedThreadPool()
    }

    /**
     * Set the Mqtt Callbacks
     */
    private fun createMqttCallback(
        onConfigurationExecutor: Executor
    ) {

    }

    /**
     * Connect to Cloud IoT Core and perform any other required set up.
     *
     * <p>If the client registered a {@link ConnectionCallback},
     * {@link ConnectionCallback#onConnected()} will be called when the connection with
     * Cloud IoT Core is established. If the IotCoreClient ever disconnects from Cloud
     * IoT Core after this method is called, it will automatically reestablish the connection unless
     * {@link IotCoreClient#disconnect()} is called.
     *
     * <p>This method is non-blocking.
     */
    fun connect() {
        mRunBackgroundThread.set(true)
        // !mBackgroundThread.isAlive TODO
        if(mBackgroundThread == null) {

            mBackgroundThread = thread(start = true) {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)

                // Run as long as the thread is enable
                while(mRunBackgroundThread.get()) {
                    reconnectLoop()
                }
            }

        }

    }

    /**
     * Handle the re-connection Loop, Backoff time, Connected Task
     */
    private fun reconnectLoop() {
        Log.d(TAG, "@MSG >> CLIENT RECONNECT LOOP")

        try {
            connectMqttClient()

        } catch (mqEx: MqttException) {

        }
    }

    /**
     * Connect to Mqtt Client (Blocking Operation)
     */
    @Throws(MqttException::class)
    private fun connectMqttClient() {
        if (mMqttClient.isConnected) {
            return
        }

        mMqttClient.connect(configureConnectionOptions())

        for (topic in mSubscriptionTopics) {
            mMqttClient.subscribe(topic)
        }
        onConnection()
    }

    /**
     * Configure the MQTT connection options
     */
    private fun configureConnectionOptions(): MqttConnectOptions {
        val options = MqttConnectOptions()
        // Cloud IoT only supports MQTT 3.1.1, and Paho requires that we explicitly set this.
        // If you don't set MQTT version, server will immediately close connection to your device.
        options.mqttVersion = (MqttConnectOptions.MQTT_VERSION_3_1_1)
        // Cloud IoT Core ignores the user name field, but Paho requires a user name in order
        // to send the password field. We set the user name because we need the password to send a
        // JWT to authorize the device.
        options.userName = "unused"
        // generate the JWT password
        options.password = mJwtGenerator.createJwt().toCharArray() // TODO
        return options
    }

    /**
     * Call client's connection callbacks - Connection
     */
    private fun onConnection() {
        mConnectionCallbackExecutor.execute {
            if(mClientConnectionState.getAndSet(true)) {
                mConnectionCallback.onConnected()
            }
        }
    }

    /**
     *  Call client's connection callbacks - Disconnection
     */
    private fun onDisconnect(reason: Int) {
        mConnectionCallbackExecutor.execute {
            if(reason == ConnectionCallback.REASON_NOT_AUTHORIZED) {
                // Always notify on NOT_AUTHORIZED errors since they mean the client is
                // misconfigured and needs to do something to fix the problem.
                mClientConnectionState.set(false)
                mConnectionCallback.onDisconnected(reason)
            } else if(mClientConnectionState.getAndSet(false)) {
                // Otherwise, only notify the client if they have not been notified
                // about the change in connection yet
                mConnectionCallback.onDisconnected(reason)
            }
        }
    }

    /**
     * Returns true if connected to Cloud IoT Core, and returns false otherwise.
     *
     * @return whether the client is connection to Cloud IoT Core
     */
    fun isConnected(): Boolean {
        return mMqttClient.isConnected
    }

    /**
     * Disconnect the client from Cloud IoT Core.
     *
     * <p>This method is non-blocking.
     *
     */
    fun disconnect() {
        mRunBackgroundThread.set(false)
        mSemaphore.release()
    }

    /**
     * Add a telemetry event to this client's telemetry queue, if it is possible to do so without
     * violating the telemetry queue's capacity restrictions, and publish the event to
     * Cloud IoT Core as soon as possible.
     *
     * <p>This method is non-blocking.
     *
     * @param event the telemetry event to publish
     * @return Returns true if the event was queued to send, or return false if the event could
     * not be queued
     */
    fun publishTelemetry(event: TelemetryEvent): Boolean {
        synchronized(mQueueLock) {
            val preOfferSize = mTelemetryQueue.size
        }

    }



}