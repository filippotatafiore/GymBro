package com.example.gymbro.plan

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDate

// Fills the horizontal WORKOUT PLANS list of the profile: one card (res/layout/item_plan.xml) per
// plan, e.g. "Hypertrophy · 4 days" / "4 days · Build muscle"; "ACTIVE" on the plan followed by the
// gym sessions. Tap a card to open the plan.
class PlanAdapter(
    private val onClick: (Plan) -> Unit
) : ListAdapter<Plan, PlanAdapter.PlanViewHolder>(DIFF) {

    // The plan followed by the gym sessions (null = none): set it before submitList
    var activePlanId: Long? = null

    class PlanViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val active: View = view.findViewById(R.id.planCardActive)
        val name: TextView = view.findViewById(R.id.planCardName)
        val info: TextView = view.findViewById(R.id.planCardInfo)
        val date: TextView = view.findViewById(R.id.planCardDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlanViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_plan, parent, false)
        return PlanViewHolder(view)
    }

    override fun onBindViewHolder(holder: PlanViewHolder, position: Int) {
        val plan = getItem(position)
        val context = holder.itemView.context

        holder.name.text = plan.name
        holder.active.isVisible = plan.id == activePlanId
        val days = context.resources.getQuantityString(R.plurals.plan_days, plan.days.size, plan.days.size)
        holder.info.text = plan.goal?.let { context.getString(R.string.plan_card_info, days, context.getString(it.label)) } ?: days
        holder.date.text = context.getString(R.string.plan_updated_on, formatDate(plan.updatedAt))
        holder.itemView.setOnClickListener { onClick(plan) }
    }

    companion object {
        // Lets the list animate only the cards that changed
        private val DIFF = object : DiffUtil.ItemCallback<Plan>() {
            override fun areItemsTheSame(old: Plan, new: Plan) = old.id == new.id
            override fun areContentsTheSame(old: Plan, new: Plan) = old == new
        }
    }
}
