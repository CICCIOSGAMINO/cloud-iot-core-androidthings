package com.ciccio.iotcoreclient

interface OnConfigurationListener {

    /**
     * Invoked when device configuration data is received from Cloud IoT Core.
     *
     * @param configurationData data received from Cloud IoT Core
     */
    fun onConfigurationReceived(configurationData: ByteArray)

}