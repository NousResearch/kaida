package pipelinedag

import kotlinx.serialization.StringFormat
import java.util.Objects
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlin.reflect.KType
import kotlin.reflect.typeOf

class PipelineVariableDelegate<T : Any>(
	private val pv: PipelineVariables,
	private val type: KType,
	private val transient: Boolean
) {
	@Suppress("unused")
	operator fun getValue(thisRef: Any?, property: KProperty<*>): Key<T> {
		return Key.forPV(pv, property.name, type, transient)
	}
}

@OptIn(ExperimentalStdlibApi::class)
inline fun <reified T : Any> pipelineDelegate(
	pv: PipelineVariables,
	transient: Boolean
): PipelineVariableDelegate<T> {
	return PipelineVariableDelegate(pv, typeOf<T>(), transient)
}

fun KType.fullyQualifiedType(): String {
	val baseName =
		when (val classifier = this.classifier) {
			is KClass<*> -> classifier.qualifiedName ?: classifier.toString()
			else -> classifier.toString()
		}

	val generics =
		if (arguments.isNotEmpty()) {
			arguments.joinToString(prefix = "<", postfix = ">") { arg ->
				arg.type?.fullyQualifiedType() ?: "*"
			}
		} else
			""

	return baseName + generics + if (isMarkedNullable) "?" else ""
}

/**
 * all of the complicated deserialization stuff results from the desire to reload pipeline variables ahead
 * of time. this could be avoided if we tried to load each variable's value on-demand, because at the
 * use site for some variable the `Key<T>` is still reified, and therefore can be deserialized.
 *
 * however, if we want to load a `List<Key<*>>` ("load every variable for this PipelineVariables object")
 * then we're iterating a set of type erased `Key<*>`, and have to do some magic to deserialize a
 * `Key<*>` -> `Key<T>`
 *
 * specifically, we must explicitly specify a deserializer for each variable (which we can derive automatically
 * in virtually every case at instantiation time), and we must make an unchecked cast from `Any?` to `T?`
 *
 * we can do that unchecked cast because the underlying value in the storage engine is guaranteed to be
 * correct so long as we're reloading the exact same structure. we ensure that using `PipelineVariables.structuralHash`
 *
 * the structural hash prevents the case where a `PipelineVariables` field defined as
 * `val whatever by list<String>()` is later changed to `list<Int>()` and causes a `String` to be deserialized as `Int`
 */
typealias DeserializationFunction = (value: String, strategy: StringFormat) -> Any?

/**
 * the root container for pipeline variables. ALL variables MUST ultimately use
 * `Key.forPV(...)`
 */
abstract class PipelineVariables {
	private val keysMutable = mutableSetOf<Key<*>>()
	val keys: Set<Key<*>> = mutableSetOf()
	private val deserializersMutable = mutableMapOf<Key<*>, DeserializationFunction>()
	val deserializers: Map<Key<*>, DeserializationFunction> = deserializersMutable

	abstract val inputs: InputConfiguration
	abstract val outputs: OutputConfiguration

	internal fun __addToKeysMutable(key: Key<*>, deserializer: DeserializationFunction) {
		keysMutable.add(key)
		deserializersMutable[key] = deserializer
	}

	/**
	 * transient defaults to true for custom types, watch out if you need to serialize!
	 *
	 * remember your type must have a KSerializer defined if it's not transient
	 */
	protected inline fun <reified T : Any> type(transient: Boolean = true) =
		pipelineDelegate<T>(this, transient)
	protected fun string(transient: Boolean = false) = type<String>(transient)
	protected fun int(transient: Boolean = false) = type<Int>(transient)
	protected fun boolean(transient: Boolean = false) = type<Boolean>(transient)
	protected inline fun <reified T : Any> list(transient: Boolean = false) = type<List<T>>(transient)
	protected inline fun <reified T : Any> set(transient: Boolean = false) = type<Set<T>>(transient)
	protected inline fun <reified K : Any, reified V : Any> map(transient: Boolean = false) = type<Map<K, V>>(transient)

	/**
	 * a hash that will tell us whether any variables have been added or removed, or their
	 * types have changed including generics
	 */
	fun structuralHash(includeTransients: Boolean = false): Int {
		return Objects.hash(
			keys.toList()
				.sortedBy { it.name }
				.run {
					if(includeTransients)
						this
					else
						this.filter { it.transient == false }
				}
				.flatMap {
					listOf(
						it.name,
						it.type.fullyQualifiedType(),
						it.transient.toString(),
					)
				}
		)
	}

	protected fun PipelineVariables.inputs(block: PVInputBuilder.() -> Unit): InputConfiguration {
		return PVInputBuilder().run {
			this.block()
			this.build()
		}
	}

	protected fun PipelineVariables.outputs(block: PVOutputBuilder.() -> Unit): OutputConfiguration {
		return PVOutputBuilder().run {
			this.block()
			this.build()
		}
	}
}

/**
 * see `Key.forPV` for default deserializer definition
 */
fun Key<*>.deserializer() = this.owner.deserializers.getValue(this)