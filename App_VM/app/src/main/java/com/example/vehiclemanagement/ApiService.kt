package com.example.vehiclemanagement

import com.example.vehiclemanagement.Model.AddVehicleRequest
import com.example.vehiclemanagement.Model.ScanHistoryItem
import com.example.vehiclemanagement.Model.ScanRequest
import com.example.vehiclemanagement.Model.ScanResponse
import com.example.vehiclemanagement.Model.Vehicle
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface ApiService {
    // 1. Kiểm tra xe (Check In/Out)
    @POST("api/vehicles/check")
    fun checkLicensePlate(@Body request: ScanRequest): Call<ScanResponse>

    // 2. Lấy lịch sử (Sửa từ "history" thành "api/logs")
    @GET("api/logs")
    fun getScanHistory(): Call<List<ScanHistoryItem>>

    // 3. Lấy danh sách xe đã đăng ký
    @GET("api/vehicles")
    fun getVehicles(): Call<List<Vehicle>>

    // 4. Thêm xe mới
    @POST("api/vehicles")
    fun addVehicle(@Body request: AddVehicleRequest): Call<Vehicle>

    // 5. Xóa xe (Mới thêm)
    @DELETE("api/vehicles/{id}")
    fun deleteVehicle(@Path("id") id: Int): Call<Void>
}



object RetrofitClient {
    private const val BASE_URL = "http://10.134.182.50:3000/"
    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}