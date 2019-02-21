package com.ciccio.iotcoreclient

interface OnCommandListener {

    /**
     * Invoked when device command data is received from Cloud IoT Core.
     *
     * @param subFolder the subFolder the command is received on.
     * @param commandData data received from Cloud IoT Core
     */
    fun onCommandReceived(subFolder: String, commandData: ByteArray)

}