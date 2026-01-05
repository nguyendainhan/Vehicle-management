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
    @SerializedName("plate_number") val plateNumber: String
)

data class AddVehicleRequest(
    @SerializedName("plate_number") val plateNumber: String
)