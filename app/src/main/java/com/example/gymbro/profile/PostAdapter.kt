package com.example.gymbro.profile

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.gymbro.R
import com.example.gymbro.formatDate
import com.example.gymbro.network.Post

// Fills the POSTS list of a profile: one row (res/layout/item_post.xml) per post, with
// "PERSONAL BEST" on the personal bests of the user. Tap a row to see the details, tap the trash can
// to delete the post (onDelete = null: no trash can, the profile of another user).
class PostAdapter(
    private val onClick: (Post) -> Unit,
    private val onDelete: ((Post) -> Unit)? = null
) : ListAdapter<Post, PostAdapter.PostViewHolder>(POST_DIFF) {

    // The posts that are personal bests (see PersonalBests): set it before submitList
    var personalBestIds: Set<Long> = emptySet()

    class PostViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.postIcon)
        val title: TextView = view.findViewById(R.id.postTitle)
        val date: TextView = view.findViewById(R.id.postDate)
        val bestBadge: View = view.findViewById(R.id.postBestBadge)
        val value: TextView = view.findViewById(R.id.postValue)
        val deleteButton: ImageButton = view.findViewById(R.id.deletePostButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PostViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_post, parent, false)
        return PostViewHolder(view)
    }

    override fun onBindViewHolder(holder: PostViewHolder, position: Int) {
        val post = getItem(position)
        holder.icon.setImageResource(post.icon())
        holder.title.text = post.displayTitle(holder.itemView.context)
        holder.date.text = formatDate(post.createdAt)
        holder.bestBadge.isVisible = post.id in personalBestIds
        holder.value.text = post.displayValue()

        holder.itemView.setOnClickListener { onClick(post) }
        holder.deleteButton.isVisible = onDelete != null
        holder.deleteButton.setOnClickListener { onDelete?.invoke(post) }
    }

    // Shows the posts and their personal bests (all the rows: a post can become or stop being a
    // personal best when another one is published or deleted)
    fun show(posts: List<Post>, bests: Set<Long>, onShown: () -> Unit = {}) {
        val bestsChanged = bests != personalBestIds
        personalBestIds = bests
        submitList(posts) {
            @Suppress("NotifyDataSetChanged")
            if (bestsChanged) notifyDataSetChanged()
            onShown()
        }
    }
}

// Lets the lists of posts animate only the rows that changed
val POST_DIFF = object : DiffUtil.ItemCallback<Post>() {
    override fun areItemsTheSame(old: Post, new: Post) = old.id == new.id
    override fun areContentsTheSame(old: Post, new: Post) = old == new
}
