package com.imnexerio.MPHolistic


data class CombinedResults(
    val poseResults: List<Any>,
    val handResults_left: List<Float>,
    val handResults_right: List<Float>,
    val face_Results: List<Float>,

    )


data class TranslationResponse(
    val translatedText: List<String>
)