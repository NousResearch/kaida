package pipelinedag

import kotlinx.coroutines.flow.Flow
import llms.ChatModelAPI
import llms.Completion
import llms.InferenceControl
import llms.InferenceParameters
import llms.Message
import llms.ModelCapabilities
import llms.RetryPolicy
import llms.configs.ModelRegistry
import llms.maybeRetry

fun extractCodeBlocks(input: String): List<String> {
	return Regex("```(.*?)```", RegexOption.DOT_MATCHES_ALL)
		.findAll(input)
		.map { it.groupValues[1].trim() }
		.toList()
}

fun extractXMLBlock(input: String, blockName: String): String? {
	val start_str = "<$blockName>"
	val start = input.indexOf(start_str)

	if(start < 0)
		return null

	val end = input.indexOf("</$blockName>", start)

	if(end < 0)
		return null

	return input.substring(start+start_str.length, end)
}