package pipelinedag

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.mapdb.DB
import org.mapdb.DBMaker
import org.mapdb.Serializer
import pipelinedag.PipelinePreExecutionBuilder.PipelinePostExecutionBuilder
import java.nio.file.Path
import java.util.UUID

interface PipelineContextSerializer {
	fun serializeKeys(runId: String, pipeline: Pipeline, keys: Set<Key<*>>, ctx: PipelineContextSourceTracked)
	fun loadContextForPipeline(
		runId: String,
		pipeline: Pipeline,
		ctx: IPipelineContext = PipelineContext(),
		overwrite: Boolean = false,
		includeOutputs: Boolean = false
	): PipelineContextSourceTracked?

	fun serializePipeline(runId: String, pipeline: Pipeline, ctx: PipelineContextSourceTracked) {
		serializeKeys(runId, pipeline, pipeline.steps.flatMap { it.produces + it.consumes }.toSet(), ctx)
	}
}

@Serializable
data class StepSource(val step: String, val inputHash: StepInputHash)

@Serializable
data class SerializedPipelineVariable(
	val runId: String,
	val pipeline: String,
	val structuralHash: Int,
	val source: StepSource?,
	val timestamp: Long,
	val key: String,
	val value: String
)

data class PipelineKey(val runId: String, val pipeline: String, val varkey: String, val timestamp: Long? = null) {
	constructor(runId: String, pipeline: Pipeline, varkey: Key<*>, timestamp: Long? = null) :
			this(runId, pipeline.id, varkey.name, timestamp)

	fun asLatestKey(): String = "$runId|$pipeline|$varkey"
	fun asHistoricalKey(): String =
		timestamp?.let { "$runId|$pipeline|$varkey|$it" }
			?: throw IllegalArgumentException("Timestamp is required for historical keys")
}

private val json = Json {
	encodeDefaults=false
	prettyPrint=false
}

class FilesystemDB(private val maker: DBMaker.Maker) : PipelineContextSerializer {
	private val db: DB = maker
		.transactionEnable()
		.make()

	val latestMap = db.hashMap<String, String>(
		"latestMap", Serializer.STRING, Serializer.STRING
	).createOrOpen()

	val historicalMap = db.treeMap<String, String>(
		"historicalMap", Serializer.STRING, Serializer.STRING
	).createOrOpen()

	override fun serializeKeys(
		runId: String,
		pipeline: Pipeline,
		keys: Set<Key<*>>,
		ctx: PipelineContextSourceTracked,
	) {
		// TODO: this is not safe. not monotonic, not guaranteed to be correct
		//       probably best choice is assume system time is reasonable, but blow up if this date is in the past
		//       compared to the most recent entry in historicalMap
		val currentTime = System.currentTimeMillis()

		for (varkey in keys) {
			val tracked = ctx.getTrackedOrNull(varkey)
				?: continue

			val key = PipelineKey(runId, pipeline.id, varkey.name, currentTime)

			val serializer = serializer(varkey.type)
			val variable = SerializedPipelineVariable(
				runId = runId,
				pipeline = pipeline.id,
				structuralHash = varkey.owner.structuralHash(),
				source = when(tracked.source) {
					null -> null
					is ContextValueSource.StepSource -> StepSource(tracked.source.name(), tracked.source.hash)
				},
				timestamp = currentTime,
				key = varkey.name,
				value = json.encodeToString(serializer, tracked.value)
			)
			val value = json.encodeToString(variable)

			latestMap[key.asLatestKey()] = value
			historicalMap[key.asHistoricalKey()] = value
		}

		db.commit()
	}

	fun getAllRunIDsForPipeline(pipeline: Pipeline): Set<String> {
		val reg = Regex("""^([^|]+)\|""" + pipeline.id)
		return latestMap.keys
			.mapNotNull { reg.find(it)?.groupValues[1] }
			.toSet()
	}

	override fun loadContextForPipeline(
		runId: String,
		pipeline: Pipeline,
		ctx: IPipelineContext,
		overwrite: Boolean,
		includeOutputs: Boolean
	): PipelineContextSourceTracked {
		val ctx = PipelineContextSourceTracked.from(ctx)

		for (varkey in pipeline.allVariables(includeOutputs)) {
			val mapkey = PipelineKey(runId, pipeline, varkey).asLatestKey()

			if(!latestMap.containsKey(mapkey))
				continue

			val raw: SerializedPipelineVariable = json.decodeFromString(latestMap.getValue(mapkey))

			if(varkey.owner.structuralHash() != raw.structuralHash) {
				println("skipping variable with bad structural hash: ${varkey.name}")
				continue
			}

			val value = varkey.deserializer().invoke(raw.value, json)

			@Suppress("UNCHECKED_CAST")
			ctx.set(
				varkey as Key<Any>,
				varkey.castValue(value) as Any,
				raw.source?.let {
					ContextValueSource.StepSource(
						pipeline.steps.first { it.name == raw.source.step },
						raw.source.inputHash
					)
				}
			)
		}

		return ctx
	}

	fun close() {
		db.close()
	}

	companion object {
		fun fromPath(path: Path): FilesystemDB {
			val db = DBMaker.fileDB(path.toFile())
				.fileMmapEnableIfSupported()
			return FilesystemDB(db)
		}

		fun memoryStore() = FilesystemDB(DBMaker.memoryDB())
	}
}

suspend fun <T : PipelineVariables> PipelinePreExecutionBuilder<T>.executeAndSave(
	runId: String,
	serializer: PipelineContextSerializer,
): PipelinePostExecutionBuilder<T> {
	val ret = this.execute()
	val tracked = ret.tracked()

	serializer.serializePipeline(runId, pipeline, tracked.ctx)

	return ret
}

// TODO: unit tests, need to test input hashes, structural hashes, reloading, etc
suspend fun main() {
	val fsdb = FilesystemDB.memoryStore()

	class Variables : PipelineVariables() {
		val input by string()
		val converted by int()
		val multiplier by int()
		val output by int()

		override val inputs = inputs {
			option(input)
		}

		override val outputs = outputs {
			option(output)
		}
	}

	val p = simplePipeline<Variables>("test_serializer.kt") {
		step("ask for multiplier") {
			produces(vars.multiplier)
			execute {
				print("multiplier: ")
				val value = readln().toInt()
				set(vars.multiplier, value)
			}
		}

		step("convert to int") {
			consumes(vars.input)
			produces(vars.converted)
			execute {
				set(vars.converted, vars.input.value().toInt())
			}
		}

		step("multiply to output") {
			consumes(vars.converted, vars.multiplier)
			produces(vars.output)
			execute {
				set(vars.output, vars.converted.value() * vars.multiplier.value())
			}
		}
	}

	val result = p
		.prepare()
		.context {
			ctx.set(vars.input, "5")
			ctx.set(vars.multiplier, 100)
		}
		.execute()
		.tracked()

	val runId = UUID.randomUUID().toString()
	fsdb.serializePipeline(runId, p.pipeline, result.ctx)

	println("Result: " + result.ctx.get(result.vars.output))
	val loadedCtx = fsdb.loadContextForPipeline(runId, p.pipeline, includeOutputs=true)
	println("Loaded PipelineContext: $loadedCtx")
	println("Loaded multiplier: " + loadedCtx.get(result.vars.multiplier))

	val result2 = p
		.prepare(loadedCtx)
		.context {
			ctx.set(vars.input, "5")
			ctx.set(vars.converted, 100)
		}
		.execute()
		.vars()

	println("Result2: " + result2.ctx.get(result2.vars.output))

	fsdb.close()
}
