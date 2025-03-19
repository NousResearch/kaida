package llms.configs

import io.ktor.client.plugins.logging.LogLevel
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@OptIn(ExperimentalSerializationApi::class)
@Serializer(forClass = LogLevel::class)
internal object LogLevelSerializer : KSerializer<LogLevel> {
	override fun serialize(encoder: Encoder, value: LogLevel) {
		encoder.encodeString(value.name)
	}

	override fun deserialize(decoder: Decoder): LogLevel {
		val name = decoder.decodeString()
		return LogLevel.valueOf(name)
	}
}