package pipelinedag

// TODO: these just let us *express* invariants, they aren't tested at runtime yet
data class InputConfiguration(
	val disallowExtraKeys: Boolean,
	val emptyAllowed: Boolean,
	val options: List<Option>
) {
	fun validate() {
		options.forEachIndexed { index, option ->
			try {
				option.validate()
			} catch (e: IllegalStateException) {
				throw IllegalStateException("Option #$index is invalid: ${e.message}", e)
			}
		}
	}
}

data class Option(val constraints: List<InputConstraint>) {
	fun validate() {
		val requiredKeys = mutableSetOf<Key<*>>()
		val forbiddenKeys = mutableSetOf<Key<*>>()

		// collect required and forbidden keys recursively
		fun collect(constraints: List<InputConstraint>) {
			for (constraint in constraints) {
				when (constraint) {
					is Required -> requiredKeys.addAll(constraint.keys)
					is Forbidden -> forbiddenKeys.addAll(constraint.keys)
					is Conditional -> collect(constraint.constraints)

					// cardinality is checked below
					is AtLeastOneOf -> {}
					is ExactlyOneOf -> {}
					is AtMostOneOf -> {}
				}
			}
		}
		collect(constraints)

		val conflicts = requiredKeys.intersect(forbiddenKeys)
		if (conflicts.isNotEmpty())
			throw IllegalStateException("The following keys are both required and forbidden: $conflicts")

		fun validateCardinality(constraint: InputConstraint) {
			when (constraint) {
				is AtLeastOneOf -> {
					val forbiddenIntersection = constraint.keys.intersect(forbiddenKeys)
					if (forbiddenIntersection.isNotEmpty())
						throw IllegalStateException("AtLeastOneOf constraint contains keys that were forbidden: $forbiddenIntersection")
				}
				is ExactlyOneOf -> {
					val forbiddenIntersection = constraint.keys.intersect(forbiddenKeys)
					if (forbiddenIntersection.isNotEmpty())
						throw IllegalStateException("ExactlyOneOf constraint contains keys that were forbidden: $forbiddenIntersection")

					val reqIntersection = constraint.keys.intersect(requiredKeys)
					if (reqIntersection.size > 1)
						throw IllegalStateException("ExactlyOneOf constraint forces more than one key: $reqIntersection")
				}
				is AtMostOneOf -> {
					val forbiddenIntersection = constraint.keys.intersect(forbiddenKeys)
					if (forbiddenIntersection.isNotEmpty())
						throw IllegalStateException("AtMostOneOf constraint contains keys that were forbidden: $forbiddenIntersection")

					val reqIntersection = constraint.keys.intersect(requiredKeys)
					if (reqIntersection.size > 1)
						throw IllegalStateException("AtMostOneOf constraint forces more than one key: $reqIntersection")
				}
				// validate recursively
				is Conditional -> {
					constraint.constraints.forEach { validateCardinality(it) }
				}
				// we collected non-cardinality types earlier
				else -> {}
			}
		}

		// Walk through all constraints in the option and check cardinality.
		fun traverse(constraints: List<InputConstraint>) {
			for (c in constraints) {
				validateCardinality(c)
				if (c is Conditional) {
					traverse(c.constraints)
				}
			}
		}
		traverse(constraints)
	}
}

sealed class InputConstraint
data class Required(val keys: List<Key<*>>) : InputConstraint()
data class Forbidden(val keys: List<Key<*>>) : InputConstraint()
data class AtLeastOneOf(val keys: List<Key<*>>) : InputConstraint()
data class ExactlyOneOf(val keys: List<Key<*>>) : InputConstraint()
data class AtMostOneOf(val keys: List<Key<*>>) : InputConstraint()
data class Conditional(val condition: Condition, val constraints: List<InputConstraint>) : InputConstraint()

sealed class Condition
data class IfMissingAny(val keys: List<Key<*>>) : Condition()
data class IfProvided(val keys: List<Key<*>>) : Condition()

@DslMarker
annotation class InputConfigurationDsl

@InputConfigurationDsl
class PVOptionBuilder {
	private val constraints = mutableListOf<InputConstraint>()

	fun required(vararg keys: Key<*>) {
		constraints.add(Required(keys.toList()))
	}

	fun forbidden(vararg keys: Key<*>) {
		constraints.add(Forbidden(keys.toList()))
	}

	fun atLeastOneOf(vararg keys: Key<*>) {
		constraints.add(AtLeastOneOf(keys.toList()))
	}

	fun exactlyOneOf(vararg keys: Key<*>) {
		constraints.add(ExactlyOneOf(keys.toList()))
	}

	fun atMostOneOf(vararg keys: Key<*>) {
		constraints.add(AtMostOneOf(keys.toList()))
	}

	fun ifMissingAnyOf(vararg keys: Key<*>, block: PVOptionBuilder.() -> Unit) {
		val nested = PVOptionBuilder().apply(block)
		constraints.add(Conditional(IfMissingAny(keys.toList()), nested.buildConstraints()))
	}

	fun ifAnyPresent(vararg keys: Key<*>, block: PVOptionBuilder.() -> Unit) {
		val nested = PVOptionBuilder().apply(block)
		constraints.add(Conditional(IfProvided(keys.toList()), nested.buildConstraints()))
	}

	internal fun buildConstraints(): List<InputConstraint> = constraints.toList()

	fun build(): Option = Option(buildConstraints())
}

@InputConfigurationDsl
class PVInputBuilder {
	private var emptyAllowed: Boolean = false
	private var disallowExtraKeys: Boolean = false
	private val options = mutableListOf<Option>()

	fun allowEmpty() {
		emptyAllowed = true
	}

	fun disallowExtraKeys() {
		disallowExtraKeys = true
	}

	/**
	 * a simple set of keys, which, when all present, constitutes a valid input set
	 */
	fun option(vararg keys: Key<*>) {
		options.add(Option(keys.map { Required(listOf(it)) }))
	}

	/**
	 * a more complex rule which may have multiple invariants
	 */
	fun option(block: PVOptionBuilder.() -> Unit) {
		val builder = PVOptionBuilder().apply(block)
		options.add(builder.build())
	}

	fun build() = InputConfiguration(
		disallowExtraKeys = disallowExtraKeys,
		emptyAllowed = emptyAllowed,
		options = options
	)
}


data class OutputConfiguration(val options: List<Option>)

@DslMarker
annotation class OutputConfigurationDsl

@OutputConfigurationDsl
class PVOutputBuilder {
	private var options = mutableListOf<Option>()

	/**
	 * a simple set of keys, which, when all present, constitutes a valid input set
	 */
	fun option(vararg keys: Key<*>) {
		options.add(Option(keys.map { Required(listOf(it)) }))
	}

	/**
	 * a more complex rule which may have multiple invariants
	 */
	fun option(block: PVOptionBuilder.() -> Unit) {
		val builder = PVOptionBuilder().apply(block)
		options.add(builder.build())
	}

	fun build() = OutputConfiguration(options)
}