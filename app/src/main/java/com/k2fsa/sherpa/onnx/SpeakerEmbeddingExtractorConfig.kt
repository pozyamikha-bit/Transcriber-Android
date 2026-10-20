// Из sherpa-onnx v1.13.8 (Apache-2.0), https://github.com/k2-fsa/sherpa-onnx — не менять: имена должны совпадать с JNI
package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)
