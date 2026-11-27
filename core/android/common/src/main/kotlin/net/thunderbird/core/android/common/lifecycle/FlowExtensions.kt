package net.thunderbird.core.android.common.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.launch

/**
 * Collects this flow while [lifecycleOwner] is at least [Lifecycle.State.STARTED].
 *
 * @return the [Job] of the collection, to cancel it before [lifecycleOwner] is destroyed.
 */
fun <T> Flow<T>.observe(lifecycleOwner: LifecycleOwner, collector: FlowCollector<T>): Job =
    lifecycleOwner.lifecycleScope.launch {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            collect(collector)
        }
    }
