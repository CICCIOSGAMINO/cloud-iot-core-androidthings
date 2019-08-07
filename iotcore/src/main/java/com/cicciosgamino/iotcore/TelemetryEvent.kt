package com.cicciosgamino.iotcore

import java.lang.IllegalArgumentException

/**
 * Constructs a new TelemetryEvent with the data to publish and an
 * optional topic subpath destination.
 *
 * @param data the telemetry event data to send to Cloud IoT Core
 * @param topicSubpath the subpath under "../device/../events/"
 * @param qos the quality of service to use when sending the message
 */
class TelemetryEvent(
            val data : ByteArray,
            val topicSubpath : String,
            val qos : Int
) {

    companion object {
        /** At most once delivery.  */
        val QOS_AT_MOST_ONCE = 0

        /** At least once delivery.  */
        val QOS_AT_LEAST_ONCE = 1
    }

    init {
        if(qos != QOS_AT_LEAST_ONCE && qos != QOS_AT_MOST_ONCE) {
            throw IllegalArgumentException("" +
                    "@EXCEPTION TELEMETRY >> Invalid quality of service QoS provided")
        }
    }
}