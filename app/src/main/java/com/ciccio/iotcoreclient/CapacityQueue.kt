package com.ciccio.iotcoreclient

import android.util.Log
import java.lang.IllegalArgumentException
import java.util.*

private val TAG = CapacityQueue::class.java.simpleName

internal class CapacityQueue<E>(
                    private var mMaxCapacity : Int = 100,
                    private val mDropPolicy : Int = 1
) : AbstractQueue<E>() {

    // Linear collection supports element insertion/removal at both ends
    private lateinit var mDeque : Deque<E>

    companion object {
        /**
         * A CapacityQueue's drop policy determines how CapacityQueue handles attempts to enqueue new
         * elements after the queue is at maximum capacity.
         *
         *
         * DROP_POLICY_TAIL means that elements enqueued in a full CapacityQueue are rejected until
         * older elements are dequeued.
         *
         *
         * DROP_POLICY_HEAD means that when an element is enqueued in a full CapacityQueue, the
         * oldest element, the element at the head of the queue, is discarded and the new element is
         * added to the back of the queue.
         */
        val DROP_POLICY_HEAD = 0
        val DROP_POLICY_TAIL = 1
    }

    init {
        if(mMaxCapacity <= 0) {
            throw IllegalArgumentException("Queue capacity must be greater than 0")
        }
        if(mDropPolicy != DROP_POLICY_HEAD &&
                mDropPolicy != DROP_POLICY_TAIL) {
            throw IllegalArgumentException("Queue drop policy must be DROP_POLICY_HEAD or DROP_POLICY_TAIL")
        }

        mDeque = ArrayDeque()
    }

    override val size: Int
        get() = mDeque.size

    override fun offer(e: E): Boolean {

        // DROP_POLICY_HEAD
        if(mDropPolicy == DROP_POLICY_TAIL) {
            return mDeque.size < mMaxCapacity && mDeque.offerLast(e)
        }

        // DROP_POLICY_TAIL
        if(mDeque.size >= mMaxCapacity) {
            Log.d(TAG, "Dropping from HEAD")
            mDeque.removeFirst()
        }
        return mDeque.offerLast(e)
    }

    override fun poll(): E {
        return mDeque.pollFirst()
    }

    override fun peek(): E {
        return mDeque.peekFirst()
    }

    override fun iterator(): MutableIterator<E> {
        return mDeque.iterator()
    }

}