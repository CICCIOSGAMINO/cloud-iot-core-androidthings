package com.ciccio.iotcoreclient

import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

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
    private val mRunBackgroundThread: AtomicBoolean, // Control the execution of b thred, The thread stops if mRunBackgroundThread is false
    private val mSubscriptionTopics: List<String> = listOf<String>(),   // Subscription topics
    private val mUnsentTelemetryEvent: TelemetryEvent,  // Store telemetry events failed to sent
    private var mTelemetryQueue: Queue<TelemetryEvent>?,
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
    private val mSemaphore = Semaphore(0)

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
                mConnectionParams.getBrokerUrl(),
                mConnectionParams.getClientId(),
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
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            }


            fun getDisconnectionReason(mqEx: MqttException): Int {
                when(mqEx.reasonCode){
                    MqttException.REASON_CODE_FAILED_AUTHENTICATION -> 
                }
            }

        })

    }

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




}