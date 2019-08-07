package com.cicciosgamino.iotcore

import java.lang.IllegalArgumentException
import java.util.*

internal class CapacityQueue<E>(
                    private var maxCapacity : Int = 100,
                    private val dropPolicy : Int = 1
) : AbstractQueue<E>() {

    // Linear collection supports element insertion/removal at both ends
    private val dqeque : Deque<E>

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
        const val DROP_POLICY_HEAD = 0
        const val DROP_POLICY_TAIL = 1
    }

    init {
        if(maxCapacity <= 0) {
            throw IllegalArgumentException(
                "@EXCEPION QUEUE >> Queue capacity must be greater than 0")
        }
        if(dropPolicy != DROP_POLICY_HEAD &&
                dropPolicy != DROP_POLICY_TAIL
        ) {
            throw IllegalArgumentException("" +
                    "@EXCEPION QUEUE >> Queue drop policy must be DROP_POLICY_HEAD or DROP_POLICY_TAIL")
        }

        dqeque = ArrayDeque()
    }

    override val size: Int
        get() = dqeque.size

    override fun offer(e: E): Boolean {

        // DROP_POLICY_HEAD
        if(dropPolicy == DROP_POLICY_TAIL) {
            return dqeque.size < maxCapacity && dqeque.offerLast(e)
        }

        // DROP_POLICY_TAIL
        if(dqeque.size >= maxCapacity) {
            dqeque.removeFirst()
        }
        return dqeque.offerLast(e)
    }

    override fun poll(): E {
        return dqeque.pollFirst()
    }

    override fun peek(): E {
        return dqeque.peekFirst()
    }

    override fun iterator(): MutableIterator<E> {
        return dqeque.iterator()
    }

}