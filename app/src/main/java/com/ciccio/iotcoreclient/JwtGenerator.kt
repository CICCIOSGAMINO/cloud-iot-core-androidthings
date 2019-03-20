package com.ciccio.iotcoreclient

import java.security.KeyPair
import java.time.Duration

class JwtGenerator(
    keyPair: KeyPair,
    projectId: String,
    authTokenLifetime: Duration
) {

    init {

    }

    fun createJwt(): String {

        return "Hello World"
    }

}