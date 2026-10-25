@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package com.lucasalfare.flrpg.communicationprotocol

actual class PlatformLock {
  actual inline fun <T> lock(block: () -> T): T {
    return block()
  }
}

actual class ThreadSafeMessageIdSet {
  private val set = mutableSetOf<MessageId>()

  actual fun contains(element: MessageId): Boolean = set.contains(element)
  actual fun add(element: MessageId): Boolean = set.add(element)
  actual fun clear() = set.clear()
}