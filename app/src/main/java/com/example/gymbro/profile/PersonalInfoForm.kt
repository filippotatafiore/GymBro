package com.example.gymbro.profile

import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.example.gymbro.R
import com.example.gymbro.network.Sex
import com.example.gymbro.network.User
import com.example.gymbro.network.UserSession
import com.example.gymbro.repository.UserRepository
import com.example.gymbro.showError
import kotlinx.coroutines.CoroutineScope
import java.text.DecimalFormat
import java.time.Year
import java.util.Locale

// Personal information of the user (body data: height, weight, year of birth, sex), with the BMI
// updated while typing, saved on this phone and on the server, also offline (see UserRepository)
// (res/layout/form_personal_info.xml, inside [root]).
// The weight is used for the calories of the runs, all of them by the AI coach for the workout
// plans. An empty field = not set. Used by the "Personal information" page of the menu of the
// profile and by the window shown after the registration. onSaved: the updated user.
class PersonalInfoForm(private val root: View, private val scope: CoroutineScope, private val onSaved: (User) -> Unit) {

    private val context = root.context
    private val heightInput: EditText = root.findViewById(R.id.profileHeightInput)
    private val weightInput: EditText = root.findViewById(R.id.profileWeightInput)
    private val birthYearInput: EditText = root.findViewById(R.id.profileBirthYearInput)
    private val sexGroup: RadioGroup = root.findViewById(R.id.profileSexGroup)
    private val bmiText: TextView = root.findViewById(R.id.profileBmiText)
    private val error: TextView = root.findViewById(R.id.profileError)
    private val save: Button = root.findViewById(R.id.saveProfileButton)

    init {
        UserSession.user?.let { show(it) }
        heightInput.doAfterTextChanged { showBmi() }
        weightInput.doAfterTextChanged { showBmi() }
        showBmi()
        save.setOnClickListener { save() }
    }

    private fun show(user: User) {
        val numberFormat = DecimalFormat("0.#")
        user.heightCm?.let { heightInput.setText(numberFormat.format(it)) }
        user.weightKg?.let { weightInput.setText(numberFormat.format(it)) }
        user.birthYear?.let { birthYearInput.setText(String.format(Locale.ROOT, "%d", it)) }
        when (user.sex) {
            Sex.MALE -> sexGroup.check(R.id.sexMale)
            Sex.FEMALE -> sexGroup.check(R.id.sexFemale)
            Sex.OTHER -> sexGroup.check(R.id.sexOther)
            null -> {}
        }
    }

    private fun EditText.number(): Double? = text.toString().trim().replace(',', '.').toDoubleOrNull()

    // BMI, updated while typing height and weight
    private fun showBmi() {
        val bmi = BodyData.bmi(heightInput.number(), weightInput.number())
        bmiText.text =
            if (bmi == null || bmi !in 5.0..100.0) context.getString(R.string.bmi_missing)
            else context.getString(
                R.string.bmi_value, DecimalFormat("0.0").format(bmi), context.getString(BodyData.bmiCategory(bmi))
            )
    }

    private fun save() {
        val height = heightInput.number()
        val weight = weightInput.number()
        val birthYear = birthYearInput.text.toString().trim().toIntOrNull()
        val thisYear = Year.now().value
        if (heightInput.text.isNotBlank() && (height == null || height !in 100.0..250.0)) {
            return error.showError(context.getString(R.string.error_invalid_height))
        }
        if (weightInput.text.isNotBlank() && (weight == null || weight !in 30.0..300.0)) {
            return error.showError(context.getString(R.string.error_invalid_body_weight))
        }
        if (birthYearInput.text.isNotBlank() && (birthYear == null || birthYear !in 1900..thisYear)) {
            return error.showError(context.getString(R.string.error_invalid_birth_year, thisYear))
        }
        val sex = when (sexGroup.checkedRadioButtonId) {
            R.id.sexMale -> Sex.MALE
            R.id.sexFemale -> Sex.FEMALE
            R.id.sexOther -> Sex.OTHER
            else -> null
        }
        error.isVisible = false
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(save.windowToken, 0)

        UserRepository.saveBodyData(height, weight, birthYear, sex)
        UserSession.user?.let { onSaved(it) }
    }
}
