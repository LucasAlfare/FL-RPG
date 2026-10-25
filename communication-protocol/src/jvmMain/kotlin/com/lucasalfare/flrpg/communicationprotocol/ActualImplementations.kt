@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lucasalfare.flrpg.communicationprotocol

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

actual class PlatformLock {
  val lock = ReentrantLock()
  actual inline fun <T> lock(block: () -> T): T {
    return lock.withLock(block)
  }
}

actual class ThreadSafeMessageIdSet {
  private val set = ConcurrentHashMap.newKeySet<MessageId>()

  actual fun contains(element: MessageId): Boolean = set.contains(element)
  actual fun add(element: MessageId): Boolean = set.add(element)
  actual fun clear() = set.clear()
}