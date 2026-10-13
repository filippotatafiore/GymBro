package com.example.gymbro.profile

import androidx.annotation.StringRes
import com.example.gymbro.R
import kotlin.math.pow

// Calculations on the body data of the profile (plain Kotlin: tested in BodyDataTest.kt)
object BodyData {

    // Body mass index: weight / height² (kg/m²); null if height or weight are missing
    fun bmi(heightCm: Double?, weightKg: Double?): Double? {
        if (heightCm == null || weightKg == null || heightCm <= 0) return null
        return weightKg / (heightCm / 100).pow(2)
    }

    // Category of the World Health Organization, e.g. "normal weight"
    @StringRes
    fun bmiCategory(bmi: Double): Int = when {
        bmi < 18.5 -> R.string.bmi_underweight
        bmi < 25 -> R.string.bmi_normal
        bmi < 30 -> R.string.bmi_overweight
        else -> R.string.bmi_obese
    }
}
