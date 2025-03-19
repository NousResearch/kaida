package pipelinedag

import kotlin.collections.set

data class IllegalVariableAccess(val key: Key<*>) :
	Exception("Tried to access undeclared pipeline variable: ${key.name}")

data class IllegalVariableSet(val key: Key<*>) :
	Exception("Tried to set undeclared pipeline variable: ${key.name}")

interface IPipelineContext {
	fun <T : Any> get(key: Key<T>): T
	fun <T : Any> getOrNull(key: Key<T>): T?
	fun <T : Any> exists(key: Key<T>): Boolean
	fun asMap(): Map<String, Any?>

	fun <T : Any> Key<T>.value(): T = get(this)
	fun <T : Any> Key<T>.valueOrNull(): T? = getOrNull(this)

	fun asTypedMap(): Map<Key<*>, Any?>
	fun copy(): IPipelineContext

	fun keys() = asTypedMap().keys
}

class PipelineContext(
	private val store: Map<Key<*>, Any?> = mapOf(),
) : IPipelineContext {

	@Suppress("UNCHECKED_CAST")
	override fun <T : Any> get(key: Key<T>): T = getOrNull(key) ?:
		throw NoSuchElementException("No value found for key: ${key.name}")

	@Suppress("UNCHECKED_CAST")
	override fun <T : Any> getOrNull(key: Key<T>): T? = store[key] as? T

	override fun <T : Any> exists(key: Key<T>): Boolean = store.containsKey(key)

	override fun asMap(): Map<String, Any?> = store.map { it.key.name to it.value }.toMap()
	override fun asTypedMap(): Map<Key<*>, Any?> = store
	override fun copy(): IPipelineContext = PipelineContext(asTypedMap())

	fun mutate(block: PipelineContextMutableView.() -> Unit): PipelineContext {
		val mutable = PipelineContextMutableView(this)
		mutable.block()
		return mutable.freeze()
	}
}

data class ContextValueWithSource<T : Any>(val value: T?, val source: ContextValueSource?)
sealed class ContextValueSource {
	data class StepSource(val step: Step, val hash: StepInputHash) : ContextValueSource()

	fun name(): String {
		return when(this) {
			is StepSource -> step.name
		}
	}
}

class PipelineContextSourceTracked(
	private val storeMutable: MutableMap<Key<*>, ContextValueWithSource<*>> = mutableMapOf()
) : IPipelineContext {
	val store: Map<Key<*>, ContextValueWithSource<*>> = storeMutable

	fun <T : Any> set(key: Key<T>, value: T, source: ContextValueSource?) {
		storeMutable[key] = ContextValueWithSource(value, source)
	}

	fun set(keyed: KeyedValue<*>, source: ContextValueSource?) {
		storeMutable[keyed.key] = ContextValueWithSource(keyed.value, source)
	}

	fun <T : Any> remove(key: Key<T>) {
		storeMutable.remove(key)
	}

	override fun <T : Any> get(key: Key<T>): T = getOrNull(key) ?:
	throw NoSuchElementException("No value found for key: ${key.name}")

	@Suppress("UNCHECKED_CAST")
	override fun <T : Any> getOrNull(key: Key<T>): T? = storeMutable[key]?.value as? T

	fun <T : Any> getTracked(key: Key<T>): ContextValueWithSource<T> = getTrackedOrNull(key) ?:
		throw NoSuchElementException("No value found for key: ${key.name}")

	@Suppress("UNCHECKED_CAST")
	fun <T : Any> getTrackedOrNull(key: Key<T>): ContextValueWithSource<T>? = storeMutable[key] as? ContextValueWithSource<T>

	override fun <T : Any> exists(key: Key<T>): Boolean = storeMutable.containsKey(key)

	override fun asMap(): Map<String, Any?> = storeMutable.map { it.key.name to it.value.value }.toMap()
	override fun asTypedMap(): Map<Key<*>, Any?> = storeMutable.map { it.key to it.value.value }.toMap()
	override fun copy(): IPipelineContext = PipelineContextSourceTracked(store.toMutableMap())

	/**
	 * removes all output variables whose input variables no longer hash to the hash that set that output variable
	 */
	fun invalidate(pipeline: Pipeline) {
		for(varkey in pipeline.allVariables()) {
			val tracked = this.getTrackedOrNull(varkey) ?: continue

			when(tracked.source) {
				null -> continue
				is ContextValueSource.StepSource -> {
					val step = tracked.source.step
					// might need to cache this if we ever use a more expensive hash
					// in principle we could be reloading hundreds of variables here
					if(step.hashInputs(this) == tracked.source.hash)
						continue
				}
			}

			// TODO: logger should be some kind of context available to everything during a pipeline run
			//       like react context, and it has flags for different things like this
//			println("invalidated: ${varkey.name}")
			this.remove(varkey)
		}
	}

	companion object {
		fun from(src: IPipelineContext): PipelineContextSourceTracked {
			if(src is PipelineContextSourceTracked)
				return src.copy() as PipelineContextSourceTracked

			return PipelineContextSourceTracked(
				src.asTypedMap()
					.map { it.key to ContextValueWithSource(it.value, null) }
					.toMap()
					.toMutableMap()
			)
		}
	}
}

data class KeyedValue<T : Any>(val key: Key<T>, val value: T?)

class PipelineContextMutableView(
	private val base: IPipelineContext,
	private val allowedGetters: Set<Key<*>>? = null,
	private val allowedSetters: Set<Key<*>>? = null
) : IPipelineContext {
	private val pendingChanges: MutableMap<Key<*>, KeyedValue<*>> = mutableMapOf()

	fun <T : Any> set(key: Key<T>, value: T) {
		if(allowedSetters != null && !allowedSetters.contains(key))
			throw IllegalVariableSet(key)

		pendingChanges[key] = KeyedValue(key, value)
	}

	fun <T : Any> remove(key: Key<T>) {
		if(allowedSetters != null && !allowedSetters.contains(key))
			throw IllegalVariableSet(key)

		pendingChanges.remove(key)
	}

	override fun <T : Any> get(key: Key<T>): T = getOrNull(key)
		?: throw NoSuchElementException("No value found for key: ${key.name}")

	override fun <T : Any> getOrNull(key: Key<T>): T? {
		if(allowedGetters != null && !allowedGetters.contains(key))
			throw IllegalVariableAccess(key)

		@Suppress("UNCHECKED_CAST")
		return pendingChanges[key]?.let { it as? T }
			?: base.getOrNull(key)
	}

	// does this need to check restrictedgetters? I can't see the harm in an exists call really
	override fun <T : Any> exists(key: Key<T>): Boolean = pendingChanges.containsKey(key) || base.exists(key)

	override fun asMap(): Map<String, Any?> = base.asMap().plus(pendingChanges.map { it.key.name to it.value.value })
	override fun asTypedMap(): Map<Key<*>, Any?> = base.asTypedMap().plus(pendingChanges.map { it.key to it.value.value })
	override fun copy(): IPipelineContext = PipelineContextMutableView(freeze())

	fun freeze() = PipelineContext(asTypedMap())

	fun pending() = pendingChanges.values

	fun freezeTracked(source: ContextValueSource? = null): PipelineContextSourceTracked {
		val ret = PipelineContextSourceTracked.from(base)
		for(entry in this.pending()) {
			ret.set(entry, source)
		}
		return ret
	}
}