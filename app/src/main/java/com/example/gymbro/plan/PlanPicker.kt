package com.example.gymbro.plan

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.createModal

// Modal window to choose one of the workout plans of the user (res/layout/dialog_choose_plan.xml),
// e.g. to share it or to publish it. emptyText: shown if there are no plans.
// onChosen: called with the plan tapped and the window, which stays open (e.g. while the plan is
// sent): close it with picker.dialog.dismiss(), or show an error with picker.showError().
class PlanPicker private constructor(val dialog: Dialog) {

    private val progress: View = dialog.findViewById(R.id.planPickerProgress)
    private val error: TextView = dialog.findViewById(R.id.planPickerError)

    // A plan is being sent: the other plans cannot be chosen meanwhile
    var busy = false
        set(value) {
            field = value
            progress.isVisible = value
            if (value) error.isVisible = false
        }

    fun showError(message: String) {
        error.text = message
        error.isVisible = true
    }

    companion object {
        fun show(
            context: Context,
            owner: LifecycleOwner,
            title: String,
            message: String,
            emptyText: String,
            plans: List<Plan>,
            onChosen: (Plan, PlanPicker) -> Unit
        ): PlanPicker {
            val dialog = createModal(context, R.layout.dialog_choose_plan, owner)
            val picker = PlanPicker(dialog)
            dialog.findViewById<TextView>(R.id.planPickerTitle).text = title
            dialog.findViewById<TextView>(R.id.planPickerMessage).text = message
            dialog.findViewById<TextView>(R.id.planPickerEmpty).apply {
                text = emptyText
                isVisible = plans.isEmpty()
            }
            dialog.findViewById<View>(R.id.cancelButton).setOnClickListener { dialog.dismiss() }

            val list = dialog.findViewById<RecyclerView>(R.id.planPickerList)
            list.layoutManager = LinearLayoutManager(context)
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = plans.size

                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = object : RecyclerView.ViewHolder(
                    LayoutInflater.from(parent.context).inflate(R.layout.item_plan_choice, parent, false)
                ) {}

                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                    val plan = plans[position]
                    val row = holder.itemView
                    row.findViewById<TextView>(R.id.planChoiceName).text = plan.name
                    row.findViewById<TextView>(R.id.planChoiceInfo).text = plan.cardInfo(context)
                    row.findViewById<View>(R.id.planChoicePublic).isVisible = plan.isPublic
                    row.setOnClickListener { if (!picker.busy) onChosen(plan, picker) }
                }
            }
            dialog.show()
            return picker
        }
    }
}
