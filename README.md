# Kaida

Kaida is a prototype Kotlin library designed to simplify and standardize interactions with multiple large language model (LLM) APIs including OpenAI-compatible completion and chat endpoints as well as Anthropic. It also features a strongly typed Directed Acyclic Graph (DAG) pipeline for structuring complex multi-step workflows.

Designed for extremely rapid iteration, Kaida prioritizes:

- Unified LLM abstractions that fully model the union set of all supported features using sum types.
    - Swap models (e.g. chat to completion, different APIs, sampler settings) without changing your application code at all.
    - Get compile time errors or fail fast at runtime - never silently do the wrong thing.
    - Write flexible code that can account for a variety of different features.
- Fully asynchronous streaming API built on Kotlin's [asynchronous flows](https://kotlinlang.org/docs/flow.html) that supports canceling requests and parallelism
- Automatic serialization of all intermediate states as well as outputs using kotlinx.serialization
- Observable and debuggable: a pipeline's internal state is easily inspected at any point during execution
- Decoupled design that lends itself well to dynamically driven UI
- Every level of tool: low level LLM completion API, high level template and pipeline API built on top

This document introduces Kaida incrementally through practical examples.

# Table of Contents

- [Installation](#installation)
- [Configuration](#configuration)
- [Motivation: Why Use Kaida?](#motivation-why-use-kaida)
- [Typed Feature Flags and Fail-Fast](#typed-feature-flags-and-fail-fast)
- [Templates for Structured Prompts](#templates-for-structured-prompts)
- [Building Complex Workflows: The Pipeline DSL](#building-complex-workflows-the-pipeline-dsl)
- [Retry Policies](#retry-policies)
- [Serialization and Reloading Pipelines](#serialization-and-reloading-pipelines)
- [Simplifying Serialization](#simplifying-serialization)
- [Contributing](#contributing)
- [License](#license)

## Installation

Using Gradle, add:

```kotlin
dependencies {
    implementation("org.nous:kaida:<latest-version>")
}
```

Replace `<latest-version>` with the current release version.

## Configuration

Kaida uses YAML to configure model details and authentication separately:

**`config/auth.yaml`**
```yaml
- name: "openai"
  auth:
    type: "api_key"
    api_key: "your_api_key_here"
- name: "anthropic"
  auth:
    type: "api_key"
    api_key: "your_api_key_here"
```

**`config/models.yaml`**
```yaml
- name: "gpt4o-mini"
  auth: "openai"
  model:
    type: "openai"
    model: "gpt-4o-mini"
- name: "claude3-opus"
  auth: "anthropic"
  model:
    type: "anthropic"
    model: "claude-3-opus-20240229"
    temperature: 1.2
    top_p: 0.95
```

## Motivation: Why Use Kaida?

Interacting directly with multiple LLM providers often results in fragmented and redundant code. Each API provider tends to have distinct capabilities, request structures, and behaviors. Kaida eliminates this complexity by providing a single consistent interface:

```kotlin
val model = ModelRegistry.get("claude3-opus")!!

val request = model.multiTurn(
    listOf(
        Message("hi what's up"),
        Message("the sky????", MessageRole.Assistant),
    ),
)

request.collect { 
    when(it) {
        is TextCompletion -> print(it)
        else -> TODO()
    }
}
```

This uniformity simplifies integrating new models or changing providers without refactoring business logic.

## Typed Feature Flags and Fail-Fast

Different models have varying capabilities (e.g. logprobs, system prompts, logit bias, etc). Kaida makes such features explicit:

```kotlin
val model = ModelRegistry.get("claude3-opus")!!

// note: only a chat completion endpoint will accept multiple messages
val chat = listOf(
    Message("hi what's up"),
    Message("the sky???", MessageRole.Assistant),
)

// this request wants logprobs, and we have a system prompt
val inferenceParams = InferenceParameters(
    logProbs=true,
    // we also can indicate here we'd prefer that this system prompt be cached if caching is available/supported
    systemPrompt=listOf(TextBlock("whatever", cacheHint=CacheHint.Cache))
)

/*
 we infer that logprobs and system prompt are required for this request,
 which means we would throw if either was unsupported by the underlying model
*/
val response = model.multiTurn(chat, inferenceParams) {
    // however, we're actually okay with text completion, we'd just prefer logprobs if it's supported
    logProbs(InferenceFlag.Preferred)
}

/*
 now we consume the cold flow (which begins the request)
 
 we could get either logprob completions or text completions
 here depending on whether the underlying model supports logprobs
*/
response.collect { result ->
    when (result) {
        is LogProbCompletion -> {
            result.tokens.forEach { token ->
                val probability = "%.2f".format(token.selected.logprob)
                val tokenText = token.selected.token
                val options = token.options.joinToString(", ") { it.token.replace("\n", "\\n") }

                println("$probability ${tokenText.padEnd(20)} [$options]")
            }
        }
        is TextCompletion -> println(result.content)
    }
}
```

If the requested model doesn't support a required feature, Kaida immediately throws an error preventing unexpected runtime behavior.

## Templates for Structured Prompts

Kaida provides a straightforward template system:

```
Please convert the following text to Shakespearean prose, preserving the emotional tone, theme, narrative, prose as closely as possible.

Do NOT include any other text besides the prose, which should be placed within a code block.

<text>
$TEXT
</text>
```

```kotlin
val model = ModelRegistry.get("claude3-opus")!!

val completion = model.templateStream(Path("instruct", "shakespeare").toString()) {
    variable("TEXT", """
        Hi Bob,

        I hope you're doing well! It's been a while since our last conversation, and I wanted to touch base. I've been reflecting on our recent project discussions, and I have some new ideas that I think could really take things to the next level.

        Best regards,
        Alice
    """.trimIndent().trim())
}

// use a helper to fetch the contents as a string, regardless of completion type
println(completion.asString())

// OR collect the completion stream manually.
completion.collect {
    when(it) {
        is TextCompletion -> print(it.content)
        // templates do not emit any other type of completion by default
        else -> TODO()
    }
}
```

Templates make it easier to maintain a libary of structured prompts and use them throughout your codebase. These are provided as a primitive, and other features like token counting, etc are easily built on top of them.

On a per-template basis, each template may:

- Specify logit_bias, max_tokens, system_prompt, stop_sequences
- Template stop sequences are NOT tokens, and are NOT passed to the LLM API. A template monitors the completion and cancels the request early if a matching stop sequence is detected.
- Have a corresponding "prepend" template, which is prepended to the LLM's output. This is primarily useful for base model completions, which often use `<tag>...</tag>` wrappers for output.

## Building Complex Workflows: The Pipeline DSL

Kaida's pipeline system helps clearly organize multi-stage tasks involving LLM calls and custom logic. The pipelines are fully typed and explicitly declare inputs, outputs, and dependencies.

Here's a simple example that processes a raw SMTP email using two different LLMs:

```kotlin
@Serializable
data class Email(
    val sender: String,
    val subject: String,
    val date: String,
    val body: String,
)

class EmailVariables : PipelineVariables() {
    val raw_smtp_email by string()

    /*
     using a custom type
     we set transient=false to allow this value to be serialized (as `Email` is serializable)
    */
    val email by type<Email>(transient=false)
    val summary by string()
    /*
     we're deciding not to save the action items, so this value will need to be regenerated
     even if we reload the rest of the variables and run this pipeline again
    */
    val actionItems by list<String>(transient=true)

    val prioritizedActionItems by list<String>()

    // there is a single required input for this pipeline
    override val inputs = inputs { option(raw_smtp_email) }
    // the only terminal state for this pipeline is having both email and prioritizedActionItems set
    override val outputs = outputs { option(email, prioritizedActionItems) }

    // for a more complex pipeline we could define many options:
    override val outputs = outputs {
        option(x)
        option { 
            atLeastOneOf(a, b, c)
        }
        option { 
            ifMissingAnyOf(x,y,z) {
                exactlyOneOf(g)
                forbidden(d)
            }
        }
    }
}

private object Models {
    val gpt4o = ModelRegistry.get("gpt-4o-mini")!!
    val opus = ModelRegistry.get("claude3-opus")!!
}

private object Templates {
    val summarize_email = Models.opus.forTemplate("instruct", "summarize_email")
    val action_items = Models.gpt4o.forTemplate("instruct", "determine_actions")
    val prioritize = Models.gpt4o.forTemplate("instruct", "prioritize_actions")
}

val emailPipeline = simplePipeline<EmailVariables>("email_analysis") {
    step("Extract email data") {
        consumes(vars.raw_smtp_email)
        produces(vars.email)

        /*
         this will throw if vars.metadata isn't set
         or if any other vars are set (a step cannot alter variables it hasn't declared)
        */
        execute {
            val message = MimeMessage(
                Session.getInstance(Properties()),
                ByteArrayInputStream(vars.raw_smtp_email.value().toByteArray(Charsets.UTF_8))
            )

            val email = Email(
                message.from.firstOrNull() ?: "n/a",
                message.subject,
                message.sentDate ?: "n/a",
                message.content.asString(),
            )
            set(vars.email, email)
        }
    }

    step("Summarize email body") {
        consumes(vars.email)
        produces(vars.summary)

        execute {
            val summarized = Templates.summarize_email {
                variable("EMAIL", vars.email.value().body)
            }.asString()

            set(vars.summary, summarized)
        }
    }

    step("Determine action items") {
        consumes(vars.summary)
        produces(vars.actionItems)

        execute {
            val result = Templates.action_items {
                variable("SUMMARY", vars.summary.value())
            }.asString()
            val actions = extractBulletPoints(result)
                ?: error("couldn't extract action item bullet points from completion:\n$result")

            require(actions.size > 0) { "an email should have at least one action item" }
            require(actions.size <= 5) { "an email should have no more than 5 action items" }

            set(vars.actionItems, actions)
        }
    }

    step("Classify priority") {
        consumes(vars.actionItems)
        produces(vars.prioritizedActionItems)

        execute {
            val actions = vars.actionItems.value()

            // if there's only one we don't need to prioritize!
            if(actions.size == 1) {
                set(vars.prioritizedActionItems, actions)
                return@execute
            }

            val result = Templates.prioritize {
                variable("ACTIONS", actions.joinToString("\n"))
            }.asString()

            val prioritized = extractBulletPoints(result)
                ?: error("couldn't extract prioritized action item bullet points from completion:\n$result")

            set(vars.prioritizedActionItems, prioritized)
        }
    }
}

suspend fun main() {
    val email = getLatestEmailFromSMTPServer()

    val results = emailPipeline
        .prepare()
        .context {
            ctx.set(vars.raw_smtp_email, email)
        }
        .execute()
        .get { vars.prioritizedActionItems }

    println("prioritized results:")
    results.forEach {
        println(it)
    }
}
```

Each step explicitly declares the data it requires and produces, allowing Kaida to determine the correct execution order and detect configuration errors immediately.

## Retry policies

A pipeline may specify a retry policy, which will be used to retry steps when they throw exceptions:

```kotlin
val defaultRetryPolicy = RetryPolicy(
    maxAttempts = 3,
    initialDelay = Duration.ofSeconds(1),
    backoffMultiplier = 2.0,
    shouldRetryForException = {
        it !is ExceptionInInitializerError
    }
)

val emailPipeline = simplePipeline<EmailVariables>("email_analysis") {
    retryPolicy(defaultRetryPolicy)
    
    // ...
}
```

However, a step may override the default pipeline policy:

```kotlin
step("Extract email data") {
    consumes(vars.raw_smtp_email)
    produces(vars.email)
    retryPolicy(rp)
    execute {
        // ...
    }
}
```

A step which fails more than `maxAttempts` will cause the entire pipeline to fail.

Note that retry policies may be used for arbitrary sections of code as well:

```kotlin
val rp: RetryPolicy? = RetryPolicy(...)

// maybeRetry will execute this block and use the RetryPolicy if not null
rp.maybeRetry {
    // ...
}

// we can also get notified when an exception happens even if we're retrying
rp.maybeControlledRetry {
    onFail { throwable ->
        // log throwable
    }
    
    execute {
        // ...
    }
}
```

## Serialization and reloading pipelines

Kaida pipelines support serialization, allowing variables to be stored and reloaded. Continuing from our previous example:

```kotlin
suspend fun main() {
    val email = getLatestEmailFromSMTPServer()
    val fsdb = FilesystemDB.memoryStore()

    // run the pipeline
    val result1 = emailPipeline
        .prepare()
        .context {
            ctx.set(vars.raw_smtp_email, email)
        }
        .execute()
        .tracked()
    
    println("result1:")
    result1.ctx.get(result1.vars.prioritizedActionItems).forEach { 
        println(it)
    }

    // save the results
    val runId = UUID.randomUUID().toString()
    fsdb.serializePipeline(runId, emailPipeline, result1.ctx)

    // reload the results into a new pipeline run
    val loadedCtx = fsdb.loadContextForPipeline(runId, emailPipeline, includeOutputs=true)

    val result2 = emailPipeline
        // use the reloaded context
        .prepare(loadedCtx)
        .context {
            // but let's clear an intermediate variable!
            ctx.remove(vars.summary)
        }
        /*
         despite prioritizedActionItems being reloaded (due to includeOutputs above), it will be regenerated because:
           - it depends on actionItems, which is transient. therefore the input hash for prioritizedActionItems will not match
           - we also cleared summary above, which actionItems depends on. this would also cause  actionItems and prioritizedActionItems to be stale
        */
        .execute()
        .tracked()

    println("result2:")
    result2.ctx.get(result1.vars.prioritizedActionItems).forEach {
        println(it)
    }

    // close our database to make sure it flushes
    fsdb.close()
}
```

This functionality is particularly useful for long-running processes, checkpoints, or resuming after interruptions.

## Simplifying serialization

You can use the `executeAndSave` helper to streamline serialization. Note that currently only saving a pipeline after full completion is supported, though saving on a per-step basis is a desired feature. Contributions are welcome!

```kotlin
val fsdb = FilesystemDB.memoryStore()
val runId = UUID.randomUUID().toString()

try {
    val result = emailPipeline
        .prepare()
        .context {
            ctx.set(vars.raw_smtp_email, email)
        }
        .executeAndSave(runId, fsdb)
        .get { vars.prioritizedActionItems }
        
    result.forEach { 
        println(it)
    }
} finally {
    println("Saving database...")
    fsdb.close()
}
```

## Contributing

While engineering time on this project is currently limited, we welcome improvements, feature proposals, and bug fixes to Kaida. To contribute:

- Fork and clone the repository.
- Clearly document your changes.
- Submit pull requests for review.

Please keep PR size manageable. We would suggest some discussion prior to large PRs.

## License

Kaida is available under the [MIT License](LICENSE).