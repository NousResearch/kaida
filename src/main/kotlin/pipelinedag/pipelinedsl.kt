package pipelinedag

import llms.CurrentRetryState
import llms.RetryPolicy

// TODO: we don't use a dslmarker because pipelinebuilder's pv and completion SHOULD be in scope for a stepbuilder
//   this is not solveable without either using context parameters (haven't landed in K2 yet)
//   or refactoring the PipelineBuilder class to support arbitrary Step subtypes (lots of deeply nested generics)
@DslMarker
annotation class PipelineDSL

//@PipelineDSL
@Suppress("unused")
open class PipelineBuilder(val id: String) {
	private val steps = mutableListOf<Step>()
	private var rp: RetryPolicy? = null

	fun step(name: String, block: StepBuilder.() -> Unit) {
		require(steps.all {it.name != name}) {"step with duplicate name: $name"}

		val dsl = StepBuilder(name)
		dsl.block()
		steps += dsl.build()
	}

	fun retryPolicy(rp: RetryPolicy?) {
		this.rp = rp
	}

	fun build(): Pipeline = Pipeline(id, steps, rp)
}

@Suppress("unused")
fun pipeline(id: String, block: PipelineBuilder.() -> Unit): Pipeline {
	val builder = PipelineBuilder(id)
	builder.block()
	return builder.build()
}

//@PipelineDSL
class StepBuilder(private val name: String) {
	private val consumeKeys = mutableSetOf<Key<*>>()
	private val produceKeys = mutableSetOf<Key<*>>()
	private var action: suspend (PipelineContextMutableView) -> Unit = {}

	fun consumes(vararg keys: Key<*>) {
		consumeKeys += keys
	}

	fun produces(vararg keys: Key<*>) {
		produceKeys += keys
	}

	fun execute(block: suspend PipelineContextMutableView.() -> Unit) {
		action = block
	}

	fun build(): Step = Step(
		name = name,
		consumes = consumeKeys,
		produces = produceKeys,
		action = action
	)
}

@DslMarker
annotation class PipelineHooksDsl

@PipelineHooksDsl
class PipelineTransitionHookReceiver(
	val pipeline: Pipeline,
	val ctx: PipelineContextSourceTracked,
)

@PipelineHooksDsl
class StepBeforeHookReceiver(
	val pipeline: Pipeline,
	val step: Step,
	val ctx: PipelineContextSourceTracked,
	val skipped: Boolean,
)

@PipelineHooksDsl
class StepExecutionHookReceiver(
	val pipeline: Pipeline,
	val step: Step,
	val ctx: PipelineContextSourceTracked,
)

@PipelineHooksDsl
class StepFailureHookReceiver(
	val pipeline: Pipeline,
	val step: Step,
	val ctx: PipelineContextSourceTracked,
	val rp: RetryPolicy,
	val currentRetryState: CurrentRetryState,
	val exception: Throwable,
)

@PipelineHooksDsl
class PipelineHooksBuilder {
	private val beforeExecutionHooks = mutableListOf<PipelineTransitionHook>()
	private val beforeEachStepHooks = mutableListOf<StepBeforeHook>()
	private val afterEachStepHooks = mutableListOf<StepExecutionHook>()
	private val onStepFailureHooks = mutableListOf<StepFailureHook>()
	private val afterExecutionHooks = mutableListOf<PipelineTransitionHook>()

	fun beforeExecution(block: PipelineTransitionHookReceiver.() -> Unit) {
		val wrapper: PipelineTransitionHook = { pipeline, ctx ->
			PipelineTransitionHookReceiver(pipeline, ctx).block()
		}
		beforeExecutionHooks.add(wrapper)
	}

	fun beforeEachStep(block: StepBeforeHookReceiver.() -> Unit) {
		val wrapper: StepBeforeHook = { pipeline, step, ctx, skipped ->
			StepBeforeHookReceiver(pipeline, step, ctx, skipped).block()
		}
		beforeEachStepHooks.add(wrapper)
	}

	fun afterEachStep(block: StepExecutionHookReceiver.() -> Unit) {
		val wrapper: StepExecutionHook = { pipeline, step, ctx ->
			StepExecutionHookReceiver(pipeline, step, ctx).block()
		}
		afterEachStepHooks.add(wrapper)
	}

	fun onStepFailure(block: StepFailureHookReceiver.() -> Unit) {
		val wrapper: StepFailureHook = { pipeline, step, ctx, rp, state, exception ->
			StepFailureHookReceiver(pipeline, step, ctx, rp, state, exception).block()
		}
		onStepFailureHooks.add(wrapper)
	}

	fun afterExecution(block: PipelineTransitionHookReceiver.() -> Unit) {
		val wrapper: PipelineTransitionHook = { pipeline, ctx ->
			PipelineTransitionHookReceiver(pipeline, ctx).block()
		}
		afterExecutionHooks.add(wrapper)
	}

	fun build(): PipelineHooks = PipelineHooks(
		beforeExecution = beforeExecutionHooks.takeIf { it.isNotEmpty() },
		beforeEachStep = beforeEachStepHooks.takeIf { it.isNotEmpty() },
		afterEachStep = afterEachStepHooks.takeIf { it.isNotEmpty() },
		onStepFailure = onStepFailureHooks.takeIf { it.isNotEmpty() },
		afterExecution = afterExecutionHooks.takeIf { it.isNotEmpty() }
	)
}

fun hooks(block: PipelineHooksBuilder.() -> Unit): PipelineHooks =
	PipelineHooksBuilder().apply(block).build()