package kotllm.client

import java.net.URI

import kotlin.test.Test
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*

import kotllm.config.ClientConfig
import kotllm.util.Yaml
import kotllm.isOk
import kotllm.orNull

class AzureFoundryTest {

    // -- Provider.infer -----------------------------------------------------

    @Test fun `Provider infer infers AzureFoundry from azure-foundry or azure-openai client kind`() {
        Provider.infer("azure-foundry", "https://custom.endpoint.com") shouldBe Provider.AzureFoundry
        Provider.infer("azure-openai", "https://custom.endpoint.com") shouldBe Provider.AzureFoundry
    }

    @Test fun `Provider infer infers AzureFoundry from services ai azure com domain`() {
        Provider.infer(
            "openai-compatible",
            "https://zzll-ai-foundry-scus.services.ai.azure.com"
        ) shouldBe Provider.AzureFoundry
        Provider.infer(
            "openai-compatible",
            "https://my-resource.services.ai.azure.com/openai/v1"
        ) shouldBe Provider.AzureFoundry
    }

    @Test fun `Provider infer infers AzureFoundry from openai azure com and cognitiveservices domains`() {
        Provider.infer(
            "openai-compatible",
            "https://my-res.openai.azure.com"
        ) shouldBe Provider.AzureFoundry
        Provider.infer(
            "openai-compatible",
            "https://my-res.cognitiveservices.azure.com"
        ) shouldBe Provider.AzureFoundry
    }

    @Test fun `Provider infer infers Databricks from azuredatabricks net or databricks com or serving-endpoints`() {
        Provider.infer(
            "openai-compatible",
            "https://adb-7193300288064453.13.azuredatabricks.net/serving-endpoints"
        ) shouldBe Provider.Databricks
        Provider.infer(
            "openai-compatible",
            "https://my-workspace.databricks.com/serving-endpoints"
        ) shouldBe Provider.Databricks
        Provider.infer(
            "openai-compatible",
            "https://proxy.internal.net/serving-endpoints"
        ) shouldBe Provider.Databricks
    }

    @Test fun `Provider infer infers Databricks from databricks client kind`() {
        Provider.infer("databricks", "https://proxy.internal.net") shouldBe Provider.Databricks
    }

    @Test fun `Provider infer infers GenericOpenAI for standard openai-compatible endpoints`() {
        // This used to use port 11434 as its example of a generic local server,
        // which is Ollama's port and nothing else's -- so the example was wrong
        // rather than the inference. A genuinely generic local server now.
        Provider.infer("openai-compatible", "http://localhost:8000/v1") shouldBe Provider.GenericOpenAI
        Provider.infer("openai", "https://api.openai.com/v1") shouldBe Provider.GenericOpenAI
    }

    @Test fun `Provider infer infers Ollama from its port, overriding a generic kind`() {
        // Same precedent as a databricks URL under `openai-compatible`: an
        // explicit azure or databricks kind wins, but a URL beats the generic
        // label.
        Provider.infer("openai-compatible", "http://localhost:11434/v1") shouldBe Provider.Ollama
        Provider.infer("", "http://gpubox.lan:11434") shouldBe Provider.Ollama
    }

    @Test fun `Provider infer infers Unknown for unsupported client kinds`() {
        Provider.infer("anthropic", "https://api.anthropic.com") shouldBe Provider.Unknown("anthropic")
    }

    // -- AzureFoundryClient.resolveEndpoint ----------------------------------

    @Test fun `AzureFoundryClient resolveEndpoint resolves bare services ai azure com without apiVersion to openai v1 chat completions`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://zzll-ai-foundry-scus.services.ai.azure.com",
            "gpt-5.6-luna",
            null
        )
        uri shouldBe URI.create("https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1/chat/completions")
    }

    @Test fun `AzureFoundryClient resolveEndpoint resolves bare services ai azure com with apiVersion to deployment route`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://zzll-ai-foundry-scus.services.ai.azure.com",
            "gpt-5.6-luna",
            "2024-12-01-preview"
        )
        uri shouldBe URI.create(
            "https://zzll-ai-foundry-scus.services.ai.azure.com/openai/deployments/gpt-5.6-luna/chat/completions?api-version=2024-12-01-preview"
        )
    }

    @Test fun `AzureFoundryClient resolveEndpoint resolves api_base ending in openai v1 to chat completions`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1",
            "gpt-5.6-luna",
            null
        )
        uri shouldBe URI.create("https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1/chat/completions")
    }

    @Test fun `AzureFoundryClient resolveEndpoint appends api-version when provided for deployment or v1 route`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1",
            "gpt-5.6-luna",
            "2024-12-01-preview"
        )
        uri shouldBe URI.create(
            "https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1/chat/completions?api-version=2024-12-01-preview"
        )
    }

    @Test fun `AzureFoundryClient resolveEndpoint resolves deployments route accurately`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://my-res.openai.azure.com/openai/deployments/my-dep",
            "my-dep",
            "2024-02-01"
        )
        uri shouldBe URI.create(
            "https://my-res.openai.azure.com/openai/deployments/my-dep/chat/completions?api-version=2024-02-01"
        )
    }

    @Test fun `AzureFoundryClient resolveEndpoint tolerates trailing slashes`() {
        val uri = AzureFoundryClient.resolveEndpoint(
            "https://zzll-ai-foundry-scus.services.ai.azure.com/",
            "gpt-5.6-luna",
            null
        )
        uri shouldBe URI.create("https://zzll-ai-foundry-scus.services.ai.azure.com/openai/v1/chat/completions")
    }

    // -- ClientConfig api_version parsing -------------------------------------

    @Test fun `ClientConfig api_version parsing parses api_version from client block`() {
        val yaml = """
            |type: openai-compatible
            |name: luna
            |api_base: https://zzll-ai-foundry-scus.services.ai.azure.com
            |api_key: secret-key
            |api_version: 2024-12-01-preview
            |""".trimMargin()

        val conf = Yaml.loadString(yaml)
        val parsed = ClientConfig.from(conf)
        parsed.isOk shouldBe true
        parsed.orNull()!!.apiVersion shouldBe "2024-12-01-preview"
    }

    // -- ChatRequest stream_options encoding ----------------------------------

    @Test fun `ChatRequest stream_options encoding encodes stream_options include_usage when stream and streamOptions are true`() {
        val req = ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(Message.user("hi")),
            stream = true,
            streamOptions = true
        )
        val encoded = req.encode()
        encoded["stream"]!!.jsonPrimitive.boolean shouldBe true
        val so = encoded["stream_options"]!!.jsonObject
        so["include_usage"]!!.jsonPrimitive.boolean shouldBe true
    }

    @Test fun `ChatRequest stream_options encoding does not encode stream_options when streamOptions is false`() {
        val req = ChatRequest(
            model = "gpt-5.6-luna",
            messages = listOf(Message.user("hi")),
            stream = true,
            streamOptions = false
        )
        val encoded = req.encode()
        encoded.containsKey("stream_options") shouldBe false
    }
}
