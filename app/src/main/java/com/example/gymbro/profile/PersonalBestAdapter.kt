package com.example.gymbro.profile

import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
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
import com.example.gymbro.formatDuration
import com.example.gymbro.network.Post
import com.example.gymbro.network.PostKind
import com.example.gymbro.session.SwimMath

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
        // runs and swims: the distance group (e.g. "10.5 km" for a 10.3 km run, "1.5 km" for a 1.3 km
        // swim); manual results: the exercise
        holder.title.text = when (best.kind) {
            PostKind.RUN -> formatDistance(PersonalBests.distanceGroupKm(best.distanceKm ?: 0.0))
            PostKind.SWIM -> formatDistance(PersonalBests.swimGroupKm(best.distanceKm ?: 0.0) ?: 0.0)
            else -> best.title
        }
        // swims: the average pace, how they are compared (e.g. "02:00 /100 m", the unit smaller)
        holder.value.text = if (best.kind == PostKind.SWIM) swimPace(best) else best.displayValue()
        holder.date.text = formatDate(best.sessionStart ?: best.createdAt)

        holder.itemView.setOnClickListener { onClick(best) }
    }

    private fun swimPace(swim: Post): CharSequence {
        val seconds = SwimMath.secondsPer100m(swim.durationSeconds ?: 0, (swim.distanceKm ?: 0.0) * 1000)
            ?: return ""
        val time = formatDuration(seconds * 1000)
        return SpannableString("$time /100 m").apply {
            setSpan(RelativeSizeSpan(0.6f), time.length, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
