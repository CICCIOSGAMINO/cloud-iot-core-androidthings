package com.cicciosgamino.iotcore

import android.os.SystemClock
import java.lang.IllegalArgumentException
import kotlin.random.Random

/**
 * Calculates bounded exponential backoff with jitter.
 *
 * BoundedExponentialBackoff instance with backoff starting value, the maximum time
 * to backoff, and the maximum amount of jitter, or randomness, to include in the backoff time.
 *
 * @param initialBackoffMillis minimum backoff time in milliseconds
 * @param maxBackoffMillis maximum backoff time in milliseconds
 * @param jitterMillis maximum variation in backoff time in milliseconds.
 *
 * Settings for exponential backoff behavior. These values are from Cloud IoT Core's
 * recommendations at
 * https://cloud.google.com/iot/docs/requirements#managing_excessive_load_with_exponential_backoff
 *
 * INITIAL_RETRY_INTERVAL_MS = 1000;
 * MAX_RETRY_JITTER_MS = 1000;
 * MAX_RETRY_INTERVAL_MS = 64 * 1000;
 *
*/
class BoundedExponentialBackoff(
        private val initialBackoffMillis: Long = 1000,
        private val jitterMillis: Long = 1000,
        private val maxBackoffMillis: Long = 600 * 1000
) {

    private val mRandom: Random
    private var mCurrentBackoffDurationMillis: Long

    init {
        if(initialBackoffMillis <= 0)
            throw  IllegalArgumentException(
                "@EXCEPION BACKOFF >> Initial Backoff time must be > 0")
        if(maxBackoffMillis <= 0)
            throw  IllegalArgumentException(
                "@EXCEPION BACKOFF >> Maximum Backoff time must be > 0")
        if(jitterMillis < 0)
            throw  IllegalArgumentException(
                "@EXCEPION BACKOFF >> Jitter Backoff time must be >= 0")
        if(maxBackoffMillis < initialBackoffMillis)
            throw IllegalArgumentException(
                "@EXCEPTION BACKOFF >> Maximum Backoff time must be >= Initial")

        mCurrentBackoffDurationMillis = initialBackoffMillis
        mRandom = Random(SystemClock.currentThreadTimeMillis())
    }


    /** Reset the backoff interval */
    fun reset() {
        mCurrentBackoffDurationMillis = initialBackoffMillis
    }

    /** Return a Backoff exponentially larger then the last */
    fun nextBackoff(): Long {
        val jitter = if (jitterMillis == 0L) 0 else mRandom.nextLong(jitterMillis)
        val backoff = mCurrentBackoffDurationMillis + jitter

        mCurrentBackoffDurationMillis = mCurrentBackoffDurationMillis shl 1
        if(mCurrentBackoffDurationMillis > maxBackoffMillis)
            mCurrentBackoffDurationMillis = maxBackoffMillis

        return backoff
    }

}