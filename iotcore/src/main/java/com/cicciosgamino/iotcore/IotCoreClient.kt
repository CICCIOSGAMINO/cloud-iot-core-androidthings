package com.cicciosgamino.iotcore

import android.util.Log
import kotlinx.coroutines.*
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.io.EOFException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException
import org.eclipse.paho.client.mqttv3.MqttException
import java.util.concurrent.atomic.AtomicReference


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
 * <p>Create a new IotCoreClient using the {@link IotCoreClient()}, and call
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
 *     private val iotCoreClient:IotCoreClient  = IotCoreClient(
                params,
 *          )
 *     iotCoreClient.connect()
 *     iotCoreClient.publishDeviceState("Hello world!".getBytes())
 * </pre>
 *
 * @param connectionParams Parameters needed to connect to Google Cloud IoT Core
 * @param subscriptionTopics List with all subscription topics
 * @param telemetryQueue Queue to retain the <TelemetryEvent?> when no connection available
 *
 * <p>While disconnected, the client queues all messages for delivery when the connection is
 * restored. To customize the behavior of the offline message queue, pass a Queue to class
 * with a queue implementation suited to your application. If no queue implementation is provided,
 * the default queue implementation is a queue that stores up to 1000 telemetry events and drops
 * events from the head of the queue when messages are inserted beyond the maximum capacity.
 *
 */

private val TAG = IotCoreClient::class.java.simpleName

class IotCoreClient(
    private val connectionCallback: ConnectionCallback,
    private val connectionParams: ConnectionParams,
    private val subscriptionTopics: List<String> = listOf<String>(),
    private val onCommandListener: OnCommandListener,
    private val onConfigurationListener: OnConfigurationListener
){

    // Connected Task Loop Delay
    private val TASKS_LOOP_DELAY = 5_000L
    // Default telemetry queue capacity
    private val DEFAULT_QUEUE_CAPACITY = 10000
    // Quality of service level ( 1 = at least one / 0 = at most one )
    private val QOS_FOR_DEVICE_STATE_MESSAGES = 1
    // Handle the MQTT Authentication
    private val mqttAuthentication: MqttAuthentication
    // MqttClient
    private var mqttClient: MqttClient
    // MqttClient Connection state
    private val clientConnectionState = AtomicBoolean(false)
    // BoundedExponentialBackoff (handle the re-connecting backoff)
    private val backoff: BoundedExponentialBackoff

    // Coroutine Connection Loop scope
    private val loopJob = Job()
    private val loopScope = CoroutineScope(Dispatchers.IO + loopJob)

    private val mCommandsTopicPrefixRegex = String.format(Locale.US, "^%s/?", connectionParams.commandsTopic)

    // Store telemetry events failed to sent
    private var unsentTelemetryEvent: TelemetryEvent? = null
    // Store telemetry events failed to sent
    private var unsentState: AtomicReference<String> = AtomicReference("")

    private var telemetryQueue: Queue<TelemetryEvent>

    init {

        // Init Mqtt authentication, Signed JWT to authenticate on Cloud IoT Core
        mqttAuthentication = MqttAuthentication()

        // Init the Telemetry Queue
        telemetryQueue = CapacityQueue<TelemetryEvent>(
            DEFAULT_QUEUE_CAPACITY,
            CapacityQueue.DROP_POLICY_HEAD
        )

        // Init Backoff
        backoff = BoundedExponentialBackoff()

        // Init the Mqtt
        try {
            mqttClient = MqttClient(
                connectionParams.brokerUrl,
                connectionParams.clientId,
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
            Log.d(TAG, "@EXCEPTION >> Init MQTT : $e")

            throw IllegalStateException(e)

        }

        // With Kotlin Object declaration, define the Mqtt Callbacks
        mqttClient.setCallback(object: MqttCallback {

            override fun connectionLost(cause: Throwable?) {

                var reason: Int = ConnectionCallback.REASON_UNKNOWN

                if(cause is MqttException) {
                    reason = getDisconnectionReason(cause)
                }

                onDisconnect(reason)
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {

                if (message?.payload != null && topic != null) {

                    if(connectionParams.configurationTopic.equals(topic, true)) {
                        // Call the client's onConfigurationListner
                        onConfigurationListener.onConfigurationReceived(message.payload)

                    } else if(topic.startsWith(connectionParams.commandsTopic)) {
                        // Call the client's OnCommandListener
                        val subFolder = topic.replaceFirst(mCommandsTopicPrefixRegex, "")
                        onCommandListener.onCommandReceived(subFolder, message.payload)
                    }

                }

            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                Log.d(TAG , "@MSG >> Delivery COMPLETE : $token")
            }

            fun getDisconnectionReason(mqEx: MqttException): Int {

                Log.d(TAG, "@DBG >> Disconnection Reason ${mqEx.message}")

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

                            // If mRunBackground is true, then Cloud Core IoT closed the connection
                            // Example this cloud happen if the client use an invalid GCP Project
                            // ID, the client exceeds a rate limit set by Cloud IoT Core or if
                            // the MQTT Broker address is invalid
                            return ConnectionCallback.REASON_CONNECTION_LOST
                            // If mRunBackground is false, then the client closed the connection
                            // return ConnectionCallback.REASON_CLIENT_CLOSED
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

        if (!isConnected()) {
            reconnectLoop()
        }
    }

    /**
     * Handle the re-connection Loop, Backoff time, Connected Task
     */
    private fun reconnectLoop() {

        // Loop Coroutine Scope - Keep track from Loop Coroutine
        loopScope.launch {

            // Explicitly check the cancellation status
            while(isActive) {

                // INFINITE LOOP - Connect MQTT - doConnected Task
                while(true) {

                    try {

                        connectMqttClient()

                        // Successfully connected, so we can reset the backoff time
                        backoff.reset()

                        // Perform Task that require a connection
                        doConnectedTask()

                    } catch (mqttException: MqttException) {
                        // Update backoff waiting time
                        if(isRetryableError(mqttException)) delay(backoff.nextBackoff())

                    }
                }

            } // End isActive
        }
    }

    /**
     * Handle the task to do when connected to MQTT Pub/Sub
     */
    @Throws(MqttException::class)
    suspend fun doConnectedTask() {

        while(isConnected()) {

            try {

                // Handle Device State
                handleState()

                // Handle Telemetry
                handleTelemetry()

                delay(TASKS_LOOP_DELAY)

            } catch(mqttEx: MqttException) {
                Log.d(TAG, "@MQTT_EXCEPTION >> doConnectedTask LOOP : ${mqttEx.message}")
            }
        }
    }

    /**
     * Handle device state event (no queue) only last state of device
     */
    @Throws(MqttException::class)
    private fun handleState() {

        val temp = unsentState?.get() ?: ""

        if(temp.isNotEmpty()) {
            // Sent device state
            publish(connectionParams.deviceStateTopic, temp.toByteArray(),
                QOS_FOR_DEVICE_STATE_MESSAGES)
            unsentState.set("")
        }

    }

    /**
     * Handle telemetry event (with queue) and actual state
     */
    @Throws(MqttException::class)
    private fun handleTelemetry() {
        // Only send events from the client's telemetry queue
        if(unsentTelemetryEvent == null) {

            // Remove and return the head of the queue
            unsentTelemetryEvent = telemetryQueue.poll()


            if(unsentTelemetryEvent == null) {
                // Queue is empty do nothing
                return
            }
        }

        // send the event
        val unsent = unsentTelemetryEvent
        if(unsent != null) {
            publish(
                connectionParams.telemetryTopic + unsent.topicSubpath,
                unsent.data,
                unsent.qos)

            // TODO - Debug Telemetry Event Sent
            Log.d(TAG, "@DBG >> Published Telemetry Event : ${unsent.data}")
        }

        // Event sent successfully. Clear the cached event.
        unsentTelemetryEvent = null
    }

    /**
     * Publish data to a topic
     */
    @Throws(MqttException::class)
    fun publish(topic: String, data: ByteArray, qos: Int) {
        try {

            mqttClient.publish(topic, data, qos, false)

        } catch (mqttException: MqttException) {
            // If there was an error publishing the message, it was either because of an issue with
            // the way the message itself was formatted or an error with the network.
            //
            // In the network error case, the message can be resent when the network is working
            // again. Rethrow the exception so higher level functions can take care of reconnecting
            // and resending the message.
            //
            // If the message itself was the problem, don't propagate the error since there's
            // nothing we can do about it except log the error to the client.
            if(isRetryableError(mqttException)) {

                throw mqttException
            }

            // Return success and don't try to resend the message that caused the exception. Log
            // the error so the user has some indication that something went wrong.
            Log.w(TAG, "Error publishing message to ${topic} >> @EXCEPTION $mqttException")
        }
    }


    /**
     * Determine whether the mqttException is an error that may be resolved by retrying or whether
     * the error cannot be resolved. Determined according to guidance from Cloud IoT Core's
     * <a href="https://cloud.google.com/iot/docs/how-tos/errors">documentation</a>.
     *
     * @return Returns true if the MQTT client should resend the message. Returns false otherwise.
     */
    private fun isRetryableError(mqttException: MqttException): Boolean {

        Log.d(TAG, "@DBG >> RetryableError .. ${mqttException.message}")


        return when(mqttException.reasonCode) {

            MqttException.REASON_CODE_SERVER_CONNECT_ERROR.toInt(),
                MqttException.REASON_CODE_WRITE_TIMEOUT.toInt(),
                MqttException.REASON_CODE_CLIENT_NOT_CONNECTED.toInt(),
                MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt() -> true
            MqttException.REASON_CODE_CLIENT_EXCEPTION.toInt() -> {
                // This case happens when there is no internet connection. Unfortunately, Paho
                // doesn't provide a better way to get that information.
                if (mqttException.cause is UnknownHostException) {
                    return true
                }
                // Not Retriable Error
                return false
            }
            MqttException.REASON_CODE_CONNECTION_LOST.toInt() -> {
                // If the MqttException's cause is an EOFException, then the client or Cloud IoT
                // Core closed the connection, the connection was likely closed because
                // the client was publishing device state updates too quickly. Mark this as a
                // "retryable" error so the message that caused the exception isn't discarded.
                if (mqttException.cause is EOFException) {
                    return true
                }
                // Not Retryable Error 
                return false
            }
            // Not Retryable Error
            else -> false
        }
    }


    /**
     * Connect to Mqtt Client
     */
    @Throws(MqttException::class)
    private fun connectMqttClient() {

        Log.d(TAG, "@DBG >> TRY To Connect to MQTT ")

        if (mqttClient.isConnected) {
            return
        }

        mqttClient.connect(configureConnectionOptions())

        for (topic in subscriptionTopics) {

            // TODO Handle the topics
            // Log.d(TAG, "@DBG >> Topics $topic")

            mqttClient.subscribe(topic)
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
        options.password = mqttAuthentication.createJwt(connectionParams.projectId)
        return options
    }

    /**
     * Call client's connection callbacks - Connection
     */
    private fun onConnection() {
        if(clientConnectionState.getAndSet(true)) {
            connectionCallback.onConnected()
        }
    }

    /**
     *  Call client's connection callbacks - Disconnection
     */
    private fun onDisconnect(reason: Int) {
            clientConnectionState.set(false)
            connectionCallback.onDisconnected(reason)
    }

    /**
     * Returns true if connected to Cloud IoT Core, and returns false otherwise.
     *
     * @return whether the client is connection to Cloud IoT Core
     */
    fun isConnected(): Boolean {
        return mqttClient.isConnected
    }

    /**
     * Disconnect the client from Cloud IoT Core. Close the MQTT Connection
     * and cancel the Coroutine
     *
     */
    fun disconnect() {
        // Close MQTT Connection
        mqttClient.close()
        // Close Coroutine Job
        loopJob.cancel()
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

        val preOfferSize = telemetryQueue.size
        if(!telemetryQueue.offer(event) || telemetryQueue.size == preOfferSize) {

            return false
        }

        // Event added to the Queue go on with Loop

        // TODO - Debug on Telemetry
        Log.d(TAG, "@DBG >> Telemetry add to Queue[size:${telemetryQueue.size}] ")

        return true

    }


    /**
     * Publishes state data to Cloud IoT Core.
     *
     * <p>If the connection to Cloud IoT Core is lost and messages cannot be published to
     * Cloud IoT Core, device state is published to Cloud IoT Core before any
     * unpublished telemetry events when the connection is reestablished.
     *
     * <p>If there are multiple attempts to publish device state while disconnected from
     * Cloud IoT Core, only the newest device state will be published when the connection is
     * reestablished.
     *
     * <p>This method is non-blocking, and state is published using "at least once" semantics.
     *
     * <p>Cloud IoT Core limits the number of device state updates per device to 1 per
     * second. If clients of this library attempt to publish device state faster than that, some
     * device state data may be lost when Cloud IoT Core resets the connection. The
     * Cloud IoT Core <a href="https://cloud.google.com/iot/quotas">documentation</a> has more
     * information about quotas and usage restrictions.
     *
     * @param state the device state data to publish
     */
    fun publishDeviceState(state: String) {

        // TODO - Debug on Device status
        Log.d(TAG, "@DBG >> Publish Device STATE")

        unsentState.set(state)
    }


}