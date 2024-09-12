// ApiService.kt
package com.imnexerio.MPHolistic

import com.google.gson.JsonObject
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
    @POST("/upload-keypoints")
    fun uploadKeypoints(@Body keypoints: JsonObject): Call<TranslationResponse>
}