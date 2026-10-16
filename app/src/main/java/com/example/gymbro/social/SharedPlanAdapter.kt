package com.example.gymbro.social

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDateTime
import com.example.gymbro.network.SharedPlan
import com.example.gymbro.plan.cardInfo

// Fills the plans shared with a user (ConversationActivity): one card (res/layout/item_shared_plan.xml)
// per plan, on the right if sent, on the left if received; "Added to your plans" on the received
// plans added to the own plans. Tap a card to see the plan.
class SharedPlanAdapter(
    private val onClick: (SharedPlan) -> Unit
) : ListAdapter<SharedPlan, SharedPlanAdapter.SharedPlanViewHolder>(DIFF) {

    class SharedPlanViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val row: LinearLayout = view.findViewById(R.id.sharedPlanRow)
        val card: View = view.findViewById(R.id.sharedPlanCard)
        val name: TextView = view.findViewById(R.id.sharedPlanName)
        val info: TextView = view.findViewById(R.id.sharedPlanInfo)
        val status: TextView = view.findViewById(R.id.sharedPlanStatus)
        val time: TextView = view.findViewById(R.id.sharedPlanTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SharedPlanViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_shared_plan, parent, false)
        return SharedPlanViewHolder(view)
    }

    override fun onBindViewHolder(holder: SharedPlanViewHolder, position: Int) {
        val shared = getItem(position)
        val context = holder.itemView.context

        holder.row.gravity = if (shared.fromMe) Gravity.END else Gravity.START
        holder.card.setBackgroundResource(
            if (shared.fromMe) R.drawable.shared_plan_sent_background else R.drawable.shared_plan_received_background
        )
        holder.name.text = shared.plan.name
        holder.info.text = shared.plan.cardInfo(context)
        holder.time.text = formatDateTime(shared.createdAt.toEpochMilli())

        val added = !shared.fromMe && shared.savedPlanId != null
        holder.status.setText(if (added) R.string.added_to_your_plans else R.string.tap_to_view)
        holder.status.setTextColor(context.getColor(if (added) R.color.successGreen else R.color.textSecondary))
        holder.status.setCompoundDrawablesRelativeWithIntrinsicBounds(if (added) R.drawable.check_badge_icon else 0, 0, 0, 0)
        holder.status.compoundDrawableTintList = ColorStateList.valueOf(context.getColor(R.color.successGreen))

        holder.card.setOnClickListener { onClick(shared) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<SharedPlan>() {
            override fun areItemsTheSame(old: SharedPlan, new: SharedPlan) = old.id == new.id
            override fun areContentsTheSame(old: SharedPlan, new: SharedPlan) = old == new
        }
    }
}
