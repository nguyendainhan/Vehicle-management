package com.example.vehiclemanagement.Model

import com.google.gson.annotations.SerializedName

data class ScanRequest(
    @SerializedName("plate") val plate: String,
    @SerializedName("action") val action: String
)


data class ScanResponse(
    @SerializedName("allowed") val allowed: Boolean
)

data class Vehicle(
    @SerializedName("id") val id: Int,
    @SerializedName("plate_number") val plateNumber: String,
    @SerializedName("owner_name") val ownerName: String?
)

data class AddVehicleRequest(
    @SerializedName("plate_number") val plateNumber: String,
    @SerializedName("owner_name") val ownerName: String = ""
)

data class ScanHistoryItem(
    @SerializedName("plate_number") val plate: String, // Khớp với DB MySQL
    @SerializedName("action") val action: String,
    @SerializedName("status") val status: String,      // "ALLOW" hoặc "DENY"
    @SerializedName("created_at") val time: String     // MySQL trả về chuỗi thời gian
) {
    // Helper để check logic hiển thị màu
    fun isAllowed(): Boolean {
        return status == "ALLOW"
    }
}
