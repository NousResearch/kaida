package pipelinedag

import kotlinx.serialization.StringFormat
import kotlinx.serialization.serializer
import llms.CurrentRetryState
import llms.RetryPolicy
import llms.maybeControlledRetry
import java.util.Objects
import kotlin.reflect.KType

/**
 * a generic type wrapper used to make all pipeline variable access strongly typed, as T is preserved at
 * compile time by `Key`s being bound to variables via `PipelineVariableDelegate`
 *
 * @property name the name of this variable
 * @property type the KType for the underlying generic T of this key
 * @property transient whether this variable should be stored after step execution. transient keys
 *                     are never serialized. ALL transient=false keys must have KSerializer registered for <T>
 */
@ConsistentCopyVisibility
data class Key<T : Any> private constructor(
	val name: String,
	val type: KType,
	val transient: Boolean=false,
	/**
	 * 	this cyclical reference is unfortunate, but this is what ultimately allows us to break the dependency of
	 * 	one pipeline = one variable container. for a large pipeline it's nice to allow us to explicitly model
	 * 	dependencies between different steps by keeping variables in separate containers
	 *
	 * 	ultimately the serialization code must be able to retrieve the owning `PipelineVariables` for some given
	 * 	`Key<*>`, and this is the most convenient way for us to access it
	 */
	val owner: PipelineVariables
) {
	/**
	 * may throw at runtime if given an invalid type
	 *
	 * we can't reify the type of `value`, it's retrieved from
	 * our storage engine which means any generic `List<X<Y>>`
	 * is erased to `List<*>` at runtime. thus, we can't test
	 * for correctness even with reflection
	 *
	 * however, we can trust the value will be correct because
	 * this shouldn't ever be called unless a `PipelineVariable`'s
	 * `structuralHash` matches
	 */
	@Suppress("UNCHECKED_CAST")
	fun castValue(value: Any?): T? = value as? T

	companion object {
		fun <T : Any> forPV(
			pv: PipelineVariables,
			name: String,
			type: KType,
			transient: Boolean=false,
			deserializer: DeserializationFunction? = null,
		) : Key<T> {
			val ret = Key<T>(name,type,transient,pv)
			pv.__addToKeysMutable(
				ret,
				deserializer ?:
					fun(value: String, strategy: StringFormat): Any? {
						return strategy.decodeFromString(serializer(type), value)
					}
			)
			return ret
		}
	}
}

typealias StepInputHash = Int

typealias PipelineTransitionHook = (
	pipeline: Pipeline,
	ctx: PipelineContextSourceTracked,
) -> Unit

typealias StepBeforeHook = (
	pipeline: Pipeline,
	step: Step,
	ctx: PipelineContextSourceTracked,
	skipped: Boolean,
) -> Unit

typealias StepExecutionHook = (
	pipeline: Pipeline,
	step: Step,
	ctx: PipelineContextSourceTracked,
) -> Unit


typealias StepFailureHook = (
	pipeline: Pipeline,
	step: Step,
	ctx: PipelineContextSourceTracked,
	rp: RetryPolicy,
	state: CurrentRetryState,
	exception: Throwable,
) -> Unit

data class PipelineHooks(
	val beforeExecution: List<PipelineTransitionHook>? = null,
	val beforeEachStep: List<StepBeforeHook>? = null,
	val afterEachStep: List<StepExecutionHook>? = null,
	val onStepFailure: List<StepFailureHook>? = null,
	val afterExecution: List<PipelineTransitionHook>? = null,
)

class Step(
	val name: String,
	val consumes: Set<Key<*>>,
	val produces: Set<Key<*>>,
	val action: suspend (PipelineContextMutableView) -> Unit
) {
	/**
	 * used for partial invalidation. each produces value is stored after step execution, alongside
	 * the input hash of this step at the time. in future runs, we will invalidate produced values
	 * at load time if the hash doesn't match
	 */
	fun hashInputs(ctx: IPipelineContext): StepInputHash = Objects.hash(consumes.map { ctx.getOrNull(it) })
}

// TODO: pipeline variables set to 'null' explicitly are ill-considered, need to be tested
// TODO: I think maybe pipelines should have their own top level inputs and outputs, separately from all intermediates
//       that would allow us to know which steps in the pipeline are
//       "input generators": steps which can produce an input if not provided (UserInteraction)
//       "input acceptors": steps which accept an input (from the initial ctx or a generator)
//       "intermediate steps": steps which consume and produce non-terminal values
//       "terminal steps": steps which produce terminal values
//       we should also define what state "terminal" actually is, like, is it every output variable? one of several?
class Pipeline(
	val id: String,
	val steps: List<Step>,
	val rp: RetryPolicy? = null,
) {
	fun allVariables(produces: Boolean=true): Set<Key<*>> =
		steps.flatMap { it.consumes + (if(produces) it.produces else setOf()) }.toSet()

	suspend fun execute(
		ctx: IPipelineContext = PipelineContext(),
		hooks: PipelineHooks? = null,
	): IPipelineContext = executeTracked(ctx, hooks)

	suspend fun executeTracked(
		ctx: IPipelineContext = PipelineContext(),
		hooks: PipelineHooks? = null,
	): PipelineContextSourceTracked {
		// TODO: should we cache this?
		val sortedSteps = kahnSort(steps)
		var ctx = PipelineContextSourceTracked.from(ctx)
		// TODO: invalidation is completely untested
		ctx.invalidate(this)

		hooks?.beforeExecution?.forEach { hook ->
			// TODO: use an immutable ctx here instead of recreating
			hook(this, PipelineContextSourceTracked.from(ctx))
		}

		for (step in sortedSteps) {
			// TODO: add support for producesOverwriting(...)? as in, a value might exist
			//   but even so we will run and replace it
			// TODO: consider the case where *some* of a step's outputs are already produced
			//   but not all of them. so the step still needs to run but maybe SHOULD or SHOULD NOT (depending)
			//   overwrite the other values that are already produced? also that means they might consume those values?
			//   this sounds complicated. do we just enforce that our graphs are more fine grained than that? like
			//   maybe that situation indicates a code smell where your step should be split into two using an
			//   intermediate variable or similar?
			val skip = step.produces.all { ctx.getOrNull(it) != null };

			hooks?.beforeEachStep?.forEach { hook ->
				// TODO: use an immutable ctx here instead of recreating
				hook(this, step, PipelineContextSourceTracked.from(ctx), skip)
			}

			if(skip)
				continue

			// TODO: if any of our consumes aren't set do we throw here? what if another step produces our value
			//  and actually *can* run?

			var mutable = PipelineContextMutableView(ctx, step.consumes, step.produces)

			rp.maybeControlledRetry {
				onFail { crp, state, ex ->
					// on fail we rollback ctx
					mutable = PipelineContextMutableView(ctx, step.consumes, step.produces)

					hooks?.onStepFailure?.forEach { hook ->
						// TODO: use an immutable ctx here instead of recreating
						hook(
							this@Pipeline,
							step,
							PipelineContextSourceTracked.from(ctx),
							crp,
							state,
							ex
						)
					}
				}

				execute {
					step.action(mutable)
				}
			}

			hooks?.afterEachStep?.forEach { hook ->
				// TODO: use an immutable ctx here instead of recreating
				hook(this, step, PipelineContextSourceTracked.from(ctx))
			}

			val result = mutable.freeze()

			val missing = step.produces.filter { result.exists(it) == false }
			if(missing.isNotEmpty())
				error("Step $step failed to produce expected values: ${missing.joinToString(", ") {it.name}}")

			val stepHash = step.hashInputs(ctx)

			for(entry in mutable.pending()) {
				ctx.set(entry, ContextValueSource.StepSource(step, stepHash))
			}

			hooks?.afterEachStep?.forEach { hook ->
				// TODO: immutable version of sourcetracked to protect from mutability
				hook.invoke(this, step, PipelineContextSourceTracked.from(ctx))
			}
		}

		hooks?.afterExecution?.forEach { hook ->
			// TODO: use an immutable ctx here instead of recreating
			hook(this, PipelineContextSourceTracked.from(ctx))
		}

		return ctx
	}
}

// TODO: unit test thoroughly
fun kahnSort(allSteps: List<Step>): List<Step> {
	val producesMap = mutableMapOf<Key<*>, MutableList<Step>>()
	for (step in allSteps) {
		for (outKey in step.produces) {
			producesMap.getOrPut(outKey) { mutableListOf() }.add(step)
		}
	}

	val incomingEdgesCount = mutableMapOf<Step, Int>()
	for (step in allSteps) incomingEdgesCount[step] = 0

	for (step in allSteps) {
		for (neededKey in step.consumes) {
			val producers = producesMap[neededKey] ?: emptyList()
			for (producerStep in producers) {
				if (producerStep != step) {
					incomingEdgesCount[step] = incomingEdgesCount[step]!! + 1
				}
			}
		}
	}

	val queue = ArrayDeque<Step>(allSteps.filter { incomingEdgesCount[it] == 0 })
	val result = mutableListOf<Step>()

	while (queue.isNotEmpty()) {
		val current = queue.removeFirst()
		result += current

		for (step in allSteps) {
			if (step == current)
				continue

			if (step.consumes.intersect(current.produces).isNotEmpty()) {
				val oldCount = incomingEdgesCount[step]!!
				val newCount = oldCount - 1
				incomingEdgesCount[step] = newCount
				if (newCount == 0) {
					queue.addLast(step)
				}
			}
		}
	}

	if (result.size < allSteps.size)
		error("Cycle detected in pipeline steps!")

	return result
}

fun countStepsToTerminal(
	allSteps: List<Step>,
	startingKeys: Set<Key<*>>,
	outputConfig: OutputConfiguration,
	skipSatisfiedSteps: Boolean = true,
): Int {
	fun isConstraintSatisfied(available: Set<Key<*>>, constraint: InputConstraint): Boolean {
		return when (constraint) {
			is Required -> constraint.keys.all { it in available }
			is Forbidden -> constraint.keys.none { it in available }
			is AtLeastOneOf -> constraint.keys.any { it in available }
			is ExactlyOneOf -> constraint.keys.count { it in available } == 1
			is AtMostOneOf -> constraint.keys.count { it in available } <= 1
			is Conditional -> {
				val conditionActive = when (constraint.condition) {
					is IfMissingAny -> constraint.condition.keys.any { it !in available }
					is IfProvided -> constraint.condition.keys.any { it in available }
				}
				if (conditionActive) {
					constraint.constraints.all { isConstraintSatisfied(available, it) }
				} else {
					true
				}
			}
		}
	}

	fun isOptionSatisfied(available: Set<Key<*>>, option: Option): Boolean =
		option.constraints.all { isConstraintSatisfied(available, it) }

	fun isTerminalState(available: Set<Key<*>>): Boolean = outputConfig.options.any { isOptionSatisfied(available, it) }

	val availableKeys = startingKeys.toMutableSet()
	val executedSteps = mutableSetOf<Step>()
	var count = 0

	while (!isTerminalState(availableKeys)) {
		val nextStep = allSteps.firstOrNull { step ->
			(skipSatisfiedSteps == false || !(step.produces.all { it in availableKeys })) &&
					step !in executedSteps && step.consumes.all { it in availableKeys }
		}

		if (nextStep == null)
			error("Pipeline cannot complete: no executable step found and terminal state not reached.")

		executedSteps.add(nextStep)
		availableKeys.addAll(nextStep.produces)
		count++
	}

	return count
}