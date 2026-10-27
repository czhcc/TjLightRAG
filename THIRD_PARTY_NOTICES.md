# Third-party notices

## HKUDS/LightRAG

- Project: https://github.com/HKUDS/LightRAG
- Reference commit: `aaebfe1bb8d55deb6a4304d357a3bf4c92fcd40a`
- Copyright (c) 2025 LightRAG Team
- License: MIT; the complete, unmodified text is in [third_party/LightRAG/LICENSE](third_party/LightRAG/LICENSE).

The stage-0 test corpus contains upstream prompt text and outputs produced by the upstream algorithms. The development-only generator and source mapping identify the functions used. The upstream notice and license must remain with these materials and with later translated or adapted algorithms/templates. No third-party license is replaced by this notice, and this file does not set a license for the rest of TjLightRAG.

Python reference dependencies are development tools, resolved from the pinned upstream uv.lock; their separate licenses continue to apply. They are not application-runtime dependencies.

## Stage 2 runtime parsing and tokenization

- JTokkit 1.1.0 (MIT): https://github.com/knuddelsgmbh/jtokkit/tree/1.1.0 ; notice in third_party/JTokkit/LICENSE.
- flexmark-java 0.64.8 (BSD 2-Clause): https://github.com/vsch/flexmark-java ; notice in third_party/flexmark-java/LICENSE.
- tiktoken 0.12.0 (MIT, reference generator only): third_party/tiktoken/LICENSE. The bundled cl100k_base vocabulary is checked against tiktoken's official SHA-256; Python is not an application runtime dependency.

The runtime image includes these notices in /app/licenses and /app/THIRD_PARTY_NOTICES.md. LightRAG's fixed-window algorithm adaptation remains covered by the upstream MIT notice above. Other dependency artifacts retain their individual licenses.

## Stage 3 extraction and graph merge

The extraction/gleaning/summary templates in backend/app/src/main/resources/rag/prompts and the adapted parsing/normalization/merge code in app.rag.index derive from the pinned LightRAG commit above. PROVENANCE.json records the original prompt source hash; the upstream MIT copyright and license in third_party/LightRAG/LICENSE apply to these materials.

## Stage 4 retrieval

The keyword/context/response templates in backend/app/src/main/resources/rag/retrieval and the adapted retrieval, polling, truncation and reference algorithms in app.rag.retrieval derive from the pinned LightRAG commit above. The upstream MIT copyright/license and the recorded PROVENANCE.json accompany these materials.

## Stage 5 answering and reranking

Answer generation/preview reuses the pinned LightRAG response template above. The chunk reranking/filtering/order in app.rag.query adapts utils.py apply_rerank_if_enabled and process_chunks_unified; the fixed upstream reranking fixture is compared with production Java. Strict failures, disabled answer caching and the session-scoped POST SSE/cancellation protocol are documented project adaptations. The same LightRAG MIT notice and license remain applicable.
