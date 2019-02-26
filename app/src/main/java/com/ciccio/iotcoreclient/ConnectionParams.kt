package com.ciccio.iotcoreclient

import java.lang.IllegalArgumentException
import java.time.Duration

/**
 * Stores information necessary to connect a single device to Google Cloud IoT Core: the device's
 * <a href="https://cloud.google.com/iot/docs/how-tos/mqtt-bridge#device_authentication">client ID
 * </a> and some MQTT configuration settings.
 *
 * <p>Specifying the device's client ID requires the full specification for the Cloud IoT Core
 * registry in which the device is registered, which includes the device's Google Cloud project ID,
 * the device's Cloud IoT Core registry ID, the registry's cloud region, and the device's ID within
 * the registry. These parameters are set in the {@link ConnectionParams.Builder}.
 */

class ConnectionParams(
        mProjectId : String,    // GCP Cloud Project name
        mRegistryId : String,   // Cloud IoT Registry name
        mDeviceId : String,     // Cloud IoT Device Id
        mCloudRegion : String,  // GCP Cloud Region
        mBridgeHostname : String,   // MQTT Bridge hostname
        mBridgePort : Int,      // MQTT Bridge port
        mAuthTokenLifetimeMills : Long  // Duration JWT Token

) {

    // Cached Cloud IoT Core client Id
    private val mClientId : String
    // Cached Cloud IoT Core Telemetry topic
    private val mTelemetryTopic : String
    // Cached Cloud IoT Core Device State Topic
    private val mDeviceStateTopic : String
    // Cached Cloud IoT Core Device Config Topic
    private val mConfigurationTopic : String
    // Cached Cloud IoT Core Device Commands Topic
    private val mCommandsTopic : String
    // Cached Broker URL
    private val mBrokerUrl : String


    companion object {

        val DEFAULT_BRIDGE_HOSTNAME = "mqtt.googleapis.com"
        val DEFAULT_BRIDGE_PORT = 8883
        val MAX_TCP_PORT = 65535
        val DEFAULT_AUTH_TOKEN_LIFETIME_MILLIS = Duration.ofHours(1).toMillis()

    }

    init {

        if(mBridgePort <= 0) {
            throw IllegalArgumentException("MQTT Bridge Port must be > 0")
        }
        if(mBridgePort > MAX_TCP_PORT) {
            throw IllegalArgumentException("MQTT Bridge Port must be < $MAX_TCP_PORT")
        }
        if(mAuthTokenLifetimeMills <= 0) {
            throw IllegalArgumentException("JWT Auth Token Lifetime must be > 0")
        }
        if(mAuthTokenLifetimeMills > Duration.ofHours(24).toMillis()){
            throw IllegalArgumentException("JWT Auth Token Lifetime cannot exceed 24 hours")
        }

        mBrokerUrl = "ssl://$mBridgeHostname:$mBridgePort"
        mClientId = "projects/$mProjectId/locations/$mCloudRegion/registries/$mRegistryId/devices/$mDeviceId"
        mTelemetryTopic = "/devices/$mDeviceId/events"
        mDeviceStateTopic = "/devices/$mDeviceId/state"
        mConfigurationTopic = "/devices/$mDeviceId/config"
        mCommandsTopic = "/devices/$mDeviceId/commands"
    }


    /**
     * Get the URL of Broker
     */
    fun getBrokerUrl(): String {
        return mBrokerUrl
    }

    /**
     * Get complete Google Cloud Client Id
     */
    fun getClientId(): String {
        return mClientId
    }


}