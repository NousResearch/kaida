package llms

import kotlinx.coroutines.time.delay
import java.time.Duration
import kotlin.math.roundToLong

// TODO: include stack traces?
class ExceededRetryAttemptsException(val attempts: List<Throwable>) : Exception(
	"Exceptions follow:\n\n" + attempts.withIndex().joinToString("\n") {
		return@joinToString "${it.index + 1}: ${it.value.toString()}"
	} + "\n"
)

suspend fun <T> RetryPolicy?.maybeRetry(block: suspend () -> T): T {
	return if(this != null)
		this.retry(block)
	else
		block()
}

suspend fun <T> RetryPolicy?.maybeControlledRetry(init: ControlledRetryScopeBuilder<T>.() -> Unit): T {
	return if(this != null)
		this.controlledRetry(init)
	else
		ControlledRetryScopeBuilder<T>().apply(init).build().executeBlock()
}

data class ControlledRetryScope<T>(
	val executeBlock: suspend () -> T,
	val onFailure: RetryPolicyFilter? = null,
)

class ControlledRetryScopeBuilder<T>() {
	private var onFailure: RetryPolicyFilter? = null
	private var executeBlock: (suspend () -> T)? = null

	/***
	 * return True if this exception should be thrown, or False if retried (up to retry limit)
	 */
	fun onFailControlled(block: RetryPolicyFilter) {
		onFailure = block
	}

	fun onFail(block: suspend (rp: RetryPolicy, state: CurrentRetryState, ex: Throwable) -> Unit) {
		onFailure = { rp, state, ex ->
			block(rp, state, ex)
			false
		}
	}

	fun execute(block: suspend () -> T) {
		executeBlock = block
	}

	fun build(): ControlledRetryScope<T> {
		require(executeBlock != null) {"execute block must be defined in a ControlledRetryScope"}
		return ControlledRetryScope(
			executeBlock!!,
			onFailure,
		)
	}
}

data class CurrentRetryState(
	val currentAttempt: Int,
	val currentDelay: Duration,
	val failures: List<Throwable>,
)

typealias RetryPolicyFilter = suspend (rp: RetryPolicy, state: CurrentRetryState, ex: Throwable) -> Boolean

data class RetryPolicy(
	val maxAttempts: Int = 3,
	val initialDelay: Duration = Duration.ofSeconds(1),
	val backoffMultiplier: Double = 2.0,
	val shouldRetryForException: RetryPolicyFilter? = null,
) {
	suspend fun <T> retry(
		block: suspend () -> T
	): T {
		return this.controlledRetry {
			execute {
				return@execute block()
			}
		}
	}

	suspend fun <T> controlledRetry(init: ControlledRetryScopeBuilder<T>.() -> Unit): T {
		val scope = ControlledRetryScopeBuilder<T>().apply(init).build()
		val onFailureHook = this::shouldRetryForException.get()
		val executeBlock = scope.executeBlock

		var state = CurrentRetryState(1, initialDelay, listOf())

		while (state.currentAttempt <= maxAttempts) {
			try {
				return executeBlock()
			} catch (ex: Throwable) {
				if (onFailureHook != null && onFailureHook(this, state, ex)) {
					scope.onFailure?.invoke(this, state, ex)
					val delay = state.currentDelay
					state = state.copy(
						currentAttempt=state.currentAttempt + 1,
						currentDelay=Duration.ofMillis((delay.toMillis() * backoffMultiplier).roundToLong()),
						failures=state.failures + listOf(ex)
					)
					delay(delay)
				} else {
					throw ex
				}
			}
		}

		throw ExceededRetryAttemptsException(state.failures)
	}
}
