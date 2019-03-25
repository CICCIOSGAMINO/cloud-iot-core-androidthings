package com.ciccio.iotcoreclient

import android.os.Environment
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import org.joda.time.DateTime
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.*
import java.security.cert.Certificate
import java.security.spec.InvalidKeySpecException
import javax.security.auth.x500.X500Principal

/**
 * Google Cloud IoT Authentication
 */
private val TAG = MqttAuthentication::class.java.simpleName

class MqttAuthentication {

    companion object {
        const val DEFAULT_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "cloudiotauth"
        const val FILE_PUB_KEY = "rs256_x509.pub"

        /** Years KeyPairs Expiration */
        const val KEY_PAIR_EXPIRATION = 20
        /** Minutes Jwt Token Expiration */
        const val JWT_TOKEN_EXPIRATION = 20
    }

    private lateinit var keyStore: KeyStore
    private lateinit var certificate : Certificate
    private lateinit var privateKey : PrivateKey

    init {

        try {
            keyStore = KeyStore.getInstance(DEFAULT_KEYSTORE)
            keyStore.load(null)

            certificate = keyStore.getCertificate(KEY_ALIAS)

            if (certificate == null) {
                /** Generate Key */
                Log.d(TAG, "No X509Certificate FOUND (Creating .... ) ")

                generateAuthenticationKey()
                certificate = keyStore.getCertificate(KEY_ALIAS)
            }
            Log.d(TAG, "Loaded certificate : " + KEY_ALIAS)

            val key : Key = keyStore.getKey(KEY_ALIAS, null)
            privateKey = key as PrivateKey

            exportPublicKey(FILE_PUB_KEY)

        } catch (generalSecurityException: GeneralSecurityException) {
            Log.w(TAG, "@SECURITY_EXCEPTION >> $generalSecurityException")
        } catch (ioExceptions: IOException) {
            Log.w(TAG, "@IOEXCEPTION >> $ioExceptions" )
        }
    }


    /**
     * Generate a new RSA Key pair entry in the Android keystore by
     * using the KeyPairGenerator API. This create both a KeyPair and
     * a self-signed certificate, both with the same alias
     */
    @Throws(GeneralSecurityException::class)
    fun generateAuthenticationKey() {

        val now = DateTime()

        val kpg : KeyPairGenerator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA, DEFAULT_KEYSTORE)
        kpg.initialize(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048)
                .setCertificateSubject(X500Principal("CN=unused"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setKeyValidityEnd(now.plusYears(KEY_PAIR_EXPIRATION).toDate())
                .build())
        kpg.generateKeyPair()

    }

    /**
     * Exports the authentication certificate to a file
     * @param fileName the file to write the certificate to PEM encoded
     */
    @Throws(
        GeneralSecurityException::class,
        IOException::class)
    fun exportPublicKey(fileName: String) {

        /** Write the Public Key in the /key/public.pub  */
        val path = Environment.getExternalStorageDirectory().absolutePath

        val file = File(path + File.separator + fileName)
        val fos = FileOutputStream(file)

        try {
            fos.write(getCertificatePEM().toByteArray())
            fos.flush()
            fos.close()
        } catch (fex : FileNotFoundException) {
            Log.e(TAG, " @EXCEPTION >> ", fex)
        }
    }


    /**
     * Get the certificate in PEM-format encoded
     */
    fun getCertificatePEM() : String {
        val sb = StringBuilder()
        sb.append("-----BEGIN CERTIFICATE-----\n")
        sb.append(Base64.encodeToString(certificate?.encoded, Base64.DEFAULT))
        sb.append("-----END CERTIFICATE-----\n")
        return sb.toString()
    }


    /**
     * Create the Auth JWT token for the device
     */
    @Throws(
        InvalidKeySpecException::class,
        NoSuchAlgorithmException::class,
        IOException::class)
    fun createJwt(projectId : String) : CharArray {
        val now = DateTime()
        /** Create a JWT token to authenticate this device, the device
         * will be disconnected after the token expires, audience field
         * should always be set to the GCP project id
         */
        val jwtBuilder = Jwts.builder()
            .setIssuedAt(now.toDate())
            .setExpiration(now.plusMinutes(JWT_TOKEN_EXPIRATION).toDate())
            .setAudience(projectId)

        return jwtBuilder.signWith(SignatureAlgorithm.RS256, privateKey).compact().toCharArray()
    }

}