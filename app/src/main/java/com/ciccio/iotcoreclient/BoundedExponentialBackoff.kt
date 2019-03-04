package com.ciccio.iotcoreclient

import android.os.SystemClock
import java.lang.IllegalArgumentException
import kotlin.random.Random

/**
 * Calculates bounded exponential backoff with jitter.
 *
 * BoundedExponentialBackoff instance with backoff starting value, the maximum time
 * to backoff, and the maximum amount of jitter, or randomness, to include in the backoff time.
 *
 * @param mInitialBackoffMillis minimum backoff time in milliseconds
 * @param mMaxBackoffMillis maximum backoff time in milliseconds
 * @param mJitterMillis maximum variation in backoff time in milliseconds.
*/
class BoundedExponentialBackoff(
        private val mInitialBackoffMillis: Long,
        private val mMaxBackoffMillis: Long,
        private val mJitterMillis: Long
) {

    private val mRandom: Random
    private var mCurrentBackoffDurationMillis: Long

    init {
        if(mInitialBackoffMillis <= 0)
            throw  IllegalArgumentException("@EXCEPION >> Initial Backoff time must be > 0")
        if(mMaxBackoffMillis <= 0)
            throw  IllegalArgumentException("@EXCEPION >> Maximum Backoff time must be > 0")
        if(mJitterMillis < 0)
            throw  IllegalArgumentException("@EXCEPION >> Jitter Backoff time must be >= 0")
        if(mMaxBackoffMillis < mInitialBackoffMillis)
            throw IllegalArgumentException("@EXCEPTION >> Maximum Backoff time must be >= Initial")

        mCurrentBackoffDurationMillis = mInitialBackoffMillis
        mRandom = Random(SystemClock.currentThreadTimeMillis())
    }


    /** Reset the backoff interval */
    fun reset() {
        mCurrentBackoffDurationMillis = mInitialBackoffMillis
    }

    /** Return a Backoff exponentially larger then the last */
    fun nextBackoff(): Long {
        val jitter = if (mJitterMillis === 0L) 0 else mRandom.nextLong(mJitterMillis)
        val backoff = mCurrentBackoffDurationMillis + jitter

        mCurrentBackoffDurationMillis = mCurrentBackoffDurationMillis shl 1
        if(mCurrentBackoffDurationMillis > mMaxBackoffMillis)
            mCurrentBackoffDurationMillis = mMaxBackoffMillis

        return backoff
    }

}