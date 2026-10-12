package com.example.gymbro.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDate
import com.example.gymbro.formatDistance
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind

// Fills the horizontal PERSONAL BESTS list of the profile: one card (res/layout/item_personal_best.xml)
// per personal best. Tap a card to see the details of the post.
class PersonalBestAdapter(
    private val onClick: (Post) -> Unit
) : ListAdapter<Post, PersonalBestAdapter.BestViewHolder>(POST_DIFF) {

    class BestViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.bestIcon)
        val title: TextView = view.findViewById(R.id.bestTitle)
        val value: TextView = view.findViewById(R.id.bestValue)
        val date: TextView = view.findViewById(R.id.bestDate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BestViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_personal_best, parent, false)
        return BestViewHolder(view)
    }

    override fun onBindViewHolder(holder: BestViewHolder, position: Int) {
        val best = getItem(position)
        holder.icon.setImageResource(best.icon())
        // runs: the distance (e.g. "10 km"); manual results: the exercise (e.g. "Deadlift")
        holder.title.text = if (best.kind == PostKind.RUN) formatDistance(best.distanceKm ?: 0.0) else best.title
        holder.value.text = best.displayValue()
        holder.date.text = formatDate(best.sessionStart ?: best.createdAt)

        holder.itemView.setOnClickListener { onClick(best) }
    }
}
