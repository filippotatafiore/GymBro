package com.example.gymbro

import com.example.gymbro.profile.BodyData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// Run with: right click on this file > Run 'BodyDataTest'
class BodyDataTest {

    @Test
    fun bmi_weightDividedByHeightSquared() {
        // 72.5 kg / (1.80 m)^2 = 22.4
        assertEquals(22.38, BodyData.bmi(180.0, 72.5)!!, 0.01)
    }

    @Test
    fun bmi_missingData() {
        assertNull(BodyData.bmi(null, 70.0))
        assertNull(BodyData.bmi(175.0, null))
    }

    @Test
    fun bmi_categoriesOfTheWorldHealthOrganization() {
        assertEquals(R.string.bmi_underweight, BodyData.bmiCategory(17.0))
        assertEquals(R.string.bmi_normal, BodyData.bmiCategory(22.4))
        assertEquals(R.string.bmi_overweight, BodyData.bmiCategory(27.0))
        assertEquals(R.string.bmi_obese, BodyData.bmiCategory(31.0))
    }
}
