package com.cicciosgamino.iotcore

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
        val projectId : String,    // GCP Cloud Project name
        val registryId : String,   // Cloud IoT Registry name
        val deviceId : String,     // Cloud IoT Device Id
        val cloudRegion : String,  // GCP Cloud Region
        val authTokenLifetime: Long = DEFAULT_AUTH_TOKEN_LIFETIME_MILLIS// Auth Token Lifetime in Mills
) {

    // Cached Cloud IoT Core client Id
    val clientId : String
    // Cached Cloud IoT Core Telemetry topic
    val telemetryTopic : String
    // Cached Cloud IoT Core Device State Topic
    val deviceStateTopic : String
    // Cached Cloud IoT Core Device Config Topic
    val configurationTopic : String
    // Cached Cloud IoT Core Device Commands Topic
    val commandsTopic : String
    // Cached Broker URL
    val brokerUrl : String


    companion object {

        val DEFAULT_BRIDGE_HOSTNAME = "mqtt.googleapis.com"
        val DEFAULT_BRIDGE_PORT = 8883
        val DEFAULT_AUTH_TOKEN_LIFETIME_MILLIS = Duration.ofHours(1).toMillis()

    }

    init {

        brokerUrl = "ssl://$DEFAULT_BRIDGE_HOSTNAME:$DEFAULT_BRIDGE_PORT"
        clientId = "projects/$projectId/locations/$cloudRegion/registries/$registryId/devices/$deviceId"
        telemetryTopic = "/devices/$deviceId/events"
        deviceStateTopic = "/devices/$deviceId/state"
        configurationTopic = "/devices/$deviceId/config"
        commandsTopic = "/devices/$deviceId/commands"
    }

}