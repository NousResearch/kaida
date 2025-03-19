package pipelinedag

data class PipelineContextWithVars<T : PipelineVariables>(val ctx: PipelineContextMutableView, val vars: T)
data class PipelineFetchContext<T : PipelineVariables>(val vars: T)

data class PipelineVariableReturn<A : Any, B : Any, C : Any, D : Any>(
	val var1: A?,
	val var2: B? = null,
	val var3: C? = null,
	val var4: D? = null,
)

class MultiScope<T : PipelineVariables> internal constructor (
	private val post: PipelinePreExecutionBuilder.PipelinePostExecutionBuilder<T>
) {
	fun <X : Any> get(
		block: PipelineFetchContext<T>.() -> Key<X>
	): X = post.get(block)

	fun <X : Any> getOrNull(
		block: PipelineFetchContext<T>.() -> Key<X>
	): X? = post.getOrNull(block)

	fun <A : Any, B : Any> collect(a: A, b: B) = Pair(a, b)
	fun <A : Any, B : Any, C : Any> collect(a: A, b: B, c: C) = Triple(a, b, c)
	fun <A : Any, B : Any, C : Any, D : Any> collect(a: A, b: B, c: C, d: D) = PipelineVariableReturn(a, b, c, d)
}

data class PipelineContextReturn<
	T : PipelineVariables,
	Tctx : IPipelineContext,
>(val vars: T, val ctx: Tctx)

class PipelinePreExecutionBuilder<T : PipelineVariables>(
	private val executor: PipelineExecutor<T>,
	private val ctx: IPipelineContext,
) {
//	private var rp: RetryPolicy? = executor.defaultRetryPolicy
	private var setup_block: (PipelineContextWithVars<T>.() -> Unit)? = null
	private val hooks = PipelineHooksBuilder()

	val pipeline: Pipeline
		get() = executor.pipeline

	fun context(block: PipelineContextWithVars<T>.() -> Unit): PipelinePreExecutionBuilder<T> {
		this.setup_block = block
		return this
	}

	// unsure if execution time retrypolicy makes sense
//	fun retryPolicy(rp: RetryPolicy): PipelinePreExecutionBuilder<T> {
//		this.rp = rp
//		return this
//	}

	fun hooks(block: PipelineHooksBuilder.() -> Unit) = also { block(hooks) }

//	fun hooks(block: PipelineHooksBuilder.() -> Unit): PipelinePreExecutionBuilder<T> {
//		block(hooks)
//		return this
//	}

	suspend fun execute(): PipelinePostExecutionBuilder<T> {
		val mutable = PipelineContextMutableView(ctx)
		setup_block?.invoke(PipelineContextWithVars(mutable, executor.variables))
		val result = executor.pipeline.executeTracked(mutable.freezeTracked(), hooks.build())

		return PipelinePostExecutionBuilder(executor.variables, result)
	}

	class PipelinePostExecutionBuilder<T : PipelineVariables>(
		val variables: T,
		val ctx: PipelineContextSourceTracked
	) {
		fun <K : Any> get(block: PipelineFetchContext<T>.() -> Key<K>): K =
			ctx.get(block(PipelineFetchContext(variables)))

		fun <K : Any> getOrNull(block: PipelineFetchContext<T>.() -> Key<K>): K? =
			ctx.getOrNull(block(PipelineFetchContext(variables)))

		fun <R> multi(block: MultiScope<T>.() -> R): R {
			val scope = MultiScope(this)
			return scope.block()
		}

		fun vars() = PipelineContextReturn<T, IPipelineContext>(variables, PipelineContext(ctx.asTypedMap()))
		fun tracked() = PipelineContextReturn<T, PipelineContextSourceTracked>(
			variables,
			ctx.copy() as PipelineContextSourceTracked
		)
	}
}

/**
 * fluent builder API for doing simple pipeline execution runs
 */
abstract class PipelineExecutor<T: PipelineVariables> {
	abstract val pipeline: Pipeline
	abstract val variables: T
//	abstract val defaultRetryPolicy: RetryPolicy?

	fun prepare(ctx: IPipelineContext? = null) = PipelinePreExecutionBuilder(this, ctx ?: PipelineContext())
}

@PipelineDSL
open class PipelineBuilderWithVariables<T : PipelineVariables>(id: String, val vars: T) : PipelineBuilder(id) {
	// special DSL functions may go here
}

/**
 * this is a utility class that bundles a pipeline with its variable type
 *
 * this allows us to easily pull those variables out while keeping them scoped to their associated pipeline
 *
 * this class assumes this pipeline has only a single variable class, though really pipelines could use
 * more than one.
 */
data class SimplePipeline<T : PipelineVariables>(
	override val pipeline: Pipeline,
	override val variables: T,
//	override val defaultRetryPolicy: RetryPolicy? = null
) : PipelineExecutor<T>() {
	companion object {
		inline fun <reified T : PipelineVariables> create(
			id: String,
			noinline init: (() -> T)? = null,
			crossinline block: PipelineBuilderWithVariables<T>.() -> Unit
		): SimplePipeline<T> {
			val pv = init?.invoke() ?: T::class.java.getDeclaredConstructor().newInstance() as T
			val builder = PipelineBuilderWithVariables<T>(id, pv)
			builder.block()
			return SimplePipeline(builder.build(), pv)
		}
	}
}

inline fun <reified T : PipelineVariables> simplePipeline(
	id: String,
	noinline init: (() -> T)? = null,
	crossinline block: PipelineBuilderWithVariables<T>.() -> Unit
): SimplePipeline<T> {
	val pv = init?.invoke() ?: T::class.java.getDeclaredConstructor().newInstance() as T
	val builder = PipelineBuilderWithVariables<T>(id, pv)
	builder.block()
	return SimplePipeline(builder.build(), pv)
}