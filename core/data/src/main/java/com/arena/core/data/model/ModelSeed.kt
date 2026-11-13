package com.arena.core.data.model

/**
 * Curated local seed of the Arena catalog.
 *
 * The full 300+ list is fetched from `https://arena.ai/api/models` by
 * [ModelRepository.refresh]; this seed guarantees:
 *  - offline model picker works out of the box,
 *  - the offline fallback never shows an empty catalog,
 *  - unit tests and previews do not need network.
 *
 * Ranks/votes are illustrative seed values and are replaced on first refresh.
 */
object ModelSeed {

    data class Seed(
        val id: String,
        val displayName: String,
        val provider: String,
        val modality: Modality,
        val arenaRank: Int? = null,
        val votes: Int? = null,
        val contextWindow: Int? = null,
        val description: String? = null,
    )

    val ALL: List<com.arena.core.data.db.ModelEntity>
        get() = seeds.map {
            com.arena.core.data.db.ModelEntity(
                id = it.id,
                displayName = it.displayName,
                provider = it.provider,
                modality = it.modality.name,
                arenaRank = it.arenaRank,
                votes = it.votes,
                contextWindow = it.contextWindow,
                description = it.description,
            )
        }

    private val seeds = listOf(
        // ---------------------------------------------------------------- TEXT
        Seed("gpt-5", "GPT-5", "OpenAI", Modality.TEXT, 1, 312_000, 400_000, "Flagship reasoning model."),
        Seed("gpt-5-mini", "GPT-5 mini", "OpenAI", Modality.TEXT, 14, 121_000, 400_000, "Fast, affordable GPT-5 class."),
        Seed("gpt-4.1", "GPT-4.1", "OpenAI", Modality.TEXT, 9, 204_000, 1_000_000, "Long-context generalist."),
        Seed("o3", "o3", "OpenAI", Modality.TEXT, 4, 167_000, 200_000, "Deep reasoning."),
        Seed("o4-mini", "o4-mini", "OpenAI", Modality.TEXT, 18, 88_000, 200_000, "Efficient reasoning."),
        Seed("claude-opus-4.5", "Claude Opus 4.5", "Anthropic", Modality.TEXT, 2, 198_000, 200_000, "Top-tier writing and analysis."),
        Seed("claude-sonnet-4.5", "Claude Sonnet 4.5", "Anthropic", Modality.TEXT, 5, 231_000, 200_000, "Balanced speed/quality."),
        Seed("claude-haiku-4.5", "Claude Haiku 4.5", "Anthropic", Modality.TEXT, 21, 74_000, 200_000, "Lightweight and quick."),
        Seed("gemini-3-pro", "Gemini 3 Pro", "Google", Modality.TEXT, 3, 187_000, 1_000_000, "Multimodal flagship."),
        Seed("gemini-3-flash", "Gemini 3 Flash", "Google", Modality.TEXT, 11, 143_000, 1_000_000, "Fast multimodal."),
        Seed("gemini-2.5-flash", "Gemini 2.5 Flash", "Google", Modality.TEXT, 16, 112_000, 1_000_000, "High throughput."),
        Seed("llama-4-maverick", "Llama 4 Maverick", "Meta", Modality.TEXT, 24, 66_000, 1_000_000, "Open-weight MoE."),
        Seed("llama-4-scout", "Llama 4 Scout", "Meta", Modality.TEXT, 33, 41_000, 10_000_000, "Huge context scout."),
        Seed("deepseek-r1", "DeepSeek R1", "DeepSeek", Modality.TEXT, 8, 154_000, 128_000, "Open reasoning model."),
        Seed("deepseek-v3", "DeepSeek V3", "DeepSeek", Modality.TEXT, 19, 97_000, 128_000, "Strong open generalist."),
        Seed("qwen3-235b", "Qwen3 235B", "Alibaba", Modality.TEXT, 13, 84_000, 128_000, "Large open MoE."),
        Seed("qwen3-max", "Qwen3 Max", "Alibaba", Modality.TEXT, 7, 142_000, 256_000, "Flagship Qwen."),
        Seed("mistral-large-3", "Mistral Large 3", "Mistral", Modality.TEXT, 17, 91_000, 256_000, "European flagship."),
        Seed("mixtral-8x22b", "Mixtral 8x22B", "Mistral", Modality.TEXT, 42, 33_000, 64_000, "Sparse open model."),
        Seed("grok-4", "Grok 4", "xAI", Modality.TEXT, 6, 161_000, 256_000, "Real-time tuned LLM."),
        Seed("grok-4-fast", "Grok 4 Fast", "xAI", Modality.TEXT, 15, 108_000, 256_000, "Latency-optimized Grok."),
        Seed("command-r-plus", "Command R+", "Cohere", Modality.TEXT, 29, 52_000, 128_000, "RAG-focused model."),
        Seed("command-a", "Command A", "Cohere", Modality.TEXT, 22, 69_000, 256_000, "Enterprise agent model."),
        Seed("nova-premier", "Nova Premier", "Amazon", Modality.TEXT, 26, 58_000, 1_000_000, "AWS flagship."),
        Seed("nova-pro", "Nova Pro", "Amazon", Modality.TEXT, 38, 39_000, 300_000, "Balanced Nova."),
        Seed("mastra-2", "MA-2", "Microsoft", Modality.TEXT, 27, 55_000, 256_000, "Azure tuned model."),
        Seed("phi-4", "Phi-4", "Microsoft", Modality.TEXT, 47, 27_000, 16_000, "Small strong reasoning."),
        Seed("kimi-k2", "Kimi K2", "Moonshot", Modality.TEXT, 12, 117_000, 128_000, "Agentic open model."),
        Seed("glm-4.7", "GLM-4.7", "Zhipu", Modality.TEXT, 25, 61_000, 128_000, "Open Chinese/English model."),
        Seed("yi-lightning", "Yi Lightning", "01.AI", Modality.TEXT, 35, 44_000, 128_000, "Ultra-fast serving."),
        Seed("fable-5", "Fable 5", "Narrative AI", Modality.TEXT, 31, 48_000, 128_000, "Creative writing specialist."),
        Seed("sonar-large", "Sonar Large", "Perplexity", Modality.SEARCH, 10, 138_000, 128_000, "Answer engine with citations."),

        // --------------------------------------------------------------- CODE
        Seed("gpt-5-codex", "GPT-5 Codex", "OpenAI", Modality.CODE, 20, 79_000, 400_000, "Repo-scale coding."),
        Seed("claude-code-sonnet", "Claude Sonnet 4.5 (Code)", "Anthropic", Modality.CODE, 23, 71_000, 200_000, "Agentic coding."),
        Seed("codestral-3", "Codestral 3", "Mistral", Modality.CODE, 36, 42_000, 256_000, "FIM completion expert."),
        Seed("qwen3-coder", "Qwen3 Coder", "Alibaba", Modality.CODE, 30, 51_000, 256_000, "Open coding agent."),
        Seed("deepseek-coder-v3", "DeepSeek Coder V3", "DeepSeek", Modality.CODE, 34, 45_000, 128_000, "Strong open code model."),
        Seed("starcoder-3", "StarCoder 3", "BigCode", Modality.CODE, 58, 19_000, 64_000, "Permissive training data."),
        Seed("devstral", "Devstral", "Mistral", Modality.CODE, 41, 36_000, 128_000, "SWE-bench tuned agent."),
        Seed("codegemma-2", "CodeGemma 2", "Google", Modality.CODE, 52, 23_000, 128_000, "Lightweight code model."),

        // ------------------------------------------------------------- IMAGE
        Seed("gpt-image-1", "GPT Image 1", "OpenAI", Modality.IMAGE, null, 96_000, null, "Photoreal generation."),
        Seed("imagen-4", "Imagen 4", "Google", Modality.IMAGE, null, 88_000, null, "Text-to-image flagship."),
        Seed("midjourney-v7", "Midjourney V7", "Midjourney", Modality.IMAGE, null, 121_000, null, "Stylized art generation."),
        Seed("flux-1.1-pro", "FLUX 1.1 Pro", "Black Forest Labs", Modality.IMAGE, null, 77_000, null, "Prompt adherence leader."),
        Seed("flux-schnell", "FLUX Schnell", "Black Forest Labs", Modality.IMAGE, null, 54_000, null, "1-step fast generation."),
        Seed("stable-diffusion-4", "Stable Diffusion 4", "Stability AI", Modality.IMAGE, null, 63_000, null, "Open-weight image model."),
        Seed("dall-e-3", "DALL·E 3", "OpenAI", Modality.IMAGE, null, 71_000, null, "Classic text-to-image."),
        Seed("ideogram-3", "Ideogram 3", "Ideogram", Modality.IMAGE, null, 49_000, null, "Text rendering in images."),
        Seed("recraft-v3", "Recraft V3", "Recraft", Modality.IMAGE, null, 38_000, null, "Vector/design oriented."),
        Seed("seedream-4", "Seedream 4", "ByteDance", Modality.IMAGE, null, 44_000, null, "Editing-focused model."),

        // ------------------------------------------------------------ SEARCH
        Seed("sonar-pro", "Sonar Pro", "Perplexity", Modality.SEARCH, 28, 64_000, 200_000, "Deep research answers."),
        Seed("searchgpt-2", "SearchGPT 2", "OpenAI", Modality.SEARCH, 37, 46_000, 128_000, "Web-grounded chat."),
        Seed("grounded-gemini", "Grounded Gemini", "Google", Modality.SEARCH, 44, 34_000, 1_000_000, "Google Search grounding."),

        // ------------------------------------------------------------- VIDEO
        Seed("veo-3", "Veo 3", "Google", Modality.VIDEO, null, 58_000, null, "Text-to-video with audio."),
        Seed("sora-2", "Sora 2", "OpenAI", Modality.VIDEO, null, 67_000, null, "Long-form video generation."),
        Seed("kling-2.5", "Kling 2.5", "Kuaishou", Modality.VIDEO, null, 41_000, null, "Cinematic motion."),
        Seed("runway-gen-4", "Gen-4", "Runway", Modality.VIDEO, null, 36_000, null, "Editor-friendly video."),
        Seed("hailuo-02", "Hailuo 02", "MiniMax", Modality.VIDEO, null, 31_000, null, "Fast video drafts."),
        Seed("luma-ray-3", "Ray 3", "Luma AI", Modality.VIDEO, null, 27_000, null, "Realistic physics."),
    )
}
