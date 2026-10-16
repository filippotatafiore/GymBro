package com.example.gymbro.social

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatTimeAgo
import com.example.gymbro.network.FeedPost
import com.example.gymbro.network.SocialUser
import com.example.gymbro.network.Post
import com.example.gymbro.profile.details
import com.example.gymbro.profile.displayTitle
import com.example.gymbro.profile.displayValue
import com.example.gymbro.profile.icon
import kotlinx.coroutines.CoroutineScope

// Fills the feed of the social page: one card (res/layout/item_feed_post.xml) per post of the
// users followed, newest first. The personal bests of their user have the border in the favorite
// color and "PERSONAL BEST". Tap the user to open the profile (onUserClick), the rest of the card
// to see the details of the post (onPostClick). scope: of the page (pictures).
class FeedAdapter(
    private val scope: CoroutineScope,
    private val onUserClick: (SocialUser) -> Unit,
    private val onPostClick: (Post) -> Unit
) : ListAdapter<FeedPost, FeedAdapter.FeedViewHolder>(DIFF) {

    class FeedViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val user: View = view.findViewById(R.id.feedUser)
        val avatar: ImageView = view.findViewById(R.id.feedAvatar)
        val username: TextView = view.findViewById(R.id.feedUsername)
        val time: TextView = view.findViewById(R.id.feedTime)
        val bestBadge: View = view.findViewById(R.id.feedBestBadge)
        val icon: ImageView = view.findViewById(R.id.feedIcon)
        val title: TextView = view.findViewById(R.id.feedTitle)
        val details: TextView = view.findViewById(R.id.feedDetails)
        val value: TextView = view.findViewById(R.id.feedValue)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FeedViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_feed_post, parent, false)
        return FeedViewHolder(view)
    }

    override fun onBindViewHolder(holder: FeedViewHolder, position: Int) {
        val item = getItem(position)
        val post = item.post
        val context = holder.itemView.context

        Avatars.show(holder.avatar, item.user.id, item.user.hasPicture, scope)
        holder.username.text = item.user.username
        holder.time.text = formatTimeAgo(post.createdAt)
        holder.bestBadge.isVisible = item.personalBest
        holder.itemView.setBackgroundResource(
            if (item.personalBest) R.drawable.feed_best_background else R.drawable.list_row_background
        )

        holder.icon.setImageResource(post.icon())
        holder.title.text = post.displayTitle(context)
        val details = post.details(context)
        holder.details.text = details
        holder.details.isVisible = details != null
        holder.value.text = post.displayValue()

        holder.user.setOnClickListener { onUserClick(item.user) }
        holder.itemView.setOnClickListener { onPostClick(post) }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<FeedPost>() {
            override fun areItemsTheSame(old: FeedPost, new: FeedPost) = old.post.id == new.post.id
            override fun areContentsTheSame(old: FeedPost, new: FeedPost) = old == new
        }
    }
}
