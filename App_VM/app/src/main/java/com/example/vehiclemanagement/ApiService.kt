package com.example.vehiclemanagement

import com.example.vehiclemanagement.Model.AddVehicleRequest
import com.example.vehiclemanagement.Model.ScanRequest
import com.example.vehiclemanagement.Model.ScanResponse
import com.example.vehiclemanagement.Model.Vehicle
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface ApiService {
    @POST("api/vehicles/check")
    fun checkLicensePlate(@Body request: ScanRequest): Call<ScanResponse>
    @GET("api/vehicles")
    fun getVehicles(): Call<List<Vehicle>>
    @POST("api/vehicles")
    fun addVehicle(@Body request: AddVehicleRequest): Call<Vehicle>
}



object RetrofitClient {
    private const val BASE_URL = "http://10.204.94.50:3000/"
    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}